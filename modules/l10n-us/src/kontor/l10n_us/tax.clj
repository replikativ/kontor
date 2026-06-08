(ns kontor.l10n-us.tax
  "US sales-tax compute — invoicing-side per-line calculation.

   Companion to `kontor.l10n-us.sales-tax` (filing-side reports).
   This namespace is the *callable* compute layer that an invoice
   posting builder reaches for; the filing-side reads posted ledger
   entries against per-state authority tags and emits a report.

   The US has no federal sales tax. Forty-five states + DC + Puerto
   Rico levy state sales tax; five states (AK, DE, MT, NH, OR) do
   not (AK permits local-only). Roughly 13,000 local jurisdictions —
   counties, cities, transit districts, special districts — layer
   their own rates on top of the state rate.

   This is the most fragmented sales-tax landscape on Earth. Per
   ADR-005, the kontor substrate does NOT bundle US sales-tax rate
   data. Production rate resolution is done by an external service:

     - Avalara AvaTax (geocode-based, full taxability matrix)
     - TaxJar (Stripe-owned, SmartCalcs API)
     - SST (Streamlined Sales Tax) member-state rate CSV files —
       freely downloadable for the 24 SST states
     - Vertex Cloud (enterprise tier)
     - In-house: state-DOR rate tables + a geocoder

   This namespace ships:

   - `compute-tax` per-line: caller supplies either `:rate`
     (pre-resolved by Avalara/TaxJar) OR `:rate-table` keyed by
     `[state product-class]` that the helper looks up.
   - `compute-invoice-tax` aggregate over multi-line invoices,
     shipped to a single state.
   - `combined-rate` adds decomposed state/county/city/district
     components into a single effective rate, matching how Avalara
     / SST returns split the breakdown.
   - `sst-states` — set of 24 Streamlined Sales Tax member states
     (useful when the caller's rate provider differs by SST vs
     non-SST jurisdiction).
   - `states-without-state-sales-tax` — the NOMAD-extended five
     (AK / DE / MT / NH / OR).

   Sources (public, non-copyrightable):
     - State DOR rate pages and tax-form filing instructions
     - Streamlined Sales and Use Tax Agreement (SSUTA):
       https://www.streamlinedsalestax.org
     - Wayfair v. South Dakota (2018) — established economic nexus

   ## Out of scope (substrate stance, ADR-005)

   - Live Avalara / TaxJar API integration
   - Per-product taxability matrices (clothing / groceries /
     prescription drugs / SaaS vary state-by-state — too volatile to
     bundle)
   - Origin-vs-destination sourcing resolution (the caller resolves
     this from ship-from / ship-to; compute just applies a rate)
   - Marketplace facilitator pass-through (Amazon / Etsy collect-
     and-remit logic is at the platform tier)"
  (:require [kontor.money :as money]))

;; ============================================================================
;; SST (Streamlined Sales Tax) reference set
;; ============================================================================

(def sst-states
  "Streamlined Sales Tax Agreement member states (24 as of 2026).
   Member states share definitions, sourcing rules, simplified
   audit/registration, and a uniform rate-database publication
   format. A caller that pulls rates from the SST CSV (or the
   centralized rates-and-boundaries service) can rely on identical
   schema across these states.

   Tennessee is a long-time associate member; the set evolves
   slowly — roughly stable since 2008 with occasional adds/drops."
  #{:AR :GA :IN :IA :KS :KY :MI :MN :NE :NV :NJ :NC
    :ND :OH :OK :RI :SD :TN :UT :VT :WA :WV :WI :WY})

(def states-without-state-sales-tax
  "The five US states that levy no state sales tax (NOMAD: New
   Hampshire, Oregon, Montana, Alaska, Delaware). Alaska permits
   local-jurisdiction (city/borough) taxes administered by the
   Alaska Remote Seller Sales Tax Commission, so an :AK sale may
   still incur tax — but the *state* rate is zero."
  #{:AK :DE :MT :NH :OR})

;; ============================================================================
;; Compute helpers
;; ============================================================================

(defn- bd
  "Coerce a BigDecimal / number / Money input to BigDecimal."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN, wrap as Money :USD. HALF-EVEN
   matches the kernel default; some state DORs prefer HALF-UP for
   filing-side aggregation, but invoice-line tax is conventionally
   HALF-EVEN (banker's rounding)."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :USD))

(defn- m-zero [] (money/zero :USD))

(defn combined-rate
  "Sum decomposed sales-tax components into a single effective rate.

   Per real Avalara / TaxJar payloads, a rate is typically decomposed
   into per-authority components: state + county + city + special
   district + transit-authority + improvement-district. Each is
   itself a non-negative BigDecimal (e.g. 0.0625 = 6.25%).

   This helper sums whatever components the caller hands it. It
   does not validate the labels — pass `{:state 0.029 :city 0.0581
   :rtd 0.011}` for a Denver-style decomposition, or
   `{:state 0.06}` for a flat state-only rate.

   Returns a BigDecimal."
  [components]
  (reduce (fn [^java.math.BigDecimal acc [_label rate]]
            (.add acc (bd rate)))
          0M components))

(defn- resolve-rate
  "Pick the applicable rate for one line.

   Priority:
     1. Explicit `:rate` wins (caller already resolved).
     2. `:rate-table` lookup keyed by `[state product-class]`, then
        falling back to `[state :default]`.
     3. Nothing — throw, because production rate resolution must be
        explicit (no surprise zeroes)."
  ^java.math.BigDecimal
  [{:keys [rate state product-class rate-table]}]
  (cond
    rate (bd rate)
    rate-table
    (or (when-let [r (get rate-table [state (or product-class :default)])]
          (bd r))
        (when-let [r (get rate-table [state :default])]
          (bd r))
        (throw (ex-info "No rate-table entry for this [state product-class]"
                        {:state state
                         :product-class product-class
                         :rate-table-keys (vec (keys rate-table))})))
    :else
    (throw (ex-info "compute-tax needs either :rate or :rate-table" {}))))

(def tax-statuses
  "Valid `:tax-status` values for compute-tax input. Default
   `:taxable` — the resolved rate applies.

     :taxable             normal taxable sale at the resolved rate
     :resale              buyer presented a resale certificate — no
                          tax (the buyer will collect when they
                          resell)
     :exempt              non-profit / government / educational
                          exemption — no tax
     :non-taxable-product e.g. unprepared food in many states;
                          treated as zero rate

   Production callers with a richer taxability matrix can pre-resolve
   to `:resale` / `:exempt` themselves and pass that in; the substrate
   doesn't try to know which product categories are exempt in which
   state."
  #{:taxable :resale :exempt :non-taxable-product})

(defn- assert-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status :valid tax-statuses})))
  status)

;; ============================================================================
;; Public compute API
;; ============================================================================

(defn compute-tax
  "Compute sales tax for one invoice line. PURE — no DB reads.

   Required:
     :line   BigDecimal | Money | number — net taxable amount
     :state  US state code as keyword (`:CA`, `:NY`, `:TX`, …)
              or `:DC` / `:PR` for the District / Puerto Rico

   Rate resolution (provide ONE):
     :rate         pre-resolved BigDecimal (Avalara / TaxJar already
                   computed the effective combined rate). Wins over
                   :rate-table.
     :rate-table   `{[state product-class] rate, ...}` map. The
                   helper looks up `[state product-class]`, then
                   falls back to `[state :default]`. Use this when
                   the caller carries a small in-house rate table.

   Optional:
     :tax-status     `:taxable` (default), `:resale`, `:exempt`,
                     `:non-taxable-product`
     :product-class  free-form keyword (`:default`, `:clothing`,
                     `:food`, `:saas`, …). Drives the rate-table
                     lookup; meaningless when `:rate` is given.
     :jurisdictions  vector of `{:authority <kw> :rate <bd> :amount
                     <Money>}` to attach for downstream filing-side
                     splits. Useful when the caller has Avalara's
                     per-authority breakdown and wants it preserved.

   Returns:
     {:tax-rate          BigDecimal effective rate (zero on resale /
                         exempt)
      :tax-amount        Money :USD (rounded HALF-EVEN to 2dp)
      :net               Money :USD
      :total-gross       Money :USD (net + tax-amount)
      :state             keyword
      :tax-status        keyword
      :tax-jurisdictions vector (echo of input or nil)}

   For `:resale`, `:exempt`, and `:non-taxable-product` lines, the
   tax-rate is 0M and tax-amount is Money 0 :USD regardless of
   `:rate` / `:rate-table`. The total-gross equals the net.

   Examples (with a hand-supplied rate):
     (compute-tax {:line 1000M :state :CA :rate 0.0725M})
       → {:tax-rate 0.0725M :tax-amount 72.50 …}

     (compute-tax {:line 100M :state :NY
                   :rate-table {[:NY :default]  0.08875M
                                [:NY :clothing] 0.0M}
                   :product-class :clothing})
       → {:tax-rate 0.0M :tax-amount 0.00 …}

     (compute-tax {:line 500M :state :OR :rate 0M})
       → {:tax-rate 0M :tax-amount 0.00 …}  ; Oregon — no sales tax

     (compute-tax {:line 500M :state :CA :rate 0.0725M
                   :tax-status :resale})
       → {:tax-rate 0M :tax-amount 0.00 …}  ; resale-cert override

   Production note: the kontor substrate does NOT bundle a default
   rate table. You MUST plug in a TaxRateProvider (Avalara, TaxJar,
   SST CSV adapter) at the consumer tier."
  [{:keys [line state tax-status jurisdictions]
    :or {tax-status :taxable}
    :as input}]
  (assert-status! tax-status)
  (when-not (keyword? state)
    (throw (ex-info ":state must be a keyword (e.g. :CA)"
                    {:state state})))
  (let [net-bd (bd line)
        net-m  (m-cents net-bd)
        zero   (m-zero)
        nontaxable? (contains? #{:resale :exempt :non-taxable-product} tax-status)]
    (if nontaxable?
      {:tax-rate          0M
       :tax-amount        zero
       :net               net-m
       :total-gross       net-m
       :state             state
       :tax-status        tax-status
       :tax-jurisdictions jurisdictions}
      (let [rate  (resolve-rate input)
            tax   (m-cents (.multiply net-bd rate))
            gross (money/add net-m tax)]
        {:tax-rate          rate
         :tax-amount        tax
         :net               net-m
         :total-gross       gross
         :state             state
         :tax-status        tax-status
         :tax-jurisdictions jurisdictions}))))

(defn compute-invoice-tax
  "Aggregate tax over a sequence of invoice lines, all shipped to the
   same `:state`. Each line is `{:line <amount> :tax-status <kw>?
   :rate <bd>? :product-class <kw>?}`. The caller can either supply
   per-line `:rate`s OR a single `:rate-table` at the top level that
   the helper threads through (per-line `:rate` still wins).

   Returns:
     {:tax-amount   Money :USD  ; sum across lines
      :net          Money :USD
      :total-gross  Money :USD
      :state        keyword
      :per-line     vector of per-line compute-tax results}

   Tax totals are `sum-of-rounded-line-amounts` (each line rounded
   to 2dp first, then added). This matches state-DOR tolerance and
   how most invoice-line tax engines aggregate."
  [{:keys [lines state rate-table]}]
  (when-not (keyword? state)
    (throw (ex-info ":state must be a keyword (e.g. :CA)"
                    {:state state})))
  (let [per-line (mapv (fn [l]
                         (compute-tax
                          (cond-> (assoc l :state state)
                            (and rate-table (not (:rate-table l)))
                            (assoc :rate-table rate-table))))
                       lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [tax-amount net total-gross]}]
                (-> acc
                    (update :tax-amount money/add tax-amount)
                    (update :net money/add net)
                    (update :total-gross money/add total-gross)))
              {:tax-amount zero :net zero :total-gross zero}
              per-line)]
    (assoc sums
           :state state
           :per-line per-line)))

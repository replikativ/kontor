(ns kontor.tax.tax-rate-provider
  "`TaxRateProvider` — ADR-071. The rate-determination half of the tax
   abstraction: given a transaction-line context, return `TaxFacts` —
   pure data describing what tax applies, at what rate, on what base.

   This namespace supersedes the legacy single-protocol
   `kontor.tax-provider/TaxProvider` (ADR-005), which conflated
   rate-determination with posting-expansion and was consequently
   unused. ADR-071 splits the concern in three:

     - `TaxRateProvider`   — rates → `TaxFacts`               (this ns)
     - `TaxFacts`          — pure data, the inter-protocol contract
     - `TaxPostingBuilder` — `TaxFacts` → GL postings  (kontor.tax.tax-posting-builder)

   A rate provider needs NO chart of accounts; a posting builder needs
   NO rate table. `TaxFacts` is the seam.

   ## TaxFacts

   `TaxFacts` is a record (constructed via `tax-facts`) with:

     :tax-use    — :sale | :purchase | :none — the discriminator that
                   makes `:reverse-charge` and `:withholding` mean
                   different things downstream (ADR-071).
     :line-base  — the pre-tax base amount (BigDecimal).
     :commodity  — the commodity ref the base is denominated in.
     :jurisdiction — {:country <cc> :subdivision <s|nil>
                      :place-of-supply <p|nil>}.
     :components — a vector of component maps, one per applicable tax.

   Each component map:

     :kind        — :output-vat | :input-vat | :sales-tax |
                    :reverse-charge | :withholding | :pre-collection |
                    :surcharge | :cess | :duty | :fee  (ADR-071 enum).
     :rate        — the rate (0.19M for 19%; absolute for :fixed).
     :base        — the base this component was computed from.
     :amount      — the computed tax amount (BigDecimal, unsigned —
                    the posting builder applies the sign per :tax-use).
     :recoverable? — passthrough of :kontor.tax/recoverable?.
     :tax-eid     — the backing :tax entity (the posting builder walks
                    its :tax-rep repartition lines).
     :tax-code    — the :kontor.tax/code, for provenance + logs.
     :provenance  — {:provider-id <kw> :rate-source <str>
                     :statute <str|nil>}.
     :jurisdiction — per-component {:authority :subdivision}, for
                     multi-authority transactions (US, BR DIFAL). nil
                     = inherit the line :jurisdiction.
     :jurisdiction-specific-codes — opaque map the kernel never
                     interprets (e.g. {:br/icms-cst \"60\"}); einvoice
                     / clearance emitters consume it.

   ## Out of scope for the substrate

   Avalara / TaxJar / SST adapters are throwing scaffolds (customers
   hold their own keys — ADR-005). Per-l10n migration of the 11
   country modules is consumer-demand-driven (ADR-071 Implication).
   The `:tax-fact/*` audit-snapshot entity is a deferred follow-up."
  (:require [datahike.api :as d]
            [kontor.tax.fiscal-position :as fp])
  (:import [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; The component-kind enum (ADR-071)
;; ============================================================================

(def component-kinds
  "The closed set of `:kind` values a `TaxFacts` component may carry."
  #{:output-vat :input-vat :sales-tax :reverse-charge :withholding
    :pre-collection :surcharge :cess :duty :fee})

;; ============================================================================
;; TaxFacts — the inter-protocol data contract
;; ============================================================================

(defrecord TaxFacts [tax-use line-base commodity jurisdiction components])

(defn tax-facts
  "Construct a `TaxFacts`. `components` is a vector of component maps
   (see the ns docstring)."
  [{:keys [tax-use line-base commodity jurisdiction components]}]
  (->TaxFacts tax-use line-base commodity jurisdiction (vec components)))

(defn taxable?
  "True when `facts` carries at least one component (i.e. some tax
   applies). `nil` facts — the no-tax case — are not taxable."
  [facts]
  (boolean (and facts (seq (:components facts)))))

(defn total-tax
  "Sum of every component's (unsigned) `:amount` in `facts` — the
   gross notional tax. NOTE: this is *not* the cash-leg adjustment
   when withholding or reverse charge is present — use `net-tax-effect`
   for that."
  [facts]
  (reduce (fn [acc c] (+ acc (:amount c 0M))) 0M (:components facts)))

;; ----------------------------------------------------------------------------
;; The netting contract (ADR-071 addendum / research note 101)
;;
;; A component's :amount affects the counterparty cash leg (AR on a
;; sale, AP on a purchase) of the transaction the tax sits on in one
;; of three ways. A consumer sizes that leg as `net + net-tax-effect`.
;; ----------------------------------------------------------------------------

(def kind-effect
  "How a component's `:amount` affects the counterparty cash leg:
     :additive — adds to the gross (VAT, sales tax, cess, duty, fee,
                 surcharge, pre-collection)
     :withheld — subtracts from it (withholding — a contra deduction)
     :neutral  — no effect (reverse charge — the buyer-side legs net
                 to zero; the seller-side marker is no GL leg)."
  {:output-vat     :additive  :input-vat      :additive
   :sales-tax      :additive  :cess           :additive
   :duty           :additive  :fee            :additive
   :surcharge      :additive  :pre-collection :additive
   :withholding    :withheld
   :reverse-charge :neutral})

(defn- sum-where-effect [facts effect]
  (reduce (fn [acc c]
            (if (= effect (kind-effect (:kind c)))
              (+ acc (:amount c 0M))
              acc))
          0M
          (:components facts)))

(defn additive-total
  "Σ of `:amount` over the components that *add* to the gross."
  [facts]
  (sum-where-effect facts :additive))

(defn withheld-total
  "Σ of `:amount` over the `:withholding` components — the part
   *subtracted* from the counterparty cash leg."
  [facts]
  (sum-where-effect facts :withheld))

(defn net-tax-effect
  "The signed amount a consumer adds to the pre-tax net to size the
   counterparty cash leg: `additive-total − withheld-total`. For pure
   VAT this equals `total-tax`; with withholding it is correctly
   smaller; reverse charge contributes nothing (its legs self-net)."
  [facts]
  (- (additive-total facts) (withheld-total facts)))

(defn valid-tax-facts?
  "Structural / closed-vocabulary check (validation layer 1, note 101):
   `facts` is a `TaxFacts` whose every component carries a `:kind` from
   the closed `component-kinds` set and a BigDecimal `:amount`. A
   `:kind` outside the set means a provider has outrun the vocabulary —
   the signal to extend the enum by ADR, not to special-case."
  [facts]
  (and (instance? TaxFacts facts)
       (every? (fn [c]
                 (and (contains? component-kinds (:kind c))
                      (decimal? (:amount c))))
               (:components facts))))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol TaxRateProvider
  "Resolve the tax that applies to one transaction line.

   A rate provider is rate-determination only — it never touches the
   chart of accounts. Its output, `TaxFacts`, is consumed by a
   `kontor.tax.tax-posting-builder/TaxPostingBuilder` to materialize GL
   postings."
  (provider-id [this]
    "A keyword identifying the implementation — :static-table,
     :avalara, :taxjar, :sst-csv, :chained — used in `:provenance`
     and logs.")
  (rate-facts [this context]
    "Given a single-line `context`, return a `TaxFacts` (or `nil`
     when no tax applies).

     Context keys:
       :base          — pre-tax base amount (number / BigDecimal)
       :commodity     — commodity ref the base is in
       :country-code  — ISO-3166 alpha-2 of the place of supply
       :tax-use       — :sale | :purchase | :none
       :at            — bitemporal valid-time of the transaction (Date)
       :subdivision   — optional state/province code
       :place-of-supply — optional
       :fiscal-position — optional `:kontor.fiscal-position` (eid or name).
                        Substitutes or drops the taxes the jurisdiction
                        rules resolved — an EU B2B customer's position turns
                        the domestic VAT into an intra-community reverse
                        charge. See `kontor.tax.fiscal-position`.
       :price-include — optional boolean. True when `:base` is a
                        tax-INCLUSIVE gross, so the provider extracts the
                        pre-tax net instead of taxing the gross. Overrides
                        the per-tax `:kontor.tax/price-include` default.
       :db            — optional db override (else the provider reads
                        its own connection)

     Never returns a partially-built TaxFacts: either a complete one
     or `nil`."))

;; ============================================================================
;; StaticTableProvider — reads the :kontor.tax/* schema (ADR-071 Implication 2)
;; ============================================================================

(defn- effective?
  "True when `at` falls within the tax entity's effective window.
   nil bounds are open (ADR-071 P2-71-1)."
  [tax ^java.util.Date at]
  (let [from  (:kontor.tax/effective-from tax)
        until (:kontor.tax/effective-until tax)]
    (and (or (nil? from)  (not (.before at ^java.util.Date from)))
         (or (nil? until) (.before at ^java.util.Date until)))))

(defn- component-kind
  "Map a `:tax` entity + `:tax-use` to a `TaxFacts` component `:kind`.
   `:kontor.tax/mechanism` (ADR-071 addendum / note 101) wins when set:
   `:reverse-charge` and `:withholding` are mechanism-determined. For
   `:standard` (or absent) recoverable VAT is input/output by use, and
   a non-recoverable tax becomes cost and is reported as `:sales-tax`."
  [tax tax-use]
  (case (:kontor.tax/mechanism tax)
    :reverse-charge :reverse-charge
    :withholding    :withholding
    ;; :standard or absent
    (cond
      (not (:kontor.tax/recoverable? tax)) :sales-tax
      (= tax-use :sale)             :output-vat
      (= tax-use :purchase)         :input-vat
      :else                         :sales-tax)))

(defn- component-amount
  "Compute the (unsigned) tax amount for a tax entity against `base`.
   Supports `:percent` and `:fixed`; `:group` / `:division` return nil
   (the static provider does not expand tax groups — a per-l10n
   provider handles those)."
  [tax ^BigDecimal base]
  (let [rate (:kontor.tax/amount tax)]
    (case (:kontor.tax/amount-type tax)
      :percent (.multiply base ^BigDecimal rate)
      :fixed   rate
      nil)))

(defn- computable?
  "True when `component-amount` can produce a number for this tax."
  [tax]
  (and (:kontor.tax/amount tax)
       (#{:percent :fixed} (:kontor.tax/amount-type tax))))

;; ----------------------------------------------------------------------------
;; Compound ordering (note 198 R3-FP-03)
;;
;; `:kontor.tax/include-base-amount` promises that a tax folds its amount into
;; the base of *subsequent* taxes — which is meaningless without an order. The
;; resolver's `d/q` returns an unordered set, so before this the answer
;; depended on set iteration. Sort by `:kontor.tax/sequence` (absent = 0),
;; breaking ties on `:kontor.tax/code` so the result is total and stable.
;; ----------------------------------------------------------------------------

(defn- tax-order [tax]
  [(or (:kontor.tax/sequence tax) 0) (str (:kontor.tax/code tax))])

(defn- forward-pass
  "Walk `taxes` (already ordered) accumulating the compound base, and return a
   vector of `{:base :amount}` aligned with them.

   A tax with `:include-base-amount` adds its amount to the running base seen
   by later taxes: a 10% excise then a 19% VAT on a net of 100 gives
   excise 10.00 on base 100 and VAT 20.90 on base 110 — not 19.00 on 100."
  [taxes ^BigDecimal net]
  (loop [ts taxes, running net, acc []]
    (if-let [t (first ts)]
      (let [amt ^BigDecimal (component-amount t running)]
        (recur (rest ts)
               (if (:kontor.tax/include-base-amount t) (.add running amt) running)
               (conj acc {:base running :amount amt})))
      acc)))

(defn- extract-net
  "Solve for the pre-tax net given a tax-INCLUSIVE `gross` (note 198 R3-FP-02).

   `gross = net + Σ amount_i(net)` over the price-INCLUDED taxes only —
   excluded taxes are charged on top of the quoted price and are not part of
   it. Every `amount_i` is affine in `net` (percent taxes scale it, fixed
   taxes add a constant, and compounding composes affine maps), so evaluating
   the forward pass at net=1 and net=0 recovers the slope and intercept and
   the inversion is exact:

     gross = net·(1 + a) + b   ⟹   net = (gross − b) / (1 + a)

   This is why the naive `gross / (1 + rate)` is not enough: it is only
   correct for a single non-compound percent tax, and silently wrong the
   moment a second included tax or a fixed levy joins the chain.

   `included?` is a predicate over the tax entity; `scale` is the commodity's
   precision. Rounding is HALF-EVEN per the money convention."
  [taxes included? ^BigDecimal gross ^long scale]
  (let [sum-included (fn [^BigDecimal n]
                       (->> (map vector taxes (forward-pass taxes n))
                            (keep (fn [[t r]] (when (included? t) (:amount r))))
                            (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b))
                                    BigDecimal/ZERO)))
        at-one  (sum-included BigDecimal/ONE)
        at-zero (sum-included BigDecimal/ZERO)
        slope   (.subtract ^BigDecimal at-one ^BigDecimal at-zero)]
    (.divide (.subtract gross ^BigDecimal at-zero)
             (.add BigDecimal/ONE slope)
             scale
             RoundingMode/HALF_EVEN)))

(defn- commodity-precision
  "Decimal places for `commodity` (eid, lookup-ref, or nil). Defaults to 2 —
   the overwhelmingly common case and a safe floor for an unresolvable ref."
  [db commodity]
  (or (when commodity
        (try (:kontor.commodity/precision
              (d/pull db [:kontor.commodity/precision] commodity))
             (catch Exception _ nil)))
      2))

(defrecord StaticTableProvider [conn opts]
  TaxRateProvider
  (provider-id [_] :static-table)
  (rate-facts [_ {:keys [base commodity country-code tax-use at db
                         subdivision place-of-supply
                         fiscal-position price-include]}]
    (let [db   (or db (d/db conn))
          at   (or at (java.util.Date.))
          base (bigdec base)
          cc   (or country-code (:default-country-code opts))
          resolved (d/q '[:find [?t ...]
                          :in $ ?cc ?use
                          :where
                          [?t :kontor.tax/country-code ?cc]
                          [?t :kontor.tax/type-tax-use ?use]
                          [?t :kontor.tax/active true]]
                        db cc tax-use)
          ;; The customer's fiscal position substitutes (or drops) taxes the
          ;; jurisdiction rules already selected — it never adds new ones.
          tax-eids (fp/map-taxes db fiscal-position resolved)
          taxes    (->> tax-eids
                        (map #(d/pull db '[*] %))
                        (filter #(effective? % at))
                        (filter computable?)
                        (sort-by tax-order)
                        vec)
          ;; A per-line :price-include in the context overrides the tax's own
          ;; attr: the same tax is quoted gross to consumers and net to
          ;; businesses, so inclusiveness is a property of the QUOTE, with the
          ;; tax attr as the default.
          included? (fn [tax] (if (some? price-include)
                                (boolean price-include)
                                (boolean (:kontor.tax/price-include tax))))
          net (if (some included? taxes)
                (extract-net taxes included? base
                             (commodity-precision db commodity))
                base)
          results (forward-pass taxes net)
          components
          (vec (for [[tax r] (map vector taxes results)]
                 {:kind         (component-kind tax tax-use)
                  :rate         (:kontor.tax/amount tax)
                  :base         (:base r)
                  :amount       (:amount r)
                  :recoverable? (boolean (:kontor.tax/recoverable? tax))
                  :tax-eid      (:db/id tax)
                  :tax-code     (:kontor.tax/code tax)
                  :price-include (included? tax)
                  :provenance   {:provider-id :static-table
                                 :rate-source (:kontor.tax/code tax)
                                 :statute     nil}
                  :jurisdiction (when (:kontor.tax/authority tax)
                                  {:authority (:kontor.tax/authority tax)})
                  :jurisdiction-specific-codes {}}))]
      (when (seq components)
        (tax-facts {:tax-use      tax-use
                    ;; the PRE-tax net, which differs from the incoming
                    ;; `base` exactly when the quote was tax-inclusive
                    :line-base    net
                    :commodity    commodity
                    :jurisdiction {:country         cc
                                   :subdivision     subdivision
                                   :place-of-supply place-of-supply}
                    :components   components})))))

(defn make-static-table-provider
  "Construct a `StaticTableProvider` over `conn`. Options:
     :default-country-code — fallback when a context omits
                             `:country-code` (default \"DE\")."
  ([conn] (make-static-table-provider conn {}))
  ([conn opts]
   (->StaticTableProvider conn (merge {:default-country-code "DE"} opts))))

;; ============================================================================
;; Scaffolds — customers hold their own keys; we never bundle them
;; (ADR-005, preserved by ADR-071 Implication 3). Names migrated from
;; the legacy kontor.tax-provider per ADR-071 Implication 2.
;; ============================================================================

(defrecord AvalaraProvider [api-key opts]
  TaxRateProvider
  (provider-id [_] :avalara)
  (rate-facts [_ _context]
    (throw (ex-info "AvalaraProvider not implemented — customers register their own API key (ADR-071, preserving ADR-005's no-bundled-keys posture)"
                    {:provider :avalara}))))

(defrecord TaxJarProvider [api-key opts]
  TaxRateProvider
  (provider-id [_] :taxjar)
  (rate-facts [_ _context]
    (throw (ex-info "TaxJarProvider not implemented — customers register their own API key (ADR-071, preserving ADR-005's no-bundled-keys posture)"
                    {:provider :taxjar}))))

(defrecord SstCsvProvider [csv-dir opts]
  TaxRateProvider
  (provider-id [_] :sst-csv)
  (rate-facts [_ _context]
    (throw (ex-info "SstCsvProvider not implemented — US SST CSV ingest is a kontor-l10n-us concern"
                    {:provider :sst-csv :csv-dir csv-dir}))))

;; ============================================================================
;; ChainedProvider — first non-nil result wins
;; ============================================================================

(defrecord ChainedProvider [providers]
  TaxRateProvider
  (provider-id [_] :chained)
  (rate-facts [_ context]
    (some (fn [p] (rate-facts p context)) providers)))

(defn chain
  "A `ChainedProvider` that tries each provider in order, returning the
   first non-nil `TaxFacts`. Useful when some jurisdictions are
   static-table-covered and others need a paid API."
  [& providers]
  (->ChainedProvider (vec providers)))

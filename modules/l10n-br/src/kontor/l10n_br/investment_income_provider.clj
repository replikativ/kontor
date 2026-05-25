(ns kontor.l10n-br.investment-income-provider
  "BR investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 155.

   Covers the four orthogonal BR pillars (note 155 §1):

   - **Dividend WHT (Lei 15.270/2025)** — PF resident 10 % IRRF on
     monthly distributions > R$ 50k from the same payer; PF non-
     resident 10 % flat on any cross-border distribution; PJ-to-PJ
     exemption preserved. Pre-2026 PF dividends EXEMPT.
   - **IRPFM (Lei 15.270/2025 art. 9-13)** — 10 % high-earner minimum
     tax with linear ramp 0%-10% across R$ 600k-R$ 1.2M annual income;
     credits against ordinary IRPF + dividend IRRF + JCP IRRF + foreign
     tax credit.
   - **JCP IRRF (PLP 128/2025)** — 17.5 % from 2026-01-01 (15 % pre).
   - **Renda fixa regressive** — 22.5/20/17.5/15 % by holding-period
     bucket; FII PF exemption preserved (≥100 cotistas + ≤10 % stake +
     listed).

   ## Two callable shapes

   - `:kind :individual` — emits up to four components by pillar.
   - `:kind :corporation` — PJ-to-PJ dividends EXEMPT (no component);
     foreign-source dividends fold into CIT base via `:cit-base-
     additions`.

   ## Per-payer aggregation (R$ 50k/month trigger)

   The trigger is `(payer × recipient × month)`. The consumer supplies
   the per-payer per-month dividend ledger via
   `:inputs :br-dividend-per-payer-per-month` as a vector of
   `{:payer :recipient :year :month :amount :foreign? :grandfathered?}`
   entries; the provider folds them — any (payer, recipient, month)
   triple > R$ 50k → 10 % IRRF on the FULL amount of that triple;
   cross-border (`:foreign? true`) → 10 % flat regardless of amount;
   `:grandfathered? true` → no IRRF (2025 AGM-approved profits per
   Lei 15.270/2025 art. 15 transition).

   The substrate `book.declare-dividend!` write produces these
   transactions; the consumer extracts them via marginalize (a future
   helper `kontor.l10n-br/dividend-aggregates` can derive the input
   shape from postings — out of scope for v1).

   ## IRPFM credit-against-ordinary-IRPF

   The IRPFM credits against the ORDINARY IRPF — the consumer wires
   `br-period-tax-provider` for IRPF FIRST, then passes its output as
   `:inputs :br-ordinary-irpf-paid` to this provider. Within this
   provider, the dividend IRRF and JCP IRRF this same call computed
   ALSO credit against the IRPFM (cross-provider on the SAME assessed
   period). The provider does the netting in one pass.

   See note 155 §3.3.

   ## Renda fixa

   The consumer supplies per-disposal summaries via
   `:inputs :br-renda-fixa` — vector of `{:holding-days :gain}`. The
   provider buckets by holding-days into one of four flat-rate buckets.
   FII distributions land via `:inputs :br-fii-distributions` — vector
   of `{:amount :conditions-met?}`; when `:conditions-met?` is truthy
   the distribution is exempt (no component), otherwise it falls
   through to the renda-fixa lane as taxable (provider-side
   conservative default).

   ## What this namespace is NOT

   Not a DAA/DCTFWeb emitter; not a renda-fixa broker-event ingester
   (a future `kontor-broker-br-b3` companion will derive the per-
   disposal summaries from B3 ledger). Treaty positions on cross-border
   dividends are consumer-side overrides via `:adjustment-items`."
  (:require [kontor.l10n-br.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Constants
;; ============================================================================

(def kinds
  "The closed set of `:kind` values the provider's constructor accepts."
  #{:individual :corporation})

(def lanes
  "The closed set of BR investment-income lanes a component classifies
   into. Mirrors note 155 §5.1."
  #{:br-dividend-irrf       ; Pillar 1 — R$50k/month + cross-border
    :br-irpfm                ; Pillar 2 — high-earner floor
    :br-jcp-irrf             ; Pillar 3 — 17.5 % JCP WHT
    :br-renda-fixa           ; Pillar 4 — regressive table
    :br-corp-foreign-div})   ; Corp lane — folds to CIT

;; ============================================================================
;; Utilities
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code as-of]
  (statute/parameter-value-at db code as-of))

(defn- bucket-for-holding-days
  "Map holding-days → renda-fixa bucket index (1-4) per Lei 11.033/2004
   art. 1. ≤180=1, 181-360=2, 361-720=3, >720=4."
  [^java.math.BigDecimal days]
  (cond
    (<= (compare days 180M) 0)     1
    (<= (compare days 360M) 0)     2
    (<= (compare days 720M) 0)     3
    :else                          4))

(defn- bucket-rate
  ^java.math.BigDecimal [db ^long bucket ^java.util.Date as-of]
  (param db (str "BR.INV.renda-fixa.rate-bucket-" bucket) as-of))

;; ============================================================================
;; Pillar 1 — dividend IRRF: per-payer per-month aggregation
;; ============================================================================

(defn- compute-dividend-irrf
  "Fold the consumer-supplied per-payer per-month dividend ledger into
   per-(payer × recipient × month) totals, then apply the trigger:

     - `:grandfathered? true` → 0 (skip).
     - `:foreign? true` → 10 % IRRF on the entry's full amount,
       regardless of monthly total.
     - PF resident, monthly per-payer total > R$ 50 000 → 10 % IRRF on
       the FULL monthly total (not just the excess), per art. 6.

   Returns `{:irrf <bd> :triggered <vector-of-entries-with-:irrf>}`."
  [db ^java.util.Date as-of entries]
  (let [pf-rate    (or (param db "BR.INV.PF.dividend-irrf-rate" as-of)    0M)
        nr-rate    (or (param db "BR.INV.NR.dividend-irrf-rate" as-of)    0M)
        trigger    (or (param db "BR.INV.PF.dividend-monthly-trigger" as-of) 50000M)
        ;; Split out grandfathered + foreign entries (foreign fires
        ;; per-entry regardless of monthly cap).
        kept       (remove :grandfathered? entries)
        {:keys [foreign domestic]}
        (group-by #(if (:foreign? %) :foreign :domestic) kept)
        ;; Foreign — 10 % flat per entry.
        foreign-rows
        (mapv (fn [e]
                (let [irrf (* (or (:amount e) 0M) nr-rate)]
                  (assoc e :irrf irrf :basis :cross-border)))
              foreign)
        ;; Domestic — group by (payer × recipient × month); each cell
        ;; whose total > trigger fires 10 % on the FULL monthly total.
        domestic-rows
        (let [grouped (group-by (juxt :payer :recipient :year :month) domestic)]
          (vec
           (mapcat
            (fn [[[payer recipient year month] es]]
              (let [monthly-total (reduce + 0M (map :amount es))]
                (if (> (compare monthly-total trigger) 0)
                  (mapv (fn [e]
                          (let [share (if (pos? monthly-total)
                                        (/ (:amount e) monthly-total)
                                        0M)
                                irrf  (* monthly-total pf-rate share)]
                            (assoc e
                                   :payer payer
                                   :recipient recipient
                                   :year year
                                   :month month
                                   :monthly-total monthly-total
                                   :irrf irrf
                                   :basis :pf-over-trigger)))
                        es)
                  ;; Below trigger → no IRRF; entry kept for audit.
                  (mapv (fn [e]
                          (assoc e
                                 :monthly-total monthly-total
                                 :irrf 0M
                                 :basis :pf-under-trigger))
                        es))))
            grouped)))
        all-rows   (vec (concat foreign-rows domestic-rows))
        total-irrf (reduce + 0M (map :irrf all-rows))]
    {:irrf       total-irrf
     :triggered  all-rows}))

(defn- dividend-irrf-component
  "Pillar 1 component — emits ONLY when at least one entry triggered
   (any non-zero :irrf). Carries every entry as a line-item for audit."
  [{:keys [commodity authority]} ^java.math.BigDecimal irrf-total triggered]
  (let [taxed-rows (filterv #(pos? (or (:irrf %) 0M)) triggered)
        base       (reduce + 0M (map :amount taxed-rows))]
    {:kind            :investment-income-tax
     :authority       authority
     :composed-of     [:br-dividend-irrf]
     :base            (money/money base commodity)
     :schedule        nil
     :gross-liability (money/money irrf-total commodity)
     :liability       (money/money irrf-total commodity)
     :prepaid         (money/money irrf-total commodity)  ; IRRF = source-withheld; closes against itself
     :line-items
     (into [{:line :dividend-irrf-total
             :label "Pillar 1 — total dividend IRRF (10 %)"
             :value (money/money irrf-total commodity)}]
           (mapv (fn [r]
                   {:line  (case (:basis r)
                             :cross-border       :div-irrf-cross-border
                             :pf-over-trigger    :div-irrf-pf-over-50k
                             :pf-under-trigger   :div-irrf-pf-under-50k
                             :div-irrf-other)
                    :label (format "%s — payer=%s recipient=%s %s/%s monthly-total=%s amount=%s"
                                   (str (name (or (:basis r) :other)))
                                   (str (:payer r))
                                   (str (:recipient r))
                                   (str (:year r))
                                   (str (:month r))
                                   (str (:monthly-total r))
                                   (str (:amount r)))
                    :value (money/money (or (:irrf r) 0M) commodity)})
                 triggered))
     :jurisdiction-specific-codes {:lane         :br-dividend-irrf
                                   :darf         9999     ; pending RFB IN (note 155 §5.4)
                                   :enacted-by   "Lei 15.270/2025 art. 6-7"}}))

;; ============================================================================
;; Pillar 2 — IRPFM: parametric-on-base linear ramp (the first formula schedule)
;; ============================================================================

(defn- irpfm-effective-rate
  "Compute the IRPFM effective rate via the linear ramp 0 % → top-rate
   across `[band-low band-high]`. Returns the effective rate as a
   BigDecimal — caller multiplies by the base.

   Linear: `max(0, min(top, (income - band-low) / (band-high - band-low) × top))`."
  ^java.math.BigDecimal
  [^java.math.BigDecimal income
   ^java.math.BigDecimal band-low
   ^java.math.BigDecimal band-high
   ^java.math.BigDecimal top-rate]
  (cond
    (<= (compare income band-low) 0)  0M
    (>= (compare income band-high) 0) top-rate
    :else
    (let [span (- band-high band-low)]
      (if (zero? span)
        top-rate
        (with-precision 34
          (* top-rate (/ (- income band-low) span)))))))

(defn- irpfm-schedule
  "Build the `:formula` schedule for IRPFM — the first kontor schedule
   to use `(ts/formula …)` for parametric-on-base rate. The fn closes
   over the per-period band-low / band-high / top-rate parameters."
  [^java.math.BigDecimal band-low
   ^java.math.BigDecimal band-high
   ^java.math.BigDecimal top-rate]
  {:schedule/type :formula
   :fn (fn [base _ctx]
         (let [eff (irpfm-effective-rate base band-low band-high top-rate)]
           (* base eff)))})

(defn- compute-irpfm
  "Returns `{:floor :credits :payable :effective-rate :base}` for the
   IRPFM given the consumer's annual income and credit inputs."
  [db ^java.util.Date as-of
   ^java.math.BigDecimal income
   ^java.math.BigDecimal ordinary-irpf
   ^java.math.BigDecimal dividend-irrf
   ^java.math.BigDecimal jcp-irrf
   ^java.math.BigDecimal foreign-credit]
  (let [band-low  (or (param db "BR.INV.IRPFM.band-low"  as-of)  600000M)
        band-high (or (param db "BR.INV.IRPFM.band-high" as-of) 1200000M)
        top-rate  (or (param db "BR.INV.IRPFM.top-rate"  as-of)    0.10M)
        eff-rate  (irpfm-effective-rate income band-low band-high top-rate)
        floor     (* income eff-rate)
        credits   (+ (or ordinary-irpf 0M)
                     (or dividend-irrf 0M)
                     (or jcp-irrf 0M)
                     (or foreign-credit 0M))
        payable   (max 0M (- floor credits))]
    {:floor          floor
     :effective-rate eff-rate
     :credits        credits
     :payable        payable
     :base           income
     :band-low       band-low
     :band-high      band-high
     :top-rate       top-rate}))

(defn- irpfm-component
  "Pillar 2 component — emits ONLY when income > band-low (otherwise
   the floor is 0). Records the credits as `:adjustment-items` per
   note 105."
  [{:keys [commodity authority]}
   {:keys [floor effective-rate credits payable base
           band-low band-high top-rate]}
   ordinary-irpf dividend-irrf jcp-irrf foreign-credit]
  (let [schedule (irpfm-schedule band-low band-high top-rate)]
    {:kind            :investment-income-tax
     :authority       authority
     :composed-of     [:br-irpfm]
     :base            (money/money base commodity)
     :schedule        schedule
     :gross-liability (money/money floor commodity)
     :liability       (money/money payable commodity)
     :prepaid         (money/zero commodity)
     :credits
     (filterv
      #(pos? (-> % :amount :amount))
      [{:code   :credit-ordinary-irpf
        :label  "Less: ordinary IRPF paid"
        :amount (money/money (or ordinary-irpf 0M) commodity)}
       {:code   :credit-dividend-irrf
        :label  "Less: Pillar 1 dividend IRRF paid"
        :amount (money/money (or dividend-irrf 0M) commodity)}
       {:code   :credit-jcp-irrf
        :label  "Less: Pillar 3 JCP IRRF paid"
        :amount (money/money (or jcp-irrf 0M) commodity)}
       {:code   :credit-foreign-tax
        :label  "Less: foreign tax credit"
        :amount (money/money (or foreign-credit 0M) commodity)}])
     :line-items
     [{:line :irpfm-base
       :label "IRPFM base (annual income including dividends, JCP, etc.)"
       :value (money/money base commodity)}
      {:line :irpfm-effective-rate
       :label (format "IRPFM effective rate (linear ramp R$ %s — R$ %s @ top %s)"
                      (str band-low) (str band-high) (str top-rate))
       :value (money/money effective-rate commodity)}
      {:line :irpfm-floor
       :label "IRPFM floor (rate × base, before credits)"
       :value (money/money floor commodity)}
      {:line :irpfm-credits-total
       :label "Total credits applied"
       :value (money/money credits commodity)}
      {:line :irpfm-payable
       :label "IRPFM payable (max 0, floor − credits)"
       :value (money/money payable commodity)}]
     :jurisdiction-specific-codes {:lane           :br-irpfm
                                   :ramp-low       band-low
                                   :ramp-high      band-high
                                   :top-rate       top-rate
                                   :enacted-by     "Lei 15.270/2025 art. 9-13"}}))

;; ============================================================================
;; Pillar 3 — JCP IRRF (bitemporal 15 → 17.5 % cliff at 2026-01-01)
;; ============================================================================

(defn- jcp-irrf-component
  "Pillar 3 component — flat rate × JCP amount; reads the bitemporal
   rate at the JCP DELIBERATION date (passed via :as-of). PLP 128/2025
   raised the rate to 17.5 % from 2026-01-01."
  [{:keys [commodity authority]} db ^java.util.Date as-of
   ^java.math.BigDecimal jcp-amount]
  (let [rate     (or (param db "BR.INV.JCP.irrf-rate" as-of) 0.175M)
        irrf     (* jcp-amount rate)
        schedule (ts/flat rate)]
    {:kind            :investment-income-tax
     :authority       authority
     :composed-of     [:br-jcp-irrf]
     :base            (money/money jcp-amount commodity)
     :schedule        schedule
     :gross-liability (money/money irrf commodity)
     :liability       (money/money irrf commodity)
     :prepaid         (money/money irrf commodity)   ; IRRF = source-withheld
     :line-items      [{:line :jcp-gross
                        :label "JCP distribution (gross)"
                        :value (money/money jcp-amount commodity)}
                       {:line :jcp-rate
                        :label (str "JCP IRRF rate at deliberation date (" rate ")")
                        :value (money/money rate commodity)}
                       {:line :jcp-irrf
                        :label "JCP IRRF (rate × gross)"
                        :value (money/money irrf commodity)}]
     :jurisdiction-specific-codes {:lane        :br-jcp-irrf
                                   :darf        5706
                                   :rate-at     rate
                                   :rate-source "PLP 128/2025 + Lei 9.249/95 art. 9"}}))

;; ============================================================================
;; Pillar 4 — renda fixa (regressive bucket) + FII (conditional exemption)
;; ============================================================================

(defn- compute-renda-fixa
  "Bucket the consumer's per-disposal vector by holding-days, sum each
   bucket's gain, and apply the per-bucket rate. Returns
   `{:bucket-rows [{:bucket :gain :rate :irrf}…] :total-irrf}`."
  [db ^java.util.Date as-of entries]
  (let [by-bucket (group-by #(bucket-for-holding-days
                              (bigdec (or (:holding-days %) 0M)))
                            entries)
        rows (mapv (fn [bucket]
                     (let [es   (get by-bucket bucket [])
                           gain (reduce + 0M (map #(or (:gain %) 0M) es))
                           rate (or (bucket-rate db bucket as-of) 0M)
                           irrf (* gain rate)]
                       {:bucket bucket :gain gain :rate rate :irrf irrf
                        :entries es}))
                   [1 2 3 4])
        total (reduce + 0M (map :irrf rows))]
    {:bucket-rows rows
     :total-irrf  total}))

(defn- renda-fixa-component
  "Pillar 4 component — flat per-bucket rate; one component covering
   all four buckets. FII distributions where `:conditions-met?` is
   truthy are EXEMPT and excluded upstream; otherwise they fall through
   to the consumer's :br-renda-fixa lane (provider-conservative)."
  [{:keys [commodity authority]} {:keys [bucket-rows total-irrf]}]
  (let [total-gain (reduce + 0M (map :gain bucket-rows))]
    {:kind            :investment-income-tax
     :authority       authority
     :composed-of     [:br-renda-fixa]
     :base            (money/money total-gain commodity)
     :schedule        nil
     :gross-liability (money/money total-irrf commodity)
     :liability       (money/money total-irrf commodity)
     :prepaid         (money/money total-irrf commodity)
     :line-items
     (into [{:line :rf-total-gain
             :label "Renda fixa total gain (all buckets)"
             :value (money/money total-gain commodity)}
            {:line :rf-total-irrf
             :label "Renda fixa total IRRF"
             :value (money/money total-irrf commodity)}]
           (mapv (fn [{:keys [bucket gain rate irrf]}]
                   {:line  (keyword (str "rf-bucket-" bucket))
                    :label (format "Bucket %s — gain %s @ %s → IRRF %s"
                                   bucket (str gain) (str rate) (str irrf))
                    :value (money/money irrf commodity)})
                 bucket-rows))
     :jurisdiction-specific-codes {:lane :br-renda-fixa
                                   :darf 8053}}))

;; ============================================================================
;; FII exemption — provider-side conditional dropout
;; ============================================================================

(defn- fii-exempt-entries
  "Partition the consumer's `:br-fii-distributions` into
   `[exempt taxable]` based on `:conditions-met?`. Exempt entries do
   not produce a component; taxable entries fall through to renda fixa."
  [entries]
  (let [{ex true tx false} (group-by (comp boolean :conditions-met?) entries)]
    [(or ex []) (or tx [])]))

;; ============================================================================
;; Corp lane — foreign-source dividends fold to CIT
;; ============================================================================

(defn- corp-foreign-div-component
  "Corporate lane — Brazilian PJ receives foreign-source dividends.
   PJ-to-PJ DOMESTIC distributions are EXEMPT (Lei 9.249/95 art. 10
   preserved by Lei 15.270/2025 art. 6 §3) — those produce NO
   component. Foreign-source dividends include in CIT base via
   `:cit-base-additions` (CFC rules under Lei 12.973/2014 unchanged)."
  [{:keys [commodity authority]} ^java.math.BigDecimal foreign-gross]
  {:kind            :investment-income-tax
   :authority       authority
   :composed-of     [:br-corp-foreign-div]
   :base            (money/money foreign-gross commodity)
   :schedule        nil
   :gross-liability (money/zero commodity)
   :liability       (money/zero commodity)
   :prepaid         (money/zero commodity)
   :line-items      [{:line :corp-foreign-div-gross
                      :label "PJ foreign-source dividend (folds to IRPJ + CSLL)"
                      :value (money/money foreign-gross commodity)}]
   :jurisdiction-specific-codes {:cit-base-additions [foreign-gross]
                                 :lane :br-corp-foreign-div
                                 :regime :lucro-real}})

;; ============================================================================
;; The provider
;; ============================================================================

(defrecord BRInvestmentIncomeTaxProvider
           [id authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db    (or (:db ctx)
                    (throw (ex-info ":db required in ctx for BR investment-income provider"
                                    {:ctx-keys (keys ctx)})))
          as-of (as-of-from-ctx ctx)
          opts  {:authority authority :commodity commodity}
          components
          (case kind
            :individual
            (let [;; -- Pillar 1 (dividend IRRF) --
                  div-entries (or (:br-dividend-per-payer-per-month inputs) [])
                  {div-irrf :irrf div-trig :triggered}
                  (compute-dividend-irrf db as-of div-entries)
                  div-cmp (when (or (pos? div-irrf) (seq div-trig))
                            (dividend-irrf-component opts div-irrf div-trig))

                  ;; -- Pillar 3 (JCP IRRF) --
                  jcp-amount  (or (get-in inputs [:br-jcp-summary :amount]) 0M)
                  jcp-as-of   (or (get-in inputs [:br-jcp-summary :deliberation-date]) as-of)
                  jcp-cmp     (when (pos? jcp-amount)
                                (jcp-irrf-component opts db jcp-as-of jcp-amount))
                  jcp-irrf    (if jcp-cmp
                                (-> jcp-cmp :liability :amount)
                                0M)

                  ;; -- Pillar 4 (renda fixa) — FII conditionally folded in --
                  rf-raw      (or (:br-renda-fixa inputs) [])
                  fii-raw     (or (:br-fii-distributions inputs) [])
                  [fii-exempt fii-taxable] (fii-exempt-entries fii-raw)
                  ;; Taxable FII falls through to renda-fixa with a
                  ;; long holding-period default if the consumer didn't
                  ;; provide one (>720d, bucket 4).
                  fii-as-rf   (mapv (fn [e]
                                      {:holding-days (or (:holding-days e) 1000)
                                       :gain         (or (:amount e) 0M)
                                       :source       :fii})
                                    fii-taxable)
                  rf-entries  (vec (concat rf-raw fii-as-rf))
                  rf-out      (compute-renda-fixa db as-of rf-entries)
                  rf-cmp      (when (or (pos? (:total-irrf rf-out))
                                        (seq rf-entries))
                                (renda-fixa-component opts rf-out))

                  ;; -- Pillar 2 (IRPFM) — credits AGAINST the running ledger --
                  ;; Annual base = consumer-supplied :br-annual-income-base
                  ;; (their σ_E across all dividend ledger + ordinary income).
                  ;; The provider does NOT re-marginalize the books here; the
                  ;; per-payer dividend trigger above gave the rough IRRF
                  ;; total but the IRPFM base is a richer aggregation
                  ;; (includes JCP + below-trigger dividends), so the
                  ;; consumer supplies it directly (note 155 §3.3).
                  annual-income   (or (:br-annual-income-base inputs) 0M)
                  ordinary-irpf   (or (:br-ordinary-irpf-paid inputs) 0M)
                  foreign-credit  (or (:br-foreign-dividend-tax-credit inputs) 0M)
                  irpfm-out       (compute-irpfm db as-of annual-income
                                                 ordinary-irpf div-irrf jcp-irrf
                                                 foreign-credit)
                  irpfm-cmp       (when (pos? annual-income)
                                    (irpfm-component opts irpfm-out
                                                     ordinary-irpf div-irrf
                                                     jcp-irrf foreign-credit))]
              (->> [div-cmp irpfm-cmp jcp-cmp rf-cmp]
                   (remove nil?)
                   ;; Surface a line-items breadcrumb when FII was exempted.
                   ((fn [cs]
                      (if (seq fii-exempt)
                        (mapv (fn [c]
                                (cond-> c
                                  (= :br-renda-fixa
                                     (get-in c [:jurisdiction-specific-codes :lane]))
                                  (update :line-items
                                          conj
                                          {:line  :fii-exempt-applied
                                           :label (format "FII PF exemption applied to %s entries (Lei 11.033/2004 art. 3 III)"
                                                          (count fii-exempt))
                                           :value (money/money
                                                   (reduce + 0M (map :amount fii-exempt))
                                                   commodity)})))
                              cs)
                        cs)))
                   vec))

            :corporation
            (let [foreign-div (or (:br-corp-foreign-dividends inputs) 0M)
                  ;; PJ-to-PJ domestic dividends: EXEMPT — no component.
                  cmp (when (pos? foreign-div)
                        (corp-foreign-div-component opts foreign-div))]
              (->> [cmp] (remove nil?) vec))

            (throw (ex-info "BR investment-income :kind must be :individual or :corporation"
                            {:kind kind :supported kinds})))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :br :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn br-individual-investment-income-provider
  "Build a BR individual investment-income provider. The consumer
   supplies inputs (note 155 §3.6):

   Inputs (all under `:inputs`):
     :br-dividend-per-payer-per-month
       — vector of `{:payer :recipient :year :month :amount
                     :foreign? :grandfathered?}`. The provider folds
         per (payer × recipient × month); above R$ 50 000 triggers
         10 % IRRF on the FULL monthly total; foreign entries fire
         per-entry regardless of amount; grandfathered entries are
         skipped (2025 AGM-approval transition).
     :br-jcp-summary {:amount :deliberation-date}
       — total JCP received in the period + the deliberation date
         (the date the JCP rate cliff resolves at). Default deliberation
         date = `:as-of`.
     :br-renda-fixa
       — vector of `{:holding-days :gain}` per disposal. The provider
         buckets per Lei 11.033/2004 art. 1.
     :br-fii-distributions
       — vector of `{:amount :conditions-met? :holding-days?}`. When
         `:conditions-met?` is truthy → exempt (no component);
         otherwise falls through to renda fixa.
     :br-annual-income-base
       — IRPFM base (annual income across all categories the consumer
         wires; Pillar 2 §3.3 of note 155). Provider does NOT
         re-marginalize — consumer is authoritative.
     :br-ordinary-irpf-paid
       — ordinary IRPF for the year (from `br-period-tax-provider`).
         Credited against the IRPFM floor.
     :br-foreign-dividend-tax-credit
       — treaty-creditable foreign WHT. Credited against the IRPFM
         floor."
  [{:keys [id commodity] :or {id :br-investment-income-individual commodity :BRL}}]
  (->BRInvestmentIncomeTaxProvider
   id :br-rfb commodity
   (str "Lei 15.270/2025 art. 6-13 (PF dividend IRRF + IRPFM); "
        "PLP 128/2025 (JCP rate cliff); "
        "Lei 11.033/2004 art. 1 (renda fixa regressive) + art. 3 III (FII PF exemption)")
   :individual))

(defn br-corporate-investment-income-provider
  "Build a BR corporate investment-income provider.

   PJ-to-PJ DOMESTIC dividends are EXEMPT (Lei 9.249/95 art. 10
   preserved by Lei 15.270/2025 art. 6 §3) and produce NO component.

   Foreign-source dividends fold into the CIT base via
   `:cit-base-additions` (CFC rules per Lei 12.973/2014 unchanged by
   the 2026 reform).

   Inputs (all under `:inputs`):
     :br-corp-foreign-dividends <bigdec>
       — gross foreign-source dividends to include in the period's
         IRPJ + CSLL base."
  [{:keys [id commodity] :or {id :br-investment-income-corporate commodity :BRL}}]
  (->BRInvestmentIncomeTaxProvider
   id :br-rfb commodity
   (str "Lei 9.249/95 art. 10 (PJ-to-PJ preserved); Lei 12.973/2014 "
        "(CFC); Lei 15.270/2025 art. 6 §3 (PJ exemption carve-out)")
   :corporation))

(defn install-statute!
  "Install the BR investment-income statute (parameters + values) into
   `conn`. Standalone — does not require any other statute installed
   first."
  [conn]
  (inv-statute/install! conn))

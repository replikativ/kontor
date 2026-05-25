(ns kontor.l10n-in.investment-income-provider
  "IN investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 156.

   Post-FA-2020, India taxes ALL investment income (dividends,
   interest, MF IDCW) in the recipient's hands at slab rate. The
   Dividend Distribution Tax (DDT) was abolished; §115BBDA's 10 %
   additional flat on dividends > ₹10 L was REPEALED. The provider's
   job is therefore mostly to **route** income into the right
   downstream (PIT / CIT / standalone §115A), feed in TDS prepayments,
   and apply the few jurisdiction-specific knobs (§80TTA / §80TTB
   deductions, FA-2024 dividend surcharge cap, §115A NRI flat).

   ## Two `:kind`s — `:individual | :corporation`

   - **Individual / HUF (resident)** — dividends + interest + MF IDCW
     fold via `:pit-base-additions` to the IN PIT provider. §80TTA /
     §80TTB savings-interest deductions surface as negative additions
     (OLD regime only). TDS feeds as `:prepaid`. FA-2024 surcharge cap
     applies on the dividend slice (computed as a `:jurisdiction-
     specific-codes :in/dividend-surcharge-cap` hint the consumer
     threads to PIT).

   - **NRI (non-resident individual / foreign company)** — dividends
     hit the **definitive** §115A 20 % flat (or DTAA-reduced rate).
     Standalone component with `(ts/flat 0.20M)` + 15 % surcharge cap
     + 4 % cess. §195 TDS feeds as `:prepaid`.

   - **Corporation (resident)** — domestic dividends fold via
     `:cit-base-additions` (the §80M chain-relief deduction is
     consumer-driven via `:inputs :in-§80m-redistribution`; when
     supplied, the provider surfaces a negative addition).
     Foreign dividends fold at gross with a foreign-tax-credit
     placeholder `:jurisdiction-specific-codes :in/foreign-tax-credit`.

   ## Inputs map shape

   ```clojure
   {:inputs
    {:in-investment-income
     {:dividends             {<partner-id> <Money>}   ; per-payer (§194 ₹10k threshold)
      :interest-bank         {<bank-id>    <Money>}   ; per-bank (§194A ₹40k/₹50k threshold)
      :interest-savings      <Money>                  ; for §80TTA (NOT §80TTB scope)
      :mf-idcw               {<amc-id>     <Money>}   ; per-AMC (§194K ₹10k threshold)
      :foreign-dividends     <Money>                  ; CORP-only: foreign at gross
      :§80m-redistribution   <Money>                  ; CORP-only: §80M-eligible portion
      :nri-dividends         <Money>                  ; NRI-only: §115A 20%
      :nri-dtaa-rate         <BigDecimal>}            ; NRI-only: override §115A rate

     :in-tds-withheld
     {:by-section {:194  <Money>     ; dividend TDS
                   :194A <Money>     ; interest TDS
                   :194K <Money>     ; MF IDCW TDS
                   :195  <Money>}}   ; NRI TDS

     :in-tax-regime :new-regime-115bac | :old-regime}

    :tax-unit {:filing-status :single | :joint  ; (unused by IN; reserved)
               :senior?       true | false      ; gates §80TTA vs §80TTB
               :nri?          true | false      ; routes to §115A lane
               :no-pan?       true | false}}    ; informational only
   ```

   ## §194 / §194A / §194K threshold behaviour

   The thresholds (₹10 000 dividend, ₹40 000 bank interest, ₹10 000
   MF IDCW) are **payer-side** — they gate whether the payer
   *withholds*. The recipient's tax-side aggregates BOTH withheld and
   non-withheld income into the slab base. So the provider does NOT
   apply the threshold to determine taxability; it uses the threshold
   only for the audit-trail line item indicating which payers'
   distributions triggered TDS.

   ## §115BBDA — REPEALED (FA 2020); NOT modelled

   Per note 156 §1.1.3 the additional 10 % flat on dividend income
   exceeding ₹10 L (resident individuals) — applicable FY 2016-17
   through FY 2019-20 — was repealed by FA 2020. The provider does
   not emit a §115BBDA component for any FY ≥ 2020-21.

   ## ITA 2025 renumbering (bitemporal-aware)

   Section references in line-item labels are produced via
   `section-label-at` — for `as-of < 2026-04-01` returns \"§194\";
   for `as-of ≥ 2026-04-01` returns \"§393(1)\" (and friends).
   Parameter rates / thresholds are unchanged.

   ## Composition with downstream providers

   The consumer wires:

     1. `in-investment-income-provider` (this) — supplies
        `:pit-base-additions` / `:cit-base-additions` + §115A
        standalone + TDS prepayments.
     2. `in-cgt-provider` — capital gains (note 131; separate flow).
     3. `in-period-tax-provider` — PIT slab + §87A + surcharge + cess.

   See research note 156 §5 + §6 for component-layout details and
   ADR-101 parameter wiring."
  (:require [kontor.l10n-in.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- money-amount
  "Coerce Money or BigDecimal to BigDecimal; nil → 0M."
  ^java.math.BigDecimal [x]
  (cond
    (nil? x)               0M
    (instance? java.math.BigDecimal x) x
    (number? x)            (bigdec x)
    (map? x)               (or (:amount x) 0M)
    :else                  0M))

(defn- sum-by-source
  "Sum a `{partner|bank|amc-id Money}` map into a single BigDecimal.
   nil → 0M."
  ^java.math.BigDecimal [source-map]
  (reduce + 0M (map (fn [[_ v]] (money-amount v)) (or source-map {}))))

(defn- count-above-threshold
  "Count keys in `source-map` whose Money amount exceeds `threshold`.
   Used for audit-trail TDS line items per note 156 §4 Gap C."
  ^long [source-map ^java.math.BigDecimal threshold]
  (long (count (filter (fn [[_ v]] (> (money-amount v) threshold))
                       (or source-map {})))))

(defn section-label-at
  "Bitemporal-aware section label — returns `1961-name` for
   `as-of < 2026-04-01`, `2025-name` for `as-of ≥ 2026-04-01`. The
   IT Act 2025 renumbering takes effect AY 2026-27 (1 April 2026)."
  [^java.util.Date as-of {:keys [act-1961 act-2025]}]
  (if (>= (compare as-of #inst "2026-04-01") 0)
    act-2025
    act-1961))

(def ^:private §194-names  {:act-1961 "§194"  :act-2025 "§393(1)"})
(def ^:private §194a-names {:act-1961 "§194A" :act-2025 "§391"})
(def ^:private §194k-names {:act-1961 "§194K" :act-2025 "§392"})
(def ^:private §115a-names {:act-1961 "§115A" :act-2025 "§163"})

;; ============================================================================
;; Compute-fns — §80TTA + §80TTB deductions
;; ============================================================================

(defn- in-§80tta-deduction
  "§80TTA — savings-account interest deduction, capped at ₹10 000.
   Reads `:inputs :in-savings-interest` (consumer-supplied savings
   interest amount). OLD regime only — the condition gates."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        cap   (or (statute/parameter-value-at db "IN.InvIncome.§80TTA.cap" as-of)
                  10000M)
        savings (bigdec0 (get-in ctx [:inputs :in-savings-interest]))]
    (ts/lesser-of savings cap)))

(defn- in-§80ttb-deduction
  "§80TTB — senior all-interest deduction, capped at ₹50 000.
   Reads `:inputs :in-senior-interest-total` (all interest — savings
   + FD + RD + post-office). OLD regime + senior only — the condition
   gates."
  ^java.math.BigDecimal [ctx]
  (let [db    (:db ctx)
        as-of (as-of-from-ctx ctx)
        cap   (or (statute/parameter-value-at db "IN.InvIncome.§80TTB.cap" as-of)
                  50000M)
        total (bigdec0 (get-in ctx [:inputs :in-senior-interest-total]))]
    (ts/lesser-of total cap)))

(defn register!
  "Register IN investment-income compute-fns with `kontor.statute`.
   Called automatically at namespace load; idempotent."
  []
  (statute/register-compute-fn! :in-§80tta-deduction in-§80tta-deduction)
  (statute/register-compute-fn! :in-§80ttb-deduction in-§80ttb-deduction))

(register!)

;; ============================================================================
;; TDS allocation
;; ============================================================================

(defn- tds-by-section
  "Pull TDS-withheld amounts from `:inputs :in-tds-withheld :by-section`.
   Returns `{:194 <bd> :194A <bd> :194K <bd> :195 <bd>}` (all defaulting
   to 0M)."
  [inputs]
  (let [m (get-in inputs [:in-tds-withheld :by-section] {})]
    {:194  (money-amount (:194  m))
     :194A (money-amount (:194A m))
     :194K (money-amount (:194K m))
     :195  (money-amount (:195  m))}))

;; ============================================================================
;; Resident-individual components (fold to PIT)
;; ============================================================================

(defn- resident-dividend-component
  "Dividend income (resident individual / HUF): folds via
   `:pit-base-additions` to the IN PIT provider. TDS § 194 / §393(1)
   prepaid. FA-2024 surcharge cap (15 %) surfaced as a hint via
   `:jurisdiction-specific-codes :in/dividend-surcharge-cap` for the
   PIT provider to honour."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal dividend-total
   per-payer-map
   ^java.math.BigDecimal tds-§194]
  (let [db        (:db ctx)
        as-of     (as-of-from-ctx ctx)
        threshold (or (statute/parameter-value-at
                       db "IN.InvIncome.§194.dividend-tds-threshold" as-of)
                      10000M)
        surch-cap (or (statute/parameter-value-at
                       db "IN.InvIncome.FA2024.dividend-surcharge-cap" as-of)
                      0.15M)
        §194-label (section-label-at as-of §194-names)
        n-above    (count-above-threshold per-payer-map threshold)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money dividend-total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money tds-§194 commodity)
     :line-items
     [{:line  :resident-dividend-total
       :label "Resident dividend income (post-FA-2020 slab; DDT abolished)"
       :value (money/money dividend-total commodity)}
      {:line  :tds-§194
       :label (str §194-label " TDS prepayment (10 % w/ PAN; threshold ₹" threshold
                   " — " n-above " payer(s) above)")
       :value (money/money tds-§194 commodity)}
      {:line  :fa2024-surcharge-cap
       :label (str "FA 2024 — surcharge cap on dividend income (" (* surch-cap 100M) " %)")
       :value (money/money surch-cap commodity)}]
     :jurisdiction-specific-codes
     {:lane                          :in-dividend-resident-slab
      :pit-base-additions            [dividend-total]
      :in/dividend-surcharge-cap     surch-cap
      :in/per-payer-count            (count per-payer-map)
      :in/payers-above-tds-threshold n-above}}))

(defn- resident-interest-component
  "Interest income (resident individual): folds via
   `:pit-base-additions` to PIT, with §80TTA / §80TTB deductions
   applied via `kontor.statute/apply-provisions` (gates on regime +
   senior?). TDS §194A / §391 prepaid."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal interest-total
   ^java.math.BigDecimal savings-interest
   ^java.math.BigDecimal tds-§194A
   regime senior?]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        §194a-label (section-label-at as-of §194a-names)
        ;; Senior → §80TTB acts on ALL interest; non-senior → §80TTA
        ;; acts only on the savings slice. Provide the right input
        ;; under the right key so the provisions fire correctly.
        scoped-ctx  (-> ctx
                        (assoc :db db :as-of as-of
                               :regime regime :senior? (boolean senior?))
                        (assoc-in [:inputs :in-savings-interest]
                                  savings-interest)
                        (assoc-in [:inputs :in-senior-interest-total]
                                  (if senior? interest-total 0M)))
        {:keys [base-items provisions]}
        (statute/apply-provisions
         db {:concept :base-transform-deduct :jurisdiction :in :as-of as-of}
         scoped-ctx)
        ;; `apply-provisions` returns :base-deduct items in :base-items
        ;; with :amount already resolved to a BigDecimal (via the
        ;; registered compute-fn).
        resolved (mapv
                  (fn [item]
                    (let [raw (:amount item)
                          amt (if (fn? raw)
                                (raw (assoc scoped-ctx :running 0M))
                                raw)]
                      (assoc item :amount (bigdec (or amt 0M)))))
                  base-items)
        deduction-total (reduce + 0M (map :amount resolved))
        net-addition    (- interest-total deduction-total)
        threshold-gen   (or (statute/parameter-value-at
                             db "IN.InvIncome.§194A.interest-tds-threshold-general" as-of)
                            40000M)
        threshold-sr    (or (statute/parameter-value-at
                             db "IN.InvIncome.§194A.interest-tds-threshold-senior" as-of)
                            50000M)
        threshold       (if senior? threshold-sr threshold-gen)
        base-lines
        [{:line  :resident-interest-total
          :label "Resident interest income (bank + FD + RD; slab rate)"
          :value (money/money interest-total commodity)}
         {:line  :tds-§194A
          :label (str §194a-label " TDS prepayment (10 % w/ PAN; threshold ₹"
                      threshold (if senior? " senior" " general") ")")
          :value (money/money tds-§194A commodity)}]
        deduction-lines
        (mapv (fn [r] {:line  (:code r)
                       :label (:label r)
                       :value (money/money (- (:amount r)) commodity)})
              resolved)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money interest-total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money tds-§194A commodity)
     :regime          regime
     :credits         (mapv #(select-keys % [:code :label :amount :provenance]) resolved)
     :line-items      (vec (concat base-lines deduction-lines))
     :provenance      {:provider-id        :in-inv-income
                       :statute            "IT Act 1961 §194A / §80TTA / §80TTB"
                       :provisions-applied (mapv :provision/code provisions)
                       :as-of              as-of}
     :jurisdiction-specific-codes
     {:lane                  :in-interest-resident-slab
      :pit-base-additions    [net-addition]
      :in/§80tta-§80ttb-deduction deduction-total
      :in/gross-interest     interest-total}}))

(defn- resident-mf-idcw-component
  "Mutual-fund IDCW (resident individual): folds via
   `:pit-base-additions` to PIT. TDS §194K / §392 prepaid."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal mf-idcw-total
   per-amc-map
   ^java.math.BigDecimal tds-§194K]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        threshold   (or (statute/parameter-value-at
                         db "IN.InvIncome.§194K.mf-idcw-tds-threshold" as-of)
                        10000M)
        §194k-label (section-label-at as-of §194k-names)
        n-above     (count-above-threshold per-amc-map threshold)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money mf-idcw-total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money tds-§194K commodity)
     :line-items
     [{:line  :resident-mf-idcw-total
       :label "Mutual-fund IDCW (post-FA-2020 slab; §115BBDA repealed; not redemption gains)"
       :value (money/money mf-idcw-total commodity)}
      {:line  :tds-§194K
       :label (str §194k-label " TDS prepayment (10 % w/ PAN; threshold ₹" threshold
                   " — " n-above " AMC(s) above)")
       :value (money/money tds-§194K commodity)}]
     :jurisdiction-specific-codes
     {:lane                          :in-mf-idcw-resident-slab
      :pit-base-additions            [mf-idcw-total]
      :in/per-amc-count              (count per-amc-map)
      :in/amcs-above-tds-threshold   n-above}}))

;; ============================================================================
;; NRI §115A component (standalone — definitive)
;; ============================================================================

(defn- nri-§115a-component
  "NRI dividend at §115A 20 % flat (or DTAA-reduced) + 15 % surcharge
   cap + 4 % H&E cess. Standalone DEFINITIVE liability; §195 TDS
   prepaid. The recipient is NOT required to file an ITR if §115A is
   the only Indian income and §195 TDS was correctly deducted."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal dividend-total
   ^java.math.BigDecimal tds-§195
   dtaa-rate]
  (let [db          (:db ctx)
        as-of       (as-of-from-ctx ctx)
        statutory   (or (statute/parameter-value-at
                         db "IN.InvIncome.§115A.nri-dividend-rate" as-of)
                        0.20M)
        rate        (or dtaa-rate statutory)
        surch-cap   (or (statute/parameter-value-at
                         db "IN.InvIncome.FA2024.dividend-surcharge-cap" as-of)
                        0.15M)
        cess-rate   (or (statute/parameter-value-at
                         db "IN.InvIncome.cess.rate" as-of)
                        0.04M)
        §115a-label (section-label-at as-of §115a-names)
        schedule    (ts/flat rate)
        gross       (ts/apply-schedule schedule dividend-total)
        surcharge   (* gross surch-cap)
        cess        (* (+ gross surcharge) cess-rate)
        liability   (+ gross surcharge cess)
        treaty?     (some? dtaa-rate)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money dividend-total commodity)
     :schedule        schedule
     :gross-liability (money/money gross commodity)
     :surtaxes        [{:code :surcharge-on-tax
                        :label (str "Surcharge (FA-2024 cap " (* surch-cap 100M) " %)")
                        :amount surcharge}
                       {:code :health-education-cess
                        :label (str "Health & Education Cess (" (* cess-rate 100M) " %)")
                        :amount cess}]
     :liability       (money/money liability commodity)
     :prepaid         (money/money tds-§195 commodity)
     :regime          :nri-§115a
     :line-items
     [{:line  :nri-dividend-gross
       :label (str §115a-label " — NRI dividend (Indian-source)"
                   (when treaty? " — DTAA reduced"))
       :value (money/money dividend-total commodity)}
      {:line  :nri-tax-rate
       :label (str §115a-label " applicable rate (" (* rate 100M) " %)")
       :value (money/money rate commodity)}
      {:line  :nri-tax-gross
       :label (str §115a-label " gross tax")
       :value (money/money gross commodity)}
      {:line  :surcharge-on-tax
       :label (str "Surcharge (FA-2024 cap " (* surch-cap 100M) " %)")
       :value (money/money surcharge commodity)}
      {:line  :health-education-cess
       :label (str "Health & Education Cess (" (* cess-rate 100M) " %)")
       :value (money/money cess commodity)}
      {:line  :tds-§195
       :label "§195 TDS prepayment (NRI withholding)"
       :value (money/money tds-§195 commodity)}]
     :jurisdiction-specific-codes
     {:lane                       :in-nri-§115a-dividend
      :in/dividend-surcharge-cap  surch-cap
      :in/dtaa-applied?           treaty?
      :in/statutory-rate          statutory
      :in/applied-rate            rate}}))

;; ============================================================================
;; Corporate components (fold to CIT)
;; ============================================================================

(defn- corporate-dividend-component
  "Corporate dividend recipient — domestic dividends fold via
   `:cit-base-additions` to CIT. §80M chain-relief (when the
   consumer supplies `:inputs :in-investment-income
   :§80m-redistribution`) surfaces as a negative addition. Foreign
   dividends fold at gross with a foreign-tax-credit placeholder."
  [{:keys [commodity authority]} ctx
   ^java.math.BigDecimal domestic-dividend
   ^java.math.BigDecimal foreign-dividend
   ^java.math.BigDecimal §80m-redistribution
   ^java.math.BigDecimal tds-§194]
  (let [as-of       (as-of-from-ctx ctx)
        §194-label  (section-label-at as-of §194-names)
        §80m-deduction (ts/lesser-of (bigdec0 §80m-redistribution)
                                     (bigdec0 domestic-dividend))
        ;; Per cross-jurisdiction convention (CA §112 / DE §8b / CN
        ;; §26(2) / AU LIC), emit the gross dividend in
        ;; `:cit-base-additions` and the §80M relief as a separate
        ;; `:cit-base-deductions` line — keeps the deduction
        ;; auditable rather than silently netted.
        cit-adds   (cond-> []
                     (pos? domestic-dividend) (conj domestic-dividend)
                     (pos? foreign-dividend)  (conj foreign-dividend))
        cit-deds   (cond-> []
                     (pos? §80m-deduction) (conj §80m-deduction))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money (+ domestic-dividend foreign-dividend) commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money tds-§194 commodity)
     :line-items
     (cond-> [{:line  :corp-domestic-dividend
               :label "Domestic dividend received (corp; post-FA-2020 slab/CIT)"
               :value (money/money domestic-dividend commodity)}]
       (pos? §80m-deduction)
       (conj {:line  :§80m-deduction
              :label "§80M chain-relief deduction (redistributed within window)"
              :value (money/money (- §80m-deduction) commodity)})

       (pos? foreign-dividend)
       (conj {:line  :corp-foreign-dividend
              :label "Foreign dividend (gross; foreign-tax-credit downstream)"
              :value (money/money foreign-dividend commodity)})

       (pos? tds-§194)
       (conj {:line  :tds-§194
              :label (str §194-label " TDS prepayment (corp)")
              :value (money/money tds-§194 commodity)}))
     :jurisdiction-specific-codes
     (cond-> {:lane :in-corp-dividend
              :in/§80m-deduction §80m-deduction
              :in/foreign-tax-credit foreign-dividend}
       (seq cit-adds) (assoc :cit-base-additions cit-adds)
       (seq cit-deds) (assoc :cit-base-deductions cit-deds))}))

;; ============================================================================
;; Provider
;; ============================================================================

(defn- regime-of
  [inputs]
  (case (or (:in-tax-regime inputs) :new-regime-115bac)
    :new-regime-115bac :new
    :old-regime        :old
    :new))

(defn- individual-tax-return-facts
  [db ctx as-of entity period commodity inputs tax-unit]
  (let [items     (or (:in-investment-income inputs) {})
        per-payer (or (:dividends items) {})
        per-bank  (or (:interest-bank items) {})
        per-amc   (or (:mf-idcw items) {})
        dividend-total (sum-by-source per-payer)
        bank-interest  (sum-by-source per-bank)
        savings-int    (money-amount (:interest-savings items))
        interest-total (+ bank-interest savings-int)
        mf-idcw-total  (sum-by-source per-amc)
        tds            (tds-by-section inputs)
        regime         (regime-of inputs)
        senior?        (boolean (:senior? tax-unit))
        opts           {:authority :in-cbdt :commodity commodity}
        ctx'           (assoc ctx :db db :as-of as-of)
        cmps           (cond-> []
                         (pos? dividend-total)
                         (conj (resident-dividend-component
                                opts ctx' dividend-total per-payer (:194 tds)))

                         (pos? interest-total)
                         (conj (resident-interest-component
                                opts ctx' interest-total savings-int
                                (:194A tds) regime senior?))

                         (pos? mf-idcw-total)
                         (conj (resident-mf-idcw-component
                                opts ctx' mf-idcw-total per-amc (:194K tds))))]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :in :authority :in-cbdt}
      :functional-commodity commodity
      :components           cmps})))

(defn- nri-tax-return-facts
  [db ctx as-of entity period commodity inputs]
  (let [items   (or (:in-investment-income inputs) {})
        div     (money-amount (:nri-dividends items))
        dtaa    (some-> (:nri-dtaa-rate items) bigdec)
        tds     (tds-by-section inputs)
        tds-195 (:195 tds)
        opts    {:authority :in-cbdt :commodity commodity}
        ctx'    (assoc ctx :db db :as-of as-of)
        cmps    (cond-> []
                  (pos? div)
                  (conj (nri-§115a-component opts ctx' div tds-195 dtaa)))]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :in :authority :in-cbdt}
      :functional-commodity commodity
      :components           cmps})))

(defn- corporate-tax-return-facts
  [db ctx as-of entity period commodity inputs]
  (let [items     (or (:in-investment-income inputs) {})
        per-payer (or (:dividends items) {})
        domestic  (sum-by-source per-payer)
        foreign   (money-amount (:foreign-dividends items))
        §80m      (money-amount (:§80m-redistribution items))
        tds       (tds-by-section inputs)
        opts      {:authority :in-cbdt :commodity commodity}
        ctx'      (assoc ctx :db db :as-of as-of)
        cmps      (if (or (pos? domestic) (pos? foreign))
                    [(corporate-dividend-component
                      opts ctx' domestic foreign §80m (:194 tds))]
                    [])]
    (ptp/tax-return-facts
     {:entity               entity
      :period               period
      :jurisdiction         {:country :in :authority :in-cbdt}
      :functional-commodity commodity
      :components           cmps})))

(defrecord INInvestmentIncomeTaxProvider [id kind commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (#{:individual :corporation} kind)
      (throw (ex-info "IN investment-income provider :kind must be :individual or :corporation"
                      {:kind kind})))
    (let [db       (or (:db ctx)
                       (throw (ex-info ":db required in ctx for IN investment-income provider"
                                       {:ctx-keys (keys ctx)})))
          as-of    (as-of-from-ctx ctx)
          tax-unit (or (:tax-unit ctx) (:tax-unit inputs) {})
          nri?     (boolean (:nri? tax-unit))]
      (cond
        ;; Individual NRI → §115A standalone definitive lane
        (and (= kind :individual) nri?)
        (nri-tax-return-facts db ctx as-of entity period commodity inputs)

        ;; Individual resident → fold to PIT
        (= kind :individual)
        (individual-tax-return-facts db ctx as-of entity period commodity inputs tax-unit)

        ;; Corporation (resident) → fold to CIT
        :else
        (corporate-tax-return-facts db ctx as-of entity period commodity inputs)))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn in-investment-income-provider
  "Build an IN investment-income provider.

   Required:
     :kind — `:individual` | `:corporation`. NRI is signalled per-call
             via `(:nri? tax-unit)` on the individual-kind provider.

   Optional:
     :id        — provider id (default `:in-inv-income-<kind>`)
     :commodity — functional commodity (default `:INR`)"
  [{:keys [id kind commodity]
    :or   {commodity :INR}}]
  (when-not (#{:individual :corporation} kind)
    (throw (ex-info ":kind must be :individual or :corporation" {:kind kind})))
  (->INInvestmentIncomeTaxProvider
   (or id (keyword (str "in-inv-income-" (name kind))))
   kind commodity
   "IT Act 1961 §194 / §194A / §194K / §115A / §80TTA / §80TTB + FA 2024 surcharge cap"))


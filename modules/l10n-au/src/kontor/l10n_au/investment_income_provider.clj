(ns kontor.l10n-au.investment-income-provider
  "AU investment-income tax provider — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 153.

   ## The AU imputation system in one breath

   A resident shareholder receiving a fully-franked dividend from a
   30 %-rate AU corporation:

     - **Cash dividend**: $700 → posts (Dr Bank $700 / Cr Income $700).
     - **Franking credit**: $700 × 0.30 / (1 − 0.30) = $300.
     - **Gross-up**: $1,000 of assessable income.
     - **PIT base addition**: $300 (the cash $700 is already on the
       books; the provider adds the credit to the base).
     - **Credit applied**: $300, refundable for resident individuals +
       complying super funds (s.207-45 ITAA 1997); non-refundable for
       companies (excess flows to the recipient's franking account);
       LOST for non-fixed trusts without FTE and for foreign residents.

   ## Holder-class dispatch (note 153 §1.4)

   Three axes drive the credit semantics:

   - `:kind` — `:individual | :trust | :super-fund | :company` (the
     base axis, shared with the AU CGT provider per note 129 §5).
   - `:tax-unit :super-fund-phase` — `:accumulation | :pension`
     (15 % vs 0 % fund tax; both refundable).
   - `:tax-unit :trust-kind` — `:fixed | :discretionary-fte |
     :discretionary-no-fte` (v1 ships `:fixed` passthrough +
     `:discretionary-no-fte` credit-lost; `:discretionary-fte` raises
     `:not-yet-implemented`).

   ## 45-day at-risk holding-period rule (note 153 §1.3)

   Former Pt IIIAA Div 1A s.160APHO ITAA 1936 (preserved as savings
   provision when Pt IIIAA repealed 2002-07-01). Each event carries
   `:holding-days` (provider trusts the consumer's at-risk-position
   computation — derivative-aware modeling is out of scope per note
   153 §7 Q5). The provider:

     1. Sums franking credits across all events in the period.
     2. If the holder is `:individual` AND the total ≤ $5,000
        (s.160APHT small-shareholder exemption), skips the 45-day
        test on all events.
     3. Otherwise, each event must satisfy `:holding-days ≥ 45`; failing
        events have their credit DENIED (cash dividend still assessable,
        no gross-up, no credit).

   ## Foreign-source dividends + FITO (note 153 §1.5)

   When `:foreign-jurisdiction` is set on an event:

     - The full GROSS dividend (cash, no Australian gross-up) folds
       into the holder's PIT/CIT base.
     - The `:foreign-tax-withheld` amount becomes a `:credit
       :refundable? false` (FITO, s.770-10 ITAA 1997 — excess is
       lost, no carry-forward).
     - The small-claims shortcut (s.770-75 — full FITO claim under
       AUD $1,000) applies AT THE CONSUMER (the consumer pre-computes
       the cap). v1 emits the FITO at face value capped by the
       provider per source; the consumer-level multi-source cap is
       deferred to the PIT provider.

   ## Interest income (note 153 §1.7)

   Folds into PIT/CIT base via `:pit-base-additions` /
   `:cit-base-additions`. TFN withholding (`:tfn-prepaid`) surfaces
   as `:credit :refundable? true` (the bank's no-TFN penalty is a
   refundable prepayment, not a final tax).

   ## Composition with AU PIT / CIT

   The provider does NOT own a `:schedule` (per the AU CGT pattern,
   note 129 §5). Each component is a feeder: `:liability` = NEGATIVE
   of the credit total (the integration relief) + a `:base` of the
   per-event gross-up. The downstream AU PIT (`au-individual`) or
   CIT (`au-company-tax`) provider sweeps the `:pit-base-additions` /
   `:cit-base-additions` + the `:credits` list."
  (:require [kontor.l10n-au.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]))

;; ============================================================================
;; Constants — closed sets
;; ============================================================================

(def kinds
  "Closed set of holder kinds the AU investment-income provider services.
   Shares the enum with `au-cgt-provider` (note 129 §5 / note 153 §6.1)."
  #{:individual :trust :super-fund :company})

(def cit-kinds
  "Holder kinds that fold into CIT (not PIT)."
  #{:company})

(def super-fund-phases
  "Closed set of `:tax-unit :super-fund-phase` values
   (note 153 §1.4 / Gap 4)."
  #{:accumulation :pension})

(def trust-kinds
  "Closed set of `:tax-unit :trust-kind` values (note 153 §1.9 / Gap 5).
   v1 ships `:fixed` (passthrough) + `:discretionary-no-fte` (credit-
   lost); `:discretionary-fte` raises `:not-yet-implemented`."
  #{:fixed :discretionary-fte :discretionary-no-fte})

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code ^java.util.Date as-of]
  (statute/parameter-value-at db code as-of))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- bd-min
  ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (if (<= (compare a b) 0) a b))

;; ============================================================================
;; Refundability resolver — per (kind, phase, trust-kind) per credit kind
;; ============================================================================

(defn- franking-credit-refundability
  "Per s.207-45 / s.207-150 ITAA 1997 + note 153 §1.4 — return the
   credit fate for franking credits given the holder shape:

     :refundable      — individual; super-fund (both phases); fixed
                        trust passthrough.
     :non-refundable  — company (excess flows to own franking account
                        via `:au-franking-account-credit-pending`).
     :lost            — non-fixed trust without FTE; foreign resident.

   `:discretionary-fte` raises `:not-yet-implemented` (note 153 §1.9
   v1 scope).

   Returns a keyword from #{:refundable :non-refundable :lost}."
  [kind tax-unit]
  (case kind
    :individual  :refundable
    :super-fund  :refundable               ; both phases — 15 % accum / 0 % pension
    :company     :non-refundable
    :trust       (case (or (:trust-kind tax-unit) :discretionary-no-fte)
                   :fixed                  :refundable
                   :discretionary-no-fte   :lost
                   :discretionary-fte      (throw
                                            (ex-info
                                             "AU investment-income: :trust-kind :discretionary-fte not yet implemented (note 153 §1.9 v1 scope)"
                                             {:tax-unit tax-unit})))
    (throw (ex-info ":kind must be one of #{:individual :trust :super-fund :company}"
                    {:kind kind}))))

(defn- foreign-resident?
  "True iff the holder is a foreign resident. The consumer sets
   `:tax-unit :foreign-resident? true` to override the default
   resident assumption. Foreign residents LOSE franking credits
   (note 153 §1.4 last row)."
  [tax-unit]
  (true? (:foreign-resident? tax-unit)))

;; ============================================================================
;; Franking-credit formula — cash × rate / (1 − rate)
;; ============================================================================

(defn- corporate-rate-for-event
  "Resolve the corporate rate that the DISTRIBUTING corporation franked
   at. The event may carry `:corporate-rate` directly (overriding); if
   absent, the provider reads the `:elective-regime
   :au-frank-distributor-bre` flag to pick BRE 25 % vs default large
   30 %."
  ^java.math.BigDecimal [db ^java.util.Date as-of event]
  (or (:corporate-rate event)
      (if (contains? (set (:elective-regime event)) :au-frank-distributor-bre)
        (or (param db "AU.InvIncome.corporate-rate.base-rate-entity" as-of) 0.25M)
        (or (param db "AU.InvIncome.corporate-rate.large" as-of) 0.30M))))

(defn- compute-franking-credit
  "Per the ATO formula (note 153 §1.2):

     franking-credit = cash × franking-percent × rate / (1 − rate)

   Where:
     - `cash`             — gross cash dividend received.
     - `franking-percent` — 0..1 fraction of the dividend that is
                            franked (default 1.0 = fully franked).
     - `rate`             — the distributing corp's franking rate
                            (30 % large / 25 % BRE).

   The unfranked slice does NOT carry a credit (the cash on that
   slice is still assessable as an unfranked dividend)."
  ^java.math.BigDecimal
  [^java.math.BigDecimal cash
   ^java.math.BigDecimal franking-percent
   ^java.math.BigDecimal rate]
  (let [franked (* cash franking-percent)
        denom   (- 1M rate)]
    (if (zero? denom)
      0M
      ;; Use MathContext for the divide to handle 1/3-style ratios
      ;; (the BRE 0.25 / 0.75 case). HALF-EVEN at scale 16 keeps audit
      ;; trail consistent with the cash precision (consumers can round
      ;; at posting time).
      (.divide ^java.math.BigDecimal (* franked rate)
               ^java.math.BigDecimal denom
               16 java.math.RoundingMode/HALF_EVEN))))

;; ============================================================================
;; Per-event compute — dividends + interest
;; ============================================================================

(defn- classify-dividend
  "Compute the franking credit (or FITO claim) for one dividend event.
   Returns:

     {:event          <input>
      :kind           :franked-dividend | :foreign-dividend | :unfranked-dividend
      :cash           <bigdec>
      :franking-credit <bigdec>  (0 unless :franked-dividend)
      :gross-up       <bigdec>   (= franking-credit; the PIT-base-addition)
      :foreign-tax    <bigdec>   (0 unless :foreign-dividend)
      :corporate-rate <bigdec>}"
  [db as-of event]
  (let [cash         (bigdec0 (:cash-amount event))
        franking-pct (bigdec0 (or (:franking-percent event) 1M))
        foreign-jur  (:foreign-jurisdiction event)
        foreign-tax  (bigdec0 (:foreign-tax-withheld event))]
    (cond
      foreign-jur
      {:event event :kind :foreign-dividend
       :cash cash :franking-credit 0M :gross-up 0M
       :foreign-tax foreign-tax :corporate-rate 0M}

      (pos? franking-pct)
      (let [rate (corporate-rate-for-event db as-of event)
            fc   (compute-franking-credit cash franking-pct rate)]
        {:event event :kind :franked-dividend
         :cash cash :franking-credit fc :gross-up fc
         :foreign-tax 0M :corporate-rate rate})

      :else
      {:event event :kind :unfranked-dividend
       :cash cash :franking-credit 0M :gross-up 0M
       :foreign-tax 0M :corporate-rate 0M})))

;; ============================================================================
;; 45-day at-risk holding rule + small-shareholder exemption
;; ============================================================================

(defn- apply-holding-period-gate
  "Apply the s.160APHO 45-day rule per event after the s.160APHT small-
   shareholder exemption check (note 153 §1.3). Mutates `:franking-credit`
   to 0 on disqualified events; records `:disqualified? true` + reason.

   Inputs:
     - `classified` — vec of per-event classification maps.
     - `cutoff-days`, `exempt-threshold` — from statute.
     - `kind` — holder kind; the exemption is `:individual`-only.
     - `ytd-claimed` — running YTD franking credit from `:inputs`
                       (mirrors the AU CGT retirement-cap pattern,
                       note 129 §4 Gap 6).

   Returns `{:classified <vec-with-disqualifications-applied>
             :exemption-applies? <bool>
             :period-fc-total <bigdec>}`."
  [classified cutoff-days exempt-threshold kind ytd-claimed]
  (let [cutoff-days     (long cutoff-days)
        exempt-threshold (bigdec exempt-threshold)
        ytd-claimed     (bigdec ytd-claimed)
        period-fc-total (reduce + 0M (map :franking-credit classified))
        annual-total    (+ ytd-claimed period-fc-total)
        exemption-applies? (and (= kind :individual)
                                (<= (compare annual-total exempt-threshold) 0))
        classified'
        (mapv
         (fn [c]
           (let [days (:holding-days (:event c))]
             (cond
               (not= :franked-dividend (:kind c))
               c

               exemption-applies?
               (assoc c :holding-period-test :exempt-small-shareholder)

               (and (some? days)
                    (< (long days) cutoff-days))
               (assoc c
                      :franking-credit 0M
                      :gross-up 0M
                      :disqualified? true
                      :disqualification-reason :failed-45-day-rule
                      :holding-period-test :failed)

               :else
               (assoc c :holding-period-test :passed))))
         classified)]
    {:classified classified'
     :exemption-applies? exemption-applies?
     :period-fc-total period-fc-total}))

;; ============================================================================
;; Interest sum
;; ============================================================================

(defn- interest-totals
  "Sum interest cash + TFN-prepaid across all interest events. Returns
   `{:gross <bd> :tfn-prepaid <bd>}`."
  [interest-events]
  (reduce
   (fn [acc evt]
     (-> acc
         (update :gross + (bigdec0 (:cash-amount evt)))
         (update :tfn-prepaid + (bigdec0 (:tfn-prepaid evt)))))
   {:gross 0M :tfn-prepaid 0M}
   interest-events))

;; ============================================================================
;; Component builder
;; ============================================================================

(defn- credit-item
  "Build one entry of the `:credits` vector. `fate` is one of
   `:refundable | :non-refundable | :lost`."
  [code label amount fate commodity]
  {:code        code
   :label       label
   :amount      (money/money amount commodity)
   :refundable? (= fate :refundable)
   :fate        fate})

(defn- build-component
  "Assemble the single component the provider emits per call. The
   component carries:

     - `:base`            — the period gross-up amount (the assessable
                             addition over and above the already-booked
                             cash dividend).
     - `:liability`       — NEGATIVE of the credit total (the integration
                             relief; downstream PIT/CIT sweeps).
     - `:credits`         — refundable + non-refundable + lost items
                             (each with `:fate`).
     - `:jurisdiction-specific-codes` — `:pit-base-additions` /
                             `:cit-base-additions` + per-lane audit
                             trail."
  [{:keys [authority commodity kind tax-unit]} dividends interest-tot
   exemption-applies? ytd-claimed period-fc-total]
  (let [fate-franking (franking-credit-refundability kind tax-unit)
        foreign-resident? (foreign-resident? tax-unit)
        ;; If foreign resident, franking is lost regardless of holder
        ;; kind otherwise dispatch.
        fate-franking (if foreign-resident? :lost fate-franking)
        ;; --- per-event aggregates ----------------------------------------
        sum-by (fn [pred field] (reduce + 0M (map field (filter pred dividends))))
        franking-credit-sum (sum-by #(= :franked-dividend (:kind %)) :franking-credit)
        gross-up-sum        (sum-by #(= :franked-dividend (:kind %)) :gross-up)
        foreign-cash-sum    (sum-by #(= :foreign-dividend (:kind %)) :cash)
        foreign-tax-sum     (sum-by #(= :foreign-dividend (:kind %)) :foreign-tax)
        unfranked-cash-sum  (sum-by #(= :unfranked-dividend (:kind %)) :cash)
        interest-gross      (:gross interest-tot)
        tfn-prepaid         (:tfn-prepaid interest-tot)
        ;; --- credit items per fate ---------------------------------------
        ;; Franking credit: only usable when fate ≠ :lost. When :lost, we
        ;; STILL surface the item (with :fate :lost, amount preserved)
        ;; for audit; the refundable? flag is false.
        franking-item (when (pos? franking-credit-sum)
                        (credit-item :au-franking-credit
                                     "Franking credit (imputation, s.207-20 ITAA 1997)"
                                     franking-credit-sum
                                     fate-franking
                                     commodity))
        ;; FITO non-refundable, capped at foreign tax paid (per-source
        ;; cap; aggregate AU-tax cap is consumer-side per note 153 §1.5).
        ;; When the holder is foreign-resident the foreign-source income
        ;; itself isn't taxable in AU (out of scope here — note 153 §7
        ;; Q6); we drop the FITO too.
        fito-item (when (and (pos? foreign-tax-sum) (not foreign-resident?))
                    (credit-item :au-fito
                                 "Foreign Income Tax Offset (s.770-10 ITAA 1997)"
                                 foreign-tax-sum
                                 :non-refundable
                                 commodity))
        ;; TFN prepaid — always refundable for residents.
        tfn-item (when (and (pos? tfn-prepaid) (not foreign-resident?))
                   (credit-item :au-tfn-prepaid
                                "TFN withholding (Pt VA ITAA 1936; refundable prepayment)"
                                tfn-prepaid
                                :refundable
                                commodity))
        credits (filterv some? [franking-item fito-item tfn-item])
        ;; Sum the credits that count toward the liability (everything
        ;; except :lost). For company, non-refundable franking flows to
        ;; the franking-account (no integration relief at this layer).
        liability-credits-sum
        (reduce + 0M
                (map (comp :amount second)
                     (for [c credits
                           :let [item-fate (:fate c)]
                           :when (and (not= :lost item-fate)
                                      (not (and (= :company kind)
                                                (= :au-franking-credit (:code c)))))]
                       [c {:amount (-> c :amount :amount)}])))
        ;; --- PIT / CIT base additions ------------------------------------
        ;; Franking gross-up + unfranked cash + foreign cash + interest
        ;; gross all join the base. When franking is :lost we DROP the
        ;; gross-up (no gross-up if no credit usable — the cash dividend
        ;; alone is assessable in the books already).
        pit-additions
        (cond-> []
          (and (pos? gross-up-sum) (not= fate-franking :lost))
          (conj gross-up-sum)
          (pos? unfranked-cash-sum)
          (conj unfranked-cash-sum)
          (and (pos? foreign-cash-sum) (not foreign-resident?))
          (conj foreign-cash-sum)
          (pos? interest-gross)
          (conj interest-gross))
        fold-code (if (cit-kinds kind) :cit-base-additions :pit-base-additions)
        ;; --- corporate-franking-account-pending --------------------------
        ;; For :company holders, the franking credit ATTACHED that exceeds
        ;; the holder's own CIT liability flows to the recipient's franking
        ;; account (note 153 §1.8 / §2.4). We emit the SIGNAL here; the
        ;; downstream issuer-side workflow (future) consumes it. We pass
        ;; the GROSS franking credit (the consumer can subtract their CIT
        ;; share themselves).
        au-fac-pending
        (when (and (= kind :company) (pos? franking-credit-sum))
          franking-credit-sum)
        ;; --- per-event line items for audit ------------------------------
        per-event-lines
        (mapv
         (fn [c]
           (let [evt (:event c)
                 lbl (case (:kind c)
                       :franked-dividend   (str "Franked dividend (rate "
                                                (:corporate-rate c) ")")
                       :foreign-dividend   (str "Foreign-source dividend ("
                                                (name (:foreign-jurisdiction evt))
                                                ")")
                       :unfranked-dividend "Unfranked dividend")]
             {:line    (:kind c)
              :label   lbl
              :value   (money/money (:cash c) commodity)
              :franking-credit (money/money (:franking-credit c) commodity)
              :gross-up        (money/money (:gross-up c) commodity)
              :disqualified?   (boolean (:disqualified? c))
              :holding-test    (:holding-period-test c)}))
         dividends)
        summary-lines
        (cond-> []
          true
          (conj {:line :period-franking-credit-total
                 :label "Period franking-credit total (pre-disqualification)"
                 :value (money/money period-fc-total commodity)})
          true
          (conj {:line :ytd-franking-credit-claimed
                 :label "YTD franking-credit-claimed (carry-in)"
                 :value (money/money ytd-claimed commodity)})
          true
          (conj {:line :small-shareholder-exemption?
                 :label "Small-shareholder exemption applied (s.160APHT)"
                 :value exemption-applies?})
          true
          (conj {:line :franking-fate
                 :label "Franking-credit fate for this holder"
                 :value fate-franking})
          (pos? interest-gross)
          (conj {:line :interest-gross
                 :label "Interest income (gross)"
                 :value (money/money interest-gross commodity)})
          (pos? tfn-prepaid)
          (conj {:line :tfn-prepaid
                 :label "TFN withholding (refundable prepayment)"
                 :value (money/money tfn-prepaid commodity)})
          (some? au-fac-pending)
          (conj {:line :au-franking-account-credit-pending
                 :label "Pending credit to recipient's franking account (corp chain)"
                 :value (money/money au-fac-pending commodity)}))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money gross-up-sum commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :credits         credits
     :liability       (money/money (- liability-credits-sum) commodity)
     :prepaid         (money/money tfn-prepaid commodity)
     :regime          kind
     :line-items      (vec (concat per-event-lines summary-lines))
     :provenance      {:provider-id :au-investment-income
                       :statute     "ITAA 1997 Part 3-6 (s.207-20/45/150) + ITAA 1936 former Pt IIIAA Div 1A (s.160APHO/APHT) + ITAA 1997 s.770-10 + ITAA 1936 Pt VA"}
     :jurisdiction-specific-codes
     (cond-> {:lane                :au-investment-income
              :holder-kind         kind
              :franking-fate       fate-franking
              :foreign-resident?   foreign-resident?
              :small-shareholder-exemption? exemption-applies?
              :emits-inputs        {:au-franking-credit-ytd-claimed
                                    (+ ytd-claimed period-fc-total)}}
       (seq pit-additions)        (assoc fold-code pit-additions)
       (some? au-fac-pending)     (assoc :au-franking-account-credit-pending
                                         au-fac-pending)
       (= kind :super-fund)       (assoc :super-fund-phase
                                         (or (:super-fund-phase tax-unit)
                                             :accumulation))
       (= kind :trust)            (assoc :trust-kind
                                         (or (:trust-kind tax-unit)
                                             :discretionary-no-fte)))}))

;; ============================================================================
;; Provider
;; ============================================================================

(defrecord AuInvestmentIncomeTaxProvider
           [id authority commodity statute kind]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs tax-unit] :as ctx}]
    (when-not (contains? kinds kind)
      (throw (ex-info ":kind must be one of #{:individual :trust :super-fund :company}"
                      {:kind kind})))
    (when-let [phase (:super-fund-phase tax-unit)]
      (when-not (contains? super-fund-phases phase)
        (throw (ex-info ":tax-unit :super-fund-phase must be :accumulation or :pension"
                        {:super-fund-phase phase}))))
    (when-let [tk (:trust-kind tax-unit)]
      (when-not (contains? trust-kinds tk)
        (throw (ex-info ":tax-unit :trust-kind must be :fixed / :discretionary-fte / :discretionary-no-fte"
                        {:trust-kind tk}))))
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AU investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          cutoff-days (long (or (param db "AU.InvIncome.holding-period-days" as-of) 45))
          exempt    (or (param db "AU.InvIncome.small-shareholder-exemption" as-of) 5000M)
          ytd       (bigdec0 (get-in inputs [:au-franking-credit-ytd-claimed]))
          events    (or (:au-investment-income-events inputs) [])
          dividends-raw (filter #(= :dividend (:kind %)) events)
          interest      (filter #(= :interest (:kind %)) events)
          dividends-classified
          (mapv #(classify-dividend db as-of %) dividends-raw)
          {:keys [classified exemption-applies? period-fc-total]}
          (apply-holding-period-gate dividends-classified
                                     cutoff-days exempt kind ytd)
          interest-tot (interest-totals interest)
          components
          (if (and (empty? classified) (zero? (:gross interest-tot)))
            []
            [(build-component {:authority authority
                               :commodity commodity
                               :kind      kind
                               :tax-unit  (or tax-unit {})}
                              classified
                              interest-tot
                              exemption-applies?
                              ytd
                              period-fc-total)])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :au :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn au-investment-income-provider
  "Build an AU investment-income provider. Required:
     :kind — one of #{:individual :trust :super-fund :company}

   Optional:
     :id        — provider id (defaults derived from :kind)
     :authority — taxing authority (default :au-ato)
     :commodity — money commodity (default :AUD)

   Consumer supplies the per-event facts via
   `:inputs :au-investment-income-events` — a vector of maps:

       {:kind                   :dividend | :interest
        :cash-amount            <bigdec>      ; the cash on the books
        :franking-percent       <bigdec>      ; 0..1 (dividend only; default 1)
        :corporate-rate         <bigdec>      ; optional override (default
                                              ;   from :elective-regime BRE
                                              ;   flag or 30%)
        :elective-regime        #{:au-frank-distributor-bre}  ; opt
        :holding-days           <long>        ; required for the 45-day test
        :foreign-jurisdiction   <kw>          ; foreign-source flag
        :foreign-tax-withheld   <bigdec>      ; in AUD-equiv at distribution
        :tfn-prepaid            <bigdec>      ; interest only; refundable
        :event-date             <inst>}        ; for audit only

   Consumer also threads:
       :tax-unit {:super-fund-phase :accumulation | :pension
                  :trust-kind       :fixed | :discretionary-no-fte
                                      | :discretionary-fte
                  :foreign-resident? <bool>}
       :inputs   {:au-franking-credit-ytd-claimed <bigdec>}  ; for s.160APHT cap"
  [{:keys [kind id authority commodity]}]
  (when-not (contains? kinds kind)
    (throw (ex-info (str ":kind must be one of " kinds)
                    {:kind kind})))
  (->AuInvestmentIncomeTaxProvider
   (or id (keyword (str "au-investment-income-" (name kind))))
   (or authority :au-ato)
   (or commodity :AUD)
   "ITAA 1997 Part 3-6 + ITAA 1936 Pt IIIAA Div 1A (savings) + s.770-10 + Pt VA"
   kind))


(ns kontor.l10n-cn.investment-income-provider
  "CN investment-income tax providers — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. Research note 158.

   The CN regime is TWO sibling providers (note 158 §5), mirroring the
   CN CGT pattern:

   - `cn-iit-investment-income-provider` — IIT category 7 利息、股息、
     红利所得 — 20 % flat for individuals on dividend + interest.
     Listed A-share dividends use Caishui [2015] 101's holding-period
     gradation (≤ 1m full / 1m–1y half / > 1y exempt) via ADR-101
     Addendum 1 `:op :schedule-override`. Stock Connect H-share
     dividends are EXEMPT for mainland residents through 2027-12-31
     (Caishui [2014] 81 — Addendum 2 `period-from-before`). Bank
     savings deposit interest is 0 % (Caishui [2008] 132). Government
     bond interest is 0 % (个税法 §4(2) + 国债条例).

   - `cn-eit-investment-income-provider` — EIT corporate side. The
     §26(2) inter-TRR dividend exemption fires for qualifying dividends
     (TRR-to-TRR, > 12 month hold on listed, NO partnership in holding
     chain — Caishui [2008] 159 §4 partnership-veil footgun). Foreign-
     source dividends folded into CIT base with `:cit-foreign-tax-
     credit` for §23 FTC. Non-resident WHT (10 % / 20 % per holder
     class) emitted as own components for outbound flow.

   ## No-aggregation discipline (note 158 §6.4)

   Unlike JP/US/DE, CN category 7 is per-receipt — NOT aggregated into
   综合所得 (the comprehensive-income base of cat 1-4). The IIT
   provider returns one multi-line `:investment-income-tax` component
   with the period's category-7 liability; it does NOT thread
   `:pit-base-additions` to the personal-income provider (contrast
   `jp-investment-income-provider`'s 総合 election).

   ## Deferred-recognition seam (note 158 §3.2)

   Caishui [2015] 101 IIT on < 1 year listed A-share dividends
   technically crystallises at SALE-or-year-end, not at the record
   date. v1 emits at the trigger event (i.e. the consumer's posted
   dividend event); deferred-recognition refinement deferred per
   §3.2's recommended path.

   ## Income event surface

   The consumer supplies `:inputs :investment-income-events` — a vector
   of maps:

       {:event-id     <opaque>
        :income-class <closed enum, see income-classes>
        :amount       <BigDecimal in CNY>
        :withheld     <BigDecimal — optional, paying-agent withholding>
        :holding-days <long — for listed-A gradation>
        :holding-chain  [<keyword vector — e.g. [:participation :partnership :issuer]>]
        :foreign-tax-paid <BigDecimal — optional, §23 FTC input>
        :treaty-rate  <BigDecimal — optional, non-resident WHT reduction>}

   The provider's `:tax-unit :tax-residency` keys the four-way
   holder-class branch (`:resident-individual` / `:resident-corporation`
   / `:non-resident-individual` / `:non-resident-corporation`)."
  (:require [kontor.l10n-cn.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.statute :as statute]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Closed enums — note 158 §4 vocabulary
;; ============================================================================

(def income-classes
  "Closed CN category-7 income-class set the IIT investment-income
   provider classifies on. Note 158 §4.5 — 14 values."
  #{:listed-a-share-dividend         ; SSE / SZSE / BSE A-share, graded
    :listed-b-share-dividend         ; foreign-currency B-share
    :stock-connect-h-share-dividend  ; HK via Connect — flat (sunset 2027-12-31)
    :unlisted-equity-dividend        ; private corp dividend
    :foreign-corp-dividend           ; non-resident corp dividend
    :cn-bank-deposit-interest        ; 0 % since Caishui [2008] 132
    :government-bond-interest        ; 0 % under §4(2) + 国债条例
    :financial-bond-interest         ; 0 % at IIT layer
    :corporate-bond-interest         ; 20 %
    :other-interest                  ; private lending — 20 %
    :wmp-distribution                ; wealth-management — taxed at issuer
    :c-reit-distribution             ; C-REITs — 20 % individual, 25 % corp
    :partnership-allocated-dividend  ; allocation from partnership / VC fund
    :foreign-interest})              ; foreign-source interest — 20 % + FTC

(def holder-classes
  "Closed `:holder-class` set for the CN investment-income providers.
   Note 158 §4.4 — 5 values."
  #{:resident-individual
    :resident-corporation
    :non-resident-individual
    :non-resident-corporation
    :partnership-vehicle})          ; flag for §26(2) NO-EXEMPTION footgun

(def iit-exempt-classes
  "Income classes for which the IIT provider emits NO COMPONENT
   (provider-side exemption check). These are also represented as
   :base-deduct provisions in the statute for audit purposes."
  #{:cn-bank-deposit-interest
    :government-bond-interest
    :financial-bond-interest
    :wmp-distribution})

(def eit-26-2-eligible-classes
  "Income classes that are CANDIDATE for the §26(2) inter-TRR
   exemption (qualifying period + direct holding + no partnership-
   vehicle in chain are additional gates). Note 158 §1.5."
  #{:listed-a-share-dividend
    :listed-b-share-dividend
    :stock-connect-h-share-dividend
    :unlisted-equity-dividend})

;; ============================================================================
;; Helpers — provider-internal
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- holding-band
  "Classify a `:holding-days` value into the Caishui [2015] 101 band
   (note 158 §3.3 shared helper). Returns one of `:le-1m | :1m-1y |
   :gt-1y`. Defaults to `:le-1m` (most-taxable band) when days are
   missing — the conservative direction.

   Boundaries per Caishui [2015] 101: ≤ 31 days = le-1m, 32-365 days =
   1m-1y, > 365 days = gt-1y (strict, see note 158 §2 example A
   footnote)."
  [^Long holding-days]
  (let [d (or holding-days 0)]
    (cond
      (<= d 31)  :le-1m
      (<= d 365) :1m-1y
      :else      :gt-1y)))

(defn- partnership-in-chain?
  "True iff `:partnership-vehicle` (or `:partnership`) appears in the
   `:holding-chain` of the event. Note 158 §6.3 — the §26(2) NO-
   EXEMPTION footgun. Caishui [2008] 159 §4 — partnership veil for
   §26(2) is NOT pierced even though it IS pierced for tax-transparency."
  [event]
  (let [chain (set (:holding-chain event))]
    (or (contains? chain :partnership-vehicle)
        (contains? chain :partnership))))

(defn- listed?
  [event]
  (#{:listed-a-share-dividend
     :listed-b-share-dividend
     :stock-connect-h-share-dividend} (:income-class event)))

(defn- qualifies-for-26-2?
  "Predicate for the EIT §26(2) inter-TRR exemption. Checks:

   - `:income-class` is in `eit-26-2-eligible-classes` (excludes
     `:c-reit-distribution`, `:partnership-allocated-dividend`,
     `:foreign-corp-dividend`).
   - `:holder-class :resident-corporation` (caller is a TRR).
   - For listed classes: `(>= holding-days inter-TRR-hold-days)` —
     12 months × 30 days ≈ 365 days approximated as 365.
   - NOT `:partnership-vehicle` in `:holding-chain` (note 158 §6.3).

   Note 158 §1.5 / §5.2 — the partnership-veil footgun is THE single
   most important predicate in this provider's correctness story."
  [event ctx]
  (let [residency (or (get-in ctx [:tax-unit :tax-residency])
                      :resident-corporation)
        cls       (:income-class event)
        days      (or (:holding-days event) 0)
        ;; 12 months — 365 days approximation per note 158 §6.2 (the
        ;; EIT side uses calendar-month rolling vs IIT's strict
        ;; > 365 days). v1 uses 365 days for both.
        threshold-days 365]
    (and (= residency :resident-corporation)
         (contains? eit-26-2-eligible-classes cls)
         (not (partnership-in-chain? event))
         (or (not (listed? event))
             (>= days threshold-days)))))

;; ============================================================================
;; IIT — per-event classification + base/liability computation
;; ============================================================================

(defn- iit-listed-a-share-tax
  "Apply Caishui [2015] 101 holding-period gradation to one listed
   A-share dividend event. Returns `{:base <bigdec> :rate <bigdec>
   :tax <bigdec> :band <kw> :schedule-override <provenance> }`.

   Uses ADR-101 Addendum 1 `:op :schedule-override` — the provider
   injects `:holding-band` into ctx and asks the statute evaluator to
   pick the matching schedule (le-1m flat 0.20, 1m-1y flat 0.10,
   gt-1y flat 0)."
  [db event ctx as-of]
  (let [band      (holding-band (:holding-days event))
        scoped    (assoc ctx
                         :income-class :listed-a-share-dividend
                         :holding-band band
                         :db           db)
        {:keys [schedule-overrides]}
        (statute/apply-provisions
         db {:concept :holding-period-preference
             :jurisdiction :cn :as-of as-of}
         scoped)
        ;; The highest-priority override wins; we expect exactly one
        ;; (the three provisions have priorities 100/110/120 keyed
        ;; on disjoint :holding-band values).
        chosen    (last schedule-overrides)
        schedule  (or (:schedule chosen)
                      ;; Fallback: 20 % flat if no override matched
                      ;; (defensive — should not happen with statute
                      ;; installed).
                      (ts/flat (or (statute/parameter-value-at
                                    db "CN.IIT.investment-income.flat-rate" as-of)
                                   0.20M)))
        rate      (or (:rate schedule) 0M)
        amount    (or (:amount event) 0M)
        tax       (ts/apply-schedule schedule amount)]
    {:base     amount
     :rate     rate
     :tax      tax
     :band     band
     :schedule schedule
     :provenance (:provenance chosen)}))

(defn- iit-stock-connect-tax
  "Stock Connect H-share dividend — exempt for mainland residents
   through 2027-12-31 via the Caishui [2014] 81 schedule-override
   provision (gated by `period-from-before #inst \"2028-01-01\"`).
   For periods beginning before the sunset the override picks 0 % flat;
   otherwise the standard 20 % default applies."
  [db event ctx as-of]
  (let [scoped (assoc ctx
                      :income-class :stock-connect-h-share-dividend
                      :db           db)
        {:keys [schedule-overrides]}
        (statute/apply-provisions
         db {:concept :participation-exemption
             :jurisdiction :cn :as-of as-of}
         scoped)
        chosen   (last schedule-overrides)
        schedule (or (:schedule chosen)
                     (ts/flat (or (statute/parameter-value-at
                                   db "CN.IIT.investment-income.flat-rate" as-of)
                                  0.20M)))
        rate     (or (:rate schedule) 0M)
        amount   (or (:amount event) 0M)
        tax      (ts/apply-schedule schedule amount)]
    {:base     amount
     :rate     rate
     :tax      tax
     :schedule schedule
     :provenance (:provenance chosen)}))

(defn- iit-flat-tax
  "Apply the IIT category 7 flat 20 % rate (or whatever the parameter
   currently is) to a dividend / interest event. Used for unlisted
   equity, listed B-share (no gradation), corporate-bond interest,
   foreign dividend (before FTC), C-REIT distribution, etc."
  [db event as-of]
  (let [rate   (or (statute/parameter-value-at
                    db "CN.IIT.investment-income.flat-rate" as-of)
                   0.20M)
        amount (or (:amount event) 0M)]
    {:base amount
     :rate rate
     :tax  (* amount rate)}))

(defn- iit-non-resident-tax
  "Non-resident individual dividend — 20 % WHT per IIT Law §3, treaty-
   reducible. Reads `:tax-unit :treaty-rate` for the override."
  [db event ctx as-of]
  (let [base-rate (or (statute/parameter-value-at
                       db "CN.IIT.outbound-wht-rate" as-of)
                      0.20M)
        treaty   (get-in ctx [:tax-unit :treaty-rate])
        rate     (if (some? treaty) (bigdec treaty) base-rate)
        amount   (or (:amount event) 0M)]
    {:base amount
     :rate rate
     :tax  (* amount rate)}))

;; ============================================================================
;; The IIT investment-income provider
;; ============================================================================

(defn- iit-classify-event
  "Classify one event. Returns one of:
     {:lane :exempt     :reason <kw> :event ev}
     {:lane :graded     :base/rate/tax/band ... :event ev}
     {:lane :flat       :base/rate/tax ...  :event ev}
     {:lane :stock-connect :base/rate/tax ... :event ev}
     {:lane :non-resident  :base/rate/tax ... :event ev}

   The lane drives both the line-item label and the audit story."
  [db event ctx as-of residency]
  (let [cls (:income-class event)]
    (cond
      ;; Permanent exemptions (bank deposit / gov bond / financial bond /
      ;; wrapped WMP) — provider returns no component for them.
      (contains? iit-exempt-classes cls)
      {:lane :exempt :reason cls :event event}

      ;; Non-resident individual — 20 % outbound WHT (treaty-reducible).
      (= residency :non-resident-individual)
      (merge {:lane :non-resident :event event}
             (iit-non-resident-tax db event ctx as-of))

      ;; Resident individual — full routing.
      (= residency :resident-individual)
      (case cls
        :listed-a-share-dividend
        (merge {:lane :graded :event event}
               (iit-listed-a-share-tax db event ctx as-of))

        :stock-connect-h-share-dividend
        (merge {:lane :stock-connect :event event}
               (iit-stock-connect-tax db event ctx as-of))

        ;; Everything else flat 20 % (unlisted, B-share, corporate
        ;; bond, foreign corp, foreign interest, C-REIT, partnership-
        ;; allocated, other-interest).
        (merge {:lane :flat :event event}
               (iit-flat-tax db event as-of)))

      :else
      ;; Resident-corporation events should not reach the IIT provider;
      ;; defensively flat-rate them but flag as :flat.
      (merge {:lane :flat :event event}
             (iit-flat-tax db event as-of)))))

(defn- iit-component
  "Build the single multi-line CN IIT investment-income component."
  [{:keys [commodity authority]}
   classified
   ^java.math.BigDecimal prepaid
   ^java.math.BigDecimal foreign-ftc
   provenance]
  (let [taxed (remove #(= :exempt (:lane %)) classified)
        gross (reduce + 0M (map :tax taxed))
        ;; FTC reduces liability but not gross-liability (audit-friendly).
        net   (max 0M (- gross foreign-ftc))
        liability (max 0M (- net prepaid))
        sum-base  (reduce + 0M (map :base taxed))
        rate-bases (group-by :rate taxed)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money sum-base commodity)
     :schedule        nil   ; multi-rate; rates surface in line-items
     :gross-liability (money/money gross commodity)
     :liability       (money/money liability commodity)
     :prepaid         (money/money prepaid commodity)
     :provenance      provenance
     :line-items
     (vec
      (concat
       ;; Per-rate aggregated lines for the audit trail.
       (for [[rate items] (sort-by first rate-bases)
             :let [b (reduce + 0M (map :base items))
                   t (reduce + 0M (map :tax  items))]]
         {:line  (keyword (str "cn-cat7-rate-" (.toPlainString ^java.math.BigDecimal (bigdec rate))))
          :label (str "Category 7 @ " (.toPlainString ^java.math.BigDecimal (bigdec rate)) " on base " b)
          :value (money/money t commodity)})
       ;; Per-event audit lines for the exempt classes.
       (for [{:keys [event reason]} (filter #(= :exempt (:lane %)) classified)]
         {:line  (keyword (str "cn-cat7-exempt-" (name reason)))
          :label (str "Exempt (" (name reason) "): " (or (:event-id event) "—"))
          :value (money/money (or (:amount event) 0M) commodity)})
       ;; Foreign tax credit.
       (when (pos? foreign-ftc)
         [{:line  :cn-cat7-foreign-tax-credit
           :label "§7 IIT Law — foreign tax credit"
           :value (money/money (- foreign-ftc) commodity)}])
       ;; Prepaid line for audit symmetry.
       [{:line  :cn-cat7-prepaid
         :label "Paying-agent withholding (prepaid)"
         :value (money/money prepaid commodity)}]))
     :jurisdiction-specific-codes {:lane :cn-iit-investment-income}}))

(defrecord CnIitInvestmentIncomeProvider [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (:db ctx)
      (throw (ex-info ":db required in ctx for CN IIT investment-income provider"
                      {:ctx-keys (keys ctx)})))
    (let [db        (:db ctx)
          as-of     (as-of-from-ctx ctx)
          residency (or (get-in ctx [:tax-unit :tax-residency])
                        :resident-individual)
          events    (or (:investment-income-events inputs) [])
          ;; The IIT provider only sees individual-side events. Corporate
          ;; events route to the EIT provider; drop them here.
          relevant  (remove (fn [_]
                              ;; If the consumer set :tax-residency to a
                              ;; corporation, the IIT provider returns
                              ;; nothing. Otherwise everything is in-scope.
                              (#{:resident-corporation :non-resident-corporation}
                               residency))
                            events)
          classified (mapv #(iit-classify-event db % ctx as-of residency) relevant)
          ;; Sum paying-agent withholdings as prepaid against the gross.
          prepaid    (reduce + 0M (keep (fn [{:keys [event lane]}]
                                          (when-not (= :exempt lane)
                                            (:withheld event)))
                                        classified))
          ;; Foreign tax credit aggregate (per-event, applied at provider
          ;; level — note 158 §3 cleaner than per-source).
          foreign-ftc (reduce + 0M (keep (fn [{:keys [event lane]}]
                                           (when-not (= :exempt lane)
                                             (:foreign-tax-paid event)))
                                         classified))
          provenance {:provider-id id :statute statute}
          components (if (or (empty? classified)
                             ;; Skip when all events are exempt AND
                             ;; nothing was prepaid / nothing to record.
                             (and (every? #(= :exempt (:lane %)) classified)
                                  (zero? prepaid)
                                  (zero? foreign-ftc)))
                       []
                       [(iit-component
                         {:authority authority :commodity commodity}
                         classified
                         (bigdec prepaid)
                         (bigdec foreign-ftc)
                         provenance)])]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :cn :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; The EIT investment-income provider
;; ============================================================================

(defn- eit-non-resident-wht
  "Compute the non-resident dividend WHT for a single outbound event.
   10 % standard (Caishui [2008] 130) for corporate non-residents;
   reduced by treaty when `:treaty-rate` is supplied. For individual
   non-residents the IIT provider handles them; this is only the
   corporate-payer side of the EIT provider's outbound WHT lane."
  [db event ctx as-of]
  (let [base-rate (or (statute/parameter-value-at
                       db "CN.EIT.outbound-wht-rate" as-of)
                      0.10M)
        treaty    (get-in ctx [:tax-unit :treaty-rate])
        rate      (if (some? treaty) (bigdec treaty) base-rate)
        amount    (or (:amount event) 0M)]
    {:amount amount :rate rate :tax (* amount rate)}))

(defn- eit-non-resident-component
  "Emit a single multi-line component aggregating outbound WHT
   liabilities for the period. Each outbound event surfaces as a
   line-item; the component's `:liability` is the period total."
  [{:keys [commodity authority]} events ctx db as-of]
  (let [computed (for [ev events]
                   (assoc (eit-non-resident-wht db ev ctx as-of) :event ev))
        gross    (reduce + 0M (map :tax computed))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money (reduce + 0M (map :amount computed)) commodity)
     :schedule        nil
     :gross-liability (money/money gross commodity)
     :liability       (money/money gross commodity)
     :prepaid         (money/zero commodity)
     :line-items
     (vec
      (for [{:keys [event rate tax amount]} computed]
        {:line  :cn-eit-outbound-wht
         :label (str "Outbound WHT @ "
                     (.toPlainString ^java.math.BigDecimal (bigdec rate))
                     " on dividend " (or (:event-id event) "—"))
         :value (money/money tax commodity)}))
     :jurisdiction-specific-codes {:lane :cn-eit-outbound-wht
                                   :outbound-events
                                   (mapv (fn [{:keys [event rate tax amount]}]
                                           {:event-id (:event-id event)
                                            :amount   amount
                                            :rate     rate
                                            :tax      tax})
                                         computed)}}))

(defn- eit-exempt-component
  "Emit the `:cit-base-deductions` component for §26(2)-qualifying
   dividends — these dividends are excluded from the EIT base, so the
   provider returns NO own liability but signals the deduction so the
   CIT provider can deduct it from its base."
  [{:keys [commodity authority]} excluded]
  (let [total (reduce + 0M (map #(or (:amount %) 0M) excluded))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (vec
                       (for [ev excluded]
                         {:line  :cn-eit-26-2-inter-trr-dividend-exemption
                          :label (str "§26(2) exemption: " (or (:event-id ev) "—"))
                          :value (money/money (or (:amount ev) 0M) commodity)}))
     :jurisdiction-specific-codes {:lane :cn-eit-investment-income
                                   :cit-base-deductions [total]}}))

(defn- eit-domestic-included-component
  "Emit the `:cit-base-additions` component for domestic non-qualifying
   corporate dividends — included in the CIT base at 25 % with NO
   foreign-tax credit. Separate from `eit-foreign-fold-component` so
   the downstream CIT provider can apply §23 FTC capping only to the
   foreign portion."
  [{:keys [commodity authority]} domestic-events]
  (let [total (reduce + 0M (map #(or (:amount %) 0M) domestic-events))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (vec
                       (for [ev domestic-events]
                         {:line  :cn-eit-non-qualifying-dividend
                          :label (str "Non-qualifying dividend (to CIT base): "
                                      (or (:event-id ev) "—"))
                          :value (money/money (or (:amount ev) 0M) commodity)}))
     :jurisdiction-specific-codes {:lane                :cn-eit-domestic-included
                                   :cit-base-additions  [total]}}))

(defn- eit-foreign-fold-component
  "Emit the `:cit-base-additions` + `:cit-foreign-tax-credit`
   component for foreign-source corporate dividends — included in the
   CIT base at 25 %, with §23 FTC threaded as a separate adjustment."
  [{:keys [commodity authority]} foreign-events]
  (let [total       (reduce + 0M (map #(or (:amount %) 0M) foreign-events))
        ftc-total   (reduce + 0M (map #(or (:foreign-tax-paid %) 0M) foreign-events))]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money total commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :line-items      (vec
                       (concat
                        (for [ev foreign-events]
                          {:line  :cn-eit-foreign-dividend
                           :label (str "Foreign dividend (§23 FTC eligible): "
                                       (or (:event-id ev) "—"))
                           :value (money/money (or (:amount ev) 0M) commodity)})
                        [{:line  :cn-eit-foreign-tax-credit
                          :label "§23-24 EIT Law — foreign tax credit"
                          :value (money/money (- ftc-total) commodity)}]))
     :jurisdiction-specific-codes
     {:lane                       :cn-eit-foreign
      :cit-base-additions         [total]
      :cit-foreign-tax-credit     [{:amount ftc-total
                                    :events (mapv (fn [ev]
                                                    {:event-id (:event-id ev)
                                                     :amount   (:foreign-tax-paid ev)})
                                                  foreign-events)}]}}))

(defrecord CnEitInvestmentIncomeProvider [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (when-not (:db ctx)
      (throw (ex-info ":db required in ctx for CN EIT investment-income provider"
                      {:ctx-keys (keys ctx)})))
    (let [db        (:db ctx)
          as-of     (as-of-from-ctx ctx)
          residency (or (get-in ctx [:tax-unit :tax-residency])
                        :resident-corporation)
          events    (or (:investment-income-events inputs) [])
          ;; Split: corporate-resident events go through §26(2)
          ;; check + foreign-fold; non-resident events drive the
          ;; outbound WHT lane.
          corp-events (filter (fn [_] (= residency :resident-corporation)) events)
          non-resident-events
          (filter (fn [_] (= residency :non-resident-corporation)) events)
          ;; Apply §26(2) predicate (note 158 §5.2 — the partnership-
          ;; veil footgun lives here).
          {excluded true included false}
          (group-by #(qualifies-for-26-2? % ctx) corp-events)
          ;; Foreign-source corporate dividends — included with FTC.
          foreign (filter #(= :foreign-corp-dividend (:income-class %)) included)
          ;; Non-foreign included corporate dividends — added to CIT
          ;; base at 25 % (CIT provider folds), no FTC.
          domestic-included (remove #(= :foreign-corp-dividend (:income-class %)) included)
          components
          (cond-> []
            (seq excluded)
            (conj (eit-exempt-component
                   {:authority authority :commodity commodity}
                   excluded))

            (seq domestic-included)
            (conj (eit-domestic-included-component
                   {:authority authority :commodity commodity}
                   domestic-included))

            (seq foreign)
            (conj (eit-foreign-fold-component
                   {:authority authority :commodity commodity}
                   foreign))

            (seq non-resident-events)
            (conj (eit-non-resident-component
                   {:authority authority :commodity commodity}
                   non-resident-events ctx db as-of)))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :cn :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn cn-iit-investment-income-provider
  "Build the CN IIT investment-income provider (category 7 利息、股息、
   红利所得 — 20 % flat with the Caishui [2015] 101 listed-A
   gradation + Caishui [2014] 81 Stock Connect exemption +
   Caishui [2008] 132 bank deposit exemption)."
  [{:keys [id]}]
  (->CnIitInvestmentIncomeProvider
   (or id :cn-iit-investment-income) :cn-sat :CNY
   "中华人民共和国个人所得税法 §3 §7 + Caishui [1998] 132 / Caishui [2014] 81 / Caishui [2015] 101"))

(defn cn-eit-investment-income-provider
  "Build the CN EIT investment-income provider (§26(2) inter-TRR
   exemption + §23 foreign tax credit + outbound WHT)."
  [{:keys [id]}]
  (->CnEitInvestmentIncomeProvider
   (or id :cn-eit-investment-income) :cn-sat :CNY
   "中华人民共和国企业所得税法 §4 §23 §26(2) + Caishui [2008] 130 / [2008] 159 / [2014] 81"))


(ns kontor.l10n-at.investment-income-provider
  "AT investment-income tax providers — `PeriodTaxProvider` (ADR-099)
   over the ADR-101 statute-as-data substrate. 

   ## Two providers, two flavours

   - `at-kest-investment-income-provider` (`:kind :individual`) —
     §27/§27a + §97 EStG KESt-Endbesteuerung on dividends + bank
     interest. Two rate buckets:
       :wertpapier-vermoegen (27.5 % — dividends + bond/fund interest)
       :sparbuch             (25 %  — bank-deposit interest)
     Endbesteuerung default (KESt withheld at source discharges the
     liability — provider emits a near-zero standalone component).
     Regelbesteuerungsoption (§27a Abs 5 EStG) SUPPRESSES the standalone
     and folds gross into PIT base via `:pit-base-additions`; the
     prepaid KESt rides via `:pit-credits {:refundable? true}` so the
     downstream PIT provider can refund excess (the rational election
     when marginal-rate < 27.5 %).

   - `at-corporate-investment-income-provider` (`:kind :corporation`) —
     §10 KStG INVERSION: default-exempt for qualifying participation
     income (domestic / foreign-portfolio / Schachtelbeteiligung); the
     consumer opts INTO taxable via
     `:elective-regime :at-§10-tax-effective-option`. Default branch
     emits `:cit-base-deductions` to REMOVE the dividend from the CIT
     taxable base (the GL booked it as ordinary income; the deduction
     neutralises it).
     `§10 Abs 4 switch-over` — when the foreign corp is in a low-tax
     jurisdiction, the exemption switches off and the dividend becomes
     fully taxable. Provider compares consumer-attested ETR against the
     bitemporal threshold parameter (12.5 % → 15 % from 2026 per
     Mindestbesteuerungsgesetz / Pillar Two).
     `§10 foreign-corp guard`: when
     `:tax-unit :held-entity-domestic? true`, the §10 default-exempt
     INVERSION does NOT fire for foreign-portfolio / Schachtel
     classifications — the dividend stays in CIT base as ordinary
     income. The domestic-dividend case (§10 Abs 1 Z 1) remains exempt
     for AT-resident corp recipients.

   ## §10 INVERSION direction

   The DEFAULT is EXEMPT — `:cit-base-deductions` (remove dividend from
   CIT base). The OPT-IN regime
   `:elective-regime :at-§10-tax-effective-option` flips to TAXABLE —
   `:cit-base-additions` (re-include if the dividend was somehow
   already deducted in the GL booking, or zero-effect if GL booked at
   gross which is the standard case; provider emits a neutralising
   component for audit/explainability).

   ## BFG 2024 ruling

   When §10 KStG exempts the dividend on the corporate side, the
   DBA-Quellensteuer credit cap = 0 (Lang 2024, SWI 2024/06). Provider
   emits NO foreign-tax credit in the default-exempt branch; the
   `:kontor.audit-doc/category :at-dba-credit-lost-bfg-2024` records the lost
   recovery opportunity for downstream consumer follow-up (foreign
   reclaim).

   ## §27 Abs 8 EStG within-year netting — DEFERRED v1

   The within-year CGT-losses ↔ investment-income netting is an
   ORCHESTRATOR-level concern across the AT KESt CGT provider + this
   provider. v1 does NOT implement the cross-
   provider netting — the consumer wires the two providers
   independently and the within-year netting is an explicit follow-up
   (per the orchestrator pattern in.3). Both providers
   stay pure; the orchestrator is a planned `kontor.l10n-at.kest-
   orchestrator/period-tax-facts` helper.

   ## Inputs

   The provider accepts BOTH a pre-computed lane map (the test path /
   1099-style upload path):

     :inputs :investment-income-bases
       {:dividends-27-5 <bigdec>          ; 27.5 % bucket dividends
        :bond-interest-27-5 <bigdec>      ; 27.5 % bucket bond interest
        :sparbuch-interest-25 <bigdec>    ; 25 % bucket bank interest
        :foreign-dividends-27-5 <bigdec>  ; foreign-source dividends (subset of dividends-27-5)
        :foreign-tax-withheld <bigdec>    ; total foreign WHT (EUR-equivalent)
        :foreign-treaty-rate <bigdec>     ; treaty-rate cap (default 0.15M)}

   Pre-prepaid KESt rides via `:inputs :at-kest-prepaid {:wertpapier
   <bigdec> :sparbuch <bigdec>}` (or a single scalar — treated as
   wertpapier-bucket).

   For the corporate side: the consumer supplies per-event
   classification + ownership data via
   `:inputs :investment-income-bases {:corporate-dividend-events [...]}`
   where each event is
     {:gross <bigdec>
      :§10-classification :domestic | :foreign-portfolio
                          | :schachtelbeteiligung | :ordinary
      :foreign-tax-withheld <bigdec>
      :foreign-treaty-rate <bigdec>
      :ownership-fraction <bigdec>
      :elective-regime #{:at-§10-tax-effective-option}}

   v1 does NOT scan the GL by chart-prefix (the cross-jurisdiction
   `:investment-income-event` substrate Gap 1 is not yet
   shipped); the consumer-supplied `:inputs :investment-income-bases`
   path is the v1 contract."
  (:require [kontor.l10n-at.investment-income-statute :as inv-statute]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute :as statute]
            [kontor.tax.tax-schedule :as ts]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- as-of-from-ctx
  ^java.util.Date [ctx]
  (or (:as-of ctx)
      (some-> ctx :period :to)
      (java.util.Date.)))

(defn- param
  ^java.math.BigDecimal [db code as-of]
  (statute/parameter-value-at db code as-of))

(defn- bigdec0
  ^java.math.BigDecimal [x]
  (or x 0M))

(defn- prepaid-for-bucket
  "Read `:inputs :at-kest-prepaid` — accepts either a scalar (legacy:
   treated as the wertpapier bucket) or a map
   `{:wertpapier <bd> :sparbuch <bd>}`."
  ^java.math.BigDecimal [ctx bucket]
  (let [raw (get-in ctx [:inputs :at-kest-prepaid])]
    (cond
      (nil? raw)     0M
      (map? raw)     (bigdec0 (get raw bucket))
      :else          (if (= bucket :wertpapier) raw 0M))))

;; ============================================================================
;; INDIVIDUAL — KESt-Endbesteuerung component (wertpapier 27.5 % bucket)
;; ============================================================================

(defn- kest-bucket-component
  "Build a KESt-Endbesteuerung component for ONE rate-bucket
   (wertpapier 27.5 % OR sparbuch 25 %). `gross` is the bucket's
   aggregated cash-amount (dividends + bond interest for wertpapier;
   bank deposit interest for sparbuch). Endbesteuerung default: emits
   the standalone component, `:liability` = gross-due − bank-prepaid
   (zero in the common case)."
  [{:keys [commodity authority]} ^java.math.BigDecimal gross
   ^java.math.BigDecimal rate
   ^java.math.BigDecimal prepaid
   bucket]
  (let [taxable  (max 0M gross)
        schedule (ts/flat rate)
        gross-tax (ts/apply-schedule schedule taxable)
        liab     (- gross-tax prepaid)
        bucket-label (case bucket
                       :wertpapier "§27a Abs 1 Z 2 EStG — KESt at 27.5 % (Wertpapier-Vermögen bucket)"
                       :sparbuch   "§27a Abs 1 Z 1 EStG — KESt at 25 % (Sparbuch / Geldeinlagen bucket)")
        lane     (case bucket
                   :wertpapier :at-kest-wertpapier-vermoegen
                   :sparbuch   :at-kest-sparbuch)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money taxable commodity)
     :schedule        schedule
     :gross-liability (money/money gross-tax commodity)
     :liability       (money/money liab commodity)
     :prepaid         (money/money prepaid commodity)
     :regime          :endbesteuerung
     :line-items      [{:line :at-kest-gross
                        :label "§27 EStG — bucket gross income (dividends + interest, year aggregate)"
                        :value (money/money gross commodity)}
                       {:line :at-kest-tax
                        :label bucket-label
                        :value (money/money gross-tax commodity)}
                       {:line :at-kest-bank-withheld
                        :label "§95 EStG — bank-withheld KESt (prepaid via :inputs)"
                        :value (money/money prepaid commodity)}]
     :jurisdiction-specific-codes {:lane lane
                                   :kest-bucket bucket}}))

(defn- regelbesteuerung-fold-component
  "Regelbesteuerungsoption (§27a Abs 5 EStG) — SUPPRESS the standalone
   KESt-Endbesteuerung component and fold the gross dividends +
   interest into the PIT base. The prepaid KESt rides via
   `:pit-credits {:refundable? true}` so the PIT provider can refund
   excess. This is the rational election when marginal rate < 27.5 %.

   The fold is ALL-OR-NOTHING across both buckets (§27a Abs 5 EStG:
   either all KESt-Vermögen income enters regular assessment, or none
   does)."
  [{:keys [commodity authority]}
   ^java.math.BigDecimal gross-27-5
   ^java.math.BigDecimal gross-25
   ^java.math.BigDecimal prepaid-total]
  (let [total-gross (+ gross-27-5 gross-25)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money total-gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/money prepaid-total commodity)
     :regime          :regelbesteuerung
     :line-items      [{:line :at-regelbesteuerung-gross-27-5
                        :label "§27a Abs 5 EStG — Regelbesteuerung: 27.5 % bucket gross folded into PIT"
                        :value (money/money gross-27-5 commodity)}
                       {:line :at-regelbesteuerung-gross-25
                        :label "§27a Abs 5 EStG — Regelbesteuerung: 25 % bucket gross folded into PIT"
                        :value (money/money gross-25 commodity)}
                       {:line :at-regelbesteuerung-prepaid
                        :label "§95 EStG — KESt prepaid (refundable against assessed PIT)"
                        :value (money/money prepaid-total commodity)}]
     :jurisdiction-specific-codes
     {:lane :at-regelbesteuerung-fold
      :pit-base-additions [total-gross]
      :pit-credits {:at-kest-prepaid prepaid-total
                    :refundable? true}}}))

(defn- dba-credit-component
  "DBA-Quellensteuer credit on foreign-source dividends. Capped at
   `min(actual-foreign-WHT, treaty-rate × foreign-dividend-gross)`.
   Non-refundable on the AT side (excess credit is lost — must be
   reclaimed at source). Surfaces as
   `:pit-credits-non-refundable {:at-dba-quellensteuer <amount>}` for
   the downstream PIT provider to apply against assessed liability."
  [{:keys [commodity authority]}
   ^java.math.BigDecimal foreign-gross
   ^java.math.BigDecimal foreign-tax-withheld
   ^java.math.BigDecimal treaty-rate]
  (let [cap     (* foreign-gross treaty-rate)
        allowed (min foreign-tax-withheld cap)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money foreign-gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :at-dba-quellensteuer
     :line-items      [{:line :at-dba-foreign-gross
                        :label "Foreign-source dividend gross (EUR-equivalent)"
                        :value (money/money foreign-gross commodity)}
                       {:line :at-dba-foreign-tax-withheld
                        :label "Foreign WHT (EUR-equivalent, before cap)"
                        :value (money/money foreign-tax-withheld commodity)}
                       {:line :at-dba-treaty-cap
                        :label "DBA treaty-rate cap (treaty-rate × foreign-gross)"
                        :value (money/money cap commodity)}
                       {:line :at-dba-credit-allowed
                        :label "DBA-Quellensteuer credit allowed (capped; non-refundable)"
                        :value (money/money allowed commodity)}]
     :jurisdiction-specific-codes
     {:lane :at-dba-quellensteuer-credit
      :pit-credits-non-refundable {:at-dba-quellensteuer allowed}}}))

;; ============================================================================
;; CORPORATE — §10 KStG components
;; ============================================================================

(def ^:private §10-classifications
  "Closed set of §10 KStG classifications a corporate-dividend event
   may carry. `:domestic` and `:foreign-portfolio` /
   `:schachtelbeteiligung` route to the default-exempt branch (subject
   to the §10 Abs 4 switch-over and the foreign-corp guard);
   `:ordinary` routes to no-deduction (taxed in CIT as ordinary
   income)."
  #{:domestic :foreign-portfolio :schachtelbeteiligung :ordinary})

(def ^:private §10-option-flag
  ":elective-regime flag that opts INTO the §10 KStG tax-effective
   regime."
  :at-§10-tax-effective-option)

(defn- elective-set
  "Normalize `:elective-regime` to a set (accepts coll, single keyword,
   or nil)."
  [event]
  (let [r (:elective-regime event)]
    (cond
      (nil? r)      #{}
      (coll? r)     (set r)
      (keyword? r)  #{r}
      :else         #{})))

(defn- §10-option-elected?
  [event]
  (contains? (elective-set event) §10-option-flag))

(defn- §10-switch-over?
  "True iff §10 Abs 4 KStG switch-over fires — the foreign corp is in
   a low-tax jurisdiction (effective tax rate < threshold). The
   consumer attests via either:
     (a) `:low-tax-jurisdiction?` boolean on the event, OR
     (b) `:foreign-corp-etr <bigdec>` on the event AND we compare
         against the bitemporal threshold parameter (12.5 % pre-2026
         → 15 % from 2026 per Mindestbesteuerungsgesetz).
   When both are absent, defaults to false (no switch-over)."
  [event ^java.math.BigDecimal threshold]
  (cond
    (true? (:low-tax-jurisdiction? event)) true
    ;; §10 Abs 4 KStG triggers when foreign ETR is "nicht mehr als"
    ;; the threshold (i.e., ≤). Boundary cases (exactly 12.5 % pre-
    ;; 2026 or 15 % from 2026) trigger the switch-over.
    (some? (:foreign-corp-etr event))      (<= (compare (:foreign-corp-etr event) threshold) 0)
    :else                                  false))

(defn- §10-schachtel-qualifying?
  "True iff a `:schachtelbeteiligung` event qualifies under
   §10 Abs 2 KStG: ≥ 10 % ownership-fraction AND ≥ 365-day holding
   period. Mirrors the AT CGT `§10-qualifying?` gate. Consumer attests via:
   - `:ownership-fraction <bd>` on the event
   - `:held-since #inst …` on the event (compared to `as-of` for the
     holding-days math)

   Each check is OPTIONAL — supplied data is verified; missing data
   trusts the consumer's label (v1 substrate leniency). Supplying
   `:ownership-fraction 0.03M` therefore downgrades; omitting both
   metrics keeps the consumer-supplied `:schachtelbeteiligung` label."
  [event ^java.math.BigDecimal qualifying-fraction
   ^long qualifying-days ^java.util.Date as-of]
  (let [own       (:ownership-fraction event)
        held      (:held-since event)
        days      (when (and held as-of)
                    (long (/ (- (.getTime ^java.util.Date as-of)
                                (.getTime ^java.util.Date held))
                             (* 1000 60 60 24))))
        own-ok?   (or (nil? own)  (>= (compare own qualifying-fraction) 0))
        days-ok?  (or (nil? days) (>= days qualifying-days))]
    (and own-ok? days-ok?)))

(defn- §10-default-exempt-component
  "§10 KStG default-exempt branch — domestic / foreign-portfolio /
   Schachtelbeteiligung dividend EXEMPT at the receiving corp. The
   GL booked the dividend at gross as ordinary income; this component
   REMOVES it from the CIT taxable base via `:cit-base-deductions`.

   Per BFG 2024: when §10 exempts the dividend, NO
   DBA-Quellensteuer credit is allowed (cap = 0). Provider records
   the lost foreign WHT in `:line-items` for audit; consumer may
   reclaim at source."
  [{:keys [commodity authority]} event classification]
  (let [gross (bigdec0 (:gross event))
        foreign-wht (bigdec0 (:foreign-tax-withheld event))
        classification-label
        (case classification
          :domestic              "§10 Abs 1 Z 1 KStG — domestic inter-corp dividend (no holding/stake test)"
          :foreign-portfolio     "§10 Abs 1 Z 5-6 KStG — international portfolio dividend (EU/EEA/treaty-state)"
          :schachtelbeteiligung  "§10 Abs 2 + Abs 3 KStG — internationale Schachtelbeteiligung (default tax-neutral)")]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :§10-default-exempt
     :line-items
     (cond-> [{:line :at-§10-gross-dividend
               :label "Dividend gross (booked at GL as ordinary income)"
               :value (money/money gross commodity)}
              {:line :at-§10-classification
               :label classification-label
               :value (money/money gross commodity)}
              {:line :at-§10-cit-base-deduct
               :label "§10 KStG — dividend removed from CIT base (DEFAULT exemption, no Option)"
               :value (money/money gross commodity)}]
       (pos? foreign-wht)
       (conj {:line :at-dba-credit-lost-bfg-2024
              :label "BFG 2024 — DBA-Quellensteuer credit cap = 0 when §10 exempts; foreign WHT must be reclaimed at source"
              :value (money/money foreign-wht commodity)}))
     :jurisdiction-specific-codes
     {:lane :at-§10-exempt-dividend
      :§10-classification classification
      :cit-base-deductions [gross]
      :dba-credit-lost (if (pos? foreign-wht) foreign-wht 0M)}}))

(defn- §10-option-taxable-component
  "§10 KStG with Option zur Steuerwirksamkeit elected — dividend
   enters CIT base at the standard rate. The GL booked the dividend
   at gross; no neutralising deduction is needed (the gross is already
   in CIT taxable income). Provider emits a documentary component +
   surfaces the DBA-Quellensteuer credit (the §10 exemption did NOT
   fire, so the BFG 2024 cap-zero does NOT apply).

   Optional DBA credit (per the consumer-supplied `:foreign-tax-
   withheld` + `:foreign-treaty-rate`) rides via
   `:cit-credits-non-refundable {:at-dba-quellensteuer <amount>}`."
  [{:keys [commodity authority]} event cit-rate]
  (let [gross (bigdec0 (:gross event))
        foreign-wht (bigdec0 (:foreign-tax-withheld event))
        treaty-rate (or (:foreign-treaty-rate event) 0.15M)
        dba-cap (* gross treaty-rate)
        dba-credit (min foreign-wht dba-cap)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :§10-tax-effective-option
     :line-items
     (cond-> [{:line :at-§10-gross-dividend
               :label "Dividend gross (booked at GL as ordinary income)"
               :value (money/money gross commodity)}
              {:line :at-§10-option-elected
               :label (str "§10 Abs 3 KStG — Option zur Steuerwirksamkeit ELECTED — dividend taxable at CIT "
                           (* cit-rate 100M) " %")
               :value (money/money gross commodity)}]
       (pos? foreign-wht)
       (conj {:line :at-dba-credit-allowed
              :label "DBA-Quellensteuer credit allowed (capped at treaty-rate × gross; non-refundable)"
              :value (money/money dba-credit commodity)}))
     :jurisdiction-specific-codes
     (cond-> {:lane :at-§10-option-taxable-dividend
              :§10-classification (:§10-classification event)}
       (pos? dba-credit)
       (assoc :cit-credits-non-refundable {:at-dba-quellensteuer dba-credit}))}))

(defn- §10-switch-over-component
  "§10 Abs 4 KStG switch-over — foreign corp is in a low-tax
   jurisdiction (ETR < threshold). The default exemption SWITCHES OFF;
   dividend becomes fully taxable at CIT (no `:cit-base-deductions`).
   GL booked at gross; no neutralising entry needed. DBA credit fires
   since the dividend IS taxed.

   The 2026 cliff (12.5 % → 15 %) is driven by the bitemporal
   parameter `AT.KStG.§10-Abs-4.low-tax-threshold` via
   `parameter-value-at` at the period's `:as-of`."
  [{:keys [commodity authority]} event cit-rate
   ^java.math.BigDecimal threshold]
  (let [gross (bigdec0 (:gross event))
        foreign-wht (bigdec0 (:foreign-tax-withheld event))
        treaty-rate (or (:foreign-treaty-rate event) 0.15M)
        dba-cap (* gross treaty-rate)
        dba-credit (min foreign-wht dba-cap)
        etr (:foreign-corp-etr event)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :§10-abs-4-switch-over
     :line-items
     (cond-> [{:line :at-§10-gross-dividend
               :label "Dividend gross (booked at GL as ordinary income)"
               :value (money/money gross commodity)}
              {:line :at-§10-abs-4-switchover
               :label (str "§10 Abs 4 KStG — low-tax switch-over fires (threshold "
                           (* threshold 100M)
                           " %"
                           (when etr
                             (str ", attested ETR " (* etr 100M) " %"))
                           ") — exemption switched OFF, dividend fully taxable at CIT "
                           (* cit-rate 100M) " %")
               :value (money/money gross commodity)}]
       (pos? foreign-wht)
       (conj {:line :at-dba-credit-allowed
              :label "DBA-Quellensteuer credit allowed (exemption switched off; credit applies)"
              :value (money/money dba-credit commodity)}))
     :jurisdiction-specific-codes
     (cond-> {:lane :at-§10-abs-4-switchover-taxable
              :§10-classification (:§10-classification event)
              :foreign-corp-etr etr
              :low-tax-threshold threshold}
       (pos? dba-credit)
       (assoc :cit-credits-non-refundable {:at-dba-quellensteuer dba-credit}))}))

(defn- corporate-interest-component
  "Corporate-side interest income — ALWAYS taxable in CIT (no §94 Z 5
   exemption for corps; the §10 KStG exemption is for participation
   INCOME, not interest). Provider emits a documentary component with
   no `:cit-base-additions`/`:deductions` (the GL booked at gross =
   already in CIT taxable income; no neutralising entry needed).

   When foreign-source interest exists, the DBA-Quellensteuer credit
   fires (interest is never §10-exempt, so the BFG 2024 cap-zero never
   applies to interest)."
  [{:keys [commodity authority]} ^java.math.BigDecimal interest-gross
   ^java.math.BigDecimal foreign-wht
   ^java.math.BigDecimal treaty-rate]
  (let [dba-cap (* interest-gross treaty-rate)
        dba-credit (if (pos? foreign-wht)
                     (min foreign-wht dba-cap)
                     0M)]
    {:kind            :investment-income-tax
     :authority       authority
     :base            (money/money interest-gross commodity)
     :schedule        nil
     :gross-liability (money/zero commodity)
     :liability       (money/zero commodity)
     :prepaid         (money/zero commodity)
     :regime          :corporate-interest
     :line-items      [{:line :at-corp-interest-gross
                        :label "Corporate interest income (gross; taxable in CIT as ordinary income)"
                        :value (money/money interest-gross commodity)}
                       {:line :at-corp-interest-dba-credit
                        :label "DBA-Quellensteuer credit on foreign-source interest (non-refundable)"
                        :value (money/money dba-credit commodity)}]
     :jurisdiction-specific-codes
     (cond-> {:lane :at-corp-interest}
       (pos? dba-credit)
       (assoc :cit-credits-non-refundable {:at-dba-quellensteuer dba-credit}))}))

;; ============================================================================
;; INDIVIDUAL provider
;; ============================================================================

(defrecord ATKestInvestmentIncomeProvider
           [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AT KESt investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          bases     (or (:investment-income-bases inputs) {})
          ;; Wertpapier-Vermögen 27.5 % bucket: dividends + bond/fund
          ;; interest (NOT bank-deposit interest). The :wertpapier-27-5
          ;; key collapses dividends + bond interest into one number;
          ;; for granularity the consumer may supply :dividends-27-5 +
          ;; :bond-interest-27-5 separately and the provider sums.
          gross-27-5 (+ (bigdec0 (:dividends-27-5 bases))
                        (bigdec0 (:bond-interest-27-5 bases))
                        (bigdec0 (:wertpapier-27-5 bases)))
          ;; Sparbuch 25 % bucket: bank-deposit interest only (walled
          ;; off from the 27.5 % bucket6).
          gross-25   (+ (bigdec0 (:sparbuch-interest-25 bases))
                        (bigdec0 (:bank-interest-25 bases)))
          ;; Foreign-source dividend gross (subset of 27.5 % bucket)
          ;; drives the DBA credit; consumer supplies aggregate, plus
          ;; foreign-WHT and treaty-rate cap.
          foreign-gross  (bigdec0 (:foreign-dividends-27-5 bases))
          foreign-wht    (bigdec0 (:foreign-tax-withheld bases))
          treaty-rate    (or (:foreign-treaty-rate bases) 0.15M)
          ;; Bank-prepaid KESt — accepts map or scalar
          prepaid-27-5   (prepaid-for-bucket ctx :wertpapier)
          prepaid-25     (prepaid-for-bucket ctx :sparbuch)
          regel?         (boolean (get-in ctx [:tax-unit :regelbesteuerung-elected?]))
          rate-27-5      (param db "AT.EStG.§27a.kest-financial-rate" as-of)
          rate-25        (param db "AT.EStG.§27a.kest-interest-rate"  as-of)
          opts           {:authority authority :commodity commodity}
          components
          (cond
            ;; Regelbesteuerungsoption (§27a Abs 5) — fold everything
            ;; into PIT base; suppress standalone components.
            (and regel? (or (pos? gross-27-5) (pos? gross-25)))
            [(regelbesteuerung-fold-component
              opts gross-27-5 gross-25 (+ prepaid-27-5 prepaid-25))]

            ;; Standard Endbesteuerung path
            :else
            (cond-> []
              (pos? gross-27-5)
              (conj (kest-bucket-component opts gross-27-5 rate-27-5
                                           prepaid-27-5 :wertpapier))
              (pos? gross-25)
              (conj (kest-bucket-component opts gross-25 rate-25
                                           prepaid-25 :sparbuch))))
          ;; DBA-Quellensteuer credit (foreign-source only; applies in
          ;; both Endbesteuerung and Regelbesteuerung branches — the
          ;; credit is against the AT tax in either case).
          components (cond-> components
                       (and (pos? foreign-gross) (pos? foreign-wht))
                       (conj (dba-credit-component
                              opts foreign-gross foreign-wht treaty-rate)))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :at :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; CORPORATE provider
;; ============================================================================

(defrecord ATCorporateInvestmentIncomeProvider
           [id authority commodity statute]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db        (or (:db ctx)
                        (throw (ex-info ":db required in ctx for AT corporate investment-income provider"
                                        {:ctx-keys (keys ctx)})))
          as-of     (as-of-from-ctx ctx)
          bases     (or (:investment-income-bases inputs) {})
          events    (or (:corporate-dividend-events bases) [])
          interest-gross (bigdec0 (:corporate-interest-gross bases))
          interest-foreign-wht (bigdec0 (:corporate-interest-foreign-tax-withheld bases))
          interest-treaty-rate (or (:corporate-interest-treaty-rate bases) 0.15M)
          ;; Foreign-corp guard (per AT CGT,.2): when
          ;; :tax-unit :held-entity-domestic? true, the §10 INVERSION
          ;; for foreign-portfolio / schachtel does NOT fire; events
          ;; classified as such fall through to ordinary CIT income.
          domestic-stake? (boolean (get-in ctx [:tax-unit :held-entity-domestic?]))
          cit-rate       (param db "AT.KStG.cit-rate" as-of)
          threshold      (param db "AT.KStG.§10-Abs-4.low-tax-threshold" as-of)
          ;; §10 Abs 2 KStG qualification thresholds — reused from
          ;; cgt-statute (no IC-specific re-declaration).
          qual-fraction  (or (param db "AT.KStG.§10.qualifying-ownership-fraction" as-of)
                             0.10M)
          qual-days      (long (or (param db "AT.KStG.§10.qualifying-holding-days" as-of)
                                   365))
          opts           {:authority authority :commodity commodity}
          per-event-components
          (->> events
               (mapv (fn [event]
                       (let [classification (:§10-classification event)
                             option?        (§10-option-elected? event)
                             switch-over?   (§10-switch-over? event threshold)
                             ;; §10 Abs 2 qualification only gates the
                             ;; default-exempt INVERSION. Option / switch-
                             ;; over make the dividend taxable regardless
                             ;; of how the consumer labelled the stake.
                             ;; A `:schachtelbeteiligung` event that fails
                             ;; ownership/holding is silently downgraded
                             ;; in the default-exempt branch only.
                             schachtel-qualifies?
                             (or (not= classification :schachtelbeteiligung)
                                 (§10-schachtel-qualifying?
                                  event qual-fraction qual-days as-of))]
                         (cond
                           ;; Sanity: unrecognised classification → no component
                           (not (contains? §10-classifications classification))
                           nil

                           ;; :ordinary — no §10 exemption applies; dividend
                           ;; stays in CIT base. No component (interest pattern).
                           (= classification :ordinary)
                           nil

                           ;; Option elected (regardless of switch-over) →
                           ;; taxable into CIT (consumer chose this)
                           option?
                           (§10-option-taxable-component opts event cit-rate)

                           ;; §10 Abs 4 switch-over fires (foreign-portfolio
                           ;; / schachtel only; domestic is unaffected by
                           ;; switch-over) → fully taxable
                           (and switch-over?
                                (or (= classification :foreign-portfolio)
                                    (= classification :schachtelbeteiligung)))
                           (§10-switch-over-component opts event cit-rate threshold)

                           ;; Foreign-corp guard: when held entity is
                           ;; domestic (`:tax-unit :held-entity-domestic?
                           ;; true`), the §10 default-exempt INVERSION
                           ;; does NOT fire for foreign-portfolio /
                           ;; schachtel classifications. Note 146 §3.2.
                           ;; (Domestic classification remains exempt
                           ;; under §10 Abs 1 Z 1.)
                           (and domestic-stake?
                                (or (= classification :foreign-portfolio)
                                    (= classification :schachtelbeteiligung)))
                           nil

                           ;; §10 Abs 2 qualification gate: when a
                           ;; consumer labels an event :schachtelbeteiligung
                           ;; but supplies ownership/holding metrics that
                           ;; fail the ≥10 % + ≥365-day test, the §10
                           ;; default-exempt INVERSION does NOT fire —
                           ;; the dividend stays in the CIT base. Cross-
                           ;; module consistency with the AT CGT provider
                           ;;.
                           (not schachtel-qualifies?)
                           nil

                           ;; Default-exempt branch (domestic /
                           ;; foreign-portfolio / schachtelbeteiligung)
                           :else
                           (§10-default-exempt-component opts event classification)))))
               (remove nil?)
               vec)
          interest-component (when (pos? interest-gross)
                               (corporate-interest-component
                                opts interest-gross
                                interest-foreign-wht
                                interest-treaty-rate))
          components (cond-> per-event-components
                       interest-component (conj interest-component))]
      (ptp/tax-return-facts
       {:entity               entity
        :period               period
        :jurisdiction         {:country :at :authority authority}
        :functional-commodity commodity
        :components           components}))))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn at-kest-investment-income-provider
  "Build an AT KESt investment-income provider (`:kind :individual`).

   §27/§27a EStG KESt-Endbesteuerung on dividends + bank interest for
   AT-resident individuals. Two rate buckets (27.5 % financial /
   25 % Sparbuch); Endbesteuerungswirkung default; Regelbesteuerungs-
   option folds into PIT base; DBA-Quellensteuer credit on foreign-
   source dividends (capped at treaty-rate × gross; non-refundable).

   Optional:
     :id        — provider id (default :at-investment-income-kest)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity]
    :or   {id :at-investment-income-kest commodity :EUR}}]
  (->ATKestInvestmentIncomeProvider
   id :at-finanzamt commodity
   "EStG §27 + §27a + §95 + §97 (KESt-Endbesteuerung) + §27a Abs 5 (Regelbesteuerungsoption)"))

(defn at-corporate-investment-income-provider
  "Build an AT corporate investment-income provider (`:kind :corporation`).

   §10 KStG INVERSION: default-exempt for domestic / foreign-portfolio
   / Schachtelbeteiligung dividends (`:cit-base-deductions` removes
   from CIT base); opt-in to taxable via `:elective-regime
   :at-§10-tax-effective-option`. §10 Abs 4 switch-over fires when the
   foreign corp's effective tax rate is below the bitemporal threshold
   (12.5 % pre-2026 → 15 % from 2026 per Mindestbesteuerungsgesetz).
   Foreign-corp guard: `:tax-unit
   :held-entity-domestic? true` blocks the §10 INVERSION for foreign-
   portfolio/schachtel classifications. BFG 2024: when §10 exempts,
   DBA-Quellensteuer credit cap = 0.

   Optional:
     :id        — provider id (default :at-investment-income-corporate)
     :commodity — functional commodity (default :EUR)"
  [{:keys [id commodity]
    :or   {id :at-investment-income-corporate commodity :EUR}}]
  (->ATCorporateInvestmentIncomeProvider
   id :at-finanzamt commodity
   "KStG §10 Abs 1 Z 1 (domestic) + §10 Abs 1 Z 5-6 (foreign portfolio) + §10 Abs 2-3 (Schachtelbeteiligung) + §10 Abs 4 (Switch-over)"))


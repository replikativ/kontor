(ns kontor.payroll-jp.wage-types
  "JP-specific `:component-kind` extensions per ADR-084 §10.1. Open-set
   per ADR-071 P2-71-2 + ADR-075's `PayrollFacts` opaque-component
   contract.

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's chart-of-accounts
       lookup key; see `kontor.payroll-jp.posting-builder`),
     - optionally a `:gensen-box` keyword identifying the 源泉徴収票
       (Gensen Choshu Hyo) statement box the component aggregates
       into for the year-end annual report (see
       `kontor.payroll-jp.gensen`),
     - optionally `:requires-age-40?` flag (engine produces these only
       for employees ≥40 years old, e.g. 介護保険料 / Kaigo Hoken),
     - optionally `:posts? false` flag (the component is carry-only
       for Gensen reporting and does NOT generate a posting leg —
       social-insurance subject earnings, taxable-income carryforward,
       year-to-date totals).

   ## JP wage-type vocabulary

   Earnings (基本給 / 残業手当 / 賞与 / 通勤手当 / 住宅手当):
     `:base-wage` (基本給), `:overtime` (残業手当),
     `:bonus` (賞与 — semi-annual; see accrual.clj),
     `:commuting-allowance` (通勤手当),
     `:housing-allowance` (住宅手当),
     `:family-allowance` (家族手当),
     `:position-allowance` (役職手当).

   Statutory social insurance — 4-bucket model (社会保険 4 つ):
     - 健康保険 (Kenko Hoken / health insurance) —
       `:employee-health-insurance` + `:employer-health-insurance`.
     - 厚生年金 (Kosei Nenkin / employees' pension) —
       `:employee-pension` + `:employer-pension`.
     - 雇用保険 (Koyo Hoken / employment insurance) —
       `:employee-employment-insurance` + `:employer-employment-insurance`.
     - 介護保険 (Kaigo Hoken / long-term-care insurance —
       employees ≥40 years old only) —
       `:employee-long-term-care` + `:employer-long-term-care`.

   Tax withholding (源泉徴収):
     - `:income-tax-withheld` (所得税) — withheld monthly, reconciled
       in 年末調整 (Nenmatsu Chosei / year-end adjustment).
     - `:resident-tax-withheld` (住民税) — withheld monthly via
       特別徴収 (Tokubetsu Choshu / special collection), forwarded
       to the employee's municipality.

   Voluntary / other deductions:
     - `:zaikei-savings` (財形貯蓄 — employer-administered savings
       program; non-statutory).
     - `:union-dues` (組合費 / Kumiaihi).
     - `:voluntary-deduction` (catch-all).

   Accruals (out-of-band — see `kontor.payroll-jp.accrual`):
     - 賞与引当金 (Shoyo Hikiatekin / bonus accrual) — JP bonuses
       are large (1-3 months' salary) paid twice yearly; accrue
       monthly toward each semi-annual payout. ADR-084 §3 §6.
     - 4 statutory-insurance employer-side accruals (per-month
       liability recognition aligned with cash-flow timing).

   ## Consumer extension

   Pass an extra map via `:jp/extras-map` to the posting builder +
   Gensen builder to add bespoke component kinds.

   ## Out-of-scope (deferred for v1, ADR-084 §7)

     - 退職給付引当金 (Taishoku Kyufu Hikiatekin / retirement-benefit
       provision) — IFRS-equivalent ASBJ guidance, actuarial.
       Deferred to a future `kontor-pension-actuary-jp` companion.
     - 給与支払報告書 (Kyuyo Shiharai Hokokusho / Year-end Salary
       Payment Report to municipalities) — derives from the same
       per-employee tape as the Gensen but routes to municipalities,
       not NTA. Deferred.
     - 法定調書合計表 (Hotei Chosho Goukei-hyo / Statutory Documents
       Summary) — the NTA cover sheet for Gensen submissions.
       Deferred.

   Reference: ADR-084 §3 (wage-type vocabulary), §6 (accrual model)."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical JP wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`. See ADR-084 §10.1 for the full
   rationale per row.

   Box codes for the 源泉徴収票 (Gensen Choshu Hyo) follow the NTA's
   公開 form layout (Reiwa-era; same identifiers used since 2016):
     :gensen/payment-amount         支払金額
     :gensen/withholding-amount     源泉徴収税額
     :gensen/social-insurance-paid  社会保険料等の金額
     :gensen/employment-income-deduction 給与所得控除後の金額
     :gensen/taxable-income         所得控除の額の合計額
   plus opaque carry-only slots for the engine's year-end calc inputs."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense (給料手当)
   ;; ──────────────────────────────────────────────────────────────
   :base-wage            {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "基本給"}
   :overtime             {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "残業手当"}
   :commuting-allowance  {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "通勤手当"
                          ;; Up to JPY 150,000/month is tax-free under
                          ;; 所得税法施行令 §20-2; over that amount is
                          ;; taxable. Engine handles the split.
                          :gensen-non-taxable-cap-monthly 150000}
   :housing-allowance    {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "住宅手当"}
   :family-allowance     {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "家族手当"}
   :position-allowance   {:account-tag :jp-payroll-wages
                          :gensen-box :gensen/payment-amount
                          :kanji "役職手当"}
   ;; 賞与 (Shoyo / bonus) — separate account on the JP starter chart
   ;; per ADR-084 §3 (J-GAAP convention; not collapsed with monthly
   ;; salary). Year-end calc still rolls it into :gensen/payment-amount.
   :bonus                {:account-tag :jp-payroll-bonus
                          :gensen-box :gensen/payment-amount
                          :kanji "賞与"}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit 預り金 (Azukari-kin / holding)
   ;; ──────────────────────────────────────────────────────────────
   :employee-health-insurance
   {:account-tag :jp-payroll-health-insurance
    :gensen-box :gensen/social-insurance-paid
    :kanji "健康保険料"}

   :employee-pension
   {:account-tag :jp-payroll-pension
    :gensen-box :gensen/social-insurance-paid
    :kanji "厚生年金保険料"}

   :employee-employment-insurance
   {:account-tag :jp-payroll-employment-insurance
    :gensen-box :gensen/social-insurance-paid
    :kanji "雇用保険料"}

   :employee-long-term-care
   {:account-tag :jp-payroll-long-term-care
    :gensen-box :gensen/social-insurance-paid
    :requires-age-40? true
    :kanji "介護保険料"}

   :income-tax-withheld
   {:account-tag :jp-payroll-income-tax
    :gensen-box :gensen/withholding-amount
    :kanji "所得税"}

   :resident-tax-withheld
   ;; 住民税 — withheld monthly via 特別徴収 (special collection) and
   ;; forwarded to the employee's municipality. NOT on the Gensen
   ;; (resident tax is municipality-side; the per-employee 給与支払
   ;; 報告書 reports it instead). Gensen-box is nil.
   {:account-tag :jp-payroll-resident-tax
    :kanji "住民税"}

   :zaikei-savings
   ;; 財形貯蓄 — voluntary employer-administered savings.
   {:account-tag :jp-payroll-zaikei
    :kanji "財形貯蓄"}

   :union-dues
   {:account-tag :jp-payroll-union-dues
    :kanji "組合費"}

   :voluntary-deduction
   {:account-tag :jp-payroll-other-deduction
    :kanji "その他控除"}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — debit 法定福利費 (Hotei Fukuri-hi),
   ;; credit matching 預り金 (Azukari-kin) liability.
   ;; ──────────────────────────────────────────────────────────────
   :employer-health-insurance
   {:account-tag :jp-payroll-er-statutory-benefits
    :employer-side? true
    :payable-tag :jp-payroll-health-insurance
    :kanji "健康保険料 (事業主負担)"}

   :employer-pension
   {:account-tag :jp-payroll-er-statutory-benefits
    :employer-side? true
    :payable-tag :jp-payroll-pension
    :kanji "厚生年金保険料 (事業主負担)"}

   :employer-employment-insurance
   {:account-tag :jp-payroll-er-statutory-benefits
    :employer-side? true
    :payable-tag :jp-payroll-employment-insurance
    :kanji "雇用保険料 (事業主負担)"}

   :employer-long-term-care
   {:account-tag :jp-payroll-er-statutory-benefits
    :employer-side? true
    :requires-age-40? true
    :payable-tag :jp-payroll-long-term-care
    :kanji "介護保険料 (事業主負担)"}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — for Gensen + year-end calc only.
   ;; ──────────────────────────────────────────────────────────────
   ;; The engine emits these so the year-end Gensen builder can
   ;; reach them without a separate carry-only protocol. The
   ;; substrate's check-facts will not include them in the gross/net
   ;; invariant.
   :gensen-employment-income-deduction
   ;; 給与所得控除 — the standard deduction applied to employment
   ;; income before tax bracket. Engine-computed (per 所得税法 §28).
   {:gensen-box :gensen/employment-income-deduction :posts? false}

   :gensen-taxable-income
   ;; 課税対象額 — gross less SI less standard-deduction.
   {:gensen-box :gensen/taxable-income :posts? false}

   :gensen-spouse-deduction
   ;; 配偶者控除 — engine-computed from spouse income declarations.
   {:gensen-box :gensen/spouse-deduction :posts? false}

   :gensen-dependent-deduction
   {:gensen-box :gensen/dependent-deduction :posts? false}

   :gensen-social-insurance-paid-ytd
   ;; Year-to-date SI carried for Gensen line; the per-period SI
   ;; deduction components already aggregate, but YTD lets the
   ;; engine reconcile against partial-year hires / transfers.
   {:gensen-box :gensen/social-insurance-paid-ytd :posts? false}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog. Used by the
   posting builder + Gensen builder so consumers can add bespoke
   kinds without code change."
  ([] standard-component-kinds)
  ([extras-map]
   (merge standard-component-kinds (or extras-map {}))))

(defn posts?
  "True iff the kind generates posting legs (i.e. is not carry-only)."
  ([kind] (posts? kind nil))
  ([kind extras-map]
   (let [m (get (merged-catalog extras-map) kind)]
     (not (false? (:posts? m))))))

(defn employer-side?
  "True iff the kind represents an employer-side contribution
   (matches the `:employer-side?` flag in a PayrollFact component)."
  ([kind] (employer-side? kind nil))
  ([kind extras-map]
   (boolean (:employer-side? (get (merged-catalog extras-map) kind)))))

(defn account-tag
  "Return the :account-tag keyword for a kind, or nil. Consumer's
   :accounts map keys on this tag."
  ([kind] (account-tag kind nil))
  ([kind extras-map]
   (:account-tag (get (merged-catalog extras-map) kind))))

(defn payable-tag
  "Return the :payable-tag keyword for an employer-side kind. For
   :employer-pension this is :jp-payroll-pension (the same payable
   the employee deduction lands on — both halves feed the same
   liability bucket forwarded to 日本年金機構 / Nenkin Kiko)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn gensen-box
  "Return the Gensen-box keyword the kind aggregates into for the
   year-end 源泉徴収票 (Gensen Choshu Hyo). nil = does not aggregate
   to a Gensen box (resident tax, employer-side kinds, opaque carry-
   only metadata that doesn't land on the statement)."
  ([kind] (gensen-box kind nil))
  ([kind extras-map]
   (:gensen-box (get (merged-catalog extras-map) kind))))

(defn requires-age-40?
  "True iff the kind is only meaningful for employees ≥40 years old.
   介護保険料 (Kaigo Hoken) is the canonical case."
  ([kind] (requires-age-40? kind nil))
  ([kind extras-map]
   (boolean (:requires-age-40? (get (merged-catalog extras-map) kind)))))

(defn kanji
  "Return the Kanji label for the kind, for human-readable narration
   on postings + audit-docs."
  ([kind] (kanji kind nil))
  ([kind extras-map]
   (:kanji (get (merged-catalog extras-map) kind))))

(defn known-kinds
  "Set of all known component kinds (standard + extras)."
  ([] (set (keys standard-component-kinds)))
  ([extras-map] (set (keys (merged-catalog extras-map)))))

(defn unknown-kinds
  "Given a vector of components (from PayrollFacts), return the set of
   kinds NOT present in the catalog. Used by the posting builder to
   fail loud rather than silently drop legs."
  ([components] (unknown-kinds components nil))
  ([components extras-map]
   (set/difference (set (map :kind components))
                   (known-kinds extras-map))))

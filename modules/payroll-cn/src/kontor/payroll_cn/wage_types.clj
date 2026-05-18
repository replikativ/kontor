(ns kontor.payroll-cn.wage-types
  "CN payroll wage-type catalog — open-set `:component-kind` mappings to
   ASBE :account-tag keys + 应付职工薪酬 (2211) sub-account routing
   (note 87 §3 + §4; ADR-085).

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's chart-of-accounts
       lookup key; see `kontor.payroll-cn.posting-builder`);
     - optionally an `:asbe-sub-account` keyword identifying the
       2211 应付职工薪酬 sub-bucket the payable lands in (per CAS 9 /
       财会〔2014〕8号);
     - optionally an `:employer-side?` flag (true for AG-side SI / HF
       components);
     - optionally a `:payable-tag` keyword (employer-side kinds emit
       BOTH an expense leg AND a payable leg);
     - optionally a `:posts? false` flag (the component is carry-only
       for audit-doc purposes and does NOT generate a posting leg —
       SI base, HF base, cumulative YTD figures, etc.).

   Consumer extension: pass an extra map via `:cn/extras-map` to the
   posting builder + emit builder to add bespoke component kinds.

   Reference: note 87 §3 (wage-type vocabulary), §4 (CoA wage-account
   map), §2.4 (年终奖 special tax treatment)."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical CN wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`. See note 87 §3 for the per-row rationale.

   Sign convention (employee-perspective per ADR-075):
     EE+ (earnings)        — positive employee-side amount
     EE− (withholdings)    — negative employee-side amount
     ER+ (employer cost)   — positive amount, :employer-side? true
     carry-only            — :posts? false, no GL leg

   Account-tag vocabulary:
     :cn-payroll-wages-expense    — DR wage expense (admin / sales / mfg)
     :cn-payroll-net-wages        — CR net wages payable (2211.01)
     :cn-payroll-iit              — CR IIT withholding payable (2221.xx)
     :cn-payroll-ee-si            — CR employee SI payable (2211.03)
     :cn-payroll-ee-hf            — CR employee housing fund payable (2211.04)
     :cn-payroll-er-si-expense    — DR employer SI expense
     :cn-payroll-er-hf-expense    — DR employer HF expense
     :cn-payroll-er-si-payable    — CR employer SI payable (2211.03)
     :cn-payroll-er-hf-payable    — CR employer HF payable (2211.04)
     :cn-payroll-bonus-payable    — CR annual-bonus accrual payable
                                    (sub-account of 2211.01 wages)"
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense, credit net wages payable
   ;; ──────────────────────────────────────────────────────────────
   :base-wage              {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "基本工资"}
   :performance-bonus      {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "绩效工资"}
   :overtime               {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "加班费"}
   :allowance              {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "补贴"}
   :taxable-benefit        {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "应税补贴"}

   ;; ──────────────────────────────────────────────────────────────
   ;; ANNUAL BONUS — distinct kind for 财税〔2018〕164号 special tax
   ;; treatment (note 87 §2.4). The IIT engine reads
   ;; :jurisdiction-specific-codes {:cn/annual-bonus-method :single
   ;; or :combined} to apply the right method.
   ;; ──────────────────────────────────────────────────────────────
   :annual-bonus           {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-net-wages
                            :asbe-sub-account :wages
                            :chinese-name "年终奖"
                            :special-tax-treatment? true}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit payable accounts (net effect on
   ;; gross→net is negative employee-side amount).
   ;; ──────────────────────────────────────────────────────────────
   :iit-withheld           {:account-tag :cn-payroll-iit
                            :chinese-name "个人所得税"}
   :ee-pension             {:account-tag :cn-payroll-ee-si
                            :asbe-sub-account :si
                            :chinese-name "养老保险-个人"
                            :si-component :pension}
   :ee-medical             {:account-tag :cn-payroll-ee-si
                            :asbe-sub-account :si
                            :chinese-name "医疗保险-个人"
                            :si-component :medical}
   :ee-unemployment        {:account-tag :cn-payroll-ee-si
                            :asbe-sub-account :si
                            :chinese-name "失业保险-个人"
                            :si-component :unemployment}
   :ee-housing-fund        {:account-tag :cn-payroll-ee-hf
                            :asbe-sub-account :hf
                            :chinese-name "住房公积金-个人"}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — debit employer SI/HF expense, credit
   ;; matching payable bucket. Per CAS 9 these all roll into 2211.03
   ;; (社保) + 2211.04 (公积金) on the credit side.
   ;; ──────────────────────────────────────────────────────────────
   :er-pension             {:account-tag :cn-payroll-er-si-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-si-payable
                            :asbe-sub-account :si
                            :chinese-name "养老保险-单位"
                            :si-component :pension}
   :er-medical             {:account-tag :cn-payroll-er-si-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-si-payable
                            :asbe-sub-account :si
                            :chinese-name "医疗保险-单位"
                            :si-component :medical}
   :er-unemployment        {:account-tag :cn-payroll-er-si-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-si-payable
                            :asbe-sub-account :si
                            :chinese-name "失业保险-单位"
                            :si-component :unemployment}
   :er-work-injury         {:account-tag :cn-payroll-er-si-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-si-payable
                            :asbe-sub-account :si
                            :chinese-name "工伤保险-单位"
                            :si-component :work-injury
                            :employer-only? true}
   :er-maternity           {:account-tag :cn-payroll-er-si-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-si-payable
                            :asbe-sub-account :si
                            :chinese-name "生育保险-单位"
                            :si-component :maternity
                            :employer-only? true
                            ;; Note: merged with medical in most cities
                            ;; since 国发〔2019〕10号 — engines in those
                            ;; cities emit zero for :er-maternity.
                            }
   :er-housing-fund        {:account-tag :cn-payroll-er-hf-expense
                            :employer-side? true
                            :payable-tag :cn-payroll-er-hf-payable
                            :asbe-sub-account :hf
                            :chinese-name "住房公积金-单位"}

   ;; ──────────────────────────────────────────────────────────────
   ;; ACCRUALS — 年终奖累计 (annual bonus accrual). Monthly 1/12 toward
   ;; expected year-end payout. DR wage expense / CR liability bucket.
   ;; ──────────────────────────────────────────────────────────────
   :annual-bonus-accrual   {:account-tag :cn-payroll-wages-expense
                            :payable-tag :cn-payroll-bonus-payable
                            :asbe-sub-account :wages
                            :employer-side? true
                            :chinese-name "年终奖累计"}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — for IIT audit-doc reporting only.
   ;; Note 87 §3 — the engine emits these as informational rows.
   ;; ──────────────────────────────────────────────────────────────
   :si-base                {:chinese-name "社保基数"
                            :posts? false}
   :hf-base                {:chinese-name "公积金基数"
                            :posts? false}
   :cumulative-taxable-ytd {:chinese-name "累计应纳税所得额"
                            :posts? false}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog. Used by the
   posting builder + emit provider so consumers can add bespoke kinds
   without code change."
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
   :accounts map keys on this tag. Per note 87 §4."
  ([kind] (account-tag kind nil))
  ([kind extras-map]
   (:account-tag (get (merged-catalog extras-map) kind))))

(defn payable-tag
  "Return the :payable-tag keyword. For employer-side kinds this is the
   matching CR payable bucket; for earnings kinds this is the
   net-wages-payable bucket. nil for withholding-side kinds (the
   :account-tag IS the payable tag in that case)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn asbe-sub-account
  "Return the 2211 应付职工薪酬 sub-account keyword (:wages | :si |
   :hf | :welfare). Used by the emit provider to group facts for the
   IIT audit-doc payload."
  ([kind] (asbe-sub-account kind nil))
  ([kind extras-map]
   (:asbe-sub-account (get (merged-catalog extras-map) kind))))

(defn chinese-name
  "Return the canonical Chinese label for a kind. Used by the emit
   provider for the zh-cn audit-doc payload."
  ([kind] (chinese-name kind nil))
  ([kind extras-map]
   (:chinese-name (get (merged-catalog extras-map) kind))))

(defn special-tax-treatment?
  "True iff the kind is the 年终奖 special-tax-treatment component."
  ([kind] (special-tax-treatment? kind nil))
  ([kind extras-map]
   (boolean (:special-tax-treatment? (get (merged-catalog extras-map) kind)))))

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

(defn assert-known!
  "Throw ex-info on any unknown kinds in `components`; return the input
   on success. Matches CA's `validate-catalog` posture (P2-86-5 — the
   across-adapter consistency convention)."
  ([components] (assert-known! components nil))
  ([components extras-map]
   (let [unknown (unknown-kinds components extras-map)]
     (when (seq unknown)
       (throw (ex-info "kontor.payroll-cn: unknown :component-kind values"
                       {:type :cn-payroll/unknown-kinds
                        :unknown unknown
                        :hint "Extend the catalog via :cn/extras-map or correct the engine's :pay-element-codes mapping."}))))
   components))

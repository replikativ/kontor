(ns kontor.payroll-in.wage-types
  "IN-specific `:component-kind` extensions for kontor-payroll-in
   (Stage R C9, ADR-083). Mirrors the CA / DE / US payroll-extension
   wage-type catalog shape.

   Open-set per ADR-071 + ADR-075's PayrollFacts contract.
   Consumer-extensible via an `:extras-map` that the posting builder
   + emit providers pass through.

   ## Per-kind metadata

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's CoA lookup key —
       see `kontor.payroll-in.posting-builder`),
     - optionally a `:payable-tag` for employer-side components
       (the matching liability account),
     - optionally `:posts? false` to mark a carry-only kind that
       contributes to TDS / PF / ESI reports but does NOT generate
       a posting leg (e.g. `:pf-wages` — the PF-applicable wages
       basis used for PF-employer 12% computation surfaces as a
       carry-only on the fact, not a posting),
     - optionally `:form-24q-section` / `:ecr-column` / `:esi-column`
       routing hints used by the TDS / PF / ESI builders.

   ## IN wage-type vocabulary

   - **Earnings**: `:basic-salary`, `:dearness-allowance` (DA),
     `:house-rent-allowance` (HRA), `:leave-travel-allowance` (LTA),
     `:medical-allowance`, `:special-allowance`, `:bonus`,
     `:overtime`, `:commission`, `:retroactive-pay`,
     `:gratuity-paid` (paid out under Payment of Gratuity Act 1972;
     accrual side is `:gratuity-accrual` / consumer-supplied),
     `:leave-encashment`.
   - **Taxable benefits / perquisites** (Section 17(2)): a single
     catch-all `:perquisite` open-set kind; consumers extend
     (`:perquisite-car`, `:perquisite-rent-free-accommodation`, …)
     via `:extras-map`.
   - **Employee deductions** (statutory): `:tds` (Section 192 TDS),
     `:pf-employee` (12% of basic+DA up to wage ceiling),
     `:esi-employee` (0.75% of gross when applicable),
     `:professional-tax` (per-state — Maharashtra, Karnataka, WB,
     TN, Gujarat, AP, Telangana, Kerala, MP, Odisha, Tripura,
     Assam, Meghalaya, Sikkim, Mizoram, Manipur, Nagaland; **NOT**
     levied in UP/Delhi/Haryana/Punjab/Rajasthan).
   - **Employee deductions** (voluntary): `:voluntary-deduction`,
     `:loan-recovery`, `:garnishment`.
   - **Employer accruals**: `:pf-employer` (12% — split EPS 8.33% +
     EPF 3.67% per EPF Act 1952), `:esi-employer` (3.25%),
     `:employer-gratuity-accrual` (Ind AS 19; consumer supplies the
     actuarial input —.3 deferral), `:bonus-accrual`
     (Payment of Bonus Act 1965 — 8.33%-20% of wages on basic ≤
     ₹21K minimum, accrued per period).
   - **Carry-only** (NOT posted): `:pf-wages` (the PF-applicable
     wages basis — capped at the EPF wage ceiling, currently
     ₹15,000/month), `:esi-wages` (the ESI-applicable wages basis —
     capped at the ESI wage ceiling, currently ₹21,000/month),
     `:section-80c-deduction`, `:section-80d-deduction`,
     `:section-80g-deduction`, `:hra-exemption-claimed`,
     `:taxable-income-ytd`.

   ## Why not bundle rate tables

   Per ADR-005 / ADR-071 / ADR-075 / ADR-083 the kernel + companion
   ships the SHAPE; per-jurisdiction rate data (TDS slabs by FY,
   PF wage ceiling, ESI threshold, per-state PT rate tables) lives
   outside kontor. Customers update these annually with the Finance
   Act / EPFO notifications; bundling them locks customers to a
   point-in-time kontor release.

   Reference: doc/research/79-hr-payroll-stage-r-plan.md §5.3, the
   Income-tax Act 1961 (Section 192), EPF Act 1952 + EPFO 2014
   Notifications, ESI Act 1948."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical IN payroll wage-type catalog. Keys are component kinds
   carried in `PayrollFacts/components`. Consumer extends via an
   extras-map.

   `:account-tag` is the lookup key into the `:accounts` map at
   posting-builder time. The starter chart (resources/coa_starter.edn)
   maps each tag to a Schedule III-aligned account."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — Schedule III 'Employee Benefit Expense' (6900-series
   ;; convention in our starter; consumer remaps).
   ;; ──────────────────────────────────────────────────────────────
   :basic-salary           {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? true
                            :esi-applicable? true}
   :dearness-allowance     {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? true
                            :esi-applicable? true}
   :house-rent-allowance   {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            ;; HRA is excluded from PF wages but
                            ;; INCLUDED in ESI wages.
                            :pf-applicable? false
                            :esi-applicable? true}
   :leave-travel-allowance {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? true}
   :medical-allowance      {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? true}
   :special-allowance      {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            ;; Special-allowance treatment for PF is
                            ;; jurisprudence-driven (Surya Roshni / RPFC
                            ;; SC 2019). Default to PF-applicable so the
                            ;; conservative position lands; consumer
                            ;; overrides via extras-map if their counsel
                            ;; opines otherwise.
                            :pf-applicable? true
                            :esi-applicable? true}
   :bonus                  {:account-tag :in-payroll-bonus
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? true}
   :overtime               {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? true}
   :commission             {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? true}
   :retroactive-pay        {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? true
                            :esi-applicable? true}
   :gratuity-paid          {:account-tag :in-payroll-gratuity-paid
                            :form-24q-section :sec-192
                            ;; Gratuity paid up to ₹20 lakh is exempt
                            ;; under Section 10(10); the engine handles
                            ;; the cap on the TDS side.
                            :pf-applicable? false
                            :esi-applicable? false}
   :leave-encashment       {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? false}

   ;; ──────────────────────────────────────────────────────────────
   ;; TAXABLE PERQUISITES (Sec 17(2)). Open-set — consumer extends.
   ;; ──────────────────────────────────────────────────────────────
   :perquisite             {:account-tag :in-payroll-salaries-wages
                            :form-24q-section :sec-192
                            :pf-applicable? false
                            :esi-applicable? false}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — statutory.
   ;; ──────────────────────────────────────────────────────────────
   :tds                    {:account-tag :in-payroll-tds-payable
                            :form-24q-section :sec-192}
   :pf-employee            {:account-tag :in-payroll-pf-payable}
   :esi-employee           {:account-tag :in-payroll-esi-payable}
   :professional-tax       {:account-tag :in-payroll-pt-payable}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — voluntary.
   ;; ──────────────────────────────────────────────────────────────
   :voluntary-deduction    {:account-tag :in-payroll-other-deduction}
   :loan-recovery          {:account-tag :in-payroll-loan-recovery}
   :garnishment            {:account-tag :in-payroll-garnishment}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — Dr expense, Cr payable.
   ;; ──────────────────────────────────────────────────────────────
   ;; PF-employer 12% is split into:
   ;;   EPS (Employees' Pension Scheme) — 8.33% capped at ₹15,000 basis
   ;;   EPF (Employees' Provident Fund) — balance (3.67% on full basis)
   ;; Engines either emit the split (`:pf-employer-eps` + `:pf-employer-epf`)
   ;; or one combined `:pf-employer` row. We support both; the split
   ;; rolls into the same payable tag.
   :pf-employer            {:account-tag :in-payroll-er-pf
                            :employer-side? true
                            :payable-tag :in-payroll-pf-payable}
   :pf-employer-eps        {:account-tag :in-payroll-er-pf
                            :employer-side? true
                            :payable-tag :in-payroll-pf-payable}
   :pf-employer-epf        {:account-tag :in-payroll-er-pf
                            :employer-side? true
                            :payable-tag :in-payroll-pf-payable}
   ;; EDLI (Employees' Deposit-Linked Insurance) — 0.5% employer-only
   ;; on EPF wages, capped at the EPF ceiling. Surfaces in ECR.
   :pf-employer-edli       {:account-tag :in-payroll-er-pf
                            :employer-side? true
                            :payable-tag :in-payroll-pf-payable}
   :esi-employer           {:account-tag :in-payroll-er-esi
                            :employer-side? true
                            :payable-tag :in-payroll-esi-payable}

   ;; ──────────────────────────────────────────────────────────────
   ;; ACCRUALS (consumer-driven; in-band when engine emits, out-of-
   ;; band when consumer computes — same divergence as.3
   ;; documented for DE / US / CA).
   ;; ──────────────────────────────────────────────────────────────
   :bonus-accrual          {:account-tag :in-payroll-bonus-accrual
                            :employer-side? true
                            :payable-tag :in-payroll-bonus-payable}
   :leave-encashment-accrual {:account-tag :in-payroll-leave-accrual
                              :employer-side? true
                              :payable-tag :in-payroll-leave-liability}
   :employer-gratuity-accrual {:account-tag :in-payroll-gratuity-accrual
                               :employer-side? true
                               :payable-tag :in-payroll-gratuity-liability}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY — NOT posted, surface in jurisdiction-specific-codes
   ;; for TDS / PF / ESI builders to read.
   ;; ──────────────────────────────────────────────────────────────
   :pf-wages                  {:posts? false}
   :esi-wages                 {:posts? false}
   :section-80c-deduction     {:posts? false}
   :section-80d-deduction     {:posts? false}
   :section-80g-deduction     {:posts? false}
   :hra-exemption-claimed     {:posts? false}
   :taxable-income-ytd        {:posts? false}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog."
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
  "True iff the kind represents an employer-side contribution."
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
  "Return the :payable-tag keyword for an employer-side kind (both
   employer contribution + employee deduction roll into the same
   liability bucket3 C9 plan)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn pf-applicable?
  "True iff the kind is part of the EPF / EPS wages base. Drives the
   PF-employer 12% calculation."
  ([kind] (pf-applicable? kind nil))
  ([kind extras-map]
   (boolean (:pf-applicable? (get (merged-catalog extras-map) kind)))))

(defn esi-applicable?
  "True iff the kind is part of the ESI wages base. Drives the ESI
   employee (0.75%) + employer (3.25%) calculation."
  ([kind] (esi-applicable? kind nil))
  ([kind extras-map]
   (boolean (:esi-applicable? (get (merged-catalog extras-map) kind)))))

(defn form-24q-section
  "Return the Form 24Q section keyword the kind reports under, or nil.
   Currently only `:sec-192` (TDS on salary)."
  ([kind] (form-24q-section kind nil))
  ([kind extras-map]
   (:form-24q-section (get (merged-catalog extras-map) kind))))

(defn known-kinds
  ([] (set (keys standard-component-kinds)))
  ([extras-map] (set (keys (merged-catalog extras-map)))))

(defn unknown-kinds
  "Given a vector of components, return the set of kinds NOT in the
   catalog. The posting builder fails loud rather than dropping silently."
  ([components] (unknown-kinds components nil))
  ([components extras-map]
   (set/difference (set (map :kind components))
                   (known-kinds extras-map))))

;; ============================================================================
;; PT (Professional Tax) jurisdiction table — which states levy PT.
;; ============================================================================

(def pt-states
  "States/UTs that levy Professional Tax under their own State Tax on
   Professions, Trades, Callings and Employments Act. Encoded as
   ISO-3166-2:IN sub-jurisdiction codes (matching kernel attr
   `:kontor.employment/province-of-employment`).

   Per.3 C9 plan: 17 states + 1 UT levy PT; the other
   17 sub-jurisdictions (UP, Delhi, Haryana, Punjab, Rajasthan,
   Uttarakhand, Himachal Pradesh, J&K, Ladakh, plus the union
   territories without legislatures: Chandigarh, A&N Islands,
   Lakshadweep, Dadra & Nagar Haveli + Daman & Diu, Goa) do NOT.

   Rates are NOT bundled (per ADR-083) — change annually via state
   finance bills and customers supply current rates via their
   PT-rate provider configuration."
  #{"IN-MH"  ; Maharashtra
    "IN-KA"  ; Karnataka
    "IN-WB"  ; West Bengal
    "IN-TN"  ; Tamil Nadu
    "IN-GJ"  ; Gujarat
    "IN-AP"  ; Andhra Pradesh
    "IN-TG"  ; Telangana
    "IN-KL"  ; Kerala
    "IN-MP"  ; Madhya Pradesh
    "IN-OR"  ; Odisha (sometimes also IN-OD; CBIC uses IN-OR)
    "IN-TR"  ; Tripura
    "IN-AS"  ; Assam
    "IN-ML"  ; Meghalaya
    "IN-SK"  ; Sikkim
    "IN-MZ"  ; Mizoram
    "IN-MN"  ; Manipur
    "IN-NL"  ; Nagaland
    "IN-PY"  ; Puducherry (UT with legislature)
    })

(defn pt-state?
  "True iff the state/UT code levies Professional Tax. Drives PT
   posting routing — when the employment's province-of-employment
   is in this set, the engine's `:professional-tax` component lands
   on the PT-payable account; otherwise the engine should NOT emit
   the component (consumer's payroll engine is responsible — kontor
   does NOT decide, only routes)."
  [iso-3166-2-code]
  (boolean (pt-states iso-3166-2-code)))

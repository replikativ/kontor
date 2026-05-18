(ns kontor.payroll-ca.wage-types
  "CA-specific `:component-kind` extensions per note 84 §10.2. Open-set
   per ADR-071 P2-71-2 + ADR-075's `PayrollFacts` opaque-component
   contract.

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's chart-of-accounts
       lookup key; see `kontor.payroll-ca.posting-builder`),
     - optionally a `:t4-box` keyword identifying the T4 slip box the
       component aggregates into for the year-end T4 (see
       `kontor.payroll-ca.t4-builder`),
     - optionally `:requires-qc?` flag (engine produces these only
       for QC employees),
     - optionally `:posts? false` flag (the component is carry-only
       for T4 reporting and does NOT generate a posting leg —
       insurable earnings, pensionable earnings, pension adjustments,
       dental-coverage code, etc.).

   Consumer extension: pass an extra map via `:ca/extras-map` to the
   posting builder + t4 builder to add bespoke component kinds.

   Reference: note 84 §10.2, §5.2 (T4 box mapping), §7.2 (CoA tags)."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical CA wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`. See note 84 §10.2 for the full
   rationale per row."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense, credit net wages payable
   ;; ──────────────────────────────────────────────────────────────
   :base-wage              {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :overtime               {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :bonus                  {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :commission             {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :vacation-pay-paid-out  {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :statutory-holiday-pay  {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :retroactive-pay        {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   ;; Severance / retiring allowance — note 84 §10.4 #6. C4 routes
   ;; these to wages-expense; T4 box-66/67 reporting is consumer-
   ;; mapped via the engine's per-component breakout.
   :severance              {:account-tag :ca-payroll-wages
                            :t4-box :box-14}
   :retiring-allowance     {:account-tag :ca-payroll-wages
                            :t4-box :box-14}

   ;; ──────────────────────────────────────────────────────────────
   ;; TAXABLE BENEFITS — debit gross wages, included in box-14, also
   ;; surface in T4 box-40 ("Other Information" aggregator).
   ;; ──────────────────────────────────────────────────────────────
   :taxable-benefit-auto              {:account-tag :ca-payroll-wages
                                       :t4-box :box-14
                                       :t4-box-40-include? true}
   :taxable-benefit-group-term-life   {:account-tag :ca-payroll-wages
                                       :t4-box :box-14
                                       :t4-box-40-include? true}
   :taxable-benefit-parking           {:account-tag :ca-payroll-wages
                                       :t4-box :box-14
                                       :t4-box-40-include? true}
   :taxable-benefit-other             {:account-tag :ca-payroll-wages
                                       :t4-box :box-14
                                       :t4-box-40-include? true}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit CRA payable accounts.
   ;; ──────────────────────────────────────────────────────────────
   :income-tax-withheld    {:account-tag :ca-payroll-itx
                            :t4-box :box-22}
   :employee-cpp           {:account-tag :ca-payroll-cpp
                            :t4-box :box-16}
   :employee-cpp2          {:account-tag :ca-payroll-cpp
                            :t4-box :box-16a}
   :employee-ei            {:account-tag :ca-payroll-ei
                            :t4-box :box-18}
   :employee-rpp-contribution {:account-tag :ca-payroll-rpp
                               :t4-box :box-20}
   :union-dues             {:account-tag :ca-payroll-union
                            :t4-box :box-44}
   :charitable-donation-payroll {:account-tag :ca-payroll-charity
                                 :t4-box :box-46}
   :garnishment            {:account-tag :ca-payroll-garnishment}
   :voluntary-deduction    {:account-tag :ca-payroll-other-deduction}

   ;; ──────────────────────────────────────────────────────────────
   ;; QC EMPLOYEE DEDUCTIONS — C4 passthrough for T4 boxes 17/17A/55;
   ;; full RL-1 emission deferred to C4.1. The :requires-qc? flag is
   ;; informational; nothing in C4 enforces it (engines that emit
   ;; QPP/QPIP for non-QC employments are misconfigured upstream).
   ;; ──────────────────────────────────────────────────────────────
   :employee-qpp           {:account-tag :ca-payroll-qpp
                            :t4-box :box-17
                            :requires-qc? true}
   :employee-qpp2          {:account-tag :ca-payroll-qpp
                            :t4-box :box-17a
                            :requires-qc? true}
   :employee-qpip          {:account-tag :ca-payroll-qpip
                            :t4-box :box-55
                            :requires-qc? true}
   :employee-qc-itx        {:account-tag :ca-payroll-qc-itx
                            :requires-qc? true}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — debit employer expense, credit payable.
   ;; ──────────────────────────────────────────────────────────────
   :employer-cpp           {:account-tag :ca-payroll-er-cpp
                            :employer-side? true
                            :payable-tag :ca-payroll-cpp}
   :employer-cpp2          {:account-tag :ca-payroll-er-cpp
                            :employer-side? true
                            :payable-tag :ca-payroll-cpp}
   :employer-ei            {:account-tag :ca-payroll-er-ei
                            :employer-side? true
                            :payable-tag :ca-payroll-ei}
   :employer-qpp           {:account-tag :ca-payroll-er-cpp
                            :employer-side? true
                            :requires-qc? true
                            :payable-tag :ca-payroll-qpp}
   :employer-qpip          {:account-tag :ca-payroll-er-ei
                            :employer-side? true
                            :requires-qc? true
                            :payable-tag :ca-payroll-qpip}
   :employer-rpp-match     {:account-tag :ca-payroll-er-rpp
                            :employer-side? true
                            :payable-tag :ca-payroll-rpp}
   :employer-eht           {:account-tag :ca-payroll-er-eht
                            :employer-side? true
                            :payable-tag :ca-payroll-eht}
   :employer-wsib          {:account-tag :ca-payroll-er-wsib
                            :employer-side? true
                            :payable-tag :ca-payroll-wsib}

   ;; ──────────────────────────────────────────────────────────────
   ;; ACCRUALS — debit accrual expense, credit accrual liability.
   ;; Note 84 §7.3 — vacation pay is the canonical accrual.
   ;; ──────────────────────────────────────────────────────────────
   :vacation-pay-accrual   {:account-tag :ca-payroll-vacation-accrual
                            :employer-side? true
                            :payable-tag :ca-payroll-vacation-liability}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — for T4 box reporting only.
   ;; ──────────────────────────────────────────────────────────────
   :ei-insurable-earnings    {:t4-box :box-24    :posts? false}
   :cpp-pensionable-earnings {:t4-box :box-26    :posts? false}
   :qpip-insurable-earnings  {:t4-box :box-56    :posts? false
                              :requires-qc? true}
   :pension-adjustment       {:t4-box :box-52    :posts? false}
   :dental-coverage-code     {:t4-box :box-45    :posts? false}})

(defn merged-catalog
  "Return the catalog merged with consumer-supplied extras-map. The
   extras-map can override or extend the standard catalog. Used by the
   posting builder + T4 builder so consumers can add bespoke kinds
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
   :accounts map keys on this tag. Per note 84 §7.2."
  ([kind] (account-tag kind nil))
  ([kind extras-map]
   (:account-tag (get (merged-catalog extras-map) kind))))

(defn payable-tag
  "Return the :payable-tag keyword for an employer-side kind. For
   :employer-cpp this is :ca-payroll-cpp (the same payable the
   employee-cpp deduction lands on — both employee + employer halves
   feed the same CRA liability bucket)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn t4-box
  "Return the T4-box keyword (e.g. :box-14, :box-16) the kind
   aggregates into for the year-end T4. nil = does not aggregate to a
   numbered T4 box (employer-side kinds, carry-only metadata, etc.)."
  ([kind] (t4-box kind nil))
  ([kind extras-map]
   (:t4-box (get (merged-catalog extras-map) kind))))

(defn t4-box-40-include?
  "True iff the kind contributes to the T4 box-40 'Other Information'
   taxable-benefit total. Per note 84 §5.2 + the T4 box-14 doc, box-40
   is the subset of box-14 that's non-cash taxable benefits."
  ([kind] (t4-box-40-include? kind nil))
  ([kind extras-map]
   (boolean (:t4-box-40-include? (get (merged-catalog extras-map) kind)))))

(defn requires-qc?
  "True iff the kind is only meaningful for QC employees."
  ([kind] (requires-qc? kind nil))
  ([kind extras-map]
   (boolean (:requires-qc? (get (merged-catalog extras-map) kind)))))

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

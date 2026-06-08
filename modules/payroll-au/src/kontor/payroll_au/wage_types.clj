(ns kontor.payroll-au.wage-types
  "AU-specific `:component-kind` extensions per ADR-080 + research
.3.

   Each kind maps to:
     - an `:account-tag` keyword (the consumer's chart-of-accounts
       lookup key; see `kontor.payroll-au.posting-builder`),
     - optionally an `:stp2-income-type` (STP Phase 2 income-type
       disaggregation per the ATO Software Developers' BMS BIG
       specification — SAW gross / OTE / PAYGW / Super / RFB),
     - optionally `:employer-side?` (employer-cost component — does
       NOT reduce employee gross→net),
     - optionally `:payable-tag` (the matching CR account tag for an
       employer-side accrual),
     - optionally `:posts? false` (carry-only for STP / payment-summary
       reporting; does not produce a posting leg).

   The catalog ships substrate-canonical (mirroring CA's pattern,
   not US's regex-driven ADP map). Consumers extend via
   `extras-map` opt at posting-builder / emit-provider construction.

   ## Validation convention

   `assert-valid!` throws ex-info on failure (DE/kernel-elsewhere
   convention). `validate` returns a vector of error maps for
   inspection. Mirrors `kontor.payroll-us-adp.wage-types/assert-valid!`
   shape; US validates a consumer regex map while AU validates a
   consumer-supplied `extras-map`.

   ## STP Phase 2 income-type disaggregation

   STP Phase 2 (ATO Software Developers BIG — Business Implementation
   Guide, accessed 2026-05-18 from softwaredevelopers.ato.gov.au)
   requires per-pay-event disaggregation:

     :gross                 SAW gross payments
     :ote                   ordinary time earnings (super basis)
     :overtime              overtime earnings
     :bonus-commission      irregular bonus + commission
     :directors-fees        directors' fees
     :paid-leave            paid leave categories
     :salary-sacrifice-s    salary-sacrifice (S — superannuation)
     :salary-sacrifice-o    salary-sacrifice (O — other)
     :allowance             work-related allowance (sub-typed per ATO)
     :lump-sum-a/b/d/e      lump-sum payments
     :etp                   employment-termination payment
     :paygw                 PAYG withholding (amount withheld)
     :super-guarantee       employer SG contributions
     :rfba                  reportable fringe-benefits amount

   See.3 (AU = C7 of the roadmap) + the task brief
   §1-§6 for the wage-type vocabulary expected.

   Reference: ADR-080, ATO Software Developers BIG (STP Phase 2)."
  (:require [clojure.set :as set]))

;; ============================================================================
;; Standard component-kind table
;; ============================================================================

(def standard-component-kinds
  "Canonical AU wage-type catalog. Keys are component kinds carried in
   `:payroll-facts/components`."
  {;; ──────────────────────────────────────────────────────────────
   ;; EARNINGS — debit gross wages expense, credit net wages payable
   ;; ──────────────────────────────────────────────────────────────
   :ordinary-time-earnings {:account-tag :au-payroll-wages
                            :stp2-income-type :ote}
   ;; Base wage — alias for ordinary-time-earnings, kept so the
   ;; substrate-canonical `:base-wage` keyword from `kontor.payroll-
   ;; provider` resolves cleanly under the AU adapter as OTE.
   :base-wage              {:account-tag :au-payroll-wages
                            :stp2-income-type :ote}
   :overtime               {:account-tag :au-payroll-wages
                            :stp2-income-type :overtime}
   :bonus                  {:account-tag :au-payroll-wages
                            :stp2-income-type :bonus-commission}
   :commission             {:account-tag :au-payroll-wages
                            :stp2-income-type :bonus-commission}
   :director-fee           {:account-tag :au-payroll-wages
                            :stp2-income-type :directors-fees}
   :paid-leave             {:account-tag :au-payroll-wages
                            :stp2-income-type :paid-leave}
   :work-related-allowance {:account-tag :au-payroll-wages
                            :stp2-income-type :allowance}
   :back-pay               {:account-tag :au-payroll-wages
                            :stp2-income-type :gross}

   ;; ──────────────────────────────────────────────────────────────
   ;; LUMP-SUM PAYMENTS — A/B/D/E sub-types per ATO Phase 2 BIG.
   ;; ──────────────────────────────────────────────────────────────
   :lump-sum-a             {:account-tag :au-payroll-wages
                            :stp2-income-type :lump-sum-a}
   :lump-sum-b             {:account-tag :au-payroll-wages
                            :stp2-income-type :lump-sum-b}
   :lump-sum-d             {:account-tag :au-payroll-wages
                            :stp2-income-type :lump-sum-d}
   :lump-sum-e             {:account-tag :au-payroll-wages
                            :stp2-income-type :lump-sum-e}

   ;; ──────────────────────────────────────────────────────────────
   ;; SALARY SACRIFICE — pre-tax deductions, reduce employee gross
   ;; for PAYGW but NOT for super-guarantee base (per OTE definition).
   ;; ──────────────────────────────────────────────────────────────
   :salary-sacrifice-super {:account-tag :au-payroll-salary-sacrifice
                            :stp2-income-type :salary-sacrifice-s}
   :salary-sacrifice-other {:account-tag :au-payroll-salary-sacrifice
                            :stp2-income-type :salary-sacrifice-o}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYEE DEDUCTIONS — credit ATO / fund payable accounts.
   ;; ──────────────────────────────────────────────────────────────
   :paygw                  {:account-tag :au-payroll-paygw
                            :stp2-income-type :paygw}
   :employee-super-contribution {:account-tag :au-payroll-super-employee
                                 :stp2-income-type :salary-sacrifice-s}
   :child-support          {:account-tag :au-payroll-child-support}
   :voluntary-deduction    {:account-tag :au-payroll-other-deduction}

   ;; ──────────────────────────────────────────────────────────────
   ;; EMPLOYER ACCRUALS — debit employer expense, credit payable.
   ;; Superannuation Guarantee (SG): 11.5% from 2024-07-01, rising
   ;; to 12.0% from 2025-07-01 (Australian Tax Office). The rate is
   ;; consumer-policy — kontor does NOT bundle the rate.
   ;; ──────────────────────────────────────────────────────────────
   :superannuation-guarantee-employer
   {:account-tag :au-payroll-er-super
    :employer-side? true
    :payable-tag :au-payroll-super
    :stp2-income-type :super-guarantee}

   ;; State-payroll-tax employer cost — threshold + rate vary per
   ;; state/territory + are consumer-policy. Kontor records the
   ;; accrual; the threshold + rate calc is the engine's job.
   :state-payroll-tax-employer
   {:account-tag :au-payroll-er-state-tax
    :employer-side? true
    :payable-tag :au-payroll-state-tax}

   ;; Workers compensation accrual — state-specific premium rates.
   ;; ADR-080 P2: per-state rate tables deferred to a follow-up.
   :workers-comp-employer  {:account-tag :au-payroll-er-workers-comp
                            :employer-side? true
                            :payable-tag :au-payroll-workers-comp}

   ;; FBT (Fringe Benefits Tax) carrier — reportable fringe-benefits
   ;; amount lives on STP year-to-date carry, not the per-period
   ;; posting. The employer-cost FBT itself lives in a separate
   ;; quarterly cycle outside scope.
   :reportable-fringe-benefit
   {:t4-box nil
    :posts? false
    :stp2-income-type :rfba}

   ;; ──────────────────────────────────────────────────────────────
   ;; CARRY-ONLY (NOT posted) — for STP reporting only.
   ;; The ATO BMS spec requires per-pay-event year-to-date totals
   ;; for OTE, gross, allowances, etc. — but those are aggregations
   ;; over the period's posting components, not separate components.
   ;; The carry-only kinds below are for *explicit* year-to-date or
   ;; informational values the engine emits but kontor does not post.
   ;; ──────────────────────────────────────────────────────────────
   :ytd-gross              {:posts? false}
   :ytd-ote                {:posts? false}
   :ytd-paygw              {:posts? false}
   :ytd-super-guarantee    {:posts? false}})

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
  "True iff the kind represents an employer-side cost (matches
   `:employer-side?` flag in a PayrollFact component)."
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
   :superannuation-guarantee-employer this is :au-payroll-super (the
   single Super payable bucket the SuperStream message clears)."
  ([kind] (payable-tag kind nil))
  ([kind extras-map]
   (:payable-tag (get (merged-catalog extras-map) kind))))

(defn stp2-income-type
  "Return the STP Phase 2 income-type keyword for a kind, or nil."
  ([kind] (stp2-income-type kind nil))
  ([kind extras-map]
   (:stp2-income-type (get (merged-catalog extras-map) kind))))

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

;; ============================================================================
;; Validation ( convention — throws on failure)
;; ============================================================================

(defn validate
  "Lightweight structural validation of a consumer-supplied
   `extras-map`. Returns a vector of error maps or nil on success.
   See `assert-valid!` for the throw-on-failure entry-point."
  [extras-map]
  (let [errs
        (cond-> []
          (and (some? extras-map)
               (not (map? extras-map)))
          (conj {:error :extras-map-not-a-map
                 :value extras-map})

          (and (map? extras-map)
               (some (fn [[k v]] (or (not (keyword? k))
                                     (not (map? v))))
                     extras-map))
          (conj {:error :extras-map-entries-malformed
                 :hint "Each extras-map entry must be keyword → map."}))]
    (when (seq errs) errs)))

(defn assert-valid!
  "Throws ex-info with `:errors` set to the validate output if the
   extras-map fails validation; returns the input unchanged on
   success. Canonical entry-point matching DE's
   `validate-catalog` + US's `assert-valid!` ( — across-adapter
   consistency)."
  [extras-map]
  (when-let [errs (validate extras-map)]
    (throw (ex-info "kontor.payroll-au extras-map invalid"
                    {:type :wage-type-extras/invalid
                     :errors errs})))
  extras-map)

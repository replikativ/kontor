(ns kontor.payroll-au.wage-types-test
  "Stage R C6 — AU wage-type catalog tests (ADR-080).

   Covers:
     - Catalog membership for the AU vocabulary (OTE / overtime / bonus
       / commission / director-fee / paid-leave / lump-sum-A-E /
       salary-sacrifice-S+O / PAYGW / SG / state payroll tax /
       workers-comp).
     - STP Phase 2 income-type mapping per kind.
     - employer-side? + payable-tag invariants.
     - extras-map extension.
     - validate / assert-valid! convention (P2-86-5 — throws on
       failure, mirrors DE)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-au.wage-types :as wt]))

(deftest standard-catalog-covers-au-vocabulary
  (testing "core earnings kinds are present"
    (doseq [k [:ordinary-time-earnings :base-wage :overtime :bonus
               :commission :director-fee :paid-leave
               :work-related-allowance :back-pay
               :lump-sum-a :lump-sum-b :lump-sum-d :lump-sum-e]]
      (is (contains? wt/standard-component-kinds k)
          (str "Expected catalog to include " k))))
  (testing "deductions + employer accruals present"
    (doseq [k [:paygw :salary-sacrifice-super :salary-sacrifice-other
               :employee-super-contribution :child-support
               :voluntary-deduction
               :superannuation-guarantee-employer
               :state-payroll-tax-employer
               :workers-comp-employer
               :reportable-fringe-benefit]]
      (is (contains? wt/standard-component-kinds k)
          (str "Expected catalog to include " k)))))

(deftest ote-and-base-wage-share-wage-tag
  (testing "both kinds route to :au-payroll-wages"
    (is (= :au-payroll-wages (wt/account-tag :ordinary-time-earnings)))
    (is (= :au-payroll-wages (wt/account-tag :base-wage))))
  (testing "both kinds carry the :ote STP income-type"
    (is (= :ote (wt/stp2-income-type :ordinary-time-earnings)))
    (is (= :ote (wt/stp2-income-type :base-wage)))))

(deftest stp-income-type-disaggregation-mapping
  (testing "wage kinds map to per-ATO STP Phase 2 codes"
    (is (= :overtime (wt/stp2-income-type :overtime)))
    (is (= :bonus-commission (wt/stp2-income-type :bonus)))
    (is (= :bonus-commission (wt/stp2-income-type :commission)))
    (is (= :directors-fees (wt/stp2-income-type :director-fee)))
    (is (= :paid-leave (wt/stp2-income-type :paid-leave)))
    (is (= :allowance (wt/stp2-income-type :work-related-allowance))))
  (testing "lump-sum kinds map to the four ATO sub-codes"
    (is (= :lump-sum-a (wt/stp2-income-type :lump-sum-a)))
    (is (= :lump-sum-b (wt/stp2-income-type :lump-sum-b)))
    (is (= :lump-sum-d (wt/stp2-income-type :lump-sum-d)))
    (is (= :lump-sum-e (wt/stp2-income-type :lump-sum-e))))
  (testing "salary-sacrifice has two sub-codes per BIG"
    (is (= :salary-sacrifice-s (wt/stp2-income-type :salary-sacrifice-super)))
    (is (= :salary-sacrifice-o (wt/stp2-income-type :salary-sacrifice-other))))
  (testing "PAYGW + SG + RFBA"
    (is (= :paygw (wt/stp2-income-type :paygw)))
    (is (= :super-guarantee (wt/stp2-income-type :superannuation-guarantee-employer)))
    (is (= :rfba (wt/stp2-income-type :reportable-fringe-benefit)))))

(deftest employer-side-and-payable-tag-invariants
  (testing "SG is employer-side + has the right payable tag"
    (is (true? (wt/employer-side? :superannuation-guarantee-employer)))
    (is (= :au-payroll-er-super
           (wt/account-tag :superannuation-guarantee-employer)))
    (is (= :au-payroll-super
           (wt/payable-tag :superannuation-guarantee-employer))))
  (testing "state payroll tax is employer-side"
    (is (true? (wt/employer-side? :state-payroll-tax-employer)))
    (is (= :au-payroll-state-tax
           (wt/payable-tag :state-payroll-tax-employer))))
  (testing "workers comp is employer-side"
    (is (true? (wt/employer-side? :workers-comp-employer)))
    (is (= :au-payroll-workers-comp
           (wt/payable-tag :workers-comp-employer))))
  (testing "OTE / bonus are NOT employer-side"
    (is (false? (wt/employer-side? :ordinary-time-earnings)))
    (is (false? (wt/employer-side? :bonus)))))

(deftest carry-only-kinds-do-not-post
  (testing "ytd-* kinds are carry-only (no posting legs)"
    (is (false? (wt/posts? :ytd-gross)))
    (is (false? (wt/posts? :ytd-ote)))
    (is (false? (wt/posts? :ytd-paygw)))
    (is (false? (wt/posts? :ytd-super-guarantee))))
  (testing "RFBA is carry-only (year-to-date only on STP)"
    (is (false? (wt/posts? :reportable-fringe-benefit))))
  (testing "regular kinds DO post"
    (is (true? (wt/posts? :ordinary-time-earnings)))
    (is (true? (wt/posts? :paygw)))
    (is (true? (wt/posts? :superannuation-guarantee-employer)))))

(deftest extras-map-extends-catalog
  (let [extras {:meal-allowance {:account-tag :au-payroll-wages
                                 :stp2-income-type :allowance}}]
    (testing "extras-map kind appears in merged catalog"
      (is (contains? (wt/merged-catalog extras) :meal-allowance)))
    (testing "account-tag + stp2-income-type resolve through extras"
      (is (= :au-payroll-wages (wt/account-tag :meal-allowance extras)))
      (is (= :allowance (wt/stp2-income-type :meal-allowance extras))))))

(deftest unknown-kinds-helper
  (let [components [{:kind :ordinary-time-earnings :amount 100M}
                    {:kind :weird-new-thing :amount 50M}
                    {:kind :paygw :amount -20M}]]
    (testing "unknown-kinds surfaces only the missing kind"
      (is (= #{:weird-new-thing} (wt/unknown-kinds components))))))

(deftest validate-and-assert-valid-convention
  (testing "validate returns nil on a clean nil extras-map"
    (is (nil? (wt/validate nil)))
    (is (nil? (wt/validate {}))))
  (testing "validate surfaces error on a non-map extras-map"
    (is (some? (wt/validate "not a map")))
    (is (= :extras-map-not-a-map (-> (wt/validate "not a map") first :error))))
  (testing "validate surfaces error on malformed entries"
    (is (some? (wt/validate {:meal-allowance "string-not-map"}))))
  (testing "assert-valid! throws on failure (DE/kernel convention; P2-86-5)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid"
                          (wt/assert-valid! "not a map"))))
  (testing "assert-valid! returns the input on success"
    (is (= {} (wt/assert-valid! {})))
    (let [extras {:foo {:account-tag :au-payroll-wages}}]
      (is (= extras (wt/assert-valid! extras))))))

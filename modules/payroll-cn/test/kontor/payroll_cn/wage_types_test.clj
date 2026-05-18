(ns kontor.payroll-cn.wage-types-test
  "Unit tests for the CN wage-type catalog (ADR-085 / note 87 §3)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.wage-types :as wt]))

(deftest standard-catalog-has-required-kinds
  (testing "all 12 standard kinds + 3 carry-only are present"
    (let [kinds (wt/known-kinds)]
      (is (contains? kinds :base-wage))
      (is (contains? kinds :performance-bonus))
      (is (contains? kinds :overtime))
      (is (contains? kinds :annual-bonus))
      (is (contains? kinds :allowance))
      (is (contains? kinds :taxable-benefit))
      (is (contains? kinds :iit-withheld))
      (is (contains? kinds :ee-pension))
      (is (contains? kinds :ee-medical))
      (is (contains? kinds :ee-unemployment))
      (is (contains? kinds :ee-housing-fund))
      (is (contains? kinds :er-pension))
      (is (contains? kinds :er-medical))
      (is (contains? kinds :er-unemployment))
      (is (contains? kinds :er-work-injury))
      (is (contains? kinds :er-maternity))
      (is (contains? kinds :er-housing-fund))
      (is (contains? kinds :annual-bonus-accrual))
      ;; carry-only
      (is (contains? kinds :si-base))
      (is (contains? kinds :hf-base))
      (is (contains? kinds :cumulative-taxable-ytd)))))

(deftest employer-side-classification
  (testing "employee deductions are NOT employer-side"
    (is (not (wt/employer-side? :iit-withheld)))
    (is (not (wt/employer-side? :ee-pension)))
    (is (not (wt/employer-side? :ee-housing-fund))))
  (testing "employer SI / HF kinds ARE employer-side"
    (is (wt/employer-side? :er-pension))
    (is (wt/employer-side? :er-medical))
    (is (wt/employer-side? :er-unemployment))
    (is (wt/employer-side? :er-work-injury))
    (is (wt/employer-side? :er-maternity))
    (is (wt/employer-side? :er-housing-fund))
    (is (wt/employer-side? :annual-bonus-accrual))))

(deftest posts-flag
  (testing "carry-only kinds are NOT posted"
    (is (not (wt/posts? :si-base)))
    (is (not (wt/posts? :hf-base)))
    (is (not (wt/posts? :cumulative-taxable-ytd))))
  (testing "real components ARE posted"
    (is (wt/posts? :base-wage))
    (is (wt/posts? :iit-withheld))
    (is (wt/posts? :er-pension))))

(deftest account-tag-resolution
  (testing "earnings route to wages-expense"
    (is (= :cn-payroll-wages-expense (wt/account-tag :base-wage)))
    (is (= :cn-payroll-wages-expense (wt/account-tag :overtime)))
    (is (= :cn-payroll-wages-expense (wt/account-tag :annual-bonus))))
  (testing "IIT routes to its own tag"
    (is (= :cn-payroll-iit (wt/account-tag :iit-withheld))))
  (testing "employee SI routes to ee-si bucket"
    (is (= :cn-payroll-ee-si (wt/account-tag :ee-pension)))
    (is (= :cn-payroll-ee-si (wt/account-tag :ee-medical))))
  (testing "employee HF routes to ee-hf bucket"
    (is (= :cn-payroll-ee-hf (wt/account-tag :ee-housing-fund))))
  (testing "employer-side routes to expense tag"
    (is (= :cn-payroll-er-si-expense (wt/account-tag :er-pension)))
    (is (= :cn-payroll-er-hf-expense (wt/account-tag :er-housing-fund)))))

(deftest payable-tag-resolution
  (testing "employer-side kinds have a payable-tag for the CR leg"
    (is (= :cn-payroll-er-si-payable (wt/payable-tag :er-pension)))
    (is (= :cn-payroll-er-hf-payable (wt/payable-tag :er-housing-fund))))
  (testing "earnings have the net-wages payable as their CR target"
    (is (= :cn-payroll-net-wages (wt/payable-tag :base-wage))))
  (testing "annual-bonus-accrual has the bonus-payable bucket"
    (is (= :cn-payroll-bonus-payable (wt/payable-tag :annual-bonus-accrual)))))

(deftest asbe-sub-account
  (testing "wages map to :wages bucket"
    (is (= :wages (wt/asbe-sub-account :base-wage)))
    (is (= :wages (wt/asbe-sub-account :annual-bonus))))
  (testing "SI components map to :si"
    (is (= :si (wt/asbe-sub-account :ee-pension)))
    (is (= :si (wt/asbe-sub-account :er-medical))))
  (testing "HF components map to :hf"
    (is (= :hf (wt/asbe-sub-account :ee-housing-fund)))
    (is (= :hf (wt/asbe-sub-account :er-housing-fund))))
  (testing "IIT has no ASBE sub-account (lives under 2221, not 2211)"
    (is (nil? (wt/asbe-sub-account :iit-withheld)))))

(deftest chinese-name-helpers
  (is (= "基本工资" (wt/chinese-name :base-wage)))
  (is (= "年终奖" (wt/chinese-name :annual-bonus)))
  (is (= "个人所得税" (wt/chinese-name :iit-withheld)))
  (is (= "养老保险-单位" (wt/chinese-name :er-pension))))

(deftest special-tax-treatment-flag
  (testing "annual-bonus is flagged for special tax treatment"
    (is (wt/special-tax-treatment? :annual-bonus)))
  (testing "performance-bonus is NOT (it's monthly cumulative method)"
    (is (not (wt/special-tax-treatment? :performance-bonus))))
  (testing "regular wages are not"
    (is (not (wt/special-tax-treatment? :base-wage)))))

(deftest unknown-kinds-detection
  (testing "all-known returns empty"
    (is (empty? (wt/unknown-kinds [{:kind :base-wage}
                                   {:kind :iit-withheld}
                                   {:kind :ee-pension}]))))
  (testing "unknown surface as a set"
    (is (= #{:bogus} (wt/unknown-kinds [{:kind :base-wage}
                                        {:kind :bogus}])))))

(deftest assert-known-throws
  (is (= [{:kind :base-wage}]
         (wt/assert-known! [{:kind :base-wage}])))
  (is (thrown? clojure.lang.ExceptionInfo
               (wt/assert-known! [{:kind :nonsense}]))))

(deftest consumer-extras-extend-catalog
  (let [extras {:executive-rsv {:account-tag :cn-payroll-wages-expense
                                :payable-tag :cn-payroll-net-wages
                                :asbe-sub-account :wages
                                :chinese-name "高管限制性股权"}}]
    (is (= :cn-payroll-wages-expense (wt/account-tag :executive-rsv extras)))
    (is (= "高管限制性股权" (wt/chinese-name :executive-rsv extras)))
    (is (contains? (wt/known-kinds extras) :executive-rsv))))

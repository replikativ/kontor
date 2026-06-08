(ns kontor.payroll-ca.wage-types-test
  "Catalog membership + lookup tests."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-ca.wage-types :as wt]))

(deftest catalog-covers-note-84-section-10-2
  (testing "Earnings kinds map to wages account tag"
    (doseq [k [:base-wage :overtime :bonus :commission :vacation-pay-paid-out
               :statutory-holiday-pay :retroactive-pay :severance
               :retiring-allowance]]
      (is (= :ca-payroll-wages (wt/account-tag k))
          (str "Earnings kind " k " should route to :ca-payroll-wages"))))
  (testing "Three statutory CRA buckets distinct"
    (is (= :ca-payroll-itx (wt/account-tag :income-tax-withheld)))
    (is (= :ca-payroll-cpp (wt/account-tag :employee-cpp)))
    (is (= :ca-payroll-cpp (wt/account-tag :employee-cpp2)))
    (is (= :ca-payroll-ei  (wt/account-tag :employee-ei))))
  (testing "Employer-side flags"
    (is (wt/employer-side? :employer-cpp))
    (is (wt/employer-side? :employer-ei))
    (is (wt/employer-side? :vacation-pay-accrual))
    (is (not (wt/employer-side? :employee-cpp))))
  (testing "Payable-tag derives employer kind to its payable bucket"
    (is (= :ca-payroll-cpp  (wt/payable-tag :employer-cpp)))
    (is (= :ca-payroll-cpp  (wt/payable-tag :employer-cpp2)))
    (is (= :ca-payroll-ei   (wt/payable-tag :employer-ei))))
  (testing "Carry-only kinds (no posting)"
    (is (not (wt/posts? :ei-insurable-earnings)))
    (is (not (wt/posts? :cpp-pensionable-earnings)))
    (is (not (wt/posts? :pension-adjustment)))
    (is (not (wt/posts? :dental-coverage-code)))
    (is (not (wt/posts? :qpip-insurable-earnings))))
  (testing "T4-box mapping coverage"
    (is (= :box-14  (wt/t4-box :base-wage)))
    (is (= :box-16  (wt/t4-box :employee-cpp)))
    (is (= :box-16a (wt/t4-box :employee-cpp2)))
    (is (= :box-17  (wt/t4-box :employee-qpp)))
    (is (= :box-17a (wt/t4-box :employee-qpp2)))
    (is (= :box-18  (wt/t4-box :employee-ei)))
    (is (= :box-20  (wt/t4-box :employee-rpp-contribution)))
    (is (= :box-22  (wt/t4-box :income-tax-withheld)))
    (is (= :box-24  (wt/t4-box :ei-insurable-earnings)))
    (is (= :box-26  (wt/t4-box :cpp-pensionable-earnings)))
    (is (= :box-44  (wt/t4-box :union-dues)))
    (is (= :box-46  (wt/t4-box :charitable-donation-payroll)))
    (is (= :box-52  (wt/t4-box :pension-adjustment)))
    (is (= :box-55  (wt/t4-box :employee-qpip)))
    (is (= :box-56  (wt/t4-box :qpip-insurable-earnings))))
  (testing "Box 40 inclusion flag set on taxable benefits"
    (is (wt/t4-box-40-include? :taxable-benefit-auto))
    (is (wt/t4-box-40-include? :taxable-benefit-group-term-life))
    (is (wt/t4-box-40-include? :taxable-benefit-parking))
    (is (wt/t4-box-40-include? :taxable-benefit-other))
    (is (not (wt/t4-box-40-include? :base-wage))))
  (testing "QC carve-out is flagged"
    (is (wt/requires-qc? :employee-qpp))
    (is (wt/requires-qc? :employee-qpip))
    (is (wt/requires-qc? :employee-qc-itx))
    (is (not (wt/requires-qc? :employee-cpp)))))

(deftest extras-map-extends-without-clobbering
  (let [extras {:custom-bonus {:account-tag :my-custom :t4-box :box-14}}]
    (testing "Standard kinds still resolve"
      (is (= :ca-payroll-wages (wt/account-tag :base-wage extras))))
    (testing "Custom kind resolves to user-supplied tag"
      (is (= :my-custom (wt/account-tag :custom-bonus extras))))
    (testing "Known kinds union"
      (is (contains? (wt/known-kinds extras) :custom-bonus))
      (is (contains? (wt/known-kinds extras) :base-wage)))))

(deftest unknown-kinds-detected
  (let [comps [{:kind :base-wage} {:kind :totally-fictional}]]
    (is (= #{:totally-fictional} (wt/unknown-kinds comps)))))

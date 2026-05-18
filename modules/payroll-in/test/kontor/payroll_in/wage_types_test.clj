(ns kontor.payroll-in.wage-types-test
  "IN wage-type catalog membership + lookup tests."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.wage-types :as wt]))

(deftest catalog-covers-in-payroll-vocabulary
  (testing "Earnings kinds map to salaries-and-wages account tag"
    (doseq [k [:basic-salary :dearness-allowance :house-rent-allowance
               :leave-travel-allowance :medical-allowance :special-allowance
               :overtime :commission :retroactive-pay :leave-encashment]]
      (is (= :in-payroll-salaries-wages (wt/account-tag k))
          (str "Earnings kind " k " should route to :in-payroll-salaries-wages"))))
  (testing "Bonus routes to its own bucket"
    (is (= :in-payroll-bonus (wt/account-tag :bonus))))
  (testing "Gratuity paid routes to its own bucket"
    (is (= :in-payroll-gratuity-paid (wt/account-tag :gratuity-paid))))
  (testing "Four statutory deduction buckets are distinct"
    (is (= :in-payroll-tds-payable (wt/account-tag :tds)))
    (is (= :in-payroll-pf-payable  (wt/account-tag :pf-employee)))
    (is (= :in-payroll-esi-payable (wt/account-tag :esi-employee)))
    (is (= :in-payroll-pt-payable  (wt/account-tag :professional-tax))))
  (testing "Employer-side flags"
    (is (wt/employer-side? :pf-employer))
    (is (wt/employer-side? :pf-employer-eps))
    (is (wt/employer-side? :pf-employer-epf))
    (is (wt/employer-side? :pf-employer-edli))
    (is (wt/employer-side? :esi-employer))
    (is (wt/employer-side? :bonus-accrual))
    (is (wt/employer-side? :leave-encashment-accrual))
    (is (wt/employer-side? :employer-gratuity-accrual))
    (is (not (wt/employer-side? :pf-employee)))
    (is (not (wt/employer-side? :tds))))
  (testing "Payable-tag derives employer kind to its payable bucket"
    (is (= :in-payroll-pf-payable  (wt/payable-tag :pf-employer)))
    (is (= :in-payroll-pf-payable  (wt/payable-tag :pf-employer-eps)))
    (is (= :in-payroll-pf-payable  (wt/payable-tag :pf-employer-epf)))
    (is (= :in-payroll-pf-payable  (wt/payable-tag :pf-employer-edli)))
    (is (= :in-payroll-esi-payable (wt/payable-tag :esi-employer)))
    (is (= :in-payroll-bonus-payable (wt/payable-tag :bonus-accrual)))
    (is (= :in-payroll-leave-liability (wt/payable-tag :leave-encashment-accrual)))
    (is (= :in-payroll-gratuity-liability (wt/payable-tag :employer-gratuity-accrual))))
  (testing "Carry-only kinds (no posting)"
    (is (not (wt/posts? :pf-wages)))
    (is (not (wt/posts? :esi-wages)))
    (is (not (wt/posts? :section-80c-deduction)))
    (is (not (wt/posts? :section-80d-deduction)))
    (is (not (wt/posts? :section-80g-deduction)))
    (is (not (wt/posts? :hra-exemption-claimed)))
    (is (not (wt/posts? :taxable-income-ytd)))
    ;; Regular kinds DO post
    (is (wt/posts? :basic-salary))
    (is (wt/posts? :tds)))
  (testing "Form 24Q section mapping"
    ;; Section 192 applies to all salary-type earnings
    (is (= :sec-192 (wt/form-24q-section :basic-salary)))
    (is (= :sec-192 (wt/form-24q-section :bonus)))
    (is (= :sec-192 (wt/form-24q-section :tds))))
  (testing "PF / ESI applicability flags"
    ;; Basic + DA are part of both PF and ESI wages
    (is (wt/pf-applicable? :basic-salary))
    (is (wt/pf-applicable? :dearness-allowance))
    (is (wt/esi-applicable? :basic-salary))
    (is (wt/esi-applicable? :dearness-allowance))
    ;; HRA is EXCLUDED from PF wages (S.Roshni SC 2019 default) but
    ;; INCLUDED in ESI wages.
    (is (not (wt/pf-applicable? :house-rent-allowance)))
    (is (wt/esi-applicable? :house-rent-allowance))
    ;; LTA / Medical out of PF, in ESI
    (is (not (wt/pf-applicable? :leave-travel-allowance)))
    (is (wt/esi-applicable? :leave-travel-allowance))))

(deftest extras-map-extends-without-clobbering
  (let [extras {:perquisite-car-lease
                {:account-tag :in-payroll-salaries-wages
                 :form-24q-section :sec-192}}]
    (testing "Standard kinds still resolve"
      (is (= :in-payroll-salaries-wages (wt/account-tag :basic-salary extras))))
    (testing "Custom kind resolves to user-supplied tag"
      (is (= :in-payroll-salaries-wages
             (wt/account-tag :perquisite-car-lease extras))))
    (testing "Known kinds union"
      (is (contains? (wt/known-kinds extras) :perquisite-car-lease))
      (is (contains? (wt/known-kinds extras) :basic-salary)))))

(deftest unknown-kinds-detected
  (let [comps [{:kind :basic-salary} {:kind :totally-fictional}]]
    (is (= #{:totally-fictional} (wt/unknown-kinds comps)))))

(deftest pt-states-only-include-pt-levying-jurisdictions
  (testing "Major PT-levying states included"
    (doseq [code ["IN-MH" "IN-KA" "IN-WB" "IN-TN" "IN-GJ" "IN-TG"
                  "IN-KL" "IN-MP"]]
      (is (wt/pt-state? code)
          (str code " should be a PT-levying state"))))
  (testing "Non-PT states excluded"
    (doseq [code ["IN-UP" "IN-DL" "IN-HR" "IN-PB" "IN-RJ" "IN-UT"
                  "IN-HP" "IN-JK" "IN-LA" "IN-GA"]]
      (is (not (wt/pt-state? code))
          (str code " should NOT levy PT"))))
  (testing "Unknown / nil codes safely return false"
    (is (not (wt/pt-state? nil)))
    (is (not (wt/pt-state? "IN-XX")))
    (is (not (wt/pt-state? "")))))

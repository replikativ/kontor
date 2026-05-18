(ns kontor.payroll-jp.wage-types-test
  "Catalog membership + lookup tests for the JP wage-type vocabulary."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.wage-types :as wt]))

(deftest catalog-covers-jp-wage-vocabulary
  (testing "Earnings kinds map to wages account tag"
    (doseq [k [:base-wage :overtime :commuting-allowance
               :housing-allowance :family-allowance :position-allowance]]
      (is (= :jp-payroll-wages (wt/account-tag k))
          (str "Earnings kind " k " should route to :jp-payroll-wages"))))
  (testing "Bonus routes to its own 賞与 account"
    (is (= :jp-payroll-bonus (wt/account-tag :bonus))))
  (testing "4-bucket statutory SI distinct"
    (is (= :jp-payroll-health-insurance (wt/account-tag :employee-health-insurance)))
    (is (= :jp-payroll-pension (wt/account-tag :employee-pension)))
    (is (= :jp-payroll-employment-insurance (wt/account-tag :employee-employment-insurance)))
    (is (= :jp-payroll-long-term-care (wt/account-tag :employee-long-term-care))))
  (testing "Tax withholdings"
    (is (= :jp-payroll-income-tax (wt/account-tag :income-tax-withheld)))
    (is (= :jp-payroll-resident-tax (wt/account-tag :resident-tax-withheld))))
  (testing "Voluntary deductions"
    (is (= :jp-payroll-zaikei (wt/account-tag :zaikei-savings)))
    (is (= :jp-payroll-union-dues (wt/account-tag :union-dues)))
    (is (= :jp-payroll-other-deduction (wt/account-tag :voluntary-deduction)))))

(deftest employer-side-flags
  (testing "Employer-side flags set on the 4 employer SI kinds"
    (is (wt/employer-side? :employer-health-insurance))
    (is (wt/employer-side? :employer-pension))
    (is (wt/employer-side? :employer-employment-insurance))
    (is (wt/employer-side? :employer-long-term-care))
    (is (not (wt/employer-side? :employee-health-insurance)))
    (is (not (wt/employer-side? :base-wage)))))

(deftest payable-tag-routes-employer-to-employee-bucket
  (testing "Employer SI maps to the same payable bucket as the employee deduction"
    (is (= :jp-payroll-health-insurance       (wt/payable-tag :employer-health-insurance)))
    (is (= :jp-payroll-pension                (wt/payable-tag :employer-pension)))
    (is (= :jp-payroll-employment-insurance   (wt/payable-tag :employer-employment-insurance)))
    (is (= :jp-payroll-long-term-care         (wt/payable-tag :employer-long-term-care))))
  (testing "Employer kinds all hit the :jp-payroll-er-statutory-benefits expense"
    (is (= :jp-payroll-er-statutory-benefits (wt/account-tag :employer-health-insurance)))
    (is (= :jp-payroll-er-statutory-benefits (wt/account-tag :employer-pension)))
    (is (= :jp-payroll-er-statutory-benefits (wt/account-tag :employer-employment-insurance)))
    (is (= :jp-payroll-er-statutory-benefits (wt/account-tag :employer-long-term-care)))))

(deftest gensen-box-mapping
  (testing "Earnings + bonus aggregate to :gensen/payment-amount (支払金額)"
    (is (= :gensen/payment-amount (wt/gensen-box :base-wage)))
    (is (= :gensen/payment-amount (wt/gensen-box :overtime)))
    (is (= :gensen/payment-amount (wt/gensen-box :bonus)))
    (is (= :gensen/payment-amount (wt/gensen-box :commuting-allowance))))
  (testing "Income tax withholding aggregates to :gensen/withholding-amount (源泉徴収税額)"
    (is (= :gensen/withholding-amount (wt/gensen-box :income-tax-withheld))))
  (testing "Employee SI components aggregate to :gensen/social-insurance-paid (社会保険料等)"
    (is (= :gensen/social-insurance-paid (wt/gensen-box :employee-health-insurance)))
    (is (= :gensen/social-insurance-paid (wt/gensen-box :employee-pension)))
    (is (= :gensen/social-insurance-paid (wt/gensen-box :employee-employment-insurance)))
    (is (= :gensen/social-insurance-paid (wt/gensen-box :employee-long-term-care))))
  (testing "Resident tax does NOT appear on the Gensen (municipality, not NTA)"
    (is (nil? (wt/gensen-box :resident-tax-withheld))))
  (testing "Voluntary deductions do NOT appear on the Gensen"
    (is (nil? (wt/gensen-box :zaikei-savings)))
    (is (nil? (wt/gensen-box :union-dues)))
    (is (nil? (wt/gensen-box :voluntary-deduction)))))

(deftest age-40-flag-on-kaigo
  (testing "Long-term-care (介護保険料) is age-40-gated"
    (is (wt/requires-age-40? :employee-long-term-care))
    (is (wt/requires-age-40? :employer-long-term-care))
    (is (not (wt/requires-age-40? :employee-health-insurance)))
    (is (not (wt/requires-age-40? :base-wage)))))

(deftest carry-only-kinds-do-not-post
  (testing "Carry-only Gensen-input kinds are flagged :posts? false"
    (is (not (wt/posts? :gensen-employment-income-deduction)))
    (is (not (wt/posts? :gensen-taxable-income)))
    (is (not (wt/posts? :gensen-spouse-deduction)))
    (is (not (wt/posts? :gensen-dependent-deduction)))
    (is (not (wt/posts? :gensen-social-insurance-paid-ytd))))
  (testing "Normal posting kinds DO post"
    (is (wt/posts? :base-wage))
    (is (wt/posts? :income-tax-withheld))
    (is (wt/posts? :employer-pension))))

(deftest kanji-labels-present
  (testing "Common kinds carry Kanji labels for narration"
    (is (= "基本給" (wt/kanji :base-wage)))
    (is (= "賞与" (wt/kanji :bonus)))
    (is (= "健康保険料" (wt/kanji :employee-health-insurance)))
    (is (= "厚生年金保険料" (wt/kanji :employee-pension)))
    (is (= "雇用保険料" (wt/kanji :employee-employment-insurance)))
    (is (= "介護保険料" (wt/kanji :employee-long-term-care)))
    (is (= "所得税" (wt/kanji :income-tax-withheld)))
    (is (= "住民税" (wt/kanji :resident-tax-withheld)))
    (is (= "通勤手当" (wt/kanji :commuting-allowance)))))

(deftest extras-map-extends-without-clobbering
  (let [extras {:custom-allowance {:account-tag :my-custom
                                   :gensen-box :gensen/payment-amount
                                   :kanji "カスタム手当"}}]
    (testing "Standard kinds still resolve"
      (is (= :jp-payroll-wages (wt/account-tag :base-wage extras))))
    (testing "Custom kind resolves to user-supplied tag"
      (is (= :my-custom (wt/account-tag :custom-allowance extras)))
      (is (= "カスタム手当" (wt/kanji :custom-allowance extras))))
    (testing "Known kinds union"
      (is (contains? (wt/known-kinds extras) :custom-allowance))
      (is (contains? (wt/known-kinds extras) :base-wage)))))

(deftest unknown-kinds-detected
  (let [comps [{:kind :base-wage} {:kind :totally-fictional-kind}]]
    (is (= #{:totally-fictional-kind} (wt/unknown-kinds comps)))))

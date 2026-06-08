(ns kontor.payroll-jp.gensen-test
  "Tests for the year-end 源泉徴収票 (Gensen Choshu Hyo) builder."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.gensen :as gensen])
  (:import [java.math BigDecimal]))

(defn- monthly-fact
  "Build a representative monthly PayrollFact for a Tokyo employee
   to feed the year-end aggregator. Amounts are illustrative."
  [employment]
  {:employment employment
   :gross 340000M
   :net 264960M
   :components [{:kind :base-wage                     :amount 300000M :employer-side? false}
                {:kind :commuting-allowance           :amount 15000M  :employer-side? false}
                {:kind :overtime                      :amount 25000M  :employer-side? false}
                {:kind :employee-health-insurance     :amount -16500M :employer-side? false}
                {:kind :employee-pension              :amount -30500M :employer-side? false}
                {:kind :employee-employment-insurance :amount -2040M  :employer-side? false}
                {:kind :income-tax-withheld           :amount -8000M  :employer-side? false}
                {:kind :resident-tax-withheld         :amount -18000M :employer-side? false}]})

(def tanaka-person
  {:given-name "太郎"
   :family-name "田中"
   :address "東京都新宿区..."
   :birth-date #inst "1985-06-12"
   :my-number-present? true})

(def employer
  {:name "Acme株式会社"
   :corporate-number "8700110005901"
   :address "東京都港区..."
   :representative "代表取締役 山田一郎"})

;; ============================================================================
;; payroll-facts->gensen-statement
;; ============================================================================

(deftest gensen-aggregates-payment-amount-over-12-months
  (let [facts (vec (repeat 12 (monthly-fact :emp/tanaka)))
        statement (gensen/payroll-facts->gensen-statement
                   {:facts facts
                    :person tanaka-person
                    :employer employer
                    :tax-year 2026})]
    (testing "支払金額 (payment-amount): 12 × 340000 = 4_080_000"
      (is (= 4080000M (:gensen/payment-amount statement))))
    (testing "源泉徴収税額 (withholding-amount): 12 × 8000 = 96_000"
      (is (= 96000M (:gensen/withholding-amount statement))))
    (testing "社会保険料等 (SI paid): 12 × (16500+30500+2040) = 588_480"
      (is (= 588480M (:gensen/social-insurance-paid statement))))
    (testing "Tax year preserved"
      (is (= 2026 (:gensen/tax-year statement))))
    (testing "Employer carried"
      (is (= "8700110005901" (-> statement :gensen/employer :corporate-number))))
    (testing "Employee identity carried (My Number value NOT inlined)"
      (is (= "田中" (-> statement :gensen/employee :family-name)))
      (is (= "太郎" (-> statement :gensen/employee :given-name)))
      (is (true? (-> statement :gensen/employee :my-number-present?)))
      ;; Negative case: kontor must NOT carry the My Number value itself.
      (is (not (contains? (:gensen/employee statement) :my-number)))
      (is (not (contains? (:gensen/employee statement) :個人番号))))))

(deftest gensen-omits-residential-tax-from-witholding
  "Per ADR-084 §7 the Gensen reports 源泉徴収税額 = income-tax-withheld
   ONLY. 住民税 (resident tax) goes to municipalities via the
   給与支払報告書 — NOT on the NTA-bound Gensen."
  (let [facts (vec (repeat 12 (monthly-fact :emp/tanaka)))
        statement (gensen/payroll-facts->gensen-statement
                   {:facts facts
                    :person tanaka-person
                    :employer employer
                    :tax-year 2026})]
    ;; If resident-tax leaked into the Gensen :withholding-amount, the
    ;; total would be 12 × (8000+18000) = 312_000.
    (is (= 96000M (:gensen/withholding-amount statement))
        "Resident tax must NOT be aggregated into the Gensen withholding-amount box")))

(deftest gensen-rolls-bonus-into-payment-amount
  (let [monthly (vec (repeat 12 (monthly-fact :emp/tanaka)))
        bonus-fact {:employment :emp/tanaka
                    :gross 800000M
                    :net 700000M
                    :components [{:kind :bonus              :amount 800000M :employer-side? false}
                                 {:kind :income-tax-withheld :amount -100000M :employer-side? false}]}
        ;; Summer + winter bonus
        facts (vec (concat monthly [bonus-fact bonus-fact]))
        statement (gensen/payroll-facts->gensen-statement
                   {:facts facts
                    :person tanaka-person
                    :employer employer
                    :tax-year 2026})]
    (testing "Bonus aggregates to :gensen/payment-amount with monthly wages"
      ;; 4_080_000 (12 months) + 1_600_000 (2 bonuses) = 5_680_000
      (is (= 5680000M (:gensen/payment-amount statement))))
    (testing "Bonus withholding aggregates to :gensen/withholding-amount"
      ;; 96_000 (12 months) + 200_000 (2 bonuses) = 296_000
      (is (= 296000M (:gensen/withholding-amount statement))))))

(deftest gensen-honors-carry-only-from-jurisdiction-codes
  "Engine-computed inputs (給与所得控除後の金額 etc.) flow through
   :jurisdiction-specific-codes per ADR-075's carry-only contract."
  (let [facts [{:employment :emp/tanaka
                :gross 340000M
                :net 264960M
                :components [{:kind :base-wage                 :amount 300000M :employer-side? false}
                             {:kind :commuting-allowance       :amount 15000M  :employer-side? false}
                             {:kind :employee-health-insurance :amount -16500M :employer-side? false}
                             {:kind :income-tax-withheld       :amount -8000M  :employer-side? false}]
                :jurisdiction-specific-codes
                {:gensen-employment-income-deduction 105000M
                 :gensen-taxable-income 195000M
                 :gensen-spouse-deduction 380000M}}]
        statement (gensen/payroll-facts->gensen-statement
                   {:facts facts
                    :person tanaka-person
                    :employer employer
                    :tax-year 2026})]
    (testing "Employment-income deduction carried through"
      (is (= 105000M (:gensen/employment-income-deduction statement))))
    (testing "Taxable-income carry-through"
      (is (= 195000M (:gensen/taxable-income statement))))
    (testing "Spouse deduction carried"
      (is (= 380000M (:gensen/spouse-deduction statement))))))

(deftest gensen-rounds-to-whole-yen
  (let [fact {:employment :emp/floaty
              :gross 100000.5M
              :net 90000.4M
              :components [{:kind :base-wage           :amount 100000.5M :employer-side? false}
                           {:kind :income-tax-withheld :amount -10000.1M :employer-side? false}]}
        statement (gensen/payroll-facts->gensen-statement
                   {:facts [fact]
                    :person tanaka-person
                    :employer employer
                    :tax-year 2026})]
    (testing "Amounts are whole-yen"
      (is (= 0 (.scale ^BigDecimal (:gensen/payment-amount statement))))
      (is (= 0 (.scale ^BigDecimal (:gensen/withholding-amount statement)))))))

;; ============================================================================
;; build-gensen-submission — multi-employee
;; ============================================================================

(deftest gensen-submission-emits-one-statement-per-person
  (let [tanaka-facts (vec (repeat 12 (monthly-fact :emp/tanaka)))
        suzuki-facts (vec (repeat 12 (monthly-fact :emp/suzuki)))
        all-facts (concat tanaka-facts suzuki-facts)
        emp->person {:emp/tanaka {:person tanaka-person
                                  :employer employer}
                     :emp/suzuki {:person {:given-name "花子"
                                           :family-name "鈴木"
                                           :address "東京都渋谷区..."
                                           :birth-date #inst "1980-03-08"
                                           :my-number-present? true}
                                  :employer employer}}
        statements (gensen/build-gensen-submission
                    {:facts all-facts
                     :tax-year 2026
                     :employer employer
                     :employment->person+employer emp->person})]
    (testing "Two statements (one per employee)"
      (is (= 2 (count statements))))
    (testing "Both statements carry the right employer corporate number"
      (is (every? #(= "8700110005901" (-> % :gensen/employer :corporate-number))
                  statements)))
    (testing "Statements carry distinct employees"
      (is (= #{"田中" "鈴木"}
             (set (map (fn [s] (-> s :gensen/employee :family-name))
                       statements)))))))

(deftest gensen-submission-requires-mandatory-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":facts required"
                        (gensen/build-gensen-submission
                         {:tax-year 2026
                          :employer employer
                          :employment->person+employer (constantly nil)})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":tax-year required"
                        (gensen/build-gensen-submission
                         {:facts []
                          :employer employer
                          :employment->person+employer (constantly nil)}))))

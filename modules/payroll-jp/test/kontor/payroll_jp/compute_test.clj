(ns kontor.payroll-jp.compute-test
  "CSV parser + compute-provider tests for the three JP file-ingest
   adapters (freee + Money Forward + Yayoi)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.compute :as compute]
            [kontor.provider.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; freee parser
;; ============================================================================

(def freee-pay-element-codes
  "Maps freee CSV `項目名` strings to kontor component-kind keywords."
  {"基本給"            :base-wage
   "通勤手当"          :commuting-allowance
   "残業手当"          :overtime
   "住宅手当"          :housing-allowance
   "健康保険料"        :employee-health-insurance
   "厚生年金保険料"    :employee-pension
   "雇用保険料"        :employee-employment-insurance
   "介護保険料"        :employee-long-term-care
   "所得税"            :income-tax-withheld
   "住民税"            :resident-tax-withheld})

(deftest parse-freee-csv-extracts-components
  (let [parsed (compute/parse-freee-csv
                (io/resource "kontor/payroll_jp/fixtures/freee_sample.csv")
                {:pay-element-codes freee-pay-element-codes})
        by-emp (group-by :employee-external-id parsed)]
    (testing "Two employees recognized"
      (is (= #{"E001" "E002"} (set (keys by-emp)))))
    (testing "Summary (集計) rows dropped"
      (is (every? #(not= :__summary (:kind %)) parsed))
      ;; The freee fixture has 8 + 8 pay-element rows + 1 summary = 17;
      ;; we expect 16 rows (the summary row is dropped).
      (is (= 16 (count parsed))))
    (testing "Earnings carry positive amounts"
      (let [base-wage (->> parsed
                           (filter #(and (= "E001" (:employee-external-id %))
                                         (= :base-wage (:kind %))))
                           first)]
        (is (= 300000M (:amount base-wage)))
        (is (false? (:employer-side? base-wage)))))
    (testing "Deductions carry NEGATIVE amounts (sign-flipped from 控除 category)"
      (let [it (->> parsed
                    (filter #(and (= "E001" (:employee-external-id %))
                                  (= :income-tax-withheld (:kind %))))
                    first)]
        (is (= -8000M (:amount it)))))
    (testing "介護保険料 only present for E002 (≥40 employee)"
      (let [kaigo (filter #(= :employee-long-term-care (:kind %)) parsed)]
        (is (= 1 (count kaigo)))
        (is (= "E002" (:employee-external-id (first kaigo))))
        (is (= -3540M (:amount (first kaigo))))))))

(deftest freee-facts-assembled
  (let [parsed (compute/parse-freee-csv
                (io/resource "kontor/payroll_jp/fixtures/freee_sample.csv")
                {:pay-element-codes freee-pay-element-codes})
        ext->eid {"E001" :emp/tanaka "E002" :emp/suzuki}
        facts (compute/freee-facts parsed
                                   {:external-id->eid ext->eid
                                    :pay-period-eid :pp-2026-05
                                    :commodity-eid :kontor.commodity/jpy})]
    (testing "One fact per employee"
      (is (= 2 (count facts))))
    (testing "Tanaka's gross / net"
      ;; E001 earnings: 300000 + 15000 + 25000 = 340000
      ;; E001 deductions: 16500 + 30500 + 2040 + 8000 + 18000 = 75040
      ;; net = 340000 - 75040 = 264960
      (let [tanaka (first (filter #(= :emp/tanaka (:employment %)) facts))]
        (is (= 340000M (:gross tanaka)))
        (is (= 264960M (:net tanaka)))))
    (testing "Suzuki's gross / net (includes 介護保険料)"
      ;; E002 earnings: 400000 + 12000 = 412000
      ;; E002 deductions: 21560 + 39750 + 3540 + 2472 + 15200 + 23000 = 105522
      ;; net = 412000 - 105522 = 306478
      (let [suzuki (first (filter #(= :emp/suzuki (:employment %)) facts))]
        (is (= 412000M (:gross suzuki)))
        (is (= 306478M (:net suzuki)))))))

(deftest freee-provider-via-protocol
  (let [provider (compute/->FreeeProvider
                  {:csv-source (io/resource "kontor/payroll_jp/fixtures/freee_sample.csv")
                   :pay-element-codes freee-pay-element-codes
                   :external-id->eid {"E001" :emp/tanaka "E002" :emp/suzuki}
                   :commodity-eid :kontor.commodity/jpy})]
    (testing "Provider id"
      (is (= :freee (pp/provider-id provider))))
    (testing "compute-payroll yields the two facts"
      (let [facts (pp/compute-payroll provider {:pay-period-eid :pp-2026-05})]
        (is (= 2 (count facts)))
        (is (every? #(contains? % :components) facts))))))

(deftest freee-rejects-unknown-pay-element
  (let [bad-codes (dissoc freee-pay-element-codes "基本給")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown JP pay-element code"
         (compute/parse-freee-csv
          (io/resource "kontor/payroll_jp/fixtures/freee_sample.csv")
          {:pay-element-codes bad-codes})))))

;; ============================================================================
;; Money Forward parser
;; ============================================================================

(def mf-pay-element-codes
  {"salary"                :base-wage
   "commuting"             :commuting-allowance
   "housing_allowance"     :housing-allowance
   "health_insurance"      :employee-health-insurance
   "pension"               :employee-pension
   "employment_insurance"  :employee-employment-insurance
   "long_term_care"        :employee-long-term-care
   "income_tax"            :income-tax-withheld
   "resident_tax"          :resident-tax-withheld})

(deftest parse-mf-csv-extracts-components
  (let [parsed (compute/parse-mf-csv
                (io/resource "kontor/payroll_jp/fixtures/mf_sample.csv")
                {:pay-element-codes mf-pay-element-codes})
        by-emp (group-by :employee-external-id parsed)]
    (testing "Two employees recognized"
      (is (= #{"EMP-100" "EMP-200"} (set (keys by-emp)))))
    (testing "Earnings positive, deductions negative"
      (let [salary (->> parsed
                        (filter #(and (= "EMP-100" (:employee-external-id %))
                                      (= :base-wage (:kind %))))
                        first)]
        (is (= 350000M (:amount salary))))
      (let [pension (->> parsed
                         (filter #(and (= "EMP-100" (:employee-external-id %))
                                       (= :employee-pension (:kind %))))
                         first)]
        (is (= -33550M (:amount pension)))))
    (testing "Long-term-care present only for EMP-200 (≥40)"
      (let [kaigo (filter #(= :employee-long-term-care (:kind %)) parsed)]
        (is (= 1 (count kaigo)))
        (is (= "EMP-200" (:employee-external-id (first kaigo))))))))

(deftest mf-provider-protocol
  (let [provider (compute/->MoneyForwardProvider
                  {:csv-source (io/resource "kontor/payroll_jp/fixtures/mf_sample.csv")
                   :pay-element-codes mf-pay-element-codes
                   :external-id->eid {"EMP-100" :emp/yamada
                                      "EMP-200" :emp/sato}
                   :commodity-eid :kontor.commodity/jpy})]
    (testing "Provider id"
      (is (= :money-forward (pp/provider-id provider))))
    (testing "Two facts produced"
      (is (= 2 (count (pp/compute-payroll provider {:pay-period-eid :pp-2026-05})))))))

(deftest mf-missing-source-throws
  (let [provider (compute/->MoneyForwardProvider {:pay-element-codes mf-pay-element-codes
                                                  :external-id->eid {}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"needs :csv-source"
                          (pp/compute-payroll provider {})))))

;; ============================================================================
;; Yayoi parser
;; ============================================================================

(deftest yayoi-parser-handles-kanji-headers
  (let [yayoi-csv (str "社員コード,氏名,支給控除項目,支給/控除,金額\n"
                       "A001,佐藤一郎,基本給,支給,280000\n"
                       "A001,佐藤一郎,健康保険料,控除,14500\n"
                       "A001,佐藤一郎,所得税,控除,7000\n")
        tmp (java.io.File/createTempFile "yayoi-test" ".csv")]
    (try
      (spit tmp yayoi-csv)
      (let [parsed (compute/parse-yayoi-csv
                    tmp
                    {:pay-element-codes {"基本給" :base-wage
                                         "健康保険料" :employee-health-insurance
                                         "所得税" :income-tax-withheld}})]
        (testing "Three rows parsed"
          (is (= 3 (count parsed))))
        (testing "Deductions sign-flipped"
          (let [it (first (filter #(= :income-tax-withheld (:kind %)) parsed))]
            (is (= -7000M (:amount it))))))
      (finally (.delete tmp)))))

;; ============================================================================
;; PCA Kyuyo API skeleton
;; ============================================================================

(deftest pca-api-skeleton-throws-with-helpful-message
  (let [provider (compute/->PcaKyuyoApiProvider {})]
    (testing "Provider id"
      (is (= :pca-kyuyo-api (pp/provider-id provider))))
    (testing "compute-payroll throws skeleton-only error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"partner-program-gated"
                            (pp/compute-payroll provider {}))))))

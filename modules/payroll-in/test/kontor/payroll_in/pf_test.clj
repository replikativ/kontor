(ns kontor.payroll-in.pf-test
  "EPFO ECR monthly builder tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.pf :as pf]))

(deftest month-bounds-returns-half-open-window
  (let [[s e] (pf/month-bounds 2026 4)]    ; April 2026
    (is (= #inst "2026-04-01" s))
    (is (= #inst "2026-05-01" e)))
  (let [[s e] (pf/month-bounds 2026 12)]    ; December rolls to Jan
    (is (= #inst "2026-12-01" s))
    (is (= #inst "2027-01-01" e))))

(deftest ecr-row-tab-delimited
  (let [row (pf/ecr-row
             {:uan "100123456789"
              :member-name "Ravi Sharma"
              :gross-wages 50000M
              :epf-wages 15000M
              :eps-wages 15000M
              :edli-wages 15000M
              :epf-contrib-ee 1800M
              :eps-contrib-er 1250M
              :epf-contrib-er 550M
              :ncp-days 0
              :refund-of-advances 0M})]
    (is (str/starts-with? row "100123456789\t"))
    (is (str/includes? row "Ravi Sharma"))
    (testing "11 tab-delimited fields"
      (is (= 11 (count (str/split row #"\t")))))))

(deftest ecr-row-defaults-ncp-days-and-refunds
  (let [row (pf/ecr-row
             {:uan "1" :member-name "X"
              :gross-wages 1M :epf-wages 1M :eps-wages 1M :edli-wages 1M
              :epf-contrib-ee 1M :eps-contrib-er 1M :epf-contrib-er 1M})]
    (is (str/ends-with? row "\t0\t0"))))

(deftest ecr-text-crlf-terminated
  (let [text (pf/ecr-text
              [{:uan "100000000001" :member-name "A"
                :gross-wages 50000M :epf-wages 15000M :eps-wages 15000M
                :edli-wages 15000M :epf-contrib-ee 1800M :eps-contrib-er 1250M
                :epf-contrib-er 550M}
               {:uan "100000000002" :member-name "B"
                :gross-wages 40000M :epf-wages 15000M :eps-wages 15000M
                :edli-wages 15000M :epf-contrib-ee 1800M :eps-contrib-er 1250M
                :epf-contrib-er 550M}])]
    (is (str/includes? text "\r\n"))
    (is (= 2 (count (str/split text #"\r\n"))))))

(deftest ecr-output-is-idempotent
  (testing "Same input → byte-identical output"
    (let [rows [{:uan "100000000001" :member-name "A"
                 :gross-wages 50000M :epf-wages 15000M :eps-wages 15000M
                 :edli-wages 15000M :epf-contrib-ee 1800M :eps-contrib-er 1250M
                 :epf-contrib-er 550M}]
          a (pf/ecr-text rows)
          b (pf/ecr-text rows)]
      (is (= a b)))))

(deftest ecr-audit-doc-has-correct-category-and-language
  (let [doc (first
             (pf/ecr-audit-doc-tx-data
              {:pf-summary {:year 2026 :month 5
                            :period-start #inst "2026-05-01"
                            :period-end #inst "2026-06-01"
                            :pf-total {:amount 12000M :commodity :INR}
                            :establishment-code-tag "EPFO-MH-BOM-12345"}
               :establishment-code "MH/BOM/12345"
               :language :en-in}))]
    (is (= :payroll-filing (:audit-doc/category doc)))
    (is (= :en-in (:audit-doc/language doc)))
    (is (re-find #"EPFO ECR" (:audit-doc/title doc)))
    (is (str/includes? (:audit-doc/code doc) "EPFO-ECR-MH/BOM/12345-2026-05"))))

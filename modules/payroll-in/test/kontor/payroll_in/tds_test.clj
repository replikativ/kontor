(ns kontor.payroll-in.tds-test
  "Form 24Q TDS — FY/quarter helpers + FVU round-trip."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.tds :as tds]))

;; ============================================================================
;; FY + quarter helpers (IN fiscal year is April-March)
;; ============================================================================

(deftest fy-of-aligns-with-april-march-fy
  (testing "April through December — current calendar year"
    (is (= 2026 (tds/fy-of #inst "2026-04-01")))
    (is (= 2026 (tds/fy-of #inst "2026-06-15")))
    (is (= 2026 (tds/fy-of #inst "2026-12-31"))))
  (testing "January through March — previous calendar year"
    (is (= 2025 (tds/fy-of #inst "2026-01-15")))
    (is (= 2025 (tds/fy-of #inst "2026-03-31")))))

(deftest quarter-of-maps-month-to-q1-q4
  (is (= 1 (tds/quarter-of #inst "2026-04-15")))
  (is (= 1 (tds/quarter-of #inst "2026-06-30")))
  (is (= 2 (tds/quarter-of #inst "2026-07-01")))
  (is (= 2 (tds/quarter-of #inst "2026-09-30")))
  (is (= 3 (tds/quarter-of #inst "2026-10-01")))
  (is (= 3 (tds/quarter-of #inst "2026-12-31")))
  (is (= 4 (tds/quarter-of #inst "2026-01-31")))
  (is (= 4 (tds/quarter-of #inst "2026-03-31"))))

(deftest quarter-bounds-correct-for-fy
  (testing "Q1 of FY 2026-27 = Apr-Jun 2026"
    (let [[s e] (tds/quarter-bounds 2026 1)]
      (is (= #inst "2026-04-01" s))
      (is (= #inst "2026-07-01" e))))
  (testing "Q4 of FY 2026-27 = Jan-Mar 2027 (year rolls over)"
    (let [[s e] (tds/quarter-bounds 2026 4)]
      (is (= #inst "2027-01-01" s))
      (is (= #inst "2027-04-01" e))))
  (testing "Q3 spans calendar year boundary (Oct - Jan)"
    (let [[s e] (tds/quarter-bounds 2026 3)]
      (is (= #inst "2026-10-01" s))
      (is (= #inst "2027-01-01" e)))))

;; ============================================================================
;; FVU emitter — file header / batch header / challan / deductee rows
;; ============================================================================

(deftest file-header-pipe-delimited
  (let [row (tds/file-header-row
             {:record-count 4
              :file-creation-date #inst "2026-07-10"
              :rpu-version "4.7"
              :fvu-version "8.2"
              :sam-version "1.0"})]
    (is (str/starts-with? row "1|"))
    (is (str/includes? row "10072026"))      ; DDMMYYYY
    (is (str/includes? row "4.7"))
    (is (str/includes? row "8.2"))))

(deftest batch-header-includes-tan-and-quarter
  (let [row (tds/batch-header-row
             {:batch-number 1
              :challan-count 1
              :deductee-count 3
              :form-no "24Q"
              :tan "BLRA12345E"
              :pan "AAAPL1234C"
              :fy 2026
              :quarter 1
              :statement-type "O"
              :deductor-name "Acme India Pvt Ltd"
              :deductor-address1 "Block A, Bangalore"
              :deductor-pin "560001"
              :deductor-state "IN-KA"
              :responsible-person {:name "K. Iyer"
                                   :pan "BBBPL5678D"
                                   :designation "Director"
                                   :address "Block A"
                                   :pin "560001"
                                   :state "IN-KA"}})]
    (is (str/starts-with? row "2|"))
    (is (str/includes? row "BLRA12345E"))
    (is (str/includes? row "AAAPL1234C"))
    (is (str/includes? row "24Q"))
    (is (str/includes? row "Q1"))
    (is (str/includes? row "Acme India"))
    (is (str/includes? row "K. Iyer"))))

(deftest challan-row-computes-total-when-omitted
  (let [row (tds/challan-row
             {:seq-no 1
              :total-tds 50000M
              :total-interest 0M
              :total-fee 0M
              :total-other 0M
              :challan-date #inst "2026-05-07"
              :bsr-code "0000123"
              :challan-serial "00001"
              :deductee-count 5})]
    (is (str/starts-with? row "3|"))
    (is (str/includes? row "0000123"))
    (is (str/includes? row "92A"))           ; default section
    (is (str/includes? row "07052026"))))

(deftest deductee-row-marks-no-pan-with-remark-b
  (let [r1 (tds/deductee-row
            {:seq-no 1
             :challan-seq 1
             :pan "AAAPA1234A"
             :name "Ravi Sharma"
             :amount-paid 75000M
             :tax-deducted 4000M
             :tds-deposit-date #inst "2026-05-07"})
        r2 (tds/deductee-row
            {:seq-no 2
             :challan-seq 1
             :pan "PANNOTAVBL"
             :name "Anita Reddy"
             :amount-paid 50000M
             :tax-deducted 10000M       ; 20% rate kicks in
             :tds-deposit-date #inst "2026-05-07"
             :remarks "B"})]
    (is (str/starts-with? r1 "4|"))
    (is (str/includes? r1 "Ravi Sharma"))
    (is (str/ends-with? r2 "|92A|B"))))

(deftest form-24q-fvu-round-trip-byte-identical
  (testing "Re-rendering same input → byte-identical output (idempotency)"
    (let [input {:file-header {:record-count 4
                               :file-creation-date #inst "2026-07-10"
                               :rpu-version "4.7"
                               :fvu-version "8.2"
                               :sam-version "1.0"}
                 :batch-header {:batch-number 1
                                :challan-count 1
                                :deductee-count 1
                                :tan "BLRA12345E"
                                :pan "AAAPL1234C"
                                :fy 2026 :quarter 1
                                :deductor-name "Acme"
                                :deductor-address1 "Addr"
                                :deductor-pin "560001"
                                :deductor-state "IN-KA"
                                :responsible-person {:name "RP" :pan "XX"
                                                     :designation "Dir"
                                                     :address "A"
                                                     :pin "560001"
                                                     :state "IN-KA"}}
                 :challans [{:seq-no 1
                             :total-tds 4000M :total-interest 0M
                             :total-fee 0M :total-other 0M
                             :challan-date #inst "2026-05-07"
                             :bsr-code "0000123" :challan-serial "00001"
                             :deductees [{:seq-no 1 :pan "AAAPA1234A"
                                          :name "R"
                                          :amount-paid 75000M
                                          :tax-deducted 4000M
                                          :tds-deposit-date #inst "2026-05-07"}]}]}
          a (tds/form-24q-fvu input)
          b (tds/form-24q-fvu input)]
      (is (= a b))
      (testing "Output has 4 records (file + batch + challan + deductee)"
        (is (= 4 (count (str/split a #"\r\n")))))
      (testing "Records are pipe-delimited"
        (doseq [line (str/split a #"\r\n")]
          (is (str/includes? line "|")))))))

;; ============================================================================
;; tds-audit-doc-tx-data — :audit-doc category + language
;; ============================================================================

(deftest tds-audit-doc-carries-payroll-filing-category-and-en-in
  (let [doc (first
             (tds/tds-audit-doc-tx-data
              {:tds-summary {:fy 2026 :quarter 1
                             :period-start #inst "2026-04-01"
                             :period-end #inst "2026-07-01"
                             :tds {:amount 4000M :commodity :INR}
                             :tan-account-tag "TAN-BLRA12345E"}}))]
    (is (= :payroll-filing (:audit-doc/category doc)))
    (is (= :en-in (:audit-doc/language doc)))
    (is (re-find #"Form 24Q" (:audit-doc/title doc)))
    (is (str/includes? (:audit-doc/code doc) "FORM-24Q-TAN-BLRA12345E-2026-Q1"))))

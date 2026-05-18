(ns kontor.payroll-in.esi-test
  "ESIC monthly contribution helper tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kontor.payroll-in.esi :as esi]))

(deftest month-bounds-mirror-pf-shape
  (let [[s e] (esi/month-bounds 2026 5)]
    (is (= #inst "2026-05-01" s))
    (is (= #inst "2026-06-01" e))))

(deftest esic-row-defaults-reason-code-to-zero
  (let [row (esi/esic-row
             {:ip-number "1234567890"
              :ip-name "Ravi Kumar"
              :days-paid 30
              :monthly-wages 20000M})]
    (is (str/includes? row "1234567890,Ravi Kumar,30,20000,0,"))))

(deftest esic-row-handles-comma-in-name
  (let [row (esi/esic-row
             {:ip-number "9999999999"
              :ip-name "Last, First"
              :days-paid 30
              :monthly-wages 18000M})]
    (is (str/includes? row "\"Last, First\""))))

(deftest esic-csv-includes-header-by-default
  (let [csv (esi/esic-csv
             [{:ip-number "1" :ip-name "A" :days-paid 30 :monthly-wages 10000M}])]
    (is (str/starts-with? csv "IP Number,IP Name"))))

(deftest esic-csv-omits-header-when-asked
  (let [csv (esi/esic-csv
             [{:ip-number "1" :ip-name "A" :days-paid 30 :monthly-wages 10000M}]
             {:include-header? false})]
    (is (not (str/starts-with? csv "IP Number")))
    (is (str/starts-with? csv "1,A,"))))

(deftest esic-csv-is-idempotent
  (let [rows [{:ip-number "1" :ip-name "A" :days-paid 30 :monthly-wages 10000M}
              {:ip-number "2" :ip-name "B" :days-paid 30 :monthly-wages 15000M}]
        a (esi/esic-csv rows)
        b (esi/esic-csv rows)]
    (is (= a b))))

(deftest separation-row-renders-last-working-day
  (let [row (esi/esic-row
             {:ip-number "1234567890"
              :ip-name "Sita Devi"
              :days-paid 15
              :monthly-wages 9000M
              :reason-code "1"
              :last-working-day #inst "2026-05-15"})]
    (is (str/includes? row "15/05/2026"))
    (is (str/includes? row ",1,"))))

(deftest esi-audit-doc-carries-correct-shape
  (let [doc (first
             (esi/esi-audit-doc-tx-data
              {:esi-summary {:year 2026 :month 5
                             :period-start #inst "2026-05-01"
                             :period-end #inst "2026-06-01"
                             :esi-total {:amount 800M :commodity :INR}
                             :ip-code-tag "ESIC-12345678"}
               :esic-code "12345678"
               :language :en-in}))]
    (is (= :payroll-filing (:audit-doc/category doc)))
    (is (= :en-in (:audit-doc/language doc)))
    (is (re-find #"ESIC monthly return" (:audit-doc/title doc)))
    (is (str/includes? (:audit-doc/code doc) "ESIC-12345678-2026-05"))))

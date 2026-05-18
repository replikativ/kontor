(ns kontor.payroll-au.super-test
  "Stage R C6 — SuperStream contribution-message helper tests (ADR-080).

   Covers:
     - contribution-line per-employee + per-fund builder.
     - contribution-message-payload aggregate envelope.
     - superstream-audit-doc-tx-data (ADR-068 builder)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-au.super :as super]))

(def alice
  {:given-name "Alice"
   :family-name "Outback"
   :tfn "123456782"
   :member-number "M-1001"
   :date-of-birth #inst "1985-03-12"})

(def hesta-fund
  {:usi "HST0100AU"
   :abn "64971749321"
   :name "HESTA Super Fund"})

(deftest contribution-line-builds-balanced-line
  (let [line (super/contribution-line
              {:member alice
               :fund hesta-fund
               :sg-amount 747.50M
               :salary-sacrifice 650M
               :pay-period-start #inst "2026-05-01"
               :pay-period-end #inst "2026-05-31"})]
    (testing "core fields are present"
      (is (= "123456782" (:super.line/member-tfn line)))
      (is (= "HST0100AU"  (:super.line/fund-usi line)))
      (is (= "747.50"     (:super.line/sg-amount line)))
      (is (= "650.00"     (:super.line/salary-sacrifice line)))
      (is (= "0.00"       (:super.line/member-voluntary line))))
    (testing "total = SG + member-voluntary + salary-sacrifice"
      (is (= "1397.50" (:super.line/total line))))))

(deftest contribution-line-rejects-bad-usi
  (testing "garbage USI throws (structural envelope check)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"USI"
                          (super/contribution-line
                           {:member alice
                            :fund (assoc hesta-fund :usi "")
                            :sg-amount 100M
                            :pay-period-start #inst "2026-05-01"
                            :pay-period-end #inst "2026-05-31"})))))

(deftest contribution-message-payload-assembly
  (let [line (super/contribution-line
              {:member alice
               :fund hesta-fund
               :sg-amount 747.50M
               :pay-period-start #inst "2026-05-01"
               :pay-period-end #inst "2026-05-31"})
        payload (super/contribution-message-payload
                 {:abn "33051775556"
                  :usi "ATO0001AU"
                  :pay-period-start #inst "2026-05-01"
                  :pay-period-end #inst "2026-05-31"
                  :submission-date #inst "2026-06-15"
                  :lines [line]
                  :total-amount 747.50M
                  :clearing-house-name "Small Business Super Clearing House"})]
    (testing "envelope carries the employer + clearing-house identity"
      (is (= "33051775556" (:super.message/abn payload)))
      (is (= "ATO0001AU"    (:super.message/usi payload)))
      (is (= "Small Business Super Clearing House"
             (:super.message/clearing-house payload))))
    (testing "line-count + total-amount derived"
      (is (= 1 (:super.message/line-count payload)))
      (is (= "747.50" (:super.message/total-amount payload))))))

(deftest contribution-message-rejects-empty-lines
  (testing "empty :lines throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":lines"
                          (super/contribution-message-payload
                           {:abn "33051775556" :usi "ATO0001AU"
                            :pay-period-start #inst "2026-05-01"
                            :pay-period-end #inst "2026-05-31"
                            :submission-date #inst "2026-06-15"
                            :lines [] :total-amount 0M})))))

(deftest superstream-audit-doc-tx-data-builder
  (let [line (super/contribution-line
              {:member alice
               :fund hesta-fund
               :sg-amount 747.50M
               :pay-period-start #inst "2026-05-01"
               :pay-period-end #inst "2026-05-31"})
        payload (super/contribution-message-payload
                 {:abn "33051775556"
                  :usi "ATO0001AU"
                  :pay-period-start #inst "2026-05-01"
                  :pay-period-end #inst "2026-05-31"
                  :submission-date #inst "2026-06-15"
                  :lines [line]
                  :total-amount 747.50M})
        tx (super/superstream-audit-doc-tx-data
            {:payload payload
             :storage-uri "s3://kontor-audit/superstream/2026-05.xml"})
        doc (first tx)]
    (testing "audit-doc carries the right category + language"
      (is (= :payroll-filing (:audit-doc/category doc)))
      (is (= :en             (:audit-doc/language doc))))
    (testing "title + description summarize the message"
      (is (re-find #"SuperStream" (:audit-doc/title doc)))
      (is (re-find #"747\.50"      (:audit-doc/description doc)))
      (is (re-find #"1 line"        (:audit-doc/description doc))))
    (testing "storage-uri attaches when supplied"
      (is (= "s3://kontor-audit/superstream/2026-05.xml"
             (:audit-doc/storage-uri doc))))
    (testing "code is deterministic from ABN + period-end"
      (is (re-find #"^SUPER-33051775556-" (:audit-doc/code doc))))))

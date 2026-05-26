(ns kontor.payroll-ca.pd7a-test
  "Tests for the PD7A remittance helper — three-bucket totals + due
   date computation per remitter type."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.chart :as ca-chart]
            [kontor.money :as money]
            [kontor.payroll-ca.chart :as pca-chart]
            [kontor.payroll-ca.pd7a :as pd7a]
            [kontor.posting :as posting]))

;; ============================================================================
;; Due-date computation
;; ============================================================================

(deftest remitter-types-table-roundtrip
  (testing "All four CRA remitter types known"
    (is (= #{:quarterly :regular :accel-t1 :accel-t2}
           (pd7a/remitter-types)))))

(deftest regular-remitter-due-15th-of-next-month
  (let [period-end #inst "2026-05-31T00:00:00.000-00:00"
        due (pd7a/next-due-date :regular period-end)
        ld (-> due .toInstant
               (.atZone java.time.ZoneOffset/UTC)
               .toLocalDate)]
    (testing "Due date is 15th of June"
      (is (= 6 (.getMonthValue ld)))
      (is (= 15 (.getDayOfMonth ld))))))

(deftest accel-t1-mid-month-rule
  (let [pay-day-10 #inst "2026-05-10T00:00:00.000-00:00"
        pay-day-20 #inst "2026-05-20T00:00:00.000-00:00"
        due-10 (pd7a/next-due-date :accel-t1 pay-day-10)
        due-20 (pd7a/next-due-date :accel-t1 pay-day-20)
        ld-10 (-> due-10 .toInstant
                  (.atZone java.time.ZoneOffset/UTC)
                  .toLocalDate)
        ld-20 (-> due-20 .toInstant
                  (.atZone java.time.ZoneOffset/UTC)
                  .toLocalDate)]
    (testing "Paydays 1-15: due 25th same month"
      (is (= 5 (.getMonthValue ld-10)))
      (is (= 25 (.getDayOfMonth ld-10))))
    (testing "Paydays 16-end: due 10th of NEXT month"
      (is (= 6 (.getMonthValue ld-20)))
      (is (= 10 (.getDayOfMonth ld-20))))))

(deftest unknown-remitter-type-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown remitter type"
                        (pd7a/next-due-date :foobar #inst "2026-05-31"))))

;; ============================================================================
;; sum-postings-by-tag + pd7a-period-due — round-trip against datahike
;; ============================================================================

(defn- bootstrap-db []
  (let [conn (core/create-test-db)]
    ;; Install base CA chart + payroll extension
    (ca-chart/install! conn)
    (pca-chart/install! conn)
    ;; Add journal + period
    (d/transact conn
                [{:db/id "journal-pay"
                  :journal/code "PAY-CA"
                  :journal/name "Payroll (CA)"
                  :journal/type :general}
                 {:period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- post-payroll-tx!
  "Helper: transact a balanced 'CRA-payable' fragment via build-transaction."
  [conn {:keys [effective-date itx-amount cpp-amount ei-amount rp-tag]}]
  (let [db (d/db conn)
        wages (d/q '[:find ?e . :where [?e :kontor.account/code "5400"]] db)
        itx (d/q '[:find ?e . :where [?e :kontor.account/code "2510"]] db)
        cpp (d/q '[:find ?e . :where [?e :kontor.account/code "2520"]] db)
        ei  (d/q '[:find ?e . :where [?e :kontor.account/code "2530"]] db)
        net (d/q '[:find ?e . :where [?e :kontor.account/code "2550"]] db)
        journal (d/q '[:find ?e . :where [?e :journal/code "PAY-CA"]] db)
        cad (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "CAD"]] db)
        gross (.add ^java.math.BigDecimal itx-amount
                    (.add ^java.math.BigDecimal cpp-amount
                          ^java.math.BigDecimal ei-amount))
        gross (.add ^java.math.BigDecimal gross 1000M) ; +1000 net wages
        net-amt 1000M
        tag (when rp-tag [[:kontor.account-tag/name rp-tag]])
        mk-post (fn [acct amount narration]
                  (cond-> {:posting/account acct
                           :posting/amount amount
                           :posting/commodity cad
                           :posting/narration narration}
                    tag (assoc :posting/account-tags tag)))
        tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/journal journal
                   :transaction/effective-date effective-date
                   :transaction/narration "test payroll"
                   :transaction/state :draft}
                  :postings [(mk-post wages gross "wages")
                             (mk-post itx (.negate ^java.math.BigDecimal itx-amount) "itx")
                             (mk-post cpp (.negate ^java.math.BigDecimal cpp-amount) "cpp")
                             (mk-post ei  (.negate ^java.math.BigDecimal ei-amount) "ei")
                             (mk-post net (.negate ^java.math.BigDecimal net-amt) "net")]})]
    (d/transact conn tx-data)))

(deftest pd7a-totals-sum-three-buckets
  (let [conn (bootstrap-db)]
    (post-payroll-tx! conn {:effective-date #inst "2026-05-10"
                            :itx-amount 850M :cpp-amount 260.30M :ei-amount 81.50M})
    (post-payroll-tx! conn {:effective-date #inst "2026-05-25"
                            :itx-amount 900M :cpp-amount 280M :ei-amount 90M})
    (let [summary (pd7a/pd7a-period-due
                   conn {:period-start #inst "2026-05-01"
                         :period-end #inst "2026-06-01"
                         :remitter-type :regular})]
      (testing "Sums ITX correctly across pay runs"
        (is (= 1750M (:amount (:itx summary)))))
      (testing "Sums CPP correctly"
        (is (= 540.30M (:amount (:cpp summary)))))
      (testing "Sums EI correctly"
        (is (= 171.50M (:amount (:ei summary)))))
      (testing "Total = ITX + CPP + EI"
        (is (= 2461.80M (:amount (:total summary)))))
      (testing "Due date computed from remitter-type"
        (is (some? (:due-date summary)))))))

(deftest pd7a-rp-routing-filters-postings
  (let [conn (bootstrap-db)]
    ;; First, register the RP tags
    (d/transact conn [{:kontor.account-tag/name "ca-cra-rp-RP0001"
                       :kontor.account-tag/country-code "CA"
                       :kontor.account-tag/applicability :account}
                      {:kontor.account-tag/name "ca-cra-rp-RP0002"
                       :kontor.account-tag/country-code "CA"
                       :kontor.account-tag/applicability :account}])
    (post-payroll-tx! conn {:effective-date #inst "2026-05-10"
                            :itx-amount 1000M :cpp-amount 300M :ei-amount 100M
                            :rp-tag "ca-cra-rp-RP0001"})
    (post-payroll-tx! conn {:effective-date #inst "2026-05-12"
                            :itx-amount 500M :cpp-amount 150M :ei-amount 50M
                            :rp-tag "ca-cra-rp-RP0002"})
    (let [rp1 (pd7a/pd7a-period-due
               conn {:period-start #inst "2026-05-01"
                     :period-end #inst "2026-06-01"
                     :rp-account-tag "ca-cra-rp-RP0001"})
          rp2 (pd7a/pd7a-period-due
               conn {:period-start #inst "2026-05-01"
                     :period-end #inst "2026-06-01"
                     :rp-account-tag "ca-cra-rp-RP0002"})]
      (testing "RP0001 totals are isolated"
        (is (= 1000M (:amount (:itx rp1))))
        (is (= 300M  (:amount (:cpp rp1))))
        (is (= 100M  (:amount (:ei rp1))))
        (is (= 1400M (:amount (:total rp1)))))
      (testing "RP0002 totals are isolated"
        (is (= 500M (:amount (:itx rp2))))
        (is (= 150M (:amount (:cpp rp2))))
        (is (= 50M  (:amount (:ei rp2))))
        (is (= 700M (:amount (:total rp2))))))))

;; ============================================================================
;; pd7a-audit-doc-tx-data — :audit-doc tx-data builder
;; ============================================================================

(deftest audit-doc-tx-data-carries-category-and-language
  (let [summary {:itx (money/money 1750M :CAD)
                 :cpp (money/money 540.30M :CAD)
                 :ei  (money/money 171.50M :CAD)
                 :total (money/money 2461.80M :CAD)
                 :period-start #inst "2026-05-01"
                 :period-end   #inst "2026-06-01"
                 :rp-account-tag "ca-cra-rp-RP0001"
                 :remitter-type :regular
                 :due-date #inst "2026-06-15"}
        tx (pd7a/pd7a-audit-doc-tx-data
            {:pd7a-summary summary :language :fr})]
    (testing "One audit-doc row produced"
      (is (= 1 (count tx))))
    (testing "Category is :payroll-filing per note 84 §3.3"
      (is (= :payroll-filing (:audit-doc/category (first tx)))))
    (testing "Language honored"
      (is (= :fr (:audit-doc/language (first tx)))))
    (testing "Title carries RP + period"
      (is (re-find #"ca-cra-rp-RP0001" (:audit-doc/title (first tx)))))))

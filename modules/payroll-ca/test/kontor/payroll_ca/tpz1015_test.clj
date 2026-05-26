(ns kontor.payroll-ca.tpz1015-test
  "Tests for the TPZ-1015 monthly QC source-deduction remittance helper —
   the Revenu Québec parallel to PD7A. Per ADR-087 §3."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.chart :as ca-chart]
            [kontor.money :as money]
            [kontor.payroll-ca.chart :as pca-chart]
            [kontor.payroll-ca.tpz1015 :as tpz]
            [kontor.posting :as posting])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Schedule helpers
;; ============================================================================

(deftest remitter-types-table-roundtrip
  (testing "All four QC remitter types known"
    (is (= #{:annual :monthly :twice-monthly :weekly}
           (tpz/remitter-types)))))

(deftest monthly-due-15th-of-next-month
  (let [period-end #inst "2026-05-31T00:00:00.000-00:00"
        due (tpz/next-due-date :monthly period-end)
        ld (-> due .toInstant
               (.atZone java.time.ZoneOffset/UTC)
               .toLocalDate)]
    (testing "Due date is 15th of June"
      (is (= 6 (.getMonthValue ld)))
      (is (= 15 (.getDayOfMonth ld))))))

(deftest twice-monthly-split
  (let [pay-day-10 #inst "2026-05-10T00:00:00.000-00:00"
        pay-day-20 #inst "2026-05-20T00:00:00.000-00:00"
        due-10 (tpz/next-due-date :twice-monthly pay-day-10)
        due-20 (tpz/next-due-date :twice-monthly pay-day-20)
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

(deftest annual-due-jan-15-next-year
  (let [period-end #inst "2026-12-31T00:00:00.000-00:00"
        due (tpz/next-due-date :annual period-end)
        ld (-> due .toInstant
               (.atZone java.time.ZoneOffset/UTC)
               .toLocalDate)]
    (testing "Annual remitter due Jan 15 of following year"
      (is (= 2027 (.getYear ld)))
      (is (= 1 (.getMonthValue ld)))
      (is (= 15 (.getDayOfMonth ld))))))

(deftest unknown-remitter-type-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown TPZ-1015 remitter type"
                        (tpz/next-due-date :foobar #inst "2026-05-31"))))

;; ============================================================================
;; tpz1015-period-due — round-trip against datahike
;; ============================================================================

(defn- bootstrap-db []
  (let [conn (core/create-test-db)]
    (ca-chart/install! conn)
    (pca-chart/install! conn)
    (d/transact conn
                [{:db/id "journal-pay"
                  :journal/code "PAY-CA"
                  :journal/name "Payroll (CA)"
                  :journal/type :general}
                 {:period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- post-qc-payroll-tx!
  "Helper: transact a balanced QC-payable fragment.
   Uses Revenu Québec accounts (QC ITX, QPP, QPIP, FSS)."
  [conn {:keys [effective-date qc-itx qpp qpip fss rp-tag]}]
  (let [db (d/db conn)
        wages (d/q '[:find ?e . :where [?e :kontor.account/code "5400"]] db)
        qc-itx-acc (d/q '[:find ?e . :where [?e :kontor.account/code "2511"]] db)
        qpp-acc    (d/q '[:find ?e . :where [?e :kontor.account/code "2521"]] db)
        qpip-acc   (d/q '[:find ?e . :where [?e :kontor.account/code "2531"]] db)
        fss-acc    (d/q '[:find ?e . :where [?e :kontor.account/code "2532"]] db)
        net-acc    (d/q '[:find ?e . :where [?e :kontor.account/code "2550"]] db)
        journal (d/q '[:find ?e . :where [?e :journal/code "PAY-CA"]] db)
        cad (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "CAD"]] db)
        gross (.add ^BigDecimal qc-itx
                    (.add ^BigDecimal qpp
                          (.add ^BigDecimal qpip ^BigDecimal fss)))
        gross (.add ^BigDecimal gross 1000M)
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
                   :transaction/narration "test QC payroll"
                   :transaction/state :draft}
                  :postings [(mk-post wages gross "wages")
                             (mk-post qc-itx-acc (.negate ^BigDecimal qc-itx) "qc-itx")
                             (mk-post qpp-acc    (.negate ^BigDecimal qpp) "qpp")
                             (mk-post qpip-acc   (.negate ^BigDecimal qpip) "qpip")
                             (mk-post fss-acc    (.negate ^BigDecimal fss) "fss")
                             (mk-post net-acc    (.negate ^BigDecimal net-amt) "net")]})]
    (d/transact conn tx-data)))

(deftest tpz1015-totals-four-buckets
  (let [conn (bootstrap-db)]
    (post-qc-payroll-tx! conn {:effective-date #inst "2026-05-10"
                               :qc-itx 260M :qpp 353M :qpip 53M :fss 215M})
    (post-qc-payroll-tx! conn {:effective-date #inst "2026-05-25"
                               :qc-itx 280M :qpp 360M :qpip 55M :fss 220M})
    (let [summary (tpz/tpz1015-period-due
                   conn {:period-start #inst "2026-05-01"
                         :period-end #inst "2026-06-01"
                         :remitter-type :monthly})]
      (testing "Sums QC ITX across pay runs"
        (is (= 540M (:amount (:qc-itx summary)))))
      (testing "Sums QPP correctly"
        (is (= 713M (:amount (:qpp summary)))))
      (testing "Sums QPIP correctly"
        (is (= 108M (:amount (:qpip summary)))))
      (testing "Sums FSS correctly"
        (is (= 435M (:amount (:fss summary)))))
      (testing "Total = QC-ITX + QPP + QPIP + FSS"
        (is (= 1796M (:amount (:total summary)))))
      (testing "Due date computed from remitter-type"
        (is (some? (:due-date summary)))))))

(deftest tpz1015-rp-routing-filters
  (let [conn (bootstrap-db)]
    (d/transact conn [{:kontor.account-tag/name "qc-rq-employer-A"
                       :kontor.account-tag/country-code "CA"
                       :kontor.account-tag/applicability :account}
                      {:kontor.account-tag/name "qc-rq-employer-B"
                       :kontor.account-tag/country-code "CA"
                       :kontor.account-tag/applicability :account}])
    (post-qc-payroll-tx! conn {:effective-date #inst "2026-05-10"
                               :qc-itx 1000M :qpp 300M :qpip 100M :fss 200M
                               :rp-tag "qc-rq-employer-A"})
    (post-qc-payroll-tx! conn {:effective-date #inst "2026-05-12"
                               :qc-itx 500M :qpp 150M :qpip 50M :fss 100M
                               :rp-tag "qc-rq-employer-B"})
    (let [a (tpz/tpz1015-period-due
             conn {:period-start #inst "2026-05-01"
                   :period-end #inst "2026-06-01"
                   :rp-account-tag "qc-rq-employer-A"})
          b (tpz/tpz1015-period-due
             conn {:period-start #inst "2026-05-01"
                   :period-end #inst "2026-06-01"
                   :rp-account-tag "qc-rq-employer-B"})]
      (testing "Employer A totals isolated"
        (is (= 1000M (:amount (:qc-itx a))))
        (is (= 300M  (:amount (:qpp a))))
        (is (= 100M  (:amount (:qpip a))))
        (is (= 200M  (:amount (:fss a))))
        (is (= 1600M (:amount (:total a)))))
      (testing "Employer B totals isolated"
        (is (= 500M (:amount (:qc-itx b))))
        (is (= 150M (:amount (:qpp b))))
        (is (= 50M  (:amount (:qpip b))))
        (is (= 100M (:amount (:fss b))))
        (is (= 800M (:amount (:total b))))))))

;; ============================================================================
;; tpz1015-audit-doc-tx-data
;; ============================================================================

(deftest audit-doc-defaults-language-fr
  (let [summary {:qc-itx (money/money 540M :CAD)
                 :qpp (money/money 713M :CAD)
                 :qpip (money/money 108M :CAD)
                 :fss (money/money 435M :CAD)
                 :total (money/money 1796M :CAD)
                 :period-start #inst "2026-05-01"
                 :period-end   #inst "2026-06-01"
                 :rp-account-tag "qc-rq-employer-A"
                 :remitter-type :monthly
                 :due-date #inst "2026-06-15"}
        tx (tpz/tpz1015-audit-doc-tx-data {:tpz1015-summary summary})]
    (testing "One audit-doc row produced"
      (is (= 1 (count tx))))
    (testing "Category is :payroll-filing"
      (is (= :payroll-filing (:audit-doc/category (first tx)))))
    (testing "Language defaults to :fr (Revenu Québec convention)"
      (is (= :fr (:audit-doc/language (first tx)))))
    (testing "Title carries RP + period"
      (is (re-find #"qc-rq-employer-A" (:audit-doc/title (first tx)))))
    (testing "Description carries all four buckets + total"
      (let [desc (:audit-doc/description (first tx))]
        (is (re-find #"QC-ITX" desc))
        (is (re-find #"QPP" desc))
        (is (re-find #"QPIP" desc))
        (is (re-find #"FSS" desc))))))

(deftest audit-doc-honors-language-override
  (let [summary {:qc-itx (money/money 540M :CAD)
                 :qpp (money/money 713M :CAD)
                 :qpip (money/money 108M :CAD)
                 :fss (money/money 435M :CAD)
                 :total (money/money 1796M :CAD)
                 :period-start #inst "2026-05-01"
                 :period-end   #inst "2026-06-01"}
        tx (tpz/tpz1015-audit-doc-tx-data
            {:tpz1015-summary summary :language :en})]
    (testing "Language override honored"
      (is (= :en (:audit-doc/language (first tx)))))))

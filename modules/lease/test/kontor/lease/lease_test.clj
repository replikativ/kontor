(ns kontor.lease.lease-test
  "ADR-062: kontor-lease — the :lease contract + lifecycle + the
   short-term / low-value exemption path.

   Covers:
   - define-lease! records a :lease at :draft, stamps :kontor.audit/create-uid,
     validates :payment-frequency / :payment-timing.
   - register-exempt-lease! creates a :kontor.schedule/kind :lease-expense
     with no :lease entity; exempt-lease-period-amount straight-lines
     (last period absorbs the remainder); plan-exempt-lease-charge
     builds a balanced sealed posting."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.asset.schema :as asset-schema]
            [kontor.core :as core]
            [kontor.lease.core :as lease]
            [kontor.lease.schema :as lease-schema]
            [kontor.posting :as kposting]
            [kontor.workflow.schedule :as schedule]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (asset-schema/install! conn)
    (lease-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "U-cfo"    :kontor.partner/name "CFO"}
                 {:kontor.partner/external-id "L-acme"   :kontor.partner/name "Acme Properties"}
                 {:db/id "class-rou"
                  :kontor.asset-class/code "rou-property"
                  :kontor.asset-class/name "Right-of-Use — Property"}
                 {:db/id "doc-lease"
                  :kontor.audit-doc/code "LEASE-CONTRACT-1"
                  :kontor.audit-doc/type :lease-contract
                  :kontor.audit-doc/storage-uri "s3://docs/lease-1"
                  :kontor.audit-doc/uploaded-at #inst "2026-01-01"}
                 {:db/id "acct-lease-exp" :kontor.account/code "6740"
                  :kontor.account/name "Short-term Lease Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-cash" :kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "journal-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- commodity [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- p   [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct [db code] (ref-eid db :kontor.account/code code))
(defn- journal [db] (ref-eid db :kontor.journal/code "GEN"))
(defn- class-eid [db] (ref-eid db :kontor.asset-class/code "rou-property"))
(defn- adoc [db] (ref-eid db :kontor.audit-doc/code "LEASE-CONTRACT-1"))

;; ============================================================================
;; define-lease!
;; ============================================================================

(deftest define-lease-records-a-draft-lease
  (let [conn (bootstrap)
        db (d/db conn)
        _ (lease/define-lease! conn
            {:code "LSE-1" :name "Berlin office"
             :lessor (p db "L-acme")
             :asset-class (class-eid db)
             :commencement-date #inst "2026-02-01"
             :term-months 60
             :payment-amount 5000.00M
             :payment-frequency :monthly
             :payment-timing :in-arrears
             :commodity (commodity db)
             :discount-rate 0.05M
             :origin-document (adoc db)
             :changed-by-uid (p db "U-cfo")})
        l (lease/pull-lease (d/db conn) "LSE-1")]
    (testing "the lease is recorded at :draft"
      (is (= :draft (:kontor.lease/status l)))
      (is (= 60 (:kontor.lease/term-months l)))
      (is (= 0.05M (:kontor.lease/discount-rate l)))
      (is (= :in-arrears (:kontor.lease/payment-timing l))))
    (testing ":kontor.audit/create-uid is stamped to the recording actor"
      (is (= (p (d/db conn) "U-cfo")
             (:db/id (:kontor.audit/create-uid (d/pull (d/db conn) [:kontor.audit/create-uid]
                                                       (lease/by-code (d/db conn) "LSE-1")))))))
    (testing "a status-history row records nil → :draft"
      (is (= 1 (count (d/q '[:find [?h ...]
                             :in $ ?e
                             :where
                             [?h :kontor.status-history/entity ?e]
                             [?h :kontor.status-history/facet :kontor.lease/status]]
                           (d/db conn) (lease/by-code (d/db conn) "LSE-1"))))))))

(deftest define-lease-validates-enums
  (let [conn (bootstrap)
        db (d/db conn)
        base {:code "LSE-BAD" :name "x" :lessor (p db "L-acme")
              :asset-class (class-eid db) :commencement-date #inst "2026-02-01"
              :term-months 12 :payment-amount 100M :payment-timing :in-arrears
              :commodity (commodity db) :discount-rate 0.05M
              :changed-by-uid "lease-admin"}]   ; ADR-153
    (testing "bad :payment-frequency is rejected"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":payment-frequency must be"
           (lease/define-lease! conn (assoc base :payment-frequency :weekly)))))
    (testing "bad :payment-timing is rejected"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":payment-timing must be"
           (lease/define-lease! conn (assoc base :payment-frequency :monthly
                                            :payment-timing :whenever)))))))

;; ============================================================================
;; The exemption path
;; ============================================================================

(deftest register-exempt-lease-is-a-plain-schedule
  (let [conn (bootstrap)
        db (d/db conn)
        ;; A 9-month short-term lease, €900 total → €100/month.
        _ (lease/register-exempt-lease! conn
                                        {:code "EXEMPT-1" :name "Short-term storage"
                                         :total-payments 900.00M
                                         :commodity (commodity db)
                                         :start-date #inst "2026-03-01"
                                         :term-months 9})
        sched (schedule/by-code (d/db conn) "EXEMPT-1")]
    (testing "a :kontor.schedule/kind :lease-expense is created — no :lease entity"
      (is (some? sched))
      (is (= :lease-expense (:kontor.schedule/kind
                             (d/pull (d/db conn) [:kontor.schedule/kind] sched))))
      (is (nil? (lease/by-code (d/db conn) "EXEMPT-1"))
          "the exemption path creates no :lease"))
    (testing "exempt-lease-period-amount straight-lines, last period absorbs the remainder"
      (is (= 100.00M (lease/exempt-lease-period-amount (d/db conn) sched 1)))
      (is (= 100.00M (lease/exempt-lease-period-amount (d/db conn) sched 5)))
      (is (= 100.00M (lease/exempt-lease-period-amount (d/db conn) sched 9))))
    (testing "plan-exempt-lease-charge builds a balanced sealed posting"
      (let [tx-data (lease/plan-exempt-lease-charge
                     {:amount (lease/exempt-lease-period-amount (d/db conn) sched 1)
                      :commodity (commodity (d/db conn))
                      :journal (journal (d/db conn))
                      :date #inst "2026-03-01"
                      :lease-expense-account (acct (d/db conn) "6740")
                      :credit-account (acct (d/db conn) "1800")})
            tx (first (filter :kontor.transaction/journal tx-data))
            postings (filter :kontor.posting/account tx-data)]
        (is (:ok? (kposting/validate {:transaction tx :postings postings})))
        (is (= #{100.00M -100.00M} (set (map :kontor.posting/amount postings))))
        (is (every? #(some? (:kontor.posting/posted-at %)) postings) "sealed")))))

(deftest exempt-lease-remainder-lands-on-the-last-period
  (let [conn (bootstrap)
        db (d/db conn)
        ;; €1000 over 7 months → 1000/7 = 142.857… → 142.86 ×6, last = 142.84.
        _ (lease/register-exempt-lease! conn
                                        {:code "EXEMPT-2" :total-payments 1000.00M
                                         :commodity (commodity db)
                                         :start-date #inst "2026-03-01"
                                         :term-months 7})
        sched (schedule/by-code (d/db conn) "EXEMPT-2")
        amts (mapv #(lease/exempt-lease-period-amount (d/db conn) sched %)
                   (range 1 8))]
    (testing "the 7 period amounts sum bit-exact to the total"
      (is (= 1000.00M (reduce + 0M amts)))
      (is (= 142.86M (first amts)))
      (is (not= (last amts) (first amts)) "the last period carries the remainder"))))

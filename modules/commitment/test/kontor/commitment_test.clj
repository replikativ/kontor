(ns kontor.commitment-test
  "Stage 4 of research note 99 — the kontor-commitment companion
   (ADR-098). Acceptance: record an open receivable; sell then
   receive-payment through the verb facade; fulfill links the payment;
   open-commitments closes it out; aging buckets a still-open one;
   the tx-time axis stays consistent."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.commitment :as commitment]
            [kontor.core :as core]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh []
  (let [conn (core/create-test-db)]
    (commitment/install! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "CASH" :kontor.journal/type :cash}
                 {:kontor.partner/external-id "CUST" :kontor.partner/name "A Customer"}
                 {:kontor.partner/external-id "U-alice" :kontor.partner/name "Alice (actor)"}
                 {:kontor.account/path "Assets:Receivable" :kontor.account/type :asset}
                 {:kontor.account/path "Assets:Cash"       :kontor.account/type :asset}
                 {:kontor.account/path "Income:Sales"      :kontor.account/type :income}])
    conn))

(def ^:private eur   [:kontor.commodity/symbol "EUR"])
(def ^:private cust  [:kontor.partner/external-id "CUST"])
(def ^:private alice [:kontor.partner/external-id "U-alice"])

(defn- tx-by-xid [conn xid]
  (d/q '[:find ?t . :in $ ?x :where [?t :kontor.transaction/external-id ?x]]
       (d/db conn) xid))

;; ============================================================================
;; Record → sell → receive-payment → fulfill → closed
;; ============================================================================

(deftest full-receivable-lifecycle
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-1" :kind :receivable :counterparty cust
           :committed-amount 1000 :commodity eur
           :due-date #inst "2026-04-01" :recorded-by-uid alice})
    (testing "the commitment opens"
      (let [opens (commitment/open-commitments (d/db conn))]
        (is (= 1 (count opens)))
        (is (= :open (:kontor.commitment/state (first opens))))
        (is (== 1000M (commitment/outstanding (d/db conn) "C-1")))))
    ;; the obligation hits the GL as a sale on account
    (book/sell! conn {:debit-account [:kontor.account/path "Assets:Receivable"]
                      :credit-account [:kontor.account/path "Income:Sales"]
                      :amount 1000 :commodity eur
                      :effective-date #inst "2026-03-01" :external-id "INV-1"})
    ;; the customer pays
    (book/receive-payment! conn {:debit-account [:kontor.account/path "Assets:Cash"]
                                 :credit-account [:kontor.account/path "Assets:Receivable"]
                                 :amount 1000 :commodity eur
                                 :effective-date #inst "2026-03-20"
                                 :external-id "PAY-1"})
    (testing "fulfilling links the settling transaction and closes the commitment"
      (commitment/fulfill! conn {:commitment "C-1"
                                 :transaction (tx-by-xid conn "PAY-1")
                                 :amount 1000 :recorded-by-uid alice})
      (is (empty? (commitment/open-commitments (d/db conn)))
          "fully fulfilled → no longer open")
      (is (= :fulfilled (:kontor.commitment/state (commitment/pull-commitment (d/db conn) "C-1"))))
      (is (== 0M (commitment/outstanding (d/db conn) "C-1"))))
    (testing "the fulfillment edge points at the settling transaction"
      (let [edge (d/q '[:find (pull ?f [*]) .
                        :where [?f :kontor.commitment-fulfillment/commitment _]]
                      (d/db conn))]
        (is (= (tx-by-xid conn "PAY-1")
               (get-in edge [:kontor.commitment-fulfillment/transaction :db/id])))
        (is (== 1000M (:kontor.commitment-fulfillment/amount edge)))))))

;; ============================================================================
;; Partial fulfillment
;; ============================================================================

(deftest partial-then-complete
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-2" :kind :payable :counterparty cust
           :committed-amount 1000 :commodity eur
           :due-date #inst "2026-04-01" :recorded-by-uid alice})
    (book/pay-bill! conn {:debit-account [:kontor.account/path "Assets:Receivable"]
                          :credit-account [:kontor.account/path "Assets:Cash"]
                          :amount 600 :commodity eur
                          :effective-date #inst "2026-03-10" :external-id "P-A"})
    (commitment/fulfill! conn {:commitment "C-2" :transaction (tx-by-xid conn "P-A")
                               :amount 600 :recorded-by-uid alice})
    (testing "a partial fulfillment leaves the commitment open"
      (is (= :partially-fulfilled
             (:kontor.commitment/state (commitment/pull-commitment (d/db conn) "C-2"))))
      (is (== 400M (commitment/outstanding (d/db conn) "C-2")))
      (is (= 1 (count (commitment/open-commitments (d/db conn))))))
    (book/pay-bill! conn {:debit-account [:kontor.account/path "Assets:Receivable"]
                          :credit-account [:kontor.account/path "Assets:Cash"]
                          :amount 400 :commodity eur
                          :effective-date #inst "2026-03-25" :external-id "P-B"})
    (commitment/fulfill! conn {:commitment "C-2" :transaction (tx-by-xid conn "P-B")
                               :amount 400 :recorded-by-uid alice})
    (testing "the completing fulfillment closes it"
      (is (= :fulfilled
             (:kontor.commitment/state (commitment/pull-commitment (d/db conn) "C-2"))))
      (is (empty? (commitment/open-commitments (d/db conn)))))))

(deftest cannot-fulfill-a-closed-commitment
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-3" :kind :receivable :counterparty cust
           :committed-amount 100 :commodity eur
           :due-date #inst "2026-04-01" :recorded-by-uid alice})
    (book/receive-payment! conn {:debit-account [:kontor.account/path "Assets:Cash"]
                                 :credit-account [:kontor.account/path "Assets:Receivable"]
                                 :amount 100 :commodity eur
                                 :effective-date #inst "2026-03-05" :external-id "F-1"})
    (commitment/fulfill! conn {:commitment "C-3" :transaction (tx-by-xid conn "F-1")
                               :amount 100 :recorded-by-uid alice})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed commitment"
                          (commitment/fulfill! conn {:commitment "C-3"
                                                     :transaction (tx-by-xid conn "F-1")
                                                     :amount 1 :recorded-by-uid alice})))))

;; ============================================================================
;; Cancel
;; ============================================================================

(deftest cancel-closes-an-unfulfilled-commitment
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-4" :kind :encumbrance :counterparty cust
           :committed-amount 500 :commodity eur
           :due-date #inst "2026-04-01" :recorded-by-uid alice})
    (commitment/cancel! conn {:commitment "C-4" :changed-by-uid alice
                              :reason :superseded})
    (is (= :cancelled (:kontor.commitment/state (commitment/pull-commitment (d/db conn) "C-4"))))
    (is (empty? (commitment/open-commitments (d/db conn))))))

;; ============================================================================
;; Aging
;; ============================================================================

(deftest aging-buckets-an-overdue-commitment
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-5" :kind :receivable :counterparty cust
           :committed-amount 800 :commodity eur
           :due-date #inst "2026-01-01" :recorded-by-uid alice})
    (let [rows (commitment/aging (d/db conn) {:as-of #inst "2026-03-15"})
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (= "C-5" (:external-id row)))
      (is (= 73 (:overdue-days row)) "2026-01-01 → 2026-03-15")
      (is (= :61-90 (:bucket row)))
      (is (== 800M (:outstanding row))))))

;; ============================================================================
;; Bitemporal consistency on the tx-time axis
;; ============================================================================

(deftest open-commitments-is-tx-time-consistent
  (let [conn (fresh)]
    (commitment/record-commitment!
     conn {:external-id "C-6" :kind :receivable :counterparty cust
           :committed-amount 200 :commodity eur
           :due-date #inst "2026-04-01" :recorded-by-uid alice})
    (let [db-before-fulfill (d/db conn)]
      (book/receive-payment! conn {:debit-account [:kontor.account/path "Assets:Cash"]
                                   :credit-account [:kontor.account/path "Assets:Receivable"]
                                   :amount 200 :commodity eur
                                   :effective-date #inst "2026-03-08"
                                   :external-id "PAY-6"})
      (commitment/fulfill! conn {:commitment "C-6" :transaction (tx-by-xid conn "PAY-6")
                                 :amount 200 :recorded-by-uid alice})
      (testing "the present sees it closed"
        (is (empty? (commitment/open-commitments (d/db conn)))))
      (testing "the snapshot before the fulfillment still sees it open"
        (is (= 1 (count (commitment/open-commitments db-before-fulfill))))))))

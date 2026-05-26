(ns kontor.inventory.ops-test
  "ADR-059: receive / issue / transfer + GL integration + the
   negative-inventory policy.

   Covers:
   - receive! writes the valuation layer + GL postings + the physical
     :inventory-detail in one tx, linked by :inventory-detail/transaction.
   - issue! consumes a layer + GL + physical detail; realizing a
     reservation retracts it and moves QOH only (:atp-diff 0).
   - the negative-inventory policy: over-issue throws when
     :negative-allowed? is false; when true, a negative-fill
     :valuation-layer + :negative-fill record let the issue proceed.
   - true-up-negative-fill! reconciles estimate → actual.
   - transfer! / complete-transfer! / cancel-transfer! — two-phase,
     GL-free."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.inventory.core :as inv]
            [kontor.inventory.ops :as ops]
            [kontor.inventory.report :as report]
            [kontor.inventory.reservation :as res]
            [kontor.inventory.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.sales.schema :as sales-schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (partner-schema/install! conn)
    (sales-schema/install! conn)
    (inv-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.partner/external-id "P-widget" :kontor.partner/name "Widget"}
                 {:kontor.partner/external-id "O-1"  :kontor.partner/name "Order 1"}
                 {:kontor.partner/external-id "OI-1" :kontor.partner/name "Order line 1"}
                 {:kontor.partner/external-id "SG-1" :kontor.partner/name "Ship group 1"}
                 ;; GL accounts.
                 {:db/id "acct-inv" :kontor.account/code "1400" :kontor.account/name "Inventory"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "acct-grir" :kontor.account/code "1410" :kontor.account/name "GR-IR Clearing"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "acct-cogs" :kontor.account/code "5000" :kontor.account/name "COGS"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-var" :kontor.account/code "5900" :kontor.account/name "Cost Variance"
                  :kontor.account/type :expense :kontor.account/active true}
                 ;; Valuation book + journal.
                 {:db/id "book" :valuation-book/code "primary"
                  :valuation-book/name "Primary" :valuation-book/cost-method :fifo
                  :valuation-book/active true}
                 {:db/id "journal-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- p       [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct    [db code] (ref-eid db :kontor.account/code code))
(defn- book    [db] (ref-eid db :valuation-book/code "primary"))
(defn- journal [db] (ref-eid db :kontor.journal/code "GEN"))

;; account-fn for plan-stock-move's stock-move roles.
(defn- account-fn [db]
  (let [m {:inventory       (acct db "1400")
           :gr-ir-clearing  (acct db "1410")
           :cogs            (acct db "5000")
           :price-variance  (acct db "5900")}]
    (fn [_move role] (get m role))))

(defn- warehouse! [conn]
  (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
  (inv/facility-by-code (d/db conn) "WH"))

(defn- gl-postings
  "All :posting eids referencing `account` — a quick GL probe."
  [db account]
  (d/q '[:find [?p ...] :in $ ?a :where [?p :kontor.posting/account ?a]] db account))

;; ============================================================================
;; receive!
;; ============================================================================

(deftest receive-writes-both-halves-linked
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        {:keys [inventory-item transaction]}
        (ops/receive! conn {:product widget :facility wh :book (book db)
                            :qty 100M :unit-cost 12.00M :commodity (ref-eid db :kontor.commodity/symbol "EUR")
                            :journal (journal db) :account-fn (account-fn db)
                            :effective-date #inst "2026-03-01"})]
    (testing "the physical half — :inventory-detail brought QOH to 100"
      (is (= 100M (inv/on-hand-qty (d/db conn) inventory-item))))
    (testing "the financial half — a :valuation-layer was created"
      (is (= 1 (count (d/q '[:find [?l ...]
                             :where [?l :valuation-layer/qty-original _]]
                           (d/db conn))))))
    (testing "the GL postings landed (Dr inventory / Cr GR-IR)"
      (is (= 1 (count (gl-postings (d/db conn) (acct (d/db conn) "1400")))))
      (is (= 1 (count (gl-postings (d/db conn) (acct (d/db conn) "1410"))))))
    (testing "the physical detail is linked to the GL transaction"
      (is (some? transaction))
      (let [det (first (inv/details-of (d/db conn) inventory-item))]
        (is (= transaction (:db/id (:inventory-detail/transaction det))))
        (is (= :receipt (:inventory-detail/source-kind det)))))))

;; ============================================================================
;; issue!
;; ============================================================================

(deftest issue-consumes-and-posts
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        eur (ref-eid db :kontor.commodity/symbol "EUR")
        _ (ops/receive! conn {:product widget :facility wh :book (book db)
                              :qty 100M :unit-cost 12.00M :commodity eur
                              :journal (journal db) :account-fn (account-fn db)})
        {:keys [inventory-item]}
        (ops/issue! conn {:product widget :facility wh :book (book (d/db conn))
                          :qty 30M :commodity eur :journal (journal (d/db conn))
                          :account-fn (account-fn (d/db conn))})]
    (testing "QOH drops to 70; COGS posting landed"
      (is (= 70M (inv/on-hand-qty (d/db conn) inventory-item)))
      (is (= 1 (count (gl-postings (d/db conn) (acct (d/db conn) "5000"))))))))

(deftest issue-realizing-a-reservation-retracts-it
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        eur (ref-eid db :kontor.commodity/symbol "EUR")
        {item :inventory-item}
        (ops/receive! conn {:product widget :facility wh :book (book db)
                            :qty 100M :unit-cost 12.00M :commodity eur
                            :journal (journal db) :account-fn (account-fn db)})
        _ (res/reserve! conn {:product widget :facility wh :quantity 40M
                              :order (p (d/db conn) "O-1")
                              :order-item (p (d/db conn) "OI-1")
                              :ship-group (p (d/db conn) "SG-1")})
        reservation (d/q '[:find ?r . :where [?r :inv-reservation/order _]]
                         (d/db conn))]
    (is (= 60M (res/atp-raw (d/db conn) item)) "reserved → ATP 60, QOH still 100")
    (is (= 100M (inv/on-hand-qty (d/db conn) item)))
    (ops/issue! conn {:product widget :facility wh :book (book (d/db conn))
                      :qty 40M :commodity eur :journal (journal (d/db conn))
                      :account-fn (account-fn (d/db conn))
                      :inventory-item item :reservation reservation})
    (testing "realizing the reservation moves QOH only, and retracts the reservation"
      (is (= 60M (inv/on-hand-qty (d/db conn) item)))
      (is (= 60M (res/atp-raw (d/db conn) item)) "ATP unchanged — it was already dropped")
      (is (nil? (d/q '[:find ?r . :where [?r :inv-reservation/order _]]
                     (d/db conn)))))))

;; ============================================================================
;; Negative-inventory policy
;; ============================================================================

(deftest issue-over-draw-refused-by-default
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        eur (ref-eid db :kontor.commodity/symbol "EUR")
        _ (ops/receive! conn {:product widget :facility wh :book (book db)
                              :qty 20M :unit-cost 12.00M :commodity eur
                              :journal (journal db) :account-fn (account-fn db)})]
    (testing "an over-issue throws :inventory/negative-not-allowed by default"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"negative inventory is not allowed"
           (ops/issue! conn {:product widget :facility wh :book (book (d/db conn))
                             :qty 50M :commodity eur :journal (journal (d/db conn))
                             :account-fn (account-fn (d/db conn))}))))))

(deftest issue-over-draw-creates-negative-fill-when-allowed
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        eur (ref-eid db :kontor.commodity/symbol "EUR")
        _ (ops/receive! conn {:product widget :facility wh :book (book db)
                              :qty 20M :unit-cost 12.00M :commodity eur
                              :journal (journal db) :account-fn (account-fn db)})
        _ (inv/define-facility-product! conn {:facility wh :product widget
                                              :negative-allowed? true})
        {:keys [inventory-item negative-fill]}
        (ops/issue! conn {:product widget :facility wh :book (book (d/db conn))
                          :qty 50M :commodity eur :journal (journal (d/db conn))
                          :account-fn (account-fn (d/db conn))
                          :estimated-unit-cost 13.00M})]
    (testing "the issue proceeds; QOH goes negative; a :negative-fill is recorded"
      (is (= -30M (inv/on-hand-qty (d/db conn) inventory-item)))
      (is (some? negative-fill))
      (let [nf (d/pull (d/db conn) '[* {:negative-fill/origin-issue [:db/id]}]
                       negative-fill)]
        (is (= :open (:negative-fill/status nf)))
        (is (= 30M (:negative-fill/shortfall-qty nf)))
        (is (= 13.00M (:negative-fill/estimated-unit-cost nf)))
        (is (some? (:db/id (:negative-fill/origin-issue nf)))
            "the negative-fill links back to the originating issue tx")))
    (testing "true-up-negative-fill! reconciles estimate → actual"
      (ops/true-up-negative-fill!
       conn {:negative-fill negative-fill :actual-unit-cost 15.00M
             :journal (journal (d/db conn))
             :inventory-account (acct (d/db conn) "1400")
             :variance-account (acct (d/db conn) "5900")})
      (let [nf (d/pull (d/db conn) '[* {:negative-fill/true-up-adjustment [*]}]
                       negative-fill)]
        (is (= :trued-up (:negative-fill/status nf)))
        ;; (15 − 13) × 30 = 60 cost delta on the layer-adjustment.
        (is (= 60.00M (:layer-adjustment/amount
                       (:negative-fill/true-up-adjustment nf))))))))

;; ============================================================================
;; Transfers
;; ============================================================================

(deftest transfer-two-phase-moves-stock
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH-1" :name "WH 1" :type :warehouse})
        _ (inv/define-facility! conn {:code "WH-2" :name "WH 2" :type :warehouse})
        db (d/db conn)
        wh1 (inv/facility-by-code db "WH-1")
        widget (p db "P-widget")
        {src :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh1 :qty 100M})
        {:keys [transfer]}
        (ops/transfer! conn {:inventory-item src :quantity 40M
                             :to-facility "WH-2"})]
    (testing "transfer! takes the qty off the source — it is in transit"
      (is (= 60M (inv/on-hand-qty (d/db conn) src)))
      (is (= :in-transit (:inventory-transfer/status
                          (d/pull (d/db conn) [:inventory-transfer/status] transfer))))
      (is (= 40M (report/in-transit-balance (d/db conn)))
          "the in-transit balance is the cutoff exposure"))
    (testing "complete-transfer! lands it at the destination"
      (let [{dest :to-inventory-item} (ops/complete-transfer! conn {:transfer transfer})]
        (is (= 40M (inv/on-hand-qty (d/db conn) dest)))
        (is (= 60M (inv/on-hand-qty (d/db conn) src)))
        (is (= 0M (report/in-transit-balance (d/db conn))) "nothing in transit now")
        (is (= :complete (:inventory-transfer/status
                          (d/pull (d/db conn) [:inventory-transfer/status]
                                  transfer))))))))

(deftest cancel-transfer-returns-stock-to-source
  (let [conn (bootstrap)
        _ (inv/define-facility! conn {:code "WH-1" :name "WH 1" :type :warehouse})
        _ (inv/define-facility! conn {:code "WH-2" :name "WH 2" :type :warehouse})
        db (d/db conn)
        wh1 (inv/facility-by-code db "WH-1")
        widget (p db "P-widget")
        {src :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh1 :qty 100M})
        {:keys [transfer]}
        (ops/transfer! conn {:inventory-item src :quantity 40M :to-facility "WH-2"})]
    (is (= 60M (inv/on-hand-qty (d/db conn) src)))
    (ops/cancel-transfer! conn transfer)
    (testing "cancel returns the qty to the source and marks the transfer :cancelled"
      (is (= 100M (inv/on-hand-qty (d/db conn) src)))
      (is (= :cancelled (:inventory-transfer/status
                         (d/pull (d/db conn) [:inventory-transfer/status]
                                 transfer)))))))

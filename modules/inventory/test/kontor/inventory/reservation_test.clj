(ns kontor.inventory.reservation-test
  "ADR-058: available-to-promise + the reservation bridge.

   Covers:
   - atp-raw = Σ :atp-diff (reservations netted by construction);
     available-to-promise = atp-raw − safety-stock.
   - reserve! walks buckets :pickloc-first, sorted by
     :reserve-order-enum, drawing :atp-diff details + :inv-reservation
     rows.
   - back-order: a shortfall lands on the last drawn row as
     :quantity-not-available + a negative-:atp-diff detail; with
     :require-inventory? it throws and writes nothing.
   - release-reservation! restores the ATP and retracts the row."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.inventory.core :as inv]
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
                 ;; Product + order-side refs — :inv-reservation/{order,
                 ;; order-item,ship-group} are bare refs; reuse :partner
                 ;; entities as stand-ins (the kernel test convention —
                 ;; the real order aggregate lives in kontor-sales).
                 {:kontor.partner/external-id "P-widget" :kontor.partner/name "Widget"}
                 {:kontor.partner/external-id "O-1"  :kontor.partner/name "Order 1"}
                 {:kontor.partner/external-id "OI-1" :kontor.partner/name "Order line 1"}
                 {:kontor.partner/external-id "SG-1" :kontor.partner/name "Ship group 1"}
                 {:db/id "lot-a" :lot/label "LOT-A"}
                 {:db/id "lot-b" :lot/label "LOT-B"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- p   [db code] (ref-eid db :kontor.partner/external-id code))
(defn- lot [db label] (ref-eid db :lot/label label))

(defn- order-refs [db]
  {:order      (p db "O-1")
   :order-item (p db "OI-1")
   :ship-group (p db "SG-1")})

;; A warehouse with a pick face + a bulk location.
(defn- warehouse! [conn]
  (inv/define-facility! conn {:code "WH" :name "WH" :type :warehouse})
  (inv/define-location! conn {:facility "WH" :seq-id "PICK-1" :type :pickloc})
  (inv/define-location! conn {:facility "WH" :seq-id "BULK-1" :type :bulk})
  (inv/facility-by-code (d/db conn) "WH"))

;; ============================================================================
;; available-to-promise
;; ============================================================================

(deftest atp-nets-reservations-and-safety-stock
  (let [conn (bootstrap)
        wh (warehouse! conn)
        widget (p (d/db conn) "P-widget")
        _ (inv/define-facility-product! conn {:facility wh :product widget
                                              :safety-stock 20M})
        _ (inv/place-opening-stock! conn {:product widget :facility wh :qty 100M})
        scope {:product widget :facility wh}]
    (testing "before any reservation: atp-raw = on-hand, ATP = on-hand − safety-stock"
      (is (= 100M (inv/on-hand-qty (d/db conn) scope)))
      (is (= 100M (res/atp-raw (d/db conn) scope)))
      (is (= 80M (res/available-to-promise (d/db conn) scope))))
    (testing "a reservation drops ATP but NOT quantity-on-hand"
      (res/reserve! conn (merge (order-refs (d/db conn))
                                {:product widget :facility wh :quantity 30M}))
      (is (= 100M (inv/on-hand-qty (d/db conn) scope)) "physical stock unchanged")
      (is (= 70M (res/atp-raw (d/db conn) scope)) "ATP netted the reservation")
      (is (= 50M (res/available-to-promise (d/db conn) scope))))))

;; ============================================================================
;; reserve! — the walk
;; ============================================================================

(deftest reserve-walks-pickloc-before-bulk
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        pick (inv/location-by db "WH" "PICK-1")
        bulk (inv/location-by db "WH" "BULK-1")
        ;; 10 in the pick face, 100 in bulk.
        {pick-item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :location pick :qty 10M})
        {bulk-item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :location bulk :qty 100M})
        result (res/reserve! conn (merge (order-refs (d/db conn))
                                         {:product widget :facility wh
                                          :quantity 25M}))]
    (testing "the walk drains the pick face first, then bulk"
      (is (= 25M (:reserved result)))
      (is (= 0M (:backordered result)))
      (is (= [10M 15M] (:draws result)) "10 from pickloc, 15 from bulk"))
    (testing "ATP dropped on both buckets, QOH untouched"
      (is (= 0M (res/atp-raw (d/db conn) pick-item)))
      (is (= 85M (res/atp-raw (d/db conn) bulk-item)))
      (is (= 110M (inv/on-hand-qty (d/db conn) {:product widget :facility wh}))))
    (testing "two :inv-reservation rows were created, one per bucket drawn"
      (is (= 2 (count (d/q '[:find [?r ...]
                             :where [?r :inv-reservation/order _]]
                           (d/db conn))))))))

(deftest reserve-fifo-rec-orders-by-received-at
  (let [conn (bootstrap)
        wh (warehouse! conn)
        db (d/db conn)
        widget (p db "P-widget")
        ;; Two lots, LOT-B received earlier than LOT-A.
        {old :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :lot (lot db "LOT-B") :qty 40M
                                        :received-at #inst "2026-01-01"})
        {new :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh
                                        :lot (lot db "LOT-A") :qty 40M
                                        :received-at #inst "2026-03-01"})
        _ (res/reserve! conn (merge (order-refs (d/db conn))
                                    {:product widget :facility wh :quantity 50M
                                     :reserve-order-enum :fifo-rec}))]
    (testing "fifo-rec drains the oldest-received bucket first"
      (is (= 0M (res/atp-raw (d/db conn) old)) "the 2026-01 lot fully drawn")
      (is (= 30M (res/atp-raw (d/db conn) new)) "10 taken from the 2026-03 lot"))))

;; ============================================================================
;; Back-order
;; ============================================================================

(deftest reserve-backorders-the-shortfall
  (let [conn (bootstrap)
        wh (warehouse! conn)
        widget (p (d/db conn) "P-widget")
        {item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh :qty 20M})
        result (res/reserve! conn (merge (order-refs (d/db conn))
                                         {:product widget :facility wh
                                          :quantity 50M}))]
    (testing "20 reserved, 30 back-ordered"
      (is (= 20M (:reserved result)))
      (is (= 30M (:backordered result))))
    (testing "ATP is driven negative by the back-order; QOH stays at 20"
      (is (= -30M (res/atp-raw (d/db conn) item)))
      (is (= 20M (inv/on-hand-qty (d/db conn) item))))
    (testing "the reservation row carries :quantity-not-available"
      (let [r (d/q '[:find (pull ?r [:inv-reservation/quantity
                                     :inv-reservation/quantity-not-available]) .
                     :where [?r :inv-reservation/order _]]
                   (d/db conn))]
        (is (= 20M (:inv-reservation/quantity r)))
        (is (= 30M (:inv-reservation/quantity-not-available r)))))))

(deftest reserve-require-inventory-throws-on-shortfall
  (let [conn (bootstrap)
        wh (warehouse! conn)
        widget (p (d/db conn) "P-widget")
        _ (inv/place-opening-stock! conn {:product widget :facility wh :qty 20M})]
    (testing ":require-inventory? true throws and writes nothing"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Insufficient ATP"
           (res/reserve! conn (merge (order-refs (d/db conn))
                                     {:product widget :facility wh :quantity 50M
                                      :require-inventory? true}))))
      (is (empty? (d/q '[:find [?r ...] :where [?r :inv-reservation/order _]]
                       (d/db conn)))
          "no :inv-reservation rows written")
      (is (= 20M (res/atp-raw (d/db conn) {:product widget :facility wh}))
          "ATP untouched"))))

;; ============================================================================
;; release-reservation!
;; ============================================================================

(deftest release-restores-atp-and-retracts-the-row
  (let [conn (bootstrap)
        wh (warehouse! conn)
        widget (p (d/db conn) "P-widget")
        {item :inventory-item}
        (inv/place-opening-stock! conn {:product widget :facility wh :qty 100M})
        _ (res/reserve! conn (merge (order-refs (d/db conn))
                                    {:product widget :facility wh :quantity 40M}))
        res-eid (d/q '[:find ?r . :where [?r :inv-reservation/order _]] (d/db conn))]
    (is (= 60M (res/atp-raw (d/db conn) item)) "reserved → ATP 60")
    (res/release-reservation! conn res-eid)
    (testing "release restores the ATP and retracts the reservation"
      (is (= 100M (res/atp-raw (d/db conn) item)))
      (is (nil? (d/q '[:find ?r . :where [?r :inv-reservation/order _]]
                     (d/db conn)))))))

(ns kontor.sales-test
  "Tests for kontor-sales — ADR-035.

   Schema install, order creation, items + composite identity, ship-
   group allocation, reservation per lot, three-level adjustments,
   role junction, status transitions (via the seeded :status-
   transition table), header promotion, recalc pipeline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.partner.schema :as partner-schema]
            [kontor.sales :as sales]
            [kontor.sales.schema :as sales-schema]
            [kontor.workflow.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (sales-schema/install! *conn*)
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Setup helpers
;; ============================================================================

(defn- seed-commodity! []
  (d/transact *conn* [{:kontor.commodity/symbol "EUR"
                       :kontor.commodity/name "Euro"
                       :kontor.commodity/precision 2
                       :kontor.commodity/iso-4217 "EUR"}]))

(defn- seed-partners! []
  (d/transact *conn*
              [{:kontor.partner/external-id "SELLER"
                :kontor.partner/type :org
                :kontor.partner/status :enabled
                :kontor.partner/name "Seller Co"}
               {:kontor.partner/external-id "BUYER"
                :kontor.partner/type :person
                :kontor.partner/status :enabled
                :kontor.partner/name "Customer Person"}
               {:kontor.partner/external-id "CARRIER"
                :kontor.partner/type :org
                :kontor.partner/status :enabled
                :kontor.partner/name "Carrier Co"}]))

(defn- minimal-order!
  "Transact a minimal order with one item; returns the order eid."
  []
  (seed-commodity!)
  (seed-partners!)
  (d/transact *conn*
              [{:kontor.order/external-id "ORD-1"
                :kontor.order/type :sales
                :kontor.order/status :order.status/created
                :kontor.order/order-date #inst "2026-05-01"
                :kontor.order/entry-date #inst "2026-05-01"
                :kontor.order/currency [:kontor.commodity/symbol "EUR"]
                :kontor.order/bill-from-partner [:kontor.partner/external-id "SELLER"]
                :kontor.order/bill-to-partner [:kontor.partner/external-id "BUYER"]
                :kontor.order/invoice-per-shipment? false}
               {:kontor.sales.order-item/order [:kontor.order/external-id "ORD-1"]
                :kontor.sales.order-item/seq-id "00001"
                :kontor.sales.order-item/type :product
                :kontor.sales.order-item/product-id "WIDGET-A"
                :kontor.sales.order-item/description "Widget A"
                :kontor.sales.order-item/quantity 10M
                :kontor.sales.order-item/unit-price 25M
                :kontor.sales.order-item/cancel-quantity 0M
                :kontor.sales.order-item/status :order-item.status/created
                :kontor.sales.order-item/auto-reserve? true}])
  (sales/by-external-id (d/db *conn*) "ORD-1"))

;; ============================================================================
;; Schema + seeds
;; ============================================================================

(deftest schema-attrs-and-seeds-present
  (let [db (d/db *conn*)
        idents (set (d/q '[:find [?i ...] :where [_ :db/ident ?i]] db))]
    (doseq [a [:kontor.order/external-id :kontor.order/type :kontor.order/status :kontor.order/currency
               :kontor.order/bill-from-partner :kontor.order/bill-to-partner :kontor.order/entity
               :kontor.order/grand-total :kontor.order/invoice-per-shipment?
               :kontor.sales.order-item/order :kontor.sales.order-item/seq-id :kontor.sales.order-item/identity
               :kontor.sales.order-item/quantity :kontor.sales.order-item/unit-price :kontor.sales.order-item/status
               :kontor.sales.order-item/auto-reserve? :kontor.sales.order-item/cost-center
               :kontor.ship-group/order :kontor.ship-group/seq-id :kontor.ship-group/identity
               :kontor.ship-group/carrier-partner :kontor.ship-group/contact-mech
               :kontor.ship-group-assoc/order-item :kontor.ship-group-assoc/ship-group
               :kontor.ship-group-assoc/quantity :kontor.ship-group-assoc/identity
               :kontor.inv-reservation/order-item :kontor.inv-reservation/inventory-item
               :kontor.inv-reservation/quantity :kontor.inv-reservation/identity
               :kontor.order-adjustment/order :kontor.order-adjustment/scope
               :kontor.order-adjustment/type :kontor.order-adjustment/amount
               :kontor.order-adjustment/include-in-tax? :kontor.order-adjustment/neutral?
               :kontor.order-role/order :kontor.order-role/partner :kontor.order-role/role-type
               :kontor.order-role/identity]]
      (is (contains? idents a) (str "missing: " a)))

    (testing "the seeded order status transitions are queryable"
      (is (true? (sm/legal-transition? db :order :kontor.order/status
                                       :order.status/created
                                       :order.status/approved)))
      (is (true? (sm/legal-transition? db :order :kontor.order/status
                                       :order.status/approved
                                       :order.status/completed)))
      (is (false? (sm/legal-transition? db :order :kontor.order/status
                                        :order.status/created
                                        :order.status/completed))
          "skip-to-completed disallowed"))

    (testing "order-item status transitions are also seeded"
      (is (true? (sm/legal-transition? db :order-item :kontor.sales.order-item/status
                                       :order-item.status/created
                                       :order-item.status/approved))))))

;; ============================================================================
;; Order + items
;; ============================================================================

(deftest order-creation-and-pull
  (minimal-order!)
  (let [db (d/db *conn*)
        ord (sales/pull-order db "ORD-1")
        items (sales/items-of db "ORD-1")]
    (is (= :sales (:kontor.order/type ord)))
    (is (= "Seller Co" (-> ord :kontor.order/bill-from-partner :kontor.partner/name)))
    (is (= "Customer Person" (-> ord :kontor.order/bill-to-partner :kontor.partner/name)))
    (is (= 1 (count items)))
    (is (= 10M (-> items first :kontor.sales.order-item/quantity)))
    (is (= 25M (-> items first :kontor.sales.order-item/unit-price)))))

(deftest order-item-composite-identity-upserts-on-duplicate-seq
  ;; Composite-tuple :db.unique/identity is upsert semantics: a second
  ;; tx with the same (order, seq-id) tuple merges into the existing
  ;; entity instead of creating a new row. This is intentional —
  ;; idempotent re-application of a tx is the expected behavior.
  (minimal-order!)
  (d/transact *conn*
              [{:kontor.sales.order-item/order [:kontor.order/external-id "ORD-1"]
                :kontor.sales.order-item/seq-id "00001"
                :kontor.sales.order-item/type :product
                :kontor.sales.order-item/quantity 5M           ; was 10M
                :kontor.sales.order-item/unit-price 30M        ; was 25M
                :kontor.sales.order-item/status :order-item.status/created}])
  (let [items (sales/items-of (d/db *conn*) "ORD-1")]
    (testing "still one item with seq-id 00001 (no duplicate row)"
      (is (= 1 (count items))))
    (testing "the row was updated with new values"
      (is (= 5M (-> items first :kontor.sales.order-item/quantity)))
      (is (= 30M (-> items first :kontor.sales.order-item/unit-price))))))

;; ============================================================================
;; Ship groups + allocation + reservation
;; ============================================================================

(deftest ship-group-allocation-and-reservation
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i .
                        :in $ ?seq
                        :where [?i :kontor.sales.order-item/seq-id ?seq]]
                      (d/db *conn*) "00001")]
    (d/transact *conn*
                [{:kontor.ship-group/order order-eid
                  :kontor.ship-group/seq-id "SG-001"
                  :kontor.ship-group/shipment-method-type :standard
                  :kontor.ship-group/carrier-partner [:kontor.partner/external-id "CARRIER"]
                  :kontor.ship-group/facility-id "WHSE-DE-1"
                  :kontor.ship-group/may-split? true}
                 {:kontor.ship-group-assoc/order order-eid
                  :kontor.ship-group-assoc/order-item item-eid
                  :kontor.ship-group-assoc/ship-group [:kontor.ship-group/identity [order-eid "SG-001"]]
                  :kontor.ship-group-assoc/quantity 10M
                  :kontor.ship-group-assoc/cancel-quantity 0M}
                 ;; Reservation against an :inventory-item bucket.
                 ;; `kontor-inventory` is not installed in this sales-only
                 ;; fixture, so `bucket-1` is a bare stand-in entity — the
                 ;; ADR-058 reserve! walk (which creates real
                 ;; inventory-item-backed reservations) is exercised in
                 ;; the kontor-inventory tests.
                 {:db/id "bucket-1" :kontor.lot/label "Widget-A batch 1"}
                 {:kontor.inv-reservation/order order-eid
                  :kontor.inv-reservation/order-item item-eid
                  :kontor.inv-reservation/ship-group [:kontor.ship-group/identity [order-eid "SG-001"]]
                  :kontor.inv-reservation/inventory-item "bucket-1"
                  :kontor.inv-reservation/quantity 10M
                  :kontor.inv-reservation/reserve-order-enum :fifo-rec
                  :kontor.inv-reservation/reserved-datetime #inst "2026-05-01"
                  :kontor.inv-reservation/promised-datetime #inst "2026-05-08"
                  :kontor.inv-reservation/current-promised-date #inst "2026-05-08"}])
    (let [db (d/db *conn*)
          sgs (sales/ship-groups-of db "ORD-1")
          res (sales/reservations-of db "ORD-1")]
      (is (= 1 (count sgs)))
      (is (= "WHSE-DE-1" (-> sgs first :kontor.ship-group/facility-id)))
      (is (= 1 (count res)))
      (is (= 10M (-> res first :kontor.inv-reservation/quantity)))
      (is (= :fifo-rec (-> res first :kontor.inv-reservation/reserve-order-enum))))))

;; ============================================================================
;; Adjustments at three levels
;; ============================================================================

(deftest adjustments-at-three-levels
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i .
                        :in $ ?seq
                        :where [?i :kontor.sales.order-item/seq-id ?seq]]
                      (d/db *conn*) "00001")]
    (d/transact *conn*
                [;; Ship group
                 {:kontor.ship-group/order order-eid
                  :kontor.ship-group/seq-id "SG-001"
                  :kontor.ship-group/shipment-method-type :standard}
                 ;; Header-level adjustment (scope = order itself)
                 {:kontor.order-adjustment/order order-eid
                  :kontor.order-adjustment/scope order-eid
                  :kontor.order-adjustment/type :discount
                  :kontor.order-adjustment/amount -10M
                  :kontor.order-adjustment/origin-code "WELCOME10"}
                 ;; Line-level adjustment (scope = order-item)
                 {:kontor.order-adjustment/order order-eid
                  :kontor.order-adjustment/scope item-eid
                  :kontor.order-adjustment/type :tax
                  :kontor.order-adjustment/amount 47.50M
                  :kontor.order-adjustment/source-percentage 0.19M
                  :kontor.order-adjustment/tax-auth-geo-id "DE"}
                 ;; Ship-group-level adjustment
                 {:kontor.order-adjustment/order order-eid
                  :kontor.order-adjustment/scope [:kontor.ship-group/identity [order-eid "SG-001"]]
                  :kontor.order-adjustment/type :shipping
                  :kontor.order-adjustment/amount 8.50M}])
    (let [db (d/db *conn*)
          all-adj (sales/adjustments-of db "ORD-1")
          header-adj (sales/adjustments-of db "ORD-1" {:level :header})
          line-adj   (sales/adjustments-of db "ORD-1" {:level :line})
          sg-adj     (sales/adjustments-of db "ORD-1" {:level :ship-group})]
      (is (= 3 (count all-adj)))
      (is (= 1 (count header-adj)))
      (is (= :discount (-> header-adj first :kontor.order-adjustment/type)))
      (is (= 1 (count line-adj)))
      (is (= :tax (-> line-adj first :kontor.order-adjustment/type)))
      (is (= 1 (count sg-adj)))
      (is (= :shipping (-> sg-adj first :kontor.order-adjustment/type))))))

;; ============================================================================
;; Roles
;; ============================================================================

(deftest order-roles-and-partner-lookup
  (let [order-eid (minimal-order!)]
    (d/transact *conn*
                [{:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order-role/role-type :customer}
                 {:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order-role/role-type :bill-to}
                 {:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order-role/role-type :ship-to}
                 {:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "SELLER"]
                  :kontor.order-role/role-type :bill-from}])
    (let [db (d/db *conn*)]
      (is (= 4 (count (sales/roles-of db "ORD-1"))))
      (is (= (d/q '[:find ?p . :in $ ?xid :where [?p :kontor.partner/external-id ?xid]]
                  db "BUYER")
             (sales/partner-on-order db "ORD-1" :customer)))
      (is (= (d/q '[:find ?p . :in $ ?xid :where [?p :kontor.partner/external-id ?xid]]
                  db "SELLER")
             (sales/partner-on-order db "ORD-1" :bill-from))))))

(deftest role-composite-identity-prevents-duplicate
  (let [order-eid (minimal-order!)]
    (d/transact *conn*
                [{:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order-role/role-type :customer}])
    ;; Same (order, partner, role-type) should be a no-op via upsert
    (d/transact *conn*
                [{:kontor.order-role/order order-eid
                  :kontor.order-role/partner [:kontor.partner/external-id "BUYER"]
                  :kontor.order-role/role-type :customer}])
    ;; Only the tx datom and identity tuple datoms are added, not a
    ;; new role row.
    (is (= 1 (count (sales/roles-of (d/db *conn*) "ORD-1"))))))

;; ============================================================================
;; Status transitions
;; ============================================================================

(deftest order-status-lifecycle-happy-path
  (let [order-eid (minimal-order!)]
    (sales/approve-order! *conn* order-eid {:reason :approved
                                            :reason-note "passes fraud check"})
    (is (= :order.status/approved (sm/current-status (d/db *conn*) order-eid :kontor.order/status)))
    (sales/complete-order! *conn* order-eid {:reason :completed
                                             :reason-note "all items shipped"})
    (is (= :order.status/completed (sm/current-status (d/db *conn*) order-eid :kontor.order/status)))
    (testing "status-history has two transitions"
      (let [hist (sm/status-history-of (d/db *conn*) order-eid :kontor.order/status)]
        (is (= 2 (count hist)))
        (is (= [:order.status/approved :order.status/completed]
               (map :kontor.status-history/to hist)))))))

(deftest order-status-illegal-transitions
  (let [order-eid (minimal-order!)]
    (testing "cannot skip directly to :completed from :created"
      (is (thrown? Exception
                   (sales/complete-order! *conn* order-eid))))
    (testing "the order remains :created after a rejected transition"
      (is (= :order.status/created
             (sm/current-status (d/db *conn*) order-eid :kontor.order/status))))))

(deftest order-hold-and-release
  (let [order-eid (minimal-order!)]
    (sales/hold-order! *conn* order-eid {:reason :fraud-detected
                                         :reason-note "manual fraud review"})
    (is (= :order.status/hold (sm/current-status (d/db *conn*) order-eid :kontor.order/status)))
    (sales/release-from-hold! *conn* order-eid {:reason :approved
                                                :reason-note "cleared"})
    (is (= :order.status/approved (sm/current-status (d/db *conn*) order-eid :kontor.order/status)))))

(deftest reopen-completed-order
  (let [order-eid (minimal-order!)]
    (sales/approve-order! *conn* order-eid)
    (sales/complete-order! *conn* order-eid)
    (testing "re-open is a legal transition"
      (is (true? (sm/legal-transition? (d/db *conn*) :order :kontor.order/status
                                       :order.status/completed
                                       :order.status/approved))))
    ;; Re-open via the underlying status-machine call
    (sm/record-status-change! *conn*
                              {:entity order-eid
                               :entity-type :order
                               :facet :kontor.order/status
                               :to :order.status/approved
                               :reason :credit-memo-issued
                               :reason-note "amended for credit memo"})
    (is (= :order.status/approved (sm/current-status (d/db *conn*) order-eid :kontor.order/status)))))

;; ============================================================================
;; Header promotion (checkItemStatus pattern)
;; ============================================================================

(deftest check-and-promote-header-on-all-items-completed
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i .
                        :where [?i :kontor.sales.order-item/seq-id "00001"]]
                      (d/db *conn*))]
    (sales/approve-order! *conn* order-eid)
    (sales/set-item-status! *conn* item-eid :order-item.status/approved)
    (sales/set-item-status! *conn* item-eid :order-item.status/completed)
    (let [promoted (sales/check-and-promote-header! *conn* order-eid)]
      (is (= :order.status/completed promoted))
      (is (= :order.status/completed
             (sm/current-status (d/db *conn*) order-eid :kontor.order/status))))))

(deftest check-and-promote-header-on-mixed-items-is-noop
  (let [order-eid (minimal-order!)
        item-eid (d/q '[:find ?i .
                        :where [?i :kontor.sales.order-item/seq-id "00001"]]
                      (d/db *conn*))]
    (sales/approve-order! *conn* order-eid)
    ;; Add a second item that stays :created
    (d/transact *conn*
                [{:kontor.sales.order-item/order order-eid
                  :kontor.sales.order-item/seq-id "00002"
                  :kontor.sales.order-item/type :product
                  :kontor.sales.order-item/quantity 2M
                  :kontor.sales.order-item/unit-price 100M
                  :kontor.sales.order-item/status :order-item.status/created}])
    (sales/set-item-status! *conn* item-eid :order-item.status/approved)
    (sales/set-item-status! *conn* item-eid :order-item.status/completed)
    ;; Mixed: one :completed, one :created → no promotion
    (let [promoted (sales/check-and-promote-header! *conn* order-eid)]
      (is (nil? promoted))
      (is (= :order.status/approved
             (sm/current-status (d/db *conn*) order-eid :kontor.order/status))))))

;; ============================================================================
;; Totals
;; ============================================================================

(deftest compute-grand-total-sums-items-and-non-neutral-adjustments
  (let [order-eid (minimal-order!)]
    (d/transact *conn*
                [{:kontor.order-adjustment/order order-eid
                  :kontor.order-adjustment/scope order-eid
                  :kontor.order-adjustment/type :discount
                  :kontor.order-adjustment/amount -25M
                  :kontor.order-adjustment/neutral? false}
                 ;; A neutral adjustment (e.g. included-VAT tracking)
                 ;; that should NOT contribute to grand total.
                 {:kontor.order-adjustment/order order-eid
                  :kontor.order-adjustment/scope order-eid
                  :kontor.order-adjustment/type :tax-vat-included
                  :kontor.order-adjustment/amount 47.50M
                  :kontor.order-adjustment/neutral? true}])
    (let [db (d/db *conn*)
          total (sales/compute-grand-total db "ORD-1")]
      ;; 10 * 25 = 250 ; minus 25 discount = 225 ; neutral ignored
      (is (= 0 (.compareTo ^java.math.BigDecimal total 225M))))))

;; ============================================================================
;; Recalc pipeline
;; ============================================================================

(deftest recalc-pipeline-runs-registered-processors
  ;; Clean any prior registrations
  (doseq [p (sales/registered-processors)]
    (sales/unregister-processor! (:id p)))
  (let [order-eid (minimal-order!)
        calls (atom [])
        proc-1 (fn [_conn order-eid]
                 (swap! calls conj [:proc-1 order-eid])
                 [])
        proc-2 (fn [_conn order-eid]
                 (swap! calls conj [:proc-2 order-eid])
                 [])]
    (sales/register-processor! {:id :test/p1 :priority 10 :proc proc-1})
    (sales/register-processor! {:id :test/p2 :priority 5 :proc proc-2})
    (sales/recalculate-order! *conn* order-eid)
    (testing "processors run in priority order (lower priority first)"
      (is (= [[:proc-2 order-eid] [:proc-1 order-eid]] @calls)))
    ;; cleanup
    (sales/unregister-processor! :test/p1)
    (sales/unregister-processor! :test/p2)))

(deftest re-registering-replaces-existing-processor
  (doseq [p (sales/registered-processors)]
    (sales/unregister-processor! (:id p)))
  (sales/register-processor! {:id :test/p :priority 0 :proc (fn [_ _] [])})
  (sales/register-processor! {:id :test/p :priority 5 :proc (fn [_ _] [])})
  (is (= 1 (count (sales/registered-processors))))
  (is (= 5 (:priority (first (sales/registered-processors)))))
  (sales/unregister-processor! :test/p))

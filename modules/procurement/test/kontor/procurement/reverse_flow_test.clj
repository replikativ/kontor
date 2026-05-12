(ns kontor.procurement.reverse-flow-test
  "Reverse flow tests (return + RTV + credit memo) — ADR-042 commit
   3/4. Customer return → credit memo; vendor return → debit memo."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.bridge :as inv]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.procurement.returns :as returns]
            [kontor.procurement.schema :as proc-schema]
            [kontor.sales.schema :as sales-schema]
            [kontor.status-machine :as sm]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (partner-schema/install! *conn*)
    (sales-schema/install! *conn*)
    (inv-schema/install! *conn*)
    (proc-schema/install! *conn*)
    (f)))

(use-fixtures :each bootstrap)

;; ============================================================================
;; Setup helpers
;; ============================================================================

(defn- seed-base! []
  (d/transact *conn*
              [{:commodity/symbol "EUR" :commodity/name "Euro"
                :commodity/precision 2 :commodity/iso-4217 "EUR"}
               {:entity/code "ACME" :entity/name "Acme Inc"
                :entity/kind :operating :entity/active true}
               {:partner/external-id "ACME-ORG" :partner/type :org
                :partner/status :enabled :partner/name "Acme Inc"}
               {:partner/external-id "CUSTOMER" :partner/type :person
                :partner/status :enabled :partner/name "Customer Jane"}
               {:partner/external-id "SUPPLIER" :partner/type :org
                :partner/status :enabled :partner/name "Supplier Co"}]))

(defn- create-sales-order!
  [{:keys [external-id qty unit-price]
    :or {external-id "SO-1" qty 10M unit-price 25M}}]
  (d/transact *conn*
              [{:order/external-id external-id
                :order/type :sales
                :order/status :order.status/created
                :order/order-date #inst "2026-04-01"
                :order/entry-date #inst "2026-04-01"
                :order/currency [:commodity/symbol "EUR"]
                :order/bill-from-partner [:partner/external-id "ACME-ORG"]
                :order/bill-to-partner [:partner/external-id "CUSTOMER"]
                :order/entity [:entity/code "ACME"]}
               {:order-item/order [:order/external-id external-id]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id "WIDGET-A"
                :order-item/quantity qty
                :order-item/unit-price unit-price
                :order-item/cancel-quantity 0M
                :order-item/status :order-item.status/approved}])
  (let [db (d/db *conn*)
        order-eid (d/q '[:find ?e . :in $ ?xid
                         :where [?e :order/external-id ?xid]]
                       db external-id)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :order-item/order ?o]]
                      db order-eid)]
    {:order-eid order-eid :item-eid item-eid}))

(defn- create-purchase-order!
  [{:keys [external-id qty unit-price]
    :or {external-id "PO-1" qty 10M unit-price 12M}}]
  (d/transact *conn*
              [{:order/external-id external-id
                :order/type :purchase
                :order/status :order.status/created
                :order/order-date #inst "2026-04-01"
                :order/entry-date #inst "2026-04-01"
                :order/currency [:commodity/symbol "EUR"]
                :order/bill-from-partner [:partner/external-id "SUPPLIER"]
                :order/bill-to-partner [:partner/external-id "ACME-ORG"]
                :order/entity [:entity/code "ACME"]}
               {:order-item/order [:order/external-id external-id]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id "WIDGET-A"
                :order-item/quantity qty
                :order-item/unit-price unit-price
                :order-item/cancel-quantity 0M
                :order-item/status :order-item.status/approved
                :order-item/category :direct}])
  (let [db (d/db *conn*)
        order-eid (d/q '[:find ?e . :in $ ?xid
                         :where [?e :order/external-id ?xid]]
                       db external-id)
        item-eid (d/q '[:find ?i . :in $ ?o
                        :where [?i :order-item/order ?o]]
                      db order-eid)]
    {:order-eid order-eid :item-eid item-eid}))

;; ============================================================================
;; Customer return lifecycle
;; ============================================================================

(deftest customer-return-lifecycle
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-sales-order! {})
        db (d/db *conn*)
        customer (d/q '[:find ?p . :where [?p :partner/external-id "CUSTOMER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)
        acme-entity (d/q '[:find ?e . :where [?e :entity/code "ACME"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-CUST-1"
                           :type :customer
                           :from-party customer
                           :to-party acme
                           :order order-eid
                           :entity acme-entity
                           :destination-facility-id "WAREHOUSE-DE-1"
                           :items [{:order-item item-eid
                                    :product-id "WIDGET-A"
                                    :return-quantity 3M
                                    :return-price 25M
                                    :reason :damaged
                                    :return-type :cash-refund
                                    :expected-disposition :defective}]})
    (let [db (d/db *conn*)
          return-eid (returns/by-external-id db "RMA-CUST-1")]
      (testing "return created in :requested"
        (is (= :requested (sm/current-status db return-eid :return/status))))
      (testing "items captured"
        (let [items (returns/items-of db return-eid)]
          (is (= 1 (count items)))
          (is (= 3M (-> items first :return-item/return-quantity)))
          (is (= :damaged (-> items first :return-item/reason)))))
      ;; Lifecycle progression
      (returns/accept-return! *conn* "RMA-CUST-1" {:reason :approved})
      (is (= :accepted (sm/current-status (d/db *conn*) return-eid :return/status)))
      (returns/receive-return! *conn* "RMA-CUST-1" {:reason :received})
      (is (= :received (sm/current-status (d/db *conn*) return-eid :return/status)))
      (returns/complete-return! *conn* "RMA-CUST-1" {:reason :completed})
      (is (= :completed (sm/current-status (d/db *conn*) return-eid :return/status))))))

(deftest customer-return-rejection
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-sales-order! {:external-id "SO-REJ"})
        db (d/db *conn*)
        customer (d/q '[:find ?p . :where [?p :partner/external-id "CUSTOMER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-REJ"
                           :type :customer
                           :from-party customer
                           :to-party acme
                           :order order-eid
                           :items [{:order-item item-eid
                                    :return-quantity 1M
                                    :reason :customer-request
                                    :return-type :cash-refund}]})
    (returns/reject-return! *conn* "RMA-REJ"
                            {:reason :rejected
                             :reason-note "outside return window"})
    (is (= :rejected (sm/current-status (d/db *conn*)
                                         (returns/by-external-id (d/db *conn*) "RMA-REJ")
                                         :return/status)))))

;; ============================================================================
;; Vendor return lifecycle (RTV)
;; ============================================================================

(deftest vendor-return-rtv
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})
        db (d/db *conn*)
        supplier (d/q '[:find ?p . :where [?p :partner/external-id "SUPPLIER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)
        acme-entity (d/q '[:find ?e . :where [?e :entity/code "ACME"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-VEND-1"
                           :type :vendor
                           :from-party acme       ; we're the from for vendor return
                           :to-party supplier     ; supplier receives the goods
                           :order order-eid
                           :entity acme-entity
                           :supplier-rma "SUP-RMA-12345"
                           :items [{:order-item item-eid
                                    :product-id "WIDGET-A"
                                    :return-quantity 2M
                                    :return-price 12M
                                    :reason :defective
                                    :return-type :vendor-credit
                                    :expected-disposition :return-to-supplier}]})
    (let [db (d/db *conn*)
          return-eid (returns/by-external-id db "RMA-VEND-1")
          ret (returns/pull-return db return-eid)]
      (testing "vendor return created with role-inverted parties"
        (is (= :vendor (:return/type ret)))
        (is (= "ACME-ORG" (-> ret :return/from-party :partner/external-id)))
        (is (= "SUPPLIER" (-> ret :return/to-party :partner/external-id))))
      (testing "supplier-rma captured"
        (is (= "SUP-RMA-12345" (:return/supplier-rma ret))))
      ;; Progress through lifecycle
      (returns/accept-return! *conn* "RMA-VEND-1")
      (returns/receive-return! *conn* "RMA-VEND-1")
      (returns/complete-return! *conn* "RMA-VEND-1")
      (is (= :completed (sm/current-status (d/db *conn*) return-eid :return/status))))))

;; ============================================================================
;; Credit memo bridge
;; ============================================================================

(deftest credit-memo-from-customer-return
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-sales-order! {})
        db (d/db *conn*)
        customer (d/q '[:find ?p . :where [?p :partner/external-id "CUSTOMER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-CM"
                           :type :customer
                           :from-party customer
                           :to-party acme
                           :order order-eid
                           :items [{:order-item item-eid
                                    :product-id "WIDGET-A"
                                    :return-quantity 3M
                                    :return-price 25M
                                    :reason :damaged
                                    :return-type :cash-refund}]})
    (returns/accept-return! *conn* "RMA-CM")
    (returns/receive-return! *conn* "RMA-CM")
    (returns/make-credit-memo-from-return! *conn* "RMA-CM"
                                            {:external-id "CM-CUST-1"
                                             :issue-date #inst "2026-05-15"})
    (let [db (d/db *conn*)
          cm-eid (inv/by-external-id db "CM-CUST-1")
          cm (inv/pull-invoice db cm-eid)
          lines (inv/lines-of db cm-eid)]
      (testing "credit memo created with :type :credit-memo"
        (is (= :credit-memo (:invoice/type cm))))
      (testing "seller = acme, buyer = customer (org issues credit to customer)"
        (is (= "ACME-ORG" (-> cm :invoice/seller :partner/external-id)))
        (is (= "CUSTOMER" (-> cm :invoice/buyer :partner/external-id))))
      (testing "one line per return-item"
        (is (= 1 (count lines)))
        (is (= 3M (-> lines first :invoice-line/quantity)))
        (is (= 75M (-> lines first :invoice-line/amount)))
        (is (= :sales-revenue (-> lines first :invoice-line/gl-account-type))))
      (testing "return-item-billing junction created"
        (let [billing-count (d/q '[:find (count ?b) .
                                   :where [?b :return-item-billing/invoice-line]]
                                 db)]
          (is (= 1 billing-count)))))))

(deftest debit-memo-from-vendor-return
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-purchase-order! {})
        db (d/db *conn*)
        supplier (d/q '[:find ?p . :where [?p :partner/external-id "SUPPLIER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-VEND-CM"
                           :type :vendor
                           :from-party acme
                           :to-party supplier
                           :order order-eid
                           :supplier-rma "SUP-CM-99"
                           :items [{:order-item item-eid
                                    :product-id "WIDGET-A"
                                    :return-quantity 2M
                                    :return-price 12M
                                    :reason :defective
                                    :return-type :vendor-credit}]})
    (returns/accept-return! *conn* "RMA-VEND-CM")
    (returns/receive-return! *conn* "RMA-VEND-CM")
    (returns/make-credit-memo-from-return! *conn* "RMA-VEND-CM"
                                            {:external-id "DM-VEND-1"})
    (let [db (d/db *conn*)
          dm (inv/pull-invoice db "DM-VEND-1")
          lines (inv/lines-of db "DM-VEND-1")]
      (testing "debit memo created with :type :debit-memo"
        (is (= :debit-memo (:invoice/type dm))))
      (testing "seller = supplier, buyer = acme (supplier issues credit to us)"
        (is (= "SUPPLIER" (-> dm :invoice/seller :partner/external-id)))
        (is (= "ACME-ORG" (-> dm :invoice/buyer :partner/external-id))))
      (testing "GL routes to :inventory (PO line is :direct material)"
        ;; debit-memo lines dispatch on :order-item/category — the
        ;; reversal hits the same account the original purchase debited.
        (is (= :inventory (-> lines first :invoice-line/gl-account-type))))
      (testing "amount derived from return-quantity × return-price"
        (is (= 24M (-> lines first :invoice-line/amount)))))))

(deftest credit-memo-uses-received-quantity-when-different
  ;; Customer requests return of 5; we receive only 3 back; credit
  ;; memo should be for 3 (received), not 5 (requested).
  (seed-base!)
  (let [{:keys [order-eid item-eid]} (create-sales-order! {:external-id "SO-PARTIAL"})
        db (d/db *conn*)
        customer (d/q '[:find ?p . :where [?p :partner/external-id "CUSTOMER"]] db)
        acme (d/q '[:find ?p . :where [?p :partner/external-id "ACME-ORG"]] db)]
    (returns/make-return! *conn*
                          {:external-id "RMA-PARTIAL"
                           :type :customer
                           :from-party customer
                           :to-party acme
                           :order order-eid
                           :items [{:order-item item-eid
                                    :return-quantity 5M
                                    :return-price 25M
                                    :reason :customer-request
                                    :return-type :cash-refund}]})
    (returns/accept-return! *conn* "RMA-PARTIAL")
    (returns/receive-return! *conn* "RMA-PARTIAL")
    ;; Override received-quantity to 3 (customer only sent back 3 of 5)
    (let [return-eid (returns/by-external-id (d/db *conn*) "RMA-PARTIAL")
          item-eid (d/q '[:find ?i . :in $ ?r
                          :where [?i :return-item/return ?r]]
                        (d/db *conn*) return-eid)]
      (d/transact *conn* [{:db/id item-eid
                           :return-item/received-quantity 3M}]))
    (returns/make-credit-memo-from-return! *conn* "RMA-PARTIAL"
                                            {:external-id "CM-PARTIAL"})
    (let [lines (inv/lines-of (d/db *conn*) "CM-PARTIAL")]
      (testing "credit-memo line uses :received-quantity (3) not :return-quantity (5)"
        (is (= 3M (-> lines first :invoice-line/quantity)))
        (is (= 75M (-> lines first :invoice-line/amount)))))))

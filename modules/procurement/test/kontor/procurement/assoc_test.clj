(ns kontor.procurement.assoc-test
  "Drop-ship + substitute + replacement + upgrade tests for
   :order-item-assoc — ADR-042 commit 4/4."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.invoice.schema :as inv-schema]
            [kontor.partner.schema :as partner-schema]
            [kontor.procurement.assoc :as oia]
            [kontor.procurement.schema :as proc-schema]
            [kontor.sales.schema :as sales-schema]))

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
              [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
               {:kontor.entity/code "ACME" :kontor.entity/name "Acme Inc"
                :kontor.entity/kind :operating :kontor.entity/active true}
               {:kontor.partner/external-id "ACME-ORG" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Acme Inc"}
               {:kontor.partner/external-id "CUSTOMER" :kontor.partner/type :person
                :kontor.partner/status :enabled :kontor.partner/name "Customer Jane"}
               {:kontor.partner/external-id "SUPPLIER" :kontor.partner/type :org
                :kontor.partner/status :enabled :kontor.partner/name "Supplier Co"}]))

(defn- create-order!
  "Create an order + one item; returns {:order-eid :item-eid}."
  [{:keys [external-id type bill-from bill-to qty unit-price product-id]
    :or {type :sales bill-from "ACME-ORG" bill-to "CUSTOMER"
         qty 10M unit-price 25M product-id "WIDGET-A"}}]
  (d/transact *conn*
              [{:order/external-id external-id
                :order/type type
                :order/status :order.status/created
                :order/order-date #inst "2026-05-01"
                :order/entry-date #inst "2026-05-01"
                :order/currency [:kontor.commodity/symbol "EUR"]
                :order/bill-from-partner [:kontor.partner/external-id bill-from]
                :order/bill-to-partner [:kontor.partner/external-id bill-to]
                :order/entity [:kontor.entity/code "ACME"]}
               {:order-item/order [:order/external-id external-id]
                :order-item/seq-id "00001"
                :order-item/type :product
                :order-item/product-id product-id
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

;; ============================================================================
;; Drop-ship link
;; ============================================================================

(deftest drop-ship-so-to-po-link
  (seed-base!)
  (let [{so-item :item-eid} (create-order! {:external-id "SO-DS"
                                              :type :sales
                                              :bill-from "ACME-ORG"
                                              :bill-to "CUSTOMER"
                                              :qty 5M :unit-price 100M})
        {po-item :item-eid} (create-order! {:external-id "PO-DS"
                                              :type :purchase
                                              :bill-from "SUPPLIER"
                                              :bill-to "ACME-ORG"
                                              :qty 5M :unit-price 60M})]
    (oia/drop-ship-link! *conn*
                         {:so-item so-item
                          :po-item po-item
                          :quantity 5M
                          :note "supplier ships direct to customer"})
    (let [db (d/db *conn*)]
      (testing "drop-ship link queries from SO side"
        (let [linked-pos (oia/drop-ship-pos-for-so db so-item)]
          (is (= 1 (count linked-pos)))
          (is (= po-item (-> linked-pos first :db/id)))))
      (testing "drop-ship link queries from PO side"
        (let [linked-sos (oia/so-for-drop-ship-po db po-item)]
          (is (= 1 (count linked-sos)))
          (is (= so-item (-> linked-sos first :db/id)))))
      (testing "assoc carries the note"
        (let [assoc-row (-> (oia/assocs-from db so-item) first)]
          (is (= "supplier ships direct to customer"
                 (:order-item-assoc/note assoc-row))))))))

(deftest drop-ship-link-idempotent
  ;; Composite identity on [from, to, type] makes re-linking a no-op.
  (seed-base!)
  (let [{so-item :item-eid} (create-order! {:external-id "SO-IDEM"
                                              :type :sales :qty 1M})
        {po-item :item-eid} (create-order! {:external-id "PO-IDEM"
                                              :type :purchase
                                              :bill-from "SUPPLIER"
                                              :bill-to "ACME-ORG"
                                              :qty 1M})]
    (oia/drop-ship-link! *conn* {:so-item so-item :po-item po-item :quantity 1M})
    (oia/drop-ship-link! *conn* {:so-item so-item :po-item po-item :quantity 1M})
    (let [db (d/db *conn*)
          links (oia/assocs-from db so-item {:type :drop-shipment})]
      (is (= 1 (count links))
          "re-linking same (from, to, :drop-shipment) is no-op"))))

;; ============================================================================
;; Substitute link
;; ============================================================================

(deftest substitute-link
  (seed-base!)
  (let [{orig :item-eid} (create-order! {:external-id "SO-ORIG"
                                          :product-id "WIDGET-A"
                                          :qty 5M})
        {alt :item-eid} (create-order! {:external-id "SO-ALT"
                                         :product-id "WIDGET-B"
                                         :qty 5M})]
    (oia/substitute! *conn*
                     {:original-item orig
                      :substitute-item alt
                      :quantity 5M
                      :note "WIDGET-A out of stock; substituting WIDGET-B"})
    (let [db (d/db *conn*)
          links (oia/assocs-from db orig {:type :substitute})]
      (is (= 1 (count links)))
      (is (= :substitute (-> links first :order-item-assoc/type))))))

;; ============================================================================
;; Replacement link
;; ============================================================================

(deftest replacement-link-for-customer-return
  (seed-base!)
  (let [{orig :item-eid} (create-order! {:external-id "SO-ORIGINAL"
                                          :qty 3M})
        {replacement :item-eid} (create-order! {:external-id "SO-REPLACEMENT"
                                                  :qty 3M})]
    (oia/replacement-link! *conn*
                           {:original-item orig
                            :replacement-item replacement
                            :quantity 3M
                            :note "replacement for damaged goods"})
    (let [db (d/db *conn*)]
      (testing "forward query: original → replacement"
        (let [links (oia/assocs-from db orig {:type :replacement})]
          (is (= 1 (count links)))
          (is (= replacement (-> links first :order-item-assoc/to-order-item :db/id)))))
      (testing "reverse query: replacement ← original"
        (let [links (oia/assocs-to db replacement {:type :replacement})]
          (is (= 1 (count links)))
          (is (= orig (-> links first :order-item-assoc/from-order-item :db/id))))))))

;; ============================================================================
;; Upgrade link
;; ============================================================================

(deftest upgrade-link
  (seed-base!)
  (let [{basic :item-eid} (create-order! {:external-id "SO-BASIC"
                                           :product-id "WIDGET-BASIC"
                                           :qty 1M :unit-price 50M})
        {upgrade :item-eid} (create-order! {:external-id "SO-PREMIUM"
                                              :product-id "WIDGET-PREMIUM"
                                              :qty 1M :unit-price 100M})]
    (oia/upgrade-link! *conn*
                       {:from-item basic
                        :upgrade-item upgrade
                        :quantity 1M
                        :note "customer upgraded to premium tier"})
    (let [db (d/db *conn*)
          links (oia/assocs-from db basic {:type :upgrade})]
      (is (= 1 (count links)))
      (is (= :upgrade (-> links first :order-item-assoc/type))))))

;; ============================================================================
;; Multiple types on same pair
;; ============================================================================

(deftest different-types-coexist-on-same-pair
  ;; Composite identity is [from, to, type] — same (from, to) can
  ;; have multiple rows with different types.
  (seed-base!)
  (let [{a :item-eid} (create-order! {:external-id "SO-A"})
        {b :item-eid} (create-order! {:external-id "SO-B"})]
    (oia/link-orders! *conn* {:from-order-item a :to-order-item b
                               :type :substitute :quantity 1M})
    (oia/link-orders! *conn* {:from-order-item a :to-order-item b
                               :type :replacement :quantity 1M})
    (let [db (d/db *conn*)
          all-links (oia/assocs-from db a)]
      (is (= 2 (count all-links))
          "(A, B, :substitute) and (A, B, :replacement) are distinct rows")
      (is (= #{:substitute :replacement}
             (set (map :order-item-assoc/type all-links)))))))

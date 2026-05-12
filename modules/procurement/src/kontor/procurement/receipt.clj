(ns kontor.procurement.receipt
  "Receipt helpers — ADR-042.

   A `:receipt` is the physical-goods-received anchor for 3-way
   match. Each receipt belongs to a PO (`:receipt/order`) and has
   N `:receipt-item` rows, one per PO line received. Each item has
   a `:quantity-accepted` + `:quantity-rejected` split with reason.

   The state machine: nil → :pending → :accepted | :rejected. Post-
   inspection rejection (:accepted → :rejected) is also legal for
   quality-issue-found-later.

   Inventory integration via `post-receipt-with-inventory!` (loose
   coupling per ADR-042 design call): the helper composes the
   receipt creation + kontor.posting/plan-stock-move :direction :in
   into ONE atomic tx, routing the GR/IR clearing through ADR-041's
   :account-type-direction table."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :receipt/external-id ?xid]]
       db external-id))

(defn resolve-receipt
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-receipt
  "Pull a receipt with items + order + ship-group."
  [db spec]
  (when-let [eid (resolve-receipt db spec)]
    (d/pull db
            '[* {:receipt/order [:order/external-id :order/type]
                 :receipt/ship-group [:ship-group/seq-id]
                 :receipt/carrier-partner [:partner/external-id :partner/name]
                 :receipt/packing-slip-ref [:audit-doc/code :audit-doc/type]}]
            eid)))

(defn items-of
  "Pulled :receipt-item rows for a receipt."
  [db spec]
  (when-let [eid (resolve-receipt db spec)]
    (->> (d/q '[:find [?ri ...]
                :in $ ?r
                :where [?ri :receipt-item/receipt ?r]]
              db eid)
         (map #(d/pull db '[* {:receipt-item/order-item [:order-item/seq-id
                                                          :order-item/product-id]}] %))
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn make-receipt!
  "Create a `:receipt` + `:receipt-item` rows in `:pending` state.

   Required:
     :external-id, :order, :received-at, :items (vec of receipt-item maps).

   Each item map requires :order-item, :quantity-accepted (and may
   include :quantity-rejected + :rejection-reason + :lot + :unit-cost).

   Optional receipt-level: :ship-group, :received-by-uid, :facility-id,
   :carrier-partner, :tracking-number, :packing-slip-ref, :notes."
  [conn {:keys [external-id order items ship-group received-at
                received-by-uid facility-id carrier-partner
                tracking-number packing-slip-ref notes]}]
  (when-not external-id  (throw (ex-info ":external-id required" {})))
  (when-not order        (throw (ex-info ":order required" {})))
  (when-not (seq items)  (throw (ex-info "non-empty :items required" {})))
  (let [receipt-tempid "receipt-1"
        receipt-row (cond-> {:db/id receipt-tempid
                             :receipt/external-id external-id
                             :receipt/order order
                             :receipt/status :pending
                             :receipt/received-at (or received-at (java.util.Date.))}
                      ship-group       (assoc :receipt/ship-group ship-group)
                      received-by-uid  (assoc :receipt/received-by-uid received-by-uid)
                      facility-id      (assoc :receipt/facility-id facility-id)
                      carrier-partner  (assoc :receipt/carrier-partner carrier-partner)
                      tracking-number  (assoc :receipt/tracking-number tracking-number)
                      packing-slip-ref (assoc :receipt/packing-slip-ref packing-slip-ref)
                      notes            (assoc :receipt/notes notes))
        item-rows (mapv (fn [{:keys [order-item product-id quantity-accepted
                                     quantity-rejected rejection-reason
                                     lot unit-cost]}]
                          (cond-> {:receipt-item/receipt receipt-tempid
                                   :receipt-item/order-item order-item
                                   :receipt-item/quantity-accepted quantity-accepted}
                            product-id        (assoc :receipt-item/product-id product-id)
                            quantity-rejected (assoc :receipt-item/quantity-rejected quantity-rejected)
                            rejection-reason  (assoc :receipt-item/rejection-reason rejection-reason)
                            lot               (assoc :receipt-item/lot lot)
                            unit-cost         (assoc :receipt-item/unit-cost unit-cost)))
                        items)]
    (d/transact conn (vec (cons receipt-row item-rows)))))

(defn accept-receipt!
  "Transition :pending → :accepted (inspection pass)."
  ([conn receipt] (accept-receipt! conn receipt nil))
  ([conn receipt opts]
   (let [eid (resolve-receipt (d/db conn) receipt)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :receipt
                                       :facet :receipt/status
                                       :to :accepted}
                                      opts)))))

(defn reject-receipt!
  "Transition :pending → :rejected or :accepted → :rejected (post-
   inspection quality issue)."
  ([conn receipt] (reject-receipt! conn receipt nil))
  ([conn receipt opts]
   (let [eid (resolve-receipt (d/db conn) receipt)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :receipt
                                       :facet :receipt/status
                                       :to :rejected}
                                      opts)))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn receipts-of-order
  "All receipts against an order, pulled with items."
  [db order-eid]
  (->> (d/q '[:find [?r ...]
              :in $ ?o
              :where [?r :receipt/order ?o]]
            db order-eid)
       (map #(pull-receipt db %))
       vec))

(defn quantity-received-of-order-item
  "Sum of :receipt-item/quantity-accepted across all receipts for
   `order-item-eid`. Returns bigdec."
  [db order-item-eid]
  (or (d/q '[:find (sum ?q) .
             :with ?ri
             :in $ ?oi
             :where
             [?ri :receipt-item/order-item ?oi]
             [?ri :receipt-item/quantity-accepted ?q]]
           db order-item-eid)
      0M))

(defn quantity-rejected-of-order-item
  "Sum of :receipt-item/quantity-rejected across all receipts for
   `order-item-eid`. Returns bigdec."
  [db order-item-eid]
  (or (d/q '[:find (sum ?q) .
             :with ?ri
             :in $ ?oi
             :where
             [?ri :receipt-item/order-item ?oi]
             [?ri :receipt-item/quantity-rejected ?q]]
           db order-item-eid)
      0M))

(ns kontor.procurement.assoc
  "Order-item association helpers — ADR-042.

   `:order-item-assoc` is the OFBiz `OrderItemAssoc` pattern lifted
   verbatim: one junction entity, four `:type` discriminators
   covering drop-ship + substitute + replacement + upgrade.

   The drop-ship pattern: an SO line is fulfilled by a linked PO
   line; the supplier ships directly to the customer. The PO's
   `:kontor.ship-group/contact-mech` should reference the SO's
   `:kontor.ship-group/contact-mech` (NOT a copy) — bitemporal queries
   answer 'what was the customer address at PO time' for free.

   The substitute pattern: alt product offered when the original is
   unavailable; SO line → alt SO line (or PO line) with `:type
   :substitute`.

   The replacement pattern: a new order line issued in response to
   a customer return; original SO line ← new replacement-order SO
   line.

   The upgrade pattern: customer-driven upgrade to a higher SKU."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; Pull helpers
;; ============================================================================

(defn assocs-from
  "Pulled :order-item-assoc rows whose :from-order-item is the given
   order-item eid. Optionally filter by `:type` opt."
  ([db order-item-eid] (assocs-from db order-item-eid nil))
  ([db order-item-eid opts]
   (let [type-filter (:type opts)
         q (if type-filter
             (d/q '[:find [?a ...]
                    :in $ ?oi ?t
                    :where
                    [?a :kontor.procurement.order-item-assoc/from-order-item ?oi]
                    [?a :kontor.procurement.order-item-assoc/type ?t]]
                  db order-item-eid type-filter)
             (d/q '[:find [?a ...]
                    :in $ ?oi
                    :where [?a :kontor.procurement.order-item-assoc/from-order-item ?oi]]
                  db order-item-eid))]
     (->> q
          (map #(d/pull db
                        '[* {:kontor.procurement.order-item-assoc/to-order-item
                             [:db/id :kontor.sales.order-item/seq-id :kontor.sales.order-item/product-id
                              {:kontor.sales.order-item/order [:kontor.order/external-id :kontor.order/type]}]}]
                        %))
          vec))))

(defn assocs-to
  "Pulled :order-item-assoc rows whose :to-order-item is the given
   order-item eid (reverse direction)."
  ([db order-item-eid] (assocs-to db order-item-eid nil))
  ([db order-item-eid opts]
   (let [type-filter (:type opts)
         q (if type-filter
             (d/q '[:find [?a ...]
                    :in $ ?oi ?t
                    :where
                    [?a :kontor.procurement.order-item-assoc/to-order-item ?oi]
                    [?a :kontor.procurement.order-item-assoc/type ?t]]
                  db order-item-eid type-filter)
             (d/q '[:find [?a ...]
                    :in $ ?oi
                    :where [?a :kontor.procurement.order-item-assoc/to-order-item ?oi]]
                  db order-item-eid))]
     (->> q
          (map #(d/pull db
                        '[* {:kontor.procurement.order-item-assoc/from-order-item
                             [:db/id :kontor.sales.order-item/seq-id :kontor.sales.order-item/product-id
                              {:kontor.sales.order-item/order [:kontor.order/external-id :kontor.order/type]}]}]
                        %))
          vec))))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare link-orders-tx-data)

(defn link-orders-tx-data
  "Pure tx-data builder for `link-orders!` (ADR-068)."
  [_db {:keys [from-order-item to-order-item type quantity note]}]
  (when-not from-order-item (throw (ex-info ":from-order-item required" {})))
  (when-not to-order-item   (throw (ex-info ":to-order-item required" {})))
  (when-not type            (throw (ex-info ":type required" {})))
  (when-not quantity        (throw (ex-info ":quantity required" {})))
  (let [row (cond-> {:kontor.procurement.order-item-assoc/from-order-item from-order-item
                     :kontor.procurement.order-item-assoc/to-order-item to-order-item
                     :kontor.procurement.order-item-assoc/type type
                     :kontor.procurement.order-item-assoc/quantity quantity}
              note (assoc :kontor.procurement.order-item-assoc/note note))]
    [row]))

(defn link-orders!
  "Create an `:order-item-assoc` row linking from-order-item →
   to-order-item with the given `:type`. Routes through the gate
   (ADR-068).

   Required: :from-order-item, :to-order-item, :type, :quantity.
   Optional: :note.

   Idempotent via composite identity `[from-order-item, to-order-
   item, type]` — re-linking the same triple is a no-op.

   The pure tx-data builder is `link-orders-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (link-orders-tx-data (d/db conn) opts)))

(defn drop-ship-link!
  "Convenience for `:type :drop-shipment`. Links a sales-order-item
   to a purchase-order-item where the supplier ships directly to the
   customer.

   Required: :so-item (the SO line), :po-item (the PO line),
   :quantity."
  [conn {:keys [so-item po-item quantity note]}]
  (link-orders! conn {:from-order-item so-item
                      :to-order-item po-item
                      :type :drop-shipment
                      :quantity quantity
                      :note note}))

(defn substitute!
  "Convenience for `:type :substitute`. Links an order-item to an
   alt order-item offered as substitute."
  [conn {:keys [original-item substitute-item quantity note]}]
  (link-orders! conn {:from-order-item original-item
                      :to-order-item substitute-item
                      :type :substitute
                      :quantity quantity
                      :note note}))

(defn replacement-link!
  "Convenience for `:type :replacement`. Links an original returned
   order-item to a new replacement order-item (created in response
   to a customer return)."
  [conn {:keys [original-item replacement-item quantity note]}]
  (link-orders! conn {:from-order-item original-item
                      :to-order-item replacement-item
                      :type :replacement
                      :quantity quantity
                      :note note}))

(defn upgrade-link!
  "Convenience for `:type :upgrade`. Links an order-item to an
   upgrade order-item (higher SKU)."
  [conn {:keys [from-item upgrade-item quantity note]}]
  (link-orders! conn {:from-order-item from-item
                      :to-order-item upgrade-item
                      :type :upgrade
                      :quantity quantity
                      :note note}))

;; ============================================================================
;; Queries
;; ============================================================================

(defn drop-ship-pos-for-so
  "For a sales-order-item, return all PO lines it's drop-ship-linked
   to. Returns pulled :order-item maps."
  [db so-item-eid]
  (->> (assocs-from db so-item-eid {:type :drop-shipment})
       (map :kontor.procurement.order-item-assoc/to-order-item)
       vec))

(defn so-for-drop-ship-po
  "For a purchase-order-item, return the SO line(s) it's drop-ship-
   linked from. Returns pulled :order-item maps."
  [db po-item-eid]
  (->> (assocs-to db po-item-eid {:type :drop-shipment})
       (map :kontor.procurement.order-item-assoc/from-order-item)
       vec))

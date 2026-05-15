(ns kontor.procurement.acceptance
  "Service-acceptance helpers — ADR-042.

   `:service-acceptance` is parallel to `:receipt` for non-physical
   PO lines (`:order-item/requires-receipt? false`). It captures the
   acceptance event with an `:audit-doc` (ADR-038) evidence ref.

   The 3-way match query (`kontor.procurement.match/three-way-report`)
   uses `:service-acceptance/quantity-accepted` for service lines
   instead of `:receipt-item/quantity-accepted`."
  (:require [datahike.api :as d]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :service-acceptance/external-id ?xid]]
       db external-id))

(defn resolve-acceptance
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-acceptance
  [db spec]
  (when-let [eid (resolve-acceptance db spec)]
    (d/pull db
            '[* {:service-acceptance/order [:order/external-id]
                 :service-acceptance/order-item [:order-item/seq-id]
                 :service-acceptance/acceptance-evidence [:audit-doc/code :audit-doc/type]}]
            eid)))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare make-acceptance-tx-data)

(defn make-acceptance-tx-data
  "Pure tx-data builder for `make-acceptance!` (ADR-068)."
  [_db {:keys [external-id order order-item quantity-accepted
               accepted-at accepted-by-uid acceptance-evidence notes]}]
  (when-not external-id       (throw (ex-info ":external-id required" {})))
  (when-not order             (throw (ex-info ":order required" {})))
  (when-not order-item        (throw (ex-info ":order-item required" {})))
  (when-not quantity-accepted (throw (ex-info ":quantity-accepted required" {})))
  (let [row (cond-> {:service-acceptance/external-id external-id
                     :service-acceptance/order order
                     :service-acceptance/order-item order-item
                     :service-acceptance/quantity-accepted quantity-accepted
                     :service-acceptance/accepted-at (or accepted-at (java.util.Date.))}
              accepted-by-uid     (assoc :service-acceptance/accepted-by-uid accepted-by-uid)
              acceptance-evidence (assoc :service-acceptance/acceptance-evidence acceptance-evidence)
              notes               (assoc :service-acceptance/notes notes))]
    [row]))

(defn make-acceptance!
  "Create a `:service-acceptance` row. Routes through the gate
   (ADR-068).

   Required:
     :external-id, :order, :order-item, :quantity-accepted, :accepted-at.

   Optional: :accepted-by-uid, :acceptance-evidence (ref to :audit-doc), :notes.

   Returns the tx-report. The order-item should have
   :order-item/requires-receipt? false; the bridge doesn't enforce
   this but the match query routes service vs goods lines off the
   flag.

   The pure tx-data builder is `make-acceptance-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (make-acceptance-tx-data (d/db conn) opts)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn acceptances-of-order
  [db order-eid]
  (->> (d/q '[:find [?a ...]
              :in $ ?o
              :where [?a :service-acceptance/order ?o]]
            db order-eid)
       (map #(pull-acceptance db %))
       vec))

(defn quantity-accepted-of-order-item
  "Sum of :service-acceptance/quantity-accepted across all
   acceptances for `order-item-eid`."
  [db order-item-eid]
  (or (d/q '[:find (sum ?q) .
             :with ?a
             :in $ ?oi
             :where
             [?a :service-acceptance/order-item ?oi]
             [?a :service-acceptance/quantity-accepted ?q]]
           db order-item-eid)
      0M))

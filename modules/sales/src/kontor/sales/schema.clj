(ns kontor.sales.schema
  "Companion schema for `kontor-sales` — see ADR-035.

   Provides the order aggregate: :order/* header, :order-item/* lines,
   the :ship-group/* + :ship-group-assoc/* + :inv-reservation/* triple
   for fulfillment planning, :order-adjustment/* (multi-level via
   single :scope ref), and :order-role/* (partner role on order).

   Seeds the order + order-item state machines into the kernel
   :status-transition table (ADR-034).

   This namespace does NOT install the order→invoice bridge or the
   AcctgTrans posting flow — those live in kontor-invoice (ADR-036)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Order header
;; ============================================================================

(def ^:private order-attrs
  [{:db/ident       :order/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier."}

   {:db/ident       :order/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator: :sales | :purchase. kontor-
                     procurement extends the :purchase side with
                     requirement + receipt entities; both types
                     share the order machinery."}

   {:db/ident       :order/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Facet driven by ADR-034 :status-transition table.
                     :order.status/{created,approved,completed,hold,
                                    cancelled,rejected}"}

   {:db/ident       :order/order-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Date the order is placed (valid-time)."}

   {:db/ident       :order/entry-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Date the order entered the system (often the
                     same as :order/order-date but distinct for
                     backdated entries)."}

   {:db/ident       :order/currency
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default :commodity for monetary fields on the
                     order. Items can override per line via the
                     per-item unit-price-commodity attr."}

   {:db/ident       :order/bill-from-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Vendor / seller — for :sales, typically the
                     org running the system; for :purchase, the
                     supplier. Ref to :partner (ADR-033)."}

   {:db/ident       :order/bill-to-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer / buyer. Ref to :partner."}

   {:db/ident       :order/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Internal :entity (ADR-031) booking this order.
                     Used for multi-entity scoping."}

   {:db/ident       :order/grand-total
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized total in :order/currency. Recomputed
                     by the recalc pipeline whenever items or
                     adjustments change."}

   {:db/ident       :order/invoice-per-shipment?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false: one invoice on order completion.
                     True: one invoice per shipment (per OFBiz
                     invoicePerShipment flag)."}

   {:db/ident       :order/priority
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Higher number = higher reservation priority
                     when inventory is contested."}

   {:db/ident       :order/agreement-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional reference to a parent contract /
                     agreement / master order."}

   {:db/ident       :order/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Order item
;; ============================================================================

(def ^:private order-item-attrs
  [{:db/ident       :order-item/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-reference to :order."}

   {:db/ident       :order-item/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Sequence identifier within the order
                     (\"00001\", \"00002\", …)."}

   {:db/ident       :order-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:order-item/order :order-item/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :order-item/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":product | :service | :rental | :digital |
                     :subscription | … free-form."}

   {:db/ident       :order-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Consumer-supplied product reference. The kernel
                     ships no :product entity yet."}

   {:db/ident       :order-item/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/unit-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Price per unit, in :order/currency."}

   {:db/ident       :order-item/unit-list-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "MSRP — for displaying discount amount."}

   {:db/ident       :order-item/discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Line-level discount percentage (0.0–1.0)."}

   {:db/ident       :order-item/cancel-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity already cancelled. Effective quantity
                     is (quantity - cancel-quantity)."}

   {:db/ident       :order-item/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Facet driven by ADR-034. :order-item.status/
                     {created,approved,completed,cancelled,rejected}"}

   {:db/ident       :order-item/auto-reserve?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. False skips reservation at order
                     creation (rare — for back-to-back orders that
                     reserve later)."}

   {:db/ident       :order-item/reserve-after-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional. If set, reservation is deferred until
                     this date (typically by a downstream batch)."}

   {:db/ident       :order-item/estimated-ship-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/estimated-delivery-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/override-gl-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :account override. When set, the
                     invoice→ledger bridge routes revenue for this
                     line to this specific account."}

   {:db/ident       :order-item/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :analytic-account for cost-center
                     reporting (ADR-032 cost-center plan)."}])

;; ============================================================================
;; Ship group (fulfillment destination)
;; ============================================================================

(def ^:private ship-group-attrs
  [{:db/ident       :ship-group/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:ship-group/order :ship-group/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :ship-group/shipment-method-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":standard | :express | :overnight | :pickup | …"}

   {:db/ident       :ship-group/carrier-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Carrier :partner; should hold :partner-role
                     :carrier per ADR-033."}

   {:db/ident       :ship-group/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Source warehouse / facility. Consumer-supplied
                     opaque string; the kernel ships no :facility
                     entity."}

   {:db/ident       :ship-group/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ship-to address :contact-mech (ADR-033)."}

   {:db/ident       :ship-group/tracking-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/shipping-instructions
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/gift-message
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/may-split?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. False forbids partial shipment."}

   {:db/ident       :ship-group/is-gift?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/ship-after-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/ship-by-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/estimated-ship-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group/estimated-delivery-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Ship-group association (item ↔ destination)
;; ============================================================================

(def ^:private ship-group-assoc-attrs
  [{:db/ident       :ship-group-assoc/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group-assoc/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group-assoc/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group-assoc/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group-assoc/cancel-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ship-group-assoc/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:ship-group-assoc/order-item :ship-group-assoc/ship-group]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Inventory reservation (per-lot)
;; ============================================================================

(def ^:private inv-reservation-attrs
  [{:db/ident       :inv-reservation/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inv-reservation/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inv-reservation/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inv-reservation/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :lot entity holding the
                     reserved units."}

   {:db/ident       :inv-reservation/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inv-reservation/quantity-not-available
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Backorder count — quantity promised but not
                     currently available."}

   {:db/ident       :inv-reservation/reserve-order-enum
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":fifo | :lifo | :priority. Reservation
                     algorithm used to pick this lot."}

   {:db/ident       :inv-reservation/reserved-datetime
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the reservation was made (immutable)."}

   {:db/ident       :inv-reservation/promised-datetime
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Original promise to the customer (immutable)."}

   {:db/ident       :inv-reservation/current-promised-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Latest revised promise. Equals :promised-
                     datetime initially; pushed out on backorder."}

   {:db/ident       :inv-reservation/priority?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inv-reservation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:inv-reservation/order-item
                     :inv-reservation/ship-group
                     :inv-reservation/lot]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Order adjustment
;; ============================================================================

(def ^:private order-adjustment-attrs
  [{:db/ident       :order-adjustment/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Always set — back-ref to :order. Cross-cutting
                     with :scope, which carries the level."}

   {:db/ident       :order-adjustment/scope
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "ONE ref pointing at :order (header-level),
                     :order-item (line-level), or :ship-group
                     (destination-level). Sylius polymorphic pattern:
                     datahike doesn't care about target type."}

   {:db/ident       :order-adjustment/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":discount | :tax | :shipping | :surcharge |
                     :promotion | :tax-vat-included | … free-form."}

   {:db/ident       :order-adjustment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-adjustment/recurring-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "For subscription-style adjustments that recur
                     per billing period."}

   {:db/ident       :order-adjustment/source-percentage
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax rate as a decimal (e.g. 0.19 for 19% VAT)
                     when this is a tax adjustment."}

   {:db/ident       :order-adjustment/tax-auth-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax authority :partner (e.g. the German Finanzamt)."}

   {:db/ident       :order-adjustment/tax-auth-geo-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code for the tax authority."}

   {:db/ident       :order-adjustment/override-gl-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional explicit :account override for the
                     invoice→ledger bridge."}

   {:db/ident       :order-adjustment/include-in-tax?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. Controls whether THIS adjustment
                     is in the BASE for OTHER tax calculations."}

   {:db/ident       :order-adjustment/include-in-shipping?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. Same as include-in-tax? but for
                     shipping base."}

   {:db/ident       :order-adjustment/is-manual?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. Manual adjustments survive the
                     recalc pipeline; automatic ones are cleared
                     and reapplied each pass."}

   {:db/ident       :order-adjustment/neutral?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. Neutral adjustments do NOT
                     contribute to :order/grand-total. Used for
                     included-VAT-style informational rows."}

   {:db/ident       :order-adjustment/origin-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-reference to the source rule (promotion
                     code, tax-rate ID). For audit."}

   {:db/ident       :order-adjustment/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Order role
;; ============================================================================

(def ^:private order-role-attrs
  [{:db/ident       :order-role/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-role/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-role/role-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Per ADR-033 canonical vocabulary: :customer |
                     :supplier | :bill-to | :ship-to | :end-user |
                     :carrier | …"}

   {:db/ident       :order-role/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:order-role/order :order-role/partner :order-role/role-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Aggregate + install
;; ============================================================================

(def all
  "Full kontor-sales companion schema."
  (vec (concat order-attrs
               order-item-attrs
               ship-group-attrs
               ship-group-assoc-attrs
               inv-reservation-attrs
               order-adjustment-attrs
               order-role-attrs)))

(def status-transition-seeds
  "Order + order-item state machines, seeded into the kernel
   :status-transition table (ADR-034). Idempotent via composite
   identity tuple."
  [;; Order facet
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/nil
    :status-transition/to :order.status/created
    :status-transition/active true
    :status-transition/name "Create Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/created
    :status-transition/to :order.status/approved
    :status-transition/active true
    :status-transition/name "Approve Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/created
    :status-transition/to :order.status/hold
    :status-transition/active true
    :status-transition/name "Place Order On Hold"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/created
    :status-transition/to :order.status/cancelled
    :status-transition/active true
    :status-transition/name "Cancel Created Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/created
    :status-transition/to :order.status/rejected
    :status-transition/active true
    :status-transition/name "Reject Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/hold
    :status-transition/to :order.status/approved
    :status-transition/active true
    :status-transition/name "Release From Hold"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/hold
    :status-transition/to :order.status/cancelled
    :status-transition/active true
    :status-transition/name "Cancel Held Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/approved
    :status-transition/to :order.status/completed
    :status-transition/active true
    :status-transition/name "Complete Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/approved
    :status-transition/to :order.status/cancelled
    :status-transition/active true
    :status-transition/name "Cancel Approved Order"}
   {:status-transition/entity-type :order
    :status-transition/facet :order/status
    :status-transition/from :order.status/completed
    :status-transition/to :order.status/approved
    :status-transition/active true
    :status-transition/name "Re-open Completed Order"}

   ;; Order-item facet
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/nil
    :status-transition/to :order-item.status/created
    :status-transition/active true
    :status-transition/name "Create Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/created
    :status-transition/to :order-item.status/approved
    :status-transition/active true
    :status-transition/name "Approve Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/created
    :status-transition/to :order-item.status/cancelled
    :status-transition/active true
    :status-transition/name "Cancel Created Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/created
    :status-transition/to :order-item.status/rejected
    :status-transition/active true
    :status-transition/name "Reject Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/approved
    :status-transition/to :order-item.status/completed
    :status-transition/active true
    :status-transition/name "Complete Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/approved
    :status-transition/to :order-item.status/cancelled
    :status-transition/active true
    :status-transition/name "Cancel Approved Item"}
   {:status-transition/entity-type :order-item
    :status-transition/facet :order-item/status
    :status-transition/from :order-item.status/completed
    :status-transition/to :order-item.status/approved
    :status-transition/active true
    :status-transition/name "Re-open Completed Item"}])

(defn install!
  "Install the kontor-sales companion: schema + state-machine seeds.
   Idempotent — composite identities on :order/external-id, :order-
   item/identity, :status-transition/identity ensure re-runs are no-ops
   for unchanged data.

   Returns the resulting tx-report from the final transact."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds))

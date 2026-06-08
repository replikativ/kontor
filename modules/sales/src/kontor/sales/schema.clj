(ns kontor.sales.schema
  "Companion schema for `kontor-sales` — see ADR-035.

   Provides the order aggregate: :kontor.order/* header, :kontor.sales.order-item/* lines,
   the :kontor.ship-group/* + :kontor.ship-group-assoc/* + :kontor.inv-reservation/* triple
   for fulfillment planning, :kontor.order-adjustment/* (multi-level via
   single :scope ref), and :kontor.order-role/* (partner role on order).

   Seeds the order + order-item state machines into the kernel
   :status-transition table (ADR-034).

   This namespace does NOT install the order→invoice bridge or the
   AcctgTrans posting flow — those live in kontor-invoice (ADR-036)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Order header
;; ============================================================================

(def ^:private order-attrs
  [{:db/ident       :kontor.order/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier."}

   {:db/ident       :kontor.order/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator: :sales | :purchase. kontor-
                     procurement extends the :purchase side with
                     requirement + receipt entities; both types
                     share the order machinery."}

   {:db/ident       :kontor.order/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Facet driven by ADR-034 :status-transition table.
                     :order.status/{created,approved,completed,hold,
                                    cancelled,rejected}"}

   {:db/ident       :kontor.order/order-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Date the order is placed (valid-time)."}

   {:db/ident       :kontor.order/entry-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Date the order entered the system (often the
                     same as :kontor.order/order-date but distinct for
                     backdated entries)."}

   {:db/ident       :kontor.order/currency
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default :commodity for monetary fields on the
                     order. Items can override per line via the
                     per-item unit-price-commodity attr."}

   {:db/ident       :kontor.order/bill-from-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Vendor / seller — for :sales, typically the
                     org running the system; for :purchase, the
                     supplier. Ref to :partner (ADR-033)."}

   {:db/ident       :kontor.order/bill-to-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Customer / buyer. Ref to :partner."}

   {:db/ident       :kontor.order/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Internal :entity (ADR-031) booking this order.
                     Used for multi-entity scoping."}

   {:db/ident       :kontor.order/grand-total
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized total in :kontor.order/currency. Recomputed
                     by the recalc pipeline whenever items or
                     adjustments change."}

   {:db/ident       :kontor.order/invoice-per-shipment?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false: one invoice on order completion.
                     True: one invoice per shipment."}

   {:db/ident       :kontor.order/priority
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Higher number = higher reservation priority
                     when inventory is contested."}

   {:db/ident       :kontor.order/agreement-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional reference to a parent contract /
                     agreement / master order."}

   {:db/ident       :kontor.order/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.order/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Order item
;; ============================================================================

(def ^:private order-item-attrs
  [{:db/ident       :kontor.sales.order-item/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-reference to :order."}

   {:db/ident       :kontor.sales.order-item/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Sequence identifier within the order
                     (\"00001\", \"00002\", …)."}

   {:db/ident       :kontor.sales.order-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.sales.order-item/order :kontor.sales.order-item/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.sales.order-item/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":product | :service | :rental | :digital |
                     :subscription | … free-form."}

   {:db/ident       :kontor.sales.order-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Consumer-supplied product reference. The kernel
                     ships no :product entity yet."}

   {:db/ident       :kontor.sales.order-item/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.sales.order-item/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.sales.order-item/unit-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Price per unit, in :kontor.order/currency."}

   {:db/ident       :kontor.sales.order-item/unit-list-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "MSRP — for displaying discount amount."}

   {:db/ident       :kontor.sales.order-item/discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Line-level discount percentage (0.0–1.0)."}

   {:db/ident       :kontor.sales.order-item/cancel-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity already cancelled. Effective quantity
                     is (quantity - cancel-quantity)."}

   {:db/ident       :kontor.sales.order-item/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Facet driven by ADR-034. :order-item.status/
                     {created,approved,completed,cancelled,rejected}"}

   {:db/ident       :kontor.sales.order-item/auto-reserve?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. False skips reservation at order
                     creation (rare — for back-to-back orders that
                     reserve later)."}

   {:db/ident       :kontor.sales.order-item/reserve-after-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional. If set, reservation is deferred until
                     this date (typically by a downstream batch)."}

   {:db/ident       :kontor.sales.order-item/estimated-ship-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.sales.order-item/estimated-delivery-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.sales.order-item/override-gl-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :account override. When set, the
                     invoice→ledger bridge routes revenue for this
                     line to this specific account."}

   {:db/ident       :kontor.sales.order-item/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :analytic-account for cost-center
                     reporting (ADR-032 cost-center plan)."}])

;; ============================================================================
;; Ship group (fulfillment destination)
;; ============================================================================

(def ^:private ship-group-attrs
  [{:db/ident       :kontor.ship-group/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.ship-group/order :kontor.ship-group/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.ship-group/shipment-method-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":standard | :express | :overnight | :pickup | …"}

   {:db/ident       :kontor.ship-group/carrier-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Carrier :partner; should hold :partner-role
                     :carrier per ADR-033."}

   {:db/ident       :kontor.ship-group/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Source warehouse / facility. Consumer-supplied
                     opaque string; the kernel ships no :facility
                     entity."}

   {:db/ident       :kontor.ship-group/contact-mech
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ship-to address :contact-mech (ADR-033)."}

   {:db/ident       :kontor.ship-group/tracking-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/shipping-instructions
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/gift-message
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/may-split?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. False forbids partial shipment."}

   {:db/ident       :kontor.ship-group/is-gift?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/ship-after-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/ship-by-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/estimated-ship-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group/estimated-delivery-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Ship-group association (item ↔ destination)
;; ============================================================================

(def ^:private ship-group-assoc-attrs
  [{:db/ident       :kontor.ship-group-assoc/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group-assoc/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group-assoc/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group-assoc/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group-assoc/cancel-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ship-group-assoc/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.ship-group-assoc/order-item :kontor.ship-group-assoc/ship-group]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Inventory reservation (per :inventory-item bucket)
;; ============================================================================
;;
;; ADR-058 fix-up: a reservation binds to a physical :inventory-item
;; bucket (kontor-inventory), not a bare :lot. A single order line
;; fans out into one :inv-reservation per bucket it draws from. The
;; ref-attr is sales-owned; the :inventory-item it points at is
;; created by kontor-inventory; kontor.inventory.reservation/reserve!
;; is the writer.

(def ^:private inv-reservation-attrs
  [{:db/ident       :kontor.inv-reservation/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inv-reservation/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inv-reservation/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inv-reservation/inventory-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kontor-inventory :inventory-item
                     bucket the units are reserved against. The lot
                     (if any) is reachable via :kontor.inventory-item/lot
                     — ADR-058."}

   {:db/ident       :kontor.inv-reservation/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inv-reservation/quantity-not-available
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Backorder count — quantity promised but not
                     currently available."}

   {:db/ident       :kontor.inv-reservation/reserve-order-enum
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Physical picking strategy — which bucket to draw
                     from. #{:fifo-rec :lifo-rec :fifo-exp :lifo-exp
                     :greatest-cost :least-cost}. Distinct from
                     :kontor.valuation-book/cost-method (the COSTING method)
                     — picking strategy ≠ costing method."}

   {:db/ident       :kontor.inv-reservation/reserved-datetime
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the reservation was made (immutable)."}

   {:db/ident       :kontor.inv-reservation/promised-datetime
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Original promise to the customer (immutable)."}

   {:db/ident       :kontor.inv-reservation/current-promised-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Latest revised promise. Equals :promised-
                     datetime initially; pushed out on backorder."}

   {:db/ident       :kontor.inv-reservation/priority?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.inv-reservation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.inv-reservation/order-item
                     :kontor.inv-reservation/ship-group
                     :kontor.inv-reservation/inventory-item]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Order adjustment
;; ============================================================================

(def ^:private order-adjustment-attrs
  [{:db/ident       :kontor.order-adjustment/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Always set — back-ref to :order. Cross-cutting
                     with :scope, which carries the level."}

   {:db/ident       :kontor.order-adjustment/scope
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "ONE ref pointing at :order (header-level),
                     :order-item (line-level), or :ship-group
                     (destination-level). Polymorphic discriminator —
                     datahike doesn't care about target type."}

   {:db/ident       :kontor.order-adjustment/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":discount | :tax | :shipping | :surcharge |
                     :promotion | :tax-vat-included | … free-form."}

   {:db/ident       :kontor.order-adjustment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.order-adjustment/recurring-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "For subscription-style adjustments that recur
                     per billing period."}

   {:db/ident       :kontor.order-adjustment/source-percentage
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax rate as a decimal (e.g. 0.19 for 19% VAT)
                     when this is a tax adjustment."}

   {:db/ident       :kontor.order-adjustment/tax-auth-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax authority :partner (e.g. the German Finanzamt)."}

   {:db/ident       :kontor.order-adjustment/tax-auth-geo-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code for the tax authority."}

   {:db/ident       :kontor.order-adjustment/override-gl-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional explicit :account override for the
                     invoice→ledger bridge."}

   {:db/ident       :kontor.order-adjustment/include-in-tax?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. Controls whether THIS adjustment
                     is in the BASE for OTHER tax calculations."}

   {:db/ident       :kontor.order-adjustment/include-in-shipping?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. Same as include-in-tax? but for
                     shipping base."}

   {:db/ident       :kontor.order-adjustment/is-manual?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. Manual adjustments survive the
                     recalc pipeline; automatic ones are cleared
                     and reapplied each pass."}

   {:db/ident       :kontor.order-adjustment/neutral?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. Neutral adjustments do NOT
                     contribute to :kontor.order/grand-total. Used for
                     included-VAT-style informational rows."}

   {:db/ident       :kontor.order-adjustment/origin-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-reference to the source rule (promotion
                     code, tax-rate ID). For audit."}

   {:db/ident       :kontor.order-adjustment/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Order role
;; ============================================================================

(def ^:private order-role-attrs
  [{:db/ident       :kontor.order-role/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.order-role/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.order-role/role-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Per ADR-033 canonical vocabulary: :customer |
                     :supplier | :bill-to | :ship-to | :end-user |
                     :carrier | …"}

   {:db/ident       :kontor.order-role/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.order-role/order :kontor.order-role/partner :kontor.order-role/role-type]
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
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/nil
    :kontor.status-transition/to :order.status/created
    :kontor.status-transition/active true
    :kontor.status-transition/name "Create Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/created
    :kontor.status-transition/to :order.status/approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Approve Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/created
    :kontor.status-transition/to :order.status/hold
    :kontor.status-transition/active true
    :kontor.status-transition/name "Place Order On Hold"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/created
    :kontor.status-transition/to :order.status/cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Created Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/created
    :kontor.status-transition/to :order.status/rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Reject Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/hold
    :kontor.status-transition/to :order.status/approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Release From Hold"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/hold
    :kontor.status-transition/to :order.status/cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Held Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/approved
    :kontor.status-transition/to :order.status/completed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Complete Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/approved
    :kontor.status-transition/to :order.status/cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Approved Order"}
   {:kontor.status-transition/entity-type :order
    :kontor.status-transition/facet :kontor.order/status
    :kontor.status-transition/from :order.status/completed
    :kontor.status-transition/to :order.status/approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Re-open Completed Order"}

   ;; Order-item facet
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/nil
    :kontor.status-transition/to :order-item.status/created
    :kontor.status-transition/active true
    :kontor.status-transition/name "Create Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/created
    :kontor.status-transition/to :order-item.status/approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Approve Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/created
    :kontor.status-transition/to :order-item.status/cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Created Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/created
    :kontor.status-transition/to :order-item.status/rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Reject Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/approved
    :kontor.status-transition/to :order-item.status/completed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Complete Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/approved
    :kontor.status-transition/to :order-item.status/cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Approved Item"}
   {:kontor.status-transition/entity-type :order-item
    :kontor.status-transition/facet :kontor.sales.order-item/status
    :kontor.status-transition/from :order-item.status/completed
    :kontor.status-transition/to :order-item.status/approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Re-open Completed Item"}])

(defn install!
  "Install the kontor-sales companion: schema + state-machine seeds.
   Idempotent — composite identities on :kontor.order/external-id, :order-
   item/identity, :kontor.status-transition/identity ensure re-runs are no-ops
   for unchanged data.

   Returns `conn` for composition."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds)
  conn)

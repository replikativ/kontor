(ns kontor.procurement.schema
  "Companion schema for `kontor-procurement` — see ADR-042.

   Forward flow: :requirement + :requirement-commitment + :receipt +
   :receipt-item + :receipt-invoice-billing + :service-acceptance.

   Reverse flow: :return + :return-item + :return-response +
   :return-item-billing.

   Cross-cutting: :order-item-assoc (drop-ship + substitute +
   replacement + upgrade) + :match-tolerance + extensions to
   :order-item (requires-receipt?, category) + :invoice
   (match-status).

   This namespace installs the procurement schema on top of:
     - kernel (ADRs 001-034, 037-041)
     - kontor-partner (ADR-033)
     - kontor-sales (ADR-035)
     - kontor-invoice (ADR-036, ADR-040)

   Seeds requirement + receipt + match-status + return state machines
   into ADR-034's :status-transition table and procurement-specific
   GL routing into ADR-041's :account-type-direction table."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Extensions to existing schemas
;; ============================================================================

(def ^:private order-item-ext-attrs
  ;; Stage K extensions to :order-item (originally ADR-035).
  [{:db/ident       :kontor.procurement.order-item/requires-receipt?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. When false, this line is a service
                     line — 3-way match degenerates to 2-way and uses
                     :service-acceptance instead of :receipt. ADR-042."}

   {:db/ident       :kontor.procurement.order-item/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":direct | :indirect | :services | :asset.
                     Drives :kontor.invoice-line/gl-account-type dispatch
                     in kontor.invoice.bridge for :kontor.order/type :purchase.
                     Direct = goods for resale or raw materials;
                     indirect = office supplies; services = consulting/
                     legal; asset = CapEx (fixed assets). ADR-042."}])

(def ^:private invoice-ext-attrs
  ;; Stage K extensions to :invoice (originally ADR-036/ADR-040).
  [{:db/ident       :kontor.invoice/match-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "3-way match state. Driven by ADR-034 :status-
                     transition table. Values: :auto-matched |
                     :exception-price | :exception-qty |
                     :exception-missing-receipt |
                     :exception-missing-po | :manual-approved |
                     :disputed | :cleared. ADR-042."}])

;; ============================================================================
;; Requirement entities
;; ============================================================================

(def ^:private requirement-attrs
  [{:db/ident       :kontor.requirement/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.requirement/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":product | :transfer | :production | :service |
                     :asset-maint. Discriminator from OFBiz
                     RequirementType. ADR-042."}

   {:db/ident       :kontor.requirement/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "State-machine facet (ADR-034). :proposed |
                     :approved | :ordered | :received | :rejected |
                     :cancelled."}

   {:db/ident       :kontor.requirement/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Consumer-supplied product ref (kernel ships no
                     :product entity yet)."}

   {:db/ident       :kontor.requirement/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/uom
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Unit of measure — :each | :kg | :hour | etc."}

   {:db/ident       :kontor.requirement/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination facility (consumer-supplied opaque
                     string; no :facility kernel entity yet)."}

   {:db/ident       :kontor.requirement/facility-to-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination for :type :transfer requirements
                     (origin is :facility-id)."}

   {:db/ident       :kontor.requirement/required-by-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/estimated-budget
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/budget-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope (ADR-031)."}

   {:db/ident       :kontor.requirement/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :analytic-account for cost-center
                     reporting (ADR-032)."}

   {:db/ident       :kontor.requirement/justification
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Replaces OFBiz `useCase` + `reason` — free-text."}

   {:db/ident       :kontor.requirement/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement/created-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def ^:private requirement-commitment-attrs
  ;; ADR-042: many-to-many junction PR ↔ PO line (OFBiz
  ;; OrderRequirementCommitment pattern).
  [{:db/ident       :kontor.requirement-commitment/requirement
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement-commitment/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement-commitment/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity of the requirement covered by this PO
                     line — many small requirements roll into one PO
                     line, or one requirement splits across N POs."}

   {:db/ident       :kontor.requirement-commitment/committed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.requirement-commitment/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.requirement-commitment/requirement
                     :kontor.requirement-commitment/order-item]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Receipt entities
;; ============================================================================

(def ^:private receipt-attrs
  [{:db/ident       :kontor.receipt/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.receipt/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO this receipt is against."}

   {:db/ident       :kontor.receipt/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :ship-group for multi-destination POs."}

   {:db/ident       :kontor.receipt/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending | :accepted | :rejected.
                     State-machine driven (ADR-034)."}

   {:db/ident       :kontor.receipt/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/received-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/inspector-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/inspected-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/packing-slip-ref
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — packing slip image
                     / shipping label / bill of lading."}

   {:db/ident       :kontor.receipt/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt/carrier-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Carrier :partner; should hold :partner-role
                     :carrier per ADR-033."}

   {:db/ident       :kontor.receipt/tracking-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private receipt-item-attrs
  [{:db/ident       :kontor.receipt-item/receipt
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt-item/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO line received."}

   {:db/ident       :kontor.receipt-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Denorm of :kontor.sales.order-item/product-id."}

   {:db/ident       :kontor.receipt-item/quantity-accepted
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity accepted into inventory."}

   {:db/ident       :kontor.receipt-item/quantity-rejected
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity rejected with reason. Routes to
                     :receive-reject-loss GL (kontor improvement over
                     OFBiz which silently drops). ADR-042."}

   {:db/ident       :kontor.receipt-item/rejection-reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":damaged | :wrong-item | :expired |
                     :quantity-mismatch | :quality-fail."}

   {:db/ident       :kontor.receipt-item/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :lot ref. Per :kontor.valuation-book/lot-
                     required? policy (ADR-027)."}

   {:db/ident       :kontor.receipt-item/unit-cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Actual cost at receipt; may differ from PO line.
                     Difference posts as :price-variance."}

   {:db/ident       :kontor.receipt-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.receipt-item/receipt :kontor.receipt-item/order-item]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private receipt-invoice-billing-attrs
  ;; ADR-042: junction for 3-way match — :receipt ↔ :invoice-line
  ;; (mirror of ADR-036's :order-item-billing).
  [{:db/ident       :kontor.receipt-invoice-billing/receipt
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt-invoice-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt-invoice-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.receipt-invoice-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.receipt-invoice-billing/receipt
                     :kontor.receipt-invoice-billing/invoice-line]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Service acceptance (parallel to :receipt for non-physical lines)
;; ============================================================================

(def ^:private service-acceptance-attrs
  [{:db/ident       :kontor.service-acceptance/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.service-acceptance/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.service-acceptance/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO line accepted. Must have
                     :kontor.procurement.order-item/requires-receipt? false."}

   {:db/ident       :kontor.service-acceptance/quantity-accepted
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Hours / days / deliverable count. Replaces
                     :kontor.receipt-item/quantity-accepted in the 3-way
                     match query for service lines."}

   {:db/ident       :kontor.service-acceptance/accepted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.service-acceptance/accepted-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.service-acceptance/acceptance-evidence
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — Slack thread, email,
                     signed milestone PDF, etc."}

   {:db/ident       :kontor.service-acceptance/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.service-acceptance/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.service-acceptance/order-item
                     :kontor.service-acceptance/accepted-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Order-item-assoc (drop-ship + substitute + replacement + upgrade)
;; ============================================================================

(def ^:private order-item-assoc-attrs
  ;; ADR-042: OFBiz OrderItemAssoc pattern lifted verbatim.
  [{:db/ident       :kontor.procurement.order-item-assoc/from-order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.procurement.order-item-assoc/to-order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.procurement.order-item-assoc/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":drop-shipment | :substitute | :replacement |
                     :upgrade.

                     :drop-shipment — SO line fulfilled by a linked PO
                     line; supplier ships directly to customer. The
                     PO's :kontor.ship-group/contact-mech should ref the SO's
                     :kontor.ship-group/contact-mech (not copy) for
                     bitemporal correctness.

                     :substitute — alt product offered for unavailable
                     original (same-or-similar SKU).

                     :replacement — replacement-order line for a
                     customer return (links the new SO line back to
                     the original).

                     :upgrade — upgrade to a higher SKU."}

   {:db/ident       :kontor.procurement.order-item-assoc/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.procurement.order-item-assoc/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.procurement.order-item-assoc/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.procurement.order-item-assoc/from-order-item
                     :kontor.procurement.order-item-assoc/to-order-item
                     :kontor.procurement.order-item-assoc/type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Match tolerance (per-(entity, supplier?, product?) bands)
;; ============================================================================

(def ^:private match-tolerance-attrs
  [{:db/ident       :kontor.match-tolerance/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required; tenant scope (ADR-031)."}

   {:db/ident       :kontor.match-tolerance/supplier
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :partner ref. Nil = entity-wide default."}

   {:db/ident       :kontor.match-tolerance/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional consumer-supplied product ref. Nil =
                     supplier-wide default (when supplier is set) or
                     entity-wide (when supplier is also nil)."}

   {:db/ident       :kontor.match-tolerance/qty-pct-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "E.g., 0.05M = 5% over-receipt allowed."}

   {:db/ident       :kontor.match-tolerance/qty-abs-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Absolute unit allowance (whichever is greater
                     wins vs pct)."}

   {:db/ident       :kontor.match-tolerance/price-pct-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.match-tolerance/price-abs-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.match-tolerance/price-abs-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.match-tolerance/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.match-tolerance/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.match-tolerance/entity
                     :kontor.match-tolerance/supplier
                     :kontor.match-tolerance/product-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Return entities (RTV + customer return; one shape, role inverted)
;; ============================================================================

(def ^:private return-attrs
  [{:db/ident       :kontor.return/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.return/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":customer | :vendor. Discriminator (OFBiz
                     ReturnHeader.returnHeaderTypeId pattern)."}

   {:db/ident       :kontor.return/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":requested | :accepted | :received | :completed |
                     :rejected | :cancelled. State-machine (ADR-034)."}

   {:db/ident       :kontor.return/from-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :customer → the customer; for :vendor →
                     ourselves (the org returning)."}

   {:db/ident       :kontor.return/to-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :customer → ourselves; for :vendor → the
                     supplier."}

   {:db/ident       :kontor.return/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The original order being returned (SO for
                     :customer, PO for :vendor)."}

   {:db/ident       :kontor.return/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope (ADR-031)."}

   {:db/ident       :kontor.return/destination-facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Where the returned goods land."}

   {:db/ident       :kontor.return/supplier-rma
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Vendor's RMA number (for :vendor returns)."}

   {:db/ident       :kontor.return/entry-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — customer email,
                     defect photo, etc."}])

(def ^:private return-item-attrs
  [{:db/ident       :kontor.return-item/return
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The original line being returned."}

   {:db/ident       :kontor.return-item/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Denorm."}

   {:db/ident       :kontor.return-item/return-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity requested for return."}

   {:db/ident       :kontor.return-item/received-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity actually received (may differ)."}

   {:db/ident       :kontor.return-item/return-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Price at return time; may differ from invoice."}

   {:db/ident       :kontor.return-item/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":damaged | :defective | :wrong-item |
                     :not-as-described | :no-longer-needed |
                     :late-delivery | :customer-request."}

   {:db/ident       :kontor.return-item/return-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":store-credit | :cash-refund | :exchange |
                     :vendor-credit."}

   {:db/ident       :kontor.return-item/expected-disposition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":available | :defective | :scrap |
                     :return-to-supplier."}

   {:db/ident       :kontor.return-item/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item/response
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.return-item/return :kontor.return-item/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private return-response-attrs
  [{:db/ident       :kontor.return-response/return-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "1:1 with :return-item."}

   {:db/ident       :kontor.return-response/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":replacement-order | :credit-memo | :cash-refund |
                     :billing-account-credit."}

   {:db/ident       :kontor.return-response/replacement-order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "New SO/PO created in response."}

   {:db/ident       :kontor.return-response/credit-memo
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Kernel :invoice with :kontor.invoice/type :credit-memo
                     or :debit-memo."}

   {:db/ident       :kontor.return-response/payment-ref
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "For refunds processed via external payment
                     processor."}

   {:db/ident       :kontor.return-response/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-response/amount-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-response/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private return-item-billing-attrs
  ;; ADR-042: junction for credit memo linkage to return-item
  ;; (mirror of :order-item-billing for forward flow, :receipt-
  ;; invoice-billing for receipts).
  [{:db/ident       :kontor.return-item-billing/return-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Credit-memo line."}

   {:db/ident       :kontor.return-item-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item-billing/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.return-item-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.return-item-billing/return-item
                     :kontor.return-item-billing/invoice-line]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Aggregate + seeds
;; ============================================================================

(def all
  "Full kontor-procurement companion schema."
  (vec (concat order-item-ext-attrs
               invoice-ext-attrs
               requirement-attrs
               requirement-commitment-attrs
               receipt-attrs
               receipt-item-attrs
               receipt-invoice-billing-attrs
               service-acceptance-attrs
               order-item-assoc-attrs
               match-tolerance-attrs
               return-attrs
               return-item-attrs
               return-response-attrs
               return-item-billing-attrs)))

(def status-transition-seeds
  "Requirement + receipt + invoice match-status + return state
   machines, seeded into the kernel :status-transition table (ADR-034)."
  [;; --- :kontor.requirement/status ---------------------------------------
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :proposed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Create Requirement"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :proposed
    :kontor.status-transition/to :approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Approve Requirement"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :proposed
    :kontor.status-transition/to :rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Reject Requirement"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :proposed
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Proposed Requirement"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :approved
    :kontor.status-transition/to :ordered
    :kontor.status-transition/active true
    :kontor.status-transition/name "Commit to PO"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :approved
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Approved Requirement"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :approved
    :kontor.status-transition/to :proposed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Revise Approval"}
   {:kontor.status-transition/entity-type :requirement
    :kontor.status-transition/facet :kontor.requirement/status
    :kontor.status-transition/from :ordered
    :kontor.status-transition/to :received
    :kontor.status-transition/active true
    :kontor.status-transition/name "Auto-Promote on Full Receipt"}

   ;; --- :kontor.receipt/status -------------------------------------------
   {:kontor.status-transition/entity-type :receipt
    :kontor.status-transition/facet :kontor.receipt/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :pending
    :kontor.status-transition/active true
    :kontor.status-transition/name "Create Receipt"}
   {:kontor.status-transition/entity-type :receipt
    :kontor.status-transition/facet :kontor.receipt/status
    :kontor.status-transition/from :pending
    :kontor.status-transition/to :accepted
    :kontor.status-transition/active true
    :kontor.status-transition/name "Inspection Pass"}
   {:kontor.status-transition/entity-type :receipt
    :kontor.status-transition/facet :kontor.receipt/status
    :kontor.status-transition/from :pending
    :kontor.status-transition/to :rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Inspection Fail"}
   {:kontor.status-transition/entity-type :receipt
    :kontor.status-transition/facet :kontor.receipt/status
    :kontor.status-transition/from :accepted
    :kontor.status-transition/to :rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Post-Inspection Reject"}

   ;; --- :kontor.invoice/match-status -------------------------------------
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :auto-matched
    :kontor.status-transition/active true
    :kontor.status-transition/name "Auto-Match (within tolerance)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :exception-price
    :kontor.status-transition/active true
    :kontor.status-transition/name "Flag Price Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :exception-qty
    :kontor.status-transition/active true
    :kontor.status-transition/name "Flag Quantity Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :exception-missing-receipt
    :kontor.status-transition/active true
    :kontor.status-transition/name "Flag Missing Receipt"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :exception-missing-po
    :kontor.status-transition/active true
    :kontor.status-transition/name "Flag Missing PO"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-price
    :kontor.status-transition/to :manual-approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Override Price Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-qty
    :kontor.status-transition/to :manual-approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Override Qty Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-missing-receipt
    :kontor.status-transition/to :manual-approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Override Missing Receipt"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-missing-po
    :kontor.status-transition/to :manual-approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Override Missing PO"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-price
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Price Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-qty
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Qty Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-missing-receipt
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Missing-Receipt Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :exception-missing-po
    :kontor.status-transition/to :disputed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Dispute Missing-PO Exception"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :manual-approved
    :kontor.status-transition/active true
    :kontor.status-transition/name "Resolve Dispute"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :disputed
    :kontor.status-transition/to :auto-matched
    :kontor.status-transition/active true
    :kontor.status-transition/name "Re-Match After Vendor Correction"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :auto-matched
    :kontor.status-transition/to :cleared
    :kontor.status-transition/active true
    :kontor.status-transition/name "Clear (post-payment)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/match-status
    :kontor.status-transition/from :manual-approved
    :kontor.status-transition/to :cleared
    :kontor.status-transition/active true
    :kontor.status-transition/name "Clear after Manual Approval (post-payment)"}

   ;; --- :kontor.return/status --------------------------------------------
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :requested
    :kontor.status-transition/active true
    :kontor.status-transition/name "Request Return"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :requested
    :kontor.status-transition/to :accepted
    :kontor.status-transition/active true
    :kontor.status-transition/name "Accept RMA"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :requested
    :kontor.status-transition/to :rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Deny RMA"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :requested
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Requested Return"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :accepted
    :kontor.status-transition/to :received
    :kontor.status-transition/active true
    :kontor.status-transition/name "Receive Returned Goods"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :accepted
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Accepted Return"}
   {:kontor.status-transition/entity-type :return
    :kontor.status-transition/facet :kontor.return/status
    :kontor.status-transition/from :received
    :kontor.status-transition/to :completed
    :kontor.status-transition/active true
    :kontor.status-transition/name "Complete Return (refund/replacement issued)"}])

(def account-type-direction-seeds
  "Procurement-specific :account-type-direction rows (ADR-041
   table). Extends the default-direction-for fallback map in
   kontor.invoice.posting.

   GR/IR clearing direction note (P0-1 fix): a :purchase invoice line
   on :gr-ir-clearing DEBITS the clearing account, reversing the
   receipt's credit. The receipt itself hardcodes the credit direction
   in kontor.posting/receipt-postings; the invoice-side direction is
   what this table feeds. So both legs net to zero on the GR/IR
   account once the invoice posts."
  [{:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :gr-ir-clearing
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :goods-receipt-accrual
    :kontor.account-type-direction/direction :credit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :landed-cost-variance
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :price-variance
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :exchange-variance
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :receive-reject-loss
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :prepaid-expense
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}
   {:kontor.account-type-direction/invoice-type :purchase
    :kontor.account-type-direction/account-type :asset-acquisition
    :kontor.account-type-direction/direction :debit
    :kontor.account-type-direction/active true}])

(defn install!
  "Install the kontor-procurement companion schema + state-machine
   seeds + procurement :account-type-direction seeds. Idempotent."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds)
  (d/transact conn account-type-direction-seeds))

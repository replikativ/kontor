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
  [{:db/ident       :order-item/requires-receipt?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default true. When false, this line is a service
                     line — 3-way match degenerates to 2-way and uses
                     :service-acceptance instead of :receipt. ADR-042."}

   {:db/ident       :order-item/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":direct | :indirect | :services | :asset.
                     Drives :invoice-line/gl-account-type dispatch
                     in kontor.invoice.bridge for :order/type :purchase.
                     Direct = goods for resale or raw materials;
                     indirect = office supplies; services = consulting/
                     legal; asset = CapEx (fixed assets). ADR-042."}])

(def ^:private invoice-ext-attrs
  ;; Stage K extensions to :invoice (originally ADR-036/ADR-040).
  [{:db/ident       :invoice/match-status
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
  [{:db/ident       :requirement/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :requirement/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":product | :transfer | :production | :service |
                     :asset-maint. Discriminator from OFBiz
                     RequirementType. ADR-042."}

   {:db/ident       :requirement/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "State-machine facet (ADR-034). :proposed |
                     :approved | :ordered | :received | :rejected |
                     :cancelled."}

   {:db/ident       :requirement/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Consumer-supplied product ref (kernel ships no
                     :product entity yet)."}

   {:db/ident       :requirement/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/uom
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Unit of measure — :each | :kg | :hour | etc."}

   {:db/ident       :requirement/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination facility (consumer-supplied opaque
                     string; no :facility kernel entity yet)."}

   {:db/ident       :requirement/facility-to-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Destination for :type :transfer requirements
                     (origin is :facility-id)."}

   {:db/ident       :requirement/required-by-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/estimated-budget
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/budget-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope (ADR-031)."}

   {:db/ident       :requirement/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :analytic-account for cost-center
                     reporting (ADR-032)."}

   {:db/ident       :requirement/justification
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Replaces OFBiz `useCase` + `reason` — free-text."}

   {:db/ident       :requirement/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement/created-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(def ^:private requirement-commitment-attrs
  ;; ADR-042: many-to-many junction PR ↔ PO line (OFBiz
  ;; OrderRequirementCommitment pattern).
  [{:db/ident       :requirement-commitment/requirement
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement-commitment/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement-commitment/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity of the requirement covered by this PO
                     line — many small requirements roll into one PO
                     line, or one requirement splits across N POs."}

   {:db/ident       :requirement-commitment/committed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :requirement-commitment/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:requirement-commitment/requirement
                     :requirement-commitment/order-item]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Receipt entities
;; ============================================================================

(def ^:private receipt-attrs
  [{:db/ident       :receipt/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :receipt/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO this receipt is against."}

   {:db/ident       :receipt/ship-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :ship-group for multi-destination POs."}

   {:db/ident       :receipt/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending | :accepted | :rejected.
                     State-machine driven (ADR-034)."}

   {:db/ident       :receipt/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/received-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/inspector-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/inspected-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/packing-slip-ref
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — packing slip image
                     / shipping label / bill of lading."}

   {:db/ident       :receipt/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt/carrier-partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Carrier :partner; should hold :partner-role
                     :carrier per ADR-033."}

   {:db/ident       :receipt/tracking-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private receipt-item-attrs
  [{:db/ident       :receipt-item/receipt
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt-item/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO line received."}

   {:db/ident       :receipt-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Denorm of :order-item/product-id."}

   {:db/ident       :receipt-item/quantity-accepted
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity accepted into inventory."}

   {:db/ident       :receipt-item/quantity-rejected
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity rejected with reason. Routes to
                     :receive-reject-loss GL (kontor improvement over
                     OFBiz which silently drops). ADR-042."}

   {:db/ident       :receipt-item/rejection-reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":damaged | :wrong-item | :expired |
                     :quantity-mismatch | :quality-fail."}

   {:db/ident       :receipt-item/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :lot ref. Per :valuation-book/lot-
                     required? policy (ADR-027)."}

   {:db/ident       :receipt-item/unit-cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Actual cost at receipt; may differ from PO line.
                     Difference posts as :price-variance."}

   {:db/ident       :receipt-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:receipt-item/receipt :receipt-item/order-item]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private receipt-invoice-billing-attrs
  ;; ADR-042: junction for 3-way match — :receipt ↔ :invoice-line
  ;; (mirror of ADR-036's :order-item-billing).
  [{:db/ident       :receipt-invoice-billing/receipt
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt-invoice-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt-invoice-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :receipt-invoice-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:receipt-invoice-billing/receipt
                     :receipt-invoice-billing/invoice-line]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Service acceptance (parallel to :receipt for non-physical lines)
;; ============================================================================

(def ^:private service-acceptance-attrs
  [{:db/ident       :service-acceptance/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :service-acceptance/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :service-acceptance/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The PO line accepted. Must have
                     :order-item/requires-receipt? false."}

   {:db/ident       :service-acceptance/quantity-accepted
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Hours / days / deliverable count. Replaces
                     :receipt-item/quantity-accepted in the 3-way
                     match query for service lines."}

   {:db/ident       :service-acceptance/accepted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :service-acceptance/accepted-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :service-acceptance/acceptance-evidence
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — Slack thread, email,
                     signed milestone PDF, etc."}

   {:db/ident       :service-acceptance/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :service-acceptance/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:service-acceptance/order-item
                     :service-acceptance/accepted-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Order-item-assoc (drop-ship + substitute + replacement + upgrade)
;; ============================================================================

(def ^:private order-item-assoc-attrs
  ;; ADR-042: OFBiz OrderItemAssoc pattern lifted verbatim.
  [{:db/ident       :order-item-assoc/from-order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item-assoc/to-order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item-assoc/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":drop-shipment | :substitute | :replacement |
                     :upgrade.

                     :drop-shipment — SO line fulfilled by a linked PO
                     line; supplier ships directly to customer. The
                     PO's :ship-group/contact-mech should ref the SO's
                     :ship-group/contact-mech (not copy) for
                     bitemporal correctness.

                     :substitute — alt product offered for unavailable
                     original (same-or-similar SKU).

                     :replacement — replacement-order line for a
                     customer return (links the new SO line back to
                     the original).

                     :upgrade — upgrade to a higher SKU."}

   {:db/ident       :order-item-assoc/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item-assoc/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item-assoc/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:order-item-assoc/from-order-item
                     :order-item-assoc/to-order-item
                     :order-item-assoc/type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Match tolerance (per-(entity, supplier?, product?) bands)
;; ============================================================================

(def ^:private match-tolerance-attrs
  [{:db/ident       :match-tolerance/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required; tenant scope (ADR-031)."}

   {:db/ident       :match-tolerance/supplier
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :partner ref. Nil = entity-wide default."}

   {:db/ident       :match-tolerance/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional consumer-supplied product ref. Nil =
                     supplier-wide default (when supplier is set) or
                     entity-wide (when supplier is also nil)."}

   {:db/ident       :match-tolerance/qty-pct-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "E.g., 0.05M = 5% over-receipt allowed."}

   {:db/ident       :match-tolerance/qty-abs-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Absolute unit allowance (whichever is greater
                     wins vs pct)."}

   {:db/ident       :match-tolerance/price-pct-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :match-tolerance/price-abs-over
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :match-tolerance/price-abs-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :match-tolerance/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :match-tolerance/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:match-tolerance/entity
                     :match-tolerance/supplier
                     :match-tolerance/product-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Return entities (RTV + customer return; one shape, role inverted)
;; ============================================================================

(def ^:private return-attrs
  [{:db/ident       :return/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :return/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":customer | :vendor. Discriminator (OFBiz
                     ReturnHeader.returnHeaderTypeId pattern)."}

   {:db/ident       :return/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":requested | :accepted | :received | :completed |
                     :rejected | :cancelled. State-machine (ADR-034)."}

   {:db/ident       :return/from-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :customer → the customer; for :vendor →
                     ourselves (the org returning)."}

   {:db/ident       :return/to-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :customer → ourselves; for :vendor → the
                     supplier."}

   {:db/ident       :return/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The original order being returned (SO for
                     :customer, PO for :vendor)."}

   {:db/ident       :return/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope (ADR-031)."}

   {:db/ident       :return/destination-facility-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Where the returned goods land."}

   {:db/ident       :return/supplier-rma
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Vendor's RMA number (for :vendor returns)."}

   {:db/ident       :return/entry-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc (ADR-038) — customer email,
                     defect photo, etc."}])

(def ^:private return-item-attrs
  [{:db/ident       :return-item/return
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The original line being returned."}

   {:db/ident       :return-item/seq-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item/product-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Denorm."}

   {:db/ident       :return-item/return-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity requested for return."}

   {:db/ident       :return-item/received-quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity actually received (may differ)."}

   {:db/ident       :return-item/return-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Price at return time; may differ from invoice."}

   {:db/ident       :return-item/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":damaged | :defective | :wrong-item |
                     :not-as-described | :no-longer-needed |
                     :late-delivery | :customer-request."}

   {:db/ident       :return-item/return-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":store-credit | :cash-refund | :exchange |
                     :vendor-credit."}

   {:db/ident       :return-item/expected-disposition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":available | :defective | :scrap |
                     :return-to-supplier."}

   {:db/ident       :return-item/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item/response
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:return-item/return :return-item/seq-id]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private return-response-attrs
  [{:db/ident       :return-response/return-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "1:1 with :return-item."}

   {:db/ident       :return-response/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":replacement-order | :credit-memo | :cash-refund |
                     :billing-account-credit."}

   {:db/ident       :return-response/replacement-order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "New SO/PO created in response."}

   {:db/ident       :return-response/credit-memo
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Kernel :invoice with :invoice/type :credit-memo
                     or :debit-memo."}

   {:db/ident       :return-response/payment-ref
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "For refunds processed via external payment
                     processor."}

   {:db/ident       :return-response/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-response/amount-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-response/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private return-item-billing-attrs
  ;; ADR-042: junction for credit memo linkage to return-item
  ;; (mirror of :order-item-billing for forward flow, :receipt-
  ;; invoice-billing for receipts).
  [{:db/ident       :return-item-billing/return-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Credit-memo line."}

   {:db/ident       :return-item-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item-billing/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :return-item-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:return-item-billing/return-item
                     :return-item-billing/invoice-line]
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
  [;; --- :requirement/status ---------------------------------------
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :nil
    :status-transition/to :proposed
    :status-transition/active true
    :status-transition/name "Create Requirement"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :proposed
    :status-transition/to :approved
    :status-transition/active true
    :status-transition/name "Approve Requirement"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :proposed
    :status-transition/to :rejected
    :status-transition/active true
    :status-transition/name "Reject Requirement"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :proposed
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Proposed Requirement"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :approved
    :status-transition/to :ordered
    :status-transition/active true
    :status-transition/name "Commit to PO"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :approved
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Approved Requirement"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :approved
    :status-transition/to :proposed
    :status-transition/active true
    :status-transition/name "Revise Approval"}
   {:status-transition/entity-type :requirement
    :status-transition/facet :requirement/status
    :status-transition/from :ordered
    :status-transition/to :received
    :status-transition/active true
    :status-transition/name "Auto-Promote on Full Receipt"}

   ;; --- :receipt/status -------------------------------------------
   {:status-transition/entity-type :receipt
    :status-transition/facet :receipt/status
    :status-transition/from :nil
    :status-transition/to :pending
    :status-transition/active true
    :status-transition/name "Create Receipt"}
   {:status-transition/entity-type :receipt
    :status-transition/facet :receipt/status
    :status-transition/from :pending
    :status-transition/to :accepted
    :status-transition/active true
    :status-transition/name "Inspection Pass"}
   {:status-transition/entity-type :receipt
    :status-transition/facet :receipt/status
    :status-transition/from :pending
    :status-transition/to :rejected
    :status-transition/active true
    :status-transition/name "Inspection Fail"}
   {:status-transition/entity-type :receipt
    :status-transition/facet :receipt/status
    :status-transition/from :accepted
    :status-transition/to :rejected
    :status-transition/active true
    :status-transition/name "Post-Inspection Reject"}

   ;; --- :invoice/match-status -------------------------------------
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :nil
    :status-transition/to :auto-matched
    :status-transition/active true
    :status-transition/name "Auto-Match (within tolerance)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :nil
    :status-transition/to :exception-price
    :status-transition/active true
    :status-transition/name "Flag Price Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :nil
    :status-transition/to :exception-qty
    :status-transition/active true
    :status-transition/name "Flag Quantity Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :nil
    :status-transition/to :exception-missing-receipt
    :status-transition/active true
    :status-transition/name "Flag Missing Receipt"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :nil
    :status-transition/to :exception-missing-po
    :status-transition/active true
    :status-transition/name "Flag Missing PO"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-price
    :status-transition/to :manual-approved
    :status-transition/active true
    :status-transition/name "Override Price Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-qty
    :status-transition/to :manual-approved
    :status-transition/active true
    :status-transition/name "Override Qty Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-missing-receipt
    :status-transition/to :manual-approved
    :status-transition/active true
    :status-transition/name "Override Missing Receipt"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-missing-po
    :status-transition/to :manual-approved
    :status-transition/active true
    :status-transition/name "Override Missing PO"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-price
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Dispute Price Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :exception-qty
    :status-transition/to :disputed
    :status-transition/active true
    :status-transition/name "Dispute Qty Exception"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :disputed
    :status-transition/to :manual-approved
    :status-transition/active true
    :status-transition/name "Resolve Dispute"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :auto-matched
    :status-transition/to :cleared
    :status-transition/active true
    :status-transition/name "Clear (post-payment)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/match-status
    :status-transition/from :manual-approved
    :status-transition/to :cleared
    :status-transition/active true
    :status-transition/name "Clear after Manual Approval (post-payment)"}

   ;; --- :return/status --------------------------------------------
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :nil
    :status-transition/to :requested
    :status-transition/active true
    :status-transition/name "Request Return"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :requested
    :status-transition/to :accepted
    :status-transition/active true
    :status-transition/name "Accept RMA"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :requested
    :status-transition/to :rejected
    :status-transition/active true
    :status-transition/name "Deny RMA"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :requested
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Requested Return"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :accepted
    :status-transition/to :received
    :status-transition/active true
    :status-transition/name "Receive Returned Goods"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :accepted
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Accepted Return"}
   {:status-transition/entity-type :return
    :status-transition/facet :return/status
    :status-transition/from :received
    :status-transition/to :completed
    :status-transition/active true
    :status-transition/name "Complete Return (refund/replacement issued)"}])

(def account-type-direction-seeds
  "Procurement-specific :account-type-direction rows (ADR-041
   table). Extends the default-direction-for fallback map in
   kontor.invoice.posting."
  [{:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :gr-ir-clearing
    :account-type-direction/direction :credit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :sales
    :account-type-direction/account-type :gr-ir-clearing
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :goods-receipt-accrual
    :account-type-direction/direction :credit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :landed-cost-variance
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :price-variance
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :exchange-variance
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :receive-reject-loss
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :prepaid-expense
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :purchase
    :account-type-direction/account-type :asset-acquisition
    :account-type-direction/direction :debit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :credit-memo
    :account-type-direction/account-type :vendor-credit-memo
    :account-type-direction/direction :credit
    :account-type-direction/active true}
   {:account-type-direction/invoice-type :debit-memo
    :account-type-direction/account-type :vendor-credit-memo
    :account-type-direction/direction :debit
    :account-type-direction/active true}])

(defn install!
  "Install the kontor-procurement companion schema + state-machine
   seeds + procurement :account-type-direction seeds. Idempotent."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds)
  (d/transact conn account-type-direction-seeds))

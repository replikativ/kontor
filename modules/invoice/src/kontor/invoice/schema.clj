(ns kontor.invoice.schema
  "Companion schema for `kontor-invoice` — see ADR-036.

   Extends the kernel's existing :invoice/* + :invoice-line/* with
   order-bridge fields, :invoice/type discriminator, sealing marker,
   multi-entity scope, and per-line GL routing metadata. Introduces
   two new entity namespaces:
     :order-item-billing/* — junction tracking invoiced quantity per
                             (order-item, invoice-line) for partial-
                             invoice arithmetic
     :gl-account-default/* — per-(account-type, entity) → :account
                             lookup table (OFBiz GlAccountTypeDefault
                             pattern)

   Seeds the invoice status machine into ADR-034's :status-transition
   table."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Invoice extensions (additive to kernel :invoice/*)
;; ============================================================================

(def ^:private invoice-ext-attrs
  [{:db/ident       :invoice/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales | :purchase | :credit-memo | :debit-memo.
                     Discriminator used by the posting bridge to route
                     debits vs credits. kontor-procurement (Stage K)
                     uses :purchase for vendor invoices."}

   {:db/ident       :invoice/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional back-ref to the :order this invoice was
                     created from. Nil for standalone bills not tied
                     to a sales order."}

   {:db/ident       :invoice/invoice-per-shipment-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to a :ship-group. When set, this
                     invoice is for one specific ship group (the
                     OFBiz invoicePerShipment pattern). When nil and
                     :invoice/order is set, the invoice covers the
                     whole order."}

   {:db/ident       :invoice/posted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Sealing marker — when the AcctgTrans was created
                     for this invoice. Distinct from :invoice/sent-at
                     (which marks customer-notification)."}

   {:db/ident       :invoice/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope per ADR-031. Required for
                     multi-entity tenants; optional for single-entity."}

   ;; ADR-040: jurisdiction primitives.
   {:db/ident       :invoice/tax-inclusive?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. When true, line :unit-price is
                     gross (tax-included); discount applies to gross
                     then tax back-solved. When false, :unit-price is
                     net; discount applies to net, tax computed on
                     post-discount base. ADR-040."}])

;; ============================================================================
;; Invoice-line extensions
;; ============================================================================

(def ^:private invoice-line-ext-attrs
  [{:db/ident       :invoice-line/parent-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Self-ref. When set, this line is a derived line
                     of its parent — e.g. a tax line attached to a
                     product line, a shipping surcharge attached to
                     a ship-group line. Posting groups parent + its
                     children together for line-level audit."}

   {:db/ident       :invoice-line/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The kontor-sales :order-item this invoice line
                     was created from. Set on the bridge call. Used
                     by partial-invoice math (subtract already-billed
                     quantity from order-item quantity on the next
                     invoice)."}

   {:db/ident       :invoice-line/order-adjustment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :order-adjustment this line was derived from.
                     Set for adjustment lines (tax, discount, shipping)."}

   {:db/ident       :invoice-line/gl-account-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales-revenue | :sales-tax-payable | :shipping-
                     income | :discount-given | :cogs | :ap-clearing |
                     … Posting-time discriminator for the GL account
                     lookup via :gl-account-default."}

   {:db/ident       :invoice-line/tax-auth-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax authority :partner (e.g. the German
                     Finanzamt). Used by ADR-016."}

   {:db/ident       :invoice-line/tax-auth-geo-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code (\"DE\", \"US-CA\")."}

   {:db/ident       :invoice-line/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Line total. For goods lines: quantity × unit-price.
                     For adjustment lines: the adjustment amount itself
                     (where quantity doesn't apply)."}

   ;; ADR-040: jurisdiction primitives.
   {:db/ident       :invoice-line/reverse-charge?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. When true, bridge emits dual
                     buyer-side postings (AP-tax-payable + AP-tax-
                     recoverable, netting to zero) and the supplier-
                     side invoice doesn't charge VAT. EU B2B
                     intracommunity + ViDA 2028. ADR-040."}

   {:db/ident       :invoice-line/recognition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":direct | :deferred. Default :direct. :deferred
                     credits :sales-revenue-deferred (a liability)
                     instead of :sales-revenue; consumer (kontor-revrec
                     when it lands) emits a :schedule (ADR-032) row
                     to release over the obligation period. ADR-040."}

   {:db/ident       :invoice-line/withholding-on-payment?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. When true, the withholding-tax
                     credit-leg defers to payment time, not invoice
                     posting time. IN TDS / MX ISR / US 1099 backup
                     withholding. ADR-040."}])

;; ============================================================================
;; :order-item-billing junction
;; ============================================================================

(def ^:private order-item-billing-attrs
  [{:db/ident       :order-item-billing/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :order-item (from kontor-sales)."}

   {:db/ident       :order-item-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :invoice-line."}

   {:db/ident       :order-item-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity of the :order-item billed on this
                     :invoice-line."}

   {:db/ident       :order-item-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:order-item-billing/order-item
                     :order-item-billing/invoice-line]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; :gl-account-default table (OFBiz GlAccountTypeDefault pattern)
;; ============================================================================

(def ^:private gl-account-default-attrs
  [{:db/ident       :gl-account-default/account-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales-revenue | :cogs | :ar | :ap | :sales-tax-
                     payable | … Posting-time GL routing key."}

   {:db/ident       :gl-account-default/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :entity scope. When nil = tenant-wide
                     default; when set = org-specific override that
                     coexists with the global. Same semantics as
                     :status-transition/applies-to-org (ADR-034)."}

   {:db/ident       :gl-account-default/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :account this account-type resolves to for
                     the given entity scope."}

   {:db/ident       :gl-account-default/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:gl-account-default/account-type
                     :gl-account-default/entity]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Aggregate + status-transition seeds
;; ============================================================================

(def all
  (vec (concat invoice-ext-attrs
               invoice-line-ext-attrs
               order-item-billing-attrs
               gl-account-default-attrs)))

(def status-transition-seeds
  "Invoice state machine seeded into the kernel :status-transition
   table (ADR-034). Vocabulary:
     :draft → :ready → :sent → :paid | :cancelled

   The :ready intermediate is optional — :draft → :sent is also
   permitted for batch flows."
  [{:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :nil
    :status-transition/to :draft
    :status-transition/active true
    :status-transition/name "Create Invoice"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :draft
    :status-transition/to :ready
    :status-transition/active true
    :status-transition/name "Finalize (lock edits)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :draft
    :status-transition/to :sent
    :status-transition/active true
    :status-transition/name "Post (skip-ready batch flow)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :draft
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Abandon Draft"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :ready
    :status-transition/to :sent
    :status-transition/active true
    :status-transition/name "Post"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :ready
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Cancel Ready"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :sent
    :status-transition/to :paid
    :status-transition/active true
    :status-transition/name "Settle"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :sent
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Void (creates reversal tx)"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :paid
    :status-transition/to :cancelled
    :status-transition/active true
    :status-transition/name "Refund"}

   ;; ADR-040: clearance lifecycle for e-invoicing jurisdictions
   ;; (IT SdI, IN IRN, BR NF-e, ES Verifactu). Opt-in — non-clearance
   ;; jurisdictions go :draft → :sent directly.
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :draft
    :status-transition/to :pending-attestation
    :status-transition/active true
    :status-transition/name "Submit for Clearance"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :ready
    :status-transition/to :pending-attestation
    :status-transition/active true
    :status-transition/name "Submit Finalized for Clearance"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :pending-attestation
    :status-transition/to :sent
    :status-transition/active true
    :status-transition/name "Cleared by Authority"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :pending-attestation
    :status-transition/to :rejected
    :status-transition/active true
    :status-transition/name "Rejected by Authority"}
   {:status-transition/entity-type :invoice
    :status-transition/facet :invoice/status
    :status-transition/from :rejected
    :status-transition/to :draft
    :status-transition/active true
    :status-transition/name "Revise and resubmit"}])

(defn install!
  "Install the kontor-invoice companion schema + state-machine seeds.
   Idempotent — composite identities ensure re-runs are no-ops."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds))

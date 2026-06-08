(ns kontor.invoice.schema
  "Companion schema for `kontor-invoice` — see ADR-036.

   Extends the kernel's existing :kontor.invoice/* + :kontor.invoice-line/* with
   order-bridge fields, :kontor.invoice/type discriminator, sealing marker,
   multi-entity scope, and per-line GL routing metadata. Introduces
   two new entity namespaces:
     :kontor.order-item-billing/* — junction tracking invoiced quantity per
                             (order-item, invoice-line) for partial-
                             invoice arithmetic
     :kontor.gl-account-default/* — per-(account-type, entity) → :account
                             lookup table with tenant-wide fallback

   Seeds the invoice status machine into ADR-034's :status-transition
   table."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Invoice extensions (additive to kernel :kontor.invoice/*)
;; ============================================================================

(def ^:private invoice-ext-attrs
  [{:db/ident       :kontor.invoice/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales | :purchase | :credit-memo | :debit-memo.
                     Discriminator used by the posting bridge to route
                     debits vs credits. kontor-procurement (Stage K)
                     uses :purchase for vendor invoices."}

   {:db/ident       :kontor.invoice/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional back-ref to the :order this invoice was
                     created from. Nil for standalone bills not tied
                     to a sales order."}

   {:db/ident       :kontor.invoice/invoice-per-shipment-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to a :ship-group. When set, this
                     invoice is for one specific ship group
                     (per-shipment invoicing). When nil and
                     :kontor.invoice/order is set, the invoice covers the
                     whole order."}

   ;; NOTE: :kontor.invoice/posted-at removed — the presence of
   ;; :kontor.invoice/transaction is the canonical "posted to GL" sentinel.
   ;; For the posted-at instant query, walk :status-history (the tx
   ;; that wrote :kontor.invoice/transaction also drove :kontor.invoice/status →
   ;; :sent) or read the :tx/valid-from on its tx.

   {:db/ident       :kontor.invoice/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Multi-entity scope per ADR-031. Required for
                     multi-entity tenants; optional for single-entity."}

   ;; ADR-040: jurisdiction primitives.
   {:db/ident       :kontor.invoice/tax-inclusive?
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
  [{:db/ident       :kontor.invoice-line/parent-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Self-ref. When set, this line is a derived line
                     of its parent — e.g. a tax line attached to a
                     product line, a shipping surcharge attached to
                     a ship-group line. Posting groups parent + its
                     children together for line-level audit."}

   {:db/ident       :kontor.invoice-line/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The kontor-sales :order-item this invoice line
                     was created from. Set on the bridge call. Used
                     by partial-invoice math (subtract already-billed
                     quantity from order-item quantity on the next
                     invoice)."}

   {:db/ident       :kontor.invoice-line/order-adjustment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :order-adjustment this line was derived from.
                     Set for adjustment lines (tax, discount, shipping)."}

   {:db/ident       :kontor.invoice-line/gl-account-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales-revenue | :sales-tax-payable | :shipping-
                     income | :discount-given | :cogs | :ap-clearing |
                     … Posting-time discriminator for the GL account
                     lookup via :gl-account-default."}

   {:db/ident       :kontor.invoice-line/tax-auth-party
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax authority :partner (e.g. the German
                     Finanzamt). Used by ADR-016."}

   {:db/ident       :kontor.invoice-line/tax-auth-geo-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code (\"DE\", \"US-CA\")."}

   {:db/ident       :kontor.invoice-line/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Line total. For goods lines: quantity × unit-price.
                     For adjustment lines: the adjustment amount itself
                     (where quantity doesn't apply)."}

   ;; ADR-040: jurisdiction primitives.
   {:db/ident       :kontor.invoice-line/reverse-charge?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Default false. When true, bridge emits dual
                     buyer-side postings (AP-tax-payable + AP-tax-
                     recoverable, netting to zero) and the supplier-
                     side invoice doesn't charge VAT. EU B2B
                     intracommunity + ViDA 2028. ADR-040."}

   {:db/ident       :kontor.invoice-line/recognition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":direct | :deferred. Default :direct. :deferred
                     credits :sales-revenue-deferred (a liability)
                     instead of :sales-revenue; consumer (kontor-revrec
                     when it lands) emits a :schedule (ADR-032) row
                     to release over the obligation period. ADR-040."}

   {:db/ident       :kontor.invoice-line/withholding-on-payment?
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
  [{:db/ident       :kontor.order-item-billing/order-item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :order-item (from kontor-sales)."}

   {:db/ident       :kontor.order-item-billing/invoice-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :invoice-line."}

   {:db/ident       :kontor.order-item-billing/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity of the :order-item billed on this
                     :invoice-line."}

   {:db/ident       :kontor.order-item-billing/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.order-item-billing/order-item
                     :kontor.order-item-billing/invoice-line]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; :gl-account-default table (per-(account-type, entity) lookup with fallback)
;; ============================================================================

(def ^:private gl-account-default-attrs
  [{:db/ident       :kontor.gl-account-default/account-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales-revenue | :cogs | :ar | :ap | :sales-tax-
                     payable | … Posting-time GL routing key."}

   {:db/ident       :kontor.gl-account-default/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :entity scope. When nil = tenant-wide
                     default; when set = org-specific override that
                     coexists with the global. Same semantics as
                     :kontor.status-transition/applies-to-org (ADR-034)."}

   {:db/ident       :kontor.gl-account-default/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :account this account-type resolves to for
                     the given entity scope."}

   {:db/ident       :kontor.gl-account-default/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.gl-account-default/account-type
                     :kontor.gl-account-default/entity]
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
  [{:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :draft
    :kontor.status-transition/active true
    :kontor.status-transition/name "Create Invoice"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :draft
    :kontor.status-transition/to :ready
    :kontor.status-transition/active true
    :kontor.status-transition/name "Finalize (lock edits)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :draft
    :kontor.status-transition/to :sent
    :kontor.status-transition/active true
    :kontor.status-transition/name "Post (skip-ready batch flow)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :draft
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Abandon Draft"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :ready
    :kontor.status-transition/to :sent
    :kontor.status-transition/active true
    :kontor.status-transition/name "Post"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :ready
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cancel Ready"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :sent
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Settle (full)"}
   ;; ADR-043: partial-payment lifecycle.
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :sent
    :kontor.status-transition/to :partially-paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "First Partial Application"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :partially-paid
    :kontor.status-transition/to :partially-paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Additional Partial Application"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :partially-paid
    :kontor.status-transition/to :paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Final Application Closes the Invoice"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :partially-paid
    :kontor.status-transition/to :sent
    :kontor.status-transition/active true
    :kontor.status-transition/name "Allocation Reversal (back to fully-open)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :paid
    :kontor.status-transition/to :sent
    :kontor.status-transition/active true
    :kontor.status-transition/name "Reversal of Final Application (full reopen)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :paid
    :kontor.status-transition/to :partially-paid
    :kontor.status-transition/active true
    :kontor.status-transition/name "Reversal Leaves Partial Balance"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :sent
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Void (creates reversal tx)"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :paid
    :kontor.status-transition/to :cancelled
    :kontor.status-transition/active true
    :kontor.status-transition/name "Refund"}

   ;; ADR-040: clearance lifecycle for e-invoicing jurisdictions
   ;; (IT SdI, IN IRN, BR NF-e, ES Verifactu). Opt-in — non-clearance
   ;; jurisdictions go :draft → :sent directly.
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :draft
    :kontor.status-transition/to :pending-attestation
    :kontor.status-transition/active true
    :kontor.status-transition/name "Submit for Clearance"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :ready
    :kontor.status-transition/to :pending-attestation
    :kontor.status-transition/active true
    :kontor.status-transition/name "Submit Finalized for Clearance"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :pending-attestation
    :kontor.status-transition/to :sent
    :kontor.status-transition/active true
    :kontor.status-transition/name "Cleared by Authority"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :pending-attestation
    :kontor.status-transition/to :rejected
    :kontor.status-transition/active true
    :kontor.status-transition/name "Rejected by Authority"}
   {:kontor.status-transition/entity-type :invoice
    :kontor.status-transition/facet :kontor.invoice/status
    :kontor.status-transition/from :rejected
    :kontor.status-transition/to :draft
    :kontor.status-transition/active true
    :kontor.status-transition/name "Revise and resubmit"}])

(defn install!
  "Install the kontor-invoice companion schema + state-machine seeds.
   Idempotent — composite identities ensure re-runs are no-ops."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds)
  conn)

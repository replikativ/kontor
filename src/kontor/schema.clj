(ns kontor.schema
  "Datahike schema for the accounting kernel.

   Every attribute is namespaced under one of the kernel namespaces
   (see CLAUDE.md and ADR-002). The intent is that this schema can be
   transacted into a datahike connection that already holds other
   namespaces (e.g. beleg's :invoice/* :customer/*) without any
   collision. That cohabitation is an architectural invariant — see
   ADR-002 in doc/decisions.md.

   Bitemporal modeling per ADR-008:
     - transaction time = :db/txInstant (datahike-supplied, free)
     - valid time       = explicit :*/valid-from and (where applicable)
                          :*/valid-to attributes on transactions and
                          postings.
     - the :posting/temporal-key tuple indexes the bitemporal axis.

   Sealing per ADR-007:
     - :posting/posted-at is the seal marker. Once set, the application
       middleware in `sealing.clj` refuses silent retract; explicit
       :db/purge is permitted (and is itself a recorded commit, so the
       audit chain documents the deletion)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Audit-trail attributes (Odoo-style create_*/write_*).
;;
;; Used on every domain entity below. Centralized so we can change the
;; convention in one place. Values are user-supplied at write time —
;; the kernel does not auto-populate (consumers know who's logged in).
;; ============================================================================

(def ^:private audit-attrs
  "Audit attributes attached to every kernel entity. The :db/txInstant
   datahike already records covers the underlying tx-time; create-uid
   /write-uid are domain-level (who, not when)."
  [{:db/ident       :create/uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who created this entity. Domain-level audit
                     field; the actual tx timestamp lives on the datom
                     via :db/txInstant."}

   {:db/ident       :write/uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who last wrote this entity at the application
                     level (last logical update, not last tx)."}])

;; ============================================================================
;; Commodity — currencies and other tradeable units.
;;
;; PTA-style: keep small. A commodity is identified by its symbol (EUR,
;; USD, BTC). Precision is the number of fractional digits used for
;; storage and display. Currency commodities have :commodity/iso-4217
;; populated; non-currency commodities (stock, crypto) do not.
;; ============================================================================

(def ^:private commodity-attrs
  [{:db/ident       :commodity/symbol
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO-4217 code for fiat (EUR, USD, CAD), ticker for
                     other commodities. Identity attribute."}

   {:db/ident       :commodity/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :commodity/precision
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Number of fractional digits used for amounts in this
                     commodity. EUR/USD = 2; JPY = 0; BTC = 8."}

   {:db/ident       :commodity/iso-4217
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO-4217 alphabetic currency code, when applicable.
                     Set iff this is a fiat currency."}])

;; ============================================================================
;; Lot — a specific acquisition of a commodity (cost-basis tracking).
;;
;; PTA convention: a lot pins (date, cost-basis-per-unit, optional
;; label) so the accounting system can FIFO/LIFO/specific-identification
;; correctly when units are later disposed. For pure cash currencies
;; the :lot model is unused; lots only become interesting for stocks,
;; crypto, inventory.
;; ============================================================================

(def ^:private lot-attrs
  [{:db/ident       :lot/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :lot/acquired-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this lot was acquired (valid-time)."}

   {:db/ident       :lot/cost-basis
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Cost basis per unit, in the cost commodity."}

   {:db/ident       :lot/cost-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :lot/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional human label for specific-identification
                     disposal (\"first batch\", \"Q3 raise\", etc.)."}])

;; ============================================================================
;; Account — chart-of-accounts entry.
;;
;; PTA-style: a hierarchical name like \"Assets:Bank:Checking\" is the
;; canonical identity. We additionally carry the Odoo-style :account/code
;; (a numeric or alphanumeric tag — SKR03 has 1200, QBO uses 1010, etc.)
;; for compatibility with country charts that ship code-keyed.
;;
;; Account hierarchy uses :account/parent (single-parent tree). Code-prefix
;; rollup (Odoo's account_group) is a query-time concept on top, not a
;; schema concept.
;; ============================================================================

(def ^:private account-attrs
  [{:db/ident       :account/path
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Hierarchical name in PTA notation, e.g.
                     \"Assets:Bank:Checking\" or
                     \"Income:Sales:EU\". Identity attribute."}

   {:db/ident       :account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional country-specific code (SKR03 \"1200\",
                     QBO \"1010\"). Indexed for prefix-rollup queries."}

   {:db/ident       :account/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name, possibly localized."}

   {:db/ident       :account/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :asset :liability :equity :income :expense.
                     Determines balance-sheet vs P&L classification and
                     the natural sign of postings."}

   {:db/ident       :account/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent account in the hierarchy. nil for roots."}

   {:db/ident       :account/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default commodity. nil = polymorphic
                     (postings carry their own commodity)."}

   {:db/ident       :account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Inactive accounts cannot be posted to but remain
                     in historical reports."}

   {:db/ident       :account/reconcilable
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether postings against this account can be
                     reconciled (typically true for receivables, payables,
                     bank accounts)."}

   {:db/ident       :account/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs for report-time aggregation
                     (Odoo's account_account_tag M2M, used by the
                     declarative report engine)."}

   ;; ADR-019: regulator-specific external codes. The single
   ;; :account/code above is the kernel-facing code (often = the
   ;; dominant regulator's code, e.g. SKR04 in DE). When an account
   ;; answers to multiple regulators (BR analytical → Plano
   ;; Referencial → SPED Contábil; DE internal → SKR04 → DATEV; or
   ;; management → IFRS group), the :account-code entities each
   ;; carry one (regulator, code) pairing.
   {:db/ident       :account/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Many-ref to :account-code entities, one per
                     (account, regulator) pair. See ADR-019."}])

;; ============================================================================
;; Document-type registry — ADR-020.
;;
;; First-class entity for the regulator-recognized kind of a fiscal
;; document. Registered per-country at module install time; referenced
;; by :transaction/document-type.
;; ============================================================================

(def ^:private document-type-attrs
  [{:db/ident       :document-type/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Regulator's short code. BR: '55' (NF-e),
                     '65' (NFC-e), '57' (CT-e), '58' (MDF-e), 'SE'
                     (NFS-e). CN: '01' (special VAT fapiao), '02'
                     (general fapiao), '10' (electronic general),
                     '65' (fully-digital).  Not globally unique;
                     identity is (code, jurisdiction)."}

   {:db/ident       :document-type/jurisdiction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Tax authority that defines this code. Conventional:
                     :br/sefaz, :cn/sta, :de/finanzamt, :ca/cra,
                     :ar/afip, :cl/sii. Co-keys the identity with code."}

   {:db/ident       :document-type/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:document-type/code :document-type/jurisdiction]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity (code, jurisdiction)."}

   {:db/ident       :document-type/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name, may include native-script:
                     'Nota Fiscal Eletrônica — mercadorias' or
                     '增值税专用发票 (Special VAT Fapiao)'."}

   {:db/ident       :document-type/internal-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":invoice | :credit-note | :debit-note | :all.
                     Drives credit/debit-note routing through the same
                     document-type as the origin invoice."}

   {:db/ident       :document-type/prefix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Prefix for the clearance-token / access key
                     when applicable ('NFe', 'NFCe')."}

   {:db/ident       :document-type/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private account-code-attrs
  [{:db/ident       :account-code/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the account this code is for."}

   {:db/ident       :account-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :br/plano-referencial, :br/sped-contabil,
                     :cn/asbe, :cn/assbe, :de/datev, :ifrs/group, etc."}

   {:db/ident       :account-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system."}

   {:db/ident       :account-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:account-code/account :account-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (account, regulator)
                     pairing exists at most once per DB."}

   {:db/ident       :account-code/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional human-readable explanation."}])

;; ============================================================================
;; Account-tag — labels for report-time aggregation.
;;
;; A tag attaches to an account or to a tax-repartition-line. The
;; declarative report engine (Phase 1.5) aggregates postings by their
;; account's tags or their tax-rep-line's tags to produce VAT-report-box-
;; style figures.
;; ============================================================================

(def ^:private account-tag-attrs
  [{:db/ident       :account-tag/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Identity attribute — one entity per tag name. Lets
                     consumers reference tags via [:account-tag/name
                     \"ust-66\"] lookup refs in tx-data."}

   {:db/ident       :account-tag/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "ISO-3166 alpha-2 country code. Tags are typically
                     country-scoped (a German VAT report tag is not
                     meaningful on a US sale)."}

   {:db/ident       :account-tag/applicability
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :account :tax. Indicates which kind of
                     entity this tag attaches to."}])

;; ============================================================================
;; Journal — categorization of journal entries.
;;
;; Sale, purchase, cash, bank, general. Each journal sequences its own
;; transaction names (\"INV-2026-0001\", \"BNK-2026-0042\") and may
;; default a posting account.
;; ============================================================================

(def ^:private journal-attrs
  [{:db/ident       :journal/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Short code (\"INV\", \"BNK\", \"GEN\"). Identity."}

   {:db/ident       :journal/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :journal/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :sale :purchase :cash :bank :general.
                     Determines default account behaviors and report
                     classification."}

   {:db/ident       :journal/default-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Account auto-debited/credited when a transaction
                     in this journal omits the contra side."}

   {:db/ident       :journal/sequence-prefix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Prefix for auto-generated transaction names
                     (\"INV/{year}/\")."}

   {:db/ident       :journal/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Partner — customer or vendor.
;;
;; Minimal in the kernel; consumer libraries (beleg) carry richer
;; partner data. The kernel needs the FK so postings can be filtered
;; by partner.
;; ============================================================================

(def ^:private partner-attrs
  [{:db/ident       :partner/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Caller-supplied stable identifier. For beleg
                     integration this is the :customer/id UUID stringified."}

   {:db/ident       :partner/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":customer | :vendor | :both"}

   {:db/ident       :partner/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "ISO-3166 alpha-2; used for fiscal-position auto-
                     application and tax-provider routing."}

   {:db/ident       :partner/tax-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "VAT ID, EIN, GST number, etc. Format is country-
                     specific; validation lives in l10n modules.
                     ADR-040 adds :partner-tax-id junction for multi-
                     jurisdiction partners; this scalar is the primary."}

   ;; ADR-039: credit limit + status (extends :partner additive).
   {:db/ident       :partner/credit-limit
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Credit limit amount in :partner/credit-commodity.
                     Nil = unlimited. Consumer's responsibility to
                     define 'open' / 'pending' for credit-available
                     calculation (kontor.partner/credit-available)."}

   {:db/ident       :partner/credit-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity (currency) of :partner/credit-limit."}

   {:db/ident       :partner/credit-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":open | :hold | :review | :closed. ADR-039.
                     Consumers enforce: post-to-ledger may refuse
                     posting against a :hold partner; sales flows
                     gate order approval on :review."}

   ;; ADR-039: KYC hooks. The actual sanctions-screening engine is a
   ;; future SanctionsProvider companion; these scalars capture the
   ;; latest result.
   {:db/ident       :partner/kyc-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":not-required | :pending | :cleared | :flagged |
                     :blocked. Trade should be forbidden when
                     :blocked (consumer enforces)."}

   {:db/ident       :partner/kyc-checked-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner/kyc-source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Provider name: LexisNexis, Refinitiv,
                     ComplyAdvantage, Manual, …"}])

;; ============================================================================
;; ADR-039: master-data primitives — merge, bank-account, partner-bank-
;; account junction, partner-tag.
;; ============================================================================

(def ^:private partner-merge-attrs
  [{:db/ident       :partner-merge/duplicate-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical (good) partner. After merge, queries
                     via resolve-canonical-partner walk superseded ->
                     duplicate-of."}

   {:db/ident       :partner-merge/superseded
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Duplicate (bad) partner being merged INTO the
                     canonical. Its history is preserved bitemporally;
                     queries resolve it to :duplicate-of from the
                     merge point forward."}

   {:db/ident       :partner-merge/merged-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-merge/merged-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-merge/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-038 codified-reason vocabulary."}

   {:db/ident       :partner-merge/reason-note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-merge/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc (ADR-038)."}

   {:db/ident       :partner-merge/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-merge/duplicate-of :partner-merge/superseded]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private bank-account-attrs
  [{:db/ident       :bank-account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :bank-account/iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-account/bic
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-account/account-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "For non-IBAN banks (US, etc.)."}

   {:db/ident       :bank-account/routing-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "US ABA, GB sort code, etc."}

   {:db/ident       :bank-account/bank-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-account/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :country (ADR-023)."}

   {:db/ident       :bank-account/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The account's currency. Multi-currency partners
                     have N bank accounts each pinned to one
                     :commodity."}

   {:db/ident       :bank-account/holder-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "On-the-account legal name (may differ from
                     :partner/name when a partner uses a service
                     intermediary)."}

   {:db/ident       :bank-account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-account/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private partner-bank-account-attrs
  [{:db/ident       :partner-bank-account/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/bank-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/purpose
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":disbursement | :collection | :both."}

   {:db/ident       :partner-bank-account/preferred?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Preferred-for-purpose flag. When multiple
                     accounts exist for the same partner+purpose,
                     :preferred? disambiguates."}

   {:db/ident       :partner-bank-account/verified?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/verified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-bank-account/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-bank-account/partner
                     :partner-bank-account/bank-account
                     :partner-bank-account/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private partner-tag-attrs
  [{:db/ident       :partner-tag/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-tag/tag-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical starter vocabulary:
                     :vip | :high-volume | :strategic-account |
                     :churn-risk | :do-not-contact | :test-account |
                     :gold-tier | :silver-tier | :bronze-tier | …
                     consumers extend."}

   {:db/ident       :partner-tag/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-tag/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :partner-tag/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:partner-tag/partner
                     :partner-tag/tag-type
                     :partner-tag/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Fiscal position — per-region tax/account remapping rules.
;;
;; Implements Odoo's account_fiscal_position concept: when a customer
;; is in country X, replace the default tax with the X-appropriate one.
;; The mapping itself lives in :fiscal-position/tax-mappings (a many-ref
;; to remap entities — declared once we have a remap entity).
;; ============================================================================

(def ^:private fiscal-position-attrs
  [{:db/ident       :fiscal-position/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :fiscal-position/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :fiscal-position/auto-apply
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :fiscal-position/vat-required
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether partner must have a tax-id for this
                     position to apply (e.g. EU intra-community
                     reverse charge requires a VAT ID)."}])

;; ============================================================================
;; Tax + tax-repartition-line + tax-group.
;;
;; The repartition pattern from Odoo (research note 01, conversation
;; analysis): a tax is a *recipe* for posting, not a number. It has
;; multiple repartition lines that say "this fraction of the base goes
;; to account X with tag T" and "this fraction of the tax goes to
;; account Y with tag U" — separate repartition for invoices vs refunds.
;;
;; Recoverable vs non-recoverable (ADR-005): VAT/HST/QST/GST set
;; :tax/recoverable? true (input tax credit). PST/RST/US sales tax set
;; it false (becomes cost of input).
;; ============================================================================

(def ^:private tax-attrs
  [{:db/ident       :tax/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Short code unique within the DB (\"DE-VAT-19\",
                     \"CA-GST-5\"). Identity."}

   {:db/ident       :tax/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :tax/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :tax/type-tax-use
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sale | :purchase | :none"}

   {:db/ident       :tax/amount-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":percent | :fixed | :group | :division"}

   {:db/ident       :tax/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The rate. 0.19M for 19%; 0.05M for 5%; for :fixed
                     the absolute amount per unit."}

   {:db/ident       :tax/recoverable?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True for VAT-style taxes (input tax credit allowed:
                     DE VAT, CA GST/HST/QST). False for retail sales
                     taxes (PST/RST in CA; sales tax in the US) where
                     the tax becomes cost of the input."}

   {:db/ident       :tax/tax-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax-group ref. The group's payable/receivable
                     accounts are where collected/recoverable taxes
                     accumulate."}

   {:db/ident       :tax/include-base-amount
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether this tax's amount is added to the base for
                     subsequent (compound) taxes."}

   {:db/ident       :tax/exigibility
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":on-invoice | :on-payment (cash-basis taxes)."}

   {:db/ident       :tax/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; Multi-jurisdiction filing — research note 09 (cross-country
   ;; variance). One business may file (a) GST/HST to Canada Revenue
   ;; Agency, (b) PST to Province of BC, (c) QST to Revenu Québec, and
   ;; (d) state sales tax to Texas Comptroller, all in the same
   ;; period. Without an explicit authority field the filing-time
   ;; aggregator has to string-match :tax/code prefixes — fragile
   ;; across l10n teams. Backfill DE entities with :de-bzst when those
   ;; ship; nil today (no DE :tax entities exist yet — UStVA tags hang
   ;; off accounts, not taxes).
   {:db/ident       :tax/authority
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Tax-collecting authority that this tax is filed
                     to. Examples: :de-bzst (Bundeszentralamt),
                     :ca-cra (Canada Revenue Agency), :ca-rq (Revenu
                     Québec), :ca-bc-finance, :us-tx-cpa (Texas
                     Comptroller). Filing reports group by this
                     attribute. Required from CA / US localizations
                     forward; optional for single-authority
                     localizations like DE / AT."}])

(def ^:private tax-rep-attrs
  [{:db/ident       :tax-rep/tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the tax this repartition line belongs to."}

   {:db/ident       :tax-rep/document-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":invoice | :refund. Different document types use
                     different repartition (typically: refunds map to
                     the same accounts but with tax tags inverted for
                     reporting)."}

   {:db/ident       :tax-rep/repartition-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":base | :tax. Whether this repartition line covers
                     the base amount (the pre-tax revenue) or the tax
                     amount itself."}

   {:db/ident       :tax-rep/factor-percent
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Percentage of the (base or tax) amount this line
                     posts. 100M for the standard case; split values for
                     partial-deductible scenarios (e.g., DE 50% input-tax
                     deductibility on certain hospitality)."}

   {:db/ident       :tax-rep/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Account the posting lands on. nil for base
                     repartition that just attaches tags without
                     producing its own posting."}

   {:db/ident       :tax-rep/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs attached to the produced posting,
                     used for VAT-report-box aggregation."}

   {:db/ident       :tax-rep/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}])

(def ^:private tax-group-attrs
  [{:db/ident       :tax-group/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :tax-group/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :tax-group/payable-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Where collected output VAT lands (liability side)."}

   {:db/ident       :tax-group/receivable-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Where deductible input VAT lands (asset side)."}])

;; ============================================================================
;; Period — open/closed accounting periods.
;;
;; A period covers a date range (typically a month or fiscal year).
;; Once :period/locked-at is set, the sealing middleware refuses new
;; postings whose effective-date falls within. Lock-tx records the
;; transaction id of the close so reports can identify the closing
;; entries explicitly.
;;
;; Open question per ADR (deferred): whether period close also forks
;; a datahike branch for that period as the persistence pattern. Phase
;; 1 uses attributes only; we'll feel out fork-per-period later.
;; ============================================================================

(def ^:private period-attrs
  [{:db/ident       :period/start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Inclusive start of the period (half-open with :end)."}

   {:db/ident       :period/end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Exclusive end of the period — the range is [start, end)."}

   {:db/ident       :period/journal
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: scope the lock to a single journal.
                     nil = applies to all journals."}

   {:db/ident       :period/locked-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "SOFT close (ADR-014). Reopen-able via period/reopen!.
                     Refuses new postings whose :posting/valid-from falls
                     in the range. Maps onto Odoo's period_lock_date /
                     tax_lock_date / sale_lock_date / purchase_lock_date,
                     NetSuite's 'Locked', Xero's 'Period Lock Date'."}

   {:db/ident       :period/sealed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "HARD close (ADR-014). Monotone — the date can only
                     move forward. Irrevocable — period/reopen! refuses
                     to clear it; sealing middleware refuses any retract
                     on a sealed-period entity. Maps onto Odoo's
                     hard_lock_date, Xero's 'End of Year Lock Date',
                     NetSuite's 'Closed', Sage Intacct's 'locked'."}

   {:db/ident       :period/sealed-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User ref recorded at the moment of sealing."}

   {:db/ident       :period/lock-tx
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-id of the soft-close (or seal), for audit reference."}

   {:db/ident       :period/adjustment?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "If true, this period overlaps the same date range as
                     a normal period and represents a year-end-adjustment
                     bucket — SAP's special periods 13–16. Mandatory for
                     DE compliance: HGB year-end audit corrections post
                     into period 13 with effective date 31 December but
                     must NOT appear in January's reports. Postings opt
                     into the adjustment period via :posting/period-tag."}

   {:db/ident       :period/tag
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator within an effective-date range when
                     multiple periods coexist. Conventional values:
                     :normal (the default if absent), :adjustment-13 ..
                     :adjustment-16 for SAP-style special periods."}

   {:db/ident       :period/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional human-readable label (e.g. \"FY2025\",
                     \"2026-Q1\"). Indexed so reports can look up
                     periods by name without scanning."}])

;; ============================================================================
;; Balance assertion — pinned reality at a point in time.
;;
;; PTA convention: \"on date X account Y had balance Z in commodity C\".
;; Used to detect drift; if a bank reconciliation produces a balance
;; that doesn't match the assertion, raise an error rather than silently
;; absorb. Mostly used by import jobs and period-close.
;; ============================================================================

(def ^:private balance-assertion-attrs
  [{:db/ident       :balance-assertion/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :balance-assertion/at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time at which the assertion was made."}

   {:db/ident       :balance-assertion/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :balance-assertion/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :balance-assertion/source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Where this assertion came from (\"bank statement
                     2026-04-30\", \"period close 2026-Q1\")."}])

;; ============================================================================
;; Transaction — the journal entry header (Odoo's account.move).
;;
;; A transaction collects 2+ postings into an atomic accounting unit.
;; The kernel enforces: postings sum to zero per commodity within one
;; transaction; transaction is fully posted (all postings posted) or
;; fully draft.
;;
;; Bitemporal:
;;   :transaction/effective-date is valid-time (when in the world this
;;   business event happened — invoice date, payment date).
;;   tx-time comes from datahike's :db/txInstant.
;; ============================================================================

(def ^:private transaction-attrs
  [{:db/ident       :transaction/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable caller-supplied id (\"INV-2026-0001\",
                     a beleg :invoice/id stringified, etc.). Identity."}

   {:db/ident       :transaction/journal
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :transaction/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Valid-time. The business date of this entry. In a
                     bitemporal query, all :as-of-valid filters apply
                     against this attribute."}

   {:db/ident       :transaction/narration
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text description (\"Customer invoice 2026-0001
                     for ACME services\")."}

   {:db/ident       :transaction/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: the primary partner for this transaction
                     (the customer for an invoice, vendor for a bill).
                     Postings may also carry their own partner refs."}

   {:db/ident       :transaction/fiscal-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :transaction/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":draft | :pending-attestation | :posted | :cancelled.
                     Lifecycle.

                     :pending-attestation (ADR-018) is the in-flight state
                     used by jurisdictions that require government
                     attestation of the invoice before it becomes legally
                     valid (BR NF-e via SEFAZ, CN fapiao via STA platform).
                     Sealing does NOT fire in this state — the entry
                     has not yet had legal effect. Transition to :posted
                     happens when the EInvoiceProvider returns a
                     successful clearance token. Peppol (JP/AU/DE) and
                     paper jurisdictions (CA, US, AT, FR) bypass this
                     state, going :draft → :posted directly."}

   {:db/ident       :transaction/clearance-token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The government-issued legal identifier of this
                     transaction's invoice, when one is required. For
                     BR NF-e it's the 44-digit access key (chave de
                     acesso). For CN fapiao it's the 8-digit fapiao
                     number, the 18-digit combined code+number, or the
                     20-digit fully-digital identifier. Set at transition
                     :pending-attestation → :posted by the country
                     module's EInvoiceProvider. Indexed because
                     reconciliation and reporting routinely look up by
                     token. nil for jurisdictions with no clearance
                     step. ADR-018."}

   ;; ADR-020: document-type registry + clearance-format dispatch.
   {:db/ident       :transaction/document-type
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ref to a :document-type entity. The regulator-
                     recognized kind of fiscal document this
                     transaction represents (BR NF-e mod 55, NFC-e
                     mod 65, CT-e mod 57, CN special-VAT fapiao 01,
                     etc.). See ADR-020."}

   {:db/ident       :transaction/clearance-format
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Quick-lookup discriminator for the clearance-token
                     format. Emitters + validators dispatch on this
                     keyword (without walking back to :document-type).
                     Conventional values:
                       :br/nfe :br/nfc-e :br/cte :br/mdf-e :br/nfs-e
                       :cn/fapiao-special-18 :cn/fapiao-general-20
                       :cn/fapiao-digital-20
                       :de/rechnung :de/factur-x
                     Validation: a per-jurisdiction validator confirms
                     the token matches the regex for this format
                     (18-digit, 20-digit, 44-digit, etc.). ADR-020."}

   {:db/ident       :transaction/posted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-time when the transaction was posted (state
                     :draft → :posted). Sealing trigger — see ADR-007
                     and sealing.clj. Once set, postings on this
                     transaction may not be silently retracted."}

   {:db/ident       :transaction/posted-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Caller-supplied user ref recorded at posting time."}

   {:db/ident       :transaction/reverses
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "If this transaction is a reversal, the original
                     transaction it reverses. Per ADR-007, corrections
                     are reversals + re-postings, never in-place edits."}

   {:db/ident       :transaction/closes-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Marks this transaction as the year-end (or any-
                     period) closing entry that zeros the P&L accounts
                     into retained earnings. Unique-identity prevents
                     a second closing entry for the same period."}

   {:db/ident       :transaction/source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-form provenance (\"beleg invoice 0001\",
                     \"bank statement camt053 2026-04-30\")."}

   {:db/ident       :transaction/settles
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Other transactions this one settles. A payment-
                     receipt transaction (driven by a bank line)
                     points to the invoice transactions it pays.
                     Cardinality many because one bank deposit can
                     settle multiple invoices for the same partner."}

   {:db/ident       :transaction/payment-term
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to a :payment-term entity. When set
                     plus :transaction/effective-date, the helper
                     fns in payment-term.clj derive :due-date and
                     :discount-deadline. Aging reports key off the
                     resulting due-date."}

   {:db/ident       :transaction/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "When the receivable is due. Either materialized
                     from :payment-term + :effective-date or set
                     explicitly by the importer / UI. Indexed because
                     aging reports filter on the relation
                     `due-date < today`."}

   {:db/ident       :transaction/discount-deadline
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last day the early-payment discount applies.
                     nil when the payment-term has no discount."}])

;; ============================================================================
;; Posting — individual debit/credit line (Odoo's account.move.line).
;;
;; The atomic unit of double entry. Sign convention:
;;   amount > 0  ⇒ debit
;;   amount < 0  ⇒ credit
;;   sum(amounts in tx, per commodity) = 0
;;
;; display-type is the Odoo discriminator for what kind of line this is
;; (real product posting, auto-generated tax line, payment-term
;; placeholder, rounding adjustment, UI-only annotation).
;; ============================================================================

(def ^:private posting-attrs
  [{:db/ident       :posting/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :posting/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :posting/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed amount in :posting/commodity. Positive = debit,
                     negative = credit. Postings within a transaction must
                     sum to zero per commodity."}

   {:db/ident       :posting/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :posting/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: specific lot when the posting affects a
                     trackable inventory of units (stocks, crypto)."}

   {:db/ident       :posting/cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional cost-basis per unit at posting time, in
                     :posting/cost-commodity. Used by FIFO/LIFO disposal
                     calculation."}

   {:db/ident       :posting/cost-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; Foreign-currency support: when commodity differs from the journal's
   ;; reporting currency, both representations are stored.
   {:db/ident       :posting/amount-base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The same amount, expressed in the journal/company
                     base currency. Set iff :posting/commodity differs
                     from the base currency."}

   {:db/ident       :posting/base-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; Valid-time anchor (ADR-008 revised): :valid-from is sufficient
   ;; for the SMB workflows we care about (backdated invoices, intra-
   ;; period delayed entry). :valid-to and the temporal-key tuple were
   ;; dropped per research note 08 — production accounting handles
   ;; corrections via reverse-and-repost in the current open period,
   ;; not preserve-the-as-filed-view-forever. Tx-time slicing remains
   ;; available via datahike's `d/as-of`.
   {:db/ident       :posting/valid-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Valid-time of the posting. Defaults to the parent
                     transaction's :transaction/effective-date if not
                     set explicitly. Used for bitemporal-as-of-valid
                     read filtering."}

   {:db/ident       :posting/display-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "What kind of line this is, mirroring Odoo's
                     account_move_line.display_type. One of: :product
                     (real posting), :tax (auto-generated tax line),
                     :payment-term (placeholder for the receivable/
                     payable from terms), :rounding (cash-rounding
                     adjustment), :section (UI section header),
                     :note (UI annotation, no posting effect)."}

   {:db/ident       :posting/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional partner ref (overrides
                     transaction-level partner for this line)."}

   {:db/ident       :posting/narration
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; Tax connection. A tax line carries :posting/tax-rep (the rep line
   ;; that produced it) and :posting/tax-base (the base amount the tax
   ;; was computed from). A base/product line that is taxed by N taxes
   ;; carries :posting/taxes-applied (cardinality many).
   {:db/ident       :posting/tax-rep
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :tax display-type postings: the
                     tax-repartition-line that produced this posting."}

   {:db/ident       :posting/tax-base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "For :tax display-type postings: the base amount
                     this tax was computed from."}

   {:db/ident       :posting/taxes-applied
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "For :product display-type postings: which taxes
                     were applied to this base. Cardinality-many because
                     a single line may be subject to multiple taxes
                     (e.g., GST + PST in BC)."}

   {:db/ident       :posting/account-tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Materialized account-tag refs at posting time
                     (mirroring tax-rep tags + account tags). Pre-
                     materialized so report engines need only one query."}

   {:db/ident       :posting/posted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Tx-time when this posting was posted. Sealing
                     trigger — see ADR-007. Set together with parent
                     transaction's :transaction/posted-at."}

   {:db/ident       :posting/period-tag
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional opt-in to a non-default period (ADR-014).
                     When two periods cover the same effective-date
                     range — e.g. a normal December and a year-end
                     adjustment :adjustment-13 — this tag picks which
                     one the posting routes into. Defaults to :normal
                     (i.e. the period whose :period/tag is absent or
                     :normal)."}

   ;; ADR-016 — Multi-tax breakdown. A product line subject to one
   ;; or more taxes carries a many-ref to :tax-application entities,
   ;; one per (line, tax) pairing. Brazil's 5-tax-stack invoice
   ;; produces 5 applications; JP dual-rate produces 1 per posting;
   ;; DE reverse-charge produces 1.
   ;;
   ;; This is parallel to :posting/taxes-applied (which still records
   ;; "this line was taxed by these taxes" for simple uses) and to
   ;; the auto-generated :tax-display-type postings (the ledger entries).
   ;; The breakdown is intent + audit; the postings are bookkeeping.
   {:db/ident       :posting/tax-breakdown
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Many-ref to :tax-application entities. ADR-016."}

   ;; ADR-018 — clearance token mirror at the posting level. Set
   ;; together with :transaction/clearance-token by the country
   ;; module's EInvoiceProvider on transition :pending-attestation
   ;; → :posted. Mirrored at the posting level so reports keyed off
   ;; postings (rather than transactions) can find the token directly.
   {:db/ident       :posting/clearance-token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Mirror of :transaction/clearance-token at the
                     posting level for query ergonomics. Set together
                     with the parent transaction's token. ADR-018."}])

(def ^:private tax-application-attrs
  "ADR-016 — per-posting per-tax computation record. Captures the
   base, the resulting tax amount, and the compound-on lineage for
   audit + report queries that need direct (not derived) per-tax
   detail. One :tax-application entity per (product-line × tax) pair."
  [{:db/ident       :tax-application/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Back-ref to the :product-display-type posting
                     this application annotates."}

   {:db/ident       :tax-application/tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :tax entity that was applied."}

   {:db/ident       :tax-application/base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The base amount this tax was computed against.
                     Differs from the posting's amount when other
                     taxes compound into the base (BR ICMS-on-net+IPI
                     case)."}

   {:db/ident       :tax-application/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The resulting tax amount."}

   {:db/ident       :tax-application/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs (typically inherited from the
                     tax-repartition lines) used by reports."}

   {:db/ident       :tax-application/compound-on
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Other :tax entities whose amounts were folded
                     into this application's :base (i.e. taxes whose
                     :tax/include-base-amount was true and which
                     preceded this one in the chain)."}

   {:db/ident       :tax-application/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Order within the compound chain. Lower numbers
                     are computed first; later applications see them
                     in their :compound-on. Required even with one
                     tax for round-tripping."}])

;; ============================================================================
;; Payment terms — Net N + early-discount window. Reusable
;; entities (one per term, e.g. NET30, NET14, 2/10-NET30) referenced
;; from transactions / invoices. The `payment-term.clj` helpers
;; compute :transaction/due-date + :transaction/discount-deadline
;; from :transaction/effective-date + :payment-term.
;; ============================================================================

(def ^:private payment-term-attrs
  [{:db/ident       :payment-term/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable id (\"NET30\", \"NET14\", \"DUE-ON-RECEIPT\",
                     \"2/10-NET30\")."}

   {:db/ident       :payment-term/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label (\"30 days net\")."}

   {:db/ident       :payment-term/net-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Days from invoice date until full payment is due.
                     0 means due on receipt."}

   {:db/ident       :payment-term/discount-pct
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional early-pay discount percentage
                     (e.g. 2.0M for 2%)."}

   {:db/ident       :payment-term/discount-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional days within which the discount applies
                     (e.g. 10 for 2/10-NET30)."}

   {:db/ident       :payment-term/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Invoice + line item — thin accounting-side invoice. NOT a CRM /
;; sales-pipeline replacement (downstream apps like beleg own that
;; domain). The kernel's :invoice carries:
;;   - the data needed to drive postings + Factur-X / XRechnung
;;   - lifecycle: :draft → :sent (auto-posts) → :paid (auto-set on
;;     reconciliation settle) → :cancelled (creates reversal tx)
;;   - the Factur-X XML payload (and optionally a Factur-X PDF) once
;;     einvoice-de generates them
;;
;; The actual accounting transaction (per HGB / SKR04 conventions)
;; lives in :transaction; :invoice points to it via :invoice/transaction.
;; Line items live in :invoice-line entities (cardinality-many ref
;; from :invoice) and carry the per-line VAT rate the country module
;; uses to split postings.
;; ============================================================================

(def ^:private invoice-attrs
  [{:db/ident       :invoice/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Invoice number (\"INV-2026-0001\"). Stable
                     identity across status transitions."}

   {:db/ident       :invoice/issue-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ausstellungsdatum / invoice date."}

   {:db/ident       :invoice/seller
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :partner entity issuing the invoice
                     (typically the user's own company partner)."}

   {:db/ident       :invoice/buyer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :partner entity being invoiced
                     (the customer)."}

   {:db/ident       :invoice/payment-term
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Materialized from issue-date + payment-term, OR
                     supplied directly. Aging reports filter on this."}

   {:db/ident       :invoice/discount-deadline
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/currency
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO 4217 currency code (\"EUR\", \"USD\")."}

   {:db/ident       :invoice/total-net
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/total-vat
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/total-gross
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Sum the customer owes. Reconciliation matches
                     bank-line amount against this for AR settlement."}

   {:db/ident       :invoice/lines
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Line items. Cardinality many; ordered via
                     :invoice-line/sequence."}

   {:db/ident       :invoice/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":draft | :sent | :paid | :cancelled. Lifecycle:
                       draft → sent (auto-creates accounting tx)
                       sent  → paid (set by reconciliation settle)
                       sent  → cancelled (creates reversal tx)"}

   {:db/ident       :invoice/sent-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/paid-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/cancelled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The accounting :transaction created on :sent.
                     Reconciliation walks back from this to flip the
                     invoice's status to :paid."}

   {:db/ident       :invoice/factur-x-xml
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The EN16931 / Factur-X / XRechnung XML payload
                     (UTF-8). Generated by einvoice-de.factur-x at
                     :sent transition when configured."}

   {:db/ident       :invoice/factur-x-pdf
    :db/valueType   :db.type/bytes
    :db/cardinality :db.cardinality/one
    :db/doc         "The Factur-X PDF/A-3 with embedded XML. Set
                     when the invoice was rendered to PDF (caller
                     supplies the PDF/A-3 input)."}

   {:db/ident       :invoice/buyer-reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Buyer-side reference / Leitweg-ID for B2G
                     XRechnung. Embeds in the EN16931 payload."}

   {:db/ident       :invoice/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "Free-text remarks (one entry per note)."}])

(def ^:private invoice-line-attrs
  [{:db/ident       :invoice-line/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Backref to parent :invoice. Redundant with
                     :invoice/lines but indexed for query."}

   {:db/ident       :invoice-line/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Display ordering (1, 2, 3 …)."}

   {:db/ident       :invoice-line/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice-line/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice-line/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :invoice-line/unit-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "UN/CEFACT unit code (\"HUR\"=hour, \"EA\"=each,
                     \"KGM\"=kg, \"DAY\"=day)."}

   {:db/ident       :invoice-line/unit-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net unit price."}

   {:db/ident       :invoice-line/vat-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "VAT percentage (e.g. 19.0M for German Regelsatz,
                     7.0M for ermäßigt, 0M for steuerfrei)."}

   {:db/ident       :invoice-line/vat-category
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "UNTDID 5305 code: S=standard, AA=reduced,
                     Z=zero, E=exempt, etc. Used by Factur-X."}

   {:db/ident       :invoice-line/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional explicit revenue account override.
                     When unset the country module's posting-builder
                     picks a default (e.g. SKR04 4400 for 19% sales)."}])

;; ============================================================================
;; Bank statement lines — imported from bank-csv parsers, matched
;; against open AR/AP, then committed as payment-receipt postings.
;;
;; Lifecycle:
;;   :unmatched   — just imported; no decision yet.
;;   :matched     — a reconciliation match has been suggested or
;;                  user-confirmed; not yet posted.
;;   :reconciled  — a posting has been created; the bank-line is
;;                  linked to it and its settled-against invoices.
;;   :ignored     — bookkeeper marked irrelevant (e.g. internal
;;                  transfer between own accounts already accounted
;;                  for elsewhere).
;;
;; Idempotency: `:bank-line/external-id` is unique-identity. The
;; ingestion code derives it as a hash of (bank, date, amount,
;; raw-row) so re-importing the same statement is a no-op.
;; ============================================================================

(def ^:private bank-line-attrs
  [{:db/ident       :bank-line/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable id derived from (bank, date, amount,
                     raw-row-hash). Re-importing the same statement
                     hits the same id and is idempotent."}

   {:db/ident       :bank-line/bank
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Bank/format keyword (:dkb :ing :chase :n26 …)."}

   {:db/ident       :bank-line/source-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Chart account this bank-line lands on (the bank-
                     side leg of the eventual posting). E.g. SKR04
                     1200 / 1210 / 1230. Required at ingestion so the
                     import driver knows which account to post."}

   {:db/ident       :bank-line/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :bank-line/value-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-line/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed: positive = inflow (credit on bank
                     statement), negative = outflow (debit). Mirrors
                     the bank's POV; the eventual posting's bank-side
                     amount has the same sign as this attribute."}

   {:db/ident       :bank-line/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-line/counterparty
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-line/counterparty-iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :bank-line/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text Verwendungszweck / memo. Used by the
                     reference-id matcher to detect invoice external-
                     ids embedded in the statement text."}

   {:db/ident       :bank-line/transaction-type
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Bank-side type label (Lastschrift, Gutschrift,
                     ACH_DEBIT, etc.). Free-form per importer."}

   {:db/ident       :bank-line/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Auto-categorizer output from bank-csv (e.g.
                     :miete, :gehalt, :einnahmen). Suggests a contra
                     account when no AR/AP match is found."}

   {:db/ident       :bank-line/raw-row
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Original CSV row joined with a separator. Stored
                     for audit / re-parse / diff against future
                     importer changes."}

   {:db/ident       :bank-line/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":unmatched | :matched | :reconciled | :ignored.
                     A reconciliation queue UI filters on this."}

   {:db/ident       :bank-line/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The bank-side posting created when the line is
                     reconciled. Set during commit-match!"}

   {:db/ident       :bank-line/reconciled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-time when the bank-line was reconciled."}])

;; ============================================================================
;; Analytic accounting (cost-center / profit-center / project) — ADR-012.
;;
;; A separate dimension orthogonal to the financial account hierarchy.
;; Postings can carry zero or more `:posting/analytic-distributions`,
;; each of which is a (plan, account, percent) triple. Used by:
;;   - German Kostenrechnung
;;   - Canadian SR&ED project tracking
;;   - US job costing
;; Phase-1 schema only; the report-time aggregator that consumes
;; distributions ships in Phase 1.5 alongside the declarative report
;; engine. (Actual cost-allocation algorithms — overhead allocation,
;; multi-step distribution — are deferred or remain consumer-app
;; concerns.)
;; ============================================================================

(def ^:private analytic-plan-attrs
  [{:db/ident       :analytic-plan/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identity (\"COST-CENTER\", \"PROJECT\",
                     \"DEPARTMENT\")."}

   {:db/ident       :analytic-plan/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :analytic-plan/applicability
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional gate: which posting context the plan
                     applies to. nil = all postings."}

   {:db/ident       :analytic-plan/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private analytic-account-attrs
  [{:db/ident       :analytic-account/path
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Hierarchical path within a plan
                     (\"COST-CENTER:Engineering:Frontend\")."}

   {:db/ident       :analytic-account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :analytic-account/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :analytic-account/plan
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :analytic-account/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :analytic-account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private analytic-distribution-attrs
  [{:db/ident       :analytic-distribution/plan
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Which plan this distribution applies under
                     (a posting may carry distributions in multiple
                     plans simultaneously — one per plan)."}

   {:db/ident       :analytic-distribution/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The analytic account the percent points at."}

   {:db/ident       :analytic-distribution/percent
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "0..100 inclusive. Sum-to-100 per plan is enforced
                     by the report engine, not by the schema."}

   {:db/ident       :analytic-distribution/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the posting this distribution annotates.
                     A posting may carry multiple distributions; we
                     reverse-traverse via :posting/_analytic-distribution-
                     posting at query time. Storing the back-ref directly
                     also keeps the distribution entity self-contained."}])

;; Attribute on posting holding the cardinality-many ref to
;; distributions. Lives in the posting namespace so consumers see the
;; whole posting shape in `(d/pull db [...] eid)`.

(def ^:private posting-analytic-attrs
  [{:db/ident       :posting/analytic-distributions
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Optional analytic-distribution refs annotating
                     this posting. Independent of the financial
                     :posting/account; used by management-reporting
                     queries and SR&ED-style project tracking."}])

;; ============================================================================
;; Per-account required analytic plans — ADR-022.
;;
;; Postings against an account that names plans here must carry
;; distributions in each named plan summing to 100%. Optional.
;; ============================================================================

(def ^:private account-analytic-attrs
  [{:db/ident       :account/required-analytic-plans
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Set of :analytic-plan entities that postings
                     against this account must populate. The posting
                     validator enforces a sum-to-100 invariant per
                     named plan. nil = no plan required."}])

;; ============================================================================
;; Ledgers — ADR-021.
;;
;; A first-class entity for parallel ledgers (IFRS / local GAAP / budget
;; / statistical). One primary ledger is auto-installed by
;; `kontor.ledger/install-defaults!`; consumers add secondary ledgers.
;; Sum-to-zero in a transaction is enforced PER LEDGER.
;; ============================================================================

(def ^:private ledger-attrs
  [{:db/ident       :ledger/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"primary\", \"ifrs\", \"hgb\",
                     \"budget\", \"statistical\")."}

   {:db/ident       :ledger/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :ledger/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":primary | :secondary | :adjustment
                     | :budget | :statistical"}

   {:db/ident       :ledger/framework
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting framework keyword.
                     :IFRS | :US-GAAP | :HGB | :ASBE | :NCRF | :ind-AS
                     | :local | ... — free-form, l10n-defined."}

   {:db/ident       :ledger/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this ledger when it
                     differs from the transaction commodity."}

   {:db/ident       :ledger/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private posting-ledger-attrs
  [{:db/ident       :posting/ledger
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The ledger this posting lives in. Optional in
                     schema; the posting-builder resolves to the
                     bootstrapped primary ledger when absent. The
                     sum-to-zero invariant runs PER LEDGER inside a
                     transaction (ADR-021)."}])

;; ============================================================================
;; Country + state + place-of-supply — ADR-023.
;;
;; First-class country and state entities, composite-tuple identity on
;; (country, code). External codes follow the ADR-019 pattern. Country
;; groups (EU / EEA / NAFTA) are data, not flags. The kernel ships no
;; geo data; l10n modules install the slice they need.
;; ============================================================================

(def ^:private country-attrs
  [{:db/ident       :country/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO 3166-1 alpha-2 (\"IN\", \"BR\", \"CA\")."}

   {:db/ident       :country/code-iso3
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO 3166-1 alpha-3 (\"IND\", \"BRA\", \"CAN\").
                     Optional — only loaded by l10n modules that
                     need it."}

   {:db/ident       :country/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "English name. Localized display names belong in
                     consumer apps."}

   {:db/ident       :country/default-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default trading commodity (INR, BRL, CAD…).
                     Hint only; does not constrain account commodity."}

   {:db/ident       :country/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :country-code entities — per-regulator
                     aliases beyond ISO. Mirrors ADR-019."}

   {:db/ident       :country/groups
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :country-group entities (EU, EEA, NAFTA…).
                     Many-to-many; a country may belong to several."}

   {:db/ident       :country/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private country-code-attrs
  [{:db/ident       :country-code/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the country this external code is for."}

   {:db/ident       :country-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :iso-3166-1-numeric, :un/m49,
                     :sap/land1, :in/customs, …"}

   {:db/ident       :country-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system."}

   {:db/ident       :country-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:country-code/country :country-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (country, regulator)
                     pairing exists at most once."}

   {:db/ident       :country-code/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional human-readable explanation."}])

(def ^:private country-group-attrs
  [{:db/ident       :country-group/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"EU\", \"EEA\", \"NAFTA\",
                     \"G7\", \"USMCA\")."}

   {:db/ident       :country-group/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private state-attrs
  [{:db/ident       :state/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent country. Required."}

   {:db/ident       :state/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO 3166-2 suffix (\"MH\", \"QC\", \"SP\", \"JAL\").
                     Just the local part — not the full \"IN-MH\"."}

   {:db/ident       :state/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :state/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:state/country :state/code]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (country, state-code)
                     pairing exists at most once."}

   {:db/ident       :state/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :state-code entities — per-regulator
                     codes beyond ISO 3166-2 (Indian GSTN, Brazilian
                     IBGE, Canadian CRA province code, etc.)."}

   {:db/ident       :state/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private state-code-attrs
  [{:db/ident       :state-code/state
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the state this external code is for."}

   {:db/ident       :state-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :in/gst, :br/ibge, :ca/cra,
                     :iso-3166-2, :sap/bland, :sat/c-estado, …"}

   {:db/ident       :state-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system.
                     E.g. \"27\" (GSTN for Maharashtra),
                     \"35\" (IBGE for São Paulo),
                     \"13\" (CRA for Quebec)."}

   {:db/ident       :state-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:state-code/state :state-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity."}

   {:db/ident       :state-code/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private partner-state-attrs
  [{:db/ident       :partner/state
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Partner's registered state — billing / address.
                     Distinct from :transaction/place-of-supply.
                     Optional (consumer-side bootstrap)."}])

(def ^:private transaction-pos-attrs
  [{:db/ident       :transaction/place-of-supply
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Place of supply (ADR-023). Ref to :state.

                     India: drives the CGST+SGST (intra-state) vs
                     IGST (inter-state) vs UTGST (union territory)
                     dispatch — compare against the issuer's state.

                     Other jurisdictions (Canada inter-provincial,
                     Mexico) may set this when POS differs from the
                     partner's registered state. Optional; absent
                     means 'same as partner state' by convention."}])

;; ============================================================================
;; Multi-attestation — ADR-024.
;;
;; One transaction may carry zero or more government-issued artifacts
;; (IRN, e-way bill, PAC stamp UUID, NF-e access key, fapiao number,
;; ZATCA ICV, etc.) each with its own format, token, validity window,
;; lifecycle state, and depends-on graph. Coexists with the legacy
;; singular :transaction/clearance-token; the cardinality-many is
;; authoritative when both are present.
;; ============================================================================

(def ^:private attestation-attrs
  [{:db/ident       :attestation/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the transaction this attestation
                     belongs to."}

   {:db/ident       :attestation/format
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Format keyword. Conventions:
                     :in/irn :in/ewb-part-a :in/ewb-part-b
                     :br/nfe-44 :mx/cfdi-uuid :cn/fapiao-20
                     :sa/zatca-icv :sa/zatca-pih :tr/efatura
                     :kr/nts-chain :it/sdi-id"}

   {:db/ident       :attestation/token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The issued artifact identifier (IRN hash, UUID,
                     access-key, …)."}

   {:db/ident       :attestation/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending | :issued | :revoked | :expired
                     | :superseded"}

   {:db/ident       :attestation/issued-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the authority's response landed."}

   {:db/ident       :attestation/valid-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional start of the legal validity window."}

   {:db/ident       :attestation/valid-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional end of the legal validity window.
                     E-way bills: 1 day per 200 km (regular) or
                     1 day per 20 km (over-dimensional cargo)."}

   {:db/ident       :attestation/depends-on
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to other :attestation entities this one
                     depends on. India: EWB Part A derives from the
                     IRN, so the EWB attestation depends-on the IRN."}

   {:db/ident       :attestation/payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical bytes sent to / received from the
                     authority. Required for cryptographic-stamp
                     regimes (KSA ZATCA Phase 2, Turkey, Korea) where
                     the bytes themselves are the legal record."}

   {:db/ident       :attestation/payload-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "SHA-256 of :attestation/payload, hex-encoded.
                     PIH (previous-invoice-hash) chains reference
                     this to link consecutive attestations."}

   {:db/ident       :attestation/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :attestation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:attestation/transaction :attestation/format]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One (transaction, format) pair exists at most
                     once. Re-issuing replaces."}])

(def ^:private transaction-attestations-attrs
  [{:db/ident       :transaction/attestations
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :attestation entities. ADR-024.

                     Coexists with :transaction/clearance-token
                     (singular string). When both present, the
                     cardinality-many is authoritative.

                     Multi-attestation jurisdictions (IN IRN+EWB,
                     IT SdI synthetic-issuer, KR NTS chain) MUST use
                     this; single-attestation jurisdictions MAY use
                     either."}])

;; ============================================================================
;; Document composition — ADR-025.
;;
;; Mexico's CFDI is one envelope + N stacked complementos (Pagos,
;; Carta Porte, Nómina, TFD). Each complemento is its own XSD;
;; serialization splices them into the envelope's <Complemento>
;; parent in :complemento/sequence order. The kernel stores opaque
;; payload bytes; XSD validation lives in the l10n module that owns
;; the namespace.
;; ============================================================================

(def ^:private complemento-attrs
  [{:db/ident       :complemento/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the transaction this complemento
                     attaches to."}

   {:db/ident       :complemento/namespace
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical XML namespace URI.
                     E.g. \"http://www.sat.gob.mx/CartaPorte31\",
                     \"http://www.sat.gob.mx/Pagos20\",
                     \"http://www.sat.gob.mx/TimbreFiscalDigital\"."}

   {:db/ident       :complemento/format
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Convenience identifier keyword.
                     E.g. :mx/cfdi-pagos-2.0 :mx/cfdi-carta-porte-3.1
                     :mx/cfdi-nomina-1.2 :mx/cfdi-tfd-1.1
                     :ubl/factur-x-additional-doc."}

   {:db/ident       :complemento/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Ordering within the envelope. Some XSDs enforce
                     a defined order; safe to default to insertion
                     order (0, 100, 200, …)."}

   {:db/ident       :complemento/payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The XML fragment as a string. The kernel does
                     not validate against the XSD; the emitter in
                     the l10n module does."}

   {:db/ident       :complemento/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Soft-supersede flag. Set to false when a later
                     complemento replaces this one (idempotency)."}

   {:db/ident       :complemento/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:complemento/transaction :complemento/namespace]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One fragment per (transaction, namespace)."}])

(def ^:private transaction-complementos-attrs
  [{:db/ident       :transaction/complementos
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :complemento entities. ADR-025."}])

;; ============================================================================
;; Effective-dated tax rates — ADR-026.
;;
;; Optional :tax/effective-from / :tax/effective-until on existing
;; :tax entities. TaxProvider selects the tax record whose validity
;; window contains the transaction's effective-date. Drives India
;; GST 2.0 (pre-2025-09-22 vs current), Brazil IBS/CBS transition,
;; Mexico IEPS annual cuotas, Germany 7%-vs-19% restaurant VAT.
;; ============================================================================

(def ^:private tax-effective-window-attrs
  [{:db/ident       :tax/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Start of the rate's legal validity window.
                     Nil means -infinity (always-effective). ADR-026."}

   {:db/ident       :tax/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "End of the rate's legal validity window
                     (open interval: this is the first instant the
                     rate is no longer in effect). Nil means
                     +infinity. ADR-026."}])

;; ============================================================================
;; Valuation book — ADR-027.
;;
;; Orthogonal-to-:ledger lens picking which cost basis applies to a
;; stock movement. One physical inventory may carry several books in
;; parallel (FIFO + Standard + IFRS). One "primary" book is auto-
;; installed by `kontor.valuation/install-defaults!`.
;; ============================================================================

(def ^:private valuation-book-attrs
  [{:db/ident       :valuation-book/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"primary\", \"ifrs\",
                     \"tax-de\", \"management\")."}

   {:db/ident       :valuation-book/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :valuation-book/framework
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting framework keyword.
                     :legal | :group | :ifrs | :us-gaap | :hgb
                     | :tax-de | :management | … free-form."}

   {:db/ident       :valuation-book/cost-method
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":fifo | :lifo | :avg | :standard | :specific.
                     CostingProvider impls dispatch on this."}

   {:db/ident       :valuation-book/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this book when it differs
                     from the transaction commodity. Optional."}

   {:db/ident       :valuation-book/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Valuation layer + consumption + adjustment — ADR-028.
;;
;; Three immutable fact entities. Layer = receipt event; consumption
;; = issue event referencing a layer; adjustment = landed-cost /
;; revaluation event referencing a layer. Remaining qty + current
;; cost are *views* (kontor.valuation/qty-remaining + /current-cost).
;; ============================================================================

(def ^:private valuation-layer-attrs
  [{:db/ident       :valuation-layer/book
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-book."}

   {:db/ident       :valuation-layer/item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref. The kernel does not model 'item';
                     the consumer-side inventory module defines what
                     an item is and points the layer at it. (ADR-010
                     scope honesty.)"}

   {:db/ident       :valuation-layer/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :lot. Lot-isolated FIFO uses
                     this to keep separate stacks per lot."}

   {:db/ident       :valuation-layer/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that created
                     this layer (the receipt event)."}

   {:db/ident       :valuation-layer/qty-original
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity received. Immutable. Remaining quantity
                     is a derived view: qty-original − Σ consumption.qty."}

   {:db/ident       :valuation-layer/unit-cost-original
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Per-unit cost at receipt. Immutable. Current cost
                     is a derived view that folds in adjustments."}

   {:db/ident       :valuation-layer/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Cost currency."}

   {:db/ident       :valuation-layer/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid time of the receipt. Used to order FIFO/LIFO
                     stacks. Distinct from :origin-transaction's tx-time."}

   {:db/ident       :valuation-layer/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private layer-consumption-attrs
  [{:db/ident       :layer-consumption/layer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-layer."}

   {:db/ident       :layer-consumption/qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity consumed FROM the referenced layer in
                     this single event. One issue may produce multiple
                     consumption rows (one per drawn layer)."}

   {:db/ident       :layer-consumption/unit-cost-at-consumption
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Book value per unit at the moment of consumption.
                     Folds in any :layer-adjustment that was applied
                     before this event."}

   {:db/ident       :layer-consumption/issue-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that issued this
                     consumption (the outbound move)."}

   {:db/ident       :layer-consumption/issued-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid time of the issue."}])

;; ============================================================================
;; Entity / legal-entity / consolidation entity — ADR-031.
;;
;; First-class :entity for multi-entity / transnational deployments.
;; Optional refs on :posting / :ledger / :valuation-book gate the
;; per-(entity, ledger, commodity) sum-to-zero invariant extension
;; in kontor.posting/validate. No kernel-level bootstrap — consumers
;; install their entity tree as data.
;; ============================================================================

(def ^:private entity-attrs
  [{:db/ident       :entity/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"acme-de\", \"acme-us\",
                     \"acme-group\", \"acme-eliminations\")."}

   {:db/ident       :entity/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :entity/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :country. Synthetic entities
                     (:elimination, :consolidation) may omit."}

   {:db/ident       :entity/functional-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this entity's standalone
                     books. Optional — synthetic entities may run in
                     a group currency selected at consolidation time."}

   {:db/ident       :entity/parent-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Group hierarchy. Self-reference; root entity has
                     no parent. Matches Odoo's :parent_id pattern."}

   {:db/ident       :entity/accounting-standard
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":hgb | :us-gaap | :br-gaap | :ifrs | :local
                     | … free-form. Drives reporting + tax filing
                     choices; consumed by l10n modules."}

   {:db/ident       :entity/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":operating (default) — a real legal entity
                                that books real transactions.
                     :elimination — a synthetic entity holding
                                consolidation eliminations
                                (NetSuite's Elimination Subsidiary).
                     :consolidation — a synthetic entity representing
                                the group view (the rollup target).

                     Queries can scope reports by kind: \"operating
                     only\" for statutory; \"operating + elimination\"
                     for consolidated; \"consolidation\" for the
                     group lens."}

   {:db/ident       :entity/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private posting-entity-attrs
  [{:db/ident       :posting/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :entity (ADR-031). When any
                     posting in a transaction carries this, the
                     sum-to-zero invariant extends to
                     per-(entity, ledger, commodity). Mixed-mode
                     (some postings tagged, some not) is rejected.

                     Placed on the line, not the transaction header
                     — matches SAP ACDOCA.RBUKRS, Oracle
                     XLA_AE_LINES.LEGAL_ENTITY_ID, NetSuite per-line
                     subsidiary, D365 per-LE DataAreaId."}])

(def ^:private ledger-entity-attrs
  [{:db/ident       :ledger/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :entity (ADR-031). Per-ERP
                     consensus: ledger is per-entity. Synthetic
                     consolidation entities have their own ledger.
                     Schema-optional for single-entity tenants."}])

(def ^:private valuation-book-entity-attrs
  [{:db/ident       :valuation-book/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :entity (ADR-031). Each entity
                     has its own valuation books; one entity may
                     run several books in parallel (ADR-027)."}])

;; ============================================================================
;; Schedule entity + occurrence log — ADR-032.
;;
;; Cross-cutting primitive: a recurring posting sequence used by
;; asset depreciation, revenue recognition, subscription billing,
;; lease amortization, PTO accrual, prepaid amortization, etc. The
;; kernel ships the entity + occurrence log; consumers ship the
;; rule-evaluation engine that decides per-period amounts.
;; ============================================================================

(def ^:private schedule-attrs
  [{:db/ident       :schedule/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"asset-1234-dep\",
                     \"sub-acme-2026-q3-rev\", \"lease-bldg-01\")."}

   {:db/ident       :schedule/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :schedule/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":depreciation | :revenue-recognition
                     | :subscription-billing | :lease-amortization
                     | :pto-accrual | :prepaid-amortization
                     | … free-form. Consumers extend."}

   {:db/ident       :schedule/origin-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref — the asset / contract /
                     subscription / lease this schedule belongs to.
                     Consumer defines what that entity is."}

   {:db/ident       :schedule/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "First scheduled occurrence (inclusive)."}

   {:db/ident       :schedule/end-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last scheduled occurrence (inclusive). Optional;
                     nil = indefinite."}

   {:db/ident       :schedule/frequency
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":daily | :weekly | :monthly | :quarterly |
                     :annual | :custom. Consumers compute the next
                     occurrence date using this."}

   {:db/ident       :schedule/total-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Total amount to be amortized over the schedule.
                     Optional — only meaningful for finite schedules
                     (depreciation, prepaid amortization). Schedules
                     where per-period amount is computed elsewhere
                     (subscription billing with variable usage) omit
                     this."}

   {:db/ident       :schedule/total-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity for :schedule/total-amount."}

   {:db/ident       :schedule/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":active | :paused | :completed | :cancelled."}

   {:db/ident       :schedule/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :schedule/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private schedule-occurrence-attrs
  [{:db/ident       :schedule-occurrence/schedule
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :schedule. Back-pointer."}

   {:db/ident       :schedule-occurrence/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "1, 2, 3, … The Nth firing of the schedule."}

   {:db/ident       :schedule-occurrence/scheduled-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The valid-time date this occurrence is for.
                     (E.g. \"depreciation for month 2026-05\".)"}

   {:db/ident       :schedule-occurrence/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to the kernel :transaction that
                     this occurrence produced."}

   {:db/ident       :schedule-occurrence/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "This period's amount. Consumer-computed."}

   {:db/ident       :schedule-occurrence/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :schedule-occurrence/fired-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Wall-clock time the occurrence was recorded.
                     Distinct from :scheduled-date (the valid-time)
                     and from the underlying datahike tx-time."}

   {:db/ident       :schedule-occurrence/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:schedule-occurrence/schedule
                     :schedule-occurrence/sequence]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity: one occurrence per
                     (schedule, sequence) pair. Idempotent — re-
                     firing period 7 collapses to the existing row."}])

(def ^:private layer-adjustment-attrs
  [{:db/ident       :layer-adjustment/layer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-layer."}

   {:db/ident       :layer-adjustment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed TOTAL amount (not per-unit). Adds to the
                     layer's total cost; positive for landed cost
                     additions, negative for write-downs."}

   {:db/ident       :layer-adjustment/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":landed-cost | :revaluation | :correction
                     | :write-down | :write-up | … free-form."}

   {:db/ident       :layer-adjustment/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that booked this
                     adjustment (the landed-cost voucher / revaluation)."}

   {:db/ident       :layer-adjustment/applied-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :layer-adjustment/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Status-transition + status-history (ADR-034)
;;
;; Cross-cutting state-machine primitive. Companions seed their own
;; transition vocabulary; the kernel ships zero seed data. See
;; `kontor.status-machine` for the public surface.
;; ============================================================================

(def ^:private status-transition-attrs
  [{:db/ident       :status-transition/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator: which entity type this transition
                     applies to. :order, :order-item, :invoice,
                     :requirement, :shipment, … Consumers extend."}

   {:db/ident       :status-transition/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The attribute on the entity carrying this state.
                     Typically :order/status, :invoice/status, etc.
                     One entity can have multiple facets — multiple
                     concurrent state machines on the same row."}

   {:db/ident       :status-transition/from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "From-state keyword. Use a :*/nil sentinel keyword
                     (e.g. :order.status/nil) for the 'new entity'
                     pseudo-state — datahike's nil-handling is awkward
                     for tx values."}

   {:db/ident       :status-transition/to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :status-transition/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable transition name (\"Approve Order\",
                     \"Mark Paid\"). For UI / log rendering."}

   {:db/ident       :status-transition/applies-to-org
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :entity ref (ADR-031). When nil, the
                     transition applies tenant-wide. When set, scopes
                     to that org as an override that does NOT delete
                     the global row — both can coexist; the predicate
                     prefers the org-specific match."}

   {:db/ident       :status-transition/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Soft-delete flag. Inactive transitions are
                     ignored by `legal-transition?` but retained for
                     audit-history queries."}

   {:db/ident       :status-transition/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :status-transition/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:status-transition/entity-type
                     :status-transition/facet
                     :status-transition/from
                     :status-transition/to
                     :status-transition/applies-to-org]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity — one row per (entity-type,
                     facet, from, to, applies-to-org) combination."}])

(def ^:private status-history-attrs
  [{:db/ident       :status-history/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The entity that transitioned. Generic ref —
                     could be an order, invoice, requirement, etc."}

   {:db/ident       :status-history/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized copy of the entity's type so cross-
                     entity queries don't need to dispatch on the
                     ref's namespace."}

   {:db/ident       :status-history/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :status-history/from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "From-state; nil-sentinel for entity creation."}

   {:db/ident       :status-history/to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :status-history/changed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time of the transition: when, semantically,
                     the change applied. Distinct from datahike's
                     :db/txInstant which records when the datom was
                     written. ADR-008 bitemporality."}

   {:db/ident       :status-history/changed-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who triggered the transition; ref to a
                     :create/uid entity. Optional but recommended."}

   {:db/ident       :status-history/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Codified reason code (ADR-038). Auditor-friendly
                     vocabulary for compliance reports. Canonical
                     starter set documented in kontor.status-machine
                     ns; consumers extend with domain-specific codes."}

   {:db/ident       :status-history/reason-note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional free-text human story alongside the
                     codified :reason. Required when :reason is
                     :other. Where :reason answers 'what kind,'
                     :reason-note answers 'what specifically.'"}

   {:db/ident       :status-history/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc — the proof an
                     auditor would ask for (customer email, credit
                     memo PDF, regulator clearance token, manager
                     override note). Kernel doesn't store bytes;
                     consumer attaches whatever artifact."}

   {:db/ident       :status-history/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to the kernel :transaction that
                     CAUSED this status change. E.g. an invoice's
                     transition to :posted happens because an
                     AcctgTrans was created — the AcctgTrans's
                     :transaction goes here."}])

;; ============================================================================
;; Audit-doc + approval-policy (ADR-038)
;;
;; Minimal kernel entities to support codified-reason vocabularies +
;; supporting docs + SoD enforcement. See ADR-038 for the design
;; rationale and `kontor.status-machine` for the enforcement code.
;; ============================================================================

(def ^:private audit-doc-attrs
  [{:db/ident       :audit-doc/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier."}

   {:db/ident       :audit-doc/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":credit-memo | :customer-email | :vendor-email |
                     :uploaded-pdf | :wet-signature-pdf |
                     :regulator-clearance | :manager-override |
                     :compliance-attestation | … consumers extend."}

   {:db/ident       :audit-doc/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label for the artifact."}

   {:db/ident       :audit-doc/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :audit-doc/content-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "SHA-256 of the artifact for integrity
                     verification. The kernel doesn't compute this;
                     consumer derives it at upload time."}

   {:db/ident       :audit-doc/storage-uri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Where the consumer stores the artifact bytes
                     ('s3://...', 'file://...', 'https://...',
                     'ipfs://...'). Kernel is storage-agnostic."}

   {:db/ident       :audit-doc/uploaded-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :audit-doc/uploaded-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private approval-policy-attrs
  [{:db/ident       :approval-policy/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Which entity type this policy applies to.
                     Mirrors :status-transition/entity-type."}

   {:db/ident       :approval-policy/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :approval-policy/transition-from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :approval-policy/transition-to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :approval-policy/applies-to-org
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional per-org scope (per ADR-031). Tenant-
                     wide when absent; org-specific overrides coexist
                     with the global."}

   {:db/ident       :approval-policy/rule
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":no-self-approval — recorded actor must differ
                     from :create/uid of the entity.
                     :requires-supporting-doc — :supporting-doc must
                     be set in the change-spec.
                     :requires-non-empty-reason-note — :reason-note
                     required.
                     … future rules extend the vocabulary."}

   {:db/ident       :approval-policy/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :approval-policy/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :approval-policy/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:approval-policy/entity-type
                     :approval-policy/facet
                     :approval-policy/transition-from
                     :approval-policy/transition-to
                     :approval-policy/rule
                     :approval-policy/applies-to-org]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  "Full kernel schema as one transactable vector. Order matters where
   refs are involved — but datahike resolves refs by ident so within
   one transact call the order of attribute definitions is irrelevant
   for refs that point to entities created in a later tx."
  (vec
   (concat
    audit-attrs
    commodity-attrs
    lot-attrs
    account-attrs
    account-code-attrs                   ; ADR-019
    document-type-attrs                  ; ADR-020
    account-tag-attrs
    journal-attrs
    partner-attrs
    fiscal-position-attrs
    tax-attrs
    tax-rep-attrs
    tax-group-attrs
    tax-application-attrs                ; ADR-016
    period-attrs
    balance-assertion-attrs
    transaction-attrs                    ; +pending-attestation + clearance-token (ADR-018)
    posting-attrs                        ; +tax-breakdown + clearance-token
    payment-term-attrs
    invoice-attrs
    invoice-line-attrs
    bank-line-attrs
    analytic-plan-attrs
    analytic-account-attrs
    analytic-distribution-attrs
    posting-analytic-attrs
    account-analytic-attrs               ; ADR-022
    ledger-attrs                         ; ADR-021
    posting-ledger-attrs                 ; ADR-021
    country-attrs                        ; ADR-023
    country-code-attrs                   ; ADR-023
    country-group-attrs                  ; ADR-023
    state-attrs                          ; ADR-023
    state-code-attrs                     ; ADR-023
    partner-state-attrs                  ; ADR-023
    transaction-pos-attrs                ; ADR-023
    attestation-attrs                    ; ADR-024
    transaction-attestations-attrs       ; ADR-024
    complemento-attrs                    ; ADR-025
    transaction-complementos-attrs       ; ADR-025
    tax-effective-window-attrs           ; ADR-026
    valuation-book-attrs                 ; ADR-027
    valuation-layer-attrs                ; ADR-028
    layer-consumption-attrs              ; ADR-028
    layer-adjustment-attrs               ; ADR-028
    entity-attrs                         ; ADR-031
    posting-entity-attrs                 ; ADR-031
    ledger-entity-attrs                  ; ADR-031
    valuation-book-entity-attrs          ; ADR-031
    schedule-attrs                       ; ADR-032
    schedule-occurrence-attrs            ; ADR-032
    status-transition-attrs              ; ADR-034
    status-history-attrs                  ; ADR-034
    audit-doc-attrs                       ; ADR-038
    approval-policy-attrs                 ; ADR-038
    partner-merge-attrs                   ; ADR-039
    bank-account-attrs                    ; ADR-039
    partner-bank-account-attrs            ; ADR-039
    partner-tag-attrs)))                  ; ADR-039

(defn install!
  "Transact the kernel schema into a connection. Idempotent — re-running
   on a connection that already has the schema produces empty ops on
   each unchanged ident.

   Returns the resulting tx-report."
  [conn]
  (d/transact conn all))

(ns kontor.schema
  "Datahike schema for the accounting kernel.

   Every attribute is namespaced under one of the kernel namespaces
   (see CLAUDE.md and ADR-002). The intent is that this schema can be
   transacted into a datahike connection that already holds other
   namespaces (e.g. beleg's :kontor.invoice/* :customer/*) without any
   collision. That cohabitation is an architectural invariant — see
   ADR-002 in doc/decisions.md.

   Bitemporal modeling per ADR-048 (supersedes the original ADR-008
   posting-attribute approach):
     - transaction time = :db/txInstant (datahike-supplied, free)
     - valid time       = :tx/valid-from on the writing tx
                          (kontor.bitemporal). All postings written by
                          one tx share that tx's valid-from. The kernel
                          builders stamp it from
                          :kontor.transaction/effective-date.
     - the resolver in kontor.bitemporal answers per-attribute
       polygons; postings are append-only so :tx/valid-to is always
       forever for them.

   Sealing per ADR-007:
     - :kontor.posting/posted-at is the seal marker. Once set, the application
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
  [{:db/ident       :kontor.audit/create-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who created this entity. Domain-level audit
                     field; the actual tx timestamp lives on the datom
                     via :db/txInstant."}

   {:db/ident       :kontor.audit/write-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who last wrote this entity at the application
                     level (last logical update, not last tx)."}])

;; ============================================================================
;; Commodity — currencies and other tradeable units.
;;
;; PTA-style: keep small. A commodity is identified by its symbol (EUR,
;; USD, BTC). Precision is the number of fractional digits used for
;; storage and display. Currency commodities have :kontor.commodity/iso-4217
;; populated; non-currency commodities (stock, crypto) do not.
;; ============================================================================

(def ^:private commodity-attrs
  [{:db/ident       :kontor.commodity/symbol
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO-4217 code for fiat (EUR, USD, CAD), ticker for
                     other commodities. Identity attribute."}

   {:db/ident       :kontor.commodity/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.commodity/precision
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Number of fractional digits used for amounts in this
                     commodity. EUR/USD = 2; JPY = 0; BTC = 8."}

   {:db/ident       :kontor.commodity/iso-4217
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO-4217 alphabetic currency code, when applicable.
                     Set iff this is a fiat currency."}

   ;; ADR-090: data-centric concept-iri seam. FIBO publishes currency
   ;; classes (https://www.omg.org/spec/EDMC-FIBO/...); ISO publishes
   ;; canonical IRIs for ISO-4217 codes. Substrate carries the IRI so a
   ;; consumer can RDF-export or align with FIBO without redoing the
   ;; mapping at every consumer.
   {:db/ident       :kontor.commodity/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional IRI identifying this commodity in an
                     external taxonomy (FIBO currency class, ISO IRI,
                     internal gist URI). ADR-090."}])

;; ============================================================================
;; FX rate — exchange-rate sample for currency translation.
;;
;; ADR-072. One entity per (from-commodity, to-commodity, at-date,
;; rate-type) sample. The `:kontor.fx-rate/by-tuple` composite gives upsert
;; semantics — repeated transacts replace prior samples for the same
;; key. `:kontor.fx-rate/source` carries provenance (`:ecb`, `:manual`, etc.);
;; `:kontor.fx-rate/source-doc` is a free-form pointer to the underlying record
;; (URL, CSV path, audit-doc eid as a string) so downstream auditors
;; can chase the number to its origin.
;;
;; Rate-type vocabulary follows IAS 21 / ASC 830:
;;   :spot       — point-in-time market rate (default)
;;   :closing    — period-end spot used for monetary BS items
;;   :average    — period-average used for P&L per IAS 21
;;   :opening    — period-open spot (rarely needed; mirrors :closing)
;;   :historical — frozen at acquisition; non-monetary items
;;
;; The kernel installs no rate data. `kontor.fx.fx-rate-provider` impls
;; either fill this table (via `kontor.fx.fx-rate-provider/save-rates!`)
;; or sit alongside it (Avalara/XE adapters fetch on demand). The
;; schema is what makes `StaticTableProvider` work without an
;; extra-DB cache.
;; ============================================================================

(def ^:private fx-rate-attrs
  [{:db/ident       :kontor.fx-rate/from-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :commodity. The base commodity (the \"1 of
                     this\" side of the quote)."}

   {:db/ident       :kontor.fx-rate/to-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :commodity. The quote commodity (the
                     \"= N of this\" side)."}

   {:db/ident       :kontor.fx-rate/at-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Sample date — the as-of for which this rate is
                     authoritative. The kernel never silently
                     interpolates: a lookup at date D with no exact
                     sample falls back per the provider's policy
                     (typically: last sample on or before D)."}

   {:db/ident       :kontor.fx-rate/rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Multiplier: amount-in-from-commodity × rate =
                     amount-in-to-commodity. BigDecimal — never a
                     double."}

   {:db/ident       :kontor.fx-rate/rate-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":spot | :closing | :average | :opening |
                     :historical — per IAS 21 / ASC 830."}

   {:db/ident       :kontor.fx-rate/source
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Provenance: :ecb | :manual | :xe | :oanda |
                     :fed-h10 | :customer-imported | … free-form
                     keyword. Used in audit reports and to filter
                     out non-authoritative samples."}

   {:db/ident       :kontor.fx-rate/source-doc
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-form pointer to the underlying source —
                     a URL, a CSV filename, an :audit-doc eid as a
                     string. Optional."}

   {:db/ident       :kontor.fx-rate/by-tuple
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.fx-rate/from-commodity
                     :kontor.fx-rate/to-commodity
                     :kontor.fx-rate/at-date
                     :kontor.fx-rate/rate-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. Re-transacting the same
                     (from, to, date, type) replaces the prior
                     :rate / :source / :source-doc."}])

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
  [{:db/ident       :kontor.lot/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lot/acquired-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When this lot was acquired (valid-time)."}

   {:db/ident       :kontor.lot/cost-basis
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Cost basis per unit, in the cost commodity."}

   {:db/ident       :kontor.lot/cost-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lot/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional human label for specific-identification
                     disposal (\"first batch\", \"Q3 raise\", etc.)."}

   {:db/ident       :kontor.lot/expires-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional expiry / best-before date. Drives FEFO
                     (first-expiry-first-out) consumption and the
                     :fifo-exp / :lifo-exp reservation walk
                     (kontor-inventory, ADR-060)."}])

;; ============================================================================
;; Account — chart-of-accounts entry.
;;
;; PTA-style: a hierarchical name like \"Assets:Bank:Checking\" is the
;; canonical identity. We additionally carry the Odoo-style :kontor.account/code
;; (a numeric or alphanumeric tag — SKR03 has 1200, QBO uses 1010, etc.)
;; for compatibility with country charts that ship code-keyed.
;;
;; Account hierarchy uses :kontor.account/parent (single-parent tree). Code-prefix
;; rollup (Odoo's account_group) is a query-time concept on top, not a
;; schema concept.
;; ============================================================================

(def ^:private account-attrs
  [{:db/ident       :kontor.account/path
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Hierarchical name in PTA notation, e.g.
                     \"Assets:Bank:Checking\" or
                     \"Income:Sales:EU\". Identity attribute."}

   {:db/ident       :kontor.account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional country-specific code (SKR03 \"1200\",
                     QBO \"1010\"). Indexed for prefix-rollup queries."}

   {:db/ident       :kontor.account/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name, possibly localized."}

   {:db/ident       :kontor.account/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :asset :liability :equity :income :expense.
                     Determines balance-sheet vs P&L classification and
                     the natural sign of postings."}

   {:db/ident       :kontor.account/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent account in the hierarchy. nil for roots."}

   {:db/ident       :kontor.account/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default commodity. nil = polymorphic
                     (postings carry their own commodity)."}

   {:db/ident       :kontor.account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Inactive accounts cannot be posted to but remain
                     in historical reports."}

   {:db/ident       :kontor.account/reconcilable
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether postings against this account can be
                     reconciled (typically true for receivables, payables,
                     bank accounts)."}

   {:db/ident       :kontor.account/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs for report-time aggregation
                     (Odoo's account_account_tag M2M, used by the
                     declarative report engine)."}

   ;; ADR-073 review P1-73-1: monetary vs non-monetary classification
   ;; for IAS 21 / ASC 830 currency translation. Monetary items (cash,
   ;; AR, AP, loans) translate at the closing rate; non-monetary items
   ;; (PP&E, inventory at cost, prepaid expenses, equity) translate at
   ;; the historical rate from acquisition. Default for asset/liability
   ;; is monetary; equity is non-monetary; income/expense translates
   ;; at the average rate so :monetary? is don't-care.
   {:db/ident       :kontor.account/monetary?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "IAS 21 / ASC 830 monetary classification. Default
                     when absent: monetary for :kontor.asset/:liability,
                     non-monetary for :equity, irrelevant for
                     :income/:expense (those translate at :average).
                     Customers with non-monetary asset holdings
                     (PP&E, inventory-at-cost, prepaid expenses) set
                     this to `false` so kontor.provider.consolidation
                     translates them at :historical rate per ADR-073."}

   ;; ADR-019: regulator-specific external codes. The single
   ;; :kontor.account/code above is the kernel-facing code (often = the
   ;; dominant regulator's code, e.g. SKR04 in DE). When an account
   ;; answers to multiple regulators (BR analytical → Plano
   ;; Referencial → SPED Contábil; DE internal → SKR04 → DATEV; or
   ;; management → IFRS group), the :account-code entities each
   ;; carry one (regulator, code) pairing.
   {:db/ident       :kontor.account/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Many-ref to :account-code entities, one per
                     (account, regulator) pair. See ADR-019."}

   ;; ADR-090: data-centric concept-iri seam — generalized from
   ;; :kontor.account-tag/concept-iri. Lets an account carry one IRI from an
   ;; external concept vocabulary (XBRL line item, FIBO Account class,
   ;; internal gist URI, etc.). Distinct from :kontor.account/external-codes
   ;; (which carries regulator short-codes via M2M :account-code refs)
   ;; — concept-iri is the *cross-system concept identity* a McComb-style
   ;; consumer dereferences; external-codes are the *per-regulator
   ;; reporting codes* a country module routes to. Both can coexist on
   ;; one account; neither is required.
   {:db/ident       :kontor.account/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional IRI binding this account to an external
                     concept (XBRL line item, FIBO class, internal
                     gist URI). Substrate carries; consumer dereferences.
                     ADR-090."}])

;; ============================================================================
;; Document-type registry — ADR-020.
;;
;; First-class entity for the regulator-recognized kind of a fiscal
;; document. Registered per-country at module install time; referenced
;; by :kontor.transaction/document-type.
;; ============================================================================

(def ^:private document-type-attrs
  [{:db/ident       :kontor.document-type/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Regulator's short code. BR: '55' (NF-e),
                     '65' (NFC-e), '57' (CT-e), '58' (MDF-e), 'SE'
                     (NFS-e). CN: '01' (special VAT fapiao), '02'
                     (general fapiao), '10' (electronic general),
                     '65' (fully-digital).  Not globally unique;
                     identity is (code, jurisdiction)."}

   {:db/ident       :kontor.document-type/jurisdiction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Tax authority that defines this code. Conventional:
                     :br/sefaz, :cn/sta, :de/finanzamt, :ca/cra,
                     :ar/afip, :cl/sii. Co-keys the identity with code."}

   {:db/ident       :kontor.document-type/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.document-type/code :kontor.document-type/jurisdiction]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity (code, jurisdiction)."}

   {:db/ident       :kontor.document-type/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name, may include native-script:
                     'Nota Fiscal Eletrônica — mercadorias' or
                     '增值税专用发票 (Special VAT Fapiao)'."}

   {:db/ident       :kontor.document-type/internal-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":invoice | :credit-note | :debit-note | :all.
                     Drives credit/debit-note routing through the same
                     document-type as the origin invoice."}

   {:db/ident       :kontor.document-type/prefix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Prefix for the clearance-token / access key
                     when applicable ('NFe', 'NFCe')."}

   {:db/ident       :kontor.document-type/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; ADR-090: data-centric concept-iri seam. Fiscal document types are
   ;; named differently by every regulator (NF-e, fapiao, Rechnung,
   ;; Invoice, etc.) but most map to a small number of cross-system
   ;; concepts (UBL InvoiceTypeCode, UN/EDIFACT document codes, XBRL
   ;; concepts). Substrate carries the IRI so a consumer can cross-walk
   ;; to UBL / Peppol / iXBRL without re-doing the mapping.
   {:db/ident       :kontor.document-type/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional IRI identifying this document type in an
                     external taxonomy (UBL, Peppol, FIBO, regulator
                     namespace). ADR-090."}])

(def ^:private account-code-attrs
  [{:db/ident       :kontor.account-code/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the account this code is for."}

   {:db/ident       :kontor.account-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :br/plano-referencial, :br/sped-contabil,
                     :cn/asbe, :cn/assbe, :de/datev, :ifrs/group, etc."}

   {:db/ident       :kontor.account-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system."}

   {:db/ident       :kontor.account-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.account-code/account :kontor.account-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (account, regulator)
                     pairing exists at most once per DB."}

   {:db/ident       :kontor.account-code/note
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
  [{:db/ident       :kontor.account-tag/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Identity attribute — one entity per tag name. Lets
                     consumers reference tags via [:kontor.account-tag/name
                     \"ust-66\"] lookup refs in tx-data."}

   {:db/ident       :kontor.account-tag/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "ISO-3166 alpha-2 country code. Tags are typically
                     country-scoped (a German VAT report tag is not
                     meaningful on a US sale)."}

   {:db/ident       :kontor.account-tag/applicability
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :account :tax. Indicates which kind of
                     entity this tag attaches to."}

   ;; ADR-019 addendum: substrate seam for XBRL /
   ;; filing-taxonomy concept identifiers. Optional. Consumers who
   ;; want to map their account-tags to XBRL concepts (IFRS, US-GAAP,
   ;; UK FRC, DE E-Bilanz / HGB-Taxonomie, JP-EDINET, ...) carry the
   ;; concept's IRI here. Consumers who don't care leave it blank —
   ;; the rest of the report engine continues to operate on tag names.
   ;;
   ;; ADR-090 generalizes this seam to :account, :partner,
   ;; :commodity, :tax, :document-type — all use the same single-
   ;; cardinality indexed string convention.
   ;;
   ;; Format: an absolute IRI uniquely identifying the concept within
   ;; its taxonomy. Convention follows XBRL's qname-to-IRI rule:
   ;;   {namespace-URI}#{local-name}
   ;; e.g. "http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#Revenue"
   ;;
   ;; Substrate does no validation beyond schema-typing the field.
   ;; Verification of (concept-iri, taxonomy, calculation-linkbase)
   ;; consistency is companion-tier — see research note 78 §7-9 +
   ;; note 88 for the design space. ADR-019 + ADR-090 carry the
   ;; rationale.
   {:db/ident       :kontor.account-tag/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional XBRL / filing-taxonomy concept IRI.
                     Format: {namespace-URI}#{local-name}. The substrate
                     stores + indexes; verification against an actual
                     taxonomy is companion-tier.
                     ADR-019 addendum + ADR-090 generalization."}])

;; ============================================================================
;; Journal — categorization of journal entries.
;;
;; Sale, purchase, cash, bank, general. Each journal sequences its own
;; transaction names (\"INV-2026-0001\", \"BNK-2026-0042\") and may
;; default a posting account.
;; ============================================================================

(def ^:private journal-attrs
  [{:db/ident       :kontor.journal/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Short code (\"INV\", \"BNK\", \"GEN\"). Identity."}

   {:db/ident       :kontor.journal/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.journal/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "One of: :sale :purchase :cash :bank :general.
                     Determines default account behaviors and report
                     classification."}

   {:db/ident       :kontor.journal/default-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Account auto-debited/credited when a transaction
                     in this journal omits the contra side."}

   {:db/ident       :kontor.journal/sequence-prefix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Prefix for auto-generated transaction names
                     (\"INV/{year}/\")."}

   {:db/ident       :kontor.journal/active
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
  [{:db/ident       :kontor.partner/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Caller-supplied stable identifier. For beleg
                     integration this is the :customer/id UUID stringified."}

   {:db/ident       :kontor.partner/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":customer | :vendor | :both"}

   {:db/ident       :kontor.partner/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "ISO-3166 alpha-2; used for fiscal-position auto-
                     application and tax-provider routing."}

   {:db/ident       :kontor.partner/tax-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "VAT ID, EIN, GST number, etc. Format is country-
                     specific; validation lives in l10n modules.
                     ADR-040 adds :partner-tax-id junction for multi-
                     jurisdiction partners; this scalar is the primary."}

   ;; ADR-039: credit limit + status (extends :partner additive).
   {:db/ident       :kontor.partner/credit-limit
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Credit limit amount in :kontor.partner/credit-commodity.
                     Nil = unlimited. Consumer's responsibility to
                     define 'open' / 'pending' for credit-available
                     calculation (kontor.partner/credit-available)."}

   {:db/ident       :kontor.partner/credit-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity (currency) of :kontor.partner/credit-limit."}

   {:db/ident       :kontor.partner/credit-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":open | :hold | :review | :closed. ADR-039.
                     Consumers enforce: post-to-ledger may refuse
                     posting against a :hold partner; sales flows
                     gate order approval on :review."}

   ;; ADR-039: KYC hooks. The actual sanctions-screening engine is a
   ;; future SanctionsProvider companion; these scalars capture the
   ;; latest result.
   {:db/ident       :kontor.partner/kyc-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":not-required | :pending | :cleared | :flagged |
                     :blocked. Trade should be forbidden when
                     :blocked (consumer enforces)."}

   {:db/ident       :kontor.partner/kyc-checked-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner/kyc-source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Provider name: LexisNexis, Refinitiv,
                     ComplyAdvantage, Manual, …"}

   ;; ADR-090: data-centric concept-iri seam. FIBO publishes a
   ;; comprehensive party / counterparty ontology (fibo-be:Organization,
   ;; fibo-be:CounterpartyRole); gist publishes gist:Person /
   ;; gist:Organization. LEI codes have canonical IRIs at gleif.org. The
   ;; substrate stores the IRI so a consumer can align with an external
   ;; party graph without re-doing the mapping at every consumer.
   {:db/ident       :kontor.partner/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional IRI identifying this partner in an
                     external taxonomy (FIBO Organization / Person,
                     gist URI, LEI gleif IRI). ADR-090."}])

;; ============================================================================
;; Person — shared substrate for natural persons across kontor-partner +
;; kontor-hr (+ future companions). Per audit note 95 §2: previously each
;; module re-defined :kontor.person/birth-date + :kontor.person/national-id with diverging
;; shapes, so installing both schemas against the same conn silently
;; overwrote whichever module installed first. Hoisted here as one source
;; of truth. Module-private attrs (HR-side :kontor.person/given-name vs partner-
;; side :kontor.person/first-name, etc.) remain in the modules; only the genuinely
;; cross-cutting bits live here.
;;
;; Shape decisions:
;;   - :kontor.person/national-id is :db.type/string (partner's historic shape) —
;;     simpler default that doesn't drag in the :audit-doc dependency at
;;     the kernel level. Consumers needing audit-doc-backed storage of
;;     national-ID material can add a module-private ref attr (e.g.
;;     :kontor.hr.person/national-id-doc) without redefining this ident.
;;   - :kontor.person/birth-date is :db.type/instant (both modules agreed).
;; ============================================================================

(def ^:private person-attrs
  [{:db/ident       :kontor.person/birth-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "PII; typical :kontor.audit-doc/category :hr-personnel.
                     DSAR collectors walk this attr; per-jurisdiction
                     :retention-policy governs erasure."}

   {:db/ident       :kontor.person/national-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Plaintext national identifier (SSN / SV-Nummer /
                     SIN / CPF / …). Sensitive — encrypt at the
                     consumer layer if subject to GDPR / HIPAA /
                     similar. Companions that prefer audit-doc-backed
                     storage should define their own ref attribute
                     (e.g. :kontor.hr.person/national-id-doc) rather than
                     re-defining this ident."}])

;; ============================================================================
;; ADR-039: master-data primitives — merge, bank-account, partner-bank-
;; account junction, partner-tag.
;; ============================================================================
;; :legal-hold  — ADR-049
;; ============================================================================

(def ^:private legal-hold-attrs
  "Per-matter preservation orders. The hold-blocks-destructive-write
   invariant (kontor.compliance.legal-hold/assert-no-hold-violating-destructive-
   writes!) is enforced in kontor.validation/validate-and-apply
   BEFORE sealing's no-silent-retract check, so the more-specific
   'blocked by hold X' error wins.

   Two scope shapes, evaluated together:
     :scope-eids   — explicit set; O(1) hot path
     :scope-query  — EDN-encoded datalog evaluated against the
                     speculative txdb at write-time (catches new
                     entities matching the matter between sweeps)"
  [{:db/ident       :kontor.legal-hold/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier for the hold (e.g. matter
                     docket number, court case ID)."}

   {:db/ident       :kontor.legal-hold/matter-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable matter description, e.g. 'Acme
                     v. Doe 24-CV-1234'."}

   {:db/ident       :kontor.legal-hold/issued-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :kontor.audit/create-uid of inside or outside counsel
                     who issued the preservation order."}

   {:db/ident       :kontor.legal-hold/issued-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the preservation order was issued
                     externally. May predate :placed-at if recording
                     lagged."}

   ;; No :kontor.legal-hold/placed-at denorm (P1-3 review fix). The
   ;; placement instant IS the :tx/valid-from of the placing tx and
   ;; the :kontor.status-history/changed-at of the nil → :placed row.
   ;; Resolve via (d/pull (d/valid-at db at) [:kontor.legal-hold/state] hold-eid) or
   ;; the status-history timeline. This matches the ADR-048
   ;; valid-time normalization and the Stage-L denorm-removal pattern.

   {:db/ident       :kontor.legal-hold/expires-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional auto-release boundary. Null = manual-
                     only release. ADR-041 sweep-time-based! flips
                     :placed → :expired when this passes."}

   {:db/ident       :kontor.legal-hold/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet.
                     #{:placed :pending-review :released :expired}.
                     :placed and :pending-review are 'active' (block
                     purges). :released and :expired are inactive."}

   {:db/ident       :kontor.legal-hold/scope-eids
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Explicit entity-ID set. Fast-path membership
                     check at write-time of :db/purge. Sweepers
                     refresh from :scope-query results."}

   {:db/ident       :kontor.legal-hold/scope-query
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN-encoded datalog query (e.g.
                     '[:find ?e :where [?e :kontor.invoice/buyer ?p]]') that
                     selects entities under this hold. Evaluated
                     against the speculative txdb at :db/purge time
                     and by sweepers refreshing :scope-eids.
                     Stored as a string for opacity; the EDN reader
                     parses on read."}

   {:db/ident       :kontor.legal-hold/scope-query-as-of
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional valid-time anchor for :scope-query
                     evaluation. Defaults to query-evaluation time."}

   {:db/ident       :kontor.legal-hold/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc carrying the preservation
                     order (PDF, signed letter). ADR-038
                     :requires-supporting-doc enforces presence on
                     placement and release."}

   {:db/ident       :kontor.legal-hold/scope-preview
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc carrying the
                     counsel-signed snapshot of the eid-set the
                     scope-query expanded to at the time of
                     placement. Provides an out-of-band attestation
                     that 'this is what counsel signed off on';
                     defends against scope-drift-mid-litigation."}

   {:db/ident       :kontor.legal-hold/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text annotation (not the canonical record;
                     the :status-history rows + :audit-doc are)."}])

;; ============================================================================
;; :retention-policy  — ADR-050
;; ============================================================================

(def ^:private retention-policy-attrs
  "Effective-dated retention rules. A policy says: entities of type
   T, in jurisdiction J, expire `duration-years` after their
   `triggered-by` anchor date, via `expiry-action`. The kernel ships
   only the SHAPE — per-jurisdiction policy data lives in l10n
   companion modules (ADR-026 effective-dated pattern). The
   kontor.compliance.retention sweeper walks candidate entities and produces
   expiry work-items; entities under an active legal hold (ADR-049)
   are reported but never expired (apply-expiry! routes through
   validate-and-apply, so the hold-middleware fires structurally)."
  [{:db/ident       :kontor.retention-policy/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "External identifier for the policy (e.g.
                     'DE-HGB-257-ledger', 'US-SOX-103')."}

   {:db/ident       :kontor.retention-policy/applies-to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "Entity-type discriminator keyword(s) this policy
                     governs — #{:transaction :invoice :partner
                     :audit-doc :status-history …}. Matches the bare-
                     keyword entity-type convention used by
                     :kontor.status-history/entity-type."}

   ;; ADR-075 — subject-matter category gate. Open-set keyword that
   ;; mirrors :kontor.audit-doc/category; nil = applies regardless of
   ;; category. When non-nil, the policy ONLY applies to entities
   ;; whose own :kontor.audit-doc/category (or, for non-audit-doc entities,
   ;; the consumer's category inference) matches. Per-jurisdiction
   ;; retention floors differ by category — payroll PII retention
   ;; (GDPR Art. 17 + DE Sozialversicherung §28f SGB IV) is NOT the
   ;; same as financial-records retention (HGB §257); the category
   ;; axis is the dimension that lets one :retention-policy table
   ;; carry both.
   {:db/ident       :kontor.retention-policy/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Subject-matter category gate (ADR-075). nil =
                     applies regardless of category. When non-nil the
                     sweeper matches against :kontor.audit-doc/category (or
                     consumer-derived category for other entity types).
                     Per-jurisdiction floors differ by category."}

   {:db/ident       :kontor.retention-policy/jurisdiction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :country. nil = applies
                     globally. policy-for prefers a jurisdiction-
                     specific policy over a global one."}

   {:db/ident       :kontor.retention-policy/duration-years
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Retention duration in whole years, measured from
                     the entity's :triggered-by anchor date."}

   {:db/ident       :kontor.retention-policy/triggered-by
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The clock-anchor attribute keyword. The
                     retention clock starts at the value of this
                     attribute ON the entity (v1: direct-attribute
                     anchors only — e.g. :kontor.transaction/effective-date,
                     :kontor.audit-doc/uploaded-at, :kontor.status-history/changed-
                     at). Entities lacking the attribute are skipped
                     by the sweeper."}

   {:db/ident       :kontor.retention-policy/expiry-action
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "What the sweeper does to an expired entity.
                     #{:purge :anonymize :archive-to-cold-storage}.
                     :purge — :db/purge the whole entity.
                     :anonymize — :db.purge/attribute each field in
                       :anonymize-fields; the row survives, PII gone.
                     :archive-to-cold-storage — deferred in v1
                       (apply-expiry! throws 'not implemented')."}

   {:db/ident       :kontor.retention-policy/anonymize-fields
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "For :expiry-action :anonymize — the set of PII
                     attribute keywords to purge. The policy carries
                     the PII field list so the kernel needs no global
                     PII registry; l10n companions ship pre-seeded
                     policies with the standard per-jurisdiction PII
                     set."}

   {:db/ident       :kontor.retention-policy/legal-basis
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text statute reference (e.g. 'HGB §257 /
                     GoBD', 'SOX §103', 'GDPR Art. 5(1)(e)')."}

   {:db/ident       :kontor.retention-policy/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-026 effective-dating. A policy applies to
                     entities whose anchor date falls in
                     [effective-from, effective-until). Statutes
                     change; an entity is evaluated against the
                     policy in force when its anchor date occurred."}

   {:db/ident       :kontor.retention-policy/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Exclusive upper bound of the policy's effective
                     window. nil = open-ended (still in force)."}

   {:db/ident       :kontor.retention-policy/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:draft :active :superseded}.
                     Only :active policies are consulted by the
                     sweeper. :draft stages a policy without firing;
                     :superseded is terminal."}

   {:db/ident       :kontor.retention-policy/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the records-retention
                     schedule document or the legal memo justifying
                     the policy. ADR-038 :requires-supporting-doc
                     enforces presence on :draft → :active."}

   {:db/ident       :kontor.retention-policy/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.retention-policy/code
                     :kontor.retention-policy/effective-from]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One policy row per (code, effective-from). Lets
                     a policy be superseded by a new row with a later
                     effective-from without code collision."}])

;; ============================================================================
;; :dsar-request  — ADR-052
;; ============================================================================

(def ^:private dsar-request-attrs
  "Data-subject-access-request lifecycle. A `:dsar-request` tracks a
   GDPR/CCPA/LGPD-style request: the subject, the kind (access /
   erasure / portability / …), the statutory deadline, and the
   status-machine state through to fulfillment or denial. The
   `kontor.compliance.dsar/collect` helper does the bitemporal walk — 'all data
   we held about this subject as of the request date' — which the
   consumer assembles into the fulfillment bundle (referenced here
   as `:fulfilled-package`)."
  [{:db/ident       :kontor.dsar-request/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier for the request (intake
                     ticket number, portal reference)."}

   {:db/ident       :kontor.dsar-request/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The data subject — a ref to :partner. collect
                     walks every attribute referencing this partner."}

   {:db/ident       :kontor.dsar-request/jurisdiction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :country — which regime governs
                     (GDPR / CCPA / LGPD / …). Drives the deadline."}

   {:db/ident       :kontor.dsar-request/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:access :portability :erasure :rectification
                       :restriction :objection}."}

   {:db/ident       :kontor.dsar-request/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the request was received — the statutory
                     clock starts here (CCPA: receipt; GDPR: receipt)."}

   {:db/ident       :kontor.dsar-request/deadline-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Statutory response window in days (GDPR 30,
                     CCPA 45, LGPD 15, …). Jurisdiction-supplied."}

   {:db/ident       :kontor.dsar-request/deadline-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Computed: received-at + deadline-days. Queryable
                     so a consumer cron can flag approaching/overdue
                     requests."}

   {:db/ident       :kontor.dsar-request/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 facet. #{:received :verifying-identity
                       :in-progress :awaiting-legal-review :extended
                       :fulfilled :denied :withdrawn}."}

   {:db/ident       :kontor.dsar-request/received-via
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:email :portal :postal :api}."}

   {:db/ident       :kontor.dsar-request/identity-verified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the subject's identity was verified — set
                     on :verifying-identity → :in-progress."}

   {:db/ident       :kontor.dsar-request/fulfilled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the request was fulfilled."}

   {:db/ident       :kontor.dsar-request/fulfilled-package
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the produced bundle artifact
                     (the data package handed to the subject)."}

   {:db/ident       :kontor.dsar-request/denied-reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:identity-not-verified :no-data
                       :legal-hold-override :exempt-records
                       :manifestly-unfounded}."}

   {:db/ident       :kontor.dsar-request/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the intake form / request
                     correspondence."}

   {:db/ident       :kontor.dsar-request/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text annotation."}])

;; ============================================================================
;; :tax-concept / :provision / :regime / :parameter — ADR-101
;; Statute-as-data substrate. Tax law lifted into first-class queryable
;; entities. See doc/decisions.md ADR-101 + research notes 116/117/118/119.
;; ============================================================================

(def ^:private tax-concept-attrs
  "Cross-jurisdiction catalogue of tax-law abstractions. A `:tax-concept`
   is a named, ADR-closed handle that jurisdiction-specific `:provision`
   entities reference (`:participation-exemption`, `:rollover-relief`,
   `:loss-bucket`, `:lifetime-cap`, …). Closed-by-ADR (additions are
   one-row migrations). Composes with ADR-090 `:concept-iri` for
   XBRL / FIBO / external taxonomy edges. ADR-101 §D6 + note 119."
  [{:db/ident       :kontor.tax-concept/code
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Closed-by-ADR concept code (e.g. :participation-exemption,
                     :rollover-relief, :loss-bucket, :lifetime-cap). Starter
                     set seeded by kontor.tax.statute/install-seeds!; new entries
                     require an ADR addendum + a migration row."}

   {:db/ident       :kontor.tax-concept/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label for the concept."}

   {:db/ident       :kontor.tax-concept/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "One-paragraph description of what the concept names —
                     enough for an l10n author to know whether their
                     statute-provision instantiates it."}

   {:db/ident       :kontor.tax-concept/family
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Coarse family this concept belongs to:
                     :exemption / :relief / :credit / :surtax / :minimum-tax /
                     :base-adjustment / :elective-regime."}

   {:db/ident       :kontor.tax-concept/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ADR-090 IRI into an external taxonomy
                     (XBRL / FIBO / gist). Lets the concept's identity be
                     anchored beyond the kontor catalogue when one exists."}])

(def ^:private provision-attrs
  "Per-jurisdiction encoded statute rule. A `:provision` is one law
   contribution: it cites the source, names the affected `:tax-concept`,
   tests applicability via a closed-vocabulary condition expression, and
   declares its consequence (a credit, surtax, base adjustment, schedule
   override, …) for the evaluator to fold in priority order. Default +
   exception semantics ride `:priority` + `:exception-of` (Catala-
   inspired). The escape hatch `:kontor.provision/compute-fn` resolves to a
   registered Clojure fn for the rare provision that exceeds the closed
   predicate vocabulary. ADR-101 + note 119."
  [{:db/ident       :kontor.provision/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable provision id (e.g. \"DE-KStG-§8b-Abs-1\",
                     \"US-IRC-§1031-2024\"). Identity attribute — one row
                     per (statute-section × law-version) combination."}

   {:db/ident       :kontor.provision/jurisdiction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code (:de, :fr, :jp, :us-fed, :ca-on, …).
                     Indexed; lets the evaluator filter applicable provisions
                     by jurisdiction in one datalog clause."}

   {:db/ident       :kontor.provision/concept
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Reference to the `:tax-concept` this provision
                     instantiates. The evaluator queries by concept first;
                     a provision without a concept is invalid."}

   {:db/ident       :kontor.provision/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable provision title — typically the
                     statute section title (\"Beteiligungsertragsbefreiung\"
                     for DE KStG §8b)."}

   {:db/ident       :kontor.provision/citation
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "URL to the authoritative statute text. Lets the
                     audit trail link a computed liability back to the
                     official source."}

   {:db/ident       :kontor.provision/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ADR-090 IRI to the provision in an external
                     taxonomy (XBRL / FIBO). Distinct from
                     `:kontor.tax-concept/concept-iri` — that's the abstract
                     concept; this is the specific provision."}

   {:db/ident       :kontor.provision/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Statutory effective-from date — when the law applies
                     under the law's own terms. Distinct from
                     `:tx/valid-from` (ADR-048), which is when the
                     provision was entered into the books. Retroactive
                     amendments can have `:effective-from` earlier than
                     `:tx/valid-from`."}

   {:db/ident       :kontor.provision/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Statutory effective-until date (open if absent — the
                     provision is still in force)."}

   {:db/ident       :kontor.provision/priority
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Application priority. Lower numbers fire first. Same-
                     priority applicable provisions are ambiguity — the
                     evaluator raises `kontor.tax/ambiguous-provision`.
                     Conventional ranges: 0-99 base-level defaults;
                     100-999 normal provisions; 1000+ overrides."}

   {:db/ident       :kontor.provision/exception-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to another `:provision` this one is an
                     exception of (Catala-inspired default+exception
                     semantics). When the exception applies, the default
                     is suppressed; when the exception doesn't apply, the
                     default fires."}

   {:db/ident       :kontor.provision/condition
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Closed-vocabulary EDN predicate expression stored as
                     a string (round-trips through any backing store; read
                     by the evaluator). Vocabulary: `:and`/`:or`/`:not`/
                     `:eq`/`:in`/`:leq`/`:geq`/`:lt`/`:gt`/`:between`/
                     `:status-is`/`true`/`false` over `:tax-context` facts.
                     `nil` ⇒ always applicable."}

   {:db/ident       :kontor.provision/consequence
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN consequence map stored as string. Shape depends
                     on `:op`: e.g.
                     `{:op :base-deduct :amount-from :parameter :parameter
                       \"DE.GewSt.§9.1-real-estate-deduction\"}`,
                     `{:op :credit :refundable? true :amount-from
                       :tax-context-fact :fact :cir-claimed}`,
                     `{:op :surtax :rate-from :parameter :parameter
                       \"DE.Soli.rate\"}`,
                     `{:op :schedule-override :schedule {...}}`."}

   {:db/ident       :kontor.provision/compute-fn
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional escape-hatch fn-key resolving to a Clojure
                     fn in the `kontor.tax.statute/*compute-fns*` registry.
                     For provisions whose computation exceeds the closed
                     predicate vocabulary (cumulative lifetime caps,
                     indexation-table lookups, complex eligibility
                     cascades). The fn receives `[ctx applicable-context]`
                     and returns the consequence resolved to a numeric
                     amount. Use sparingly — every use is a documented
                     deviation from data-only provisions."}

   {:db/ident       :kontor.provision/regime
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional `:regime` ref. If absent, the provision
                     applies whenever its condition matches; if present,
                     applies only when the taxpayer has elected this
                     regime (election rides ADR-034 status-machine — see
                     ADR-101 §D5)."}

   {:db/ident       :kontor.provision/audit-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Per ADR-038 audit-doc refs (Beck commentary, BMF
                     Schreiben, board-resolution docs)."}])

(def ^:private regime-attrs
  "Elective container — a `:regime` groups provisions that compose into
   one computation (IN's old-vs-new income-tax regime, FR's PME vs
   standard IS, US's itemized vs standard deduction). The election event
   itself rides ADR-034's `:status-transition` + `:status-history` — no
   parallel `:regime-election` namespace; see ADR-101 §D5.
   `:kontor.regime/extends` supports counterfactual / amendment overlay (OpenFisca
   reform pattern). ADR-101 + note 118."
  [{:db/ident       :kontor.regime/code
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Regime code (e.g. :in-pit-new, :in-pit-old, :fr-is-pme).
                     Identity attribute."}

   {:db/ident       :kontor.regime/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label."}

   {:db/ident       :kontor.regime/jurisdiction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction code (:in, :fr, …)."}

   {:db/ident       :kontor.regime/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ADR-090 IRI."}

   {:db/ident       :kontor.regime/extends
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional parent regime — the OpenFisca reform pattern
                     for counterfactual overlay. A `:regime` that extends
                     another inherits its provisions, may add new ones,
                     and may override (via `:kontor.provision/exception-of`).
                     Cycles raise `kontor.tax/cyclic-regime`."}

   {:db/ident       :kontor.regime/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.regime/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private parameter-attrs
  "Date-keyed value history — the OpenFisca operational pattern. A
   `:parameter` is an identified entity (rate / bracket-scale / threshold);
   its values are kept as a temporal series of `:parameter-value` rows
   (scalar parameters) or `:parameter-bracket` rows (bracket scales).
   Replaces the pattern of editing a defrecord config to update a rate.
   ADR-101 + note 118 §1.1."
  [{:db/ident       :kontor.parameter/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable parameter id, hierarchical dot-notation per
                     OpenFisca convention (e.g. \"de.kst.rate\",
                     \"de.gewst.messzahl\", \"fr.is.pme-brackets\").
                     Identity attribute."}

   {:db/ident       :kontor.parameter/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label."}

   {:db/ident       :kontor.parameter/jurisdiction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.parameter/unit
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "What this parameter measures: :rate (decimal 0-1),
                     :amount-money (BigDecimal in some commodity),
                     :threshold (BigDecimal, same shape as :amount-money
                     but used as a comparison value), :ratio (BigDecimal
                     unbounded), :bracket-scale (collection of
                     `:parameter-bracket` rows refer to this parent)."}

   {:db/ident       :kontor.parameter/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional commodity ref — when `:unit` is :amount-money
                     or :threshold, the commodity the value is denominated
                     in. nil for :rate / :ratio / :bracket-scale."}

   {:db/ident       :kontor.parameter/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ADR-090 IRI."}

   {:db/ident       :kontor.parameter-value/parameter
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the `:parameter` this value belongs to. One
                     `:parameter` typically has many `:parameter-value`
                     rows, each effective in a different period."}

   {:db/ident       :kontor.parameter-value/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Statutory effective-from date of this value. The
                     resolver picks the value whose effective range
                     contains the as-of instant."}

   {:db/ident       :kontor.parameter-value/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.parameter-value/decimal-value
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The scalar value (BigDecimal). v1 ships only the
                     decimal shape; non-scalar values (instant, boolean,
                     string) deferred per ADR-101 §D9."}

   {:db/ident       :kontor.parameter-value/citation
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional URL to the authority publication asserting
                     this value (BMF Schreiben, IRS Rev. Proc., …)."}

   {:db/ident       :kontor.parameter-value/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.parameter-value/parameter
                     :kontor.parameter-value/effective-from]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity (parameter, effective-from) — one
                     value per parameter per statutory effective date.

                     Load-bearing for the idempotency every l10n preset
                     claims. Without it each `install!` re-transacted its
                     value rows unconditionally: running an installer
                     twice took l10n-in from 69 value rows to 138, and
                     `parameter-value-at` then picked arbitrarily among
                     duplicates. Re-transacting the same (parameter,
                     date) now replaces the prior :decimal-value /
                     :effective-until / :citation instead of adding a
                     row. Note 194 §1."}

   {:db/ident       :kontor.parameter-bracket/parameter
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the parent `:parameter` (must have
                     `:kontor.parameter/unit :bracket-scale`)."}

   {:db/ident       :kontor.parameter-bracket/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Position in the scale (0-indexed, ordered)."}

   {:db/ident       :kontor.parameter-bracket/rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Marginal rate for this bracket (decimal 0-1)."}

   {:db/ident       :kontor.parameter-bracket/upper
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Upper threshold (exclusive) for this bracket. Absent
                     ⇒ open top band."}

   {:db/ident       :kontor.parameter-bracket/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.parameter-bracket/effective-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.parameter-bracket/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.parameter-bracket/parameter
                     :kontor.parameter-bracket/index
                     :kontor.parameter-bracket/effective-from]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity (parameter, index, effective-from)
                     — one band per position per statutory effective date.

                     A duplicated bracket ladder is worse than a
                     duplicated scalar: a second `install!` took the IN
                     surcharge from 3 bands to 6, and the provider's
                     `prior-upper` walks the vector by identity, so the
                     predecessor of the top open band became the OTHER
                     COPY OF ITSELF — the marginal-relief threshold read
                     nil instead of ₹100,000,000 and the relief was
                     silently skipped. Note 194 §1."}])

;; ============================================================================

(def ^:private partner-merge-attrs
  [{:db/ident       :kontor.partner-merge/duplicate-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical (good) partner. After merge, queries
                     via resolve-canonical-partner walk superseded ->
                     duplicate-of."}

   {:db/ident       :kontor.partner-merge/superseded
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Duplicate (bad) partner being merged INTO the
                     canonical. Its history is preserved bitemporally;
                     queries resolve it to :duplicate-of from the
                     merge point forward."}

   {:db/ident       :kontor.partner-merge/merged-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-merge/merged-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-merge/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-038 codified-reason vocabulary."}

   {:db/ident       :kontor.partner-merge/reason-note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-merge/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc (ADR-038)."}

   {:db/ident       :kontor.partner-merge/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.partner-merge/duplicate-of :kontor.partner-merge/superseded]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private bank-account-attrs
  [{:db/ident       :kontor.bank-account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.bank-account/iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-account/bic
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-account/account-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "For non-IBAN banks (US, etc.)."}

   {:db/ident       :kontor.bank-account/routing-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "US ABA, GB sort code, etc."}

   {:db/ident       :kontor.bank-account/bank-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-account/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :country (ADR-023)."}

   {:db/ident       :kontor.bank-account/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The account's currency. Multi-currency partners
                     have N bank accounts each pinned to one
                     :commodity."}

   {:db/ident       :kontor.bank-account/holder-name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "On-the-account legal name (may differ from
                     :kontor.partner/name when a partner uses a service
                     intermediary)."}

   {:db/ident       :kontor.bank-account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-account/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private partner-bank-account-attrs
  [{:db/ident       :kontor.partner-bank-account/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/bank-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/purpose
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":disbursement | :collection | :both."}

   {:db/ident       :kontor.partner-bank-account/preferred?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Preferred-for-purpose flag. When multiple
                     accounts exist for the same partner+purpose,
                     :preferred? disambiguates."}

   {:db/ident       :kontor.partner-bank-account/verified?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/verified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-bank-account/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.partner-bank-account/partner
                     :kontor.partner-bank-account/bank-account
                     :kontor.partner-bank-account/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private side-effect-intent-attrs
  ;; ADR-041: side-effect intent row pattern.
  [{:db/ident       :kontor.side-effect-intent/key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Idempotency key; convention is
                     hash(entity-id, transition, attempt, payload).
                     Worker dedupes on this."}

   {:db/ident       :kontor.side-effect-intent/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":send-email | :send-edi | :send-peppol |
                     :charge-card | :webhook | :notify-slack | …"}

   {:db/ident       :kontor.side-effect-intent/payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN or JSON blob the consumer interprets.
                     Kernel doesn't parse."}

   {:db/ident       :kontor.side-effect-intent/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending | :processing | :done | :failed | :abandoned"}

   {:db/ident       :kontor.side-effect-intent/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/processing-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/processed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/last-error
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/retry-count
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/max-retries
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.side-effect-intent/origin-history
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :status-history row that produced this
                     intent."}])

;; ============================================================================
;; Cross-DB side-effect — ADR-074. Idempotency marker written on the
;; TARGET tx so a drain worker can deterministically detect whether a
;; cross-tx post already landed. See [[kontor.workflow.side-effect.cross]].
;; ============================================================================

(def ^:private cross-tx-attrs
  [{:db/ident       :kontor.cross-tx/step-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Saga-step idempotency key. Written as a tx-meta
                     attribute on the TARGET tx by a kontor.workflow.side-effect.cross
                     drain worker. Deterministically derived from
                     (source-intent-key, target-tx-data) so a re-claimed
                     intent can short-circuit when the worker crashed
                     between target-commit and source-mark-done. ADR-074."}])

;; Tx-level valid-time attributes (ADR-048) now live in datahike itself
;; as :db.valid/from + :db.valid/to (system schema, pre-installed on
;; every fresh DB by feature/bitemporal-v1). Kontor's old `:tx/valid-from`
;; was a stopgap; the upstream attrs have identical semantics. See
;; kontor.bitemporal for the resolver that delegates to d/valid-at.

(def ^:private payment-application-attrs
  ;; ADR-043: partial-payment primitive. Closes the scope-cut at
  ;; reconciliation.clj:38-47 — :open-amount per invoice now equals
  ;; (invoice gross − Σ application amounts). Bitemporal via datahike
  ;; tx-time: query "what applications were known as of T?" reads
  ;; rows with :applied-at ≤ T. Replayable: write a :reversal-of row
  ;; with negated :amount.
  [{:db/ident       :kontor.payment-application/payment
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :transaction that brought cash in
                     (typically a bank-line settlement)."}

   {:db/ident       :kontor.payment-application/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :invoice this application reduces."}

   {:db/ident       :kontor.payment-application/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed amount. Positive reduces the invoice's
                     open balance; negative is an allocation reversal
                     (see :reversal-of)."}

   {:db/ident       :kontor.payment-application/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Currency / commodity of :amount. Must match the
                     invoice's currency."}

   {:db/ident       :kontor.payment-application/applied-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Wall-clock instant the application was recorded.
                     Bitemporal queries read applications with
                     :applied-at ≤ :as-of-valid."}

   {:db/ident       :kontor.payment-application/applied-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :kontor.audit/create-uid of the actor who applied."}

   {:db/ident       :kontor.payment-application/strategy
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":fifo | :customer-instruction | :proportional
                     | :cherry-pick | :reversal | :write-off.
                     :write-off is the bad-debt path — the application
                     carries the amount as :write-off-amount with
                     :amount 0M, because no cash was applied; it closes
                     the open item that the GL relief already removed
                     (note 198 audit HIGH-3)."}

   {:db/ident       :kontor.payment-application/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional reason keyword (e.g. :remittance-
                     received, :allocation-correction, :customer-
                     dispute)."}

   {:db/ident       :kontor.payment-application/reason-note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.payment-application/reversal-of
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to the prior :payment-application
                     this row reverses. Set on reversals; null on
                     forward allocations."}

   {:db/ident       :kontor.payment-application/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc (e.g. remittance
                     advice PDF)."}

   ;; Payment-linked write-off / tolerance leg (note 198 r3-recon-b). A short
   ;; payment — a rounding difference, an agreed cash discount, a bank fee
   ;; deducted at source, a small bad-debt tolerance — closes the remaining
   ;; open amount as PART of the settlement rather than leaving the invoice
   ;; stuck at :partially-paid forever. `open-amount-of-invoice` nets the
   ;; write-off alongside the cash, so the invoice reaches :paid.
   ;; Distinct from `kontor.collections.writeoff`, which writes off the FULL
   ;; open amount as a bad-debt collections event gated behind a
   ;; :collection-case. Odoo: the reconcile write-off wizard driving
   ;; account_move_line.py amount_residual → 0 + full_reconcile_id.
   {:db/ident       :kontor.payment-application/write-off-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Amount of the invoice's open balance closed by a
                     write-off as part of this settlement (same commodity
                     as :amount). Counts toward the applied total."}

   {:db/ident       :kontor.payment-application/write-off-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Account the write-off is charged to (cash-discount
                     granted, rounding difference, bank charges, small
                     bad-debt). Required whenever :write-off-amount is set."}

   {:db/ident       :kontor.payment-application/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.payment-application/payment
                     :kontor.payment-application/invoice
                     :kontor.payment-application/applied-at]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Posting-level reconciliation — note 198 (Tier 2, r3-recon-a)
;;
;; The `:payment-application` above nets an INVOICE against cash. It cannot
;; close out a clearing account: a GR/IR pair, a suspense line, an inter-account
;; transfer — arbitrary GL lines that offset each other but belong to no
;; invoice. Odoo models this with `account.partial.reconcile` (a debit line
;; matched against a credit line for an amount) plus `account.full.reconcile`
;; (the group formed once every member's residual reaches zero), surfaced on the
;; line as `amount_residual` / `full_reconcile_id` / `matching_number`
;; (account_move_line.py:242/255/284). kontor mirrors that shape.
;;
;; `:amount-residual` is MATERIALISED on the posting — it is the unmatched
;; remainder of that line, maintained by `kontor.banking.line-reconcile`, so
;; "what is still open on this clearing account?" is a direct query rather than
;; a fold over every partial.
;; ============================================================================

(def ^:private posting-reconcile-attrs
  [{:db/ident       :kontor.posting/amount-residual
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Unmatched remainder of this posting, same commodity as
                     :kontor.posting/amount. Absent = never reconciled (treat as
                     the full amount); 0 = fully matched. Maintained by
                     kontor.banking.line-reconcile."}

   {:db/ident       :kontor.posting/full-reconcile
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :full-reconcile group this posting belongs to, set
                     once the whole matched set nets to zero (Odoo
                     full_reconcile_id)."}

   {:db/ident       :kontor.posting/matching-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-facing match code shared by every line in the
                     matched set (Odoo matching_number). Fully-matched sets
                     carry the :full-reconcile code; a partially-matched line
                     carries a provisional marker."}])

(def ^:private partial-reconcile-attrs
  [{:db/ident       :kontor.partial-reconcile/debit-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The debit-side posting of this match (Odoo
                     debit_move_id)."}

   {:db/ident       :kontor.partial-reconcile/credit-line
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The credit-side posting of this match (credit_move_id)."}

   {:db/ident       :kontor.partial-reconcile/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Amount matched between the two lines (always positive)."}

   {:db/ident       :kontor.partial-reconcile/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partial-reconcile/matched-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partial-reconcile/full-reconcile
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the :full-reconcile group this partial became
                     part of, once the set closed."}

   {:db/ident       :kontor.full-reconcile/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable matching code for a fully-reconciled set — the
                     value mirrored onto each member's
                     :kontor.posting/matching-number."}

   {:db/ident       :kontor.full-reconcile/reconciled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.full-reconcile/reconciled-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Payment + batch payment — note 198 (Tier 2, PAY-A / PAY-C)
;;
;; kontor's "payment" was a bare cash `:transaction`. A business backbone needs
;; the register→clear two-step Odoo models on `account.payment`: at REGISTER
;; time the cash lands in a transient OUTSTANDING (undeposited-funds / in-transit)
;; account; at CLEAR time the bank statement reconciles that outstanding line
;; and `is_matched` flips (account_payment.py:123-128 / :478-492). Without it
;; "which cash have we received but not yet banked?" is unanswerable.
;; ============================================================================

(def ^:private payment-attrs
  [{:db/ident       :kontor.payment/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.payment/direction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":inbound (customer receipt) | :outbound (vendor payment)
                     — Odoo's payment_type."}

   {:db/ident       :kontor.payment/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle: :draft → :registered → :cleared, plus
                     :cancelled. :registered = cash sits in the outstanding
                     account; :cleared = the bank line reconciled it."}

   {:db/ident       :kontor.payment/method
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Payment method — :bank-transfer | :cheque | :card | :cash
                     | :direct-debit | :other. Consumers may extend."}

   {:db/ident       :kontor.payment/outstanding-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Transient account the cash rests in between register and
                     clear (undeposited funds / payments in transit) — Odoo's
                     outstanding_account_id."}

   {:db/ident       :kontor.payment/is-matched
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True once the outstanding line has been reconciled against
                     a bank line. False on a registered-but-uncleared payment —
                     the flag that answers 'which cash is still undeposited?'."}

   {:db/ident       :kontor.payment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.payment/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.payment/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.payment/payment-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.payment/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The GL transaction this payment booked."}

   {:db/ident       :kontor.payment/batch
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :batch-payment run this payment belongs to, if any."}])

(def ^:private batch-payment-attrs
  [{:db/ident       :kontor.batch-payment/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :kontor.batch-payment/payments
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "The N payments this run aggregates — they leave the bank
                     as ONE aggregate line (Odoo account.batch_payment)."}

   {:db/ident       :kontor.batch-payment/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle: :draft → :sent → :reconciled, plus
                     :cancelled."}

   {:db/ident       :kontor.batch-payment/total-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Sum of the member payments — the figure that must match
                     the single bank statement line."}

   {:db/ident       :kontor.batch-payment/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.batch-payment/direction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":inbound | :outbound — a batch is single-direction."}

   {:db/ident       :kontor.batch-payment/batch-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private account-type-direction-attrs
  ;; ADR-041: debit/credit data table replacing hardcoded map.
  [{:db/ident       :kontor.account-type-direction/invoice-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sales | :purchase | :credit-memo | :debit-memo"}

   {:db/ident       :kontor.account-type-direction/account-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "GL routing key; same vocabulary as
                     :kontor.invoice-line/gl-account-type."}

   {:db/ident       :kontor.account-type-direction/direction
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":debit | :credit"}

   {:db/ident       :kontor.account-type-direction/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.account-type-direction/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.account-type-direction/invoice-type
                     :kontor.account-type-direction/account-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private partner-tax-id-attrs
  ;; ADR-040: multi-tax-id-per-jurisdiction junction.
  [{:db/ident       :kontor.partner-tax-id/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tax-id/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Jurisdiction (ADR-023 :country)."}

   {:db/ident       :kontor.partner-tax-id/tax-id-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":vat-eu | :gst-au | :gst-in | :tin-us | :rfc-mx |
                     :cnpj-br | :cpf-br | :pan-in | :abn-au | :kvk-nl |
                     :rsin-nl | :btw-nl | … consumers extend."}

   {:db/ident       :kontor.partner-tax-id/tax-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The ID value. Format country-specific; validation
                     in l10n modules."}

   {:db/ident       :kontor.partner-tax-id/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tax-id/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tax-id/verified?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "VIES / SAT / IRP / consumer-side validated."}

   {:db/ident       :kontor.partner-tax-id/verified-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tax-id/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.partner-tax-id/partner
                     :kontor.partner-tax-id/country
                     :kontor.partner-tax-id/tax-id-type
                     :kontor.partner-tax-id/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

(def ^:private partner-tag-attrs
  [{:db/ident       :kontor.partner-tag/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tag/tag-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical starter vocabulary:
                     :vip | :high-volume | :strategic-account |
                     :churn-risk | :do-not-contact | :test-account |
                     :gold-tier | :silver-tier | :bronze-tier | …
                     consumers extend."}

   {:db/ident       :kontor.partner-tag/from-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tag/thru-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.partner-tag/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.partner-tag/partner
                     :kontor.partner-tag/tag-type
                     :kontor.partner-tag/from-date]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Fiscal position — per-region tax/account remapping rules.
;;
;; Implements Odoo's account_fiscal_position concept: when a customer
;; is in country X, replace the default tax with the X-appropriate one.
;;
;; The mapping lines are SEPARATE entities that point BACK at the position
;; (`:kontor.fiscal-position-tax/fiscal-position`), mirroring Odoo's
;; account.fiscal.position.tax / .account rather than a forward many-ref off
;; the position. That keeps the position entity itself a pure marker and lets
;; a mapping line carry its own attributes (sequence, a nil destination
;; meaning \"drop this tax\"). note 198 R3-FP-01.
;; ============================================================================

(def ^:private fiscal-position-attrs
  [{:db/ident       :kontor.fiscal-position/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    ;; Indexed, not unique: a book may legitimately hold "EU B2B" positions
    ;; for two entities. `kontor.tax.fiscal-position/by-name` therefore
    ;; refuses to guess when a name resolves to more than one — note 198
    ;; audit (M9), same posture as `kontor.account/resolve-code`.
    :db/index       true}

   {:db/ident       :kontor.fiscal-position/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.fiscal-position/auto-apply
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.fiscal-position/vat-required
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether partner must have a tax-id for this
                     position to apply (e.g. EU intra-community
                     reverse charge requires a VAT ID)."}])

;; Odoo: account.fiscal.position.tax (tax_src_id → tax_dest_id), consumed by
;; AccountFiscalPosition.map_tax. kontor's equivalent is read by
;; `kontor.tax.fiscal-position/map-tax` and applied by the
;; StaticTableProvider when a rate-facts context carries :fiscal-position.
(def ^:private fiscal-position-tax-attrs
  [{:db/ident       :kontor.fiscal-position-tax/fiscal-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The position this mapping line belongs to."}

   {:db/ident       :kontor.fiscal-position-tax/src-tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The tax that would otherwise apply (the domestic
                     default)."}

   {:db/ident       :kontor.fiscal-position-tax/dest-tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The tax that replaces it. ABSENT means the source tax
                     is DROPPED — the export case, where a domestic VAT
                     simply does not apply and no substitute exists."}

   {:db/ident       :kontor.fiscal-position-tax/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   ;; One mapping per (position, source tax) — re-transacting the same pair
   ;; updates the destination instead of accumulating contradictory lines.
   {:db/ident       :kontor.fiscal-position-tax/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.fiscal-position-tax/fiscal-position
                     :kontor.fiscal-position-tax/src-tax]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; Odoo: account.fiscal.position.account (account_src_id → account_dest_id),
;; consumed by AccountFiscalPosition.map_account. Lets an EU/export position
;; route revenue to a different income account than the domestic default.
(def ^:private fiscal-position-account-attrs
  [{:db/ident       :kontor.fiscal-position-account/fiscal-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.fiscal-position-account/src-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.fiscal-position-account/dest-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.fiscal-position-account/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.fiscal-position-account/fiscal-position
                     :kontor.fiscal-position-account/src-account]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Tax + tax-repartition-line + tax-group.
;;
;; The repartition pattern from Odoo (research note 01, conversation
;; analysis): a tax is a *recipe* for posting, not a number. It has
;; multiple repartition lines that say "this fraction of the base goes
;; to account X with tag T" and "this fraction of the tax goes to
;; account Y with tag U" — separate repartition for invoices vs refunds.
;;
;; Recoverable vs non-recoverable (ADR-005 / ADR-071): VAT/HST/QST/GST set
;; :kontor.tax/recoverable? true (input tax credit). PST/RST/US sales tax set
;; it false (becomes cost of input).
;; ============================================================================

(def ^:private tax-attrs
  [{:db/ident       :kontor.tax/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Short code unique within the DB (\"DE-VAT-19\",
                     \"CA-GST-5\"). Identity."}

   {:db/ident       :kontor.tax/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.tax/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.tax/type-tax-use
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":sale | :purchase | :none"}

   {:db/ident       :kontor.tax/amount-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":percent | :fixed | :group | :division"}

   {:db/ident       :kontor.tax/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The rate. 0.19M for 19%; 0.05M for 5%; for :fixed
                     the absolute amount per unit."}

   {:db/ident       :kontor.tax/recoverable?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True for VAT-style taxes (input tax credit allowed:
                     DE VAT, CA GST/HST/QST). False for retail sales
                     taxes (PST/RST in CA; sales tax in the US) where
                     the tax becomes cost of the input."}

   ;; ADR-071 addendum. The tax-collection
   ;; *mechanism* — the posting shape, as distinct from the *rate*.
   ;; StaticTableProvider maps this to the TaxFacts component :kind; a
   ;; bespoke per-country provider sets :kind directly and ignores it.
   {:db/ident       :kontor.tax/mechanism
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":standard (default — when absent) | :reverse-charge
                     | :withholding. :reverse-charge → buyer-side
                     self-assessment (both-legs); :withholding → a
                     contra deduction (TDS, retención). ADR-071 / note 101."}

   {:db/ident       :kontor.tax/tax-group
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax-group ref. The group's payable/receivable
                     accounts are where collected/recoverable taxes
                     accumulate."}

   {:db/ident       :kontor.tax/include-base-amount
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether this tax's amount is added to the base for
                     subsequent (compound) taxes."}

   ;; note 198 R3-FP-03. `include-base-amount` is only meaningful given an
   ;; ORDER — \"subsequent\" has no referent otherwise. Until this attr
   ;; existed the StaticTableProvider consumed an unordered `d/q` result set,
   ;; so which tax counted as \"subsequent\" was whatever the set happened to
   ;; iterate first. Lower sequences are computed first.
   {:db/ident       :kontor.tax/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Order within the compound chain. Lower is computed
                     first; a tax with :include-base-amount true folds its
                     amount into the base seen by higher-sequence taxes.
                     Absent = 0; ties break on :kontor.tax/code so
                     resolution is always deterministic."}

   ;; note 198 R3-FP-02. Odoo `account.tax.price_include` (account_tax.py).
   {:db/ident       :kontor.tax/price-include
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True when quoted prices already CONTAIN this tax (the
                     B2C gross-price convention). The rate provider then
                     extracts the pre-tax net from the gross instead of
                     treating the gross as the base: 119.00 @ 19% → net
                     100.00 + tax 19.00, not 119.00 × 19% = 22.61. A
                     per-line `:price-include` in the rate-facts context
                     overrides this attr, since the same tax is quoted gross
                     to consumers and net to businesses."}

   {:db/ident       :kontor.tax/exigibility
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":on-invoice | :on-payment (cash-basis taxes)."}

   {:db/ident       :kontor.tax/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; Multi-jurisdiction filing — research note 09 (cross-country
   ;; variance). One business may file (a) GST/HST to Canada Revenue
   ;; Agency, (b) PST to Province of BC, (c) QST to Revenu Québec, and
   ;; (d) state sales tax to Texas Comptroller, all in the same
   ;; period. Without an explicit authority field the filing-time
   ;; aggregator has to string-match :kontor.tax/code prefixes — fragile
   ;; across l10n teams. Backfill DE entities with :de-bzst when those
   ;; ship; nil today (no DE :tax entities exist yet — UStVA tags hang
   ;; off accounts, not taxes).
   {:db/ident       :kontor.tax/authority
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
                     localizations like DE / AT."}

   ;; ADR-090: data-centric concept-iri seam. Tax categories can be
   ;; mapped to filing-taxonomy concepts (XBRL VAT line items, FIBO
   ;; TaxIdentifier, GST/HST authority IRIs). Substrate carries; consumer
   ;; aligns at filing time.
   {:db/ident       :kontor.tax/concept-iri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Optional IRI identifying this tax in an external
                     taxonomy (XBRL, FIBO, regulator namespace).
                     ADR-090."}])

(def ^:private tax-rep-attrs
  [{:db/ident       :kontor.tax-rep/tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the tax this repartition line belongs to."}

   {:db/ident       :kontor.tax-rep/document-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":invoice | :refund. Different document types use
                     different repartition (typically: refunds map to
                     the same accounts but with tax tags inverted for
                     reporting)."}

   {:db/ident       :kontor.tax-rep/repartition-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":base | :tax. Whether this repartition line covers
                     the base amount (the pre-tax revenue) or the tax
                     amount itself."}

   {:db/ident       :kontor.tax-rep/factor-percent
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Percentage of the (base or tax) amount this line
                     posts. 100M for the standard case; split values for
                     partial-deductible scenarios (e.g., DE 50% input-tax
                     deductibility on certain hospitality)."}

   {:db/ident       :kontor.tax-rep/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Account the posting lands on. nil for base
                     repartition that just attaches tags without
                     producing its own posting."}

   {:db/ident       :kontor.tax-rep/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs attached to the produced posting,
                     used for VAT-report-box aggregation."}

   {:db/ident       :kontor.tax-rep/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}])

(def ^:private tax-group-attrs
  [{:db/ident       :kontor.vat-group/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.vat-group/country-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.vat-group/payable-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Where collected output VAT lands (liability side)."}

   {:db/ident       :kontor.vat-group/receivable-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Where deductible input VAT lands (asset side)."}])

;; ============================================================================
;; Period — open/closed accounting periods.
;;
;; A period covers a date range (typically a month or fiscal year).
;; Once :kontor.period/locked-at is set, the sealing middleware refuses new
;; postings whose effective-date falls within. Lock-tx records the
;; transaction id of the close so reports can identify the closing
;; entries explicitly.
;;
;; Open question per ADR (deferred): whether period close also forks
;; a datahike branch for that period as the persistence pattern. Phase
;; 1 uses attributes only; we'll feel out fork-per-period later.
;; ============================================================================

(def ^:private period-attrs
  [{:db/ident       :kontor.period/start
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Inclusive start of the period (half-open with :end)."}

   {:db/ident       :kontor.period/end
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Exclusive end of the period — the range is [start, end)."}

   {:db/ident       :kontor.period/journal
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: scope the lock to a single journal.
                     nil = applies to all journals."}

   {:db/ident       :kontor.period/locked-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "SOFT close (ADR-014). Reopen-able via period/reopen!.
                     Refuses new postings whose tx's :tx/valid-from
                     falls in the range. Maps onto Odoo's
                     period_lock_date / tax_lock_date / sale_lock_date /
                     purchase_lock_date, NetSuite's 'Locked', Xero's
                     'Period Lock Date'."}

   {:db/ident       :kontor.period/sealed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "HARD close (ADR-014). Monotone — the date can only
                     move forward. Irrevocable — period/reopen! refuses
                     to clear it; sealing middleware refuses any retract
                     on a sealed-period entity. Maps onto Odoo's
                     hard_lock_date, Xero's 'End of Year Lock Date',
                     NetSuite's 'Closed', Sage Intacct's 'locked'."}

   {:db/ident       :kontor.period/sealed-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User ref recorded at the moment of sealing."}

   {:db/ident       :kontor.period/lock-tx
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-id of the soft-close (or seal), for audit reference."}

   {:db/ident       :kontor.period/adjustment?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "If true, this period overlaps the same date range as
                     a normal period and represents a year-end-adjustment
                     bucket — SAP's special periods 13–16. Mandatory for
                     DE compliance: HGB year-end audit corrections post
                     into period 13 with effective date 31 December but
                     must NOT appear in January's reports. Postings opt
                     into the adjustment period via :kontor.posting/period-tag."}

   {:db/ident       :kontor.period/tag
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator within an effective-date range when
                     multiple periods coexist. Conventional values:
                     :normal (the default if absent), :adjustment-13 ..
                     :adjustment-16 for SAP-style special periods."}

   {:db/ident       :kontor.period/name
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
  [{:db/ident       :kontor.balance-assertion/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.balance-assertion/at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time at which the assertion was made."}

   {:db/ident       :kontor.balance-assertion/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.balance-assertion/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.balance-assertion/source
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
;;   :kontor.transaction/effective-date is valid-time (when in the world this
;;   business event happened — invoice date, payment date).
;;   tx-time comes from datahike's :db/txInstant.
;; ============================================================================

(def ^:private transaction-attrs
  [{:db/ident       :kontor.transaction/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable caller-supplied id (\"INV-2026-0001\",
                     a beleg :kontor.invoice/id stringified, etc.). Identity."}

   {:db/ident       :kontor.transaction/journal
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.transaction/effective-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Valid-time. The business date of this entry. In a
                     bitemporal query, all :as-of-valid filters apply
                     against this attribute."}

   {:db/ident       :kontor.transaction/narration
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text description (\"Customer invoice 2026-0001
                     for ACME services\")."}

   {:db/ident       :kontor.transaction/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: the primary partner for this transaction
                     (the customer for an invoice, vendor for a bill).
                     Postings may also carry their own partner refs."}

   {:db/ident       :kontor.transaction/fiscal-position
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.transaction/state
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

   {:db/ident       :kontor.transaction/clearance-token
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
   {:db/ident       :kontor.transaction/document-type
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ref to a :document-type entity. The regulator-
                     recognized kind of fiscal document this
                     transaction represents (BR NF-e mod 55, NFC-e
                     mod 65, CT-e mod 57, CN special-VAT fapiao 01,
                     etc.). See ADR-020."}

   {:db/ident       :kontor.transaction/clearance-format
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

   {:db/ident       :kontor.transaction/posted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-time when the transaction was posted (state
                     :draft → :posted). Sealing trigger — see ADR-007
                     and sealing.clj. Once set, postings on this
                     transaction may not be silently retracted."}

   {:db/ident       :kontor.transaction/posted-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Caller-supplied user ref recorded at posting time."}

   {:db/ident       :kontor.transaction/reverses
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "If this transaction is a reversal, the original
                     transaction it reverses. Per ADR-007, corrections
                     are reversals + re-postings, never in-place edits."}

   {:db/ident       :kontor.transaction/closes-period
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Marks this transaction as the year-end (or any-
                     period) closing entry that zeros the P&L accounts
                     into retained earnings. Unique-identity prevents
                     a second closing entry for the same period."}

   {:db/ident       :kontor.transaction/source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-form provenance (\"beleg invoice 0001\",
                     \"bank statement camt053 2026-04-30\")."}

   {:db/ident       :kontor.transaction/settles
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Other transactions this one settles. A payment-
                     receipt transaction (driven by a bank line)
                     points to the invoice transactions it pays.
                     Cardinality many because one bank deposit can
                     settle multiple invoices for the same partner."}

   {:db/ident       :kontor.transaction/payment-term
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to a :payment-term entity. When set
                     plus :kontor.transaction/effective-date, the helper
                     fns in payment-term.clj derive :due-date and
                     :discount-deadline. Aging reports key off the
                     resulting due-date."}

   {:db/ident       :kontor.transaction/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "When the receivable is due. Either materialized
                     from :payment-term + :effective-date or set
                     explicitly by the importer / UI. Indexed because
                     aging reports filter on the relation
                     `due-date < today`."}

   {:db/ident       :kontor.transaction/discount-deadline
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
  [{:db/ident       :kontor.posting/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.posting/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.posting/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed amount in :kontor.posting/commodity. Positive = debit,
                     negative = credit. Postings within a transaction must
                     sum to zero per commodity."}

   {:db/ident       :kontor.posting/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.posting/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional: specific lot when the posting affects a
                     trackable inventory of units (stocks, crypto)."}

   {:db/ident       :kontor.posting/cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional cost-basis per unit at posting time, in
                     :kontor.posting/cost-commodity. Used by FIFO/LIFO disposal
                     calculation."}

   {:db/ident       :kontor.posting/cost-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; Foreign-currency support: when commodity differs from the journal's
   ;; reporting currency, both representations are stored.
   {:db/ident       :kontor.posting/amount-base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The same amount, expressed in the journal/company
                     base currency. Set iff :kontor.posting/commodity differs
                     from the base currency."}

   {:db/ident       :kontor.posting/base-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; Valid-time lives on the tx via kontor.bitemporal's
   ;; :tx/valid-from. ADR-048: all postings written by one tx share
   ;; that tx's :tx/valid-from, which the kernel builders stamp from
   ;; :kontor.transaction/effective-date. There is no per-posting valid-from
   ;; attribute. :valid-to and the temporal-key tuple were dropped per
   ;; research note 08 — corrections are reverse-and-repost.
   {:db/ident       :kontor.posting/display-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "What kind of line this is, mirroring Odoo's
                     account_move_line.display_type. One of: :product
                     (real posting), :tax (auto-generated tax line),
                     :payment-term (placeholder for the receivable/
                     payable from terms), :rounding (cash-rounding
                     adjustment), :section (UI section header),
                     :note (UI annotation, no posting effect)."}

   {:db/ident       :kontor.posting/partner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional partner ref (overrides
                     transaction-level partner for this line)."}

   {:db/ident       :kontor.posting/narration
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; Tax connection. A tax line carries :kontor.posting/tax-rep (the rep line
   ;; that produced it) and :kontor.posting/tax-base (the base amount the tax
   ;; was computed from). A base/product line that is taxed by N taxes
   ;; carries :kontor.posting/taxes-applied (cardinality many).
   {:db/ident       :kontor.posting/tax-rep
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "For :tax display-type postings: the
                     tax-repartition-line that produced this posting."}

   {:db/ident       :kontor.posting/tax-base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "For :tax display-type postings: the base amount
                     this tax was computed from."}

   {:db/ident       :kontor.posting/taxes-applied
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "For :product display-type postings: which taxes
                     were applied to this base. Cardinality-many because
                     a single line may be subject to multiple taxes
                     (e.g., GST + PST in BC)."}

   {:db/ident       :kontor.posting/account-tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Materialized account-tag refs at posting time
                     (mirroring tax-rep tags + account tags). Pre-
                     materialized so report engines need only one query."}

   {:db/ident       :kontor.posting/posted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Tx-time when this posting was posted. Sealing
                     trigger — see ADR-007. Set together with parent
                     transaction's :kontor.transaction/posted-at."}

   {:db/ident       :kontor.posting/period-tag
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional opt-in to a non-default period (ADR-014).
                     When two periods cover the same effective-date
                     range — e.g. a normal December and a year-end
                     adjustment :adjustment-13 — this tag picks which
                     one the posting routes into. Defaults to :normal
                     (i.e. the period whose :kontor.period/tag is absent or
                     :normal)."}

   ;; ADR-016 — Multi-tax breakdown. A product line subject to one
   ;; or more taxes carries a many-ref to :tax-application entities,
   ;; one per (line, tax) pairing. Brazil's 5-tax-stack invoice
   ;; produces 5 applications; JP dual-rate produces 1 per posting;
   ;; DE reverse-charge produces 1.
   ;;
   ;; This is parallel to :kontor.posting/taxes-applied (which still records
   ;; "this line was taxed by these taxes" for simple uses) and to
   ;; the auto-generated :tax-display-type postings (the ledger entries).
   ;; The breakdown is intent + audit; the postings are bookkeeping.
   {:db/ident       :kontor.posting/tax-breakdown
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Many-ref to :tax-application entities. ADR-016."}

   ;; ADR-018 — clearance token mirror at the posting level. Set
   ;; together with :kontor.transaction/clearance-token by the country
   ;; module's EInvoiceProvider on transition :pending-attestation
   ;; → :posted. Mirrored at the posting level so reports keyed off
   ;; postings (rather than transactions) can find the token directly.
   {:db/ident       :kontor.posting/clearance-token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Mirror of :kontor.transaction/clearance-token at the
                     posting level for query ergonomics. Set together
                     with the parent transaction's token. ADR-018."}

   ;; ADR-097 — classification dimensions. `:kontor.posting/account` is ONE
   ;; classification axis; this many-ref carries the others (cost-
   ;; centre, project, segment, fund, McComb-style business
   ;; categories). The report engine `marginalize`s over any axis
   ;; (ADR-096). Written explicitly by the consumer / verb facade —
   ;; NOT materialized the way `:kontor.posting/account-tags` is.
   {:db/ident       :kontor.posting/dimensions
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Many-ref to :posting-dimension entities — the
                     classification axes of this posting beyond
                     :kontor.posting/account. ADR-097."}])

;; ============================================================================
;; Posting dimension — a flat classification tag on a posting (ADR-097).
;;
;; Demotes :account from THE classification axis to ONE axis among
;; several. A :posting-dimension is a CBox entity in McComb's
;; TBox/CBox/ABox split: a consumer-governed
;; taxonomy value, not kernel structure. Per the note-99 DCR
;; sharpening, taxonomies are FLAT TAGS — :value is a string, not an
;; entity with its own relational web. The anti-pattern to avoid is
;; axis-as-attribute (a bespoke :kontor.posting/cost-center attr per axis —
;; the SNOMED mistake): keep the axes few and structural, the values
;; data.
;; ============================================================================

(def ^:private posting-dimension-attrs
  [{:db/ident       :kontor.posting-dimension/axis
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The classification axis — :cost-center, :project,
                     :segment, :fund, … An open-set keyword; consumers
                     define their own axes. ADR-097."}

   {:db/ident       :kontor.posting-dimension/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The class within the axis — a flat string tag
                     (\"CC-Sales\", \"PRJ-Alpha\"). Consumers key
                     their own vocabularies. ADR-097."}])

(def ^:private tax-application-attrs
  "ADR-016 — per-posting per-tax computation record. Captures the
   base, the resulting tax amount, and the compound-on lineage for
   audit + report queries that need direct (not derived) per-tax
   detail. One :tax-application entity per (product-line × tax) pair."
  [{:db/ident       :kontor.tax-application/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Back-ref to the :product-display-type posting
                     this application annotates."}

   {:db/ident       :kontor.tax-application/tax
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :tax entity that was applied."}

   {:db/ident       :kontor.tax-application/base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The base amount this tax was computed against.
                     Differs from the posting's amount when other
                     taxes compound into the base (BR ICMS-on-net+IPI
                     case)."}

   {:db/ident       :kontor.tax-application/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The resulting tax amount."}

   {:db/ident       :kontor.tax-application/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Account-tag refs (typically inherited from the
                     tax-repartition lines) used by reports."}

   {:db/ident       :kontor.tax-application/compound-on
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Other :tax entities whose amounts were folded
                     into this application's :base (i.e. taxes whose
                     :kontor.tax/include-base-amount was true and which
                     preceded this one in the chain)."}

   {:db/ident       :kontor.tax-application/sequence
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
;; compute :kontor.transaction/due-date + :kontor.transaction/discount-deadline
;; from :kontor.transaction/effective-date + :payment-term.
;; ============================================================================

(def ^:private payment-term-attrs
  [{:db/ident       :kontor.payment-term/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable id (\"NET30\", \"NET14\", \"DUE-ON-RECEIPT\",
                     \"2/10-NET30\")."}

   {:db/ident       :kontor.payment-term/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label (\"30 days net\")."}

   {:db/ident       :kontor.payment-term/net-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Days from invoice date until full payment is due.
                     0 means due on receipt."}

   {:db/ident       :kontor.payment-term/discount-pct
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional early-pay discount percentage
                     (e.g. 2.0M for 2%)."}

   {:db/ident       :kontor.payment-term/discount-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional days within which the discount applies
                     (e.g. 10 for 2/10-NET30)."}

   {:db/ident       :kontor.payment-term/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Payment-term LINES — instalment plans ("30% now, 70% in 60 days").
;;
;; A term with no lines is the scalar net-days case and keeps behaving
;; exactly as before; `compute-tranches` synthesises the single 100%
;; tranche for it. A term WITH lines explodes an invoice into one tranche
;; per line, each with its own due date, so tranches age and settle
;; independently. Odoo: account.payment.term.line (value_amount + nb_days),
;; exploded by AccountPaymentTerm._compute_terms. note 198 R3-FP-04.
;; ============================================================================

(def ^:private payment-term-line-attrs
  [{:db/ident       :kontor.payment-term-line/term
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the owning :payment-term."}

   {:db/ident       :kontor.payment-term-line/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Order of the tranche within the term. Lower first;
                     the LAST tranche absorbs any rounding residue so the
                     tranches always sum to the invoice total exactly."}

   {:db/ident       :kontor.payment-term-line/value-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":percent — :value percent of the total.
                     :fixed   — :value as an absolute amount.
                     :balance — whatever is left after the other lines
                                (Odoo's `balance`; :value is ignored)."}

   {:db/ident       :kontor.payment-term-line/value
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "30M for 30% when :value-type is :percent; the absolute
                     amount when :fixed; ignored when :balance."}

   {:db/ident       :kontor.payment-term-line/nb-days
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Calendar days from the invoice date to THIS tranche's
                     due date. 0 = due immediately."}

   {:db/ident       :kontor.payment-term-line/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.payment-term-line/term
                     :kontor.payment-term-line/sequence]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

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
;; lives in :transaction; :invoice points to it via :kontor.invoice/transaction.
;; Line items live in :invoice-line entities (cardinality-many ref
;; from :invoice) and carry the per-line VAT rate the country module
;; uses to split postings.
;; ============================================================================

(def ^:private invoice-attrs
  [{:db/ident       :kontor.invoice/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Invoice number (\"INV-2026-0001\"). Stable
                     identity across status transitions."}

   {:db/ident       :kontor.invoice/issue-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ausstellungsdatum / invoice date."}

   {:db/ident       :kontor.invoice/seller
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :partner entity issuing the invoice
                     (typically the user's own company partner)."}

   {:db/ident       :kontor.invoice/buyer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the :partner entity being invoiced
                     (the customer)."}

   {:db/ident       :kontor.invoice/payment-term
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice/due-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Materialized from issue-date + payment-term, OR
                     supplied directly. Aging reports filter on this."}

   {:db/ident       :kontor.invoice/discount-deadline
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice/currency
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO 4217 currency code (\"EUR\", \"USD\")."}

   {:db/ident       :kontor.invoice/total-net
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice/total-vat
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice/total-gross
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Sum the customer owes. Reconciliation matches
                     bank-line amount against this for AR settlement."}

   {:db/ident       :kontor.invoice/lines
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Line items. Cardinality many; ordered via
                     :kontor.invoice-line/sequence."}

   {:db/ident       :kontor.invoice/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":draft | :sent | :paid | :cancelled. Lifecycle:
                       draft → sent (auto-creates accounting tx)
                       sent  → paid (set by reconciliation settle)
                       sent  → cancelled (creates reversal tx)"}

   ;; NOTE: :kontor.invoice/sent-at / :paid-at / :cancelled-at / :posted-at
   ;; (the status-transition timestamp denorms) were removed —
   ;; resolvable from :status-history + :tx/valid-from via
   ;; (d/pull (d/valid-at db now) [:kontor.invoice/status] inv). The presence
   ;; of :kontor.invoice/transaction is the canonical "posted to GL"
   ;; sentinel; no separate :posted-at needed.

   {:db/ident       :kontor.invoice/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The accounting :transaction created on :sent.
                     Reconciliation walks back from this to flip the
                     invoice's status to :paid."}

   {:db/ident       :kontor.invoice/factur-x-xml
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The EN16931 / Factur-X / XRechnung XML payload
                     (UTF-8). Generated by einvoice-de.factur-x at
                     :sent transition when configured."}

   {:db/ident       :kontor.invoice/factur-x-pdf
    :db/valueType   :db.type/bytes
    :db/cardinality :db.cardinality/one
    :db/doc         "The Factur-X PDF/A-3 with embedded XML. Set
                     when the invoice was rendered to PDF (caller
                     supplies the PDF/A-3 input)."}

   {:db/ident       :kontor.invoice/buyer-reference
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Buyer-side reference / Leitweg-ID for B2G
                     XRechnung. Embeds in the EN16931 payload."}

   {:db/ident       :kontor.invoice/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "Free-text remarks (one entry per note)."}])

(def ^:private invoice-line-attrs
  [{:db/ident       :kontor.invoice-line/invoice
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Backref to parent :invoice. Redundant with
                     :kontor.invoice/lines but indexed for query."}

   {:db/ident       :kontor.invoice-line/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Display ordering (1, 2, 3 …)."}

   {:db/ident       :kontor.invoice-line/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice-line/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice-line/quantity
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.invoice-line/unit-code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "UN/CEFACT unit code (\"HUR\"=hour, \"EA\"=each,
                     \"KGM\"=kg, \"DAY\"=day)."}

   {:db/ident       :kontor.invoice-line/unit-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net unit price."}

   {:db/ident       :kontor.invoice-line/vat-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "VAT percentage (e.g. 19.0M for German Regelsatz,
                     7.0M for ermäßigt, 0M for steuerfrei)."}

   {:db/ident       :kontor.invoice-line/vat-category
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "UNTDID 5305 code: S=standard, AA=reduced,
                     Z=zero, E=exempt, etc. Used by Factur-X."}

   {:db/ident       :kontor.invoice-line/account
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
;; Idempotency: `:kontor.bank-line/external-id` is unique-identity. The
;; ingestion code derives it as a hash of (bank, date, amount,
;; raw-row) so re-importing the same statement is a no-op.
;; ============================================================================

(def ^:private bank-line-attrs
  [{:db/ident       :kontor.bank-line/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable id derived from (bank, date, amount,
                     raw-row-hash). Re-importing the same statement
                     hits the same id and is idempotent."}

   {:db/ident       :kontor.bank-line/bank
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Bank/format keyword (:dkb :ing :chase :n26 …)."}

   {:db/ident       :kontor.bank-line/source-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Chart account this bank-line lands on (the bank-
                     side leg of the eventual posting). E.g. SKR04
                     1200 / 1210 / 1230. Required at ingestion so the
                     import driver knows which account to post."}

   {:db/ident       :kontor.bank-line/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.bank-line/value-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-line/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed: positive = inflow (credit on bank
                     statement), negative = outflow (debit). Mirrors
                     the bank's POV; the eventual posting's bank-side
                     amount has the same sign as this attribute."}

   {:db/ident       :kontor.bank-line/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-line/counterparty
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-line/counterparty-iban
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.bank-line/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text Verwendungszweck / memo. Used by the
                     reference-id matcher to detect invoice external-
                     ids embedded in the statement text."}

   {:db/ident       :kontor.bank-line/transaction-type
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Bank-side type label (Lastschrift, Gutschrift,
                     ACH_DEBIT, etc.). Free-form per importer."}

   {:db/ident       :kontor.bank-line/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Auto-categorizer output from bank-csv (e.g.
                     :miete, :gehalt, :einnahmen). Suggests a contra
                     account when no AR/AP match is found."}

   {:db/ident       :kontor.bank-line/raw-row
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Original CSV row joined with a separator. Stored
                     for audit / re-parse / diff against future
                     importer changes."}

   {:db/ident       :kontor.bank-line/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":unmatched | :matched | :reconciled | :ignored.
                     A reconciliation queue UI filters on this."}

   {:db/ident       :kontor.bank-line/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The bank-side posting created when the line is
                     reconciled. Set during commit-match!"}

   {:db/ident       :kontor.bank-line/reconciled-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Tx-time when the bank-line was reconciled."}])

;; ============================================================================
;; Analytic accounting (cost-center / profit-center / project) — ADR-012.
;;
;; A separate dimension orthogonal to the financial account hierarchy.
;; Postings can carry zero or more `:kontor.posting/analytic-distributions`,
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
  [{:db/ident       :kontor.analytic-plan/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identity (\"COST-CENTER\", \"PROJECT\",
                     \"DEPARTMENT\")."}

   {:db/ident       :kontor.analytic-plan/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.analytic-plan/applicability
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional gate: which posting context the plan
                     applies to. nil = all postings."}

   {:db/ident       :kontor.analytic-plan/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private analytic-account-attrs
  [{:db/ident       :kontor.analytic-account/path
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Hierarchical path within a plan
                     (\"COST-CENTER:Engineering:Frontend\")."}

   {:db/ident       :kontor.analytic-account/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :kontor.analytic-account/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.analytic-account/plan
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.analytic-account/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.analytic-account/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private analytic-distribution-attrs
  [{:db/ident       :kontor.analytic-distribution/plan
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Which plan this distribution applies under
                     (a posting may carry distributions in multiple
                     plans simultaneously — one per plan)."}

   {:db/ident       :kontor.analytic-distribution/account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The analytic account the percent points at."}

   {:db/ident       :kontor.analytic-distribution/percent
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "0..100 inclusive. Sum-to-100 per plan is enforced
                     by the report engine, not by the schema."}

   {:db/ident       :kontor.analytic-distribution/posting
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the posting this distribution annotates.
                     A posting may carry multiple distributions; we
                     reverse-traverse via :kontor.posting/_analytic-distribution-
                     posting at query time. Storing the back-ref directly
                     also keeps the distribution entity self-contained."}])

;; Attribute on posting holding the cardinality-many ref to
;; distributions. Lives in the posting namespace so consumers see the
;; whole posting shape in `(d/pull db [...] eid)`.

(def ^:private posting-analytic-attrs
  [{:db/ident       :kontor.posting/analytic-distributions
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Optional analytic-distribution refs annotating
                     this posting. Independent of the financial
                     :kontor.posting/account; used by management-reporting
                     queries and SR&ED-style project tracking."}])

;; ============================================================================
;; Per-account required analytic plans — ADR-022.
;;
;; Postings against an account that names plans here must carry
;; distributions in each named plan summing to 100%. Optional.
;; ============================================================================

(def ^:private account-analytic-attrs
  [{:db/ident       :kontor.account/required-analytic-plans
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
;; `kontor.reporting.ledger/install-defaults!`; consumers add secondary ledgers.
;; Sum-to-zero in a transaction is enforced PER LEDGER.
;; ============================================================================

(def ^:private ledger-attrs
  [{:db/ident       :kontor.ledger/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"primary\", \"ifrs\", \"hgb\",
                     \"budget\", \"statistical\")."}

   {:db/ident       :kontor.ledger/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.ledger/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":primary | :secondary | :adjustment
                     | :budget | :statistical"}

   {:db/ident       :kontor.ledger/framework
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting framework keyword.
                     :IFRS | :US-GAAP | :HGB | :ASBE | :NCRF | :ind-AS
                     | :local | ... — free-form, l10n-defined."}

   {:db/ident       :kontor.ledger/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this ledger when it
                     differs from the transaction commodity."}

   {:db/ident       :kontor.ledger/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private posting-ledger-attrs
  [{:db/ident       :kontor.posting/ledger
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
  [{:db/ident       :kontor.country/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO 3166-1 alpha-2 (\"IN\", \"BR\", \"CA\")."}

   {:db/ident       :kontor.country/code-iso3
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "ISO 3166-1 alpha-3 (\"IND\", \"BRA\", \"CAN\").
                     Optional — only loaded by l10n modules that
                     need it."}

   {:db/ident       :kontor.country/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "English name. Localized display names belong in
                     consumer apps."}

   {:db/ident       :kontor.country/default-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Default trading commodity (INR, BRL, CAD…).
                     Hint only; does not constrain account commodity."}

   {:db/ident       :kontor.country/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :country-code entities — per-regulator
                     aliases beyond ISO. Mirrors ADR-019."}

   {:db/ident       :kontor.country/groups
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :country-group entities (EU, EEA, NAFTA…).
                     Many-to-many; a country may belong to several."}

   {:db/ident       :kontor.country/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private country-code-attrs
  [{:db/ident       :kontor.country-code/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the country this external code is for."}

   {:db/ident       :kontor.country-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :iso-3166-1-numeric, :un/m49,
                     :sap/land1, :in/customs, …"}

   {:db/ident       :kontor.country-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system."}

   {:db/ident       :kontor.country-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.country-code/country :kontor.country-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (country, regulator)
                     pairing exists at most once."}

   {:db/ident       :kontor.country-code/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional human-readable explanation."}])

(def ^:private country-group-attrs
  [{:db/ident       :kontor.country-group/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"EU\", \"EEA\", \"NAFTA\",
                     \"G7\", \"USMCA\")."}

   {:db/ident       :kontor.country-group/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private state-attrs
  [{:db/ident       :kontor.state/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent country. Required."}

   {:db/ident       :kontor.state/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "ISO 3166-2 suffix (\"MH\", \"QC\", \"SP\", \"JAL\").
                     Just the local part — not the full \"IN-MH\"."}

   {:db/ident       :kontor.state/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.state/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.state/country :kontor.state/code]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity. One (country, state-code)
                     pairing exists at most once."}

   {:db/ident       :kontor.state/external-codes
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :state-code entities — per-regulator
                     codes beyond ISO 3166-2 (Indian GSTN, Brazilian
                     IBGE, Canadian CRA province code, etc.)."}

   {:db/ident       :kontor.state/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

(def ^:private state-code-attrs
  [{:db/ident       :kontor.state-code/state
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the state this external code is for."}

   {:db/ident       :kontor.state-code/regulator
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Identifies the regulator / mapping target.
                     Conventions: :in/gst, :br/ibge, :ca/cra,
                     :iso-3166-2, :sap/bland, :sat/c-estado, …"}

   {:db/ident       :kontor.state-code/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The code in the regulator's system.
                     E.g. \"27\" (GSTN for Maharashtra),
                     \"35\" (IBGE for São Paulo),
                     \"13\" (CRA for Quebec)."}

   {:db/ident       :kontor.state-code/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.state-code/state :kontor.state-code/regulator]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity."}

   {:db/ident       :kontor.state-code/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private partner-state-attrs
  [{:db/ident       :kontor.partner/state
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Partner's registered state — billing / address.
                     Distinct from :kontor.transaction/place-of-supply.
                     Optional (consumer-side bootstrap)."}])

(def ^:private transaction-pos-attrs
  [{:db/ident       :kontor.transaction/place-of-supply
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
;; singular :kontor.transaction/clearance-token; the cardinality-many is
;; authoritative when both are present.
;; ============================================================================

(def ^:private attestation-attrs
  [{:db/ident       :kontor.attestation/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the transaction this attestation
                     belongs to."}

   {:db/ident       :kontor.attestation/format
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Format keyword. Conventions:
                     :in/irn :in/ewb-part-a :in/ewb-part-b
                     :br/nfe-44 :mx/cfdi-uuid :cn/fapiao-20
                     :sa/zatca-icv :sa/zatca-pih :tr/efatura
                     :kr/nts-chain :it/sdi-id"}

   {:db/ident       :kontor.attestation/token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The issued artifact identifier (IRN hash, UUID,
                     access-key, …)."}

   {:db/ident       :kontor.attestation/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending | :issued | :revoked | :expired
                     | :superseded"}

   {:db/ident       :kontor.attestation/issued-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the authority's response landed."}

   {:db/ident       :kontor.attestation/valid-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional start of the legal validity window."}

   {:db/ident       :kontor.attestation/valid-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional end of the legal validity window.
                     E-way bills: 1 day per 200 km (regular) or
                     1 day per 20 km (over-dimensional cargo)."}

   {:db/ident       :kontor.attestation/depends-on
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to other :attestation entities this one
                     depends on. India: EWB Part A derives from the
                     IRN, so the EWB attestation depends-on the IRN."}

   {:db/ident       :kontor.attestation/payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical bytes sent to / received from the
                     authority. Required for cryptographic-stamp
                     regimes (KSA ZATCA Phase 2, Turkey, Korea) where
                     the bytes themselves are the legal record."}

   {:db/ident       :kontor.attestation/payload-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "SHA-256 of :kontor.attestation/payload, hex-encoded.
                     PIH (previous-invoice-hash) chains reference
                     this to link consecutive attestations."}

   {:db/ident       :kontor.attestation/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.attestation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.attestation/transaction :kontor.attestation/format]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One (transaction, format) pair exists at most
                     once. Re-issuing replaces."}])

(def ^:private transaction-attestations-attrs
  [{:db/ident       :kontor.transaction/attestations
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :attestation entities. ADR-024.

                     Coexists with :kontor.transaction/clearance-token
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
;; parent in :kontor.complemento/sequence order. The kernel stores opaque
;; payload bytes; XSD validation lives in the l10n module that owns
;; the namespace.
;; ============================================================================

(def ^:private complemento-attrs
  [{:db/ident       :kontor.complemento/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Back-ref to the transaction this complemento
                     attaches to."}

   {:db/ident       :kontor.complemento/namespace
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Canonical XML namespace URI.
                     E.g. \"http://www.sat.gob.mx/CartaPorte31\",
                     \"http://www.sat.gob.mx/Pagos20\",
                     \"http://www.sat.gob.mx/TimbreFiscalDigital\"."}

   {:db/ident       :kontor.complemento/format
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Convenience identifier keyword.
                     E.g. :mx/cfdi-pagos-2.0 :mx/cfdi-carta-porte-3.1
                     :mx/cfdi-nomina-1.2 :mx/cfdi-tfd-1.1
                     :ubl/factur-x-additional-doc."}

   {:db/ident       :kontor.complemento/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Ordering within the envelope. Some XSDs enforce
                     a defined order; safe to default to insertion
                     order (0, 100, 200, …)."}

   {:db/ident       :kontor.complemento/payload
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The XML fragment as a string. The kernel does
                     not validate against the XSD; the emitter in
                     the l10n module does."}

   {:db/ident       :kontor.complemento/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Soft-supersede flag. Set to false when a later
                     complemento replaces this one (idempotency)."}

   {:db/ident       :kontor.complemento/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.complemento/transaction :kontor.complemento/namespace]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One fragment per (transaction, namespace)."}])

(def ^:private transaction-complementos-attrs
  [{:db/ident       :kontor.transaction/complementos
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to :complemento entities. ADR-025."}])

;; ============================================================================
;; Effective-dated tax rates — ADR-026.
;;
;; Optional :kontor.tax/effective-from / :kontor.tax/effective-until on existing
;; :tax entities. TaxRateProvider selects the tax record whose validity
;; window contains the transaction's effective-date. Drives India
;; GST 2.0 (pre-2025-09-22 vs current), Brazil IBS/CBS transition,
;; Mexico IEPS annual cuotas, Germany 7%-vs-19% restaurant VAT.
;; ============================================================================

(def ^:private tax-effective-window-attrs
  [{:db/ident       :kontor.tax/effective-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Start of the rate's legal validity window.
                     Nil means -infinity (always-effective). ADR-026."}

   {:db/ident       :kontor.tax/effective-until
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
;; installed by `kontor.provider.valuation/install-defaults!`.
;; ============================================================================

(def ^:private valuation-book-attrs
  [{:db/ident       :kontor.valuation-book/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"primary\", \"ifrs\",
                     \"tax-de\", \"management\")."}

   {:db/ident       :kontor.valuation-book/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.valuation-book/framework
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting framework keyword.
                     :legal | :group | :ifrs | :us-gaap | :hgb
                     | :tax-de | :management | … free-form."}

   {:db/ident       :kontor.valuation-book/cost-method
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":fifo | :lifo | :avg | :standard | :specific.
                     CostingProvider impls dispatch on this."}

   {:db/ident       :kontor.valuation-book/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this book when it differs
                     from the transaction commodity. Optional."}

   {:db/ident       :kontor.valuation-book/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Valuation layer + consumption + adjustment — ADR-028.
;;
;; Three immutable fact entities. Layer = receipt event; consumption
;; = issue event referencing a layer; adjustment = landed-cost /
;; revaluation event referencing a layer. Remaining qty + current
;; cost are *views* (kontor.provider.valuation/qty-remaining + /current-cost).
;; ============================================================================

(def ^:private valuation-layer-attrs
  [{:db/ident       :kontor.valuation-layer/book
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-book."}

   {:db/ident       :kontor.valuation-layer/item
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref. The kernel does not model 'item';
                     the consumer-side inventory module defines what
                     an item is and points the layer at it. (ADR-010
                     scope honesty.)"}

   {:db/ident       :kontor.valuation-layer/lot
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :lot. Lot-isolated FIFO uses
                     this to keep separate stacks per lot."}

   {:db/ident       :kontor.valuation-layer/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that created
                     this layer (the receipt event)."}

   {:db/ident       :kontor.valuation-layer/qty-original
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity received. Immutable. Remaining quantity
                     is a derived view: qty-original − Σ consumption.qty."}

   {:db/ident       :kontor.valuation-layer/unit-cost-original
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Per-unit cost at receipt. Immutable. Current cost
                     is a derived view that folds in adjustments."}

   {:db/ident       :kontor.valuation-layer/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Cost currency."}

   {:db/ident       :kontor.valuation-layer/received-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid time of the receipt. Used to order FIFO/LIFO
                     stacks. Distinct from :origin-transaction's tx-time."}

   {:db/ident       :kontor.valuation-layer/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private layer-consumption-attrs
  [{:db/ident       :kontor.layer-consumption/layer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-layer."}

   {:db/ident       :kontor.layer-consumption/qty
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Quantity consumed FROM the referenced layer in
                     this single event. One issue may produce multiple
                     consumption rows (one per drawn layer)."}

   {:db/ident       :kontor.layer-consumption/unit-cost-at-consumption
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Book value per unit at the moment of consumption.
                     Folds in any :layer-adjustment that was applied
                     before this event."}

   {:db/ident       :kontor.layer-consumption/issue-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that issued this
                     consumption (the outbound move)."}

   {:db/ident       :kontor.layer-consumption/issued-at
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
  [{:db/ident       :kontor.entity/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"acme-de\", \"acme-us\",
                     \"acme-group\", \"acme-eliminations\")."}

   {:db/ident       :kontor.entity/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.entity/country
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :country. Synthetic entities
                     (:elimination, :consolidation) may omit."}

   {:db/ident       :kontor.entity/functional-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Accounting currency for this entity's standalone
                     books. Optional — synthetic entities may run in
                     a group currency selected at consolidation time."}

   {:db/ident       :kontor.entity/parent-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Group hierarchy. Self-reference; root entity has
                     no parent. Matches Odoo's :parent_id pattern."}

   {:db/ident       :kontor.entity/accounting-standard
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":hgb | :us-gaap | :br-gaap | :ifrs | :local
                     | … free-form. Drives reporting + tax filing
                     choices; consumed by l10n modules."}

   {:db/ident       :kontor.entity/kind
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

   {:db/ident       :kontor.entity/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   ;; GLEIF master-data hooks (kontor-import-gleif / ADR-090 family).
   ;; The LEI is the global join key for cross-jurisdictional consolidations
   ;; (ADR-073). EDGAR ingest joins on CIK→LEI, Companies House on CRN→LEI,
   ;; Bundesanzeiger on HRB→LEI — and GLEIF carries every cross-reference.
   {:db/ident       :kontor.entity/lei
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "GLEIF Legal Entity Identifier (20-character ISO
                     17442). Optional — synthetic entities + sole
                     proprietorships rarely carry an LEI. Unique-value
                     (not -identity) so an entity can be looked up by
                     LEI but uniqueness is enforced. ADR-073 +
                     research note 91 §6."}

   {:db/ident       :kontor.entity/legal-form
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "GLEIF ELF (Entity Legal Form) code or local
                     equivalent — 'GmbH', 'LLC', 'plc', 'AS', 'SA',
                     'KK', etc. Free-string; consumers using the
                     ISO 20275 codes use those (e.g. '2HBR' for GmbH)."}

   {:db/ident       :kontor.entity/registration-status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "GLEIF registration status. Open-set:
                     :issued | :lapsed | :merged | :retired
                     | :duplicate | :transferred | :annulled."}

   {:db/ident       :kontor.entity/parent-lei
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Direct parent LEI (GLEIF RR-CDF Level 2
                     IS_DIRECTLY_CONSOLIDATED_BY). Stored as the raw
                     LEI string for ingest-time + reingest-time
                     debugging; the resolved :kontor.entity/parent-entity ref
                     is the structural answer that
                     `kontor.entity/family` walks."}

   {:db/ident       :kontor.entity/ultimate-parent-lei
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Ultimate parent LEI (GLEIF RR-CDF
                     IS_ULTIMATELY_CONSOLIDATED_BY). Same posture as
                     :kontor.entity/parent-lei — provenance only."}

   {:db/ident       :kontor.entity/source-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Provenance opaque identifier — e.g.
                     'gleif://Golden-Copy/2026-05-18',
                     'edgar://CIK0000320193/10-K/2024'. Carries the
                     ingest source + version for audit + idempotent
                     re-ingest."}])

(def ^:private posting-entity-attrs
  [{:db/ident       :kontor.posting/entity
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

(def ^:private intercompany-pair-attrs
  [{:db/ident       :kontor.transaction/intercompany-pair-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Shared identifier across the two (or more)
                     transactions that together form an intercompany
                     event — e.g. a DE-GmbH AR booking + the matching
                     US-LLC AP booking against each other. The
                     consolidation engine
                     (kontor.provider.consolidation/eliminate-intercompany-pair-tx-data)
                     finds the group by this id and emits offsetting
                     postings on the consolidation/elimination entity.

                     Not unique: the same pair-id by construction
                     appears on each tx in the pair. ADR-073."}

   {:db/ident       :kontor.transaction/consolidation-source-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Provenance ref on a consolidation tx: the
                     operating entity whose trial-balance was
                     translated to produce this entry. Set by
                     kontor.provider.consolidation/translate-trial-balance-tx-data;
                     readable for audit + drill-back. ADR-073."}

   {:db/ident       :kontor.transaction/consolidation-kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":translation | :elimination — tags the kind of
                     consolidation tx so the audit reader can filter.
                     ADR-073."}])

(def ^:private ledger-entity-attrs
  [{:db/ident       :kontor.ledger/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :entity (ADR-031). Per-ERP
                     consensus: ledger is per-entity. Synthetic
                     consolidation entities have their own ledger.
                     Schema-optional for single-entity tenants."}])

(def ^:private valuation-book-entity-attrs
  [{:db/ident       :kontor.valuation-book/entity
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
  [{:db/ident       :kontor.schedule/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable identifier (\"asset-1234-dep\",
                     \"sub-acme-2026-q3-rev\", \"lease-bldg-01\")."}

   {:db/ident       :kontor.schedule/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.schedule/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":depreciation | :revenue-recognition
                     | :subscription-billing | :lease-amortization
                     | :pto-accrual | :prepaid-amortization
                     | … free-form. Consumers extend."}

   {:db/ident       :kontor.schedule/origin-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref — the asset / contract /
                     subscription / lease this schedule belongs to.
                     Consumer defines what that entity is."}

   {:db/ident       :kontor.schedule/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "First scheduled occurrence (inclusive)."}

   {:db/ident       :kontor.schedule/end-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last scheduled occurrence (inclusive). Optional;
                     nil = indefinite."}

   {:db/ident       :kontor.schedule/frequency
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":daily | :weekly | :monthly | :quarterly |
                     :annual | :custom. Consumers compute the next
                     occurrence date using this."}

   {:db/ident       :kontor.schedule/total-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Total amount to be amortized over the schedule.
                     Optional — only meaningful for finite schedules
                     (depreciation, prepaid amortization). Schedules
                     where per-period amount is computed elsewhere
                     (subscription billing with variable usage) omit
                     this."}

   {:db/ident       :kontor.schedule/total-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity for :kontor.schedule/total-amount."}

   {:db/ident       :kontor.schedule/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":active | :paused | :completed | :cancelled."}

   {:db/ident       :kontor.schedule/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.schedule/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private schedule-occurrence-attrs
  [{:db/ident       :kontor.schedule-occurrence/schedule
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :schedule. Back-pointer."}

   {:db/ident       :kontor.schedule-occurrence/sequence
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "1, 2, 3, … The Nth firing of the schedule."}

   {:db/ident       :kontor.schedule-occurrence/scheduled-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The valid-time date this occurrence is for.
                     (E.g. \"depreciation for month 2026-05\".)"}

   {:db/ident       :kontor.schedule-occurrence/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to the kernel :transaction that
                     this occurrence produced."}

   {:db/ident       :kontor.schedule-occurrence/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "This period's amount. Consumer-computed."}

   {:db/ident       :kontor.schedule-occurrence/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.schedule-occurrence/fired-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Wall-clock time the occurrence was recorded.
                     Distinct from :scheduled-date (the valid-time)
                     and from the underlying datahike tx-time."}

   {:db/ident       :kontor.schedule-occurrence/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.schedule-occurrence/schedule
                     :kontor.schedule-occurrence/sequence]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity: one occurrence per
                     (schedule, sequence) pair. Idempotent — re-
                     firing period 7 collapses to the existing row."}])

(def ^:private layer-adjustment-attrs
  [{:db/ident       :kontor.layer-adjustment/layer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Required ref to :valuation-layer."}

   {:db/ident       :kontor.layer-adjustment/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Signed TOTAL amount (not per-unit). Adds to the
                     layer's total cost; positive for landed cost
                     additions, negative for write-downs."}

   {:db/ident       :kontor.layer-adjustment/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":landed-cost | :revaluation | :correction
                     | :write-down | :write-up | … free-form."}

   {:db/ident       :kontor.layer-adjustment/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel :transaction that booked this
                     adjustment (the landed-cost voucher / revaluation)."}

   {:db/ident       :kontor.layer-adjustment/applied-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.layer-adjustment/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Status-transition + status-history (ADR-034)
;;
;; Cross-cutting state-machine primitive. Companions seed their own
;; transition vocabulary; the kernel ships zero seed data. See
;; `kontor.workflow.status-machine` for the public surface.
;; ============================================================================

(def ^:private status-transition-attrs
  [{:db/ident       :kontor.status-transition/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Discriminator: which entity type this transition
                     applies to. :order, :order-item, :invoice,
                     :requirement, :shipment, … Consumers extend."}

   {:db/ident       :kontor.status-transition/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "The attribute on the entity carrying this state.
                     Typically :kontor.order/status, :kontor.invoice/status, etc.
                     One entity can have multiple facets — multiple
                     concurrent state machines on the same row."}

   {:db/ident       :kontor.status-transition/from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "From-state keyword. Use a :*/nil sentinel keyword
                     (e.g. :order.status/nil) for the 'new entity'
                     pseudo-state — datahike's nil-handling is awkward
                     for tx values."}

   {:db/ident       :kontor.status-transition/to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.status-transition/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable transition name (\"Approve Order\",
                     \"Mark Paid\"). For UI / log rendering."}

   {:db/ident       :kontor.status-transition/applies-to-org
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional :entity ref (ADR-031). When nil, the
                     transition applies tenant-wide. When set, scopes
                     to that org as an override that does NOT delete
                     the global row — both can coexist; the predicate
                     prefers the org-specific match."}

   {:db/ident       :kontor.status-transition/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Soft-delete flag. Inactive transitions are
                     ignored by `legal-transition?` but retained for
                     audit-history queries."}

   {:db/ident       :kontor.status-transition/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; ADR-041: time-based transition extension.
   {:db/ident       :kontor.status-transition/auto-after-millis
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Duration in milliseconds. When set, the
                     kontor.workflow.status-machine/sweep-time-based! helper
                     auto-applies this transition to entities that
                     have been in the from-state longer than the
                     duration. Nil = manual-only. ADR-041."}

   {:db/ident       :kontor.status-transition/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.status-transition/entity-type
                     :kontor.status-transition/facet
                     :kontor.status-transition/from
                     :kontor.status-transition/to
                     :kontor.status-transition/applies-to-org]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Composite identity — one row per (entity-type,
                     facet, from, to, applies-to-org) combination."}])

(def ^:private status-history-attrs
  [{:db/ident       :kontor.status-history/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The entity that transitioned. Generic ref —
                     could be an order, invoice, requirement, etc."}

   {:db/ident       :kontor.status-history/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalized copy of the entity's type so cross-
                     entity queries don't need to dispatch on the
                     ref's namespace."}

   {:db/ident       :kontor.status-history/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.status-history/from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "From-state; nil-sentinel for entity creation."}

   {:db/ident       :kontor.status-history/to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.status-history/changed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time of the transition: when, semantically,
                     the change applied. Distinct from datahike's
                     :db/txInstant which records when the datom was
                     written. ADR-008 bitemporality."}

   {:db/ident       :kontor.status-history/changed-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User who triggered the transition; ref to a
                     :kontor.audit/create-uid entity. Optional but recommended."}

   {:db/ident       :kontor.status-history/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Codified reason code (ADR-038). Auditor-friendly
                     vocabulary for compliance reports. Canonical
                     starter set documented in kontor.workflow.status-machine
                     ns; consumers extend with domain-specific codes."}

   {:db/ident       :kontor.status-history/reason-note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional free-text human story alongside the
                     codified :reason. Required when :reason is
                     :other. Where :reason answers 'what kind,'
                     :reason-note answers 'what specifically.'"}

   {:db/ident       :kontor.status-history/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :audit-doc — the proof an
                     auditor would ask for (customer email, credit
                     memo PDF, regulator clearance token, manager
                     override note). Kernel doesn't store bytes;
                     consumer attaches whatever artifact."}

   {:db/ident       :kontor.status-history/origin-transaction
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
;; rationale and `kontor.workflow.status-machine` for the enforcement code.
;; ============================================================================

(def ^:private audit-doc-attrs
  [{:db/ident       :kontor.audit-doc/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Consumer-supplied opaque identifier."}

   {:db/ident       :kontor.audit-doc/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":credit-memo | :customer-email | :vendor-email |
                     :uploaded-pdf | :wet-signature-pdf |
                     :regulator-clearance | :manager-override |
                     :compliance-attestation | … consumers extend."}

   {:db/ident       :kontor.audit-doc/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable label for the artifact."}

   {:db/ident       :kontor.audit-doc/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.audit-doc/content-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "SHA-256 of the artifact for integrity
                     verification. The kernel doesn't compute this;
                     consumer derives it at upload time."}

   {:db/ident       :kontor.audit-doc/storage-uri
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Where the consumer stores the artifact bytes
                     ('s3://...', 'file://...', 'https://...',
                     'ipfs://...'). Kernel is storage-agnostic."}

   {:db/ident       :kontor.audit-doc/uploaded-by-uid
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.audit-doc/uploaded-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   ;; ADR-051 — legal-privilege classification. Open-set keyword:
   ;; :none (default; nil treated as :none) | :attorney-client |
   ;; :work-product | :joint-defense | :settlement-communication |
   ;; :trade-secret | :pii-sensitive | <consumer extensions>.
   ;; This is the status-machine facet :kontor.audit-doc/privilege —
   ;; changes go through kontor.compliance.audit-doc/reclassify-privilege!
   ;; (waivers are ADR-038 approval-gated). The kernel TAGS; the
   ;; consumer's auth layer ENFORCES — there is no kernel ACL.
   {:db/ident       :kontor.audit-doc/privilege
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Legal-privilege classification (ADR-051).
                     Status-machine facet; nil = :none. Bitemporal:
                     (d/pull (d/valid-at db filing-date) [...] doc-eid)
                     answers 'privilege at filing date'."}

   ;; ADR-075 — subject-matter category, orthogonal to :kontor.audit-doc/
   ;; privilege. Open-set keyword: :none (default; nil treated as
   ;; :none) | :financial | :payroll | :hr-personnel | :hr-medical |
   ;; :hr-immigration | :tax-filing | :legal-proceeding |
   ;; :compliance-attestation | <consumer extensions>.
   ;; The two-axis design (privilege × category) is what lets a
   ;; consumer's auth layer express "HR role can access category
   ;; :payroll regardless of privilege" or "tax-prep contractor can
   ;; access category :tax-filing UNLESS privilege :attorney-client".
   ;; GDPR Art. 30 records-of-processing organize by subject-matter
   ;; "category of personal data" — this attr is the regulatory
   ;; schema's reflection in kontor.
   {:db/ident       :kontor.audit-doc/category
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Subject-matter category (ADR-075 + ADR-094).
                     Open-set; nil = :none. Orthogonal to
                     :kontor.audit-doc/privilege — legal-doctrine and domain
                     are independent axes. The consumer's auth layer
                     reads BOTH to make access decisions; the kernel
                     tags only. Canonical vocabulary (project-endorsed,
                     consumer-extensible) lives in
                     `kontor.compliance.audit-doc/canonical-categories`. Per
                     ADR-094, the project refuses to canonicalize
                     values facilitating AI-Act-banned use."}

   ;; ADR-078 — language/locale axis, orthogonal to category +
   ;; privilege. Open-set keyword: nil (default; treated as :en in
   ;; emit code) | :en | :fr | :bilingual | <consumer extensions>.
   ;; The three-axis design (privilege × category × language) is what
   ;; lets CRA T619 / Revenu Québec / pan-Canadian split-language
   ;; workforces route correctly. Per ADR-051's open-set pattern,
   ;; this is a non-breaking addition; DSAR / retention rules are
   ;; per-category, NOT per-language, so language stays independent.
   {:db/ident       :kontor.audit-doc/language
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Language / locale axis (ADR-078). Open-set;
                     nil treated as :en by emit code. CRA T619 takes
                     lang_cd E|F per submission; Revenu Québec RL-1
                     is FR by convention. Consumer-facing PDF
                     templates read this slot."}])

(def ^:private approval-policy-attrs
  [{:db/ident       :kontor.approval-policy/entity-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Which entity type this policy applies to.
                     Mirrors :kontor.status-transition/entity-type."}

   {:db/ident       :kontor.approval-policy/facet
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.approval-policy/transition-from
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.approval-policy/transition-to
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.approval-policy/applies-to-org
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional per-org scope (per ADR-031). Tenant-
                     wide when absent; org-specific overrides coexist
                     with the global."}

   {:db/ident       :kontor.approval-policy/rule
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":no-self-approval — recorded actor must differ
                     from :kontor.audit/create-uid of the entity.
                     :requires-supporting-doc — :supporting-doc must
                     be set in the change-spec.
                     :requires-non-empty-reason-note — :reason-note
                     required.
                     … future rules extend the vocabulary."}

   {:db/ident       :kontor.approval-policy/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.approval-policy/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.approval-policy/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.approval-policy/entity-type
                     :kontor.approval-policy/facet
                     :kontor.approval-policy/transition-from
                     :kontor.approval-policy/transition-to
                     :kontor.approval-policy/rule
                     :kontor.approval-policy/applies-to-org]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}])

;; ============================================================================
;; Invariant registration attrs (ADR-011)
;;
;; The data-driven datalog invariants the `kontor.invariant` engine
;; reads at write time. Two attrs only — the attribute keyword that
;; triggers each invariant and the EDN-string-encoded 4-source query.
;;
;; Lives in `schema.clj` (not `validation.clj` where the
;; `install-invariants!` ROW data lives) so `(kontor.schema/all)`
;; surfaces every kernel attr in one place — consumers introspecting
;; the schema (REPL, generators, doc tooling, the `schema-summary`
;; helper) see these alongside the rest of the kernel.
;; Per T-7 in note 160. install-invariants! still owns the
;; transacting of `:invariant/rule` ROWS — only the attribute
;; declarations move here.
;; ============================================================================

;; ============================================================================
;; ADR-113 — fiscal-unit substrate for tax-group consolidation (Gap #8 v1)
;;
;; The substrate for tax-group consolidation regimes (DE Organschaft, FR
;; intégration fiscale, US §1502, JP group-tsuusan, AT/AU/CN/MX/UK).
;; Two schema groups + a small extension to :transaction for the
;; tax-specific elimination kinds.
;;
;; Bitemporality: membership uses :joined-on / :left-on bitemporal
;; windows (entities can join + leave + rejoin); the :fiscal-unit itself
;; is bitemporal via :elected-from / :elected-until.
;;
;; Many-to-many membership: an :entity can participate in MULTIPLE
;; fiscal units simultaneously (one for income-tax grouping under KStG
;; §14, one for VAT grouping under UStG §2 — though VAT grouping uses
;; the separate :kontor.vat-group/* namespace, NOT :kontor.fiscal-unit).
;; Note 166 §1.9 + note 167 §2.2.
;; ============================================================================

(def ^:private fiscal-unit-attrs
  [{:db/ident       :kontor.fiscal-unit/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable external identifier for the fiscal-unit
                     election, e.g. \"DE-HansTech-Organschaft-2026\".
                     ADR-113."}

   {:db/ident       :kontor.fiscal-unit/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable name for the fiscal-unit. ADR-113."}

   {:db/ident       :kontor.fiscal-unit/parent-entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the parent/Organträger/tête de
                     groupe/common parent that files the consolidated
                     return on behalf of the unit. ADR-113."}

   {:db/ident       :kontor.fiscal-unit/regime
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Closed enum (per-ADR addendum to extend):
                     :de-organschaft | :fr-integration | :us-1502 |
                     :jp-group-tsuusan | :uk-group-relief |
                     :at-gruppenbesteuerung | :au-tcr | :cn-ccsv |
                     :mx-rigs. The 9-regime starter set.
                     ADR-113."}

   {:db/ident       :kontor.fiscal-unit/computation-style
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Closed enum:
                     :single-base — group treated as one taxpayer
                       (DE/FR/US/AT/AU/CN/MX). Provider runs once on
                       the parent with :fiscal-unit in ctx.
                     :per-member-with-netting — each member computes
                       its own base, then loss-allocation netting
                       (JP group-tsuusan post-2022).
                     :loss-surrender — independent computation + UK-
                       style loss-claim transfer (UK group-relief).
                     ADR-113."}

   {:db/ident       :kontor.fiscal-unit/elected-from
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "First day the election is effective. ADR-113."}

   {:db/ident       :kontor.fiscal-unit/elected-until
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last day the election is effective (inclusive).
                     Nil = open-ended (most jurisdictions). ADR-113."}

   {:db/ident       :kontor.fiscal-unit/minimum-term-ends
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Earliest date the election may be voluntarily
                     terminated. DE EAV requires a 5-year minimum
                     under KStG §14 Abs. 1 Nr. 3. Nil for jurisdictions
                     without a minimum term (US §1502 is permanent
                     until exit; FR is renewable 5-year cycles).
                     ADR-113."}

   {:db/ident       :kontor.fiscal-unit/active
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Boolean — convenience denorm of the status. True
                     iff status is in #{:active}. ADR-113."}

   {:db/ident       :kontor.fiscal-unit/anchor-document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the election document
                     (DE EAV / FR Convention d'intégration / US Form
                     1122 / JP application etc.). ADR-113."}

   {:db/ident       :kontor.fiscal-unit/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Status keyword on the ADR-034 status-machine:
                     :proposed → :elected → :active → :exiting →
                     :exited | :voided-retro. The :voided-retro
                     transition supports the DE retroactive-break case
                     (tax authority retroactively voids the
                     Organschaft, requires bitemporal restatement).
                     ADR-113."}])

(def ^:private fiscal-unit-member-attrs
  [{:db/ident       :kontor.fiscal-unit-member/fiscal-unit
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :kontor.fiscal-unit. ADR-113."}

   {:db/ident       :kontor.fiscal-unit-member/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :entity — the member entity participating
                     in this fiscal-unit. Note: an entity can be a
                     member of multiple fiscal-units simultaneously
                     (income-tax vs trade-tax grouping). ADR-113."}

   {:db/ident       :kontor.fiscal-unit-member/role
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":parent | :sub. The :parent member's :entity must
                     equal the unit's :parent-entity. ADR-113."}

   {:db/ident       :kontor.fiscal-unit-member/ownership-fraction
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Parent's economic ownership of the sub. 1.0M for
                     wholly-owned; lower for partial. DE Organschaft
                     requires >50% (financial integration); US §1504
                     requires ≥80% by vote and value. Substrate doesn't
                     enforce minimums — that's per-regime :provision
                     gating. ADR-113."}

   {:db/ident       :kontor.fiscal-unit-member/joined-on
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "First day the member is part of the unit. ADR-113."}

   {:db/ident       :kontor.fiscal-unit-member/left-on
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Last day the member was part of the unit
                     (inclusive). Nil = still a member. ADR-113."}])

(def ^:private tax-elimination-attrs
  ;; Extends the existing :kontor.transaction/consolidation-kind
  ;; (schema.clj line ~3805) with tax-specific sub-classifications +
  ;; the deferral-lifecycle helpers note 167 §2.3 + ADR-113 needs.
  ;; The existing :consolidation-kind is unchanged (:translation /
  ;; :elimination axis remains the high-level kind); the new
  ;; :elimination-style refines the :elimination case with
  ;; :tax-elimination | :tax-deferral | :tax-neutralisation.
  [{:db/ident       :kontor.transaction/elimination-style
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Closed enum (per-ADR addendum to extend):
                     :tax-elimination — permanent removal at the group
                       level (FR Art. 223 B dividendes intégrés;
                       DE EAV profit attribution).
                     :tax-deferral — deferred until externalisation
                       (US §1502-13 deferred-and-matching; FR Art.
                       223 F déneutralisation until disposal).
                     :tax-neutralisation — rate-reduced at the group
                       level (FR Art. 223 B quote-part 5% → 1%;
                       DE §15 KStG Bruttomethode adjustment).
                     ADR-113."}

   {:db/ident       :kontor.transaction/elimination-reversal-trigger
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "What event triggers the reversal of a
                     :tax-deferral elimination. :externalisation |
                     :regime-exit | :asset-disposal | :member-exit.
                     Used by the deferral-crystallisation walk
                     (`kontor.tax.fiscal-unit/crystallise-deferrals`,
                     stub in v1; full impl in v1.1 with US §1502-13).
                     ADR-113."}

   {:db/ident       :kontor.transaction/elimination-components
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to other :transaction entries that this
                     elimination depends on — the underlying deferred
                     items being held. Used to walk the crystallisation
                     chain when a :reversal-trigger fires. ADR-113."}])

(def ^:private invariant-attrs
  [{:db/ident       :invariant/rule
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Attribute keyword that triggers this invariant
                     when present in a tx (e.g. :kontor.posting/account).
                     Identity attribute — one query per rule keyword."}
   {:db/ident       :invariant/query
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string of the datalog query; see
                     `invariant.query/assert-valid-query` for shape
                     constraints (must take 4 sources)."}])

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
    fx-rate-attrs                        ; ADR-072
    lot-attrs
    account-attrs
    account-code-attrs                   ; ADR-019
    document-type-attrs                  ; ADR-020
    account-tag-attrs
    journal-attrs
    partner-attrs
    person-attrs                         ; kernel-shared :kontor.person/*
    fiscal-position-attrs
    fiscal-position-tax-attrs
    fiscal-position-account-attrs
    tax-attrs
    tax-rep-attrs
    tax-group-attrs
    tax-application-attrs                ; ADR-016
    period-attrs
    balance-assertion-attrs
    transaction-attrs                    ; +pending-attestation + clearance-token (ADR-018)
    posting-attrs                        ; +tax-breakdown + clearance-token
    posting-dimension-attrs              ; ADR-097
    payment-term-attrs
    payment-term-line-attrs
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
    intercompany-pair-attrs              ; ADR-073
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
    partner-tag-attrs                     ; ADR-039
    partner-tax-id-attrs                  ; ADR-040
    side-effect-intent-attrs              ; ADR-041
    cross-tx-attrs                        ; ADR-074
    account-type-direction-attrs          ; ADR-041
    payment-application-attrs             ; ADR-043
    posting-reconcile-attrs               ; note 198 Tier 2 (kontor.banking.line-reconcile)
    partial-reconcile-attrs               ; note 198 Tier 2
    payment-attrs                         ; note 198 Tier 2 (kontor.banking.payment)
    batch-payment-attrs                   ; note 198 Tier 2
    ;; ADR-048 valid-time attrs are now upstream (:db.valid/from + :db.valid/to,
    ;; pre-installed by datahike's feature/bitemporal-v1)
    legal-hold-attrs                      ; ADR-049 (kontor.compliance.legal-hold)
    retention-policy-attrs                ; ADR-050 (kontor.compliance.retention)
    dsar-request-attrs                    ; ADR-052 (kontor.compliance.dsar)
    tax-concept-attrs                     ; ADR-101 (kontor.tax.statute)
    provision-attrs                       ; ADR-101 (kontor.tax.statute)
    regime-attrs                          ; ADR-101 (kontor.tax.statute)
    parameter-attrs                       ; ADR-101 (kontor.tax.statute)
    fiscal-unit-attrs                     ; ADR-113 (kontor.tax.fiscal-unit)
    fiscal-unit-member-attrs              ; ADR-113 (kontor.tax.fiscal-unit)
    tax-elimination-attrs                 ; ADR-113 (extends :kontor.transaction/*)
    invariant-attrs)))                    ; ADR-011 (kontor.invariant, T-7 of note 160)

(defn install!
  "Transact the kernel schema into a connection. Idempotent — re-running
   on a connection that already has the schema produces empty ops on
   each unchanged ident.

   Returns the resulting tx-report."
  [conn]
  (d/transact conn all))

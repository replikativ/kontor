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
                     declarative report engine)."}])

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
                     specific; validation lives in l10n modules."}])

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
    :db/doc         ":draft | :posted | :cancelled. Lifecycle."}

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
                     :normal)."}])

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
    account-tag-attrs
    journal-attrs
    partner-attrs
    fiscal-position-attrs
    tax-attrs
    tax-rep-attrs
    tax-group-attrs
    period-attrs
    balance-assertion-attrs
    transaction-attrs
    posting-attrs
    payment-term-attrs
    invoice-attrs
    invoice-line-attrs
    bank-line-attrs
    analytic-plan-attrs
    analytic-account-attrs
    analytic-distribution-attrs
    posting-analytic-attrs)))

(defn install!
  "Transact the kernel schema into a connection. Idempotent — re-running
   on a connection that already has the schema produces empty ops on
   each unchanged ident.

   Returns the resulting tx-report."
  [conn]
  (d/transact conn all))

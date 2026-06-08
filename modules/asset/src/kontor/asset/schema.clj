(ns kontor.asset.schema
  "kontor-asset companion schema — ADR-053 + ADR-054.

   Entities (ADR-053 — the register + lifecycle):
     :asset         — one physical capitalised asset
     :asset-class   — the category (l10n ships the rows; e.g. a DE
                      class maps to an AfA-Tabelle row, a US class
                      to a MACRS recovery class)
     :asset-event   — an append-only mid-life-event fact (disposal,
                      impairment, revaluation, useful-life revision,
                      addition, transfer). The lifecycle transactors
                      only ever CREATE :asset-event entities — never
                      retract or edit one — so the audit trail is
                      append-only BY CONVENTION. It is not
                      sealing-enforced (sealing, ADR-007, guards
                      :posting entities; wiring a companion entity
                      into kernel sealing would breach the kernel's
                      anti-accretion contract). A consumer that needs
                      a hard guarantee layers its own seal.

   Entities (ADR-054 — the depreciation books):
     :asset-depreciation   — the per-(asset, ledger) depreciation
                             book; the 'depreciation area' IS a
                             :ledger (ADR-021)
     :asset-method-params  — the small heterogeneous DepreciationProvider
                             config entity (1:1 component of a book)

   State machine (per ADR-034):
     :kontor.asset/status  — :planned → :in-service → :fully-depreciated
                      / :disposed / :transferred

   ADR-053 is GL-free (the data model + lifecycle + governance).
   ADR-054 adds the per-(asset, ledger) depreciation books, book
   management, and the GL posting builders. The DepreciationProvider
   protocol + the runner are ADR-055.

   Componentisation is `:kontor.asset/parent` self-reference — a component
   is just an :asset whose parent points at the whole (IAS 16); no
   separate :asset-component entity.

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :asset
;; ============================================================================

(def ^:private asset-attrs
  [{:db/ident       :kontor.asset/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'MACH-001', 'VEH-2026-07'."}

   {:db/ident       :kontor.asset/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset/class
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :asset-class — the category carrying the
                     jurisdiction defaults (AfA-Tabelle / MACRS
                     recovery class)."}

   {:db/ident       :kontor.asset/acquisition-cost
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The single acquisition cost ALL depreciation
                     books share (ADR-054)."}

   {:db/ident       :kontor.asset/acquisition-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset/acquisition-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the asset was acquired. DRIVES effective-
                     dated depreciation-rule resolution (ADR-055):
                     the rule governing an asset is fixed at
                     acquisition for its whole life."}

   {:db/ident       :kontor.asset/in-service-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the depreciation clock starts — may differ
                     from :acquisition-date (DE: 'Anschaffung' vs
                     'betriebsbereit'; US/CA: 'placed in service' /
                     'available for use')."}

   {:db/ident       :kontor.asset/salvage-value
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Residual value. IAS 16: reviewed annually — may
                     change via an :asset-event :useful-life-revision.
                     Often 0."}

   {:db/ident       :kontor.asset/asset-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "BS account carrying gross cost. Used by ADR-054's
                     posting helpers."}

   {:db/ident       :kontor.asset/accumulated-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Contra-asset — kumulierte AfA / accumulated
                     depreciation."}

   {:db/ident       :kontor.asset/expense-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Depreciation-expense account (P&L)."}

   {:db/ident       :kontor.asset/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :analytic-account — uses the
                     bootstrapped 'cost-center' plan (ADR-032)."}

   {:db/ident       :kontor.asset/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional legal-entity scope (ADR-031)."}

   {:db/ident       :kontor.asset/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Componentisation (IAS 16): the 'whole' this
                     component rolls up to. A component is just an
                     :asset whose :parent points at the whole —
                     independent depreciation books, shared identity
                     for disposal. Optional."}

   {:db/ident       :kontor.asset/origin-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The capitalisation GL entry (ref to
                     :transaction). Caller-supplied in ADR-053;
                     ADR-054's posting helpers build it."}

   {:db/ident       :kontor.asset/origin-document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the acquisition invoice /
                     contract / board resolution (ADR-038)."}

   {:db/ident       :kontor.asset/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle facet.
                     #{:planned :in-service :fully-depreciated
                       :disposed :transferred}."}

   {:db/ident       :kontor.asset/serial-number
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset/location
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-class
;; ============================================================================

(def ^:private asset-class-attrs
  [{:db/ident       :kontor.asset-class/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'machinery',
                     'office-equipment', 'buildings-commercial'."}

   {:db/ident       :kontor.asset-class/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset-class/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Class hierarchy parent. Optional."}

   {:db/ident       :kontor.asset-class/default-useful-life-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Default useful life; overridable per
                     :asset-depreciation book (ADR-054)."}

   {:db/ident       :kontor.asset-class/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-event
;; ============================================================================

(def ^:private asset-event-attrs
  [{:db/ident       :kontor.asset-event/asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset-event/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Wired: #{:disposal :impairment :revaluation
                       :useful-life-revision :addition :transfer}.
                     RESERVED (no transactor / posting builder /
                     re-plan path yet — a documented follow-up;
                     review-after market-pain): :partial-disposal.
                     The attr is an open keyword — a consumer may
                     transact other kinds, but only the wired set is
                     understood by the runner / roll-forward."}

   {:db/ident       :kontor.asset-event/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Valid-time of the event."}

   {:db/ident       :kontor.asset-event/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Impairment loss / revaluation delta / disposal
                     proceeds / addition cost — interpretation
                     depends on :kind."}

   {:db/ident       :kontor.asset-event/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset-event/new-useful-life-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "For :kind :useful-life-revision — the revised
                     remaining useful life."}

   {:db/ident       :kontor.asset-event/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The GL entry this event posted (ref to
                     :transaction). Caller-supplied in ADR-053;
                     ADR-054's posting helpers build it."}

   {:db/ident       :kontor.asset-event/justification
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the impairment-test memo,
                     disposal authorisation, or valuation report.
                     Required (inline guard) for :impairment /
                     :revaluation / :disposal events."}

   {:db/ident       :kontor.asset-event/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-depreciation — the per-(asset, ledger) book (ADR-054)
;; ============================================================================

(def ^:private asset-depreciation-attrs
  [{:db/ident       :kontor.asset-depreciation/asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :asset this book depreciates."}

   {:db/ident       :kontor.asset-depreciation/ledger
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The :ledger this book posts to (ADR-021). The
                     'depreciation area' IS a ledger — the HGB book
                     and the Steuerbilanz book are two ledgers."}

   {:db/ident       :kontor.asset-depreciation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.asset-depreciation/asset :kontor.asset-depreciation/ledger]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One depreciation book per (asset, ledger). Both
                     tuple members are always present, so no
                     nil-in-tuple non-idempotency caveat."}

   {:db/ident       :kontor.asset-depreciation/provider-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Which DepreciationProvider computes this book's
                     schedule (:straight-line, :declining-balance,
                     :sum-of-years-digits, :units-of-production,
                     :macrs, :afa-degressive, …). ADR-055."}

   {:db/ident       :kontor.asset-depreciation/method-params
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to an :asset-method-params entity
                     carrying provider config (rate multiple, % ceiling,
                     table key, …)."}

   {:db/ident       :kontor.asset-depreciation/useful-life-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "This book's useful life in months. An HGB life
                     and an AfA-Tabelle life commonly differ — that
                     is the whole point of parallel books."}

   {:db/ident       :kontor.asset-depreciation/convention
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":full | :half-year | :mid-quarter | :mid-month
                     | :zeitanteilig — the first/last-period
                     proration convention."}

   {:db/ident       :kontor.asset-depreciation/expense-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional per-book override of the asset's
                     `:kontor.asset/expense-account` — the P&L account the
                     depreciation charge debits FOR THIS book. Added
                     for kontor-lease (ADR-063): a Right-of-Use asset
                     that is a *finance* lease under IFRS but an
                     *operating* lease under US-GAAP debits
                     depreciation-expense on the IFRS ledger and the
                     single lease-expense account on the US-GAAP
                     ledger. Absent ⇒ the asset's `:expense-account`."}

   {:db/ident       :kontor.asset-depreciation/depreciable-base
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The amount spread over the schedule — usually
                     acquisition-cost − salvage-value, but may differ
                     per book (a tax bonus reduces the tax base). For
                     a mid-life import this is the REMAINING base."}

   {:db/ident       :kontor.asset-depreciation/opening-accumulated
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Depreciation accumulated BEFORE this book's
                     :schedule started — the mid-life-import case (an
                     asset 3 years into its life on day one). A pure
                     reporting scalar: `accumulated-depreciation` and
                     `net-book-value` add it to the occurrence sum;
                     the DepreciationProvider never sees it (it plans
                     only the schedule's own occurrences against the
                     REMAINING :depreciable-base). Optional; absent
                     means a fresh book."}

   {:db/ident       :kontor.asset-depreciation/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.asset-depreciation/start-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The depreciation clock start for this book.
                     Defaults to the asset's :in-service-date."}

   {:db/ident       :kontor.asset-depreciation/schedule
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the ADR-032 :schedule the runner fires.
                     :kontor.schedule/kind :depreciation, :origin-entity →
                     this book."}

   {:db/ident       :kontor.asset-depreciation/effective-rule
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to the l10n-owned effective-dated
                     depreciation-rule row resolved at book creation
                     (ADR-055 §effective-dating, ADR-026 pattern).
                     l10n owns the rule rows; the companion only
                     stores the pinned ref."}

   {:db/ident       :kontor.asset-depreciation/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :asset-method-params — small heterogeneous provider-config entity (ADR-054)
;; ============================================================================

(def ^:private asset-method-params-attrs
  [{:db/ident       :kontor.asset-method-params/rate-multiple
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Declining-balance: the rate multiple applied to
                     the straight-line rate (1.5×, 2×, 2.5×)."}

   {:db/ident       :kontor.asset-method-params/ceiling-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Declining-balance: an absolute %-of-base ceiling
                     on the annual rate (e.g. 0.25M for the DE
                     2020-22 degressive-AfA window)."}

   {:db/ident       :kontor.asset-method-params/switch-to-straight-line
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Declining-balance: switch to straight-line in
                     the first year SL ≥ DB (the standard
                     optimisation; §7 Abs. 2 EStG permits it)."}

   {:db/ident       :kontor.asset-method-params/total-units
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Units-of-production: the asset's lifetime unit
                     count (the denominator of the per-unit rate)."}

   {:db/ident       :kontor.asset-method-params/table-key
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "A keyword a table-driven l10n provider (MACRS,
                     AfA-Tabelle) keys its percentage table on."}

   {:db/ident       :kontor.asset-method-params/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat asset-attrs asset-class-attrs asset-event-attrs
               asset-depreciation-attrs asset-method-params-attrs)))

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034 / ADR-038)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :kontor.asset/status lifecycle."
  [{:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :nil :kontor.status-transition/to :planned
    :kontor.status-transition/active true :kontor.status-transition/name "Acquire (Planned)"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :nil :kontor.status-transition/to :in-service
    :kontor.status-transition/active true :kontor.status-transition/name "Acquire In-Service"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :planned :kontor.status-transition/to :in-service
    :kontor.status-transition/active true :kontor.status-transition/name "Place In Service"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :in-service :kontor.status-transition/to :fully-depreciated
    :kontor.status-transition/active true :kontor.status-transition/name "Fully Depreciated"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :in-service :kontor.status-transition/to :disposed
    :kontor.status-transition/active true :kontor.status-transition/name "Dispose"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :fully-depreciated :kontor.status-transition/to :disposed
    :kontor.status-transition/active true :kontor.status-transition/name "Scrap (Fully Depreciated)"}
   {:kontor.status-transition/entity-type :asset
    :kontor.status-transition/facet :kontor.asset/status
    :kontor.status-transition/from :in-service :kontor.status-transition/to :transferred
    :kontor.status-transition/active true :kontor.status-transition/name "Transfer To Another Entity"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Disposal is the consequential
   status transition — it ends the asset's life and triggers the
   gain/loss recognition (ADR-054). It requires the disposal
   authorisation document and separation of duties."
  [{:kontor.approval-policy/entity-type     :asset
    :kontor.approval-policy/facet           :kontor.asset/status
    :kontor.approval-policy/transition-from :in-service
    :kontor.approval-policy/transition-to   :disposed
    :kontor.approval-policy/rule            :requires-supporting-doc
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :asset
    :kontor.approval-policy/facet           :kontor.asset/status
    :kontor.approval-policy/transition-from :in-service
    :kontor.approval-policy/transition-to   :disposed
    :kontor.approval-policy/rule            :no-self-approval
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :asset
    :kontor.approval-policy/facet           :kontor.asset/status
    :kontor.approval-policy/transition-from :fully-depreciated
    :kontor.approval-policy/transition-to   :disposed
    :kontor.approval-policy/rule            :requires-supporting-doc
    :kontor.approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-asset schema + status-transition + approval-
   policy seeds. Idempotent for the schema attrs; the seeds are
   guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat).

   Run after kontor.core/install-schema! — kontor-asset references
   kernel attrs (:account, :commodity, :analytic-account, :entity,
   :transaction, :audit-doc)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :kontor.status-transition/entity-type :asset]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds)))))
  conn)

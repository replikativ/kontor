(ns kontor.lease.schema
  "kontor-lease companion schema — ADR-062 (the :lease contract +
   the lifecycle).

   Lessee-side lease accounting under IFRS 16 and ASC 842. A thin
   companion — the substrate was built for it:
     - the Right-of-Use asset IS an :asset (`:kontor.asset/class` a ROU
       class) — reuse kontor-asset whole; no `:rou-asset` entity.
     - the liability unwind + ROU depreciation are each a :schedule
       (ADR-032).
     - IFRS-16 / ASC-842 / local-GAAP books are each a :ledger
       (ADR-021) — classification is per-(lease, ledger), so it
       lives on the :lease-liability book (ADR-063), NOT here.

   :lease carries framework-NEUTRAL contract facts only. The
   :lease-liability per-(lease, ledger) book + the LeaseProvider +
   the operating-lease ROU plug + the full `commence!` transactor
   are ADR-063; :lease-modification + remeasurements + variable
   payments + FX are ADR-064.

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :lease — the contract master (framework-neutral facts)
;; ============================================================================

(def ^:private lease-attrs
  [{:db/ident       :kontor.lease/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'LSE-2026-014'."}

   {:db/ident       :kontor.lease/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lease/lessor
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :partner — the counterparty (lessor)."}

   {:db/ident       :kontor.lease/underlying-asset-desc
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "What is leased — free text (a property, a
                     vehicle fleet, equipment)."}

   {:db/ident       :kontor.lease/asset-class
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :asset-class — the ROU :asset-class the
                     commencement transactor uses when it `acquire!`s
                     the Right-of-Use :asset (ADR-063)."}

   {:db/ident       :kontor.lease/commencement-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the lessee gets the right to use the asset
                     — the valid-time anchor; the ROU asset's
                     :acquisition-date + :in-service-date."}

   {:db/ident       :kontor.lease/term-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The lease term AS ASSESSED — the renewal /
                     termination-option judgement (IFRS 16.18-19 /
                     ASC 842-10-30-1 'reasonably certain') already
                     folded in by the consumer. A change in that
                     assessment is a :lease-modification (ADR-064)."}

   {:db/ident       :kontor.lease/payment-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The periodic fixed (or in-substance-fixed)
                     payment. An index-linked payment uses the index
                     at commencement; a later index change is a
                     :lease-modification :index-reset (ADR-064)."}

   {:db/ident       :kontor.lease/payment-frequency
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":monthly | :quarterly | :annual — the :schedule
                     frequency for both the liability and ROU books."}

   {:db/ident       :kontor.lease/payment-timing
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":in-advance (annuity-due — payment at the start
                     of the period) | :in-arrears (ordinary annuity).
                     Affects period-1 interest in the unwind."}

   {:db/ident       :kontor.lease/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lease/discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The annual rate pinned at commencement — the
                     rate implicit in the lease, or (usually) the
                     lessee's incremental borrowing rate. NOT
                     kernel-computed: a consumer input, like a tax
                     rate. Re-discounted only on a :term-change /
                     :rate-reset modification (ADR-064)."}

   {:db/ident       :kontor.lease/initial-direct-costs
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Costs capitalised INTO the ROU asset cost.
                     Optional."}

   {:db/ident       :kontor.lease/prepaid-at-commencement
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Payments made at or before commencement — added
                     to the ROU asset cost. Optional."}

   {:db/ident       :kontor.lease/incentives-received
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Lease incentives received — REDUCE the ROU asset
                     cost. Optional."}

   {:db/ident       :kontor.lease/purchase-option-price
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "If the lessee is reasonably certain to exercise
                     a purchase option — included in the liability.
                     Optional."}

   {:db/ident       :kontor.lease/rou-asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the Right-of-Use :asset — created by
                     `commence!` (ADR-063) via
                     `kontor.asset.asset/acquire!`. One ROU :asset
                     per lease; its per-ledger depreciation books
                     are `:asset-depreciation`."}

   {:db/ident       :kontor.lease/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional legal-entity scope (ADR-031)."}

   {:db/ident       :kontor.lease/origin-document
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the signed lease contract.
                     Required (approval policy) for :draft → :active."}

   {:db/ident       :kontor.lease/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle facet.
                     #{:draft :active :expired :terminated :purchased}."}

   {:db/ident       :kontor.lease/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; ADR-069 mid-life-import audit denorms
   {:db/ident       :kontor.lease/imported?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True iff this :lease entered :active via
                     `kontor.lease.runner/import-lease!` rather than
                     `commence!` — i.e. the lease was already mid-term
                     in a prior system and is being onboarded with
                     carried-forward balance-sheet amounts.

                     ADR-069. The accompanying audit denorms
                     :kontor.lease/imported-as-of,
                     :kontor.lease/imported-original-commencement-date and
                     :kontor.lease/imported-original-term-months document
                     the contractual history that
                     :kontor.lease/commencement-date + :kontor.lease/term-months
                     do NOT carry (those re-anchor on the import
                     date for the new system's schedules)."}

   {:db/ident       :kontor.lease/imported-as-of
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The date `import-lease!` re-anchored this lease
                     in the new system — equals
                     :kontor.lease/commencement-date for an imported lease.
                     ADR-069."}

   {:db/ident       :kontor.lease/imported-original-commencement-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "The lease's contractual commencement date,
                     PRESERVED across the import — distinct from
                     :kontor.lease/commencement-date which re-anchors on
                     the import date. ADR-069."}

   {:db/ident       :kontor.lease/imported-original-term-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The lease's contractual total term in months,
                     PRESERVED across the import — distinct from
                     :kontor.lease/term-months which re-anchors to the
                     REMAINING term. ADR-069."}])

;; ============================================================================
;; :lease-liability — the per-(lease, ledger) liability book (ADR-063)
;; ============================================================================
;;
;; Sibling of :asset-depreciation. THIS carries the per-ledger
;; classification — the same lease is :finance on the IFRS ledger and
;; effectively off-balance on an HGB ledger, so classification is
;; per-(lease, ledger), not per-:lease.

(def ^:private lease-liability-attrs
  [{:db/ident       :kontor.lease-liability/lease
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lease-liability/ledger
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The framework book (ADR-021) this liability is
                     measured on — :ledger \"ifrs\", \"us-gaap\", …"}

   {:db/ident       :kontor.lease-liability/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:kontor.lease-liability/lease :kontor.lease-liability/ledger]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One liability book per (lease, ledger). Both
                     tuple members always present — no nil caveat."}

   {:db/ident       :kontor.lease-liability/classification
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:finance :operating}. PER-(lease, ledger): the
                     same lease is :finance on the IFRS ledger and
                     :operating on a US-GAAP ledger. A short-term /
                     low-value exempt lease gets NO :lease-liability
                     book at all (the exemption path)."}

   {:db/ident       :kontor.lease-liability/provider-id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Which LeaseProvider computes this book's
                     amortization — :effective-interest (the
                     built-in)."}

   {:db/ident       :kontor.lease-liability/opening-liability
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The liability measurement this book unwinds from
                     — the PV of the lease payments at commencement,
                     or (post-modification, ADR-064) the remeasured
                     balance. The IFRS book and a local-GAAP book CAN
                     differ here."}

   {:db/ident       :kontor.lease-liability/discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The annual rate this book discounts at — usually
                     the lease's, but a parallel book could differ.
                     Re-discounted only on a :term-change / :rate-reset
                     modification (ADR-064)."}

   {:db/ident       :kontor.lease-liability/liability-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The BS lease-liability account this book posts
                     against."}

   {:db/ident       :kontor.lease-liability/interest-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The P&L account the interest leg of each payment
                     debits. For a :finance book this is an
                     interest-expense account; for an :operating book
                     `commence!` sets it to the single
                     lease-expense account, so the P&L shows one
                     lease-expense line (interest + the ROU plug both
                     land there = the straight-line expense)."}

   {:db/ident       :kontor.lease-liability/opening-fired-through
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "How many schedule occurrences were already fired
                     before this book's current `:opening-liability`
                     applied — 0 at commencement; set by a
                     modification's re-plan (ADR-064) or a mid-life
                     import. The LeaseProvider computes the un-fired
                     tail from period (opening-fired-through + 1)."}

   {:db/ident       :kontor.lease-liability/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lease-liability/schedule
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the ADR-032 :schedule the lease runner
                     fires. :kontor.schedule/kind :lease-liability,
                     :origin-entity → this book."}

   {:db/ident       :kontor.lease-liability/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; ADR-070 disclosure-support — discount-rate audit trail
   {:db/ident       :kontor.lease-liability/rate-rationale
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the justification for this
                     book's :discount-rate (an appraiser's IBR memo,
                     a treasury rate-board record, etc.).
                     Optional but encouraged on every book —
                     under IFRS 16 §27 / ASC 842-20-30-3 the
                     discount rate IS a key input and auditors will
                     want to trace it. ADR-070 / note 40 §4."}])

;; ============================================================================
;; :lease-modification — the append-only modification event (ADR-064)
;; ============================================================================
;;
;; Sibling of :asset-event. A modification — an index reset, a term
;; change, a rate reset, a partial or full termination, a purchase —
;; is an append-only fact: the transactors here only ever CREATE one,
;; never retract or edit. The :lease contract facts ARE mutated (a
;; modification IS a change to the contract), but every change is
;; documented by a :lease-modification.

(def ^:private lease-modification-attrs
  [{:db/ident       :kontor.lease-modification/lease
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.lease-modification/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:remeasurement :index-reset :term-change
                     :rate-reset :partial-termination :termination
                     :purchase}. :index-reset / :term-change /
                     :rate-reset all route through `remeasure!` — the
                     keyword records intent; the math is the same
                     re-measure-and-adjust-ROU."}

   {:db/ident       :kontor.lease-modification/date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Effective date of the modification — the
                     valid-time anchor and the re-anchor point for the
                     liability books."}

   {:db/ident       :kontor.lease-modification/new-payment-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The revised periodic payment. An index-linked
                     payment change is just this — the consumer
                     supplies the new amount from the new index."}

   {:db/ident       :kontor.lease-modification/new-term-months
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "The revised lease term."}

   {:db/ident       :kontor.lease-modification/new-discount-rate
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "The revised discount rate — IFRS 16 re-discounts
                     on a term or scope change."}

   {:db/ident       :kontor.lease-modification/scope-decrease-pct
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Partial termination only — the fraction of the
                     right-of-use given up (0 < pct < 1). The
                     liability + the ROU asset are reduced
                     proportionally; the difference is a P&L
                     gain/loss (the proportional approach)."}

   {:db/ident       :kontor.lease-modification/justification
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the modification / termination
                     agreement."}

   {:db/ident       :kontor.lease-modification/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to the GL adjustment :transaction(s) this
                     modification produced — one per affected ledger
                     book."}

   {:db/ident       :kontor.lease-modification/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; ADR-070 disclosure-support — persisted aggregated deltas
   {:db/ident       :kontor.lease-modification/liability-delta
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net change in :kontor.lease-liability/opening-liability
                     this modification caused, AGGREGATED across all
                     affected per-(lease,ledger) books. Positive =
                     liability increased (e.g. term extension);
                     negative = liability decreased (e.g. partial
                     termination). Persisted as a convenience for
                     the IFRS 16 / ASC 842 lease-liability
                     roll-forward disclosure (note 40 §2). The data
                     is derivable from the per-book before/after
                     `:opening-liability` values; this denorm makes
                     the disclosure a trivial read. ADR-070."}

   {:db/ident       :kontor.lease-modification/rou-delta
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net change in ROU :kontor.asset-depreciation/depreciable-
                     base aggregated across all affected books. The
                     ROU side counterpart to :liability-delta — paired
                     with it for the IFRS 16 ROU roll-forward
                     disclosure. ADR-070."}

   {:db/ident       :kontor.lease-modification/pnl-delta
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Net P&L impact of this modification (the plug
                     between :liability-delta and :rou-delta). For
                     a partial termination this is the IFRS 16 §46(b)
                     gain/loss; for a scope-decrease modification it
                     is the proportional re-measurement gain. Zero
                     for a remeasurement that flows entirely to BS.

                     Sign convention (ADR-070 §sign-convention table):
                     positive = the modification ADDS to net income
                     (a derecognition gain, a scope-decrease pickup);
                     negative = the modification REDUCES net income
                     (a derecognition loss, an IFRS 16.39 floor plug)."}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat lease-attrs lease-liability-attrs lease-modification-attrs)))

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034 / ADR-038)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :kontor.lease/status lifecycle.
   `:draft` is the recorded-but-not-commenced state — `define-lease!`
   creates the lease at `:draft`; ADR-063's `commence!` does the
   balance-sheet recognition (`:draft → :active`)."
  (vec
   (for [[from to name]
         [[:nil     :draft       "Record (draft)"]
          [:draft   :active      "Commence (balance-sheet recognition)"]
          [:active  :expired     "Expire (end of term)"]
          [:active  :terminated  "Terminate early"]
          [:active  :purchased   "Purchase option exercised"]]]
     {:kontor.status-transition/entity-type :lease
      :kontor.status-transition/facet :kontor.lease/status
      :kontor.status-transition/from from
      :kontor.status-transition/to to
      :kontor.status-transition/active true
      :kontor.status-transition/name name})))

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Commencement (`:draft → :active`)
   requires the signed lease contract; early termination requires
   the termination agreement + separation of duties."
  [{:kontor.approval-policy/entity-type     :lease
    :kontor.approval-policy/facet           :kontor.lease/status
    :kontor.approval-policy/transition-from :draft
    :kontor.approval-policy/transition-to   :active
    :kontor.approval-policy/rule            :requires-supporting-doc
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :lease
    :kontor.approval-policy/facet           :kontor.lease/status
    :kontor.approval-policy/transition-from :active
    :kontor.approval-policy/transition-to   :terminated
    :kontor.approval-policy/rule            :requires-supporting-doc
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :lease
    :kontor.approval-policy/facet           :kontor.lease/status
    :kontor.approval-policy/transition-from :active
    :kontor.approval-policy/transition-to   :terminated
    :kontor.approval-policy/rule            :no-self-approval
    :kontor.approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-lease schema + status-transition + approval-
   policy seeds. Idempotent for the schema attrs; the seeds are
   guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat).

   Run after kontor.core/install-schema! AND
   kontor.asset.schema/install! — kontor-lease references kernel
   attrs (:partner, :commodity, :ledger, :account, :entity,
   :audit-doc, :transaction, :status-transition) and kontor-asset
   attrs (:asset, :asset-class, :asset-depreciation)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :kontor.status-transition/entity-type :lease]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

(ns kontor.disposal.schema
  "kontor-disposal companion schema — ADR-102, research notes 107 / 112-115.

   A `:disposal` is an ownership-change EVENT: at one moment in time
   a specific subject (an asset, a lot of shares, a participation, a
   business segment) is relinquished, producing proceeds and realizing
   gain or loss against the holder's basis.

   The GL posts the proceeds / basis / gain-or-loss against accounts;
   the `:disposal` carries the EVENT DATA — what was disposed, when,
   for how much, against what basis, with what holding period — that
   the GL alone cannot see. CGT providers (per-jurisdiction) consume
   `:disposal` entities to compute capital-gains tax: the `:disposal`
   carries the data, the provider applies the law.

   ## The shape

   - **Core data** (every disposal): kind, subject (polymorphic ref +
     enum), acquired-on / disposed-on instants, proceeds + basis (Money
     pairs), realizing-tx edge to the kernel `:transaction`, audit-doc
     refs, state-machine facet `:disposal/state`.

   - **Jurisdiction-specific extension** (sparse, populated when the
     subject's CGT regime requires it): subject-form, ownership-
     fraction, asset-class, depreciation-taken, residence?, elective-
     regime, exemption-claimed, rollover-into-asset/amount/deadline,
     loss-bucket. Each surfaced by a CGT research note (112-115);
     each is optional — a generic disposal needs only the core.

   - **Holding-period** is BOTH `:acquired-on` + `:disposed-on` (the
     dates the jurisdiction's classifier reads) AND `:holding-period`
     (a denormalized `:short` / `:long` / `:n-a` / `:long-residence`
     etc. classification stamped at `record-disposal!` time). The
     denormalization captures the rule that was in force when the
     event recorded itself — the law-as-it-stood doctrine. See note
     115 (JP) for the canonical example (Jan-1 measurement rule).

   ## State machine (ADR-034, facet `:disposal/state`)

     :recorded → :recognized
     :recorded / :recognized → :voided

   `:recorded` is the data entered; `:recognized` is the realizing
   transaction posted; `:voided` is a correction (the original
   `:disposal` is kept for audit; the void emits its own state-history
   row + audit-doc rationale).

   ## DisposalSource protocol

   Kernel CGT providers depend on a `DisposalSource` protocol (one
   method: `(disposals-in-period conn period entity)`); the companion
   implements it. Pure-service consumers don't load the companion →
   no `:disposal/*` schema → CGT providers see no disposals (return
   nil). Loose coupling; matches the existing `TaxRateProvider` /
   `FxRateProvider` pattern."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :disposal — the event entity
;; ============================================================================

(def ^:private disposal-attrs
  [;; --- Identity ---------------------------------------------------------
   {:db/ident       :disposal/external-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Caller-supplied stable id. Identity attribute."}

   ;; --- Event taxonomy ---------------------------------------------------
   {:db/ident       :disposal/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Event kind. Closed enum:
                     :sale (arm's-length transfer for consideration),
                     :incorporation-contribution (founder contributes to a
                       new entity — often a deemed disposal),
                     :abandonment (worthless / junked / unrecognised loss),
                     :gift (transfer without consideration),
                     :conversion (US §1033 — involuntary, e.g. casualty),
                     :distribution-in-kind (corp distributes asset to owner),
                     :deemed (statutorily-deemed, e.g. exit tax,
                       constructive sale, mark-to-market election)."}

   ;; --- Who is disposing (the holder) ----------------------------------
   {:db/ident       :disposal/entity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The `:entity` holder — the legal person whose books
                     this disposal hits and against whom the CGT liability
                     accrues. Required for multi-tenant CGT computation:
                     the CGT provider asks per-entity for disposals in a
                     period; without `:entity` it would either over-report
                     (mixing entities) or have to climb every polymorphic
                     `:subject` ref to derive the holder."}

   ;; --- What was disposed ------------------------------------------------
   {:db/ident       :disposal/subject
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Polymorphic ref to the disposed entity — typically
                     an `:asset` (kontor-asset), a `:lot` (kernel lot
                     tracking), a `:commitment` (kontor-commitment),
                     or a participation/partner ref. The ref's namespace
                     identifies the kind; `:disposal/subject-kind`
                     redundantly tags it for fast filtering."}

   {:db/ident       :disposal/subject-kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Subject classification (closed-by-ADR; extend via
                     ADR addendum):
                     :fixed-asset, :participation (corporate
                       shareholding), :inventory, :intangible (patent,
                       trademark, goodwill), :business-segment (entire
                       trade/business), :securities-stock (listed +
                       unlisted shares held as investment),
                     :securities-other (bonds, units, debentures),
                     :real-estate-private (DE §23 / JP land /
                       US §121 main home), :movable-private (DE §23
                       speculation period)."}

   {:db/ident       :disposal/asset-class
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Finer-grained jurisdiction-tagged classification
                     for CGT regime selection. Open vocabulary,
                     conventionally namespaced by jurisdiction:
                     `:de-§8b-participation` / `:us-§1202-qsbs` /
                     `:jp-listed-securities` / `:uk-residential-property`
                     etc. Read by per-jurisdiction CGT providers to
                     pick the right schedule + exemptions."}

   {:db/ident       :disposal/subject-form
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Legal form of the subject's holder/issuer when it
                     matters for the regime: `:corp` / `:partnership` /
                     `:sole-prop` / `:individual` (DE §8b gates on
                     corporate holder; DE §17 on individual ≥1% stake;
                     UK BADR on 5% trading-company holdings)."}

   ;; --- Timing -----------------------------------------------------------
   {:db/ident       :disposal/acquired-on
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Acquisition date of the subject. The date the
                     jurisdiction's holding-period classifier reads —
                     per notes 113 + 114, the enum `:short / :long`
                     is not enough (DE has 1y/10y/tax-free-beyond
                     cutoffs; UK indexation needs per-acquisition
                     date)."}

   {:db/ident       :disposal/disposed-on
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Disposal date — the moment ownership transferred.
                     JP uses Jan-1-of-disposal-year for the holding
                     classifier (note 115); the classifier reads the
                     date, the result lands in `:holding-period`."}

   {:db/ident       :disposal/holding-period
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Denormalised holding-period classification — the
                     law-as-it-stood result of the per-jurisdiction
                     classifier at `record-disposal!` time. Common
                     values: `:short` / `:long` / `:n-a`. Jurisdiction-
                     specific extensions: `:long-residence` (JP §31-3
                     >10y land), `:tax-free` (DE §23 >10y real estate)."}

   ;; --- Money pairs (proceeds + basis + depreciation-taken + rollover) --
   ;; Each Money is two attrs (BigDecimal amount + commodity ref) per
   ;; kontor convention (no `:db.type/edn` for Money values).

   {:db/ident       :disposal/proceeds-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Proceeds received — the consideration. Gross of
                     transaction costs unless the caller subtracts."}

   {:db/ident       :disposal/proceeds-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Commodity ref for `:disposal/proceeds-amount`
                     (typically the functional currency)."}

   {:db/ident       :disposal/basis-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Tax basis (adjusted cost) of the disposed subject.
                     For depreciable assets, basis is acquisition cost
                     minus accumulated depreciation (the depreciation
                     itself recorded in `:depreciation-taken-amount`
                     for recapture)."}

   {:db/ident       :disposal/basis-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :disposal/depreciation-taken-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Accumulated depreciation taken against the
                     disposed asset. US §1245 (personal property) /
                     §1250 (real property) recapture rules read this
                     to split the gain into ordinary-income vs
                     long-term-capital lanes (note 112 §3 P0 — required
                     for US correctness)."}

   {:db/ident       :disposal/depreciation-taken-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   ;; --- Eligibility + classification facts ------------------------------
   {:db/ident       :disposal/ownership-fraction
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Holder's ownership percentage of the disposed
                     participation (0–1). DE §17 gates on ≥1%; DE §8b
                     Streubesitz on ≥10%; UK BADR on ≥5%."}

   {:db/ident       :disposal/residence?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True iff the disposed real-estate subject was the
                     holder's principal residence. US §121 ($250k/$500k
                     exclusion); JP §35 (¥30M deduction); DE §23
                     residence exception."}

   {:db/ident       :disposal/elective-regime
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "Elections claimed for this disposal. Cardinality-
                     many keyword set: `:de-günstigerprüfung` /
                     `:de-§6b-reserve` / `:de-teileinkünfteverfahren` /
                     `:us-§453-installment` / `:us-§1031-like-kind`."}

   {:db/ident       :disposal/exemption-claimed
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "Statutory exemptions the holder claims. Cardinality-
                     many keyword set: `:uk-sse` / `:de-§8b-95pct` /
                     `:us-§1202-qsbs` / `:us-§121-home-sale` /
                     `:jp-§35-residence` / `:fr-art-150-0-D-PEA`."}

   ;; --- Rollover relief --------------------------------------------------
   {:db/ident       :disposal/rollover-into-asset
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the replacement asset (typically a
                     `kontor-asset` `:asset`) when this disposal's
                     gain is deferred via rollover relief. US §1031
                     like-kind, DE §6b reserve, UK TCGA s152,
                     JP §36-2 replacement."}

   {:db/ident       :disposal/rollover-amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Amount of gain deferred into the replacement asset.
                     The recognized gain is `proceeds − basis − rollover-amount`."}

   {:db/ident       :disposal/rollover-amount-commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :disposal/rollover-deadline
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Deadline by which the replacement must be acquired
                     for the rollover to be honoured. DE §6b — 4 years
                     (6 for buildings); UK s152 — 12 months before /
                     36 after; JP §36-2 prescribed period."}

   ;; --- Loss compartmentalisation ---------------------------------------
   {:db/ident       :disposal/loss-bucket
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Compartmentalises the realised loss for offset
                     purposes. DE four-bucket walls (`:de-§8b` /
                     `:de-§17` / `:de-§20-stock` / `:de-§20-other` /
                     `:de-§23`); US (`:st-capital` / `:lt-capital` /
                     `:§1250-unrecaptured` / `:§1245-recapture`);
                     UK (`:uk-capital`); JP per-asset-class."}

   ;; --- GL + audit -------------------------------------------------------
   {:db/ident       :disposal/realizing-tx
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to the kernel `:transaction` that posted the
                     proceeds, basis, and realised gain/loss to the GL.
                     Set when the disposal transitions
                     :recorded → :recognized."}

   {:db/ident       :disposal/audit-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Refs to ADR-038 `:audit-doc`s — sale contract,
                     board resolution, IRS Form 8949, BMF Schreiben."}

   {:db/ident       :disposal/notes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Free-text annotation."}

   {:db/ident       :disposal/voids
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "When a disposal is a CORRECTION of an earlier one,
                     the void disposal points at the original. Maintains
                     the audit chain rather than retracting."}

   ;; --- State-machine facet (ADR-034) -----------------------------------
   {:db/ident       :disposal/state
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 status-machine facet for the disposal
                     lifecycle: :recorded → :recognized; :recorded
                     or :recognized → :voided. Drives
                     `record-status-change!` writes; the transition
                     seeds live alongside this schema."}])

;; ============================================================================
;; :disposal/state — facet of the kernel ADR-034 status-machine
;; ============================================================================

(def ^:private status-transition-seeds
  "Seed `:disposal/state` lifecycle transitions per ADR-034.
   `:status-transition/identity` is a composite tuple
   `[entity-type facet from to org]` → re-running install! on the SAME
   DB violates the unique-constraint; install once per DB."
  [{:status-transition/entity-type :disposal
    :status-transition/facet       :disposal/state
    :status-transition/from        :nil
    :status-transition/to          :recorded
    :status-transition/active      true
    :status-transition/name        "Record Disposal — initial create"}
   {:status-transition/entity-type :disposal
    :status-transition/facet       :disposal/state
    :status-transition/from        :recorded
    :status-transition/to          :recognized
    :status-transition/active      true
    :status-transition/name        "Recognize Disposal — posting committed"}
   {:status-transition/entity-type :disposal
    :status-transition/facet       :disposal/state
    :status-transition/from        :recorded
    :status-transition/to          :voided
    :status-transition/active      true
    :status-transition/name        "Void Disposal — pre-recognition correction"}
   {:status-transition/entity-type :disposal
    :status-transition/facet       :disposal/state
    :status-transition/from        :recognized
    :status-transition/to          :voided
    :status-transition/active      true
    :status-transition/name        "Void Disposal — post-recognition correction"}])

;; ============================================================================
;; Installer
;; ============================================================================

(def all
  "All disposal schema attrs (NOT including the status-transition
   seeds — those go through `install!` which is one-per-DB)."
  disposal-attrs)

(defn install!
  "Install the kontor-disposal schema + `:disposal/state` status-
   machine seeds. Run after `kontor.schema/install!` (the kernel
   `:status-transition` attrs must already exist).

   The schema attrs are idempotent; the status-transition seeds carry
   the kernel-wide composite-tuple non-idempotency caveat — one
   install per DB."
  [conn]
  (d/transact conn all)
  (d/transact conn status-transition-seeds))

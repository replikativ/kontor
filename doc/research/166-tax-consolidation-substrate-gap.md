---
date: 2026-05-25
title: 166 — Tax-consolidation substrate gap: what kontor already provides vs. what Gap #8 needs
status: design note (no code changed) — companion to notes 164 (DE Organschaft / FR intégration fiscale) + 165 (US §1502 / JP group-tsuusan)
audience: maintainer + Gap #8 (tax-group consolidation) ADR author
related:
  - ADR-031 (`:entity` per-line scoping)
  - ADR-073 (`kontor.consolidation` translation + elimination)
  - ADR-095 (`kontor.book` verb facade)
  - ADR-096 (`kontor.report/marginalize` as σ_E)
  - ADR-097 (`:posting-dimension` classification axes)
  - ADR-099 (`PeriodTaxProvider` + `TaxReturnFacts`)
  - ADR-101 (statute-as-data: `:provision` / `:regime` / `:parameter`)
  - ADR-102 (`kontor-disposal` companion + `:disposal/loss-bucket`)
  - ADR-103 (`DisposalSource` protocol — model for the new GroupSource protocol below)
  - note 102 §4 (the "mostly substrate that already exists" claim under test here)
  - note 104 (the tax-completion program — Gap #8 sits inside Phase 4)
---

# 166 — Tax-consolidation substrate gap

## TL;DR

Note 102 §4 (echoed by note 104 §2) claimed tax consolidation is **"mostly
substrate that already exists"** — the argument being that ADR-031 (`:entity`
family), ADR-073 (financial consolidation: translate + eliminate), ADR-072
(`FxRateProvider`), ADR-096 (`marginalize` / σ_E), and ADR-099 (`PeriodTaxProvider`
+ `TaxReturnFacts`) collectively cover the multinational rung.

This note tests that claim against the actual code as of commit `1f116dc`
(11 CGT providers shipped, 6 ADR-101 CIT providers shipped — all single-entity).

**Verdict: partially true (≈ 40 %).** The financial-consolidation half is
genuinely substrate. The tax-consolidation half is materially new work:
the existing primitives **do not carry** the regime-electing membership,
the deferred-not-eliminated intercompany semantics, the pre-affiliation
loss buckets, or the single-base-vs-per-member-netting axis that the four
reference regimes (DE Organschaft, FR intégration fiscale, US §1502, JP
group-tsuusan) all require in one form or another.

The shape of the gap (sized in §2): **3 kernel schema additions**, **2
provider-protocol surfaces** (a new `TaxGroupSource` + an extension to
`PeriodTaxProvider`'s ctx contract), **1 metadata flag on existing
elimination postings**, and **1 evaluator helper** (per-member loss-bucket
roll). No new evaluator namespace is needed — `kontor.statute/apply-provisions`
extends without code change once a `:tax-group` context key is honored.

The recommended landing shape (§4) is **Option C** — kernel `:tax-group/*`
namespace + a small `kontor.tax-group` ns + reuse of the existing
companion-pattern for jurisdictional regimes — because Option A (a
`:tax-mode` flag on the financial-consolidation primitive) couples two
genuinely different operations, and Option B (a fully separate companion)
duplicates the entity family walk and creates two consolidation surfaces
the consumer has to keep aligned.

This note is content-neutral on which regime-specific design (DE / FR
single-base; US deferred-matching; JP per-member-with-netting) the
synthesizing ADR picks first; that's note 164's + 165's job and is
deliberately not pre-empted here.

---

## §1. What kontor already provides — verified inventory

For each existing primitive: file:line citations, its API surface, and one
sentence on whether it carries tax consolidation.

### 1.1 `kontor.consolidation` — financial consolidation (ADR-073)

**`translate-trial-balance-tx-data`** (`src/kontor/consolidation.clj:130-286`)
— pure tx-data builder. Translates one `:operating` entity's trial balance
into the consolidation entity's functional commodity per IAS 21 rate-types,
emitting ONE balanced tx stamped `:posting/entity = consolidation-entity`,
with the CTA absorbing the per-commodity plug.

- **Inputs**: `:db :source-entity :consolidation-entity :presentation-commodity
  :fx-provider :at-date :journal :cta-account :trial-balance` (and per-account
  rate-type overrides).
- **Output**: a single `:transaction/consolidation-kind :translation` tx with
  N postings, each stamped with the consolidation entity.
- **Carries tax consolidation**: **no**. Translation is the *financial*
  operation — restating a foreign-functional entity's balances into the
  group's presentation currency. Tax consolidation re-aggregates *taxable*
  bases, which are derived from the *local* statutory books, not the
  *translated* books. A DE Organschaft computes the Organträger's KSt base
  from each Organgesellschaft's local-GAAP profit *in EUR* — no translation
  involved when all members are EUR-functional, and when they aren't
  (rare for German Organschaft because all members must be German tax
  residents, common for US §1502 across multi-currency members) the
  translation is at the *member's* level for *currency* reasons, not at
  the *group* level for *consolidation* reasons. The two are orthogonal.

**`eliminate-intercompany-pair-tx-data`** (`src/kontor/consolidation.clj:328-388`)
— pure tx-data builder. Given a `:transaction/intercompany-pair-id`, emits
one tx that exactly negates every paired posting, stamped with the
elimination entity. `find-pair-postings` (`:292-326`) excludes prior
consolidation txs to keep re-runs idempotent and filters
`:transaction/state :posted` to skip drafts.

- **Inputs**: `:db :pair-id :elimination-entity :journal :date`.
- **Output**: a single `:transaction/consolidation-kind :elimination` tx with
  N postings on the elimination entity.
- **Carries tax consolidation**: **partially**. The pair-id mechanism +
  the "find all postings tagged X, emit their negation" machinery is
  reusable — *both* financial elimination and tax neutralisation walk
  the same pair set. What's missing is the *style* of neutralisation:
  - **Financial elimination** (what this does today) — full negation.
    The intercompany sale never happened from the group's perspective.
  - **US §1502-13 deferred intercompany transaction** — the income/loss
    is *deferred*, not eliminated. A deferred-tax asset/liability moves
    in lock-step, recognised when the asset leaves the group (the
    "matching" rule). The posting is *real* but its *timing* is shifted.
  - **FR Art. 223 F déneutralisation** — the elimination is *removed*
    when the underlying asset leaves the group (the reverse of §1502-13).
  - **DE Organschaft** — intercompany distributions inside the group are
    simply not subject to KSt §8b (no participation exemption needed because
    the income is the *Organträger's* anyway), but services between members
    *do* trigger GewSt at each side unless the recipient/supplier are both
    in the same Hebesatz-zone (rare). The elimination here is
    component-selective.

  None of these styles have a representation in the current
  `eliminate-intercompany-pair-tx-data`. The same pair-id + negation
  mechanism would work, but the *metadata* — what *kind* of neutralisation
  this is, what its *reversal trigger* is, and which *tax component* it
  affects — has no home today.

**`consolidate-tx-data` + `consolidate!`** (`src/kontor/consolidation.clj:394-578`)
— the composer + the `kontor.process/run-process` orchestrator. Walks
`kontor.entity/family` from `:group-root`, runs translation per `:operating`
entity, runs elimination per distinct pair-id touching the family, returns
a vector of tx-data fragments stitched into one atomic commit. Has
idempotency guards on both translation (`translation-exists?` `:482-491`)
and elimination (`elimination-exists?` `:497-508`).

- **Inputs**: `:conn :group-root :consolidation-entity :elimination-entity
  :presentation-commodity :fx-provider :at-date :journal :cta-account`.
- **Carries tax consolidation**: **no**. The walk + the atomic-commit
  envelope are reusable, but the operation it composes (translate +
  eliminate) is the *financial* op. A *tax* consolidate! would compose a
  different operation set: identify tax-group members, marginalize each
  member's tax base, aggregate per group rules, neutralize intercompany
  per the regime's deferral pattern, file ONE return that names the
  group as the assessable unit.

### 1.2 `kontor.entity` — the family walk (ADR-031)

**`family`** (`src/kontor/entity.clj:94-98`) — `entity-eid → #{ancestors-or-self}`
— actually returns the entity + all descendants (`descendants` `:82-92` is
the transitive reach). Walks `:entity/parent-entity` refs.

- **Output**: a plain set of entity-eids.
- **Schema dependencies**: `:entity/code :entity/parent-entity :entity/kind
  :entity/active :entity/country :entity/functional-commodity
  :entity/accounting-standard :entity/lei :entity/parent-lei` and
  GLEIF master-data hooks (`schema.clj:3650-3762`).
- **Carries tax consolidation**: **partially**.
  - **What it DOES carry**: the *legal/ownership-or-control* family — the
    same hierarchy that financial consolidation walks. Adequate for
    "membership of a consolidation group" when "consolidation group" =
    "the same as the financial consolidation family."
  - **What it DOES NOT carry**:
    - **Ownership fraction (% held).** No `:entity/ownership-fraction` attr
      exists (verified: `grep -n "ownership-fraction" src/kontor/schema.clj`
      returns nothing; `:disposal/ownership-fraction` exists in the disposal
      companion but is per-disposal, not per-entity-relation).
      US §1502 requires ≥ 80 % vote AND value of each subsidiary;
      DE Organschaft requires `finanzielle Eingliederung` ≥ 50 %; FR
      requires ≥ 95 %; JP group-tsuusan requires 100 %. The current
      schema cannot express the threshold check.
    - **Regime election state.** Whether a given parent ↔ child relationship
      is *elected into* a particular tax-consolidation regime is missing.
      The `:entity/parent-entity` ref is a fact about *structure*; the
      tax-group is a fact about *choice* (and reversibility — Organschaft
      runs a minimum 5-year `Mindestlaufzeit`).
    - **Regime-specific facts.** Organschaft needs a `Gewinnabführungsvertrag`
      date + valid-from / valid-until. §1502 needs the §1504 affiliated-group
      vote-and-value verification + the §1.1502-75 election doc. None of
      these have a home in the entity schema.

### 1.3 `kontor.report/marginalize` — the σ_E primitive (ADR-096)

**`marginalize`** (`src/kontor/report.clj:230-254`) — partition a posting
sequence by `dimension` and sum within each class. Returns `{class {:value
Money :postings [eid…]}}`. Built-in axes via `dimension-extractors`
(`:198-206`): `:account-type :account-code :account-path :ledger :entity
:commodity :partner :account-tags`. Plus any `:posting-dimension` axis
(set-valued; ADR-097).

- **`:entity` axis is in the table**: `(report/marginalize postings :entity {...})`
  already gives sum-by-member trivially today.
- **`:tax-group` axis would NOT extend cleanly today**: there is no
  `:posting/tax-group` ref to extract over, and no resolver from
  `:posting/entity → :tax-group` either. A trivial path is "marginalize over
  `:entity`, then group the result by `tax-group-of(entity)` in client
  code" — but that loses the kernel's σ_E uniformity.
- **Carries tax consolidation**: **partially**. The math is right (sum
  over an axis); the missing piece is *which axis*. Either:
  - add a `:posting/tax-group` ref stamped at post-time (analogous to
    `:posting/entity`, ADR-031 / `schema.clj:3764-3777`), OR
  - add a derived dimension extractor that resolves `:entity → :tax-group`
    via the entity tree.

  The second is much cheaper (no posting rewrites; no migration of
  historical data; reads the membership graph from `:tax-group/members`
  refs at marginalize-time). The first is faster at scale but couples
  posting writes to a regime concept that *can change* (Organschaft
  ends, US §1502 election revoked).

### 1.4 `kontor.book/entry!` — the verb facade (ADR-095)

**`entry!` + `entry-tx-data`** (`src/kontor/book.clj:1-200+`) — the single
pure builder; verbs are conveniences. Per-posting `:entity` overrides the
entry-level one — this is the "intercompany pattern" referenced in
`book.clj:61` and `:166`. A judgment entry can mix entities across postings.

- **Does an entry know whether it's "intra-group" today?**
  - **No.** The entry knows about its `:entity`s (whatever the consumer
    sets on each posting); it knows whether `:transaction/intercompany-pair-id`
    is present (the consumer sets that explicitly per ADR-073). It does NOT
    auto-detect "this is intercompany because both entities are members of
    the same parent-entity tree." That's a query-side concern today.
  - The CGT pattern for "intra-group disposal" (note 127 CA, note 132 FR)
    is currently handled by the consumer stamping the relevant
    `:disposal/elective-regime` (e.g. `:de-§6b-rollover`) — there is no
    automatic intra-group classification.
- **Carries tax consolidation**: **partially**. Entries can be made between
  group members (sum-to-zero per (entity, ledger, commodity) holds by
  ADR-031); they can be flagged with a pair-id for elimination later. What
  they cannot do is be *flagged as intra-group* at write time so the
  consolidator can find them without the consumer remembering to set a
  pair-id on every IC transaction. (Note 80 / 88 already flagged this as a
  weakness — the McComb arc would want every entry to know its place in
  the larger event graph.)

### 1.5 `:posting/entity` — single-entity stamp (ADR-031, ADR-097 sibling)

**Schema attr** (`src/kontor/schema.clj:3764-3777`) — single-cardinality ref
on every line. Per ADR-031, when any posting in a tx carries it, the
sum-to-zero invariant extends to per-(entity, ledger, commodity). Mixed
mode (some postings tagged, some not) is rejected.

- **Carries tax consolidation**: **no, but it's the natural anchor.**
  The cleanest substrate extension is a sibling `:posting/tax-group`
  ref OR a derived view via the membership graph. The first option's
  trade-off is exactly what ADR-031 made for `:entity` — line-level
  vs. tx-header — and the same argument carries (lines need to be
  cleanly classifiable; mixed-entity intercompany lives natively at
  the line level). Note that a single posting could carry BOTH
  `:posting/entity` (statutory legal entity) AND `:posting/tax-group`
  (the elected fiscal unit) — they are not the same dimension.

### 1.6 `kontor.period-tax-provider/PeriodTaxProvider` — the period-tax substrate (ADR-099)

**`period-tax-facts`** (`src/kontor/period_tax_provider.clj:126-157`) — the
sole protocol op. Context keys: `:entity :period :base-period :db :as-of-tx
:as-of-valid :tax-unit :inputs`. Returns a `TaxReturnFacts` (`:81-86`):
`{:entity :period :jurisdiction :functional-commodity :components}`.

- **Can a `:tax-group` enter the ctx?** **Mechanically yes** — the ctx is an
  opaque map; the protocol does not enforce that `:entity` is the only
  scope key. A provider could read `(get ctx :tax-group)` and prefer it
  over `:entity` for its base selector.
- **Semantically, the gap**: the `TaxReturnFacts` record has a single
  `:entity` field. There is no `:tax-group` field. Today the answer would
  be "stuff the group-eid in `:entity` and document the convention" —
  which works but loses the distinction between a *statutory entity*
  (where the books actually live) and the *fiscal unit* (the assessable
  unit). The synthesizing ADR will want a real second slot:
  `{:entity :tax-group :period ...}` — `:entity` for the per-member rollup
  view, `:tax-group` for the group-level facts.
- **Carries tax consolidation**: **partially**. The protocol itself is
  scope-agnostic. The `TaxReturnFacts` data record has only `:entity`
  today. A clean extension is a new field + a constructor variant.

### 1.7 `kontor.statute/apply-provisions` — the ADR-101 evaluator

**`apply-provisions`** (`src/kontor/statute.clj:485-526`) — pipeline:
`applicable-provisions` (`:307-342`) → `prune-defaults` → `assert-no-ambiguity!`
→ resolve consequences → group by `:op`. Returns `{:base-items :tax-items
:schedule-overrides :provisions}`.

- **Ctx contract**: the only thing `apply-provisions` does with `ctx` is
  thread it into `eval-condition` (for `:provision/condition` predicates)
  and `resolve-consequence` (for `:tax-context-fact` / `:compute-fn`
  consequences). It does **not** require any specific key.
- **Carries tax consolidation**: **yes, transparently.** A provision can
  already express "this rule fires only when `:tax-group/regime
  :de-organschaft`" via `[:eq [:tax-group :regime] :de-organschaft]` in
  its `:provision/condition` (the closed predicate vocab `:58-61` includes
  `:eq` and the nested-vector fact path in `fact` `:63-68` supports it),
  and a `:compute-fn` consequence can compute against any ctx shape. The
  evaluator itself needs ZERO change for tax-group support; the change is
  upstream — what does the provider put in `ctx`?

### 1.8 `kontor.fx` — FX translation (ADR-072)

**`convert`** (`src/kontor/fx.clj:50-...`) + `translate-amounts-by-commodity`
+ the `FxRateProvider` protocol — already shipped, already consumed by
`kontor.consolidation/translate-trial-balance-tx-data`.

- **Carries tax consolidation**: **yes, reusable as-is.** When tax-consolidation
  needs to translate per-member taxable bases to a group currency (US §1502
  for a US parent with USD-functional + foreign subsidiaries that are
  themselves EUR/GBP/JPY-functional — note that US §1502 typically
  consolidates only domestic members, so this is a narrower case than
  financial), `fx/convert` handles the math without modification. The IAS
  21 rate-type story is over-engineered for tax (tax usually wants
  average-rate for P&L and closing-rate for BS items, but there's a single
  authoritative rule per regime — IRC §985-989 for US, no IFRS-style
  free choice), but the over-engineering doesn't *block* anything.

### 1.9 `:tax-group/*` schema — name collision warning

**`tax-group-attrs`** (`src/kontor/schema.clj:2012-2030`) — the *existing*
`:tax-group/*` namespace is the **VAT accounting-group** concept:
`:tax-group/name :tax-group/country-code :tax-group/payable-account
:tax-group/receivable-account`. This is what `:tax/tax-group`
(`schema.clj:1910-1915`) points at — the bucket where collected output VAT
and recoverable input VAT accumulate per `tax_posting_builder.clj:120-139`.

- **It is NOT a corporate-tax-consolidation group.** The namespace is taken
  for an unrelated concept. The synthesizing ADR must either:
  - Rename one of them (the VAT concept is older; renaming it is a
    breaking change for every l10n module that wires `:tax/tax-group`).
  - Pick a different namespace for the corporate-tax-consolidation concept
    (`:fiscal-unit/*` per FR usage; `:consolidated-group/*` per US §1504
    usage; `:tax-affiliation/*` per the membership-as-edge framing).

  **Recommendation: `:fiscal-unit/*`** — it's regime-neutral (FR uses
  "intégration fiscale" → "fiscal unit"; UK uses "group" but the
  "fiscal unit" framing reads better in cross-jurisdictional context),
  it doesn't collide with the existing VAT concept, and it parallels how
  payroll uses `:pay-period/*` separately from `:period/*`.

---

## §2. What's structurally missing — the ranked gap

Six elements, in rough dependency order:

### 2.1 A `:fiscal-unit` entity — the consolidating election

The membership group with regime metadata. Doesn't exist; `:entity` family
walk is the closest but doesn't carry regime info, doesn't carry
ownership-fraction, doesn't carry election-state.

**Proposed shapes** (the ADR picks one):

- **(a) Kernel schema attr group `:fiscal-unit/*`** — first-class entity:
  `:fiscal-unit/code :fiscal-unit/name :fiscal-unit/parent-entity (ref →
  :entity, the responsible / consolidating member) :fiscal-unit/regime
  (keyword :de-organschaft | :us-1502 | :fr-integration | :jp-tsuusan |
  ...) :fiscal-unit/elected-from :fiscal-unit/elected-until
  :fiscal-unit/minimum-term-ends (DE 5-year Mindestlaufzeit) :fiscal-unit/active`.
  Plus a join: `:fiscal-unit-member` linking `(fiscal-unit, entity, role
  :parent | :subsidiary, ownership-fraction, joined-on, left-on)`.
  Trade-off: makes "the group" queryable in datalog at full fidelity;
  but commits the kernel schema to a regime taxonomy (the
  `:fiscal-unit/regime` enum). The enum is closed-by-ADR (note 101
  discipline); adding `:cn-corporate-group` is an ADR addendum.

- **(b) Companion module `kontor-fiscal-unit`** — schema + helpers in a
  companion; kernel stays untouched. Mirrors `kontor-disposal` (ADR-102)
  → CGT providers depend on a `DisposalSource` protocol, with the
  companion as the canonical impl. The "FiscalUnitSource" protocol would
  expose `members-of`, `regime-of`, `ownership-fraction`, leaving storage
  up to the consumer.
  Trade-off: matches the companion-tier discipline; but every CIT
  provider must add a `FiscalUnitSource` dep, and the cross-cutting
  reads (e.g. `:posting/tax-group` resolution) become protocol calls,
  not datalog joins.

- **(c) Derived view over `:entity/parent-entity` + a small overlay** —
  no new top-level entity; ship a regime-election table `:fiscal-election/*`
  (`:fiscal-election/parent-entity :fiscal-election/regime
  :fiscal-election/elected-from :fiscal-election/elected-until`) and
  derive "members of a fiscal unit" by walking `family` from the parent
  and filtering by an `:entity/in-fiscal-election?` predicate evaluated
  at `:as-of`.
  Trade-off: minimal schema; but the membership semantics are spread
  across entity-tree + election table; auditing "who was in the group
  on date X" requires both queries.

### 2.2 Regime-specific constraints — what the regime IMPLIES

Each regime carries a distinct constraint set. The substrate needs a way
for the provider to read these constraints; the data they live in is the
gap.

| Regime | Constraint kind | Where it currently lives |
|---|---|---|
| DE Organschaft | Each member files own KSt/GewSt computationally, but Organträger remits sum; intercompany distributions ignored for §8b; 5-year minimum | Provider code (would have to special-case) |
| US §1502 | Single consolidated return; intercompany deferred-then-matching; SRLY pre-affiliation losses; ELA/dual-consolidated-loss caps | Nothing today |
| FR intégration fiscale | Single base = Σ member bases ± neutralisations; déneutralisation when leaving; 5-year minimum | Nothing today |
| JP group-tsuusan | Per-member filings with inter-member loss netting + group adjustments; can mix unitary + separate elections per member | Nothing today |

**Proposed shape**: `:fiscal-unit/regime-rules` as `:tax-concept`-pointed
provisions per ADR-101. A regime is a `:regime` entity (already exists,
`schema.clj` per ADR-101); regime-bound provisions
(`:provision/regime → :regime`) already work via `regime-chain`
(`statute.clj:274-305`). What's missing is the *gating ctx fact*:
`apply-provisions` doesn't know "the taxpayer has elected `:de-organschaft`
for this period." Adding `(get-in ctx [:fiscal-unit :regime])` as a
canonical fact-path is one line of provider code per provider; the
substrate just needs to document it.

### 2.3 Per-member loss buckets scoped to "pre-this-group"

DE pre-Organschaft *vorvertraglicher Verlust* (loss carried from before
the Organschaft) cannot offset the Organträger's profit. US SRLY (separate
return limitation year) restricts pre-affiliation losses to the contributing
member's own post-affiliation income. JP pre-tsuusan losses similarly
walled.

The kernel's current loss-bucket model is `:disposal/loss-bucket`
(`modules/disposal/src/kontor/disposal/schema.clj:276-284`) — a keyword on
each disposal classifying the realised loss for offset purposes. It is
per-entity but **not pre/post-affiliation aware**: there is no
`:loss-bucket/eligible-against :fiscal-unit-or-pre-affiliation` axis.

**Proposed shape**: extend `:disposal/loss-bucket` (or introduce a sibling
`:loss-bucket-entry/*` schema in `kontor-disposal`) with an
`:eligibility-window` ref naming the fiscal-unit-period the loss can be
offset against. Pre-affiliation losses get an eligibility-window of "the
member's own standalone return only"; post-affiliation losses get the
fiscal unit's window.

Adjacent: there is no schema today for a *standalone* loss carryforward
either — losses live implicitly in the CGT computation per period. The
pre-affiliation framing forces this implicit model to become explicit.
Tracked separately as a follow-up; not strictly required to ship Gap #8
v1 if the v1 ships with a "consumer supplies pre-affiliation residual via
`:inputs`" escape hatch.

### 2.4 A second "elimination" style — deferral vs. neutralisation

Financial consolidation eliminates intercompany sales entirely. Tax
consolidation:
- **defers them** (US §1502-13) — the income exists, the recognition is
  postponed; a deferred-tax pair moves alongside.
- **neutralises them at a different rate** (FR Art. 223 F déneutralisation)
  — eliminated within the group, reversed when the asset leaves.
- **selectively ignores them per component** (DE Organschaft GewSt vs KSt).

Different metadata on the same intercompany-pair posting:

| Today (financial) | Needed (tax) |
|---|---|
| `:transaction/consolidation-kind :elimination` | `:transaction/consolidation-kind :elimination | :deferral | :neutralisation` |
| (no reversal trigger) | `:elimination/reversal-trigger :asset-leaves-group | :member-departs | :election-revoked` |
| (no per-component scope) | `:elimination/components-affected #{:kst :gewst | :income | :gst}` |
| `:transaction/intercompany-pair-id` (string) | reused as-is |

**Proposed shape**: extend `:transaction/consolidation-kind` enum
(`schema.clj:3805-3810`) to include `:deferral` and `:neutralisation`; add
`:transaction/elimination-reversal-trigger` (keyword) and
`:transaction/elimination-components` (cardinality-many keyword). The
`eliminate-intercompany-pair-tx-data` builder gains a `:style` opt
defaulting to `:elimination` (preserves current behaviour); a new
`tax-eliminate-intercompany-pair-tx-data` (or `:style :deferral` /
`:style :neutralisation` branch in the same fn) emits the variant.

### 2.5 The "single base vs per-member netting" axis

DE / FR / US compute a **single group base** — the assessable unit is the
fiscal unit, members do not file. JP keeps **per-member filings** + does
**inter-entity loss netting post-compute** — the assessable unit is the
member; the group is a netting layer above.

The provider protocol must support both. Today `PeriodTaxProvider/period-tax-facts`
returns ONE `TaxReturnFacts` per call with ONE `:entity`. The single-base
case fits if we pass the fiscal unit as `:entity` (or as `:tax-group` per
2.6). The per-member-with-netting case needs to return either:
- N `TaxReturnFacts` (one per member), with a netting step the orchestrator
  composes downstream, OR
- ONE `TaxReturnFacts` with `:components` carrying per-member breakouts
  and the netting reflected in the component math.

Both representations are expressible in the existing record shape (note
that `:components` is already a vector; nothing prevents one component per
member with a `:tax-group-member entity-eid` field). What's missing is a
**convention** — which representation does kontor pick? The synthesizing
ADR's call.

**Proposed shape**: ship a `TaxGroupProvider` orchestrator (analogous to
the existing `kontor.consolidation/consolidate!` orchestrator) that takes
a `PeriodTaxProvider` instance + a fiscal-unit ref + a period, and:
- For single-base regimes: calls the provider once with `:tax-group` in ctx
  and the marginalisation rewired to the group axis.
- For per-member-with-netting regimes: calls the provider N times (one per
  member), then runs a `netting-fn` on the returned `TaxReturnFacts` seq
  to produce the consolidated outcome.

This is **schema-free** (it's an orchestrator over the existing protocol).
It does need one new kernel namespace, `kontor.fiscal-unit`, with the
orchestrator + the family-resolver + the regime-rule dispatch.

### 2.6 A `compute-tax` entry point that takes `:tax-group`

Current `period-tax-facts` ctx has `:entity`. Extending to `:tax-group` is
mechanically simple but the semantics need definition:

- When BOTH `:entity` and `:tax-group` are present, which wins for the
  base-selector? (Convention: `:tax-group` wins; `:entity` is the per-member
  detail view used by per-member-with-netting regimes.)
- When the fiscal unit's regime says "marginalize over members," does the
  base-selector see `(family members)` or only `(members at as-of-valid)`?
  (Convention: members-at-period-end; the bitemporal axis already exists.)
- When `:as-of-valid` falls outside the fiscal unit's election window, is
  it an error or does the provider fall back to the per-entity view?
  (Convention: error — calling code is asking the wrong question.)

**Proposed shape**: add `:tax-group` (or `:fiscal-unit`) as a documented
canonical ctx key in `period-tax-provider.clj:126-157`'s docstring + the
`TaxReturnFacts` record (`:81-86`) gains a `:fiscal-unit` field. Existing
providers ignore it (default behaviour unchanged); fiscal-unit-aware
providers read it.

---

## §3. Is note 102's claim accurate?

**Verdict: partially — about 40 % accurate.**

Note 102 §4's claim, restated: *"Payroll, multi-entity consolidation and
FX are already shipped — the multinational rung is mostly substrate that
already exists."*

What's already substrate (the 40 %):
- `:entity` family walk (§1.2) gives membership when the tax-group ==
  financial-consolidation family.
- `kontor.report/marginalize` over `:entity` (§1.3) gives sum-over-members
  for free.
- `kontor.fx` (§1.8) gives multi-currency translation as needed.
- `kontor.statute/apply-provisions` (§1.7) extends transparently to
  tax-group-aware provisions; zero evaluator change needed.
- `kontor.process/run-process` gives the atomic-commit envelope reused.
- `:transaction/intercompany-pair-id` (`schema.clj:3779-3794`) + the
  pair-walking machinery in `eliminate-intercompany-pair-tx-data` is
  reusable for the tax-eliminate variant.
- `TaxReturnFacts` record + `:components` vector accommodates the
  per-member breakout shape without new schema.

What's NOT substrate (the 60 %):
- No fiscal-unit entity with regime + ownership + election state (§2.1).
- No way to express the regime constraints (§2.2) — though the data path
  via `:provision` + `:regime` exists.
- No pre-affiliation loss-bucket scoping (§2.3).
- No `:deferral` / `:neutralisation` styles on the elimination primitive
  (§2.4) — and the current schema's enum is closed.
- No documented orchestrator for the per-member-with-netting style (§2.5).
- No `:tax-group` / `:fiscal-unit` canonical ctx key for `period-tax-facts`
  (§2.6) — though the protocol is opaque enough to accept it.
- A naming collision (§1.9) — `:tax-group/*` is taken for the VAT bucket
  pair.

**Quantification**: 3 substantive kernel schema additions (the
`:fiscal-unit` namespace; the `:transaction/elimination-*` extensions to
the existing enum + new ref; the `TaxReturnFacts` `:fiscal-unit` field),
1 ctx-contract extension on `PeriodTaxProvider`, 1 new protocol
(`TaxGroupSource` / `FiscalUnitSource` if we follow the companion pattern),
1 new orchestrator namespace (`kontor.fiscal-unit`), and a loss-bucket
schema extension in the disposal companion (§2.3, can be deferred to v2).
Plus a regime-rules data set per jurisdiction (consumer-tier, not
substrate). That's not "mostly substrate that already exists" — it's
"about 40 % free, 60 % new substrate, plus per-jurisdiction content."

The good news: most of the 60 % is **additive** (no rewrites of existing
consumers; existing single-entity CIT providers continue to compute the
single-entity outcome unchanged), and the new schema is small.

---

## §4. Design-space options for Gap #8

Three options on the table for the synthesizing ADR. Each sketched at
schema-delta + protocol-delta + companion-delta granularity.

### Option A — Extend `kontor.consolidation` with a `:tax-mode true` flag

**Schema delta**: extend `:transaction/consolidation-kind` enum
(`schema.clj:3805-3810`) with `:tax-elimination` and `:tax-deferral` and
`:tax-neutralisation`. Add `:transaction/elimination-style` for the new
metadata. Don't touch `:entity` or introduce `:fiscal-unit`.

**Protocol delta**: `PeriodTaxProvider` unchanged; the provider is told
"this entity is a fiscal unit's parent — marginalize over the family."
A new `kontor.consolidation/tax-consolidate!` mirrors `consolidate!` but
runs the tax variant.

**Companion delta**: per-regime jurisdictional behaviour ships in existing
`l10n-{de,fr,us,jp}` modules as `:provision`/`:regime` data + small
provider hooks.

**Existing primitives that DO NOT change**:
`kontor.entity/family`, `kontor.report/marginalize`, `kontor.fx`,
`kontor.statute`, `kontor.process`, `kontor.book`, `PeriodTaxProvider`'s
protocol, `TaxReturnFacts` record shape.

**Existing primitives that DO change**:
`kontor.consolidation` gains `tax-consolidate!` + tax variants of the
elimination builder. The `:transaction/consolidation-kind` closed enum
grows.

**Trade-off**: cheapest. Maximum coupling — `kontor.consolidation` becomes
the home for both financial and tax consolidation, and the entity family
becomes the implicit fiscal unit (no separate election state; the assumption
is "everyone in the family who's eligible elects in"). Breaks down when a
group's financial-consolidation family differs from its tax-consolidation
family — which is the normal case for US §1502 (only domestic members
consolidate for tax even when foreign members consolidate for financials).

### Option B — A new companion `kontor-fiscal-unit`

**Schema delta**: zero in the kernel. New companion ships
`:fiscal-unit/* :fiscal-unit-member/* :fiscal-election/*` in
`modules/fiscal-unit/src/kontor/fiscal_unit/schema.clj`.

**Protocol delta**: kernel ships `FiscalUnitSource` protocol (mirroring
`DisposalSource`, ADR-103) in a new `kontor.fiscal-unit-source` namespace.
The companion's `kontor.fiscal-unit/DatahikeFiscalUnitSource` is the
canonical impl.

**Companion delta**: maximum. The new companion holds the orchestrator,
the schema, the regime-rule sets. Per-jurisdiction l10n modules add a
small `:de-organschaft` (etc.) regime config + provisions.

**Existing primitives that DO NOT change**:
Everything in `kontor.consolidation`, `kontor.entity`, the kernel schema,
the existing provider protocols.

**Existing primitives that DO change**:
None in the kernel; the kernel grows one new namespace
(`kontor.fiscal-unit-source`) for the protocol. CIT providers in
`l10n-de/cit-provider`, `l10n-fr/cit-provider`, `l10n-us/cit-provider`
(when it ships), `l10n-jp/cit-provider` add a `FiscalUnitSource` dep
that defaults to a "no fiscal unit" impl when not registered.

**Trade-off**: most isolated; companion-tier discipline preserved.
Largest surface area — every CIT provider sprouts a `FiscalUnitSource`
dep, the consumer wiring grows, and the consolidation surface
proliferates (the consumer must keep `kontor.consolidation` family and
`kontor.fiscal-unit` membership in sync manually).

### Option C — Kernel `:fiscal-unit/*` namespace + `kontor.fiscal-unit` ns (recommended)

**Schema delta**: kernel adds `fiscal-unit-attrs`:
`:fiscal-unit/code :fiscal-unit/name :fiscal-unit/parent-entity (ref →
:entity, the responsible / consolidating member) :fiscal-unit/regime
(keyword, closed-by-ADR) :fiscal-unit/elected-from :fiscal-unit/elected-until
:fiscal-unit/minimum-term-ends :fiscal-unit/active`. Plus `:fiscal-unit-member`
join: `(fiscal-unit, entity, role, ownership-fraction-bigdec, joined-on,
left-on)`. Plus `:posting/tax-group` (ref → :fiscal-unit, optional) for
the marginalisation axis (analogous to `:posting/entity`). Plus the
elimination-style extensions from Option A.

**Protocol delta**: `PeriodTaxProvider` ctx contract documents
`:fiscal-unit` (preferred) and `:tax-group` (alias for `:fiscal-unit`,
because the term is what the user types — and the kernel-internal name
is reserved to avoid the `:tax-group/*` VAT collision per §1.9). The
record gains `:fiscal-unit` field.

**Companion delta**: per-regime jurisdictional behaviour ships in
existing `l10n-{de,fr,us,jp}` modules as `:provision`/`:regime` data +
small provider hooks. Loss-bucket pre-affiliation extension goes to
`kontor-disposal`.

**Existing primitives that DO NOT change**:
`kontor.consolidation` (the financial primitive stays as-is — the tax
variant lives next door in `kontor.fiscal-unit`, not bolted on).
`kontor.entity/family` (the membership walk stays the financial one;
fiscal-unit membership is the new query). `kontor.statute` (zero
evaluator change). `kontor.report/marginalize` (gains a dimension
extractor for `:tax-group`, one line).

**Existing primitives that DO change**:
`kontor.period-tax-provider`'s docstring + record shape (additive).
`TaxReturnFacts` constructor gains a `:fiscal-unit` arg.

**Trade-off**: middle ground. Schema lives where it's reused
(`:fiscal-unit` is referenced by both `:posting/tax-group` and by the
provider protocol — companion-shaped storage would force every read to
go through `FiscalUnitSource`, which is over-engineered for what is
fundamentally a per-tenant configuration). Companion-tier discipline
preserved for regime-specific behaviour. The consumer wires one entity
tree + the fiscal-unit refs and is done.

**Why C beats A**: A bakes the financial-family-IS-the-fiscal-unit
assumption into `kontor.consolidation`. C keeps the two membership
graphs separate (correct for §1502).

**Why C beats B**: B's `FiscalUnitSource` protocol layer is unnecessary
overhead for a concept that 95 % of tenants will keep in-DB anyway. The
disposal-source protocol made sense because disposals can come from
external feeds (1099-B, HMRC CGT import); fiscal-unit elections are
*always* the tenant's own configuration. Protocol for protocol's sake
isn't kontor's discipline.

---

## §5. Sequencing call

If Gap #8 ships under Option C, the order:

1. **Kernel `:fiscal-unit/*` schema + family-relation refs** (§2.1).
   Schema additions; install the closed regime enum (initially
   `#{:de-organschaft :fr-integration :us-1502 :jp-tsuusan}` —
   ADR-extension thereafter). Update `core/install-schema!`. Add
   `kontor.fiscal-unit/members` + `regime-of` + `eligibility-at` helpers.
   No provider change; financial-consolidation tests unchanged.

2. **Provider-protocol extension** (§2.6).
   `period-tax-facts` ctx now documents `:fiscal-unit` (and a `:tax-group`
   alias). `TaxReturnFacts` gains `:fiscal-unit`. All existing providers
   default `:fiscal-unit nil` (single-entity behaviour unchanged); add
   one regression test per existing provider proving the default path
   doesn't regress (DE / FR / CA / JP / BR / IN CIT providers).

3. **`:transaction/elimination-*` extensions + `tax-eliminate-intercompany-pair-tx-data`** (§2.4).
   Enum + ref additions; new builder; new `kontor.fiscal-unit/tax-consolidate!`
   orchestrator that mirrors `kontor.consolidation/consolidate!` but
   delegates to the tax-eliminate builder. Both orchestrators co-exist;
   the consumer runs both (financial close + tax consolidation).

4. **Pilot regime: DE Organschaft** (closest to existing 4-component DE
   CIT; note 164 provides the ADR-101 statute data). Validates the
   single-base path. Single-currency, all members DE, KSt + GewSt + Soli
   stack composes with the existing DE CIT provider with minimal change.

5. **Second pilot: US §1502** (note 165 provides the deferred-and-matching
   pattern). Validates §2.4's deferral style and §2.3's SRLY loss-bucket
   extension. Multi-state allocation reuses the existing `:analytic-account/state`
   pattern from `kontor-payroll-us-adp` (ADR-077).

6. **JP group-tsuusan** (per-member-filing-with-netting — validates the
   "second style" support per §2.5). Validates the orchestrator's
   N-calls-then-netting branch.

7. **FR intégration fiscale** (single-base like DE, plus déneutralisation
   variant). Validates §2.4's `:neutralisation` style.

8. **UK, CA, BR, IN, MX, CN, AT, AU fan-out** — each follows the
   per-stage pattern (note 163 / etc.) once substrate is locked. Parallel
   per-country agents.

**Parallelisable**: steps 1-3 are sequential (substrate). Steps 4-5-6-7
are sequential because each validates a substrate dimension that the
others don't fully exercise (single-base; deferred-matching; per-member-netting;
denetting). Step 8's per-country jurisdictions parallelise via the
agent pattern.

---

## §6. License + sourcing posture

This note describes kontor's own substrate against its own ADRs and
research notes. No external code, no proprietary documentation. All
citations are to:
- `src/kontor/*.clj` (kernel, EPL-1.0)
- `modules/*/src/kontor/**/*.clj` (companions + l10n, EPL-1.0)
- `doc/decisions.md` (ADRs in-tree)
- `doc/research/*.md` (research notes in-tree)

Notes 164 + 165 (the regime-specific reference studies running in parallel)
will cite gesetze-im-internet.de (DE; public-domain statutory text), the
Legifrance / BOFiP (FR; public statutory text + administrative doctrine),
the IRC + Treasury Regulations (US; public-domain), and the 国税庁 Law
Library (JP; public statutory text) — all clean-room reference, no
proprietary source. License posture unchanged from existing CIT providers
(ADRs 104, 105, 106, 107).

---

## §7. Open questions for the synthesizing ADR

1. **Namespace name.** `:fiscal-unit/*` (recommended) vs.
   `:consolidated-group/*` (US-coloured) vs. `:tax-affiliation/*`
   (edge-framing). Picking `:fiscal-unit/*` does not preclude per-regime
   aliases in providers.

2. **Closed regime enum vs. open keyword.** Note 101 discipline says
   closed-by-ADR. v1 enum: `#{:de-organschaft :fr-integration :us-1502
   :jp-tsuusan}`. Adding regime is an ADR addendum.

3. **`:posting/tax-group` vs. derived.** Whether to stamp the fiscal-unit
   on every posting at write-time (faster reads, harder to change) or
   derive at marginalize-time from the `:fiscal-unit-member` + `:posting/entity`
   join (slower reads, election changes for free). Recommend derived for
   v1 — election can change and the historical stamp is wrong; revisit if
   performance dictates.

4. **Where does the netting orchestrator live?** Option C puts it in
   `kontor.fiscal-unit/tax-consolidate!`. If the substrate later needs to
   compose tax-consolidation with financial-consolidation (e.g. consolidate
   tax across financially-consolidated subgroups), a shared
   `kontor.consolidation/run-consolidations!` umbrella may want to land —
   not for v1.

5. **`kontor-commitment` interaction.** A tax-consolidation regime's
   provision creates a commitment (the tax payable) on the *fiscal unit's
   parent*, not the individual member. The commitment companion (ADR-098)
   already accepts arbitrary `:counterparty` — pointing it at the fiscal
   unit parent's entity is mechanically fine; what's new is that the
   commitment's *origin* is a `TaxReturnFacts` whose `:entity` is the
   fiscal unit (not a real legal entity). Document the pattern; no schema
   change needed.

6. **Pillar 2 (GloBE) interaction.** Pillar 2 is a *floor* on the
   group's effective tax rate computed across all members in a
   jurisdiction. Note 102 §4 deliberately defers Pillar 2 to a
   `kontor-tax-provision` companion (ADR-073 §"What this primitive is
   NOT"). Gap #8 should NOT try to subsume it; the fiscal-unit substrate
   is the *enabler* for a future GloBE provider (which would consume
   `TaxReturnFacts` across members + compute the top-up), not the
   provider itself.

7. **The "second-axis" question.** Many regimes have a *separate*
   consolidation for VAT/GST (the existing `:tax-group/*` VAT concept,
   §1.9) vs. for income tax (the new `:fiscal-unit/*`). Some
   jurisdictions allow VAT grouping with a different member set than
   the income-tax group. The substrate must NOT assume one fiscal unit
   per entity; an `:entity` can participate in MULTIPLE
   `:fiscal-unit`s (one per tax category). `:fiscal-unit-member` as a
   join (not a one-to-one ref) handles this naturally; flag it
   explicitly in the ADR.

---

## §8. Closing — what this note is and is NOT

This note **does NOT**:
- Pick a regime-specific design (DE single-base vs. US deferred-matching
  vs. JP per-member-netting) — that's notes 164 + 165's job.
- Estimate the per-regime implementation cost — same.
- Make the call between Options A/B/C — that's the synthesizing ADR's job
  (this note recommends C with rationale).
- Update note 102 §4 — it stands as the historical claim; this note
  is the audit of that claim and the basis for revising it in the
  Gap #8 ADR's "decision context" section.

This note **does**:
- Verify, file:line, what existing kontor substrate carries vs. doesn't.
- Rank the missing pieces by structural depth.
- Frame the design space for the synthesizing ADR.
- Sequence the substrate work so 164 + 165's regime-specific findings can
  populate steps 4-7 without further substrate churn.

The synthesizing ADR — call it ADR-108 by current numbering — will pick
Option C (or argue for A/B), name the schema attrs, draft the
`kontor.fiscal-unit` namespace's API, and reference this note as the
basis. Implementation follows the per-stage rhythm in `CLAUDE.md` —
research-before (164 + 165 + this note), implement (kernel + DE pilot),
review-after.

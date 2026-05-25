---
date: 2026-05-25
title: 167 — Gap #8 tax-group substrate synthesis — `:fiscal-unit` (ADR-108 draft)
audience: maintainer + the Gap #8 implementation agents
status: synthesis of notes 164 / 165 / 166 — converged recommendation, awaiting maintainer sign-off
---

# 167 — Fiscal-unit substrate synthesis

Three research-before notes landed for Gap #8 (group-tax consolidation):

- **Note 164** — DE Organschaft (KStG §14 ff., GewStG §2 Abs. 2 Satz 2) + FR
  intégration fiscale (CGI Art. 223 A ff.). Two civil-law European regimes
  that compute a single group base; parent files all, subsidiaries file zero.
- **Note 165** — US §1502 (Treas. Reg. §1.1502-x) + JP group-tsuusan
  (法人税法, post-2022 rewrite of 連結納税). US matches DE/FR's "single base"
  shape; JP keeps per-member filings PLUS statutorily-mandated intra-group
  cash settlement for loss-sharing. **JP is the structural outlier**.
- **Note 166** — internal substrate gap analysis. Verdict on note 102 §4's
  "mostly substrate that already exists" claim: **40 % accurate**. Financial
  consolidation, family walk, `marginalize`, FX, statute evaluator, and
  process orchestrator all extend cleanly; the remaining 60 % needs new
  primitives (fiscal-unit entity + membership graph + elimination-style
  extension + per-member loss buckets + protocol-ctx extension).

This note synthesizes all three into a concrete substrate design — the draft
of what will become **ADR-108: `:fiscal-unit` substrate for tax-group
consolidation**.

## §1. The converged design

### 1.1 Naming — `:fiscal-unit/*`, not `:tax-group/*`

Note 166 §1.9 surfaced a critical naming collision: `:tax-group/*` is already
taken by `src/kontor/schema.clj` for **VAT bucket-pair** purposes (UStG §2
Abs. 2 Nr. 2 / EU VAT grouping — a structurally unrelated concept that maps
a multi-entity bucket onto a single VAT registration). A new corporate-tax
namespace using `:tax-group/*` would silently shadow the VAT meaning.

We use **`:fiscal-unit/*`** — the OECD/IFRS term used in IAS 12 §A22
("a tax consolidation regime treats a group of entities as if they were a
single taxable entity ('fiscal unit')"). Matches FR's "unité fiscale" usage.
DE Organschaft and US §1502 both fit the term; JP group-tsuusan less directly
but the regime label `:jp-group-tsuusan` distinguishes the shape.

### 1.2 The substrate decision — Option C (as refined by note 166)

All three notes' option lists converged on a middle ground:

| Note | Recommendation |
|---|---|
| 164 | Weak prior toward Option B (new companion); deferred to 166 |
| 165 | Option A or B for v1; Option C long-term — flags JP as structurally distinct |
| 166 | **Option C** — kernel `:fiscal-unit/*` namespace + thin `kontor.fiscal-unit` ns |

The reasoning chain that produces C as the winner:

1. **A fails**: financial consolidation membership (`kontor.entity/family`)
   is NOT the same as tax-consolidation membership. US §1502 only
   consolidates *domestic* members even when financial consolidation
   includes foreign subs. DE Organschaft requires an EAV contract; FR
   intégration requires 95 % capital test + annual option. Bolting tax
   onto `kontor.consolidation` bakes the wrong assumption.
2. **B fails**: a `FiscalUnitSource` protocol layer (mirroring `DisposalSource`
   per ADR-103) is overhead for a concept that 95 % of tenants will store
   in-DB anyway. Per-tenant configuration belongs in the schema, not behind
   a protocol indirection.
3. **C wins**: minimal schema in the kernel where it's reused by every
   ADR-101 CIT provider; companion-tier discipline preserved for *regime-
   specific behaviour* (which ships as ADR-101 `:provision`/`:regime` data
   in the existing `l10n-{de,fr,us,jp}` modules); the financial-consolidation
   primitive (ADR-073) is untouched.

### 1.3 Two regime shapes, one substrate

The substrate MUST support both regime shapes by design:

| Shape | Regimes | Compute pattern |
|---|---|---|
| **Single-base** | DE / FR / US / pre-2022 JP | parent files one consolidated return; subsidiaries file zero; one liability |
| **Per-member-with-netting** | post-2022 JP group-tsuusan | each member files its own return; intra-group loss-sharing settled in cash at year-end; N liabilities + N intra-group cash settlements |

Note 165 was emphatic: **JP cannot be collapsed into Shape A**. The ¥46.4M
intra-group settlement in note 165 §4.2's worked example is statutorily
mandated, not optional. Any substrate that picks only Shape A fails JP.

The decision: the `:fiscal-unit/regime` attribute carries
`:fiscal-unit/computation-style :single-base | :per-member-with-netting`
(closed-by-ADR enum); each provider reads this and branches its return
arity accordingly.

## §2. Schema delta

Three additive groups in the kernel; one extension to an existing companion.

### 2.1 `:fiscal-unit/*` — the consolidating election entity

```edn
{:fiscal-unit/code           {:db/unique :db.unique/identity ; "DE-HansTech-Organschaft-2026"
                              :db/valueType :db.type/string}
 :fiscal-unit/name           {:db/valueType :db.type/string}
 :fiscal-unit/parent-entity  {:db/valueType :db.type/ref   ; → :entity (Organträger / tête / common parent)
                              :db/cardinality :db.cardinality/one}
 :fiscal-unit/regime         {:db/valueType :db.type/keyword
                              ; closed enum, per-ADR: :de-organschaft :fr-integration
                              ; :us-1502 :jp-group-tsuusan :uk-group-relief …
                              :db/cardinality :db.cardinality/one}
 :fiscal-unit/computation-style {:db/valueType :db.type/keyword
                                 ; closed enum: :single-base | :per-member-with-netting
                                 :db/cardinality :db.cardinality/one}
 :fiscal-unit/elected-from   {:db/valueType :db.type/instant}
 :fiscal-unit/elected-until  {:db/valueType :db.type/instant}  ; nil = open-ended
 :fiscal-unit/minimum-term-ends {:db/valueType :db.type/instant} ; DE EAV 5y; US permanent → nil
 :fiscal-unit/active         {:db/valueType :db.type/boolean}
 :fiscal-unit/anchor-document {:db/valueType :db.type/ref}   ; → :audit-doc (EAV, election form)
 :fiscal-unit/status         {:db/valueType :db.type/keyword}
 ; status rides ADR-034 status-machine: :proposed → :elected → :active
 ; → :exiting → :exited | :voided-retro (DE retroactive break case)
}
```

### 2.2 `:fiscal-unit-member/*` — the membership join

```edn
{:fiscal-unit-member/fiscal-unit   {:db/valueType :db.type/ref}     ; → :fiscal-unit
 :fiscal-unit-member/entity        {:db/valueType :db.type/ref}     ; → :entity
 :fiscal-unit-member/role          {:db/valueType :db.type/keyword
                                    ; :parent | :sub
                                    :db/cardinality :db.cardinality/one}
 :fiscal-unit-member/ownership-fraction {:db/valueType :db.type/bigdec}
 :fiscal-unit-member/joined-on     {:db/valueType :db.type/instant}
 :fiscal-unit-member/left-on       {:db/valueType :db.type/instant}  ; nil = still in
}
```

**Why a join, not a multi-cardinality ref**: per note 166 §1.9 finding 3,
entities can participate in MULTIPLE fiscal units simultaneously — e.g.
one for VAT grouping (UStG §2 Abs. 2 Nr. 2), one for income-tax grouping
(KStG §14), one for trade-tax grouping (GewStG §2). Membership is
many-to-many.

### 2.3 `:transaction/elimination-style` — second elimination kind

Existing `:transaction/consolidation-kind` enum (schema.clj:3805-3810) carries
the financial-consolidation kinds. Extend with tax-specific kinds:

```edn
; Existing values plus:
:tax-elimination       ; permanent removal (FR Art. 223 B dividendes intégrés;
                       ; DE EAV-transferred profit attribution)
:tax-deferral          ; deferred until externalisation (US §1502-13
                       ; deferred-and-matching; FR Art. 223 F déneutralisation
                       ; until disposal)
:tax-neutralisation    ; rate-reduced at group level (FR Art. 223 B quote-part
                       ; 5 % → 1 %; DE §15 KStG Bruttomethode adjustment)
```

Plus three new attributes for the deferral lifecycle:

```edn
{:transaction/elimination-style  {:db/valueType :db.type/keyword
                                  :db/cardinality :db.cardinality/one}
 :transaction/elimination-reversal-trigger {:db/valueType :db.type/keyword
                                            ; :externalisation | :regime-exit
                                            ; | :asset-disposal | :member-exit
                                            :db/cardinality :db.cardinality/one}
 :transaction/elimination-components {:db/valueType :db.type/ref
                                      :db/cardinality :db.cardinality/many}
 ; → :transaction (the deferred items being eliminated; for crystallisation walk)
}
```

### 2.4 `:posting/tax-group` — the marginalisation axis (deferred to v1.1)

The `marginalize` engine (ADR-096) needs an axis to slice postings by
fiscal-unit membership. Two design choices:

- **Option D1 (v1)**: derive at query time. `marginalize` accepts a
  `:fiscal-unit <eid>` filter that joins through `:posting/entity` →
  `:fiscal-unit-member`. Zero schema add.
- **Option D2 (v1.1)**: denormalize as `:posting/tax-group ref → :fiscal-unit`,
  stamped at posting time by `kontor.book` when the entry's entity is a
  fiscal-unit member. Mirrors `:posting/entity` (ADR-031); faster queries;
  needs an `install-fiscal-unit-stamper!` middleware.

**v1 ships D1**; v1.1 can promote to D2 if benchmarks demand it. The
substrate-fit recommendation is to leave the door open by reserving
`:posting/tax-group` in the attribute registry without yet writing
the stamper.

### 2.5 Loss-bucket extension to `kontor-disposal`

Pre-affiliation losses are scoped per-member per-fiscal-unit:

- DE pre-Organschaft losses (KStG §15 Satz 1 Nr. 1) frozen against
  parent-level usage; usable only against the same Organgesellschaft's
  post-termination income.
- US SRLY (Treas. Reg. §1.1502-21(c)) — pre-affiliation NOLs of an
  acquired sub usable only against that sub's own contribution to
  consolidated income.
- JP CTA Art. 57 §8 — same shape as SRLY for pre-tsuusan losses.
- FR Art. 223 I (1° du 4) — déficits pré-intégration boxed.

These all share **one primitive shape**: per-member NOL provenance tag
+ cumulative-since-affiliation scoreboard (note 165 §6 explicitly
calls this out as a single-primitive opportunity).

Extension to the **kontor-disposal** companion (`modules/disposal/`)
because it already has `:loss-bucket` for capital-loss carryforward
(ADR-102 / ADR-103 / CGT providers):

```edn
; In disposal companion's loss-bucket schema:
{:loss-bucket/fiscal-unit-scope {:db/valueType :db.type/ref
                                 ; → :fiscal-unit (nil = unscoped / regular)
                                 :db/cardinality :db.cardinality/one}
 :loss-bucket/pre-affiliation?  {:db/valueType :db.type/boolean}
 ; if true, the bucket is unavailable for cross-member offset within the unit
}
```

This is **deferrable to v2**: v1 can ship without pre-affiliation NOL
tracking by documenting it as "regime supported; pre-affiliation losses
require manual disclosure". Most v1 consumers don't acquire mid-year.

## §3. Protocol delta

### 3.1 `PeriodTaxProvider` ctx extension

`src/kontor/period_tax_provider.clj`:

```clojure
(defprotocol PeriodTaxProvider
  "...
   ctx keys (additive):
     :db          - datahike db value (required)
     :entity      - the filing entity (required for single-entity scope)
     :fiscal-unit - alternative scope for group filings (optional; mutually
                    exclusive with :entity for single-base regimes; both
                    set for per-member-with-netting regimes where the
                    provider runs for each :entity within :fiscal-unit)
     :period      - {:from <inst> :to <inst>}
     :tax-unit    - per-jurisdiction facts (entity attributes, e.g.
                    {:hebesatz 490 :ccpc? true :pme? false})
     :inputs      - per-period facts (income, deductions, etc.)"
  (period-tax-facts [provider ctx]
    "Returns TaxReturnFacts. Behaviour with :fiscal-unit:

     - :single-base regimes (DE/FR/US): the provider is called ONCE on
       the parent entity with :fiscal-unit set. It marginalises member
       income via kontor.report/marginalize, applies group-level
       provisions, returns ONE TaxReturnFacts with :components reflecting
       the consolidated liability (subsidiaries' zero returns are
       generated as separate calls without :fiscal-unit).

     - :per-member-with-netting regimes (JP): the provider is called
       PER MEMBER with both :entity and :fiscal-unit set. It computes
       the member's own base, then a post-pass loss-allocation
       provision settles inter-entity flows. The orchestrator
       (kontor.fiscal-unit/run-group-tax!) aggregates and emits the
       intra-group cash settlements."))
```

### 3.2 `TaxReturnFacts` record extension

`src/kontor/period_tax_provider.clj`:

```clojure
(defrecord TaxReturnFacts
  [;; existing fields:
   period authority filing-currency components inputs
   ;; new:
   fiscal-unit             ; ref or nil — the unit this filing belongs to
   intra-group-settlements ; vec of {:from-member :to-member :amount} or nil
                           ; populated only by :per-member-with-netting regimes
])
```

The `intra-group-settlements` field is `nil` for single-base regimes and a
vector for per-member-with-netting regimes (JP). The
`TaxReturnPostingBuilder` (`src/kontor/tax_return_posting_builder.clj`)
emits these as intercompany postings between members when present.

### 3.3 New namespace — `src/kontor/fiscal_unit.clj`

Thin kernel namespace (~150 LOC):

```clojure
(ns kontor.fiscal-unit
  "Fiscal-unit substrate for tax-group consolidation.
   See ADR-108 (note 167 synthesis). Two regime shapes supported:
   :single-base (DE/FR/US) and :per-member-with-netting (JP group-tsuusan)."
  (:require [datahike.api :as d]
            [kontor.entity :as entity]
            [kontor.period-tax-provider :as ptp]
            [kontor.status-machine :as sm]))

(defn members
  "All :fiscal-unit-members for the unit at :as-of (default now).
   Honours :joined-on / :left-on bitemporal windows."
  [db fiscal-unit & {:keys [as-of role]}] ...)

(defn member-entities
  "Convenience: returns :entity eids only."
  [db fiscal-unit & opts] ...)

(defn elect-tx-data
  "Builds the tx-data for a new election. ADR-068 builder convention."
  [{:keys [code name parent-entity regime computation-style
           elected-from minimum-term-ends anchor-document
           members]}] ...)

(defn elect!
  "ADR-068 ! wrapper — routes through transact-with-validation."
  [conn opts] ...)

(defn exit-tx-data [{:keys [fiscal-unit exit-date trigger]}] ...)
(defn exit!         [conn opts] ...)

(defn run-group-tax!
  "Orchestrate a group-tax computation. Branches on
   :fiscal-unit/computation-style:
     - :single-base — call provider once on parent with :fiscal-unit set
     - :per-member-with-netting — call provider per member, then run
       inter-entity loss-allocation pass + emit settlements
   Returns: {:filings [TaxReturnFacts ...] :settlements [{...} ...]}"
  [conn {:keys [fiscal-unit period provider]}] ...)
```

## §4. What ships in v1, what doesn't

### 4.1 v1 scope (the substrate + 1 pilot)

- All §2 schema additions (`:fiscal-unit/*`, `:fiscal-unit-member/*`,
  `:transaction/elimination-style` + reversal-trigger + components,
  closed-enum extensions, status-transition seeds for `:fiscal-unit/status`).
- §3 protocol + record extensions.
- `src/kontor/fiscal_unit.clj` (thin orchestrator).
- One pilot regime: **DE Organschaft** (closest to the existing DE CIT
  provider — KStG §15 Bruttomethode adjustments fit naturally as
  ADR-101 `:provisions` keyed on `:fiscal-unit/regime :de-organschaft`).
- Sanity test: a 3-entity DE Organschaft (Organträger + 2
  Organgesellschaften with +€2M / +€500k / −€1M) producing one
  consolidated KSt + Soli + GewSt to the cent.

### 4.2 v1.1 scope (the second pilot)

- **US §1502** — proves the `:tax-deferral` elimination-style + the
  §1502-13 deferred-item lifecycle. Will surface whether
  `:transaction/elimination-components` carries the multi-year
  acceleration correctly.

### 4.3 v1.2 scope (the structural-outlier pilot)

- **JP group-tsuusan** — proves the `:per-member-with-netting`
  computation-style + the `intra-group-settlements` emission. This is
  the test of the design: if the substrate can carry both DE's
  single-base and JP's per-member-with-netting without forking the
  protocol, the design is right.

### 4.4 v2 scope

- **FR intégration fiscale** — déneutralisation crystallisation on
  exit (Art. 223 F) needs the `:tax-deferral` machinery already
  exercised by US v1.1.
- **Pre-affiliation NOL buckets** — the kontor-disposal extension
  in §2.5.
- **Full §1502-13 deferred-and-matching** — v1.1 ships the skeleton;
  full multi-year matching/acceleration is a v2 deepening.
- **CA / UK / IN / BR / MX / AT / AU / CN** regimes — fan out, each
  as a small `:provision`/`:regime` data set + minimal provider hook.
- **Pillar Two interactions** — explicitly out of scope; CAMT,
  GloBE / jp-Min-Tax are separate regimes against a different
  perimeter (note 165 §6.4).

### 4.5 Not v1 — explicit non-goals

- Pillar Two compute (CAMT, GLOBE, jp-Min-Tax)
- US §382 ownership-change limitation
- VAT grouping (UStG §2 Abs. 2 Nr. 2) — distinct concept, distinct
  schema (`:tax-group/*` namespace is reserved for VAT)
- Insurance / financial-institution sub-consolidation regimes
- Several-liability projection (out of v1; recordable via the
  members + status query)

## §5. Sequencing (revised from notes 164 / 166 §5)

1. **Kernel substrate** — §2 + §3 (the schema + protocol + thin ns).
   One ADR (ADR-108). Acceptance: schema tests pass; status-transition
   seeds installed; `members` / `elect-tx-data` / `exit-tx-data` round-trip.
2. **DE Organschaft pilot** — `modules/l10n-de/src/kontor/l10n_de/
   organschaft_statute.clj` + `organschaft_provider.clj` + tests.
   Reuses ADR-104's `cit_provider`. Acceptance: 3-entity worked example
   to the cent.
3. **US §1502 pilot** — `modules/l10n-us/src/kontor/l10n_us/
   consolidated_return_statute.clj` + provider + tests. Acceptance:
   3-entity worked example + a `:tax-deferral` intercompany sale
   that defers correctly across the year boundary.
4. **JP group-tsuusan pilot** — `modules/l10n-jp/src/kontor/l10n_jp/
   group_tsuusan_statute.clj` + provider + tests. Acceptance: 3-entity
   worked example with intra-group cash settlements emitted to the yen.
5. **Review-after** — per ADR-037: spawn code-review + market-pain
   agents. The audit covers all 3 pilots; any P0 closes before fanout.
6. **FR / UK / CA / IN / BR / MX / AT / AU / CN fan-out** — per-regime
   in parallel agents, each ~200 LOC `:provision`/`:regime` data.

Steps 3 + 4 can run in parallel after step 2 lands.

## §6. The findings the implementer must NOT lose

These come out of the research notes; flagging them here so they don't
get re-discovered:

- **F1** (note 164 §3) — DE retroactive termination vs FR forward-only
  termination both must work. Use bitemporal `:tx/valid-from` for the
  DE retroactive-void case; FR is just the absence of retroactive
  void.
- **F2** (note 164 §3.1) — DE Bruttomethode (`:tax-neutralisation`)
  is rate-reduced at group level, not eliminated. Don't conflate
  with `:tax-elimination`.
- **F3** (note 164 §2.5) — FR 5 % → 1 % quote-part for inside-group
  dividends — a `:provision` with `:predicate {:both-in-fiscal-unit?
  true}` keyed on `:fr-integration` regime.
- **F4** (note 165 §1) — US §1502-13 deferred items have a multi-year
  state machine (the buyer's eventual sale-out-of-group triggers
  recognition). The `:transaction/elimination-components` ref-many
  carries the matched items; the orchestrator walks them at the
  externalisation event.
- **F5** (note 165 §2 + §6) — JP's intra-group cash settlement is
  STATUTORILY MANDATED, not optional. The `intra-group-settlements`
  field on `TaxReturnFacts` is required-non-nil for the JP regime.
- **F6** (note 165 §3) — SRLY (US) and CTA Art. 57 §8 (JP) share
  the SAME primitive shape — implement once in §2.5's
  loss-bucket extension.
- **F7** (note 166 §1.9) — `:tax-group/*` is RESERVED for VAT
  bucket-pair concept. Do not reuse. Use `:fiscal-unit/*`.
- **F8** (note 166 §1.9) — entities can belong to multiple fiscal
  units simultaneously. Membership is a join, not a ref.
- **F9** (note 166 §1.9) — `kontor.statute/apply-provisions` is
  transparent to `:fiscal-unit` in ctx today. Zero evaluator change
  required.
- **F10** (note 165 §6.4) — Pillar Two regimes have a DIFFERENT
  perimeter (US CAMT: §52 controlled-group 50 % threshold;
  JP Min-Tax: MNE ultimate parent perimeter). Don't foreclose
  multi-perimeter modeling — the same entity may belong to one
  `:fiscal-unit` for §1502 AND another for CAMT.

## §7. Token-cost estimate

| Step | LOC | Test count | Notes |
|---|---|---|---|
| 1. Kernel substrate | ~250 LOC schema + ~150 LOC fiscal-unit.clj + ~200 LOC tests | ~12 deftests / ~40 assertions | One ADR-108 |
| 2. DE Organschaft pilot | ~400 LOC statute + ~200 LOC provider + ~350 LOC tests | ~10 deftests / ~50 assertions | Reuses ADR-104 |
| 3. US §1502 pilot | ~500 LOC statute + ~250 LOC provider + ~400 LOC tests | ~12 deftests / ~60 assertions | First `:tax-deferral` consumer |
| 4. JP group-tsuusan pilot | ~450 LOC statute + ~300 LOC provider + ~400 LOC tests | ~12 deftests / ~60 assertions | First per-member-with-netting consumer |
| 5. Review-after | ~3 agents | n/a | Per ADR-037 |
| 6. Fan-out (9 regimes) | ~200 LOC × 9 = ~1800 LOC | ~8 × 9 = ~72 deftests | Parallel |

**v1 total** (substrate + 3 pilots + review): ~3,800 LOC code + ~80 tests / ~360 assertions.
**v2 fan-out**: ~1,800 LOC + ~72 tests.

Comparable in scale to Gap #3 (BR + IN CIT) which shipped at 2,400 LOC + 26
tests, but with the substrate cost amortised over the eventual 12-jurisdiction
fan-out.

## §8. Open questions for the maintainer

Before implementation kicks off, three calls worth flagging:

1. **Q1** — Pre-affiliation NOL buckets in v1 or v2? Note 165's SRLY
   pattern is so well-defined that shipping it in v1 (with the
   kontor-disposal extension in §2.5) is feasible. Cost: ~200 LOC + 8
   tests. Benefit: M&A scenarios in the v1.x pilots become correct.
   Recommendation: **v1.1** (with US §1502 pilot, since SRLY is the
   canonical example).

2. **Q2** — Denormalise `:posting/tax-group` in v1 (Option D2) or
   keep it derived (Option D1)? D1 is correct but slower; D2 is faster
   but adds a stamper middleware to `kontor.book`. Recommendation: **D1
   in v1**, promote to D2 in v2 if benchmarks show >5 % overhead on
   group-tax reports.

3. **Q3** — `:fiscal-unit/regime` closed enum: do we close it now to
   the 11 known regimes (`:de-organschaft`, `:fr-integration`,
   `:us-1502`, `:jp-group-tsuusan`, `:uk-group-relief`, `:ca-srtg`,
   `:br-deferred`, `:in-not-applicable`, `:mx-grupo`, `:at-gruppe`,
   `:au-tcg`, `:cn-cct`), or leave open with per-ADR closing?
   Recommendation: **closed-by-ADR-108** (the same pattern as the
   period-tax-kinds enum in ADR-099 §9). New regimes require an ADR
   addendum.

## §9. Next step

Maintainer review of this synthesis. On sign-off, three new tasks land:

- ADR-108 formalisation (substrate)
- DE Organschaft pilot (substrate consumer #1)
- Review-after agents (per ADR-037)

The three reference notes (164 / 165 / 166) become permanent — they're
the citation source for the per-regime provisions that l10n modules will
encode.

## §10. References

- Note 102 §4 — original Gap #8 mention ("multinational rung is mostly substrate
  that already exists" — verdict: 40 % accurate)
- Note 164 — DE Organschaft + FR intégration fiscale reference (1174 LOC)
- Note 165 — US §1502 + JP group-tsuusan reference (1219 LOC)
- Note 166 — internal substrate gap analysis (854 LOC)
- ADR-031 — `:entity` family
- ADR-034 — status-machine
- ADR-068 — `*-tx-data` builders
- ADR-073 — `kontor.consolidation` (financial; NOT touched by this work)
- ADR-095 — `kontor.book` verb facade
- ADR-096 — `marginalize` / σ_E
- ADR-097 — `:posting/dimension`
- ADR-099 — `PeriodTaxProvider` substrate
- ADR-101 — statute-as-data (`:tax-concept` / `:provision` / `:regime` /
  `:parameter`)
- ADR-103 — `DisposalSource` protocol (reference pattern for what NOT to do —
  per note 166's Option B rejection)
- ADR-104 — DE CIT (the existing consumer that the DE Organschaft pilot extends)

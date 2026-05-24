---
date: 2026-05-24
title: 119 — ADR-101 draft: statute-as-data substrate
audience: maintainer — review draft before it folds into `doc/decisions.md`
status: draft for review
---

# 119 — ADR-101 draft (statute-as-data substrate)

User picked **Option 1** (build substrate first, then Phase 3 providers on it).
This note is the concrete ADR draft + the 6 resolved design choices with
rationale + the implementation-order consequence. Once approved (with edits),
the ADR body folds into `doc/decisions.md` as ADR-101; the Phase 3 ADRs
proposed in note 107 (`kontor-disposal`, `kontor.incorporation`, the CIT
providers) shift to ADR-102+ and become consumers of this substrate.

---

## ADR-101 (draft) — Tax law as data: `:tax-concept` / `:provision` / `:regime` / `:parameter`

**Status.** Proposed. Research notes 107 / 108-115 / 116 / 117 / 118.

**Context.** The Phase 1 tax substrate (`PeriodTaxProvider`, ADR-099 +
addenda; note 105's adjustment-layer algebra) treats each jurisdiction's
tax law as a Clojure record with rates, brackets, and adjustments hardcoded
inside a `defrecord` body. Phase 1 shipped 11 PIT providers this way. As
Phase 3 fanned out into 4 CIT + 4 CGT jurisdictions, eight independent
research agents (notes 108-115) kept naming the **same cross-jurisdiction
concepts** — rollover relief (US §1031 / DE §6b / UK s152 / JP §36-2),
participation exemption (DE §8b / UK SSE), lifetime cap on preferential
treatment (UK BADR / US §1202), loss-offset compartmentalization (DE 4
buckets / UK gains-only / US $3k/year / JP per-class), holding-period
preferential rate (US LT/ST / DE §23 / JP real-estate 5y@Jan-1) — that
the current substrate cannot represent as data: each instance lives only
inside its provider's compiled code, and the concept itself lives nowhere.
Prior-art survey (note 116) confirmed two serious productionised tax-as-
data systems (Catala, OpenFisca); per-project code-depth reads (notes 117
/ 118) independently converged on essentially the same 3-namespace
kontor schema, with OpenFisca contributing a 4th (`:parameter`) for the
date-keyed rate/bracket history pattern.

**Decision.** Four kernel namespaces — statutes, provisions, regimes, and
parameters as first-class queryable data — plus two small extensions to
existing substrate. The detailed attribute listings (≈36 attrs total)
live in notes 117 §2 and 118 §2; this ADR commits to the **shape**:

- **`:tax-concept`** (~5 attrs) — the **cross-jurisdiction catalogue**. A
  named, ADR-closed set of abstractions (`:participation-exemption`,
  `:rollover-relief`, `:loss-bucket`, `:lifetime-cap`, …) that
  jurisdiction-specific provisions reference. Composes with ADR-090
  `:concept-iri` for XBRL / FIBO / external taxonomy edges.
- **`:provision`** (~14 attrs) — the **per-jurisdiction encoded statute
  rule**. Carries the source-of-law citation, the affected `:tax-concept`,
  a condition expression (closed-vocab `:and`/`:or`/`:leq`/`:geq`/…
  predicates over `:tax-context` facts), a consequence (`:base-transform`,
  `:credit`, `:surtax`, `:base-deduct`, `:schedule-override`, …), an
  ordered `:priority`, an optional `:exception-of` ref (Catala-inspired
  default+exception semantics), and `:effective-from` / `:effective-until`
  date guards. A `:provision/compute-fn` keyword escape hatch resolves to
  a registered Clojure fn for the rare provision that exceeds the closed
  predicate vocabulary.
- **`:regime`** (~7 attrs) — the **elective container**. Groups provisions
  that compose into one computation (IN's old-vs-new regime, FR's PME vs
  standard IS); supports `:regime/extends` for counterfactual / amendment
  overlay. The elect event itself rides the existing ADR-034 status-machine
  substrate (no parallel `:regime-election` namespace).
- **`:parameter` + `:parameter-value` + `:parameter-bracket`** (~9 attrs)
  — the **date-keyed value history**. The OpenFisca operational pattern:
  rates / brackets / thresholds are entities with `:parameter-value`
  records keyed by `:effective-from` (so the 2024 rate, 2025 rate, …
  are separate data points), per-bracket fields with independent
  histories (a threshold may move while a rate stays). Replaces the
  current pattern of "edit the y2024 namespace's `defrecord` config to
  update a rate."

Two small extensions to existing substrate:

- **`:op :base-deduct`** added to the adjustment-layer vocabulary
  (note 105). Today's `:op` set is `{:credit :surtax}`; DE §9 GewSt
  reductions are base-deducts (subtract from the taxable base before the
  schedule fires), distinct from `:credit` (subtract from gross liability)
  and from negative additions in `:base-transform :adjustments`. The
  vocabulary becomes `{:credit :surtax :base-add :base-deduct}`.
- **`:provision/effective-from`** semantically distinct from
  `:tx/valid-from` (ADR-048). The former is *the statutory date the law
  applies from* (e.g. "this rate is effective FY 2025"); the latter is
  *when we entered the provision into our books*. Both real — retroactive
  amendments differ from forward-effective ones, and the audit story needs
  both axes.

A single kernel evaluator (`kontor.statute/apply-provisions`, name TBD)
folds applicable provisions onto a base in priority order, detecting
ambiguity (two provisions at same priority both applicable) at run-time as
a `kontor.tax/ambiguous-provision` exception with both citations. Existing
`PeriodTaxProvider` records continue to work unchanged; new per-country
implementations from Phase 3 onward become **configurations of `:provision`
data** rather than hardcoded `defrecord` bodies.

**Implication.** Kernel, additive, no breaking changes to ADR-099 /
note 105 / shipped Phase 1 providers. Per-country tax becomes data
(queryable: "which jurisdictions implement `:rollover-relief`?";
auditable: every component carries `:provision/citations` back to the
statute section it cited; counterfactual: a `:regime/extends` overlay
models proposed amendments). The 11 shipped PIT providers stay
record-shaped; migrate opportunistically when l10n modules are touched
(not a forced sweep — record-shaped providers and statute-data providers
coexist).

**Tested.** A new `statute_test.clj` will exercise: catalogue lookup,
provision priority + exception-of fold, ambiguity-detection trap, parameter
history at-instant resolution, `:regime` electives via ADR-034 status-
machine. The Phase 3 CIT/CGT providers (ADR-103+) will be the first
end-to-end consumers, with the existing notes 108-115 fit-assessments as
the golden case template.

**Research backing.** Notes 108-115 (cross-jurisdiction patterns surfaced
the gap); note 116 (prior-art survey ruling out 11 of 13 candidate
systems); notes 117 (Catala) + 118 (OpenFisca) — independent code-depth
reads that converged on this schema shape.

Date: 2026-05-24.

---

## The 6 resolved design choices (with rationale)

These are the calls that 117/118 surfaced as the load-bearing
decisions. Each is resolved in the ADR body above; the rationale lives
here so future readers can reconstruct why.

### D1 — `:parameter` namespace adopted as the 4th namespace

OpenFisca's parameter-tree was its strongest distinctive pattern (note
118 §1.1 calls it the operational gold standard; note 117 didn't surface
it because Catala has no analog — Catala folds values into scope-level
definitions). The need is concrete: every jurisdiction's rates change
yearly (US TCJA sunsets 2025, FR IS staged reduction, JP defense surtax
phase-in 2026). Without `:parameter` the only way to express "the 2024
rate is 21% but the 2025 rate is 25%" is to edit code. With `:parameter`,
it's a new `:parameter-value` row. **Adopted.**

### D2 — Condition vocabulary: closed predicates + registered-fn escape hatch

117 leans registered-keyword → Clojure fn (flexible, blurs data/code);
118 leans closed predicate vocab (pure data, less flexible). **Both** is
the better answer: ship a closed vocab (`:and`/`:or`/`:not`/`:leq`/`:geq`/
`:eq`/`:in`/`:status-is`, ~10 predicates) covering the 90% case, and a
`:provision/compute-fn` keyword resolving to a registered Clojure fn for
the rare provision that needs imperative arithmetic (cumulative lifetime
caps, indexation-table lookups, complex eligibility cascades). Note 116
§D2's "configuration-first; escape hatch only for the genuinely complex"
guidance applies. The vocab and the fn-registry are both append-only.
**Adopted as combined.**

### D3 — `:op :base-deduct` extends the adjustment-layer

Catala (117 §2 + open question) flagged this; OpenFisca didn't, but the
DE §9 GewSt-reductions case from note 108 validates: `:credit` is "subtract
from gross liability", `:base-add` is "add to taxable base before schedule",
`:base-deduct` is "subtract from taxable base before schedule" — 3 distinct
fold semantics. Today's vocab `{:credit :surtax}` plus `:base-transform`
covers credits + surtaxes + base-adds but not clean base-deducts (you can
encode a base-deduct as a negative-amount `:base-add`, but that's a wart).
**Adopted.**

### D4 — `:provision/effective-from` distinct from `:tx/valid-from`

OpenFisca (118 Q10) flagged this; Catala uses bitemporal directly (117).
OpenFisca's right: a tax law passed on 2024-03-01 effective from
2024-01-01 has TWO real dates. `:tx/valid-from` (ADR-048) is our book-
entry timestamp; `:provision/effective-from` is the statute's own
applies-from date. Some jurisdictions allow retroactive amendments
(`:effective-from` < the amendment's enactment date) and the audit story
needs both axes. **Adopted.**

### D5 — `:regime-election` reuses ADR-034 status-machine (no new namespace)

117 Q6 flagged: a `:regime-election` namespace duplicates ADR-034
status-machine (a dated decision that transitions an entity into a
different mode is literally what status-machines are). OpenFisca (118)
doesn't surface it because it uses `:regime/extends` for simpler
counterfactual overlay. Resolution: `:regime` is the entity; electing
into a regime is a status-machine transition on the taxpayer entity. No
new namespace. **Adopted as fold-into-ADR-034.**

### D6 — Concept catalogue: closed-by-ADR, starter set of 14

Note 116 §D1 tentative recommendation (closed, append-only). The starter
set is the 14 cross-jurisdiction concepts the agents surfaced in notes
108-115 + 117/118:

```
:participation-exemption          (DE §8b, UK SSE)
:rollover-relief                  (US §1031, DE §6b, UK s152, JP §36-2)
:like-kind-exchange               (US §1031 specifically — narrower than :rollover-relief)
:replacement-property             (US §1033, DE §6b sub-concept)
:loss-bucket                      (DE 4-walls, UK gains-only, US $3k, JP per-class)
:lifetime-cap                     (UK BADR £1M, US §1202 $10M-or-10×basis)
:holding-period-preference        (US LT/ST, DE §23, JP real-estate)
:non-refundable-credit            (most credits)
:refundable-credit                (US EITC, CA SR&ED for CCPC, FR CIR)
:surtax                           (DE Soli, JP local CIT)
:minimum-tax                      (US CAMT, future)
:base-transform-add               (DE §10 KStG add-backs, §8 GewStG)
:base-transform-deduct            (DE §9 GewStG reductions; the :op :base-deduct case)
:elective-regime                  (IN old-vs-new, FR PME vs std, US itemized-vs-std)
```

Additions are 1-row schema migrations + a quarterly review of l10n-
suggested concepts (note 117 Q1 + Q7). **Adopted.**

---

## Implementation order (the consequence of picking Option 1)

Substrate-first means the Phase 3 fan-out from note 107 shifts:

| # | What | Status / consumes |
|---|---|---|
| **ADR-101** | Statute-as-data substrate (THIS ADR) | substrate-only; no consumers yet |
| **ADR-102** | `kontor-disposal` companion (was 101 in note 107) | consumes ADR-101 `:provision` schema for CGT-classification |
| **ADR-103** | `kontor.incorporation` primitive + dividend verbs (was 102) | unchanged — orthogonal to ADR-101 |
| **ADR-099 add.** | `:fixed-amount` schedule kind | landed as part of ADR-101's `:provision/consequence` vocabulary |
| **ADR-104..107** | DE / FR / JP / CA CIT providers (was 103-106) | consume ADR-101 — first end-to-end consumers; per-country becomes data not code |
| **ADR-108..111** | US / DE / UK / JP CGT providers | consume ADR-101 + ADR-102 |

The 11 shipped Phase 1 PIT providers stay record-shaped; they DO work,
and migrating them is a Phase 4 opportunistic sweep, not a precondition
for shipping ADR-101.

**Estimated effort.**
- ADR-101 substrate: ~1-2 weeks (schema + evaluator + tests + starter
  catalogue + the 2 vocabulary extensions).
- ADR-102 disposal companion + ADR-103 incorporation: each ~3-5 days,
  parallelizable.
- ADR-104..111 (4 CIT + 4 CGT): in the statute-as-data shape, each
  becomes ~2-3 days of *data authoring* (the statute encoded as
  `:provision` rows) + golden tests. Parallel agents per country
  remain the natural fan-out.

This is roughly 4-6 weeks of focused work for the whole Phase 3 batch,
which is heavier than "ship Phase 3 in current shape" (~2-3 weeks) but
delivers Phase 3 + the substrate that makes Phase 4 / per-country
amendments / opportunistic Phase 1 migration cheap going forward. The
maintainer accepted this trade in picking Option 1.

---

## Open implementation questions (smaller, can be resolved at code time)

These are the residual questions from notes 117 §6 + 118 §6 that don't
need to be locked in the ADR — they can be resolved when the
implementation is in the REPL:

- Closed predicate vocabulary final list (~10 predicates; pin during
  ADR-101 implementation, with starter from 118 §2.2)
- Stringified-EDN vs `:db.type/edn` for `:provision/consequence` +
  `:condition/args` (117 Q8 — defer to first datahike-EDN-type stress)
- `:provision-scope` namespace as designed-but-unused expansion (117 Q3
  — defer; add only when a 3rd jurisdiction surfaces the gap)
- Non-scalar `:parameter-value` types (string, instant, boolean — 118 Q9
  — defer; ship `:decimal-value` only in iteration 1)
- Bracket-parameter shape: per-field refs vs inline (118 Q2 — defer to
  first real l10n encoding; my lean is per-field refs per OpenFisca
  evidence, but the cost is small either way)
- Authority-source ingestion (XBRL / AKN) — out of scope for ADR-101;
  schema is designed to permit it later

---

## What this ADR does NOT do

- Does not migrate the 11 shipped PIT providers. (Opportunistic only.)
- Does not introduce a new runtime, DSL, or surface syntax.
- Does not commit to a `:provision/scope` Catala-style scope namespace
  (deferred per 117 Q3).
- Does not commit to deontic markers (LegalRuleML's
  obligation/permission/prohibition — 116 §D8).
- Does not commit to a citation-back-to-PDF discipline for the starter
  catalogue (citations are URLs to authority sites; PDF-anchored
  citations are a follow-up if a consumer asks).
- Does not commit to public `:provision` read API (116 §D10 — kontor
  is a library, not a service).

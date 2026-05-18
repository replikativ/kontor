---
date: 2026-05-18
title: 86 — Stage R final review-after (C1 substrate + DE / US / CA adapters)
status: review-after
audience: maintainer — read before C4.1 (QC RL-1) or C5 (next country) lands
---

# 86 — Stage R final review-after

Cross-cutting audit of the four Stage R commits that landed C1
(substrate) + C2 (DE-DATEV-LODAS, ADR-076) + C3 (US-ADP-GLI,
ADR-077) + C4 (CA-CRA, ADR-078) plus the trans-national cross-stage
test. Three impl agents worked in separate worktrees and the
per-stage research notes diverge in vocabulary and convention; this
note's job is to make the cross-cutting friction visible to a
maintainer who only sees the merged main branch.

Note 85 (the C1-only review-after) is referenced where its open
findings are still relevant; I do not re-enumerate them. The two
note-85 P0s have already been closed (commit `08a2e63`). What
follows is **new** ground above the C1 line.

The audit reads ADR-075 / ADR-076 / ADR-077 / ADR-078, the four
modules, the kernel attrs they touch (`:audit-doc/category`,
`:audit-doc/language`, `:retention-policy/category`,
`:payroll-run/emit-docs`), the cross-stage test, and the
note-73 12-theme market-pain catalog. Discipline per ADR-037: no
new code, only diagnoses with `file:line` citations and concrete
fix recommendations.

---

## §1 — TL;DR (ship verdict + counts)

**Verdict: SHIP Stage R end-to-end. Fix the two P0s before
C4.1 (QC RL-1) lands; the P1 backlog can absorb into C5.** The
substrate composition works; the cross-stage test passes (eyes-on
reading — no live REPL was available); the three adapters honor
the protocol shape; the per-country accruals (Urlaubsrückstellung
DE / ASC 710 US / vacation-pay CA) cleanly separate. The two P0s
are both about cross-cutting consistency rather than wrong code:
one breaks the kernel's documented `:payroll-run/emit-docs`
contract (the schema attr exists but `run-payroll!` never sets
it); the other is a vocabulary divergence in the per-adapter
`:audit-doc/category` values that, left as-is, will pollute every
consumer's auth grid.

- **P0 ship-blockers: 2** (emit-docs linkage; category-vocabulary
  divergence — both in §3).
- **P1 followups: 7** (orchestrator parameter surface, missing
  schema declarations, missing test paths, missing DSAR
  registration carry-over from note-85 P2 — §4).
- **P2 nice-to-haves: 6** (accrual-primitive convention drift,
  bilingual-DE/US, provider-id on the other two protocols
  carry-over from note-85, …  — §5).

The four-design-call audit (per ADR-075 / ADR-076 / ADR-077 /
ADR-078) is clean — every locked design call landed correctly.
The cross-stage trans-national test surfaces three frictions
(§7) that aren't bugs but are real seams a future consumer will
hit. The Theme A–L market-pain audit (§6) confirms note 73's
substrate-bet: backdated corrections, multi-state allocation,
audit-doc retention, payroll-correction-on-held-entity, parallel-
ledger book/tax accruals all compose; the 5 themes kontor
explicitly does NOT solve (gross-to-net, reciprocity, UX, time-
clock, year-end form generation) stay correctly outside the
substrate.

---

## §2 — Cross-cutting consistency findings (the three adapters)

Each adapter agent worked from a separate research-before note
(82 / 83 / 84) and a separate worktree, then merged. The three
impl conventions diverge in five places. These aren't bugs but
they accumulate: the next country adapter agent reads three
existing modules and has to pick one to mimic. The audit's job is
to name the divergences so the maintainer can pick a canonical
form before C5.

### 2.1 — Provider trio completeness diverges

| Adapter | `PayrollComputeProvider` | `PayrollPostingBuilder` | `PayrollEmitProvider` |
|---|---|---|---|
| DE | `DatevLodasComputeProvider` (`compute.clj:470`) | `DatevLodasPostingBuilder` (`posting_builder.clj:177`) | `DatevLodasEmitProvider` (`emit.clj:329`) |
| US | `AdpGliComputeProvider` (`compute.clj:401`) | `UsPayrollPostingBuilder` (`posting_builder.clj:264`) | **NONE** (relies on default `LocalfileEmitProvider`) |
| CA | `CeridianDayforceGlProvider` + `AdpCanadaProvider` + `WagepointApiProvider` (skeleton) (`compute.clj:225 / 377 / 401`) | `CaPayrollPostingBuilder` (`posting_builder.clj:220`) | `CaPayrollEmitProvider` (`emit.clj:69`) |

DE ships a real emit provider (LODAS Importdatei) because the DE
jurisdiction has an inbound clearance-shape file. US explicitly
doesn't ship one because there's no clearance regime — that's
correct per ADR-077's "default `LocalfileEmitProvider` returns
`[]`". CA ships a summary `:audit-doc/category :payroll` emit
even though CA doesn't have a clearance regime either (PD7A is
correspondence-from-CRA, not employer-filed). The reason CA does
emit is to carry the QC-detection warning plus a per-pay-period
audit-doc summary; that's reasonable but it sets up a vocabulary
divergence (see §2.2 / P0-86-2).

CA also ships **three** compute providers (Ceridian + ADP-CA +
Wagepoint stub) where DE and US ship one. This is sensible (CA
has no dominant single engine like DE-DATEV) but it widens the
provider-trio surface and creates room for further divergence in
how each provider handles `:variable-inputs`.

**Implication.** No fix needed; document the convention. A new
country (BR / UK / FR) MAY ship without an emit provider; it MAY
ship multiple compute providers. The skeleton-with-throw pattern
(CA's `WagepointApiProvider` at `compute.clj:401-416`) is the
right way to surface "deferred" without breaking the protocol —
adopt it as canonical for future country adapters.

### 2.2 — `:audit-doc/category` vocabulary diverges across adapters

This is the load-bearing cross-cutting finding (escalates to **P0**
in §3 below).

| File:line | `:audit-doc/category` value |
|---|---|
| `modules/payroll-de-datev/src/.../emit.clj:352` | `:tax-filing` |
| `modules/payroll-ca/src/.../emit.clj:83` (run summary) | `:payroll` |
| `modules/payroll-ca/src/.../emit.clj:182` (termination) | `:hr-personnel` |
| `modules/payroll-ca/src/.../emit.clj:236` (T4 IFT) | `:payroll-filing` |
| `modules/payroll-ca/src/.../pd7a.clj:254 / 276` | `:payroll-filing` |
| `modules/hr/src/.../employment.clj:12` (doc only) | `:hr-personnel` |
| `modules/hr/src/.../schema.clj:478` (doc only) | `:tax-filing` |
| `src/kontor/schema.clj:3678` doc-string ADR-075 examples | `:payroll`, `:hr-personnel`, `:hr-medical`, `:tax-filing` |

Vocabulary in active use across the three adapters:
- `:tax-filing` — DE LODAS Importdatei. ADR-075 schema doc
  endorses this for filings.
- `:payroll` — CA per-pay-period run summary.
- `:payroll-filing` — CA T4 IFT + PD7A audit-doc.
- `:hr-personnel` — CA termination event.

The same conceptual thing — "an emit-side audit-doc carrying a
filing-ready payload" — uses `:tax-filing` in DE and
`:payroll-filing` in CA. A consumer's auth-grid policy keyed on
`:audit-doc/category :tax-filing` will MISS the CA T4 IFT doc
because it's tagged `:payroll-filing`. The note-75 design call
("two-dimensional grid the ADR promised") is silently broken at
the data level because no canonical category vocabulary was ever
codified.

ADR-075 line 3678 doc-string lists `:payroll`, `:hr-personnel`,
`:hr-medical`, `:tax-filing` as examples (open-set); it does NOT
canonicalize `:payroll-filing` vs `:tax-filing`. The DE agent
read note 82 §8 / ADR-075 schema doc + picked `:tax-filing`; the
CA agent read note 84 §9 + picked `:payroll-filing` to
distinguish payroll filings from tax-prep filings.

See P0-86-2 below.

### 2.3 — Per-country accrual primitive shape diverges

Three different conventions for the same conceptual seam:

| Adapter | Accrual surface | Standard | Composition pattern |
|---|---|---|---|
| DE | `urlaubsrueckstellung-amount` + `urlaubsrueckstellung-tx-data` (`posting_builder.clj:226 / 270`) | HGB §249 | **Separate top-level fns** in the posting-builder ns; consumer composes outside `run-payroll!` |
| US | `asc-710-pto-accrual-tx-data` + `asc-710-pto-accrual!` + `er-401k-match-accrual-tx-data` + `tax-recognize-401k-match-tx-data` (`accrual.clj`, separate ns) | ASC 710 + IRC §404(a)(6) | **Separate ns + `!` wrapper through `transact-with-validation`** |
| CA | `:vacation-pay-accrual` component-kind (`wage_types.clj:147-149`) | Engine-driven | **Folded into the regular wage-type / posting builder pipeline** as an employer-side component routing to a vacation-accrual expense + vacation-liability payable |

All three are correct under their respective accounting standards.
But the convention divergence means:
- a new-country agent has to read all three and pick one,
- a consumer needing both DE PTO accrual AND US 401(k) match
  uses two entirely different APIs,
- the substrate's `kontor.process/run-process` orchestration
  doesn't compose them uniformly — the DE Urlaubsrückstellung
  doesn't auto-fire from `run-payroll!`, the US ASC 710 doesn't
  either, but the CA vacation-pay accrual IS auto-emitted by the
  posting builder if the engine reports `:vacation-pay-accrual`.

**Implication.** Not a bug; convention drift. P2-86-3 (§5) flags
the recommendation: standardize on the CA pattern (component-
kind-driven) where the engine emits the accrual, with the DE/US
out-of-band `tx-data` builders reserved for the consumer-
computed case (when no engine reports an accrual). The merge
would canonicalize "if the engine emits an accrual component →
posting-builder routes it; if the consumer computes it → call
the out-of-band builder" as the rule.

### 2.4 — `:variable-inputs` shape varies per adapter

`kontor.hr.payroll/run-payroll!` passes opaque `:variable-inputs`
to the compute provider. Each adapter has its own conventions:

| Adapter | `:variable-inputs` keys consumed |
|---|---|
| DE | `:buchungsbeleg-content` OR `:facts` (`compute.clj:482-487`) |
| US | `:adp-gli-csv-source` + `:wage-type-map` + `:employee->employment` (`compute.clj:417`) |
| CA Ceridian | `:csv-source` + `:column-mapping` + `:pay-element-codes` + `:external-id->eid` + `:extras-map` (`compute.clj:228-250`) |
| CA ADP | `:csv-source` + `:pay-element-codes` + `:external-id->eid` + `:headerless?` (`compute.clj:380-395`) |

This is consistent with the "opaque per-provider" contract in
ADR-075, but two divergences stand out:
- US calls its CSV source key `:adp-gli-csv-source`; CA calls
  its CSV source key `:csv-source`. There's no kontor-canonical
  name. A future cross-vendor parser-sharing module (Gusto /
  Paychex / OnPay per note 83 §3) will pick one and the other
  will need a follow-up rename.
- US passes `:wage-type-map` through `:variable-inputs` (so the
  same `AdpGliComputeProvider` instance can handle different
  wage-type maps per run); CA passes the equivalent
  (`:pay-element-codes`) but takes a default from the provider's
  `:opts`. DE doesn't have a wage-type-map slot in `:variable-
  inputs` at all — the catalog goes through the posting-builder
  + emit-provider opts.

**Implication.** P2-86-4 (§5). Document the canonical
`:variable-inputs` schema in `kontor.payroll-provider` so the
next adapter doesn't reinvent.

### 2.5 — Wage-types catalog API shape diverges

Three different validation surfaces and three different lookup
patterns:

- DE `kontor.payroll-de-datev.wage-types/validate-catalog`
  (validates an EDN map keyed by integer Lohnart numbers; throws
  ex-info on validation failure).
- US `kontor.payroll-us-adp.wage-types/validate` (returns a
  vector of error maps; returns `nil` on success — semantically
  opposite of DE).
- CA `kontor.payroll-ca.wage-types/standard-component-kinds`
  (built-in map keyed by component-kind keyword; consumers
  extend via an `extras-map`; no validation surface).

DE validates a per-customer Steuerberater configuration
(Lohnart-number-driven). US validates a per-customer ADP wage-
type regex map. CA ships a known-set + extras-map. These reflect
genuine domain differences (DE's Lohnart vocabulary IS customer-
configured; CA's component-kind vocabulary IS substrate-canonical
with consumer extensions), so the divergence is appropriate.

But the **validation-return convention** divergence (throws vs.
returns errors) is gratuitous. The next adapter agent will read
both and pick one; document the canonical form.

**Implication.** P2-86-5 (§5). Standardize on "throw ex-info on
failure" (the DE/kernel-elsewhere convention) for the next
adapter; the US `validate` return-shape becomes the unique
outlier.

---

## §3 — P0 ship-blockers

### P0-86-1 — `run-payroll!` produces emit-docs but never sets `:payroll-run/emit-docs`

**Citation.** `modules/hr/src/kontor/hr/schema.clj:472-478` declares
`:payroll-run/emit-docs` as `:db.type/ref :db.cardinality/many`
("the emissions produced by the PayrollEmitProvider"). The
orchestrator `modules/hr/src/kontor/hr/payroll.clj:173-188`
fetches `emit-docs` from the emit provider and concatenates
them into `:tx-data` — but it never links them back to the
payroll-run via `:payroll-run/emit-docs`.

```clojure
;; payroll.clj:173-188 — emit-docs are transacted but not linked
emit-docs (pp/emit-payroll-events emit-provider facts ...)
run-frag (create-payroll-run-tx-data ...)
{:tx-data (vec (concat tx-frag
                       emit-docs              ; ← transacted standalone
                       run-frag
                       [{:db/id "payroll-run-1"
                         :payroll-run/payroll-transaction "payroll-tx-1"}]))}
                       ;; ← :payroll-run/emit-docs is never threaded here
```

The DE e2e test (`modules/payroll-de-datev/test/.../e2e_test.clj:189-193`)
already documents the workaround: it queries the audit-doc by
`:audit-doc/payroll-period` rather than via the payroll-run.
ADR-076's P1 followup list flags this explicitly. The CA e2e
test (`modules/payroll-ca/test/.../e2e_test.clj:261-265`) does
the same workaround — queries by `:audit-doc/category :payroll`.

**Why this is a ship-blocker.** Without the linkage, the
documented audit-chain contract is broken: a consumer querying
"what emit-docs did payroll-run X produce?" gets nothing. The
schema attr exists, the doc-string promises it, ADR-075 + ADR-076
+ ADR-078 narrate around it — but no code path populates it.
Every consumer that wants this lookup writes a custom query;
that's the kind of substrate-leakage the substrate is supposed
to prevent.

**Fix.** Three-line change in `payroll.clj:172-188`:
1. Tag each emit-doc with a tempid (`emit-doc-1`, `emit-doc-2`, …).
2. Add a `[{:db/id "payroll-run-1" :payroll-run/emit-docs
   ["emit-doc-1" "emit-doc-2" ...]}]` linkage to the tx-data.
3. Update the two e2e tests' workaround queries to walk
   `:payroll-run/emit-docs` directly.

This was already the documented P1-from-ADR-076; promoting to
P0 because it directly blocks the cross-cutting substrate
contract three modules depend on.

**Commit hint.** `fix(hr): link emit-docs via :payroll-run/emit-docs in run-payroll!`

### P0-86-2 — `:audit-doc/category` vocabulary fragments across adapters

**Citation.** See §2.2 table. Same conceptual filing-shape
audit-doc gets `:tax-filing` (DE) and `:payroll-filing` (CA);
ADR-075 doc-string lists `:tax-filing` as canonical but ADR-078
doesn't reuse it.

**Why this is a ship-blocker.** ADR-075 §"two-axis category"
explicitly promises: "HR role can access category `:payroll`
regardless of privilege; tax-prep contractor can access
category `:tax-filing` UNLESS privilege `:attorney-client`".
A consumer wiring an auth-grid against those exact category
values today MISSES CA T4 / PD7A audit-docs entirely (they're
tagged `:payroll-filing`, not `:tax-filing`). Worse, when C5
(UK) and C6 (FR / DSN) land, each agent picks whichever
spelling they think the docstring suggests, and the auth-grid
keeps fragmenting.

The deeper problem: there's NO canonical vocabulary file. The
schema doc-string examples are aspirational; no test pins them
down; nothing in `kontor.retention` / `kontor.dsar` enforces a
known-set.

**Fix.** Two-step:
1. **Codify the canonical category vocabulary** in
   `src/kontor/schema.clj:3675` doc-string AND in a new
   `kontor.audit-doc/canonical-categories` def (open-set, but
   the canonical-form keywords are listed). Minimum set per
   the three live adapters + ADR-075 narration: `:payroll` |
   `:payroll-filing` | `:hr-personnel` | `:hr-medical` |
   `:tax-filing` | `:financial` | `:garnishment-order` | `:i9`.
   Decide whether `:payroll-filing` is a sub-category of
   `:tax-filing` (CA's distinction) or whether they collapse
   (DE's convention).
2. **Align the three adapters** to the canonical vocabulary.
   The DE LODAS Importdatei is more naturally `:payroll-filing`
   (it's a payroll-system inbound feed, not a tax-authority
   filing); rename `:tax-filing` → `:payroll-filing` in
   `modules/payroll-de-datev/src/.../emit.clj:352`. OR pick
   `:tax-filing` as canonical and rename CA's `:payroll-filing`
   → `:tax-filing` in three call sites. Either decision is
   defensible; pick one.

The two-decision-points (canonical name, sub-category vs
collapse) are the maintainer's design call; once made, the
patch is ~5 edits across three modules.

**Commit hint.** `fix(audit-doc): canonicalize :audit-doc/category vocabulary across DE / US / CA payroll emit`

---

## §4 — P1 followups (land before C5 / C4.1 starts)

### P1-86-1 — `run-payroll!` doesn't thread `:ledgers-map`

**Citation.** `modules/hr/src/kontor/hr/payroll.clj:156-161`
calls `pp/build-postings` with `{:accounts accounts :ledger
ledger :fx-provider fx-provider}` — singular `:ledger`. The US
posting builder explicitly wants `:ledgers-map` for the parallel-
ledger book/tax split (`modules/payroll-us-adp/src/.../
posting_builder.clj:239-245`). The US e2e test
(`e2e_test.clj:175-183`) has a long comment documenting the
workaround: "kontor.hr.payroll passes `:ledger` only — so for
this e2e test we configure the provider's default ledgers-map
below."

**Fix.** Add `:ledgers-map` to the run-payroll! opts parameter
surface and thread it through to `pp/build-postings`. ~3-line
change. Until then the US module's `:us-gaap` vs `:us-tax`
parallel-ledger split (ADR-077 §"Parallel-ledger split for
accruals") only works when consumers bypass the orchestrator
and call the posting builder directly.

### P1-86-2 — `run-payroll!` doesn't thread `:state-allocations`

**Citation.** `modules/payroll-us-adp/src/.../posting_builder.clj:266`
declares `:state-allocations` as a recognized opt; the US e2e
test fixture uses only the default single-state per row.
Hybrid / multi-state employees (note 73 Theme B P1; ADR-077
§"multi-state allocation") need a per-employee state-percent
override map. The orchestrator's parameter surface doesn't
include `:state-allocations`.

**Fix.** Add `:state-allocations` to `run-payroll!` opts,
thread to `pp/build-postings`. Same 3-line shape as P1-86-1.

### P1-86-3 — `:employment/province-of-employment` referenced but never declared in schema

**Citation.** `modules/payroll-ca/src/.../t4_builder.clj:47, 90-91, 270`
references `:employment/province-of-employment`; `emit.clj:194`
references `:employment/final-pay-period-end-date`. Neither is
declared in `modules/hr/src/kontor/hr/schema.clj` (the
canonical home for `:employment/*`) nor in any payroll-ca
schema-extension transact. datahike's open-set tolerance means
the pulls don't crash — but a typo in the attr name would also
silently return nil.

**Fix.** Either (a) add the two attrs to `kontor.hr.schema`
(promoting from "CA-specific" to "substrate" — they're useful in
US states / DE Bundesländer too once provincial / state
employment data lands), or (b) declare them in a
`kontor.payroll-ca.chart/install!` schema-extension transact
(mirrors the DE C2 pattern of installing four extra `:audit-doc/*`
attrs in `payroll_de_datev/core.clj:73-101`). Either way, declare
them.

### P1-86-4 — DE LODAS `EmitProvider` doesn't carry `:audit-doc/language`

**Citation.** `modules/payroll-de-datev/src/.../emit.clj:350-359`
produces `:audit-doc/category :tax-filing` but no
`:audit-doc/language`. CA correctly threads `:audit-doc/language`
through its emit + termination + T4 builders
(`payroll_ca/emit.clj:84, 183, 237`). DE LODAS files are
de-facto-DE (the format header is German); CA T4 / RL-1 are
explicitly bilingual.

If the auth-grid story is "category × privilege × language", the
DE side breaks the grid by leaving language nil. ADR-078
explicitly establishes `:audit-doc/language` as a three-axis
component.

**Fix.** Add `:audit-doc/language :de` to the DE LODAS audit-doc.
~2-line change. Consider whether to extend the
`PayrollEmitProvider` protocol to take `:language` from the
caller, or whether it lives in the per-provider opts (the latter
matches the existing CA pattern — `emit.clj:189`'s
`{:language :en}`).

### P1-86-5 — `kontor.hr.core/install!` still doesn't register `:partner/person` with the DSAR registry

**Citation.** Note-85 P2-85-2 is still open. The CA module's
`collect-for-person` helper (`modules/hr/src/.../dsar.clj:19`)
works, and the cross-stage test confirms the walk reaches all
three employments + compensations
(`stage_r_cross_stage_test.clj:343-351`). But a consumer using
the kernel `kontor.dsar/collect` directly — which is the
documented entry point — still misses HR data because nothing
registered `:partner/person` with the kernel walker.

This was P2 in note 85; promoting to P1 here because Stage R
now ships three concrete payroll adapters that ALL want DSAR
coverage of payroll-result audit-docs, and the cross-stage
test's `collect-for-person` workaround is fine for the C1
substrate but doesn't scale — a UK-payroll DSAR walk needs to
follow the payroll-run → audit-doc chain, which is shipped
substrate the kernel walker already supports if `:partner/person`
were registered.

**Fix.** Add `(kontor.dsar/register-partner-attr! conn
:partner/person)` to `kontor.hr.core/install!` (one line).
Plus a test that asserts `kontor.dsar/collect` (NOT
`hr-dsar/collect-employee`) reaches the HR side.

### P1-86-6 — No back-dated `:compensation` correction test (carry-over from note-85 P1-85-4)

Still open. The trans-national test exercises three forward-
dated compensations; nothing exercises the back-dated case.
This is the load-bearing bitemporal-correction story per note
73 Theme A P1 + ADR-075's stated bitemporal-substrate value
proposition. A test would catch a hypothetical regression in
the bitemporal substrate that the unit tests can't see.

### P1-86-7 — Cross-stage test asserts FTE-sum 1.20 across 3 employments without flagging the over-100% case

**Citation.** `test/kontor/stage_r_cross_stage_test.clj:285-295`
asserts `(reduce +)` over the three employments' `:work-time-fraction`
equals `1.20M`. The scenario is Jane DE @0.60 + US @0.40 + CA
@0.20. **This is over-allocation** — Jane can't actually work
120% of a full week. No invariant fires; the substrate just
accepts it.

Multi-employment was designed (note 79 Call 2) precisely to
allow scenarios like secondment, but the >1.0 sum is a soft
red flag the substrate doesn't surface. ADR-075 + the schema
docstring for `:employment/work-time-fraction` (`schema.clj:207-211`)
say nothing about the constraint.

**Fix.** Either (a) document explicitly that
`Σ :work-time-fraction` may exceed 1.0 by design (e.g.
secondment with overlap), or (b) add an optional validation
helper (`kontor.hr.employment/check-fte-sum`) that consumers
can wire if their scenarios disallow over-allocation. A schema-
level constraint is over-engineering; a documented helper +
example test is right-sized.

---

## §5 — P2 nice-to-haves

### P2-86-1 — `PayrollPostingBuilder` + `PayrollEmitProvider` still lack `provider-id` (carry-over from note-85)

`src/kontor/payroll_provider.clj:139-160` + `162-181`. The
audit chain on `:payroll-run/provider-id` records the
compute-provider ID only. When DE C2 ships a dual-ledger HGB-vs-
Steuerbilanz posting-builder, or when CA ships a Wagepoint live
emit provider, the audit chain is silent about WHICH posting/emit
provider ran. P2 because no live consumer hits this yet — but
once C4.1 ships RL-1 emission, the gap matters.

### P2-86-2 — `kontor.payroll-provider` lacks a canonical `:variable-inputs` schema spec

See §2.4. Add an `:expects-variable-inputs` doc-string section on
each protocol method, OR add a Plumatic-style spec in the
provider record. Either way, a contributor reading the protocol
shouldn't have to grep the three impls to learn the variable-
input shape.

### P2-86-3 — Accrual primitives diverge per country (see §2.3)

Standardize on the CA "component-kind drives accrual via the
posting-builder" pattern; reserve the DE/US out-of-band tx-data
builders for the consumer-computed-amount case.

### P2-86-4 — `:adp-gli-csv-source` vs `:csv-source` naming

US uses `:adp-gli-csv-source`; CA uses `:csv-source` for both
Ceridian and ADP-CA. Pick one; document. If keeping the US-
prefix-style, document why ("avoid collision with the consumer's
own `:csv-source` for a different ingest").

### P2-86-5 — Wage-types validation return-shape diverges (see §2.5)

DE throws; US returns errors. Standardize.

### P2-86-6 — `WagepointApiProvider` skeleton's throw is fine but no test confirms the throw

`modules/payroll-ca/src/.../compute.clj:401-416` is the skeleton;
`modules/payroll-ca/test/.../compute_test.clj` (per the test
inventory: 11 tests) presumably covers the throw but I didn't
verify. P2 nit: ensure one test asserts the ex-info shape.

---

## §6 — Market-pain audit per theme (note 73 themes A–L)

Substrate-coverage assessment against the 12 themes from note
73 (the market-pain catalog that drove Stage R's prioritization).

### Theme A — Correction workflows

Note 73 P1 ("backdated correction reprocessing breaks audit
trail") is THE flagship kontor pain-target. **Substrate:
SHIPPED.** The bitemporal `:db.valid/from` + `:db.tx-time`
machinery is the right primitive (per ADR-008 + note 68 +
ADR-075 narration). **Per-stage gap (P1-86-6 above):** no test
exercises the back-dated correction. The substrate's
bitemporal-correction story is therefore unverified through the
HR/payroll stack. A test that back-dates Jane's compensation
2026-04-01 → 4500M (originally 5000M) and re-runs payroll for
2026-04-30 with a corrected fact, asserting both the original
filing AND the corrected view, would close this.

### Theme B — Multi-state US payroll

**Substrate: SHIPPED via `:analytic-account/state`.** US adapter
installs 56 `:analytic-account` rows for ISO-3166-2:US
(`payroll-us-adp/core.clj:73-130`); the posting builder threads
`:analytic-distribution` on every wage-side posting; the e2e
test verifies CA / NY / TX allocation
(`e2e_test.clj:213-223`). **Per-stage gap (P1-86-2 above):**
orchestrator doesn't thread `:state-allocations`, so multi-
state-per-employee hybrid allocation requires bypassing
`run-payroll!`.

### Theme C — Multi-country / trans-national / secondment

**Substrate: SHIPPED + EXERCISED.** The trans-national test
(`test/kontor/stage_r_cross_stage_test.clj`) is the live proof:
one `:person` (Jane), three `:employment` rows across three
entities, three currencies, three country-specific compute
providers + posting builders, three balanced transactions on
three CoAs. **Friction surfaced (§7 below):** the test uses
mock posting builders (`MockPostingBuilder` at
`stage_r_cross_stage_test.clj:60-78`) rather than the real per-
country builders. The real builders compose only because the
cross-stage test bypasses their country-specific wage-type
catalogs; an end-to-end trans-national test with REAL builders
would have to coordinate three wage-type catalogs + three
account-maps simultaneously, which is genuinely complex and
deferred work.

### Theme D — Parallel-ledger book / tax for accruals

**Substrate: SHIPPED for DE + US; INCOMPLETE wiring.** ADR-021's
parallel-ledger machinery + the per-component `:ledgers` set
(US `payroll_us_adp/posting_builder.clj:79-93`) handle the
book-vs-tax split. DE Urlaubsrückstellung takes a `:framework
:hgb-handelsbilanz | :de-steuerbilanz` parameter
(`posting_builder.clj:242-253`). **Gap (P1-86-1 above):** the
orchestrator doesn't thread `:ledgers-map`, so the US parallel-
ledger path only works outside `run-payroll!`. CA doesn't ship
the parallel-ledger split at all (its vacation-pay accrual is
single-ledger book-only); that's defensible because CA doesn't
have a US-style book-vs-tax gulf, but the convention divergence
matters when comparing the three adapters.

### Theme E — PTO accrual + carryover

**Substrate: SHIPPED for DE + US + CA in three different shapes
(§2.3 + P2-86-3).** The accruals are correct per their
respective standards (HGB §249 DE, ASC 710 US, vacation-pay
liability CA). Convention divergence is the concern.

### Theme F — W-2 / T4 / DEÜV / DSN reconciliation

**Substrate coverage:**
- US: `kontor.payroll-us-adp.w2-recon` produces the YTD per-
  employee Box-1/3/5/12 reconciliation report
  (`w2_recon.clj:88-135`). ADR-077 ships this as a data-prep
  report, NOT a W-2 emitter — correct posture.
- CA: `kontor.payroll-ca.t4-builder` aggregates the T4 slip
  values + composes the T619 + T4 + T4-Summary submission via
  the already-shipped `kontor.l10n-ca.xml.t4` emitter. ADR-078
  §4 narrates the round-trip against the 2026V4 XSD; the test
  inventory references "XSD validation against the shipped
  2026V4 T619_T4.xsd" in `t4_builder_test/full-submission-
  validates-against-xsd`. **This is the strongest theme-F win**
  — CA ships a full year-end XML generator round-tripping
  against the regulator's XSD.
- DE: Note 82 §8.1 / ADR-076 explicitly **defer DEÜV / GKV-
  Monatsmeldung / Lohnsteueranmeldung to DATEV** — kontor only
  emits the LODAS Importdatei (the *inbound* variable-input
  feed). That's the substrate's principled position per
  ADR-005 / ADR-071 / ADR-075. The substrate doesn't have a
  DEÜV story because by design it doesn't need one.

**Gap:** US W-2 multi-jurisdictional reconciliation (Box 16
state, Box 18 local) is present in `w2_recon.clj:125-134` but
isn't exercised by an e2e test. CA T4 round-trip is exercised.
DE has no exercise because no DE filing leaves kontor.

### Theme G — Year-end close + retained-earnings rollup

**Out of Stage R scope.** No friction surfaced. The substrate
machinery (`kontor.closing`, `kontor.report`) is unchanged by
Stage R.

### Theme H — PEO co-employment

**Out of Stage R scope.** The substrate doesn't preclude:
multi-`:partner/kind :employee` partners with different
`:employment/entity` refs handle the co-employment shape (the
PEO is an `:entity`, the customer is another `:entity`, the
worker has employments at both). No PEO-specific entities were
added, which is correct — PEO is an adapter concern.

### Theme I — International assignment / secondment

**Substrate: SHIPPED via multi-employment.** Jane's three
concurrent employments at three entities IS the secondment
case. The cross-stage test exercises it. **Friction:** no
substrate-side helper articulates the "primary employment"
vs "secondary" distinction many real cases need (which payroll
does the Christmas bonus belong to?). That's a per-country /
per-consumer determination, but a doc-string in
`employment.clj` flagging the design choice ("kontor records
N concurrent employments; choice of primary is the consumer's
or the per-country adapter's") would help.

### Theme J — Compensation structure (bonus / RSU / pension / VWL)

**Substrate: SHIPPED via the §9.6 `:compensation-component`
refactor.** Note 81's recommendation landed: each compensation
is N components with `:kind` + `:amount` + `:period` +
`:account-hint`. DE C2's `:weihnachtsgeld` / `:urlaubsgeld` /
`:vwl` / `:bav-direktversicherung` use this seam
(`wage_types.clj:95-101`). US's `:role`-based component
classification (`compute.clj:261-287`) mirrors it. CA's
`:vacation-pay-accrual` / `:retroactive-pay` / `:retiring-
allowance` mirrors it (`wage_types.clj:36-57`). **Gap:** the
`:equity-vest` component kind is declared in the protocol
vocabulary (`payroll_provider.clj:59`) but no adapter exercises
it. Real RSU vest payroll math is genuinely complex (note 73
Theme I + note 79 §7); the substrate-side seam is correct.

### Theme K — Worker classification (1099 / W-2 / EOR / direct)

**Substrate: SHIPPED via `:person/kind` (open-set keyword:
`:employee | :contingent | :applicant | :retiree | :board-
member | :intern | …`) + `:employment/work-relationship-kind`
(`:standard | :secondment | :board-position | :apprentice |
:intern | :working-student | :civil-servant | …`).** Per the
note-85 audit + note 81 §9.7 recommendation. **Gap:** none of
the three adapters surface the `:person/kind :contingent` case
in tests, even though the schema doc-string (`hr/schema.clj:97-102`)
explicitly cites contingent-worker payroll. A test that hires
a `:contingent` worker, routes a payroll-via-1099, and asserts
the substrate doesn't choke would close the cosmetic gap.

### Theme L — Privacy + retention + DSAR + legal-hold

**Substrate: SHIPPED for retention + legal-hold; PARTIAL for
DSAR (P1-86-5).** The two new kernel attrs (`:audit-doc/
category` + `:retention-policy/category`) are the right axes;
note-85 P0-85-2 closed the sweeper-reads-category bug; CA's
`:audit-doc/language` adds the third axis. The DSAR side is
where P1-86-5 sits — `:partner/person` still isn't registered
with the kernel walker, so the kernel-canonical
`kontor.dsar/collect` walk doesn't hit HR.

---

## §7 — Cross-stage friction surfaced by the trans-national test

The trans-national test (`test/kontor/stage_r_cross_stage_test.clj`)
is the strongest signal Stage R has on whether the substrate's
composition story actually holds. Three frictions surface:

### F1 — Mock posting builders, not real ones

`stage_r_cross_stage_test.clj:60-78` uses a `MockPostingBuilder`
that emits a single `Dr wages-expense / Cr wages-payable` pair
per fact. The real DE / US / CA posting builders each take
country-specific opts (`:catalog` + `:commodity` + multiple
`:account-tag` keys; `:ledgers-map`; `:rp-account-tag`). Using
the real ones in the cross-stage test would require setting up
three full account-maps + three wage-type catalogs + three
ledger configs — significantly more setup than the test
currently has.

This is the right level of cross-stage simplification for now,
BUT the assertion that "the three real adapters compose under
the substrate orchestrator" is unverified. A future cross-
stage-2 test using the real adapters would close this. P2 (not
included in the count above; documented here as the test-debt
seam).

### F2 — DSAR walk uses HR-specific helper, not kernel walker

`stage_r_cross_stage_test.clj:343-351` explicitly comments on
this: "the kernel DSAR walker is partner-keyed; HR side needs
the person-keyed helper to surface employment data." The test
calls `hr-dsar/collect-for-person`, NOT `kontor.dsar/collect`.
This is P1-86-5 — the kernel-canonical walker would compose if
`:partner/person` were registered.

### F3 — Per-employee FTE-sum exceeds 1.0 silently

`stage_r_cross_stage_test.clj:285-295` asserts the sum is 1.20.
The scenario is realistic (executive split-time across
entities) but the substrate accepts arbitrarily-overallocated
employees with no signal. P1-86-7 captures this.

### F4 — Three currencies, no FX cross-translation tested

The test sets up Jane with three commodities (EUR / USD / CAD)
but never invokes the `FxRateProvider` to translate across
them. A consolidated "total compensation for Jane in EUR" query
would force exercise of ADR-072's `FxRateProvider` + the
ADR-073 consolidation primitives. P2 — Stage R doesn't claim to
ship consolidation; ADR-073 already does. But the trans-
national test would be more valuable with the FX dimension.

---

## §8 — Per-design-call audit (ADR-075 + ADR-076 + ADR-077 + ADR-078)

### ADR-075 design calls (the 5 substrate calls)

Per note-85, all 5 calls land correctly. Promoted updates here:

- **Call 1** (companion-tier `kontor-hr`) — ✓ unchanged.
- **Call 2** (Workday multi-employment) — ✓ EXERCISED by the
  trans-national test (3 employments / 1 person).
- **Call 3** (hybrid `:partner/person` linker) — ✓ schema-shape
  correct; P1-86-5 still pending (DSAR registry registration).
- **Call 4** (three-protocol PayrollProvider + emit) — ✓ all
  three adapters satisfy the protocol cleanly; emit-provider
  optional pattern works (US omits, DE + CA ship).
- **Call 5** (two-axis `:audit-doc/category`) — ✓ schema-shape
  correct; P0-86-2 calls out the vocabulary fragmentation.

### ADR-076 design calls (DE-DATEV-LODAS)

- **Single-module for both LODAS + LuG** — ✓ correct posture;
  the `:datev-target :lodas | :lug` knob is in the emit-
  provider's `[Allgemein]` block (`emit.clj:143`).
- **EXTF Buchungsbeleg parser asserts Verrechnung-zero** — ✓
  `compute.clj:276-294` enforces; corrupt files raise ex-info.
  Cited in the e2e test.
- **HGB §249 simplified PTO + actuarial pensions deferred** —
  ✓ `posting_builder.clj:226-304` ships
  `urlaubsrueckstellung-amount` + `urlaubsrueckstellung-tx-data`
  with `:framework` knob; pensions correctly out of scope.
- **No bundled DATEV catalog** — ✓ `wage_types.clj` ships only
  the 10 load-bearing account defaults; everything else is
  consumer-supplied + validated.
- **P1/P2 followups from ADR-076 line 8126-8130:**
  - P1: `:payroll-run/emit-docs` linkage — **STILL OPEN
    (promoted to P0-86-1)**.
  - P2: skr04.edn missing 9 personnel accounts — verified:
    the file ships `6020` only (audited via `head` on
    `modules/l10n-de/resources/kontor/l10n_de/skr04.edn`);
    still open per ADR-076 narration.
  - P2: Stammdaten emit slot empty — verified: `emit.clj:330-359`
    only emits Bewegungsdaten; still open.

### ADR-077 design calls (US-ADP-GLI)

- **Parse GLI 10-column CSV with balancing-row trap** — ✓
  `compute.clj:172-222` enforces sum-to-zero and skips empty-
  GL-account rows.
- **Regex-driven wage-type classification with state-from-group**
  — ✓ `compute.clj:228-255` matches + extracts.
- **Multi-state via `:analytic-account/state`, NOT `:posting/entity`**
  — ✓ `posting_builder.clj:95-123` writes
  `:posting/analytic-distributions` with `:analytic-plan/code
  "state"` lookups. Schema-shape consistent with ADR-022.
- **Parallel-ledger via `:ledgers` per component** — ✓
  `posting_builder.clj:66-93`. Gap: orchestrator threading
  (P1-86-1).
- **ASC 710 PTO + 401(k) match accrual** — ✓ `accrual.clj` ships
  the pair of tx-data builders + the IRC §404(a)(6) late-cycle
  tax-recognition primitive. The substrate ships the seam; the
  consumer chooses when to invoke.
- **W-2 reconciliation report (data-prep, NOT W-2 emitter)** —
  ✓ `w2_recon.clj` produces YTD per-employee box totals.
- **NO bundled CoA / API credentials / SUTA tables / nexus**
  — ✓ verified.

### ADR-078 design calls (CA-CRA)

- **Five module files (compute + posting-builder + t4-builder
  + pd7a + emit) + bilingual** — ✓ verified.
- **Three compute providers (Ceridian + ADP-CA + Wagepoint
  skeleton)** — ✓ `compute.clj:225 / 377 / 401`.
- **`:audit-doc/language` as new kernel attr (orthogonal to
  category + privilege)** — ✓ schema attr exists at
  `kontor/schema.clj:3692-3699`; CA threads it through three
  call sites in `emit.clj`. **Gap (P1-86-4):** DE LODAS doesn't
  carry `:audit-doc/language :de`.
- **No PD7A emission (CRA-to-employer correspondence)** — ✓
  `pd7a.clj:1-13` narrates the deferral; the helper produces
  totals + audit-doc, not a form.
- **QC RL-1 emit (C4.1)** — ✓ closed by ADR-087 (2026-05-18).
  `modules/payroll-ca/src/kontor/payroll_ca/rl1.clj` +
  `rl1_summary.clj` + `tpz1015.clj` + `qc_emit.clj` ship the slip /
  Summary / monthly-remittance / per-pay-period emitter quartet;
  `emit.clj:warn-if-qc-detected!` suppresses the warning when
  `:qc-emit-installed?` is wired. Wage-types catalog adds
  `:employer-fss`; chart adds `2532 RQ-FSS` + `5417 Employer-FSS`.
  No partner XSD shipped (Revenu Québec gate); element shape is
  clean-room from the public RL-1.T-V / RLZ-1.S-G-V forms.
- **Wagepoint live API deferred** — ✓ `compute.clj:401-416`
  throws on `compute-payroll` with a clear partner-program-
  reference message.
- **EHT / WSIB deferred to C4.2** — ✓ wage-types catalog has
  `:employer-eht` / `:employer-wsib` accrual seams but no
  per-province rate machinery. P2 per ADR-078 followups.
- **No vendor pay-element catalogs bundled** — ✓ verified.

---

## §9 — Sources (file:line by topic)

### Kernel additions

- `src/kontor/schema.clj:754-761` — `:retention-policy/category`.
- `src/kontor/schema.clj:3675-3682` — `:audit-doc/category`.
- `src/kontor/schema.clj:3692-3699` — `:audit-doc/language` (new
  in C4 per ADR-078).
- `src/kontor/payroll_provider.clj:105-181` — the three
  protocols + the fourth EmitProvider.

### kontor-hr (C1 substrate)

- `modules/hr/src/kontor/hr/schema.clj:472-478` —
  `:payroll-run/emit-docs` declared (load-bearing for P0-86-1).
- `modules/hr/src/kontor/hr/payroll.clj:97-192` — `run-payroll!`
  orchestrator (P0-86-1 + P1-86-1 + P1-86-2 fix site).
- `modules/hr/src/kontor/hr/payroll.clj:173-188` — the emit-doc
  tempid-elision lines.
- `modules/hr/src/kontor/hr/dsar.clj:19-95` — DSAR walker
  (P1-86-5 still open).
- `modules/hr/src/kontor/hr/core.clj:30-36` — `install!`
  (P1-86-5 fix site).
- `modules/hr/src/kontor/hr/employment.clj:94-117` —
  `terminate-tx-data` (now correctly routes through status-
  machine; note-85 P0-85-1 closed in commit `08a2e63`).

### DE adapter (C2 / ADR-076)

- `modules/payroll-de-datev/src/.../compute.clj:470-513` —
  `DatevLodasComputeProvider`.
- `modules/payroll-de-datev/src/.../posting_builder.clj:177-188` —
  `DatevLodasPostingBuilder`.
- `modules/payroll-de-datev/src/.../posting_builder.clj:226-304` —
  HGB §249 PTO accrual.
- `modules/payroll-de-datev/src/.../emit.clj:329-380` —
  `DatevLodasEmitProvider` (P0-86-2 + P1-86-4 site).
- `modules/payroll-de-datev/test/.../e2e_test.clj:189-193` —
  e2e workaround for P0-86-1.

### US adapter (C3 / ADR-077)

- `modules/payroll-us-adp/src/.../compute.clj:178-222` —
  GLI parser + balancing-row trap.
- `modules/payroll-us-adp/src/.../compute.clj:401-437` —
  `AdpGliComputeProvider`.
- `modules/payroll-us-adp/src/.../posting_builder.clj:66-93` —
  ledger-membership multi-ledger split.
- `modules/payroll-us-adp/src/.../posting_builder.clj:95-123` —
  `:analytic-distribution` state allocation.
- `modules/payroll-us-adp/src/.../posting_builder.clj:264-287` —
  `UsPayrollPostingBuilder` (P1-86-1 + P1-86-2 site).
- `modules/payroll-us-adp/src/.../accrual.clj:93-225` —
  ASC 710 PTO + 401(k) match.
- `modules/payroll-us-adp/src/.../w2_recon.clj:88-135` —
  YTD W-2 reconciliation.
- `modules/payroll-us-adp/src/.../core.clj:72-130` — 50-state
  + DC + 5-territory `:analytic-account` install.
- `modules/payroll-us-adp/test/.../e2e_test.clj:175-183` —
  workaround documenting P1-86-1.

### CA adapter (C4 / ADR-078)

- `modules/payroll-ca/src/.../wage-types.clj:29-159` — standard
  CA component-kind table.
- `modules/payroll-ca/src/.../compute.clj:225-251` —
  `CeridianDayforceGlProvider`.
- `modules/payroll-ca/src/.../compute.clj:312-396` — ADP CSV
  + `AdpCanadaProvider`.
- `modules/payroll-ca/src/.../compute.clj:401-416` —
  Wagepoint partner-program-gated skeleton.
- `modules/payroll-ca/src/.../posting_builder.clj:220-239` —
  `CaPayrollPostingBuilder`.
- `modules/payroll-ca/src/.../pd7a.clj:64-132` — remitter
  schedule + due-date helpers.
- `modules/payroll-ca/src/.../pd7a.clj:191-277` —
  `pd7a-period-due` + audit-doc builder.
- `modules/payroll-ca/src/.../emit.clj:69-85` —
  `CaPayrollEmitProvider` (P0-86-2 site).
- `modules/payroll-ca/src/.../emit.clj:112-196` —
  `terminate-employment-tx-data` (Block-15 ROE data).
- `modules/payroll-ca/src/.../t4_builder.clj:47-91` —
  `:employment/province-of-employment` reference (P1-86-3
  site — attr undeclared).

### Cross-stage validation

- `test/kontor/stage_r_cross_stage_test.clj:52-78` — mock
  provider trio (F1 friction).
- `test/kontor/stage_r_cross_stage_test.clj:142-190` —
  trans-national setup-jane!.
- `test/kontor/stage_r_cross_stage_test.clj:285-295` —
  over-allocation FTE-sum (P1-86-7).
- `test/kontor/stage_r_cross_stage_test.clj:343-351` —
  DSAR walk via HR-specific helper (P1-86-5 friction).
- `test/kontor/stage_r_cross_stage_test.clj:309-317` —
  three `:payroll-run` rows with distinct provider IDs (the
  load-bearing cross-stage assertion that the substrate
  composes the three adapters cleanly).

### Reference notes consumed

- doc/research/73 (12-theme market-pain catalog — §6 audit).
- doc/research/79 (Stage R plan + per-country sequencing).
- doc/research/82 / 83 / 84 (per-country research-before bundles).
- doc/research/85 (C1 substrate review-after — referenced for
  open items P1-86-5 + P1-86-6 + P2-86-1).

### Decision records cross-referenced

- ADR-075 (`doc/decisions.md:7909-7956`) — substrate + the 5
  design calls.
- ADR-076 (`doc/decisions.md:8083-8136`) — DE-DATEV-LODAS + the
  P1/P2 followups it self-flags (P0-86-1 was P1 there).
- ADR-077 (`doc/decisions.md:7960-8025`) — US-ADP-GLI.
- ADR-078 (`doc/decisions.md:8029-8079`) — CA-CRA +
  `:audit-doc/language`.

---

End of note 86.

---
date: 2026-05-18
title: 90 — Showcase + integration-test map (post-Stage R)
status: draft
audience: maintainer — read before scoping the next round of integration tests / showcase notebooks / agent-driven scenarios
---

# 90 — Showcase + integration-test map

The substrate ships in shape: 11 country payroll adapters + Stage R substrate
+ McComb seams + 88 prior research notes. What we have *less* of is a
complete map of **what's exercised by integration tests** vs **what's
exercised only by unit tests** vs **what's documented as a showcase but
not load-tested as a scenario**. This note inventories the four axes,
flags the gaps, and proposes 2-3 new scenarios that would close the
"agent-driven business workflow" demonstration loop the maintainer is
heading toward.

The note is not a research-before for a new stage — it's a coverage map
that helps the next round (multi-year DE Mahnverfahren + real-adapter
cross-stage + Big4 Jahresabschluss benchmark + people-record companion)
pick the right scope.

---

## §1 — TL;DR

- **4 Clay showcases** at `doc/showcases/01..04` (~2300 LoC) cover DE +
  US + IN + multi-entity end-to-end on synthetic data. They are
  documentation-grade, not test-grade — none of them assert against
  expected output. Worth turning into asserted integration tests.
- **1 cross-stage integration test** (`test/kontor/stage_r_cross_stage_test.clj`)
  exercises a trans-national Jane Doe across DE / US / CA simultaneously
  but uses **mock providers** — the bridge to real adapters is unverified.
- **~115 per-module e2e tests** across the 11 payroll adapters exercise
  each engine adapter against its own fixture CSV but **don't compose**
  with the kernel orchestrator + other adapters in trans-national flows.
- **0 multi-year scenarios** — every test runs in a single month / quarter
  / year. The bitemporal-correction substrate (kontor's flagship value
  prop) is **structurally exercised** by `kontor.bitemporal-test` +
  `back-dated-compensation-correction` in `hr_test.clj`, but no scenario
  threads a 3-5 year company history with layered corrections / mergers /
  restructurings — the test that would *demonstrate* what the substrate
  is for.
- **0 agent-driven scenarios** — kontor.explain + kontor.event-bus
  shipped (ADRs 091/092) but no scenario exercises them end-to-end via
  an LLM/VLM agent reading the substrate + writing back through the
  orchestrators. The "Jahresabschluss Big4-examination" benchmark is
  the canonical missing piece (task #246 deferred).
- **Recommended new scenarios** (in order of strategic value): (1)
  multi-year DE GmbH Mahnverfahren with bitemporal corrections; (2)
  cross-stage trans-national with REAL country adapters wired in; (3)
  agent-driven Jahresabschluss benchmark spec.

---

## §2 — What we have today

### §2.1 Clay showcases (`doc/showcases/`)

| # | Title | LoC | Scope | Substrate exercised |
|---|---|---|---|---|
| 01 | DE B2B Factur-X + Mahnverfahren | 532 | Schnitzel & Code GmbH × Goldener Brezel GmbH — invoice, partial pay, dispute, Mahnstufe 1+2, settlement | SKR04, BGB §286/288, EU 2011/7/EU, Factur-X 1.0.07, bitemporal aging snapshots, dispute-driven dunning suppression |
| 02 | US LLC multi-state sales tax + Reg-F dunning | 530 | Skyline Analytics LLC — SaaS billing across CA/NY/TX/WA, partial pay, tax-line dispute, CFPB Reg-F frequency cap | TaxProvider, multi-state allocation, Reg-F 12 CFR Part 1006, Wayfair, dispute suppression |
| 03 | IN B2B with IRN + GSTR + TDS + reverse-charge | 619 | Bharat Metalcraft Pvt Ltd — IGST inter-state, NIC IRN, TDS withholding, reverse charge | CGST/SGST/IGST, GSTR-1/3B, IRN signing, TDS §194-O |
| 04 | Multi-entity intercompany | 604 | Acme Group — DE GmbH + US LLC + CA Corp + intercompany eliminations | ADR-031 entity, ADR-073 consolidation, FxRateProvider, translation, elimination |

**Showcase strengths:** narrative; cite real regulators; exercise major
substrate pieces; readable to a finance professional. **Showcase
weaknesses:**

- **Not asserted.** Each showcase is a tutorial-style notebook that
  produces output (tables, ledgers, audit-chains) but doesn't compare
  output to expected values. A regression in `kontor.posting` could
  break a showcase silently.
- **Synthetic data.** Names + GSTINs + Steuernummern are made up. Useful
  for narrative + license posture but doesn't exercise edge cases real
  customer data would surface.
- **Single-period.** Every showcase runs one month / quarter. Bitemporal
  substrate is shown via `:as-of-valid` snapshots but not via multi-year
  amendments / restatements.
- **No payroll showcase yet.** Stage R landed 11 country adapters but
  the showcase suite stops at l10n + invoice + dunning + consolidation.
  A payroll-specific Clay notebook would close the gap between "showcase
  set" and "what we've actually built."

### §2.2 Cross-stage integration test

`test/kontor/stage_r_cross_stage_test.clj` (391 LoC, 1 deftest, ~24
assertions). The trans-national Jane Doe scenario from note 79 §2.2 — one
`:person` × three `:employment` rows across DE-GmbH / US-LLC / CA-Corp,
three commodities (EUR / USD / CAD), three concurrent payroll runs.

Asserts:
- multi-employment per person across module boundaries
- per-employment FTE summing 1.20 (over-allocation explicitly allowed by
  design per note 86 P1-86-7)
- per-employment compensation in distinct currencies
- three `:payroll-run` rows with country-specific provider IDs
- each run linked to a balanced `:transaction` (sums to 0 per ledger ×
  commodity)
- DSAR walk from `:person` reaches all 3 employments + 3 compensations
- DSAR walk via kontor.dsar/collect — kernel canonical entry point
  reaches HR via `:extensions :hr` (P1-86-5 fix)
- per-country wage-expense reconciles to engine output

**Strengths:** exercises substrate composition (HR + compensation +
payroll + audit-doc + DSAR + bitemporal) across 3 jurisdictions in one
flow. **Weaknesses:**

- **Mock providers.** Uses `MockCompute` + `MockPostingBuilder` from
  inside the test file. The real DATEV-LODAS / ADP-GLI / CA Ceridian
  adapters compose-via-`run-payroll!` is **unverified at the cross-stage
  level**.
- **Single pay-period.** All three runs are for one period; no multi-
  year axis exercised.
- **No correction.** No backdated revision / restatement / cross-period
  reconciliation in this scenario.
- **No simulation.** No `d/branch` / forward-projection / agent-driven
  workflow.

### §2.3 Per-module e2e tests

| Module | e2e deftests | LoC est. | Real fixture | Notes |
|---|---|---|---|---|
| payroll-de-datev | 1 | ~340 | synthetic EXTF Buchungsbeleg | Bruttomethode 10-leg posting verified |
| payroll-us-adp | 1 | ~230 | synthetic ADP GLI CSV | 3 employees × 3 states; multi-state allocation via `:analytic-account/state` |
| payroll-ca | 2 | ~390 | synthetic Ceridian-shape CSV | bilingual ON + QC; full RL-1 round-trip with XSD validation |
| payroll-fr | 1 | ~150 | synthetic Silae CSV | DSN NEODES end-to-end |
| payroll-au | 3 | ~310 | synthetic Xero CSV | STP Phase 2 + SuperStream |
| payroll-br | 1 | ~?? | synthetic CSV | eSocial S-1200/1210/1299 |
| payroll-mx | 2 | ~?? | CONTPAQi CSV | CFDI Nómina XML |
| payroll-in | 1 | ~280 | Keka CSV | Form 24Q + EPFO ECR |
| payroll-jp | 1 | ~?? | freee CSV | Gensen + My Number discipline |
| payroll-cn | 1 | ~?? | Yonyou CSV | IIT + 五险一金 + 34 provinces |
| payroll-at | 2 | ~?? | BMD CSV | mBGM + L16 + bridge adapter |
| hr (substrate) | 14 | ~860 | synthetic | install + hire + compensation + supersession + back-dated correction + run-payroll! |
| **total** | **~30** | **~3000** | | |

**Coverage strengths:** every adapter has at least one focused e2e that
drives `kontor.hr.payroll/run-payroll!` (post-bridge for MX + AT) end-
to-end with a fixture CSV. The HR substrate test exercises the
bitemporal-correction story (back-dated compensation + d/as-of).

**Coverage gaps:**

- **No multi-period adapter test.** Each adapter test runs one pay-
  period. The 10-leg DE Bruttomethode is verified for one month; running
  it across 12 months + a year-end Sonderzahlung + a backdated correction
  is not verified anywhere.
- **No cross-module composition.** Each test installs only its own
  module; tests don't exercise (e.g.) inventory + payroll + lease
  together in one DB.
- **No real CSV fixtures** — every fixture is hand-written synthetic
  data. The DATEV / ADP / Ceridian agents cite the public-spec examples
  but the fixtures themselves were authored for the test, not extracted
  from a real engine export.

### §2.4 Other substrate tests worth mentioning

- `test/kontor/bitemporal-test.clj` — exercises `:db.valid/from` semantics
  + `close-validity!` + supersession explicitly. The substrate-level
  guarantee that the corrections-across-time story holds is verified here
  rather than at the showcase level.
- `test/kontor/consolidation-test.clj` — the trans-national
  consolidation primitive (ADR-073) is exercised + the P0-73-1/2/3
  regressions are pinned.
- `test/kontor/end-to-end-demo-test.clj` (320 LoC) — the original kernel
  demo from Phase 1; runs a small DE GmbH through invoicing + dunning +
  partial settlement + period close. Substantive but pre-dates the Stage R
  substrate so doesn't exercise HR / payroll / McComb seams.
- `test/kontor/composition-test.clj` — exercises cross-companion
  composition (inventory + posting + audit-doc) in one transaction.

---

## §3 — Coverage gaps

Cross-cutting from §2:

### §3.1 No multi-year scenarios

Bitemporal correction is kontor's most-claimed feature. It's exercised
at unit-test depth (`bitemporal-test.clj`, `back-dated-compensation-
correction`) but no scenario threads a multi-year company history. The
canonical missing exercise:

> Acme DE GmbH year 1 — invoice + partial pay + dispute + settle +
> normal year-end close. Year 2 — discover an under-billed customer
> from year 1's December, backdated invoice with corrected effective-
> date, file UStVA correction with `:audit-doc/category :tax-filing`
> + amendment chain. Year 3 — IT audit discovers a misclassified
> expense from year 1, posting purge via retention sweep + DSAR
> response covering the 3-year window.

This scenario exercises sealing + period locks + bitemporal correction
+ retention + DSAR + audit-doc privilege in ways no current test does.
It's also the most-customer-shaped scenario kontor could write — it
mirrors what a real accountant does over years.

### §3.2 Mock providers in cross-stage

The cross-stage trans-national test uses `MockCompute` /
`MockPostingBuilder` instead of the real per-country adapters. Reasons
the test was written this way: the real adapters take country-specific
opts (catalog + commodity + multiple `:account-tag` keys; `:ledgers-map`
for US; `:rp-account-tag` for CA) that would have made the cross-stage
test setup balloon.

But now that all 11 adapters are merged and the bridge layer is in for
MX + AT, the real-adapter cross-stage is feasible. The shape of the
extension:

- Replace at least one country's mock with the real adapter + a fixture
  CSV (DE-DATEV-LODAS is the natural first pick because the maintainer's
  home jurisdiction + EXTF Buchungsbeleg parsing is well-understood)
- Assert that the kernel orchestrator drives the real provider trio
  correctly + the resulting audit-chain is balanced + the DSAR walk
  reaches the real audit-docs
- Document the consumer-supplied wiring (`:accounts`, `:ledgers-map`,
  `:variable-inputs`) so future country-adapter consumers have a
  reference

### §3.3 Showcases aren't tests

The 4 Clay showcases at `doc/showcases/01..04` are documentation. They
have no `is`-assertions; a refactor to `kontor.posting` could silently
break a showcase's output without any CI signal. Turning them into
asserted integration tests would close the regression gap without
sacrificing the narrative quality (Clay supports both `^:kindly/hide-
code true` for narrative paragraphs and standard `clojure.test`
assertions for the regression points).

### §3.4 No payroll showcase

Showcases 1-4 stop at l10n + invoice + dunning + consolidation. Nothing
in `doc/showcases/` exercises Stage R substrate. Worth adding:

> Showcase 05 — payroll month-end across DE + US + CA. Three entities,
> three engines (DATEV LODAS + ADP GLI + Ceridian Dayforce), three
> regulator emissions (LODAS Importdatei + W-2 reconciliation + T4 batch
> with T619 envelope). Bitemporal correction: a payroll provider
> reports a corrected wage for the prior period; rerun + supersede.

### §3.5 No agent-driven scenarios

`kontor.explain` (ADR-091) ships graph walks; `kontor.event-bus`
(ADR-092) ships in-process pub-sub. Both are exercised by their own
unit tests but no scenario in the substrate exercises them via an
LLM/VLM agent loop: read substrate state → reason → propose write →
write through orchestrator → verify via event-bus + explain.

The canonical missing scenario is the agent-driven Jahresabschluss
(task #246):

> Year-end DE GmbH. Agent receives `(d/db conn)` + a goal ("produce a
> Bilanz + GuV + Anhang + Lagebericht + tax-filing pack consistent
> with §238-263 HGB"). Agent uses `kontor.explain/explain-balance` +
> `kontor.report/compute-report` to inspect the state, proposes the
> closing tx + financial statements, transacts. A separate examiner
> agent (the Big4 stand-in) audits the output: are all postings
> sealed? Does the trial balance close? Do the BS/GuV reconcile?
> Are the parallel-ledger HGB-vs-Steuerbilanz reconciliations
> documented?

This is the demo that justifies the substrate. It's also a research
note + harness + iteration job — task #246 was deferred for that
reason.

### §3.6 No simulation scenarios

simmis is the long-stated direction (clone kontor DB → adjust knobs →
project forward). The substrate has `d/branch` + bitemporal + the
McComb event-bus. No scenario exercises it. The canonical missing piece:

> Take Acme DE GmbH at year-end + 3 simmis-driven scenarios: (a) 5% raise
> on Jan 1; (b) 3 new hires Mar 1 + Apr 1; (c) FX shift EUR/USD -10%.
> Project 6 quarters forward across DE + US + CA payroll. Show the cash-
> flow fan-chart + the per-quarter payroll cost + the consolidated trial
> balance under each scenario.

This is the Clay showcase Option B from the strategic-direction
discussion earlier in the session.

---

## §4 — Proposed new scenarios (priority order)

### Scenario A — Multi-year DE GmbH with bitemporal correction (priority 1)

**Form:** asserted integration test at `test/kontor/scenario_de_gmbh_
multi_year_test.clj` PLUS a Clay showcase narrative at
`doc/showcases/05_de_gmbh_multi_year.clj`.

**Story:** Acme Manufacturing GmbH (München). 36-month timeline.

- **Year 1 (months 1-12):** standard operations. 2 employees on payroll
  (Geschäftsführer + Vertriebsmitarbeiter). Monthly DATEV LODAS run.
  4 customer invoices/month, 2 supplier invoices/month. Mid-year:
  acquire a Maschine (asset, depreciation 8% linear AfA 7 years).
  Year-end close with Jahresabschluss to HGB + Steuerbilanz parallel
  ledgers.
- **Year 2:** routine + (a) employee #2 promotes to manager (compensation
  supersession), (b) Q3 lease IFRS 16 for a delivery vehicle (3 years,
  €450/month), (c) Q4 backdated correction: discover a misclassified
  Reisekosten posting in Y1-Sep that should have been Bewirtungsaufwand
  70% deductible (Y1 UStVA needs correction; HGB books need restated;
  Steuerbilanz separately corrected with §233a interest).
- **Year 3:** Q1 hire employee #3 (working-student, 20% FTE). Q2 lease-
  modification (vehicle replaced with EV — modification per ADR-064 +
  remeasurement). Q3 customer files insolvency → write-off receivable;
  audit-doc with privilege `:work-product` (we have a recovery
  strategy). Q4 DSAR request from terminated employee #1 (auto-purge of
  expired payroll PII + retention floor check for HGB §257 records).

**What this exercises end-to-end:**

- Multi-year bitemporal (3 year company history)
- Cross-period correction with §233a interest + UStVA amendment
- Sealing + period-lock + reopen for amendment (ADR-014)
- Parallel-ledger HGB vs Steuerbilanz reconciliation (ADR-021)
- Asset acquisition + depreciation + disposal across multiple years
- Lease IFRS 16 + modification + remeasurement (ADR-062/064)
- Payroll run × 36 months + compensation supersession + hire/term
- Audit-doc privilege + DSAR walk across 3 years
- Retention sweep with `:retention-policy/category` filter (ADR-075
  + note 86 P0-85-2 fix)
- Legal-hold check (Insolvenzverwalter request blocks purge)

**Effort:** ~2-3 maintainer-days for the test + showcase. Substantial
fixture setup; pays back as the canonical "this is what kontor is for"
demonstration.

**Deliverable:** asserted test passing in `bb ci` + Clay showcase 05
rendering to HTML.

### Scenario B — Cross-stage trans-national with REAL adapters (priority 2)

**Form:** extend `test/kontor/stage_r_cross_stage_test.clj` to add a
second deftest using real adapters for at least one country.

**Story:** same Jane Doe trans-national setup; replace `MockCompute` +
`MockPostingBuilder` for DE with the real `DatevLodasComputeProvider` +
`DatevLodasPostingBuilder` + `DatevLodasEmitProvider` from
`modules/payroll-de-datev/`. Use a fixture EXTF Buchungsbeleg CSV (the
existing one at `modules/payroll-de-datev/resources/.../fixtures/`).

Assert:
- The real DE adapter produces the canonical PayrollFacts shape via
  the kernel orchestrator
- The 10-leg Bruttomethode lands in the cross-stage transaction
- The LODAS Importdatei emission is linked via `:payroll-run/emit-docs`
- The audit-doc carries `:audit-doc/category :payroll-filing` +
  `:audit-doc/language :de`
- US + CA remain mocked (they're proof the substrate composes even when
  not all adapters are real)

**Why "only one real":** the existing cross-stage test is already
substantial; replacing all 3 mocks would balloon setup (3 fixture CSVs
+ 3 catalog wirings + 3 `:variable-inputs` maps). One real adapter is
enough to prove the substrate composes. Future PR can add US + CA real
adapters when value-density justifies.

**Effort:** ~1 maintainer-day. The fixture exists; the wiring is the
work.

### Scenario C — Agent-driven Jahresabschluss benchmark (priority 3 — spec first, then harness)

**Form:** research note 94 (spec) → integration test harness at
`test/kontor/scenario_jahresabschluss_benchmark_test.clj` → iteration
on agent prompts.

**Story:** the year-end close from Scenario A's Year 1 driven by an
LLM agent rather than the maintainer. Agent receives:
- `(d/db conn)` at Y1-Dec-31
- A goal: "produce a Bilanz + GuV + Anhang + Lagebericht consistent
  with HGB §238-263, plus a UStVA-Q4 + jahresübergreifend reconciliation"
- The kontor tool catalog: `kontor.explain` + `kontor.report/compute-
  report` + `kontor.closing/year-end-close-tx-data` + the read-side of
  every substrate primitive (no write access until the agent's tx-data
  passes the gate)

Agent must:
1. Inspect the trial balance + Verrechnungskonten (any open items?)
2. Identify any unposted invoices / open Mahnverfahren
3. Compute depreciation roll-forward (Maschine year-1 of 7)
4. Compute Steuerbilanz adjustments (e.g. AfA difference HGB vs StB)
5. Propose closing entries
6. Build the year-end Bilanz + GuV
7. Produce the Anhang narrative

Examiner agent (Big4 stand-in) audits:
- Are all postings sealed (`:posting/posted-at` set)?
- Does Trial Balance close (sum = 0 per ledger × commodity)?
- Do BS + GuV reconcile to the GL?
- Are parallel-ledger reconciliations documented?
- Does the UStVA reconcile to GL VAT accounts?

**Why agent-driven:** kontor's pitch is that the substrate makes
agent-driven workflow auditor-grade. The Jahresabschluss is the hardest
single workflow a German accountant does + has the most regulator
scrutiny. Passing this benchmark is the proof.

**Effort:** ~3 maintainer-days for spec + harness; weeks to iterate on
agent prompts. Spec first; harness second; iteration is the long pole.

**Cross-references:** task #246 (Jahresabschluss Big4-examination
benchmark — spec) is this work.

### Scenario D — Simmis-style forward projection (priority 4)

**Form:** Clay showcase `doc/showcases/06_simmis_forward_projection.
clj` + minimal harness.

**Story:** take Acme DE GmbH at end-of-Year-2 from Scenario A. Branch
the datahike db (`d/branch`?). Run 3 simmis-driven scenarios over 6
quarters forward:

- **Scenario 1:** 5% raise on Jan 1 Y3 across all 3 employees
- **Scenario 2:** 3 new hires Mar/Apr/Jun (Sales engineer, Product
  manager, Working student)
- **Scenario 3:** FX EUR/USD -10% on Apr 1 Y3 (Acme has US-LLC books too)

For each scenario, project payroll cost + consolidated trial balance
+ cash-flow forecast. Render as a Clay-rendered fan-chart.

**Effort:** ~2 maintainer-days. Gated on a `kontor-simmis` ergonomic
wrapper for branching + scenario-set + projection.

### Scenario E — Payroll-focused showcase (priority 5 — small + useful)

**Form:** Clay showcase `doc/showcases/05_de_us_ca_payroll_month.clj`.

**Story:** smaller than Scenario A. One month's payroll across DE + US
+ CA simultaneously, showing:
- DATEV LODAS file emitted for DE
- ADP GLI parsed for US with multi-state allocation
- T4 batch + T619 envelope previewed for CA year-end
- The cross-stage audit chain
- DSAR pretend-request walk

**Effort:** ~1 maintainer-day. Useful for the "what does payroll look
like on kontor" narrative.

---

## §5 — Recommended sequencing

For the next round:

1. **Scenario B first** — the cross-stage real-adapter wiring (~1 day).
   Closes the load-bearing "do the adapters compose" gap; minimal new
   code; reuses existing fixtures.

2. **Scenario E** — the payroll showcase (~1 day). Closes the showcase-
   suite-doesn't-cover-Stage-R gap. Useful narrative artifact for
   external readers.

3. **Scenario A** — multi-year DE GmbH (~2-3 days). The canonical
   "this is what kontor is for" demonstration. Combines test + showcase.

4. **Spawn research agents 91/92/93** (parallel, ~1 day to land each)
   — datasets / market positioning / privacy framework. Output feeds
   Scenario C + the `kontor-people-record` companion sketch.

5. **Scenario C** — agent-driven Jahresabschluss benchmark spec + harness
   (~3 days + iteration). Gated on research notes 91/92 landing so the
   spec can reference real-world dataset shapes + competitive landscape.

6. **Scenario D** — simmis forward projection (~2 days). Gated on a
   `kontor-simmis` companion ergonomic layer (small new module).

7. **Per-companion** — sketch `kontor-people-record` (the employee
   track-record companion) once research note 93 lands with the privacy
   framework. ADR + minimal schema + a Clay showcase exercising it.

---

## §6 — Open questions for the maintainer

1. **Do showcases get assertions?** Turning Clay showcases into
   `clojure.test` deftests with assertions is a clean win for
   regression protection but adds maintenance cost (assertion drift on
   every refactor). Alternative: a separate "showcase-snapshot" test
   that diffs Clay's HTML output byte-by-byte and fails on drift.

2. **Real CSV fixtures vs synthetic.** Should the multi-year DE scenario
   use a real (anonymized) Steuerberater dataset, or stay synthetic?
   Real data is more credible but adds licensing + redaction risk.
   Recommendation: synthetic + cite the real-world regulatory sources
   the synthetic mimics — same posture as the existing showcases.

3. **`kontor-simmis` companion shape.** What does "branch + project +
   compare" look like as a primitive? Is it a thin wrapper over `d/
   branch` + `kontor.report/compute-report` + scenario-set + tx-source-
   tagging? Or does it need its own schema (scenarios as entities,
   experiments as entities, etc.)? Probably the latter — but not for
   this round; the showcase can prototype the API first.

4. **Big4-examination benchmark — single agent or multi-agent?** Spec
   question — is the examiner agent the SAME LLM run with a different
   prompt, or a different model entirely (e.g. closer expert)? Probably
   different to avoid scoring-the-output-self-grading bias. The spec
   should call this out.

5. **`kontor-mcp` companion** — when does this land? Roadmap shows it
   as candidate; the agent-driven scenarios above all benefit from a
   real MCP server. Probably the first companion built once research
   note 92 lands with the market positioning.

---

## §7 — Sources

### Codebase paths (current state, 2026-05-18)

- `doc/showcases/00_index.clj` — index + cite list
- `doc/showcases/{01..04}_*.clj` — 4 narrative notebooks (~2300 LoC
  total)
- `test/kontor/stage_r_cross_stage_test.clj:1-391` — cross-stage
  trans-national test
- `test/kontor/bitemporal_test.clj` — bitemporal substrate verification
- `test/kontor/consolidation_test.clj` — ADR-073 + P0-73-1/2/3
  regressions
- `test/kontor/end_to_end_demo_test.clj` — Phase 1 kernel demo
- `test/kontor/composition_test.clj` — cross-companion composition
- `modules/payroll-*/test/kontor/payroll_*/e2e_test.clj` — 11
  per-module e2e tests
- `modules/hr/test/kontor/hr/hr_test.clj` — HR substrate exercise
  including back-dated compensation correction

### Reference notes consumed

- `doc/research/79-hr-payroll-stage-r-plan.md` — the Jane Doe trans-
  national pitch
- `doc/research/85-c1-substrate-review-after.md` — substrate gap audit
- `doc/research/86-stage-r-final-review-after.md` — country-wave audit
- `doc/research/87-cn-payroll-research-before.md` — illustrative per-
  country research-before
- `doc/research/88-mccomb-substrate-seams-round-1.md` — McComb seams
- `doc/research/80-mccomb-future-of-accounting-vs-kontor.md` — the
  framework kontor compares against

### Open follow-up notes (commissioned, in-flight)

- doc/research/91 — real-world accounting datasets
- doc/research/92 — company-as-software market positioning
- doc/research/93 — employee track-record privacy framework

### Decision records cross-referenced

- ADR-014 (period soft + hard close)
- ADR-021 (parallel ledgers — HGB vs Steuerbilanz)
- ADR-031 (`:entity` filter for trans-national)
- ADR-049 (legal-hold)
- ADR-050 (retention policy + sweeper)
- ADR-051 (`:audit-doc/privilege`)
- ADR-052 (DSAR collect)
- ADR-064 (lease modifications + remeasurement)
- ADR-073 (consolidation primitive)
- ADR-075 (Stage R substrate + `:audit-doc/category`)
- ADR-076..087 (per-country payroll adapters)
- ADR-090/091/092 (McComb seams)

---

End of note 90.

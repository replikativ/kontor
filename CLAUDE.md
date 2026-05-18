# CLAUDE.md

Guidance for Claude Code and other AI assistants working in this repository.

## Project in one paragraph

`kontor` is a double-entry accounting **kernel** built on [datahike](https://github.com/replikativ/datahike). It supplies the schema, posting/balance/period semantics, tax engine, sealing/audit story, and bitemporal queries that an accounting workload needs. **It does not ship a UI, an ERP, or country-specific data**; consumer apps (beleg, simmis) and per-country localization modules (`kontor-l10n-*`) compose on top.

The library is **EPL-1.0**, **Clojure-only**, **single dependency** (datahike). Read [doc/decisions.md](doc/decisions.md) before any non-trivial change — every locked design choice has an ADR there.

## Where to start

1. [doc/decisions.md](doc/decisions.md) — every architectural choice with rationale (ADR-001 .. ADR-074, with ADR-067/068 codifying the `kontor.process` + `*-tx-data` builder convention, and ADR-071/072/073/074 the trans-national substrate)
2. [doc/architecture.md](doc/architecture.md) — the layer cake, namespace map, kernel module list, provider-protocol surface
3. [doc/roadmap.md](doc/roadmap.md) — phased plan with acceptance criteria per phase
4. [doc/research/00-index.md](doc/research/00-index.md) — point-in-time research that informed the decisions (78 entries as of 2026-05-18)
5. [src/kontor/schema.clj](src/kontor/schema.clj) — kernel schema (the source of truth for entities and attributes)
6. [doc/value.md](doc/value.md) — for evaluators / business stakeholders; [doc/programming.md](doc/programming.md) — for Clojure developers (transact gate + status machines + bitemporal substrate)
7. [doc/showcases/](doc/showcases/) — four end-to-end notebooks: DE B2B Factur-X, US LLC multi-state, IN B2B IRN+TDS, multi-entity intercompany

## How to work in this repo

### Iteration loop

This project is being built test-first. The canonical loop:

1. Pick a slice from `doc/roadmap.md` (e.g., "trial balance bitemporal").
2. Write the failing test in `test/kontor/<slice>_test.clj`.
3. Implement in `src/kontor/<slice>.clj`.
4. `bb test` (uses kaocha).
5. Update `doc/roadmap.md` checkbox; if the slice produced a new design choice, add an ADR to `doc/decisions.md` and reference it from the code.

### Use the REPL for the inner loop

`bb nrepl` (or `clojure -M:dev -m nrepl.cmdline --middleware '[cider.nrepl/cider-middleware]'`) boots a Clojure REPL with test sources on the classpath and nREPL listening. The user's `clj-nrepl-eval -p <port>` is the canonical way to talk to it from a shell.

**The loop**:

```bash
clj-nrepl-eval --discover-ports                     # find the running port
clj-nrepl-eval -p 43781 "(require 'kontor.posting :reload)"
clj-nrepl-eval -p 43781 "(require 'kontor.posting-test :reload) \
                          (clojure.test/test-vars [#'kontor.posting-test/end-to-end-balanced-tx-transacts])"
```

≈ 200ms per cycle vs bb's full-JVM ~10s. Use this for every iteration; reserve `bb ci` for the final pre-commit check.

The schema fixture `(kontor.core/create-test-db)` returns a fresh in-memory connection in ~50ms. Use it freely from the REPL to poke at queries.

For schema-shape questions ("what should `:posting/foo` look like?"), the running pg-datahike + Odoo install on `:15433` is a useful reference oracle — see `../pg-datahike/test/integration/odoo/README.md`. We do not lift Odoo's schema verbatim; we use it to cross-check that we haven't missed a real-world concern.

### When you make a non-trivial decision

Add an ADR to `doc/decisions.md`. Number it sequentially. Link to it from any code comment that depends on it. The ADR documents *why*, not *what* — the code documents *what*.

### When you finish a roadmap slice

Tick the checkbox in `doc/roadmap.md` AND make sure the per-phase acceptance criterion is genuinely met. Don't tick prematurely.

### Per-stage rhythm (Stage I onward, codified by ADR-037)

Each substantial stage (J, K, L, …) follows a three-step pattern. **Don't skip steps.**

**1. Research-before.** Spawn 2-3 background agents in parallel:
- **Reference study** — deep read of a license-clean reference implementation (OFBiz Apache-2.0, Sylius MIT, KillBill Apache-2.0) at file:line depth. Output: research note in `doc/research/`.
- **Market-pain study** — online research (vendor docs, OSS issue trackers, RFP commentary, customer reviews on G2/Capterra/Trustpilot) on what real customers complain about in this domain. Output: pain-point list with severity + remediation hints in `doc/research/`.
- **Internal gap analysis** (optional) — what does the current kontor substrate already provide, what's clearly missing, what's ambiguous and needs a design call.

Synthesize the agent reports BEFORE writing any code. Use AskUserQuestion to surface design calls the research surfaces.

**2. Implement.** Draft the stage's ADR(s) in `doc/decisions.md`. Schema → helpers → tests. Commit per coherent unit (one ADR per commit typically). Run `bb test` after each commit.

**3. Review-after.** Spawn 1-2 background agents in parallel:
- **Code-review agent** — independent audit of the new ADRs + schema + tests against the local code. Hunts for P0 ship-blockers, P1 issues, P2 followups with file:line citations.
- **Market-pain review** — audit the implementation against the pain points from step 1. Catches gaps that purely-code review misses.

Fix the P0s before the stage is "done." P1s and P2s get triaged into followups or rolled into later stages. Capture cross-cutting findings in a research note.

**Token economics.** This rhythm is expensive (~5-10 agents per stage at ~$1-5 each, plus the implementation tokens). It pays for itself when the alternative is rework: per the project's culture, "sloppy initially → more churn / iterations later." Five hours of agent research up-front beats five days of refactoring out of bad design choices later.

### Cross-stage user-story validation

After 2-3 stages land, run **end-to-end user stories** through the substrate to find integration friction the per-stage rhythm can't see. Each user story is a concrete scenario:

- DE GmbH B2B with Factur-X invoicing.
- US LLC with multi-state sales tax (Avalara via `TaxProvider`).
- Brazilian retailer with NF-e clearance.
- Indian B2B with IRN + GSTR + TDS withholding.
- Multi-entity intercompany (parent ↔ subsidiary in different currencies).
- SaaS subscription with deferred revenue + monthly recognition.
- Project-based services billing with timesheet-as-analytic-line.

For each story:
1. Write the smallest end-to-end integration test that exercises the substrate.
2. Note every friction point (missing helper, wrong default, awkward API, undocumented invariant).
3. Triage: which frictions deserve fixes vs documentation vs a new primitive.
4. Smooth out the worst friction before the next stage.

Friction discovered this way is the ground truth — the per-stage research informs design, but user stories reveal what actually works in practice.

## Conventions

### Namespacing

Every datahike attribute namespaces under one of: `:account/* :journal/* :transaction/* :posting/* :commodity/* :lot/* :tax/* :tax-rep/* :tax-group/* :account-tag/* :partner/* :fiscal-position/* :period/* :balance-assertion/*`. New namespaces require an ADR.

This convention is what lets kontor cohabit with beleg in one DB (ADR-002).

### Money

Always `BigDecimal` + commodity tag. Never doubles. The `Money` type in `src/kontor/money.clj` is the canonical representation. Rounding is HALF-EVEN unless a regulator mandates otherwise (some VAT jurisdictions require HALF-UP — those are documented case-by-case in l10n modules).

### Bitemporal

Every query that reads accounting data takes `:as-of-tx` and `:as-of-valid` parameters, defaulting to `now`/`now`. New query helpers must make this explicit, not implicit. ADR-008.

### Sealing

A posting transitions from "draft" to "posted" by setting `:posting/posted-at`. After that, *silent* retraction is forbidden by the middleware in `sealing.clj`. **Explicit `:db/purge` IS allowed** but is itself a recorded commit; the audit story is "the chain documents the purge", not "deletion is impossible". ADR-007.

### Tax

The `TaxProvider` protocol is the only abstraction the kernel uses to compute taxes. Per-country tax data lives in `kontor-l10n-<cc>` artifacts; the kernel ships `StaticTableProvider` as a default impl. Avalara/TaxJar adapters scaffold but do not bundle API keys. ADR-005.

## What NOT to do

- **Do not translate Odoo Python.** FSF treats translation as derivative work; LGPLv3 follows. Reference Odoo's source for *patterns*; write our own implementation. ADR-001 + research note 01.
- **Do not lift Tryton code.** GPLv3 contagion; bad for our consumers. Tryton is a reference design, not a code source.
- **Do not bundle Avalara/TaxJar/TaxCloud API keys or rate data.** Every customer holds their own. ToS-restricted. ADR-005, research note 03.
- **Do not add a UI.** Not even "just a small one." UI lives in consumers (beleg HTMX, simmis Replicant). ADR-010.
- **Do not introduce a second runtime** (no JS, no Python helpers, no shell scripts beyond `bb`). The whole stack is JVM Clojure + datahike.
- **Do not write a US sales tax engine.** We provide the protocol; customers integrate Avalara/TaxJar. ADR-005, ADR-010.
- **Do not silently retract a posted entity.** Use `:db/purge` if the data legitimately needs deletion; the purge becomes its own commit and the audit chain documents it. ADR-007.

## Relationship to nearby projects

- **`../pg-datahike`** — Postgres-wire-protocol shim over datahike. Currently used to validate that real Odoo runs against datahike (see its `test/integration/odoo/`). Useful as a reference oracle for schema decisions, not a runtime dependency.
- **`../beleg`** — contractor invoice management. Will become the *first consumer* of `kontor`: posting an issued invoice writes both `:invoice/status` and the matching `:transaction` + `:posting`s in one tx. ADR-002.
- **`../simmis`** — distributed-scope ClojureScript app. Long-term consumer for ERP-shaped workloads on top of `kontor` + `../spindel`'s reactive primitives.
- **`../spindel`** — incremental reactive computation; the simulation/computation engine simmis builds on. Not a direct dep here, but design-aligned (event-sourced, deterministic recomputation).
- **`../odoo`** — Odoo 19 source. Read-only reference oracle. We do not lift its code.

## Useful one-liners

```bash
bb test                # kaocha unit tests
bb nrepl               # Clojure REPL + nREPL on a free port
bb format              # cljfmt check
bb ffix                # cljfmt fix
bb lint                # clj-kondo
bb ci                  # all of the above
```

```clojure
;; In the REPL: open an in-memory accounting DB
(require '[kontor.core :as a])
(def conn (a/create-test-db))    ; ephemeral, schema loaded
(a/post-transaction! conn ...)
```

## File layout summary

```
LICENSE                     EPL-1.0
deps.edn                    minimal — only datahike
bb.edn                      babashka tasks
tests.edn                   kaocha config
CLAUDE.md                   this file
README.md                   short user-facing summary
doc/
  decisions.md              architectural decisions (ADR-001 .. ADR-074)
  architecture.md           layer cake + module map + provider protocols
  conventions.md            transactor opts, status-machine writes, vt stamping, namespacing, money discipline
  roadmap.md                phased plan with acceptance criteria
  value.md                  evaluator-facing "what kontor IS"
  programming.md            developer-facing transact-gate / status-machine / bitemporal model
  research/                 78 point-in-time research notes (00-index.md is the table of contents)
  showcases/                01-04 end-to-end Clay notebooks (DE / US / IN / multi-entity)
src/kontor/    kernel
  schema.clj                schema EDN (kernel attrs across ~30 namespaces)
  core.clj                  create-test-db + install-schema! + provider registration
  money.clj                 Money + arithmetic (ADR-013)
  posting.clj               build-transaction, sum-to-zero, multi-(entity, ledger, commodity)
  balance.clj / ledger.clj  account-balance + postings-against-account, bitemporal-aware
  trial.clj / closing.clj   trial-balance + year-end retained-earnings rollup
  period.clj                open/close periods + soft/hard lock (ADR-014)
  bitemporal.clj            :tx/valid-from + resolver + close-validity (ADR-048)
  query.clj                 bitemporal convenience helpers
  sealing.clj               :posted-at + middleware (ADR-007)
  audit.clj                 commit-hash wrapper (ADR-003)
  audit_doc.clj             :audit-doc + :approval-policy (ADR-038)
  status_machine.clj        :status-transition + :status-history + record-status-change! (ADR-034)
  state_machine.clj         :transaction/state lifecycle (kernel-internal)
  side_effect.clj           :side-effect-intent dispatcher (ADR-041)
  side_effect/cross.clj     :cross-tx/step-id + CrossTxRouter + drain! (ADR-074)
  schedule.clj              :schedule recurring postings (ADR-032)
  process.clj               kontor.process / run-process orchestrator (ADR-067)
  tax_provider.clj          legacy TaxProvider (kept for back-compat; ADR-005 / superseded by ADR-071)
  tax_rate_provider.clj     TaxRateProvider + TaxFacts (ADR-071)
  tax.clj                   apply-tax to a posting
  fx_rate_provider.clj      FxRateProvider protocol + StaticTable / ECB / Chained (ADR-072)
  fx.clj                    Money-level convert / translate / to-functional-currency
  consolidation.clj         translate + eliminate + consolidate! (ADR-073)
  costing_provider.clj      CostingProvider (ADR-029); valuation.clj for :valuation-book
  einvoice_provider.clj     EInvoiceProvider + PureXmlProvider (ADR-017)
  payment_application.clj   partial-payment primitive (ADR-043)
  reconciliation.clj        bank-line → transaction scaffolding
  bank_account.clj          :bank-account helpers (ADR-039)
  bank_csv.clj              generic CSV importer
  payment_term.clj / aging.clj  due-date + AR aging
  entity.clj                :entity helpers + family walk (ADR-031)
  report.clj                declarative report engine (+ :translate-to per ADR-072)
  financial_statements.clj  BS / P&L generators
  validation.clj            datopia/invariant middleware (ADR-011)
  document/                 sub-namespace (e.g. document/invoice.clj after the namespace-collision rename)
  import_/beancount.clj     Beancount round-trip (ADR-009)
test/kontor/   tests, mirroring src
modules/       companion modules + l10n + bank importers (each a separate Maven artifact)
```

## Recent ADRs + research (since last refresh)

- **ADR-067** — `kontor.process`: multi-step transactional processes as pure step-lists.
- **ADR-068** — every business write exposes a `*-tx-data` builder; the `!` wrapper routes through `transact-with-validation`.
- **ADR-069** — `kontor-lease`: mid-life portfolio import via `import-lease!`.
- **ADR-070** — `kontor-lease`: disclosure-support deltas + discount-rate audit-doc.
- **ADR-071** — Tax abstraction: `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder` (supersedes ADR-005's single `TaxProvider`).
- **ADR-072** — `FxRateProvider` protocol + `:fx-rate/*` schema + `kontor.fx` Money-level translation. ECB attribution required; no rates bundled.
- **ADR-073** — Consolidation primitive: `translate-trial-balance-tx-data` + `eliminate-intercompany-pair-tx-data` + `consolidate!` over `kontor.entity/family`.
- **ADR-074** — `kontor.side-effect.cross`: cross-DB saga primitive via `CrossTxRouter` + content-hash `:cross-tx/step-id` idempotency.
- **ADR-087** — `kontor-payroll-ca` C4.1: Quebec RL-1 + RL-1 Summary (`RLZ-1.S`) + TPZ-1015 monthly remittance + `QcPayrollEmitProvider` + `:employer-fss` wage type. Closes the C4 QC carve-out deferral from ADR-078; partner XSD NOT shipped (clean-room from public RL-1.T-V / RLZ-1.S-G-V forms).
- **`:account-tag/concept-iri`** (no new ADR; commit `9a160aa`) — substrate seam for XBRL / filing taxonomies per research note 78.

Research notes 50-78 land between 2026-05-15 and 2026-05-17 (28 new notes):

- **50** banking-as-consumer; **51** tax-authority-as-consumer; **52** single- vs double-entry survey; **53** v2 consolidation recs (drove value/programming split); **54** simmis UI integration.
- **55-63 + 66-68 + 77** the bitemporal substrate arc — XTDB v1/v2 comparison, datahike `feature/bitemporal-v1` upstream cut, stratum `feature/valid-time`, terminology recommendation, the kontor port from `:posting/valid-from` → `:tx/valid-from` → `:db.valid/from`, the supersession comparison driving `close-validity!`.
- **69** architecture review + clean FP model (drove ADR-071/072/073); **70** tax abstraction design; **71** cross-DB atomic transact (drove ADR-074); **75** kontor+stratum integration plan; **76** review-after ADR-071/072/073 (all P0s closed same-day).
- **72-74** HR / payroll research-before bundle (Stage R gating).
- **78** XBRL + accounting taxonomies (drove `:account-tag/concept-iri`).

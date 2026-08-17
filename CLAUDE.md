# CLAUDE.md

Guidance for Claude Code and other AI assistants working in this repository.

## Project in one paragraph

`kontor` is a double-entry accounting **kernel** built on [datahike](https://github.com/replikativ/datahike). It supplies the schema, posting/balance/period semantics, tax engine, sealing/audit story, and bitemporal queries that an accounting workload needs. **It does not ship a UI, an ERP, or country-specific data**; consumer apps (beleg, simmis) and per-country localization modules (`kontor-l10n-*`) compose on top.

The library is **Apache 2.0**, **Clojure-only**, with a **single substrate dependency** (datahike). The kernel pulls one additional small dep — `instaparse` — for the Beancount round-trip helper (ADR-009 Phase-1 acceptance test); companion modules add `org.clojure/data.csv` (bank importers), `org.clojure/data.xml` (per-country information returns), `org.clojure/data.json` (e-invoice / IRN payloads) and `org.mustangproject/library` (Factur-X PDF generation). Kernel-only consumers can drop the companion-specific entries from their `deps.edn`. Read [doc/decisions.md](doc/decisions.md) before any non-trivial change — every locked design choice is distilled there (with pointers into [doc/decisions-history.md](doc/decisions-history.md) for full ADR rationale).

## Where to start

1. [doc/decisions.md](doc/decisions.md) — distilled architecture decisions (~30 entries, topic-grouped). Full historical record in [doc/decisions-history.md](doc/decisions-history.md) (ADR-001 .. ADR-107).
2. [doc/architecture.md](doc/architecture.md) — the layer cake, namespace map, kernel module list, provider-protocol surface
3. [doc/roadmap.md](doc/roadmap.md) — phased plan with acceptance criteria per phase
4. [src/kontor/schema.clj](src/kontor/schema.clj) — kernel schema (the source of truth for entities and attributes)
5. [doc/value.md](doc/value.md) — for evaluators / business stakeholders; [doc/programming.md](doc/programming.md) — for Clojure developers (transact gate + status machines + bitemporal substrate)
6. [doc/showcases/](doc/showcases/) — four end-to-end notebooks: DE B2B Factur-X, US LLC multi-state, IN B2B IRN+TDS, multi-entity intercompany
7. `.internal/research/00-index.md` — local-only point-in-time research notes (gitignored; not part of the public repo). The substrate ADRs in `decisions.md` are the public-facing distillation.

## How to work in this repo

### Iteration loop

This project is being built test-first. The canonical loop:

1. Pick a slice from `doc/roadmap.md` (e.g., "trial balance bitemporal").
2. Write the failing test in `test/kontor/<slice>_test.clj`.
3. Implement in `src/kontor/<slice>.clj`.
4. `clojure -M:test` (kaocha).
5. Update `doc/roadmap.md` checkbox; if the slice produced a new design choice, append an ADR to `doc/decisions-history.md` (the chronological record) and — only if the decision is load-bearing for new readers — also surface it in `doc/decisions.md` (the distilled set).

### Use the REPL for the inner loop

`clojure -M:dev -m nrepl.cmdline --middleware '[cider.nrepl/cider-middleware]'` boots a Clojure REPL with test sources on the classpath and nREPL listening. The user's `clj-nrepl-eval -p <port>` is the canonical way to talk to it from a shell.

**The loop**:

```bash
clj-nrepl-eval --discover-ports                     # find the running port
clj-nrepl-eval -p 43781 "(require 'kontor.posting :reload)"
clj-nrepl-eval -p 43781 "(require 'kontor.posting-test :reload) \
                          (clojure.test/test-vars [#'kontor.posting-test/end-to-end-balanced-tx-transacts])"
```

≈ 200ms per cycle vs a fresh-JVM `clojure -M:test` ~10s. Use this for every iteration; reserve the format/lint/test trio for the final pre-commit check.

The schema fixture `(kontor.core/create-test-db)` returns a fresh in-memory connection in ~50ms. Use it freely from the REPL to poke at queries.

For schema-shape questions ("what should `:posting/foo` look like?"), the running pg-datahike + Odoo install on `:15433` is a useful reference oracle — see `../pg-datahike/test/integration/odoo/README.md`. We do not lift Odoo's schema verbatim; we use it to cross-check that we haven't missed a real-world concern.

### When you make a non-trivial decision

Add an ADR to `doc/decisions-history.md`. Number it sequentially (next after ADR-107). Link to it from any code comment that depends on it. The ADR documents *why*, not *what* — the code documents *what*. If the new decision is load-bearing for new readers, also update the relevant section in `doc/decisions.md` (the distilled set).

### When you finish a roadmap slice

Tick the checkbox in `doc/roadmap.md` AND make sure the per-phase acceptance criterion is genuinely met. Don't tick prematurely.

### Per-stage rhythm (Stage I onward, codified by ADR-037)

Each substantial stage (J, K, L, …) follows a three-step pattern. **Don't skip steps.**

**1. Research-before.** Spawn 2-3 background agents in parallel:
- **Reference study** — deep read of a license-compatible reference implementation at file:line depth, in private. Output: research note in `.internal/research/` (local, gitignored).
- **Market-pain study** — online research (vendor docs, OSS issue trackers, RFP commentary, customer reviews on G2/Capterra/Trustpilot) on what real customers complain about in this domain. Output: pain-point list with severity + remediation hints in `.internal/research/`.
- **Internal gap analysis** (optional) — what does the current kontor substrate already provide, what's clearly missing, what's ambiguous and needs a design call.

Synthesize the agent reports BEFORE writing any code. Use AskUserQuestion to surface design calls the research surfaces.

**2. Implement.** Draft the stage's ADR(s) in `.internal/decisions-history.md`. Schema → helpers → tests. Commit per coherent unit (one ADR per commit typically). Run `clojure -M:test` after each commit. Update `doc/decisions.md` if the new decision belongs in the distilled public set.

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

Every datahike attribute the kernel owns is prefixed with `:kontor.*`
to avoid silent collisions when kontor's schema cohabits with consumer
apps in the same datahike connection (note 173 / W1 schema sweep).
Kernel attrs live under: `:kontor.account/*`, `:kontor.journal/*`,
`:kontor.transaction/*`, `:kontor.posting/*`, `:kontor.posting-dimension/*`,
`:kontor.commodity/*`, `:kontor.lot/*`, `:kontor.tax/*`, `:kontor.tax-rep/*`,
`:kontor.vat-group/*` (was `:tax-group/*` — semantic rename), `:kontor.account-tag/*`,
`:kontor.partner/*`, `:kontor.person/*`, `:kontor.fiscal-position/*`,
`:kontor.period/*`, `:kontor.balance-assertion/*`, `:kontor.entity/*`,
`:kontor.ledger/*`, `:kontor.audit/{create-uid,write-uid}` (audit-trail),
`:kontor.audit-doc/*`, `:kontor.status-transition/*`, `:kontor.status-history/*`.
Companion modules own `:kontor.<companion>/*` (e.g. `:kontor.asset/*`,
`:kontor.lease/*`, `:kontor.hr.person/*` — dotted sub-namespace extends
the kernel person ref). New top-level namespaces require an ADR.

This convention is what lets kontor cohabit with beleg in one DB (ADR-002).

**Schema attrs are flat; code namespaces are clustered.** A datahike
attribute like `:kontor.audit-doc/category` stays under the single
`kontor.compliance.audit-doc` schema namespace forever — that's what the
substrate sees and what consumer queries write against. But the
*Clojure namespace* that implements it lives at
`kontor.compliance.audit-doc` (file
`src/kontor/compliance/audit_doc.clj`). Do not "promote" schema-
attribute namespaces to mirror the cluster prefix; that would
silently break every consumer's queries. Note 160 §C.

### Money

Always `BigDecimal` + commodity tag. Never doubles. The `Money` type in `src/kontor/money.clj` is the canonical representation. Rounding is HALF-EVEN unless a regulator mandates otherwise (some VAT jurisdictions require HALF-UP — those are documented case-by-case in l10n modules).

### Bitemporal

Every query that reads accounting data takes `:as-of-tx` and `:as-of-valid` parameters, defaulting to `now`/`now`. New query helpers must make this explicit, not implicit. ADR-008.

### Sealing

A posting transitions from "draft" to "posted" by setting `:posting/posted-at`. After that, *silent* retraction is forbidden by the middleware in `sealing.clj`. **Explicit `:db/purge` IS allowed** but is itself a recorded commit; the audit story is "the chain documents the purge", not "deletion is impossible". ADR-007.

### Tax

The `TaxProvider` protocol is the only abstraction the kernel uses to compute taxes. Per-country tax data lives in `kontor-l10n-<cc>` artifacts; the kernel ships `StaticTableProvider` as a default impl. Avalara/TaxJar adapters scaffold but do not bundle API keys. ADR-005.

## What NOT to do

- **Do not lift code from third-party projects** without verifying license compatibility and adding proper attribution. Ask if unsure.
- **Do not bundle Avalara/TaxJar/TaxCloud API keys or rate data.** Every customer holds their own. ToS-restricted. ADR-005, research note 03.
- **Do not add a UI.** Not even "just a small one." UI lives in consumers (beleg HTMX, simmis Replicant). ADR-010.
- **Do not introduce a second runtime** (no JS, no Python helpers, no shell scripts beyond what `clojure` aliases need). The whole stack is JVM Clojure + datahike.
- **Do not write a US sales tax engine.** We provide the protocol; customers integrate Avalara/TaxJar. ADR-005, ADR-010.
- **Do not silently retract a posted entity.** Use `:db/purge` if the data legitimately needs deletion; the purge becomes its own commit and the audit chain documents it. ADR-007.

## Useful one-liners

```bash
clojure -M:test                     # kaocha unit tests (JVM)
clojure -M:dev -m nrepl.cmdline \
        --middleware '[cider.nrepl/cider-middleware]'   # REPL + nREPL
clojure -M:format                   # cljfmt check
clojure -M:ffix                     # cljfmt fix
clojure -M:lint                     # clj-kondo (src + test)
clojure -M:format && clojure -M:lint && clojure -M:test   # pre-push trio

# ClojureScript test lane (Phase 0, research note 191) — runs the
# cross-platform (.cljc) substrate on Node so portability can't rot
# silently. Add new .cljc `-test` namespaces to kontor.node-runner.
clojure -M:cljs -m shadow.cljs.devtools.cli compile node-test \
        && node target/node-test.js
```

```clojure
;; In the REPL: open an in-memory accounting DB
(require '[kontor.core :as a])
(def conn (a/create-test-db))    ; ephemeral, schema loaded
(a/post-transaction! conn ...)
```

## File layout summary

```
LICENSE                     Apache 2.0
deps.edn                    minimal — only datahike
tests.edn                   kaocha config
CLAUDE.md                   this file
README.md                   short user-facing summary
doc/
  decisions.md              distilled ADRs (topic-grouped reading order)
  architecture.md           layer cake + module map + provider protocols
  programming.md            developer-facing transact-gate / status-machine / bitemporal model
  accounting-model.md       verbs → debits/credits bridge
  quickstart.md             5-minute REPL walkthrough
  showcases/                six end-to-end Clay notebooks
src/kontor/    kernel (clustered by domain)
  ; ─── Top-level: substrate + gate + write API ───
  schema.clj                schema EDN (kernel attrs across ~30 namespaces)
  core.clj                  create-test-db + install-schema! + provider registration
  money.clj                 Money + arithmetic (ADR-013) — substrate
  clock.cljc                the one place the kernel asks the time (ADR-171).
                            `*-tx-data` builders default timestamps from
                            `clock/now`, never a direct `(java.util.Date.)`,
                            so a builder stays a function of its inputs. Bind
                            `clock/*now*` to make the whole write path
                            reproducible. Read-side `as-of-*` defaults in
                            `kontor.reporting.*` are deliberately excluded.
  bitemporal.clj            :tx/valid-from + resolver + close-validity (ADR-048)
  entity.clj                :entity helpers + family walk (ADR-031)
  gate.clj                  transact-with-validation entry point + registry (T-2 note 160)
  validation.clj            datopia/invariant middleware composition (ADR-011)
  invariant.clj             vendored datopia/invariant primitive (T-3 note 160)
  book.clj                  kontor.book verb facade — receive/pay/sell/buy/… (ADR-095)
                            **Start here for any new business write.** Call
                            `entry!` with `{:debit-account :credit-account
                            :amount :commodity}` for a two-leg entry, or one of
                            the named verbs (`sell!`/`pay!`/…) that bake in the
                            journal type. `entry-tx-data` is the pure builder.
  posting.clj               build-transaction, sum-to-zero, multi-(entity, ledger, commodity)
  ; ─── Already-clustered ───
  document/invoice.clj      :invoice (sub-namespace post the namespace-collision rename)
  import_/beancount.clj     Beancount round-trip (ADR-009)
  ; ─── fx/ : FX conversion + rate providers ───
  fx/fx.clj                 Money-level convert / translate / to-functional-currency
  fx/fx_rate_provider.clj   FxRateProvider protocol + StaticTable / ECB / Chained (ADR-072)
  ; ─── tax/ : the whole tax stack ───
  tax/tax_rate_provider.clj     TaxRateProvider + TaxFacts + StaticTableProvider (ADR-071)
  tax/tax_posting_builder.clj   TaxPostingBuilder + StaticTablePostingBuilder (ADR-071)
  tax/tax_schedule.clj          schedule algebra — flat/bracket/capped/formula/elect/sum + base-transform + apply-adjustments (ADR-099, note 105)
  tax/period_tax_provider.clj   PeriodTaxProvider + TaxReturnFacts (ADR-099)
  tax/tax_return_posting_builder.clj  TaxReturnPostingBuilder — provision/payment (ADR-099)
  tax/statute.clj               statute-as-data evaluator — apply-provisions (ADR-101)
  tax/cgt.clj                   CGT composition helpers (ADR-103)
  tax/standalone_payroll_tax.clj  StandalonePayrollTaxProvider — generic levy on a marginalized wage sum (ADR-099)
  tax/corporate_income_tax.clj  CorporateIncomeTaxProvider — flat-rate CIT on marginalized book profit (ADR-099)
  tax/personal_income_tax.clj   PersonalIncomeTaxProvider — schedule(income−deductions)−credits+surtaxes (ADR-099)
  tax/vat_return.clj            periodic VAT/GST return — compute-vat-return + remittance (ADR-100)
  tax/sole_proprietor.clj       business-net + business-income-input — the sole-proprietor rung (ADR-100)
  ; ─── workflow/ : status machines + processes + side effects ───
  workflow/status_machine.clj   :status-transition + :status-history + record-status-change! (ADR-034)
  workflow/state_machine.clj    :transaction/state lifecycle (kernel-internal)
  workflow/side_effect.clj      :side-effect-intent dispatcher (ADR-041)
  workflow/side_effect/cross.clj  :cross-tx/step-id + CrossTxRouter + drain! (ADR-074)
  workflow/schedule.clj         :schedule recurring postings (ADR-032)
  workflow/process.clj          kontor.workflow.process / run-process orchestrator (ADR-067)
  workflow/event_bus.clj        in-process pub-sub on commit (ADR-092)
  ; ─── compliance/ : audit + sealing + retention + hold + DSAR + period locks
  ;     These co-locate because they're all "what the auditor + the regulator
  ;     + the data-subject see." Sealing freezes posted entries; retention
  ;     decides when to expire them; legal-hold blocks expiry under litigation;
  ;     DSAR walks the graph to satisfy data-subject access requests; period
  ;     locks gate fiscal-period writes; audit-doc records approvals + privilege.
  compliance/sealing.clj        :posted-at + middleware (ADR-007)
  compliance/audit_doc.clj      :audit-doc + :approval-policy (ADR-038)
  compliance/legal_hold.clj     :legal-hold + scope expansion (ADR-049)
  compliance/retention.clj      :retention-policy + sweeper (ADR-050)
  compliance/dsar.clj           :dsar-request + collect (ADR-052)
  compliance/period.clj         open/close periods + soft/hard lock (ADR-014)
  ; ─── reporting/ : read-side (balances, statements, aging, explain)
  ;     Dependency order: `balance` is the primitive (account → amount @ time);
  ;     `ledger` is the bitemporal posting list against an account; `trial`
  ;     aggregates balances per account; `financial_statements` aggregates
  ;     trials into BS/P&L; `report` is the declarative engine that everything
  ;     above composes through.
  reporting/balance.clj         account-balance (bitemporal-aware)
  reporting/ledger.clj          postings-against-account (bitemporal-aware)
  reporting/trial.clj           trial-balance
  reporting/closing.clj         year-end retained-earnings rollup
  reporting/aging.clj           AR aging
  reporting/report.clj          declarative report engine (+ :translate-to per ADR-072)
  reporting/financial_statements.clj  BS / P&L generators
  reporting/explain.clj         "explain this number" graph walks (ADR-091)
  ; ─── banking/ : reconciliation + payments + bank refs ───
  banking/reconciliation.clj    bank-line → transaction scaffolding
  banking/payment_application.clj  partial-payment primitive (ADR-043)
  banking/bank_csv.clj          generic CSV importer
  banking/bank_account.clj      :bank-account helpers (ADR-039)
  banking/payment_term.clj      :payment-term + due-date
  ; ─── provider/ : impl-of-protocol surfaces ───
  provider/einvoice_provider.clj   EInvoiceProvider + PureXmlProvider (ADR-017)
  provider/payroll_provider.clj    PayrollComputeProvider + PostingBuilder + EmitProvider (ADR-075)
  provider/consolidation.clj       translate + eliminate + consolidate! (ADR-073)
  provider/disposal_provider.clj   DisposalSource protocol (ADR-103)
  provider/valuation.clj           :valuation-book / :valuation-layer helpers (ADR-029)
  provider/costing_provider.clj    CostingProvider (ADR-029)
test/kontor/   tests, mirroring src/ (same cluster layout)
modules/       companion modules + l10n + bank importers (each a separate Maven artifact)
```

## Recent ADRs + research (since last refresh)

### Note 99 — event-driven accounting (ADR-095 .. ADR-098)

The McComb arc (research notes 80 / 88 / 97 / 98 / 99). A deliberately **deflated** scope: kontor does NOT build a stored-`:event` / θ-as-data framework — the `*-tx-data` builders already ARE θ in code, and sealing (ADR-007) neutralizes the re-derivability payoff (note 97's critical reading). What shipped instead:

- **ADR-095** — `kontor.book`: a verb facade — `entry-tx-data`/`entry!` + 8 verbs (`receive`/`pay`/`sell`/`buy`/`receive-payment`/`pay-bill`/`transfer`/`adjust`). Organizing sugar over ADR-068; no new schema.
- **ADR-096** — `kontor.report` as a family of marginalizations `σ_E`: `marginalize` (the quotient-epimorphism primitive), a generic `:dimension` engine, `:account-codes`/`:tax-tags` reframed as instances (behaviour-identical). Kernel, no schema.
- **ADR-097** — `:posting-dimension` + `:posting/dimensions`: classification axes beyond `:account` (cost-centre, project, segment). `marginalize` pivots over any axis. Kernel schema, additive.
- **ADR-098** — *withdrawn* (was `kontor-commitment`; companion dropped in W2 / note 174 — zero consumers outside its own tests).

### ADR-099 — `PeriodTaxProvider`: the period-tax substrate

Sibling of ADR-071's `TaxRateProvider` for *period/entity-incident* taxes (income / corporate / capital-gains / property / wealth / standalone employer-payroll). A tax is `(scope, base-selector, schedule) → liability → posting`; the period sibling fills the slots with `(entity×period, marginalize/σ_E, progressive brackets)`. Three schema-free kernel namespaces: `kontor.tax.tax-schedule` (the schedule algebra), `kontor.tax.period-tax-provider` (`PeriodTaxProvider` + `TaxReturnFacts` + a closed 8-value `period-tax-kinds` enum), `kontor.tax.tax-return-posting-builder` (provision/payment via the verb facade). Iteration 1 (substrate) shipped; per-jurisdiction build staged per research note 102 §10 — pilot = CA `t1`, then standalone payroll taxes, corporate income tax, personal income tax. Research note 102.

### ADR-100 — Phase 2: the sole-proprietor rung

Note 104 Phase 2. Two thin kernel namespaces: `kontor.tax.sole-proprietor` (`business-net` = σ_E income − expense; `business-income-input` folds the net onto the personal income-tax provider's `:inputs` — the CA t2125 pattern, generalised) + `kontor.tax.vat-return` (periodic VAT/GST reconciliation — `compute-vat-return` marginalizes already-posted output/input VAT by account code; `vat-return-tx-data` materialises the balanced remittance). Schema-free, additive. Research note 104.

### ADR-101 — Statute-as-data substrate: `:tax-concept` / `:provision` / `:regime` / `:parameter`

Four new kernel namespaces lift tax law itself into first-class queryable data: cross-jurisdiction concept catalogue (`:tax-concept`, ~5 attrs — composes with ADR-090 `:concept-iri`; closed-by-ADR with 14-concept starter set: `:participation-exemption`, `:rollover-relief`, `:loss-bucket`, `:lifetime-cap`, …), per-jurisdiction encoded statute rule (`:provision`, ~14 attrs — closed predicate vocab + `:provision/compute-fn` escape hatch; default+exception via ordered `:priority` + `:exception-of` ref, Catala-inspired), elective container (`:regime`, ~7 attrs — election rides ADR-034 status-machine; counterfactual via `:regime/extends`), date-keyed value history (`:parameter` + `:parameter-value` + `:parameter-bracket`, ~9 attrs — OpenFisca's parameter-tree pattern). Two small extensions: `:op :base-deduct` (note 105 vocab); `:provision/effective-from` distinct from `:tx/valid-from` (statutory vs book-entry date). Single evaluator (`kontor.tax.statute/apply-provisions`) folds provisions in priority order, raises `kontor.tax/ambiguous-provision` on same-priority conflicts. Phase 1 PIT providers stay record-shaped; new per-country (Phase 3 CIT/CGT) becomes `:provision` data. Research notes 107, 108-115, 116, 117 (Catala — Apache 2.0), 118 (OpenFisca — AGPL, concepts read by analogy), 119 (ADR draft).

### ADR-104 — DE CIT provider — KSt + Soli + GewSt as ADR-101 statute data

First end-to-end consumer of ADR-101. Three files in `modules/l10n-de/`: `cit-statute` (15 `:parameter`s + 7 `:provision`s — DE-KStG-§10 / §8b-Abs-5, DE-SolZG-§4, DE-GewStG-§8-Nr-1 (consolidated six-bucket per note 120 P0-1/2), DE-GewStG-§9-Nr-1 split into pre-2025 (old 1.2% × Einheitswert × 1.4) + from-2025 (actual Grundsteuer paid, JStG-2024 reform) — all with citations to gesetze-im-internet.de + the bgbl Fundstelle on parameter-values), `cit-provider` (the `PeriodTaxProvider` record + 4 compute-fns; ~100 lines of "thread the substrate" — sets `:component :kst` / `:gewst` in ctx per pass, calls `kontor.tax.statute/apply-provisions` for `:base-transform-add` / `:base-transform-deduct` / `:surtax`, assembles 2-component `TaxReturnFacts`), `cit-provider-test` (7 deftests / 31 assertions, including the BMF GmbH @ €150k / Hebesatz 380% → €43,687.50 exact match, a complex case with every adjustment lever firing → €83,402.15, and a bitemporal §9 swap test (as-of 2024-12-31 vs 2025-01-01 fires different provisions)). Hebesatz comes via `:tax-unit`. Validates substrate end-to-end on a real statute against authority-published figures. Research notes 108 (fit), 120 (baseline review that found the §8 + §9 P0s).

### ADR-101 Addendum 1 — `:op :schedule-override` + `compose-greater-of` + two-pass query

Three polish items landed after the BR / IN / CN cross-checks (notes 121-123) confirmed the substrate travels but flagged conventions worth completing: (1) `:op :schedule-override` joins the closed `:op` set — a provision can swap the schedule (CN regime-elective preferentials, FR PME 15%/25%, IN §115BAA flat 22%); `apply-provisions` return shape becomes `{:base-items :tax-items :schedule-overrides :provisions}`; (2) `kontor.tax.statute/compose-greater-of` documents the MAT/AMT pattern via code — two components with different bases, the greater liability prevails, both recorded in `:composed-of` + `:composition` for audit; (3) two-pass query pattern documented in `apply-provisions` docstring (qualification cliffs like CN SLPE / IN turnover-band / FR PME compute the base first, then re-query with the result injected into `:inputs`). Substrate validated DE / BR / IN / CN; zero new primitives. Tests: statute-test grew 33 → 38 / 78 → 97.

### ADR-105 — FR CIT (Impôt sur les Sociétés) as ADR-101 statute data

`modules/l10n-fr/cit-{statute,provider,test}` — 9 `:parameter`s + 4 `:provision`s. Exercises three substrate features the DE case didn't: `:op :schedule-override` (PME 15%/25% bracket vs flat 25% standard), `:tax-unit :pme?` company-eligibility gating, refundable `:credit` driving liability negative (CIR R&D credit, refundable for SMEs). Régime mère-fille (95% participation exemption) parallels DE §8b. Worked example: SAS @ €8M turnover / €4M profit / PME-eligible / CGE-applies → IS €995,750 + CS €7,680.75 = €1,003,430.75 to the cent (sourced from legifiscal.fr). 11 deftests / 52 assertions. One minor substrate stress: `:parameter-bracket` lacks uniqueness attr; FR ships an internal dedup. Research note 109.

### ADR-106 — JP CIT (5-component stack) as ADR-101 statute data

`modules/l10n-jp/cit-{statute,provider,test}` — 17 `:parameter`s + 7 `:provision`s. Most multi-component l10n provider yet — 3-component `TaxReturnFacts` (`:jp-nta` for national CIT + local CIT + 2026 defense surtax; `:jp-prefecture` for enterprise tax + special corporate enterprise tax; `:jp-municipality` for inhabitants' income-percentage + per-capita levy). Cross-component surtaxes wired by injecting the prior component's GROSS into the next pass's ctx (not `:running` — preserves statutory semantics: surtax references underlying tax, not running total). Per-capita 均等割 expressed as "surtax-on-zero" per note 110 §4 stress A (no `:fixed-amount` schedule kind needed in v1). Defense surtax bitemporally gated post-2026-04-01. Worked example: JETRO Tokyo SME @ ¥10M income → ¥2,625,912 (without per-capita) / ¥2,695,912 (with ¥70k per-capita) to the yen. 11 deftests / 50 assertions. Research note 110.

### ADR-107 — CA CIT (T2 federal + per-province) as ADR-101 statute data

`modules/l10n-ca/cit-{statute,provider,test}` — 18 `:parameter`s + 10 `:provision`s. Most multi-jurisdiction l10n provider yet — N-component `TaxReturnFacts` (1 federal + N provincial with non-zero allocation). CCPC-conditional Small Business Deduction cascade via `:op :schedule-override` + `:tax-unit :ccpc?` gating. Per-province SBD pool = federal $500k × Schedule-5 allocation share (provider-internal — note 111 §4 Finding 2 + note 123 (CN CCSV) cross-check both confirmed this pattern doesn't need substrate change). Refundable SR&ED for CCPCs (35% on first $3M) vs non-refundable for others (15%) — `:refundable?` driven by `:tax-unit :ccpc?`. v1 ships federal + ON + BC + AB; other provinces via consumer rate override. Worked example: CCPC ON+AB multi-province → CAD 89,230 (note 111 §2 reference). 10 deftests / 66 assertions. Research note 111.

### ADRs 108-112 — Gap #5 closure: AU / CN / MX / US CIT+PIT on ADR-101 statute data

Five jurisdictions migrated to the `:provision`/`:parameter` statute-as-data substrate, completing the 9-country statute-data cohort (DE/FR/JP/CA from earlier + AT/AU/CN/MX/US). Each ships `cit-statute` + `cit-provider` (and `pit-statute` + `pit-provider` where applicable) under `modules/l10n-<cc>/`. Each provider matches authority-published worked examples to the cent. Notes 185-188 = per-jurisdiction fit analyses; closure summary appended to `decisions-history.md`.

### ADR-064 Addendum 1 — kontor-lease ASC 842 operating + `:index-reset` fork

Silently-wrong dual-reporting bug fix. ASC 842-10-30-5 forbids remeasurement of an operating lease on an index-only payment reset — the variable expense is recognized when paid (not as a ROU/liability remeasurement) — but the kernel's `kontor.lease.modification/remeasure!` was applying the IFRS 16 remeasurement path unconditionally. New `asc842-operating-index-reset?` predicate + `apply-variable-expense-tx-data` helper + fork inside `remeasure!`; `pre-mod-snapshot` extended with `:framework`/`:classification`. The `:kontor.ledger/framework` enum (`:ifrs / :us-gaap / :de-hgb / :other`) already lived on `:ledger`. Two new tests in `modules/lease/test/.../modification_test.clj` (one happy-path, one missing-accounts guard). Found via Gap #8 architecture-validation review (note 189).

### ADR-113 — Fiscal-unit substrate (Gap #8 v1)

The substrate for tax-group consolidation. Three new attribute groups in `kontor.schema`: `:kontor.fiscal-unit/*` (11 attrs — code/name/parent-entity/regime/computation-style/elected-from/elected-until/minimum-term-ends/active/anchor-document/status), `:kontor.fiscal-unit-member/*` (6 attrs), `:kontor.transaction/elimination-*` (3 attrs). New `kontor.tax.fiscal-unit` namespace: 9-regime closed enum (`:de-organschaft / :fr-integration / :us-1502 / :jp-group-tsuusan / :uk-group-relief / :at-gruppenbesteuerung / :au-tcr / :cn-ccsv / :mx-rigs`), 3-style dispatch on `run-group-tax!` (`:single-base / :per-member-with-netting / :loss-surrender`), 6 `:status-transition` seeds (`:proposed → :elected → :active → :exiting → :exited` + `:active → :voided-retro`), `elect!` / `exit!` builders per ADR-068, `members` / `member-entities` / `fiscal-units-of` queries (bitemporally-correct, mid-year-exit-aware). `kontor.tax.statute/compose-aggregate-of` (sibling of `compose-greater-of`) records the economic delta vs separate filing. 12 deftests / 49 assertions. Research note 189 (gap-8-architecture-validation).

### ADR-113 Addendum 1 — DE Organschaft pilot

First end-to-end consumer of the fiscal-unit substrate. `modules/l10n-de/organschaft_provider.clj` — a `DEOrganschaftProvider` defrecord that wraps the DE CIT provider (ADR-104): sums per-member `:gewinn-aus-gewerbebetrieb` into a consolidated zvE, delegates to the underlying KSt+Soli+GewSt math (no new statute file), tags every component with `:regime :de-organschaft`. The canonical Müller-Gruppe worked example (note 164 §4.1.2): +€2M Holding / +€500k Industries / −€1M Logistik → €1.5M consolidated zvE → €237,375 KSt+Soli **to the cent**. Plus `elected-vs-separate` audit helper composing via `compose-aggregate-of` (matches by `:authority` since the DE CIT provider emits two same-`:kind` components for KSt+Soli vs GewSt): elected €237,375 vs separate €395,625 → €158,250 economic delta. 5 deftests / 20 assertions. Multi-municipal Zerlegung + §15-KStG/§8b cohabitation + retroactive-void deferred to ADR-113 v1.1.

### ADR-102 — `kontor-disposal` companion: ownership-change events

The substrate for capital-gains tax. `:disposal/*` schema (kind / subject / asset-class / acquired-on / disposed-on / proceeds / basis / depreciation-taken / ownership-fraction / elective-regime / exemption-claimed / rollover / loss-bucket / state) + ADR-034 status-machine facet (`:recorded → :recognized | :voided`). Companion at `modules/disposal/`; kernel untouched. Builders: `record-disposal!` / `recognize!` / `void!` per ADR-068. Queries: `disposals-of` / `disposals-in-period` (entity-scoped + void-aware). 18 tests / 44 assertions. Research notes 107 + 112-115.

### ADR-103 — `DisposalSource` protocol + per-jurisdiction CGT provider pattern

Three pieces: (1) kernel `kontor.disposal-source/DisposalSource` protocol (CGT providers depend on this, not the companion); (2) companion `kontor.disposal.source/DatahikeDisposalSource` canonical impl; (3) per-jurisdiction CGT providers per ADR-101 statute-as-data. Provider returns 0+ `:capital-gains-tax` components — some standalone with own schedule (DE §20 25%, US §1(h) 0/15/20), others fold via `:jurisdiction-specific-codes {:cit-base-additions [...] :pit-base-additions [...]}` consumed by CIT/PIT providers. **All 11 jurisdictions shipped** in this stage: research notes 112-115 (US/DE/UK/JP) + 127-134 (CA/FR/AU/BR/IN/MX/CN/AT).

### ADR-103 in practice — 11 CGT providers (~16k lines, 226 tests / 549 assertions)

| Country | File | Tests | Distinguishing feature |
|---|---|---|---|
| US | `l10n-us/cgt-{statute,provider}` | 12 / 31 | LT §1(h) 0/15/20 brackets × filing-status; §1250 25%; §1411 NIIT 3.8% surtax |
| DE | `l10n-de/cgt-{statute,provider}` (2 providers) | 21 / 57 | §8b 95/5; §17 60% Teileinkünfte + €9,060 Freibetrag taper; §20 Abgeltungsteuer + Soli; §23 10y/1y cutoffs + €1k Freigrenze HARD; 4 loss buckets |
| CA | `l10n-ca/cgt-{statute,provider}` | 18 / 61 | 50% inclusion; LCGE $1.275M (2026); ABIL via :pit-base-deductions; CCA recapture split |
| AU | `l10n-au/cgt-{statute,provider}` | 17 / 29 | Div 115 50% discount (sunset 2027-07-01); Subdiv 152 cascade w/ $500k retirement cap |
| UK | `l10n-uk/cgt-{statute,provider}` (new module) | 16 / 34 | AEA £3k; 18/24% std; BADR £1M cap; SSE corporate; post-Autumn-Budget-2024 rates |
| JP | `l10n-jp/cgt-{statute,provider}` | 22 / 57 | 5-component (listed/unlisted/RE-short/RE-long/§31-3); **Jan-1 measurement rule**; §35 ¥30M deduction; 復興 surtax |
| FR | `l10n-fr/cgt-{statute,provider}` (2 providers) | 27 / 58 | PFU 31.4% vs barème; mobilière abattements; immobilière dual IR/PS ladders; QPFC 12%; brevets IP-box 10% |
| BR | `l10n-br/cgt-{statute,provider}` | 22 / 56 | 4-bracket ladder 15→22.5%; B3 swing 15% / day 20%; R$35k+R$20k monthly aggregate exemptions |
| IN | `l10n-in/cgt-{statute,provider}` | 24 / 66 | Post-FA-2024 12.5% LTCG / 20% STCG; CII indexation election; §54 family + §54EC ₹50L cap; 4% cess |
| MX | `l10n-mx/cgt-{statute,provider}` | 19 / 45 | art. 120 averaging; 700k UDIS casa-habitación; art. 22 costo promedio w/ CUFIN/CUCA |
| CN | `l10n-cn/cgt-{statute,provider}` + `lat-provider` (3 providers) | 28 / 55 | IIT 20% cat 9 (listed exempt Caishui [1998] 61); 满五唯一 dual-prong; EIT 25% fold; Land Appreciation Tax 30/40/50/60% progressive |
| AT | `l10n-at/cgt-{statute,provider}` | 21 / 59 | KESt 27.5% (no carry, annual reset); ImmoESt 30% w/ 2 alternative residence tests; §10 KStG default-EXEMPT (inverted election direction) |

ZERO kernel changes were needed across all 11 — the disposal substrate (ADR-102) + DisposalSource protocol (ADR-103) generalised cleanly. Two new kontor.book code paths (CDA in CA, CFDI in BR/MX) are tracked for follow-up.

### Stage R substrate (ADR-067 .. ADR-087) — HR/payroll across 11 jurisdictions

- **ADR-067** — `kontor.process`: multi-step transactional processes as pure step-lists.
- **ADR-068** — every business write exposes a `*-tx-data` builder; the `!` wrapper routes through `transact-with-validation`.
- **ADR-069** / **ADR-070** — `kontor-lease`: mid-life portfolio import + disclosure-support deltas + discount-rate audit-doc.
- **ADR-071** — Tax abstraction: `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder` (supersedes ADR-005's single `TaxProvider`).
- **ADR-072** — `FxRateProvider` protocol + `:fx-rate/*` schema + `kontor.fx` Money-level translation.
- **ADR-073** — Consolidation primitive over `kontor.entity/family`.
- **ADR-074** — `kontor.workflow.side-effect.cross`: cross-DB saga primitive.
- **ADR-075** — Stage R substrate: `kontor-hr` companion (`:person` / `:employment` / `:compensation` + multi-cardinality `:compensation-component` / `:pay-period` / `:payroll-run`) + `kontor.provider.payroll-provider` protocol trio (`PayrollComputeProvider` + `PayrollPostingBuilder` + `PayrollEmitProvider`) + `kontor.hr.payroll/run-payroll!` orchestrator + two-axis `:audit-doc/category` (legal-doctrine × subject-matter) + `:retention-policy/category`.
- **ADR-076** — `kontor-payroll-de-datev`: DATEV LODAS (ISO-8859-1 4-section file) + EXTF Buchungsbeleg parser + HGB §249 simplified PTO accrual.
- **ADR-077** — `kontor-payroll-us-adp`: ADP GLI 10-column CSV + balancing-row trap + multi-state via `:analytic-account/state` (50 + DC + 5 territories) + ASC 710 PTO + 401(k) match accruals + W-2 reconciliation report.
- **ADR-078** — `kontor-payroll-ca`: Ceridian Dayforce + ADP Canada + Wagepoint skeleton + T4 builder (reuses shipped `xml/t4.clj` + `xml/t619.clj`) + PD7A period-due helper + ROE termination event + `:audit-doc/language` kernel attr.
- **ADR-079** — `kontor-payroll-fr`: Silae + Sage providers + DSN NEODES emit + PCG account routing.
- **ADR-080** — `kontor-payroll-au`: Xero + MYOB providers + STP Phase 2 emit + SuperStream contributions + 8-jurisdiction `:analytic-account/state` allocation.
- **ADR-081** — `kontor-payroll-br`: RH Sistemas + Senior HCM + Pluxee providers + eSocial S-1000..S-2399 event family + four-bucket statutory discipline + three CPC-33 accruals (férias + 13º + multa rescisória).
- **ADR-082** — `kontor-payroll-mx`: CONTPAQi + Aspel NOI providers + CFDI Nómina v1.2 emit + SAT Código Agrupador routing + aguinaldo / prima-vacacional accruals.
- **ADR-083** — `kontor-payroll-in`: Keka + GreytHR + ZenHR providers + Form 24Q quarterly TDS + EPFO monthly ECR + ESIC monthly + per-state PT routing + thin gratuity accrual (Ind AS 19 actuarial consumer-supplied).
- **ADR-084** — `kontor-payroll-jp`: freee + Money Forward + Yayoi + PCA-Kyuyo providers + 4-bucket statutory SI + 賞与 separate from 給料手当 + Gensen Choshu Hyo annual + My Number PII discipline (kontor never stores; attestation-only audit-doc).
- **ADR-085** — `kontor-payroll-cn`: Yonyou + Kingdee + Beisen providers + 应付职工薪酬 routing + 五险一金 (engine-authoritative IIT — no recomputation) + 34-province `:cn-province` analytic + 年终奖 special-method (single/combined per Cai Shui [2018] 164 → 2027).
- **ADR-086** — `kontor-payroll-at`: BMD + RZL providers + ELDA mBGM XML + L16 annual Lohnzettel + RLG-1 chart + Urlaubsrückstellung + Sonderzahlungsrückstellung accruals.
- **ADR-087** — `kontor-payroll-ca` C4.1: Quebec RL-1 + RL-1 Summary (`RLZ-1.S`) + TPZ-1015 monthly remittance + `QcPayrollEmitProvider`. Closes the C4 QC carve-out deferral; partner XSD NOT shipped (clean-room from public form documentation).

### McComb-aligned substrate seams (ADR-090 .. ADR-092)

- **ADR-090** — generalized `:concept-iri` seam across substrate entities (`:account`, `:partner`, `:commodity`, `:tax`, `:document-type` join the original `:account-tag/concept-iri` per ADR-019 / note 78). Stable IRIs into XBRL / FIBO / gist / internal taxonomies.
- **ADR-091** — `kontor.explain`: substrate "explain this number" graph walks (`explain-balance` / `explain-posting` / `entities-with-concept-iri`). Pure read-only datalog returning plain Clojure maps — McComb's "data outlives applications" framing.
- **ADR-092** — `kontor.workflow.event-bus`: in-process pub-sub on commit; `register-handler!` / `commit-and-emit` / `:transaction/committed` event kind. ADR-001 single-dep preserved; consumers wanting Kafka/NATS write an adapter.

### Research notes 79-89 — Stage R + McComb arc

- **79** Stage R plan; **81** HR gold-standards validation (Workday/SF/Oracle/Gusto/etc.) — drove §9.6 `:compensation`-as-separate-entity refactor + §9.7 `:person/kind` / `:work-time-fraction` / `:work-relationship-kind`.
- **82** DE-DATEV-LODAS research-before; **83** US-ADP-GLI; **84** CA-CRA-payroll.
- **85** C1 review-after (2 P0s closed in `08a2e63`); **86** Stage R final review-after (2 P0s closed in `2b51ae8` + 5 P1s + 2 P2s closed in `b9b7229`).
- **87** CN payroll research-before; **88** McComb substrate seams round 1 (the ADR-090/091/092 design rationale); **89** AT payroll research.
- **80** McComb "Future of Accounting" survey (drove the substrate-seam direction, not a kernel rewrite).
- **`:account-tag/concept-iri`** (commit `9a160aa`) — substrate seam for XBRL / filing taxonomies per research note 78.

### Stage R bottom line

11 active jurisdictions: DE (LODAS) / US (ADP) / CA + QC (CRA + RL-1) / FR (DSN) / AU (STP P2) / BR (eSocial) / MX (CFDI Nómina) / IN (TDS + PF + ESI + PT) / JP (Gensen) / CN (IIT + 五险一金) / AT (mBGM + L16). UK deferred per note 78 iXBRL gate.

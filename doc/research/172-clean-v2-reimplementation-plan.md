---
date: 2026-05-25
title: 172 — Clean v2 reimplementation plan (the distillation exercise, not a commitment)
audience: maintainer (port-vs-in-place-cleanup decision); the deliverable is the clarity the audit produces, not the port
status: structural audit — no code changed; reads `src/kontor/` (57 ns), `modules/*/src/` (46 modules), `src/kontor/schema.clj`, `doc/decisions.md` (107 ADRs), and notes 159-171
related:
  - notes 160 (API-consistency log — 19 items I-1..I-19, 4 fixed)
  - notes 161 (Phase-D two-DB scenario — the canonical personal-use walk)
  - notes 167 (fiscal-unit synthesis — Gap #8; designed, not coded)
  - notes 168 (tax-system coverage matrix + 11 seams S1-S11 + 4 dead-code D1-D4)
  - notes 169 (non-tax substrate coverage — 4 P0 / 5 P1 / 5 P2)
  - notes 170 (composition + tiered public-API surface map)
  - notes 171 (v0.1.0-alpha publishability — 3-5 days to ship)
  - ADR-001 (single-dep + EPL-1.0)
  - ADR-067 / ADR-068 (`kontor.process` + `*-tx-data` builder convention)
  - ADR-071 / ADR-072 / ADR-073 / ADR-074 / ADR-075 / ADR-099 / ADR-101 / ADR-103 (the eight protocol families)
  - ADR-095 / ADR-096 / ADR-097 / ADR-098 (the McComb verb-and-marginalize arc)
---

# 172 — Clean v2 reimplementation plan

## TL;DR

If we had to port kontor into a fresh repo, the substrate **semantics
would stay 1:1** (bitemporal-on-every-read, sealing-as-purge-is-a-commit,
status-machine-as-audit-trail, ADR-068 builders, statute-as-data,
marginalize-as-report-engine) and the **107 ADRs would distil to
~30** load-bearing decisions; the other 77 are stepping stones we
walked over and need not re-walk. Of the structural debt notes
159-171 have catalogued — 19 inconsistencies in note 160, 11 seams
in note 168 (2 P0s already closed), 4 P0 / 5 P1 / 5 P2 in note 169,
~8 surface gaps in note 170, 3 P0 / 6 P1 / 7 P2 in note 171 — about
**half is free in a port** (uniform discipline applied at green-field
write-time) and **half costs the same** whether done in-place or in v2
(the per-jurisdiction Gap #5 CIT migration, the property/wealth-tax
research call, the fiscal-unit substrate implementation).

The honest punchline is that **v2 would distil the *organization* but
would not improve the *substrate quality*** in any way the existing
codebase couldn't reach with a 6-8 week disciplined cleanup pass.
The substrate is already correct; the noise is in the *file layout +
naming + per-module presets + ADR pile + doc drift*, all of which an
in-place sweep addresses. The single argument for porting that has
merit is **shedding the 107-ADR cognitive surface**: a contributor
who lands on `decisions.md` today reads 10,123 lines; v2 would let
them read ~3,000.

§13's recommendation is **(c) hybrid** — keep the repo, do the
in-place sweep, but during the sweep draft each consolidated
namespace as if writing fresh (i.e. **distil first, then move**).
This achieves the v2 clarity without paying the test-suite + ADR-
provenance + integration-history cost of a fresh repo. The maintainer
**does not commit** to executing this; the value is the clarity the
audit below produces about *what's worth keeping*.

---

## §1. The framing — what v2 would (and wouldn't) be

### §1.1 What changes IF we did the port

- **File layout.** Kernel collapses the audit/compliance namespaces
  into one subtree (`kontor.compliance/`), the tax namespaces into
  one subtree (`kontor.tax/`), the report+balance+trial+closing
  namespaces into one subtree (`kontor.report/`). The single-file
  flat `src/kontor/*.clj` layout the kernel uses today (55 top-level
  files + 2 sub-namespaces — note 169 §1) becomes ~25 namespaces in
  a 5-subtree organization. Mechanical refactor, not a redesign.
- **Namespace organization.** Three orphaned-by-rename namespaces
  vanish from docs (`kontor.audit`, `kontor.query`, `kontor.tax` —
  named in `doc/architecture.md` + `CLAUDE.md` but absent on disk
  per note 169 §6.3.5). Provider records get one naming convention
  (note 168 S5 catalogued three: `DECITProvider` / `DECapitalGainsTaxProvider`
  / `CnEitCgtProvider` — three patterns in one substrate).
- **Schema-namespace discipline.** Every `:foo/*` namespace has ONE
  owner documented in a per-namespace ADR addendum (today the
  ownership is implicit; note 166 surfaced `:tax-group/*` collision
  between VAT and fiscal-unit; the partner companion still has
  `:person/*` overlap with the kernel per note 169 §6.4 P0).
- **Provider-protocol normalization.** 8 protocols collapse to a
  uniform shape — `defprotocol X[Provider|Source]` with
  `provider-id` + work-fn(this, ctx) returning a `Facts` record,
  default impl `Static<Xxx>` in the same file. Today: 4 different
  arities (note 168 S6 — `:as-of-valid` threading inconsistency).
- **Deprecated patterns dropped.** ADR-005 (legacy `TaxProvider`)
  marked superseded but still loaded by docs (note 168 D1).
  Record-shape CIT providers (US/AT/AU/CN/MX, Gap #5) ported
  directly as ADR-101 statute-data, skipping the record-shape
  intermediate state.
- **ADR pile distilled.** 107 ADRs → ~30. The ones dropped were
  stepping stones (e.g. ADR-005 superseded by ADR-071; ADR-053-056
  collapse into one `kontor-asset` ADR; ADR-076-087 collapse from
  11 payroll-adapter ADRs into one "PayrollProvider trio + 11
  adapters" ADR with a per-adapter appendix; ADR-101 + Addendum 1
  + Addendum 2 collapse into one ADR-101). The load-bearing
  decisions stay; the per-stage record-keeping moves to a
  `doc/history.md` summarising what each stage learned.

### §1.2 What stays the same

The **substrate semantics** are not up for debate; the breadth-first
exploration that produced 107 ADRs DID land on a correct substrate.
What ports 1:1:

- **Bitemporal-on-every-read** (ADR-008, ADR-048; note 169 §6.1.1):
  every `balance` / `trial` / `ledger` / `report` query takes
  `:as-of-tx` + `:as-of-valid`. The I-17 fix (note 161, default
  changed to `nil` = all valid time) is the API; v2 ships with that
  default from day one.
- **Sealing + purge-is-a-recorded-commit** (ADR-007). The middleware
  in `sealing.clj` refuses silent retract; explicit `:db/purge` is
  allowed and itself a recorded commit.
- **Status-machine-as-audit-trail** (ADR-034 + ADR-038):
  `:status-transition` + `:status-history` + `:approval-policy`.
  No separate audit log; every state change is a queryable row.
- **`*-tx-data` builder convention** (ADR-068): every business
  write is a pure `foo-tx-data` builder + `!` wrapper that routes
  through `transact-with-validation`. Already consistent across
  kernel + all 17 companions (note 169 §6.1.4).
- **Statute-as-data** (ADR-101): `:tax-concept` / `:provision` /
  `:regime` / `:parameter` with `kontor.statute/apply-provisions`
  priority-folding + closed `:op` vocab. 41/109 statute tests on
  this surface; 6/11 jurisdictions ported.
- **Marginalize as the report engine** (ADR-096): σ_E partition-
  and-sum is the kernel of every report; pure data in, pure data
  out.

### §1.3 What's preserved 1:1

- **All 3050 tests / 11,683 assertions.** The test suite IS the
  spec for the substrate; v2 would not rewrite tests, only move
  them under the new namespace layout. Per-jurisdiction provision
  data (each `:provision` is a fact about a tax statute — that
  doesn't change with a port) ports verbatim.
- **The Christian scenario** (note 161, promoted to
  `test/kontor/integration/christian_scenario_test.clj`). It IS
  the existence proof of the cross-border two-DB topology
  working; v2 ports it 1:1.
- **All ADR-104..107 CIT providers** (DE / FR / JP / CA) + the
  11 CGT providers + the 11 investment-income providers. The
  statute-data ports verbatim; the provider record shape changes
  cosmetically per the new naming convention.
- **All 11 payroll adapters** (DE-DATEV-LODAS, US-ADP-GLI,
  CA + QC RL-1, FR-DSN, AU-STP-P2, BR-eSocial, MX-CFDI-Nómina,
  IN-TDS-PF-ESI-PT, JP-Gensen, CN-IIT-五险一金, AT-mBGM-L16) +
  the 5 bank-statement importers (bank-{at,ca,de,fr,us}). These
  are the most expensive surface to redo and the substrate didn't
  shift under them.

---

## §2. Proposed v2 namespace organization (the kernel)

### §2.1 The side-by-side

| Current | Proposed v2 | Rationale | Kind |
|---|---|---|---|
| `kontor.core` + `kontor.schema` | unchanged | bootstrap surface; already minimal | — |
| `kontor.posting` + `kontor.book` + `kontor.process` | unchanged | the three substrate primitives; clean already (notes 169 §1.3, 170 §3) | — |
| `kontor.balance` + `kontor.ledger` + `kontor.trial` + `kontor.closing` | `kontor.report/{balance,ledger,trial,closing,marginalize,financial-statements,report}` subtree | currently 4 small files for "balance arithmetic" + `kontor.report` + `kontor.financial-statements` live separately; one `report/` subtree mirrors how they actually compose (every report is a marginalize over the balance arithmetic) | cosmetic |
| `kontor.audit-doc` + `kontor.legal-hold` + `kontor.retention` + `kontor.dsar` + `kontor.sealing` + `kontor.status-machine` + `kontor.state-machine` + `kontor.validation` | `kontor.compliance/{audit-doc,legal-hold,retention,dsar,sealing,status-machine,state-machine,validation}` subtree | currently 8 top-level files for related concerns (every one of these IS "the compliance surface"); the subtree makes the surface discoverable | cosmetic |
| `kontor.fx` + `kontor.fx-rate-provider` | `kontor.fx/{fx,rate-provider}` subtree | minor; consolidates the protocol with the Money-level helper | cosmetic |
| `kontor.bitemporal` | unchanged | the bitemporal substrate shim; ports 1:1 | — |
| `kontor.entity` | unchanged | entity-family walk; ports 1:1 | — |
| `kontor.consolidation` | unchanged | translate + eliminate + consolidate! | — |
| `kontor.money` | unchanged | Money + BigDecimal arithmetic; the canonical type | — |
| `kontor.period` | unchanged | open/close/seal; ports 1:1 | — |
| `kontor.schedule` | unchanged | recurring-postings; ports 1:1 | — |
| `kontor.side-effect` + `kontor.side-effect.cross` | `kontor.saga/{in-process,cross-db}` subtree | renames clarify that these ARE the saga primitives; today the naming says "intent dispatcher" + "cross-db" which is implementation, not concept | **semantic** (rename) |
| `kontor.event-bus` | unchanged | in-process pub-sub | — |
| `kontor.explain` | unchanged | graph-walk helpers | — |
| `kontor.agent-tools` | unchanged | tool catalog | — |
| `kontor.payment-application` + `kontor.aging` + `kontor.payment-term` + `kontor.reconciliation` + `kontor.bank-account` + `kontor.bank-csv` | `kontor.money-flow/{payment-application,aging,payment-term,reconciliation,bank-account,bank-csv}` subtree | currently 6 top-level files for "AR/AP money-flow primitives"; one subtree mirrors how they compose in the canonical AR/AP workflow | cosmetic |
| `kontor.valuation` + `kontor.costing-provider` | `kontor.costing/{valuation,provider}` subtree | currently 2 top-level files for "cost-flow" | cosmetic |
| `kontor.tax-rate-provider` + `kontor.tax-posting-builder` + `kontor.period-tax-provider` + `kontor.tax-return-posting-builder` + `kontor.tax-schedule` + `kontor.statute` + `kontor.standalone-payroll-tax` + `kontor.corporate-income-tax` + `kontor.personal-income-tax` + `kontor.cgt` + `kontor.sole-proprietor` + `kontor.vat-return` | `kontor.tax/{rate-provider,posting-builder,period-provider,return-builder,schedule,statute,standalone-payroll,corporate-income,personal-income,cgt,sole-proprietor,vat-return}` subtree | **biggest grouping opportunity** — currently 12 top-level files for the tax substrate; one subtree mirrors how they actually compose. Closes note 168 S5 (record-name drift) by giving the subtree a single home + naming convention | **semantic** (one subtree implies one orchestration model) |
| `kontor.disposal-source` | `kontor.cgt/disposal-source` (collapsed into the cgt subtree) | the protocol exists to let CGT providers read disposal events; lives next to its consumer | cosmetic |
| `kontor.einvoice-provider` | `kontor.einvoice` (collapsed) | the protocol + the default `PureXmlProvider` in one ns; ADR-017 supersedes the split | cosmetic |
| `kontor.payroll-provider` | `kontor.payroll` (collapsed; trio still defrecord-able) | one ns for the trio + the StandardPostingBuilder default | cosmetic |
| `kontor.standalone-payroll-tax` | `kontor.tax/standalone-payroll` (moved into the tax subtree) | semantic alignment | cosmetic |
| `kontor.import_/beancount` | `kontor.io/beancount` | renames the placeholder `import_` (kebab-cased) | cosmetic |
| `kontor.document/invoice` | `kontor.document/invoice` | unchanged | — |
| `kontor.incorporation` | unchanged | the keystone single-fn; lives at the kernel root | — |
| `kontor.audit` (ghost in docs only — ADR-003 says hashing lives upstream in datahike) | DELETE from `architecture.md` + `CLAUDE.md` | doc drift (note 169 §6.3.5) | cosmetic |
| `kontor.query` (ghost in docs only — folded into `kontor.bitemporal`) | DELETE from `architecture.md` + `CLAUDE.md` | doc drift | cosmetic |
| `kontor.tax` (ghost in docs only — superseded by ADR-071 trio; the subtree becomes the real `kontor.tax/`) | DELETE the ghost reference; the new subtree is the canonical home | doc drift | cosmetic |

### §2.2 Cosmetic vs semantic

Per the side-by-side: 4 **semantic** merges (`kontor.saga/*`,
`kontor.tax/*`, the `*-provider` collapses, the report subtree
consolidating the marginalize-API surface) and ~12 **cosmetic**
moves. The semantic merges DO change consumer namespace requires
(`(require '[kontor.tax.period-provider :as ptp])` instead of
`(require '[kontor.period-tax-provider :as ptp])`); the cosmetic
moves are mechanical refactors.

### §2.3 The reading order this implies

A v2 reader lands on:

```
src/kontor/
├── core.clj                     entry point
├── schema.clj                   the kernel schema EDN
├── money.clj                    Money + arithmetic
├── posting.clj                  build-transaction + sum-to-zero
├── entity.clj                   per-(entity, ledger, commodity)
├── period.clj                   open/close/seal
├── bitemporal.clj               the write-side shim
├── process.clj                  multi-step orchestrator
├── book.clj                     verb facade (8 verbs + dividend)
├── consolidation.clj            translate + eliminate + consolidate!
├── incorporation.clj            the keystone single-fn
├── schedule.clj                 recurring postings
├── event_bus.clj                in-process pub-sub
├── explain.clj                  graph walks
├── agent_tools.clj              tool catalog
├── compliance/                  ─┐ 8 ns: audit-doc legal-hold retention dsar sealing status-machine state-machine validation
├── report/                       ─┐ 7 ns: balance ledger trial closing marginalize report financial-statements
├── tax/                          ─┐ 12 ns: rate-provider posting-builder period-provider return-builder schedule statute standalone-payroll corporate-income personal-income cgt sole-proprietor vat-return
├── fx/                           ─┐ 2 ns: fx rate-provider
├── money_flow/                   ─┐ 6 ns: payment-application aging payment-term reconciliation bank-account bank-csv
├── costing/                      ─┐ 2 ns: valuation provider
├── saga/                         ─┐ 2 ns: in-process cross-db
├── payroll/                      ─┐ 1 ns (the trio + default builder)
├── einvoice/                     ─┐ 1 ns (protocol + PureXmlProvider)
├── document/                     ─┐ 1 ns (invoice)
├── io/                           ─┐ 1 ns (beancount)
└── disposal_source.clj          ⟶ folded into tax/cgt
```

15 top-level files + 8 subtrees ≈ 50 namespaces total (same count
as today; the *organization* changes, not the file count). A
contributor browsing the layout sees what the substrate IS in 30
seconds, vs the current 57-file flat list that requires reading
`architecture.md` to navigate.

---

## §3. Companion module standardization

### §3.1 The 17 (+ 5 bank + 1 einvoice + 11 payroll + 12 l10n) companion landscape

Total: 46 companions per note 171 §5 ("32 of 46 modules lack a
README"). The non-l10n / non-payroll companions are the 17 from
note 169 §2.1 + the 5 bank importers + einvoice-de.

### §3.2 Cross-cutting conventions audit

#### Schema-attr-namespace discipline

| Companion | Owns | Audit |
|---|---|---|
| `asset` | `:asset` `:asset-class` `:asset-depreciation` `:asset-event` `:asset-method-params` | clean — 5 prefixes scoped to this companion |
| `authz` | (no schema.clj — companion provides protocols not entities) | clean |
| `collections` | `:collection-case` `:credit-hold` `:dispute` `:dunning-event` `:dunning-pause` `:dunning-policy` `:payment-promise` | clean — 7 prefixes |
| `commitment` | `:commitment` `:commitment-fulfillment` | clean |
| `disposal` | `:disposal` | clean (single prefix) |
| `expense` | `:expense-line` `:expense-report` | clean |
| `hr` | `:compensation` `:compensation-component` `:consent` `:department` `:employment` `:hr-person` `:pay-period` `:payroll-run` `:person` | **OVERLAP — `:person/*`**; the kernel also owns `:person/*` (`:person/birth-date`, `:person/national-id`); audit found this in notes 169 §6.4 P0 and 268 ("Resolve hr vs partner :person/* attr collision"). The compromise was `:hr-person/*` for the HR-richer storage; the qualified prefix was deferred. v2 fix: `:hr/*` for the companion or fold the kernel `:person/*` into the partner companion. |
| `inventory` | `:facility` `:facility-location` `:facility-product` `:inventory-detail` `:inventory-item` `:inventory-transfer` `:inventory-variance` `:negative-fill` `:physical-inventory` | clean — 9 prefixes |
| `invoice` | `:gl-account-default` `:invoice` `:invoice-line` `:order-item-billing` | **DOUBLE-OWNERSHIP**: kernel `:invoice/*` (in `src/kontor/schema.clj` lines 1067-ish for `:invoice-line/*` + invoice attrs) **and** companion `:invoice/*`. The kernel-side has the document-lifecycle attrs; the companion has the GL-posting attrs. v2 fix: namespace by ownership (`:document/invoice-*` for the kernel-side, `:invoice/*` for the GL-side) OR collapse into one. Note 169's "doc-vs-code drift" surfacing. |
| `lease` | `:lease` `:lease-liability` `:lease-modification` | clean |
| `partner` | `:contact-mech` `:email-address` `:org` `:partner` `:partner-contact-mech` `:partner-contact-mech-purpose` `:partner-relationship` `:partner-role` `:person` `:postal-address` `:telecom-number` | **OVERLAP — `:person/*`** (same issue as hr above). v2 fix: kernel owns `:person/*` OR partner companion owns it; only one. |
| `sales` | `:inv-reservation` `:order` `:order-adjustment` `:order-item` `:order-role` `:ship-group` `:ship-group-assoc` | clean |
| `people-record` | `:performance-review` `:position-held` `:promotion` | clean |
| `treaty-de-ca` | (no schema.clj — pure functions over kernel + l10n primitives) | clean |
| `einvoice-de` | (no schema.clj — wraps the upstream Mustang library) | clean |

**Finding**: 2 companions share `:person/*` with the kernel (hr,
partner). Every other companion holds a unique top-level namespace.
v2 fix is a single rename; the substrate-correct ownership is for
**partner** to own `:person/*` (party-as-root per ADR-033), with
kernel adding a slimmer `:person/*` IF needed for cross-companion
references (e.g. employment relationships referencing a partner-side
person). Today's `:hr-person/*` rename is the pragmatic compromise;
v2 makes it canonical.

#### `install!` shape

Per note 168 §S10 + commit `c1143fb`: all 12 l10n modules now have
`preset.clj` with `install-all!` + `create-XX-db`. The kernel-
adjacent companions are uneven:

- `kontor-asset` → no `install-all!`; consumer calls
  `kontor.asset.schema/install!` then wires `DepreciationProvider`.
- `kontor-lease` → no `install-all!`.
- `kontor-inventory` → no `install-all!`.
- `kontor-partner` → no `install-all!`.
- `kontor-disposal` → no `install-all!`.
- `kontor-collections` → no `install-all!`.

**Finding** (note 169 §6.4 P0): no kernel-side
`install-all-companions!` exists. Every consumer wires
`kontor.core/install-schema!` + each `kontor.<companion>.schema/install!`
by hand. v2 fix: ship `kontor.preset/install-all!` taking a `#{:kernel
:partner :sales :invoice :procurement :collections :asset :inventory
:lease :hr :disposal :commitment :expense :authz}` set; ship a per-
companion `install-all!` (idempotent, includes status-transition
seeds + sample data if relevant) for every companion.

#### `*-tx-data` builder convention (ADR-068)

Audited at note 169 §6.1.4: "consistent across kernel + all 17
companions". No findings; the convention is real. v2 ports 1:1.

#### Test conventions

All companions test under `modules/<companion>/test/kontor/<companion>/`.
Uniform. Per note 169 §2.4 the test density varies but the layout
doesn't.

#### Provider records

Per note 168 §S5 ("provider record name drift") — **three patterns
in one substrate**:

- `DECITProvider` / `FRCITProvider` / `INCITProvider` /
  `JPCITProvider` / `BRCITProvider` / `CACITProvider` —
  uppercase-country + UPPERCASE-tax-abbrev (CIT providers)
- `DECapitalGainsTaxProvider` / `MXCapitalGainsTaxProvider` /
  `USCapitalGainsTaxProvider` / `AuCapitalGainsTaxProvider` /
  `JpCapitalGainsTaxProvider` (CGT providers; AU + JP are `Au`/`Jp`,
  others all-upper). Inconsistent within the same protocol family.
- `ATKestCgtProvider` / `ATImmoEstProvider` / `CnIitCgtProvider` /
  `CnEitCgtProvider` / `CnLatProvider` — country + regime-tag +
  `Provider`
- `DeTaxRateProvider` / `AtTaxRateProvider` / `AuTaxRateProvider`
  (Pascal-Case country prefix — different convention again)

v2 fix: pick one — recommend `<CountryPascal><Regime?><TaxAbbrev>Provider`
— e.g. `DeCitProvider`, `JpCgtProvider`, `AtKestCgtProvider`. Mass-
rename in v2 (free); in-place is a single grep + `sed` sweep (~30
minutes) but bumps an external consumer's symbol-references.

### §3.3 Triage table

| Finding | Trivial-in-place? | Non-trivial in-place? | Free in v2? |
|---|---|---|---|
| `:person/*` overlap (hr ↔ partner ↔ kernel) | yes (single-tx rename + migration helper) | — | yes (clean start) |
| `:invoice/*` double-ownership (kernel ↔ invoice companion) | yes (namespace by ownership) | — | yes |
| No kernel-side `install-all-companions!` | yes (~150 LOC) | — | yes |
| Provider-record naming drift | yes (~30 min `sed`) | — | yes (clean start) |
| Per-companion `install-all!` shape uniformity | yes (~10 × 15 LOC) | — | yes |
| Three orphaned-by-rename ghost namespaces | yes (~5 min doc sweep) | — | yes |
| ADR-068 builder uniformity | already clean | — | already clean |

**Conclusion**: every cross-cutting companion consistency issue
**is fixable in-place** in ~1 day total. v2's win here is **not
having to write the migration helpers** (e.g. for the `:person/*`
rename); v2 starts with the right shape.

---

## §4. Provider-protocol consistency

### §4.1 The 8 (+ 5) protocol surface

Per note 169 §3 + §5.3:

| Protocol | Module | ADR | Key fns | Default impl | Adopters |
|---|---|---|---|---|---|
| `TaxRateProvider` | kernel `kontor.tax-rate-provider` | ADR-071 | `provider-id` + `rate-facts` | `StaticTableProvider` + 4 scaffolds | 11 l10n |
| `TaxPostingBuilder` | kernel `kontor.tax-posting-builder` | ADR-071 | `builder-id` + `tax-postings` | `StaticTablePostingBuilder` | shared |
| `PeriodTaxProvider` | kernel `kontor.period-tax-provider` | ADR-099 | `provider-id` + `period-tax-facts` + `period-tax-kind` | none | 6 statute-data + 5 record-shape |
| `TaxReturnPostingBuilder` | kernel `kontor.tax-return-posting-builder` | ADR-099 | `return-postings` + `payment-postings` | `BookEntryTaxReturnPostingBuilder` | shared |
| `FxRateProvider` | kernel `kontor.fx-rate-provider` | ADR-072 | `resolve-rate` + `resolve-period-rates` + `provider-id` | StaticTable + ECB + Chained | consumer choice |
| `CostingProvider` | kernel `kontor.costing-provider` | ADR-029 | `plan-receipt` + `plan-consumption` | FIFO / LIFO / WAvg / Std | FefoCostingProvider in inventory |
| `EInvoiceProvider` | kernel `kontor.einvoice-provider` | ADR-017 | `emit-artifact` + `transmit!` + `provider-id` | `PureXmlProvider` shape | einvoice-de + BR/CN/IN inside l10n |
| `DisposalSource` | kernel `kontor.disposal-source` | ADR-103 | `disposals-in-period` + `disposals-of` | `DatahikeDisposalSource` (in companion) | 11 CGT providers |
| `PayrollComputeProvider` | kernel `kontor.payroll-provider` | ADR-075 | `compute-pay-period` | none | 11 payroll adapters |
| `PayrollPostingBuilder` | kernel `kontor.payroll-provider` | ADR-075 | `build-payroll-postings` | `StandardPayrollPostingBuilder` | shared |
| `PayrollEmitProvider` | kernel `kontor.payroll-provider` | ADR-075 | `emit-filing-artifact` + `transmit!` | none | per-country emit |
| `DepreciationProvider` | `kontor-asset/depreciation_provider` | ADR-055 | `plan-depreciation` + `period-end-rollforward` | SL / DB / UoP | none required |
| `LeaseProvider` + `RouProvider` | `kontor-lease/lease_provider` + `rou_provider` | ADR-063 | `classify` + `plan-period-postings` | operating + finance defaults | none required |
| `IAuthorization` | `kontor-authz/core` | ADR-065 | `authorized?` + `relationships-of` + `principals-of` | EACL-derived ReBAC | none required |

Plus 5 in-companion-only protocols: `MvaProvider` (BR),
`NoaParser` (CA), `DunningTemplateProvider` (collections),
`MxEngineProvider`+`MxCfdiEmitter` (MX payroll), `AtEngineProvider`+
`AtFilingEmitProvider` (AT payroll).

### §4.2 Audit findings

#### Naming
- **8 of 13** are `XxxProvider`. **1 outlier**: `DisposalSource`
  (ADR-103). Note 168 §S5 raised this — "why does ADR-103 ship
  `DisposalSource` not `DisposalProvider`?" The answer (from
  reading the ADR-103 prose): the protocol is consumed *backwards*
  from typical providers — the CGT provider asks the source for
  disposal events; the source is the data side, not a computation
  pluggable. Reasonable but inconsistent. v2 fix: keep
  `DisposalSource` (the semantic is right) but document the
  `*Source` vs `*Provider` distinction in a v2 ADR.
- **2 implementation outliers**: `IAuthorization` (the Java-flavored
  `I` prefix); `MvaProvider` (no country prefix because it lives
  inside l10n-br).

#### Surface (arity)
- Most are 2-arity `(this ctx)`. Some take more (the
  `TaxRateProvider/rate-facts` is `(this ctx)` returning facts;
  the `CostingProvider/plan-consumption` is `(this conn ctx args)`).
  v2 normalization: every protocol fn is `(this ctx)` where `ctx`
  is a map containing `db` + any other inputs. Consumers compose
  with a `*PostingBuilder` if they emit GL.

#### Return shape
- Tax providers return Facts records (`TaxFacts`,
  `TaxReturnFacts`). Cost providers return tx-data directly.
  Payroll providers return Facts (`PayPeriodFacts`). FX returns
  raw Money. Mixed.
- v2 normalization: every protocol fn returns a typed Facts record;
  the `*PostingBuilder` sibling lifts Facts to tx-data.

#### `provider-id`
- 8 of the 11 kernel protocols expose `provider-id`. 3 don't
  (`CostingProvider`, `EInvoiceProvider`, `LeaseProvider`). The
  rationale (per note 169 §5.3): the 3 without are *behavioural*
  (the consumer doesn't need to identify which impl ran), the 8
  with are *attestation-bearing* (the audit-doc records "computed
  by provider X"). v2: document the rule; add `provider-id` to
  all where attestation matters, leave it off the 3 where it
  doesn't.

#### Default impl
- Every kernel protocol has at least one default in the same file.
  `PeriodTaxProvider` is the exception — kernel thin, all impls
  in l10n. v2: same (no kernel default makes sense for jurisdiction-
  specific tax math; ship a kernel `StaticPeriodTaxProvider` as a
  test fixture but document it's NOT meant for real consumers).

### §4.3 Proposed uniform protocol shape for v2

```clojure
(defprotocol Xxx[Provider|Source]
  (provider-id [this])                  ; when attestation matters
  (xxx-work-fn  [this ctx])             ; pure; returns a typed Facts record
  )

;; Default impl in the same file
(defrecord StaticXxxProvider [opts] Xxx[Provider|Source]
  (provider-id [_] :static)
  (xxx-work-fn [_ ctx] ...))

;; Consumers compose via a sibling builder
(defprotocol XxxPostingBuilder
  (builder-id [this])
  (xxx-postings [this facts ctx]))      ; Facts → tx-data
```

Today: ~9 of the 13 protocols already follow this shape; 4
deviate. v2 normalization is mechanical.

---

## §5. Schema-namespace discipline audit

### §5.1 Kernel namespace prefixes catalogued

Per `grep` of `src/kontor/schema.clj` (4418 lines): **68 unique
`:foo/*` namespace prefixes** are owned by the kernel schema.
Grouped by concern:

| Concern | Prefixes |
|---|---|
| **Core posting** | `:account` `:account-code` `:account-tag` `:account-type-direction` `:commodity` `:journal` `:lot` `:posting` `:posting-dimension` `:transaction` `:write` `:create` (12) |
| **Tax substrate** | `:tax` `:tax-application` `:tax-concept` `:tax-group` `:tax-rep` `:fiscal-position` `:parameter` `:parameter-value` `:parameter-bracket` `:provision` `:regime` (11) |
| **Compliance** | `:audit-doc` `:status-history` `:status-transition` `:approval-policy` `:attestation` `:dsar-request` `:legal-hold` `:retention-policy` `:person` (9) |
| **Period / bitemporal** | `:period` `:state` `:balance-assertion` (3) |
| **Analytic** | `:analytic-account` `:analytic-distribution` `:analytic-plan` (3) |
| **Money flow** | `:bank-account` `:bank-line` `:partner-bank-account` `:payment-application` `:payment-term` `:schedule` `:schedule-occurrence` (7) |
| **Master data** | `:document-type` `:entity` `:partner` `:partner-merge` `:partner-tag` `:partner-tax-id` (6) |
| **Geography** | `:country` `:country-code` `:country-group` `:state-code` (4) |
| **FX** | `:fx-rate` (1) |
| **Inventory cost** | `:valuation-book` `:valuation-layer` `:layer-adjustment` `:layer-consumption` (4) |
| **Invoice (kernel-side)** | `:invoice` `:invoice-line` `:complemento` (3) |
| **Ledger** | `:ledger` (1) |
| **Side-effect** | `:side-effect-intent` (1) |
| **Cross-tx saga** | (`:cross-tx/*` — surveyed separately in `cross.clj`) — note 168 D4 reports 1 real consumer |

(Total counted via `grep -E '^\s*\{:db/ident\s+:[a-z]' | sort -u`
returns 68 unique prefixes from the kernel.)

### §5.2 Companion-owned prefixes

Per §3.2 audit (16 companions surveyed):

| Companion | Schema prefixes |
|---|---|
| asset | `:asset` `:asset-class` `:asset-depreciation` `:asset-event` `:asset-method-params` |
| collections | `:collection-case` `:credit-hold` `:dispute` `:dunning-event` `:dunning-pause` `:dunning-policy` `:payment-promise` |
| commitment | `:commitment` `:commitment-fulfillment` |
| disposal | `:disposal` |
| expense | `:expense-line` `:expense-report` |
| hr | `:compensation` `:compensation-component` `:consent` `:department` `:employment` `:hr-person` `:pay-period` `:payroll-run` `:person` ⚠ |
| import-edgar | `:edgar-filing` (no overlap) |
| inventory | `:facility` `:facility-location` `:facility-product` `:inventory-detail` `:inventory-item` `:inventory-transfer` `:inventory-variance` `:negative-fill` `:physical-inventory` |
| invoice | `:gl-account-default` `:invoice` ⚠ `:invoice-line` ⚠ `:order-item-billing` |
| lease | `:lease` `:lease-liability` `:lease-modification` |
| partner | `:contact-mech` `:email-address` `:org` `:partner` `:partner-contact-mech` `:partner-contact-mech-purpose` `:partner-relationship` `:partner-role` `:person` ⚠ `:postal-address` `:telecom-number` |
| people-record | `:performance-review` `:position-held` `:promotion` |
| sales | `:inv-reservation` `:order` `:order-adjustment` `:order-item` `:order-role` `:ship-group` `:ship-group-assoc` |
| authz | (no kernel-style schema; ports relationship tuples per ADR-066) |

**Companion-only prefixes**: ~55 additional. **Plus l10n
modules** (12 of them) seed data into the kernel schema (no new
attrs) — except per the planned `:de-organschaft/*` (ADR-167
fiscal-unit; not coded yet). **Plus payroll modules** (11) — each
seeds chart routing + tax codes but adds no new schema namespaces.

**Grand total schema-namespace prefixes catalogued**: ~123 (68
kernel + ~55 companion). Closed-set; no jurisdiction-specific
attrs in the current codebase (the fiscal-unit substrate will be
the first; v2 ships with a documented per-jurisdiction-prefix
convention).

### §5.3 Collisions found

**Three confirmed collisions / double-ownership cases**:

1. **`:person/*`** — kernel + partner + hr (3-way). Resolved via
   `:hr-person/*` rename (#268) but the partner-vs-kernel overlap
   remains. v2 fix: partner owns `:person/*`; kernel removes its
   2 attrs (`:person/birth-date`, `:person/national-id`) or moves
   them under a more specific kernel namespace (e.g. `:identity/*`).
2. **`:invoice/*` + `:invoice-line/*`** — kernel + invoice
   companion (2-way). Per ADR-036 the kernel split was deliberate
   (lifecycle in kernel, GL in companion) but the namespace doesn't
   reflect that. v2 fix: kernel owns `:document.invoice/*` (already
   has the sub-namespace structure); companion owns `:invoice/*`
   only for GL-bearing attrs.
3. **`:tax-group/*`** (note 166) — VAT-side reserves it; the
   fiscal-unit substrate also wanted it. Resolved by ADR-167 by
   reserving `:fiscal-unit/*` instead. No code collision; the
   planning collision was caught before code landed.

**Stale attrs**: a focused `grep` of every `:foo/bar` attr against
`src` + `modules` + `test` would surface stale attrs. Not done in
this audit (would take ~half a day); deferred. Per the maintainer's
testing discipline, stale attrs would show up as 0-coverage in test
counts; the per-namespace audits would catch them. v2 cleanup: an
opportunity to drop any attr that has zero references outside the
schema definition itself.

**No collisions across jurisdiction**: every l10n module today
seeds into the kernel schema rather than declaring new attrs.

### §5.4 Proposed v2 schema-discipline rule

> **Every `:foo/*` namespace has ONE owner, documented in a
> per-namespace ADR addendum (or in a top-of-schema-file comment
> referencing the owning ADR). A namespace may NOT be split across
> the kernel and a companion; if a companion wants to extend, it
> uses its own `:companion-foo/*` prefix.**

This closes the `:person/*` + `:invoice/*` issues by construction.
In-place fix: rename the 2 kernel `:person/*` attrs (single tx);
rename the 4 kernel `:invoice/*` attrs to `:document.invoice/*`.
v2 fix: same renames at green-field time, zero migration cost.

---

## §6. API consistency findings — cross-reference

Consolidated from notes 160 (I-1..I-19), 168 §2 (S1-S11) + §3
(D1-D4), 169 §6 (4 P0 / 5 P1 / 5 P2), 170 §4 (F-G1..F-G8), 171 §2-§3
(A-side + B-side gaps).

| ID | Source | Title | Status | Cost in v2 |
|---|---|---|---|---|
| **I-1** | 160 | Stable-id attr differs (`:partner/external-id` vs `:entity/code` vs `:journal/code` vs `:account/path` vs `:account/code`) | OPEN | **free in v2** (uniform discipline at green-field) |
| **I-2** | 160 | bare keyword commodity auto-coerce | FIXED `2c745ee` | already done |
| **I-3** | 160 | `:account/code` not unique; `:account/path` IS | OPEN | same cost (substrate-correct as-is; doc fix only) |
| **I-4** | 160 | book verb dual-input shape (`:debit-account` + `:credit-account` OR `:postings`) | OPEN | **free in v2** (pick one canonical shape) |
| **I-5** | 160 | `Money` records fail opaquely as `:amount` | OPEN | **free in v2** (`->bigdec` recognises Money at green-field) |
| **I-6** | 160 | Verbs don't accept `:entity` | FIXED `fd3027c` | already done |
| **I-7** | 160 | No default journals in `create-test-db` | OPEN | **free in v2** (`install-default-journals!` ships with `create-test-db`) |
| **I-8** | 160 | Multi-step prerequisite-aware install dance | PARTIAL-FIX | **free in v2** (single `install-all!` per module from day 1) |
| **I-9** | 160 | Split install paths | FIXED | already done |
| **I-10** | 160 | `:to` in report windows is exclusive | FIXED `:through` `fd3027c` | already done; v2 ships `:through` as canonical |
| **I-11** | 160 | Constructor opts vary across providers | OPEN | **free in v2** (uniform constructor shape) |
| **I-12** | 160 | `commit/record-commitment!` requires status-transition seeds | FIXED | already done |
| **I-13** | 160 | Journal codes differ across tests | OPEN | **free in v2** (per-jurisdiction canonical journal codes documented) |
| **I-14** | 160 | `:entity/legal-form` is `string?`, not enum | OPEN | **free in v2** (add `:entity/legal-form-kind` keyword enum) |
| **I-15** | 160 | Per-posting `:partner` silently dropped | FIXED `fd3027c` | already done |
| **I-16** | 160 | F10 fix doesn't retro-apply | OPEN | same cost in v2 (need a backfill helper) |
| **I-17** | 160 | `trial-balance` defaults `:as-of-valid` to wall-clock now | FIXED | already done; v2 ships with `nil` default |
| **I-18** | 160 | Tax accounts missing from shipped SKR04 | FIXED `fd3027c` | already done |
| **I-19** | 160 | Cross-DB FX + treaty refs are consumer plumbing | PARTIAL (treaty-de-ca ships) | same cost (per-treaty companions, additive) |
| **S1** | 168 | No FX path on tax-emission | SUBSTRATE-FIXED T1.W1.1; per-l10n adoption pending | **free in v2** (every provider built to the new contract) |
| **S2** | 168 | Two install entry points per module | FIXED T1.W1.2 | already done |
| **S3** | 168 | `:op :base-add` vs `:tax-concept/code :base-transform-add` | OPEN | **free in v2** (pick one vocab) |
| **S4** | 168 | `:provision/compute-fn` used in 2 of 6 CIT providers | OPEN | same cost (audit + maybe data-port; substrate-correct otherwise) |
| **S5** | 168 | Provider record name drift (3 conventions) | OPEN | **free in v2** (one convention at green-field) |
| **S6** | 168 | `:as-of-valid` not in provider record spec | OPEN | **free in v2** (uniform ctx contract) |
| **S7** | 168 | `:audit-doc` not attached by tax providers | OPEN | same cost (need new builder code; substrate fix is additive) |
| **S8** | 168 | Account routing diverges (per-country chart files) | OPEN | same cost (per-country `:concept-iri` tagging is work either way) |
| **S9** | 168 | `kontor.book` adoption uneven | OPEN | **free in v2** (every provider built atop kontor.book by default) |
| **S10** | 168 | No per-module `install-all!` | FIXED T1.W1.2 | already done |
| **S11** | 168 | l10n-uk thin (CGT + investment-income only) | OPEN | same cost (deliberate per note 78 iXBRL gate; doc-only fix) |
| **D1** | 168 | Legacy ADR-005 `kontor.tax-provider` unused | OPEN | **free in v2** (drop) |
| **D2** | 168 | Record-shape CIT providers compose with nothing (Gap #5) | OPEN | same cost (Gap #5 is per-jurisdiction work either way) |
| **D3** | 168 | `kontor.commitment` 1 real consumer | OPEN | same cost (need a showcase OR drop) |
| **D4** | 168 | `:cross-tx/*` 1 real consumer | OPEN | same cost (need a showcase OR drop) |
| **169-P0a** | 169 | No kernel-side `install-all-companions!` | OPEN | **free in v2** (ships day 1) |
| **169-P0b** | 169 | `commitment` companion no real consumer | OPEN | same cost |
| **169-P0c** | 169 | partner-vs-kernel `:person/*` overlap | PARTIAL (ADR-094) | **free in v2** (clean ownership at green-field) |
| **169-P0d** | 169 | No 15-minute quickstart | PARTIAL (`doc/quickstart.md` exists, 146L) | same cost (doc-only) |
| **170-F-G1** | 170 | `:as-of-valid` default change isn't documented | OPEN | same cost (doc sweep) |
| **170-F-G2** | 170 | Per-entity P&L / BS not threaded through l10n modules | OPEN | same cost (per-module code edits) |
| **170-F-G3** | 170 | Chart bootstrapping uneven across 12 jurisdictions | OPEN | same cost (per-jurisdiction chart authoring) |
| **170-F-G4** | 170 | No batch CSV importer for opening balances | OPEN | same cost (new helper) |
| **170-F-G5** | 170 | `:entity` lifecycle implicit (no `declare!` helper) | OPEN | **free in v2** (kontor.entity/declare! ships day 1) |
| **171-T0.1** | 171 | `:local/root` deps in `deps.edn` | OPEN | **free in v2** (no `:local/root` from day 1) |
| **171-T0.2** | 171 | `CONTRIBUTING.md` missing | OPEN | same cost (doc work) |
| **171-T0.4** | 171 | ADR TOC missing | OPEN | **free in v2** (~30 distilled ADRs, easily TOC'd) |

**Tally**:
- **Free in v2** (uniform discipline at green-field, no migration): **~21** of the ~52 items
- **Same cost in v2** (need code that wasn't going to write itself): **~25** of the items
- **Already fixed in main**: **~10** items

**The "cost in v2" axis is the lever** for §13. Items where v2
is free are weighted toward port; items where v2 still costs the
same amount are weighted toward in-place cleanup (which preserves
the test suite + the integration history).

---

## §7. What to DROP in v2

The deprecated / dead / under-used primitives that wouldn't be
ported. For each: drop / rewire-as-real / fold.

| Item | Action | Rationale |
|---|---|---|
| `kontor-commitment` companion | **rewire-as-real** OR drop | Note 169 P0: 6/23 tests, zero consumers outside the companion's own tests. The substrate framing (recognising + liquidating obligations) IS correct; what's missing is the consumer wiring. v2: ship `kontor-commitment` only if at least one companion (lease modifications? AR partial payments?) wires through it. If no consumer at v2 design time, drop. |
| `:cross-tx/*` schema + `kontor.side-effect.cross` (ADR-074) | **rewire** | Note 168 D4: 1 real consumer (`kontor.incorporation`). The primitive is *structurally* required for cross-DB scenarios (note 161's two-DB walk) but the showcase doesn't yet exercise it (treaty-de-ca is a thin pure-function helper, not a cross-DB saga). v2: ship + write the cross-DB showcase as the existence proof. If no showcase appears, the substrate doesn't earn its keep. |
| `kontor.consent` (ADR-094) | **rewire** (kontor-hr only) | Note 168 D4 sibling: present + tested but few real consumers outside `kontor-hr`. Keep, scoped to `kontor-hr`. |
| `kontor.audit` / `kontor.query` / `kontor.tax` (ghost namespaces) | **drop from docs** | Note 169 §6.3.5: named in `architecture.md` + `CLAUDE.md` but absent on disk. v2: never mention them. |
| 5 record-shape CIT providers (US/AT/AU/CN/MX, Gap #5) | **port directly as ADR-101 statute-data** | Skip the record-shape intermediate state. Gap #5 work happens whether in-place or in v2; v2 just skips the deprecated step. |
| ADR-005 `TaxProvider` protocol | **drop** | Marked superseded by ADR-071 in 2026-05-17; not implemented by any code today (note 168 D1). Mark ADR superseded; do not load `kontor.tax-provider.clj`. |
| ADR-053-056 split (4 ADRs for `kontor-asset`) | **collapse to 1 ADR** | Asset register + depreciation books + DepreciationProvider + Anlagengitter — all one companion. v2 ships one ADR-053 covering all four. |
| ADR-076-087 split (11 payroll-adapter ADRs) | **collapse to 1 ADR + appendix** | One ADR-075 ("PayrollProvider trio + 11 adapters") with a per-adapter appendix. v2 reduces 11 ADRs to 1+11 appendix entries. |
| ADR-101 + Addendum 1 + Addendum 2 | **collapse to 1 ADR-101** | The addenda landed within 2 weeks of the parent ADR; ship as one in v2. |
| Per-stage research notes (147-158 — 11 investment-income research notes; 108-115 — CIT/CGT research notes) | **summarise** | The notes were valuable AT design time; v2 doesn't need to re-do the research. Ship as one `doc/research/` per jurisdiction (one note per country covering CIT + CGT + investment-income + PIT) instead of 4+ notes per country today. |
| `kontor.standalone-payroll-tax` namespace separate from `kontor.payroll` | **fold** into `kontor.tax/standalone-payroll` | The "standalone payroll tax" is a tax-side concept (a `PeriodTaxProvider`), not a payroll-side concept. Live in the tax subtree. |
| `kontor.import_/beancount.clj` placeholder dir name | **rename** to `kontor.io/beancount` | `import_` is a Python-flavored placeholder; v2 uses `io/` or `interop/`. |
| `kontor.tax-provider.clj` (ADR-005 legacy) | **delete the file** | Already unused; ghost-loading in deps. v2: gone. |
| Old datahike `:posting/valid-from` migration cruft | **gone** | The 2026-05-08 rename to `:tx/valid-from` (ADR-048) finished; v2 ships post-migration. |

**Total DROP-list items**: **12**. Of these: 3 drop-entirely
(ADR-005, kontor.tax-provider.clj, 3 ghost namespaces in docs); 4
rewire-as-real (commitment, cross-tx, consent, the 5 record-shape
CITs — each becomes "earn-it-or-drop-it" at v2 design time); 5
fold/collapse (3 ADR mergers + standalone-payroll + import_).

---

## §8. What to HOLD BACK in v2-phase-2

Things that exist (or are designed) but would be phase 2 of v2,
not the initial port.

| Item | Status today | Hold rationale |
|---|---|---|
| **Fiscal-unit substrate** (Gap #8, note 167, draft ADR-108) | designed, not coded | v2-phase-1 keeps the design as a research note; v2-phase-2 implements after community feedback signals demand (the planned pilot is DE Organschaft per note 167 §3.4). |
| **Pillar Two compute** | not started; explicit non-goal | OECD GloBE rules; out of scope. |
| **Stratum SIMD on datahike secondary index** | task #248, deferred | The reactive read-path performance work belongs in stratum, not kontor. |
| **`kontor-mcp` standalone server** | task #260, deferred | Gated on a consumer asking for it. |
| **Showcase 07 (agent + MCP + people-record)** | task #265, deferred | Gated on the MCP server existing. |
| **Property / wealth / land tax** | uniformly missing across 11 of 12 jurisdictions (note 168 §1.1) | Needs a research-and-decision pass (note 168 §6 W2.8) before any per-jurisdiction code lands. |
| **JP large-corp pro-forma enterprise tax** | task #290, pending | Per-jurisdiction Phase 3 work; design pattern is settled (5-component stack per ADR-106), code is the gap. |
| **UK CIT + PIT + UK-VAT + RTI** | deliberate per note 78 §3 (iXBRL gate) | Wait for iXBRL substrate work. |
| **Per-companion `deps.edn`** (ADR-006 split-when-needed) | not needed yet | The current monorepo `:paths` works for the maintainer; v2 splits when an external consumer needs only one l10n module. |
| **Per-module SPDX manifest** | not needed yet | License audit tooling concern; v0.2 work per note 171 §4. |
| **Cross-jurisdiction integration tests (3+ providers)** | partial (cgt_pit_integration_test for US; christian_scenario for DE+CA; Jane-Doe payroll for 3 jurisdictions) | More cross-jurisdiction scenarios = better confidence, but each scenario is per-pair work; ship more as the consumers ask. |
| **Group consolidation companion (`kontor-consolidation`)** | kernel primitive ships (ADR-073) but no companion (ownership %, minority interest, IFRS 10 control, IFRS 3 goodwill, IAS 27 step-acquisitions) | Note 169 P2: the kernel primitive is enough for the maintainer's setup; the companion is consumer-driven. |
| **Per-jurisdiction `EInvoiceProvider` consolidation** | DE has a dedicated `einvoice-de` companion; BR / IN / CN ship inside l10n-* | Naming-vs-physics seam (note 95). Hold; the inconsistency is acceptable for v2-phase-1. |
| **Reverse-direction treaty helpers** (DE-receives-from-CA, etc.) | only DE→CA shipped (4/15 tests, treaty-de-ca companion) | Pattern is sound; consumers add new pairs as needed. |
| **`kontor.event-bus` real consumer demo** | 9/27 tests, no in-tree consumer | Held until a consumer signals (reactive UI on simmis is the planned consumer). |
| **`kontor.agent-tools` MCP wiring** | 9/39 tests, no `kontor-mcp` server yet | Held with the MCP server. |
| **Big4-examination benchmark** (task #246) | spec pending | Held for v2-phase-2 once consumers are running real books. |
| **Beleg → kontor UI / PDF rendering** (task #247) | sketch pending | Beleg-side work; not kontor-substrate work. |

**Total HOLD-list items**: **18** — most are explicit non-goals
or deferred-pending-signal items.

---

## §9. Tax-completeness honest table

Per-jurisdiction × per-tax-category, mirroring note 168 §1.1 with a
"v2 port status" column added. Cells use the same legend
(`[shipped]` = ADR-101 statute-as-data; `[record]` = record-shape
provider; `[partial]` = some sub-component; `[missing]` = no code).

| Jurisdiction | Tax | Today | v2 port | What's incomplete (honestly) |
|---|---|---|---|---|
| **DE** | CIT | `[shipped]` ADR-104 KSt+Soli+GewSt | full port (ADR-101 data) | Pillar Two transposition (held); Organschaft (Gap #8, designed) |
| DE | PIT | `[shipped]` Einkommensteuer | full port | thin: 2/9 tests; needs broader scenario coverage |
| DE | CGT | `[shipped]` 2 providers (§17 Teileinkünfte + §23 Privatveräußerung) | full port | — |
| DE | Investment-income | `[shipped]` (KESt Abgeltungsteuer) | full port | — |
| DE | VAT | `[shipped]` (UStVA) | full port | — |
| DE | Payroll | `[shipped]` DATEV LODAS | full port | — |
| DE | Property/wealth | `[missing]` | hold; await property-tax decision | — |
| DE | Fiscal-unit | `[planned]` (Gap #8) | hold v2-phase-2 | — |
| **FR** | CIT | `[shipped]` ADR-105 (PME 15/25% + CGE + mère-fille + CIR) | full port | — |
| FR | PIT | `[shipped]` | full port | thin: 2/6 tests |
| FR | CGT | `[shipped]` 2 providers (personal + corporate) | full port | — |
| FR | Investment-income | `[shipped]` (PFU 31.4% vs barème) | full port | — |
| FR | VAT | `[shipped]` (CA3) | full port | — |
| FR | Payroll | `[shipped]` DSN NEODES | full port | — |
| FR | Property/wealth | `[missing]` | hold | — |
| FR | Fiscal-unit | `[planned]` (intégration fiscale) | hold | — |
| **CA** | CIT | `[shipped]` ADR-107 (T2 fed + per-province) | full port | — |
| CA | PIT | `[shipped]` ADR-099 pilot (CaT1) | full port | thin: 4/17 tests; sole-prop → T1 composition test missing (note 171 A.2.b P0) |
| CA | CGT | `[shipped]` | full port | — |
| CA | Investment-income | `[shipped]` | full port | — |
| CA | VAT/GST/HST | `[shipped]` (gst_hst) | full port | — |
| CA | Payroll | `[shipped]` + QC RL-1 | full port | — |
| CA | Property/wealth | `[missing]` | hold | — |
| CA | Fiscal-unit | `[planned]` (acquisition-of-control rules; not piloted in note 167) | hold | — |
| **JP** | CIT | `[shipped]` ADR-106 (5-component stack) | full port | pro-forma enterprise tax for large corps (Gap #4 pending) |
| JP | PIT | `[shipped]` 4 providers | full port | — |
| JP | CGT | `[shipped]` (5-component, Jan-1 measurement rule) | full port | — |
| JP | Investment-income | `[shipped]` | full port | — |
| JP | VAT | `[shipped]` (consumption_tax) | full port | — |
| JP | Payroll | `[shipped]` Gensen | full port | — |
| JP | Property/wealth | `[missing]` (固定資産税 not modelled) | hold | — |
| JP | Fiscal-unit | `[planned]` (group-tsuusan) | hold | — |
| **US** | CIT | `[record]` Form 1120 flat-21% | **port AS ADR-101** (Gap #5 — free in v2) | CAMT, state CITs |
| US | PIT | `[record]` Form 1040 (in same file as 1120) | port AS ADR-101 | — |
| US | CGT | `[shipped]` (§1(h) LT brackets + §1411 NIIT) | full port | — |
| US | Investment-income | `[shipped]` | full port | — |
| US | Sales tax | `[partial]` (Avalara/TaxJar protocol scaffold) | full port (no rates bundled — ADR-005) | per ADR-005 design |
| US | Payroll | `[shipped]` ADP GLI + multi-state | full port | — |
| US | Property/wealth | `[missing]` (50-state patchwork — not in scope) | hold | — |
| US | Fiscal-unit | `[planned]` (§1502) | hold | — |
| **AU** | CIT | `[record]` (au-company-tax-provider) | **port AS ADR-101** (Gap #5 — free in v2) | — |
| AU | PIT | `[record]` (au-income-tax-provider) | port AS ADR-101 | — |
| AU | CGT | `[shipped]` (Div 115 50% discount) | full port | — |
| AU | Investment-income | `[shipped]` | full port | — |
| AU | GST | `[shipped]` (gst + bas) | full port | — |
| AU | Payroll | `[shipped]` STP P2 | full port | — |
| AU | Property/wealth | `[missing]` (state land tax) | hold | — |
| AU | Fiscal-unit | `[missing]` | hold | — |
| **AT** | CIT | `[record]` | **port AS ADR-101** (Gap #5 — free in v2) | — |
| AT | PIT | `[record]` | port AS ADR-101 | — |
| AT | CGT | `[shipped]` 3 providers (KESt + ImmoESt + corp.) | full port | — |
| AT | Investment-income | `[shipped]` 2 providers | full port | — |
| AT | VAT | `[shipped]` (uva) | full port | — |
| AT | Payroll | `[shipped]` mBGM + L16 | full port | — |
| AT | Property/wealth | `[missing]` | hold | — |
| AT | Fiscal-unit | `[missing]` (no Gruppenbesteuerung) | hold | — |
| **BR** | CIT | `[shipped]` (IRPJ + CSLL + JCP cap) | full port | — |
| BR | PIT | `[shipped]` IRPF (211 LOC) | full port | — |
| BR | CGT | `[shipped]` | full port | — |
| BR | Investment-income | `[shipped]` | full port | — |
| BR | Clearance | `[partial]` (cst + nfe + sped + periodic_returns) | full port (no unified VAT provider — scaffolding only) | unified VAT provider |
| BR | Payroll | `[shipped]` eSocial | full port | — |
| BR | Property/wealth | `[missing]` | hold | — |
| BR | Fiscal-unit | `[missing]` | hold | — |
| **IN** | CIT | `[shipped]` (with §115BAA :schedule-override + 4% cess) | full port | — |
| IN | PIT | `[shipped]` (old/new regime) | full port | — |
| IN | CGT | `[shipped]` | full port | — |
| IN | Investment-income | `[shipped]` | full port | — |
| IN | GST | `[partial]` (irn + ewb + returns; no unified provider) | full port | unified GST liability provider |
| IN | Payroll | `[shipped]` TDS + PF + ESI + PT | full port | — |
| IN | Property/wealth | `[missing]` | hold | — |
| IN | Fiscal-unit | `[missing]` | hold | — |
| **MX** | CIT | `[record]` (mx-isr-corporate-provider) | **port AS ADR-101** (Gap #5 — free in v2) | — |
| MX | PIT | `[record]` (mx-isr-personal-provider) | port AS ADR-101 | — |
| MX | CGT | `[shipped]` | full port | — |
| MX | Investment-income | `[shipped]` | full port | — |
| MX | Clearance | `[partial]` (cfdi + returns; no IVA provider) | full port | IVA provider |
| MX | Payroll | `[shipped]` CFDI Nómina | full port | — |
| MX | Property/wealth | `[missing]` | hold | — |
| MX | Fiscal-unit | `[missing]` | hold | — |
| **CN** | CIT | `[record]` (cn-eit-provider flat 25%) | **port AS ADR-101** (Gap #5 — free in v2) | — |
| CN | PIT | `[record]` (cn-iit-provider) | port AS ADR-101 | — |
| CN | CGT | `[shipped]` + bespoke `lat_provider` | full port | — |
| CN | Investment-income | `[shipped]` 2 providers | full port | — |
| CN | VAT | `[shipped]` (vat + fapiao + returns) | full port | — |
| CN | Payroll | `[shipped]` IIT + 五险一金 | full port | — |
| CN | Property/wealth | `[partial]` (LAT only) | hold (broader property tax) | — |
| CN | Fiscal-unit | `[missing]` (no EIT consolidation) | hold | — |
| **UK** | CIT | `[missing]` (deliberate per note 78 iXBRL gate) | hold v2-phase-2 (post-iXBRL) | — |
| UK | PIT | `[missing]` (same gate) | hold | — |
| UK | CGT | `[shipped]` | full port | — |
| UK | Investment-income | `[shipped]` | full port | — |
| UK | VAT | `[missing]` | hold | — |
| UK | Payroll | `[missing]` (RTI — in deferral set) | hold | — |
| UK | Property/wealth | `[missing]` | hold | — |
| UK | Fiscal-unit | `[missing]` | hold | — |

**Per-jurisdiction summary** (v2 port status):
- **6 fully-statute-data CITs port 1:1**: DE / FR / CA / JP / BR / IN
- **5 CITs port AS ADR-101 (Gap #5 work — free in v2 at green-field write-time)**: US / AT / AU / CN / MX
- **1 CIT held**: UK (iXBRL gate)
- **All 11 CGT + all 11 investment-income + all 5 unified VAT providers port 1:1**
- **3 partial-VAT modules** (BR / IN / MX clearance) port as-is; unified VAT provider work is the same cost either way
- **All 11 payroll adapters port 1:1**
- **Property/wealth uniformly missing**; held for the W2.8 research-and-decision call

---

## §10. Proposed v2 file layout

```
kontor-v2/
├── README.md
├── CHANGELOG.md
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── LICENSE                              # EPL-1.0
├── deps.edn                             # single dep: datahike (:mvn/version, NOT :local/root)
├── bb.edn
├── .github/
│   ├── workflows/ci.yml
│   ├── ISSUE_TEMPLATE/{bug,feature,question}.md
│   └── PULL_REQUEST_TEMPLATE.md
├── doc/
│   ├── decisions.md                     # ~30 distilled ADRs (was ~107)
│   ├── history.md                       # what each stage learned, post-distillation
│   ├── architecture.md                  # the layer cake (no ghost namespaces)
│   ├── conventions.md                   # all post-160 decisions baked in
│   ├── quickstart.md                    # 50-LOC walkthrough; the README's source-of-truth
│   ├── ecosystem.md                     # pg-datahike / beleg / simmis / spindel refs moved here
│   ├── adding-a-jurisdiction.md         # concrete recipe (T0.5 from note 171)
│   ├── value.md                         # evaluator pitch
│   ├── programming.md                   # developer-facing 3-axis model
│   ├── accounting-model.md              # debit/credit bridge
│   ├── research/                        # ~30 notes (was 172); per-jurisdiction collapsed
│   ├── showcases/                       # 6-7 Clay notebooks; quarto-rendered to docs/
│   └── walkthroughs/
│       └── christian-scenario.md        # the canonical two-DB walk
├── src/kontor/
│   ├── core.clj
│   ├── schema.clj
│   ├── money.clj
│   ├── posting.clj
│   ├── entity.clj
│   ├── period.clj
│   ├── bitemporal.clj
│   ├── process.clj
│   ├── book.clj
│   ├── consolidation.clj
│   ├── incorporation.clj
│   ├── schedule.clj
│   ├── event_bus.clj
│   ├── explain.clj
│   ├── agent_tools.clj
│   ├── preset.clj                       # the kernel-side install-all-companions!
│   ├── compliance/
│   │   ├── audit_doc.clj
│   │   ├── legal_hold.clj
│   │   ├── retention.clj
│   │   ├── dsar.clj
│   │   ├── sealing.clj
│   │   ├── status_machine.clj
│   │   ├── state_machine.clj
│   │   └── validation.clj
│   ├── report/
│   │   ├── balance.clj
│   │   ├── ledger.clj
│   │   ├── trial.clj
│   │   ├── closing.clj
│   │   ├── marginalize.clj
│   │   ├── report.clj
│   │   └── financial_statements.clj
│   ├── tax/
│   │   ├── rate_provider.clj
│   │   ├── posting_builder.clj
│   │   ├── period_provider.clj
│   │   ├── return_builder.clj
│   │   ├── schedule.clj
│   │   ├── statute.clj
│   │   ├── standalone_payroll.clj
│   │   ├── corporate_income.clj
│   │   ├── personal_income.clj
│   │   ├── cgt.clj
│   │   ├── sole_proprietor.clj
│   │   └── vat_return.clj
│   ├── fx/
│   │   ├── fx.clj
│   │   └── rate_provider.clj
│   ├── money_flow/
│   │   ├── payment_application.clj
│   │   ├── aging.clj
│   │   ├── payment_term.clj
│   │   ├── reconciliation.clj
│   │   ├── bank_account.clj
│   │   └── bank_csv.clj
│   ├── costing/
│   │   ├── valuation.clj
│   │   └── provider.clj
│   ├── saga/
│   │   ├── in_process.clj
│   │   └── cross_db.clj
│   ├── payroll.clj
│   ├── einvoice.clj
│   ├── document/
│   │   └── invoice.clj
│   └── io/
│       └── beancount.clj
├── modules/
│   ├── asset/                           # README + LICENSE-link + src + test
│   ├── lease/
│   ├── inventory/
│   ├── procurement/
│   ├── collections/
│   ├── invoice/
│   ├── sales/
│   ├── partner/                         # owns :person/* now
│   ├── hr/                              # uses :person/* from partner; owns :hr/*
│   ├── expense/
│   ├── authz/
│   ├── disposal/
│   ├── commitment/                      # only if real consumer wires it
│   ├── people-record/
│   ├── import-gleif/
│   ├── import-edgar/
│   ├── einvoice-de/
│   ├── bank-{at,ca,de,fr,us}/
│   ├── treaty-de-ca/
│   ├── l10n-{at,au,br,ca,cn,de,fr,in,jp,mx,uk,us}/
│   │   ├── src/
│   │   ├── test/
│   │   ├── resources/
│   │   └── README.md                    # per-jurisdiction coverage table (lifted from note 168 §1)
│   └── payroll-{at,au,br,ca,cn,de-datev,fr,in,jp,mx,us-adp}/
└── test/
    ├── kontor/                          # mirrors src/
    └── integration/
        ├── christian_scenario_test.clj
        ├── jane_doe_payroll_triangle_test.clj
        ├── multi_year_de_gmbh_test.clj
        └── ...
```

**Specific merges from current to v2**:

- `src/kontor/balance.clj` + `ledger.clj` + `trial.clj` + `closing.clj` + `report.clj` + `financial_statements.clj` → `src/kontor/report/{balance,ledger,trial,closing,report,financial_statements,marginalize}.clj` (`marginalize.clj` extracted from the current `report.clj` body)
- `src/kontor/audit_doc.clj` + `legal_hold.clj` + `retention.clj` + `dsar.clj` + `sealing.clj` + `status_machine.clj` + `state_machine.clj` + `validation.clj` → `src/kontor/compliance/{audit_doc,legal_hold,retention,dsar,sealing,status_machine,state_machine,validation}.clj`
- `src/kontor/tax_rate_provider.clj` + `tax_posting_builder.clj` + `period_tax_provider.clj` + `tax_return_posting_builder.clj` + `tax_schedule.clj` + `statute.clj` + `standalone_payroll_tax.clj` + `corporate_income_tax.clj` + `personal_income_tax.clj` + `cgt.clj` + `sole_proprietor.clj` + `vat_return.clj` → `src/kontor/tax/*` (12 files in a subtree)
- `src/kontor/fx.clj` + `fx_rate_provider.clj` → `src/kontor/fx/{fx,rate_provider}.clj`
- `src/kontor/payment_application.clj` + `aging.clj` + `payment_term.clj` + `reconciliation.clj` + `bank_account.clj` + `bank_csv.clj` → `src/kontor/money_flow/*` (6 files)
- `src/kontor/valuation.clj` + `costing_provider.clj` → `src/kontor/costing/{valuation,provider}.clj`
- `src/kontor/side_effect.clj` + `side_effect/cross.clj` → `src/kontor/saga/{in_process,cross_db}.clj`
- `src/kontor/payroll_provider.clj` → `src/kontor/payroll.clj` (collapsed)
- `src/kontor/einvoice_provider.clj` → `src/kontor/einvoice.clj` (collapsed)
- `src/kontor/disposal_source.clj` → fold into `src/kontor/tax/cgt.clj`
- `src/kontor/import_/beancount.clj` → `src/kontor/io/beancount.clj`

**No splits required**; the v2 layout is purely consolidation, not
decomposition.

---

## §11. Port sequencing

If we DID execute the port end-to-end, the order:

| Stage | Scope | Effort | End-of-stage gate |
|---|---|---|---|
| **1. Bootstrap** | `core` + `schema` + `money` + `posting` + `entity` + `period` + `bitemporal` + `compliance/validation` + vendored `invariant` | ~1 week | `bb test` on core ns passes; can post + read a balanced tx |
| **2. Reports + balance** | `report/{balance,ledger,trial,closing,marginalize,report,financial_statements}` | ~3 days | balance + trial + marginalize tests pass |
| **3. Compliance + audit** | `compliance/{audit_doc,legal_hold,retention,dsar,sealing,status_machine,state_machine}` | ~1 week | full compliance suite passes (~50 tests / 150 assertions) |
| **4. Provider protocols** | Define + default-impl all 8 normalized kernel protocols; ship `kontor.fx/*`, `kontor.costing/*`, `kontor.tax/*` substrate (rate-provider, posting-builder, period-provider, return-builder, schedule, statute), `kontor.payroll`, `kontor.einvoice`, `kontor.tax/cgt` (with `DisposalSource` folded) | ~2 weeks | protocol + default-impl tests pass; statute eval passes; tax-schedule tests pass |
| **5. `process` + `book` + `incorporation` + `consolidation` + `schedule` + `event_bus` + `explain` + `agent_tools` + `money_flow/*` + `document/invoice` + `io/beancount`** | ~1 week | every kernel ns has a test; book verbs work end-to-end |
| **6. First companion (asset)** | `kontor-asset` clean port — reference for every subsequent companion | ~1 week | asset lifecycle works; depreciation runs |
| **7. Companion fan-out** | `lease` / `inventory` / `procurement` / `collections` / `invoice` / `sales` / `partner` / `hr` / `expense` / `authz` / `disposal` / `commitment` (if rewired) / `people-record` / `import-gleif` / `import-edgar` / `einvoice-de` / `bank-{at,ca,de,fr,us}` / `treaty-de-ca` | ~3 weeks | every companion has a test; the canonical 17 companion suite is green |
| **8. First l10n (DE)** | `l10n-de` full port — CIT (ADR-101 data) + CGT + investment-income + chart (SKR04) + VAT (UStVA) + preset + README + tests | ~1 week | DE preset boots; DE-CIT numbers match the cent; UStVA computes |
| **9. l10n fan-out** | CA / FR / JP / BR / IN (the 5 fully-statute-data jurisdictions other than DE) | ~3 weeks (1/2 wk each) | each preset boots + tests pass |
| **10. Gap #5 fan-out** | US / AT / AU / CN / MX as ADR-101 statute-data (skipping the record-shape intermediate state) | ~4 weeks (1 wk each, includes the statute research) | full coverage matrix is green on the new substrate |
| **11. UK (CGT + investment-income only)** | as-is port | ~2 days | UK README documents the iXBRL deferral |
| **12. Payroll fan-out** | 11 payroll adapters: DE-DATEV / US-ADP / CA + QC RL-1 / FR-DSN / AU-STP-P2 / BR-eSocial / MX-CFDI / IN-TDS-PF-ESI-PT / JP-Gensen / CN-IIT-五险一金 / AT-mBGM-L16 | ~4 weeks (parallelizable) | every payroll preset runs end-to-end |
| **13. Showcase + integration tests** | port `christian_scenario_test`, `jane_doe_payroll_triangle`, `multi_year_de_gmbh`, 6 showcases | ~1 week | full integration suite green; quarto renders link from README |
| **14. Doc finalization** | README + CHANGELOG + CONTRIBUTING + decisions.md (distilled to ~30 ADRs) + per-l10n READMEs + adding-a-jurisdiction.md + history.md | ~2 weeks | v0.1.0-beta release tag |

**End-of-stage acceptance**: every stage ends with `bb test` green
+ the stage's specific functional gate. No cumulative debt across
stages.

---

## §12. Honest cost estimate

### §12.1 Port end-to-end

Per §11: roughly **16-20 weeks of focused single-person work**.
Translating to agent-execution-budget terms: agent runs at ~2x
maintainer-velocity per stage, so ~8-10 weeks of agent-driven port
with maintainer review per stage. Cost: **~$300-600** in API
spend at current rates (each stage ~$30-60 in agent tokens including
research + review), plus maintainer review time.

Wall-clock if executed in parallel where stages allow (payroll
fan-out, l10n fan-out, some companion fan-out): **~10-14 weeks
wall-clock**.

### §12.2 Disciplined in-place cleanup

A bounded sweep addressing the same structural debt without
leaving the repo:

| Task | Effort |
|---|---|
| Drop ADR-005 + ghost-namespace doc sweep | 1 hour |
| Provider record naming sweep (sed + tests) | 2 hours |
| `:person/*` ownership cleanup (kernel + hr + partner) | 4 hours |
| `:invoice/*` ownership cleanup | 4 hours |
| Per-companion `install-all!` (16 companions × ~15 LOC) | 1 day |
| Kernel-side `install-all-companions!` | 4 hours |
| Constructor-opts uniformity sweep (across providers) | 1 day |
| `:as-of-valid` provider-record spec doc | 4 hours |
| `:op :base-add` vs `:base-transform-add` vocab pick | 4 hours |
| Gap #5 CIT migration (US / AT / AU / CN / MX) | 4-6 weeks (same as in v2; the work doesn't change) |
| ADR distillation (107 → ~30 in decisions.md + write history.md) | 1 week |
| File/namespace reorg per §10 (move + ns-decl updates + test moves) | 1 week |
| Per-module READMEs (32 of 46 missing) | 1 week |
| CONTRIBUTING + .github/ + ci.yml + ADR TOC | 1-2 days |
| `:local/root` deps → `:git/url+:sha` | 2-4 hours |
| New integration tests (sole-prop→T1 + 2026→2027 rollover) | 1-2 days |
| Bring 4 deprecated items to "rewire OR drop" decision (commitment / cross-tx / consent / record-CITs) | 1 week |

**Total in-place**: **8-12 weeks of focused single-person work**;
~$150-300 agent spend; **6-10 weeks wall-clock**.

### §12.3 Honest comparison

| Metric | v2 port | In-place sweep | Hybrid |
|---|---|---|---|
| Focused-work weeks | 16-20 | 8-12 | 10-14 |
| Wall-clock weeks | 10-14 | 6-10 | 8-12 |
| Agent token spend | $300-600 | $150-300 | $200-450 |
| Risk of regression | LOW (tests port verbatim) | MEDIUM (refactor-in-place breakage windows) | LOW-MEDIUM |
| Preserves test history | tests yes, git history no | YES | YES |
| Preserves issue/PR history | NO | YES | YES |
| Preserves the integration-debugging history (notes 159-171) | as research notes | YES | YES |
| Outcome at the end | clean repo, fresh tags, fresh issues | clean repo, full history, big diff over a few weeks | clean repo, full history, distilled draft visible as `v2-design/` |
| Audience win | "v2.0 new repo" press is real | "the 0.2 cleanup" is real but less marketable | "we rebuilt in-place, the new layout is here" — credible |

The **port is ~2x the work for ~0x improvement in substrate
quality** over the in-place sweep. The port's win is **the
cognitive surface reset** (107 ADRs → ~30; flat 57-file kernel →
subtree-organized 50). The in-place sweep gets the same outcome on
the substrate; it does NOT get the cognitive surface reset because
the git log preserves the full ADR history.

The hybrid (option c in §13) splits the difference: in-place
cleanup as the *real* work; a `v2-design/` directory inside the
repo where the maintainer drafts the distilled ADRs + the
target namespace layout BEFORE the in-place sweep applies them.
That gives the cognitive surface reset (the maintainer reads the
30 distilled ADRs as the cleanup proceeds) without the cost of
forking the repo.

---

## §13. Recommendation

**(c) Hybrid: keep this repo, draft the v2 structure as a
`kontor-v2-design/` directory inside it, then port-as-you-go via
disciplined in-place cleanup over the next 6-10 weeks.** Only
consider moving to a fresh repo once the kernel substrate is
stable across 2-3 community-feedback cycles AND a real consumer
ships using kontor (beleg or simmis). The evidence (§6's free-in-
v2 column being ~21/52 items, §12's 2x cost ratio, the test suite
being load-bearing for the substrate's "structurally complete"
claim per note 168 §7, and the McComb-aligned substrate seams
ADR-090/091/092 being barely exercised per note 171 §6 reason 3)
all point to a substrate that is **correct but young** — young
substrates benefit from preserving the integration-debugging
history (every commit between `fd3027c` and `c1143fb` was a
real-use surfacing), and the 107-ADR pile, distilled into a
~30-ADR `kontor-v2-design/decisions.md` draft alongside the
current `doc/decisions.md`, gives the maintainer the cognitive
surface reset without the cost of starting over. The single thing
that would change this recommendation is if a community reviewer
during v0.1.0-alpha feedback identifies a **substrate-correct flaw**
that requires a clean break (e.g. "the bitemporal model should
unify valid + decision time" or "sealing should be commit-hash-
linked"); none of the existing notes 159-171 surface such a
flaw, so the hybrid is the rational path today.

---

*End of note 172.*

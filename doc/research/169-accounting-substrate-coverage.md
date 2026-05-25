---
date: 2026-05-25
title: 169 — Accounting-substrate coverage map (non-tax half; input to note 171 publishability)
audience: maintainer (first-complete-iteration / GitHub-v1 sequencing); pairs with note 168 (tax) and feeds note 171 (publishability)
status: wide-then-medium survey — no code changed; reads `src/kontor/`, `modules/` (excl. l10n / payroll), `tests.edn`, `deps.edn`, `doc/{architecture,decisions,roadmap}.md`, `doc/showcases/`
related:
  - note 168 (tax-surface coverage; the *tax* half of this map)
  - note 161 / 159 (Phase-D + C3 REPL exploration; surfaced silent-wrong P0s)
  - note 160 (API-consistency audit, in-flight)
  - note 167 (fiscal-unit synthesis; the planned consolidation companion)
  - note 99 / 97 / 98 (the McComb / event-driven accounting arc; ADR-095..098)
  - note 102 / 104 (the tax-completion program — substrate provenance is shared)
  - ADR-067 / 068 (`kontor.process` + `*-tx-data` builder convention)
  - ADR-095 (`kontor.book` verb facade)
  - ADR-096 (`marginalize` σ_E — the report substrate)
  - ADR-007 / 008 / 048 (sealing + bitemporal)
  - ADR-034 / 038 (status machine + audit-doc / approval-policy)
  - ADR-049 / 050 / 051 / 052 (legal-hold + retention + privilege + DSAR)
  - ADR-071 / 072 / 073 / 074 / 075 / 103 (provider protocols)
---

# 169 — Accounting-substrate coverage map

## TL;DR

The **accounting** substrate (everything kontor ships that is *not* a
per-jurisdiction tax provider) is the kernel + 17 companion modules
the maintainer has built over ~108 ADRs. This note maps it the same
way note 168 mapped the tax surface — kernel namespace by kernel
namespace, companion by companion, protocol by protocol — so note 171
can decide *what is publishable, what is shipped-but-shallow, and
what is materially absent*.

**Shape of the substrate**: a **double-entry kernel that is small,
opinionated, and deep on three orthogonal axes** (bitemporality,
status-machine-as-audit-trail, statute-as-data) wrapped by **8
provider protocols** and consumed by **17 companions** that range
from deep-and-tested (asset, lease, inventory, partner, collections,
procurement, hr) to thin-but-real (commitment, disposal, expense) to
import-only (gleif, edgar, people-record, treaty-de-ca).

**Numbers**: 57 kernel namespaces (incl. 2 sub-namespaces), 17 non-
l10n / non-payroll companions, 8 protocols in the kernel + 5 more
in companions, 7 showcase notebooks, 1 cross-company integration
test (`christian_scenario_test.clj`). Kernel test density is roughly
**530 deftests / 1655 `is`-assertions** before the l10n / payroll
overlay — i.e. the substrate itself carries about a quarter of the
project's total ~3050 deftests.

**Gaps for v1** (full list §6):
- **P0** — no kernel-side `install-all-companions!` (each companion
  is wired by hand; the consumer cargo-cults from
  `christian_scenario_test`); the **commitment** companion ships
  schema + 6 tests but no consumer outside its own test ns; the
  **partner** companion still owns a small overlap with kernel
  `:person/*` (resolved by ADR-094 but the overlap is documented
  rather than collapsed).
- **P1** — the **expense / people-record / treaty-de-ca / import-***
  companions are deliberately minimal; their `deftest` counts are
  small (4-9) and a v1 consumer will discover surface bugs first.
  `kontor.process` is correctly used internally but the doc is one
  paragraph in `architecture.md`; consumers building their own
  orchestrators need a quick-start.
- **P2** — three orphaned-by-rename namespaces (`audit.clj`,
  `query.clj`, `tax.clj`) appear in `architecture.md` / `CLAUDE.md`
  but the files do not exist — what survives is `audit-doc.clj`,
  the bitemporal helpers, and the protocol trio; doc-vs-code drift.

**Shape verdict (one sentence):** *kontor is a small, deep, opinionated
double-entry kernel with a credible substrate for bitemporal audit, status
machines, statute-as-data, multi-entity consolidation, and the standard
companion suite — but the consumer-onboarding surface (one-call install,
a quickstart that doesn't read like a research note) is the v1 gap, not
the substrate itself.*

---

## §1. Kernel accounting primitives

The kernel ships **55 top-level + 2 sub-namespace files** in
`src/kontor/`. The table below covers them grouped by concern; tax-
specific namespaces (rate / period / disposal / statute / schedule
/ sole-prop / VAT-return / standalone-payroll / corporate-income /
personal-income / CGT-composition) appear here for completeness but
are surveyed in *depth* in note 168; this note treats them as
substrate seams.

**Test counts** = `deftest / is-assertions` grep'd from
`test/kontor/*_test.clj` (sibling-named) on 2026-05-25.

### §1.1 — Core posting + balance + period

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.schema` | Schema EDN — kernel attrs across ~30 namespaces | `kernel-schema`, `install!` | ADR-002 | shipped | 5 / 13 |
| `kontor.core` | Lifecycle — `create-test-db`, `install-schema!`, provider registration | `create-test-db`, `install-schema!`, `with-tax-rate-provider` | ADR-002 | shipped | — (covered by every test) |
| `kontor.money` | `Money` = BigDecimal + commodity tag, HALF-EVEN arithmetic | `make`, `+`, `-`, `*`, `compare-numerically` | ADR-013 | shipped | 38 / 71 |
| `kontor.posting` | Build balanced txs — sum-to-zero per (entity, ledger, commodity) | `build-transaction`, `post-transaction-tx-data`, `post-transaction!`, `expand-distribution`, `plan-stock-move` | ADR-021 / ADR-031 | shipped | 28 / 75 |
| `kontor.balance` | Per-account bitemporal balance (`{commodity Money}`) | `account-balance` | ADR-008 / ADR-048 | shipped | 9 / 15 |
| `kontor.ledger` | Postings against an account; account-vs-ledger glossary | `postings-against-account`, `ledger-entries` | ADR-021 | shipped | 6 / 17 (ledger_entity test) |
| `kontor.trial` | Trial balance over a date range, bitemporal | `trial-balance`, `trial-balance-by-account` | ADR-008 | shipped | 7 / 15 |
| `kontor.closing` | Year-end close — roll P&L → retained earnings | `close-period-tx-data`, `close-period!` | (kernel-internal) | shipped | 5 / 16 |
| `kontor.period` | Open/close periods + soft/hard lock | `open!`, `close!`, `lock-period!`, `posting-falls-in-locked?` | ADR-014 | shipped | 21 / 25 |
| `kontor.entity` | Legal entity / accounting unit, `:entity/kind` walk | `family`, `descendants`, `ancestors`, `parent` | ADR-031 | shipped | 11 / 48 |
| `kontor.validation` | Runtime invariants via `datopia/invariant` + middleware | `transact-with-validation`, `register-invariant!` | ADR-011 | shipped | 7 / 8 |

### §1.2 — Time + audit + governance

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.bitemporal` | Thin write-side shim over upstream `:db.valid/from` — `with-vt`, `close-validity` | `with-vt`, `close-validity`, `posting-vf-by-tx` | ADR-008 / ADR-048 | shipped | 10 / 24 |
| `kontor.sealing` | Refuse silent retract of `:posting/posted-at`-marked datoms | `find-silent-retracts`, `assert-no-silent-retracts!`, `transact-with-sealing` | ADR-007 | shipped | 7 / 10 |
| `kontor.audit-doc` | Supporting-doc entity + `:audit-doc/privilege` status facet | `audit-doc-tx-data`, `reclassify-privilege!`, canonical-categories | ADR-038 / ADR-051 | shipped | 7 / 13 (privilege) |
| `kontor.status-machine` | Generic state-machine — `:status-transition` + `:status-history` + approval policies | `record-status-change!`, `record-status-change-tx-data`, `legal-transition?`, `check-policies`, `bulk-record-status-change!`, `current-status` | ADR-034 / ADR-038 | shipped | 14 / 38 |
| `kontor.state-machine` | Transaction-state lifecycle `draft → posted → cancelled` (kernel-internal) | `advance-state!`, `advance-state-tx-data` | (kernel-internal) | shipped | 12 / 29 |
| `kontor.legal-hold` | Write-time invariant blocking `:db/purge` of held entities | `apply-hold!`, `release-hold!`, `assert-not-held!` | ADR-049 | shipped | 14 / 27 |
| `kontor.retention` | Time-based expiry sweeper that respects holds | `sweep-time-based!` | ADR-050 | shipped | 13 / 51 |
| `kontor.dsar` | DSAR `collect` walk + companion-registered partner-attr registry | `register-partner-attr!`, `register-tx-attr!`, `register-extension-collector!`, `collect`, `file-request!`, `advance-state!` | ADR-052 | shipped | 9 / 38 |

### §1.3 — Composition primitives

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.book` | Verb facade — `receive`/`pay`/`sell`/`buy`/`receive-payment`/`pay-bill`/`transfer`/`adjust` (8 verbs) | `entry-tx-data`, `entry!`, `receive!`, `pay!`, `sell!`, `buy!`, `receive-payment!`, `pay-bill!`, `transfer!`, `adjust!` | ADR-095 | shipped | 6 / 31 |
| `kontor.process` | Multi-step transactional processes as pure step-lists | `run-process`, `run-steps` | ADR-067 | shipped | 11 / 19 |
| `kontor.side-effect` | `:side-effect-intent` dispatcher (in-process) | `dispatch-intents!`, `register-handler!` | ADR-041 | shipped | 3 / 12 |
| `kontor.side-effect.cross` | Cross-DB saga primitive — content-hash idempotency via `:cross-tx/step-id` | `drain!`, `register-router!`, `CrossTxRouter` | ADR-074 | shipped | 9 / 27 |
| `kontor.schedule` | Recurring-posting schedule helpers | `next-occurrence`, `materialize-due!` | ADR-032 | shipped | 14 / 36 |
| `kontor.event-bus` | In-process pub-sub on commit — McComb-style consumer seam | `register-handler!`, `commit-and-emit`, `dispatch` | ADR-092 | shipped | 9 / 27 |
| `kontor.explain` | Graph-walk helpers — `explain-balance` / `explain-posting` | `explain-balance`, `explain-posting`, `entities-with-concept-iri` | ADR-091 | shipped | 10 / 36 |
| `kontor.agent-tools` | Server-agnostic tool catalog over read + write surface | `read-tools`, `write-tools`, `with-tools` | (note 94 §3.2) | shipped | 9 / 39 |

### §1.4 — Money flow + master data

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.payment-application` | Partial-payment primitive (AR/AP) | `apply-payment!`, `apply-payment-tx-data`, `reverse-application!`, `allocate-fifo!` | ADR-043 | shipped | 8 / 33 |
| `kontor.reconciliation` | Bank-line → tx scaffolding, open-receivables-by-tx | `ingest-bank-lines!`, `open-receivables-by-tx`, `match-line!` | (kernel-internal) | shipped | 10 / 29 |
| `kontor.bank-account` | `:bank-account` master-data helpers | `by-code`, `bank-account-tx-data` | ADR-039 | shipped | (covered by reconciliation) |
| `kontor.bank-csv` | Generic CSV importer with per-bank configs | `parse-csv`, `register-bank-config!` | (kernel-internal) | shipped | (covered by bank-de/at/…) |
| `kontor.payment-term` | Due-date + discount-deadline computation | `compute-due-date`, `compute-discount-deadline` | (kernel-internal) | shipped | 6 / 13 |
| `kontor.aging` | AR/AP aging buckets (not-yet-due / 0-30 / 31-60 / 61-90 / 90+) | `aging-report` | (kernel-internal) | shipped | 5 / 24 |
| `kontor.valuation` | `:valuation-book` + `:valuation-layer` + consumption + adjustments | `receive-layer-tx-data`, `consume-layers-tx-data`, `book-balance` | ADR-027 / ADR-028 | shipped | 18 / 41 |
| `kontor.costing-provider` | `CostingProvider` protocol + FIFO/LIFO/WeightedAvg/StandardCost impls | `plan-receipt`, `plan-consumption` (protocol fns) | ADR-029 | shipped | 13 / 30 |

### §1.5 — FX + consolidation

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.fx-rate-provider` | `FxRateProvider` protocol + StaticTable / ECB / Chained impls | `resolve-rate`, `resolve-period-rates`, `provider-id`, `make-static-table-provider`, `make-ecb-provider`, `make-chained-provider` | ADR-072 | shipped | (covered by fx + fx_wiring) |
| `kontor.fx` | Money-level convert / translate / to-functional-currency | `convert`, `translate-money-seq`, `translate-amounts-by-commodity`, `to-functional-currency` | ADR-072 | shipped | 29 / 47 (fx) + 5 / 13 (fx_wiring) |
| `kontor.consolidation` | Multi-entity consolidation — translate + eliminate + consolidate! | `translate-trial-balance-tx-data`, `eliminate-intercompany-pair-tx-data`, `consolidate-tx-data`, `consolidate!` | ADR-073 | shipped | 10 / 30 |

### §1.6 — Provider seams (covered in §3)

| Namespace | Purpose | ADR | Status | Tests |
|---|---|---|---|---|
| `kontor.tax-rate-provider` | `TaxRateProvider` + `TaxFacts` + `StaticTableProvider` (transaction-line tax) | ADR-071 | shipped | 13 / 51 |
| `kontor.tax-posting-builder` | `TaxPostingBuilder` + `StaticTablePostingBuilder` | ADR-071 | shipped | (with tax-rate-provider) |
| `kontor.period-tax-provider` | `PeriodTaxProvider` + `TaxReturnFacts` (period/entity-incident tax) | ADR-099 | shipped | 19 / 64 |
| `kontor.tax-return-posting-builder` | `TaxReturnPostingBuilder` — provision / payment | ADR-099 | shipped | (with period-tax-provider) |
| `kontor.einvoice-provider` | `EInvoiceProvider` + `PureXmlProvider` | ADR-017 | shipped | (covered by einvoice-de + l10n-* clearance) |
| `kontor.payroll-provider` | `PayrollComputeProvider` + `PayrollPostingBuilder` + `PayrollEmitProvider` | ADR-075 | shipped | (covered by payroll-* tests) |
| `kontor.disposal-source` | `DisposalSource` — CGT providers read disposal events through this | ADR-103 | shipped | (covered by 11 l10n-* cgt suites) |

### §1.7 — Tax substrate (depth in note 168, listed here for completeness)

| Namespace | Purpose | ADR | Status | Tests |
|---|---|---|---|---|
| `kontor.statute` | `:tax-concept` / `:provision` / `:regime` / `:parameter` substrate + `apply-provisions` | ADR-101 (+ Addenda 1/2) | shipped | 41 / 109 |
| `kontor.tax-schedule` | flat / bracket / capped / formula / elect / sum + `apply-base-transform` + `apply-adjustments` | ADR-099 | shipped | (with period-tax) |
| `kontor.cgt` | CGT → PIT/CIT composition helper (`fold-into-base-transform`) | ADR-103 Add. 1 | shipped | 9 / 20 + 4 / 22 (cgt_pit_integration) |
| `kontor.standalone-payroll-tax` | Generic `PeriodTaxProvider` for flat/capped employer payroll levies | ADR-099 | shipped | 3 / 8 |
| `kontor.corporate-income-tax` | Generic flat-rate CIT `PeriodTaxProvider` (US/AU/CN/MX/AT-bound) | ADR-099 | shipped | 5 / 13 |
| `kontor.personal-income-tax` | Generic schedule(income − deductions) − credits + surtaxes PIT provider | ADR-099 | shipped | 4 / 13 |
| `kontor.sole-proprietor` | Sole-prop rung — `business-net` + `business-income-input` | ADR-100 | shipped | 3 / 13 |
| `kontor.vat-return` | Periodic VAT/GST return — `compute-vat-return` + remittance | ADR-100 | shipped | (covered by l10n-* uva/ustva/gst-hst) |
| `kontor.incorporation` | Phase-3 keystone — individual → corporation (Newco + opening + share issuance) | ADR-103 (note 107) | shipped | 4 / 16 |

### §1.8 — I/O + reporting

| Namespace | Purpose | Top public defns | ADR | Status | Tests |
|---|---|---|---|---|---|
| `kontor.report` | Declarative engine — `marginalize` σ_E + `:account-codes` / `:tax-tags` / `:dimension` engines | `compute-report`, `marginalize`, `report-postings`, `sum-postings` | ADR-096 | shipped | 12 / 23 |
| `kontor.financial-statements` | Generic P&L + BS builders atop `report.clj` | `pnl-tx-data`, `balance-sheet-tx-data`, `compute-pnl`, `compute-balance-sheet` | (kernel-internal) | shipped | (covered by 06 showcase + l10n-de eur) |
| `kontor.import-.beancount` | Beancount round-trip — Phase-1 acceptance test | `parse-beancount`, `transact-beancount!`, `dump-beancount` | ADR-009 | shipped | 4 / 17 |
| `kontor.document.invoice` | Kernel-side invoice-as-document (lifecycle); GL workflow in `modules/invoice/` | `create-invoice-tx-data`, `send!`, `mark-paid!`, `cancel!` | ADR-036 (kernel split) | shipped | 7 / 21 |

### §1.9 — Counts

- **57 kernel namespaces** (55 top-level + 2 sub-namespaces).
- Roughly **530 deftest / 1655 is-assertions** across the kernel (`test/kontor/*_test.clj`), before counting any companion or l10n. The five biggest kernel test suites: `statute` (41/109), `money` (38/71), `fx` (29/47), `posting` (28/75), `period` (21/25).
- **3 namespaces named in `doc/architecture.md` + `CLAUDE.md` but absent on disk**: `kontor.audit` (the commit-hash wrapper from ADR-003 — has not been implemented; relies on upstream datahike `:crypto-hash? true`), `kontor.query` (the bitemporal convenience helpers — folded into `kontor.bitemporal` and the per-concern namespaces), `kontor.tax` (the `apply-tax` single-line transformer — superseded by ADR-071's `TaxRateProvider` + `TaxPostingBuilder` trio). Doc-vs-code drift; see §6 P2.

---

## §2. Companion modules (non-l10n / non-payroll)

Per `tests.edn` and `deps.edn`, kontor wires **17 non-tax-domain
companions** + **5 bank-import companions** + the per-jurisdiction
l10n / payroll modules covered in note 168.

The kernel-adjacent companion suite (the 17) is the surface a
consumer wires into a typical app — partner / sales / invoice /
procurement / collections / asset / inventory / expense / lease /
authz / hr / commitment / disposal / import-gleif / import-edgar /
people-record / treaty-de-ca. The bank-importers are surface for
month-end reconciliation; the einvoice-de is the only e-invoice
emitter shipped to date.

### §2.1 — Kernel-adjacent companions

| Companion | One-line purpose | Src files | deftest / is | Inbound deps |
|---|---|---|---|---|
| `kontor-partner` | Party-as-root with polymorphic contact mechanisms; partner / contact / address / id-doc | 2 | 42 / 141 | every collections + l10n-* invoicing path; kernel `kontor.dsar` registers `:partner` attrs |
| `kontor-sales` | Order header + items + ship-groups + adjustments + roles | 2 | 16 / 48 | l10n-de/at/fr/ca/us/jp/au/cn/in/br/mx/uk invoice helpers (chain `kontor-sales` → `kontor-invoice`) |
| `kontor-invoice` | Order → invoice bridge, status machine, AcctgTrans posting | 3 | 19 / 52 | every l10n-* invoice helper; uses `kontor.book` + `kontor.process` |
| `kontor-procurement` | Requisition + receipt + 3-way match + drop-ship + RTV | 7 | 43 / 150 | `kontor-inventory` (issue / receive flows), `kontor-collections` for vendor-side dispute hooks |
| `kontor-collections` | AR collections — case, dunning, dispute, promise, write-off, credit-hold, pause | 10 | 38 / 141 | reads `kontor.aging` + writes through `kontor.book`; `DunningTemplateProvider` (own protocol) |
| `kontor-asset` | `:asset` register + lifecycle + depreciation books + runner | 7 | 40 / 164 | `kontor-lease` (ROU asset reuse); `DepreciationProvider` (own protocol) |
| `kontor-inventory` | Facilities + physical stock ledger + ATP + receive/issue/transfer + FEFO + cycle counts | 7 | 26 / 110 | `kontor-procurement` (receipt → issue), `kontor-sales` (order reservation); ships `FefoCostingProvider` |
| `kontor-expense` | Employee expense reports — minimal | 2 | 9 / 26 | none outside its own tests; documented "minimal" per ADR-061 |
| `kontor-lease` | IFRS 16 / ASC 842 lessee-side lease accounting + ROU + liability + modifications + FX | 8 | 28 / 153 | `kontor-asset` (ROU = `:asset`); ships `LeaseProvider`, `RouProvider` |
| `kontor-authz` | ReBAC (relationship-based access control) — ported from EACL | 8 | 25 / 99 | none in kontor itself (consumer choice); ships `IAuthorization` protocol |
| `kontor-hr` | `:person` / `:employment` / `:compensation` + pay-period + payroll runner + consent + DSAR contributions | 10 | 18 / 79 | every `payroll-*` adapter; `kontor.dsar/register-partner-attr!` extension |
| `kontor-commitment` | Recognising + liquidating obligations (`:commitment` + `:commitment-fulfillment`) — ADR-098 | 2 | 6 / 23 | **none outside its own tests** (P0 — see §6) |
| `kontor-disposal` | Ownership-change events for CGT (record / recognize / void) — ADR-102 | 3 | 19 / 48 | every l10n-* `cgt_provider` (via `DisposalSource`) |
| `kontor-import-gleif` | GLEIF LEI bulk-import → `:partner` + `:partner-tag` | 1 | 5 / 33 | showcase 05 + showcase 06 + `christian_scenario_test` |
| `kontor-import-edgar` | SEC EDGAR 10-K/10-Q ingest → bitemporal restatement substrate | 2 | 5 / 30 | showcase 05 (Apple 10-K) |
| `kontor-people-record` | Minimal people-tracking record — privacy-aware (ADR-094) | 2 | 1 / 15 | showcase 06 + `kontor-hr` consent surface |
| `kontor-treaty-de-ca` | DE↔CA double-taxation treaty helpers (dividend / interest / royalty) | 1 | 4 / 15 | `christian_scenario_test` only |

### §2.2 — Bank-statement importers (separate concern)

5 importers wired into deps + tests: `bank-de` / `bank-at` / `bank-fr` / `bank-ca` / `bank-us`. Each ships a few `bank-configs` for the country's biggest retail banks and a `categorize` step that turns a parsed CSV row into a `:bank-line` ready for `kontor.reconciliation`. The kernel does the parsing (`kontor.bank-csv`); the per-country module supplies the format table + the categorizer. Pattern is uniform across 5 countries; not surveyed in depth here.

### §2.3 — E-invoice (only one shipped)

`einvoice-de` ships **Factur-X** / **XRechnung** / **ZUGFeRD** emit, wrapping the Mustang library (APL-2, brought in as the heavy dep in `deps.edn`). 10 deftests / 42 assertions. No other country has an e-invoice emitter companion as of 2026-05-25 — `EInvoiceProvider` impls live inside the corresponding `l10n-<cc>` modules (BR NF-e, IN IRN, CN fapiao, etc.) rather than in dedicated `einvoice-*` companions, which is a naming-vs-physics seam tracked separately by note 95.

### §2.4 — Module test density (companion-only, excluding kernel + l10n / payroll)

- **Deep & tested** (≥25 deftest): partner (42), procurement (43), asset (40), collections (38), lease (28), inventory (26), authz (25).
- **Shipped & functional** (10-24 deftest): invoice (19), disposal (19), hr (18), sales (16).
- **Thin but useful** (5-9 deftest): expense (9), commitment (6), import-gleif (5), import-edgar (5), treaty-de-ca (4).
- **Stub-shaped** (≤3 deftest): people-record (1 — a single three-year-employee-lifecycle test).

Total companion test density: **~272 deftest / ~1029 is**.

---

## §3. Provider protocols (the integration seams)

The kernel exposes **8 protocols** (one trio + five singletons). The
"shape" of the substrate is most visible here: every integration
point a consumer needs is a protocol, the kernel ships a
StaticTable-flavoured default, and per-country code is a Record that
satisfies it.

| Protocol | Module | ADR | Key fns | Default impl(s) | Per-jurisdiction adopters |
|---|---|---|---|---|---|
| `TaxRateProvider` | `kontor.tax-rate-provider` | ADR-071 | `provider-id`, `rate-facts` | `StaticTableProvider`, `ChainedProvider`, `AvalaraProvider`/`TaxJarProvider`/`SstCsvProvider` (throwing scaffolds) | every `l10n-<cc>` has a `tax_provider.clj` |
| `TaxPostingBuilder` | `kontor.tax-posting-builder` | ADR-071 | `builder-id`, `tax-postings` | `StaticTablePostingBuilder` | the same l10n providers |
| `PeriodTaxProvider` | `kontor.period-tax-provider` | ADR-099 | `provider-id`, `period-tax-facts`, `period-tax-kind` | (no kernel default — l10n provides) | DE/FR/CA/JP CIT (statute-as-data); BR/IN/US/AT/AU/CN/MX (record-shape, Gap #5 pending) |
| `TaxReturnPostingBuilder` | `kontor.tax-return-posting-builder` | ADR-099 | `return-postings`, `payment-postings` | `BookEntryTaxReturnPostingBuilder` (the verb-facade default) | shared by every PeriodTaxProvider; FX-wired since T1.W1.1 (note 168 S1) |
| `FxRateProvider` | `kontor.fx-rate-provider` | ADR-072 | `resolve-rate`, `resolve-period-rates`, `provider-id` | `StaticTableProvider`, `EcbReferenceRatesProvider`, `ChainedProvider`; scaffolds for `XeProvider`/`OandaProvider`/`FedH10Provider` | none required — the kernel default + consumer feeds cover most cases |
| `CostingProvider` | `kontor.costing-provider` | ADR-029 | `plan-receipt`, `plan-consumption` | FIFO, LIFO, `WeightedAverage`, `StandardCost` | `FefoCostingProvider` in `kontor-inventory` (perishables / lots) |
| `EInvoiceProvider` | `kontor.einvoice-provider` | ADR-017 | `emit-artifact`, `transmit!`, `provider-id` | `PureXmlProvider` shape | `einvoice-de` (Factur-X / ZUGFeRD); BR / CN / IN per-country emit lives inside l10n-* not as `einvoice-*` companions (see §2.3) |
| `DisposalSource` | `kontor.disposal-source` | ADR-103 | `disposals-in-period`, `disposals-of` | `DatahikeDisposalSource` in `kontor-disposal` | every of the 11 l10n CGT providers reads through this |
| `PayrollComputeProvider` | `kontor.payroll-provider` | ADR-075 | `compute-pay-period` | (no kernel default — each adapter ships its engine-CSV bridge) | DE/US/CA/FR/AU/BR/MX/IN/JP/CN/AT |
| `PayrollPostingBuilder` | `kontor.payroll-provider` | ADR-075 | `build-payroll-postings` | `StandardPayrollPostingBuilder` | shared across 11 payroll adapters |
| `PayrollEmitProvider` | `kontor.payroll-provider` | ADR-075 | `emit-filing-artifact`, `transmit!` | none in kernel | per-country `*EmitProvider` (DSN, eSocial, STP P2, mBGM, T4, RL-1, ECR, …) |
| `DepreciationProvider` | `kontor.asset.depreciation-provider` (companion) | ADR-055 | `plan-depreciation`, `period-end-rollforward` | straight-line, declining-balance, units-of-production | none required for v1 |
| `LeaseProvider` | `kontor.lease.lease-provider` (companion) | ADR-063 | `classify`, `plan-period-postings` | operating-lease + finance-lease defaults | none required for v1 |
| `IAuthorization` | `kontor.authz.core` (companion) | ADR-065 | `authorized?`, `relationships-of`, `principals-of` | EACL-derived ReBAC engine | none required for v1 |

Plus **two non-kernel, jurisdiction-internal protocols** worth noting (each lives inside one `l10n-<cc>` module): `MvaProvider` (BR — multi-VAT-attribute helper), `NoaParser` (CA — Notice of Assessment parser); plus the payroll-adapter-internal `MxEngineProvider`/`MxCfdiEmitter`/`AtEngineProvider`/`AtFilingEmitProvider`/`DunningTemplateProvider` (`kontor-collections`).

The protocol surface is **stable** — the only churn in the last six months was ADR-005 → ADR-071 (`TaxProvider` → `TaxRateProvider` trio); since then, every new substrate has been a *new* protocol (`PeriodTaxProvider`, `DisposalSource`, `PayrollProvider` trio) rather than a re-shape of an existing one.

---

## §4. Composition primitives — how it chains

The substrate's "feel" comes from a handful of cross-cutting
primitives a consumer reaches for. Each shown with its canonical
3-5-line shape.

### `kontor.process/run-process` — multi-step orchestrator

```clojure
(process/run-process
 conn
 [(fn [{:keys [db]}] {:tx-data (commence-tx-data db {:lease lease-eid …})})
  (fn [{:keys [db tx-data-acc]}] {:tx-data (initial-rou-tx-data db lease-eid)})
  (fn [{:keys [db tx-data-acc]}] {:tx-data (initial-liability-tx-data db lease-eid)})]
 {:as-of-valid effective-date})
;; ⇒ one atomic commit through transact-with-validation
```

### `*-tx-data` builder convention — ADR-068

```clojure
;; Every business write is a pair:
(defn record-status-change-tx-data [db {:keys [entity facet to reason changed-by-uid]}] …)  ; pure
(defn record-status-change!        [conn args] (d/transact! conn (record-status-change-tx-data (d/db conn) args)))
;; ⇒ pure builder can be composed inside `kontor.process` or `kontor.book/entry-tx-data`;
;;   the `!` wrapper is the convenient default that goes through the validation gate.
```

### `kontor.book` verbs — ADR-095

```clojure
(book/sell! conn
            {:partner [:partner/external-id "ACME-001"]
             :amount 1000.00M :commodity :EUR
             :revenue-account [:account/code "8400"]
             :receivable-account [:account/code "1400"]
             :date #inst "2026-05-25"
             :journal [:journal/code "SALES"]})
;; ⇒ one balanced, sealed, status-stamped transaction with one fn call.
```

### `kontor.report/marginalize` — σ_E partition+sum (ADR-096)

```clojure
(report/marginalize conn
                    {:dimension :posting/account
                     :postings  (report/report-postings db {:as-of-valid …})
                     :group-by  (fn [posting] (:account/code (:posting/account posting)))})
;; ⇒ {<group-key> Money} — the kernel of every report; pure data in / pure data out.
```

### `:posting/dimension` — classification axes (ADR-097)

```clojure
;; Define a cost-centre dimension
[{:posting-dimension/key  :cost-centre
  :posting-dimension/name "Cost centre"}
 {:posting-dimension/key  :project
  :posting-dimension/name "Project"}]
;; Attach to postings
[{:posting/account [:account/code "6400"]
  :posting/amount  -250.00M
  :posting/commodity :EUR
  :posting/dimensions [{:posting-dimension/key :cost-centre :value "R&D"}
                        {:posting-dimension/key :project      :value "kontor-v1"}]}]
;; marginalize then pivots over either axis identically.
```

### Status machines + approval policies — ADR-034 + ADR-038

```clojure
(status-machine/record-status-change!
 conn {:entity invoice-eid
       :facet :invoice/status
       :to    :sent
       :reason :invoice-issued
       :reason-note "Customer onboarding — first invoice"
       :supporting-doc audit-doc-eid     ; ← required by :approval-policy
       :changed-by-uid user-uid})
;; ⇒ validates :status-transition; writes :status-history; enforces :no-self-approval etc.
```

### `kontor.side-effect.cross` — cross-DB saga (ADR-074)

```clojure
(side-effect.cross/drain!
 conn router         ; CrossTxRouter — consumer's system-id → conn map
 {:batch-size 100})
;; ⇒ for each :cross-tx-post intent: deterministic step-id, idempotent target commit,
;;   crash-safe (worker dies after target-commit but before mark-done? next pass mark-dones).
```

### `kontor.consolidation/consolidate!` — financial consolidation (ADR-073)

```clojure
(consolidation/consolidate!
 conn {:entity-family (entity/family db parent-eid)
       :consolidation-entity [:entity/code "GROUP"]
       :elimination-entity   [:entity/code "ELIM"]
       :presentation-commodity :EUR
       :fx-provider ecb-provider
       :as-of-tx now
       :as-of-valid #inst "2026-12-31"})
;; ⇒ translate-trial-balance per operating entity + eliminate per :transaction/intercompany-pair-id.
```

### `kontor.fx` — Money-level translation

```clojure
(fx/to-functional-currency conn
                            {:money (money/make 1000M :CHF)
                             :functional :EUR
                             :at-date #inst "2026-05-25"
                             :rate-type :spot
                             :fx-provider ecb-provider})
;; ⇒ Money 920.50M :EUR (precision 2 unless overridden)
```

### Bitemporal — `:tx/valid-from` + `:as-of-valid` (ADR-008 / ADR-048)

```clojure
(bitemporal/with-vt tx-data effective-date)   ; write-side
(trial/trial-balance conn {:as-of-valid #inst "2024-12-31"
                            :as-of-tx    #inst "2025-03-31"})   ; read-side
```

### Sealing — `:posting/posted-at` + middleware (ADR-007)

```clojure
;; All writes flow through the kernel transact gate:
(d/transact! conn (sealing/transact-with-sealing tx-data (d/db conn)))
;; ⇒ silent retract of a posted entity throws ex-info with :remediation "Use [:db/purge ...] explicitly"
```

These 10 primitives are what makes kontor "feel like one substrate" rather than a dozen vendored libraries.

---

## §5. Coverage scorecard

Same legend as note 168 §1: `[shipped]` = on-path, tested, with consumers; `[partial]` = shipped + tested but limited consumer surface; `[stub]` = file present, minimal tests; `[planned]` = ADR exists, no implementation yet; `[missing]` = not modelled.

### §5.1 — Kernel modules

| Concern | Status | Test density | Consumer surface |
|---|---|---|---|
| Posting / balance / trial / closing / period | `[shipped]` | high (28+9+7+5+21 deftest) | every business write |
| Money + commodity arithmetic | `[shipped]` | high (38 deftest) | every Money-touching path |
| Bitemporal substrate | `[shipped]` | high (10 deftest, plus implicit in every balance / trial test) | every read |
| Sealing + audit-doc + status-machine + approval-policy | `[shipped]` | high (7+7+14+plus inline) | every state-change path |
| Legal-hold + retention + DSAR + privilege | `[shipped]` | high (14+13+9+7 deftest) | GDPR / SOX / FRCP responses |
| `kontor.process` + ADR-068 builders | `[shipped]` | medium (11 deftest) | every companion's orchestrator |
| `kontor.book` verbs | `[shipped]` | medium (6 deftest, 31 is) | the new on-ramp; consumed by `christian_scenario_test` |
| `kontor.side-effect` + `.cross` | `[shipped]` | medium (3+9 deftest) | intercompany / cross-DB |
| `kontor.event-bus` | `[shipped]` | medium (9 deftest) | McComb consumers |
| `kontor.explain` | `[shipped]` | medium (10 deftest) | "explain this number" UI consumers |
| `kontor.agent-tools` | `[shipped]` | medium (9 deftest) | MCP / agent consumers |
| FX (`fx-rate-provider` + `fx`) | `[shipped]` | high (29+5 deftest) | translation + consolidation + tax (post T1.W1.1) |
| Consolidation | `[shipped]` | medium (10 deftest) | multi-entity reporters |
| Reporting (`report` + `financial-statements`) | `[shipped]` | medium (12 deftest) | every BS / P&L / UStVA consumer |
| Payment-application / aging / reconciliation | `[shipped]` | medium (8+5+10 deftest) | every AR / AP path |
| Valuation books / costing-provider | `[shipped]` | high (18+13 deftest) | every inventory move |
| Beancount round-trip | `[shipped]` | low (4 deftest) | acceptance gate; rarely a consumer concern |
| Schedule (recurring postings) | `[shipped]` | medium (14 deftest) | subscription / SaaS recurring billing |

### §5.2 — Companion modules (non-tax-domain)

| Companion | Status | Tests | First-class consumer? |
|---|---|---|---|
| `kontor-partner` | `[shipped]` | high (42/141) | every l10n-* invoice |
| `kontor-sales` | `[shipped]` | medium (16/48) | every l10n-* invoice |
| `kontor-invoice` | `[shipped]` | medium (19/52) | every l10n-* invoice |
| `kontor-procurement` | `[shipped]` | high (43/150) | inventory, vendor flows |
| `kontor-collections` | `[shipped]` | high (38/141) | showcase 01 (DE Mahnverfahren), 02 (US Reg-F) |
| `kontor-asset` | `[shipped]` | high (40/164) | `kontor-lease` (ROU), every depreciation flow |
| `kontor-inventory` | `[shipped]` | high (26/110) | `kontor-procurement` + `kontor-sales` flows |
| `kontor-lease` | `[shipped]` | high (28/153) | showcase 06 (DE GmbH multi-year) |
| `kontor-authz` | `[shipped]` | medium (25/99) | no in-tree consumer (consumer choice) |
| `kontor-hr` | `[shipped]` | medium (18/79) | every `payroll-*` adapter |
| `kontor-disposal` | `[shipped]` | medium (19/48) | every l10n-* CGT provider (11) |
| `kontor-commitment` | `[partial]` | low (6/23) | **none outside its own test ns** — P0 §6 |
| `kontor-expense` | `[partial]` | low (9/26) | none outside its own tests |
| `kontor-import-gleif` | `[partial]` | low (5/33) | showcases 05/06 + `christian_scenario_test` |
| `kontor-import-edgar` | `[partial]` | low (5/30) | showcase 05 |
| `kontor-people-record` | `[stub]` | very-low (1/15) | showcase 06; `kontor-hr` consent surface |
| `kontor-treaty-de-ca` | `[partial]` | low (4/15) | `christian_scenario_test` only |
| `einvoice-de` | `[shipped]` | low-medium (10/42) | showcase 01 |
| `bank-{de,at,fr,ca,us}` | `[shipped]` (5 of them) | not surveyed in this note | every bank-recon path |

### §5.3 — Provider protocols

| Protocol | Default impl | Per-jurisdiction adoption | Status |
|---|---|---|---|
| `TaxRateProvider` + `TaxPostingBuilder` | StaticTable + scaffolds | 11 / 11 jurisdictions | `[shipped]` |
| `PeriodTaxProvider` + `TaxReturnPostingBuilder` | none (kernel-thin) | 6 statute-as-data + 5 record-shape (Gap #5) | `[shipped]` |
| `FxRateProvider` | StaticTable + ECB + Chained | consumer choice | `[shipped]` |
| `CostingProvider` | FIFO/LIFO/WAvg/Std | FEFO in `kontor-inventory` | `[shipped]` |
| `EInvoiceProvider` | PureXmlProvider shape | DE/BR/IN/CN (+ scaffolds) | `[partial]` (only DE is a dedicated companion; rest inside l10n) |
| `DisposalSource` | DatahikeDisposalSource (in companion) | 11 / 11 CGT providers | `[shipped]` |
| `PayrollProvider` trio | StandardPostingBuilder + no compute/emit default | 11 / 11 jurisdictions | `[shipped]` |
| `DepreciationProvider` | SL / DB / UoP (in `kontor-asset`) | n/a | `[shipped]` |
| `LeaseProvider` + `RouProvider` | operating + finance defaults (in `kontor-lease`) | n/a | `[shipped]` |
| `IAuthorization` | EACL-derived ReBAC (in `kontor-authz`) | n/a | `[shipped]` |

### §5.4 — Cross-cutting integration

| Surface | Status | Evidence |
|---|---|---|
| Bitemporal (write + read + Allen interval predicates) | `[shipped]` | every read takes `:as-of-tx` / `:as-of-valid`; upstream datahike `:db.valid/from` + 8 Allen interval rules |
| Sealing + middleware | `[shipped]` | every `kontor.posting/post-transaction!` flows through `transact-with-sealing` |
| Status machine + approval policy + audit-doc | `[shipped]` | every state-change in every entity-bearing companion (`:invoice/status` / `:dispute/state` / `:disposal/state` / `:asset/state` / `:lease/state` / …) |
| Verb facade + ADR-068 builders | `[shipped]` | every business write has both a `*-tx-data` and a `!` form; book verbs cover 8 most-common shapes |
| `kontor.process` orchestration | `[shipped]` | every multi-tx companion orchestrator (`commence!`, `run-depreciation!`, `run-lease!`, `close-fiscal-year!`, `allocate-fifo!`) uses it |
| `kontor.report/marginalize` | `[shipped]` | every report engine collapses to a `marginalize` call |
| Consolidation primitive | `[shipped]` | translate + eliminate + consolidate! — showcase 04 (multi-entity intercompany) |
| Cross-DB saga | `[shipped]` | `kontor.side-effect.cross` + step-id idempotency |
| Single one-call `install-all!` for the kernel + chosen companions | `[missing]` | each `install!` is hand-wired; only `l10n-de/preset` + `l10n-ca/preset` exist (note 168 S2 Phase B); a kernel-side `install-all-companions!` does not exist — P0 §6 |
| Consumer-facing quickstart | `[partial]` | `doc/programming.md` (739L) + `doc/value.md` (585L) read as substrate-design notes, not as a 15-minute quickstart — P1 §6 |

---

## §6. Strengths and gaps

### §6.1 — Deeply complete (publish-ready)

These are the substrate axes where kontor *materially* differentiates from any other open-source double-entry kernel, with the test count + multi-consumer evidence to back the claim:

1. **Bitemporality on every read.** Every `balance` / `trial` / `ledger` / `report` / `payment-application` / `aging` / `reconciliation` / `consolidation` / `vat-return` / period-tax-provider query takes `:as-of-tx` and `:as-of-valid`. Shipped via `:tx/valid-from` + upstream datahike `:db.valid/from`. Evidence: every kernel test fixture exercises both axes; showcase 05 (Apple 10-K bitemporal restatement) and showcase 06 (DE GmbH bitemporal correction) are end-to-end stories.
2. **Status-machine-as-audit-trail.** No separate audit log. Every state change is a `:status-history` row with `:reason` / `:reason-note` / `:supporting-doc` / `:changed-by-uid` / `:origin-transaction` / `:changed-at`. `:approval-policy` enforces `:no-self-approval` / `:requires-supporting-doc` / `:requires-non-empty-reason-note`. Evidence: 14 / 38 (status_machine), 7 / 13 (audit-doc privilege), every companion uses the same primitive.
3. **Sealing + purge-is-a-recorded-commit.** Posted entries can't be silently retracted (`assert-no-silent-retracts!` throws); `:db/purge` is allowed but becomes its own commit in the audit chain. This is the cleanest answer in OSS to "GDPR right-to-erasure vs audit trail" — 14 / 27 (legal-hold), 13 / 51 (retention), 9 / 38 (dsar).
4. **The verb facade + ADR-068 builder convention.** Every business write is a pure `*-tx-data` builder + `!` wrapper that flows through the validation gate. `kontor.book` ships 8 verbs (`receive`/`pay`/`sell`/`buy`/`receive-payment`/`pay-bill`/`transfer`/`adjust`). The pattern is **consistent across kernel + all 17 companions** — surveyed by the API-consistency audit (note 160 §3 line items).
5. **Statute-as-data substrate.** `:tax-concept` / `:provision` / `:regime` / `:parameter` with `kontor.statute/apply-provisions` priority-folding + closed `:op` vocab (`:credit` / `:surtax` / `:base-add` / `:base-deduct` / `:schedule-override`). 41 / 109 statute tests, 6 / 11 jurisdictions on the statute path; the rest tracked by Gap #5.
6. **Multi-entity consolidation primitive.** `translate-trial-balance-tx-data` (IAS 21 / ASC 830 rate-types) + `eliminate-intercompany-pair-tx-data` (deterministic via `:transaction/intercompany-pair-id`) + `consolidate!` orchestrator using `kontor.process`. 10 / 30 tests + showcase 04. CTA plug, FX-aware.
7. **FX on the Money-level.** `convert` / `translate-money-seq` / `to-functional-currency`; `kontor.report/compute-report` grew a `:translate-to` option; `kontor.lease.posting/plan-fx-retranslation` is an IAS 21 retranslation primitive. As of T1.W1.1, the tax-emission path is also FX-wired (`monocommodity-facts?`, `translate-amounts-to-functional`).
8. **The 8-protocol seam map.** Every integration point is a protocol with a sensible default impl. `TaxRateProvider`, `PeriodTaxProvider`, `FxRateProvider`, `CostingProvider`, `EInvoiceProvider`, `DisposalSource`, `PayrollProvider` trio. Customers plug; the kernel doesn't depend on Avalara/TaxJar/ECB API keys.

### §6.2 — Shipped but shallow

These are real, but a consumer building on them today will hit a wall faster than they will on §6.1 items:

1. **`kontor-commitment`** — ADR-098 + 2 src files + 6 deftest / 23 is, no consumer outside its own tests. Schema: `:commitment` + `:commitment-fulfillment` edge. The substrate framing (recognising + liquidating obligations) is correct, but the *integration story* (which transactions liquidate which commitments at posting time) is implicit. **P0 for v1** — either pull it into a showcase or document its consumer-side wiring explicitly.
2. **`kontor-expense`** — 2 src files + 9 deftest / 26 is. Expense reports → reimbursement postings. The per-diem feature was trimmed (task #272). Functional, but no l10n-* expense-rule integration (e.g. DE Bundesreisekostengesetz tables). **P1** — fine for a one-person consultancy; not credible for an SME.
3. **`kontor-people-record`** — 2 src files + 1 deftest / 15 is. A single three-year-employee-lifecycle test. Designed minimal per ADR-094 (anti-Foundry framing); the 1-deftest count is honest, not concerning, but a v1 consumer will discover bugs first. **P1**.
4. **`kontor-treaty-de-ca`** — 1 src file + 4 deftest / 15 is. The only treaty companion. Demonstrates the pattern (per-pair helpers for cross-border dividend / interest / royalty). A v1 consumer who needs DE-US or DE-UK or CA-US treaties will write their own. **P1** for the maintainer's setup; design pattern is sound.
5. **`kontor-import-{gleif,edgar}`** — 5 deftest each; gleif consumed by showcases 05+06 + `christian_scenario_test`; edgar by showcase 05 only. Functional for the showcases shown; a v1 consumer who needs a different source (Companies House, SEDAR, EUR-Lex) has nothing to copy. **P1**.
6. **`kontor.incorporation`** — ADR-103 / note 107 keystone, 4 deftest / 16 is. The single-fn `incorporate!` ships the individual → corporation continuum's most important transition. Works in `christian_scenario_test`; v1-publishable but test density is low for the substrate-weight of what it does. **P2**.
7. **`kontor.event-bus`** — ADR-092, in-process pub-sub, 9 deftest / 27 is. No in-tree consumer (the McComb consumer-side seam is by design); a v1 consumer building a reactive UI on top is expected to be the first user. **P2**.
8. **`kontor.agent-tools`** — 9 deftest / 39 is. Server-agnostic tool catalog (note 94 §3.2). No in-tree consumer (the `kontor-mcp` standalone server is task #260 deferred). **P2**.

### §6.3 — Missing for v1

These bite a v1 consumer the moment they wire kontor for any non-trivial setup:

1. **No kernel-side `install-all-companions!`** — every consumer wires `kontor.core/install-schema!` + each `kontor.<companion>.schema/install!` by hand. `christian_scenario_test` is the only example a consumer can copy; the showcases each wire their own subset. **P0** — surface a `kontor.preset/install-all!` (or similar) taking a `#{:kernel :partner :sales :invoice :procurement :collections :asset :inventory :lease :hr :disposal :commitment :expense :authz}` set; the l10n + payroll presets per-country already exist (note 168 S2).
2. **No 15-minute quickstart.** `doc/programming.md` is 739 lines of substrate-design rationale; `doc/value.md` is 585 lines of evaluator framing; `doc/architecture.md` is 513 lines of layer-cake. A new contributor lands in a research note. **P0** — a `doc/quickstart.md` with "10 lines + 1 image" should land before publish.
3. **No `kontor-mcp` standalone server.** Deferred per task #260. `kontor.agent-tools` is the substrate; without the MCP server, the agent-tools surface is a library-only feature. **P1** — the maintainer's own usage (real DE-UG + Vancouver sole-prop accounting) will likely want this within a quarter.
4. **No UK / no UK-VAT / no UK-RTI.** Deliberate per note 78 §3 (iXBRL gate). A v1 OSS release that ships 11 jurisdictions but has UK as `[missing]` everywhere is a discoverability gap, not a substrate gap. **P1** — document the deferral in the README so prospective UK users don't bounce.
5. **Three orphaned-by-rename namespaces in the docs.** `kontor.audit`, `kontor.query`, `kontor.tax` are named in `doc/architecture.md` + `CLAUDE.md` but the files do not exist; their roles are filled by upstream datahike `:crypto-hash? true` (audit), `kontor.bitemporal` + per-concern namespaces (query), and the `TaxRateProvider` trio (tax). **P2** — doc sweep.
6. **`einvoice-*` naming-vs-physics seam.** Only DE has a dedicated `einvoice-de` companion; BR / IN / CN ship their `EInvoiceProvider` impls inside `l10n-*`. Inconsistent. **P2** — either extract or document the convention.
7. **No `kontor-consolidation` companion.** The kernel `kontor.consolidation` primitive ships translate + eliminate + consolidate!; the *companion tier* (ownership %, minority interest, IFRS 10 control, IFRS 3 goodwill, IAS 27 step-acquisitions) is `[planned]` in `doc/architecture.md` but not present. **P2** for v1 if the maintainer's use cases don't need it; **P1** if "v1 publishable for SMEs" includes consolidating subsidiaries.
8. **`kontor-collections/dunning` template provider is in-house.** `DunningTemplateProvider` is a per-collections-module protocol with no kernel surface and no per-country defaults. A v1 SME consumer in DE will want a Mahnschreiben-shaped default. **P2**.

### §6.4 — Triage summary

- **P0 (4 items)**: install-all-companions; quickstart; commitment-without-consumer; partner / `:person/*` overlap audit (was marked resolved via ADR-094 but the overlap remains visible in the schema — note 159 §3 still flags this).
- **P1 (5 items)**: expense l10n-rule integration; people-record consumer; treaty companions (DE-US, DE-UK, CA-US); kontor-mcp; UK module deferral disclosure.
- **P2 (5 items)**: incorporation test density; event-bus consumer demo; orphaned-by-rename docs sweep; einvoice-* naming consistency; consolidation companion (subjective).

---

## §7. Comparable systems (one-paragraph reality check)

For *accounting* primitives (not tax), the open-source landscape splits three ways. **Ledger-CLI / Beancount** are text-file double-entry languages with strong reporting toolchains; kontor matches them on round-trip semantics (`import_/beancount` is a Phase-1 acceptance test) but adds bitemporal queries, status machines, and a real schema — they cannot answer "what did the books show on 2024-12-31 as known on 2025-03-31" without a manual fixture. **OFBiz / Tryton / Odoo (community editions)** are full ERPs with charts of accounts, tax engines, invoicing, procurement, and UIs; kontor is **smaller** than any of them by orders of magnitude (single-dep, no UI, no embedded BPMN, no built-in country rates) but **deeper** on three axes those projects don't have: bitemporality is structural rather than bolted-on, the status-machine-and-supporting-doc primitive is the audit log (no separate "history" table), and statute-as-data (`:provision` + `:parameter`) lets per-country tax rules be queried + diff'd as data rather than read out of Python / XML. **What kontor lacks vs the ERP cohort**: a UI (deliberate per ADR-010 — `beleg` and `simmis` consume), a batch / scheduler runtime (deliberate per ADR-067 — `kontor.process` is the substrate, the consumer wires their own scheduler — but the maintainer's `loop` skill suggests this gap will pinch), workflow / BPMN (per note 21 — kontor declines; consumers pick Camunda / Temporal), and per-country chart-of-account / chart-of-tax-rule preconfigurations for jurisdictions other than the 11 covered (the `[missing]` cells in note 168 §1.1). For a v1 publish, the credible positioning is **"the accounting kernel that gives you bitemporality, sealing, audit-doc, status machines, and statute-as-data — without an ERP wrapped around it"**.

---

## §8. Numbers for note 171

- **Kernel namespaces surveyed**: 57 (55 top-level + 2 sub).
- **Companions surveyed (non-l10n / non-payroll)**: 17 + 5 bank importers + 1 e-invoice = 23.
- **Protocols catalogued**: 8 kernel + 5 companion = 13.
- **Showcases**: 6 (plus the 7th deferred per task #265) + `christian_scenario_test` as the one cross-company integration test.
- **Kernel test density**: ~530 deftest / ~1655 is.
- **Companion test density (non-l10n / non-payroll)**: ~272 deftest / ~1029 is.
- **P0 / P1 / P2 findings**: 4 / 5 / 5 (§6.4).
- **Doc-vs-code drift**: 3 named-but-absent namespaces (§6.3.5).
- **One-sentence shape verdict**: *kontor is a small, deep, opinionated double-entry kernel with a credible substrate for bitemporal audit, status machines, statute-as-data, multi-entity consolidation, and the standard companion suite — but the consumer-onboarding surface (one-call install, a quickstart that doesn't read like a research note) is the v1 gap, not the substrate itself.*

---

*End of note 169.*

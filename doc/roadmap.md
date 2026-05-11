# Roadmap

The phases below are sized in **wallclock weeks at single-developer focus**. Mark off as we go.

For *why* the sequencing is what it is, see [decisions.md](decisions.md). For the *shape* of each layer, see [architecture.md](architecture.md).

---

## Phase 0 — Initialize (this commit)

- [x] Create `../kontor/` repository
- [x] LICENSE (EPL-1.0)
- [x] `deps.edn`, `bb.edn`, `tests.edn`
- [x] `CLAUDE.md` for AI-assisted iteration
- [x] `doc/decisions.md` recording all locked choices (ADR-001 .. ADR-010)
- [x] `doc/architecture.md` describing the layer cake
- [x] `doc/research/` with the three research reports + index
- [x] `src/kontor/schema.clj` skeleton with namespaced kernel attrs
- [x] `src/kontor/tax_provider.clj` protocol skeleton
- [x] `src/kontor/core.clj` entry point
- [x] `test/kontor/schema_test.clj` smoke test (schema loads)

## Phase 1 — Kernel + tax engine + bitemporal (4-5 weeks)

The smallest thing that is genuinely a working double-entry kernel with tax and bitemporal queries.

- [x] Schema: `account`, `journal`, `transaction`, `posting`, `commodity`, `lot`, `partner`, `period`, `balance-assertion`, `fiscal-position`, `tax`, `tax-repartition-line`, `tax-group`, `account-tag` *(Phase 0)*
- [x] `Money` type — BigDecimal + commodity tag + arithmetic + rounding (HALF-EVEN) *(ADR-013)*
- [x] `posting/build-transaction` — assembles + validates sum-to-zero, currency-correct *(structural; catalog-aware checks come with the invariant integration)*
- [x] Bump `datopia/invariant` to datahike 0.8.x (ADR-011) *(also added :db/retract dispatch and entity-map tx form support upstream)*
- [x] `validation.clj` middleware skeleton + first invariant (`account_active.edn`) — ADR-011
- [x] commodity-match invariant (`commodity_match.edn`) and tests
- [x] sealing.clj: refuse silent retract of `:posting/posted-at`-marked entities (ADR-007)
- [ ] period.clj: refuse postings whose `:posting/valid-from` falls inside a closed `:period/locked-at` period
- [ ] state-machine.clj: enforce :transaction/state lifecycle (draft → posted → cancelled)
- [ ] Schema additions for analytic accounts (ADR-012): `:analytic-plan/*`, `:analytic-account/*`, `:analytic-distribution/*`, `:posting/analytic-distributions`
- [ ] `tax/apply` — uses `TaxProvider` protocol to expand a base posting into base + tax postings
- [ ] `StaticTableProvider` reading EDN tax definitions
- [ ] `sealing/transact-with-sealing` — middleware refusing silent retract of `:posting/posted-at`-marked entities
- [ ] `balance/account-balance` and `ledger/postings-against` — bitemporal-aware (as-of-tx, as-of-valid)
- [ ] `trial/trial-balance` — date-range, bitemporal
- [ ] `period/open?`, `period/close!`, `period/lock-tx`
- [ ] `import/beancount` — parser + transactor + dumper; round-trip test against `examples/example.beancount`
- [ ] Test coverage: schema invariants, sum-to-zero, sealing, bitemporal queries, Beancount round-trip

**Acceptance**: a representative Beancount example file round-trips byte-clean (modulo whitespace), tax + sealing tests pass, trial balance computes correctly across a known fixture.

## Phase 1.5 — Declarative report engine (1-2 weeks)

- [ ] Schema: `report`, `report-line` (hierarchical), `report-expression`
- [ ] Engines: `:account-codes`, `:tax-tags`, `:aggregation`, `:domain` (Datalog-shaped)
- [ ] Compute: walk the report tree, evaluate expressions, materialize per-period values
- [ ] First test reports: P&L, Balance Sheet (against the fixture from Phase 1)

## Phase 2-DE — Germany (3-4 weeks)

Separate artifact: `kontor-l10n-de` (GPLv3, sourced from Tryton `account_de_skr03` + GnuCash community SKR04 + Odoo cross-check).

- [ ] `resources/skr03.edn` and `resources/skr04.edn` — chart of accounts as data
- [ ] `resources/vat-tags.edn` — VAT report tag definitions
- [ ] `resources/vat-report-2026.edn` — Umsatzsteuer-Voranmeldung shape
- [ ] `StaticTableProvider` config for DE (standard 19% + reduced 7% + zero + reverse charge + EU intra-community)
- [ ] `kontor-einvoice-de` (separate artifact, EPL-1.0): Mustang wrapper for Factur-X read/write/validate
- [ ] `kontor-export-gobd` (separate artifact): GoBD/GDPdU 14-file ASCII bundle generator
- [ ] `kontor-import-datev` (separate artifact): DATEV CSV import (the SMB exchange format with their accountant)
- [ ] CI: KoSIT validator gate on every Factur-X output

**Acceptance**: a generated Factur-X invoice passes KoSIT validation; SKR03/SKR04 charts load and pass schema validation; a small fixture invoice → posted journal entry → VAT report shows correct figures.

## Phase 3 — Beleg integration (1-2 weeks)

- [ ] Beleg schema augmentation: `:invoice/journal :invoice/transaction` references
- [ ] Posting bridge: `beleg.accounting/post-invoice!` writes both sides atomically in one tx
- [ ] Migration: backfill historical beleg invoices into journal entries
- [ ] Reverse path: voiding an issued invoice creates a reversing transaction (no in-place edit)

**Acceptance**: existing beleg DB migrates without data loss; new invoices issued through the UI produce balanced journal entries; Phase 2 reports include both new and migrated bookings.

## Phase 4-CA — Canada (3-5 weeks)

Separate artifact: `kontor-l10n-ca` (EPL-1.0 — CRA facts are public).

- [ ] `resources/ca-tax.edn` — GST + each HST province + each PST province + Quebec QST as `tax` + `tax-repartition-line` rows
- [ ] `resources/ca-coa.edn` — QBO-style baseline CoA, optional Tryton-derived alt
- [ ] `StaticTableProvider` config for CA: per-province routing, recoverable-vs-not flag
- [ ] `kontor-export-cra-t4` (separate artifact): T4 information return XML generator (CRA published schema)
- [ ] `kontor-export-cra-t5` (separate artifact): T5 information return XML generator
- [ ] `kontor-export-cra-gst` (separate artifact): GST/HST NETFILE-ready figures
- [ ] SR&ED project dimension on postings (analytical-account-style)
- [ ] CAMT.053 / OFX import covers Canadian banks (most major CA banks publish ISO 20022)

**Acceptance**: a fixture transaction in BC (5% GST + 7% PST split, both posted to correct accounts; PST as expense, GST as recoverable input tax credit); Quebec QST/GST split correct; CRA T4 XML validates against published schema.

## Phase 5-US — United States (4-6 weeks)

Separate artifact: `kontor-l10n-us` (EPL-1.0 kernel pieces; SST data file licensing per SST terms).

- [ ] `resources/us-coa.edn` — QBO-style default CoA
- [ ] `SstCsvProvider` — ingests quarterly SST rate + boundary CSVs for ~24 SST states
- [ ] `AvalaraProvider` adapter scaffold (no API key bundled; customer registers)
- [ ] `TaxJarProvider` adapter scaffold (same)
- [ ] 1099-NEC tracking: vendor flag + YTD payment aggregation by vendor
- [ ] `kontor-export-irs-iris` (separate artifact, when IRS TCC obtainable): IRIS XML generator for 1099 series
- [ ] Cash-basis reporting view (US SMBs default)
- [ ] State-by-state sales tax filing pre-fill (no transmission — generate values to enter)

**Acceptance**: a fixture sale in California (SST non-member) with `AvalaraProvider` mocked returns correct rate; same sale in Tennessee (SST associate) routes through `SstCsvProvider`; 1099-NEC YTD aggregation matches against a fixture vendor.

## Cross-cutting: Track B (parallel, 1-2 weeks)

Upstream PRs to `replikativ/datahike`:

- [ ] Replace per-tx `:hash` with SHA-256 over canonical-encoded sorted EAVT
- [ ] Stored in `:meta`, fed into `create-commit-id`
- [ ] Commit signature hook: caller-provided fn invoked at commit time
- [ ] Documentation: `:crypto-hash? true` is the recommended config for accounting/audit use
- [ ] Tests: round-trip the new hash chain through konserve, verify cross-machine reproducibility

Phase 1 ships before Track B lands. When Track B lands, `audit.clj` in this repo cuts over.

## Cross-cutting: bank-statement importers

Used by all phases:

- [ ] `kontor-bank-camt053` (separate artifact): ISO 20022 CAMT.053 parser → unposted suggestions
- [ ] `kontor-bank-nacha` (Phase 5-US, separate artifact): NACHA ACH import

Both produce **suggested** postings, never auto-posted. Reconciliation UI lives in consumers (beleg).

---

## Out of scope for v1

- Peppol Access Point (deferred until a customer needs network delivery)
- Multi-tenant / multi-company in one DB (use one DB per tenant)
- Cost accounting / management accounting beyond the analytic-dimension model
- Inventory valuation (FIFO/LIFO/weighted-average) — defer to a separate `inventory` module
- Payroll — own beast, separate library
- Asset depreciation schedules — Phase 6 candidate
- Consolidations across multiple entities

## Out of scope forever (per ADR-010)

- ERP modules (CRM, MRP, HR, project management)
- UI / view layer
- A US sales-tax engine (we wrap, not build)
- A clean-room reimplementation of Odoo

---

## Estimated wallclock totals

| Milestone | Cumulative weeks |
|---|---|
| Phase 0 done (this commit) | 0 |
| Phase 1 (kernel + tax + bitemporal) | 4-5 |
| Phase 1.5 (reports) | 5-7 |
| Phase 2-DE (DE-credible MVP) | 8-11 |
| Phase 3 (beleg integration) | 9-13 |
| Phase 4-CA (CA-credible) | 12-18 |
| Phase 5-US (US-credible with paid-provider scaffolds) | 16-24 |
| Track B audit-chain (parallel, ships any time after week 2) | +1-2 |

Plus per-country annual maintenance: low (DE) to moderate (US sales tax).

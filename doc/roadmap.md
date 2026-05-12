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

## Phase 4-CA — Canada — breadth (DONE)

Module: `modules/l10n-ca` (EPL-1.0 — CRA facts are public).

- [x] `resources/ca-coa.edn` — baseline CoA loaded by `chart.clj`
- [x] CA chart of accounts + account tags + per-authority tag conventions
- [x] GST/HST line generator (lines 101/103/108) in `returns.clj`
- [x] QST + BC PST report scaffolds in `returns.clj`
- [x] `bank-ca` CSV importer
- [ ] *(superseded by Phase 4-CA-depth below)* SR&ED project dimension, full info-return XML — these become Phase 4-CA-depth items below.

**Status**: breadth done; the kernel can post BC/ON/QC sales correctly and compute per-authority totals. What's missing for a *filable* CA tool is depth — see Phase 4-CA-depth.

## Phase 4-CA-depth — Canada depth-first (per ADR-015)

Goal: take CA from "models the books correctly" to "produces filable artifacts the user actually mails / uploads." Per ADR-015 the architecture is three rings (kernel / renderer / transmission); transmission is deferred.

Order is the minimum that makes the BC + T4 + self-employed + GST/HST-registered filer profile filable end-to-end:

- [x] **GST/HST filing-complete** (`gst_hst.clj`): all GST34-2 lines (101, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113A, 114, 115), period detection (quarterly + annual), transcription sheet ready for NETFILE web-form entry. Adjustment / instalment / rebate lines (104/107/110/111) are caller-supplied opts. Refund / payment / nil-return outcome detection. 14 tests, 51 assertions. Quick Method deferred.
- [x] **T1 model layer** under `y2024/`: T1 jacket + federal tax (brackets), S9 (donations 15/29/33%), S11 (tuition); T2125 (gross/expenses/CCA with half-year rule), S8 (CPP base/enhanced/CPP2 across employment + SE); S3 (capital gains 50% inclusion), S4 (investment income with dividend gross-up + federal/BC DTC); BC428 (provincial tax with NRTCs + tiered donation credit). 49 tests / 147 assertions. **Deferred**: BC479 refundable credits (climate action, sales tax), S7 RRSP carryforward (post-NoA-ingestion), AMT (T691), spousal/dependant/disability credits.
- [x] **NoA ingestion scaffolding** (`noa.clj`): carryforward fact schema (`:carryforward/*` namespace) + `from-manual-map` for hand-typed NoA data + `NoaParser` protocol with a stub PDF parser + `->t1-inputs` projection. 4 tests / 7 assertions. **Pending real CRA NoA PDF samples to implement the parser**; manual path works today.
- [~] **PDF renderer** (`pdf.clj`): Apache PDFBox AcroForm fill (`fill-form`, `list-fields`, `validate-field-map`) — mechanics tested via synthetic AcroForm PDF round-trip. **Reframed 2026-05-10:** real CRA fillable PDFs (5000-R, 5010-C, T2125) are **XFA dynamic forms**, not AcroForm — PDFBox's classic API returns only one container field per file. `extract-xfa-xml` retrieves the 1.5-MB XFA XML stream for future XFA-fill work. Synthetic-PDF mechanics still pass 5 tests. **Real CRA-PDF fill requires the XFA path** (sketched in pdf.clj docstring); the XFA XML dumps for T1/BC428/T2125 are saved in `test/resources/cra/*.xfa.xml`.
- [x] **Info-return XML** (`xml/`): T4, T5, T5018 generators using clojure.data.xml, with shared T619 transmittal envelope (`xml/t619.clj`). **Verified against CRA's published 2026V4 XSDs** (bundle in `test/resources/cra/info-returns-xsd-2026/`) via `xml/validation.clj` (JAXP, zero new deps). Every form's XSD-validity is asserted in tests. Submission shape per actual XSD: `Submission > T619 + Return > T4/T5/T5018 > Slip(s) + Summary`. All element names match the schemas. 13 tests across the three forms. **Generalizing-to-business deliverable** — non-cert path via IFT + WAC.

**Acceptance**:
- GST/HST: a quarter of fixture postings (BC sales + GST-bearing bills) produces all 15 GST34-2 line values correct against hand calculation; the transcription sheet is human-readable.
- T1: a full BC fixture (one T4, one T2125 with CCA, one S3 capital gain, RRSP deduction, donation, tuition) produces taxable income, federal tax, provincial tax, and balance owing matching the Haskell `canadian-income-tax` oracle to the cent.
- NoA: a sample 2023 NoA PDF parses to a carryforward fact-set that feeds the 2024 T1 correctly.
- PDF: the filled T1 General + BC428 PDF opens in Acrobat with all fields populated and prints clean.
- Info returns: a generated T4 XML validates against CRA's published XSD without errors.

**Out of scope for this depth iteration (and ADR-015 documents why):**

- Quebec TP1 / RL slips / ImpôtNet — a parallel-magnitude project.
- NETFILE / EFILE / CIF certification — bounded later addition; isolated to a single transmission namespace.
- T2 corporate income tax — mandatory e-filing means T2 is only useful with cert.
- AFR (Auto-fill My Return) — cert-gated.
- ReFILE — cert-gated.
- Express NOA — cert-gated.

## Phase 5-Multi-Country — Asia-Pacific + Latin America scaffolds

Per ADRs 016–019 (multi-tax-breakdown + EInvoiceProvider + clearance-token
lifecycle + account external-codes), four additional country modules
scaffolded end-2026-05 to validate the design against the hardest
cases (BR + CN). Each ships a starter chart, tax-stack model,
e-invoicing format emitter, and verification against authoritative
sources.

- [x] **JP — kontor-l10n-jp**: J-GAAP-style chart (~40 accounts, JPY
  precision 0); Consumption Tax (JCT) 10/8/exempt/zero-rated with the
  three zero-tax categories (非課税 / 免税 / 不課税); QIS registration
  number validator (T + 13 digits); Peppol PINT JP UBL emitter
  (`urn:peppol:pint:billing-1@jp-1` + `urn:peppol:bis:billing`).
  Verified against NTA + Digital Agency + Peppol docs; ProfileID
  initially wrong (had European :3.0) — corrected. 17 tests.
- [x] **AU — kontor-l10n-au**: ATO-aligned chart (~40 accounts, AUD
  precision 2) tagged to BAS labels; GST 10% single-rate; BAS report
  with Simpler-vs-Full mode (G1/1A/1B for <AUD 10M; full label set
  above); Peppol PINT A-NZ UBL emitter (`urn:peppol:pint:billing-1@aunz-1`).
  ABN validator. All values verified correct against ATO + Peppol; one
  cosmetic G3 reword applied. 13 tests.
- [x] **CN — kontor-l10n-cn**: ASBE/ASSBE-coded chart (~50 accounts,
  CNY precision 2, external-codes attached per ADR-019); VAT rates
  13/9/6/3/0% + surcharges (UMCT + edu + local-edu); fapiao number
  validators (8-digit legacy / 18-digit special-VAT combined /
  20-digit fully-digital); EInvoiceProvider draft-XML scaffold. STA
  platform integration deferred to partner module
  `kontor-l10n-cn-fapiao`. Verified against MOF/STA + China-Briefing;
  fapiao number format corrected (was 8-digit-only, now 8/18/20).
  11 tests.
- [x] **BR — kontor-l10n-br**: Plano de Contas Referencial-aligned
  chart (~60 accounts, BRL precision 2, ECF De/Para via external-codes);
  legacy tax stack (ICMS state matrix + IPI + PIS + COFINS + ISS +
  IRPJ + CSLL) + new CBS/IBS dual-VAT scaffold for 2026-33 reform;
  interstate routing pattern (state sets as data, not embedded);
  NF-e 4.0 XML emitter scaffold (kernel-side only); SPED EFD-ICMS/IPI
  subset (records 0000/0001/C100/C170/9999 with pipe-delimited
  framing). Signing + SEFAZ transmission = partner module
  `kontor-l10n-br-nfe`. 22 tests.

**Architectural validation (per user request):** the four ADRs
(016-019) held up against BR and CN without retrofit. The schema
changes for tax-breakdown collection, clearance-token lifecycle,
external-codes mapping, and EInvoiceProvider protocol all integrated
cleanly with the existing kernel; no existing tests broke. ADR-008
bitemporal validated under the BR tax-reform parallel-rule scenario.

## Phase 4-CA-cert — Canada certification (deferred)

Trigger: when the time saved by NETFILE-transmitting an annual stack of personal/business filings exceeds the cert effort, **or** when a customer with multi-tenant needs surfaces. Cert flow per ADR-015:

- [ ] Apply to CRA EFILE/NETFILE developer program (NDA, test scenario access).
- [ ] Implement `kontor.l10n-ca.transmit` against the published-to-vendors protocol.
- [ ] Pass the annual CRA test battery (mid-Nov → mid-Feb cycle).
- [ ] Add AFR, ReFILE, Express NOA inside the transmission ring.

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

## Phase 6 — Business-OS companions (hybrid plan, post-Phase-2)

Building on the kernel + first-jurisdiction stack, the project grew an explicit "business operating system" scope: companion modules under `modules/<name>/` that compose on the kernel without bloating it. This walks back the strictest reading of ADR-010 ("no ERP modules forever") — see ADRs 027-033 for the progressive scope walk and rationale. Companions are opt-in: kernel-only consumers ignore them.

Hybrid build order (per research notes 09-12; the foundations get 3-4 weeks of depth each so the downstream cadence can be faster):

- [x] **Stage H — ADR-032** — `:schedule` entity + `:cost-center` bootstrap (cross-cutting primitives every companion needs). Done 2026-05-12.
- [x] **Stage I — kontor-partner** — party-as-root + person/org subtypes + polymorphic contact-mech + roles + relationships (ADR-033). Done 2026-05-12. 618 tests / 2094 assertions baseline.
- [ ] **Stage J — kontor-sales** — order header + items + ship groups + adjustments + roles + status history. Foundation for invoice generation, procurement, revrec, subscription. (3-4 weeks; in progress.)
- [ ] **CHECKPOINT** — validate kontor-partner + kontor-sales against a real customer use case OR independent code review before proceeding to faster cadence.
- [ ] **Stage K — kontor-procurement** — `:order/type :purchase` shape + requirement (requisition) entity + 3-way match (PO ↔ receipt ↔ invoice). Builds on Stage J. (~2-3 weeks.)
- [ ] **Stage L — kontor-asset** — fixed-asset register + depreciation schedule (consumes ADR-032 `:schedule`). (~2 weeks.)
- [ ] **Stage M — kontor-revrec** — ASC 606 / IFRS 15 over-time revenue recognition (consumes Stage J + ADR-032). (~3 weeks.)
- [ ] **Stage N — kontor-subscription** — recurring billing with catalog versioning (Kill Bill-pattern). (~2 weeks.)
- [ ] **Stage O — kontor-project** — project + task + timesheet (timesheet = analytic-line per Odoo pattern, no new entity). (~2 weeks.)
- [ ] **Stage P — kontor-commerce-adapter** — UBL 2.1 + Peppol BIS round-trip for B2B document interchange. (~1 week per integration.)
- [ ] **Stage Q — kontor-hr + kontor-payroll-de-datev** — `:person` + `:employment` (effective-dated, multi-job per Workday pattern) + per-jurisdiction PayrollProvider adapter for DE (DATEV LODAS / Lohn und Gehalt).

Deferred until concrete consumer demand: kontor-mfg, kontor-helpdesk, kontor-field-service, kontor-fleet, kontor-clm, kontor-marketing, kontor-crm. The "commodity SaaS" verdict from research note 10 §3: most of these have license-clean OSS or paid SaaS that we'd integrate with rather than reimplement.

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
| Phase 6 stages H + I (schedule + cost-center + partner) | done |
| Phase 6 stage J (sales — foundation, 3-4 weeks) | in progress |
| Phase 6 stages K-Q (later companions, after checkpoint) | +12-16 |

Plus per-country annual maintenance: low (DE) to moderate (US sales tax).

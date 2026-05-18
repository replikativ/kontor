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
- [x] period.clj: refuse postings whose tx's `:tx/valid-from` (kontor.bitemporal, ADR-048; was `:posting/valid-from` per ADR-008) falls inside a closed `:period/locked-at` period
- [x] state-machine.clj: enforce :transaction/state lifecycle (draft → posted → cancelled)
- [x] Schema additions for analytic accounts (ADR-012): `:analytic-plan/*`, `:analytic-account/*`, `:analytic-distribution/*`, `:posting/analytic-distributions`
- [x] `tax/apply` — uses `TaxProvider` protocol to expand a base posting into base + tax postings
- [x] `StaticTableProvider` reading EDN tax definitions
- [x] `sealing/transact-with-sealing` — middleware refusing silent retract of `:posting/posted-at`-marked entities
- [x] `balance/account-balance` and `ledger/postings-against` — bitemporal-aware (as-of-tx, as-of-valid via `kontor.bitemporal/posting-vf` per ADR-048)
- [x] `trial/trial-balance` — date-range, bitemporal
- [x] `period/open?`, `period/close!`, `period/lock-tx`
- [x] `import_/beancount` — parser + transactor + dumper; round-trip test against `examples/example.beancount`
- [x] Test coverage: schema invariants, sum-to-zero, sealing, bitemporal queries, Beancount round-trip

**Acceptance**: a representative Beancount example file round-trips byte-clean (modulo whitespace), tax + sealing tests pass, trial balance computes correctly across a known fixture.

## Phase 1.5 — Declarative report engine (DONE)

- [x] Schema: `report`, `report-line` (hierarchical), `report-expression`
- [x] Engines: `:account-codes`, `:tax-tags`. Further engines (`:aggregation`, `:domain`) deferred until a real consumer needs them — per ADR-014 we don't build the broader Odoo shape until a second country forces it.
- [x] Compute: walk the report tree, evaluate expressions, materialize per-period values
- [x] First test reports: P&L, Balance Sheet (kontor.financial-statements)

## Phase 2-DE — Germany (DONE — Stage L baseline)

Lives in `modules/l10n-de/` (GPLv3, sourced from Tryton `account_de_skr04` + GnuCash community SKR04 + Odoo cross-check).

- [x] SKR04 chart of accounts as data (`modules/l10n-de/.../chart.clj`); SKR03 deferred until a customer requests it
- [x] VAT report tag definitions + UStVA Kennzahlen
- [x] UStVA monthly return (`modules/l10n-de/.../ustva.clj`)
- [x] `StaticTableProvider` config for DE (standard 19% + reduced 7% + zero + reverse charge + EU intra-community)
- [x] DATEV EXTF exporter (`modules/l10n-de/.../datev.clj`)
- [x] Year-end closing (`modules/l10n-de/.../closing.clj`)
- [x] EÜR + BWA + P&L year-end (`modules/l10n-de/.../eur.clj`)
- [x] `modules/einvoice-de/` — Factur-X / XRechnung / ZUGFeRD wrapper

**Acceptance met** — see `doc/showcases/01_de_b2b_factur_x.clj` (DE Mahnverfahren end-to-end).

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
- [x] **Stage J — kontor-sales + kontor-invoice** — order header + items + ship groups + adjustments + roles + status history (ADR-035), AND extends kernel `:invoice/*` with order-bridge + status machine + three-tier GL resolution + AcctgTrans posting (ADR-036). State-machine table is a generic kernel primitive (ADR-034: `:status-transition` + `:status-history` + `kontor.status-machine`). Done 2026-05-12. 657 tests / 2278 assertions baseline.
- [x] **Stage J CHECKPOINT** — 1 independent code-review agent + 4 market-pain research agents (partner/MDM, order management, invoicing/AR, status-machine/workflow) audited the design. 6 P0 ship-blockers from local review + 1 P0 from market-pain (period-close enforcement) fixed in commit `8524e42`. Findings captured in [research note 13](research/13-stage-j-pain-and-followups.md). Architecture validated on 11 dimensions; remaining gaps are mostly content (vocabularies, account-types) not architecture.
- [x] **Stage J POSITIONING — ADR-037** — `kontor` as a business operating system. Defines what business OS means in kontor's context, target consumer, differentiation strategy, minimum coherent module set, non-goals. Supersedes the strict reading of ADR-010 ("no ERP modules forever") while preserving its narrow claims (no UI, no US sales-tax engine, no Odoo translation). Sets direction for the Stage J-2 cross-cutting primitive ADRs.
- [x] **Stage J-2 — Cross-cutting primitive ADRs** (per ADR-037 + research note 13). Resolved ~30 P1 items in 4 coherent passes. 675 tests / 2336 assertions baseline.
  - [x] **ADR-038 Audit + Governance** (commit `91f325b`) — `:status-history/reason` as keyword + `:reason-note` string + `:status-history/supporting-doc` ref + `:audit-doc` kernel entity + `:approval-policy` with SoD enforcement (`:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note`). New `kontor.audit-doc` namespace.
  - [x] **ADR-039 Master-data** (commit `f2c97da`) — `:partner-merge` non-destructive link + `:bank-account` entity + `:partner-bank-account` junction + `:partner/credit-limit` + `:partner-tag` segmentation + KYC trio. New `kontor.bank-account` namespace.
  - [x] **ADR-040 Jurisdiction** (commit `32d76a0`) — `:partner-tax-id` junction + `:invoice-line/reverse-charge?` + `:invoice/tax-inclusive?` + `:invoice-line/recognition` keyword + withholding-tax routing + 5 new clearance state-transitions (`:pending-attestation`, `:rejected`) for IT SdI / IN IRN / BR NF-e / ES Verifactu.
  - [x] **ADR-041 Workflow extensions** — time-based transitions via `:status-transition/auto-after-millis` + `sweep-time-based!` sweeper + `:side-effect-intent` kernel entity with `kontor.side-effect` dispatcher + `bulk-record-status-change-tx-data` + `:account-type-direction` data table replacing hardcoded debit/credit map.
- [x] **Stage K — kontor-procurement** — Full P2P + reverse flow. ADR-042 design (`a08c239`) + 4 implementation commits: schema/seeds (`35fcf12`), forward 3-way match + bridge polymorphism (`16a7f4a`), reverse flow + credit-memo (`2e570d8`), drop-ship + `:order-item-assoc` (`1b69e06`). 708/2554 baseline.
- [x] **Stage K-5 — review-after fixes** (commit `3eb8a0a`). 6 P0 from code-review + market-pain delta closed: `post-receipt-with-inventory!` shipped, `:requires-three-way-match-pass` rule wired, credit/debit-memo polarity flip, three-way invariant filters by receipt status + nets returns, 3 missing state transitions, `:receipt-invoice-billing` junction live, end-to-end P2P posting test. Bridge `:purchase + :direct → :gr-ir-clearing` (canonical receipt-first). 718/2576 baseline.
- [x] **Stage L — kontor-collections** — Done. AR collections companion + kernel `:payment-application` partial-payment primitive landed. Five commits per the original plan. Post-Stage-L review delivered ADR-048 (valid-time normalization to `:tx/valid-from`) and the six research notes (17-21) + Agent A code review. P0/P1 cleanup completed 2026-05-13: kernel `kontor.invoice` ported to status machine (P0-4α); `credit-hold` + `dunning-pause` ported to status machine (P0-5); four denorms dropped (P0-6); `:collection-case/{opened-at,closed-at}` + `:credit-hold/placed-at` deferred as tuple-identity-coupled.
- [x] **Stage L′ — kontor-asset** — fixed-asset register + depreciation schedule (consumes ADR-032 `:schedule`). ADRs 053-056 landed; `modules/asset` ships the `:asset` register + `DepreciationProvider` protocol + Anlagengitter + cash-flow + equity-changes report engines + the per-`(asset, ledger)` parallel-book story (Handelsbilanz vs Steuerbilanz). Review-after note 33 closed all P0s.
- [x] **Stage M — kontor-legal** — `:legal-hold` (kernel) + `:retention-policy` (kernel shape + l10n data) + `:audit-doc/privilege` keyword (kernel). ADRs 049-052 landed; review-after notes 27 + 32 closed all P0s. Heavy companions (`kontor-counsel`, `kontor-privacy`, `kontor-clm`) remain gated on user-story pull.
- [x] **Stage N — kontor-inventory** — facilities + physical stock ledger + ATP/reservation + receive/issue/transfer/cycle-count/FEFO + GL integration. ADRs 057-060 landed; review-after note 37 closed P1s + cheap P2s. `modules/inventory` ships.
- [x] **Stage N+ — kontor-expense** — employee expense reports. ADR-061; `modules/expense` ships.
- [x] **Stage L (kontor-lease) — kontor-lease** — IFRS 16 / ASC 842 lessee-side. ADRs 062-064 + 069 (mid-life import) + 070 (disclosure-support deltas + discount-rate audit-doc) landed; review-after note 40 closed P1s. `modules/lease` ships.
- [x] **kontor-authz** — ReBAC ported from EACL. ADRs 065-066 landed; review-after note 43 closed P0+P1s; consumer-readiness sweep (#127) closed the `ADR-066-deferred` methods. `modules/authz` ships.
- [x] **Stage P — kontor.process + universal `*-tx-data` builders** — ADRs 067 + 068. Every business-write transactor across kernel + companions now exposes a pure `*-tx-data` builder + a thin `!` wrapper routing through the kernel validation gate. Review-after notes 48 + 49 closed P0s; remaining P1s tracked.
- [ ] **Stage N (revrec) — kontor-revrec** — ASC 606 / IFRS 15 over-time revenue recognition (consumes Stage J + ADR-032 + Stage L `:payment-application`). Not yet started.
- [ ] **Stage O — kontor-subscription** — recurring billing with catalog versioning (Kill Bill-pattern). Not yet started.
- [ ] **Stage P (project) — kontor-project** — project + task + timesheet (timesheet = analytic-line per Odoo pattern, no new entity). Not yet started; Stage P stage-letter was repurposed for `kontor.process` (ADR-067) so this becomes "Stage U" or similar.
- [ ] **Stage Q — kontor-commerce-adapter** — UBL 2.1 + Peppol BIS round-trip for B2B document interchange. Not yet started; `modules/einvoice-de` ships Factur-X today.
- [x] **Stage R — kontor-hr + per-country PayrollProvider adapters** — **Stage R complete: C1 substrate + C2 DE + C3 US + C4 CA all landed.** Substrate (C1, ADR-075): `:person` + `:employment` + `:compensation` + `:compensation-component` + `:pay-period` + `:payroll-run` in `kontor-hr`, plus kernel attrs `:audit-doc/category` + `:retention-policy/category` + `:audit-doc/language`, plus `kontor.payroll-provider` protocol trio + `PayrollFacts` shape + `kontor.hr.payroll/run-payroll!` orchestrator. C2 (ADR-076): DE-DATEV-LODAS adapter parses EXTF Buchungsstapel + emits LODAS Importdatei + HGB §249 PTO accrual. C3 (ADR-077): US-ADP-GLI adapter parses 10-column CSV with balancing-row trap + `:analytic-account/state` per-state allocation + ASC 710 + 401(k) match + W-2 reconciliation. C4 (ADR-078): CA-CRA adapter ships Ceridian + ADP-CA + Wagepoint skeleton + BN program-account routing + T4 builder reusing shipped `xml/t4.clj` + T619 + PD7A period-due + ROE termination-event. Research-before (notes 79 + 81-84) + two review-afters (notes 85 + 86, 4 P0s closed). Cross-stage validation: trans-national Jane Doe employed in DE-GmbH + US-LLC + CA-Corp simultaneously, 3 currencies, 3 payroll-runs (`test/kontor/stage_r_cross_stage_test.clj`). ~115 deftests + 870 assertions in new modules. P1/P2 backlog from note 86 absorbs into C5 (next country) follow-up.

Candidate companions surfaced by the post-Stage-L research (each gated on a real consumer story):

- **kontor-mcp** — Model Context Protocol server exposing read/reason/write tools over the kontor substrate. Bitemporal "what did the model see" replay is the killer feature. Companion artifact, not kernel. Research: `doc/research/20-ai-native-business-os.md`.
- **kontor-forecast** — Probabilistic + numerical forecasting on top of `kontor` + `simmis` + `stratum` + `raster` + (anglican OR raster-native MCMC). Worked example: 12-month rolling cash-flow fan-chart. License posture deferred (Anglican is GPL-3.0; raster-native MCMC keeps the stack permissive). Research: `doc/research/19-probabilistic-numerical-integration.md`.
- **kontor-workflow** — `:workflow-instance` correlation primitive (Agent F's Option B — ~7 attrs, back-refs on `:status-history` and `:side-effect-intent`) + OCEL-2.0 process-mining emit. ~4-6 weeks for the mining MVP. Research: `doc/research/21-process-workflow-modeling.md`.

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

Per-country bank-statement parsers live under `modules/bank-<cc>/`. Each produces **suggested** postings via `kontor.reconciliation`, never auto-posted. Reconciliation UI lives in consumers (beleg).

Shipped: `bank-at`, `bank-ca`, `bank-de`, `bank-fr`, `bank-us` (kernel-level CSV importer in `kontor.bank-csv` + per-bank format adapters).

- [ ] CAMT.053 (ISO 20022) parser — pending; planned as a shared adapter once a second country needs it
- [ ] NACHA ACH import — Phase 5-US, separate artifact

---

## Trans-national substrate (ADR-071 / ADR-072 / ADR-073 / ADR-074) — LANDED 2026-05-17 / -18

The kernel grew four coordinated substrate primitives that close the architecture review's §4 gaps (research note 69) plus the cross-DB story from research note 71:

- **ADR-071 — Tax abstraction redesign** (`TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder`). 3-protocol split unblocks per-jurisdiction tax engines without the kernel knowing chart-of-accounts. Migration per-l10n-module + consumer-driven; the kernel ships the protocol skeletons.
- **ADR-072 — `FxRateProvider` + `kontor.fx`**. Protocol + `:fx-rate/*` schema + `StaticTableProvider` + `EcbReferenceRatesProvider` + Money-level convert / translate-amounts-by-commodity / functional-currency rebase. ECB attribution required; no rates bundled. `kontor.report/compute-report` gained `:translate-to`; `kontor.lease.posting/plan-fx-retranslation` gained the IAS 21 provider mode.
- **ADR-073 — Consolidation primitive** (`kontor.consolidation`). Per-IAS-21 translation + intercompany elimination + `consolidate!` orchestrator over `kontor.entity/family`. Substrate provides the mechanics; companion-tier (`kontor-consolidation`) layers ownership %, minority interest, IFRS 10 control on top.
- **ADR-074 — `kontor.side-effect.cross`: cross-DB saga primitive**. `CrossTxRouter` protocol + `:cross-tx/step-id` content-hash idempotency + `drain!` worker over `:side-effect-intent` (ADR-041). Crash-safe, no XA/JTA. Enables kontor↔stratum / kontor↔kontor / kontor↔scriptum without coupling.

Architecture review §4 Gaps 1 (`:entity` filter), 2 (FxRateProvider), 4 (consolidation) now closed at the substrate level. Gap 3 (HR/payroll) is research-before complete (notes 72/73/74); implementation gated on 5 design calls in note 74.

Plus two smaller follow-ons:

- **Cross-tx vf<vt guard + `kontor.bitemporal/close-validity{-tx-data,!}`** (commits `411d411` / `1655165`) — retroactive valid-time-window closure helpers, post-supersession-research (note 77).
- **`:account-tag/concept-iri`** (commit `9a160aa`) — substrate seam for XBRL / filing taxonomies per research note 78. Substrate stores + indexes; verification is companion-tier.

## Out of scope for v1

- Peppol Access Point (deferred until a customer needs network delivery)
- Multi-tenant / multi-company in one DB (use one DB per tenant)
- Cost accounting / management accounting beyond the analytic-dimension model
- Payroll — own beast, separate library (research-before bundle in notes 72/73/74)
- Consolidation policy (ownership %, minority interest, IFRS 10) — substrate primitives ship (ADR-073); policy is the future `kontor-consolidation` companion

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
| Phase 6 stages H + I + J (schedule + partner + sales + invoice + status-machine) | done |
| Phase 6 stages K-Q (later companions, after checkpoint) | +12-16 |

Plus per-country annual maintenance: low (DE) to moderate (US sales tax).

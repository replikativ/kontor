# 51 — Tax authority as a kontor consumer: substrate-fit analysis

**Date:** 2026-05-15
**Agent:** general-purpose (research)
**Scope:** would a national tax authority (HMRC, IRS, BMF, SAT, RFB,
GSTN) keep its taxpayer-account books on top of kontor, and what
would have to change for that to work? The question is not "can
kontor compute taxes" — that's ADR-005 already, and the answer is
yes — but "can kontor be the *system of record* for the
*authority side*: the per-taxpayer per-tax-type per-period running
balance of assessments, payments, refunds, abatements, interest, and
penalties."
**Method:** read of the open documentation for the dominant
authority-side platforms (HMRC ETMP on SAP PSCD, IRS Master File,
GSTN three-ledger model, Brazil SPED/Receita Data, OECD TAS,
IMF/TADAT, CRMS literature), correlated against kontor's existing
ADRs (-005, -007, -008, -021, -031, -034, -038, -039, -040, -048,
-049, -050, -051, -052).

## 1. Tax-authority substrate summary

Every modern revenue administration boils down to **one shape**: per
(taxpayer × tax-type × period) a subledger of *open items* that
accrue, get satisfied, get reversed, and bitemporally restate when
returns are amended. Four reference points, all converge:

**HMRC ETMP / SAP PSCD.** ETMP is the SAP-based backbone for
£800B/yr across 45+ tax regimes, used daily by ~40k staff. It runs
on **SAP Public Sector Collection & Disbursement (PSCD)** — SAP's
contract-accounting subledger. PSCD's entity model:
*Business Partner* (taxpayer) → 1..N *Contract Accounts* (one per
tax type / regime) → N *Documents* (each filing, payment, refund,
correction posts a multi-line document) → N *Open Items* (each line
in a document is a receivable or payable with its own due date,
clearing status, dunning level). Open items clear against other
open items (a payment clears a liability); a write-off, abatement,
or reversal is itself a document posting. Each subledger line maps
1:1 to a GL reconciliation account.
([SAP PSCD overview](https://learning.sap.com/courses/exploring-financial-contract-accounting-in-sap-public-sector-collection-and-disbursement/introducing-sap-public-sector-collection-and-disbursement-sap-pscd),
[ETMP regeneration AOA](https://www.gov.uk/government/publications/enterprise-tax-management-platform-regeneration-programme-accounting-officers-assessment-summary/enterprise-tax-management-platform-regeneration-programme-accounting-officers-assessment-summary),
[SAP/HMRC 2026 announcement](https://news.sap.com/uk/2026/02/sap-selected-by-hmrc-to-lead-major-technology-transformation-of-the-uk-tax-system/))

**IRS Master File (IMF/BMF).** A taxpayer's record splits into an
**Entity Module** (TIN, name, addresses, filing requirements) and a
set of **Tax Modules**, one per (form-type × tax-period). Every
posting to a tax module is a **Transaction Code (TC)**, a 3-digit
opcode keyed by date and cycle: TC 150 is the original return, TC
290 an additional assessment, TC 291 an abatement, TC 670 a
payment, TC 846 a refund, TC 340/341 interest restriction,
TC 521/523 freeze. Posting order is by TC numeric order within a
cycle. The "TXMODA" transcript = the full history. Amended returns
do NOT mutate TC 150; they post a TC 290/291/976/977 alongside it.
The Assessment Statute Expiration Date (ASED, IRC §6501) is
3 years from the later of return-due-date and filed-date, extended
to 6 years for substantial understatement, unlimited for fraud or
non-filing — encoded as a per-tax-module field, not a query.
([IRM 25.6](https://www.irs.gov/irm/part25/irm_25-006-001r),
[IRS Pub 6209 Section 8A](https://www.irs.gov/pub/irs-6209/6209sec8amasterfilecodes.pdf),
[IRM 21.2.3 transcripts](https://www.irs.gov/irm/part21/irm_21-002-003r))

**GSTN three-ledger model.** Every registered GSTIN runs three
parallel running-balance books:
- **Electronic Liability Ledger** — debit-side, posted from filed
  GSTR-3B, demand notices, late-fee accruals, audit assessments.
- **Electronic Cash Ledger** — credit-side wallet for deposits
  (NEFT/UPI/over-the-counter).
- **Electronic Credit Ledger** — credit-side wallet for ITC (input
  tax credit) auto-populated from GSTR-2B.
Discharging a liability is a settlement transaction that debits one
of the two credit ledgers against the liability ledger; precedence
rules constrain which ledger may clear what (e.g., cess against
cess, IGST first against IGST output then CGST/SGST). IRN/e-invoice
clearance is upstream of the ledgers; the IRP returns an IRN +
signed QR, and the matched invoice flows into auto-GSTR-1 →
auto-GSTR-2B → ITC. ([ClearTax e-ledgers](https://cleartax.in/s/e-ledgers-under-gst),
[Webtel guide](https://webtel.in/Blog/overview-of-cash-credit-and-liability-ledgers-under-gst-a-complete-guide-for-2025/3488))

**Brazil RFB / SPED / Receita Data.** Receita Federal runs **600+
transactional systems / hundreds of DBs**, with SPED submissions
feeding a Hadoop/Trino data-lake ("Receita Data") that runs
trillion-row queries for cross-checks. The authority's *books* sit
behind SPED as a per-CNPJ × per-tax × per-period subledger; the
data-lake is the analytic/CRMS layer on top. ([RFB BRICS case
study](https://tax.brics.br/Brazil_BRICS_Case_Study_Brazil.Paper.pdf),
[CIAT digital transformation](https://www.ciat.org/an-overview-of-digital-transformation-at-receita-federal-do-brasil/))

**Cross-cutting concerns** consistent across all four:
1. **Entity model**: Taxpayer, Tax-type, Period, Return, Assessment,
   Payment, Refund, Abatement/Adjustment, Interest accrual, Penalty,
   Lien, Audit case, Installment-plan, Write-off — every reference
   shows essentially this set of ~15 entities.
2. **Event model**: filings, payments, refunds, abatements, audit
   determinations, statute expirations. Order matters (TC posting
   order); idempotency is enforced by document-unique-IDs.
3. **As-filed vs as-currently-assessed**: textbook bitemporal —
   "what did the taxpayer say on the return as filed?" vs "what is
   the authority's current position?" answered separately.
4. **Statute-of-limitations**: per-(taxpayer, tax-period) ASED with
   a vocabulary of suspensions (audit-open, bankruptcy, OIC pending,
   offshore-fraud) and extensions (Form 872 consent). The deadline
   is a derived field on the tax module, not a row.
5. **Compliance Risk Management (CRMS/CRM)**: the OECD/IMF
   architectural pattern is *separate* the operational subledger
   from the analytic risk engine. Risk score is computed in the
   data-lake from features (return ratios, sector benchmarks,
   third-party data, network graphs) and written back as a flag.
   ([IMF CRMS technical note](https://www.imf.org/-/media/Files/Publications/TNM/2022/English/TNMEA2022005.ashx),
   [EU CRM guide 2023](https://taxation-customs.ec.europa.eu/system/files/2024-01/2023_CRM_Guide.pdf))
6. **Inter-jurisdiction sharing**: OECD CRS XML schema and IRS
   FATCA XML schema are well-defined transport formats keyed by
   ISO-3166-1 alpha-2 country codes. ([CRS XML schema 2024](https://www.oecd.org/content/dam/oecd/en/publications/reports/2024/10/amended-common-reporting-standard-xml-schema_27960161/dd7ee57a-en.pdf),
   [IRS FATCA schemas](https://www.irs.gov/businesses/corporations/fatca-xml-schemas-and-business-rules-for-form-8966))

## 2. kontor coverage map

**Covered cleanly — kontor's primitives already do this.**

| Authority concern | kontor primitive | ADR |
|---|---|---|
| Per-taxpayer per-tax-type subledger | `:account` + `:ledger` per tax-type; `:partner` = taxpayer; sum-to-zero per-(entity, ledger, commodity) | -021, -031 |
| Document with N postings (PSCD "document"; IRS TC posting) | `:transaction` + N `:posting`s | core |
| As-filed vs as-currently-assessed | bitemporal `:tx/valid-from` + `:db/txInstant` separation; `kbt/value-at` answers "what did we know at date D" and "what was legally in effect on date D" independently | -008, -048 |
| Amended return = restatement | reversal + repost pattern; ADR-007 forbids silent retracts; the amend chain self-documents | -007, -008 |
| Filing / assessment lifecycle (received → processed → assessed → adjusted → closed) | `kontor.status-machine` + `:status-history` + codified `:reason` keywords | -034, -038 |
| Approval gate on consequential moves (writing off >$X, releasing a freeze) | `:approval-policy` with `:no-self-approval` / `:requires-supporting-doc` | -038 |
| Supporting evidence on every adjustment (correspondence, audit reports) | `:audit-doc` + `:content-hash` + `:storage-uri` | -038 |
| Legal hold on litigation-relevant taxpayer records | `:legal-hold` with `:scope-query` | -049 |
| Retention with statute-aware expiry | `:retention-policy/triggered-by` + `:duration-years` + `:expiry-action` | -050 |
| Privilege classification (taxpayer-rep correspondence) | `:audit-doc/privilege` status-machine | -051 |
| Subject-access / FOI walks of all data on a taxpayer | `kontor.dsar/collect` bitemporal walk | -052 |
| Effective-dated tax rates (rate changes mid-year for current periods, original rate for back periods) | `:tax/effective-from` + `:tax/effective-until` | -026 |
| Interest accrual on overdue balances | the `:schedule` entity for recurring postings; ADR-032 cost-center bootstrap pattern | -032 |
| Multi-entity (federal vs state-by-state ledgers, or one authority running several taxes as parallel books) | `:entity` + per-(entity, ledger, commodity) sum-to-zero | -031 |
| Multi-jurisdiction tax bookkeeping | `:country` + `:state` + `:transaction/place-of-supply` | -023 |
| Multi-tax-id per taxpayer | ADR-040 jurisdiction multi-tax-id primitives | -040 |
| Cross-jurisdiction sharing (CRS/FATCA) export | bitemporal point-in-time queries are exactly what the CRS reportable-period semantics need; we ship a `CrsExportProvider` shaped like ADR-005's `TaxProvider` | -005 (pattern) |

**Needs a kontor extension — net-new but shapes fit cleanly.**

1. **`:taxpayer` discriminator on `:partner`.** kontor's `:partner`
   already carries `:partner/tax-id` and `:partner-tax-id`
   (ADR-040). What's missing is a `:partner/kind :taxpayer` role +
   the authority-specific scalar facets (`:taxpayer/registration-status`,
   `:taxpayer/effective-registration-date`,
   `:taxpayer/filing-requirements` — analog of the IRS Entity
   Module). Pattern: like `:partner-merge` / `:partner-bank-account`
   from ADR-039. Single new namespace.

2. **`:tax-period` entity.** Authority side cares about period as a
   *first-class* dimension (CY2024, Q3-FY26, monthly-Sep-2025) for
   joining (taxpayer × tax-type × period → liability ledger). The
   kernel today has `:period` as fiscal-period for a *firm*, not as
   a *recurring statutory window* for a *tax-type*. Small extension:
   `:tax-period/code` + `:tax-period/start` + `:tax-period/end` +
   `:tax-period/tax-type` + `:tax-period/jurisdiction`. Composes
   cleanly with ADR-014 period locks for the authority's own books.

3. **`:assessment` entity (the document discriminator).** A
   `:transaction` with `:transaction/kind :assessment` is enough
   structurally, but the joins ("show me every assessment for
   taxpayer X for period Y including amendments") want a typed
   header carrying `:assessment/taxpayer`,
   `:assessment/tax-period`, `:assessment/assessment-date`,
   `:assessment/source` (`:original | :amended | :audit-determined
   | :default-assessment`), `:assessment/ased-deadline` (statute
   expiry, derived field stored for index-scan). The underlying
   `:transaction` carries the postings; the `:assessment` is the
   semantic spine. Pattern: same as `:invoice` (ADR-036) sitting on
   top of an AcctgTrans.

4. **`:return` entity (the as-filed snapshot).** Distinct from
   `:assessment` — a return is what the *taxpayer* said; an
   assessment is what the *authority* concluded. Amended returns are
   new `:return` rows linked back via `:return/amends`; the
   authority's view evolves independently via the assessment chain.
   Same pattern as ADR-025 BR e-invoice `:complemento`.

5. **`:audit-case` entity.** Container for an open audit / examination:
   `:audit-case/opened-at`, `:audit-case/closed-at`,
   `:audit-case/suspends-ased?` (computed flag that affects statute
   expiry), `:audit-case/examiner-uid` (consumer's user model),
   `:audit-case/state` (status-machine facet). Connects via
   `:audit-case/covers-assessments` cardinality-many ref. The
   `:audit-doc` from ADR-038 attaches the workpapers.

6. **`ComplianceRiskProvider` protocol.** Direct sibling of
   `TaxProvider` (ADR-005) and `EInvoiceProvider` (ADR-017). The
   kernel defines the protocol shape (`score-taxpayer`,
   `score-return`, `flag-features`) and ships a no-op default. Real
   implementations (a customer-supplied ML pipeline, an integration
   with a CRMS vendor) plug into the same shape. Risk scores get
   written back as `:taxpayer/risk-score` + a side-band
   `:risk-determination` audit-doc — never a hidden field; every
   score has a recorded reason and supporting feature snapshot.
   Per IMF/EU/OECD guidance, the *analytic* layer is *separate*
   from the operational subledger; this protocol enforces that
   separation by construction.

**Sibling modules — clearly NOT in the kernel.**

- **Return-format parsers** — SPED ECF/ECD/EFD, GSTR-1/3B JSON,
  HMRC MTD API specifics, IRS MeF e-file schemas, SAT CFDI 4.0,
  FATCA/CRS XML. Each is a `kontor-l10n-<jurisdiction>-authority`
  module mirroring the consumer-side `kontor-l10n-<cc>` pattern
  (ADR-006). The kernel knows nothing about XML namespaces or
  state-tax-line schedules; the module ships the parser +
  per-tax-type field mapping into the kernel's `:return` /
  `:assessment` entities.
- **Case management / examiner workflow** — task assignment, SLA
  timers, supervisor escalation, IDR (information document
  requests). This is `kontor.process` (ADR-067) consumer
  territory: the authority composes `audit-case-tx-data` +
  `assessment-tx-data` + `status-transition-tx-data` builders
  (ADR-068) into atomic process steps. No new primitive needed.
- **CRMS scoring engine** — the *implementation* of
  `ComplianceRiskProvider`, not the protocol. Big-data / ML stack
  belongs out of process (cf. RFB Receita Data Hadoop lake).
- **Notice generation / correspondence rendering** — PDF / e-mail
  letter templates, e-service-portal HTMX UI. ADR-010: no UI in
  kernel.
- **Banking/payment-channel adapters** — net banking, IRS Direct
  Pay, UPI, EFTPS, debit-card processors. Already covered by
  `kontor.bank-csv` import pattern for the inbound side; outbound
  refund issuance is a sibling.

**Show-stoppers — places where kontor's invariants conflict.**

1. **Sum-to-zero per (ledger, commodity) — the big one.** This
   needs careful handling but is *not* fatal. The naive worry is
   "an assessment is one-sided from the authority's perspective."
   But: PSCD, IRS Master File, and the GSTN ledgers all *do*
   double-entry internally. An IRS TC 290 (additional assessment)
   debits the taxpayer's tax-module liability AND credits a revenue
   account; a TC 670 payment debits the revenue-clearing account
   AND credits the liability; a TC 846 refund reverses the flow.
   The taxpayer's running balance is the **net** of the liability
   account in their tax-module subledger. So: model the authority's
   books as a normal kontor multi-ledger structure where each
   `(taxpayer × tax-type)` is its own ledger or analytic account,
   and assessments / payments / refunds are balanced transactions
   like any other. **No invariant change needed.** The mental
   shift is that the "customer" of the authority's accounting is
   the authority itself, not the taxpayer — same primitives, dual
   perspective.

2. **Sealing vs amended-return re-opens-period.** ADR-007 says
   posted transactions cannot be silently retracted; amended
   returns post *new* transactions (TC 290/291 pattern), they
   never mutate the original TC 150. **kontor's sealing model is
   exactly right for this** — the original posting is sealed; the
   amendment is a new posting; bitemporal `:tx/valid-from`
   distinguishes "the return as filed on date X" from "the
   assessment as it stands on date Y." ADR-014 period locks need a
   companion concept though: a taxpayer's tax period can be
   *re-opened* by an audit even after the authority has soft-closed
   its books for the calendar quarter. The current `period/reopen!`
   helper already supports this — the reopen is itself a recorded
   commit. **No conflict; just needs to be exercised in tests.**

3. **Volume.** Receita Federal stores billions of files/year; GSTN
   processes billions of invoices/month. Single-instance datahike
   is the wrong shape for this scale. **kontor is the substrate for
   the small-to-medium authority** (a municipal property-tax
   office, a Caribbean revenue authority running SIGTAS-replacement,
   a state department of revenue) or **the small partition** (one
   tax-type / one region) of a large authority. The protocol/
   schema contributions are still valuable at scale (you could
   replay the model on a different datalog store); the
   single-instance kernel is not. Honest about this; not a
   show-stopper, a positioning constraint.

4. **CRMS analytics live in a data lake, not in datahike.** The
   IMF/OECD guidance is explicit that scoring runs on a separate
   analytic stack with billion-row queries. kontor doesn't compete
   here, and shouldn't try. The `ComplianceRiskProvider` protocol
   is the seam — scores flow *in*; features for scoring flow *out*
   via a kontor → parquet export. ETL boundary; not a kernel
   concern.

## 3. Verdict and recommendation

**Viability: yes, with a focused companion module — kontor is a
genuinely good substrate for the *operational subledger* tier of a
tax authority.** The shapes line up unusually well:

- PSCD's *Business Partner → Contract Account → Document → Open
  Item* maps to *Partner → Ledger → Transaction → Posting* with
  near-zero impedance.
- IRS *Tax Module + TC chain* maps to *per-(partner, tax-type)
  ledger + the seal-and-restate posting pattern*.
- GSTN *three-ledger model* is literally three kontor ledgers
  (`:ledger/cash`, `:ledger/credit`, `:ledger/liability`) with
  precedence rules expressed as `kontor.process` step-lists.
- "As filed vs as currently assessed" is the **canonical
  bitemporal example** — ADR-008 was designed for exactly this
  shape. No other consumer in the kontor ecosystem exercises
  bitemporality this hard; the tax-authority use case would be the
  best validation we could give it.
- Governance / approval / audit-doc / legal-hold / DSAR /
  retention — the Stage M primitives (ADR-049 to -052) read as if
  they were written for a tax-authority workload. The
  privilege-classification on `:audit-doc` (ADR-051) is a near-
  direct match for the attorney-client work-product distinction
  that audit examiners deal with daily.

**Minimum extension to ship a `kontor-tax-authority` companion:**

- ~6 new entity types: `:taxpayer` (role on `:partner`),
  `:tax-period`, `:return`, `:assessment`, `:audit-case`,
  `:risk-determination`.
- ~30 new attributes total (estimate; matches the size of ADR-039
  partner extensions or ADR-053 asset register).
- 1 new protocol: `ComplianceRiskProvider`.
- 1 new status-machine facet family: `:assessment/state`,
  `:return/state`, `:audit-case/state` (each ~6-8 transitions);
  approval-policy seeds for waivers / abatements / write-offs.
- 1 statute-of-limitations helper: `kontor.tax-authority.statute/
  ased-of` taking `:assessment/source` + filing date +
  `:audit-case/suspends-ased?` into account. Pure function.
- 1 new companion-level `:reason` vocabulary
  (`:audit-determined`, `:statute-expired`, `:taxpayer-amended`,
  `:cp2000-issued`, `:abatement-reasonable-cause`).

Size estimate: **comparable to `kontor-collections` (ADR-043)** —
roughly one Stage's worth of work, 4-6 weeks of focused
implementation, ~2000 LOC of schema + helpers + tests, plus per-
jurisdiction l10n companions.

**Companion-module vs separate-library vs fork: companion module.**
- *Not a fork.* Every primitive that matters (bitemporal,
  sealing, audit-doc, approval-policy, legal-hold, retention,
  status-machine, multi-ledger, multi-entity) is already in the
  kernel. A fork would diverge for no gain and lose upstream
  improvements.
- *Not a separate library either.* The companion sits in the same
  datahike instance per ADR-002 (just as `kontor-invoice` and
  `kontor-collections` do); cohabitation is the whole point. A
  large authority will want partner KYC, retention, DSAR,
  legal-hold compose with the tax-authority module without ETL.
- *Companion module*, sibling of `kontor-collections`. New
  directory `modules/tax-authority/`, schema namespaces
  `:taxpayer/* :tax-period/* :return/* :assessment/*
  :audit-case/* :risk-determination/*`. EPL-1.0 like the kernel.

**One caveat worth stating up-front.** kontor is the *right
substrate* for the **books**. It is not, and should not become, a
tax-authority *application* — the case-management UX, the
notice-rendering, the taxpayer self-service portal, the call-center
CRM, the data-lake/CRMS analytics, the secure file-transfer EDI for
inter-jurisdiction sharing all live above and beside the kernel,
mirroring the `kontor` ↔ `beleg` relationship for the commercial
side. Position the companion as "the tax authority's general
ledger and subledger of record, plus the governance scaffolding
around it" — not "a tax administration platform."

## Sources

- [SAP Public Sector Collection and Disbursement (SAP PSCD) — SAP Learning](https://learning.sap.com/courses/exploring-financial-contract-accounting-in-sap-public-sector-collection-and-disbursement/introducing-sap-public-sector-collection-and-disbursement-sap-pscd)
- [HMRC ETMP Regeneration Programme: Accounting Officer's Assessment Summary — GOV.UK](https://www.gov.uk/government/publications/enterprise-tax-management-platform-regeneration-programme-accounting-officers-assessment-summary/enterprise-tax-management-platform-regeneration-programme-accounting-officers-assessment-summary)
- [SAP Selected by HMRC to Lead Major Technology Transformation of the UK Tax System (Feb 2026)](https://news.sap.com/uk/2026/02/sap-selected-by-hmrc-to-lead-major-technology-transformation-of-the-uk-tax-system/)
- [HMRC Self Assessment Accounts API + View Self Assessment Account API](https://developer.service.hmrc.gov.uk/api-documentation/docs/api)
- [HMRC Making Tax Digital for Income Tax end-to-end service guide](https://developer.service.hmrc.gov.uk/guides/income-tax-mtd-end-to-end-service-guide/)
- [IRS IRM 25.6.1 Statute of Limitations Processes and Procedures](https://www.irs.gov/irm/part25/irm_25-006-001r)
- [IRS IRM 21.2.3 Transcripts (TXMODA, ENMOD)](https://www.irs.gov/irm/part21/irm_21-002-003r)
- [IRS Pub 6209 Section 8A — Master File Codes — Transaction, MF and IDRS](https://www.irs.gov/pub/irs-6209/6209sec8amasterfilecodes.pdf)
- [26 USC §6501 Limitations on assessment and collection](https://www.law.cornell.edu/uscode/text/26/6501)
- [GSTN electronic ledgers — ClearTax](https://cleartax.in/s/e-ledgers-under-gst)
- [Overview of Cash, Credit & Liability Ledgers under GST — Webtel](https://webtel.in/Blog/overview-of-cash-credit-and-liability-ledgers-under-gst-a-complete-guide-for-2025/3488)
- [Infosys / GSTN case study (Centralized Tax Administration Platform)](https://www.infosys.com/services/application-modernization/case-studies/centralized-administration-platform.html)
- [Receita Data: Evolution of Analytics in Receita Federal (BRICS case study)](https://tax.brics.br/Brazil_BRICS_Case_Study_Brazil.Paper.pdf)
- [Digital transformation at Receita Federal do Brasil — CIAT](https://www.ciat.org/an-overview-of-digital-transformation-at-receita-federal-do-brasil/?lang=en)
- [OECD Tax Administration 2023 (eleventh edition)](https://www.oecd.org/en/publications/tax-administration-2023_900b6382-en.html)
- [IMF/TADAT Tax Administration Diagnostic Assessment Tool — Program Document](https://www.tadat.org/content/dam/tadat/en/resources/pdf/ProgramDocument.pdf.pdf)
- [IMF Revenue Administration: Compliance Risk Management (TNM 2022/05)](https://www.imf.org/-/media/Files/Publications/TNM/2022/English/TNMEA2022005.ashx)
- [European Commission Compliance Risk Management in the Digital Era (2023 CRM Guide)](https://taxation-customs.ec.europa.eu/system/files/2024-01/2023_CRM_Guide.pdf)
- [OECD Amended Common Reporting Standard XML Schema (2024)](https://www.oecd.org/content/dam/oecd/en/publications/reports/2024/10/amended-common-reporting-standard-xml-schema_27960161/dd7ee57a-en.pdf)
- [IRS FATCA XML schemas and business rules for Form 8966](https://www.irs.gov/businesses/corporations/fatca-xml-schemas-and-business-rules-for-form-8966)
- [SIGTAS overview — Sogema Technologies](https://sogematech.com/en/sigtas)
- [World Bank: Understanding an Integrated Tax Administration IT System (P166640)](https://documents1.worldbank.org/curated/en/099050124135542353/pdf/P16664011d99e7091a10c1e4e763e47f3e.pdf)
- [IRS Direct File source code (open source, FSF blog)](https://www.fsf.org/blogs/community/irs-direct-file-released-as-free-software)

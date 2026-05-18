---
date: 2026-05-18
title: 84 — CA-CRA payroll adapter research-before (Stage R C4)
status: draft
audience: implementation agent for Stage R C4 (kontor-payroll-ca) — write this once, never re-derive
---

# 84 — CA-CRA payroll adapter research-before

Stage R C1 ships the protocol trio (`PayrollComputeProvider` /
`PayrollPostingBuilder` / `PayrollEmitProvider`) plus the
`PayrollFacts` data shape per
[[79-hr-payroll-stage-r-plan]] §4 (with the §9.6 compensation-as-
entity refinement from [[81-hr-data-model-gold-standards]] folded
in). Stage R C2 lands DE-DATEV-LODAS and Stage R C3 lands US-ADP-
export. **This note is the research-before for C4: the CA-CRA
adapter.**

Per note 79 §5.3, CA is "cheap follow-on" — the load-bearing CRA
XML emitters (T619 envelope + T4 slip) are **already shipped** in
[`modules/l10n-ca/src/kontor/l10n_ca/xml/`](../../modules/l10n-ca/src/kontor/l10n_ca/xml/),
verified against the published CRA 2026V4 XSDs. The Business
Number / RP program account validators are shipped in
[`identifiers.clj`](../../modules/l10n-ca/src/kontor/l10n_ca/identifiers.clj).
The GST/HST infrastructure is shipped in
[`gst_hst.clj`](../../modules/l10n-ca/src/kontor/l10n_ca/gst_hst.clj).
The CAD-defaulting chart loader is shipped in
[`chart.clj`](../../modules/l10n-ca/src/kontor/l10n_ca/chart.clj).

What's left to build for C4 is **glue** — three thin namespaces
(`compute_provider.clj`, `posting_builder.clj`, `emit_provider.clj`),
a wage-type catalog, a payroll-specific CoA tag set, the
`:payroll-facts` → T4 slip mapping that lets a year of pay-period
postings aggregate to a year-end T4 without manual re-entry, and
bilingual (EN/FR) audit-doc routing.

License posture (unchanged from CLAUDE.md + ADR-001):

- The CRA XML schemas (XSDs) are publicly published; the existing
  `xml/t4.clj` + `xml/t619.clj` are clean-room implementations
  against them — **not** lifted from any vendor.
- Algorithmic specs (Luhn check digit, T4 box meanings, PD7A
  totals) are facts; not copyrightable; spec quotations only.
- **No vendor API keys bundled**; consumer holds CRA My Business
  Account creds, ROE Web creds, Wagepoint OAuth secrets, etc.
- **No CRA paper forms bundled** beyond what the existing XML
  emitters already use; consumer prints/files paper PD7A from
  the CRA mail-out or My Business Account.

---

## §1 — TL;DR (the impl agent's cheat sheet)

1. **Half the work is done.** T619 envelope + T4/T4 Summary XML
   (verified against CRA 2026V4 XSDs) + BN/RP validators + GST/HST
   filings + CAD-defaulting chart all ship today. C4 is the
   payroll-side glue, not a from-scratch country build.

2. **Two reference compute providers, not one.** Wagepoint's
   self-serve API is partner-program-gated (no public OpenAPI as
   of 2026-05-18) but a `WagepointApiProvider` skeleton +
   `DeveloperApiAgreement` doc reference covers the SMB end.
   Ceridian Dayforce / Powerpay's CSV GL export is the
   enterprise-friendly reference. **Build both** as shipped
   adapters so the protocol surface is exercised under two very
   different shapes (REST polling vs file ingest).

3. **kontor does NOT emit PD7A.** PD7A is a **CRA-to-employer**
   statement-of-account form (CRA mails it; My Business Account
   shows it). The employer's job is to **remit** the source
   deductions on time — not to file the PD7A back. C4 ships the
   posting target (`:cra-payroll-payable` sub-accounts split by
   the three statutory buckets — income tax, CPP, EI — plus a
   per-RP discriminator) and a PD7A **remittance helper** that
   computes what's owed for a period. Actual remittance is via
   CRA payment channels (My Payment, online banking with the BN
   as the account ID, or paper voucher) — consumer-driven.

4. **kontor DOES emit T4 + T4 Summary annually** via the shipped
   `xml/t4.clj` and `xml/t619.clj`. C4's load-bearing new code is
   the `:payroll-facts` → `T4Slip` aggregator: a per-employee
   per-RP per-tax-year reduction over the year's `:payroll-facts`
   that emits the 15+ T4 boxes correctly (box 14 employment income,
   16/16A CPP/CPP2, 17/17A QPP/QPP2 [QC only], 18 EI premium, 20
   RPP, 22 income tax, 24 EI insurable earnings, 26 CPP/QPP
   pensionable earnings, 40 taxable benefits, 44 union dues, 45
   dental, 46 charitable donations, 52 pension adjustment, 55/56
   QPIP [QC]).

5. **kontor does NOT emit ROE.** ROEs go via ROE Web (Service
   Canada, not CRA). They are produced by the payroll engine
   (ADP, Ceridian, Wagepoint all automate this) using up to 53
   weeks of prior insurable earnings + 27 pay-periods of hours.
   kontor's responsibility on termination is to emit a
   `:termination-event` audit-doc containing the data the engine
   needs (last pay-period, reason code per Block 16, accumulated
   insurable hours, accumulated insurable earnings by pay period)
   — **never to generate the ROE itself**. The
   `PayrollExtractXmlV2.xsd` / .BLK format that ROE Web's bulk
   upload uses is a payroll-engine output, not a kontor one.

6. **Three statutory deduction buckets, never collapsed.** For PD7A
   accuracy + audit, kontor keeps income tax, CPP (employee + CPP2
   + employer share), and EI (employee + employer 1.4x) in
   **separate `:cra-payroll-payable-{itx,cpp,ei}` accounts** —
   never a single "CRA payable" account. Consumer remits the
   aggregate but the breakdown is what the PD7A and the year-end
   T4 Summary need to be reconcilable.

7. **Program-account routing on `:account-tag/program-account RP0001`.**
   A business with two divisions (separate RP accounts) keeps
   payroll postings cleanly separable per RP via this tag. The
   `:identifiers.clj` validator already accepts RP-program-suffixed
   BN15 strings. The T4/T4 Summary emitter already keys on the
   employer BN, so the per-RP aggregator is just a filter +
   group-by.

8. **Vacation pay accrues every pay period.** ESA across provinces
   requires 4% (years 1–4) / 6% (year 5+) of wages set aside as
   vacation pay liability; pay-out hits a Liability account when
   vacation is taken. The pay-period posting builder emits a
   `:vacation-pay/accrue` line every period; the rate is
   per-employment per-jurisdiction. Consumer-supplied via
   `:employment/vacation-rate` (decimal 0.04 / 0.06 / higher per
   contract).

9. **QC is C4.1, not C4.** QPP replaces CPP for QC employees;
   Revenu Québec receives a parallel **RL-1 slip + RL-1 Summary**
   filing; QPIP premiums replace the federal-EI parental-leave
   portion (EI rate is reduced for QC employees to 1.31% vs 1.63%
   federally). C4 ships T4 boxes 17/17A/55/56 as
   *passthrough-when-QC* — the values flow correctly if QC inputs
   come in — but the RL-1 XML emitter and the Revenu Québec
   transmission flow defer to C4.1.

10. **Bilingual via `:audit-doc/language`, not `:category` overload.**
    CRA T619 takes a `lang_cd` (`E`/`F`); RL-1 documents are
    French by Revenu Québec convention. Recommend a small new
    `:audit-doc/language` slot (kw — `:en`/`:fr`/`:bilingual`) so
    the `:audit-doc/category :payroll-filing` rows route to the
    right correspondent. Single attr add, no kernel ADR needed
    (open-set per ADR-051 + ADR-067-style consumer freedom).

---

## §2 — CA payroll engines — ComputeProvider targets

Stage R's architectural commitment is that kontor **consumes** an
engine's gross-to-net result, never re-implements jurisdictional
math (note 79 §1 + §5.1). CA payroll math (T4032 deduction tables,
CPP/CPP2 phase-in, QC-vs-rest-of-Canada, multi-province withholding,
TD1-form-driven personal credits, federal+provincial bracket math)
is *exactly* the kind of thing the engine vendors maintain. The C4
adapter consumes their output and posts.

### 2.1 — Wagepoint (SMB, REST API via partner program)

**Market position.** Cloud-native, SMB-only (Wagepoint explicitly
targets businesses with <50 employees), founded 2013. ~25k
customers as of 2025 per Capterra. Bookkeeper-channel-heavy
(integrates natively with QuickBooks Online, Xero, FreshBooks).
Acquired by Intuit's "Advanced Payroll Solution" partnership track
in 2024 (Intuit's developer forum confirms there's no public API
exposure of the Wagepoint engine yet, even though the engine
powers QBO Advanced Payroll Canada).

**API.** Wagepoint runs a [Developer API Agreement](https://wagepoint.com/people/developer-api-agreement)
program — the API is real but partner-program-gated; not openly
documented as of 2026-05-18. The partner program gives access to
`Wagepoint Developers Tools`. **For C4: build the adapter
skeleton against a documented surface** — `compute-payroll` calls
`(get-payroll-run pp-id)` returning the same per-employee gross /
deduction / employer-contribution shape as the CSV adapters — and
mark the namespace `^:wagepoint/requires-partner-approval` in a
top-level docstring. A consumer with partner approval wires their
credential into the provider config.

**Pros.** REST, JSON, OAuth — clean. Modern. Heavy Canadian
penetration. Hosted on AWS Canada region.

**Cons.** No public docs (partner program required). API surface
is opaque from outside.

**Recommendation.** Ship a `WagepointApiProvider` skeleton with a
clearly-marked TODO for the live API call wiring; document the
partner-program path. Useful as a structural reference even before
a real Wagepoint partner consumer surfaces.

### 2.2 — Ceridian Dayforce / Powerpay (enterprise + SMB, CSV GL export)

**Market position.** Dayforce is the dominant Canadian enterprise
HCM (Ceridian was a Canadian company before its 2024 rebrand to
Dayforce, Inc.); Powerpay is the SMB sibling targeting <100-
employee businesses. Powerpay alone has 45k+ Canadian SMB
customers per the [Powerpay product page](https://www.ceridian.com/ca/products/powerpay).
Combined, Ceridian/Dayforce is arguably the single largest
Canadian-payroll-engine ecosystem.

**GL export shape.** Dayforce ships a "Payroll Data Export" — the
admin schedules a per-pay-group "GL" export which produces a CSV
(or fixed-width text) keyed to per-pay-element GL account
mappings configured inside Dayforce. Powerpay has a similar
"Journal Entry" export with a one-click flow to Xero / QuickBooks
Online / Sage. Both are pull-not-push from the engine.

**Field shape (typical Dayforce GL CSV).** Per row: pay-date,
pay-group, GL account code, debit, credit, employee external-ID,
pay-element code, optional cost-center / location. The schema is
configurable per customer (admin-defined column ordering) — so
the C4 adapter takes a `column-mapping` config map keyed by
external column name → kontor pay-element-kind.

**Pros.** Stable, well-documented, customer can configure column
shape. No live API auth required — file lands in SFTP / email /
download. Excellent fit for the kontor posture (engine
authoritative, kontor records).

**Cons.** Per-customer column variation means the adapter is a
parser-with-config rather than a parser-with-fixed-schema.

**Recommendation.** Make `CeridianDayforceGlCsvProvider` the
**reference CSV adapter** for C4. Build it as the
`PayrollComputeProvider` that exercises every code path; the
Wagepoint API adapter then mirrors the same shape.

### 2.3 — ADP Canada (Workforce Now + Run Canada)

**Market position.** Second-largest CA payroll engine after
Ceridian. Workforce Now is mid-market (200–1000 employees), Run
Canada is SMB.

**GL export.** Both produce a "General Ledger Interface" (GLI)
CSV — fixed 10-column schema per row (client code, GL account,
description, fiscal date, amount, debit/credit indicator,
employee external-ID, pay-element, cost-center, run-ID). This is
the same GLI shape kontor's Stage R C3 (US-ADP) consumes, with
Canada-specific differences in the pay-element codes (CPP not
SS, EI not FUTA, etc.).

**Pros.** Identical CSV shape across US + CA; C3 work largely
transfers to CA with a pay-element-code re-mapping table.

**Cons.** GLI is a one-way export (no inbound updates), no live
API for consumption (live API exists separately as ADP
"DataConnector" / ADP Marketplace — but requires ADP partner
certification).

**Recommendation.** Reuse the C3 `AdpGliCsvProvider` skeleton with
an `ca-adp-pay-element-codes.edn` lookup. Don't build a separate
namespace; pass `:ca-mode? true` to the existing provider.

### 2.4 — Other engines (Payworks, Knit People, QBO Payroll, Easypay)

| Engine | Niche | Adapter shape | C4 ship status |
|---|---|---|---|
| Payworks | Mid-market Canadian | Journal-entry export to QBO/Xero; no public API | Defer to consumer demand |
| Knit People | SMB modern | API exists, unified through getKnit; pushes JE to QBO/Xero | Defer |
| QuickBooks Online Payroll Canada (Intuit) | SMB; uses Wagepoint engine | Internal GL post (not a kontor target — if customer is on QBO Payroll, they're posting to QBO) | Out of scope |
| Easypay Premier | Legacy on-prem, McDonald's / Canadian Tire franchisees | ASCII data file export | Defer; document only |

**Recommendation.** Ship **two reference implementations** (Ceridian
Dayforce CSV + ADP GLI CSV-with-CA-codes) in C4. Wagepoint API
provider lands as a third when a partner-program consumer
surfaces. The other engines are documentation-only in the C4 docs
("here's how a Payworks adapter would land: extend
`PayrollComputeProvider` and follow the Ceridian template").

### 2.5 — kontor-side adapter file tree (recommended)

```
modules/kontor-payroll-ca/
  deps.edn
  src/kontor/payroll_ca/
    compute_provider/
      ceridian_dayforce_csv.clj     ; reference CSV adapter
      adp_gli_csv.clj               ; reuses C3's structure + CA codes
      wagepoint_api.clj             ; skeleton; partner-program-gated
    posting_builder.clj             ; pay-element → CA CoA mapping
    emit_provider.clj               ; PD7A remittance helper + T4 emit
    wage_types.clj                  ; the catalog (see §10)
    chart.clj                       ; payroll-specific account tags
                                     ; on top of l10n-ca/chart.clj
    coa_starter.edn                 ; resource — wage account starter set
    pay_element_codes/
      ceridian_dayforce.edn
      adp_ca.edn
      wagepoint.edn
    bn_routing.clj                  ; per-RP filtering helpers
  test/kontor/payroll_ca/
    fixtures/
      ceridian_sample_3employee.csv
      adp_sample_3employee.csv
      wagepoint_sample_run.edn
      bilingual_hire.edn            ; EN + FR hire fixture (§10)
    end_to_end_test.clj             ; 12-month run → year-end T4 + T4 Sum
```

---

## §3 — PD7A monthly remittance — the kontor posting target

### 3.1 — What PD7A actually is

PD7A is **CRA-to-employer** correspondence — not an employer-filed
form. Per [Canada.ca: PD7A — Statement of Account for Current Source
Deductions](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-overview/statement-account-current-source-deductions-regular-quarterly-remitters.html):
the PD7A is CRA's statement to the employer confirming what was
remitted, what's outstanding, the remitter type, and the next
remittance due date. Two variants:

- **PD7A** (paper, mailed monthly/quarterly) — regular + quarterly
  remitters.
- **PD7A(TM)** (accelerated) — Threshold-1 / Threshold-2
  remitters; remitted more frequently than monthly.

PD7A also includes a tear-off **remittance voucher** with a
micro-encoded line, used for paying at a Canadian financial
institution. Photocopies / faxes are not accepted because the
micro-encoding is what posts payment to the correct BN/RP account.

**Critical.** The employer **does not file** the PD7A. The
employer **remits** the source deductions and **may file a "PD7A
reply"** (e.g., a nil remittance via TeleReply or My Business
Account if no deductions for the period). After CRA receives the
remittance, CRA generates the next PD7A as a statement of account.

### 3.2 — Remitter type → remittance schedule

Per [Canada.ca: How and when to remit (pay) — Types of remitters](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-more-information.html)
and the [Workzoom 2026 guide](https://www.workzoom.com/blog/payroll-remittance-cra-2026/):

| Type | Avg monthly withholding (AMWA) | Remit by |
|---|---|---|
| Quarterly | <C$3,000 (new small employers + perfect compliance history) | 15th of the month after each calendar quarter |
| Regular | ≤C$25,000 | 15th of the month following pay |
| Accelerated Threshold 1 | C$25,000–$99,999 | Paydays 1–15 by 25th of same month; paydays 16–end-of-month by 10th of next month |
| Accelerated Threshold 2 | ≥C$100,000 | Within 3 working days after each weekly bucket (1–7, 8–14, 15–21, 22–end) |

AMWA is computed from the **second prior calendar year**.

### 3.3 — kontor's PD7A surface: posting target + remittance helper

**Recommendation: kontor posts the deductions to typed liability
accounts; emits a "PD7A remittance" helper that totals what's due
for a period; never emits a PD7A "form" because there isn't one
the employer files.**

Three statutory liability accounts (with optional per-RP
discriminator via `:account-tag/program-account`):

```clojure
;; Resource: coa_starter.edn (extends l10n-ca/chart.edn) — example rows
{:code "2310"
 :path "Liabilities / Payroll / CRA — Income tax withheld"
 :name "Income tax payable (CRA RP0001)"
 :type :liability
 :tags [:ca-payroll-itx :ca-cra-pd7a]}

{:code "2320"
 :path "Liabilities / Payroll / CRA — CPP (employee + employer + CPP2)"
 :name "CPP payable (CRA RP0001)"
 :type :liability
 :tags [:ca-payroll-cpp :ca-cra-pd7a]}

{:code "2330"
 :path "Liabilities / Payroll / CRA — EI (employee + 1.4× employer)"
 :name "EI payable (CRA RP0001)"
 :type :liability
 :tags [:ca-payroll-ei :ca-cra-pd7a]}
```

Posting builder credits these three (+ wages-payable, +
vacation-pay-liability) per the §7 pay-period shape.

The **PD7A remittance helper** (a function in `emit_provider.clj`):

```clojure
(defn pd7a-period-due
  "Sum the three statutory CRA payables (itx + cpp + ei) for a
   remittance period, optionally filtered to an :account-tag/program-account.
   Returns {:itx Money :cpp Money :ei Money :total Money :pay-dates [...]}
   suitable for a remittance voucher + audit-doc of what was paid."
  [conn {:keys [period-start period-end rp-account as-of-tx as-of-valid]}]
  ...)
```

Output drops into an `:audit-doc/category :payroll-filing` row with
`:audit-doc/title "CRA remittance for RP0001 — 2026-03"` so the
audit chain shows what was computed; the consumer remits via My
Payment / online banking / paper voucher and records the cash
payment as a separate `:transaction` debiting the three payable
accounts and crediting bank.

### 3.4 — What kontor does NOT do for PD7A

- Does **not** emit a PD7A XML / form (no such artifact exists
  for the employer to file).
- Does **not** auto-remit. Consumer holds the CRA payment
  channel credentials.
- Does **not** assign the remitter type — that's CRA-determined
  from AMWA history; consumer carries the type as configuration
  (`:cra-remitter/type :regular | :quarterly | :accel-t1 |
  :accel-t2`) for the remittance-helper to know which schedule
  to use when computing due dates.

---

## §4 — Program-account routing on Business Number (BN)

### 4.1 — The structure (already shipped in `identifiers.clj`)

Every CA business has a 9-digit BN (Luhn-validated). CRA
**program accounts** are 15-character composite identifiers:
`<BN9><PROGRAM><REFERENCE>` where PROGRAM is the 2-letter program
code and REFERENCE is a 4-digit running suffix (`0001`, `0002`, …).

Per [identifiers.clj](../../modules/l10n-ca/src/kontor/l10n_ca/identifiers.clj#L151-L159):

```
RT — GST/HST (already used in tax filings)
RP — Payroll source deductions (T4, PD7A)
RC — Corporate income tax (T2)
RM — Import / Export (customs)
RR — Registered charity (T3010)
RZ — Information returns (T5, T5018, T4A, T5008, etc.)
```

**Why this matters for payroll.** A business with two divisions
may have **two RP accounts** (`123456789RP0001` for Division A,
`123456789RP0002` for Division B). Each RP files its **own** T4
return, its **own** T4 Summary, and **its own** PD7A remittance
schedule. The CRA treats them as separate filers under one BN.

### 4.2 — kontor handling

Two routing tactics, used together:

**(a) Tag postings with the RP via `:account-tag/program-account`.**

The existing `:account-tag/*` schema already supports
country-keyed tags. Recommend a new `:account-tag/program-account`
(string, optional) attribute that carries the BN15 of the
governing RP:

```clojure
;; In kontor-payroll-ca/chart.clj or via consumer install
[:account-tag/program-account "123456789RP0001"]
```

Postings against the wage / payable accounts inherit the tag via
the account, OR carry the tag directly on `:posting/tags` for
shared accounts (e.g., shared wages-payable that ledger-splits by
RP via posting-time tagging).

**(b) Filter T4/T4 Summary / PD7A aggregators by RP.**

The aggregators query payroll-facts joined-to-postings filtered by
the RP tag, then key the T4 emitter on `:t4/employer-bn`
already-accepting the full BN15 (see
[xml/t4.clj `:t4/employer-bn`](../../modules/l10n-ca/src/kontor/l10n_ca/xml/t4.clj#L136)).

**RZ / RC interactions.** RZ is for non-payroll info returns
(T5018 contractor slips, T5 investment income); already-shipped
emitters in `xml/t5.clj` + `xml/t5018.clj` use the same envelope.
RC is corporate tax — out of payroll scope. RM is customs — out
of payroll scope.

### 4.3 — Validation already present

`(identifiers/valid-program-account? "123456789RP0001")` returns
true; `(identifiers/parse-program-account ...)` returns
`{:bn "123456789" :program "RP" :program-name "Payroll" :reference "0001"}`.
The T619/T4 emitters already validate that the BN15 is well-formed
(implicit via Luhn) and that the program is RP (`bnRPType` XSD
restriction).

C4 should add a small `bn_routing.clj` helper that:
- Resolves an `:employment/entity` → governing RP (via
  `:entity/payroll-rp` ref to a `:cra-rp-account` entity carrying
  the BN15 + remitter-type configuration).
- Aggregates per-RP for T4 + T4 Summary + PD7A helpers.

---

## §5 — T4 + T4 Summary annual cycle — wiring `:payroll-facts` → existing emitters

### 5.1 — The cycle

Per [CRA Employers' Guide RC4120](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/rc4120/employers-guide-filing-t4-slip-summary.html):

- **T4 slip** — per-employee per-RP per-tax-year. One per
  employment per year (multi-employment with different RPs =
  multiple T4s for the same person).
- **T4 Summary** — employer-side total per RP per tax-year, one
  per RP. Aggregates box 14 / 16 / 18 / 20 / 22 / 52 across all
  slips in the return.
- **T619** — CRA's "Electronic Transmittal" wrapper. One per
  submission, containing one or more T4 returns (each return =
  slips + summary for one RP).
- **Filing deadline** — **last day of February** following the tax
  year (March 2, 2026 for tax year 2025 since Feb 28 falls
  weekend). Per RC4120.
- **Electronic mandatory if >5 slips** per RC4120 and
  [CRA: File information returns electronically](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/t619-2026.html).
  Filed via Internet File Transfer (IFT) — upload XML against the
  `partXXxmlschm1-26-1.zip` schema (CRA XML Schema, version 1-26-1,
  effective Nov 2025 for 2026 filing season; the existing
  `xml/t4.clj` validates against the 2026V4 family — confirmed
  current at the date of this note).
- **Language code** — T619 requires `lang_cd` (`E` or `F`)
  per-submission since 2025; existing `xml/t619.clj` accepts
  `:submission/language :english | :french`. See §9.

### 5.2 — `:payroll-facts` → `T4Slip` mapping (the C4 deliverable)

The T4 slip's box list per [CRA: T4 Statement of Remuneration Paid 2026](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/t619-2026/t4-2026.html)
and the existing `xml/t4.clj` element coverage:

| T4 box | Element | Sums over `:payroll-facts` component-kind | Notes |
|---|---|---|---|
| 14 | `empt_incamt` | `:base-wage` + `:bonus` + `:overtime` + `:vacation-pay-paid-out` + `:taxable-benefit` + all `:other-employment-income` | Gross employment income (Box 14 = sum of all taxable income subject to income tax per [CRA T4 Box 14](https://www.taxprep.com/assistance/TF/2021/v20/en-ca/Content/Forms/T4box14.htm)). Includes taxable benefits even though they also appear in Box 40 |
| 16 | `cpp_cntrb_amt` | `:employee-cpp` (≤ CPP1 max) | 2026 max C$4,230.45 |
| 16A | `cppe_cntrb_amt` | `:employee-cpp2` | NEW since 2024. 2026 max C$416.00 (4% × (YAMPE − YMPE) = 4% × $10,400) |
| 17 | `qpp_cntrb_amt` | `:employee-qpp` (QC employees only) | C4.1 — see §8 |
| 17A | `qppe_cntrb_amt` | `:employee-qpp2` (QC only) | C4.1 |
| 18 | `empe_eip_amt` | `:employee-ei` | 2026 federal rate 1.63% (QC 1.31%); employee max C$1,123.07 federal |
| 20 | `rpp_cntrb_amt` | `:employee-rpp-contribution` | Registered Pension Plan only (not group RRSP) |
| 22 | `itx_ddct_amt` | `:income-tax-withheld` (federal + provincial combined per CRA T4032) | Federal + provincial is a single withholding from CRA's perspective |
| 24 | `ei_insu_ern_amt` | per-pay-period `:ei-insurable-earnings` (capped) | NOT gross — capped per period at MIE |
| 26 | `cpp_qpp_ern_amt` | per-pay-period `:cpp-pensionable-earnings` (capped) | NOT gross — capped per period at YMPE-pro-rated; if YAMPE+ in last periods, still capped at YAMPE |
| 28 | `cpp_qpp_xmpt_cd` / `ei_xmpt_cd` | derived from `:employment/cpp-exempt?` / `:employment/ei-exempt?` | Existing `xml/t4.clj` already takes `:t4/cpp-qpp-exempt?` + `:t4/ei-exempt?` bools |
| 40 | (no current element — add) | `:taxable-benefit` (all non-cash) | "Other Information" box; not in `T4_AMT` per existing emitter — may need extension if 40 commonly carried |
| 44 | `unn_dues_amt` | `:union-dues` | Already supported |
| 45 | (dental — new since 2023) | from `:employment/dental-coverage-tier-code` | Code 1–5 per [Knit People CPP2 box 16A guide](https://help.knitpeople.com/hc/en-us/articles/16416929732759-New-Box-on-the-T4-for-Second-Canada-Pension-Plan-CPP-Contribution-Reporting). MAY need adding to existing `T4_AMT` element list |
| 46 | `chrty_dons_amt` | `:charitable-donation-payroll` | Already supported |
| 52 | `padj_amt` | `:pension-adjustment` (RPP + DPSP combined per [CATaxTools T4 Box 52](https://catax.tools/t4-box/52/)) | Already supported |
| 55 | `prov_pip_amt` | `:employee-qpip` (QC only) | C4.1 |
| 56 | `prov_insu_ern_amt` | per-pay-period `:qpip-insurable-earnings` (QC only) | C4.1 |
| 29 | (employment code — Other Information) | from `:employment/employment-code` | E.g. "11" for placement agency self-employed, "13" for fishers; per [Powerpay Box 29 guide](https://help.powerpay.ca/en/pphelp/Content/YearEnd/YearEndGuide/CompleteBox29T4.htm) |

**Aggregator signature (the load-bearing C4 function):**

```clojure
(ns kontor.payroll-ca.emit-provider)

(defn payroll-facts->t4-slip
  "Reduce a year of :payroll-facts for one (person × RP × tax-year)
   into the input shape that kontor.l10n-ca.xml.t4/slip->element
   accepts.

   Returns:
     {:t4/employer-bn …       ; the RP BN15
      :t4/sin …               ; from :person/national-id (typed as SIN)
      :t4/employee {…}        ; from :person/family-name + :person/given-name
      :t4/employee-address {…} ; from :person address audit-doc
      :t4/province-of-employment …
      :t4/cpp-qpp-exempt? …
      :t4/ei-exempt? …
      :t4/report-type :original  ; or :amended on re-emit
      :t4/boxes {:box-14 Money :box-16 Money …}}"
  [conn {:keys [person-eid rp-bn15 tax-year as-of-tx as-of-valid]}]
  ...)

(defn build-t4-return-submission
  "All-employees aggregator: for one RP, build the
   {:t619 :t4-summary :slips} shape kontor.l10n-ca.xml.t4/submission
   accepts. Sums per-slip Box 14/16/18/20/22/52 into the T4 Summary.

   Returns a clojure.data.xml-ready value; consumer calls
   `(xml/emit-string (xml/t4/submission ...))` for the IFT upload."
  [conn {:keys [rp-bn15 tax-year transmitter-config language]}]
  ...)
```

**Province-of-employment** comes from
`:employment/province-of-employment` (BC/ON/AB/QC/…). Multi-province
employees over a year (rare but possible — note 73 Theme B P1)
produce **multiple T4s per RP** (one per province-of-employment),
which is CRA's documented handling per the RC4120 guide.

### 5.3 — Amendments + cancellations

The existing `xml/t4.clj` accepts `:t4/report-type :original |
:amended | :cancelled`. The aggregator surface should support
re-running for a prior tax year with `:report-type :amended` once
the slip was already filed — and emit only the diffs / the full
amended slip per CRA's amendment guide (full slip re-emit is
required, per RC4120; the emitter does the right thing).

T4 Summary `rpt_tcd` allows `O`/`A`/`M` (`:original`/`:amended`/
`:modified`) but **not** `C` per existing emitter — cancelled
slips need an amended-with-zero-amount summary.

---

## §6 — ROE Web termination cycle — what kontor surfaces, what the engine handles

### 6.1 — What an ROE is and who files it

Per [Canada.ca: Record of Employment (ROE)](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/ei-roe.html)
and the [ROE Web user guide](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/reports/completing-single-roe.html):

- **ROE = Record of Employment.** Required on every "interruption
  of earnings" — termination, layoff, leave (parental, sick,
  unpaid), reduction below 60% threshold.
- **Filed with Service Canada**, not CRA. Different agency,
  different system (ROE Web), different XSD
  (`PayrollExtractXmlV2.xsd`, .BLK file extension), different
  upload mechanism (bulk file upload via SFTP or HTTP via ROE Web
  for up to 1200 ROEs at a time).
- **Deadline.** 5 calendar days after the end of the pay period in
  which the interruption occurred (paper); ROE Web variants give
  up to 15 days after the first day of the interruption.

### 6.2 — Block 15 (the load-bearing block)

Per [Canada.ca: Completing a Single ROE](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/reports/completing-single-roe.html):

- **Block 15A** — Total Insurable Hours (last 27 pay periods if
  weekly, last 53 if monthly — depends on the pay-period type in
  Block 6).
- **Block 15B** — Total Insurable Earnings (last 14 / 27 / 53
  consecutive pay periods depending on pay-period type). **ROE
  Web auto-calculates** this from the values entered in 15C — the
  employer does not directly enter 15B.
- **Block 15C** — Per-pay-period insurable earnings, last 27
  consecutive pay periods (or 14 weekly / 53 if needed for
  benefit calc); ROE Web shows only the pay periods it actually
  requires given Blocks 6/10/11/12.

Other load-bearing blocks: Block 6 (pay period type), Block 11
(last day worked), Block 12 (final pay period end-date), Block 16
(reason for issuing ROE — A=shortage of work, B=strike or lockout,
D=illness, E=quit, K=other, M=dismissal, etc.), Block 17 (separation
payments — severance, vacation pay-out, retiring allowance).

### 6.3 — kontor's job is data-surfacing, not ROE-emission

Per the project recommendation in the task brief: **don't generate
ROEs; emit a `:termination-event` audit-doc with the data the
engine needs.**

Rationale:
- ADP, Ceridian, Wagepoint, Knit, Payworks all generate ROEs
  automatically — that's table-stakes for a payroll engine in
  Canada. The engine already has the pay-period history (its job)
  and the insurable-earnings/insurable-hours rolling windows.
- The ROE Web XML (.BLK) is a payroll-engine emission, not a
  kontor emission. The engine's bulk-upload to ROE Web is a
  closed loop between the engine and Service Canada.
- kontor's value-add is the **audit chain** that the engine can't
  see — the legal hold, the audit-doc, the approval-policy on
  termination, the link to severance posting.

**Recommendation: ship a `terminate-employment!` helper that:**

1. Status-machine transitions `:employment/state` from `:active` to
   `:terminated` (recording `:status-history` per ADR-034).
2. Sets `:employment/end-date` to the last-day-worked.
3. Sets `:employment/termination-reason` (open-set kw, mappable to
   ROE Block 16 codes — `:shortage-of-work | :quit | :dismissal |
   :illness | :leave | :retirement | :death | :other`).
4. Emits a `:termination-event` audit-doc with
   `:audit-doc/category :hr-termination` containing the structured
   data the engine needs (last 27/53 pay periods of insurable
   earnings, insurable hours, separation payments planned).
5. Triggers the severance-pay-posting if severance is owed (via
   `:transaction/state-machine` — note 79 §2.4 + ADR-061).
6. **Optionally** writes a `:payroll-event/roe-trigger` row that
   the consumer's engine integration polls for and acts on.

**What the implementation should NOT include:**
- No .BLK XML emitter.
- No ROE Web upload code.
- No insurable-earnings-rolling-window calculator (the engine
  does this from pay-period records — kontor has those, but
  emitting them in ROE format is duplication of the engine's job).

**Future extension (post-C4).** If a kontor consumer explicitly
ships *without* an engine (full-DIY payroll, e.g., a single-owner
S-Corp-equivalent), a separate `kontor-payroll-ca-diy` module
could add the ROE emitter. **Out of C4 scope; document only.**

---

## §7 — CPP / EI / income-tax pay-period posting shape

### 7.1 — Typical CA payroll journal entry

Per [Bookkeeping Essentials manual payroll](https://www.bookkeeping-essentials.com/manual-payroll.html)
and the [VCC Learning Centre Payroll Accounting handout](https://learningcentre.vcc.ca/media/vcc-library/content-assets/learning-centre/worksheets/by-coursex2fprogram/business/Payroll_Accounting.pdf),
a CA pay-period posting (per-employee or per-pay-group rolled up)
has this structure:

```
DR  Wages expense (gross)               5400  $5,000.00
DR  Employer CPP expense                5410    $246.75
DR  Employer EI expense                 5411    $114.94
DR  Vacation pay accrual expense        5412    $200.00 (4% of gross)
    CR  Income tax payable (CRA)        2310            $850.00
    CR  CPP payable (employee+employer) 2320            $493.50
    CR  EI payable (employee+1.4×employer) 2330         $196.18
    CR  Vacation pay liability          2340            $200.00
    CR  Wages payable (net)             2350          $3,821.07

Where (illustrative; not 2026-accurate):
- Employee CPP = (5000 - 3500/26) * 5.95% = ~$246.75
- Employer CPP = matching = $246.75
- Employee EI  = 5000 * 1.63% = $81.50
- Employer EI  = 81.50 * 1.4 = $114.10 (rounded as engine computes)
- Federal+provincial income tax (T4032 tables) = ~$850
- Net = 5000 − 850 − 246.75 − 81.50 = $3,821.75 (close — engine is authoritative)
```

The engine computes the gross-to-net; kontor posts the result.

### 7.2 — CA CoA wage-account map (starter chart, consumer-extensible)

The kontor-payroll-ca module ships a `coa_starter.edn` resource
that extends the kernel `kontor-l10n-ca/chart.edn` with a payroll
section. Recommended starter rows (consumer overrides as needed):

```clojure
;; Expense side
{:code "5400" :path "Expenses / Payroll / Wages and salaries"        :type :expense
 :tags [:ca-payroll-wages]}
{:code "5410" :path "Expenses / Payroll / Employer CPP / CPP2"       :type :expense
 :tags [:ca-payroll-er-cpp]}
{:code "5411" :path "Expenses / Payroll / Employer EI"               :type :expense
 :tags [:ca-payroll-er-ei]}
{:code "5412" :path "Expenses / Payroll / Vacation pay accrual"      :type :expense
 :tags [:ca-payroll-vacation-accrual]}
{:code "5413" :path "Expenses / Payroll / Employer RPP"              :type :expense
 :tags [:ca-payroll-er-rpp]}
{:code "5414" :path "Expenses / Payroll / Other benefits"            :type :expense
 :tags [:ca-payroll-benefits]}

;; Liability side (CRA-statutory + employee-payable)
{:code "2310" :path "Liabilities / Payroll / CRA — Income tax"       :type :liability
 :tags [:ca-payroll-itx :ca-cra-pd7a]}
{:code "2320" :path "Liabilities / Payroll / CRA — CPP / CPP2"       :type :liability
 :tags [:ca-payroll-cpp :ca-cra-pd7a]}
{:code "2330" :path "Liabilities / Payroll / CRA — EI"               :type :liability
 :tags [:ca-payroll-ei :ca-cra-pd7a]}
{:code "2340" :path "Liabilities / Payroll / Vacation pay liability" :type :liability
 :tags [:ca-payroll-vacation-liability]}
{:code "2350" :path "Liabilities / Payroll / Wages payable (net)"    :type :liability
 :tags [:ca-payroll-net-wages]}
{:code "2360" :path "Liabilities / Payroll / RPP contributions"      :type :liability
 :tags [:ca-payroll-rpp]}

;; QC-specific (added in C4.1)
{:code "2321" :path "Liabilities / Payroll / Revenu Québec — QPP"    :type :liability
 :tags [:ca-payroll-qpp :qc-rq-rl1]}
{:code "2331" :path "Liabilities / Payroll / Revenu Québec — QPIP"   :type :liability
 :tags [:ca-payroll-qpip :qc-rq-rl1]}
{:code "2311" :path "Liabilities / Payroll / Revenu Québec — income tax" :type :liability
 :tags [:ca-payroll-qc-itx :qc-rq-rl1]}
```

Code numbers are illustrative (consumer's CoA owns the real
codes); the `:account-tag/name` keys are the load-bearing
identifiers that filings + PD7A helpers + T4 aggregators query
against.

### 7.3 — Vacation-pay-accrual rule per province

Per [Ontario ESA Vacation Pay](https://www.ontario.ca/document/your-guide-employment-standards-act-0/vacation),
[Alberta Vacation Pay](https://www.alberta.ca/vacation-pay), and
the [Samfiru Tumarkin Vacation Pay Ontario 2026 guide](https://stlawyers.ca/blog-news/vacation-pay-in-ontario/):

- **Years 1–4:** 4% of gross wages (and 2 weeks vacation time).
- **Year 5+:** 6% of gross wages (and 3 weeks).
- **Federally regulated** (banks, telecom, interprovincial
  transport, airlines): 4% (years 1–5), 6% (years 5–9), 8%
  (years 10+) per the Canada Labour Code.
- **Saskatchewan:** 3 weeks (~5.77%) from day 1, rising to 4 weeks
  (~7.69%) at 10 years.
- **Quebec:** 4% to 6%, with sub-1-year exceptions.

**Recommendation.** Carry `:employment/vacation-rate` as a
decimal on the employment (or compute via
`(kontor.payroll-ca/vacation-rate {:province :on :years-of-service 6})`
when not stored). The posting builder multiplies it by gross each
pay period and accrues into `2340` (vacation pay liability). When
vacation is taken/paid out, debit `2340` and credit `2350` (net
wages payable) — `2340` is the running balance.

### 7.4 — Province-of-employment vs province-of-residence

Critical distinction per [CRA T4032 Payroll Deductions Tables](https://www.canada.ca/en/revenue-agency/services/forms-publications/payroll/t4032-payroll-deductions-tables/t4032oc-jan/t4032oc-january-general-information.html):

- **Province of employment** drives income-tax withholding tables
  (T4032-XX) and the T4 box 10 / `empt_prov_cd` element.
- **Province of residence** drives the employee's personal
  income-tax return at year-end (CRA refunds / collects the
  difference if the two differ).
- For remote employees: province-of-employment is generally the
  province where the employer "establishment" is that the
  employee reports to. Multi-state-style allocation does NOT
  apply at the kontor posting level — it's a single
  province-of-employment per T4.

**kontor records both** via `:employment/province-of-employment`
(load-bearing for the T4 + withholding) and `:person/province-of-
residence` (informational, used for benefits/disclosures).

---

## §8 — QC carve-out (QPP + RL-1) — defer to C4.1

### 8.1 — What's different for QC employees

Per [Workzoom: Quebec Payroll Compliance Guide](https://www.workzoom.com/blog/quebec-payroll-compliance-guide/)
and [Revenu Québec: RL Slips](https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/rl-slips/):

| Federal (rest of Canada) | Quebec equivalent |
|---|---|
| CPP (employee 5.95% + matching employer + CPP2 4%) | QPP (employee 6.40% + matching + QPP2 4%); 2026 max contribution C$4,550.40 |
| EI (1.63% employee, 1.4× employer) | EI reduced (1.31% employee for QC) + QPIP (Quebec Parental Insurance Plan) for parental-leave portion |
| Federal income tax withholding | Federal **+** Quebec provincial income tax withholding (Quebec collects its own) |
| T4 + T4 Summary to CRA | T4 + T4 Summary to CRA **AND** RL-1 + RL-1 Summary to Revenu Québec |
| PD7A to CRA monthly | TPZ-1015 to Revenu Québec monthly (parallel form) |
| Filing deadline last day of Feb (CRA) | Same Feb deadline for RL-1 (Revenu Québec) |

### 8.2 — Why defer to C4.1

QC requires:
- A parallel chart-of-accounts split (Revenu Québec payables
  separate from CRA payables) — substrate-friendly but adds 3
  starter accounts.
- An RL-1 XML emitter — separate XSD family from CRA T619; needs
  its own clean-room build matching Revenu Québec's published
  schema.
- An RL-1 Summary aggregator.
- A TPZ-1015 monthly remittance helper (analogous to PD7A but for
  Revenu Québec).
- The decision tree: federally-incorporated entity with QC
  employee → both CRA (T4) and Revenu Québec (RL-1) are required.

**Estimated C4.1 cost: ~3–4 days** on top of C4. **C4 ships
QC-passthrough** — T4 boxes 17/17A/55/56 are populated correctly
if the input `:payroll-facts` carries `:employee-qpp` /
`:employee-qpp2` / `:employee-qpip` component-kinds (they do, if
the engine is QC-aware) — so a QC employee on a federally-
incorporated employer gets a correct **T4** in C4. The **RL-1**
flow is the C4.1 deliverable.

### 8.3 — C4 documentation requirement

`emit_provider.clj` should `(when (some #(= :qc (:employment/
province-of-employment %)) employments) (log/warn "QC employees
detected — RL-1 filing not yet supported in this version; see
C4.1 roadmap"))` so consumers with QC employees aren't silently
missing a filing.

---

## §9 — Bilingual emit + audit-doc convention

### 9.1 — The need

CRA's T619 envelope requires `lang_cd` (`E` or `F`) per submission;
the existing `xml/t619.clj` honors this via `:submission/language
:english | :french`. Revenu Québec RL-1 documents are French by
convention.

For pan-Canadian customers with both EN + FR T4s in one filing
year, the kontor audit trail needs to record which artifact is
which language.

### 9.2 — Recommendation: add `:audit-doc/language`

A single new kernel-level enum-typed attribute:

```clojure
[K] :audit-doc/language    kw    one    ; :en | :fr | :bilingual; nil=:en default
```

Why a slot rather than derived-from-category?
- Same filing TYPE (T4 envelope) can be EN or FR depending on
  the employee's correspondence preference (CRA respects the
  employee's CRA-account language choice when distributing slips).
- A `:payroll-filing` category with an EN slip and an FR slip
  per the same RP+year is a valid, common case.
- DSAR / retention rules don't differ by language (whereas they
  do differ by category) — so language is orthogonal to
  category, just like ADR-051's privilege is orthogonal to
  category.

Per ADR-051's open-set posture this is a non-breaking addition.

### 9.3 — Routing in `emit_provider.clj`

The T4 submission builder takes `:language :en | :fr`; emits the
T619 with the right `lang_cd`; writes an `:audit-doc` row carrying
`:audit-doc/language :en` (or `:fr`) and `:audit-doc/category
:payroll-filing`. For consumers with split-language workforces,
two submissions get built — one EN, one FR — each with its own
audit-doc row.

For employee-facing T4 PDFs (the boxed paper-style form
employees receive), the same language slot routes the PDF
template selection (out of C4 scope but documented for C4.x: the
T4 PDF generator follows the same per-doc language slot).

### 9.4 — Cost

Single kernel attribute add. No ADR (open-set per ADR-051).
Consumer-side: zero migration cost (default `:en`).

---

## §10 — Concrete impl recommendations for C4

### 10.1 — C4 file tree (recap from §2.5)

```
modules/kontor-payroll-ca/
  deps.edn                                       ; deps: kontor + kontor-hr + kontor-l10n-ca
  src/kontor/payroll_ca/
    compute_provider/
      ceridian_dayforce_csv.clj                  ; ~250 lines parser + mapping
      adp_gli_csv.clj                            ; ~150 lines (reuse US C3 + ca-codes)
      wagepoint_api.clj                          ; ~100 lines skeleton + TODOs
    posting_builder.clj                          ; ~200 lines, pay-element → CoA
    emit_provider.clj                            ; ~300 lines
                                                 ; - pd7a-period-due
                                                 ; - payroll-facts->t4-slip
                                                 ; - build-t4-return-submission
                                                 ; - terminate-employment! → audit-doc
    wage_types.clj                               ; the catalog (§10.2)
    chart.clj                                    ; payroll account installer
    coa_starter.edn                              ; resource — §7.2 starter accounts
    pay_element_codes/
      ceridian_dayforce.edn                      ; engine wage-code → kontor kind
      adp_ca.edn
      wagepoint.edn
    bn_routing.clj                               ; per-RP helpers
    vacation.clj                                 ; per-province rate table
  test/kontor/payroll_ca/
    fixtures/
      ceridian_sample_3employee.csv
      adp_sample_3employee.csv
      wagepoint_sample_run.edn
      bilingual_hire.edn                         ; §10.3
    end_to_end_test.clj                          ; full year → T4 + T4 Summary
    pd7a_helper_test.clj
    bn_routing_test.clj
    vacation_test.clj
    qc_passthrough_test.clj                      ; assert C4.1 warning fires
```

### 10.2 — The wage-type catalog (EDN sketch)

`wage_types.clj` defines the canonical `:component-kind` open-set
extension for CA — what flows through `:payroll-facts/components`
and what the posting builder maps to accounts.

```clojure
(ns kontor.payroll-ca.wage-types
  "CA-specific :component-kind extensions. Open-set per ADR-071
   P2-71-2 and note 79 §2.4. Mapped to default CoA tags in
   `coa-starter.edn`; consumer-extensible via :ca/extras-map.")

(def standard-component-kinds
  "The set of pay-element kinds the CA posting builder recognizes
   out of the box. Each maps to an :account-tag/name (consumer can
   override the account mapping). See :ca-payroll-* tags in
   coa-starter.edn."
  {;; EARNINGS (debit gross wages expense, credit net wages payable)
   :base-wage              {:account-tag :ca-payroll-wages}
   :overtime               {:account-tag :ca-payroll-wages}
   :bonus                  {:account-tag :ca-payroll-wages}
   :commission             {:account-tag :ca-payroll-wages}
   :vacation-pay-paid-out  {:account-tag :ca-payroll-wages}
   :statutory-holiday-pay  {:account-tag :ca-payroll-wages}
   :retroactive-pay        {:account-tag :ca-payroll-wages}
   :severance              {:account-tag :ca-payroll-wages}
   :retiring-allowance     {:account-tag :ca-payroll-wages}

   ;; TAXABLE BENEFITS (debit gross wages expense; T4 Box 14 + Box 40)
   :taxable-benefit-auto              {:account-tag :ca-payroll-wages :t4-box-40-include true}
   :taxable-benefit-group-term-life   {:account-tag :ca-payroll-wages :t4-box-40-include true}
   :taxable-benefit-parking           {:account-tag :ca-payroll-wages :t4-box-40-include true}
   :taxable-benefit-other             {:account-tag :ca-payroll-wages :t4-box-40-include true}

   ;; EMPLOYEE DEDUCTIONS (credit CRA/RQ payables)
   :income-tax-withheld    {:account-tag :ca-payroll-itx       :t4-box :box-22}
   :employee-cpp           {:account-tag :ca-payroll-cpp       :t4-box :box-16}
   :employee-cpp2          {:account-tag :ca-payroll-cpp       :t4-box :box-16a}
   :employee-ei            {:account-tag :ca-payroll-ei        :t4-box :box-18}
   :employee-rpp-contribution {:account-tag :ca-payroll-rpp    :t4-box :box-20}
   :union-dues             {:account-tag :ca-payroll-union     :t4-box :box-44}
   :charitable-donation-payroll {:account-tag :ca-payroll-charity :t4-box :box-46}
   :garnishment            {:account-tag :ca-payroll-garnishment}
   :voluntary-deduction    {:account-tag :ca-payroll-other-deduction}

   ;; QC employee deductions — C4.1 emit, C4 passthrough
   :employee-qpp           {:account-tag :ca-payroll-qpp       :t4-box :box-17 :requires-qc? true}
   :employee-qpp2          {:account-tag :ca-payroll-qpp       :t4-box :box-17a :requires-qc? true}
   :employee-qpip          {:account-tag :ca-payroll-qpip      :t4-box :box-55 :requires-qc? true}
   :employee-qc-itx        {:account-tag :ca-payroll-qc-itx    :requires-qc? true}

   ;; EMPLOYER ACCRUALS (debit employer expense, credit payable)
   :employer-cpp           {:account-tag :ca-payroll-er-cpp}    ; matched to employee-cpp
   :employer-cpp2          {:account-tag :ca-payroll-er-cpp}
   :employer-ei            {:account-tag :ca-payroll-er-ei}     ; 1.4 × employee-ei
   :employer-qpp           {:account-tag :ca-payroll-er-cpp :requires-qc? true}
   :employer-qpip          {:account-tag :ca-payroll-er-ei  :requires-qc? true}
   :employer-rpp-match     {:account-tag :ca-payroll-er-rpp}
   :employer-eht           {:account-tag :ca-payroll-er-eht}    ; Ontario / BC / MB employer health tax
   :employer-wsib          {:account-tag :ca-payroll-er-wsib}   ; Workers comp board (per-province)

   ;; ACCRUAL (per pay period, on top of base wage)
   :vacation-pay-accrual   {:account-tag :ca-payroll-vacation-accrual
                            :accrues-to :ca-payroll-vacation-liability}

   ;; INSURABLE EARNINGS (NOT a posting; carried for T4 Box 24/26/56)
   :ei-insurable-earnings    {:t4-box :box-24 :posts? false}
   :cpp-pensionable-earnings {:t4-box :box-26 :posts? false}
   :qpip-insurable-earnings  {:t4-box :box-56 :posts? false :requires-qc? true}

   ;; META
   :pension-adjustment     {:t4-box :box-52 :posts? false}
   :dental-coverage-code   {:t4-box :box-45 :posts? false}})
```

The `:t4-box` slots are what the year-end T4 aggregator (§5.2)
keys on. The `:posts?` slot distinguishes posting-generating
components from carry-only (T4-box-only) components.

### 10.3 — The bilingual hire test fixture

A canonical end-to-end test fixture that exercises EN + FR
correspondence routing for one hire:

```clojure
;; test/kontor/payroll_ca/fixtures/bilingual_hire.edn
{:entities
 [{:entity/legal-name "Acme Canada Inc."
   :entity/country-code "CA"
   :entity/payroll-rp "123456782RP0001"           ; Luhn-valid
   :entity/cra-remitter-type :regular}]

 :rps
 [{:cra-rp-account/bn15 "123456782RP0001"
   :cra-rp-account/transmitter-contact
   {:name "Alice Payroll"
    :phone "604-555-0100"
    :email "payroll@acme.ca"}}]

 :hires
 [{:person/given-name "Sophie"
   :person/family-name "Lavoie"
   :person/national-id-doc-sin "123456782"        ; valid SIN
   :person/preferred-correspondence-language :fr
   :employment/entity "Acme Canada Inc."
   :employment/start-date #inst "2026-01-15"
   :employment/province-of-employment "QC"
   :employment/vacation-rate 0.04M
   :compensation/base {:amount 75000.00M :commodity :CAD :period :annual}}

  {:person/given-name "James"
   :person/family-name "MacDonald"
   :person/national-id-doc-sin "123456790"        ; valid SIN
   :person/preferred-correspondence-language :en
   :employment/entity "Acme Canada Inc."
   :employment/start-date #inst "2026-01-15"
   :employment/province-of-employment "ON"
   :employment/vacation-rate 0.04M
   :compensation/base {:amount 85000.00M :commodity :CAD :period :annual}}]

 :payroll-runs
 ;; 24 biweekly pay periods Jan–Dec
 [...]

 :assertions
 ;; Two T4s emitted at year-end:
 ;;   T4(Sophie, RP0001, 2026) — FR slip; :audit-doc/language :fr
 ;;   T4(James,  RP0001, 2026) — EN slip; :audit-doc/language :en
 ;; One T619 envelope per language (since lang_cd is per-submission)
 ;;   — so two submissions: one EN, one FR
 ;; PD7A monthly helper sums CRA payables for the whole RP across both employees
 ;; QC employee passes through with T4 box 17/17A/55/56 populated, BUT
 ;;   the test asserts a warning is logged: "RL-1 filing not in C4 scope"
 ;; Vacation-pay liability balance at year-end = 4% × (sum gross from Jan 15 forward)
 }
```

### 10.4 — Known gotchas (collect-them-once, save the impl-agent a day each)

1. **QC employee in a federally-incorporated entity.** The
   employee gets a T4 (federal) AND an RL-1 (Revenu Québec) —
   they are NOT mutually exclusive. C4 ships T4 emission only;
   document the RL-1 gap clearly. The federal income-tax
   withholding uses T4032-QC tables (lower federal rate to
   account for the Quebec abatement); QC provincial tax is
   withheld separately by the engine and remitted to Revenu
   Québec.

2. **Multi-province employees within one tax year.** Box 10 / 
   `empt_prov_cd` is single-valued per T4 slip — so an employee
   who moves provinces mid-year requires **multiple T4 slips**
   per the RC4120 guide. The aggregator must group-by
   (person × RP × tax-year × province-of-employment), not just
   (person × RP × tax-year).

3. **CPP2 (box 16A) phase-in.** CPP2 is NEW since 2024 — earnings
   between YMPE (C$74,600 in 2026) and YAMPE (C$85,000 in 2026)
   are subject to CPP2 at 4%. The engine handles the per-pay-
   period cap math (employee won't pay CPP after YMPE is reached
   in that calendar year, but starts paying CPP2 at 4% until
   YAMPE is reached). kontor just records what the engine emits.

4. **EI insurable earnings cap (box 24) is per-pay-period, not
   annual.** The MIE (Maximum Insurable Earnings) for 2026 is
   C$68,500 (estimated; verify against current CRA EI rate
   table). EI premium stops when annual insurable earnings reach
   MIE, but per-pay-period the engine caps at the pro-rated
   per-period amount. Box 24 is the SUM of per-period insurable
   earnings (capped), NOT min(gross, MIE).

5. **Vacation-pay-paid-out is in box 14, NOT separate.** If an
   employee takes vacation, the wages paid during vacation are
   ordinary employment income (box 14). The vacation-pay
   liability is drawn down at payout; box 14 keeps incrementing.

6. **Severance / retiring allowance has special tax treatment.**
   Lump-sum severance may have different withholding rates per
   T4032; the engine handles this. The posting builder treats
   severance as a `:severance` component-kind that goes to wages
   expense (debit) but the `:income-tax-withheld` line for the
   pay period reflects the lump-sum withholding rate. T4 box
   reporting: severance generally lands in box 67 (retiring
   allowance — eligible for transfer to RRSP) or box 66
   (retiring allowance — non-eligible), with `box 14` excluding
   it. **C4 documents the gotcha; consumer maps engine codes.**

7. **EHT (Employer Health Tax) is provincial, not CRA.** Ontario
   EHT (0.98%–1.95% on payroll above C$1M), BC EHT, Manitoba HE
   levy, Newfoundland HAPSET each are remitted to the **province**,
   not CRA. C4's account `:ca-payroll-er-eht` is a placeholder;
   per-province installer is a separate C4.x deliverable. NB / NS
   / PEI / SK don't have EHT.

8. **WSIB / WCB premiums are provincial workers-comp boards.**
   Reported annually (or quarterly in some provinces) to the
   per-province WSIB/WCB, not the CRA. Posts to
   `:ca-payroll-er-wsib`; out of T4 / PD7A scope.

9. **TPZ-1015 vs PD7A.** Revenu Québec's monthly source-deduction
   remittance is TPZ-1015, NOT PD7A. The pattern is identical
   (statement-of-account) but it's a parallel system; C4.1
   ships the TPZ-1015 helper alongside RL-1.

10. **Bilingual `lang_cd` is per submission, not per slip.** If a
    consumer wants per-employee language slips, they file
    **two T619 submissions** — one EN, one FR — each with its
    own set of T4 slips. The aggregator must split before
    serializing.

11. **CRA accelerated remitter due dates do NOT shift for weekends
    or holidays** in the same way. Per [Canada.ca: When to remit](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-due-dates.html):
    if a due date falls on a weekend or holiday, it shifts to
    the next business day for regular remitters; accelerated
    remitters have different rules. Carry this in the
    remittance-helper's due-date computation.

12. **CRA T619 amendment of an existing return** is done by
    re-submitting the WHOLE return with `:t4/report-type
    :amended` and the T4 Summary `:report-type :modified`.
    Partial amendments not supported.

---

## §11 — Open questions

These items are NOT settled by this note — the impl agent should
surface them for maintainer decision (preferably before C4-D2):

1. **Wagepoint partner-program access.** Is the maintainer (or a
   nominated kontor consumer) able to enroll in the Wagepoint
   Developers Program in time for C4 to ship a working live
   adapter? If no, the `wagepoint_api.clj` stays skeleton-only;
   if yes, scope grows by ~2 days for OAuth + endpoint wiring
   against the actual API.

2. **`:audit-doc/language` kernel add — separate ADR or addendum
   to ADR-051?** The slot is small; ADR-051 addendum probably
   suffices. Confirm.

3. **`:account-tag/program-account` schema location.** Should
   this be a kernel attr (it's BN-shaped — country-specific) or
   a CA-companion attr (it's CRA-specific)? Recommendation:
   CA-companion (lives in `kontor-payroll-ca/chart.clj`), with
   the kernel `:account-tag/name` doing the heavy lifting via
   the `:ca-cra-rp-NNNN` tag-name convention if the consumer
   doesn't want the typed slot. Confirm direction.

4. **T4 Box 40 (taxable benefits) and Box 45 (dental coverage)
   coverage in `xml/t4.clj`.** The existing emitter doesn't list
   element names for these in `T4_AMT`. Box 40 historically lives
   in T4 "Other Information" boxes (named by code, not box
   number); Box 45 was added in 2023. **Does the 2026V4 XSD have
   `T4_AMT` slots for these, or do they require an "Other"
   element list?** The impl agent must check the XSD before
   adding; this drives whether `xml/t4.clj` needs extension or
   C4 ships with a documented Box-40/Box-45 gap.

5. **C4 acceptance criterion.** Note 79 § (per-checkpoint
   acceptance) defines C2 + C3 acceptance. **What is C4's?**
   Recommendation: "A CA Inc with 3 employees (1 ON, 1 BC, 1 QC)
   posts 24 biweekly payrolls via the Ceridian CSV adapter,
   accrues vacation pay per province rate, computes a correct
   monthly PD7A total per the regular-remitter schedule, and at
   year-end emits a valid T619+T4+T4-Summary submission for the
   RP00001 that validates against the 2026V4 XSD. The QC
   employee's T4 boxes 17/17A/55/56 are populated correctly; an
   info-level log emits 'RL-1 emission deferred to C4.1'."
   Confirm.

6. **EHT + WSIB ship in C4 or split out?** Both are provincial,
   regular accrual on every payroll, and consumer-friction-
   contributors. Cheap to land C4 (per-province rate table +
   accrual line). Recommendation: ship EHT for Ontario only in
   C4 (largest market), other provinces in a C4.2 follow-up
   alongside Revenu Québec.

---

## §12 — Sources

All accessed 2026-05-18.

### Kontor file:line

- `modules/l10n-ca/src/kontor/l10n_ca/xml/t619.clj` — T619 envelope (2026V4-verified).
- `modules/l10n-ca/src/kontor/l10n_ca/xml/t4.clj` — T4 slip + T4 Summary (2026V4-verified).
- `modules/l10n-ca/src/kontor/l10n_ca/xml/t5.clj` + `t5018.clj` + `validation.clj` — sibling info-return emitters; share T619 envelope shape.
- `modules/l10n-ca/src/kontor/l10n_ca/identifiers.clj` — BN9 + BN15 + program-account validators (`RP`/`RT`/`RC`/`RM`/`RR`/`RZ`).
- `modules/l10n-ca/src/kontor/l10n_ca/chart.clj` — CAD-defaulting chart loader.
- `modules/l10n-ca/src/kontor/l10n_ca/gst_hst.clj` — GST34-2 line aggregator (reference for the "report engine" pattern; PD7A helper follows same shape).
- `modules/l10n-ca/src/kontor/l10n_ca/returns.clj` — QST + BC PST aggregators (sibling-pattern reference).

### Kontor research notes consumed

- [[79-hr-payroll-stage-r-plan]] — Stage R plan; §4 protocol shapes; §5.3 CA scope.
- [[81-hr-data-model-gold-standards]] — §9.6 compensation-as-entity refactor (relevant to wage-type catalog shape).
- [[72-hr-payroll-reference-study]] — OFBiz `humanres` reference (Apache-2.0 lift-safe).
- [[73-hr-payroll-market-pain]] — Theme B multi-state pain (relevant to multi-province T4 gotcha).
- ADR-005 (superseded by ADR-071) — TaxProvider pattern.
- ADR-017 — `EInvoiceProvider` (template for the engine-not-bundling-creds posture).
- ADR-034 — status-machine (for `:employment/state` lifecycle including `:terminated`).
- ADR-038 — audit-doc + approval (for `:audit-doc/category :payroll-filing` + `:audit-doc/language`).
- ADR-051 — audit-doc privilege axis (extended here with `:audit-doc/language` orthogonal axis).
- ADR-067 — `kontor.process` (for atomic compose-3-providers run-payroll!).
- ADR-068 — `*-tx-data` builder convention (for `pd7a-period-due` returning tx-data).
- ADR-071 — TaxRateProvider / TaxFacts / TaxPostingBuilder (mirrored by the Payroll trio).

### CRA / Service Canada / federal authoritative sources

- [Canada.ca — PD7A — Statement of Account for Current Source Deductions (Regular and quarterly remitters)](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-overview/statement-account-current-source-deductions-regular-quarterly-remitters.html) — PD7A is CRA-to-employer; remitter-type detail.
- [Canada.ca — Statement of account for current source deductions – Accelerated remitters – PD7A(TM)](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-overview/statement-account-current-source-deductions-accelerated-remitters.html) — Accelerated variants.
- [Canada.ca — Types of remitters](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/remitting-source-deductions/how-when-remit-more-information.html) — Quarterly / Regular / Accelerated T1 / T2 thresholds and rules.
- [Canada.ca — Employers' Guide RC4120: Filing the T4 Slip and Summary](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/rc4120/employers-guide-filing-t4-slip-summary.html) — T4 slip + Summary cycle; per-RP, last-day-of-Feb deadline.
- [Canada.ca — T4, Statement of Remuneration Paid 2026](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/t619-2026/t4-2026.html) — T4 2026 box list; CPP2 (16A) since 2024.
- [Canada.ca — T619, Electronic Transmittal 2026](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/t619-2026.html) — XML schema `partXXxmlschm1-26-1.zip` (Nov 2025); single-return-type-per-submission since 2025.
- [Canada.ca — XML specifications for all information return types](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/xml-specs.html) — Schema family bundles.
- [Canada.ca — File information returns electronically (tax slips and summaries)](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/filing-information-returns-electronically-t4-t5-other-types-returns-what-you-should-know-before.html) — >5 slips → mandatory electronic.
- [Canada.ca — Payroll Deductions Tables T4032 (general information)](https://www.canada.ca/en/revenue-agency/services/forms-publications/payroll/t4032-payroll-deductions-tables/t4032oc-jan/t4032oc-january-general-information.html) — Province-of-employment vs province-of-residence; T4032-XX per province.
- [Canada.ca — Record of Employment (ROE) overview](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/ei-roe.html) — ROE = Service Canada (not CRA); interruption-of-earnings trigger.
- [Canada.ca — Completing a Single ROE (Block 15A/15B/15C)](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/reports/completing-single-roe.html) — Block 15 sub-block semantics; 15B auto-calculated.
- [Canada.ca — Employers: How to complete the ROE](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/reports/roe-guide.html) — Block 16 reason codes; 5-day deadline.
- [Canada.ca — ROE Web user requirements (Appendices)](https://www.canada.ca/en/employment-social-development/programs/ei/ei-list/ei-roe/user-requirements/appendix-d.html) — `PayrollExtractXmlV2.xsd` (.BLK file); 1200 ROEs per upload.
- [Canada.ca — Business Number registration](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/registering-your-business/business-number.html) — BN9 source.
- [Canada.ca — BN structure (program identifiers)](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/registering-your-business/bn-structure.html) — RT/RP/RC/RM/RR/RZ.
- [Canada.ca — CPP and the CPP enhancement](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/payroll/payroll-deductions-contributions/canada-pension-plan-cpp/cpp-enhancement.html) — CPP2 (since 2024) detail.

### CRA contribution rate sources

- [Certified Professional Bookkeepers of Canada — 2026 CRA Maximum Pensionable Earnings announcement](https://cpbcan.ca/canada-revenue-agency-announces-maximum-pensionable-earnings-and-contributions-for-2026/) — YMPE 2026 = C$74,600; YAMPE = C$85,000; CPP rate 5.95%; CPP2 rate 4%; max contributions $4,230.45 + $416.00.
- [UAPP — Year's Maximum Pensionable Earnings 2026](https://uapp.ca/years-maximum-pensionable-earnings-under-cpp-for-2026-increases-to-74600-from-71300-in-2025/) — Same; +4.6% YoY.
- [SmartSMSSolutions — CPP Enhancement 2026 Payroll Impact](https://smartsmssolutions.com/resources/blog/ca/cpp-enhancement-2026-payroll-impact) — Employer-side guide.
- [WealthNorth — CPP Contribution Rates 2026](https://wealthnorth.ca/taxes/cpp-contribution-rates/) — Detail.
- [SMR CPA — 2026 CPP & EI Rates](https://smrcpa.ca/2026-cpp-and-ei-rates-canada/) — Combined rate update.

### Quebec / Revenu Québec sources (for §8 carve-out)

- [Revenu Québec — RL Slips](https://www.revenuquebec.ca/en/businesses/rl-slips-and-summaries/rl-slips/) — RL-1 overview.
- [Revenu Québec — RL-1 Slip: Employment and Other Income (courtesy translation)](https://www.revenuquebec.ca/en/online-services/forms-and-publications/current-details/rl-1-t/) — Form structure.
- [Revenu Québec — Guide to Filing the RL-1 Slip](https://www.revenuquebec.ca/en/online-services/forms-and-publications/rl-1-g-v/guide-to-filing-the-rl-1-slip-employment-and-other-income/) — Filing instructions.
- [Revenu Québec — Guide to Filing the RL-1 Summary](https://www.revenuquebec.ca/en/online-services/forms-and-publications/rlz-1-s-g-v/guide-to-filing-the-rl-1-summary-summary-of-source-deductions-and-employer-contributions/) — RL-1 Summary.
- [Workzoom — Quebec Payroll Compliance Guide](https://www.workzoom.com/blog/quebec-payroll-compliance-guide/) — QPP / QPIP / RL-1 / EI reduced rate for QC.
- [Cleverence — T4 and RL-1 Box Mapping](https://www.cleverence.com/articles/sage-documentation/t4-and-rl-1-slips-box-options-3496/) — Box-level cross-mapping reference.

### T4 box detail sources

- [Knit People — New Box 16A for CPP2](https://help.knitpeople.com/hc/en-us/articles/16416929732759-New-Box-on-the-T4-for-Second-Canada-Pension-Plan-CPP-Contribution-Reporting) — CPP2 implementation detail.
- [CATaxTools — T4 Box 52 (Pension Adjustment)](https://catax.tools/t4-box/52/) — PA semantics.
- [CATaxTools — T4 Slip Explained — Every Box Decoded](https://catax.tools/tax-insights/t4-slip-explained/) — Full box-by-box.
- [Powerpay — Box 29 employment codes](https://help.powerpay.ca/en/pphelp/Content/YearEnd/YearEndGuide/CompleteBox29T4.htm) — Employment codes for special cases.
- [Powerpay — Coding the 'Other Information' area](https://help.powerpay.ca/en/pphelp/Content/YearEnd/YearEndGuide/CodingOtherInformationT4.htm) — Box 40 / 45 / others.
- [TaxPrep — T4 Box 14 detail](https://www.taxprep.com/assistance/TF/2021/v20/en-ca/Content/Forms/T4box14.htm) — What's in box 14 (gross + taxable benefits).
- [QuickBooks — What is a T4 (employer + employee)](https://quickbooks.intuit.com/ca/resources/taxes/t4/) — Plain-English T4 overview.
- [Wagepoint — T4 for employers SMB guide](https://www.wagepoint.com/resources/guide/a-canadian-small-business-employer-s-guide-to-t4s/) — SMB filing perspective.
- [Avanti — 2025 T4 tax slip guide](https://www.avanti.ca/resources/t4-tax-slip-guide) — Per-box detail.
- [Employment Hero — T4 & T4A guide for employers](https://employmenthero.com/en-ca/resources/t4-guide-for-employers/) — Employer-side overview.

### Vacation pay sources (§7.3)

- [Ontario ESA — Vacation](https://www.ontario.ca/document/your-guide-employment-standards-act-0/vacation) — 4% / 6% Ontario.
- [Alberta — Vacations and vacation pay](https://www.alberta.ca/vacation-pay) — Alberta rate structure.
- [Samfiru Tumarkin — Vacation Pay Ontario 2026](https://stlawyers.ca/blog-news/vacation-pay-in-ontario/) — Plain-English 4-vs-6 rule.
- [Canada.ca — Annual vacations and general holidays (federally regulated employers)](https://www.canada.ca/en/services/jobs/workplace/federal-labour-standards/vacations-holidays.html) — Federal 4/6/8 rule.
- [TimeTrex — Canadian Vacation Rollover 2026](https://www.timetrex.com/blog/canadian-vacation-rollover-for-2026) — Year-over-year vacation accrual carry.
- [LawyerInfo — Vacation Pay Ontario rules](https://lawyerinfo.ca/guides/ontario/employment-rights-ontario/unpaid-wages-ontario/vacation-pay-ontario-4-vs-6-rules-and-how-it-accumulates/) — 4-vs-6 detail.
- [Outsource Bookkeeping — Vacation Pay Alberta 2026](https://www.outsourcebookkeeping.ca/blog/vacation-pay-alberta) — Alberta detail.

### Engine / vendor sources (§2)

- [Wagepoint — Developer API Agreement](https://wagepoint.com/people/developer-api-agreement) — Partner-program-gated API.
- [Wagepoint — Bookkeeper Partnership Program](https://www.wagepoint.com/partners/) — Partner channel.
- [Wagepoint — Payroll Remittance Schedules 2026](https://www.wagepoint.com/resources/guide/what-you-need-to-know-about-your-payroll-remittance-schedule/) — Remittance schedule SMB-friendly write-up.
- [Wagepoint — Create and Submit a Record of Employment (ROE) — Wagepoint 2.0 KB](https://wagepoint2help.zendesk.com/hc/en-us/articles/18898895711383-Create-and-Submit-a-Record-of-Employment-ROE) — Confirms ROE is engine-emitted.
- [Ceridian (Dayforce) — Payroll Data Export help](https://help.dayforce.com/r/ImplementationGuide/Dayforce-Implementation-Guide/Payroll-Data-Export) — Dayforce GL CSV export.
- [Dayforce — Schedule the Payroll GL Export](https://help.dayforce.com/r/ImplementationGuide/Dayforce-Implementation-Guide/Schedule-the-Payroll-GL-Export) — Scheduling.
- [Dayforce — GL Account Numbers](https://help.dayforce.com/r/ImplementationGuide/Dayforce-Implementation-Guide/GL-Account-Numbers) — Mapping.
- [Powerpay — small-business product page](https://www.ceridian.com/ca/products/powerpay) — Powerpay SMB positioning (45k+ CA customers).
- [Powerpay — Powerpay landing](https://www.powerpay.ca/) — Powerpay overview.
- [Microsoft Learn — Ceridian Payroll extension for Business Central](https://learn.microsoft.com/en-us/dynamics365/business-central/ui-extensions-ceridian-payroll) — Ceridian → ERP integration shape (reference for kontor adapter).
- [ADP Support — General Ledger Mapping for RUN](https://support.adp.com/adp_payroll/content/hybrid/GL/Online-Infographic-GL-Mapping.pdf) — RUN GL mapping.
- [ADP Support — RUN GL Guide for QBO](https://support.adp.com/adp_payroll/content/hybrid/GL/RUN_GL_Guide_QBO.pdf) — RUN → QBO GL push.
- [Microsoft Learn — ADP import process in Payroll Connect](https://learn.microsoft.com/en-us/troubleshoot/dynamics/gp/the-adp-import-process-payroll-connect) — ADP CSV 10-field shape.
- [Payworks — Tired of the journal entry download-upload dance](https://blog.payworks.ca/tired-of-the-journal-entry-download-upload-dance-theres-an-integration-for-that) — JE integration pattern.
- [Payworks — Five ways for accountants and bookkeepers](https://blog.payworks.ca/unique-payroll-no-problem-here-are-five-ways-weve-got-accountants-and-bookkeepers-covered) — Customization options.
- [Knit People — Best Payroll Software in Canada guide](https://knitpeople.com/guides/best-payroll-software-canada) — Market overview.
- [getKnit — Full list of Knit's Payroll API guides](https://www.getknit.dev/blog/full-list-of-knits-payroll-api-guides) — Unified-API perspective.
- [getKnit — Employee Payroll API reference](https://developers.getknit.dev/reference/employee-payroll-api) — Unified payroll API shape (informative reference, not a direct integration target).
- [getKnit — 15 Payroll APIs to Integrate With in 2026](https://unified.to/blog/15_payroll_apis_to_integrate_with_in_2026_adp_gusto_paychex) — Payroll API landscape.
- [Easypay — Canadian Payroll Software](https://www.easypay.ca/) — Easypay vendor page (legacy, ASCII export only).
- [Avalon Accounting — Best Payroll Software in Canada](https://www.avalonaccounting.ca/blog/best-payroll-software-canada) — Market positioning.
- [Rise People — What is the Best Payroll Software in Canada](https://risepeople.com/blog/what-is-the-best-payroll-software-in-canada/) — 16-alternative comparison.

### PD7A / remittance how-to + remitter-type sources

- [Workzoom — Payroll Remittance to CRA: Step-by-Step Guide for 2026](https://www.workzoom.com/blog/payroll-remittance-cra-2026/) — Per-remitter-type schedule.
- [Fluentbook — CRA Remittances Guide 2026](https://fluentbook.ca/resources/cra-remittances-guide) — Voucher + penalty detail.
- [Rise — Comprehensive Guide to Payroll Deduction Remittances](https://risepeople.com/blog/a-comprehensive-guide-to-payroll-deduction-remittances/) — Detailed remitter-type / due-date.
- [PayTrak — CRA Payroll Remittances: Everything You Need](https://paytrak.ca/payroll-blog/cra-payroll-remittances-everything-you-need-to-know) — Plain-English overview.
- [QuickBooks — How to access your PD7A in QuickBooks](https://quickbooks.intuit.com/learn-support/en-ca/help-article/quarterly-employer-forms/access-pd7a/L7HcaPNX6_CA_en_CA) — PD7A retrieval through accounting software.
- [Sage 50 CA — Complete the PD7A Payroll Remittance Form](https://help-sage50.na.sage.com/en-ca/core/2026/Content/Employees/GovernmentForms/CompletePD7AForm.htm) — PD7A vendor-side walkthrough.
- [PEO Canada — CRA PD7A Form Changes: What Employers Must Know](https://peocanada.com/blogs/changes-in-cra-pd7a-format/) — Recent PD7A format change context.
- [Total Tax Solutions — PD7A Form sample PDF](https://www.totaltaxsolutions.ca/wp-content/uploads/2018/05/PD7A.pdf) — Sample paper PD7A.

### Provincial EHT / WSIB (§10.4 #7-8)

- [Ontario — Employer Health Tax (EHT)](https://www.ontario.ca/document/employer-health-tax-eht) — ON EHT structure (C$1M exemption; 0.98–1.95% rates).
- [Ontario — EHT remuneration](https://www.ontario.ca/document/employer-health-tax-eht/remuneration) — What counts as remuneration for EHT.
- [Baker Tilly Canada — EHT and workers' compensation](https://www.bakertilly.ca/en/btc/services/eht-and-wsib) — Combined EHT + WSIB overview.
- [GTA Accounting — Employer Health Tax Ontario 2025](https://www.gtaaccounting.ca/blog/employer-health-tax-ontario) — ON EHT detail.
- [CFIB — Payroll Deduction Tables 2026](https://www.cfib-fcei.ca/en/tools-resources/payroll-deduction-tables) — All-province summary.

### Journal-entry / chart-of-accounts references (§7)

- [VCC Learning Centre — Payroll Accounting](https://learningcentre.vcc.ca/media/vcc-library/content-assets/learning-centre/worksheets/by-coursex2fprogram/business/Payroll_Accounting.pdf) — Standard CA payroll JE shape.
- [Bookkeeping Essentials — Manual Payroll](https://www.bookkeeping-essentials.com/manual-payroll.html) — Hand-coded CA payroll example.
- [Indeed Canada — Payroll Journal Entry](https://ca.indeed.com/career-advice/career-development/payroll-journal-entry) — JE structure.
- [Wize University — Payroll Liabilities (Canada)](https://www.wizeprep.com/textbooks/undergrad/accounting/4016/sections/99342) — Per-account liability detail.
- [QuickBooks Canada — Payroll Accounting Guide](https://quickbooks.intuit.com/ca/resources/payroll/what-is-payroll-accounting/) — Plain-English JE walkthrough.
- [Canada.ca — Employers' Guide T4001: Payroll Deductions and Remittances](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4001/employers-guide-payroll-deductions-remittances.html) — Authoritative employer guide.

---

End of note 84.

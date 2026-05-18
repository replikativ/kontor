---
date: 2026-05-17
title: 73 — HR / payroll market-pain catalog (vendor reviews + OSS issues + analyst commentary)
status: draft
audience: maintainer + future-self prioritizing Stage R substrate features
---

# 73 — HR / payroll market-pain catalog

Stage R research-before, file 2 of 3. Companion to
[[72-hr-payroll-reference-study]] (OFBiz / Tryton / Odoo data-model
study) and [[74-hr-payroll-internal-gap-analysis]] (substrate audit +
maintainer design calls). Aggregates customer complaints and engineer
post-mortems across the HR/payroll vendor landscape, then ranks them
by remediation-feasibility against the **kontor substrate properties**:
bitemporal-valid-time, parallel ledgers, audit-doc with privilege
tagging, retention + legal-hold + DSAR, status-as-data, process
orchestration.

Sources canvassed:
- G2 / Capterra / TrustPilot reviews of Workday, SAP SuccessFactors,
  BambooHR, Gusto, ADP RUN + ADP Workforce Now, Paychex, DATEV
  LODAS, Sage Payroll, Rippling, Justworks, Deel, Personio,
  HiBob, Paycor, UKG Pro, NetSuite SuitePeople.
- HN / Reddit threads in r/Payroll, r/humanresources, r/sysadmin,
  r/CPA, r/AskHR.
- OSS issue trackers: OFBiz JIRA (project HUMANRES), Odoo GitHub
  issues labelled `hr` / `hr_payroll`, Frappe/ERPNext issues
  labelled `payroll`, Tryton bug tracker.
- Vendor / analyst posts: Gusto Engineering Blog, OnPay blog,
  Avalara payroll commentary, Brian Sommer's HR Tech reviews (ZDNet
  archives), Josh Bersin Substack on payroll tech, Pete Tiliakos's
  *PayTech* podcast notes, Naomi Bloom's HR-Tech writings.
- Reg/Compliance: IRS Pub 15, DATEV LODAS-Schnittstellen-Dokumentation,
  HMRC RTI guidance, ATO STP Phase 2 guidance, Brazil eSocial S-1000
  layout, India ITDept TDS quarterly returns RFP.

The pain-points below are *categorical* — each is observed across
multiple vendors and discussion threads, not pinned to a single
customer or product. Where a particular product is uniquely bad on
a dimension that detail is called out.

## TL;DR

- **Backdated corrections are the single most painful axis** across
  every payroll product surveyed. Customers complain about "I can't
  see what was true on March 31" (Workday G2), "the YTD totals jumped
  after a Q1 correction" (Gusto support), "we had to delete-and-
  re-post the whole period" (DATEV admin forums). **This is the
  pain kontor's bitemporal substrate uniquely solves.** A `:posting`
  written at `:db.valid/from #inst "2026-03-31"` and `:db.tx-time
  #inst "2026-05-17"` answers both "what did we know on May 17?" and
  "what did we tell the tax authority on April 5?" without re-running
  a pay engine.
- **Multi-state US payroll** is the most-cited US complexity. Reciprocity
  agreements, multi-state employees (one-state-resident,
  other-state-work-location), local-tax overlap (Ohio RITA, Pennsylvania
  PSD codes), and the `~10,000`-jurisdiction SUTA registry overwhelm
  in-house engines. **kontor cannot solve this with code** — but the
  `TaxProvider` + `PayrollProvider` boundaries route this to vendors
  who do (ADP, Vertex, Avalara payroll, OnPay).
- **Multi-country payroll** in EU + LATAM hits "no single vendor
  covers all 27" walls. Deel, Remote, Papaya partial-cover via local-
  vendor aggregation; SAP SuccessFactors covers via 40+ country
  payrolls but at enterprise price. **Same conclusion**: the
  substrate hosts adapter modules; the math lives behind a vendor
  boundary.
- **Year-end forms (W-2, T4, P60, Lohnsteuerbescheinigung)** are
  uniformly described as "the most stressful 2 weeks of the year"
  across HR teams. The pain is upstream: amended forms (W-2c, T4A)
  cascade back to prior-period payroll. **kontor's bitemporal +
  parallel-ledger story is the right substrate** for amendment
  cascades without losing the original-as-filed snapshot.
- **PII / sensitive data handling** is increasingly regulator-
  driven (GDPR Art. 32 + state-level US laws like CCPA / CPRA / SB
  220 NV / SHIELD NY). Vendors uniformly fail on subject-access
  (Art. 15) — customer complaints describe 30-90 day delays. kontor's
  ADR-052 `:dsar-request` + ADR-051 `:audit-doc/privilege` exists
  for this.
- **The 5 pain points kontor uniquely fits.** Backdated corrections,
  multi-entity HR transfers, audit-doc-as-contract retention,
  payroll-correction-on-held-entity composability, and "book vs tax
  basis" wage accruals (parallel ledgers per ADR-021). Substrate
  beats engine on every one.

---

## 1. Pain-point catalog

The format below follows ADR-037's convention:

```
P<priority> — <title>
  Frequency: <how often it shows up>
  Severity: <impact on the customer>
  Sources: <who's complaining>
  Remediation hint: <how the kontor substrate addresses it>
```

### Theme A — Correction workflows

The single most-mentioned class of pain. Manifest in seven distinct
sub-shapes.

**P1 — Backdated correction reprocessing breaks audit trail.**
- *Frequency:* High. Cited in Workday G2 reviews (search "retro
  pay"), Gusto support forum threads on Q1 amendments, DATEV LODAS
  Newsletter Q&A on `Korrekturlauf` (correction-run) semantics, ADP
  WFN community threads on "void and reissue."
- *Severity:* High. Finance teams report "running payroll totals" no
  longer match prior reports after a correction; CFOs lose trust in
  the system; reconciliation against the GL requires manual journal
  entries.
- *Sources:* Workday G2 (multiple 3-star reviews citing "you can't see
  what was true on the original run date"); Gusto support forum
  (search "amended Q1"); DATEV community forum
  (`Stornierung` vs. `Rückrechnung` confusion); r/Payroll thread
  "Why does ADP's YTD jump after a void?" (Aug 2024).
- *Remediation hint:* **kontor's bitemporal substrate
  (`:db.valid/from` + `:db.tx-time` from datahike's `:db.valid/*`
  surface — see [doc/research/68-bitemporal-port-and-stratum-plan.md])
  natively handles this.** A correction is a new transaction with
  `:vt-from` = original date, `:tx-time` = correction date.
  `(d/as-of db tx-id)` returns the as-of-original-filing state;
  `(d/valid-at db original-date)` returns the corrected truth. The
  YTD totals at any (filing-time, valid-time) pair are reproducible.
  Consumer entities don't have to re-run the payroll engine — they
  transact a corrected sample with the correct valid-time, and
  `kontor.report/compute-report` can produce both views.

**P1 — "Void and reissue" pattern destroys reconciliation lineage.**
- *Frequency / severity:* High / medium-high in US-vendor reviews.
  The voided payment leaves an orphan in the GL; the reissued one
  looks like a fresh transaction. Reconciling the bank-feed deposit
  reversal + redeposit becomes manual.
- *Sources:* Gusto support forum; r/CPA "Bank rec after a Gusto void
  is hell"; ADP RUN community.
- *Remediation hint:* kontor's posting-status sealing (ADR-007) +
  bitemporal valid-time turn void-and-reissue into a *single*
  composite process: `kontor.process.run-process` with a step that
  reverses the original posting at `:vt-from` = void-date and a step
  that issues the corrected one, atomically committed, linked via
  `:transaction/source "payroll-correction:<id>"`. The reconciliation
  side sees both halves as a paired event.

**P2 — Retro pay across pay-period boundary calculated wrong.**
- *Frequency / severity:* Medium / medium. Common when wage change
  is effective mid-pay-period.
- *Sources:* BambooHR + HiBob + Workday user communities.
- *Remediation hint:* kontor's `:employment/wage` is bitemporal —
  changing it at `:vt-from #inst "2026-03-15"` makes prior-period
  queries return the prior wage and current-period queries return the
  new wage. The prorate becomes a derived query over
  `(d/valid-at db t)` at each work-date in the pay period.

**P2 — Year-end form amendments (W-2c, T4A, ATO STP Variation)
cascade to prior-period payroll.**
- *Frequency / severity:* Annual / high. Drives 1099 / W-2 amendment
  costs and IRS correspondence.
- *Sources:* IRS Pub 15 commentary; Gusto "How to handle a W-2c";
  ADP WFN community.
- *Remediation hint:* Same as P1 (bitemporal substrate keeps original-
  as-filed AND amended views). **kontor uniquely fits**: most vendors
  keep two parallel databases (live + as-of-Dec-31-snapshot) and
  reconcile by hand.

**P3 — Correction approval workflow is bolt-on.** Frequency medium /
severity low. Workday G2 + UKG Pro. ADR-034 status-machine + ADR-038
audit-doc cover it natively: `:payroll-correction/state :proposed →
:approved → :posted` with `:status-history/changed-by-uid`.

### Theme B — Multi-state US payroll

Cited in nearly every US-vendor review. The complexity is genuinely
high (51 state-level jurisdictions plus ~10,000 local taxing
jurisdictions per ADR-005). Vendors split into "we handle it"
(ADP, Paychex, Gusto, Rippling, OnPay) and "we don't fully" (BambooHR
payroll, HiBob payroll).

**P1 — Reciprocity agreement handling is buggy across vendors.**
- *Frequency / severity:* High in border states (NJ/NY/PA, IL/IN/KY/
  MI/WI, MD/DC/VA/WV) / high — wrong withholding triggers
  employee tax-return surprise.
- *Sources:* r/CPA "Gusto's PA-NJ reciprocity is wrong"; ADP RUN on
  Indiana counties; Rippling on Michigan-Ohio.
- *Remediation hint:* **kontor does not solve this.** Reciprocity
  logic belongs in the `PayrollProvider`. kontor's job is to (a)
  make the boundary explicit so adapters can be swapped, and (b) host
  the audit trail so the customer can prove they did the right thing
  when an employee's W-2 disagrees with state filing.

**P1 — Multi-state remote employees: which state withholds?**
- *Frequency / severity:* Skyrocketed since 2020 / high — mis-
  allocation triggers state-tax compliance failures and employer
  nexus issues.
- *Sources:* OnPay blog; Gusto blog; r/Payroll "1 employee in 4
  states" (Apr 2025).
- *Remediation hint:* kontor's `:posting/entity` (ADR-031) + state-
  tag via `:account-tag/country-code` lets the substrate *report*
  multi-state allocations correctly; the *decision* of where to
  withhold is the `PayrollProvider`'s.

**P2 — Local-tax (Ohio RITA, PA PSD, Indiana counties) badly modeled.**
Frequency medium / severity medium. r/Payroll + Avalara payroll
blog + OnPay support. Same remediation: substrate provides the
jurisdiction-tagging axis; engine lives outside.

**P3 — SUTA new-state setup latency.** 4-6 week delays cited at ADP
and Paychex. r/Payroll + HN. kontor's `:employment/state` handles
new-state-hire as a state transition with a documented `:audit-doc`
for SUTA registration; not on the critical path of the filing itself.

### Theme C — Multi-country EU + payroll country-of-employment

The trans-national pain (per architecture-review note 69 §4 Gap 3,
this is exactly what Stage R is for).

**P1 — Cross-border remote employees: "is the German employee working
from Spain a German payroll case or a Spanish one?"**
- *Frequency:* High since 2020.
- *Severity:* High — wrong call triggers permanent-establishment
  exposure for the employer.
- *Sources:* Deel / Remote / Papaya blog posts repeatedly; HN
  threads ("My company can't figure out where to pay me"); Personio
  community on DE+other-EU; SAP SuccessFactors EC community on
  expat / assignment management.
- *Remediation hint:* **kontor's ADR-031 `:entity` axis is exactly
  the right primitive** — an employee can have one `:employment/entity
  acme-de-gmbh` AND a concurrent `:employment/entity acme-es-sl` with
  per-entity-functional-commodity (`:entity/functional-commodity`
  EUR for both, but per-entity payroll feeds independent). Note 09
  §6 already maps the Workday-multi-job pattern onto this. The
  determination of *which* entity employs is a legal call, not a
  substrate one — but kontor records BOTH employments cleanly,
  unlike vendors that force a single primary employer.

**P1 — Per-country payroll-vendor sprawl: "we use Personio for DE,
Gusto for US, Silae for FR, ZenHR for IN, Pluxee for BR..."**
- *Frequency:* Universal at >5-country companies.
- *Severity:* High — GL consolidation is manual; bookkeeping closes
  late.
- *Sources:* Brian Sommer's HR Tech reviews; Josh Bersin Substack;
  Personio + Workday SuccessFactors integration whitepapers; r/HRtech.
- *Remediation hint:* kontor's `PayrollProvider` protocol (ADR-005-
  shaped) with one impl per (country, vendor) — Personio-DE,
  ADP-US, Silae-FR — produces a normalized `:payroll-result`
  schema (note 09 §7) that the kernel composes into GL journal
  entries. **This is the headline substrate value.** Note 09's
  Personio-DATEV pattern already documents the canonical adapter
  shape (`Lohnimportdatenservice` push, `Lohnauswertungsdatenservice`
  pull).

**P2 — DE Lohnsteuerbescheinigung vs. ELStAM vs. SV quirks.** Constant
for DE employers / medium-high severity (ELStAM mismatches, quarterly
SV bracket changes). DATEV community + HaufeForum + Lohn-und-Gehalt.
Adapter concern (`kontor-payroll-de-datev` / `-personio`).

**P2 — Brazil eSocial event-bus complexity.** Universal for BR /
very high (40+ event types, real-time reporting, late-event
penalties). Pluxee community + Receita Federal docs + Globo IT.
Adapter concern (`kontor-payroll-br-esocial`); substrate hosts the
events and resulting GL postings.

**P3 — India statutory complexity** (TDS quarterly + PF monthly +
ESI + per-state professional tax). Universal for IN / high
operationally. ZenHR + ITDept TDS commentary. Adapter concern.

### Theme D — Gross-to-net edge cases (the eternal bug class)

**P1 — Garnishment + child-support order calculation order is wrong.**
Medium frequency / high severity (federal-state patchwork). OnPay +
ADP WFN + Gusto blog. kontor doesn't compute; substrate value-add is
`:audit-doc/category :garnishment-order` with retention per state
law (ADR-050) and `:audit-doc/privilege :pii-sensitive` for the
court order.

**P1 — Imputed income (GTL >$50K, personal-use vehicle, gym,
equity-vest 26-pay-day spread) computed inconsistently across
vendors.** Quarterly / medium. Engine concern; substrate hosts the
postings with `:transaction/source "payroll:<run-id>"` and
`:posting/account` mapped per wage-type.

**P2 — Bonus + supplemental wage withholding (US 22% / 37%) applied
wrong.** High around Q4 / medium. Engine concern.

**P3 — 401(k) catch-up + after-tax Roth + Mega Backdoor edge cases.**
Low frequency / high stakes (HCE testing). Engine concern; substrate
hosts contribution postings with `:account-tag/applicability
:retirement-401k`.

### Theme E — Benefits enrollment race conditions + open-enrollment

**P2 — Open-enrollment race condition ("I clicked submit 11:59 PM
Dec 31, system didn't save it").** Annual / high to affected employee.
Workday G2 + SAP SuccessFactors + BambooHR communities. kontor's
bitemporal substrate disambiguates: an enrollment with `:vt-from
#inst "2025-12-31T23:59:59Z"` and `:tx-time` a millisecond later is
unambiguously valid for the following year. Vendor pain comes from
non-bitemporal stores where "the row says 2026 but was written
2025-12-31" requires application-level disambiguation.

**P2 — Mid-year QLE enrollment ("system says wait for next open
enrollment").** Continuous / medium-high. Workday G2 + BambooHR +
Gusto Benefits community. QLE = `:audit-doc/type :qle-evidence`
attached to the enrollment-change `:status-history` row.

**P3 — COBRA 14-day notification missed.** Common / high (federal
penalty). TASC + WageWorks + ADP COBRA admin. `kontor.schedule`
(ADR-032) + `:cobra-notification/state` machine schedule the
deadline; natural fit.

### Theme F — Year-end tax forms

**P1 — W-2 / 1099 amendment cascade after late correction.** Annual
/ very high. See Theme A P1; bitemporal substrate fits. Per-
jurisdiction form layout lives in `kontor-payroll-<cc>-<vendor>`.

**P2 — Multi-jurisdiction wage reporting (W-2 box 16 state, box 18
local) doesn't reconcile with year-totals.** High in multi-state /
medium. r/CPA + ADP community. kontor's `:posting/entity` +
jurisdiction tag + parallel-ledger (ADR-021) produce per-
jurisdiction wage totals natively — unusually well-suited.

**P3 — Form 941 vs. Form 944 mid-year flip.** Low / medium. Engine
concern.

### Theme G — Time-tracking integration

**P1 — Timesheet → payroll missing punches undetected until run.**
Continuous / medium (retro pay). When I Work + Deputy + Connecteam
+ HotSchedules ↔ ADP. kontor's `:analytic-line` (ADR-022) hosts
timesheets; a `kontor.schedule` completeness check surfaces missing
(employment, work-date) pairs as `:exception/state :open` rows.

**P2 — Salaried+overtime hybrid computed wrong.** Medium in
hospitality / healthcare / transportation; medium-severity FLSA
exposure. r/HR + Paycor + Paylocity. Engine concern; substrate
stores `:employment/exempt-flag` + `:employment/wage-period` per
OFBiz pattern.

**P3 — Geofenced clock-in / inaccurate GPS.** Out of scope.

### Theme H — Immigration / visa data sensitivity

**P1 — I-9 + E-Verify retention (3y after hire OR 1y after
termination, whichever later) mis-handled.** Audit-driven low
frequency / high severity (DHS penalties). USCIS audit notices + HR
Compliance LinkedIn. **kontor's ADR-050 `:retention-policy` is
exactly this primitive.** One row with `:min-keep` "3y-after
:hire-date OR 1y-after :termination-date." I-9 = `:audit-doc/type
:i9` with `:audit-doc/privilege :pii-sensitive`.

**P1 — Visa expiry (H-1B / O-1 / L-1) not surfaced ahead of work-
auth cutoff.** Continuous at sponsor employers / very high (federal
violation). HR Compliance community + immigration-law firm blogs +
r/h1b. `:audit-doc/expiry-date` + `kontor.schedule` sweep surfacing
upcoming-expiry rows N-days-before.

**P2 — Passport / national-ID leakage in HR exports.** Continuous /
very high under GDPR Art. 32 + CCPA. GDPR enforcement DB + breach
disclosures. `:audit-doc/privilege :pii-sensitive` per ADR-051
(schema.clj:3525-3539); export tooling honors privilege.

### Theme I — Equity comp + RSU vest accounting

**P1 — RSU vest wage event at vest-date FMV: stock-price drift
breaks withholding.** Quarterly at vest-cliff companies / high.
Carta + Schwab Equity Awards + r/personalfinance. Engine concern;
substrate hosts the event as `:employment/equity-event` with
`:audit-doc/type :vest-notice`.

**P2 — ISO disqualifying disposition triggered by employee action,
not payroll schedule.** Per-event / high when missed. Carta blog +
Schwab. Engine concern.

**P3 — 409A valuation refresh + per-employee strike-price impact.**
Annual / high (IRS penalty for stale). Carta + Pulley + AngelList.
`:audit-doc/type :409a-valuation` with `:audit-doc/effective-date`;
expiry sweep via `kontor.schedule`.

### Theme J — DSAR / GDPR Article 15 + state-equivalents

**P1 — Subject access request takes 30-90 days because payroll data
is in 4 systems.** Per-request (rising post-CCPA/CPRA) / very high
if missed (GDPR Art. 12(3) one month extendable to three; CCPA 45
days). DPO LinkedIn + Privacy Subreddit + ICO enforcement.
**kontor's ADR-052 `:dsar-request` + ADR-051 privilege tagging are
precisely engineered for this.** `kontor.dsar/collect` aggregates
`:partner [:partner/kind :employee]`-tagged data; privileged docs
excluded with redaction-reason. SLA tracking via
`:dsar-request/deadline-at` (schema.clj:834-840).

**P2 — Right-to-erasure conflicts with payroll retention (e.g., DE
HGB §257 10y).** Rising as employees exercise the right / very high
(regulator action either way). Privacy Subreddit + ICO + DSGVO
community. **kontor's ADR-050 `:retention-policy` is designed for
this exact conflict** — both `:min-keep` (10y per HGB) AND
`:max-keep` (GDPR purpose-limitation ceiling); `eligible-for-purge?`
selects the tighter ceiling. Personnel retention is
jurisdictionally distinct from accounting retention.

**P3 — Anonymization vs. deletion: vendors anonymize the field but
retain the row, leaving a recognizable shadow.** Across all surveyed
vendors / medium (passes audit but regulators starting to
challenge). Personio "anonymize" docs + Odoo `privacy_lookup` (note
22 §1) + DSGVO commentary. kontor's `:db/purge` (ADR-007) *deletes*
the personal data while leaving the posting hash; the audit chain
documents the purge.

### Theme K — Integration friction with finance

**P1 — Payroll → GL summary doesn't match per-employee detail;
reconciliation is manual.** Monthly / high (close delays). SAP HCM
↔ S/4HANA + ADP ↔ NetSuite + Gusto ↔ QBO. **kontor uniquely fits**:
per-employee detail postings (`:posting/partner [:partner/kind
:employee]` + analytic distribution per `:analytic-account`) feed the
same `kontor.report` that produces the GL summary. Two views agree
by construction.

**P2 — Inter-company payroll allocation (employee on US payroll but
does 30% work for DE entity).** High in multinationals / high
(transfer-pricing exposure). Big-4 advisory blogs + SAP SuccessFactors
EC + Workday Global Payroll Cloud. kontor's `:posting/entity` (ADR-031)
+ `:analytic-distribution` (ADR-022) handle natively; gross-pay
splits across N `:posting/entity` rows with sum-to-zero per entity;
intercompany pair becomes a composite transaction; consolidation
eliminates (note 69 §4 Gap 4).

**P3 — Payroll-week vs. fiscal-period boundary straddling.** Constant
/ low-medium. QBO + Gusto + NetSuite + ADP communities. Standard
practice — `kontor.period` (fiscal) is separate from `:pay-period`;
period-end accrual; substrate handles.

### Theme L — OSS-specific findings

**P2 — OFBiz humanres has no payroll engine; community plugins
unmaintained.** OFBiz JIRA HUMANRES-3 (2008, "Add payroll module";
still open). Confirms substrate-vs-engine split.

**P2 — Odoo `hr_payroll` is enterprise-only.** OCA `payroll` repo is
the community fallback with smaller country coverage. Validates
`PayrollProvider` boundary.

**P3 — ERPNext HR + Payroll has multi-currency edge cases.** Frappe
forum threads on multi-currency payroll. kontor's
`:entity/functional-commodity` + ADR-031 per-entity sum-to-zero
gives the substrate the right shape.

**P3 — Tryton `account_payroll` does not exist** (only
`attendance`). Tryton is not a useful payroll reference.

---

## 2. The 5 pain points kontor uniquely fits

| Pain | Theme | Why kontor uniquely fits |
|---|---|---|
| Backdated correction reprocessing breaks audit trail (P1) | A | **Bitemporal substrate** (`:db.valid/from` + `:db.tx-time`). Reproduces both as-of-filing and as-of-corrected views. Other vendors keep two databases. |
| Multi-entity HR transfers (P1) | C | **`:posting/entity` (ADR-031) + multi-employment** per Workday pattern. Note 09 §1 maps cleanly. SAP / Workday do this; small-mid market vendors don't. |
| Audit-doc-as-contract retention (P1, P2) | H, J | **ADR-038 `:audit-doc/*` + ADR-050 retention + ADR-049 legal-hold + ADR-051 privilege.** No competing OSS has this combination; commercial vendors have it but at enterprise price. |
| Payroll correction on legally-held entity composability (P1) | A, J | **`kontor.process.run-process` + status-machine + retention** compose cleanly. The note-69 §3.2 trace through the gate (legal-hold → sealing → period → state-machine → sum-to-zero) handles the correction-on-held-entity scenario by construction. |
| "Book vs tax basis" wage accruals (P2) | F | **Parallel ledgers (ADR-021)** `:ledger/framework :HGB` vs `:tax`. The HGB book accrues PTO liability; the tax book defers per local statute. Substrate-level invariant. |

## 3. The pain points kontor does NOT solve

Acknowledge upfront so the substrate-vs-engine split is honest:

- **Gross-to-net math** (Themes B, D): jurisdictional engines own this.
  Substrate hosts the result postings + the audit trail.
- **Open-enrollment UX** (Theme E): consumer / vendor concern. Substrate
  hosts the resulting `:benefit-enrollment` rows + status-history.
- **Time-clock hardware** (Theme G): out of scope entirely.
- **Reciprocity-agreement state-tax logic** (Theme B): engine concern.
  Substrate hosts results.
- **Year-end form generation** (Theme F): adapter concern. Substrate
  feeds the data; adapter formats.

## 4. Cross-links

- Reference data model that motivates these pain points:
  [[72-hr-payroll-reference-study]].
- Substrate-audit + maintainer design calls based on these pain points:
  [[74-hr-payroll-internal-gap-analysis]].
- Predecessor: [09-hr-personnel-payroll.md](09-hr-personnel-payroll.md).
- Architecture review that ranks Stage R P1 for trans-national:
  [69-architecture-review-and-fp-model.md](69-architecture-review-and-fp-model.md).
- Bitemporal-substrate plan: [68-bitemporal-port-and-stratum-plan.md](68-bitemporal-port-and-stratum-plan.md).
- ADR-050 retention semantics: see also note 22 (legal-reference-study)
  §1 for the jurisdictional retention floors that conflict with
  GDPR purpose-limitation.

## Appendix: source bibliography (themes A-L)

Web content is not file:line-citable; the source list below grounds
the patterns. Spot-check links may rot — the *themes* are durable
across sources. Listed compactly per theme:

- **A correction workflows** — Workday G2 reviews ("retro pay"),
  Gusto support forum ("Q1 amendment" / "void and reissue"), DATEV
  community (`Korrekturlauf` / `Stornierung`), r/Payroll, r/CPA,
  IRS Pub 15-T amendments commentary.
- **B multi-state US** — r/CPA reciprocity threads, OnPay blog
  multi-state remote series, Gusto Engineering Blog, Avalara payroll
  blog PSD-code series, ADP RUN community.
- **C multi-country EU** — Deel / Remote / Papaya blogs, HN threads
  on cross-border remote, Personio + SAP SuccessFactors EC + Pluxee
  BR communities.
- **D gross-to-net** — OnPay blog (garnishment), Gusto blog (imputed
  income, supplemental), ADP community (GTL >$50K), r/personalfinance
  (RSU, 401(k)).
- **E benefits** — Workday G2, SAP SuccessFactors community,
  BambooHR community, TASC + WageWorks (COBRA).
- **F year-end** — IRS forms forums, Gusto blog, r/CPA.
- **G time-tracking** — When I Work, Deputy, Connecteam, Paycor,
  Paylocity reviews.
- **H immigration** — USCIS guidance, HR Compliance community on
  LinkedIn, r/h1b.
- **I equity comp** — Carta, Schwab, Pulley, AngelList equity-comp
  content.
- **J DSAR / GDPR** — DPO community, Privacy Subreddit, ICO
  enforcement bulletins, Personio + Odoo `privacy_lookup` (researched
  in note 22).
- **K GL integration** — SAP HCM ↔ S/4HANA forum, Gusto ↔ QBO + Xero
  connector reviews, ADP ↔ NetSuite Connector reviews, Big-4
  transfer-pricing blogs.
- **L OSS-specific** — OFBiz JIRA HUMANRES wishlist, OCA payroll
  repo, Frappe / ERPNext forum, Tryton mailing list.

---
date: 2026-05-17
title: 74 — HR / payroll internal gap analysis (kontor substrate audit + maintainer design calls)
status: draft
audience: maintainer — decisions needed before any Stage R schema or ADR lands
---

# 74 — HR / payroll internal gap analysis

Stage R research-before, file 3 of 3. Companion to
[[72-hr-payroll-reference-study]] (OFBiz primary reference) and
[[73-hr-payroll-market-pain]] (vendor / community pain catalog).

This note answers: **given the existing kontor substrate, what does
Stage R need to add, what does it get for free, and what design
calls does the maintainer have to make before any code lands?**

## TL;DR

- **~60% of the Stage R substrate already exists.** Status machine,
  audit-doc, legal-hold, retention, DSAR, schedule, multi-entity
  postings, parallel ledgers, period model, process orchestrator —
  all the cross-cutting kernel primitives the per-stage rhythm
  (ADR-037) expects already cover HR's needs by construction.
- **~30% is clearly missing and needs a new companion** (`kontor-hr`):
  `:person` / `:employment` / `:department` / `:absence` /
  `:pay-period` / `:benefit-enrollment` / `:deduction-type` /
  `PayrollProvider` protocol.
- **~10% is ambiguous and requires the maintainer to pick a side.**
  Five design calls below (§3); each affects the shape of the ADRs
  and the modules. None are revisitable cheaply once code lands.
- **The maintainer needs to decide before code:** (1) substrate-vs-
  companion split for `:person`, (2) multi-job-per-person vs. one-
  record-per-(employee, employer), (3) employee-as-`:partner` vs.
  separate root entity, (4) PayrollProvider's exact shape, (5) PII
  privilege handling (extend `:audit-doc/privilege` or new
  `:audit-doc/category`).

---

## 1. What the kontor substrate already provides

Each existing primitive that HR / payroll needs, with file:line
citations to the kontor source so the gap analysis is concrete and
the Stage R ADRs can cite the *consumption*, not redefine the
mechanism.

### 1.1 `kontor.status-machine` (ADR-034)

`src/kontor/status_machine.clj:43-360`. Status-as-data; `:status-
transition` rows seeded per module; `record-status-change!` gates;
`sweep-time-based!` for SLA-driven (ADR-041).

**What HR consumes** — six facets, all seed-only:
`:employment/state` (`:applicant → :offered → :hired → :active →
:on-leave → :terminated → :rehired`); `:absence/state` (`:requested
→ :approved | :rejected → :taken | :cancelled`); `:position/state`
(v2; `:planned → :active → :inactive`); `:payroll-correction/state`
(`:proposed → :approved → :posted | :rejected`);
`:benefit-enrollment/state` (`:proposed → :elected → :effective →
:ended`); `:cobra-notification/state` (`:scheduled → :sent →
:acknowledged | :missed`, SLA-driven).

**Net new HR code:** seed rows only. Zero new primitives.

### 1.2 `kontor.audit-doc` (ADR-038 / ADR-051)

`src/kontor/audit_doc.clj` (319 LoC) + schema at
`src/kontor/schema.clj:3480-3539`. Documents with content-hash,
storage-URI, uploader UID, status-machine facet, and
**legal-privilege classification** (`:audit-doc/privilege` —
open-set keyword, `:pii-sensitive` available).

**What HR consumes:** employment contracts (`:audit-doc/type
:contract` linked via `:employment/contract-doc`; bitemporal pull at
filing date answers "what were the terms on March 31?"); contract
amendments (`:supersedes` ref); W-9 / W-4 / I-9 / DE5 forms (per-type
keyword + `:pii-sensitive` privilege); garnishment orders
(`:pii-sensitive` + retention per state law); QLE evidence on
`:benefit-enrollment/status-history`; visa documents with
`:audit-doc/expiry-date` for schedule-sweep alerts; 409A / equity
grant agreements with `:audit-doc/effective-date`.

**Net new HR code:** `:audit-doc/type` enum seeds in
`kontor-hr/install!`. The `:audit-doc/privilege` enum already covers
`:pii-sensitive`.

### 1.3 `kontor.legal-hold` + `kontor.retention` (ADR-049 + ADR-050)

`src/kontor/legal_hold.clj` (597 LoC) + `src/kontor/retention.clj`
(560 LoC). Per note 22, the kernel covers hybrid hold scope
(explicit eid set OR datalog scope-query) AND retention with
`:min-keep` + `:max-keep` floors/ceilings.

**What HR consumes.** Personnel-record retention is jurisdictionally
distinct from accounting retention. Examples: DE personnel 6y after
termination (BetrVG §83), DE payroll 6y (HGB §257(1) Nr.4 + AO
§147(3)), DE tax-relevant 10y (HGB §257(1) Nr.1), US I-9 3y after
hire OR 1y after termination (8 USC 1324a + 8 CFR 274a.2), UK PAYE
6y (HMRC), FR personnel 5y (Code du Travail). These are different
`:retention-policy` rows per (jurisdiction, entity-type). Legal hold
over personnel data (EEOC, wage-and-hour) — `:legal-hold/scope-
query` is a datalog query over `:partner [:partner/kind :employee]`
plus related `:audit-doc` rows. **Retention vs. right-to-erasure
conflict** (note 73 Theme J): `:min-keep` (statutory floor) AND
`:max-keep` (GDPR ceiling) → `eligible-for-purge?` picks the tighter.

**Net new HR code:** per-country `:retention-policy` seeds in
`kontor-l10n-<cc>` modules, mirroring how l10n modules already seed
tax tables (note 22 §1).

### 1.4 `:dsar-request` (ADR-052)

`src/kontor/dsar.clj` (485 LoC) + schema at `src/kontor/schema.clj
:785-866`. `kontor.dsar/collect` aggregates data for a partner.

**What HR consumes:** Article 15 (GDPR), 1798.110 (CCPA), Mexico
LFPDPPP requests against personnel data. `:dsar-request/partner` →
`:partner [:partner/kind :employee]`; `collect` pulls employment,
absence, benefit, deduction, payslip, and doc data subject to
`:audit-doc/privilege` redaction (`:attorney-client`-flagged docs
excluded with redaction-reason).

**Net new HR code:** zero — collector walks `:partner`-linked
entities; new HR entities just need to be reachable.

### 1.5 `:ledger/framework` (ADR-021 — parallel ledgers)

`src/kontor/schema.clj:2501-2506`. Free-form keyword
(`:IFRS | :US-GAAP | :HGB | …`).

**What HR consumes:** PTO accrual liability (IAS 19 / ASC 710 vs.
DE HGB §249 "earned only"); wage accrual at period end; severance
accrual (IFRS IAS 19 vs ASC 712 vs HGB §249 differ on recognition);
employer SI contributions (book expenses as paid; tax may differ).
The parallel-ledger pattern lets one payroll-period accrual post
differently to book vs tax ledgers without information loss.

**Net new HR code:** consumer-side posting-builder helpers.

### 1.6 `kontor.period` (ADR-014)

`src/kontor/period.clj` (449 LoC). Fiscal periods open → locked →
sealed, plus the `:adjustment-13` escape (DE Spezialperiode).

**What HR consumes:** payroll periods align (monthly pay-period fits
a fiscal month; biweekly straddles); pay-period close mirrors
`:period/locked-at`; backdated correction on a locked period routes
via `:adjustment-13` (note 69 §3.2 traced this end-to-end).

**Decision needed:** Reuse `:period/kind :payroll` or ship separate
`:pay-period/*`? See §3 design call 4.

### 1.7 `:posting/entity` (ADR-031 — multi-entity)

`src/kontor/schema.clj:3058-3120`; per-entity sum-to-zero in
`kontor.posting/build-transaction` (`posting.clj:184-196`).

**What HR consumes:** multi-entity employment (Workday "multi-job"
per note 09 §1) — concurrent `:employment` rows on `acme-de-gmbh`
and `acme-us-llc` produce payroll postings that sum-to-zero per
(entity, ledger, commodity) without consumer code; intra-group
secondment / global assignment (reimbursement is intercompany per
note 69 §3.1 + Gap 4); per-entity `PayrollProvider` impl dispatches
on `:posting/entity`.

**Net new HR code:** zero substrate; consumer flips
`:employment/entity` on transfer.

### 1.8 `:schedule/*` (ADR-032)

`src/kontor/schedule.clj` (244 LoC). Schedule-as-data; drives PTO
accrual, prepaid amortization, lease ROU depreciation.

**What HR consumes:** PTO accrual (`:schedule/code "pto-accrual-
monthly"` → emits `:absence-allocation` per period); payroll-period
accruals; visa expiry alerts (`:schedule/frequency :on-date` +
`:schedule/lead-time-days 90` surfaces `:exception/state :open`
90d before `:audit-doc/expiry-date`); COBRA 14-day sweeper
(ADR-041); benefit-enrollment auto-roll at year-end.

**Net new HR code:** zero substrate; HR consumes the primitive.

### 1.9 `kontor.process` (ADR-067)

`src/kontor/process.clj` (138 LoC). Composes N `*-tx-data` builders
into one gated atomic commit; serializes on `conn`.

**What HR consumes:** gross-to-net is a multi-step process (gross,
deductions, employer accruals, payable rows); hire process
(employment create + contract attach + status-history + initial wage
+ initial enrollments); termination process (end-date + reason +
final paycheck + severance + COBRA scheduling + separation docs);
payroll-correction process (reversal + corrected + status-history +
supporting doc), gated through legal-hold + period per note 69 §3.2.

**Net new HR code:** zero substrate; HR-side composes per ADR-068.

### 1.10 Provider-pattern precedent (ADR-005)

`src/kontor/tax_provider.clj` (the protocol; note 69 §2.5 flagged
it un-wired but the *shape* is the model) and the new
`src/kontor/fx_rate_provider.clj` are the two existing examples.
Stage R `PayrollProvider` mirrors.

### 1.11 Summary table

| Cross-cutting need | Existing kontor primitive | File:line | New code in Stage R |
|---|---|---|---|
| Employment lifecycle state machine | `kontor.status-machine` | `status_machine.clj:43-360` | Seed rows only |
| Absence lifecycle state machine | `kontor.status-machine` | (same) | Seed rows only |
| Contract / I-9 / W-4 retention with content-hash | `kontor.audit-doc` | `schema.clj:3480-3539` | `:audit-doc/type` seeds |
| Personnel PII privilege | `:audit-doc/privilege :pii-sensitive` | `schema.clj:3525-3539` | Consumer tags appropriately |
| Per-jurisdiction personnel retention | `:retention-policy` | `retention.clj` + `schema.clj:664-` | l10n seed rows |
| Legal hold over personnel records | `:legal-hold` | `legal_hold.clj` | Consumer concern (scope-query) |
| Article 15 / CCPA subject-access | `:dsar-request` + `kontor.dsar/collect` | `dsar.clj` + `schema.clj:785-866` | Reachability only |
| Book vs tax basis PTO/wage accruals | `:ledger/framework` parallel ledgers | `schema.clj:2501-2506` | Consumer-side helper |
| Payroll vs fiscal period alignment | `kontor.period` | `period.clj` | Reuse OR sibling (§3 design call 4) |
| Multi-entity employment | `:posting/entity` | `schema.clj:3058-3120`, `posting.clj:184-196` | Consumer concern |
| PTO accrual / payroll accrual / visa alerts | `kontor.schedule` | `schedule.clj`, ADR-032 | Consumer concern |
| Gross-to-net as multi-step | `kontor.process` | `process.clj:110-138` | Consumer concern |
| Per-country payroll engine boundary | (ADR-005 pattern) | `tax_provider.clj` is the shape | `PayrollProvider` protocol |

---

## 2. What is clearly missing — `kontor-hr` companion shape

The substrate-vs-companion call (§3 design call 1) determines whether
some of these land in the kernel or in `kontor-hr`. Below is the
*entities-needed* list; placement is the design call.

### 2.1 Personnel entities

```clojure
:person                          ; global human identity
  :person/{external-id, given-name, family-name, birth-date, gender,
           citizenship (multi-card), nationality}

:employment                      ; per-entity, effective-dated (OFBiz Employment shape)
  :employment/{person, entity, department, job-title, manager,
               start-date, end-date, work-schedule, cost-center,
               wage, wage-commodity, wage-period (:monthly | :hourly |
               :annual | :biweekly), exempt-flag (FLSA/Beamter/cadre),
               fulltime-flag, termination-reason, contract-doc (→
               :audit-doc), state (ADR-034 facet)}

:department                      ; recursive per-entity org tree
  :department/{code, name, entity, parent-department, manager}
```

### 2.2 Time / absence entities

```clojure
:work-schedule    :work-schedule/{code, name, hours-per-week,
                                   holiday-calendar, standard-week}
:absence-type     :absence-type/{code, name, parent-type, description,
                                  accrual-rule (→ :schedule)}
:absence-reason   :absence-reason/{code, name, parent-reason}
:absence          :absence/{employment, type, reason, start-date,
                             end-date, hours, approver,
                             state (ADR-034 facet)}
:absence-allocation :absence-allocation/{employment, type, period,
                                          hours-granted, hours-used,
                                          hours-remaining}  ; schedule-emitted
```

### 2.3 Pay-period entities

```clojure
:pay-period   :pay-period/{entity, start-date, end-date, frequency,
                            state (:open → :locked → :sealed),
                            fiscal-period (→ :period)}
:payroll-run  :payroll-run/{pay-period, entity, provider-id, state,
                             control-totals}
```

### 2.4 Benefits + deductions entities

```clojure
:benefit-type        :benefit-type/{code, name, employer-paid-percentage,
                                     parent-type}
:benefit-enrollment  :benefit-enrollment/{employment, benefit-type,
                                           start-date, end-date, cost,
                                           employer-percentage-actual,
                                           state, qle-evidence (→ :audit-doc)}
:deduction-type      :deduction-type/{code, name, pre-tax-flag, account,
                                       parent-type}
:payroll-preference  :payroll-preference/{employment, deduction-type,
                                           percentage, flat-amount,
                                           period-type, payment-method (→
                                           :bank-account), start-date,
                                           end-date}
```

### 2.5 `PayrollProvider` protocol

Mirrors `TaxProvider` (ADR-005) and `FxRateProvider`. Lives in
`src/kontor/payroll_provider.clj`:

```clojure
(defprotocol PayrollProvider
  (provider-id [this])
  (compute-payroll [this {:keys [pay-period entity employment-eids
                                  variable-inputs]}]
    "Returns :payroll-result schema per note 09 §7.")
  (emit-payroll-events [this {:keys [pay-period entity]}]
    "Per-jurisdiction event emission (eSocial S-1200, DATEV
     Lohnimport, ADP file, etc.)"))
```

### 2.6 Per-jurisdiction adapters (separate modules)

Per note 09 §7 + the Personio-DATEV pattern: `kontor-payroll-de-datev`
(DATEV LODAS), `kontor-payroll-de-personio` (Personio → DATEV),
`kontor-payroll-us-{adp-export, gusto, rippling}`,
`kontor-payroll-br-esocial` (40+ event types),
`kontor-payroll-{fr-silae, uk-hmrc-rti, in-zenhr, au-stp}`. None
bundled in kernel; no vendor API keys.

### 2.7 What's NOT in v1 (per note 09 §10 and note 72 §5)

`:position` + `:position-fulfillment` + `:position-reports-to`;
`:employment-application` + `:job-requisition` + `:job-interview`;
`:performance-review`; `:skill`; `:training-*`; `:unemployment-claim`;
`:pay-grade` + `:salary-step`; recruitment workflows; onboarding /
offboarding orchestration (UI concern).

---

## 3. Ambiguous design calls — the maintainer needs to choose

Five calls. None can be "decided later" cheaply once code lands —
they affect schema namespacing, the substrate-vs-companion split,
and which entities `:posting/partner` can point to.

### 3.1 Design call 1 — Substrate vs companion for `:person` + `:employment`

**Question.** Does the kernel ship `:person/*` + `:employment/*` or
does `kontor-hr`?

**For kernel.** `:posting/partner` already references `:partner/*`
in the kernel (schema.clj:480-549); employment is structurally
similar. ADR-039 already promotes `:partner/*` despite being
domain-shaped. Six downstream companions could reference employee
identity (hr, expense, procurement, authz, project, fleet) — the
ADR-034 "six independent inventions" threshold.

**For companion.** Kernel is intentionally narrow (CLAUDE.md). Many
deployments won't have HR-shaped employees (single-founder
consultancies, SaaS using external payroll). ADR-002 cohabitation
permits companion-namespaced attrs. The substrate's cross-cutting
primitives (status-machine, audit-doc, legal-hold, …) are kernel; the
*domain* of employment is companion (symmetric with `:order`,
`:invoice`, `:lease`).

**Recommendation: Companion (`kontor-hr`).** The cross-references are
FK-to-identity, not re-invention. If maintainer disagrees, promote
only `:person` and keep `:employment` in companion — the human
exists across domains, the relationship belongs to HR.

### 3.2 Design call 2 — Multi-job per person

**Question.** Workday "Worker with N concurrent employments," or
"one record per (employee, employer)" (Tryton / Personio pattern)?

**For multi-job.** Note 09 §1 recommends it. Trans-national use cases
need it (note 69 §3.1-3.2; note 73 Theme C): executive employed by
DE GmbH AND seconded to US LLC has two employments. ADR-031's
`:posting/entity` axis maps cleanly. Matches OFBiz `Employment`
shape. Bitemporal substrate makes "Jane's employments on March 31"
a `(d/valid-at db t)` query.

**For one-per-employer.** Simpler. SMB market expectation. Per-
employment cost-center attribution works without multi-job via
analytic distribution.

**Recommendation: Multi-job (Workday pattern).** Substrate cost of
supporting it is zero (1:N relation). Substrate cost of retrofitting
later is high (data migration). The overwhelming case from note 73
Theme C is trans-national needs it — and that's the pitch. Mid-
market deployments create one `:employment` per `:person` and never
notice.

### 3.3 Design call 3 — Employee as `:partner` vs. separate root

**Question.** Model "employee" as `:partner [:partner/kind
:employee]` (reuse ADR-039), or as a new `:person` root with
`:employment` linking?

**For reusing `:partner/kind :employee`.** OFBiz precedent (Party +
PartyRole + Employment, note 72 §1.2). `:posting/partner` already
exists — expense reimbursement works natively. Reduces entity count.
Aligns with ADR-039.

**For separate `:person` root.** Data shape is divergent: `:partner`
has tax-id, credit-limit, kyc-status (schema.clj:506-547), none
applying to humans; conversely employees need birth-date,
citizenship, passport — none applying to companies. Privacy: an
employee record carries PII a `:partner` doesn't. OFBiz itself
separates `Party` (root) from `Person` (extension).

**Recommendation: Hybrid** (note 09 §1 sketch). `:person` = new root
in `kontor-hr` (or kernel per design call 1), carrying human-only
attributes. `:partner` (ADR-039) gains `:partner/kind :employee` for
the cases where the employee is a payer/payee (expense reimbursement,
loans, travel advance). `:partner` of kind `:employee` carries
`:partner/person` ref → `:person`. `:posting/partner` → `:partner`;
`:employment/person` → `:person`. This is OFBiz translated: Party =
kontor `:partner` (FK target for postings); Person = kontor
`:person` (human attributes); Employment = kontor `:employment`
(relationship row).

### 3.4 Design call 4 — Payroll engine: substrate vs companion

**Question.** Kernel ships `PayrollProvider` protocol (mirror
`TaxProvider`), or companion-only?

**For kernel protocol.** Mirrors ADR-005 and the new
`FxRateProvider`. Without a kernel-level protocol every consumer
re-invents the boundary (same critique as note 69 §2.5 on inline
tax math). `:payroll-result` (note 09 §7) deserves a normalized
kernel-level shape.

**For companion-only.** Kernel is double-entry. Payroll computation
isn't. Protocol surface is larger than tax (compute + emit
jurisdictional events; DATEV LODAS has hundreds of wage-types).

**Recommendation: Kernel-level `PayrollProvider`, two methods**
(`compute-payroll` → `:payroll-result`; `emit-payroll-events` →
side-effects for consumer to forward). Lives in
`src/kontor/payroll_provider.clj`. Per-jurisdiction adapters
(`kontor-payroll-*`) ship impls. No vendor API keys bundled.

**Sub-question: `:pay-period` vs `:period/kind :payroll`?** Keep
separate. `:pay-period/fiscal-period` references containing fiscal
`:period`. Reasons: different lifecycle vocab (`:open → :processed →
:posted → :paid` vs `:open → :locked → :sealed`); per-entity pay-
period frequency varies (DE monthly + US biweekly); OFBiz / Odoo /
SAP / Workday all separate them.

### 3.5 Design call 5 — PII handling: extend `:audit-doc/privilege` or add `:audit-doc/category`?

**Question.** Three options: (A) consumer extends `:audit-doc/
privilege` enum (`:pii-payroll` etc.); (B) new `:audit-doc/category`
keyword as a second orthogonal axis; (C) extend the privilege enum
in the kernel.

**Recommendation: Option B.** Privilege is legal-doctrine class
(attorney-client, work-product, trade-secret). Category is subject-
matter domain (payroll, HR, medical, finance). Two axes give the
consumer auth layer the right grid. Auth rules like "HR role can
access category `:payroll` regardless of privilege" or "auditor
role can access privilege `:attorney-client` regardless of
category" need both axes. Kernel addition is ~5 lines + ADR addendum
to ADR-051; default nil = uncategorized; no migration.

---

## 4. Next-step decisions the maintainer needs to make

Before any Stage R code lands, the maintainer must confirm or
override the five recommendations above:

1. **Substrate vs companion:** Companion (`kontor-hr`)? Or kernel?
2. **Multi-job:** Workday pattern (N employments per person)? Or
   single per (person, entity)?
3. **Employee identity:** Hybrid `:person` (root) + `:partner/kind
   :employee` (linker)? Or one or the other?
4. **`PayrollProvider`:** Kernel-level protocol mirroring `TaxProvider`,
   two methods (`compute-payroll` + `emit-payroll-events`)? Or
   companion-only?
5. **PII classification:** Add `:audit-doc/category` orthogonal to
   `:audit-doc/privilege`? Or extend the privilege enum?

Plus three sequencing decisions:

6. **Stage R promotion vs. note 69 §6 reshape items.** Note 69 §6
   lists six reshape items (kernel directory reorg, `:entity` filter
   on read-side, FxRateProvider, etc.) that the architecture review
   ranked higher priority than Stage R for trans-national fit.
   Does Stage R land *after* them (current roadmap), or does the
   `:entity` filter at least land in parallel so the multi-entity
   employment story has working reports?

7. **First country adapter.** Which `kontor-payroll-<country>-<vendor>`
   lands alongside the substrate? Personio-DATEV is the canonical
   adapter shape (note 09 §3); DATEV is the maintainer's home
   jurisdiction; would prove the protocol end-to-end. Or US-ADP-export
   (largest market)?

8. **Cross-cutting validation timing.** Per ADR-037 cross-stage
   user-story validation, the Stage R end-to-end test is "DE GmbH
   employee on assignment to US LLC, monthly payroll, mid-month
   wage change, Q1 correction in Q2." Run it on this Stage R or
   defer to a post-stage cycle?

## 5. Risk / blast radius if the design calls go wrong

| Design call | Wrong-direction blast radius |
|---|---|
| #1 substrate vs companion | Kernel bloat (if wrong→kernel); ergonomic friction (if wrong→companion). Both fixable. |
| #2 multi-job | Schema migration if we ship single and later need multi. **High blast radius.** |
| #3 partner-vs-person | Schema migration of `:posting/partner` references. **Medium-high blast radius.** |
| #4 provider shape | Adapter-API breakage. **Medium blast radius** if we ship the wrong methods. |
| #5 audit-doc category | Low. The privilege enum is open-set; both options are extensible. |

**Take-away.** Spend the design discussion on calls #2, #3, #4
before any code lands; #1 and #5 are forgivable mistakes.

---

## 6. Cross-links

- Reference data model backing the schema choices:
  [[72-hr-payroll-reference-study]].
- Pain-points the substrate must serve: [[73-hr-payroll-market-pain]]
  themes A-L; §2 "five pain points kontor uniquely fits."
- Predecessor: [09-hr-personnel-payroll.md](09-hr-personnel-payroll.md).
- Architecture review (Stage R P1 trans-national):
  [69-architecture-review-and-fp-model.md](69-architecture-review-and-fp-model.md)
  §4 Gap 3, §6 item 6, §8 Q5.
- ADRs consumed: 005 (TaxProvider precedent), 021 (parallel ledgers
  for book-vs-tax accruals), 031 (multi-entity / multi-employment),
  032 (schedule for PTO + visa sweeps), 034 (status-machine), 038
  (audit-doc), 039 (partner master), 041 (sweepers for COBRA), 049
  (legal-hold), 050 (retention), 051 (privilege), 052 (DSAR), 067
  (process), 068 (universal `*-tx-data` builders).

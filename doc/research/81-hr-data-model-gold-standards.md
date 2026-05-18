---
date: 2026-05-18
title: 81 — HR data-model gold-standards (Workday / SuccessFactors / NetSuite / Oracle HCM / Gusto / Rippling / BambooHR / Deel / OrangeHRM / Frappe HR) vs note 79
status: draft
audience: maintainer — pre-C1 validation insurance for the Stage R schema choices
---

# 81 — HR data-model gold-standards study (vs note 79)

Stage R is implementation-ready per
[[79-hr-payroll-stage-r-plan]]. Note 79's five design calls were
grounded in OFBiz `humanres` (Apache-2.0, lift-safe, deep-studied in
[[72-hr-payroll-reference-study]]) plus broad market-pain canvassing
([[73-hr-payroll-market-pain]]). The **data-model details of the
modern gold-standard systems** were not yet looked at structurally
— this note fills that gap before C1 lands and refactor cost spikes.

Scope: read the data-model documentation of Workday, SAP
SuccessFactors Employee Central, Oracle Fusion HCM, NetSuite
SuitePeople, Gusto, Rippling, BambooHR, Deel, OrangeHRM, Frappe HR
(plus quick recap of OFBiz / Tryton / Odoo from note 72). For each:
the load-bearing entities, the "Worker vs Person" choice, multi-
employment shape, position-vs-job separation, compensation-as-own-
entity, effective-dating model, cross-border employment shape,
contractor distinction, benefits enrollment shape, time-off accrual,
PII / audit classification. Output: a comparative table, a per-
design-call **Confirmed / Refined / Reconsidered** verdict, and a
ship-vs-refactor recommendation for C1.

## §1 — TL;DR

- **Note 79's Workday-multi-employment call holds up.** Every
  enterprise-tier system (Workday, SuccessFactors, Oracle Fusion,
  NetSuite, Deel) models N concurrent employments/assignments per
  person; only Tryton-shaped SMB tools collapse it. Note 79's call
  is the strongest-grounded of the five.
- **Note 79's hybrid `:person` + `:partner/kind :employee` linker
  holds up.** OFBiz, Workday, SuccessFactors, Oracle Fusion, Frappe
  HR all separate the "human" record from the "employment context"
  record. The minor refinement: name the slot consistently with
  Oracle's vocabulary (`work-relationship` is the rest-of-industry
  term; kontor uses `:employment`). No schema change needed.
- **Compensation-as-its-own-entity is the only material thing note
  79 ducked.** Workday, SuccessFactors, Gusto, NetSuite, Oracle
  Fusion all model compensation as a *separate, effective-dated
  entity* attached to the employment/assignment — not as scalar
  attrs on the employment. Note 79 folded `:employment/wage` +
  `:employment/wage-period` + `:employment/wage-commodity` into
  `:employment/*`. **This is the refactor worth doing before C1.**
  See §9.
- **Position-as-separate-entity is correctly deferred.** Workday's
  Position Management is one of two staffing models — Job
  Management (no positions) is the other; both are first-class.
  SuccessFactors makes Position optional. Note 79 defers to C5+;
  evidence backs that call.
- **The "Worker" primitive is the modern term.** Workday calls
  it `Worker` (Employee + Contingent Worker as subtypes); Oracle
  Fusion calls it `Person` with employee / contingent-worker /
  applicant assignment types. Note 79's `:person` is structurally
  equivalent. **Minor: consider exposing `:person/kind` (open-set:
  `:employee | :contingent | :applicant | :retiree`) so contractor
  payroll lands without retrofit.** This is a 1-attr add, not a
  refactor.
- **Effective-dating: kontor's bitemporal substrate is structurally
  cleaner than any system surveyed.** Workday, SuccessFactors,
  Oracle Fusion all implement "every-change-is-a-new-row" by
  convention; kontor gets it for free via `:db.valid/from`. No
  change needed; note 79's "bitemporal `:employment/wage` replaces
  PayHistory" is validated by every gold-standard system's pain
  description (Odoo `hr.version`, SuccessFactors `startDate`, Oracle
  `_F` suffix tables — all reinventing what kontor has natively).
- **Net recommendation: ship C1 with one schema refactor.** Lift
  `:wage`/`:wage-commodity`/`:wage-period` off `:employment` into a
  separate `:compensation` entity referencing `:employment` and
  carrying its own bitemporal axis. ~3 hours of schema work; saves
  ~3 days of migration debt later when the first consumer (DE C2)
  needs to model recurring pay components (Christmas bonus,
  vermögenswirksame Leistungen, employer pension contribution) as
  separate compensation records. **Everything else in note 79 ships
  as-is.**

---

## §2 — Note 79's schema (the comparison baseline)

Note 79's locked design calls (§2 of that note):

1. **Companion-tier placement.** `:person/*`, `:employment/*`,
   `:department/*`, `:pay-period/*`, `:payroll-run/*`,
   `:benefit-*`, `:absence-*` live in `kontor-hr`, not the kernel.
   Only `:audit-doc/category` + `:retention-policy/category` are
   added kernel-side.
2. **Workday-style multi-employment.** `:employment/person` is
   `:db.cardinality/one`; a `:person` may have N concurrent
   `:employment` rows, one per `:employment/entity` (employer-side
   entity per ADR-031). Re-hire = new `:employment` with later
   `:start-date`.
3. **Hybrid identity.** `:person` is a new root in `kontor-hr`;
   `:partner/kind :employee` exists in the kernel's open-set enum;
   `:partner/person` ref (set when `:partner/kind=:employee`) bridges
   them. `:posting/partner` stays `:partner`; `:employment/person` is
   `:person`.
4. **Three-protocol-plus-emit `PayrollProvider`** mirroring ADR-071:
   `PayrollComputeProvider` (gross-to-net), `PayrollFacts` (pure-data
   shape), `PayrollPostingBuilder` (GL CoA mapping),
   `PayrollEmitProvider` (jurisdictional event-bus).
5. **`:audit-doc/category` as orthogonal axis** to
   `:audit-doc/privilege` from ADR-051 — kernel-side, open-set, the
   only kernel schema add.

Schema sketch (note 79 §3, ~14 attrs across 6 entities):

```
:audit-doc/category, :retention-policy/category               (kernel)
:person/external-id | given-name | family-name | birth-date |
  citizenship | national-id | state                            (kontor-hr)
:partner/person                                                (kontor-hr)
:employment/{person, entity, start-date, end-date, job-title,
  department, manager, wage, wage-commodity, wage-period,
  exempt-flag, fulltime-flag, contract-doc, state,
  termination-reason}                                          (kontor-hr)
:department/{code, name, entity, parent, manager}              (kontor-hr)
:pay-period/{entity, start-date, end-date, frequency,
  fiscal-period, state}                                        (kontor-hr)
:payroll-run/{pay-period, provider-id, state,
  control-total-gross, control-total-net, payroll-facts}       (kontor-hr)
```

The single most-attended-to detail in note 79 is the bitemporal
treatment of `:employment/wage` — note 79 §3 calls out that
`:db.valid/from` is the per-attr effective-date axis, replacing the
OFBiz `PayHistory` sidecar. We will check that against the gold
standards in §3-7.

---

## §3 — Workday Worker model (deep)

License: proprietary; data-model documentation is partially public
via the Community API docs and the "Object Transporter" datasheets.
The reverse-engineered surface from API docs + tenant
documentation is detailed enough to compare structurally.

### 3.1 — Worker as root

Workday's primary entity is `Worker`, not `Employee`. `Worker` has
two subtypes — **Employee** and **Contingent Worker** — sharing the
common `Worker` identity. A Worker carries a stable `Worker ID`
that persists across employments, terminations, and re-hires. Re-
hire reuses the same `Worker` record; the prior employment is
historic.

For kontor: this is exactly the `:person` shape note 79 picked
(global human identity, stable across employments). The note 79
call is well-grounded; the only refinement worth making is exposing
a `:person/kind` slot (`:employee | :contingent | :applicant |
:retiree`) so contingent-worker / contractor / freelance payroll
doesn't force a schema migration later. Cheap (1 attr) and matches
both Workday and Oracle's classification approach.

### 3.2 — The four-level job hierarchy

Workday separates four concepts that note 79 collapses to one
(`:employment/job-title`):

| Level | Workday term | What it is |
|---|---|---|
| 1 | **Job Family Group** | Broad org category — "Engineering," "Sales," "G&A" |
| 2 | **Job Family** | Mid-level grouping — "Software Engineering," "Solutions Engineering" |
| 3 | **Job Profile** | The reusable "template" of a role — title, description, compensation grade, FLSA flag, management level |
| 4 | **Position** | A specific seat tied to a Supervisory Org; optional (see §3.4) |

A `Worker` is **assigned to a Job Profile** (and optionally a
Position). The Job Profile carries `Management Level`, FLSA
classification, compensation grade pointer, and high-level
description.

**For kontor:** the four-level hierarchy is overkill for C1. The
note 79 `:employment/job-title` (free-text string) is the minimum
useful — note 79 §9 lists `:position` as deferred. The relevant
*structural* take-away is that Workday separates "the role
template" (Job Profile) from "the seat" (Position) from "the
person" (Worker). Note 79 collapses all three to `:employment` for
v1. **Defensible**: every gold-standard system supports a
collapsed-model deployment too (Workday's Job Management staffing
model has no Position entity).

### 3.3 — Compensation as its own entity

Workday's `Compensation` is **not** a scalar on the worker or
position — it's a separate, effective-dated record with its own
ID. A `Compensation Plan` references a Compensation Grade and a
Salary/Hourly Plan. Each "Pay Change" (merit increase, promotion-
related adjustment, market adjustment) creates a new Compensation
record dated forward; the prior remains queryable. Multiple
**Pay Components** (base, bonus target %, allowance, car
allowance, equity grant target) live under one Compensation
record.

**For kontor:** this is the gap. Note 79 has
`:employment/wage` + `:wage-period` + `:wage-commodity` as scalars
on `:employment`. The bitemporal `:db.valid/from` makes "what was
Jane's wage on 2026-03-31?" work via `(d/valid-at db t)` — but the
moment a DE consumer needs Christmas bonus + base wage as
*separate, simultaneously-active* records (different SKR04
accounts, different employer-SI treatment), the scalar approach
forces re-jigging. **Refactor recommendation in §9 lifts these
attrs to a `:compensation` entity.**

### 3.4 — Position Management vs Job Management

Workday supports **two staffing models** within one tenant:

- **Position Management** — every hire fills a pre-defined Position
  with hiring restrictions (FLSA, comp grade, manager). Common for
  budget-disciplined enterprises.
- **Job Management** — hiring restrictions live on the Supervisory
  Organization; no separate Position entity per seat.

Workday's documentation explicitly supports **mixed**
configurations: position management for some Supervisory Orgs, job
management for others, designed intentionally.

**For kontor:** confirms note 79's call to defer `:position`. Even
Workday treats position management as optional. The kontor analog
is: ship `:employment/*` first (= Job Management), add `:position`
+ `:position-fulfillment` later for consumers who need headcount
budgeting (note 79 §9 open question 1).

### 3.5 — Supervisory Organization, Org Assignments, Effective-dating

`Supervisory Organization` is a tree of teams; reporting lives at
the org level, not the worker level. Note 79's `:department`
(recursive) + `:department/manager` + `:employment/manager`
captures this. No change.

Workday `Organization Assignments` (Cost Center, Business Unit,
Region, Location, Company, Currency) map to kontor's existing
primitives: `:employment/entity` (Company), `:analytic-account`
(Cost Center, Business Unit; ADR-022). No new HR entity needed.

Workday's `Effective Date` + `Entry Date` is **bitemporal in
intent** — convention-imposed by the application layer, not a
storage primitive. kontor's `:db.valid/from` + `:db.tx-time`
gives this at the database layer. **Note 79's bitemporal posture
is structurally superior to Workday's effective-dating-as-
convention.**

### 3.6 — Sources

Full URLs in §12. Cited from `community.workday.com` API docs (via
3rd-party guides — `unified.to`, `stitchflow.com`), Washington
State public Workday glossary, `kinnectx.com` and
`workdaynavigator.com` consultancy comparisons.

---

## §4 — SAP SuccessFactors Employee Central (deep)

License: proprietary; the OData v2 API reference is public and
exhaustive. SuccessFactors EC is the closest competitor to Workday
HCM in the enterprise tier; the data model is structurally similar
but explicitly **multi-entity per person**.

### 4.1 — The entity tree

The SuccessFactors EC entity tree, rooted at the human:

```
PerPerson                        ← global human identity (root)
├── PerPersonal                  ← personal attrs (name, gender, marital)
├── PerNationalId                ← per-country gov't IDs
├── PerEmail / PerPhone          ← contact channels (multi)
├── PerEmergencyContact          ← contacts
├── PerGlobalInfo<COUNTRY>       ← country-specific personnel data
│                                   (e.g. PerGlobalInfoUSA, PerGlobalInfoDEU)
└── EmpEmployment                ← N per person (multi-employment!)
    ├── EmpJob                   ← N per employment (effective-dated)
    ├── EmpCompensation          ← N per employment (effective-dated)
    │   └── EmpPayCompRecurring  ← N per compensation (each pay component)
    └── EmpPayCompNonRecurring   ← N per employment (one-off payments)
```

The split is **load-bearing**:

- **`PerPerson` = global identity.** One per actual human. Stable.
- **`EmpEmployment` = the legal relationship**, one per legal-entity-
  employer pair. **A worker who's an Acme-DE employee AND an Acme-
  US contractor has two `EmpEmployment` rows. Identical to note
  79's call 2 (Workday multi-employment).**
- **`EmpJob` = the assignment within an employment** — job title,
  department, manager, location, FTE, employee class. Effective-
  dated.
- **`EmpCompensation` = the compensation envelope** for an
  employment, effective-dated. Carries `frequency`, `currencyCode`,
  `payGroup`. **Pay components live one level below as
  `EmpPayCompRecurring`** (recurring components like base wage,
  housing allowance, employer pension) and `EmpPayCompNonRecurring`
  (one-offs like signing bonus).

### 4.2 — The split kontor missed

Note 79 collapses `EmpEmployment` + `EmpJob` + `EmpCompensation` +
`EmpPayCompRecurring` into a single `:employment` entity. The
attributes on note 79's `:employment` are:

- "EmpEmployment-shaped" — `:person`, `:entity`, `:start-date`,
  `:end-date`, `:termination-reason`, `:state`
- "EmpJob-shaped" — `:job-title`, `:department`, `:manager`,
  `:exempt-flag`, `:fulltime-flag`, `:contract-doc`
- "EmpCompensation-shaped" — `:wage`, `:wage-commodity`,
  `:wage-period`

The first two are reasonable to collapse for kontor's posture — a
DE Mittelstand consumer creating their first 5-employee setup
benefits from one entity, not three. **The third is where
collapsing bites.** Pay components (base / Christmas bonus /
employer pension / vermögenswirksame Leistungen) are *multi-
cardinality* in every country with non-trivial compensation
structure; one attr can't carry them. See §9 refactor.

### 4.3 — Effective-dating: startDate/endDate

SuccessFactors EC uses **`startDate` / `endDate` pairs on every
versioned entity** — `EmpJob`, `EmpCompensation`,
`EmpPayCompRecurring`, etc. Each amendment writes a new row with
a new `startDate`, and the prior row's `endDate` is the day
before. (Identical to OFBiz `fromDate`/`thruDate` pattern note 72
§1.2 documented at depth.)

The OData API has a special `asOfDate` parameter that returns the
effective-as-of state. This is, structurally, valid-time bitemporal
— the system-time axis is implicit (the `lastModifiedDate` field).

kontor's `:db.valid/from` / `:db.valid/to` + datahike's
`:db.tx-time` collapses the SuccessFactors pattern to one storage
primitive (no `startDate`/`endDate` per attr needed). **Note 79's
take-away from §3 holds: kontor wins on storage primitive.**

### 4.4 — Benefits and time-off

`BenefitProgram` + `BenefitEnrollment` is the benefits stack.
`EmpTimeOffCalendar` + `EmpTimeAccountAdjustment` is the time-off
stack. Neither is on the C1 path per note 79 §3 deferral list.

Note: SuccessFactors' time-account model is the most general
surveyed — `EmpTimeAccount` is the running balance, with
`Adjustment` events modifying it, and `EmpTimeOffCalendar` is the
schedule of accruals. **When kontor lands `:absence-allocation` in
C4 (per note 79 §3 deferral), this is the structural template to
match.** No change to C1.

### 4.5 — Per-country layering: PerGlobalInfo<COUNTRY>

SuccessFactors uses per-country extension entities — `PerGlobalInfoUSA`
carries `ssn`, `ethnicity`, `veteranStatus`, `i9Status`;
`PerGlobalInfoDEU` carries `versicherungsnummer`,
`steueridentifikationsnummer`. **One entity per (person, country).**
This is exactly the "per-l10n country extension" pattern note 79
puts in `kontor-l10n-<cc>` modules.

For kontor: confirms the per-country attribute extension goes in
`kontor-l10n-de` / `kontor-l10n-us`, not in `kontor-hr`. **No
change needed.**

### 4.6 — Sources

Full URLs in §12. SAP Help Portal data-model entry, EmpPayCompRecurring
docs, SAP Note 2619357 (Employee creation sequence), 3rd-party
walkthroughs at `erpqna.com`.

---

## §5 — NetSuite SuitePeople + Oracle Fusion HCM (medium depth)

License: proprietary (both). Oracle Fusion HCM's table-level data
model is documented at `docs.oracle.com/en/cloud/saas/human-
resources/<release>/oedmh/` — searchable, schema-browser quality.
NetSuite's SuitePeople employee record is documented in the Online
Help and the SOAP Schema Browser.

### 5.1 — Oracle Fusion HCM: Person + Work Relationship + Assignment

Oracle Fusion's data model is the **three-tier employment model**:

| Tier | Entity | What it is |
|---|---|---|
| 1 | `PER_ALL_PEOPLE_F` (Person) | Global human identity (`PERSON_ID`) |
| 2 | `PER_ALL_WORK_RELATIONSHIPS_M` (Work Relationship) | Legal employment tie to one Legal Employer |
| 3 | `PER_ALL_ASSIGNMENTS_M` (Assignment) | The specific role within a Work Relationship |

Oracle also supports a **two-tier model** for simpler deployments
(Work Relationship + Assignment, no Employment Terms layer).

The `_F` and `_M` suffixes mark **date-effective** tables —
multiple rows per logical entity over time, joined by `PERSON_ID`
plus `effective_start_date` BETWEEN. Identical pattern to OFBiz
`(fromDate, thruDate)`. Identical pattern to SuccessFactors
`startDate / endDate`.

**A Person can have multiple concurrent Work Relationships**, one
per Legal Employer. Each Work Relationship can have multiple
concurrent Assignments. **The model is generous on multi-
employment** — note 79's call 2 lines up with this exactly.

### 5.2 — The "Assignment" vs "Work Relationship" distinction

Oracle's vocabulary is the closest fit to kontor's intuitive
naming:

- **Work Relationship** = the legal tie ("Jane is an employee of
  Acme-DE-GmbH"). Carries hire-date, termination-date, legal
  employer, worker type (employee / contingent worker / pending /
  applicant).
- **Assignment** = the operational role ("Jane is a Senior
  Engineer in the Berlin office on the Platform team at 100% FTE").
  Multiple Assignments per Work Relationship for "Jane is both 60%
  Senior Engineer and 40% Engineering Manager."

For kontor: this is the **strongest argument** for compensation-
as-its-own-entity. Oracle separates "the legal tie" (Work
Relationship) from "the role" (Assignment) from compensation
(`PAY_*` tables, separate again). Note 79's `:employment` is best-
mapped to Oracle's "Work Relationship" — and the gold-standard
shape says compensation lives one level off it.

### 5.3 — Person-vs-Assignment distinction matters globally

Oracle's `PERSON_ID` is global; `ASSIGNMENT_ID` is local. When Jane
transfers from Acme-DE to Acme-US, her `PERSON_ID` carries over;
she gets new Work Relationship + Assignment rows tied to her
existing person. **This is the "global mobility" pattern.**

For kontor: `:person` (global) + `:employment` (per-employing-
entity) maps precisely. Note 79 §2.2 Trans-national pitch (the
executive seconded from DE to US) is exactly the Oracle pattern.
**Validated.**

### 5.4 — NetSuite SuitePeople: more SMB-shaped

NetSuite's Employee record (SuiteAnalytics record name `employee`)
is **flatter** than Workday / SuccessFactors / Oracle Fusion. The
single Employee record carries:

- Identity: `internalid`, `entityid`, `firstName`, `lastName`,
  `email`, `phone`
- Employment: `hireDate`, `releaseDate`, `supervisor`,
  `department`, `class`, `location`, `subsidiary` (= legal entity)
- Compensation: `compensationCurrency`, `paymethod`,
  `payFrequency`, `defaultExpenseReportCurrency`
- Payroll: `socialSecurityNumber`, `useTimeData`,
  `nextPaycheck`, embedded `payrollItems` sublist
- Custom fields: arbitrary `customFieldList`

**One Employee record per (person, subsidiary)**. Multi-subsidiary
employment ("Jane works for Acme-DE and Acme-US") creates two
separate Employee records, joined by convention (e.g., matching
`socialSecurityNumber`).

For kontor: NetSuite's shape is **structurally weaker** than
Workday / SuccessFactors / Oracle Fusion on multi-employment.
Note 79's call 2 (Workday-multi-employment) is the right call;
following NetSuite would have forced a step backward. The fact that
NetSuite is widely used despite this shape says that the SMB
market tolerates the limitation — but the trans-national pitch
(note 79 §1) is the differentiator, and there the multi-employment
shape is required.

NetSuite's effective-dating is **partial** — `compensationCurrency`
and similar are scalars (last-write-wins), while a separate
`employeeChange` record captures historical changes for audit. The
audit-vs-current split is awkward; SuccessFactors / Workday /
Oracle Fusion all do it cleaner.

### 5.5 — Sources

Full URLs in §12. Oracle Cloud Help Center
(`docs.oracle.com/en/cloud/saas/human-resources/...`) for table
references; `apps2fusion.com`, `unogeeks.com` for the employment-
model overview; NetSuite SOAP Schema Browser at
`netsuite.com/help/helpcenter/...` and Oracle's NetSuite docs.

---

## §6 — Modern SMB tools (Gusto / Rippling / BambooHR / Deel / Justworks)

### 6.1 — Gusto

API docs: `docs.gusto.com`. License: proprietary; public OpenAPI.

Entity model:

```
Employee  (root, per company)
  ├── home_address / work_addresses (multi, state-tax allocation)
  ├── jobs (MULTI per employee)
  │     └── compensations (MULTI per job over time)
  ├── federal_tax_setup / state_tax_setup (per state)
  ├── garnishments / benefits
```

Gusto is **multi-job from the start**: every Employee has N `Job`
records (title, location, pay rate). **`Job` is what kontor calls
`:employment`**; **`Compensation` under Job is the comp-as-
separate-entity pattern**. Each Job has multiple Compensations
over time (only one active); each carries `rate`, `payment_unit`
(Hour | Year), `flsa_status`. Pay-changes write new Compensation
rows, not Job edits.

Per-state tax setup matches note 73 Theme B P1 (multi-state remote
employees); kontor handles via `:posting/entity` + per-state tag +
`PayrollComputeProvider`. **Gusto's two-layer (Job, Compensation)
split is the direct refactor target for §9.** Even at SMB scale,
separating Compensation from Job is the right call.

### 6.2 — Rippling

API docs: `developer.rippling.com`. License: proprietary.

Rippling's positioning is **the "Employee Graph"** — every system
(HR, IT, payroll, devices) traces back to the same `employee`
object. From `developer.rippling.com/documentation/base-api/
reference/get-employees-employee-id`:

```
Employee
  ├── personal info (name, contact, demographics)
  ├── employmentStatus  (ACTIVE | INACTIVE | TERMINATED)
  ├── department
  ├── manager           (by Rippling employee ID)
  ├── location
  ├── employmentType    (full-time | part-time | contractor)
  ├── flsaStatus
  ├── compensation      (object: amount, period, etc.)
  └── customFields
```

Rippling is **single-employment-per-person** in its base API
shape — `employmentStatus` is a single enum, not a multi-cardinality
collection. This reflects Rippling's positioning as US SMB-first;
the "Global EOR" product layers separate legal-entity assignments
on top via Rippling's `Global Workforce` product extension.

For kontor: **Rippling validates that single-employment is
acceptable for US-first SMB.** But Rippling has to build a
*second product* (Global Workforce) to handle multi-entity — the
note 79 multi-employment call avoids that bifurcation.

Rippling's contribution to the design conversation is the
**"Employee Graph" framing** — every business system points to the
same employee identity. kontor's `:person` plays this role; it's
the FK target from HR, IT (future companion), expense
(`kontor-expense`), authz (`kontor-authz` per ADR-127), procurement
(`kontor-procurement`). **The hybrid `:person` + `:partner/kind
:employee` model is the right call for this hub-and-spoke shape.**

### 6.3 — BambooHR

API docs: `documentation.bamboohr.com`. License: proprietary.

BambooHR is **HRIS-only**, no payroll engine. The Employee record
is the entire model: a flat record with 100+ standard fields
(employeeNumber, firstName, lastName, hireDate, jobTitle,
department, division, supervisor, payRate, payType, payPer, ...)
plus tenant-defined custom fields, up to 400 fields per API
request. No separate Job, Compensation, or Position entity. Re-
hire creates a new employee record.

For kontor: BambooHR is the **floor** of a working HR data model
(single flat record). Adopting it would be a strict regression vs.
the multi-employment / comp-as-entity shape. **Note 79's call 2
is well-positioned above this floor.**

### 6.4 — Deel

API docs: `developer.deel.com/api/platform/introduction`.
Proprietary.

Deel's distinguishing problem: **cross-border worker
classification**. In 150+ countries, a person is either a
Contractor (Deel handles 1099/W-9, person is independent), EOR
Employee (Deel is the legal employer via Deel's local entity), or
Direct Employee (customer has their own local entity, Deel
provides payroll). Data model:

```
Organization
  ├── LegalEntities (own local entities)
  ├── Workers → {Contractors, EOREmployees, DirectEmployees}
  ├── Contracts (per worker per engagement)
  ├── ComplianceScreenings (KYC, sanctions, misclassification)
  └── ImmigrationCases
```

**Worker → Contract distinction.** A single human can have a
Contractor relationship for project A and an EOR Employee
relationship for role B — two Contracts under one Worker.
Structurally **identical to note 79's call 2 (multi-employment per
person)** with worker classification varying per contract.

For kontor: Deel **reinforces note 79's call 2** and **suggests
`:person/kind`** (or `:employment/kind`) for the contractor-vs-
employee axis. Adding `:person/kind :contingent` is cheap.

**Cross-border employment**: Deel puts country on the
Contract/Legal Entity, not the Worker. Note 79 follows the same
pattern: `:employment/entity` → `:entity/country-code` per
ADR-031. No "country of employment" attr on `:person` needed.

### 6.5 — Justworks (PEO + EOR)

Proprietary. Justworks' US PEO is **co-employment** — the customer
keeps their own EIN as legal employer; Justworks Employment Group
LLC becomes co-employer for admin/payroll, providing a shared-
services EIN for FICA/FUTA/SUTA aggregation. One Employment, two
`Employer` references.

For kontor: **edge case territory for C1.** The substrate handles
via `:posting/entity` — the customer's legal entity is the
employer; PEO routing is a payment-provider concern (wage check
cut to "Justworks Employment Group" not to the employee). **No
schema change needed.**

### 6.6 — The SMB-tier net insight

The SMB tools surveyed cluster into three structural shapes:

| Shape | Examples | Multi-employment | Comp-as-entity |
|---|---|---|---|
| **HRIS-flat** | BambooHR | No | Scalar |
| **Job + Comp split** | Gusto, OnPay | Yes (Jobs) | Yes |
| **Worker / Contract split** | Deel, Rippling Global | Yes (Contracts) | Per contract |

Note 79's structural posture (multi-employment + scalar comp on
employment) sits between BambooHR and Gusto — slightly more
generous than BambooHR (because multi-employment), slightly less
than Gusto (because comp scalar). **The asymmetry isn't quite
right**: if we're going to do the harder work (multi-employment),
the easier work (comp-as-entity) is worth doing too.

---

## §7 — Open-source references

### 7.1 — OrangeHRM

Repository: `github.com/orangehrm/orangehrm`.
License: **GNU GPLv3** (confirmed at upstream `LICENSE` 2026-05-18).
**Reference-only**; GPL contagion blocks code lifting.

Entity model summary (from `src/plugins/orangehrmCorePlugin/Entity/`):

- `Employee` — root; `empNumber`, `joinedDate`, `terminationId`,
  `employmentStatus`, `jobTitle`, `subDivision`.
- `EmpContract` — `startDate`, `endDate`, doc.
- `EmpBasicsalary` — comp-as-scalar sidecar (one row per employee).
- `EmpDependent`, `EmpEmergencyContact`, `EmpEducation`,
  `EmpSkill`, `EmpLanguage`, `EmpLicense` — HRIS attribute tail.
- `JobTitle`, `EmploymentStatus`, `PayGrade` — catalog enums.
- `Leave`, `LeaveRequest`, `LeaveEntitlement`, `LeaveType` — time-off.

**Single-employment-per-employee** (multi-employment requires multi
Employee rows, BambooHR/Tryton/NetSuite pattern); **comp-as-scalar**
(`EmpBasicsalary` sidecar, not multi-cardinality); **identity fused
to Employee** (no separate Person record).

For kontor: structurally **weaker than note 79's schema**. The
take-away is negative — what NOT to do. Only positive lift: the
HRIS attribute catalog (`EmpEducation`, `EmpSkill`, etc.) is a
roadmap for the C4+ deferred entities.

### 7.2 — Frappe HR / ERPNext HR

Repository: `github.com/frappe/hrms`.
License: **GNU GPLv3** (verified at `license.txt` 2026-05-18).
Reference-only.

Key DocTypes:
- `Employee` — `employee_name`, `company`, `date_of_joining`,
  `status` (Active | Inactive | Suspended | Left), `date_of_birth`.
- `reports_to` — self-ref NestedSet (recursive org tree on
  Employee itself, like Tryton).
- **`Salary Structure` + `Salary Structure Assignment`** — separate
  DocTypes capturing comp; **comp-as-its-own-entity**.
- `Payroll Entry` — per-period payroll-run.
- `Leave Application`, `Leave Allocation`, `Leave Type` — time-off.

**Single-employment-per-employee** (one `company` per Employee).
**Comp-as-separate-entity** — `Salary Structure Assignment` binds
Employee → Salary Structure with effective `from_date`.

Frappe HR's `Salary Structure Assignment` is **the closest
"minimum viable" comp-as-entity model surveyed** — Salary Structure
carries the formula (base + housing + transport), Salary Structure
Assignment binds with effective date. The §9 refactor lifts
directly from this shape: kontor `:compensation` (per
`:employment`, effective-dated) + `:compensation-component`
rows (multi-cardinality, each carrying amount + kind).

### 7.3 — Tryton + Odoo (recap from note 72)

Already covered at depth in note 72. Brief recap of what this
note adds:

- **Tryton `company.employee`** (GPLv3, reference-only): one
  employee row per `(party, company)` — no multi-employment; comp
  is scalar; supervisor self-ref on the employee row. **Floor of
  reasonable simplicity.** kontor should not collapse to this
  shape.
- **Odoo `hr.employee` + `hr.version`** (LGPLv3, reference-only):
  `_inherits` delegation to `hr.version` versioned-fields row; per-
  country `hr.payroll.structure.type` registry — exactly the
  `PayrollProvider` shape kontor adopted in note 79 call 4.

The Odoo `hr.version` pattern is **what kontor gets for free**
from `:db.valid/from` — Odoo has to maintain the version sidecar
table manually; kontor doesn't.

---

## §8 — Cross-cutting design choices (the comparison table)

The single highest-value deliverable of this note. For each
design choice, what every gold-standard system picked, and what
note 79 picked.

| Design choice | Workday | SuccessFactors | NetSuite | Oracle Fusion | Gusto | Rippling | BambooHR | Deel | OrangeHRM | Frappe HR | OFBiz | **kontor (note 79)** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Worker vs Person primitive** | Worker (Employee + Contingent) | PerPerson | Employee | Person | Employee | Employee | Employee | Worker | Employee | Employee | Party + Person | **`:person`** (separate root) |
| **Multi-employment allowed** | Yes (multiple Positions/Jobs) | Yes (multiple EmpEmployment) | No (one per subsidiary) | Yes (multiple Work Rel.) | Yes (multiple Jobs) | No (1 employment; Global add-on) | No | Yes (multiple Contracts) | No | No | Yes (composite-PK Employment) | **Yes (1:N `:employment`)** |
| **Position separate from Employment** | Yes (Pos Mgmt model); optional (Job Mgmt) | Yes (Position object), optional | No | Yes (Position), optional | No | No | No | No | No (JobTitle catalog only) | No (Designation catalog) | Yes (EmplPosition) | **No (deferred to C5+)** |
| **Compensation as own entity** | Yes (Compensation, Pay Components) | Yes (EmpCompensation, EmpPayCompRecurring) | Partial (sidecar) | Yes (PAY_* tables) | **Yes (Compensation per Job)** | Object on Employee | Scalar | Per Contract | Scalar (EmpBasicsalary) | **Yes (Salary Structure)** | Yes (PayHistory) | **No (scalars on `:employment`)** ← §9 refactor |
| **Effective-dating on every attr** | Effective + Entry Date (convention) | startDate/endDate per entity | Mostly (audit log split) | _F/_M suffix tables | Per-resource | Last-write-wins | Last-write-wins | Per-record dating | Last-write-wins | from_date on assignments | (fromDate, thruDate) | **`:db.valid/from` natively** |
| **Cross-border employment** | OrgAssignment.Company | EmpEmployment per legal entity + PerGlobalInfo<CC> | Subsidiary on Employee | Work Relationship per Legal Employer | Per company (legal entity) | Global Workforce add-on | Country field; no FX | Contracts to local Legal Entity | Single-country | company on Employee | partyIdFrom = employer | **`:employment/entity` (ADR-031)** |
| **Contractor vs employee** | Contingent Worker subtype | EmpEmployment.employeeClass enum | employeeStatus + isJobResource | personType on assignment | employmentType enum | employmentType enum | employmentStatus enum | Worker.Classification | EmploymentStatus enum | employment_type enum | RoleType (EMPLOYEE / CONTRACTOR) | **`:partner/kind` (suggests `:person/kind` refinement)** |
| **Benefits enrollment** | BenefitPlan + BenefitEnrollment | BenefitProgram + BenefitEnrollment | benefitsAccrual on Employee | BEN_* tables | benefits per employee | benefits per employee | benefits (basic) | Benefits per contract | Benefit entity | Employee Benefit | PartyBenefit | **`:benefit-enrollment` (deferred to C4)** |
| **Time-off / leave** | Time Off Plan + Absence Plan | EmpTimeAccount + EmpTimeOffCalendar | timeOffPolicy + accrual | ABS_*, PAY_* tables | time_off_policies + time_off_balances | Leave per Employee | timeOff per employee | Time off per contract | Leave + LeaveEntitlement | Leave Type + Leave Allocation | EmplLeave + EmplLeaveType | **`:absence` + `:absence-allocation` (deferred to C4)** |
| **Audit / PII classification** | Field-level security; data classification framework | Field-level RBAC; encrypted PII | Role-based field hiding | Role-based field hiding + Risk Mgmt Cloud | OAuth scope (compensations:read) | Role-based field hiding | Field permissions per role | Per-record classification | Role-based | Role + Permission per DocField | encrypt="true" column flag | **`:audit-doc/privilege` + new `:audit-doc/category`** ← note 79 call 5 |
| **Manager / reporting tree** | Supervisory Organization tree | Manager ref on EmpJob; org via Position | supervisor on Employee | Manager on Assignment; Position hierarchy | manager_id on Job | manager (by Employee ID) | supervisor field | Within Group/Org | supervisor + subDivision tree | reports_to (NestedSet) | EmplPositionReportingStruct | **`:employment/manager` (ref → `:employment`) + `:department` tree** |
| **Org unit (department) tree** | Supervisory Org + Cost Center + Business Unit | Foundation Object: Org | Department + Class + Location | Department + Org Tree | departments | Department | Department + Division | Group / Organization | SubDivision tree | Department NestedSet | (uses Party/PartyGroup) | **`:department/parent` recursive** |
| **Identity hub linkage to non-HR systems** | Workday hub | SAP hub | NetSuite Entity (FK target) | Oracle hub | Per-app | **Employee Graph** | Limited | API integrations | Limited | DocType refs | Party (universal) | **`:person` + `:partner/kind :employee` linker** (note 79 call 3) |

**Patterns visible from the table.**

1. **Multi-employment**: every enterprise system supports it
   (Workday, SuccessFactors, Oracle, Deel, Gusto, OFBiz);
   only SMB-flat tools (NetSuite, BambooHR, Rippling base,
   OrangeHRM, Frappe, Tryton) collapse it. **Note 79's call 2 is
   right.**
2. **Compensation as separate entity**: this is the *majority*
   pattern (Workday, SuccessFactors, Oracle, Gusto, Frappe HR,
   OFBiz). Only the flat-shaped SMB tools (BambooHR, OrangeHRM,
   Tryton) keep it scalar. **Note 79 went against the majority on
   this one without good reason — §9 refactors it.**
3. **Position as separate entity**: this is *optional* — every
   enterprise tool offers both modes (Workday Job vs Position
   Mgmt; Oracle two-tier vs three-tier). **Note 79's deferral
   is defensible.**
4. **Effective-dating**: every system reinvents this; kontor wins
   on substrate. **Note 79 documented this; no change.**
5. **Identity hub**: Rippling's "Employee Graph" framing is the
   most modern; OFBiz Party + Person is the deepest; Deel's
   Worker-Contract is the cross-border-native. **Note 79's hybrid
   `:person` + `:partner/kind :employee` covers all three
   intuitions.**

---

## §9 — Recommendations for note 79's schema

For each design call in note 79 §2, the verdict.

### 9.1 — Call 1: Companion-tier `:person` placement

**Confirmed.** Every system surveyed has its HR data in a separate
module / namespace (SuccessFactors EC vs core; Workday HCM vs other
clouds; Oracle Fusion HCM vs Financials; NetSuite SuitePeople vs
core; Gusto's payroll vs benefits; Frappe HR vs ERPNext). **Note
79's companion-tier call is structurally correct.** No change.

### 9.2 — Call 2: Workday-style multi-employment

**Confirmed.** §8 table confirms the entire enterprise tier picks
multi-employment; SMB-flat tools collapse it but Deel and Gusto
(both SMB-targeted) carry it. The trans-national pitch (note 79
§2.2) needs it. **Ship as-is.**

**Minor refinement:** add a `:person/kind` keyword (open-set:
`:employee | :contingent | :applicant | :retiree | :board-member`)
to support contractor / contingent worker classification at the
person level, matching Workday's Worker subtype + Deel's Worker
classification. Cheap (1 attr) and avoids a forced retrofit when
the first contingent-worker payroll lands in C5+. **Add this to
the C1 schema sketch in §3 of note 79.**

### 9.3 — Call 3: Hybrid `:person` + `:partner/kind :employee`

**Confirmed.** OFBiz Party + Person, Workday Worker, Oracle Person,
SuccessFactors PerPerson, Rippling's Employee Graph — every
identity-hub pattern in the gold-standard set separates "the
human" from "the business-relationship party." Note 79's hybrid is
the canonical shape. **Ship as-is.**

### 9.4 — Call 4: Three-protocol-plus-emit `PayrollProvider`

**Confirmed.** None of the gold-standard systems separates these
concerns the way kontor does (most are end-to-end SaaS); but the
mirror-ADR-071 case stands on its own. The relevant validation
from the gold-standard study is that **compensation modeling
(comp-as-entity) is upstream of the provider** — the provider
consumes `:compensation` rows (not scalars on `:employment`) to
compute payroll facts. **Confirms the §9.5 refactor below.**

### 9.5 — Call 5: `:audit-doc/category` orthogonal to privilege

**Confirmed.** Every system surveyed uses field-level RBAC + a
data-classification flag. Workday's data-classification framework,
SuccessFactors' field-level RBAC + sensitivity tags, Oracle Risk
Management Cloud's classification dimensions — all are
**two-axis**: legal-doctrine privilege + subject-matter category.
The two-axis design is industry-standard for regulated PII
handling. **Ship as-is.**

### 9.6 — The new reconsidered call: compensation as separate entity

This is the only **Reconsidered** item from the gold-standard
study. Note 79's schema sketch puts `:employment/wage` +
`:employment/wage-commodity` + `:employment/wage-period` directly
on `:employment`.

**Evidence against this:**
- Workday: `Compensation` + `Pay Components` are separate, multi-
  cardinality, effective-dated.
- SuccessFactors: `EmpCompensation` + `EmpPayCompRecurring` +
  `EmpPayCompNonRecurring`, three levels, multi-cardinality.
- Oracle Fusion: `PAY_*` tables, separate from `PER_ALL_ASSIGNMENTS_F`.
- Gusto: `Compensation` per `Job`, multi over time.
- Frappe HR: `Salary Structure Assignment` + `Salary Structure`.
- OFBiz: `PayHistory` separate from `Employment`.

**Evidence for note 79's choice:**
- Bitemporal `:db.valid/from` does give "wage as of date" for free.
- Single attr is simpler.
- BambooHR / OrangeHRM / Tryton are scalar.

**Decisive argument**: a single scalar can't represent N
*simultaneously active* compensation components (base wage +
Christmas bonus + employer pension + vermögenswirksame Leistungen
+ housing allowance + RSU vest schedule). DE C2 (the maintainer's
own consumer) needs at minimum:
- Base wage (SKR04 4120 Löhne)
- Employer SI accrual (SKR04 4130 Soziale Aufwendungen)
- Optional 13th-month / Weihnachtsgeld (SKR04 4128)
- Optional VWL (SKR04 4140)

These are **separate posting lines** with separate accounts,
separate employer-SI treatment, separate accrual cadence (annual
bonus vs monthly wage). Folding them into `:employment/wage`
scalar forces the consumer to either (a) override with multiple
`:employment` rows (wrong — these are all attributes of one
employment) or (b) push the structure into `:variable-inputs` on
the `PayrollComputeProvider` (wrong — variable inputs are
*per-pay-period overrides*, not standing comp structure).

**Refactor recommendation:**

```clojure
;; --- COMPANION (kontor-hr) ---
;; :compensation — comp envelope for an employment (Gusto/Frappe shape)
[H] :compensation/employment           ref    one        ; → :employment
[H] :compensation/effective-from       inst   one        ; valid-time on top of :db.valid/from
[H] :compensation/effective-to         inst   one        ; nil=open; OFBiz/SF pattern
[H] :compensation/commodity            ref    one
[H] :compensation/state                kw     one        ; ADR-034 facet: :proposed → :active → :superseded

;; :compensation-component — individual pay component (multi per :compensation)
[H] :compensation-component/compensation ref  one        ; → :compensation
[H] :compensation-component/kind        kw    one        ; :base-wage | :allowance |
                                                        ; :bonus-target | :pension | :imputed-income | …
[H] :compensation-component/amount      bigdec one
[H] :compensation-component/period      kw    one        ; :hourly|:monthly|:annual|:one-time
[H] :compensation-component/account-hint kw   one        ; consumer-side CoA mapping hint
                                                        ; (e.g., :base-wage → SKR04 4120)
```

`:employment/wage`, `:employment/wage-commodity`, `:employment/
wage-period` move OUT of `:employment`. The "current base wage"
becomes a derived query: latest active `:compensation` for an
`:employment` with a `:compensation-component/kind :base-wage`.

A `kontor.hr/employment-current-wage` helper hides the derived
query exactly the way Odoo's `_inherits` does — query ergonomics
preserved.

**Cost.** ~3 hours of schema work; ~1 hour for the helper
function; ~1 hour to update the C1 hire test. **Total: 1
maintainer-half-day extra on C1.**

**Benefit.** Avoids ~3 days of migration debt when DE C2 lands
(maintainer's own consumer; the worst-case downstream blast
radius). Aligns kontor with the gold-standard pattern (Workday /
SuccessFactors / Oracle / Gusto / Frappe HR all do this). Lets the
DE Mahnverfahren showcase (note 79 §8 C2 D6) carry Weihnachtsgeld
correctly without contorting the schema.

**Risk if NOT done:** medium. The maintainer will hit this in C2
when DE needs the Christmas bonus + employer pension distinct from
base wage in posting builders. At that point, the refactor is the
same shape but harder to do (existing data + maintenance debt).

### 9.7 — Other minor refinements (cheap, optional)

- **Add `:person/kind`** (per §9.2) — 1-attr, open-set.
- **Add `:employment/work-time-fraction`** (FTE; 0.0 to 1.0 BigDecimal):
  every gold-standard system has FTE as a numeric attr on the
  employment/assignment, not just `:fulltime-flag`. Note 79's
  `:fulltime-flag` is too coarse — half-FTE part-time vs 80%
  reduced-hours secondment matter for payroll math. **2-minute
  change.** Add to the C1 schema.
- **Add `:employment/work-relationship-kind`** (open-set:
  `:standard | :secondment | :board-position | :apprentice |
  :intern | :working-student`): note 73 Theme C P1 (DE secondment
  scenario) needs this. The current `:exempt-flag` only covers
  US FLSA; DE has Beamter, working-student-status (§ 6 SGB IV),
  apprentice (§ 1 BBiG) — none of which fit FLSA-exempt yes/no.
  **5-minute change.**

These three (`:person/kind`, `:work-time-fraction`,
`:work-relationship-kind`) are all single-attr additions to the
existing note 79 schema. **Total marginal schema work: < 1 hour.**

### 9.8 — What note 79 was right about

For completeness, the things note 79 nailed that aren't refactor
candidates:

- Companion-tier placement (validated by every system).
- Multi-employment per person (Workday-shape).
- Hybrid identity with `:person` root + `:partner/kind :employee`
  linker.
- Three-protocol PayrollProvider mirroring ADR-071.
- `:audit-doc/category` orthogonal axis.
- Bitemporal `:db.valid/from` for valid-time effective-dating.
- Deferring `:position`, `:job-family`, `:job-profile` to C5+.
- `:department` recursive tree.
- `:pay-period` separate from `:period`.
- The status-machine facets (`:employment/state` lifecycle,
  `:pay-period/state` lifecycle, `:payroll-run/state` lifecycle).
- `:audit-doc/expiry-date` + `kontor.schedule` for visa sweeps.

---

## §10 — What note 79 might have missed

Honest pass — what *structural choices* did notes 72/74/79 not
consider that the gold-standard study surfaces?

1. **`:compensation` as separate entity** — §9.6 above. **The
   structurally significant gap.**
2. **`:person/kind` for Worker subtyping** — Workday Worker
   (Employee + Contingent), Oracle (employee / contingent /
   applicant), Deel (employee / contractor / EOR). **The minor
   refinement worth adopting.**
3. **`:employment/work-time-fraction`** — FTE as continuous, not
   just full/part flag. Universal in gold-standard systems.
4. **`:employment/work-relationship-kind`** — extends past the
   FLSA-exempt binary into the SuccessFactors `employeeClass` /
   Oracle `assignment_category` / Workday `employee_type_id`.
5. **Multiple work locations per employment** (Gusto `work_addresses`
   multi). Note 73 Theme B P1 explicitly cited multi-state remote
   workers — but note 79 doesn't model location on `:employment`
   at all. This is a *deferred* gap, not a refactor (the consumer
   can carry location via `:posting/entity` allocation + `:analytic-
   account`), but worth flagging. **Defer to C3 (US-ADP) when
   multi-state allocation becomes load-bearing.**
6. **`:compensation-component/kind` + the PayrollFacts protocol
   shape.** Once §9.6 lands, the `PayrollFacts` data shape (note
   79 §4) should reference `:compensation-component/kind` for the
   per-component breakdown — so the `:base-wage`, `:bonus`,
   `:employer-si`, etc. enum from note 79 §2.4 gets
   structural reuse rather than living only inside `PayrollFacts`.
7. **Position as the primary anchor (SuccessFactors / Workday
   position-mgmt mode).** Note 79 defers this; the gold-standard
   tier-1 systems all support both modes. **Confirmed deferral, no
   action.**
8. **PEO co-employment shape** (Justworks): unique two-employer-
   one-employment shape; **out of C1 scope per §6.5.** Substrate
   handles via `:posting/entity` indirectly. No refactor.
9. **Cross-border "country of employment" attr distinct from
   `:person/country`**: Deel models this on the Contract/Legal
   Entity, not the Worker — kontor follows the same shape via
   `:employment/entity` → `:entity/country-code`. **No refactor
   needed; note this is the right structural choice but it wasn't
   explicitly justified in note 79.**
10. **Identity hub semantics**: `:person` is the FK target from
    HR + IT + expense + procurement + authz + ... (Rippling's
    Employee Graph framing). Note 79 §2.1 mentions this in
    passing; the gold-standard study **strongly reinforces** that
    `:person` should be the hub. No schema change; but the
    `kontor-hr/install!` should publish the schema for use by
    `kontor-expense`, `kontor-procurement`, future `kontor-it`,
    etc. **Documentation refinement, not code.**

---

## §11 — Net recommendation

### Ship C1 as-is per note 79: **YES, with the §9.6 refactor + three minor adds**

The §9.6 refactor (compensation-as-separate-entity) is the only
material change. The three minor additions (`:person/kind`,
`:employment/work-time-fraction`, `:employment/work-relationship-
kind`) are 1-attr cheap. **Total marginal C1 cost: ~half a day.**

### Risk of NOT incorporating §9.6 refactor: **medium**

The maintainer will need it for the DE C2 implementation (the next
checkpoint after C1). Weihnachtsgeld, employer pension, VWL — none
of these are single-scalar-wage. Refactoring after DE-DATEV-LODAS
ships is harder than refactoring at the schema-only-no-data C1
stage. **Refactoring now is the cheap option; refactoring later
costs ~3 days of migration + downstream consumer churn.**

### Risk of NOT incorporating §9.7 (`:person/kind`, etc.): **low**

These are additive enums; can land in C2 or C3 without blast
radius. But they're 5-minute additions, so the cost-of-adding-now
is essentially zero. **Add anyway.**

### Estimated additional research needed before C1: **0 hours**

The gold-standard study is complete. The maintainer can ship C1
with the §9 refactor or commission a deeper study (e.g., on the
PayrollFacts data shape internals), but it's not gating.

### Estimated additional schema work before C1: **~4 hours**

- §9.6 — `:compensation` + `:compensation-component` schema +
  helper function + updated C1 hire test: ~3 hours.
- §9.7 — three single-attr additions + schema docs: ~30 minutes.
- Updated ADR-Stage-R-1 to reflect the refactor: ~30 minutes.

### Estimated additional schema work after C1 (if refactor deferred): **~3 days**

- C2 hits the gap during SKR04 mapping for Weihnachtsgeld.
- Refactor `:employment/wage*` → `:compensation/*` with existing
  C1 test data.
- Update DATEV LODAS posting builder to consume per-component
  records.
- Schema migration ADR + addendum to ADR-Stage-R-1.

### Final disposition for the §9 design calls

| Call | Verdict | Action before C1 |
|---|---|---|
| #1 Companion-tier | Confirmed | None |
| #2 Multi-employment | Confirmed | None |
| #3 Hybrid `:person` + `:partner/person` | Confirmed | None |
| #4 Three-protocol PayrollProvider | Confirmed | None |
| #5 `:audit-doc/category` orthogonal | Confirmed | None |
| **NEW** #6 Compensation as own entity | **Reconsidered** | **Add `:compensation` + `:compensation-component` to C1 schema (§9.6)** |
| **NEW** #7 `:person/kind` enum | Refined | Add to C1 (§9.7) |
| **NEW** #8 `:employment/work-time-fraction` | Refined | Add to C1 (§9.7) |
| **NEW** #9 `:employment/work-relationship-kind` | Refined | Add to C1 (§9.7) |

After the refactor + additions, C1 ships **with the canonical
gold-standard data shape**: Person → Employment → Compensation →
Compensation Components. **kontor's posture is then "OFBiz +
Workday + SuccessFactors-shaped, with kontor's bitemporal substrate
underneath."**

---

## §12 — Sources

All URLs accessed 2026-05-18.

**Workday** (proprietary; public docs partial):
`community.workday.com/sites/default/files/file-hosting/productionapi/`
(Get_Worker, Get_Position, Contract_Contingent_Worker — paywalled
tenant docs; structural details cross-verified via
`one.wa.gov/sites/default/files/2021-06/Workday%20Glossary.pdf` and
`workday.com/content/dam/web/en-us/documents/datasheets/organization-management-in-workday-datasheet-en-us.pdf`).
Staffing model + Job hierarchy:
`kinnectx.com/blog-posts/choosing-workday-position-management-vs-job-management`,
`workdaynavigator.com/blog/workday-position-vs-job-management-when-to-use-which`,
`wp.nyu.edu/peoplesync/peoplesync-fundamentals-job-family-group-job-family-and-job-profile`.
Contingent worker: `workday.com/content/dam/web/en-us/documents/datasheets/workday-vndly-worker-profile-management-datasheet-vms.pdf`.

**SAP SuccessFactors Employee Central** (proprietary; SAP Help Portal):
`help.sap.com/docs/successfactors-employee-central/implementing-employee-central-core/data-models`,
`help.sap.com/docs/SAP_SUCCESSFACTORS_PLATFORM/d599f15995d348a1b45ba5603e2aba9b/003f8d2d95e0437abad34169e4bcc1f4.html`
(EmpPayCompRecurring), SAP Note 2619357 at
`userapps.support.sap.com/sap/support/knowledge/en/2619357`,
`erpqna.com/employee-central-entities-interfacing/`.

**Oracle Fusion HCM** (proprietary; public schema reference):
`docs.oracle.com/en/cloud/saas/human-resources/22d/oedmh/perallpeoplef-18166.html`,
`docs.oracle.com/en/cloud/saas/human-resources/oedmh/perallassignmentsm-30304.html`.
Employment model overviews:
`apps2fusion.com/employment-model-in-oracle-fusion-hcm`,
`unogeeks.com/oracle-fusion-hcm-employment-model`,
`medium.com/@futureprooftrainings/hcm-employment-model-in-oracle-fusion-hcm-cloud-0ec24a4e638a`,
`ateam-oracle.com/loading-workers-and-users-into-fusion-hcm-cloud`.

**NetSuite SuitePeople** (proprietary):
`netsuite.com/help/helpcenter/en_US/srbrowser/Browser2016_1/schema/record/employee.html`,
`docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/chapter_1495573671.html`,
`docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/preface_1538679887.html`.

**Gusto** (proprietary; public API):
`docs.gusto.com/embedded-payroll/docs/create-a-job-and-compensation`,
`docs.gusto.com/embedded-payroll/reference/get-v1-employees`,
`docs.gusto.com/embedded-payroll/reference/get-v1-jobs-job_id`,
`embedded.gusto.com/blog/core-concepts-payroll-apis/`.

**Rippling** (proprietary; public API):
`developer.rippling.com/documentation/base-api/reference/get-employees-employee-id`,
`marvinvista.substack.com/p/rippling-and-the-employee-graph-yc`,
`rippling.com/blog/introducing-rippling-platform`.

**BambooHR** (proprietary; public API):
`documentation.bamboohr.com/reference`,
`documentation.bamboohr.com/reference/get-employee`.

**Deel** (proprietary; public API):
`developer.deel.com/api/platform/introduction`,
`deel.com/hr-platform/api/`, `deel.com/solutions/payroll/eor/`,
`deel.com/blog/contractor-management-vs-eor-for-long-term-international-hiring/`.

**Justworks** (proprietary; public help docs):
`help.justworks.com/hc/en-us/articles/360004430612-How-Co-Employment-Works`,
`help.justworks.com/hc/en-us/articles/24026656179099-Justworks-Employer-of-Record-EOR`.

**OrangeHRM** (GPLv3 — reference-only):
`github.com/orangehrm/orangehrm`; license verified at
`github.com/orangehrm/orangehrm/blob/master/LICENSE`. Historical
schema reference (community fork):
`github.com/mkondel/orangehrm/blob/master/symfony/data/sql/schema.sql`.

**Frappe HR / ERPNext HR** (GPLv3 — reference-only):
`github.com/frappe/hrms`; license verified at
`github.com/frappe/hrms/blob/develop/license.txt`.
Docs: `docs.frappe.io/hr/employee`,
`clefincode.com/blog/global-digital-vibes/en/erpnext-v15-hr-module-detailed-overview-and-deep-dive`,
`github.com/frappe/erpnext/blob/develop/erpnext/setup/doctype/employee/employee.py`.

**Tryton + Odoo + OFBiz** — already cited at file:line depth in
[[72-hr-payroll-reference-study]] §1-§3.

**PII classification methodology**:
`legalclarity.org/pii-classification-levels-frameworks-and-best-practices/`,
`dol.gov/general/ppii`,
`concentric.ai/comparing-spii-vs-phi-and-pii-a-sensitive-information-guide/`,
`shrm.org/topics-tools/tools/policies/personal-identity-information-pii-security-notification-confidentiality-policy`.

**kontor anchor citations**:
`/home/christian-weilbach/Development/kontor/doc/research/72-hr-payroll-reference-study.md`,
`/home/christian-weilbach/Development/kontor/doc/research/73-hr-payroll-market-pain.md`,
`/home/christian-weilbach/Development/kontor/doc/research/74-hr-payroll-internal-gap-analysis.md`,
`/home/christian-weilbach/Development/kontor/doc/research/79-hr-payroll-stage-r-plan.md`.
`src/kontor/schema.clj:525-598` (`:partner/*`),
`src/kontor/schema.clj:3480-3539` (`:audit-doc/privilege`).

**License posture summary.** OFBiz Apache-2.0 lift-safe; OrangeHRM
+ Frappe HR + Tryton GPLv3 reference-only; Odoo LGPLv3 reference-
only; all proprietary systems' patterns are publicly documented
and clean-room implementable in kontor. The §9.6 refactor draws
shape from public data-model conventions across all surveyed
enterprise systems — no proprietary code lifted.

---

End of note 81.

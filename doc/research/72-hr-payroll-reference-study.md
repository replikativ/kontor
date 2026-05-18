---
date: 2026-05-17
title: 72 — HR / payroll reference-implementation study (OFBiz primary, Tryton + Odoo secondary)
status: draft
audience: maintainer + future-self drafting the Stage R ADRs
---

# 72 — HR / payroll reference-implementation study

Stage R research-before, file 1 of 3. Companion to
[[73-hr-payroll-market-pain]] (pain-point catalog) and
[[74-hr-payroll-internal-gap-analysis]] (kontor-substrate audit + design
calls). Reads OFBiz `humanres` end-to-end as the primary reference;
adds 200-400 word secondary surveys of Tryton's `company` /
`attendance` modules and Odoo's `hr` / `hr_version` pattern. Research
note [09-hr-personnel-payroll.md](09-hr-personnel-payroll.md) is the
predecessor — this note grounds it at file:line depth.

License posture (ADR-001):
- **OFBiz humanres** — Apache-2.0. Patterns AND structure are
  liftable; code translation is fine.
- **Tryton company / attendance** — GPLv3. Read for design pattern;
  do not lift code.
- **Odoo hr / hr_version** — LGPLv3. Read for design pattern; do not
  lift code.

## TL;DR

- **OFBiz reuses `Party` + `Person` + `PartyRole` for the personnel
  record.** No standalone `Employee` table. The "employee" is a
  `Person`-typed `Party` that holds an `EMPLOYEE` role. `Employment`
  is a *relationship* row between two parties (employer party,
  employee party), effective-dated via `(fromDate, thruDate)`. This
  is the Universal Data Model "Party + PartyRole + PartyRelationship"
  pattern, applied verbatim. **Strongest single take-away for kontor**:
  if we keep `:partner/*` as the universal party root (ADR-039),
  `:partner/kind :employee` plus a `:employment` relationship row falls
  out for free — no new root entity, no integration friction with
  `:posting/partner`.
- **The four-PK composite on `Employment`** (`roleTypeIdFrom`,
  `roleTypeIdTo`, `partyIdFrom`, `partyIdTo`, `fromDate`) at
  `humanres-entitymodel.xml:341-345` is OFBiz's effective-dating
  pattern. kontor's bitemporal `:db.valid/from` + `:db.valid/to` does
  the same job with one attribute pair and a query. This is the most
  obvious place where datahike bitemporality is structurally cleaner
  than a relational FK shape.
- **OFBiz separates Person↔Position↔Fulfillment.** `EmplPosition` is
  the *seat* (job opening), `EmplPositionFulfillment` is the
  many-to-many that connects a position to one or more employees over
  time, `EmplPositionReportingStruct` is the position→position
  reporting tree. This is the SAP / Workday shape. Note 09 §1
  recommended skipping positions for v1; the OFBiz layout makes adding
  them later cheap (separate entity, additive ref).
- **OFBiz has NO payroll engine.** It ships data: `PayHistory`,
  `PayrollPreference`, `Deduction`, `DeductionType` (the last two live
  in `accounting-entitymodel.xml:2801-2827`, NOT in humanres). Gross-
  to-net computation is delegated to external systems via wage-type-
  to-GL-account mappings. **This confirms ADR-005's protocol-only
  posture is the right shape.** Per-jurisdiction tax math doesn't
  belong in the substrate.
- **Tryton's `company.employee` is one-company-per-employee** with a
  supervisor self-ref, plus an `attendance.line` event log with
  `attendance.period` close-period semantics. Much simpler than
  OFBiz; loses multi-employment by design. Useful as a *minimum
  viable* reference but not as the architectural target.
- **Odoo's `hr.employee` `_inherits` `hr.version`** — every change to
  an employee's master data (job, department, wage, salary structure)
  writes a new `hr.version` row with `date_version`. This is the
  effective-dating-as-versioning pattern that kontor's bitemporal
  store does *natively*: a kontor `:employment` entity edited at
  `:db.valid/from` automatically gives us the "what was the wage
  on March 31?" query without a `hr.version`-style sidecar.

## 1. OFBiz `humanres` — the primary reference

Path: `/home/christian-weilbach/Development/ofbiz-framework/applications/humanres/`
Entity model: `/home/christian-weilbach/Development/ofbiz-framework/applications/datamodel/entitydef/humanres-entitymodel.xml`
(972 lines, 41 entities + view-entities)
Seed data: `/home/christian-weilbach/Development/ofbiz-framework/applications/datamodel/data/seed/HumanResSeedData.xml`
(166 lines)
Services (CRUD-shaped): `applications/humanres/servicedef/services{,_employment,_position,_ability}.xml`
(54 services across 4 files, mostly entity-auto)
DeductionType / Deduction (payroll-adjacent): live OUTSIDE humanres
in `applications/datamodel/entitydef/accounting-entitymodel.xml:2801-2827`.

### 1.1 Entity table — 41 entities by package

OFBiz organizes humanres into five packages declared by `package-name`:
`org.apache.ofbiz.humanres.{ability, employment, position, recruitment,
trainings}`. Below is the catalog grouped by package; line numbers
are from `humanres-entitymodel.xml`. View-entities (compiled joins)
marked with †; tree-shaped catalog types (parent_id self-ref) marked
with ▲.

**`ability` package (12 entities + 1 view-entity):**
`PartyQual` (43-69, qualification with FK to `PartyQualType`▲ at 71-82),
`PartyResume` (83-98, resume with FK to `Content`), `PartySkill`
(99-116, FK to `SkillType`▲ at 274-285), `PerfReview` (129-165, with
`PerfReviewItem` 166-198 and `PerfReviewItemType`▲ 199-210, scored by
`PerfRatingType`▲ 117-128), `PerformanceNote` (211-230, free-text on
(party, role, date)), `PersonTraining` (231-261, FK to
`TrainingClassType` 286-298), `ResponsibilityType`▲ (262-273).

**`employment` package (15 entities + 2 view-entities†):**
`BenefitType`▲ (303-316, with `employerPaidPercentage`),
`BenefitTypeAndParty`† (317-329), `Employment` (330-370 — **the
employer↔employee relationship row**, composite PK over four
party/role keys + `fromDate`, with `EMPLMNT_AGR → Agreement` at
366-369), `EmploymentAndPerson`† (371-381), `EmploymentApp` (383-417,
job application; FK to `EmplPosition`, `JobRequisition`),
`EmploymentAppSourceType`▲ (418-429), `EmplLeave` (430-459, FK to
`EmplLeaveType`▲ 460-472 + `EmplLeaveReasonType`▲ 959-971 + approver
+ status), `PartyBenefit` (473-513, benefit enrollment with `cost`
and actual employer-paid %), `PayGrade` (514-521, broad pay band),
`PayHistory` (522-561 — **wage history** with composite PK
`(roleTypeIdFrom, ..., emplFromDate, fromDate)`), `PayrollPreference`
(562-597, per-employee deduction/payment routing), `SalaryStep`
(598-615, step within a pay grade), `TerminationReason` (616-622),
`TerminationType`▲ (623-634), `UnemploymentClaim` (635-659, US-only).

**`position` package (9 entities + 2 view-entities†):**
`EmplPosition` (665-696 — **the seat**; status, type, budget link,
salary/exempt/fulltime/temporary flags), `EmplPositionClassType`▲
(697-708, FLSA classification), `EmplPositionFulfillment` (709-726,
m:n linking position to fulfiller), `EmplPositionAndFulfillment`†
(727-742), `EmplPositionReportingStruct` (743-761, position→position
reporting tree), `EmplPositionResponsibility` (762-779),
`EmplPositionType`▲ (780-791, seeded `PROGRAMMER`, `CEO` etc. in
`HumanResSeedData.xml:64-72`), `EmplPositionTypeClass` (792-809,
with `standardHoursPerWeek`), `ValidResponsibility` (810-827,
type/responsibility join), `EmplPositionTypeRate` (828-851,
wage rate per position-type / pay-grade / step / from-date),
`EmplPositionTypeRateAndAmount`† (852-867).

**`recruitment` package (3 entities + 1 view-entity†):**
`JobRequisition` (872-899, open posting; location, exam type),
`JobInterview` (900-927), `JobInterviewType` (928-934),
`EmplPositionFulfillmentAndReportingStruct`† (935-952, org-chart
query).

**`trainings` package (1 entity):** `TrainingRequest` (953-958, stub).

**Cross-package dependencies (not counted above):** `DeductionType`▲
(`accounting-entitymodel.xml:2816-2827`), `Deduction`
(`accounting-entitymodel.xml:2801-2815`, attached to a `Payment` —
NOT a payroll-period concept), `Party` (`party-entitymodel.xml:1594`,
the universal root), `Person` (`party-entitymodel.xml:2800-2860`,
human-attributes extension; encrypts `socialSecurityNumber`,
`passportNumber`, `mothersMaidenName` via `encrypt="true"`),
`PartyRole` (`party-entitymodel.xml:2561`, M:N with `RoleType`),
`Agreement` (`party-entitymodel.xml:64`, contract doc linked to
`Employment` via `EMPLMNT_AGR`).

### 1.2 The ten load-bearing entities, in detail

#### `Person` (`party-entitymodel.xml:2800-2860`)

Fields: `partyId` (FK to `Party`), `firstName`, `lastName`, `gender`,
`birthDate`, `socialSecurityNumber` (`encrypt="true"`), `passportNumber`
(`encrypt="true"`), `passportExpireDate`, `maritalStatusTypeId`,
`mothersMaidenName` (`encrypt="true"`), `employmentStatusEnumId`,
`residenceStatusEnumId`.

The **human-level** attributes. Notable: three PII fields use OFBiz
delegator-layer `encrypt="true"` column-encryption. kontor analog:
field-level retention + `:audit-doc/privilege :pii-sensitive`
(ADR-051, schema.clj:3525-3539). **Role:** the global human identity.
One per actual human; transferable across legal entities by adding a
new `Employment` row, not by re-creating the `Person`.

#### `Party` + `PartyRole` + `Employment` triple

`Party` (`party-entitymodel.xml:1594`) is the abstract root — every
person AND every organization AND every group. `PartyRole`
(`party-entitymodel.xml:2561`) attaches one or more roles to a party:
a single `Party` of type `Person` typically holds roles like
`EMPLOYEE`, `INTERNAL_ORGANIZATIO_MEMBER`, `END_USER_CUSTOMER`. A
single `Party` of type `PartyGroup` (the "Acme GmbH" record) holds
roles like `INTERNAL_ORGANIZATIO`, `EMPLOYER`.

`Employment` (`humanres-entitymodel.xml:330-370`) is the relationship
row. Composite PK `(roleTypeIdFrom, roleTypeIdTo, partyIdFrom,
partyIdTo, fromDate)` (lines 341-345). Fields: `thruDate`,
`terminationReasonId`, `terminationTypeId`, plus the `EMPLMNT_AGR →
Agreement` relation at 366-369.

**Why a 5-attribute composite PK?** Effective-dating: the same
`(employer, employee)` pair can re-employ each other (prior row's
`thruDate` set + new row's fresh `fromDate`). The role pair lets a
single party be in two relationships simultaneously (e.g., a
contractor who's also a shareholder). **Role for kontor:** the
`:employment` entity. Bitemporal `:db.valid/from` collapses `fromDate
+ thruDate`. A re-hire is a second `:employment` entity, not a
row-update.

The `EMPLMNT_AGR` relation points `Employment` to an `Agreement`
(the signed contract). kontor analog: ADR-038 `:audit-doc/*` —
employment contract is `:audit-doc/type :contract` with
`:audit-doc/content-hash` proving the file hasn't changed.

#### `EmplPosition` (`humanres-entitymodel.xml:665-696`)

The **seat** separate from the person. Fields: `statusId`
(`EMPL_POS_PLANNEDFOR | ACTIVE | INACTIVE`; seeds at
`HumanResSeedData.xml:24-26`), `partyId` (employer org), `budgetId
+ budgetItemSeqId` (headcount planning), `emplPositionTypeId`
(`PROGRAMMER`, `CEO`...), `estimated{From,Thru}Date`, `salaryFlag`,
`exemptFlag` (FLSA), `fulltimeFlag`, `temporaryFlag`, `actual{From,Thru}Date`.
**Role:** decouples "the company budgets for a CFO" from "Jane Smith
is currently the CFO." A position can be vacant (status `ACTIVE`, no
fulfillment) or filled by N people via `EmplPositionFulfillment`.

#### `EmplPositionFulfillment` (`humanres-entitymodel.xml:709-726`)

The m:n linking `EmplPosition` to the filler, effective-dated.
Composite PK `(emplPositionId, partyId, fromDate)`. One position can
have multiple fulfillers concurrently (job-share) or serially
(succession).

#### `EmplPositionReportingStruct` (`humanres-entitymodel.xml:743-761`)

The **org chart at the position level**, not the person level.
Fields: `emplPositionIdReportingTo`, `emplPositionIdManagedBy`,
`fromDate`, `thruDate`, `primaryFlag`. **Role:** the reporting tree
survives personnel turnover. If the CFO leaves, the position still
reports to the CEO position; the new CFO inherits. The most
defensible argument for keeping `EmplPosition` first-class in
kontor v2 (note 09 recommended deferring it).

#### `EmplLeave` (`humanres-entitymodel.xml:430-459`)

Single leave instance. PK `(partyId, leaveTypeId, fromDate)`. Status
seeds at `HumanResSeedData.xml:59-62`: 3 states (`LEAVE_CREATED →
LEAVE_APPROVED | LEAVE_REJECTED`). **No accrual math** in the entity
model — accrual is computed at query time from (employment start,
entitlement per leaveType, leaves taken). Weak: real systems need an
`:absence-balance` entity to handle carry-over and tenure-based
tiers. Note 09 §2 names this gap.

#### `PayHistory` (`humanres-entitymodel.xml:522-561`)

Wage history attached to `Employment`. 6-key PK (4 party/role keys +
`emplFromDate` + `fromDate`); `amount`, `periodTypeId`,
`salaryStepSeqId`, `payGradeId`. **Role:** every wage change is a new
row, never an update — exactly what kontor's bitemporal store does
for free with `:db.valid/from` on `:employment/wage`. Eliminates
`PayHistory` as a sidecar.

#### `PayrollPreference` (`humanres-entitymodel.xml:562-597`)

Per-employee deduction/payment routing. Fields: `deductionTypeId`
(FK to accounting `DeductionType`), `paymentMethodTypeId`,
`periodTypeId`, `percentage`, `flatAmount`, `routingNumber`,
`accountNumber`, `bankName`. Routes part of net pay to a deduction
(401(k), health-share) or to a bank account. **Bank fields are
unencrypted** — a real gap; kontor needs `:audit-doc/privilege
:pii-sensitive` or `:bank-account` linkage.

#### `BenefitType` + `PartyBenefit` (`:303-316`, `:473-513`)

Benefit catalog + enrollment. `PartyBenefit` carries
`actualEmployerPaidPercent` and `cost`. No race-condition story in
the data model; concurrent enrollment would need application-level
locking.

#### `UnemploymentClaim` (`humanres-entitymodel.xml:635-659`)

US-specific shape: tracks state-UI claims affecting future SUTA
rates. **Role:** the only nominally jurisdictional entity in the
humanres model — everything else is country-agnostic.

### 1.3 State machines

OFBiz uses the **status framework**: `StatusType` + `StatusItem` +
`StatusValidChange` rows in the `common` package, queried by the
`status` services. HR seeds five state machines in
`HumanResSeedData.xml`:

| Status type | States | Lines |
|---|---|---|
| `EMPLOYMENT_APP_STTS` | (declared but no items seeded in HR; consumers seed) | `:22` |
| `EMPL_POSITION_STATUS` | `EMPL_POS_PLANNEDFOR → EMPL_POS_ACTIVE → EMPL_POS_INACTIVE` | `:23-26` |
| `HR_DEGREE_STATUS` | `HR_DS_COMPLETE`, `HR_DS_INCOMPLETE`, `HR_DS_DEFERRED` | `:30-33` |
| `HR_JOB_STATUS` | `HR_JS_FULLTIME`, `HR_JS_PARTTIME`, `HR_JS_CONTRACTOR` | `:35-38` |
| `PARTYQUAL_VERIFY` | `PQV_NOT_VERIFIED`, `PQV_VERIFIED` | `:40-42` |
| `IJP_STATUS` | `IJP_APPROVED`, `IJP_REJECTED` | `:44-46` |
| `RELOCATION_STATUS` | `STATUS_PENDING`, `STATUS_RELOCATED` | `:48-50` |
| `TRAINING_STATUS` | `TRAINING_APPLIED → TRAINING_APPROVED → TRAINING_ASSIGNED → TRAINING_PROPOSED → TRAINING_REJECTED` | `:52-57` |
| `LEAVE_STATUS` | `LEAVE_CREATED → LEAVE_APPROVED \| LEAVE_REJECTED` | `:59-62` |

The **employment lifecycle proper** (`applicant → employed →
terminated`) is implicit. There is no `EMPLOYMENT_STATUS` enum: the
states are encoded *structurally*:

- **Applicant** — `EmploymentApp` row exists with no linked `Employment`.
- **Hired** — `EmploymentApp.statusId` flips to a hired state AND an
  `Employment` row is created.
- **Active** — `Employment.thruDate IS NULL` (or in the future).
- **Terminated** — `Employment.thruDate` is set, and
  `terminationReasonId` + `terminationTypeId` populate.

**Comparison with kontor's status-machine.** kontor's ADR-034 +
`kontor.status-machine` (status_machine.clj:43-360) ships the same
shape — `:status-transition` rows in the db, an
`:applicable-to-entity-type` predicate, transitions gated by a
`record-status-change!` validator. The OFBiz status framework is a
direct conceptual ancestor. **A kontor `:employment/state` facet
(`:applicant → :offered → :hired → :active → :on-leave → :terminated
→ :rehired`) drops in trivially via `kontor.status-machine`.** Same
for `:absence/state` (`:requested → :approved → :rejected`) and
`:position/state`.

**PTO accrual + consumption.** Not state-machine-driven in OFBiz —
the only states are on the `EmplLeave` event itself. Real accrual
math (entitlement carries from year to year, partial-period prorating
on mid-month hire, tenure-based tier escalations) is application
logic that OFBiz does not provide. kontor's `:schedule` (ADR-032,
schedule.clj) is the right primitive for accrual: an
`:absence-accrual-schedule` with `:schedule/frequency :biweekly`
emits an `:absence-allocation` posting each pay period, consumed by
matching `:absence/state :approved` events.

### 1.4 The "payroll engine" that isn't

**OFBiz does not compute payroll.** No `payroll` package, no
gross-to-net calculator, no jurisdictional plumbing. What it provides
is the **chassis** for an external engine to read from and write back to:

- `PayHistory` — what the agreed-on wage is at a date.
- `PayrollPreference` — where the money goes (deductions, bank).
- `DeductionType` / `Deduction` (in accounting, not HR) — what
  deductions exist as accounting concepts.

The external engine's contract is: read these tables, compute, write
`Payment` rows (in the accounting package) with FK back to `Deduction`
rows for the line-itemed deductions. `AcctgTrans` rows capture the
GL posting per payroll run. There is no `PayrollRun` entity binding
all the payments and acct-trans together — that's a real gap.

**The employer-side accrual is also absent.** Accrued-but-unpaid
PTO liability postings, employer SI/SUTA accruals, year-end bonus
accruals — none of this is in the entity model. A consumer must build
on top.

**Per-jurisdiction structure pattern (Odoo for comparison).** Odoo's
`hr.payroll.structure.type` (referenced from `hr_version.py:35-39`
default-salary-structure resolver) keys per `country_id`. Each
country's payroll module (e.g., `l10n_be_hr_payroll`) registers its
structure types at install. The kontor analog is what ADR-005 set up
for tax: `PayrollProvider` protocol, l10n modules implement it.

**Take-away.** OFBiz validates the
"substrate-supplies-entities-and-protocols, country-modules-supply-
math" split that note 09 §3 already proposed. **kontor's PayrollProvider
protocol is the right shape**; gross-to-net stays out of the kernel.

### 1.5 Services as workflows

The 54 services in `applications/humanres/servicedef/` are >90% pure
CRUD (`engine="entity-auto"`) with `auth="true"` permission gates.
The non-CRUD ones are:

- `createEmployment` (`services.xml:189-202`) — composite: creates
  the `Employment` row AND seeds the first `PayHistory` row in one
  service. Engine: `simple` (the OFBiz minilang). This is the
  "create-employment-with-initial-wage" composite write. **kontor
  analog**: a `kontor.process.run-process` with two `*-tx-data`
  builders (`employment-create-tx-data` + `employment-wage-tx-data`),
  per ADR-067 / ADR-068.
- `expirePayHistory` (`services.xml:303-308`) — marks a pay-history
  row as superseded. kontor analog: `:db.valid/to` set on the prior
  `:employment/wage` value when a new value is asserted; no separate
  expiration call needed.
- `updatePayHistory` (`services.xml:288-297`) — `simple` engine, not
  `entity-auto`; presumably enforces "you may not update an active
  history row, only end-date it and create a new one." kontor's
  bitemporal store enforces this naturally: history is append-only.

## 2. Tryton `company` + `attendance` — secondary

Path: `/home/christian-weilbach/Development/tryton/modules/company/`
+ `/home/christian-weilbach/Development/tryton/modules/attendance/`

License: **GPLv3** — read for design pattern, do not lift code.

Tryton's HR coverage is thin. There is **no** `account_payroll`
module in the local tree; the HR substrate is the `Employee` class
inside the `company` module plus the small `attendance` module. The
employee data model (`company/company.py:320-407`):

```python
class Employee(ModelSQL, ModelView):
    __name__ = 'company.employee'
    party = fields.Many2One('party.party', 'Party', required=True, ...)
    company = fields.Many2One('company.company', 'Company', required=True, ...)
    active = fields.Function(fields.Boolean("Active"),
        'on_change_with_active', searcher='search_active')
    start_date = fields.Date('Start Date', ...)
    end_date = fields.Date('End Date', ...)
    supervisor = fields.Many2One('company.employee', "Supervisor", ...)
    subordinates = fields.One2Many('company.employee', 'supervisor', ...)
```

The `(party, company, start_date, end_date)` shape mirrors OFBiz's
`Employment` minus the role-pair and minus the multi-employment
support — a Tryton employee row is unique per `(party, company)`, so
a person who works for two Tryton companies needs two records. The
`active` field is a computed predicate over `(start_date, end_date,
context.date)` — a clever pattern that lets the same row represent
"active right now" or "active on 2026-03-31" depending on the search
context (`company.py:331-400`).

**What Tryton does right:** the supervisor is a self-ref on
`company.employee`, with a `subordinates` reverse — the org chart
lives on the employee record, not on a separate position. For
sub-100-employee shops this is right-sized. The bitemporal-via-search-
context trick (`search_active` at `company.py:375-400`) is elegant.

**What Tryton does wrong (or just simply):** no multi-employment;
re-hire requires a new row with a new `id` (loses continuity of
identity in queries). No position separate from person, no benefits,
no leave, no wage history, no payroll. The `attendance.line` event
log (`attendance/attendance.py:27-138`) is a minimal clock-in/out
event with `attendance.period` close-period semantics — kontor's
ADR-014 period model would replace it cleanly.

**What kontor could borrow conceptually:** the
`active`-as-function-of-`(start_date, end_date, context.date)`
pattern. kontor's `:db.valid/from`/`:db.valid/to` plus
`(d/valid-at db t)` does exactly this — Tryton's design is what you
end up doing in a non-bitemporal relational store to *simulate*
bitemporality on a single relation.

**Code-lift verdict.** None. GPL; design idea is already absorbed
by kontor's bitemporal substrate.

## 3. Odoo `hr` + `hr_version` — secondary

Path: `/home/christian-weilbach/Development/odoo/addons/hr/` (+ 27
other `hr_*` add-ons plus 6 `l10n_*_hr*` add-ons; `hr_payroll` is
enterprise-only so absent from this community-edition tree).

License: **LGPLv3** — read for design pattern, do not lift code.

The architectural pattern that matters: `hr.employee._inherits =
{'hr.version': 'version_id'}` (`hr_employee.py:43`). Odoo's
`_inherits` is a delegation pattern — the employee gets all `hr.version`
fields as if they were its own, but the underlying storage is a
`hr.version` row referenced by `version_id`. Each change to a
versioned field writes a new `hr.version` row with a fresh
`date_version` (`hr_version.py:53`).

```python
class HrVersion(models.Model):
    _name = 'hr.version'
    _order = 'date_version'
    company_id = fields.Many2one('res.company', ...)
    employee_id = fields.Many2one('hr.employee', ...)
    date_version = fields.Date(required=True, default=fields.Date.today, tracking=True, ...)
    last_modified_uid = fields.Many2one('res.users', ...)
    last_modified_date = fields.Datetime(...)
    # ~80 fields: identification, address, marital, employment_type,
    # department, job, wage, salary_structure, etc.
```

The `current_version_id` (`hr_employee.py:55-60`) is a stored
computed field — the latest version. Reads use it. Historical reads
traverse `version_ids` (`hr_employee.py:66-72`) filtered by
`date_version <=`.

**What Odoo does right:**

- **Effective-dating as a first-class citizen.** Every employee
  master-data change is a new row; nothing is destructively updated.
  This is the bitemporal-without-bitemporal-substrate workaround
  (same pattern as SAP Foundation Objects' portlets and Workday's
  effective-dated entries).
- **Per-country payroll structure.** `_default_salary_structure`
  (`hr_version.py:35-39`) looks up `hr.payroll.structure.type` by
  `country_id`. Per-country payroll modules register structure types
  at install. This is the protocol-shape kontor needs for
  `PayrollProvider`.
- **The `_inherits` delegation** keeps the read-side query ergonomic:
  `employee.wage` resolves through to the current version. Without
  it, every query would have to remember to traverse versions.

**What Odoo does wrong (or differently):**

- **Versioning is field-scoped to `hr.version`, not universal.**
  Edits to `hr.employee.user_id` or `hr.employee.work_contact_id`
  don't create a new version — they're stored on the employee row
  itself. This is an Odoo internal convention; auditors who expect
  "every change is dated" hit edge cases.
- **`tracking=True` on every versioned field** layers in a mail
  message history. Useful for UI but redundant with the row history.
- **Group-based field visibility** (`groups="hr.group_hr_user"` on
  every PII field) is access-control by hiding columns from
  unauthorized users. Same model as OFBiz's `encrypt="true"` flag
  but at a different layer. **kontor's analog** is `:audit-doc/
  privilege` (ADR-051) plus the consumer's auth layer.

**What kontor could borrow conceptually:**

- The `country_id` keyed `payroll.structure.type` registry is the
  shape `PayrollProvider` implementations should follow: register at
  install, resolved per (country, employee, period).
- The "modified-by-uid + modified-at on every versioned row"
  invariant. kontor has this for free via datahike's `:db/tx-time`
  and `:db/tx-instant`, augmented with `:status-history/changed-by-
  uid` per ADR-038 (audit_doc.clj). No additional schema needed.
- The split between `hr.employee` (the identity-stable record) and
  `hr.version` (the changing record) lines up with the Workday
  `Worker`/`Position` model and with our intended `:person` +
  `:employment` split. Confirms note 09 §1's recommendation.

**Code-lift verdict.** None (LGPL). The country-keyed
`payroll.structure.type` registry shape is the only thing worth
copying conceptually.

## 4. Reference patterns to lift into kontor (Apache-2.0 safe)

From OFBiz humanres + accounting:

1. **Party + PartyRole + Employment-relationship triple.** Translate
   to `:partner/kind :employee` plus `:employment` entity. ~12 new
   attributes (start-date, end-date, employer-partner,
   employee-partner, job-title, department, wage, wage-period,
   supervisor, termination-{reason,type}, status).
2. **Position separate from Person.** Translate to `:position` +
   `:position-fulfillment` (m:n) + `:position-reports-to` (recursive).
   **Optional v2.**
3. **EmplLeave + EmplLeaveType + EmplLeaveReasonType.** Translate
   to `:absence` + `:absence-type` + `:absence-reason`. Drive accrual
   via `kontor.schedule` (ADR-032).
4. **BenefitType + PartyBenefit.** Translate to `:benefit-type` +
   `:benefit-enrollment` with `employerPaidPercentage`.
5. **PayrollPreference** — translate to `:payroll-preference` but
   route bank fields through existing `:bank-account/*` joined to
   `:partner [:partner/kind :employee]`.
6. **DeductionType (catalog only).** Translate to `:deduction-type`.
   Per-payslip `Deduction` maps to kontor postings (one CR per
   deduction).
7. **EmploymentApp** — defer to v2; commodity SaaS replaces for most
   deployments (note 09 §4).
8. **View-entity layer** — kontor datalog renders these joins at
   query time. No schema needed.

## 5. The "ten most important entities" prioritization for kontor v1

Given Stage R sizing constraints (note 09 §7 sketched `:person` +
`:employment` + `:department` + `:work-schedule` + `:absence` +
`:absence-type` + `:pay-period` + PayrollProvider), the OFBiz
reference confirms:

| Rank | Entity | OFBiz source | kontor v1 | Notes |
|---|---|---|---|---|
| 1 | `:partner/kind :employee` (reuse) | `Party + Person` | YES | Reuses ADR-039 master record |
| 2 | `:employment` | `Employment` lines 330-370 | YES | Bitemporal valid-time replaces composite-PK pattern |
| 3 | `:department` | not in OFBiz humanres (uses `Party + PartyGroup`) | YES | Recursive per-entity tree |
| 4 | `:absence-type` | `EmplLeaveType` lines 460-472 | YES | Recursive catalog |
| 5 | `:absence` | `EmplLeave` lines 430-459 | YES | Status-machine via ADR-034 |
| 6 | `:work-schedule` | not in OFBiz (implicit in `EmplPosition.fulltimeFlag`) | YES | Weekly hours + holiday calendar |
| 7 | `:pay-period` | implicit (uses `PeriodType` from accounting) | YES | Maps to `:period/kind :payroll` |
| 8 | `:benefit-type` + `:benefit-enrollment` | `BenefitType` + `PartyBenefit` | v1 (schema only; no enrollment workflow) | Open-enrollment is consumer concern |
| 9 | `:deduction-type` | `DeductionType` in accounting-entitymodel.xml:2816 | YES | Catalog only |
| 10 | `PayrollProvider` protocol | (not in OFBiz; OFBiz delegates) | YES | Mirrors `TaxProvider` |

Deferred to v2:
- `:position` + `:position-fulfillment` + `:position-reports-to`
- `:employment-application` + `:job-requisition` + `:job-interview`
- `:skill` + `:skill-type` + `:performance-review`
- `:training-class` + `:training-enrollment`
- `:unemployment-claim`
- `:pay-grade` + `:salary-step`

## 6. Cross-links

- Pain-point validation: see [[73-hr-payroll-market-pain]] for which
  of these entities the real-world software actually gets wrong.
- Substrate audit + design calls: see
  [[74-hr-payroll-internal-gap-analysis]] for what kontor already
  provides and what's ambiguous.
- Predecessor: [09-hr-personnel-payroll.md](09-hr-personnel-payroll.md)
  proposed the overall shape; this note grounds it at file:line and
  validates against OFBiz's actual data model.
- Status-machine substrate: [doc/decisions.md ADR-034](../decisions.md)
  (kontor's analog of OFBiz `StatusType + StatusItem + StatusValidChange`).
- Audit doc / contracts: [doc/decisions.md ADR-038](../decisions.md)
  (kontor's analog of OFBiz `Employment.EMPLMNT_AGR → Agreement`).

## Appendix: key file:line citations

**OFBiz primary** (all paths under `/home/christian-weilbach/Development/ofbiz-framework`):
- Entity model: `applications/datamodel/entitydef/humanres-entitymodel.xml` (972 lines, 41 entities)
- Seed data: `applications/datamodel/data/seed/HumanResSeedData.xml:22-90`
- Services: `applications/humanres/servicedef/services.xml:189-345`
- `Person` cross-package: `applications/datamodel/entitydef/party-entitymodel.xml:2800-2860`
- `Party`, `PartyRole`, `Agreement`: `party-entitymodel.xml:1594, 2561, 64`
- `Deduction` / `DeductionType` (NOT in humanres): `applications/datamodel/entitydef/accounting-entitymodel.xml:2801-2827`

**Tryton secondary** (`/home/christian-weilbach/Development/tryton/`):
- Employee: `modules/company/company.py:320-407` (incl. `search_active` bitemporal-style search at `:375-400`)
- Attendance: `modules/attendance/attendance.py:27-254` (`Line`, `Period`, `SheetLine`)

**Odoo secondary** (`/home/christian-weilbach/Development/odoo/`):
- `hr.employee`: `addons/hr/models/hr_employee.py:29-128`
- `hr.version`: `addons/hr/models/hr_version.py:23-128` (per-country payroll registry at `:35-39`)

**kontor anchor citations** (`/home/christian-weilbach/Development/kontor/`):
- `kontor.status-machine`: `src/kontor/status_machine.clj:43-360`
- `kontor.audit-doc` privilege: `src/kontor/schema.clj:3480-3539`
- `:partner/kind`: `src/kontor/schema.clj:486-490`
- `:ledger/framework`: `src/kontor/schema.clj:2501-2506`

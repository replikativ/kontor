# 09 — HR / Personnel / Payroll: companion-project survey

Research note capturing the agent survey of Odoo, Tryton, SAP SuccessFactors / S/4HANA HCM, Workday Core HR, and Personio for the **HR + payroll + talent** domain. The goal is to inform the shape of future `kontor-hr` and per-jurisdiction `kontor-payroll-*-*` companion projects.

Date: 2026-05-12. Source: agent survey + Odoo/Tryton local read.

## 1. Core entity model — convergent across 5 systems

All five reference systems converge on the same shape, with differences in **temporality** and **org-unit granularity**.

### The person ↔ employment split

- **Person** (the human) — global identity, address, contact, citizenship, tax-IDs. Crosses entities.
- **Employment** (the relationship) — the contract between one person and one legal entity, with start-date / end-date.

| System | Person | Employment |
|---|---|---|
| Odoo | `hr.employee` (current view) + `hr.version` (effective-dated rows) | Implicit in `hr.version`'s `company_id` + dates |
| Tryton | `party.party` (shared) | `company.employee` with `(party, company, start_date, end_date, supervisor)` |
| SAP SuccessFactors EC | Foundation Objects + Employee | Employment Information portlet (effective-dated) |
| Workday | Worker | Position + Job assignment (multi-job ability) |
| Personio | Employee record (flat) | Implicit |

**Recommendation for kontor-hr:** Workday's *Worker with multi-job ability* model — `:person` (the human, global) + `:employment` (per-entity, effective-dated). Transfers add a new `:employment` row; the prior is end-dated. Cleaner than Odoo (re-create employee record under new company) or Tryton (single-company strict FK).

### Effective-dating

Odoo's `hr.version` is the canonical pattern: every change to wage, department, or contract dates writes a new row keyed by `(employee_id, date_version)`. Uniqueness index on the pair. This is the same shape as SAP EC's portlets and Workday's effective-dated entries. **Bitemporal-native** — slots into kontor ADR-008 directly.

### Org unit + position

- **Department** — recursive parent/child tree, one manager (FK to employee), scoped to an entity.
- **Position** (SAP/Workday only) — the *seat* separate from the *person*. Justified only at ≥500-emp scale.

**Recommendation for kontor-hr v1:** skip positions; ship `:department` + `:job-title` string on `:employment`. Add `:position` later if a real deployment needs it.

## 2. Time tracking

Three orthogonal concerns:

1. **Attendance** — raw clock-in/out events. Tryton's `attendance.py` is minimal: `(company, employee, at, date, type ∈ {in, out})`. Odoo adds geolocation + supervisor approval.

2. **Timesheet** — chargeable hours by project/task/work-center. **Critical insight**: Odoo uses `account.analytic.line` for timesheets. The timesheet IS an analytic posting without a financial counterpart, until costed into payroll or billed to a project.

3. **Leave / absence** — typed (vacation / sick / parental / comp / sabbatical), with allocation grants + accrual plans + accrual levels (tenure-based tiers).

### kontor cross-dep: timesheet ≡ analytic-line

kontor's existing **ADR-022 analytic dimensions** already cover the timesheet shape. We don't need a separate `:timesheet-entry` entity — a timesheet entry is just an `:analytic-distribution` on a `:posting` that doesn't post a financial amount yet (a notional posting with `:display-type :timesheet`, or a separate marker attribute).

PTO accrual liabilities are real balance-sheet items under IFRS/HGB/US-GAAP. Accrued-but-unused vacation posts `Dr Wage Expense / Cr PTO Liability`; reversed when taken or paid out.

## 3. Payroll — the hardest domain

The convergent pattern across all five systems: **payroll engine in → payslip → summary journal entry to GL out**.

### Per-jurisdiction complexity

| Country | Complexity |
|---|---|
| DE | Lohnsteuer + Solidaritätszuschlag + Kirchensteuer + SV (KV/PV/RV/AV/UV) + ELStAM federal communication + DEÜV SI communication |
| US | Federal + 50 state + ~1000 local income tax, FICA (employer+employee SS+Medicare), FUTA + 50× SUTA, 401(k) pre/post-tax, garnishments |
| BR | eSocial (federal HR-events bus, 40+ event types), INSS, FGTS (8% employer mandatory savings), 13º salário, férias (33% bonus) |
| IN | TDS on salary, PF (12%+12%), ESI, professional tax per-state |
| FR | PAS (prélèvement à la source), URSSAF, CSG/CRDS, AGIRC-ARRCO complementary pensions |

**None of this should live in the kontor kernel.** The correct shape is a `PayrollProvider` boundary, and each `kontor-payroll-<country>-<vendor>` is a thin adapter to:

- DATEV LODAS / Lohn und Gehalt (DE)
- Personio Payroll (DE, expanding EU)
- Lexware / Sage Lohn (DE)
- SAP HCM export (any country)
- ADP / Paychex / Gusto / Rippling (US)
- eSocial direct (BR)
- ZenHR / Workday EMEA (IN, others)
- Silae (FR)

**Personio + DATEV integration pattern** (researched): Personio doesn't compute payroll itself; pushes employee master + variable inputs (absences, one-time comps) into DATEV LODAS via *Lohnimportdatenservice*; pulls documents back via *Lohnauswertungsdatenservice*. **This is the canonical adapter shape** — kontor-payroll-de-personio mirrors it.

### Cross-dep with kontor kernel

Per pay period per entity, payroll generates roughly:

```
DR Gross Wages Expense (P&L, by cost-center analytic)
DR Employer SI Expense (P&L, by cost-center)
   CR Wages Payable (BS — to be paid to employee)
   CR Income Tax Withheld Payable (BS — to tax authority)
   CR Employee SI Withheld Payable (BS — to SI authority)
   CR Employer SI Payable (BS — to SI authority)
```

This consumes:
- `:account/external-codes` keyed by regulator (ADR-019) — wage-type → GL-account mapping
- `:tax` rates (DE Lohnsteuer rate tables technically `:tax` entries with `:tax/type-tax-use = :withholding`)
- `:entity/functional-commodity` (ADR-031)
- `:period` must be open (ADR-014)
- `:analytic-distribution` per employee cost-center (ADR-022)

Amendments to closed-period payroll must use the *adjustment period* per ADR-014 (DE "Spezialperiode 13–16" pattern).

## 4. Talent / recruitment / performance — mostly commodity SaaS

| Domain | Recommendation |
|---|---|
| Applicant tracking (ATS) | **Commodity** — Greenhouse / Lever / Workable / Personio Recruiting |
| Performance reviews / 360 | **Commodity** — Lattice / 15Five / Leapsome / Workday Talent |
| Learning management | **Commodity** — Cornerstone / Docebo / 360Learning |
| Goals / OKRs | **Commodity** — Lattice / 15Five / Leapsome |
| Skills / competencies | **Optional v2** — kontor-hr small surface; Odoo's `hr_skills` is the reference |
| Position management | **Optional v2** — only ≥500-emp deployments need it |
| Onboarding / offboarding | Workflow on top of `:employment` start/end — UI concern |
| Engagement surveys | **Commodity** — Culture Amp / Lattice |
| Compensation planning | **Commodity** — Lattice / Carta (for equity) |

The pattern: **anything the GL doesn't audit is fair game for commodity SaaS**.

## 5. Expense management — half-HR, half-accounting

Odoo's `hr.expense` shape is informative: `(employee_id, product_id [= expense category], date, total_amount, currency_id, tax_ids, payment_mode ∈ {own_account, company_account}, account_move_id, analytic_distribution)`.

The **payment_mode split** is load-bearing:

- `own_account` — employee paid; AP-style entry: `DR Expense (P&L, analytic) / CR Employee Reimbursement Payable`. On reimbursement, second entry settles to cash.
- `company_account` — paid with company card; `DR Expense / CR Corporate Card Clearing`. Bank-statement matching closes the clearing.

**Specialist competitors:** SAP Concur, Expensify, Pleo, Spendesk, Ramp, Brex, Pliant. Each runs its own GL-export pipeline.

**Recommendation:** ship the canonical entity shape (per Odoo) + `ExpenseImporter` adapter protocol for these vendors. No UI in kontor; UI lives in consumer apps (beleg).

## 6. Multi-entity + transnational HR

ADR-031's `:entity` is exactly what HR-multi-entity needs.

- **Employee transfers** (Acme-DE → Acme-US):
  - SAP "Global Assignment" — one Person, multiple concurrent Employments
  - Odoo / Tryton — re-create the employee under the new company (lose continuity)
  - Workday — single Worker, multi-job ability across Companies
  - **Recommendation for kontor-hr**: Workday-style. Transfer = new `:employment` row; prior end-dated.

- **Pay-frequency variation** — monthly DE vs biweekly US. A pay-period entity in `kontor-hr` rolls up to kontor's fiscal `:period`. One fiscal month = 1 DE-monthly run + 2 US-biweekly runs + 0-1 catchup runs.

- **Per-entity payroll provider** — one `PayrollProvider` impl per (country, vendor): `kontor-payroll-de-datev`, `kontor-payroll-de-personio`, `kontor-payroll-us-gusto`, `kontor-payroll-br-esocial`.

## 7. The shape of `kontor-hr`

### Core entities (in `kontor-hr` proper)

```clojure
:person          ; the human, global
  :person/external-id           ; tax-id or government-id
  :person/given-name :family-name :birth-date :gender
  :person/citizenship           ; ref to :country (multiple)

:employment      ; the relationship, per-entity, effective-dated
  :employment/person            ; ref to :person
  :employment/entity            ; ref to :entity (ADR-031)
  :employment/department        ; ref to :department
  :employment/job-title         ; string (v2: ref to :position)
  :employment/manager           ; ref to :employment (in same entity)
  :employment/start-date        ; instant; required
  :employment/end-date          ; instant; nil = current
  :employment/work-schedule     ; ref to :work-schedule
  :employment/cost-center       ; ref to :analytic-account (ADR-022)
  :employment/wage              ; bigdec + ref to :commodity
  :employment/wage-period       ; :monthly | :hourly | :annual

:department      ; recursive, per-entity
  :department/code :name :entity :parent-department :manager

:work-schedule   ; weekly working hours + holiday calendar
:absence-type    ; :vacation :sick :parental :sabbatical :comp :other
:absence         ; (employment, type, start-date, end-date, hours)
:absence-balance ; computed view: entitlement - taken - planned

:pay-period      ; per-entity, with rollup to fiscal :period
  :pay-period/entity :start-date :end-date :state

;; PayrollProvider protocol (sibling of TaxProvider, EInvoiceProvider, CostingProvider)
;; Normalized :payroll-result schema:
{:payroll-result/employment, :pay-period
 :gross :net
 :deductions [{:code :amount :account-hint}]
 :employer-contributions [{:code :amount :account-hint}]}
```

### Per-jurisdiction modules

- `kontor-payroll-de-datev` — wage-type → HGB account mapping; DATEV LODAS adapter
- `kontor-payroll-de-personio` — DATEV integration via Personio
- `kontor-payroll-us-gusto`, `kontor-payroll-us-rippling`, `kontor-payroll-us-adp`
- `kontor-payroll-br-esocial` — eSocial XSD parsing + journal mapping
- `kontor-payroll-fr-silae`
- `kontor-payroll-in-zenhr`

## 8. Two minor kontor kernel additions surfaced

Both small + low-risk additive:

1. **Bootstrap `:cost-center` analytic plan** at kernel install (mirrors how we bootstrap the primary ledger + primary valuation book). Every companion will lean on this plan — payroll, project, manufacturing, asset, fleet.

2. **Document `:transaction/source` convention for HR-originated postings**. Already exists in schema; just needs a doc-pointer convention: `:transaction/source` = `"payslip:<payslip-id>"` for payroll runs, `"expense-report:<id>"` for expense submissions, etc.

## 9. Dependency graph

```
                ┌──────────────────────────────────────────┐
                │              kontor (kernel)             │
                │  :entity :ledger :account :partner       │
                │  :transaction :posting :tax :period      │
                │  :analytic-plan :analytic-account        │
                │  :commodity :fiscal-position             │
                │  TaxProvider · CostingProvider           │
                └──────────────────────────────────────────┘
                          ▲          ▲          ▲
                          │ reads    │ writes   │ reads
                          │ entities │ txns     │ accts/taxes
                ┌─────────┴──────────┴──────────┴──────────┐
                │              kontor-hr                   │
                │  :person :employment :department         │
                │  :work-schedule :absence :absence-type   │
                │  :pay-period · PayrollProvider protocol  │
                │  :payroll-result schema                  │
                │  journal-entries-from-payroll-result     │
                └──────────────────────────────────────────┘
                       ▲          ▲          ▲          ▲
                       │          │          │          │
              ┌────────┘          │          │          └─────────┐
              │                   │          │                    │
       ┌──────┴─────┐    ┌────────┴───┐  ┌───┴──────────┐  ┌──────┴──────┐
       │ kontor-    │    │ kontor-    │  │ kontor-      │  │ kontor-     │
       │ payroll-de │    │ payroll-us │  │ payroll-br   │  │ expense-    │
       │ -datev /   │    │ -gusto /   │  │ -esocial     │  │ adapter-*   │
       │ -personio  │    │ -adp /     │  │              │  │ (concur,    │
       │            │    │ -rippling  │  │              │  │ expensify,  │
       └────────────┘    └────────────┘  └──────────────┘  │ pleo, ramp) │
                                                            └─────────────┘
```

## 10. Load-bearing vs commodity, summarized

| Layer | Verdict |
|---|---|
| Person + Employment (effective-dated) | **Load-bearing** for kontor-hr |
| Department + cost-center linkage | **Load-bearing** (consumes ADR-022) |
| Timesheet → analytic-line | **Reuses existing kernel** (no new entity) |
| Leave accrual liability postings | **Load-bearing** (ADR-014 + ADR-021) |
| Payroll computation engine | **Out-of-scope kernel concern**; ship adapters |
| Payroll → GL journal composition | **Load-bearing kernel value-add** |
| Expense report entity + GL composition | **Load-bearing** (UI to consumer) |
| Applicant tracking | **Commodity SaaS** |
| Performance / 360 / OKR | **Commodity SaaS** |
| Learning management | **Commodity SaaS** |
| Skills / competency tracking | **Optional v2** |
| Position management (SAP/Workday style) | **Optional v2** |

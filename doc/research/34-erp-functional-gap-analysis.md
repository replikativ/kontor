# 34 — ERP functional-gap analysis: what companions come next

**Date:** 2026-05-14
**Method:** survey of the four local reference ERPs (`../ofbiz-framework` Apache-2.0,
`../odoo` LGPLv3, `../tryton` GPLv3, `../erpnext` GPLv3) at directory + entity-def
depth, cross-checked against SAP S/4HANA and NetSuite module taxonomies, mapped
against kontor's current kernel + companions.
**Builds on (does not duplicate):** research notes 09 (HR/payroll), 10 (business-OS
companion survey), 12 (OFBiz mappings), 13/14/15 (stage J/K/L pain), 11 (license
calls).
**Verified?** Medium-high — every OFBiz claim cites `file:line`; Odoo/Tryton/ERPNext
claims cite the module directory; SAP/NetSuite are taxonomy-level from public docs.

---

## 1. What kontor already has

**Kernel primitives** (the substrate companions compose on):

| Primitive | ADR | Covers |
|---|---|---|
| `:transaction` / `:posting` + sum-to-zero | 021/031 | double-entry, multi-ledger, multi-entity |
| `:schedule` + `:schedule-occurrence` | 032 | recurring postings (depreciation, revrec, subscription, lease, PTO) |
| `:status-transition` / `:status-history` + `status-machine` | 034 | every entity state machine + audit trail |
| `:side-effect-intent` + dispatcher | 041 | cross-aggregate side effects |
| `:valuation-book` / `:valuation-layer` / `:layer-consumption` | 027/028 | parallel cost bases, FIFO/LIFO/Avg layers |
| `CostingProvider` protocol | 029 | pluggable cost engines |
| `plan-stock-move` | 030 | pure posting-builder for inventory moves |
| `TaxProvider` / `EInvoiceProvider` | 005/017 | pluggable tax + e-invoice |
| `:analytic-plan` / `:analytic-account` / `:analytic-distribution` | 012/022 | cost/profit centers, project tags, **timesheet shape** |
| `:cost-center` plan bootstrapped at install | 032 | shared analytic plan for every companion |
| `:payment-application` | 043 | partial-payment primitive |
| `:audit-doc` / `:approval-policy` (SoD) | 038 | governance backbone |
| `:legal-hold` / `:retention-policy` | 049/050 | purge-blocking + retention sweeper |
| `:entity` legal-entity scope | 031 | per-entity sum-to-zero, transnational books |
| bitemporal `:tx/valid-from` resolver | 048 | as-of-tx × as-of-valid on every query |

**Companions shipped** (`modules/`):

- `partner` — party-as-root MDM (person/org, contact-mech, roles, relationships, merge, bank-account, tax-id, tags, KYC hooks). ADR-033/039/040.
- `sales` — order aggregate: header, items, **ship-groups + inventory-reservation triple**, multi-level adjustments, roles, state machine. `:order/type` discriminates `:sales`/`:purchase`. ADR-035.
- `invoice` — order→invoice bridge, `:invoice/type` discriminator, three-tier GL resolution, AcctgTrans posting, status machine. ADR-036.
- `procurement` — full P2P: requirement, receipt, 3-way match (tolerance bands), service-acceptance, drop-ship, returns-to-vendor. ADR-042.
- `collections` — AR collections: aging, collection-case, dunning, credit-hold, dunning-pause, payment-promise, dispute, write-off. ADR-043.
- `asset` — fixed-asset register, asset-class, asset-event, per-(asset,ledger) depreciation books, `DepreciationProvider`, runner. ADR-053/054/055.
- `l10n-{de,fr,ca,us,au,jp,cn,in,br,mx,at}` — per-country charts, tax stacks, statements, e-invoice emitters.
- `bank-{de,fr,ca,us,at}` — statement importers → suggested reconciliation postings.
- `einvoice-de` — Factur-X / XRechnung / ZUGFeRD.

**Already planned** (roadmap Phase 6, gated): `kontor-legal` (Stage M), `kontor-revrec`
(N), `kontor-subscription` (O), `kontor-project` (P), `kontor-commerce-adapter` (Q),
`kontor-hr` + `kontor-payroll-de-datev` (R). Candidates: `kontor-mcp`,
`kontor-forecast`, `kontor-workflow`.

So the **order-to-cash and procure-to-pay spines are done**, master-data is done,
fixed assets just shipped. The gaps below are everything *off* those two spines.

---

## 2. The gap analysis, domain by domain

For each: who has it, the license-clean lift source, what kernel substrate already
exists, the verdict, and the materiality (would a DE-GmbH / US-LLC mid-market
customer hit it in their first quarter).

### 2.1 Inventory / warehouse management — **MISSING, high materiality**

One-line: physical stock — facilities, bins, lots, on-hand quantity, reservations,
physical-inventory counts, valuation roll-forward separate from the GL.

- **Who has it:** OFBiz (`Facility`, `InventoryItem`, `InventoryItemDetail`,
  `PhysicalInventory`, `Lot` — `product-entitymodel.xml:996,1953,2125,2419,2428`;
  `ItemIssuance`, `Picklist`, `ShipmentReceipt` — `shipment-entitymodel.xml:44,151,389`).
  Odoo `stock`. Tryton `stock`, `stock_lot`, `stock_forecast`, `stock_supply`,
  `stock_inventory_location`. ERPNext `stock`. SAP MM-IM / EWM. NetSuite Inventory
  Management.
- **Lift source:** OFBiz `Facility` + `InventoryItem` + `InventoryItemDetail`
  (`product-entitymodel.xml:996-1461, 1953-2353`) and `ShipmentReceipt`
  (`shipment-entitymodel.xml:389-444`) — Apache-2.0, liftable.
- **Substrate already there:** **most of the financial half.** `:valuation-book` +
  `:valuation-layer` + `:layer-consumption` (ADR-027/028) is the costing ledger;
  `plan-stock-move` (ADR-030) is the posting-builder; `CostingProvider` (ADR-029)
  is FIFO/LIFO/Avg/Std. `procurement` already drives receipts into valuation
  layers. **What's missing is the operational quantity ledger:** a `:facility` /
  `:location` / `:inventory-item` / `:stock-quantity` model with on-hand,
  available-to-promise, and reservation. The `sales` ship-group triple already
  references `:inv-reservation/*` — that reservation entity is currently a stub
  with nothing tracking real on-hand against it.
- **Verdict: (a) new companion `kontor-inventory` — HIGH priority.** No new kernel
  primitive needed; the costing/valuation kernel is the hard part and it exists.
  This is the missing operational layer under the two spines that already ship.
  It also unblocks manufacturing (§2.2) and POS (§2.10).
- **Materiality: YES, first quarter.** Any GmbH or LLC that sells physical goods
  (not pure services) needs on-hand quantity, COGS-on-shipment, and a year-end
  physical-inventory count. `procurement` posts receipts to valuation layers today
  but there is no "how many widgets are in the warehouse" query. This is the
  single most material gap.

### 2.2 Manufacturing / MRP / BOM — **MISSING, low-medium materiality**

One-line: bills-of-material, routings/work-centers, production orders, WIP,
backflushing, production-cost roll-up.

- **Who has it:** OFBiz (`manufacturing` app — `ProductManufacturingRule`,
  `MrpEvent`, `TechDataCalendar` in `manufacturing-entitymodel.xml:43,166,80`;
  BOM via `ProductAssoc` `product-entitymodel.xml:2935`; `CostComponent` /
  `CostComponentCalc` `product-entitymodel.xml:788,884`; production runs via
  `WorkEffort`). Odoo `mrp`. Tryton `production`, `production_routing`,
  `production_work`, `production_outsourcing`. ERPNext `manufacturing` +
  `subcontracting` (strong). SAP PP. NetSuite Manufacturing / WIP & Routings.
- **Lift source:** OFBiz `manufacturing` services (`services_bom.xml`,
  `services_mrp.xml`, `services_production_run.xml`, `services_routing.xml`) +
  `CostComponentCalc` — Apache-2.0. The BOM-explosion + production-cost-roll-up
  logic is liftable as patterns.
- **Substrate already there:** **the deep accounting parts.** Production consumes
  raws → `plan-stock-move` (ADR-030). Finished-good capitalization → new
  `:valuation-layer` with cost = component layers + labor + applied OH. Labor +
  overhead → `:analytic-distribution` (ADR-022); standard-vs-actual variance is
  two postings. `:schedule` covers nothing here, but `CostingProvider` needs one
  new method: `compute-production-cost`. **What's missing:** `:bom` / `:bom-line`
  (a phantom/multi-level tree — `product_kit` in Tryton is the minimal form),
  `:routing` / `:work-center`, `:production-order` with a state machine, and the
  WIP-account posting choreography. Depends on §2.1 (inventory) landing first.
- **Verdict: (b) one small kernel primitive first** — a `CostingProvider/compute-production-cost`
  method — **then (a) new companion `kontor-manufacturing` — LOW-MEDIUM priority,
  deferred.** Roadmap already lists `kontor-mfg` as "deferred until concrete
  consumer demand"; that call holds. Most kontor target customers (DE-GmbH
  services firms, US-LLC) are not discrete manufacturers.
- **Materiality: NO for the typical target customer; YES if the customer makes
  physical goods.** Gated correctly. Build when a manufacturing consumer story
  appears.

### 2.3 HR & payroll — **MISSING, medium materiality** (already researched, note 09)

One-line: person/employment master, departments, absence/PTO, payroll-to-GL.

- **Who has it:** OFBiz `humanres` (`Employment` `humanres-entitymodel.xml:330`,
  `EmplLeave:430`, `EmplPosition:665`, `PayHistory:522`, `PayGrade:514`). Odoo
  `hr`, `hr_holidays`, `hr_work_entry`, `hr_payroll` (Enterprise). Tryton —
  thin (`company.employee`, `attendance`, `timesheet`). ERPNext strong HR. SAP
  HCM / SuccessFactors. NetSuite SuitePeople.
- **Lift source:** OFBiz `Employment` + `EmplLeave` + `PayHistory` — Apache-2.0.
- **Substrate already there:** **almost nothing kontor-specific is needed, which
  is the point.** Note 09 established it: `:person` + `:employment` (effective-
  dated, multi-job per Workday) is plain companion data; timesheet ≡
  `:analytic-distribution` (no new entity); PTO-accrual liability rides
  `:schedule` + period locks; payroll-engine is **out of kernel scope** behind a
  `PayrollProvider` boundary; payroll→GL journal composition is the kernel
  value-add and already expressible with `build-transaction` +
  `:account/external-codes` (ADR-019).
- **Verdict: (a) new companion `kontor-hr` + per-jurisdiction `kontor-payroll-*`
  adapters — MEDIUM priority.** Already on the roadmap as Stage R. No new kernel
  primitive. The payroll *computation* is correctly out-of-scope (note 09 §3);
  kontor ships the adapter protocol and the GL-composition helper only.
- **Materiality: YES eventually, NO necessarily in Q1.** A GmbH with employees
  needs payroll from day one — but they almost always already run DATEV LODAS /
  Lexware, and the integration is "import the journal," not "compute the payroll."
  The companion's job is the import, and it's medium-urgency because the customer
  has a working payroll system already.

### 2.4 Project management / job costing / timesheets — **MISSING, high materiality**

One-line: projects, tasks/WBS, milestones, timesheets, WIP, T&M and
fixed-fee-milestone billing, percent-complete revenue.

- **Who has it:** OFBiz `WorkEffort` + `TimeEntry` + `Timesheet` +
  `WorkEffortBilling` (`workeffort-entitymodel.xml:184,42,77,386`). Odoo `project`
  + `hr_timesheet` + `sale_timesheet`. Tryton `project`, `project_invoice`,
  `project_plan`, `project_revenue`, `timesheet`, `timesheet_cost`. ERPNext
  `projects`. SAP PS (Project System). NetSuite Project Management / SuiteProjects.
- **Lift source:** OFBiz `WorkEffort` hierarchy + `WorkEffortBilling` +
  `WorkEffortCostCalc` (`workeffort-entitymodel.xml:184-631`) — Apache-2.0,
  liftable. This is the cleanest OFBiz subsystem for kontor's idiom.
- **Substrate already there:** **strong.** Note 10 §6 + note 09 §2 established it:
  timesheet entry ≡ `:analytic-distribution` on a posting with no financial
  amount yet (or `:display-type :timesheet`) — **no new entity**. Project ≡
  `:analytic-account` on the `:cost-center` plan or a dedicated `:project` plan
  (ADR-022). WIP postings (cost → BS WIP account during the project; WIP → COGS +
  revenue at milestone) are plain `build-transaction` calls. Percent-complete
  revrec emits a posting per period from `:schedule`. Multi-currency contracts:
  `Money` + commodity tag native.
- **Verdict: (a) new companion `kontor-project` — HIGH priority.** Roadmap Stage P.
  No new kernel primitive — needs `:project` / `:task` / `:milestone` entities
  and the three billing-mode helpers, all companion-level. Should arguably move
  *up* the queue: it's the natural consumer for the agency/consulting segment
  that note 10 §6 flags, and kontor's analytic substrate is purpose-built for it.
- **Materiality: YES, first quarter — for services firms.** A consulting GmbH or
  a US professional-services LLC bills by project/timesheet from day one; without
  this companion they cannot answer "is project X profitable" or do
  percent-complete revenue. For a pure-product company, low. Given kontor's likely
  early adopters skew services (beleg = contractor invoicing), this is high.

### 2.5 CRM (leads / opportunities / pipeline) — **OUT OF SCOPE (mostly)**

One-line: leads, opportunities, sales pipeline, activities, campaign attribution.

- **Who has it:** Odoo `crm`. Tryton `sale_opportunity`. ERPNext `crm`. OFBiz —
  thin (SFA in `marketing`). SAP CRM / C4C. NetSuite CRM. Salesforce is the
  category gold-standard.
- **Substrate already there:** **none needed — and that's correct.** Note 10 §2
  established it: CRM does not touch the GL. The lead→opportunity lifecycle is
  pure operational data with no posting until a won opportunity becomes a `sales`
  order — and that conversion point is already covered by the `sales` companion.
- **Verdict: (c) out-of-scope for an accounting kernel.** Commodity SaaS
  (Salesforce / HubSpot / Pipedrive — note 10 §14). The only kontor touch-point is
  the partner-master reconciliation, and `partner` already provides that. If a
  consumer wants a thin opportunity tracker, it belongs in the consumer app
  (simmis), not a kontor companion. Customer-master already reconciles via
  `:partner`.
- **Materiality: low as a kontor gap.** A customer hits "I need a CRM" in Q1, but
  the answer is "use one, point it at kontor's partner master" — not a missing
  kontor companion.

### 2.6 Expense management — **MISSING, high materiality** (already researched, note 09 §5)

One-line: employee expense reports, own-account vs company-card, reimbursement,
GL composition.

- **Who has it:** Odoo `hr_expense`. Tryton — none native. ERPNext (in HR). SAP
  Concur. NetSuite Expense Management. Specialists: Expensify, Pleo, Ramp, Brex.
- **Lift source:** none needed — note 09 §5 already gives the canonical entity
  shape (Odoo `hr.expense` shape, cleanroom).
- **Substrate already there:** **everything.** The `payment_mode` split is the
  only load-bearing modeling decision and it's two posting shapes: `own_account`
  → `DR Expense / CR Employee Reimbursement Payable`; `company_account` →
  `DR Expense / CR Corporate Card Clearing` (bank-statement matching closes the
  clearing — `bank-*` companions + `reconciliation.clj` already do this).
  `:analytic-distribution` carries the cost-center. `:audit-doc` carries the
  receipt image hash.
- **Verdict: (a) new companion `kontor-expense` — HIGH priority, and it is small.**
  No new kernel primitive. Ship the entity shape + an `ExpenseImporter` adapter
  protocol (sibling of `TaxProvider`) for Concur/Expensify/Pleo/Ramp GL-export
  pipelines. Note 09 §5 already specs it. This is a cheap, high-value companion —
  arguably the *fastest* of the top-5 to ship.
- **Materiality: YES, first quarter.** Every company with employees has expense
  reports in month one. Either kontor models them or every customer hand-keys
  journal entries from a spreadsheet.

### 2.7 Budgeting & financial planning — **MISSING, medium materiality**

One-line: budgets by account/period/cost-center, budget-vs-actual variance,
budget revisions, encumbrance/commitment accounting.

- **Who has it:** OFBiz (`Budget`, `BudgetItem`, `BudgetRevision`,
  `BudgetScenario` — `accounting-entitymodel.xml:48,82,182,238`). Tryton
  `account_budget`, `analytic_budget`. Odoo `account_budget` (Community in older
  versions). ERPNext (Budget in accounts). SAP BPC / S/4HANA budgeting.
  NetSuite Planning & Budgeting.
- **Lift source:** OFBiz `Budget` + `BudgetItem` + `BudgetRevision`
  (`accounting-entitymodel.xml:48-315`) — Apache-2.0, liftable. A clean, simple
  model.
- **Substrate already there:** **the reporting half.** `report.clj` +
  `financial_statements.clj` already walk account trees and materialize
  per-period values — budget-vs-actual is the same engine with a second column.
  `:analytic-account` carries the cost-center dimension. **What's missing:** a
  `:budget` / `:budget-line` entity (account × period × analytic → planned
  amount), and — if the customer wants encumbrance accounting — a commitment
  posting that the `procurement` requisition could emit into a memo ledger
  (a `:ledger` per ADR-021, never touching the financial books).
- **Verdict: (a) new companion `kontor-budget` — MEDIUM priority.** No new kernel
  primitive for plain budget-vs-actual; encumbrance accounting would reuse the
  `:ledger` primitive (already exists). Small companion. Pairs naturally with the
  declarative report engine.
- **Materiality: medium.** A mid-market GmbH/LLC wants budget-vs-actual by quarter
  two or three, not necessarily week one. Real but not urgent.

### 2.8 Financial consolidation & intercompany elimination — **MISSING, medium materiality**

One-line: roll up multiple legal entities into a group statement, eliminate
intercompany AR/AP and intercompany revenue, currency translation (CTA).

- **Who has it:** Tryton `account_consolidation`. Odoo `account_consolidation`
  (Enterprise). OFBiz — partial (multi-org via `organizationPartyId` on
  `AcctgTransEntry`). SAP Group Reporting / S/4HANA. NetSuite Consolidation.
- **Substrate already there:** **the hard structural part — the rest is a
  reporting overlay.** `:entity` (ADR-031) gives per-entity scoped books;
  per-(entity,ledger,commodity) sum-to-zero means each subsidiary's books balance
  independently in one DB. `Money` + commodity handles FX. The multi-entity
  showcase (`doc/showcases/04_multi_entity_intercompany.clj`) already exercises
  intercompany postings. **What's missing:** an *elimination* layer — a
  consolidation `:ledger` that holds elimination entries (intercompany AR vs
  intercompany AP net to zero), a group-level reporting view that sums entities
  and applies eliminations, and currency-translation-adjustment posting logic.
- **Verdict: (a) new companion `kontor-consolidation` — MEDIUM priority**, OR a
  thin extension to the report engine. No new kernel primitive — eliminations
  ride the existing `:ledger` mechanism (an "elimination ledger" is just a
  `:ledger` row). The companion is mostly a reporting overlay + elimination-entry
  helpers.
- **Materiality: medium, and rising with multi-entity adoption.** A single-entity
  GmbH never hits this. A group with a parent + one subsidiary hits it at the
  first consolidated year-end. Roadmap "Out of scope for v1: Consolidations" — but
  ADR-031 + showcase 04 have already built the substrate, so this is now a small
  companion, not a project.

### 2.9 Treasury & cash management — **PARTIALLY MISSING, medium materiality**

One-line: cash positioning, bank-account register, cash-flow forecasting,
FX-exposure, payment runs, in-house banking.

- **Who has it:** OFBiz (`FinAccount`, `FinAccountTrans`, `GlReconciliation` —
  `accounting-entitymodel.xml:339,462,2377`). Tryton `account_payment` +
  `account_statement*` family. Odoo `account_payment` + bank-rec. SAP Treasury &
  Risk Management / Cash Management. NetSuite Cash 360.
- **Substrate already there:** **a lot.** `:bank-account` entity (ADR-039),
  `reconciliation.clj` (bank-line → transaction matching), `bank-*` companions
  (statement import), `aging.clj`, `payment-term.clj`, `:payment-application`
  (ADR-043). **What's missing:** a *cash-position* roll-up across bank accounts
  and a *cash-flow forecast*. The forecast is explicitly already a roadmap
  candidate — `kontor-forecast` (note 19, probabilistic cash-flow fan-chart).
  Payment runs (batch a set of due AP into one disbursement) are a thin
  `procurement`/`collections`-adjacent helper.
- **Verdict: (b)/(a) split.** The forecast half is the planned `kontor-forecast`
  companion (gated, fine). The cash-positioning + payment-run half is a small
  `kontor-treasury` companion — no new kernel primitive, it composes
  `:bank-account` + `:payment-application` + `aging`. **LOW-MEDIUM priority** —
  most of the value is already in the kernel + `bank-*` + `collections`.
- **Materiality: medium.** "How much cash do I have across 3 banks" is a real Q1
  question, but it's a query over data kontor already has, not a missing
  capability — closer to a documentation/helper gap than a companion gap.

### 2.10 Point of sale — **OUT OF SCOPE**

One-line: retail checkout, cash drawer, session/Z-report, offline-first terminal.

- **Who has it:** Odoo `point_of_sale`. Tryton `sale_point`. ERPNext (POS in
  selling). SAP Customer Checkout. NetSuite SuiteCommerce POS.
- **Verdict: (c) out-of-scope.** POS is a *terminal application* — offline-first
  UI, hardware integration (drawer, scanner, receipt printer), session
  management. That is squarely "UI / second runtime," forbidden by ADR-010. The
  *accounting* of a POS session (the Z-report → summary journal entry) is a
  `sales` + `invoice` posting the POS app emits. kontor is the ledger a POS app
  posts *into*, never the POS app.
- **Materiality: low as a kontor gap.** A retailer needs a POS, but the answer is
  "use a POS app, post sessions to kontor."

### 2.11 Field service / EAM / maintenance — **OUT OF SCOPE (mostly), low materiality**

One-line: work orders, service appointments, dispatch, equipment maintenance
schedules, spare-parts.

- **Who has it:** Odoo `industry_fsm` + `maintenance` (Enterprise). OFBiz
  `WorkEffort` + `FixedAssetMaint` (`workeffort-entitymodel.xml:503,530`). ERPNext
  `maintenance`. SAP PM / CS / FSM. NetSuite Field Service Management.
- **Substrate already there:** equipment ≡ `:asset` (the `asset` companion just
  shipped) used as an analytic dimension; maintenance schedule ≡ `:schedule`;
  billable service hours ≡ the project/timesheet path (§2.4). The *accounting* is
  covered.
- **Verdict: (c) out-of-scope, OR a thin v2 extension of `kontor-project`.** The
  dispatch/scheduling/mobile-technician surface is UI + optimization — commodity
  SaaS (ServiceTitan, Salesforce FS — note 10 §14). Note 10 §5 already reached
  this verdict. The maintenance-schedule-on-an-asset bit is small and could be a
  `kontor-asset` v2 addition if a consumer asks.
- **Materiality: low for kontor's target customers.**

### 2.12 Contract management (CLM) — **DEFERRED, low-medium materiality**

One-line: contract lifecycle — drafting, redline, counter-signature, renewal,
obligation tracking.

- **Who has it:** OFBiz `Agreement` family (`party-entitymodel.xml:64-532` —
  `Agreement`, `AgreementItem`, `AgreementTerm`, `AgreementRole`, `AgreementStatus`).
  Tryton — none native. DocuSign CLM / SAP Ariba Contracts are market leaders.
- **Lift source:** OFBiz `Agreement` + `AgreementItem` + `AgreementTerm`
  (`party-entitymodel.xml:64-446`) — Apache-2.0, liftable; a genuinely rich
  pricing/term model.
- **Substrate already there:** **~70% per note 10 §9 — the deepest of the
  "non-accounting" domains.** `:status-transition` is the contract state machine;
  `:audit-doc` (with content hash + URI) holds the signed PDF; the
  `:attestation` pattern (ADR-024) models counter-signature exactly like a
  clearance token; `:document-type` registry (ADR-020) registers `:contract`
  types. E-signature callbacks post a new `:attestation`.
- **Verdict: (a) `kontor-clm` companion — but DEFERRED**, gated on a consumer
  story (roadmap already lists it under Stage M's deferred heavy companions). No
  new kernel primitive. Blanket-agreement *pricing* (the AgreementTerm model)
  would be valuable to `sales`/`procurement`, so a *thin* `:agreement` entity
  might land earlier as a `sales` extension.
- **Materiality: low-medium.** Contracts exist from day one, but customers track
  them in DocuSign + a folder; the CLM-as-kontor-companion need shows up only for
  contract-heavy businesses.

### 2.13 Quality management — **OUT OF SCOPE, low materiality**

One-line: inspection plans, quality checks, non-conformance, CAPA.

- **Who has it:** Odoo `quality_control` (Enterprise). Tryton `quality`. ERPNext
  `quality_management`. SAP QM. NetSuite Quality Management.
- **Substrate already there:** nothing accounting-specific; a failed-inspection
  scrap posting is a `plan-stock-move` write.
- **Verdict: (c) out-of-scope for an accounting kernel.** QM is a manufacturing/
  operations concern; if kontor ever ships `kontor-manufacturing`, a quality
  sub-surface could ride along, but it has no GL substance of its own. Defer with
  manufacturing.
- **Materiality: low** for kontor's target customers.

### 2.14 Document management (DMS) — **OUT OF SCOPE (storage), partially covered (metadata)**

One-line: file storage, versioning, OCR, full-text search, retention.

- **Who has it:** Odoo `documents` (Enterprise). Tryton `document_incoming` +
  `document_incoming_ocr`. SAP DMS. NetSuite File Cabinet.
- **Substrate already there:** `:audit-doc` (content hash + URI) is the
  *metadata + linkage* layer — it deliberately leaves blob storage out of kernel
  scope (architecture.md: "the URI's storage is out of kernel scope").
  `:retention-policy` (ADR-050) + `:legal-hold` (ADR-049) cover the
  retention/hold lifecycle.
- **Verdict: (c) storage is out-of-scope** (S3 / filesystem / consumer app's job);
  the *accounting-document linkage* is already a kernel primitive. No companion
  needed — kontor already has the right amount of DMS: the pointer, not the blob.
- **Materiality: low as a gap** — the substrate decision is already correct.

### 2.15 Warranty / RMA — **MISSING, low-medium materiality**

One-line: return-merchandise authorization, warranty claims, repair-vs-replace,
warranty-reserve accounting.

- **Who has it:** OFBiz `ReturnHeader` / `ReturnItem` / `ReturnItemResponse` /
  `ReturnItemBilling` (`order-entitymodel.xml:2492,2559,2627,2733`). Odoo via
  `stock` returns + `repair`. Tryton `sale_complaint`. ERPNext (in support/stock).
- **Substrate already there:** `procurement` already shipped a full
  *return-to-vendor* flow (`:return` / `:return-item` / `:return-response` /
  `:return-item-billing` — ADR-042). The **customer-side** RMA is the mirror of
  that — the same OFBiz `ReturnHeader` model, `:order/type :sales` direction.
  Warranty-reserve accounting is a `:schedule`-driven accrual posting.
- **Verdict: (a) extend `kontor-sales` with a customer-RMA flow — LOW-MEDIUM
  priority.** No new kernel primitive; it is the structural mirror of
  procurement's RTV, and OFBiz `ReturnHeader` is one model for both directions
  (same as the Order discriminator pattern). Small.
- **Materiality: low-medium.** A product company hits customer returns in Q1; a
  services firm never does. Gated on a product-selling consumer story.

### 2.16 Commission management — **MISSING, low materiality**

One-line: sales-commission plans, accrual, payout, clawback.

- **Who has it:** Tryton `commission`, `commission_waiting`. Odoo `sale_commission`
  (via OCA / Enterprise). SAP Incentive & Commission Management. NetSuite
  Incentive Compensation.
- **Substrate already there:** commission accrual ≡ a `:schedule` or an
  event-driven posting (`DR Commission Expense / CR Commission Payable`) keyed off
  an `invoice`/`sales` event; `:analytic-distribution` tags the salesperson;
  `:side-effect-intent` could trigger the accrual when an invoice is paid.
- **Verdict: (a) thin `kontor-commission` companion OR a `sales` extension — LOW
  priority.** No new kernel primitive. The commission *plan* (rate tables, tiers,
  clawback rules) is companion data; the accrual is a posting. Small, gated.
- **Materiality: low** for the typical target customer; medium for sales-driven
  businesses. Defer.

### 2.17 Lease accounting (IFRS 16 / ASC 842) — **MISSING, medium materiality**

One-line: right-of-use asset + lease liability recognition, monthly unwind,
remeasurement, parallel GAAP/tax treatment.

- **Who has it:** Odoo (via `account_asset` + custom). SAP Contract & Lease
  Management / RE-FX. NetSuite Lease Accounting / `Fixed Assets` module.
  Tryton — none native.
- **Substrate already there:** **this is the showcase case for the existing
  kernel.** architecture.md explicitly names it: "IFRS-16 vs ASC-842 lease
  accounting … all ride this single mechanism" — the parallel `:ledger` (ADR-021).
  The ROU asset is an `:asset` (the `asset` companion shipped); the lease
  liability + monthly unwind is a `:schedule` (ADR-032). Remeasurement is a new
  `:asset-event` + a schedule revision.
- **Verdict: (a) `kontor-lease` companion — MEDIUM priority**, OR a sub-module of
  `kontor-asset`. No new kernel primitive — it is `:asset` + `:schedule` +
  parallel `:ledger` composed. Probably the cheapest "impressive" companion: the
  substrate was *designed* for it.
- **Materiality: medium.** Any GmbH with an office lease or company cars under
  IFRS reporting hits IFRS 16; US-LLC with GAAP reporting hits ASC 842. Real, and
  audit-visible — but most small companies use the practical-expedient exemption
  for short/low-value leases, so it is medium not high.

### 2.18 Grant / fund accounting — **OUT OF SCOPE, low materiality**

One-line: restricted-fund tracking, grant-budget compliance, fund-balance
reporting (non-profit / public-sector).

- **Who has it:** specialized (Blackbaud, Sage Intacct nonprofit). OFBiz/Odoo/
  Tryton — none native. SAP Public Sector / Funds Management. NetSuite for
  Nonprofits.
- **Substrate already there:** fund ≡ an `:analytic-plan` dimension (ADR-022);
  restricted-vs-unrestricted ≡ analytic accounts; grant budgets ≡ the §2.7 budget
  companion scoped by fund. The substrate covers it *by composition* with no new
  primitive.
- **Verdict: (c) out-of-scope as a dedicated companion** — it is the §2.7 budget
  companion + the analytic dimensions, applied to a non-profit chart. If a
  non-profit consumer appears, it's an `l10n`-style chart + budget config, not new
  code. kontor's target is for-profit DE-GmbH / US-LLC; fund accounting is a
  different customer.
- **Materiality: none** for the stated target customer.

### 2.19 Subscription billing & revenue recognition — **PLANNED (note: confirm priority)**

Already on the roadmap (Stage N `kontor-revrec`, Stage O `kontor-subscription`).
Listed here only to confirm: the substrate is `:schedule` (ADR-032) + the Stage L
`:payment-application` primitive + the `sales`/`invoice` spine. No new kernel
primitive. **HIGH materiality** for SaaS customers (deferred revenue is the single
most-audited SaaS line). The roadmap sequencing is correct; flagging that revrec
should not slip behind the lower-materiality companions above.

---

## 3. Ranked recommendation table

Priority = materiality for a DE-GmbH / US-LLC mid-market customer in their first
1-2 quarters × substrate-readiness (how much is a cheap compose vs net-new).

| Rank | Companion | Verdict | New kernel primitive? | Lift source (Apache-2.0) | Materiality | Notes |
|---|---|---|---|---|---|---|
| 1 | **kontor-inventory** | new companion, HIGH | none — costing kernel exists | OFBiz `Facility`+`InventoryItem`+`InventoryItemDetail` (`product-entitymodel.xml:996-2353`), `ShipmentReceipt` (`shipment-entitymodel.xml:389`) | **high** — any goods-selling customer, Q1 | the missing operational quantity ledger under the two spines that ship; unblocks mfg + POS-posting |
| 2 | **kontor-project** | new companion, HIGH | none — analytic substrate purpose-built | OFBiz `WorkEffort`+`Timesheet`+`WorkEffortBilling` (`workeffort-entitymodel.xml:42-631`) | **high** — services firms, Q1 | timesheet ≡ analytic-line (no new entity); roadmap Stage P — consider moving up |
| 3 | **kontor-expense** | new companion, HIGH | none | none (cleanroom, note 09 §5 shape) | **high** — anyone with employees, month 1 | cheapest of the top-5; ship entity shape + `ExpenseImporter` protocol |
| 4 | **kontor-revrec** + **kontor-subscription** | planned (Stage N/O) | none — `:schedule` + `:payment-application` | KillBill Apache-2.0 (subscription) | **high** for SaaS | already roadmapped; do not let it slip behind lower-materiality items |
| 5 | **kontor-hr** + **kontor-payroll-*** | new companion, MEDIUM | none — `PayrollProvider` boundary | OFBiz `Employment`+`EmplLeave` (`humanres-entitymodel.xml:330-460`) | medium — customer already runs DATEV/Lexware | roadmap Stage R; note 09 fully specs it; payroll *engine* stays out-of-scope |
| 6 | kontor-lease | new companion / asset sub-module, MEDIUM | none — `:asset`+`:schedule`+parallel `:ledger` | — | medium — IFRS 16 / ASC 842 | substrate was *designed* for this; cheap + impressive |
| 7 | kontor-budget | new companion, MEDIUM | none (encumbrance reuses `:ledger`) | OFBiz `Budget`+`BudgetItem` (`accounting-entitymodel.xml:48-315`) | medium — quarter 2-3 | budget-vs-actual is the report engine + a second column |
| 8 | kontor-consolidation | new companion / report overlay, MEDIUM | none — eliminations ride `:ledger` | — | medium — multi-entity groups | ADR-031 + showcase 04 already built the substrate |
| 9 | kontor-treasury | small companion, LOW-MED | none | OFBiz `FinAccount`+`FinAccountTrans` (`accounting-entitymodel.xml:339-525`) | medium — but mostly query over existing data | cash-positioning + payment-runs; forecast half is planned `kontor-forecast` |
| 10 | kontor-manufacturing | new companion, LOW-MED (deferred) | **yes** — `CostingProvider/compute-production-cost` | OFBiz `manufacturing` services (`services_bom.xml`, `services_mrp.xml`) | low for target customer | gated; depends on #1 inventory; roadmap already defers |
| 11 | customer-RMA (extend kontor-sales) | sales extension, LOW-MED | none | OFBiz `ReturnHeader` (`order-entitymodel.xml:2492-2766`) | low-med — product companies | structural mirror of procurement's shipped RTV flow |
| 12 | kontor-commission | thin companion / sales ext, LOW | none | Tryton `commission` (design-only, GPL) | low | accrual is a posting; plan is companion data |
| 13 | kontor-clm | companion, DEFERRED | none — ~70% covered by `:attestation`+`:audit-doc` | OFBiz `Agreement` (`party-entitymodel.xml:64-446`) | low-med | roadmap Stage M deferred heavy companion; blanket-agreement pricing could land in `sales` earlier |

### Correctly OUT OF SCOPE for an accounting kernel

| Domain | Why |
|---|---|
| **CRM** (leads/opportunities/pipeline) | does not touch the GL; conversion point already covered by `sales`; commodity SaaS (Salesforce/HubSpot/Pipedrive) pointed at `partner` master |
| **Point of sale** | a terminal *application* — offline-first UI + hardware integration; forbidden by ADR-010; kontor is the ledger a POS posts into |
| **Field service / dispatch** | dispatch/scheduling/mobile is UI + optimization; commodity SaaS (ServiceTitan); the accounting (billable hours, equipment) already covered by `asset` + project path |
| **Quality management** | no GL substance; rides along if `kontor-manufacturing` ever ships |
| **Document storage (blobs)** | `:audit-doc` already provides the metadata + linkage layer; blob storage is S3/filesystem/consumer-app job — the substrate decision is already correct |
| **Grant / fund accounting** | composes from `:analytic-plan` + the budget companion + a non-profit chart; a different customer (non-profit) than kontor's stated DE-GmbH / US-LLC target |
| **ATS / performance / LMS / OKRs / engagement / marketing automation** | per note 09 + note 10: "anything the GL doesn't audit is fair game for commodity SaaS" |

---

## 4. Key findings for the maintainer

1. **The two spines (O2C, P2P) plus MDM and fixed assets are done. The biggest
   single gap is `kontor-inventory`** — the *operational* quantity ledger. The
   *financial* inventory kernel (valuation books, layers, `CostingProvider`,
   `plan-stock-move`) was built in ADR-027-030, and `procurement` already drives
   receipts into it — but nothing tracks on-hand quantity, available-to-promise,
   or honors the `:inv-reservation` stub the `sales` ship-groups already
   reference. This is the gap a goods-selling customer hits first.

2. **Three of the top-5 need ZERO new kernel primitives** — inventory, project,
   expense all compose from `:valuation-*` / `:analytic-*` / `:schedule` /
   `build-transaction`. The kernel's anti-accretion contract is holding: the
   Stage H-M primitives were the right investments and the next companions cash
   them in. The *only* net-new kernel primitive in the whole analysis is one
   `CostingProvider` method (`compute-production-cost`) for the deferred
   manufacturing companion.

3. **`kontor-project` should arguably move up the roadmap.** It's Stage P today
   (after revrec, subscription). But kontor's likely early adopters skew services
   (beleg = contractor invoicing), the analytic substrate was purpose-built for
   timesheets, and "is this project profitable" is a Q1 question for any
   consulting GmbH / US professional-services LLC.

4. **`kontor-expense` is the cheapest high-value win** — note 09 §5 already specs
   the entity shape, the substrate is 100% there, and it's a month-1 need for any
   employer. Could ship in a fraction of a stage.

5. **`kontor-lease` and `kontor-consolidation` are now small companions, not
   projects** — the roadmap's "out of scope for v1" lines for consolidation and
   lease predate ADR-021 (parallel ledgers), ADR-031 (`:entity`), ADR-032
   (`:schedule`), and the `asset` companion. The substrate was *designed* for
   IFRS 16 / ASC 842 (architecture.md says so explicitly) and showcase 04 already
   exercises intercompany. These should be re-classified from "out of scope" to
   "medium-priority small companion."

6. **The out-of-scope list is principled and should stay firm.** CRM, POS, field
   service, quality, blob storage, fund accounting — each fails the test "does the
   GL audit this?" The kernel's job is to be the ledger those systems post *into*,
   reconciled through the `partner` master and the provider protocols. Resisting
   these is what keeps kontor a kernel and not a half-built Odoo.

# 10 — Business-OS companion projects: survey of Odoo, Tryton, SAP, Salesforce

Research note capturing the agent survey of the non-HR, non-pure-accounting business surface. The objective is to map what companion projects to kontor would need to cover and how each touches the kernel ADRs.

Date: 2026-05-12. Source: agent survey + Odoo/Tryton local read.

## Caveat on sources

The local Odoo install ships only Community modules — Enterprise-only addons (`helpdesk`, `industry_fsm`, `sign`, `documents`, `approvals`, `sale_subscription`, `sale_renting`, `account_consolidation`, `account_asset`, `hr_payroll`) are absent. Where cited, those rely on public Odoo Apps documentation. Tryton tree is complete on disk.

## 1. Sales lifecycle — Lead → Opportunity → Quote → Order → Fulfillment → Invoice → Payment → Revrec

Entity comparison across the four:

| Stage | Odoo | Tryton | SAP S/4HANA (SD) | Salesforce |
|---|---|---|---|---|
| Lead | `crm.lead` (state `lead`) | `sale.opportunity` (state `lead`) | Lead (doc type `LEAD`) | `Lead` sObject |
| Opportunity | `crm.lead` (`type='opportunity'`) | Same record, state `opportunity` | Opportunity (CRM-integrated) | `Opportunity` |
| Quote | `sale.order` (`state='draft'/'sent'`) | `sale.sale` (`state='quotation'`) | Quotation (doc type `QT`) | `Quote` |
| Order | `sale.order` (`state='sale'`) | `sale.sale` (`state='confirmed'/'processing'`) | Sales Order (doc type `OR`) | `Order` |
| Fulfillment | `stock.picking` | `stock.shipment.out` | Outbound delivery (LE-SHP) | n/a (integration) |
| Invoice | `account.move` (`move_type='out_invoice'`) | `account.invoice` | Billing doc (SD-BIL) | `Invoice` (Revenue Cloud) |
| Payment | `account.payment` | `account.payment` | FI clearing | platform tx |
| Revrec | `account.move.line.deferred_*` (premium) | `sale_invoice_defer` | RAR sub-module | Revenue Cloud |

### Design points

**Quote-to-order conversion** — Odoo collapses both into one `sale.order` row toggled by `state`. Tryton uses an explicit `Workflow` mixin with named transitions. SAP keeps separate document types with copy-control. Salesforce splits Quote and Order entirely.

**Multi-line mixed product/service** — all four allow lines of differing types. Stockable products post on shipment; services post on invoice; subscriptions post per schedule period. Odoo's `sale.order.line.invoice_status` is a 4-value enum; Tryton splits shipping and invoice status as separate selections (cleaner).

**Subscription / contract-based revenue** — Tryton has built-in `sale_subscription`. Odoo equivalent is Enterprise-only. SAP handles via BRIM (Subscription Billing). Salesforce Revenue Cloud (formerly CPQ+Billing). All implement the same conceptual model: a recurring schedule generates invoice lines on a clock, with proration at start/end/upgrade.

**Returns / credit notes** — Odoo creates `account.move` with `move_type='out_refund'` and `reversed_entry_id`. Tryton uses negative-quantity `account.invoice` with `type='out'` (+ `sale_amendment` for proper return workflow). SAP has returns order (doc type `RE`) → returns delivery → credit memo.

### Cross-dep with kontor

- Revenue posting via `kontor.posting/build-transaction`. The DR/CR shape on fulfillment vs invoice differs (anglo-saxon vs continental — Tryton has separate modules `account_stock_anglo_saxon` / `account_stock_continental`).
- Deferred revenue + contract assets need a `:revrec-schedule` entity (— this is part of the cross-cutting `:schedule` primitive surfaced as ADR-032 candidate).
- Multi-entity transnational sales hits **ADR-031** (`:entity` per-(entity, ledger, commodity) sum-to-zero).
- Analytic tagging — `:project`, `:cost-center`, `:campaign` per **ADR-022**.

## 2. CRM — the Salesforce stronghold

### Data model

Salesforce is the gold standard:

- **Account ↔ Contact** with `AccountContactRelation` many-to-many. Odoo collapses both into `res.partner.is_company` flag (common cause of bad analytics). Tryton has `party.party` + `party.contact_mechanism` + separate `party.relationship` (cleaner).
- **Lead → Opportunity conversion** — Salesforce's `convertLead` creates Account + Contact + Opportunity atomically.
- **Opportunity stage-driven probability** — Salesforce `StageName → Probability`. Odoo `crm.lead.stage_id` + `probability`. Tryton `sale.opportunity.state` + `probability`.
- **Activity** — Salesforce's `Task` and `Event` are first-class with `WhoId`/`WhatId` polymorphism. Odoo uses `mail.activity` mixin. Tryton uses `ChatMixin` chatter.
- **Campaign / attribution** — Salesforce `Campaign` + `CampaignMember`. Odoo's `utm` module (`utm.campaign`, `utm.medium`, `utm.source`). Tryton thin `marketing_campaign`.
- **Sales team / territory / quota** — Salesforce `Territory2Model` (rich many-to-many with rules). Odoo `crm.team` + `crm.team.member`. Tryton has only a `sales_team` tag.

### Salesforce platform affordances

- **Custom Objects** — declarative data modeling at runtime; auto-get REST API, page layouts, reports, permissions.
- **Custom Fields** — runtime alter table.
- **Apex** — server-side imperative Java-like with governor limits (max 100 SOQL per tx).
- **Triggers** — before/after insert/update/delete callbacks.
- **Flow / Process Builder / Workflow Rules** — declarative visual automation aimed at admins.
- **Validation Rules** — declarative formula-based constraints.
- **Reports & Dashboards** — drag-and-drop builder + pivot.
- **AppExchange** — marketplace + managed-package isolation + security review.
- **Multi-tenant ACL** — Org / Profile / Permission Set / Sharing Rule.

### Cross-dep with kontor

CRM mostly does *not* touch the GL. Lead/opportunity lifecycle stays outside accounting until conversion to order.

- Customer master reconciles with kontor's `:partner` namespace.
- Won-opportunity → quote → order — pure data, no posting until SO ships.

## 3. Procurement / Purchase-to-Pay

| Stage | Odoo | Tryton | SAP MM |
|---|---|---|---|
| Requisition | `purchase.requisition` | `purchase.request` + `purchase.request.quotation` | ME51N Purchase Requisition |
| RFQ | `purchase.order` (`state='draft'/'sent'`) | `purchase.request.quotation` | RFQ (doc type `AN`) |
| Purchase Order | `purchase.order` (`state='purchase'`) | `purchase.purchase` (`state='confirmed'`) | PO (doc type `NB`) |
| Receipt | `stock.picking` (incoming) | `stock.shipment.in` | Goods Receipt (MIGO / MB01) |
| Invoice | `account.move` (`move_type='in_invoice'`) | `account.invoice` (type `in`) | Logistics Invoice Verification (MIRO) |
| Payment | `account.payment` | `account.payment` | F-53 / APP |

### 3-way matching

The procurement world centers on this. Match PO line ↔ GR (goods received) ↔ Vendor invoice; only post AP if quantities/prices align within tolerance. AI/OCR auto-matching now claimed at 99%+ in vendor pitches; reality is ~85-90% with the rest needing human review.

Odoo's match engine: `purchase_bill_line_match.py`. Tryton handles via `purchase_amendment` + `purchase_invoice_line_standalone`.

### Procurement bits potentially underexplored

The initial survey covered the canonical lifecycle. Worth exploring depth in a future research pass:

- **Tolerance-band 3-way matching** — not exact match; allow ±N% variance per line/total
- **GR/IR-A clearing** — when invoice arrives *before* receipt
- **Punchout catalogs (cXML)** — Ariba/Coupa pattern for embedding vendor catalogs
- **Catalog buy vs spot buy** — pre-approved items vs ad-hoc requisition
- **Contract pricing / blanket orders** — negotiated rates that apply to specific vendors/items
- **Vendor performance management** — on-time delivery, quality scores, vendor scorecards
- **Vendor onboarding / KYV (Know-Your-Vendor)** — compliance, sanctions screening
- **Supplier portals** — vendor self-service for invoice status, PO confirmation
- **Procurement card (P-card) reconciliation** — corporate card statements matched to receipts/POs
- **Direct vs indirect procurement** — direct (resale/production) vs indirect (office, services)
- **Services procurement** — statement of work, milestone-based invoicing (vs goods)
- **Subcontractor / contingent labor** — Fieldglass-style; per-hour or per-deliverable
- **Spend categorization** — UNSPSC, NAICS taxonomies
- **Tail spend management** — long tail of small vendors
- **Sustainability/ESG metrics** — supplier carbon, diversity reporting
- **Drop shipping** — vendor ships directly to customer; no intermediate stock
- **Vendor consignment inventory** — vendor owns until consumed
- **VMI (vendor-managed inventory)** — vendor monitors + auto-replenishes
- **Returns to vendor (RTV)** — credit memo flow

Most of these are **module-level extensions**, not kernel concerns. The kernel ADRs already cover the deep parts:
- Receipt → stock layer via ADR-028, ADR-030
- Invoice clearance via ADR-018 (Italy/Mexico/Brazil)
- Multi-attestation for approval chains via ADR-024
- Document composition for cXML PunchOut via ADR-025

### Cross-dep with kontor

- Receipt → `:valuation-layer` via `plan-stock-move` (ADR-028, ADR-030)
- Invoice → AP posting via `kontor.posting`; needs 3-way match before triggering
- Clearance tokens (ADR-018) for SDI/CFDI/NF-e
- Vendor master attached to `:partner` entities

## 4. Manufacturing / Production

### Module surface

Odoo `mrp`: `mrp.bom`, `mrp.bom.line`, `mrp.production`, `mrp.workcenter`, `mrp.routing.workcenter`, `mrp.workorder`, `mrp.unbuild`, `mrp.scrap`.

Tryton: `production`, `production.bom`, `production.routing`, `production.work` (+ `production_outsourcing` for subcontracting).

SAP PP modules: `PP-MRP`, `PP-SFC` (shop floor control), `PP-PI` (process industries), `PP-PDC` (plant data collection). Salesforce Manufacturing Cloud is account-based selling for manufacturers — *not* actual production scheduling; SAP retains dominant share in discrete-mfg / process-mfg.

### Concepts

- BOM single + multi-level + phantom (explosion-only, no stock kept)
- Routing + work center (ordered operations on resources)
- Backflushing (consume raws at completion vs at start)
- Quality inspection (Odoo Enterprise `quality_control`; Tryton `quality`)
- Maintenance / asset uptime (Odoo `maintenance` Community-tier; CMMS extensions go further)

### Cross-dep with kontor

- Production consumes raws → `plan-stock-move` (ADR-030)
- Labor + overhead via analytic dimensions (ADR-022); standard cost vs actual variance is two postings
- Finished good capitalization → new `:valuation-layer` with cost = component layers + labor + applied OH; `CostingProvider` needs a `compute-production-cost` method
- Subcontracting → titling change on raws sent to subcontractor; `:valuation-book` (ADR-027) entries on "Subcontractor inventory" account

## 5. Service-after-sale / Field service / Helpdesk

### Domain coverage

- **Helpdesk** — Odoo Enterprise `helpdesk.ticket` (stages, SLA policies, team queues). Salesforce Service Cloud `Case`: Subject, Description, Priority, Status, Owner, Account, Contact, SuppliedEmail, EntitlementId. Per the 2026 Service Cloud features, Omni-Channel routing prioritizes by original-request-date (transferred cases keep their place); SLA milestones can be agentic (AI initial response + status drafts).
- **SLA tracking** — timestamp pairs (`first_response_at`, `sla_target_at`, `breached_at`) with policy lookups by priority/account-tier/contract.
- **Field service / dispatch** — Odoo Enterprise `industry_fsm` (builds on `project.task`). Salesforce Field Service: Service Appointment, Resource Absence, Work Order, Work Type, Service Territory, with optimization. SAP: Multiresource Scheduling within CS + Field Service Management.
- **Service contracts / warranties** — Odoo `helpdesk_account` (premium). Tryton `sale_complaint` + `sale_subscription`.
- **Spare-part inventory** — standard stock layer, tagged with `:vehicle`/`:equipment` analytic.
- **Knowledge base** — Salesforce `Knowledge__kav` (versioned, multilingual). Odoo Enterprise `knowledge`. Tryton: nothing native.

### Cross-dep with kontor

- Billable service hours → invoice (T&M pattern from §6)
- Warranty replacement vs paid repair — status flag decides whether parts post as warranty expense or rebill customer
- Equipment as analytic dimension (ADR-022)

## 6. Project / Professional services

| Entity | Odoo | Tryton |
|---|---|---|
| Project | `project.project` | `project.work` |
| Task / WBS | `project.task` (recursive) | `project.work` (recursive) |
| Milestone | `project.milestone` | `project.work.type='milestone'` |
| Resource | `resource.resource` + `hr.employee` | `company.employee` |
| Billing | `sale.order` via `sale_project` | `project_invoice` |

### Billing modes

Three canonical:

1. **Time and materials (T&M)** — hours × rate, billed monthly
2. **Fixed-fee with milestones** — invoice on milestone status `done`; revenue at milestone OR percent-complete
3. **Percent-complete revrec** — `revenue_recognized = total_contract × (cost_incurred_to_date / total_estimated_cost)`. ASC 606 / IFRS 15 prescribe it as input method for over-time recognition.

Tryton's `project_revenue` computes project revenue. Odoo `sale_timesheet` ties timesheets to `sale.order.line` for T&M.

### Cross-dep with kontor

- WIP postings — during project, costs flow to BS WIP account; at milestone/completion, WIP flips to COGS + revenue
- Revenue recognition over time emits posting per period from `:revrec-schedule` (the cross-cutting schedule primitive)
- Project as analytic dimension (ADR-022)
- Multi-currency contracts — `Money` + commodity tag handles natively

## 7. Marketing

Salesforce Marketing Cloud + Pardot ("Account Engagement") is the heavyweight: Email Studio, Journey Builder, Audience Studio, Advertising Studio, Mobile Studio. Architectural pain: Marketing Cloud is on its own tenant, not SF core platform.

Odoo `mass_mailing` + `marketing_automation`. Tryton `marketing_*` set is thin. SAP equivalent: Emarsys Customer Engagement.

### Cross-dep with kontor

Minimal accounting touch. Campaign costs (ad spend, vendor invoices) post as P&L expenses tagged with `:campaign` analytic dim. Attribution of revenue to campaign is a reporting concern, not posting.

**Commodity-SaaS pick**: Mailchimp / Brevo / SendGrid / HubSpot for the mail engine; attribute via UTM. Kernel just needs the `:campaign` plan registered.

## 8. E-commerce / Commerce

- **SAP Commerce Cloud** (formerly Hybris) — Java/Spring on Azure via SAP BTP. #1 B2B per Forrester. **SAP Hybris PCM EOL 2026-07-31** — migrations active right now.
- **Salesforce Commerce Cloud** — multi-tenant, AI-native, deeply tied to Service/Marketing Cloud.
- **Odoo `website_sale`** — integrated storefront over the same `sale.order` data model.
- **Tryton `web_shop`** — integrates with Shopify, Vue Storefront, custom storefront — *not* trying to be its own front-end.

### Architectural rule of thumb

Storefront must own:
- Catalog (product, variants, pricing, availability)
- Cart / checkout (stateless or session-based pre-order)
- Tax + shipping at checkout — this is the `TaxProvider` integration point (ADR-005)
- Order capture → fulfillment writes `:sale-order` entity

### Cross-dep with kontor

Storefront is *upstream* of the sale lifecycle. Tax at checkout calls `TaxProvider` (kernel-resident). Payment hold/auth is *not posted* (no money moved); capture is. Inventory reservation is a `:valuation-layer` reservation (deferred — see ADR-028 deferrals), not a consumption.

**Recommendation**: do not build a kontor-bundled storefront. Build adapters for Shopify, Stripe Checkout, Saleor. Treat them as TaxProvider-callers and order-emitters. Tryton pattern.

## 9. Document / Contract / Approval workflow

### Coverage

- **Document management (DMS)** — Odoo Enterprise `documents`. Tryton `document_incoming` + `document_incoming_ocr` for inbound specifically.
- **Contract lifecycle (CLM)** — DocuSign CLM is market leader. Salesforce CLM (formerly SpringCM, now Revenue Cloud). SAP Ariba Contracts (procurement) + SAP CLM (sales). Odoo/Tryton no first-class CLM.
- **E-signature** — DocuSign eSignature, Adobe Sign, HelloSign, OneSpan. Salesforce native limited. Odoo Enterprise `sign`.
- **Approval workflows** — Odoo Enterprise `approvals` (generic n-step). Salesforce Approval Processes (parallel + serial + delegation). SAP Workflow / Flexible Workflow.

### Cross-dep with kontor — the deepest coupling

- **ADR-018 — Clearance-token lifecycle** — Italy/Mexico/Brazil vendor invoice in `:pending-attestation` with `:posting/clearance-token` until government XML clears. Same pattern as contract waiting for counter-signature.
- **ADR-024 — Multi-attestation lifecycle** — multiple `:attestation` entities per transaction (CFO + auditor + tax-authority). Spend approval workflows are isomorphic.
- **ADR-020 — Document-type registry** — CLM module registers `:contract` doc-types distinct from `:invoice` / `:complemento`.
- **E-signature callbacks** — DocuSign signed-PDF hash + signer identity post as a new `:attestation` referencing contract entity.

This is the most accounting-adjacent of "non-accounting" domains — kernel covers ~70% of structural needs.

## 10. Fleet / Asset management

- **Vehicle fleet** — Odoo `fleet` (`fleet.vehicle`, `fleet.vehicle.log.services`, `fleet.vehicle.log.contract`, `fleet.vehicle.odometer`). Tryton `stock_fleet` for carrier fleet.
- **Fixed assets (depreciation)** — Tryton `account_asset` (GPL, read-only oracle). Odoo `account_asset` is Enterprise.
- **Asset maintenance schedules** — Odoo `maintenance` (CMMS-lite).
- **IoT integration** — SAP IoT / Plant Connectivity in heavy industry; Samsara / Fleetio in mid-market.

### Cross-dep with kontor

- Depreciation schedule = `:schedule`-shaped entity (the cross-cutting primitive — see ADR-032 candidate). Periodic depreciation posting hits depreciation expense (P&L) + accumulated depreciation (BS contra).
- Asset disposal — complex posting (remove cost, remove accumulated depr, recognize gain/loss). Pure kernel work.
- Vehicle as analytic dimension (ADR-022).

Kernel does heavy lifting; companion is thin.

## 11. Cross-cutting patterns surfaced

### Pattern 1 — `:schedule` entity (ADR-032 candidate)

Six unrelated companion projects need *the same* primitive: a recurring schedule that emits a posting per period.

- Fixed-asset depreciation
- Revenue recognition (ASC 606 / IFRS 15 over-time)
- Subscription billing (recurring invoice generation)
- Lease accounting (IFRS 16 / ASC 842)
- PTO accrual (from HR research note 09)
- Insurance premium amortization

Otherwise each would invent its own. **Single cleanest cross-cutting addition.**

### Pattern 2 — `:cost-center` analytic plan bootstrapped at kernel install

Every companion (HR cost-center on employee, project cost-center on timesheet, manufacturing work-center, asset cost-center, fleet vehicle) lands tags on the same analytic plan. Like ADR-021 primary ledger and ADR-027 primary valuation book — a single kernel-installed plan removes coordination overhead.

## 12. Build order for companion projects

Path A:

1. **ADR-032: `:schedule` entity + `:cost-center` plan bootstrap** — ~1 week. Unlocks asset, revrec, subscription, lease, PTO accrual.
2. **`kontor-partner`** — at `../kontor-partner`. VAT/EIN lookup, address verification, partner-type taxonomy. 1-2 weeks. Reference: Tryton `party` + `party_relationship`.
3. **`kontor-sales`** — `:sale-order` + `:sale-order-line` with state machine. 3-4 weeks. Reference: Tryton `sale` + Odoo `sale_order.py`.
4. **`kontor-procurement`** — `:purchase-order`, `:goods-receipt`, 3-way match. 2-3 weeks.
5. **`kontor-asset`** — fixed-asset depreciation with parallel ledgers (ADR-021 for US-GAAP vs IFRS books); schedule-driven (ADR-032). 2 weeks. Reference: Tryton `account_asset`.
6. **`kontor-revrec`** — ASC 606 / IFRS 15. The biggest market differentiator vs Beancount/GnuCash. 3 weeks.
7. **`kontor-subscription`** — rides on revrec; opens SaaS-customer segment. 2 weeks.
8. **`kontor-project`** — rides on revrec + sales; opens agency / consulting market. 2 weeks.
9. **`kontor-commerce-adapter`** — Shopify first, then Stripe Checkout. 1 week per integration.
10. **`kontor-hr` + `kontor-payroll-de-datev`** — see research note 09.

**Defer until concrete demand**: kontor-mfg, kontor-helpdesk, kontor-field-service, kontor-fleet, kontor-clm, kontor-marketing, kontor-crm.

## 13. Forks vs from-scratch

| Source | License | Recommendation |
|---|---|---|
| Odoo addons (Community) | LGPLv3 | **Do not lift**; read-only oracle per ADR-001 |
| Tryton modules | GPLv3 | **Do not lift**; GPLv3 contagion would force consumers to GPL |
| Beancount (already in `kontor.import.beancount`) | GPLv2 | OK — parser/dumper is plumbing, not core |
| Salesforce data model | proprietary | Read SOAP/REST WSDL/OpenAPI specs for shape |
| SAP S/4HANA | proprietary | Read help.sap.com docs for shape |

Cleanroom Clojure from spec. Tryton as shape-oracle preference (cleaner state machines, no `res.partner` polymorphism abuse). Salesforce data model as oracle for CRM specifically.

## 14. Commodity-SaaS picks (do NOT build)

| Domain | Vendor recommendation |
|---|---|
| CRM | Salesforce / HubSpot / Pipedrive |
| Marketing | Mailchimp / Brevo / SendGrid / HubSpot |
| ATS | Greenhouse / Lever / Workable / Personio Recruiting |
| Performance / 360 / OKRs | Lattice / 15Five / Workday Talent / SuccessFactors P&G |
| LMS | Cornerstone / Docebo / 360Learning |
| Helpdesk | Zendesk / Freshdesk |
| Field service | ServiceTitan / Salesforce FS |
| Fleet telematics | Samsara / Fleetio |
| E-signature backend | DocuSign / Adobe Sign |
| Engagement surveys | Culture Amp / Lattice |
| Compensation planning | Lattice / Carta |
| LMS | Cornerstone / Docebo / 360Learning |

The pattern: **anything the GL doesn't audit is fair game for commodity SaaS**.

## 15. Salesforce platform gap

Salesforce's distinguishing value is **admin-tier customizability** — admins, not developers, extend the system via Custom Objects + Flow + Validation Rules + Approval Processes.

The Clojure/datahike stack out-of-the-box gives developers radically more power but gives non-developer admins essentially nothing. The largest gap vs Salesforce for SMB adoption is the absence of an **admin-facing visual rule/workflow editor**.

This is a *simmis* opportunity — a Replicant frontend over kontor's invariant primitives (ADR-011) that lets an admin compose validation rules + workflow triggers without writing Clojure. Outside kontor itself; flag for the simmis roadmap.

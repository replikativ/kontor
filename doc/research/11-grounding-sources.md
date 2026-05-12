# 11 — License-compatible OSS, schemas, and reference material for companion projects

Research note capturing the agent survey of grounding material for the upcoming companion-project work (Path A from research note 10).

Date: 2026-05-12. Source: agent web research.

## License posture (recap)

All license calls are **for kontor** (EPL-1.0):

- **Compatible** — can be lifted/embedded without polluting EPL: Apache-2.0, MIT, BSD-2/3, EPL-1.0/2.0, CC0/Public Domain, OGL-UK, EUPL (where stated), most government open licences (IRS/US federal works).
- **Incompatible** — may read source but must not lift code: GPL, LGPL, AGPL, MPL-2.0 (file-level copyleft — usable as reference, dangerous to copy whole files), proprietary EULAs.

## Top 3 OSS to clone to `..` next

1. **Apache OFBiz** (Apache-2.0) — `https://github.com/apache/ofbiz-framework`. The single most license-clean enterprise ERP reference. Covers requisitioning, supplier selection, PO generation, approval, goods receipt, 3-way match. Five-step MRP→PO process documented at `https://cwiki.apache.org/confluence/display/OFBENDUSER/Create+a+Purchase+Order`. **The procurement / sales / asset / project oracle** to read instead of Tryton/Odoo. Code CAN be lifted.

2. **KillBill** (Apache-2.0) — `https://github.com/killbill/killbill`. Java; the most refined OSS subscription billing engine: account → subscription → plan → phase → price-list → bundles, with proration, catalogue versioning, dunning. The **catalogue model** (versioned plans + price lists across time) is the hardest part of subscription billing and worth careful study.

3. **Sylius** (MIT) — `https://github.com/Sylius/Sylius`. PHP/Symfony. Order state machine is the canonical worked example: `cart → addressed → shipping_selected → payment_selected → completed`, plus separate payment + shipment state machines. Docs: `https://docs.sylius.com/the-book/architecture/state-machine`.

## Top 3 standards to build tests against

1. **UBL 2.1 + Peppol BIS profiles** — royalty-free, OASIS-blessed, EU-mandated. Round-tripping `:transaction/*` + `:purchase-order/*` + `:invoice/*` through UBL is the litmus test of the data model. `https://docs.oasis-open.org/ubl/os-UBL-2.1/`, `https://docs.peppol.eu/`. Profiles: BIS 28 Ordering, BIS 30 Despatch Advice, BIS 42 Order Agreement, BIS 63 Invoice Response.

2. **IFRS 15 IE1–IE63 + ASC 606 Example 1–60** — every revenue-recognition corner case the standards-setters could think of. Encode as test fixtures in `kontor-revrec`. Covers variable consideration, significant financing component, contract modifications, principal-vs-agent, breakage, repurchase agreements, bill-and-hold, customer options, warranties, licences with right-of-use vs right-of-access. `https://www.ifrs.org/issued-standards/list-of-standards/ifrs-15-revenue-from-contracts-with-customers/`.

3. **IRS Pub 946 MACRS tables + German BMF AfA-Tabellen** — actual numerical depreciation tables we ship as l10n data. MACRS is public domain (US federal work); AfA-Tabellen is BMF open data. `https://www.irs.gov/pub/irs-pdf/p946.pdf`, `https://www.bundesfinanzministerium.de/Datenportal/Daten/offene-daten/steuern-zoelle/afa-tabellen/AFA-Tabellen.html`.

## Top 3 detailed documentation sources

1. **Apache OFBiz procurement Confluence pages** — concrete five-step requisition → PO → approval → receipt → match process. `https://cwiki.apache.org/confluence/display/OFBENDUSER/Create+a+Purchase+Order`. Generates ~20 integration tests for `kontor-procurement`.

2. **cXML User's Guide + Reference Guide** (Ariba) — `https://xml.cxml.org/current/cXMLUsersGuide.pdf`, `https://xml.cxml.org/current/cXMLReferenceGuide.pdf`. PunchOut + cross-application order semantics. Critical for SAP-Ariba interop. Generates the cXML import/export tests for `kontor-procurement`.

3. **IFRS 16 Leases standard + Example 13** — `https://www.ifrs.org/content/dam/ifrs/publications/pdf-standards/english/2021/issued/part-a/ifrs-16-leases.pdf`. Right-of-use asset measurement + lease liability remeasurement is the heaviest computation. Generates lease-accounting tests for `kontor-asset` (or a future `kontor-lease` module).

## By companion project

### `:schedule` primitive (ADR-032)

- **Chime** (EPL-1.0) — `https://github.com/jarohen/chime`. Data-driven schedule (lazy `java.time` instant sequence). Right *shape* — schedules as persisted data we can replay deterministically. Can lift.
- **Quartzite** (Apache-2.0) — `https://github.com/michaelklishin/quartzite`. Wraps Quartz Scheduler (Apache-2.0). Cron expressions, misfire policies, calendars. Heavier. Reference if calendar arithmetic (business days, fiscal calendars) is needed.

**Recommendation:** Chime's data model + Quartz's cron-expression parser if needed. The kontor model already stores schedules as datahike entities, so a missed window after a crash is a query, not a Quartz misfire policy.

### `kontor-partner`

- **pretix** (Apache-2.0) — `https://github.com/pretix/pretix`. Django ticketing with clean `Customer`/`Order`/`Organizer` model, REST API, GDPR-aware deletion. Shape reference for B2C partner records.
- **CRMint** (Apache-2.0) — `https://github.com/google-marketing-solutions/crmint`. Google's marketing/CRM pipeline. Job/task scheduling is well-engineered.
- **vCard 4.0 / RFC 6350** — `https://datatracker.ietf.org/doc/html/rfc6350`. IETF standards-track contact representation. Use as the *interchange shape* for `:partner/*`.
- **Peppol Business Card / ISO 6523** — `https://docs.peppol.eu/edelivery/codelists/`. Defines `iso6523-actorid-upis:<scheme>:<value>` for cross-border party identification.
- **schema.org/Organization, /Person, /Order** — `https://schema.org/Organization`. CC-licensed vocabulary; SEO/UI projection target.

**Recommendation:** model `:partner/*` to map cleanly to vCard 4.0 + Peppol Business Card. Treat schema.org as the SEO/UI projection.

### `kontor-sales`

- **Saleor** (BSD-3) — `https://github.com/saleor/saleor`. Python/GraphQL headless commerce. Strong order model: `Order → OrderLine → Fulfillment + Payment + Voucher + Discount`. Lift schema/test fixtures.
- **Sylius** (MIT) — see top-3 above. Order state machine is canonical.
- **Solidus** (BSD-3) — `https://github.com/solidusio/solidus`. Ruby/Rails. Mature, battle-tested `Spree::Order`.
- **Medusa.js** (MIT) — `https://github.com/medusajs/medusa`. Modern headless commerce; module boundaries (`cart`, `order`, `fulfillment`, `payment`) map naturally to companion modules.
- **Peppol BIS Order 3.3 / Order Response / Despatch Advice / Invoice Response** — royalty-free OASIS.
- **EN 16931** — European invoice semantic model (170 business terms BT-1..BT-170). Use as source-of-truth field list for `:invoice/*`.

**Sample fixtures:**
- **ConnectingEurope/eInvoicing-EN16931** — `https://github.com/ConnectingEurope/eInvoicing-EN16931`. Sample valid UBL + CII invoices.
- **Tradeshift/tradeshift-ubl-xsd** (Apache-2.0) — sample UBL XMLs.
- **Shopify webhook schemas** (gadget-inc, MIT) — `https://github.com/gadget-inc/shopify-webhook-schemas`.

### `kontor-procurement` (deep-dive)

**Code:**
- **Apache OFBiz** — see top-3.
- **OpenProcurement / Prozorro** (Apache-2.0) — `https://github.com/openprocurement`. Ukraine's national e-procurement; full tender lifecycle, talks OCDS natively.

**Schemas — all royalty-free:**

| Source | URL | Use |
|---|---|---|
| Peppol BIS Ordering 3.3 | `https://docs.peppol.eu/poacc/upgrade-3/profiles/28-ordering/` | Order + Order Response |
| Peppol BIS Order Agreement 3.0 | `https://docs.peppol.eu/poacc/upgrade-3/2025-Q4/profiles/42-orderagreement/` | Confirmed order = the contract |
| Peppol BIS Despatch Advice 3.1 | `https://docs.peppol.eu/poacc/upgrade-3/profiles/30-despatchadvice/` | ASN (shipment notice) |
| UBL 2.1 (full document set) | `https://docs.oasis-open.org/ubl/os-UBL-2.1/` | Foundational |
| UN/CEFACT Cross Industry Order | `https://unece.org/trade/uncefact/mainstandards` | Alternative syntax |
| cXML (Ariba) | `https://xml.cxml.org/current/cXMLUsersGuide.pdf` | PunchOut, supplier catalogue, PO/PO-ack — critical for SAP-Ariba interop |
| OAGi BODs | `https://oagi.org/` | OAGIS PO/Invoice/Shipment |
| X12 EDI 850/855/856/810/820 | `https://x12.org/examples` | US-style EDI |
| EDIFACT ORDERS/ORDRSP/DESADV/INVOIC | `https://service.unece.org/trade/untdid/` | International EDI |
| OCDS | `https://standard.open-contracting.org/` | Public-sector PO/award data |
| UNSPSC | `https://www.undp.org/unspsc` | Product/service classification |

**Sample documents / public data:**
- **SAM.gov Contract Opportunities API** (public domain US gov) — `https://open.gsa.gov/api/get-opportunities-public-api/`
- **TED (Tenders Electronic Daily)** — `https://ted.europa.eu/`. EU OJEU notices, EUPL/OGL-flavoured.
- **UK Contracts Finder** (OGL v3.0) — `https://www.contractsfinder.service.gov.uk/apidocumentation`
- ERICO + Insight EDI vendor sample PDFs

**Recommendation:** make `:purchase-order/*` and `:goods-receipt/*` round-trip both Peppol BIS Order/DespatchAdvice **and** cXML — together they cover ~95% of real B2B procurement traffic. OFBiz is the rare license-clean procurement reference; read it the way some teams read Odoo.

### `kontor-asset`

- **beancount-plugins (davidastephens)** (BSD-3) — `https://github.com/davidastephens/beancount-plugins`. Depreciation plugin with WDV + CRA half-year convention. Small, readable, liftable.
- **IRS Publication 946** (public domain) — MACRS Tables A-1..A-20. Bundle freely.
- **German BMF AfA-Tabellen** (open data) — AfA-Tabelle AV + branch-specific tables. Bundle into `kontor-l10n-de`.
- **HMRC Capital Allowances Manual** (OGL v3.0) — UK capital allowances (WDA 18%/14%, AIA, FYA).
- **IFRS 16 Leases** — standard text + Example 13 (post-2020 amendment). ROU asset + lease liability remeasurement.

**Recommendation:** kernel ships `DepreciationMethodProvider` protocol (analog to `TaxProvider`, ADR-005) + in-tree `StaticTableProvider` loading MACRS, AfA, default straight-line/DDB/SYD. l10n modules layer on jurisdiction-specific tables.

### `kontor-revrec`

- **No clean OSS engine exists.** Stripe / Zuora / Sage proprietary. Greenfield.
- **FASB ASC 606 ASU 2014-09 + 2016-12** PDFs publicly available.
- **IFRS 15** full text + IE1–IE63 illustrative examples.
- **AICPA Implementation Issue guides** — free PDFs.

**Recommendation:** implement ASC 606 five-step model as a pipeline. Transcribe IE1–IE63 + ASC 606 Examples 1–60 as fixtures. Output stream of postings via kernel posting API.

### `kontor-subscription`

- **KillBill** (Apache-2.0) — see top-3 above.
- **TM Forum APIs** (Apache-2.0) — TMF678 Customer Bill, TMF666 Account Management, TMF637 Product Inventory, TMF622 Product Ordering. Telco-flavoured but cleanly modelled JSON schemas.
- **Stripe Billing** (proprietary engine; public webhook + invoice/subscription JSON samples).

**Recommendation:** model after KillBill's account/subscription/catalogue trinity but in datahike — so catalogue versions are *queries*, not separate tables. Expose TMF678/TMF666-shaped read APIs for telco interop.

### `kontor-project`

- **Kanboard** (MIT) — `https://github.com/kanboard/kanboard`. Small project/task tool.
- **No clean Apache/BSD/MIT professional-services automation exists.** Redmine GPL, project-open[ GPL, OpenProject GPL. Read-only oracles.
- **Taiga** — backend MPL-2.0 (file-level copyleft; safe as dep, dangerous to copy whole files), frontend AGPL-3.0 (skip).
- **IFRS 15 §B14–B23 + Examples 24–27** — input vs output methods for over-time revenue recognition.

## Flag list — INCOMPATIBLE, do NOT lift code

| Project | License | Why tempting | Why not |
|---|---|---|---|
| Odoo | LGPLv3 + EULA on Enterprise | Most comprehensive ERP reference | LGPL contagion + EULA. Read only. |
| Tryton | GPLv3 | Excellent accounting reference | GPL propagates. Read only. |
| ERPNext / Frappe | GPLv3 | Big Python ERP | GPL. Read only. |
| Lago | AGPLv3 | Modern subscription billing | AGPL — worse than GPL. Skip. |
| HumHub | AGPLv3 | — | Skip. |
| Redmine, project-open, OpenProject | GPL | PM tooling | GPL. Read only. |
| Akuukis/beancount_interpolate | likely GPL | Amortisation | Verify; if GPL, do not lift. |
| Taiga frontend | AGPL-3.0 | Agile UI | Skip. |
| Stripe RevRec / Stripe Billing | proprietary | Best-in-class engines | Use only public webhook samples as test fixtures. |
| Zuora RevPro, Sage Intacct, NetSuite, Oracle Fusion, SAP MM/SD/RE-FX | proprietary | Industrial-grade | Read public docs; never lift. |

## Key insights for the kontor team

- The single highest-leverage move is **adopting UBL 2.1 + Peppol BIS as the kernel's canonical document interchange**. Every companion (sales, procurement, subscription invoicing) round-trips through it; every l10n module just maps local syntax onto the same semantic core. EN 16931 already did the convergence work; we ride it.
- **Apache OFBiz is, in license-cleanliness terms, an irreplaceable resource.** Read it before designing kontor-procurement or kontor-sales. We may even prefer it to Odoo as a reference oracle going forward.
- **KillBill is the same gift for kontor-subscription.** The catalogue-versioning model alone is worth careful study.
- The **ASC 606 / IFRS 15 illustrative examples are essentially a free test suite for kontor-revrec.** Transcribe — don't invent — the tests.
- All BMF / IRS / HMRC tax tables are government open data. Ship in l10n artifacts (`kontor-l10n-de`, `kontor-l10n-us`, `kontor-l10n-uk`) with attribution.

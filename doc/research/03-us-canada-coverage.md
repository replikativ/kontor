# Going North-American with `kontor`: a clear-eyed scoping report

## TL;DR

Canada is "Germany-hard" with a different shape: federal+provincial mechanics are well-documented, schemas are public, and a single-vendor accounting kernel can ship credibly. **The US is the cliff** — not because the accounting model is harder, but because sales tax is *the* product (~11,000+ jurisdictions, no federal authority, ~24 SST states + ~22 non-SST including CA/TX/NY/FL/IL, product-taxability matrices that vary per state). The rational sequence is **DE -> CA -> US**, with US shipped as "kernel-correct + pluggable rate provider" rather than a self-contained tax engine.

---

## 1. US sales tax: shape of the problem and OSS floor

The numerical shape is well-documented: 45 states + DC have a general sales tax, and post-Wayfair (2018) all 45 have economic-nexus laws covering remote sellers. Industry sources cite "11,000+ jurisdictions" — Texas alone has ~1,900 ([CPA Journal, 2025](https://www.cpajournal.com/2025/09/02/how-wayfairs-economic-nexus-has-redefined-business-tax-obligations/); [Avalara nexus guide](https://www.avalara.com/us/en/learn/guides/state-by-state-guide-economic-nexus-laws.html)). Thresholds vary on six axes (gross vs taxable, TPP vs services, marketplace inclusion, transaction count, prior vs rolling year). NY uniquely requires *both* $500k AND 100 transactions; CA/TX use $500k sales-only; IL dropped its 200-transaction threshold for 2026 ([Sales Tax Institute](https://www.salestaxinstitute.com/resources/economic-nexus-state-guide); [TaxCloud nexus chart](https://taxcloud.com/blog/sales-tax-nexus-by-state/)).

**SST standardization** ([streamlinedsalestax.org](https://www.streamlinedsalestax.org/)): 23 full member states + Tennessee as associate, as of August 2025. SST standardizes definitions (food, clothing, prepared food), publishes free **rate and boundary CSV files updated quarterly** ([SST Rate & Boundary Files](https://www.streamlinedsalestax.org/Shared-Pages/rate-and-boundary-files); also state-level mirrors at [NCDOR](https://www.ncdor.gov/taxes-forms/sales-and-use-tax/streamlined-sales-tax-information/sales-tax-rate-database), [Wisconsin DOR](https://www.revenue.wi.gov/Pages/SSTP/ratebound.aspx)). Critically, the big revenue-share states — **CA, TX, NY, FL, IL, PA, MA, VA, CO, AZ** — are NOT SST. So SST's free data covers maybe ~30-35% of the addressable market.

**Product taxability** is the second axis of cruelty: clothing taxed in 37 states, exempt in NJ/MN/PA, threshold-taxed in MA ($175) and NY ($110); groceries taxable vs prepared-food rules differ; HI and NM tax nearly all services while CA taxes almost none ([Sales Tax Institute on classification](https://www.salestaxinstitute.com/resources/product-classification-for-sales-tax-are-you-doing-it-right); [TaxJar product variance](https://www.taxjar.com/sales-tax/why-sales-tax-can-vary-from-product-to-product)). Add tax holidays, special districts (transit/stadium/tourism), origin-vs-destination sourcing (CA/TX/IL are mixed-sourcing), and marketplace facilitator carve-outs.

**OSS floor.** Real options:
- **TaxCloud** (CSP-certified): free calculation/filing/registration/audit-support in all 24 SST states; commercial for the rest. Has a documented [API](https://taxcloud.com/api/). Not OSS but the SST-state data flow is genuinely free.
- **Avalara** publishes a [free download of state rate tables](https://www.avalara.com/taxrates/en/download-tax-tables.html) and free rate-only API — but ToS restricts redistribution and the data is rate-only (no boundaries, no taxability).
- **GitHub OSS**: [mikesparr/us-sales-tax-rates](https://github.com/mikesparr/us-sales-tax-rates), [dirk/sales_tax](https://github.com/dirk/sales_tax) — both small, single-author, ZIP-keyed (which is *wrong* — sales tax is geo-keyed, ZIPs cross jurisdictions). No actively maintained OSS engine equivalent to Mustang.
- [salestaxhandbook.com](https://www.salestaxhandbook.com/data) sells CSV bulk data.

**Realistic minimum for v1**: ship the kernel with a `tax-provider` protocol; ingest SST CSVs for the 24 states; route the rest through a paid Avalara/TaxJar adapter. **TaxJar Professional is $99/mo entry, with API calls counted as 1/10 of an order** ([TaxJar pricing review](https://taxcloud.com/blog/taxjar-pricing-how-much-does-taxjar-cost/)); **Avalara is opaque, custom-quoted** ([Avalara pricing review](https://taxcloud.com/blog/avalara-pricing/)). Building our own engine that reaches Avalara parity is a multi-engineer-year effort and is the canonical reason accounting startups either limit to one state, restrict to SaaS-exempt states, or wrap a paid API. **Don't try to be Avalara.**

## 2. US chart of accounts and reporting

There is **no statutory US COA** like SKR03/04. AICPA publishes a [Financial Reporting Framework for SMEs](https://www.ifrs-gaap.com/standardized-chart-accounts) and structural guidance, but the de-facto SMB standard is the **QuickBooks Online default chart** (~6 income / 1 COGS / 20 expense main accounts + 66 sub-accounts, [Intuit](https://quickbooks.intuit.com/learn-support/en-us/help-article/chart-accounts/learn-chart-accounts-quickbooks-online/L2yc6KBob_US_en_US); [QBKAccounting](https://qbkaccounting.com/standard-chart-accounts-account-types/)). For Tryton-style ports, [pentandra/account_us](https://github.com/pentandra/account_us) exists but is self-described as alpha — useful as a reference, not as a drop-in. GnuCash ships US sample hierarchies via its [Account Hierarchy Template](https://wiki.gnucash.org/wiki/Account_Hierarchy_Template) wizard but they're skeletal.

**GAAP vs cash basis**: no federal law requires GAAP for private companies; **most US SMBs file cash-basis** ([Wegner CPAs](https://www.wegnercpas.com/interplay-between-method-of-accounting-and-1099-reporting/); [WCG](https://wcginc.com/kb/accounting-method/)). Implication: the kernel must support a cash-basis reporting view cleanly (it already does via posted-on-payment recognition rules), but GAAP-clean accrual is needed for any company with audit, lender, or 1099-K reconciliation needs.

**1099 tracking**: vendor-level "1099 status" flag + per-vendor YTD payment aggregation, output as the IRS file format. The current FIRE system (fixed-width ASCII per [Pub 1220](https://www.irs.gov/pub/irs-pdf/p1220.pdf)) is **shutting down Dec 31, 2026**, replaced by [IRIS](https://www.irs.gov/e-file-providers/iris-schemas-and-business-rules) which uses XML schemas — but you must hold an IRS Transmitter Control Code to even receive the schema. There's a small OSS reference: [sdj0/fire-1099](https://github.com/sdj0/fire-1099) (Python). 1099-NEC threshold rises from $600 to $2,000 in 2026.

**Sales tax filings** are 50 different return formats across 50 DORs. SST states accept the [Simplified Electronic Return (SER)](https://www.streamlinedsalestax.org/) — the only actual standardization. Non-SST states require state-portal upload or paid filing service. Plan: produce SER for SST states; PDF + CSV for the rest; punt actual filing to TaxCloud/TaxJar/Avalara.

## 3. Canada: GST/HST/PST/QST mechanics

Three coexisting systems, all relevant in one ledger ([Wikipedia](https://en.wikipedia.org/wiki/Sales_taxes_in_Canada); [PwC](https://taxsummaries.pwc.com/canada/corporate/other-taxes); [TaxTips 2025 rates](https://www.taxtips.ca/salestaxes/sales-tax-rates-2025.htm)):

- **HST provinces (ON 13%, NB/NL/PEI 15%, NS 14% as of Apr 2025)**: single combined rate, single CRA return, one tax line on the invoice. Invoices must NOT split federal/provincial.
- **GST + PST provinces (BC 5+7, SK 5+6, MB 5+7)**: two *separate* taxes, two filings, PST is **not a VAT** — non-recoverable, becomes cost of the input. Big data-model implication: PST collected is a liability *and* PST paid is an expense (no offset).
- **QC (5% GST + 9.975% QST)**: dual VAT, GST + QST both VATs but QST is administered by **Revenu Québec** which also collects GST in QC on CRA's behalf for resident registrants — separate filings.
- **AB + 3 territories**: GST only.

Data-model implication for our `tax`/`tax_repartition_line`: we need (a) a per-province tax composition that yields **one or two output tax lines** depending on type, (b) a "recoverable" flag distinguishing VAT (GST/HST/QST) from retail sales tax (PST/RST), and (c) per-jurisdiction registration/return aggregation (one CRA filing for GST/HST nationwide; separate provincial filings for BC/SK/MB and QC). This is a clean ~5 enum-state model — much smaller than the US matrix.

**E-filing**: CRA publishes [XML specs for information returns](https://www.canada.ca/en/revenue-agency/services/e-services/filing-information-returns-electronically-t4-t5-other-types-returns-overview/xml-specs.html) (T4, T5, etc.). GST/HST NETFILE is largely web-form-based; bulk XML filing via "GIFT" (GST/HST Internet File Transfer) exists for high-volume filers but specifications are CRA-distributed under TCC. No mature OSS Canadian filing library — likely first-party work, but small.

**SR&ED** (R&D credit, ~$3B/yr program — relevant for any tech SMB customer): kernel needs **project-coded posting** (sub-account or analytic dimension on labour, materials, contractor expenses) so reports can isolate eligible expenditures by project ([CRA filing requirements](https://www.canada.ca/en/revenue-agency/services/scientific-research-experimental-development-tax-incentive-program/filing-requirements-policy.html); [BKC ProHub guide](https://bkcprohub.com/sred-eligibility-compliance-guide-for-canadian-businesses/)). The proxy method (55% of eligible salaries as deemed overhead) is the dominant claim path, so detailed overhead allocation is *not* required — a reporting-time annotation layer plus a project dimension on transactions is enough. Time-tracking integration is out of accounting scope.

## 4. OSS assets and JVM/Clojure libraries

- **Beancount** has no canonical US chart, but a strong [community pattern](https://beancount.io/blog/2026/04/03/chart-of-accounts-what-it-is-how-to-set-one-up) of `Liabilities:SalesTax:<state>` accounts — useful reference for our jurisdictional liability model.
- **GnuCash** US/CA templates: thin, generic; not authoritative.
- **Tryton** [account_us](https://github.com/pentandra/account_us) — alpha, but a published structural reference.
- **TaxJar** was acquired by Stripe in 2021; its smart-calc engine was never fully OSS and is proprietary today. No salvageable Apache-2 successor.
- **JVM 1099 e-filing**: nothing usable. Only the Python [sdj0/fire-1099](https://github.com/sdj0/fire-1099). Schemas for the IRIS replacement require a TCC.
- **NACHA (US ACH) bank import**: [afrunt/jach](https://github.com/afrunt/jach) is a usable Java library; [moov-io/ach](https://github.com/moov-io/ach) is best-in-class but Go.
- **CAMT.053** ([Payments Canada V08 guideline](https://www.payments.ca/sites/default/files/Bank%20to%20customer%20statement%20V08%20%28camt.053.001.08%29%28pdf%29.pdf)) is officially adopted by Payments Canada and most large US banks via SWIFT — same parser code paths as our DE plan.
- **Canadian GST/HST OSS**: nothing of note; first-party work.

## 5. E-invoicing in NA

**No mandate in either country.** US has the [DBNAlliance](https://dddinvoices.com/learn/e-invoicing-usa-digital-business-networks-alliance), spun out of the Federal Reserve / Business Payments Coalition pilot — a Peppol-like 4-corner network that went live in 2024 and is voluntary; ~600 entities onboarded; no mandatory timeline ([PRNewswire launch](https://www.prnewswire.com/news-releases/digital-business-networks-alliance-launches-to-operate-us-e-invoicing-exchange-network-302029239.html)). California SB 1213 references in our brief don't appear in current legislative trackers — the bill number is the wrong handle; current US activity is voluntary state pilots, not statute. **Recommendation: skip e-invoicing for NA v1.** Bank-statement import (CAMT.053 + NACHA) is the only NA "e-document" worth shipping.

## 6. Strategic synthesis

**Canada vs Germany**: roughly equivalent effort, possibly slightly less. Federal+provincial mechanics are public and well-documented; the COA is a localization (no SKR-grade institution but QBO-style charts are widely accepted); CRA filing schemas are public. A "usable in Canada" tick is achievable in **~1.5-2x DE effort** (mostly: PST non-recoverability; QST split-administration; SR&ED project dimension; CRA filing adapters).

**US**: kernel itself is *easier* than DE (no SKR, no GoBD, no XRechnung) — the cliff is exclusively sales tax + the 50-state filing zoo. Realistic v1 architecture:
- Self-contained: GAAP/cash-basis posting, QBO-style default COA, 1099 vendor tracking, IRIS XML adapter (when TCC obtainable).
- **Pluggable** `tax-provider`: ship a free SST-CSV provider covering ~24 states; require Avalara/TaxJar adapter for the rest. Document this honestly. Don't bundle anyone's API key.
- **Cost cliff = sales tax**. Building our own engine to Avalara parity is multi-engineer-year work and a perpetual data-licensing chase. Wrap, don't compete.

**Recommended sequence: DE -> CA -> US.** Canada is mid-complexity, mostly first-party work, and proves the multi-jurisdiction tax model with a sane number of states. US then plugs into the same model with ~50 enums, an SST data feeder, and explicit paid-provider adapters. **DE -> US first** would force the sales-tax architecture before validating it on a tractable system, and would commit the project to either (a) shipping with a paid Avalara dep on day one, or (b) a much-too-narrow "SST-only" US story that excludes the largest revenue states.

**Regulatory landmines to flag explicitly:**
- Avalara/TaxJar API ToS prohibit redistributing rate data — every customer must hold their own API key.
- IRIS XML schemas require an IRS-issued TCC; we cannot redistribute them.
- US sales tax is collected in trust; misconfiguration creates personal liability for officers in many states. A "best-effort calculator" disclaimer is mandatory.
- SST CSV data is free but the state DORs reserve the right to deprecate; build for quarterly refresh cadence.

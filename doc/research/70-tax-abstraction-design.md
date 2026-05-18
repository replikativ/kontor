# 70 — Tax abstraction design

## TL;DR

- **The current `TaxProvider` is dead code.** `src/kontor/tax_provider.clj:78-82` returns literal `[]`; zero callers in `src/` or `test/`; the matching `:tax/* :tax-rep/* :tax-group/*` schema (`schema.clj:1244-1393`) is defined but un-instantiated. The contract that actually runs in production is `posting-builder`: a per-country function the kernel invokes from `kontor.document.invoice/send!` (`src/kontor/document/invoice.clj:205`).
- **"Tax provider" conflates four concerns that have different consumers and different lifecycles.** Rate determination (what tax applies), posting expansion (GL postings for that tax), reporting (filings + boxes), and clearance/attestation (NF-e, IRN, fapiao, CFDI submission flows). The 2-protocol split in the maintainer's preliminary read collapses two of those four; the others still need their own seams.
- **The right abstraction is three protocols + one pure-data shape**, not one. `TaxRateProvider` determines rates; the in-between `TaxFacts` map is data; `TaxPostingBuilder` materializes GL postings from `TaxFacts`; `TaxReportAggregator` is already in place as `kontor.report` (`:engine :tax-tags`) and stays orthogonal. Clearance is a fourth axis already factored out via `:attestation/*` (ADR-018, ADR-024) — kept out of this design.
- **`TaxFacts` is the load-bearing artifact.** It must carry: line-base amount + commodity, jurisdiction (country + subdivision + place-of-supply), per-component rate items with kind (output / input / withholding / reverse-charge / pre-collection / surcharge / cess / compound), and provenance (provider-id, rate-source, statute citation when available). The shape has to express IN component-split (CGST/SGST/IGST), BR cascade (ICMS-by-inside, ICMS-ST, PIS/COFINS minus ICMS-destacado, DIFAL, FCP), CN surcharges on net VAT, MX retenciones, AU GST + PAYG-W, and DE reverse-charge — all of which exist in our l10n today.
- **`TaxPostingBuilder` stays per-country.** That's where SKR04 / NCM-CST / GSTN account routing lives. The eight existing `posting-builder` / `compute-return` modules **can keep most of their code**; what changes is they become two functions instead of one (rate → facts; facts → postings) with a thin adapter for the existing call sites.
- **`TaxRateProvider` is where Avalara / TaxJar / SST-CSV / static-EDN plug in.** ADR-005's "we never bundle keys" survives unchanged. The provider returns `TaxFacts`, not postings — so an external service's contract never needs to know SKR04 / NCM-CST / GSTN codes.
- **The BR NFe + IN IRN + MX CFDI flows do not break this split.** They consume the same `TaxFacts` shape on the way to their clearance envelopes (per-item `<ICMS00>`, `<ICMSUFDest>`, `<Traslado>`, GST component blocks). Today they each independently re-derive what `TaxFacts` would have given them; the refactor lets them share.

---

## 1. What kontor's l10n modules do today (per-module survey)

There are **eleven** l10n modules in `modules/l10n-*`. The tax-relevant code splits cleanly into two layers:

- **A**: rate constants + per-line compute helpers (the "rate determination" side)
- **B**: report definitions over `:engine :tax-tags` (the "filing" side)

A third candidate layer — **GL posting expansion** — exists in exactly one module (`l10n-de/invoice.clj` `posting-builder`) at production quality. Other modules expect the *generic* `kontor.invoice.posting/post-to-ledger!` (`modules/invoice/src/kontor/invoice/posting.clj:294`) to do the GL work using account-type defaults; tax-line postings are not currently materialized per-country.

### DE — `modules/l10n-de/`

- **`invoice.clj:69-129`** — `posting-builder`. Takes a pulled `:invoice`, **buckets lines by `:invoice-line/vat-rate`**, looks up `rev-account-by-rate {19.0M "4400", 7.0M "4300", 0.0M "4200"}` and `vat-account-by-rate {19.0M "3801", 7.0M "3806"}`, builds two postings per bucket (revenue, VAT). No partner/fiscal-position dispatch; reverse-charge (4125 / 4120) is mentioned in the docstring but not implemented.
- **`ustva.clj:32-101`** — UStVA monthly return. 8 line definitions, each `{:engine :tax-tags :tags [:ust-XX] :sign :inflow}`. `compute` sums the lines and emits `:ustva/zahllast = ust-19 + ust-7 - vorsteuer`.
- **`chart.clj`, `bs.clj`, `pnl.clj`, `eur.clj`, `closing.clj`, `datev.clj`** — chart-of-accounts seeds + financial-statement reports + DATEV CSV export. No tax logic.

Classification: **Full tax flow** for VAT 19/7/0 on a domestic sale. Partial on input-VAT (UStVA line 66 reads `:ust-66`-tagged purchase postings, but the *purchase-side* posting-builder doesn't exist in `l10n-de` — purchase tax-lines arrive via the generic bridge with manually-tagged accounts). No reverse-charge implementation. No EU intra-community-supplies posting-builder.

### CN — `modules/l10n-cn/`

- **`vat.clj:108-267`** — Full return computation. Three load-bearing innovations beyond the DE pattern:
  1. **Per-rate revenue tagging at source**: revenue accounts `5001.13 / 5001.9 / 5001.6 / 5001.0` carry per-rate tags; output-VAT is reconstructed at report time as `revenue × rate`. The output-VAT account itself (`2221.01.01`) is a single MOF-canonical bucket, NOT split per rate (`vat.clj:21-31`).
  2. **Surcharge stack on net VAT**: `umct / education-surcharge / local-education-surcharge` (`vat.clj:79-103`) — three contributions that ride on positive net VAT (zero on refund). Location-tier (`:municipal | :county | :other`) governs UMCT rate.
  3. **Small-scale preferential rate** with effective-dated statute citation (Cai Shui [2023] 19, expiry 2027-12-31; threshold rule Cai Shui [2023] 1 separately) — `vat.clj:60-77`.
- **`fapiao.clj`** — fapiao issuance attestation flow (ADR-018 attestation slot).
- **`chart.clj`** — MOF chart with rate-tagged revenue accounts.

Classification: **Full tax flow** for VAT and a clear *partial-on-reporting* surcharges story. No posting-builder; revenue postings are tagged by the chart, so the *generic* `kontor.invoice.posting` bridge supplies the GL postings, the tags on those accounts feed `vat.clj` at filing time.

### CA — `modules/l10n-ca/`

- **`gst_hst.clj:1-244`** — Full GST/HST + ITC computation aligned with CRA form GST34-2. 15 lines, all derived from kernel tags (`:ca-cra-line-101`, `-103`, `-108`) — same `:engine :tax-tags` pattern as DE/CN/FR/AT/JP/AU.
- **`returns.clj`** — Multi-province return orchestration (CRA / Revenu Québec / per-province PST).
- **`y2024/`** subdir — T1/T2125/S3/S4/S8/S9/S11/BC428 individual + corporate income-tax filings. **Not VAT/sales-tax** — corporate income tax. Different beast: postings flow to CRA-prescribed boxes via an `:account-tag` registry, same `:tax-tags` engine.
- **`xml/`** subdir — T4/T5/T5018/T619 information slips. Reporting only.
- **`pdf.clj`, `noa.clj`** — paper-form fillable PDF + Notice-of-Assessment parsing.

Classification: **Full reporting** for GST/HST and income tax. **No posting-builder** — same as CN; the chart's per-account tags do the heavy lifting at filing time. No QST (Revenu Québec, separate authority) coverage of its own form yet, despite the architectural readiness.

### BR — `modules/l10n-br/`

- **`taxes.clj:1-433`** — **The architectural stress test**. Five+ legacy taxes (ICMS, IPI, PIS, COFINS, ISS) and the post-2026 dual-VAT (CBS, IBS, IS). Carries:
  - State macro-region sets (`taxes.clj:35-56`) — south-southeast vs N/NE/MW, with ES exception documented.
  - `icms-intrastate-rates` map (`taxes.clj:62-90`) — per-state modal rate, statute-cited.
  - `icms-interstate-rate` fn (`taxes.clj:92-123`) — encodes CONFAZ Res. SF 22/1989 and the mandatory 4% (Res. SF 13/2012) for imported-content goods.
  - `compute-icms-by-inside-base` (`taxes.clj:206-214`) — Brazil's "cálculo por dentro" — ICMS base *includes* IPI in some scenarios (the canonical compound-tax example).
  - `difal-applies?` + `difal-due` (`taxes.clj:230-310`) — DIFAL routing for EC 87/2015 / LC 190/2022, with buyer-type × purpose dispatch.
  - `MvaProvider` **protocol** (`taxes.clj:328-345`) — *pre-existing precedent* for the "user brings their own data source" pattern. ICMS-ST MVA tables ship via customer-supplied implementations of this protocol (Sovos, Avalara LATAM, hand-curated). The `StaticMvaProvider` is the default.
  - `icms-st` (`taxes.clj:353-381`) — ST base = `(base + IPI + freight + insurance) × (1 + MVA)`; `st-due = base-ST × rate-dest − icms-normal`.
  - `fcp-amount` (`taxes.clj:392-400`) — Fundo de Combate à Pobreza per-state add-on.
  - `compute-pis-cofins-base` (`taxes.clj:402-432`) — STF Tema 69: ICMS-destacado excluded from PIS/COFINS base.
- **`cst.clj`** — CST (Código de Situação Tributária) reference tables (`icms-orig / icms-cst / icms-csosn / ipi-cst / pis-cst / cofins-cst`). Each CST drives a *different XML element group* (`<ICMS00>` vs `<ICMS10>` vs ...) in NF-e emission.
- **`nfe.clj`** — NF-e 4.0 XML emitter, CST-dispatched. Per-tax sub-emitters (`emit-icms`, `emit-icms-st-extra`, `emit-icms-uf-dest`, `emit-fcp`, `emit-ipi`, `emit-pis`, `emit-cofins`). Per the file's own P0 note (`nfe.clj:32-44`): CBS/IBS/IS NT-2025.002 groups still missing.
- **`sped.clj`** — SPED block emitters (federal digital bookkeeping).
- **`identifiers.clj`** — CNPJ / CPF / IE / NCM validators.
- **`chart.clj`** — chart-of-accounts seed.

Classification: **Full tax flow at the rate-determination layer** + **full clearance-side emitter**. No `posting-builder` — but BR's NF-e workflow doesn't go through `kontor.document.invoice/send!` today; it composes its own envelope. **The posting side is the gap**: nothing in `l10n-br` writes the `:posting` rows that materialize ICMS / IPI / PIS / COFINS / ISS into the GL. Production filers would have to drive the generic bridge with hand-tagged accounts.

### IN — `modules/l10n-in/`

- **`taxes.clj:1-173`** — GST engine. Effective-dated slabs (pre vs post 2025-09-22 GST 2.0; `taxes.clj:47-78`). `dispatch-supply` (`taxes.clj:103-120`) returns `:intra-state | :inter-state | :ut-supply` from (supplier-state, POS-state, ut-without-legislature?). `component-split` (`taxes.clj:122-135`) yields `{:cgst h/2 :sgst h/2}` or `{:igst h}` or `{:cgst h/2 :utgst h/2}`. `compute-tax` (`taxes.clj:137-164`) returns `{:components {kw → Money} :cess Money|nil :total Money}` — *already very close to a TaxFacts shape*.
- **`irn.clj`** — IRN (Invoice Reference Number) SHA-256 + NIC IRP JSON payload builder.
- **`ewb.clj`** — e-way bill payload.
- **`identifiers.clj`** — GSTIN / PAN validators.
- **`states.clj`** — 37-state table with GSTN codes + Ladakh + 96/97 pseudo-codes.

Classification: **Full tax flow at rate + components** for GST. TDS (withholding by buyer) and TCS (collection by seller on certain sales) are **not implemented** — they're called out in the country survey (research 09) but no code today. No posting-builder; component postings are presumed to feed the generic bridge with three account routes (CGST payable, SGST payable, IGST payable) tagged for the eventual GSTR filing.

### US — `modules/l10n-us/`

- **`sales_tax.clj:1-83`** — Per-state filing reports for CA / TX / NY / WA / FL + Denver, CO. Each is a single-line `{:engine :tax-tags :tags [:us-XX-state-line-1]}` report. Authority field on each (`:us-ca-cdtfa`, `:us-tx-cpa`, etc.) so a multi-state filer can iterate.
- **`chart.clj`** — minimal US chart.

Classification: **Filing only**, single line per state. **No rate determination, no posting-builder, no nexus logic, no economic-thresholds logic, no exemption-certificate machinery.** This is the canonical "the rate determination *has* to come from outside" case — ADR-005's reason for the protocol existed.

### FR — `modules/l10n-fr/`

- **`ca3.clj:1-99`** — TVA CA3 monthly return (Cerfa 3310-CA3). 11 lines + auto-liquidation handling for `06-tva` (intra-community reverse-charge). Same `:engine :tax-tags` pattern. Notes encaissements vs débits (cash-basis vs accrual) as a `:tax/exigibility` selector.
- **`chart.clj`** — FR chart seed.

Classification: **Filing only**. No `posting-builder`. Cash-basis (`tax/exigibility :on-payment`) is acknowledged in the file but the kernel doesn't implement it (see "open questions" §7).

### AT — `modules/l10n-at/`

- **`uva.clj:1-96`** — UVA monthly return. 10 fields (022/006/029 for 20/13/10% sales; 057 reverse-charge for construction; 011 intra-community supplies; 066 input-VAT; 021 §6 exempt). Same shape as DE UStVA — explicitly noted in the file (`uva.clj:26-28`).
- **`chart.clj`** — AT chart seed.

Classification: **Filing only**. Same pattern as DE for filing; no `posting-builder`.

### JP — `modules/l10n-jp/`

- **`consumption_tax.clj:1-163`** — JCT return. Standard 10% + reduced 8%; the three zero-tax categories (`non-taxable / export-exempt / out-of-scope`) materialized as separate tag lines.
- **`invoice.clj:1-89`** — QIS (Qualified Invoice System) registration-number validation (`T` + 13 digits) and required-field checklist. No XML / clearance.
- **`peppol_pint.clj`** — Peppol PINT-JP envelope (e-invoicing transport).
- **`chart.clj`** — JP chart seed.

Classification: **Filing + invoice-validation**. No `posting-builder`.

### MX — `modules/l10n-mx/`

- **`cfdi.clj:1-270`** — **CFDI 4.0 e-invoice XML emitter**. Per-line `<Concepto>` with nested `<Impuestos><Traslados/><Retenciones/>` blocks. Critical observation (`cfdi.clj:94-132`): each `<Traslado>` / `<Retencion>` carries `Base / Impuesto / TipoFactor / TasaOCuota / Importe` — *exactly the `TaxFacts` shape we want*. The MX emitter today receives this data via the input map's `:impuestos` key; the caller is responsible for computing it.
- **`identifiers.clj`** — RFC / CURP validators.

Classification: **Clearance emitter only**. No rate determination, no filing report, no posting-builder. The retención (withholding) split is *modeled in the XML emitter today* but not in any rate-determination code.

### AU — `modules/l10n-au/`

- **`gst.clj:1-192`** — BAS computation. 10 labels (G1 / G2 / G3 / G4 / G10 / G11 / 1A / 1B / W1 / W2). Single 10% GST rate. Simpler-BAS vs Full-BAS mode switch.
- **`peppol_pint.clj`** — Peppol PINT-A/NZ envelope.
- **`chart.clj`** — AU chart seed.

Classification: **Filing only**. No `posting-builder`. PAYG-W (W1/W2) is *payroll withholding*, lives in the BAS labels but isn't sourced from a payroll engine that exists in kontor today — relies on hand-tagged postings.

### Cross-module summary

| Module | Rate determination | Posting expansion | Filing report | Clearance emitter |
|---|---|---|---|---|
| **l10n-de** | inline in `posting-builder` | Yes (DE-only) | Yes (UStVA) | (none; Mustang in einvoice-de) |
| **l10n-at** | (none) | (none) | Yes (UVA) | (none) |
| **l10n-fr** | (none) | (none) | Yes (CA3) | (none) |
| **l10n-cn** | helpers in `vat.clj` + chart tags | (none — chart-driven) | Yes (VAT + surcharges) | fapiao (attestation) |
| **l10n-jp** | (rate constants) | (none) | Yes (JCT) | invoice validation |
| **l10n-au** | (rate constants) | (none) | Yes (BAS) | (Peppol envelope) |
| **l10n-ca** | (none — chart-driven) | (none) | Yes (GST34-2 + income-tax) | (Peppol envelope) |
| **l10n-us** | (none — out of scope by design) | (none) | Yes (per-state) | (none) |
| **l10n-in** | Full (`taxes.clj`: slabs + dispatch + components) | (none) | (none yet) | IRN + EWB payloads |
| **l10n-br** | Full (`taxes.clj`: ICMS matrix + DIFAL + ICMS-ST + FCP + PIS/COFINS) + protocol precedent (`MvaProvider`) | (none) | (none yet — SPED block) | NF-e CST-dispatched |
| **l10n-mx** | (none) | (none) | (none yet) | CFDI 4.0 XML |

Two observations the table makes obvious:

1. **The posting-expansion column has one entry.** This is the gap. Every other country relies on the generic `kontor.invoice.posting/post-to-ledger!` bridge using `:invoice-line/gl-account-type` and the kernel `account-type-direction` table — which produces only **line postings + a single contra (AR/AP)**. *No tax lines are materialized* unless the chart's per-account tags happen to encode them implicitly (the CN trick).
2. **The clearance column is independent.** NF-e, CFDI, IRN, fapiao, Peppol — these are envelope emitters that consume the same tax facts. They don't need a different protocol; they need the same data.

---

## 2. Jurisdictional realities (per-country, ranked by complexity)

### US — 11,000+ jurisdictions, the canonical "rate-from-outside" case

No federal sales tax. **Every state with sales tax is its own authority** (45 states + DC + 38,000+ local jurisdictions when home-rule cities are counted). Rates change weekly. **Wayfair-era economic nexus** (South Dakota v. Wayfair, 2018) means a seller without physical presence owes sales tax in any state where their gross receipts or transaction count exceeds the state's threshold ($100K / 200 transactions is the common pattern; some states only one). **Marketplace facilitator** laws shift collection responsibility from the seller to a marketplace operator (Amazon, eBay, Etsy) — relevant because the *seller's* return still reports the gross sale but the *marketplace's* return reports the tax collected.

**Streamlined Sales Tax (SST)** — 24 member states publish free quarterly CSV rate files (boundary tables, taxability matrices). Non-SST states require either subscription rate-services (Avalara, TaxJar, TaxCloud) or hand-curated tables that drift.

**Origin vs destination sourcing** — most states are destination-sourced (rate is the buyer's address); a few (TX, CA for in-state local, others) are origin-sourced. Shipping is taxable in some states, not in others, depending on whether separately stated, whether the underlying goods are taxable, and whether the carrier is the seller's agent.

**Exemption certificates** — partner × jurisdiction × category. A resale-exempt customer in TX is *not* exempt in CA without a separate CA certificate.

**Sales-tax holidays** — date-bounded exemptions on specific item categories (e.g., FL hurricane-prep weekend, TX back-to-school). Effective-dated rate windows.

**Use tax** — buyer self-assessment for tax the seller didn't collect. A return obligation even with zero sales-tax collection.

**Context the rate decision needs**: ship-from, ship-to (street precision), order date, line-item taxability category (often by Avalara's "tax code" — a 5-7-char categorization), customer-exemption-state-per-jurisdiction, marketplace-facilitator flag.

**Posting shape**: For *recoverable* US sales tax — never; US sales tax is always non-recoverable from the seller's side (it's a true pass-through). Two postings per state: AR up by gross, revenue down by net, sales-tax-payable-per-state down by tax. The state liability accumulates per-authority, settled monthly/quarterly/annually depending on volume.

### BR — five+ taxes simultaneously, NF-e clearance + transition to IBS/CBS

The architectural stress test, well-covered in `modules/l10n-br/taxes.clj`. Key realities not yet code:

- **NCM-driven everything**: rate, base reductions, ST applicability, IPI bucket, FCP applicability — all keyed on NCM (8-digit product code). A complete production rate table is ~10,000+ entries × per-state variation. Sovos / Avalara LATAM are the standard data sources.
- **2026-2033 transition** is *not* a flag-day cutover. From 2026-01, NF-e MUST carry CBS+IBS at 0.9% + 0.1% (testing rates), compensable against PIS/COFINS — the kernel must emit two parallel tax stacks on the same invoice for years.
- **NF-e clearance is synchronous**: invoice is not legal until SEFAZ issues `cStat=100` and the 44-digit access key. Kontor's ADR-018 attestation slot covers this. The rate decision must complete *before* the envelope is built; recomputing it after SEFAZ acceptance is forbidden by the cross-reference seal.
- **CST drives XML schema branch** (`l10n-br/cst.clj`). A line tagged `ICMS CST 60` ("ICMS cobrado anteriormente por ST") goes through `<ICMS60>` and does NOT emit an output-VAT amount on this seller's NF-e — but the *original* substitute seller already emitted it with `<ICMS10>`. This is *not* something a generic tax engine can guess; it requires per-product, per-supply-chain knowledge.
- **Withholding ("retenção na fonte")**: PIS, COFINS, CSLL, IR withholding by certain buyer types (federal entities, financial institutions, large taxpayers). Modeled in NF-e via `<retTrib>` (currently P2 gap in `nfe.clj`).

### IN — GST + place-of-supply + e-invoice clearance + withholding

GST headline rate splits into CGST+SGST (intra-state), IGST (inter-state), or CGST+UTGST (UT without legislature). Determined by **place-of-supply** rules — for goods, generally ship-to; for services, eleven different sub-rules depending on service type (transportation, telecom, advertising, online, real estate, …). The `:transaction/place-of-supply` kernel attr (ADR-023) is the input; `l10n-in.taxes/dispatch-supply` is the decision.

**HSN (goods) / SAC (services) codes** drive rate slabs. Effective-dated registry post-2025-09-22 (GST 2.0; `l10n-in/taxes.clj:47-78`) — `slabs-effective-on` resolves the right slab map.

**IRN (Invoice Reference Number)** is mandatory above a turnover threshold (currently INR 5 crore; trending downward annually). Pre-submission to NIC IRP; SHA-256 hash of `supplier-GSTIN_doc-no_FY_doc-type` (l10n-in/irn.clj:61-74). E-way bill is a separate clearance for goods movement above INR 50,000.

**TDS (Tax Deducted at Source)** — buyer-side withholding on specified payments (professional fees @ 10%, contractor @ 1-2%, rent @ 10%, …). Negative tax-line on the supplier's invoice from the supplier's perspective; the buyer remits the withheld amount to the government quarterly via TDS returns (Form 26Q, 24Q). **TCS** is the seller-side analogue on specified sales (motor vehicles > INR 10L, scrap, foreign-remittance > INR 7L).

### CN — VAT + surcharges + special vs regular fapiao

Standard 13% / reduced 9% / services 6% / small-scale 3% (currently 1% preferential). **General-taxpayer** vs **small-scale-taxpayer** distinction is structural — small-scale cannot issue *special* VAT fapiao (the kind that lets the buyer claim input credit) without applying for it, and even then can only issue *regular* fapiao to most buyers. Modeled by `l10n-cn/fapiao.clj` attestation flow.

**Three surcharges ride on positive net VAT**: UMCT (7/5/1% by location), education surcharge (3%), local education surcharge (2%). Already in `l10n-cn/vat.clj:79-103`. The 留抵退税 (input-credit refund) reduces the *next period's* surcharge base — currently a FIXME in `vat.clj:225-238`.

**Regional differences** — Beijing / Shanghai / Shenzhen have distinct e-fapiao rollouts, varying preferential policies for certain industries. The kernel exposes location-tier as an enum (`:municipal | :county | :other`); finer-grain regional rules would need additional context.

### CA — federal GST + provincial PST/QST + combined HST

Federal GST 5% in non-HST provinces. HST 13-15% in NB / NL / NS / ON / PE (combined federal+provincial, single rate, both filed via CRA GST34-2). PST 7% in BC + SK + MB (separate provincial authority, separate return). QST 9.975% in QC (filed to Revenu Québec, not CRA).

**The `:tax/authority` attribute** (`schema.clj:1316-1327`) is critical: a single Canadian SMB can file GST/HST to CRA (line 103 collects the federal 5% slice in BC, the combined 13% in ON), PST to BC Ministry of Finance, and QST to Revenu Québec, all in the same period. The kernel groups filings by `:tax/authority`. `l10n-ca/returns.clj` orchestrates.

**Zero-rated vs exempt** — zero-rated supplies (basic groceries, prescription drugs, exports) allow ITC recovery; exempt supplies (financial services, residential rent) do not. Material posting-side distinction.

### DE — VAT 19/7/0 + intra-community reverse charge

The simplest of the VAT regimes covered. Domestic 19% standard / 7% reduced / 0% specific exemptions. Reverse-charge for B2B EU intra-community supplies (§13b UStG, plus construction §13b Abs.2 Nr.4, plus a half-dozen specific sectors). Each reverse-charge case requires the **buyer** to post both an input-VAT line and an offsetting output-VAT line of the same amount — net zero cash-effect, full reporting impact (UStVA line 21, line 81, line 66 cross-references).

**VAT-ID validation** via VIES (EU portal) is *required* to qualify the intra-community supply as zero-rated — a §4 Nr.1b UStG condition. Without a valid buyer VAT-ID at supply time, the seller charges domestic VAT.

**Innergemeinschaftliche Lieferung** (intra-community supply) — UStVA line 41. The seller posts revenue to account 4125 (Erlöse §4 Nr.1b) with appropriate tags; no output-VAT collected.

### FR / AT — DE-shaped

FR: 20/10/5.5/2.1% TVA, intra-EU auto-liquidation analogous to DE Reverse-Charge. CA3 monthly form. **Cash-basis vs accrual** (encaissements vs débits) is per-tax via `:tax/exigibility :on-payment | :on-invoice` (`schema.clj:1298-1301`) — schema-ready but unimplemented in the engine.

AT: 20/13/10/0% USt, §19/1a Bauleistungen reverse-charge specifically called out in UVA line 057. Otherwise mirrors DE.

### JP — consumption tax + qualified invoice gating

10% standard / 8% reduced (food + non-alc beverages + twice-weekly news). Three zero-tax categories with **different input-VAT-credit consequences**: non-taxable (no credit), export-exempt (full credit), out-of-scope (not even a supply).

**Qualified Invoice System (since 2023-10-01)** — buyer can claim input credit only against invoices from NTA-registered issuers carrying a `T` + 13-digit registration number. Transitional 80% / 50% / 0% credits from non-registered suppliers through 2029-09 (already in `l10n-jp/invoice.clj`).

### MX — IVA + IEPS + retenciones + CFDI clearance

IVA 16% / reduced 8% (border-zone municipalities only) / 0% / exempt. **IEPS** — federal excise on alcohol, tobacco, motor fuels, sugary drinks, telecom; rates from 3% to 160%.

**Retención** (withholding by buyer) on certain payments — IVA retention on services from individuals (2/3 of the IVA), ISR retention on professional services (10%). CFDI's `<Retencion>` blocks carry these.

**CFDI 4.0** is the canonical e-invoice — must be signed with the seller's CSD digital cert and *timbrado* (stamped) by an authorized PAC before legal validity. ADR-017 carves the signing/PAC out of the kernel; `l10n-mx/cfdi.clj` ships the pure XML.

### AU — single-rate GST + BAS reporting

GST 10% flat. BAS labels distinguish exports (G2), other GST-free (G3), input-taxed (G4 — financial supplies, residential rent), capital purchases (G10), non-capital (G11). PAYG-W (W1/W2) labels are payroll withholding; in scope for the *report* even though the kernel doesn't have a payroll engine. **Simpler BAS** mode for turnover < AUD 10M lodges only G1/1A/1B/1H.

---

## 3. Reference systems

### Odoo `account.tax` (AGPL-3 — read for patterns only)

`addons/account/models/account_tax.py` is ~5,000 lines. Key fields (lines 80-205):

- `name`, `country_id`, `company_id`, `active`, `sequence`
- `type_tax_use` ∈ `{'sale','purchase','none','adjustment'}`
- `tax_scope` ∈ `{'service','consu',null}` (line-applicability filter)
- `amount_type` ∈ `{'group','fixed','percent','division'}` — `division` is the "price already includes this tax" inverse percentage (CN-style).
- `amount` (the rate, 4 decimal precision)
- `price_include`, `price_include_override`, `company_price_include` — whether quoted prices are tax-inclusive (matters for some EU consumer contexts).
- `include_base_amount` — does this tax's amount join the base of *subsequent* (higher-sequence) taxes? The compound switch.
- `is_base_affected` — the inverse: does this tax accept being affected by *prior* compounds?
- `analytic` — flag for cost-allocation routing.
- `tax_group_id` — Odoo's tax-group concept; aligns with our `:tax/tax-group` (`schema.clj:1285-1290`).
- `tax_exigibility` ∈ `{'on_invoice','on_payment'}` + `cash_basis_transition_account_id`.
- **`invoice_repartition_line_ids`** + **`refund_repartition_line_ids`** — separate distribution recipes for invoices vs credit notes.
- `fiscal_position_ids` — links to fiscal-position records that swap this tax for another.
- `original_tax_ids` / `replacing_tax_ids` — explicit tax-replacement chains used by fiscal positions.
- `children_tax_ids` — for `amount_type='group'`.
- `has_negative_factor` — flag set when any repartition line has factor < 0 (used for reverse-charge `+100 / -100` patterns).
- `invoice_legal_notes` — translatable HTML to print on the invoice for tax-specific legal mentions.

**The repartition line** (`account.tax.repartition.line` — separate model, not shown above) is the key insight Odoo encoded that pure-rate systems miss:

- `factor_percent` (typically 100, but split for partial-deductibility or for reverse-charge ±100)
- `repartition_type` ∈ `{'base','tax'}` (does this line cover the base amount or the tax amount?)
- `document_type` ∈ `{'invoice','refund'}`
- `account_id` (where the posting lands, nullable for tag-only base distributions)
- `tag_ids` — *the report-box tags*, exactly our `:posting/account-tags` mechanism

Our `:tax-rep/*` schema attrs (`schema.clj:1329-1373`) already mirror this structure faithfully. Odoo's design is the prior art; we just haven't wired it.

**What we learn from Odoo and explicitly do differently:**

1. *Keep* the `tax + repartition` core (we already did).
2. *Drop* `amount_type :group` as a tax kind — instead use a *list of taxes* at the call site. Group-of-taxes is a UI convenience in Odoo; on the protocol it just complicates `compute`.
3. *Drop* `price_include` as a tax attribute. Pricing is a UI concern; the engine should always receive the unambiguous net base. Beleg / simmis can de-gross at input.
4. *Reframe* fiscal positions as **routing inside `TaxRateProvider`**, not as a per-tax field. Odoo's fiscal-position-as-tax-attribute is structurally awkward; a single fiscal-position decision (`(decide-fiscal-position partner)`) at the *front* of rate determination is cleaner.

### Avalara AvaTax

`POST /api/v2/transactions/create` accepts:

```json
{
  "type": "SalesInvoice",
  "companyCode": "DEFAULT",
  "date": "2026-05-17",
  "customerCode": "ABC",
  "addresses": {"shipFrom": {...}, "shipTo": {...}},
  "lines": [{
    "number": "1",
    "amount": 100.00,
    "quantity": 1,
    "taxCode": "P0000000",      // taxability category
    "itemCode": "SKU-001",
    "exemptionNo": "RESALE-CA-1234",  // optional cert
    "customerUsageType": "G"     // gov-purchase, etc.
  }]
}
```

Returns per-line per-jurisdiction breakdown (`taxLineId / jurisCode / jurisName / jurisType ∈ {State,County,City,Special} / rate / tax`). A single $100 sale to a buyer in Aurora, CO can produce **four tax lines**: CO state (2.9%), Adams County (0.75%), Aurora city (3.75%), RTD special district (1%) — totaling 8.4%.

**The contract Avalara uses is exactly the shape `TaxFacts` needs**: line × N components, each with jurisdiction + rate + amount + authority identifier. Plus the per-line `taxability category` (Avalara's `taxCode`) which the seller maps from product data.

### TaxJar `/v2/taxes`

Similar shape, simpler API. Single call returns `{order_total_amount, shipping, taxable_amount, amount_to_collect, rate, breakdown: { state_amount, county_amount, city_amount, special_district_amount }}`. Less granular than Avalara on the per-jurisdiction breakdown — typically used by smaller sellers.

### Streamlined Sales Tax (SST)

24-state CSV consortium. The "Rates and Boundaries" download is six files per state per quarter: state boundaries (street addresses → jurisdiction), tax rates, taxability matrix, product definitions, holiday windows, exemption-certificate categories. **Free** public data; the SST agreement license obliges accurate registration but no ToS-restricted redistribution clause. Suitable to bundle as data with `l10n-us` — the only US tax dataset we can ship without a CMS dependency.

### EU VAT OSS / IOSS

One-Stop-Shop scheme (since 2021-07-01) for B2C cross-border supplies inside the EU. A DE seller selling to consumers in FR / IT / ES can register once and file a single OSS return per quarter, listing per-destination-country breakdowns. The rate applied is the *consumer's country's* rate, not the seller's. **The €10,000 annual threshold** below which the seller can apply their own country's rate matters for very small sellers.

OSS is *not* about a different rate engine; it's a *filing* aggregation. The kernel needs per-tx destination-country tagging (which it has via the partner's `:partner/country-code`) and a quarterly cross-country aggregator (which would be a new report-engine variant).

### Common contract synthesized from all of the above

Inputs are nearly invariant: *(jurisdiction context, line items with category + amount + commodity, partner with tax-id + cert-status, transaction date)*.

Outputs are nearly invariant: *per-line, per-component {kind, jurisdiction, rate, base, amount, authority, recoverable?, reporting-tags}*.

This is the `TaxFacts` shape.

---

## 4. Tax complications worth surfacing

### Reverse charge (EU + AU + CN export-of-services edge cases)

The *buyer* records both an input-VAT and an offsetting output-VAT line. Net cash impact zero; reporting impact full. In Odoo, modeled with a tax having two repartition lines on the tax side at factor `+100%` / `-100%` to different accounts (input-VAT receivable and output-VAT payable). Our `:tax-rep/factor-percent` (`schema.clj:1350-1357`) supports this; the repartition-line array per tax materializes as multiple postings.

In `TaxFacts`: a component item carries `:kind :reverse-charge` and `:produces-both? true`. The posting-builder is expected to emit the two postings.

### Withholding (TDS in IN, retenciones in MX, US W-9 backup withholding)

The buyer reduces the cash paid to the supplier by the withheld amount, owing it to the authority. From the *supplier's* invoice perspective: a *negative* line item (TDS deducted) reduces the AR. From the *buyer's* perspective: an account-payable reduction matched by a tax-payable accrual.

In `TaxFacts`: `:kind :withholding`, `:direction :reduces-payable | :reduces-receivable`, `:authority`. Posting-side: a `:posting/account` on the withholding-tax-payable side (a liability), counterposted to AR/AP.

The MX CFDI emitter already models this with `<Retenciones>` separate from `<Traslados>` (`l10n-mx/cfdi.clj:123-132`).

### Compound and tax-on-tax (BR ICMS-by-inside, QC PST-on-GST historical, ICMS-ST)

`compute-icms-by-inside-base` in `l10n-br/taxes.clj:206-214` is the load-bearing example: ICMS base = `net + IPI`. **The TaxFacts compute order matters** — `:sequence` field, processed low → high; each tax's `:include-base-amount` flag determines whether subsequent taxes (with `:is-base-affected? true`) re-base.

ICMS-ST goes further: `Base_ST = (base + IPI + freight + insurance) × (1 + MVA)`. The MVA factor multiplies the *combined* base. The pattern is: a per-tax `base-recipe` that names the contributors. Expressing this purely with sequence + flags is rigid; a small DSL for the base recipe (`{:add [:net :ipi :freight :insurance] :gross-up-by :mva}`) is more honest.

### Exemption certificates (US-centric; analogues in DE Steuerbefreiungsbescheinigung)

Partner × jurisdiction × certificate-category. A resale certificate is jurisdiction-bound. A pure-data model fits: `:partner/tax-exemption [{:jurisdiction "US-CA" :category :resale :certificate-no "..." :valid-from ... :valid-until ...}]`. The `TaxRateProvider` reads it and returns zero-rated components with `:exempt-reason :resale-certificate :certificate-id "..."` so the auditor can trace.

### Fiscal positions (Odoo concept, schema-ready in kontor: `schema.clj:1209-1228`)

A rule-set that *swaps* the default tax for another based on partner profile. Examples:

- DE seller + EU-VAT-registered buyer in another EU country → swap "DE-VAT-19" for "DE-INTRA-COMM-REVERSE-CHARGE-19".
- DE seller + non-EU buyer → swap "DE-VAT-19" for "DE-EXPORT-0".
- BR seller in SP + buyer in BA → apply 7% interstate ICMS instead of 18% SP intra-state.

Two design options:

1. **Fiscal position as a tax-attribute** (Odoo's choice via `fiscal_position_ids` many-to-many). Pro: every tax knows its own remapping rules. Con: the `TaxRateProvider` has to walk every tax's FP list to find a match — N×M.
2. **Fiscal position as a routing decision at the top of `TaxRateProvider`** (our recommendation). The provider first decides the fiscal position via `(decide-fiscal-position partner transaction-context)`, then runs rate determination *in that fiscal context*. Tax replacement is a context-dependent lookup, not a per-tax attribute. Pro: single decision point, easier to reason about, easier to test. Con: replaces an existing schema convention.

We can support both: keep the `:fiscal-position/*` schema for storage + introspection, but the *engine flow* is the routing-decision model.

### Use-tax (US — buyer self-assesses sales tax the seller didn't collect)

A *purchase* posting that adds an *output-VAT-equivalent* tax-payable line. Same shape as reverse-charge mechanically. Different audit story (the buyer is the assessment-responsible party). `TaxFacts` `:kind :use-tax` flag; the posting-builder produces the self-assessment lines.

### Rounding regimes

DE / EU general: HALF-EVEN (banker's rounding) at the *per-document* level (UStG §16; cumulative line sum, then round once at total). Some retail invoices round per-line.

US: HALF-UP per the IRS; sales-tax compute is usually per-line then summed.

JP: HALF-EVEN at the per-rate-aggregate level (JCT explicit rule), per-line allowed historically.

AU: HALF-UP per-line is more common per the `l10n-au/gst.clj:38-42` note; we currently use HALF-EVEN.

**Conclusion**: rounding strategy is a per-tax (or per-jurisdiction) property, not a global. The `TaxFacts` shape carries `:rounding-mode` and `:rounding-scope ∈ {:per-line, :per-rate, :per-document}` per component.

### Currency conversion

Cross-border invoices: invoice currency may differ from seller's functional currency or from the reporting currency. EU rule (Directive 2006/112 Art. 91): for VAT, use the ECB reference rate on the supply date OR the customs-published rate. BR rule for import VAT: PTAX rate on the day before the supply.

The `TaxFacts` shape stays in the *invoice* currency; conversion to functional/reporting currency is a separate concern handled by `kontor.commodity`/`kontor.money` at posting time. The provider must surface the rate used (for audit) but should not itself perform conversion.

---

## 5. Proposed abstraction

### Three protocols + one data shape

#### `TaxFacts` (data, not a protocol)

```clojure
;; Per-line tax decision result. Pure data; nREPL-printable;
;; serializable (so consumer apps can cache, log, replay).
{:tax-facts/line-id          <opaque caller-supplied tag>
 :tax-facts/base             {:amount BigDecimal :commodity keyword}
 :tax-facts/jurisdiction
   {:country-code "DE"          ; ISO 3166-1 alpha-2
    :subdivision  "BY"          ; optional sub-national (state/province/UF)
    :place-of-supply "BY"       ; optional further routing (IN POS, BR DIFAL)
    :supplier-locale  "BY"      ; optional (IN: supplier-state for dispatch)
    :ship-from        "..."     ; optional (US street precision)
    :ship-to          "..."     ; optional}

 :tax-facts/components        ; ordered vector of component decisions
 [{:component/sequence 1
   :component/kind     :output-vat   ; see kinds below
   :component/tax-code "DE-VAT-19"   ; → :tax/code lookup
   :component/authority :de-bzst     ; → :tax/authority
   :component/rate     0.19M
   :component/base     {:amount 100.00M :commodity :EUR}
   :component/amount   {:amount  19.00M :commodity :EUR}
   :component/recoverable? false     ; collector side; buyer is true
   :component/reporting-tags [:ust-81-ust]      ; :account-tag refs
   :component/include-in-subsequent-base? false ; compound flag
   :component/base-affected-by         #{}      ; sequence #s this base reads
   :component/rounding {:mode :half-even :scope :per-document}
   :component/exempt?  false
   :component/exempt-reason nil      ; e.g. :resale-cert :export :small-scale
   :component/provenance
     {:provider-id    :static-table
      :rule-cited     "§12 Abs.1 UStG"
      :rate-effective-from #inst "2007-01-01"
      :computed-at    #inst "2026-05-17T..."}}
  ; ... more components ...
  ]
 :tax-facts/totals
   {:gross   {:amount 119.00M :commodity :EUR}    ; base + sum(non-reducing components)
    :net     {:amount 100.00M :commodity :EUR}
    :tax     {:amount  19.00M :commodity :EUR}
    :withheld {:amount  0.00M :commodity :EUR}}}
```

**Component kinds** (exhaustive enum):

- `:output-vat` — recoverable output (seller collects, buyer can credit on input side)
- `:input-vat` — recoverable input (buyer-side mirror)
- `:sales-tax` — non-recoverable retail tax (US sales tax, CA PST/RST)
- `:reverse-charge` — produces both sides on the *buyer's* books (zero cash; full reporting)
- `:withholding` — buyer reduces payment, owes authority (TDS, MX retención, US backup)
- `:pre-collection` — substitution / ICMS-ST / TCS (collector pays on behalf of downstream)
- `:surcharge` — rides on net VAT (CN UMCT/edu/local-edu)
- `:cess` — per-item add-on (IN compensation cess)
- `:duty` — federal excise / IPI / IEPS / Australian LCT/WET
- `:fee` — fixed-amount levy (rare; recycling fees, eco-tax)

#### Protocol 1: `TaxRateProvider`

```clojure
(defprotocol TaxRateProvider
  "Decides what taxes apply to a transaction line. Returns pure
   TaxFacts data — no postings, no chart-of-accounts knowledge."

  (provider-id [this]
    "Keyword identifier for audit logs and tests.
     Examples: :static-table, :sst-csv, :avalara, :taxjar,
     :avalara-latam, :l10n-de-static, :l10n-in-static.")

  (supports?
    [this context]
    "True iff this provider claims to handle the given context.
     Used by the dispatching ChainedProvider. Context fields:
       :country-code, :subdivision, :tax-use ∈ #{:sale :purchase},
       :line-categories #{...}.
     A static-table provider returns true for its country; an
     Avalara provider returns true for any US line; SST returns
     true only for SST-member states.")

  (decide-fiscal-position
    [this context]
    "Returns a :fiscal-position ref (or nil), used by determine-taxes
     to swap the default tax set. Context fields:
       :partner (with :tax-id, :country-code, :subdivision, :cert-status)
       :transaction-date
     Default fiscal-position decisions:
       - DE seller + EU-VAT buyer in another EU country → :de-eu-intra-comm
       - DE seller + non-EU buyer → :de-export
       - Same-country buyer → nil (use defaults)
     A provider that doesn't model fiscal positions returns nil.")

  (determine-taxes
    [this context]
    "The core method. Returns a vector of TaxFacts (one per line).

     Context shape:
       {:transaction
          {:date #inst             ; supply date
           :journal <ref>
           :type :sale | :purchase | :refund
           :functional-currency :EUR
           :place-of-supply ...}    ; IN POS, optional
        :supplier
          {:country-code :subdivision :tax-id :registration
           :small-scale? ...}        ; CN
        :partner
          {:country-code :subdivision :tax-id :cert-status
           :buyer-type :contributor | :non-contributor   ; BR DIFAL
           :tax-exemptions [{:jurisdiction ... :category ...}]}
        :lines
          [{:line-id :base {:amount :commodity}
            :category <provider-vocabulary tax-code/SKU>
            :ncm \"...\"           ; BR
            :hsn-sac \"...\"       ; IN
            :avalara-tax-code ...  ; US AvaTax
            :purpose :resale | :consumption | :fixed-asset
            :imported-content-pct 0.45}]
        :fiscal-position <ref or nil>     ; from decide-fiscal-position
        :db <datahike db value, read-only>}   ; for lookups

     Contract guarantees:
       - Returns never nil (empty vector when no tax applies).
       - Components within a TaxFacts are sequence-ordered; compound
         taxes appear at higher sequence than their base contributors.
       - All amounts and bases use BigDecimal + commodity; no doubles.
       - :component/provenance is populated for every component
         (audit-trail requirement; auditor must be able to reconstruct
         why a particular rate was applied).
       - Cross-line dependencies (e.g., a per-document rounding pass)
         are NOT performed here — that's a posting-builder concern."))
```

#### Protocol 2: `TaxPostingBuilder`

```clojure
(defprotocol TaxPostingBuilder
  "Materializes GL postings from TaxFacts. Country-specific because
   chart-of-accounts identity (SKR04 1400 vs CN 2221.01.01 vs IN
   CGST/SGST/IGST payable per-state) lives here."

  (builder-id [this]
    "Keyword identifier. Examples: :l10n-de-skr04, :l10n-de-skr03,
     :l10n-cn-mof, :l10n-in-gst, :l10n-br-icms-suite, :generic.")

  (expand-postings
    [this context tax-facts]
    "Returns a vector of :posting maps to merge into the transaction.

     Inputs:
       context — the same context map TaxRateProvider received, with
                 :base-postings added (the line postings already built
                 by the kernel's line-posting bridge).
       tax-facts — vec of TaxFacts (one per line) from
                   TaxRateProvider/determine-taxes.

     Each returned posting is a regular :posting entity map carrying
     at minimum:
       {:posting/account     <ref>
        :posting/amount      <signed BigDecimal>
        :posting/commodity   <ref>
        :posting/account-tags <vec of :account-tag refs>}
     plus optional:
       :posting/partner, :posting/entity, :posting/ledger,
       :posting/tax-fact-id  (back-reference to the TaxFacts component)

     Contract guarantees:
       - Posting amounts SUM to the tax totals in the input TaxFacts
         (cross-check). The kernel's sum-to-zero invariant still
         applies to the assembled transaction.
       - Reverse-charge produces both sides (input + offsetting output).
       - Withholding produces the negative-AR + positive-tax-payable
         pair on the supplier's side, or the AP-reduction + tax-
         payable pair on the buyer's side per :tax-use.
       - The builder MUST resolve account references against the DB
         in the input context (no hardcoded eids).
       - The builder MAY perform per-document rounding adjustments
         (e.g., bucket the cumulative line-rounding remainder onto
         the highest-rate VAT line to make the AR sum to the
         caller-supplied gross — a common reconciliation requirement).")

  (validate-postings
    [this tax-facts postings]
    "Post-condition check. Returns vec of complaint maps; empty when
     the postings are consistent with the facts. Used by tests and
     by an optional invariant middleware. Complaint shape:
       {:complaint :tax-mismatch | :missing-component | :sign-error
        :component <fact ref> :expected-amount ... :actual-amount ...}"))
```

#### Protocol 3: (existing) `TaxReportAggregator`

The report engine in `src/kontor/report.clj` (`:engine :tax-tags` at lines 191-209) IS the third protocol-shaped thing — it's just not currently expressed as a `defprotocol`. It doesn't need to be: it's a pure function `(compute-report conn report-definition opts) → report`. **Leave it alone.** The 11 l10n filing reports (UStVA, UVA, CA3, GST34-2, BAS, JCT, CN VAT return, US per-state, etc.) all already compose on top.

The one thing to add: a `(filings-by-authority conn period)` helper that groups filings by `:tax/authority` so multi-authority filers (CA federal+provincial; US multi-state) get a single dispatch point. Already implicit in `l10n-us/sales_tax.clj/compute-all-active-states`; lift to kernel.

### Composition: ChainedRateProvider

The existing `ChainedProvider` (`tax_provider.clj:133-147`) is the right shape but tries each provider sequentially. Better: **route by `supports?`**.

```clojure
(defrecord RoutedRateProvider [providers]
  TaxRateProvider
  (provider-id [_] :routed)
  (supports? [_ ctx] (boolean (some #(supports? % ctx) providers)))
  (decide-fiscal-position [_ ctx]
    (some #(when (supports? % ctx) (decide-fiscal-position % ctx)) providers))
  (determine-taxes [_ ctx]
    (or (some #(when (supports? % ctx)
                 (let [r (determine-taxes % ctx)] (when (seq r) r)))
              providers)
        [])))
```

So a US-deploying customer composes:

```clojure
(routed-rate-provider
 [(sst-csv-provider {:csv-dir "/var/sst/2026Q2"})  ; 24 SST states
  (avalara-provider {:api-key (System/getenv "AVALARA_KEY")})])  ; the rest
```

Static-EDN providers for DE/AT/FR/JP/AU/CA-CRA/CN/IN compose similarly:

```clojure
(routed-rate-provider
 [(de-static-provider {})
  (cn-static-provider {})
  (in-static-provider {})
  ; ...
  ])
```

### Why three and not two

The maintainer's preliminary `TaxRateProvider` + `TaxPostingBuilder` is the *load-bearing* split — it cleanly factors out the chart-of-accounts dependency from rate determination. That's the right top-level cut.

The third "protocol" (the report aggregator) already exists as `:engine :tax-tags` and didn't need a `defprotocol` to function. Keeping it visible as a *named concern* in the design (rather than melting into "the report engine") matters because:

1. The MX CFDI / BR NFe / IN IRN emitters consume `TaxFacts` directly — they're a *fourth* potential consumer category but they're already factored out into per-country clearance modules via ADR-018 attestations. Mentioning them keeps the design honest about scope.
2. Aggregating across `:tax/authority` (multi-state US, federal+provincial CA, etc.) is a kernel concern, not a per-country one. Naming it surfaces the question.

### The `:tax/*` schema

Mostly stays. The current `:tax` + `:tax-rep` + `:tax-group` shape (`schema.clj:1244-1393`) maps onto the proposed design as the *storage* for `StaticTableProvider`'s rate table:

- `:tax/code`, `:tax/country-code`, `:tax/amount` (the rate), `:tax/type-tax-use`, `:tax/recoverable?`, `:tax/include-base-amount`, `:tax/exigibility`, `:tax/authority` — all directly read by `(determine-taxes static-provider ctx)`.
- `:tax-rep/factor-percent`, `:tax-rep/repartition-type`, `:tax-rep/account`, `:tax-rep/tags` — read by `(expand-postings static-builder ctx facts)` to know which accounts the per-component amounts land on and which tags to attach.
- `:tax-group/payable-account`, `:tax-group/receivable-account` — the default routing when a tax doesn't specify a per-rep account.

**Add**: `:tax/component-kind` (matching the enum from §5), `:tax/rounding-mode`, `:tax/rounding-scope`. **Drop**: nothing.

**Add at the partner level**: `:partner/tax-exemptions [{...}]` (entity-component pattern; a separate `:tax-exemption/*` entity referenced cardinality-many).

**Add at the fiscal-position level**: `:fiscal-position/replaces [{:from-tax X :to-tax Y}]` (`:fiscal-position-mapping` entity, already implicitly hinted at in `schema.clj:1205-1207`).

---

## 6. Per-module migration cost

| Module | What stays | What changes | LoC change | New protocol impls |
|---|---|---|---|---|
| **l10n-de** | `chart.clj`, `ustva.clj` (report definitions), `bs.clj` / `pnl.clj` / `eur.clj` / `closing.clj` / `datev.clj` unchanged. | `invoice.clj/posting-builder` splits into `de-rate-provider` (returns TaxFacts for the line buckets) + `de-skr04-posting-builder` (materializes 1400 / 4400 / 3801 / 4300 / 3806 / 4200 postings). Add reverse-charge support (intra-community + §13b Bauleistungen). | +200 LoC, -60 LoC in `invoice.clj`. New file `de_taxes.clj`. | `DeStaticRateProvider`, `DeSkr04PostingBuilder` |
| **l10n-at** | `uva.clj`, `chart.clj` unchanged. | Add `at_taxes.clj` (static provider for 20/13/10/0%) + `at_posting_builder.clj` (chart-routes UStG accounts). Production-ready when those land. | +250 LoC new. | `AtStaticRateProvider`, `AtPostingBuilder` |
| **l10n-fr** | `ca3.clj`, `chart.clj` unchanged. | Add `fr_taxes.clj` + `fr_posting_builder.clj`. Cash-basis (`:on-payment` exigibility) needs engine work: the posting timing must be deferred until payment-tx settles. Out of scope for this protocol; flag as the FR-specific followup. | +250 LoC new + cash-basis engine in followup. | `FrStaticRateProvider`, `FrPostingBuilder` |
| **l10n-cn** | `vat.clj` (return + surcharges), `fapiao.clj`, `chart.clj` unchanged — the chart-tagged-revenue trick keeps working. | Add `cn_taxes.clj` (static provider with effective-dated small-scale preferential rate) + thin `cn_posting_builder.clj` that just routes the single MOF output-VAT account. Surcharge computation stays in `vat.clj` (report-time, not posting-time). | +200 LoC new. | `CnStaticRateProvider`, `CnPostingBuilder` |
| **l10n-jp** | `consumption_tax.clj`, `invoice.clj` (QIS), `peppol_pint.clj`, `chart.clj` unchanged. | Add `jp_taxes.clj` + `jp_posting_builder.clj`. Two zero-tax categories matter (non-taxable vs export-exempt → input-credit difference); encode via `:exempt-reason`. | +200 LoC new. | `JpStaticRateProvider`, `JpPostingBuilder` |
| **l10n-au** | `gst.clj` (BAS), `chart.clj` unchanged. | Add `au_taxes.clj` (trivial — one rate) + `au_posting_builder.clj`. HALF-UP per-line rounding flagged as AU-specific. | +120 LoC new. | `AuStaticRateProvider`, `AuPostingBuilder` |
| **l10n-ca** | `gst_hst.clj`, `returns.clj`, `y2024/*`, `xml/*`, `pdf.clj`, `noa.clj`, `chart.clj` unchanged. | Add `ca_taxes.clj` (handles GST/HST + PST per province + QST). Posting-builder splits to three authorities (CRA, BC/SK/MB Finance, Revenu Québec) and routes per `:tax/authority`. | +400 LoC new (more taxes, more provinces). | `CaStaticRateProvider`, `CaPostingBuilder` |
| **l10n-us** | `sales_tax.clj` (filing) unchanged. | Heavy lift: write `SstCsvRateProvider` (parse SST quarterly CSVs, address-→-jurisdiction match), `AvalaraRateProvider` (HTTP client gated by user-supplied key), `TaxJarRateProvider`. Posting-builder is straightforward (per-state liability account routing). Add nexus-tracking primitives so the engine knows when to start collecting in a new state. | +1500 LoC new across multiple files. | `SstCsvRateProvider`, `AvalaraRateProvider`, `TaxJarRateProvider`, `UsPostingBuilder`, plus nexus helper |
| **l10n-in** | `taxes.clj` core math (slabs / dispatch-supply / component-split) survives intact; `irn.clj`, `ewb.clj`, `states.clj`, `identifiers.clj` unchanged. | `taxes.clj` gets a thin adapter `in-rate-provider` that wraps the existing `compute-tax` to return TaxFacts shape (the existing return `{:components {kw → Money} :total Money}` maps 1:1). Add `in_posting_builder.clj` (CGST/SGST/IGST payable accounts per-state). Add TDS/TCS support as a separate component-kind set. | +200 LoC adapter + 300 LoC TDS/TCS. | `InStaticRateProvider`, `InPostingBuilder` |
| **l10n-br** | `taxes.clj` core (ICMS matrix / interstate / DIFAL / ICMS-ST / FCP / PIS-COFINS base) survives intact; `cst.clj`, `nfe.clj`, `sped.clj`, `chart.clj`, `identifiers.clj` unchanged. | New `br-rate-provider` that composes the existing helpers into a TaxFacts emission. The CST-driven NF-e XML dispatch in `nfe.clj` consumes TaxFacts directly (each component-kind + CST pair → an emitter). The `MvaProvider` *protocol* already exists for the ST data-source split — keep it as a sub-provider injected into `BrStaticRateProvider`. CBS / IBS / IS support is additive (per ADR-016 sequence-based compound). | +400 LoC adapter + 600 LoC CBS/IBS/IS net-new. | `BrStaticRateProvider` (with injected `MvaProvider`), `BrPostingBuilder` |
| **l10n-mx** | `cfdi.clj` (XML emitter), `identifiers.clj` unchanged. | New `mx-rate-provider` (IVA + IEPS + retenciones — five components on a single CFDI line is normal). `cfdi.clj`'s `<Traslados>` and `<Retenciones>` consume TaxFacts directly. New `mx_posting_builder.clj`. | +500 LoC new. | `MxStaticRateProvider`, `MxPostingBuilder` |

**Modules that genuinely strain the design:**

- **l10n-us**: Not because the protocol is wrong, but because the data + adapter work is irreducibly large. The protocol gives us a clean place to plug Avalara without polluting the kernel; that's the design's contribution.
- **l10n-br**: The CST-driven XML dispatch in `nfe.clj` is *semantically* tightly coupled to per-tax decisions (CST 60 ICMS-ST-paid-elsewhere emits a different XML block AND doesn't emit an output-VAT line). The TaxFacts shape must carry the CST so the emitter can dispatch. Recommended: `:component/jurisdiction-specific-codes {:br/icms-cst "60", :br/csosn nil}` — an opaque map per component that jurisdiction-specific emitters can read.

**The migration is not blocking.** The current `posting-builder` arg pattern (`invoice/send!` calls a caller-supplied fn) is *already* the right plug point. The proposed change inserts a layer (`rate-provider` returns `TaxFacts`; `posting-builder` consumes them) but the call site stays a single function call: `(send! conn invoice-eid (composed-tax-pipeline rp pb))`.

A composition helper:

```clojure
(defn tax-pipeline
  "Compose a TaxRateProvider + TaxPostingBuilder into a posting-builder
   compatible with kontor.document.invoice/send!."
  [rate-provider posting-builder]
  (fn [db invoice]
    (let [ctx   (build-tax-context db invoice)
          fp    (decide-fiscal-position rate-provider ctx)
          facts (determine-taxes rate-provider (assoc ctx :fiscal-position fp))
          base-postings  (build-line-and-contra-postings ctx)
          tax-postings   (expand-postings posting-builder
                                          (assoc ctx :base-postings base-postings)
                                          facts)]
      {:transaction (build-transaction-header ctx facts)
       :postings    (into base-postings tax-postings)})))
```

This keeps `kontor.document.invoice/send!` unchanged.

---

## 7. Open questions for the maintainer

1. **Drop the existing `TaxProvider` protocol or evolve it in place?** The current `(resolve-taxes [this context] → vector-of-postings)` signature is the wrong shape (returns postings; conflates rate+expansion). Evolving in place breaks the contract; dropping and re-introducing as `TaxRateProvider` is cleaner. Recommendation: **drop**, since there are zero callers — but flag in the ADR that the `:tax/* :tax-rep/* :tax-group/*` schema attrs are *preserved* (they back the static-table provider).

2. **Where does the `tax-pipeline` composition helper live?** Two reasonable places: `kontor.tax-provider` (the protocols' home) or `kontor.invoice.posting` (the consumer side). Argument for the former: the helper is provider-flavoured. Argument for the latter: it's a *bridge* concern, like the existing `kontor.invoice.posting/post-to-ledger!`. Recommendation: a new ns `kontor.tax-pipeline` that depends on both — keeps the protocols pure-data and the consumer thin.

3. **How do we model "tax facts captured at posting time, *frozen* for audit"?** A purchased rate (e.g., what Avalara returned for that transaction) should be stored alongside the postings, not re-derived every time someone reruns the report. Options:
   - **Per-posting `:posting/tax-fact-id`** referencing a separate `:tax-fact/*` entity with the full TaxFacts component data.
   - **Per-transaction `:transaction/tax-facts-edn`** as a frozen string blob.
   - **`:posting/account-tags` already records the report-box tags** — sufficient for filing, insufficient for audit/forensics.

   Recommendation: the entity option. Surfaces in queries, supports the `:component/provenance` data, is one schema add. Aligns with how `:attestation/*` works for clearance.

4. **Effective-dated rate registry across providers.** The IN module has `slabs-effective-on` baked in (`l10n-in/taxes.clj:72-78`). The CN module has effective-dated small-scale preferential rate. The proposed `:component/provenance/rate-effective-from` makes this surface in TaxFacts, but **the schema doesn't currently support effective-dated `:tax/amount` over time**. ADR-026 (referenced by `l10n-in/taxes.clj:21`) presumably did this. Verify that the static-table provider can express "rate X effective 2007–2020-12-31; rate Y effective 2021–"; if not, add `:tax/effective-from` + `:tax/effective-until` (already partially present, `schema.clj:2809-2818`).

5. **Withholding placement: rate-provider or separate?** TDS / retención / backup-withholding are *tax decisions* in the sense that they affect what gets posted, but they're frequently *driven by the buyer's policy*, not the seller's catalog. Two approaches:
   - Keep withholding in `TaxRateProvider` with `:component/kind :withholding` — clean, but the seller's rate-provider needs to know about the buyer's TDS policy.
   - Separate `WithholdingProvider` protocol — symmetric to TaxRateProvider but called from the buyer side. Cleaner separation; one more concept.

   Recommendation: keep in `TaxRateProvider` with explicit `:tax-use` distinction (`:sale` returns output-side facts; `:purchase` returns input-side facts + withholding the buyer is obligated to perform). Adding a separate protocol is premature.

6. **What is the test oracle for `expand-postings`?** Round-trip is the obvious answer: take a `TaxFacts`, call `expand-postings`, sum the resulting postings, assert the totals match. But the *account routing* part (1400 vs 1500 receivable; 3801 vs 3811) is country-policy, not derivable from TaxFacts alone. Recommendation: per-country golden-fixture tests (`test/kontor/l10n_de/posting_builder_test.clj`) that exercise representative DE/CN/IN/BR/US cases — same test-discipline rhythm ADR-037 codifies.

7. **Reverse-charge: does the posting end up on the seller's books or the buyer's?** A DE seller selling to a FR-VAT-registered buyer issues an invoice with `Reverse Charge` notation; *no* VAT lines on the seller's invoice. The seller's GL has only AR + revenue, with reporting-tag `:ust-41`. The FR buyer's GL has AP + expense + input-VAT receivable + output-VAT payable (the both-sides pattern). So `:component/kind :reverse-charge` MEANS DIFFERENT THINGS depending on `:tax-use` — on the seller side it's a reporting-tag-only marker (no postings beyond AR/revenue); on the buyer side it materializes two postings. Document this asymmetry explicitly in the protocol docstring + posting-builder contract.

8. **Cross-line rounding pass.** DE/EU VAT rules generally permit per-document rounding (the invoice's stated VAT must match the recomputed total within €0.01 — Section 14 Abs.4 UStG). The current `l10n-de/invoice.clj` rounds per-line (HALF-EVEN at 2dp) and accumulates — works for most cases but can produce €0.01 drift on long invoices. The proposed `expand-postings` MAY perform a per-document reconciliation pass; we should pick: always-allowed (and the per-country builder may choose to do it or not), or always-required (kernel enforces). Recommendation: **always-allowed, never required**, with a `rounding-strategy` opt on the posting-builder construction.

9. **Multi-jurisdiction transactions in a single line.** Possible cases: a US sale shipping from CA to a TX warehouse for further shipment to NY (origin/destination/intermediate); a BR sale where origin and destination states differ and DIFAL is owed to one and ICMS to another. The proposed `TaxFacts` carries *one* `:jurisdiction` per line — multi-jurisdiction is expressed as multiple components within the same `TaxFacts`. Verify that the per-component `:authority` field is enough to route filing correctly. Recommendation: add `:component/jurisdiction {:authority ... :subdivision ...}` so a single TaxFacts can carry components routed to different authorities.

10. **The relationship with `kontor.einvoice-provider`.** The BR `nfe.clj` already requires `kontor.einvoice-provider` (`l10n-br/nfe.clj:49`). What's that protocol? If it's the e-invoicing-side cousin of TaxProvider, the design has a sibling. Recommendation: include a sentence-or-two on alignment in the ADR.

---

## Sources

### kontor code (file:line)

- `src/kontor/tax_provider.clj:78-82` — `StaticTableProvider/resolve-taxes` returns literal `[]`.
- `src/kontor/tax_provider.clj:101-122` — stub Avalara/TaxJar/SST scaffolds.
- `src/kontor/tax_provider.clj:133-147` — `ChainedProvider` sequential routing.
- `src/kontor/schema.clj:1209-1228` — `:fiscal-position/*` attrs.
- `src/kontor/schema.clj:1244-1327` — `:tax/*` attrs (incl. `:tax/authority`).
- `src/kontor/schema.clj:1329-1373` — `:tax-rep/*` (repartition lines).
- `src/kontor/schema.clj:1375-1393` — `:tax-group/*`.
- `src/kontor/schema.clj:2809-2818` — `:tax/effective-from` / `:tax/effective-until` (already present).
- `src/kontor/document/invoice.clj:205` — kernel `send!` invokes `posting-builder`.
- `src/kontor/report.clj:191-209` — `:engine :tax-tags` (the existing aggregator).
- `modules/invoice/src/kontor/invoice/posting.clj:294-345` — generic `post-to-ledger!` bridge (no tax-line expansion).
- `modules/l10n-de/src/kontor/l10n_de/invoice.clj:69-129` — DE `posting-builder`.
- `modules/l10n-de/src/kontor/l10n_de/ustva.clj:32-101` — UStVA reporting.
- `modules/l10n-cn/src/kontor/l10n_cn/vat.clj:108-267` — CN VAT + surcharges.
- `modules/l10n-ca/src/kontor/l10n_ca/gst_hst.clj:86-183` — CA GST/HST GST34-2.
- `modules/l10n-br/src/kontor/l10n_br/taxes.clj` (entire) — BR rate engine.
- `modules/l10n-br/src/kontor/l10n_br/taxes.clj:328-345` — `MvaProvider` protocol (existing precedent).
- `modules/l10n-br/src/kontor/l10n_br/cst.clj` — CST tables.
- `modules/l10n-br/src/kontor/l10n_br/nfe.clj:14-44` — NF-e CST-dispatched emitter + known gaps.
- `modules/l10n-in/src/kontor/l10n_in/taxes.clj` (entire) — IN GST engine.
- `modules/l10n-in/src/kontor/l10n_in/irn.clj:61-74` — IRN hash + IRP payload.
- `modules/l10n-jp/src/kontor/l10n_jp/consumption_tax.clj:1-163` — JCT.
- `modules/l10n-jp/src/kontor/l10n_jp/invoice.clj:25-89` — QIS registration validation.
- `modules/l10n-fr/src/kontor/l10n_fr/ca3.clj` — CA3.
- `modules/l10n-at/src/kontor/l10n_at/uva.clj` — UVA.
- `modules/l10n-au/src/kontor/l10n_au/gst.clj` — BAS.
- `modules/l10n-us/src/kontor/l10n_us/sales_tax.clj` — per-state filing.
- `modules/l10n-mx/src/kontor/l10n_mx/cfdi.clj:94-160` — CFDI Impuestos / Traslados / Retenciones.

### Odoo (AGPL-3; read for patterns only)

- `addons/account/models/account_tax.py:71-209` — `account.tax` field list.
- `addons/account/models/account_tax.py:80-95` — `type_tax_use` / `amount_type` selections.
- `addons/account/models/account_tax.py:148-154` — `include_base_amount` / `is_base_affected`.
- `addons/account/models/account_tax.py:164-173` — `tax_exigibility` + cash-basis transition account.
- `addons/account/models/account_tax.py:174-195` — `invoice_repartition_line_ids` / `refund_repartition_line_ids` / `repartition_line_ids`.

### Kontor ADRs

- ADR-001 — License: EPL-1.0.
- ADR-002 — One database, beleg cohabitation (invoice + posting same tx).
- ADR-005 — `tax-provider` protocol from day 1; "we never bundle Avalara/TaxJar API keys."
- ADR-007 — Purge is a recorded commit (not a silent retract).
- ADR-010 — Scope boundaries; "we are not a US sales tax engine."
- ADR-014 — `:tax/authority` (per-authority filing).
- ADR-016 — `:tax-application` compound chain.
- ADR-017 — Pure XML emitters; signing + clearance live in partner adapters.
- ADR-018 — `:attestation/*` slot for clearance tokens (NF-e cStat, MX TFD UUID, IRN, fapiao code).
- ADR-023 — `:transaction/place-of-supply`.
- ADR-024 — Attestation dependencies (IRN → EWB Part A).
- ADR-025 — CFDI complementos stacked on the envelope.
- ADR-026 — Effective-dated rate registry (IN GST 2.0 cutover).
- ADR-037 — Per-stage research-implement-review rhythm.
- ADR-041 — `account-type-direction` table for debit/credit dispatch.
- ADR-068 — Single-tx posting gate (pure tx-data builders + side-effect wrappers).

### Jurisdictional / commercial references

- South Dakota v. Wayfair (2018) — US economic-nexus doctrine.
- Streamlined Sales Tax (SST) Governing Board — `streamlinedsalestax.org` (24 member states, free quarterly CSV).
- Avalara AvaTax API — `developer.avalara.com/api-reference/avatax/rest/v2/` (transaction-create endpoint).
- TaxJar API — `developer.taxjar.com/api/reference/` (`/v2/taxes`).
- CRA GST/HST NETFILE — `canada.ca/en/revenue-agency/services/e-services/digital-services-businesses/gst-hst-netfile.html`.
- Revenu Québec QST returns — `revenuquebec.ca`.
- BMF (DE) UStVA-Formular 2026; BOFiP-Impôts BOI-TVA-DECLA-20-20 (FR CA3).
- Bundesministerium für Finanzen (AT) UVA Stand 2026.
- ATO BAS labels — `ato.gov.au/forms/business-activity-statement-form`.
- NTA (JP) Qualified Invoice System — `nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/`.
- NIC IRP (IN) — `einvoice1.gst.gov.in/Documents/EINVOICE_SCHEMA.pdf` (v1.1).
- SEFAZ NF-e 4.0 Manual de Integração — `portal.nfe.fazenda.gov.br`.
- LC 214/2025 + EC 132/2023 art. 124-ADCT — BR Tax Reform schedule.
- CONFAZ Res. SF 22/1989 + Res. SF 13/2012 — BR ICMS interstate routing.
- STF RE 574.706 (Tema 69) — BR ICMS exclusion from PIS/COFINS base.
- Cai Shui [2023] 1 + [2023] 19 — CN small-scale preferential rate + threshold.
- MOF/STA Announcement 2021 No. 28 — CN surcharge base rule (post-UMCT-Law codification).
- 56th GST Council meeting (2025) — IN GST 2.0 slabs effective 2025-09-22.

### Kontor research notes (prior)

- 01 — Odoo accounting reference (license + reference-not-source guidance).
- 03 — US sales tax research (SST CSVs vs Avalara/TaxJar; "we never bundle keys").
- 09 — Cross-country tax variance survey.
- 31 — Asset close + tax law for kontor design.
- 51 — Tax authority as consumer.

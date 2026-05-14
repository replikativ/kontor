# Research note 30 — SAP / NetSuite / Oracle: fixed-asset depreciation + year-end close + statutory statements

**Date:** 2026-05-14
**Stage:** L′ (`kontor-asset`) research-before — enterprise-systems study
**Scope:** How SAP FI-AA, NetSuite FAM, and Oracle Assets model fixed-asset depreciation ("Abschreibungen"), year-end close + statutory financial statements ("Jahresabschluss"), with special focus on the **parallel-valuation mechanism** (book vs tax depreciation). All three systems are proprietary; everything below is sourced from public vendor docs, help portals, training material, and consultant blogs — URLs cited inline.
**Verified?** Medium-high. Every claim cites a public URL. The three vendors' depreciation-area / tax-book / alternate-method designs are well documented; the German FI-AA specifics are corroborated across SAP-press, Haufe, and SAP Community.

---

## 0. Why this note exists — the kontor gap

`kontor`'s `:schedule` entity (ADR-032) can model depreciation as *recurring postings* (`Dr Depreciation Expense / Cr Accumulated Depreciation` per period) — but the kernel deliberately does **not** compute per-period amounts, has **no `:asset` register**, **no depreciation-method engine**, and **no parallel books for fixed assets**. `:valuation-book` (ADR-027) is a parallel-cost-basis entity, but it is scoped to *inventory* costing (FIFO/LIFO/avg/standard), not fixed-asset depreciation. `kontor.closing` + `kontor.financial-statements` + `kontor.period` give year-end mechanics (P&L roll-to-retained-earnings, soft/hard period lock, data-defined statement hierarchy).

The hardest design problem for `kontor-asset` is **parallel valuation**: one physical asset, N regulatory valuations — German Handelsbilanz vs Steuerbilanz being the canonical pain. The enterprise systems solved this decades ago. This note distills *how*, so the `kontor-asset` ADR can adopt the convergent shape rather than reinvent it.

---

## 1. SAP Asset Accounting (FI-AA / S/4HANA New Asset Accounting) — the deepest reference

SAP is a German product; FI-AA is the most mature fixed-asset subledger in the industry, and it was built from day one around German Handelsbilanz/Steuerbilanz parallel valuation. It is the primary oracle for `kontor-asset`.

### 1.1 Depreciation areas — THE key concept

A **depreciation area** ("Bewertungsbereich") is SAP's unit of parallel valuation. One **asset master record** carries **N depreciation areas** — each area is an independent valuation of the *same physical asset* under a *different accounting principle*. The set of areas available to a company code is collected in a **chart of depreciation** ("Bewertungsplan"), which can hold up to 99 areas, each identified by a two-digit numeric key ([SAP-press: Depreciation Areas](https://blog.sap-press.com/depreciation-areas-in-sap-s4hana)).

Conventional standard areas:
- **01** — book depreciation (Handelsbilanz / IFRS leading valuation)
- **15** — tax depreciation (Steuerbilanz)
- **20** — cost-accounting valuation (kalkulatorische Bewertung — imputed depreciation/interest for CO)
- **30** — group/consolidated valuation (Konzernbilanz), often in group currency

(Typical German set: Handelsbilanz, Steuerbilanz, Konzernbilanz, Vermögensbewertung, kalkulatorische Bewertung — [SAP Community / docplayer FI-AA Customizing study](https://docplayer.org/7902078-Implementierung-und-customizing-einer-anlagenbuchhaltung-am-beispiel-des-sap-moduls-fi-aa.html).)

**Real vs derived areas.** A *real* area commits values to the database. A *derived* area is computed by adding/subtracting two or more real areas — e.g. a "delta area" giving an immediate view of the Handelsbilanz-vs-Steuerbilanz difference (the special-reserve / `Sonderposten` delta). Derived areas need no storage; they are a query over real areas ([SAP-press](https://blog.sap-press.com/depreciation-areas-in-sap-s4hana)).

**How areas post to the GL.** Each area's posting behaviour is configured independently:
- **Real-time posting** — area posts both APC (acquisition/production cost) *and* depreciation; standard for the ledger approach.
- **Depreciation only** — posts depreciation but not APC (typical for cost-controlling areas).
- **APC real-time, depreciation periodic** — the legacy "accounts approach" shape.
- **Reporting only / statistical** — no GL posting at all (currency-conversion areas, insurance-value areas).

A critical invariant: **"there is no such thing as real-time depreciation posting — depreciation is always periodic"** ([SAP-press](https://blog.sap-press.com/depreciation-areas-in-sap-s4hana)). APC postings hit the GL the moment the asset is acquired; depreciation is *always* batched by a periodic run.

### 1.2 The S/4HANA "ledger approach" — how areas map to ledgers

S/4HANA "New Asset Accounting" made all depreciation areas **equal and real-time-posting** — the old "one leading area + periodically-posted secondary areas" asymmetry is gone ([SAP learning: configuring asset accounting](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-how-depreciation-areas-post-to-the-general-ledger)).

The linkage is: **depreciation area → accounting principle → ledger group → ledger**. Each area is assigned to an **accounting principle**; all areas representing the same accounting principle map to the same ledger group ([SAP Help: Ledger Approach in Asset Accounting](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/651d8af3ea974ad1a4d74449122c620e/dd214452ab903607e10000000a441470.html)). Two posting strategies:

- **Ledger approach** — same GL accounts, *different ledgers*. Area 01 posts to ledger 0L, area 15 posts to a non-leading ledger. The chart of depreciation must mirror the GL ledger setup: every base ledger must be controlled by a depreciation area.
- **Accounts approach** — same ledger, *different GL accounts*. Book values on one set of accounts, tax-delta on another.

**This is exactly kontor's ADR-021 parallel-ledger pattern.** SAP's "depreciation area → accounting principle → ledger" chain maps cleanly to kontor's `:posting/ledger` ref (one posting per ledger from one source event). The "depreciation area" in kontor terms is *the (asset, ledger) pair* — see §4.

### 1.3 Depreciation keys — the rule object

A **depreciation key** ("Abschreibungsschlüssel") is the configurable rule object that encodes *how* an area depreciates an asset. It is **data, not code** — assigned to the asset (per area) and changeable by changing the key, which retroactively re-derives all assets carrying it ([SAP Community: changing depreciation method](https://community.sap.com/t5/enterprise-resource-planning-q-a/depreciation-key-calculation-method/qaq-p/9052578)).

A depreciation key **bundles up to five calculation methods** ([Skillstek: depreciation calculation methods](https://skillstek.com/methods-of-depreciation-calculation-in-sap/); [SAP learning](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-depreciation-areas-keys-calculation-and-posting)):

1. **Base method** — the *type* of depreciation (ordinary / special / unplanned / unit-of-production) and the *method family* (straight-line, declining-balance, stated-percentage). Created via AFAMR.
2. **Declining-balance method** — the multiplication factor + ceiling % + floor %; the constant rate applied to net book value. Created via AFAMD.
3. **Maximum-amount method** — a depreciation ceiling not to be exceeded in a period. AFAMH.
4. **Multi-level method** — a *list of levels*, each level a (validity period in years/months → percentage rate). When a level's validity expires, the next level's rate takes over. This is how a key encodes a tax authority's mandated rate schedule that *changes over the asset's life*. AFAMS.
5. **Period-control method** — the rule for *when* depreciation starts/stops relative to acquisition/retirement timing: mid-month acquisition → which period; mid-year addition → which period to begin. (Pro-rata, first-of-month, half-year, etc.)

Plus a **changeover method**: a key can declare "when declining-balance falls below what straight-line would yield, change over to straight-line" — or, for German investment-grant ("Sonderabschreibung") schemes, "after the tax-concession period, depreciate the remaining net book value straight-line over remaining useful life" ([SAP Community: different methods of depreciation calculation](https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-members/different-methods-of-depreciation-calculation/ba-p/13249758)). The changeover year and trigger condition are key parameters.

Also **time-dependent**: depreciation key, useful life, variable portion, and scrap value can all be effective-dated on the asset — *"changes only affect future periods"*, never recompute closed periods ([SAP learning](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-depreciation-areas-keys-calculation-and-posting)). This is a *bitemporal* statement — directly relevant to kontor's effective-dated-attribute substrate.

### 1.4 Asset classes — the template

An **asset class** ("Anlagenklasse") is the master-data template every asset is created against ([SAP learning: components of an asset class](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/explaining-the-components-of-an-asset-class)). It carries:
- **Account-determination key** — links the class to the GL accounts to post (APC account, accumulated-depreciation account, depreciation-expense account, gain/loss accounts), *per chart of accounts and per depreciation area* ([SAP Community: assign depreciation key to asset class](https://community.sap.com/t5/enterprise-resource-planning-q-a/asset-class-master-problems-default-values/qaq-p/2614951)).
- **Screen layout** — which asset-master fields are required / optional / suppressed.
- **Number range** — the ID-assignment interval for assets in this class.
- **Default depreciation key + default useful life *per depreciation area*** — set in AO21, with a "Class" flag controlling whether the default is offered.

This is the "asset category" concept that NetSuite and Oracle also have. The key insight: **the asset class defaults a *different* depreciation key and useful life for each depreciation area** — i.e. one class can say "book area: straight-line 10y, tax area: declining-balance 7y."

### 1.5 The German angle — Handelsbilanz vs Steuerbilanz

FI-AA's parallel-area design exists *because* German law mandates two divergent valuations:
- **Handelsbilanz** (HGB commercial balance sheet) — "planmäßige Abschreibung" (planned depreciation), area 01.
- **Steuerbilanz** (EStG tax balance sheet) — "Absetzung für Abnutzung (AfA)", area 15.

Both are *mandatory* and *differ* in method, useful life, and special allowances ([docplayer FI-AA study](https://docplayer.org/7902078-Implementierung-und-customizing-einer-anlagenbuchhaltung-am-beispiel-des-sap-moduls-fi-aa.html); [erplingo: tax depreciation in FI-AA](https://www.erplingo.com/sap-glossary/en/fi-aa/tax-depreciation)). Useful lives in the tax area follow the **AfA-Tabellen** — the official BMF depreciation tables (publicly available, public-domain data — noted as a kernel-usable l10n data source in research note 11).

German-specific depreciation features FI-AA must model, all expressible as depreciation keys / asset classes:
- **GWG (geringwertige Wirtschaftsgüter — low-value assets).** Net cost ≤ €250 → immediate write-off (Sofortabschreibung). €250.01–€800 → immediate write-off *or* pooling. Either a dedicated GWG asset class with a "100% in year 1" depreciation key, or it bypasses the asset register entirely ([Haufe: GWG](https://www.haufe.de/thema/geringwertiges-wirtschaftsgut/); [steuern.de: Abschreibung GWG](https://www.steuern.de/abschreibung-gwg)).
- **Sammelposten (pool / GWG-pool).** Assets €250.01–€1,000 pooled into one collective item, dissolved 20%/year over 5 years regardless of individual disposals — a *pooled* asset with a fixed 5-year straight-line key ([Haufe](https://www.haufe.de/thema/geringwertiges-wirtschaftsgut/)).
- **Degressive AfA** — declining-balance, periodically permitted by tax law (e.g. reintroduced 2020–2022, 2024+) with a statutory cap on the rate; encoded as a declining-balance method with a multiplication factor + ceiling.
- **Sonderabschreibung** — special tax allowances on top of ordinary AfA; modelled as a *special depreciation* base-method type, often with a changeover to ordinary AfA after the concession window.

The HGB-vs-Steuer delta produces a balance-sheet item (`Sonderposten mit Rücklageanteil` / deferred-tax effects) — in SAP this is what **derived (delta) depreciation areas** surface automatically.

### 1.6 Year-end in FI-AA — the close as a process

FI-AA has its own close, executed *before* the GL year-end close ([sap-f5: Fiscal Year Change & Year-End Closing](http://sap-f5.blogspot.com/2009/08/fiscal-year-change-ajrw-year-end.html); [SAP learning: executing year-end closing](https://learning.sap.com/courses/asset-accounting-year-end-closing-activities/executing-year-end-closing-1-1)):

1. **Fiscal-year-change program (AJRW).** Opens new annual value fields for every asset and carries the previous year's asset values forward *cumulatively* into the new year. This is the **balance-carryforward for the asset subledger** — it is purely structural (opens slots), not a posting.
2. **Depreciation posting run (RAPOST2000 / program `FAA_DEPRECIATION_POST`).** The periodic batch that computes and posts depreciation for the period across all areas — ordinary, special, unplanned, tax, imputed interest, revaluation. A **test run** flags errors (locked cost centers, missing account assignments) before the productive run ([SAP learning: depreciation areas/keys/posting](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-depreciation-areas-keys-calculation-and-posting)).
3. **Asset history sheet ("Anlagengitter").** The statutory fixed-asset movement schedule — opening APC, additions, disposals, transfers, write-ups, closing APC, accumulated depreciation, net book value — *per asset class*. **This IS a mandatory component of the German Jahresabschluss** (HGB §284 ff. Anlagenspiegel). Checked before depreciation is finally posted.
4. **Year-end-closing program (AJAB).** Verifies depreciation is fully posted and no asset is in error/incomplete; if clean, it updates "last closed fiscal year" *per depreciation area* — i.e. the close is *per-area*, not global. After this the year is locked for asset transactions.

The crucial structural fact: **the asset subledger closes per depreciation area, before and independently of the GL close.** Carryforward (open new value fields) and the close (lock the year) are separate steps.

### 1.7 GL year-end close + the Financial Statement Version (FSV)

The GL side:
- **Fiscal year variant** — defines the periods (12 normal + up to 4 "special periods" for year-end adjustments — kontor already models this, research note 07 / ADR-014).
- **Balance carryforward program (FAGLGVTR)** — carries balance-sheet account balances forward to the new year's opening, and rolls P&L into retained earnings. (kontor's `kontor.closing/close-period!` is the P&L-roll half; the BS-carryforward half kontor does *implicitly* via cumulative-balance computation — see ADR-021 / closing.clj docstring.)
- **Financial Statement Version (FSV)** — SAP's **data-defined Balance Sheet / P&L structure**. Created in transaction **OB58**; it is a *hierarchy* of financial-statement items, accounts assigned at the leaf level, the system computing subtotals/totals at each node ([SAP Help: Financial Statement Version](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/651d8af3ea974ad1a4d74449122c620e/c064c2531bb9b44ce10000000a174cb4.html); [SAP learning: configuring a new FSV](https://learning.sap.com/learning-journeys/implementing-record-to-report-in-sap-s-4hana/configuring-a-new-financial-statement-version)). One FSV per accounting purpose (HGB FSV, IFRS FSV, US-GAAP FSV); the FSV is tied to a chart of accounts; functional areas can be assigned to nodes for cost-of-sales P&L. S/4HANA's "Manage Global Hierarchies" is the modern editor for the same structure.

**The FSV is directly analogous to kontor's `compute-statement` definition** — a hierarchy of sections/lines, account-code prefixes at the leaves, computed subtotals (see `src/kontor/financial_statements.clj`). The difference: SAP's FSV is a *persisted entity*; kontor's statement definition is currently passed as a plain map at call time. See §6.

---

## 2. NetSuite Fixed Assets Management (FAM)

NetSuite FAM is a SuiteApp (bundle), not native — which itself signals the subledger-vs-GL separation. It is the "SMB-to-mid-market" reference: simpler than SAP, and its limitations are instructive.

### 2.1 Asset model

- **Asset record** — defines the asset and its depreciation rules; shows net book value, depreciation-life-to-date, last-depreciation-date, original cost, residual value ([VNMT: NetSuite FAM guide](https://www.vnmtsolutions.com/netsuite-fixed-asset-management/)).
- **Asset Type** — NetSuite's "asset class": carries the default depreciation method, default GL accounts, asset-life defaults. Has an **"Other Methods" / "Alternate Depreciation" subtab** where additional default methods for other books can be attached ([Houseblend: NetSuite FAM depreciation setup](https://www.houseblend.io/articles/netsuite-fixed-assets-depreciation-setup-methods)).
- **Depreciation Method record** — the rule object. NetSuite ships straight-line, declining-balance / fixed-declining, sum-of-the-years-digits, units-of-production; methods are *records* with a formula, not hardcoded ([Oracle docs: NetSuite Asset Depreciation](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2158182.html)).
- **Depreciation schedule** — materialized per asset: an Acquisition line, a "catch-up" Depreciation line for the gap between purchase and go-live, then one line per future period. Regenerated (optionally nightly) when the asset changes ([theledgerlabs: NetSuite FAM](https://theledgerlabs.com/netsuite-fixed-asset-management/)).
- **Asset disposal** — a disposal transaction removes cost + accumulated depreciation, books gain/loss; *must be the last entry for the asset*, and final partial-period depreciation must be brought current first ([MS Learn: dispose/retire FA](https://learn.microsoft.com/en-us/dynamics365/business-central/fa-how-dispose-retire) — generic, corroborated across vendors).

### 2.2 NetSuite's parallel-book answer

NetSuite has **two** parallel mechanisms, and the distinction matters:

1. **Alternate methods (a.k.a. tax depreciation methods).** Created under *Fixed Assets > Setup > Alternate Methods*. An alternate method is **computed during the depreciation run but does NOT post to the GL** — it is informational/tracking only (e.g. for the tax return). An **Override Flag** lets the alternate method override Depreciation Method, Convention, Asset Life, Financial Year Start, and Period Convention per asset ([Oracle docs: Creating Alternate Methods (Tax Depreciation Methods)](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2140809.html)). The **Period Convention** is NetSuite's prorate convention: "12 months × 30 days" (uniform, US/EU) vs "actual days, 365-day year" (uneven, AU/NZ).
2. **Multi-Book Accounting.** NetSuite's *real* parallel-ledger feature (primary book + up to 4 secondary books — see ADR-021's competitor survey). FAM *can* depreciate per accounting book — a posting depreciation run generates GL journal entries per book. Secondary books may be Full or Adjustment-Only.

So NetSuite is **weaker than SAP here**: SAP makes *every* depreciation area a first-class real-time-posting valuation; NetSuite's "alternate methods" are second-class (no GL posting), and full parallel-book depreciation requires the separately-licensed Multi-Book Accounting module. The lesson for kontor: **don't build a second-class "tax-only, non-posting" mechanism — make every book equal** (SAP's S/4HANA decision, and it matches kontor's ADR-021 "every ledger is equal" philosophy).

### 2.3 Period close

NetSuite's **Period Close Checklist** is a sequenced task list per accounting period ([Oracle docs: Using the Period Close Checklist](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N1455781.html)):
- Preliminary **locking tasks** — Lock A/P, Lock A/R, Lock Payroll, Lock All — each *partially* locks (e.g. Lock A/R blocks any transaction touching an A/R-type GL account).
- **Adjustment tasks** — Create Intercompany Adjustments, Revalue Open Foreign Currency Balances, Calculate Consolidated Exchange Rates.
- **Close Period** — once all tasks done; afterward no further actions in the period.
- With Multi-Book, periods **close per accounting book independently** ([Oracle docs: Accounting Book Period Close Management](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_4308385929.html)).

The **soft-lock (per-subledger) → hard-close** progression mirrors kontor's ADR-014 (`:period/locked-at` soft, `:period/sealed-at` hard) — but NetSuite's *per-subledger* partial locks are finer-grained than kontor's current single lock state. The Financial Statement Builder is NetSuite's data-defined statement tool (analogous to FSV/FSG).

---

## 3. Oracle Assets (Oracle Fusion Cloud Assets / EBS Oracle Assets)

### 3.1 Asset books — corporate vs tax

Oracle's parallel-valuation unit is the **asset book**:
- **Corporate book** — the primary valuation, posts to the GL via a ledger.
- **Tax book(s)** — N tax books, each *linked to a corporate book*, holding an independent tax valuation ([Oracle docs: Asset Books (Chapter 3)](https://docs.oracle.com/en/cloud/saas/financials/21a/faias/asset-books.html)).

The link between corporate and tax books is **copy-based, not shared-master**:
- **Perform Initial Mass Copy** — populates a new tax book with all assets *as they appeared at the end of the tax book's current fiscal year* in the corporate book ([Oracle docs: Asset Books](https://docs.oracle.com/en/cloud/saas/financials/21a/faias/asset-books.html)).
- **Periodic Mass Copy** — thereafter copies new acquisitions / adjustments / retirements from the corporate book into the tax book each period.
- The tax book then *depreciates independently* with its own method, life, prorate convention. **A tax book can have a different depreciation calendar than its corporate book.**

This is a meaningfully *different* design from SAP: SAP has **one asset master with N areas** (the areas are facets of one record); Oracle has **N book-copies of the asset** kept in sync by a copy program. SAP's is cleaner (no sync step, no drift); Oracle's gives each book full independence at the cost of a reconciliation surface. **kontor should follow SAP's shape** — one `:asset`, N `(asset, ledger)` valuations — not Oracle's copy-and-sync.

### 3.2 Methods, conventions, calendars

- **Depreciation method** — a record (straight-line, declining-balance, units-of-production, table-based, formula-based). Defaulted from the **asset category** (Oracle's "asset class").
- **Prorate convention** — determines first-year and last-year depreciation based on date-placed-in-service; the convention + DPIS yield the *prorate date*, which indexes the *prorate calendar* to find the prorate period ([Oracle docs: Asset Books](https://docs.oracle.com/en/cloud/saas/financials/21a/faias/asset-books.html)). This is the same concept as SAP's period-control method.
- **Depreciation calendar + prorate calendar** — each book has both; books can share calendars; a tax book may diverge.
- Cost, salvage value, prorate convention, method, life, rate, bonus rule, depreciation ceiling can all be **changed in a later period** — i.e. effective-dated, prospective.

### 3.3 Year-end + the Financial Statement Generator (FSG)

Oracle's depreciation **close** is per book per period; year-end rolls the depreciation calendar. The statutory-statement tool is the **Financial Statement Generator (FSG)** ([Oracle docs: Using the Financial Statement Generator](https://docs.oracle.com/cd/A60725_05/html/comnls/us/gl/fsgover.htm)):
- **Row Set** — the report's rows: line items, account ranges, calculation rows for subtotals/totals.
- **Column Set** — the report's columns: headings, currency assignments, amount types, calculation columns.
- **Content Set** (optional) — expansion values that fan one report definition into many (e.g. one per cost center), optionally run in parallel.
- A **report definition** = Row Set × Column Set (+ optional Content Set / Row Order / Display Set).

FSG factorizes the statement into an *orthogonal* row-structure × column-structure — more flexible than SAP's FSV (a single hierarchy) and than kontor's `compute-statement` (sections × lines, single axis). See §6.

---

## 4. The parallel-valuation pattern, distilled

SAP "depreciation areas," NetSuite "alternate methods / accounting books," Oracle "tax books" all solve the same problem: **one physical asset, N regulatory valuations**. The convergent shape:

| Concern | SAP FI-AA | NetSuite FAM | Oracle Assets |
|---|---|---|---|
| Parallel-valuation unit | Depreciation area | Accounting book (+ alternate method) | Asset book (corporate / tax) |
| One master or N copies? | **One master, N area-facets** | One asset, N book-postings | **N book-copies, sync'd by Mass Copy** |
| Posts to GL? | Every real area, real-time | Per book (alternate methods don't post) | Per book |
| Bound to a GL ledger via | Accounting principle → ledger group | Accounting book | Ledger |
| Method/life can differ per valuation? | Yes (per area) | Yes (per book / override flag) | Yes (per book) |
| Calendar can differ per valuation? | Period control per area | Period convention per method | Depreciation calendar per book |

**The distilled common shape:**
1. **One asset entity** holding physical/identity facts (acquisition cost, acquisition date, asset class, description, location, owner).
2. **N valuations**, each = (asset × accounting-framework) carrying its *own* depreciation method, useful life, salvage value, period-control, and accumulated-depreciation running total.
3. **Each valuation is bound to a GL ledger** (SAP "accounting principle → ledger"; Oracle "ledger"; NetSuite "accounting book").
4. **One physical event (acquisition / depreciation tick / disposal) fans out into N postings**, one per valuation, each into its valuation's ledger.
5. The **chart-of-accounts can be shared** across valuations (SAP "accounts approach" / NetSuite / Oracle default) — divergence rides on *separate postings into separate ledgers*, not on parallel amount columns.

**How this maps to kontor:** This is *exactly* the ADR-021 parallel-ledger pattern. A "depreciation area" in kontor terms is **the `(asset, :ledger)` pair**. kontor does NOT need a new "depreciation area" entity — it needs:
- a new **`:asset`** entity (physical/identity facts) — genuinely new, nothing in the kernel models it;
- a new **`:asset-valuation`** entity = `(asset-ref, ledger-ref, depreciation-method-ref, useful-life, salvage-value, in-service-date, accumulated-depreciation-account, depreciation-expense-account)` — the per-ledger facet, the "depreciation area";
- the depreciation tick reuses **`:schedule` + `:schedule-occurrence`** (ADR-032) — one schedule *per valuation*, firing one transaction per period whose postings target that valuation's ledger; sum-to-zero stays per-(ledger, commodity) per ADR-021/031;
- the multi-entity dimension (ADR-031 `:posting/entity`) composes orthogonally — a group asset valued in parent + subsidiary books is just `(asset, ledger, entity)`.

`:valuation-book` (ADR-027) is **not** the right home — it is inventory-cost-method scoped (FIFO/LIFO/avg/standard) and conceptually orthogonal. The "parallel valuation of a fixed asset" axis is the *ledger* axis, not the *cost-method* axis. (One could argue `:valuation-book` should have been named more generally; it wasn't, and conflating fixed-asset depreciation books with inventory cost bases would muddy both. Keep them separate; the ADR should note the parallel.)

---

## 5. Depreciation-method-as-data — the rule-object shape

All three vendors encode the method as a **configurable rule object, never hardcoded logic**. SAP is the richest (a depreciation key = up to 5 calculation methods + changeover); NetSuite and Oracle ship a method record with a formula. The distilled rule-object shape kontor should adopt for a **`:depreciation-method`** entity:

```
:depreciation-method/code           ; "DE-LINEAR-10Y", "DE-GWG", "DE-DEGRESSIV-25"
:depreciation-method/family         ; :straight-line | :declining-balance
                                    ;  | :sum-of-years | :units-of-production
                                    ;  | :immediate (GWG) | :manual
:depreciation-method/rate-or-factor ; bigdec — % for declining-balance,
                                    ;   multiplication factor, or nil for SL
:depreciation-method/period-control ; :pro-rata | :first-of-month
                                    ;   | :half-year | :full-month
                                    ;   | :next-period  (SAP period-control)
:depreciation-method/changeover     ; nil, or {:to <method-ref>
                                    ;          :trigger :sl-yields-more
                                    ;                  | :after-year N}
:depreciation-method/rate-schedule  ; optional — vector of
                                    ;   {:from-year :to-year :rate}
                                    ;   (SAP "multi-level method": rate
                                    ;    changes over the asset's life)
:depreciation-method/ceiling-pct    ; optional cap (degressive AfA statutory cap)
:depreciation-method/floor-pct      ; optional floor
```

**Where the computation lives.** Following ADR-032's explicit decision ("the kernel does NOT compute per-period amounts… consumers ship the rule-evaluation engine"), there are two honest options:
- **(a)** The kernel ships `:depreciation-method` as *data only* and a *pure function* `(amounts-for valuation method as-of)` that the consumer calls — kontor *can* own the math here because, unlike inventory costing (genuinely pluggable per ADR-029's `CostingProvider`), depreciation math is a small closed set of well-known formulas. The five families above cover ~all of GAAP/IFRS/HGB/EStG.
- **(b)** Mirror `CostingProvider` exactly with a `DepreciationProvider` protocol — maximal flexibility, but probably over-engineered: the ADR-032 rejected-alternatives section already says "most consumers use one well-known method per schedule-kind and don't need a runtime-pluggable engine."

**Recommendation: (a)** — ship `:depreciation-method` data + a pure `kontor.depreciation/schedule-amounts` function covering the five families + changeover + multi-level rate schedule. Keep the math in the kernel (it's small, closed, and testable against IRS Pub 946 MACRS tables + German AfA-Tabellen — both public-domain, flagged usable in research note 11). l10n modules ship the *data* (`:depreciation-method` seeds + per-class defaults), not new code. This is the same split as `StaticTableProvider` for tax: kernel ships the engine, l10n ships the table.

---

## 6. Statutory-statement-as-data — FSV / FSG / Builder vs `compute-statement`

| | SAP FSV | Oracle FSG | NetSuite FS Builder | kontor `compute-statement` |
|---|---|---|---|---|
| Structure | Single **hierarchy** of FS items; accounts at leaves; auto subtotals | **Row Set × Column Set** (orthogonal); optional Content Set fan-out | Layout builder, hierarchical | Sections → Lines; account-code prefixes at leaves; auto subtotals |
| Persisted? | Yes (OB58 entity, per CoA) | Yes (Row/Column/Report definitions) | Yes | **No — passed as a plain map at call time** |
| Multiple per book? | Yes (HGB FSV, IFRS FSV, …) | Yes | Yes | Yes (caller chooses) |
| Bound to CoA / ledger? | Tied to chart of accounts | Tied to ledger / CoA | Tied to subsidiary/book | Country tag only; no ledger binding |

kontor's `compute-statement` (`src/kontor/financial_statements.clj`) already nails the **core idea**: a P&L/BS is a *data-defined hierarchy* — sections roll into lines, lines name account-code prefixes, subtotals and a grand total computed, line ordering preserved, point-in-time (BS) vs period (P&L) windowing, bitemporal `:as-of-tx`. That is the FSV/FSG essence. Two gaps versus the enterprise systems, both worth a small ADR in `kontor-asset` or a sibling stage:

1. **The statement definition is not a persisted entity.** SAP/Oracle/NetSuite all persist it (versioned, named, attached to a CoA). kontor passes it as a map. For a *statutory* Jahresabschluss this matters: the FSV used to produce the filed statement is itself audit evidence. Recommendation: a `:statement-definition` entity (code, country, framework, ledger-ref, the section/line tree as nested data or child entities) so a computed statement can cite *which* definition produced it — and so the definition is bitemporally queryable ("what FSV was in force at filing date?"). This composes with the legal-hold/audit story (notes 22–24, ADR-049).
2. **No `:ledger` binding.** A statutory statement is *per accounting framework* — the HGB BS and the IFRS BS read the same CoA but different ledgers (ADR-021). `compute-statement` should take a `:ledger` option and `report.clj`'s account-codes engine should filter by it. This is the single concrete change `kontor-asset` needs from `financial-statements.clj` to make the Anlagenspiegel/Jahresabschluss story real.

The **asset history sheet (Anlagengitter / Anlagenspiegel)** is itself a statutory statement with a *fixed* structure (opening APC → additions → disposals → transfers → write-ups → closing APC → accumulated depreciation → NBV, per asset class). It is best modelled as a *specialized report over `:asset` + `:asset-valuation` + their depreciation `:schedule-occurrence`s* — not as a general `compute-statement` (its rows are asset classes, its columns are movement types — it is genuinely a Row-Set × Column-Set shape, the one place Oracle's FSG model fits better than SAP's single hierarchy).

---

## 7. Anti-patterns — what kontor should NOT copy

1. **SAP's configuration sprawl.** FI-AA is notorious: chart of depreciation, depreciation areas, accounting principles, ledger groups, account-determination keys, screen layouts, number ranges, base/declining/multi-level/period-control/maximum-amount methods all configured *separately* and *cross-dependently* across FI + CO + AA. Consultants call account-assignment-derivation misconfiguration the single most common error class ([SAP Community: account assignment object issues](https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-sap/how-to-avoid-the-most-common-account-assignment-object-issues-in-sap-asset/ba-p/14357197)); S/4HANA asset migration is flagged as a top complexity area ([SAPinsider: SAP Asset Accounting](https://sapinsider.org/topic/sap-finance/sap-asset-accounting/)). **kontor should collapse this to: `:asset` + `:asset-valuation` + `:depreciation-method` + reuse `:ledger`/`:account`/`:schedule`.** No "chart of depreciation," no "accounting principle" indirection layer — the `:ledger` *is* the accounting principle (ADR-021 already made `:ledger/framework` carry `:HGB | :IFRS | …`).
2. **NetSuite's second-class "non-posting" tax methods.** Alternate methods that compute but don't post create a reconciliation gap (the tax valuation lives outside the GL). SAP's S/4HANA fix — *every area posts, every area is equal* — is the right call and matches ADR-021. **Make every `:asset-valuation` post into a real `:ledger`.** Don't build a "tracking-only" tier.
3. **Oracle's copy-and-sync (Mass Copy).** Keeping tax books as *copies* synchronized by a periodic program creates drift, a reconciliation surface, and a "did Mass Copy run?" failure mode. **One `:asset`, N `:asset-valuation` facets** — no copy step.
4. **The FAM-as-bolt-on governance gap.** NetSuite FAM ships functionality but *not* governance — the subledger-to-GL reconciliation "either doesn't happen or relies on a spreadsheet nobody reviews," and disposals/impairments are the top material-weakness cluster ([Nuage: NetSuite Fixed Assets setup auditors find](https://nuagecg.com/blog/netsuite-fixed-assets-the-setup-most-companies-skip-and-auditors-always-find)). kontor's advantage: the asset subledger and the GL are *the same datahike DB* — the `:asset-valuation`'s accumulated depreciation is *derivable from* the `:schedule-occurrence` postings, not a parallel number that can drift. **Design so the asset register's NBV is a query over postings, never a stored field that needs reconciling.**
5. **Calendar-date vs period-date depreciation bugs.** NetSuite FAM's out-of-box monthly reports use *calendar* dates, producing months with zero or double depreciation under non-calendar fiscal years ([Nuage](https://nuagecg.com/blog/netsuite-fixed-assets-the-setup-most-companies-skip-and-auditors-always-find); [Prolecto: 4-4-5 periods and FAM](https://meir.prolecto.com/2021/11/01/solved-4-4-5-accounting-periods-and-fam-reports/)). kontor must tie depreciation periods to the **`:period` entity** (ADR-014), never to raw calendar arithmetic.
6. **Don't bundle country depreciation tables in the kernel.** AfA-Tabellen, MACRS tables, useful-life conventions are *l10n data* — same rule as tax rates (ADR-005). Kernel ships the `:depreciation-method` schema + engine; `kontor-l10n-de` ships the AfA-table-derived method seeds + asset-class defaults.

---

## 8. Concrete recommendations for the `kontor-asset` design

**New entities (3):**
- **`:asset`** — `code` (unique-identity), `name`, `asset-class` (ref), `acquisition-date`, `acquisition-cost` (bigdec) + `acquisition-commodity` (ref), `in-service-date`, `status` (via ADR-034 status-machine: `:planned → :in-service → :fully-depreciated → :disposed`), `owner-partner` (ref, already anticipated in decisions.md line 2236), optional `entity` (ref, ADR-031), `cost-center` (ref → ADR-032 analytic plan).
- **`:asset-valuation`** — the "depreciation area." `asset` (ref), `ledger` (ref → ADR-021 — *this* is the parallel-valuation axis), `depreciation-method` (ref), `useful-life-periods` (long), `salvage-value` (bigdec), `in-service-date` (per-valuation, can differ from the asset's), `apc-account` / `accumulated-depreciation-account` / `depreciation-expense-account` / `gain-loss-account` (refs), `schedule` (ref → ADR-032 — one schedule per valuation), composite-identity tuple `[asset, ledger]`.
- **`:depreciation-method`** — the rule object, shape per §5.
- **`:asset-class`** — *optional* convenience template (defaults a `:depreciation-method` + useful-life *per ledger*); could also be deferred to l10n if the kernel wants to stay minimal. Recommend a thin kernel `:asset-class` (code, name, default-valuation-templates) because SAP/NetSuite/Oracle all converge on it.

**Reuse, don't reinvent:**
- **Depreciation tick = `:schedule` + `:schedule-occurrence`** (ADR-032). One `:schedule` per `:asset-valuation`, `:schedule/kind :depreciation`, `:schedule/origin-entity` → the `:asset-valuation`. The consumer (or a kernel helper `kontor.depreciation`) computes the period amount via the method engine and calls `kontor.schedule/record-occurrence!`. Re-firing a missed period is already idempotent via the `[schedule, sequence]` tuple.
- **Parallel valuation = `:ledger`** (ADR-021). No new "depreciation area" entity. Sum-to-zero stays per-(entity, ledger, commodity).
- **Acquisition / disposal = ordinary `:transaction` + `:posting`s.** Acquisition: `Dr Asset APC / Cr Cash-or-Payable`, fanned per valuation-ledger. Disposal: bring depreciation current (fire the final partial `:schedule-occurrence`), then `Dr Cash + Dr Accumulated-Depreciation / Cr Asset-APC + Cr/Dr Gain-or-Loss` — per valuation, since NBV differs per book.
- **Year-end close = `kontor.closing` + `kontor.period`.** The asset subledger has no separate "fiscal-year-change program" because kontor computes balances cumulatively (closing.clj docstring) — the SAP "open new annual value fields" step is unnecessary in an event-sourced store. What `kontor-asset` *does* need: a **pre-close validation hook** ("all `:schedule-occurrence`s for the period fired? no asset in `:planned` that should be in-service?") plugged into ADR-014's pre-close validation, mirroring SAP's AJAB error-check.

**New kernel code (small):**
- **`kontor.depreciation`** — pure `schedule-amounts` (the 5-family engine + changeover + multi-level rate schedule), `nbv` (net book value = acquisition cost − Σ depreciation occurrences for a valuation, *as a query over postings* — never a stored field, per anti-pattern 4), `next-depreciation-amount`.
- **`kontor.asset`** — register helpers: `acquire!` (asset + valuations + acquisition tx), `depreciate-period!` (fire schedule occurrences across all valuations), `dispose!` (final depreciation + disposal tx per valuation), `asset-history-sheet` (the Anlagenspiegel — a movement schedule over `:asset`/`:asset-valuation`/`:schedule-occurrence`, Row-Set × Column-Set shaped).

**Two changes to existing kernel code (each its own small ADR or folded into the asset ADR):**
- **`financial-statements.clj`/`report.clj` gain a `:ledger` filter option** — so an HGB statement and an IFRS statement read the same CoA across different ledgers. This is the one hard prerequisite for a real Jahresabschluss.
- **Optionally promote the statement definition to a `:statement-definition` entity** — persisted, named, framework/ledger-bound, bitemporally queryable (the FSV/FSG lesson). Lower priority than the `:ledger` filter; can be a follow-up.

**ADR sequencing for the stage:** (1) `:depreciation-method` + the `kontor.depreciation` engine; (2) `:asset` + `:asset-valuation` + `:asset-class` + `kontor.asset` register helpers (depends on 1, ADR-021, ADR-032, ADR-034); (3) `compute-statement` `:ledger` filter + pre-close validation hook for the asset subledger; (4 — optional follow-up) `:statement-definition` persistence. The German showcase (Handelsbilanz area 01 + Steuerbilanz area 15, GWG immediate write-off, degressive AfA with changeover, Anlagenspiegel) is the cross-stage user-story that validates the whole stage.

---

## Sources

- [SAP-press: Depreciation Areas in SAP S/4HANA](https://blog.sap-press.com/depreciation-areas-in-sap-s4hana)
- [SAP-press: Asset Depreciation in SAP S/4HANA](https://blog.sap-press.com/asset-depreciation-in-sap-s4hana)
- [SAP Help: Ledger Approach in Asset Accounting](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/651d8af3ea974ad1a4d74449122c620e/dd214452ab903607e10000000a441470.html)
- [SAP learning: Defining How Depreciation Areas Post to the General Ledger](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-how-depreciation-areas-post-to-the-general-ledger)
- [SAP learning: Defining Depreciation Areas, Keys, Calculation, and Posting](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/defining-depreciation-areas-keys-calculation-and-posting)
- [SAP learning: Explaining the Components of an Asset Class](https://learning.sap.com/courses/configuring-asset-accounting-in-sap-s4hana/explaining-the-components-of-an-asset-class)
- [SAP learning: Executing Year-End Closing](https://learning.sap.com/courses/asset-accounting-year-end-closing-activities/executing-year-end-closing-1-1)
- [SAP learning: Configuring a New Financial Statement Version](https://learning.sap.com/learning-journeys/implementing-record-to-report-in-sap-s-4hana/configuring-a-new-financial-statement-version)
- [SAP Help: Financial Statement Version (FSV)](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/651d8af3ea974ad1a4d74449122c620e/c064c2531bb9b44ce10000000a174cb4.html)
- [SAP Help: Manage Financial Statement Versions (OB58)](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/651d8af3ea974ad1a4d74449122c620e/ee7fd1538cdf4608e10000000a174cb4.html)
- [Skillstek: Methods of Depreciation Calculation in SAP](https://skillstek.com/methods-of-depreciation-calculation-in-sap/)
- [SAP Community: Different Methods of Depreciation Calculation](https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-members/different-methods-of-depreciation-calculation/ba-p/13249758)
- [SAP Community: Depreciation Key - Calculation Method](https://community.sap.com/t5/enterprise-resource-planning-q-a/depreciation-key-calculation-method/qaq-p/9052578)
- [SAP Community: Asset class master problems - default values](https://community.sap.com/t5/enterprise-resource-planning-q-a/asset-class-master-problems-default-values/qaq-p/2614951)
- [SAP Community: How to Avoid Common Account Assignment Object Issues in FI-AA](https://community.sap.com/t5/enterprise-resource-planning-blog-posts-by-sap/how-to-avoid-the-most-common-account-assignment-object-issues-in-sap-asset/ba-p/14357197)
- [SAPinsider: SAP Asset Accounting](https://sapinsider.org/topic/sap-finance/sap-asset-accounting/)
- [sap-f5: Fiscal Year Change (AJRW) & Year-End Closing (AJAB)](http://sap-f5.blogspot.com/2009/08/fiscal-year-change-ajrw-year-end.html)
- [docplayer: Implementierung und Customizing einer Anlagenbuchhaltung am Beispiel des SAP Moduls FI-AA](https://docplayer.org/7902078-Implementierung-und-customizing-einer-anlagenbuchhaltung-am-beispiel-des-sap-moduls-fi-aa.html)
- [erplingo: What is tax depreciation in SAP FI-AA?](https://www.erplingo.com/sap-glossary/en/fi-aa/tax-depreciation)
- [Haufe: Geringwertige Wirtschaftsgüter (GWG)](https://www.haufe.de/thema/geringwertiges-wirtschaftsgut/)
- [steuern.de: Geringwertige Wirtschaftsgüter (GWG) absetzen](https://www.steuern.de/abschreibung-gwg)
- [Oracle docs: NetSuite Asset Depreciation](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2158182.html)
- [Oracle docs: NetSuite Creating Alternate Methods (Tax Depreciation Methods)](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2140809.html)
- [Oracle docs: NetSuite Using the Period Close Checklist](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N1455781.html)
- [Oracle docs: NetSuite Accounting Book Period Close Management](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_4308385929.html)
- [Houseblend: NetSuite FAM Depreciation Setup, Methods & Common Errors](https://www.houseblend.io/articles/netsuite-fixed-assets-depreciation-setup-methods)
- [theledgerlabs: 7 Steps for Easy NetSuite Fixed Assets Management](https://theledgerlabs.com/netsuite-fixed-asset-management/)
- [VNMT Solutions: NetSuite Fixed Asset Management Guide](https://www.vnmtsolutions.com/netsuite-fixed-asset-management/)
- [Nuage: NetSuite Fixed Assets — The Setup Most Companies Skip](https://nuagecg.com/blog/netsuite-fixed-assets-the-setup-most-companies-skip-and-auditors-always-find)
- [Prolecto: 4-4-5 Accounting Periods and Fixed Asset Management (FAM) Reports](https://meir.prolecto.com/2021/11/01/solved-4-4-5-accounting-periods-and-fam-reports/)
- [Oracle docs: Asset Books (Chapter 3) 21A](https://docs.oracle.com/en/cloud/saas/financials/21a/faias/asset-books.html)
- [Oracle docs: Using the Financial Statement Generator](https://docs.oracle.com/cd/A60725_05/html/comnls/us/gl/fsgover.htm)
- [Microsoft Learn: Dispose or retire a fixed asset (generic disposal accounting, cross-vendor corroboration)](https://learn.microsoft.com/en-us/dynamics365/business-central/fa-how-dispose-retire)

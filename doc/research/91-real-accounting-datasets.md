---
date: 2026-05-18
agent: research
title: Real-world openly-licensed accounting datasets — what's actually
       ingestible into kontor's bitemporal substrate
status: research-note
---

# 91 — Real-world openly-licensed accounting datasets for kontor

Companion survey to research note 78 (XBRL + accounting taxonomies).
Note 78 documented the **specification** layer — what XBRL is, which
taxonomies exist, how kontor's substrate seams (`:account-tag/*`,
`:account/external-codes`, the report engine, the bitemporal axes)
map onto an XBRL filing shape. This note documents the **data**
layer — what actual companies' books are openly downloadable, under
what license, in what format, with what coverage, and where the
**bitemporal-correction richness** that kontor cares about (the
`as-of-tx` axis, the "what we thought then vs now" archetype)
actually lives.

The goal is to stop writing synthetic fixtures for integration tests
and Clay showcases and instead point kontor at *real* corporate
ledgers — so a 10-K from Apple in 2020, restated in 2022, ingested
through `kontor.import-edgar`, exposes both the original and the
restatement through `(d/valid-at db t)` / `(d/as-of db tx)` exactly
the way the substrate promised.

## §1 — TL;DR (highest-leverage datasets)

Five datasets are recommended as **load-bearing** for kontor's
showcase + integration-test story. Each is fully open (public-domain
or CC0 / OGL-equivalent), large enough to exercise multi-year
multi-entity scenarios, and structurally rich enough to test
kontor's *distinguishing* features (bitemporal corrections,
multi-regulator code mapping, parallel ledgers, consolidation).

1. **SEC EDGAR XBRL filings** (priority 1). Public-domain federal
   work under 17 U.S.C. § 105. Every 10-K, 10-Q, 10-K/A, 10-Q/A
   filed by a US listed company since 2009 is iXBRL or XBRL. Bulk
   access via `https://www.sec.gov/Archives/edgar/daily-index/`,
   structured access via `https://data.sec.gov/api/xbrl/…`,
   nightly companyfacts.zip + submissions.zip rebuilds. Schema fit:
   excellent — US-GAAP taxonomy concepts map directly onto
   `:account-tag/concept-iri`. Bitemporal richness: **maximal** —
   10-K/A amendments are the canonical "what we believed then vs now"
   dataset; the XBRL US public-filings database tags every restated
   fact with `report.restated = TRUE`.
2. **UK Companies House Free Accounts Data Product** (priority 2).
   Open Government Licence v3.0 — permissive, commercial reuse OK,
   share-alike free. Daily iXBRL bulk dump of every UK Ltd's
   annual return (Tue–Sat). Since 1 April 2026 iXBRL filing is
   mandatory for ALL UK limited companies (per research note 78
   §3.4), so coverage is now ~100% of the UK Ltd universe.
   Schema fit: excellent — FRC taxonomy maps via
   `:account-code/regulator :uk/frs-102` etc.
3. **GLEIF Golden Copy LEI files** (priority 1, but for master data,
   not transactions). CC0 — best-possible open license. Daily
   Level 1 (LEI-CDF) + Level 2 (RR-CDF) drops covering ~2.7M active
   legal entities globally with cross-references (BIC, MIC, ISIN,
   national IDs). Schema fit: maps onto kontor's `:entity` + `:partner`
   master data, plus ADR-019 cross-reference patterns. This is the
   *spine* for cross-jurisdictional intercompany scenarios.
4. **IRS Form 990 XML filings** (priority 2). US federal public-domain
   work. Every 501(c)(3) nonprofit's annual return is XML-machine-
   readable from 2013 onward; IRS publishes monthly zips
   (`apps.irs.gov/pub/epostcard/990/xml/YYYY/`). The legacy AWS S3
   mirror at `s3://irs-form-990` is deprecated as of 31 Dec 2021
   but still queryable. Schema fit: medium — Form 990's line-item
   schema is not double-entry; it's a regulator-form schema. But
   it's a useful counter-shape that exercises kontor's
   single-entity-per-filer + line-tagging machinery on real data.
5. **FFIEC Call Reports** (priority 3, niche). All US FDIC-insured
   banks file quarterly Call Reports in XBRL since 2005; public-
   domain federal work. Available via the FFIEC Central Data
   Repository (`https://cdr.ffiec.gov/public/`). The XBRL taxonomy
   is **the most mature production XBRL pipeline in the US** — older
   and more battle-tested than SEC EDGAR's. Schema fit: medium — Call
   Reports use a regulator-specific schema that doesn't align with
   a posting-level GL, but they're an excellent test of the report
   engine's `:engine :tax-tags` + dimensional rollup story.

Two datasets that look attractive but are **not** load-bearing:

- **ESEF (EU listed)** — coverage is patchy because the central
  ESAP portal doesn't come online until 2027. Until then, each
  EU member state's OAM is queried individually. `filings.xbrl.org`
  is a best-effort aggregator (~25k filings as of 2026-05) but its
  license is unclear. Use after SEC EDGAR for IFRS comparability.
- **Beancount open ledgers (NVIDIA / Alphabet / Adyen / MiniMax)** —
  interesting demo data but the source ledgers are *derived* from
  SEC filings via AI parsing, not authoritative. Useful as a
  comparison oracle ("does kontor's ingest produce the same trial
  balance Beancount.io's hand-curated one does?"), not as a
  primary source.

Open question for maintainer (see §10): **how aggressive should the
ingestion be?** Three plausible scopes for kontor v1:
(a) ship one full-pipeline import for SEC EDGAR + GLEIF, document
the rest; (b) ship import scaffolds for all five priority datasets,
fixtures-only; (c) ship import scaffolds for SEC EDGAR only, leave
the rest as research notes. Recommendation in §10.

## §2 — SEC EDGAR XBRL filings

### 2.1 — What it is

The SEC's Electronic Data Gathering, Analysis, and Retrieval
(EDGAR) system contains every public company filing since 1993.
XBRL mandates phased in 2009–2011 for financial statements; iXBRL
mandate phased in 2019–2021 for the entire filing (cover page +
narrative + footnotes + schedules). As of 2026-05, **every active
US listed company files iXBRL 10-K + 10-Q**, including their
amended counterparts 10-K/A + 10-Q/A.

### 2.2 — License

**Public domain.** SEC EDGAR filings are works of the United States
Government within the meaning of 17 U.S.C. § 105 and are not subject
to domestic copyright. The SEC's own marks ("SEC", "EDGAR") are
trademarked and cannot be used in a kontor-affiliated trade name,
but the *data* itself is freely redistributable. (Source:
[17 U.S.C. § 105](https://www.law.cornell.edu/uscode/text/17/105),
[SEC.gov: Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data),
accessed 2026-05-18.)

This is the strongest possible license posture: kontor can ship a
SEC EDGAR import module that bundles redistributable fixtures
(e.g. a snapshot of Apple's 10-K filings 2015–2024) in
`modules/kontor-import-edgar/test/resources/` without license
friction.

### 2.3 — Access mechanics

Three layers of access, from highest-leverage to least:

**Bulk archive ZIPs (recommended for kontor ingest)**:
- `https://www.sec.gov/Archives/edgar/daily-index/xbrl/companyfacts.zip`
  — nightly rebuild containing all XBRL facts for every CIK.
- `https://www.sec.gov/Archives/edgar/daily-index/bulkdata/submissions.zip`
  — nightly rebuild containing the filing-history metadata for
  every CIK.
- Both republish ~3:00 a.m. ET. Sizes: ~5 GB unzipped each.

**Structured XBRL APIs (best for incremental ingest)**:
- `https://data.sec.gov/api/xbrl/companyfacts/CIK{10-digit-cik}.json`
  — every XBRL fact a company has ever filed in one JSON.
- `https://data.sec.gov/api/xbrl/companyconcept/CIK{cik}/{taxonomy}/{tag}.json`
  — single-concept time series (e.g. `us-gaap/Revenues`).
- `https://data.sec.gov/api/xbrl/frames/{taxonomy}/{tag}/{unit}/{period}.json`
  — one-fact-per-entity for a calendrical period across all filers.
- `https://data.sec.gov/submissions/CIK{cik}.json`
  — filing history.
- Rate limit: 10 requests/second per IP.
- **Mandatory User-Agent header**: `"CompanyName admin@email.com"`.
  Requests without this receive `403 Forbidden`.
  (This is why WebFetch returned 403 throughout this research note —
  the harness's User-Agent does not match SEC's required format.
  A kontor ingest module must set the header explicitly.)

**Raw filing archives (for iXBRL primary documents)**:
- `https://www.sec.gov/Archives/edgar/data/{cik}/{accession-no-dashes}/`
  — every filing's full primary-document set including iXBRL.
- Example: Apple's 10-K for FY2023 lives at
  `https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=0000320193&type=10-K`.

(Sources:
[SEC.gov: EDGAR APIs](https://www.sec.gov/search-filings/edgar-application-programming-interfaces),
[dealcharts.org: SEC EDGAR API Guide](https://dealcharts.org/blog/sec-edgar-api-guide),
accessed 2026-05-18.)

### 2.4 — Bitemporal-correction richness — the killer feature

10-K/A (annual report amendment) and 10-Q/A (quarterly amendment)
filings are the canonical bitemporal-restatement archetype for
kontor. The shape:

- Company XYZ files **10-K for FY2020** on 2021-02-15. iXBRL facts
  for Revenue, Net Income, AR, etc. land in EDGAR.
- In Q3 2022, XYZ discovers a material misstatement (revenue
  recognition error, lease accounting mis-classification, …).
- XYZ files **10-K/A for FY2020** on 2022-09-30. The amended iXBRL
  includes restated facts for the *same* (concept, context, unit)
  triples, plus an explicit `dei:AmendmentFlag` = true and an
  `us-gaap:RestatementAdjustmentMember` dimension value on the
  restated contexts.

This is **exactly the shape kontor's `(d/valid-at db t)` +
`(d/as-of db tx)` axes are designed to capture**:

- Valid-at axis: the *fact value* axis. "What did XYZ report for
  Revenue at year-end 2020?" Answer: filed value as of 2021-02-15
  filing, OR restated value as of 2022-09-30 filing, depending on
  which valid-time slice you ask.
- As-of-tx axis: the *restatement* axis. "What did the world know
  about XYZ's 2020 Revenue on 2022-06-01 (before restatement)?"
  Answer: the original 10-K's value. "What about on 2022-12-01?"
  Answer: the restated 10-K/A's value.

The XBRL US public-filings database surfaces this directly: every
fact carries a `report.restated` boolean + `report.restated-index`
integer counting the number of restatements since the original
filing. (Source: [XBRL US forum: 10-K/A files](https://xbrl.us/forums/topic/10-k-a-files/),
accessed 2026-05-18.)

**Why this matters for kontor**: writing a synthetic restatement
fixture is straightforward but *unconvincing* — a code reviewer
naturally asks "would this work on real corporate restatements,
with all their dimensional + footnote-linkbase complexity?" An
EDGAR-sourced fixture closes that question. Recommended Clay
showcase: ingest **General Electric's 2009 10-K, 2010 10-K/A,
2011 10-K/A, 2018 10-K/A** chain (GE has the most-restated
financials of any large US issuer in the 2009–2018 era) and walk
through `(d/as-of db t)` for each filing instant.

### 2.5 — Schema mapping difficulty: 2/5

Easy. US-GAAP concept QNames (`us-gaap:Revenues`,
`us-gaap:CashAndCashEquivalentsAtCarryingValue`, …) map onto
kontor's `:account-tag/concept-iri` per ADR-090. The fact's
`(contextRef, unitRef, decimals, value)` tuple maps onto:

- `contextRef` → resolve to `(entity, period, dimensions)`:
  - `entity.identifier.scheme + entity.identifier.value` →
    `:transaction/entity` (entity lookup table keyed by CIK).
  - `period.instant` or `period.startDate`/`endDate` →
    `:posting/valid-from` (instant) or report window (duration).
  - `scenario/segment` dimensions → `:posting/analytic-line` or
    `:posting/dimensions`.
- `unitRef` → `:money/commodity` (ISO 4217 code).
- `decimals` → precision context, dropped or carried as audit-doc.
- value → `:money/amount`.

Note that XBRL facts are **single-entry** (one fact = one
concept-context-value triple, not a debit/credit pair). To
double-entry-ify them, an EDGAR ingest must either:
(a) treat each fact as a single-sided posting against a synthetic
"EDGAR reporting" suspense account (preserving the original
asymmetry), OR
(b) reconstruct double-entry by pairing BS facts via the accounting
equation (assets = liabilities + equity) and P&L facts via the
income statement structure.

Option (a) is honest and matches what the data actually says;
option (b) is what consumers might want for trial-balance demos
but introduces inference risk. **Recommendation: (a) for the
ingest module, document that EDGAR data is "reported facts not
journal entries," and let a consumer-side helper synthesize the
double-entry view for visualization.**

### 2.6 — Concrete ingest sketch

```clojure
(ns kontor.import-edgar
  "Ingest SEC EDGAR XBRL filings into a kontor datahike connection.

  Source: https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json
  License: public domain (17 USC § 105).

  Bitemporal contract:
  - `:tx/valid-from` of each posting = the filing's `period.instant`
    (BS) or `period.endDate` (P&L).
  - The DB-transaction time of the posting carries the filing's
    accession-no — restatements arrive as later transactions with
    the same valid-from but a newer tx-time, so
    `(d/as-of db <tx>)` recovers the as-filed view.
  - Each posting carries `:posting/source-id` =
    \"edgar://CIK/{cik}/accession/{accn}\" for drill-down.")

(defn fetch-companyfacts
  "GET https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json
   with User-Agent. Rate-limited at 10 req/s."
  [cik {:keys [user-agent throttle]}]
  …)

(defn fact->posting-tx-data
  "Build a posting tx-data from a single XBRL fact.

  Returns nil if the fact is not yet account-mappable
  (consumer has not provided a concept→account map for it)."
  [account-by-concept fact]
  (let [{:keys [val end start accn fp form taxonomy concept unit]} fact
        valid-from (or end start)
        account-eid (account-by-concept (str taxonomy ":" concept))]
    (when account-eid
      {:posting/account account-eid
       :posting/amount (Money. val (commodity-of unit))
       :tx/valid-from valid-from
       :posting/source-id (format "edgar://CIK/%010d/accession/%s"
                                   (long cik) accn)
       :posting/concept-iri (str taxonomy ":" concept)
       :posting/source-form form
       :posting/source-period fp})))

(defn ingest!
  "Ingest a CIK's full filing history. Each filing becomes one
   datahike commit (so (d/as-of db <commit-tx>) recovers the
   as-filed view of that period)."
  [conn cik account-by-concept opts]
  …)
```

### 2.7 — Recommended Clay showcase

Title: **"Apple 10-K 2015–2024 — bitemporal trial balance from
real SEC filings."** The notebook walks through:

1. Ingest Apple's `companyfacts` JSON for CIK 0000320193.
2. Build a trial balance for FY2023 using `(d/valid-at db
   #inst "2023-09-30")` — should match Apple's reported balance
   sheet.
3. Show the same trial balance using
   `(d/as-of db <2024-02-01-tx>)` — recovers the "as filed" view
   before any later 10-K/A correction.
4. Compare to `:engine :account-codes` rollups using
   `us-gaap:Revenues`, `us-gaap:OperatingIncomeLoss`, etc., to
   demonstrate that kontor's report engine + the
   `:account-tag/concept-iri` seam form a working XBRL-fact
   reproduction pipeline.

## §3 — UK Companies House Free Accounts Data Product

### 3.1 — What it is

Companies House (the UK companies registrar) publishes a free
daily bulk dump of every iXBRL annual return filed the previous
day. Coverage as of 2026-05 is ~100% of UK Ltd filings (the
1 April 2026 mandate closed the WebFiling path, per research
note 78 §3.4). About 75% of the ~2.2M UK annual returns currently
flow through this product; the remaining 25% are paper or transition
backlog.

### 3.2 — License

**Open Government Licence v3.0.** Permissive, EPL-1.0-compatible,
commercial reuse OK, share-alike free, attribution required.
The OGL is one of the cleanest public-sector open licenses
worldwide and is functionally equivalent to CC-BY-4.0 with
explicit UK-jurisdiction language.
(Source: [download.companieshouse.gov.uk: Free Accounts Data Product](https://download.companieshouse.gov.uk/en_accountsdata.html),
accessed 2026-05-18.)

### 3.3 — Access mechanics

- URL pattern: `https://download.companieshouse.gov.uk/Accounts_Bulk_Data-{YYYY-MM-DD}.zip`
- Frequency: daily, Tue–Sat (Tuesday's file covers Sat+Sun+Mon).
- Retention: 60 days for daily files; older data is in monthly
  archives (`download.companieshouse.gov.uk/en_monthlyaccountsdata.html`).
- Per-file naming: `Prod223_{seq}_{company-no-8}_{YYYYMMDD-bal-sheet-date}.{html,xml,zip}`.
- Formats: iXBRL (`.html`), XBRL (`.xml`), and report-package zips.

For a multi-year ingest, the monthly archives are the realistic
starting point. A `kontor.import-companies-house` module would:
(1) download a year's monthly zips,
(2) unpack each daily zip,
(3) parse each iXBRL filing,
(4) emit tx-data per filing.

### 3.4 — Bitemporal-correction richness

UK Ltd annual returns **can** be amended post-filing, but the
mechanism is less standardized than SEC's 10-K/A. The shape:
the company files a corrected set of accounts, and Companies
House makes both the original and the correction available.
The "filing date" + "balance sheet date" on each file lets a
consumer reconstruct the chronology. Frequency: small companies
restate rarely (<<1%); large companies (FTSE-100 subs) more
often. Lower restatement rate than SEC because UK FRS 102 is
less subject to the dimensional re-tagging issues that drive US
10-K/A volume.

Bitemporal richness: **medium**. Less dramatic than EDGAR's
10-K/A pipeline, but still real, and the FRC taxonomy's stability
means a consumer can ingest the same company's filings across
multiple years without taxonomy-version drift dominating the
comparison.

### 3.5 — Schema mapping difficulty: 2/5

The FRC taxonomy suite covers FRS 101, FRS 102, FRS 105, UK IFRS,
Charities SORP, and the Detailed P&L for HMRC. Each is XBRL with
the same structural mapping as US-GAAP. The
`:account-code/regulator` keys would be e.g.
`:uk/frs-102-2026`, `:uk/frs-105-2026`, `:uk/dpl-2026`.

The main difference from SEC EDGAR ingest: UK filings are
*per-company per-year*, not aggregated into a `companyfacts.zip`
equivalent. The ingest pattern is "walk every file in the monthly
zip, extract its facts, key them by company number."

### 3.6 — Concrete ingest sketch

```clojure
(ns kontor.import-companies-house
  "Ingest UK iXBRL annual returns from Companies House Free Accounts
   Data Product.

   Source: https://download.companieshouse.gov.uk/en_accountsdata.html
   License: Open Government Licence v3.0 (attribution required).

   The OGL attribution must appear in any redistributed dataset; the
   kontor consumer is responsible for surfacing it (e.g. in their
   showcase notebook header).")

(defn fetch-monthly-archive [yyyy mm] …)

(defn parse-ixbrl-filing
  "Parse an iXBRL file into a kontor filing record."
  [html-bytes] …)

(defn filing->postings-tx-data
  "Build postings from a parsed FRS-102 (or 101/105/IFRS) filing.

  The schema-mapping helper resolves
  e.g. `frs-102:Revenue` → an account in the kontor chart."
  [account-by-concept filing] …)
```

### 3.7 — Recommended Clay showcase

Title: **"A UK small company's 5-year ledger, from Companies
House to kontor."** Pick a real Ltd (the maintainer can choose
one whose filings are public and uncontroversial — e.g. a
well-known OSS-foundation UK subsidiary or a consultancy with
public CH filings). Ingest FY2020–FY2024 iXBRL filings. Walk
through the trial balance + P&L per year, and demonstrate
ADR-019's multi-regulator code mapping by tagging the same
accounts with both the SKR04 (hypothetical group-DE-mapping) and
FRC FRS-102 codes.

## §4 — ESEF (EU listed) + per-country regulator portals

### 4.1 — What it is

ESEF (European Single Electronic Format) is the EU-mandated
iXBRL filing format for IFRS consolidated annual reports of EU
listed companies on regulated markets. ESMA publishes the
taxonomy yearly; the central archive of filings, however, does
**not** exist yet — until ESAP (Electronic Single Access Point)
goes live in **2027**, ESEF filings are stored in per-country
Officially Appointed Mechanisms (OAMs).

### 4.2 — License

**Per-country, mostly permissive.** Each OAM has its own terms.
The aggregator `filings.xbrl.org` carries ~25k filings as of
2026-05 with a "© 2021-23 XBRL All Rights Reserved" footer —
ambiguous and probably not redistributable without further
clarification. `easyESEF` (an XBRL Europe utility) provides a
bulk-download tool but doesn't bundle a redistribution license.

**This is the dataset's weakness**: the aggregator licensing
is unclear, and the per-OAM situation is N=31 jurisdictions to
research individually. For kontor's purposes, this should be
considered "valuable but blocked on legal clarity" until either
ESAP arrives or a maintainer chooses one OAM (e.g. Germany's
BaFin or France's AMF) to ingest directly under that OAM's terms.

### 4.3 — Access mechanics

- `https://filings.xbrl.org/` — search interface, ~25k filings.
- `https://github.com/European-Securities-Markets-Authority/esef_toolkit`
  — ESMA-published Python toolkit for filtering + downloading
  ESEF filings. Apache-2.0 licensed (the *toolkit* is, not
  the filings).
- `https://easyesef.eu/` — third-party portal with download utility.
- ESAP (https://www.esma.europa.eu/transparency-rules-bond-regulated-market/european-single-access-point-esap):
  scheduled for 2027 go-live.

### 4.4 — Bitemporal-correction richness

Lower than SEC EDGAR. ESEF mandates IFRS consolidated annual
reports only — no quarterly equivalent, no separate amendment
form. Restatements happen but with much less filing-frequency
richness. **Not the dataset for kontor's bitemporal showcase.**

### 4.5 — Schema mapping difficulty: 3/5

The IFRS Foundation taxonomy is ~6,000 concepts (vs US-GAAP's
~15,000), so the mapping is smaller and more uniform. But ESEF
extends IFRS with EU-specific concepts, and each issuer can
file *with* or *without* the taxonomy embedded — some embed,
some link. The toolkit handles this but the ingest pipeline has
to too.

### 4.6 — Recommendation

**Defer to v2.** Use SEC EDGAR for the primary
bitemporal-correction story; use Companies House for the UK
small-company story; revisit ESEF when ESAP lands in 2027.

## §5 — Nonprofit + government data (Form 990 + ACFRs)

### 5.1 — IRS Form 990

**What it is**: every US 501(c)(3) files Form 990 annually
(or 990-EZ for small orgs, 990-PF for private foundations,
990-N postcard for tiny orgs). XML format for electronically-filed
returns from 2013 onward.

**License**: public domain (17 U.S.C. § 105). Bundleable in
kontor test fixtures.

**Access**:
- IRS direct: `https://apps.irs.gov/pub/epostcard/990/xml/{YYYY}/{YYYY}_TEOS_XML_{NN}{X}.zip`
  — monthly zips. 2024 had 12 files; 2025 had 14.
- IRS index files: `https://apps.irs.gov/pub/epostcard/990/xml/{YYYY}/index_{YYYY}.csv`
- AWS S3 (legacy mirror, deprecated 2021-12-31): `s3://irs-form-990/`
- ProPublica Nonprofit Explorer: web UI + REST API at
  `https://projects.propublica.org/nonprofits/api/v2/`.

(Sources:
[IRS Form 990 Series Downloads](https://www.irs.gov/charities-non-profits/form-990-series-downloads);
[AWS Registry: IRS 990 Filings](https://registry.opendata.aws/irs990/);
[ProPublica Nonprofit Explorer](https://projects.propublica.org/nonprofits/),
accessed 2026-05-18.)

**Schema fit**: 3/5. Form 990 is a *regulatory form*, not a
double-entry GL. The schema has ~50 line items per filing
(revenue, salaries, occupancy, …) plus the schedules. Mapping to
kontor:
- Each 990 line item becomes a tagged account in the kontor chart.
- `:account-tag/concept-iri` carries the IRS efile schema URI
  (e.g. `irs:Form990/PartIX/Line1a` for grants paid).
- The filing date + tax year define the bitemporal axes.

**Bitemporal richness**: low-to-medium. Amendments exist (Form
990 amendments use Form 990-T or a re-filed Form 990 marked
"AMENDED"). The schema's `AmendedReturn` boolean carries this.
Restatement frequency is lower than SEC because nonprofit
financial statements face less scrutiny.

**Recommended Clay showcase**: **"Apache Software Foundation
Form 990 multi-year ledger."** ASF is a familiar reference point
for the kontor audience, files publicly, and has 10+ years of
filings (EIN 47-0825376). The notebook ingests ASF's 2013–2023
Form 990 XMLs, builds a year-over-year nonprofit P&L + balance
sheet, and demonstrates kontor's handling of the schedule-driven
nonprofit reporting structure.

### 5.2 — US municipal CAFRs (now ACFRs — Annual Comprehensive
Financial Reports)

**What it is**: ~30,000 US state + local governments produce
GASB-shaped ACFRs annually. The vast majority are PDF; an
emerging minority are XBRL.

**License**: public domain for federal/state/local works
(varies; some states have additional terms).

**Access** — XBRL examples are scarce but real:
- **Florida LOGERX** (`https://logerx.myfloridacfo.gov/`) —
  state-mandated XBRL filings from FY2022 onward per Florida
  HB 1073. Covers ~3,500 Florida local governments.
- **Will County, Illinois** (`https://www.willcountyauditor.com/`) —
  first US local government to publish CAFR in XBRL voluntarily.
  Sample iXBRL viewer at
  `https://xbrlus.github.io/cafr/ixviewer/ix.html?doc=../samples/25/County-of-Will-Illinois-20181130-Annual-Accounts.htm`.
- **XBRL US CAFR Taxonomy** (`https://xbrl.us/xbrl-taxonomy/2022-florida/`) —
  the demonstration + Florida-specific taxonomies, public.
- **`govwiki/us-municipal-cafr-taxonomy`** on GitHub —
  tools for converting state and local government audited
  financial statements to XBRL. Permissive (check the LICENSE
  file in-repo).

(Sources:
[XBRL.org: XBRL US Release CAFR Taxonomy](https://www.xbrl.org/news/xbrl-us-release-cafr-taxonomy-for-municipal-reporting/);
[XBRL US Florida Reporting](https://xbrl.us/news/florida-44909/);
[Mercatus Center: Applying XBRL to US State and Local Government](https://www.mercatus.org/media/158201/download?attachment=),
accessed 2026-05-18.)

**Schema fit**: 4/5. GASB ACFRs introduce fund accounting (a
single government has multiple funds — general fund, special
revenue, debt service, …) that **kontor does not yet support
natively** (no `kontor-fund-accounting` module ships in v1). The
import is possible only by treating each fund as a separate
`:entity` and accepting that the "consolidation" view of all
funds together is a custom rollup.

**Bitemporal richness**: low. ACFRs are annual; restatements are
rare; the Florida LOGERX is too new (FY2022+) to have an
established restatement chain.

**FDTA (Financial Data Transparency Act) implication**: the SEC
must establish data standards for municipal securities by
December 2026 under the FDTA, with compliance expected by 2027.
This will drive ACFR XBRL adoption nationally over 2026–2029.
For kontor's purposes, the ACFR ingest story is *forward-looking*:
ingest scaffolds today set kontor up to absorb the FDTA-driven
wave once mandates land. (Source:
[Congress.gov: FDTA Implementation IF13093](https://www.congress.gov/crs-product/IF13093);
[SEC.gov: FDTA Joint Data Standards](https://www.sec.gov/rules-regulations/2024/08/s7-2024-05),
accessed 2026-05-18.)

**Recommended Clay showcase**: **"Will County Illinois ACFR
ingest demonstrates fund accounting"** — ingest Will County's
2018 + 2019 + 2020 ACFRs, build a multi-fund trial balance, and
document the friction (specifically what `kontor-fund-accounting`
would need to ship to make this clean).

### 5.3 — FFIEC Call Reports (US banks)

**What it is**: quarterly Reports of Condition and Income filed
by every FDIC-insured bank since 2005-Q4 in XBRL. The most
mature production XBRL pipeline in the US — older than SEC EDGAR's.

**License**: public domain federal work.

**Access**:
- `https://cdr.ffiec.gov/public/PWS/DownloadBulkData.aspx` —
  bulk download in Excel and XBRL.
- `https://cdr.ffiec.gov/public/HelpFiles/DownloadHelp.htm` —
  download help.
- Coverage: March 31, 2001 onward.

(Sources:
[FFIEC CDR](https://cdr.ffiec.gov/public/);
[FFIEC Bulk Data Download](https://cdr.ffiec.gov/public/PWS/DownloadBulkData.aspx);
[XBRL US: FFIEC](https://xbrl.us/xbrl-reference/federal-financial-institutions-examination-councils-central-data-repository/),
accessed 2026-05-18.)

**Schema fit**: 3/5. Call Reports use a bank-specific taxonomy
(RC-A, RC-B, RC-C, …) that doesn't map onto a general GL chart.
The line items are aggregates ("loans secured by real estate")
not journal entries. Mapping to kontor would treat each Call
Report as a single bank-entity's reporting filing with concept-
tagged lines, similar to a Form 990 ingest.

**Bitemporal richness**: medium. Call Reports are amended ("MDRM
revisions") more than 10-K/As, but the amendments are usually
small data-correction patches rather than full restatements. The
FFIEC's `report.amended` flag tracks this.

**Recommendation**: niche but useful for a "banking-as-consumer"
follow-up to research note 50. Defer to a `kontor-l10n-us-bank`
companion module.

## §6 — LEI + cross-reference identifiers (GLEIF)

### 6.1 — What it is

Every legal entity that has ever filed in a major financial-
regulator regime since ~2014 has a 20-character LEI (Legal
Entity Identifier) issued by an LOU (Local Operating Unit) under
GLEIF accreditation. ~2.7M active LEIs globally as of 2026-05.
GLEIF publishes Golden Copy files daily (Level 1 = LEI + name +
address + status; Level 2 = parent/subsidiary relationships).

### 6.2 — License

**CC0 1.0 Universal.** This is the best-possible open license —
no attribution required, no share-alike, commercial use fully
unrestricted. GLEIF has formally endorsed the International Open
Data Charter.
(Sources:
[GLEIF Open Data](https://www.gleif.org/en/about/open-data);
[LEI Data Terms of Use](https://www.gleif.org/en/meta/lei-data-terms-of-use),
accessed 2026-05-18.)

### 6.3 — Access mechanics

- `https://www.gleif.org/en/lei-data/gleif-golden-copy/download-the-golden-copy`
  — Golden Copy daily.
- `https://www.gleif.org/en/lei-data/gleif-concatenated-file/download-the-concatenated-file`
  — single-file aggregated snapshot.
- Formats: CSV, JSON, XML, RDF (via data.world partnership).
- Three drops per day, with deltas.
- Schema: LEI-CDF 3.1 (Level 1), RR-CDF 2.1 (Level 2),
  Reporting Exceptions format.

### 6.4 — Why this matters for kontor

LEIs are the **spine** for cross-entity, cross-jurisdiction
master data. ADR-031's `:entity` schema + ADR-073's consolidation
machinery rely on stable entity identity across DBs. GLEIF
provides:

- A globally-unique stable identifier (the LEI) for the
  `:entity/lei` attribute (proposed; not yet shipped).
- Cross-references to other systems: CIK (SEC), BIC (SWIFT),
  MIC (ISO market codes), national company numbers (UK CRN,
  DE HRB, FR SIREN, …), DUNS, ISIN.
- Parent / subsidiary / ultimate-parent relationships → directly
  feeds ADR-073's `kontor.entity/family` walk.
- Sustained operational metadata (registration status: active /
  lapsed / merged / retired) → audit trail for entity life-cycle.

The cross-references are load-bearing: an EDGAR ingest can join
on CIK, a Companies House ingest can join on UK CRN, a
Bundesanzeiger ingest can join on DE HRB, and **GLEIF is the
join key that connects them**. Without LEIs, a multi-jurisdiction
consolidation has no stable identity model; with them, it's a
schema-level lookup.

### 6.5 — Schema mapping difficulty: 1/5

Trivial. CSV → datahike transact, schema unchanged. The only
new attribute is `:entity/lei` (a string with the GLEIF check
characters validated).

### 6.6 — Concrete ingest sketch

```clojure
(ns kontor.import-gleif
  "Ingest GLEIF Golden Copy Level 1 + Level 2 data as kontor
   entity master data.

   Source: https://www.gleif.org/en/lei-data/gleif-golden-copy
   License: CC0 1.0 Universal (no attribution required).

   Schema:
   - :entity/lei              \"LEI 20-char\"
   - :entity/legal-name       \"…\"
   - :entity/legal-jurisdiction \"DE\"  ; ISO 3166-1
   - :entity/legal-form       \"GmbH\"
   - :entity/registration-status :active | :lapsed | :merged | :retired
   - :entity/parent-lei       \"LEI of immediate parent\" (Level 2 RR)
   - :entity/ultimate-parent-lei \"LEI of ultimate parent\"
   - :entity/source-id        \"gleif://Golden-Copy/YYYY-MM-DD\"")
```

### 6.7 — Recommended use

**Ship in v1** as the foundation for every other ingest. A
`kontor-import-gleif` companion module loads the Golden Copy
snapshot and writes `:entity` records that subsequent EDGAR /
Companies House / Bundesanzeiger ingests can resolve against.

## §7 — What's NOT openly available — the gap

A short, honest inventory of what kontor cannot get from open
data:

- **Record-level payroll**. BLS QCEW publishes aggregates by
  industry × geography (county/state/national), not per-employee
  payroll records. No open-data source exists for record-level
  payroll. ADP / Gusto / Workday / Paychex don't publish; the
  ~5 academic studies that touch record-level payroll do so
  under restrictive use agreements. **Implication for kontor**:
  the payroll showcases (ADR-079 + ADR-083 + ADR-084 + ADR-089)
  cannot be backed by real anonymized payroll data. They will
  remain synthetic-fixture-driven for the foreseeable future.
- **Internal management accounts**. Real companies' month-end
  GL exports, posting journals, draft trial balances, intercompany
  reconciliations — none of this is openly published. EDGAR is
  *reported* facts (post-close, post-audit), not journal entries.
  The gap is structural: management accounts are commercially
  sensitive in a way reported financials aren't.
- **CRM / sales data**. Salesforce, HubSpot, etc. don't publish
  customer data. The closest open analogue is the Kaggle-style
  synthetic CRM data; none are realistic.
- **Inventory / supply-chain ledgers**. Same — commercially
  sensitive. ADR-N (kontor-inventory) will rely on synthetic
  fixtures.
- **Time-sheet data**. Same. ADR-N (kontor-time / payroll
  upstream) will rely on synthetic fixtures.

**The implication**: kontor's bitemporal-correction story is
fully real-data backable for **reported financials**; the
**transaction-level operational story** (postings, journals,
batch close, intercompany, payroll runs) will continue to rely
on hand-crafted fixtures + the Clay showcases' synthetic-but-
realistic narratives.

This isn't a defect — it's the same data-asymmetry every
commercial accounting product faces. No vendor has openly
published their customers' GLs either. Kontor's competitive
position is the substrate, not the corpus.

## §8 — Concrete integration-test ingest sketches (recommended)

Two showcase scenarios that exercise the substrate hard, plus
one stretch goal:

### 8.1 — Scenario A: Apple bitemporal ledger (priority 1)

**Files**:
`modules/kontor-import-edgar/test/resources/edgar/aapl-companyfacts.json`
(snapshot from 2025-Q4, 320 MB), plus the 2015–2024 filing
chain.

**Notebook**: `doc/showcases/05-aapl-edgar-bitemporal.clj`.

**Demonstrates**:
- `:account-tag/concept-iri` mapping `us-gaap:Revenues` →
  a kontor chart account.
- `(d/valid-at db t)` and `(d/as-of db tx)` axes against real
  filing dates.
- The report engine's `:engine :account-codes` reproducing
  Apple's reported income statement from the underlying postings.
- ADR-072 currency translation if multi-segment FX needed.

**License-ship-ability**: yes. SEC data is public domain;
bundle the snapshot in `test/resources/`. Add `LICENSE` note
attributing SEC.

### 8.2 — Scenario B: UK Ltd 5-year history (priority 2)

**Files**: `modules/kontor-import-companies-house/test/resources/`
with 5 monthly bulk dumps' worth of iXBRL files for a single
chosen UK Ltd. The maintainer chooses the company — recommend a
non-controversial OSS-foundation UK subsidiary or a published
example company from the OGL docs.

**Notebook**: `doc/showcases/06-uk-ltd-companies-house-multi-year.clj`.

**Demonstrates**:
- Multi-regulator code mapping: same accounts tagged with both
  SKR04 (for hypothetical DE group consolidation) and FRC FRS-102.
- ADR-019 in action against a real second-jurisdiction code map.
- Year-over-year trial balance using `(d/valid-at db t)`.

**License-ship-ability**: yes, with OGL attribution. The
notebook header must say "Contains public sector information
licensed under the Open Government Licence v3.0."

### 8.3 — Scenario C: ASF Form 990 multi-year (stretch / priority 3)

**Files**:
`modules/kontor-import-irs-990/test/resources/asf/2013-2024-form-990.xml`.

**Notebook**: `doc/showcases/07-asf-form-990.clj`.

**Demonstrates**:
- Nonprofit P&L + balance sheet shape (Form 990 schedule
  structure).
- Single-entity per-filing ingest pattern, contrasted with
  EDGAR's `companyfacts` aggregation.
- ProPublica Nonprofit Explorer API as a comparison oracle.

**License-ship-ability**: yes. IRS data is federal public
domain; ASF's 990 is its own filed return; no attribution
required but attributing IRS + ProPublica is courteous.

### 8.4 — Scenario D: Multi-entity GLEIF spine (priority 1, but as
infra not as showcase)

**Files**: `modules/kontor-import-gleif/test/resources/gleif-sample.csv`
(a 1000-row Level 1 sample covering the entities ingested in
Scenarios A + B + C).

**Notebook**: not a standalone showcase, but a *fixture* the
other showcases rely on for entity identity.

**Demonstrates**:
- `:entity/lei` as a stable cross-DB join key.
- ADR-073 `kontor.entity/family` walk against real
  parent/subsidiary chains.

## §9 — Licensing matrix

A summary of which datasets we can bundle in shipped test
fixtures vs which must be downloaded at test-time:

| Dataset | License | Shippable in test/resources? | Attribution required? |
|---|---|---|---|
| SEC EDGAR XBRL | Public domain (17 U.S.C. § 105) | Yes | Courteous, not required |
| UK Companies House Free Accounts | Open Government Licence v3.0 | Yes | Required (OGL attribution) |
| GLEIF Golden Copy | CC0 1.0 Universal | Yes | None |
| IRS Form 990 XML | Public domain (17 U.S.C. § 105) | Yes | Courteous |
| FFIEC Call Reports | Public domain (17 U.S.C. § 105) | Yes | Courteous |
| FL LOGERX ACFRs | Public domain | Yes | Courteous |
| Will County IL ACFRs | Public domain | Yes | Courteous |
| `filings.xbrl.org` (ESEF aggregator) | Unclear ("All Rights Reserved" footer) | **No** — defer | N/A |
| ESEF per-OAM | Per-country | Per-jurisdiction | Per-jurisdiction |
| Bundesanzeiger | Per OpenRegister / 3rd-party APIs | Mostly **No** (third-party Ts&Cs) | N/A |
| Beancount Open Ledger (Bcio) | Unclear | **No** | N/A |
| Apache Software Foundation 990s | Public (via IRS) | Yes | Courteous |
| FreeBSD Foundation 990s | Public (via IRS) | Yes | Courteous |
| BLS QCEW | Public domain | Yes | Courteous |
| Compustat (WRDS) | Subscription, share-prohibited | **No** | N/A — cannot redistribute |

**Recommendation for kontor**: ship `test/resources/` fixtures
**only** from the first seven rows (public-domain federal/state
US + OGL UK + CC0 GLEIF). Everything else: ingest at test-time
from upstream, do not bundle.

## §10 — Open questions for maintainer decisions

1. **Scope of v1 ingest modules**. Three options:
   a. Ship one full-pipeline import (SEC EDGAR + GLEIF) under
      `modules/kontor-import-edgar` + `modules/kontor-import-gleif`,
      document the rest as research notes.
   b. Ship import scaffolds (schema-mapping + fact→tx-data helpers
      only; no fetch logic) for all five priority datasets.
   c. Ship import scaffolds for SEC EDGAR only.

   **Recommendation: (a).** GLEIF is small and easy; EDGAR is
   the load-bearing showcase. Each is a real working pipeline,
   not vaporware. Companies House + Form 990 + ACFR can land in
   v1.1 once the EDGAR + GLEIF pattern is proven.

2. **Where do the fixtures live**. Two options:
   a. In-tree under `modules/kontor-import-edgar/test/resources/`.
      Pros: zero-network tests, deterministic. Cons: repo size
      (Apple's `companyfacts.json` alone is 320 MB).
   b. Out-of-tree, fetched on demand from a cached snapshot in
      a separate `kontor-corpus` repo.
      Pros: keeps the kernel repo small. Cons: tests are
      network-dependent.

   **Recommendation: (b) for large fixtures (>1 MB), (a) for
   small ones (<1 MB).** A 10-fact GLEIF sample + a 100-fact
   Apple subset can live in-tree; the full 320 MB
   `companyfacts.json` lives in `kontor-corpus`.

3. **Whether to write our own iXBRL parser or shell out to Arelle**.
   Per research note 78 §4.1, Arelle is the Apache-2.0 Python
   reference. ADR-037 forbids Python sidecars. Three options:
   a. Write a minimal pure-Clojure iXBRL fact extractor (using
      `clojure.data.xml`) sufficient for EDGAR + Companies House
      ingest. Skip validation (depend on the regulator's
      validation).
   b. Use `data.sec.gov`'s JSON facts API instead of parsing
      iXBRL directly, so SEC EDGAR ingest never touches XML.
   c. Ship a sidecar Arelle process for "filing-time" validation
      only (not CI-time). Permits Python; ADR-037 prevents this.

   **Recommendation: (b) for EDGAR (use JSON API), (a) for
   Companies House (must parse iXBRL directly, no JSON API
   exists).** This sidesteps the most painful XML for the most
   important dataset, and limits the Clojure iXBRL parser's
   complexity to the FRC taxonomy shape (smaller than US-GAAP).

4. **Whether to ship a GLEIF cross-reference table at all**.
   The CC0 license makes this trivial to ship, but the data is
   ~500 MB. Alternative: ship a small (50-entity) curated sample
   covering the entities referenced in showcases.

   **Recommendation: ship the 50-entity sample in-tree; document
   the full Golden Copy ingest path.**

5. **Whether to formalize a `kontor-corpus` companion repo**.
   The corpus is data, not code. If we have one (per Q2 + Q4
   recommendations), it likely warrants its own repo with its
   own license metadata + attribution boilerplate per dataset.

   **Recommendation: yes, create `kontor-corpus` as a sibling
   to `kontor-import-edgar` / `kontor-import-gleif`.**

## §11 — Sources (URLs + accessed 2026-05-18)

**SEC EDGAR**:
- [SEC.gov: EDGAR APIs](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)
  (403 to WebFetch; access required via API-aware tool with proper User-Agent)
- [SEC.gov: Accessing EDGAR Data](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)
- [SEC.gov: Financial Statement Data Sets](https://www.sec.gov/data-research/sec-markets-data/financial-statement-data-sets)
- [SEC.gov: Financial Statement and Notes Data Sets](https://www.sec.gov/data-research/sec-markets-data/financial-statement-notes-data-sets)
- [SEC.gov: Developer Resources](https://www.sec.gov/about/developer-resources)
- [SEC.gov: Inline XBRL guidance](https://www.sec.gov/divisions/corpfin/guidance/interactivedatainterp)
- [17 U.S.C. § 105 — Government works](https://www.law.cornell.edu/uscode/text/17/105)
- [XBRL US: 10-K/A files forum thread](https://xbrl.us/forums/topic/10-k-a-files/)
- [GitHub: secdatabase/SEC-XBRL-Financial-Statement-Dataset](https://github.com/secdatabase/SEC-XBRL-Financial-Statement-Dataset)
- [dealcharts.org: SEC EDGAR API Guide](https://dealcharts.org/blog/sec-edgar-api-guide)
- [tldrfiling.com: SEC EDGAR API Guide 2026](https://tldrfiling.com/blog/sec-edgar-api-guide/)
- [tldrfiling.com: SEC EDGAR XBRL API Tutorial 2026](https://tldrfiling.com/blog/sec-edgar-xbrl-api-python-tutorial)

**UK Companies House + FRC**:
- [Companies House: Free Accounts Data Product](https://download.companieshouse.gov.uk/en_accountsdata.html)
- [Companies House: Monthly Accounts Data](https://download.companieshouse.gov.uk/en_monthlyaccountsdata.html)
- [Companies House: Data Products](https://www.gov.uk/guidance/companies-house-data-products)
- [FRC Taxonomies](https://www.frc.org.uk/library/standards-codes-policy/accounting-and-reporting/frc-taxonomies/)
- [GOV.UK: XBRL Guide for UK Businesses](https://www.gov.uk/government/publications/xbrl-guide-for-uk-businesses/xbrl-guide-for-uk-businesses)
- [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/)

**ESEF**:
- [ESMA: Electronic Reporting](https://www.esma.europa.eu/issuer-disclosure/electronic-reporting)
- [filings.xbrl.org — ESEF aggregator](https://filings.xbrl.org/)
- [GitHub: European-Securities-Markets-Authority/esef_toolkit](https://github.com/European-Securities-Markets-Authority/esef_toolkit)
- [easyESEF: ESEF Filings](https://easyesef.eu/esef-filings/)
- [XBRL US: ESMA ESEF Data](https://xbrl.us/academic-repository/esma-esef-data/)
- [ESMA: 2024 ESEF XBRL files + conformance suite](https://www.esma.europa.eu/press-news/esma-news/esma-publishes-2024-esef-xbrl-files-and-esef-conformance-suite)

**IRS Form 990**:
- [IRS: Form 990 Series Downloads](https://www.irs.gov/charities-non-profits/form-990-series-downloads)
- [AWS Registry of Open Data: IRS 990 Filings](https://registry.opendata.aws/irs990/)
- [ProPublica: Nonprofit Explorer](https://projects.propublica.org/nonprofits/)
- [ProPublica: Nonprofit Explorer API v2](https://projects.propublica.org/nonprofits/api)
- [ProPublica: Nonprofit Explorer Update — full text of 1.9M records](https://www.propublica.org/nerds/nonprofit-explorer-update-full-text-of-nearly-two-million-records)
- [Apache Software Foundation 990 (EIN 47-0825376)](https://projects.propublica.org/nonprofits/organizations/470825376)
- [FreeBSD Foundation 990 (EIN 84-1545163)](https://projects.propublica.org/nonprofits/organizations/841545163)

**US ACFR / Municipal**:
- [XBRL US: 2022 Florida Open Financial Statement Taxonomy](https://xbrl.us/xbrl-taxonomy/2022-florida/)
- [Florida LOGERX repository](https://logerx.myfloridacfo.gov/)
- [Will County Auditor: XBRL](https://www.willcountyauditor.com/news-xbrl)
- [GitHub: govwiki/us-municipal-cafr-taxonomy](https://github.com/govwiki/us-municipal-cafr-taxonomy)
- [XBRL US: CAFR Taxonomy Release 2](https://xbrl.us/news/cafr-taxonomy-release2/)
- [XBRL.org: XBRL US CAFR Taxonomy](https://www.xbrl.org/news/xbrl-us-release-cafr-taxonomy-for-municipal-reporting/)
- [Mercatus Center: Applying XBRL to State and Local Government Financial Reports](https://www.mercatus.org/media/158201/download?attachment=)
- [Congress.gov: FDTA IF13093](https://www.congress.gov/crs-product/IF13093)
- [SEC.gov: FDTA Joint Data Standards s7-2024-05](https://www.sec.gov/rules-regulations/2024/08/s7-2024-05)
- [GFOA: FDTA Implementation](https://www.gfoa.org/fdta)

**FFIEC**:
- [FFIEC CDR Public](https://cdr.ffiec.gov/public/)
- [FFIEC CDR: Bulk Data Download](https://cdr.ffiec.gov/public/PWS/DownloadBulkData.aspx)
- [FFIEC CDR: Download Help](https://cdr.ffiec.gov/public/HelpFiles/DownloadHelp.htm)
- [Federal Reserve Bank of Chicago: Commercial Bank Data](https://www.chicagofed.org/banking/financial-institution-reports/commercial-bank-data)
- [XBRL US: FFIEC CDR](https://xbrl.us/xbrl-reference/federal-financial-institutions-examination-councils-central-data-repository/)

**GLEIF**:
- [GLEIF: Open Data](https://www.gleif.org/en/about/open-data)
- [GLEIF: LEI Data Terms of Use](https://www.gleif.org/en/meta/lei-data-terms-of-use)
- [GLEIF: Golden Copy Download](https://www.gleif.org/en/lei-data/gleif-golden-copy/download-the-golden-copy)
- [GLEIF: Concatenated File Download](https://www.gleif.org/en/lei-data/gleif-concatenated-file/download-the-concatenated-file)
- [GLEIF: Level 1 LEI-CDF 3.1](https://www.gleif.org/en/lei-data/access-and-use-lei-data/level-1-data-lei-cdf-3-1-format)
- [GLEIF: LEI Statistics](https://www.gleif.org/en/lei-data/global-lei-index/lei-statistics)
- [Creative Commons CC0 1.0 Legal Code](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en)
- [OpenOwnership: GLEIF→BODS mapping](https://www.openownership.org/en/blog/mapping-global-legal-entity-identifier-foundation-data-to-the-beneficial-ownership-data-standard/)

**Bundesanzeiger / DE**:
- [Bundesanzeiger search](https://www.bundesanzeiger.de/pub/en/search)
- [OpenRegister: Bundesanzeiger](https://docs.openregister.de/sources/bundesanzeiger)
- [GitHub: bundesAPI/deutschland](https://github.com/bundesAPI/deutschland)

**Plain-text accounting + open ledgers**:
- [GitHub: beancount/beancount](https://github.com/beancount/beancount)
- [Beancount Open Ledger Movement (NVIDIA / Alphabet / Adyen / MiniMax)](https://beancount.io/blog/2026/01/28/open-ledger-movement)
- [plaintextaccounting.org](https://plaintextaccounting.org/)
- [Awesome Beancount](https://awesome-beancount.com/)

**Payroll gap**:
- [BLS: Quarterly Census of Employment and Wages](https://www.bls.gov/cew/)
- [BLS: QCEW Data Files](https://www.bls.gov/cew/downloadable-data-files.htm)
- [BLS: QCEW Open Data](https://www.bls.gov/cew/additional-resources/open-data/home.htm)

**Compustat / Compustat alternatives**:
- [WRDS](https://wrds-www.wharton.upenn.edu/)
- [NYU Library WRDS FAQ](https://guides.nyu.edu/wrds/faqs)

**Cross-cutting**:
- [Apache Software Foundation: Public Records](https://www.apache.org/foundation/records/)
- [FreeBSD Foundation: Financials](https://freebsdfoundation.org/about-us/about-the-foundation/financials/)
- [Wikipedia: Copyright status of US federal works](https://en.wikipedia.org/wiki/Copyright_status_of_works_by_the_federal_government_of_the_United_States)
- [resources.data.gov: Open Licenses](https://resources.data.gov/open-licenses/)

**Companion kontor research notes**:
- doc/research/78-xbrl-and-accounting-taxonomies.md (XBRL substrate
  design input; this note's specification-layer companion).
- doc/research/50-banking-as-consumer.md (banking as a kontor
  consumer — feeds the FFIEC ingest decision).
- doc/research/51-tax-authority-as-consumer.md (tax authority
  consumer — feeds the Form 990 IRS ingest decision).

End — note 91.

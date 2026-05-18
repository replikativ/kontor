---
date: 2026-05-17
agent: research
title: XBRL + accounting taxonomies — substrate-design input for kontor
status: research-note
---

# 78 — XBRL + accounting taxonomies: substrate-design input for kontor

Research-before note for the open design question: *should kontor grow
XBRL-shaped concepts, and if so, how deep should it go?* The maintainer
is new to XBRL; the note's job is to lay out the standard, the
taxonomy ecosystem, the reference systems, and what already-shipped
kontor primitives map cleanly to it — then offer three directions
honestly (minimal hooks → kernel primitive → full iXBRL pipeline)
without pushing toward any of them.

No code is written here. No ADR is drafted. The output is a map.

## §1 — TL;DR

- **XBRL is a tagging vocabulary, not a runtime.** Every reported
  number ("Revenue 2025 Q1 = €1,234,567") is annotated with a
  *concept* (a URI like `ifrs:Revenue`), a *context* (entity + period
  + optional dimensions), and a *unit* (`iso4217:EUR`). The "data
  model" is small; the heavy lifting is in the *taxonomies* — the
  shared dictionaries that say which concepts exist, what they mean,
  how they nest, and how they sum. Major taxonomies (US-GAAP, IFRS,
  E-Bilanz, FRC, EDINET, EBA FINREP/COREP, ESEF) are 3k–17k concepts
  each, version-bumped annually, and extensible per-filer.
- **iXBRL is the same vocabulary embedded inside HTML.** SEC EDGAR
  (US listed companies), ESMA ESEF (EU listed companies), HMRC +
  Companies House (UK limited companies — mandatory for ALL of them
  from 1 April 2026), MCA (India, large companies), EDINET (Japan),
  ACRA (Singapore SMEs) all require some form of iXBRL or XBRL
  filing today. Canada / SEDAR+ remains *voluntary* on XBRL as of
  2026.
- **The shape XBRL imposes maps cleanly onto things kontor already
  has.** `:account-tag/*` (`schema.clj:420-449`) already supplies a
  tagging substrate. `:account/external-codes` + `:account-code/*`
  (ADR-019, `schema.clj:316-320, 380-409`) already supports
  multi-regulator code mappings. `kontor.report`'s
  `:engine :account-codes` + `:engine :tax-tags`
  (`src/kontor/report.clj:172-210`) is structurally the same engine
  Odoo Enterprise's `account_report` uses to drive iXBRL emission —
  Odoo's open-source `account.report` model
  (`/home/christian-weilbach/Development/odoo/addons/account/models/account_report.py:391-506`)
  ships the identical engine vocabulary (`account_codes`, `tax_tags`,
  `aggregation`, `domain`, `external`). The "next step" — wiring a
  computed line to an XBRL concept — is one schema attr away.
- **The calculation linkbase is a NEW kind of invariant on top of
  what kontor enforces today.** Sum-to-zero (`kontor.posting`) is
  per-(entity, ledger, commodity); calculation linkbase says
  *parent-line = Σ children* across a STATEMENT shape. They're
  orthogonal: a journal can be perfectly balanced and still have a
  calc-linkbase inconsistency, because the statement layout is a
  cross-account aggregation tree, not a per-transaction invariant.
  This invariant is the *fundamental* output of calc-linkbase
  consistency checking, and adding it would catch a class of layout
  bugs the current engine cannot.
- **Bitemporal alignment is excellent.** XBRL contexts carry a
  *period* that is either an instant (BS — "2025-12-31") or a
  duration (P&L — "2025-01-01..2025-12-31"). This maps 1:1 onto
  kontor's bitemporal axis: an instant context is `(d/valid-at db
  t)`; a duration context is the existing report-window machinery
  (`compute-report :from :to`). XBRL has no built-in *transaction-
  time* axis — facts can be RESTATED in a later filing but the
  system-time history is the regulator's job, not the taxonomy's.
  Kontor's `:as-of-tx` axis is strictly more powerful than what
  XBRL specifies.
- **No JVM-native XBRL processor exists; the de facto OSS standard
  is Arelle, Apache-2.0 Python.** EPL-1.0 compatibility is fine
  (Apache-2.0 → EPL-1.0 is a clean one-way contribution path), but
  Arelle is Python; running it from kontor means either (a) emit
  XBRL artifacts in pure Clojure + validate via a separate Arelle
  shell-out at filing time (acceptable for a once-per-year flow,
  unacceptable for CI), (b) accept a Python sidecar runtime (which
  ADR-037 explicitly forbids), or (c) wrap one of the lesser-used
  Java libraries (xbrlj, XBRLAPI) — none are widely used or
  certified. There is *no* meaningful Clojure XBRL library on the
  JVM. **Validation is a real ecosystem gap.** Emission is straightforward
  (it's XML); validation against a 17k-concept taxonomy with
  thousands of calc + dimension rules is not.
- **Neither Odoo CE nor Tryton emits iXBRL.** Confirmed by direct
  grep across both worktrees. Odoo's iXBRL emission lives in the
  proprietary Enterprise edition; Tryton has no XBRL story at all.
  This means the reference-OSS-pattern well kontor usually draws from
  is dry — there is no Apache-2.0 / MIT codebase to study at file:line
  for "how an open-source accounting kernel emits iXBRL." The
  closest analogues are commercial: Workiva Wdesk, SAP Disclosure
  Management, IRIS Carbon, ez-XBRL. They sit *between* the ERP and
  the regulator, not inside the ERP.

## §2 — XBRL primer

### 2.1 — The data model: concept, context, fact, unit, dimensions

An XBRL **instance document** is an XML file whose root is
`<xbrli:xbrl>` and whose child elements are **facts**. Every fact is a
data point: "this concept, in this context, with this unit, has this
value." The four moving parts:

- **Concept** — a URI like `us-gaap:Revenues` or `ifrs-full:Revenue`.
  Each concept is defined in a *taxonomy*'s XSD schema; the taxonomy
  pins down its data type (monetary, decimal, integer, string, date),
  its period type (`instant` vs `duration`), and its balance sign
  (debit vs credit, where applicable). A taxonomy can declare a
  concept as `abstract="true"` — those are scaffolding nodes that
  hold position in a presentation tree but never have facts of their
  own.
- **Context** — an `<xbrli:context>` element with three required
  parts:
  1. **Entity** — the reporting entity, expressed as a scheme +
     identifier pair (e.g. `<identifier
     scheme="http://www.sec.gov/CIK">0000320193</identifier>` for
     Apple).
  2. **Period** — either `<instant>2025-12-31</instant>` (for
     BS-style point-in-time facts) or `<startDate>2025-01-01</…><endDate>2025-12-31</…>`
     (for P&L/CF duration facts).
  3. **Scenario / Segment** — optional containers for dimension
     values (segment is for entity-side dimensions like reporting
     segment; scenario is for reporting-side dimensions like
     "as restated" vs "as previously reported"). Dimension values
     are populated via the XBRL Dimensions (XDT) extension; see §2.3.
- **Fact** — the leaf element, e.g.
  `<us-gaap:Revenues contextRef="ctx-2025" unitRef="USD"
  decimals="-3">1234567000</us-gaap:Revenues>`. The `contextRef` and
  `unitRef` are XML IDREFs into the contexts and units declared
  above; the `decimals` attribute encodes precision ("-3" = rounded
  to thousands).
- **Unit** — `<xbrli:unit id="USD"><measure>iso4217:USD</measure></xbrli:unit>`.
  Compound units (e.g. EUR/share) use `<divide>`. Required for every
  numeric fact.
- **Footnote** (optional) — `<link:footnote>` elements via the
  footnote linkbase, providing the equivalent of MD&A narrative
  attached to a specific fact. iXBRL filings frequently use these
  for "(1)" / "(*)" style annotations.

This is the entire fact-bearing model. Everything else — the
taxonomy and its linkbases — is **metadata about concepts**, not
about facts. (Source:
[xbrl.org wiki: XBRL data model](https://en.wikipedia.org/wiki/XBRL),
[xbrl.org: XBRL Essentials](https://specifications.xbrl.org/xbrl-essentials.html),
accessed 2026-05-17.)

### 2.2 — The five linkbases

A taxonomy is an XSD that declares concepts, plus a bundle of
**linkbases** — XML files using W3C XLink to express typed
relationships between concepts (and between concepts and external
resources). The five standard linkbases (XBRL 2.1):

1. **Label linkbase** — concept → human label, per language and per
   role. Lets an English filer see "Cash and Cash Equivalents" and a
   German one see "Liquide Mittel" against the same `us-gaap` concept.
   Roles distinguish e.g. `terseLabel`, `verboseLabel`, `documentation`,
   `periodStartLabel`, `negatedLabel`.
2. **Reference linkbase** — concept → authoritative citation
   (`FASB ASC 230-10-45-1` or `IAS 1.81A`). Useful for audit drill-
   down ("why is this concept defined this way?") but rarely
   load-bearing at runtime.
3. **Calculation linkbase** — concept → concept relationships of the
   form `parent = Σ weight × child`. The validation rule is: for every
   `(parent, period, context)` combination in the instance, sum the
   child facts and compare to the parent fact. If they disagree
   (modulo `decimals` precision), raise `calc11e:inconsistentCalculation`.
   This is the *consistency* primitive — see §7. (Source:
   [xbrl.org: Calculation Linkbase](https://www.openriskmanual.org/wiki/XBRL_Calculation_Linkbase);
   [XBRL US data quality rules](https://xbrl.us/data-rule/dqc_0118/),
   accessed 2026-05-17.)
4. **Presentation linkbase** — concept → concept ordering for
   *rendering*. Drives the visual layout of a financial statement
   (sections, sub-sections, line order, indentation). Carries no
   semantic weight — purely cosmetic. iXBRL rendering software walks
   the presentation tree to lay out the HTML version of a statement.
5. **Definition linkbase** — concept → concept relationships of
   non-calculation, non-presentation types. The most important uses
   today: **dimensional relationships** (per XBRL Dimensions —
   `has-hypercube`, `hypercube-dimension`, `dimension-domain`,
   `domain-member`), plus older general-special / essence-alias
   arc-roles.

(Source:
[xbrl.org: Calculation, Presentation, Label, Reference linkbases](https://en.wikipedia.org/wiki/XBRL),
accessed 2026-05-17.)

A sixth linkbase — the **table linkbase** (Specification 1.0, 2014) —
came later and lets a taxonomy declare *rendering templates* (rows ×
columns × dimension slicers) that an iXBRL viewer can use to show a
fact in a table view rather than as a flat list. EBA's FINREP/COREP
templates use table linkbase heavily; ESEF and SEC do not require it.
(Source:
[xbrl.org: Table Linkbase](https://specifications.xbrl.org/spec-group-index-table-linkbase.html),
accessed 2026-05-17.)

### 2.3 — XBRL Dimensions (XDT)

The base XBRL spec gives every fact three implicit dimensions:
*concept*, *entity*, and *period*. The Dimensions 1.0 extension
(`xbrldt:` namespace) adds **typed** and **explicit** user-defined
dimensions, organized into **hypercubes**.

- An **explicit dimension** carries a value from a fixed enumeration
  (e.g. `us-gaap:StatementBusinessSegmentsAxis` with members like
  `EurpoeSegmentMember`, `AmericasSegmentMember`).
- A **typed dimension** carries a free-form XML-typed value (e.g. a
  loan ID for a per-loan disclosure).
- A **hypercube** declares "this set of concepts is reportable
  against this set of dimensions." A fact for a hypercube-bound
  concept must carry values for every required dimension in its
  context's segment/scenario.

(Source:
[xbrl.org: XBRL Dimensions 1.0](https://specifications.xbrl.org/work-product-index-group-dimensions-dimensions.html),
accessed 2026-05-17.)

The mental model: "Revenue" by itself is a one-dimensional fact (just
the concept + period + entity). "Revenue by Geography by Segment" is
a three-dimensional fact, and XDT is what lets a taxonomy declare
"the dimensions of Revenue are `GeographyAxis` and `SegmentsAxis`."

This maps directly onto kontor's `:posting/entity` (ADR-031),
analytic accounts (ADR-012, ADR-022), and the future per-segment
reporting that will eventually grow on top of ADR-073's consolidation
primitives. **Every kontor `:posting` is already a multi-dimensional
fact** — it just doesn't expose those dimensions to XBRL today.

### 2.4 — iXBRL — Inline XBRL

iXBRL (Inline XBRL) is XBRL tags embedded inside an HTML document.
Filers prepare *one* HTML file with `<ix:nonFraction>`,
`<ix:nonNumeric>`, `<ix:header>`, `<ix:context>`, etc. elements
interspersed with the visible narrative + tables. A human reads the
HTML; a machine extracts the XBRL facts via the inline tags. This
solved the dual-document problem of XBRL 2.1: filers used to ship
both a glossy PDF annual report *and* a separate raw-XBRL exhibit
that nobody read.

The regulators that have moved to iXBRL since ~2018:
- **SEC EDGAR** (US listed). Domestic filers' Form 10-Q, 10-K, and
  certain non-IPO registration statements must include cover page,
  financial statements, footnotes, schedules, and (annual)
  auditor information in iXBRL. (Source:
  [SEC.gov: Inline XBRL](https://www.sec.gov/data-research/structured-data/inline-xbrl);
  [SEC EDGAR XBRL Guide, May 2026](https://www.sec.gov/files/edgar/filer-information/specifications/xbrl-guide.pdf),
  accessed 2026-05-17.)
- **ESMA ESEF** (EU regulated markets). IFRS consolidated financial
  statements in annual reports must be marked up with the ESEF
  taxonomy via iXBRL. (Source:
  [ESMA: Electronic Reporting](https://www.esma.europa.eu/issuer-disclosure/electronic-reporting),
  accessed 2026-05-17.)
- **HMRC + Companies House** (UK limited companies). The big
  recent change: from **1 April 2026**, iXBRL filing via commercial
  software is *mandatory for ALL UK limited companies* — micro-
  entities filing FRS 105, small companies filing FRS 102 abridged,
  dormant companies. WebFiling and HMRC's CATO web portal closed on
  31 March 2026. No small-company exemption. (Source:
  [alto-accounting.com: iXBRL Accounts Filing 2026](https://www.alto-accounting.com/insights/ixbrl-accounts-filing-companies-house-2026);
  [FRC: 2026 Taxonomy Suite](https://www.frc.org.uk/library/standards-codes-policy/accounting-and-reporting/frc-taxonomies/current-frc-taxonomy-suites/2026-frc-taxonomy-suite/),
  accessed 2026-05-17.)
- **EDINET** (Japan). JP-GAAP, IFRS, and US-GAAP filers all submit
  via XBRL; the 2027 EDINET taxonomy adds IFRS Sustainability
  Disclosure Taxonomy alignment. (Source:
  [xbrl.org: Japan progresses with 2027 EDINET](https://www.xbrl.org/news/japan-progresses-with-2027-edinet-taxonomy-plans/),
  accessed 2026-05-17.)
- **ACRA** (Singapore — incorporated companies, not sole proprietors)
- **MCA** (India — companies with paid-up capital ≥ INR 50M or
  turnover ≥ INR 1B).

(Source:
[MCA XBRL mandate](https://assets.kpmg.com/content/dam/kpmg/pdf/2016/03/XBRL.pdf),
accessed 2026-05-17.)

### 2.5 — Period semantics ↔ kontor's bitemporal axis

XBRL has *two* period kinds on facts:
- **Instant** — concept has `periodType="instant"` (every BS account:
  cash, AR, AP, PP&E, equity). Reported as a single date.
- **Duration** — concept has `periodType="duration"` (every P&L /
  CF account: revenue, expense, depreciation expense, cash from ops).
  Reported as a (startDate, endDate) pair.

This maps trivially onto kontor's existing bitemporal-aware
reporting (`src/kontor/report.clj:258-342`, `compute-report` with
`:from` / `:to`):
- BS line: `compute-report :to <as-of-date>` (no `:from`) = instant
  fact at `<as-of-date>`.
- P&L line: `compute-report :from <start> :to <end>` = duration fact
  for that window.
- `(d/valid-at db t)` axis = the *fact value* axis (what was true on
  the books at logical date `t`).
- `(d/as-of db tx)` axis = the *restatement* axis (what we thought
  was true as of system time `tx`). XBRL has no native concept for
  this — restatements are a separate filing that supersedes the
  prior one — but kontor's bitemporal substrate is strictly richer
  than what XBRL specifies, so the mapping is "downward compatible":
  any XBRL filing is `(d/valid-at db reporting-date)` at the
  current-tx snapshot.

(Source: [xbrl.org: Using Date Contexts](https://xbrl.us/guidance/using-date-contexts-in-different-scenarios/),
accessed 2026-05-17.)

## §3 — Major taxonomies + filing regimes

### 3.1 — US-GAAP (FASB → SEC)

The FASB Financial Reporting Taxonomy + SEC Reporting Taxonomy +
DQCRT (Data Quality Committee Rules Taxonomy) are republished
annually. The 2024 Taxonomy (in production through ~Mar 2026) added
393 new Taxonomy Implementation Notes (TINs) and 24 new DQC rules
(46 total). 2025 + 2026 taxonomies were published on schedule.
(Source:
[XBRL US: 2024 US GAAP Taxonomy](https://xbrl.us/xbrl-taxonomy/2024-us-gaap/);
[FASB: 2024 GAAP Financial Reporting Taxonomy](https://www.fasb.org/page/detail?pageId=/projects/FASB-Taxonomies/2024-gaap-financial-reporting-taxonomy.html);
[XBRL US: 2025 US GAAP Taxonomy](https://xbrl.us/xbrl-taxonomy/2025-us-gaap/),
accessed 2026-05-17.) Public-record concept counts vary by source
(~14k–17k including SEC + DQCRT additions); industry rule of thumb
is "~15,000 concepts to consider." The taxonomy is XBRL Dimensions-
heavy: BS line items have segments + scenarios; restatements are a
separate set of contexts within the same instance.

**Filer-side extension** is the second load-bearing US-GAAP
characteristic: FASB allows filers to create *extension concepts*
when the standard taxonomy doesn't fit. Extension creation is
common (most filings have dozens to hundreds of extensions); it's
also a frequent comparability complaint. (Source:
[Accounting Today: FASB offers guide to XBRL extensible lists](https://www.accountingtoday.com/news/fasb-offers-guide-to-xbrl-extensible-lists),
accessed 2026-05-17.)

### 3.2 — IFRS (IFRS Foundation → ESEF + various national regulators)

The IFRS Accounting Taxonomy is the IFRS Foundation's official
XBRL representation of IFRS Standards. The 2024 update published
27 March 2024 incorporates Pillar Two model rules, supplier finance
arrangements, lack-of-exchangeability amendments, plus general
improvements. Around 6,000 concepts. (Source:
[IFRS Foundation: IFRS Accounting Taxonomy 2024](https://www.ifrs.org/issued-standards/ifrs-taxonomy/ifrs-accounting-taxonomy-2024/);
[Workiva: 2024 IFRS Taxonomy Update Guide](https://www.workiva.com/blog/your-guide-2024-ifrs-taxonomy-update),
accessed 2026-05-17.)

**ESEF** (European Single Electronic Format) is the EU-mandated
flavor: it *extends* the IFRS taxonomy with European-specific
concepts and is the format issuers on EU regulated markets must use
for IFRS consolidated annual financial reports. ESMA publishes
yearly ESEF taxonomy updates (most recent at time of writing:
ESEF 2024 with 2025 in progress including IFRS 18 early-adopter
content). (Source:
[ESMA: ESEF Taxonomy 2024](https://www.esma.europa.eu/document/esef-taxonomy-2024);
[xbrl.org: ESMA Updates ESEF with 2025](https://www.xbrl.org/news/esma-updates-esef-with-2025-ifrs-taxonomy-critical-changes-for-early-ifrs-18-adopters/),
accessed 2026-05-17.)

### 3.3 — DE / E-Bilanz / HGB-Taxonomie

The HGB-Taxonomie is the German tax authority's electronic balance
sheet ("E-Bilanz") taxonomy, maintained by XBRL Deutschland under
direction from the Bundesfinanzministerium. It is **not** an
optional reporting vehicle: HGB §5b requires electronic transmission
of the balance sheet + P&L for any business required to keep books
under HGB §141. Most recent version at time of writing: 6.8
(superseding 6.7 / 6.6 / 6.5). (Source:
[de.xbrl.org: E-Bilanz / HGB-Taxonomie Version 6.8](https://de.xbrl.org/taxonomien/e-bilanz-hgb-taxonomie-version-6-8/),
accessed 2026-05-17.)

The taxonomy includes a **GCD module** (`globalCommonData`) covering
filer identity + period + reporting unit, plus a **GAAP module**
covering HGB + IFRS-line equivalents. SKR03 / SKR04 mappings: filers
using DATEV's SKR03 or SKR04 chart can auto-assign their accounts to
HGB-Taxonomie positions via the "Automation" button in tools like
myebilanz. (Source:
[myebilanz documentation](https://www.myebilanz.de/myebilanz.pdf),
accessed 2026-05-17.) This is exactly the multi-regulator code
mapping ADR-019 (`:account/external-codes` keyed by
`:account-code/regulator`) is built for — the regulator key would be
e.g. `:de/hgb-taxonomie-6-8` and the code would be the taxonomy
concept's QName.

### 3.4 — UK FRC / Companies House / HMRC

The FRC Taxonomy Suite (current: 2026 suite) packages multiple
taxonomies for use with Companies House + HMRC filings:
- **FRS 105** (micro-entities)
- **FRS 102** (small + medium UK companies — most companies)
- **FRS 101** (reduced-disclosure subsidiaries)
- **UK IFRS** (UK-listed IFRS reporters)
- **Charities SORP**
- **DPL** (Detailed Profit & Loss for HMRC tax computation)

The 2026 suite supports the **mandatory iXBRL filing from
1 April 2026** that closed Companies House WebFiling. There is no
small-company exemption: every UK limited company, regardless of
size, files via commercial iXBRL software now. (Sources cited in
§2.4 above.)

This is the most aggressive small-business iXBRL mandate of any
major regulator — and arguably the one where kontor's
beleg-style consumer-app target audience overlaps most. A UK-domiciled
solo founder running on a kontor + beleg stack now needs *some*
path to filing iXBRL accounts, or they have to bolt on a commercial
filing tool. This is a real market signal even if kontor itself
doesn't emit iXBRL — at minimum, the data needs to be *exportable*
into something a filing tool can consume.

### 3.5 — JP-EDINET

EDINET (Electronic Disclosure for Investors' NETwork) is Japan's FSA-
run XBRL filing system, covering Annual / Semiannual / Quarterly
Securities Reports + Securities Registration Statements. Filers may
use any of three accounting standards — JP-GAAP (with manufacturer
vs service-company vs bank vs insurance variants), IFRS, or US-GAAP
— and the taxonomy supports all three; the consequence is that a
single conceptual line ("Net Income") has different element names
depending on which standard variant the filer uses. (Source:
[axiora.dev: EDINET for Developers](https://axiora.dev/en/blog/edinet-for-developers);
[github.com/axioradev/edinet-xbrl](https://github.com/axioradev/edinet-xbrl),
accessed 2026-05-17.)

The 2027 EDINET taxonomy (in development through 2026) adds the
**IFRS Sustainability Disclosure Standards taxonomy** — Japan is
the first jurisdiction to commit to a digital ISSB filing format.
(Source:
[xbrl.org: Japan progresses with 2027 EDINET](https://www.xbrl.org/news/japan-progresses-with-2027-edinet-taxonomy-plans/),
accessed 2026-05-17.)

### 3.6 — CA / SEDAR+

Canada is the **major outlier**: as of 2026, XBRL filing on SEDAR+
remains **voluntary**. Issuers must continue to file PDF financial
statements; XBRL is a supplementary filing. The Canadian Securities
Administrators (CSA) is consulting on adoption for investment fund
disclosures (xbrl.us has submitted comments advocating broader
adoption), but no mandate is in place. (Source:
[OSC: XBRL](https://www.osc.ca/en/xbrl);
[xbrl.org: Canada to embrace open data standards](https://www.xbrl.org/news/canada-to-embrace-open-data-standards-for-investment-fund-reporting/),
accessed 2026-05-17.)

For kontor's country roadmap (ADR-004: DE → CA → US, ADR-015: CA
three-ring tax-filing), this is good news: **CA does not require
XBRL emission to be operationally viable**. The maintainer's CA
priority can ship without XBRL and still be filing-complete. US
takes the heaviest XBRL lift, eventually.

### 3.7 — Industry / supplementary taxonomies

Banking and insurance run their own XBRL regimes on top of the
above:
- **EBA FINREP** (financial reporting) + **EBA COREP** (regulatory
  capital reporting) cover EU credit institutions. Both are
  mandatory XBRL; the EBA publishes a unified Data Point Model
  (DPM) that the XBRL taxonomy implements. Current framework 4.2
  applies from Q1 2026. (Source:
  [EBA: Reporting framework 4.2](https://www.eba.europa.eu/risk-and-data-analysis/reporting-frameworks/reporting-framework-42);
  [Regulation Tomorrow: EBA consults on simplifying CRR](https://www.regulationtomorrow.com/2026/04/eba-consults-on-simplifying-supervisory-reporting-under-crr/),
  accessed 2026-05-17.)
- **Solvency II** — EIOPA insurance supervisory reporting, XBRL.
- **AnaCredit** (analytical credit datasets — ECB).
- **FERC** — US energy-sector financial reporting.

For kontor: these are not v1 concerns. They're worth mapping mentally
because they show the depth of the XBRL ecosystem ("specific
industry × specific regulator × specific framework × annual revision"
is the recurring shape), but no kontor consumer is asking for them.

## §4 — Reference systems landscape

### 4.1 — Arelle (Apache-2.0, Python)

Arelle is the de facto open-source XBRL processor:
- **License**: Apache-2.0 (`arelle/Arelle` on GitHub). Compatible
  with EPL-1.0 in the one-way sense: kontor *could* incorporate or
  shell out to Arelle without license conflict.
- **Language**: Python (the entire codebase). No JVM port.
- **Capabilities**: certified by XBRL International as a Validating
  Processor. Parses + validates XBRL instances against taxonomies,
  including calc linkbase checks, dimension validity, formula
  linkbase rules.
- **Modes**: desktop app + REST web service + CLI + Python API.
- **Adoption**: used by 50+ regulators, banks, technology companies
  worldwide.
- **Recent activity**: `arelle-release` 2.37.49 on PyPI; `arelle-mcp`
  package released April 2026 (an MCP server wrapping Arelle for
  AI-agent integrations) — signal of continuing maintenance.

(Sources:
[github.com/Arelle/Arelle](https://github.com/Arelle/Arelle);
[arelle.org](https://arelle.org/arelle/);
[pypi.org/project/arelle-release](https://pypi.org/project/arelle-release/),
accessed 2026-05-17.)

**Implication for kontor**: if kontor ever needs validation against
a taxonomy, the *practical* path is to invoke Arelle as an external
binary at filing time. Embedding Arelle in-process means a Python
sidecar, which **ADR-037 explicitly forbids** ("Do not introduce a
second runtime — no JS, no Python helpers, no shell scripts beyond
`bb`"). The shell-out-at-filing-time pattern is fine because filing
is rare (annual) and human-driven; it's not a CI-time pattern.

### 4.2 — JVM XBRL libraries

There are a handful, none widely adopted:
- **xbrlj** (`ammasjk/xbrlj`) — Java library for parsing XBRL +
  iXBRL. Small project; activity sporadic.
- **xbrlapi** (`xbrlapi.org`, SourceForge) — older Java API,
  comprehensive but not actively maintained.
- **nortal/xbrl-engine** — XBRL instance parser in Java.
- **JeasyXBRL** — defunct; documented as a regex-based 2015–2016
  project.

(Source:
[github.com/ammasjk/xbrlj](https://github.com/ammasjk/xbrlj);
[sourceforge.net/projects/xbrlapi](https://sourceforge.net/projects/xbrlapi/);
[github.com/nortal/xbrl-engine](https://github.com/nortal/xbrl-engine),
accessed 2026-05-17.)

**There is no Clojure XBRL library worth citing.** The
`clojure.data.xml` library covers the underlying XML manipulation;
that is the realistic starting point if kontor ever does anything
in-process (emission, not validation).

### 4.3 — Odoo CE — what's actually there

Odoo Community ships `account.report` — a declarative reporting model
that closely mirrors what kontor's `report.clj` engine does. The
Python class at
`/home/christian-weilbach/Development/odoo/addons/account/models/account_report.py:44-967`
defines five expression *engines*
(`account_report.py:391-396`):
- `domain` — match move lines by an Odoo domain filter
- `account_codes` — sum lines whose account codes match a formula
- `aggregation` — combine other report lines via a formula
- `external` — externally-supplied figures
- `tax_tags` — sum lines tagged with specific tax tags

These are the same five engines kontor's `kontor.report` ships (two
today; three additional are documented "future engines" in the
docstring at `src/kontor/report.clj:53`). Odoo CE uses them to drive
the tax-return reports (UStVA, VAT 100, etc.); kontor uses them for
the same purpose.

**iXBRL emission is NOT in Odoo CE.** Confirmed by direct
grep: zero results for "xbrl", "ixbrl", "ebilanz", or "e-bilanz"
across the entire `/home/christian-weilbach/Development/odoo`
worktree. iXBRL emission lives in Odoo's proprietary
Enterprise edition (`account_reports` module), which kontor cannot
reference and is not visible in the worktree. The community pattern
is "you have the report engine; build your own emitter on top."

### 4.4 — Tryton

Tryton's `modules/account` and adjacent country modules (account_fr,
account_be, account_eu, account_consolidation, edocument_ubl,
edocument_peppol) handle e-invoicing (Peppol, UBL, UN/CEFACT) and
country-specific tax workflows, but **none of them emit XBRL or
iXBRL.** Direct grep across
`/home/christian-weilbach/Development/tryton/modules/` for
"xbrl|ixbrl|ebilanz" returned zero substantive hits (only locale
false positives like "SBR" matching translation keys).

Tryton's reporting approach is different from Odoo's: instead of a
generic engine-keyed report definition, Tryton ships per-country
fiscal modules that hardcode the reporting requirements. This is
the pattern kontor explicitly rejects (per ADR-014 + ADR-019 — keep
the report engine generic; ship country-specific definitions as
data, not code).

### 4.5 — Commercial DRM tools (Workiva, SAP DM, OneStream, IRIS)

The commercial **Disclosure Management** (DRM) category sits
between the ERP and the regulator:

- **Workiva Wdesk** — connects to ERP / consolidation systems,
  combines numbers + narrative, ships iXBRL + XBRL + EDGAR /
  ESEF / HMRC filings. Workiva accounts for "more iXBRL facts
  filed with the SEC than any other provider." (Source:
  [Workiva: SEC Reporting Software](https://www.workiva.com/solutions/sec-reporting);
  [Workiva: Wdesk Benefits](https://8020consulting.com/blog/workiva-wdesk-benefits),
  accessed 2026-05-17.) Workiva also publishes an open-source
  Inline XBRL Viewer
  ([github.com/Workiva/ixbrl-viewer](https://github.com/Workiva/ixbrl-viewer),
  Apache-2.0) that other tools embed.
- **SAP Disclosure Management** — sits on top of S/4HANA Group
  Reporting; produces iXBRL for ESEF, Solvency II, FRS, HMRC
  Company Tax Returns. Workflow per chapter, per editor; refreshes
  from S/4HANA on demand. (Source:
  [SAP Help: Creating Inline XBRL in SAP Disclosure Management](https://help.sap.com/doc/ce00212a04a144f9970fcee0f24774c6/10.1_SP08/en-US/3db93b3053e54ef082466b0c0214f78b.html);
  [SAP Community: S/4HANA + Disclosure Management blog](https://community.sap.com/t5/enterprise-resource-planning-blogs-by-sap/sap-s-4hana-for-group-reporting-delivers-the-last-mile-of-reporting-with/ba-p/13484657),
  accessed 2026-05-17.)
- **IRIS Carbon**, **ez-XBRL**, **DataTracks**, **CoreFiling**,
  **Toppan Merrill**, **Donnelley** — outsourcing / tagging services
  that take a filer's Excel + Word + PDF and ship iXBRL to the
  regulator.

The recurring pattern: **the ERP does not file. A separate
"last-mile" tool handles iXBRL emission, validation, and
transmission.** This is *evidence*, not a verdict — it tells us
the market separates "produce numbers" from "ship iXBRL," and
that the second tier is its own multi-billion-dollar category
(Workiva alone is $700M+ ARR). Whether kontor should *play* in
the last-mile tier is a design call; the existence of the tier
shows it's a real piece of work.

## §5 — What kontor already has that maps naturally

Walking the kontor primitives that already do work XBRL would need:

### 5.1 — `:account-tag/*` ↔ XBRL concept tagging

`schema.clj:420-449` defines a many-cardinality M2M
`:account/tags → :account-tag`, with `:account-tag/name` as an
identity attribute and an optional `:account-tag/country-code`
scope. The declarative report engine
(`src/kontor/report.clj:192-210`, `run-engine :tax-tags`) already
sums postings by tag.

An XBRL concept tag is structurally identical: it's an external
label (a QName like `us-gaap:Revenues`) attached to an account, used
at report time to sum the contributing postings. The natural
mapping:

```clojure
;; Today:
{:account-tag/name "ust-81"
 :account-tag/country-code "DE"}

;; Adding XBRL conceptually:
{:account-tag/name "ifrs-revenue"
 :account-tag/country-code "ESEF"
 :account-tag/xbrl-concept "ifrs-full:Revenue"        ; NEW
 :account-tag/period-type :duration}                   ; NEW (instant | duration)
```

Two new optional attrs on `:account-tag` would let any country-
configured tag carry the XBRL concept it answers to. The existing
report engine doesn't change; a new emitter walks the computed
lines and emits XBRL facts using `:line/value` + the tag's
concept + the report's window for context.

### 5.2 — `:account/external-codes` ↔ multi-regulator concept mapping

ADR-019 + `schema.clj:380-409` already encodes per-(account,
regulator) code mappings. An XBRL taxonomy is *another regulator*
in this exact shape:

```clojure
;; ADR-019 already supports:
{:account-code/regulator :de/skr04
 :account-code/code "4400"}

;; XBRL fits with a new regulator keyword:
{:account-code/regulator :de/hgb-taxonomie-6-8
 :account-code/code "de-gaap-ci:bs.eqLiab.equity.subscribed.subscribedFromAffiliated"}

{:account-code/regulator :esef/ifrs-full
 :account-code/code "ifrs-full:Revenue"}

{:account-code/regulator :us/us-gaap-2024
 :account-code/code "us-gaap:Revenues"}
```

The regulator keyword's naming convention would carry the taxonomy
version, so a 2026 filing using HGB 6.8 and a 2027 filing using HGB
7.0 can coexist on the same account. **No schema change needed.**
The convention is the contract.

This is the most striking alignment in the substrate: ADR-019's
"every account can answer to N regulators with M codes" was designed
for BR's Plano Referencial / DE's DATEV / IFRS group consolidation,
but XBRL taxonomies *are* this exact shape — just larger and more
varied. The kernel didn't know XBRL existed when ADR-019 landed; the
mapping is gratuitous.

### 5.3 — `kontor.report` engines ↔ Odoo / FRS / HGB report engines

`src/kontor/report.clj:172-210` implements two engines
(`:account-codes`, `:tax-tags`). The docstring at
`src/kontor/report.clj:51-53` documents three planned-but-not-built
engines (`:aggregation`, `:domain`, presumably `:external`).

Odoo's `account_report.py:391-396` ships the same five engines with
the same names. The shape of an XBRL calculation linkbase rule —
"this concept's value equals the sum of these N child concepts'
values, each with weight ±1" — is exactly an `:aggregation` engine
expression. The shape of an `:engine :account-codes` line — "sum
postings against accounts whose code matches pattern P" — is the
operational form of a concept-level fact: the line's value IS the
XBRL fact for that concept.

The implication: **kontor's report engine already produces values
that are XBRL fact candidates.** What's missing is (a) the
concept binding (§5.1) and (b) the calculation-linkbase consistency
check that lets you *prove* a parent line equals the sum of its
children. The second part is §7.

### 5.4 — `kontor.financial-statements` ↔ statement composition

`src/kontor/financial_statements.clj:1-178` composes P&L, BS,
cash-flow, and equity-changes statements as section-keyed roll-ups
of report lines. The output shape is:

```clojure
{:statement/name "Gewinn- und Verlustrechnung"
 :statement/sections
 [{:section/code "I" :section/label "Umsatzerlöse"
   :section/lines [{:line/code "1.1" :line/value <Money>
                    :line/postings [<eid>...]}]
   :section/subtotal <Money>}]
 :statement/total <Money>}
```

Map this to an XBRL filing:
- `:statement/name` → the report's presentation linkbase root.
- `:section/code` + `:line/code` → abstract concepts in the
  presentation tree.
- `:line/value` → an `<us-gaap:X contextRef="…" unitRef="…">value</…>`
  fact for the concept the line is bound to.
- `:section/subtotal` → a fact for the section's parent concept,
  computed by summing line values — and verifiable against the
  child facts via calc linkbase.
- `:line/postings [eid …]` → the drill-down path; XBRL's footnote
  linkbase could carry this provenance, though regulators don't
  require it.

The composition is already most of the way to an XBRL emission
target. The piece that's missing is the *binding* — currently
`:line/code` is a string like `"1.1"`, opaque to any external
taxonomy. Tagging a line with an XBRL concept (via §5.1) closes
the loop.

### 5.5 — `:posting/entity` + ADR-073 consolidation ↔ XBRL dimensions

ADR-031's `:posting/entity` and ADR-073's `consolidate!` give
kontor multi-entity facts: the same conceptual line ("Revenue") has
different values for different entities (Acme-US-Inc, Acme-DE-GmbH,
Acme-Group consolidated). An XBRL filing of a multi-entity group
emits separate `<context>` elements with different `<identifier>`
values and the same concept reference; or, for segment reporting,
uses XBRL Dimensions (XDT) to declare a `BusinessSegmentsAxis`
dimension and emit one fact per segment + one consolidated fact.

ADR-073's `:transaction/consolidation-source-entity` +
`:transaction/consolidation-kind` provide the provenance an XBRL
filing would attribute to entities; ADR-072's `FxRateProvider`
gives the currency translation that IAS 21 / ESEF demand. The
substrate has the underlying dimensionality; the question is
whether kontor surfaces it as XBRL contexts/dimensions, or leaves
that to a downstream tool.

### 5.6 — Bitemporal alignment

Already covered in §2.5: XBRL's instant/duration period semantics
map onto kontor's `(d/valid-at db t)` and report-window APIs
without retrofit. Kontor's `:as-of-tx` axis is strictly stronger
than what XBRL specifies; an XBRL restatement is a new filing
that supersedes a prior one, while kontor can express both
"original" and "restated" through the same axes (`as-of-tx` for
the original; current snapshot for the restated). This is
*better* than what most XBRL filers can do — it's the substrate
showing through.

## §6 — Compositionality implications

XBRL taxonomies are **extensible by design.** The pattern repeats
across every major regulator:
- IFRS Foundation publishes a base taxonomy.
- ESMA extends it with ESEF.
- ESMA can extend further with EU member-state add-ons.
- Filers can extend further with company-specific concepts.

US-GAAP works the same way: FASB → SEC + DQCRT → filer extensions.
DE works the same way: HGB-Taxonomie → BMF-specific sub-modules →
filer-specific extension content.

This is **the same shape as kontor's kernel + `kontor-l10n-*` +
consumer architecture.** Both stack extension on top of a core
abstraction; both bottom out at a "company-specific" layer that the
end user owns; both expect the layers to compose without the lower
layers knowing about the upper ones.

The compositional design question for kontor is: **where does the
taxonomy data LIVE?** Three plausible answers:

1. **In-DB as first-class entities.** A new `:xbrl-concept` namespace
   carries every concept (or every concept the consumer cares about)
   as a datahike entity. This makes the data bitemporal-aware
   (an auditor's "what did the IFRS taxonomy look like in 2022?"
   is a `(d/as-of db <2022-instant>)` query), history-recorded,
   queryable via Datalog, and indexable via stratum. It also makes
   the consumer's DB ~Mb–Gb bigger and adds a (substantial) load
   step.
2. **As a static catalog ingested at boot or per-filing.** Concepts
   live in EDN files in a `kontor-taxonomy-{cc}` companion module,
   loaded into memory at startup. Bitemporality lives in the module
   versioning (use the 2024 module to file for 2024 facts), not in
   datahike. Smaller, simpler, less queryable.
3. **As a companion-tier concern, not a kernel concern.** The
   kernel ships *hooks* (the `:account-tag/xbrl-concept` attr from
   §5.1), and a `kontor-filing-{cc}` module handles concept
   metadata + emission + validation entirely outside the kernel.
   Kernel knows nothing about XBRL.

The tradeoff axis is straightforward:

| Approach | DB size | Queryability | Bitemporal | Modules to coordinate |
|----------|---------|--------------|------------|-----------------------|
| In-DB    | Large   | Excellent    | Yes        | One (kernel-extended) |
| Catalog  | Medium  | Good         | Module-versioned | Two (kernel + catalog module) |
| Companion-only | Small   | Per-companion | Companion's call | Three+ (kernel + tag attr + filing companion) |

The companion-only approach (option 3) is the lowest-commitment for
the kernel and aligns with how ADR-005 / ADR-006 / ADR-017 / ADR-029
handle tax / l10n / e-invoice / costing: kernel ships the protocol
and the seam; companions ship the data + the heavy logic. The
in-DB approach (option 1) is the most aggressive but also most
kontor-shaped — bitemporal taxonomy snapshots fall out for free,
which auditor workflows actively need.

The catalog approach (option 2) is intermediate: it captures the
"taxonomy versions are themselves data" insight without imposing
DB bloat. Companion modules already ship catalog-shaped data —
country tax tables, default chart of accounts, document type
registries — so this is well-trodden ground.

## §7 — Consistency implications

The XBRL calculation linkbase encodes per-statement-shape invariants:
"Total Revenue = sum of Product Revenue + Service Revenue +
Subscription Revenue." This is a different invariant from sum-to-
zero.

### 7.1 — Two invariants, orthogonal

Today kontor enforces:
- **Sum-to-zero** (`kontor.posting`): per (entity, ledger,
  commodity), every transaction's postings net to zero. Enforced
  at write time via `transact-with-validation`.

Calculation-linkbase consistency would add:
- **Statement-shape consistency** (proposed): for any computed
  report, every parent line's value equals the weighted sum of its
  child lines' values. Enforceable at *read* time (compute the
  report, walk the parent-child tree, verify).

These are orthogonal:
- A journal can be perfectly sum-to-zero (every tx balances) and
  fail statement consistency (the chart-of-accounts mapping into
  the statement layout is broken — e.g. an account tagged with
  both `revenue-product` and `revenue-service` double-counts at
  the parent).
- A statement can be self-consistent (parents = sum of children)
  and the underlying journal can be unbalanced (some posting was
  silently retracted — though ADR-007 prevents this).

So calc-linkbase consistency is a *new kind* of check, not a
generalization of sum-to-zero.

### 7.2 — Where would the check live?

Three plausible places:

a) **Inside `kontor.report/compute-report`** as an automatic post-
   step. After the engines run, walk the line tree (which would
   need parent-child structure added) and verify. Surfaces
   inconsistencies as warnings on every compute. Pro: zero
   friction; auto-protects every report consumer. Con: requires
   making the line tree explicit (today reports are flat lists);
   adds compute cost on every call.

b) **A separate `kontor.taxonomy` namespace** with explicit
   `verify-calculation-linkbase!` / `verify-statement-consistency`
   helpers. Called by consumers who care. Pro: opt-in, focused.
   Con: easy to forget; consistency becomes a "did we remember
   to check?" liability.

c) **A new `:engine :calculated` (or `:engine :sum-of-children`)**
   that, instead of querying the journal, computes the parent
   value from its declared children. This makes the parent
   *definitionally* equal to the sum of children — there's nothing
   to inconsistent-be. Pro: structurally impossible to be
   inconsistent. Con: loses the ability to detect when the
   journal *didn't* match what the statement layout expected
   (because the engine never goes back to the journal for the
   parent — it just sums what the children said).

These are not mutually exclusive. A reasonable layering would be:
- Default engine for derived lines: `:calculated` (option c) —
  parent line value is *defined* as sum of children. Most
  statements have this structure anyway.
- Optional consistency check (option a/b): when a parent line IS
  computed from the journal (its own `:engine :account-codes`),
  verify it matches `sum(children)` and surface the diff. This
  is a *cross-check*, catching cases where the chart-of-accounts
  → statement-line mapping has drifted out of sync.

### 7.3 — Cross-statement consistency (BS-PL-CF interlocks)

XBRL's calc linkbase also encodes inter-statement invariants:
- Net Income from P&L = retained-earnings increment in BS = Net
  Income line in CF.
- Cash + cash equivalents (closing) on BS = opening + Σ cash-flow
  movements on CF.
- Total comprehensive income from OCI = equity-changes movement on
  the equity-changes statement.

Kontor has the building blocks: `kontor.closing/close-fiscal-year!`
(`src/kontor/closing.clj:1-46`) posts the closing tx that moves P&L
to retained-earnings; `kontor.financial-statements/compute-cash-flow`
already validates `:statement/reconciliation` between the indirect-
method CF and actual cash movement
(`src/kontor/financial_statements.clj:241-266`). The reconciliation
fields (`:expected`, `:actual`, `:difference`, `:ok?`) are exactly
the shape XBRL calc consistency reports back: expected vs actual vs
diff vs pass/fail.

The cross-statement interlocks live at *period-close* time today
(closing tx makes net income → retained earnings explicit). A calc-
linkbase verification would extend this to ALL the interlocks the
taxonomy declares, not just the one closing tx enforces.

### 7.4 — Bitemporal calc consistency

A back-correction reissues a fact at an earlier valid-time
(per the polygon supersession discussion in research note 77 and
ADR-073's `:db.valid/from`-stamped consolidation entries). The calc
linkbase says siblings must sum to parent; if the back-correction
lands on a child but not the parent, the consistency check fires.
This is the *correct* behavior — but it tells us that calc-linkbase
verification must compose with `(d/valid-at db t)`. The verifier
needs to be bitemporal-axes-aware:

```clojure
(verify-calculation-linkbase! conn statement-def
                              {:as-of-tx tx-instant
                               :as-of-valid valid-instant
                               :taxonomy :ifrs-full-2024})
```

The `:as-of-valid` axis answers: "as of what we believed about
fact values on date X" (post-back-correction). The `:as-of-tx`
axis answers: "as of what we knew on system date Y" (pre or post
the back-correction commit). Auditors care about both.

The good news: kontor's bitemporal substrate handles this without
any new machinery. `compute-report` already takes `:as-of-tx`. A
calc-linkbase verifier sits on top with the same options forwarded.

### 7.5 — What the calc-linkbase invariant catches that today's substrate doesn't

Concrete scenarios kontor would catch automatically with calc-
linkbase verification:
- An account is renamed but its `:account-tag` set wasn't updated
  → the account drops out of its parent line's sum, and the
  consistency check fires.
- An l10n module ships a new chart-of-accounts version where a
  previously-leaf account is split into two; if one of the two
  isn't tagged correctly, the parent sum is wrong and the check
  catches it.
- A new tax rate is introduced (effective-dated per ADR-026) and
  the corresponding `:account-tag` isn't extended → the new tax's
  postings sit in the parent line but not a child line, and the
  parent → sum-of-children check fires.

These are real maintenance bugs the substrate currently has no
hook to catch. They tend to surface at year-end review when an
auditor or accountant manually reconciles totals. Calc-linkbase
verification automates that reconciliation against a declarative
spec.

## §8 — Three design directions

Each direction is internally consistent. The maintainer's choice is
which level of XBRL alignment is worth the kernel's surface-area
investment.

### Direction A — Minimal hooks (~0 LoC kernel)

**Schema change**: add two optional attrs on `:account-tag` —
`:account-tag/xbrl-concept` (string IRI like `"ifrs-full:Revenue"`)
and `:account-tag/period-type` (`:instant | :duration`). Document
the convention; let consumers and future filing companions carry
the rest. One short ADR.

**What it unlocks**: a downstream emitter (in a `kontor-filing-{cc}`
module or directly in a consumer app) walks the report output, looks
up the concept on each line's tag, emits a fact. Kernel does nothing
XBRL-specific.

**What it does NOT do**: no calc-linkbase verification, no
dimension-aware emission, no taxonomy versioning, no validation.

**Reversibility**: very high. If kontor moves to Direction B or C
later, the attr is a no-op for consumers that don't use it.

**Effort**: an afternoon. **Risk**: a single account that contributes
to multiple concepts across taxonomies (`us-gaap:Revenues` AND
`ifrs-full:Revenue` for a dual-filer) needs multiple tags because
the attr is cardinality-one. Acceptable; matches ADR-019.

### Direction B — `kontor.taxonomy` substrate primitive (~500–800 LoC)

**Schema additions** (on top of Direction A): a new `:xbrl-concept`
entity with `:xbrl-concept/iri` (unique-identity), `:xbrl-concept/taxonomy`
(keyword: `:ifrs-full-2024`, `:us-gaap-2025`, `:hgb-taxonomie-6-8`),
`:xbrl-concept/period-type`, `:xbrl-concept/balance` (`:debit | :credit | nil`),
`:xbrl-concept/abstract?`, `:xbrl-concept/parent` (ref —
calc-linkbase parent), `:xbrl-concept/weight` (BigDecimal, typically
±1).

**Code**:
- `kontor.taxonomy/import-taxonomy!` — bulk-load a taxonomy's concept
  tree from consumer-supplied EDN. Kernel does not parse XSD.
- `kontor.taxonomy/concept` — lookup by IRI.
- `kontor.taxonomy/verify-calculation-linkbase!` — given a computed
  statement, walk parent-child concepts and verify
  `parent ≈ sum(weight × child)`. Returns inconsistencies
  `[{:concept iri :expected M :actual M :difference M :ok? bool} …]`.
- `kontor.taxonomy/report-from-taxonomy` — produce a report def
  automatically from a taxonomy + an account→concept mapping.

**Per-country companion**: `kontor-l10n-{cc}-taxonomy` ships the
imported EDN form of the relevant taxonomy (preprocessed from Arelle
or hand-curated). Two ADRs (schema + verification API).

**What it unlocks**: substrate-level consistency checks; bitemporal
taxonomy queries (`(d/as-of db <2022>)` → IFRS 2022 concepts); a
foundation Direction C can build on.

**What it does NOT do**: no iXBRL emission; no XSD ingestion; no
formula-linkbase (only calc linkbase).

**Reversibility**: medium. Schema is committed once shipped; API can
evolve. **Effort**: 2–4 weeks including performance work on the
verifier for 17k-concept taxonomies. **Risk**: scope creep —
"verify calc linkbase" / "do all XBRL validation" / "formula
linkbase rules" / "dimension validity" is a slippery slope; pin the
boundary explicitly in the ADR.

### Direction C — Full iXBRL emission pipeline (companion-tier, multi-month)

**`iXBRLProvider` protocol** in the kernel (sibling to
`EInvoiceProvider`): `taxonomy-id`, `emit-instance` (returns
`{:ixbrl/payload :ixbrl/content-type :ixbrl/intended-for
:keep-on-file|:transmit|:clearance}`), `validate`, `transmit!`.

**Per-country companions** (each multi-month): `kontor-filing-uk-frc`
(highest priority — Apr 2026 mandate), `kontor-filing-de-ebilanz`,
`kontor-filing-us-sec`, `kontor-filing-eu-esef`. **Validation**:
shell out to Arelle at filing time (annual, human-driven — NOT CI).

**What it unlocks**: kontor consumers file iXBRL to regulators
without a Workiva-class tool. Major value for UK mandate; substantial
for SME-tier DE / EU.

**What it does NOT do**: the *narrative* side (MD&A, footnotes,
auditor letter — Workiva territory); disclosure-management workflow
(chapter ownership, approval — also Workiva territory).

**Reversibility**: low — once a UK customer files annual accounts
via `kontor-filing-uk-frc`, you own that path for the historical
filing window (3+ years).

**Risk**: highest. Per-country effort doesn't compose. Remediation
loops are slow (you don't find out until you file). Regulator
filing pipelines are exacting.

**Critical observation**: Direction C is **not** prevented by
Direction A or Direction B. Both are foundations C can build on.
Shipping A or B does not commit you to C.

### Comparison

|                        | Direction A | Direction B | Direction C |
|------------------------|-------------|-------------|-------------|
| Kernel LoC             | ~30         | ~500–800    | ~200 (protocol only) + multi-month companions |
| New ADRs               | 1           | 2           | 1 (protocol) + per-country |
| Consumer value         | Hook for future emitters | Substrate-level consistency checks | End-to-end filing |
| Reversibility          | Very high   | Medium      | Low |
| Catches statement bugs | No          | Yes         | Yes (via the bundled verifier) |
| Files to regulator     | No          | No          | Yes |
| Time to first ship     | Day         | Month       | Quarter+ per country |
| Multi-runtime risk     | Zero        | Zero        | Arelle shell-out only |

## §9 — Open questions for the maintainer

1. **Which taxonomies merit first-class consumer support, if any?**
   The country priority ADR-004 says DE → CA → US. XBRL coverage is
   strongest for US (SEC), strong for EU (ESEF), nontrivial for DE
   (E-Bilanz), absent for CA (voluntary on SEDAR+). If "first
   country to file iXBRL" is a roadmap goal, **the UK** is the
   most urgent market signal — the April 2026 mandate just
   landed and there's a real underserved-small-business market
   that doesn't fit Workiva's pricing. UK isn't in ADR-004 today.
2. **Is calc-linkbase verification a kernel concern or a companion
   concern?** Direction B puts it in the kernel; Direction A leaves
   it to companions. The argument for the kernel: consistency
   checks are a substrate property like sum-to-zero, and putting
   them in companion-land means every companion duplicates the
   walk-the-tree code. The argument against: the kernel adds
   ~500–800 LoC and a new ADR-shaped concern (`:xbrl-concept/*`)
   that consumers without filing needs never touch. Lean toward
   the kernel if multiple companions would benefit; lean toward
   the companion if only one or two will.
3. **Does iXBRL emission belong in kontor at all?** The market's
   answer is "no" — every reference ERP punts to a DRM tool
   (Workiva, SAP DM, IRIS Carbon). Adding it makes kontor
   substantially more "end-to-end" for consumers but pulls kontor
   into a regulator-relationship dynamic the maintainer has
   explicitly avoided (ADR-005 around tax: kernel ships the
   protocol, partners ship the integrations). Direction C is
   honest about being companion-tier work.
4. **How does the XBRL concept relate to existing tags + codes?**
   Three plausible models:
   - **Subsume**: `:account/external-codes` for *all* per-regulator
     codes, where XBRL concepts are just another regulator. Single
     mechanism. (Recommended by §5.2.)
   - **Coexist**: tags carry XBRL concepts; external-codes carry
     numeric chart codes; they live in parallel. Slightly cleaner
     separation but two mechanisms for the same idea.
   - **Reuse `:account-tag`**: every XBRL concept becomes an
     account-tag whose `:account-tag/name` is the concept IRI.
     Lightweight; minimal schema. Slight risk of tag namespace
     pollution. (Recommended by §5.1.)

   These are not mutually exclusive — the recommended pairing is
   tag for the per-line aggregation key (engine-side), external-
   code for the per-account regulator identity. Pick one as the
   *primary* binding for any consumer/companion that integrates.
5. **Bitemporal taxonomies — substrate or consumer responsibility?**
   "What did IFRS 2022 look like?" is a real auditor query. If the
   taxonomy lives in-DB (Direction B option 1), the answer is
   `(d/as-of db <2022-instant>)`. If it lives in module versions
   (Direction A or B option 2), the answer is "load the
   `kontor-l10n-{cc}-taxonomy-2022` module." Both work; the
   in-DB version is more queryable but heavier. Worth being
   intentional about which one you commit to.
6. **What's the right granularity for "XBRL fact provenance"?**
   When an XBRL filing is restated, regulators want to know which
   facts changed and why. Kontor's bitemporal substrate already
   tracks this at the posting level. The question is whether to
   surface restatement as a first-class XBRL concept (a separate
   filing with `<ix:hidden>` references back to the original) or
   as a bitemporal query result presented as "this is what the
   filing said in 2024-Q1 vs 2024-Q4." The substrate gives you
   the second; the regulator wants the first. There's a
   translation step.
7. **Is there a smaller-scope "data export to feed Workiva /
   IRIS / Toppan" mode that's actually the right v1?**
   The DRM tools all accept structured-data exports from upstream
   ERPs. A kontor consumer might never emit iXBRL itself, but
   shipping a clean `(export-to-drm conn taxonomy-id options)`
   that produces a JSON / CSV / XBRL-instance-without-iXBRL-
   inlining payload is a small companion that covers the
   "interop with the filing tool" use case without taking on the
   full filing-tool responsibility. This is the "minimum viable
   Direction C-adjacent" option and arguably the right pragmatic
   next step.

## §10 — Sources

All URLs accessed 2026-05-17.

### kontor code (file:line)

- `CLAUDE.md` — project posture.
- `doc/decisions.md:696-789` — ADR-019 (multi-regulator `:account-code`).
- `doc/decisions.md:793-933` — ADR-020 (document-type registry).
- `doc/decisions.md:1721-1808` — ADR-031 (per-entity sum-to-zero).
- `doc/decisions.md:7734-7832` — ADR-073 (consolidation primitive).
- `src/kontor/schema.clj:231-320` — `:account/*` (incl. `:account/tags`, `:account/external-codes`).
- `src/kontor/schema.clj:330-378` — `:document-type/*` (ADR-020).
- `src/kontor/schema.clj:380-409` — `:account-code/*` (ADR-019).
- `src/kontor/schema.clj:420-449` — `:account-tag/*`.
- `src/kontor/schema.clj:3168-3197` — consolidation tx attrs (ADR-073).
- `src/kontor/report.clj:1-53` — declarative report engine docstring.
- `src/kontor/report.clj:172-210` — `:account-codes` + `:tax-tags` engines.
- `src/kontor/report.clj:258-342` — bitemporal `compute-report` (`:translate-to` / `:fx-provider` / `:entity` / `:ledger`).
- `src/kontor/financial_statements.clj:1-178` — P&L / BS section composer.
- `src/kontor/financial_statements.clj:200-266` — `compute-cash-flow` with `:statement/reconciliation` (parallels XBRL calc-consistency error reports).
- `src/kontor/financial_statements.clj:268-354` — `compute-equity-changes` (`:component/reconciles?`).
- `src/kontor/einvoice_provider.clj:1-157` — `EInvoiceProvider` protocol shape (model for a potential `iXBRLProvider`).
- `src/kontor/closing.clj:1-46` — year-end close (Net Income → Retained Earnings interlock).

### Reference systems (worktree)

- Odoo CE `addons/account/models/account_report.py:1-967`. Engines at lines `391-396, 494-554`. Confirmed: **no XBRL / iXBRL emission** anywhere in the worktree (zero hits for `xbrl|ixbrl|ebilanz|e-bilanz`).
- Tryton `modules/`. Confirmed: **no XBRL / iXBRL** anywhere (zero substantive hits). Closest module is `account_consolidation`, which composes a consolidated journal but does not emit external filings.

### External: XBRL specifications + DQC

- [xbrl.org: XBRL Standard index](https://specifications.xbrl.org/)
- [xbrl.org: XBRL Essentials](https://specifications.xbrl.org/xbrl-essentials.html)
- [xbrl.org: Presentation + table linkbase intro](https://specifications.xbrl.org/presentation.html)
- [xbrl.org: XBRL Dimensions 1.0](https://specifications.xbrl.org/work-product-index-group-dimensions-dimensions.html)
- [xbrl.org: Calculations 1.1 PWD](https://www.xbrl.org/Specification/calculation-1.1/PWD-2021-11-08/calculation-1.1-PWD-2021-11-08.html)
- [openriskmanual.org: XBRL Calculation Linkbase explainer](https://www.openriskmanual.org/wiki/XBRL_Calculation_Linkbase)
- [en.wikipedia.org: XBRL article](https://en.wikipedia.org/wiki/XBRL)
- [xbrl.us: Using Date Contexts](https://xbrl.us/guidance/using-date-contexts-in-different-scenarios/)
- [xbrl.us: dqc_0118 — calculation check](https://xbrl.us/data-rule/dqc_0118/)

### External: regulator filings + mandates

- [SEC.gov: Inline XBRL](https://www.sec.gov/data-research/structured-data/inline-xbrl)
- [SEC EDGAR XBRL Guide May 2026 (PDF)](https://www.sec.gov/files/edgar/filer-information/specifications/xbrl-guide.pdf)
- [ESMA: Electronic Reporting / ESEF](https://www.esma.europa.eu/issuer-disclosure/electronic-reporting)
- [ESMA: ESEF Taxonomy 2024](https://www.esma.europa.eu/document/esef-taxonomy-2024)
- [xbrl.org: ESEF 2025 IFRS update](https://www.xbrl.org/news/esma-updates-esef-with-2025-ifrs-taxonomy-critical-changes-for-early-ifrs-18-adopters/)
- [alto-accounting.com: iXBRL Accounts Filing 2026 (UK)](https://www.alto-accounting.com/insights/ixbrl-accounts-filing-companies-house-2026)
- [FRC: 2026 Taxonomy Suite](https://www.frc.org.uk/library/standards-codes-policy/accounting-and-reporting/frc-taxonomies/current-frc-taxonomy-suites/2026-frc-taxonomy-suite/)
- [gov.uk: Businesses XBRL guide](https://www.gov.uk/government/publications/xbrl-guide-for-uk-businesses/xbrl-guide-for-uk-businesses)
- [de.xbrl.org: HGB-Taxonomie 6.8](https://de.xbrl.org/taxonomien/e-bilanz-hgb-taxonomie-version-6-8/)
- [myebilanz.de docs](https://www.myebilanz.de/myebilanz.pdf)
- [OSC: XBRL (Canada)](https://www.osc.ca/en/xbrl)
- [xbrl.org: Canada open data standards consultation](https://www.xbrl.org/news/canada-to-embrace-open-data-standards-for-investment-fund-reporting/)
- [xbrl.org: Japan 2027 EDINET](https://www.xbrl.org/news/japan-progresses-with-2027-edinet-taxonomy-plans/)
- [axiora.dev: EDINET for Developers](https://axiora.dev/en/blog/edinet-for-developers)
- [github.com/axioradev/edinet-xbrl](https://github.com/axioradev/edinet-xbrl)
- [EBA: Reporting framework 4.2](https://www.eba.europa.eu/risk-and-data-analysis/reporting-frameworks/reporting-framework-42)
- [regulationtomorrow.com: EBA simplifies CRR reporting (Apr 2026)](https://www.regulationtomorrow.com/2026/04/eba-consults-on-simplifying-supervisory-reporting-under-crr/)

### External: taxonomies + extension policy

- [FASB: 2024 GAAP Financial Reporting Taxonomy](https://www.fasb.org/page/detail?pageId=/projects/FASB-Taxonomies/2024-gaap-financial-reporting-taxonomy.html)
- [XBRL US: 2024 US-GAAP Taxonomy](https://xbrl.us/xbrl-taxonomy/2024-us-gaap/)
- [XBRL US: 2025 US-GAAP Taxonomy](https://xbrl.us/xbrl-taxonomy/2025-us-gaap/)
- [Workiva: 2024 US-GAAP Update Guide](https://www.workiva.com/blog/your-guide-2024-us-gaap-taxonomy-update)
- [IFRS Foundation: IFRS Accounting Taxonomy 2024](https://www.ifrs.org/issued-standards/ifrs-taxonomy/ifrs-accounting-taxonomy-2024/)
- [Workiva: 2024 IFRS Taxonomy Update Guide](https://www.workiva.com/blog/your-guide-2024-ifrs-taxonomy-update)
- [Accounting Today: FASB extensible lists guide](https://www.accountingtoday.com/news/fasb-offers-guide-to-xbrl-extensible-lists)
- [KPMG India: MCA XBRL Mandate](https://assets.kpmg.com/content/dam/kpmg/pdf/2016/03/XBRL.pdf)
- [ACRA Singapore: XBRL filing requirements](https://www.acra.gov.sg/xbrl-filing-and-resources/who-needs-to-file-financial-statements)

### External: OSS XBRL implementations

- [github.com/Arelle/Arelle](https://github.com/Arelle/Arelle) — Apache-2.0, Python; XBRL International certified validating processor.
- [arelle.org](https://arelle.org/arelle/)
- [pypi.org/project/arelle-release](https://pypi.org/project/arelle-release/) — 2.37.49.
- [pypi.org/project/arelle-mcp/1.0.1](https://pypi.org/project/arelle-mcp/1.0.1/) — Arelle MCP server (Apr 2026 — signal of active maintenance).
- [github.com/ammasjk/xbrlj](https://github.com/ammasjk/xbrlj) — Java XBRL/iXBRL parser.
- [sourceforge.net/projects/xbrlapi](https://sourceforge.net/projects/xbrlapi/) — older Java XBRL API.
- [github.com/nortal/xbrl-engine](https://github.com/nortal/xbrl-engine) — Java XBRL engine.
- [github.com/Workiva/ixbrl-viewer](https://github.com/Workiva/ixbrl-viewer) — Apache-2.0 iXBRL viewer.

### External: commercial DRM platforms

- [Workiva: SEC Reporting](https://www.workiva.com/solutions/sec-reporting)
- [Workiva: XBRL/iXBRL solutions](https://www.workiva.com/solutions/xbrl-and-ixbrl)
- [8020consulting.com: Wdesk benefits](https://8020consulting.com/blog/workiva-wdesk-benefits)
- [SAP Help: Creating iXBRL in SAP Disclosure Management](https://help.sap.com/doc/ce00212a04a144f9970fcee0f24774c6/10.1_SP08/en-US/3db93b3053e54ef082466b0c0214f78b.html)
- [SAP Community: S/4HANA + Disclosure Management](https://community.sap.com/t5/enterprise-resource-planning-blogs-by-sap/sap-s-4hana-for-group-reporting-delivers-the-last-mile-of-reporting-with/ba-p/13484657)
- [iriscarbon.com: SEC iXBRL Mandate](https://iriscarbon.com/mandates/sec-ixbrl-mandate/)
- [iriscarbon.com: SSM MBRS Malaysia](https://iriscarbon.com/mandates/ssm-mbrs/)

### Related kontor research notes

- `doc/research/76-review-after-adr-071-072-073.md` — ADR-073 review; calc-consistency-shaped reconciliation gap.
- `doc/research/77-supersession-comparison-xtdb-stratum.md` — bitemporal supersession; relevant to §7.4.
- `doc/research/69-architecture-review-and-fp-model.md` — kontor's reporting layer in substrate context.

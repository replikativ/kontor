# kontor — what it is and why it matters

This document is for people **evaluating** kontor: founders building a
vertical SaaS, technical leads at an accounting consultancy, product
managers shopping for an accounting substrate, finance leaders sizing
up the build-vs-buy question against SAP / NetSuite / Odoo / Tryton.

If you've not yet decided whether kontor is for you at all, read
[doc/start-here.md](start-here.md) first — it walks one concrete
story (a 3-year German GmbH with a backdated correction) through
the substrate and shows you what the bitemporal axis buys you that
a traditional ledger cannot. This document is the longer-form
companion: the eight kernel concerns, who kontor is for, and where
it sits in the competitive landscape.

If you're a Clojure developer who already decided kontor is the right
thing and wants to USE it, skip to [doc/programming.md](programming.md)
and [the ADRs](decisions.md). This doc is the pitch.

## The elevator pitch

kontor is **the bookkeeping kernel underneath an accounting product
— the part that has to be correct**. It is **not an ERP, not a UI,
not a country-specific package**. It is the substrate: the schema,
the validators, the audit chain, the **bitemporal time model**
(what the books said then vs. what we know now, as two first-class
clocks on every fact), the status machines, the legal-hold story,
the consent + retention + DSAR plumbing, the cross-module
composition primitive, the trans-national substrate (FX, tax,
consolidation, 11 country payroll adapters). You build the product
(or the per-country package) on top.

The bet: every accounting platform eventually has to answer the
same eight questions (audit chain, bitemporal restatement,
multi-entity intercompany, legal hold, retention / DSAR, segregation
of duties, atomic multi-module events, status-machine lifecycles).
Traditional ERPs answer them as **bolt-ons** — modules, partners,
add-ons, custom code — because the kernel wasn't designed for them.
kontor answers them as **kernel concerns**, so the substrate is
correct by default and your product layer can focus on the business
logic that actually differentiates it.

The headline capability is **bitemporal correctness**. Two
showcases demonstrate it end-to-end:

- [Showcase 05 — Apple 10-K/A bitemporal restatement](showcases/05_apple_10k_bitemporal.clj)
  ingests Apple's actual 2009 10-K and the 2010 amendment from SEC
  EDGAR. `(d/valid-at db #inst "2009-12-01")` returns the original
  AccruedLiabilities figure ($3.719B); `(d/valid-at db #inst
  "2010-02-01")` returns the ASC 605-25 restatement ($4.224B). Real
  public data; both filings are first-class facts.
- [Showcase 06 — Multi-year DE GmbH with a backdated correction](showcases/06_de_gmbh_multi_year.clj)
  runs three years of a synthetic Acme GmbH. In Y2 Q4 the
  Steuerberater catches a Y1 misclassification (§4(5) Nr. 2 EStG —
  business meal posted as travel); the correction is a new write at
  past valid-time, and both views — "what the Y1 books said at
  filing" and "what they say now, restated" — remain queryable
  forever.

License: EPL-1.0. Runtime: Clojure on the JVM. Storage: datahike
(immutable, content-addressed, datalog-queryable).

## What kontor IS — and what it isn't

| Layer | Owns | Doesn't own |
|---|---|---|
| **kontor (the kernel)** | Schema, postings, ledgers, periods, transactions, the validator gate, kontor.process, status machines, approval policies, audit-docs, legal holds, retention + DSAR + consent shape, bitemporal queries, sealing, FX + tax + consolidation + payroll *provider protocols*, McComb-style external-concept seams, `kontor.agent-tools` catalog | Any UI, any chart of accounts, any tax rates, any FX rates, any localization data, any business policy, any MCP / JSON-RPC transport |
| **kontor-l10n-** *(separate modules)* | German / US / Brazilian / Indian / Japanese / Chinese / Austrian / French / etc. chart-of-accounts seeders, tax + FX provider stubs, statutory report shapes, per-jurisdiction retention seeds | Tax rates themselves (the consumer holds these — see ADR-005), engine-specific compliance data |
| **kontor-payroll-** *(11 country modules)* | DE-DATEV-LODAS, US-ADP-GLI, CA (CRA + RL-1), FR-DSN, AU-STP-Phase-2, BR-eSocial, MX-CFDI-Nómina, IN (TDS + PF + ESI + PT), JP-Gensen, CN-IIT, AT-mBGM — each parses an engine export, posts to the country's chart, and emits the regulator filing | Live integrations with the payroll engines themselves; the providers are file-shaped |
| **Consumer apps** *(beleg, simmis, your product)* | UI, business workflows, integrations, tax-rate sourcing (Avalara/TaxJar), MCP transport (we recommend dvergr), industry-specific schemas, your customers | The eight kernel concerns above (kontor handles those) |

The split exists because **the kernel is reusable across products and
the products are not reusable across customers**. A consultancy
building three different SaaS apps for three different verticals
uses one kontor and three different consumer layers. A traditional
ERP couples all of this together and pays the cost when any
customer needs a deviation.

## The eight pains kontor solves at the kernel

### 1. Audit chain that's actually a chain

Every commit in kontor is a datahike transaction. Every datahike
transaction is content-addressed and immutable. The audit trail
isn't a log file the application writes — it's the storage layer
itself. You can ask "what did the books say on 2024-03-15?" and get
the literal bytes from that moment, signed by their content hash.

Traditional ERPs: an audit log table the application code writes
into. You trust the application code to write it (no enforced
audit), you trust the database admin not to UPDATE the table
(it's mutable), and you trust the backup-restore story to preserve
historic state (it usually doesn't).

kontor: cryptographic chain by construction. Auditors can verify
without trusting the application code.

### 2. Bitemporal correctness — the restated past

Accounting has TWO clocks: when did the event happen in the
business world, and when did we record it in the system. Examples:
a Q1 invoice corrected in April, an asset sale backdated to month-
end, a payroll adjustment that "should have been" pre-tax, a Y1
expense miscategorized in §4(5) EStG that the Steuerberater catches
during the Y2 Jahresabschluss review. Every accounting professional
has war stories about getting these backward.

kontor stores both axes in every transaction (`:db.valid/from` /
`:db.valid/to` on the commit-tx, plus datahike's `:db/txInstant`).
A query like "what did our books LOOK LIKE on March 31, including
only what we KNEW on March 31" is one line of datalog. The same
query "with everything we now know, restated to March 31" — also
one line. The two axes don't get confused; the substrate enforces
it. A correction is a *new write at a past valid-time* paired with
a `close-validity!` call that closes the prior tx's window — never
an in-place edit, never a `:db/retract` on the original. Both views
remain queryable forever, and an auditor can replay either timeline.

Traditional ERPs: one axis (system time); corrections are reversing
journal entries with explanatory narratives. "What did our books
LOOK LIKE on March 31?" requires backup tapes.

The two canonical demonstrations:

- [Showcase 05](showcases/05_apple_10k_bitemporal.clj) reproduces
  Apple's actual 2009 10-K → 2010 10-K/A restatement (the iPhone
  ASC 605-25 multiple-element re-recognition) from real SEC EDGAR
  JSON. `(d/valid-at db t)` returns the authoritative figure at any
  timeline point. No XBRL parser required — the EDGAR companyfacts
  API ships pre-parsed bitemporal-ready facts.
- [Showcase 06](showcases/06_de_gmbh_multi_year.clj) follows three
  years of Acme GmbH (München) including the Y2 backdated
  correction described above, a Y3 employee termination + DSAR, and
  a retention-floor sweep against the `kontor-l10n-de`
  jurisdiction-keyed retention seeds. The same query infrastructure
  carries all three stories.

### 3. Multi-entity intercompany — built in, not bolted on

A company with a US LLC and a German GmbH posts payroll in the
GmbH and consults the LLC. The two entities settle via
intercompany invoices. Traditional ERPs require either separate
databases (no consolidation), an "intercompany module" (an add-on
with its own data model), or extensive customization.

kontor's `:transaction/posted-from-entity` + multi-ledger postings
mean intercompany is just two postings tagged with the right
entities. Consolidation is a query. ADR-031 covers the model;
showcase #4 walks through a real scenario.

### 4. Legal hold — kernel-grade, not a third-party module

If your customer is sued or investigated, certain data has to be
preserved literally (no deletion, no modification, no anonymization)
until counsel releases the hold. Most ERPs have this as a paid
add-on or a per-customer custom build.

kontor ships ADR-049: `:legal-hold` entities with bitemporal scope
queries, a middleware that BLOCKS any destructive write to held
entities (you can't silently retract a posting under hold), an
audit-doc-required release flow, and a per-hold "what's covered
right now / what was covered last June" temporal answer. Built into
the validator gate; impossible to bypass without code that
intentionally circumvents it.

### 5. Retention + DSAR + consent — compliance plumbing in the kernel

GDPR Article 17 ("right to erasure"). CCPA §1798.105. SOX 7-year
retention. HIPAA. Tax code retention rules per jurisdiction.
GDPR Art. 6/9/22 lawful-basis discipline. BDSG §26 employment-data
specifics. EU AI Act Art. 5 prohibitions on real-time biometric
emotion recognition + workplace biometric categorisation (in force
since 2 Feb 2025). Every SaaS deals with these, every ERP makes
the customer build them.

kontor ships ADR-050 (retention policies with the legal-hold
interaction worked out — held data survives retention sweeps),
ADR-051 (privilege classification — attorney-client work product
gets the right treatment under DSAR), ADR-052 (the DSAR
"everything we know about this data subject" walk as one
bitemporal query across kernel + companions), and ADR-094 (the
consent substrate). The retention sweep is a transactor that calls
the same gate every business write goes through — no separate
"compliance mode."

ADR-094 adds three things that make consent + monitoring a
substrate concern, not a bolt-on:

1. **A canonical category vocabulary** on `:audit-doc/category` —
   16 values total, 8 of them HR-specific (`:hr-track-record`,
   `:hr-activity-monitoring`, `:hr-activity-content`,
   `:hr-communications`, `:hr-background-check`,
   `:hr-compensation-negotiation`, `:hr-grievance`,
   `:hr-monitoring-consent`). Open-set; consumers extend without
   schema change.
2. **A `:consent/*` mini-schema** (in kontor-hr) recording per-
   subject, per-scope, per-legal-basis consent as a bitemporal
   fact. `:consent/legal-basis` is a vocabulary keyed to GDPR Art.
   6/9/22/35/88 + BDSG §26 + BetrVG §87 + a special
   `:ai-act-incompatible` substrate refusal marker. Withdrawal
   does NOT retroactively invalidate processing during the window
   the consent was active — that's the regulator-aligned semantic.
   `kontor.hr.consent/active-at?` answers "was consent operationally
   in force at instant T?" for any (subject, scope, T) triple.
3. **Two new approval-policy rules** — `:requires-dpia-supporting-
   doc` and `:requires-works-agreement-ref` — that gate sensitive
   transitions (e.g., placing a person under monitoring) on a
   `:audit-doc` referencing a DPIA or a Betriebsvereinbarung.

Per-jurisdiction retention seeds ship in the l10n companions; the
DE seeds (`modules/l10n-de/src/kontor/l10n_de/retention.clj`)
cover HGB §257, BDSG §26, GefStoffV §10a, DSGVO Art. 5, BetrVG
§82-83, AO §147, SGB IV §28f. Other jurisdictions add their seeds
incrementally — no schema migration required.

The project also publicly refuses to scaffold categories that
facilitate AI-Act-banned use (real-time biometric emotion
recognition, covert workforce monitoring, automated termination
recommendations). Consumers can still extend the open-set vocabulary
for their own categories, but they don't get the kernel's blessing
or first-party companion integrations for those.

### 6. Segregation of duties — approval policies tied to status machines

The clerk who creates the invoice can't be the approver who posts
it. The counsel who creates an audit-doc can't be the same counsel
who waives its attorney-client privilege. The CFO who set the
asset's useful life can't be the same person who disposes it for
zero proceeds.

ADR-038 covers this: every status transition that matters has an
approval-policy row with `:no-self-approval` / `:requires-
supporting-doc` / `:requires-non-empty-reason-note` constraints.
The policy is data; consumer apps add their own policies the same
way kontor ships its own. The gate enforces all of them on every
status change.

### 7. Atomic cross-module events — the ADR-068 win

This is the headline of Stage P: when sales closes a deal, the
system atomically does ALL of this together or NONE of it:

- Creates the customer invoice (kernel `:transaction` + `:posting`s).
- Grants the buyer read access via authz (so they can see it in
  the customer portal).
- Links the signed contract PDF as the invoice's `:origin-document`.
- Schedules an audit-doc retention reminder.
- Marks the opportunity `:closed-won` on the sales side.

In a traditional ERP, this is **five separate transactions across
five subsystems**, each of which can succeed or fail independently.
You build a saga, you handle compensating rollbacks, you sleep
poorly when a partial state lands. The auditor finds an invoice
without a contract or a contract without an invoice and you spend
two weeks tracing why.

In kontor: one `kontor.process` call. The five fragments build via
`*-tx-data` builders, compose into one tx-data, route through one
gate, commit as one datahike transaction. All-or-nothing is
structural.

This is **impossible in Odoo / Tryton / NetSuite / SAP** without
heavy customization — none of them have a kernel-level atomic-tx
primitive that spans modules cleanly. (We surveyed all four; see
research notes 44 + 47.)

### 8. Status machines — every business entity has a lifecycle

Invoices: `:draft → :sent → :paid` (or `:cancelled`, or `:partially-
paid`). Leases: `:draft → :active → :terminated` / `:expired` /
`:purchased`. Assets: `:planned → :in-service → :disposed` /
`:fully-depreciated` / `:impaired`. Holds: `:placed → :released`.
DSAR requests: `:received → :verifying-identity → :in-progress →
:fulfilled` (or `:denied`).

ADR-034 codifies this. Every transition is either legal (registered
in a `:status-transition` row, queryable, organisation-scoped) or
illegal (rejected by the gate). The history is a `:status-history`
row, audit-doc-linked where ADR-038 requires it. Every consumer
adds its own status machines via the same primitive.

Traditional ERPs: status fields with application-code transitions.
"Why can the Q3 invoice be `:cancelled`? The clerk did it." Who
authorized? "I'll check the audit log." Was it allowed? "Let me
check the source code."

## The trans-national substrate

A SaaS that grows out of one country eventually has to consolidate
across many. kontor's substrate-tier seams (ADR-071 .. ADR-074)
handle the multi-jurisdictional case as first-class kernel
concerns:

- **Tax** — `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder`
  (ADR-071) supersedes the original single `TaxProvider`. Rate
  determination is pure data: a provider returns a `TaxFacts`
  record (the buyer + seller jurisdictions, the product/service
  category, the applicable rates and accounts); the
  `TaxPostingBuilder` turns it into postings. The kernel ships
  `StaticTableProvider` + scaffolds for Avalara, TaxJar, SST.
  Per-country chain-of-providers is one `chain`-call.
- **FX** — `FxRateProvider` (ADR-072) returns the rate for a
  (from, to, instant, rate-type) tuple. Rate-types are IAS 21 /
  ASC 830 concepts (`:spot`, `:closing`, `:average`,
  `:historical`). Built-ins: `StaticTable`, ECB CSV ingest,
  `Chained` (try providers in order). `kontor.fx` exposes
  `convert`, `translate-money-seq`, `to-functional-currency` over
  `Money` values. A consumer's reporting pipeline asks for "this
  trial balance translated to USD at closing-rate as-of 2026-09-30"
  in one call.
- **Consolidation** — `kontor.consolidation` (ADR-073) composes the
  two above. `translate-trial-balance-tx-data` translates each
  subsidiary's trial balance to the parent's functional currency at
  the configured rate-type per balance-sheet line item;
  `eliminate-intercompany-pair-tx-data` removes the matching
  intercompany asset/liability pair on the consolidating ledger;
  `consolidate!` runs the full pipeline as one atomic
  `kontor.process` commit.
- **Cross-DB saga** — `CrossTxRouter` (ADR-074) gives the
  "atomic-feel commit that spans two DBs" pattern when consolidation
  is not enough (e.g., parent and subsidiary on different
  database instances). Content-hash idempotency makes the routed
  intent safe to re-emit; `drain!` runs the pending intents one
  at a time. This is the saga-without-the-saga-bookkeeping.

Showcase 04 walks through the DE-parent + US-subsidiary scenario;
the trans-national integration test
`test/kontor/stage_r_cross_stage_test.clj` follows one trans-
national employee (Jane Doe — three concurrent employments in DE,
US, BR) through a single payroll month, demonstrating that the
substrate composes one global `:person` identity with three
country payroll providers without losing the audit chain or
breaking jurisdiction isolation.

## Country payroll — 11 jurisdictions, one provider trio

Most "international payroll" products are a thin layer over a
single engine. kontor's `kontor.hr.payroll/run-payroll!`
orchestrator (ADR-075) takes a `:pay-period` and a provider trio
(`PayrollComputeProvider` + `PayrollPostingBuilder` +
`PayrollEmitProvider`) and runs the full per-employee compute →
post → regulator-emit pipeline as one gated `kontor.process`
commit per period.

Eleven country adapters ship today (ADR-076 .. ADR-087):

| Country | Engine compute | Posting target | Regulator emit |
|---|---|---|---|
| DE | DATEV LODAS (ISO-8859-1 4-section) + EXTF Buchungsbeleg | SKR04 + HGB §249 PTO accrual | LODAS Importdatei |
| US | ADP GLI 10-column CSV (with the balancing-row trap) | per-state `:analytic-account/state` (50 + DC + 5 territories) + ASC 710 PTO + 401(k) match | W-2 reconciliation |
| CA + QC | Ceridian Dayforce + ADP Canada + Wagepoint | CA-baseline + Quebec RL-1 + TPZ-1015 | T4 + T619 envelope, RL-1 + RLZ-1.S |
| FR | Silae + Sage | PCG accounts | DSN NEODES |
| AU | Xero + MYOB | ATO-aligned + 8-jurisdiction state allocation | STP Phase 2 + SuperStream |
| BR | RH Sistemas + Senior HCM + Pluxee | four-bucket statutory + 3 CPC-33 accruals (férias + 13º + multa rescisória) | eSocial S-1000..S-2399 |
| MX | CONTPAQi + Aspel NOI | SAT Código Agrupador + aguinaldo + prima vacacional accruals | CFDI Nómina v1.2 |
| IN | Keka + GreytHR + ZenHR | per-state PT + thin gratuity accrual | Form 24Q + EPFO ECR + ESIC |
| JP | freee + Money Forward + Yayoi + PCA-Kyuyo | 4-bucket statutory SI + 賞与 separate from 給料手当 | Gensen Choshu Hyo (annual) |
| CN | Yonyou + Kingdee + Beisen | 应付职工薪酬 + 五险一金 + 34-province `:cn-province` analytic | engine-authoritative IIT (no recomputation) |
| AT | BMD + RZL | RLG-1 + Urlaubsrückstellung + Sonderzahlungsrückstellung | ELDA mBGM XML + L16 annual |

The provider trio is the same in every country; the per-country
modules differ in what the compute provider parses, what chart of
accounts the posting builder targets, and what regulator envelope
the emit provider serializes. Adding a new country is "write three
protocol impls"; the kernel orchestrator + the audit-chain + the
status machines + the bitemporal substrate stay invariant.

## A concrete cross-module composition example

A B2B subscription company:

```clojure
;; The business event: customer signs a 12-month contract.
(process/run-process conn
  {:steps
   [;; (1) Create the customer invoice for the first month.
    (fn [sdb _]
      (invoice/create-tx-data
       sdb {:tempid "inv-1"
            :buyer customer-eid
            :seller our-entity-eid
            :total-gross 199M
            :commodity :USD
            :due-date next-30-days}))

    ;; (2) Grant the customer's user read access to the invoice.
    (fn [sdb _]
      (authz/grant-tx-data sdb authz-client
                           customer-user :view "inv-1"))

    ;; (3) Attach the signed Order Form PDF as the supporting doc.
    (fn [sdb _]
      (audit-doc/create-doc-tx-data
       sdb {:tempid "order-form"
            :code "OF-2026-12345"
            :type :order-form
            :storage-uri "s3://docs/of-2026-12345.pdf"
            :uploaded-by-uid sales-rep-uid}))

    ;; (4) Schedule the remaining 11 monthly invoices.
    (fn [sdb _]
      (schedule/define-tx-data
       sdb {:code "SUB-12345" :kind :subscription
            :frequency :monthly :n-periods 11
            :start-date (+1-month-from now)
            :total-amount (* 199M 11)}))]
   :vt-from contract-signed-at})
```

This commits as one atomic transaction routed through the kernel
gate. If ANY of the four fragments fails any validator (sealing,
period-lock, sum-to-zero, the approval policy, the datalog
invariants), the whole event aborts — no partial state. The auditor
sees one transaction with `:tx/valid-from = contract-signed-at`
spanning four modules.

In every other accounting system: four API calls, four chances to
fail, four chances to leave partial state, four chances for the
buyer-access to land before the invoice does or vice versa. Then
the orchestration layer needs an audit log of its own.

## Who this is for

**Vertical SaaS builders.** You're building accounting software for
a specific industry — restaurant POS, dental practices, freight
forwarders, SaaS subscriptions, construction GCs. Your customers
care about industry-specific workflows. You don't want to build the
core double-entry engine + audit + bitemporal + multi-entity
yourself, but you don't want to bolt onto Odoo because every
customer needs a deviation and the LGPL license complicates your
distribution story.

**Accounting consultancies.** You build three different products
for three different verticals. Sharing infrastructure across them
matters; sharing data across them doesn't.

**ERP modernization projects.** You have an existing system that's
outgrown QuickBooks but isn't ready to commit to SAP's six-figure
implementation. kontor is the substrate; you build the product
layer to match what the customer actually does.

**Embedded accounting.** Your product needs accounting
functionality (invoicing, ledger, tax routing) but isn't an
accounting product — a marketplace, a freelancer platform, a
B2B integrations company. kontor as a library, not an app.

## Who this is NOT for

**Someone shopping for "an accounting app."** kontor is a
substrate. You'll spend developer-months building the UI and
business logic. If you want to open a browser and see an invoice
form, buy QuickBooks Online or hire a Tryton implementer.

**Someone allergic to Clojure.** kontor is Clojure-on-the-JVM. The
runtime is reachable from any JVM language but the canonical API
is Clojure. A Java team can use kontor (via interop); a team that
wants every line of code to be Python or Ruby cannot.

**Someone who needs a tax engine for every country.** kontor's tax
story is "ship the `TaxRateProvider` protocol, integrate Avalara
or TaxJar yourself or via the consumer's adapter" (ADR-071,
superseding ADR-005's single-provider shape). It does not bundle
tax rate tables. The l10n modules ship country-specific chart-of-
accounts and report shapes; they don't ship tax rates.

## Where kontor sits in the competitive landscape

A measured note for evaluators who recognize the pattern of "new
AI-native accounting product" and want to know quickly whether
kontor is one of those. It isn't.

**kontor is not the next Rillet / Campfire / Numeric / Causal /
Puzzle.** These are closed, AI-native ERP / FP&A products targeted
at hyper-growth US SaaS — the "QBO outgrown, NetSuite not ready"
gap. They raised on the order of $100M each in 2024-25 (per
research note 92 §4.6) for proprietary closed-cloud architectures
with US-only coverage. The closest comparison to what they sell is
a fully-managed product; the closest comparison to what kontor
ships is the substrate any of them could have been built on if
they wanted to be open and multi-jurisdictional. We expect them
to dominate the hyper-growth US SaaS segment; we expect the open-
substrate audience to be the European / Indian / Brazilian
multi-entity, multi-ledger, multi-jurisdiction long tail those
products structurally cannot serve.

**kontor is not Palantir Foundry, Snowflake Cortex Agents,
Salesforce Agentforce, Databricks Genie, or Workday Sana.** Those
are data platforms and agentic enterprise OSes — generic ontology
+ tool-orchestration layers that an enterprise builds on across
many domains (finance, supply chain, manufacturing, claims). They
are powerful and they are closed. kontor is single-purpose: an
accounting substrate, embedded in a Clojure app, owned by the
customer. The closest parallel to Foundry's Ontology is kontor's
`:concept-iri` seam (ADR-090) + `kontor.explain` walks (ADR-091),
but the substrate underneath is double-entry, bitemporal, EPL-1.0,
and runs on one JVM with one dependency (datahike). Foundry-tier
deployments cost USG-tier money and lock the customer's data
inside Foundry's runtime; kontor is the auditable bitemporal core
that runs anywhere the JVM does.

**kontor is not an MCP server.** Research note 92 found that
Anthropic's Model Context Protocol has become the de facto agent-
tool standard (OpenAI, Google, Microsoft all adopted it in 2025).
The leverage point is the tool catalog, not another JSON-RPC
server. kontor ships `kontor.agent-tools` — a server-agnostic
catalog of read + write tools that respects every kernel gate
(sealing, period-lock, audit-doc category, status-machine,
approval-policy, legal-hold, invariants) when an LLM agent
invokes them. The catalog composes with the existing MCP server
in [dvergr](https://github.com/replikativ/dvergr) today. A
standalone `kontor-mcp` is deferred until a consumer asks for one
without buying into dvergr's transport. (Note 94 §3.2.)

The competitive gap kontor fills: there is **no open,
multi-jurisdictional, bitemporal accounting substrate** in the
market. The proprietary AI-native ERPs are closed and US-only;
Foundry is closed and generic; Tryton + Odoo are open but tainted
licensing (GPLv3 / LGPLv3) and not bitemporal; SAP / NetSuite are
neither open nor bitemporal. kontor occupies the substrate slot
one layer below the closed AI-native ERP — the layer a builder
who wants to ship the *next* Rillet, but open and multi-juris,
would start from.

The anti-framing comes from research note 92 §4.6, §10.3, and the
strategy synthesis in note 94 §6.

## What you'd build on top

A typical kontor-based product needs:

- **A UI.** Web app, mobile app, terminal — your choice. kontor
  has no opinion. ADR-010.
- **A workflow engine.** kontor ships `kontor.process` for atomic
  composition; the macro-workflow layer (orders → fulfillment →
  invoicing → revenue recognition over weeks) is yours. Many
  vertical SaaSes use a state-machine library or build their own.
- **Tax provider integration.** Sign up for Avalara or TaxJar;
  write an adapter implementing the `TaxProvider` protocol. ~200
  lines.
- **The chart of accounts.** kontor ships *some* l10n modules
  (German, US starter, Indian) with conservative defaults. Your
  customers will want overrides — kontor's account-tag and
  multi-ledger story makes this not painful.
- **Your business logic.** This is where you spend your time.
  Pricing rules, dunning policies, revenue-recognition treatments
  for your specific products, integrations with your CRM and CDP
  and payments processor.

## Where to go next

- **On-ramp**: [doc/start-here.md](start-here.md) — single-page
  walkthrough of showcase 06 (the 3-year DE GmbH with the
  backdated correction). Read this first if you're still deciding
  whether kontor is for you.
- **Programming model**: [doc/programming.md](programming.md) — the
  Clojure-developer walkthrough.
- **Architecture**: [doc/architecture.md](architecture.md) — the
  layer cake and namespace map.
- **Design decisions**: [doc/decisions.md](decisions.md) — 94 ADRs
  spanning everything from the bitemporal model to ADR-068's
  cross-module composability story to ADR-094's consent +
  retention + AI Act refusal posture.
- **Research notes**: [doc/research/](research/) — point-in-time
  research that informed each decision, including prior-art
  surveys of Odoo, Tryton, SAP, NetSuite, Oracle, KillBill, OFBiz,
  SpiceDB, EACL, XTDB v2, the company-as-software market
  (note 92), and the employee-tracking privacy landscape
  (note 93).
- **Showcases**: [doc/showcases/](showcases/) — six fully-worked
  scenarios. Showcase 05 (Apple 10-K/A) and showcase 06 (DE GmbH
  multi-year) demonstrate the bitemporal headline; showcases 01-04
  cover DE Mahnverfahren, US multi-state collections, IN B2B with
  IRN + GSTR + TDS, and multi-entity intercompany.

License: EPL-1.0. Source: [github.com/replikativ/kontor](https://github.com/replikativ/kontor).

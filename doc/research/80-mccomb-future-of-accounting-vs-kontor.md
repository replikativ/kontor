---
date: 2026-05-18
agent: research
title: 80 — McComb's "Future of Accounting" vs. kontor — alignment, divergence, what to do about it
status: research-note
audience: maintainer reading McComb's book in real time; want a map, not a verdict
---

# 80 — McComb's "Future of Accounting" vs. kontor — alignment, divergence, what to do about it

Dave McComb + Cheryl Dunn published *The Future of Accounting* (Technics
Publications, 2025) as the data-centric movement's accounting manifesto.
The book sits in a particular intellectual lineage — McComb's
application-centric critique from *Software Wasteland* (2018) and *The
Data-Centric Revolution* (2019), Bill McCarthy's REA accounting model
(1982), Semantic Arts' gist upper ontology, and the broader W3C semantic
web stack (RDF, OWL, SPARQL, URIs). Dunn's three decades of REA work is
what gives the book its concrete proposal: replace debits/credits with
commitments + rights/obligations + events as the primitive vocabulary
of accounting.

The maintainer is reading the book in real time and asked for an
honest map of where kontor fits McComb's framework, where it
diverges, and which of the divergences are defensible vs. concerning.
What follows is that map. The book itself is paywalled and excerpts
are thin online, so the McComb position is reconstructed from his
publicly-available essays, talks, and the gist / Semantic Arts
documentation — flagged below as "primary-source-light" where it
matters.

## §1 — TL;DR (the maintainer-actionable verdict)

- **Kontor agrees with McComb's structural critique** of
  application-centric ERPs (anti-Odoo / anti-NetSuite is a shared
  enemy), with event-driven + immutable storage as the foundation, and
  with the "data outlives applications" thesis (datahike + bitemporal
  reads encode it). ADR-001, ADR-002, ADR-007, ADR-008, ADR-010.
- **Kontor diverges on three load-bearing points**: it keeps the
  chart-of-accounts as foundational (McComb / Dunn want it
  derivative); it uses namespaced keywords + datalog rather than URIs
  + RDF/SPARQL; and it stops at posting-level events (debits/credits)
  rather than walking up to commitment/event-level events
  (REA-shaped).
- **Two of the three divergences are defensible** given kontor's
  posture as a *substrate* for accounting workloads, not a
  greenfield accounting research project. The third — debits/credits
  vs. events — is more interesting and partially addressable without
  rewriting the kernel.
- **The cheapest McComb-aligned moves kontor has already made**: the
  `:account-tag/concept-iri` seam (XBRL IRIs, addendum to ADR-019)
  *is* the first URI-keyed identifier in the substrate. Extending
  that pattern to other dimensions where consumers want external
  identifiers (gist URIs, FIBO classes, ISO codes) is cheap and
  doesn't bend the kernel.
- **The hardest McComb-aligned move kontor has *not* made**: there
  is no "commitment" concept in the kernel. Consumers can model it
  in their own namespaces (beleg's `:invoice/*`, simmis' future
  contract types), but kontor itself reaches the ledger one step
  too late — the moment of `:posting/posted-at`, not the moment of
  promise-to-pay. This is the divergence most worth taking
  seriously.
- **Bitemporal is a kontor superpower McComb doesn't address.** His
  framework is event-sourcing-shaped (tx-time axis) but doesn't
  carry an explicit valid-time axis. Kontor's `:tx/valid-from` +
  XTDB-v2-style polygon supersession (ADR-048, research note 77) is
  *strictly more expressive* than what McComb proposes and is the
  thing to defend.
- **Net read**: kontor is McComb-compatible at the substrate level
  but not McComb-conformant at the modeling level. The right
  long-game move is *substrate-tier seams* (URI-keyed identifiers,
  commitment-shaped extension points in companions) rather than
  rewriting the kernel as an REA / gist implementation.

## §2 — McComb's positions (faithful summary, primary-source-light)

### §2.1 — The application-centric critique (*Software Wasteland*, 2018)

McComb's foundational critique is that enterprise IT spends ~$3.8T/yr
worldwide, most of it on application development that produces
redundant, siloed software stacks (Semantic Arts, *Becoming
Data-Centric with Semantics*). His characteristic estimate: "take the
number of employees you have in your company and divide it by 10,
that's probably about how many applications you're currently
managing" (Knowledge Graph Insights interview). Each application
brings its own data model, its own database, and a layer of
integration code that exists only to reconcile the differences. The
total cost is paid in integration debt, redundant truth, and
brittleness.

The remedy is to invert priority: data + the data model is permanent,
applications are ephemeral visitors. From the Data-Centric Manifesto
(*datacentricmanifesto.org*):

> 1. Data is a key asset of any person, organization, and society.
> 2. Data is self-describing and does not rely on an application
>    for interpretation and meaning.
> 3. Data is expressed in open, non-proprietary formats.
> 4. Access to and security of the data is a responsibility of
>    the enterprise data layer or the personal data vault, and
>    not managed by applications.
> 5. Applications are allowed to visit the data, perform their
>    magic and express the results of their process back into the
>    data layer.

### §2.2 — The data-centric architecture (*The Data-Centric Revolution*, 2019)

The architecture McComb proposes has four load-bearing pillars
(Semantic Arts, *Data-Centric Architecture Explained*):

1. **URIs as universal identity.** Every concept gets a globally
   unique IRI. Identity is the seam by which all systems share
   meaning; no two applications mint different IDs for the same
   real-world thing.
2. **RDF triples as the primitive.** Subject-predicate-object,
   queryable via SPARQL, federable across endpoints. Graph topology
   instead of relational schemas.
3. **A small, shared upper ontology** (Semantic Arts ships *gist*
   — ~100 classes, ~100 properties — CC-BY-SA, hosted on GitHub at
   `semanticarts/gist`).
4. **Per-enterprise / per-industry extension ontologies** that
   inherit from the upper ontology. Companies are different;
   industries are different; the substrate must let them differ
   without breaking interoperability.

The "schema later" stance (McComb, BR Community interview): data
need not have a schema *before* it is written. The shape can grow.
Schemas are not constraints but post-hoc descriptions of what's in
the graph.

### §2.3 — *The Future of Accounting* (McComb + Dunn, 2025) — what the book proposes

The book's overall thesis (Semantic Arts, *tfoa* landing page; PR
Newswire announcement; Knowledge Graph Insights interview):

- Traditional accounting *starts* with the financial statement and
  works backward into the business. Data-centric accounting *starts*
  with the elemental business events and works forward to the
  statement (Semantic Arts, *tfoa*).
- The book proposes a "single, simple, extensible data model" that
  captures *all* organizational events in real time, not just the
  ones that pass through bookkeeping (the implication: bookkeeping
  is one *projection* of organizational reality, not the truth
  itself).
- The central modeling primitive is the **commitment** (gist:Commitment
  in the upper ontology). Every transaction shown on a financial
  statement is the consequence of a commitment. A commitment has
  two sides: a **right** (e.g. "I am owed a whiteboard") and an
  **obligation** (e.g. "I owe payment"). Most of accounting is
  tracking commitments and their fulfillment (McComb in Knowledge
  Graph Insights).
- **Debits and credits as an organizing primitive disappear.**
  McComb's claim (Knowledge Graph Insights): "literal debits and
  credits have already been gone for about 50 years" in actual
  software systems — what survives are negative numbers in a column
  reported with a credit label. The conceptual fiction is what the
  book aims to retire.
- **Accounting policy as data, not procedure.** McComb (Knowledge
  Graph Insights): "a very simple table for your industry, for your
  company ... when an event occurs, it just immediately bumps up
  against that table, it knows exactly how to classify, and how to
  value it." The book reframes GAAP / IFRS recognition rules as
  declarative classification + valuation lookups over events.
- The lineage is REA — Bill McCarthy's 1982 model (Resources,
  Events, Agents, with a duality relation across event pairs).
  Dunn's three decades of REA scholarship is the academic backing;
  McComb's data-centric architecture is the implementation lens.
  Per the Wikipedia summary of REA, "double-entry bookkeeping
  disappears in an REA system, and many general ledger accounts
  also disappear, at least as persistent objects; e.g., accounts
  receivable or accounts payable. The computer can generate these
  accounts in real time using source document records."
- iXBRL / regulatory taxonomies are an output projection in this
  model: filings are generated by classifying the underlying events
  through filing-specific lenses, not by mapping a fixed chart of
  accounts to disclosure lines.

### §2.4 — Chart-of-accounts critique (extrapolated)

McComb's published material does not include a single decisive
chart-of-accounts critique passage. The position has to be
reconstructed:

- If the primitive is events + commitments, the chart of accounts
  is a *report-axis*, not a *storage-axis*. It is one of many
  possible classifications of the underlying events.
- The chart is *derivative*: it's how an accountant chooses to
  bucket events for a particular set of reports. A different filer
  (statutory, IFRS, internal mgmt) needs a different chart, and the
  events are the same.
- The chart is *historical* in REA's framing: McCarthy explicitly
  said many GL accounts (AR, AP) "disappear" — they are computed
  on demand from the source events.

Cohort thinkers reach the same conclusion: KillBill's design treats
the GL as a projection over an immutable invoice + payment event
log; Crux / XTDB v2 papers cast accounting as bitemporal facts with
GL as a queried view; Bitcoin / Ethereum's "balance is a derived
view over the UTXO / state-transition log" is the same shape one
layer down the stack.

### §2.5 — Adjacent thinkers and what they bring

- **Bill McCarthy (REA, 1982)** — the original "accounting without
  debits/credits" paper. Available open-access at
  `home.business.utah.edu/actme/7410/McCarthy-82.pdf`. Dunn's
  scholarship is the bridge from 1982 academic accounting-IS theory
  to 2025 data-centric architecture.
- **Cheryl Dunn (REA-AIS textbook, 2005)** — extends McCarthy with
  commitments, policy rules, value chains. Now Semantic Arts'
  ontologist (Semantic Arts team page).
- **FIBO (Financial Industry Business Ontology)** — EDM Council /
  OMG initiative. Production-grade ontology for financial
  instruments + market participants + transactions. Aligns with
  REA on transaction semantics, with XBRL on reporting (FIBO
  GitHub `edmcouncil/fibo`; Ontotext blog). gist's "Quick Start"
  for finance teams (`semanticarts.com/fibo-quick-start`) is the
  Semantic Arts bridge between gist and FIBO.
- **Rich Hickey + Datomic** — values-as-facts, time as a first
  class concept, EAV with a tx axis. McComb sits adjacent;
  Datomic + datahike are the FP-flavored kin of his RDF +
  knowledge-graph stack. The conceptual overlap is large; the
  surface syntax differs.
- **XTDB v2 + Crux** — bitemporal + immutable + datalog. Closest
  living implementation to "McComb's stack done in JVM with FP
  ergonomics." Kontor inherits the lineage via datahike +
  research notes 55–68/77.

### §2.6 — What McComb does *not* explicitly address

Three blind spots that matter for kontor's comparison:

1. **Valid-time / bitemporality.** McComb's stance is event-driven
   and immutable but not bitemporal. The graph carries facts as of
   when they were *recorded*; he does not develop a story about
   facts whose validity is dated to a different moment than their
   recording (correction of an old fact: same recording-time
   semantics but a past valid-time). XTDB v2 develops this in a
   way McComb does not. Kontor's `:tx/valid-from` + polygon
   resolver (ADR-048, note 77) is McComb-compatible but adds an
   axis he does not specify.
2. **Cross-DB atomicity.** McComb's model assumes one logical
   data layer; kontor (ADR-074, research note 71) acknowledges
   that real deployments span multiple datahike conns + external
   side effects and need saga discipline. McComb's writing does
   not address this; the data-centric ideal assumes the data
   layer is The Data Layer.
3. **Regulatory jurisdiction.** McComb's tone is universalist —
   one model fits the enterprise. Kontor's design (ADR-019
   external-codes, ADR-021 parallel ledgers, ADR-031 multi-entity)
   is built around the empirical reality that an enterprise
   filing in DE, US, BR, and IN has to satisfy four *different*
   regulators with *different* charts and *different* event
   classifications simultaneously. McComb would say the upper
   ontology absorbs this; the practitioner question is how, with
   what tooling, on whose schedule.

## §3 — Kontor's current programming model (faithful summary)

### §3.1 — Substrate posture

- **Single dependency: datahike** (ADR-001). EAV + datalog +
  bitemporal commits + immutable hash-linked commit graph + content-
  addressed storage via konserve. The accounting state *is* the
  datalog query target.
- **One DB, two-plus schema namespaces** (ADR-002). Beleg's
  `:invoice/*`, simmis' future namespaces, and kontor's
  `:account/* :posting/* :journal/* :transaction/*` cohabit in one
  datahike connection. Atomicity across the seam means "post the
  invoice + write the AcctgTrans in one tx" is structurally
  enforced, not application-discipline.
- **Bitemporal lean** (ADR-008 / ADR-048). Transaction-time =
  `:db/txInstant`. Valid-time = `:tx/valid-from` on the writing
  transaction (per ADR-048, normalized off per-posting). Reads
  take `:as-of-tx` × `:as-of-valid`. Polygon supersession via the
  resolver in `kontor.bitemporal` (note 77).
- **Sealing semantics** (ADR-007). `:posting/posted-at` is the
  seal marker; silent retract is forbidden; `:db/purge` is
  permitted *and is itself a recorded commit*, so the audit chain
  documents the deletion. The audit story is "traceability of
  changes", not "data is immutable" — the design accommodates
  GDPR Art. 17 alongside auditor traceability.

### §3.2 — The functional surface

- **`*-tx-data` builders** (ADR-068). Every business-write
  transactor splits into a pure `(db, opts) -> tx-data` builder
  + a thin `!`-wrapper that routes through
  `kontor.validation/transact-with-validation`. 87 builders
  across 207 `!`-wrappers as of note 69. The whole kernel is a
  functional core + imperative shell.
- **`kontor.process` step-lists** (ADR-067 +
  `src/kontor/process.clj`). Multi-step processes are sequences
  of pure step fns; `run-process` threads them against one
  start-snapshot, accumulates one tx-data vector, applies one
  outer `with-vt`, and commits through the validation gate as
  one atomic transaction. Monadic flatten via `{:steps ...}`
  returns. The "10 sub-transactors each doing their own
  d/transact" pattern is collapsed to one validated commit.
- **Provider protocols.** `TaxRateProvider` + `TaxPostingBuilder`
  (ADR-071), `FxRateProvider` (ADR-072), `EInvoiceProvider`
  (ADR-017), `CostingProvider` (ADR-029), `DepreciationProvider`
  (ADR-055), `LeaseProvider` (ADR-063), `CrossTxRouter` (ADR-074).
  Plug-points for per-jurisdiction or per-customer logic without
  modifying the kernel.

### §3.3 — The chart-of-accounts shape

- **`:account/code`** — string, unique per ledger. The user's
  primary chart code.
- **`:account/path`** — slash-separated hierarchy (Beancount-style
  `Assets:Bank:Main`).
- **`:account/type`** — `:asset :liability :equity :income
  :expense` enum.
- **`:account/external-codes`** (ADR-019) — many-cardinality ref
  to small `:account-code/*` entities, each carrying a regulator
  + that regulator's code. One account can simultaneously hold a
  SKR04 code (DE), an IFRS group code, a DATEV code, and a BR
  Plano de Contas Referencial code.
- **`:account-tag/*`** (`schema.clj:420-474`) — tags as
  first-class entities, with `:account-tag/concept-iri` carrying
  XBRL / filing-taxonomy IRIs (addendum to ADR-019, note 78).
- **Parallel ledgers** (ADR-021) — `:ledger` entity +
  `:posting/ledger` ref. Postings sum-to-zero per (entity, ledger,
  commodity). One business event can post to N ledgers (book,
  tax, IFRS, management) simultaneously, each book balancing
  independently.
- **Analytic dimensions** (ADR-022) — per-account required
  analytic plans (cost-center, project, partner, …) with a
  sum-to-100 invariant.
- **Multi-entity** (ADR-031) — `:entity` entity scoping postings
  for transnational books.

### §3.4 — Vertical adaptation

- **Kernel** — country-agnostic; ships substrate primitives
  only.
- **Companions** — invoice / sales / partner / procurement /
  collections; kernel-tier but optional.
- **`kontor-l10n-<cc>`** — per-country chart, tax stack,
  filing taxonomies, e-invoice format. Separately licensed
  (ADR-006: GPLv3 for DE / Tryton-derived, EPL-1.0 for CA /
  CRA-derived, etc.).
- **Partner adapters** — Avalara, TaxJar, Mustang, PAC, IRP,
  SEFAZ, Peppol AP. Never bundle credentials.

## §4 — Where kontor aligns with McComb

Concrete alignments, with citations to kontor sources.

### §4.1 — Anti-application-stack

ADR-010 (no UI, no ERP, no Peppol AP, no Avalara reimplementation,
no clean-room Odoo) is exactly the "data outlives applications"
stance. The kernel is "the substrate you would have built underneath
Odoo / NetSuite / Xero" (`README.md:14-17`). Beleg, simmis, and
third parties are the *visiting applications* in McComb's manifesto
sense; kontor is the data layer they share.

### §4.2 — Event-driven storage

Every posting is a datom assertion; the tx-log is the event stream;
the bitemporal axis lets queries time-travel both axes (`README.md:23-28`).
`d/datoms` + the commit graph give exactly the "every fact has a
recording moment" property McComb names as data-centric.

### §4.3 — Immutable storage with audited deletion

ADR-007 — "Purge is a recorded commit, not a violation" — encodes
exactly McComb's "data is permanent" stance with the right-to-erasure
escape hatch the universalist version would otherwise miss. The
audit story matches the manifesto: data outlives applications, but
when data legitimately must go, its absence is itself a recorded
fact (the purge commit). McComb's writing does not engage with
GDPR Art. 17 directly; kontor's variant is McComb-compatible with
the regulatory friction handled.

### §4.4 — FP-inspired

The `*-tx-data` builder convention (ADR-068) is the FP core +
imperative shell pattern McComb's data-centric stance lives well
with. There's no in-place mutation; every business write composes
through pure functions returning tx-data; commits are explicit and
gated.

### §4.5 — Graph database

Datahike is an EAV graph store with datalog as the query language.
It is not RDF/SPARQL — but the *shape* (entities + attributes +
relationships, traversable, schema-flexible-on-the-data-axis) is
the graph topology McComb advocates. Note 60 covers the
datahike/XTDB lineage in detail; the design family is "knowledge
graph DBs with FP ergonomics."

### §4.6 — Schema flexibility through namespacing

ADR-002's cohabitation invariant is the McComb pattern reduced to
Clojure: `:invoice/*` and `:account/*` coexist *because the
namespace is the boundary*, not the database. Adding a new
companion module's schema is `(d/transact conn module/schema)` —
no migration, no breaking change to existing data, no ALTER TABLE.
McComb's "schema later" / extensible-RDF stance hits the same
property by a different syntactic route.

### §4.7 — URI-keyed identifiers (recent, narrow)

`:account-tag/concept-iri` (note 78, ADR-019 addendum,
`schema.clj:466-474`) is kontor's first URI-keyed identifier.
Consumers can carry XBRL concept IRIs like
`http://xbrl.ifrs.org/taxonomy/2024-03-27/ifrs-full#Revenue`
verbatim on tags. The substrate stores and indexes; verification
is companion-tier. This is the smallest possible McComb-aligned
seam ("identity lives in URIs") shipped at substrate level.

### §4.8 — Multi-jurisdiction reality without one-size-fits-all

ADR-019 + ADR-021 + ADR-031 honor the empirical fact that a single
account answers to multiple regulators with different codes, that
parallel books (HB/StB/IFRS) coexist, and that a transnational
deployment has multiple legal entities in one datahike instance.
McComb's universalist stance would say "one upper ontology absorbs
all this." Kontor's stance is "one substrate carries the pluralism
honestly." The two are reconcilable but the practitioner posture
differs (see §5).

## §5 — Where kontor diverges from McComb

Six divergences worth being honest about. For each: is McComb right
that this matters? What does kontor pay for diverging? What would
adopting his stance cost?

### §5.1 — Ontology (shared) vs. schema (project-internal)

**McComb's stance.** Identifiers should be globally unique URIs
(gist:Person, fibo-be:Organization, ifrs-full:Revenue). The upper
ontology (gist) provides ~100 cross-enterprise concepts that
anyone can extend. Sharing the upper ontology means two firms can
align their data without ETL — same URIs, same meaning.

**Kontor's stance.** Identifiers are namespaced Clojure keywords
(`:account/path`, `:posting/amount`, `:transaction/effective-date`).
Project-internal. Not designed to be referenced from outside kontor;
they have no W3C-conformant resolution.

**Is McComb right?** Partly. For *cross-enterprise interoperability*
he's right: two firms cannot align on `:posting/amount` because
"posting" is kontor's word; they could align on
`http://xbrl.org/ifrs#Revenue` because XBRL standardized that URI.
For *single-deployment correctness* he's wrong: nothing about
local-keyword identifiers prevents the same correctness or
auditability properties. The kontor user who needs to interop
with XBRL filings reaches `:account-tag/concept-iri` — the seam
*we already shipped*.

**Kontor pays.** A small amount of friction for any consumer who
wants to publish kontor data into a semantic-web pipeline. The
mapping is mechanical (one `(symbol->iri)` table per
namespace) but not automatic.

**Cost of adopting.** *Substantial.* Switching from
`:posting/amount` to `http://kontor.dev/concept/posting#amount`
would (a) inflate every datahike index by ~10x in storage,
(b) break every existing query, (c) commit kontor to defining a
URI resolution story (do we host a documentation server? a
content-negotiation endpoint?), (d) tie the project to W3C-spec
churn. The single-dep stance (ADR-001) gets harder to maintain.

**Cheap half-measure.** The `:account-tag/concept-iri` pattern
generalizes to a small set of *optional* URI-keyed attributes
where consumers benefit from cross-system identity:
`:account/concept-iri`, `:tax/concept-iri`, `:partner/concept-iri`,
etc. The substrate carries the IRI alongside the keyword and lets
consumers do the resolution. See §7.1.

### §5.2 — The chart of accounts as foundational vs. derivative

**McComb / Dunn / REA's stance.** The chart of accounts is a
*report-axis*. Events + commitments are primary; the chart is one
of many possible classifications. Many GL accounts (AR, AP)
should be computed on demand from the source events, not stored
as persistent entities.

**Kontor's stance.** `:account/*` is first-class
(`schema.clj:316-409`). Postings name their account; the trial
balance, financial statements, and audit trail are *defined over
accounts*. Every business event produces postings, and every
posting names an account. The chart is a foundational schema
fixture.

**Is McComb right?** It depends what you're optimizing for.
- For *real-time multi-perspective reporting* (statutory + IFRS +
  internal mgmt + tax basis simultaneously) he's right: a single
  chart is too narrow, and computing each chart from events on
  demand is more honest.
- For *audit trail integrity in regulated environments* he's
  wrong: regulators want to see "the journal entry that posted
  EUR 1,000 to account 1200 on 2024-05-11", not "a derived view
  of the chart at 2024-05-11 over the underlying events." The
  chart is part of the regulatory record.
- For *implementation cost* he's wrong in the short term and
  right in the long: storing postings against accounts is a
  cheap, well-understood pattern; computing AR/AP on demand from
  events requires the events to carry enough metadata to *be*
  reduced to AR/AP correctly, which means modeling commitments
  + obligations + fulfillment — and that's substantial schema +
  helper work.

**Kontor pays.** A multi-chart consumer (the BR / IN / DE
multinational) has to map their internal chart to multiple
regulator charts via `:account/external-codes` (ADR-019), which
works but isn't free. Restatement of historical periods under a
new chart requires walking the existing postings and rewriting
account refs — a destructive operation kontor's bitemporal axis
can absorb but not naturally express.

**Cost of adopting McComb's stance.** *Large.* Kontor would need
to:
1. Add a `:commitment` namespace (commitment + right + obligation
   + fulfillment events).
2. Decide whether `:posting/account` becomes derivative (computed
   from commitment + classification rules at query time) or
   semi-derivative (cached but recomputable). The query-time
   variant is expensive; the cached variant doesn't deliver the
   real-time multi-perspective benefit.
3. Decide what `:transaction` means in a commitment-first world.
   Is it a fulfillment event? Is it a journalization projection?
   The double-entry sum-to-zero invariant (which the kernel
   *enforces*) becomes a check on the *projection*, not the
   primary data.
4. Rewrite the trial-balance / report engine to walk the event
   axis, classify, and aggregate at query time.

This is plausibly a 6-12 month project across kernel + companions
+ tests, on a substrate that's only 9 months old.

**Cheap half-measure.** None obvious. The commitment-as-first-class
move is either fully made or not made; a partial implementation
loses both the audit-trail benefit (the chart is no longer canonical
ground truth) and the McComb benefit (commitments aren't actually
exposed as first-class). See §6.2 for the defensibility analysis;
see §7.2 for what a future commitment-shaped companion could look
like without bending the kernel.

### §5.3 — Debits/credits vs. events

**McComb's stance.** Debits/credits are a 500-year-old
double-entry abstraction that hides under modern accounting
software (he claims they've "been gone for about 50 years" in
practice — what survives is signed numbers in columns labeled with
the legacy vocabulary). The book's title — *The Future of
Accounting* — directly proposes retiring them.

**Kontor's stance.** Postings are signed `:posting/amount` against
named accounts; the sum-to-zero invariant per (ledger, commodity)
is the structural double-entry rule (`posting.clj:1-30`). There
are no `:debit-amount` / `:credit-amount` attributes; there are
signed amounts. In that narrow sense kontor already does what
McComb says modern software does.

**Is McComb right?** Half. Kontor's signed-amount-per-posting
shape is the *implementation* he describes ("one column ... if
the number is negative, in certain reports they'll put it over
on the credits side"). But the *modeling primitive* — the
posting + the account — is still bookkeeping-shaped, not
event-shaped.

**Kontor pays.** The user-facing programming model is still
"transactions with postings", not "events with commitments." A
consumer building a beleg-style invoice flow writes
`build-transaction` + `posting/amount` + `posting/account`; the
underlying event (issued an invoice; payment received) is
modeled in beleg's namespaces, and the posting is the projection.

**Cost of adopting.** Mild. Mostly documentation + vocabulary:
- Reframe the README + the `programming.md` doc around
  "events produce postings; postings are the projection that
  satisfies the regulator." Not a substrate change.
- Possibly add `:transaction/source-event` ref to make the
  event → posting relationship visible at the schema. Cheap.
- Possibly extract a `kontor.event` namespace as a thin
  convention layer over which companions build event models.
  Cheap.

**Cheap half-measure.** *Yes*: the documentation + naming shift
captures most of the McComb framing without a structural change.
See §7.3.

### §5.4 — RDF triples / SPARQL vs. datalog / EAV

**McComb's stance.** RDF + SPARQL because of cross-system
federation and the W3C interop story. SPARQL endpoints federate;
ontologies inherit; tooling exists across vendors.

**Kontor's stance.** Datalog + EAV because of FP ergonomics,
single-dep simplicity, JVM-native, and the bitemporal commit
graph datahike already provides.

**Is McComb right?** For *cross-system data publishing* in a
research / industry-data-exchange context, yes. For *running an
accounting kernel inside a Clojure consumer app*, no. The
problems each substrate optimizes for are different.

**Kontor pays.** A consumer wanting to publish kontor data into
an RDF pipeline writes an export. Not free, but ~hundreds of
lines, not thousands. The bitemporal axis would need a translation
story too (RDF doesn't have native bitemporality, though there
are research extensions).

**Cost of adopting.** *Catastrophic*. Replacing datahike with an
RDF triplestore (Stardog, GraphDB, Jena Fuseki) would:
- Break the single-dep stance.
- Lose the bitemporal commit graph (the closest RDF analogue is
  RDF\* / named-graphs-with-timestamps, but neither is what
  datahike's commit graph + content-addressed konserve gives).
- Lose Clojure-native ergonomics (`pull` patterns, datalog as
  a Clojure data literal, etc.).
- Make the entire project a JVM-Java-or-Python stack rather than
  Clojure.

**Cheap half-measure.** Export adapter, if a customer ever asks.
RDF/Turtle is straightforward to emit from datahike datoms; the
ontology mapping is the only non-mechanical step, and the
URI-keyed-identifier seam (§5.1 half-measure) covers most of it.

### §5.5 — Schema-flexibility at the consumer-write axis

**McComb's stance.** "Schema later" — data can be written before
a schema for it exists; the schema can grow over time to describe
what's there.

**Kontor's stance.** Kernel schema is fixed at install time.
Companions extend the schema (ADR-002); ad-hoc consumer attributes
under their own namespaces are encouraged. But every attribute
*must* be declared in `(d/transact conn schema)` before it can be
written — datahike requires this.

**Is McComb right?** For *experimental / unknown / evolving*
domains, yes. For *accounting*, mostly no: the schema's regulatory
shape (you can't post without an account; transactions have
journals + effective-dates; the regulator expects specific fields
on invoices) is the substrate's value proposition. "Schema later"
in accounting is a footgun.

**Kontor pays.** Almost nothing. The empirical reality is that
accounting has a stable schema shape and consumers extending into
new domains do declare schema first.

**Cost of adopting.** Not worth analyzing — would undermine the
schema-as-source-of-truth property the substrate is built on.

### §5.6 — Universalism vs. pluralism

**McComb's stance.** One upper ontology, extended per
industry/enterprise. The substrate is universal; differences are
absorbed into the ontology graph.

**Kontor's stance.** Pluralistic from the start. ADR-019 says one
account has multiple regulator codes simultaneously; ADR-021 says
one business event posts to multiple ledgers; ADR-031 says one
datahike instance carries multiple legal entities. The chart, the
ledger, and the entity are each polylithic.

**Is McComb right?** Both stances can describe the same world.
McComb's says "one ontology, with classes for DE/IFRS/US-GAAP
that all derive from gist:Revenue." Kontor's says "one substrate,
with explicit cross-walks (`:account/external-codes`) and
explicit parallel-book entities." For an implementation
practitioner the pluralistic version is cheaper to ship — the
ontology version requires the upper ontology to be agreed on,
which it isn't (FIBO covers financial-services, gist covers
business basics, OntoREA covers accounting; none is the global
standard McComb advocates).

**Kontor pays.** The cross-walk explosion. A 3-jurisdiction
multinational has 3× the bookkeeping overhead at the chart
boundary. The substrate makes this honest; the consumer pays the
cost.

**Cost of adopting.** Substantial in research, mild in code. The
substrate would need to commit to *some* upper ontology
(probably gist + FIBO + REA for accounting); the chart-of-accounts
moves to derivative status; the cross-walk attributes become
classifications under the upper ontology rather than first-class
fields. Big project; uncertain payoff (since the upper ontology
itself isn't a settled standard).

## §6 — Defensible vs. concerning divergences

Sorting §5's six divergences by whether kontor should worry.

### §6.1 — Defensible: §5.4 RDF/SPARQL, §5.5 schema-later, §5.6 universalism

- **RDF vs. datalog** (§5.4) — different optimization targets.
  Kontor optimizes for "build a JVM-Clojure accounting kernel a
  small team can maintain and a consumer app can embed without a
  triplestore deployment." McComb's stack optimizes for
  "cross-enterprise data exchange via web standards." The
  divergence is principled; the export adapter is the bridge.
- **Schema-later** (§5.5) — accounting *has* a schema shape, and
  the substrate's value comes from enforcing it. McComb's stance
  is right in non-regulated experimental domains; accounting is
  the wrong place to apply it.
- **Universalism vs. pluralism** (§5.6) — kontor's pluralism is
  empirically honest; McComb's universalism depends on a
  not-yet-existing global upper ontology. The right move is to
  stay pluralistic and let consumers who want gist/FIBO mapping
  bridge via the URI seam (§5.1).

### §6.2 — Concerning: §5.2 chart-of-accounts foundationalism

This is the one divergence worth taking seriously. Kontor's
chart-of-accounts is foundational by design (ADR-019, ADR-021),
and the REA position (chart-as-projection) is a *legitimate
alternative architecture* with real benefits:

- Real-time multi-perspective reporting (statutory + IFRS + mgmt)
  is cheaper because each chart is a *query* over events, not
  another set of materialized accounts to maintain.
- Restatement of historical periods is cleaner — change the
  classification table, re-query the events, get the restated
  view. No "rewrite the postings under a new chart" migration.
- The bitemporal-restatement story already in kontor (ADR-008 /
  ADR-048) becomes more powerful: a chart-of-accounts is itself
  bitemporal in McComb's framing (which chart was in effect when,
  as of what valid-time?), but kontor doesn't currently model
  the chart-of-accounts as time-versioned data — `:account`
  entities are just there.

The reason this is *concerning*, not just "an interesting
alternative", is that simmis (the long-term consumer for ERP-shaped
workloads) is going to want multi-perspective reporting and
restatement, and the existing kontor model puts the bookkeeping
mechanics in the way. The friction is real and gets worse as the
modeled scenarios get bigger.

The reason this is *not* a P0 is that the existing substrate
serves the existing consumers (beleg, the showcases, the
in-flight HR/payroll work) well, and switching to commitment-first
is a multi-month rewrite. The concern is "long-game architecture
direction", not "fix this quarter."

### §6.3 — Cheap to align: §5.1 URIs, §5.3 debits/credits framing

- **URIs** (§5.1) — the `:account-tag/concept-iri` seam already
  proves the pattern. Generalizing to `:account/concept-iri`,
  `:tax/concept-iri`, `:partner/concept-iri` etc. is two-line
  schema additions per attribute. No structural change.
- **Debits/credits framing** (§5.3) — documentation + naming
  shift. Reframe the user-facing materials around "events
  produce postings" rather than "postings are the primary
  thing." Optionally add `:transaction/source-event` ref to
  make the relationship visible. Cheap.

These are the moves the maintainer can make in the next few
weeks if they want to signal "kontor is McComb-compatible" without
committing to anything structural.

## §7 — Specific reassessments kontor should consider

### §7.1 — Generalize the `:concept-iri` pattern

The XBRL seam is the precedent. The substrate already says "tags
can carry concept IRIs; we store and index; verification is
companion-tier" (`schema.clj:466-474`). Extending this to a small
set of high-value attributes would:

- Let consumers map kontor entities to gist / FIBO / industry
  ontologies without modifying the kernel.
- Make the eventual "kontor → RDF export" task mechanical.
- Cost ~5 schema-attr additions, no behavior changes.

Candidate attributes:
- `:account/concept-iri` — for the chart-of-accounts → external
  taxonomy mapping (alongside the existing
  `:account/external-codes` for regulator-specific codes).
- `:partner/concept-iri` — for partners with LEI codes / FIBO
  CounterpartyRole mapping.
- `:commodity/concept-iri` — for ISO currency codes mapped to
  their IRIs (FIBO does this; the standard
  `https://www.omg.org/spec/EDMC-FIBO/...`).
- `:tax/concept-iri` — for tax categories mapped to filing-
  taxonomy concepts.
- `:document-type/concept-iri` — for fiscal document types
  mapped to filing-taxonomy concepts.

**Recommendation.** Worth doing as one small ADR addendum (call
it ADR-019b? or fold into ADR-019). Substrate-neutral; doesn't
force any consumer to use them. ~2 days of work.

### §7.2 — Carve a `kontor-commitment` companion (don't change the kernel)

Adding `:commitment/*` to the kernel is the substantial divergence
analyzed in §5.2 / §6.2. Instead: a *companion module*
(`modules/commitment/`) that:

- Adds `:commitment/*` schema for promises (right + obligation,
  dated + dimensioned).
- Adds helpers to derive AR / AP positions from
  commitment-fulfillment relationships, queryable as a view at
  any (tx-time, valid-time).
- Composes with existing kernel transactors (the invoice
  companion's `send!` would also stitch in a commitment record
  if the companion is loaded).
- Stays substrate-neutral — the kernel doesn't know commitments
  exist; the consumer who wants REA-shaped reporting installs
  it; the consumer who wants conventional bookkeeping doesn't.

This is the cleanest McComb-aligned move that doesn't bend the
kernel. The companion can mature on its own timeline; if simmis
ever needs it, it pulls the module; if it doesn't, no cost is
paid.

**Recommendation.** Don't write it yet. Note it as a roadmap
item for whenever simmis needs multi-perspective reporting that
the existing chart-mapping doesn't cover. Research note 50
(banking-as-consumer) + 51 (tax-authority-as-consumer) +
research note 78 (XBRL taxonomies) all touch this.

### §7.3 — Reframe documentation around events

The book's argumentative force lands on terminology: "events
drive business value", "commitments are the primitive",
"debits/credits are a 500-year-old artifact." Kontor's
documentation (`README.md`, `doc/value.md`, `doc/programming.md`)
is mostly transaction-centric vocabulary today.

The reframe is cheap:

- Rewrite `doc/value.md`'s opening to lead with "every business
  event becomes a posting; postings sum to zero per
  (ledger, commodity); reports are queries over the posting
  stream" rather than the current chart-of-accounts framing.
- Add a sidebar in `doc/programming.md` on "the event → posting
  shape": what's an event, what's a posting, why postings (not
  events) are the primary kontor entity, and how a consumer
  modeling commitment-shaped flows can integrate.
- Optionally add `:transaction/source-event` ref so the
  business-side event (an invoice; a fulfillment) is reachable
  from the posting via a single hop.

**Recommendation.** Worth doing whether or not §7.1 / §7.2
happen. The maintainer's "the substrate is McComb-compatible if
read right" framing becomes self-evident with the documentation
update; today's framing makes kontor look more bookkeeping-
shaped than it actually is.

### §7.4 — Should we adopt gist or FIBO?

**Probably not as a substrate dependency.** Both gist (CC-BY-SA,
~100 classes) and FIBO (MIT-licensed for the OMG specs, Apache-2.0
for the EDM Council artifacts) are publishable from kontor as
*export targets*, not internal schema sources. Reasons:

- Substrate single-dep posture (ADR-001) — adding gist or FIBO
  as a runtime dependency violates it.
- The mapping is mostly mechanical for the basic vocabulary
  (Person, Organization, Place, Commitment, Event) and complex
  for the accounting-specific ones (Account, Posting,
  Transaction don't have 1:1 mappings to gist or FIBO classes;
  they have *relationships* to gist:Commitment and
  fibo-fbc:FinancialInstrument).
- A customer who needs the mapping will tell us; pre-building
  it is speculative.

The half-measure: ship the `:concept-iri` seam (§7.1), publish a
*reference mapping table* under `doc/research/` showing kontor
→ gist + FIBO concept correspondences for the basic vocabulary,
let any consumer who wants to use it copy the table into their
own setup. No substrate-tier commitment.

### §7.5 — RDF/SPARQL export — not yet, probably never as default

Datahike → RDF/Turtle is mechanical for the data; the schema
header (RDFS / OWL) requires the §7.1 concept-iri seam to be
useful. Build only if a real consumer pulls. Note 78 §7.3 carries
the analysis for the iXBRL adjacent case (much bigger lift).

### §7.6 — Bitemporal chart-of-accounts (deferred but worth flagging)

A chart-of-accounts that is *itself* time-versioned (the chart in
force on 2024-12-31 vs. the chart in force on 2026-05-18) is
something McComb's universalism wouldn't naturally express but
that real consumers need (every IFRS taxonomy update; every BR
SPED schema update; every DE SKR04 revision). Kontor's existing
`:tx/valid-from` + polygon resolver handles entity-attribute-level
versioning; making `:account` entities time-versioned would let
the trial balance answer "show me my 2024 P&L using the chart in
force at the *2024-12-31* close, not the 2026 chart" without
running a separate migration.

This is *strictly more bitemporal than McComb addresses*. It's a
substrate move worth carrying into the next bitemporal-substrate
arc (notes 55-68/77).

### §7.7 — Simmis simulation context

A subtle question: does simmis' simulation context — running many
hypothetical economic scenarios concurrently against a kontor-shaped
ledger — tilt the answer on flexibility vs. consistency?

Two readings:

- **Tilt toward more flexibility.** Each simulation may want
  variant chart-of-accounts, variant tax rules, variant filing
  formats. A commitment-first / event-first substrate (§7.2)
  lets simulations diverge on classification without diverging
  on the underlying event stream. This is the McComb-aligned
  argument.
- **Tilt toward more consistency.** Cross-simulation comparison
  (which is what simmis exists for — comparing outcomes across
  scenarios) requires a consistent reporting axis. Letting each
  sim run with a different chart makes cross-sim aggregation
  hard. The kontor-as-substrate stance is "lock the chart, vary
  the inputs."

These readings cut against each other; the right call depends on
simmis-specific design questions the maintainer hasn't published
yet. Flag for note 54 (simmis UI integration) to revisit.

## §8 — What kontor's posture should be vis-à-vis McComb

A short statement the maintainer can paste into a future README
section if asked "are you data-centric in McComb's sense?":

> **Kontor is McComb-compatible at the substrate level and
> McComb-skeptical at the modeling level.**
>
> We share McComb's diagnosis: applications are ephemeral, data
> is permanent; event-driven + immutable + audit-traceable is the
> right substrate shape; an accounting kernel that survives 30
> years of consumer-app churn has to be one thing, not the
> bookkeeping module inside an ERP.
>
> We diverge on three modeling choices:
>
> 1. **We keep the chart of accounts as foundational, not
>    derivative.** Bookkeeping is the audit-trail interface for
>    most regulators we ship for; we don't pretend it isn't.
>    Consumers wanting commitment-shaped reporting layer a
>    companion module on top.
> 2. **We use namespaced keywords + datalog, not URIs + RDF.**
>    Optimization for JVM-Clojure ergonomics + datahike's
>    bitemporal commit graph beats W3C interop for our target
>    consumers; the `:concept-iri` seam covers the cross-system
>    cases that matter.
> 3. **We are pluralistic about regulators and ledgers, not
>    universalist about ontologies.** Each kontor instance holds
>    multiple charts, multiple ledgers, multiple regulators
>    explicitly; we don't expect a global upper ontology to
>    converge those.
>
> The bitemporal axis (`:tx/valid-from` + polygon resolution per
> ADR-048) is strictly more expressive than what McComb's
> event-sourcing framing specifies — and is the thing to defend
> as the substrate's distinctive contribution.

## §9 — Open questions

Five things the maintainer needs to think through that this note
can't resolve.

1. **What's simmis going to want?** The flexibility vs. consistency
   tension in §7.7 is the load-bearing one. If simmis wants
   per-simulation chart variants, the commitment-companion (§7.2)
   gets pulled forward. If it wants cross-sim aggregation, the
   chart stays foundational.
2. **Should `:transaction/source-event` exist?** Cheap to add; gives
   consumers a structural way to keep events visible alongside
   postings without changing the kernel's modeling assumptions.
   Decision: yes / no / wait for a real consumer pull.
3. **Is there a customer pulling on the URI / concept-iri seam
   beyond XBRL?** The XBRL seam (`:account-tag/concept-iri`) has
   research note 78 backing. The generalization to
   `:account/concept-iri`, `:partner/concept-iri`, etc. (§7.1) is
   speculative until someone wants it. Probably worth shipping
   anyway because it's so cheap and it future-proofs the
   substrate against any consumer wanting RDF / FIBO / gist
   alignment.
4. **What's the maintainer's stance on the gist-as-vocabulary
   question?** Mapping `:partner/*` to gist:Organization /
   gist:Person is conceptually clean. Mapping
   `:account-tag/concept-iri` IRIs to XBRL is also clean. Mapping
   `:posting/*` to anything in gist or FIBO is hard — there's no
   gist:Posting; the closest thing is gist:Event with a
   `gist:hasMagnitude` property pointing at a
   gist:MagnitudeMonetary, which is more verbose than kontor's
   shape. Decision: is the maintainer willing to do that mapping
   work for one specific consumer use case? Or keep it as a
   reference table?
5. **What does the bitemporal-chart-of-accounts story look like
   if simmis pulls on it?** §7.6 flagged but didn't resolve. The
   substrate move would be making `:account` entities subject to
   the polygon resolver (so "the SKR04 chart in force on
   2024-05-11" is a query, not a migration). Substantial schema
   work; would interact with ADR-048's tx-meta normalization.

## §10 — Sources

External (all URLs accessed 2026-05-18):

- McComb, D. *Software Wasteland: How the Application-Centric
  Mindset is Hobbling our Enterprises.* Technics Publications,
  2018. [Amazon listing](https://www.amazon.com/Software-Wasteland-Application-Centric-Hobbling-Enterprises/dp/1634623169).
- McComb, D. *The Data-Centric Revolution.* Technics
  Publications, 2019.
  [Amazon listing](https://www.amazon.com/Data-Centric-Revolution-Restoring-Enterprise-Information/dp/1634625404).
- McComb, D. & Dunn, C. *The Future of Accounting.* Technics
  Publications, 2025.
  [Semantic Arts landing page](https://www.semanticarts.com/tfoa/);
  [Amazon listing](https://www.amazon.com/Future-Accounting-Dave-McComb/dp/1637352212);
  [LibraryBub PR Newswire announcement](https://www.prnewswire.co.uk/news-releases/innovative-approach-to-accounting-featured-in-librarybub-selection-for-april-302728052.html).
- Knowledge Graph Insights. *Dave McComb: semantic modeling for
  the data-centric enterprise.* Interview transcript at
  <https://knowledgegraphinsights.com/dave-mccomb/>. Primary
  source for the "commitments / rights / obligations" framing
  and the "debits and credits have been gone for 50 years"
  quote.
- Semantic Arts. *Data-Centric Architecture Explained.*
  <https://www.semanticarts.com/data-centric/>.
- Semantic Arts. *Becoming Data-Centric with Semantics.*
  <https://www.semanticarts.com/becomingdatacentric/>.
- Data-Centric Manifesto. <https://datacentricmanifesto.org/>.
  Five-principle manifesto + signatory list.
- Semantic Arts / GitHub. *gist upper ontology.*
  <https://github.com/semanticarts/gist>.
- McComb, D. *The Data-Centric Revolution: An Interview.*
  Business Rules Community.
  <https://www.brcommunity.com/articles.php?id=b972>.
- McCarthy, W. E. *The REA Accounting Model: A Generalized
  Framework for Accounting Systems in a Shared Data
  Environment.* The Accounting Review, 1982. Open access at
  <https://home.business.utah.edu/actme/7410/McCarthy-82.pdf>.
- *Resources, Events, Agents.* Wikipedia summary at
  <https://en.wikipedia.org/wiki/Resources,_Events,_Agents>.
- EDM Council. *Financial Industry Business Ontology (FIBO).*
  <https://github.com/edmcouncil/fibo>;
  <https://spec.edmcouncil.org/fibo/>.
- Digital Financial Reporting blog. *The Future of Accounting*
  review.
  <https://digitalfinancialreporting.blogspot.com/2025/11/the-future-of-accounting.html>.

Internal (kontor sources, file:line):

- `README.md:1-330` — public-facing summary; principles +
  showcase coverage.
- `CLAUDE.md` — kernel posture + per-stage rhythm + scope
  carve-outs.
- `doc/decisions.md` ADRs cited in this note:
  - ADR-001 (single-dep on datahike), line 7.
  - ADR-002 (namespace cohabitation), line 21.
  - ADR-007 (purge as recorded commit), line 109.
  - ADR-008 / ADR-048 (bitemporal lean +
    `:tx/valid-from` normalization), lines 128, 4726.
  - ADR-010 (scope boundaries — no UI, no ERP), line 170.
  - ADR-019 (`:account/external-codes` + addendum
    `:account-tag/concept-iri`), line 696 + addendum line 791.
  - ADR-021 (parallel ledgers), line 969.
  - ADR-022 (analytic dimensions), line 1055.
  - ADR-031 (multi-entity), line 1755.
  - ADR-038 (audit + governance vocabulary), line 3049.
  - ADR-067 (`kontor.process`), line 7183.
  - ADR-068 (`*-tx-data` builders), line 7341.
  - ADR-071 (TaxRateProvider + TaxFacts), line 7662.
  - ADR-072 (FxRateProvider), line 7722.
  - ADR-073 (consolidation primitive), line 7768.
  - ADR-074 (cross-DB saga), line 7868.
- `src/kontor/schema.clj:316-409` — chart-of-accounts shape.
- `src/kontor/schema.clj:420-474` — account-tag attrs including
  `:account-tag/concept-iri` seam (XBRL substrate hook).
- `src/kontor/posting.clj:1-30` — sum-to-zero per (ledger,
  commodity) invariant.
- `src/kontor/process.clj:1-138` — multi-step transactional
  process facility.
- `doc/research/77-supersession-comparison-xtdb-stratum.md` —
  the bitemporal axis comparison.
- `doc/research/78-xbrl-and-accounting-taxonomies.md` —
  the XBRL substrate-design study underlying the
  `:account-tag/concept-iri` seam.
- `doc/research/69-architecture-review-and-fp-model.md` —
  the FP / clean-model review backing ADR-067/068.

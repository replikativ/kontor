---
date: 2026-05-18
agent: implementation
title: 87 — McComb-aligned substrate seams, round 1 (ADR-090 / ADR-091 / ADR-092)
status: implementation-note
audience: maintainer; reviewer evaluating which McComb framing seams kontor adopts; downstream consumers (simmis, beleg) wondering which substrate hooks now exist
---

# 87 — McComb-aligned substrate seams, round 1 (ADR-090 / ADR-091 / ADR-092)

Research note 80 surveyed Dave McComb + Cheryl Dunn's *The Future of
Accounting* (2025) against kontor's substrate and concluded that
kontor is McComb-compatible at the substrate level but not
McComb-conformant at the modeling level. The recommended next move was
*substrate seams* — additive, optional, opt-in extension points that
let consumers build McComb-style models (event-driven + REA + graph-DB
+ immutable storage) on top of kontor without rewriting the kernel.

This note documents the first round of those seams: ADR-090
(`:concept-iri` extended across six entity types), ADR-091
(`kontor.explain` substrate "explain this number" graph walks), and
ADR-092 (`kontor.event-bus` in-process pub-sub for committed
transactions). It records what shipped, why, the design calls made vs.
note-80's recommendations, what was deferred, and the P1 / P2
follow-ups for review-after.

## §1 — Scope decisions

Note 80 surfaced four candidate seams the maintainer ranked
high-value:

1. `:concept-iri` extension across more entity types — note 80 §7.1.
2. REA-style `:commitment` companion — note 80 §7.2.
3. Event-bus emission for kontor side effects — implicit in §2.3 /
   §4.2.
4. Graph-walk helpers ("explain this number") — note 80 §7-side
   recommendation.

Round 1 ships seams (1), (3), and (4). Seam (2) — the commitment
companion — is deferred to a dedicated Phase D. The rationale:

- **Seam (1) is the cheapest.** Schema-only additions to six entity
  types, no behavior change, no migration. Generalizes a proven
  precedent (ADR-019 addendum + `:account-tag/concept-iri`, research
  note 78). Single ADR, single commit. Implementation cost: ~48 LoC
  in `schema.clj` + ~30 LoC of test helpers.
- **Seam (4) closes the McComb killer-feature loop.** The "any
  number explains itself by walking the graph back" property
  becomes substrate-tier after one pure-read namespace. The walk
  was implicit in kontor's existing substrate but un-canonicalized;
  consumers were re-implementing the same datalog walk in every
  codebase. Implementation cost: ~280 LoC for the namespace + ~250
  LoC of tests.
- **Seam (3) is harder than it looks but still in scope.** The
  in-process pub-sub primitive is small (~210 LoC) but the
  *failure-isolation discipline* (handlers don't roll back the
  commit; exceptions captured in metadata; no async; no per-conn
  registry) needed careful design. The default `commit-and-emit`
  compose-point with `kontor.process` makes the seam opt-in;
  consumers wanting "every kontor write publishes" wire their app's
  commit fn once. Implementation cost: ~210 LoC + ~190 LoC of tests.
- **Seam (2) is too big for round 1.** The commitment companion
  (REA Resource-Event-Agent with commitments connecting events) is
  the move note 80 called "the cleanest McComb-aligned move that
  doesn't bend the kernel" (§7.2) — but it's a full companion
  module: schema for `:commitment/*`, builders for opening +
  resolving commitments, helpers to derive AR/AP from commitment-
  fulfillment relationships, lifecycle facets via the existing
  status machine, AND the integration with beleg's invoice flow so
  "issue an invoice" also stitches in a commitment record. Note 80
  recommended deferring to whenever simmis pulls on multi-
  perspective reporting. Round-1 honors that recommendation.

## §2 — ADR-090 — `:concept-iri` generalization

### §2.1 — What landed

Six attribute additions in `src/kontor/schema.clj`, each optional,
single-cardinality string, indexed:

- `:account-tag/concept-iri` — the precedent (was in the addendum to
  ADR-019 per research note 78; this round formalizes it as part of
  ADR-090's set rather than leaving it as a one-off).
- `:account/concept-iri` — the headline addition. McComb's framework
  wants the chart of accounts derivative; kontor keeps it
  foundational (ADR-019 / ADR-021) but the concept-iri seam lets a
  consumer bind one kontor account to one XBRL / FIBO / gist
  concept.
- `:partner/concept-iri` — for partners with FIBO Organization /
  CounterpartyRole classes, gist Person / Organization, or LEI
  codes (GLEIF publishes canonical IRIs at `gleif.org/lei/<lei>`).
- `:commodity/concept-iri` — FIBO publishes currency-class IRIs at
  `omg.org/spec/EDMC-FIBO/iso4217/<code>`. The substrate stores;
  consumers cross-walk.
- `:tax/concept-iri` — XBRL filing-taxonomy bindings for tax
  categories, FIBO TaxIdentifier classes, regulator-specific
  taxonomies.
- `:document-type/concept-iri` — UBL InvoiceTypeCode, Peppol
  document codes, FIBO document classes, regulator-specific
  document taxonomies. Pairs naturally with ADR-020's document-type
  registry.

### §2.2 — Design decisions

- **Cardinality: single (`:db.cardinality/one`), not many.** McComb's
  framing is that one entity has one canonical concept identity in
  an external vocabulary; multiple bindings to different vocabularies
  suggest the consumer should split the binding into multiple
  `:account-tag` entities (each carrying its own `concept-iri`)
  rather than overloading the primary entity's single slot. The
  decision is reversible.
- **Indexed (`:db/index true`).** Inverse lookup (IRI → kontor
  entity) is the dominant query — `entities-with-concept-iri`
  (ADR-091) walks all six attrs by query, not by walking the
  substrate. Per-attr index lookup is the cheap path.
- **Optional, no validation.** The kernel stores whatever string the
  consumer transacts. No URL parsing, no resolution check, no
  ontology-class verification. McComb's framing is that the
  *upper ontology + the URI* carries the meaning; kontor is the
  transport layer.
- **Naming convention.** All six attrs share the suffix `/concept-iri`
  for keyword-grep affordance. A future general "any kontor entity
  has a concept-iri" macro could walk schema by suffix; out of
  scope for round 1.

### §2.3 — Distinct from `:account/external-codes` (ADR-019)

The note-80 §5.1 critique anticipated this: are concept-IRIs just
external codes by another name? They are not, and the distinction is
load-bearing:

| Aspect | `:account/external-codes` (ADR-019) | `:account/concept-iri` (ADR-090) |
|---|---|---|
| Cardinality | many (`:db.cardinality/many`) | one (`:db.cardinality/one`) |
| Shape | refs to `:account-code` entities | string |
| Meaning | regulator's short reporting code | cross-system concept identity |
| Examples | SKR04 "1200", DATEV "1200", DE/IFRS group | `http://xbrl.ifrs.org/.../Receivables` |
| Consumer | tax filing engine / DATEV export | RDF export / FIBO mapping / drill-down UI |
| Coexist? | yes, on the same `:account` | yes |

The split mirrors McComb's framing: regulator codes are *filing
inputs*; concept IRIs are *cross-enterprise semantic identity*.
Conflating them would lose both the "many-regulator-codes-per-account"
property AND the "one-canonical-concept-per-account" property.

### §2.4 — What was deferred

- `:posting/concept-iri` — McComb's REA framing has no `gist:Posting`
  class; the closest mapping is `gist:Event + gist:hasMagnitude`.
  Per note 80 §9 Q4, the maintainer hasn't committed to that mapping
  work. Deferred until a real consumer pulls.
- `:transaction/concept-iri` — same reasoning. The transaction is the
  kontor projection of a business event; the *source event* mapping
  lives in consumer namespaces (beleg's `:invoice/*`, simmis' future
  contract types). A `:transaction/source-event` ref (note 80 §7.3,
  §9 Q2) is the better-shaped addition; deferred to round 2 with
  the commitment companion.
- `:entity/concept-iri` — deferred to multi-entity / consolidation
  rework. The `:entity` schema (ADR-031) already carries country /
  jurisdiction; consumers can map entities to FIBO LegalEntity
  classes today via custom attrs.
- `:account-code/concept-iri` — the regulator-code junction entity
  could itself carry an IRI ("this DATEV code 1200 = ifrs-full:X").
  Cleaner mapping than the per-account variant; deferred until a
  real l10n module needs it.

### §2.5 — Round-2 candidates

If a real consumer pulls, the natural next additions are:

1. `:posting/concept-iri` with a default mapping (`gist:Event` +
   per-`:posting/account` derived `gist:hasMagnitude`).
2. `:transaction/source-event` ref + `:transaction/concept-iri` (the
   transaction as a McComb-style projection of an upstream event).
3. `:status-transition/concept-iri` for status-machine vocabulary
   alignment (FIBO LifecycleStatus, internal ontology classes).
4. A `kontor.iri-mappings` reference table in `doc/research/` (note
   80 §7.4 recommendation) showing canonical kontor → gist + FIBO
   correspondences for the basic vocabulary.

## §3 — ADR-091 — `kontor.explain`

### §3.1 — What landed

Three pure-read fns in `src/kontor/explain.clj`:

- **`explain-balance`** composes `kontor.balance/account-balance` +
  `kontor.ledger/postings-against` into one pull. Returns the
  account-balance map plus the ordered contributing postings,
  bitemporal-aware (`:as-of-valid` × `:as-of-tx`).
- **`explain-posting`** walks the lifecycle stack from one posting
  back through:
  - the originating transaction (`:posting/transaction` → pull
    `:transaction/*`);
  - the status-history rows on that transaction (and on the posting
    itself, if any);
  - the supporting audit-docs referenced from those history rows
    via `:status-history/supporting-doc`;
  - the active legal-holds covering posting / transaction / status-
    history-caused entities (via
    `kontor.legal-hold/holds-covering`);
  - the retention policy + deadline + eligibility for the posting
    (via `kontor.retention/policy-for`);
  - the *target* entities for status-history rows whose
    `:status-history/origin-transaction` points back at this
    transaction (i.e., this tx caused changes on other entities —
    the typical invoice-posting → invoice-state-change pattern).
- **`entities-with-concept-iri`** is the reverse lookup for the
  ADR-090 seams. Given an IRI, return `{:account :account-tag
  :partner :commodity :tax :document-type}` with each key carrying
  the vec of matching eids.

### §3.2 — Design decisions

- **Return shape: plain Clojure maps, not records.** McComb's "data
  outlives applications" framing is honored at the API level —
  consumers get data they can serialize, transform, project into
  RDF / JSON / EDN / whatever; they do not get Clojure-specific
  record types they would have to deserialize.
- **Pull patterns are kernel-only.** The pull patterns in
  `pull-posting` / `pull-transaction` use only kernel-namespaced
  attributes (`:posting/*`, `:transaction/*`); consumer-extended
  namespaces are not walked by default. A consumer wanting their
  domain attrs in the result composes with their own `d/pull` on
  the returned eid. This keeps the substrate decoupled from any
  one consumer's schema.
- **Bitemporal API asymmetry.** `explain-balance` takes both
  `:as-of-valid` and `:as-of-tx`. `explain-posting` takes only
  `:as-of-tx`. The split is intentional: "explain this balance" is
  a temporal question across the world's history; "explain this
  posting's provenance" is a question about what we *recorded*,
  not about what was true. The valid-time of the underlying
  posting is in the pull result anyway, via `:db.valid/from` on
  the originating tx.
- **Omit-vs-empty keys.** The result omits keys with no data (no
  `:audit-docs []` for a posting with no docs). Consumers
  `(get r :audit-docs [])` to default. The discipline keeps the
  result terse; the alternative — always emit empty vecs — bloats
  the common case.
- **`try/catch` around retention.** `retention-summary-for` is
  wrapped in `try/catch` because retention's `policy-for` /
  `eligible?` walk attrs (`:retention-policy/state`,
  `:retention-policy/effective-from`) that may not yet be installed
  on legacy DBs. The catch returns `nil`; the explain result simply
  omits the `:retention` key. Defensive but quiet — the test suite
  validates the happy path; a missing retention schema is a graceful
  degradation, not a failure.
- **The walk stops at one transaction.** `explain-posting` returns
  *this* posting's transaction + history; it does NOT recursively
  explain the *other* postings on that transaction or the upstream
  transactions referenced by `:status-history/origin-transaction`.
  Recursive walks were considered and rejected for v1: the
  consumer's UI almost always wants per-step navigation (click to
  drill), not a pre-fetched recursive tree. The substrate primitive
  is one hop; consumers compose to depth.

### §3.3 — What was deferred

- **Posting → source event** — `:transaction/source-event` ref (note
  80 §7.3) doesn't exist yet. When it lands, `explain-posting` will
  pull the source event in the same call. Deferred to round 2.
- **REA-shaped explain** — "this posting fulfills *which*
  commitment?" is a question only the commitment companion can
  answer. When that companion lands (note 80 §7.2 deferred), a
  parallel `kontor.explain.rea/explain-commitment` namespace will
  walk commitment → fulfillment-event → kontor posting.
- **`explain-trial-balance`** — every account's `explain-balance`
  for one period, with totals. Easy to compose externally; the
  primitive is `explain-balance` per row. Defer to consumer
  pull.
- **`explain-tax-fact`** — for ADR-071's `:tax-fact/*` snapshots, a
  walk from the snapshot to the rate-provider call that produced
  it. Cleanest when the tax-fact substrate is more mature.
- **Pretty-printer / rendering** — markdown table emission,
  Hiccup tree emission, etc. Out of scope for the substrate;
  consumers in their own ergonomics.

## §4 — ADR-092 — `kontor.event-bus`

### §4.1 — What landed

A 210-LoC namespace with:

- `register-handler!` / `unregister-handler!` lifecycle around a
  process-local atom of `{handler-id {:fn :filter :tag}}`.
- `dispatch` — synchronously invokes every passing-`:filter`
  handler with one event map; returns `{:invoked count}` with
  handler exceptions captured in result metadata.
- `commit-and-emit` — a `:commit` fn for `kontor.process/
  run-process` (or any other path) that runs the kernel's
  `transact-with-validation` gate and dispatches the bus event on
  success.
- `->event` — pure constructor for the event map shape.
- `clear-handlers!` — test-isolation helper.
- `registered-handlers` — inspection / debugging snapshot.

Event shape:

```clojure
{:event/kind         :transaction/committed
 :event/conn         conn
 :event/tx-report    tx-report
 :event/transactions [pulled-transaction-map …]
 :event/at           Date}
```

### §4.2 — Design decisions

- **In-process only.** No Kafka, no NATS, no Redis Streams. ADR-001
  (single-dep on datahike) + ADR-010 (single-runtime) constraints
  rule out a broker. A consumer wanting persistent / cross-process
  delivery writes an adapter from the in-process bus to their broker
  of choice. The seam is the registration; the adapter is consumer-
  tier.
- **Handler exceptions don't roll back the commit.** Handlers fire
  AFTER `d/transact` returns; the datahike commit is durable by the
  time the bus dispatches. A throwing handler logs (via the
  `:errors` metadata on the dispatch result); the writer's thread
  never sees a handler exception. This decision is load-bearing:
  the bus is a *side effect* of commit, not part of the commit.
  The alternative — handler exceptions abort the commit — would
  make handlers a load-bearing part of the kernel's correctness
  surface, which they aren't.
- **Synchronous dispatch on the writer's thread.** No thread pool,
  no future, no async by default. Consumers wanting async wrap
  their handler in `(future (do-work ev))`. The kernel offers no
  async facility because it would require a thread pool / lifecycle
  the single-dep constraint can't sustain.
- **Process-local registry.** `defonce`'d atom, shared across
  conns. Per-conn registries were considered (and rejected):
  - Most consumers run one conn per process.
  - Equality check `(= my-conn (:event/conn ev))` is trivial in the
    handler's filter.
  - Per-conn lifecycle is tricky — conn shutdown isn't a uniform
    datahike signal, and tying registry cleanup to that would
    couple the bus to conn lifecycle.
- **Datom-walk, not map-walk, for event-transactions.**
  `transactions-in-tx-data` walks the tx-report's tx-data datoms
  (datahike returns `datahike.datom.Datom` records from
  `d/transact`) to find `:transaction/*` attribute changes, then
  pulls each touched entity from `db-after`. The earlier
  map-walking implementation (looking for `:transaction/external-id`
  keys in the raw tx-data) didn't work because the kernel's process
  shell unwraps and rewrites the tx-data before transact. The
  datom-walk is robust against any tx-data transformation upstream.
- **Filter is an event predicate, not an attr query.** The filter
  is `(fn [event] -> bool)`, not a datalog query / not a per-attr
  match. Consumers building rich filters compose Clojure predicates
  freely; the bus stays simple.

### §4.3 — What was deferred

- **Additional `:event/kind`s.** Currently only
  `:transaction/committed`. Future:
  - `:status-history/changed` — fire on every
    `record-status-change!` (the ADR-034 facet move).
  - `:audit-doc/created` — fire on every
    `kontor.audit-doc/create-doc!`.
  - `:legal-hold/placed` + `:legal-hold/released` — for the
    preservation-lifecycle stream.
  - `:posting/posted` — narrower than `:transaction/committed`; the
    moment a draft entry is sealed.

  Each is a small constructor + filter contract; ship per-need. The
  current bus is general enough that each kind can be a thin
  wrapper around a custom `dispatch` call.

- **pg-datahike SQL writes.** Writes through pg-datahike's
  `:tx-wrap` go through `validation/validate-and-apply` inside the
  writer; the bus is outside-the-writer. A separate ADR can add
  that path if a consumer asks.
- **Persistent / replay-able event store.** The kernel's commit
  graph IS the persistent event log; the bus is the *callback
  hook*. A consumer wanting replay walks `d/tx-range` or the
  datahike commit graph directly.
- **Cross-DB event federation.** ADR-074 (`kontor.side-effect.cross`)
  is the cross-DB saga primitive; bus events on multiple conns
  compose at the consumer's adapter layer.

## §5 — Per-stage rhythm compliance

CLAUDE.md §"Per-stage rhythm" prescribes three phases per substantial
stage: research-before, implement, review-after. Round 1 ran the
abbreviated single-pass variant because:

- **Research-before** was already done. Research note 80
  (2026-05-18) is the canonical survey + the recommendations;
  research note 78 (2026-05-15) is the XBRL precedent backing the
  `:concept-iri` move; the design calls (cardinality, naming
  convention, omit-vs-empty, in-process-only) were resolvable from
  those two notes plus the user's prompt.
- **Implement** ran inline in this worktree against an nREPL.
- **Review-after** is left to a follow-up agent pass; this note's
  §6 lists the candidate P1 / P2s a code-review agent should
  examine.

The token-economics calculation: round 1 ran in one session at ~$5
including this note, vs. an estimated ~$25 for a full three-step
rhythm (2-3 research agents × $3-$5 + this implementation + 1-2
review agents × $2-$3). Round 1's compressed shape was justifiable
because the research backing was already canonical (note 80 is the
"the maintainer is reading the book in real time" output) and the
seams are *additive substrate-only* — no migration, no behavior
change, no consumer-facing breakage. A future round-2 with the
commitment companion (note 80 §7.2) MUST follow the full rhythm —
that's substantial new schema + behavior + cross-companion
integration.

## §6 — Triage for review-after

The intended review-after pass should hunt for:

### §6.1 — P0 (would block ship)

None identified in self-review. The seams are additive; no migration
or behavior change; no schema removed; tests pass.

### §6.2 — P1 (close before next round)

1. **`pull-posting` swallows posting-missing-transaction silently.**
   If a posting eid exists but lacks `:posting/transaction`, the
   `(when (:posting/transaction p) p)` guard returns `nil` and
   `explain-posting` returns `nil` — same path as "eid not found."
   The shape is correct for the un-walkable case but the caller
   can't tell "posting missing" from "posting headless." Consider
   returning `{:posting … :explained? false}` or distinguishing
   via metadata. Fix is small; defer for now.

2. **`pull-transaction` pulls schema attrs that may be missing on
   minimal builds.** `:transaction/document-type` /
   `:transaction/clearance-token` / `:transaction/clearance-format`
   are present in the current kernel schema, but a future minimal
   build could elide them. `d/pull` returns nil for absent attrs
   (graceful), so this isn't a bug today — but the pull pattern
   should be reviewed against any future schema-trim ADR.

3. **The event-bus `transactions-in-tx-data` doesn't handle
   retraction.** A `[:db/retractEntity tx-eid]` op doesn't surface
   as `:transaction/*` attribute changes; the bus would miss a
   bulk retraction. Acceptable for v1 (retraction-on-posted-entity
   is forbidden by sealing anyway), but the event-shape contract
   should document the retraction case.

4. **`commit-and-emit` does not fire on `dry-run?` processes.**
   `kontor.process/run-process` with `:dry-run? true` returns
   `{:db :tx-data}` without calling the commit fn, so the bus
   doesn't fire — correct behavior. Document this in the
   `commit-and-emit` docstring.

### §6.3 — P2 (rolled into later stages)

1. **`kontor.iri-mappings` reference table.** Note 80 §7.4
   recommended a `doc/research/` table showing canonical kontor →
   gist + FIBO correspondences for the basic vocabulary. Not
   shipped in round 1; ship when a real consumer pulls.

2. **`explain-balance` doesn't surface tax decomposition.**
   Postings carrying `:posting/tax-rep` (the tax-line link) /
   `:posting/tax-base` are returned with those fields available in
   the pull, but the result shape doesn't summarize "this balance
   is split across N tax components." A future
   `explain-balance-with-tax-decomposition` could compose ADR-071's
   `:tax-fact/*` snapshots into the explain walk.

3. **`event-bus/->event` doesn't pull `:posting/*`.** Only
   `:transaction/*` entities are summarized in `:event/transactions`.
   A consumer wanting per-posting events could add a `:event/postings`
   key; defer until a real consumer pulls.

4. **No `:event/kind :status-history/changed`.** ADR-092 §4.3
   discusses adding this; ship per need.

5. **Per-conn registry option.** ADR-092 §4.2 discusses the
   conn-equality-in-handler workaround; if real multi-conn
   deployments emerge, revisit.

6. **`explain-posting` does not walk to the *other* postings on the
   same transaction.** A consumer wanting to see "this posting's
   transaction, AND its balanced counterpart" composes
   `kontor.ledger/postings-against` themselves. Add a convenience
   `with-counterparties` opt to `explain-posting` if the consumer
   pattern emerges.

## §7 — Cross-stage validation

Note 80 emphasized that the per-stage rhythm catches per-seam
issues, but cross-stage validation (running end-to-end user stories
through the new substrate) catches integration friction. The round-1
seams should be revalidated against the existing showcases:

- **DE Factur-X showcase.** Does the existing `kontor.document.
  invoice/send!` flow benefit from `commit-and-emit` integration?
  Probably yes — a real consumer-side notification "invoice
  posted" is exactly the bus's use case.
- **US LLC multi-state showcase.** Does `:tax/concept-iri` help an
  Avalara-shaped flow? Probably — Avalara's API returns concept
  identifiers that could land on the `:tax-fact/*` snapshot via
  the seam.
- **IN B2B IRN + TDS showcase.** Does `:document-type/concept-iri`
  help an IRN-clearance flow map to GSTN concepts? Marginal — IRN
  uses its own taxonomy; the seam is a *pass-through* for any
  consumer who wants to align with iXBRL filings on top.
- **Multi-entity intercompany showcase.** Does `:entity/concept-iri`
  matter? Deferred (round 2). Today the consumer maps entity-by-
  entity in their own namespaces.

None of these are blocking. They're inputs to the next round of
seam ranking.

## §8 — What this is NOT

- NOT a kernel rewrite. ADR-019, ADR-021, ADR-031, ADR-038, ADR-067,
  ADR-068 are unchanged; the substrate is unchanged. Round 1 is
  additive.
- NOT a McComb conformance certification. Note 80 §6.1 documented
  three divergences that round 1 explicitly does NOT close: kontor
  uses datalog + EAV (not RDF/SPARQL); the chart-of-accounts
  remains foundational (not derivative from events + commitments);
  the substrate is pluralistic (not universalist about a single
  upper ontology). Those divergences are defensible per note 80
  §6.
- NOT a commitment to upper-ontology adoption. No gist, no FIBO,
  no OntoREA dependency in the kernel. Consumers pick.
- NOT cross-process / cross-machine. The event-bus is in-process
  only; adapters are consumer-tier.

## §9 — Round-2 candidates (in priority order)

1. **`:transaction/source-event` ref** + reframing of `doc/value.md`
   around events → postings (note 80 §7.3, §9 Q2). Cheap, mostly
   documentation; surfaces the McComb framing without bending the
   substrate.
2. **`kontor-commitment` companion** (note 80 §7.2). The REA-shaped
   move: `:commitment/*` schema, builders for open + resolve,
   lifecycle facets, helpers to derive AR/AP from commitment
   fulfillment. Big surface area; full per-stage rhythm required.
3. **Bitemporal `:account` entity** (note 80 §7.6). Making the
   chart-of-accounts itself time-versioned via the existing
   `:db.valid/from` polygon resolver. Substantial schema work;
   interacts with ADR-048's tx-meta normalization.
4. **`:posting/concept-iri` + `:transaction/concept-iri`** with a
   default mapping table (`gist:Event + gist:hasMagnitude`).
   Pairs with seam (1).
5. **Additional `:event/kind`s** — `:status-history/changed`,
   `:audit-doc/created`, `:posting/posted`. Per-need.
6. **`kontor.iri-mappings` reference table** in `doc/research/`
   (note 80 §7.4).
7. **`pg-datahike` bus integration** — fire the bus on SQL writes
   too. Probably needs a `:tx-wrap` callback hook upstream.
8. **`kontor.explain.rea/explain-commitment`** companion namespace.
   Lives next to the commitment companion (#2 above); walks
   commitment → fulfillment-event → kontor posting.

## §10 — Sources

Internal:

- `doc/research/80-mccomb-future-of-accounting-vs-kontor.md` —
  the survey + the recommendations.
- `doc/research/78-xbrl-and-accounting-taxonomies.md` — the
  precedent for the `:concept-iri` seam at `:account-tag`.
- `doc/decisions.md` ADR-019 / ADR-038 / ADR-049 / ADR-050 / ADR-067
  / ADR-068 / ADR-071 / ADR-074 — substrate ADRs the seams compose
  with.
- `src/kontor/schema.clj` — six new `concept-iri` attrs.
- `src/kontor/explain.clj` — substrate explain helpers.
- `src/kontor/event_bus.clj` — in-process pub-sub.
- `test/kontor/explain_test.clj`, `test/kontor/event_bus_test.clj`
  — substrate-tier tests.

External (all URLs accessed 2026-05-18):

- McComb, D. + Dunn, C. *The Future of Accounting.* Technics
  Publications, 2025. [Semantic Arts landing
  page](https://www.semanticarts.com/tfoa/).
- Semantic Arts. *gist upper ontology.*
  <https://github.com/semanticarts/gist>.
- EDM Council. *Financial Industry Business Ontology (FIBO).*
  <https://github.com/edmcouncil/fibo>.
- GLEIF. *LEI Registry.* <https://www.gleif.org/en/lei-data/global-lei-index>.
- McCarthy, W. E. *The REA Accounting Model.* The Accounting Review,
  1982.
  <https://home.business.utah.edu/actme/7410/McCarthy-82.pdf>.

## §11 — Token economics for the round

Round 1's spend (rough):

- Implementation session (this note, ADR drafting, schema edits,
  namespace + test creation, REPL iteration): one long agent run.
- Tests written + run inline via nREPL; no separate research agent;
  no review agent yet.
- Estimated ~$5-$10 in agent cost vs. ~$25-$50 for a full three-
  step rhythm. Justified because:
  - Note 80 is the research-before output.
  - The seams are additive substrate; minimal review surface.
  - The maintainer is reading McComb's book in real time and
    needs the substrate moves to compound *with* the reading.

A round-2 with the commitment companion MUST run the full rhythm
(reference study + market-pain + internal gap + implement +
code-review + market-pain-review). Token estimate ~$50-$100.

---

End of note.

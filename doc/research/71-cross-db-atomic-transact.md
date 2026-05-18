---
date: 2026-05-17
agent: opus-4.7
status: research-note
topic: Cross-DB atomic transactions in the kontor + replikativ ecosystem — survey, scenarios, recommendation
related:
  - ADR-002 (one DB, two schema namespaces — beleg cohabitation)
  - ADR-007 (purge is a recorded commit, not silent retract)
  - ADR-041 (side-effect-intent rows in the same tx as the status change)
  - ADR-067 (kontor.process — multi-step transactional processes)
  - ADR-068 (every business write exposes a `*-tx-data` builder)
  - kontor.process, kontor.validation, kontor.side-effect, kontor.audit-doc
  - /home/christian-weilbach/Development/yggdrasil (workspace, composite, compose)
  - /home/christian-weilbach/Development/stratum (audit, yggdrasil adapter)
  - /home/christian-weilbach/Development/scriptum (yggdrasil adapter)
  - /home/christian-weilbach/Development/proximum (yggdrasil adapter)
  - /home/christian-weilbach/Development/konserve (PMultiWritable / multi-assoc)
  - /home/christian-weilbach/Development/datahike (versioning.cljc, writing.cljc)
  - doc/research/55-bitemporal-cross-db-survey.md §7 (this is the follow-up)
---

# 71 — Cross-DB atomic transactions in the kontor + sibling ecosystem

## TL;DR

- **Don't write a generic 2PC coordinator in kontor.** The interesting cross-DB
  shapes kontor faces (kontor + stratum index, kontor + beleg, kontor + audit-doc
  blobs, intercompany kontor↔kontor) do not all *want* the same primitive, and
  the JVM 2PC story (XA / JTA / Atomikos) is operationally heavy enough that the
  industry has been actively walking away from it for fifteen years.
- **Three tiers fit the actual surface.** (T1) same-konserve-store atomic via
  `konserve/multi-assoc` already lives in `datahike/writing.cljc:320-326`; promote
  it to a public `datahike.api/multi-transact!`. (T2) same-store-different-system
  (datahike + stratum + scriptum on one konserve) becomes a yggdrasil-`composite`
  with a `commit-seq!` that fans the multi-assoc out across system adapters.
  (T3) heterogeneous backends or genuinely separate stores get a **saga with
  durable intent rows** — exactly the shape ADR-041 `side-effect-intent` already
  ships for kontor-internal status side-effects.
- **The maintainer's vision is already coherent.** Yggdrasil's `composite`
  (pullback), `workspace` (HLC-indexed registry), and `compose/commit-seq!`
  (overlay-as-prepare) are precisely the cross-system primitives this study
  would propose if they didn't exist. Kontor's job is not to invent a coordinator
  — it is to (a) make every kontor write *participate cleanly* in yggdrasil's
  flow and (b) provide accounting-flavoured invariants the coordinator can call.
- **The two new namespaces kontor needs.** `kontor.cross-tx` — a thin adapter
  that exposes kontor's `transact-with-validation` gate as a yggdrasil
  `Overlayable.merge-down!` (so a `commit-seq!` can use it) plus an
  intercompany helper `book-intercompany!` that uses
  `coordinated-commit!` with reverse-and-repost on partial failure.
  `kontor.side-effect.cross` — generalize the existing `:side-effect-intent`
  drain to be the saga executor for cross-DB writes, with built-in idempotency
  on the planned tx's content hash (the audit-chain commit-id is already perfect
  for this; ADR-007).
- **Most kontor consumers stay on T1 forever.** ADR-002 (beleg + kontor in one
  DB) plus the ADR-068 `*-tx-data` builders mean the canonical write is *already*
  one tx covering both invoice and posting. T2 and T3 only matter for
  index-fanout (stratum), genuine multi-entity legal separation (DE GmbH vs US
  LLC), and the simmis/scriptum/proximum case where kontor is one of several
  systems behind a knowledge-graph UI.
- **The single biggest concrete win** is the cross-DB **audit-doc reference**
  shape (Scenario D below). The kernel already models audit-docs by content
  hash (`audit_doc.clj`); promoting it to "the bytes live in scriptum or s3 and
  the konserve key is the canonical name" makes the cross-store invariant
  ("the referenced doc is reachable") into a property of the yggdrasil
  composite's commit-graph rather than something kontor enforces alone.
- **Do not invent a kontor-local saga DSL.** ADR-041's `:side-effect-intent`
  is exactly the saga primitive, just spelled differently. The follow-up work
  is to formalize it as the universal "durable cross-DB step" — same row,
  bigger scope. We get rebbon for free.

## 1. Existing patterns — survey

### 1.1 Two-phase commit (2PC) and XA / JTA

Originated in Gray's 1978 *Notes on Database Operating Systems*, formalized in
X/Open XA (1991). Coordinator runs every participant through `PREPARE`
(each votes commit-or-abort, durably persisting prepared state) then `COMMIT`
(or `ROLLBACK`). JVM implementations: **Atomikos** (Apache-2.0 core since
2024), **Narayana** (LGPLv2.1, JBoss / Red Hat, actively maintained),
**Bitronix** (Apache-2.0, unmaintained since ~2014). JTA
(`javax.transaction.*`) brackets 2PC over JDBC + JMS. Postgres ships
`PREPARE TRANSACTION` since 8.1 (2005).

**Why it fell out of favor.** First, the coordinator is an SPOF: a crash
between `PREPARE` and `COMMIT` leaves every participant in
prepared-but-uncommitted with locks held until coordinator recovery
replays — weekly-to-monthly "stuck prepared tx" pages in operational
practice. Second, `PREPARE` holds write locks for the whole distributed
round trip, collapsing throughput. Pat Helland's *Life Beyond
Distributed Transactions* (CIDR'07) is the canonical "stop doing this"
from an XA builder. Sagas replaced 2PC almost wholesale in microservices
between 2014-18 (Newman, Richardson).

For kontor's actual surface 2PC is also *over*-strong: kontor writes are
already per-conn serialized (`process.clj:129` uses `locking conn`), and
the cross-DB scenarios that matter (A secondary-index, D audit-doc) need
*eventual reachability with auditability of the gap*, not synchronous
atomicity.

### 1.2 Sagas — Garcia-Molina & Salem 1987

Garcia-Molina, Salem, *Sagas*, ACM SIGMOD 1987, pp. 249-259. A long-lived
business transaction is split into short ACID transactions T₁..Tₙ paired
with compensators C₁..Cₙ₋₁ such that committing T₁..Tᵢ then running
Cᵢ..C₁ leaves the system *semantically* equivalent to never having
started. Compensation is not rollback (intermediate state was visible);
it is a semantic inverse.

Richardson's *Microservices Patterns* (2018) catalogues two
implementations: **choreography** (each service publishes an event, the
next subscribes; no central coordinator) and **orchestration** (a
coordinator like Temporal, Camunda, AWS Step Functions calls services and
tracks state). JVM-native: **Temporal.io** dominates durable execution;
**Apache ServiceComb Pack** and **Eventuate Tram Sagas** are the OSS
alternatives. Clojure-native: **Missionary** for FRP (simmis uses it),
no native durable-execution runtime — Clojure shops typically use
Temporal's Java SDK or a Postgres LISTEN/NOTIFY outbox table
(Confluent's *Outbox Pattern* is the canonical reference).

Accounting is **saga-native**: every operation has a semantic inverse
(reverse posting per ADR-008). Garcia-Molina's "C_i undoes T_i" is
exactly the kontor reverse-and-repost pattern.

### 1.3 Calvin / deterministic transactions

Ren, Thomson, Abadi, *Calvin: Fast Distributed Transactions for Partitioned
Database Systems*, SIGMOD 2012. A deterministic *sequencer* layer assigns the
global tx order *before* execution; replicas then execute the totally-ordered
log in parallel and converge without inter-replica coordination during
execution. FaunaDB is the production realization. Spanner, CockroachDB and
Yugabyte use Percolator/Raft variants; Calvin is the purest log-deterministic
form.

Relevance to kontor: the yggdrasil composite is already Calvin-shaped — the
composite's commit DAG *is* the log, and each sub-system's state is the
deterministic replay of its slice. We don't need to implement Calvin; we
need to *recognize* that this is why the yggdrasil composite works without 2PC.

### 1.4 CRDTs

Shapiro et al., *Conflict-free Replicated Data Types*, INRIA RR-7506 (2011).
Data types where concurrent updates from any replicas converge in any order
(SEC).

Non-starter for the general accounting case: "mark invoice as posted" is a
state transition with preconditions (must be `:draft`), and the
sum-to-zero invariant is global over a transaction's posting set —
neither is a CRDT operation. They *do* fit append-only audit logs and
counter aggregates (trial-balance sum over a fixed posting set is a
G-Counter / OR-Set). Stratum's `wj` and `latest-on` are CRDT-shaped reads
over the append-only PSS.

### 1.5 Externally-ordered log — Kafka-as-truth, Debezium

Jay Kreps, *The Log: What every software engineer should know about real-time
data's unifying abstraction*, LinkedIn Engineering 2013. Kafka is the source
of truth; every database is a materialized view of the log; Debezium feeds
CDC from Postgres / MySQL into the log.

This is **exactly the shape stratum-as-secondary-index already implements** —
datahike's konserve commit-log *is* the log, stratum reads it and rebuilds a
columnar materialization, and the yggdrasil workspace HLC-indexes the cross-
system commit graph. The replikativ stack does Debezium natively because
every backend speaks konserve.

### 1.6 Single-writer with fanout

The simplest correct pattern: one DB is source of truth; secondaries
subscribe and rebuild. No cross-DB write; secondaries are stale by
definition. This is the current stratum-as-secondary-index pattern via
`d/listen` (`datahike/connector.cljc:84`) — the yggdrasil datahike adapter
installs the native listen hook (`yggdrasil/workspace.clj:41-48` is the
Watchable fallback). For kontor this is the right tradeoff: a stratum
index lag of seconds is fine; a kontor posting refusing to commit because
the stratum machine is paused is *not* fine.

### 1.7 XTDB v2 and Datomic Cloud

Both use a single-writer-on-a-log model. XTDB v2 appends to a log (Kafka or
local), the indexer consumes and builds Arrow columnar state, and "atomicity"
is "one record in the log." Cross-system writes are saga-shaped because the
log only owns XTDB's slice. Datomic Cloud is the same shape per-DB
(transactor → DynamoDB log → peer readers); Hickey's 2018 *Ions* talk
explicitly recommends "one Datomic DB per bounded context, sagas across them."

The takeaway: even the most opinionated immutable-DB vendors in the JVM
ecosystem reject 2PC and use sagas across DBs.

## 2. Concrete scenarios — kontor-flavoured

### Scenario A — kontor + stratum (secondary index)

Kontor writes a posting via `transact-with-validation`
(`validation.clj:207-225`); stratum holds a columnar projection used for
trial-balance OLAP. Today stratum runs `d/listen` on the datahike
connection — the listener fires *after* the konserve commit succeeds, so
there is no transactional join. The yggdrasil datahike adapter formalizes
this: `workspace/manage!` installs the listener; the workspace registers
the post-commit snapshot in the HLC registry (`workspace.clj:113`).

**Failure mode**: stratum process crashes after the kontor commit but
before the dataset append → stratum is stale by one tx. *No double-write
problem* because the konserve commit-log is the canonical replay log.
**Recovery**: on restart stratum reads its last-applied tx-id from its
own konserve, replays via `(d/tx-data db :since last)`. The stratum
yggdrasil adapter (`stratum/yggdrasil.clj:79-115`) exposes the
`snapshot-id`/`parent-ids` needed for this.

**2PC needed?** No. Reads against stratum carry their dataset commit-id;
strict-consistency consumers either read through datahike or wait for
stratum to catch up to a known tx-id (the HLC registry makes the wait
trivial). **Saga needed?** No — the secondary does nothing externally
visible during its update. A schema-mismatch failure is a *data* bug, not
a distributed-tx problem.

### Scenario B — Multi-instance kontor intercompany

DE GmbH (parent) issues an intercompany invoice to a US LLC (subsidiary).
DE books a receivable (`+100 EUR`); US books a payable (`-100 USD` after
fx). **Separate datahike instances** — separate konserve stores, separate
audit chains, separate access controls (the DE accountant cannot see US
books; the US auditor must not depend on DE's audit-chain).

**Cross-DB invariant**: at consolidation, `receivable + payable +
fx-difference = 0`. Neither DB can enforce this alone.

**Today**: not implemented. Kontor's `:entity` model (`entity.clj:1-45`)
covers multi-entity *within one DB* (simpler case used for consolidated
reporting from one books). The two-DB shape is the real intercompany case.

**Failure mode**: half-completes. DE books the receivable; US crashes
before booking the payable. Each side is *individually* balanced; the
consolidation invariant is broken.

**Recovery story**: kontor already has the primitive — write a
`:side-effect-intent` row (ADR-041, `side_effect.clj`) on the DE side
*in the same tx as the receivable* with
`:side-effect-intent/type :intercompany-post` and a target payload. A
worker polls the DE side, executes the US-side tx idempotently (keyed by
the intent's `:side-effect-intent/key`), marks `:done`. On the DE side a
reverse-direction ack intent is written when the US side acknowledges.

**2PC needed?** Only if "DE fails iff US fails" must be guaranteed
atomically — auditors do not require this for *posted* intercompany
invoices. They require: (a) DE can name the US posting eventually,
(b) the US posting eventually exists, (c) the gap is auditable. The
intent-row pattern satisfies all three. **Saga is the right shape** —
this is literally Garcia-Molina 1987's bank-transfer example, and
the compensator (if US fails permanently) is a reverse posting on
the DE side which kontor already supports via ADR-008.

### Scenario C — kontor + beleg as separate consumers

Per ADR-002, beleg's `:invoice/* :customer/* :offer/*` and kontor's
`:account/* :posting/* :journal/*` share *one* datahike connection.
Posting an invoice is a single atomic transaction —
`kontor.process/run-process` (`process.clj:110-138`) is the orchestrator,
ADR-068's per-namespace `*-tx-data` builders compose cleanly:
`(beleg.invoice/send-tx-data ...)` + `(kontor.posting/build-transaction-tx-data
...)` glued by `run-process`. **2PC/saga both irrelevant** — single
konserve commit, single audit chain.

If they split into separate DBs (legal-separation hypothetical, e.g.
beleg-as-SaaS), this collapses to Scenario B with a different invariant:
side-effect-intent + content-hash idempotency. **Headline**: ADR-002 is
right precisely because it makes Scenario C trivial; splitting is an
opt-out for specific deployments, not a kernel concern.

### Scenario D — distributed audit chain

A posting references an audit-doc (scanned receipt PDF). The PDF lives in
S3 or in scriptum; the kontor entity is the reference + content-hash + URI
per `audit_doc.clj:1-30`.

**Cross-DB invariant**: every `:posting/audit-doc` must eventually be
readable; bytes must hash to the recorded content-hash; the bytes must
remain reachable for the regulatory retention period (DE GoBD 10y,
IRS 7y).

**Failure modes today**: (1) kontor commits, S3 upload fails — entity
points at a 404. (2) Upload succeeds, then a different process deletes
the object before retention expires. (3) Retention worker deletes a
still-referenced doc. The kernel's `legal_hold.clj` enforces kontor-
side hold; nothing prevents the underlying bytes from disappearing.

**This is the scenario the maintainer should care about most.** Where
yggdrasil's composite shines: kontor + scriptum (or s3-via-konserve) as
sub-systems of one `yggdrasil.composite` over one konserve gives (a)
atomic (kontor-tx, scriptum-doc-version) pairs via `multi-assoc`, (b)
composite GC walks both systems' branch heads — a scriptum doc
referenced by any kontor posting on any branch is protected
(`yggdrasil/README.md:809-824`), (c) retention becomes a workspace
property. The pattern: **the kernel models the reference + hash; the
cross-DB coordinator owns reachability**. Kontor doesn't need to know
how scriptum or S3 work — it writes the audit-doc commit-id alongside
its tx-data and lets yggdrasil own the rest.

### Scenario E — kontor + scriptum + proximum + simmis (knowledge graph)

Simmis is a categorical schema substrate; objects include `S/Invoice`,
`S/Posting`, `S/AuditDoc`. Postings live in kontor; invoice/contract text
in scriptum; customer embeddings in proximum; the schema-of-the-schema
in simmis-datahike. A "approve invoice and tag billable to project P"
action touches all four; simmis' UI orchestrates.

**Failure mode**: same as other multi-DB scenarios — half-completion.

**Why yggdrasil is the answer**: the four systems share a konserve
(or compose stores via `konserve/tiered.cljc`) and are managed as a
`yggdrasil.composite` (`yggdrasil/README.md:610-695`). One
`(p/commit! composite ...)` commits all four. This is the only
scenario where the cross-system primitive must be *general* — and
yggdrasil already provides it.

## 3. What kontor + siblings already have

### 3.1 kontor side

**`kontor.process/run-process`** (`process.clj:110-138`). Orchestrates a
step-list into one atomic transaction under `(locking conn ...)`,
commits via `transact-with-validation`. **The kontor analog of an
orchestrated saga, single-DB.** Could generalize to multi-DB *in
principle* but shouldn't — its value is that the whole sequence is *one*
`d/transact`, free single-konserve atomicity. Cross-store coordination
needs something strictly heavier than `locking conn` — yggdrasil.

**`kontor.validation/transact-with-validation`** (`validation.clj:207-225`).
The universal write gate. **Should not become a cross-DB coordinator**:
validation is a property of one commit, cross-DB coordination is a
property of a set of commits. The right composition is yggdrasil-on-the-
outside calling `transact-with-validation` per sub-system.

**`kontor.side-effect`** (`side_effect.clj`, ADR-041). A
`:side-effect-intent` row is written in the *same tx* as the triggering
state change; a worker drains pending intents idempotently. **This is
already the saga primitive.** The intent's `:key` is the saga-step id;
`:status` is the step state (`:pending → :in-progress → :done` /
`:failed → :compensating → :compensated`); `:type` dispatches the
executor. Currently scoped to kontor-internal side-effects (email, EDI)
but the shape is right for cross-DB writes.

**`kontor.audit-doc`** (`audit_doc.clj`). Reference + content-hash + URI
to an externally-stored document. Kernel doesn't enforce URI reachability
(ADR-038). The kontor side of the cross-DB audit-doc shape; missing piece
is the coordinator side (§5).

### 3.2 datahike side

**`konserve.core/multi-assoc`** (`konserve/core.cljc:301-327`). Atomic
multi-key write within *one* konserve store. Backed by `PMultiWritable` on
backends that implement it (`konserve/utils.cljc` — `multi-key-capable?`).
Used today by datahike's `commit!` to atomically write `{cid db-state,
branch db-state, pending-kvs}` (`writing.cljc:320-326`). This **is** the
single-store cross-DB primitive — kontor + stratum + scriptum sharing one
konserve get atomic multi-system commits *for free* via this.

**`datahike/versioning.cljc:branch!`** (`versioning.cljc:190` uses
`multi-assoc`). Datahike's native branch primitive. A branch is a
named pointer in the konserve store; branching is structural-sharing-cheap.
Yggdrasil's datahike adapter wraps this
(`yggdrasil/src/yggdrasil/adapters/datahike.clj:128-149`).

**`d/listen`** (`connector.cljc:84` — `listeners (atom {})`). The
post-commit hook stratum already uses for secondary-index updates.

### 3.3 yggdrasil side — the whole pattern is already there

Most of the work this study would *propose* already exists in yggdrasil
as of mid-2026. The relevant pieces:

- **`yggdrasil.composite/pullback`** (`composite.clj`, README:610-695) —
  wraps N sub-systems into one unit with componentwise
  `branch!`/`commit!`/`merge!`. Categorically a fiber product over the
  shared branch space. **Right primitive for Scenario E.**
- **`yggdrasil.workspace/coordinated-commit!`**
  (`workspace.clj:216-240`) — pins a shared HLC, runs each system's
  `commit-fn`, captures per-system `{:results :errors}`. Partial-failure
  aware. **Right primitive for Scenarios A, B, D** (loose coupling +
  HLC correlation).
- **`yggdrasil.compose/commit-seq!`** (`compose.cljc:26-52`) —
  overlay-as-prepare-then-commit; each overlay merged-down in sequence,
  remaining discarded on failure. **The saga executor at the yggdrasil
  layer** — 2PC-style preparation with saga "discard on failure"
  semantics rather than 2PC "block on coordinator".
- **Adapters**: `yggdrasil.adapters.datahike` installs `d/listen` commit
  hooks via `hooks/install-commit-hook!`. `stratum.yggdrasil`,
  `scriptum.yggdrasil`, `proximum.yggdrasil` all implement the protocol
  stack (scriptum passes the 22-test compliance suite).

### 3.4 Composite GC and cross-system audit

Yggdrasil's `gc.clj` runs mark-and-sweep across all managed systems
under a workspace: a snapshot referenced by any system protects the
shared konserve objects. That's the cross-system GC coordination this
study would otherwise need to invent.

Stratum's `IAuditable` (`stratum/audit.clj:38-53`) is **deliberately
identical to datahike's** `datahike.index.audit/IAuditable`; the
result-map vocabulary (`{:status :ok|:mismatch|:advisory|:incomplete}`)
is shared so bridges pass results through without translation. A kontor
audit walk can therefore call `verify-chain` against every IAuditable
system in a workspace and aggregate without per-system glue.

## 4. Sibling project landscape

Briefly, ground-truth from each project's docstring or README. All five
sibling projects exist on disk at the expected paths.

- **`stratum`** — SIMD-accelerated SQL engine over columnar copy-on-write
  data, Apache 2.0. Persistent storage is konserve. `stratum.yggdrasil`
  already implements snapshotable/branchable/graphable. `stratum.audit`
  shares vocabulary with datahike (`audit.clj:16-20`). *Role*: columnar
  secondary index (trial-balance OLAP, ASOF reconciliation, time-series
  aggregates) plus the PG-wire surface for BI consumers.
- **`scriptum`** — Copy-on-write branching over Apache Lucene. Apache 2.0.
  Optional SHA-512 merkle commit hashing (`scriptum/README.md:17-27`) —
  directly relevant to ADR-007 audit semantics. Yggdrasil adapter passes
  the full 22-test compliance suite. *Role*: full-text-searchable
  audit-doc store, invoice/contract text, vendor-document indexing.
- **`yggdrasil`** — Unified copy-on-write protocols across heterogeneous
  storage. Apache 2.0. Twelve adapters (Git, ZFS, Btrfs, IPFS, Iceberg,
  Datahike, Scriptum, OverlayFS, Podman, LakeFS, Dolt, Composite).
  HLC-coordinated multi-system commits, persistent registry,
  mark-and-sweep GC. **The cross-DB coordination layer for the ecosystem.**
- **`proximum`** — Embeddable vector DB with Git-like versioning. Apache
  2.0. HNSW + structural sharing. Yggdrasil-compatible. *Role*: semantic
  search over customer/contract embeddings, "find similar expense
  receipts" for fraud detection. Less hot-path than stratum.
- **`simmis`** — Categorical KM substrate. Backend Clojure + datahike,
  frontend ClojureScript + UIx, Missionary for FRP. Stores its own
  schema as datahike entities. **Long-term, kontor's
  `:posting`/`:account` will appear in simmis as schema-objects** (note
  54). *Role*: top-of-stack consumer that wants kontor to participate in
  a workspace alongside its own datahike + scriptum + proximum.
- **`spindel`** — Incremental reactive computation. Not a storage system,
  not a yggdrasil adapter. The compute engine simmis builds on. Cited
  by `yggdrasil/compose.cljc:1-7` as the orchestrating runtime that
  decides ordering policy. Not a direct cross-DB-tx participant.

**The maintainer's vision, legible in the source**: kontor is the
double-entry kernel, stratum the columnar accelerator, scriptum +
proximum handle text + vector search, simmis the categorical UI layer,
yggdrasil binds them, spindel reactively re-derives. The cross-DB story
is *yggdrasil's job*; the work is mostly already done — what's missing
is kontor's idiomatic participation.

## 5. Proposed primitive(s)

Five concrete pieces. Two new kontor namespaces, three thin adapters.

### 5.1 `kontor.cross-tx` — yggdrasil-aware accounting writes

A new kontor namespace, ~150 lines. Three surface functions:

- `(transact! conn tx-data)` — single-DB equivalent of
  `transact-with-validation`. Provided for API uniformity so callers do
  not branch on "is this cross-DB?".
- `(participate! conn tx-data)` — returns a yggdrasil-shaped commit-fn
  for `workspace/coordinated-commit!`. Calls `transact-with-validation`
  under the workspace's pinned HLC, returns the resulting commit-id as
  the yggdrasil snapshot-id. Throws on validation failure — the
  workspace records the per-system error and proceeds.
- `(book-intercompany! pair)` — atomic-with-saga-fallback intercompany
  posting. If both `:debit-conn` and `:credit-conn` share a konserve
  store, route through `konserve/multi-assoc` in one transactor
  invocation. If they don't share a store, fall back to a saga: write a
  `:side-effect-intent` on the debit side in the same tx as the debit
  posting; a worker on the credit side polls, applies idempotently,
  acks. `pair` carries `:compensate-tx-data` for the
  exhausted-retry case (reverse the debit posting via ADR-008).

The namespace adapts kontor's `transact-with-validation` to yggdrasil's
existing shapes; it does not invent coordination.

### 5.2 `kontor.side-effect.cross` — generalize the intent drain to cross-DB

The existing `kontor.side-effect` ships intent rows with status-machine
semantics (`side_effect.clj`). ADR-041 is the ADR. The proposed generalization:

- An intent with `:side-effect-intent/type :cross-tx-post` carries
  `:target/system-id`, `:target/branch`, `:target/tx-data` as the saga
  step's *payload*.
- The intent's `:side-effect-intent/key` becomes the **saga-step idempotency
  key**. Workers MUST hash the target tx-data + the source intent's key
  into a deterministic step id (recommended: hasch UUID of the pair); the
  worker MUST check before transacting whether that step id is already
  marked `:done` for the target system.
- Compensation: if a step retries to exhaustion (`:side-effect-intent/attempts`
  exceeds a configurable budget), the intent transitions to `:failed`
  and the worker emits the *compensator* intent on the source side —
  which kontor consumers implement as a reversing posting.

The new namespace `kontor.side-effect.cross` is ~80 lines: it provides the
hash-keyed idempotency helper, the cross-DB executor protocol, and the
"failed → compensate" transition. The existing `:side-effect-intent` schema
is unchanged.

This namespace is **the saga implementation for kontor**. It doesn't try
to compete with Temporal or AWS Step Functions — it does the minimum
needed for "one accounting event spans two kontor DBs" and "one accounting
event triggers an audit-doc upload to scriptum/s3".

### 5.3 Promote `datahike.api/multi-transact!`

Per research note 55 §7 tier 1. `konserve/multi-assoc` already powers
datahike's own commit (`writing.cljc:320-326`); exposing
`datahike.api/multi-transact!` makes the single-konserve-store cross-DB
case `(d/multi-transact! [[conn-kontor tx-1] [conn-scriptum tx-2]
[conn-stratum tx-3]])` into one atomic call. Implementation: collect each
conn's would-be tx-data post-validation, route through one `multi-assoc`
against the shared konserve, return per-conn `tx-report`s.

Datahike-side change, not kontor; listed here because it is the
foundation for §5.1 and §5.4.

### 5.4 Kontor as a yggdrasil sub-system (adapter, not namespace)

Currently the yggdrasil datahike adapter wraps a raw datahike connection
(`yggdrasil/src/yggdrasil/adapters/datahike.clj`). For most cases that's
fine — kontor *is* a datahike connection.

The opt-in extension: a `kontor.yggdrasil` adapter that *augments* the
datahike system with kontor-specific capabilities:

- `gc-roots` includes audit-doc-referenced snapshots so a workspace GC
  won't sweep them.
- `system-meta` exposes `:kontor/sealed-at`, `:kontor/period-locked-at`
  so a workspace can refuse to delete branches whose head is sealed.
- `system-type :kontor` for hooks to dispatch on.

This is ~100 lines of `extend-protocol`; it does not change kontor's
core API.

### 5.5 Audit-chain bridge for cross-system verification

Stratum already shares the `IAuditable` vocabulary with datahike
(`stratum/audit.clj:16-20`). The analogous shape for scriptum is its
`verify-commit` API (`scriptum/README.md:84-99`). The proposed bridge:

```clojure
(ns kontor.audit-chain
  "Cross-system audit-chain verification.")

(defn verify-workspace!
  "Walk every IAuditable system in a workspace; aggregate results.
   Returns {sid -> result-map} plus a :status across the union.
   A workspace is :ok iff every sub-system is :ok or :advisory."
  [workspace] ...)
```

This is purely a *bridge* — it doesn't add storage or invariants; it
calls the existing per-system `verify-chain`/`verify-commit`/etc and
unifies the output. ~50 lines.

---

**What these proposals reject**:

- *A generic 2PC coordinator in kontor.* Wrong layer. If it's needed
  anywhere it's yggdrasil-level, but the evidence says sagas + the
  konserve-shared-store atomic path cover the actual scenarios.
- *A workflow DSL in kontor.* `kontor.process` is the orchestrator for
  single-DB; `kontor.side-effect.cross` is the orchestrator for
  cross-DB. Together they are sufficient. Anything bigger is a Temporal/
  Camunda concern that belongs in a consumer.
- *Tighter coupling between kontor and stratum.* The single-writer-with-
  fanout pattern is right for stratum-as-secondary-index; the audit
  chain catches divergence. Pulling stratum into kontor's transaction
  boundary trades the wrong axis.
- *Cross-store XA on Postgres.* Could work via shared JDBC tx + konserve-
  jdbc (research note 55 §7 spelled out the design). But: (a) konserve-
  jdbc would need to learn external-transaction support, (b) the
  operational story for hung prepared txes is heavy, (c) the deployments
  that would benefit are few. Defer until a real customer asks.

## 6. Adoption story

### 6.1 What changes for kontor consumers

**Most don't notice.** ADR-002 keeps beleg + kontor in one DB; the
default write path stays `(kontor.validation/transact-with-validation
conn tx-data)` or `(kontor.process/run-process conn {:steps ...})`.
Unchanged.

**Stratum-as-secondary-index consumers** keep using `d/listen` or the
yggdrasil datahike adapter's hooks; nothing breaks. They optionally
gain `kontor.audit-chain/verify-workspace!` and the workspace's
HLC registry for cross-system point-in-time queries.

**Multi-entity intercompany consumers** (DE GmbH + US LLC in separate
datahike) gain `kontor.cross-tx/book-intercompany!`. They lose nothing
— there's no current API for this case.

**kontor + scriptum + proximum + simmis consumers** (currently simmis
itself) set up a yggdrasil workspace with all four systems managed and
use `workspace/coordinated-commit!` with `kontor.cross-tx/participate!`
for the kontor slice. Wholly additive.

### 6.2 What changes for `kontor.process`

`kontor.process/run-process` is **kept as-is** — the right abstraction
for single-DB multi-step (the bulk of kontor's orchestration). Making
it span DBs would require a workspace argument and per-sub-system
dispatch, which collapses to the yggdrasil composite anyway. Correct
composition is **outer**: a yggdrasil workspace includes a `run-process`
invocation as the kontor slice of a `coordinated-commit!`:

```clojure
(workspace/coordinated-commit! ws
  {"kontor"   (fn [_] (kontor.process/run-process conn {:steps [...]})
                      (str (-> conn d/db :meta :datahike/commit-id)))
   "scriptum" (fn [sys] (scriptum.core/add-doc! sys {...})
                        (scriptum.core/commit! sys "audit-doc"))
   "stratum"  (fn [_] (stratum.api/sync! ds) ...)})
```

The HLC pins all three snapshot-ids in the workspace registry; the
composite history walks them as a unit.

### 6.3 ADR + upstream changes

- ADR-067 (`kontor.process`) unchanged. ADR-068 (`*-tx-data` builders)
  unchanged — these builders become the participating units in cross-DB
  writes too.
- ADR-041 (`:side-effect-intent`) gets a follow-up ADR formalizing
  cross-DB saga semantics on the existing schema.
- New ADR for `kontor.cross-tx` documents the yggdrasil adapter and
  the intercompany helper.
- Upstream: **datahike** promotes `multi-assoc` to
  `datahike.api/multi-transact!` (~1 day; mechanism already exists).
  **konserve** + **yggdrasil** require no protocol changes.

## 7. Open questions

- **Does `kontor.cross-tx/book-intercompany!` need a third strategy for
  the konserve-jdbc-on-shared-Postgres case?** Theoretically yes — a
  shared JDBC tx through both kontor connections is genuinely atomic
  without sagas. Practically, this requires konserve-jdbc to expose
  external-tx support, which it does not as of 2026-05. Defer until a
  customer with two kontor DBs on one Postgres asks. The saga fallback
  is correct meanwhile.

- **How does `:side-effect-intent` interact with kontor's bitemporal
  axis?** An intent written today (`:db/txInstant` = now) for a posting
  with `:tx/valid-from` in the future creates an unusual shape: the
  intent's *target* posting is back-dated, but the intent itself is
  current. Probably correct (the intent is a fact about now), but
  needs a test case. ADR-041 didn't think about this because it didn't
  span DBs.

- **Stratum's `verify-chain` is layer-1 + layer-2 (PSS-tree walk);
  scriptum's `verify-commit` is at the commit-file level.** Are these
  equally strong for a kontor audit? Probably yes — both detect bytes-
  level tampering — but the aggregate `kontor.audit-chain/verify-
  workspace!` should explicitly document its weakest link, which is
  scriptum's segment-file hashing.

- **Workspace GC and audit-doc retention.** Yggdrasil's mark-and-sweep
  protects any snapshot referenced by any system's branch head. But
  regulatory retention requires keeping snapshots *referenced by a
  posting* alive for N years even if no current branch head references
  them. A `kontor.retention/protect-historical!` hook into
  workspace GC would write GC-protection entries for every audit-doc-
  hash referenced by any sealed posting. This is the open design call
  for ADR-072 candidate.

- **Should `kontor.side-effect.cross`'s saga compensator be
  automatic or manual?** Automatic means: "after N retries, the
  worker writes the compensating reverse posting." Manual means:
  "the worker marks the intent `:failed` and a human reviews." For
  accounting, *manual* is almost certainly right — auto-compensating
  a stuck intercompany posting could mask a real bug (e.g. the US
  side is paused for end-of-year close, not crashed). The retry
  budget should be conservative and the failed-intent surface should
  be a first-class operator UX, not a silent log line.

- **HLC restoration across kontor + yggdrasil restarts**
  (`yggdrasil/workspace.clj:59-70`) — does kontor's `:tx/txInstant`
  need to be HLC-derived? Probably not — HLC is a *write-ordering*
  clock; `:tx/txInstant` is the human-readable wall-clock-ish stamp,
  and the konserve commit-id is already the monotonic-causal-id.
  But it might be worth threading the workspace's HLC into the
  kontor tx-meta as a *separate* attribute (`:tx/hlc-physical`,
  `:tx/hlc-logical`) for cross-system audit queries. Open design call.

- **What about the kontor + spindel reactive case?** If a simmis screen
  is a reactive computation over kontor postings via spindel, and a
  kontor write triggers a re-derivation, is spindel a participant in
  the cross-DB transaction? Probably no — spindel is read-side, not
  write-side, and its re-derivation is post-commit by construction.
  But the question becomes interesting if spindel writes *back*
  (e.g. a derived aggregate stored in proximum for fast similarity
  search). That's a follow-up.

## Sources

### Kontor

- `src/kontor/process.clj:1-138` — `kontor.process/run-process`, single-DB orchestrator.
- `src/kontor/validation.clj:167-225` — `transact-with-validation`, the universal write gate.
- `src/kontor/side_effect.clj:1-60` — `:side-effect-intent` schema (ADR-041).
- `src/kontor/audit_doc.clj:1-30` — `audit-doc` reference + content-hash model (ADR-038).
- `src/kontor/entity.clj:1-45` — multi-entity model within one DB (ADR-031).
- `src/kontor/sealing.clj` — `:posting/posted-at` no-silent-retract (ADR-007).
- `doc/decisions.md:21-31` — ADR-002 beleg cohabitation in one DB.
- `doc/decisions.md:7149-7305` — ADR-067 `kontor.process`.
- `doc/decisions.md:7307-7400` — ADR-068 every-write-tx-data-builder.
- `doc/decisions.md:3541-3771` — ADR-041 workflow + side-effect-intent.
- `doc/research/55-bitemporal-cross-db-survey.md:310-388` — predecessor analysis of cross-DB transact (this study is the implementation-detail follow-up).
- `doc/research/46-kontor-process-implementation-survey.md` — context for `run-process`.
- `doc/research/53-kontor-v2-consolidation.md` — kontor + sibling consolidation context.

### Datahike + konserve

- `src/datahike/writing.cljc:300-346` — `commit!` using `k/multi-assoc` for atomic multi-key writes.
- `src/datahike/versioning.cljc:190` — `branch!` via `multi-assoc`.
- `src/datahike/connector.cljc:43-84` — `Connection` + `:listeners` (the `d/listen` machinery).
- `src/konserve/core.cljc:279-340` — `multi-assoc`/`multi-get`/`multi-dissoc`.
- `src/konserve/protocols.cljc` — `PMultiWritable` protocol.
- `src/konserve/utils.cljc` — `multi-key-capable?`.
- `src/konserve/tiered.cljc:47-368` — tiered multi-assoc semantics across frontend/backend.

### Yggdrasil

- `README.md:1-922` — full feature surface, protocol stack, adapter matrix.
- `src/yggdrasil/protocols.cljc` — Snapshotable/Branchable/Graphable/Mergeable/Overlayable/Watchable/GarbageCollectable.
- `src/yggdrasil/workspace.clj:216-240` — `coordinated-commit!` with shared HLC.
- `src/yggdrasil/workspace.clj:41-48` — default Watchable commit-hook fallback.
- `src/yggdrasil/compose.cljc:1-74` — `prepare-all`, `commit-seq!` (overlay-as-prepare, saga-shaped).
- `src/yggdrasil/composite.clj` — `pullback` / `composite` constructors.
- `src/yggdrasil/registry.clj` — HLC-indexed PSS snapshot registry on konserve.
- `src/yggdrasil/gc.clj` — cross-system mark-and-sweep.
- `src/yggdrasil/adapters/datahike.clj:128-149` — datahike branch! / commit! adapter.
- `docs/CONSISTENCY.md` — Parallel Snapshot Isolation (PSI) semantics.
- `docs/CATEGORICAL_SEMANTICS.md` — pullback/pushout treatment of composite vs merge.

### Stratum + scriptum + proximum + simmis

- `stratum/src/stratum/audit.clj:1-396` — `IAuditable` shape; deliberately identical to datahike's.
- `stratum/src/stratum/yggdrasil.clj:1-120` — StratumSystem yggdrasil adapter.
- `stratum/README.md:248-258` — replikativ ecosystem positioning.
- `scriptum/README.md:17-27` — optional SHA-512 merkle hashing.
- `scriptum/README.md:429-450` — yggdrasil protocol-stack implementation.
- `proximum/README.md:36-95` — branching + commit-id + immutable model.
- `simmis/README.md:1-100` — categorical schema substrate, datahike + Kabel + Missionary stack.

### External — survey papers and prior art

- Garcia-Molina, Salem. *Sagas*. ACM SIGMOD 1987, pp. 249-259. [https://dl.acm.org/doi/10.1145/38713.38742](https://dl.acm.org/doi/10.1145/38713.38742)
- Gray, J. *Notes on Database Operating Systems*. IBM Research Report RJ2188, 1978 — origin of 2PC.
- X/Open. *Distributed Transaction Processing: The XA Specification*. 1991.
- Pat Helland. *Life Beyond Distributed Transactions: An Apostate's Opinion*. CIDR 2007. [https://www.ics.uci.edu/~cs223/papers/cidr07p15.pdf](https://www.ics.uci.edu/~cs223/papers/cidr07p15.pdf)
- Ren, Thomson, Abadi. *Calvin: Fast Distributed Transactions for Partitioned Database Systems*. SIGMOD 2012. [http://cs.yale.edu/homes/thomson/publications/calvin-sigmod12.pdf](http://cs.yale.edu/homes/thomson/publications/calvin-sigmod12.pdf)
- Shapiro, Preguiça, Baquero, Zawirski. *Conflict-free Replicated Data Types*. INRIA RR-7506, 2011.
- Jay Kreps. *The Log: What every software engineer should know about real-time data's unifying abstraction*. LinkedIn Engineering Blog, 2013. [https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying](https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying)
- Chris Richardson. *Microservices Patterns* (Manning, 2018) — orchestrated vs choreographed sagas.
- Sam Newman. *Building Microservices* (O'Reilly, 2nd ed. 2021) — saga + outbox patterns.
- Confluent. *Transactional Outbox Pattern*. [https://www.confluent.io/blog/dual-write-problem/](https://www.confluent.io/blog/dual-write-problem/)
- Rich Hickey. *Datomic Ions* (2018), Strange Loop talk — single-writer-with-fanout pattern; "sagas across DBs" recommendation.
- XTDB v2. [https://docs.xtdb.com](https://docs.xtdb.com) — log-based transactor as the only ordering mechanism.

### JVM 2PC implementations

- Atomikos TransactionsEssentials. [https://www.atomikos.com](https://www.atomikos.com) — Apache 2.0 core since 2024.
- Narayana (JBoss / Red Hat). [https://narayana.io](https://narayana.io) — LGPLv2.1, JTA / JTS.
- Bitronix BTM. [https://github.com/bitronix/btm](https://github.com/bitronix/btm) — Apache 2.0, unmaintained since ~2014.
- Postgres `PREPARE TRANSACTION` docs. [https://www.postgresql.org/docs/current/sql-prepare-transaction.html](https://www.postgresql.org/docs/current/sql-prepare-transaction.html)

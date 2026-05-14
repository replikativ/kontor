# 44 — Composing application logic into atomic transactions: a field survey

**Date:** 2026-05-14
**Method:** breadth-first survey of prior art across databases and
distributed systems — Datomic, XTDB v1/v2, SQL/PL-pgSQL + SSI, the
OCC/MVCC literature, the Saga pattern, event-sourcing command
handlers, the immutable-datalog family (Datascript, Materialize), and
single-writer runtimes (Disruptor, Redis). Sources are docs, the
foundational papers, and vendor blogs — all linked inline.
**This is the field-survey companion to note 42.** Note 42 did the
*internal* analysis (kontor's three inconsistent state models, the
non-atomic runners, the `kontor.process` proposal). This note
contextualizes that proposal against what the rest of the field
actually does, so the eventual `kontor.process` ADR can cite
precedent rather than re-derive it.

## The concrete decision being contextualized

datahike needs a consistent programming model for multi-step
transactions. Three candidate models (from note 42 + the maintainer's
empirical datahike probing):

- **(A)** compute outside the writer against a snapshot, thread
  `d/db-with`, commit once — fast, but optimistic (a concurrent
  commit between snapshot and commit can invalidate the compute).
- **(B)** the whole multi-step compute runs *inside* one transaction
  function (`[:db.fn/call f args]`) — atomic + serializable, but the
  compute blocks the single writer.
- **(C)** optimistic compute-outside + an in-transaction re-check
  (compare-and-swap) at commit, with retry.

datahike facts established empirically: `d/transact` is synchronous,
single-writer, serialized; `[:db.fn/call f args]` runs *inside* the
tx at apply-time, **sees earlier items in the same tx** (including
prior tx-fns' output), can return nested tx-fns, aborts the whole tx
on throw, but **reentrant `d/transact` deadlocks**; `d/db-with` is a
pure speculative `db → db`; no SQL `BEGIN…END`, no built-in CAS.

---

## 1. Datomic transaction functions — the closest cousin

Datomic's `:db/fn` is "a pure function `[db-before, args] -> tx-data`"
([Datomic docs][datomic-txfn]). It runs **inside the serialized
pipeline of transactions** on the transactor. The rules are strict
and worth quoting: functions "must be pure functions, free of side
effects"; they take `db-before` (the database *as of transaction
start*); on success they "return valid transaction data (which can
include more transaction functions!)". Aborting is done by calling
`cancel` with an anomaly, which throws to the caller.

The decisive divergence from datahike: **Datomic transaction
functions "do not see each other's return value."** They each receive
`db-before` and only their own args — never the in-flight db, never a
prior tx-fn's output. The docs are explicit that this is *by design*:
"This design preserves Datomic's declarative semantics by preventing
order-dependent composition between functions." Composition in
Datomic is therefore *flat* — a tx is an unordered set of assertions
+ independent tx-fn calls, each resolved against the same starting
point.

Operational warnings: a slow tx-fn "impact[s] all queued
transactions," so they "should do the minimal amount of work
possible, and should do only work that requires access to the
in-transaction value of the database." On Datomic Cloud the function
is an *ion* sharing the JVM classpath with Datomic itself — same
"runs on the transactor, keep it small, no reentrant transact"
discipline ([Datomic Cloud ions][datomic-ions]).

**This is exactly option (B), and Datomic's accumulated wisdom about
(B) is: keep the in-transaction compute minimal.** Datomic's answer
to "where does the heavy compute go" is: *outside*, against a regular
db value, producing tx-data — and only the part that genuinely needs
the in-transaction db goes in the tx-fn.

## 2. datahike's `:db.fn/call` vs Datomic — a meaningful difference

datahike's `[:db.fn/call f args]` is the same surface but **not the
same semantics**. The maintainer confirmed empirically that datahike's
tx-fn *sees earlier items in the same tx*, including prior tx-fns'
output, and can return nested tx-fns that also see the accumulating
state. That is the **Datascript** semantics, not the Datomic
semantics: in Datascript "the db that [the] function receive[s] is a
'partial db' relative to its position in transaction" ([Datascript
internals][datascript]). datahike inherits Datascript's transactor.

So datahike's tx-fn is *more* expressive than Datomic's — it can do
ordered, db-threaded composition *inside* the transaction. That is
precisely what makes option (B) viable in datahike where it would be
impossible in Datomic. But it inherits the same cost (blocks the
single writer) and the same hard limit (**no reentrant transact** —
Datomic forbids it by doctrine, datahike enforces it by deadlock).

## 3. XTDB — match as built-in compare-and-swap

**XTDB v1** has four primitive ops: `put`, `delete`, `match`, `evict`
([XTDB v1 datalog transactions][xtdb1-tx]). `match` "checks the
current state of an entity — if the entity doesn't match the provided
doc, the transaction will not continue"; if the supplied doc is
`nil`, match passes only if no document with that id exists. **`match`
is a built-in compare-and-swap.** All match ops in a tx must pass for
the tx to be indexed. XTDB v1 also has transaction functions
(installed as documents, invoked via `::xt/fn`) that receive a db
context, "can only return new transaction operations," and roll the
whole tx back on `false`/throw — "conceptually similar to stored
procedures" ([XTDB tx-fn blog][xtdb1-txfn]). The blog frames the
choice exactly as our (A) vs (C): use a tx-fn when you need
read-then-write of the *latest* entity; `match` is the lighter-weight
optimistic check when you can express the precondition as a value.

**XTDB v2** abandoned the document-op model for SQL ([XTDB v2 SQL
txs][xtdb2-tx]). Transactions are `BEGIN`/`COMMIT`/`ROLLBACK` blocks,
`READ ONLY` or `READ WRITE`. DML is `INSERT`/`UPDATE`/`PATCH`/
`DELETE`/`ERASE`. The CAS primitive is now **`ASSERT`**: "Rolls back
the transaction if the provided predicate is false" — the canonical
example is asserting no duplicate email before insert. v2 keeps the
serial-transaction-log model (a tx is still effectively one
document/message on the log) but gives it SQL's multi-statement
composition. Notably v2 **dropped general transaction functions** in
favor of SQL DML + `ASSERT` — a deliberate move from "arbitrary code
inside the tx" toward "declarative statements + a precondition check."

**Takeaway for kontor:** XTDB independently arrived at the
`match`/`ASSERT` primitive — a *declarative precondition checked at
apply-time inside the serialized log*. That is the spine of option
(C), and XTDB's v1→v2 evolution is a vote *against* unconstrained
in-transaction code and *for* "compose declaratively, guard with an
assertion."

## 4. SQL stored procedures, isolation levels, and SSI

The classic "logic inside the transaction" is the stored procedure /
PL-pgSQL block inside `BEGIN…COMMIT`. The interesting part is not the
procedure mechanism but the **isolation level**, because that is
where the field's optimistic-vs-pessimistic wisdom is codified
([Postgres isolation docs][pg-iso]):

- **READ COMMITTED** (Postgres default): every statement gets a fresh
  snapshot. Multi-step logic can see concurrent commits *mid-
  transaction* — non-repeatable reads, phantoms. This is the trap
  equivalent of kontor's "transact-per-step" runners.
- **REPEATABLE READ**: one snapshot for the whole transaction (taken
  at the first statement). On a write/write conflict it raises
  `could not serialize access due to concurrent update` and the
  "[a]pplications using this level must be prepared to retry." This
  is **option (A) with a safety net** — snapshot isolation, first-
  committer-wins, caller retries.
- **SERIALIZABLE (SSI)**: "emulates serial transaction execution for
  all committed transactions." Postgres implements **Serializable
  Snapshot Isolation** via *predicate locks* (`SIReadLock`) that
  "allow it to determine when a write would have had an impact on the
  result of a previous read from a concurrent transaction, had it run
  first." Crucially these locks **"do not cause any blocking"** — SSI
  is *optimistic*: it lets transactions run on their snapshots and
  *detects* the dangerous read/write dependency structure at commit,
  aborting one with `SQLSTATE 40001`. Cost: monitoring overhead +
  predicate-lock memory + the caller *must* retry on 40001.

SSI's headline guarantee is the one kontor should care about: "if you
can demonstrate that a single transaction, as written, will do the
right thing when run by itself, you can have confidence that it will
do the right thing in any mix of Serializable transactions." That is
*compositional* correctness — the whole point of wanting a
`kontor.process` abstraction.

Pessimistic alternatives in the same family: `SELECT … FOR UPDATE`
(row locks, can deadlock), advisory locks (app-level mutexes). SSI's
pitch over these is exactly Postgres's: predicate locks don't block,
so no deadlock — you pay in retries instead.

## 5. OCC and MVCC — the formal model

Kung & Robinson's 1981 "On Optimistic Methods for Concurrency
Control" ([Kung-Robinson][kung-robinson]) is the foundational paper.
The model: every transaction runs in three phases — **read** (work in
a private workspace, accumulate a *read-set* and *write-set*),
**validation** (check serializability against concurrently-committed
transactions), **write** (apply if validation passed, else abort and
restart). "Optimistic" means it "rel[ies] mainly on transaction
backup as a control mechanism, 'hoping' that conflicts between
transactions will not occur." Backward validation: at commit, check
whether T read anything modified by a transaction that committed
after T started. The practical decision rule is **first-committer-
wins** — whoever validates first commits, the loser aborts.

This is the formal skeleton under *all* of options (A) and (C),
XTDB's `match`/`ASSERT`, EventStore's `expectedRevision`, and SQL's
`WHERE version = ?`. The accumulated field wisdom on *when OCC wins
vs thrashes*: OCC wins when conflicts are **rare** (the validation is
cheap, aborts are rare, no lock-holding latency); OCC **thrashes**
when contention is high (work is done, then thrown away, then
redone — pure waste, and under load it can livelock). The
contention-shape question is the whole game.

datahike's situation has a specific twist: it is **single-writer and
serialized**. There is no *write/write* concurrency to lose to — only
the window between "I snapshotted the db" (option A, outside the
writer) and "my tx reaches the front of the single-writer queue." A
concurrent commit in that window is the only race. So kontor's option
(A) is OCC with a read-set but where the "validation" is currently
*absent* — option (C) is simply "add the missing validation phase as
an in-transaction assertion."

## 6. The Saga pattern — when you *can't* be one atomic transaction

Garcia-Molina & Salem's saga is the escape hatch for when atomicity
is *impossible* because a step touches an external system (a payment
gateway, a tax authority's clearance API). A saga is "a sequence of
local transactions where each local transaction updates the database
and publishes a message or event to trigger the next"; on failure it
"executes a series of **compensating transactions** that undo the
changes that were made by the preceding local transactions"
([microservices.io saga][saga]). It trades ACID atomicity for
*eventual* consistency with explicit semantic rollback.

Two structures: **orchestration** (a central coordinator drives the
steps and the compensations — easier to reason about, but the
orchestrator is a single point of complexity) and **choreography**
(each step emits events that trigger the next — looser coupling, but
the control flow is smeared across the system and hard to follow).

**This is the line kontor must draw deliberately.** Note 42 already
states the rule of thumb: if the intermediate state is true in the
world (in-transit inventory, `:pending-attestation` awaiting a
government callback) it is a *legitimate* multi-tx process — model it
as a saga-shaped status machine + ADR-041 `:side-effect-intent` rows
(kontor already has a choreography-ish substrate here). If the
intermediate state is merely "I had to commit to read it back," it is
*not* a saga — it should collapse into one atomic tx. The saga
literature's contribution to the kontor decision is the *taxonomy*:
it tells you which multi-step operations are allowed to be non-
atomic, so `kontor.process` can confidently make all the *others*
atomic.

## 7. Event sourcing / CQRS command handlers — expectedVersion

The event-sourcing command handler is the cleanest small-scale
statement of the pattern kontor wants. The shape: load the entity's
events → fold to current state → run the command's business logic
against that state → produce new event(s) → **append with an
`expectedRevision`**. EventStoreDB compares the supplied
`expectedRevision` to the stream's `currentVersion`: equal → append
and ack; mismatch → `WrongExpectedVersionException`, and "the handler
reloads the entity, reevaluates, and retries" ([EventStoreDB
appending][esdb]). Marten (Postgres event store) and Axon implement
the same `expectedVersion`/`@AggregateVersion` check.

This is **option (C) in its most distilled form** and it is the
*dominant* pattern in the event-sourcing world — not a niche choice.
The command handler does its compute optimistically against a loaded
snapshot, and the *only* thing that runs inside the serialized append
is the cheap version comparison. The retry loop is in the handler,
not the store. The naming convention — `expectedRevision` /
`expectedVersion` — is worth borrowing: the precondition is "I
computed this against version N; only commit if you are still at N."

## 8. Immutable-datalog & deductive DBs — transaction-as-data

**Datascript** is the purest expression of "the db is an immutable
value, a transaction is just data." Its db is "more like data
structures than databases (think Hashmap)"; `d/db-with` applies
tx-data to a db *value* yielding a new db value, same as
`(:db-after (with db tx-data))` ([Datascript internals][datascript]).
Datascript has tx-fns (with the partial-db semantics datahike
inherits) but the *idiomatic* composition story is: build tx-data as
ordinary data, optionally thread it through `db-with` to see
intermediate states, transact once. There is no concurrency story
because Datascript is in-process and single-threaded by construction
— which is *exactly* datahike's shape minus durability.

**Materialize / Differential Dataflow** sit at the other end:
transactions are inputs to a dataflow, and consistency is about
*timestamps* — every read is "as of timestamp T," writes advance T.
Composition is "all writes at the same timestamp are atomic." Not
directly a model for kontor, but it reinforces the immutable-value
worldview: a "transaction" is a labeled batch of data, not a
mutable session.

The throughline across the immutable-datalog family: **the
transaction is a value you build up, not a session you hold open.**
datahike's `d/db-with`-threading (option A's mechanism) is the
*native idiom* of this family, not a workaround. An experimental
upstream `datahike.optimistic` namespace already layers pending
tx-data over a db via `db-with` (note 42 §4) — the threading pattern
is considered idiomatic by datahike's own authors.

## 9. Single-writer runtimes — embrace the serialization

datahike's single-writer shape is not a defect; several high-
performance systems *choose* it. The **LMAX Disruptor** is built on
the "Single Writer Principle" — "in environments where a single
thread is responsible for all writes, contention and synchronization
overhead can be minimized" ([Martin Fowler, LMAX][lmax]). LMAX's
business logic runs on **one thread**, processing events serially
from a ring buffer; it hits ~6M tx/s precisely because there is no
locking. **Redis** is famously single-threaded for command execution
for the same reason. The programming model these systems pair with a
single writer is always the same: **the writer thread does only the
fast, in-memory state mutation; all slow work (I/O, heavy compute) is
kept off the writer thread and pre-staged.** LMAX pre-loads all data
into memory so the business-logic thread never blocks; the
"transaction" reaching the writer is already a small, ready-to-apply
unit.

This is the same lesson as Datomic's "keep the tx-fn minimal" and
EventStore's "only the version check runs in the append" — stated
three times by three unrelated communities. **For a single-writer
system, the dominant design is: compute outside, commit a small
ready-made unit, guard with a cheap precondition.** That is option
(A)+(C), not option (B).

## 10. Transaction DSLs / builders — transaction as a composable value

Worth noting that "the transaction is data" enables a whole style:
Datomic/datahike tx-data is itself a composable EDN value (vectors
and maps you can `concat`, `into`, generate); HoneySQL builds SQL as
data; command objects in CQRS are plain data records. The relevance
to kontor: a `kontor.process` step that returns a *tx-data fragment*
(rather than performing a transact) is squarely in this tradition —
fragments compose by `into`, the assembled vector is one value, and
the value can be inspected/tested (`:dry-run?`) before it ever
reaches the writer. kontor's existing pure `plan-*` fns and
`build-transaction` already work this way; note 42's proposal just
extends the composition across step boundaries.

---

## Synthesis — what this means for the kontor decision

### Where `:db.fn/call` sits, and what the precedents warn about

datahike's `:db.fn/call` is **Datascript's tx-fn** (ordered,
partial-db-threaded), wearing **Datomic's `:db/fn` syntax**, running
in a **single-writer serialized pipeline like Datomic's transactor**.
The closest precedent is therefore Datomic's `:db/fn` *operationally*
(single writer, no reentrancy, abort-on-throw) but Datascript's
*semantically* (it sees the in-flight db). Every precedent that uses
in-transaction code — Datomic, XTDB v1, PL-pgSQL, LMAX — issues the
**same warning**: keep the in-transaction work minimal, because it
serializes against every other writer. Datomic says it in the docs;
XTDB v2 said it by *removing* general tx-fns; LMAX says it by
architecture. **The warning is unanimous: option (B) — heavy compute
inside the tx-fn — is the anti-pattern the whole field steers away
from.**

### The ancient tradeoff — is there a dominant answer?

"Compute inside the transaction (blocks the writer) vs compute
outside optimistically (race)" is the OCC-vs-pessimistic-locking
debate, and the field *does* have a dominant answer for kontor's
shape — but it is conditional on contention:

- **Under low write-contention** (kontor's actual situation — single
  writer, accounting is an inherently serialized domain, and the only
  race is the snapshot→commit window) the dominant answer is
  **optimistic: compute outside, guard with a precondition, retry on
  the rare conflict.** SSI, EventStore, XTDB `match`/`ASSERT`, Marten,
  Axon — all default to this. The retry is cheap because it almost
  never fires.
- **Under high write-contention** OCC thrashes and pessimistic
  locking wins — but kontor is single-writer, so it is structurally
  in the low-contention regime. It does not have the workload that
  makes OCC thrash.

So it is *not* genuinely workload-dependent *for kontor* — kontor's
workload is pinned to the regime where optimistic wins. The honest
caveat: if a future kontor consumer hammers one hot entity (e.g. a
shared sequence-number row), that *one* attribute could thrash, and
the answer there is to make *that specific contention point*
pessimistic (a tx-fn that does the minimal increment) — exactly the
"only the part that needs the in-transaction db goes in the tx-fn"
discipline.

### What the closest analogs recommend for a single-writer immutable datalog DB

- **Datomic:** compute against a regular db value *outside* the
  transactor; put only the genuinely-in-transaction-dependent bit in
  a `:db/fn`. Transactions compose as flat tx-data sets.
- **XTDB:** build declarative DML; guard with `match` (v1) / `ASSERT`
  (v2). v2 deliberately removed general tx-fns.
- **Datascript:** the transaction *is* data; thread `db-with` to see
  intermediate states; transact the assembled value once.

All three converge on note 42's option (A) shape — *thread `db-with`
outside, commit once* — and they converge on adding a precondition
when correctness under concurrency matters (option C). **None of them
recommend option (B) as the default.** This is strong external
support for note 42 §5's `run-process` design: pure steps threaded
through `d/db-with`, committed once. The one thing note 42's
`run-process` sketch is *missing* relative to this survey is the
**precondition** — it commits the assembled tx-data with no check
that the snapshot it computed against is still current. That is the
gap option (C) fills (next point).

### Is compare-and-swap the right shape for option (C), and how to express it

Yes — and it is the single most-recurring pattern in the entire
survey. XTDB `match`, XTDB2 `ASSERT`, EventStore `expectedRevision`,
Marten/Axon `expectedVersion`, SQL `WHERE version = ?`, the OCC
validation phase — **every one is "I computed against state X; only
commit if the relevant part of state is still X."** The canonical
shapes, in increasing precision:

1. **Entity-version CAS** — the EventStore shape. Carry an expected
   version/revision per touched aggregate; assert it at commit.
   Cleanest when "the thing I depend on" is one entity.
2. **Read-set CAS / predicate assertion** — the SSI / XTDB-`match`
   shape. Assert that a *predicate over the db* still holds. More
   general; matches kontor because a `kontor.process` step's
   dependency is often "the db as it would be after step k-1," i.e. a
   set of facts, not one version number.

For kontor specifically, the natural expression of option (C) is:
`run-process` records, per step or for the process as a whole, the
**facts the computation depended on** (a read-set, or a small set of
`[e a v]` the steps `d/pull`-ed), and emits a *final* `[:db.fn/call
check-readset rs]` as the **first item** of the committed tx-data.
That tx-fn runs inside the serialized writer, sees the real
`db-before`, re-checks the read-set, and `throw`s (aborting the whole
tx) on divergence — `run-process` catches that and retries the whole
optimistic compute. This is *exactly* SSI's structure (optimistic
run, validate at commit, retry on conflict) but hand-rolled at the
read-set granularity kontor controls — and it keeps the
in-transaction work to a single cheap predicate check, satisfying the
unanimous "keep the tx-fn minimal" warning. datahike's `:db.fn/call`
*can* express this today (it sees `db-before`, throwing aborts) — no
upstream datahike change needed for the check itself.

The pragmatic sequencing: **ship option (A) first** (note 42's
`run-process` — atomicity alone deletes `:fired-before-violation`,
`assert-modifiable!`, the lockstep guard, and the `commence!`
tempid re-read). Add the option-(C) read-set assertion **only where a
real snapshot→commit race is demonstrated** — for most kontor
processes the steps are driven by a human action and the window is
microseconds against a single writer, so (A) is sufficient and (C) is
gold-plating until proven otherwise. This staged approach mirrors how
SSI itself is offered: REPEATABLE READ (≈ option A) is the workhorse,
SERIALIZABLE (≈ option C) is opt-in for the transactions that need
it.

### Naming and structuring conventions worth borrowing

| System | Calls it | Borrowable convention |
|---|---|---|
| Datomic / datahike | **transaction function** (`:db/fn`, `:db.fn/call`) | "pure `[db args] → tx-data`"; abort-by-throw |
| SQL | **stored procedure**, `BEGIN…COMMIT` | isolation level as an explicit knob |
| XTDB | **`match`** / **`ASSERT`** | the precondition is a *named op*, not buried in code |
| Saga literature | **saga**, **compensating transaction**, orchestration/choreography | name the *non-atomic* multi-step thing distinctly so it is not confused with the atomic one |
| Event sourcing | **command handler**, **`expectedRevision`/`expectedVersion`** | the optimistic precondition has a *name* and lives in the handler, retry loop in the handler |
| OCC literature | **read-set / write-set**, **validation phase**, **first-committer-wins** | the vocabulary for reasoning about why a process is/ isn't safe |
| LMAX | **single writer principle**, **event handler** | "the writer does only the fast part" as an explicit principle |

Concrete recommendations for `kontor.process`:

- Call the unit a **process** and its parts **steps** (note 42
  already does) — but reserve **saga** for the *deliberately non-
  atomic*, externally-coupled multi-step case (note 42's
  `:side-effect-intent`-backed processes). Two names for two things,
  per the saga literature's discipline.
- A step is a **pure `(speculative-db, accumulated-tx-data, ctx) →
  tx-data-fragment`** — the Datomic/Datascript "pure `[db …] →
  tx-data`" convention, extended with the threaded accumulator.
- If/when option (C) lands, name the precondition explicitly — an
  **`:expect`** or **`:assert`** clause on a process, echoing XTDB
  `ASSERT` and EventStore `expectedRevision` — rather than burying it
  in a bespoke tx-fn. The recurrence of this exact named-precondition
  pattern across five unrelated systems is the signal that it is the
  right shape.
- Keep the **retry loop in `run-process`** (the "command handler"),
  not in datahike — EventStore, Marten, and SSI-using apps all put
  retry in the caller.

---

## Sources

- [Datomic — Transaction Functions][datomic-txfn]
- [Datomic — Cloud Ions][datomic-ions]
- [Datascript internals (tonsky.me) — partial-db tx-fn semantics, `db-with`][datascript]
- [XTDB v1 — Datalog Transactions (`put`/`match`/`delete`/`evict`)][xtdb1-tx]
- [XTDB — Transactional Extensibility (v1 transaction functions)][xtdb1-txfn]
- [XTDB v2 — SQL Transactions (`BEGIN`/`COMMIT`, `ASSERT`)][xtdb2-tx]
- [PostgreSQL — Transaction Isolation (READ COMMITTED / REPEATABLE READ / SERIALIZABLE + SSI)][pg-iso]
- [Kung & Robinson — On Optimistic Methods for Concurrency Control (ACM TODS 1981)][kung-robinson]
- [microservices.io — Saga pattern (after Garcia-Molina & Salem)][saga]
- [EventStoreDB — Appending events / `expectedRevision`][esdb]
- [Martin Fowler — The LMAX Architecture (Single Writer Principle)][lmax]

[datomic-txfn]: https://docs.datomic.com/transactions/transaction-functions.html
[datomic-ions]: https://docs.datomic.com/ions/ions.html
[datascript]: https://tonsky.me/blog/datascript-internals/
[xtdb1-tx]: https://v1-docs.xtdb.com/language-reference/1.24.3/datalog-transactions/
[xtdb1-txfn]: https://xtdb.com/blog/xtdb-transaction-functions
[xtdb2-tx]: https://docs.xtdb.com/reference/main/sql/txs.html
[pg-iso]: https://www.postgresql.org/docs/current/transaction-iso.html
[kung-robinson]: https://www.eecs.harvard.edu/~htk/publication/1981-tods-kung-robinson.pdf
[saga]: https://microservices.io/patterns/data/saga.html
[esdb]: https://docs.kurrent.io/clients/tcp/dotnet/21.2/appending
[lmax]: https://martinfowler.com/articles/lmax.html

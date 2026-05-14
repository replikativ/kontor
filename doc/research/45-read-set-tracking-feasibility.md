# 45 — Can kontor track read-sets cheaply? — datahike / Datomic / XTDB v2 / Postgres-SSI

**Date:** 2026-05-14
**Method:** two agents — (1) a web + local-source survey of Datomic,
XTDB v2, and datahike's transaction/conflict primitives; (2) a
file:line read of the **PostgreSQL SSI source** (`predicate.c`,
`README-SSI`) as the canonical reference implementation.
**Question this answers:** the `kontor.process` design (notes 42, 44)
wants a *structural* guarantee against the snapshot-vs-commit race. A
hand-declared read-set (`:expect`) is "only as safe as the
declaration is complete." Auto-captured read-sets — what real SSI
does — would be structural. *Is cheap auto-capture feasible on
datahike?*

## Bottom line

**Serialize `kontor.process` by default.** Cheap *precise* auto-capture
does not exist for datahike off the shelf, and the genuine blocker is
not the SSI logic — it is making *capture* automatic. Auto-capture is
**not** rejected as too-expensive (Postgres's source shows
single-writer collapses ~80% of SSI's machinery); it is *deferred*
because doing it cleanly needs a small **upstream datahike hook**, not
a fragile in-tree wrapper. The two agents disagree on the headline —
and that disagreement *is* the finding: the SSI conflict-check is
cheap on a single writer; the read-*capture* is the hard part.

## What the survey found (agent 1)

- **No off-the-shelf datahike mechanism.** `datahike.optimistic` is a
  *UI* overlay (the name is a trap — it layers pending client txns
  over `@conn` for responsive rendering); `datahike.query-stats` is
  query-plan introspection (row counts, clause timings) — neither
  records *which datoms were read*.
- **datahike ships `:db.fn/cas`** — single-datom compare-and-swap,
  built into the transactor (`db/transaction.cljc:582-583,698-710,
  862`), mirroring Datomic's `:db/cas` exactly: `[entity attr
  expected new]`, cardinality-one, throws on mismatch. This is a
  *structural* guarantee for the single-datom case, with zero
  read-set bookkeeping.
- **Datomic has no read-set mechanism either.** Its docs are explicit:
  `:db/cas` is "the sole built-in mechanism for optimistic
  concurrency," single-attribute granularity; multi-datom
  preconditions must be hand-coded inside a transaction function. So
  datahike already exposes Datomic's *whole* toolkit — and that
  toolkit deliberately stops at single-datom CAS + tx-fn.
- **XTDB v2 *removed* general transaction functions in favour of
  declarative `ASSERT`** (`indexer.clj:351` `->assert-idxer`,
  SQLSTATE `P0004`). `ASSERT` is a query evaluated against the
  in-flight DB at apply-time — it closes the *staleness* window (it
  runs at commit, not against a stale snapshot) but **not** the
  *completeness* window (a forgotten `ASSERT` still fails silently).
  XTDB v2 tracks **no read-set** — it is exactly the hand-declared
  precondition model. The industry trend is *toward* explicit,
  declarative, apply-time-evaluated preconditions and *away* from
  both auto-magic and opaque closures.
- **A datahike read-logging wrapper is fragile.** `d/q`/`d/pull`/the
  entity API dispatch through `ISearch`/`IIndexAccess` — but the
  query *engine* bypasses those protocols, reaching `(:eavt db)` /
  `(get db index)` directly throughout `query/execute.cljc`
  (`:410,673,698,801,1401,2340,…`). A wrapper would have to
  instrument *two* layers (the protocols *and* the index types) and
  would silently leak reads on any future direct-access added
  upstream. The captured read-set is also coarse (index ranges) —
  the SSI granularity tax (below).

## What the Postgres SSI source shows (agent 2)

PostgreSQL's `SERIALIZABLE` is the reference implementation of
automatic read-set tracking. From `predicate.c` / `README-SSI`:

- A tracked read is a **SIREAD "lock"** — not a lock, "more like a
  flag" (`predicate.c:46-48`): a `{target, txid}` pair in shared-
  memory hash tables, keyed by a 16-byte `{db,relation,page,offset}`
  tag whose *granularity is encoded in the tag itself*
  (`predicate_internals.h:267-273,414-417`).
- **Granularity + promotion is the crux of "cheap"**
  (`predicate.c:2286-2375`, `README-SSI:290-298`). SSI starts at the
  finest granularity (tuple / index-leaf-page) and *promotes upward*
  under memory pressure — `max_pred_locks_per_page` defaults to **2**
  (3+ tuple locks on a page collapse to a page lock). Coarse locks
  are O(1) memory but conflict against *any* write to the relation
  → **false-positive serialization failures**. Fine locks are
  precise but O(rows). The whole design is managing that tradeoff.
- **The honest cost**: ~5000 lines, three shared hash tables, an
  SLRU spill, partition locks, a careful lock-ordering protocol, 2PC
  support. `README-SSI:231-235` itself warns SSI "degrades more
  rapidly with a large number of active transactions."
- **But — most of that is multi-writer bookkeeping that does not
  exist for datahike.** Single-writer + immutable db *values*
  deletes: the shared-memory lock table (no cross-process
  visibility/lifetime needed), rw-conflict-*out* detection, the
  "dangerous structure" pivot graph, abort-victim selection. The
  only question kontor faces — "did anything in my read-set change
  between the db value I read and the db value at commit?" — is
  rw-conflict-*in* against **one** serialized commit point: a
  set-intersection / range-overlap test, not a graph walk. Agent 2's
  estimate: the *tracker* is a few hundred lines, not five thousand.
- **The transferable lesson — the granularity ladder.** It maps onto
  datahike's index structure: per-datom `[e a v]` ↔ tuple lock
  (precise, but a scan records thousands); per-`[a v]`/`[e a]` range
  ↔ page lock (**the sweet spot** — a datalog clause *is* a range
  over an index); per-attribute `[a]` ↔ relation lock (the cheap
  escalation target). Postgres's aggressive default (`= 2`) says:
  default to range/attribute granularity, only go per-datom for
  point lookups, promote eagerly.

## Synthesis — the decision for `kontor.process`

The two agents reconcile cleanly: **the SSI *conflict-check* collapses
to cheap on a single writer (agent 2 is right); the read-*capture*
is the hard, fragile part (agent 1 is right)** — because datahike's
query engine doesn't route reads through a wrappable seam. So
auto-capture's blocker is not algorithmic complexity, it is "make
capture automatic without a fragile two-layer wrapper" — which is
best solved by a *small upstream datahike hook* (a `query-stats`-style
recorder that also emits the index ranges touched), in the same
spirit as the already-contemplated datahike-PR work (task #75).

The discriminator both agents converge on: **auto-capture pays off
only for *long* processes you want to run *concurrently* with other
writers.** kontor's processes are short (note 42's concrete walk:
`commence!` is PV arithmetic + entity maps, `run-lease!` over a few
periods is milliseconds), and its domain is serial by nature (the
ledger is a total order). The genuinely-long processes (a 60-month
backfill, a year-end close) are *rare* and arguably *should* be
serialized anyway. So for kontor's actual workload, serialization is
the pragmatic structural guarantee and auto-capture buys little.

**Recommended `kontor.process` model:**

1. **`run-process` serializes** — a lock around `(d/db conn) →
   compute → d/transact`. The structural concurrency guarantee, zero
   read-set bookkeeping, fits the domain. Commit through the existing
   `validate-and-apply` `:db/fn` gate.
2. **`:db.fn/cas`** (datahike's built-in) — the escape hatch for a
   genuinely-hot single-datom transition you'd run lock-free.
   Structural, no read-set.
3. **An optional hand-declared `:expect` predicate**, re-checked
   inside a `:db.fn/call` at apply-time (XTDB-`ASSERT`-style) — for a
   multi-datom precondition on a process you deliberately run
   lock-free. **Documented honestly**: closes the staleness window,
   *not* the completeness window.
4. **Auto-capture: considered, deferred — not rejected.** Recorded in
   the ADR as: single-writer collapses the SSI machinery to ~a few
   hundred lines; the real blocker is automatic capture, best done
   with a small upstream datahike read-range-recording hook. If/when
   kontor grows long processes that must run concurrently, it is a
   bounded, cheap addition — *not* a Postgres-grade monster.

### Sources

Agent 1: Datomic docs (transaction-functions, model, ACID), XTDB v2
docs + blog (*Transactional Extensibility*) + local
`xtdb2/core/.../indexer.clj`, `pgwire.clj`; datahike local source
`db/interface.cljc`, `db.cljc`, `query/execute.cljc`,
`db/transaction.cljc`, `optimistic.cljc`, `query_stats.cljc`.
Agent 2: PostgreSQL local source `src/backend/storage/lmgr/
predicate.c`, `README-SSI`, `src/include/storage/
predicate_internals.h`, `guc_parameters.dat`; Ports & Grittner
(VLDB 2012, arXiv:1208.4179); Cahill/Röhm/Fekete (SIGMOD 2008).

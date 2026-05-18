---
date: 2026-05-15
title: Is XTDB v2's system-time mutable per-row or immutable per-tx? Implications for stratum P0-1.
status: draft
---

## TL;DR

XTDB v2 does **not** store `_system_to` on the row at all. Only `_system_from`
is written, exactly once, at the transaction's commit instant; existing rows
are **never** touched again, including during backdated valid-time
corrections. The `_system_to` column that appears in query results is
**computed at read time** by the `PolygonCalculator` / `Ceiling` pair, by
finding the next-later event for the same `iid` whose vt-window overlaps. The
public documentation matches this: "Rather than mutating existing rows, XTDB
creates new row versions … each update as a new row that sits alongside the
previous versions of that row in the same table" (docs.xtdb.com/concepts/key-concepts).

In semantic spirit XTDB v2's `_system_from` is the **direct analogue of
datahike's `:db/txInstant`** — set once per tx, immutable per row/datom,
supersession is resolved at read time.

This means stratum's P0-1 (closing `_system_to` on the old row via `idx-set!`
during SCD2 surgery) is **not what XTDB does internally**, but it is **not
necessarily wrong** for stratum, because stratum has a row-storage model
without a trie-merge / ceiling abstraction. Stratum's P0-1 is one of three
self-consistent designs (§"Implications"). The honest framing is: stratum's
storage model is closer to a classical SCD2 / SQL:2011 system-time table
(where the engine writes `_system_to` because it has no other way to express
supersession) than to XTDB's append-only ledger. Both are correct
implementations of the same observable semantic at the query surface; the
internal representations diverge.

## XTDB v2's system-time semantic (with citations)

### Code: only `_system_from` is written; `_system_to` is read-time

`xtdb2/core/src/main/kotlin/xtdb/indexer/LiveTable.kt` declares writers for
only four temporal columns, and `_system_to` is **not among them**:

```kotlin
// LiveTable.kt:38-40
private val iidWtr        = liveRelation.vectorFor("_iid")
private val systemFromWtr = liveRelation.vectorFor("_system_from")
private val validFromWtr  = liveRelation.vectorFor("_valid_from")
private val validToWtr    = liveRelation.vectorFor("_valid_to")
```

There is no `systemToWtr`. Cross-checking with grep across the whole kotlin
tree confirms: `_system_to` appears only in **read-side** code paths
(`BitemporalConsumer`, scan operator, `Polygon`, `Ceiling`, SQL projection in
`sql.clj:301,332`, information-schema column definition in
`information_schema.clj:39`).

The three log operations all write `_system_from = systemFrom` (the tx commit
instant computed once at `Tx.<init>` from `txKey.systemTime`) and never touch
any other row:

```kotlin
// LiveTable.kt:83-97
fun logPut(iid: ByteBuffer, validFrom: Long, validTo: Long, writeDocFun: Runnable) {
    val pos = liveRelation.rowCount
    iidWtr.writeBytes(iid)
    systemFromWtr.writeLong(systemFrom)   // immutable per-tx
    validFromWtr.writeLong(validFrom)
    validToWtr.writeLong(validTo)
    writeDocFun.run()
    liveRelation.endRow()
    transientTrie += pos                   // append-only
    rowCounter.addRows(1)
}
```

`logDelete` (LiveTable.kt:99-111) and `logErase` (113-125) do the same thing:
allocate a new row at the end of the relation, write `_system_from = tx.systemTime`,
no mutation of any prior row.

The clojure side mirrors this. `->upsert-rel-indexer` in
`xtdb2/core/src/main/clojure/xtdb/indexer.clj` ends every successful row in
the inbound relation with `.logPut`, and never looks up older rows to close
them:

```clojure
;; indexer.clj:283-300 (->upsert-rel-indexer)
(dotimes [idx row-count]
  (err/wrap-anomaly {...}
    (let [eid        (.getObject id-col idx)
          valid-from (... idx)
          valid-to   (... idx)]
      (when (< valid-from valid-to)
        (.logPut live-table-tx (util/->iid eid) valid-from valid-to
                 #(.copyRow live-idx-table-copier idx))))))
```

`->delete-rel-indexer` (302-331) calls `.logDelete`. `->erase-rel-indexer`
(333-349) calls `.logErase`. None of these read the existing trie to find
"the row I need to close out" — there is no such row to close out.

### How `_system_to` materialises at read time

The bitemporal scan operator emits a `_system_to` value to the row consumer,
but it does **not** read the value from storage. It derives it via the
`PolygonCalculator` (`xtdb2/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt`)
which walks events for one `iid` in **descending system-time order**
(EventRowPointer comparator, EventRowPointer.kt:46-51 sorts by iid then by
descending `systemFrom`). For each event it applies a "ceiling" update:

```kotlin
// PolygonCalculator.kt:35-44
val systemFrom = erp.systemFrom
if (temporalBounds != null && systemFrom >= temporalBounds.systemTime.upper) return null
val validFrom = erp.validFrom
val validTo   = erp.validTo
polygon.calculateFor(ceiling, validFrom, validTo)
ceiling.applyLog(systemFrom, validFrom, validTo)
return polygon
```

`Ceiling.applyLog` (Ceiling.kt:78-122) maintains a per-iid map from
vt-segments to "the smallest `_system_from` we have seen so far for this
segment". Because events are processed newest-first, an earlier (lower
`_system_from`) event that overlaps the same vt-segment finds the ceiling
already occupied by the newer event's `systemFrom`; the **older event's
`_system_to` for that segment then equals the newer event's `_system_from`**
— derived, not stored. `BitemporalConsumer.accept`
(BitemporalConsumer.kt:31-43) projects `systemTo == MAX_LONG` as SQL `NULL`,
which is how XTDB v2 reports "still open".

### Documentation

`docs.xtdb.com/concepts/key-concepts` (fetched 2026-05-15):

> "XTDB is designed for making non-destructive updates to your data simple
>  and achieves this by modeling row-level temporal versions of data. This
>  works by representing each update as a new row that sits alongside the
>  previous versions of that row in the same table."

> "The values of these columns [`_system_from`, `_system_to`] are maintained
>  automatically and the respective pairs of columns always form 'closed-open'
>  periods (i.e. inclusive of 'from', exclusive of 'to')."

> "`system_time_start` can be specified to allow for importing bitemporal
>  records from legacy systems."

`docs.xtdb.com/intro/what-is-xtdb` (fetched 2026-05-15):

> "XTDB tracks both the system time when data is inserted (or UPDATE-d) into
>  the database, and also the valid time periods."

> "Cope with out of order arrival of information, including corrections to
>  past data while maintaining a general sense of immutability."

> "All data is bitemporal without having to think about storing or updating
>  additional columns."

`docs.xtdb.com/reference/main/sql/txs` confirms the ERASE caveat:

> "While XTDB is immutable, in some cases it is legally necessary to
>  irretrievably delete data (e.g. for a GDPR request)."

(ERASE is a tombstone op that `PolygonCalculator` honours by resetting the
ceiling and skipping the iid — PolygonCalculator.kt:29-33 — but the erase
**row itself** is still an append; it doesn't reach back and delete prior
storage rows. Storage-level retraction is a separate compaction concern.)

The public docs never use the phrase "system-time is immutable" in those
exact words, but the equivalent claim — "non-destructive updates", "each
update as a new row", "maintained automatically", "valid_time_from /
valid_time_to can be specified [for backdating], system-time cannot
(except `system_time_start` for legacy import)" — is direct and consistent.

### Summary of the XTDB v2 model

1. **Per-row `_system_from` is set once** at the transaction's commit instant
   (`txKey.systemTime`) and is **never modified**.
2. **`_system_to` is not stored** on the row at all. The column appears in
   query projections only; the value is computed at read time as "the
   smallest `_system_from` of a later event for the same iid that overlaps
   this vt-segment", or `NULL` (= MAX_LONG) if no such later event exists.
3. **Backdated corrections never touch old rows.** A `UPDATE FOR PORTION OF
   VALID_TIME FROM A TO B SET …` issued at tx-time `T1` against an entity
   previously written at tx-time `T0` (with vt-window `[A0, B0)`) just
   appends a new row with `_system_from = T1`, `_valid_from = A`,
   `_valid_to = B`. The T0 row stays byte-for-byte identical on disk; at
   read time the scan operator merges them and reports the T0 row's
   `_system_to = T1` for the overlapping vt-segment.
4. **The WITHOUT OVERLAPS invariant is per-(iid, "as-of-system-time)**:
   when you ask `FOR SYSTEM_TIME AS OF s` the operator filters events to
   those with `_system_from <= s`, then runs the ceiling algorithm, and the
   resulting polygon for one iid has non-overlapping vt-segments by
   construction. The invariant is a read-time consequence of the algorithm,
   not a write-time constraint that needs to be enforced by closing-out
   columns on existing rows.

## Comparison to datahike's `:db/txInstant`

datahike's `:db/txInstant` is, in spirit, the same thing as XTDB v2's
`_system_from`, applied at datom granularity instead of row granularity.

`datahike-bitemporal-v1/src/datahike/db/transaction.cljc:959-960`:

```clojure
(update initial-report :tx-meta
        #(merge {:db/txInstant (get-date)} %))
```

Set once at transaction start, then `flush-tx-meta` (686-) produces a single
`[:db/add tx-eid :db/txInstant <instant>]` datom that gets folded into the
transaction. After commit, that datom is **never modified**. A subsequent tx
that "updates" an entity is itself a new tx with its own `:db/txInstant` and
its own retract/assert datoms; supersession of the older value happens at
read time via the eavt index (`d/as-of` filters out datoms whose tx is later
than the requested asof).

Side-by-side:

| Concept                       | XTDB v2                   | datahike                       |
|-------------------------------|---------------------------|--------------------------------|
| Tx-time stamp                 | `_system_from` (per row)  | `:db/txInstant` (per tx)       |
| Immutability                  | Per-row, never modified   | Per-datom, never modified      |
| "Tx-to" column                | None (computed at read)   | None (computed via eavt+asof)  |
| Backdated correction          | Append new rows           | Append new datoms              |
| AS-OF semantic                | Filter `_sf <= s`, ceiling| Filter `tx <= as-of-tx`        |
| Per-row supersession marker   | Derived from next event   | Derived from next assert/retract|
| Real deletion                 | `ERASE` (compaction)      | `:db/purge` (compaction)       |

The two systems differ only in granularity (datahike: per-datom; XTDB v2:
per-row, where "row" = one (iid, vt-window) tuple) and in how the read-side
algorithm assembles the as-of view. They are **the same model**.

The model both share is best described as **append-only ledger of events
keyed by entity-id, with system-time fixed per event at write and per-event
supersession resolved at read**. SQL:2011 system-time tables (the
"application-time / system-time period" feature, which is what stratum's
grammar is mimicking) take the *opposite* approach: the engine writes
`_system_to` at write time when superseding an older row, because the
storage model is classical row-versioning, not an event log.

## Implications for stratum's P0-1 design

### What stratum P0-1 currently does

Per the commit message and the task list (`P0-1: system-time symmetry on
SCD2 writes`, task #176), stratum's `idx-update!` / `idx-set!` on a
bitemporal index closes the OLD row's `_system_to` via `idx-set!` and
appends a SUCCESSOR row with fresh `_system_from`. The motivation given
was: a query `FOR SYSTEM_TIME AS OF <past>` should see the pre-correction
state.

### Is the motivation right?

The motivation is **correct** at the query-surface level. SQL:2011 / XTDB /
datahike all agree: `FOR SYSTEM_TIME AS OF <past>` must return the state
as the database believed it at `<past>`, not the corrected state. Stratum's
P0-1 achieves that. It is **not** misinterpreted at the query surface.

### Is the storage representation right?

This is where stratum diverges from XTDB. XTDB does not need to write
`_system_to` because the trie-merge scan operator can derive it from the
event log. Stratum does not have a trie-merge scan operator; it has a
classical secondary index over (entity, attribute, value, system-window,
valid-window) tuples and queries it row-at-a-time. **For stratum's storage
model, P0-1 is the simplest correct implementation.**

The alternatives:

- **(a) Status quo (P0-1).** Close `_system_to` on the old row at write
  time. AS-OF queries filter rows where `_system_from <= s < _system_to`.
  Simple read predicate. Costs one extra write per correction (mutate the
  old row's `_system_to`). **This is what stratum does today.**
- **(b) Append-only, derive at read.** Never mutate old rows; queries
  filter `_system_from <= s` and run a per-iid "latest wins" merge at read
  time. Matches XTDB v2 internally. Requires a read-time merge operator
  that stratum currently does not have; would also break stratum's
  "secondary-index lookup is one B-tree probe" performance model.
- **(c) Drop the `_system_to` column entirely.** Treat system-time as
  exclusively per-tx (like datahike's `:db/txInstant`). Implies stratum
  drops bitemporal support and only does valid-time. **Not viable** —
  stratum's whole vt design assumes bitemporal storage of (vf, vt, sf, st).

The choice between (a) and (b) is **not** a correctness question; both can
implement SQL:2011 system-time semantics correctly. It is a **storage /
read-cost tradeoff**:

- (a) writes more on correction (the `idx-set!` on the old row) but reads
  cheaper (one B-tree probe per AS-OF query).
- (b) writes cheaper (single append) but reads more expensive (must scan
  all events for an iid and run the ceiling algorithm).

Given stratum's target — secondary indices over a Datahike-style
key-value store, where reads dominate writes by 100:1 or more in
accounting workloads — (a) is the right call. P0-1 should stay.

### One thing P0-1 must guarantee

If P0-1 closes `_system_to` on the old row, then **the close itself must
not be visible at AS-OF queries against system-times before the close**.
Concretely: a query `FOR SYSTEM_TIME AS OF T1 - 1` must still see the old
row's `_system_to = MAX_LONG` (i.e., open). This is what XTDB gets for
free because it never mutates old rows. Stratum needs to either:

- (i) keep two versions of the row (a "system-versioned" history of each
  row's `_system_to`), or
- (ii) treat the act of closing `_system_to` as itself bitemporal — i.e.,
  the close is a new event at `_system_from = T1`, not a mutation, and the
  AS-OF query filters events to those with `_system_from <= asof`. This
  collapses (a) into (b) with extra denormalization.

If stratum's current implementation **just overwrites** the old row's
`_system_to` in place, then an AS-OF query at `T0 < T1` would see the row
already closed at `T1`, which leaks future-state information into the
past view — the very bug that motivated P0-1 in the first place, now
appearing on the other axis. **This needs to be confirmed in code.** The
P0-1 commit (`a70ce43`) and surrounding tests should be re-audited.

## Recommendation

1. **Keep P0-1's intent** (close `_system_to` on SCD2 writes) — the
   query-surface semantics are right and match XTDB / datahike at the
   read surface. Do **not** rewrite stratum to be append-only; the
   classical SCD2 model is the right fit for stratum's secondary-index
   storage.

2. **Audit the AS-OF correctness** of P0-1: verify that a query
   `FOR SYSTEM_TIME AS OF <past>` sees the old row as still-open at
   `<past>`, not retroactively closed. If the current implementation
   does a flat `idx-set!` on `_system_to` without preserving the
   pre-mutation state, this is a bug — stratum needs to either keep a
   system-history of `_system_to` itself, or treat the close as a new
   bitemporal event (option (b) variant). This is a real ship-blocker
   for the bitemporal story, even if the test suite doesn't currently
   exercise it.

3. **Document the divergence from XTDB in stratum's design note.** Make
   clear that stratum's storage model is "classical SCD2 system-time
   table" not "append-only event log", and that this is a deliberate
   choice driven by the secondary-index substrate. Cite this kontor note
   (which is license-safe — internal research, no derivative code) but
   do NOT lift the XTDB v2 file:line citations into stratum's
   user-facing docs, since stratum is a separate project with its own
   provenance.

4. **For kontor's own audit story**: kontor's bitemporal queries go
   through stratum's vt-aware indices today (per recent ADRs). Whatever
   stratum decides on the AS-OF correctness audit propagates directly
   to kontor's audit trail. The dependency is real and worth a P1
   followup once stratum's behaviour is confirmed.

## Files referenced (kontor-internal, license-safe)

- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/indexer/LiveTable.kt:38-125`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/trie/EventRowPointer.kt:33-51`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt:21-45`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Polygon.kt:8-44`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Ceiling.kt:43-122`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/operator/scan/BitemporalConsumer.kt:11-43`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/indexer.clj:253-349`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/types.clj:244`
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/information_schema.clj:39`
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/db/transaction.cljc:686, 959-960`
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/doc/time_variance.md` (§"Meta Entity")
- `docs.xtdb.com/concepts/key-concepts` (fetched 2026-05-15)
- `docs.xtdb.com/intro/what-is-xtdb` (fetched 2026-05-15)
- `docs.xtdb.com/reference/main/sql/txs` (fetched 2026-05-15, ERASE section)

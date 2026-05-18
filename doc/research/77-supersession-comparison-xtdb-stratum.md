---
date: 2026-05-17
agent: research
title: Valid-time supersession — datahike feature/bitemporal-v1 vs xtdb v1 vs xtdb v2 vs stratum
status: research-note
---

# 77 — Valid-time supersession: datahike vs xtdb v1 vs xtdb v2 vs stratum

Focused merge-decision input for the datahike `feature/bitemporal-v1` PR
that ships polygon-at-read-time supersession plus retroactively-mutable
tx-meta `:db.valid/from` / `:db.valid/to` attributes. The maintainer
confirmed by REPL that a later commit can mutate a prior tx-entity's
valid-time window with no cross-tx `vf < vt` validation guard. We compare
the supersession discipline of three established systems (xtdb v1, xtdb
v2, stratum) along the five dimensions A-E from the brief.

## TL;DR

- **Read-time polygon vs write-time SCD2 is a real architectural fork.**
  XTDB v2 + datahike feature/bitemporal-v1 compute supersession at read
  time. XTDB v1 + stratum bitemporal mode close prior validity at write
  time. The "polygon" wing is the newer design and the one datahike is
  copying.
- **Nobody but datahike lets a user mutate a prior tx's valid-time
  window after the fact.** XTDB v2 has a hard ban —
  `forbidden-update-col?` in `core/src/main/clojure/xtdb/sql.clj:2647`
  rejects any UPDATE on a column whose name starts with `_`, which
  includes `_valid_from` / `_valid_to`. XTDB v1 stores valid-time on
  immutable `EntityTx` entries — to revise a window you submit a new
  `:crux.tx/put` with the corrected `start-valid-time` /
  `end-valid-time` (`core/src/xtdb/tx.clj:92-131`). Stratum's
  bitemporal path appends a successor row and only mutates
  `_system_to` on the prior row, never `_valid_from` / `_valid_to`
  (`src/stratum/dataset.clj:1262-1291`).
- **All four engines validate `vf < vt` at the write boundary; datahike
  is the only one that lets `vf >= vt` slip in through retroactive
  mutation of a *prior* row.** XTDB v2 indexer rejects at
  `core/src/main/clojure/xtdb/indexer.clj:173-177`; stratum rejects at
  `src/stratum/dataset.clj:286, 481, 1343` via `validate-period!`
  (`dataset.clj:1094-1107`). Datahike's `vf<vt` check runs only against
  the *new* tx's own meta — see `datahike-bitemporal-v1`
  `src/datahike/db/transaction.cljc` — not against the closure produced
  by editing a prior tx-entity's vt-to.
- **None of the comparators dedupe new-entities-per-run.** Re-running a
  consolidation that issues fresh `:txN/correction-of` entities every
  cycle will double-count in all four engines if the consumer doesn't
  use stable IDs. The polygon and SCD2 algorithms are key-by-(iid,
  attr, value); a new iid is a new fact, full stop. This is the
  consumer's job, not the engine's.
- **For the "supersede AT the same vt" edge case all four behave
  identically and sensibly**: as-of-tx 1 sees the original value; as-of-
  tx 2 (or later) sees the correction. The vt-coordinate is shared; the
  system/tx coordinate disambiguates.
- **The correction is itself a first-class auditable event** in every
  one of the four engines. They differ on whether the in-storage form
  of the correction is "the prior event minus a slice" (XTDB v1
  rewrites EntityTx on PUT), "a new event whose effect is computed at
  read time" (XTDB v2 and datahike polygon), or "an in-place
  `_system_to` close + a new SCD2 row" (stratum bitemporal mode).
- **Verdict for datahike's PR**: ship the polygon. Add a cross-tx
  `vf < vt` validation guard *before* merge. **Add a guard that
  forbids retroactively writing `:db.valid/from` or `:db.valid/to` on a
  tx-entity that's not the current tx — supersession should be by
  posting a new tx, not by mutating a prior one.** Both guards are
  cheap to add and bring the surface in line with the de facto
  standard set by XTDB v2 (the system the PR claims to mirror).

## 1. Datahike feature/bitemporal-v1 (baseline)

Worktree: `/home/christian-weilbach/Development/datahike-bitemporal-v1`.

Schema: `:db.valid/from` and `:db.valid/to` are normal
`:db.cardinality/one`, AVET-indexed tx-meta attrs
(`src/datahike/schema.cljc:127-134`). They sit on the tx-entity itself.
Like any normal datom they go through the transactor and end up in
EAVT/AEVT/AVET. There is no special "tx-meta" container — a tx-entity
is just an entity whose eid happens to be the tx-id, and which gets
written to as part of the same commit it describes.

Supersession (A): polygon at READ time. `mk-vt-pred`
(`src/datahike/api/impl.cljc:229-267`) is a `d/filter` predicate that,
for every datom under consideration, scans all historical datoms about
the same `(e, a, v)` and admits the datom only if its tx-id is the
greatest among those whose tx-vt window covers the query's `at`. So a
back-correction tx with a later tx-id supersedes the original, because
the algorithm picks the larger tx-id whose vt window covers `at`. The
docstring openly states this "mirrors XTDB v2's polygon at read time"
(`src/datahike/api/impl.cljc:232`). `valid-at`
(`src/datahike/api/impl.cljc:269-317`) wraps `d/filter` and stamps a
`:datahike/valid-at` meta marker so vt-aware secondaries (stratum) can
short-circuit via `IValidTimeAware/-search-at-vt`
(`src/datahike/api/impl.cljc:312`).

Mutability (B): YES — `[:db/add prior-tx-eid :db.valid/to <new-inst>]`
in any subsequent commit is accepted by the transactor and the closure
takes effect on subsequent `valid-at` queries. Datahike enforces
`vf < vt` for the NEW tx's own meta but does NOT re-validate the closure
formed by combining a prior tx's existing `:db.valid/from` with the new
`:db.valid/to`. So a careless write of `vt = 2026-01-15` against a
prior tx whose `vf = 2026-02-01` produces a "negative-width" window
that silently zeroes out the prior tx's contribution to every
`(d/valid-at db t)` query.

New-entities-per-run (C): a back-correction that issues a brand-new
posting entity (rather than reusing the original posting's eid) is, by
construction, a fact about a DIFFERENT `(e, a, v)` triple. The polygon
algorithm has no way to know the two postings represent the same logical
fact. Result: both contribute. This is the kontor-consolidation
double-count gap that note 76 flagged for ADR-073.

Same-vt edge case (D): tx-1 (vf=jan-31) and tx-2 (correction, vt of
tx-1's same vf=jan-31). At as-of-tx covering both, polygon picks tx-2
because it has the larger tx-id and its vt window covers jan-31. At
as-of-tx covering only tx-1 (`d/as-of (db) day-1`), polygon sees only
tx-1 and returns tx-1's value. The composition `(d/valid-at (d/as-of db
t) v)` is the documented way to bound the supersession horizon
(`src/datahike/api/impl.cljc:284-290`).

Audit (E): the correction is a normal commit; `:db.valid/from` /
`:db.valid/to` writes are real datoms with a real tx-id and a real
`:db/txInstant`. `datahike.audit/verify-chain` survives them. The
retroactive vt-to write IS a first-class auditable event. **What is
NOT documented in the audit chain is the *intent* — that the new vt-to
is a "supersede" rather than e.g. "fix a typo in the original tx-meta
that was a draft" — and there's nothing structurally stopping a write
from doing both at once.**

## 2. XTDB v1 (Crux line)

Repo: `/home/christian-weilbach/Development/xtdb`. README confirms
it's `com.xtdb/xtdb-core 1.23.1` at HEAD
(`README.md:30-37`). Datalog query interface, Calcite SQL,
document-keyed bitemporal kv index.

### A. Supersession mechanism

WRITE-time SCD2. The transactor handler `put-delete-coords`
(`core/src/xtdb/tx.clj:92-131`) recomputes the entity history for the
target eid against the new write's `[start-valid-time, end-valid-time)`
and emits an entry that close-and-reopens the existing slices. When
`end-valid-time` is supplied (bounded form, lines 101-118), it walks
the entity history descending from `end-valid-time`, replaces every
EntityTx whose vt is `< start-valid-time` ascending with new EntityTxs
pointing at the new content-hash, and inserts a closing EntityTx at
`end-valid-time` carrying the *previously-visible* content-hash so that
the window after `end-valid-time` is restored. When `end-valid-time` is
absent (lines 120-131), it splits the entity history at
`start-valid-time` and replaces every same-content-hash slice forward
to the next different-content-hash slice. The read path
(`core/src/xtdb/kv/index_store.clj:819-845`) is a plain seek on the
bitemporal index: the `EntityTx` for `(eid, valid-time, tx-id)` is
returned by a single bitemap-prefix seek. No polygon math at read time
— the polygon was baked at write time.

For the brief's back-correction example: `tx-1: salary=100k, vf=Jan`;
`tx-2: salary=90k, vf=Apr`. The Apr write splits the open window
`[Jan, ∞)` of tx-1 into `[Jan, Apr)` with content-hash A and the new
`[Apr, ∞)` with content-hash B. Query at vt=May returns 90k. Query at
vt=Feb returns 100k. Mechanically identical to the polygon outcome,
but achieved by writing two distinct `EntityTx` rows instead of
recomputing at read time.

### B. Mutability of prior tx-time metadata

NO. The only tx ops that touch the bitemporal index are `:crux.tx/put`,
`:crux.tx/delete`, `:crux.tx/cas`, `:crux.tx/match`, `:crux.tx/evict`,
`:crux.tx/fn` (`core/src/xtdb/tx.clj:133-186`). None of them carry a
"rewrite the vt-window of a prior tx" semantic. Every revision is
modeled as a new PUT (or DELETE) for the corrected `[start-vt, end-vt)`
window of the same eid. The transactor recomputes the supersession on
the fly inside `put-delete-coords`.

Cross-tx `vf < vt` validation: the new PUT's `end-valid-time` is
silently treated as `> start-valid-time` (line 101 — there's no
explicit check, but the case `start-valid-time = end-valid-time`
short-circuits the EntityTx rewrite on line 102 to "no etxs" — so a
reverse window is mechanically a no-op rather than a corruption). XTDB
v1's invariant is "the bitemporal index is fully determined by the
sequence of write ops; rewinding prior windows is not in the op set."

To "close" a prior tx's contribution to a window, the consumer issues a
new PUT with the corrected `end-valid-time` and (optionally) a new
content-hash. This is the analog of `FOR PORTION OF VALID_TIME UPDATE`,
done at the application level.

### C. New-entities-per-run pattern

Same answer as datahike polygon: XTDB v1 keys supersession by eid. A
consolidation that allocates a new eid per run produces N distinct
entity histories that all happen to describe the same accounting
position. The engine does not (cannot) deduplicate. Consumer's job.

(XTDB v1 has one subtle assist that polygon-only engines don't: a `cas`
op lets the consumer atomically *bail* if a stable key already has a
content-hash, which makes idempotent re-runs of consolidations possible
if the consumer chooses a stable key.)

### D. Same-vt edge case

tx-1 has vf=jan-31 with content-hash A. tx-2 corrects with the same
vf=jan-31, content-hash B. In `put-delete-coords` the open-window
branch (lines 120-131) at `start-vt = jan-31` finds the prior slice
`[jan-31, ∞)` with hash A, splits the open window again, and writes a
new EntityTx for `[jan-31, ∞)` pointing at hash B. The old EntityTx
for `[jan-31, ∞)` with hash A still exists in the index but with the
*earlier* tx-id — the `entity-as-of-resolver` seek at
`core/src/xtdb/kv/index_store.clj:819-845` filters by `tx-id <=
query-tx-id`, so:
- `entity-as-of(eid, vt=jan-31, tx-id=tx-1)` returns hash A.
- `entity-as-of(eid, vt=jan-31, tx-id=tx-2)` returns hash B.

The same-vt edge case is handled cleanly because the bitemporal index
key includes tx-id as a tiebreaker.

### E. Audit chain interaction

The PUT itself is a first-class log entry in the immutable tx log
(`core/src/xtdb/tx.clj` indexes `:crux.tx/put` etc.). The corrected
content-hash points at a new document in the document store. The
revision is irreversible (without `:crux.tx/evict`, which is GDPR-style
physical removal — a separate op family). XTDB v1's audit chain is
"the log of submitted ops + content-addressed documents"; corrections
appear as new PUTs and are bit-perfectly replayable from the log.

`:crux.tx/evict` (`core/src/xtdb/tx.clj:174-186`) does physically
remove documents for an eid — but the current main rejects vt-range
arguments (line 178-181, requiring an env var to opt back in to legacy
behavior) and treats evict as all-history-for-this-eid. The
purposeful design: evict is for GDPR / "we never want this data again",
not for retroactive vt-supersession.

## 3. XTDB v2

Repo: `/home/christian-weilbach/Development/xtdb2`. README confirms
the Apache-2.0 Arrow rewrite; SQL+XTQL surface; Java/Kotlin
implementation.

### A. Supersession mechanism

READ-time polygon — this is the system that originated the model
datahike's PR copies. `ScanCursor`
(`core/src/main/kotlin/xtdb/operator/scan/ScanCursor.kt:19-116`) is
the per-table read path. It walks the per-iid event stream
(newest-system-time first, line 46-47 priority queue with
`EventRowPointer.comparator()`), then for each event calls
`PolygonCalculator.calculate(evPtr)` (line 68).

`PolygonCalculator` (`core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt`)
holds a single `Ceiling` per iid that tracks the still-valid vt-ranges
not yet superseded by a later event. For each event:
1. If iid changed, reset the ceiling (line 24-27).
2. If op is `erase` (GDPR physical removal), reset and skip
   (line 29-33).
3. Otherwise compute the Polygon for this event = the vt-ranges still
   owned by this event (Polygon.calculateFor in
   `core/src/main/kotlin/xtdb/bitemporal/Polygon.kt:20-44`), then update
   the Ceiling with this event's `(systemFrom, validFrom, validTo)`
   (`Ceiling.applyLog` in `Ceiling.kt:78-122`).
4. Return the polygon — possibly multiple vt-ranges if the event was
   partially superseded by later events.

The scanner emits one row per polygon vt-range
(`ScanCursor.kt:74-87`), so a "single PUT, later superseded in part"
event yields multiple output rows, each with its own
`[_valid_from, _valid_to)` reflecting what survived.

Back-correction example: tx-1 PUT salary=100k with vf=Jan, vt=∞. tx-2
PUT salary=90k with vf=Apr, vt=∞. At read time the scanner sees tx-2
first (newer systemFrom). Polygon for tx-2 = `[Apr, ∞)`, ceiling
updates accordingly. Then sees tx-1; polygon for tx-1 = `[Jan, Apr)`
because `[Apr, ∞)` is now in the ceiling. Query at vt=May returns 90k.
Query at vt=Feb returns 100k.

### B. Mutability of prior tx-time metadata

NO — and this is a hard ban, enforced in two places:

1. `LiveTable.kt:83-125` — `logPut` / `logDelete` / `logErase` always
   write a NEW event row with `systemFrom = txKey.systemTime`. There
   is no API to mutate the systemFrom or vt of a prior event.
2. SQL UPDATE statements explicitly forbid touching `_*` columns:
   `core/src/main/clojure/xtdb/sql.clj:2646-2651` — `forbidden-update-col?
   = (str/starts-with? (str col) "_")`, and `_valid_from`, `_valid_to`,
   `_system_from`, `_system_to` all match. Same for INSERT
   (`forbidden-insert-col?` line 2649-2651 explicitly excludes
   `_valid_from`/`_valid_to` from the forbidden set — they're allowed
   on insert, forbidden on update). PATCH (`forbidden-patch-col?` line
   2653-2655) forbids all `_*` columns including the temporals.

The `_valid_from` / `_valid_to` of a prior event are immutable. The
*only* way to "close" a prior event's vt-contribution to a window is
to issue a new event (PUT / DELETE / PATCH) for the same iid with a
window that overlaps; `PolygonCalculator` then computes the closure
at read time.

`vf < vt` validation: enforced once, at write, in
`core/src/main/clojure/xtdb/indexer.clj:173-177`:

```clojure
(when-not (> valid-to valid-from)
  (throw (err/incorrect :xtdb.indexer/invalid-valid-times
                        "Invalid valid times"
                        {:valid-from (time/micros->instant valid-from)
                         :valid-to (time/micros->instant valid-to)})))
```

The check appears in `->put-docs-indexer` (line 173-177),
`->delete-docs-indexer` (line 214-217), and `->upsert-rel-indexer`
(line 292-296). Because prior events' vt columns are immutable, the
write-time check is sufficient — there's no path to compose two writes
into an invalid window the way datahike's retroactive `:db.valid/to`
write can.

The "FOR PORTION OF VALID_TIME UPDATE" SQL surface is implemented as
*compose-via-new-events*: `dml-stmt-valid-time-portion` in
`core/src/main/clojure/xtdb/sql.clj:2587-2597` plans the DML as a
self-join that emits new rows with the projected vt-window, then the
indexer writes them — prior events stay untouched.

### C. New-entities-per-run pattern

Same as datahike polygon and XTDB v1: supersession keys on iid, and a
new iid is a different entity. A consolidation that allocates a fresh
iid per run will double-count. XTDB v2 doesn't help. (It does help in
the closely-related "two PUTs with the same iid in different vt
windows" case — that's what the polygon is *for* — but it can't infer
"these two iids represent the same logical fact".)

### D. Same-vt edge case

tx-1 PUT iid=X with vf=jan-31, value=A. tx-2 PUT iid=X with vf=jan-31,
value=B (correction). At read time:
- Query as of `system-time = tx-1` and vt=jan-31: scanner only sees
  tx-1's event (tx-2's systemFrom > tx-1). Polygon = `[jan-31, ∞)`,
  returns value A.
- Query as of `system-time = tx-2` and vt=jan-31: scanner sees tx-2
  first (newer systemFrom). Ceiling absorbs `[jan-31, ∞)`. Then sees
  tx-1; its polygon = empty (it was entirely superseded). Returns
  value B.

`temporalBounds.intersects` (`ScanCursor.kt:80-82`) bounds the polygon
by the query's `system_time` / `valid_time` settings, so the user can
explicitly bound the supersession horizon.

### E. Audit chain interaction

The append-only event log + the systemFrom-keyed compaction in
`SegmentMerge.kt` means every correction is a first-class event in the
log and remains queryable via `FOR SYSTEM_TIME AS OF <past>`. The
`erase` op (`LiveTable.kt:113-125`) is the only path that produces a
*non*-auditable mutation — it writes a sentinel `erase` event that
`PolygonCalculator.calculate` interprets as "reset the iid and skip
forever" (`PolygonCalculator.kt:29-33`); the original events are
physically removed by compaction. This is the GDPR safety valve and is
explicitly documented as "irrevocably erases data" in the SQL ref
(`docs/src/content/docs/reference/main/sql/txs.adoc:94-106`).

## 4. Stratum

Repo: `/home/christian-weilbach/Development/stratum`. Columnar
analytics engine; secondary index for datahike. Implements SCD2 +
SQL:2011 `FOR PORTION OF VALID_TIME` DML.

### A. Supersession mechanism

WRITE-time SCD2-on-both-axes. Stratum's `:bitemporal` config attaches
two axes — `:valid` (vt) and `:system` (st) — and the write primitives
(`append!`, `upsert!`, `retract!`, `bounded-update!`) maintain a
"one-row-per-validity-slice, one-replacement-per-system-time-revision"
discipline.

The mechanism: prior rows in the dataset whose vt-window overlaps a
new write are touched in two ways:
1. Their `_system_to` is set in place to `system-now`
   (`src/stratum/dataset.clj:1283`) so any subsequent `FOR SYSTEM_TIME
   AS OF <after system-now>` query no longer sees them.
2. A replacement row is appended (`dataset.clj:1286-1291`) with the
   corrected `[vf, vt)`, the merged data, and a fresh `_system_from =
   system-now`, `_system_to = MAX`.

The prior row stays physically present at the original index `i` — its
`_valid_from`, `_valid_to`, content, and `_system_from` are
untouched — so a `FOR SYSTEM_TIME AS OF <system-now -1>` query sees
the pre-correction state.

For the brief's back-correction example: `tx-1: salary=100k, vf=Jan,
vt=∞`. Stored as one row: `_valid_from=Jan, _valid_to=MAX,
_system_from=t1, _system_to=MAX, salary=100k`. Now `tx-2: salary=90k,
vf=Apr`. In `upsert!` (`dataset.clj:311-452`) the prior row is
classified `:close-safe` (open, row-vf=Jan < new-vf=Apr); replacements
emitted are two slices:
- `{:vf Jan, :vt Apr, data=100k}` — the pre-Apr part of the old value
- `{:vf Apr, :vt MAX, data=90k}` — the new corrected value

In the bitemporal path (`dataset.clj:413-435`), the prior row's
`_system_to` is closed to t2; both slices are appended with
`_system_from=t2`. Query at vt=May returns 90k. Query at vt=Feb
returns 100k. Query `FOR SYSTEM_TIME AS OF t1, vt=May` returns
100k (the old open row is still visible at t1).

For the simpler valid-only path (`dataset.clj:436-451` — no system
axis configured), the prior row's `_valid_to` is mutated in place to
Apr; the new 90k row is appended. There's no system-time replay
capability in that mode.

### B. Mutability of prior tx-time metadata

Mixed. In the **bitemporal mode**, prior rows' `_valid_from` and
`_valid_to` are NEVER mutated; only `_system_to` is closed in place.
In the **valid-only mode**, prior rows' `_valid_to` IS mutated in
place — but this is exactly the SCD2-close behavior the kontor design
needs, and is what makes the "valid-only" path lossy with respect to
system-time history (you cannot re-query the pre-correction state).

Cross-tx `vf < vt` validation: enforced in `validate-period!`
(`dataset.clj:1094-1107`) at every entry point — `append!`
(`dataset.clj:286`), `retract!` (`dataset.clj:481`), `bounded-update!`
(`dataset.clj:1343`). The check is `(< from to)`, axis-aware. It
fires on every write — including the *successor* rows that
`replace-row-bitemporal!` constructs — so even an internal coding bug
can't produce a reverse-window row.

The closest stratum analog to datahike's "retroactively close a prior
tx's vt-to" is `bounded-update!` (`dataset.clj:1306-1396`), which
implements SQL:2011 `UPDATE … FOR PORTION OF VALID_TIME FROM x TO y
SET col=val WHERE p`. It:
1. Captures matching rows that overlap [x, y).
2. Calls the `retract!` bounded path to surgically slice the prior
   rows' vt-windows (`dataset.clj:493-580`).
3. Appends a new slice per captured row with the updated data and the
   overlap window.

Crucially, the bounded-retract path NEVER edits a prior row's
`_valid_from` in place in bitemporal mode — it appends a replacement
row and closes the prior `_system_to`. In valid-only mode it does
edit `_valid_to`/`_valid_from` in place on the prior row, but the
period validator in `dataset.clj:481` rejects zero-width and reverse
windows before the surgery commits.

### C. New-entities-per-run pattern

Stratum SCD2 is keyed by the user's `:where` predicate, not by a
synthetic iid. If a consolidation issues `upsert!` with a stable
`:where [:= :group "Q1-translation"]` predicate, the prior translation
row is automatically closed and superseded. If the consolidation
issues `append!` with a fresh row each time (no stable key), the rows
accumulate and double-count. Same answer as the polygon engines —
*stable keys are the consumer's responsibility* — but stratum's
predicate-based UPSERT makes the stable-key contract explicit at the
API surface.

(Compare datahike-via-stratum-secondary: the adapter
`src-secondary/datahike/index/secondary/stratum.clj:923-948` closes
the OPEN row for each eid in `pending-adds` ∪ `pending-retracts` —
keyed by eid. So a consolidation that allocates a new eid per run
double-counts; one that reuses the eid is auto-superseded.)

### D. Same-vt edge case

tx-1 `upsert!` at vt=jan-31. tx-2 `upsert!` at vt=jan-31 with new
value. `upsert!` classifies the prior row: row-vf=jan-31, open
`_valid_to=MAX`, new-vf=jan-31 → the predicate `(< row-vf
close-vt-val)` at `dataset.clj:361` returns FALSE → classified
`:overlaps`. Without `:auto-split? true`, stratum throws "upsert!
would overlap existing rows' vt-windows" (`dataset.clj:391-397`). With
`:auto-split? true`, the row is dropped (because row-vf=new-vf, line
384-386) and the new row appended. In the bitemporal path
(`dataset.clj:427-431`), the prior row's `_system_to` is closed and a
new row appended at `[jan-31, MAX)` with `_system_from = t2`.

Query at vt=jan-31, `FOR SYSTEM_TIME AS OF t1`: returns tx-1's value
(prior row still open at t1). Query at vt=jan-31, `FOR SYSTEM_TIME AS
OF t2`: returns tx-2's value.

The default behavior of *throwing on same-vt overlap unless explicit
auto-split* is interesting — it forces the consumer to opt into the
surgery, which is the opposite of polygon engines that silently
supersede. For an accounting kernel this is probably the right
default: silently superseding posted data is a class of footgun.

### E. Audit chain interaction

Stratum integrates with datahike via the secondary-index adapter
(`src-secondary/datahike/index/secondary/stratum.clj`). In that
integration path the audit chain story is exactly datahike's audit
chain — stratum's column updates are derived from datahike's tx log
and replayable. The SCD2 surgery (close `_system_to`, append
replacement) is performed during datahike's tx-commit, so the
correction is a first-class datahike commit and a first-class stratum
row mutation; both are recoverable from the log.

Standalone stratum (used directly as a dataset) doesn't itself
maintain a chain; it relies on the consumer (datahike, pg-datahike)
for the auditable log. The combination is "datahike log + stratum
SCD2 storage", which composes cleanly.

## 5. Cross-cutting comparison table

| Dim | datahike feature/bitemporal-v1 | XTDB v1 | XTDB v2 | Stratum |
|---|---|---|---|---|
| **A. Supersession** | Read-time polygon (`mk-vt-pred`, `api/impl.cljc:229`) | Write-time SCD2 via `put-delete-coords` (`tx.clj:92-131`) — bake the polygon on PUT | Read-time polygon (`PolygonCalculator.kt`, `Ceiling.kt`) — the system datahike copies | Write-time SCD2-on-both-axes (`dataset.clj:1262-1291`) — close `_system_to` in place, append successor row |
| **B. Prior tx-meta vt mutability** | YES, accepted, no cross-tx vf<vt guard (`schema.cljc:127-134` + transactor) | NO — tx ops have no such surface (`tx.clj:133-186`); supersession is by new PUT | NO — `_*` columns immutable; SQL `forbidden-update-col?` (`sql.clj:2647`); valid-times rejected at indexer (`indexer.clj:173`) | Bitemporal: NO (only `_system_to` mutated in place); Valid-only: YES on prior row's vt-to (`dataset.clj:438-451`). `validate-period!` blocks reverse windows (`dataset.clj:1094`) |
| **C. New-entities-per-run** | Double-counts (different `(e,a,v)`) | Double-counts (different eid) | Double-counts (different iid) | Double-counts unless `upsert!` with stable `:where` predicate; bitemporal `upsert!` rejects same-vt overlap by default |
| **D. Same-vt edge case** | Correct: polygon picks larger tx-id; `(d/as-of) (d/valid-at)` composes | Correct: bitemap index keys on (eid, vt, tx-id); seek tiebreaks on tx-id (`index_store.clj:830-845`) | Correct: scanner orders by systemFrom desc per iid; older event's polygon = empty | Correct: prior `_system_to` closed; `FOR SYSTEM_TIME AS OF <past>` returns pre-correction; auto-split required to avoid throw |
| **E. Audit of correction** | Correction is a new commit; tx-entity datoms recorded; `audit/verify-chain` ok. Intent NOT recorded | Correction is a new log entry; bit-perfectly replayable | Correction is a new log event; `FOR SYSTEM_TIME AS OF` replays pre-correction | Closure + replacement recorded via parent log (datahike); standalone stratum delegates audit upstream |

## 6. Recommendation for datahike PR

### The cross-tx `vf < vt` gap: P0 ship-blocker

By the standards of the three comparators this is a P0. XTDB v2's
`indexer.clj:173-177` check is the single closest analog and it's
*always* enforced at the write boundary. Stratum's `validate-period!`
runs on every write entry point including SCD2 replacement-row
construction. XTDB v1 prevents the case structurally by not exposing a
"rewrite prior vt" operation. Datahike's current PR is the only system
where the write boundary check runs on the new tx's own meta but not
on the closure formed by editing a prior tx's meta. That's a
silent-corruption gap — a careless `[:db/add prior-tx :db.valid/to t]`
with `t <= prior-tx's :db.valid/from` zeroes the prior tx's
contribution to every `valid-at` query.

**Fix**: in `db/transaction.cljc`, when validating a tx, detect writes
to `:db.valid/from` or `:db.valid/to` on a tx-entity that's not the
*current* tx, look up the existing `(other-attr, value)` on that
tx-entity in the same db value the transactor is reading, and reject
if the resulting closure would have `vf >= vt`. Cheap — single AVET
lookup per such write.

### Retroactive mutability of prior tx-meta: P1 (feature with a sharp edge)

This is more nuanced than the P0. XTDB v2 forbids it outright (P0 by
their lights); stratum bitemporal mode declines to do it (P0 by their
lights); XTDB v1 has no mechanism (P0 by their lights). Datahike is
the lone permissive system. There are two ways to read the
permissiveness:

1. **Feature**: it composes with datahike's existing "schema attrs are
   normal datoms" philosophy. A user who knows what they're doing can
   model `:db.valid/to` corrections without inventing a new tx op.
2. **Sharp edge**: it's *too* expressive — there are at least three
   distinct intents ("close validity of a posted tx because we
   discovered the actual window is different", "fix a typo in tx-meta
   of an in-flight draft", "supersede this tx") and the same on-disk
   form expresses all three indistinguishably. Audit chains can't
   replay intent.

**Recommendation**: keep the mechanism but add a named helper —
something like `(d/close-validity conn prior-tx new-vt)` — that wraps
the `[:db/add prior-tx :db.valid/to new-vt]` write with:
- the cross-tx `vf < vt` validation from above
- an optional `:reason` attr recorded on the new tx (`:audit/reason`,
  free text) so the intent is captured in the audit chain
- a precondition that the prior tx is "posted" (datahike has no such
  notion today but kontor does via `:posting/posted-at`); guard at
  the datahike layer is structural — "the prior tx exists and has the
  attribute being closed", at most.

The bare datom form should still work (don't break composability), but
the helper documents the intended use and gets the validation by
default. Mirrors XTDB v2's choice to expose `DELETE FOR PORTION OF
VALID_TIME` as a named SQL op rather than only as a low-level event.

### Named supersession operations the PR should mirror

XTDB v2 doesn't *have* a dedicated "close validity" op — its model is
"post a new event that overlaps and the polygon computes the closure".
The named operations it exposes are:
- `PUT … FOR PORTION OF VALID_TIME …` — write a new event with an
  explicit window
- `DELETE … FOR PORTION OF VALID_TIME …` — write a delete event with
  an explicit window
- `PATCH … FOR PORTION OF VALID_TIME …` — upsert with explicit window
- `ERASE` — physical GDPR removal

Datahike's PR already gives the first three (the same effect is
achievable by writing `:db.valid/from`/`:db.valid/to` on the new tx's
meta). It does NOT give the fourth — `:db/purge` is the closest, and
ADR-007 already documents that purge is the auditable GDPR path. The
gap is "supersede" — which datahike's polygon achieves implicitly via
"post a new tx with overlapping vt", same as XTDB v2.

The one named operation that stratum *does* expose and the others
don't is `bounded-update!` (`dataset.clj:1306-1396`) — the SQL:2011
sequenced UPDATE that surgically replaces a slice of validity with
new data. Datahike achieves this by composing `[:db/retract +
:db/add]` plus the tx-meta `:db.valid/from`/`:db.valid/to`, but the
composition is non-obvious. Worth a documented recipe (or a thin
helper) in the merge-time docs.

### Summary of pre-merge asks

1. **(P0)** Cross-tx `vf < vt` validation. Reject `[:db/add prior-tx
   :db.valid/{from,to} t]` if the closure produces `vf >= vt`.
2. **(P1)** Named `d/close-validity` / `d/supersede` helper that wraps
   the retroactive vt-to write with the validation, an optional
   `:audit/reason`, and a docstring stating the intent. The bare
   datom form continues to work.
3. **(P2)** Documented recipe for the SQL:2011 bounded-update pattern
   on top of the existing primitives (or a thin `(d/bounded-update!
   conn pred [vf vt] new-attrs)` helper that desugars to a multi-op
   tx).
4. **(P2)** Audit chain docs should explicitly call out that
   retroactive vt mutations ARE recorded as new commits (which they
   already are — this is just docs work).

The polygon itself is correct and the architecture is sound. The PR
is one validation guard plus optional ergonomics away from being
defensible against the comparators.

## Sources

### Datahike feature/bitemporal-v1
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/schema.cljc:127-134` — `:db.valid/{from,to}` schema
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/api/impl.cljc:229-267` — `mk-vt-pred` polygon predicate
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/api/impl.cljc:269-317` — `valid-at` wrapper, docstring claiming XTDB v2 mirror
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:545,619,632,923-948` — stratum secondary integration, vt-aware search path

### XTDB v1
- `/home/christian-weilbach/Development/xtdb/README.md:30-37` — version 1.23.1
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/tx.clj:92-131` — `put-delete-coords` SCD2 rewrite at write time
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/tx.clj:133-186` — supported tx ops (none mutate prior vt)
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/kv/index_store.clj:819-868` — `entity-as-of-resolver` / `entity-as-of` plain bitemap seek (no polygon at read time)
- `/home/christian-weilbach/Development/xtdb/docs/concepts/modules/ROOT/pages/bitemporality.adoc:38-110,400-409` — bitemporal model, retroactive data structures

### XTDB v2
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt` — read-time polygon driver
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Polygon.kt` — polygon shape (immutable per-event computation)
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Ceiling.kt:78-122` — ceiling = "vt-ranges not yet superseded" data structure
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/operator/scan/ScanCursor.kt:19-116` — polygon invocation per scan
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/indexer/LiveTable.kt:83-125` — `logPut`/`logDelete`/`logErase` append-only event API
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/indexer.clj:97-251` — INSERT/DELETE/ERASE/UPSERT indexer paths
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/indexer.clj:173-177,214-217,292-296` — `vf <= vt` rejection at every write entry point
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/sql.clj:2587-2615` — `dml-stmt-valid-time-portion` plans FOR PORTION OF VALID_TIME
- `/home/christian-weilbach/Development/xtdb2/core/src/main/clojure/xtdb/sql.clj:2646-2667` — `forbidden-update-col?` / `forbidden-patch-col?` block writes to `_*` columns
- `/home/christian-weilbach/Development/xtdb2/docs/src/content/docs/concepts/key-concepts.adoc:47-93` — bitemporal model, built-in WITHOUT OVERLAPS
- `/home/christian-weilbach/Development/xtdb2/docs/src/content/docs/reference/main/sql/txs.adoc:33-106` — SQL UPDATE/PATCH/DELETE/ERASE semantics

### Stratum
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:105-150` — `IDataset` protocol: `append!`, `upsert!`, `retract!`
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:251-307` — `append!` impl, axis validation
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:311-452` — `upsert!` impl, close-safe/overlap/auto-split classification, bitemporal vs valid-only paths
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:456-647` — `retract!` impl, bounded vs open-window, bitemporal vs valid-only
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:1094-1107` — `validate-period!`
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:1109-1122` — `system-now-from-tx-meta`
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:1247-1291` — `replace-row-bitemporal!` — close `_system_to` in place, append replacements
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:1306-1396` — `bounded-update!` — SQL:2011 FOR PORTION OF VALID_TIME UPDATE

### Prior research notes (for cross-context)
- `doc/research/60-xtdb-vt-feature-comparison.md` — XTDB v1/v2 vt feature gap table
- `doc/research/63-xtdb-v2-system-time-semantic.md` — XTDB v2 system-time semantics
- `doc/research/66-datahike-vs-xtdb-bitemporal-gaps-2026-05.md` — known gaps as of May 2026
- `doc/research/67-bitemporal-open-issues-tracker.md` — open issues tracker

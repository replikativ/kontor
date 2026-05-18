---
date: 2026-05-15
agent: research
title: Stratum valid-time — write-path follow-ups after `feature/valid-time` PR
status: draft
---

Note: numbered 58 because `57-stratum-valid-time-plan.md` already exists on
the same topic — this is the *follow-up audit* of the write paths after the
PR landed, not a duplicate of the plan.

# Stratum valid-time — write-path follow-ups

The maintainer landed three commits on `feature/valid-time`:

- `2f8facf` — `:metadata {:valid-time {…}}` column convention on
  `make-dataset` (`src/stratum/dataset.clj:436-483, 489-565, 670-716`).
- `6ea05d1` — bench tier 10 + baseline snapshot.
- `88e2723` — docs (`doc/dataset.md` § Valid-Time Window, new `doc/audit.md`).

The PR is **purely a column-naming + temporal-unit-stamping convention**.
There is no automatic vt maintenance on writes: `_valid_from` and
`_valid_to` are just two more `:int64` columns that the caller must
populate. The SCD2 close-on-update semantic lives in the consumer adapter
(`datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:826-962`,
`vt-persist-transient-stratum-index`), not in stratum core. This note
audits each write surface and proposes where vt should grow.

## Surfaces inventory

| # | Surface | File | Today's vt behavior |
|---|---|---|---|
| 1 | `make-dataset` constructor | `dataset.clj:489` | Stamps `:temporal-unit` on the two vt cols; validates type. |
| 2 | `assoc`/`add-column` (persistent) | `dataset.clj:302` | No vt awareness; never re-runs `apply-vt-config`. |
| 3 | `append!` (transient) | `dataset.clj:183-193` | Hard-requires *every* column, including `_valid_from`/`_valid_to`. Silent 0 if absent only if caller wraps. |
| 4 | `set-at!` (transient) | `dataset.clj:176-181` | Generic typed setter; no vt awareness. |
| 5 | `sync!` / `load` | `dataset.clj:199-260, 670-716` | Round-trips the `:valid-time` map and re-stamps `:temporal-unit` on load. |
| 6 | SQL `INSERT` | `sql.clj:2353-2385`, `server.clj:342-397` | Caller supplies all values; no auto-stamp. |
| 7 | SQL `UPDATE` | `sql.clj:2387-2413`, `server.clj:524-674` | In-place column rewrite; no SCD2 close-and-reopen. |
| 8 | SQL `UPDATE … FROM` (joined) | same | Same as UPDATE. |
| 9 | SQL `DELETE` | `sql.clj:2415-2423`, `server.clj:676-791` | Physical row removal; no logical close. |
| 10 | SQL `UPSERT` (INSERT … ON CONFLICT) | `sql.clj:2353-2385`, `server.clj:399-522` | Same; no vt close on conflicting row. |
| 11 | SQL `CREATE TABLE` | `sql.clj:2297-2313`, `server.clj:279-300` | Stamps `:column-schema {col {:temporal-unit U}}` as metadata; no `:valid-time` map. |
| 12 | `read_csv` table function | `sql.clj:2559-2570`, `csv.clj:154-201` | No metadata bridging; `from-csv` always emits `:source-type :csv`. |
| 13 | `read_parquet` / `parquet-dataset` | `parquet.clj:465-595, 1602-1718` | No vt detection; parquet INT96/TIMESTAMP columns get typed but never recognised as vt windows. |
| 14 | `index-parquet!` (konserve ingest) | `parquet.clj:275-463` | Emits `:source-type :parquet` only. |
| 15 | Planner / kernel routing | `query/expression.clj:309-562, 834+`, `query/group_by.clj:285-465`, `query/prepare.clj:347`, `query.clj:529` | Sees `:temporal-unit`; doesn't yet know two columns form a vt *window*, no pruning advice tied to the pair. |

## Per-surface analysis

### 1. `make-dataset` (the PR's home turf)

`apply-vt-config` (`dataset.clj:436-474`) is correct as far as it goes — it
errors loudly on missing columns and type conflicts. The gap: `:valid-time`
is treated as opaque metadata everywhere else in the codebase, so once a
dataset escapes the constructor, downstream surfaces have no easy way to
*react* to its vt-aware status. Surface this with predicates `vt-aware?`
and `vt-cols` (≈10 LOC) next to `vt-config`.

### 2. `assoc` (`dataset.clj:302-336`)

A user can `assoc` a column over `_valid_from` and clobber the
`:temporal-unit` because `assoc` doesn't re-run `apply-vt-config`. Smallest
fix: if `(get metadata :valid-time)` mentions `k`, re-stamp the new
column's `:temporal-unit`, or refuse if the type is wrong. ≈8 LOC. **Lives
in stratum core** — pure self-consistency.

### 3. `append!` (`dataset.clj:183-193`)

Today, `append!` throws if any column is missing from the row-map
(`dataset.clj:189`). For a vt-aware dataset that means the caller must
*always* supply `_valid_from`/`_valid_to`. That's correct *as a default*
but defeats the ergonomic win the convention is supposed to deliver. Two
sensible additions:

- **Auto-fill mode**: when `(vt-config ds)` is non-nil and the row-map
  omits the vt cols, fill `_valid_from = (current-micros)` and
  `_valid_to = Long/MAX_VALUE`. Behind an opt-in dataset metadata flag
  (`:valid-time {… :auto-stamp? true}`) so silent semantics aren't
  imposed on existing callers. ≈25 LOC + tests.
- **Explicit `vt-append!`**: `(vt-append! ds-t {:eid 1 :salary 110000}
  {:as-of micros})` — does the SCD2 dance (find current open row for
  `:eid`, mutate `_valid_to` via `set-at!`, then `append!` the new row).
  This is exactly what `vt-persist-transient-stratum-index`
  (`datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:888-962`)
  does — about 75 LOC there. **Design call**: lift a *thin* version into
  stratum core (~60 LOC) because two adapters (datahike, future SQL
  `UPDATE … FOR PORTION OF VALID_TIME`) will both want it; keep
  attribute-merging in the adapter where ident/ref-type knowledge lives.

### 4. `set-at!` (`dataset.clj:176-181`)

The single-cell mutator is fine as a low-level primitive — vt callers must
already understand they're closing a window. Document this; no code change.

### 5. `sync!` / `load`

Already vt-aware (the PR's main correctness move). No followup.

### 6-7-8. SQL `INSERT` / `UPDATE` / `UPDATE … FROM`

The server-side table-registry path stores arrays plus side metadata
`{:column-schema {col {:temporal-unit U}}}` (`server.clj:298, sql.clj:1449`).
The `INSERT` branch (`server.clj:342-397`) reallocates and copies arrays
with no awareness that two cols are bound together. **Two ladders**:

- **Cheap ladder — auto-stamp on plain `INSERT`**: if `:column-schema`
  also carries `:valid-time {…}` and the row doesn't supply
  `_valid_from`/`_valid_to`, fill `now` / `Long/MAX_VALUE`. ≈40 LOC in
  `server.clj`. Lives in core; trivially useful for any SQL user.
- **Expensive ladder — SQL:2011 syntax**: `INSERT … FOR PORTION OF
  VALID_TIME FROM t1 TO t2`, `UPDATE … FOR PORTION OF VALID_TIME …`,
  `DELETE … FOR PORTION OF VALID_TIME …`. SQL:2011 close-and-reopen on
  UPDATE is exactly the SCD2 pattern. JSqlParser does not recognise this
  grammar today; either pre-tokenise (regex strip + manual rewrite) or
  extend JSqlParser. **Design call**: this *should* live in stratum core
  because the grammar belongs to the SQL surface, but the cost is high
  (~250 LOC + a parser branch). Worth a separate stage. Without it, every
  consumer wires their own SCD2; the cheap ladder is the pragmatic
  shipping target.

### 9-10. SQL `DELETE` / `UPSERT`

`DELETE` physically removes rows (`server.clj:746-787`). For a vt-aware
table, the audit story breaks: a posting that was logically true for a
window can disappear without a trace. Same options as INSERT: cheap
auto-close on `DELETE FROM t WHERE …` (set `_valid_to = now` instead of
removing); explicit SQL:2011 syntax later. **Design call**: this is the
exact analog to kontor's "no silent retract" ADR-007 — when stratum is
deployed as kontor's substrate, a SQL `DELETE` against a vt-aware table
should refuse-by-default and require explicit opt-in to physical purge.

`UPSERT` (`server.clj:399-522`) has the same gap: a conflict triggers
in-place mutation of the existing row's columns, which destroys the prior
vt window. The natural rewrite is "close the open row, append the new
one" — and at that point UPSERT is just SCD2 with a particular conflict
key. Worth treating as one feature.

### 11. `CREATE TABLE`

Today only stamps `:temporal-unit` per column (`server.clj:281-298`). A
small extension: a `CREATE TABLE … WITH (valid_time = (from_col,
to_col))` clause stores `:valid-time {:from-col … :to-col … :unit
:micros}` in the table's column-schema metadata, so the INSERT/DELETE
auto-stamp paths above know which two columns to manage. ≈20 LOC + grammar
hop. Alternative: piggyback on a `PERIOD FOR VALID_TIME (from_col,
to_col)` clause (SQL:2011 syntax) — same metadata target, more standard.

### 12. CSV ingest

`from-csv` always emits `:source-type :csv` metadata
(`csv.clj:198-201`). For a CSV named `salaries_history.csv` with columns
`valid_from`, `valid_to`, no vt-mode lights up. A heuristic-free
opt-in is the right move: extend `from-csv` to accept `:valid-time
{:from-col :_valid_from :to-col :_valid_to :unit :micros}` and pass it
through to `make-dataset`'s metadata. ≈8 LOC. **No magic detection** —
column-name sniffing is a footgun (legitimate non-temporal columns named
`valid_from` exist in user data).

### 13-14. Parquet ingest

`parquet-dataset`, `from-parquet`, and `index-parquet!` all emit
`{:source-type :parquet}` only (`parquet.clj:458, 591, 1703`). Parquet
files written by Iceberg / Delta / hand-rolled bitemporal pipelines often
carry vt-window columns by convention; parquet KeyValue metadata is the
standard channel for "this is a vt table, these are the cols." Two pieces:

- **Read side**: probe `FileMetaData.keyValueMetaData` for a key like
  `stratum.valid_time` whose value is `{:from-col … :to-col … :unit
  :micros}` (printed EDN or JSON) and pass it to `make-dataset`. ≈30 LOC.
- **Write side**: stratum has no parquet *writer* today; the question is
  moot until that exists. Defer.

If the parquet has *system-time + valid-time* (bitemporal in the SQL:2011
sense), today stratum can store the four columns but only one window
gets `:valid-time` recognition. The metadata schema should generalize:
`{:valid-time {…} :system-time {…}}`, keyed by which axis. Mark this as
forward-compat in the spec; don't implement system-time today (kontor
uses the konserve commit graph as its system-time axis, not column pairs).

### 15. Planner / query side

`prepare.clj:347` and `query.clj:529` already harvest `:temporal-unit`
into `*columns-meta*`, so date kernels at correct precision are routed
without any extra work — the PR's choice to stamp on cols means
`DATE_TRUNC(_valid_from)` already works. What's **missing**: the planner
doesn't know that `[_valid_from, _valid_to)` is a *paired* predicate. A
`WHERE _valid_from <= ts AND _valid_to > ts` query could be detected and
rewritten as a single window-overlap predicate that prunes on both zone
maps at once (`_valid_from <= ts ≤ _valid_to` ⟹ chunks with
`min(_valid_from) > ts` OR `max(_valid_to) ≤ ts` are dead). Wins on tier
10 of the bench. This is a *light* optimization — ≈50 LOC in
`query/predicate.clj` — but the win is shape-specific. Mark as P2.

## Recommended next moves, ordered by leverage

1. **Make `append!` silent-zero impossible on vt-aware datasets.**
   Today, if a caller `append!`s without `_valid_from`, the helpful error
   `"append! requires values for all columns"` does fire
   (`dataset.clj:189`) — so this is actually not a silent zero. **But**
   if the caller follows `vt-aware?` semantics and explicitly passes
   `:_valid_from 0`, they silently insert an epoch-anchored row. Add an
   opt-in `:auto-stamp? true` flag to the `:valid-time` config that lets
   `append!` fill `now` / `MAX_VALUE` when omitted, and warn on a
   zero/MIN_VALUE `_valid_from` when the dataset is vt-aware. ≈25 LOC,
   stratum core. **High leverage**, low cost, no API break.

2. **Expose `vt-append!` / `vt-update!` SCD2 helpers in `stratum.dataset`.**
   Lift the close-old-open-new helper out of the datahike adapter
   (`datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:888-962`)
   into stratum core as a generic two-function API on transient
   datasets. Adapter keeps the attribute-merging logic on top. ≈75-100
   LOC, stratum core. This is what kontor's posting-write path would
   want directly, without the datahike round-trip.

3. **Cheap-ladder SQL INSERT/DELETE auto-stamp.** Wire
   `:column-schema` to carry `:valid-time` from CREATE TABLE through to
   the INSERT branch (`server.clj:342-397`) and the DELETE branch
   (`server.clj:676-791`). Default: auto-fill on INSERT, refuse on
   DELETE (require explicit `WITH PHYSICAL_PURGE`). ≈80 LOC in
   `server.clj` + 20 LOC grammar work in `sql.clj`. Stratum core.

4. **CSV/Parquet metadata pass-through.** Add `:valid-time` keyword arg
   to `from-csv` / `from-parquet` / `index-parquet!` / `parquet-dataset`;
   for parquet, also read the `stratum.valid_time` KeyValue metadata if
   present. ≈40 LOC across csv.clj + parquet.clj. Stratum core.

5. **Planner pair-aware pruning (P2, deferrable).** Detect
   `WHERE from-col <= ts AND to-col > ts` and rewrite to a window-overlap
   predicate that prunes both zone maps simultaneously. ≈50 LOC in
   `query/predicate.clj`. Win is workload-shape-specific; ship after
   benchmarks justify it.

## Open questions for the maintainer

- **Where should SCD2 helpers live?** Stratum core (my recommendation:
  pure-data SCD2 with no attribute-semantics knowledge) vs. continue to
  live in adapters? The argument *for* core: two adapters (datahike,
  SQL:2011 UPDATE … FOR PORTION OF VALID_TIME) will both want the
  primitive. *Against*: it pulls "what's an entity?" into stratum, which
  has been deliberately schemaless. A compromise is a primitive
  `close-and-open!` that takes an explicit predicate
  `(fn [row-idx] true-if-this-is-the-open-row-for-the-entity)` — keeps
  entity semantics in the caller.

- **Is `:column-schema` table-metadata the right channel?** Today it's
  attached as Clojure `meta` on the table-registry map
  (`server.clj:298`). Extending it to carry `:valid-time` works but the
  channel is fragile (anything that `swap!`s the table without
  preserving meta drops the schema). Worth a dedicated registry value
  shape `{:cols {…} :schema {…}}`.

- **SQL:2011 `FOR PORTION OF VALID_TIME` grammar — pre-tokenise in
  stratum, or extend JSqlParser?** JSqlParser is the source-of-grammar
  today; extending it upstream is correct but slow. A regex pre-pass to
  strip `FOR PORTION OF VALID_TIME FROM t1 TO t2` into a separate
  payload that the translator picks up is a viable temporary path.
  Decision needed before stage planning.

- **Should `DELETE` on a vt-aware table refuse by default, or auto-close
  by default?** Refusing matches kontor's ADR-007 ("no silent retract")
  but breaks plain SQL tooling; auto-closing is friendlier to existing
  pipelines but silently changes semantics. Recommendation: refuse, with
  `WITH PHYSICAL_PURGE` opt-in *and* a separate `CLOSE … VALID_TIME …`
  surface for the SCD2 close.

- **Audit chain interaction.** `stratum.audit/verify-chain`
  (`doc/audit.md:31-70`) covers tampering on the commit metadata. A
  vt-aware dataset where `DELETE` physically purged rows would still
  verify clean — there's no record of the row ever existing. Worth a
  short note in `doc/audit.md` calling this out: physical purge breaks
  the *historical* audit story even if the commit chain remains
  byte-correct.

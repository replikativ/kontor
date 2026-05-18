---
date: 2026-05-15
agent: research
title: Stratum bitemporal vs XTDB v1/v2 — gap analysis after PR #26 Phase D+
status: draft
---

# 61 — Stratum bitemporal vs XTDB v1/v2: gap analysis

Scope: stratum `feature/valid-time` (PR #26) after commits `2bd1c43` (auto-split + `ds-delete-rows!`), `0eb6c4a` (Phase D — SQL `FOR PORTION OF VALID_TIME`), `75da350` (Phase D+ — bounded `UPDATE` + `INSERT FOR PORTION OF`).

Reference oracles: `~/Development/xtdb` (v1 Datalog), `~/Development/xtdb2` (SQL:2011/XTQL rewrite). Citations are file:line in the XTDB repos; OK internally — this note lives in kontor, not in stratum's published artifacts.

## TL;DR

Stratum's PR #26 ships a respectable SCD2 + non-sequenced DML core: schema, four-way bounded retract (drop / truncate-vt / truncate-vf / split), auto-split overlap policy, SQL `INSERT/UPDATE/DELETE FOR PORTION OF VALID_TIME`. The biggest gaps vs XTDB v2 cluster around **system-time symmetry** (P0 — we silently leave the system-axis stale on every SCD2 surgery), **boundary checks** (P0 — zero-width and reverse periods slip through), and **SELECT-side temporal SQL** (P1 — no `FOR VALID_TIME AS OF`/`BETWEEN`/`ALL` on the read path of the SQL surface; users have only the pg-datahike session vars). Allen interval predicates, `PERIOD` constructor, `RANGE_BINS`, `FOR ALL VALID_TIME`, column-expression period bounds, and SQL-side `ERASE` are absent (P1/P2 each).

Counts: **3 P0**, **6 P1**, **5 P2**.

## P0 — ship-blockers

### P0-1. System-time axis is never closed on SCD2 surgery

**Stratum.** `upsert!` / `retract!` / `bounded-update!` only ever `idx-set!` on the *valid-time* columns. The previous row's `_system_to` stays at `Long/MAX_VALUE` after we close `_valid_to` (`dataset.clj:373-376, 466, 469-470, 474-475, 527-528`). A new row appended through `append!` does get its `_system_from` auto-stamped, but the row whose `_valid_to` we just mutated keeps its *original* `_system_from`/`_system_to`, so it claims to have been "known to the DB" with the new vt-window since the original write.

**XTDB v2.** Every modification closes the prior row's `_system_to` to the transaction's system-time and writes a brand-new row with `_system_from = system-time-µs` / `_system_to = Long/MAX_VALUE`. See `xtdb2/core/src/main/clojure/xtdb/indexer.clj:183-188` (`logPut` writes a fresh row), and the live trie semantics in `LiveTable.kt` (every `logPut`/`logDelete` is an *event* with the tx's system-time, no in-place mutation).

**Why it's wrong.** A `SELECT … FOR SYSTEM_TIME AS OF <T-before-correction>` should return the pre-correction state. With stratum's current write path, the mutated row will still appear at every system-time ≥ its original `_system_from`, even system-times that predate the surgery — so reads at past system-times silently return post-correction data. The audit story (the whole point of system-time) collapses.

**Fix sketch.** In `upsert!`/`retract!`/`bounded-update!`, when the dataset has a `:system` axis, do not `idx-set!` on the old row at all. Instead:

1. Capture the row's full data + original `_system_from`/`_system_to`.
2. `ds-delete-rows!` the original row.
3. Append two rows: the historical fact (`_system_from`=original, `_system_to`=now) and the "current view" with mutated vt-window (`_system_from`=now, `_system_to`=MAX).

That's the SCD2-on-both-axes pattern. Adds one append per mutated row but keeps system-time honest. (XTDB v2 gets this for free because everything is event-sourced; stratum's mutate-in-place storage needs explicit doubling.)

### P0-2. Zero-width and reverse valid-time windows are accepted silently

**Stratum.** `append!` / `upsert!` / `retract!` / `bounded-update!` do not validate `valid-from < valid-to`. A caller passing `:valid-from x :valid-to x` (zero-width) or `:valid-from x :valid-to (- x 1)` (reverse) writes the row as-is. In `bounded-update!` the test `(not (or (<= row-vt new-vf) (>= row-vf new-vt)))` collapses to "never overlaps anything" when `new-vt <= new-vf`, so the retract+append both no-op and silently swallow the bug. (`dataset.clj:1086-1110`)

**XTDB v2.** Every entry point validates `valid-from < valid-to`:
- `indexer.clj:173-177` (put-docs)
- `indexer.clj:214-217` (delete-docs)
- `indexer.clj:292-296` (upsert-rel)
- `indexer.clj:324-328` (delete-rel)

Throws `:xtdb.indexer/invalid-valid-times`. There is a FIXME at `indexer.clj:298-299` lamenting that the generated SQL sometimes produces `vf=vt` rows, but the runtime check is still in place and only `(< vf vt)` survives to the trie.

**Fix sketch.** One helper in `dataset.clj` (`(validate-period! from to)`) called from `append!`, `upsert!`, `retract!`, `bounded-update!`, and the four `*-portion-via-index-backend` helpers in `server.clj:238-354`. Throw with a useful error.

### P0-3. SQL `FOR PORTION OF VALID_TIME FROM x` (no `TO`) is rejected; XTDB allows it (= open-ended)

**Stratum.** The preprocessor regex in `sql/rewrite.clj:296` requires both `FROM` and `TO`: `\bFOR\s+PORTION\s+OF\s+(VALID_TIME|SYSTEM_TIME)\s+FROM\s+`, and the parser walks forward expecting a `TO` keyword (`rewrite.clj:302-305`). A statement `DELETE … FOR PORTION OF VALID_TIME FROM '2024-01-01'` (no `TO`) will be treated as no-period — silently routing to a full-table DELETE on a non-bitemporal path, deleting *every* row matching `WHERE`.

**XTDB v2.** Grammar at `Sql.g4:828-829`: `FOR ('PORTION' 'OF')? 'VALID_TIME' 'FROM' from=staticExpr ('TO' to=staticExpr)?`. Open-ended `FROM` lowers to `[:in vf, end-of-time]`. See `sql.clj:2587-2596`:
```
{vt-col (if to-expr
          (least (coalesce vt-col xtdb/end-of-time)
                 (coalesce (cast to-expr ...) xtdb/end-of-time))
          vt-col)}
```

**Why it's wrong.** Silent fall-through to a non-temporal DELETE on a bitemporal table is the worst possible outcome — it destroys history without any sealing/audit trace. (kontor's ADR-007 explicitly forbids silent retraction.)

**Fix sketch.** Two parts: (a) make `TO` optional in the preprocessor regex and the keyword walk; default `to` to `Long/MAX_VALUE` / `xtdb/end-of-time`. (b) Until done, **reject** SQL that has `FOR PORTION OF VALID_TIME FROM <x>` without `TO` rather than silently dropping the clause. Same fix-or-reject pattern as the existing rejection on `INSERT … ON CONFLICT … FOR PORTION OF` (`server.clj:739-746`).

## P1 — real user pain

### P1-1. No SELECT-side temporal SQL surface (`FOR VALID_TIME AS OF`, `BETWEEN`, `ALL`, `FROM…TO`)

**Stratum.** SQL preprocessor handles ASOF JOIN (DuckDB-style) and `FOR PORTION OF VALID_TIME` on DML, full stop. There is no read-side temporal grammar in `sql/rewrite.clj` or `sql.clj`. The temporal-design doc nods at this (Phase F/G defer pg-datahike session vars), but the substrate ships incomplete: writes can stamp vt windows, reads can't ask "what did this look like at time T".

**XTDB v2.** Full grammar: `Sql.g4:202-211` (period predicates), `Sql.g4:578-593` (`querySystemTimePeriodSpecification` + `queryValidTimePeriodSpecification` + `tableTimePeriodSpecification` with `AS OF`/`ALL`/`BETWEEN`/`FROM…TO`). Per-FROM, per-table, fully composable across joins. Plus `SETTING DEFAULT VALID_TIME TO …` for session-wide. `docs/.../sql/queries.adoc:20-30, 154-163`.

**Fix sketch.** Mirror the FOR PORTION OF preprocessor: strip `FOR VALID_TIME (AS OF x | ALL | BETWEEN a AND b | FROM x TO y)` after each table-ref, capture as side-channel `{:table-temporal {<table> {:axis :valid :spec [:as-of t]}}}`, then teach the planner to push a range predicate on the configured `vf-col`/`vt-col` for the table. For datahike-backed sources, route through the existing `valid-at` machinery.

### P1-2. Auto-split limited to open-window `upsert!`; bounded `INSERT/UPDATE` rejects overlaps without an opt-in

**Stratum.** `upsert!` and the open-window branch of `retract!` honor `:auto-split? true` (`dataset.clj:348-356, 511-518`). The bounded `retract!` (used by SQL DELETE FOR PORTION OF) is *unconditionally* surgical. But `bounded-update!` and `insert-portion-via-index-backend` don't have an overlap detector at all — `append!` simply writes the new row, and overlapping legacy rows are left in place. If the same `:eid` already has an open row covering the bounded slice, the result is two rows simultaneously "valid" over the bounded slice (XTDB's WITHOUT OVERLAPS invariant is violated).

**XTDB v2.** Every put/upsert/delete writes against the temporal trie, and the merge step (`SegmentMerge.kt`, `PatchGapsCursor.kt`) enforces WITHOUT OVERLAPS by construction. There is no "rejected; pass a flag to split" toggle — the invariant is a property of the storage layout, not of the writer.

**Fix sketch.** Two options:
- (cheap) Make `bounded-update!` call `retract!` against `[new-vf, new-vt)` *with the system-time fix in P0-1*, then append the merged slice. That's already the structure; just thread the same surgery through `INSERT FOR PORTION OF` (`server.clj:238-271`) so it pre-clears the slice. Document the new semantics as "non-sequenced INSERT FOR PORTION OF replaces the slice for matching rows."
- (expensive) Push WITHOUT OVERLAPS into the dataset layer as an invariant that every write checks. ~XTDB v2's approach. Lots of work; defer.

### P1-3. Allen interval predicates: only `period-overlaps?` exists; XTDB v2 has the full set

**Stratum.** One Clojure predicate, `period-overlaps?`, in the datahike rule library. Nothing in SQL.

**XTDB v2.** Eight predicates in SQL `expr` grammar (`Sql.g4:205-213, 357`): `OVERLAPS`, `EQUALS`, `CONTAINS`, `PRECEDES`, `SUCCEEDS`, `IMMEDIATELY PRECEDES`, `IMMEDIATELY SUCCEEDS`, plus `STRICTLY` variants. Documented with exact boundary semantics at `stdlib/temporal.adoc:80-132`. Used heavily in audit queries ("policy renewals strictly succeed lapses").

**Fix sketch.** Derive the other seven mechanically from `period-overlaps?` once we have a `PERIOD(from, to)` value (see P1-4). SQL preprocessor recognizes them; lower to compound `_valid_from`/`_valid_to` comparisons. Pure additive change.

### P1-4. No `PERIOD(from, to)` constructor / `LOWER`/`UPPER`/`*` intersection

**Stratum.** Periods are implicit — two columns. No first-class value type.

**XTDB v2.** `PERIOD(from, to)` or `TSTZRANGE(from, to)` returns a single value (`Sql.g4:357`, `stdlib/temporal.adoc:72-145`). `LOWER`/`UPPER`/`LOWER_INF`/`UPPER_INF` access bounds, `p1 * p2` intersects.

**Why it matters.** Without a Period value type, every audit query against two tables has to bind four time columns and write the half-open membership check by hand. With it, joins read like prose.

**Fix sketch.** Defer until a consumer asks. Then: a `Period` deftype with `lower`/`upper`/`*` methods + grammar hook. SQL surface lowers to compiled compound predicates over the two backing columns; the planner sees the two-column shape it already understands.

### P1-5. `FOR ALL VALID_TIME` DML scope is missing

**Stratum.** No way to express "apply this DELETE/UPDATE across the entire vt-history of the matching entity." The only available shapes are open-window (no `valid-to` → close at now) and bounded (explicit `vf`/`vt`).

**XTDB v2.** `Sql.g4:830`: `dmlStatementValidTimeExtents : … | 'FOR' ('ALL' 'VALID_TIME' | 'VALID_TIME' 'ALL') # DmlStatementValidTimeAll`. Equivalent to `:for-valid-time :all-time` in the plan (`sql.clj:2606`). Used for "this fact was a recording error from day one, expunge across all vt."

**Fix sketch.** Preprocessor recognizes the tokens, sets `:period {:axis :valid_time :scope :all}`. Server lowers to a full retract over `[start-of-time, end-of-time)`. Costs nothing extra.

### P1-6. Period bounds via column refs are unparseable; only literals accepted

**Stratum.** `parse-temporal-literal` in `sql/rewrite.clj:227-255` recognizes integer literals, `DATE 'x'`, `TIMESTAMP 'x'`, and plain `'YYYY-MM-DD'`. Anything else (column reference, subquery, expression) throws "Unparseable temporal literal in FOR PORTION OF VALID_TIME". So `DELETE … FOR PORTION OF VALID_TIME FROM contract_start TO contract_end WHERE …` fails.

**XTDB v2.** `Sql.g4:828` uses `staticExpr` for both from and to; any constant-foldable expression works.

**Fix sketch.** Until we want to do real expression evaluation, document the literal-only restriction in `temporal-design.md`. Long-term: route `from`/`to` through the existing expression evaluator and have the preprocessor recognize at least column references and simple arithmetic.

## P2 — nice-to-have

### P2-1. No `ERASE` (system-time physical purge) DML

**Stratum.** `ds-delete-rows!` physically removes; the SQL DELETE branch routes through it when there is no `FOR PORTION OF VALID_TIME`. But there's no surface to say "destroy across *all* system-time" — physical and logical deletes are conflated by which SQL clause you used. For GDPR-style "right to be forgotten," this matters: stratum can't currently distinguish "retract from vt-history" from "erase even from past system-time snapshots."

**XTDB v2.** Two surfaces: `DELETE FOR PORTION OF VALID_TIME` (retraction in vt) and `ERASE FROM <t> WHERE <p>` (physical, across both axes). `Sql.g4:22, 847`; `sql/txs.adoc:94-105`. Different verb, different audit semantics.

**Fix sketch.** Add `ERASE FROM <t> WHERE <p>` to the preprocessor. Lower to `ds-delete-rows!` over the matching indices. Plain `DELETE WHERE` (no `FOR PORTION OF`) on a bitemporal table should arguably refuse — or auto-route to bounded retract over `[now, MAX)` rather than physical purge. Pick one; document in ADR.

### P2-2. No `RANGE_BINS` / `GENERATE_SERIES` time-slicing

**Stratum.** No helper to materialize "trial balance every Friday of 2024" in one query. Consumers must run N queries.

**XTDB v2.** `RANGE_BINS(stride, period, [origin])` returns weighted bins (`stdlib/temporal.adoc:213-242`); `GENERATE_SERIES` for plain timestamp ranges (`stdlib/temporal.adoc:184-211`).

**Fix sketch.** A Clojure-side helper `(stratum.temporal/range-bins stride period origin)` returning `[{:_from, :_to, :_weight}]`, joinable via `unnest`. SQL grammar opt-in for the time someone wires it through.

### P2-3. `SETTING CLOCK_TIME` / `SETTING SNAPSHOT_TIME` / `BEGIN … WITH (SYSTEM_TIME …)` are absent

**Stratum.** "Now" is `(System/currentTimeMillis)` at the moment `append!` runs (`dataset.clj:884-893`). No way to pin it for repeatable test runs or regulator replays.

**XTDB v2.** `SETTING CLOCK_TIME TO TIMESTAMP '…'` per query, `BEGIN READ WRITE WITH (SYSTEM_TIME TIMESTAMP '…')` per transaction (`stdlib/temporal.adoc:50-56`, `Sql.g4:909`). Replayable.

**Fix sketch.** Thread a `*current-time*` dynamic var through `now-in-unit`. Surface in the SQL preprocessor as a `SET datahike.clock_time = …` session var (parallel to the existing `datahike.valid_at`).

### P2-4. Zone-map / chunk-stats pruning doesn't yet recognize `[vf, vt)` as a window predicate

**Stratum.** The planner walks `:_valid_from`/`:_valid_to` as independent columns and applies per-column zone-map pruning (`query/executor.clj:320-388, 346-358`). A query `WHERE _valid_from <= ts AND _valid_to > ts` will prune each column separately — fine, but it doesn't exploit the *pair*: a chunk where `min(_valid_from) > ts` *or* `max(_valid_to) ≤ ts` can be skipped entirely. The follow-up note `58-stratum-vt-followup.md §15` already flags this.

**XTDB v2.** Arrow trie + temporal column zone maps at `core/src/main/kotlin/xtdb/trie/MetadataFileWriter.kt` and `PageMetadata.kt` keep min/max for `_valid_from`/`_valid_to`/`_system_from`/`_system_to` per page; the scan operator (`BitemporalConsumer.kt`) skips pages whose temporal extents don't overlap the query's window.

**Fix sketch.** Phase G+ optimization. ≈50 LOC in `query/predicate.clj` to detect the paired-column shape and emit a single window-overlap pruner. Bench-driven.

### P2-5. Schema discovery is explicit; XTDB v2 auto-creates the four temporal columns

**Stratum.** The user must declare `:bitemporal {:valid {:from-col … :to-col … :unit …} :system {…}}` on `make-dataset`. Plain `make-dataset` produces a non-temporal table.

**XTDB v2.** Every table is bitemporal by construction; the four temporal columns are added automatically (`docs/.../concepts/key-concepts.adoc:55-58`).

**Fix sketch.** Two paths: (a) opt-in flag on `make-dataset` (e.g. `:bitemporal :default`) that auto-adds `_valid_from`/`_valid_to`/`_system_from`/`_system_to`. (b) Continue requiring explicit declaration but make `CREATE TABLE … WITH SYSTEM VERSIONING` / `PERIOD FOR VALID_TIME (a, b)` work in SQL `CREATE TABLE`. Recommendation: keep declaration explicit (ADR-002 — kontor cohabits with non-temporal beleg, blanket bitemporality is wrong); but accept SQL:2011's `PERIOD FOR …` clause so SQL users can opt in without crafting metadata maps.

## Other findings

- **`upsert!`'s degenerate-to-insert** (`dataset.clj:389-390`) passes `tx-meta` augmented with `:valid-from close-vt-val`, but the user's original `:set` map may already contain `:_valid_from`. If so, `merge-axis-defaults` keeps the user's value, and `tx-meta :valid-from` is ignored — correct, but undocumented. Worth a sentence in `temporal-design.md`.

- **Concurrent writes / atomicity.** `bounded-update!` is *not* atomic with respect to a parallel reader of the same transient — but transients in Clojure are single-threaded by convention (`ITransientCollection`); a sync! barrier separates writers and readers. No actual bug, but the doc could be explicit: "stratum's bitemporal primitives are transient-scoped; cross-transient atomicity is a sync!-level guarantee." XTDB v2 gets serializable writes via a single-writer log.

- **`auto-split?` flag at retract!.** The bounded retract branch ignores `auto-split?` (the bounded form *is* the surgery). But the open-window branch still respects it. A user setting `:auto-split? true` AND `:valid-to` together gets two different behaviors depending on the branch — confusing. Either reject the combination or document.

## Notes on prior research notes (58, 59, 60)

**`58-stratum-vt-followup.md`** (write-path audit). Most concerns are now addressed by PR #26:
- "`append!` silent-zero on vt-aware datasets" → fixed by `merge-axis-defaults` auto-stamping.
- "Expose SCD2 helpers" → `upsert!`/`retract!`/`bounded-update!` are exactly this.
- "Cheap-ladder SQL INSERT/DELETE auto-stamp" → done via the four `*-portion-via-index-backend` helpers.
- Still relevant: paired-column zone-map pruning (P2-4 here), CSV/Parquet metadata pass-through (out of scope for this PR).

**`59-bitemporality-terminology-and-landscape.md`**. Pure terminology survey; no findings to update.

**`60-xtdb-vt-feature-comparison.md`**. The original feature matrix. After PR #26:
- `FOR PORTION OF VALID_TIME … UPDATE/DELETE/INSERT` was a GAP-MUST → **MATCH** (Phase D + D+).
- VT-aware uniqueness / WITHOUT OVERLAPS was a GAP-MUST → **partial MATCH** (open-window upsert! detects + auto-splits; bounded INSERT/UPDATE doesn't — see P1-2 here).
- Per-relation `:for-valid-time` annotation in datalog: still GAP-NICE; not in PR #26.
- Allen interval predicates: still GAP-NICE (P1-3 here).
- `FOR VALID_TIME AS OF` on SQL reads: was MATCH via pg-datahike session var, but the in-stratum SQL surface still has no per-query temporal grammar (P1-1 here promotes this concern: the session var alone doesn't compose well).

New findings not in 60:
- **System-time symmetry on SCD2 writes** (P0-1 here). 60 noted system-time was MATCH because the konserve commit graph carries it; PR #26's per-row `_system_from`/`_system_to` design intends row-level system-time, but the writes don't follow through. This is a regression of the abstraction PR #26 introduced, not of anything 60 covered.
- **Zero-width/reverse period validation** (P0-2). XTDB v2 enforces; stratum doesn't.
- **`FROM … TO` open-end form** (P0-3). XTDB v2 allows optional `TO`; stratum requires both.
- **`FOR ALL VALID_TIME` on DML** (P1-5). 60 marked this GAP-NICE; still missing.

## Stratum's strengths (worth capturing for the PR description)

- **Per-axis opt-in.** `:bitemporal {:valid {…}}` *or* `{:system {…}}` *or* both. XTDB v2 forces bitemporal on everything; stratum lets non-temporal tables stay cheap.
- **`auto-split?` as an explicit policy.** XTDB v2's WITHOUT OVERLAPS is implicit and unavoidable; stratum's reject-by-default with explicit opt-in matches kontor's audit ethos (ADR-007: no silent retract).
- **Configurable axis units.** `:micros` / `:millis` / `:seconds` / `:days` per axis (`dataset.clj:884-927`). XTDB v2 is micros-only.
- **Predicate-driven DML.** `:where` works in pure Clojure (`eval-pred`) or via the SQL surface — adapters can plug in their own predicate languages without rewriting the SCD2 core.
- **SCD2 + non-sequenced UPDATE composability.** `bounded-update!` decomposes into `retract!` + per-row `append!` over captured slices; the temporal surgery is reused. XTDB v2 has separate code paths for UPSERT vs PATCH; stratum has fewer moving parts.

## References

Stratum (post-Phase D+):
- `~/Development/stratum/src/stratum/dataset.clj:247-552, 1062-1123` (write primitives + bounded-update!)
- `~/Development/stratum/src/stratum/sql/rewrite.clj:113-317` (preprocessor)
- `~/Development/stratum/src/stratum/server.clj:195-354, 633-746, 878-1055` (DML branches)
- `~/Development/stratum/doc/temporal-design.md` (design doc)

XTDB v2 (Apache-2.0, citation-only):
- `~/Development/xtdb2/core/src/main/antlr/xtdb/antlr/Sql.g4:65-66, 202-213, 357, 578-593, 828-847, 909` (grammar)
- `~/Development/xtdb2/core/src/main/clojure/xtdb/indexer.clj:97-349` (put/delete/upsert/erase indexers + validation)
- `~/Development/xtdb2/core/src/main/clojure/xtdb/sql.clj:1474-1521, 2580-2700` (period predicates + `dml-stmt-valid-time-portion`)
- `~/Development/xtdb2/docs/src/content/docs/concepts/key-concepts.adoc:47-78` (bitemporal data model)
- `~/Development/xtdb2/docs/src/content/docs/reference/main/sql/queries.adoc:20-30, 154-163` (`SETTING`, `FOR VALID_TIME`)
- `~/Development/xtdb2/docs/src/content/docs/reference/main/sql/txs.adoc:40-105` (`FOR PORTION OF`, `ERASE`, `PATCH`)
- `~/Development/xtdb2/docs/src/content/docs/reference/main/stdlib/temporal.adoc:50-243` (Allen + `RANGE_BINS` + `GENERATE_SERIES`)

XTDB v1 (EPL-1.0):
- `~/Development/xtdb/modules/sql/src/xtdb/calcite.clj:57, 403` (`VALIDTIME` Calcite extension)
- `~/Development/xtdb/core/src/xtdb/api.clj:227-262` (entity-history API)

Prior kontor research:
- `doc/research/58-stratum-vt-followup.md` (write-path audit pre-PR)
- `doc/research/59-bitemporality-terminology-and-landscape.md` (terms)
- `doc/research/60-xtdb-vt-feature-comparison.md` (feature matrix pre-Phase D+)

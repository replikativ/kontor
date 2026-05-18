---
name: bitemporal-repl-experiments
description: REPL benchmarks comparing per-tx-vt vs per-datom-vt vs bulk-load paths in datahike; analysis of why current vt-filter queries are ~200× slower than unfiltered; proposed v1 path (engine-recognized vt rule + optional AVET-with-vt) before considering an invasive 5-tuple datom shape
date: 2026-05-15
agent: human-driven REPL session
---

# REPL experiments on datahike bitemporal representations

## Measurements

All on `:keep-history? true` in-memory datahike, single-threaded. Wall-clock `System/nanoTime`.

| path | what | rows | tx-count | ms | notes |
| --- | --- | --- | --- | --- | --- |
| **A** | per-row vt via N separate transactions | 1 000 | 1 000 | **1274 ms** | one `d/transact` per row, each carries its own `:tx/valid-from` tx-meta |
| **B** | shared vt — one tx with 1000 rows | 1 000 | 1 | **51 ms** | regular transactor batched |
| **D** | raw-datom `transact-entities-directly` with per-row synthetic tx + per-tx `:tx/valid-from` datom | 1 000 | (1000 logical) | **17 ms** | the raster/valley bulk-load escape hatch — bypasses the transactor |
| Read-baseline-history | `(d/q '[:find (count ?e) . :where [?e :emp/salary _]] (d/history db))` | 25 000 datoms | – | **23 ms** | unfiltered history scan |
| Read-baseline-current | same as above on `(d/db conn)` | 5 000 | – | **4 ms** | current-snapshot scan |
| Read-valid-at | salary count with `(valid-at ?tx ?at)` rule | 25 000 datoms / 5 000 emps | – | **4 938 ms** | rule-based vt-filter on history db |

## Headline observations

1. **Per-row vt via separate transactions is 25× slower than batched** (1274 ms vs 51 ms) — confirms note 55's finding that mixed-vt bulk writes are the ergonomic break-case.
2. **Bulk-load via `transact-entities-directly` is 75× faster than per-tx, 3× faster than batched single-tx** — and trivially carries per-row vt as a synthetic-tx datom (`[tx :tx/valid-from vt tx true]` for each unique vt). This is the existing raster/valley pattern; it's an underused escape hatch.
3. **Query side is the real problem, not the write side.** A `(valid-at ?tx ?at)` rule over a 25 000-datom history is **200× slower** than the same query without vt filtering (4938 ms vs 23 ms). The planner pulls every tx-bearing datom into a relation, joins to its tx entity, then filters in Clojure — no pushdown into AVET on `:tx/valid-from`.

## The two real costs

- **Writes:** per-row vt via the regular transactor is slow because the transactor does *per-tx* setup (next-eid, sync into PSS, schema check, transient → persistent at the end). The fix is **already in datahike** — `transact-entities-directly` skips all of this. The question is whether to expose a `bulk-backfill-with-vt!` API that wraps it and routes through `transact-with-validation` for the kontor gate.
- **Reads:** the planner doesn't know that `:tx/valid-from` is a *temporal sort key*. The rule pushes the predicate down into the join *output*, not the *input*. The fix is **planner intelligence**, not storage shape. A recognized rule `(valid-at ?tx ?at)` should rewrite to `(seek :tx/valid-from [dawn, at]) ∩ (seek :tx/valid-to [at+, forever])` before joining with salary datoms.

## The four shape options

| # | name | write cost | read perf | storage overhead for non-vt | invasive in datahike? |
| --- | --- | --- | --- | --- | --- |
| 1 | **Per-tx vt via tx-meta** (current) | OK if batched; 25× pessimization for per-row vt | poor (200× slower with vt filter) | 0 | not invasive |
| 2 | **Per-datom vt in `[e a v t vt]`** (5-tuple datom) | cheap | excellent (one composite-key seek) | ~17% on every datom — pay-per-non-use | **very invasive** — touches Datom deftype, every comparator, PSS serialization |
| 3 | **Optional per-attribute AVET-with-vt** (schema flag) | cheap on vt-marked attrs only | excellent on vt-marked attrs | 0 for unmarked attrs | moderately invasive — new index variant; opt-in |
| 4 | **Engine-recognized vt rule + status quo storage** | unchanged | good (rewrite makes vt-filter use index scan) | 0 | minimally invasive — planner change + 1-2 built-in rules |

## Recommendation: option 4 → option 3 → option 2 only if profiled

The cheapest unlock is **option 4** alone — recognize `(valid-at ?tx ?at)`, `(valid-between ?tx ?at-from ?at-to)`, `(period-overlaps? ?tx ?from ?to)` as engine-built-in rules that the planner rewrites into AVET seeks on `:tx/valid-from`/`:tx/valid-to`. No storage change. No invasive datom-shape rewrite. Closes the 200× read regression. Estimated effort: a 200-line patch to the new query planner.

Option 3 (per-attribute AVET-with-vt) becomes the second step **only when option 4's read perf isn't enough** — e.g. tax-authority workloads with billions of vt-bearing facts where the `:tx/valid-from` AVET seek is itself the bottleneck. The schema flag is `(:db.attribute/temporal? true)`; the index variant is opt-in; it composes with option 4's planner rules (the same rule clauses just dispatch to AVET-with-vt when present).

Option 2 (5-tuple datom shape) is the last resort. Two reasons to defer:

- The storage regression is paid by every user, including users who never use bitemporality. The replikativ ecosystem (proximum, scriptum, stratum) is much larger than the bitemporal users; a 17% blanket cost would hurt the whole stack.
- Migration of existing databases is a real operational story (write a new PSS, rewrite every datom). Avoidable for the period kontor + simmis need this.

## Write path: graduate the bulk-load escape hatch

`transact-entities-directly` already does the right thing — 17 ms for 1000 rows with per-row vt. The v1 public API:

```clojure
(defn bulk-transact!
  "Idempotent bulk transact. Accepts either entity-maps or raw datoms.
   When the input is a sequence of {:tx-data tx-data :valid-from vf}
   each tx-data is committed as its own logical tx with its vf.
   Bypasses the regular transactor; routes the resulting Datom flow
   through the validation gate."
  [conn batches] ...)
```

Closes use case #8 (bulk migration backfill) from research note 55 without changing the storage shape.

## Yggdrasil + cross-DB transact

Orthogonal to the shape question. The cross-system bitemporal axis (note 55 §6, option C) is a workspace-level vt axis. None of the four shape options above changes that protocol-level recommendation.

## Concrete datahike branch plan

Three commits on a `feature/bitemporal-v1` branch from main:

1. **`d/valid-at` / `d/valid-between` rules as engine built-ins** — recognize them in the query rewriter, push down to AVET seek on `:tx/valid-from`/`:tx/valid-to`. Add `time-variance` tests parallel to the existing `d/as-of` ones.
2. **`d/bulk-transact!` wrapping `transact-entities-directly`** with optional `:valid-from`/`:valid-to` per batch item. Includes a benchmark mirroring the table above.
3. **(deferred to step 2 PR follow-up)** — `:db.attribute/temporal?` schema flag + AVET-with-vt index variant. Only if benchmark evidence demands it.

That's the minimum that closes the kontor break-cases (#3 mixed-vt tx, #7 carve-a-hole, #8 bulk backfill) — all of which are really write-path and read-path planner-intelligence problems, not datom-shape problems.

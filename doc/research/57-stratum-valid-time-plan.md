---
date: 2026-05-15
agent: Plan
title: Stratum Valid-Time Support and Datahike Integration
status: draft
---

# Stratum Valid-Time Support — Implementation Plan

## 1. Stratum-side convention (Q1 verdict)

**Verdict: plain columns `_valid_from` / `_valid_to`, both `:type :int64` with `:temporal-unit :micros`, surfaced as a dataset-level config `{:valid-time {:from-col ... :to-col ... :unit :micros}}` for engine-aware paths.**

Evidence:
- Leading underscores are *not* engine-reserved. The one in-tree example, `:__latest_rn` (`/home/christian-weilbach/Development/stratum/src/stratum/api.clj:635`), uses double underscore by *convention* (window column), not enforcement. Single-underscore prefix is safe.
- `:temporal-unit` already threads through the whole pipeline: `column.clj:31` documents it; `query.clj:529` and `query.clj:637` thread it into `*columns-meta*`; `query/expression.clj:309` (`col-temporal-unit`) dispatches all date kernels off it; `sql.clj:2293` already maps SQL `TIMESTAMP` → `{:type :int64 :temporal-unit :micros}`. **No engine changes are needed to make `_valid_from`/`_valid_to` carry tagged microsecond semantics.** This is the same DuckDB convention `TIMESTAMP`/`TIMESTAMPTZ` use.
- Tagging *also* via dataset-level config (`make-dataset … {:metadata {:valid-time {...}}}`) is cheap (a metadata map) and lets the future ASOF/range-pruner know which columns to treat as the vt-window without parsing column names. Mirrors how `server.clj:289-298` already stores `:column-schema` as Clojure metadata on the table value.

Trade-off: a name-only convention is convenient for ad-hoc queries; the metadata-tagged form is needed for engine pushdowns and for the datahike adapter to round-trip the config across `sync!`/`load`. **Do both, redundantly.** The metadata is the source of truth; the names are the on-disk form that the SQL surface can address.

## 2. Read path (Q2)

**Recommendation: Option A (WHERE-clause range scan), period. Defer B/C until benchmark evidence justifies the engineering.**

### Option A — WHERE-clause range scan. **Recommended.**

- Compiles to `[:<= :_valid_from at]` and `[:> :_valid_to at]` — two existing predicate ops in `stratum.query.predicate`.
- Zone-map pruning is *already* `:gte`/`:lte`/`:between`-aware (`stats.clj:284-325`). For chunks loaded in vt-from-sorted order — which is the natural insert order for the datahike adapter, since transactions are linear in tx-time — pruning will eliminate the bulk of irrelevant chunks before scan, the same way the existing TPC-H Q6 path prunes on `l_shipdate`.
- **Correctness on half-open `[vf, vt)`** is direct: `_valid_from <= at AND _valid_to > at`. Open-ended writes use `_valid_to = Long/MAX_VALUE` (sentinel; zone-map handles it correctly since `Long/MAX_VALUE > at` is always true).
- **Performance**: identical hot path to Tier 1 (TPC-H Q6) and Tier 4 (NYC taxi pickup-month filters). No new operators. Filter cost is two predicates on long columns — the kernel that already runs at ~13ms on 10M rows.
- **Implementation cost**: zero engine LOC. ~30 LOC in the adapter to splice the two predicates into the user `:where`.
- **Bench coverage**: Tier 1 (TPC-H Q6), Tier 4 (NYC taxi monthly filters), Tier 9 (asof, indirectly via storage size) all already exercise range scans on long columns. A new bench is needed only to exercise the *combined* selectivity, see §6.

### Option B — ASOF JOIN rewrite. **Defer.**

Tempting: the radix-partitioned two-pointer merge at `query/asof_join.clj:135` would, in principle, be faster on extremely tall vt-indexed tables. Cost:
- Requires `(entity, _valid_from)` sortedness invariant. Today `idx-append!` (`index.clj:596-637`) is strictly append-at-end — it never re-sorts and chunks carry `min`/`max` stats but no global ordering guarantee. Maintaining this invariant on every datahike `-transact` is a *real* engineering hazard: out-of-order vt writes (corrections, retroactive entries) would force per-chunk re-sort, which voids the O(1) append cost the rest of the engine assumes.
- Even when sorted, the post-filter `_valid_to > at` is still required — half the work of Option A done twice.
- Correctness gotcha: ASOF returns *one* match per probe row; vt-as-of needs *all* rows whose window contains `at`. ASOF would have to be inverted (probe = points-in-time set, build = vt-windows) which is a different kernel.

### Option C — Hybrid. **Defer.**

Plan-rewrite from A to B when sortedness can be statically proved. Adds two non-trivial pieces (the proof, the rewrite rule). No bench evidence today suggests A is too slow. Premature.

**Decision rule for revisiting B/C**: if the §6 bench shows Option A slower than DuckDB's `SELECT … WHERE valid_from <= ? AND valid_to > ?` by more than 1.5x on a 10M-row vt-table, reopen.

## 3. Write path (Q3)

**Recommendation: option (1) — plain column writes from the datahike adapter — combined with optional dataset-level vt-config from (2) for validation. Skip (3) until B/C is justified.**

- The datahike `IValidTimeAware` adapter projects `tx-meta` → `_valid_from`/`_valid_to` values per datom row inside `-transact`. It calls `dataset/append!` (`dataset.clj:183`) on a transient StratumDataset. No engine changes.
- `append!` already validates "all columns present" (`dataset.clj:189`), so a missing vt projection raises immediately — exactly the validation we want.
- Dataset-level config (option 2) is added as `:metadata {:valid-time {...}}` on `make-dataset`; the adapter reads it back on `load` and uses it to (a) validate that the columns still exist, (b) advertise vt-awareness to the planner.
- Sortedness invariant (option 3) is **explicitly out of scope** for the first feature branch. Reopen only on Option B evidence.

## 4. SQL surface (Q4)

### Stratum itself

**Recommendation: ship (a) plain `WHERE`-style filters in the first cut. Defer (b) SQL:2011 grammar.**

- (a) works today with zero parser changes. The adapter only ever has to produce predicates.
- (b) `FOR VALID_TIME AS OF '…'` is not in JSqlParser 5.2's grammar (only `FOR SYSTEM_TIME` is partial). Adding it requires either a pre-parse rewriter (string-level: regex-strip the `FOR VALID_TIME AS OF '…'` clause, stash the timestamp in a thread-local, splice the predicate at translate time) or forking the parser. The pre-parse rewriter is feasible (~80 LOC in `sql.clj`) but earns its keep only when a real client demands SQL:2011 syntax. The Postgres wire surface goes through pg-datahike anyway.

### pg-datahike

**Recommendation: extend the existing `parse-temporal-set` at `/home/christian-weilbach/Development/pg-datahike/src/datahike/pg/server.clj:2409-2439` with three new keys: `datahike.valid_at`, `datahike.valid_from`, `datahike.valid_to`.**

- The mechanism is already there: session-state atom, `apply-temporal` at `server.clj:2472-2496` composes wrappers around `(d/db conn)`. Add a `valid-at` wrapper that — when the user's query hits a vt-aware secondary index — passes the date down to `-search-at-vt`; otherwise composes a post-hoc AVET filter as described in the protocol docstring at `secondary.cljc:121-122`.
- LOC: ~40 in `pg/server.clj`, ~15 in `sql/classify.clj:913` (the comment already notes temporal vars are pre-extracted).

## 5. Datahike integration (Q5)

**Recommendation: new namespace `stratum.datahike` *inside* the stratum repo, gated behind the `:datahike` alias** (so the JAR doesn't pull datahike as a hard dep). Mirrors how `stratum.tablecloth` is structured.

Sketch (~250 LOC):

```clojure
(ns stratum.datahike
  (:require [datahike.index.secondary :as sec]
            [stratum.dataset :as ds]
            [stratum.api :as st]))

(deftype StratumSecondaryIndex
  [dataset-atom config]        ;; atom holding current StratumDataset value
  sec/ISecondaryIndex
  (-search [_ q ef] ...)        ;; → (st/q (translate q) {table dataset}) → bitmap
  (-estimate [_ q] ...)         ;; → row-count or stats heuristic
  (-can-order? [_ a d] ...)
  (-slice-ordered [_ q ef a d limit] ...)
  (-indexed-attrs [_] (:attrs config))
  (-transact [this {:keys [datom added? tx-meta]}]
    (let [vf (or (:db.valid/from tx-meta) (:db/txInstant tx-meta))
          vt (or (:db.valid/to tx-meta) Long/MAX_VALUE)]
      (swap! dataset-atom
             #(persistent!
                (-> % transient
                    (ds/append! {:e (.-e datom) :a (.-a datom) :v (.-v datom)
                                 :tx (.-tx datom) :added? added?
                                 :_valid_from (date->micros vf)
                                 :_valid_to   (date->micros vt)}))))
      this))
  sec/IValidTimeAware
  (-search-at-vt [_ q ef vt-arg]
    (st/q (-> (translate q)
              (update :where conj
                      [:<= :_valid_from (vt->micros vt-arg)]
                      [:>  :_valid_to   (vt->micros vt-arg)]))
          {table @dataset-atom}))
  sec/IVersionedSecondaryIndex
  (-sec-flush   [_ store branch] (ds/sync! @dataset-atom store branch) ...)
  (-sec-restore [_ store key-map] (reset! dataset-atom (ds/load store ...)))
  (-sec-branch  [_ store from-branch new-branch] (ds/fork @dataset-atom) ...)
  (-sec-mark    [_] ...))

(defn register! []
  (sec/register-index-type! :stratum/columnar
    (fn [config db] (->StratumSecondaryIndex (atom (make-vt-dataset config)) config))))
```

Open question to defer: whether to also implement `IColumnarAggregate` for direct columnar group-by pushdown. Big win, but out of scope for the vt branch.

## 6. Performance protection plan (Q6)

The risk: every row gets two extra `long` columns. On 10M rows that is 160 MB of extra storage and two extra cache lines per row to skip on every scan.

Concrete gates:

1. **Baseline snapshot, commit 0.** Before any code changes, run `clj -M:olap 10000000` *and* save the result as `/home/christian-weilbach/Development/stratum/bench/baseline-vt-branch.edn`. This is the regression reference.
2. **Tiers that *don't* touch vt columns must not move.** Tiers 1, 2, 3, 4, 5, 6, 7, 8 (everything except the new vt tier) share storage layout assumptions but should never read `_valid_from`/`_valid_to`. The branch is correct iff their numbers are within ±5% of baseline.
3. **New Tier 10: vt-as-of.** Add three shapes:
   - **vt-Q1** — 10M rows, point-in-time vt lookup, 1% selectivity (typical "as of last quarter"). Compare to a plain `WHERE`-equivalent on the same data.
   - **vt-Q2** — 10M rows, point-in-time vt lookup, 50% selectivity (open-ended `_valid_to = MAX_VALUE`). Stresses zone-map pruning on the sentinel.
   - **vt-Q3** — joined query, vt filter pushed into a fact-table scan that drives an asof join. Mirrors the shape kontor will actually hit.
4. **Gate on every commit in the branch.** Re-run `clj -M:olap t1 t2 t9 t10` (existing + new) and post the diff to the commit message. Anything red on tiers 1/2/9 blocks merge.
5. **Decision rule.** ±5% on existing tiers is acceptable noise. >5% requires either a fix or an explicit waiver in the commit message. Tier 10 has no baseline; the rule there is "within 2x of DuckDB's equivalent vt-as-of query on the same data."

Two existing tiers are most likely to drift:
- **Tier 4 (NYC taxi)** because the CSV loader (`bench/olap_bench.clj:2467`) will need a path for the vt-augmented variant. Keep two variants in the bench.
- **Tier 9 (ASOF)** because if the adapter accidentally lands `_valid_from`-sorting on the index, asof-q1 will get faster — but that may indicate the planner has been silently rewritten. Investigate any *improvement* on this tier as carefully as a regression.

## 7. Branch + commit plan (Q7)

Branch: `feature/valid-time`. Five commits in dependency order.

**Commit 1 — vt-aware column convention + dataset config.** ~80 LOC.
- Add `:valid-time` recognition in `make-dataset` metadata in `stratum/dataset.clj`.
- Add the `:_valid_from`/`_valid_to` column tagging at `encode-column` time when the config is present (defaults to `:temporal-unit :micros`).
- Tests: a unit test that a dataset created with the config exposes `:temporal-unit :micros` on those columns and that `DATE_TRUNC` works on them.
- **Cheap to land and revert.** No SQL surface change, no engine change.

**Commit 2 — bench baseline + Tier 10 skeleton.** ~150 LOC.
- Capture baseline edn (commit 0 snapshot ahead of commit 1; replay-able).
- Add `bench/olap_bench.clj` Tier 10 with the three shapes above; both Stratum and DuckDB variants.
- No semantic change yet. Pure measurement infrastructure.

**Commit 3 — `stratum.datahike` adapter (ISecondaryIndex + IValidTimeAware).** ~250 LOC.
- New file `stratum/src/stratum/datahike.clj`.
- Implements the protocols against a single StratumDataset per index instance.
- Tests: clone shapes from `/home/christian-weilbach/Development/datahike-bitemporal-v1/test/datahike/test/secondary_vt_test.cljc` lines 39-72, 130-147.
- Bench gate: re-run Tier 1, 2, 9, 10 — must stay green.

**Commit 4 — IVersionedSecondaryIndex (flush / restore / branch / mark).** ~120 LOC.
- Wires `sync!` / `load` / `fork` of StratumDataset to the four versioning callbacks.
- Tests: round-trip an index through a commit + branch, search the branched view, confirm the vt-filter holds.

**Commit 5 — pg-datahike session vars + end-to-end integration test.** ~80 LOC in pg-datahike, ~150 LOC in test.
- Add `datahike.valid_at` / `.valid_from` / `.valid_to` to `parse-temporal-set` and `apply-temporal`.
- End-to-end test: kontor schema → datahike with `:db.secondary/type :stratum/columnar` → transact with `:db.valid/from` tx-meta → psql `SET datahike.valid_at = '…'` → see the right rows.
- This is the proof the feature branch ships value end-to-end.

---

## Critical Files for Implementation

- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj`
- `/home/christian-weilbach/Development/stratum/src/stratum/column.clj`
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/index/secondary.cljc`
- `/home/christian-weilbach/Development/pg-datahike/src/datahike/pg/server.clj`
- `/home/christian-weilbach/Development/stratum/bench/olap_bench.clj`

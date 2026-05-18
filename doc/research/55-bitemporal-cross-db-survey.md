---
date: 2026-05-15
agent: general-purpose
status: research-note
topic: Bitemporal query surface across XTDB v1/v2, gap-analysis vs kontor, recommendations for datahike + stratum/proximum/scriptum + yggdrasil, plus atomic cross-DB transact
related: [kontor.bitemporal, ADR-008, ADR-048, ADR-067, doc/research/05-xtdb-accounting-patterns.md, doc/research/08-bitemporality-evidence.md, doc/research/53-kontor-v2-consolidation.md, doc/research/54-simmis-ui-integration.md, /home/christian-weilbach/Development/xtdb, /home/christian-weilbach/Development/xtdb2, /home/christian-weilbach/Development/datahike, /home/christian-weilbach/Development/yggdrasil, /home/christian-weilbach/Development/stratum, /home/christian-weilbach/Development/proximum, /home/christian-weilbach/Development/scriptum, /home/christian-weilbach/Development/konserve]
---

# 55 — Bitemporal cross-DB survey: XTDB v1/v2 → kontor → datahike → replikativ stack → yggdrasil

## 1. XTDB v1 surface

XTDB v1 (Crux era) models bitemporality at the **document granularity** and exposes
it through the transaction API rather than the query body. The data model is:
each `put` carries an optional `valid-time` (`/docs/concepts/.../bitemporality.adoc:38-68`),
and a document version remains visible *for all valid-time on or after that put*
until a later put/delete supersedes it. Transaction-time is set server-side.

Reads pin a `db` snapshot to a `(valid-time, tx-time)` pair: `(xt/db node {::xt/valid-time ..., ::xt/tx ...})`
(`docs/concepts/.../examples/bitemporality_test.clj:208-216`).
Inside Datalog queries valid-time and tx-time are *implicit on the snapshot* —
you cannot bind `?vt` as a logic variable from within the `:where` body. The
only in-query temporal predicates are `get-start-valid-time` / `get-end-valid-time`
(`docs/.../datalog-queries.adoc:860-867`) and the side-channel `History API`
(`docs/.../datalog-queries.adoc:821-867`), which returns a sorted seq of versions
bounded by four coordinates (`start-valid-time`, `end-valid-time`, `start-tx`,
`end-tx`).

Critically: v1 has **no period predicate language** inside queries — no
`OVERLAPS`, no `CONTAINS`, no Allen relations. To do "all facts visible in
`[t1, t2)`" you either run N point-queries at breakpoints (the crime-investigation
tutorial's pattern, `bitemporal_tale_test.clj`) or pull `entity-history` and
post-filter in Clojure. The SQL adapter in `modules/sql/` exposes `VALIDTIME (...)
SELECT ...` and `TRANSACTIONTIME (...)` modifiers (`modules/sql/test/.../calcite_test.clj:32-62`)
but again these pin the snapshot rather than range-quantify inside the predicate.

v1 is **document-level bitemporal**: every `put` of `{:xt/id ...}` writes the
*whole document*, so a backdated correction to one field replaces the whole
document at that valid-time. The polygon resolution under the hood is the same
"latest tx-time wins on overlap, half-open intervals" rule kontor implements —
but the user-visible primitive is "put the whole doc again at the new vt".

## 2. XTDB v2 surface

v2 is a structural rewrite around Arrow columnar storage, Postgres wire
compatibility, and **SQL:2011 application-time + system-time** as the
primary surface. The data model moves from "documents" to **columnar rows
with implicit `_valid_from`, `_valid_to`, `_system_from`, `_system_to`
columns**, exposed as the dotted `_valid_time` / `_system_time` period
columns (`docs/.../reference/main/sql/queries.adoc:154-163`,
`docs/.../reference/main/sql/txs.adoc:27-30`).

### Query surface

Three layers (`docs/.../reference/main/sql/queries.adoc:20-30, 154-163`):

1. **Temporal filter on a relation**: `FROM users FOR VALID_TIME AS OF '2024-01-01'`,
   `FOR VALID_TIME FROM ... TO ...`, `FOR VALID_TIME BETWEEN ... AND ...`,
   `FOR VALID_TIME ALL`, plus the same against `SYSTEM_TIME`. Both axes are
   independent; `FOR ALL VALID_TIME FOR ALL SYSTEM_TIME` enumerates every
   `(vt-rect, sys-rect)` rectangle ever written.
2. **Period predicates in `WHERE`**: `_VALID_TIME OVERLAPS PERIOD(t1,t2)`,
   `_VALID_TIME CONTAINS TIMESTAMP '...'`, plus `PRECEDES`, `SUCCEEDS`,
   `IMMEDIATELY_PRECEDES`, `EQUALS`, `STRICTLY_CONTAINS`, `STRICTLY_OVERLAPS`
   (`core/src/main/clojure/xtdb/expression/temporal.clj:1830-1840`). All 12 Allen
   relations are codegen'd against `tstz-range` type and the special `_VALID_TIME`
   column.
3. **`SETTING DEFAULT VALID_TIME`** at the top of a query, which sets the default
   filter for every unannotated table reference in the body
   (`sql_temporal_test.clj:56-71`).

### Write surface — per-row valid-time intervals

`INSERT INTO foo (...) VALUES (...)` defaults `_valid_from := now`,
`_valid_to := end-of-time`. **Per-row valid-time is set by including
`_valid_from`/`_valid_to` columns in the document** (`docs/.../sql/txs.adoc:27-30`),
which makes them first-class data — not tx-scoped. `UPDATE foo FOR PORTION OF
VALID_TIME FROM t1 TO t2 SET ... WHERE ...` (`sql/txs.adoc:37-53`) is the
SQL:2011 portion-update: only the slice of valid-time in `[t1, t2)` for
matching rows gets the new value, the rest of the timeline is preserved.
`PATCH INTO foo FOR PORTION OF VALID_TIME ...` adds upsert semantics
(`sql/txs.adoc:55-72`). `DELETE FOR PORTION OF VALID_TIME ...` carves a hole.
`ERASE FROM foo WHERE ...` is the GDPR escape: irrevocable removal across both
axes (`sql/txs.adoc:94-106`).

### Polygon resolver

Under the hood, valid-time + system-time forms a 2D plane chopped into
rectangles. The **Polygon / Ceiling** data structure in
`core/src/main/kotlin/xtdb/bitemporal/Polygon.kt:1-49` and
`Ceiling.kt:43-122` is the actual algorithm: as the indexer scans events in
reverse-system-time order, `Ceiling.applyLog(systemFrom, validFrom, validTo)`
mutates a sorted `LongArrayList` of valid-time breakpoints, each carrying the
*ceiling* (latest visible system-time for that vt slice). The polygon for an
event is then `polygon.calculateFor(ceiling, vf, vt)` — splitting the event's
vt range against the existing breakpoints and emitting `(vf, vt, sys-ceiling)`
sub-rectangles (`PolygonTest.kt:30-100` exhaustively exercises overlap cases).
`PolygonCalculator.kt:21-45` is the per-iid driver.

The `TemporalBounds` type (`util/TemporalBounds.kt:7-54`) — a `{validTime,
systemTime}` rect — is the unit of filter pushdown. A scan with a temporal
filter intersects the bounds against per-page `TemporalMetadata` (min/max of
vf, vt, system-from) for pruning before deserializing the page.

### XTQL — Clojure dialect

XTQL pipelines (`docs/.../reference/main/xtql/queries.adoc:136-211`) expose
the same axes via `:for-valid-time (in t1 t2)`, `:for-system-time (at t)`,
etc. The Clojure surface is more uniform than the SQL but semantically
identical.

### Practical observations

- **Period intersection** is an expression: `PERIOD(t1, t2) * PERIOD(t3, t4)`
  returns a `tstz-range` (`sql_temporal_test.clj:230-274`). You can compute
  `foo._valid_time * bar._valid_time` and project the resulting overlap window.
- **Restatement** is just an `UPDATE ... FOR PORTION OF VALID_TIME` — no
  reverse-and-repost dance.
- **`ERASE`** is the only operation that breaks the immutability invariant; everything
  else stays append-only at the column level.
- **No tx-meta layer**: there is no per-tx "this whole transaction is backdated"
  switch — every row carries its own `_valid_from`/`_valid_to`. The tx writes
  multiple rows; if they share a vt window, the writer sets it row-by-row.

## 3. Gap analysis — kontor's tx-meta model vs XTDB v2 polygon model

|  # | Use case | kontor (`:tx/valid-from` + `:tx/valid-to` on tx) | Verdict |
| -- | -- | -- | -- |
|  1 | Restatement of one fact in a closed period (e.g. amend a 2024 invoice's amount) | Wrap the corrected datom(s) in `(with-vt tx vf vt)` — `bitemporal.clj:120-136`. Resolver picks "latest tx-time wins on overlap" — semantically identical to v2 PolygonCalculator for the one-datom case. | **Clean.** |
|  2 | Per-row vt intervals (employee salary history) | Each salary change = one tx, vt = effective date of the change, vt-to = next change's date. Awkward: you have to *predict* `vt-to` at write time, or close-and-reopen later. v2's `UPDATE FOR PORTION OF VALID_TIME` carves the slice automatically. | **Works but awkward** — requires a higher-level helper that issues the "close the prior assertion" tx. |
|  3 | Mixed-vt transaction: backdated fact + current fact in the same logical operation | The tx-meta is shared by **every** datom in a kontor tx — there is no per-datom vt override. You must split into two txes. | **Breaks** (in the strict per-datom-vt sense). Fix: per-datom vt — either a sibling attr on the entity (e.g. `:posting/vt-from`) or a tuple-keyed datom shape (heavier; see §4). |
|  4 | Range query "all facts true at any point in `[t1, t2)`" | `(values-between db eid attr from to)` (`bitemporal.clj:258-291`) — breakpoint-decomposes the window, runs the resolver at each breakpoint, dedupes by value. O(history-size × breakpoints) per `(eid, attr)`. v2 prunes by `TemporalBounds` against page metadata before opening the column. | **Works**, but unindexed scans don't scale past low millions of datoms. |
|  5 | Period-overlap predicate inside a query | `vt-overlaps?`, `vt-precedes?` etc. (`bitemporal.clj:329-376`) are Clojure functions over interval maps — they don't participate in datalog `:where` indexing; you compute a candidate set and then filter. v2 `_VALID_TIME OVERLAPS PERIOD(...)` is a query-engine primitive that pushes down to scan. | **Works but awkward** — no datalog-native period predicate. |
|  6 | Bitemporal compose: "what we knew at tx-time X about valid-time Y" | `(value-at (d/as-of db past-tx) eid attr vt)` — `bitemporal.clj:305-320`. Composition is exact. | **Clean.** |
|  7 | Time-series-style "carve a hole" — delete vt range `[t1, t2)` for a fact | No primitive. You'd need to (a) read the assertion at t1-, (b) reverse-post for `[t1, t2)`, (c) re-assert at t2+. v2: `DELETE FOR PORTION OF VALID_TIME`. | **Breaks** at the primitive level; can be built as a helper. |
|  8 | Migration backfill: load 5 years of historical journal entries, each with its own effective date, in one batch | Today: one tx per vt because tx-meta is shared. With ~50k historical postings this is 50k txes. v2: one bulk `INSERT` per page with per-row `_valid_from`. | **Breaks ergonomically**, not semantically. Fix: per-datom vt or a "batch backfill" API that pipelines micro-txes. |
|  9 | "What did the trial balance show as of TX `T` for valid-date `D`?" — auditor compose | `(kbt/as-of-bitemporal db {:tx T :vt D})` + sum of `value-at` across postings — works, but the `sum-at` (`bitemporal.clj:382-400`) walks every account entity in Clojure. v2 does this with a SQL aggregate plus filter pushdown. | **Works**; performance ceiling at ~hundreds of thousands of postings before it hurts. |
| 10 | GDPR erase — irreversibly remove an entity across both axes | `:db/purge` exists in datahike but the kontor middleware (ADR-007) forbids silent purge. Manual purge is allowed and itself recorded; the audit chain documents it. v2 `ERASE` is a first-class op. | **Clean** (kontor's semantics are stricter than v2 by design). |
| 11 | "Show me how this fact changed" — timeline UI | `(timeline db eid attr)` (`bitemporal.clj:293-299`) — every assertion in vt order, with `(vf, vt, ti, tx)` metadata. Equivalent to v2's `FROM foo FOR ALL VALID_TIME WHERE _id = X` projecting `_valid_from, _valid_to`. | **Clean.** |
| 12 | Allen-relation join: "find all leases whose vt is contained in some lease-modification's vt" | Today: pull both sets to Clojure, run `vt-contains?` pairwise. v2: `WHERE lease._valid_time CONTAINS modification._valid_time` — a single SQL join. | **Works but awkward** at scale. |
| 13 | Period intersection as a value: produce the overlap window of two periods | No primitive; compute in Clojure. v2: `PERIOD(...) * PERIOD(...) AS overlap`. | **Works**; could add `vt-intersect` to kontor in ~10 lines. |
| 14 | Process / multi-step bitemporal tx (ADR-067) — every step in `run-process` sees the same vt | `kbt/strip-tx-meta` + `with-vt` on the merged tx-data is the canonical pattern (`bitemporal.clj:109-118`, used in `kontor.process`). Whole-process vt assignment is **easier** in kontor than v2, where every row insert must repeat the vt. | **Clean — kontor advantage.** |
| 15 | Real-time write (now, not backdated) | `(d/transact conn tx-data)` without tx-meta — defaults to `:db/txInstant`. Zero ceremony. v2 also has zero ceremony but ships an extra column on every row regardless. | **Clean.** |
| 16 | Reverse-and-repost vs portion-update (ADR-008 revised) | kontor mandates reverse-and-repost for closed periods — every correction is a new tx-time event, valid-time-shifted. v2's `UPDATE FOR PORTION OF VALID_TIME` is the same semantically (new system-time row covering the carved vt slice) but more ergonomic. | **Works but verbose**; ADR-008's rationale (auditor-readable reverse + repost) keeps it explicit. |

**Headline**: out of 16 cases, 9 are clean, 5 work-but-awkward, 2 break ergonomically (#3 mixed-vt tx, #7 carve-a-hole, #8 bulk migration backfill). All three break-cases share a root cause: **valid-time is per-tx, not per-datom**. Restating the gap as a single sentence: kontor implements **document-level vt with tx as the document boundary**, which is strictly weaker than XTDB v2's **per-row vt with portion-update primitives**.

## 4. Datahike query-engine recommendations

### 4.1 Graduate `:tx/valid-from` / `:tx/valid-to` to datahike-native tx-meta

The kontor schema is currently namespaced under `:tx/*` (`bitemporal.clj:75-92`).
Two attributes, both `:db.type/instant`, both indexed. They are *just regular
datoms on the tx entity* — datahike doesn't know they have temporal semantics.

**Minimum upgrade**:

1. Reserve `:db/valid-from` and `:db/valid-to` as **datahike system attributes** alongside `:db/txInstant` (mirrors how Datomic reserves `:db/txInstant`). Move sentinel defaults (`forever`, `dawn`) into datahike constants.
2. Provide three query APIs:
   - `(d/valid-as-of db vt)` — analogous to `d/as-of`, returns a `ValidDB` filter view where every datom is visible iff its tx's `[vf, vt)` contains `vt`.
   - `(d/valid-between db vf vt)` — same but the filter window is `[vf, vt)`, returning every datom whose vt-window intersects.
   - `(d/valid-history db)` — the analog of `d/history` along the vt axis, returning a relation `[e a v t vf vt]`.
3. Compose: `(-> db (d/as-of tx-id) (d/valid-as-of vt))` gives the bitemporal lattice point. The compose order should commute.

### 4.2 Per-datom valid-time — the harder question

The honest answer is: **don't do per-datom vt as a first move**. Two reasons:

- Datahike's index store (`src/datahike/index/persistent_set.cljc`) keys on `[e a v t]` 4-tuples. Carrying vt as a fifth tuple component would re-shape the index store; that's a tree-rewrite, not a feature.
- The 80%-case (showcases 1-4 + every companion module) is satisfied by tx-scoped vt. The remaining 20% — case #3 (mixed-vt tx), #7 (carve a hole), #8 (bulk backfill) — is better served by **higher-level write helpers** that split into N txes than by reshaping the EAV invariant.

If per-datom vt becomes a hard requirement: the realistic shape is a sibling
attr per business attribute (e.g. for `:posting/amount`, define `:posting/amount+vf`
and `:posting/amount+vt` as a composite tuple-attr; or use datahike's
`:db.type/tuple` to model `[amount vf vt]` as the value). Schema-explicit,
indexable on each component, no engine change. This is the cost of staying
within datahike's EAV invariant.

### 4.3 Period predicates as datalog primitives

The Allen-relation set XTDB v2 implements (`xpr/temporal.clj:1830-1840`) is
the right shape. Datahike should expose at minimum:

- `(period-overlaps? vf1 vt1 vf2 vt2)` — symmetric overlap, half-open.
- `(period-contains? vf-outer vt-outer vf-inner vt-inner)`.
- `(period-contains-instant? vf vt instant)`.
- `(period-precedes? vf1 vt1 vf2 vt2)` / `(period-meets? ...)` for Allen completeness.
- `(period-intersection vf1 vt1 vf2 vt2)` — returns `[vf-out vt-out]` or `nil`.

These can be added as **built-in datalog predicates** in
`src/datahike/built_ins.cljc` (or wherever the comparison/arithmetic predicates
land) — pure Clojure fns over `Date`/`Instant` — without any storage change.
The win is they participate in `:where` clauses and the planner can hoist them.

### 4.4 Index shape

For range queries on vf/vt, the realistic choice given datahike's PSS-backed
index store is:

- **Phase 1**: rely on the existing `AEVT` index for `[?tx :tx/valid-from ?vf]` — a normal range scan over a `BigDecimal`-comparable `Date`. This is what kontor does today; it's slow because each candidate `[e a v t]` needs the tx-`get-else`. Estimate: O(history-size) per query, no pushdown.
- **Phase 2**: an **`AVET` composite secondary index on `(attr, vf, vt)`** per datom (synthesized from the tx's vf/vt) — written at commit time, queryable as a range. Implementation: extend the secondary-index infrastructure in `src/datahike/index/secondary.cljc`. This buys range-scan pushdown for `value-at` / `values-between`.
- **Phase 3 (deferred)**: an interval tree per `(e, a)`. The constant factor against `AVET+range` is small until you have hundreds of thousands of overlapping intervals on the same entity. Premature.

The **right v1 step** is Phase 1 with the predicates from §4.3 — that unblocks
kontor and the showcases without changing the storage layer. Phase 2 is a Stage
S item, gated by benchmark evidence (showcase 4's intercompany set is the right
stress test).

## 5. Stratum / Proximum / Scriptum extension hooks

### Stratum (columnar SQL)

Stratum already has `:temporal-unit` metadata on long-shaped columns
(`stratum/column.clj:31`, `stratum/api.clj:60-122`) — that's time-series unit
tagging (micros/seconds), not valid-time. It also has **ASOF joins**
(`stratum/sql/rewrite.clj:111-185`, `stratum/sql.clj:1252`) which are kdb-style
"last known value as of t" — semantically half a bitemporal: it answers "what
was the most recent left row whose time ≤ each right row's time", *along
one axis*.

Natural shape for vt: a **`_valid_from`/`_valid_to` column pair per row**,
identical to XTDB v2. Stratum is already columnar Arrow; adding two more
columns plus the period predicates is the smallest change. The branchable-table
axis (`README.md:11-13`) maps to tx-time / system-time naturally — every
branch is a snapshot. The new axis is vt.

**Minimum API extension**:
- Add `:valid-time` to `q`'s opts map, accepting `:as-of`, `[:between t1 t2]`, `:all`.
- Add `_valid_time` as a virtual range column (period type) computed from the pair.
- Add `OVERLAPS`/`CONTAINS` to the SQL parser and codegen them against the pair.
- Most powerful win: an **`ASOF JOIN ... ON ... WITHIN VALID_TIME`** that combines kdb-style asof with bitemporal carving. There's no SQL standard for this and stratum has the freedom to define it.

### Proximum (vector DB with git-branching)

Proximum's branching axis is tx-time (every commit is a `commit-id`,
`README.md`). Valid-time is orthogonal. The natural shape is **vector-with-validity**:
each `(id, vector)` pair has a vt window `[vf, vt)`. Searches take a vt
parameter and constrain to vectors whose window contains it.

**Minimum extension**: an optional `valid-from`/`valid-to` per inserted
vector, plus a `(prox/search idx q k {:valid-at t})` filter. Storage: HNSW
node metadata extended by 16 bytes per vector (two `Long` micros). At query
time, prune candidates against the vt filter before distance computation.
Tiny implementation; large semantic win for embedding-versioned RAG ("show
me what we knew about this document on Jan 15").

The branching axis stays orthogonal: a branch is a *system-time* snapshot of
the index, and inside that snapshot every vector still has its own vt window.

### Scriptum (CoW Lucene)

Scriptum already does branching + cryptographic-hash content addressing
(`README.md`). For full-text, valid-time maps to a **document-level vt window
stored in two Lucene `LongPoint` fields**: `_valid_from`, `_valid_to`. The
yggdrasil integration (`scriptum/yggdrasil.clj`) already exposes snapshot-id;
the new axis is per-document.

**Minimum extension**: a `BooleanQuery` helper that adds the period filter,
plus a sugared `(sc/search-as-of writer query t)`. Lucene's range queries are
fast enough that no special index is needed. Per-row vt slots in naturally
because Lucene documents are already typed-multi-field.

### Cross-cutting

The same `_valid_from`/`_valid_to` pair-column convention works across all
three engines, mirrors XTDB v2's column shape, and matches kontor's
`:tx/valid-from`/`:tx/valid-to`. The replikativ stack would converge on
**"two timestamps per fact, half-open, sentinel `+∞` for open-ended"** as the
common bitemporal contract.

## 6. Yggdrasil — recommend option (C)

The choices, reframed against the cross-stack evidence:

- **(A) Snapshot ID = `(tx-snapshot, valid-time)` 2-tuple**. Rigid: Git, ZFS, Docker have no native vt — adapters would have to fabricate one (`vt := commit-time`?), conflating axes. Cross-system PSI semantics get muddier, not clearer.
- **(B) Yggdrasil unaware, adapters expose vt-aware reads**. Leaky: cross-system temporal joins (Datahike posting at vt=Y joined with Scriptum invoice text valid at vt=Y) become per-adapter ad-hoc dances. The whole point of yggdrasil is uniformity.
- **(C) Optional vt axis at the workspace level**. A workspace can be "at snapshot=X, vt=Y". Systems that don't support vt simply ignore the vt axis (returning their normal snapshot view); systems that do support vt apply the filter. Yggdrasil-level operations like `commit-seq!` (`DESIGN.md:367-373`) take an optional `:valid-time` that propagates to each system's adapter.

### Sketch of the protocol delta

```
Layer 1 (Snapshotable) — add:
  valid-time?    : System → Boolean    ; does this system support vt?
  valid-as-of    : System × Instant → ReadView  ; if supported
  valid-between  : System × Instant × Instant → ReadView  ; if supported

Layer 5 (Workspace) — extend:
  (workspace {:branch "main"
              :systems [datahike scriptum proximum]
              :valid-time #inst "2024-Q4"})    ; optional axis

  Reads composed via:
    (-> system (as-of snapshot-id) (valid-as-of vt))

  Commits (commit-seq!) — extend:
    (commit-seq! overlays {:valid-time vt})    ; propagates per-adapter
```

Three properties:

1. **Optionality**: workspace-level vt is a no-op for Git/ZFS/Docker, applied to Datahike/Scriptum/Proximum/Stratum, mandatory for kontor reads.
2. **Composition**: tx-time (snapshot) and vt commute. The workspace materializes the lattice point.
3. **HLC interplay**: HLC stays on the tx-time axis (it's a *write-ordering* clock). Vt is application-time and doesn't need causal ordering — it's data.

The protocol delta is ~30 lines of Clojure spec plus three default-`false`
adapter methods. Cheap to introduce; the question is mostly whether each
system implements the methods — Datahike via §4, Scriptum/Proximum via §5,
Git via "ignore", Docker via "ignore".

## 7. Atomic cross-DB transact

Datahike already piggy-backs on konserve's `PMultiWritable` for atomic
multi-key writes inside *one* store (`konserve/protocols.cljc:20-37`,
`datahike/versioning.cljc:190`, `datahike/writing.cljc:326`). The extension is
to make this cross-database.

### By backend class

- **konserve-mem, konserve-rocksdb, konserve-lmdb**: single-store only. No cross-DB atomicity unless the two DBs share the same LMDB env (single transaction across multiple sub-DBs is then possible). Realistic answer: co-locate.
- **konserve-jdbc (Postgres)**: Postgres has true multi-statement transactions and `BEGIN ... COMMIT`. A `multi-transact!` across two datahike connections backed by the same Postgres instance is achievable by **threading a shared JDBC `Connection`** through both konserve-jdbc store instances, calling `BEGIN`, queueing both DBs' multi-assoc writes, then `COMMIT`. Two changes: konserve-jdbc must implement `PMultiWritable` and accept an external transaction; datahike must expose a `multi-transact!` that opens the JDBC tx and threads it. Cross-Postgres-instance: needs 2PC, which Postgres supports (`PREPARE TRANSACTION`).
- **konserve-foundationdb** (if added): FoundationDB natively does multi-key cross-subspace transactions; trivially atomic.
- **konserve-s3, konserve-gcs, konserve-ddb-s3, konserve-clutch**: no multi-put. Right answer is **write-ahead log + idempotent replay**: log the planned multi-DB tx into a durable journal (e.g. one shared Postgres table or an S3 multipart write), then apply each DB's write; on partial failure, replay from the log. The semantics are *eventually atomic* — readers in between see partial state — so this is acceptable only for back-office writes, not for showcase 4's intercompany invariant.

### Minimum API

```
(d/multi-transact! [[conn1 tx-data1 tx-meta1]
                    [conn2 tx-data2 tx-meta2]] opts)
```

Returns one `tx-report` per conn on success, throws on partial failure.
Implementation tiers:

1. **Same-store (single konserve)**: today's `multi-assoc` already works; just expose `multi-transact!` as a thin orchestrator.
2. **Same-backend, different stores (e.g. two Postgres-backed konserve instances on the same DB)**: open one JDBC tx, route both writes through it, commit.
3. **Different backends**: fall back to WAL+replay; idempotency keyed by the planned tx's content hash (kontor's audit-chain commit hash, ADR-007, is perfect).

### Yggdrasil composition

This sits **inside** yggdrasil's `commit-seq!` for the same-store and same-backend
cases (yggdrasil just calls `(d/multi-transact! ...)` for the datahike-only
slice); yggdrasil adds the **cross-system** layer above it for Datahike-x-Scriptum-x-Proximum
via the overlay-as-prepare pattern (`DESIGN.md:350-373`). Different backends with
no multi-put fall to overlay-as-prepare too. The protocol is:

```
yggdrasil/commit-seq!
  ├── for systems sharing a konserve store: d/multi-transact! (atomic)
  ├── for same-backend Postgres: shared JDBC tx (atomic)
  └── for heterogeneous backends:    overlay-as-prepare (saga)
```

So `multi-transact!` is **datahike-level** for the same-store case, with
yggdrasil promoting it to the cross-system orchestrator above. Don't put the
cross-system saga in datahike — that's a layering violation.

## 8. Sequenced plan

**Ship to unblock kontor v2 + simmis integration**:

1. **datahike — period predicates (§4.3)**. Pure-Clojure built-ins, no storage change. Unlocks `vt-overlaps?` etc. inside datalog `:where` bodies for kontor showcases and simmis UI. Estimated: 2-3 days.
2. **datahike — `d/valid-as-of` + `d/valid-between` + `d/valid-history` (§4.1)**. Reserved `:db/valid-from`/`:db/valid-to` system attrs, filter-DB types. Kontor's `kontor.bitemporal` becomes a 50-line shim over these. Estimated: 1-2 weeks.
3. **kontor — migrate to the new datahike primitives**. ADR-070 retires `:tx/valid-from` in favour of `:db/valid-from`; `kontor.bitemporal` keeps its high-level helpers (`assertion-at`, `timeline`, period-predicate wrappers) as thin sugar.
4. **datahike — `multi-transact!` for same-store case (§7 tier 1)**. Cheap (`konserve/multi-assoc` already works). Unblocks atomic invoice+posting in beleg.
5. **yggdrasil — workspace-level vt axis (§6 option C)**. Spec the protocol delta; default implementations for Git/ZFS/Docker are no-ops; Datahike implementation lands with step 2.
6. **kontor v2 — replace `with-vt` with a `(d/transact conn tx-data {:valid-time [vf vt]})` opts-map form**. Eliminates the "magic tx-meta map" pattern from showcases 1-4 and from companion modules. Cleaner than `kbt/with-vt`.

**Defer to a yggdrasil-2 milestone**:

7. **Stratum/Proximum/Scriptum vt extensions (§5)**. Independent of kontor's hot path; ship as each system reaches its respective 1.0.
8. **Per-datom valid-time in datahike (§4.2)**. Only if a real consumer surfaces a need that bulk-backfill helpers can't satisfy.
9. **AVET secondary index on `(attr, vf, vt)` (§4.4 Phase 2)**. Gated by benchmark evidence — kontor showcase 4 + a bulk backfill stress test are the gates.
10. **Cross-backend atomic transact via WAL+replay (§7 tier 3)**. Mostly relevant for hybrid Postgres-x-S3 deployments; the use cases are still few.
11. **2PC across multiple Postgres instances (§7)**. Postgres `PREPARE TRANSACTION` works but the operational story (recovering hung 2PC txes) is heavy. Defer until a real multi-tenant deployment asks.

### Headline

kontor's tx-scoped vt model is **semantically correct** and **cleanly composed
with datahike's tx-time axis**. It is **ergonomically weaker than XTDB v2's
per-row vt with portion-update primitives**, but the gap is closeable in
datahike — not by reshaping EAV, but by graduating valid-time to first-class
tx-meta, adding period predicates as built-in datalog fns, and exposing a
small set of bitemporal db-filter views. yggdrasil should treat valid-time as
an **optional fourth axis at the workspace level** — orthogonal to its
existing snapshot/branch/HLC axes. Atomic cross-DB transact is mostly a
plumbing exercise on top of konserve's existing `PMultiWritable`; the
cross-backend saga case correctly belongs above datahike in yggdrasil.

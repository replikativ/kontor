---
date: 2026-05-17
title: 75 — kontor + stratum integration: practical plan
status: draft
audience: implementer about to wire stratum as a datahike secondary index for kontor's three OLAP query paths
related:
  - doc/research/55-bitemporal-cross-db-survey.md (the cross-DB landscape)
  - doc/research/61-stratum-vs-xtdb-gap-analysis.md (what stratum can/can't do, vs XTDB)
  - doc/research/68-bitemporal-port-and-stratum-plan.md (the *which paths* analysis — this note is the follow-up *how*)
  - doc/research/71-cross-db-atomic-transact.md (yggdrasil / composite — provides the multi-system context this plan does NOT need to invent)
  - ADR-001 (single-dep kernel — discussed at §4)
  - ADR-008 (every read is bitemporal — drives §1's vt requirement)
  - ADR-021 (parallel ledgers — explains the `:posting/ledger` denorm in §3)
  - ADR-031 (per-(entity, ledger, commodity) sum-to-zero — explains `:posting/entity` denorm in §3)
  - feature/bitemporal-v1 (datahike branch — the secondary-index registration surface kontor consumes)
---

# 75 — kontor + stratum integration: practical plan

## TL;DR

- **Stratum is wired through datahike, not alongside it.** The
  integration model the brief assumed (`stratum.datahike` adapter +
  `vt-append!` primitives) does not exist in stratum itself — those
  primitives live in **datahike-side** at
  `datahike/src-secondary/datahike/index/secondary/stratum.clj`
  (feature/bitemporal-v1). Stratum exposes a generic
  `stratum.api/q` + `stratum.dataset/append!`/`upsert!`/`retract!`
  + a bitemporal `:metadata` flag on `make-dataset`. The secondary
  adapter is the bridge. Kontor wires *one schema declaration* — no
  imperative install! — and the existing transactor pipeline
  populates and queries the index. **This shrinks the kontor-side
  surface from ~150 LoC to ~40 LoC.**
- **Three query paths to wire, in order.** Trial-balance →
  report-engine → aging. All three are well-defined OLAP scans that
  currently `d/q + d/pull + reduce` over O(all postings). All three
  are *bitemporal*, so they need the `IValidTimeAware` push-down — the
  vt-mode stratum secondary handles that natively.
- **Threshold ≈ 100k postings.** Below that, today's datalog
  reduction is fast enough (sub-100ms) and the per-tx maintenance
  cost of stratum dominates. Above that, the SIMD aggregate
  outperforms by 10×–100×, and trial-balance gets the biggest win
  because today's per-account inner loop is O(accounts × postings).
- **Recommend dependency posture (c): optional lazy-resolve.**
  `kontor.deps.edn` does NOT pull stratum; consumers add
  `org.replikativ/stratum` themselves AND declare the secondary in
  their schema. Kontor ships **one tiny namespace**
  (`kontor.index.stratum`) that contains (a) the schema fragment
  helper, (b) the `requiring-resolve` calls into the
  datahike-secondary adapter, (c) the "if registered, use; else fall
  back" routers for the three query paths. ADR-001 stays intact
  because stratum is never a *direct* kontor dep — the kernel keeps
  compiling against datahike alone.
- **Backfill is free.** Stratum's secondary adapter walks AEVT on
  first attach and seeds rows with `[MIN_VALUE, MAX_VALUE)` vt
  windows (per `stratum.clj:380-420` in the bitemporal-v1 secondary).
  Consumers who add the secondary to an already-populated kontor DB
  get the index populated on next connect.
- **Blocker: feature/bitemporal-v1 must land on datahike main.**
  Until it does, this work is contingent on the same local-root deps
  the bitemporal port (note 68) already uses. The secondary itself
  is feature-complete on the branch (`-search-at-vt`,
  `-columnar-aggregate`, `IVersionedSecondaryIndex` all implemented).
- **Total effort: ~3 weeks of focused engineering** across three
  checkpoints. Each checkpoint ships a roadmap-tickable slice with a
  bench harness proving the win at 10k / 100k / 1M postings.

---

## 1. Top-3 query paths (operational detail)

### 1.1 `kontor.trial/trial-balance` — RANK 1

**Today's shape** (`src/kontor/trial.clj:25-53`): iterate every
account eid (`all-account-eids`), call `balance/account-balance`
per account, which runs `pull-postings-against`
(`balance.clj:58-99`) — a `d/q` for the account's postings + a
per-row `d/pull` of 4 attrs + nested account/tx. For a book of
`A` accounts × average `P` postings/account, the total cost is
`O(A × P)` pulls plus the Clojure-side reduce.

**What stratum accelerates.** Both queries collapse to one columnar
aggregate against a stratum-backed `posting_index`:

```clojure
(st/q {:from posting-index
       :where [[:<= :_valid_from as-of-valid-micros]
               [:>   :_valid_to   as-of-valid-micros]
               [:in  :tx-state   #{:posted}]]
       :group [:account-eid :commodity-eid]
       :agg   [[:sum :amount-cents]]})
```

The `IValidTimeAware` push-down (`stratum.clj:625-660` in
datahike-secondary) injects the two vt predicates *before* the
SIMD scan; zone-map pruning skips chunks whose [min, max] vt
range can't intersect; the aggregate runs in one fused pass.

**Expected speedup.** Today: ~1ms per account at 1k × 1k ≈ 1s.
Stratum: a single 1M-row aggregate ≈ 50-100ms (per stratum's
TPC-H Q1 of 75ms at 10M rows). **10×-20× at 100k postings;
~50× at 1M.**

**Threshold.** ~100k total postings (per note 68 §4.1 + stratum
bench numbers). Below that, the per-tx maintenance cost makes
the secondary a net loss; above, the fixed cost is paid back
many times.

**Bitemporal requirement.** Yes — vt-mode mandatory. The
current-only mode would force a Clojure-side post-filter and
lose most of the speedup.

### 1.2 `kontor.report/compute-report` — RANK 2

**Today's shape** (`src/kontor/report.clj:283-309`): `all-pids`
materializes **every** posting eid via
`(d/q '[:find [?p ...] :where [?p :posting/account _]])`; then
`pull-posting` (`report.clj:72-123`) does 3-4 d/pulls per posting
+ a separate `d/q` for the posting's tx valid-from. For 100k
postings, that's ~400k db-touches; the 12-line UStVA then
re-iterates the pulled vec per line.

**What stratum accelerates.** Both engines (`:account-codes` and
`:tax-tags`) reduce to `WHERE ... GROUP BY <engine-line> SUM`. The
account-code engine pushes a `:like` on `:account-code` plus the
vt/tx-state predicates; the tax-tags engine needs the posting's
tag-set materialized as either a denormalized string column or
(preferred) one bool column per active tag — landing the
intersection in pure SIMD.

**Expected speedup.** Stratum runs each line in a single
100k-row scan (~10ms). **30×-100× at 100k postings.** A 20-line
P&L on 1M postings: legacy ≈30s, stratum ≈300ms.

**Threshold.** Same ~100k as trial-balance, but the relative
win is *larger* because today's path repeats d/pull per report
line.

**Bitemporal requirement.** Yes. `IValidTimeAware` handles vt;
as-of-tx uses stratum's system-time axis (populated from
`:db/txInstant` per `stratum.clj:331-340`). **The most demanding
path** for stratum's bitemporal story.

### 1.3 `kontor.aging/aging-rows` — RANK 3

**Today's shape** (`src/kontor/aging.clj:57-89` +
`src/kontor/reconciliation.clj:133-203`):

Two `d/q`s per call:
- `reconciliation.clj:147-160` — every AR-account posting joined
  with its tx external-id, partner, date, journal-type, state.
- `reconciliation.clj:176-184` — every settling posting (a tx
  with `:transaction/settles`).

Then a Clojure-side `reduce` to group by tx and subtract
settlements; then `aging-rows` adds bucketing on top.

**What stratum accelerates.** Each `d/q` becomes one stratum
aggregate (sum by transaction-eid); the cross-tx settlement
subtraction is a stratum LEFT JOIN on the two aggregates (or a
Clojure-side merge if the JOIN grammar doesn't fit cleanly).
The bucketing stays in Clojure — it's a per-row CASE that's fast
on a few thousand rows. The `:transaction/settles` ref must be
denormalized into the posting row (as `:settles-eid`) so the
join runs fully in the columnar engine.

**Expected speedup.** Year-of-AR (~10k tx × 3 postings = ~30k
rows): legacy ~500ms, stratum ~30-50ms. **10×-15× at 30k rows;
bigger at multi-year retention.**

**Threshold.** Lower than the other two (~10k AR postings)
because today's two-query path materializes a cross-product
before reducing. Aging is also interactive (collections
workflow), so 500ms→50ms is the most user-visible win.

**Bitemporal requirement.** Yes, but relaxed — usually run "as
of today, as known today" — the point-form `-search-at-vt`.

---

## 2. Stratum API surface kontor would call

The brief assumed kontor would talk to stratum directly. The actual
shape is: **kontor talks to datahike, datahike's secondary adapter
talks to stratum.** Three layers, three different APIs.

### 2.1 Index registration (one-time at boot, via schema)

Kontor consumers add **one schema entry** to their datahike schema.
No imperative call:

```clojure
{:posting-analytics
 {:db.secondary/type :stratum
  :db.secondary/attrs [:posting/account :posting/commodity
                       :posting/amount :posting/transaction
                       :posting/entity :posting/ledger
                       :posting/account-tags]
  :db.secondary/config {:vt-mode? true}}}
```

This is parsed by datahike at connect-time:
- `datahike/writing.cljc:155-200` (`restore-secondary-indices`) —
  detects `:db.secondary/type`, looks up the registered factory,
  builds the StratumIndex instance.
- `datahike/index/secondary.cljc:132-149`
  (`register-index-type!`) — the registry; the stratum adapter
  registers itself under both `:stratum` and
  `:datahike.index.secondary/stratum` at load time
  (`stratum.clj:1042-1055`).
- `datahike/connector.cljc:226` — wires the index into the
  connection.

**What kontor calls:** nothing imperative. Just emits the schema
fragment via `kontor.index.stratum/secondary-schema-fragment`
(~30 LoC, see §3).

### 2.2 Per-tx maintenance (synchronous, transactor-internal)

After the schema is in place, every `(d/transact conn …)` that
writes a posting datom triggers:

- `datahike.writing` (transactor) invokes the index's
  `-as-transient` (`stratum.clj:521-527`) once at tx-start.
- For each datom in the tx, `-transact!` (`stratum.clj:686-708`)
  buffers into `pending-adds` / `pending-retracts` HashMaps.
- At tx-commit, `-persistent!` (`stratum.clj:710-714`) calls
  `persist-transient-stratum-index` which dispatches to the
  vt-aware path (`vt-persist-transient-stratum-index`,
  `stratum.clj:949-998`).
- The vt-aware path reads `tx-meta` (the `:db.valid/from`,
  `:db.valid/to`, `:db/txInstant` on the tx-entity) and runs SCD2
  surgery on stratum's columnar storage: closes superseded rows'
  `_valid_to`, appends new rows with `[vf, vt)` and `[sys-now,
  MAX)`.
- `-sec-flush` (`stratum.clj:551-563`) is called at branch flush;
  the dataset's `sync!` returns a content-addressed commit-id
  that gets folded into the datahike commit hash (audit chain
  per ADR-066 + research note 71).

**Contract.** Synchronous; runs inside the datahike write lock; a
throw aborts the tx (the StratumIndex stays at its pre-tx state).
No callback hooks for kontor — the transactor owns the lifecycle.

**What kontor calls:** nothing. The maintenance is fully
transparent. **This is the key architectural property** that
keeps the kontor-side wiring tiny.

### 2.3 Query (read calls)

Kontor's query paths route through one of two protocol calls,
not direct stratum calls:

- `sec/-search-at-vt` (vt-aware entity-bitset, for filter-then-pull
  shapes — `secondary.cljc:128-149` defines the protocol;
  `stratum.clj:625-660` implements it). Returns an EntityBitSet
  the planner intersects with the rest of the query.
- `sec/-columnar-aggregate` (group-by + sum directly on the
  columnar storage, no entity-bitset materialization —
  `secondary.cljc:71-78` defines it; `stratum.clj:608-622`
  implements it). Returns a vec of maps `[{:account-eid 42
  :commodity-eid 7 :sum 12345} ...]` ready for the kontor caller
  to lift back into Money objects.

Both are kept private to datahike's planner *for the datalog
path*. For kontor's purposes — where the report engine is already
a Clojure-side reduce — we want to call `-columnar-aggregate`
**directly**, bypassing datalog entirely for these three OLAP
paths. The datahike maintainers have signalled this is the
intended use (the protocol is public for exactly this).

**What kontor calls.** `sec/-columnar-aggregate` and
`sec/-search-at-vt`, after dereferencing the live index instance
from `(d/db conn)`'s secondary-indices map. Kontor wraps this in
a single helper (`kontor.index.stratum/aggregate`, ~10 LoC) so the
three accelerated paths don't reach into internal connector state.

### 2.4 Backfill (when registering against a populated DB)

`build-initial-dataset` (`stratum.clj:378-440`) walks AEVT for
each indexed attr at index-creation time. Each pre-existing
posting gets seeded with vt-window `[MIN_VALUE, MAX_VALUE)` and
the same for system-time. Subsequent vt-aware txes close those
windows normally via SCD2. The backfill runs automatically when
`restore-secondary-indices` (`writing.cljc:188-191`) finds a
schema entry without a stored key-map.

**Limitation.** Pre-existing postings' real valid-from is **not**
reconstructed — current-state queries are correct (SCD2 picks the
latest), but historical as-of-valid queries on pre-stratum data
return the current value, not the point-in-time-correct one. For
greenfield consumers, fine; for tax-audit reconstruction,
`kontor.index.stratum/backfill-from-history!` (~50 LoC) walks
`d/history` and replays vt-windows. Ship lazily.

**What kontor calls:** nothing imperative.

### 2.5 Lifecycle (close, GC, persistence)

In-process; no separate stratum process. GC walks `-sec-mark` +
`mark-from-key-map :stratum` (`stratum.clj:1060-1095`) — full
transitive walk of the dataset commit chain in datahike's
konserve. Every datahike commit triggers a stratum `sync!`; the
dataset commit-id is folded into the audit-chain via
`-merkle-root`. Branching and time-travel work via `-sec-branch`
and `-sec-restore`. **What kontor calls:** nothing.

---

## 3. Wiring proposal — namespace by namespace

### 3.1 New: `kontor.index.stratum`

The **only new kontor namespace.** ~150 LoC total. Lives at
`src/kontor/index/stratum.clj`.

**Responsibilities:**
1. Emit the secondary-index schema fragment consumers add to
   their datahike schema.
2. Look up the live index instance from a kontor `conn` (one
   helper that hides the internal-state dereference).
3. Wrap `sec/-columnar-aggregate` and `sec/-search-at-vt` with
   `requiring-resolve` so the namespace loads even when stratum
   is not on the classpath.
4. Expose three high-level helpers — `trial-balance-stratum`,
   `compute-report-stratum`, `aging-rows-stratum` — that the
   three accelerated paths in `kontor.trial` /
   `kontor.report` / `kontor.aging` delegate to.

**Public API (signatures):**

```clojure
(ns kontor.index.stratum)

(defn enabled? [conn idx-ident])             ;; true iff stratum on cp + idx registered

(def secondary-schema-fragment                ;; the drop-in schema entry
  {:posting-analytics
   {:db.secondary/type :stratum
    :db.secondary/attrs [:posting/account :posting/commodity
                         :posting/amount :posting/transaction
                         :posting/entity :posting/ledger
                         :posting/account-tags]
    :db.secondary/config {:vt-mode? true}}})

(defn install-schema! [conn])                 ;; transact the fragment (idempotent)

(defn aggregate [conn query-spec])            ;; thin wrap of -columnar-aggregate
(defn vt-search [conn query-spec valid-at])   ;; thin wrap of -search-at-vt

(defn trial-balance-stratum [conn opts])      ;; the three accelerated paths
(defn compute-report-stratum [conn report opts])
(defn aging-rows-stratum [conn account-codes opts])

(defn backfill-from-history! [conn])          ;; optional vt-window replay
```

**Dependencies.** The namespace requires only `datahike.api` and
`datahike.index.secondary` (the protocol surface). It calls
`requiring-resolve` for the actual stratum-adapter symbols so the
file compiles on a kontor classpath that doesn't include the
secondary adapter.

### 3.2 Modified: `kontor.trial`

**Today's `trial-balance`** (`src/kontor/trial.clj:25-53`) stays
as `trial-balance-datalog` (still exposed for parity tests). The
public `trial-balance` becomes a 3-line dispatch: if
`(requiring-resolve 'kontor.index.stratum/enabled?)` returns
true (and the caller hasn't passed `:force-datalog?`), delegate
to `kontor.index.stratum/trial-balance-stratum`; else call the
datalog body.

`balance/account-balance` does **not** route through stratum
directly — the per-account use case is below the threshold. But
when callers ask for many accounts (e.g. via `trial-balance` or
`balance/account-balances-bulk`), the stratum path wins. The
opportunistic acceleration for the bulk case lives in
`kontor.index.stratum/trial-balance-stratum`.

### 3.3 Modified: `kontor.report`

Same shape. `compute-report` gains the 3-line dispatch; the
existing body becomes `compute-report-datalog`. The `defmulti`
on `run-engine` stays — it's part of the public API consumers
extend with custom engines. The stratum path implements
`run-engine-stratum` for the two built-in engines
(`:account-codes` and `:tax-tags`); unknown engines fall through
to the legacy reduce path even when stratum is enabled.

### 3.4 Modified: `kontor.aging`

Same dispatch. `aging-rows` checks for stratum; the
`reconciliation/open-receivables-by-tx` and `open-payables-by-tx`
helpers gain stratum-backed twins
(`open-receivables-by-tx-stratum`,
`open-payables-by-tx-stratum`) that live in
`kontor.index.stratum`.

### 3.5 Consumer opt-in shape

A kontor consumer (beleg, simmis, a custom app) gains stratum
acceleration in three steps:

1. Add `org.replikativ/stratum {:mvn/version "0.3.69"}` to their
   `deps.edn` (and optionally
   `io.replikativ/datahike-bitemporal-secondaries
   {:local/root "../datahike-bitemporal-v1/src-secondary"}` or
   the eventual published artifact).
2. Add `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
   to their `:jvm-opts` (stratum requires JDK 22+ with the Vector
   API).
3. Merge `kontor.index.stratum/secondary-schema-fragment` into
   their datahike schema before connect.

**No code changes** to existing kontor query callsites. The
dispatch in §3.2-§3.4 routes automatically.

### 3.6 Error / fallback

All three accelerated paths wrap the stratum call in `try/catch`:
on `Throwable`, log under `:kontor.index.stratum/fallback` and
delegate to the datalog body. Invisible to the caller; leaves a
breadcrumb for debugging. Per ADR-008 the two paths must be bit-
identical (modulo BigDecimal scale, which a post-step normalizes).

---

## 4. Dependency posture — the ADR-001 question

ADR-001 says **the kernel is single-dep on datahike**. Adding
stratum directly would amend that. Three options:

**(a) Companion artifact.** Ship `kontor-stratum-accel` as a
separate Clojars artifact. Kernel stays clean. Costs: a second
artifact to release, version-lockstep nightmares, and most
consumers won't know to add the second dep.

**(b) Amend ADR-001.** Make it "datahike + optional stratum".
Costs: ADR change (modest), but every kernel test now has to
either include or exclude stratum (some CI jobs need both
shapes), and the JDK-22 requirement (stratum needs the Vector
API) bleeds into kontor's published `:jvm-opts`.

**(c) Lazy `requiring-resolve`.** Kontor ships
`kontor.index.stratum` with all stratum calls behind
`(requiring-resolve 'stratum.foo/bar)`. The namespace compiles
without stratum on the classpath; the lookups return `nil` (and
`enabled?` returns false) so the kernel stays single-dep at
runtime AND compile-time. Consumers who want acceleration add
stratum + the datahike secondary adapter themselves.

**Recommendation: (c).** Three reasons:

1. **It preserves ADR-001 verbatim.** Kontor's published
   `deps.edn` has only datahike. The JDK-22 / Vector-API
   requirement stays a consumer concern. We do not push that
   onto consumers who only want the kernel.
2. **Stratum is genuinely optional.** Note 68 §4.1 establishes
   the ~100k posting threshold. Many kontor consumers (beleg,
   small-SMB books) never cross it; pulling stratum into the
   kernel forces a 20MB-jar weight on workloads that don't need
   it.
3. **The `requiring-resolve` shape is well-trodden.** The pattern
   appears in `datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:597-602`
   itself for the audit recompute. It's not novel.

**Cost.** The dispatch is slightly noisier than direct calls.
Mitigation: a tiny helper `(defn- ?resolve [sym] (when-let [v
(try (requiring-resolve sym) (catch Throwable _ nil))] @v))`
centralizes the pattern; the three call-sites become one-liners.

**ADR draft.** Worth opening **ADR-NN+1: "Stratum acceleration is
optional, lazy-resolved"** alongside the implementation. The ADR
documents the choice and gives consumers an opinionated install
recipe.

---

## 5. Backward compatibility + migration

### 5.1 Existing consumers (no stratum installed)

**Zero change.** The dispatch in §3.2-§3.4 sees `enabled? = false`
and routes to the datalog body; the test suite stays green. The
new `kontor.index.stratum` namespace compiles to a no-op (every
public fn short-circuits to false or throws "not enabled").

### 5.2 New consumers installing stratum on an existing DB

When the datahike connector first sees the schema entry, it calls
`build-initial-dataset`, which walks AEVT for the indexed attrs
and seeds the stratum dataset with one row per posting at vt-window
`[MIN, MAX)`. **Works after backfill:** current-state queries
(SCD2 picks the latest) + forward-going vt queries (new txes
carry real `:db.valid/from` and close the seed rows on first
update). **Doesn't:** historical as-of-valid queries on
pre-existing postings — see §2.4 limitation + the optional
`backfill-from-history!` escape hatch.

**Migration sequence:** (1) add stratum + datahike-secondary to
`deps.edn`, (2) restart with JDK-22 module flags, (3) merge
`secondary-schema-fragment` into the consumer's schema and
reconnect, (4) optional `backfill-from-history!`, (5) new writes
maintain the index automatically. No data migration; no
retract-and-rewrite; the kernel does nothing.

### 5.3 The feature/bitemporal-v1 dependency

The plan requires datahike's `feature/bitemporal-v1` branch
(currently at commit `89061a8f` per the maintainer's note). The
upstream merge to `main` is **not yet landed**. Until it is, the
work is blocked by the same local-root dep the bitemporal port
(note 68) already uses.

**Practical sequencing:** finish note 68's bitemporal port first
(it's a prerequisite — the secondary's vt push-down assumes
`:db.valid/from` on kontor's txes), THEN attach this stratum
plan. Note 68 explicitly opens the door at §4: "The other paths
either already use AVET seeks or have data volume too small to
matter." This note IS the follow-up to that door.

---

## 6. Testing strategy

Three layers, each with one minimum-viable test file.

### 6.1 Unit: `test/kontor/index/stratum_test.clj`

Asserts (1) `enabled?` returns false when stratum is off the
classpath and true when both the namespace and the schema entry
are present, (2) `secondary-schema-fragment` validates against
`datahike.schema/api-schema-spec`, (3) `aggregate` raises
`:type :kontor.index.stratum/not-enabled` when called without
stratum. Small file (~80 LoC); covers only the lazy-resolve
plumbing.

### 6.2 Integration: `test/kontor/index/stratum_parity_test.clj`

Setup: one in-memory datahike conn WITH stratum secondary
registered, one WITHOUT, same fixture postings on both. Asserts:
(1) `trial-balance`, `compute-report` (full DE UStVA), and
`aging-rows` (AR + AP) return identical results across ~20 opt
combinations (entity / ledger / as-of-tx / as-of-valid /
include-states / accounts-restriction), (2) maintenance happens
in-tx — `d/db` after `d/transact` reflects the stratum update
synchronously, no async fence, (3) vt-correctness: insert posting
vf=2026-01-01, `:as-of-valid 2025-12-31` → 0 rows,
`:as-of-valid 2026-01-02` → 1 row; same for SCD2 closed-window.
This is **the** load-bearing test; runs on every CI build.

### 6.3 Bench: `dev/kontor/index/stratum_bench.clj`

Three scales (10k / 100k / 1M postings, synthetic DE GmbH
fixture) × three queries (trial-balance, full UStVA,
year-of-AR aging). Side-by-side wall-clock + GC time per query,
written to `dev/bench-results/<git-sha>.edn`. Expected: stratum
0.5×-2× datalog at 10k (likely net loss; below threshold),
5×-20× at 100k (clear win), 30×-100× at 1M (design intent).
Failures > 2× in the wrong direction block merge.

---

## 7. Effort + sequencing

Three checkpoints, sized in *focused engineering days* (not
including the review-after rhythm per ADR-037, which adds ~1 day
per checkpoint).

### Checkpoint C1: Foundation + trial-balance (~5-7 days)

- D1 (1d): smoke-test datahike `feature/bitemporal-v1` + stratum
  `0.3.69` + datahike-secondary adapter coexist on a kontor
  local-root `deps.edn`; verify a trivial schema with one
  stratum index survives reconnect.
- D2 (1d): write the namespace `kontor.index.stratum` skeleton —
  `enabled?`, `secondary-schema-fragment`, `install-schema!`,
  the lazy-resolve helper.
- D3 (1-2d): implement `trial-balance-stratum` (the aggregate
  call + result-shaping back to `{eid {cm Money}}`).
- D4 (1d): dispatch in `kontor.trial`; unit + trial-balance
  parity tests.
- D5 (1d): first bench harness pass at 10k / 100k; calibrate
  the threshold.
- D6 (0.5d): draft ADR-NN+1 "Stratum acceleration is optional,
  lazy-resolved"; update `doc/roadmap.md`.

**Exit criterion.** Parity green; ≥5× at 100k; ADR drafted.

### Checkpoint C2: Report engine + bench validation (~5-7 days)

- E1 (0.5d): pick tag-projection shape — recommend per-tag bool
  columns (simpler SIMD; width is `O(tags-in-system)`, fine for
  DE UStVA's ~20 active tags; revisit if a consumer hits the
  hundreds — see §8 Q2).
- E2 (1d): extend the schema fragment with tag bool columns;
  backfill walks AEVT for `:account/tags` + `:posting/account-tags`.
- E3 (1d): `run-engine-stratum :account-codes` — code-prefix
  matching pre-resolved to an account-eid bitset, pushed as
  entity-filter.
- E4 (1d): `run-engine-stratum :tax-tags` — per-tag bool predicate.
- E5 (1d): dispatch in `kontor.report/compute-report`; full
  UStVA parity test.
- E6 (1d): bench at 10k / 100k / 1M; validate ≥10× at 100k.
- E7 (0.5d): `financial-statements/compute-statement` rides on
  E5 — no new code, just one dispatch line.

**Exit criterion.** Report parity green; ≥10× at 100k.

### Checkpoint C3: Aging + cleanup (~3-4 days)

- F1 (1d): extend fragment with the settlement denorm
  (`:transaction/settles` → posting-row `:settles-eid`); backfill.
- F2 (1d): `open-receivables-by-tx-stratum` /
  `open-payables-by-tx-stratum` (two aggregates + a JOIN, or
  Clojure-side merge if the JOIN grammar doesn't fit).
- F3 (0.5d): `aging-rows-stratum`; bucketing stays Clojure-side;
  parity test.
- F4 (0.5d): bench.
- F5 (1d): update `doc/architecture.md`, `doc/roadmap.md`,
  `CLAUDE.md`; merge ADR-NN+1.

**Exit criterion.** All three paths landed; ADR merged; CI runs
the 100k bench as a smoke (1M is opt-in).

### Cumulative

- **Total: 13-18 days of focused engineering.**
- **Critical path:** datahike-bitemporal-v1 merge to main
  (external blocker) → kontor bitemporal port (note 68) →
  C1 → C2 → C3.
- C2 and C3 can pipeline if a second engineer joins, but the
  bench at C1 sets the threshold for both — don't parallelize
  before C1's bench validates the design.

---

## 8. Open questions

1. **Datahike `feature/bitemporal-v1` merge timeline.** Work is
   blocked until the branch lands on main (or kontor commits to
   local-root indefinitely). Coordinate with note 68's port,
   which has the same dependency. *Maintainer input needed.*

2. **Per-tag column projection — explosive width?** Multi-year
   retention can accumulate hundreds of tags; one bool column per
   tag becomes painful. Alternatives: (a) a single dict-encoded
   "tag-vector" string column with `LIKE` predicates (slower but
   bounded width), (b) per-tag array column with `:in` membership
   (cleaner but needs a stratum feature we haven't confirmed).
   Investigate during E1.

3. **Backfill-from-history correctness.** The seed treats pre-
   existing postings as "valid forever". Consumers who need
   historical as-of-valid accuracy run
   `backfill-from-history!`. Do any kontor consumers actually
   rely on this? If yes, the helper must run automatically at
   first install; if no, ship as a documented escape hatch.

4. **System-time push-down for as-of-tx.** `IValidTimeAware`
   pushes valid-time only. Stratum has `_system_from` /
   `_system_to` but no dedicated push-down protocol. Recommend
   (a) falling back to datalog for non-current as-of-tx in v1
   (rare query); revisit if needed.

5. **Stratum dataset commit-id in kontor's audit chain.** Per
   note 71 §3 + stratum.clj's `-merkle-root`, the dataset
   commit-id folds into datahike's audit-chain automatically.
   Verify `kontor.audit` (ADR-066) survives the addition during
   C1 (one day).

6. **Stratum version pinning.** Use Clojars `0.3.69` (consumer-
   friendly) or `:local/root "../stratum"` (matches bitemporal-v1
   local-root pattern)? Recommend `:mvn/version` for the
   documented example; consumers override.

7. **JDK-22 requirement.** Stratum requires JDK 22 + Vector API
   + foreign-memory. Some kontor consumers are on JDK 17/21; the
   lazy-resolve approach keeps them working at datalog speeds
   but the *option* to accelerate is gated on a JDK upgrade.
   Document prominently in `doc/value.md`.

8. **Stratum's `IColumnarAggregate` multi-column GROUP BY.**
   `trial-balance` needs `(account_eid, commodity_eid)`. The SQL
   surface supports it; verify at C1 the protocol exposes it
   cleanly. Fallback: single-column group + Clojure-side rebucket.

9. **Per-entity / per-ledger composition.** ADR-031 means an
   entity-filtered trial balance is itself balanced. The stratum
   path must include `:entity-eid` / `:ledger-eid` in GROUP BY (or
   push as :where when the report restricts to one). Already in
   the §3.1 fragment; calling it out.

10. **Dispatch in kernel vs companion namespace.** Recommend
    keeping the 3-line dispatch in `kontor.trial` /
    `kontor.report` / `kontor.aging` rather than fully factoring
    into a companion artifact — the discoverability win (you see
    the acceleration path when reading the kernel module) beats
    the dependency-purity ideology, especially given the
    lazy-resolve keeps the runtime dep clean.

---

## Sources

- `doc/research/68-bitemporal-port-and-stratum-plan.md` — §4 enumerates the three OLAP paths; this note is the follow-up *how-to*.
- `doc/research/55-bitemporal-cross-db-survey.md` — cross-DB landscape; situates this work in the replikativ ecosystem.
- `doc/research/61-stratum-vs-xtdb-gap-analysis.md` — what stratum can/can't do; used to size §1 speedups.
- `doc/research/71-cross-db-atomic-transact.md` — yggdrasil + audit-chain integration; the merkle-root story in §2.5 + Q5.
- `../stratum/README.md` — stratum's public API (`st/q`, `st/make-dataset`, the bitemporal `:metadata` flag).
- `../stratum/src/stratum/api.clj` — public surface (`q`, `make-dataset`, `sync!`, `load`, `fork`, `resolve`).
- `../stratum/src/stratum/dataset.clj:90-160, :1300-1410` — `append!`, `upsert!`, `retract!`, `bounded-update!` — the SCD2 primitives the datahike adapter calls.
- `../datahike/src/datahike/index/secondary.cljc` — `ISecondaryIndex`, `IColumnarAggregate`, `IValidTimeAware`, `IVersionedSecondaryIndex` protocols. The integration target.
- `../datahike` `feature/bitemporal-v1`, `src-secondary/datahike/index/secondary/stratum.clj` (1102 LoC adapter): lines 378-440 (`build-initial-dataset` — §2.4 + §5.2), 480-545 (`StratumIndex` deftype — §2.3), 551-563 (`-sec-flush` — §2.5), 608-622 (`-columnar-aggregate` — §2.3), 625-660 (`-search-at-vt` — §2.3 + §1), 686-714 (transient maintenance — §2.2), 949-998 (SCD2 commit surgery), 1042-1055 (`register-index-type!` — kontor consumes via schema fragment).
- `src/kontor/trial.clj` — the rank-1 path.
- `src/kontor/balance.clj:58-99` — `pull-postings-against`, the inner loop replaced by the columnar aggregate.
- `src/kontor/report.clj:72-309` — the rank-2 path; `pull-posting` is the per-posting d/pull that goes away.
- `src/kontor/aging.clj:57-89` + `src/kontor/reconciliation.clj:133-262` — the rank-3 path.
- `CLAUDE.md` — single-dep posture (ADR-001) discussed at §4.

*Note 75 sits in the bitemporal/OLAP arc; predecessor is note 68
(which paths); the natural successor — once implementation lands —
is a review-after note 76 capturing P0/P1 findings and the bench
results at the three scales.*

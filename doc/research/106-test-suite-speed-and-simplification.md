---
date: 2026-05-21
title: 106 — Test-suite speed + simplification plan
status: superseded by §0 (2026-05-24) — dominant lever applied,
  the §1-§7 plan below is now historical context
audience: maintainer — read §0 first; the body is the analysis that
  preceded the §0 fix
supersedes-partially: note 96 (Lever 2 there assumed a kaocha parallel
  plugin that does not exist; Lever 1's `d/with` reuse is largely
  inapplicable — see §3)
---

# 106 — Test-suite speed + simplification plan

Note 96 measured the suite (~7m38s) and named three levers; remediation
was deferred "until consumer demand." The demand is now here. This note
re-measures, **corrects two factual errors in note 96**, and gives a
prioritized, coverage-preserving plan with a concrete target.

## 0 — Update 2026-05-24: applied via datahike branching API

The §1-§7 plan below names three levers — process-parallel sharding,
fix the bootstrap-in-`doseq` anti-pattern, and `:once` fixtures via a
shared `kontor.test-fixtures` helper. It **misses the dominant lever
entirely**: datahike's CoW branching API (`d/branch!` +
`d/connect (assoc cfg :branch …)`) on the `:memory` backend.

### What was applied

`kontor.core/create-test-db` rewritten to (a) build ONE schema'd
in-memory template DB lazily (once per JVM), and (b) per call,
`d/branch!` a fresh CoW branch off the template's `:db` and
`d/connect` to it. Connections deduplicate by `[store-id branch]`
(`datahike.connector.cljc:175`), so each branch is its own isolated,
writable DB. ~15-line change in `src/kontor/core.clj`; zero test-file
changes.

### Measured impact (REPL bench, warm JVM)

| Step | ms |
|---|---:|
| template build (once per JVM) | ~1100 |
| `d/branch!` + `d/connect` (per test) | **~1** |
| `d/delete-branch!` (per teardown) | ~0.6 |
| old `create-test-db` per call | ~560 |

Per-fixture speedup: **~560×**. The "GC pressure ~1.6 s" inflation
documented in §1 disappears with it (no per-test 505-attr schema
transaction).

### End-to-end suite result

`bb test`: **7m38s → 4m39.7s** on the same box, single process.
2407 tests / 9604 assertions / 0 failures. ~39% wall-time reduction.

### Why the §1-§7 plan missed it

§3 surveyed two approaches to amortizing schema install — `d/with`
on a shared db (rejected: only 1/274 files pure-query) and konserve
store-fork (rejected: connection caches schema separately). It did
not consider datahike's first-class **branching** primitive, which
solves the same problem cleanly: the connection cache isn't bypassed,
it's branch-keyed; isolation is enforced by the branching contract
(CoW indices, separate writer state).

This is a documentation/awareness gap, not a substrate one — the API
has been in datahike for some time (`doc/secondary-indices.md`
documents the `branch! + connect` recipe directly).

### What the §1-§7 levers buy from here

Still valid as **further** wins, but the priority order changes:

1. **Process-parallel sharding** (§5 Lever 1) — ~2× wall-time on a
   multi-core box. Now the biggest remaining lever. Worth it if/when
   4m39s feels slow.
2. **Bootstrap-in-`doseq` fix** (§5 Lever 2) — ~14 deftests still
   call `create-test-db` inside a loop, paying it 5-13×. With branch
   cost ~1 ms it's no longer dominant; saves ~10-20s, not the 60-100s
   originally projected.
3. **`:once` fixtures helper** (§5 Lever 4) — largely subsumed; the
   per-test cost is already ~1 ms. The motivation (sharing a costly
   bootstrap) is gone. Could still help the few namespaces with
   expensive per-test setup beyond `create-test-db`.

**Revised target if pursued further**: <2m with sharding alone; the
§5 Stage-3 ":once fixtures" work is now hard to justify on cost
grounds.

### What did not change

- The §1 decomposition (where the 960 ms went) is still accurate
  for `:once` per-process fixtures.
- The §2 "no safe in-JVM parallelism" finding stands — `defonce`
  store state, sealing middleware, etc. would still race.
- The §8 corrections to note 96 stand.

The rest of this note (§1-§8) is the assessment that preceded this
fix and remains useful as background for any future test-perf work.

---

## TL;DR

- Dominant cost: **`create-test-db` ≈ 0.96 s isolated, ≈ 1.6 s under
  GC load**, called ~354 times across 162 of 274 test files. Schema
  install (`kontor.schema/install!` ≈ 580 ms) + the seed/defaults
  bundle (≈ 340 ms) is ~95% of it; `d/create-database` is only ~40 ms.
- **Correction to note 96 Lever 2**: kaocha ships **no parallel
  plugin** (`kaocha-1.91.1392.jar` plugin list verified — only
  profiling / filter / randomize / hooks / notifier / gc-profiling /
  etc.). `kaocha.plugin/parallel-tests` does not exist. In-JVM
  parallelism is also unsafe (§2). Parallelism must be **process-level
  shard-and-merge**.
- **Correction to note 96 Lever 1**: `d/with` on a shared schema'd db
  is ~0.13 ms (4000× cheaper than a fresh db) — but only **1 of 274
  test files** (`money_test.clj`) is pure-query. 162 files do real
  `d/transact` / `!`-wrapper writes and need a live connection; 60+
  assert on whole-DB balances/counts and require per-test isolation.
  So `d/with` reuse is a near-non-lever here.
- The realistic, high-leverage, low-risk plan: **(1) process-parallel
  sharding** (≈ 8-core box → ~3–4× wall-time), **(2) fix the
  bootstrap-in-`doseq` anti-pattern** (~14 deftests re-boot a DB
  5–13× inside one test), **(3) a shared `kontor.test-fixtures` helper
  + `:once` namespace fixtures** for the namespaces whose tests do not
  assert isolation.
- Target: **default `bb test` ≤ 2m30s** on an 8-core dev box (from
  ~7m40s), with **CI doing a full unsharded run** as the correctness
  oracle. Coverage unchanged.

## 1 — Where the runtime goes

### Measured cost of the fixture

REPL benchmarks against the running nREPL (`kontor.core`, datahike
in-mem, warm JVM), `n`-iteration averages:

| Step | ms |
|---|---:|
| `d/create-database` + `d/connect` | ~40 |
| `kontor.schema/install!` (505 attrs, `src/kontor/schema.clj`) | ~580 |
| `+ ledger/valuation/legal-hold/retention/audit-doc/dsar seeds` | ~340 |
| **`core/create-test-db` total, warm** | **~960** |
| `core/create-test-db` measured mid-run (GC pressure) | **~1600** |
| `d/with` on a pre-built schema'd db value | ~0.13 |

`src/kontor/core.clj:78-92` — `install-schema!` runs `schema/install!`
then six `install-*!` seed calls. `src/kontor/core.clj:94-110` —
`create-test-db` does `create-database` → `connect` → `install-schema!`.
A schema'd test DB holds **~2766 datoms** before any test data.

### Why the run is slower than 960 ms × 2400

No test calls `d/delete-database` (0 hits across `test` +
`modules/*/test`). datahike keeps every connection in a process-global
registry (`datahike.connections/*connections*`, an atom) and the
~2400 in-mem stores stay heap-resident for the whole run. The GC
pressure inflates per-`create-test-db` cost from ~960 ms to ~1.6 s —
i.e. the lack of teardown is itself a ~1.5× tax.

### Slowest namespaces (note 96's profiling table, still representative)

Top-20 nses sum to ~170 s ≈ half the runtime; canonical shape is the
`:each` fixture re-installing the kernel schema **plus** companion
schemas per deftest. `kontor.procurement.forward-flow-test` (14.5 s)
is the worst: `modules/procurement/test/kontor/procurement/forward_flow_test.clj:23-31`
boots `create-test-db` (full kernel schema) **then** `partner-schema/install!`,
`sales-schema/install!`, `inv-schema/install!`, `proc-schema/install!`
on top — measured ~1.46 s/bootstrap — and every one of its 14 deftests
*also* calls `(seed-base!)` (`:119-384`). 14 × (1.46 s boot + seed).

### The bootstrap-in-loop anti-pattern

The single highest-density redundancy. All **11 `l10n-*/invoice_test.clj`**
files carry a `invoice-postings-sum-to-zero` deftest that calls
`(bootstrap)` — i.e. `create-test-db` — **inside a `doseq`** over 5–6
VAT-class cases:

- `modules/l10n-at/test/kontor/l10n_at/invoice_test.clj:327` — 6 boots
- `modules/l10n-br/.../invoice_test.clj:482` — `doseq` + boot
- `modules/l10n-mx/.../invoice_test.clj:355-389`
- `modules/l10n-us/.../invoice_test.clj:308-321`
- `modules/l10n-cn/.../invoice_test.clj:298-316`
- `modules/l10n-fr/.../invoice_test.clj:268-281`
- `modules/l10n-ca/.../invoice_test.clj:271-283`
- + `l10n-de`, `l10n-au`, `l10n-in`, `l10n-jp` siblings

≈ 11 modules × ~6 boots = **~66 redundant DB creations ≈ 60–100 s**
spent re-installing the schema to post one more invoice. Note 96
flagged the three slowest of these (~2 s each, cited as single
deftests) but did not connect them to the loop. One shared conn per
deftest (post N distinct invoices into it, assert each sums to zero
independently) gives **identical** coverage.

## 2 — Parallelization (the headline lever)

### kaocha has no parallel plugin

Verified: `kaocha-1.91.1392.jar` contains
`kaocha/plugin/{profiling,filter,randomize,hooks,debug,print_invocations,
orchestra,preloads,capture_output,version_filter,notifier,gc_profiling}`
and `plugin/alpha/{info,spec_test_check,xfail}`. **No `parallel`.**
Note 96's Lever 2 (`:plugins [kaocha.plugin/parallel-tests]`) cannot
work as written.

### In-JVM thread parallelism is unsafe anyway

The kernel keeps process-global mutable registries that several tests
mutate at test time:

- `kontor.event-bus/handlers` (`defonce` atom, `src/kontor/event_bus.clj:80`)
  — `test/kontor/event_bus_test.clj` registers/clears handlers in
  `:each` fixtures and deftests; concurrent deftests would
  cross-contaminate.
- `kontor.sales/processors` (`src/kontor/sales.clj:300`) — mutated by
  `modules/sales/test/kontor/sales_test.clj`.
- `kontor.agent-tools/registry`, `kontor.dsar/*-registry`,
  `kontor.einvoice-provider/registry` — load-time-populated, but the
  agent-tools / dsar tests touch them.

These are fine across **separate JVM processes** (each gets its own
atoms) but would race under in-process threads.

### Recommendation: process-level shard-and-merge

Each shard is its own `clojure -M:test` JVM running a disjoint subset
of namespaces; a wrapper merges exit codes. Two viable mechanisms:

1. **kaocha `--focus` / `--skip` by namespace** — kaocha still *loads*
   all test nses then focuses, so this does not reduce per-shard load
   cost (see §below).
2. **Multiple `:test` suite IDs in `tests.edn`** keyed by module
   group, each shard runs `--focus-suite :shard-N`. Cleaner; each
   shard's `:test-paths` is a real subset so load is proportional.
   (This is a `tests.edn` change — out of scope for this *assessment*
   but it is the recommended mechanism.)

**Per-shard fixed cost is significant.** A measured single-namespace
`clojure -M:test --focus ...` run took **~35 s wall** — JVM boot +
classpath realize + Clojure load of the whole `src` tree dominates.
So the sweet spot is **~4 shards on an 8-core box**, not 8+: with 4
shards each ≈ 35 s fixed + (~7 min ÷ 4 ≈ 105 s) work ≈ **~140 s**,
vs ~460 s serial → **~3.3× wall-time win**. More shards lose to the
fixed ~35 s tax. Combined with §4 (bootstrap-loop fix removing
~60–100 s of work first), 4 shards land near **~110–130 s**.

Shard split should balance by note-96 namespace weight, e.g.:
- shard A: `kontor.procurement.*` + `kontor.invoice.*` + `kontor.sales*`
  + `kontor.fx*` (the heavy nses)
- shard B: `kontor.partner*` + `kontor.period*` + `kontor.lease.*`
  + `kontor.collections.*` + `kontor.valuation*`
- shard C: all `l10n-*`
- shard D: all `payroll-*` + `bank-*` + kernel remainder

Risk: **low**. No test code changes; each shard is a clean JVM. The
only discipline cost is keeping the shard split roughly balanced as
modules are added — a one-line `tests.edn` edit, caught by CI timing.

## 3 — Fixture cost: what can and cannot be cheapened

### `d/with` reuse — mostly does not apply

Note 96 Lever 1 proposed a `:once` schema install + per-test `d/with`.
Reality: only **`money_test.clj`** is pure-query. 162 of 274 files do
`d/transact` or `!`-wrapper writes; **`d/with` returns a db *value*,
not a connection**, so it cannot back a test that calls
`(d/transact conn ...)` or a `*-tx-data` `!` wrapper. Keep Lever 1
only for the genuinely pure namespaces — a rounding error.

### Schema-store fast-clone — tried, rejected

Probed: build one schema'd in-mem DB, deep-copy the konserve
`MemoryStore` state atom into a fresh store, `d/connect`. **Fails** —
the connection caches the schema in its own `:schema`/`:rschema`
index, separate from the konserve store; a transact against the
forked conn throws `Bad entity attribute … not defined in current
schema`. A correct fast-clone needs datahike-internal surgery (copy
the conn's schema index too) — too fragile for a test helper, and
ADR-001 (single dep) discourages a datahike fork for test ergonomics.
**Do not pursue** unless datahike grows a first-class
`clone-database` / `fork` primitive (worth filing upstream).

### `:once` namespace fixture + shared conn — applies where isolation is not asserted

Many slow namespaces' deftests write **distinct** entities (unique
`:*/external-id`s) and never assert whole-DB counts. Those can share
**one conn per namespace** via `(use-fixtures :once …)`, paying the
~1 s boot once instead of N times. Guardrail: **60+ files** assert on
`trial-balance` / `account-balance` / `d/datoms`-counts and *require*
a fresh DB per test — those keep `:each`. A `kontor.test-fixtures`
helper (see §4) should expose **both** `with-shared-db` (`:once`) and
`with-fresh-db` (`:each`) so the author picks per namespace.
Conservative estimate: ~40 namespaces convert safely → each saves
(tests−1) × ~1 s. On the top-20 alone that is **~80–120 s**.

### Teardown

Add `d/delete-database` in fixture teardown (or a JVM-wide
`:once`-of-suite cleanup). Removing the ~2400-store heap residency
should pull per-`create-test-db` back toward the ~960 ms isolated
figure from the ~1.6 s observed — a **~1.3–1.5× multiplier** on
everything that still boots a DB, for near-zero risk.

## 4 — Redundancy + normalization

### Over-testing to trim (coverage-neutral)

- **`invoice-postings-sum-to-zero` × 11 l10n modules** — the
  bootstrap-in-`doseq` (§1). Fix: one conn, loop posts distinct
  invoices, assert each. Same assertions, ~60–100 s back. *This is
  the single best simplification.*
- **`forward-flow-test`** — 14 deftests each re-run a 4-companion-schema
  bootstrap **and** `(seed-base!)`. Convert to `:once` schema install
  + `:each` `seed-base!` only where a deftest needs clean ledgers.
  ~15–20 s back.
- **l10n `chart_test.clj`** (`l10n-mx` 17 `create-test-db`,
  `l10n-in` 15, `l10n-us`/`l10n-cn` ~13) — chart-of-accounts
  presence tests. A chart is static seed data; one shared conn per
  file suffices. ~10–15 modules × ~12 boots saved.

### Inconsistent fixtures — normalize via `kontor.test-fixtures`

There is **no shared test helper today** (no `test_fixtures.clj` /
`test_helpers.clj` anywhere). Every l10n test re-hand-rolls a db +
`{:commodity/symbol "EUR" …}` + journals + accounts (`fx_test.clj:31`
`bootstrap-commodities!`, `forward_flow_test.clj:37` `seed-base!`,
`runner_test.clj:81-90` `ref-eid`/`p`/`acct`/`journal` helpers — all
near-duplicates). A single `kontor.test-fixtures` namespace under
`test/` should ship:
- `fresh-db` / `shared-db-fixture` (the `:each` / `:once` pair)
- `seed-commodities!`, `seed-journals!`, `seed-coa!` — the three
  blocks every test re-types
- `ref-eid` and friends (currently copy-pasted in ≥6 files)

This does not by itself cut runtime much, but it (a) makes the
bootstrap-loop fix a one-line change per file, (b) lets a future
optimization land in one place, (c) removes ~30 copies of the same
seed block. **Low risk, high maintainability payoff.**

### Period-tax l10n tests — already right-sized

The 13-module period-tax test wave (note 102) is **well-shaped**:
`modules/l10n-de/test/kontor/l10n_de/period_tax_provider_test.clj`
has 3 deftests, only 1 touches a DB; `l10n-fr`/`l10n-at`/`l10n-au`/
`l10n-us`/`l10n-br` `period_tax_provider_test.clj` use **zero**
`create-test-db` (pure schedule-algebra assertions). No trimming
needed there — the schedule math is genuine golden-value coverage.

## 5 — Slow-suite split

Note 96 Lever 3. Genuinely heavy integration tests —
`kontor.consolidation-test` (~7.4 s), `kontor.lease.runner-test`
(~6.7 s), `stage_r_cross_stage_test`, the `payroll-*/e2e_test`
family — could move to a separate `:integration` suite ID run only
in `bb ci`. **Recommendation: defer.** Once §2 (sharding) +
§4 (loop fix) land, the default suite is already ≤ ~2m30s; the
split adds a permanent discipline tax ("which suite does my new test
go in?") for a marginal extra win. Revisit only if a contributor
needs a sub-60 s inner loop — and even then, kaocha `--focus` on the
slice under development already gives that today.

## 6 — What NOT to cut (coverage that must stay)

- **The `Ker σ` sum-to-zero gate** — per-(entity, ledger, commodity)
  balancing (`src/kontor/posting.clj:156-299`, ADR-021/031). Every
  `*-postings-sum-to-zero` deftest stays; only the *fixture* around
  it changes. This is the kernel's defining invariant.
- **Sealing invariants** — `find-silent-retracts` /
  `:posting/posted-at` middleware (`src/kontor/sealing.clj`, ADR-007).
  The draft→posted→no-silent-retract path must keep full coverage.
- **Bitemporal as-of** — `value-at` / `as-of-bitemporal` / `timeline`
  (ADR-008/048) tests; `:as-of-tx` × `:as-of-valid` matrices.
- **Per-jurisdiction golden values** — every `l10n-*` and `payroll-*`
  numeric assertion (DE §32a piecewise polynomial, US multi-state,
  IN TDS, the eSocial / CFDI / STP / mBGM numbers). These are the
  whole point of the l10n modules; they are *cheap* (pure arithmetic)
  and must not be thinned to "a representative few."
- **Tax-schedule algebra** (`kontor.tax-schedule`, ADR-099) — bracket
  continuity / monotonicity / known-value tests. Pure, fast, keep all.
- **Status-machine + period-lock** transition coverage (ADR-014/034).
- **Cross-DB saga** (`side-effect/cross`, ADR-074) and consolidation
  elimination tests — slow but irreplaceable; move to a slow suite
  *if* §5 is ever done, never delete.

The rule: **simplify fixtures, never assertions.** Every change in
this plan is fixture-shape or process-topology; the set of
`(is …)` forms exercised is invariant.

## 7 — Prioritized plan + target

| # | Change | Est. wall-time impact | Risk | Coverage argument |
|---|---|---|---|---|
| 1 | **Process-parallel sharding** — 4 `:test` suite IDs in `tests.edn`, wrapper merges exit codes; `bb test` shards, `bb ci` keeps one full run | ~460 s → ~140 s (**~3.3×**) | **Low** — no test code change; each shard a clean JVM, isolates the global registries | None — identical tests, different processes |
| 2 | **Fix bootstrap-in-`doseq`** in 11 `l10n-*/invoice_test.clj` + `chart_test.clj` family + `forward-flow-test` | −60–120 s of *work* (compounds under sharding) | **Low** — mechanical: hoist `(bootstrap)` out of the loop, post distinct invoices into one conn | Same `(is …)` forms; each case still posted + asserted independently |
| 3 | **Add `d/delete-database` teardown** to fixtures | ~1.3–1.5× on every still-booting test (removes heap residency) | **Low** | None — teardown only |
| 4 | **`kontor.test-fixtures` helper + `:once` conversion** for the ~40 namespaces not asserting isolation | −80–120 s | **Medium** — must audit each ns for whole-DB-count assertions before converting; helper ships both `:each` and `:once` | `with-fresh-db` retained for the 60+ isolation-asserting files; converted nses verified independent |
| 5 | (defer) **`:integration` slow-suite split** | inner-loop only | discipline tax | n/a |

**Staged path to the target:**

1. **Stage 1 (lowest risk, biggest win): #1 + #3.** Sharding +
   teardown. No test-code edits beyond fixture teardown. Expected:
   ~460 s → **~150–170 s**.
2. **Stage 2: #2.** Mechanical loop hoist, ~15 files. Expected:
   → **~120–140 s**.
3. **Stage 3: #4.** Introduce `kontor.test-fixtures`; convert
   namespaces ns-by-ns with a per-ns isolation audit. Expected:
   → **≤ 2m30s**, comfortably, on an 8-core box; a 4-core box lands
   ~3m30s.

**Concrete target: default `bb test` ≤ 2m30s on 8 cores** (from
~7m40s), CI runs the full unsharded suite as the correctness oracle.
If a faster inner loop is later wanted, kaocha `--focus` on the
slice under development already delivers it without #5.

## 8 — Corrections logged against note 96

- Note 96 §"Lever 2": `kaocha.plugin/parallel-tests` **does not
  exist** in kaocha 1.91. Use process-level sharding.
- Note 96 §"Lever 1": `d/with` reuse applies to **1 file**, not the
  suite — 162/274 files write and need a live conn. Recommended path
  is `:once`-shared-conn (a real connection) where isolation is not
  asserted, not `d/with`.
- Note 96 named the three slowest `invoice-postings-sum-to-zero`
  deftests but did not identify the **bootstrap-in-`doseq`** root
  cause shared by all 11 l10n invoice tests.

## How this assessment was produced

- REPL benchmarks against the live nREPL: `create-test-db` decomposed
  into `create-database` / `schema/install!` / seed bundle; `d/with`
  vs fresh-db; konserve store-copy fork (failed).
- `kaocha-1.91.1392.jar` plugin inventory via `unzip -l`.
- Static scan: 274 `*_test.clj`, 354 `create-test-db` call-sites,
  2682 `deftest`, 18 `use-fixtures :each` / 0 `:once`, the
  loop-+-`create-test-db` co-occurrence scan.
- Note 96's per-namespace profiling table reused as still-current
  (the suite has grown ~150 tests since; ranking is stable).

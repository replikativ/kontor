---
date: 2026-05-15
title: Datahike `feature/bitemporal-v1` vs stratum `feature/valid-time` — bitemporal feature parity audit
status: draft
---

## TL;DR

Datahike's bitemporal v1 graduates `:db.valid/from` + `:db.valid/to` into
system schema, plumbs them through tx-meta into secondary indices, and
adds a `d/valid-at` wrapper plus four built-in datalog rules. Stratum's
`feature/valid-time` — the dataset/SQL layer the stratum-backed datahike
adapter sits on — covers a much wider feature surface: bounded surgery
(INSERT / UPDATE / DELETE FOR PORTION OF), `FOR ALL VALID_TIME`,
`ERASE`, SELECT-side temporal grammar (AS OF / BETWEEN / FROM-TO /
ALL), nine Allen predicates, a repeatable clock, zero-width validation,
and (critically) **system-time symmetry on SCD2 writes**. Across ten
axes: **3 MATCH** (tx-meta wire format; SCD2 valid-time close-reopen;
AVET / zone-map pushdown), **5 PARTIAL** (read-side surface, bounded
DML, period validation, clock, Allen predicates), **2 MISSING in
datahike core** (`d/valid-between` / `d/valid-during` wrappers;
system-time symmetry in the stratum vt-adapter). Most gaps are either
SQL-grammar concerns that belong in pg-datahike, or trivial datalog
helpers that should land in datahike as thin wrappers over the existing
built-in rules.

## What's shared (MATCH)

- **Tx-meta wire format.** Both speak the same `:db.valid/from` /
  `:db.valid/to` shape end-to-end. A kontor consumer can build one
  tx-meta map and route it to either substrate.
    - Datahike: `meta-attrs-for-secondary` at
      `src/datahike/db/transaction.cljc:232` and `tx-meta-for-secondary`
      at `:253-260` flow meta-attrs into secondary indices.
    - Stratum: `IDataset/append!` docstring at
      `src/stratum/dataset.clj:104-117` documents the same keys; the
      datahike-side adapter's `tx-meta->vf` / `tx-meta->vt` at
      `src-secondary/datahike/index/secondary/stratum.clj:299-313`
      consumes them.

- **SCD2 close-and-reopen on the valid axis.** Both substrates close an
  open row's `_valid_to` and append a successor on update.
    - Datahike adapter: `vt-persist-transient-stratum-index` at
      `src-secondary/datahike/index/secondary/stratum.clj:888-962`
      materialises current rows, closes any row whose `eid` is in the
      pending set and whose `_valid_to = Long/MAX_VALUE` to the tx's
      vt-from, then appends merged successors.
    - Stratum: `upsert!` at `src/stratum/dataset.clj:287-428` is the
      canonical implementation; the adapter mirrors its logic one
      level up.

- **AVET / zone-map pushdown.** Both push the vt filter down to their
  physical layer.
    - Datahike: built-in rules at `src/datahike/query.cljc:636-657`
      (`valid-at`, `valid-between`, `valid-during`,
      `period-overlaps?`) expand to an AVET seek on `:db.valid/from`
      (declared `:db/index true` at
      `src/datahike/constants.cljc:172-173`) plus a `get-else` for
      `:db.valid/to`.
    - Stratum: `-search-at-vt` at
      `src-secondary/datahike/index/secondary/stratum.clj:586-617`
      appends `[:<= vt-from-col at] [:> vt-to-col at]` predicates onto
      the query's WHERE and the zone-map pruner skips chunks whose
      `[min,max]` can't intersect.

## Where datahike lags (MISSING / PARTIAL)

### 1. System-time symmetry in vt-mode (P0)

- **Stratum (`a70ce43`).** Every vt mutation closes the row's
  `_system_to` and appends a successor with a fresh `_system_from`.
  See the bitemporal branch in `upsert!` at
  `src/stratum/dataset.clj:382-411` (`system-now-from-tx-meta` +
  `replace-row-bitemporal!`) and the analogous block in `retract!` at
  `:509-540`.
- **Datahike adapter status.** The vt-mode adapter at
  `src-secondary/datahike/index/secondary/stratum.clj:888-962` manages
  only a `:valid` axis. `ds-metadata` at `:315-323` hardcodes a single
  `:valid-time` key — no `:system` sibling. Consequence: an `AS OF
  SYSTEM_TIME <past>` query cannot reconstruct the pre-correction
  state of any bitemporally corrected row — the historical row was
  edited in place.
- **Severity.** P0 for consumers that need both axes (kontor: yes —
  accountants must distinguish "the period the world thought X was
  true" from "the period the DB thought X was true").
- **Fix sketch.** Add `:system-time true` (or `:bitemporal true`) to
  the index config; mirror stratum's pattern — `ds-metadata` emits
  `{:valid {...} :system {...}}`, `vt-persist-transient-stratum-index`
  routes through stratum's bitemporal `upsert!`. The adapter becomes
  a thin wrapper, as envisioned in stratum's Phase E.

### 2. Bounded DML (P1)

- **Stratum.** `INSERT / UPDATE / DELETE FOR PORTION OF VALID_TIME
  FROM x TO y`, `FOR ALL VALID_TIME`, and `ERASE FROM` lower to
  `dataset/append!`, `bounded-update!`
  (`src/stratum/dataset.clj:1242-1310`), and `retract!` with `:valid-to`
  in tx-meta (`src/stratum/dataset.clj:432-623`). The 4-way bounded
  retract (drop / truncate-vt / shift-vf / split) is at `:469-555`.
- **Datahike.** No transactor-side analogue. The connector flows
  `:db.valid/from` / `:db.valid/to` per *transaction*, not per
  *retraction range*; `d/transact` cannot say "retract these datoms
  only over `[vf, vt)`." Only standard per-datom retract or
  `:db.purge/*` are available.
- **Fix sketch.** Add a tx-data op `[:db.valid/retract-range <ref>
  <vf> <vt>]` that the transactor decomposes into the right
  retract + reassert chain. Mirrors stratum's bounded retract at the
  datom layer. Not blocking for kontor (SQL path covers it).

### 3. SELECT-side temporal wrappers (P2)

- **Stratum SQL.** `SELECT … FOR VALID_TIME (AS OF | BETWEEN | FROM TO
  | ALL)` at `src/stratum/sql/rewrite.clj:413-655` — preprocessor
  rewrites into WHERE predicates on `_valid_from` / `_valid_to`.
- **Datahike datalog.** `d/valid-at` (AS OF), `d/as-of` (tx-time),
  `d/since`, `d/history`. Of stratum's four SELECT forms, datahike
  core only ships AS OF as a db wrapper. `BETWEEN` and `FROM TO` are
  *available* via the built-in `valid-between` / `valid-during` rules
  but have no `d/`-prefixed wrapper. `ALL` is implicit (plain
  `d/history` carries every vt-window), but the lack of an explicit
  wrapper makes cross-axis composition awkward to spell.
- **Fix sketch.** Add `d/valid-between` / `d/valid-during` /
  `d/valid-all` wrappers mirroring `d/valid-at` at
  `src/datahike/api/impl.cljc:186-216`.

### 4. Period validation (P1)

- **Stratum.** `validate-period!` at
  `src/stratum/dataset.clj:1030-1043` rejects zero-width and reverse
  windows at every entry point (`append!`, `upsert!`, `retract!` with
  `:valid-to`). Critical because the bounded-surgery overlap test
  silently degenerates to a no-op on `vt <= vf`.
- **Datahike.** No validation. A tx with `:db.valid/from #inst
  "2024-07-01" :db.valid/to #inst "2024-01-01"` is accepted at the
  transactor and produces a tx-entity that no `valid-at` query can
  ever match (rule expansion is `vf <= at < vt`, unsatisfiable).
  Silent data-quality bug.
- **Fix sketch.** One-line check in
  `src/datahike/db/transaction.cljc` near line 686 where tx-meta is
  merged.

### 5. Repeatable clock (P2)

- **Stratum.** `*clock-time-millis*` dynamic var at
  `src/stratum/dataset.clj:968-977` pins the default for
  `:valid-from` / `:system-from`; SQL session var
  `SET datahike.clock_time = …` at `src/stratum/server.clj:545-573`
  binds it per statement. Used for replay, regulatory simulation,
  deterministic tests.
- **Datahike.** Tx-meta supports `:db/txInstant` override at
  `src/datahike/db/transaction.cljc:960` (caller's tx-meta shadows
  the default). That covers tx-time, but does not extend to
  valid-time — there's no defaulting story for `:db.valid/from`.
  In practice callers always supply it explicitly (correct kontor
  practice), so the gap is purely ergonomic.
- **Fix sketch.** Plumb stratum's `*clock-time-millis*` through the
  adapter's `tx-meta->vf` fallback at
  `src-secondary/datahike/index/secondary/stratum.clj:299-305`.

### 6. Allen interval predicates (P2)

- **Stratum.** Nine SQL functions at `src/stratum/sql.clj:749-785`:
  `OVERLAPS`, `EQUALS_PERIOD`, `CONTAINS_PERIOD`, `PRECEDES` /
  `STRICTLY_PRECEDES` / `IMMEDIATELY_PRECEDES`, `SUCCEEDS` /
  `STRICTLY_SUCCEEDS` / `IMMEDIATELY_SUCCEEDS`, `MEETS`. Generic over
  any int64 column pair.
- **Datahike.** One built-in rule (`period-overlaps?` at
  `src/datahike/query.cljc:656-657`, alias for `valid-between`). The
  other eight Allen relations are absent.
- **Fix sketch.** Extend `built-in-rules` at
  `src/datahike/query.cljc:628-657` with eight more rule heads, each
  a 4-arg `(rule ?af ?at ?bf ?bt)` taking two interval endpoints —
  generic, not vt-axis-specific (mirroring stratum's design).

### 7. ERASE / temporal literals / open-ended FROM (non-gaps)

- **ERASE.** Stratum exposes `ERASE FROM …` at
  `src/stratum/sql/rewrite.clj:141-151`; the datahike equivalent is
  `:db.purge/*` (`doc/time_variance.md:274-279`). Same semantic;
  surface lives in pg-datahike, not datahike core.
- **Temporal literals + open-ended FROM.** `parse-temporal-literal`
  at `src/stratum/sql/rewrite.clj:250-310` accepts
  `'YYYY-MM-DD'`, `DATE`/`TIMESTAMP` prefixes, numeric micros,
  `CURRENT_TIMESTAMP`, `END_OF_TIME` / `MAX_VALUE`. Datahike tx-meta
  uses typed `java.util.Date`; absent `:db.valid/to` means
  open-ended per `tx-meta->vt` at the adapter
  (`src-secondary/.../stratum.clj:307-313`). No core gap; SQL surface
  is pg-datahike's concern.

### 8. Test coverage (P2)

- **Stratum** tests cover each write shape (append, upsert, retract,
  bounded-retract, auto-split), each SQL read form (AS OF, BETWEEN,
  FROM TO, ALL), every Allen predicate, system-time symmetry,
  zero-width validation, open-ended FOR PORTION OF, and repeatable
  clock.
- **Datahike**:
  - `test/datahike/test/valid_at_test.clj` — 4 deftests covering
    valid-at + history-additions discipline, nil-clears-marker,
    composition with as-of, non-vt-tx pass-through.
  - `test/datahike/test/stratum_vt_test.clj` — 9 deftests covering
    adapter shape, SCD2 close-on-upsert, `-search-at-vt`, plain-mode
    parity, release/reconnect, branch.
  - No tests for `valid-between` / `valid-during` /
    `period-overlaps?` rules (they exist but no test invokes them).
  - No tests for invalid `vf >= vt` rejection (because no rejection
    — see gap 4).
  - No tests for bounded retract / surgery (feature absent — see
    gap 2).

## Architectural asymmetries (by design)

- **Datom vs row layout.** Datahike stores tx-meta on the tx-entity
  (one `:db.valid/from` per tx; every datom inherits via
  `(datom-tx d)` → tx-entity lookup at
  `src/datahike/api/impl.cljc:157-184`). Stratum stores `_valid_from`
  / `_valid_to` per row. Both are correct; reconciling them is the
  adapter's job (`tx-meta->vf` reads tx-meta, writes row columns).

- **One axis explicit, one implicit.** Datahike's vt is built on top
  of the existing tx-time + history machinery — the second axis comes
  for free at the datalog layer. Stratum is event-sourced into a
  single row store, so both axes are explicit. The real gap is the
  adapter not propagating stratum's system-time onto SCD2 surgery
  (gap 1).

- **SQL grammar lives elsewhere.** `FOR PORTION OF`, `ERASE`, Allen
  predicates in SQL — all stratum-SQL features and (longer-term)
  pg-datahike features. Datahike core needs *datalog* analogues
  (gap 6 is real; gap 7 isn't).

- **Per-tx vt vs per-row vt.** Stratum's bounded INSERT / UPDATE
  applies one `[vf, vt)` to a row. Datahike's tx-meta applies one
  `[vf, vt)` to an entire transaction. Splitting one tx into multiple
  vt-bounded slices means multiple tx calls — a UX gap, not a
  correctness gap.

## Recommendations

Ranked by ROI for the combined kontor + datahike + stratum + pg-datahike
story.

1. **Wire stratum's bitemporal mode through the adapter (Phase E).**
   Add `:system-time true` / `:bitemporal true` to the index config;
   route through stratum's `upsert!` / `retract!` directly. Closes
   gap 1 (system-time symmetry), shrinks
   `vt-persist-transient-stratum-index` by ~80 LOC into a thin call
   into stratum, and gives a `d/system-at` story for free (Phase F).
   File: `src-secondary/datahike/index/secondary/stratum.clj`.
   Already on the open-tasks list as "Phase E / F / G."

2. **Validate `vf < vt` at the transactor.** One-line check in
   `src/datahike/db/transaction.cljc` near line 686 where tx-meta is
   merged. Closes gap 4; prevents silent data-quality bugs.

3. **Add `d/valid-between` / `d/valid-during` / `d/valid-all`
   wrappers.** Mirrors `d/valid-at` at
   `src/datahike/api/impl.cljc:186-216`. Each is a 5-line wrapper
   over the existing built-in rules plus a meta-key for vt-aware
   index routing. Closes gap 3.

4. **Add the other 8 Allen predicates as built-in rules.** Extend
   `src/datahike/query.cljc:628-657`. Generic over int64-typed
   intervals; mirrors stratum's design. Closes gap 6.

5. **Plumb `*clock-time-millis*` through the adapter.** Already
   available stratum-side; just needs to be bound when the adapter
   defaults a vt-from. Closes gap 5.

6. **Bounded DML in datahike core (`[:db.valid/retract-range ...]`).**
   Deferred. The kontor consumer story routes through SQL +
   pg-datahike, which routes to stratum primitives; pure-datalog
   bounded DML is nice-to-have but not blocking. Closes gap 2 if
   pursued.

7. **Document the vt schema + tx-meta + d/valid-at story in
   `doc/time_variance.md`.** Currently the doc only covers tx-time.
   A one-page "Valid-Time" section (schema attrs, tx-meta shape,
   four built-in rules, `d/valid-at` wrapper) would make the feature
   discoverable.

Out of scope for datahike core (belong elsewhere):

- `FOR PORTION OF` / `FOR ALL` / `ERASE` SQL grammar — pg-datahike.
- `SET datahike.clock_time` SQL session var — pg-datahike or stratum
  server.
- Temporal literals (`'YYYY-MM-DD'`, `END_OF_TIME`, etc.) — SQL
  layer.

Bottom line: datahike's bitemporal v1 is the right *shape* (tx-meta +
built-in rules + adapter pushdown) but is one cycle behind stratum's
`feature/valid-time` on (a) system-time symmetry, (b) safety
validation, and (c) the auxiliary datalog wrappers that round out the
read surface. Items 1–5 above can land as one combined datahike PR and
bring the two substrates into alignment for the joint story.

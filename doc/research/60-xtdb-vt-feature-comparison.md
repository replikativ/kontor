---
date: 2026-05-15
agent: research
title: XTDB v1/v2 valid-time feature comparison vs stratum/datahike
status: draft
---

# 59 — XTDB v1/v2 valid-time feature comparison vs stratum/datahike

Read-only survey of XTDB v1 and v2 valid-time (vt) features, scored against the
in-flight stratum + datahike + pg-datahike bitemporal bridge (PRs
replikativ/stratum#26, datahike#828, pg-datahike#7). The goal is to surface the
must-haves we still owe before we can credibly tell a kontor customer "we are a
bitemporal store."

Sources cited inline as `file:line`. XTDB v1 = `master` at
`~/Development/xtdb`; XTDB v2 = `~/Development/xtdb2` (the Apache-2.0 rewrite,
SQL+XTQL+pgwire).

## 1. Summary table

| Feature                                              | XTDB v1                     | XTDB v2                                              | stratum/datahike                                       | Status     |
|------------------------------------------------------|-----------------------------|------------------------------------------------------|--------------------------------------------------------|------------|
| Backdated put (past `_valid_from`)                   | yes (`put` w/ vt arg)       | yes (`INSERT` w/ `_valid_from`, `[:put-docs … :valid-from]`) | yes (writer supplies `:db.valid/from` tx-meta)        | MATCH      |
| Future-dated put                                     | yes                         | yes                                                  | yes (same path)                                        | MATCH      |
| Bitemporal correction (past tx-time + past vt)       | tx-time always = now; correction via *new* tx with backdated vt | same | same (no rewinding tx-time)                            | MATCH      |
| Default upsert range when no vt given                | now → end-of-time           | now → end-of-time (deviation from SQL:2011, by design) | now → end-of-time (matches XTDB choice)                | MATCH      |
| SCD2 close-on-update                                 | implicit (temporal index)   | implicit (temporal index)                            | yes, opt-in stratum adapter w/ `_valid_from`/`_valid_to` rows | MATCH      |
| `FOR PORTION OF VALID_TIME FROM…TO` (SQL:2011 DML)   | no (no native SQL DML)      | yes (UPDATE/DELETE/PATCH, `Sql.g4:828`)              | **no**                                                 | GAP-MUST   |
| `FOR ALL VALID_TIME` DML scope                       | no                          | yes (`Sql.g4:829`)                                   | **no** (no scope concept)                              | GAP-NICE   |
| Sequenced vs non-sequenced semantics                 | partial (per-op)            | partial (per-op, no separate keyword)                | n/a                                                    | EXOTIC     |
| `PATCH … FOR PORTION OF VALID_TIME`                  | no                          | yes                                                  | no                                                     | GAP-NICE   |
| `ERASE FROM <t> WHERE …` (physical purge)            | `evict`                     | yes (`ERASE`, txs.adoc:94)                           | yes (`:db/purge`, ADR-007)                             | MATCH      |
| Physical vs logical delete                           | both (delete vs evict)      | both (delete vs erase)                               | both (retract vs purge)                                | MATCH      |
| VT-aware uniqueness / `WITHOUT OVERLAPS` constraint  | no                          | implicit, built-in (key-concepts.adoc:71)            | **no** (overlap detection up to user)                  | GAP-MUST   |
| `FOR VALID_TIME AS OF <ts>`                          | partial (Calcite VALIDTIME setting, calcite.clj:57) | yes (queries.adoc:154 `<temporal filter>`)           | `d/valid-at db inst` (api commit f4c0c2e6)            | MATCH      |
| `FOR VALID_TIME BETWEEN a AND b`                     | no (history API only)       | yes                                                  | `(valid-between ?tx ?from ?to)` rule (commit 7cb5abac) | MATCH      |
| `FOR VALID_TIME FROM … TO …` (half-open window)      | no                          | yes                                                  | yes (`valid-between` half-open)                        | MATCH      |
| `FOR ALL VALID_TIME` / `FOR VALID_TIME ALL` in reads | partial (entity-history)    | yes                                                  | no first-class form (drop the marker + use rules)      | GAP-NICE   |
| Full bitemporal as-at (tx-time T + vt V)             | yes (`db` w/ vt + tx)       | yes (`SETTING SNAPSHOT_TIME …, DEFAULT VALID_TIME …`, queries.adoc:27) | yes (`d/as-of` + `d/valid-at` compose, ADR-008)        | MATCH      |
| Cross-temporal joins (consistent vt across joins)    | implicit on the db value    | implicit per-`from` clause, per-relation overrideable | yes (FilteredDB applies once; per-relation override **GAP-NICE**) | partial    |
| Time-slicing primitive (multi-state in one query)    | no                          | `RANGE_BINS` (temporal.adoc:213) + `FOR ALL`         | **no** (must run N queries)                            | GAP-NICE   |
| Allen interval predicates (`OVERLAPS`/`CONTAINS`/…)  | no                          | yes, with `STRICTLY`/`IMMEDIATELY` variants (temporal.adoc:80-132) | partial: `period-overlaps?` rule only                  | GAP-NICE   |
| `PERIOD(from,to)` constructor + `LOWER/UPPER`        | no                          | yes (temporal.adoc:72-145)                           | no (datalog uses raw inst pairs)                       | GAP-NICE   |
| Per-table vt opt-in                                  | n/a (universal)             | n/a (universal)                                      | yes (`:db.secondary/config {:valid-time true}`)        | EXOTIC     |
| Implicit vs explicit vt columns                      | implicit (`::xt/valid-time`) | implicit (`_valid_from`/`_valid_to`)                 | mixed: `:db.valid/from` tx-meta is *explicit* on write, *queryable as datoms*; SCD2 columns implicit on read | partial |
| Schema evolution under vt                            | schemaless                  | schemaless                                           | datahike schema is itself vt-historical (every `:db/ident` is a normal datom — ADR-002 still holds) | MATCH |
| VT-aware temporal indexes                            | yes (`tx-time`/`vt` z-curve, morton.clj) | yes (Arrow trie + temporal column metadata, `trie.clj`) | yes (stratum zone-map prune on `_valid_from`/`_valid_to`; AVET pushdown on `:db.valid/from`/`:db.valid/to`) | MATCH |
| Range push-down for vt scans                         | yes                         | yes                                                  | yes (rschema-aware AVET pushdown, commit 7cb5abac)     | MATCH      |
| `SETTING CURRENT_TIME = …` / `CLOCK_TIME`            | no                          | yes (queries.adoc:28, temporal.adoc:52)              | no (`now` is whatever clock the JVM has)               | GAP-NICE   |
| `SET datahike.valid_at = …` session var              | no (Calcite VALIDTIME only) | yes (SETTING DEFAULT VALID_TIME … TO/AS OF)          | yes (pg-datahike#7, `valid_at`/`valid_from`/`valid_to`) | MATCH      |
| XTQL-style EDN temporal filter                       | n/a                         | yes (`:for-valid-time (in T1 T2)`, queries.adoc:182) | no equivalent EDN form; only `(d/valid-at db t)`       | GAP-NICE   |
| `ASSERT NOT EXISTS …` precondition                   | `match` op                  | yes (`ASSERT` in txs.adoc:108)                       | datahike has cas + tx-fns                              | MATCH      |

## 2. GAP-MUST paragraphs

### 2.1 `FOR PORTION OF VALID_TIME FROM … TO …` for UPDATE/DELETE

This is the SQL:2011 sequenced-update primitive. In XTDB v2 it's the *only* way
to update a record across a specific past slice (`Sql.g4:828`,
`docs/.../sql/txs.adoc:40`). Without it, the only way to "amend the price
of order X between 2025-03-01 and 2025-03-15" in datahike today is to compose
two writes (close the open vt-row early, append a new one) at application
level — which is exactly the SCD2 ceremony the stratum adapter was supposed to
hide. Implementing this as a tx-fn on top of the existing SCD2 close-on-update
gets us 80 % of the way; doing it in SQL via pg-datahike is the natural surface.

### 2.2 VT-aware uniqueness / overlap detection

XTDB v2 maintains a *built-in* "no two versions of the same `_id` ever overlap"
invariant — every put/upsert silently rewrites neighbours so periods stay
disjoint (`key-concepts.adoc:71`, indexer.clj:106-180). Stratum's SCD2 mode
*also* closes the previous open row on update, but it does not detect or reject
a write that lands *inside* an already-closed row (i.e. a backdated correction
that overlaps a historical period). For accounting that's a genuine
correctness gap — a backdated invoice amendment can produce two simultaneously
"valid" rows for the same `:invoice/id`. Either reject (strict
WITHOUT-OVERLAPS) or auto-split (XTDB-style); pick one and document it as an
ADR.

## 3. GAP-NICE paragraphs

### 3.1 Allen interval predicates as first-class rules

XTDB v2 ships `OVERLAPS`, `CONTAINS`, `PRECEDES`, `SUCCEEDS`, `LEADS`, `LAGS`,
`EQUALS`, each with `STRICTLY` and `IMMEDIATELY` variants
(`stdlib/temporal.adoc:80-132`). Datahike has just one — `period-overlaps?`,
which is an alias for `valid-between`. The other six are mechanically derivable
from `:db.valid/from`/`:db.valid/to` and would compose cleanly with the existing
auto-injected rule machinery. Cheap to add; useful for any "subscription
renewals strictly succeeding a policy lapse" query that downstream consumers
will eventually want.

### 3.2 `PERIOD` value type + `LOWER`/`UPPER`/`*` intersection

A `Period` value (the closed-open pair) as a first-class queryable thing is
both ergonomic (`PERIOD(from,to)`) and useful for schema (a single attribute
of period type, not two attributes). Datahike currently models periods as
*pairs of datoms on tx-meta*. A `:db.type/period` value type would unify the
on-disk SCD2 representation with the query-language surface. Defer until a
real consumer asks.

### 3.3 Time-slicing via `RANGE_BINS`

XTDB v2 has `RANGE_BINS(stride, period [, origin])` returning weighted bin
arrays (`stdlib/temporal.adoc:213`), letting you, in one query, materialise the
state of an entity at every weekly tick across a year. Datahike consumers
currently do this with N round-trips. A `(d/range-bins db eid from to stride)`
helper that wraps the existing point-in-time machinery is one small commit and
significantly improves the "show me the chart" UX for beleg/simmis.

### 3.4 Per-relation `:for-valid-time` filter in datalog `:where`

Today `d/valid-at db t` is a *db-level* marker — every relation in the query
sees the same vt. XTDB v2 binds vt *per `from` clause*
(`xtql/queries.adoc:137`), which is what lets a query say "show me the price of
this contract at signing-time, joined to the customer's address today".
Implementable as a per-pattern annotation: `[?e :foo ?v {:valid-at ?t}]`
desugars to a join through tx-meta. This is the user-story scenario that
breaks first when an audit needs "as known on tx-time T about vt-day V for
entity A, joined to vt-today snapshot of entity B". Worth doing for credible
audit support.

### 3.5 `SETTING CURRENT_TIME` / `CLOCK_TIME` for repeatable queries

A query's notion of "now" can be pinned (`temporal.adoc:52-56`). This matters
in tests (so a query at midnight doesn't flicker) and in regulator-replayable
reports. The pg-datahike layer already has the session-var infrastructure
(`datahike.valid_at`); adding `datahike.clock_time` is a thin wrapper.

## 4. Prioritized roadmap (top picks)

1. **VT overlap detection on backdated writes** (GAP-MUST). Choose between
   strict reject and auto-split, then implement in the stratum adapter and
   surface a tx-fn (`d/transact!` precondition) when not using stratum.
2. **`FOR PORTION OF VALID_TIME FROM … TO …`** (GAP-MUST). Tx-fn first
   (`d/update-during!`/`d/delete-during!`), then pg-datahike SQL grammar so
   PSQL-shaped consumers can use it.
3. **`FOR ALL VALID_TIME` read scope as a marker** (GAP-NICE/MUST). Right now
   you can simulate by listing every tx with valid-time tx-meta, but the
   ergonomic loss matters. A `d/all-valid-time` db-level marker that disables
   the half-open membership filter is one commit.
4. **Allen interval rules** (`valid-contains?`, `valid-precedes?`,
   `valid-succeeds?`, etc.) auto-injected alongside the existing four
   (`valid-at`, `valid-between`, `valid-during`, `period-overlaps?`).
5. **Per-relation `{:valid-at t}` annotation in `:where`** (GAP-NICE). Required
   for cross-temporal joins; otherwise a real audit query can't ask "as of T,
   for vt V, joined to vt-now for B".
6. **`d/range-bins` time-slicing helper** (GAP-NICE). Cheap; ships a chart-able
   API surface.
7. **`SETTING CURRENT_TIME` session var in pg-datahike** (GAP-NICE). Parallels
   `valid_at` infrastructure.
8. **`PATCH … FOR PORTION OF VALID_TIME`** (GAP-NICE). Upsert into a window.

## 5. Things we choose NOT to match

- **Sequenced vs non-sequenced as separate operators.** SQL:2011 distinguishes;
  XTDB v2 implicitly picks per-op (PORTION-OF = sequenced, ALL = scope-only,
  no explicit "non-sequenced UPDATE" keyword). The bookkeeping cost of a
  separate operator keyword set is not worth the additional clarity for our
  consumers. Document the chosen semantics; don't add a keyword.
- **Schemaless universal bitemporality.** XTDB makes every row bitemporal.
  We've already locked the opposite choice (ADR-002: kontor cohabits with
  beleg, schemas are explicit; valid-time is opt-in via
  `:db.secondary/config`). Customers who want universal bitemporality should
  use XTDB.
- **A second query language (XTQL).** Datahike has datalog, pg-datahike has
  SQL. The XTQL EDN temporal-filter shape (`:for-valid-time (in T1 T2)`) is
  aesthetically nice, but adding it would mean a third grammar to maintain.
  Stick to extending datalog (rules + per-pattern annotations) and SQL (session
  vars + DML clauses).
- **`PERIOD` as a built-in value type.** A real consumer needs to ask first.
  Today, pairs of datoms on tx-meta are sufficient and don't burn a schema
  slot.
- **Z-curve / morton bitemporal indexes** (XTDB v1, `core/src/xtdb/morton.clj`).
  Our SCD2 + AVET-pushdown approach is good enough for the kontor workload
  (sub-million tx/year per tenant); a dedicated 2-D index is a year of work
  for marginal gain.

## References

- XTDB v2 SQL grammar — `~/Development/xtdb2/core/src/main/antlr/xtdb/antlr/Sql.g4:65,126,578-584,828-839`
- XTDB v2 SQL txs reference — `~/Development/xtdb2/docs/src/content/docs/reference/main/sql/txs.adoc`
- XTDB v2 SQL queries reference — `~/Development/xtdb2/docs/src/content/docs/reference/main/sql/queries.adoc:20-30,154-163`
- XTDB v2 XTQL queries — `~/Development/xtdb2/docs/src/content/docs/reference/main/xtql/queries.adoc:120-212`
- XTDB v2 XTQL txs — `~/Development/xtdb2/docs/src/content/docs/reference/main/xtql/txs.adoc`
- XTDB v2 stdlib temporal — `~/Development/xtdb2/docs/src/content/docs/reference/main/stdlib/temporal.adoc:50-243`
- XTDB v2 key concepts — `~/Development/xtdb2/docs/src/content/docs/concepts/key-concepts.adoc:47-78`
- XTDB v2 indexer — `~/Development/xtdb2/core/src/main/clojure/xtdb/indexer.clj:106-180`
- XTDB v1 concepts — `~/Development/xtdb/docs/concepts/modules/ROOT/pages/bitemporality.adoc`
- XTDB v1 datalog txs — `~/Development/xtdb/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc:452-468`
- XTDB v1 datalog queries — `~/Development/xtdb/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc:713-748,821-867`
- XTDB v1 SQL (Calcite) — `~/Development/xtdb/modules/sql/src/xtdb/calcite.clj:57,403`
- XTDB v1 entity-history API — `~/Development/xtdb/core/src/xtdb/api.clj:227-262`
- datahike commits — `7cb5abac` (built-in rules), `f4c0c2e6` (`d/valid-at`),
  `9b4bf00d` (SCD2 secondary index), `ee33fd33` (FilteredDB planner lift)
- stratum SCD2 — `~/Development/stratum/src/stratum/dataset.clj`
- pg-datahike session vars — `~/Development/pg-datahike/src/datahike/pg/server.clj`
- kontor ADR-008 (bitemporal) — `~/Development/kontor/doc/decisions.md`
- prior research — `doc/research/55-bitemporal-cross-db-survey.md`,
  `doc/research/57-stratum-valid-time-plan.md`,
  `doc/research/58-stratum-vt-followup.md`

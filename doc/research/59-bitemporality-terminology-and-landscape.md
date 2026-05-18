---
date: 2026-05-15
agent: general-purpose
status: research-note
topic: History of bitemporality, 2026 industry survey of temporal databases, and a terminology recommendation for kontor + stratum + datahike + pg-datahike user-facing docs
related: [doc/research/05-xtdb-accounting-patterns.md, doc/research/08-bitemporality-evidence.md, doc/research/55-bitemporal-cross-db-survey.md, doc/research/56-bitemporal-repl-experiments.md, doc/research/57-stratum-valid-time-plan.md, ADR-008]
---

# 59 — Bitemporality: terminology and 2026 landscape

We are about to ship a bitemporal surface across `stratum`, `datahike`, and
`pg-datahike`, and the documentation will reach far more people than the schema.
Before locking the vocabulary in writing, this note steps back to ask two
questions:

1. Where did "valid-time" and "transaction-time" come from, and what does the
   academic / standards lineage still constrain?
2. What does the rest of the industry call these things in 2026, and is
   "bitemporal" still the right user-facing word — or has the market converged
   on something clearer?

The note is read-only and finishes with a concrete recommendation.

## 1. History (~250 words)

**Snodgrass and Ahn (1985–1992).** The vocabulary of "valid time" and
"transaction time" originates in Richard Snodgrass's group at Arizona; his
doctoral student Ilsoo Ahn coined the distinction in the mid-80s. Snodgrass
brought it to ISO/IEC in 1992 as an SQL-92 extension proposal, which crystallised
as **TSQL2** in 1993–1994, the first attempt at a standard temporal SQL
([Wikipedia: Temporal database](https://en.wikipedia.org/wiki/Temporal_database),
[Snodgrass papers](https://www2.cs.arizona.edu/~rts/pubs/EDC.pdf)).

**Consensus glossary (1998).** Jensen, Dyreson, Böhlen, Snodgrass and ~20 others
hammered out *The Consensus Glossary of Temporal Database Concepts* (LNCS 1399),
the canonical reference still cited in 2026 papers
([Springer chapter](https://link.springer.com/chapter/10.1007/BFb0053710)).

**TSQL2 → SQL/Temporal (2001) → SQL:2011.** TSQL2 itself was never ratified, but
its concepts were absorbed first into the (also-abandoned) SQL/Temporal
substandard, then finally into **SQL:2011** as two distinct features:
**application-time period tables** (user-managed validity) and
**system-versioned tables** (DB-managed audit). Combining both yields a
*bitemporal table* ([SQL:2011 Wikipedia](https://en.wikipedia.org/wiki/SQL:2011),
[Illuminated Computing survey](https://illuminatedcomputing.com/posts/2019/08/sql2011-survey/)).
SQL:2011 deliberately did **not** standardise on the Snodgrass names; it picked
neutral grammar (`PERIOD FOR SYSTEM_TIME`, `PERIOD FOR <user-name>`).

**Allen (1983).** Orthogonal but adjacent: James F. Allen's interval algebra
gave the 13 binary relations (`BEFORE`, `MEETS`, `OVERLAPS`, …) that SQL:2011's
temporal predicates (`CONTAINS`, `OVERLAPS`, `PRECEDES`, `SUCCEEDS`, …) directly
encode.

**Datomic (2012).** Hickey rejected user-managed valid-time as conflating two
concerns; Datomic exposed only **transaction time** (`:db/txInstant`), and told
users to model valid-time *in their schema* as ordinary attributes.

**Crux / XTDB (2019, renamed 2021).** JUXT re-introduced first-class
user-controlled bitemporality to the Clojure/Datalog world, deliberately using
the Snodgrass names — *valid-time* and *transaction-time* in v1, retitled to
*valid-time* and *system-time* in v2 to align with SQL:2011 grammar
([JUXT: Introducing Crux](https://www.juxt.pro/blog/introducing-crux/),
[XTDB v2 launch](https://xtdb.com/blog/launching-xtdb-v2)).

## 2. Industry table

| System | User-managed axis | DB-managed axis | Primary surface |
|---|---|---|---|
| Datomic | (in schema) | `tx-time` | `db.asOf(t)`, `db.since(t)`, `db.history()` |
| XTDB v1 (Crux) | `valid-time` | `transaction-time` | `(xt/db node {::xt/valid-time …, ::xt/tx …})` |
| XTDB v2 | `valid_time` | `system_time` | SQL: `FOR VALID_TIME AS OF …`, `FOR SYSTEM_TIME AS OF …` |
| Datahike (today) | (in schema) | `tx-time` / `:db/txInstant` | `(d/as-of conn t)`, `(d/since conn t)`, `(d/history conn)` |
| Datahike (stratum, planned) | `valid-time` | `tx-time` | `:as-of-valid`, `:as-of-tx`; mirrors XTDB v2 grammar in pg-datahike |
| SQL:2011 | `PERIOD FOR <name>` (app-time) | `PERIOD FOR SYSTEM_TIME` | `FOR PORTION OF`, `FOR SYSTEM_TIME AS OF` |
| MariaDB | `PERIOD FOR <name>` | `PERIOD FOR SYSTEM_TIME` | `FOR SYSTEM_TIME AS OF …` + bitemporal combo |
| MS SQL Server | (in schema) | `PERIOD FOR SYSTEM_TIME` | `FOR SYSTEM_TIME AS OF/BETWEEN/CONTAINED IN/ALL` |
| Oracle | "valid time" (Temporal Validity) | "transaction time" (Flashback) | `PERIOD FOR …`, `AS OF SCN/TIMESTAMP`, `FLASHBACK` |
| DB2 | `BUSINESS_TIME` | `SYSTEM_TIME` | `FOR BUSINESS_TIME AS OF …`, `FOR SYSTEM_TIME AS OF …` |
| PostgreSQL | extension-only (no native) | `temporal_tables` ext + range types | `BETWEEN SYMMETRIC`, custom triggers |
| Snowflake | (in schema) | "Time Travel" retention window | `AT (TIMESTAMP => …)`, `BEFORE (STATEMENT => …)` |
| ClickHouse | (in schema) | implicit via `*MergeTree` versioning | `FINAL`, version columns; no AS-OF predicate |
| ImmuDB | (none) | append-only ledger | history API + cryptographic proof |
| TerminusDB | (none — branches) | commit graph | `WOQL` over a commit ref; git-style `branch/merge` |
| Dolt | (none — branches) | commit graph | `AS OF '<commit-or-time>'` |
| TigerBeetle | `user_data_64` (DIY) | nanosecond `timestamp` | strictly monotonic system clock |
| Materialize | event-time | processing-time | `FOR SYSTEM_TIME AS OF PROCTIME()` style |
| Apache Flink | event-time + watermarks | processing-time | event-time windows |

## 3. Industry paragraphs

**Datomic.** Single axis: transaction time (`:db/txInstant` on every tx).
`db.asOf(t)` rewinds the *database* to point-in-time; valid-time must be
modelled by the application as ordinary attributes (`:contract/effective-from`,
`/:contract/effective-to`). The community workaround pattern is documented by
Val Waeselynck and others — you end up reinventing half of SQL:2011 in user
space, which is exactly the gap stratum closes
([Val on Programming: "this is not the history you're looking for"](https://vvvvalvalval.github.io/posts/2017-07-08-Datomic-this-is-not-the-history-youre-looking-for.html)).

**XTDB v1 (Crux).** First mainstream Clojure DB to ship user-managed
valid-time. Bitemporality is **per-document**: every `put` carries a `valid-time`,
reads pin a snapshot to a `(valid-time, transaction-time)` pair, and there is no
in-query period predicate language — you replay `entity-history` and post-filter
([XTDB v1 docs](https://v1-docs.xtdb.com/concepts/bitemporality/)).

**XTDB v2.** Structural rewrite, columnar (Arrow), Postgres-wire compatible.
All tables are SQL:2011 bitemporal tables; system maintains four hidden columns
(`_valid_from`, `_valid_to`, `_system_from`, `_system_to`) and users query with
`FOR VALID_TIME AS OF` / `FOR SYSTEM_TIME AS OF` / `… BETWEEN …` / `… ALL` on
either axis. v2 also renamed *transaction-time* to *system-time* explicitly to
match SQL:2011 ([XTDB v2 launch post](https://xtdb.com/blog/launching-xtdb-v2)).

**MariaDB.** Full SQL:2011 implementation. `PERIOD FOR SYSTEM_TIME` plus
`WITH SYSTEM VERSIONING` gives audit; a separately-named `PERIOD FOR <name>`
gives user-managed validity; combining both makes a bitemporal table. Closest
public reference implementation of the standard grammar
([MariaDB Bitemporal Tables](https://mariadb.com/docs/server/reference/sql-structure/temporal-tables/bitemporal-tables)).

**PostgreSQL.** No native temporal tables in 2026. The `temporal_tables`
extension (Arkhipov, plus a PL/pgSQL fork from NearForm) approximates
system-versioning via triggers; user-managed periods are typically modelled
with `tstzrange` plus `btree_gist` exclusion constraints. The community-tracked
`SQL2011Temporal` proposal exists but has not landed in core
([PG wiki](https://wiki.postgresql.org/wiki/Temporal_Extensions),
[temporal_tables](https://pgxn.org/dist/temporal_tables/)).
This is exactly the gap pg-datahike fills by inheriting datahike's native
versioning and exposing SQL:2011-style temporal grammar.

**Microsoft SQL Server.** Ships system-versioned temporal tables only —
`PERIOD FOR SYSTEM_TIME (ValidFrom, ValidTo)` + `WITH (SYSTEM_VERSIONING = ON)`
materialises a history table automatically. User-managed validity is not a
built-in; people model it themselves. Queries use `FOR SYSTEM_TIME AS OF /
FROM…TO / BETWEEN / CONTAINED IN / ALL`
([MS Learn: Temporal Tables](https://learn.microsoft.com/en-us/sql/relational-databases/tables/temporal-tables)).
Notably Microsoft documentation uses *system-time* and (somewhat confusingly)
names the columns `ValidFrom`/`ValidTo` — i.e., the column-name semantics drift
away from the SQL:2011 abstraction.

**Oracle.** Has both axes but with different product names:
*Temporal Validity* (12c+, user-managed, `PERIOD FOR`) and *Flashback*
(system-managed, `AS OF SCN`/`TIMESTAMP`, plus Flashback Data Archive for
long-term retention). Oracle's own docs use "valid time" and "transaction
time" in prose ([Oracle Temporal Validity](https://docs.oracle.com/database/121/VLDBG/GUID-AF78C832-516A-4686-9DDF-CE12597F7723.htm)).

**DB2.** Uses the SQL:2011 grammar but renames the user-managed axis to
`BUSINESS_TIME` (instead of leaving it user-named). Queries are
`FOR BUSINESS_TIME AS OF …` / `FOR SYSTEM_TIME AS OF …`. IBM's "A Matter of
Time" white paper is one of the clearest published treatments of bitemporality
in any RDBMS ([IBM A Matter of Time](https://public.dhe.ibm.com/software/data/sw-library/db2/papers/A_Matter_of_Time_-_DB2_zOS_Temporal_Tables_-_White_Paper_v1.4.1.pdf)).

**Snowflake.** Calls its axis *Time Travel*; covers transaction time only via
a retention window (`DATA_RETENTION_TIME_IN_DAYS`, 0–90 days standard, up to
90+ on enterprise). Syntax is `AT (TIMESTAMP => …)` / `BEFORE (STATEMENT => …)`.
Beyond the retention window, data falls into "Fail-safe" (admin-only) — so it
is system-time with a *finite horizon*, not the unbounded audit trail SQL:2011
implies ([Snowflake Time Travel](https://docs.snowflake.com/en/user-guide/data-time-travel)).

**ClickHouse.** No first-class temporal grammar. `ReplacingMergeTree` collapses
duplicates on a version column at merge time — explicitly *destroys* history
after merge. `VersionedCollapsingMergeTree` is the only audit-safe variant
([ClickHouse VCMT](https://clickhouse.com/docs/engines/table-engines/mergetree-family/versionedcollapsingmergetree)).
Effectively tx-time with eventual loss.

**ImmuDB.** Cryptographically-verifiable append-only ledger. Single axis
(write/tx time). Time travel is a key-history lookup; valid-time would be
user-modelled.

**TerminusDB.** Git-style: every write is a commit on a named branch; "time
travel" means picking a commit. Branches and three-way merge are first class;
valid-time would be user-modelled.

**Dolt.** Same idea as TerminusDB but on top of MySQL syntax. Queries support
`AS OF '<commit-or-time>'`; branches, diff, merge, blame. Single axis (commit
time).

**TigerBeetle.** Pure double-entry, in-memory state machine. Single axis
(nanosecond-monotonic system timestamp). The TigerBeetle blog explicitly
acknowledges bitemporality and suggests stuffing a real-world timestamp into
the user-data field — i.e., the platform punts valid-time to the application
([TigerBeetle: One for the Treble, Two for the Time](https://tigerbeetle.com/blog/2026-01-14-bitemporality/)).

**Materialize.** Streaming, so the relevant axes are *event-time* (what the
record claims) vs *processing-time* (when the operator saw it). Materialize
supports SQL:2011-flavoured `FOR SYSTEM_TIME AS OF PROCTIME()` for as-of joins
against slowly-changing dimensions. Conceptually this is the streaming analog
of bitemporality, with watermarks playing the role of the system-time clock.

**Apache Flink.** Same event-time vs processing-time distinction, with
*watermarks* as the explicit progress mechanism. Flink's terminology is *the*
reference in stream processing; many SQL-streaming products inherit it.

## 4. Terminology comparison

**"valid-time" vs "application-time" vs "business-time" vs "effective-date".**
*valid-time* is Snodgrass's term and the dominant academic / Clojure / XTDB /
Oracle prose usage; *application-time* is the SQL:2011 grammar word; DB2 alone
renamed it *business-time*; *effective-date* is the insurance/contract domain
word for a *single point*, not a range. Trade-off: *valid-time* is the most
googleable and aligns with what developers find when they research the
concept; *application-time* matches the SQL grammar token but is a less-common
prose term and risks sounding like "application server time".

**"transaction-time" vs "system-time" vs "processing-time" vs "as-at".**
*transaction-time* is Snodgrass and Datomic; *system-time* is the SQL:2011
grammar token and what XTDB v2, MariaDB, MS SQL Server, DB2 use; *processing-time*
is Flink/Materialize; *as-at* is a finance/audit colloquialism for "as the books
stood at date X". Trade-off: *transaction-time* is precise but collides with
"a database transaction"; *system-time* is the standard-aligned grammar but
collides with "system clock"; *as-at* is what auditors actually say in
conversation.

**"bitemporal" vs "system-versioned + application-versioned" vs "as-of-X +
valid-at-Y".** *Bitemporal* is the term of art — every paper, every standards
document, every academic course uses it. *System-versioned + application-time*
is what SQL:2011 grammar produces but is a mouthful. *as-of + valid-at* is
the operational reading suitable for tutorials. Recommendation: use
*bitemporal* once on the landing page with a one-sentence gloss, then talk
about the two axes by their names.

**"current state" vs "snapshot" vs "head".** *Current state* is the
plain-English win for "right now, what the books say". *Snapshot* is precise
but overloaded with backup/CoW semantics. *Head* is git vocabulary and biases
toward branch-flavoured systems (TerminusDB, Dolt). Datomic and datahike both
expose "db" as the value; we should not invent a fourth name.

**XTDB v2 "system-time" vs datahike "tx-time".** They are the *same axis* by
construction — the wall-clock instant the DB recorded the assertion, monotone
in commit order, system-managed, immutable. The renaming in XTDB v2 was
explicitly to align with SQL:2011 grammar (`FOR SYSTEM_TIME AS OF`). They are
**interchangeable in semantics**; they differ only in spelling. pg-datahike
will need to expose the SQL:2011 spelling at the surface even if the EDN /
datalog API keeps `:as-of-tx`.

## 5. Recommendation

Standardise on the following user-facing term set for kontor, stratum, datahike
and pg-datahike documentation.

| Concept | Term | Notes |
|---|---|---|
| The two-axis idea (once, on the landing page) | **bitemporal** | Gloss it: "two time axes — what you knew, and when you knew it." Don't repeat the word in API names. |
| User-managed axis | **valid-time** | Aligns with XTDB, Oracle, the academic literature, and the SQL:2011 grammar token `VALID_TIME` that pg-datahike will emit. Avoids the DB2-only "business-time" and the SQL-grammar-only "application-time". |
| DB-managed axis | **tx-time** *in EDN / Clojure APIs*, **system-time** *in SQL surface* | Two surfaces, two registers: Clojure people read "tx-time" and immediately map it to Datomic's `:db/txInstant`. SQL people read `FOR SYSTEM_TIME AS OF` and immediately map it to SQL:2011. Document the equivalence once, prominently. |
| "Right now" view | **current** (the default, no special word) | A db without `:as-of-…` arguments *is* the current view; we do not need a noun for it. |
| Query parameters in Clojure | **`:as-of-tx`** and **`:as-of-valid`** | Mirrors existing datahike `(d/as-of conn t)`. Defaults are both `now`. |
| Query parameters in SQL (pg-datahike) | **`FOR VALID_TIME AS OF`**, **`FOR SYSTEM_TIME AS OF`** | Standard SQL:2011 grammar; matches XTDB v2 and MariaDB. |
| Range/portion queries | **`FOR VALID_TIME BETWEEN … AND …`** etc. | SQL:2011 grammar; Allen relations available through `OVERLAPS`/`CONTAINS`. |
| History view | **`history`** (Clojure), **`FOR VALID_TIME ALL FOR SYSTEM_TIME ALL`** (SQL) | Keep datahike's existing `history` verb; don't rename. |

**Reasoning.**

1. *valid-time* wins on every axis we care about: it is the most-googled term,
   it is what XTDB users (our nearest Clojure neighbour) already type, it is
   what Oracle/Snodgrass use in prose, and it matches the SQL grammar token
   `VALID_TIME` that pg-datahike will emit.

2. *tx-time* in EDN preserves continuity with datahike's existing
   `(d/as-of conn t)` / `(d/since conn t)` / `(d/history conn)` and with
   Datomic muscle memory. Renaming the EDN surface to "system-time" would
   gain SQL alignment but break years of Clojure idiom.

3. *system-time* in SQL is non-negotiable — that's the SQL:2011 token and
   what every SQL temporal DB (MariaDB, MS SQL Server, DB2, XTDB v2) emits.

4. *bitemporal* survives as the concept name because every textbook, vendor
   page, conference talk, and academic paper uses it. Reinventing the word
   would isolate us from the literature. But we use it sparingly — once
   prominently, then never in API names.

5. We deliberately **do not** adopt *application-time* (too SQL-grammar-only),
   *business-time* (DB2-only), *effective-date* (point, not range), or
   *as-at* (auditor jargon).

## 6. Open questions for the maintainer

1. **EDN vs SQL split.** Are you comfortable with the dual register
   (`:as-of-tx` in EDN, `FOR SYSTEM_TIME` in SQL)? An alternative is to make
   the EDN keyword match the SQL token literally (`:as-of-system`); this
   gains uniformity at the cost of breaking the existing `(d/as-of conn t)`
   datahike vocabulary, where `as-of` already means tx-time.

2. **Default for valid-time on writes.** XTDB v2 defaults valid-time to
   transaction-time when the user omits it. Should stratum follow that
   default, or require an explicit `:valid-time` on every write? Default-to-tx
   is friendlier for newcomers but hides the bitemporal modelling decision.

3. **Range predicates.** Will we expose Allen's full 13 relations or only the
   SQL:2011 subset (`CONTAINS`, `OVERLAPS`, `PRECEDES`, `SUCCEEDS`,
   `IMMEDIATELY PRECEDES`, `IMMEDIATELY SUCCEEDS`, `EQUALS`)? The SQL:2011
   subset is sufficient for almost every accounting scenario; full Allen is
   nice for legal/regulatory queries.

4. **History surface.** datahike currently has `(d/history conn)` returning a
   db value. Do we want a `history` *parameter* (`:as-of-tx :history`) for
   uniformity with `:as-of`, or keep the two as orthogonal verbs?

5. **Naming the period columns in pg-datahike.** MS SQL Server uses
   `ValidFrom`/`ValidTo`; MariaDB uses `row_start`/`row_end`; XTDB v2 uses
   `_valid_from`/`_valid_to`/`_system_from`/`_system_to`. Recommendation:
   match XTDB v2 exactly — it's the closest neighbour and the column names
   are unambiguously "system-managed" because of the underscore prefix.

6. **"Bitemporal" in marketing copy.** The word is academically correct but
   off-putting to newcomers. Consider a one-line gloss on the landing page —
   "kontor remembers both *what* was true and *when you learned* it was true" —
   then earn the word *bitemporal* on the first detail page.

## Sources

- [Wikipedia — Temporal database](https://en.wikipedia.org/wiki/Temporal_database)
- [Snodgrass — Temporal Databases (EDC)](https://www2.cs.arizona.edu/~rts/pubs/EDC.pdf)
- [Jensen et al. — Consensus Glossary 1998 (Springer)](https://link.springer.com/chapter/10.1007/BFb0053710)
- [SQL:2011 — Wikipedia](https://en.wikipedia.org/wiki/SQL:2011)
- [Illuminated Computing — Survey of SQL:2011 Temporal Features](https://illuminatedcomputing.com/posts/2019/08/sql2011-survey/)
- [Martin Fowler — Bitemporal History](https://martinfowler.com/articles/bitemporal-history.html)
- [Datomic — Transaction Data Reference](https://docs.datomic.com/transactions/transaction-data-reference.html)
- [Val on Programming — Datomic: this is not the history you're looking for](https://vvvvalvalval.github.io/posts/2017-07-08-Datomic-this-is-not-the-history-youre-looking-for.html)
- [XTDB v1 — Bitemporality](https://v1-docs.xtdb.com/concepts/bitemporality/)
- [XTDB — Time in XTDB](https://docs.xtdb.com/about/time-in-xtdb.html)
- [XTDB v2 — Launch blog post](https://xtdb.com/blog/launching-xtdb-v2)
- [JUXT — Introducing Crux](https://www.juxt.pro/blog/introducing-crux/)
- [MariaDB — Bitemporal Tables](https://mariadb.com/docs/server/reference/sql-structure/temporal-tables/bitemporal-tables)
- [MariaDB — System-Versioned Tables](https://mariadb.com/docs/server/reference/sql-structure/temporal-tables/system-versioned-tables)
- [MariaDB — Application-Time Periods](https://mariadb.com/docs/server/reference/sql-structure/temporal-tables/application-time-periods)
- [PostgreSQL wiki — Temporal Extensions](https://wiki.postgresql.org/wiki/Temporal_Extensions)
- [PostgreSQL wiki — SQL2011Temporal](https://wiki.postgresql.org/wiki/SQL2011Temporal)
- [PGXN — temporal_tables](https://pgxn.org/dist/temporal_tables/)
- [Microsoft Learn — Temporal Tables](https://learn.microsoft.com/en-us/sql/relational-databases/tables/temporal-tables)
- [Microsoft Learn — Querying System-Versioned Temporal Tables](https://learn.microsoft.com/en-us/sql/relational-databases/tables/querying-data-in-a-system-versioned-temporal-table)
- [Oracle — Using Temporal Validity](https://docs.oracle.com/database/121/VLDBG/GUID-AF78C832-516A-4686-9DDF-CE12597F7723.htm)
- [Oracle — Flashback Technology](https://docs.oracle.com/en/database/oracle/oracle-database/26/adfns/flashback.html)
- [IBM — A Matter of Time (DB2 z/OS white paper)](https://public.dhe.ibm.com/software/data/sw-library/db2/papers/A_Matter_of_Time_-_DB2_zOS_Temporal_Tables_-_White_Paper_v1.4.1.pdf)
- [IBM — BUSINESS_TIME period](https://www.ibm.com/docs/en/db2/11.1.0?topic=tables-business-time-period)
- [Snowflake — Time Travel](https://docs.snowflake.com/en/user-guide/data-time-travel)
- [ClickHouse — VersionedCollapsingMergeTree](https://clickhouse.com/docs/engines/table-engines/mergetree-family/versionedcollapsingmergetree)
- [ImmuDB — explained](https://docs.immudb.io/master/immudb.html)
- [TerminusDB — GitHub](https://github.com/terminusdb/terminusdb)
- [Dolt — Git for Data](https://docs.dolthub.com/introduction/getting-started/git-for-data)
- [Dolt — Unlocking Time-Travel](https://www.dolthub.com/blog/2023-01-18-unlocking-time-travel/)
- [TigerBeetle — One for the Treble, Two for the Time](https://tigerbeetle.com/blog/2026-01-14-bitemporality/)
- [Apache Flink — Timely Stream Processing](https://nightlies.apache.org/flink/flink-docs-stable/docs/concepts/time/)
- [Confluent — Apache Flink 101: Event Time and Watermarks](https://developer.confluent.io/courses/apache-flink/timely-stream-processing/)
- [Materialize — Stream Processor Comparison](https://materialize.com/blog/stream-processor-comparison/)

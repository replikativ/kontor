# 66 — datahike-bitemporal-v1 vs XTDB v1/v2: bitemporal feature gap analysis

Date: 2026-05-16.
Audit target: `datahike-bitemporal-v1` on branch `feature/bitemporal-v1` (commit
`576d6205`) plus `stratum` on branch `feature/valid-time` (commit `4a0848b`).
References: `xtdb` (v1) and `xtdb2` working copies at HEAD.

Cells in the table below use four columns: **X1** = XTDB v1, **X2** = XTDB v2,
**DH-q** = datahike datalog/`d/valid-*` wrappers, **DH-s** = datahike +
stratum secondary index (PG-wire SQL surface). License caveat: XTDB v2 is
MPL-2.0, XTDB v1 was MIT — references are conceptual; no code is lifted.

---

## 1. Summary

- **Top P0** — datahike's primary EAVT/AEVT/AVET indices remain valid-time-blind.
  Any datalog query that does *not* route through the stratum secondary
  (every non-attribute-coverage query, every entity-API call,
  every `pull`, every aggregate over a non-secondary attr) sees the
  *retraction* axis only. XTDB v1/v2 treat every column as VT-aware at the
  storage layer. Until datahike either (a) makes the primary indices
  VT-aware or (b) documents the routing contract so consumers can opt
  every query through the secondary, the substrate is "bitemporal in some
  queries, not others" — a footgun for `kontor`'s entity-history scenarios.
- **Top P1** — datahike has **no FOR PORTION OF VALID_TIME DML** at the
  datalog layer and no temporal `:db/retractEntity`. SCD2 surgery only
  exists inside stratum (`vt-persist-transient-stratum-index`,
  `src-secondary/datahike/index/secondary/stratum.clj:994`) and never reaches
  the EAV store. A "back-correct an invoice from 2024-03 onward" workflow
  silently rewrites tx-time history outside the secondary.
- **Top P2** — no first-class PERIOD type, no `FOR SYSTEM_TIME AS OF` cross-axis
  composition at the datalog layer (only via `(d/as-of (d/valid-at db vt) tx)`
  composition; works but isn't surfaced as a coherent query feature). XTDB v2
  exposes both axes uniformly.

---

## 2. By-feature comparison

| Feature | X1 | X2 | DH-q | DH-s |
| --- | --- | --- | --- | --- |
| Valid-time per **datom/row** | partial — per-document, not per-attr (`core/src/xtdb/tx.clj:133`) | ✓ per-row (`core/src/main/kotlin/xtdb/indexer/LiveTable.kt:39-40`) | partial — VT attached to *tx*, inherited by every datom in it (`src/datahike/db/transaction.cljc:232`) | ✓ per-row in vt-mode dataset (`src-secondary/datahike/index/secondary/stratum.clj:279-282`) |
| System-time per row | ✓ tx-time recorded (`core/src/xtdb/kv/index_store.clj:240-258`) | ✓ derived (Ceiling/Polygon, `core/src/main/kotlin/xtdb/bitemporal/Ceiling.kt:78-122`) | partial — `:db/txInstant` recorded; no `system_to` axis except inside stratum | ✓ stored explicitly (intentional SCD2 mutate; `src-secondary/datahike/index/secondary/stratum.clj:281-282`, justified ADR-NN) |
| `AS OF VALID_TIME <point>` query | ✓ datalog (`db node {::xt/valid-time vt}`, `core/src/xtdb/api.clj:359`) | ✓ XTQL + SQL (`api/src/main/clojure/xtdb/xtql.clj:246-247`, antlr `Sql.g4:583`) | ✓ `d/valid-at` (`src/datahike/api/impl.cljc:186-216`) + datalog rule (`src/datahike/query.cljc:637-641`) | ✓ via `FOR VALID_TIME AS OF` (stratum SQL, `src/stratum/sql/rewrite.clj:413-425`) |
| `FOR VALID_TIME BETWEEN/FROM/TO` SELECT | partial — only via entity-history options (`core/src/xtdb/api/HistoryOptions.java:30-37`) | ✓ (`api/src/main/clojure/xtdb/xtql.clj:249-256`; `Sql.g4:583`) | ✓ `d/valid-between` + `d/valid-during` (`src/datahike/api/impl.cljc:264-293`) + datalog rules (`src/datahike/query.cljc:644-654`) | ✓ (`src/stratum/sql/rewrite.clj:426-427`) |
| `FOR ALL VALID_TIME` SELECT | partial — `entity-history :with-corrections?` (`core/src/xtdb/api.clj:235`) | ✓ (`Sql.g4:584`) | ✓ `d/valid-all` (`src/datahike/api/impl.cljc:295-305`) | ✓ (`src/stratum/sql/rewrite.clj:366`) |
| `FOR PORTION OF VALID_TIME` DML | ✓ via `:crux.tx/put [doc start-vt end-vt]` (`core/src/xtdb/tx.clj:133`) | ✓ (`Sql.g4:828`; PATCH and UPDATE both) | ✗ — datalog `d/transact` has no temporal-portion semantics | ✓ (`src/stratum/sql/rewrite.clj:247-265`); SCD2 surgery is inside the index, not the EAV store |
| `FOR ALL VALID_TIME` DML | ✓ (legacy evict-with-time-ranges, deprecated) | ✓ (`Sql.g4:829`) | ✗ | ✓ (`src/stratum/sql/rewrite.clj:366`) |
| `PERIOD` value type / `PERIOD(a,b)` ctor | ✗ | ✓ (`Sql.g4:358`; `OVERLAPS '(' expr ( ',' expr )+ ')'` on rows-of-PERIOD `Sql.g4:357`) | ✗ — Allen rules use raw 4-arg form (`src/datahike/query.cljc:667-699`) | ✗ — deliberate; 4-arg form covers identical use cases (`src/stratum/sql.clj:742-744`) |
| Allen interval predicates | partial — `valid-time-overlap?` only via custom preds | ✓ as binary infix `OVERLAPS/CONTAINS/PRECEDES/…` on PERIOD operands (`Sql.g4:207-213`) | ✓ 10 four-arg rules: overlaps, equals, contains, strictly-contains, precedes (+strict, +immediate), succeeds (+strict, +immediate), meets (`src/datahike/query.cljc:667-699`) | ✓ 10 four-arg SQL functions: `OVERLAPS / EQUALS_PERIOD / CONTAINS_PERIOD / PRECEDES / STRICTLY_PRECEDES / IMMEDIATELY_PRECEDES / SUCCEEDS / STRICTLY_SUCCEEDS / IMMEDIATELY_SUCCEEDS / MEETS` (`src/stratum/sql.clj:749-785`) |
| `ERASE` (cross-axis physical purge) | ✓ `:crux.tx/evict` (`core/src/xtdb/tx.clj:174-186`) | ✓ `ERASE FROM` (`Sql.g4:847`); LiveTable op-tag (`core/src/main/kotlin/xtdb/indexer/LiveTable.kt:113-125`) | partial — datahike's `:db/purge` exists (ADR-007 of kontor) but is not VT-bounded; same-row partial-vt erase has no story | ✓ `ERASE FROM …` rewrites to `DELETE FROM …` with `:erase? true` flag (`src/stratum/sql/rewrite.clj:141-166`) |
| `FOR SYSTEM_TIME AS OF <point>` | ✓ via tx-time in basis (`core/src/xtdb/api.clj:386`) | ✓ (`Sql.g4:578-579`) + XTQL `for-system-time` (`api/src/main/clojure/xtdb/xtql.clj:371-372`) | partial — `(d/as-of db tx-id-or-time)` composes with `d/valid-at`; not surfaced as a single API call | partial — antlr grammar has `'FOR SYSTEM_TIME'` keyword but rewrite skips it (`src/stratum/sql/rewrite.clj:551`) — not yet implemented in stratum SQL surface |
| `WITHOUT OVERLAPS` integrity (built-in) | ✓ via single-doc invariant | ✓ "WITHOUT OVERLAPS … is built-in" (`docs/src/content/docs/concepts/key-concepts.adoc:71`) | ✗ — overlapping vf/vt on the *same entity* is permitted; only `vf<vt` on a single tx is checked (`src/datahike/db/transaction.cljc:970-977`) | partial — `upsert!` rejects overlaps with `:auto-split?` opt-in (`src/stratum/dataset.clj:309-373`); only one axis (valid) |
| Multi-axis (bitemporal) history walk | ✓ `entity-history` with start/end vt + start/end tx (`core/src/xtdb/api.clj:236-243`) | ✓ XTQL `(at vt)` + `(at tx)` on a single From (`api/src/main/clojure/xtdb/xtql.clj:319`) | partial — `(d/valid-at (d/as-of db tx) vt)` composes (`src/datahike/api/impl.cljc:194-197`) but no equivalent of `entity-history :with-corrections?` | partial — stratum dataset stores both axes, so a 4-axis query is expressible but no DSL fronts it |
| Schema-level VT opt-in | n/a — VT always-on | n/a — VT always-on | ✓ `:db.valid/from`/`:db.valid/to` are system schema, optional per tx (`src/datahike/schema.cljc:129-134`; `src/datahike/constants.cljc:170-173`) | ✓ `:valid-time true` in `:db.secondary/config` (`src-secondary/datahike/index/secondary/stratum.clj:277`) |
| Clock pinning | ✓ `::xt/tx-time` override on submit (`core/src/xtdb/api.clj:148-149`) | ✓ `SETTING CLOCK_TIME` (`Sql.g4:68`) + `SHOW CLOCK_TIME` (`Sql.g4:40`) | ✓ tx-meta `:db/txInstant` + `alter-var-root` (`src/datahike/tools.cljc:64-78`) | ✓ `SET datahike.clock_time = …` (`src/stratum/sql.clj:2072-2098`) |
| Bitemporal as-of-tx-and-vt at storage level | ✓ Z-order morton encoding (`core/src/xtdb/kv/index_store.clj:275-301`) | ✓ Polygon + Ceiling (`core/src/main/kotlin/xtdb/bitemporal/Polygon.kt:20-44`) | ✗ at primary; partial via stratum | partial — vt-pushdown into `-search-at-vt` (`src-secondary/datahike/index/secondary/stratum.clj:619-650`); single-axis only |
| Range scans over vt-window | ✓ | ✓ (zone-map pruning) | ✗ — AVET on `:db.valid/from` exists but no planner-level rule pushes vt-predicates through it consistently | ✓ zone-map on vf/vt columns (`src-secondary/datahike/index/secondary/stratum.clj:622-625`, paired pruning verified) |
| Cross-attribute VT consistency (one tx, many attrs) | ✓ — VT is per-document | ✓ — VT is per-row | ✓ — VT is per-*tx*, so every datom in a tx shares the same window (this is *better* than XTDB for kontor's posting workflow) | ✓ inherits from datahike |
| Reverse-charged retract with VT bounds | ✓ `:crux.tx/delete [k start-vt end-vt]` (`core/src/xtdb/tx.clj:136-137`) | ✓ DELETE with `FOR PORTION OF VALID_TIME` | ✗ | ✓ `retract!` with valid-time window (`src/stratum/dataset.clj:432-466`) |

---

## 3. P0 ship-blockers

### P0-1 — Primary indices are valid-time blind

**Where it bites**: any datalog query that doesn't run through a vt-aware
secondary index sees only the *retraction* axis. Examples:

- `(d/q '[:find ?e :where [?e :invoice/total ?t]] db)` on a `db` carrying
  `:datahike/valid-at` meta — the EAVT/AEVT scan ignores the marker; the
  FilteredDB predicate (`src/datahike/api/impl.cljc:166-184`) salvages
  correctness *only* because `d/valid-at` always wraps via `dcore/filter`.
- `(d/entity db <eid>)` — uses `IDB/-datoms` directly, no FilteredDB
  wrapping, no vt awareness. Even with the new `valid-at` marker, the
  entity API returns the current EAV state, not the as-of-VT state. Cf.
  `src/datahike/api/impl.cljc:213-216` — meta is set but `dcore/filter`
  is the only path that enforces it; entity API bypasses it.
- Aggregates / pull / index-range — same story.

**XTDB v1 reference**: `entity-as-of-resolver` is threaded through
`new-entity-resolver-fn` in `core/src/xtdb/query.clj:1695-1696`; every
entity lookup picks up the basis automatically. v2 does the same via
`TemporalBounds.intersects` (`core/src/main/kotlin/xtdb/util/TemporalBounds.kt:40-41`)
inside the scan operator — VT is non-negotiable storage-level metadata.

**Remediation**: short-term, document that "VT correctness requires routing
through `d/valid-at` (which returns a FilteredDB) *and* using `d/q` —
`d/entity`, `d/pull`, `d/datoms` do not honour the marker." Long-term,
either (a) push the FilteredDB pred down into the entity API (mirror what
the `:external-engine` planner branch already does for `d/q`) or (b)
specify in an ADR that vt-aware queries *must* go through the stratum
secondary and surface a friendly error from the entity API when called on
a vt-marked db.

### P0-2 — No `FOR PORTION OF VALID_TIME` at the datalog layer

The only way to "close" an invoice's vt-window today is to write a fresh tx
with `:db.valid/to <date>` set on the tx-meta — which closes *every* datom
in that tx symmetrically. There is no datalog primitive analogous to
`(xt/submit-tx node [[::xt/put doc start-vt end-vt]])` (XTDB v1,
`core/src/xtdb/api/tx/PutOperation.java:6-18`) that lets a single tx assert
"this fact was true from `[start, end)`, and nothing else changes."

**Why P0 and not P1**: kontor's user stories 4–7 (multi-entity intercompany,
SaaS subscription deferred revenue, Indian B2B IRN corrections) all need
back-dated corrections that close a posting's vt-window without disturbing
its sibling postings. The stratum SCD2 surgery
(`src-secondary/datahike/index/secondary/stratum.clj:994-1038`) closes rows
in the secondary index but the EAV truth (the actual `:posting/amount` datoms)
is untouched, so a datalog audit query reports "the posting is still
in force forever," contradicting the secondary's view.

**Remediation**: design `(d/portion-of-valid-time <tx-data> start-vt end-vt)`
or accept it as a tx-meta key `:db.valid/portion-of? true` that the
transactor handles by writing close-and-reopen datom pairs on the affected
entities. Mirrors XTDB v1's put-coords logic
(`core/src/xtdb/tx.clj:92-129`).

### P0-3 — `:db/purge` has no valid-time bounds

ADR-007 of kontor permits `:db/purge` for audited deletions but the API
takes only an entity-id, not a vt-window. XTDB v1's `:crux.tx/evict` shares
the limitation but only after `XTDB_EVICT_TIME_RANGES=EVICT_ALL` env
override (`core/src/xtdb/tx.clj:171-186`); v2's `ERASE FROM table WHERE …
FOR PORTION OF VALID_TIME FROM x TO y` is the proper SQL:2011 form.

**Why P0 for kontor**: GDPR / "right-to-be-forgotten" deletes need to
remove a partner's PII *for a specific time window* (during which they
were a customer) without wiping the rest of their history. Today the kernel
either purges everything or nothing.

**Remediation**: extend `:db/purge` to accept `:db.purge/from`,
`:db.purge/to`; refuse the operation when the entity has datoms outside
that window unless `:db.purge/cascade? true` is set.

---

## 4. P1 should-have

### P1-1 — WITHOUT OVERLAPS constraint is single-axis and opt-in inside stratum

`stratum/dataset.clj:309-373`'s overlap detector kicks in at the secondary
layer only; the EAV store accepts two postings with overlapping vt-windows
on the same entity silently. The validator
`src/datahike/db/transaction.cljc:961-977` checks only `vf<vt` *per tx*,
not "no other tx on this entity overlaps."

**Why P1**: a real "subscription monthly recognition" workload double-asserts
revenue if a back-dated correction overlaps the original. The secondary's
`:auto-split?` flag fixes it for stratum-routed reads but a datalog query
straight through EAVT still sees both.

**Remediation**: enforce per-entity vt-disjointness as a `:db/ensure`
predicate on the entity-spec; document the constraint as a kontor-level
ADR.

### P1-2 — System-time ("tx-time") axis not exposed as a query primitive

datahike has `d/as-of` (tx-time) and `d/valid-at` (vt) but no fused
`d/at` taking both. XTDB v1 and v2 both expose the joint basis as a single
map (`:xtdb.api/tx`, `:xtdb.api/valid-time`). Today you must write
`(d/valid-at (d/as-of db tx-time) vt)` — works but is verbose and the
ordering matters (filters compose intersection-wise, but a user expecting
"vt fixed first, then tx-time replay" can get surprised).

**Remediation**: add `d/at-basis db {:tx-time t :valid-time v}` that
internally orders the wrappers and asserts both markers consistently.

### P1-3 — `entity-history` equivalent missing

XTDB v1's `entity-history` (`core/src/xtdb/api.clj:227-252`) is the
canonical "show me everything you know about this entity, bitemporally"
audit primitive. Datahike has `d/history` (tx-time-only) and
`d/valid-all`. Neither walks the (vt, tx-time) lattice for one entity.

**Remediation**: build `d/entity-history db eid {:sort :asc :with-corrections? true}`
returning a vec of `{:eid :tx-id :tx-time :valid-from :valid-to :datoms}`
maps. Mostly assembly of existing primitives.

### P1-4 — `SET datahike.clock_time` is stratum-SQL only; the datalog side relies on per-tx `:db/txInstant` or `alter-var-root`

`src/datahike/tools.cljc:64-78` acknowledges this. A test suite that
intermixes datalog and SQL has two different clock-pinning mechanisms.

**Why P1, not P0**: workarounds documented; doesn't block functionality.

**Remediation**: surface `(d/with-clock! conn instant)` /
`(d/clear-clock! conn)` on the conn that mutates a private atom the
transactor reads. Makes both sides consistent.

### P1-5 — No `PERIOD` type at all

Both datahike datalog rules and stratum SQL Allen predicates take 4
scalars (`?af ?at ?bf ?bt`) rather than a PERIOD value. This is
deliberate (`src/stratum/sql.clj:742-744`) — same expressive power,
simpler internals — but means consumers can't `SELECT vt_period FROM t`
or `(d/q '[:find ?p :where [(period ?vf ?vt) ?p]])`. XTDB v2 makes
PERIOD a first-class column (`_valid_time`/`_system_time`
in `core/src/main/clojure/xtdb/sql.clj:301`).

**Why P1, not P0**: every use case is expressible without it; cost is
ergonomics.

**Remediation**: ship a `:db.type/period` value type + accessors
`(period-lower p)` `(period-upper p)`. Pure DX upgrade.

---

## 5. P2 nice-to-have

### P2-1 — Built-in `valid-at` datalog rule requires explicit `?tx` binding

`src/datahike/query.cljc:637-641` rules take `?tx` as the first argument.
For most queries the user wants "filter by the *current* fact's tx" — the
implicit datom's tx. Today they must write
`[?e :foo ?v ?tx] (valid-at ?tx <vt>)`. Could add a 2-arg form that
auto-binds.

**Remediation**: tweak `auto-inject-built-in-rules`
(`src/datahike/query.cljc:720-743`) to support the implicit form.

### P2-2 — No SQL `FOR SYSTEM_TIME AS OF` in stratum

Grammar mentions it (`src/stratum/sql/rewrite.clj:551` comment) but it's
not parsed. Low-priority because datahike's `d/as-of` already covers it;
stratum doesn't yet need it because the secondary's own `_system_to`
column is mutated SCD2 (intentional, see §6).

### P2-3 — Backfill of historical pre-VT data uses `vt-from-floor = Long/MIN_VALUE`

`src-secondary/datahike/index/secondary/stratum.clj:285-287` — rows
materialized from existing AEVT data before vt-mode was enabled get an
artificial `_valid_from = -∞`. This is correct but surprising for
audit-trail readers who expect `_valid_from = _system_from`. A migration
note in the index doc would suffice.

### P2-4 — Allen predicates are not commutative-aware

`interval-overlaps?` is symmetric and that's fine, but
`interval-precedes?` and `interval-succeeds?` are stated as separate rules
even though `(A precedes B) ⇔ (B succeeds A)`. The planner doesn't
recognise the equivalence and won't substitute one for the other in
optimisations.

**Remediation**: minor; document only.

### P2-5 — No `:bitemporal-visualizer` style tooling

XTDB v2 ships a [bitemporal visualizer](https://bitemporal-visualizer.github.io/)
(`docs/src/content/docs/concepts/key-concepts.adoc:67`). Datahike has none.
Useful for kontor audit reviews.

---

## 6. Non-gaps — deliberate divergence

### 6.1 Stored vs derived `_system_to`

Datahike+stratum stores `_system_to` as a mutated SCD2 column
(`src-secondary/datahike/index/secondary/stratum.clj:281-282`); XTDB v2
derives it via Ceiling/Polygon from an append-only event log
(`core/src/main/kotlin/xtdb/bitemporal/Ceiling.kt`, `Polygon.kt`).

**Why divergence is correct**: the audit guarantee is *visibility under
`FOR SYSTEM_TIME AS OF <past>`*, not *physical immutability of the column*.
With one writer pinning `sys-now` per batch
(`src-secondary/datahike/index/secondary/stratum.clj:1001-1004`), every row
written by an older tx still has its old `_system_from` and `_system_to`
visible to a past-tt scan — the predicate `(sys_from ≤ tt) ∧ (sys_to > tt)`
admits the same rows whether `_system_to` is mutated-at-supersession or
derived-at-read. The standalone-stratum read-heavy case wins from
mutation (one column read, no Ceiling reconstruction); the audit semantic
is preserved.

**Caveat**: the symmetry holds only because the SCD2 surgery in `vt-persist-…`
runs inside a single tx (`tx-meta->sf` is captured once for the whole batch,
`stratum.clj:1014`). Concurrent writers would break this; datahike's
single-writer invariant protects it.

### 6.2 VT per-tx, not per-datom

Datahike attaches `:db.valid/from`/`:db.valid/to` to the *tx entity*
(`src/datahike/db/transaction.cljc:232`), not to each datom. XTDB v2
stores them per-row.

**Why divergence is correct**: for accounting / business-event use cases,
every datom in a tx shares the same vt window by construction (a posting's
amount, account, and partner are facts about the same business event). The
per-tx attachment is *less* data and equally expressive for the workload.
The cost is that a tx that writes facts with mixed vt windows (rare in
practice, common in test scenarios) needs to be split into multiple txes —
acceptable price.

### 6.3 No PERIOD type

`src/stratum/sql.clj:742-744` notes "PERIOD value type and `LOWER`/`UPPER`
accessors are deferred." Same on the datalog side. The 4-arg form
(`interval-overlaps? ?af ?at ?bf ?bt`) is isomorphic and the planner
doesn't have to thread a new value type through every operator.

### 6.4 Schema-level VT opt-in vs always-on

XTDB makes VT always-on; datahike makes it opt-in via tx-meta
(`src/datahike/schema.cljc:74-75`). Both are defensible. Datahike's
choice keeps the substrate usable for transactional workloads that don't
care about VT (a simple key-value store still works) at the cost of
having to remember to set `:db.valid/from` when you do care.

### 6.5 Auto-split as opt-in, not default

Stratum's `upsert!` rejects overlaps unless `:auto-split? true`
(`src/stratum/dataset.clj:309-313`). XTDB v2 always splits (WITHOUT OVERLAPS
built-in). Defensible because the conservative default catches accidental
overlap-on-overwrite bugs early; production code that needs split-on-overlap
explicitly opts in.

---

## 7. Coverage summary (lines exercised)

- datahike vt tests: 365+322+209 = 896 lines across 3 files
  (`test/datahike/test/valid_at_test.clj`,
  `test/datahike/test/stratum_vt_test.clj`,
  `test/datahike/test/valid_time_test.cljc`).
- Test coverage hits: every `d/valid-*` wrapper, every Allen rule
  (`interval-overlaps?`, `interval-contains?`, `interval-precedes?`),
  tx-meta `:db/txInstant` override, vf<vt validation, SCD2 close-on-supersession
  including the new system-time symmetry case
  (`test/datahike/test/stratum_vt_test.clj:294`).
- Untested-but-shipped: composition with `d/since`, `d/history`, attribute-refs
  mode + vt simultaneously, multi-entity vt-window overlap detection at the
  *datalog* layer (only tested at the stratum layer).

---

## 8. Recommendations in priority order

1. **(P0-1)** Decide and document the VT routing contract. Either lift vt
   awareness into the primary indices (large work, large win) or assert
   "vt-aware queries require stratum routing" and make the entity-API call-site
   either honour the marker or refuse with a clear error.
2. **(P0-2)** Design `FOR PORTION OF VALID_TIME` semantics at the datalog
   transactor — even a `:db.valid/portion-of` tx-meta hint would unblock
   kontor's correction workflows.
3. **(P0-3)** Extend `:db/purge` with `:db.purge/from`/`:db.purge/to`.
4. **(P1-1)** Lift `WITHOUT OVERLAPS` from stratum's opt-in into a
   datalog `:db.entity/preds` enforced check.
5. **(P1-2/3)** Surface a single `d/at-basis` and `d/entity-history`.
6. **(P1-5)** PERIOD type — defer until two consumers ask.
7. **(P2-*)** Polish.

---

## 9. References

Datahike (audit target):
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/api/impl.cljc:150-305` — `valid-*` wrappers, FilteredDB preds.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/query.cljc:608-705` — built-in Allen rules.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/db/transaction.cljc:228-260,950-977` — tx-meta plumbing, vf<vt validation.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/schema.cljc:65-167` — system-schema entries for `:db.valid/from`/`:db.valid/to`.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/constants.cljc:122-173` — sys-idents.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src/datahike/tools.cljc:56-78` — clock pinning.
- `/home/christian-weilbach/Development/datahike-bitemporal-v1/src-secondary/datahike/index/secondary/stratum.clj:269-650,994-1038` — vt-mode + SCD2 surgery + IValidTimeAware.

Stratum (audit target, upstream):
- `/home/christian-weilbach/Development/stratum/src/stratum/sql.clj:735-789,2072-2098` — Allen predicates, CLOCK_TIME.
- `/home/christian-weilbach/Development/stratum/src/stratum/sql/rewrite.clj:115-651` — FOR PORTION OF / FOR ALL / SELECT-side FOR VALID_TIME / ERASE.
- `/home/christian-weilbach/Development/stratum/src/stratum/dataset.clj:280-466` — upsert!/retract! with auto-split.

XTDB v1 (reference, MIT):
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/api.clj:175-280` — PXtdbDatasource, entity-history.
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/tx.clj:92-186` — put/delete coords, evict.
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/query.clj:1695-2009` — entity resolver, history threading.
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/kv/index_store.clj:235-318` — bitemp key encoding, morton-z.
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/api/HistoryOptions.java` — HistoryOptions Java API.
- `/home/christian-weilbach/Development/xtdb/core/src/xtdb/api/tx/PutOperation.java` — VT-bounded put.

XTDB v2 (reference, MPL-2.0):
- `/home/christian-weilbach/Development/xtdb2/core/src/main/antlr/xtdb/antlr/Sql.g4:40-909` — SQL grammar with FOR VALID_TIME / SYSTEM_TIME / PERIOD / Allen predicates / ERASE / SETTING CLOCK_TIME / DEFAULT VALID_TIME.
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/indexer/LiveTable.kt:35-125` — append-only event log per row, system_from stamped at tx, no system_to written.
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Ceiling.kt:43-122` — Ceiling for deriving system_to.
- `/home/christian-weilbach/Development/xtdb2/core/src/main/kotlin/xtdb/bitemporal/Polygon.kt` — Polygon construction for VT∪TT slabs.
- `/home/christian-weilbach/Development/xtdb2/api/src/main/clojure/xtdb/xtql.clj:227-336` — XTQL `(at vt)` / `(in from to)` / `(from x)` / `(to x)` / `:all-time` parsing.
- `/home/christian-weilbach/Development/xtdb2/docs/src/content/docs/concepts/key-concepts.adoc:62-75` — VT/TT contract narrative.

---

End of report.

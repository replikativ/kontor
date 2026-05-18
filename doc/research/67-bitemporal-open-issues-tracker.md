# 67 — Bitemporal stack: open-issues tracker

Date opened: 2026-05-16
Last updated: 2026-05-16 (PG-1 + DH-1 + DH-11 closed)

Cross-repo tracking of every known issue, deferred item, and follow-up
across the bitemporal stack. Each entry lists the repo, the line/file
or symbolic anchor, severity, and the path to a fix. Severity:

- **P0** — correctness or merge-blocker.
- **P1** — meaningful gap that should land before broad consumer
  adoption.
- **P2** — polish, ergonomics, perf upgrade.
- **DEFERRED** — explicitly out of scope for current arc; revisit if
  a use case demands it.

## stratum (PR #27 — open, ready to merge)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| ST-1 | DEFERRED | `server.clj` UPDATE FOR PORTION OF | `SET col = <expression>` not supported, literals only | Needs SELECT/UPDATE engine convergence; document only |
| ST-2 | P2 | `sql/rewrite.clj` `spec->predicate-sql` | Multi-table SELECT with two `FOR VALID_TIME` clauses rejected at parse time; the real fix is qualifier-aware column resolution in the planner | Planner work, ~weeks |
| ST-3 | P2 | docstring of `update-portion-via-index-backend` | `:n-updated` returns pre-mutation WHERE-match count (Postgres-partitioned-table-style semantic) — documented but unusual | Either accept or compute physical row delta |
| ST-4 | P2 | (deferred) | Per-pattern `{:valid-at t}` annotation in `:where` | Open API design call |
| ST-5 | P2 | (deferred) | Time-slicing helpers ("trial-balance as of each Friday of 2024") | Reporting-shaped; reopen if demanded |
| ST-6 | P2 | (deferred) | System-time DML grammar (only auto-stamp on writes today) | If/when a consumer needs to "correct system-time" |

## datahike-bitemporal-v1 (PR #828 — `feature/bitemporal-v1`)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| DH-1 | **DONE** (2026-05-16) | `transaction.cljc` `attrs-have-datoms?` | `proximum-secondary-contributes-merkle-root` failed because `attrs-have-datoms?` (transaction.cljc:124) called `dbi/-datoms db :aevt [a]` for each `:db.secondary/attrs` ident, which routes through `resolve-datom` → `validate-attr-ident` and throws on attrs not in the schema. Secondary specs legitimately reference not-yet-declared attrs (schema-flexibility :read, or index registered before any data writes). | Gate the `-datoms` lookup on `(contains? schema attr)` first — if the attr isn't in the schema it has zero datoms by definition. Suite: 78 audit+secondary tests pass; full suite pending. |
| DH-2 | DEFERRED | transactor | Atomic multi-tx-entity per commit (`d/transact-multi!`) for multi-vt-window writes | Documented; supersession-aware reads cover correctness without it |
| DH-3 | DEFERRED | `:db/purge` | No vt-window bounds | Composable in user-space via existing `:db/purge` primitive (see kontor research note 67 §kontor) |
| DH-4 | DEFERRED | `d/entity-history` | Not in the API; XTDB v1 has the canonical bitemporal audit walk | Lives more naturally as a kontor-layer audit primitive with companion-aware enrichment |
| DH-5 | DEFERRED | `d/at-basis` fused tx+vt API | Not present; composition `(d/valid-at (d/as-of db t) v)` works | Documented + assertion guards inversion |
| DH-6 | P1 | `d/valid-at` rule | The built-in `(valid-at ?tx ?at)` rule is single-axis (no supersession); only `d/valid-at` function applies polygon | Acceptable split; document |
| DH-7 | P1 | `valid-between`/`valid-during`/`valid-all` | Stay single-axis (no supersession) — intentional, mirrors XTDB's `FOR VALID_TIME BETWEEN` overlap-style semantic | Document |
| DH-8 | P2 | Allen rules | Don't auto-bind tx-vf/tx-vt from `?tx` — user must add `[?tx :db.valid/from ?vf]` patterns | Convenience macro |
| DH-9 | P2 | WITHOUT OVERLAPS | Not enforced at datalog layer (stratum has opt-in `:auto-split?`); two concurrent writes can leave overlapping vt windows on the same eid+attr | Add `:db.entity/preds` predicate at the kontor layer |
| DH-10 | P0 (post-bench) | perf | Supersession-aware `d/valid-at` does one EAVT scan per unique `(e,a,v)` in result set; cached per query. Heavy bitemporal workloads should route through a vt-aware secondary index (stratum) | Document the routing convention |
| DH-11 | **DONE** (2026-05-16) | `transaction.cljc` auto-stamp of `:db/txInstant` | `d/as-of <Date>` was ambiguous under tied `:db/txInstant` millis (back-to-back writes within a single wall-clock ms tied; flake reproduced ~5/1000 under 8-thread JVM load on `valid-at-composes-with-as-of`). | New `next-tx-instant` allocator returns `max(get-date, prev-tx-instant + 1ms)` — matches Datomic. Pinned clock becomes logical clock for free. Allocator is `^:dynamic` for future HLC/µs-Instant swaps. Suite: 1666 tests / 7694 assertions / 0 failures at the originally-flaky seed AND at a fresh random seed. Regression lock in `tx_instant_monotonic_test.clj` (200×8 thread reproducer + 4 contract tests). User-override path unchanged. |

## pg-datahike (PR #7 — `feature/valid-time`)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| PG-1 | **DONE** (2026-05-16) | SELECT after `SET datahike.valid_at` OR `FOR VALID_TIME AS OF` | Threw `getLookupThunk is not supported on FilteredDB` because 13 sites used `(:schema db)` direct keyword lookup; FilteredDB explicitly rejects keyword access via `getLookupThunk`. AsOfDB silently returned nil (defrecord ILookup) so it "worked by accident". | Replaced all 13 sites with `(dbi/-schema db)` (server.clj, sql.clj, sql/fns.clj, dump.clj); added `[datahike.db.interface :as dbi]` requires. `set-valid-at-survives-roundtrip` now passes; added `for-valid-time-as-of-survives-roundtrip` as regression lock. Full suite 778 tests / 2178 assertions / 0 failures. |
| PG-2 | P1 | `parse-temporal-set` for `valid_at` | Currently parses values via `parse-instant` which treats numeric strings as tx-id longs — wrong for vt. Documented in commit body as "users should pass instants" | Branch in parse: for vt keys, treat numerics as epoch-millis Date |
| PG-3 | P1 | `valid_from` / `valid_to` reserved but inert | Set but never consumed; `apply-temporal` only uses `:valid-at`. Should compose into `d/valid-between` (or equivalent) | Wire into apply-temporal as `:valid-between [from to]` when both are set |
| PG-4 | DEFERRED | `SET datahike.clock_time` | Not yet ported from stratum to pg-datahike; would pin the clock for `:db/txInstant` defaults across the session | Need to thread into datahike's transact path |
| PG-5 | DEFERRED | `ERASE FROM` SQL verb | Not ported; would map to `:db/purge` | Design call: is the SQL surface the right place for GDPR-style ops? |
| PG-6 | DEFERRED | DML `FOR PORTION OF VALID_TIME` | Blocked on DH-2 (atomic multi-tx-entity in datahike) | Reopen when DH-2 lands |
| PG-7 | P1 | `deps.edn` | `:local/root "../datahike-bitemporal-v1"` — needs revert to `:mvn/version` after datahike releases | Coordinate with datahike release |
| PG-8 | P2 | Commit message of `34c4d71` | "Commit 6 of feature/valid-time (kontor doc/research/57 §4)" — kontor-internal cross-ref | Squash on merge or reword via interactive rebase |
| PG-9 | P1 | Multi-table SELECT with 2× `FOR VALID_TIME` | Preprocessor rejects with same error as stratum; same planner-side limitation | Mirror stratum's fix when it lands |

## Newly added in pg-datahike (this branch)

Done (1 commit pending push):
- SELECT-side `FOR VALID_TIME AS OF / BETWEEN / FROM-TO / ALL` preprocessor
- `apply-temporal` 3-arity (override path)
- 10 Allen interval predicates in `sql-fn->clj-fn`
- 30 tests / 90 assertions / 0 failures

## scriptum (no PR yet; design in `../scriptum/.internal/01-valid-time-design.md`)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| SC-1 | DEFERRED | `-transact` adapter | Doesn't implement `IValidTimeAware`; non-vt-aware secondary, post-hoc filtered correctly today | Storage layer needs `_tx_id`/`_valid_from`/`_valid_to` Lucene fields + FILTER-clause search; design doc has phased plan |
| SC-2 | DEFERRED | retention integration | Append-only history means index grows with tx-count; need retention sweeper hook | Document the wiring contract |
| SC-3 | DEFERRED | layer 2 grouping | Polygon-correct search needs Lucene grouping module wiring | Phase D of design doc |
| SC-4 | DEFERRED | DF/IDF distortion | History-included corpus stats slightly skew relevance | Per-bucket indices as future optimization |

Estimated effort: ~10 days end-to-end.

## proximum (no PR yet; design in `../proximum/.internal/valid-time-design.md`)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| PX-1 | DEFERRED | `-transact` adapter | Doesn't implement `IValidTimeAware`; post-hoc filter DEGRADES RECALL because HNSW returns top-K by distance and filtering after-the-fact loses the next-K-vt-surviving | Use proximum's existing `nearest-filtered` with vt-pred for native filtering |
| PX-2 | DEFERRED | retraction handling | Currently calls `prox/delete` (hard) — should write tombstone vector for bitemporal mode | `:op :retract` in metadata, keep vector in graph |
| PX-3 | DEFERRED | `ef` recall tuning | Filtered search needs `ef` ≈ `default × (1 / pass_rate)` for same recall | Document; consider auto-tune later |
| PX-4 | DEFERRED | polygon layer 2 | Post-HNSW group-by `(eid, attr)` pick max-tx-id, drop retractions | ~2 days on top of layer 1 |
| PX-5 | DEFERRED | GDPR vs vt-retract | Two distinct delete semantics need to be wired | Adapter distinguishes `:db.purge/entity` (hard) vs `:added? false` (tombstone) |

Estimated effort: ~7–8 days end-to-end.

## kontor (overarching consumer)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| KT-1 | P1 | `doc/research/66-…md` | Out of date post-supersession. P0-1 framing ("primary indices are valid-time-blind") is wrong — corrected: FilteredDB filters every read API; only the meta marker is for stratum-pushdown. P0-3 (vt-bounded purge) over-stated — composable in user-space | Rewrite/update the note |
| KT-2 | P2 | (not started) | Bitemporal contract ADR not written; should formalize "what supersession gives, what stratum gives, consumer responsibilities" | Write ADR-NN — depends on user appetite |
| KT-3 | P2 | (not started) | `kontor.gdpr/erase-portion-of-valid-time!` helper for vt-bounded purge (composed from `:db/purge`) | ~25 LOC + tests, ship as kontor library function |
| KT-4 | P2 | (not started) | kontor `audit-history` with companion-aware enrichment (consumer of `d/entity-history` once that lands, or built directly from primitives today) | Depends on demand |

## stratum/datahike integration (the bridge in datahike-bitemporal-v1's `src-secondary/`)

| ID | Sev | Where | Issue | Path |
|---|---|---|---|---|
| BR-1 | DONE | `IValidTimeAware` stratum impl | Implemented; SCD2 surgery with system-time symmetry | Closed |
| BR-2 | DEFERRED | proximum/scriptum native vt-aware | Currently route through post-hoc filter (correct for scriptum; recall-degrading for proximum). See SC-1 / PX-1 | Each lives in its own upstream lib |
| BR-3 | P1 | `kontor.dsar/collect` + bitemporal | DSAR walk takes `:as-of-tx` but no per-entity `:as-of-valid` filter. Documented follow-up | Add to `kontor.dsar/collect` |

## Process notes

- **Severity calibration**: P0 means "would block merging the current PR." P1 means "should land in a follow-up before broad consumer adoption." P2 means "polish or perf, can wait." DEFERRED means "explicit out-of-scope decision."
- When a P0 lands on this tracker mid-arc, decide IMMEDIATELY: fix now, downgrade to P1 with explicit rationale, or revert the change that surfaced it.
- This document is the single source of truth for "what's left." If you find an issue that's not here, **add it here first** before fixing it — prevents silent loss when interrupted.
- Cross-reference: each entry should have a stable anchor (`ST-N`, `DH-N`, `PG-N`, etc.) so commits and PR descriptions can refer back.

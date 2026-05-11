# Investigation: `datopia/invariant` library fit for `kontor`

Source library: `/home/christian-weilbach/Development/datopia/invariant`
Date: 2026-05-09
Source agent: Explore

## What the library does

The `invariant` library extends Datahike with a declarative constraint-checking system. You declare invariants as Datalog queries — one per attribute — stored in the schema as `:invariant/rule` and `:invariant/query` datoms. Before a transaction commits, `assert-invariants` (`/home/christian-weilbach/Development/datopia/invariant/src/invariant/datahike.clj:43-62`) evaluates each invariant by passing four database snapshots to the query:

- `$before`: current DB state
- `$after`: DB after applying the transaction
- `$empty+txs`: empty DB with only the transaction applied
- `$datoms`: the transaction data itself

The query must return a truthy result (typically `true`, a tuple, or `:matches`). If it returns falsy, an `ex-info` exception is thrown with type `:invariant/invariant-mismatch` and the failed attribute/invariant in the ex-data map. **Throw-on-failure model**; no soft-validation reports.

**Example pattern** (from `/home/christian-weilbach/Development/datopia/invariant/dev-resources/valid_invariant.edn` — zero-sum balance check):

1. Subquery sums `:account/balance` changes from the empty+txs snapshot.
2. Verify `?sum-before == ?sum-after` (delta is zero-sum).
3. Verify all resulting balances are non-negative.

## Cost model

Per transaction:

- One query execution per affected attribute (`invariant/datahike.clj:53-55`). The system extracts attributes touched in `tx-data`, then runs registered invariants for those attributes.
- Four datalog evaluations per affected-attribute invariant (the four-database snapshot model). Sequential.
- `dc/db-with` (datahike's) is linear in transaction size. For a typical posting tx with 3-5 datoms, sub-millisecond per invariant; for bulk operations, scales linearly.
- **No history requirement**: works without `:keep-history? true`. We have it on for bitemporal anyway (ADR-008), so no extra cost.

## What it can express well

1. **Sum-to-zero** (double-entry): subquery to sum `$empty+txs` changes grouped by entity or commodity, assert sum equals zero. Mirrors the reference example almost exactly.
2. **Account must be active**: `[:find ?matches . :in $before $after $empty+txs $datoms :where [$after ?e :account/active true] [(= true) ?matches]]` — straightforward.
3. **Commodity mismatch**: `[$after ?posting :posting/commodity ?p-c] [$after ?account :account/commodity ?a-c] [(not= ?p-c ?a-c)]` — easy.
4. **Period locking**: query `:period/locked-at`; reject postings with `:posting/valid-from` within the locked range. Expressible.
5. **Tax repartition 100%**: subquery grouped by (tax, document-type, repartition-type), sum `:tax-rep/factor-percent`, assert == 100M. Expressible but verbose.

## What it can't express well

- **Sealing** (silent retract of posted entries): the four-database model sees state diffs, not *how* a datom got into/out of the diff. A retract is a side effect of the transaction logic, not an attribute on the resulting state. Per ADR-007, sealing is application middleware logic — outside the library's mental model.
- **Transaction state lifecycle** (draft → posted → cancelled, no skipping/regression): the library is per-attribute. Cross-attribute choreography (state ↔ posted-at ↔ posted-by all transitioning together) is awkward.
- **Cross-entity foreign-key integrity** with complex rules: possible but wrestling against the per-attribute mental model.

## Maturity and integration friction

- **Author**: Christian Weilbach (the user). Active development to mid-2023, then a long gap; two stashed work-in-progress commits Apr 2025.
- **No formal release**. Git-based dependency only.
- **Datahike pin: `0.6.1595`** (~2023). The current ecosystem is on `0.8.x`. **Library is at risk of bitrot** unless we bump it. Mitigated because we control both.
- **Clojure 1.11.1** (current).
- **Integration overhead**: minimal. Call `(invariant/assert-invariants conn tx-data)` before `d/transact`. Schema additions: `:invariant/rule`, `:invariant/query` as normal attrs. No reserved columns or config files.

## Recommendation: hybrid

**Adopt `invariant` for state-shape constraints; hand-roll behavior/lifecycle constraints in middleware.**

| Invariant | Where |
|---|---|
| Sum-to-zero per commodity | `invariant` |
| Account must be active | `invariant` |
| Posting commodity matches account commodity | `invariant` |
| Tax repartition sums to 100% per (tax, document-type, type) | `invariant` (or middleware — borderline; depends on tax-engine architecture) |
| Sealing: no silent retract of posted entries | middleware (`sealing.clj`) |
| Period locking: no posting in locked period | middleware (`period.clj`) |
| Transaction state lifecycle | middleware (`state_machine.clj`) |

### Why this split

1. The three top items are pure state predicates — exactly what `invariant` was designed for. Declaring as Datalog queries makes them composable, REPL-testable, decoupled from hand-rolled code.
2. Sealing is *behavior*, not state. The library can't see retracts; middleware can. Simpler and more explicit.
3. Period locking and tax-repartition-100% are expressible as invariants but read clearer as middleware predicates: `(assert (<= posting-valid-from period-start))` is more legible to auditors than a 10-line query. Accounting code is reviewed by humans who don't know Datalog; readability matters.
4. State-machine choreography (state ↔ posted-at ↔ posted-by transitioning together) is a Clojure validation function, not a query.

### Implementation sketch

```clojure
;; src/kontor/validation.clj
(defn transact-with-validation
  [conn tx-data]
  (invariant.datahike/assert-invariants conn tx-data)        ;; library
  (sealing/assert-no-silent-retracts (d/db conn) tx-data)    ;; middleware
  (period/assert-not-in-locked-period (d/db conn) tx-data)   ;; middleware
  (state-machine/assert-transition (d/db conn) tx-data)      ;; middleware
  (d/transact conn tx-data))
```

### Followups before integration

- **Bump `invariant` to datahike 0.8.x.** Will likely be a small day's work; we own the repo.
- **Add invariant resource files**: `resources/invariants/sum_to_zero.edn`, `account_active.edn`, `commodity_match.edn`.
- **Install hook**: `(install-invariants! conn)` after schema install, idempotent like the schema itself.
- **Test split**: each invariant has its own EDN + a `<name>_test.clj`. The middleware checks have their own `<module>_test.clj`.

## What `invariant` is NOT for

- **Behavior / temporal constraints**: can't see retract-vs-add or transaction context. Sealing, audit-trail enforcement.
- **Performance-critical bulk operations**: 10k postings × N invariants × 4 snapshots is expensive. Accounting bulk ops are rare and async; not a blocker, but watch the profile.
- **Complex multi-entity choreography**: state machines, "if A changes then B must follow." Code is better.

## Decision (will become ADR-011)

Adopt the hybrid approach. Track the `invariant` upgrade to datahike 0.8.x as a prerequisite to Phase 1 closure. Resources and middleware split per the table above.

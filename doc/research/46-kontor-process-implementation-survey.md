# 46 — `kontor.process` v1 — implementation survey

**Date:** 2026-05-14
**Method:** one agent, whole-tree read of every multi-step transactor
in kontor (kernel + all companions; ~110 `d/transact` sites). The
implementation-planning document for the `kontor.process` stage —
joins the research-before (notes 42, 44, 45).

## The model being implemented

A *process* is a sequence of **pure step fns** `(db, ctx) → tx-data |
{:tx-data :ctx}`. `run-process` runs the whole process as one atomic
transaction via datahike's `:db.fn/call` — the steps **monadically
flatten**: a `:db.fn/call` emits its tx-data fragment *plus* a
`[:db.fn/call run-steps … rest-steps]`, and the datahike transactor's
recursive `:db.fn/call` expansion *is* the monadic bind (each step
sees the db with all prior steps' tx-data applied — REPL-confirmed,
note 44 §2). Sub-transactors are therefore **not "called"** — they are
pure step-lists that splice in. Atomic + serializable by construction
(single writer); no snapshot-vs-commit race.

## The six problems, triaged

- **Problem A — orchestrators call sub-transactors that each
  `d/transact` (THE job).** `commence!` calls `acquire!` /
  `open-liability-book!` / `open-book!`; `run-lease!` calls
  `record-occurrence!` + `run-depreciation!`; the four
  `modification.clj` transactors call `record-modification!` /
  `revise-*-book!` / `mark-cancelled!`; `run-depreciation!`,
  `allocate-fifo!`, the inventory two-phase flows, `close-fiscal-
  year!` similarly. The fix: extract ~**10 leaf transactors** into
  pure `*-tx-data` builders (the entity-map construction — the bodies
  are already `let` + `cond->` maps), keeping a 1-line `!` wrapper for
  standalone callers. Mechanical per-split, but it **ripples** —
  `commence!` + 4 modification transactors + 2 runners converge on
  the lease/asset leaves, so the leaf-split must land as its own
  coherent commit *before* any orchestrator migration.
- **Problem B — reentrant `d/transact` deadlock.** Entirely a
  *consequence* of A: a `:db.fn/call` calling `d/transact` deadlocks
  (single writer, confirmed). Splitting the leaves eliminates it. No
  transactor has a reentrant `d/transact` that isn't a sub-transactor
  call.
- **Problem C — external calls mid-process (sagas): does not exist.**
  No network/filesystem/`TaxProvider` I/O inside the multi-step
  transactors. The `:side-effect-intent` substrate (ADR-041) already
  handles the genuinely-non-atomic cases correctly (`run-dunning!`
  writes intent rows in-tx; a worker drains them). **v1 needs no saga
  primitive** — `run-process` only needs to *coexist* with intent-row
  emission (a step can emit intent-row tx-data — it's just data).
- **Problem D — impure steps: does not exist.** No atoms / logging
  inside transactors; steps are pure modulo their reads.
- **Problem E — `kbt/with-vt` composition: a real design call.**
  `with-vt` appends one `{:db/id "datomic.tx" …}` map; there can be
  only one per transaction. Kernel builders (`build-transaction`, the
  `posting` builders) *already embed* `with-vt`. So `run-process`
  must **own valid-time**: `strip-tx-meta` every step fragment as it
  accumulates, and apply one outer `with-vt` for the whole process.
  Steps must not be trusted to emit tx-meta.
- **Problem F — composition with the `validate-and-apply` gate.** The
  kernel already runs every validated write through `[:db.fn/call
  validate-and-apply tx-data]` (`validation.clj:225`) — but the
  companion transactors call `d/transact` raw and skip it. The agent
  recommends **folding the validators into the process tx-fn**: when
  the step chain is exhausted, `run-steps` validates the accumulated
  tx-data (throws → whole tx aborts). One `:db.fn/call` family, the
  companions finally get sealing/period/invariant enforcement for
  free.

So: **C and D don't exist; B is a consequence of A; A is the whole
job; E and F are design calls with clear answers.** The
`:db.fn/call`-the-process model is sound across the codebase.

## Expensive-query inventory

Under this model the step reads run *inside the writer*. Only one
transactor has a *genuinely heavy* in-process read: **`closing/
close-period!`** via `balance/account-balance`, which `d/pull`s every
posting against every P&L account and bitemporal-filters in Clojure —
O(P&L accounts × postings). `period/close!`'s pre-checks are second.
Everything else (`run-lease!`, `run-depreciation!`,
`payment-application`) is O(periods)/O(applications) and small per
invocation. → The maintainer's *warm-the-query-cache-before-the-
`:db.fn/call`* optimization matters mainly for `closing`; and
`closing` is also the natural **long-process escape** candidate
(rare, year-end, serial-by-nature).

## Read-pattern inventory

datahike's query-result cache captures attr-deps only for `d/q`
(note 45). Across the *transactors*, the non-`d/q` reads are
overwhelmingly **small known-key `d/pull`s** — trivially
re-expressible as `d/q`, hence cache-eligible + cache-warmable. The
maintainer's hypothesis holds for the process targets. The
genuinely-irreducible `index-range` / tuple-attr primitives all live
in **`authz`, which has no transactors** — they never enter a
`:db.fn/call` and never need cache-warming. Note 45's "hard case"
simply does not intersect `kontor.process` v1. (REPL-verify the
re-expressible pulls when migrating each transactor.)

## The `commence!` proof-of-concept

`commence!` today: 5 + 2N `d/transact`s, and commits the ROU `:asset`
purely to re-read its eid (the tempid-vs-eid artifact). As a process:
3 leaf splits (`acquire!`/`open-liability-book!`/`open-book!` →
`*-tx-data`), then 4 steps — `recognize-rou-asset` (tempid
`"rou-asset"` threads — *the re-read disappears*) → `open-books`
per-ledger → `post-recognition` per-book (`plan-lease-recognition` is
*already* a pure builder) → `link-and-activate`. Hits only Problem A
(3 bounded leaf splits) + E (`strip-then-wrap` `with-vt`). The
`period/assert-not-in-locked-period!` hand-call is *deleted* — it's
in the gate. Genuinely Level 0.

## v1 plan + migration order

**(0)** Ship `src/kontor/process.clj` — `run-process` + `run-steps`
(the monadic interpreter) + `:dry-run?` (assemble via `d/db-with`
against `(d/db conn)`, don't commit) + the strip-then-wrap `with-vt`
discipline + the folded-in validation. Designed **datahike-level
pure** (deps: `datahike.api` + a parameterizable tx-meta util) so it
could later move upstream to datahike.
**(1)** Extract the ~10 pure `*-tx-data` builders from the leaf
transactors — one coherent commit, *before* any orchestrator.
**(2)** Migrate orchestrators, deleting the mitigations as you go
(`:fired-before-violation`, `assert-modifiable!`, the lockstep guard,
`offset-tempids`, the `commence!` re-read).

Order: **`commence!`** (the PoC — worst offender, 3 leaf splits, no
problems beyond A+E) → **`run-depreciation!`** (deletes
`:fired-before-violation`; needs the `record-occurrence!` leaf split)
→ **`run-lease!`** (deletes the lockstep guard) → **the four
`modification.clj` transactors** (delete `assert-modifiable!`) →
**`allocate-fifo!`** → **inventory `issue!`/`complete-transfer!`/
`count`** → **`close-fiscal-year!`** (consider leaving `close-period!`
as a long-process escape — the one heavy in-process read) → the
already-atomic transactors last (uniformity / the gate, no
correctness pressure).

Key files: new `src/kontor/process.clj`; composes with
`src/kontor/validation.clj` (the gate) + `src/kontor/bitemporal.clj`
(`strip-tx-meta`/`with-vt`). First leaves to split:
`modules/asset/src/kontor/asset/{asset,depreciation}.clj`,
`modules/lease/src/kontor/lease/liability.clj`,
`src/kontor/schedule.clj`. Reference "already-right" pure builders:
`modules/{lease,asset}/src/kontor/.../posting.clj`,
`src/kontor/posting.clj`.

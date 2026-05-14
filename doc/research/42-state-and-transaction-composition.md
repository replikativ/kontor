# 42 — How state + transactions work in kontor; the composition problem

**Date:** 2026-05-14
**Method:** one synthesizing agent — a deep file:line read of the
status-machine substrate, the transaction lifecycle, every
multi-step transactor across the companions, and the actual datahike
`with`/`branch` API (verified against the local datahike checkout).
**This is the research-before for a future `kontor.process` stage** —
the maintainer chose to finish kontor-authz first, then do the
transaction/composition rework as its own stage; this note captures
the analysis so it does not rot.

## 1. State is modelled three inconsistent ways

| Mechanism | Used for | Audit history? |
|---|---|---|
| **ADR-034 status machine** (`:status-transition` edge table + `:status-history` + `record-status-change-tx-data` + ADR-038 `:approval-policy`) | companion `:*/status` facets — `:invoice/status`, `:asset/status`, `:lease/status`, `:expense-report/status`, collections, … (~half the state-bearing entities) | yes — `:status-history` |
| **`kontor.state-machine`** — a *separate*, hardcoded Clojure `allowed-transitions` map, enforced as inbound-tx-data middleware | `:transaction/state` (draft → posted → cancelled, + `:pending-attestation`) | no |
| **ad-hoc keyword attrs** flipped by bespoke transactors with inline `when`-guards | `:schedule/state`, `:period/locked-at` / `:sealed-at`, `:inventory-transfer/status`, `:negative-fill/status`, `:side-effect-intent/status` | **no** — only datahike raw tx-time |

ADR-034 itself parked the "two state machines side by side, one
eventually wins" reconciliation back at Stage J — still open. The
ad-hoc tail gets no `reason` / `changed-by-uid` / valid-time, which
ADR-034 argues is exactly the wrong thing. Realized multi-facet
pattern is multi-*entity* (a lease's "state" is spread across
`:lease/status` + N `:lease-liability` schedule states + N ROU
`:asset-depreciation` schedule states), not multi-facet-single-entity.

## 2. The transaction lifecycle + the enforcement seam

`:transaction/state` and sealing (ADR-007), period locks (ADR-014),
and the datalog invariants are all composed in
`kontor.validation/validate-and-apply` — but **only fire if the
caller routes through `transact-with-validation` / the `:tx-wrap`.**
The companion runners call `d/transact` **directly**. They get
sealing + the transaction state machine + invariants *only* when they
go through `kontor.posting/post-transaction!` — and most don't; they
build tx-data and transact it raw. The one cross-cutting guard the
runners *do* call by hand, consistently, is
`period/assert-not-in-locked-period!`. So: period-locking is enforced
by convention; sealing + the tx state machine are enforced
*structurally* (postings are built `:posted` from birth, never
retracted) rather than by the gate.

`build-transaction` and every companion `plan-*` fn are **pure**
(`db → tx-data`, `with-vt`-stamped). The purity boundary is sharp —
and drawn at the wrong place for atomicity (see §3).

## 3. The composition problem — the core finding

The pure planning layer is clean; **the runners break it by
transacting per step.** Three idioms coexist with no unifying model:

1. **compose-into-one-vector** — kernel `post-transaction!`, invoice
   `post-to-ledger!`, expense `post-report!`, `record-status-change-
   tx-data`. Genuinely atomic. *The good pattern.*
2. **transact-per-step + a partial-progress carry** — asset
   `run-depreciation!` (N+1 transacts), lease `run-lease!`. Non-atomic;
   `:fired-before-violation` is the band-aid.
3. **transact-per-step + bespoke pre-flight probes / divergence
   detectors** — lease `commence!` (~3+2N transacts), the four
   `modification.clj` transactors (2+~3N; `assert-modifiable!` is an
   explicit up-front mitigation), `run-lease!`'s lockstep-divergence
   guard. Non-atomic; hand-written compensating checks.

**Every mitigation in idioms 2 and 3 — `:fired-before-violation`,
`assert-modifiable!`, the lockstep guard — is a symptom of the same
missing abstraction: non-atomic multi-step commits.** The runners
re-enter the connection between plans because a step's input is "the
db as it would be after the previous step" — but that is a dependency
on *speculative* state, not *committed* state. `commence!` even
commits the ROU `:asset` purely to re-read its real eid
(`runner.clj:191`) — a tempid-vs-eid artifact, not a real dependency.

**Rule of thumb for what *should* be atomic:** if the intermediate
state corresponds to something true in the world (in-transit
inventory, `:pending-attestation` awaiting a government callback)
it's a *legitimate* multi-tx process — model it with a status facet +
ADR-041 `:side-effect-intent` rows. If the intermediate state is only
an artifact of "I had to commit to read it back," it should be one
`db-with`-threaded tx. Every "non-atomic" runner above is the latter.

## 4. datahike capabilities (verified against the local checkout)

- **`transact` is synchronous** — `@(transact! conn …)`, derefs the
  writer future, returns the `TxReport`. One writer; writes
  serialized. This *matches* accounting (an inherently serialized
  single-writer domain) — synchrony was never the problem; calling
  `transact` N times instead of once was.
- **`d/with` / `d/db-with` exist** — `[db tx-data] → TxReport` /
  `→ db`, against an immutable db *value*, running the **full** tx
  engine (tempids, schema, uniqueness, refs) purely in memory, no
  commit. Spec'd referentially-transparent. *Exactly* the
  speculative-threading primitive.
- **git-like branches exist** — `branch!`, `merge-db!`,
  `branch-as-db`, etc. Durable, writer-routed — heavier than `with`.
  The tool for *long-lived draft workflows*, not within-call
  atomicity.
- An experimental upstream `datahike.optimistic` namespace already
  layers pending tx-data over a db via `db-with` — the threading
  pattern is considered idiomatic.

## 5. Recommendation — a `kontor.process` abstraction

A process is a sequence of **pure steps** `(speculative-db,
accumulated-tx-data, ctx) → tx-data-fragment`, threaded through
`d/db-with` so step k sees the db as if steps 0..k-1 had committed,
accumulating all fragments, committed **once** through
`transact-with-validation`:

```clojure
(defn run-process [conn ctx steps]
  (let [{:keys [tx-data]}
        (reduce (fn [{:keys [db tx-data]} step]
                  (let [frag (step db tx-data ctx)]
                    {:db (d/db-with db frag) :tx-data (into tx-data frag)}))
                {:db (d/db conn) :tx-data []}
                steps)]
    (validation/transact-with-validation conn tx-data)))
```

Plain step fns + a `run-process` helper first; a `defprocess` macro
only if named/replayable steps earn it. **Buys:** atomicity (every
non-atomic runner becomes all-or-nothing — `:fired-before-violation`,
`assert-modifiable!`, the lockstep guard all *delete themselves*);
the tempid-vs-eid re-read in `commence!` disappears (tempids thread
naturally within one vector); composability (a step list can embed a
sub-process's step list); testability (a `:dry-run?` mode yields the
assembled tx-data + final speculative db with zero connection
mutation); one enforcement path (companions finally get sealing +
invariants). **Costs:** per-period runners assemble one large tx-data
vector — more correct (no half-run close) but needs a `:batch-size`
knob for catch-up backfills; `d/db-with` re-runs the tx engine per
step (cheap, but measure on the lease lockstep case); steps must be
genuinely pure — the `d/pull` reads scattered through today's runners
move *inside* steps, reading the speculative db (mechanical
migration). **Branches are not for this** — reach for them only if a
consumer wants a real cross-session staging area.

**Suggested sequencing for the future stage:** (1) `kontor.process`
with `run-process` + `:dry-run?`; (2) migrate `commence!` first — the
worst offender, biggest visible win; (3) the other runners + the four
`modification.clj` transactors, deleting the mitigations as you go;
(4) separately reconcile `kontor.state-machine` + the ad-hoc status
attrs into the ADR-034 machine (or document each exception); (5) the
ADR. This is exactly the kind of locked design choice the per-stage
rhythm exists for.

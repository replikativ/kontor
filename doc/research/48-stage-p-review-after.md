# Stage P / ADR-067 + ADR-068 — review-after

Code-review pass on commits `627640c..7aeeb99` covering `kontor.process`,
the `*-tx-data` sweep, the orchestrator migrations, and the
invariant-library patch.

## Bottom line

**Fix-P0-first, then ship.** `kontor.process` is sound, the
composability addendum (`:tempid`, `:tempid-suffix`, `:asset-tempid`,
`:tx-tempid`) is precise, and the headline migrations (`commence!`,
the four `modification.clj` transactors, the per-period
`run-depreciation!` / `run-lease!`, `allocate-fifo!`, inventory
`issue!`'s negative-fill merger) are correctly atomic and gate-routed.
`composition_test.clj` convincingly proves the cross-module story.

The ADR-068 promise — *every* `defn xxx!` doing `d/transact` routes
through the gate — is **not** met. ~13 business-write `!` wrappers
across kernel + companions still call raw `d/transact`, including the
cluster the invoice bridges depend on (`record-status-change!`,
`apply-payment!`, `apply-payment!`, schedule lifecycle, side-effect
intent transitions, authz writes, the asset / lease book opener +
reviser). The Stage P commit message asserts universal gate routing;
the code does not. `apply-payment!` in particular is on the hot path
and quietly creates a gap that the parallel `reverse-application!`
does not have.

## P0 — ship-blockers

### P0-1. `apply-payment!` bypasses the gate
`src/kontor/payment_application.clj:229` — `(d/transact conn final-tx)`.

Sibling `reverse-application!` (`:338`) correctly uses
`validation/transact-with-validation`. The asymmetry skips sealing /
period / state-machine / sum-to-zero / datalog invariants on a write
that drives `:invoice/status :sent → :partially-paid → :paid`. The
state-machine validator inside the gate is the one place that catches
an illegal facet transition; with the bypass a buggy caller silently
writes an illegal transition.

Fix: route through the gate. `payment_application.clj` already
requires `kontor.validation`, no cycle.

### P0-2. `record-status-change!` and `bulk-record-status-change!` bypass the gate
`src/kontor/status_machine.clj:335, :355`.

`record-status-change!` is the canonical convenience transactor for
every `:status-history` write. `invoice.bridge/make-ready!`,
`/mark-paid!`, `/cancel!` (`bridge.clj:115, :135, :149`) call it
directly, so the three invoice lifecycle transitions skip the gate.
ADR-068's "scope of business write" preserves
`record-status-change-tx-data` as a bare builder but does NOT exempt
the `!` wrapper. Static require of `kontor.validation` is safe here.

### P0-3. `asset/depreciation/open-book!` and `revise-book!` bypass the gate
`modules/asset/src/kontor/asset/depreciation.clj:332, :403`.

Both `*-tx-data` builders exist; the wrappers do raw `d/transact`. The
asset runner's per-period commits already route through the gate (via
`run-process`), so the inconsistency is only at the standalone-write
entry points. One-line fix each.

### P0-4. `lease/liability/open-liability-book!` and `revise-liability-book!` bypass the gate
`modules/lease/src/kontor/lease/liability.clj:258, :319`. Same shape
as P0-3. The lease modification transactors invoke the builders inside
their `run-process`, so the gate covers the modification path; a
standalone caller using these `!` wrappers directly bypasses it.

### P0-5. Stage P commit message overstates the migration
`git show 7aeeb99`: "Every `!` wrapper routes through the gate
(transact-with-validation) for uniform sealing / period / sum-to-zero
/ state-machine / invariant enforcement."

Provably false given P0-1..P0-4 plus the P1 gaps below. `bb test` is
green only because no test exercises the gate-reject path for these
specific writes. Either make the message true by closing P0-1..P0-4
and P1-2..P1-5, or demote the message to "migrated the orchestrators;
the standalone-wrapper gate bypasses are tracked in note 48".

## P1 — subtle bugs / missing cases

### P1-1. Inventory `issue!` relies on speculative-db eids leaking into commit-time refs
`modules/inventory/src/kontor/inventory/ops.clj:194-197` (docs),
`:341-385` (the reads).

The negative-fill step emits a `:valuation-layer` at string tempid
`"nf-layer"`. The next step calls `(posting/plan-stock-move sdb …)`
which calls `(valuation/available-layers sdb …)` — returning the
layer's *speculative-db numeric eid* — and emits a
`:layer-consumption` with `:layer-consumption/layer <numeric eid>`.

ADR-067 §"The cross-step identity rule" explicitly warns: "Reference
cross-step entities by **string tempid**, never by an eid read off
the speculative db." The implementation does the forbidden thing
intentionally, justifying it by datahike's "max-eid + 1 in encounter
order" allocator (note 47).

The reliance is undocumented in datahike — a single parallelisation
in `db-with` breaks the inventory subsystem. The claim is not pinned
by a test.

Fix sketch: refactor `plan-stock-move` to return layer references as
tagged opaque values so the consumption can carry a string tempid; OR
(minimum) add a regression test that exercises `d/db-with`-then-
`d/transact` and asserts `speculative-eid == (:tempids report) eid`
for an entity created earlier in the same tx-data. The test fails
loudly on any datahike upgrade that changes the allocator.

### P1-2. `record-occurrence!`, schedule lifecycle, side-effect intent transitions bypass the gate
- `src/kontor/schedule.clj:207, :228, :233, :239` —
  `record-occurrence!`, `mark-completed!`, `mark-paused!`,
  `mark-cancelled!`
- `src/kontor/side_effect.clj:75, :83, :99, :110` — `claim!`,
  `mark-done!`, `mark-failed!`, `mark-abandoned!`

`record-occurrence!` is technically dead code post-Stage-P (every
production caller now uses `record-occurrence-tx-data` inside
`run-process`), but it's public API. The state-machine validator in
the gate would catch a malformed `:schedule/state` /
`:side-effect-intent/status` transition; today these are unenforced.

### P1-3. Inventory `record-detail!`, `legal-hold/refresh-scope-eids!`, `authz/do-write-relationships!` bypass the gate
- `modules/inventory/src/kontor/inventory/core.clj:287` — low-level
  quantity-ledger writer; a backdated detail whose
  `:effective-date` falls in a locked period would silently land.
- `src/kontor/legal_hold.clj:591` — `refresh-scope-eids!` (the
  sweeper expanding `:scope-eids`); business write, should be gated.
- `modules/authz/src/kontor/authz/client.clj:155-159` —
  `do-write-relationships!`. The Stage P commit body specifically
  calls this out as "the headline cross-module composability win",
  but only the *builder* was extracted; the standalone wrapper still
  does `d/transact`.

### P1-4. `invariant.datahike` patch only covers `:db/retractEntity`
`../datopia/invariant/src/invariant/datahike.clj:47-52` (commit
`90a2f9f`) adds the `:db/retractEntity` dispatch but not
`:db.fn/retractEntity`, `:db/purge`, `:db.purge/entity`,
`:db.purge/attribute`, or `:db.fn/cas`. Today the kernel's
`retention/expire!` (`retention.clj:361-362`) deliberately runs
`validate-and-apply` *outside* `transact-with-validation` (and
therefore outside `assert-invariants`), so the missing dispatches
don't crash. The moment a consumer composes a `:db/purge` into a
`run-process` step (legitimate — e.g. purge a negative-fill layer
after true-up), `assert-invariants` crashes with `No method in
multimethod 'get-attribute'`. `legal-hold.clj:317-326` already
enumerates the full destructive op set; extend the multimethod with
the same set returning nil.

### P1-5. `posting/build-transaction` violates ADR-068's "no `with-vt` in builders" rule
`src/kontor/posting.clj:371`. ADR-068 §"The `with-vt` discipline":
"The builder DOES NOT embed `with-vt`." `build-transaction` IS a
builder per ADR-068's exempt list, and it embeds `with-vt`.

The implementation is *correct* — `run-process`'s `strip-tx-meta`
removes the embedded tx-meta and the process's outer `with-vt` wins
(`process_test.clj:136-149`). But ADR-068 text and code disagree.
P1 for the ADR text, not the code: update ADR-068 to acknowledge
`build-transaction` as the documented exception (the default
`:transaction/effective-date → :tx/valid-from` mapping is hard-
coded inside the builder and would be onerous to lift out).

## P2 — followups / documentation

- **`inventory issue!` passes `:vt-to nil`** at
  `modules/inventory/src/kontor/inventory/ops.clj:391` while all
  other ops in the file pass `(or vt-to kbt/forever)`. Benign today;
  fix to match.
- **`post-to-ledger!` has a redundant `assert-not-in-locked-period!`
  outside the gate** (`modules/invoice/src/kontor/invoice/posting.clj:340`).
  The gate already runs this validator. Leftover; delete.
- **`period/close!` still does two transactions** (`src/kontor/period.clj:365-370`);
  the second `d/transact` for `:period/lock-tx` is unguarded. Documented
  as a datahike limitation. P2: document the "locked but lock-tx
  absent" state if it ever fails between the two.
- **`close-fiscal-year!` is not a `run-process`** (`src/kontor/closing.clj:217-233`);
  ADR-067 listed it for migration. A failure between the two transacts
  leaves the closing tx posted but the period unlocked. Blocker is the
  same as the `:period/lock-tx` denorm.
- **Injected-instant key proliferation**: `:changed-at` /
  `:received-state-at` / `:placed-at` / `:recorded-at` / `:applied-at`
  / `:posted-at` across the builders. A uniform `:builder/at` would be
  ergonomic for composers.
- **`requiring-resolve` thread-safety is per-call** for
  `legal_hold.clj:56-59` / `period.clj:40-43` / `posting.clj:36-39`.
  Safe (Clojure's load semantics), but each call is a hash-map lookup.
  Trivially optimisable with a `(delay ...)` cache if a benchmark
  ever flags it.

## What's solid

- **`kontor.process` is small, correct, and well-tested.** 138 lines;
  the test file covers speculative-db threading, monadic flatten,
  atomic-abort, strip-tx-meta + one-outer-`with-vt`, cross-step
  string tempids, dry-run, and overridable commit fn. The
  serialise-on-conn lock is the right structural primitive for note
  45's read-set-tracking-not-yet-available reality.
- **The orchestrator migrations are correct.** All four
  `modification.clj` transactors compose adjustment GL + re-anchor +
  status change in one gated commit. `commence!`'s ROU-asset-by-
  string-tempid threading through the dep-book step is the textbook
  ADR-067 pattern. `allocate-fifo!`'s `:tempid-suffix` avoidance of
  the N-applications collision is exactly right.
  `run-depreciation!` / `run-lease!` preserve per-period vt-from
  while still atomically gating each period.
- **The composition test convincingly proves the cross-module story.**
  `composition_test.clj` spans `kontor.audit-doc` and
  `kontor.legal-hold` in one `run-process` (the hold's
  `:supporting-doc` resolves to a doc the same process creates by
  string tempid), demonstrates atomic abort on gate violation, and
  shows the cheap `vec-concat` + `transact-with-validation` path
  for callers who don't need `run-process`'s serialisation.
- **The cycle resolution via `requiring-resolve` is the right call.**
  Alternative restructurings would either complicate validator
  registration or couple the gate to its plugins. The lazy resolver
  is two lines, thread-safe by Clojure's load semantics, cleanly
  named.
- **`build-transaction`'s `:tx-tempid` knob is precise.** Posting
  tempids derived as `"<tx-tempid>-pN"` keep the original `-100-i`
  semantics when `tx-tempid` is the default `-1`; the explicit
  string-tx-tempid is what unlocks `commence!`'s per-ledger
  recognition entries and the lease modifications' per-book
  adjustment entries.
- **The invariant patch's design choice is sound.** A
  `:db/retractEntity` asserts no attribute values; returning nil and
  letting `spread-attrs` spread to no attrs is the cleanest fix in
  the multimethod model. Needs P1-4 extension to the rest of the
  destructive ops.

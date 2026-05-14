# 40 — `kontor-lease` review-after (ADR-062 / 063 / 064)

**Date:** 2026-05-14
**Method:** the ADR-037 per-stage rhythm step 3 — two independent
background agents run in parallel against the committed module:

1. **Code-review agent** — a rigorous file:line audit of the eight
   `kontor.lease.*` namespaces + the ADR-063 `kontor-asset` touch +
   the three test files, against the GL-balance / unwind-to-zero /
   re-anchor / lockstep / multi-book / datahike-trap invariants. The
   agent ran its own REPL probes to confirm findings.
2. **Market-pain review agent** — audited the *implemented* module
   against research notes 38 (reference) + 39 (market-pain): which
   customer pains are addressed, partially addressed, explicitly
   deferred, or missed.

## Verdict

**No P0 ship-blockers.** The code-review agent confirmed by REPL
probe that the GL balances, the liability unwind lands exactly on
zero, the operating-lease ROU plug sums to the depreciable base,
multi-book parallel ledgers with *different* discount rates each
balance independently, and modifications (including `terminate!`
after a `remeasure!`) conserve money. All the datahike traps the
team has hit before — pull-without-`:db/id`, boxed-`count`,
`sum`-without-`:with`, nil-tuple identity — are avoided. The
market-pain agent's verdict: the module gets the *hardest* pains
right (the modification machinery, the IFRS-vs-US-GAAP dual-reporting
burden via per-`(lease, ledger)` classification) and is genuinely
thin, as the ADRs intended.

The findings were all P1/P2 (code-review) or deferred-scope
(market-pain). The P1s — a real period-discipline gap, a boundary-
validation gap, and a load-bearing-but-unenforced invariant — were
fixed in the review-fix commit. The market-pain gaps are genuine
deferred scope (the ADRs already document several deliberate v1
simplifications); they are triaged into followup tasks below, not
silently dropped.

## Code-review findings — fixed in the review-fix commit

- **P1 — period-lock not enforced on modifications or `commence!`.**
  `run-lease!` correctly refused to fire a payment into a soft-closed
  / sealed period, but `remeasure!` / `partial-terminate!` /
  `terminate!` / `purchase!` and `commence!`'s day-one recognition
  entry all transacted their *sealed* GL entries with no
  `kontor.period` check — confirmed by probe (a `remeasure!` dated
  into a closed period posted silently). **Fix:** a `transact-checked!`
  helper wraps every modification GL posting; `commence!` checks
  inline; and — because `record-modification!` mutates the `:lease`
  contract facts *before* the per-book GL loop — an `assert-modifiable!`
  pre-flight gate now refuses the whole modification up-front, so a
  period-locked `remeasure!` can no longer leave an orphaned
  contract-fact change with no GL adjustment behind it.

- **P1 — `:term-months` not validated positive.** A `0` / negative
  term sailed through `define-lease!` and blew up deep inside
  `schedule/date-of-occurrence` with an opaque `"sequence must be
  1-indexed positive"`. **Fix:** `define-lease!` now validates
  `:term-months` is a positive integer, `:payment-amount` is
  positive, and `:discount-rate` is non-negative — the error names
  the real problem at the boundary.

- **P1 — the liability ↔ ROU lockstep invariant was load-bearing but
  unenforced.** `rou-provider/plan-schedule` throws if the liability
  plan does not cover every un-fired ROU period — which holds only
  while the two schedules are fired together. A prior partial failure
  (e.g. the liability fires, then `run-depreciation!` throws on a
  period lock) could desync them. **Fix:** `run-lease!` now asserts
  the two fired-counts match *before* it runs, throwing a clear
  `:lease/lockstep-divergence` instead of a cryptic
  `…-misaligned` later; `rou-provider/provider`'s docstring is
  louder about preferring `run-lease!` over a direct
  `run-depreciation!` call.

## Code-review findings — P2, fixed where cheap

- The ROU `:asset/acquisition-cost` is the *first* book's ROU cost,
  which diverges from a non-primary book's `:depreciable-base` when
  discount rates differ. Nothing inside kontor-lease reads it (the
  providers + the modification snapshot all read the per-book
  `:depreciable-base`), but `kontor.asset.posting/plan-disposal` /
  `net-book-value` default to it — **fixed by documentation:** the
  `commence!` source now warns that a consumer disposing a
  non-primary ROU book must pass an explicit `:asset-account-cost`.
- `EffectiveInterestProvider`'s `:straight-line-expense` is a
  commencement-only figure (`rou-provider` re-levels it itself
  post-modification and no longer reads it) — **fixed:** the docstring
  now says so explicitly.
- `(count …)` for `ofthr` in `modification.clj` was not `long`-coerced
  (harmless today — only used in arithmetic — but inconsistent with
  the deliberate coercion in `revise-liability-book!`) — **fixed.**
- `run-lease!` silently no-op'd the ROU leg when the ROU asset / book
  was missing — **fixed:** it now throws `:lease/not-commenced` /
  `:lease/no-rou-dep-book`.
- **Test coverage gap** — no test exercised a modification on an
  *operating* lease (the ROU-plug re-anchor path), a `:new-term-months`
  change, or a modification into an already-re-anchored book.
  **Fixed:** four new tests added — `remeasure-on-an-operating-lease-
  still-unwinds`, `remeasure-with-a-term-extension-reschedules-and-
  unwinds`, `terminate-after-a-remeasure-balances`, and
  `modifications-refuse-to-post-into-a-locked-period`.

## Market-pain findings — triaged to followups (genuine deferred scope)

The ADRs already document several deliberate v1 simplifications;
these are not failures, but they leave real-world workflows
incomplete and are worth tracking. None is a correctness bug.

1. **Mid-life import / `catch-up!` path** *(highest real-world
   severity)*. There is no transactor to onboard an existing lease
   portfolio at transition-date carrying amounts — `commence!` always
   computes PV from scratch and asserts `:opening-fired-through 0`.
   The schema field (`:lease-liability/opening-fired-through`) and the
   provider's event-awareness already support it; what's missing is
   an `import-lease!` transactor. For *any* adopting customer this is
   the first task. → followup.

2. **Persist the remeasurement deltas on `:lease-modification`.** The
   transactors already *compute* the liability / ROU / P&L deltas;
   persisting them (`:lease-modification/liability-delta` etc., as the
   note-38 schema sketch had) would make the IFRS/US-GAAP lease-
   liability roll-forward a trivial read instead of a multi-source GL
   join. The data *is* derivable today (from the
   `:lease-modification/transaction` refs + the GL), so this is a
   convenience, not a correctness gap — but it is cheap and high
   value. → followup.

3. **Per-`(lease, ledger)` index-reset fork.** Under ASC 842 an
   index-linked payment change is *period expense*; under IFRS 16 it
   is a full remeasurement. `remeasure!` currently loops over all
   books identically. → followup.

4. **Discount-rate audit trail.** The rate is a first-class scalar
   and re-discountable, but the commencement rate has no mandatory
   `:audit-doc` justification ref and is not effective-dated (only
   bitemporally recoverable). → followup.

5. **Smaller items** — stepped-rent / rent-free cash-flow profiles
   (`:lease/payment-amount` is a single scalar); the
   modification-vs-separate-lease judgment call is undocumented; ASC
   842's *second* partial-termination method (16.46(a)) is not
   offered; FX retranslation has a builder but no transactor.
   → rolled into the followup task as a checklist.

## Outcome

`bb test` after the review-fix commit: **909 tests, 3322 assertions,
0 failures** (the lease suite grew 17 → 21 tests / 103 → 125
assertions). cljfmt + clj-kondo clean. The kontor-lease companion
(ADR-062..064) is complete; the market-pain deferred-scope items are
captured as followup tasks and as ADR-064 addendum notes.

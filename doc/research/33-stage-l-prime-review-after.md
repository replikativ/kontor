# Stage L′ (`kontor-asset`) review-after — code review + market-pain review

Date: 2026-05-14. Two independent review-after agents ran against the
committed Stage L′ code (ADR-053 register/lifecycle, ADR-054
depreciation books, ADR-055 `DepreciationProvider` + runner, ADR-056
Jahresabschluss). This note records what they found, what was fixed
in the review-fix commit, and what was triaged into follow-ups.

## Agent 1 — independent code review

Scope: ADR-053..056 + the asset module + the two kernel touches
(`report.clj`, `financial_statements.clj`). Verdict: spine sound,
**one P0**.

- **P0-1 — `DecliningBalanceProvider` ignored `:asset-depreciation/
  depreciable-base`.** It threaded book value from
  `:asset/acquisition-cost`, so a tax book with a bonus-reduced base
  silently over-depreciated (REPL-confirmed: `:depreciable-base
  70000` on a `100000` asset → DB plan `:total 100000`). SL and SYD
  honoured the base; DB did not. **Fixed** — `assemble` now takes an
  explicit `starting-book-value` = `depreciable-base + salvage-value`;
  all three providers thread from it; DB's `floor = book-value −
  salvage` math still lands on salvage, and `Σ = depreciable-base`
  by construction.
- **P1-2 — `:basis-remaining` threaded from `:acquisition-cost`.**
  Wrong carried-forward figure for non-default-base books. **Fixed**
  by the same `assemble` restructure.
- **P1-3 — runner depreciated past a disposal.** It fired every
  pending occurrence through `:as-of` with no awareness of an
  `:asset-event :disposal`/`:transfer`. **Fixed** — the runner now
  stops at the earliest terminal event; `:disposal-date` is returned
  when this truncates the run.
- **P1-1 — declining-balance `:ceiling-rate` time-base.** Flagged as
  a possible per-period-vs-annual mismatch. **Investigated — false
  positive.** `sl-rate = 1/n-periods` is per-period; `db-rate =
  multiple × sl-rate` is per-period; the annual `:ceiling-rate` is
  converted to a per-period cap by `÷ periods-per-year` — all three
  on the same base. REPL-confirmed at the boundary case. A
  `declining-balance-ceiling-rate-caps-the-rate` test was added to
  lock the behaviour; a clarifying comment was added.
- **P1-4 — two NBV definitions.** `net-book-value` used the asset's
  `:acquisition-cost`; `plan-disposal` required a caller-supplied
  `:asset-account-cost`. **Fixed** — `:asset-account-cost` is now
  optional, defaulting to the asset's `:acquisition-cost` (the
  whole-asset case); a partial disposal still overrides it.
- **P2-1 — `volatile!` in the DB provider.** The switch-to-SL flag
  rode a mutable cell, relying on `assemble` calling in ascending
  order. **Fixed** — `assemble` now threads an opaque `state` value
  through `unfired-amount`; the flag is pure accumulator state.
- **P2-2 — nil `:commodity` could propagate** from `open-book!` into
  the occurrence log. **Fixed** — `open-book!` now throws if no
  commodity is resolvable.
- **Kernel touches — clean.** `report.clj`'s `:ledger` filter and
  `financial_statements.clj`'s additions stay minimal, no new
  dependency, no companion logic leaked in; `ledger-filter-pred`
  handles eid + lookup-ref; the datahike aggregate-set-collapse trap
  is correctly handled. No kernel-contract violation.

## Agent 2 — market-pain review

Scope: the implementation against real fixed-asset / depreciation /
year-end-close customer pain (cross-checked against research notes
28-31). Verdict: research-before tracked faithfully, architecture
calls right, gaps in the mid-life-event surface.

- **P0-1 — `:asset-event` "immutable" overclaimed.** ADRs and
  docstrings called `:asset-event` immutable, but nothing enforces
  it (sealing, ADR-007, guards `:posting` entities only). **Fixed —
  reworded.** `:asset-event` is append-only *by transactor
  convention*; wiring a companion entity into kernel sealing would
  breach the anti-accretion contract, so the honest fix is accurate
  wording, not a kernel change. Documented in `schema.clj`,
  `asset.clj`, and the ADR-053 addendum.
- **P0-2 — runner ignored period locks.** A `catch-up!` after a
  close would post sealed depreciation into a locked/sealed period.
  Research note 31 §5.3 said the runner should surface the
  violation. **Fixed** — the runner now runs
  `kontor.period/assert-not-in-locked-period!` per charge and
  surfaces `:period/locked-period-violation` with the partial
  progress (`:fired-before-violation`).
- **P1-2 — conventions silently ignored.** `:convention` was
  accepted, stored, and then ignored by every built-in (all compute
  `:full`). **Fixed** — the built-ins now throw
  `:asset/unsupported-convention` on a non-`:full` convention; exact
  proration stays l10n-provider territory, but a built-in fails loud
  instead of returning a wrong schedule.
- **P1-4 — roll-forward missed impairment / revaluation.** The
  Anlagengitter read only `:schedule-occurrence`, so an impairment
  write-down or a revaluation was invisible (HGB §284 Abs. 3 wants
  außerplanmäßige Abschreibung shown). **Fixed** —
  `asset-roll-forward` now folds `:impairment` `:asset-event`s into
  the accumulated-depreciation roll-forward and `:revaluation`
  events into the gross-cost roll-forward, with `:impairments` /
  `:revaluations` exposed as in-window memo totals; the closing
  identities still hold.
- **P1-3 — no catch-up / mid-life-import depreciation.** No way to
  onboard an asset already part-way through its life. **Fixed
  (contained)** — `:asset-depreciation/opening-accumulated` added: a
  pure reporting scalar that `accumulated-depreciation` /
  `net-book-value` add to the occurrence sum and that the
  roll-forward treats as opening accumulated; the provider never
  sees it (the caller passes the *remaining* `:depreciable-base`).
- **P1-5 — `revise-useful-life!` didn't reach the book.** It records
  the cross-book `:asset-event` but nothing applied it to a book's
  schedule. A cycle (`asset` → `depreciation` → `asset`) blocks
  auto-wiring. **Fixed — documented.** `revise-useful-life!` /
  `record-addition!` docstrings now state explicitly that the
  per-book apply step is `depreciation/revise-book!` (per-book
  because an HGB life ≠ an AfA-Tabelle life).
- **P1-1 — `:partial-disposal` enum-only.** A `:asset-event/kind`
  value with no transactor / posting builder / re-plan path.
  **Triaged — marked RESERVED.** The schema doc now lists it as a
  documented follow-up, separate from the wired kinds; "half-built
  is worse than absent," so it is honestly labelled rather than
  shipped half-done. A `partial-dispose!` (event + `revise-book!` +
  `plan-disposal`) is the follow-up.

## Deferred follow-ups (P2 / out of scope for the review-fix commit)

- `partial-dispose!` transactor (market-pain P1-1) — asset splits,
  pooled-unit retirement.
- Bulk `acquire!` import helper (market-pain P2-3) — arguably a
  consumer-app concern.
- `transfer!` paired-acquisition on the receiving entity
  (market-pain P2-4) — intercompany asset transfer; currently the
  receiving side is caller-assembled.
- A cross-asset MACRS mid-quarter example test (market-pain P2-1) —
  the protocol's `db` param is the seam; an l10n implementer would
  benefit from a worked example.
- A GL-reconciliation check on `asset-roll-forward` mirroring
  `compute-cash-flow`'s `:reconcile-codes` (code-review P1-4 flip
  side) — the subledger-to-GL tie-out.

## Outcome

All P0s and every correctness-trap P1 fixed in the review-fix commit
on top of `fb3463b`. Full suite green afterwards. The remaining
items are genuine feature gaps (not bugs) with the existing seams
sufficient to absorb them — captured here so a later stage or an
l10n module can pick them up.

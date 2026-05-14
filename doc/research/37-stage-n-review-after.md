# Stage N (`kontor-inventory`) review-after — code review + market-pain review

Date: 2026-05-14. Two independent review-after agents ran against the
committed Stage N code (ADR-057 facilities + the physical stock
ledger, ADR-058 available-to-promise + reservations, ADR-059 receive
/ issue / transfer + GL, ADR-060 cycle counts + reconciliation +
FEFO). This note records what they found, what was fixed in the
review-fix commit, and what was triaged into follow-ups.

## Agent 1 — independent code review

Verdict: the signed-delta `:qoh-diff`/`:atp-diff` model is sound and
composes correctly; the two kernel touches (`:lot/expires-at`,
`available-layers` `:order-by`) are minimal and clean. **No P0
ship-blockers.** Three P1s, fixed:

- **P1-1 — `valuation-tie-out` silently ignored `:as-of-tx` on the
  subledger side.** `account-balance` honours `:as-of-tx`;
  `valuation/on-hand-value` does not — so a `:as-of-tx` call compared
  a *current* subledger to a *historical* GL, a spurious
  `:difference` undermining the detective control. **Fixed** —
  `valuation-tie-out` now snapshots the db with `(d/as-of …)` before
  the subledger reduce, so the tx-time axis applies to both sides.
- **P1-2 — `receive!` / `issue!` could leave an orphan
  `:inventory-item` bucket** on a non-underflow `plan-stock-move`
  failure: the bucket was find-or-created (committed) *before*
  `plan-stock-move` ran. **Fixed** — both now plan the move FIRST
  (it is pure, throws before anything is committed), then build the
  bucket entity INLINE in the atomic transaction (a tempid);
  `create-negative-fill!` likewise creates the bucket in its own tx
  when it does not exist. `resolve-issue-bucket` is now resolve-only.
- **P1-3 — `post-count!` could leave a count half-posted.** Each
  variance line is its own transaction, and the mid-reduce
  `:found-unit-cost` check fired *after* earlier lines had already
  posted. **Fixed** — a pre-flight pass validates `:found-unit-cost`
  against ALL current lines before posting ANY, and `post-count!` is
  now idempotent: a variance line whose `:source`-linked
  `:inventory-detail` already exists is skipped, so a partial run is
  safely re-runnable.
- **P2s — fixed where cheap:** `:effective-date` opts on
  `cancel-transfer!` / `release-reservation!` (a backdated
  compensation can now be expressed); the `true-up-negative-fill!`
  docstring clarified (the `:layer-adjustment` is an audit marker —
  the layer is already consumed, the GL correction posting does the
  work). `seal-stock-move`'s shape-based discrimination and
  `find-inventory-item`'s O(n) pull are noted but left — they work,
  and the materialised-snapshot perf question is already a deferred
  ADR (research note 36 §10).

## Agent 2 — market-pain review

Verdict: Stage N delivered all four headline design calls
(negative-inventory policy, valuation tie-out, ATP, multi-location)
as real working code — the negative-fill + true-up in particular is
fully implemented, not a stub. The honest gaps are at the P1/P2
edges, and concern the *honesty of the deferral lists* more than the
code:

- **Serialized items (P1) — the schema over-advertised.**
  `:inventory-item/kind :serialized` existed as a value, but
  `reserve!` skips `:serialized` buckets and `issue!` has no
  serialized special-casing. **Fixed (honesty)** — `:inventory-item/
  kind`'s schema doc now marks `:serialized` **RESERVED** (the
  serial-as-qty-1-lot path is a documented follow-up; use
  `:non-serial` until it ships) — the same correction Stage L′ made
  for `:partial-disposal`.
- **Returns / RMA (P1) — absent from the deferral list.** A return
  is mechanically a `receive!`, but the *hard* part (what unit cost?
  the link back to the original `:layer-consumption`?) has no path,
  and ADR-059's deferral list did not name it. **Fixed (honesty)** —
  added to the ADR-059 deferred-follow-ups list with the
  return-costing-default note.
- **`:account/system-managed?` (P1 for a consumer, P2 for the
  kernel) — deferred without an ADR note.** Research note 36 §2
  asked for a flag so only `plan-stock-move` may touch the inventory
  account (the *preventive* control; `valuation-tie-out` is only
  *detective*). Stage N did not ship it. **Fixed (honesty)** — an
  explicit "deferred, here's why" note added to ADR-059: kontor
  ships no UI to fat-finger a raw JE, so it is a consumer-app-layer
  concern; the flag + middleware enforcement is a named follow-up.
- **In-transit ATP (P1) — unstated deferral.** `transfer!`
  decrements the source ATP at send time and the destination only
  gains it at `complete-transfer!` — so in-transit stock is
  promiseable at neither facility during transit. Defensible for
  v1, but it was not in any deferral list. **Fixed (honesty)** —
  added to ADR-059.
- **In-transit-balance query helper (P2) — claimed, no API.**
  ADR-059 defined the in-transit balance as `Σ :inventory-transfer/
  quantity` over `:in-transit` rows but shipped no helper. **Fixed**
  — `kontor.inventory.report/in-transit-balance` added (optionally
  scoped by from/to facility).
- **Negative-fill not linked to the originating sale (P2).** Note
  36 §1 asked the true-up to link back to the originating issue.
  **Fixed** — `:negative-fill/origin-issue` attr added, stamped by
  `issue!` (the issue tx eid was already in hand) inside the atomic
  tx.

## Deferred follow-ups (carried, not fixed)

- The serialized-item path in `reserve!` / `issue!` (qty-1 bucket,
  `:status :consumed` on issue).
- A `return!` helper that defaults a return's unit cost from the
  original `:layer-consumption` and records the link.
- The `:account/system-managed?` preventive control (kernel schema
  flag + posting-middleware enforcement).
- In-transit stock as promiseable ATP (a virtual `:transit` facility
  the transfer moves *through*).
- Kit / unbuild helpers, the consignment ownership flag, drop-ship
  as a non-inventory path — vertical-dependent P2 (already in
  ADR-060's deferral list).
- A materialised QOH/ATP snapshot if the derived-view linear scan
  proves slow at scale (its own future ADR per ADR-057).

## Outcome

No P0s. All three code-review P1s and the cheap P2s fixed in the
review-fix commit on top of `7caf724`; the market-pain findings were
mostly deferral-list honesty corrections (schema reword + ADR
notes) plus two small additions (`in-transit-balance`,
`:negative-fill/origin-issue`). Full suite green afterwards. The
remaining items are genuine feature gaps the existing seams absorb —
captured here for a later stage or an l10n module.

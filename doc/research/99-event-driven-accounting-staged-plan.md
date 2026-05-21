---
date: 2026-05-20
title: 99 — Event-driven accounting — the staged plan of record
status: plan of record (maintainer-approved 2026-05-20)
audience: maintainer + implementer working the stages
---

# 99 — Event-driven accounting: the staged plan of record

The plan that came out of the McComb round (notes 80 / 88 / 97 / 98)
plus the critical reading that **deflated** the scope: kontor does
*not* build a stored-`:event` / θ-as-data framework. kontor's
`*-tx-data` builders already are θ (in code, the right
representation); sealing (ADR-007) neutralizes the framework's
re-derivability payoff; "events" are already the dispatch operations
kontor provides. What kontor builds instead is **three targeted
moves** + the cleanup they expose + docs.

Grounded by two research-agent investigations (implementation-options
+ the staged plan) archived from the 2026-05-20 round.

## Maintainer decisions (2026-05-20)

1. **ADR-071 — implement it for real.** `TaxRateProvider` /
   `TaxFacts` / `TaxPostingBuilder` were never built (`tax_rate_
   provider.clj` never in git history; only the legacy stub
   `tax_provider.clj` exists). The decision is to *implement* the
   trio, not demote the ADR to "proposed." This becomes Stage 2.
2. **Verb-facade namespace: `kontor.book`.** ("To book a
   transaction" — accounting term of art; avoids the
   `kontor.event-bus` / `kontor.process` / `clojure.core/record`
   clashes.)
3. **`:posting/dimension` lives in the kernel schema** — additive.
   A posting-level classification axis is a cross-cutting substrate
   concern; a companion location would invert the layering (the
   kernel report engine must `marginalize` over it without a
   companion dependency).

## The three moves (recap)

- **Move 1** — a thin verb facade: ~10 well-named verbs as sugar
  over the existing `*-tx-data` builders. No new entity, no schema.
- **Move 2** — wider account-class tagging + a marginalizing report
  engine: `:account` becomes one classification axis among several;
  reports aggregate over any axis (the quotient epimorphism `σ_E`).
- **Move 3** — a `:commitment` entity for the forward-looking,
  not-yet-posted promise (open AR/AP, encumbrances). Conservatively
  scoped — *add* the entity; do **not** unify the scattered
  commitment-shaped entities (`:promise`, `:schedule`, lease
  liability, procurement `:order`) — that is a named later pass.

## Stages

Each stage independently shippable; test-first; per the ADR-037
rhythm. ADR numbers are **095–098** (ADR-094 is the highest existing).

### Stage 1 — `kontor.book` verb facade — ADR-095

New kernel namespace `src/kontor/book.clj`. ~10 verbs, each a thin
`*-tx-data` builder + `!` wrapper through `kontor.validation/
transact-with-validation` (ADR-068). The **minimal 8** that close a
complete cash+accrual single-entity book: `receive`, `pay`, `sell`,
`buy`, `receive-payment`, `pay-bill`, `transfer`, `adjust`. Extras:
`raise` / `lower` (revaluation pair). Consistent one-options-map
signature; delegates to `post-transaction-tx-data` (and
`apply-payment-tx-data` for the settle verbs). Kernel, **no schema**.
`deliver` and `order` are deliberately *excluded* — `deliver` needs
a `CostingProvider` (it is the inventory companion's verb); `order`
is a commitment, not a posting (Stage 4).

*Acceptance:* a `book_test.clj` runs a full `buy → sell →
receive-payment → pay-bill` cycle via the facade; trial balance is
zero and matches a hand-built `post-transaction!` baseline.

### Stage 2 — implement ADR-071 (`TaxRateProvider`)

Build the `TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder` trio
ADR-071 specifies (`src/kontor/tax_rate_provider.clj`), with a
`StaticTableProvider` over the existing `:tax/*` schema. Drop /
migrate the legacy `tax_provider.clj` defrecords per ADR-071
§"Implication 2". Update ADR-071's status + `CLAUDE.md` +
`architecture.md` to reflect reality. Independent of Stage 1 (the
facade's `sell`/`buy` take caller-supplied tax postings until this
lands, then can resolve tax via the provider).

*Acceptance:* `tax_rate_provider.clj` exists; a `StaticTableProvider`
resolves a real tax line; ADR-071 + docs no longer describe vapor.

### Stage 3a — `marginalize` / `σ_E` report engine — ADR-096

Generalize `kontor.report` so the engine is a dimension-agnostic
`marginalize` (a quotient epimorphism `σ_E`, note 97 §3). The
existing `:account-codes` + `:tax-tags` `defmulti` methods become
*instances*. Add a `:dimension` engine with built-in axes
(`:account-type`, `:account-code-prefix`, `:account-tags`,
`:ledger`, `:entity`, `:commodity`, `:partner` — all already
surfaced by `pull-posting`). Add an optional `:posting-filter`
datalog clause to `compute-report` to narrow the current
pull-all-postings scan. Kernel, **no schema**.

*Acceptance:* all 11 l10n tax-return modules' tests still pass
(behaviour-identical regression gate); a new `report_test.clj`
shows `marginalize` over `:account-type` reproduces the balance
sheet.

### Stage 3b — `:posting/dimension` — ADR-097

New kernel `:posting-dimension/*` entity `{axis, value, posting}` +
`:posting/dimensions` cardinality-many ref. `:account` becomes one
classification axis among several; "free the basis" (note 97 §7.4)
realized as *"`:account` is no longer the only thing you can
marginalize over"*, **not** as removing `:account` (it stays
mandatory — it backs `Ker(σ)`). NOT materialized the way
`:posting/account-tags` is — written explicitly by the verb facade /
consumer. Strictly additive: no change to sealing, sum-to-zero,
bitemporality. Kernel schema change (decision 3).

*Acceptance:* a transaction booked via `kontor.book` carrying two
`:posting/dimensions` aggregates correctly under a `:dimension`
report over each axis; the `:account-type` report is unaffected.

### Stage 4 — `kontor-commitment` companion — ADR-098

New companion `modules/commitment/`. `:commitment/*` entity
(`kind` ∈ `:receivable|:payable|:encumbrance`, counterparty,
committed + fulfilled amounts, due-date, `:state` via the
status-machine) + a `:commitment-fulfillment` edge to the settling
`:transaction`. Helpers: `record-commitment!`, `fulfill!`,
`open-commitments` (bitemporal), `aging`. **Kernel untouched** —
the fulfillment edge lives in the companion. Conservatively scoped:
`:commitment/origin` is an opt-in soft link to `:order` / `:schedule`
/ lease liability; those modules are **not** changed; unification is
a named later pass.

*Acceptance:* `record-commitment!` an open receivable; `sell` then
`receive-payment`; `fulfill!` links the payment; `open-commitments`
closes it out; `aging` buckets a still-open one — bitemporally
consistent.

### Stage 5 — consistency cleanup (triaged)

Triaged sweep of note 95 §2/§3 remaining items + doc↔code drift
found en route. Fix-now: rewrite note 95 §3 conventions to match
observed code (schema owns `install!`); add `kontor.book` +
`:posting-dimension/*` to the `CLAUDE.md` namespace map + ADR-002
list. Defer: the `plan-*`→`*-tx-data` rename pass (note 95 §4),
the `kontor.asset.asset` / `expense.expense-test` ns renames.
Accept+document: the `kontor.invoice.bridge` kernel-collision name,
the `:posting/account-tags` materialization debt. Docs-only.

### Stage 6 — documentation (last — describes shipped code)

`doc/quickstart.md` (new) — verb-facade on-ramp: `create-test-db`
→ a closed cash+accrual book using only `kontor.book`.
`doc/accounting-model.md` (new) — verbs ↔ debits/credits ↔
chart-of-accounts-as-a-basis ↔ journals/statements-as-`σ_E`, with a
user-facing keepers/discards table (note 97 §6). Update `README.md`
quick-start pointer, `doc/programming.md`, `doc/value.md` (name
kontor as a typed bitemporal realization of the balance module).
ADR-010 honored — markdown only, no UI.

## Sequencing

S1 first (cheap, no-schema on-ramp). S2 independent — can run
alongside. S3a before S3b (3b's schema needs 3a's `marginalize` to
be useful; 3a must regression-pass the 11 l10n modules first). S4
after Move 2. S5 interleaved-cheap. S6 last.

## Deferred (explicitly not this round)

- The stored-`:event` / θ-as-data framework (the critical reading
  killed it — note 97 reconsidered).
- Unifying `:promise` / `:schedule` / `:lease-liability` / `:order`
  under `:commitment` — named later pass.
- The `plan-*`→`*-tx-data` rename pass (note 95 §4).
- A materialized / incremental report engine (the
  pull-all-postings scan is mitigated, not eliminated, in S3a).

## Risks

- **Move 2 blast radius** — 11 l10n tax-return modules consume the
  report engine; the `marginalize` refactor must be
  behaviour-identical (the regression gate).
- **`compute-report` performance** — O(ledger size) pull-all scan;
  `:posting-filter` mitigates, does not eliminate.
- **`kontor.book` signature lock-in** — a published surface;
  ADR-095 must treat the signature as carefully as ADR-068 treats
  `*-tx-data`.
- **`:posting/account-tags` materialization debt** — `:posting/
  dimension` deliberately does NOT replicate it; the contrast must
  be documented or it confuses future contributors.

## Sources

Internal: notes 80 / 88 / 97 / 98; note 95 (consistency audit);
two 2026-05-20 research-agent reports (implementation-options +
staged plan). ADRs to write: 095 (`kontor.book`), 096 (report as
marginalization), 097 (`:posting/dimension`), 098
(`kontor-commitment`); ADR-071 implemented + status-corrected.

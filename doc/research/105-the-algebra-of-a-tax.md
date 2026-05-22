---
date: 2026-05-21
title: 105 — The algebra of a tax — the period-tax substrate's generalization frontier
status: design note — names three frontier generalizations; frontier 1 implemented
audience: maintainer + period-tax implementers
---

# 105 — The algebra of a tax

ADR-099 + notes 102 / 103 / 104 built the period-tax substrate and proved it
across 11 jurisdictions. note 104 Stage 1 (personal income tax × 6) surfaced —
via the per-country abstraction audits — that the substrate is an *early
approximation* of a fuller algebra. This note names that algebra and its
generalization frontier.

## 0. A tax is a stateful, signed, graph-composable pipeline

A single tax is the pipeline

```
(scope, carry-in) → base-selector → base-transform → schedule
                  → adjustment-fold → (signed liability, carry-out) → posting
```

and **taxes compose** — one tax's output feeds another's input. The substrate
today handles the *pipeline* well (scope / base / schedule — ADR-099) and is an
early approximation at three places: the **adjustment layer**, the **carry**,
and the **composition graph**. Each is a genuine algebraic structure the
substrate currently flattens.

## 1. Frontier 1 — the adjustment layer (IMPLEMENTED — ADR-099 addendum 4)

`liability = gross − Σcredits + Σsurtaxes` models the credit/surtax layer as a
*commutative sum*. It is really an **ordered fold producing a signed result**,
with three facets, all demanded across most jurisdictions:

- **base-aware** — credits and surtaxes routinely depend on *income*, not the
  tax: CA's BPA phase-out, US CTC / EITC / QBI, AU LITO + Medicare Levy
  Surcharge, MX subsidio, FR décote, IN §87A + the income-banded surcharge.
  `:credits` could only carry *static pre-computed* amounts; `:surtax-fns` saw
  only the post-credit tax — so all that income-dependent logic was pushed onto
  the consumer or buried in a `:formula`.
- **ordered** — credits are not commutative: non-refundable apply before
  refundable; "wasted" credit depends on order.
- **signed** — a *refundable* credit can push the liability **below zero**: the
  EITC, the MX subsidio, the refundable CTC are *negative tax* — a transfer
  *to* the taxpayer. Flooring at 0 silently mis-models them. A signed liability
  also unifies with the collector / two-sided view (a negative tax is the state
  on the paying side).

**The design — `kontor.tax-schedule/apply-adjustments`.** An ordered fold over
**adjustment items**:

```clojure
{:code :label
 :op          :credit | :surtax        ; subtract | add
 :refundable? <bool>                    ; :credit only — false (default) floors
 :amount      <bigdec> | <fn>}          ; data, OR a base-aware fn of ctx
```

`ctx = {:base :gross :running :tax-unit}` — `:fn` items see the base, the gross
tax, and the running tax. The fold: `running` starts at `gross`; a
non-refundable credit does `max(0, running − amt)`, a refundable credit
`running − amt` (may go negative), a surtax `running + amt`. Returns the signed
liability + the resolved items (so they surface as structured
`:credits` / `:surtaxes`, not buried in a `:formula`). `:credits` /
`:surtaxes` as plain `:inputs` data and config `:surtax-fns` keep working
(translated to items) — backward-compatible.

## 2. Frontier 2 — the carry (deferred; demand: multi-year)

A tax is not `base → liability` but **`(carry-in, base) → (liability,
carry-out)`**: loss carryforward, unused-credit carryforward, allowance
carryover, capital-loss pools. note 103 §3a gave an ad-hoc `:inputs` convention
for one case; the general structure is a *fold over the period stream* (a
Mealy machine) — `carry-out` of year N is `carry-in` of N+1. kontor is
bitemporal and multi-year; faithful corporate loss carryforward and capital
gains genuinely need this. Build it when Phase 3 (corporate income tax) demands
it.

## 3. Frontier 3 — the tax graph (deferred; demand: Phase 3 interacting taxes)

An entity's real tax position is **many taxes interacting** — a dependency
graph: one tax's liability feeds another's base (deductibility — JP enterprise
tax, US state-deductible-from-federal, DE Gewerbesteuer ↔ KSt), minimum taxes
supersede, surtaxes ride a prior liability. The substrate has the *edge
primitives* (`surtax-on`, `greater-of`, the `:composed-of` field) but no graph
*evaluator* — the provider topologically orders by hand and `TaxReturnFacts` is
a flat component vector. Making the graph first-class (nodes = taxes, typed
edges = feeds-base / creditable-against / supersedes; an evaluator that sorts
and threads) is the unifying generalization — `surtax-on` / `greater-of` /
base-aware-credit all become edge kinds. Build it when Phase 3's DE
Gewerbesteuer ↔ Körperschaftsteuer interaction lands — the first real DAG.

## 4. Demand-order

The three frontiers are demand-ordered, per the project discipline (extend the
substrate when a jurisdiction demands it, never speculatively):

| Frontier | Demanded by | Status |
|---|---|---|
| 1 — adjustment layer | personal income tax — most jurisdictions | **built** (ADR-099 addendum 4) |
| 2 — the carry | corporate loss / capital-gains carryforward | deferred → Phase 3 |
| 3 — the tax graph | DE Gewerbesteuer ↔ KSt and other interacting taxes | deferred → Phase 3 |

Frontier 1 ships now (before note-104 Phase 2) so the sole-proprietor,
corporate and property rungs build on a complete adjustment layer. Frontiers 2
and 3 are named and tracked here; they ship inside Phase 3 when the corporate
build genuinely needs them — not before.

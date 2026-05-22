---
date: 2026-05-21
title: 104 — Completing the tax system — the individual → corporation continuum
status: completion-program plan — organises the per-jurisdiction tax build
audience: maintainer + the per-country implementation agents
---

# 104 — Completing the tax system: the individual → corporation continuum

ADR-099 + notes 102 / 103 delivered the period-tax **substrate** and proved it
across all 11 jurisdictions. This note is the **completion program**: the
staged plan to fill in the per-country tax content faithfully.

The organising principle is **not** tax type but the **growth continuum of an
economic actor**: an individual worker → a side hustle → a sole proprietor → an
incorporated entity → a growing company → a multinational group. kontor should
carry the actor along that whole path on one substrate — the same
`kontor.book` verbs, the same posting kernel, the same `TaxRateProvider` /
`PeriodTaxProvider`. Each rung adds bookkeeping and tax obligations; none
requires a different system. **The individual rung comes first** — kontor must
"work reasonably well on an individual level" before the organisational rungs
matter.

## 1. Where we stand

Substrate: **complete** — `kontor.tax-schedule` (flat / bracket / capped /
formula / elect / sum + base-transform + surtax-on / greater-of / lesser-of),
`PeriodTaxProvider` / `TaxReturnFacts` / `TaxReturnPostingBuilder`,
`StandalonePayrollTaxProvider`, `CorporateIncomeTaxProvider`,
`PersonalIncomeTaxProvider` (ADR-099 + addenda 1 / 2).

Per-country content (`✓` faithful provider · `◐` partial · `✗` not built):

| | VAT on `TaxRateProvider` | Personal income | Corporate income | Capital gains | Property / wealth |
|---|---|---|---|---|---|
| AT | ✓ | ✓ | ✓ | ✗ | ✗ |
| AU | ✓ | ✓ | ✓ | ✗ | ✗ |
| CA | ✓ | ✓ | ✗ | ◐ (`s3`) | ✗ |
| DE | ✓ | ✓ | ✗ | ✗ | ✗ |
| FR | ✓ | ✓ | ✗ | ✗ | ✗ |
| MX | ✗ | ✗ | ✓ | ✗ | ✗ |
| US | ✗ | ✗ | ✓ | ✗ | ✗ |
| CN | ✓ | ✗ | ✓ | ✗ | ✗ |
| JP | ✓ | ✗ | ✗ | ✗ | ✗ |
| IN | ✗ | ✗ | ✗ | ✗ | ✗ |
| BR | ✗ | ✗ | ✗ (rate consts) | ✗ | ✗ |

Standalone employer payroll levies: MX ISN / AU state payroll / AT
Kommunalsteuer shipped. Employer social-insurance contributions: recorded
engine-authoritative for all 11 (ADR-075). VAT/GST exists for all 11; on the
new `TaxRateProvider` for 7 (BR / IN / MX / US deferred — note 100).

## 2. Abstraction status — what is genuinely missing

The two-provider substrate is **structurally complete for the routine
business / personal tax surface** — note 103's coverage proof + iterations
1.5 / 5 closed every gap. Genuinely outstanding:

- **Event-incident taxes** — inheritance / estate / gift. These attach to a
  *one-off event* (a death, a gift) that is neither a routine transaction nor
  a period — a real *third incidence shape*, expressible by neither provider.
  The one honest abstraction gap; deferred until estate modelling is wanted.
- **The `:disposal/*` capital-gains data model** — deferred (note 103 §3b):
  per-disposal holding-period + ACB tracking, a companion, no kernel change.
- **Group / consolidated taxation** (DE Organschaft, US consolidated returns)
  — *expressible* today (the base-selector can marginalize an entity-family,
  ADR-031) but unexercised.

Everything else is **content, not abstraction**. The per-country agents
(§4) must nonetheless keep raising incompatibilities — if a jurisdiction
cannot be expressed faithfully, the substrate is extended, never the tax
approximated (the iteration-5 discipline: `:sum` / `:surtaxes`).

## 3. The staged plan

### Phase 1 — the individual level

Personal income tax for the six jurisdictions that lack it — **US, CN, MX,
JP, IN, BR** — faithful `PersonalIncomeTaxProvider` configs + golden tests.
After Phase 1 an individual worker in any of the 11 can keep books in
`kontor.book` and compute their income-tax return. Validate with a freelancer
user story. This is the first rung and the immediate priority.

### Phase 2 — sole proprietor / side hustle

Business income on the personal return — the sole-proprietor pattern (CA's
`t2125` generalised: business net feeds personal taxable income via a
`:base-transform` / an `:inputs` income component). VAT/GST registration for
the small trader. Selling and buying *as the individual's registered
business* — `kontor.book` + a single `:entity` already make this possible;
Phase 2 makes it a smooth, documented path.

### Phase 3 — incorporation

The transition rung — the "website that grows into a company" moment. A
documented primitive for *individual incorporates → a legal `:entity`*: the
new entity's opening books, owner compensation (salary vs dividend) flows,
and the disposal/contribution of the sole-proprietor assets into the entity.
Corporate income tax for the remaining jurisdictions — **DE** (KSt +
Gewerbesteuer add-backs + Soli), **FR** (IS), **CA** (T2), **JP** (the
national + local + enterprise stack). Capital gains — build the `:disposal/*`
companion here (incorporation is itself often a disposal event).

### Phase 4 — growing company / multinational

Property / wealth taxes across the 11 (`:flat` / `:progressive` on an
`:inputs`-fed assessed value — incl. FR IFI). The BR / IN / MX / US
transaction-tax migration (note 100's deferred L/XL modules). Group /
consolidated taxation (entity-family scope). Payroll, multi-entity
consolidation and FX are already shipped — the multinational rung is mostly
substrate that already exists.

## 4. Per-stage agent strategy

Once a phase is "fill in individual countries" (Phases 1, 3-property,
4-property), it fans out to **parallel per-country agents**. Each agent has a
dual mandate:

1. **Implement faithfully** — the real statutory schedule (brackets /
   formula / elect), real current-year figures (bundled with a verify
   caveat, the established l10n pattern), wired as a provider config, with
   golden tests cross-checked against published worked examples.
2. **Review the abstraction** — explicitly assess whether the jurisdiction's
   tax fits the `(scope, base-selector, schedule)` substrate, and **raise any
   incompatibility** as a structured finding (not a silent workaround).

The maintainer synthesises the findings after each phase: a genuine
incompatibility extends the substrate (an ADR-099 addendum, as `:sum` /
`:surtaxes` were); the rest is folded into the per-country configs. Each
phase ends green on `bb test`.

## 5. "Faithfully complete" — the bound

Complete = **each jurisdiction's routine tax surface has a faithful,
golden-tested provider** — the taxes a normal individual / business actually
files. It does **not** mean every form, every credit, every edge case — that
content is unbounded and grows on consumer demand. The substrate is finished;
every remaining piece is a provider config + tests, no new design — except
the two named deferrals (event-incident taxes; the `:disposal` model, built
in Phase 3).

Bottom line: the program is four phases along the individual → corporation
continuum, individual-first; the per-country work parallelises to agents that
both implement and audit; the substrate grows only when a jurisdiction
genuinely demands it.

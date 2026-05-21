---
date: 2026-05-21
title: 103 — Period-tax coverage proof — common taxes × 11 legislations against the shipped substrate
status: research-before for the per-jurisdiction period-tax build — surfaces 3 substrate gaps
audience: maintainer + implementer of the period-tax build
---

# 103 — Period-tax coverage proof

ADR-099 shipped the period-tax substrate (`kontor.tax-schedule` +
`kontor.period-tax-provider` + `kontor.tax-return-posting-builder`). Note 102
designed it and mapped *what taxes* the 11 legislations levy. This note is the
missing step: a concrete `(tax-type × legislation)` **coverage proof against
the shipped code** — does the substrate actually express each common tax, for
each jurisdiction? Two research agents verified it; this is the synthesis. It
changes no code; it gates the per-jurisdiction build (note 102 §10).

## TL;DR

- **42 of 44 "regular" cells expressible** (personal/corporate income, property,
  standalone payroll × 11) — 24 cleanly, 18 with a caveat; 2 cannot be
  expressed regularly (IN corporate / MAT, BR corporate / Lucro Presumido).
- All friction reduces to **three substrate gaps** — every one small,
  additive, schema-free, and *already foreseen by note 102 §9* but not fully
  carried into ADR-099's iteration-1 code.
- **Capital gains:** the schedule algebra is sufficient; every break is on the
  *base* side. CGT is v1-expressible (gains fed via `:inputs`, the `s3.clj`
  pattern) for CA/AT/FR/CN/IN; the 6 jurisdictions with holding-period splits
  or non-trivial cost-base rules (US, AU, JP, DE, IN, MX) need a `:disposal/*`
  data model for a *faithful* build — a companion, zero kernel change.
- **Verdict: the design holds.** Fix the 3 gaps once (an ADR-099 addendum)
  before the per-country grind; adopt a `:inputs` loss-carryforward convention;
  defer the `:disposal/*` companion. Then resume the build.

## 1. Coverage matrix — the regular taxes

PIT personal income · CIT corporate income · PROP property/asset · PAYL
standalone employer payroll levy. ✅ expressible as-is · ⚠️ expressible with a
caveat (one of the 3 gaps) · ❌ not expressible regularly.

| | DE | AT | FR | US | CA | AU | JP | CN | IN | BR | MX |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **PIT** | ⚠️ | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| **CIT** | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ✅ | ⚠️ | ⚠️ | ❌ | ❌ | ⚠️ |
| **PROP** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **PAYL** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ |

Net 24 ✅ / 18 ⚠️ / 2 ❌. **Property tax** is clean across all 11 — `:flat` (or
`:progressive-bracket` for AU land tax) on an externally-assessed value fed via
`:inputs`; the wealth-tax stock-base path (FR IFI) works too. **Standalone
payroll levies** (MX ISN, AU state payroll tax, AT Kommunalsteuer) are clean —
`:flat`/`:capped` on a marginalized wage sum, the cleanest fits in the survey.
All ⚠️/❌ trace to the three gaps below.

## 2. The three substrate gaps

Each was foreseen by note 102 §9; each is small and additive. The
iteration-1 substrate was implemented from note 102 §1–§4 (the v1 design); §9's
amendments were not all carried back into §1–§4, so two of these slipped.

### GAP 1 — no `:base-transform` stage (note 102 §9-B, designed, not shipped)

The substrate pipeline is `base-selector → schedule`. There is **no stage
between the marginalized aggregate and the schedule** — `grep base-transform
src/kontor/` is empty. But corporate income tax's taxable base is *book profit
+ statutory add-backs − deductions* (DE Gewerbesteuer Hinzurechnungen, FR
réintégrations, US Schedule M-1, …), and **BR Lucro Presumido** is *a
presumption ratio × revenue* (8 % / 32 %). Today that arithmetic hides in
opaque provider code; BR Lucro Presumido (❌) cannot be expressed *regularly*
at all. This is the substantive gap — it drives every CIT ⚠️ cell.

**Fix:** an optional `:base-transform` on the component + a
`kontor.tax-schedule/apply-base-transform` sibling fn — a tagged-data
transform (`{:transform/type :presumption-ratio :ratio 0.08M}`,
`{:transform/type :add-backs :rules [...]}`) applied between the marginalized
aggregate and `apply-schedule`. Schema-free, additive.

### GAP 2 — `:elect :max` is same-base only (note 102 §7-stress-3 / §9-A)

`elect-tax` applies every sub-schedule to **one shared base**. A genuine
minimum tax (**IN MAT** — ❌; US AMT) compares regular tax against a minimum
computed on a *different* base. `:elect :max` cannot express it; `surtax-on`
(rate on a prior liability) does not help. (AT Mindest-KöSt is safe — its
minimum is a fixed amount, so `:elect :max` works there.)

**Fix:** a component-level `greater-of` combinator — two already-computed
component liabilities → the larger, the loser marked superseded via
`:composed-of`. Parallel to where `surtax-on` already sits (outside
`apply-schedule`).

### GAP 3 — `:formula` is single-arity `(fn [base])` (note 102 §9-D weakened)

`apply-schedule`'s `:formula` arm calls `((:fn schedule) base)` — one arg. The
tax-unit (FR quotient familial, DE Ehegattensplitting, US filing status) must
therefore be closed into the `:formula` fn at construction; `context :tax-unit`
is collected but never reaches the schedule. Expressible (hence ⚠️ not ❌) but
the tax-unit is invisible to the regular layer — exactly what §9-D said to
avoid.

**Fix:** widen the `:formula` arm to `((:fn schedule) base ctx)` (ctx optional)
— one line, backward-compatible.

Also noted: `:composed-of` shipped as a real component field but **inert** —
nothing computes it; it is a pure audit edge. Acceptable as-is (the provider
composes; the field records); `greater-of` will be its first real writer.

## 3. Capital gains

The schedule algebra is **not** the bottleneck — `:flat`, `:progressive-bracket`,
`:elect` (AT/FR/IN regime elections), `surtax-on` (DE Soli, JP reconstruction,
IN cess) cover every CGT rate structure across all 11. Every break is on the
**base** side, in four shapes a scalar `:base` Money cannot carry: holding-period
splits (DE 10 yr, US 1 yr, AU 12 mo, JP 5 yr, IN 12/24 mo), cost-base rules
(CA ACB averaging, IN 31-Jan-2018 grandfathering, MX INPC indexation, AT
Altvermögen), asset-class-siloed loss netting + carryforward (every
jurisdiction), and per-disposal withholding (notary / broker / FIRPTA).

**Verdict.** CGT is **v1-expressible** — a `:capital-gains-tax` component
(kind already in the enum) with `:base` = the pre-computed aggregate taxable
gain, per-disposal detail in `:line-items`, carryforward in `:inputs` — the
`s3.clj` contract generalized. CA / AT / FR / CN / IN are legitimately v1 this
way. But for **US / AU / JP / DE / IN / MX** a *faithful* build needs
per-disposal records: holding-period classification and siloed netting are a
**data-model** question, not a schedule question. A scalar `:base` structurally
cannot represent "₹2L of 13-month equity gain + ₹1L of 8-month equity loss."

**Two fixes, different urgency:**

- **(a) `:inputs` loss-carryforward convention — adopt now, zero code.** A
  documented contract for the key the provider reads/writes:
  `:inputs {:capital-loss-carryforward {...}}` in, the residual in a
  `:line-items` carry-out entry. Closes `s3.clj:19`'s "handled at the
  NoA-ingestion layer" hole with an actual convention.
- **(b) A `:disposal/*` companion — the faithful answer, deferred.** Not a
  kernel change: a `:disposal/*` entity (`:asset-class`, `:acquired-at`,
  `:disposed-at`, `:proceeds`, `:cost-base`, `:costs`) in a companion —
  reusing `kontor-asset`'s existing `:asset-event :disposal` for registered
  fixed assets; a pure-securities `:disposal` is the genuinely new shape. The
  provider then `marginalize`s over `:disposal` records — note 102 §7's own
  analogy: "`:disposal` to `:capital-gains-tax` mirrors `:posting` to
  `marginalize`." `kontor.report/marginalize` (ADR-096) pivots disposals by
  `:asset-class` and a derived `:term` axis — no new marginalize machinery.
  **No new `:schedule/type` is needed** — the rate side is solved.

## 4. Sequencing — note 102 §10 revised

Note 102 §10 deferred capital gains entirely to step 6. Revise:

- **Pull forward:** CGT-as-`:inputs`-fed-aggregate ships **with the CA T1
  pilot (step 2)** — CA folds CGT into income (line 12700 → 15000), so the T1
  port *cannot be faithful without it*. The `:inputs` carryforward convention
  (3a) is a one-paragraph ADR addendum that unblocks it.
- **Keep deferred:** the `:disposal/*` companion + ACB/indexation tracking —
  real work, rightly after the income-tax pilots prove the substrate.
- **Confirm:** the hard CGT jurisdictions (US/AU/JP/DE — holding-period +
  multi-bucket netting) wait for the `:disposal/*` companion.

## 5. Recommendation

1. **Fix the 3 gaps now** — an **ADR-099 addendum** + code: `:base-transform`
   + `apply-base-transform` (GAP 1), `greater-of` (GAP 2), the `:formula`
   3-arity (GAP 3). Small, additive, schema-free; do it once before the grind.
2. **Adopt the `:inputs` loss-carryforward convention** (3a) — documented in
   the same addendum, no code.
3. **Defer the `:disposal/*` companion** (3b) — named, for the 6 hard CGT
   jurisdictions; built after the income-tax pilots.
4. **Then resume the per-country build** — CA T1 pilot (now including
   `:inputs`-fed CGT), standalone payroll taxes, corporate income tax (now
   with `:base-transform` for the add-backs), personal income tax.

The coverage proof did its job: the design holds, and the gaps are cheap to
close now rather than mid-grind.

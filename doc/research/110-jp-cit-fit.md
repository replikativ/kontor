---
date: 2026-05-24
title: 110 — JP corporate income tax statute-fit assessment (Phase 3)
status: research-before — pre-implementation substrate audit
---

## §0. Scope

A statute-fit assessment for Japanese corporate income tax (KK 株式会社 / GK
合同会社) against the kontor `PeriodTaxProvider` substrate (ADR-099 + four
addenda, notes 102–105). The aim is to confirm or refute — *before any code
lands* — that the existing kernel algebra (`kontor.tax-schedule` 6 schedule
kinds, `apply-base-transform`, `apply-adjustments`, `surtax-on`, `greater-of`)
is sufficient, and to surface every abstraction stress, not only the one note
107 §4.3 already flagged.

The prior research-before pass (note 107 §4.3) walked the same stack at a
shallower depth and flagged ONE stress — the per-capita inhabitants' levy
(`均等割`) — proposing a tiny additive `:fixed-amount` schedule kind. This
note does the deep pass: §1 enumerates every component, §2 walks JETRO's
worked example end to end, §3 maps each component onto kontor primitives,
§4 catalogues stresses (the §4.3 one AND any others), §5 gives the
consolidated minimal substrate-add list, §6 cites sources.

## §1. Statute summary — the JP corporate-tax stack (FY ≥ 2025-04-01)

Five (six from FY 2026-04-01) levies attach to one corporate fiscal year:

1. **National Corporation Tax — 法人税 (Hōjinzei).** NTA-administered.
   Schedule depends on capital and SME status:
   - SME (paid-in capital ≤ ¥100M, not a wholly-owned subsidiary of a ≥
     ¥500M parent, three-year avg taxable income < ¥1.5B): **15 %** on the
     first ¥8M of taxable income, **23.2 %** on the excess. The ≤ ¥8M
     reduced rate rises to **17 %** if current-year taxable income > ¥1B.
   - Large company (capital > ¥100M, or otherwise SME-excluded):
     **23.2 % flat**.

2. **National Local Corporation Tax — 地方法人税 (Chihō Hōjinzei).** A
   national tax administered for redistribution to local governments.
   **10.3 % × the national corporation tax liability** (a tax-on-tax, NOT
   a tax on income).

3. **Enterprise Tax — 事業税 (Jigyōzei).** Prefectural. Two-schedule split
   by company size:
   - **SME (capital ≤ ¥100M):** progressive income-base ladder, 3.5 % /
     5.3 % / 7.0 % on the ≤¥4M / ¥4M–¥8M / >¥8M slices (Tokyo: 3.75 / 5.665
     / 7.48 %).
   - **Large (capital > ¥100M) — pro-forma (外形標準課税):** **THREE
     simultaneous components on three different bases**:
     - income-base: 1.18 % standard (Tokyo progressive 0.495 / 0.835 / 1.18 %);
     - **value-added base** (報酬給与額 + 純支払利子 + 純支払賃借料 ± loss):
       1.26 %;
     - **capital base** (資本金 + 資本剰余金): 0.525 %.
   This is the most structurally interesting tax — a single statute that
   marginalizes THREE different aggregates.

4. **Special Corporate Enterprise Tax — 特別法人事業税 (Tokubetsu Hōjin
   Jigyōzei).** National levy, locally collected on the enterprise-tax
   return. Computed as **(rate) × the INCOME-base enterprise-tax amount** —
   a surtax-on-a-component-of-another-tax. SMEs: 37 %; large: 260 %.

5. **Corporate Inhabitants' Tax — 法人住民税 (Hōjin Jūminzei).**
   Prefectural + municipal. TWO components per government level (so four
   sub-charges, normally aggregated into two visible totals):
   - **Income-percentage 法人税割.** Standard prefectural 1.0 % / municipal
     6.0 % (max 2.0 / 8.4 %) — but the **base is the national corporation
     tax liability**, NOT taxable income (same shape as the local
     corporation tax: another tax-on-tax).
   - **Per-capita levy 均等割.** A **FIXED YEN AMOUNT** indexed by
     paid-in capital × headcount, 5×2 = 10 tiers:

     | Paid-in capital | ≤50 employees | >50 employees |
     |---|---|---|
     | ≤¥10M | ¥70 000 | ¥140 000 |
     | ¥10M – ¥100M | ¥180 000 | ¥200 000 |
     | ¥100M – ¥1B | ¥290 000 | ¥530 000 |
     | ¥1B – ¥5B | ¥950 000 | ¥2 290 000 |
     | >¥5B | ¥1 210 000 | ¥3 800 000 |

     This is **the** structurally unusual component — *no base at all*; the
     levy is a function of `:tax-unit` only.

6. **Special Corporate Tax for Strengthening Defense Capabilities — 防衛
   特別法人税** (FY ≥ 2026-04-01). **4 % × (national CIT − ¥5M basic
   deduction)** — a surtax with a deduction-on-the-prior-liability shape.

**Net operating loss carry-forward.** Up to 10 years for losses incurred
TY ≥ 2018; SMEs deduct unlimited carry-forward against current income;
**large companies cap utilization at 50 % of current taxable income** (the
direct analogue of DE's Mindestbesteuerung). One-year carryback for SMEs
only at national level.

**Reconstruction surtax (法人税).** The 復興特別法人税 corporate-tax surtax
expired FY 2014; only the *individual* 復興特別所得税 (modeled in
`l10n-jp/period_tax_provider.clj`) remains. No corporate equivalent today;
the 2026 defense surtax is its replacement-in-spirit.

## §2. Worked example — JETRO's SME illustration

Per JETRO Section 3.3, an SME (capital ≤ ¥100M, Tokyo, fully-domestic,
≤50 employees) with **¥10 000 000 taxable income**:

| Component | Computation | Amount |
|---|---|---|
| National CIT 法人税 | 0.15 × 8 000 000 + 0.232 × 2 000 000 | **¥1 664 000** |
| Local CIT 地方法人税 | 0.103 × 1 664 000 | **¥171 392** (JETRO ≈ ¥171 800; small rounding) |
| Inhabitants' income levy 法人税割 | 0.07 × 1 664 000 (prefectural 1 % + municipal 6 %) | **¥116 480** (JETRO ¥116 400) |
| Inhabitants' per-capita 均等割 | tier (capital ≤¥10M, ≤50 emp) | **¥70 000** (omitted from JETRO's ¥2 626 400, which is the effective-rate-only figure) |
| Enterprise tax 事業税 | 3.5 % × 4M + 5.3 % × 4M + 7.0 % × 2M (Tokyo rates slightly higher) | **¥492 000** |
| Special corp enterprise tax 特別法人事業税 | 37 % × 492 000 | **¥182 040** (JETRO ¥182 200) |
| **Total (excluding 均等割)** | | **¥2 625 912** ≈ JETRO ¥2 626 400 |
| **Total including 均等割** | | **¥2 695 912** |

The example exercises **five of the six components** in §1 (defense surtax
is FY 2026+, and the SME path skips the value-added / capital pro-forma
bases). Adding `均等割` is the only piece JETRO's "effective rate" view
omits — the very component note 107 §4.3 flagged.

## §3. Substrate fit — component-by-component

| § | Component | Substrate primitive | Verdict |
|---|---|---|---|
| 1 | National CIT (large) | `ts/flat 0.232M`; existing `CorporateIncomeTaxProvider` (`flat`-only) | **clean** |
| 1 | National CIT (SME 15 / 23.2 with ¥8M kink) | `ts/progressive [{:rate 0.15M :upper 8000000M} {:rate 0.232M :upper nil}]` | **clean** — but the *existing* `CorporateIncomeTaxProvider` is hard-wired to `ts/flat` (line 47); SME progressivity needs the provider's `:schedule` slot to be parameterised (note 107 §4.1's DE finding is identical — JP confirms it) |
| 1 | SME rate kick-up to 17 % when income > ¥1B | A formula on the schedule, OR a precondition that picks one of two `progressive` tables before the call; cleanest fit = `:formula` schedule that switches tables on `(if (> base 1e9) … …)` | **clean via `:formula`** — no substrate change |
| 2 | Local CIT (10.3 % × national CIT) | `ts/surtax-on 0.103M <national-liab>` already exists; emit as a separate component with `:composed-of [:corporate-income-tax]` | **clean** |
| 3 | Enterprise tax — SME income-base | A second `:corporate-income-tax` component with `:authority :jp-prefecture`, schedule `ts/progressive` over the 3.5/5.3/7.0 ladder | **clean** |
| 3 | Enterprise tax — large pro-forma (3 simultaneous bases) | THREE separate `:corporate-income-tax` components (income / value-added / capital), each with its own `:base-selector`, all carrying `:authority :jp-prefecture` and `:jurisdiction-specific-codes {:jp/enterprise-tax-base :income\|:value-added\|:capital}` | **clean — but with caveats**, see §4 stress B |
| 4 | Special corp enterprise tax (% × income-base enterprise tax) | A second `surtax-on` invocation; component `:composed-of [:corporate-income-tax]` with provenance pointing at the prior enterprise-tax component | **clean** |
| 5 | Inhabitants' income levy (7 % × national CIT) | `ts/surtax-on 0.07M <national-liab>` — same shape as local CIT | **clean** |
| 5 | Inhabitants' per-capita 均等割 | `:formula` schedule `(fn [_base _ctx] (lookup-tier (:tax-unit ctx)))` works today; the proposal is to add a first-class `:fixed-amount` schedule kind | **works via `:formula`; substrate addition `:fixed-amount` is OPTIONAL polish, see §4 stress A** |
| 6 | Defense surtax (4 % × (national CIT − ¥5M)) | `surtax-on` operates on a raw prior-liability; the ¥5M deduction is best expressed as `(ts/surtax-on 0.04M (max 0M (- prior 5000000M)))` inline, or as an adjustment-layer item with a base-aware `:amount` fn | **clean** — `apply-adjustments` already handles `:amount` as a fn-of-`{:base :gross :running}` (ADR-099 addendum 4); the ¥5M is just one subtraction in the fn body |
| NOL | 10-year carry-forward, 50 % cap for large | `:base-transform :adjustments` with a negative addition for the consumed NOL; the 50 % cap is min-of, computed once outside the substrate (consumer-side) — exactly DE's `Mindestbesteuerung` shape | **clean — same pattern DE uses; document it but do not re-implement** |

**Confirmation of note 107 §4.3's `:fixed-amount` proposal:** the per-capita
levy genuinely has no base, and modeling it as `:formula` works but reads
awkwardly (the `_base` ignore argument is the smell). However, an *equally
expressive alternative* exists with zero substrate change — the
`apply-adjustments` layer (ADR-099 addendum 4) already represents a
liability-mutating item with `:op :surtax` and a fn `:amount`, and a
`:surtax` of a `_running`-ignoring constant *is* a fixed addition. So the
per-capita levy can be modelled as:

```clojure
{:code :per-capita-levy :op :surtax
 :amount (fn [{:keys [tax-unit]}] (lookup-tier tax-unit))}
```

attached not to one of the upstream components but to a NEW
`:corporate-income-tax` component whose `:gross-liability` starts at 0 —
i.e. an adjustment-only component. That is ugly in a different way (a
"surtax on zero"). **Recommendation stands** — adopt the §4 stress A
disposition.

## §4. Abstraction stress — full catalogue

### Stress A — the per-capita inhabitants' levy 均等割 (note 107 §4.3, confirmed)

**Shape.** A fixed yen amount, indexed by `(:tax-unit ctx)` only — *no
base*. The 10-tier capital × headcount lookup is consumer-side data
(`(fn [{:keys [paid-in-capital headcount]}] …)`).

**Current substrate.** Expressible as `:formula` (works) or as an
adjustment item with a constant fn `:amount` (works, ugly).

**Proposed addition.** A 7th `:schedule/type` `:fixed-amount`:

```clojure
:fixed-amount        (:amount schedule)          ; in apply-schedule's case
```

plus a `(fixed-amount amt)` constructor. One line in `apply-schedule`, one
constructor, two tests. Membership in `schedule-types`.

**Verdict.** Adopt — same reasoning note 107 §4.3 gave. The conceptual
content ("a per-capita levy IS a schedule whose output ignores its base")
is worth a named kind; the cost is trivial.

### Stress B — multi-base enterprise tax for large companies (NEW finding)

**Shape.** Pro-forma 外形標準課税 for capital > ¥100M emits **three
liabilities from one statute, against three different bases** — taxable
income, value-added (報酬給与額 + 純支払利子 + 純支払賃借料 ± gain/loss),
and stated capital + capital reserve. The three components must all be
emitted, and their sum is what the prefecture bills.

**Current substrate.** Each of the three IS a `:corporate-income-tax`
component with its own `:base` and `:schedule`; nothing prevents one
`TaxReturnFacts` from carrying three of them. The `period-tax-kinds` enum
permits this. The CA T1 multi-authority pattern (federal + provincial in
one return) is the structural parallel.

**However:** the **value-added base** and the **capital base** are not
derivable from the kernel's `report/marginalize` over `:account-type`.
Value-added is a payroll + net-interest + net-rent + book-loss roll-up — a
hand-rolled marginalization over **specific accounts**, not a single
account-type bucket. Capital base is a balance-sheet snapshot of equity
sub-accounts. Both need bespoke base-selectors in the JP provider.

**Verdict.** **No substrate stress — but a documentation / provider
discipline finding.** kontor's `report/marginalize` is general enough; it
accepts any selector key. The l10n-jp provider will need to define
`:jp/value-added-base` and `:jp/capital-base` as classification keys
(probably as `:posting-dimension`s per ADR-097, or as
`:account-tag/concept-iri`s per ADR-090) so the marginalization is data-
driven and auditable, not a hand-rolled query inside the provider. Worth
calling out in the ADR text but does not change the substrate.

### Stress C — chained `surtax-on` references with deductions (NEW finding, minor)

**Shape.** The defense surtax (FY 2026+) is 4 % × (national CIT − ¥5M
basic deduction). `kontor.tax-schedule/surtax-on` is `rate ×
prior-liability` with no notion of a deduction on the prior liability.

**Current substrate.** `apply-adjustments` already handles a fn `:amount`
of `{:base :gross :running :tax-unit}` — and the consumer can compute
`(* 0.04M (max 0M (- prior 5000000M)))` inside that fn. So the algebra
covers it; the only loss is that the deduction is *implicit in a closure*
rather than *named structured data*.

**Verdict.** **Not worth a substrate change today.** This pattern recurs
across jurisdictions (UK ring-fence trade with a £50M deduction, etc.)
and may earn a `{:op :surtax :rate … :deduction …}` adjustment-item
shorthand later if more cases land. For Phase 3, the closure form is
acceptable; flag in the ADR with a forward note.

### Stress D — surtax on a non-top-level component (NEW finding, minor)

**Shape.** The special corporate enterprise tax (§1 component 4) is a %
of the **income-base enterprise tax**, not of the national CIT. So one
TaxReturnFacts must let an adjustment item reference *a specific other
component by index or code*, not just "the running total".

**Current substrate.** `apply-adjustments` folds over ONE running total —
the provider must build the chain manually, computing each component's
liability and feeding the right one into the next via plain Clojure.
That works (the JP provider is a regular function), but the `:composed-of
[<kind>]` field on a component is currently *informational* — it does
not enforce that the surtax was actually computed off the named prior.

**Verdict.** **Documentation finding only.** The provider's
responsibility to wire up correctly is fine; the audit trail in
`:composed-of` plus `:provenance` is sufficient. No substrate change.

### No other stress

A careful walk of national CIT (flat + progressive + SME-kick-up),
local CIT, enterprise tax (SME + pro-forma), special corp enterprise
tax, inhabitants' tax (income + per-capita), defense surtax, NOL with
50 % cap — surfaces **stresses A and B as substantive, C and D as
documentation-only**. The kernel algebra is otherwise sufficient.

## §5. Minimal substrate adds (consolidated)

Phase 3's JP CIT provider needs **one and only one** substrate change:

1. **Add `:fixed-amount` to `kontor.tax-schedule`** (stress A). ADR-099
   addendum 5. Concretely:
   - `apply-schedule` case clause: `:fixed-amount (:amount schedule)`;
   - constructor `(defn fixed-amount [amt] {:schedule/type :fixed-amount :amount amt})`;
   - membership in `schedule-types`;
   - two unit tests (returns amount; ignores base);
   - one golden test through the inhabitants' per-capita levy.

Everything else is l10n-jp module work:

2. **Generalise `CorporateIncomeTaxProvider`** (already required for DE/FR
   per note 107 §4) to take a `:schedule` config slot rather than a flat
   `:rate`. This is `kontor.corporate-income-tax`'s deferred refactor, not
   net-new substrate; the JP work shares it with DE/FR.

3. **`l10n-jp` provider** — `jp-corporate-income-tax-provider` emitting a
   multi-component `TaxReturnFacts` with: national CIT (`ts/progressive` +
   SME `:formula` kick-up), local CIT (`surtax-on`), enterprise tax
   (SME-`ts/progressive` OR three pro-forma components for large),
   special corp enterprise tax (`surtax-on` an enterprise-tax component),
   inhabitants' income levy (`surtax-on`), inhabitants' per-capita
   (`ts/fixed-amount` with consumer-supplied tier lookup), defense surtax
   (adjustment-layer item with `:amount` fn that applies the ¥5M
   deduction).

4. **`:tax-unit` schema** in JP: `{:filing-status :jp/sme | :jp/large
   :paid-in-capital <BigDecimal> :headcount <int> :prefecture <kw>
   :fiscal-year-start #inst}`. SME determination is consumer-supplied; the
   provider trusts the input.

5. **Document** value-added-base and capital-base classification keys
   (`:posting-dimension` or `:account-tag/concept-iri`) for the
   l10n-jp chart, so a pro-forma marginalization is data-driven (stress
   B).

## §6. Sources

- **PwC Worldwide Tax Summaries — Japan, Corporate Taxes on Corporate
  Income** (FY 2025-04-01 onward):
  https://taxsummaries.pwc.com/japan/corporate/taxes-on-corporate-income —
  rates, SME thresholds, effective-rate worked example, defense surtax
  ¥5M deduction.
- **PwC Worldwide Tax Summaries — Japan, Deductions** (NOL):
  https://taxsummaries.pwc.com/japan/corporate/deductions — 10-year
  carry-forward, 50 % cap for large corporations, SME exemption,
  one-year carry-back.
- **JETRO Section 3 — Setting Up Business, 3.3 Overview of Corporate
  Income Taxes**: https://www.jetro.go.jp/en/invest/setting_up/section3/page3.html
  — pro-forma three-base table, the 10-tier per-capita inhabitants'
  levy table, the ¥10M-income SME worked example.
- **National Tax Agency (NTA) 法人税法 §66** (national CIT rates):
  authoritative statute reference — quoted indirectly via PwC / JETRO,
  cite at implementation time.
- **Tokyo Metropolitan Bureau of Taxation 法人都民税** —
  per-municipality rate tables (used for the Tokyo numbers in §2);
  consumer-supplied at integration time.
- **note 107 §4.3** (the prior research-before pass that flagged
  `:fixed-amount` and which this note confirms in depth).
- **ADR-099 + addenda 1–4** (the substrate this note assesses):
  `kontor.period-tax-provider`, `kontor.tax-schedule`,
  `kontor.corporate-income-tax`.
- **`modules/l10n-jp/src/kontor/l10n_jp/period_tax_provider.clj`** —
  the *personal* income/inhabitant tax precedent that already proves
  the `:formula` workaround works for fixed per-capita additions on the
  individual side (line 188's `inhabitant-tax-schedule`); the corporate
  per-capita levy is the *scaled-up* same shape and earns first-class
  `:fixed-amount` because it is materially larger (¥70k–¥3.8M vs ¥6 000
  for individuals) and depends on `:tax-unit` data rather than always
  being one constant.

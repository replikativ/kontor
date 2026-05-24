---
date: 2026-05-24
title: 111 — CA T2 corporate income tax — statute-fit assessment for kontor Phase 3
status: research-before — note-104 Phase 3, CA T2 implementation track (ADR-106)
audience: maintainer + the Phase-3 CA agent
---

# 111 — CA T2: statute-fit against the kontor period-tax substrate

Note 104 Phase 3 names **CA T2** alongside DE / FR / JP as a corporate income tax
to build on top of the shipped `CorporateIncomeTaxProvider` (ADR-099). Note 107
§7-Q8 recommends adding it as **ADR-106** to keep the Phase-3 batch coherent,
on the assumption that "CA T2 is the simplest of the four — flat federal 15 %
+ provincial flat rates, identical shape to US 1120." This note tests that
assumption by walking the actual statute, a worked example, and the abstraction
fit. The conclusion: T2 fits the substrate **cleanly** if a corporation operates
in a single province; **multi-province income allocation** (Schedule 5) is the
genuine fit question, and it does **not** require a new substrate primitive —
it is *content* the provider computes from inputs.

---

## §1. Statute summary — what a normal CCPC actually files

### 1.1 The federal stack (Part I)

The CRA's [Part I tax computation](https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income)
is a *cascading reduction* of a notional 38 % base rate:

```
  38 %   basic federal rate (ITA §123)
− 10 %   provincial abatement — applies ONLY to taxable income earned in a
           Canadian province/territory; foreign-sourced income is excluded
           (this is the seam where Schedule 5 — §2 below — bites the federal
           number, not only the provincial one)
− 13 %   General Tax Reduction (ITA §123.4) — on full-rate taxable income
−  9 %   Small Business Deduction (ITA §125) — on the FIRST $500,000 of
           Canadian-earned active business income, for CCPCs only
```

Effective rates:
- Non-CCPC / income above the SBD limit: **15 %** federal.
- CCPC active business income within the **$500,000** business limit: **9 %**.
- The business limit phases out linearly from $10M to $50M of taxable capital
  (a CCPC big enough to be near-public loses the preference), and from
  $50,000 to $150,000 of passive investment income (the 2018+ "passive
  income grind").

### 1.2 Provincial CIT (all 13 provinces / territories)

Every province has its own corporate income tax — most flat, all with a
CCPC small-business carve-out that mirrors the federal SBD. 2025 rates per
[TaxTips.ca](https://www.taxtips.ca/smallbusiness/corporatetax/corporate-tax-rates-2025.htm):

| Province | General | Small | Limit |
|---|---|---|---|
| AB | 8.0 % | 2.0 % | 500k |
| BC | 12.0 % | 2.0 % | 500k |
| MB | 12.0 % | 0.0 % | 500k |
| NB | 14.0 % | 2.5 % | 500k |
| NL | 15.0 % | 2.5 % | 500k |
| NS | 14.0 % | 1.5 % | 700k (Apr 2025) |
| NT | 11.5 % | 2.0 % | 500k |
| NU | 12.0 % | 3.0 % | 500k |
| ON | 11.5 % | 3.2 % | 500k |
| PE | 15.0 % | 1.0 % | 600k (Jul 2025) |
| QC | 11.5 % | 3.2 % | 500k |
| SK | 12.0 % | 1.0 % | 600k |
| YT | 12.0 % | 0.0 % | 500k |

Two provinces — **QC** and **AB** — do **not** collect through the CRA;
the corporation files a separate return with Revenu Québec / Alberta TRA.
Operationally distinct, but for the kontor substrate the same shape: another
component with `:authority :qc-rq` or `:authority :ab-tra`.

### 1.3 CCPC status — the gating predicate

ITA §125(7): a CCPC is a private (non-listed) Canadian-resident corporation
not controlled by non-residents or by public corporations. Per
[Canada.ca / Practical Law](https://ca.practicallaw.thomsonreuters.com/9-568-7547),
CCPC status is **a configuration-time fact** about the corporation's
shareholders, not something computed from postings. It gates the SBD, the
SR&ED 35 % refundable rate, capital-dividend election, and several other
preferences. In kontor terms it is exactly the same shape as
US-1040 `:filing-status` or FR-IS `:sme?` (note 107 §4.2): a field on
`:tax-unit` in `context :inputs`.

### 1.4 SR&ED — the dominant CCPC tax credit

[T2 Sch 31 / SR&ED ITC](https://www.canada.ca/en/revenue-agency/services/scientific-research-experimental-development-tax-incentive-program/investment-tax-credit-policy.html):
- **CCPCs** earn an **enhanced 35 %** refundable ITC on qualifying current
  expenditures up to the expenditure limit (raised from $3M to $6M for
  tax years beginning after 2024-12-15).
- Non-CCPCs and CCPC expenditures over the limit: **15 %** non-refundable
  ITC. Carry forward 20 years, back 3.

This is exactly the `:credit` adjustment-layer shape — see §3 fit.

### 1.5 Losses, RDTOH, multi-province

- **Non-capital losses** (NCL): 20-year forward / 3-year back; against any
  income, ITA §111. T2 Schedule 4.
- **Net capital losses** (NCL): indefinite forward, capital-against-capital
  only. ITA §111(1)(b).
- **Part IV refundable tax / RDTOH** (the dividend-integration mechanism):
  CCPCs collect Part IV tax (38.33 %) on inter-corporate portfolio
  dividends + a refundable portion of Part I on investment income, all
  parked in a two-pool RDTOH balance, refunded on subsequent dividend
  payment. This is structurally **a carry-out from one period feeding a
  carry-in to the next** — exactly what note 105 §2 ("Frontier 2 — the
  carry") names as a deferred-until-demanded frontier. **Out of scope for
  Phase 3** (see §4 below).

---

## §2. Worked example — Acme Widgets Co. (CCPC, ON + AB)

**Source**: synthesised from
[PwC Canada Corporate Summaries](https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income)
+ [CRA Sch 5 guidance](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/you-have-complete-schedule-5.html)
+ [TaxTips 2025 rates](https://www.taxtips.ca/smallbusiness/corporatetax/corporate-tax-rates-2025.htm).

**Facts (TY2025)**:
- CCPC throughout; taxable capital < $10M; passive income < $50k. Full SBD
  preserved on the $500,000 business limit.
- Book profit (P&L): **CAD 600,000**. Schedule M-1-style add-backs net to
  +CAD 20,000 (50 % meals + non-deductible reserve). **Taxable income:
  CAD 620,000.**
- All income is active business income earned in Canada. Permanent
  establishments in **Ontario** (HQ) and **Alberta** (warehouse).
- Allocation factors (Sch 5 Part 1): Wages ON 700k / AB 300k (1,000k
  total). Gross revenue ON 1,800k / AB 1,200k (3,000k total).

**Sch 5 allocation** — average of (wage share, revenue share) per province:
- ON: (700/1000 + 1800/3000) / 2 = (0.70 + 0.60) / 2 = **0.65**
- AB: (300/1000 + 1200/3000) / 2 = (0.30 + 0.40) / 2 = **0.35**

Taxable income allocated: ON 620k × 0.65 = **403,000**; AB 620k × 0.35 = **217,000**.

**Federal Part I**:
- SBD income = min(active business income in Canada, taxable income,
  business limit) = min(620k, 620k, 500k) = **500,000**.
- At 9 %: **45,000**. Excess 120,000 at 15 %: **18,000**. Federal Part I:
  **63,000** (before refundable taxes and credits).

**Provincial — ON** (SBD 3.2 % up to 500k; general 11.5 %):
- ON's SBD pool is the provincial allocation times the federal SBD share —
  500k × (403/620) = **322,580** allocated SBD income.
- ON SBD tax: 322,580 × 3.2 % = **10,322.56**. Remainder (403,000 −
  322,580) = 80,420 × 11.5 % = **9,248.30**. ON tax: **19,570.86**.

**Provincial — AB** (filed separately with Alberta TRA; SBD 2 % up to 500k;
general 8 %):
- AB SBD allocation: 500k × (217/620) = **175,000**. Tax: 175,000 × 2 % = **3,500**.
  Remainder 217,000 − 175,000 = 42,000 × 8 % = **3,360**. AB tax: **6,860**.

**Total liability**: 63,000 + 19,570.86 + 6,860 = **89,430.86** CAD.

The arithmetic is unremarkable; the **shape** is what matters for the fit
assessment (§3).

---

## §3. Substrate fit — component-by-component

### 3.1 Federal CIT — fits `corporate-income-tax-provider` with `:progressive` schedule

The SBD-vs-general split is **structurally a two-bracket progressive** — the
same shape FR's 15 %/25 % SME stack uses, the same shape note 107 §4.2
recommended:

```clojure
;; for CCPCs:
{:schedule/type :progressive-bracket
 :brackets [{:rate 0.09M :upper 500000M}    ; SBD on first $500k
            {:rate 0.15M :upper nil}]}      ; general above
;; for non-CCPCs:
{:schedule/type :flat :rate 0.15M}
```

Schedule selection by `:tax-unit {:ccpc? true}` — `:formula` schedule wrapping
both, exactly the US 1040 / FR IS `:tax-unit`-driven dispatch (`l10n_us/period_tax_provider.clj:114-139`).
The Schedule M-1-style book-to-taxable add-backs ride
`:inputs :base-transform :adjustments` — already supported by
`tax-schedule/apply-base-transform`
(`src/kontor/tax_schedule.clj:142-163`) and exercised by the US 1120
provider. **Fits cleanly. No new substrate.**

The **business-limit phase-out** (passive-income grind / taxable-capital
grind) is a `:tax-unit`-time computation by the consumer: they pass the
*already-reduced* business limit as part of `:tax-unit`, and the provider
just uses that as the bracket `:upper`. Same posture US 1040 takes with
the standard deduction (computed externally, fed in).

### 3.2 Provincial CIT — fits as additional components on one `TaxReturnFacts`

Exactly the multi-component fan-out the existing CA T1 wrapper already
demonstrates (`modules/l10n-ca/.../period_tax_provider.clj:36-79` — federal
+ BC428 as two components on one `TaxReturnFacts`). For T2 the same pattern
generalises: one `:corporate-income-tax` component per province where the
corporation has a permanent establishment. The provincial allocation
fraction lives in the component's `:jurisdiction-specific-codes` or
`:line-items`, and each component carries its own (province-specific)
`:base`, `:schedule`, and `:liability`.

**This is the key answer to the open fit question:** federal-and-provincial
T2 lands as **one federal component + N provincial components** on one
`TaxReturnFacts`, where N = number of provinces with a permanent
establishment. The current CA T1 wrapper has N=1 (the filer's province of
residence). T2 has N≥1 by the same mechanism — **no schema change**,
just a longer `:components` vector.

QC and AB filing through their own tax administrations is purely an
`:authority` value (`:qc-rq`, `:ab-tra`) — the substrate already lets
authority vary per component (`period_tax_provider.clj:76-80`).

### 3.3 CCPC status — fits `:tax-unit`

Note 107 §4.2 already established the pattern for FR IS' SME carve-out:
the eligibility predicate is *not* derivable from postings, so it rides
`context :inputs :tax-unit {:sme? true}`. CA T2 needs the same:
`{:ccpc? true :passive-investment-income … :taxable-capital …}`. The
provider reads `:ccpc?` to select the schedule and reads the latter two
to compute the (possibly reduced) business limit. **Fits cleanly. Same
mechanism in three jurisdictions (US filing-status, FR SME, CA CCPC) —
the pattern is robust.**

### 3.4 SR&ED — fits the adjustment layer (frontier 1, note 105)

Refundable 35 % CCPC ITC and non-refundable 15 % ITC are exactly the
two cases `apply-adjustments` distinguishes via `:refundable?`
(`tax-schedule.clj:192-235`). The credit is a function of qualifying
expenditures (an `:inputs` quantity, not derived from postings), and a
function the *running tax* (it can push refundable below zero) — the
`fn`-of-ctx `:amount` shape applies. **Fits cleanly. Already shipped.**

### 3.5 Multi-province allocation — the genuine fit question

This is where the assumption "CA T2 is the simplest of the four" needs
testing. Two facts make multi-province allocation non-trivial:

1. The Sch-5 allocation formula `(wages-share + revenue-share) / 2` reads
   data that is **not in the GL by itself** — wages per province AND
   revenues per province. Wages-per-province can be marginalised via
   `kontor.report/marginalize` over `:analytic-account/state` (the
   payroll modules already write that — ADR-077 US, etc.), or over a
   province-tagged `:posting-dimension` (ADR-097). Revenue-per-province
   needs a similar dimension on revenue postings.

2. The allocation fraction **flows into the federal Part I as the
   provincial-abatement scope** (ITA §124 — the 10 % abatement applies
   only to "taxable income earned in a province"). So the federal
   computation is NOT independent of the provincial allocation — the
   federal component's `:base` and the per-province components' `:base`
   are *coupled*.

**Does this need a `:jurisdiction-allocation` mechanism in the
substrate?** **No.** The coupling is *intra-provider*: the
`ca-corporate-income-tax-provider` does the marginalisation, computes the
fractions, and emits coupled components — exactly the same way the US
1040 provider couples its filing-status-driven schedule and standard
deduction inside one provider call. The `TaxReturnFacts` shape carries
the per-component result; it does not need to model the dependency edges
between components (frontier 3 — the tax graph — would, but note 105
defers that until DE Gewerbesteuer ↔ KSt demands it).

A new posting-dimension key — call it `:posting-dimension/ca-province`
or generalise to `:posting-dimension/subdivision` — should be **declared
by the CA provider's documentation** as a precondition: the consumer
tags revenue postings with the province they were earned in, payroll
already tags wages by state/province (per ADR-077 / etc.), and the
provider's allocation step calls `marginalize` over both axes. **No
schema change** — ADR-097's `:posting-dimension` is an open axis set;
the CA provider just picks a key.

---

## §4. Abstraction stress — specific findings

**Finding 1 — no substrate stress for the routine T2 surface.** Federal
+ provincial CIT, SR&ED, the SBD carve-out, and book-to-taxable
add-backs all map directly onto shipped primitives
(`:progressive-bracket`, `:tax-unit`-keyed schedule selection,
`apply-base-transform :adjustments`, `apply-adjustments` with
`:refundable?`, multi-component `TaxReturnFacts`).

**Finding 2 — multi-province allocation is content, not abstraction.**
It reads two marginalisations (wages-by-province, revenue-by-province)
and combines them via a closed-form formula inside the provider. The
provincial-abatement coupling to the federal component lives **inside
the provider call**, not in `TaxReturnFacts`. This rules out a new
`:jurisdiction-allocation` substrate primitive — it would be
single-jurisdiction-specific over-fitting.

**Finding 3 — the same `:posting-dimension` convention should generalise.**
Payroll modules write `:analytic-account/state` (US per ADR-077, AU per
ADR-080, CN `:cn-province` per ADR-085, IN per ADR-083). CA T2 needs
the same axis on revenue postings, with the same convention. Worth a
sentence in `doc/conventions.md` ("when a jurisdiction allocates tax by
subdivision, the convention is `:posting-dimension/<cc>-<subdivision>`
on revenue + payroll postings") — but **not** a new substrate primitive.
The dimension key is l10n content.

**Finding 4 — RDTOH / Part IV is genuinely Phase-4.** The
refundable-dividend mechanism is a *carry* (note 105 §2 — frontier 2):
RDTOH at period end is the carry-out to next period's carry-in, and
the refund-on-dividend is a *state transition* not a single-period
computation. Building it cleanly demands the deferred carry-frontier
generalisation. Out of Phase-3 scope; note 105 already names this as
"deferred until corporate loss carryforward demands it" — CCPCs hitting
RDTOH meaningfully are exactly that demand signal, so the Phase-4
sequencing is right.

**Finding 5 — non-capital and capital loss carry-forward is the same
deferral.** 20-year NCL forward, indefinite net-capital-loss forward
(capital-against-capital only). Both are *carries*; both are the
frontier-2 work. Phase 3 can ship CA T2 with `:loss-carryforward` as an
`:inputs` quantity (the consumer supplies the brought-forward NCL pool,
the provider applies it as a `:base-transform :adjustments` deduction
and reports the resulting carry-out in `:line-items` — the same
ad-hoc convention note 103 §3a used for capital-loss carry-in). The
frontier-2 substrate work lifts that into a first-class carry when
demand stacks (CIT loss-carryforward + capital-gains carry-pool + JP
inhabitant-tax base-period together justify the generalisation).

**Bottom line — fits cleanly.** Zero new substrate primitives are
required for routine T2 CCPC + single-or-multi-province + SR&ED. The
two deferred items (RDTOH, multi-year loss carry as a true Mealy fold)
are the **same** frontier-2 work note 105 already named — CA T2 is one
more demand signal, not a new substrate question.

---

## §5. Minimal substrate adds (preferred: none)

**None required for Phase 3.** Specifically:

- No `:jurisdiction-allocation` mechanism. The allocation is a
  provider-internal computation over `marginalize` results.
- No new `:schedule/type`. The `:progressive-bracket` + `:formula` for
  CCPC dispatch are sufficient.
- No new `:tax-unit` field at the substrate level — `:tax-unit` is an
  opaque map by design (`period_tax_provider.clj:131-133`); the CA
  provider documents `{:ccpc? :passive-investment-income
  :taxable-capital}` as its expected keys.
- No schema change. `:posting-dimension/<cc>-<subdivision>` is an
  l10n-side dimension key on ADR-097's open axis set.

**Useful documentation-side adds** (no code):

- A `doc/conventions.md` paragraph naming the `:posting-dimension/
  <cc>-<subdivision>` convention so CA T2, US state CIT (Phase 4),
  IN-state PT (already shipped), CN-province surtax (ADR-085) all
  cohere.
- The Phase-3 ADR-106 docstring teaches the `:tax-unit` keys CA T2
  expects, mirroring how the US 1040 provider teaches `:filing-status`.

---

## §6. Sources

**kontor substrate** (file:line):
- `src/kontor/corporate_income_tax.clj:38-89` — the CIT substrate.
- `src/kontor/period_tax_provider.clj:67-100` — `TaxReturnFacts`.
- `src/kontor/tax_schedule.clj:142-235` — `apply-base-transform` +
  `apply-adjustments`.
- `modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:40-49`,
  `:60-165` — US 1120 reference + US 1040 `:tax-unit`-keyed schedule.
- `modules/l10n-ca/src/kontor/l10n_ca/period_tax_provider.clj:36-90`
  — the existing CA federal+provincial fan-out (T1) pattern T2 mirrors.
- `modules/l10n-ca/src/kontor/l10n_ca/y2024/{constants.clj,t1.clj}` —
  the CA y2024 immutable-module style ADR-106 will follow.
- `doc/decisions.md` — ADR-031 (entity), ADR-095/098 (verb facade,
  commitment), ADR-097 (posting-dimension axis), ADR-099 (PeriodTaxProvider).
- `doc/research/104-tax-completion-individual-to-corporation.md` — the
  Phase-3 mandate.
- `doc/research/105-the-algebra-of-a-tax.md` — frontiers 1/2/3; carry
  deferral context.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — §7-Q8
  recommends adding CA T2 as ADR-106.

**Canadian tax — external**:
- [PwC Tax Summaries — Canada, Corporate, Taxes on Corporate Income](https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income)
  — the 38 / −10 / −13 / −9 cascade.
- [CRA — Corporation tax rates](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/corporation-tax-rates.html)
  — federal rates + business-limit reductions.
- [CRA — If you have to complete Schedule 5](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/you-have-complete-schedule-5.html)
  — the multi-province allocation requirement.
- [CRA — Income Tax Folio S4-F3-C2, Provincial Income Allocation](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/folio-3-general-principles-business-income-calculation/income-tax-folio-s4-f3-c2-provincial-income-allocation.html)
  — Regulation 402 / 2-factor formula doctrine.
- [TaxTips.ca — 2025 Corporate Tax Rates](https://www.taxtips.ca/smallbusiness/corporatetax/corporate-tax-rates-2025.htm)
  — all 13 provincial rates + SBD limits.
- [Canada.ca — SR&ED Investment Tax Credit Policy](https://www.canada.ca/en/revenue-agency/services/scientific-research-experimental-development-tax-incentive-program/investment-tax-credit-policy.html)
  — 35 % refundable CCPC / 15 % non-refundable; expenditure-limit raised to $6M.
- [CRA — T2SCH4 Corporation Loss Continuity](https://www.canada.ca/en/revenue-agency/services/forms-publications/forms/t2sch4.html)
  — 20-year NCL, indefinite NCL, 3-year back.
- [CRA — About Refundable Dividend Tax on Hand (RDTOH)](https://www.canada.ca/en/revenue-agency/services/e-services/digital-services-businesses/business-account/about-refundable-dividend-tax-on-hand-rdtoh-balances.html)
  — the two-pool (ERDTOH / NERDTOH) post-2018 mechanism (deferred work).
- [Practical Law — CCPC definition](https://ca.practicallaw.thomsonreuters.com/9-568-7547)
  — ITA §125(7) CCPC test.

---

End of note 111.

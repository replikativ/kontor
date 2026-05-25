---
date: 2026-05-24
title: 151 — JP investment-income tax — substrate fit assessment for Phase C2
audience: maintainer + the Phase C2 `jp-investment-income-provider` implementation agent
status: research-before for the JP investment-income companion of ADR-099 / ADR-101; no code
---

# 151 — JP investment-income tax: substrate fit for Phase C2

Japanese investment-income tax is, structurally, **the optionality
problem made statutory**. The same listed-share dividend can be taxed
**three different ways at the holder's choice** —  申告不要 (withholding-
only, 20.315 % flat), 申告分離課税 (separate self-assessment, same
20.315 % but offsettable against listed-securities losses), and
総合課税 (aggregation into the progressive 5–45 % brackets, with a
配当控除 dividend-tax-credit on the back end). Interest is mostly
locked at 20.315 % final withholding. NISA wraps everything in an
exemption envelope. iDeCo defers. The 大口株主 3 %-ownership cliff
**forces** the aggregation path. And a tax reform 12 years ago (2013)
collapsed the bond-interest universe into a single 申告分離 lane.

This note (a) summarises the JP investment-income regime per income
class + per election, (b) walks two worked examples, (c) assesses
whether the kontor period-tax substrate (ADR-099 + the existing JP
PIT + JP CGT providers + ADR-101 statute-as-data) carries it, (d) names
concrete data gaps, (e) sketches `jp-investment-income-provider`
(actually: TWO providers in a sibling pattern), (f) shows how it
coordinates with the shipped `jp-cgt-provider` and `jp-income-tax-provider`,
and (g) cites sources.

**Bottom line: the substrate carries JP investment-income cleanly.**
The 復興 surtax pattern from `jp-cgt-provider` reuses verbatim. The
total existing schema gap is **ZERO**. The provider sketch adds **ONE
new namespace** to `kontor-l10n-jp` (the investment-income provider
plus its statute file) and **ONE small extension** to the period-tax
`:inputs` shape (the election-axis carrier — `:jp-dividend-election`).

---

## §1. The JP investment-income regime — three taxes, four lanes, three elections

### 1.1 The 4-lane income map (individuals)

JP carves investment income into four primary lanes, each with its
own statutory hook:

| Lane | Statute | Default treatment | Election? |
|---|---|---|---|
| **配当所得** (haitō shotoku, dividends) | 所得税法 §24 + 租特法 §8-4 / §8-5 / §9-3 | 20.315 % final withholding by paying agent (申告不要) for listed shares | Three-way: 申告不要 / 申告分離 / 総合 |
| **利子所得** (rishi shotoku, interest) | 所得税法 §23 + 租特法 §3 / §3-3 | 20.315 % final withholding by paying agent | Limited (bond interest can elect 申告分離 to offset listed losses) |
| **公社債等の譲渡益・償還差益** (kōshasai-tō, public bond capital gain + redemption profit) | 租特法 §37-11 / §37-15 | 20.315 % 申告分離 (mandatory post-2016) | None — fixed at 申告分離 |
| **割引債の償還差益** (warikibichai, discount-bond redemption profit) | 租特法 §41-12-2 | Withheld 20.315 % at redemption; final | None |

The election cliffs are the JP-unique structural feature — the same
1,000,000 ¥ dividend can produce **three different liabilities** for
the same taxpayer depending on the election. The provider has to
make that election a first-class input.

### 1.2 The three dividend elections — 配当所得 in detail

For listed-share dividends ONLY (上場株式等の配当):

**(a) 申告不要 (no-declaration, default).** Paying agent withholds at
20.315 % composite (15 % national + 0.315 % 復興 reconstruction surtax
on the national portion + 5 % local 住民税). Holder files nothing.
The 20.315 % is **final** — no further income-tax obligation; the
dividend does NOT enter 課税総所得金額; the holder cannot apply
losses against it.

**(b) 申告分離課税 (separate self-assessment, elective via 確定申告).**
Same composite 20.315 % rate, but the dividend joins the **listed-
securities compartment** for offset purposes — current-year and
3-year-carryforward losses on listed shares (see jp-cgt-provider
note 115 §1.4) reduce the dividend base. The 20.315 % already
withheld becomes a `:prepaid` against the assessed liability; the
balance refunds. Mutually exclusive WITH the 総合課税 election (one
or the other per filing year, across ALL listed dividends).

**(c) 総合課税 (aggregation, elective).** Dividend enters 課税総所得金額
at full grossed amount and is taxed at the 7-bracket marginal rate
(5/10/20/23/33/40/45 % national + 10 % 住民税). The 配当控除
(dividend-tax-credit, see §1.3) is then applied as a **tax credit
against the national income tax**. The 20.315 % already withheld
becomes a `:prepaid`.

The **economic break-even** between (a) and (c) sits at roughly
課税所得 ¥9 M (the 23 % bracket): below, (c) wins for individuals
who can apply the 配当控除; above, (a) usually wins.

**Important post-2023 constraint:** until tax year 2022, the
**national** election (申告不要 vs 申告分離 vs 総合) could differ from
the **inhabitant-tax** election (e.g. file 総合 for national, leave
申告不要 for inhabitant). That arbitrage is **gone from TY 2023**;
the national + inhabitant elections must now match per 地方税法
改正. The substrate carries ONE election per dividend per year, not
two.

### 1.3 配当控除 (haitō kōjo, dividend-tax-credit)

`所得税法 §92`. Only fires when the **総合課税** election is made.
The deduction rate depends on **(asset class, 課税総所得金額)**:

**Asset class — domestic-corporation cash dividends:**

| 課税総所得金額 | Rate (national income tax) | Rate (住民税) |
|---|---|---|
| ≤ ¥10 M | 10 % of dividend amount | 2.8 % |
| > ¥10 M (on the SLICE of dividend that pushes total over ¥10 M) | 5 % of that slice | 1.4 % |

**Asset class — domestic securities-investment-trust distributions
(non-foreign-asset trust):**

| 課税総所得金額 | Rate (national income tax) | Rate (住民税) |
|---|---|---|
| ≤ ¥10 M | 5 % of distribution | 1.4 % |
| > ¥10 M slice | 2.5 % | 0.7 % |

**Foreign-asset securities investment trust** (currency-hedged, etc.):
half the above (2.5 % / 1.4 % / 1.25 % / 0.7 % across the same income
tiers).

**Excluded entirely** (no 配当控除): J-REIT distributions
(`基金利息`), foreign-corporation dividends, distributions from
private placement bond-fund trusts, distributions to taxpayers who
elected 申告分離 or 申告不要 (the credit is a 総合課税-only relief).

The credit is **non-refundable** — it cannot drive the national
income tax below zero. It IS allowed to interact with prior credits
(housing-loan, foreign-tax-credit) per §92(2).

### 1.4 The 大口株主 3 % cliff — election forced off

`租特法 §8-4(1)` strips the 申告分離 / 申告不要 election from any
shareholder whose ownership is **≥ 3 % of issued shares OR voting
rights** at the record date. Such a holder MUST aggregate
(総合課税) — the 20.42 % national WHT becomes a prepaid against
marginal tax + 配当控除. (Note the WHT rate is 20.42 % not 20.315 %
for the 3 %+ holder — `租特法 §9-3-2` does not apply, so the 復興
surtax of 0.42 % attaches to a base 20 % rather than the listed-
share 15 %.) From TY 2023, this cliff applies "look-through" via
**§ 8-4(1)第二項** — voting/ownership held by a **same-family** /
**parent-controlled corporation** counts toward the 3 % test, closing
a long-standing planning loophole.

For the substrate: the consumer's `:disposal/asset-class` analog for
income — call it `:jp-dividend-class` — needs values
`:listed-non-major / :listed-major-3% / :unlisted / :j-reit / :foreign /
:investment-trust-domestic / :investment-trust-foreign`. Different
classes drive different election availability + different 配当控除
rates.

### 1.5 利子所得 (interest income) — the 申告分離 / 申告不要 binary

**Bank-deposit interest** (普通預金, 定期預金), **money-market
fund interest**, **specified-account corporate-bond interest**: paying
agent withholds 20.315 % (15 % + 0.315 % + 5 %), no filing required,
no further obligation. Cannot offset against anything.

**Listed-bond interest** (上場公社債 including JGBs) and **specified-
account bond interest**: post-2016 reform (note 115 §1.5 mentions in
passing) routed through the same 申告分離 lane as listed shares —
the holder MAY file 申告分離 to apply listed-share losses against
the interest, mirroring the dividend election. Default is 申告不要.

**Foreign-currency bank-deposit interest**: 20.315 % withheld on the
yen-equivalent at the credit date. Exchange-rate movement is
separately a 雑所得 (miscellaneous income) and DOES enter the
progressive brackets — the bank does not withhold on the FX gain.

**The aggregation election does NOT exist for interest** (with one
narrow exception: bond interest paid OUTSIDE the JP withholding
system — typically foreign-broker-held bonds — defaults to 雑所得 at
marginal rates). Interest cannot ride the 配当控除.

### 1.6 NISA + iDeCo — the tax-wrapped envelopes

**NISA (少額投資非課税制度)** — post-2024 reform shape (the "新NISA"):

| Envelope | Annual investment-limit | Lifetime cumulative cap | Holding-period limit |
|---|---|---|---|
| つみたて投資枠 (tsumitate, "accumulation slot") | ¥1.2 M / year | ¥18 M lifetime (counted across both slots) | None — perpetual non-taxation |
| 成長投資枠 (seichō, "growth slot") | ¥2.4 M / year | Cap of ¥12 M lifetime (within the ¥18 M total) | None |

So a couple can shelter up to ¥7.2 M / year combined (¥3.6 M each).
Dividends, capital gains, interest, all of it inside a NISA account
is **100 % tax-exempt** — neither withheld at source (broker
identifies the position as NISA) nor entering any declaration. The
3-year listed-loss carryforward (note 115 §1.4) DOES NOT APPLY to
NISA losses — a NISA-held disposal at a loss is simply ignored;
the loss does not transfer outside the envelope.

**Substrate posture**: NISA-shielded positions show up in the GL but
their dividends and gains are **NOT** investment income for the
provider — they are exempt. The consumer flags the position
(`:account/jp-nisa-account? true` on the position account or
similar) and the provider's σ_E base-selector filters them out.

**iDeCo (個人型確定拠出年金)** — 確定拠出年金 (DC) pension wrapper:

- **Contributions deductible** at full amount from 課税総所得金額
  via 小規模企業共済等掛金控除 (`所得税法 §75`); annual cap depends on
  employment status (¥276,000 – ¥816,000 / year per
  確定拠出年金法).
- **In-account growth tax-exempt** while held.
- **Withdrawal taxed**:
  - Lump-sum at withdrawal → 退職所得 (retirement income, separate
    schedule with 退職所得控除).
  - Annuity → 公的年金等 (public-pension category, separate
    deduction + brackets).
- Crucially: the provider's investment-income lane does NOT touch
  iDeCo balances — they live in a separate income category
  (退職所得 or 公的年金等) and emerge only on withdrawal.

**Substrate posture**: same as NISA — flag the iDeCo account, σ_E
filters out interest + dividends; on withdrawal, route through the
appropriate non-investment-income category.

### 1.7 Foreign dividends and the 外国税額控除

Foreign-corporation dividends do NOT receive the 配当控除. They DO
enter the same election framework:

- **申告不要** is NOT available — the JP paying agent's withholding
  is the agent's withholding NOT the foreign WHT, and foreign WHT
  is not "final" in the JP sense; the holder MUST declare.
- **申告分離 vs 総合課税**: foreign-dividend election parallels the
  domestic-listed case, with the same 20.315 % flat for 申告分離 or
  marginal-bracket-plus-no-credit for 総合.
- **外国税額控除 (foreign-tax-credit)** under 所得税法 §95: the foreign
  WHT (treaty-capped, typically 10–15 %) credits against the JP tax
  on the same income, subject to the §95(1) per-country limitation
  (`foreign-tax × foreign-income / world-income`). Excess carries
  forward 3 years.

For the substrate: foreign dividend = ordinary `:jp-dividend-class
:foreign`; the consumer supplies the foreign-WHT amount in
`:inputs :jp-foreign-tax-paid {<country-iso> <Money>}`; the
provider folds it as a `:credit :refundable? false` in the
adjustment-layer of whichever component the dividend lands in
(申告分離 or 総合).

### 1.8 Corporate-side investment income — the 受取配当等の益金不算入

Domestic corporations receiving dividends from other domestic
corporations are NOT taxed on the full amount — `法人税法 §23` (and
its J-REIT / 私募 carve-outs in §23-2) allows a **partial
exclusion** (受取配当等の益金不算入) of the dividend from taxable
income, calibrated to the ownership stake:

| Ownership | Exclusion |
|---|---|
| ≥ 100 % (kanzen-shihai) | 100 % |
| ≥ ⅓ (kanren-hōjin) | 100 % less directly-allocable interest |
| > 5 % – < ⅓ (other shareholdings) | 50 % |
| ≤ 5 % | 20 % |

This is a **CIT-side** treatment, not investment-income-provider
territory — it folds into the JP CIT provider (ADR-106 / note 110)
via a `:base-deduct` adjustment. Outside the scope of this note;
mentioned for completeness.

---

## §2. Worked examples

### Example A — Mr. Tanaka, listed-share dividend, three elections compared

Mr. Tanaka, Tokyo resident, salaried at ¥8 M gross. He receives
¥1,000,000 in dividends from JP-listed Toyota Motor (large public
corp, no ownership-cliff issue). His broker withheld 20.315 % =
¥203,150 at payment. Other deductions / credits aside, what's his
year-end position under each election?

**Setup** (deductions roughly approximated for clarity):

- Gross salary ¥8 M; employment-income deduction = ¥1.94 M (per
  `jp-income-tax-provider` `employment-income-deduction` for 8 M);
  personal deductions = ¥0.48 M basic + ¥0.4 M social-insurance
  premium (rounded). So 課税所得 (excluding dividend) ≈ ¥5.18 M
  → 20 % bracket.
- Dividend ¥1 M arrives WITH ¥203,150 already withheld.

**(a) 申告不要**: nothing to file for the dividend. His income tax
on the salary stands at progressive-schedule on ¥5.18 M:

  ¥1.95 M × 5 % + ¥1.35 M × 10 % + ¥1.88 M × 20 % = ¥97,500 +
  ¥135,000 + ¥376,000 = **¥608,500 national income tax** + 復興
  surtax 2.1 % = ¥12,778 → ¥621,278; 住民税 ≈ ¥518,000 + per-capita
  ¥6,000. Dividend tax stands at the withheld ¥203,150 (¥150,000
  national + ¥3,150 reconstruction + ¥50,000 local).

  **Total tax on dividend: ¥203,150 final.** Marginal cost = 20.315 %.

**(b) 申告分離**: same total ¥203,150 BUT now refundable if Mr. Tanaka
has unused listed-share losses (he doesn't here, so no change to the
final tax). Useful only when carry-forward losses exist.

**(c) 総合課税**: ¥1 M dividend joins the salary base. New 課税所得
= ¥5.18 M + ¥1.0 M = ¥6.18 M.

  Progressive schedule: ¥1.95 M × 5 % + ¥1.35 M × 10 % + ¥2.88 M × 20 %
  = ¥97,500 + ¥135,000 + ¥576,000 = ¥808,500.

  配当控除 (Mr. Tanaka's total taxable income is under ¥10 M):
  10 % × ¥1 M = ¥100,000 against national income tax.
  Net national tax: ¥808,500 − ¥100,000 = ¥708,500.
  復興 surtax: 2.1 % × ¥708,500 = ¥14,879.
  Total national: ¥723,379. **Increase over election (a)'s national
  income tax of ¥621,278 = ¥102,101** national-side.

  住民税 with the same dividend in base: ¥6.18 M × 10 % + ¥6,000 =
  ¥624,000 vs (a)'s ¥524,000 = +¥100,000; the 住民税 配当控除 is
  2.8 % × ¥1 M = ¥28,000; net 住民税 = ¥596,000 vs (a)'s
  (¥518,000 + ¥50,000 withheld on dividend) = ¥568,000 → +¥28,000.

  Total tax under (c): ¥723,379 + ¥596,000 + ¥6,000 = ¥1,325,379.
  Total tax under (a) (counting the withheld dividend portion):
  ¥621,278 + ¥518,000 + ¥6,000 + ¥203,150 = ¥1,348,428.

  **(c) saves ¥23,049 vs (a)** for Mr. Tanaka. The crossover
  happens because his marginal rate (20 % national + 10 % local =
  30 %) MINUS the 配当控除 (10 % + 2.8 % = 12.8 %) = 17.2 % effective
  on the dividend — below the 20.315 % flat. Mr. Tanaka should elect
  (c). At ¥12 M 課税所得 (33 % bracket) the elective math flips —
  (a)/(b) would win.

Substrate trace for election (c): ONE `JpInvestmentIncomeFacts` with
- `:dividend-amount ¥1,000,000`
- `:dividend-class :jp-listed-non-major`
- `:election :sogo` (the aggregation election)
- `:withheld ¥203,150` (paying-agent WHT — becomes `:prepaid`)
- `:foreign-tax-paid 0M`

The provider feeds the dividend into the existing
`jp-income-tax-provider` via `:pit-base-additions [1000000M]` (the
gross amount), AND emits a `:credits [{:code :jp-haitō-kōjo :op
:credit :refundable? false :amount 100000M}]` for the 配当控除. The
住民税 side rides the same wiring to `jp-inhabitant-tax-provider`
with the 2.8 % credit. The 復興 surtax fires automatically inside
`jp-income-tax-provider` (it's already a per-component adjustment
in the JP PIT provider — note 102 §6).

### Example B — Mr. Suzuki, mixed lanes including NISA and listed-bond interest

Mr. Suzuki, Yokohama resident, salaried at ¥10 M, received:
- ¥800,000 dividends from a J-REIT (大和ハウスリート) outside any
  shelter.
- ¥400,000 dividends from JP-listed Mitsubishi Heavy (regular
  account, non-NISA, < 3 % ownership).
- ¥300,000 dividends from foreign Apple ADRs (regular account); US
  withheld 10 % treaty rate = $30 / ¥4,500 equivalent.
- ¥200,000 interest from a JGB held in 特定口座.
- ¥100,000 dividends inside his 成長投資枠 NISA position in
  Sony Corp.
- ¥50,000 interest from his bank.

**Election analysis:**

- J-REIT ¥800,000: 配当控除 NOT available; election still possible
  but no credit. He elects 申告不要 — clean.
- Mitsubishi ¥400,000: < 3 % owner, listed; he elects 申告分離 because
  he has a ¥300,000 carryforward from a 2024 listed-share loss
  (note 115 §1.4). Net base: ¥400,000 − ¥300,000 = ¥100,000 ×
  20.315 % = ¥20,315 owed; minus ¥81,260 withheld → ¥60,945
  refundable.
- Apple ADR ¥300,000: foreign, no 配当控除. He elects 申告分離 (same
  rate, simpler than 総合). Foreign WHT ¥4,500 becomes a 外国税額
  控除 against his JP tax on Apple. Net JP tax: ¥300,000 ×
  20.315 % = ¥60,945; minus ¥4,500 foreign-tax-credit = ¥56,445
  owed.
- JGB ¥200,000: locked at 20.315 % final withholding = ¥40,630
  final.
- NISA dividend ¥100,000: exempt; ignored entirely.
- Bank interest ¥50,000: locked at 20.315 % final withholding =
  ¥10,158 final.

**Substrate trace**: SIX `JpInvestmentIncomeFacts` items + the bank
interest passes through automatically as 申告不要-withheld (the
provider doesn't even emit a component for it — withholding IS the
return, no period-tax-facts needed). The NISA position is filtered
out at σ_E (the position-account-flag the consumer set). The two
申告分離 components produce ONE merged 申告分離 component on the
`TaxReturnFacts` (the listed-securities and foreign sub-lots
combined per filing). The J-REIT 申告不要 produces a separate
"informational" component with `:liability 0M :prepaid 162,520M`
(the dividend × 20.315 %), no further computation — it documents
that the holder elected (a) and the tax stands at the WHT.

The complexity is "real" but the substrate carries it: ONE provider
returns a multi-component `TaxReturnFacts` (one component per
election-lane the holder used), each component a self-contained
`base / schedule / prepaid / credits / surtaxes` slot per the
existing `kontor.period-tax-provider` shape.

---

## §3. Substrate fit assessment

### 3.1 ADR-099 `PeriodTaxProvider` shape — clean fit

`period-tax-facts` returns one `TaxReturnFacts` with N
`:components`. Each component carries its own `:base / :schedule /
:gross-liability / :credits / :surtaxes / :liability / :prepaid /
:provenance / :jurisdiction-specific-codes / :line-items`. This is
exactly what the JP investment-income story needs:

- 申告不要 dividend → component `{:kind :withholding :authority :jp-nta
  :base <dividend> :schedule (ts/flat 0.15M) ... :prepaid <withheld>
  :liability <same as schedule output>}` — the consumer sees a balanced
  prepaid/liability line.
- 申告分離 dividend → component `{:kind :capital-gains-tax (yes,
  REUSE the existing enum — the listed-securities compartment of
  jp-cgt-provider is the same line) ... :composed-of [:jp-haitō-bunri]}`.
- 総合課税 dividend → the dividend is FED INTO `jp-income-tax-provider`
  via `:pit-base-additions [N]` (the same fan-in pattern
  `ca-cgt-provider` uses for capital gains).
- 利子所得 → mostly silent; only emits a component for the rare
  申告分離-elected bond-interest lane.

### 3.2 The `kontor.tax-schedule` algebra — `:flat` + adjustment fold

The 20.315 % composite is `:flat 0.15M` (national) + `:flat 0.05M`
(local) + the 復興 0.21M × national-running adjustment. The JP CGT
provider already implements this exact pattern (note 115 §5 + the
provider file's `regime-component` helper at line 313). The
investment-income provider literally copies the schedule-and-surtax
wiring; only the base-selector and the election handling differ.

### 3.3 ADR-101 statute-as-data — natural home for the rate table

Rate evolution is real here: the 20.315 % rate was 10.147 % before
2014 (the so-called "Maruyu" half-rate sunset for listed shares),
became 20.315 % from 2014, and the 復興 0.21 % part sunsets in 2037.
The 配当控除 rate ladder hasn't moved in years but the 1,000万円
threshold has been politically discussed every other budget. All of
this fits cleanly into the ADR-101 `:parameter / :parameter-value`
shape with `:effective-from` keys, exactly as `jp-cgt-statute`
already encodes the CGT rate ladder.

`:provision`-shape candidates from this regime:

- `JP-Shotokuzeihō-§92` (配当控除) — a `:non-refundable-credit` keyed
  on `:condition [:eq :election :sogo]` + `:condition
  [:lte :total-taxable-income 10000000M]` for the unscaled rate; a
  second provision for the > 10M slice with the half rate.
- `JP-Shotokuzeihō-§95` (foreign-tax-credit) — a `:non-refundable-
  credit` with `:condition [:has-input :jp-foreign-tax-paid]`.
- `JP-Sotokuhō-§8-4` (3 %-cliff) — a `:elective-regime` that, when
  the consumer-supplied `:dividend-class` is `:jp-listed-major-3%`,
  FORCES the `:election :sogo` and rejects the 申告分離 / 申告不要
  alternatives. (Substrate stress: see §4 — this is the one new
  provision-side pattern.)
- `JP-FUKKO` — the 復興 2.1 % surtax — REUSES verbatim the provision
  the JP CGT provider already registers. Same compute-fn key.

### 3.4 ADR-101 Addendum 1 `:schedule-override` — the 大口株主 cliff

The 3 % ownership cliff (§1.4) is precisely a `:schedule-override` use
case (Addendum 1 §1): when the disposal/income carries
`:jp-dividend-class :jp-listed-major-3%`, override the 申告分離 /
申告不要 schedules with the progressive PIT schedule. The provider
pattern is: build the disposal-style classification, check class +
elections, raise an error if the election clashes with the cliff,
else swap the schedule. **No new substrate primitive needed.**

### 3.5 σ_E base-selector — needs a `:position-shelter?` filter

The base-selector for dividend/interest income needs to filter
NISA-held and iDeCo-held positions out. The shipped
`personal-income-tax/gross-income` (line 37-46) marginalizes by
`:account-type :income`; it does not yet support a position-level
shelter filter. **This is a real (small) substrate gap** — see §4.

Two design options:
1. **Account-level discrimination.** Tag the position account
   (`:account/jp-nisa? true` on the NISA-wrapped account); the
   base-selector excludes accounts so tagged.
2. **Posting-level dimension.** Tag the posting with
   `:posting-dimension/shelter :jp-nisa | :jp-ideco | :none`
   (ADR-097); the marginalizer filters by dimension.

Recommendation: **option 2** (ADR-097 dimensions). NISA cap-tracking
needs **per-posting** memory: the same broker account holds both
NISA and 一般 lots, and the broker reports them separately
already (the consumer just lifts the broker's tag onto the posting
dimension). Option 1 forces a per-shelter ledger account which
duplicates the broker's chart.

### 3.6 Cross-provider coordination

THREE existing providers + ONE new provider compose:

- `jp-investment-income-provider` (NEW) — emits its own components
  for the withholding / 申告分離 paths AND emits 総合課税 contributions
  as `:pit-base-additions [<dividend>]` + `:pit-credits
  [{:code :jp-haitō-kōjo ...}]` for downstream consumption.
- `jp-income-tax-provider` (SHIPPED, note 102 §6) — reads the
  `:pit-base-additions` and `:pit-credits` from the consumer's
  threading layer.
- `jp-inhabitant-tax-provider` (SHIPPED, note 102 §6) — same.
- `jp-cgt-provider` (SHIPPED, note 115) — coordinates with the
  investment-income provider through the SHARED carryforward
  `:inputs :capital-loss-carryforward {:jp-listed-securities ...}`:
  listed-share losses offset BOTH dividends (申告分離 election) and
  listed-share gains, drawn from the same pool. The consumer's
  return-assembly layer pulls from one pool, the providers must NOT
  both consume it. **This is the one cross-provider coordination
  concern** — see §4.

---

## §4. Concrete data gaps

Four gaps total — **ZERO substrate-schema changes**; one ADR-097
dimension convention; one ADR-099 `:inputs`-shape extension; two
companion-namespace conventions.

1. **`:posting-dimension :jp-shelter` convention** — values
   `#{:jp-nisa-tsumitate :jp-nisa-seichō :jp-ideco :unsheltered}`
   per posting carrying investment income (or the underlying
   purchase posting at lot-creation). The base-selector filters out
   non-`:unsheltered` postings when computing investment-income
   gross. Schema-side: nothing — ADR-097 already supports arbitrary
   dimension values.

2. **`:inputs :jp-dividend-elections`** — a map keyed by
   `:jp-dividend-class` (or by individual transaction-id) of the
   consumer's election decision. Shape:
   ```
   {:jp-listed-non-major   :申告分離       ; or :申告不要 / :総合課税
    :jp-listed-major-3%    :総合課税        ; the only valid choice
    :unlisted              :総合課税        ; the only valid choice
    :j-reit                :申告不要
    :foreign               :申告分離
    :investment-trust-domestic :総合課税}
   ```
   The provider validates each election against the class's
   permitted set (rejects `:申告不要` when class is `:jp-listed-
   major-3%`, etc.) and threads the chosen schedule. The natural
   ADR-099 `:inputs` extension; the existing PIT provider already
   accepts arbitrary `:inputs` keys.

3. **Shared carry-forward pool coordination** — the `:capital-loss-
   carryforward {:jp-listed-securities <Money>}` is consumed by
   BOTH `jp-cgt-provider` (listed-share gains) AND
   `jp-investment-income-provider` (申告分離-elected listed
   dividends + listed-bond interest). The consumer's return
   assembly must:
   - Compute CGT first (it has stronger statutory claim to the
     pool — `租特法 §37-12-2` orders the offset against listed-
     security gains BEFORE dividends).
   - Pass the post-CGT residual to the investment-income provider
     via the SAME `:capital-loss-carryforward` key.
   - The investment-income provider emits its own residual back to
     the consumer for next-year carry.
   No schema change; pure consumer-orchestration convention; needs
   one paragraph in the provider docstring.

4. **`:jp-foreign-tax-paid` `:inputs` map** — per-foreign-country
   foreign-tax credit input. Shape:
   ```
   {:jp-foreign-tax-paid {"US" #money :JPY 4500
                          "GB" #money :JPY 7800}}
   ```
   The provider applies the §95 per-country limit (foreign-tax ×
   foreign-income / world-income) and emits a `:credit :refundable?
   false :amount <capped>` for each. Excess carries forward 3 years
   via `:inputs :jp-foreign-tax-carryforward {<country> <Money>}`,
   echoed back. Pure `:inputs`-shape; ADR-099 supports arbitrary
   keys.

**Bottom line**: ZERO kernel changes. ZERO companion-schema
additions. ONE ADR-097 dimension convention (`:jp-shelter`). THREE
`:inputs`-shape conventions (`:jp-dividend-elections`,
`:jp-foreign-tax-paid`, `:jp-foreign-tax-carryforward`). The 配当
分類 (`:jp-dividend-class`) lives in the COMPANION namespace as a
`def asset-classes`-style constant on the new provider (mirroring
`ca-cgt-provider`'s `asset-classes` set at line 103-119).

---

## §5. `jp-investment-income-provider` sketch

### 5.1 Single provider, multi-component `TaxReturnFacts`

Mirror `jp-cgt-provider`'s architecture. ONE provider, ONE
`TaxReturnFacts` per call, one component per active election-lane.

Component fan-out:

| Lane | Emits | Schedule | Sample `:line-items` |
|---|---|---|---|
| `:申告不要 dividend` (listed, non-major) | A `:kind :withholding` informational component | `(ts/flat 0.15M)` national + `(ts/flat 0.05M)` local | gross, withheld, "no further obligation" |
| `:申告分離 dividend` | A `:kind :capital-gains-tax` component (REUSES the existing JP CGT compartment for offset purposes) | Same as above, but `:base` = post-carry-offset | gross, carry-applied, withheld, refund-or-top-up |
| `:総合課税 dividend` | NO own component; emits `:pit-base-additions` + `:pit-credits` for consumer threading into `jp-income-tax-provider` | n/a (downstream) | gross, 配当控除-amount, foreign-tax-credit-amount |
| `:申告不要 interest` (bank / non-listed bond) | Informational component, same as 申告不要 dividend | same | gross, withheld, "final" |
| `:申告分離 interest` (listed bond) | Same as 申告分離 dividend, same schedule | same | gross, carry-applied, withheld, refund-or-top-up |
| `:foreign-dividend 申告分離` | Same as 申告分離 dividend PLUS `:credits [{:code :jp-foreign-tax-credit}]` | same | gross, foreign-tax-credit, foreign-tax-residual |

### 5.2 The election validator

Before classification, the provider validates each consumer-supplied
election against the dividend-class's permitted set:

```
permitted-elections =
  {:jp-listed-non-major          #{:申告不要 :申告分離 :総合課税}
   :jp-listed-major-3%           #{:総合課税}                   ; the cliff
   :unlisted                     #{:総合課税}                   ; no listed regime
   :j-reit                       #{:申告不要 :申告分離 :総合課税} ; no 配当控除
   :foreign                      #{:申告分離 :総合課税}          ; no 申告不要
   :investment-trust-domestic    #{:申告不要 :申告分離 :総合課税}
   :investment-trust-foreign     #{:申告不要 :申告分離 :総合課税}
   :listed-bond-interest         #{:申告不要 :申告分離}
   :bank-interest                #{:申告不要}}                  ; locked
```

An invalid election raises an `ex-info` (mirrors the JP CGT provider's
"unknown `:kind`" rejection at line 502-503).

### 5.3 The 配当控除 compute-fn

A `kontor.statute` compute-fn keyed `:jp-haitō-kōjo`. Reads
`(:total-taxable-income ctx)` (which the provider stages by
pre-aggregating salary + sōgō dividends BEFORE applying credits),
applies the two-tier rate, returns the credit amount. The provision
fires only when `:election :sogo`.

The function signature mirrors `jp-cgt-reconstruction-surtax`
(provider line 220-230): a closure that captures `ctx` and returns
a fn `ctx-w-running → amount`. The `:total-taxable-income` is
sourced from `ctx :inputs :pre-credit-taxable-income` — the
consumer threads it from a first pass of `jp-income-tax-provider`
(or computes it inline; the provider docstring documents both
paths).

### 5.4 The 外国税額控除 compute-fn

A second compute-fn keyed `:jp-foreign-tax-credit`. Reads
`(:foreign-tax-paid ctx)` and `(:foreign-income ctx)` and
`(:world-income ctx)`, computes the per-country §95 limit. Excess
flows to `:jurisdiction-specific-codes :foreign-tax-carryforward-out`
for the consumer to next-year-input.

### 5.5 The constructor

Two callable shapes (mirror JP CGT):

```clojure
(jp-individual-investment-income-provider {:id :jp-inv-income-individual})
;; ^ for individuals — emits the election-lane components.

(jp-corporate-investment-income-provider {:id :jp-inv-income-corporate})
;; ^ for corporations — folds into CIT via :cit-base-additions /
;; :cit-base-deductions (the 受取配当等の益金不算入 deduction lives
;; here as a `:provision`).
```

### 5.6 Statute file shape

`modules/l10n-jp/src/kontor/l10n_jp/investment_income_statute.clj`,
mirroring `cgt-statute.clj`:

```
[parameters]                   ;; rates + thresholds, ~10 entries
- "JP.InvIncome.national-rate-listed"  (15 %, effective 2014-)
- "JP.InvIncome.local-rate-listed"     (5 %)
- "JP.InvIncome.haitō-kōjo-low-rate"   (10 %)
- "JP.InvIncome.haitō-kōjo-high-rate"  (5 %, applies to slice > 10M)
- "JP.InvIncome.haitō-kōjo-threshold"  (10000000M)
- "JP.InvIncome.haitō-kōjo-jūmin-low-rate"   (2.8 %)
- "JP.InvIncome.haitō-kōjo-jūmin-high-rate"  (1.4 %)
- "JP.InvIncome.haitō-kōjo-trust-domestic-low-rate"  (5 %)
- "JP.InvIncome.haitō-kōjo-trust-foreign-low-rate"   (2.5 %)
- "JP.InvIncome.major-shareholder-threshold-pct"     (3 %)

[parameter-values]
;; each with :effective-from + citation

[provisions]                   ;; ~5-8 entries
- :jp-haitō-kōjo-default              (10 % credit, gated on election + threshold)
- :jp-haitō-kōjo-slice                (5 % on the over-threshold slice)
- :jp-major-shareholder-cliff         (forces :total-taxable election)
- :jp-haitō-kōjo-investment-trust     (5 % / 2.5 % at half rate)
- :jp-foreign-tax-credit              (§95)
- :jp-fukko-surtax                    (REUSE the JP CGT provision)
```

---

## §6. PIT / CGT coordination

### 6.1 Pool consumption order — the 申告分離 cascade

When the holder has:
- ¥X carry-in listed-loss
- ¥A listed-securities gain (CGT)
- ¥B 申告分離-elected listed dividend
- ¥C 申告分離-elected listed-bond interest

Statutory order (`租特法 §37-12-2`):
1. Net A against X first (in JP CGT compartment).
2. Residual loss → reduce B (dividends).
3. Residual loss → reduce C (bond interest).
4. Final residual → carry forward 3 years.

Substrate orchestration: the consumer calls `jp-cgt-provider` first
(it reads `:capital-loss-carryforward :jp-listed-securities X` and
emits a residual under its own
`:jurisdiction-specific-codes :residual-loss`), then calls
`jp-investment-income-provider` with `:capital-loss-carryforward
:jp-listed-securities <residual>`, which folds it across B then C.
The final residual emerges in the investment-income provider's
`:jurisdiction-specific-codes :residual-loss`, which the consumer
echoes to next-year `:inputs`.

This is exactly the existing JP CGT provider's `regime-component`
shape (lines 313-404, especially the `:residual-loss` field at line
402). The investment-income provider re-uses the carry shape
verbatim.

### 6.2 配当控除 timing — the 総合課税 dependency on PIT

The 配当控除 needs `:total-taxable-income` (the income-tax base
**including** the dividend) to choose 10 % vs 5 % per the threshold
test. The PIT provider's `:base-transform` already computes this in
its `:gross-income → :taxable-income` stage (line 67-68). Two
orchestration patterns:

**Pattern A — one-pass.** The investment-income provider, when
emitting `:pit-base-additions`, ALSO supplies a 配当控除 estimate
based on an `:inputs :pre-credit-taxable-income` the consumer
pre-computes. The PIT provider just sums the credits supplied via
`:pit-credits`.

**Pattern B — two-pass.** Consumer calls PIT provider first WITHOUT
the dividend, gets `:taxable-income`, supplies it as
`:pre-credit-taxable-income` to the investment-income provider,
then re-calls PIT WITH the `:pit-base-additions` and
`:pit-credits`. (Same pattern as ADR-101 Addendum 1's "two-pass
query" for IN §115BAA, KR / CN qualification cliffs.)

**Recommendation**: Pattern B is cleaner and exists as a documented
ADR-101 substrate pattern. Pattern A is OK for the simple case
(no foreign credits, no carry-in) but does not generalise to the
foreign-tax-credit case which also needs the same `:total-taxable
-income`.

### 6.3 With `jp-cgt-provider` and the 復興 surtax

The 復興 surtax provider (`:jp-cgt-reconstruction-surtax`,
provider line 220-230) is REGISTERED by `jp-cgt-provider` at load
time (line 232-239). The investment-income provider uses the SAME
provision (`JP-FUKKO`) without re-registering — the `kontor.statute`
catalog is shared. The component's `:pass :national` ctx fires the
provision against the national-rate component only, exactly as JP
CGT already orchestrates.

### 6.4 Net schema impact

| Slot | Owner | Existing? | This note's ask |
|---|---|---|---|
| `:tax-concept` enum | KERNEL (ADR-101) | yes | no change — `:non-refundable-credit`, `:elective-regime`, `:refundable-credit` suffice |
| `:provision` shape | KERNEL (ADR-101) | yes | no change — `:schedule-override` (Addendum 1) covers the 3% cliff |
| `:parameter` shape | KERNEL (ADR-101) | yes | no change |
| `:posting-dimension` (ADR-097) | KERNEL | yes | new convention only: `:jp-shelter` axis with `#{:jp-nisa-tsumitate :jp-nisa-seichō :jp-ideco :unsheltered}` values |
| `:inputs :jp-dividend-elections` | ADR-099 `:inputs` | open map | new key |
| `:inputs :jp-foreign-tax-paid` | ADR-099 `:inputs` | open map | new key |
| `:inputs :pre-credit-taxable-income` | ADR-099 `:inputs` | open map | new key |
| `:jurisdiction-specific-codes :foreign-tax-carryforward-out` | ADR-099 component | open map | new key |
| `jp-investment-income-provider` namespace | NEW companion file | no | new namespace |
| `jp-investment-income-statute` namespace | NEW companion file | no | new namespace |

ZERO kernel changes; ZERO existing-companion-schema changes; TWO
new companion files; FIVE `:inputs` / `:jurisdiction-specific-codes`
key conventions; ONE ADR-097 dimension convention.

---

## §7. Sources

JP statutory:
- 所得税法 §23 (利子所得), §24 (配当所得), §75 (小規模企業共済等掛金控除
  — iDeCo contribution deduction), §92 (配当控除), §95 (外国税額控除)
- 租税特別措置法 §3 (利子所得 separate withholding), §3-3 (公社債等
  interest specifics), §8-4 (配当所得 separate self-assessment +
  3 %-shareholder cliff), §8-5 (申告不要 default), §9-3 (上場株式等
  dividend 20.315 % rate), §9-3-2 (3 %+ holder ineligibility),
  §37-11 / §37-15 (公社債 capital gain + redemption 申告分離),
  §37-12-2 (3-yr loss carryforward on listed shares & dividends),
  §41-12-2 (割引債 redemption)
- 復興特別所得税 — 東日本大震災復興特別措置法 (2.1 % surtax)
- 確定拠出年金法 (iDeCo)
- 措置法 NISA — 租特法 §37-14 (旧 NISA) → 2024-reform 新NISA per
  令和 5 年度 税制改正

NTA authoritative:
- [No.1250 配当所得があるとき (配当控除)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1250.htm)
  — the official 配当控除 rate ladder, threshold rules,
  asset-class differentiation (cash dividends vs trust-distributions
  vs foreign-asset trusts vs J-REIT exclusion).
- [No.1330 配当金を受け取ったとき (配当所得)](https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1330.htm)
  — the three-way election framework + the 3 %-shareholder cliff.
- [No.1331 上場株式等の配当等に係る申告分離課税制度](https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1331.htm)
  — the 申告分離 mechanics for listed dividends.
- [NISA特設サイト 金融庁](https://www.fsa.go.jp/policy/nisa2/know/index.html)
  — official 新NISA limits and rules from FSA.

Reference / commentary (en):
- [PwC Worldwide Tax Summaries — Japan Individual: Income determination](https://taxsummaries.pwc.com/japan/individual/income-determination)
  — three-way election summary; 20.315 % breakdown; foreign dividend
  treatment; iDeCo / NISA outline.
- [PwC Japan Corporate: Withholding taxes](https://taxsummaries.pwc.com/japan/corporate/withholding-taxes)
  — confirms the 3 %-shareholder 20.42 % WHT (no 復興 reduction
  because the base is 20 % not 15 %).
- [JETRO Section 3.9 — Setting Up Business / International Tax](https://www.jetro.go.jp/en/invest/setting_up/section3/page9.html)
  — the 受取配当等の益金不算入 ownership ladder for corporations.
- [Ministry of Finance — Overview of Taxation on JGBs](https://www.mof.go.jp/english/policy/jgbs/topics/taxation2016/1.html)
  — the post-2016 公社債 reform that unified the 申告分離 regime.
- [JSRI Securities Taxation Chapter XIV](https://www.jsri.or.jp/publish/english/pdf/english_2024/2024_14.pdf)
  — comprehensive English overview of post-2014 listed-securities
  + listed-bonds taxation including elections, loss-offsetting.
- [EY Japan 2026 Tax Reform — Financial-Income Provisions](https://www.ey.com/content/dam/ey-unified-site/ey-com/en-jp/technical/tax-alerts/2026/pdf/ey-japan-tax-alert-11-march-2026-en.pdf)
  — FY2026 financial-income changes outline.

Reference / commentary (ja):
- [Daiwa Securities — 配当金・分配金の税金: 証券税制トピックス](https://www.daiwa.jp/seminar/study_tax/tax_zeisei/)
  — election decision matrix; break-even by marginal rate.
- [SMBC日興証券 — 上場株式等の配当課税](https://www.smbcnikko.co.jp/service/tax_sys/stock/haitou.html)
  — concrete numerical examples of the three elections.
- [freee — 配当控除とは: 確定申告すべきケース + 計算シミュレーション](https://www.freee.co.jp/kb/kb-kakuteishinkoku/dividend-deduction/)
  — worked examples at varied income levels.
- [野村ホールディングス — 第4章 有価証券と税金](https://www.nomuraholdings.com/jp/investor/shareholders/tax/main/09/teaserItems1/0/linkList/0/link/tax_4_1.pdf)
  — investor-facing flowchart for dividend election.
- [マネーフォワード — 配当控除と確定申告での計算方法](https://biz.moneyforward.com/tax_return/basic/2317/)
  — the > 10M slicing formula with worked numbers.
- [SBI証券 — 新NISAの上限額・限度額](https://go.sbisec.co.jp/media/report/nisaideco_topic/nisa_limit_241111.html)
  — the new NISA ¥3.6M / ¥18M envelope structure.
- [楽天証券 — 新NISA 年間投資枠 + 非課税保有限度額](https://www.rakuten-sec.co.jp/web/nisa/limit/)
  — tsumitate / seichō slot mechanics + the lifetime cap rules.

kontor substrate cited:
- `src/kontor/personal_income_tax.clj:37-46` — the `gross-income`
  base-selector that the investment-income provider would extend
  with a shelter filter (or that the consumer pre-filters via
  ADR-097 dimensions).
- `src/kontor/personal_income_tax.clj:61-118` — the
  `PersonalIncomeTaxProvider` shape that the 総合課税 path feeds
  via `:pit-base-additions` + `:pit-credits`.
- `src/kontor/period_tax_provider.clj:44-61` — the closed
  `period-tax-kinds` enum; the new investment-income provider's
  components carry `:kind :withholding` (new value? — see §4) or
  `:kind :capital-gains-tax` (existing); `:kind :personal-income-tax`
  is reserved for the downstream PIT provider.
  **Open ADR-099-addendum question**: do we want a new
  `:kind :withholding-tax` enum value to distinguish 申告不要
  components from PIT components? Recommendation: yes, the
  withholding lane is statutorily distinct.
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor; used
  exactly the same way as `jp-cgt-provider`.
- `src/kontor/tax_schedule.clj` `apply-adjustments` — the credit /
  surtax fold the provider invokes for 配当控除 / foreign-tax-
  credit / 復興.
- `src/kontor/statute.clj:423-460` — `apply-provisions` returning
  `:base-items` / `:tax-items` / `:schedule-overrides` / `:provisions`;
  the provider invokes it once per component pass.
- `src/kontor/statute.clj:532-560` — `compose-greater-of` (NOT
  used here; mentioned only to confirm we don't need a MAT-style
  composition for the JP dividend story — the three elections are
  consumer-chosen, not an algorithmic max).
- `modules/l10n-jp/src/kontor/l10n_jp/period_tax_provider.clj` — the
  shipped JP national + inhabitant income-tax providers; the
  investment-income provider feeds them as a sibling.
- `modules/l10n-jp/src/kontor/l10n_jp/cgt_provider.clj` — the
  shipped JP CGT provider; the investment-income provider mirrors
  its structure (line 433-503: the multi-component fan-out), the
  Jan-1 measurement is absent here (not relevant to dividends),
  and the 復興 provision is REUSED verbatim.
- `modules/l10n-jp/src/kontor/l10n_jp/cgt_statute.clj` — the
  statute file shape the investment-income statute mirrors.
- `doc/research/115-jp-cgt-fit.md` — the JP CGT note this companion
  closes the loop with; §1.4 (loss-offset compartments) names the
  shared listed-securities pool that BOTH providers consume.
- `doc/research/102-period-tax-provider-design.md` §6 / §9-E — the
  base-period mechanic the JP inhabitant-tax provider uses; same
  mechanic applies if a future revision wires the dividend-side
  inhabitant-tax fan-out separately (current sketch folds inhabitant
  via the 住民税 portion of the 20.315 % composite — simpler).
- `doc/research/107-phase-3-incorporation-and-disposal.md` — the
  ADR-101 statute-as-data substrate that the new statute file
  populates.

---

End of note 151.

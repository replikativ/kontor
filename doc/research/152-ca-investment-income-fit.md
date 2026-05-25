---
date: 2026-05-24
title: 152 — CA investment-income tax — substrate fit assessment for Phase C2
audience: maintainer + the Phase C2 `ca-investment-income-provider` implementation agent
status: research-before for the CA investment-income companion of ADR-099 / ADR-101; no code
---

# 152 — CA investment-income tax: substrate fit for Phase C2

Canadian investment-income tax is, **structurally**, the integration
problem: corporations have already paid corporate tax on the income
that funded the dividend, and the individual taxpayer's regime
**reconstructs** the corporate side as a notional credit so the
combined corporate + personal tax matches what a comparable
unincorporated business would pay. This is the **gross-up + dividend
tax credit** (DTC) mechanic. The complexity is **two-tier** — the
eligible / non-eligible split — because the corporate tax rate that
funded the dividend depends on whether the issuer was a public/
high-tax CCPC (eligible, full corporate tax → high gross-up + high
DTC) or a small CCPC paying SBD rates (non-eligible, low corporate
tax → low gross-up + low DTC).

Atop that integration substrate sit four orthogonal stories:
- **Interest** — fully ordinary at marginal, no integration relief.
- **Foreign dividends** — get NO DTC (no Canadian corporate tax to
  integrate), gross + foreign-tax-credit instead (§126 capped at 15 %).
- **Tax wrappers** — TFSA / RRSP / FHSA / RPP / DPSP — shield the
  income from current taxation entirely (TFSA, FHSA-qualifying-
  withdrawal) or defer it until withdrawal (RRSP, RPP).
- **Rental income** — property income at marginal rates, the CRA
  facts-and-circumstances test that converts it to **business
  income** when "additional services" are provided, the CCA-cannot-
  create-or-increase-loss restriction that quietly silos rental from
  the loss-bucket lattice.

This note (a) summarises the CA investment-income regime per income
class + per wrapper, (b) walks two worked examples, (c) assesses
substrate fit against ADR-099 + ADR-101 + the shipped CA PIT/CIT/CGT
providers + the prior-art `kontor.l10n-ca.y2024.s4` Schedule-4 module,
(d) names the data gaps, (e) sketches the
`ca-investment-income-provider`, (f) shows how it coordinates with
the existing PIT/CGT providers, (g) cites sources.

**Bottom line: the substrate carries CA investment-income cleanly.**
The DTC story maps to the kernel's `:non-refundable-credit` adjustment-
layer item; the gross-up maps to `:base-add`. The shipped
`kontor.l10n-ca.y2024.s4` is a frozen-year prior-art reference (TY2024
rates: 1.38× / 6/11 federal eligible + 1.15× / 9/13 federal
non-eligible) that proves the math works; the new substrate-shaped
provider lifts the same calculus into the ADR-099 + ADR-101 fold and
generalises it across provinces. ZERO kernel changes; ZERO existing-
companion-schema changes; the only new substrate touchpoints are the
`:inputs` shape extensions, the ADR-097 `:ca-shelter` dimension
convention (analogous to JP NISA — see note 151), and the two new
companion files (`ca-investment-income-provider` + statute).

---

## §1. The CA investment-income regime — the integration math, four lanes, four wrappers

### 1.1 The 4-lane income map (individuals)

CA classifies investment income into four primary lanes:

| Lane | Statute | Treatment | Where it lands on T1 |
|---|---|---|---|
| **Eligible dividends** (publics + high-tax CCPCs) | ITA §89(1) (definition), §82(1)(b)(ii), §121(a) (federal DTC) | 1.38× gross-up + 15.0198 % federal DTC + provincial DTC | Line 12000 (taxable amount) → bracket; DTC at line 40425 |
| **Non-eligible dividends** (low-tax CCPC dividends) | ITA §89(1), §82(1)(b)(i), §121(a) | 1.15× gross-up + 9.0301 % federal DTC + provincial DTC | Line 12000 → bracket; DTC at line 40425 |
| **Interest income** (deposits, bonds, GICs, T-bills) | ITA §12(1)(c) + s.12(4) accrual rule | Full amount taxable at marginal rate; NO credit, NO gross-up | Line 12100 (S4 part II) → bracket |
| **Rental income** (real estate) | ITA §9 (if business) / §3(a)(ii) (if property) | Net rental income (rent − expenses − optional CCA) at marginal; CCA cannot create a loss; possible business-income recharacterisation | Line 12600 (gross) + Line 12600 (net via T776); BUSINESS income → Line 13500 via T2125 |

These four lanes coexist with the **two corporate-income wrappers**
(eligible/non-eligible dividends) and the **two pension/savings
wrappers** (TFSA, RRSP/FHSA/RPP/DPSP — see §1.6). The provider's job
is to land each lane into the right slot of the downstream PIT (T1)
or CIT (T2) machinery.

### 1.2 The dividend integration math — eligible

`ITA §82(1)(b)(ii)` + `§121(a)`:

```
gross-up                = 1.38 × actual dividend received
taxable dividend amount = actual + gross-up portion = 1.38 × actual
                           (sits at line 12000)
federal DTC             = (6/11) × gross-up portion
                        = (6/11) × 0.38 × actual
                        = 0.2073 × actual          (round 20.73 %)
                        OR equivalently 15.0198 % of TAXABLE dividend
provincial DTC          = province-specific (see §1.4)
```

The **rationale** (Income Tax Folio S3-F2-C2 §1.40): the gross-up
notionally reverses the corporate-tax discount that funded the
dividend. A public corp pays ~15 % federal + ~12 % provincial ≈
27 % combined corporate tax. From $100 pre-tax profit, $73 remains
to distribute. The gross-up restores the original $100; the DTC
credits back the corporate tax notionally paid. The "integration"
target: combined corporate + personal tax ≈ unincorporated
marginal rate. Federal integration sits very close to neutral for
eligible dividends; provincial integration varies (slight under- or
over-integration per province).

### 1.3 The dividend integration math — non-eligible

`ITA §82(1)(b)(i)` + `§121(a)`:

```
gross-up                = 0.15 × actual dividend received
taxable dividend amount = 1.15 × actual
federal DTC             = (9/13) × gross-up portion
                        = (9/13) × 0.15 × actual
                        = 0.1038 × actual          (round 10.38 %)
                        OR equivalently 9.0301 % of TAXABLE dividend
```

The smaller gross-up and DTC reflect the lower corporate rate
(~9 % federal + ~3 % provincial ≈ 12 % combined SBD-rate corporate
tax) that funded the non-eligible dividend.

### 1.4 Provincial DTC rates — 2026

Each province piggybacks on its own brackets, applying a provincial
DTC keyed to the GROSSED-UP amount (i.e. line 12000). The rates
vary by province + by year:

| Province | Eligible DTC (% of grossed-up) | Non-eligible DTC (% of grossed-up) |
|---|---|---|
| **Ontario** | 10.00 % | 2.9863 % |
| **British Columbia** | 12.00 % | 1.96 % |
| **Alberta** | 8.12 % | 2.18 % |
| **Quebec** | 11.70 % | 3.42 % |
| Federal (for context) | 15.0198 % | 9.0301 % |

(Sources: TaxTips.ca eligible/non-eligible DTC tables; KPMG Tax
Facts 2025-2026. The Ontario non-eligible rate dropped from 3.2863 %
to 2.9863 % effective 2025, harmonising with Ontario's small-business
rate change. Quebec applies its DTC on a slightly different base via
TP-1 form D — taxonomically, it's still the grossed-up amount,
expressed differently on the form.)

So the **combined** (federal + provincial) DTC ranges from
~23 % (eligible, AB) to ~27 % (eligible, BC) on the grossed-up
amount. As a fraction of the actual dividend received, the eligible
DTC is roughly 32–37 % depending on province.

### 1.5 Interest income — no integration relief

`ITA §12(1)(c)`: interest is included in income on a "received,
receivable, or accrued" basis. Tax treatment: full inclusion at
marginal rate. No gross-up; no credit. The only special wrinkles:

- **Accrual rule** (§12(4)): for "investment contracts" the holder
  must include accrued interest at each anniversary date, even if
  the interest has not been paid. Closes a deferral loophole for
  zero-coupon bonds and compound-interest investments.
- **Indexed bonds** (real-return bonds): the inflation-compensation
  component is treated as interest, not capital gain.
- **Stripped coupons**: separately taxable as accrued interest per
  Income Tax Folio S3-F10-C2 ¶1.71-1.75.

For the substrate: interest is a `:base-add` to PIT/CIT with no
credit. Folio S3-F10-C2 covers the accrual + stripped-coupon
specifics; the substrate trusts the consumer-supplied accrued
amount and does not enforce the §12(4) anniversary mechanics.

### 1.6 Tax-wrapped envelopes — TFSA, RRSP, FHSA, RPP/DPSP

**TFSA (Tax-Free Savings Account)** — ITA §146.2:

- **Contributions**: NON-deductible from income. Capital must come
  from already-taxed dollars.
- **Inside-account growth**: Tax-exempt. Dividends, interest, gains,
  all exempt.
- **Withdrawals**: Tax-free. Withdrawn principal restores
  contribution room the NEXT calendar year.
- **2026 annual contribution room**: $7,000. Cumulative room since
  inception (eligible adults from 2009): $102,000.

**RRSP (Registered Retirement Savings Plan)** — ITA §146:

- **Contributions**: Deductible from income (`§60(i)`); reduces
  current-year tax. Annual limit: lesser of 18 % of prior-year
  earned income OR the indexed cap. 2026 limit: **$33,810** (up
  from $32,490 in 2025), plus carry-forward of unused room.
- **Inside-account growth**: Tax-deferred. No current taxation.
- **Withdrawals**: Fully taxable at marginal rate. Withdrawn capital
  + investment income lump into ordinary income.
- **Special withdrawal programs**: Home Buyers' Plan (HBP, up to
  $60k tax-free withdrawal repayable over 15 yrs) + Lifelong
  Learning Plan (LLP, education).

**FHSA (First Home Savings Account)** — ITA §146.6 (effective 2023):

- **Contributions**: Deductible (RRSP-like).
- **Annual limit**: $8,000. **Lifetime limit**: $40,000. Unused
  annual room carries forward ONE year ($8,000 max carry).
- **Inside-account growth**: Tax-exempt.
- **Qualifying withdrawals** (for first home): Tax-FREE — neither
  the contributed amount nor the growth is taxable.
- **Non-qualifying withdrawals**: Fully taxable at marginal rate.
- **Combines with HBP**: a taxpayer can use both FHSA + HBP for
  the same home purchase (post-2024 reform).

**RPP (Registered Pension Plan)** + **DPSP (Deferred Profit-Sharing
Plan)** — ITA §147.x:

- **Contributions**: Employer + employee contributions deductible;
  pension-adjustment (PA) reduces the participant's RRSP room.
- **Inside-account growth**: Tax-deferred.
- **Withdrawals / annuity**: Fully taxable.

**Substrate posture (all four)**: investment income inside any
wrapped account is **out of scope** for the investment-income
provider — flagged via ADR-097 posting dimensions
(`:posting-dimension :ca-shelter` with values `#{:ca-tfsa
:ca-rrsp :ca-fhsa :ca-rpp :ca-dpsp :unsheltered}`). The
base-selector filters out non-`:unsheltered` postings. RRSP /
FHSA / RPP / DPSP **contributions** are deductions that ride
`:inputs :base-transform` on the PIT provider; **withdrawals**
(taxable on RRSP / RPP / DPSP, tax-free on TFSA / qualifying-FHSA)
are routed through ordinary income at the consumer's discretion.

### 1.7 Foreign-investment income + foreign-tax credit (§126)

Foreign dividends + foreign interest enter at GROSS amount in
income (T1 line 12100 for foreign interest; line 12000 for foreign
dividends WITHOUT the Canadian gross-up — note: foreign dividends
are NOT grossed-up because no Canadian corporate tax funded them).

The **foreign non-business income tax credit** (§126(1)) credits
back the foreign withholding tax paid, capped at:

```
foreign-tax-credit-limit  =  Canadian-tax × foreign-income / world-income
```

(the standard "average rate" formula — exactly the JP §95 shape).
A separate per-country computation is required; for non-business
income, the foreign tax claim is **further capped at 15 %** of the
foreign income (an Income Tax Act limitation; if treaty WHT exceeds
15 %, the excess is a deduction under §20(11) — a base reduction,
not a credit).

CRA Form **T2209** computes the federal foreign tax credit; **TP-772-
V** for Quebec. Each foreign country requires a separate
computation.

The substrate path: `:inputs :ca-foreign-tax-paid {<country-iso>
<Money>}` per country + per income type (dividend/interest), the
provider applies the §126 limit and emits a `:credit :refundable?
false` for each. Excess flows to a `:non-business-income-tax
-carryforward` deduction (10-yr carryforward + 3-yr carryback per
§126(2)(b)).

### 1.8 Rental income — property vs business + the loss-silo

CRA distinguishes **rental as property income** (default; reported
on T776, line 12600) from **rental as business income** (reported
on T2125, line 13500). The threshold is a **facts-and-
circumstances** test on the "number and types of services" the
landlord provides:

- **Basic services** (heat, light, parking, laundry, water): NOT
  business — still property income.
- **Additional services** (cleaning between stays, meals, security,
  reception): CAN be business — facts test.

The classification matters because:

- **Property income** is investment income; CCA on the rental
  property cannot create or increase a rental loss (Folio S4-F2-C2);
  business losses (T2125) DO offset other income freely.
- **GST/HST applicability**: residential rental is exempt;
  short-term commercial rental (Airbnb, etc.) is taxable above the
  $30k small-supplier threshold.
- **Capital-property treatment on sale**: depreciable property → CCA
  recapture (ordinary) + capital gain (separately) — see note 127
  §1.7 (CA CGT note) for the recapture-vs-capital split that the
  shipped CA CGT provider already handles.

**Substrate posture**: rental income is treated as a separate
income LANE by the investment-income provider; classification
(`:property` vs `:business`) is a consumer flag on the rental
posting (or aggregated via the consumer's reporting choice). The
provider emits `:pit-base-additions [<net-rental>]` and a separate
note for any CCA suppression (the "cannot create a rental loss"
rule — see §4 gap).

### 1.9 Corporate-side investment income — the integration loops

For corporations holding portfolio investments:

- **Inter-corporate dividends from connected Canadian corps**:
  §112 deduction (tax-free at the receiving corp; integration
  ensures the corporate tax was already paid at the issuing corp).
- **Inter-corporate dividends from non-connected (portfolio) corps**:
  Part IV tax at 38⅓ % under §186, REFUNDABLE via the
  Non-Eligible RDTOH (Refundable Dividend Tax on Hand) when the
  receiving corp pays out a non-eligible dividend to its own
  shareholders. The integration loop closes when both layers'
  taxes (Part IV + personal DTC) sum to the unincorporated marginal
  rate.
- **Portfolio interest + capital gains on portfolio assets**: form
  "aggregate investment income" (AII) — the Part I refundable tax
  (ART) of 10⅔ % on AII per §123.3 / §129(4). Adds to **Eligible
  RDTOH** for capital-gain-driven half; **Non-Eligible RDTOH** for
  interest portion. CCPC SBD also gets ground down ($5/$1 starting
  at $50k AAII, fully ground at $150k — `§125(5.1)` — see note 127
  §1.10 for context).

This is corporate-side territory — note 127 §1.10 sketches the CDA
integration; this note adds the dividend + interest + RDTOH
mechanics. Substrate disposition: investment-income provider for
corporations emits via `:cit-base-additions` + dedicated `:credits`
(the §112 deduction), with the RDTOH loops belonging to the CA CIT
provider's existing scope (ADR-107).

---

## §2. Worked examples

### Example A — Ms. Chen, mixed portfolio with eligible + non-eligible + interest + foreign

Ms. Chen, BC resident, salaried at $90,000. 2026 investment income:
- $5,000 eligible dividends from RBC (Canadian public bank)
- $3,000 non-eligible dividends from her brother's OpCo (a CCPC paying
  SBD-rate dividends)
- $1,500 interest from a GIC
- $2,000 foreign dividend from Microsoft (US WHT 15 % treaty rate =
  US$300 ≈ CAD $400 withheld)

Other facts: she has $30,000 unused FHSA room, contributes $8,000;
$5,000 TFSA contribution (room available).

**Income inclusion (T1):**

| Item | Calc | Line 12000/12100 amount |
|---|---|---|
| RBC eligible div | 5,000 × 1.38 = 6,900 | 6,900 (line 12000) |
| OpCo non-eligible div | 3,000 × 1.15 = 3,450 | 3,450 (line 12000) |
| GIC interest | 1,500 × 1 | 1,500 (line 12100) |
| MSFT foreign div | 2,000 × 1 | 2,000 (line 12000) — no gross-up |
| TFSA dividends | sheltered | 0 |
| FHSA contribution | deduction | −8,000 (line 20805) |
| Salary | | 90,000 (line 10100) |

Taxable income before federal/provincial tax:
`90,000 + 6,900 + 3,450 + 1,500 + 2,000 − 8,000 = 95,850`.

**Federal Part I tax (assume 2026 brackets approximate)**:
~15 % × bracket + 20.5 % × bracket + 26 % × marginal slice. At
$95,850 her marginal is the 26 % band. Federal tax before credits
≈ $17,000 (illustrative).

**Federal DTC**:
- Eligible: 6,900 × 15.0198 % = $1,036.37
- Non-eligible: 3,450 × 9.0301 % = $311.54
- Total federal DTC: $1,347.91 (line 40425)

**Federal foreign-tax credit**:
- US WHT $400 on $2,000 income; §126 limit = federal-tax × 2000/95,850 =
  $17,000 × 0.0209 = $355.30 → cap at min($400, $355.30) = $355.30
  → claimed; residual $44.70 deductible under §20(11). Actually for
  non-business foreign income the cap is min(foreign-tax, 15 % ×
  foreign-income) = min($400, 300) — wait, the 15 % limit applies
  to NON-business foreign tax; treaty rate IS 15 % so this is at
  the limit. Cleaner: the entire $300 (15 % treaty) is a credit;
  any excess to a deduction. Substrate-wise, the consumer supplies
  the rate (here the treaty cap is statutory).

**BC provincial tax**:
BC brackets at $95,850 → ~17 % avg + DTC. BC eligible DTC at 12 %
of grossed-up: 6,900 × 12 % = $828; BC non-eligible at 1.96 %:
3,450 × 1.96 % = $67.62. Total BC DTC ≈ $895.62.

Substrate trace: ONE `CaInvestmentIncomeFacts` per dividend, plus
one for interest, plus one for foreign dividend (all on the SAME
`TaxReturnFacts`). The provider:

1. Pre-classifies each item by lane (`:ca-eligible-dividend /
   :ca-non-eligible-dividend / :ca-interest / :ca-foreign-dividend`).
2. Emits `:pit-base-additions [6900 3450 1500 2000]` to PIT
   provider (the four gross-up'd or raw amounts).
3. Emits `:pit-credits [{:code :ca-federal-dtc-eligible :amount
   1036.37} {:code :ca-federal-dtc-non-eligible :amount 311.54}
   {:code :ca-federal-foreign-tax-credit :amount 355.30 (or
   300)}]`.
4. Emits parallel `:pit-credits` for the BC PIT provider via a
   second component / fan-out with `:authority :bc`.
5. Emits a `:base-transform-deduct` for the $8,000 FHSA
   contribution as an own line.

Match against `kontor.l10n-ca.y2024.s4` TY2024 sketch (lines 27-32):
the rates `eligible-gross-up 1.38M`, `eligible-federal-dtc-rate
0.150198M`, `non-eligible-gross-up 1.15M`, `non-eligible-federal-
dtc-rate 0.090301M`, `eligible-bc-dtc-rate 0.12M`, `non-eligible-
bc-dtc-rate 0.0196M` — these are the exact numbers the new provider
would lift into ADR-101 `:parameter`s (replacing the frozen-year
constants).

### Example B — OpsCo, holding-company structure receiving inter-corporate dividends

OpsCo (a CCPC, federally-resident, 100 % Ontario allocation, no
SBD-grind issues) is a holding company. It receives:
- $50,000 portfolio dividends from non-connected public corps (TD, BMO,
  CN Rail) — these are eligible dividends from non-connected corps.
- $25,000 portfolio interest from corporate bonds.
- $20,000 dividend from a 100 %-owned operating subsidiary
  (OpsSubCo, also a CCPC) — this is a CONNECTED corp.

**Tax treatment**:

- **Portfolio eligible dividends ($50k)**: §112 deduction (full
  $50k); but Part IV refundable tax at 38⅓ % = $19,167 on the
  non-connected portfolio dividends. This is added to **Eligible
  RDTOH** (because it's eligible dividends — the RDTOH bucket
  matches the dividend type). When OpsCo later pays an eligible
  dividend to its own shareholders, $38.33 of the Eligible RDTOH
  is refunded per $100 of eligible dividend paid.
- **Connected-corp dividend from OpsSubCo ($20k)**: §112 deduction
  in full; Part IV applies ONLY IF OpsSubCo received a Part IV
  refund on a dividend that funded the OpsCo dividend (the
  "connected" exclusion in §186(1)(b)). In most operating contexts,
  this is $0 Part IV.
- **Bond interest ($25k)**: Part I tax at the corporate rate
  (general 15 % federal + ON 11.5 % = 26.5 %), PLUS the §123.3 ART
  at 10⅔ % on AII = 25,000 × 10⅔ % = $2,667; ART adds to
  **Non-Eligible RDTOH**. Total Part I (federal+ON) on interest =
  25,000 × 26.5 % + 2,667 = $9,292.

For the substrate: investment-income provider for corporations
returns:
- `:cit-base-additions [25,000]` (interest portion — flows to
  ordinary corporate base).
- `:cit-base-deductions [50,000 20,000]` (the §112 inter-corporate
  deduction).
- A separate `:components` entry with `:kind :part-iv-tax
  :authority :cra :liability 19,167 :prepaid 0` (the Part IV
  refundable tax — distinct from Part I).
- A `:jurisdiction-specific-codes :rdtoh-credits-out` map: `{:eligible
  19,167 :non-eligible 2,667}` for the consumer's bookkeeping
  module (`kontor.book/credit-rdtoh!` or similar — a verb sibling
  to the CDA verbs note 127 §3.7 sketched).

Substrate stress this surfaces: ONE — the Part IV tax is a NEW
period-tax-kind (currently `period-tax-kinds` is closed at 8: PIT,
CIT, payroll, CGT, VAT, withholding, property, wealth). The
investment-income provider's corporate path needs **`:kind :part-iv-
tax` added to the enum** OR can re-use `:kind :withholding` (Part IV
IS effectively a withholding-on-dividend mechanism). See §4.

---

## §3. Substrate fit assessment

### 3.1 ADR-099 `PeriodTaxProvider` shape — clean fit

The provider returns one `TaxReturnFacts` per call. Component
fan-out by election-lane is analogous to JP (note 151 §5.1) but
simpler — CA has no statutory election cliff:

| Lane | Component | `:kind` | Schedule |
|---|---|---|---|
| Eligible dividend (individual) | Feeds PIT via `:pit-base-additions` + `:pit-credits`; NO own component | — | downstream |
| Non-eligible dividend (individual) | Same | — | downstream |
| Interest (individual) | Same | — | downstream |
| Foreign dividend (individual) | Feeds PIT via `:pit-base-additions` + `:pit-credits` for foreign-tax-credit | — | downstream |
| Rental (individual) | Feeds PIT via `:pit-base-additions [<net-rental>]` | — | downstream |
| Eligible portfolio dividend (corporation) | Component `{:kind :part-iv-tax :authority :cra ...}` + `:cit-base-deductions [<gross>]` for §112 | `:part-iv-tax` (new?) | `(ts/flat 0.3833M)` |
| Interest + AII (corporation) | `:cit-base-additions [<interest>]` + separate `:components` for the §123.3 ART | downstream + a small ART component | downstream |

The individual path emits ZERO own components — it is a pure
"feeder" provider, threading `:pit-base-additions` + `:pit-credits`
into the existing `ca-t1-period-tax-provider`. This is **simpler
than JP** (which has standalone 申告分離 components) because CA has
no statutory separate-taxation lane for dividends — everything
folds into ordinary PIT, just with credits.

The corporate path produces 1-2 own components (Part IV + ART) plus
fed-in deductions/additions.

### 3.2 `kontor.tax-schedule` algebra — `:flat` + adjustment fold

The 38⅓ % Part IV tax is `(ts/flat 0.38333M)`. The DTC items are
`:non-refundable-credit`s in the adjustment-layer (`apply-adjustments`,
note 105). The §126 foreign-tax-credit is a per-country
`:non-refundable-credit` with the average-rate cap baked into the
amount-fn (mirroring the JP foreign-tax-credit pattern from note 151
§5.4).

### 3.3 ADR-101 statute-as-data — natural home for the rate table

CA's investment-income story has many rate parameters that have
evolved historically and continue to evolve provincially. ADR-101
is the right home:

```
[parameters]                              ~15 entries
- "CA.InvIncome.eligible-gross-up"        1.38 (post-2010 reform)
- "CA.InvIncome.non-eligible-gross-up"    1.15 (since 2019)
- "CA.InvIncome.federal-dtc-eligible-fraction"  6/11
- "CA.InvIncome.federal-dtc-non-eligible-fraction"  9/13
- "CA.InvIncome.part-iv-tax-rate"         0.38333  (§186)
- "CA.InvIncome.refundable-art-rate"      0.10667  (§123.3)
- "CA.InvIncome.part-iv-refund-rate"      0.38333  ($38.33 per $100)
- "CA.InvIncome.foreign-non-bus-tax-cap-pct"  0.15
- "CA.ON.DTC.eligible-rate"               0.10  (% of grossed-up)
- "CA.ON.DTC.non-eligible-rate"           0.029863
- "CA.BC.DTC.eligible-rate"               0.12
- "CA.BC.DTC.non-eligible-rate"           0.0196
- "CA.AB.DTC.eligible-rate"               0.0812
- "CA.AB.DTC.non-eligible-rate"           0.0218
- "CA.QC.DTC.eligible-rate"               0.117
- "CA.QC.DTC.non-eligible-rate"           0.0342
- "CA.TFSA.annual-room"                   7000  (2026)
- "CA.RRSP.annual-cap"                    33810  (2026)
- "CA.FHSA.annual-room"                   8000
- "CA.FHSA.lifetime-cap"                  40000

[provisions]                              ~10 entries
- :ca-federal-dtc-eligible            non-refundable credit, gated on :class :eligible-dividend
- :ca-federal-dtc-non-eligible        non-refundable credit, gated on :class :non-eligible-dividend
- :ca-prov-dtc-on (...bc, ab, qc)     non-refundable credit, gated on (:class, :province)
- :ca-federal-foreign-tax-credit      §126 with average-rate cap
- :ca-part-iv-refundable-tax          §186 (corporate); refundable on outbound dividend
- :ca-section-112-deduction           inter-corporate dividend deduction
- :ca-rrsp-deduction                  §60(i) base-transform-deduct
- :ca-fhsa-deduction                  §146.6(8) base-transform-deduct
- :ca-cca-cannot-create-rental-loss   floor on rental-CCA (special — see §4)
```

The rates evolve infrequently but they DO evolve (TY2018 / 2019 the
non-eligible gross-up dropped from 1.16× to 1.15× alongside the
federal small-business rate drop from 10 % to 9 %, locked together
to preserve integration). The ADR-101 `:effective-from` keying
handles this naturally.

### 3.4 σ_E base-selector — needs the `:ca-shelter` filter

Same need as JP (note 151 §3.5): NISA / iDeCo / TFSA / RRSP / FHSA
all share the "investment income inside a wrapper does not enter
the base" semantic. Recommendation: use the SAME ADR-097
`:posting-dimension :ca-shelter` axis with values
`#{:ca-tfsa :ca-rrsp :ca-fhsa :ca-rpp :ca-dpsp :unsheltered}`. The
substrate convention is per-country (`:jp-shelter` vs `:ca-shelter`)
because the wrapper-specific contribution / withdrawal mechanics
differ — but the dimension-as-filter shape is identical.

### 3.5 ADR-101 Addendum 1 — NO `:schedule-override` needed

Unlike JP (where the 3 %-shareholder cliff forces a schedule swap
per §1.4), CA has no equivalent cliff for dividends. Every
dividend follows the same gross-up-and-credit pattern with rates
varying by `:class` (eligible vs non-eligible). The class is
consumer-supplied or derived from issuer characteristics. No
schedule override.

The `:greater-of` composition (`compose-greater-of`, statute.clj
line 532) is NOT used either — CA does not have an AMT/MAT cliff
at the individual portfolio level (the federal AMT exists but
operates on the PIT side, not investment-income-specific; the
PIT provider could implement it as a `compose-greater-of` between
regular tax and AMT — but that is PIT scope, not this provider).

### 3.6 Cross-provider coordination

FOUR existing providers + ONE new provider:

- `ca-investment-income-provider` (NEW) — emits feeder additions/
  credits to PIT; emits standalone Part IV + ART components for
  corporations.
- `ca-t1-period-tax-provider` (SHIPPED, note 102 §6 / `period_tax_provider.clj`)
  — reads the `:pit-base-additions` and `:pit-credits` from the
  consumer's threading layer. CURRENTLY only handles two
  pre-shipped credits (NRTC + DTC at lines 50 + 53) — extending
  to consume the substrate-layer-supplied additions is a small
  consumer-orchestration change, not a provider change.
- `ca-cit-provider` (SHIPPED, ADR-107) — reads
  `:cit-base-deductions` (§112) + `:cit-base-additions` (interest),
  separately accepts the Part IV + ART components if present.
- `ca-cgt-provider` (SHIPPED, note 127) — coordinates with the
  investment-income provider through the SHARED CCA-recapture
  framework (see §6.2).

### 3.7 Where this overlaps the shipped `s4.clj` prior art

`modules/l10n-ca/src/kontor/l10n_ca/y2024/s4.clj` already implements
the TY2024 dividend gross-up + federal DTC + BC DTC math (lines
27-71). It returns a `{:s4/line-12000 ... :s4/federal-dtc ...}` map
that's already wired into `t1/compute`. **The new investment-income
provider's task is NOT to displace `s4.clj`** but to:

1. Lift the constants into ADR-101 `:parameter`s for multi-year +
   multi-province support.
2. Expose the math via the `PeriodTaxProvider` protocol so it
   composes with rest of the substrate (period-tax facts,
   marginalize-able base, audit-doc carriers).
3. Carry the non-BC provinces (ON / AB / QC + the rest of CA)
   without per-year freezing.

`s4.clj` then becomes a frozen-year wire for backwards
compatibility (callable from the t1 wrapper for TY2024-specific
returns) while the new provider serves the substrate for TY2025+
and the future bitemporal scope.

---

## §4. Concrete data gaps

Six gaps total — **ZERO kernel-schema changes**; ONE possible
period-tax-kinds enum addition (Part IV); one ADR-097 dimension
convention; THREE `:inputs`-shape extensions; ONE companion-side
vocab.

1. **`:posting-dimension :ca-shelter` convention** — values
   `#{:ca-tfsa :ca-rrsp :ca-fhsa :ca-rpp :ca-dpsp :unsheltered}`.
   Mirrors JP `:jp-shelter` (note 151 §4 #1). Pure ADR-097
   convention; no schema work.

2. **`period-tax-kinds` extension — add `:part-iv-tax` (or reuse
   `:withholding`)** — Part IV refundable tax on portfolio dividends
   received by corporations. Statutorily distinct from Part I
   (income tax); refundable through the RDTOH mechanism. Two
   options:
   - **Add `:part-iv-tax`** to the closed enum. Cleanest from a
     semantics perspective; adds an enum value. ADR-099 addendum.
   - **Reuse `:withholding`** with a `:jurisdiction-specific-codes
     :withholding-source :ca-part-iv` discriminator. No enum change;
     slightly less clean.

   Recommendation: **add `:part-iv-tax`**. The enum is closed by
   design for downstream report-routing purposes; Part IV is a
   semantically distinct period tax with its own filing line on
   the T2 (Schedule 3 / 27).

3. **`:inputs :ca-dividend-classifications`** — a map keyed by
   transaction-id (or by dividend-issuer-id) of the
   `:eligible | :non-eligible | :foreign` classification. Shape:
   ```
   {:ca-dividend-classifications
    {<issuer-or-tx-id> :eligible}}
   ```
   The provider classifies each dividend posting and applies the
   correct gross-up + DTC fractions. Defaults: domestic-public-
   corp → eligible; domestic-CCPC → non-eligible (consumer can
   override per dividend — small CCPCs paying eligible dividends
   from full-tax income do exist). Pure `:inputs`-shape; ADR-099
   open.

4. **`:inputs :ca-foreign-tax-paid {<country> <amount>}`** —
   per-foreign-country foreign-tax credit input. Same shape as
   the JP equivalent (note 151 §4 #4).

5. **`:inputs :ca-rental-classifications`** — for rental income
   postings, the consumer-supplied `:property | :business`
   classification per rental "unit" (entity / address / lease):
   ```
   {:ca-rental-classifications
    {<rental-property-id> {:kind :property
                            :services [:basic-services-only]
                            :cca-claimed 0M}}}
   ```
   The provider routes property rentals to T776 (line 12600)
   semantics (CCA-cannot-create-loss restriction enforced inside
   the provider — IF the consumer-supplied `:cca-claimed` would
   drive rental income below zero, the provider caps it and emits
   a `:line-items :cca-suppression` audit entry). Business
   rentals route to T2125 (line 13500). Pure `:inputs`; no
   substrate change.

6. **`:jurisdiction-specific-codes :rdtoh-credits-out` map (on the
   Part IV component)** — `{:eligible <Money> :non-eligible <Money>}`
   for the consumer's `kontor.book` module to credit the RDTOH
   memo accounts. Mirrors `:jurisdiction-specific-codes :cit-base-
   additions` shape; no substrate-level change.

**Bottom line**: ZERO kernel changes. ZERO existing-companion-
schema changes. ONE possible `period-tax-kinds` enum addition
(`:part-iv-tax` — ADR-099-addendum). ONE ADR-097 dimension
convention. FOUR `:inputs`-shape conventions. ONE
`:jurisdiction-specific-codes` key. TWO new companion files (the
provider + the statute). All open ADR-099 extension surfaces; no
substrate-modeling changes.

---

## §5. `ca-investment-income-provider` sketch

### 5.1 Single provider, `:kind :individual | :corporation`

Mirror the `ca-cgt-provider` two-kind constructor pattern. Both
shapes share the same record; the `:kind` axis gates:

- Individual: NO own components; pure feeder via `:pit-base-additions`
  + `:pit-credits`.
- Corporation: 1-2 own components (Part IV + ART), plus
  `:cit-base-deductions` (§112 inter-corporate) + `:cit-base-additions`
  (interest).

### 5.2 The individual path

```
period-tax-facts [provider {:keys [entity period inputs] :as ctx}]
  1. Pull all investment-income postings via DisposalSource-equivalent
     or via the σ_E base-selector with `:ca-shelter :unsheltered` filter.
  2. Classify each into the lane (eligible-dividend / non-eligible-
     dividend / interest / foreign-dividend / rental) using
     `:ca-dividend-classifications` inputs + posting metadata.
  3. Compute gross-up per dividend (looks up rate from
     `kontor.statute/parameter-value-at`).
  4. Compute federal DTC per dividend (looks up fraction).
  5. Compute provincial DTC per dividend (looks up per-province rate
     keyed on `ctx :province` or `:tax-unit :province`).
  6. Compute §126 foreign-tax-credit per foreign-country aggregate
     (calls a compute-fn that knows the average-rate formula).
  7. Compute rental-income aggregation (sum net amounts, enforce
     CCA-cannot-create-loss cap, emit suppression audit-line).
  8. Return a TaxReturnFacts with ONE feeder component
     (`:kind :feeder` ? — or `:kind :personal-income-tax` with
     `:liability 0` and `:jurisdiction-specific-codes :pit-base-
     additions / :pit-credits`).
```

The "feeder" component shape is the same shape CA CGT already
uses (`:base nil :schedule nil :liability 0M` + a
`:jurisdiction-specific-codes :pit-base-additions [...] :pit-
credits [...]`). The downstream PIT provider receives the
combined map via its `:inputs` thread.

### 5.3 The corporate path

```
period-tax-facts [provider {... :keys [entity period inputs ...]}]
  1. Pull investment-income postings (same σ_E + shelter filter).
  2. Classify into eligible-portfolio / non-eligible-portfolio /
     connected-dividend / interest / capital-gains-portion.
  3. Compute §112 inter-corporate deduction (full amount of all
     dividends from connected corps + all dividends from non-
     connected Canadian corps where the receiving corp is taxable).
  4. Compute Part IV tax on non-connected dividends: 38.33 % ×
     gross dividend amount.
  5. Compute §123.3 ART on AII (interest + net capital gains
     portion at 50 % × 2 = full taxable cap gain) = 10⅔ % × AII.
  6. Build:
     - A `:part-iv-tax` component (the 38.33 % liability;
       `:jurisdiction-specific-codes :rdtoh-credits-out
        {:eligible <part-iv-eligible>
         :non-eligible <part-iv-non-eligible>}`).
     - An `:art` line within the CIT-feeder OR a separate
       `:kind :corporate-income-tax` component with `:jurisdiction-
       specific-codes :art-credit-out <non-eligible-rdtoh-add>`.
     - The feeder slot: `:cit-base-additions [<interest> <half-
       capital-gain>]`, `:cit-base-deductions [<§112-deduction>]`.
  7. Return the multi-component TaxReturnFacts.
```

### 5.4 Constructors

```clojure
(ca-individual-investment-income-provider
  {:id :ca-inv-income-individual})
;; ^ for individuals — pure feeder to PIT.

(ca-corporate-investment-income-provider
  {:id :ca-inv-income-corporate})
;; ^ for corporations — emits Part IV + ART components +
;; §112 / interest fed to CIT.
```

### 5.5 Statute file shape

`modules/l10n-ca/src/kontor/l10n_ca/investment_income_statute.clj`,
mirroring `cgt-statute.clj` + `cit-statute.clj`:

- `(def parameters [...])` — the ~15 entries listed in §3.3.
- `(def parameter-values [...])` — each with `:effective-from` +
  citation pointing at `laws-lois.justice.gc.ca/eng/acts/I-3.3/...`
  for federal items, `taxtips.ca/dtc/...` for provincial rates
  (with a backing citation to each province's `Taxation Act`),
  and `canada.ca/en/revenue-agency/...` for CRA-administered
  numbers.
- `(def provisions [...])` — the ~10 entries listed in §3.3,
  using existing ADR-101 `:tax-concept`s.
- `install!` — idempotent installer.

---

## §6. PIT / CGT coordination

### 6.1 The PIT integration — Pattern B "two-pass" recommended

CA's 配当 / DTC story has the same need as JP for a two-pass query
(note 151 §6.2):

- Pass 1: PIT provider computes pre-credit federal tax (line 40400)
  given the consumer-supplied wages + the gross-up dividend
  amounts. This gives the marginal-rate context for any income-
  dependent items (none in CA dividends; the DTC fractions are
  rate-INDEPENDENT, but the FOREIGN-tax credit's §126 cap depends
  on the federal tax).
- Pass 2: Investment-income provider computes the DTC + §126
  credits, including the average-rate cap that depends on Pass 1's
  federal tax.
- Final: PIT provider applies the credits and produces the final
  liability.

This is exactly Pattern B from note 151 §6.2; the substrate
already supports it (ADR-101 Addendum 1 documented the two-pass
query for IN §115BAA, KR / CN cliffs).

### 6.2 With `ca-cgt-provider` — the rental / depreciable interplay

A rental property is BOTH:
- An income-generating asset → rental income → investment-income
  provider scope.
- A depreciable capital asset → eventual disposal → CCA recapture +
  capital gain → CGT provider scope.

The two providers MUST agree on the **basis at disposal** because
CCA claimed in prior years REDUCES the UCC (basis), and the
CGT provider's `:disposal/depreciation-taken-amount` field needs
to match the cumulative CCA the investment-income provider has
let the consumer claim.

Substrate orchestration:
- Investment-income provider tracks CCA claimed per rental
  property per year (emits as `:jurisdiction-specific-codes
  :cca-claimed {<property-id> <amount>}` for the consumer's
  bookkeeping module to update the property's UCC).
- When the property is later disposed, the consumer's
  `record-disposal!` call supplies `:disposal/depreciation-taken-
  amount` from the cumulative claim. CA CGT provider's
  `cca-split` (already implemented, file line 236-279) handles
  the recapture vs capital split.

No new substrate primitive; the coordination is via the consumer's
property-bookkeeping layer, which both providers read independently
via `:inputs`.

### 6.3 With `ca-cgt-provider` — the LCGE / interest deduction interplay

A taxpayer with cumulative net investment loss (CNIL) has their
LCGE GROUND down per `s.110.6(1) "annual gains limit"` reduction.
CNIL = cumulative investment expenses (carrying charges +
investment-interest expense + rental losses + ABIL) MINUS cumulative
investment income (interest + taxable dividends + net taxable
capital gains).

The investment-income provider's output FEEDS the CNIL
calculation:
- Investment-income provider contributes to the **income** side
  of CNIL.
- CGT provider's ABIL contributions go to the **expense** side.
- Both feed an accumulator the consumer maintains; CGT provider
  reads `:inputs :cnil-balance` (note 127 §3.2).

The investment-income provider emits a
`:jurisdiction-specific-codes :cnil-income-contribution <amount>`
on each call; the consumer accumulates it into a per-taxpayer
CNIL ledger that flows to the CGT provider's `:inputs` for the
next period.

No substrate change; pure consumer convention; mirrors the CGT
provider's existing `:lcge-claimed-prior` mechanism.

### 6.4 Net schema impact

| Slot | Owner | Existing? | This note's ask |
|---|---|---|---|
| `:tax-concept` enum | KERNEL (ADR-101) | yes | no change — `:non-refundable-credit`, `:base-transform-add`, `:base-transform-deduct` suffice |
| `:provision` shape | KERNEL (ADR-101) | yes | no change |
| `:parameter` shape | KERNEL (ADR-101) | yes | no change |
| `period-tax-kinds` enum | KERNEL (ADR-099) | yes | **POSSIBLE addition: `:part-iv-tax`** (ADR-099 addendum) |
| `:posting-dimension` (ADR-097) | KERNEL | yes | new convention: `:ca-shelter` axis |
| `:inputs :ca-dividend-classifications` | ADR-099 `:inputs` | open map | new key |
| `:inputs :ca-foreign-tax-paid` | ADR-099 `:inputs` | open map | new key |
| `:inputs :ca-rental-classifications` | ADR-099 `:inputs` | open map | new key |
| `:jurisdiction-specific-codes :rdtoh-credits-out` | ADR-099 component | open map | new key |
| `:jurisdiction-specific-codes :cnil-income-contribution` | ADR-099 component | open map | new key |
| `:jurisdiction-specific-codes :cca-claimed` | ADR-099 component | open map | new key |
| `ca-investment-income-provider` namespace | NEW companion file | no | new namespace |
| `ca-investment-income-statute` namespace | NEW companion file | no | new namespace |

ZERO kernel changes (modulo the possible `:part-iv-tax` enum addition,
which is an ADR-099-addendum); ZERO existing-companion-schema
changes; TWO new companion files; SIX `:inputs` /
`:jurisdiction-specific-codes` key conventions; ONE ADR-097
dimension convention.

---

## §7. Sources

CA statutory:
- [ITA §9 — business income](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-9.html)
  (the property-vs-business test for rental).
- [ITA §12(1)(c) + §12(4) — interest inclusion + accrual](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-12.html)
- [ITA §20(11) — foreign-tax deduction over 15 % treaty cap](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-20.html)
- [ITA §60(i) — RRSP contribution deduction](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-60.html)
- [ITA §82(1)(b) — taxable dividend gross-up: eligible 38 % + non-eligible 15 %](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-82.html)
- [ITA §89(1) — eligible dividend definition](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-89.html)
- [ITA §112 — inter-corporate dividend deduction](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-112.html)
- [ITA §121 — federal DTC: 6/11 × gross-up (eligible) + 9/13 × gross-up (non-eligible)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-121.html)
- [ITA §123.3 — Additional Refundable Tax on Investment Income (ART, 10⅔ %)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.3.html)
- [ITA §125 — Small Business Deduction (CCPC SBD)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html)
- [ITA §126 — foreign tax credit (non-business + business)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-126.html)
- [ITA §129 — RDTOH machinery](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-129.html)
- [ITA §146 — RRSP](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-146.html)
- [ITA §146.2 — TFSA](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-146.2.html)
- [ITA §146.6 — FHSA](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-146.6.html)
- [ITA §186 — Part IV refundable tax on portfolio dividends](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-186.html)

CA government guidance:
- [CRA — Federal dividend tax credit (line 40425)](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-40425-federal-dividend-tax-credit.html)
- [CRA — Federal foreign tax credit (line 40500) + Form T2209](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-40500-federal-foreign-tax-credit.html)
- [T2209 Federal Foreign Tax Credits form](https://www.canada.ca/en/revenue-agency/services/forms-publications/forms/t2209.html)
- [Income Tax Folio S3-F2-C2 — Taxable Dividends from Corporations Resident in Canada](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plan-folio-2-dividends/income-tax-folio-s3-f2-c2-taxable-dividends-corporations-resident-canada.html)
- [Income Tax Folio S5-F2-C1 — Foreign Tax Credit](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-5-international-residency/folio-2-foreign-tax-credits-deductions/income-tax-folio-s5-f2-c1-foreign-tax-credit.html)
- [CRA — Rental income or business income (the services test)](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/rental-income/you-have-rental-income-business-income.html)
- [CRA — Rental Income T4036 guide](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4036/rental-income.html)
- [CRA — Form T776 (Statement of Real Estate Rentals)](https://www.canada.ca/en/revenue-agency/services/forms-publications/forms/t776.html)
- [CRA — TFSA contribution room](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/tax-free-savings-account/contributing/calculate-room.html)
- [CRA — RRSP contribution limits (Savings & Pension Plan administration)](https://www.canada.ca/en/revenue-agency/services/tax/registered-plans-administrators/whats-new.html)
- [CRA — FHSA overview](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/first-home-savings-account.html)
- [CRA — FHSA contribution + tax deductions](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/first-home-savings-account/tax-deductions-fhsa-contributions.html)

Reference / commentary:
- [TaxTips.ca — Eligible Dividend Tax Credit Rates (provincial table)](https://www.taxtips.ca/dtc/eligible-dividends/eligible-dividend-tax-credit-rates.htm)
- [TaxTips.ca — Non-Eligible Dividend Tax Credit Rates](https://www.taxtips.ca/dtc/non-eligible-dividend-tax-credit.htm)
- [TaxTips.ca — Foreign Tax Credit](https://www.taxtips.ca/filing/foreign-tax-credit-non-business-income.htm)
- [TaxTips.ca — Quebec Dividend Tax Credits](https://www.taxtips.ca/qctax/dividend-tax-credit.htm)
- [KPMG — Tax Facts 2025-2026 (provincial dividend rate tables)](https://assets.kpmg.com/content/dam/kpmg/ca/pdf/2025/08/tax-facts-2025-2026-en.pdf)
- [Income Tax Folio S3-F10-C2 — Prescribed Annuity Contracts (interest accrual context)](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plan-folio-10-prescribed-investments/income-tax-folio-s3-f10-c2-prescribed-annuity-contracts.html)
- [TaxTips.ca — Rental property income or business income?](https://www.taxtips.ca/real-estate/is-your-rental-income-property-income-or-business-income.htm)
- [Canadian Money Help — Dividend Gross-Up and Tax Credit Explained (2026)](https://canadianmoneyhelp.ca/articles/dividend-gross-up-tax-credit/)
- [LifeMoney.ca — Dividend Tax Credit Canada 2026 Guide](https://lifemoney.ca/blog/dividend-tax-credit-canada-2026-guide)
- [RSM Canada — Canadian tax integration on private company income (2025)](https://rsmcanada.com/content/dam/rsm/insights/services/business-tax/1pdf/2025-planner-canadian-tax-integration-private-company-income.pdf)
- [Marcil Lavallée — How the dividend tax credit works](https://marcil-lavallee.ca/en/bulletin/how-the-dividend-tax-credit-works/)
- [TaxLawyer.com — Canadian Dividend Tax Strategy: Eligible, Non-Eligible & Capital Dividends](https://taxlawyer.com/a-guide-to-canadian-corporate-dividend-tax-strategy/)
- [KPU Pressbooks — Introductory Canadian Tax: dividends in the hands of an individual](https://kpu.pressbooks.pub/cdntax/chapter/__unknown__-48/)
- [LifeMoney.ca — FHSA Canada 2026 Complete Guide](https://lifemoney.ca/blog/fhsa-canada-complete-guide-2026)
- [TD — 2026 TFSA Contribution Limits and Withdrawal Rules](https://www.td.com/ca/en/personal-banking/personal-investing/learn/tfsa-contribution-room-withdrawal-rules)
- [RBC Wealth Management — CDA + RDTOH integration guide](https://ca.rbcwealthmanagement.com/delegate/services/file/620750/content) (cross-reference with note 127 §1.10)
- [Mackisen CPA — Passive income taxation for CCPCs (AAII / SBD grind)](https://mackisen.com/blog/passive-income-taxation-for-canadian-controlled-private-corporations)

kontor substrate cited:
- `modules/l10n-ca/src/kontor/l10n_ca/y2024/s4.clj:27-71` — the TY2024
  Schedule-4 prior art whose constants the new statute file lifts
  into ADR-101 `:parameter`s. The new provider does NOT displace
  this file; it generalises it across provinces + years + the
  ADR-099 protocol.
- `modules/l10n-ca/src/kontor/l10n_ca/y2024/t1.clj` — the existing
  TY2024 T1 wrapper that consumes `s4/compute` outputs at line 186;
  no change required, but the consumer for TY2025+ should call the
  new provider instead.
- `modules/l10n-ca/src/kontor/l10n_ca/period_tax_provider.clj:37-79`
  — the CA T1 `PeriodTaxProvider` shape the new provider feeds via
  `:pit-base-additions` + `:pit-credits`.
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:236-279` —
  the `cca-split` function that handles depreciable-property
  recapture + capital split on disposal; the investment-income
  provider tracks CCA claimed during the holding period via
  `:jurisdiction-specific-codes :cca-claimed`, and the consumer's
  property-bookkeeping layer threads it through to
  `:disposal/depreciation-taken-amount` at disposal time.
- `modules/l10n-ca/src/kontor/l10n_ca/cgt_provider.clj:402-433` —
  the `apply-lcge` helper for the LCGE cap consumption; the
  investment-income provider's CNIL contribution feeds the
  `:inputs :cnil-balance` the LCGE math relies on (note 127 §3.2).
- `modules/l10n-ca/src/kontor/l10n_ca/cit_provider.clj` — the
  shipped CA CIT provider (ADR-107); the corporate-side
  investment-income components feed Part IV + §112 + ART into its
  inputs.
- `modules/l10n-ca/src/kontor/l10n_ca/cit_statute.clj` — the
  statute file structure the new investment-income statute mirrors
  (parameters + parameter-values + provisions + install!).
- `src/kontor/personal_income_tax.clj:37-46` — the `gross-income`
  base-selector that the investment-income provider would call
  through the consumer's pre-filtered (ADR-097 `:ca-shelter`)
  posting view.
- `src/kontor/personal_income_tax.clj:61-118` — the
  `PersonalIncomeTaxProvider` shape that the dividend / interest /
  rental items feed via `:pit-base-additions` + `:pit-credits`.
- `src/kontor/period_tax_provider.clj:44-61` — the closed
  `period-tax-kinds` enum; this note's only POSSIBLE substrate ask
  is the addition of `:part-iv-tax` to that enum (ADR-099-addendum
  track).
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor; the
  38⅓ % Part IV tax + the 10⅔ % ART are `(ts/flat 0.3833M)` and
  `(ts/flat 0.10667M)`.
- `src/kontor/tax_schedule.clj:233-274` — `apply-adjustments` for
  the DTC + foreign-tax-credit fold.
- `src/kontor/statute.clj:423-460` — `apply-provisions`; the
  provider invokes it once per pass to fold the DTC + foreign-tax
  + §112 provisions.
- `doc/research/127-ca-cgt-fit.md` §1.10 — the CDA + RDTOH
  bookkeeping that the corporate-side investment-income provider
  partially feeds (the dividend half; the cap-gain half lives in
  CGT).
- `doc/research/151-jp-investment-income-fit.md` — the sibling JP
  note; the substrate patterns (election validator, shelter-
  filter, statute parameters, multi-component feeder) are
  consciously aligned across the two notes to make the pattern
  re-usable for FR / DE / US / IN follow-ups in Phase C3.
- `doc/research/102-period-tax-provider-design.md` §6 — the
  `PeriodTaxProvider` protocol shape the new provider implements.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — the
  ADR-101 statute-as-data substrate that the new statute file
  populates.

---

End of note 152.

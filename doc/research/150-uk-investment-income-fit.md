---
date: 2026-05-24
title: 150 — UK investment-income taxation — substrate fit for the `uk-investment-income-provider`
status: research-before for note-104 Phase 3 C2 (FR + UK investment-income providers); no code; data-gap list at §4
audience: maintainer + the UK-investment-income implementation agent
---

# 150 — UK investment-income substrate fit

Phase 3 C2 pairs France (note 149) and the United Kingdom for the
shareholder-side investment-income gap. The UK side composes onto
the already-shipped `uk-individual-cgt-provider` +
`uk-corporate-cgt-provider` (note 114 / ADR-102 /
`modules/l10n-uk/.../cgt-provider.clj`). The integration is
straightforward in shape — *but* the UK regime carries a unique
mechanic the substrate must respect: **the income-tax ordering
rules**. Non-savings income, savings income, and dividend income
are layered (not netted) in a strict statutory order; each layer
has its own zero-rate band (PA / starting-rate / savings-allowance /
dividend allowance) and the available band of one layer DEPENDS on
the volume of the prior layer. The CGT provider's `:tax-unit
:income-band` slot (note 114 §5.1) papers over this with a binary
`:basic | :higher` simplification; the investment-income provider
**cannot** — it must compute the layered ordering correctly.

Bottom line: **the substrate is sufficient.** No new schema; the
ordering math lives entirely inside the provider as a pure
function over a `:tax-unit` carrying the prior-layer totals. The
provider extends note 149's `:investment-income-tax` `:kind` enum
addition (shared kernel one-liner). The UK provider's API is
narrower than FR's (no PFU/barème election; the rates are statutory
and not optional), and the wrapper exemptions (ISA) are handled the
same way FR PEA is — the wrapper makes income invisible to the
substrate at the point of receipt.

---

## §1. The UK investment-income regime — three statutory shapes

UK has THREE distinct income compartments under the Income Tax Act
2007 (ITA 2007) — non-savings, savings, and dividend — each with
its own rate ladder and zero-rate allowance. The shapes are
defined by ITTOIA 2005 (Income Tax (Trading and Other Income) Act):

- **Chapter 2 of Part 4 ITTOIA** — interest (savings income).
- **Chapter 3 of Part 4 ITTOIA** — dividend / distributions
  (dividend income).
- **Chapter 3 of Part 3 ITTOIA** — rental income (property income
  — separate category, treated as non-savings income).

The rate ladders are in ITA 2007 §§ 6-21.

### 1.1 Dividend income — ITTOIA Ch. 3 / ITA 2007 § 13A

- **Dividend allowance**: **£500** for 2024/25 and 2025/26 (slashed
  from £1 000 in 2023/24, from £2 000 in 2017-22, from £5 000
  originally in 2016/17). Within the allowance: **0 % rate** (the
  "dividend nil rate").
- **Dividend tax rates** (ITA 2007 §§ 8 + 13A, plus Finance Acts):
  - 2024/25 and 2025/26: basic **8.75 %**, higher **33.75 %**,
    additional **39.35 %**.
  - **From 6 April 2026** (Autumn Budget 2025 confirmed): basic
    rises to **10.75 %**, higher to **35.75 %**, additional
    UNCHANGED at **39.35 %** ([Accounted Ltd — dividend tax
    changes 2026](https://www.accountedltd.com/blog/dividend-tax-rate-changes-april-2026-what-uk-directors-need-to-know);
    [FreeAgent — UK dividend rates 2026/27](https://www.freeagent.com/rates/dividend-tax/)).
  - Substrate consequence: the rate is **bitemporally parameter-
    driven** via `:parameter`; ADR-101 substrate handles the
    transition cleanly.

**Critical mechanic — the dividend allowance counts towards the
tax bands**: the £500 dividend nil rate is taxed at 0 %, but the
£500 still consumes basic-rate band capacity. So a taxpayer at the
boundary of the higher-rate threshold may find that £500 of
dividend income pushes the *next* £500 of any income into the
higher rate. ([1st Formations — dividend allowance 2026/27](https://www.1stformations.co.uk/blog/dividend-tax-allowance/)
confirms: "The £500 dividend allowance does not sit 'outside' your
tax bands - it still counts to your overall income to determine
which tax band you fall into.")

### 1.2 Savings income (interest) — ITTOIA Ch. 2 / ITA 2007 §§ 11A-18A

Three layered zero-rate bands apply to savings income:

- **Personal Allowance (PA)**: £12 570 for 2024/25, unchanged
  through 2025/26 and 2026/27 (frozen until 2028 by Sunak-era
  policy). The PA is *not* a savings-income allowance per se —
  it's a global income allowance — but its leftover after non-
  savings income is "soaked up" by savings income at 0 % rate.
- **Starting Rate for Savings (SRS)**: **0 % on the first £5 000
  of savings income** (ITA 2007 § 12). Crucial caveat: this band
  is **reduced £1-for-£1 by each £1 of non-savings income above
  the PA**. So at non-savings income £17 570 (= £12 570 PA +
  £5 000 SRB), the SRS is exhausted ([LITRG — starting rate for
  savings](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-savings-income/starting-rate-savings)).
- **Personal Savings Allowance (PSA)**: introduced by Finance Act
  2016. Basic-rate: **£1 000**; higher-rate: **£500**; additional-
  rate: **£0** (no PSA at all). The PSA is a **zero-rate band**,
  not a tax-free amount above the line — savings income in the
  PSA still counts towards the tax bands ([gov.uk PSA](https://www.gov.uk/government/publications/income-tax-personal-savings-allowance/income-tax-personal-savings-allowance)).

Above all three bands, savings income is taxed at the same rates
as non-savings income: 20 % / 40 % / 45 %.

### 1.3 Non-savings income — ITA 2007 § 6

The default. Includes employment income (already withheld via
PAYE), self-employment profits, rental income (ITTOIA Ch. 3 of
Part 3), pension income. Rates:

- 2024/25 — 2026/27: basic **20 %** (band £0-£37 700 above PA),
  higher **40 %** (band £37 701-£125 140), additional **45 %**
  (band > £125 140 — the threshold lowered from £150 000 to
  £125 140 by Finance Act 2023). The PA itself is **tapered away**
  by £1 for every £2 of income above **£100 000** — the
  "60 % effective marginal rate" trap zone.

### 1.4 The income-tax ordering rule (THE critical substrate concern)

UK income tax is computed via a strict ordering rule (ITA 2007 §
16) that stacks income in this order:

1. **Non-savings income** first.
2. **Savings income** second (on top of non-savings).
3. **Dividend income** last (on top of both).

Each layer is taxed at the rate of the band it sits in, after
the prior layers have consumed band capacity. This is *not* an
elective ordering — it is statutory and rigid.

The ordering matters because:

- The PA goes against non-savings income first; only leftover PA
  cascades to savings, then dividends.
- The SRS depends on how much PA leftover there is (only fires if
  total non-savings income < £17 570).
- The PSA size depends on the marginal-rate band the SAVINGS
  income falls into.
- The dividend allowance does NOT shift bands — but dividends ARE
  taxed at the rate band they fall into after non-savings +
  savings have stacked.

Substrate consequence: the UK provider needs to compute the
ordering. The PIT provider holds the non-savings income; the
savings-income subprovider and the dividend subprovider must read
**both** (a) the period's non-savings total and (b) the period's
savings total in advance. The natural shape: ONE
`uk-investment-income-provider` with TWO components (savings,
dividends) that share a pre-computed ordering helper.

### 1.5 ISA — wrapper exemption (Finance Act 2004 + ISA Regulations 1998)

The **Individual Savings Account** is the UK's all-purpose tax-
free wrapper. Annual subscription limit **£20 000** for 2024/25
and 2025/26 (with sub-types: Cash ISA, Stocks & Shares ISA,
Innovative Finance ISA, Lifetime ISA £4 000 sub-cap, Junior ISA
£9 000 sub-cap). The 2024-04 reform allows multiple ISAs of the
same type in the same tax year.

Inside an ISA:

- Interest is tax-free (does not consume PSA / SRS).
- Dividends are tax-free (does not consume dividend allowance).
- Capital gains are tax-free (does not require CGT reporting).

The kontor convention mirrors FR PEA (note 149 §1.2 / §7 open
question 2): an ISA portfolio is a single `Asset:ISA-Portfolio`
on the holder's books; income inside the wrapper does NOT post
to investment-income accounts; gains accrue on the asset balance.
The provider simply never sees the income — substrate stays
clean.

**Lifetime ISA** specifics — 25 % government bonus on
contributions (separate accrual), 25 % withdrawal penalty if
non-qualifying withdrawal. Bonus tracked as `Income:LISA-bonus`;
the withdrawal penalty as a separate expense. v1 substrate does
not require any new attrs.

### 1.6 Corporate dividend exemption — CTA 2009 Part 9A

Distinct from individual treatment. When a UK-resident company
receives a dividend (UK or foreign-source), the receipt is
**generally exempt** from corporation tax, subject to falling
within an exempt class. The Part 9A structure:

- **Chapter 2 — small companies**: a "small company" (micro or
  small per Commission Recommendation 2003/361/EC: < 50
  employees, ≤ €10M turnover OR ≤ €10M balance sheet) gets a
  **broad exemption** for both UK and most foreign-source
  dividends, with narrow anti-avoidance carve-outs (the dividend
  must come from a "qualifying territory" — most treaty
  partners; the payer must not be a tax-haven entity).
- **Chapter 3 — other (non-small) companies**: exemption per
  membership of one of **5 exempt classes** ([HMRC INTM651020](https://www.gov.uk/hmrc-internal-manuals/international-manual/intm651020)):
  - **Class 1**: distributions from "controlled" companies (the
    recipient controls the payer, > 50 % voting).
  - **Class 2**: distributions in respect of **non-redeemable
    ordinary shares** (the most-used exemption).
  - **Class 3**: distributions in respect of holdings of < 10 %
    of share capital — **the "portfolio holdings exemption"**
    — provided the payer is in a qualifying territory.
  - **Class 4**: distributions derived from transactions not
    designed to reduce UK tax (anti-avoidance carve-in).
  - **Class 5**: distributions in respect of shares accounted
    for as liabilities (preference shares with debt-like
    features).

The £10 holding threshold for **Class 3** is the closest UK
equivalent to FR mère-fille's 5 % stake — but UK is *more
generous*: a sub-10 % holding can STILL be exempt under Class 3
provided the payer is in a qualifying territory and the
distribution is on ordinary shares. The dividend exemption in
UK is far broader than FR mère-fille.

**Substrate consequence**: corporate dividend income posts to the
income account tagged `:uk-investment-income/corp-dividend-
exempt` (the typical case) and the provider treats it as
**fully exempt** — emits a zero-liability component with the
gross dividend in `:line-items` for audit but no
`:cit-base-additions`. Only dividends NOT falling within a
Part 9A exempt class flow through to CT.

### 1.7 Loss offset — capital losses do NOT offset investment income

Same rule as FR (note 149 §1.6), US, JP: capital losses offset
capital gains only (UK CGT shipped under note 114 with a single
`:uk-capital` loss bucket — losses cannot offset dividend or
interest income at all).

**Negative dividend income** (capital reduction treated as
distribution, anti-avoidance corner cases) is rare and not
covered by v1.

### 1.8 Rental income — Chapter 3 of Part 3 ITTOIA

Treated as non-savings income (stacks with employment / self-
employment). For individuals, the **property allowance** of £1 000
applies — gross rental income ≤ £1 000 is automatically tax-free;
above that, the property owner may either deduct actual expenses
OR claim the £1 000 deduction.

**Mortgage interest restriction** — since April 2020, residential-
property landlords cannot deduct mortgage interest as an expense
above the line. Instead, they receive a **20 % basic-rate tax
credit** on the interest (ITTOIA s274A). For higher / additional-
rate taxpayers, this is a meaningful tax hike — the cost was
formerly deducted at the marginal rate.

For corporations, none of these restrictions apply — corporate
landlords deduct interest as a normal business expense.

Substrate consequence: rental income provider is a separate
component (sibling of investment-income), folds into PIT at
marginal rate as non-savings income, surfaces a 20 % interest
tax credit via `:credits`. v1 ships the substrate marker tag and
documents the gap; the full UK rental-income provider is a
follow-on (see §5.6).

### 1.9 Trust dividend / interest tax — out of scope for v1

UK trusts pay tax at the **trust rate** on dividend income
(**39.35 %**) and savings/non-savings income (**45 %**), with
the **£500 tax-free de minimis** for low-income trusts (replaces
the pre-April-2024 £1 000 standard-rate band). When trustees
distribute, beneficiaries claim a tax-pool credit. The mechanics
require a separate `uk-trust-tax-provider` and a `:trust` entity
kind extension — deferred to a future stage.

---

## §2. Worked examples

### 2.1 Individual — dividend income + savings income with full ordering

Source: Adapted from [PwC UK individual income determination](https://taxsummaries.pwc.com/united-kingdom/individual/income-determination)
+ [LITRG starting rate for savings](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-savings-income/starting-rate-savings).

C. Brown, single filer, fiscal year 2024/25:

- **Non-savings income** (salary): £30 000.
- **Savings income** (interest from a UK bank): £2 500.
- **Dividend income** (FTSE-100 dividends in a regular brokerage —
  NOT inside an ISA): £4 000.
- No pension contributions; no Gift Aid.

**Step 1 — apply Personal Allowance (£12 570) to non-savings
income first**:

- Non-savings taxable = £30 000 − £12 570 = **£17 430**.
- PA fully consumed by non-savings income.

**Step 2 — basic rate band capacity** (£37 700 from PA top to higher-
rate threshold):

- Used by non-savings: £17 430.
- Remaining basic-rate capacity: £37 700 − £17 430 = **£20 270**.

**Step 3 — apply Starting Rate for Savings (SRS, £5 000)**:

- Non-savings income above PA = £17 430 > 0, so SRS is reduced by
  £1 per £1.
- Actually, the reduction is based on whether non-savings income
  exceeds the PA. Non-savings income (£30 000) − PA (£12 570) =
  £17 430. That's above the £5 000 SRS cap, so **SRS is reduced
  to £0**.

**Step 4 — apply Personal Savings Allowance (PSA)**:

- Total income before dividends = £30 000 + £2 500 = £32 500 (well
  below higher-rate threshold £50 270) → **basic-rate taxpayer**
  for savings → PSA = **£1 000**.
- Savings income within PSA: £1 000 @ 0 %.
- Savings income above PSA: £1 500 @ 20 % = £300.

**Step 5 — apply Dividend Allowance (£500)**:

- Dividend allowance: £500 @ 0 %.
- Remaining dividends: £3 500.
- Position before dividends: cumulative income = £32 500.
- Higher-rate threshold = £50 270 (PA £12 570 + basic band
  £37 700). Remaining basic-rate capacity for dividends:
  £50 270 − £32 500 = £17 770.
- ALL £3 500 of remaining dividends fits in basic rate.
- Dividend tax: £3 500 × 8.75 % = **£306.25**.

**Step 6 — total tax**:

- Non-savings: £17 430 × 20 % = £3 486.
- Savings (PSA covered £1 000): £1 500 × 20 % = £300.
- Dividends (allowance covered £500): £3 500 × 8.75 % = £306.25.
- **Total: £4 092.25**.

Substrate trace: TWO postings record the income — Dr Bank £2 500,
Cr Interest-Income £2 500 (tagged `:uk-investment-income/savings-
uk-domestic`); Dr Bank £4 000, Cr Dividend-Income £4 000 (tagged
`:uk-investment-income/dividend-uk`). The
`uk-investment-income-provider` reads:

- `(:tax-unit :non-savings-income)` → £17 430 (the PIT provider's
  taxable non-savings income; the consumer pre-computes and
  threads).
- `(:tax-unit :marginal-rate-band)` → `:basic` (computed from the
  totals).
- Marginalizes interest income → £2 500; dividend income → £4 000.
- Computes per the ordering above; emits ONE component carrying:
  - `:base` = £6 500 (total income subject to investment-income
    layer).
  - `:line-items` = [PA-consumed-by-non-savings, SRS-reduced-to-0,
    PSA-used £1 000, dividend-allowance-used £500, savings-tax
    £300, dividend-tax £306.25].
  - `:liability` = £606.25 (the £3 486 non-savings tax is OWNED by
    the PIT provider, NOT this provider — composition discipline).
  - `:surtaxes` = empty (no PS in UK).
  - `:credits` = empty (no PAS in UK).

### 2.2 Individual — higher-rate dividends + ISA wrapper

D. Patel, single filer, fiscal year 2024/25:

- **Non-savings income**: £60 000 (salary).
- **Savings income** (regular bank account): £600.
- **Savings income inside ISA**: £400 (invisible).
- **Dividend income (regular brokerage)**: £3 000.
- **Dividend income inside ISA**: £2 000 (invisible).

**Step 1 — PA**: £12 570 against non-savings → non-savings
taxable = £47 430.

**Step 2 — Tax bands**: D. is a higher-rate taxpayer (income
£60 000 > £50 270 threshold). PSA = £500 (higher-rate); SRS = £0
(non-savings exceeds PA + £5 000).

**Step 3 — Non-savings tax**:

- Basic band: £37 700 × 20 % = £7 540.
- Higher band: £47 430 − £37 700 = £9 730 × 40 % = £3 892.
- Non-savings tax = £11 432.

**Step 4 — Savings**:

- £600 savings income; PSA = £500 at 0 %; £100 at 40 % (higher-
  rate; the £100 sits in the higher-rate band) = **£40**.

**Step 5 — Dividends**:

- Dividend allowance £500 @ 0 %.
- Remaining £2 500.
- Position: £60 000 + £600 = £60 600; higher-rate band already
  active; £2 500 all in higher band.
- Dividend tax = £2 500 × 33.75 % = **£843.75**.

**Step 6 — Totals**:

- Non-savings: £11 432.
- Savings: £40.
- Dividends: £843.75.
- **Total: £12 315.75**.

**Inside the ISA**: £400 interest + £2 000 dividends are tax-free
— not posted to any income account at all (the ISA portfolio is
an asset; gains accrue on the asset balance internally). Without
ISA, the £2 400 ISA income would have added ~£787 (£400 × 40 %
+ £2 000 × 33.75 %) — the wrapper saves D. ~£787 each year.

Substrate trace: same shape as 2.1, but with `:tax-unit
:marginal-rate-band :higher`, so PSA = £500 and dividend tax fires
at 33.75 %. ISA holdings have NO investment-income postings at
all — the ISA asset account is the only GL trace.

### 2.3 Corporate — dividend exemption (Class 3, portfolio holding)

GreenCo Ltd (a UK-resident OpCo with > 50 employees, > €10M
turnover — NOT a "small company") owns 4 % of FTSE-100 BlueChip
plc and receives a £100 000 dividend in 2024/25.

- 4 % < 10 % → **Class 1 / 2 may not apply** (Class 1 needs
  control, Class 2 needs non-redeemable ordinary; assuming
  ordinary shares, Class 2 fires).
- Even at 4 %, the **portfolio holdings exemption (Class 3)**
  ALSO fires because BlueChip plc is in a qualifying territory
  (UK) and the shares are ordinary.
- Result: dividend is **fully exempt** from CT.

GreenCo's CT computation **excludes** the £100 000 from
chargeable profits. The dividend posts to GreenCo's books (Dr
Bank £100 000, Cr Investment-Income £100 000 tagged
`:uk-investment-income/corp-dividend-exempt`), but the
`uk-corporate-investment-income-provider` emits a component with
`:base 0`, `:liability 0`, the gross in `:line-items` for audit,
and no `:cit-base-additions`.

Contrast with FR mère-fille: FR would still reintegrate 5 % QPFC
(€5 000 → €1 250 IS @ 25 % effective). UK Class 3 is **strictly
more generous** — no QPFC, no reintegration.

### 2.4 Individual — corner case: starting rate for savings active

E. Wilson, retiree, fiscal year 2024/25:

- **Non-savings income** (state pension): £11 000.
- **Savings income** (interest from savings accounts): £8 000.
- **Dividend income**: £0.

**Step 1 — PA**: £12 570 against non-savings → non-savings
taxable = £0; **£1 570 of PA is leftover** for savings.

**Step 2 — SRS**: Non-savings income £11 000 < PA £12 570, so
NO reduction of the £5 000 SRS. Full £5 000 SRS available.

**Step 3 — PSA**: E. is a basic-rate taxpayer → PSA = £1 000.

**Step 4 — Savings income decomposition**:

- £1 570 against leftover PA @ 0 %.
- £5 000 against SRS @ 0 %.
- £1 000 against PSA @ 0 %.
- Subtotal exempt = £7 570.
- Remaining: £8 000 − £7 570 = **£430** @ 20 % = **£86**.

**Step 5 — Total tax**: **£86**.

E. enjoys £7 570 of tax-free savings income — the most
generous individual savings carve-out in the UK regime, available
only to people with low non-savings income. The substrate must
compute this correctly; a hardcoded "PSA = £1 000 / £500" without
SRS support would over-tax E. by £1 000 (£5 000 SRS × 20 %).

Substrate trace: the provider's ordering computation needs the
SRS layer; the provider implements the **full ITA § 16 ordering
algorithm** as a pure function:

```clojure
(defn uk-income-tax-allocation
  "Pure: given non-savings, savings, dividend income + bands,
   return the per-layer-per-band breakdown."
  [non-savings savings dividends {:keys [pa srs basic-band higher-band
                                          dividend-allowance psa-basic psa-higher]}]
  ;; ... see §5.3 sketch
  )
```

---

## §3. `:disposal`-style substrate — same answer as FR

Note 149 §3 concluded: investment-income does NOT need a
companion entity; the GL posting + account-tag carries everything.
The UK answer is identical:

- No basis. No holding period (no dividend-equivalent of the
  long/short distinction).
- The posting carries date, amount, partner. The
  `:account-tag :uk-investment-income/*` carries the tax-routing
  classification.
- The provider reads via `report/marginalize` exactly the way
  the FR provider does (note 149 §3).

### 3.1 The chart-of-accounts shape

UK chart-of-accounts is less standardised than FR's PCG (no
nationally-mandated chart equivalent to PCG class 76x). The
common public-companies-house convention groups investment income
under:

- **Account 9001** Investment income — UK dividends.
- **Account 9002** Investment income — UK savings interest.
- **Account 9003** Investment income — overseas dividends (with
  treaty WHT).
- **Account 9004** Investment income — overseas interest (with
  treaty WHT).
- **Account 9005** Investment income — corp distributions (Part
  9A exempt class).
- **Account 9006** Investment income — bond coupon / gilt.
- **Account 9007** Investment income — peer-to-peer lending /
  alternative finance.

The l10n-uk chart (not yet shipped — see ADR-104+ for the
in-progress UK CIT module; the chart is a follow-on under ADR-uk-
chart) will add these accounts with the appropriate tags:

- `:uk-investment-income/dividend-uk` — domestic dividend (ordinary
  rate ladder per 2.1).
- `:uk-investment-income/dividend-foreign-with-credit` — foreign
  dividend; treaty WHT generates a foreign-tax credit (deferred to
  a follow-on like FR — needs FX + treaty matrix).
- `:uk-investment-income/savings-uk-domestic` — savings income
  (interest + interest-equivalent).
- `:uk-investment-income/savings-uk-isa-wrapped` — exempt (filter
  out at compute time, but tag preserves auditability).
- `:uk-investment-income/dividend-uk-isa-wrapped` — exempt
  (sibling tag).
- `:uk-investment-income/corp-dividend-exempt` — corporate
  recipient under Part 9A; provider treats as fully exempt.
- `:uk-investment-income/corp-dividend-taxable` — corporate
  recipient NOT in an exempt class (rare); flows to CT.
- `:uk-investment-income/rental-uk` — domestic rental income (out
  of scope for v1 investment-income provider; routes to a future
  `uk-rental-income-provider`).
- `:uk-investment-income/property-allowance-used` — marker for the
  £1 000 property-allowance election (rental v1 follow-on).

The tag set is open (per ADR-090); l10n-uk publishes the closed
v1 vocabulary.

---

## §4. Data gaps — concrete list

| # | Gap | Required for | Recommendation |
|---|---|---|---|
| G1 | `:period-tax-kinds` enum needs `:investment-income-tax` value | Provider's `:kind` slot is constrained | **EXTEND** the enum (shared with FR note 149 G1). Single-line kernel addition under an ADR-099 amendment. |
| G2 | `:tax-unit :non-savings-income` (Money) | Ordering rule needs the prior layer's total | **NO SCHEMA** — `:tax-unit` is opaque. Consumer threads from the UK PIT provider's `TaxReturnFacts`; documented per-provider convention. |
| G3 | `:tax-unit :marginal-rate-band` (`:basic | :higher | :additional`) | PSA selection (£1 000 / £500 / £0); dividend rate band | **NO SCHEMA** — consumer derives from total taxable income; documented. Note 114 §5.1 already uses `:income-band :basic | :higher` for CGT — extend to include `:additional` (the £125 140 + bracket) for investment income (CGT post-Oct-2024 doesn't distinguish; investment income does). |
| G4 | Account-tag family `:uk-investment-income/*` | Routing income postings into the right lane (UK domestic / foreign / ISA / corp-exempt / Part 9A non-exempt) | **NO SCHEMA** — `:account-tag` accepts open keywords. l10n-uk publishes the closed v1 vocabulary (§3.1). |
| G5 | Rate parameter table (basic/higher/additional × dividend/savings/non-savings) | Per-band tax rates | **`:parameter`** entries per ADR-101. l10n-uk ships under `"UK.IIT.dividend.basic-rate"`, `"UK.IIT.dividend.higher-rate"`, etc., with bitemporal effective-from for the 2026 rate hike. |
| G6 | Allowance parameter table (PA, SRS, PSA basic, PSA higher, dividend-allowance, property-allowance) | Per-allowance amounts (frozen / rate-band-dependent) | **`:parameter`** entries. |
| G7 | Corporate-recipient Part 9A exempt-class routing | Whether a dividend falls in Class 1-5 (exempt) or non-exempt | **`:disposal/exemption-claimed` analog on the income posting** — but the kontor convention is that the posting's `:account-tag` carries the routing (e.g. `:uk-investment-income/corp-dividend-exempt` = exempt; `:uk-investment-income/corp-dividend-taxable-class-X` = non-exempt). Consumer asserts classification at posting time; provider trusts the tag (same pattern as UK CGT trusting `:disposal/exemption-claimed :uk-sse`). |
| G8 | Property-allowance £1 000 election | Rental income micro-regime | **DEFERRED** to the `uk-rental-income-provider` (v1 marker only). Tagged `:uk-investment-income/rental-uk` for filtering. |
| G9 | Mortgage-interest tax credit 20 % | Residential landlord interest deductibility post-2020 | **DEFERRED** — `:credits` on the rental-income component with `:code :uk-mortgage-interest-credit-s274A`. |
| G10 | Foreign-tax credit (treaty WHT) | Overseas dividends with WHT | **DEFERRED** — needs FX + treaty matrix. Tag `:uk-investment-income/dividend-foreign-with-credit` reserved; provider documents the gap. |
| G11 | Trust tax (39.35 % trust rate on dividends) | Trustee compliance | **DEFERRED** — separate `uk-trust-tax-provider`. Documented; out of scope. |

**Summary of additive substrate changes**:

- **ONE kernel addition (shared with FR note 149)**:
  `:investment-income-tax` value in `period-tax-kinds`.
- **ZERO new attributes**, **ZERO new entity kinds**, **ZERO new
  protocols**.
- All other "gaps" are provider-internal conventions on already-
  shipped `:inputs` / `:tax-unit` / `:account-tag` / `:parameter`
  slots.

---

## §5. `uk-investment-income-provider` sketch

### 5.1 ONE personal provider with two coordinated sub-computations

Unlike FR (where the dividend / interest / assurance-vie / mère-
fille split is statutorily distinct and each has its own rate
ladder), UK rates for savings income above PSA are simply the
*same* basic/higher/additional rates as non-savings income — so
savings income doesn't get its own rate ladder, only its own
zero-rate band (PSA + SRS). The cleanest fit is ONE provider with
the **ordering computation as a shared pure function** and ONE
component that captures both savings and dividend slices, with
distinct line items.

(Alternative: TWO components, one for savings-income tax and one
for dividend-income tax. This is more granular and may suit the
SA100 reporting structure better. v1 implementer's call;
substrate supports either.)

### 5.2 Corporate provider — Part 9A exemption check

A separate `uk-corporate-investment-income-provider`, sibling of
`uk-corporate-cgt-provider`. Mostly trivial: dividends tagged
`:corp-dividend-exempt` post to a component with zero liability;
dividends tagged `:corp-dividend-taxable` route via
`:cit-base-additions` to the UK CT provider (when it ships).

### 5.3 Personal provider conceptual sketch

```clojure
;; Conceptual — no code change in this note.
(defrecord UKPersonalInvestmentIncomeProvider [id authority commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs tax-unit] :as ctx}]
    (let [db        (:db ctx)
          as-of     (or (:to period) (java.util.Date.))
          ;; Marginalize the entity's income postings by UK-l10n tag
          postings  (report/report-postings db {:from (:from period) :to (:to period)
                                                :entity entity})
          by-tag    (report/marginalize postings :account-tag {:commodity commodity})
          ;; Lane totals
          div-uk        (get-in by-tag [:uk-investment-income/dividend-uk :value])
          savings-uk    (get-in by-tag [:uk-investment-income/savings-uk-domestic :value])
          ;; ISA-wrapped lanes are FILTERED (treated as if not posted)
          ;; Consumer-supplied prior-layer income
          non-savings   (or (:non-savings-income tax-unit) 0M)
          ;; Rate-band classification (computed by consumer or here)
          band          (or (:marginal-rate-band tax-unit) :basic)
          ;; Allowances (bitemporally parameter-driven)
          pa            (param db "UK.IIT.PA" as-of)               ; 12 570
          srs           (param db "UK.IIT.SRS" as-of)              ; 5 000
          psa-basic     (param db "UK.IIT.PSA-basic" as-of)        ; 1 000
          psa-higher    (param db "UK.IIT.PSA-higher" as-of)       ; 500
          div-allow     (param db "UK.IIT.dividend-allowance" as-of) ; 500
          basic-band    (param db "UK.IIT.basic-band" as-of)       ; 37 700
          basic-rate    (param db "UK.IIT.basic-rate" as-of)       ; 20 %
          higher-rate   (param db "UK.IIT.higher-rate" as-of)      ; 40 %
          additional-rate (param db "UK.IIT.additional-rate" as-of) ; 45 %
          div-basic-rate  (param db "UK.IIT.dividend.basic-rate" as-of)   ; 8.75 % 2024-26, 10.75 % 2026+
          div-higher-rate (param db "UK.IIT.dividend.higher-rate" as-of)  ; 33.75 % 2024-26, 35.75 % 2026+
          div-add-rate    (param db "UK.IIT.dividend.additional-rate" as-of) ; 39.35 %

          ;; Run the ordering computation (§5.4 below)
          alloc (uk-income-tax-allocation
                  {:non-savings non-savings
                   :savings savings-uk
                   :dividends div-uk}
                  {:pa pa :srs srs :psa-basic psa-basic :psa-higher psa-higher
                   :basic-band basic-band :div-allowance div-allow
                   :basic-rate basic-rate :higher-rate higher-rate
                   :additional-rate additional-rate
                   :div-basic-rate div-basic-rate :div-higher-rate div-higher-rate
                   :div-add-rate div-add-rate})]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:country :uk :authority authority}
        :functional-commodity commodity
        :components
        [{:kind :investment-income-tax :authority authority
          :base (money/money (+ savings-uk div-uk) commodity)
          :gross-liability (money/money (:savings-tax alloc) commodity)
          :liability (money/money (+ (:savings-tax alloc)
                                     (:dividend-tax alloc)) commodity)
          :line-items (:line-items alloc)
          :jurisdiction-specific-codes
          {:lane :uk-investment-income
           :psa-used (:psa-used alloc)
           :srs-used (:srs-used alloc)
           :dividend-allowance-used (:div-allowance-used alloc)
           :pa-left-from-non-savings (:pa-leftover alloc)
           :marginal-rate-band band}}]}))))
```

### 5.4 The ordering algorithm (pure function)

```clojure
(defn uk-income-tax-allocation
  "Implements ITA 2007 § 16 ordering. Pure function. Returns:
     {:savings-tax  <bd>
      :dividend-tax <bd>
      :line-items   [...]
      :psa-used     <bd>
      :srs-used     <bd>
      :div-allowance-used <bd>
      :pa-leftover  <bd>}

   Algorithm:
     1. PA against non-savings first. Leftover PA cascades to savings.
     2. Compute SRS = max(0, srs - max(0, non-savings - pa)).
     3. Compute PSA from marginal-rate-band (basic: 1000, higher: 500, add: 0).
     4. Apply (leftover-PA + SRS + PSA) to savings income at 0 %.
        Remaining savings @ basic/higher/additional rate per band.
     5. Apply dividend allowance to dividends at 0 %.
     6. Remaining dividends @ dividend-basic/higher/additional per band.
     7. The dividend allowance DOES NOT extend bands — it sits IN the
        band and counts toward higher-rate threshold."
  [{:keys [non-savings savings dividends]}
   {:keys [pa srs psa-basic psa-higher psa-additional div-allowance
           basic-band basic-rate higher-rate additional-rate
           div-basic-rate div-higher-rate div-add-rate]}]
  (let [;; PA against non-savings first
        pa-non-savings (min non-savings pa)
        pa-leftover    (- pa pa-non-savings)
        ;; SRS = 5000 minus £1 per £1 non-savings above PA
        non-savings-above-pa (max 0M (- non-savings pa))
        srs-available  (max 0M (- srs non-savings-above-pa))
        ;; PSA depends on marginal-rate-band
        ;; (the band is determined by total income, which here is
        ;;  non-savings + savings + dividends; for simplicity, the
        ;;  caller pre-computes and passes :marginal-rate-band, OR
        ;;  we run a bracket lookup inline)
        higher-rate-threshold (+ pa basic-band)        ; 50 270
        additional-threshold  125140M
        total-income   (+ non-savings savings dividends)
        band           (cond
                         (>= total-income additional-threshold) :additional
                         (>= total-income higher-rate-threshold) :higher
                         :else :basic)
        psa            (case band :basic psa-basic
                                  :higher psa-higher
                                  :additional 0M)
        ;; Apply zero-rate bands to savings in order
        zero-rate-savings (min savings (+ pa-leftover srs-available psa))
        savings-taxable   (- savings zero-rate-savings)
        ;; Allocate savings-taxable across basic / higher / additional
        ;; (depends on cumulative position: non-savings-after-PA fills
        ;; the basic band first; savings starts where non-savings ends)
        used-by-non-savings (max 0M (- non-savings pa))
        basic-band-remaining-for-savings
          (max 0M (- basic-band used-by-non-savings))
        savings-in-basic   (min savings-taxable basic-band-remaining-for-savings)
        savings-after-basic (- savings-taxable savings-in-basic)
        higher-band-cap (- additional-threshold higher-rate-threshold) ; 74 870
        used-by-savings-in-higher (- used-by-non-savings (- used-by-non-savings))
        ;; ... actual provider implements the band-walking carefully
        savings-tax (+ (* savings-in-basic basic-rate)
                       ;; ... similar walks for higher / additional
                       0M)
        ;; Dividend allowance
        dividends-in-allowance (min dividends div-allowance)
        dividends-taxable      (- dividends dividends-in-allowance)
        ;; Dividends sit on top of non-savings + savings; band-walk again
        position-before-dividends (+ non-savings savings)
        ;; ... band-walk for dividends
        dividend-tax 0M]                 ; ... implement full walk
    {:savings-tax savings-tax
     :dividend-tax dividend-tax
     :line-items [{:line :pa-against-non-savings :label "PA used against non-savings income"
                   :value pa-non-savings}
                  {:line :pa-leftover :label "Leftover PA cascading to savings"
                   :value pa-leftover}
                  {:line :srs-available :label "Starting-rate band available for savings"
                   :value srs-available}
                  {:line :psa :label "Personal Savings Allowance (basic/higher/additional band)"
                   :value psa}
                  {:line :savings-tax :label "Tax on savings income (above zero-rate bands)"
                   :value savings-tax}
                  {:line :div-allowance-used :label "Dividend allowance used"
                   :value dividends-in-allowance}
                  {:line :dividend-tax :label "Tax on dividends (above allowance)"
                   :value dividend-tax}]
     :psa-used (min savings psa)
     :srs-used (min savings srs-available)
     :div-allowance-used dividends-in-allowance
     :pa-leftover pa-leftover}))
```

The algorithm is **non-trivial** — the band walking for savings
then dividends has off-by-one risks at the higher-rate transition.
The v1 implementer should test against the four §2 worked examples
+ the corner cases in [LITRG starting rate for
savings](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-savings-income/starting-rate-savings)
walking through a few low-income retiree scenarios.

### 5.5 Corporate provider conceptual sketch

```clojure
(defrecord UKCorporateInvestmentIncomeProvider [id authority commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (:db ctx)
          postings  (report/report-postings db ...)
          by-tag    (report/marginalize postings :account-tag {:commodity commodity})
          ;; Exempt under Part 9A — for audit only
          exempt-gross   (get-in by-tag [:uk-investment-income/corp-dividend-exempt :value])
          ;; Non-exempt — flows to CT
          taxable-gross  (get-in by-tag [:uk-investment-income/corp-dividend-taxable :value])]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:country :uk :authority authority}
        :functional-commodity commodity
        :components
        [{:kind :investment-income-tax :authority authority
          :base (money/zero commodity)
          :liability (money/zero commodity)
          :line-items [{:line :exempt-dividends-part-9A
                        :label "Dividends exempt under CTA 2009 Part 9A"
                        :value (money/money exempt-gross commodity)}
                       {:line :taxable-dividends
                        :label "Dividends not within an exempt class (to CT)"
                        :value (money/money taxable-gross commodity)}]
          :jurisdiction-specific-codes {:lane :uk-corp-investment-income
                                         :cit-base-additions [taxable-gross]
                                         :exempt-amount exempt-gross}}]}))))
```

### 5.6 The uk-rental-income-provider — out of scope for v1

Rental income (UK property income, ITTOIA Part 3 Ch. 3) is a
separate category. The v1 substrate marker tag
`:uk-investment-income/rental-uk` is reserved; the full provider
is a follow-on. Implementation outline:

- Marginalize rental-income postings by tag.
- Apply property-allowance election (£1 000 — pure threshold).
- Compute net rental profit (gross − allowable expenses, or gross
  − £1 000).
- Folds into the UK PIT provider's non-savings base via
  `:pit-base-additions`.
- Emit a `:credit` for 20 % of mortgage interest (s274A
  restriction).

No new substrate; same shape as the FR rental-income provider
(note 149 §5.6).

---

## §6. Cross-jurisdiction integration check

| Aspect | UK | (cross: FR note 149) | (cross: DE) | (cross: US) |
|---|---|---|---|---|
| Default rate on dividends | Tier 8.75 / 33.75 / 39.35 % (2024-26); 10.75 / 35.75 / 39.35 % (2026+) | PFU 31.4 % (12.8 + 18.6) | Abgeltungsteuer 26.375 % | Qualified 0/15/20 % + NIIT 3.8 % |
| Rate election | NONE (statutory) | Barème (CGI 200 A, 2) | Günstigerprüfung (§32d Abs. 6 EStG) | Ordinary-rate election (Form 4952) |
| Zero-rate allowance | £500 dividend allowance + £0 / £500 / £1 000 PSA + £5 000 SRS | 40 % abattement on dividends (barème only) | €1 000 Sparer-Pauschbetrag | Qualified-dividend rate cap |
| Wrapper exemption | ISA (£20 000/year — comprehensive) | PEA (5y) + assurance-vie (8y) | Riester/Rürup | 401(k) / IRA / HSA |
| Layered ordering | YES — strict (non-savings → savings → dividends) | NO (parallel categories) | NO | NO |
| Corporate-recipient exemption | Part 9A (small: broad; large: 5 exempt classes; > 10 % portfolio also exempt) | Mère-fille 95 % (5 % QPFC; 5 % stake) | §8b KStG 95 % (5 % NABE) | DRD §243 (50/65/100 %) |
| WHT at payment | NO domestic WHT | 12.8 % PAS (CGI 117 quater) | 25 % Kapitalertragsteuer | 24 % backup WHT |
| Trust regime | 39.35 % / 45 % trust rate | Sociétés interposées | Treuhand fiscally transparent | DNI / Form 1041 |

**The UK is unique in the ordering requirement.** All other
jurisdictions tax investment-income components in parallel; UK
stacks them. The kontor substrate handles this purely inside the
provider — no kernel impact — but the implementer needs care:
the helper algorithm in §5.4 is the load-bearing piece.

---

## §7. Open design questions / followups

1. **Single component vs split components for savings + dividends**
   (§5.1). UK SA100 reports them on separate lines; the provider
   could split. v1 implementer's call — substrate supports both;
   recommend the SPLIT shape for cleaner SA100 mapping (two
   components, one per `:kind :investment-income-tax` with a
   `:subcomponent :savings | :dividends` discriminator in
   `:jurisdiction-specific-codes`).

2. **Marginal-rate-band determination location** — should the
   provider derive `:band` itself (from total income) or trust the
   consumer's pre-computed value in `:tax-unit`? CGT provider
   (note 114 §5.1) trusts the consumer. Investment-income provider
   has more data on hand (it knows the gross savings + dividends
   directly), so it CAN compute. Recommendation: derive internally
   (the provider has the full picture); accept consumer override
   via `:tax-unit :marginal-rate-band` for tests / unusual cases.

3. **ISA-wrapped income exposure** — should the substrate post ISA
   income at all, or stay completely silent? FR PEA recommendation
   (note 149 §7 #2) is "stay silent — wrapper asset accrues
   internally." Same recommendation for UK ISA. The
   `:uk-investment-income/dividend-uk-isa-wrapped` /
   `:savings-uk-isa-wrapped` tags exist for the consumer who
   chooses to *record* ISA income for portfolio tracking — the
   provider filters out at compute time.

4. **Rate transition 2024-26 → 2026+** — basic dividend rate rises
   8.75 → 10.75 %; higher 33.75 → 35.75 %. ADR-101 bitemporal
   parameter handles this cleanly; the `:parameter-value` for
   `"UK.IIT.dividend.basic-rate"` has two rows with effective-from
   dates. v1 ships both; the provider reads as-of the period end.
   No provider code change needed for the transition.

5. **PA tapering above £100 000** — the £12 570 PA tapers £1 for
   every £2 of income above £100 000, hitting zero at £125 140
   (the additional-rate threshold floor). This is an income-tax
   computation concern (PIT provider) not an investment-income
   concern per se; but the UK investment-income provider's
   marginal-rate-band determination must reflect post-taper income.
   Recommend: PIT provider exposes `:tax-unit :adjusted-net-income`
   for downstream providers to read.

6. **Foreign-source dividends with treaty WHT** — out of scope for
   v1 (same as FR §7 #3). Tag `:uk-investment-income/dividend-
   foreign-with-credit` reserved; provider documents the gap.

7. **Trust tax** — out of scope; separate provider + entity-kind
   extension (§1.9).

---

## §8. Sources

**UK statutes (legislation.gov.uk — license: Crown copyright / open
government licence)**:

- [Income Tax Act 2007](https://www.legislation.gov.uk/ukpga/2007/3) —
  the principal income tax charging act; § 6 (charge); § 12 (starting
  rate for savings); § 13A (dividend rates); § 16 (ordering rule).
- [Income Tax (Trading and Other Income) Act 2005 (ITTOIA)](https://www.legislation.gov.uk/ukpga/2005/5) —
  Part 3 Ch. 3 (property income); Part 4 Ch. 2 (interest); Part 4
  Ch. 3 (dividends).
- [Finance Act 2016 § 4](https://www.legislation.gov.uk/ukpga/2016/24/section/4) —
  Personal Savings Allowance introduction (2016-04-06 effective).
- [Corporation Tax Act 2009 Part 9A](https://www.legislation.gov.uk/ukpga/2009/4/part/9A) —
  Distribution exemption for corporate recipients.
- [Finance Act 2009 Sch 14](https://www.legislation.gov.uk/ukpga/2009/10/schedule/14) —
  inserted Part 9A into CTA 2009.

**HMRC manuals (gov.uk — license: Crown copyright / OGL)**:

- [Income Tax: Personal Savings Allowance](https://www.gov.uk/government/publications/income-tax-personal-savings-allowance/income-tax-personal-savings-allowance) —
  official PSA description.
- [Income Tax: PSA update](https://www.gov.uk/government/publications/income-tax-personal-savings-allowance-update/income-tax-personal-savings-allowance-update) —
  rate-band interaction.
- [Starting rate of tax for savings — TIIN](https://assets.publishing.service.gov.uk/media/5a7c7485ed915d6969f44fc7/TIIN_8073_8076_the_starting_rate_of_tax_for_savings.pdf) —
  the £5 000 SRS technical impact notice.
- [Tax on savings interest — gov.uk](https://www.gov.uk/apply-tax-free-interest-on-savings) —
  taxpayer overview of bands + bank-paid-gross convention.
- [Tax on dividends — gov.uk](https://www.gov.uk/tax-on-dividends) —
  taxpayer-facing dividend tax rates + allowance.
- [Changes to tax rates for property, savings & dividend income](https://www.gov.uk/government/publications/changes-to-tax-rates-for-property-savings-dividend-income/changes-to-tax-rates-for-property-savings-dividend-income) —
  Autumn-Budget 2025 announcement of the 2026 rate hikes.
- [HMRC INTM651020 — distribution exemption overview](https://www.gov.uk/hmrc-internal-manuals/international-manual/intm651020) —
  CTA 2009 Part 9A structure.
- [HMRC INTM653010 — exemption for non-small companies](https://www.gov.uk/hmrc-internal-manuals/international-manual/intm653010) —
  5 exempt classes.
- [HMRC CTM02060 — dividends and other distributions received](https://www.gov.uk/hmrc-internal-manuals/company-taxation-manual/ctm02060) —
  CT computation.
- [Income Tax when you rent out a property — gov.uk](https://www.gov.uk/guidance/income-tax-when-you-rent-out-a-property-working-out-your-rental-income) —
  rental income basics.
- [ISA Regulations — gov.uk individual savings accounts how ISAs work](https://www.gov.uk/individual-savings-accounts/how-isas-work) —
  ISA tax-free treatment + £20 000 limit.

**Authoritative practitioner / professional commentary**:

- [LITRG — Personal Savings Allowance](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-savings-income/personal-savings-allowance) —
  detailed PSA mechanics + interaction with bands.
- [LITRG — Starting Rate for Savings](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-savings-income/starting-rate-savings) —
  detailed SRS mechanics; the §2.4 worked-example shape.
- [LITRG — Individual Savings Accounts](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/individual-savings-accounts-isas) —
  taxpayer-facing ISA explainer.
- [LITRG — Tax on dividends](https://www.litrg.org.uk/savings-property/tax-savings-and-investments/tax-dividends) —
  dividend allowance + dividend stacks on top of other income.
- [LITRG — Property income](https://www.litrg.org.uk/savings-property/property-income) —
  rental income basics + property allowance.
- [HMRC research-briefing CBP-9993 (Parliament Library, April 2024)](https://researchbriefings.files.parliament.uk/documents/CBP-9993/CBP-9993.pdf) —
  authoritative Library paper on UK income tax structure including
  ordering rule.
- [Bishop Fleming tax tables 2025/26](https://www.bishopfleming.co.uk/sites/default/files/2024-11/bishop_fleming_tax_tables_2025_to_2026.pdf) —
  consolidated rate-band tables.
- [Equiniti — UK dividend tax changes](https://equiniti.com/uk/news-and-views/eq-views/in-the-spotlight-uk-dividend-tax-changes/) —
  historical dividend allowance trajectory £5 000 → £500.
- [Patterson Hall — dividend allowance](https://www.pattersonhallaccountants.co.uk/dividend-allowance/) —
  practitioner walk-through.
- [Whitefield Tax — Tax on Savings and Dividends](https://www.whitefieldtax.co.uk/help/briefings/tax-on-savings/) —
  ordering rule worked examples.
- [PwC UK individual income determination](https://taxsummaries.pwc.com/united-kingdom/individual/income-determination) —
  authoritative summary including ordering rule.
- [Practical Law (Thomson Reuters) — Dividends: tax rules for corporates](https://uk.practicallaw.thomsonreuters.com/1-366-8036) —
  CTA 2009 Part 9A practitioner reference.
- [Trustee Support Services — taxation of trusts from 6 April 2024](https://www.trusteesupportservices.com/trust-taxation-from-6-april-2024/) —
  39.35 % trust dividend rate + £500 de minimis.
- [Mills & Reeve — Trustee update May 2024](https://www.mills-reeve.com/publications/trustee-update-may-2024/) —
  trust rate changes 2024.
- [Tolley — Distributions tax guidance](https://www.lexisnexis.co.uk/tolley/tax/guidance/distributions) —
  practitioner-grade tax-pool mechanics for trusts.
- [Tax Journal — distributions received by small companies](https://www.taxjournal.com/articles/distributions-received-small-companies-purposive-interpretation) —
  Part 9A purposive interpretation.
- [Accounted Ltd — April 2026 dividend tax changes](https://www.accountedltd.com/blog/dividend-tax-rate-changes-april-2026-what-uk-directors-need-to-know) —
  the 2pp rise in basic + higher dividend rates from April 2026.
- [FreeAgent — UK dividend tax 2026/27](https://www.freeagent.com/rates/dividend-tax/) —
  2026/27 rate table.
- [Your Company Formations — directors' guide to UK dividend tax 2024-27](https://www.yourcompanyformations.co.uk/blog/dividend-tax-rates/) —
  multi-year rate table.
- [1st Formations — UK dividend allowance 2026/27](https://www.1stformations.co.uk/blog/dividend-tax-allowance/) —
  the "£500 counts towards the bands" detail.
- [Landlord Studio — UK landlord tax 2025/26](https://www.landlordstudio.com/uk-blog/landlord-tax-rates-relief-and-changes) —
  property allowance + mortgage-interest restriction.

**kontor substrate cited (file:line)**:

- `src/kontor/period_tax_provider.clj:51-60` — closed `period-tax-kinds` enum (G1 addition `:investment-income-tax` shared with FR note 149).
- `src/kontor/personal_income_tax.clj:60-118` — adjustment-layer composition pattern.
- `src/kontor/book.clj:296-330` — `declare-dividend!` / `distribute-dividend!` verbs (corporation side); consumer-side `receive!` for individual dividend receipt.
- `src/kontor/report.clj` — `marginalize` (σ_E for rolling up income postings by tag).
- `src/kontor/statute.clj` — `parameter-value-at` (bitemporal parameter read for the per-band rates + allowances).
- `modules/l10n-uk/src/kontor/l10n_uk/cgt_provider.clj:65-115` — the closed `asset-classes` + `income-bands` (G3 extension of `:income-band` to include `:additional`).
- `modules/l10n-uk/src/kontor/l10n_uk/cgt_provider.clj:200-310` — `individual-components` shape the investment-income provider mirrors.
- `modules/l10n-uk/src/kontor/l10n_uk/cgt_statute.clj` — the parameter installation pattern the UK investment-income statute follows.
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_provider.clj:790-810` — sibling `fr-personal-cgt-provider` constructor (note 149 mirrors).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §2.6 — dividend verbs design.
- `doc/research/114-uk-cgt-fit.md` §1.1 + §5.1 — UK CGT post-Oct-2024 rate alignment + `:income-band` design (G3 extends).
- `doc/research/149-fr-investment-income-fit.md` — sibling FR note. §3.1 chart-of-accounts pattern; §5.1 sibling provider shape; G1 enum addition shared.
- `doc/research/102-period-tax-provider-design.md` §7 — the `period-tax-kinds` enum design rationale.
- `doc/research/119-adr-101-draft.md` — `:parameter` substrate that carries the UK rate-band + allowance amounts.

---

End of note 150.

---
date: 2026-05-24
title: 156 — IN investment-income regimes — substrate fit for Phase C2
audience: maintainer + the Phase C2 `in-investment-income-provider` implementation agent
status: research-before for the IN investment-income companion (sibling to `in-cgt-provider` of note 131) + the future `in-investment-income-provider`; no code
---

# 156 — IN investment-income regimes: substrate fit for Phase C2

India's investment-income regime sits in a different shape from
BR (note 155) and MX (note 157): there is **no separate definitive
withholding** that closes the recipient's liability. **All** investment
income — dividends, interest, mutual-fund IDCW — flows into the
recipient's slab-rate income and is reconciled annually on ITR-2/3/6.
Withholding sections (§194 dividends, §194A interest, §194K mutual
funds, §195 NR payments) serve only as **prepayments** the recipient
credits.

This is a **substantively different** posture from the pre-2020 regime
(when DDT was definitive at the company level), and very different
from the BR / MX dividend-WHT regimes that effectively close
liability at source. India's design folds investment income into the
already-rich PIT machinery, which **the substrate's existing
`in-period-tax-provider` already handles** via the slab/§87A/surcharge/
cess stack (note 105, §87A as a base-aware adjustment item).

What's *new* for kontor at the investment-income companion is:
1. The **section vocabulary** for routing different income kinds to
   the right slab/non-slab treatment.
2. The **TDS-as-prepayment** input shape (parallel to BR IRRF and
   MX notary withholding).
3. The **§115A NRI lane** (20 % flat on dividends/royalties/fees for
   NRIs) — the one truly **definitive** withholding the substrate
   meets.
4. The **§80M chain-relief** for inter-corporate dividends (a
   deduction the recipient PJ takes if it redistributes within one
   month before ITR-filing).
5. The **§80TTA / §80TTB** small-savings-interest deductions
   (₹ 10k / ₹ 50k respectively).
6. The **dividend-income surcharge cap** at 15 % per Finance Act 2024
   (lower than the 25 % / 37 % top-bracket surcharges for other
   income kinds).
7. The **2026 renumbering** under the new Income-tax Act 2025
   effective 1 April 2026 — §194 → §393(1); §194A → §391 etc.

This note (a) summarises each lane with the 2026-enacted state, (b)
walks two worked examples (PF mid-tier + NRI), (c) assesses fit
against the shipped substrate, (d) names the data gaps, (e) sketches
the `in-investment-income-provider`, (f) cites sources.

---

## §1. The IN investment-income regime — by source and recipient

### 1.1 Dividends — slab rate, §194 / §393(1) TDS 10 %

Source: [Income Tax India — Taxation of Dividend and Interest](https://www.incometaxindia.gov.in/taxation-of-dividend-and-interest);
[Income Tax India — Tax Treatment of Dividend Received (FA 2025) PDF](https://incometaxindia.gov.in/tutorials/tax%20treatment%20of%20dividend%20received.pdf);
[CleartTax — Tax on Dividend Income](https://cleartax.in/s/how-dividends-taxable);
[Bajaj Finserv — Section 194 TDS on Dividends](https://www.bajajfinserv.in/investments/section-194-income-tax-act);
[CleartTax — Section 194 (FY 2025-26)](https://cleartax.in/s/section-194-income-tax-act);
[Motilal Oswal — Section 194 TDS on Dividends](https://www.motilaloswal.com/personal-finance/tax/section-194-of-income-tax-act-tds-on-payment-of-dividend);
[tdsman — Section 393(1) IT Act 2025](https://blog.tdsman.com/2026/05/tds-on-dividend-section-3931-section-194/).

#### 1.1.1 The post-FA-2020 framework — DDT abolished

The Finance Act 2020 abolished the **Dividend Distribution Tax
(DDT)** (paid by companies at ~17.65 % grossed-up rate on
distributions, which had insulated the recipient from further
taxation) for FY 2020-21 onwards. Dividends are now taxed in the
**shareholder's hands** at:
- **Resident individuals / HUFs / firms**: slab rate (added to total
  income; the 30 %-bracket + surcharge + cess for high earners).
- **Resident domestic companies**: 30 % corporate rate (or 25 % for
  certain SMEs), with **§80M relief** (see §1.5).
- **NRIs**: 20 % flat under **§115A** (the rare definitive WHT — see
  §1.4).

#### 1.1.2 Section 194 / §393(1) — TDS on dividends

When an Indian company pays dividends to a resident shareholder
whose aggregate dividend from that company in a financial year
**exceeds ₹ 10,000** (raised from ₹ 5,000 by Finance Act 2025
effective 1 April 2025; was ₹ 5,000 pre-FY 2025-26), it must
deduct TDS at:
- **10 %** with PAN (the default);
- **20 %** if PAN not furnished (§206AA penalty rate).

The renumbering under the **Income-tax Act 2025** moves §194 to
**§393(1)** effective **1 April 2026**. Rate and threshold unchanged.

Critically: this TDS is a **prepayment**. The recipient claims it on
their ITR-2/3 against the dividend income's slab-rate tax. If TDS
exceeds the slab tax (low-income recipient), the excess is refunded.

#### 1.1.3 Section 115BBDA — REPEALED (FA 2020)

The additional **10 % flat** on dividend income exceeding **₹ 10 L**
for resident individuals — applicable FY 2016-17 through FY 2019-20 —
was **repealed** by Finance Act 2020. **Do not model.**

#### 1.1.4 Dividend surcharge cap at 15 % (FA 2024)

Important post-FA-2024 detail: the **enhanced surcharge** of 25 %
and 37 % that applies to income above ₹ 2 crore and ₹ 5 crore
respectively in other income categories is **capped at 15 %** for
dividend income (and for §111A/§112/§112A capital gains, per note
131 §1.2). The 10 % and 15 % bands (income > ₹ 50 L and > ₹ 1 cr
respectively) DO apply.

Substrate impact: the IN PIT provider already handles surcharge
bands (see `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj`).
The investment-income provider's dividend component returns the
base + tax; the **surcharge cap at 15 %** is an **adjustment-item
override** that the provider sets on the dividend component
specifically (different from the wage/business income surcharge
band).

#### 1.1.5 4 % Health & Education cess

Applies on (tax + surcharge) for **all** income categories,
including dividends. The IN PIT provider already applies this. The
investment-income provider needs only ensure the cess applies to
its own components (the existing adjustment-layer dispatches by
component).

### 1.2 Interest income — slab rate, §194A / §391 TDS 10 %, §80TTA/B deductions

Source: [Bajaj Finserv — Section 194A TDS on interest income](https://www.bajajfinserv.in/investments/section-194a-income-tax-act);
[CleartTax — Section 80TTA](https://cleartax.in/s/section-80tta);
[CleartTax — Section 80TTB Senior Citizens](https://cleartax.in/s/section-80ttb-senior-citizens-deduction);
[Income Tax India — Taxation of Dividend and Interest](https://www.incometaxindia.gov.in/taxation-of-dividend-and-interest).

#### 1.2.1 §194A TDS on bank/FD interest

Banks deduct **10 %** TDS on interest paid to a depositor exceeding
**₹ 40,000 per year per bank** (₹ 50,000 for senior citizens).
**20 %** if PAN not furnished.

The renumbering under the IT Act 2025 moves §194A to **§391**
effective 1 April 2026. Rate and thresholds unchanged.

Interest is fully **slab-rate** taxable. TDS is a prepayment.

#### 1.2.2 §80TTA — savings-account interest deduction

Resident individuals (under 60) can deduct up to **₹ 10,000** per
year of **savings-bank account** interest (from banks, co-ops, post
office). NOT applicable to FD interest. NOT available under the
**new tax regime** (§115BAC default from FY 2023-24 onwards).

#### 1.2.3 §80TTB — senior-citizen interest deduction

Resident individuals **≥ 60** years can deduct up to **₹ 50,000**
per year of **all** interest (savings, FD, RD, post-office). Replaces
§80TTA for seniors (a senior cannot claim both). NOT available
under the new tax regime.

#### 1.2.4 New tax regime (§115BAC, default from FY 2023-24)

Under §115BAC default new regime, **§80TTA and §80TTB are NOT
available**. Most resident individuals are on the new regime
post-FA-2023 (the new regime became default). The investment-income
provider needs to **know which regime** the taxpayer is on:
`:inputs :in-tax-regime :new-regime-115bac | :old-regime`.

### 1.3 Mutual fund distributions (IDCW) — §194K / §392 TDS 10 %, slab rate

Source: [CleartTax — Section 194K](https://cleartax.in/s/section-194k);
[Bajaj Finserv — Section 194K TDS on Mutual Funds](https://www.bajajfinserv.in/investments/section-194k-of-income-tax-act);
[Tax2Win — Section 194K Mutual Fund](https://tax2win.in/guide/tds-section-194k-tds-on-mutual-fund);
[Quicko — Section 194K TDS Mutual Funds](https://learn.quicko.com/section-194k-tds-dividend-mutual-funds);
[AMFI — Tax Regime for Mutual Funds](https://www.amfiindia.com/investor/knowledge-center-info?zoneName=TaxRegimeForMutualFunds).

Section 194K (introduced FA 2020 — replaced the prior §194-IBB
dividend-on-MF-units mechanism) covers TDS on **IDCW** (Income
Distribution cum Capital Withdrawal) from mutual funds:

- **10 %** if aggregate IDCW from the AMC > **₹ 10,000** per FY
  (raised from ₹ 5,000 by Finance Act 2025, eff. 1 April 2025).
- **20 %** if PAN not furnished.
- Does **NOT** apply to mutual-fund **redemption gains** — those
  are capital gains under §112A (equity MF) / §50AA (debt MF
  post-1-Apr-2023) etc., handled by the IN CGT provider (note 131).
- NRI mutual fund IDCW falls under §195 (not §194K).

The renumbering under the IT Act 2025 moves §194K to **§392**
effective 1 April 2026. Rate and threshold unchanged.

IDCW is **slab-rate** taxable in the unitholder's hands. TDS is a
prepayment.

### 1.4 NRI dividend / interest income — §115A 20 % flat (the definitive lane)

Source: [Bajaj Finserv — NRI dividend tax (§115A 20 %)](https://www.bajajfinserv.in/investment/tax-on-dividend-income);
[Income Tax India — Tax Treatment of Dividend Received (FA 2025)](https://incometaxindia.gov.in/tutorials/tax%20treatment%20of%20dividend%20received.pdf);
[Nippon India MF — Tax Reckoner FY 2025-26 (PDF)](https://mf.nipponindiaim.com/LearnAndInvest/TaxRateDocuments/Tax-Reckoner-for-FY-2025-26.pdf).

For NRIs (non-resident individuals + foreign companies):

| Income kind                                | Rate (§115A)  | Section / WHT |
|--------------------------------------------|---------------|----------------|
| Dividend (from Indian company, not §115AB) | **20 %** flat | §115A + §195 TDS at 20 % |
| Interest from infrastructure debt fund     | **5 %**       | §115A + §194LBA |
| Interest from rupee-denominated bonds      | **5 %**       | §194LD          |
| Royalty / FTS                              | **20 %** (default) or **10 %** (qualified)  | §115A + §195 |
| Capital gains (listed equity LTCG)         | 12.5 % (§112A) — flat | §195 |
| Capital gains (listed equity STCG)         | 20 % (§111A) — flat | §195 |
| Other capital gains                        | per §112       | §195 |
| Salary / pension / business income         | slab           | §192 etc.     |

§115A is the **definitive** lane for NRI dividend / interest: the
20 % flat tax (plus surcharge + cess, capped at 15 % surcharge per
FA 2024) is the **final liability** — the NRI is **NOT** required
to file an ITR if §115A income is the only income and TDS was
correctly deducted. **DTAA can reduce the rate** (Form 10F + TRC
+ beneficial-ownership declaration required); typical treaty rates
are 10-15 % on dividends.

For substrate: the NRI lane is the **only** investment-income
component that produces a **definitive** liability on this
provider's side (the other lanes feed PIT). The provider returns a
distinct `:in-nri-§115A` component for NRI recipients.

### 1.5 Inter-corporate dividend — §80M chain-relief

Source: [CleartTax — Section 80M](https://cleartax.in/s/section-80m);
[Angel One — Section 80M Income Tax Act](https://www.angelone.in/knowledge-center/income-tax/section-80m-of-income-tax-act);
[Income Tax India — Section 80M (new)](https://www.incometaxindia.gov.in/w/section-80m-new-);
[finnovate — Mutual Fund Taxation FY 2025-26](https://www.finnovate.in/learn/blog/mutual-fund-taxation-india-fy-2025-26).

**§80M** prevents cascading taxation when a domestic company receives
dividends from another domestic company AND then redistributes them
to its own shareholders. Conditions:

- Receiver: a domestic company.
- Income: dividends from any domestic company **or** from a foreign
  company **or** from a business trust.
- Redistribution: the receiving company must **distribute dividends
  to its own shareholders** within a window — **one month before the
  due date of filing** the ITR (typically 30 September of the
  assessment year for non-audit cases).
- Relief: the receiving company deducts the lesser of (received
  dividend) and (redistributed dividend) from its taxable income.

Net effect: the dividend "flows through" the receiving company with
no incremental CIT.

For substrate: the §80M deduction lives on the **CIT** provider
(`in-cit-provider`), not on this investment-income provider. But the
investment-income provider's classifier needs to know that
**dividend income on a corporate recipient that the recipient
redistributes within the §80M window is not its own component** —
it's a CIT-base reduction. Recommendation: the provider emits the
gross dividend in its own component (so the audit chain is
correct), AND simultaneously emits a **`:base-transform-deduct`
intent toward the CIT provider** for the qualifying portion (parallel
to how the IN CGT slab-rate STCG component folds into PIT via
`:base-transform-add` — note 131 §5.2).

### 1.6 What does NOT exist (or no longer exists)

- **No DDT** — abolished FA 2020. Do not model.
- **No §115BBDA** — repealed FA 2020. Do not model.
- **No FII-style PF dividend exemption** like BR's FII regime.
- **No qualified-dividend / ordinary-dividend split** like US.
- **No dividend imputation / franking** like AU. Indian individuals
  pay slab-rate on the full grossed-up dividend without credit for
  underlying CIT.

---

## §2. Worked examples

### Example A — Resident individual, mixed dividend + FD interest + MF IDCW

Mr. Mehta, resident individual, FY 2025-26, new tax regime (§115BAC
default):

- Salary: ₹ 2,400,000 (₹ 200k/month). The new regime gives a standard
  deduction of ₹ 75,000.
- Dividends from RIL: ₹ 60,000 (TDS @ 10 % = ₹ 6,000 since > ₹ 10k
  threshold per §194/§393(1)).
- Dividends from Infosys: ₹ 8,000 (no TDS — under ₹ 10k threshold).
- FD interest (HDFC Bank): ₹ 120,000 (TDS @ 10 % = ₹ 12,000 since
  > ₹ 40k §194A threshold).
- Savings-account interest: ₹ 7,000 (no TDS — under threshold).
- MF IDCW from SBI MF: ₹ 25,000 (TDS @ 10 % = ₹ 2,500 since > ₹ 10k
  threshold per §194K/§392).

**Total taxable income (new regime)**:
- 2,400,000 + 60,000 + 8,000 + 120,000 + 7,000 + 25,000 − 75,000 =
  ₹ 2,545,000.
- (§80TTA NOT available under new regime → savings interest fully
  taxable.)

**Tax (new regime FY 2025-26 slabs)**:
- 0-3 L: nil → ₹ 0
- 3-7 L: 5 % × 4 L = ₹ 20,000
- 7-10 L: 10 % × 3 L = ₹ 30,000
- 10-12 L: 15 % × 2 L = ₹ 30,000
- 12-15 L: 20 % × 3 L = ₹ 60,000
- 15-20 L: 25 % × 5 L = ₹ 125,000
- > 20 L: 30 % × 5.45 L = ₹ 163,500
- **Subtotal**: ₹ 428,500
- Surcharge: 10 % (income 50 L − 1 cr) × ₹ 428,500 — but Mr. Mehta's
  total income of ₹ 25.45 L is below the ₹ 50 L threshold, so **no
  surcharge applies**.
- 4 % Health & Education cess: 428,500 × 0.04 = ₹ 17,140.
- **Total tax**: ₹ 445,640.

**TDS prepayments**: 6,000 + 12,000 + 2,500 = ₹ 20,500.

**Tax payable on ITR-2 filing**: 445,640 − 20,500 = **₹ 425,140**
(self-assessment due 31 July 2026; the TDS would also have included
salary TDS via §192, not counted here).

Substrate trace: the consumer wires `in-period-tax-provider`
(existing PIT) for the slab calc; `in-investment-income-provider`
(new) supplies the investment-income additions to the base AND the
TDS prepayments via `:inputs :in-tds-withheld`. The PIT provider
reads the `:base-transform-add` to add dividend/interest/IDCW to
total income, runs the slab + cess.

**No standalone tax-component** is emitted by the investment-income
provider in this resident-individual case; it's all routing into
PIT.

### Example B — NRI dividend recipient with treaty reduction

Mr. Patel, US resident (NRI), holds Indian equity:

- Dividends from RIL: ₹ 1,500,000.
- Indian custodian withholds TDS @ 20 % (§115A + §195) = ₹ 300,000.
- Mr. Patel's CA files Form 10F + US TRC + beneficial-ownership
  declaration before payment, claiming the **India-US DTAA reduced
  rate of 15 %** on portfolio dividends (per the existing US-IN treaty
  protocol).
- Custodian re-applies: TDS @ 15 % = ₹ 225,000.
- Plus 15 % surcharge (NRI income > ₹ 50 L, FA-2024 dividend cap) +
  4 % cess: ₹ 225,000 × 1.15 × 1.04 = ₹ 269,100.

This is **definitive** under §115A; Mr. Patel **need not file an
ITR-2** if §115A income is his only Indian income.

Substrate trace: the `in-investment-income-provider`, on the
`:partner` classifier seeing the recipient is an NRI (`:partner/
jurisdiction :US`, `:partner/tax-residence :US`, NOT
`:partner/tax-residence :IN`), routes to the **§115A component**:

```clojure
{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-nri-§115A-dividend]
 :base 1500000M
 :schedule (ts/flat 0.20M)                       ;; or 0.15M post-treaty
 :adjustment-items
 [{:kind :surtax :name :surcharge-on-tax :rate 0.15M}      ;; capped at 15%
  {:kind :surtax :name :health-education-cess :rate 0.04M}]
 :line-items [:treaty-applied :form-10f-on-file]}
```

This is the **definitive lane** — no PIT coupling, no slab fold.

---

## §3. Substrate fit assessment

The IN regime stresses different substrate primitives than BR or MX,
because most of the work flows through the existing PIT provider.

### 3.1 Dividends, interest, IDCW (resident) — base-transform to PIT

These three lanes are **slab-rate** for residents — they need to be
**added to the PIT base**, not taxed separately. Pattern: this
provider emits **`:base-transform-add` items** into the
`in-period-tax-provider`. Exactly the pattern of the IN CGT
slab-rate STCG fold (note 131 §5.2).

```clojure
{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-dividend-resident-slab]
 :base ...           ;; sum of dividend income for the FY
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :in-pit
   :amount <dividend total>
   :category :income-from-other-sources}]
 :prepaid <TDS withheld>}
```

The substrate accommodates this via the existing
`:base-transform-add` algebra (note 105). **Substrate impact:
ZERO new primitives.**

### 3.2 NRI lane — standalone component with rate override

The §115A 20 % flat is a **standalone** component with `(ts/flat
0.20M)` (or DTAA-reduced via `:inputs :in-dtaa-rate-override`). This
is straightforward; the existing surtax pattern handles the surcharge
+ cess. **Substrate impact: ZERO new primitives.**

### 3.3 Dividend surcharge cap (15 %) — adjustment-item override

The 15 % cap on the dividend surcharge (vs. the 25 % / 37 % top
brackets for other income) needs the provider to **specify the
surcharge cap per component**. The note-105 adjustment layer is
keyed by `:base` (the component) — a per-component surcharge schedule
sits in `:adjustment-items`. The provider emits a custom
`:cap 0.15M` on the dividend surcharge adjustment item.

The IN PIT provider's `surcharge-with-marginal-relief` (per
`modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj:166-187`)
runs on the **total** tax; the per-component cap requires a small
adjustment to how the surcharge interacts. Two options:

1. **The investment-income provider computes its own surcharge on its
   dividend component** (with the 15 % cap), emits a `:surtax`
   adjustment item, and the consumer wires the two providers' results
   together (`in-pit-provider` for non-dividend surcharge,
   `in-investment-income-provider` for dividend surcharge).
2. **Extend the IN PIT provider** to accept per-component surcharge
   caps via `:inputs :in-surcharge-caps {:dividend 0.15M :ltcg 0.15M
   :stcg-§111A 0.15M}`; the PIT provider applies the cap on the
   component's surcharge sub-computation.

**Recommendation**: Option 1 for v1 (simpler, doesn't touch PIT
provider). Option 2 if real-world wiring frequently splits
components across providers (probable; promote in v2).

**Substrate impact: ZERO new kernel primitives.**

### 3.4 §80M — base-transform-deduct to CIT

The inter-corporate dividend chain-relief is a CIT-side deduction.
This provider, on detecting a domestic-company recipient with a
qualifying redistribution, emits a `:base-transform-deduct` into the
`in-cit-provider`. Mirror of §3.1 but on the corporate side.

**Substrate impact: ZERO new primitives.**

### 3.5 §80TTA / §80TTB — old-regime base-transform-deduct to PIT

Under the **old regime**, the savings-interest deduction is a PIT
base reduction. The investment-income provider's classifier reads
`:inputs :in-tax-regime`; if `:old-regime`, emits a
`:base-transform-deduct` for the qualifying interest amount (capped
at ₹ 10k / ₹ 50k). Under `:new-regime-115bac`, no deduction emitted.

**Substrate impact: ZERO.**

### 3.6 TDS prepayments — `:inputs` map

The §194/§393(1), §194A/§391, §194K/§392, §195 TDS amounts come from
**outside** the investment-income provider (broker/bank/AMC TDS
statements, Form 26AS). The consumer feeds them as:

```clojure
:inputs {:in-tds-withheld
         {:by-section
          {:194    <dividend TDS Money>
           :194A   <interest TDS Money>
           :194K   <MF IDCW TDS Money>
           :195    <NR TDS Money>
           :194-IA <real-estate TDS Money>  ;; reuses note 131 IRRF shape
           :194-LA <compulsory acq Money>}}}
```

Mirror of BR IRRF (note 130 §4 Gap B) and MX notary withholding
(note 132 §4 Gap C). The provider sums by section, credits the
final liability (resident lanes: against the PIT liability; NRI
lane: against the §115A liability).

**Substrate impact: ZERO** (reuses pattern).

### 3.7 IT Act 2025 renumbering — bitemporal section vocabulary

The provider's section-keyword vocabulary needs to handle both the
1961 Act numbers (§194, §194A, §194K, §115A) AND the 2025 Act
numbers (§393(1), §391, §392, etc.) effective 1 April 2026. Two
options:

1. **Keep 1961 numbering** as the canonical section keyword; document
   the 2025-Act equivalent in a comment. The economic rules are
   unchanged.
2. **Migrate to 2025 numbering** as canonical; document 1961
   equivalent.

**Recommendation**: keep 1961 numbering as canonical. Rationale: (a)
established practitioner vocabulary; (b) tutorials and commentary
will use both for years to come; (c) the substrate can carry both
via `:concept-iri` (ADR-090) — e.g.
`https://taxonomy.cbdt.gov.in/section-194` and
`https://taxonomy.cbdt.gov.in/it-act-2025/section-393-1` both alias
to the same kontor classification. Document the renumbering as a
comment in the provider; do not refactor section keywords.

**Substrate impact: ZERO** (uses `:concept-iri`).

### 3.8 Bottom line — substrate posture

**Zero schema changes.** IN investment income fits the shipped shape
with:
- 4 ADR-101 `:parameter`s (the §194/394A/194K thresholds and §115A
  rates).
- ~8 `:inputs` map keys: `:in-tax-regime`,
  `:in-tds-withheld`, `:in-dtaa-rate-override`,
  `:in-dividend-by-source`, `:in-interest-by-source`,
  `:in-mf-idcw-by-amc`, `:in-§80m-redistribution`,
  `:in-savings-interest`.
- 1 new `:audit-doc/category` (`:in-form-10f-tax-residence-cert`).
- 0 new schema attrs.

Open-vocabulary extensions in `kontor-l10n-in`. Kernel untouched.

---

## §4. Concrete data gaps

### Gap A — TDS-section vocabulary alignment

The `:inputs :in-tds-withheld :by-section` map keys (§194, §194A,
§194K, §195, etc.) currently overlap with §194-IA / §194-LA that the
IN CGT provider (note 131 §5.5) consumes. Recommend **shared
namespace** — one `:in-tds-withheld :by-section` map that both
providers read, each filtering for the sections relevant to them.

**Resolution**: documentation convention. The CGT provider reads
{§194-IA, §194-LA, §195}; the investment-income provider reads
{§194, §194A, §194K, §195}. Overlapping (§195) — see Gap B.

**Substrate impact: ZERO.**

### Gap B — §195 NR TDS — shared between CGT (gain) and investment-income (dividend / interest)

§195 covers any payment to a non-resident — both capital gains
(§111A/§112/§112A flat rates) and §115A dividend/interest. The
consumer's bank/custodian may report a single §195 amount mixing the
two. The two providers each need only **their** share.

**Resolution**: the consumer pre-splits the §195 number per income
kind before feeding to providers. The bank's TDS statement
(typically "TDS u/s 195 on dividend" vs "TDS u/s 195 on long-term
capital gain") makes this attribution explicit. If the consumer
cannot split, the consumer can route the entire §195 to the
investment-income provider (which credits against §115A liability)
and the CGT provider sees ₹ 0 §195 — they may need to refund-claim
the over-attribution on the recipient's return.

**Substrate impact: ZERO**, but the providers' docstrings must
spell out the consumer-side split convention.

### Gap C — Per-source breakdown for dividends + interest + MF IDCW

The §194 ₹ 10k threshold is **per payer per FY** — dividends from
RIL aggregate separately from dividends from Infosys. Similarly,
§194A ₹ 40k threshold is **per bank per FY**, §194K ₹ 10k threshold
is **per AMC per FY**. The provider needs per-source totals.

**Resolution**: provider-side fold over the period's investment
income postings, grouped by `:posting/partner` (the dividend
payer / bank / AMC). Mirror of BR's per-payer per-month
aggregation (note 155 §3.1). No schema gap.

But: the per-source aggregation requires that the consumer **post
each receipt with `:posting/partner` set correctly**. A consumer
booking dividend receipts as a single aggregated entry per
month (without partner attribution) loses the per-payer detail.
Document the convention.

### Gap D — Old-regime vs new-regime classifier

The taxpayer's regime gates §80TTA / §80TTB. The substrate has no
first-class "tax regime" entity. The consumer feeds it as
`:inputs :in-tax-regime :new-regime-115bac | :old-regime` per tax
year. ADR-101 could carry the per-year default (the new regime
became default FY 2023-24); the consumer overrides if the taxpayer
opted out.

**Resolution**: `:inputs` plus a default in the provider config.
ADR-101 `:parameter :in/default-tax-regime` keyed by FY for the
provider's fallback. **Substrate impact: ZERO**.

### Gap E — §80M one-month redistribution window

The §80M deduction requires that the receiving company redistribute
its received dividend **one month before** the ITR-filing due
date. The substrate has no first-class "redistribution event"
entity; the dividend-out event is captured by
`book.declare-dividend!` (ADR-095), and the dividend-in event is a
receipt posting.

**Resolution**: the provider's classifier walks the corporate
recipient's dividend-out distributions, sums those whose
**effective-date** falls within the window from each dividend-in
event, attributes the §80M-eligible portion. Provider-side fold; no
schema gap.

The IT Act 2025 §80M moves to **§134** (the new numbering) but the
rules are unchanged.

**Substrate impact: ZERO.**

### Bottom line — schema posture

**Zero schema changes.** IN fits the shipped shape with ~8 new
`:inputs` map keys, 4 ADR-101 parameters, 1 `:audit-doc/category`,
and provider-side folds. Kernel and disposal-companion untouched.

---

## §5. `in-investment-income-provider` sketch

### 5.1 Component count

The provider returns ONE `TaxReturnFacts` per assessed entity per
FY with up to **five components**, by recipient kind + income kind:

```clojure
;; Resident individual / HUF / firm — three folds to PIT
{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-dividend-resident-slab]
 :base ...                                ;; FY dividend total
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :in-pit :amount ... :category :income-from-other-sources}]
 :prepaid <§194 TDS>
 :surcharge-cap 0.15M                    ;; per FA 2024
 :line-items [:per-payer-breakdown]}

{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-interest-resident-slab]
 :base ...
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :in-pit :amount ... :category :income-from-other-sources}
  {:to-provider :in-pit :amount <80TTA-or-80TTB-deduction>
   :category :chapter-VI-A-deduction :sign :negative}]
 :prepaid <§194A TDS>}

{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-mf-idcw-resident-slab]
 :base ...
 :schedule :delegated-to-pit
 :base-transform-add
 [{:to-provider :in-pit :amount ... :category :income-from-other-sources}]
 :prepaid <§194K TDS>}

;; Resident company — §80M relief
{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-corp-dividend-§80M]
 :base ...                                ;; received dividend total
 :schedule :delegated-to-cit
 :base-transform-add
 [{:to-provider :in-cit :amount <received> :category :income-from-other-sources}
  {:to-provider :in-cit :amount <§80M-deduction-amount>
   :category :chapter-VI-A-deduction :sign :negative}]
 :prepaid <§194 TDS>}

;; NRI — standalone §115A
{:kind :investment-income-tax :authority :in-cbdt
 :composed-of [:in-nri-§115A-dividend]
 :base ...
 :schedule (ts/flat 0.20M)                ;; or DTAA reduced
 :adjustment-items
 [{:kind :surtax :name :surcharge-on-tax :rate <band> :cap 0.15M}
  {:kind :surtax :name :health-education-cess :rate 0.04M}]
 :prepaid <§195 TDS-on-NR-dividend>
 :line-items [:treaty-rate-applied :form-10f-on-file]}
```

### 5.2 Schedule algebra

- Resident lanes: `:delegated-to-pit` (no schedule of own — folds
  base into PIT).
- Corporate lane: `:delegated-to-cit`.
- NRI lane: `(ts/flat 0.20M)` (or `(ts/flat <treaty-rate>)`).
- Surcharge cap: `:cap 0.15M` on the dividend / §115A surtax
  adjustment items (per note 105).
- Cess: `(ts/flat 0.04M)` as surtax on the tax (per note 105).

### 5.3 The TDS-credit pass

```clojure
(defn tds-credit-per-section
  "Given the period's :inputs :in-tds-withheld :by-section map and the
   computed per-component tax, allocate TDS by section to the correct
   component. §194 → dividend resident slab; §194A → interest; §194K →
   MF IDCW; §195 → NR §115A. The allocation is straightforward (one
   section maps to one component) — no two-pass needed."
  [tds-by-section components]
  ...)
```

The NRI §195 TDS is credited against the §115A liability **first**;
excess is refunded.

### 5.4 The §80M two-pass fold

```clojure
(defn §80m-deduction
  "For a corporate recipient, given dividends-received-this-FY and
   dividends-redistributed-within-window, compute the §80M deduction =
   min(received, redistributed)."
  [received-amount redistribution-amount filing-due-date]
  ...)
```

The window check uses `:transaction/effective-date` on the
redistribution. Provider-side fold; no schema gap.

### 5.5 Authority and emission

All lanes file to **CBDT** (`:in-cbdt`):
- Resident individuals: ITR-2 / ITR-3 (Schedule **OS** — Income from
  Other Sources).
- Corporations: ITR-6 (Schedule **OS** + Schedule **VI-A** for §80M
  deduction).
- NRIs: ITR-2 (if other Indian income) OR exempt from filing if
  §115A is the only income and §195 TDS was correctly deducted.
- TDS reconciliation: Form 26AS auto-pull (the provider doesn't
  parse 26AS; the consumer pre-loads).

A v2 `kontor-l10n-in-investment-income-emit` extension can populate
ITR-2/3/6 Schedule OS JSON from the disposal log + dividend/interest
ledger. v1 ships the computation only.

### 5.6 Substrate stress this provider surfaces

- **Per-source aggregation via `kontor.report/marginalize`**: same
  pattern as BR note 155 §3.1. Stable.
- **Multi-component `:base-transform-add` to PIT + CIT**: this
  provider is the first to fold into **both** providers in one
  return (dividend-to-PIT for individuals + dividend-to-CIT for
  corporations). The dispatch is on
  `:disposal/subject-form`-equivalent — call it
  `:partner/legal-form` or pass via `:inputs :in-recipient-form`.
- **Per-component surcharge cap**: the 15 % cap on dividend
  surcharge vs. the 25/37 % bands for other income surfaces a small
  PIT-provider design question (centralised vs. distributed cap
  handling). v1 keeps it on this provider; v2 may centralise.
- **`:concept-iri` for IT Act 1961 ↔ 2025 dual numbering**: this is
  the first kontor provider where section renumbering is meaningful.
  Sets a precedent for future IT-Act-2025-aware tooling.

Total: **0 kernel changes**, **0 disposal-companion changes**, **1
provider** (`in-investment-income-provider.clj`), **1 statute file**
(`in-investment-income-statute.clj`), **1 test file**. Within the
note 107 conservative posture.

---

## §6. ADR-101 statute-as-data — what IN investment-income writes

The provider stays record-shaped (Phase 2 / C2 per note 102 §10).
ADR-101 parameters:

```clojure
;; The §194/§393(1) dividend TDS threshold
{:parameter/code :in/dividend-tds-threshold
 :parameter/jurisdiction :in
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-04-01"
   :parameter-value/amount 5000M
   :parameter-value/currency :INR}
  {:parameter-value/effective-from #inst "2025-04-01"
   :parameter-value/amount 10000M
   :parameter-value/currency :INR}]}

;; The §194/§393(1) rate
{:parameter/code :in/dividend-tds-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-04-01"
   :parameter-value/rate 0.10M}]}
{:parameter/code :in/dividend-tds-rate-no-pan
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-04-01"
   :parameter-value/rate 0.20M}]}

;; §194A bank interest threshold (and senior-citizen variant)
{:parameter/code :in/interest-bank-tds-threshold-general
 :parameter/values
 [{:parameter-value/effective-from #inst "2019-09-01"
   :parameter-value/amount 40000M
   :parameter-value/currency :INR}]}
{:parameter/code :in/interest-bank-tds-threshold-senior
 :parameter/values
 [{:parameter-value/effective-from #inst "2018-04-01"
   :parameter-value/amount 50000M
   :parameter-value/currency :INR}]}

;; §194K MF IDCW threshold
{:parameter/code :in/mf-idcw-tds-threshold
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-04-01"
   :parameter-value/amount 5000M
   :parameter-value/currency :INR}
  {:parameter-value/effective-from #inst "2025-04-01"
   :parameter-value/amount 10000M
   :parameter-value/currency :INR}]}

;; §115A NRI dividend rate
{:parameter/code :in/nri-§115a-dividend-rate
 :parameter/values
 [{:parameter-value/effective-from #inst "2020-04-01"
   :parameter-value/rate 0.20M}]}

;; §80TTA savings interest deduction cap
{:parameter/code :in/§80tta-cap
 :parameter/values
 [{:parameter-value/effective-from #inst "2013-04-01"
   :parameter-value/amount 10000M
   :parameter-value/currency :INR}]}

;; §80TTB senior citizen interest deduction cap
{:parameter/code :in/§80ttb-cap
 :parameter/values
 [{:parameter-value/effective-from #inst "2018-04-01"
   :parameter-value/amount 50000M
   :parameter-value/currency :INR}]}

;; FA 2024 dividend surcharge cap
{:parameter/code :in/dividend-surcharge-cap
 :parameter/values
 [{:parameter-value/effective-from #inst "2024-07-23"
   :parameter-value/rate 0.15M}]}
```

The DTAA-reduced rates stay as consumer-side `:inputs`
(jurisdiction-by-jurisdiction; ADR-101 doesn't carry treaty rates
in v1).

§80M / §115A / §80TTA / §80TTB conditions stay record-shaped in
the provider (not migrated to `:provision`-shape in Phase C2).

The IT Act 2025 section renumbering rides `:concept-iri` (ADR-090):
each parameter additionally carries `:parameter/concept-iri
"https://taxonomy.cbdt.gov.in/section-NNN"` where NNN is the
canonical IT-Act-1961 number.

---

## §7. Sources

### IN statutory primary

- **Income-tax Act, 1961**:
  - **§115A** — NRI tax rates (20 % dividend, 5 % infrastructure-debt
    interest, 10/20 % royalty/FTS).
  - **§115BAC** — concessional new tax regime (default FY 2023-24).
  - **§115BBDA** — REPEALED by FA 2020 (don't model).
  - **§194 / §393(1)** — dividend TDS 10 %.
  - **§194A / §391** — interest TDS 10 %.
  - **§194K / §392** — MF IDCW TDS 10 %.
  - **§194-IA** — real-estate TDS (IN CGT, note 131).
  - **§194LD**, **§194LBA** — qualified-debt-fund TDS for NRIs.
  - **§195** — TDS on payments to non-residents (catch-all).
  - **§206AA** — PAN-not-furnished penalty rate (20 %).
  - **§80M** — inter-corporate dividend deduction.
  - **§80TTA**, **§80TTB** — savings/senior interest deductions.
- **Finance Act 2020** — abolished DDT; introduced §194 dividend
  TDS; introduced §194K MF IDCW TDS.
- **Finance Act 2024** — §111A 15 % → 20 % STCG; raised §112A floor
  to ₹ 1.25 L; **dividend surcharge cap at 15 %**.
- **Finance Act 2025** — TDS threshold raises (§194 ₹ 5k → ₹ 10k;
  §194K ₹ 5k → ₹ 10k).
- **Income-tax Act, 2025** (effective AY 2026-27) — renumbering:
  §194 → §393(1), §194A → §391, §194K → §392, §115A → §163, §80M →
  §134 (illustrative; verify against final IT Act 2025 text).

### CBDT regulatory / tutorial

- [Income Tax India — Taxation of Dividend and Interest](https://www.incometaxindia.gov.in/taxation-of-dividend-and-interest)
  — canonical resident treatment.
- [Income Tax India — Tax Treatment of Dividend Received FA 2025 (PDF)](https://incometaxindia.gov.in/tutorials/tax%20treatment%20of%20dividend%20received.pdf)
  — official PDF, post-FA-2025 state.
- [Income Tax India — Section 80M (new)](https://www.incometaxindia.gov.in/w/section-80m-new-)
  — §80M tutorial.
- [Income Tax Department — Salaried Individuals AY 2026-27](https://www.incometax.gov.in/iec/foportal/help/individual/return-applicable-1)
  — current ITR-2/3 guidance.
- [Finance Bill 2026 (Memorandum, indiabudget.gov.in)](https://www.indiabudget.gov.in/doc/memo.pdf)
  — Budget 2026 memorandum (no investment-income changes confirmed).
- [Finance (No. 2) Bill 2024 (indiabudget.gov.in)](https://www.indiabudget.gov.in/budget2024-25/doc/Finance_Bill.pdf)
  — FA 2024 source for the surcharge cap + STCG/LTCG rate cliff.

### Reference / commentary

- [CleartTax — Tax on Dividend Income](https://cleartax.in/s/how-dividends-taxable).
- [CleartTax — Section 194 FY 2025-26](https://cleartax.in/s/section-194-income-tax-act).
- [CleartTax — Section 80M Inter-corporate dividends](https://cleartax.in/s/section-80m).
- [CleartTax — Section 80TTA](https://cleartax.in/s/section-80tta).
- [CleartTax — Section 80TTB Senior Citizens](https://cleartax.in/s/section-80ttb-senior-citizens-deduction).
- [CleartTax — Section 194K Mutual Fund](https://cleartax.in/s/section-194k).
- [Bajaj Finserv — Section 194 TDS on Dividends](https://www.bajajfinserv.in/investments/section-194-income-tax-act).
- [Bajaj Finserv — Section 194A TDS on interest](https://www.bajajfinserv.in/investments/section-194a-income-tax-act).
- [Bajaj Finserv — Section 194K TDS on MF](https://www.bajajfinserv.in/investments/section-194k-of-income-tax-act).
- [Bajaj Finserv — Tax on Dividend Income FY 2026-27](https://www.bajajfinserv.in/investment/tax-on-dividend-income)
  — NRI §115A treatment.
- [Motilal Oswal — Section 194 TDS on Dividends](https://www.motilaloswal.com/personal-finance/tax/section-194-of-income-tax-act-tds-on-payment-of-dividend).
- [Angel One — TDS on Dividend (§194)](https://www.angelone.in/knowledge-center/income-tax/tds-on-dividend).
- [Angel One — Section 80M Eligibility](https://www.angelone.in/knowledge-center/income-tax/section-80m-of-income-tax-act).
- [Quicko — Section 194K TDS on Dividend from MF](https://learn.quicko.com/section-194k-tds-dividend-mutual-funds).
- [Quicko — Section 194 TDS on dividend equity shares](https://learn.quicko.com/section-194-tds-dividend-equity-shares).
- [Tax2Win — Section 194K Mutual Fund](https://tax2win.in/guide/tds-section-194k-tds-on-mutual-fund).
- [BankBazaar — Section 194 Complete Info](https://www.bankbazaar.com/tax/section-194-income-tax-act.html).
- [tdsman — Section 393(1) IT Act 2025](https://blog.tdsman.com/2026/05/tds-on-dividend-section-3931-section-194/)
  — IT Act 2025 renumbering + effective date.
- [TransactIG — Section 194 Dividend TDS Reconciliation](https://www.terra-insight.com/insights/tds-section-194-dividend-reconciliation-india/).
- [Finnovate — Mutual Fund Taxation FY 2025-26](https://www.finnovate.in/learn/blog/mutual-fund-taxation-india-fy-2025-26).
- [Finnovate — How Is Dividend Income Taxed in India](https://www.finnovate.in/learn/blog/tax-on-dividend-income-india).
- [Fundscart — Mutual Fund Taxation](https://fundscart.com/taxation-on-mutual-funds/).
- [DisyTax — Section 194K](https://disytax.com/section-194k-income-mutual-fund-units/).
- [Busy — Section 194K TDS on MF](https://busy.in/tds/section-194k-tds-on-mutual-fund-distributions-demystified/).
- [BFML — Section 194K Bajaj Finserv Markets](https://www.bajajfinservmarkets.in/income-tax/section-194k-of-income-ax-act).
- [Upstox — MF tax rules 2026](https://upstox.com/news/personal-finance/mutual-funds/mutual-fund-tax-rules-you-can-t-ignore-in-2026-how-your-equity-debt-and-hybrid-gains-are-taxed/article-193461/).
- [AMFI — Tax Regime for Mutual Funds](https://www.amfiindia.com/investor/knowledge-center-info?zoneName=TaxRegimeForMutualFunds).
- [Nippon India MF — Tax Reckoner FY 2025-26 (PDF)](https://mf.nipponindiaim.com/LearnAndInvest/TaxRateDocuments/Tax-Reckoner-for-FY-2025-26.pdf)
  — practitioner-grade reckoner including NRI §115A column.
- [Referencer — Income Tax Rates AY 2025-26](https://www.referencer.in/Income_Tax/Income_Tax_Rates_AY_2025-26.aspx)
  — surcharge cap confirmation.
- [PwC India — Withholding taxes](https://taxsummaries.pwc.com/india/corporate/withholding-taxes)
  — corporate WHT including dividends.
- [PwC India — Income determination](https://taxsummaries.pwc.com/india/corporate/income-determination)
  — corporate dividend treatment.
- [Taxbuddy — Section 194K Insights](https://www.taxbuddy.com/blog/section-194k-of-income-tax-act).
- [Jainam — Section 194K of Income Tax Act](https://www.jainam.in/glossary/section-194k-of-income-tax-act/).
- [IndiaFilings — Tax on Dividend Income](https://www.indiafilings.com/learn/tax-on-dividend-income-taxation-of-dividend-income).

### kontor substrate cited

- `src/kontor/book.clj:296-330` — `declare-dividend!` +
  `distribute-dividend!`; the dividend-in event on the recipient's
  books rides this.
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider`; the
  five components ride this.
- `src/kontor/tax_schedule.clj` — `:flat` (NRI §115A); the resident
  lanes use `:delegated-to-pit` (a sentinel; no schedule).
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer;
  surcharge cap rides as a `:cap` on the surtax adjustment item
  (note 105).
- `src/kontor/statute.clj` — `apply-provisions` (ADR-101).
- `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj:138-187`
  — the existing IN PIT provider with surcharge bands + cess +
  §87A; the resident dividend / interest / IDCW components
  base-transform-add into this.
- `modules/l10n-in/src/kontor/l10n_in/cgt_provider.clj` — the IN CGT
  provider; sibling; shares §195 TDS via `:inputs :in-tds-withheld`.
- `doc/research/131-in-cgt-fit.md` §5.2 — provider-coupling pattern
  (CGT slab-rate STCG fold to PIT); this provider reuses the same
  shape extensively.
- `doc/research/155-br-investment-income-fit.md` — sibling BR note;
  same posture on per-payer aggregation + per-section TDS inputs.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — the
  disposal substrate (relevant for MF redemption gains routed
  through IN CGT, not this provider).

---

End of note 156.

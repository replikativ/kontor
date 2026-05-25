---
date: 2026-05-24
title: 153 — AU investment-income — substrate fit for the imputation system + interest WHT, on top of the shipped CGT provider
status: research-before for note-104 Phase C2 — AU investment-income companion of the dividend / interest verbs (book.clj) + the existing `au-cgt-provider` (note 129 / ADR-102)
audience: maintainer + the AU-investment-income implementation agent
---

# 153 — AU investment-income substrate fit

Phase C2 of note 104 adds **investment-income** providers — the
companion of the just-shipped `kontor.incorporation` + dividend
verbs (`book.clj/declare-dividend!` + `distribute-dividend!`) and the
11 CGT providers from Phase B. The kernel's `distribute-dividend!`
docstring already names the in-scope adapters:

> The shareholder records the receipt separately on their books via
> `receive!` (Dr Bank, Cr Income:Dividends) — the investment-income
> regime in `kontor-l10n-<cc>` then taxes it (DE Abgeltungsteuer, US
> qualified-dividend, FR PFU, JP 20.315 %, …).

This note assesses **Australia**. AU is the most structurally
distinctive substrate the project has seen on the investment-income
side: alone among comparable jurisdictions, the dividend received by
the shareholder is **grossed up by an imputation credit** that
reflects the corporate tax already paid, and that credit then
**reduces** the shareholder's own tax liability — to the extent the
credit exceeds the liability, it is **refunded in cash** to resident
individuals + super funds (s207-145). Two consequences for the
substrate:

1. **The taxable base is the *gross-up*, not the cash dividend**. The
   shareholder posts (Dr Bank, Cr Income:Dividends) for the *cash*
   amount; the provider must compute the franking credit
   (`cash × rate/(1−rate)`), gross up the assessable income, and emit
   a refundable credit. The PIT / CIT provider then sweeps the
   grossed-up base AND consumes the credit.
2. **Holder-class dispatch is multidimensional**. Refundability (yes
   for individuals + super funds; no for non-fixed-trusts under
   s207-150), holding-period exposure (45-day rule with $5,000
   small-shareholder exemption), and accumulation-vs-pension split
   for SMSFs all gate the credit. The CGT provider's existing
   `{:individual :trust :super-fund :company}` dispatch (note 129
   §5) is the right starting point and gets ONE additional axis
   (`:super-fund-phase :accumulation | :pension`).

Plus the **foreign-source dividend** branch — gross income + FITO
(s.770-10), with the foreign withholding tax acting as a
non-refundable offset, capped at the AU tax that would have been
payable on the same net foreign income. And the **interest income**
branch — ordinary income at the holder's marginal rate, with TFN
withholding (47%) as the no-TFN-provided penalty mechanism (NOT a
final tax; it's a refundable prepayment).

Bottom line: the AU investment-income provider is **one provider
returning a multi-component `TaxReturnFacts`** layered on top of the
existing `au-cgt-provider` architecture (note 129 §5). It needs **0
kernel additions**, **2 companion-namespace attrs** on a new income
substrate (`:investment-income-event/*` — see §3 for the design
choice), and **1 `:inputs` shape extension** for the per-stakeholder
running franking-credit-claim ledger (mirrors the AU CGT $500 k
retirement-cap shape from note 129 §4 Gap 6). Zero schedule-algebra
changes — the gross-up + refundable-credit mechanic is expressible
as `:base-add` + `:credit refundable?:true` on ADR-101 statute data;
the algebra already supports both.

---

## §1. AU investment-income regime — the moving parts

### 1.1 The imputation system (Part IIIAA ITAA 1936 → former Part 3-6 ITAA 1997)

The imputation system was introduced 1987 (Hawke-Keating) to end
double taxation of corporate profits. Conceptually:

1. The corporation pays corporate tax on profit at its corporate
   rate (30 % large / 25 % base rate entity).
2. When that after-tax profit is distributed as a dividend, the
   corporation "attaches" a **franking credit** representing the
   tax already paid.
3. The shareholder receives `cash + credit` as assessable income
   (the gross-up).
4. The shareholder's own tax liability on the grossed-up amount is
   computed at their own rate, then **reduced** by the franking
   credit (s207-20).
5. If the credit > the liability, the excess is **refunded in cash**
   (s207-45) for resident individuals + complying super funds; the
   excess is **lost** for non-fixed trusts, foreign residents, and
   companies (companies retain the credit in their *own* franking
   account for downstream distribution).

The statutory scaffolding moved between Acts over the decades. The
1936 Act ITAA 1936 Part IIIAA was the original home; the **45-day
holding period rule** lived in former Pt IIIAA Div 1A (s160APHO);
Pt IIIAA was repealed effective 1 July 2002 by the rewrite. The
substantive holding-period rule was preserved in
**former Pt IIIAA Div 1A as savings provisions** (technically *not*
in the ITAA 1997 itself — they were intentionally left in the 1936
Act when the rewrite happened, an unusual structural choice that
makes citation awkward). The franking mechanics live in
**Part 3-6 ITAA 1997 (Divisions 200–220)**, with the gross-up rule
in s207-20 and refundability in s207-45.

For the substrate this means: the **citation discipline** for the
holder-class branches should reference both Acts — the gross-up at
ITAA 1997 s207-20; the holding-period rule at ITAA 1936 former
Pt IIIAA Div 1A (preserved as savings). The provider's docstring
should call out the historical split so future audit cites are
unambiguous.

### 1.2 The franking-credit formula

The official ATO formula (allocating-franking-credits.html):

```
Franking Credit  =  Cash Dividend × (Corporate Rate ÷ (1 − Corporate Rate))

Grossed-Up Dividend (Assessable Income)  =  Cash Dividend + Franking Credit
```

Where "Corporate Rate" is the rate at which the *distributing
corporation* is franking — NOT necessarily the rate at which it pays
tax. The benchmark franking percentage (s203-25) is set by the first
distribution in a franking period and must be applied to all
subsequent distributions in the period; over-franking attracts
**over-franking tax** (s203-50, equal to the excess franking
credits attached above the benchmark).

The two prevalent rates in practice:

- **30 %** — large companies (turnover ≥ $50M aggregated, OR > 80 %
  passive-income proportion).
- **25 %** — base rate entities (BRE; turnover < $50M aggregated AND
  ≤ 80 % passive income).

So for a fully-franked $700 cash dividend from a 30 %-rate company:
franking credit = $700 × (0.30 / 0.70) = **$300**; gross-up = $700 +
$300 = **$1,000 assessable income** to the shareholder. For the
same $700 from a 25 %-rate BRE: franking credit = $700 × (0.25 /
0.75) = **$233.33**; gross-up = **$933.33 assessable income**.

**Partial franking** is common — a 50 %-franked $700 dividend from a
30 %-rate company has $350 of cash franked + $350 unfranked; franking
credit = $350 × (0.30 / 0.70) = **$150**; assessable income = $700 +
$150 = **$850**.

### 1.3 The 45-day holding period rule (former Pt IIIAA Div 1A, s160APHO ITAA 1936)

The qualified-person rules require the shareholder to hold the
shares **at risk for at least 45 days** (90 days for preference
shares) during a primary qualification period straddling the
ex-dividend date. "At risk" means economically — net long position
≥ 30 % at all times during the qualification window; hedges,
short positions, and derivative offsets reduce the at-risk position
below the threshold and disqualify the credit.

The primary qualification period:

- **Start**: the day AFTER the shares are acquired.
- **End**: the 45th day (or 90th for preference shares) AFTER the
  ex-dividend day.

If the shareholder fails the holding-period test, the franking
credit is **denied** — the cash dividend is still assessable, but
no gross-up and no refund.

**Small shareholder exemption** (s160APHT ITAA 1936): the rule does
NOT apply if the shareholder's total franking-credit entitlement for
the income year is **≤ $5,000** (≈ $11,667 of fully franked
dividends at 30 % rate). Available to **individual taxpayers only**
— not to SMSFs, not to trusts other than family trusts that have
elected.

For the substrate this is a per-holder, per-year computation: sum
the year's franking credits; if ≤ $5,000 AND holder is an
individual, skip the holding-period test on every disposal. The
substrate has the data (`:investment-income-event` records per
distribution; `:holder-kind :individual`); the provider does the
sum + gate.

### 1.4 Holder-class refundability matrix (s207-45 / s207-150 / Pt 3-6 Div 207)

The franking credit's behaviour after offset depends on holder class:

| Holder class | Gross-up applies? | Credit usable? | Refundable if excess? |
|---|---|---|---|
| **Resident individual** | Yes | Yes | **Yes** (cash refund per s207-45) |
| **Resident company** | Yes | Yes (against own tax liability) | **No** (excess credited to receiver's franking account for later attachment to own dividends) |
| **Complying super fund (incl. SMSF) accumulation phase** | Yes | Yes (against 15 % fund tax) | **Yes** (cash refund) |
| **Complying super fund pension phase** | Yes (assessable income still 0 % rate) | Effectively N/A | **Yes** — the entire credit is refunded since 0 % tax applies |
| **Resident family trust (FTE elected)** | Yes | Distributed to beneficiaries who use it | Yes (via the beneficiary path) |
| **Resident non-fixed trust (no FTE)** | Yes | Often DENIED (s207-150 — the credit doesn't pass through unless FTE filed) | No |
| **Resident partnership** | Pass-through to partners; same treatment as partner-class | per partner | per partner |
| **Foreign resident** | Yes (the dividend itself is exempt from DWT under s128B(3) to the extent franked) | NO — franking credit is NOT usable | No |

The 2019 Labor proposal to abolish refundability (the
"retiree-tax-grab" controversy) was rejected at the May 2019
federal election; refundability remains in force as of May 2026.
No reform on the horizon — confirmed in the 2026-27 federal
budget which kept the imputation system intact and limited CGT
changes to the 50 % discount sunset (note 129 §1.4).

### 1.5 Foreign-source dividends + the FITO (s.770-10 ITAA 1997)

A resident receiving a foreign-source dividend:

- The dividend (gross of any foreign withholding tax) is
  assessable in AU (s44(1) ITAA 1936).
- The foreign withholding tax paid is converted to a **Foreign
  Income Tax Offset (FITO)** under s.770-10 ITAA 1997.
- The FITO is **non-refundable** (unlike the franking credit) —
  excess offset is **lost** (cannot carry forward beyond the year).
- The FITO is **capped** at the lesser of:
  - the actual foreign tax paid (converted to AUD at the
    distribution date FX rate, per s.960-50);
  - the AU tax that would have been payable on the **same net
    foreign income** (the "AU-tax-on-foreign-income" cap; complex
    apportionment for high-income holders).
- For amounts ≤ **AUD $1,000 of foreign tax**, the small-claims
  shortcut applies — no apportionment calculation; full amount
  claimable (s.770-75).

Special wrinkles the substrate must handle:

- **Treaty-reduced rate** (s.4 ITAA 1953 + International Tax
  Agreements): foreign WHT exceeding the treaty rate is NOT a
  creditable foreign income tax — only the treaty rate; the excess
  must be reclaimed from the foreign tax authority.
- **Anti-hybrid rules** (Div 832) — foreign tax on a hybrid
  instrument may not be creditable.
- **Conduit-foreign-income passthrough** (s.802) — when an AU
  corporate intermediates a foreign dividend with CFI status, the
  intermediate-level credit is preserved; the substrate must
  recognise the CFI flag on the inbound distribution.

For the substrate this means: the `:investment-income-event` record
needs to carry the foreign-source flag + a `:foreign-tax-withheld`
companion amount + the source jurisdiction. The provider then emits
a `:credit` line with `:refundable? false` capped at the FITO
ceiling.

### 1.6 Conduit Foreign Income (Subdivision 802-A, ITAA 1997)

An AU company that receives a foreign-source dividend may **on-pay
it** to a foreign-resident shareholder as **conduit foreign income**
(CFI) — the on-payment is:

- NOT assessable to the foreign resident (s.802-15);
- NOT subject to AU dividend withholding tax (s.128B(3)(ga)).

The CFI declaration is **per-distribution** — the company declares
on the dividend statement which portion of the unfranked dividend
is CFI. The CFI bucket lives in the company's franking-account-
equivalent CFI ledger (s.802-50); over-distribution exhausts the
bucket and the excess is treated as ordinary unfranked dividend.

This is exclusively a **corporate-issuer** concern. The
shareholder-side investment-income provider does NOT see CFI —
foreign-resident shareholders aren't subject to AU income tax on
the CFI portion at all. The substrate handles it on the issuer
side via the existing `distribute-dividend!` verb augmented with a
CFI declaration field (out of scope for this note; covered when the
verb is enhanced).

### 1.7 Interest income (ordinary; TFN withholding)

Unlike DE / AT / JP / FR where bank-deposit interest has a
final-withholding regime, **AU does not** — interest is **ordinary
income** taxed at the holder's marginal rate (PIT brackets for
individuals; CIT rate for companies; 15 % for accumulation-phase
super; 0 % for pension-phase super).

The **TFN withholding** mechanism (Pt VA ITAA 1936) is the *no-TFN
penalty*: if the depositor has not provided their Tax File Number to
the financial institution, the institution withholds at **47 %**
(the top marginal rate, 2026-27) — but this is a **prepayment**,
NOT a final tax. The depositor reclaims it via their annual return
by lodging the income + the prepayment.

For **non-residents**, **interest WHT** at **10 % of gross interest**
applies (s.128B; capped by treaty at 10 % typically). This is a
**final tax** for the non-resident — they do not lodge a return on
the interest. AU-resident issuers withhold and remit.

For the substrate this means: a resident shareholder receiving
interest posts (Dr Bank, Cr Income:Interest); the provider's
contract is to flow the interest into the PIT / CIT taxable base.
The TFN withholding is recognised as a `:prepaid` line item; the
provider does NOT compute it (it's already on the books from the
bank's deposit, less the withhold). The PIT / CIT provider sweeps
the gross interest + the prepayment.

### 1.8 The corporate-shareholder branch (intercorporate dividend rebate ≈ "DRA")

Australia does NOT have a separate intercorporate dividend rebate
("DRA" in US terminology). The dividend received by a resident
company is fully assessable; the franking credit attached can be
used against the recipient's own tax liability AND the excess
credits are added to the recipient's own franking account
(s205-15) — preserving the imputation across the corporate chain.

This is materially different from US §243 DRD (50 % / 65 % / 100 %
deduction) and DE §8b KStG (95 % exemption) — AU runs full
gross-up + offset at every corporate layer, with no exemption.

For the substrate: when `:holder-kind :company`, the provider:
- includes the full grossed-up dividend in CIT taxable base;
- offsets the franking credit against the company's CIT liability;
- emits a `:franking-account-credit` line that increments the
  recipient's own franking account balance (for downstream
  attachment when the recipient itself distributes).

The franking-account ledger is **issuer-side state** (not on the
disposal/distribution event itself). The substrate's home for this
is the `kontor.incorporation` namespace's still-emerging
issuer-side accounting — out of scope for the AU
investment-income provider, but the provider must signal the
intent (via a `:jurisdiction-specific-codes
{:au-franking-account-credit amount}` adjustment-input) so a
future ledger-side workflow can consume it.

### 1.9 Trusts (Pt 3-6 Div 207 Subdiv 207-B; s207-50)

The franking-credit flow through trusts is the most fraught corner
of the regime. The default rule:

- A discretionary (non-fixed) trust **cannot pass franking credits**
  unless it has made a **Family Trust Election** (FTE; Sch 2F ITAA
  1936). Without an FTE, the credit is "trapped" at the trust level
  and **lost** (s207-150).
- A fixed trust (unit trust where beneficiaries' entitlements are
  vested and indefeasible) passes credits to beneficiaries
  proportional to their fixed entitlement.
- An FTE-elected family trust passes credits to "family group"
  beneficiaries; non-family-group distributions trigger Family
  Trust Distribution Tax (FTDT, 47 %) — not the substrate's
  concern.

For the substrate: the AU investment-income provider's
`:holder-kind :trust` branch reads a `:tax-unit :trust-kind
:fixed | :discretionary-fte | :discretionary-no-fte` flag; trapped
credits emit a `:credit-lost` line item for audit; passthrough
trusts emit a `:credit-passed-through` adjustment with the
beneficiary-distribution shape.

V1 recommendation: **ship the `:discretionary-no-fte` (credit-lost)
branch + the `:fixed` branch (full passthrough)**; defer
`:discretionary-fte` (family-group apportionment is consumer-side
logic). The substrate carries the flag; the provider routes.

### 1.10 Listed Investment Companies (LIC capital gain credit, s115-285)

A separate small mechanic: LICs (listed investment companies) can
attach a **LIC capital gain credit** to a portion of their dividend
representing realised long-term capital gains (held > 12 months).
The shareholder grosses up by the credit AND gets a **50 % deduction
for the LIC portion** for individuals / trusts (the LIC effectively
passes through the discount).

Substrate fit: ride a `:investment-income-event :lic-cg-credit
amount` companion attr; provider applies the 50 % deduction. V1
deferable to consumer demand; flag in the gap list.

### 1.11 Summary table

| Income type | Holder | Substrate posting | Provider action |
|---|---|---|---|
| Fully franked dividend, 30 % rate | Resident individual | Dr Bank $700 / Cr Income $700 | Gross up $1,000; emit $300 refundable credit; PIT sweeps |
| Fully franked dividend, 30 % rate | SMSF accumulation | same | Gross up $1,000; emit $300 refundable credit; 15 % fund tax → net refund |
| Fully franked dividend, 30 % rate | SMSF pension | same | Gross up $1,000; emit $300 refundable credit; 0 % rate → full $300 refunded |
| Fully franked dividend, 30 % rate | Resident company | same | Gross up $1,000; emit $300 credit against own CIT; add $300 to own franking account |
| Fully franked dividend, 30 % rate | Foreign resident | same | NO gross-up; no DWT (franked portion); cash dividend remains in books |
| Unfranked dividend | Resident individual | Dr Bank $700 / Cr Income $700 | No gross-up; full $700 in PIT base; no credit |
| Unfranked dividend | Foreign resident (non-CFI) | Dr Bank $595 / Cr Income $700 / Dr WHT $105 | DWT 30 % (or 15 % treaty); final tax |
| Foreign-source dividend USD | Resident individual | Dr Bank gross / Cr Income gross / Dr FTC withheld | Full gross in PIT base; FITO non-refundable credit; capped at AU tax on net foreign income |
| Interest, AU bank, TFN supplied | Resident individual | Dr Bank gross / Cr Income gross | Full gross in PIT base at marginal rate |
| Interest, AU bank, no TFN | Resident individual | Dr Bank net / Cr Income gross / Dr WHT 47 % | Full gross in PIT base; WHT 47 % as refundable prepayment |
| Interest, AU bank | Foreign resident | Dr Bank net / Cr Income gross / Dr WHT 10 % | DWT 10 % final tax |

---

## §2. Worked examples

### 2.1 Resident individual — fully franked dividend at 30 % rate

Marcus (AU-resident individual, 37 % marginal bracket, no other
investment income except this distribution) receives a fully
franked $700 cash dividend from BHP (a 30 %-rate large company) on
2026-03-15. He has held the shares > 45 days at risk.

- **Cash received**: $700 → posts (Dr Bank $700 / Cr Income:Dividends
  $700).
- **Franking credit**: $700 × (0.30 / 0.70) = **$300**.
- **Gross-up (assessable)**: $700 + $300 = **$1,000**.
- **PIT base addition**: $1,000 at 37 % marginal = **$370 gross
  liability**.
- **Franking credit offset**: $370 − $300 = **$70 net liability**.
- **Refundability**: $300 ≤ $370, so no refund; standard offset.
- **Effective tax on $700 cash**: $70 / $700 = **10 %** (the
  imputation system has equalised AU corporate + personal tax at
  the 30 % rate; Marcus's effective extra tax is `(0.37 − 0.30) /
  0.70 ≈ 10 %`).

Substrate trace: one `:investment-income-event` —

```clojure
{:investment-income-event/kind             :dividend
 :investment-income-event/cash-amount      700M
 :investment-income-event/franking-credit  300M
 :investment-income-event/franking-percent 100M     ;; fully franked
 :investment-income-event/corporate-rate   0.30M
 :investment-income-event/source           :au-resident-corp
 :investment-income-event/event-date       #inst "2026-03-15"
 :investment-income-event/holding-days     412
 :investment-income-event/holder-kind      :individual
 :investment-income-event/realizing-tx     #db/id<…>}
```

The `au-investment-income-provider` (composing on top of
`au-cgt-provider`) reads this in the period, applies the 45-day
test (passed), grosses up to $1,000, emits `:pit-base-additions
{:dividend-gross-up 300M}` (the credit-side, since the $700 is
already on the books) + `:pit-credits {:franking-credit 300M
:refundable? true}`. PIT provider sweeps both — the gross-up
into the bracket calc, the credit against the gross liability,
the refundability into the cash-refund branch.

### 2.2 SMSF pension phase — fully franked dividend

The Murphy Super Fund (SMSF, 100 % pension phase, 0 % tax rate)
receives the same $700 fully franked dividend.

- **Cash received**: $700 → posts (Dr Bank / Cr Income:Dividends).
- **Franking credit**: $300.
- **Gross-up**: $1,000 assessable income (notionally; the 0 % rate
  applies).
- **Gross liability**: $1,000 × 0 % = **$0**.
- **Franking credit offset**: $0 − $300 = **−$300 (refund)**.
- **Refundability**: SMSF complying + pension → **$300 cash refund
  from the ATO**.

This is the canonical "refundable franking credit" outcome that the
2019 Labor reform proposed to abolish (and that was rejected at the
election). For SMSFs in pension phase it is a **direct cash
subsidy** equal to the corporate tax already paid.

Substrate trace: same as 2.1 but with `:holder-kind :super-fund` +
`:tax-unit :super-fund-phase :pension`. Provider emits the same
gross-up + credit but the PIT (here, the SIS-equivalent
super-fund-tax provider) sweeps at 0 % → full refund.

### 2.3 Resident individual — small shareholder exemption + holding period

Sarah (AU-resident individual) does day-trading. She bought 1,000
CBA shares on 2026-02-10 for $130,000; ex-dividend date 2026-02-25;
she sells on 2026-02-28 (held 18 days, the at-risk period for the
dividend was less than 45 days). The cash dividend is $1,000, fully
franked at 30 %.

- **Cash received**: $1,000.
- **Franking credit**: $1,000 × (0.30 / 0.70) = **$428.57**.
- **Holding-period test**: 18 days < 45 days → **FAIL**.
- **Small-shareholder exemption check**: Sarah's total franking
  credit entitlement for the year, including this distribution, =
  $428.57 ≤ $5,000 → **PASSES exemption** → 45-day rule **DOES NOT
  APPLY**.
- **Gross-up**: $1,000 + $428.57 = **$1,428.57**.
- **Provider issues the credit**: usable / refundable per holder
  class.

Without the exemption, the credit would be **denied** and Sarah
would only have $1,000 cash dividend assessable, no credit.

Substrate trace: the provider sums the year's franking-credit
entitlements (across all `:investment-income-event` records for the
holder), gates on `≤ $5,000` AND `:holder-kind :individual`, and
either passes or fails the 45-day test accordingly. The substrate
needs the `:holding-days` field per event AND the cross-event sum;
the provider does the sum.

### 2.4 Resident company — fully franked dividend in a corporate chain

InvestCo Pty Ltd (resident AU company, 25 % BRE rate) receives a
fully franked $1,000 cash dividend from OpCo Ltd (large company,
30 % rate).

- **Cash received**: $1,000.
- **Franking credit**: $1,000 × (0.30 / 0.70) = **$428.57** (the
  credit is at OpCo's franking rate, NOT InvestCo's).
- **Gross-up**: $1,428.57 assessable to InvestCo.
- **InvestCo CIT liability**: $1,428.57 × 25 % = **$357.14**.
- **Franking credit offset**: $357.14 − $428.57 = **−$71.43**.
- **Refundability for companies**: **NO** — the excess $71.43 is
  NOT refunded. Instead it is **added to InvestCo's own franking
  account**, available for attachment to InvestCo's own future
  distributions.

When InvestCo later distributes to its individual shareholder,
the residual credit follows. The imputation has *carried over*
without loss across the corporate chain — even when the
intermediate company has a lower tax rate.

Substrate trace: provider emits `:cit-base-additions
{:dividend-gross-up 428.57M}` + `:cit-credits {:franking-credit
428.57M :refundable? false}` + `:jurisdiction-specific-codes
{:au-franking-account-credit 71.43M}`. The future ledger-side
workflow consumes the third line to update InvestCo's own
franking account.

### 2.5 Resident individual — foreign-source dividend with FITO

Tom (AU-resident individual, 45 % top bracket) receives a
USD-denominated dividend from his US holding of Apple shares —
$1,000 USD cash, $150 USD withheld at source under the AU-US
treaty (15 % treaty rate). At the distribution date, USD-AUD =
1.50, so the AUD-equivalent is $1,500 cash received (after $225
foreign tax in AUD).

- **Foreign-source dividend (gross)**: AUD $1,500.
- **Foreign tax paid (AUD)**: $225.
- **PIT base addition**: $1,500 (the gross, NOT the net).
- **PIT gross liability**: $1,500 × 45 % = **$675**.
- **FITO claim**: capped at the lesser of $225 (foreign tax paid)
  or the AU tax on the net foreign income ($675 in this case
  for a high-bracket holder) → **$225** is fully creditable.
- **FITO is non-refundable** — excess would be lost, but no
  excess here.
- **Net AU tax**: $675 − $225 = **$450**.

Substrate trace:

```clojure
{:investment-income-event/kind            :dividend
 :investment-income-event/cash-amount     1500M    ;; AUD-equivalent
 :investment-income-event/franking-credit 0M       ;; foreign-source
 :investment-income-event/source          :foreign
 :investment-income-event/foreign-jurisdiction :us
 :investment-income-event/foreign-tax-withheld 225M
 :investment-income-event/event-date      #inst "2026-04-20"
 :investment-income-event/holder-kind     :individual}
```

Provider emits `:pit-base-additions {:dividend-foreign 1500M}` +
`:pit-credits {:fito 225M :refundable? false :cap-formula
:au-tax-on-net-foreign-income}`. PIT provider computes the
gross-on-bracket, deducts the FITO non-refundably, caps as needed.

### 2.6 Resident individual — bank-deposit interest, TFN supplied

Marcus (37 % bracket) receives $2,000 interest on his Commonwealth
Bank savings account, TFN on file.

- **Cash received**: $2,000.
- **TFN withholding**: NONE (TFN supplied).
- **PIT base addition**: $2,000 at 37 % = **$740 tax owed**.

No franking, no FITO, no special regime — interest is plain
ordinary income.

Substrate trace:

```clojure
{:investment-income-event/kind            :interest
 :investment-income-event/cash-amount     2000M
 :investment-income-event/tfn-prepaid     0M       ;; TFN supplied
 :investment-income-event/source          :au-resident-bank
 :investment-income-event/event-date      #inst "2026-06-30"
 :investment-income-event/holder-kind     :individual}
```

Provider emits `:pit-base-additions {:interest-ordinary 2000M}`.
PIT sweeps. Provider's contribution to `:components` is
informational only (no separate tax component to compute).

### 2.7 Resident individual — bank-deposit interest, no TFN

Same as 2.6 but Marcus forgot to lodge his TFN with the bank. The
bank withholds at 47 % → cash received = $1,060, withholding $940.

- **Gross interest income**: $2,000 (the assessable amount, not the
  net).
- **PIT base addition**: $2,000 at 37 % = **$740 tax owed**.
- **TFN withholding offset**: refundable prepayment, **$940 is
  credited against tax owed**.
- **Net cash position post-return**: Marcus is refunded $940 − $740
  = **$200 cash** (the bank over-withheld; the marginal rate is
  lower than the no-TFN penalty rate).

Substrate trace:

```clojure
{:investment-income-event/kind            :interest
 :investment-income-event/cash-amount     2000M    ;; gross
 :investment-income-event/tfn-prepaid     940M     ;; refundable
 :investment-income-event/source          :au-resident-bank
 :investment-income-event/holder-kind     :individual}
```

Provider emits `:pit-credits {:tfn-prepaid 940M :refundable? true}`.
PIT sweeps; cash refund branch fires if credit > liability.

---

## §3. `:investment-income-event` substrate fit — schema or no schema?

The kernel does not currently have a "distribution received"
substrate analogous to `:disposal`. Two design choices:

### Option A — schemaless: tag the GL transaction

The distribution is already on the books as a `:transaction` (the
shareholder posts `Dr Bank $700 / Cr Income:Dividends $700`). Add
ADR-090 `:concept-iri :au-investment-income-dividend` on the
income side; the provider marginalises the GL by concept-iri,
reads the per-distribution facts from a sidecar `:audit-doc` or
from per-event metadata on the `:transaction`.

**Pros**: zero new schema; ADR-090 already paid for the indexing;
matches the existing "the GL is the source of truth" doctrine.

**Cons**: the franking-credit + foreign-tax-withheld + holding-days
facts are NOT amounts on the GL — they are metadata about the
distribution. Smuggling them through `:audit-doc` or `:transaction
:metadata` makes them inaccessible to datalog queries (the kernel's
substrate seam principle, ADR-091, says facts that drive the tax
computation should be queryable).

### Option B — minimal schema: `:investment-income-event` companion

A small companion namespace on a new module (`kontor-investment-income`
or rolled into `kontor.l10n-au`) carrying ~8 attrs:

```
:investment-income-event/kind            :keyword  ; :dividend, :interest, :distribution
:investment-income-event/cash-amount     :bigdec   ; the cash received
:investment-income-event/source          :keyword  ; :au-resident-corp, :foreign, :au-resident-bank, :au-trust, :foreign-bank
:investment-income-event/event-date      :instant
:investment-income-event/holder-kind     :keyword  ; :individual, :super-fund, :company, :trust
:investment-income-event/realizing-tx    :ref      ; the GL transaction
:investment-income-event/audit-doc       :ref      ; sidecar audit pack
;; AU-specific (or move to :au-investment-income-event/* if mixed-jurisdiction)
:au-iie/franking-credit                  :bigdec
:au-iie/franking-percent                 :bigdec   ; 0..1
:au-iie/corporate-rate                   :bigdec   ; 0.30M / 0.25M
:au-iie/holding-days                     :long
:au-iie/foreign-jurisdiction             :keyword  ; ISO 3166-1
:au-iie/foreign-tax-withheld             :bigdec
:au-iie/tfn-prepaid                      :bigdec
:au-iie/cfi-declared?                    :boolean
:au-iie/lic-cg-credit                    :bigdec
```

**Pros**: queryable; clear audit trail; mirrors `:disposal` (the
"realisation event" substrate); future-proofs for other
jurisdictions that need similar (DE Abgeltungsteuer, JP
20.315 %, FR PFU, AT KESt — see note 154).

**Cons**: new schema; another bookkeeping concept the consumer must
populate.

### Recommendation — Option B, named `kontor-investment-income`

The pattern is the same as `kontor-disposal` (ADR-102): a
**realisation-event substrate** that hosts per-jurisdiction
investment-income providers analogously to how `:disposal` hosts
per-jurisdiction CGT providers. Note 154 (AT) will share the same
substrate; same goes for the eventual DE / JP / FR / US / CA / IN
investment-income providers. The cross-jurisdiction substrate is
worth ~9 kernel attrs + per-jurisdiction extensions.

**Suggested ADR**: a new companion ADR analogous to ADR-102 —
`kontor-investment-income` schema, ~9 kernel attrs, hosts
per-jurisdiction `*-iie/*` companion attrs.

Kernel attrs:

```
:investment-income-event/kind            #{:dividend :interest :distribution :rental :royalty :fund-distribution}
:investment-income-event/cash-amount     :bigdec
:investment-income-event/commodity       :keyword
:investment-income-event/source          :keyword  ; resident-corp, foreign, resident-bank, trust, etc.
:investment-income-event/source-partner  :ref      ; the distributing entity (optional)
:investment-income-event/event-date      :instant
:investment-income-event/holder-kind     :keyword  ; cross-jurisdiction enum
:investment-income-event/realizing-tx    :ref
:investment-income-event/audit-doc       :ref
```

Per-jurisdiction additive: `:au-iie/franking-credit`,
`:au-iie/holding-days`, etc. — mirrors the `:disposal/au-cgt-event`
companion pattern.

---

## §4. Concrete data gaps

Three additions, layered:

### Gap 1 — new kernel companion: `kontor-investment-income`

Per §3 Option B. ~9 kernel attrs in `:investment-income-event/*`
namespace, plus a separate ADR (analogous to ADR-102 for disposals).
This is the **biggest decision** in this note: if approved, it's the
substrate AU + AT (and eventually DE / JP / FR / US / CA / IN)
share. If rejected, the substrate falls back to Option A
(GL-tagged) and the providers become more brittle.

**Recommendation**: ship the kernel companion. The cross-jurisdiction
share is significant; the alternative pushes per-jurisdiction
bookkeeping into ad-hoc sidecars that don't compose.

### Gap 2 — `:au-iie/*` companion attrs (au-l10n-side)

Per §3. ~9 attrs. Drives the AU provider's compute. Standard
companion-namespace addition, no kernel concern.

### Gap 3 — `:inputs :au-franking-credit-ytd-claimed` shape

The 45-day rule's small-shareholder exemption ($5,000 annual
threshold) requires the provider to sum franking credits across
all `:investment-income-event` records for the year. The substrate
already has the data (all events queryable), so the provider can
compute this internally per call. BUT for incremental computation
across many periods within a year (e.g., monthly recognition for
some consumers), the **running total** can be passed as
`:inputs :au-franking-credit-ytd-claimed BigDecimal`; provider
threads it through.

This mirrors the AU CGT `:inputs :au-retirement-cap-used` shape
(note 129 §4 Gap 6).

### Gap 4 — `:tax-unit :super-fund-phase` (au-l10n companion)

The AU CGT provider already dispatches on `:holder-kind
:super-fund` (note 129 §5). Investment-income additionally needs
**`:accumulation` vs `:pension` phase** to compute the 15 % vs 0 %
tax rate. Add `:tax-unit :super-fund-phase
:accumulation | :pension` on the period-tax call's `:tax-unit`
map.

This mirrors the AT KESt provider's
`:tax-unit :held-entity-domestic?` shape (note 134 §6.1).

### Gap 5 — `:tax-unit :trust-kind` (au-l10n companion)

For trust holders, the franking-credit flow-through behaviour
depends on whether the trust is fixed, FTE-elected, or
non-FTE-discretionary (note 153 §1.9). Add `:tax-unit :trust-kind
:fixed | :discretionary-fte | :discretionary-no-fte`. v1 ships
`:fixed` (passthrough) + `:discretionary-no-fte` (credit lost);
`:discretionary-fte` deferred to consumer demand.

### Gap 6 — DEFER: LIC capital gain credit (`:au-iie/lic-cg-credit`)

Niche; only LIC investors hit it. Document the attr but raise
`:not-yet-implemented` in the provider. P2 follow-up.

### Gap 7 — DEFER: CFI / non-resident DWT issuer-side mechanics

Per §1.6, CFI is exclusively an issuer-side concern. Out of scope
for the shareholder-side investment-income provider; flag in the
ADR for the future `kontor.incorporation` ADR expansion.

**Totals**: 1 new kernel companion (~9 attrs, separate ADR); ~9
companion-namespace attrs (au-l10n-side); 2 `:tax-unit` shape
extensions; 1 `:inputs` shape extension; 0 schedule-algebra
changes.

---

## §5. `au-investment-income-provider` sketch

### Architecture — one provider, multi-component facts

Single `PeriodTaxProvider`, `:kind :investment-income-tax`,
`:authority :au-ato`. Composes with `au-cgt-provider` (note 129
§5) — both rely on the same `{:individual :trust :super-fund
:company}` holder dispatch.

```clojure
(defrecord AuInvestmentIncomeProvider [kind cgt-provider]
  PeriodTaxProvider
  (period-tax-facts [_ ctx period entity]
    (let [events       (events-in-period ctx period entity)
          dividends    (filter #(= :dividend  (:investment-income-event/kind %)) events)
          interest     (filter #(= :interest  (:investment-income-event/kind %)) events)
          holder       (get-in ctx [:tax-unit :holder-kind])
          fund-phase   (get-in ctx [:tax-unit :super-fund-phase])
          trust-kind   (get-in ctx [:tax-unit :trust-kind])
          ;; --- per-event compute
          ytd-fc       (or (get-in ctx [:inputs :au-franking-credit-ytd-claimed])
                           0M)
          franking-credits (compute-franking-credits dividends holder ytd-fc)
          fito-claims   (compute-fito-claims dividends)
          tfn-prepaids  (sum-tfn-prepaids interest)
          ;; --- gross-up + line-item assembly
          dividend-gross-up   (reduce-gross-up dividends franking-credits)
          interest-gross-add  (reduce + 0M (map :investment-income-event/cash-amount interest))
          base-additions      (+ dividend-gross-up interest-gross-add)
          credits             (concat franking-credits fito-claims tfn-prepaids)]
      {:kind                :investment-income-tax
       :authority           :au-ato
       :period              period
       :base                base-additions
       :line-items          (per-event-line-items events)
       :schedule            nil       ;; folds into PIT / CIT, no own schedule
       :composed-of         []
       :provisions          []
       :reads-inputs        #{:au-franking-credit-ytd-claimed}
       :emits-inputs        {:au-franking-credit-ytd-claimed
                             (+ ytd-fc (sum franking-credits))}
       :jurisdiction-specific-codes
       {(if (= holder :company) :cit-base-additions :pit-base-additions)
        {:dividend-gross-up  dividend-gross-up
         :interest-ordinary  interest-gross-add}
        (if (= holder :company) :cit-credits :pit-credits)
        credits
        ;; franking-account-credit for corporate chain
        (when (= holder :company)
          :au-franking-account-credit-pending
          (excess-fc-over-cit-liability ...))
        }})))
```

**Key design calls**:

1. **No `:schedule` set** — same posture as `au-cgt-provider` (note
   129 §5 Key 1). Investment-income folds into the holder's PIT or
   CIT as ordinary income (with the gross-up + credit) at the
   downstream provider's marginal-rate or flat-rate calc.

2. **Refundable vs non-refundable credit emission** — franking
   credits are **refundable** for individual + super-fund-pension;
   FITO is **non-refundable**; TFN-prepaid is **refundable**. The
   provider emits `{:credit-name amount :refundable? bool}` shape
   per line; the PIT / CIT provider's `:credits` reducer applies
   the refundability rule (negative net liability → cash refund if
   refundable, lost otherwise).

3. **Holder-class dispatch is multi-axis** — the
   `(:individual :trust :super-fund :company)` axis from the CGT
   provider PLUS `:super-fund-phase` (acc vs pension) PLUS
   `:trust-kind` (fixed vs FTE vs no-FTE). Three axes; the provider
   reads them from `(:tax-unit ctx)`.

4. **Small-shareholder exemption** is provider-internal: read
   `ytd-fc` from `:inputs`; sum the period's credits; if total ≤
   $5,000 AND `:holder-kind :individual`, skip the 45-day test on
   all events.

5. **Foreign-source dividends** go through the FITO branch; the
   `:au-iie/foreign-jurisdiction` flag drives the dispatch.
   Domestic dividends (`:au-iie/franking-percent > 0`) go through
   the franking-credit branch. The two branches are mutually
   exclusive on a per-event basis.

6. **Trust passthrough** (Gap 5) — when `:holder-kind :trust` AND
   `:trust-kind :fixed | :discretionary-fte`, the provider does NOT
   compute holder-level tax; it emits a `:beneficiary-passthrough`
   line carrying the credit + gross-up for downstream
   beneficiary-side providers to sweep. v1 raises
   `:not-yet-implemented` on `:discretionary-fte`; ships
   `:discretionary-no-fte` as the credit-lost branch.

7. **Bitemporal-safe** — the corporate-rate-at-distribution-time
   parameter rides on the `:au-iie/corporate-rate` field (carried
   at the time of distribution), so a bitemporal query
   (`as-of-valid :before 2022-07-01`) still sees the correct
   distributing-period rate even if BHP's franking rate changes
   later.

### Substrate stress this provider surfaces

- **The `:credit refundable?` slot** on `TaxReturnFacts` — does the
  current substrate have this? Need to verify the credit-shape in
  `kontor.tax-schedule`. If not, add as an addendum to ADR-101.

  **From CLAUDE.md ADR-101 Addendum 1 read**: the `:op :credit`
  vocab is present; whether refundability is a slot or has to ride
  in jurisdiction-specific-codes needs the implementer to check
  `src/kontor/tax_schedule.clj` and confirm.

- **The `:base-add` mechanic** — adding to the holder's PIT / CIT
  base from a sibling provider — is the standard
  `jurisdiction-specific-codes :pit-base-additions` /
  `:cit-base-additions` shape (note 113 §5.2). Same pattern.

- **The companion-event substrate** (Gap 1) — first cross-
  jurisdiction realisation-event substrate after `:disposal`. The
  ADR for `kontor-investment-income` is analogous to ADR-102; this
  is the substrate's natural pattern repeating.

---

## §6. Coordination with the existing `au-cgt-provider` (note 129)

The shipped `au-cgt-provider` already has the holder-class dispatch
this provider needs. Three concrete coordinations:

### 6.1 Holder-class dispatch — share the enum

The CGT provider uses `#{:individual :trust :super-fund :company}`
(`cgt_provider.clj` line 80). The investment-income provider
should reuse this enum (or import the namespaced constant). The
**addition** of `:super-fund-phase` (Gap 4) and `:trust-kind`
(Gap 5) is investment-income-specific — these don't change the
CGT computation (the CGT 1/3 discount applies regardless of fund
phase). They live on the AU companion as
`:l10n-au.tax-unit/super-fund-phase` and `:l10n-au.tax-unit/
trust-kind` per the namespace discipline.

### 6.2 Refundable-credit composition

Both providers may emit refundable credits in the same period
(franking credit + capital loss offset + R&D credit + LITO).
The downstream PIT provider sweeps them in a defined order:

1. Non-refundable credits first (FITO, education tax offset,
   LITO);
2. Refundable credits second (franking credit, TFN prepaid).

The kontor PIT provider already handles this via the
`:refundable?` flag — adopted from US §32 EITC and confirmed in
ADR-101 Addendum 1 (the refundability slot exists). The AU
investment-income provider's contract is to emit each credit with
the correct `:refundable?` flag; the PIT provider does the
ordering.

### 6.3 LIC capital gain credit interaction (deferred Gap 6)

LICs (note §1.10) pass through a portion of their distribution as
realised LT capital gain. The shareholder gets a 50 % deduction
for the LIC portion (individuals / trusts), mirroring the
post-2027-still-extant 50 % discount on direct holdings (note
129 §1.4 — note that the LIC mechanism survives the 2027 reform
since it's a Subdiv 115 *deduction* not a discount).

If shipped, the investment-income provider would emit the LIC-CG
portion to the *CGT* provider's `:inputs` rather than the PIT
provider's — the CGT provider then runs the 50 % discount. Cross-
provider input wiring — the substrate already supports it via the
`:inputs` map. Deferred to consumer demand; flag in the ADR.

### 6.4 Cross-component composition pattern reaffirmed

The pattern of **multiple sibling providers each emitting
adjustment inputs that the holder's PIT / CIT provider sweeps** is
the standard kontor pattern documented in note 113 §5.1 / note 115
§5 / note 129 §5. AU investment-income is the THIRD provider that
folds into AU PIT / CIT (along with `au-cgt-provider` and the
existing `au-individual` / `au-company-tax` providers). No
substrate stress; the pattern travels.

---

## §7. Open questions for the implementation agent

1. **Should `kontor-investment-income` be its own ADR + companion
   module, or roll into `kontor.l10n-au`?** Gap 1's recommendation is
   a kernel companion. The market for it is: AU (this note); AT
   (note 154); DE (Abgeltungsteuer regime); JP (20.315 %); FR (PFU);
   eventually US (qualified dividend / NIIT), CA (eligible /
   non-eligible dividend gross-up). Six+ jurisdictions imply a
   shared substrate justifies a kernel companion. **Recommendation:
   ship the companion + ADR.**

2. **Should the franking-credit + foreign-tax-withheld + holding-days
   live on the `:transaction` directly (via ADR-090 concept-iri tags
   + `:audit-doc`) or on a dedicated `:investment-income-event`
   substrate?** §3 favours the substrate; this question is the same
   as Q1.

3. **How does the provider receive the GL-side cash dividend posting
   from the holder's perspective?** The kernel's `book.clj/receive!`
   verb posts (Dr Bank / Cr Income); the provider needs to recognise
   the income-side credit as the cash dividend. Mechanism: the
   `:investment-income-event/realizing-tx` ref points to the
   transaction; the provider reads the credit side amount from there.
   This matches the `:disposal/realizing-tx` pattern (note 129).

4. **CFI declaration**: when the holder is a foreign resident,
   should the provider emit a CFI-eligibility check? §1.6 says CFI
   is issuer-side; the shareholder-side provider just doesn't
   include CFI-tagged distributions in taxable income. The
   `:au-iie/cfi-declared?` flag drives the exclusion. v1 ships the
   exclusion; the issuer-side mechanic (the issuer's CFI ledger) is
   future work.

5. **Holding-period rule — when to ship the at-risk-position
   computation?** §1.3's "at risk" definition requires derivative-
   offset modeling (hedges, short positions, options). The substrate
   does NOT track derivative positions at the kernel level. **v1
   recommendation**: ship the small-shareholder exemption + a
   simple holding-days check; for `:individual` holders below
   $5,000 annual credit, skip; otherwise rely on
   `:au-iie/holding-days ≥ 45` as the proxy (consumer is responsible
   for ensuring at-risk position when populating the event). P2
   follow-up for derivative-aware computation when consumers need
   it.

6. **Foreign-resident dividend WHT** (§1.4 last row) — is this in
   scope for the investment-income provider, or is it
   issuer-side? **Recommendation**: issuer-side, like CFI. The
   foreign resident's holder-side provider in their home
   jurisdiction handles the FITO claim from the foreign WHT.

7. **Trust passthrough — v1 scope?** §1.9 / Gap 5 ship `:fixed` +
   `:discretionary-no-fte` only. FTE family-group apportionment
   is consumer-specific (the consumer knows their family-group
   membership). **Recommendation: confirm.**

8. **Should the provider compute the franking-account-credit
   ledger for the corporate-holder branch (§1.8 / §2.4)?** v1
   recommendation: emit the pending-credit signal via
   `:jurisdiction-specific-codes
   :au-franking-account-credit-pending`; the ledger-side workflow
   (future, in `kontor.incorporation` ADR expansion) consumes it.
   The provider does NOT maintain the franking-account state
   itself.

---

## §8. Sources

### AU statute

- **ITAA 1997 Part 3-6** — Imputation (Divisions 200–220).
  - s.200-5 — What Subdivision 200-A is about (overview).
  - s.202-25 — Allocation of franking credits.
  - s.203-25 — Benchmark franking percentage.
  - s.203-50 — Over-franking tax.
  - s.205-15 — Crediting the franking account.
  - s.207-20 — The gross-up rule.
  - s.207-45 — Refund of excess franking credits (resident
    individuals + super funds).
  - s.207-145 — Refundability denied to certain non-fixed trusts.
  - s.207-150 — Trapped franking credits in non-fixed trusts.
- **ITAA 1936 former Part IIIAA Div 1A** (preserved as savings) —
  qualified-person rules; 45-day holding-period rule.
  - s.160APHO — the 45-day at-risk holding-period rule.
  - s.160APHT — small-shareholder exemption ($5,000 annual cap).
- **ITAA 1936 s.44(1)** — assessable income includes dividends.
- **ITAA 1936 s.128B(3)** — dividend withholding tax exemption for
  franked dividends to foreign residents.
- **ITAA 1936 s.128B** — interest withholding tax (non-resident).
- **ITAA 1936 Pt VA** — TFN withholding mechanism.
- **ITAA 1997 s.770-10** — Foreign Income Tax Offset (FITO).
- **ITAA 1997 s.770-75** — FITO small-claims shortcut ($1,000).
- **ITAA 1997 s.960-50** — currency translation for foreign income.
- **ITAA 1997 Div 802 (Subdiv 802-A)** — conduit foreign income.
  - s.802-15 — CFI not assessable to foreign residents.
  - s.802-20 — non-assessable non-exempt income for AU corporates.
  - s.802-50 — CFI account.
- **ITAA 1997 Subdiv 115-285** — LIC capital gain credit.
- **ITAA 1997 s.115-100** — discount percentages (50 % / 1/3 / 0 %).
- **ITAA 1936 Sch 2F** — Family Trust Election.

### ATO guidance

- ATO — [Allocating franking credits](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/paying-dividends-and-other-distributions/allocating-franking-credits).
- ATO — [Receiving dividends and other distributions](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/receiving-dividends-and-other-distributions).
- ATO — [Benchmark rule](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/paying-dividends-and-other-distributions/allocating-franking-credits/benchmark-rule).
- ATO — [How to calculate over-franking tax and under-franking debit](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/in-detail/how-to-calculate-over-franking-tax-and-under-franking-debit).
- ATO — [Franking deficit tax](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/paying-dividends-and-other-distributions/franking-account/franking-deficit-tax).
- ATO — [Refund of franking credits for individuals](https://www.ato.gov.au/individuals-and-families/investments-and-assets/investing-in-shares/owning-shares/refunding-franking-credits-individuals).
- ATO — [Refund of franking credits for not-for-profit entities](https://www.ato.gov.au/businesses-and-organisations/not-for-profit-organisations/statements-and-returns/refund-of-franking-credits-for-not-for-profit-entities/rules-on-claiming-a-franking-credit-refund).
- ATO — [Franking credit trading (integrity)](https://www.ato.gov.au/businesses-and-organisations/corporate-tax-measures-and-assurance/imputation/integrity-rules/franking-credit-trading).
- ATO — [Claiming a foreign income tax offset](https://www.ato.gov.au/individuals-and-families/income-deductions-offsets-and-records/tax-offsets/claiming-a-foreign-income-tax-offset).
- ATO — [Guide to foreign income tax offset rules 2025](https://www.ato.gov.au/forms-and-instructions/foreign-income-tax-offset-rules-guide-2025).
- ATO — [Dividends and non-resident companies and shareholders](https://www.ato.gov.au/forms-and-instructions/you-and-your-shares-2022/dividends-and-non-resident-companies-and-shareholders).
- ATO — [Interest, unfranked dividends and royalties](https://www.ato.gov.au/individuals-and-families/investments-and-assets/foreign-resident-investments/interest-unfranked-dividends-and-royalties).
- ATO — [Withholding from dividends paid to foreign residents](https://www.ato.gov.au/businesses-and-organisations/international-tax-for-business/in-detail/income/withholding-from-dividends-paid-to-foreign-residents).
- ATO — [Withholding from investment income (overview)](https://www.ato.gov.au/businesses-and-organisations/hiring-and-paying-your-workers/payg-withholding/payments-you-need-to-withhold-from/withholding-from-investment-income).
- ATO — [Withholding rate (non-resident)](https://www.ato.gov.au/businesses-and-organisations/hiring-and-paying-your-workers/payg-withholding/payments-you-need-to-withhold-from/withholding-from-investment-income/investment-income-and-royalties-paid-to-foreign-residents/withholding-rate).
- ATO — [Interest, dividends, royalties and MIT payments](https://www.ato.gov.au/businesses-and-organisations/hiring-and-paying-your-workers/payg-withholding/payments-you-need-to-withhold-from/withholding-from-investment-income/investment-income-and-royalties-paid-to-foreign-residents/interest-dividends-royalties-and-mit-payments).
- ATO TR 2009/6 — entitlement to FITO under s.770-10 [AustLII](https://www8.austlii.edu.au/au/other/rulings/ato/ATOTR/2009/TR20096.html).

### Statutory database (AustLII)

- ITAA 1997 s.802-5 — what Subdiv 802-A is about: https://classic.austlii.edu.au/au/legis/cth/consol_act/itaa1997240/s802.5.html
- ITAA 1997 s.802-20 — distributions between AU corporates as NANE: https://classic.austlii.edu.au/au/legis/cth/consol_act/itaa1997240/s802.20.html

### Practitioner commentary

- Bristax — [Dividend Withholding Tax](https://bristax.com.au/tax-articles/dividend-withholding-tax/).
- Bristax — [Interest Withholding Tax](https://bristax.com.au/tax-articles/interest-withholding-tax/).
- Bristax — [Foreign Income Tax Offset](https://bristax.com.au/tax-articles/foreign-income-tax-offset/).
- HWL Ebsworth — [Breaching the 45-day rule: your franking credits are at risk](https://hwlebsworth.com.au/tax-insight-your-franking-credits-are-at-risk-newly-incorporated-companies-breaching-the-45-day-holding-period-rule/).
- Journal of Australian Taxation — [The 45 Day Holding Period Rule (Laurie, Collins, Murton 1999)](https://journalofaustraliantax.com.au/Articles_Free/JAT%20Volume%2002,%20Issue%203%20-%20Laurie.pdf) — historical primer.
- Tax Technical — [Franking credits — the 45-day rule](https://taxtechnical.com.au/franking-credits-the-45-day-rule-where-are-you/).
- Grow Accounting — [45 Day Rule — Don't Lose Your Franking Credits](https://growaccounting.com.au/45-day-franking-credits/).
- Class Support — [The 45 Day Rule](https://support.class.com.au/hc/en-au/articles/360001760656-The-45-Day-Rule).
- Tax Board (Institute of Chartered Accountants in Australia) — [Holding-Period Rule submission](https://taxboard.gov.au/sites/taxboard.gov.au/files/migrated/2015/07/The_Institute_of_Chartered_Accountants_in_Australia.pdf).
- Baron Accounting — [How to Calculate Franking Credits](https://www.baronaccounting.com/post/how-to-calculate-franking-credits-an-australian-guide-for-investors).
- Everglow — [How to Calculate Franking Credits: 2026 Guide](https://everglow.au/how-to-calculate-franking-credits/).
- Parliamentary Budget Office — [Dividend imputation and franking credits explainer](https://www.pbo.gov.au/about-budgets/budget-insights/budget-explainers/dividend-imputation-and-franking-credits).
- AusTax.tools — [Franking Credits Calculator 2025-26](https://austax.tools/franking-credits-calculator-australia/).
- SuperGuide — [Franking credits and dividend income in your SMSF](https://www.superguide.com.au/smsfs/franked-dividends-and-franking-credits-how-do-they-work).
- BK Partners — [SMSF Using dividend franking credits](https://www.bkpartners.com.au/news-articles/smsf-and-superannuation/smsf-using-dividend-franking-credits).
- LTE Tax — [Retiree & SMSF Guide to Franking Credits and Tax Refunds](https://www.ltetax.com/blog/franking-credits-in-australia-how-retirees-smsfs-can-maximise-their-tax-returns/).
- Bentleys — [SMSF Tax in Australia: Essential Guide](https://www.bentleys.com.au/insights/smsf-tax-in-australia/).
- Superannuation Warehouse — [Imputation Credits in a SMSF](https://superannuationwarehouse.com.au/assets/imputation-credits-in-a-smsf/).
- Sleek — [What Is Withholding Tax in Australia and How Does It Work?](https://sleek.com/au/resources/withholding-tax/).
- PwC Worldwide Tax Summaries — [Australia Corporate Withholding Taxes](https://taxsummaries.pwc.com/australia/corporate/withholding-taxes).
- CST Tax — [Exempt Dividend Income From Overseas Subsidiaries](https://csttax.com/en-au/professionals/blog/exempt-dividend-income-from-overseas-subsidiaries/).
- Nanak Accountants — [Foreign Income Tax Offset (FITO) Australia 2025 — ATO Guide](https://nanakaccountants.com.au/blog/foreign-income-tax-offset-fito-in-australia/).
- Endurego — [Foreign Income Tax Offset](https://www.endurego.com.au/foreign-income-tax-offset/).
- Wolters Kluwer CCH — [Foreign Income and the Foreign Income Tax Offset (Mark Chapman)](https://assets.contenthub.wolterskluwer.com/api/public/content/2389982-powerpoint---foreign-income-and-the-foreign-income-offset-313afa0094?v=4be66cbc).

### kontor substrate cited

- `src/kontor/book.clj:296-330` — `declare-dividend!` +
  `distribute-dividend!` verbs; the docstring naming the
  jurisdiction-specific investment-income regimes that this
  note implements for AU.
- `src/kontor/incorporation.clj` — the issuer-side state where
  CFI / franking-account ledger will eventually live (out of
  scope for this note).
- `modules/disposal/src/kontor/disposal/schema.clj` — the
  `:disposal` schema this note's proposed
  `:investment-income-event` companion mirrors structurally.
- `modules/l10n-au/src/kontor/l10n_au/cgt_provider.clj:80` —
  the `:individual / :trust / :super-fund / :company` holder-kind
  enum the investment-income provider reuses.
- `modules/l10n-au/src/kontor/l10n_au/period_tax_provider.clj` —
  the AU PIT (`au-individual`) and CIT (`au-company-tax`) providers
  the investment-income provider folds its gross-ups and credits
  into.
- `src/kontor/tax_schedule.clj` — schedule algebra; the
  `:credit refundable?` slot the provider needs (verify in
  implementation; ADR-101 Addendum 1 documents `:op :credit`).
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider` +
  `TaxReturnFacts` + the `:jurisdiction-specific-codes
  :pit-base-additions / :cit-base-additions / :pit-credits /
  :cit-credits` shape pattern.
- `doc/decisions.md` ADR-099 — `PeriodTaxProvider` substrate.
- `doc/decisions.md` ADR-101 — statute-as-data; potential home
  for the corporate-rate-cutover parameters (large vs BRE, with
  per-issuer rate tracking).
- `doc/decisions.md` ADR-101 Addendum 1 — `:op :credit` + refundability slot.
- `doc/decisions.md` ADR-102 — `kontor-disposal` companion; the
  structural analogue for the proposed
  `kontor-investment-income` companion.
- `doc/research/107-phase-3-incorporation-and-disposal.md` — Phase
  3 plan; investment-income lands in Phase C2 (note 153 + 154).
- `doc/research/129-au-cgt-fit.md` — AU CGT fit note; the
  precedent for AU multi-axis holder-class dispatch.
- `doc/research/134-at-cgt-fit.md` — AT CGT fit; the structural
  precedent for the corporate-investment-income inversion
  pattern (§10 KStG) the AT companion note 154 builds on.
- `doc/research/154-at-investment-income-fit.md` — AT
  investment-income fit (this note's sibling); shares the
  proposed `kontor-investment-income` companion substrate.

---

End of note 153.

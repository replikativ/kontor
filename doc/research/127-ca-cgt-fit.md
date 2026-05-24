---
date: 2026-05-24
title: 127 — CA capital-gains tax — substrate fit against the shipped `:disposal` schema
audience: maintainer + the Phase 3 `ca-cgt-provider` implementation agent
status: research-before for the CA CGT companion of ADR-101 `:disposal` + the future `ca-cgt-provider`; no code
---

# 127 — CA capital-gains tax: `:disposal` schema fit assessment

CA capital-gains tax is, **statutorily**, one of the simplest CGT regimes
the kontor substrate has touched — `ITA s.38(a)` is a single one-half
inclusion rate, period. The complexity is **at the edges**: the
lifetime exemption (LCGE, `s.110.6`), the long list of elective
rollovers (`s.85` / `s.86` / `s.51` / `s.87` / `s.44` / `s.73`),
the watertight superficial-loss rule (`s.40(2)(g)(i)` + `s.54
"superficial loss"`), the ABIL detour out of the capital-gains
compartment (`s.38(c) + s.39(1)(c)`), the principal-residence formula
(`s.40(2)(b)`), the CCA-recapture split for depreciables (`s.13(1)`
recapture is **ordinary income**, not capital), and the corporate-side
Capital Dividend Account integration (`s.83(2)` / `s.89(1)`). CA also
has the rare political-history wrinkle that the **proposed 66.67 %
inclusion rate above $250 k was cancelled** on 21 Mar 2025, so the
substrate must NOT carry that change.

This note (a) summarises the CA regime per asset class + per provision,
(b) walks two worked examples, (c) assesses whether the shipped
`:disposal` schema carries it, (d) names concrete data gaps, (e) sketches
a `ca-cgt-provider` aligned with the existing
`modules/l10n-ca/cit-provider`, and (f) cites sources.

**Bottom line: the shipped `:disposal` schema covers CA cleanly.** ZERO
schema additions are required for v1; the elective regimes, exemptions,
loss buckets, and asset classes proposed in the task prompt all fit the
existing many-keyword `:elective-regime` / `:exemption-claimed` /
`:loss-bucket` slots. ONE optional companion-side helper (a CA-specific
`asset-class` enumeration registered in a side table) makes provider
dispatch easier but is NOT a schema change. TWO conceptual cautions
surface that the provider must enforce in code, not in schema:
(1) CCA-recapture splits gain into ordinary vs capital — `:disposal`
already carries `:depreciation-taken-amount` per note 112; (2) ABIL is
NOT a capital-loss bucket — it flows OUT of the CGT compartment into
"any income" via the personal-income-tax provider's `:inputs`.

---

## §1. The CA CGT regime — the moving parts

### 1.1 The inclusion rate — `ITA s.38(a)` — one-half, period

For 2026:

> "a taxpayer's **taxable capital gain** for a taxation year from the
> disposition of any property is ½ of the taxpayer's capital gain for
> the year from the disposition of the property"
>
> — [Income Tax Act, s.38(a)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html)

The famous "50 % inclusion." Two non-50 % exceptions matter for the
substrate:

- **`s.38(a.1)` — zero-inclusion gift of publicly listed securities**.
  Gain on an in-kind donation to a qualified donee of a share/debt-
  obligation/right **listed on a designated stock exchange** has
  inclusion rate **ZERO**. CRA: ["Capital gains realized on gifts of
  certain capital property"](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-34900-donations-gifts/capital-gains-realized-on-gifts-certain-capital-property.html).
  Reported on **Form T1170**.
- **`s.38(c)` — ABIL at ½ of a business investment loss**. The ABIL is
  "an allowable business investment loss for a taxation year is ½ of
  a business investment loss." Unlike ordinary capital losses, the
  ABIL is **deductible against ANY income** (not just capital gains).
  See [Income Tax Folio S4-F8-C1](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/series-4-businesses-folio-8-losses/income-tax-folio-s4-f8-c1-business-investment-losses.html).

### 1.2 The cancelled 2024 increase — substrate MUST NOT carry it

Timeline:

- **25 Jun 2024**: Bill C-69 (Budget 2024) introduced an increase of the
  inclusion rate from ½ to **2/3** on capital gains realised annually
  **above $250 000** by individuals, and on **all** gains by
  corporations and most trusts.
- **31 Jan 2025**: Government of Canada deferred the effective date to
  **1 Jan 2026** ([Department of Finance press release](https://www.canada.ca/en/department-finance/news/2025/01/government-of-canada-announces-deferral-in-implementation-of-change-to-capital-gains-inclusion-rate.html)).
- **21 Mar 2025**: Government announced **cancellation** of the increase
  outright ([Scotia Wealth Management commentary](https://enrichedthinking.scotiawealthmanagement.com/2025/04/07/cancellation-of-the-proposed-capital-gains-inclusion-rate-increase/),
  citing the Department of Finance March-21 statement). Quebec
  harmonised ([Revenu Québec, 5 Feb 2025](https://www.revenuquebec.ca/en/press-room/tax-news/details/2025-02-05/harmonization-with-the-deferral-until-january-1-2026-of-the-implementation-of-the-change-to-the-capital-gains-inclusion-rate/),
  then implicitly cancelled with the federal cancellation).

**Substrate posture**: the `ca-cgt-provider` ships with a **single**
`(ts/flat 0.5M)` schedule. No tiered bracket. No $250 k threshold.
A `:parameter`-level commented entry **may** preserve the cancellation
for audit-trail purposes (a `:parameter/value 0.5` with a citation
noting "proposed 2/3 cancelled Mar-21-2025"), but no provision fires.

The **Canadian Entrepreneurs' Incentive (CEI)** — the supposed
sweetener for the cancelled increase, offering a one-third inclusion
on a lifetime $2 M of eligible gains — is **also in political limbo**
post-cancellation ([Scotia commentary](https://enrichedthinking.scotiawealthmanagement.com/2025/04/07/cancellation-of-the-proposed-capital-gains-inclusion-rate-increase/)
notes its status is "uncertain"). The substrate ships WITHOUT a CEI
provision; a `:regime/extends` on CEI would be the addition pattern
**when and if** it lands.

### 1.3 Asset categories — `ITA` reads as a uniform "capital property" world, the substrate carves classes

CA's statute does NOT enumerate asset classes; "capital property" is
defined negatively (`s.54`) — anything that isn't inventory or
employment income. The asset-class breakdown emerges from the
provisions that **gate** on what was disposed:

| Class | Statute hook | Special treatment | Substrate `:asset-class` (proposed) |
|---|---|---|---|
| **Listed public shares** (TFSA/RRSP shelter aside — those are not "dispositions" in any taxable sense) | `s.38(a)` flat | 50 % inclusion; zero if in-kind donated `s.38(a.1)` | `:ca-public-shares` |
| **Private-co shares — QSBC** | `s.110.6(1) "qualified small business corporation share"` + `s.110.6(2.1)` deduction | LCGE up to $1,275,000 (2026) — see §1.4 | `:ca-qsbcs` |
| **Qualified farm / fishing property** | `s.110.6(1.3)` + `s.110.6(2)` | Same LCGE pool as QSBCS (single $1,275,000 lifetime cap covers both) | `:ca-qfp-qfishing` |
| **Personal-use property (PUP)** | `s.46(1)` | $1,000 floor on ACB AND proceeds; loss generally **NOT deductible** | `:ca-personal-use` |
| **Listed personal property (LPP — art, coins, stamps, books, jewellery)** | `s.46(2) + s.41` | $1,000 floor; LPP losses offset only LPP gains, 3-yr carryback / 7-yr carryforward (specific lane) | `:ca-listed-personal-property` |
| **Principal residence** | `s.40(2)(b)` | Exemption via formula (see §1.5); designate per year on **Form T2091** | `:ca-principal-residence` |
| **Real estate — investment / rental** | `s.38(a)` flat + `s.13` for depreciable building | Land = capital; building = depreciable (recapture+capital) | `:ca-real-estate` |
| **Depreciable property** | `s.13(1)` recapture + `s.38(a)` for capital portion | Recapture ordinary; capital portion 50 %; terminal loss possible | `:ca-depreciable` |
| **Eligible capital property / Class 14.1 intangibles** | Post-2017 reform: ECP folded into Class 14.1 depreciable; `s.13` applies | Same split as depreciable (recapture ordinary + capital gain) | `:ca-class-14.1` |
| **Bonds, debentures, T-bills** | `s.38(a)` flat | 50 % inclusion; accrued interest separately taxable | `:ca-debt-instruments` |
| **Foreign currency** | `s.39(2)` | $200 gain/loss de-minimis per year for individuals | `:ca-foreign-currency` |
| **Cryptocurrency** | `s.38(a)` flat **IF** held on capital account; CRA tends to argue business income for traders | 50 % inclusion if capital; full inclusion if business | `:ca-crypto-asset` |

The proposed asset-class enumeration in the task prompt
(`:ca-public-shares`, `:ca-qsbcs`, `:ca-qfp-qfishing`,
`:ca-principal-residence`, `:ca-personal-use`, `:ca-depreciable`,
`:ca-real-estate`) is **adequate for v1** and maps cleanly onto the
provider's lane-selection. Recommended additions for completeness:
`:ca-listed-personal-property` (the LPP carry-bucket is its own lane),
`:ca-class-14.1` (intangibles' recapture story), `:ca-debt-instruments`
(routinely disposed-of asset class), `:ca-foreign-currency`,
`:ca-crypto-asset`.

### 1.4 Lifetime Capital Gains Exemption (LCGE) — `s.110.6`

The shape:

- **2025 disposals**: $1,250,000 (frozen at this level — the
  June-25-2024 increase from $1,016,836 stood; the inclusion-rate
  increase was cancelled, but the LCGE bump was kept).
- **2026 disposals**: **$1,275,000** (indexation resumed) —
  [TaxTips.ca LCGE history](https://www.taxtips.ca/smallbusiness/lifetime-capital-gains-exemption.htm).
- **Future**: indexed annually to inflation per `s.117.1`.

The deduction itself is **lifetime-cumulative**: the taxpayer's prior
LCGE claims (across all prior tax years, against all prior QSBC / QFP
dispositions) **reduce the available exemption**. The CRA tracks
this via **Form T657** ("Calculation of Capital Gains Deduction") +
**Form T936** ("Calculation of Cumulative Net Investment Loss"
— the CNIL grind that further reduces the LCGE).

Three eligibility tests for QSBC shares ([Mondaq: QSBC 24-month rule](https://www.mondaq.com/canada/capital-gains-tax/1723130/qsbc-shares-and-the-24-month-holding-period-requirement)):

1. **Small Business Corporation test at disposition** (`s.110.6(1)
   "qualified small business corporation share" (a)`): **all or
   substantially all** (CRA: ≥ 90 %) of the corporation's FMV is
   attributable to assets used principally in an active business
   carried on primarily in Canada, OR shares/debt of connected SBCs.
2. **24-month holding test** (`s.110.6(1) "QSBC share" (b)`): the
   shares were owned ONLY by the taxpayer or a related person
   throughout the 24 months ending at disposition.
3. **24-month asset test** (`s.110.6(1) "QSBC share" (c)`): throughout
   the 24-month period, MORE THAN 50 % of the corporation's FMV was
   attributable to active-business assets used principally in Canada.

The provider does NOT verify these tests — they require facts kontor
does not own (CRA-side audit material, fair-market-value reports). The
provider reads `:disposal/exemption-claimed :ca-lcge-qsbcs` and trusts
the claim; the audit-doc + the consumer's bookkeeping bear the
verification burden.

### 1.5 Principal-residence exemption — `s.40(2)(b)`

The "1 plus" formula:

```
exempt portion of gain  =  gain × (1 + years designated as principal residence) / (years owned)
```

[Income Tax Folio S1-F3-C2](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-1-individuals/folio-3-family-unit-issues/income-tax-folio-s1-f3-c2-principal-residence.html)
unpacks the variables:

- **A** = the capital gain on the property (proceeds − ACB − selling
  expenses).
- **B** = 1 + (number of tax years ending after acquisition date for
  which the property was designated as the taxpayer's principal
  residence AND during which the taxpayer was resident in Canada).
- **C** = number of tax years owned.
- **Exempt** = A × B / C; **taxable** = A − exempt.

The "**+1**" allows the year of move-out / move-in to be claimed on
**both** the old and new properties without double-claim (a year can
only be designated once per **family unit** post-1981 — including
spouse/common-law partner and minor children).

Two complications for a substrate:

1. **Family-unit designation conflict** — only one residence per family
   per year qualifies. The provider needs to read **all** disposals
   for the family unit's `:residence?` claims across years, NOT just
   the assessed-entity's disposals. This is a **provider-time fold over
   the disposal history**, not a per-`:disposal` field.
2. **The 1-plus rule does NOT apply for years of non-residence** —
   if the holder was a non-resident of Canada during the designated
   year, the rule collapses to the actual designated years only
   ([Shajani CPA explainer](https://shajani.ca/principal-residence-exemption-pre-2026-how-the-formula-really-works/)).

### 1.6 No short / long distinction

CA has **NO** statutory short-vs-long-term distinction in its capital-
gains regime. Held one day or held forty years, the inclusion rate is
the same ½. This is unlike the US (1-year ST/LT cutoff), DE (1-yr
movables / 10-yr real-estate / lifetime-exempt cutoffs), JP (5-yr +
10-yr Jan-1-measurement cutoffs).

**Substrate posture**: the `:disposal/holding-period` enum
(`:short / :long / :n-a`) is **`:n-a`** for every CA disposal. The
QSBC 24-month holding test is a **provider-time computation** on
`(:disposal/acquired-on, :disposal/disposed-on)`, not a denorm. The
2-yr threshold gates LCGE eligibility, NOT the inclusion rate.

### 1.7 Loss buckets — `s.111` and friends

The CA loss compartmentalisation:

| Compartment | Statute | Within-year offset | Carry rules |
|---|---|---|---|
| **Net capital loss** | `s.111(1)(b)` | Offsets taxable capital gains within the year | **3-yr carryback + indefinite forward** ([Section 111](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-111.html)); **only** against future taxable capital gains |
| **ABIL** | `s.38(c) + s.39(1)(c)` | ½ of a business-investment loss; deducts against **ANY income** in the year | **3-yr carryback + 10-yr forward**; if unused at year 11, becomes a regular net capital loss (re-enters the capital lane) |
| **LPP loss** | `s.41` | Offsets only LPP gains within the year | 3-yr carryback + **7-yr forward**; LPP-only |
| **Superficial loss** | `s.40(2)(g)(i) + s.54 "superficial loss"` | **Denied**; loss is added to the ACB of the substituted (re-acquired) property; recovered when the substituted property is later sold | Effectively rolled into a future disposal — no carry "ledger entry" |
| **PUP loss** (non-LPP) | `s.46(1)` (no deduction) | NEVER deductible | n/a |

**The superficial-loss rule** ([Manulife: Superficial losses](https://www.manulifeim.com/retail/ca/en/viewpoints/tax-planning/superficial-losses);
[CRA Capital losses](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/personal-income/line-12700-capital-gains/capital-losses-deductions.html)):
a loss is **superficial** if **both**:

1. The taxpayer (or an affiliated person — spouse, controlled corp,
   spouse-controlled corp) buys the identical or "substituted" property
   in the 61-day window starting 30 days **before** the disposal and
   ending 30 days **after**;
2. The taxpayer (or affiliated person) **still owns** the substituted
   property 30 days **after** the disposal.

The denied loss is added to the ACB of the substituted property. The
detection is a **cross-disposal computation** (provider-time, joins
disposals against re-acquisitions of "substantially identical" property
— commodity-equivalent join). Same shape as US §1091; same posture for
v1: trust the consumer to flag (`:disposal/loss-bucket :ca-superficial`)
when known; full detection is a future enhancement.

### 1.8 Elective regimes — the rollover family

| Rollover | Statute | What it does | When elected |
|---|---|---|---|
| **`s.85` transfer to corp** | ITA `s.85` | Transfer eligible property (capital property, eligible capital property, inventory, real property, certain debt) to a taxable Canadian corporation at an elected agreed amount between cost and FMV; corp's basis = elected amount; transferor's proceeds = elected amount; gain deferred to the extent of basis carryover | Joint election on **Form T2057** (or T2058 for partnerships) within prescribed time |
| **`s.86` share-for-share within same corp** | ITA `s.86` | Exchange ALL shares of a class for shares of another class in the **same corp** on a corporate reorganisation; basis rolls over; automatic (no election) | Automatic when conditions met |
| **`s.51` convertible-property conversion** | ITA `s.51(1)` | Convert a convertible bond/debenture/note/share into shares of the same corp; basis rolls over; automatic | Automatic |
| **`s.87` amalgamation** | ITA `s.87` | Merge two+ corps; tax attributes (losses, basis) carry to the amalgamated corp; automatic | Automatic |
| **`s.44` replacement property (former business)** | ITA `s.44(1)`, `s.13(4)` for depreciable | Voluntary or involuntary disposal of a "former business property" + acquire qualifying replacement within prescribed window (12 mo after fiscal year of involuntary; 12 mo for former business); gain deferred via basis carryover to replacement | Elective via amended return |
| **`s.73` spousal rollover** | ITA `s.73(1)` | Transfer capital property to spouse / common-law partner / spousal trust at rollover cost; basis carries to spouse; spouse recognises on subsequent disposal | Automatic unless **opt-out** elected |
| **In-kind donation of public shares** | ITA `s.38(a.1)` | Donate listed securities to a qualified donee → zero inclusion rate (the gain itself is NOT deferred, but the taxable portion is zero) | Automatic; reported on **Form T1170** |

The task-prompt elective-regime keyword set
(`:ca-§85-rollover`, `:ca-§44-replacement`, `:ca-§73-spousal`,
`:ca-charitable-public-share`) is **adequate for v1**. The substrate
will need ONE additional value, `:ca-§86-reorganisation`, to cover
the share-for-share case the provider sees often in CCPC
recapitalisations. `:ca-§87-amalgamation` and `:ca-§51-conversion`
are corporate-side events the provider rarely sees as a "disposal"
(they tend to fold into the M&A bookkeeping); v1 can defer.

### 1.9 Provincial dimension

CGT is **federal-only** in its computation. Each province piggybacks:
the **taxable capital gain** (i.e. half the gain, post-50 %-inclusion,
post-LCGE-deduction, post-loss-offset) flows into the **provincial
PIT/CIT base** without further per-province adjustments. The provincial
provider sees the post-inclusion number as a line item, taxes it at
the province's brackets.

**Quebec is the partial exception**: TP-1 Schedule G ([Revenu Québec
Schedule G overview](https://www.revenuquebec.ca/en/citizens/your-situation/capital-gains-faq/))
computes the taxable capital gain SEPARATELY for Quebec purposes, but
the result is the same as the federal Schedule 3 in normal cases.
QC harmonises with federal s.38 inclusion rate per Bulletin
[2025-1, 5 Feb 2025](https://cdn-contenu.quebec.ca/cdn-contenu/adm/min/finances/publications-adm/Bulletins/EN/BULEN_2025-1.pdf):
the QC inclusion rate matches federal at all times.

**Substrate posture**: the `ca-cgt-provider` produces ONE
`TaxReturnFacts` with `:authority :ca-cra`; the provincial PIT
provider's `:inputs` pick up the taxable-capital-gain line as a
`:base-add` item. Quebec's QC-CIT/PIT providers read the same
federal inclusion result. **No per-province CGT components needed.**

### 1.10 Corporate CGT — same ½, plus the CDA story

Corporate CGT mechanics ([RBC Wealth Management:
CDA + RDTOH guide](https://ca.rbcwealthmanagement.com/delegate/services/file/620750/content)):

- The corporation includes ½ of the capital gain in taxable income
  at the regular CIT rate (CCPC SBD up to limit at ~9-12 % combined;
  general corporate rate ~25-31 % depending on province).
- The **non-taxable half** is credited to the **Capital Dividend
  Account (CDA)** — a notional account, `s.89(1) "capital dividend
  account"`.
- A CCPC can **elect** (`s.83(2)` election; **Form T2054**) to pay
  the CDA balance out as a **tax-free capital dividend** to
  shareholders. The shareholders receive it free of tax.
- The taxable half flows through the **aggregate investment income**
  / **adjusted aggregate investment income** machinery for CCPCs:
  - Generates **non-eligible RDTOH** (Refundable Dividend Tax on Hand)
    at the rate of the refundable Part I (Additional Refundable Tax on
    Investment Income — ARTI, 10⅔ %) — the corporation gets a refund
    of $38.33 of every $100 of non-eligible dividends it pays.
  - Counts toward the **passive-income SBD-grind**: CCPCs with
    adjusted aggregate investment income (AAII) > $50 k in the prior
    year start losing their Small Business Deduction at $5 per $1 of
    AAII excess, fully ground at $150 k AAII
    ([Mackisen CPA: Passive income](https://mackisen.com/blog/passive-income-taxation-for-canadian-controlled-private-corporations)).

The substrate implications:

- The `ca-cgt-provider` adds the taxable half (½ × gain) as a
  **`:base-add`** item to the CIT provider's input. The CIT provider
  is already shipped (`modules/l10n-ca/cit-provider`); the CGT
  provider feeds it.
- The CDA half — the **non-taxable portion** — does NOT flow through
  `TaxReturnFacts` (it is not a tax). It is a **GL bookkeeping
  artefact**: a credit to a memo-account `:account/ca-cda-balance`
  whenever a corporate disposal is recognised. This is a **`kontor.book`
  verb** addition (`book/credit-cda`), NOT a CGT provider concern.
  When the corp elects to pay a capital dividend, the verb
  `book/pay-capital-dividend` debits CDA, credits cash, and emits the
  `s.83(2)` election audit-doc.
- The non-eligible RDTOH addition (Part I refundable tax + Part IV on
  non-eligible portfolio dividends) is also a CIT/dividend-tax
  bookkeeping artefact, NOT CGT — the CIT provider sees the taxable
  half of the gain in `:investment-income`, applies the additional
  refundable tax (ART), credits the result to `:account/ca-nrdtoh`.

**Conclusion**: the corporate-side CGT computation is **the same as
the individual side** (½ inclusion), with two **GL-side memo accounts**
(CDA, NRDTOH) the **CIT and book modules** maintain — NOT the CGT
provider.

---

## §2. Worked examples

### Example A — Individual, QSBC sale with LCGE

Mr. Singh, an Ontario resident, founded HoldCo Inc. (a CCPC) in
2019. He owns 100 % of the shares (basis $100). On 2026-06-15 he
sells his shares to a strategic acquirer for $1,500,000 cash.

HoldCo qualifies as a QSBC at the time of sale (>90 % active-business
assets, all in Canada, owned only by Mr. Singh for the 24 months prior).
Mr. Singh has $0 prior LCGE claimed, $0 CNIL, $0 net capital losses
carried forward.

**Computation**:

- Capital gain = $1,500,000 − $100 = **$1,499,900**.
- LCGE deduction (2026 limit $1,275,000) — capped at $1,275,000:
  **$1,275,000** of the gain is sheltered.
- Net taxable capital gain before inclusion = $1,499,900 − $1,275,000
  = $224,900.
- Apply 50 % inclusion: $224,900 × 0.5 = **$112,450 taxable capital
  gain** (Line 12700 of T1).
- This flows into Mr. Singh's federal + Ontario PIT brackets. At his
  combined marginal rate (assume Ontario top bracket ≈ 53.53 %),
  tax on this slice ≈ $60,170.
- **The LCGE itself shows as a deduction on Line 25400 of T1**, claimed
  via Form T657, of the FULL $1,275,000 × 50 % = $637,500
  (the "capital gains deduction" — half-of-LCGE because the LCGE pre-
  inclusion-rate-applied amount is sheltered).

Substrate trace: ONE `:disposal` with `:disposal/kind :sale`,
`:disposal/subject-kind :participation`, `:disposal/asset-class
:ca-qsbcs`, `:disposal/exemption-claimed #{:ca-lcge-qsbcs}`,
`:proceeds-amount 1500000M`, `:basis-amount 100M`. The CA CGT provider
reads the disposal, applies the LCGE cap, computes $112,450
taxable capital gain, returns it via `TaxReturnFacts` as a `:base-add`
to the PIT provider's `gross-income`. ON PIT provider taxes it.

### Example B — CCPC, listed-share gain, CDA election

OpsCo (a CCPC, fed/AB resident) sells 1,000 listed RBC shares for
$200,000 on 2026-04-15. Original cost (FIFO ACB) was $80,000.

**Computation**:

- Capital gain = $200,000 − $80,000 = **$120,000**.
- ½ inclusion → **$60,000 taxable capital gain** included in OpsCo's
  taxable income.
- The other **$60,000** (non-taxable half) is credited to the CDA.
- OpsCo pays Part I tax on the $60,000 at the general corporate rate
  (assume fed 15 % + AB 8 % = 23 %): $13,800 cash tax.
- $60,000 is also "investment income" for the ART (additional refundable
  tax) layer: 10⅔ % × $60,000 = $6,400 additional refundable tax,
  credited to non-eligible RDTOH (refundable on later payment of a
  non-eligible dividend).
- OpsCo elects under `s.83(2)` (Form T2054) and pays a $60,000
  **capital dividend** to its sole shareholder (Mr. Singh) on 2026-05-15.
  Mr. Singh receives $60,000 tax-free (no Schedule 3 entry; no T-slip).

Substrate trace: ONE `:disposal` with `:disposal/kind :sale`,
`:disposal/subject-kind :participation`, `:disposal/asset-class
:ca-public-shares`, `:proceeds-amount 200000M`, `:basis-amount 80000M`.
The CA CGT provider returns $60,000 as a `:base-add` to the CIT
provider's input. The CIT provider (already shipped) applies the
fed+AB rates. The CDA credit is a **`kontor.book` verb call** in the
recognising transaction (`book/credit-cda 60000M`), NOT a CGT-provider
concern. The capital-dividend payment is a separate
`book/pay-capital-dividend` call later.

---

## §3. `:disposal` schema fit assessment

Mapping each CA computation requirement against the shipped schema
(`modules/disposal/src/kontor/disposal/schema.clj`):

| CA requirement | Field | Carries? |
|---|---|---|
| Inclusion rate (uniform ½) | none needed | **Yes** — provider applies `(ts/flat 0.5M)` directly |
| Acquisition + disposal date (for QSBC 24-mo + holding-period audit) | `:disposal/acquired-on` + `:disposal/disposed-on` | **Yes** |
| Holding-period denorm (CA-specific: always `:n-a`) | `:disposal/holding-period` | **Yes** — set `:n-a` by the CA classifier; not informative |
| Proceeds + basis | `:disposal/proceeds-amount` / `-commodity` + `:disposal/basis-amount` / `-commodity` | **Yes** |
| Depreciable property — recapture split | `:disposal/depreciation-taken-amount` / `-commodity` | **Yes** — already shipped per note 112 |
| Asset class (QSBC vs PUP vs principal-residence vs ...) | `:disposal/asset-class` | **Yes** — open vocab, jurisdictionally namespaced |
| LCGE / Principal-residence / Public-share-donation exemption | `:disposal/exemption-claimed` (cardinality-many keyword) | **Yes** — keys `:ca-lcge-qsbcs` / `:ca-lcge-qfp` / `:ca-principal-residence` / `:ca-§38a1-listed-donation` |
| Elective rollovers (s.85 / s.44 / s.73 / s.86) | `:disposal/elective-regime` (cardinality-many keyword) | **Yes** — keys `:ca-§85-rollover` / `:ca-§86-reorganisation` / `:ca-§44-replacement` / `:ca-§73-spousal` |
| Loss-bucket classification | `:disposal/loss-bucket` | **Yes** — `:ca-capital-loss` / `:ca-abil` / `:ca-superficial` / `:ca-lpp-loss` |
| Rollover target reference | `:disposal/rollover-into-asset` + `:disposal/rollover-amount` / `-commodity` + `:disposal/rollover-deadline` | **Yes** — already shipped |
| GL realising transaction | `:disposal/realizing-tx` | **Yes** |
| Audit docs (T657, T1170, T2057, T2054, T2091) | `:disposal/audit-doc` | **Yes** (cardinality-many) |
| Ownership-fraction (CCPC connected-corp tests, QSBC asset test) | `:disposal/ownership-fraction` | **Yes** |
| Subject form (CCPC vs PCC vs PartnershipShare) | `:disposal/subject-form` | **Yes** — `:corp` / `:partnership` / `:sole-prop` / `:individual` |
| Principal-residence flag | `:disposal/residence?` | **Yes** |

**ZERO schema additions are required.** Every CA requirement maps to
an existing slot.

### 3.1 Concrete value-set extensions (companion-namespace, NOT schema)

For provider dispatch, the CA companion should DOCUMENT (not enforce)
the following value sets in the provider's docstring + register them in
a CA-companion `cgt-vocab` constant. None of these are schema changes
— they are conventions on the **open** `:disposal/asset-class`,
`:disposal/elective-regime`, `:disposal/exemption-claimed`,
`:disposal/loss-bucket` value spaces.

**`:disposal/asset-class` — CA values (proposed)**:
- `:ca-public-shares` — listed (designated stock exchange) equity
- `:ca-qsbcs` — QSBC shares per `s.110.6(1)`
- `:ca-qfp-qfishing` — qualified farm / fishing property per
  `s.110.6(1.3)` (single value because they share the LCGE pool)
- `:ca-principal-residence` — property designated under `s.40(2)(b)`
- `:ca-personal-use` — non-LPP PUP per `s.46(1)`
- `:ca-listed-personal-property` — LPP per `s.46(2)+s.41`
- `:ca-real-estate` — investment real estate (land + non-depreciated
  building component)
- `:ca-depreciable` — depreciable property per `s.13` (the recapture
  split happens at provider time using `:depreciation-taken-amount`)
- `:ca-class-14.1` — Class 14.1 intangibles (post-2017 ECP reform)
- `:ca-debt-instruments` — bonds, debentures, T-bills
- `:ca-foreign-currency` — `s.39(2)` foreign-exchange capital lane
- `:ca-crypto-asset` — crypto held on capital account (provider
  warns if `:subject-form` suggests dealer/trader → business income)

**`:disposal/elective-regime` — CA values (proposed)**:
- `:ca-§85-rollover` — transfer-to-corp at elected amount (T2057)
- `:ca-§86-reorganisation` — share-for-share in same corp (automatic)
- `:ca-§51-conversion` — convertible-property conversion (automatic)
- `:ca-§87-amalgamation` — amalgamation (automatic; rarely needs a
  `:disposal` because the GL handles it as a non-disposal continuation)
- `:ca-§44-replacement` — replacement property (former business or
  involuntary)
- `:ca-§73-spousal` — spousal rollover (`s.73(1)`)
- `:ca-charitable-public-share` — in-kind donation of listed
  securities (T1170; the `s.38(a.1)` zero-inclusion lane)

**`:disposal/exemption-claimed` — CA values (proposed)**:
- `:ca-lcge-qsbcs` — LCGE against a QSBC disposal
- `:ca-lcge-qfp` — LCGE against a QFP/QFishing disposal (same pool)
- `:ca-principal-residence` — `s.40(2)(b)` exemption (the formula's
  parameters live in the provider; the `:residence?` flag enables it)
- `:ca-§38a1-listed-donation` — zero-inclusion gift of listed
  securities (NOT a deduction; an inclusion-rate override)

**`:disposal/loss-bucket` — CA values (proposed)**:
- `:ca-capital-loss` — ordinary net capital loss (`s.111(1)(b)`;
  3-yr back / indefinite forward; only against future taxable CGs)
- `:ca-abil` — ½ of a business investment loss (`s.38(c)`;
  deducts against ANY income in-year; 3-yr back / 10-yr forward, then
  re-enters the capital lane)
- `:ca-superficial` — denied loss added to substituted property's
  ACB (`s.40(2)(g)(i)`; no carry — basis bump on the future disposal)
- `:ca-lpp-loss` — LPP-only loss (`s.41`; 3-yr back / 7-yr forward,
  only against LPP gains)

### 3.2 What the schema does NOT carry (and shouldn't)

Two CA quirks remain provider-side concerns, NOT schema attrs:

- **CNIL grind on LCGE** (`s.110.6(1) "annual gains limit"` reduced
  by cumulative net investment loss). CNIL is a **per-taxpayer
  multi-year state** — it does not live on a `:disposal`. The provider
  reads it as an `:inputs :cnil-balance` and reduces the available
  LCGE accordingly.
- **Lifetime LCGE already-claimed** (`s.110.6(2) "annual gains
  limit"` aggregating). Same shape: `:inputs :lcge-claimed-prior` is
  a per-taxpayer value the provider subtracts from the year's LCGE.

Both are **`:inputs` map fields** on the `PeriodTaxProvider` call —
exactly the shape ADR-099's `:capital-loss-carryforward` and PIT's
`:credits-carried-forward` already use.

### 3.3 Family-unit principal-residence designation

The `s.40(2)(b)` "one residence per family per year" rule is a
**cross-disposal fold across the family unit's disposals across years**.
The substrate's `:family/parent` ref (ADR-031 `kontor.entity`) plus
the disposal log give the provider what it needs: walk all
`:disposal`s where `:residence?` is true for entities in the family
unit, by year, and enforce the one-per-year designation. **No
schema addition** beyond what already exists.

---

## §4. Concrete data gaps

**Zero** schema gaps. **Three** documentation gaps the CA companion
must own:

1. **CGT-vocab constant** in the CA companion — register the
   `:asset-class` / `:elective-regime` / `:exemption-claimed` /
   `:loss-bucket` value sets above as a Clojure data map, with
   per-key citations to the ITA section. This is the equivalent of
   the JP companion's `jp-real-estate-holding-period` helper but
   for vocab discovery rather than computation.
2. **`PeriodTaxProvider` `:inputs` shape extension** — document the
   CA-specific input keys: `:lcge-claimed-prior` (Money), `:cnil-balance`
   (Money), `:capital-loss-carryforward {:ca-capital-loss <Money>
   :ca-lpp-loss <Money>}` (per-compartment map; ADR-099-addendum), and
   `:abil-carryforward {:year-of-origin :balance ...}` (per-vintage map
   because ABILs convert to ordinary capital loss at year 11).
3. **Superficial-loss helper** — a CA-companion fn
   `detect-superficial-losses [disposals window-days]` that walks a
   year's disposals (joined against subsequent re-acquisitions of
   the same commodity) and flags loss-disposals that would be
   superficial. v1 trusts the consumer's
   `:disposal/loss-bucket :ca-superficial` flag; the helper exists as
   a `bb` analyst tool. Full automated detection is v2.

---

## §5. `ca-cgt-provider` sketch

### 5.1 Component count

**One component per assessed entity per period**, fanned into ONE
`TaxReturnFacts` whose `:authority` is `:ca-cra`. The provincial
component(s) do NOT appear here — they piggyback on the federal
taxable-capital-gain line via `:base-add` on the existing CA CIT
provider's input or the future CA PIT provider's input.

The single component's `:base` is the period's net taxable capital gain:

```
∑ (½ × (proceeds − basis))
  − ∑ LCGE_used (per-disposal, capped at remaining lifetime pool)
  − ∑ taxable_loss_offset (within-period + carry-in)
  + recapture from depreciable dispositions (ordinary, separate line)
```

The provider emits the result as a `:base-add` adjustment to the
downstream PIT (individuals) or CIT (corporations) provider:

```clojure
{:kind :capital-gains-tax
 :authority :ca-cra
 :component :ca-fed
 :base #money :CAD 112450
 :schedule (ts/identity)        ;; the 50% inclusion is already baked into base
 :line-items [{:disposal/external-id "..." :role :gain :amount ...}
              {:disposal/external-id "..." :role :basis :amount ...}
              {:role :lcge-deduction :amount #money :CAD 1275000
               :statute "ITA s.110.6(2)" :audit-doc <T657>}
              ...]
 :composed-of [:ca-cgt-fed]}
```

The downstream PIT / CIT provider reads this as `:inputs :base-add`
or as an explicit `:gross-income-components` entry. Two ratchets to
consider:

- **Option A**: CGT provider returns the **taxable capital gain
  number** (post-50%-inclusion, post-LCGE, post-loss-offset) as a
  ready-to-add line; PIT / CIT does no further CGT-side math. This is
  the cleanest fit and mirrors note 112 §5.5's "option 1" for the US
  corp.
- **Option B**: CGT provider returns its own `:capital-gains-tax`
  component with a real schedule and rate; the PIT / CIT provider
  nets the gain OUT of book-profit via `:base-transform`. This is
  **wrong for CA** — CA does NOT have a separate CGT rate; the
  gain enters the PIT / CIT base AT the inclusion rate and pays the
  base provider's regular schedule.

**Recommend Option A**. The CGT provider does inclusion + exemption +
loss-offset; the PIT / CIT provider applies the brackets / flat rate.

### 5.2 Where recapture goes

The CCA-recapture portion (proceeds beyond NBV up to original capital
cost) is **ordinary income**, not capital. The CGT provider splits
each depreciable disposal:

- `recapture = min(proceeds, capital_cost) − NBV` → emits as a
  **separate `:base-add` to the PIT / CIT provider** with `:role
  :cca-recapture` (NOT as a CGT component);
- `capital_portion = max(0, proceeds − capital_cost)` → enters the
  CGT component at 50 % inclusion.

The split uses `:disposal/depreciation-taken-amount` to derive NBV:
`NBV = :basis-amount` (NBV is what the disposal calls "basis"; the
original cost reconstruction is `basis + depreciation-taken`). The
provider needs **both** the original capital cost AND the NBV to
distinguish recapture from capital portion — this is **already in
the shipped schema** (`:basis-amount` = NBV; original cost =
`:basis-amount + :depreciation-taken-amount`).

### 5.3 `DisposalSource` integration

Same as note 112's US sketch: the provider depends on the protocol,
the companion implements it. No new shape needed.

### 5.4 Configuration

```clojure
(defn ca-cgt-provider
  [{:keys [source kind]}]   ;; kind = :individual | :corporation
  (->CaCapitalGainsTaxProvider :ca-cgt source :ca-cra :CAD
                               "ITA s.38(a), s.39, s.40, s.110.6"
                               kind))
```

Two builders, one record. The `kind` axis gates the LCGE eligibility
(corporations get NO LCGE — LCGE is individuals + personal trusts
only) and the CDA credit (corporations only).

### 5.5 What lives elsewhere

- **The 50 % flat schedule** — `(ts/flat 0.5M)` in
  `kontor.tax-schedule`; already available.
- **The LCGE indexed amount** — a `:parameter` in the CA companion
  statute, keyed by year (2025 = $1,250,000; 2026 = $1,275,000;
  future years pulled from `s.117.1` indexation).
- **The CDA credit + capital-dividend payment** — `kontor.book`
  verbs (`credit-cda` / `pay-capital-dividend`), NOT CGT-provider.
- **The NRDTOH integration** — a CIT-provider concern (the additional
  refundable tax on investment income; the provider already exists
  per ADR-107).
- **The superficial-loss detection** — a CA-companion analyst tool
  (`detect-superficial-losses`); the provider TRUSTS the
  consumer-supplied `:loss-bucket :ca-superficial` flag.

---

## §6. Substrate stress this provider surfaces

- **None on the schedule algebra** — `(ts/flat 0.5M)` is what `:flat`
  is for; `:base-add` adjustments are what the adjustment layer
  (note 105) carries.
- **None on the `:disposal` schema** — ZERO additions needed. (CA
  is the simplest CGT fit of the five jurisdictions analysed —
  US / DE / UK / JP / CA — because its complexity lives in the
  **lifetime-cumulative** state, NOT in the disposal-level data.)
- **One on the `:inputs` shape** — three CA-specific input keys
  (`:lcge-claimed-prior`, `:cnil-balance`, `:abil-carryforward`).
  Same shape as ADR-099's existing `:capital-loss-carryforward`;
  ADR-099-addendum-track.
- **One on the family-unit walk** — the principal-residence
  designation rule reads disposals across entities in the
  `:family/parent` cluster. The substrate already supports this
  (ADR-031 `kontor.entity/family`); the CGT provider invokes
  `family-walk` for the principal-residence check.
- **One on the corporate book** — the CDA credit + capital-dividend
  payment verbs (`book/credit-cda`, `book/pay-capital-dividend`) are
  NEW `kontor.book` additions. Two new `:account/*` codes for
  `:ca-cda-balance` and `:ca-nrdtoh` (consumer-side, NOT kernel).

**Total**: 0 kernel changes + 0 disposal-schema changes + 3 `:inputs`
keys + 2 `kontor.book` verbs + 2 chart-of-accounts conventions in
the CA companion.

---

## §7. Sources

CA statutory (legislation):
- [Income Tax Act s.38 — taxable capital gain (½ inclusion)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html)
- [ITA s.39 — meaning of capital gain (ABIL definition at s.39(1)(c))](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-39.html)
- [ITA s.40 — capital gain computation; principal residence formula at s.40(2)(b); superficial loss at s.40(2)(g)(i)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-40.html)
- [ITA s.41 — Listed Personal Property loss bucket + 7-yr carry](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-41.html)
- [ITA s.44 — replacement property rollover (involuntary + former business)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-44.html)
- [ITA s.46 — personal-use property + $1,000 floor + LPP definition](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-46.html)
- [ITA s.54 — definitions (capital property, superficial loss, ACB)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-54.html)
- [ITA s.73 — spousal rollover (capital property + farm/fishing)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-73.html)
- [ITA s.83(2) — capital dividend election (CDA)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-83.html)
- [ITA s.85 — transfer to a Canadian corporation (T2057 election)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-85.html)
- [ITA s.86 — share-for-share within same corp (automatic)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-86.html)
- [ITA s.87 — amalgamation rollover](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-87.html)
- [ITA s.89(1) — capital dividend account definition](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-89.html)
- [ITA s.110.6 — Lifetime Capital Gains Exemption (QSBC + QFP/QFishing)](https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-110.6.html)
- [ITA s.111 — losses generally; net capital loss 3-yr back / indefinite forward at s.111(1)(b)](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-111.html)
- [ITA s.13 — recapture of CCA; s.13(4) replacement-property deferral](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-13.html)

CA government guidance:
- [Government of Canada deferral announcement — 31 Jan 2025](https://www.canada.ca/en/department-finance/news/2025/01/government-of-canada-announces-deferral-in-implementation-of-change-to-capital-gains-inclusion-rate.html)
- [CRA T4037 Capital Gains 2025 guide](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/t4037/capital-gains.html)
- [Income Tax Folio S1-F3-C2 — Principal Residence](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-1-individuals/folio-3-family-unit-issues/income-tax-folio-s1-f3-c2-principal-residence.html)
- [Income Tax Folio S3-F4-C1 — General Discussion of CCA](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/series-3-property-investments-savings-plans-folio-4-capital-cost-allowance/income-tax-folio-s3-f4-c1-general-discussion-capital-cost-allowance.html)
- [Income Tax Folio S3-F3-C1 — Replacement Property](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-3-property-investments-savings-plans/folio-3-capital-transactions/income-tax-folio-s3-f3-c1-replacement-property.html)
- [Income Tax Folio S4-F8-C1 — Business Investment Losses (ABIL)](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/series-4-businesses-folio-8-losses/income-tax-folio-s4-f8-c1-business-investment-losses.html)
- [Capital gains on gifts of certain capital property (s.38(a.1))](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-34900-donations-gifts/capital-gains-realized-on-gifts-certain-capital-property.html)
- [Capital losses — Line 25300 net capital losses of other years](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/deductions-credits-expenses/line-25300-net-capital-losses-other-years.html)
- [Schedule 3 — Completing Schedule 3 (capital gains for T1)](https://www.canada.ca/en/revenue-agency/services/tax/individuals/topics/about-your-tax-return/tax-return/completing-a-tax-return/personal-income/line-12700-capital-gains/completing-schedule-3.html)
- [T2 Schedule 6 — Summary of Dispositions of Capital Property](https://support.cchifirm.ca/en/assistance/T2/2024/content/taxhelp/fed7.htm)
- [T2 Schedule 7 — Aggregate Investment Income + Small Business Deduction grind](https://support.cchifirm.ca/en/assistance/T2/2023/content/taxhelp/fed8.htm)

Reference / commentary:
- [Scotia Wealth Management — Cancellation of proposed inclusion-rate increase (7 Apr 2025)](https://enrichedthinking.scotiawealthmanagement.com/2025/04/07/cancellation-of-the-proposed-capital-gains-inclusion-rate-increase/)
- [Mondaq / Rotfleisch — QSBC 24-month holding rule](https://www.mondaq.com/canada/capital-gains-tax/1723130/qsbc-shares-and-the-24-month-holding-period-requirement)
- [TaxLawyer.com — Mastering the 24-month rule for QSBC + LCGE](https://taxlawyer.com/how-to-master-the-24-month-holding-period-requirement-for-qsbc-shares-and-lifetime-capital-gains-exemption-in-canada/)
- [TaxTips.ca — LCGE history and indexation table](https://www.taxtips.ca/smallbusiness/lifetime-capital-gains-exemption.htm)
- [BMO Private Wealth — Lifetime Capital Gains Exemption demystified](https://privatewealth-insights.bmo.com/en/insights/wealth-planning-and-strategy/lifetime-capital-gains-exemption-demystified-strategies-for-maximizing-your-gains/)
- [Shajani CPA — PRE 2026 formula explainer](https://shajani.ca/principal-residence-exemption-pre-2026-how-the-formula-really-works/)
- [Shajani CPA — Loss carrybacks/carryforwards (3-yr / indefinite)](https://shajani.ca/capital-loss-carrybacks-and-carryforwards-2026-the-3-year-indefinite-rule-explained/)
- [Manulife Investment Management — Superficial losses](https://www.manulifeim.com/retail/ca/en/viewpoints/tax-planning/superficial-losses)
- [Carson Law — Section 85 vs Section 86 rollovers (2026 update)](https://www.carsonlaw.ca/ourblog/2026/1/14/section-85-vs-section-86-rollovers-tax-deferred-restructuring-for-canadian-businesses)
- [Rosen Tax Law — Section 44 replacement property rules](https://rosentaxlaw.com/replacement-property-rules-deferring-a-capital-gain/)
- [RBC Wealth Management — CDA + RDTOH integration guide](https://ca.rbcwealthmanagement.com/delegate/services/file/620750/content)
- [Invesco — Passive investment income taxation for CCPCs](https://www.invesco.com/ca/en/insights/all-about-private-corporation-taxation-of-passive-investment-income.html)
- [Mackisen CPA — Passive income taxation for CCPCs (SBD grind)](https://mackisen.com/blog/passive-income-taxation-for-canadian-controlled-private-corporations)
- [Gowling WLG — Tax considerations when donating shares](https://gowlingwlg.com/en/insights-resources/articles/2025/tax-considerations-when-donating-shares-in-canada)
- [Marcil Lavallée — Sale of building, possible denial of terminal loss](https://marcil-lavallee.ca/en/bulletin/sale-of-building-used-for-business-or-rental-possible-denial-of-terminal-loss/)
- [Revenu Québec — Harmonisation with federal CGT deferral (5 Feb 2025)](https://www.revenuquebec.ca/en/press-room/tax-news/details/2025-02-05/harmonization-with-the-deferral-until-january-1-2026-of-the-implementation-of-the-change-to-the-capital-gains-inclusion-rate/)
- [Revenu Québec — Capital Gains FAQ](https://www.revenuquebec.ca/en/citizens/your-situation/capital-gains-faq/)
- [Ministère des Finances Québec — Bulletin 2025-1 (PDF)](https://cdn-contenu.quebec.ca/cdn-contenu/adm/min/finances/publications-adm/Bulletins/EN/BULEN_2025-1.pdf)

kontor substrate cited:
- `modules/disposal/src/kontor/disposal/schema.clj:62-309` — the shipped
  `:disposal/*` attrs this note assesses; ZERO additions required for CA.
- `src/kontor/period_tax_provider.clj:44-61` — closed `period-tax-kinds`
  enum, `:capital-gains-tax` already in.
- `src/kontor/period_tax_provider.clj:138-141` —
  `:capital-loss-carryforward` `:inputs` shape; CA extends to per-
  compartment keys (`:ca-capital-loss`, `:ca-lpp-loss`).
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor; CA's
  `(ts/flat 0.5M)` inclusion.
- `src/kontor/personal_income_tax.clj:37-118` — PIT provider into which
  CA CGT's taxable capital gain folds via `:base-add`.
- `modules/l10n-ca/src/kontor/l10n_ca/cit_provider.clj` — the shipped
  CA CIT provider (ADR-107); CA CGT provider feeds it the taxable
  capital gain as a `:base-add` on the federal component.
- `modules/l10n-ca/src/kontor/l10n_ca/cit_statute.clj` — the
  `:parameter` + `:provision` shape the CA CGT statute file would
  mirror (an LCGE `:parameter-value` keyed by year + indexation; a
  thin set of `:provision`s for §38(a.1) zero inclusion + §110.6
  LCGE deduction).
- `src/kontor/entity.clj` (ADR-031) — `family-walk` for the principal-
  residence designation rule.
- `src/kontor/book.clj` (ADR-095) — verb facade; the CDA + capital-
  dividend verbs slot here.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  `:disposal` schema proposal this note validates against CA.
- `doc/research/112-us-cgt-fit.md` — sibling note; the
  `:depreciation-taken-amount` field added there serves CA's CCA-
  recapture computation identically.
- `doc/research/111-ca-cit-fit.md` — sibling note; the CA CIT provider
  this CGT provider feeds.

---

End of note 127.

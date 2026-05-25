---
date: 2026-05-24
title: 158 — CN investment-income tax — substrate fit for Phase C2
audience: maintainer + the Phase C2 `cn-investment-income-provider` implementation agent
status: research-before for the CN investment-income companion of ADR-099 / ADR-101; no code; data-gap list at §4
---

# 158 — CN investment-income tax: substrate fit for Phase C2

Chinese investment-income tax is, structurally, **a duration-rewarded
flat-rate regime with a domestic-corporate inclusion bypass**. The
individual side is IIT category 7 (利息、股息、红利所得) at a nominal
flat **20 %**, but a 12-year-old reform (Caishui [2015] 101) carved
listed A-share dividends into a **three-tier holding-period gradation**
(≤ 1 month full, > 1 month and ≤ 1 year half, > 1 year fully exempt)
that is, in practice, the de-facto rule for the bulk of retail
dividend income. Bank-deposit interest has been **0 %** since 2008
(Caishui [2008] 132). The corporate side is even simpler: dividends
between qualifying TRRs (Tax Resident Enterprises) are **fully excluded
from EIT** under 企业所得税法 §26(2), again with a 12-month-on-listed
threshold under Article 83 of the Implementation Regulations. The
non-resident dividend tail pays 10 % WHT (treaty-reducible).

This note (a) summarises the CN investment-income regime per income
class + per taxpayer class, (b) walks two worked examples, (c) assesses
whether the kontor period-tax substrate (ADR-099 + the shipped CN CGT
providers + ADR-101 statute-as-data) carries it, (d) names concrete
data gaps, (e) sketches `cn-iit-investment-income-provider` +
`cn-eit-investment-income-provider` as siblings of the CGT providers,
(f) shows how it coordinates with the shipped `cn-iit-cgt-provider` +
`cn-eit-cgt-provider` (note 133), and (g) cites sources.

**Bottom line: the substrate carries CN investment-income cleanly.**
The 上市 A-share duration-gradation pattern is **structurally identical**
to the CGT provider's existing exemption-by-asset-class logic (note 133
§3) — same 上市 A-share asset class, different statutory hook (dividend
not gain), same date-arithmetic on holding period. The total kernel
schema gap is **ZERO**. The provider sketch adds **one new
namespace pair** to `kontor-l10n-cn` (the investment-income provider +
its statute file) and **two small companion attrs** on the income event
side (`:cn-investment-income/holder-class` + `:cn-investment-income/
shareholding-percentage` — see §4).

---

## §1. The CN investment-income regime — three income classes, three taxpayer-class branches

### 1.1 The income map

CN groups passive investment income for IIT under a single statutory
hook — **category 7** of 个人所得税法 §2 — `利息、股息、红利所得`
(interest, dividends, bonus income). The 2018 IIT Law amendment (last
amended 2018-08-31, effective 2019-01-01) renumbered the historical
9 categories into 9 again, with category 7 carrying the
"interest / dividends / bonus" bucket; category 8 is
`财产租赁所得` (property rental), and category 9 is the CGT bucket
`财产转让所得` (note 133).

| Income class | IIT statute | EIT statute | Default rate |
|---|---|---|---|
| **股息、红利 (dividends, listed A-share)** | 个税法 §2(7) + §3 + Caishui [2015] 101 | 企税法 §26(2) + Impl. Reg. §83 | 20 % IIT (graded), 25 % EIT (excluded if qualifying) |
| **股息、红利 (dividends, unlisted)** | 个税法 §2(7) + §3 | 企税法 §26(2) | 20 % IIT (full), 0 % inter-TRR EIT |
| **股息、红利 (dividends, Stock Connect H-share)** | 个税法 §2(7) + §3 + Caishui [2014] 81 / [2016] 127 | 企税法 §26(2) + Caishui [2014] 81 §4 | 20 % IIT WHT (no gradation), 0 % EIT if holding ≥ 12m |
| **股息、红利 (dividends, foreign-corp source)** | 个税法 §2(7) + §3 + §7 (FTC) | 企税法 §23-24 | 20 % IIT (+ FTC), 25 % EIT (+ FTC) |
| **利息 (savings-deposit interest)** | 个税法 §2(7) + Caishui [2008] 132 | n/a (corp interest is op income) | **0 %** IIT since 2008-10-09 |
| **利息 (government bond interest)** | 个税法 §2(7) + 国债条例 | 企税法 §26(1) | **0 %** IIT; 0 % EIT (国债 interest is on the §26(1) exempt list) |
| **利息 (corporate-bond / other interest)** | 个税法 §2(7) + §3 | n/a (op income) | 20 % IIT (no gradation) |
| **利息 (wealth-management product net distribution)** | 个税法 §2(7) — taxed at issuer level | n/a | Issuer remits; individual gets net (typically 0 % at investor level) |

Three structural features distinguish CN from the US/DE pattern most
substrate developers will have internalised:

1. **No "ordinary vs qualified dividend" cliff** — CN has one rate
   (20 %) and one reduction mechanism (the listed A-share holding-
   period gradation). There is no equivalent of the US 0/15/20 %
   qualified-dividend schedule.
2. **No aggregation election** — CN dividends and interest do NOT
   join the comprehensive-income (综合所得) base of categories 1-4 (per
   the 2018 IIT Law's category-grouping reform); they are taxed
   **per-receipt** (see §1.4) outside the progressive schedule.
   Contrast JP (note 151 §1.2's three-way election).
3. **Bank-deposit interest is 0 % since 2008** — a major divergence
   from every other jurisdiction in the active-l10n set. Don't model
   "savings-deposit interest" as taxable in the substrate; model it as
   exempt with a citation.

### 1.2 Listed A-share dividend — the Caishui [2015] 101 gradation

`财政部 国家税务总局 证监会关于上市公司股息红利差别化个人所得税
政策有关问题的通知` — Caishui [2015] No. 101, jointly issued by
MOF + SAT + CSRC on 2015-09-07, effective 2015-09-08, **superseded the
prior Caishui [2012] No. 85 differential regime by extending the
upper-bracket exemption permanently**.

For dividends from shares listed on Shanghai Stock Exchange (SSE),
Shenzhen Stock Exchange (SZSE) — and now the Beijing Stock Exchange
(BSE, added by Caishui [2021] No. 9 mirroring the [2015] 101 rule):

| Holding period at record date | IIT base | Effective rate |
|---|---|---|
| **≤ 1 month** | 100 % of dividend × 20 % | 20 % |
| **> 1 month and ≤ 1 year** | 50 % of dividend × 20 % | 10 % |
| **> 1 year** | 0 % (full exemption) | 0 % |

Two mechanics worth modelling carefully:

- **Holding-period measurement is FIFO at the China Securities
  Depository and Clearing Corp. (CSDC) account level**. The CSDC
  tracks acquisition dates per security per account; on the dividend
  record date, the dividend is allocated against the oldest lots
  first. If the holder sells within 1 year, the CSDC's records
  trigger a back-charge: the broker withholds at the higher rate at
  sale. So the *provider* sees a **net dividend** (already adjusted)
  + an optional **back-charge** event the consumer posts to the IIT
  liability account.

- **The differential is "deferred withholding"** — the broker /
  paying agent withholds **0 % at dividend record date** for listed
  A-share dividends (a 2015-101 administrative simplification);
  the actual tax (20 % or 10 % or 0 %) is collected at the **sale**
  of the underlying shares OR at year-end stale-position assessment.
  This pushes the recognition event off the dividend date — the
  consumer's GL records the gross dividend at receipt and the IIT
  liability only at the **sell-or-year-end** trigger. This is a
  substrate seam: the provider's `period` may close BEFORE the
  trigger fires, deferring the liability into the next period.
  The shipped `:transaction/state` lifecycle (`kernel-internal`) and
  the `:posting/posted-at` sealing pattern (ADR-007) carry this
  cleanly — the liability is *recognised* at the trigger event, not
  the dividend event.

### 1.3 Stock Connect H-share dividend — flat 20 %, no gradation

`财政部 国家税务总局 证监会关于沪港股票市场交易互联互通机制
试点有关税收政策的通知` — Caishui [2014] No. 81 (effective 2014-11-17,
covering Shanghai-HK Connect), extended by Caishui [2016] No. 127 for
Shenzhen-HK Connect, and reaffirmed by the December 2017 MOF/SAT/CSRC
joint extension notice.

For dividends from H-shares (or any HK-listed company) held by
**mainland resident individuals** through Stock Connect:

- **H-share company withholds 20 % via the China Securities Depository
  and Clearing Corp.** at distribution. NO holding-period gradation
  applies (the Caishui [2015] 101 reduction is **A-share only**, by
  the notice's plain text).
- Where the H-share company has already paid HK or other foreign WHT,
  the holder may apply for an offset against the 20 % CN tax by
  presenting valid foreign-tax-payment certificates to the competent
  tax authority. The article includes a **20 % no-double-tax cap**:
  if foreign WHT already at 20 % or more, no further CN tax is
  withheld; if below 20 %, the CSDC tops up to 20 %.
- **Caishui [2014] 81 §4** carves out **TRR-corporate** holders of
  H-shares via Connect: if held continuously **≥ 12 months**, the
  dividend is **exempt from EIT** (mirrors the §26(2) domestic
  inter-TRR exemption — note 1.5).

A **2024 reform proposal** (Bloomberg 2024-05-09) floated a waiver
of the Stock Connect dividend tax for mainland individuals. As of
2026-05 the proposal has not been enacted; the 20 % WHT remains in
force. The substrate should parameterise the rate per ADR-101 so the
reform, when it lands, is a single parameter-value insert.

### 1.4 利息 — interest income, the three-zone reality

The IIT Law's text says interest income is category-7, 20 % flat. The
substrate-relevant reality is more granular:

**Zone A — exempt (effective 0 %):**

- **Bank savings deposits** (储蓄存款): `财政部 国家税务总局关于
  储蓄存款利息所得有关个人所得税政策的通知` — Caishui [2008] No. 132,
  effective 2008-10-09 — exempts ALL post-2008-10-09 savings-deposit
  interest from IIT. (Historical: pre-1999-11-01 exempt; 1999-11-01
  to 2007-08-14 taxed at 20 %; 2007-08-15 to 2008-10-08 taxed at
  5 %; post-2008-10-09 exempt. The exemption remains in force as of
  2026-05.)
- **Government bonds (国债)** — exempt under both 个税法 §4(2) and the
  国债条例 (Government Bond Regulations); listed on the 企税法 §26(1)
  exempt list as well.
- **Local-government bonds + policy-bank financial bonds** — IIT
  exempt by analogy to government bonds (Caishui [2012] No. 5
  expanded the exemption). **Note**: VAT on these bond categories
  was REIMPOSED by MOF/STA Announcement [2025] No. 4 (effective
  2025-08-08, on newly issued bonds only — see §1.7); **IIT
  exemption is NOT affected** by the 2025 VAT reform — the substrate
  must distinguish the two layers.

**Zone B — flat 20 % IIT:**

- **Corporate bonds** (公司债 — non-financial-bond corporate debt
  securities) — 20 %, withheld by paying agent.
- **Private lending / non-bank deposit interest** — 20 %, self-
  declared.
- **Foreign-source bank interest received by resident individuals**
  — 20 % with FTC under §7.

**Zone C — wealth-management products (理财产品):**

Bank-issued wealth-management products (often packaged as trust
products under the WMP umbrella) typically distribute **net** —
the issuer remits taxes at the trust / issuer level; the individual
investor sees no withholding event and recognises no further IIT
on the WMP distribution. The substrate should NOT generate an IIT
component for WMP receipts unless the consumer flags the product as
"unwrapped" (i.e., a pass-through structure where the investor IS
the tax-recognising entity). See §1.6 for the partnership-fund
parallel.

### 1.5 Inter-TRR dividend exclusion — 企业所得税法 §26(2) + Impl. Reg. §83

The corporate side is uniquely permissive:

`中华人民共和国企业所得税法` Article 26 §2: "Tax-exempt income
includes: ... (2) Income from equity investment, such as dividends and
profit distribution, between qualified resident enterprises
(符合条件的居民企业之间的股息、红利等权益性投资收益)."

The Implementation Regulations Article 83 then defines "qualified":

- **Direct investment** (直接投资) — must be a **direct holding**,
  not a holding-through-fund or holding-through-intermediary
  partnership.
- **Excludes**: dividends from publicly-traded shares of a resident
  enterprise where the **holding period is < 12 months**.

So the exemption fires in two zones:

| Investment type | Holding period | EIT treatment |
|---|---|---|
| Unlisted equity, direct holding | any | **Exempt** |
| Listed A/B/H share, direct holding | **≥ 12 months** | **Exempt** |
| Listed A/B/H share, direct holding | **< 12 months** | **25 % standard EIT** |
| Stock Connect H-share | **≥ 12 months** | **Exempt** (Caishui [2014] 81 §4) |
| Stock Connect H-share | **< 12 months** | **25 % EIT** |
| Indirect (through partnership / fund) | any | **25 % EIT** at the corp on the allocation |

**Reduced-rate taxpayer interactions:** the 12-month listed exemption
applies regardless of taxpayer rate (15 % HNTE / 20 % SLPE / 25 %
standard). The exemption is **rate-substitution at the income-class
level** (excludes from base) — different from a tax-credit. The CN CIT
provider already sweeps `:base-deduct` adjustment-inputs; the
investment-income provider emits `:cit-base-deductions` for qualifying
dividends in the same pattern the CGT provider uses for special-
restructuring (note 133 §5.2).

### 1.6 Partnership and fund distributions

Per `财政部 国家税务总局关于合伙企业合伙人所得税问题的通知`
(Caishui [2008] 159) + Caishui [2019] 8 (the venture-capital
clarification):

- **Partnership** = transparent for IIT/EIT (allocation pass-through).
- **Individual partner's share of partnership-received dividend** —
  retains category-7 character: **flat 20 %, NO aggregation with the
  partner's business income** (which goes at 5-35 % brackets).
- **VC fund** (venture capital limited partnership): under Caishui
  [2019] 8, the GP elects between (a) single-investment-fund
  accounting (20 % flat on each fund's profit allocated to individual
  partners, treating each invested-position as a separate income
  event) OR (b) annual-aggregate accounting (5-35 % business income
  brackets on the fund's net allocated to individual partners).
- **Corporate partner's share of partnership-allocated dividend** —
  the §26(2) exemption is **NOT available** for corporate partners
  receiving partnership-allocated dividends, because the §26(2)
  language requires "direct investment between resident enterprises"
  (Caishui [2008] 159 §4). This is a notable substrate stress: the
  partnership veil is NOT pierced for the §26(2) exemption, even
  though it IS pierced for tax-transparency.

### 1.7 The 2025-08-08 VAT reimposition on government / financial bonds

MOF / STA Joint Announcement [2025] No. 4 (effective 2025-08-08):
re-introduced VAT on interest income from **newly issued** (post-
2025-08-08) government bonds, local government bonds, and financial
bonds. Pre-2025-08-08 bonds retain VAT exemption through maturity.

**Impact on investment-income IIT/EIT layer**: NONE at the
holder-side. The reform applies VAT at the **interest-earner**
(holder, when an FI) at 6 % for FIs / 3 % for asset-management
products. The IIT exemption for individual savings-deposit interest
and government-bond interest is **unaffected**.

**Why this note flags it**: the substrate's CN CIT provider may
already include a VAT pass-through component; the investment-income
provider should NOT duplicate. The 2025-08-08 reform is a CN VAT
provider concern (kontor.l10n-cn.vat.clj), not a category-7 IIT
concern.

### 1.8 Outbound dividend WHT — non-TRR enterprise + non-resident individual

`财税 [2008] No. 1` and Cai Shui [2008] 130 (the corporate WHT
implementing notice) + 企税法 §4 (reduced rate) + §27(5):

- **Non-resident enterprise**: 10 % WHT on dividends sourced from
  China-resident enterprises. Treaty reduction common (5 % under
  many treaties for substantial-holding shareholders ≥ 25 % equity,
  e.g. CN-NL, CN-DE, CN-FR, CN-HK).
- **Non-resident individual**: 20 % WHT under IIT Law on outbound
  dividends, treaty-reducible (often to 10 %).
- **Withholding-tax-deferral via reinvestment** (Caishui [2018] 102):
  for foreign investors who receive a dividend from a China TRR and
  **reinvest directly** into China within the eligible scope, the
  10 % WHT is **deferred** until the reinvestment is recovered (sale,
  liquidation, etc.). 2025 reform (Caishui [2025] PwC alert
  2025q3-jul-6) extended the deferral coverage and added a **10 %
  tax credit** for FY 2025-2028 reinvestment.

### 1.9 REITs — the post-2020 pass-through carve-out

`财政部 国家税务总局公告 [2022] 第 3 号` — MOF / STA Announcement
[2022] No. 3 (issued 2022-01-29, effective 2022-01-01): the pilot tax
policy for infrastructure REITs (基础设施 REITs / C-REITs).

Two-stage treatment:

**Setup stage** (project-company → REIT vehicle): the **special-
restructuring rules** of Caishui [2009] 59 apply (same regime as the
CGT special-restructuring deferral, note 133 §1.4). The original
equity holder's gain on contributing the project-company equity to
the REIT vehicle is fully tax-deferred.

**Operational stage** (distribution to REIT unit holders): the
substrate-relevant lane.

- **Individual unit holder** — distribution is **category-7
  dividend** at 20 %. No A-share holding-period gradation
  (Caishui [2015] 101 covers SSE/SZSE/BSE corporate dividends,
  not REIT distributions). The C-REIT vehicle withholds 20 % at
  distribution.
- **Corporate unit holder** — distribution is **ordinary income at
  25 %**. The §26(2) inter-TRR exemption does NOT apply to REIT
  distributions (per the 2022 Announcement §1) because the REIT
  vehicle is not a "resident enterprise" for §26(2) purposes — it
  is a vehicle subject to its own special regime.

The 2022 Announcement also defers **EIT on the REIT setup-stage gain
of the original equity holder for 12 months** after the REIT IPO. The
substrate carries this as a setup-time `:elective-regime` consistent
with the CGT provider's `:cn-special-restructuring` flag.

---

## §2. Worked examples

### Example A — Resident individual, listed A-share dividend at three holding-period bands

Ms. Liu, Beijing resident, holds three positions in JP-listed Toyota's
**SSE-listed comparable**: a 2,000-share position in CATL (300750.SZ)
acquired in three separate FIFO lots through her CSDC account. CATL
declares a 2026-04 cash dividend of **CNY 2.00 per share** (record
date 2026-04-15).

Lot-by-lot holding periods at record date:

| Lot | Acquisition date | Days held at 2026-04-15 | Shares | Gross dividend | IIT band |
|---|---|---|---|---|---|
| L1 | 2025-04-20 | **361 days** (> 12 m by 0 days? — see note) | 800 | CNY 1,600 | > 1 year → **0 % exempt** |
| L2 | 2026-01-10 | **95 days** (> 1 m, < 1 y) | 700 | CNY 1,400 | half → **10 % effective** |
| L3 | 2026-04-03 | **12 days** (≤ 1 month) | 500 | CNY 1,000 | full → **20 %** |

(Note on the 361-day case: Caishui [2015] 101 defines ">1 year" as
**strictly more than 365 days from acquisition to record date**, FIFO
per the CSDC's records. The 361-day L1 actually falls into the "1 month
< x ≤ 1 year" band → half-tax. Adjust the example to L1 acquired
2024-04-20 for the > 1 year case to fire — kept here as a substrate
warning: the boundary arithmetic must use the exact 365-day rule, not
calendar-year-rollover.)

Recompute with **L1 acquired 2024-04-20** (727 days held):

| Lot | Days held | Gross dividend | Taxable base | IIT |
|---|---|---|---|---|
| L1 | 727 (> 1 y) | CNY 1,600 | 0 | **CNY 0** |
| L2 | 95 (> 1 m) | CNY 1,400 | CNY 700 | CNY 700 × 20 % = **CNY 140** |
| L3 | 12 (≤ 1 m) | CNY 1,000 | CNY 1,000 | CNY 1,000 × 20 % = **CNY 200** |
| **Total** | | **CNY 4,000** | | **CNY 340** |

Effective overall rate: 340 / 4,000 = **8.5 %**.

**Withholding timing**: per §1.2 — the broker withholds 0 % at the
record date (2026-04-15). CATL distributes CNY 4,000 gross to Ms. Liu's
CSDC sub-account. The CNY 340 is collected when:

- Ms. Liu sells the L2 (within 1 year) — broker back-charges CNY 140 at
  sale; OR
- Ms. Liu sells the L3 (within 1 month or thereafter still within 1 y)
  — broker back-charges CNY 200; OR
- Year-end stale-position assessment if L2/L3 still held.

Substrate trace: the consumer posts the gross CNY 4,000 dividend at
2026-04-15 as a category-7 income event with companion attrs
`{:cn-investment-income/income-class :listed-a-share-dividend,
:cn-investment-income/holder-class :resident-individual,
:cn-investment-income/lot-acquisitions [{2024-04-20, 800-shares}
{2026-01-10, 700-shares} {2026-04-03, 500-shares}]}`. The
`cn-iit-investment-income-provider` sweeps category-7 events for the
period, applies the FIFO holding-period stratification, and produces
ONE component `:kind :income-tax :authority :cn-sat` with
`:gross-liability CNY 340 :liability CNY 340 :prepaid CNY 0`.

### Example B — Resident enterprise, mixed dividend + inter-TRR exemption + Stock Connect

Beijing Hightech Corp. (a Chinese TRR, 25 % standard EIT rate) holds:

1. **8 % equity in Domestic Unlisted Co. A** — receives CNY
   10,000,000 dividend in 2026-Q1.
2. **1.5 % equity in SSE-listed Co. B** acquired 2023-06 — receives
   CNY 600,000 dividend in 2026-Q1 (holding period at record date >
   12 months ✓).
3. **0.8 % equity in SSE-listed Co. C** acquired 2026-01 — receives
   CNY 200,000 dividend in 2026-Q3 (holding period at record date <
   12 months ✗).
4. **2 % equity in Stock Connect H-share Co. D** acquired 2024-09 —
   receives HKD 800,000 dividend (CNY equivalent at conversion
   date: CNY 730,000), holding period 18 months ✓.
5. **0.3 % equity in foreign-corp Co. E (incorporated in DE)** —
   receives EUR 50,000 dividend (CNY equivalent: CNY 380,000); DE
   WHT at treaty rate (5 %) = EUR 2,500 (CNY 19,000).

| # | Dividend | EIT inclusion? | Rationale |
|---|---|---|---|
| 1 | CNY 10,000,000 | **EXCLUDED** | §26(2) — unlisted, direct, ≥ 0d held |
| 2 | CNY 600,000 | **EXCLUDED** | §26(2) + Impl. Reg. §83 — listed, held > 12 m |
| 3 | CNY 200,000 | **INCLUDED at 25 %** | §83 carve-out: listed < 12 m |
| 4 | CNY 730,000 | **EXCLUDED** | Caishui [2014] 81 §4 — Connect H-share held ≥ 12 m |
| 5 | CNY 380,000 | **INCLUDED at 25 %**; FTC = CNY 19,000 | §23 — foreign source, FTC credit |

Substrate trace:

- Consumer posts each dividend as a category-7 income event with
  `:holder-class :resident-corporation` + `:income-class
  :listed-a-share-dividend / :unlisted-equity-dividend / :stock-
  connect-h-share-dividend / :foreign-corp-dividend` + acquisition-date
  (lot-level for the listed cases).
- `cn-eit-investment-income-provider` sweeps the period:
  - Events 1, 2, 4 → emit `{:cit-base-deductions [<dividend amount>]}`
    adjustments to the CIT provider (no separate component returned by
    the investment-income provider for excluded dividends — they
    simply REMOVE themselves from the EIT base via the inclusion-
    bypass).
  - Event 3 → emit `{:cit-base-additions [200_000]}` (it was already
    in GL as dividend income; the investment-income provider has
    nothing to do here — the CIT provider sweeps the GL).
  - Actually, simpler: the investment-income provider emits a SINGLE
    `:cit-base-deductions` for events 1, 2, 4 (the qualifying
    exemptions) and lets the CIT provider naturally include the
    rest. Same pattern as the CGT special-restructuring deferral
    (note 133 §5.2).
  - Event 5 → emit `{:cit-foreign-tax-credit [{:country :de :amount
    CNY 19_000}]}` for the CIT provider to apply per §23.

Total tax savings from §26(2) + §4: **CNY 11,330,000 × 25 % = CNY
2,832,500** vs the naive "all dividends taxable" baseline. The §26(2)
exemption is the single largest substrate-relevant tax incident in
the corporate investment-income lane.

---

## §3. Substrate fit — provision-by-provision

| CN provision | Substrate carrier | Adequacy |
|---|---|---|
| IIT category 7 — 20 % flat on dividends + interest | Existing `:flat 0.20M` schedule from `kontor.tax-schedule`; provider's `:base` = sum of taxable category-7 events | **CLEAN** — identical to the CGT provider's category-9 base computation. |
| Listed A-share dividend gradation (Caishui [2015] 101) | Schedule algebra needs a "duration-gradation" stratification on the income event's lot-level holding periods → companion provider routine computes `:taxable-base = full × duration-factor` where `duration-factor ∈ {0, 0.5, 1.0}`. | **CLEAN** — analogous to note 133 §3's `:cn-listed-a-share` exemption gating; the gradation is an extension of the same date-arithmetic. **No new schedule kind needed**. |
| Stock Connect H-share dividend 20 % (Caishui [2014] 81) | Same flat 20 % schedule; companion attr `:cn-investment-income/income-class :stock-connect-h-share-dividend` routes to the flat rate without gradation. | **CLEAN**. |
| Bank-deposit interest 0 % (Caishui [2008] 132) | Provider's exemption check on `:income-class :bank-savings-interest` returns NO COMPONENT. | **CLEAN** — same pattern as the CGT provider's listed-A-share gain exemption. |
| Government bond interest 0 % | Same exemption-check on `:income-class :government-bond-interest`. | **CLEAN**. |
| Foreign dividend with FTC (IIT §7 / EIT §23-24) | Provider folds foreign-WHT prepaid into the period's `:adjustments :credit :refundable? false` per ADR-100's adjustment layer. | **CLEAN** — identical to the JP-CGT-provider's foreign-tax-credit handling (note 115 §3.3). |
| EIT inter-TRR dividend exclusion (§26(2) + Impl. Reg. §83) | Provider emits `:cit-base-deductions` adjustment input to the CIT provider for qualifying dividends. | **CLEAN** — identical mechanism to CGT's special-restructuring deferral (note 133 §5.2). |
| EIT 12-month listed holding test | Provider date-arithmetic on acquisition date vs record date; same FIFO-per-CSDC lot accounting. | **CLEAN** — reuses the IIT-side lot-tracker. |
| Outbound dividend 10 % WHT to non-TRR enterprise | Provider routes events with `:holder-class :non-resident-corporation` through a `:flat 0.10M` schedule with treaty-rate parameter; companion provider emits the WHT component into the EIT-WHT return. | **CLEAN** — parameter-driven via ADR-101. |
| Withholding-tax-deferral on reinvestment (Caishui [2018] 102) | `:elective-regime :cn-reinvestment-deferral` flag on the dividend event; provider defers liability via a setup-time `:cit-base-deductions` adjustment. | **CLEAN** — parallels the CGT special-restructuring lockup pattern (note 133 §3.2). |
| C-REIT operational distribution | New `:income-class :c-reit-distribution` enum value; routes to flat 20 % (individual) or 25 % (corporate); §26(2) does NOT apply for corporate holders. | **CLEAN** — single enum extension. |
| WMP distribution (issuer-level) | Provider's exemption check returns NO COMPONENT for `:income-class :wmp-distribution` UNLESS the consumer flags `:wmp-unwrapped? true`. | **CLEAN**. |
| Partnership dividend allocation (corporate partner) | Per §1.6 — the §26(2) exemption is NOT available. The substrate carries this by ROUTING partnership-allocated dividends as `:income-class :partnership-allocated-dividend` and excluding them from the §26(2) eligibility check inside the provider. | **CLEAN — but a footgun**. The substrate's `kontor.entity/family` walk could naively traverse through a partnership; the provider must STOP at the partnership boundary. Add a docstring warning. |
| Non-resident individual outbound dividend 20 % WHT | Provider routes events with `:holder-class :non-resident-individual` through `:flat 0.20M` × treaty-cap. | **CLEAN**. |

### 3.1 The lot-level FIFO accounting — substrate seam

The Caishui [2015] 101 gradation needs lot-level acquisition tracking
to assign each share's dividend to a holding-period band. The shipped
`:disposal` schema (note 133 §3 + ADR-102) carries lot-tracking on the
**disposal side** (`:basis-method :fifo / :average / :specific-id`,
`:lot-id`, `:acquired-on`). The investment-income provider needs the
**income side** to also carry lot data — specifically, for each
dividend event, the **vector of holding-position lots at record date**.

Three encoding options (in order of preference):

1. **Provider reads from a lot-ledger** — if the consumer maintains a
   `:lot` schema for held positions (e.g. via the costing-provider
   substrate `kontor.costing-provider` per ADR-029), the provider
   reads the lots-at-record-date directly. No new schema. **Preferred**
   — leverages existing infrastructure.

2. **Per-event lot-snapshot attr** — `:cn-investment-income/lot-snapshot
   [{:acquired-on <date> :shares <int>} ...]`. Denormalised
   per-event; consumer responsible for FIFO assignment. Less
   substrate-perfect but easier when no lot-ledger is maintained.

3. **Provider computes from disposal history + opening-balance** — the
   provider walks the disposal substrate backward from record-date to
   reconstruct lot composition. Requires the consumer to seed an
   opening-balance lot list at the start of substrate adoption.

For Phase C2: **support both (1) and (2)**, with (1) as the default
and (2) as the fallback when no `:lot` data exists in the consumer's
GL. This mirrors the CGT provider's optional `:basis-method` field —
some consumers feed it, others rely on default FIFO from disposal
events.

### 3.2 The deferred-recognition seam — record date vs sale-or-year-end trigger

Per §1.2, the IIT liability on a < 1 year listed A-share dividend
crystallises **at sale OR at year-end stale-position assessment** —
NOT at the record date. This is a substrate timing issue:

- The consumer posts gross dividend income at the record date.
- The provider sweeps category-7 events per period — but a period
  closing 2026-Q1 includes the 2026-Q1 record-date dividends,
  even though the IIT crystallisation may not happen until later.

**Three substrate options:**

(a) **Recognise the IIT liability at the record date with current
holding period** — assume the holder still holds at year-end. Overpay
if the holder later sells within 1 year (back-charge would have raised
the rate). Simple but inaccurate.

(b) **Recognise at the record date with PROVISIONAL period rate AND
schedule a year-end-or-sale reconciliation event** — uses the
`kontor.schedule` recurring-posting mechanism (ADR-032) to create a
year-end reconciliation entry per outstanding < 1 year position.
Accurate but heavyweight.

(c) **Recognise at the trigger event** — wait for the sale or
year-end to post the IIT liability. Aligns with PRC withholding
practice. The IIT provider emits a component only for the *triggered*
events in the period, not the record-date events. **Recommended** —
matches PRC withholding practice; consumer's tax-payable balance
reflects accrued liability via the gross-dividend posting.

The CN-IIT-CGT provider already handles a similar "transferee-
withholding" lag (note 133 §2.1). The investment-income provider
extends the pattern: emit at trigger, not at event.

### 3.3 Coordination with `cn-iit-cgt-provider` — shared 上市 A-share logic

The Caishui [2015] 101 dividend gradation and the Caishui [1998] 61
listed A-share gain exemption are **structurally related**: both reward
duration on the same asset class (listed A-share) with the same
holder class (resident individual) via different statutory hooks.

**Shared substrate components**:

- `:asset-class :cn-listed-a-share` (already in `iit-asset-classes`,
  `cgt_provider.clj:65`) — the asset-class enum is shared.
- Lot-level acquisition-date tracking — both providers consume the
  same lot ledger.
- The 358-day vs > 365-day boundary arithmetic — extract a shared
  helper `cn-holding-period-band` returning `:le-1m | :1m-1y | :gt-1y`
  for the dividend provider and `:gt-1y` (CGT exempt) for the CGT
  provider.

**Where they DIFFER**:

- The CGT provider exempts ALL holding-period bands on listed
  A-share gains (the 1998 carve-out is unconditional on duration).
  The dividend provider grades by duration.
- The CGT provider's exemption is `:exemption-claimed
  :cn-caishui-1998-61` (a boolean claim); the dividend provider's
  gradation is a `:duration-factor` (numeric).

A shared `kontor.l10n-cn.shared` namespace can host:

```clojure
;; Sketch — not actual code.
(defn holding-period-band [acquired-on as-of]
  (let [days (days-between acquired-on as-of)]
    (cond
      (<= days 31)  :le-1m
      (<= days 365) :1m-1y
      :else         :gt-1y)))

(defn dividend-duration-factor [band]
  (case band
    :le-1m  1.0M    ; 100 % of dividend
    :1m-1y  0.5M    ; 50 % of dividend (half-tax)
    :gt-1y  0.0M))  ; 0 % of dividend (full exemption)
```

The CGT provider's existing `days-between` helper
(`cgt_provider.clj:100`) is the natural ancestor; promote it to the
shared namespace.

---

## §4. Data gaps — the concrete extension list

**Zero kernel-schema changes**. Two companion-namespace attrs and
one new `:income-class` enum on a new
`kontor.l10n-cn.investment-income` namespace:

### 4.1 `:cn-investment-income/holder-class`

Drives the four-way taxpayer branch — IIT individual vs IIT corporate
vs non-resident individual vs non-resident corporation.

```
:cn-investment-income/holder-class  :keyword   ; closed enum (§4.4)
```

Cost: 1 companion attr. Used by both
`cn-iit-investment-income-provider` and
`cn-eit-investment-income-provider` to route.

### 4.2 `:cn-investment-income/income-class`

Drives the income-class branch — listed A-share dividend vs Stock
Connect H-share vs unlisted equity vs bank interest vs etc.

```
:cn-investment-income/income-class  :keyword   ; closed enum (§4.5)
```

Cost: 1 companion attr. The most-frequently-set field per income
event.

### 4.3 `:cn-investment-income/shareholding-percentage`

Used **only** for §26(2) corporate-partnership and the 12-month-listed
test (whose threshold is duration, not percentage; percentage is for
forward extensibility — e.g. if a future PRC reform adds a substantial-
holding cliff like JP's 3 % 大口株主 rule). Denormalised; the
consumer's choice of authoritative source (GL position lookup vs
external register) is consumer-side.

```
:cn-investment-income/shareholding-percentage  :bigdec   ; 0..1
```

Cost: 1 companion attr. Optional.

### 4.4 Closed `:holder-class` enum

```clojure
#{:resident-individual
  :resident-corporation
  :non-resident-individual
  :non-resident-corporation
  :partnership-vehicle}       ; flag for the §26(2) NO-EXEMPTION
                              ; carve-out per §1.6
```

5 values. Aligned with the CGT provider's `tax-residencies` enum
(`cgt_provider.clj:76-81`) plus the `:partnership-vehicle` warning
case.

### 4.5 Closed `:income-class` enum

```clojure
#{:listed-a-share-dividend         ; SSE/SZSE/BSE A-share, graded
  :listed-b-share-dividend         ; foreign-currency B-share
  :stock-connect-h-share-dividend  ; HK via Connect — flat 20 %
  :unlisted-equity-dividend        ; private corp dividend
  :foreign-corp-dividend           ; non-resident corp dividend
  :bank-savings-interest           ; 0 % since Caishui [2008] 132
  :government-bond-interest        ; 0 % under §26(1) / 国债条例
  :financial-bond-interest         ; 0 % at IIT layer
  :corporate-bond-interest         ; 20 %
  :other-interest                  ; private lending — 20 %
  :wmp-distribution                ; wealth-management — taxed at issuer
  :c-reit-distribution             ; C-REITs — 20 % individual, 25 % corp
  :partnership-allocated-dividend  ; allocation from partnership/VC fund
  :foreign-interest}               ; foreign-source interest — 20 % + FTC
```

14 values — closes the universe of category-7 income events the
provider needs to discriminate. Note that A-share gain (CGT category-9)
is in the SHIPPED `iit-asset-classes` set, not here — the income-class
enum is for INCOME-TAX category-7 events.

### 4.6 Optional `:cn-investment-income/lot-snapshot`

Per §3.1 — the per-event lot vector for consumers without a
position-lot ledger.

```
:cn-investment-income/lot-snapshot  [:ref ...]    ; vector of lot refs
                                                  ; each ref:
                                                  ;   :lot/acquired-on  :inst
                                                  ;   :lot/shares       :long
```

Cost: 1 companion attr + 1 child-entity type (~2 attrs). Skip if the
consumer feeds the provider a lot ledger via the existing costing-
provider substrate (option (1) in §3.1).

**Total**: 3 mandatory companion attrs (`:holder-class`,
`:income-class`, `:shareholding-percentage`) + 2 optional companion
attrs for the lot-snapshot fallback. Zero kernel-schema changes.

---

## §5. `cn-investment-income-provider` sketch — TWO sibling providers

Per the CGT-provider pattern (note 133 §5), CN investment income
splits along TWO sibling providers (no separate provider for LAT-like
levies — investment income has no analog of LAT).

### 5.1 `cn-iit-investment-income-provider` — feeds the individual IIT return

`PeriodTaxProvider` of `:kind :income-tax` (not `:capital-gains-tax`
— this is dividend/interest, not gain), `:authority :cn-sat`.

```clojure
;; Conceptual sketch — no code in this note.
(defn cn-iit-investment-income-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :cn-iit-investment-income)
    (period-tax-facts [_ {:keys [conn entity period inputs] :as ctx}]
      (let [;; Sweep category-7 income events for the period
            events (income/events-in-period
                    conn period entity :cn-investment-income)
            ;; Drop NISA-equivalents (CN has no NISA but C-REIT and
            ;; bank-savings-interest are exemption-by-class).
            taxable (->> events
                         (filter #(= :resident-individual
                                     (:cn-investment-income/holder-class %)))
                         (remove exempt-by-class?))
            ;; Stratify each event by income-class and duration
            ;; (Caishui [2015] 101 gradation for listed A-share)
            stratified (group-by classify-event taxable)
            ;; Compute taxable base per stratum
            bases (map-vals sum-stratum-base stratified)
            ;; Apply 20 % flat schedule
            liability (->> bases vals
                           (map #(money/* % 0.20M))
                           (reduce money/+))
            prepaid (or (:cn-iit-investment-income/prepaid inputs)
                        (money/zero :CNY))]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :cn-sat}
          :functional-commodity :CNY
          :components
          [{:kind :income-tax :authority :cn-sat
            :base (reduce money/+ (vals bases))
            :schedule (ts/flat 0.20M)
            :gross-liability liability
            :liability (money/- liability prepaid)
            :prepaid prepaid
            :line-items
            [{:line :cn-category-7-listed-a-share-graded
              :value (get bases :listed-a-share-graded money/zero)}
             {:line :cn-category-7-stock-connect-h-share
              :value (get bases :stock-connect-h-share-dividend money/zero)}
             {:line :cn-category-7-unlisted-equity
              :value (get bases :unlisted-equity-dividend money/zero)}
             {:line :cn-category-7-corporate-bond-interest
              :value (get bases :corporate-bond-interest money/zero)}
             ;; ... etc.
             ]
            :adjustments (foreign-tax-credit-adjustments events)}]})))))
```

Notes on the sketch:

- The `classify-event` helper applies the §1.2 duration-gradation for
  listed A-shares: returns `:listed-a-share-graded` with the post-
  gradation base (= `lot-shares × dividend-per-share ×
  duration-factor`).
- The `exempt-by-class?` predicate returns true for
  `:bank-savings-interest`, `:government-bond-interest`,
  `:financial-bond-interest`, and `:wmp-distribution`-without-unwrap.
- The `foreign-tax-credit-adjustments` helper folds foreign-WHT
  prepaids into the ADR-100 adjustment layer with `:op :credit :sign
  :negative :refundable? false`.

### 5.2 `cn-eit-investment-income-provider` — feeds the EIT return

`PeriodTaxProvider` of `:kind :corporate-income-tax`, `:authority
:cn-sat`. As with `cn-eit-cgt-provider`, the bulk of corporate
dividend handling is **excluded-from-base** rather than added — the
provider emits `:cit-base-deductions` for qualifying dividends and
lets the CIT provider naturally include the rest.

```clojure
;; Conceptual sketch.
(defn cn-eit-investment-income-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :cn-eit-investment-income)
    (period-tax-facts [_ {:keys [conn entity period inputs] :as ctx}]
      (let [events (income/events-in-period
                    conn period entity :cn-investment-income)
            corp-events (filter #(= :resident-corporation
                                    (:cn-investment-income/holder-class %))
                                events)
            ;; Split into excluded (§26(2)-qualifying) vs included
            {excluded true included false}
            (group-by #(qualifies-for-26-2? % ctx) corp-events)
            ;; Foreign-source dividends — included + FTC
            foreign (filter #(= :foreign-corp-dividend
                                (:cn-investment-income/income-class %))
                            included)
            ;; WHT on outbound dividends — emit a WHT component
            non-resident-events (filter #(#{:non-resident-corporation
                                           :non-resident-individual}
                                          (:cn-investment-income/holder-class %))
                                        events)
            wht-components (non-resident-wht-components
                            non-resident-events ctx)]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :cn-sat}
          :functional-commodity :CNY
          :components
          (cond-> []
            (seq excluded)
            (conj {:kind :corporate-income-tax :authority :cn-sat
                   :cit-base-deductions (sum-events excluded)
                   :line-items
                   [{:line :cn-eit-26-2-inter-trr-dividend-exemption
                     :value (sum-events excluded)}]})

            (seq foreign)
            (conj {:kind :corporate-income-tax :authority :cn-sat
                   :cit-foreign-tax-credit (foreign-tax-credit foreign ctx)
                   :line-items
                   [{:line :cn-eit-23-24-foreign-tax-credit
                     :value (foreign-tax-credit foreign ctx)}]})

            (seq wht-components) (into wht-components))})))))
```

The `qualifies-for-26-2?` predicate checks:

- `:income-class` is in `#{:unlisted-equity-dividend
  :listed-a-share-dividend :listed-b-share-dividend
  :stock-connect-h-share-dividend}` (excludes
  `:c-reit-distribution`, `:partnership-allocated-dividend`,
  `:foreign-corp-dividend`).
- For listed classes: `(>= holding-days 365)`.
- `:holder-class :resident-corporation`.
- NOT `:partnership-vehicle` in the holding-chain.

### 5.3 Summary

| Provider | Components per return | Substrate stress |
|---|---|---|
| `cn-iit-investment-income-provider` | 1 (multi-line) | none — uses existing `:flat 0.20M` + adjustment layer; only the lot-stratification routine is new |
| `cn-eit-investment-income-provider` | 0-3 (exclusion + FTC + WHT) | none — same `:cit-base-deductions` pattern as note 133 §5.2 |

Total: **2 providers, 0 kernel schema changes, 3 mandatory companion
attrs (+ 2 optional) across 1 new companion namespace
(`l10n-cn.investment-income`), 0 new substrate primitives**. Within
the conservative posture of notes 107 / 115 / 133 / 151.

---

## §6. Cross-cutting design notes

### 6.1 The "income event vs sale event" framing

CN's listed-A-share dividend is a unique substrate stressor because
the **dividend event** (record date) and the **tax-recognition event**
(sale or year-end stale assessment) are time-separated. Substrate
options were analysed in §3.2; the recommended path is to
**recognise at the trigger event**, not the record date. The consumer
posts gross dividend income at the record date (the GL needs the
income); the provider emits the IIT component only for events that
have *triggered* in the period.

The trigger-event abstraction is **substrate-relevant** because three
other CN providers face an analogous pattern:

- The CGT provider's "transferee withholding" (note 133 §2.1) —
  similar lag between sale event and withholding event.
- The VAT provider's "deferred VAT recognition" on installment sales.
- The payroll-IIT provider's `年终奖` special-method (ADR-085) — the
  bonus is paid in December, but the IIT is computed against the
  separate-method schedule that depends on the full-year aggregation.

A shared `:trigger-event` companion attr might be worth promoting
post-Phase C2 if the pattern recurs in three or more places. Note for
future consolidation; not in scope for Phase C2.

### 6.2 The 12-month listed-holding test — shared across IIT + EIT

The same 12-month threshold gates:

- The IIT `:gt-1y` band of Caishui [2015] 101 (dividend exemption for
  individuals).
- The EIT §26(2) + Impl. Reg. §83 listed-share exemption for
  corporations.

Different statutes, same arithmetic. The shared helper §3.3 sketches
serves both providers.

**Substrate footgun**: the IIT test is `> 365 days` (strict); the EIT
test is `>= 12 months` (calendar-month rolling). These can differ by a
day or two at year boundaries. Encode both as separate parameters
under ADR-101 with citations, not as a hardcoded "1 year" constant.

### 6.3 The §26(2) NOT applying to partnership-allocated dividends

Per §1.6 — the §26(2) exemption is **NOT** available for corporate
partners receiving partnership-allocated dividends. This is a
substrate-relevant **anti-pattern**: the corporate partner's GL would
naturally show a "dividend received" line, and the corporate partner's
EIT return would naively claim the §26(2) exemption. The provider's
`qualifies-for-26-2?` predicate must **explicitly reject** events
where the holding chain passes through a partnership.

Two ways to encode the holding-chain:

(a) **Companion attr `:cn-investment-income/holding-chain
[:participation :partnership :issuer]`** — a vector of held entity
types in order from the holder to the issuer. Predicate checks for
`:partnership` in the chain.

(b) **Walk `kontor.entity/family`** — if the chain is reflected in
the entity graph (parent → partnership → issuer), the predicate walks
the graph. Requires the consumer to maintain the entity graph
faithfully.

For Phase C2: **support (a) as the primary, with (b) as a future
enhancement**. The consumer's per-dividend tag is the most reliable
data source; the graph walk is fragile because the partnership may
not always be modelled as an `:entity`.

### 6.4 The "no aggregation" rule — divergence from JP / US

Most peer jurisdictions (US, DE, JP, FR) include investment income
in some comprehensive base or offer an aggregation election (JP's
総合課税, US's "qualified dividend" inclusion in ordinary income at a
preferential rate). CN does NOT — category 7 is **per-receipt**, not
aggregated.

**Substrate implication**: the IIT provider's `:base` for category 7
is the SUM of per-receipt taxable amounts in the period; there is no
interaction with the comprehensive-income (综合所得) base of
categories 1-4. The shipped `cn-iit-provider` (the comprehensive-
income one) does NOT need to know about category-7 events. They are
**separate components** of the SAME `cn-iit` return — and the
substrate's `TaxReturnFacts` shape carries multiple components per
return cleanly (see ADR-099).

### 6.5 The Stock Connect 2024 reform — parameterise!

The pending Stock Connect dividend tax reform (Bloomberg 2024-05-09 —
not yet enacted as of 2026-05) would align Stock Connect H-share
dividends with the A-share gradation. The substrate must
**parameterise the Stock Connect rate** under ADR-101 so the reform,
when it lands, is a single parameter-value insert with effective-from
date. Do not hardcode `0.20M` in the provider; reference the
parameter `CN.IIT.investment-income.stock-connect-rate`.

### 6.6 The bank-savings-interest exemption is NOT a sunset

Caishui [2008] 132's bank-deposit interest exemption is structured
as a **permanent exemption** (effective 2008-10-09 onward), not a
sunset-extended preference. The substrate should NOT include a
`:sunset-date` parameter for it. Contrast Stock Connect (Caishui
[2014] 81 — sunset-extended via the joint-notice mechanism every 3
years; the most recent extension covers through 2027-12-31).

### 6.7 The 2025 VAT reimposition is NOT in scope

Per §1.7 — the August 2025 VAT reform on government / financial bond
interest is a **VAT-side** concern, not an investment-income IIT/EIT
concern. The substrate already has a separate
`kontor.l10n-cn.vat.clj` namespace (verified at
`modules/l10n-cn/src/kontor/l10n_cn/vat.clj` — 267 lines). The
investment-income provider should NOT touch VAT.

---

## §7. Sources

CN statutes and regulations (chinatax.gov.cn / fgk.chinatax.gov.cn —
public):

- 《中华人民共和国个人所得税法》 (IIT Law, last amended 2018-08-31,
  effective 2019-01-01) — Article 2 (categories), Article 3
  (rates), Article 6 (income computation), Article 7 (foreign tax
  credit), Article 12 (withholding). Verified text at
  https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html
- 《中华人民共和国企业所得税法》 (EIT Law, 2007 with 2017/2018
  amendments) — Article 4 (rates), Article 23 (foreign tax credit),
  Article 24 (indirect FTC), Article 26 (tax-exempt income — esp.
  §26(2) inter-TRR dividend), Article 27 (reduced tax).
- 《中华人民共和国企业所得税法实施条例》 (EIT Implementation
  Regulations, 国务院令 No. 512) — Article 83 (12-month listed
  holding test for §26(2) exemption).
- Caishui [1998] No. 61 — Temporary IIT exemption on listed-share
  transfer gains (mentioned for completeness; this is the CGT carve-
  out, not the dividend one — see note 133).
- Caishui [2008] No. 130 — Non-resident-enterprise WHT regulations
  (10 % on outbound dividends to non-TRR enterprises).
- **Caishui [2008] No. 132** — Bank savings deposit interest IIT
  exemption (effective 2008-10-09, still in force 2026).
  https://shanghai.chinatax.gov.cn/zcfw/zcfgk/grsds/200810/t288953.html
- Caishui [2012] No. 5 — Local-government bond interest IIT exemption.
- Caishui [2012] No. 85 — Original dividend differential policy
  (superseded by [2015] 101).
- **Caishui [2014] No. 81** — Shanghai-HK Stock Connect tax policies:
  20 % IIT WHT on H-share dividends for mainland individuals; EIT
  exemption for ≥ 12-month TRR-corp Connect holdings.
- Caishui [2016] No. 127 — Shenzhen-HK Stock Connect tax policies
  (mirrors [2014] 81 for SZSE-HK route).
- **Caishui [2015] No. 101** — Listed A-share dividend differential
  IIT (the gradation ≤ 1m / 1m-1y / > 1y → 20 % / 10 % / 0 %).
  Effective 2015-09-08, jointly issued by MOF / SAT / CSRC on
  2015-09-07.
- Caishui [2018] No. 102 — Withholding-tax deferral on non-TRR
  reinvestment of dividends.
- Caishui [2008] No. 159 — Partnership enterprise income tax (transparency).
- Caishui [2019] No. 8 — Venture capital fund partnership tax
  treatment (20 % flat election vs 5-35 % bracket election).
- MOF/STA Announcement [2022] No. 3 — Infrastructure REITs pilot tax
  policy (special-restructuring deferral at setup + 20 % / 25 %
  individual / corporate at distribution stage).
- MOF/STA Announcement [2025] No. 4 — VAT reimposition on government
  / financial bond interest (effective 2025-08-08; does NOT affect
  IIT exemption).
- 国务院令 [2014] No. — Stock Connect joint policy MOF/SAT/CSRC
  extension notices (most recent: 2024 joint notice extending Stock
  Connect IIT exemption through 2027-12-31 — same notice that covers
  the IIT CGT exemption per note 133 §1.1).
- SAT Announcement [2017] No. 37 — Non-resident enterprise WHT
  mechanics.

Practitioner references (verified):

- PwC Worldwide Tax Summaries — China — Individual Income
  Determination + Other Tax Credits and Incentives, retrieved
  2025-12.
  https://taxsummaries.pwc.com/peoples-republic-of-china/individual/income-determination
  https://taxsummaries.pwc.com/peoples-republic-of-china/individual/other-tax-credits-and-incentives
- PwC Worldwide Tax Summaries — China — Corporate Withholding
  Taxes, retrieved 2025-12.
  https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/withholding-taxes
- KPMG China Tax Alert No. 5 (2025-08) — VAT Reimposition on
  Government Bonds, Local Government Bonds, and Financial Bonds.
  https://kpmg.com/cn/en/insights/2025/08/china-tax-alert-5.html
- MSA Advisory — China Dividend Tax: Rates, Withholding &
  Repatriation Guide, updated 2025-11-13.
  https://msadvisory.com/taxes-on-dividends-china/
- China Briefing — FAQ: Dividends for Non-Resident Enterprises in China.
  https://www.china-briefing.com/news/qa-key-points-regarding-dividends-derived-by-non-resident-enterprises-in-china/
- China Briefing — Withholding Tax in China.
  https://www.china-briefing.com/doing-business-guide/china/taxation-and-accounting/withholding-tax-in-china
- China Law and Practice — Announcement on Tax Policies for Pilot
  REITs in Infrastructure Sector (2022-02-25).
- Caixin Global — "Overseas Investors in Chinese Bonds Get Four More
  Years of Tax Breaks" (2021-10-28).
  https://www.caixinglobal.com/2021-10-28/overseas-investors-in-chinese-bonds-get-four-more-years-of-tax-breaks-101793026.html
- Bloomberg — "China Mulls Dividend Tax Waiver on Hong Kong Stocks
  Connect" (2024-05-09).
- Hawksford — Key considerations for dividend repatriation from China.
- gov.cn English — "China extends tax exemption for overseas
  investors in Chinese bond market" (2026-01-15) — covering 2026-01-01
  to 2027-12-31.
  https://english.www.gov.cn/news/202601/15/content_WS6968dee9c6d00ca5f9a08979.html
- Law.asia — "MOF, STA clarify tax issues for infrastructure REITs."
  https://law.asia/mof-sta-reits/

Internal cross-references:

- Note 133 — CN CGT substrate fit (companion regime; sibling provider
  pattern; shared `:cn-listed-a-share` asset-class + lot-tracking).
- Note 123 — CN CIT substrate fit (the EIT side; the inter-TRR
  exemption from this note becomes a `:cit-base-deductions`
  adjustment to that provider).
- Note 151 — JP investment-income fit (sibling Phase C2 note; the
  duration-gradation pattern recurs there as 配当控除 + 大口株主 cliff).
- ADR-099 — `PeriodTaxProvider` (the substrate this provider implements).
- ADR-100 — adjustment-layer ordering (where the FTC + base-deduction
  patterns live).
- ADR-101 — statute-as-data substrate (where parameters + provisions
  live for the rates + sunset dates + thresholds in this note).
- ADR-102 — disposal substrate (the income-side analog is what this
  note's §3.1 lot-snapshot extends).

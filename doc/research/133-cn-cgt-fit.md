---
date: 2026-05-24
title: 133 — CN capital-gains tax — substrate fit against the shipped `:disposal` schema
audience: maintainer + the Phase 3 `cn-cgt-provider` implementer
status: research-before for the Phase 3 CN CGT provider(s); no code; data-gap list at §4
---

# 133 — CN capital-gains tax: `:disposal` schema fit assessment

This note answers one question: does the shipped `:disposal` schema
(`modules/disposal/src/kontor/disposal/schema.clj`) carry enough data to
drive a faithful CN capital-gains-tax provider across the **four**
shapes the PRC tax system uses to tax realised wealth — IIT
财产转让所得 (property-transfer income, category 9), EIT inclusion of
gains in ordinary corporate income, the Land Appreciation Tax (土地增值
税) regime, and SAT Announcement [2015] No. 7 indirect transfers — or
do we need to extend it?

Bottom line: **the schema needs ZERO additive kernel fields**. The
shipped fields (`:asset-class`, `:elective-regime` cardinality-many,
`:exemption-claimed` cardinality-many, `:loss-bucket`,
`:ownership-fraction`, `:residence?`, `:acquired-on`,
`:rollover-into-asset`) carry every CN distinction this note surfaces.
What CN _does_ surface is **two architectural questions** the substrate
must answer: (a) is the Land Appreciation Tax a *separate provider*
(it is not income tax — it is a stand-alone levy on a specific gain
class) or an `:elective-regime` flag inside the CGT provider; (b) the
"deemed-rate" 1–3% election for residential real estate is a **base
substitution**, not an adjustment — it replaces (proceeds − basis)
with (proceeds × deemed-rate). §3.2 names a fifth substrate enrichment:
the CN companion ships a *base-method* selector on the disposal so the
provider knows which calculation to apply. §5 sketches *three*
sibling providers — `cn-iit-cgt-provider`, `cn-eit-cgt-provider`,
`cn-lat-provider`.

---

## §1. The CN CGT regime — four statutory shapes

CN does not have ONE capital-gains tax. It has four overlapping
provisions, each with a distinct base, rate, taxpayer class, and
loss-offset rule:

### 1.1 Individual side — IIT 财产转让所得 (《个人所得税法》第二条第九项)

Article 2 of the 《中华人民共和国个人所得税法》 (Individual Income Tax
Law, last amended 2018, effective 2019-01-01) enumerates **nine
categories** of taxable income; **category 9** is 财产转让所得
(income from property transfers — "income from securities, shares,
real estate, partnership interests and other property"). Article 3
fixes the rate at a **flat 20%** (适用比例税率，税率为百分之二十).

The Implementation Regulations of the IIT Law (《个人所得税法实施
条例》, 国务院令 No. 707, 2018-12-18) further define property-transfer
income as **proceeds − original cost − reasonable expenses** (Article
16).

The **two major carve-outs** the substrate must model:

- **Listed A-/B-share gains for mainland individuals — temporarily
  exempt since 1998 (Caishui [1998] No. 61)**, periodically extended
  by Caishui [2005] No. 35 and Caishui [2009] No. 167; the exemption
  remains in force as of 2026-01. The exemption covers **A-shares,
  B-shares, and shares listed on the Beijing Stock Exchange** when
  sold by domestic individuals; gains on Hong Kong H-shares acquired
  via Stock Connect by mainland individuals are similarly exempt under
  Caishui [2014] No. 81 and successor extensions (current Stock Connect
  IIT exemption extended through 2027-12-31 per the 2024 MOF/SAT/CSRC
  joint notice). **Dividend WHT remains 20%** — only capital gains
  are exempt.

- **Real estate — twin-track**. Personal residential property sales
  are taxed at the **20% flat rate on net gain** under category 9.
  When the seller cannot substantiate basis with original purchase
  documentation, the tax authority may **assess at 1–3% of gross
  proceeds** (the "**核定征收**" / deemed-assessment method, per
  Guoshuifa [2006] No. 108, July 18 2006). The taxpayer effectively
  has an *implicit election*: produce documents → 20% × net gain;
  fail to → 1–3% × gross. (Provincial rates vary inside the 1–3% band —
  Beijing/Shanghai default 1%, lower-tier cities up to 3%.)

- **"满五唯一" exemption** — Guoshuifa [2006] No. 108 §1 carves out
  personal income tax on the sale of a residence that is BOTH (i)
  **self-occupied for ≥ 5 years** (家庭唯一生活用房 — measured from
  the earlier of purchase contract date, payment receipt date, and
  property certificate date) AND (ii) **the family's sole residence**
  (the family — taxpayer + spouse — owns only this one residence in
  the same province/autonomous region/directly-administered
  municipality). Both prongs are required; "满五" alone is
  insufficient.

### 1.2 Individual side — unlisted equity transfers (股权转让)

Disposals of **non-listed corporate equity** by individuals — including
LLC interests, founder shares pre-IPO, employee-incentive shares
post-vesting — fall squarely inside category 9 with **no exemption**.
SAT Announcement [2014] No. 67 prescribes the valuation discipline and
anti-avoidance rules (below-market transfers can be re-priced by the
tax authority using net-asset value).

- **Rate**: 20% on net gain (proceeds − basis − reasonable expenses).
- **Withholding**: the **transferee** is the statutory withholding
  agent (SAT Announcement [2014] No. 67, Article 5). Cash settlement
  → withhold at sale; share-for-share → defer per Caishui [2009] No.
  59 / [2014] No. 109 special-restructuring rules (below).
- **Loss treatment**: capital losses on unlisted equity offset **only**
  category-9 gains in the same calendar year; **no carryforward**
  under the current IIT Law (this is a major divergence from the US/DE
  carryforward norm).

### 1.3 Individual side — other property

- **Movables — gold, art, jewellery, vintage cars, collectibles**:
  category 9 at 20% net gain. No 1-year private-speculation exemption
  (cf. DE § 23 EStG); CN has no equivalent of US §1031 like-kind
  exchange for personal-use movables.
- **Crypto-assets**: legal grey zone — PRC has banned crypto exchanges
  since 2017 (PBOC + 6-ministry circular). Where individuals
  nevertheless realise gains, the 2024 SAT guidance has begun treating
  them as 财产转让所得 at 20%; no formal Caishui has been issued, but
  Beijing tax bureau enforcement actions in 2024-2025 against
  ex-exchange-platform traders applied 20% to net gain (see Yahoo
  Finance "Global Gains, Local Taxes" 2025-09 reporting on the
  enforcement push for **overseas-listed stock gains by mainland
  residents** — the underlying treatment, though not yet codified,
  uses category 9).
- **Partnership interests** (合伙企业份额): SAT Announcement [2014]
  No. 67 §4 treats individual partners' transfers of partnership
  interests as category 9 at 20%.

### 1.4 Corporate side — EIT inclusion (《企业所得税法》第六条第三项)

Article 6 of the 《中华人民共和国企业所得税法》 (Enterprise Income
Tax Law, 2007 with 2017/2018 amendments) enumerates taxable income;
**item 3** is 转让财产收入 (income from property transfers). Article
4 fixes the standard rate at **25%**.

Unlike the individual regime, **there is no exemption for listed
shares**: a resident enterprise selling A-shares it holds on its
balance sheet recognises ordinary income at 25%. Reduced rates apply
to specific taxpayer classes (15% high-and-new-tech enterprises, 5–10%
small-low-profit enterprises) — the **rate substitution is at the
taxpayer level, not the asset-class level**, which means the CN CGT
provider does *not* need a separate "qualified taxpayer" branch — the
CIT provider already gates on `:tax-unit :high-new-tech?` /
`:tax-unit :slpe?`.

**Non-resident enterprises** selling equity in a Chinese resident
enterprise (or other "Chinese taxable property" per SAT Announcement
[2015] No. 7 — below) pay **10% withholding tax** on the gross gain
(EIT Law Article 4 reduced rate for non-resident WHT income; SAT
Announcement [2017] No. 37 prescribes the withholding mechanics).
Treaty relief may reduce this.

**Three corporate-side carve-outs**:

1. **Special-restructuring rollover** (Caishui [2009] No. 59 +
   Caishui [2014] No. 109 + SAT Announcement [2015] No. 48):
   share-for-share or asset-for-share reorganisations meeting **five
   criteria** qualify for tax-deferred treatment (the rollover
   relief).

2. **100%-controlled internal transfers** (Caishui [2014] No. 109 §3):
   transfers between enterprises in a 100%-control chain at **net
   book value** are tax-deferred without meeting the five general
   criteria — a relaxed track for intra-group reorganisations.

3. **Non-cash-asset investment 5-year spread** (Caishui [2014] No.
   116): when a resident enterprise contributes non-cash assets to
   another resident enterprise in exchange for equity, the gain
   recognised on the contribution may be **allocated evenly over 5
   taxable years**. An election; partial deferral, not full rollover.

### 1.5 The Special Restructuring five criteria (Caishui [2009] No. 59)

For an equity acquisition / asset acquisition / merger / demerger to
qualify for tax-deferred treatment (the "special restructuring"
track), **all five** of the following must hold:

| # | Criterion | Threshold |
|---|---|---|
| 1 | Reasonable commercial purpose, not for tax avoidance/reduction/exemption/deferral | qualitative |
| 2 | Acquired equity (or assets) as a share of target's total equity (or total assets) | **≥ 50%** (Circular 109 lowered from 75% in Circular 59) |
| 3 | Original business activity continues for **12 consecutive months** after the transaction | continuity |
| 4 | Equity consideration as a share of total consideration paid | **≥ 85%** (cash and other non-equity ≤ 15%) |
| 5 | Original principal shareholder does not transfer the received equity for **12 consecutive months** | continuity |

If all five hold, the gain on the transferred equity/assets is
**deferred** — the transferee inherits the transferor's tax basis (the
"carry-over basis" rule), and tax is recognised only when the asset
eventually leaves the chain. If any criterion fails, the standard
25% applies on the gain at transaction date.

### 1.6 Land Appreciation Tax (土地增值税, LAT)

LAT is a **separate tax**, not an income tax — it is a stand-alone
levy on the *value uplift* of state-owned land use rights and the
buildings thereon when transferred for consideration. Governed by
the 《中华人民共和国土地增值税暂行条例》 (Provisional Regulations on
LAT, 国务院令 No. 138, 1993-12-13) and Implementing Rules (Caifazi
[1995] No. 6).

The taxable amount is **value-added** = transfer income − deductible
items (acquisition cost of land use rights, development costs +
expenses with a 20% deduction-cap multiplier, taxes paid on transfer,
**plus a 20% extra deduction for ordinary residential housing
developers**).

**Four-tier progressive schedule**:

| Value-add as % of deductibles | Marginal rate |
|---|---|
| ≤ 50% | **30%** |
| 50% – 100% | **40%** |
| 100% – 200% | **50%** |
| > 200% | **60%** |

Developers pay provisionally at **1–3% of gross sales revenue** during
the project, then **settle** at the four-tier rate after the project
completes (清算).

**Two carve-outs**:

- **Ordinary residential housing built by developers** is *exempt*
  when the value-add ≤ 20% of deductibles (Provisional Regs Article
  8 §1).
- **Individual sales of personal residences** are **exempt from LAT**
  (Provisional Regs Article 8 §2 — superseded for the 1999 onward
  period by Caishui [2008] No. 137, which exempts ALL individual
  residential sales from LAT regardless of holding period or sole-
  residence status). **This is broader than the IIT exemption** — IIT
  needs "满五唯一"; LAT exempts all personal residential sales.

### 1.7 SAT Announcement [2015] No. 7 — indirect transfers

Where a **non-resident enterprise** transfers equity in an **offshore
holding company** whose primary value is Chinese taxable property
(equity in Chinese resident enterprises, Chinese real estate, or
Chinese establishment-or-place property), the transaction may be
**re-characterised as a direct transfer** of the underlying Chinese
property if it **lacks a reasonable commercial purpose** (the GAAR
trigger). Re-characterised gains are taxed under EIT Law Article 4 at
10% WHT.

The classic substantive trigger is the **VIE-structure unwind** or
the **cross-border M&A** where a Cayman-incorporated parent of a
Chinese OpCo is sold. SAT Announcement [2015] No. 7 supersedes Guoshuihan
[2009] No. 698, providing a safe-harbour list (public-market
secondary-market trades of the same overseas-listed entity; reorganisations
exempt by treaty) and a **detailed-disclosure procedure** when the
re-characterisation risk is uncertain.

This is **substrate-relevant** because: (i) the `:disposal/subject` may
be an offshore HoldCo equity even when the *taxed* property is the
Chinese OpCo; (ii) the provider must read the company graph (the
offshore HoldCo's `:entity/family` parent chain) to identify the
Chinese taxable-property nexus.

### 1.8 Loss-offset compartments

The CN regime is unusually **restrictive** on loss carry:

- **Individuals — category 9**: capital losses on unlisted equity /
  real estate / movables offset only category-9 gains in the **same
  calendar year**; **no carryforward**; no cross-category offset (a
  business loss in category 5 不能 offset a category-9 gain).
- **Listed-share losses for individuals**: irrelevant — gains are
  exempt, so losses are not recognised either.
- **Enterprises**: capital losses on equity / asset disposals flow
  into ordinary EIT taxable income; net operating losses **carry
  forward up to 5 years** (10 years for high-and-new-tech enterprises
  per Caishui [2018] No. 76; 8 years for film industry per Caishui
  [2020] No. 25). The 5-year carry is **shared across all income
  classes** — no separate capital-gains bucket on the EIT side.
- **LAT**: no loss concept — LAT is gain-only. Project losses are
  irrelevant to LAT (they reduce EIT taxable income via the GL).

---

## §2. Worked examples

### 2.1 Individual sells unlisted equity in a Chinese tech start-up

Founder Wang acquired 5,000,000 RMB nominal of equity in a Beijing
LLC in 2019-03 for **CNY 5,000,000**. In 2026-04 he sells the stake
for **CNY 25,000,000** to a strategic acquirer.

- Realised gain = CNY 20,000,000 (less attributable broker fee
  CNY 200,000 → CNY 19,800,000 taxable).
- Classification: 财产转让所得, category 9.
- IIT owed: **CNY 19,800,000 × 20% = CNY 3,960,000**.
- **Withholding**: the acquirer (transferee) withholds and remits
  the CNY 3,960,000 to the local tax bureau (SAT Announcement [2014]
  No. 67 Article 5); Wang receives net CNY 21,040,000.

Substrate trace: one `:disposal` with `:kind :sale`,
`:subject-kind :participation`, `:asset-class :cn-unlisted-equity`,
`:proceeds CNY 25M`, `:basis CNY 5M`, `:loss-bucket :cn-category-9`,
`:ownership-fraction 1.0M`. The CN-IIT-CGT provider sweeps category-9
disposals for the period, applies 20% × net, and records the
transferee-withheld amount in `:inputs :cn-iit-prepaid CNY 3.96M` so
the resulting liability nets to zero at the period close.

### 2.2 Individual sells primary residence held 7 years (满五唯一)

Mrs. Zhang bought her family's only Beijing apartment in 2018-05 for
**CNY 4,000,000**. She sells it 2026-06 for **CNY 7,500,000**.

- Holding period: 8 years 1 month from purchase contract date →
  satisfies 满五 (≥ 5 years).
- Sole-residence test: the family owns only this apartment in
  Beijing → satisfies 唯一.
- **Both conditions hold** → exempt from IIT under Guoshuifa [2006]
  No. 108 §1.
- LAT: exempt under Caishui [2008] No. 137 (personal residential
  sales).
- **Total CGT-equivalent tax: CNY 0.** (Stamp duty 0.05% × proceeds
  + deed tax for buyer + value-added tax considerations apply outside
  the CGT layer.)

Substrate trace: one `:disposal` with `:kind :sale`,
`:subject-kind :real-estate-private`, `:asset-class :cn-residential`,
`:residence? true`, `:acquired-on 2018-05-15`,
`:disposed-on 2026-06-20`, `:proceeds CNY 7.5M`,
`:exemption-claimed #{:cn-manwuweiyi :cn-lat-personal-residence}`,
`:holding-period :long`. The CN-IIT-CGT provider sees the
`:cn-manwuweiyi` exemption tag, verifies the conditions
(`:residence? true` + `:disposed - :acquired ≥ 5y` + the holder's
`(entity-other-residences-in-province conn)` returns empty), and
emits a zero-tax line item; the CN-LAT provider sees the
`:cn-lat-personal-residence` tag and short-circuits.

### 2.3 Resident enterprise share-for-share acquisition (special restructuring)

GMP-PRC, a 100%-owned subsidiary of a US listco, acquires 60% of
RuralTech-PRC (a Chinese unlisted LLC) in 2026-03. Consideration:
**90% GMP equity + 10% cash**. RuralTech's selling shareholders
held the equity at a book basis of **CNY 200M**, fair value
**CNY 1.2B**.

- Criterion 1 (commercial purpose): satisfied (industrial
  consolidation rationale documented).
- Criterion 2 (≥ 50% of target equity transferred): **60% ≥ 50%** ✓.
- Criterion 3 (12-month business continuity): RuralTech's product
  lines maintained ✓.
- Criterion 4 (≥ 85% equity consideration): **90% ≥ 85%** ✓.
- Criterion 5 (transferor 12-month lockup): committed in deal docs ✓.

**Result**: gain on the equity-paid portion (CNY 1.2B × 60% × 90% −
CNY 200M × 60% × 90% = **CNY 540M**) is **deferred** — GMP-PRC
inherits the CNY 108M (= 200M × 60% × 90%) carry-over basis; the
cash-paid portion (CNY 1.2B × 60% × 10% − CNY 200M × 60% × 10% =
CNY 60M of gain) is **currently taxable** at 25% → **EIT owed
CNY 15M** on the cash slice; CNY 540M deferred.

Substrate trace: ONE `:disposal` with `:kind :sale`,
`:asset-class :cn-unlisted-equity`, `:proceeds CNY 720M`,
`:basis CNY 120M`, `:elective-regime #{:cn-special-restructuring}`,
`:rollover-into-asset <ref to GMP equity received>`,
`:rollover-amount CNY 540M`, `:rollover-deadline 2027-03-01`
(12-month lockup). The CN-EIT-CGT provider verifies the five
criteria from companion data (the disposal carries the rollover
election; the consumer attests the qualitative criteria via
`:audit-doc`), recognises only the cash-slice gain in the current
period, and emits a `:cit-base-additions [60_000_000M]`
adjustment-input to the CIT provider.

---

## §3. `:disposal` schema fit — provision-by-provision

| CN provision | Schema field that carries it | Adequacy |
|---|---|---|
| IIT category 9, listed-share exemption | `:asset-class :cn-listed-a-share / :cn-listed-h-share-via-connect` + `:exemption-claimed :cn-caishui-1998-61` | **CLEAN** — the provider classifies on `:asset-class`; the exemption claim is a recorded fact for audit. |
| IIT unlisted equity 20% | `:asset-class :cn-unlisted-equity` + `:subject-kind :participation` | **CLEAN** — the provider's `:flat 0.20M` schedule fires on category-9 disposals not exempt. |
| IIT real estate — net-gain vs deemed-rate election | `:asset-class :cn-residential / :cn-non-residential` + **NEW** `:base-method :net-gain / :deemed-gross` | **PARTIAL GAP — see §3.2 + §4.1** — the deemed-rate path *substitutes* the base; the schema's `:proceeds`/`:basis` pair carries net-gain inputs; deemed-rate needs the provider to compute `(× proceeds rate)` and ignore basis. The cleanest carrier is a companion-namespace `:base-method` selector — *not* a kernel field. |
| 满五唯一 exemption | `:residence? true` + `:acquired-on` + `:exemption-claimed :cn-manwuweiyi` + provider reads `(entity-other-residences-in-province conn)` | **CLEAN** — the provider's verification routine is companion code; the disposal carries the claim. |
| EIT property-transfer 25% | gains flow to GL via `:realizing-tx`; EIT provider sweeps GL | **CLEAN** — corporate CGT is *ordinary income on the EIT return*, exactly the JP corporate pattern (note 115 §1.3). The CN-EIT-CGT provider's role is narrow: handle the *exceptions* (special restructuring deferral, non-cash 5-year spread, indirect-transfer reclassification). |
| 10% non-resident WHT on equity transfer | `:subject-form :non-resident-corp` + provider routes to 10% schedule | **CLEAN** — the form-of-holder discriminator already in the schema. |
| Special restructuring (Caishui [2009] 59) — five criteria | `:elective-regime :cn-special-restructuring` + `:rollover-into-asset / -amount / -deadline` + `:audit-doc` for qualitative criteria | **CLEAN** — the five criteria split into substrate-verifiable (criteria 2 + 4: percentages on `:ownership-fraction` and a new payment-mix attr) and audit-doc-only (criteria 1, 3, 5: commercial purpose, 12-month business continuity, 12-month lockup). For the verifiable two, see §4.2. |
| 100%-controlled intra-group transfer | `:elective-regime :cn-intra-group-100pct` + provider reads `kontor.entity/family` for 100%-control verification | **CLEAN** — the existing `kontor.entity/family` (ADR-031) carries the ownership graph. |
| Non-cash-asset 5-year spread (Caishui [2014] 116) | `:elective-regime :cn-five-year-spread` | **CLEAN** — the election; the provider creates 5 schedule entries on the consumer's behalf (a deferred-recognition pattern shared with US §453 installment). |
| LAT progressive 30-60% | separate provider (`cn-lat-provider`); reads `:asset-class :cn-residential / :cn-commercial-real-estate` + `:subject-form :developer / :other` | **CLEAN** — LAT is a separate provider, *not* an `:elective-regime`. See §5.3. |
| LAT individual-residence exemption | `:exemption-claimed :cn-lat-personal-residence` | **CLEAN**. |
| SAT [2015] 7 indirect transfer | `:subject-kind :participation` + `:subject-form :non-resident-corp` + companion attr `:cn-indirect-transfer-target` (ref to underlying Chinese OpCo) | **PARTIAL GAP — see §4.3** — the *re-characterisation* needs the substrate to know which Chinese OpCo's value drives the offshore equity. A companion-namespace attr suffices; no kernel growth. |
| Loss carry — category 9 individual (no carryforward) | `:loss-bucket :cn-category-9` + provider zero-pads negative period totals | **CLEAN** — provider-side discipline. The `:inputs :capital-loss-carryforward` slot is *not used* for CN individuals (the law says no carry); the provider simply does not write a carry-out entry. |
| Loss carry — enterprise (5-year shared) | gains/losses are ordinary income; EIT provider's `:inputs :nol-carryforward` handles it | **CLEAN** — no CGT-specific bucket on the EIT side. |

### 3.2 The 1–3% deemed-rate election — base substitution

Guoshuifa [2006] No. 108 lets the tax authority assess **1–3% of
gross proceeds** when basis is not substantiated. This is **not** an
adjustment to the net-gain base — it **replaces** the base entirely:
`taxable = proceeds × deemed-rate` instead of `taxable = max(0,
proceeds − basis − expenses)`. The deemed rate is provincial; the
substrate cannot hard-code "1%" or "3%".

Two clean encodings:

- **Companion-namespace `:base-method` selector** —
  `{:cn-iit/base-method :net-gain | :deemed-gross}` + `:cn-iit/deemed-rate
  <BigDecimal 0.01..0.03>`. Provider branches: `:net-gain` → standard;
  `:deemed-gross` → `(* proceeds rate)`. **Recommended** — keeps the
  kernel disposal schema clean.

- **`:elective-regime :cn-deemed-rate-1pct / -2pct / -3pct`** — fewer
  attrs but uglier (three enum values for a continuous parameter).
  Rejected.

The deemed-rate path is *not* an `:exemption-claimed` because there
**is** tax owed; it is a `:base-method` because the *computation
method* changes.

---

## §4. Data gaps — the concrete extension list

**Zero kernel-schema changes**. Three companion-namespace attrs on the
`kontor.l10n-cn.disposal` side (a new namespace introduced by the CN
companion module):

### 4.1 `:cn-iit/base-method` + `:cn-iit/deemed-rate`

Per §3.2 — the real-estate net-gain-vs-deemed-rate selector.

```
:cn-iit/base-method  :keyword   ; :net-gain | :deemed-gross
:cn-iit/deemed-rate  :bigdec    ; 0.01..0.03, populated iff :deemed-gross
```

Cost: 2 companion attrs. Used by `cn-iit-cgt-provider` to branch the
base calculation.

### 4.2 `:cn-restructuring/payment-mix` + `:cn-restructuring/equity-share`

Caishui [2009] 59 criterion 4 (≥ 85% equity consideration) is a
substrate-verifiable percentage. The schema's `:proceeds` is a single
Money; restructuring consideration is multi-component (equity +
cash + assumed liabilities + non-equity securities). Encode as:

```
:cn-restructuring/payment-mix   :ref    ; ref to a child entity
:cn-restructuring/equity-share  :bigdec ; 0..1, derived
```

The child entity carries the consideration breakdown
(equity-amount + cash-amount + other-amount, each Money pairs).
`:equity-share` is the derived percentage the provider gates the
five-criteria test on. Cost: 2 companion attrs + 1 child entity
type (~5 attrs).

Alternative: skip the child entity, store only `:equity-share` as a
denormalised BigDecimal. The five-criteria audit doc carries the
breakdown for human verification. **Recommended** — denormalisation
matches `:holding-period` denorm precedent (note 115 §3.1).

### 4.3 `:cn-indirect-transfer/target` + `:cn-indirect-transfer/chinese-property-share`

SAT Announcement [2015] No. 7 needs the substrate to identify the
Chinese taxable-property nexus underlying the disposed offshore equity.

```
:cn-indirect-transfer/target                  :ref    ; ref to the Chinese OpCo (kernel :entity)
:cn-indirect-transfer/chinese-property-share  :bigdec ; 0..1 of the offshore HoldCo's value attributable to Chinese property
```

Cost: 2 companion attrs. The substrate uses these to (i) route the
disposal to the SAT [2015] 7 re-characterisation branch in
`cn-eit-cgt-provider`, (ii) carry the disclosure-required denominator
for the GAAR safe-harbour test.

---

## §5. `cn-cgt-provider` sketch — THREE providers

CN CGT splits along three tax axes that do not share a return form:

### 5.1 `cn-iit-cgt-provider` — feeds the individual IIT return

`PeriodTaxProvider` of `:kind :capital-gains-tax`,
`:authority :cn-sat`. Single component, but with per-`:asset-class`
line items (listed-A-exempt, listed-Connect-exempt, unlisted-equity,
residential, non-residential, movable, crypto). Each line either
applies the 20% schedule or short-circuits via
`:exemption-claimed`. The 满五唯一 verification runs as a
companion routine the provider calls into.

```clojure
;; Conceptual — no code in this note.
(defn cn-iit-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :cn-iit-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals (disposal/disposals-in-period conn period entity)
            net (->> disposals
                     (remove (every-pred :disposal/exemption-claimed
                                          #(seq (clojure.set/intersection
                                                 (:disposal/exemption-claimed %)
                                                 #{:cn-caishui-1998-61
                                                   :cn-stock-connect
                                                   :cn-manwuweiyi}))))
                     (group-by :cn-iit/base-method)
                     (map-vals sum-base-with-method))
            taxable-net-gain    (get net :net-gain (money/zero :CNY))
            taxable-deemed-gross (get net :deemed-gross (money/zero :CNY))
            gross-liability     (+ (* 0.20M taxable-net-gain)
                                   taxable-deemed-gross)  ; rate already baked
            prepaid             (or (:cn-iit-prepaid inputs) (money/zero :CNY))]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :cn-sat}
          :functional-commodity :CNY
          :components
          [{:kind :capital-gains-tax :authority :cn-sat
            :base (+ taxable-net-gain (/ taxable-deemed-gross 0.20M))
            :schedule (ts/flat 0.20M)
            :gross-liability gross-liability
            :liability (- gross-liability prepaid)
            :prepaid prepaid
            :line-items
            [{:line :cn-category-9-net-gain    :value taxable-net-gain}
             {:line :cn-category-9-deemed-gross :value taxable-deemed-gross}]
            :jurisdiction-specific-codes
            {:withheld-by-transferee prepaid}}]})))))
```

### 5.2 `cn-eit-cgt-provider` — feeds the EIT return (only for exceptions)

For the *vast majority* of corporate CGT, this provider returns
**nothing** — gains land in the GL as ordinary income and the existing
CN-CIT provider sweeps them up at 25%. The CGT provider exists to
handle four exception families:

1. **Special-restructuring deferral** — when
   `:elective-regime #{:cn-special-restructuring}`, the gain on the
   equity-paid slice is *removed* from EIT taxable income via a
   `:cit-base-deductions` adjustment input, and the carry-over basis
   is recorded for the future-disposal trail.
2. **100%-controlled intra-group** — similar deferral.
3. **5-year spread** — emit a 5-year schedule of `:cit-base-additions`
   (1/5 of the gain each year) instead of the full gain in year 1.
4. **SAT [2015] 7 indirect transfers** — for offshore disposals
   tagged `:cn-indirect-transfer`, re-characterise at 10% WHT,
   compute the Chinese-property share, and emit a WHT line.

### 5.3 `cn-lat-provider` — Land Appreciation Tax (separate provider)

LAT is **not** part of IIT or EIT — it is a stand-alone tax with its
own progressive schedule, its own taxpayer class (anyone transferring
state-owned land use rights + buildings), and its own filing form.

**Design call**: `cn-lat-provider` is a sibling `PeriodTaxProvider`
of `:kind :other-tax` (a new enum value reserved for stand-alone
levies that don't fit `:capital-gains-tax` / `:income-tax` / `:vat`).
Reading the existing `period-tax-kinds` enum
(`src/kontor/period_tax_provider.clj:44-61`):

```
(def period-tax-kinds
  #{:income-tax :corporate-income-tax :capital-gains-tax
    :vat :payroll-tax :property-tax :wealth-tax :other})
```

LAT fits `:other` cleanly — `:property-tax` would mislead (LAT is
event-driven on transfer, not periodic on ownership). The provider
emits one component per LAT-eligible disposal in the period;
components carry the four-tier schedule applied as a
`:schedule (ts/bracket [...])` from the existing schedule algebra
(note 102 §6.1's `bracket` constructor is sufficient).

The substrate handles LAT cleanly:

```clojure
;; LAT brackets — from Provisional Regs Article 7.
;; Bracket boundaries are RATIOS of value-add to deductibles.
(defn cn-lat-schedule [value-add deductibles]
  (ts/bracket
   {:base (/ value-add deductibles)   ; the ratio is what gets ranked
    :brackets
    [{:up-to 0.50M  :rate 0.30M}
     {:up-to 1.00M  :rate 0.40M}
     {:up-to 2.00M  :rate 0.50M}
     {:up-to nil    :rate 0.60M}]
    :on value-add}))                    ; the rate is applied to value-add
```

The bracket constructor (`tax_schedule.clj:bracket`) already handles
the ratio-to-bracket-then-apply-to-base mechanic — same pattern the
CA CIT provider uses for the Schedule-5 allocation share (note 111
§4 Finding 2).

**LAT-only data gaps** (companion namespace):

```
:cn-lat/value-add-amount       :bigdec  ; numerator
:cn-lat/value-add-commodity    :ref
:cn-lat/deductibles-amount     :bigdec  ; denominator (cost of land + dev + taxes + 20% extra for ordinary-residential)
:cn-lat/deductibles-commodity  :ref
:cn-lat/developer?             :boolean ; gates the 20% extra-deduction
:cn-lat/ordinary-residential?  :boolean ; gates the 20%-cap exemption
```

Cost: 4 companion attrs + 2 booleans. None kernel.

### 5.4 Summary

| Provider | Components per return | Substrate stress |
|---|---|---|
| `cn-iit-cgt-provider` | 1 (multi-line) | none |
| `cn-eit-cgt-provider` | 0–N (exceptions only) | none — the adjustment-input pattern (`:cit-base-additions / -deductions`) is established (note 113 §5.1) |
| `cn-lat-provider` | N per LAT-eligible disposal | uses existing `bracket` algebra; needs `:period-tax-kinds :other` (already in enum) |

Total: **3 providers, 0 kernel schema changes, 8 companion attrs
across 2 companion namespaces (`l10n-cn.disposal` + `l10n-cn.lat`),
0 new substrate primitives**. Within the conservative posture of
notes 107 + 115.

---

## §6. Cross-cutting design notes

### 6.1 The "LAT vs CGT" framing

LAT is structurally a transfer tax, not an income tax — but it *is*
imposed on the realised gain on a real-estate transfer. From the
taxpayer's perspective it competes for cashflow with IIT/EIT and is
often the larger bill. The substrate-clean framing:

- **CGT providers compute income-tax liabilities** (IIT category 9,
  EIT property-transfer income).
- **LAT is a sibling provider** computing a separate liability on
  the same disposal event.
- **The disposal `:realizing-tx` posts BOTH** — the GL records the
  gain once, then routes the IIT/EIT portion to one tax-payable
  account and the LAT portion to another.

This is the same pattern the substrate uses for **VAT and income tax
on the same sale invoice** — two providers reading the same event,
posting to different liability accounts. No new primitive.

### 6.2 The "no carryforward" individual rule

The IIT Law's no-carryforward rule for category-9 losses is
unusual — most jurisdictions (US, DE, UK, JP) carry capital losses
forward indefinitely or for a fixed window. The substrate handles
this by **not writing** a carry-out entry for the CN individual
provider; the `:inputs :capital-loss-carryforward` slot is omitted.
A negative period total simply rounds to zero tax.

This is **provider-side discipline**, not a substrate gap. Document
in the `cn-iit-cgt-provider` docstring with citation to IIT Law
Article 6 (loss-handling is item-by-item per income category, with
no inter-period carry for category 9).

### 6.3 The Stock Connect dynamic

Caishui [2014] No. 81 carved out Stock Connect mainland-individual
gains; subsequent extensions (Caishui [2016] No. 36, [2019] No. 51,
[2022] No. 32, [2024] joint MOF/SAT/CSRC notice) have extended the
exemption through 2027-12-31. The substrate carries the date via
`:disposal/disposed-on`; the `:exemption-claimed :cn-stock-connect`
tag is **conditional on `:disposed-on` falling inside an active
exemption window**. The provider's verification reads a small
parameter table (extension windows × tax) — `:parameter` per
ADR-101 is the right home.

---

## §7. Sources

CN statutes and regulations (chinatax.gov.cn — public):

- 《中华人民共和国个人所得税法》 (IIT Law, last amended 2018-08-31,
  effective 2019-01-01):
  https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html
- 《中华人民共和国企业所得税法》 (EIT Law, 2007 with 2017/2018
  amendments).
- 《中华人民共和国土地增值税暂行条例》 (LAT Provisional Regulations,
  国务院令 No. 138, 1993-12-13) +
  《土地增值税暂行条例实施细则》 (Implementing Rules, Caifazi [1995]
  No. 6).
- Caishui [1998] No. 61 — temporary IIT exemption on listed-share
  transfers.
- Caishui [2008] No. 137 — LAT exemption on individual residential
  sales.
- Caishui [2009] No. 59 — Special restructuring CIT treatment (five
  criteria).
- Caishui [2014] No. 81 — Stock Connect IIT exemption for mainland
  individuals.
- Caishui [2014] No. 109 — Lowered the special-restructuring asset/
  equity threshold from 75% to 50%; introduced the 100%-control
  intra-group track.
- Caishui [2014] No. 116 — Non-cash-asset 5-year spread election.
- Guoshuifa [2006] No. 108 — Individual residential property
  transfer IIT: 20% net gain vs 1-3% deemed-gross; 满五唯一
  exemption.
  https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html
- SAT Announcement [2014] No. 67 — Individual equity transfer IIT
  administration; transferee withholding.
- SAT Announcement [2015] No. 7 — Non-resident enterprise indirect
  transfers (supersedes Guoshuihan [2009] No. 698).
- SAT Announcement [2015] No. 48 — Procedural rules for
  special-restructuring filing.
- SAT Announcement [2017] No. 37 — Non-resident WHT mechanics.

Practitioner commentary:

- FDIChina, "Essential 12 Facts About China Capital Gains Tax Rules
  (2025 Playbook)": https://fdichina.com/blog/china-capital-gains-tax-rules-2025/
- PwC Worldwide Tax Summaries — China Individual / Corporate Income
  Determination: https://taxsummaries.pwc.com/peoples-republic-of-china/individual/income-determination
- China Briefing, "Tax Liabilities for Equity Transfer in China":
  https://www.china-briefing.com/news/tax-liabilities-for-equity-transfer-in-china-an-introduction/
- WilmerHale, "New Tax Rules Regarding M&A Transactions in China"
  (2009 on Caishui [2009] 59):
  https://www.wilmerhale.com/en/insights/publications/new-tax-rules-regarding-manda-transactions-in-china-june-10-2009
- Forvis Mazars, "Fine-Tuning of Rules on Non-resident Indirect
  Transfer by SAT" (2015 on Announcement [2015] 7).
- China Tax Insights, "A New Milestone for Taxation on Indirect
  Asset Transfer by Non-resident Enterprises".
- Mazars HK, "Easing of Tax Treatment in Corporate Restructuring
  Activities in China" (2015 on Caishui [2014] 109).
- KPMG, "Taxation of cross-border mergers and acquisitions: China"
  (2016).
- 辽宁省税务局, 满五唯一 explainer (2023):
  https://liaoning.chinatax.gov.cn/art/2023/2/6/art_525_96687.html
- V&T 律师事务所 originals on 满五唯一 IIT exemption:
  https://www.vtlaw.cn/news/1945.html
- LSEG, "Guide to Chinese Share Classes" (2025-04 — for the
  A/B/H/Stock-Connect taxonomy this note relies on).

kontor substrate cited (file:line):

- `modules/disposal/src/kontor/disposal/schema.clj:62-309` — the
  shipped `:disposal` schema this note assesses.
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor for the
  20% IIT and 10% WHT components.
- `src/kontor/tax_schedule.clj` — `bracket` constructor for the LAT
  four-tier schedule.
- `src/kontor/period_tax_provider.clj:44-61` — `period-tax-kinds`
  enum; `:capital-gains-tax` and `:other` already in.
- `src/kontor/entity.clj` (ADR-031) — `kontor.entity/family` walk for
  the 100%-control verification.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  `:disposal` schema design this note's CN companion extends.
- `doc/research/113-de-cgt-fit.md` §5.1 — the "CGT provider feeds
  CIT via adjustment input" composition pattern reused for the CN
  EIT exceptions.
- `doc/research/115-jp-cgt-fit.md` §5 — the "multi-component
  single-provider" pattern reused for `cn-iit-cgt-provider`.

---

End of note 133.

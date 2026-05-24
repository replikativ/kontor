---
date: 2026-05-24
title: 131 — IN capital-gains tax — substrate fit for Phase 3
audience: maintainer + the Phase 3 `in-cgt-provider` implementation agent
status: research-before for the IN CGT companion of `kontor-disposal` (ADR-102) + the future `in-cgt-provider`; no code
---

# 131 — IN capital-gains tax: substrate fit for Phase 3

India overhauled its capital-gains regime in **Finance (No. 2) Act
2024**, effective for transfers from **23 July 2024 onward**. The reform
collapsed a previously complex rate stack into **two uniform rates**
(12.5 % LTCG without indexation, 20 % STCG on listed equity), removed
indexation for almost every asset class, and grandfathered indexation
only for pre-23-July-2024 immovable property held by residents. Finance
Act 2025 made **no further changes** to the CGT rates but the new
Income Tax Bill 2025 (effective AY 2026-27) tidies the section numbering
without changing the rates.

For substrate purposes, IN now has the **simplest** CGT rate structure
of any major jurisdiction kontor will cover — but the **richest
catalogue of rollover exemptions** (§54, §54B, §54D, §54EC, §54F, §54G,
§54GA, §54GB) and the most aggressive **TDS-at-source mechanic**
(§194-IA on real estate, §195 on non-resident transfers).

This note (a) summarises the IN CGT regime post-FA 2024/2025, (b) walks
two worked examples, (c) assesses fit against the shipped
`kontor-disposal` schema, (d) names the data gaps, (e) sketches the
`in-cgt-provider`, and (f) cites sources.

---

## §1. The IN CGT regime — post-Finance Act 2024

### 1.1 Holding-period classifier (uniformised by FA 2024)

Source: [Income Tax India — STCG (as amended by FA 2025)](https://incometaxindia.gov.in/tutorials/14-%20stcg.pdf);
[CleartTax — STCG](https://cleartax.in/s/short-term-capital-gain-on-shares);
[Precize — Unlisted Shares Tax 2025-26](https://www.precize.in/guides/unlisted-shares-tax).

Two-tier holding-period classifier (post-23-Jul-2024):

| Asset type                                              | LTCG threshold |
|---------------------------------------------------------|----------------|
| Listed equity shares + equity-oriented mutual funds + units of business trust + listed bonds/debentures | **> 12 months** |
| All other assets — unlisted shares, immovable property, gold, debt MFs (pre-1-Apr-2023 acquisitions), foreign-listed securities | **> 24 months** |

FA 2024 collapsed the prior three-tier (12 / 24 / 36 month) scheme into
this two-tier one. The old 36-month bucket (debt MF, gold, etc.) is
gone.

### 1.2 Headline rates (post-23-Jul-2024)

Source: [Income Tax India — Exemptions from Capital Gains (FA 2025)](https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf);
[Bajaj Finserv — LTCG FY 2025-26](https://www.bajajfinserv.in/investments/understanding-long-term-capital-gains-tax);
[BusinessToday — Capital Gain Tax After Budget 2025](https://www.businesstoday.in/personal-finance/tax/story/capital-gain-tax-after-budget-2025-latest-ltcg-stcg-rates-on-various-assets-post-budget-2025-463274-2025-02-03).

#### LTCG rates

| Asset class                                              | LTCG rate (post-23-Jul-2024) | Indexation? | Section |
|----------------------------------------------------------|------------------------------|--------------|---------|
| Listed equity, equity MF, units of BT (with STT paid)    | **12.5 %** above ₹ 1.25 lakh exemption | No | §112A |
| Unlisted equity shares                                   | **12.5 %**                   | No           | §112    |
| Immovable property (land/building) — acquired **before** 23-Jul-2024 by resident individual/HUF | **12.5 %** without indexation OR **20 %** with indexation (taxpayer elects, whichever is lower) | Elective | §112 proviso (FA 2024) |
| Immovable property — acquired on/after 23-Jul-2024 OR by non-resident/non-individual | **12.5 %** | No           | §112    |
| Gold + non-equity assets (incl. listed bonds/debentures) | **12.5 %**                   | No           | §112    |
| Debt MF (acquired on/after 1-Apr-2023)                   | **Slab rate (STCG always)**  | No (treated as STCG regardless of holding) | §50AA |
| Market-linked debentures + specified MF holding > 65 % debt | **Slab rate (STCG always)** | No           | §50AA |

#### STCG rates

| Asset class                                              | STCG rate                    | Section |
|----------------------------------------------------------|------------------------------|---------|
| Listed equity, equity MF, units of BT (with STT paid)    | **20 %** (raised from 15 % by FA 2024) | §111A |
| Unlisted equity shares                                   | **slab rate** (added to total income) | (default §45)|
| Immovable property — short-term                          | **slab rate**                | (default §45)|
| Gold, other non-equity                                   | **slab rate**                | (default §45)|
| Depreciable assets (block of assets, §50)                | **slab rate** — ALWAYS STCG regardless of holding | §50  |
| Slump sale of an undertaking held < 36 months            | **slab rate** (STCG)         | §50B    |
| Slump sale of an undertaking held ≥ 36 months            | **12.5 %** (LTCG)            | §50B    |

The **₹ 1.25 lakh annual exemption** under §112A is a per-financial-year
floor — only LTCG above ₹ 1.25 L is taxed at 12.5 %. The exemption was
raised from ₹ 1 L by FA 2024.

### 1.3 Indexation — narrow grandfather only

Per FA 2024 + [CleartTax — CII](https://cleartax.in/s/cost-inflation-index)
+ [Tax2Win — CII](https://tax2win.in/guide/cost-inflation-index):

- **CII removed for almost all assets** transferred on/after 23-Jul-2024.
- **One exception**: resident individuals and HUFs may **elect** to use
  indexation on **immovable property acquired before 23-Jul-2024**,
  paying **20 % with indexation** instead of **12.5 % without**. The
  taxpayer picks the lower-tax option per-disposal.
- CII for FY 2024-25 = 363; CII for FY 2025-26 = 376
  (incometaxindia.gov.in).

### 1.4 Section 47 — transfers not regarded as transfer (rollover-by-fiction)

Source: [CleartTax — Section 47](https://cleartax.in/s/section-47-of-income-tax-act);
[MASLLP — Section 47](https://masllp.com/section-47-certain-transactions-not-regarded-as-transfer-for-capital-gains/);
[Bajaj Finserv — Section 47](https://www.bajajfinserv.in/section-47-of-the-income-tax-act).

§47 enumerates 30+ transactions where the disposal does NOT trigger
CGT (the transferee inherits the transferor's cost + holding period
under §49). Key items:

- **§47(i) total partition of HUF** — no CGT on the partition itself.
- **§47(iii) gift, will, irrevocable trust** — no CGT for the donor.
  **(Finance Act 2024 caveat: from AY 2025-26, gifts to **non-relatives**
  are deemed transfers for the recipient under §56(2)(x).)**
- **§47(iv), (v) transfers between parent + 100 %-subsidiary** (one is
  Indian company) — no CGT.
- **§47(vi) amalgamation** transfers to an Indian-resident amalgamated
  company — no CGT for the amalgamating company.
- **§47(vib) demerger** transfers to an Indian-resident resulting company.
- **§47(vid) conversion of bonds/debentures into shares** of the same
  company — no CGT.
- **§47(xiiib) conversion of LLP into a private company** + reverse, on
  specified conditions.

In substrate terms: a `:disposal` with `:disposal/kind :sale` (or
`:incorporation-contribution`, `:distribution-in-kind`) PLUS
`:disposal/exemption-claimed #{:in-§47-amalgamation}` (or the relevant
§47 sub-clause) and the IN provider yields **zero CGT**, while still
recording the basis carry-over to the recipient.

### 1.5 Sections 54 / 54B / 54D / 54EC / 54F / 54GA — rollover reliefs

Source: [Income Tax India — Exemptions from Capital Gains (FA 2025)](https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf);
[CleartTax — Section 54EC](https://cleartax.in/s/section-54ec-bonds);
[StoxN — Section 54 (2025 edition)](https://stoxntax.com/2025/08/25/exemption-under-section-54-complete-law-rules-and-recent-updates-2025-edition/);
[Bajaj Finserv — Section 54](https://www.bajajfinserv.in/understanding-sec-54-of-the-income-tax-act).

A density-rich catalogue — kontor's `:exemption-claimed` and
`:rollover-into-asset` must accommodate:

| §        | Original asset                          | Reinvest in                                    | Time window                    | Cap         | Holding lock on new asset |
|----------|------------------------------------------|------------------------------------------------|--------------------------------|-------------|----------------------------|
| **§54**  | Residential house (LTCG, indiv/HUF)      | Another residential house                       | -1 to +2 yrs (purchase) / +3 yrs (construct) | ₹ 10 cr cap on new asset cost (FA 2023, AY 2024-25+); one-time two-house option if gain ≤ ₹ 2 cr | 3 yrs       |
| **§54B** | Agricultural land (urban; LTCG/STCG)     | Another agricultural land                       | +2 yrs                          | none        | 3 yrs                       |
| **§54D** | Industrial undertaking land/building (compulsory acquisition) | Land/building for industrial undertaking | +3 yrs                          | none        | 3 yrs                       |
| **§54EC**| Land or building (LTCG)                  | NHAI/REC/PFC/IRFC 5-yr bonds                    | +6 months                       | **₹ 50 L per FY** across §54EC bonds | 5 yrs lock-in |
| **§54F** | Any LTCG asset other than residential house (indiv/HUF) | Residential house                               | -1 to +2 yrs / +3 yrs construct | ₹ 10 cr cap; can hold ONLY ONE other house at sale date | 3 yrs       |
| **§54G** | Industrial undertaking in urban area (shift to non-urban) | Land/building/machinery in non-urban area | +1 yr before / +3 yrs after     | none        | 3 yrs                       |
| **§54GA**| Shift from urban to SEZ                  | SEZ land/building/machinery                     | +1 yr before / +3 yrs after     | none        | 3 yrs                       |
| **§54GB**| Residential property (indiv/HUF)         | Equity of eligible startup, applied to plant/machinery | +6 months                | none (sunset 31-Mar-2025; extended in FA 2025?) | 5 yrs       |

**Capital Gains Account Scheme (CGAS) — §54(2) etc.** Unused
reinvestment proceeds must be deposited in a special CGAS bank account
by the return-filing due date; reinvestment from CGAS within the
prescribed window preserves the exemption.

### 1.6 Section 50C, 50D, 50CA — anti-undervaluation

Source: [TaxTMI — Clause 78 IT Bill 2025 / Section 50C](https://www.taxtmi.com/tmi_notes?id=1529);
[CleartTax — Section 50C](https://cleartax.in/s/taxability-sale-land-building-section-50c).

When the **stated consideration** is less than the deemed FMV:

- **§50C — land/building**: full value of consideration deemed to be
  the stamp-duty value (SDV) if SDV > stated price. Safe harbour: 10 %
  tolerance (so SDV must exceed 110 % of stated for §50C to bite; FA
  2020 raised the threshold to 110 % from the original 105 %).
- **§50CA — unlisted shares**: deemed FMV per Rule 11UA computation
  is the consideration if higher than stated.
- **§50D — unascertainable consideration**: deemed FMV.

Substrate impact: the BR companion's recorder helper may need a
`:disposal/deemed-proceeds-amount` if the consumer wants to record
both the stated price AND the §50C-deemed price for audit. **But** the
shipped `:disposal/proceeds-amount` already captures the price that
**lands on the tax computation**; the consumer either records the
deemed value (and notes the diff in `:audit-doc`) or records both via
a paired void disposal. Recommendation: keep it provider-side — the
IN provider's recorder applies §50C as a pre-computation transform
when fed the SDV via `:audit-doc` ref. No schema gap.

### 1.7 Section 70, 71, 74 — set-off and carry-forward

Source: [Income Tax India — Set Off / Carry Forward (FA 2025)](https://incometaxindia.gov.in/Tutorials/21-%20MCQ%20set%20off%20and%20carry%20frwrd.pdf);
[CleartTax — Set off / carry forward of capital losses](https://cleartax.in/s/set-off-carry-forward-capital-losses);
[Bajaj Finserv — Section 74](https://www.bajajfinserv.in/investments/section-74-of-income-tax-act);
[CAalley — IT Bill 2025 one-time LTCL set-off](https://www.caalley.com/news-updates/indian-news/one-time-set-off-of-long-term-capital-loss-against-stcg-new-income-tax-bill-2025-allows-this-from-tax-year-2026-27-onwards).

| Loss kind     | Same-year set-off                          | Carry-forward                                  |
|---------------|---------------------------------------------|------------------------------------------------|
| **STCL**      | against STCG **AND** LTCG (§70(2))          | **8 AY**, against STCG OR LTCG (§74(1)(a))     |
| **LTCL**      | against LTCG ONLY (§70(3))                  | **8 AY**, against **LTCG only** (§74(1)(b)) — **but IT Bill 2025 one-time relief**: LTCL incurred up to 31-Mar-2026 may set off against any capital gain (STCG or LTCG) from AY 2027-28 |

Losses CANNOT offset other heads of income (no offset against salary,
business profits, etc.). Speculation losses (§73) and unabsorbed
depreciation (§32(2)) follow different rules — out of CGT scope.

**Filing precondition**: carry-forward is conditional on filing the
return **on or before the due date** (§139(3)). Late return → loss is
lost.

### 1.8 TDS at source — §194-IA, §194-IB, §194-LA, §195

Source: [Income Tax India — TDS Purchase of Immovable Property](https://www.incometaxindia.gov.in/w/tds-purchase-of-immovable-property);
[CleartTax — Section 194-IA](https://cleartax.in/s/how-to-file-tds-on-sale-property);
[Bajaj Finserv — TDS Property Purchase](https://www.bajajfinserv.in/tds-on-property-purchase-above-50-lakhs-section).

Brokers, buyers, and the government withhold CGT-relevant TDS:

| Section    | Trigger                                                   | Rate              | Threshold              |
|------------|------------------------------------------------------------|-------------------|------------------------|
| **§194-IA**| Buyer of immovable property (non-agricultural) from resident | **1 %** on sale consideration **OR** SDV (whichever higher; FA 2024) | ≥ ₹ 50 L (FA 2025 amendment: includes incidental charges + joint-ownership aggregate) |
| **§194-IB**| Rent > ₹ 50k/month by individual not subject to audit       | 5 %               | Rent (not CGT)         |
| **§194-LA**| Compulsory acquisition by government — compensation        | 10 %              | > ₹ 2.5 L              |
| **§195**   | Buyer of any asset from **non-resident**                   | depends on asset + treaty (typically 12.5 % LTCG, 20 % STCG; rates apply against gain, but most buyers withhold against **gross** unless seller obtains §197 lower-deduction cert) | none |

§194-IA TDS is creditable against the seller's final tax via Form
26AS. The credit lands on the seller's return; the buyer files Form
26QB within 30 days of the credit/payment month-end.

For substrate: like BR's IRRF, this is **broker/buyer-side
withholding**, fed to the IN provider via `:inputs :in-tds-withheld`
or stored on the disposal as `:disposal/tds-withheld-amount`. See §4
gap B.

### 1.9 Corporate vs individual — same rates, different return

Source: [Bajaj Finserv — Section 74](https://www.bajajfinserv.in/investments/section-74-of-income-tax-act);
PwC Worldwide Tax Summaries — India Corporate.

Both individuals and companies use the **same CGT rates** (§111A, §112,
§112A, §50, §50B). Differences:

- **Companies** cannot use §54, §54B, §54F (those are
  individual/HUF-only). They CAN use §54D, §54EC, §54G, §54GA.
- **Companies** under §115BAA (22 % concessional CIT) treat CGT
  separately at §112-rates (12.5 % LTCG) — capital gains do **not**
  enter the 22 % base. Same for §115BAB (15 % new-mfg).
- **Companies** report on ITR-6 (vs individuals on ITR-2 / ITR-3);
  CGT computation is the same but a different schedule lane.
- The §112A ₹ 1.25 L exemption applies to companies too (counterintuitively
  — per the section text — though in practice rarely matters as
  corporate LTCG portfolios are large).

For substrate: ONE IN CGT provider for both cases; the
`:disposal/subject-form` field (`:individual` / `:huf` / `:corp` /
`:partnership` / `:llp`) gates §54/54B/54F eligibility at provider
time.

---

## §2. Worked examples

### Example A — Resident individual, immovable property with indexation election

Mr. Sharma, resident individual, sells a Mumbai flat on 2026-04-15:

- Acquired 2010-03 for ₹ 30,00,000.
- Improvements (added bath) 2015-06 ₹ 5,00,000.
- Sale price 2026-04 ₹ 1,80,00,000; expenses ₹ 3,00,000.
- Held > 24 months → LTCG.
- Acquired **before 23-Jul-2024** → eligible for the FA 2024 election.

**Option 1 — 12.5 % without indexation**

- Net proceeds = 1,80,00,000 − 3,00,000 = ₹ 1,77,00,000.
- Total cost = 30,00,000 + 5,00,000 = ₹ 35,00,000.
- LTCG = 1,77,00,000 − 35,00,000 = **₹ 1,42,00,000**.
- Tax = 1,42,00,000 × 12.5 % = **₹ 17,75,000**.

**Option 2 — 20 % with indexation** (CII 2010-11 = 167, CII 2015-16 =
254, CII 2026-27 = 376 — extrapolated)

- Indexed cost of 2010 acquisition = 30,00,000 × (376/167) = **₹ 67,54,491**.
- Indexed cost of 2015 improvement = 5,00,000 × (376/254) = **₹ 7,40,157**.
- Total indexed cost = **₹ 74,94,648**.
- LTCG = 1,77,00,000 − 74,94,648 = **₹ 1,02,05,352**.
- Tax = 1,02,05,352 × 20 % = **₹ 20,41,070**.

**Mr. Sharma elects Option 1** (₹ 17,75,000 < ₹ 20,41,070), saving
₹ 2,66,070.

He also reinvests ₹ 1,42,00,000 of the gain in REC 5-yr bonds within
6 months under **§54EC** → exemption capped at ₹ 50 L → taxable LTCG
after exemption = 1,42,00,000 − 50,00,000 = **₹ 92,00,000**; tax =
₹ 11,50,000.

He further claims **§54** by buying a new flat for ₹ 1,30,00,000
within 2 years → ₹ 92 L gain fully sheltered (the new flat is well
above the ₹ 10 cr §54 cap) → **net CGT = ₹ 0**.

The buyer withheld §194-IA TDS = 1 % × 1,80,00,000 = ₹ 1,80,000 (since
SDV ≥ stated). Mr. Sharma claims the ₹ 1,80,000 as a refund on his
ITR-2.

Substrate trace: ONE `:disposal` with
- `:subject-kind :real-estate-private`,
- `:asset-class :in-immovable-residential-pre-2024-07-23`,
- `:holding-period :long`,
- `:elective-regime #{:in-§112-proviso-no-indexation}`,
- `:exemption-claimed #{:in-§54EC-bonds :in-§54-residential}`,
- `:rollover-into-asset <new flat>` (for §54),
- `:rollover-amount 9200000M` (the §54-sheltered portion AFTER §54EC).

The IN provider walks the §54EC cap first, then the §54 shelter, then
applies 12.5 %. Output: zero tax + ₹ 1,80,000 refund line item +
audit-doc for both elections.

### Example B — Resident individual, listed equity portfolio

Ms. Patel's FY 2025-26 brokerage statement:

- LTCG on RIL shares (held > 12 months, STT paid): ₹ 4,00,000.
- LTCG on Infosys shares (held > 12 months, STT paid): ₹ 80,000.
- STCG on HDFC Bank shares (held 4 months, STT paid): ₹ 1,50,000.
- LTCL on Tata Motors (held 18 months, STT paid): ₹ 60,000.

**Step 1 — within-year set-off**:
- LTCG = 4,00,000 + 80,000 − 60,000 = ₹ 4,20,000.
- STCG = ₹ 1,50,000.

**Step 2 — §112A ₹ 1.25 L exemption**:
- Net LTCG above floor = 4,20,000 − 1,25,000 = **₹ 2,95,000**.

**Step 3 — apply rates** (FA 2024 post-23-Jul):
- LTCG tax (§112A) = 2,95,000 × 12.5 % = **₹ 36,875**.
- STCG tax (§111A) = 1,50,000 × 20 % = **₹ 30,000**.

Plus 4 % health & education cess. Total = (36,875 + 30,000) × 1.04 =
**₹ 69,550**.

Substrate trace: four `:disposal`s, each with `:asset-class
:in-listed-equity-stt-paid` and `:holding-period :short` or `:long`.
The IN provider:
- separates into STCG (1 disposal) and LTCG (3 disposals);
- applies §70(3) within-year set-off (LTCL → LTCG only);
- subtracts §112A ₹ 1.25 L floor from positive net LTCG;
- applies 12.5 % and 20 %;
- adds 4 % cess.

---

## §3. `:disposal` schema fit assessment

### 3.1 `:holding-period :short / :long / :n-a` — fits, with a wrinkle

IN's two-tier classifier (12 months for listed equity, 24 months for
everything else) lands in `:short` / `:long` cleanly. The IN companion
ships a per-asset-class classifier:

```clojure
(defn in-holding-period
  "Post-23-Jul-2024 IN classifier. 12 months for listed-equity, 24 for
   everything else. Depreciable assets (§50, block-of-assets) and
   debt MFs post-1-Apr-2023 (§50AA) are ALWAYS :short for rate
   purposes — see :asset-class enum below for the §50/§50AA flags."
  [acquired-on disposal-date asset-class]
  (let [months (#'date/months-between acquired-on disposal-date)
        threshold (if (#{:in-listed-equity-stt-paid
                         :in-listed-equity-mf
                         :in-bt-unit
                         :in-listed-bond} asset-class)
                    12
                    24)]
    (cond
      (#{:in-depreciable-block :in-debt-mf-post-2023} asset-class) :short
      (> months threshold) :long
      :else :short)))
```

The §50/§50AA "always short" rule is the classifier's responsibility
(not the schema's). Audit-trail-correct: the law-as-it-stood doctrine
lands `:short` permanently for those classes.

### 3.2 `:asset-class` — IN vocabulary

Suggested IN-namespaced values for `:disposal/asset-class`:

| Value                                          | Meaning                                                |
|------------------------------------------------|--------------------------------------------------------|
| `:in-listed-equity-stt-paid`                   | Listed equity / equity MF / business-trust units with STT paid (§111A/§112A) |
| `:in-listed-equity-no-stt`                     | Listed equity / MF without STT (e.g. off-market sale)  |
| `:in-listed-bond`                              | Listed bonds, debentures (LTCG 12.5 %, STCG slab)      |
| `:in-unlisted-equity`                          | Unlisted shares (LTCG 12.5 %, STCG slab)               |
| `:in-immovable-residential-pre-2024-07-23`     | Indiv/HUF residential land/building, pre-FA2024 acq    |
| `:in-immovable-residential-post-2024-07-23`    | Indiv/HUF residential, post-FA2024 acq (no election)   |
| `:in-immovable-commercial`                     | Commercial property                                    |
| `:in-immovable-agricultural-urban`             | Urban agricultural land (taxable; §54B available)      |
| `:in-immovable-agricultural-rural`             | Rural agricultural land (NOT a capital asset; out of CGT) |
| `:in-gold-bullion`                             | Gold / silver / precious metals                        |
| `:in-debt-mf-post-2023`                        | Debt MF acquired on/after 1-Apr-2023 (always STCG, §50AA) |
| `:in-debt-mf-pre-2023`                         | Debt MF acquired before 1-Apr-2023 (LTCG 12.5 %, no indexation) |
| `:in-mld`                                      | Market-linked debenture (§50AA — always STCG)          |
| `:in-depreciable-block`                        | Block-of-assets depreciable (§50 — always STCG slab)   |
| `:in-business-undertaking-slump-sale`          | Whole undertaking transferred slump-style (§50B)       |
| `:in-foreign-listed-security`                  | Foreign-listed shares (LTCG 12.5 %, STCG slab)         |
| `:in-foreign-immovable-property`               | Foreign-situated immovable                             |
| `:in-self-generated-goodwill`                  | Self-generated goodwill (FA 2021 — cost deemed zero)   |

### 3.3 `:loss-bucket` — IN compartments

| Value                  | Meaning                                                |
|------------------------|--------------------------------------------------------|
| `:in-stcl-listed-equity`| STCL on §111A-eligible assets — sets off STCG/LTCG     |
| `:in-stcl-other`       | STCL on slab-rate assets — sets off STCG/LTCG          |
| `:in-ltcl-listed-equity`| LTCL on §112A-eligible — sets off LTCG only            |
| `:in-ltcl-other`       | LTCL on §112-eligible — sets off LTCG only             |
| `:in-ltcl-pre-2026-onetime`| Pre-31-Mar-2026 LTCL, one-time set-off vs STCG (IT Bill 2025) |
| `:in-§50-depreciable-loss`| Block-of-assets loss — distinct treatment under §41   |

8-year carry-forward across all (per §74). Provider's
`:capital-loss-carryforward` `:inputs` map keys by bucket.

### 3.4 `:exemption-claimed` — IN vocabulary

| Value                          | §        | Notes                                |
|--------------------------------|----------|--------------------------------------|
| `:in-§54-residential`          | §54      | Residential house roll-over          |
| `:in-§54-two-houses-onetime`   | §54      | One-time two-house option, gain ≤ ₹ 2 cr |
| `:in-§54B-agri-land`           | §54B     | Agricultural land roll-over          |
| `:in-§54D-industrial`          | §54D     | Industrial land (compulsory acq)     |
| `:in-§54EC-bonds`              | §54EC    | REC/NHAI/PFC/IRFC bonds — ₹ 50 L cap/FY |
| `:in-§54F-other-asset`         | §54F     | Other LTCG asset → residential house |
| `:in-§54G-shift-non-urban`     | §54G     | Industrial shift urban → non-urban   |
| `:in-§54GA-shift-sez`          | §54GA    | Shift urban → SEZ                    |
| `:in-§54GB-startup`            | §54GB    | Residential proceeds → startup equity |
| `:in-§112A-floor`              | §112A    | The ₹ 1.25 L annual floor (per-taxpayer; provider applies, not per-disposal) |
| `:in-§47-amalgamation`         | §47(vi)  | Amalgamation — no transfer           |
| `:in-§47-demerger`             | §47(vib) | Demerger — no transfer               |
| `:in-§47-gift-relative`        | §47(iii) | Gift to relative                     |
| `:in-§47-parent-subsidiary`    | §47(iv)/(v) | Wholly-owned sub transfer         |
| `:in-§47-llp-conversion`       | §47(xiiib)| LLP↔private company conversion      |
| `:in-§10(37)-compulsory-agri`  | §10(37)  | Compulsory acq of agricultural urban land |

### 3.5 `:elective-regime` — IN vocabulary

| Value                                  | Meaning                                       |
|----------------------------------------|-----------------------------------------------|
| `:in-§112-proviso-no-indexation`       | The 12.5 %-no-indexation election (resident indiv/HUF on pre-23-Jul-2024 immovable) |
| `:in-§112-proviso-with-indexation`     | The 20 %-with-indexation election (same)      |
| `:in-§115BAA-corporate-22pct`          | Corp opting concessional 22 % CIT base (CGT still §112) |
| `:in-§115BAB-corporate-15pct`          | Corp new-mfg 15 % CIT base                    |
| `:in-§54-cgas-deposit`                 | Proceeds parked in CGAS pending reinvestment  |

### 3.6 `:rollover-into-asset` — IN use

Covers §54, §54B, §54D, §54EC, §54F, §54G, §54GA, §54GB. The
`:rollover-deadline` field reflects the per-§ window (e.g. 6 months
for §54EC, +2 yrs for §54, +3 yrs for construction §54). For §54EC
bonds, `:rollover-into-asset` points at a bond instrument; for §54
residential, at a property `:asset`. The new asset's
**3-year (or 5-year for §54EC) holding lock** is tracked on the
**new asset's** own `:disposal` if/when sold prematurely (a clawback
rule the IN provider needs to detect — see §4 gap A).

---

## §4. Concrete data gaps

### Gap A — Clawback on premature disposal of the rollover asset

When the taxpayer sells the §54 replacement house within 3 years (or
§54EC bonds within 5 years), the previously-exempted gain is
**clawed back** as taxable in the year of the premature disposal
(§54(1) proviso, §54EC(2)). The shipped schema has no native field
that flags "this disposal triggers a clawback of an earlier
`:disposal`'s exemption."

**Resolution**: provider-side, leveraging the existing
`:disposal/voids` edge in reverse. The IN companion's recorder helper
walks back from the new disposal's `:subject` (the §54 replacement
asset) to find the prior `:disposal` whose `:rollover-into-asset`
points at it; computes the deferred gain; surfaces it as a clawback
line item on the new disposal's tax computation.

**Substrate impact**: zero new schema. But the provider's docstring
must clearly document the convention (and a `:disposal/clawback-of`
edge would be a clean v2 add if usage proves it). Cheap.

### Gap B — TDS withheld credit

§194-IA (1 % real estate), §195 (NR transfer), §194-LA (compulsory
acq) all withhold tax that the seller credits against final liability.
The shipped schema has no TDS field. Same resolution as BR's IRRF:
**`:inputs` map**, not a schema attr.

```clojure
:inputs {:in-tds-withheld {:by-section
                            {:194-IA <Money>
                             :194-LA <Money>
                             :195   <Money>}}}
```

The IN provider sums by section, applies the credit after computing
gross CGT, exposes the residual as the DARF/challan-payable line.

### Gap C — §54EC ₹ 50 L per-FY cap aggregation

Like BR's R$ 35k aggregate exemption, §54EC's ₹ 50 L cap aggregates
across **all** §54EC bonds invested in the financial year. Multiple
disposals routing into multiple bond purchases must share the cap.

**Resolution**: provider-side fold. The IN provider iterates the FY's
disposals, sums claimed `:rollover-amount` where
`:exemption-claimed` contains `:in-§54EC-bonds`, allows the first
₹ 50 L, denies the excess. Zero schema gap.

### Gap D — §112A floor of ₹ 1.25 L

The ₹ 1.25 L annual exemption under §112A applies to the **taxpayer's
total** STT-paid LTCG, not per-disposal. Same shape as Gap C —
provider-side fold across the period's disposals tagged
`:asset-class :in-listed-equity-stt-paid` with `:holding-period
:long`. Zero schema gap.

### Gap E — Indexation cost lookup (resident indiv/HUF, pre-23-Jul-2024 immovable)

The 20 %-with-indexation alternative needs the CII for the
acquisition year (and every improvement year). The shipped schema
has no indexation field.

**Resolution**: ADR-101 `:parameter` (date-keyed CII table — 25 yearly
values from FY 2001-02 base). The IN provider reads
`:disposal/acquired-on` (and any improvement-date metadata via
`:audit-doc` or a future companion `:asset-improvement` entity)
and computes the indexed cost. The election itself is captured by
`:elective-regime #{:in-§112-proviso-with-indexation}`.

### Gap F — §50C deemed proceeds

When stamp-duty value > 110 % × stated price, §50C deems SDV to be
the consideration. The shipped `:disposal/proceeds-amount` captures
what hits the tax computation; the consumer either records SDV
directly (with a note in `:audit-doc`) or records the stated price
and lets the provider deem it.

**Recommendation**: provider-side. If `:audit-doc` includes a
`:audit-doc/sdv-amount` reference, the provider overrides
`:proceeds-amount` to `max(:proceeds-amount, :sdv-amount × 1/1.10)`
on the gain computation, surfaces the override in
`:line-items :deemed-proceeds-applied`. Adds one optional
`:audit-doc/category :stamp-duty-valuation` value. **Zero schema gap.**

### Bottom line — schema posture

**Zero schema changes** required. IN fits the shipped shape with:

- 17 new `:disposal/asset-class` values.
- 6 new `:disposal/loss-bucket` values.
- 16 new `:disposal/exemption-claimed` keywords.
- 5 new `:disposal/elective-regime` keywords.
- `:inputs` extensions for TDS, §54EC cap, §112A floor, CII lookup.

All open-vocabulary extensions in `kontor-l10n-in`. Kernel and
disposal-companion untouched.

---

## §5. `in-cgt-provider` sketch

### 5.1 Component count

ONE `TaxReturnFacts` per assessed entity per FY with up to **five
components**, one per rate-lane:

```clojure
{:kind :capital-gains-tax :authority :in-cbdt
 :composed-of [:in-ltcg-§112A]   ;; listed-equity LTCG above ₹1.25L floor
 :base ...                        ;; net LTCG above floor
 :schedule (ts/flat 0.125M)
 :line-items [:floor :line-set-off-stcl :section-47-exempt]}

{:kind :capital-gains-tax :authority :in-cbdt
 :composed-of [:in-stcg-§111A]   ;; listed-equity STCG
 :base ...
 :schedule (ts/flat 0.20M)
 :line-items [...]}

{:kind :capital-gains-tax :authority :in-cbdt
 :composed-of [:in-ltcg-§112]    ;; everything else LTCG
 :base ...                        ;; after §54/§54F/§54EC, optionally indexed
 :schedule (ts/flat 0.125M)       ;; or 0.20M if indexation elected
 :line-items [:rollover-§54 :rollover-§54EC :indexation-applied]}

{:kind :capital-gains-tax :authority :in-cbdt
 :composed-of [:in-stcg-other]    ;; everything else STCG → slab rate
 :base ...                        ;; folds into ITR slab via :base-transform-add to PIT
 :schedule :delegated-to-pit       ;; the IN PIT provider applies the slab
 :line-items [...]}

{:kind :capital-gains-tax :authority :in-cbdt
 :composed-of [:in-§50-depreciable]
 :base ...                        ;; depreciable block always slab
 :schedule :delegated-to-pit-or-cit
 :line-items [...]}
```

Plus a **4 % cess** surtax via the note-105 adjustment layer (already
the JP precedent for 2.1 % reconstruction surtax).

### 5.2 GL-fold seam for the slab lane

The two "delegated-to-PIT/CIT" components emit a **`:base-transform-add`
into the PIT/CIT provider's input** for slab-rate STCG (per note 112
§5.5 Option 1 for US corp). The dispatch (PIT vs CIT) depends on
`:disposal/subject-form`:

- `:individual` / `:huf` → fold into `in-pit-provider`.
- `:corp` / `:partnership` / `:llp` → fold into `in-cit-provider`.

This pattern reuses the existing `:base-transform` algebra (note 105)
without new substrate primitives.

### 5.3 Election logic — per-disposal lower-tax pick

For `:asset-class :in-immovable-residential-pre-2024-07-23`, the
provider computes BOTH options and picks the lower-tax one
**per-disposal** (the FA 2024 election operates per-asset, not in
aggregate):

```clojure
(defn pick-immovable-election
  [{:keys [proceeds basis acquired-on improvement-dates] :as disposal}
   cii-table]
  (let [no-index-tax (m/* (m/- proceeds basis) 0.125M)
        indexed-cost (compute-indexed-cost basis acquired-on
                                            improvement-dates cii-table)
        index-tax    (m/* (m/- proceeds indexed-cost) 0.20M)]
    (if (m/< no-index-tax index-tax)
      {:election :no-indexation :tax no-index-tax}
      {:election :with-indexation :tax index-tax})))
```

Surface the chosen election in `:line-items :election-chosen` and the
audit-doc.

### 5.4 §54 / §54EC / §54F two-pass interaction

The exemption hierarchy is:

1. §54EC bonds (₹ 50 L cap) — claimed first because it's a hard
   ₹-cap; any gain beyond the cap proceeds to step 2.
2. §54 / §54F (residential house) — claims after §54EC; subject to the
   ₹ 10 cr cap on the new house's cost.
3. §112A ₹ 1.25 L floor (listed equity only).

Two-pass query: step 1 needs the FY's aggregate §54EC claims (to enforce
the ₹ 50 L cap); step 2 needs the post-step-1 remainder. The IN
provider runs the passes inline; no schedule-algebra extension
needed.

### 5.5 Authority and emission

All file to **Central Board of Direct Taxes (CBDT)** (`:in-cbdt`). The
return is **ITR-2** (individuals with capital gains) or **ITR-3**
(individuals with business + capital gains) or **ITR-6** (companies).
Schedule **CG** carries the per-asset computation; Schedule **VI-A**
carries Chapter VI-A deductions; the §194-IA TDS shows on Schedule
**TDS2** (Form 26AS auto-reconciliation).

A v2 `kontor-l10n-in-cgt-emit` extension can synthesise the ITR-2/3
Schedule CG JSON from the disposal log + IRRF inputs; v1 ships the
computation, leaves the emit to the consumer.

---

## §6. ADR-101 statute-as-data — what IN CGT writes

Suggested ADR-101 parameters:

```clojure
;; The §112A floor (raised by FA 2024 from ₹1 L to ₹1.25 L)
{:parameter/code :in/cgt-§112A-floor
 :parameter/values
 [{:parameter-value/effective-from #inst "2018-04-01"
   :parameter-value/amount 100000M
   :parameter-value/currency :INR}
  {:parameter-value/effective-from #inst "2024-07-23"
   :parameter-value/amount 125000M
   :parameter-value/currency :INR}]}

;; §54EC bond cap
{:parameter/code :in/cgt-§54EC-cap
 :parameter/values
 [{:parameter-value/effective-from #inst "2014-04-01"
   :parameter-value/amount 5000000M       ;; ₹ 50 L
   :parameter-value/currency :INR}]}

;; CII table — 25 yearly values, base FY 2001-02 = 100
{:parameter/code :in/cgt-cii
 :parameter/values
 [{:parameter-value/effective-from #inst "2001-04-01" :parameter-value/index 100}
  ...
  {:parameter-value/effective-from #inst "2024-04-01" :parameter-value/index 363}
  {:parameter-value/effective-from #inst "2025-04-01" :parameter-value/index 376}]}

;; Rate parameters
{:parameter/code :in/cgt-§112A-rate
 :parameter/values [{:parameter-value/effective-from #inst "2024-07-23"
                     :parameter-value/rate 0.125M}
                    {:parameter-value/effective-from #inst "2018-04-01"
                     :parameter-value/rate 0.10M
                     :parameter-value/sunset-on #inst "2024-07-22"}]}

{:parameter/code :in/cgt-§111A-rate
 :parameter/values [{:parameter-value/effective-from #inst "2024-07-23"
                     :parameter-value/rate 0.20M}
                    {:parameter-value/effective-from #inst "2008-10-01"
                     :parameter-value/rate 0.15M
                     :parameter-value/sunset-on #inst "2024-07-22"}]}
```

The bitemporal `:parameter-value/effective-from` correctly handles the
23-Jul-2024 rate cliff. A pre-23-Jul-2024 disposal queried as-of
`:tx/valid-from #inst "2024-06-01"` reads the OLD rate.

Provisions (§54, §54EC, §54F, §47) stay in the record-shaped provider
for Phase 2 per note 102 §10.

---

## §7. Sources

### IN statutory primary

- **Income-tax Act, 1961**, sections **§45** (charge), §46–48
  (computation), **§50** (depreciable block), **§50AA** (debt MF / MLD),
  **§50B** (slump sale), **§50C** (stamp-duty value), **§50CA** (unlisted
  share FMV), **§50D** (unascertainable consideration), **§54** (residential
  house), **§54B** (agricultural land), **§54D** (industrial undertaking),
  **§54EC** (bonds), **§54F** (other LTCG asset), **§54G**, **§54GA**, **§54GB**,
  **§55** (FMV / cost of acquisition), **§55A** (valuation reference),
  **§70**, **§71**, **§74** (set-off / carry-forward), **§111A** (STCG listed
  equity), **§112** (LTCG default), **§112A** (LTCG STT-paid equity).
- **Finance (No. 2) Act, 2024** — the rate cliff and indexation removal.
- **Finance Act, 2025** — no CGT rate changes.
- **Income-tax Bill, 2025** (effective AY 2026-27) — section renumbering
  + the one-time LTCL set-off relief vs STCG.

### CBDT regulatory / tutorial

- [Income Tax India — Exemptions from Capital Gains (FA 2025)](https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf)
  — canonical §54/54B/54D/54EC/54F/54G/54GA reference.
- [Income Tax India — STCG tutorial (FA 2025)](https://incometaxindia.gov.in/tutorials/14-%20stcg.pdf)
  — §111A rate, §50 / §50AA classification.
- [Income Tax India — Set-off and carry-forward (FA 2025)](https://incometaxindia.gov.in/Tutorials/21-%20MCQ%20set%20off%20and%20carry%20frwrd.pdf)
  — §70/§71/§74.
- [Income Tax India — TDS on Purchase of Immovable Property](https://www.incometaxindia.gov.in/w/tds-purchase-of-immovable-property)
  — §194-IA.
- [Income Tax India — Cost Inflation Index](https://incometaxindia.gov.in/charts%20%20tables/cost-inflation-index.htm)
  — official CII table.
- [PIB — CBDT FAQs on new capital-gains regime, Budget 2024-25](https://www.pib.gov.in/PressReleasePage.aspx?PRID=2036604)
  — official Q&A.

### Reference / commentary

- [CleartTax — Section 47 (transfers not regarded)](https://cleartax.in/s/section-47-of-income-tax-act).
- [CleartTax — STCG on shares (§111A)](https://cleartax.in/s/short-term-capital-gain-on-shares).
- [CleartTax — Section 54EC bonds](https://cleartax.in/s/section-54ec-bonds).
- [CleartTax — Set-off / carry-forward of capital losses](https://cleartax.in/s/set-off-carry-forward-capital-losses).
- [CleartTax — Section 50C](https://cleartax.in/s/taxability-sale-land-building-section-50c).
- [Bajaj Finserv — §74](https://www.bajajfinserv.in/investments/section-74-of-income-tax-act),
  [§54](https://www.bajajfinserv.in/understanding-sec-54-of-the-income-tax-act),
  [§194-IA](https://www.bajajfinserv.in/tds-on-property-purchase-above-50-lakhs-section).
- [BusinessToday — Capital Gain Tax After Budget 2025](https://www.businesstoday.in/personal-finance/tax/story/capital-gain-tax-after-budget-2025-latest-ltcg-stcg-rates-on-various-assets-post-budget-2025-463274-2025-02-03).
- [StoxN Tax — §54 (2025 edition)](https://stoxntax.com/2025/08/25/exemption-under-section-54-complete-law-rules-and-recent-updates-2025-edition/).
- [TaxTMI — Clause 78 IT Bill 2025 (§50C parallels)](https://www.taxtmi.com/tmi_notes?id=1529).
- [Precize — Tax on Unlisted Shares in India (2025-26)](https://www.precize.in/guides/unlisted-shares-tax).
- [Tax2Win — CII guide](https://tax2win.in/guide/cost-inflation-index).
- [CleartTax — CII for FY 2025-26](https://cleartax.in/s/cost-inflation-index).
- [CAalley — IT Bill 2025 one-time LTCL set-off](https://www.caalley.com/news-updates/indian-news/one-time-set-off-of-long-term-capital-loss-against-stcg-new-income-tax-bill-2025-allows-this-from-tax-year-2026-27-onwards).

### kontor substrate cited

- `modules/disposal/src/kontor/disposal/schema.clj` — the shipped
  disposal schema this note assesses. Holding-period at lines 150-158,
  asset-class at 111-120, elective-regime at 219-225,
  exemption-claimed at 227-233, rollover triple at 236-261, loss-
  bucket at 264-272.
- `src/kontor/period_tax_provider.clj:44-61` — `:capital-gains-tax`
  in the closed enum.
- `src/kontor/period_tax_provider.clj:138-141` —
  `:capital-loss-carryforward` `:inputs` shape (extends to per-
  bucket map for IN).
- `src/kontor/tax_schedule.clj:64-90` — `:flat` for each IN rate-lane;
  bracket is not needed (IN rates are flat post-FA2024).
- `src/kontor/personal_income_tax.clj:71-83` — adjustment-layer
  pattern that 4 % cess and TDS credit ride.
- `modules/l10n-in/src/kontor/l10n_in/period_tax_provider.clj:40-49`
  — the existing IN PIT provider; the slab-rate STCG fold lands here
  via `:base-transform-add`.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  disposal schema this note exercises.
- `doc/research/112-us-cgt-fit.md` §5 — provider-sketch pattern reused.
- `doc/research/115-jp-cgt-fit.md` §5 — multi-component pattern reused
  for IN's five-lane fan-out.
- `doc/research/130-br-cgt-fit.md` — sibling note (BR); same posture on
  TDS-as-`:inputs`, monthly-aggregate exemptions as provider-side
  folds.

---

End of note 131.

---
date: 2026-05-24
title: 123 — CN corporate income tax (企业所得税) substrate-fit cross-check
audience: maintainer
status: research — pre-implementation fit assessment for ADR-101 substrate vs PRC EIT
---

# 123 — CN CIT substrate-fit cross-check

ADR-104 shipped DE CIT as the first end-to-end consumer of ADR-101's
statute-as-data substrate (`:tax-concept` / `:provision` / `:regime` /
`:parameter`). Before fanning out, the maintainer asked for three
non-DE/EU cross-checks — BR, IN, CN — to validate the pattern travels.
This is the CN read.

PRC corporate income tax (企业所得税 / Qǐyè Suǒdéshuì, "EIT") is governed
by the **Enterprise Income Tax Law of the PRC** adopted 2007-03-16 by the
10th NPC and effective **2008-01-01**, which unified domestic + foreign
enterprise taxation. The standard rate is 25 % (Art. 4); four major
preferential paths drop it to 15 % or 5 %; a multi-province consolidated-
filing mechanism (SAT Bulletin No. 57 / 2012) splits a single enterprise's
liability across the provinces it operates in; the R&D super deduction
gives 200 %/220 % of qualifying expense as a base deduction (yes — *more*
than 100 %). kontor has never looked at CN CIT — only CN payroll IIT
(ADR-085) has shipped.

**Headline.** The ADR-101 substrate fits CN CIT cleanly. The standard
25 % schedule, the small-low-profit (SLPE) effective-5 % rate, the
HNTE/Hainan/Lingang/Western 15 % preferential rates, the participation-
exemption between Chinese tax-resident enterprises (TREs), and the
10 % non-resident withholding all encode as parameters + provisions or
as `:regime`-elective sub-schedules with no new primitives needed. **The
R&D super deduction at 200 % / 220 % is fine** — the substrate's
`:base-deduct` already accepts amounts >0 with no upper bound tied to
actual cash spent, and the consumer supplies the qualifying-expense
amount; the multiplier (1.00, 1.00, or 1.20) is a parameter row. No
substrate change.

**The one CN-shaped wrinkle that doesn't have a clean DE/EU analogue** is
the **multi-province consolidated filing** under SAT Bulletin 57/2012
("CCSV" — Centralized Calculation, Separate Verification — 跨地区经营汇
总纳税). One legal entity computes ONE liability on a unified base, then
the substrate must allocate **50 %** to the HQ and **50 %** across the
non-excluded branches via a three-factor formula (35 % revenue + 35 %
payroll + 30 % assets). This is not a "different schedule" — it's an
**allocation/distribution step** *after* the schedule fires. The DE GewSt
Hebesatz (each municipality multiplies the same base by its own rate) is
shape-wise the closest analogue and is encoded as a `:tax-unit
:hebesatz` ctx fact + a compute-fn. CN CCSV needs a *post-liability*
fan-out instead of a pre-liability rate-variation — the natural fit is
a `kontor.report/marginalize`-style σ over the per-branch allocation
shares, NOT a substrate change. **P1 finding** below.

The other interesting CN-shaped wrinkle is the **stacking rules** between
SLPE (effective 5 %) and HNTE (15 %) and regional 15 %. These are
non-stackable elective regimes — the taxpayer picks one — which is
*exactly* what ADR-101 `:regime` is for. Substrate fits cleanly.

The rest of this note works through each preferential path with statute
citations, sketches the EDN encoding, identifies stress, and triages
findings.

## §1. Statute summary — rates, regimes, base adjustments

### 1.1 Standard rate — 25 % (EIT Law Art. 4 ¶1)

**Statute.** "*The rate of enterprise income tax shall be twenty-five
percent.*" (中华人民共和国企业所得税法 §4 ¶1, effective 2008-01-01.) Art. 4
¶2 sets 20 % for non-resident enterprises without a PE in China —
practically reduced to 10 % via Art. 27 + Implementing Regs §91, the
withholding regime (§1.5 below).

**Tax-resident enterprise (TRE)** is defined in Art. 2 ¶2: incorporated
in China, OR managed-and-controlled in China. The standard rate applies
to a TRE's worldwide income; foreign-tax credit per Art. 23-24.

### 1.2 Small low-profit enterprise (SLPE) — effective 5 % on first RMB 3 M
        (Cai Shui [2023] No. 6 + extensions through 2027-12-31)

**Statute.** EIT Law Art. 28 ¶1 authorises a reduced 20 % rate for SLPEs
("符合条件的小型微利企业，减按20%的税率征收企业所得税"); the actual
**effective** rate is set by recurring MoF/SAT circulars. The current
in-force version (Cai Shui [2023] No. 6 of 2023-03-26, extended to
**2027-12-31** by Cai Shui [2023] No. 12 / Ann. 2023 No. 8 → Ann. 2025
No. 6) takes 25 % of the SLPE's taxable income, applies the 20 % rate to
that slice, and exempts the other 75 %:

```
liability = (taxable_income × 0.25) × 0.20 = 0.05 × taxable_income
```

i.e. an **effective 5 % rate on taxable income up to RMB 3 million**.
The previous 2021-2022 version stratified — 2.5 % on the first RMB 1 M
and 5 % on RMB 1 M-3 M — but the 2023 extension flattened to a single
5 % effective rate on the full RMB 3 M bucket.

**SLPE qualification** (3 cumulative tests, Cai Shui [2023] No. 6 §2):

- annual taxable income ≤ RMB 3 M
- average headcount ≤ 300
- total assets (average over period) ≤ RMB 50 M

**Negative list** (same enterprises excluded from R&D super deduction —
§1.4 below): tobacco, accommodation+catering, wholesale+retail, real
estate, leasing+business services, entertainment.

**Stacking.** A taxpayer who is BOTH SLPE-qualified AND HNTE-qualified
**picks one**. The SLPE 5 % effective rate is taxpayer-favourable up to
RMB 1.5 M (where SLPE's 5 % beats HNTE's 15 %); above that, HNTE on the
full taxable income wins for most plausible cases. Per PwC China Tax
Facts 2025 + EU SME Centre + acclime: "preferential rates do not stack"
(不能叠加享受). ADR-101 `:regime` semantics: SLPE and HNTE are mutually-
exclusive elective regimes; the provider chooses the one the taxpayer
elected.

### 1.3 HNTE — High and New Technology Enterprise — 15 % rate
        (EIT Law Art. 28 ¶2; Cai Shui Guo Ke Fa [2016] No. 32)

**Statute.** EIT Law Art. 28 ¶2: "国家需要重点扶持的高新技术企业，减按15%
的税率征收企业所得税." Implementing Regulation §93 + the joint MoF/SAT/
MoST notice "高新技术企业认定管理办法" Guo Ke Fa Huo [2016] No. 32 set
the qualification criteria:

- IP ownership in one of **8 supporting technology fields** (electronics
  + IT, biotech, aerospace, new materials, high-tech services, new
  energy + energy conservation, resources + environment, advanced
  manufacturing + automation)
- ≥ 10 % of staff in R&D roles; ≥ 60 % of revenue from high-tech
  products/services
- R&D spend ≥ 5 % of revenue (revenue < RMB 50 M), 4 % (RMB 50-200 M),
  3 % (> RMB 200 M)
- HNTE certificate valid 3 years, renewable

**Stacking.** Combined with the R&D super deduction (Art. 30 ¶1) +
extended loss carryforward (10 years vs standard 5 — Cai Shui [2018]
No. 76). Cannot stack with SLPE or with Hainan/Lingang regional 15 %
(some regional rates are themselves HNTE-only carve-outs — the layering
is *which 15 %*, not "stacked").

### 1.4 R&D super deduction — 200 % / 220 % pre-tax deduction
        (Cai Shui [2023] No. 7 effective 2023-01-01; Cai Shui [2023] No. 44
         for IC + machine-tools through 2027-12-31)

**Statute.** EIT Law Art. 30 ¶1: "*The following expenses incurred by an
enterprise may be additionally deducted in computing the taxable income:
(1) Expenses on research and development of new technology, new products
and new craftsmanship.*" The recursive cascade of Cai Shui circulars
(2015 No. 119 introducing the modern negative-list framework → 2018 No.
99 raising the rate → 2023 No. 7 the current 200 %):

| Period / industry             | Multiplier | Effective deduction |
|-------------------------------|------------|---------------------|
| General enterprise pre-2023   | 175 %      | actual × 1.75       |
| **General enterprise 2023-on**| **200 %**  | **actual × 2.00**   |
| **IC + machine-tools 2023-27**| **220 %** | **actual × 2.20**   |
| Capitalised as intangible     | + 100 %    | amortisation × 2.0 (or × 2.2) |

i.e. **for every RMB 100 of qualifying R&D expense, the enterprise
deducts RMB 200 (or 220) from the taxable base.** The "extra 100 % /
120 %" is the **super deduction** — a `:base-deduct` of an amount
larger than what was actually spent. Substrate handles this fine: the
provision computes `(qualifying_expense × multiplier_minus_1)` as the
extra deduction, and the actual expense was already in book profit.

**Negative-list industries** (Cai Shui [2015] No. 119, still in force):
tobacco, accommodation+catering, wholesale+retail, real estate,
leasing+business services, entertainment. These are *not* eligible.

### 1.5 Withholding tax on non-residents — 10 %
        (EIT Law Art. 3 ¶3 + Art. 27; Implementing Regs §91)

**Statute.** EIT Law Art. 3 ¶3 defines a non-TRE without a PE in China
or with income unrelated to its PE. EIT Law Art. 4 ¶2 sets the statutory
rate at 20 %; Art. 27 §5 + Implementing Regs §91 reduce it to **10 %**
on China-source passive income (dividends, interest, royalties, rents,
capital gains, "other"). Treaty rates lower than 10 % apply if more
favourable per Art. 58. Common treaty reductions: HK 5 % dividends (≥
25 % equity), Singapore 5 %, Netherlands 5 %, US 10 % (no reduction).

**Mechanics.** Withholding agent = the Chinese payer (Implementing Regs
§104); withholding is on gross. **No interaction with the corporate
return** — the WHT is the non-resident's final tax. From the PERSPECTIVE
OF KONTOR, this is a transaction-tax via `TaxRateProvider` (ADR-071) at
the payment moment, NOT a period tax via `PeriodTaxProvider`. It belongs
in the same family as VAT/GST. **Out of scope for the CIT provider** —
mention it here for completeness; encoding goes through the transaction
substrate.

### 1.6 Regional preferential 15 % rates

Four major regional regimes; all share the **15 % rate** + a **qualifying
industry catalogue** + a **substantive operation requirement**:

- **Western Region Development** — Cai Shui [2020] No. 23 + extensions
  through **2030-12-31**; 12 provinces + 5 prefectures (Inner Mongolia,
  Ningxia, Shaanxi, Gansu, Chongqing, Sichuan, Guizhou, Guangxi, Yunnan,
  Qinghai, Tibet, Xinjiang); qualifying-industry main income ≥ 60 %.
- **Hainan Free Trade Port** — Cai Shui [2020] No. 31; **2020-01-01 to
  2027-12-31**; substantive ops in Hainan; encouraged-industry main
  income ≥ 60 %.
- **Lingang New Area (Shanghai)** — Cai Shui [2021] No. 13; companies
  in 4 key industries (integrated circuits, AI, biomedicine, civil
  aviation); 15 % for the first 5 years from establishment.
- **Qianhai (Shenzhen-Hong Kong)** + **Hengqin (Guangdong-Macao)** —
  similar carve-outs with their own qualifying-industry catalogues.

**Stacking.** Regional preferential rates do NOT stack with each other
or with HNTE — the taxpayer elects one. Some carve-outs (e.g. Hainan
IIT high-end talent at 15 %) interact at the individual level; CIT-side
they are alternatives.

### 1.7 Loss carryforward

**Standard.** EIT Law Art. 18: losses carry forward **5 years**, NO
carry-back.

**Extended.** Cai Shui [2018] No. 76: **10 years** for HNTE-certified
or TSME-certified enterprises, applies retroactively to unutilised
losses from the prior 5 years if the enterprise was HNTE-certified in
the year of the loss.

**Substrate fit.** Already covered by note-105 frontier 2 (the carry
primitive — deferred). Same gap as DE Mindestbesteuerung (note 120 P1-4).
Track in the same followup, NOT in the CN ship.

### 1.8 Multi-province consolidated filing — CCSV (SAT Bulletin 57/2012)

**Statute.** SAT Bulletin No. 57 of 2012 "跨地区经营汇总纳税企业所得税征收
管理办法" (《Administrative Measures for the Collection of EIT for
Cross-Regional Consolidated Tax-Paying Enterprises》), in force from
**2013-01-01**, replacing the 2008 Guo Shui Fa [2008] No. 28 framework.
Mechanics:

**Step 1.** The legal entity computes one consolidated EIT on a unified
taxable base (Art. 5-9). One return; one liability.

**Step 2.** The consolidated liability is split (Art. 6):
- **50 %** stays with HQ (总机构 — half of which is paid locally where
  the HQ sits, half goes to the central treasury);
- **50 %** is distributed across the non-excluded branches via the
  three-factor formula.

**Step 3 (Art. 15).** Each non-excluded branch *i*'s share of the 50 %:

```
share_i = 0.35 × (revenue_i / Σ revenue_j)
        + 0.35 × (payroll_i / Σ payroll_j)
        + 0.30 × (assets_i  / Σ assets_j)
```

where the three factors are the prior-year (上年度) full-year revenue,
prior-year full-year payroll (职工薪酬), and the assets-total at
prior-year **December 31** (Art. 17).

**Step 4 (Art. 5 — exclusions).** Five categories of branches are NOT
in the allocation:
- auxiliary branches without main production/operation function
- branches that qualified as SLPE in the prior year
- branches established in the current year
- branches closed in the current year
- foreign branches without legal-person status

**Substrate-fit consequence.** The schedule (25 % flat, or 15 %, or 5 %)
fires ONCE on the consolidated base. The result is then **fanned out**
across `(N+1)` provincial collectors — HQ + N branches. This is shape-wise
identical to how `kontor.report/marginalize` (ADR-096) works on the
write-side: ONE liability number → many `:posting`s tagged with their
provincial collector. **No new primitive needed**; what IS needed is a
*post-component* allocation step in the provider — see §4 P1-1 below.

### 1.9 What is NOT in CN CIT

For honest fitness assessment, three things German/US tax has that CN
**does not** have:

- **No surtax on EIT.** Unlike DE Soli (5.5 % on KSt) or JP local CIT
  (separate inhabitant + enterprise tax on top of national CIT), CN has
  ONE corporate-level income tax — the 25 % EIT. No `:surtax` provisions
  on the CIT side. (Education levy / urban-maintenance levy attach to
  VAT, not EIT.)
- **No AMT / minimum tax.** EIT Law has no alternative-minimum-tax
  mechanism. The substrate's `:minimum-tax` `:kind` value (ADR-099,
  note 102 §9-A) doesn't fire in CN.
- **No graduated rate.** EIT Law Art. 4 is flat 25 %. SLPE is a separate
  *regime* (effectively a different `:schedule/type :flat` with rate
  0.05M and a base-cap at RMB 3M), not a bracket of the main schedule.

These absences are friendly to the substrate: less complexity to encode.

## §2. Worked example — HNTE in Shanghai at RMB 50 M book profit

**Scenario.** "Shanghai TechCo Ltd" is HNTE-certified, books **RMB 50 M
book profit** for FY 2025, spends **RMB 5 M on qualifying R&D** (already
expensed in book profit; not in IC/machine-tool sector so 200 %
multiplier applies), has **RMB 2 M of non-deductible expenses** (fines,
non-deductible entertainment, non-listed-charity donations over the
12 %-of-profit cap), and receives **RMB 1 M dividends** from another
Chinese TRE in which it holds shares > 12 months (exempt under EIT Law
Art. 26 + Implementing Reg. §83).

**Step 1 — book-to-tax base adjustments.**

```
book profit:                                   50,000,000
+ non-deductible expenses (Art. 10):            +2,000,000
− exempt dividend income (Art. 26 ¶2):          −1,000,000
− R&D super deduction (Art. 30 ¶1):
  qualifying spend 5,000,000 × (2.00 − 1.00) =  −5,000,000
                                              -----------
taxable income:                                 46,000,000
```

The R&D super-deduction line deducts **RMB 5 M** — the *extra* 100 %
beyond the actual expense that was already in book profit. (If
TechCo were in IC/machine-tools, the multiplier would be 2.20 → extra
1.20 → −6,000,000 → taxable income 45,000,000.)

**Step 2 — schedule.**

```
HNTE rate (Art. 28 ¶2):  15 %
gross liability:         46,000,000 × 0.15 = 6,900,000
```

**Step 3 — no surtax, no credits in this example.** Net liability =
gross liability = **RMB 6,900,000**.

**Step 4 — CCSV fan-out, if applicable.** Assume TechCo has HQ in
Shanghai + 2 non-excluded branches in Beijing and Shenzhen with prior-year:

| Branch    | Revenue (M) | Payroll (M) | Assets@12-31 (M) |
|-----------|-------------|-------------|------------------|
| Beijing   | 60          | 8           | 30               |
| Shenzhen  | 40          | 5           | 20               |
| **Σ**     | **100**     | **13**      | **50**           |

```
Beijing  share = 0.35×(60/100) + 0.35×(8/13) + 0.30×(30/50)
              = 0.2100 + 0.2154 + 0.1800
              = 0.6054
Shenzhen share = 0.35×(40/100) + 0.35×(5/13) + 0.30×(20/50)
              = 0.1400 + 0.1346 + 0.1200
              = 0.3946
Σ        = 1.0000 ✓

HQ portion (50%):       3,450,000   → Shanghai (½ local, ½ central)
Branch portion (50%):   3,450,000
  Beijing  (60.54%):    2,088,630
  Shenzhen (39.46%):    1,361,370
Σ                       6,900,000 ✓
```

**Sources used in the worked example.**

- EIT Law Arts. 4, 10, 26, 28, 30 — chinatax.gov.cn / zhejiang.chinatax
- Cai Shui [2023] No. 7 (R&D 200 % through 2027-12-31)
- Cai Shui Guo Ke Fa [2016] No. 32 (HNTE qualification)
- SAT Bulletin 57/2012 Art. 6, 15, 17 (CCSV mechanics)

## §3. Substrate encoding — `:parameter` + `:provision` sketches

### 3.1 Parameters

```clojure
[;; Standard rate
 {:parameter/code         "CN.EIT.rate"
  :parameter/label        "PRC EIT standard rate (Art. 4 ¶1)"
  :parameter/jurisdiction :cn
  :parameter/unit         :rate
  :parameter/concept-iri  "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"}

 ;; HNTE preferential rate
 {:parameter/code         "CN.EIT.hnte-rate"
  :parameter/label        "HNTE preferential rate (Art. 28 ¶2)"
  :parameter/jurisdiction :cn :parameter/unit :rate}

 ;; SLPE effective rate (the 0.25 × 0.20 = 0.05 collapses into one stored value)
 {:parameter/code         "CN.EIT.slpe-effective-rate"
  :parameter/label        "SLPE effective rate on taxable income ≤ RMB 3 M (Cai Shui [2023] No. 6)"
  :parameter/jurisdiction :cn :parameter/unit :rate}

 ;; SLPE cap (the income ceiling beyond which SLPE rate doesn't apply)
 {:parameter/code         "CN.EIT.slpe-income-cap"
  :parameter/label        "SLPE taxable-income cap (RMB 3,000,000)"
  :parameter/jurisdiction :cn :parameter/unit :amount-money}

 ;; R&D super-deduction multiplier (general)
 {:parameter/code         "CN.EIT.rd-multiplier-general"
  :parameter/label        "R&D pre-tax super-deduction multiplier — general enterprise"
  :parameter/jurisdiction :cn :parameter/unit :rate}

 ;; R&D super-deduction multiplier (IC + machine-tools)
 {:parameter/code         "CN.EIT.rd-multiplier-ic-mt"
  :parameter/label        "R&D super-deduction multiplier — IC + industrial mother-machine sectors"
  :parameter/jurisdiction :cn :parameter/unit :rate}

 ;; Non-resident WHT (transaction-side; for completeness)
 {:parameter/code         "CN.EIT.nonres-wht"
  :parameter/label        "Withholding rate on non-resident passive income (Art. 27 §5)"
  :parameter/jurisdiction :cn :parameter/unit :rate}

 ;; CCSV three-factor weights
 {:parameter/code         "CN.EIT.ccsv-revenue-weight"
  :parameter/label        "CCSV three-factor revenue weight (35%)"
  :parameter/jurisdiction :cn :parameter/unit :rate}
 {:parameter/code         "CN.EIT.ccsv-payroll-weight"
  :parameter/label        "CCSV three-factor payroll weight (35%)"
  :parameter/jurisdiction :cn :parameter/unit :rate}
 {:parameter/code         "CN.EIT.ccsv-assets-weight"
  :parameter/label        "CCSV three-factor assets weight (30%)"
  :parameter/jurisdiction :cn :parameter/unit :rate}
 {:parameter/code         "CN.EIT.ccsv-hq-share"
  :parameter/label        "CCSV portion staying with HQ (50%)"
  :parameter/jurisdiction :cn :parameter/unit :rate}]
```

Parameter-value rows (representative ones):

```clojure
[{:parameter-value/parameter      [:parameter/code "CN.EIT.rate"]
  :parameter-value/effective-from #inst "2008-01-01"
  :parameter-value/decimal-value  0.25M
  :parameter-value/citation       "中华人民共和国企业所得税法 §4 ¶1 (2008-01-01 effective)"}

 {:parameter-value/parameter      [:parameter/code "CN.EIT.hnte-rate"]
  :parameter-value/effective-from #inst "2008-01-01"
  :parameter-value/decimal-value  0.15M
  :parameter-value/citation       "EIT Law §28 ¶2 — HNTE 15 % since 2008-01-01"}

 {:parameter-value/parameter       [:parameter/code "CN.EIT.slpe-effective-rate"]
  :parameter-value/effective-from  #inst "2023-01-01"
  :parameter-value/effective-until #inst "2028-01-01"
  :parameter-value/decimal-value   0.05M
  :parameter-value/citation        "Cai Shui [2023] No. 6 — extended by Ann. 2025 No. 6 to 2027-12-31"}

 {:parameter-value/parameter      [:parameter/code "CN.EIT.slpe-income-cap"]
  :parameter-value/effective-from #inst "2023-01-01"
  :parameter-value/decimal-value  3000000M
  :parameter-value/citation       "Cai Shui [2023] No. 6 §2 — RMB 3 M taxable-income ceiling"}

 {:parameter-value/parameter       [:parameter/code "CN.EIT.rd-multiplier-general"]
  :parameter-value/effective-from  #inst "2023-01-01"
  :parameter-value/effective-until #inst "2028-01-01"
  :parameter-value/decimal-value   2.00M
  :parameter-value/citation        "Cai Shui [2023] No. 7 — 200 % from 2023-01-01"}

 {:parameter-value/parameter       [:parameter/code "CN.EIT.rd-multiplier-ic-mt"]
  :parameter-value/effective-from  #inst "2023-01-01"
  :parameter-value/effective-until #inst "2028-01-01"
  :parameter-value/decimal-value   2.20M
  :parameter-value/citation        "Cai Shui [2023] No. 44 — 220 % for IC + industrial-mother-machine sectors through 2027-12-31"}

 {:parameter-value/parameter      [:parameter/code "CN.EIT.nonres-wht"]
  :parameter-value/effective-from #inst "2008-01-01"
  :parameter-value/decimal-value  0.10M
  :parameter-value/citation       "EIT Law Implementing Regs §91 — 10 % reduced from 20 % statutory"}

 {:parameter-value/parameter      [:parameter/code "CN.EIT.ccsv-revenue-weight"]
  :parameter-value/effective-from #inst "2013-01-01"
  :parameter-value/decimal-value  0.35M
  :parameter-value/citation       "SAT Bulletin 57/2012 Art. 15 — 0.35 weight on prior-year revenue"}
 ;; ... payroll-weight (0.35M) + assets-weight (0.30M) + hq-share (0.50M) similarly]
```

### 3.2 Regimes (the elective layer)

The HNTE / SLPE / regional preferential rates are mutually-exclusive
elective regimes. Encode each as a `:regime`:

```clojure
[{:regime/code        :cn-eit-standard
  :regime/jurisdiction :de
  :regime/label       "PRC EIT standard regime (25%)"}

 {:regime/code        :cn-eit-slpe
  :regime/jurisdiction :cn
  :regime/label       "Small Low-Profit Enterprise (effective 5%)"
  :regime/extends     [:regime/code :cn-eit-standard]}

 {:regime/code        :cn-eit-hnte
  :regime/jurisdiction :cn
  :regime/label       "High and New Technology Enterprise (15%)"
  :regime/extends     [:regime/code :cn-eit-standard]}

 {:regime/code        :cn-eit-hainan-ftp
  :regime/jurisdiction :cn
  :regime/label       "Hainan FTP encouraged-industry (15%)"
  :regime/extends     [:regime/code :cn-eit-standard]}
 ;; ... :cn-eit-western, :cn-eit-lingang, etc.
 ]
```

The taxpayer's elected regime rides ADR-034 status-machine (per ADR-101
§D5). At provider call time, `ctx :tax-unit :regime` carries the elected
code; `kontor.statute/applicable-provisions` filters provisions to the
elected-regime chain.

### 3.3 Provisions

Core provisions (representative subset):

```clojure
[;; Schedule provisions — different :regime, different rate
 {:provision/code           "CN-EIT-§4-standard"
  :provision/jurisdiction   :cn
  :provision/concept        [:tax-concept/code :base-transform-add]
                            ; *** placeholder: see §4 P0-1 ***
  :provision/regime         [:regime/code :cn-eit-standard]
  :provision/title          "EIT §4 ¶1 — standard 25 % rate"
  :provision/citation       "https://flk.npc.gov.cn/detail2.html?…"
  :provision/effective-from #inst "2008-01-01"
  :provision/priority       50
  :provision/consequence    (pr-str {:op :schedule-override
                                     :amount-from :parameter
                                     :parameter "CN.EIT.rate"})}

 {:provision/code           "CN-EIT-§28-¶2-hnte"
  :provision/jurisdiction   :cn
  :provision/regime         [:regime/code :cn-eit-hnte]
  :provision/title          "EIT §28 ¶2 — HNTE 15 % rate"
  :provision/citation       "https://flk.npc.gov.cn/detail2.html?…"
  :provision/effective-from #inst "2008-01-01"
  :provision/priority       50
  :provision/consequence    (pr-str {:op :schedule-override
                                     :amount-from :parameter
                                     :parameter "CN.EIT.hnte-rate"})}

 ;; §10 EIT Law non-deductible expenses — base-add
 {:provision/code           "CN-EIT-§10"
  :provision/jurisdiction   :cn
  :provision/concept        [:tax-concept/code :base-transform-add]
  :provision/title          "EIT §10 — non-deductible expense add-back"
  :provision/citation       "https://flk.npc.gov.cn/detail2.html?…"
  :provision/effective-from #inst "2008-01-01"
  :provision/priority       100
  :provision/condition      (pr-str [:gt [:inputs :cn-non-deductibles] 0M])
  :provision/consequence    (pr-str {:op :base-add
                                     :code :cn-§10
                                     :label "§10 non-deductible expenses"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :cn-non-deductibles]})}

 ;; §26 dividend exemption (between TREs) — base-deduct
 {:provision/code           "CN-EIT-§26-¶2"
  :provision/jurisdiction   :cn
  :provision/concept        [:tax-concept/code :participation-exemption]
  :provision/title          "EIT §26 ¶2 — dividend between resident enterprises"
  :provision/citation       "https://flk.npc.gov.cn/detail2.html?…"
  :provision/effective-from #inst "2008-01-01"
  :provision/priority       100
  :provision/condition      (pr-str [:and
                                     [:gt [:inputs :tre-dividend-income] 0M]
                                     [:eq [:inputs :tre-dividend-held-12m] true]])
  :provision/consequence    (pr-str {:op :base-deduct
                                     :code :cn-§26-tre-dividend
                                     :label "§26 ¶2 TRE-to-TRE dividend exemption"
                                     :amount-from :tax-context-fact
                                     :fact [:inputs :tre-dividend-income]})}

 ;; §30 R&D super deduction — base-deduct via compute-fn
 ;; (multiplier comes from parameter; consumer supplies qualifying-expense)
 {:provision/code           "CN-EIT-§30-rd-general"
  :provision/jurisdiction   :cn
  :provision/concept        [:tax-concept/code :base-transform-deduct]
  :provision/title          "EIT §30 ¶1 + Cai Shui [2023] No. 7 — R&D 200 % deduction (general)"
  :provision/citation       "https://flk.npc.gov.cn/detail2.html?…"
  :provision/effective-from #inst "2023-01-01"
  :provision/effective-until #inst "2028-01-01"
  :provision/priority       110
  :provision/condition      (pr-str [:and
                                     [:gt [:inputs :rd-qualifying-expense] 0M]
                                     [:not [:in [:inputs :industry] [:tobacco :hospitality :wholesale :real-estate :leasing :entertainment]]]
                                     [:not [:eq [:inputs :rd-sector] :ic-machine-tools]]])
  :provision/consequence    (pr-str {:op :base-deduct
                                     :code :cn-§30-rd-general
                                     :label "R&D super-deduction extra (general — 100 % of qualifying expense)"
                                     :amount-from :compute-fn
                                     :fn :cn-rd-super-deduction-general})}

 ;; R&D super deduction — IC + machine tools (220% / extra 120%)
 {:provision/code           "CN-EIT-§30-rd-ic-mt"
  :provision/jurisdiction   :cn
  :provision/concept        [:tax-concept/code :base-transform-deduct]
  :provision/title          "EIT §30 + Cai Shui [2023] No. 44 — R&D 220 % deduction (IC + machine-tools)"
  :provision/citation       "https://…cai-shui-2023-44"
  :provision/effective-from #inst "2023-01-01"
  :provision/effective-until #inst "2028-01-01"
  :provision/priority       110
  :provision/condition      (pr-str [:and
                                     [:gt [:inputs :rd-qualifying-expense] 0M]
                                     [:eq [:inputs :rd-sector] :ic-machine-tools]])
  :provision/consequence    (pr-str {:op :base-deduct
                                     :code :cn-§30-rd-ic-mt
                                     :label "R&D super-deduction extra (IC + machine-tools — 120 % of qualifying expense)"
                                     :amount-from :compute-fn
                                     :fn :cn-rd-super-deduction-ic-mt})}]
```

The R&D compute-fns are 3-liners:

```clojure
(defn- cn-rd-super-deduction-general [ctx]
  (let [m  (statute/parameter-value-at (:db ctx) "CN.EIT.rd-multiplier-general" (as-of ctx))
        qe (get-in ctx [:inputs :rd-qualifying-expense] 0M)]
    (* qe (- m 1M))))             ; 5,000,000 × (2.00 − 1.00) = 5,000,000

(defn- cn-rd-super-deduction-ic-mt [ctx]
  (let [m  (statute/parameter-value-at (:db ctx) "CN.EIT.rd-multiplier-ic-mt" (as-of ctx))
        qe (get-in ctx [:inputs :rd-qualifying-expense] 0M)]
    (* qe (- m 1M))))             ; × (2.20 − 1.00) = 6,000,000
```

### 3.4 SLPE — the cap stress

SLPE is the one regime that doesn't fit "rate-swap by regime" cleanly:
the 5 % rate applies only on the **first RMB 3 M of taxable income**;
above that, the rate snaps back to 25 %. Two equivalent encodings:

- **Option A** (faithful to statute text — "first RMB 3 M"): use a
  2-bracket progressive schedule `[{:rate 0.05 :upper 3000000}
  {:rate 0.25 :upper nil}]`. Already supported via `:progressive-bracket`.
- **Option B** (current statute text — implicit ceiling): the SLPE
  regime carries a `:regime/eligibility-cap` of RMB 3 M; if taxable
  income > 3 M the enterprise is no longer SLPE-qualified at all and
  the 25 % standard rate applies to the whole base.

**Statute says Option B** — Cai Shui [2023] No. 6 §2 requires the FULL
taxable-income test ≤ 3 M to qualify. Above that, you're not SLPE; the
preferential 5 % rate doesn't get a partial bite. This is therefore a
**regime gate** (qualification fails → fall back to standard regime),
not a schedule bracket.

Encode as a `[:leq [:inputs :taxable-income] 3000000M]` condition on
the SLPE schedule-override provision. If the condition fails, that
provision doesn't apply, and the standard regime's 25 % fires.
**Substrate accommodates this fine.**

(Note: there's a known quirk that SLPE income tests are also subject
to the headcount and assets caps — a 3-cumulative-AND condition. The
substrate's `[:and ...]` predicate covers it cleanly.)

## §4. Abstraction stress — P0 / P1 / P2 findings

### P0 — would prevent correct CN CIT computation without substrate help

**P0-1: `:op :schedule-override` does not exist.** §3.3 above lazily
shows a `:schedule-override` consequence on the regime-rate provisions,
but the actual closed `:op` set today is `{:base-add :base-deduct
:credit :surtax}` (note 105 + ADR-101). The DE CIT provider sidesteps
this by reading the rate parameter directly inside the provider code —
it never expresses "the schedule's rate IS this parameter" as a
provision. For CN with three regime-elective rates (25 / 15 / 5
effective) — and ultimately for IN with old/new IT regimes, FR with PME
rate, US with FDII rate, etc. — we want the **rate itself** to be
encoded as a regime-bound provision.

The natural extension is a fifth `:op`:

```
:op :schedule-override  — replace the schedule's rate (or whole schedule)
                          with the consequence amount/data
```

**Decision needed.** Either:

- (a) **Extend the `:op` set** to `{:base-add :base-deduct :credit
  :surtax :schedule-override}`. The fold in `apply-adjustments` rejects
  it (correctly — `:schedule-override` is consumed by the provider
  ABOVE the fold). The provider does a separate pass for
  `:concept :schedule-override` provisions, picks the highest-priority
  applicable one, and uses that rate.
- (b) **Keep `:op` closed** and continue the DE pattern — the provider
  reads `CN.EIT.rate` / `CN.EIT.hnte-rate` directly from parameters
  based on `ctx :tax-unit :regime`. Statute-as-data degrades a step:
  the *rate values* are queryable, but the *regime-to-rate mapping* is
  inside the provider.

Option (a) is cleaner for the auditable-explanation story ("WHY did this
taxpayer get 15 %?" → answer = a provision citation, not a hardcoded
case-on-regime). Option (b) is what DE already does and it works.

**Recommendation: defer to first ADR-101 consumer that needs it.** If CN
is that consumer (and IN's old-vs-new regimes will be the next), draft
the `:schedule-override` `:op` as an ADR-101 minor revision (1-line
vocabulary extension + the provider-side query convention). Total cost:
~10 LOC.

**P0-2: Multi-province allocation has no substrate support.** The CCSV
50/50 split + three-factor branch fan-out (§1.8) is a *post-schedule*
operation that produces **N+1 outputs** (HQ liability + branch
liabilities, each tagged with a provincial collector). The current
`TaxReturnFacts` `:components` shape supports ONE component per `:kind`
× `:authority` pair, so we'd build:

```
:components [{:kind :corporate-income-tax :authority :cn-sat-hq           :liability 3,450,000}
             {:kind :corporate-income-tax :authority :cn-sat-bj-branch    :liability 2,088,630}
             {:kind :corporate-income-tax :authority :cn-sat-sz-branch    :liability 1,361,370}]
```

This works! The `:authority` field already varies per component (per
ADR-099 + note 102 §2 — "*lets one return fan out across governments
(CA federal :cra + provincial :bc; US 50 states)*"). The CCSV
allocation is *one* component (the consolidated 25 % × base) FANNED OUT
across branches at the `TaxReturnPostingBuilder` stage, not multiple
schedule computations.

**Two implementation options:**

- (a) The provider computes the consolidated liability AS ONE
  `:components` entry, then post-processes by allocating across the
  branch list (read from `ctx :tax-unit :branches`). The `:components`
  vector ends with N+1 entries; allocation is provider-side logic, not
  substrate.
- (b) A new helper `kontor.period-tax-provider/allocate-component` that
  takes a single component + an allocation function (3-factor weights,
  or a custom one for state-by-state US apportionment) and returns a
  vector of weighted sub-components. **Substrate add.**

**Recommendation: do (a) inside the provider for CN.** Option (b) is
attractive once US state apportionment lands (note 113 §3 / ADR-077 has
this for payroll already) — but generalising prematurely from one
consumer is YAGNI. The provider already has the data it needs (`:branches`
in ctx); the allocator is ~15 LOC.

This is a **P0 for the provider**, but **P1 for the substrate** — i.e.
no substrate change is required, but the CN provider must do work the
DE provider does not.

### P1 — fits the substrate but stresses the convention

**P1-1: Regional preferential rate as a regime — but the regime is
location-bound, not taxpayer-elected.** Western Region / Hainan / Lingang
15 % rates are not free elections — they depend on the enterprise's
location AND its main-income-from-encouraged-industries ≥ 60 % test.
The DE CIT pattern uses `:tax-unit :hebesatz` for location-varying GewSt
multiplier; the natural CN pattern is `:tax-unit :region` =
`:hainan-ftp` / `:western-region` / `:lingang` / `:standard` plus a
substantive-operations attestation (boolean fact). The provider then
sets the regime in ctx based on the location.

This is *not* substrate stress — it's just convention: the regime
**code** maps 1:1 to a regional carve-out, even though the taxpayer
didn't "elect" it freely. The `:regime/extends :cn-eit-standard` chain
correctly inherits all standard provisions and overrides only the rate.
**Substrate fits.**

**P1-2: SLPE qualification cliff.** The 5 % effective rate disappears
*entirely* the instant taxable income exceeds RMB 3 M (cliff, not
phase-out). The substrate's `[:leq [:inputs :taxable-income] 3000000M]`
condition handles this correctly — the provision fires only at-or-below
the cap; above it, the standard regime kicks in. **But**: the condition
reads `:taxable-income`, which is the OUTPUT of the base-adjustment
fold, not an input. Currently `kontor.statute/applicable-provisions`
runs once over `ctx` — it doesn't loop after base-adjustments compute
the actual taxable income.

**Workaround.** The provider must compute taxable income FIRST (book
profit + adds − deducts, ignoring the SLPE rate question), THEN re-query
provisions with the computed `:taxable-income` in ctx, THEN apply the
right schedule. This is a **two-pass** pattern (pass 1: base-adjust;
pass 2: schedule). DE only has one pass because its rates are not
income-conditioned. CN SLPE makes this a substrate-discipline
clarification, not a substrate change.

The pattern is general — IN's small-company surcharge, US's QBI
phase-out, FR's IS-PME income cap all have the same shape. **Worth
documenting in ADR-101 as a convention** ("provisions whose condition
reads computed-base facts must be queried after the base-fold").

**P1-3: R&D negative-list as `[:not [:in :industry [...]]]`.** §3.3
shows the R&D provision with a six-industry exclusion list inline in
the condition. Cleaner alternative: factor the list as a parameter
(e.g. `CN.EIT.rd-negative-list :unit :enum-set`) so the data is
discoverable. But the substrate doesn't currently have an enum-set
`:parameter/unit`. **Defer.** The inline `[:in ...]` predicate works
today.

**P1-4: Loss carryforward (5 / 10 years).** Same status as note 120
P1-4 (DE Mindestbesteuerung): blocked on note-105 frontier 2 (the carry
primitive). Track in the same followup list, not in the CN ship.

### P2 — defer, document as known gaps

- **P2-1: Withholding tax — out of scope here.** Belongs in the
  transaction-tax provider family (ADR-071), encoded against the
  payer-side payment posting. Document the boundary.
- **P2-2: Multi-currency in CCSV.** A foreign-branch flag exists but
  foreign branches are excluded from CCSV allocation (Art. 5). No
  multi-currency math needed for the allocation itself; the
  consolidated base is already in RMB.
- **P2-3: Cai Shui circular renewal cadence.** Most of CN's CIT
  preferential rates sunset every 2-5 years and get extended by a new
  Cai Shui. The `:effective-until` parameter-value axis handles this
  cleanly — but it requires diligent maintenance. Same as DE's
  Investitionssofortprogramm KSt-rate sunset (note 120 P1-3). Document
  the pattern; no substrate change.
- **P2-4: Tax treaty rates on non-resident WHT.** Out of scope for
  the period-tax substrate. Belongs in `TaxRateProvider` with treaty-
  per-payee facts.

## §5. Minimal substrate adds

**Honest answer: none required for a first CN CIT ship, but ONE
small extension is highly attractive.**

The `:op :schedule-override` extension to the ADR-101 closed `:op`
vocabulary (P0-1) is appealing because:

- It expresses "this rate IS the rate" as a citable provision (which
  IS the goal of statute-as-data — the DE pattern degrades to "this
  is what the provider's code chose").
- It would also clean up IN old-vs-new regime (the very next consumer
  after CN) and FR IS-PME elective rate.
- It is a **1-keyword vocabulary extension**, additive, no breaking
  changes. ~10 LOC in the provider-side pass (read all applicable
  `:schedule-override`s, pick the highest-priority one, use its rate).

**Recommendation: draft `:schedule-override` as an ADR-101 minor
revision when CN CIT ships.** Either include in the CN CIT ADR (ADR
number TBD) or fold into a follow-up "ADR-101 vocabulary additions"
revision that groups same-class extensions.

Everything else is provider-side logic (CCSV fan-out, multi-pass
querying for SLPE cliff), not substrate.

## §6. Sources

**Statute text (canonical, official)**

- [EIT Law of the PRC — full text on chinatax.gov.cn (Zhejiang Tax Bureau English mirror)](https://zhejiang.chinatax.gov.cn/art/2024/10/30/art_26318_626886.html)
- [EIT Law — Wikisource English translation](https://en.wikisource.org/wiki/Enterprise_Income_Tax_Law_of_the_People's_Republic_of_China)
- [EIT Law — China-Tax.net PDF (2008 edition)](https://www.china-tax.net/static/upload/files/Corporation%20income%20tax%20(China)/PRC_CIT%20Law%202008.pdf)
- [EIT Law — chinatax.gov.cn English summary](https://www.chinatax.gov.cn/eng/c101280/c5099666/content.html)
- [SAT Bulletin 57/2012 — 跨地区经营汇总纳税企业所得税征收管理办法 (full text on www.gov.cn)](https://www.gov.cn/gongbao/content/2013/content_2376213.htm)
- [SAT Bulletin 57/2012 — shui5.cn annotated version](https://www.shui5.cn/article/47/57093.html)
- [Shanghai Tax Bureau guidance on Bulletin 57](https://shanghai.chinatax.gov.cn/zcfw/zcfgk/qysds/201303/t402316.html)
- [Shanghai Tax Bureau 2025 FAQ on consolidated tax filing](https://shanghai.chinatax.gov.cn/zcfw/rdwd/202508/t477386.html)

**MoF / SAT Cai Shui circulars (the main rate / preferential history)**

- [Cai Shui [2015] No. 119 — R&D super-deduction reform / negative list (Lexology)](https://www.lexology.com/library/detail.aspx?g=f1b69aa8-0e16-448a-abca-2284ec19f806)
- [Cai Shui [2018] No. 76 — HNTE / TSME extended 10-year loss carryforward (EY)](https://globaltaxnews.ey.com/news/2018-5912-china-grants-additional-five-years-to-loss-carryforward-period-for-certain-technology-enterprises)
- [Cai Shui [2023] R&D super-deduction — Zhejiang Tax Bureau English](https://zhejiang.chinatax.gov.cn/art/2025/1/13/art_26318_630434.html)
- [Cai Shui [2023] Circulars No. 5 + No. 6 — SLPE 5 % extension](https://www.internationaltaxreview.com/article/2bjuqw2o64p35k54k6dxc/local-insights/china-enhances-tax-relief-for-innovation-and-small-businesses)

**Commercial commentary**

- [PwC China Tax Facts and Figures 2025 (PDF)](https://www.pwccn.com/en/tax/publications/people-republic-of-china-tax-facts-2025.pdf)
- [PwC Worldwide Tax Summaries — China CIT rates](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/taxes-on-corporate-income)
- [PwC Worldwide Tax Summaries — China CIT deductions](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/deductions)
- [PwC Worldwide Tax Summaries — China CIT branch income](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/branch-income)
- [PwC Worldwide Tax Summaries — China CIT income determination](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/income-determination)
- [PwC Worldwide Tax Summaries — China CIT incentives](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/tax-credits-and-incentives)
- [PwC Worldwide Tax Summaries — China withholding tax](https://taxsummaries.pwc.com/peoples-republic-of-china/corporate/withholding-taxes)
- [PwC CN — HNTE service page](https://www.pwccn.com/en/services/tax/china-rd-incentive-service/high-and-new-technology-enterprise.html)
- [PwC CN — R&D super-deduction service page (Chinese)](https://www.pwccn.com/zh/services/tax/china-rd-incentive-service/r-d-expense-super-deduction.html)
- [KPMG China 2018 Tax Profile (PDF)](https://assets.kpmg.com/content/dam/kpmg/xx/pdf/2018/08/china-2018-updated.pdf)
- [Deloitte China Tax Analysis](https://www.deloitte.com/cn/en/services/tax/perspectives/tax-analysis.html)
- [China Briefing — CIT in China guide](https://www.china-briefing.com/doing-business-guide/china/taxation-and-accounting/corporate-income-tax-in-china)
- [China Briefing — CIT incentives overview](https://www.china-briefing.com/news/what-are-the-major-cit-incentives-offered-in-china/)
- [China Briefing — Hainan FTP preferential policies](https://www.china-briefing.com/news/hainans-preferential-tax-policies-cit-iit/)
- [China Briefing — Western Region / Lingang / Qianhai / Hengqin 15 % rate guide](https://www.china-briefing.com/news/15-percent-corporate-income-tax-china-development-zones-qualification-criteria/)
- [China Briefing — R&D super-deduction explainer](https://www.china-briefing.com/news/china-rd-expenses-pre-tax-super-deduction-explainer/)
- [Acclime China — SLPE preferential income tax policies](https://china.acclime.com/news-insights/preferential-income-tax-policies-micro-small-enterprises/)
- [Acclime China — R&D pre-tax additional deduction](https://china.acclime.com/news-insights/pre-tax-additional-deduction-rd-expenses/)
- [Hawksford — HNTE tax incentives guide](https://www.hawksford.com/insights-and-guides/china-business-guides/tax-incentives-for-high-tech-enterprises-in-china)
- [MS Advisory — China Corporate Income Tax 2026](https://msadvisory.com/chinas-corporate-income-tax/)
- [MS Advisory — HNTE 15 % rate guide](https://msadvisory.com/hnte-china/)
- [MS Advisory — China tax rates 2026](https://msadvisory.com/china-tax-rates/)
- [MS Advisory — China withholding tax guide](https://msadvisory.com/withholding-tax-in-china/)
- [Vita Liberta — China CIT 2026 rates / incentives / payment rules](https://www.vitaliberta.hk/en/china-cit-corporate-tax-rates-incentives-2026/)
- [KWM — Hainan FTP preferential tax policies issued](https://www.kingandwood.com/us/en/insights/latest-thinking/hainan-free-trade-port-preferential-tax-policies-issued.html)
- [International Tax Review — China innovation + SLPE relief (2024)](https://www.internationaltaxreview.com/article/2bjuqw2o64p35k54k6dxc/local-insights/china-enhances-tax-relief-for-innovation-and-small-businesses)
- [Mayer Brown — Asia Tax Bulletin Autumn 2025](https://www.mayerbrown.com/en/insights/publications/2025/10/asia-tax-bulletin-autumn-2025)

**kontor references**

- ADR-101 — statute-as-data substrate (doc/decisions.md)
- ADR-099 — `PeriodTaxProvider` (doc/decisions.md)
- ADR-104 — DE CIT (the first end-to-end ADR-101 consumer)
- ADR-085 — CN payroll (the existing CN module, IIT only)
- Note 102 — `PeriodTaxProvider` design
- Note 105 — adjustment-layer algebra
- Note 108 — DE CIT fit (the substrate-fit template for this note)
- Note 119 — ADR-101 draft + 6 design choices
- Note 120 — DE CIT baseline review (statute-fidelity audit template)

---

End of note 123.

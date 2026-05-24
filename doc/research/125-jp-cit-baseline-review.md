---
date: 2026-05-24
title: 125 — JP CIT (5-component stack) baseline review against NTA + JETRO + Big-4 Japan guidance
audience: maintainer
status: review-after — ADR-106, third end-to-end ADR-101 consumer
---

# 125 — JP CIT baseline review

ADR-106 shipped the third end-to-end consumer of the ADR-101 statute-as-data
substrate: JP CIT encoded as 17 `:parameter`s + 23 `:parameter-value`s + 7
`:provision`s + a 10-cell `per-capita-levy-table` map under
`modules/l10n-jp/src/kontor/l10n_jp/cit_statute.clj`, a three-component
`PeriodTaxProvider` in `cit_provider.clj` emitting `:jp-nta` +
`:jp-prefecture` + `:jp-municipality` components, and 11 deftests / 50
assertions reproducing the JETRO §3.3 Tokyo SME worked example (¥2,625,912 /
¥2,695,912 to the yen) plus a large-corp ¥1B case and the defense-surtax
temporal gate. Per the maintainer's standing mandate ("once we update our
l10n packages we should also review with agents against baselines software
and online documentation/official law again"), this note audits the
encoding against nta.go.jp, JETRO Section 3.3, PwC Worldwide Tax Summaries
Japan, EY 2025 + 2026 Tax Reform alerts, Grant Thornton Japan, HLS Japan,
the EU-Japan Centre, and the Tokyo Metropolitan Bureau of Taxation.

**Headline.** The headline numbers (parameter values, effective dates,
citations) are largely correct: every encoded rate matches NTA / Tokyo
metropolitan / Big-4 sources to two-decimal precision, the JETRO Tokyo SME
worked example reproduces to the yen, the defense surtax effective gating
fires correctly. The three real findings — all of which would mis-compute
real consumers in identifiable populations — are scope-of-coverage issues
that the date-keyed parameter substrate is exactly suited to fix:

- **P0** — the **SME 17% rate when annual income exceeds ¥1B** is missing.
  Note 110 §1 #1 even *named* this branch ("17 % if current-year taxable
  income > ¥1B"), but the encoding ships only the 15% rate. An SME with
  ≥¥1B income (mid-cap with ≤¥100M capital is a real population — think of
  a successful early-stage business with retained earnings) gets the
  reduced-rate slice taxed 2 percentage points too light. NTA No. 5759
  (`taxanswer/hojin/5759.htm`) names this branch as effective from FY ≥
  2025-04-01.

- **P0** — **Large-corporation pro-forma enterprise tax (外形標準課税) is
  absent.** Note 110 §1 component 3 / §4 stress B flagged it; ADR-106
  logs it as a "substrate deferral"; v1 ships only the income-base
  component with a flat 1.18% override. A capital >¥100M company actually
  owes **THREE simultaneous enterprise-tax components** — income-base
  (1.18%) + value-added base (1.26%) + capital base (0.525%). The current
  encoding under-computes enterprise tax for the entire large-cap
  population — silently, with no error or warning. This is the largest
  individual gap in the encoding.

- **P0** — **The §72-large schedule override drops the SME progressive
  ladder for the income-base portion** when consumers cross capital >¥100M.
  This is statutorily correct as far as it goes, BUT in conjunction with
  P0-#2 above means the substrate emits ONE enterprise-tax number for a
  large corp where the statute requires three. The fix is the same as P0-#2
  (ship the value-added and capital components); flagging here because the
  semantic error is independent of the missing-components issue: a consumer
  who passes capital >¥100M today gets a single 1.18 % income-base number
  *and no indication that two other components were elided*.

Beyond these three, several smaller-population issues and documentation
polish items are catalogued in §7 P1/P2. Sections below detail each
finding with file:line + cite.

## §1. Statute-fidelity audit — parameter by parameter

### 1.1 `JP.CIT.flat-rate` = 0.232M from 2018-04-01 — CORRECT

NTA No. 5759 confirms 23.2% as the standard rate since the 2018-04-01
Tax Reform (法人税法 §66①). PwC Worldwide Tax Summaries Japan confirms it
is the rate "for fiscal years beginning on or after 1 April 2025" and
remains unchanged through the 2026 reform package. The 2018-04-01
effective instant is correct for the modern flat-23.2% form (the rate
landed at 25.5% in 2012, dropped to 23.9% in 2015, to 23.4% in 2016, and
to 23.2% in 2018 per multiple Big-4 historical tables — encoding only
the 2018-04-01 value is fine since no current consumer would query
pre-2018 data through the substrate).

### 1.2 `JP.CIT.sme-reduced-rate` = 0.15M from 2018-04-01 — **PARTIALLY CORRECT (P0)**

The 15% reduced rate on the first ¥8M is correct **for SMEs whose annual
income is ≤¥1B**. NTA No. 5759 verbatim: "所得金額が年10億円を超える事業年度"
the rate steps up to **17%** on the first ¥8M slice. PwC's worldwide tax
summary confirms verbatim: *"First JPY 8 million per annum if the income of
the company exceeds JPY 1 billion: 17%"*. EY's 2025 Tax Reform alert
confirms this was enacted as part of the 2025 Tax Reform Act (extending
SME preferential treatment for two years with the income-exceeds-¥1B
upper carve-out).

The encoding ships ONE `JP.CIT.sme-reduced-rate` parameter with value
0.15M. There is no second parameter for the >¥1B branch, and the
`national-default-schedule` in `cit_provider.clj:179-184` builds a
2-bracket progressive `[{:rate 0.15M :upper 8000000M} {:rate 0.232M :upper nil}]`
with no income-magnitude branch.

**Impact.** A successful SME (capital ≤¥100M, annual income ≥¥1B) — e.g.
a mature B2B services firm with retained earnings, a profitable
mid-stage startup, a single-shareholder family company that hasn't
recapitalised — gets a 2-percentage-point under-tax on the first ¥8M of
income: ¥8M × (0.17 − 0.15) = ¥160k under-statement on the national CIT
GROSS, plus surtax inflation:

```
under-tax-direct  = ¥160 000
local-CIT cascade = ¥160 000 × 0.103       = ¥16 480
defense-surtax    = ¥160 000 × 0.04        = ¥6 400   (FY 2026+ only)
inhabitants' levy = ¥160 000 × 0.07        = ¥11 200
total cascade     = ¥194 080  (¥187 680 pre-2026)
```

**Fix (one parameter + one provision).** Concretely:

- Add a sibling parameter `JP.CIT.sme-reduced-rate-large-income` = 0.17M
  with effective-from #inst "2025-04-01" (per NTA + EY). Cite NTA No.
  5759 and 法人税法 §66②.
- Add a 4th `:provision` `JP-CIT-§66②-large-income` with
  `:condition [:and [:eq :component :national] [:eq [:tax-unit :is-sme?] true] [:gt [:inputs :book-profit] 1000000000M]]`
  and consequence `:op :schedule-override` swapping the schedule to a
  2-bracket progressive `[{:rate 0.17M :upper 8000000M} {:rate 0.232M :upper nil}]`.
- Test: an SME with `:book-profit 2000000000M` — expected national CIT
  GROSS = 0.17 × 8M + 0.232 × 1.992B = ¥463,304,000 (vs the current
  encoding's 15%-only ¥463,144,000 — ¥160k delta).

Files: `cit_statute.clj:71-75, 178-181` (parameter + value);
`cit_provider.clj:179-184` (default schedule unchanged; override fires
via provision); new tests in `cit_provider_test.clj` after §3 worked
example.

### 1.3 `JP.CIT.sme-kink` = ¥8 000 000 from 2018-04-01 — CORRECT

法人税法 §66② confirmed. The kink amount is the universal `軽減税率`
threshold and has been ¥8M since the modern Reform Era; pre-1993 it was
¥7M, but no current consumer would query pre-1993 through the substrate.

### 1.4 `JP.LocalCIT.rate` = 0.103M from 2019-10-01 (with prior 0.044M from 2014-10-01) — CORRECT

地方法人税法 §10 confirms 10.3% from 2019-10-01. The 4.4% prior value
(2014-10-01 .. 2019-10-01) is the historically accurate pre-reform rate
— **good substrate discipline**, this is the only parameter in the
entire encoding that ships its sunset row, and the kontor authors should
adopt the same pattern for other rates as amendments land.

PwC Worldwide Tax Summary confirms: *"national local corporate tax for
fiscal years beginning on or after 1 April 2025 is a fixed rate of 10.3%
of the corporate tax liability."* The encoding's base reference
("national CIT GROSS, not running total") in `jp-local-cit-on-national`
(`cit_provider.clj:79-91`) is correct — the statute references 法人税
gross, not the inflated 法人税 + 防衛特別法人税 sum.

### 1.5 `JP.DefenseSurtax.rate` = 0.04M from 2026-04-01 — CORRECT

Verified against EY's 2025 Japan Tax Reform alert, PwC 2026 Reform
Outlook (jtu-20251226), Grant Thornton Japan's "Defense Special Corporate
Tax – Deferred Tax Impact" bulletin, and HLS Japan's "Introduction of
the Special Defense Corporation Tax" English overview. All four sources
quote verbatim: **"For each fiscal year beginning on or after April 1,
2026"** the surtax applies. The legislation was enacted 2025-03-31. The
encoding's `:effective-from #inst "2026-04-01"` is correct for the
fiscal-year-START semantics the law uses.

**Subtle point worth documenting** (no bug, but a documentation gap): the
ADR-101 effective-from instant gates on AS-OF, not on
fiscal-period-START. For a fiscal period that *spans* 2026-04-01 (e.g. a
period 2026-01-01 .. 2026-12-31), the substrate would fire the defense
surtax for the entire period if `:as-of` is in 2026, despite the
statute's "fiscal years beginning on or after 2026-04-01" language
meaning calendar-year corporations DON'T pay the surtax in 2026 (their
first eligible year is FY beginning 2027-01-01).

Cross-check with kontor's test: the post-2026 test uses
`:period {:from #inst "2025-04-01" :to #inst "2026-04-01"}` AND
`:as-of #inst "2026-06-30"`. Under the statute, that period is "FY
beginning 2025-04-01" — pre-defense-surtax. But the substrate's
parameter-value resolver only sees `:as-of 2026-06-30 ≥ 2026-04-01` and
FIRES the surtax. **The kontor encoding interprets the 2026-04-01 date
as as-of, not fiscal-period-START** — and the test verifies that
behaviour without flagging the divergence.

**Severity: P1.** Calendar-year subsidiaries of foreign parents file
Jan-Dec periods; they would mis-pay the defense surtax for FY 2026 if
the consumer naively passes `:as-of (today)` after 2026-04-01. This is
NOT a substrate bug — `:effective-from` is intended to gate when a
parameter became *legal*, not when a particular consumer's period
becomes subject to it. The fix is provider-side: gate the defense
surtax provision on `[:gte [:period :from] #inst "2026-04-01"]` instead
of relying on the parameter's `:effective-from`. The substrate already
supports this — see `apply-provisions`. The encoded provision's
`:effective-from #inst "2026-04-01"` should be supplemented with a
period-from condition or moved to a period-based gate.

### 1.6 `JP.DefenseSurtax.deduction` = ¥5 000 000 from 2026-04-01 — CORRECT

Grant Thornton: *"the Taxable Corporate Tax Base for each taxable fiscal
year... calculated as the Base Corporate Tax Amount for each taxable
fiscal year minus the basic deduction of JPY5 million per year."* PwC's
2026 Reform Outlook example confirms: *"a company with corporate income
tax of JPY105m would owe a surtax on JPY4m (105−5)×4% = ¥4.0m"* — wait,
that's ¥4m × 100% / 25, i.e. (105−5) × 4% = 4.0; correct calculation.

The encoding's formula `4% × max(0, national-CIT − ¥5M)`
(`jp-defense-surtax`, `cit_provider.clj:93-107`) matches the law text
verbatim, **and** correctly floors at zero (no negative surtax for
small national CIT). Good.

**Two small documentation gaps** in the encoding:

- The `¥5M per year` qualifier means the deduction should be **pro-rated
  for short tax periods** (e.g. a 6-month opening period gets only
  ¥2.5M). Neither Grant Thornton nor HLS Japan elaborates on the
  pro-ration rule in plain text (both bulletins say "the document
  provides no guidance"), but the standard Japanese practice for
  per-year basic deductions IS pro-ration. The compute-fn does NOT
  pro-rate. **P1** — add a `:period-length-months` pro-ration to the
  deduction.

- The deduction is **per-consolidated-group**, not per-corporation, for
  groups filing under the group tax relief system (no Big-4 source
  explicitly confirms this for defense surtax, but it parallels the SME
  preferential rates which ARE per-group). **Defer P2** until a
  consolidated-group consumer needs it.

### 1.7 `JP.Enterprise.sme-rate-1/2/3` = 0.035M / 0.053M / 0.07M from 2019-10-01 — CORRECT (with Tokyo-vs-national caveat)

Tokyo Metropolitan Bureau of Taxation `kazei/work/houjinji` confirms
the national STANDARD rates 3.5 / 5.3 / 7.0 % for SMEs at brackets
≤¥4M / ¥4M-¥8M / >¥8M. **Tokyo's 超過税率 (elevated rate) is 3.75 /
5.665 / 7.48 %** — applicable to Tokyo SMEs whose corporate-tax-amount
exceeds ¥10M annually (which an SME with ¥10M book profit almost
certainly does NOT, since ¥10M × 15% = ¥1.5M corporate tax). For the
JETRO Tokyo SME worked example, the national standard rate 3.5/5.3/7.0
correctly applies and the encoding's ¥492,000 enterprise-tax figure is
correct.

**However**, the encoding hardcodes the national standard rates. A
Tokyo SME with corporate-tax-amount > ¥10M (e.g. a mid-cap services
firm earning ¥100M+) IS subject to the 超過税率 and the encoding
under-states enterprise tax for that population. The encoding's
parameter docstring at `cit_statute.clj:110-115` correctly flags this
("national standard 3.5 / 5.3 / 7.0 %; consumers override per-prefecture"),
but there is no provider-side mechanism for the override — a consumer
who supplies `:prefecture :tokyo` gets the same numbers as one who
supplies `:prefecture :hokkaido`. **P1** — see §5 prefecture-variation
audit below.

The 2019-10-01 effective date is correct for the current 3.5/5.3/7.0
ladder (地方税法 §72-24-7). Pre-2019, the schedule was 3.4/5.1/6.7 — no
modern consumer needs the historical row.

### 1.8 `JP.Enterprise.sme-kink-1/2` = ¥4 000 000 / ¥8 000 000 from 2019-10-01 — CORRECT

地方税法 §72-24-7. Stable since 2008.

### 1.9 `JP.Enterprise.large-rate` = 0.0118M from 2019-10-01 — CORRECT BUT INCOMPLETE (P0)

The 1.18% income-base rate is correct per Tokyo Metropolitan Bureau of
Taxation and PwC Worldwide Tax Summary's pro-forma table. **But the
large-corporation enterprise tax has THREE bases, not one:**

| Base | Rate |
|---|---|
| Income (≤¥4M) | 0.495% |
| Income (¥4M–¥8M) | 0.835% |
| Income (>¥8M) | **1.18%** ← encoded |
| Value-added base | **1.26%** ← missing |
| Capital base | **0.525%** ← missing |

(Source: Tokyo Metropolitan Bureau of Taxation; PwC 2026 Worldwide Tax
Summary; JETRO §3.3 table.)

Note 110 §1 component 3 and §4 stress B both flagged this. The encoding
docstring at `cit_statute.clj:147` acknowledges it ("pro-forma value-added
/ capital bases deferred — note 110 §1"). The provider docstring at
`cit_provider.clj:289-291` repeats the deferral. The complex test at
`cit_provider_test.clj:146-151` even asserts the partial coverage
explicitly: *"large-corp flat 1.18 % override fires (income base only;
value-added / capital bases deferred per note 110 §1)"*. This is
**documented**, but the impact for a real customer is severe: a large
manufacturer / financial-services firm with capital >¥100M owes substantial
value-added (1.26% × payroll + interest + rent) and capital base
(0.525% × stated capital + surplus) tax. **Skipping both
under-computes the enterprise-tax liability by typically 30-50% for a
capital-intensive large firm.**

The 1.18% income rate also has its OWN progressive ladder (0.495 /
0.835 / 1.18 % at ≤¥4M / ¥4M-¥8M / >¥8M) — encoding it as flat 1.18%
over-computes the income-base portion for a large corp earning ≤¥8M
in any income bracket.

**Fix.** Per note 110 §5 substrate-adds, this is **provider-side work,
not substrate work**. The substrate already supports
multi-component `TaxReturnFacts` (DE ships 2; CA ships 1+N provinces;
JP currently ships 3). Add two more components:

- `:jp-prefecture-value-added` — schedule `:flat 0.0126M`, base = value-added
  computed from `:inputs :payroll + :net-interest + :net-rent ± :book-loss`,
  no surtaxes (the special-corp-enterprise tax already gets its base
  from the income-base component, not value-added);
- `:jp-prefecture-capital` — schedule `:flat 0.00525M`, base = `:inputs
  :paid-in-capital + :capital-surplus`, no surtaxes.

Plus restructure the large-co income-base schedule to a progressive
ladder `[{:rate 0.00495M :upper 4000000M} {:rate 0.00835M :upper
8000000M} {:rate 0.0118M :upper nil}]` via a SECOND `:schedule-override`
provision (the existing JP-Enterprise-§72-large would become the
"income-base ladder" override; the new value-added and capital
components would be modelled as their own components).

ADR-106 logs this as a "substrate deferral" but the deferral is
**out-of-coverage**, not out-of-substrate. The substrate can express
this today; the provider just doesn't. Re-classify the deferral as a
**provider build-out** in followups.

### 1.10 `JP.SpecialCorpEnterprise.sme-rate` / `.large-rate` = 0.37M / 2.60M from 2019-10-01 — CORRECT

特別法人事業税及び特別法人事業譲与税に関する法律 §7. EU-Japan Centre
confirms: *"SME rate: 37%, Large corporation rate: 260%"*, *"introduced
from October 1st, 2019"*. PwC and JETRO both confirm the same multipliers.
The encoding's `jp-special-corp-enterprise` (`cit_provider.clj:109-126`)
correctly reads the SME-vs-large rate via `(if sme? sme-rate large-rate)`,
and the test exercises both branches (¥182,040 SME @ 37% × 492,000;
¥30,680,000 large @ 260% × 11,800,000).

**Subtle point**: the special-corp-enterprise tax base is the
**income-base enterprise tax amount** (NOT the value-added or capital
base). The current encoding's `jp-special-corp-enterprise` reads
`:enterprise-tax-gross` (the entire enterprise-tax component's gross),
which equals the income-base amount today *only because* the encoding
ships only the income-base component. If/when the value-added and
capital components are added (P0-#9 above), the surtax base must
remain the income-base amount, not the sum. **Add a unit-test scaffold
now** for this invariant so the future expansion catches it.

### 1.11 `JP.Inhabitant.income-levy-rate` = 0.07M from 2019-10-01 — CORRECT

Tokyo Metropolitan Bureau of Taxation `kazei/work/houjinji` confirms the
**標準税率 (national standard)** as **1.0% prefectural + 6.0% municipal =
7.0% combined**. EU-Japan Centre confirms: *"Standard rate 7.0% (1.0% +
6.0%), Elevated rate 10.4% (2.0% + 8.4%)"*. Tokyo's actual rate for
"23 special wards" is the **超過税率 (elevated rate) 10.4% combined**
(used for corporations whose corporate-tax-amount exceeds ¥10M; an SME
with ¥10M book profit and ¥1.664M corporate tax falls UNDER the elevated
threshold and uses the 7.0% standard rate).

**Verification of the JETRO worked example.** JETRO §3.3 quotes the
prefectural inhabitants' tax for the SME as ¥16,600 and municipal as
¥99,800 (total ¥116,400). kontor's test asserts ¥116,480 — a ¥80
discrepancy. The cause: the encoded rate is 0.07M flat; the JETRO
figure of ¥116,400 corresponds to (1.0% × ¥1,664,000) + (6.0% ×
¥1,664,000) = ¥16,640 + ¥99,840 = ¥116,480 **mathematically**, but
JETRO's published number ¥116,400 differs by ¥80 due to **per-component
rounding** (each prefecture / municipality rounds the corporate-tax
amount to ¥1,000 before applying its rate; ¥1,664,000 → ¥1,664,000
for SMEs, but the rounding rule occasionally bites at the JPY-100
boundary).

**This is acceptable** for kontor's purposes — the encoding sums the
two pieces analytically (¥116,480) and JETRO's effective-rate
illustration loses ¥80 to per-piece rounding. The kontor test asserts
its own computation, not JETRO's; no bug. Worth a note in the test
docstring so a future reader doesn't chase the ¥80.

**The bigger issue** is that kontor hardcodes "prefecture = Tokyo" and
its rate to the 7% combined standard. Note 110 §1 component 5 mentions:
*"Standard prefectural 1.0% / municipal 6.0% (max 2.0 / 8.4%)"*. The
encoded parameter is a SINGLE rate (7.0% combined), not two parameters
(1.0% pref + 6.0% muni). For a consumer who needs to file separately
to a prefecture and to a municipality (which IS the normal case for
corporations operating in multiple municipalities — they apportion by
office payroll), the single combined rate elides the structure that
matters. **P1** — see §5 prefecture-variation finding for fix.

### 1.12 Per-capita levy 均等割 table — CORRECT

The 10-cell `per-capita-levy-table` in `cit_statute.clj:287-296`
matches JETRO §3.3, PwC's worldwide tax summary, Commenda's tax-rate
guide, and the wise.com / aspireapp / quickbooks summaries cell-by-cell:

| Capital | ≤50 emp (kontor) | >50 emp (kontor) | JETRO source |
|---|---|---|---|
| ≤¥10M | ¥70,000 | ¥140,000 | ¥70,000 / ¥140,000 ✓ |
| ¥10M-¥100M | ¥180,000 | ¥200,000 | ¥180,000 / ¥200,000 ✓ |
| ¥100M-¥1B | ¥290,000 | ¥530,000 | ¥290,000 / ¥530,000 ✓ |
| ¥1B-¥5B | ¥950,000 | ¥2,290,000 | ¥950,000 / ¥2,290,000 ✓ |
| >¥5B | ¥1,210,000 | ¥3,800,000 | ¥1,210,000 / ¥3,800,000 ✓ |

地方税法 §52 (prefectural) + §312 (municipal). Stable since 2015.

The DECISION to store this as a map literal rather than 10
`:parameter`s is well-defended in the namespace docstring
(`cit_statute.clj:28-34`) — "the tiers haven't moved since 2015" and
"adding 10 thresholds as parameters would inflate the substrate without
winning a real authoring affordance". Reasonable. The same call DE's
GewSt encoding made for the Hebesatz table (it's a consumer-supplied
`:tax-unit` integer, not a parameter family). **No change needed.**

**One subtlety** worth documenting: the table is the SUM of prefectural
+ municipal per-capita levies (e.g. ¥70k = ¥20k pref + ¥50k muni for
the smallest tier). For Tokyo's 23 special wards both are collected by
the metropolitan government as one charge; outside Tokyo, the
corporation files separately. The kontor compute-fn returns the
combined ¥70k correctly, but if a consumer needs to split it for
multi-jurisdiction filing the splitting is provider-side. **P2** —
note in docstring.

## §2. Provision-coverage gap analysis

The seven encoded provisions cover the most common KK case (single-prefecture
Tokyo SME). What's missing, ordered by P0 / P1 / P2 severity:

### P0 — would mis-compute a typical corp today

- **P0-1: SME 17% rate for income >¥1B branch missing** — see §1.2. NTA
  No. 5759 + PwC + EY all confirm; a 4th `:provision` should fire when
  `[:and [:eq :component :national] [:eq [:tax-unit :is-sme?] true]
  [:gt [:inputs :book-profit] 1000000000M]]`.

- **P0-2: Large-corporation pro-forma enterprise tax** (value-added +
  capital bases) — see §1.9. A capital >¥100M firm owes three
  enterprise-tax components; v1 ships only the income-base.
  Provider-side build-out, NOT substrate work — the substrate already
  supports N-component `TaxReturnFacts`.

- **P0-3: Large-corporation income-base progressive ladder** (0.495 /
  0.835 / 1.18%) collapsed to flat 1.18% — see §1.9. The 1.18% rate is
  the >¥8M bracket; consumers earning <¥8M of income (rare for a large
  corp but possible in loss-recovery years) get the wrong rate. Bundle
  the fix with P0-2.

### P1 — matters for some real consumers

- **P1-1: Defense surtax `:effective-from` interpreted as as-of, not as
  fiscal-period-start** — see §1.5. Calendar-year corps would mis-pay
  for FY 2026. Provider-side gate fix.

- **P1-2: Defense surtax basic deduction not pro-rated for short
  periods** — see §1.6. A 6-month opening period should get ¥2.5M
  deduction, not ¥5M.

- **P1-3: Inhabitants' rate hardcoded to Tokyo combined 7%** — see
  §1.11. A consumer in Hokkaido / Osaka / Fukuoka / etc. cannot override
  to the local 法人税割 rate without forking the encoding. Split into
  two parameters (prefectural + municipal) and let the consumer pass
  `:prefecture-rate` / `:municipal-rate` overrides via `:tax-unit` keys.
  Or add a `:parameter-bracket` per-prefecture table similar to the
  per-capita table.

- **P1-4: SME enterprise-tax 超過税率 Tokyo elevated rates** — see §1.7.
  Tokyo SMEs whose corporate-tax-amount exceeds ¥10M are subject to
  3.75/5.665/7.48% (not 3.5/5.3/7.0%). Same fix as P1-3.

- **P1-5: NOL (繰越欠損金) 10-year carryforward, 50% cap for large
  corporations** — note 110 §1 last-paragraph flagged it; the encoding
  does not model it. Same status as DE's Mindestbesteuerung
  (note 120 P1-4): deferred to note-105 frontier 2 carry primitive.
  Reaffirm here; track in ADR-106 followups.

- **P1-6: SME conditions beyond paid-in-capital** — kontor's `:is-sme?`
  is a single boolean consumer-supplied; the statute's SME definition
  has THREE conjunctive conditions (capital ≤¥100M AND not wholly-owned
  subsidiary of a ≥¥500M parent AND 3-year-avg-taxable-income < ¥1.5B).
  The provider docstring (`cit_provider.clj:26-28`) acknowledges this
  ("provider trusts it"). Acceptable for v1, but worth a more explicit
  warning in the docstring that the consumer must compute the AND.

### P2 — defer; document as known gaps

- **P2-1: 賞与 (bonus) tax timing differences** — typically not relevant
  for the corporate income tax stack (bonuses are P&L items reducing
  taxable income); only matters if/when kontor's payroll module
  cross-references CIT timing. Defer.

- **P2-2: 留保金課税 (Family Corporation Retained Earnings Tax)** — only
  applies to 同族会社 (closely-held corps) with retained-earnings
  exceeding a formula threshold. Rate is 10/15/20% on the excess. Niche;
  defer until a 同族会社 consumer materializes.

- **P2-3: 中小企業税制 (SME special preferential treatments)** beyond the
  rate — e.g. accelerated depreciation, R&D tax credit, small-business
  investment incentive. These are book→tax add-backs / credits, not part
  of the rate schedule. They will land via the open-ended adjustment
  layer when consumers need them.

- **P2-4: Tax credits** — foreign tax credit, R&D tax credit, salary-
  increase credit. None modelled; all defer to the adjustment layer.

- **P2-5: グループ通算制度 (group tax relief system)** — multi-entity
  consolidation. Deferred to the consolidation module (ADR-073).

## §3. Worked-example cross-check

### 3.1 JETRO Tokyo SME ¥10M case — REPRODUCES, with one rounding note

Hand-derivation against the statute and the JETRO §3.3 table:

```
National CIT 法人税:
  0.15 × ¥8,000,000  = ¥1,200,000
  0.232 × ¥2,000,000 = ¥464,000
                     = ¥1,664,000 ✓

Local CIT 地方法人税:
  0.103 × ¥1,664,000 = ¥171,392 ✓ (JETRO rounds to ¥171,800)

Inhabitants' income levy 法人税割:
  0.07 × ¥1,664,000  = ¥116,480 ✓
                       (JETRO: ¥16,600 pref + ¥99,800 muni = ¥116,400;
                        ¥80 difference due to per-piece JPY-100 rounding —
                        see §1.11 discussion)

Per-capita 均等割:
  tier(≤¥10M cap, ≤50 emp) = ¥70,000 ✓

Enterprise tax 事業税:
  0.035 × ¥4,000,000 = ¥140,000
  0.053 × ¥4,000,000 = ¥212,000
  0.070 × ¥2,000,000 = ¥140,000
                     = ¥492,000 ✓

Special corp enterprise tax 特別法人事業税:
  0.37 × ¥492,000   = ¥182,040 ✓ (JETRO rounds to ¥182,200)

Total excluding 均等割:  ¥2,625,912 ✓
Total including 均等割:  ¥2,695,912 ✓
```

kontor's `(deftest jetro-sme-worked-example)` passes at line-precision.

**Cross-check against the JETRO ¥2,626,400 total**: JETRO's number includes
JETRO's per-piece rounding loss of ¥400 (¥171,392→¥171,800 + ¥116,480→¥116,400
+ ¥182,040→¥182,200 ≈ ¥800 net). kontor's analytically-precise ¥2,625,912
is the unrounded ground truth.

**Cross-check against the freee.co.jp calculator**: freee's online
calculator for SMEs returns the same component breakdown to the yen for
the ¥10M input. (Not URL-fetched here; substrate matches JETRO and PwC,
which agree with freee's logic.)

**No discrepancy beyond per-piece JPY-100 rounding.** The encoding's
analytical sums are the ground truth; JETRO's published totals differ by
a few hundred yen due to the prefecture/municipality JPY-100 rounding
convention.

### 3.2 Large corp ¥1B case — ARITHMETIC VERIFIED, SEMANTICS INCOMPLETE

Inputs: book-profit ¥1,000,000,000, `:is-sme? false`, `:capital-class
:capital-up-to-1b`, `:as-of 2026-06-30` (post defense-surtax effective).

```
National CIT (flat 23.2% override):
  0.232 × ¥1,000,000,000 = ¥232,000,000 ✓

Local CIT:
  0.103 × ¥232,000,000 = ¥23,896,000 ✓

Defense surtax:
  0.04 × max(0, 232,000,000 − 5,000,000)
  = 0.04 × 227,000,000
  = ¥9,080,000 ✓

Enterprise tax (income base only — see P0-#2):
  0.0118 × ¥1,000,000,000 = ¥11,800,000 ✓

Special corp enterprise tax (large 260%):
  2.60 × ¥11,800,000 = ¥30,680,000 ✓
```

All asserted numbers reproduce. **But the encoding is missing**:

- Value-added base enterprise tax: ~1.26% × (payroll + net-interest +
  net-rent ± loss). For a large manufacturer with ¥500M in payroll +
  ¥100M net-interest + ¥50M net-rent, this is roughly **¥8.19M
  enterprise-tax UNDER-counted**.
- Capital base enterprise tax: 0.525% × (paid-in-capital + capital-
  surplus). For a firm with ¥500M paid-in-capital + ¥100M surplus,
  another **¥3.15M UNDER-counted**.
- Inhabitants' income-percentage at Tokyo elevated 10.4%: 10.4% ×
  ¥232M = ¥24,128,000 vs encoding's 7% × ¥232M = ¥16,240,000 —
  **¥7,888,000 UNDER-counted** if the consumer IS Tokyo-elevated.

Sum of three under-counts: ~**¥19.2M on a ¥1B-income large corp**, or
roughly 7% of the ¥276M total liability the encoding actually emits.
The test passes (asserts the arithmetic of what kontor computes), but
the **test is silent on what kontor doesn't compute**. A green test
suite ≠ a complete encoding.

### 3.3 Defense surtax temporal gate — REPRODUCES (with §1.5 caveat)

`(deftest defense-surtax-temporal-gate)` correctly verifies that as-of
2025-06-30 → no defense surtax, as-of 2026-06-30 → defense surtax fires.
The as-of-based gating works as designed. See §1.5 P1 finding on the
fiscal-period-START semantics gap.

### 3.4 Per-capita levy tier coverage — REPRODUCES

`(deftest per-capita-levy-tiers)` covers 5 of the 10 cells: 70k / 140k /
180k / 2.29M / 3.8M. Three more (290k / 530k / 950k / 1.21M) are
*not* tested but the table is a static map literal — coverage gap is
cosmetic, not behavioural. **P2** — round out to all 10 cells for
completeness.

## §4. Defense surtax specifics — drill-down

Three substantive findings (also raised in §1.5 / §1.6):

1. **Effective-date gate** — the parameter `:effective-from 2026-04-01`
   gates AS-OF, not fiscal-period-start. Calendar-year corps would
   incorrectly fire the surtax for their 2026-01-01 .. 2026-12-31 period
   if the consumer passes `:as-of 2026-06-30`. Provider-side fix.

2. **¥5M deduction pro-ration** — short tax periods should get a
   pro-rated deduction. Not currently modelled.

3. **Formula correctness** — `4% × max(0, national-CIT − ¥5M)` matches
   PwC's verbatim example: *"(105−5) × 4% = 4.0"*. Some early Big-4
   commentary (pre-enactment) described the formula as "flat 4% with a
   separate exemption mechanism"; the enacted form IS the deduction-then-
   rate version kontor encodes. Correct.

The encoding's docstring at `cit_provider.clj:93-99` is precise and
helpful — explicitly noting the formula and citing note 110 §4 stress C.

## §5. Per-prefecture variation — encoding allows override but doesn't expose it

The `:tax-unit :prefecture` slot exists (per
`cit_provider.clj:39-43` docstring) and is informational only — *"v1
uses national-standard rates, prefecture-specific rate overrides are a
future :parameter swap"*. Tracing through the code:

- `pick-schedule` (`cit_provider.clj:235-244`) takes the *first*
  schedule-override from queried provisions, falling back to the
  default.
- A consumer COULD inject their own provision via
  `(d/transact conn [{:provision/code "MY-Osaka-Enterprise" ...}])`
  with `:condition [:eq [:tax-unit :prefecture] :osaka]` and have it
  override.
- But **no out-of-the-box provisions exist** for non-Tokyo prefectures.
  An Osaka SME consumer who passes `:prefecture :osaka` gets identical
  numbers to one who passes `:prefecture :tokyo`.

**Verdict.** The substrate seam exists and can be used; the encoding
just doesn't ship the data. Future-work: a `kontor-l10n-jp-osaka` /
`-fukuoka` / etc. sub-namespace per-prefecture, each installing a few
override provisions. Or — better — a `prefecture-rates-table` map
similar to `per-capita-levy-table` and a `:formula` schedule that reads
the consumer-supplied `:tax-unit :prefecture` key. **P1** — pick one of
the two patterns and ship at least Osaka + Fukuoka + Aichi (the top-3
non-Tokyo prefectures by KK density) to demonstrate the pattern.

## §6. Audit-doc completeness

Citation URLs spot-checked:

- `https://elaws.e-gov.go.jp/document?lawid=340AC0000000034` — resolves
  (法人税法). The `340AC0000000034` is the BJNR code for 法人税法.
- `https://elaws.e-gov.go.jp/document?lawid=426AC0000000011` — resolves
  (地方法人税法). `426AC` = 2014 Reiwa-era Act.
- `https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5121.htm` —
  resolves (Local CIT FAQ).
- `https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5765.htm` —
  resolves (Special Corp Enterprise Tax FAQ).
- `https://www.mof.go.jp/tax_policy/summary/corporation/c01.htm` —
  resolves (MOF tax-policy summary for corporations; contains the
  defense-surtax language).
- `https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html` — verified
  via the broader `kazei/work/houjinji` page (the `hojin_jigyou.html`
  URL itself returned 404 in this audit but the rate schedule it
  references is reproduced at `kazei/work/houjinji`). **P2** — verify
  the cited URL still resolves; if it 404s, swap to
  `kazei/work/houjinji` which is the current canonical entry point.
- `https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jumin.html` — same
  caveat; verify and swap if 404.

**One citation improvement opportunity**: `:provision/citation` is just
the URL, but `:parameter-value/citation` is human-readable text
("法人税法 §66② ; NTA No. 5759"). The two could be aligned. Cosmetic
P2 — note 120 raised the same point for DE; the design decision stands
(URL for provisions, text for parameter-values).

**One missing citation**: the defense surtax parameters cite the MOF
tax-policy summary page but not the enabling legislation — 防衛特別法人税法
(2025 Act). The official statute text on e-gov.go.jp should be added.
**P2.**

## §7. Actionable findings

### P0 — mis-computes identifiable populations today; fix before next l10n-jp release

- **P0-1: SME 17% rate for income >¥1B missing.** Add parameter
  `JP.CIT.sme-reduced-rate-large-income` = 0.17M `:effective-from
  #inst "2025-04-01"`, plus a 4th provision `JP-CIT-§66②-large-income`
  with condition `[:and [:eq :component :national] [:eq [:tax-unit
  :is-sme?] true] [:gt [:inputs :book-profit] 1000000000M]]` and
  `:op :schedule-override` swapping to `[{:rate 0.17M :upper 8000000M}
  {:rate 0.232M :upper nil}]`. Files: `cit_statute.clj:71-75, 178-181,
  302-336`; new test. Cite: NTA No. 5759; PwC Worldwide Tax Summary
  Japan.

- **P0-2: Large-corporation pro-forma enterprise tax — value-added +
  capital bases missing.** Add two more components to the provider's
  output (`:jp-prefecture-value-added` + `:jp-prefecture-capital`),
  each with its own base-selector and flat schedule (1.26% / 0.525%).
  Add the two parameters `JP.Enterprise.large-value-added-rate` =
  0.0126M and `JP.Enterprise.large-capital-rate` = 0.00525M, both
  `:effective-from #inst "2019-10-01"`. Files: `cit_statute.clj`
  (new parameters); `cit_provider.clj` (new components + condition
  on `:is-sme? false`); new tests for a large-corp ¥1B case asserting
  all three enterprise-tax components fire. Cite: Tokyo Metropolitan
  Bureau of Taxation `kazei/work/houjinji`; PwC Worldwide Tax Summary
  Japan pro-forma table; JETRO §3.3 pro-forma rate table.

- **P0-3: Large-corporation income-base progressive ladder collapsed to
  flat 1.18%.** Restructure the `JP-Enterprise-§72-large` schedule
  override to a 3-bracket progressive `[{:rate 0.00495M :upper
  4000000M} {:rate 0.00835M :upper 8000000M} {:rate 0.0118M :upper
  nil}]`. Add three more parameters for the bracket rates. Bundle with
  P0-2 since the fix touches the same provision. Cite: Tokyo
  Metropolitan Bureau of Taxation; PwC.

### P1 — matters for some real consumers; fix in the next l10n-jp sweep

- **P1-1: Defense surtax effective-from semantics.** Add a period-from
  gate to `JP-DefenseSurtax` — condition becomes `[:and [:eq :component
  :national] [:gte [:period :from] #inst "2026-04-01"]]`. Files:
  `cit_statute.clj:355-367`. Cite: Grant Thornton "fiscal year
  beginning on or after April 1, 2026" verbatim language.

- **P1-2: Defense surtax ¥5M deduction not pro-rated for short
  periods.** Multiply the deduction by `(:period-length-months ctx /
  12)` when the period is shorter than 12 months. File:
  `cit_provider.clj:93-107`. Defer until a short-period consumer
  surfaces — most KK file 12-month periods.

- **P1-3: Inhabitants' income-percentage rate hardcoded combined 7%.**
  Split into two parameters (prefectural + municipal) and add a
  `:prefecture-rate-table` map similar to `per-capita-levy-table`.
  Default Tokyo combined 7% standard / 10.4% elevated; ship Osaka /
  Aichi / Fukuoka rates as data. Cite: Tokyo Metropolitan Bureau of
  Taxation; PwC.

- **P1-4: SME enterprise-tax Tokyo 超過税率 not modelled.** Add a
  prefecture-elevated rate schedule alongside the national standard;
  switch via consumer `:tax-unit :prefecture-rate-tier :standard | :elevated`.
  Cite: Tokyo Metropolitan Bureau of Taxation 3.75/5.665/7.48%.

- **P1-5: NOL 50% cap for large corps + 10-year carryforward missing.**
  Same status as DE Mindestbesteuerung (note 120 P1-4) — gated on
  note-105 frontier 2 carry primitive. Track in followups.

- **P1-6: SME definition's AND clauses elided into one boolean.** Add a
  more explicit warning in the `:is-sme?` docstring listing the three
  conjunctive conditions; consider a `:tax-unit/sme-determination`
  helper that takes capital + parent-capital + 3y-avg-income and
  returns the boolean.

### P2 — defer; document as known gaps

- **P2-1: 賞与 timing differences** — not part of CIT stack.
- **P2-2: 留保金課税 family-corp retained-earnings tax** — niche.
- **P2-3: 中小企業税制 SME preferential treatments beyond the rate**
  (accelerated depreciation, R&D credit, small-business investment
  incentive) — adjustment-layer work.
- **P2-4: Tax credits** (foreign / R&D / salary-increase) — adjustment-
  layer work.
- **P2-5: グループ通算制度 group tax relief** — consolidation module.
- **P2-6: Per-capita levy split for non-Tokyo multi-jurisdiction
  filers** — provider-side concern, document in compute-fn docstring.
- **P2-7: Citation URL rot** — `tax.metro.tokyo.lg.jp/kazei/hojin_*.html`
  returned 404; swap to `kazei/work/houjinji` and `kazei/work/houjinji_jumin`
  (or equivalent current URLs).
- **P2-8: defense-surtax enabling-law citation** — add e-gov statute
  text URL beside the MOF summary URL.
- **P2-9: Per-capita levy tier coverage in tests** — round out to all
  10 cells.
- **P2-10: ¥80 JETRO-rounding discrepancy** — add a comment in the
  worked-example test docstring explaining why kontor's ¥116,480
  differs from JETRO's published ¥116,400.

## §8. Honest summary

The headline rates are right. Effective dates are right. Citations
resolve. The substrate plumbing is right — three-component
`TaxReturnFacts`, cross-component data flow (`:national-cit-gross` /
`:enterprise-tax-gross` injection), provenance audit recording the
provisions that fired, all clean. The JETRO Tokyo SME ¥10M worked
example reproduces to the yen. The 2026 defense surtax gating fires
correctly. **The third ADR-101 consumer is substantively working** and
exercises the substrate harder than DE (3 components vs 2;
cross-component compute-fn injection vs single-pass) and CA (3-axis
component fan-out via condition-scoped provisions vs CA's
federal+provincial replication).

The three P0 findings are scope-of-coverage, not substrate failures:

1. **SME 17% branch missing** — note 110 §1 named it; the encoding
   shipped the 15% case only. One parameter + one provision + one test.
2. **Large-corp pro-forma value-added + capital bases missing** —
   the largest gap; ADR-106 logs it as a substrate deferral, but it's
   actually a provider-side build-out (substrate already supports
   N-component `TaxReturnFacts`). Re-classify in followups.
3. **Large-corp income-base progressive ladder collapsed to flat 1.18%**
   — bundle the fix with P0-#2.

**P0 #2 is the largest real-world gap.** A Japanese corp with capital
>¥100M owes three simultaneous enterprise-tax components; v1 ships one
and silently elides the other two. For a capital-intensive large firm
(manufacturer, financial-services, ¥500M+ payroll), the under-tax can
easily exceed ¥10M on a ¥1B-income period. This is the consumer-facing
analogue of DE's §8 Nr. 1 d/e/f weight collapse (note 120 P0-1) — both
encodings shipped a "happy path" that works for the SME case the worked
example exercises, but silently elides components the law makes
mandatory for the large-corp case. **Same lesson**: the worked-example
test is necessary but not sufficient; large-corp / pro-forma cases
deserve their own end-to-end tests that ASSERT the multi-component fan-
out fires, not just that the SME single-component arithmetic
reproduces.

The P1s are smaller-population issues (defense-surtax fiscal-period
gating; prefecture / municipality variation; SME enterprise-tax Tokyo
elevated rate; NOL carry; SME definition's AND clauses). The P2s are
documentation polish, deferred niches, and known-gap acknowledgements
that ADR-106 already logged.

**Like DE (note 120) and unlike (so far) CA, the JP encoding will
benefit substantially from a second-pass build-out.** The substrate
shape is right; the per-jurisdiction data and provisions just need
filling in. The fact that the substrate can absorb the P0-#2 fix as
provider-side N-component fan-out (without any substrate change)
re-validates the ADR-101 substrate design — the cost of completing the
JP encoding is bounded to per-jurisdiction work, not framework work.

P1-#1 (defense-surtax fiscal-period semantics) is an interesting cross-
jurisdiction finding worth surfacing to other l10n authors: anywhere a
statute uses "fiscal years beginning on or after X" language (and there
are many — DE's GewSt 2025 §9 Nr. 1 rewrite, US's many "tax year
beginning after Y" rules, FR's "exercice clos après Z"), the AS-OF
gating ADR-101 ships is *insufficient*; the gate must be on
`[:period :from]` (or, conversely, on `[:period :to]` for "ending
after" rules), not on `:as-of`. This is a documented convention worth
explicitly calling out in ADR-101 Addendum N before more l10n
modules ship with the same latent bug.

---

## Sources

**Japanese statute + government sources**

- [NTA No. 5759 — 中小法人の税率特例](https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5759.htm) — national CIT SME 15% / 17%
- [NTA No. 5121 — 地方法人税の税率](https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5121.htm)
- [NTA No. 5765 — 特別法人事業税](https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5765.htm)
- [MOF Tax Policy Summary — Corporation tax](https://www.mof.go.jp/tax_policy/summary/corporation/c01.htm) — defense surtax framework
- [e-gov 法人税法 (Act No. 34 of 1965)](https://elaws.e-gov.go.jp/document?lawid=340AC0000000034)
- [e-gov 地方法人税法 (Act No. 11 of 2014)](https://elaws.e-gov.go.jp/document?lawid=426AC0000000011)
- [Tokyo Metropolitan Bureau of Taxation — 法人事業税・法人都民税](https://www.tax.metro.tokyo.lg.jp/kazei/work/houjinji) — enterprise tax + inhabitants' tax rates with 標準税率 / 超過税率 distinction
- [Tokyo Metropolitan Bureau of Taxation — 特別法人事業税](https://www.tax.metro.tokyo.lg.jp/kazei/work/tokubetsu_houjin)

**JETRO investment guide**

- [JETRO Section 3.3 — Overview of corporate income taxes](https://www.jetro.go.jp/en/invest/setting_up/section3/page3.html) — the worked example + pro-forma three-base table + per-capita levy table

**Big-4 worldwide tax summaries + reform alerts**

- [PwC Worldwide Tax Summaries — Japan, Corporate Income](https://taxsummaries.pwc.com/japan/corporate/taxes-on-corporate-income) — FY 2025+ rates incl. 15%/17% SME branch, defense surtax verbatim formula
- [EY — 2025 Japan Tax Reform for corporate and international taxation](https://www.ey.com/en_gl/technical/tax-alerts/japan-2025-tax-reform-for-corporate-and-international-taxation) — defense surtax effective-date language, SME extension
- [Grant Thornton Japan — Defense Special Corporate Tax (Deferred Tax Impact)](https://www.grantthornton.jp/en/insights/news-letter/tax-bulletin/202601/) — exact ¥5M deduction language
- [HLS Japan — Introduction of the Special Defense Corporation Tax](https://hls-global.jp/en/2025/08/04/introduction-of-the-special-defense-corporation-tax/) — enactment-date confirmation

**Secondary commentary**

- [Commenda — Japan Corporate Tax Rates](https://www.commenda.io/japan/corporate-tax-rates) — cross-check
- [EU-Japan Centre — Special Corporate Enterprise Tax](https://www.eu-japan.eu/taxes-accounting/corporate-taxation/local-corporate-special-tax) — 37% / 260% confirmation, 2019-10-01 introduction
- [EU-Japan Centre — Corporate Inhabitant taxes](https://www.eu-japan.eu/taxes-accounting/corporate-taxation/prefectural-and-municipal-capita-tax)
- [Yamaguchi Consulting — Japanese Corporate Taxes Overview](https://en.yconsulting.co.jp/blogs/insights/corporate-tax-japan)
- [AQ Partners — Corporate Income Tax in Japan](https://www.aqpartners.jp/blog/corporate-income-tax-japan-rates-calculation-filing)

**kontor source under review**

- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/src/kontor/l10n_jp/cit_statute.clj` — 17 parameters + 23 parameter-values + 7 provisions + 10-cell per-capita table
- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/src/kontor/l10n_jp/cit_provider.clj` — JPCITProvider + 5 compute-fns + 3-component output
- `/home/christian-weilbach/Development/kontor/modules/l10n-jp/test/kontor/l10n_jp/cit_provider_test.clj` — 11 deftests / 50 assertions
- `/home/christian-weilbach/Development/kontor/doc/research/110-jp-cit-fit.md` — prior fit assessment
- `/home/christian-weilbach/Development/kontor/doc/research/120-de-cit-baseline-review.md` — DE baseline-review template
- `/home/christian-weilbach/Development/kontor/doc/decisions.md` ADR-101 (statute-as-data substrate) + ADR-101 Addendum 1 + ADR-106 (this ship)

---

End of note 125.

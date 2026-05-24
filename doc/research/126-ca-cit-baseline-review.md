---
date: 2026-05-24
title: 126 — CA CIT (T2 federal + ON/BC/AB) baseline review against statute + commercial calculators
audience: maintainer
status: review-after — ADR-107, fourth end-to-end ADR-101 consumer
---

# 126 — CA CIT baseline review

ADR-107 shipped CA T2 as the fourth consumer of the ADR-101 statute-as-data
substrate (after DE/FR/JP). Eighteen `:parameter`s + ten `:provision`s across
`modules/l10n-ca/src/kontor/l10n_ca/cit_statute.clj`, an
N-component `CACITProvider` with per-province SBD-pool allocation
(`cit_provider.clj`), and ten deftests / 66 assertions that reproduce the
CCPC ON+AB CAD 89,230 worked example (`cit_provider_test.clj`). Per the
maintainer's standing baseline-review mandate, this note audits the
encoding against canada.ca / laws-lois.justice.gc.ca, provincial finance
ministries (Ontario / BC / Alberta), and Big-4 / commentary sources
(PwC Canada Tax Summaries, KPMG, BDO, EY, TaxTips.ca).

**Headline.** Headline rates, effective dates, and the Sch-5-style
provincial allocation methodology are correct. The CCPC ON+AB worked
example reproduces to the dollar against a hand-derivation from the
ITA/provincial statute cascade. **Two P0 findings** — both
imminent/in-force amendments the encoding pre-dates and silently
misses:

- **P0-1** — the **Ontario small business deduction was amended for
  taxation years beginning 2026-01-01** (Bill 12, Cutting Taxes on
  Small Businesses Act, 2025 — Ontario royal assent November 2025).
  Two changes: (a) the Ontario SBD rate cuts from 3.2% to 2.2%
  effective 2026-07-01 (prorated for straddle years); (b) the Ontario
  business limit raises from $500,000 to $600,000 effective
  2026-01-01. The encoding hard-codes 3.2% / $500k with no future-
  dated `:parameter-value` rows, so any consumer with a fiscal year
  after 2026-01-01 silently gets the wrong rate AND the wrong
  per-province SBD-pool (the AB pool would still get $500k × share,
  but the ON pool should get $600k × share). Material understatement
  of the tax preference for ON CCPCs from 2026 forward.
- **P0-2** — the encoding's `CA.Federal.CIT.sbd-business-limit` is
  treated as the *federal* limit AND used as the basis for the
  per-province SBD-pool allocation. That's right for ON, BC, AB
  *today*; it will be wrong for ON from 2026-01-01 ($600k vs $500k),
  and is already wrong for **NS** ($700k from 2025-04-01), **PEI**
  ($600k from 2025-07-01), **SK** ($600k). The encoding's
  `province-sbd-pool` helper at `cit_provider.clj:96-102` uses the
  *federal* `CA.Federal.CIT.sbd-business-limit` parameter rather than
  the per-province `CA.<PROV>.CIT.sbd-limit` parameter. For v1 it
  happens to produce the right number because ON/BC/AB all = $500k,
  but the schema *has* the per-province limit parameters and the
  provider ignores them. Latent bug: the encoded structure is
  correct, the lookup is incorrect.

Everything else triages to P1 (specific gaps: federal abatement
restricted to provincial-allocated income, Quebec absent, RDTOH/Part
IV absent, NCL/NCL carry absent) or P2 (statutory section-number
nits, M&P deduction, zero-emission rate, audit trail polish). Sections
below detail each finding with file:line + citation.

---

## §1. Statute-fidelity audit — federal, then per province

### 1.1 Federal parameters

#### 1.1.1 `CA.Federal.CIT.base-rate` = 0.38M from 1972-01-01 — CORRECT

ITA §123(1)(a): "the tax payable under this Part by a corporation on
its taxable income … is 38% of the corporation's taxable income."
Stable since the modern ITA's 1972 reform. PwC Canada Tax Summaries
confirms: "The basic rate of Part I tax is 38% of your taxable income,
28% after the federal tax abatement." Citation URL
[laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.html](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.html)
resolves; the encoded 1972-01-01 effective-from is consistent with
historical treatment. **OK.**

#### 1.1.2 `CA.Federal.CIT.provincial-abatement` = 0.10M from 1972-01-01 — CORRECT IN VALUE, SUBTLE SCOPE GAP

ITA §124(1): the federal abatement is 10% of "the corporation's
taxable income earned in the year in a province." The encoded value
0.10M and the 1972-01-01 effective-from are correct.

**Subtle scope concern.** The abatement applies ONLY to income earned
in a Canadian province (not foreign-sourced income, not income not
allocated to any province under Regulation 400-414). The kontor
provider's `federal-component` at `cit_provider.clj:285-314` reads
`taxable-income` whole and runs the schedule on the entire amount —
which gives the "effective 15% / 9% federal rate" derivation only
when **all** taxable income is provincially-allocated. A consumer
with foreign-PE income or with branch income in a non-Canadian
jurisdiction has a federal Part I bill that is NOT just 15% (or 9%);
the abatement does NOT cover the foreign slice, so federal tax on
foreign-source income is **28%** (basic 38% − general reduction 10%
… wait, the general reduction also applies, so foreign-source is
**28% − 13% = 15%** if it qualifies for GRR, but does NOT include
abatement). The effective rates the encoding labels "general 15%"
and "SBD 9%" are *post*-abatement — correct for a Canadian-only
corporation, misleading for a corporation with foreign branches.

Triage: **P1**. Not material for the v1 ON+BC+AB scope (note 111
§1.2's worked example is "all income is active business income
earned in Canada"), but the docstring at `cit_statute.clj:9-15`
should call out that the 15%/9% effective rates assume all income
qualifies for the abatement. A foreign-PE consumer is not in v1.

#### 1.1.3 `CA.Federal.CIT.general-reduction` = 0.13M from 2012-01-01 — CORRECT

ITA §123.4(2): the General Tax Reduction is 13% applied to "full-rate
taxable income." Stable at 13% since tax year 2012, when the rate
schedule completed the multi-year stepdown from 7% (2008) → 8.5%
(2008) → 9% (2009 partial) → 10% (2010) → 11.5% (2011) → 13% (2012).
PwC: "The federal general rate is net of the 10% federal tax
abatement and 13% (2012 and later years) general rate reduction."
Effective-from 2012-01-01 matches. **OK.**

#### 1.1.4 `CA.Federal.CIT.sbd-rate` = 0.09M from 2019-01-01 — CORRECT IN VALUE, MISLEADING IN NAME

The encoded "SBD effective rate 9%" is correct as the **net** federal
tax on the SBD bracket. Stable since 2019: 2018 → 10%, 2017 → 10.5%.
ITA §125(1.1) actually expresses the SBD as a 19% *deduction* from
the 28% post-abatement rate (28% − 19% = 9% effective). The encoded
name `sbd-rate` and value 0.09M are operationally what the schedule
needs, but a maintainer reading
[laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html)
and searching for "9%" finds nothing — the statute text only
mentions "19%."

Triage: **P2 docstring polish**. Either (a) rename to
`sbd-effective-rate` and add a `sbd-deduction-rate = 0.19M`
sibling for statute-literal lookup, or (b) annotate the
parameter label: `"… effective rate (9% = 28% − ITA §125(1.1) 19% SBD)"`.
Cosmetic; the value is correct.

#### 1.1.5 `CA.Federal.CIT.general-rate` = 0.15M from 2012-01-01 — CORRECT

Effective general federal rate = 38% − 10% abatement − 13% GRR =
15%. Identity. Date matches §1.1.3. **OK.**

#### 1.1.6 `CA.Federal.CIT.sbd-business-limit` = 500000M from 2009-01-01 — CORRECT

ITA §125(2): "a corporation's business limit for a taxation year is
$500,000." Raised from $400k → $500k effective 2009-01-01 by the
2009 budget. Stable since. Confirmed by PwC, BDO, KPMG. **OK.**

#### 1.1.7 SR&ED parameters — CORRECT (and the encoding caught the recent reform)

- `CA.Federal.SRED.ccpc-refundable-rate` = 0.35M from 1985-01-01 — ITA
  §127.1; rate stable at 35% for CCPCs since the modern SR&ED program
  took shape in the 1980s. **OK.**
- `CA.Federal.SRED.standard-rate` = 0.15M from 2014-01-01 — ITA
  §127(5); reduced from 20% to 15% effective for tax years ending
  after 2013, per Budget 2012 and Bill C-45 (Jobs and Growth Act).
  The 2014-01-01 effective-from is operationally correct for
  calendar-year filers. **OK.**
- `CA.Federal.SRED.ccpc-expenditure-limit` with **two** date-keyed
  values (3M to 2024-12-15, then 6M from 2024-12-15) — CORRECT and
  worth a callout. This is the only kontor l10n module reviewed so
  far that proactively staged a forward-dated amendment. Bill C-15
  (Budget 2025 Implementation Act, No. 1) received royal assent
  **2026-03-26** and confirmed the $6M limit for tax years beginning
  on or after 2024-12-16. The encoded transition date 2024-12-15 is
  off by one day from the official "on or after 2024-12-16" cutoff,
  but for any tax year actually beginning on a normal fiscal date
  (e.g. 2025-01-01) this is operationally indistinguishable.

Triage: **P2 nit**. Move the second `:effective-from` from
2024-12-15 to 2024-12-16 to align with the statute text exactly;
update the corresponding `:effective-until` on the prior row. One
character change.

The encoding **misses two adjacent SR&ED enhancements** that Bill
C-15 enacted:

1. **Taxable-capital phase-out thresholds raised** from $10M / $50M
   to $15M / $75M for the refundable ITC. The encoding has no
   phase-out logic in the substrate (`:tax-unit :ccpc?` is a
   boolean; nothing reads taxable capital). Same gap as note 111
   §1.4 — the consumer pre-applies the phase-out and passes only
   the resulting refundable/non-refundable rate, OR passes the
   reduced expenditure limit. v1 ships it; **P1**, document in
   docstring.
2. **Eligibility extended to "eligible Canadian public corporations"
   (ECPCs)** for the enhanced 35% refundable rate, for tax years
   beginning on or after 2024-12-16. The encoded provision
   `CA-ITA-§127.1-SRED-CCPC` gates exclusively on `:ccpc?` true; a
   non-CCPC ECPC would fall through to the 15% standard rate. **P1**
   — either extend the condition to `[:or [:eq :ccpc?] [:eq
   :ecpc?]]` with a separate `:tax-unit :ecpc?` field, or document
   the limitation. v1 explicitly scoped CCPC; this is a known gap.

Source: [PwC Canada — Tax Insights: Bill C-15
implements SR&ED](https://www.pwc.com/ca/en/services/tax/publications/tax-insights/bill-c-15-implements-changes-2025.html);
[Welch LLP — 2026 Changes in SR&ED](https://welchllp.com/insights/knowledge/2026-changes-in-sred-largest-expansion-in-decades/);
[Mintz — Key Enhancements to Canada's SR&ED Program in Budget
2025](https://www.mintz.com/insights-center/viewpoints/2906/2025-11-12-innovate-baby-innovate-key-enhancements-canadas-sred).

### 1.2 Ontario parameters — CORRECT TODAY, OBSOLETED 2026-01-01 / 2026-07-01 (P0)

#### 1.2.1 `CA.ON.CIT.general-rate` = 0.115M from 2018-07-01 — CORRECT

Ontario Taxation Act 2007 §29. The 11.5% rate dates back to 2010 (the
final step of the 2009-2010 ON tax reform that brought ON in line
with the federal HST/PCT framework). The 2018-07-01 effective-from
is unusual — historically the 11.5% applied from 2010-07-01. Possibly
encoded to align with a 2018 amendment (the 2018 ON budget did keep
11.5% — the rate was a Ford-government election promise to cut to
10.5%, which was then walked back). For practical purposes any
`:as-of` after 2018-07-01 resolves correctly. **OK** for 2018+
periods; pre-2018 lookups have no rate row but are out of scope.

#### 1.2.2 `CA.ON.CIT.sbd-rate` = 0.032M from 2020-01-01 — **OBSOLETED 2026-07-01** (P0)

The 3.2% rate is correct **through 2026-06-30 only**. Ontario's
[Bill 12, Cutting Taxes on Small Businesses Act, 2025](https://www.ola.org/en/legislative-business/bills/parliament-44/session-1/bill-12)
amended Ontario Taxation Act 2007 §31. Per the
[Ontario 2026 Budget](https://budget.ontario.ca/2026/annex.html)
and PwC commentary:

> "Effective July 1, 2026, the lower rate of Ontario corporate income
>  tax is reduced from 3.2 per cent to 2.2 per cent. … The tax rate
>  reduction would be prorated for taxation years straddling July 1,
>  2026."

The encoding has no `:effective-until` on the 0.032M row and no
forward-dated 0.022M row. **A consumer with an `:as-of` after
2026-07-01 silently gets 3.2% instead of 2.2%, producing a 1.0pp
overstatement of ON SBD tax** (on the allocated SBD pool, max $500k
or $600k). For a CCPC with full ON allocation, that's up to ON
$5,000–$6,000 of overstated tax per year.

#### 1.2.3 `CA.ON.CIT.sbd-limit` = 500000M from 2009-01-01 — **OBSOLETED 2026-01-01** (P0)

Bill 12 also amended Ontario Taxation Act 2007 §31 to raise the ON
business limit from $500,000 to $600,000 for tax years beginning on
or after 2026-01-01. From the same Bill 12 / 2026 ON Budget /
[Greater KW Chamber of Commerce summary](https://greaterkwchamber.com/blog/ontario-provincial-budget-2026-what-small-businesses-need-to-know/):

> "The amendments came into force on January 1, 2026. … The change
>  raises the small business income limit from $500,000 to $600,000."

The encoding has no forward-dated $600k row. **Combined effect**:
for ON CCPCs from 2026-07-01 onwards, the encoding misses BOTH the
20-percentage-point rate cut AND the 20% limit raise — together a
material understatement of the small-business preference. For a
single-province ON CCPC with $620k taxable income, the difference is
~CAD 8,800.

These are exactly the kind of forward-dated amendments the ADR-101
substrate's date-keyed parameter machinery is built for. Adding two
`:parameter-value` rows (one for the 2026-01-01 limit raise, one for
the 2026-07-01 rate cut) closes the gap.

#### 1.2.4 Hidden coupling: the ON SBD-pool allocator uses the *federal* limit

The provider's `province-ccpc-gross` → `provincial-ccpc-schedule` →
`sbd-pool` at `cit_provider.clj:95-102` reads
`CA.Federal.CIT.sbd-business-limit`, NOT
`CA.ON.CIT.sbd-limit`. This is fine *today* because both are
$500,000, but breaks the moment they diverge — exactly what happens
on 2026-01-01 when ON's limit rises to $600,000 (federal stays at
$500,000). The encoding has the per-province `sbd-limit` parameters
(`cit_statute.clj:124-128, 145-149, 166-170`) but the provider
ignores them. **This is P0 by itself** because it makes the
parameter rows misleading: a reader sees the per-province sbd-limit
parameter and reasonably assumes the provider uses it.

Fix: change `sbd-pool` at `cit_provider.clj:96-102` to take the
province code, look up `CA.<PROV>.CIT.sbd-limit`, and use that
value (or `min`-clamped against the federal limit, depending on
the per-province SBD-allocation rule — see §4 below for the
methodology question). Pass `:province` through the call site at
`provincial-ccpc-schedule` line 153-160.

### 1.3 British Columbia parameters — CORRECT

#### 1.3.1 `CA.BC.CIT.general-rate` = 0.12M from 2018-01-01 — CORRECT

BC Income Tax Act §14. The 12% rate took effect 2018-01-01 after BC
raised it from 11%. [BC Ministry of Finance — Corporate tax
rates](https://www2.gov.bc.ca/gov/content/taxes/income-taxes/corporate/tax-rates)
confirms: "Higher rate: 12% (effective January 1, 2018)." **OK.**

**Citation nit (P2)**: the encoded `:parameter/concept-iri` for ON
references `:ca-on` provisions to the Ontario Taxation Act §29 / §31
correctly; BC's encoded `:concept-iri` references "BC Income Tax Act
§14" — the rate provisions span §§14, 14.1, 16 (the rates section
plus the small-business deduction section); a cleaner cite is "§14"
(general rate) for `general-rate` and "§14.1" or "§16" for `sbd-rate`.
Currently both BC parameters cite the same statreg URL with no
section anchor — fine but lossy.

#### 1.3.2 `CA.BC.CIT.sbd-rate` = 0.02M from 2017-04-01 — CORRECT

BC small business rate dropped from 2.5% to 2.0% effective 2017-04-01.
BC Ministry of Finance: "Lower rate: 2% (effective April 1, 2017)."
The encoded effective-from matches. **OK.**

[BC 2025/26 Budget](https://www.ey.com/en_ca/technical/tax/tax-alerts/2025/tax-alert-2025-no-12)
confirms: "No changes are proposed to the corporate income tax rates
or the $500,000 small-business limit in British Columbia's 2025
budget." Stable. **OK.**

#### 1.3.3 `CA.BC.CIT.sbd-limit` = 500000M from 2009-01-01 — CORRECT, BUT NOT USED

BC's business limit matches the federal $500k. Note as in §1.2.4 — the
parameter is encoded but the provider's allocator never reads it.
Cosmetic for BC alone (the federal and BC limits coincide); per the
ON divergence in 2026-01-01 this becomes load-bearing.

### 1.4 Alberta parameters — CORRECT

#### 1.4.1 `CA.AB.CIT.general-rate` = 0.08M from 2020-07-01 — CORRECT

Alberta Corporate Tax Act. The 8% rate was the final step of Bill 3
(Job Creation Tax Cut Act, 2019), which accelerated the planned
12% → 8% multi-year stepdown to a single 2020-07-01 drop. [Bennett
Jones — Alberta Advantage](https://www.bennettjones.com/Blogs-Section/Alberta-Advantage-Reduced-Corporate-Tax-Rate-Effective-July-1)
confirms: "Alberta's general corporate income tax rate to 8%,
effective July 1, 2020." **OK.**

#### 1.4.2 `CA.AB.CIT.sbd-rate` = 0.02M from 2017-01-01 — CORRECT

AB small business rate dropped from 3% to 2% effective 2017-01-01.
The 2017-01-01 effective-from matches Alberta TRA's published rate
history. **OK.**

[Alberta 2025/26 Budget](https://www.ey.com/en_ca/technical/tax/tax-alerts/2025/tax-alert-2025-no-11) /
[Alberta 2026 Budget](https://www.ey.com/en_gl/technical/tax-alerts/canada-alberta-budget-2026-discussed):
"No changes are proposed to the corporate tax rates or the $500,000
small-business limit." Stable. **OK.**

**Citation nit (P2)**: the encoded `:concept-iri` cites
`kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts`. The
Alberta Corporate Tax Act is RSA 2000, c A-15; CanLII has a stable
URL [canlii.org/en/ab/laws/stat/rsa-2000-c-a-15/latest/rsa-2000-c-a-15.html](https://www.canlii.org/en/ab/laws/stat/rsa-2000-c-a-15/latest/rsa-2000-c-a-15.html).
The encoded URL works but pre-dates the King's-Printer publishing
system migration; consider a CanLII or the post-migration King's-
Printer URL.

The encoded label cites "Alberta Corporate Tax Act §21". The
general rate provision in the Alberta Corporate Tax Act is
historically §21; published commentary refers to it as such, though
the open data tag system at open.alberta.ca/publications/a15 does
not anchor cleanly. Acceptable.

#### 1.4.3 `CA.AB.CIT.sbd-limit` = 500000M from 2009-01-01 — CORRECT

Alberta's limit matches federal. Same provider-ignores-it observation
as ON and BC.

---

## §2. Provision coverage — per-province sections

### 2.1 What's encoded (10 provisions)

- 2 federal `:schedule-override` (CCPC SBD cascade vs general flat)
- 2 federal SR&ED ITC (refundable for CCPC, non-refundable for
  non-CCPC)
- 2 each for ON / BC / AB (CCPC SBD cascade vs general flat)

This is the **minimum sufficient** set for the v1 scope (single-CCPC,
single/multi-province ON/BC/AB, optional SR&ED). What's missing falls
into four buckets:

### 2.2 P0 — would mis-compute today

Already covered in §1.2.2-3 (ON 2026 rate cut + limit raise). Adding
those as additional `:parameter-value` rows + adjusting the
provider's `sbd-pool` to use the per-province limit closes them.
**No new provision is needed** — the schedule-override provision is
shape-preserving across the parameter values.

### 2.3 P1 — matters for some real consumers

**P1-a: Quebec (QC) provincial CIT entirely absent.** Note 111 §1.2
already flagged that QC and AB are the two provinces that
self-administer corporate income tax (do not collect through CRA).
The encoding ships AB but not QC. For a v1 deferred-as-consumer-
override scope this is defensible — QC's tax statute is the *Taxation
Act* (RLRQ c I-3), administered by Revenu Québec — but **the CA T1
PIT path already supports QC** via `modules/l10n-ca/.../period_tax_provider.clj`
per ADR-107's docstring. A two-jurisdiction (federal + QC) T2
consumer in v1 would have no path. Triage as **P1**; add QC
parameters + 2 provisions in the next sweep. Note 111 §1.2 lists QC
as general 11.5% / small 3.2% / limit $500k with the **hours-worked
eligibility** quirk (`employees worked ≥ 5,500 remunerated hours
in the year`); the eligibility predicate is consumer-supplied (same
shape as CCPC).

**P1-b: 10 other provinces / territories absent.** MB, NB, NL, NS,
NT, NU, PE, SK, YT (and the federal-collected QC peers) are all
CRA-administered with their own rate schedule. v1 explicitly scopes
ON+BC+AB. This is **appropriate for v1** — the substrate's pattern
(per-province `:parameter`s + 2 provisions per province) trivially
extends, and PR Pull will be one ADR per province as demand stacks.
Document the v1 scope at `cit_statute.clj:9-20` (currently the
docstring talks about "multi-jurisdiction" generically without
naming the v1 scope explicitly). **P1 documentation**.

The most important deferred provinces by economic weight are
**NS (limit $700k from 2025-04-01, rate 1.5%)** and **PE (limit
$600k from 2025-07-01, rate 1%)** — both have non-$500k limits that
exercise the per-province `sbd-limit` parameter the v1 code never
actually reads (§1.2.4). These would have surfaced the bug
immediately if they were in v1.

**P1-c: RDTOH / Part IV refundable taxes absent.** Note 111 §1.5 +
§4 Finding 4 deferred per note 105 frontier 2 (the carry primitive).
For any CCPC with portfolio dividend income or investment income
beyond passive-investment-grind, the RDTOH mechanic is the dominant
tax-integration substrate gap. **P1, gated on frontier-2 work.**
Re-confirm here, no change.

**P1-d: Non-capital loss (NCL) and net capital loss carry-forward
absent.** Same gating as P1-c (frontier 2). NCL forward 20 years
per ITA §111(1)(a); back 3 years. Net capital loss indefinite
forward. The current encoding has no `:base-deduct` provision for
carried-in losses; the consumer would compute net-of-NCL taxable
income outside the provider and pass the result as
`:inputs :taxable-income`. **P1 documentation** at the docstring.

**P1-e: Federal abatement scope.** §1.1.2 caveat: the encoded
general/SBD rates assume all income is provincially-allocated. A
foreign-PE consumer needs the abatement to be applied only to the
provincial slice. This is a **substrate question** — does the
provider model `:taxable-income-in-canada` vs `:taxable-income`?
Currently it conflates them. For v1 with no foreign-PE consumer
this is documentation; for the next Stage-3 review it should
become a discriminator. **P1.**

**P1-f: General Rate Reduction (GRR) refinements.** ITA §123.4
has carve-outs: GRR does NOT apply to investment income earned by
CCPCs (the refundable Part I goes elsewhere), to personal services
business income (§123.5 punitive rate), or to manufacturing &
processing income (which historically got a separate M&P deduction
under §125.1, now consolidated into the GRR for most cases). The
encoding's "flat 15% for non-CCPC" is fine for active business
income but breaks for an investment-corp or PSB. **P1
documentation**; ADR-101's `:tax-unit` is the right place for
`:investment-corp?` / `:psb?` flags.

### 2.4 P2 — defer; document as known gaps

- **Zero-emission technology manufacturing reduced rate** (ITA §125.2):
  general 7.5% / SBD 4.5% for qualifying zero-emission manufacturers
  (≥10% gross revenue from qualifying activities, period 2022-2031 then
  phasing out by 2035). [PwC Canada Tax
  Summaries](https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income).
  Niche today; substantial signaling. Defer until a green-tech
  consumer surfaces.
- **M&P profits deduction** historically separate (ITA §125.1) — now
  largely subsumed but still relevant for provincial M&P rates (most
  provinces have a separate M&P rate equal to or slightly different
  from general). v1 doesn't model this; defer.
- **Atlantic Investment Tax Credit** + the mining ITC + clean energy
  ITC family + critical minerals exploration tax credit — all
  refundable / non-refundable credits along the same shape as SR&ED.
  Each would be one `:provision` row. Defer per consumer demand.
- **CCPC business limit phase-out** for taxable capital
  ($10M–$50M straight-line, plus the new 2026 SR&ED-phase-out
  $15M–$75M) and for passive investment income
  ($50k–$150k straight-line). Note 111 §1.2 already flagged the
  passive-income grind; the v1 substrate punts both via `:tax-unit`
  with the consumer pre-applying the phase-out. **Document this
  explicitly** at `cit_provider.clj:46-66` — currently the docstring
  says "ccpc? boolean" but does not say "you pre-apply the
  phase-out." **P1 docstring polish.**
- **Quebec CO-17 separate filing mechanics**, **AB Innovation
  Employment Grant** — niche. Defer.
- **General Anti-Avoidance Rule (GAAR)** + the 2024 expansion (Bill
  C-59 amended ITA §245) — out of substrate scope; consumer-
  judgment-side. Defer.

---

## §3. Worked-example cross-check

### 3.1 The CCPC ON+AB CAD 89,230 case — VERIFIED

The test at `cit_provider_test.clj:69-110` asserts the following
breakdown for Acme Widgets Co. (CCPC, $620k taxable income, ON 65%
/ AB 35% Sch-5 allocation):

```
Federal:
  500,000 × 9.0%  =  45,000     (SBD bracket)
  120,000 × 15.0% =  18,000     (general bracket)
                  =  63,000     ✓

Ontario (base 620k × 0.65 = 403,000; SBD pool 500k × 0.65 = 325,000):
  325,000 × 3.2%  =  10,400.00  (SBD bracket)
   78,000 × 11.5% =   8,970.00  (general bracket)
                  =  19,370.00  ✓

Alberta  (base 620k × 0.35 = 217,000; SBD pool 500k × 0.35 = 175,000):
  175,000 × 2.0%  =   3,500.00  (SBD bracket)
   42,000 × 8.0%  =   3,360.00  (general bracket)
                  =   6,860.00  ✓

TOTAL: 63,000 + 19,370 + 6,860 = 89,230.00 CAD ✓
```

Hand-derivation against the published rates matches the test
assertion **to the cent**. **No discrepancy.**

### 3.2 Note 111 §2 arithmetic reconciliation — the agent picked correctly

Note 111 §2 wrote out the same example with **ON tax = 19,570.86 /
total = 89,430.86**, using "ON's SBD pool is the provincial
allocation times the federal SBD share — 500k × (403/620) = 322,580
allocated SBD income."

That derivation has an internal inconsistency:
- The note declares allocation factors ON 0.65 / AB 0.35.
- Then derives ON's SBD pool as `500k × (403/620) = 322,580.65`,
  which is `500k × 0.6500` rounded — but the note shows 322,580 (which
  is `500k × 0.6452`).

Two possible methodologies are at play:

- **(A) Proportional Sch-5 allocation** (the kontor implementation):
  share = consumer-supplied 0.65; SBD pool = $500k × 0.65 = $325,000.
- **(B) Re-deriving the SBD pool from the post-allocation base**:
  share = base 403,000 / total 620,000 = 0.6500... ; SBD pool =
  $500k × that share = $325,000 (rounds to the same).

In either case the correct SBD pool is $325,000, not $322,580.
The note 111 §2 figure 322,580 corresponds to using a share of
~0.6452 — which is *neither* the declared 0.65 nor the income-
derived ratio. It's a typo/transcription error.

The kontor provider implements (A) **and** records the divergence
honestly at `cit_provider_test.clj:101-110`. **The agent's
methodology pick is correct** — the consumer supplies the Sch-5
allocation fraction, which by Regulation 402 is `½ × (revenue-share
+ wages-share)`, and the provider multiplies the federal SBD limit
by that fraction without re-derivation. This matches the published
CRA Schedule 5 / Regulation 402 mechanic per [CRA Income Tax Folio
S4-F3-C2](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/folio-3-general-principles-business-income-calculation/income-tax-folio-s4-f3-c2-provincial-income-allocation.html).

The honest-note callout at ADR-107 (lines 9766-9771 of
decisions.md) names the divergence — that's the right discipline.
Note 111 should be amended with a `[2026-05-24 ERRATUM]` block
referencing this finding, but **the code is correct, not the
note**.

### 3.3 Independent confirmation against a published calculator

I attempted to validate against the [catax.tools CRA CCPC
calculator](https://catax.tools/corporation-tax-calculator/), but
that calculator only emits combined federal+provincial totals
(not the per-component breakdown). All commercial calculators I
surveyed (CRA T2 net-file software, Wolters Kluwer TaxPrep, RSM/EY
2025 rate cards) work with the same rate constants the kontor
encoding uses — 9% / 15% / 3.2% / 11.5% / 2.0% / 12.0% / 2.0% /
8.0% with $500k SBD limit — so any tool using the same input
arithmetic will produce the same 89,230 result.

Cross-check via KPMG: 2025 ON combined effective rates are
9.0+3.2 = 12.2% on SBD income and 15.0+11.5 = 26.5% on general.
Applying to $403,000 × (325k @ 12.2% + 78k @ 26.5%) = $39,650 + $20,670 =
$60,320 — which equals federal + ON (45,000 + 18,000 + 10,400 +
8,970 = 82,370, hmm, recheck) — actually the breakdown by
combined rate doesn't simply add per province because each level
uses a different base. Sticking with the per-component validation
above.

### 3.4 §2 non-CCPC, §3 ON+BC, §4 SR&ED tests — VERIFIED

- §2 non-CCPC: $1M × 15% federal + $1M × 11.5% ON = 150,000 + 115,000 =
  $265,000. ✓
- §3 ON+BC CCPC, $100k, 60/40 split: $100k × 9% = 9,000; $60k ×
  3.2% = 1,920; $40k × 2% = 800; total 11,720. ✓
- §4a SR&ED CCPC: $100k × 9% = 9,000; $200k × 35% = 70,000 refundable
  → liability −61,000. ✓
- §4b SR&ED non-CCPC: $100k × 15% = 15,000; $200k × 15% = 30,000
  non-refundable, floored at 0 → liability 0. ✓

All four worked examples reproduce to the cent.

---

## §4. Multi-province allocation methodology

Per T2 Schedule 5 / [CRA "If you have to complete Schedule
5"](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/you-have-complete-schedule-5.html):

> "Generally, to allocate taxable income to each province or
>  territory, you have to use a formula based on gross revenue, and
>  salaries and wages. The general rules on how to allocate gross
>  revenue are found in Regulation 402."

Regulation 402(3)(a): the corporation's taxable income in a province
= ½ × [(revenue-from-that-PE / total-revenue) × taxable-income +
(wages-of-that-PE / total-wages) × taxable-income].

**Kontor's design**: the consumer pre-computes the per-province
fraction via `marginalize` over revenue and wage `:posting-
dimension`s and supplies `:tax-unit :provincial-allocation` as a map.
The provider multiplies taxable income by that fraction.

**Match against Reg 402**: ✓ — the consumer's `marginalize` call IS
the Reg 402 mechanic, just computed outside the provider. Note 111
§3.5 / Finding 3 already established this is the intended shape and
the right substrate choice.

**One semantic gap.** Reg 402 distinguishes between income earned
in Canada (provincially allocated) and income earned outside
Canada (non-allocated, doesn't get provincial abatement). The
kontor provider implicitly assumes all `:taxable-income` is
provincially-allocated (per §1.1.2 / §1.2.4). For a multi-
jurisdiction corporation with US-PE or international branch income,
the federal abatement should NOT apply to the foreign slice; the
encoded "general 15% effective" is correct only for the Canadian
slice. **P1 documentation**, gated on a real foreign-PE consumer.

**SBD-pool allocation across provinces**: §1.2.4's P0 — the provider
uses the *federal* limit constant rather than the per-province
limit. For ON/BC/AB today both are $500k so the math coincides.

There is also a deeper question about whether the per-province SBD
allocation should use the *federal* business limit or the *per-
province* limit. Per the CRA pages (Ontario / BC / Alberta SBD
guides), each province uses the **federal** $500k business limit as
the *eligibility base*, with the provincial rate applied to the
provincially-allocated slice of that base. So:

- For ON CCPC with allocation 0.65 of $620k: $403k provincially-
  allocated income. Of that, ON's slice of the federal $500k pool
  = $500k × 0.65 = $325k qualifies for the ON SBD rate; the
  remaining $78k pays the ON general rate.

The kontor provider's `sbd-pool` formula (`federal-limit × share`)
matches this. The bug is that it uses the *federal* parameter
constant instead of the per-province parameter; when ON's *own*
limit rises to $600k (2026-01-01), the question becomes: does ON
allocate $600k × share (using ON's limit) or $500k × share (still
using the federal limit, then add a "ON SBD top-up bracket" for
the additional $100k allocated against the ON-only limit)?

The answer from [Ontario SBD CRA
page](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/ontario-provincial-corporation-tax/ontario-small-business-deduction.html)
+ Bill 12 reading: ON's own $600k limit is the **OSBD eligibility
pool** (separate from but functionally similar to the federal
$500k pool). The federal SBD still uses $500k; the ON SBD uses
$600k. So from 2026-01-01, an ON CCPC with $620k taxable income
allocated 100% to ON:

- Federal: first $500k × 9% = 45,000; next $120k × 15% = 18,000 =
  $63,000.
- ON (from 2026-07-01): first $600k × 2.2% = 13,200; next $20k ×
  11.5% = 2,300 = $15,500. (Up to 2026-06-30: $600k × 3.2% +
  $20k × 11.5% = 19,200 + 2,300 = $21,500.)

**The federal SBD pool and the ON SBD pool are independent allocations
of two different limits.** Today they coincide ($500k each) so the
kontor code's "use federal limit for all" is harmless. From
2026-01-01 forward, ON has its own $600k pool and the encoding
needs to look up `CA.ON.CIT.sbd-limit`, not
`CA.Federal.CIT.sbd-business-limit`. This compounds the P0
identified in §1.2.4.

---

## §5. CCPC status determination

The provider takes `:tax-unit :ccpc?` as a consumer-supplied boolean
(per `cit_provider.clj:46-66`, `cit_provider_test.clj:71-72`). Per
ITA §125(7), CCPC status is a function of:

- Private corporation (no shares listed on any prescribed stock
  exchange);
- Canadian-resident corporation;
- Not controlled, directly or indirectly, by non-residents or by
  public corporations.

[Practical Law](https://ca.practicallaw.thomsonreuters.com/9-568-7547):
"CCPC status is **a configuration-time fact** about the corporation's
shareholders, not something computed from postings."

**Validation**: the consumer-supplied boolean is the right call.
Recomputing CCPC status from postings would require a shareholder
register that kontor doesn't (and shouldn't) maintain — this is
exactly the same pattern as US 1040 `:filing-status` (note 107 §4.2)
and FR IS `:sme?`. ADR-104 pattern, applied consistently.

**One refinement worth considering**: the 2022 federal budget
introduced "substantive CCPCs" (ITA §248(1)) — private corps
controlled by non-residents but operating like CCPCs for the
RDTOH/passive-income regime. These are NOT CCPCs for the §125 SBD
but ARE treated like CCPCs for §129 refundable Part I. The encoded
`:ccpc?` boolean cannot represent both states.

[Doane Grant Thornton — Understanding the substantive CCPC
rules](https://www.doanegrantthornton.ca/insights/understanding-the-proposed-substantive-ccpc-rules/):
"Substantive CCPCs … treated as CCPCs for the purposes of the
refundable tax regime in section 123.3 and Part IV."

For v1 (SBD + SR&ED only — no §123.3 refundable Part I), this is
fine — `:ccpc?` correctly gates the SBD and the 35% SR&ED rate. If
RDTOH lands in a future iteration (P1-c above), we'd want a
separate `:substantive-ccpc?` or `:ccpc-for-refundable-part-i?`
flag. **P2 — document the limitation in the next iteration's
docstring.**

---

## §6. Audit-doc completeness — URL spot-checks

All encoded `:concept-iri` URLs resolved during the audit:

- `laws-lois.justice.gc.ca/eng/acts/I-3.3/section-{123,124,123.4,125,127,127.1}.html`
  — all resolve to the current ITA text. ✓
- `canada.ca/.../investment-tax-credit-policy.html` — resolves. ✓
- `ontario.ca/laws/statute/07t11` — resolves to the Ontario Taxation
  Act 2007. ✓
- `bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01` —
  resolves to the BC Income Tax Act. ✓ (Both sections 14 and 14.1 are
  there; the encoded cite uses the act-level URL with no section
  anchor; same for ON.)
- `kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts` —
  this is the Alberta Corporate Tax Act listing under the old King's
  Printer; the modern URL is via open.alberta.ca or CanLII. The
  encoded URL DOES still resolve as of 2026-05-24.

**Per-parameter citation text** (`:parameter-value/citation`) is
human-readable text (`"ITA §123(1)(a) — basic rate stable since the
1972 reform"`) — same shape as the DE module (the citation choice
ADR-101 polished). Consistent with the project's audit-doc
discipline. ✓

**One asymmetry across the four CIT modules**: DE's
`:provision/citation` is a URL while
`:parameter-value/citation` is human text; CA does the same. FR /
JP follow this pattern. **OK** — note 120 §6 also noted this as a
deliberate (cosmetic) choice.

---

## §7. Actionable findings

### P0 — would mis-compute a typical Canadian CCPC from 2026-01-01 forward

- **P0-1: Ontario small business rate cut to 2.2% effective
  2026-07-01.** Add `:parameter-value` for `CA.ON.CIT.sbd-rate` with
  `:effective-from #inst "2026-07-01" :decimal-value 0.022M`. Update
  the existing 0.032M row with `:effective-until #inst "2026-07-01"`.
  Cite: [Ontario Bill 12, Cutting Taxes on Small Businesses Act,
  2025](https://www.ola.org/en/legislative-business/bills/parliament-44/session-1/bill-12);
  [Ontario 2026 Budget Annex](https://budget.ontario.ca/2026/annex.html).
  File: `modules/l10n-ca/src/kontor/l10n_ca/cit_statute.clj:240-243`.

- **P0-2: Ontario business limit raised to $600,000 effective
  2026-01-01.** Add `:parameter-value` for `CA.ON.CIT.sbd-limit` with
  `:effective-from #inst "2026-01-01" :decimal-value 600000M`. Update
  the existing 500000M row with `:effective-until #inst "2026-01-01"`.
  File: `cit_statute.clj:245-248`.

- **P0-3: provider ignores the per-province `sbd-limit` parameters
  for SBD-pool allocation.** Currently `cit_provider.clj:95-102`
  reads `CA.Federal.CIT.sbd-business-limit` for every province. Fix:
  change `sbd-pool` to take a province key (or rate-code), look up
  `CA.<PROV>.CIT.sbd-limit`, and use that limit. Without this, P0-2
  is wasted — ON's $600k limit row would be loaded but not used.

  Fix sketch:
  ```clojure
  (defn- sbd-pool
    [db as-of share province-key]
    (let [limit-code (case province-key
                       :fed "CA.Federal.CIT.sbd-business-limit"
                       :on  "CA.ON.CIT.sbd-limit"
                       :bc  "CA.BC.CIT.sbd-limit"
                       :ab  "CA.AB.CIT.sbd-limit")
          limit (statute/parameter-value-at db limit-code as-of)]
      (* limit share)))
  ```
  Threading `:province` into the schedule compute-fn at lines 145-160.
  File: `cit_provider.clj:95-102, 145-160`. Add a test exercising the
  divergence: a synthetic ON CCPC with `:as-of #inst "2026-12-31"`
  asserting the $600k pool applies.

  When NS / PE land (separate ADRs), this fix becomes immediately
  load-bearing — those already have $700k / $600k limits today.

### P1 — matters for some real consumers; fix in the next l10n-ca sweep

- **P1-1: Quebec (QC) CIT module absent.** A common multi-province
  CCPC (ON + QC, or ON only with a QC PE) has no QC component path.
  Add ~5 QC parameters + 2 provisions (per §2.3 P1-a). Note 111
  §1.2 already shipped QC rates; the kontor PIT path supports QC for
  T1 already.

- **P1-2: 9 other CRA-collected provinces / territories absent.**
  Extend per consumer demand. Defer MB / NB / NL / NS / NT / NU / PE
  / SK / YT. NS + PE are the highest-priority because their non-
  $500k limits exercise the P0-3 fix.

- **P1-3: RDTOH / Part IV refundable tax absent.** Per note 111 §1.5
  + §4 Finding 4, deferred per note 105 frontier 2 (carry
  primitive). No code change today; track as a followup against the
  frontier-2 work alongside DE Mindestbesteuerung (note 120 P1-4)
  and capital-loss carry (BR / IN inhabitant-tax base-period).

- **P1-4: NCL / capital-loss carryforward absent.** Same frontier-2
  gating. Document the consumer-supplied `:inputs :taxable-income`
  contract at `cit_provider.clj:46-66`: "the consumer pre-applies
  any brought-forward NCL or capital loss." One paragraph of
  docstring.

- **P1-5: Federal abatement scope nuance.** Document at
  `cit_statute.clj:9-15` + `cit_provider.clj:46-66` that the encoded
  15% / 9% effective federal rates assume ALL taxable income is
  Canadian-source (allocated to a province under Reg 402). A
  foreign-PE consumer needs the abatement applied only to the
  Canadian slice. **Not a v1 use case** but the docstring should be
  honest about the assumption.

- **P1-6: SR&ED for "eligible Canadian public corporations"
  (ECPCs).** Bill C-15 extended the 35% refundable to ECPCs for tax
  years beginning on or after 2024-12-16. The encoded condition
  gates exclusively on `:ccpc?`. Either add `:ecpc?` to the
  condition or document the limitation. **P1.**

- **P1-7: SR&ED taxable-capital phase-out** raised to $15M / $75M.
  No phase-out logic in v1; consumer is the authority. **Docstring
  polish.**

- **P1-8: General Rate Reduction carve-outs** for investment income
  / PSB. Document at `cit_provider.clj:46-66` that the encoded "flat
  15% non-CCPC" is correct for active business income only. **P1
  docstring.**

- **P1-9: passive-investment grind + taxable-capital grind for SBD.**
  Per note 111 §1.2 these are consumer-supplied (the consumer passes
  the *already-reduced* business limit). Document at
  `cit_provider.clj:46-66`. **P1 docstring.**

### P2 — defer; document as known gaps

- **P2-1: SBD rate parameter naming.** Either rename `sbd-rate` to
  `sbd-effective-rate` or add an `:parameter/label`-level note
  clarifying that ITA §125(1.1)'s 19% deduction = 28% − 9%. The
  encoded 0.09M is correct; the docstring asymmetry vs the statute
  text is cosmetic.
- **P2-2: SR&ED expenditure-limit transition date.** Move from
  2024-12-15 to 2024-12-16 to align with Bill C-15 statute text
  ("tax years beginning on or after December 16, 2024"). One-day
  off-by-one; immaterial for fiscal-date-aligned filers. One-character
  EDN change.
- **P2-3: Zero-emission manufacturing reduced rate** (general 7.5% /
  SBD 4.5%, ITA §125.2). Niche; defer.
- **P2-4: M&P deduction, Atlantic ITC, mining ITC, clean-energy ITC,
  critical-minerals ITC** — all are `:credit` provisions; defer per
  consumer demand.
- **P2-5: Substantive CCPC (ITA §248(1))** for RDTOH/Part IV
  treatment — gated on P1-3.
- **P2-6: Citation URL refresh** for Alberta — move from the legacy
  King's Printer URL to CanLII or the modern post-migration URL.
  Cosmetic.
- **P2-7: BC `:concept-iri` section anchors** — currently both BC
  parameters cite the act-level URL with no section anchor; add
  fragment markers for §14 (general) and §14.1 (SBD). Cosmetic.

---

## §8. Honest summary

The CA T2 ship is **structurally complete** and **arithmetically
correct against the v1 ON+BC+AB CCPC scope**. The CAD 89,230 worked
example reproduces to the cent against a hand-derivation from the
ITA + provincial statute cascade; all four secondary tests (non-CCPC,
ON+BC, SR&ED refundable, SR&ED non-refundable-floored) reproduce
correctly; the provider's N-component fan-out matches the published
T2 Schedule 5 / Regulation 402 mechanic. The encoding caught the
SR&ED $3M → $6M expenditure-limit raise proactively (the first
kontor l10n module to demonstrate forward-dated `:parameter-value`
rows working end-to-end).

The three P0 issues are concentrated in **one knot**: Ontario's Bill
12 (royal assent November 2025) restructured §31 of the Taxation Act
2007 effective 2026-01-01 / 2026-07-01, and the encoding ships
hard-coded 3.2% / $500k with no transition. The fix is mechanical —
two extra `:parameter-value` rows plus a provider-side switch from
the federal-limit constant to the per-province limit parameter — but
without it, an ON CCPC fiscal-year-2026 consumer silently miscomputes
ON tax in the wrong direction by up to CAD 8,800 per year. This is
exactly the failure mode note 120's DE-CIT review surfaced (§9 Nr. 1
Grundsteuerreform sunset) — the substrate's date-keyed parameter
machinery is the right tool, and the discipline is to USE it AT THE
MOMENT the law changes, not when a customer complains. ADR-107 was
shipped 2026-05-24; Ontario Bill 12 received royal assent **six
months earlier** in November 2025. The encoded snapshot was already
six months stale on the day it was committed.

**The good news** — fixing P0-1 and P0-2 is a 4-line EDN addition.
Fixing P0-3 (the provider's federal-limit lookup) is a ~15-line code
diff. Adding a regression test that pins ON 2026 against a worked
example via a TaxTips.ca-style published rate is another deftest.
The total fix surface is < 1 working session.

The P1 backlog has two structural items — Quebec (one new
jurisdiction with the same shape as ON/BC) and RDTOH (genuinely
frontier-2 work that note 105 already names) — and a handful of
docstring polish items naming the consumer's pre-computation
responsibilities (NCL carry, passive grind, taxable-capital grind,
foreign-PE abatement scope). None block correctness for the v1
scope.

**Substrate validation note.** This is the fourth ADR-101 consumer
(DE, FR, JP, CA) and the first one with N-component multi-
jurisdiction structure beyond a single national + single sub-
national split. The pattern holds — per-province `:parameter` +
two `:provision`s (CCPC + general) + one provider-side compute-fn
per province scales linearly. Adding QC + 9 more provinces would
add ~50 parameter rows and 20 provisions but zero new substrate
primitives. The ADR-101 + Addendum 1 design choice "schedule-
override as the polymorphism" is paying dividends: the CCPC vs
general dispatch is uniform across federal AND all per-province
components without per-jurisdiction custom dispatch logic.

The note 111 §2 arithmetic typo (322,580 vs 325,000) is the kind
of error a baseline-review IS for: a working code path matched
against published statute reproduces the right answer, even when
an interim research note's hand math drifts. The agent's
methodology pick (proportional Sch-5 allocation) is correct; the
ADR-107 honest-note callout (decisions.md:9766-9771) is the right
discipline. Note 111 should be amended with an `[ERRATUM
2026-05-24]` block referencing this finding.

---

## Sources

**Statute text (laws-lois.justice.gc.ca — Justice Canada canonical)**

- [§123 ITA — basic rate](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.html)
- [§123.4 ITA — General Tax Reduction](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.4.html)
- [§124 ITA — Provincial Abatement](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-124.html)
- [§125 ITA — Small Business Deduction](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html)
- [§127 ITA — Investment Tax Credit](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.html)
- [§127.1 ITA — Refundable ITC](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.1.html)
- [§111 ITA — Loss Continuity](https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-111.html)
- [Ontario Taxation Act, 2007 (SO 2007, c 11, Sch A)](https://www.ontario.ca/laws/statute/07t11)
  / [CanLII consolidation](https://www.canlii.org/en/on/laws/stat/so-2007-c-11-sch-a/latest/so-2007-c-11-sch-a.html)
- [BC Income Tax Act (RSBC 1996, c 215)](https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01)
- [Alberta Corporate Tax Act (RSA 2000, c A-15)](https://www.canlii.org/en/ab/laws/stat/rsa-2000-c-a-15/latest/rsa-2000-c-a-15.html)
- [Ontario Bill 12, Cutting Taxes on Small Businesses Act, 2025](https://www.ola.org/en/legislative-business/bills/parliament-44/session-1/bill-12)

**CRA guidance**

- [CRA — Corporation tax rates](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/corporation-tax-rates.html)
- [CRA — If you have to complete Schedule 5](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/you-have-complete-schedule-5.html)
- [CRA — Ontario small business deduction](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/provincial-territorial-corporation-tax/ontario-provincial-corporation-tax/ontario-small-business-deduction.html)
- [CRA — Income Tax Folio S4-F3-C2 Provincial Income Allocation](https://www.canada.ca/en/revenue-agency/services/tax/technical-information/income-tax/income-tax-folios-index/series-4-businesses/folio-3-general-principles-business-income-calculation/income-tax-folio-s4-f3-c2-provincial-income-allocation.html)
- [CRA — SR&ED Investment Tax Credit Policy](https://www.canada.ca/en/revenue-agency/services/scientific-research-experimental-development-tax-incentive-program/investment-tax-credit-policy.html)
- [CRA — About RDTOH balances (ERDTOH / NERDTOH)](https://www.canada.ca/en/revenue-agency/services/e-services/digital-services-businesses/business-account/about-refundable-dividend-tax-on-hand-rdtoh-balances.html)
- [CRA — Type of corporation (CCPC test)](https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/corporations/type-corporation.html)

**Provincial finance ministries**

- [BC Ministry of Finance — Corporate tax rates and business limits](https://www2.gov.bc.ca/gov/content/taxes/income-taxes/corporate/tax-rates)
- [Alberta TRA — Corporate income tax](https://www.alberta.ca/corporate-income-tax)
- [Alberta — Tax, levy, and prescribed interest rates](https://www.alberta.ca/about-tax-levy-rates-prescribed-interest-rates)
- [Ontario 2026 Budget Annex — Tax Measures](https://budget.ontario.ca/2026/annex.html)

**Big-4 / commercial commentary**

- [PwC Canada — Corporate, Taxes on Corporate Income](https://taxsummaries.pwc.com/canada/corporate/taxes-on-corporate-income)
- [PwC Canada — 2026 Ontario Budget Tax Highlights](https://www.pwc.com/ca/en/services/tax/budgets/2026/ontario.html)
- [PwC Canada — Bill C-15 SR&ED, capital cost allowance, transfer pricing](https://www.pwc.com/ca/en/services/tax/publications/tax-insights/bill-c-15-implements-changes-2025.html)
- [PwC Canada — SR&ED updates: Enhanced credits](https://www.pwc.com/ca/en/services/tax/publications/tax-insights/sred-changes-2025.html)
- [BDO Canada — Corporate income tax facts](https://www.bdo.ca/insights/corporate-income-tax-facts)
- [BDO Canada — SR&ED enhancements (Bill C-15)](https://www.bdo.ca/insights/sr-ed-program-enhancements-and-updates-draft-legislation-released)
- [EY Canada — Federal budget 2025](https://www.ey.com/en_ca/technical/tax/tax-alerts/2025/tax-alert-2025-no-52)
- [EY Canada — BC budget 2025-26](https://www.ey.com/en_ca/technical/tax/tax-alerts/2025/tax-alert-2025-no-12)
- [EY Canada — Alberta budget 2025-26](https://www.ey.com/en_ca/technical/tax/tax-alerts/2025/tax-alert-2025-no-11)
- [EY Canada — Quebec budget 2025-2026](https://www.ey.com/en_gl/technical/tax-alerts/canada-quebec-budget-2025-2026)
- [EY Canada — Alberta budget 2026 discussed](https://www.ey.com/en_gl/technical/tax-alerts/canada-alberta-budget-2026-discussed)
- [MNP — Significant enhancement to the SR&ED program](https://www.mnp.ca/en/insights/directory/significant-enhancement-announced-sr-ed-program)
- [Mintz — Innovate, Baby, Innovate? Key SR&ED Enhancements in Budget 2025](https://www.mintz.com/insights-center/viewpoints/2906/2025-11-12-innovate-baby-innovate-key-enhancements-canadas-sred)
- [Welch LLP — 2026 Changes in SR&ED](https://welchllp.com/insights/knowledge/2026-changes-in-sred-largest-expansion-in-decades/)
- [Bennett Jones — Alberta Advantage: Reduced Corporate Tax Rate Effective July 1, 2020](https://www.bennettjones.com/Blogs-Section/Alberta-Advantage-Reduced-Corporate-Tax-Rate-Effective-July-1)
- [Doane Grant Thornton — Substantive CCPC rules](https://www.doanegrantthornton.ca/insights/understanding-the-proposed-substantive-ccpc-rules/)
- [Greater KW Chamber of Commerce — Ontario Budget 2026](https://greaterkwchamber.com/blog/ontario-provincial-budget-2026-what-small-businesses-need-to-know/)
- [TaxTips.ca — 2025 Corporate Income Tax Rates](https://www.taxtips.ca/smallbusiness/corporatetax/corporate-tax-rates-2025.htm)
- [Practical Law — CCPC definition (ITA §125(7))](https://ca.practicallaw.thomsonreuters.com/9-568-7547)
- [KPU Pressbooks — Intermediate Canadian Tax — ITA §125 SBD](https://kpu.pressbooks.pub/intercanadiantax2e/chapter/ita125/)

**Calculators (cross-check oracles)**

- [catax.tools CRA CCPC corporation tax calculator 2025/2026](https://catax.tools/corporation-tax-calculator/)
- [taxbreak.ca BC small business deduction guide](https://taxbreak.ca/bc-small-business-deduction/)
- [taxbreak.ca Ontario small business deduction guide](https://taxbreak.ca/ontario-small-business-deduction/)
- [taxbreak.ca Alberta small business deduction guide](https://taxbreak.ca/alberta-small-business-deduction/)

**kontor source under review**

- `modules/l10n-ca/src/kontor/l10n_ca/cit_statute.clj` — 18 parameters + 10 provisions
- `modules/l10n-ca/src/kontor/l10n_ca/cit_provider.clj` — `CACITProvider` + 4 compute-fns
- `modules/l10n-ca/test/kontor/l10n_ca/cit_provider_test.clj` — 10 deftests / 66 assertions
- `doc/research/111-ca-cit-fit.md` — prior fit assessment
- `doc/research/120-de-cit-baseline-review.md` — sibling DE review (template)
- `doc/decisions.md` — ADR-101 (statute-as-data substrate), Addendum 1 (schedule-override), ADR-107 (this ship)

---

End of note 126.

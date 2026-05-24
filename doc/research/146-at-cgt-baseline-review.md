---
date: 2026-05-24
title: 146 — AT CGT (KESt + ImmoESt + §10 KStG) baseline review against statute + practitioner sources
audience: maintainer
status: review-after — ADR-103 AT consumer (parallel-agent ship); third end-to-end CGT consumer after DE / FR / JP CIT
---

# 146 — AT CGT baseline review

ADR-103 shipped the AT CGT triplet (`at-kest-cgt-provider`,
`at-immoest-provider`, `at-corporate-cgt-provider`) as the per-
jurisdiction CGT consumer for Austria. Twelve `:parameter`s + zero
`:provision`s in `modules/l10n-at/src/kontor/l10n_at/cgt_statute.clj`,
three `PeriodTaxProvider` records in `cgt_provider.clj` (~760 lines),
and 14 deftests / ~50 assertions in `cgt_provider_test.clj` covering
the four note-134 worked examples (Frau Huber OMV €4 950 KESt, Herr
Mayer Hauptwohnsitz €0 ImmoESt, Müller-Holding §10 default-exempt €8M
deduction, Müller-Holding §10-Option + loss €285 714 Siebentel slice).
Per the maintainer's standing baseline-review mandate, this note
audits the encoding against ris.bka.gv.at + jusline.at (statute),
findok.bmf.gv.at + bmf.gv.at (admin guidance), and Austrian Big-4 /
boutique practitioner commentary (KPMG / PwC / Deloitte / EY / WKO /
ÖSV / ICON / Brandauer / Schelhammer / Erste Group).

**Headline.** Headline rates, effective dates, and the four worked
examples reproduce to the cent against authority-published figures.
The §10 KStG INVERSION (the most subtle design call per the prompt)
is correctly modelled — the default branch emits
`:cit-base-deductions [gain]` to net out what the GL already booked
as ordinary income, and the opt-in `:at-§10-tax-effective-option`
correctly routes both gain (→ `:cit-base-additions`) and loss (→
Siebentelregelung 1/7 spread). The Hauptwohnsitz OR-gate (`some` over
the two flags) is correctly implemented. KESt no-carryforward is
correctly enforced via clamp-to-zero. Regelbesteuerungsoption folds
the net into `:pit-base-additions` as designed.

**Two P0 findings**, both ship-date-relevant:

- **P0-1 — Umwidmungszuschlag deferral is silent**. The 2025
  surcharge (§30 Abs 6a EStG, BBG 2025) entered into force for sales
  after **2025-07-01** where the rezoning was effective after
  **2025-01-01**. ADR-103 shipped 2026-05-24 — TEN months after the
  surcharge went live. Note 134 §1.3 / §4.1 flagged this as PARTIAL
  pending the `:at-immoest/land-share` companion attr, and the AT
  agent confirmed PARTIAL in the prompt. But the shipped code has
  zero affordance — no parameter, no provision, no `:elective-regime`
  keyword, no `nil`-branch in `cgt_provider.clj`, no docstring
  flag in the public namespace docstrings, NO TEST asserting the gap.
  A consumer with a 2026 Bauland disposal silently gets the
  pre-Umwidmungszuschlag figure (30% only, missing the 30% × gain
  surcharge), understating ImmoESt by 30% of (positive gain × land
  share). For a €180k gain (Brandauer's example), the silent loss is
  €54k. The fix is two-fold: (a) docstring fence in
  `cgt-provider.clj:1-78` AND in the constructor docstring, with a
  WARNING-level callout AND a thrown `ex-info` on every Bauland-style
  disposal in 2025-07-01+ until the companion attrs ship; (b) a
  failing test pinned to a 2025-12-01 :as-of demonstrating the
  current behaviour and labelled `^:pending`. Without (a), the silent
  miscompute is exactly the failure mode note 120 (DE §9 Nr 1) and
  note 126 (ON Bill 12) surfaced.

- **P0-2 — §10 KStG default-exempt path is NOT GUARDED by the
  "foreign corporation" predicate**. §10 KStG §10 Abs 2 (the
  *internationale* Schachtelbeteiligung) is explicit: the held entity
  must be a foreign corporation. The encoded constants are correct
  (≥10% + 365 days, both as `:parameter`s), but the provider's
  qualifying check (`cgt_provider.clj:204-218`) only verifies
  ownership-fraction and holding-period. The docstring at line
  207-210 acknowledges "the consumer assumes the entity is foreign
  when `:at-§10-participation` is set", but the substrate has a
  perfectly good signal — `:disposal/subject-form :corp` plus a
  jurisdiction lookup on the `:disposal/subject` (or even just a
  consumer-supplied `:tax-unit :held-entity-domestic?` boolean) —
  that the provider IGNORES. A consumer who tags an AT-domestic
  Kapitalgesellschaft Schachtelbeteiligung with
  `:at-§10-participation` would receive the §10 KStG INVERSION
  treatment, silently routing the gain to `:cit-base-deductions`.
  In reality, §10 Abs 1 Z 1 KStG already exempts dividends from
  domestic participations WITHOUT a holding-period test BUT gains
  on domestic participations are NOT exempt (per the WKO source and
  the jusline.at extract: "Disposal gains are NOT exempt under this
  tier — they remain ordinary corporate income"). The provider's
  silent inclusion of domestic participations in the INVERSION is
  therefore the *opposite* of statute. Fix: thread a
  `:held-entity-domestic?` flag through `:tax-unit` (consumer
  attests; matches the CCPC/PME pattern from ADR-104/105/107) and
  short-circuit to "no component" when true. Failing test should
  document the routing.

Everything else triages to **P1** (specific gaps: immediate-offset
elective for §30 Abs 7 loss carry; missing tax-effective §10-Option
loss-cap rule; KESt 25% interest rate parameter is dead code; missing
§10 Abs 3 anti-abuse switch-over) or **P2** (statute-cite polish, the
`:provisions []` empty vector, line-item field naming, doc-string
fence on §9 KStG / §6 Z 2 lit c). Sections below cite file:line.

---

## §1. Statute-fidelity audit — parameter by parameter

### 1.1 KESt rates (§27a EStG)

#### 1.1.1 `AT.EStG.§27a.kest-financial-rate` = 0.275M from 2016-01-01 — VALUE CORRECT, DATE CONSERVATIVE

`cgt_statute.clj:145-148`. The 27.5% rate was introduced by the
**StRefG 2015/2016 (BGBl I 2015/118)** effective 2016-01-01 — yes,
correct. The *preceding* rate was 25% on capital-asset gains from
the 2012-04-01 BBG 2011 introduction, raised to 27.5% in 2016.
For periods pre-2016 the parameter returns nil; the test suite only
exercises 2026 periods, so this is a latent gap for a back-dated
filer but irrelevant for v1 scope.

Triage: **P2** — add a 0.25M row with `:effective-from 2012-04-01
:effective-until 2016-01-01` for symmetry with the ImmoESt rate
ladder which DID include both rows (`cgt_statute.clj:161-170`). The
agent did this for ImmoESt but not for KESt — asymmetric, fixable in
one EDN block. **OK.**

#### 1.1.2 `AT.EStG.§27a.kest-interest-rate` = 0.25M from 2012-04-01 — DEAD CODE

`cgt_statute.clj:150-153, 60-64`. The 25% rate applies to bank-deposit
interest under §27a Abs 1 Z 1 EStG. **The interest is not a
disposal event** (note 134 §3 row 2 confirms: "interest is not on
`:disposal` — interest is recurring income, handled by the existing
`kontor.book/receive-payment`"). The KESt provider does not read this
parameter — `(kest-asset-classes :at-kest-aktien :at-kest-anleihen
:at-kest-fonds :at-kest-derivate)` is the closed set at line 90-94,
and the rate looked up at line 529-530 is the financial rate
(`AT.EStG.§27a.kest-financial-rate`), never the interest rate.

This is the prompt's check 2: *"Bank interest is NOT a disposal event
per note 134; the 25% is parameterized but should not fire on
disposal. Verify."* — **VERIFIED CORRECT in code**. The parameter is
parked for future use (e.g., a `kontor.book/receive-interest` style
helper would consume it). **OK** as a documented intent, but the
parameter is *currently* dead code — a reader of the statute file
might reasonably expect it to be wired.

Triage: **P2 docstring** — add a comment at the parameter definition
(`cgt_statute.clj:60-64`) explaining that the rate is parked for a
future interest-recognition provider, not consumed by the CGT
providers. Cite note 134 §3. Cosmetic.

#### 1.1.3 `AT.EStG.§6-Z2-lit-c.business-loss-limit` = 0.55M from 2014-01-01 — VALUE CORRECT, DEAD CODE BY DESIGN

`cgt_statute.clj:155-158, 66-70`. The 55% Begrenzung is the
Betriebsvermögen-side rule from §6 Z 2 lit c EStG (per note 134 §1.6
final paragraph). Per the prompt's check 5: *"55% Begrenzung — encoded
but not exercised in v1 (per agent — it's a Betriebsvermögen rule
outside CGT scope per §27a Abs 6 EStG). Verify the deferral is
documented."* The statute file's docstring at `cgt_statute.clj:32-35`
calls this out: "Business-side rule for KESt-Vermögen losses in
Betriebsvermögen offset against ordinary income. The 55% factor
matches the 27.5% flat rate × 2 to keep the integration ratio
honest". **OK** — deferral documented.

**One nit**: the parameter is loaded into the DB but no provider
reads it. The deferral note belongs in the *provider* file as well,
not just the statute file (a reader of `cgt_provider.clj` shouldn't
need to cross-reference to learn this is intentionally unused).
Triage: **P2 docstring polish**.

### 1.2 ImmoESt rates (§30 / §30a EStG)

#### 1.2.1 `AT.EStG.§30a.immoest-rate` ladder — CORRECT

`cgt_statute.clj:161-170`. Two rows:
- 0.25M from 2012-04-01 to 2016-01-01 (BBG 2011)
- 0.30M from 2016-01-01 (StRefG 2015/2016)

Both dates and values verified against jusline.at §30a EStG and PwC
Worldwide Tax Summaries Austria Individual. **OK.**

#### 1.2.2 `AT.EStG.§30.altvermoegen-unwidmet-effective-rate` = 0.042M from 2016-01-01 — CORRECT BUT DATE BOUNDARY MISSING

`cgt_statute.clj:172-175`. The math is right (86% deemed basis
→ 14% deemed gain × 30% = 4.2% effective on gross). The
**pre-2016** effective rate was **3.5%** (14% × 25% = 3.5%) — the
parameter does NOT have a row for the 2012-04-01 to 2016-01-01
window. Same shape gap as §1.1.1. For v1 (2026 :as-of) irrelevant.

Triage: **P2** — add a 0.035M row with the pre-2016 window for
symmetry. The statute file's docstring at `cgt_statute.clj:136-141`
explicitly cites both rates ("3.5 % / 15 % proceeds-effective
pre-2016; 4.2 % / 18 % post-2016") so the *intent* was to encode
both — only the post-2016 made it into the data table. Asymmetric.

#### 1.2.3 `AT.EStG.§30.altvermoegen-gewidmet-effective-rate` = 0.18M from 2016-01-01 — CORRECT, SAME P2 ON THE PRE-2016 ROW

`cgt_statute.clj:177-180`. 60% deemed gain × 30% = 18%. Same gap as
§1.2.2 (missing 0.15M = 60% × 25% pre-2016 row).

#### 1.2.4 `AT.EStG.§30-Abs-7.loss-carry-factor` = 0.60M from 2012-04-01 — CORRECT

`cgt_statute.clj:182-185`. §30 Abs 7 EStG: *"ist dieser auf 60% zu
kürzen"* — verbatim 60%. Verified against jusline.at §30 EStG.
Effective-from 2012-04-01 matches the BBG 2011 introduction. **OK.**

#### 1.2.5 `AT.EStG.§30-Abs-7.loss-carry-years` = 15M from 2012-04-01 — VALUE CORRECT, UNIT/SHAPE NIT

`cgt_statute.clj:187-190`. §30 Abs 7 says *"gleichmäßig auf das Jahr
der Verlustentstehung und die folgenden vierzehn Jahre zu
verteilen"* — 15 calendar years (loss year + 14). **Value correct.**

**One subtle nit**: the parameter is stored with
`:parameter/unit :ratio` (`cgt_statute.clj:100`). The kernel schema
at `src/kontor/schema.clj:1313-1321` allows only
`:rate | :amount-money | :threshold | :ratio | :bracket-scale` —
there is no `:integer` or `:count` unit. So `:ratio` is the right
*closed-set* choice. But the value `15M` is a count of years, not a
ratio in the usual sense. The same choice was made for `:siebentel-
years 7M` (`cgt_statute.clj:122-125`) and
`:qualifying-holding-days 365M` (`cgt_statute.clj:116-120`). The
Catala-inspired vocabulary doesn't have a "duration" or "count" unit
yet. Consistency holds; the JP/FR/CA modules use `:ratio` for
similar counts (e.g., note 110 §11.x for the 24-month CGE).

Triage: **P2 substrate followup** — consider adding `:integer` /
`:count` / `:duration-days` to the closed unit vocabulary in a
future ADR-101 amendment. Not blocking. **OK** for v1.

### 1.3 CIT rate ladder + §10 parameters

#### 1.3.1 `AT.KStG.cit-rate` ladder — CORRECT

`cgt_statute.clj:193-208`. Three rows:
- 0.25M from 2005-01-01 to 2023-01-01
- 0.24M from 2023-01-01 to 2024-01-01
- 0.23M from 2024-01-01 onward

Verified against [WKO Aktuelle Werte 2024](https://www.wko.at/steuern/aktuelle-werte-einkommen-koerperschaftsteuer-ab-2024)
and [WKO KÖSt page](https://www.wko.at/steuern/koest-koerperschaftsteuer):
"the corporate tax rate is 23% (until 2022: 25%; in 2023: 24%)". The
**ÖkoStRefG 2022 (BGBl I 2022/10)** is the correct citation; the
1972-style 25% had been stable since 2005 (StRefG 2005). The 2005-01-01
effective-from is the conservative choice (the rate WAS reduced from
34% → 25% in StRefG 2005). All three values + dates **OK**.

**Per the prompt's check 1** (*"CIT rate ladder — 25% (2022) → 24%
(2023) → 23% (2024+). Three date-keyed parameter-values. Verify the
dates."*): **VERIFIED CORRECT.** Test `cit-rate-ladder-2024-23-
percent` at `cgt_provider_test.clj:488-500` exercises all three
windows. ✓

#### 1.3.2 `AT.KStG.§10.qualifying-ownership-fraction` = 0.10M — CORRECT

`cgt_statute.clj:110-114, 210-213`. §10 Abs 2 KStG: *"mindestens zu
einem Zehntel"* — exactly 10%. **OK.**

#### 1.3.3 `AT.KStG.§10.qualifying-holding-days` = 365M — CORRECT

`cgt_statute.clj:116-120, 215-218`. §10 Abs 2 KStG: *"während eines
ununterbrochenen Zeitraumes von mindestens einem Jahr"* — 12
consecutive months. **OK.**

**Minor nit**: 365 days vs 12 calendar months — for a leap-year
straddle the day-count interpretation (365 days) is one day STRICTER
than the calendar-month interpretation (which a Feb-29 acquisition
would satisfy on Feb-28 of the next year, 364 days). The KStR 2013
Rz 1216 reads "12 Monate" not "365 Tage". For the v1 scope
(no Feb-29 edge cases in the tests), this is harmless; for a
production consumer it's a known data-modelling choice.

Triage: **P2** — document the choice in the provider docstring
(`cgt_provider.clj:204-218`); the kernel doesn't have a "12-month"
arithmetic helper today, so day-count is the pragmatic choice.

#### 1.3.4 `AT.KStG.§12-Abs-3-Z-2.siebentel-years` = 7M — CORRECT

`cgt_statute.clj:122-125, 220-222`. **OK** — verbatim 7-year spread
per §12 Abs 3 Z 2 KStG.

### 1.4 Parameter-coverage summary

| Encoded? | Source | Value | Date | Verdict |
|---|---|---|---|---|
| KESt 27.5% financial | §27a Abs 1 Z 2 EStG | 0.275M | 2016-01-01+ | OK (P2: pre-2016 0.25M row missing) |
| KESt 25% interest | §27a Abs 1 Z 1 EStG | 0.25M | 2012-04-01+ | OK but dead code (P2: docstring) |
| 55% Begrenzung | §6 Z 2 lit c EStG | 0.55M | 2014+ | OK but dead code by design (P2) |
| ImmoESt 30% | §30a Abs 1 EStG | 0.30M | 2016-01-01+ | OK |
| ImmoESt 25% (pre-2016) | §30a Abs 1 EStG | 0.25M | 2012-04-01 to 2016-01-01 | OK |
| Altvermögen unwidmet 4.2% | §30 Abs 4 Z 2 EStG | 0.042M | 2016-01-01+ | OK (P2: pre-2016 3.5% row) |
| Altvermögen gewidmet 18% | §30 Abs 4 Z 1 EStG | 0.18M | 2016-01-01+ | OK (P2: pre-2016 15% row) |
| §30 Abs 7 60% factor | §30 Abs 7 EStG | 0.60M | 2012-04-01+ | OK |
| §30 Abs 7 15 years | §30 Abs 7 EStG | 15M | 2012-04-01+ | OK |
| CIT 25/24/23% | §22 KStG | three rows | 2005/2023/2024 | OK |
| §10 KStG 10% threshold | §10 Abs 2 KStG | 0.10M | 2011+ | OK |
| §10 KStG 365 days | §10 Abs 2 KStG | 365M | 2011+ | OK (P2: 12-month interp) |
| §12 Abs 3 Z 2 Siebentel | §12 Abs 3 Z 2 KStG | 7M | 2011+ | OK |
| Umwidmungszuschlag 30% | §30 Abs 6a EStG | **MISSING** | **2025-07-01+** | **P0-1** |
| Umwidmungszuschlag cap (proceeds) | §30 Abs 6a EStG | **MISSING** | 2025-07-01+ | **P0-1** |

12 of 14 statutorily-active parameters encoded. 2 missing (the
Umwidmungszuschlag pair) — see §3.1.

---

## §2. Worked-example reproductions (note 134 §2)

All four note-134 worked examples reproduce to the cent in the test
suite. Verified against authority sources independently.

### 2.1 Frau Huber OMV (note 134 §2.1) — €4 950 KESt

`cgt_provider_test.clj:111-128`. Acquisition 2022-08-15 €38k →
disposal 2026-04-15 €56k. Gain €18 000 × 27.5% = €4 950.

**Authority cross-check** ([Erste Group Securities know-how](https://www.erstegroup.com/en/investments/service-knowledge/services/securities-know-how/capital-gains-tax),
[Schelhammer KESt-Verlustausgleich](https://schelhammer.at/home/newsarticle/kest-verlustausgleich-in-oesterreich-so-nuetzen-sie-verluste-steuerlich-25/)):
flat 27.5% on realised securities gains; bank as
Abzugsverpflichteter; Endbesteuerungswirkung if withheld. ✓

The bank-prepaid variant
(`kest-with-bank-withholding-zero-liability`,
`cgt_provider_test.clj:130-145`) correctly nets liability to zero via
`:inputs :at-kest-prepaid 4950M`. Discharge per §97 EStG. ✓

### 2.2 Herr Mayer Hauptwohnsitz (note 134 §2.2) — €0 ImmoESt

`cgt_provider_test.clj:272-289` (5-of-10) and `:291-307` (2-of-2).
Both tests assert `(== 0M (-> cmp :liability :amount))` and
`(:regime :hauptwohnsitzbefreiung)`. ✓

**Per the prompt's check 8** (*"Hauptwohnsitzbefreiung — TWO
alternative tests: 2-of-2 OR 5-of-last-10... Verify the OR gate."*):
**VERIFIED CORRECT.** The OR gate at `cgt_provider.clj:186-190`
uses `(some (exemption-set disposal) hauptwohnsitz-flags)` — `some`
returns the first matching flag (truthy) when EITHER is present,
short-circuiting to the residence-component (zero tax). The flag
set is closed at `cgt_provider.clj:106-110` to exactly
`#{:at-hauptwohnsitz-2of2 :at-hauptwohnsitz-5of10}`. Either suffices.
Both tests pass the assertion. ✓

**Authority cross-check** ([KPMG BFG zur Hauptwohnsitzbefreiung
2025](https://kpmg.com/at/de/media/newsletter/tax-news/2025/11/tn-bfg-zur-hauptwohnsitzbefreiung.html)):
"Die Hauptwohnsitzbefreiung gem § 30 Abs 2 Z 1 EStG 1988 sieht zwei
zeitliche Voraussetzungen vor: a) entweder die Immobilie diente ab
Anschaffung mindestens zwei Jahre durchgehend als Hauptwohnsitz, b)
oder die Immobilie diente innerhalb der letzten zehn Jahre vor dem
Verkauf mindestens fünf Jahre durchgehend als Hauptwohnsitz". The
"a) oder b)" verbal disjunction matches the provider's `some` OR
gate exactly. ✓

### 2.3 Müller-Holding §10 default-exempt (note 134 §2.3) — €8M deduction

`cgt_provider_test.clj:357-377`. Acquisition 2018-02-15 €4M → disposal
2026-09-15 €12M, ownership 25%, no Option. Result: regime
`:§10-default-exempt`, liability €0, `:cit-base-deductions [8000000M]`
to net out the GL-booked €8M ordinary income. ✓

**Per the prompt's check 11** (*"§10 KStG INVERSION — DEFAULT is
tax-exempt for qualifying participations (>10% + 1-year hold).
OPT-IN to taxable via `:elective-regime :at-§10-tax-effective-
option`. The agent flagged this as the most subtle case. Verify the
default branch correctly emits `:cit-base-deductions [gain]` to NET
OUT what's in the GL."*): **VERIFIED CORRECT.** The default branch
at `cgt_provider.clj:660-667` matches `(and (not option?)
qualifying? (pos? g))` and emits `(§10-exempt-component opts d g)`,
which at `:427-451` returns
`{:jurisdiction-specific-codes {:cit-base-deductions [gain]
 :lane :at-§10-exempt}}`. ✓

**Authority cross-check** ([jusline.at §10 KStG](https://www.jusline.at/gesetz/kstg/paragraf/10),
WebFetch above): "Veräußerungsgewinne, Veräußerungsverluste und
sonstige Wertänderungen aus internationalen Schachtelbeteiligungen
... bleiben außer Ansatz" — the default IS tax-neutral; the Option
is the opt-OUT into taxable. The kontor INVERSION shape matches. ✓

### 2.4 Müller-Holding §10-Option + loss (note 134 §2.4) — €285 714 Siebentel slice

`cgt_provider_test.clj:403-424`. Acquisition €4M → disposal €2M
(loss €2M), Option elected. Yearly Siebentel slice = €2 000 000 / 7
≈ €285 714.285714. ✓

**Per the prompt's check 12** (*"§10 Option-loss Siebentelregelung —
1/7 rule, losses spread over 7 years. Via `:inputs :at-§10-loss-
siebentel`. Per §10 Abs 3 KStG. Verify the per-year distribution."*):
**VERIFIED CORRECT for the FIRST-YEAR SLICE.** The component at
`cgt_provider.clj:481-506` computes `yearly = loss-amount / 7` with
HALF_EVEN to 6 decimals and emits `:cit-base-deductions [yearly]` for
the CURRENT year. ✓

**Citation nit**: the prompt cites §10 Abs 3 KStG; the actual statute
is **§12 Abs 3 Z 2 KStG** (the encoding correctly cites this at
`cgt_statute.clj:122-125, 220-222`). §10 Abs 3 KStG is the
*opt-into-taxable* mechanic; §12 Abs 3 Z 2 KStG is the *7-year-spread
of Option-elected losses* mechanic. The encoding's citation is
correct; the prompt's citation is slightly off (the two are related
but distinct paragraphs). No code change.

**P1 follow-on** — the encoding handles year-1 (1/7) but DOES NOT
auto-distribute years 2-7 from a single disposal. Note 134 §6.1's
spec is "remaining 6/7 ride through `:inputs :at-§10-loss-siebentel`"
which means the CONSUMER must thread the prior years' slices forward
into each subsequent period's `:inputs`. The provider does NOT read
that input — the search in `cgt_provider.clj` shows zero references
to `:at-§10-loss-siebentel` in any handler. So for years 2-7, the
deduction is silently NOT generated. See §3.5 below.

---

## §3. Findings

### 3.1 P0-1 — Umwidmungszuschlag 2025 deferral is silent (note 134 §1.3 / §4.1)

`cgt_statute.clj` ships zero parameters for §30 Abs 6a EStG.
`cgt_provider.clj` ships zero branches. The shipped namespace
docstrings at `cgt_provider.clj:1-78` and `cgt_statute.clj:1-43`
do NOT mention the surcharge. The prompt's check 15 (*"Umwidmungs-
zuschlag — 2025 30% surcharge on land-slice (LBG 2025) — agent
flagged as PARTIAL pending companion attrs. Confirm the deferral is
documented."*) — **FAIL.** The deferral is not documented in the
shipped code.

**Authority** ([Brandauer 2025-10-06](https://brandauer-rechtsanwaelte.at/2025/10/06/umwidmung-in-bauland-der-neue-30-umwidmungszuschlag-in-oesterreich/),
[BILLUP 2025](https://billup.at/umwidmungszuschlag-grundstuecke-2025/),
WebFetch above):

- **Statutory basis**: §30 Abs 6a EStG (private side) and §4 Abs 3a
  Z 6 EStG (business side); introduced by **BBG 2025**.
- **Effective**: rezoning *legally effective* after 2024-12-31 AND
  disposal *after 2025-06-30*. NOT "LBG 2025" as the prompt asserts —
  the LBG (Liegenschaftsbewertungsgesetz) is unrelated. The correct
  citation is **BBG 2025**.
- **Mechanic**: 30% surcharge on the **positive gain** (NOT on gross
  proceeds), applied **only to the land slice** (Grund und Boden);
  buildings exempt.
- **Cap**: Bemessungsgrundlage = `min(1.30 × Gewinn ; Erlös)` — the
  enhanced base cannot exceed proceeds.

**Impact**: a 2026 disposal of rezoned Bauland with €180k positive
gain (Brandauer's example) silently undercharges by 30% × €180k =
**€54k × 30% (ImmoESt) = €16 200** missed tax. Note 134 §1.3 cites
**€54k surcharge applied at 30%** — clarifying: the surcharge raises
the taxable base from €180k to €234k (= 180 × 1.30), and the 30%
ImmoESt on the €54k DELTA is **€16 200**. Material.

**Fix**:
1. Add namespace-docstring fence at `cgt_provider.clj:1-78` AND at
   the constructor `at-immoest-provider` at line 724-741 with an
   explicit WARNING: "Umwidmungszuschlag §30 Abs 6a EStG (BBG 2025;
   sales post-2025-06-30 where rezoning effective post-2024-12-31)
   is NOT YET IMPLEMENTED — consumer must hand-compute the surcharge
   and supply via :inputs :at-umwidmungszuschlag (kontor's
   convention) until companion attrs ship".
2. Add a `defn-pending`-style failing test (or `^:pending` deftest)
   in `cgt_provider_test.clj:226+` (after the Altvermögen block)
   that exercises a 2025-07-01+ rezoned Bauland disposal and asserts
   the gap. Test should reproduce the Brandauer €180k example and
   note the expected delta.
3. Add a `:parameter` row for the surcharge rate
   (`AT.EStG.§30-Abs-6a.umwidmungszuschlag-rate = 0.30M` from
   2025-07-01) and a paired `:parameter` for the proceeds cap
   factor (just `1M` — the identity factor used in the min). This
   is statute-as-data even if the provider doesn't consume it yet —
   matches the discipline of "load the rate when the law lands, wire
   the provider when the data model lands" (note 120's lesson on
   forward-dated `:parameter-value` rows).

**P1 follow-up**: companion attrs `:at-immoest/land-share` and
`:at-umwidmung/post-2024-12-31?` per note 134 §4.1. These are
companion-namespace attrs (`:at-immoest/*`), so they need a new
module-side schema file and an `install!` extension. Track as a
separate ADR (companion-attrs schema lift).

**Per the prompt's check 7** (*"Altvermögen pauschale — 4.2%
(unwidmet, default) vs 18% (gewidmet) per `:elective-regime`.
Pauschale rates apply to GROSS sales price, not gain. Verify the
base."*): **VERIFIED CORRECT** at `cgt_provider.clj:344-379`. The
base is `(proceeds disposal)` (line 357), the schedule fires on
proceeds, and the `:base` field correctly carries gross-proceeds
(line 363). The two regimes are selected by membership in the
`:elective-regime` set against `:at-immoest-alt-unwidmet` vs
`:at-immoest-alt-gewidmet` (lines 122-131). Default-when-neither-set
falls through to `:altvermoegen-unwidmet` (line 369-370). Both tests
(`cgt_provider_test.clj:231-266`) reproduce: 400k × 0.042 = 16 800
and 400k × 0.18 = 72 000. ✓

### 3.2 P0-2 — §10 KStG default-exempt path lacks foreign-corp guard

`cgt_provider.clj:204-218` — the qualifying check verifies
ownership-fraction and holding-period only. The docstring at lines
207-210 explicitly DEFERS the foreign-corp check to the consumer:
"provider assumes the asset-class `:at-§10-participation` is set
only when the consumer has verified the entity is foreign". This
is a **silent failure mode**.

**Statute** ([jusline.at §10 KStG](https://www.jusline.at/gesetz/kstg/paragraf/10) +
WebFetch above):
- §10 Abs 1 Z 1 KStG — **domestic** participations: dividends
  exempt, gains NOT exempt (remain ordinary CIT base).
- §10 Abs 2 KStG — **international** Schachtel: foreign corp + 10%
  + 1 year → default tax-neutral on dividends AND gains AND losses.
- §10 Abs 3 KStG — the Option zur Steuerwirksamkeit (international
  only).

A consumer who erroneously tags an **AT-domestic** GmbH-stake
disposal with `:asset-class :at-§10-participation` and provides
ownership-fraction ≥ 0.10M and holding ≥ 365 days would receive the
INVERSION treatment — the gain would be removed from the CIT base
via `:cit-base-deductions [gain]` (line 450). But for a domestic
participation, the gain IS taxable. The kontor result would
*understate* CIT by 23% × gain. For a 25% domestic GmbH stake with
€8M gain: silent **€1 840 000** undercharge.

**Fix**:
1. Add `:held-entity-domestic?` boolean to `:tax-unit` consumer
   contract (matches ADR-104/105/107 CCPC/PME/etc. patterns).
2. In `§10-qualifying?` (`cgt_provider.clj:204-218`), short-circuit
   to `false` when `(get-in ctx [:tax-unit :held-entity-domestic?])`
   is true.
3. Add a deftest `§10-domestic-participation-not-inverted` that
   feeds `:held-entity-domestic? true` and asserts the gain does
   NOT route to `:cit-base-deductions` — instead the provider emits
   no component (gain stays in GL ordinary income).
4. Update the namespace docstring at `cgt_provider.clj:30-38` to
   call out the foreign-corp gate: "the provider assumes the held
   entity is foreign UNLESS `:tax-unit :held-entity-domestic?` is
   true. Without the guard, a domestic stake tagged
   `:at-§10-participation` would be silently treated under the
   §10 Abs 3 INVERSION which is wrong per statute."

**Per the prompt's check 11** clarification — the INVERSION shape
itself is CORRECT (gain → `:cit-base-deductions`); the bug is that
the gate that DECIDES whether to use the INVERSION ignores the
foreign-corp predicate. A defensive guard closes the gap with one
boolean and one short-circuit.

### 3.3 P1 — §30 Abs 7 immediate-offset election not exposed

Per note 134 §1.6 final sentence: *"The taxpayer may elect immediate
offset against §28 in the loss year (full 60% in one year) instead
of the 15-year spread."* The provider always uses the 15-year
spread (`cgt_provider.clj:381-421`). No `:elective-regime` flag,
no `:tax-unit` toggle, no test.

**Statute** (jusline.at §30 Abs 7 EStG, WebFetch above): "Auf
Antrag" — the taxpayer applies for the immediate-offset election,
which converts the year-1 60% deduction from 1/15 to full. A
consumer needing this loses the immediate-recognition benefit
silently. For a €300k loss × 60% = €180k, the 15-year spread
yields €12k/year; the elective immediate yields €180k in year 1
(15× larger). Material for cash-flow-sensitive consumers.

**Fix**: add `:elective-regime :at-§30-abs-7-sofort-vortrag` (or
similar) to the disposal; provider branches at line 600-611 to
either yearly=loss×0.60/15 (today) or yearly=loss×0.60 (election
exercised); test the divergence.

Triage: **P1** — document in the next l10n-at sweep. Not v1 ship-
blocker since note 134's §6.2 specifies the 15-year path as the
default; the elective is an optimization.

### 3.4 P1 — §10 KStG anti-abuse switch-over (§10 Abs 4 KStG)

§10 Abs 4 KStG provides a switch-over rule (Methodenwechsel) for
participations in low-tax jurisdictions: the exemption is replaced
by an indirect credit for foreign tax actually paid. This affects
the default-exempt branch — for a Schachtelbeteiligung in a
low-tax-rate country (effective rate < 15%), the gain is taxable
even without the Option. The provider has zero awareness of the
held entity's effective tax rate.

**Authority** ([WKO Internationale
Schachtelbeteiligung](https://www.wko.at/steuern/internationale-schachtelbeteiligung)):
"Die §10 Abs 4 KStG Methodenwechsel-Regel ersetzt die Befreiung
durch Anrechnung wenn die ausländische Körperschaft einer
vergleichbaren tatsächlichen Steuerbelastung von weniger als 15%
unterliegt."

Triage: **P1 documentation** — the v1 scope assumes the consumer
has done the §10 Abs 4 analysis upstream and only tags
`:at-§10-participation` when the exemption genuinely applies. Add a
docstring at `cgt_provider.clj:30-38` mentioning the limitation.
No code change for v1.

### 3.5 P1 — Siebentelregelung years 2-7 silently dropped

§2.4 above: the provider correctly computes year 1's 1/7 slice but
does not auto-thread years 2-7. The consumer must remember to feed
`:inputs :at-§10-loss-siebentel {2027 285714.285714M, 2028 ...,
2031 285714.285714M}` for each subsequent period. The provider
**reads zero such input** today (`grep at-§10-loss-siebentel` in
`cgt_provider.clj` returns the constant definition `:120` only; no
reader).

Triage: **P1** — either (a) write the year-2..7 schedule into a
side-effect-intent / commitment per ADR-098 (the model is exactly
what `kontor.commitment` is built for), OR (b) extend the provider
to read `:inputs :at-§10-loss-siebentel-pool` and emit the current
period's slice from a running tally. Option (a) is cleaner — the
disposal recognizes a 7-year commitment; the provider's job in
years 2-7 is to query the commitment ledger, not to be told what to
deduct.

This is the same shape as the §30 Abs 7 15-year deduction at
`cgt_provider.clj:381-421` — which has the same gap (it emits
year-1 only and does not auto-thread years 2-15). Both should be
fixed in the same sweep.

### 3.6 P1 — KESt no-carryforward is correctly enforced, BUT not the way the prompt asks

**Per the prompt's check 3** (*"KESt no-carryforward — Verlust-
verrechnungstopf resets Jan 1. Provider should ignore `:capital-
loss-carryforward :at-kest`. Verify."*) and the docstring at
`cgt_provider.clj:66-67` ("DELIBERATELY UNUSED"):

The encoding's actual behavior at `cgt_provider.clj:516-542` is:
- The provider IGNORES `:capital-loss-carryforward` entirely (good).
- When a net loss is computed (negative `kest-net`), the `taxable`
  is clamped to `(max 0M kest-net)` (line 240).
- The component is still emitted with `:base 0M` and `:gross-
  liability 0M` (lines 244-249).

Test `kest-no-carryforward-substrate-discipline` at
`cgt_provider_test.clj:170-187` asserts the clamp and confirms the
carry-in is ignored. ✓

**But there's a subtle difference between "ignored" and "actively
rejected"**. The test feeds `{:capital-loss-carryforward {:at-kest
5000M}}` and asserts the carry is "not honored" — but the provider
doesn't validate or warn about the unexpected input. A consumer who
believes they're providing a meaningful carryforward gets silent
acceptance instead of a clear error. Documented in the docstring
(line 67-71: "DELIBERATELY UNUSED") but a defensive warning at the
provider boundary would be friendlier.

Triage: **P2** — optional: log a warning if `:capital-loss-
carryforward :at-kest` is non-nil. Not blocking. The prompt's
verification is satisfied; the encoded behavior matches §27 Abs 8
EStG. **OK.**

**Authority** ([Schelhammer 2025](https://schelhammer.at/home/newsarticle/kest-verlustausgleich-in-oesterreich-so-nuetzen-sie-verluste-steuerlich-25/),
[BTV Verlustausgleich
2024](https://btv.at/wissen/die-neue-verlustausgleichbescheinigung/),
[Enzinger StB
KESt-Verlustausgleich](https://www.enzinger-stb.at/kest-verlustausgleich-in-oesterreich-tipps-und-beispiele/)):
all confirm "der Verlusttopf wird per 31.12. auf null gesetzt" —
the loss pool resets to zero at year-end; no carryforward for
private investors. ✓ aligned.

### 3.7 P1 — Regelbesteuerungsoption is fed correctly to PIT, but no test exercises the negative case

**Per the prompt's check 4** (*"KESt Regelbesteuerungsoption — opt-
in to barème → fold via `:pit-base-additions` (no standalone tax).
Verify."*):

`cgt_provider.clj:267-268` adds `:pit-base-additions [(max 0M
kest-net)]` to `:jurisdiction-specific-codes` when `regel?` is
truthy. Test `kest-regelbesteuerung-folds-into-pit` at
`cgt_provider_test.clj:189-203` asserts this. ✓

**One nuance** — when Regelbesteuerung is elected AND the consumer
has a HIGHER marginal rate than 27.5%, the election WOULD be
disadvantageous (the Regelbesteuerungsoption is only optimal when
the marginal rate is *lower* per the Bergmann SWK 36/2015 article).
Per Austrian practice the bank/tax-advisor checks ex-ante via
Günstigerprüfung. The provider does not validate this — it just
trusts the `:tax-unit :regelbesteuerung-elected?` toggle. **OK** —
consumer-provided election is the right shape (matches the DE
Günstigerprüfung pattern from ADR-104).

**Additional concern**: when Regelbesteuerung is elected, the
provider STILL emits a `:gross-liability` line (line 247-249)
computed at the 27.5% flat rate. For a Regelbesteuerung path,
the gross liability should be ZERO at the CGT provider (the PIT
provider takes over). The current behavior would *double-count*
if the consumer also wires the `:pit-base-additions` deduction.

Re-reading the code carefully: the `:gross-liability` is set to
`(money/money gross commodity)` (line 247) which is the 27.5% × net
amount, NOT zero. The `:line-items` include both the KESt 27.5% tax
line AND the Regelbesteuerung line. A naive consumer reading
`:liability` would book BOTH the KESt €4 950 AND the PIT-marginal
folded €20 000 × marginal rate. The intended composition is that
the CIT/PIT provider sees the `:pit-base-additions` and the CGT
component is INFORMATIONAL only (liability should be zero in the
Regelbesteuerung path).

Triage: **P1** — when `regel?` is true, the CGT component's
`:gross-liability` and `:liability` should be zero (the PIT
provider applies the marginal rate via `:pit-base-additions`). The
test `kest-regelbesteuerung-folds-into-pit` does not assert
liability is zero in this branch — it only asserts the
`:pit-base-additions` fold. Fix: at `cgt_provider.clj:240-249`,
when `regelbesteuerung?`, set `taxable = 0M` and `gross = 0M`. Add
test assertion `(is (== 0M (-> kest :liability :amount)))` in the
existing Regelbesteuerung test.

### 3.8 P1 — Herstellerbefreiung docstring is the only enforcement

**Per the prompt's check 9** (*"Herstellerbefreiung — building self-
built exempt; land taxable. Agent says 'consumer supplies basis =
land-only via `:disposal/basis-amount` and `:notes`.' Verify the
docstring."*):

`cgt_provider.clj:301-309` docstring says: *"When
`:at-herstellerbefreiung` is in the exemption set, the consumer
must have set `:disposal/basis-amount` to represent the LAND-only
portion (note 134 §4.2 — building share exempt; the deviation is
documented in `:notes`)."*

This is **honest deferral** — the substrate cannot split
proceeds/basis without `:at-immoest/building-share` (the companion
attr deferred per note 134 §4.2). The consumer is on the hook to
hand-split.

But there is **no test** that exercises the Herstellerbefreiung
path. No deftest in `cgt_provider_test.clj` references
`:at-herstellerbefreiung` or `:herstellerbefreiung` regime. The
provider's `hersteller-claimed?` predicate at line 192-195 and the
component-emission branch at line 316-336 are exercised by zero
test paths. Latent code.

Triage: **P1** — add a deftest with `:exemption-claimed #{:at-
herstellerbefreiung}`, asserting (a) the `:regime` is
`:herstellerbefreiung`, (b) the component carries the line-item at
line 335-337, (c) the consumer-supplied land-only basis is used.
Even a single test pins the behavior against regression.

### 3.9 P1 — §30 Abs 7 carry destination encoded as raw amount, not commodity-tagged

`cgt_provider.clj:419-421`:
```
:jurisdiction-specific-codes
{:lane :at-immoest-loss-carry
 :pit-base-deductions {:§28-vermietung [yearly]}}
```

The `yearly` is a `java.math.BigDecimal`, NOT a `Money`. The other
`:jurisdiction-specific-codes` outputs in the same file
(`cit-base-deductions [gain]` at line 450, `cit-base-additions
[g]` at line 478, etc.) are ALL raw BigDecimals — no Money. This
is internally consistent within `cgt_provider.clj` but breaks the
project's "always BigDecimal + commodity tag, never doubles" rule
from CLAUDE.md.

The DE / FR / JP / CA CIT providers feed `:cit-base-*` with raw
BigDecimals (the convention is set by the PIT/CIT provider's input
contract on `:inputs` — `kontor.personal_income_tax/period-tax-
facts` reads `:inputs :pit-base-additions` as a vector of
BigDecimals, not a vector of Moneys). The CIT provider then folds
them at the entity's functional commodity. So the AT CGT provider
emitting raw BigDecimals IS consistent with the cross-provider
contract.

But the destination shape for §30 Abs 7 is NEW: `{:§28-vermietung
[amount]}` (note 134 §6.2 introduces this — first cross-category
CGT loss). The substrate convention for this shape is undefined.
Note 134 §6.2 final paragraph: *"This is opaque to the kernel —
`:inputs` is a free-form map per ADR-099. The AT companion documents
the convention."* The convention is documented in the CGT provider;
the **PIT-side consumer** of this shape has not yet been written
(no `kontor.l10n-at.pit-provider` exists today — note 134 §5.2 sketch
only). So the cross-category routing is half-built: the producer
exists, the consumer does not.

Triage: **P1** — track as a follow-up note "AT PIT provider must
read `:inputs :pit-base-deductions :§28-vermietung` and apply
against §28 Vermietung income only, capped at the year's §28
amount". This is gated on the AT PIT module shipping — not a v1
ship-blocker but a known gap.

**Per the prompt's check 10** (*"§30 Abs 7 loss carry — RE losses
× 60% × 15-year carry forward AGAINST §28-Vermietung income. Via
`:inputs :pit-base-deductions {:§28-vermietung [...]}`. THE FIRST
jurisdiction where CGT loss crosses income categories. Verify the
impl + the convention."*): **VERIFIED CORRECT for the producer
side**, with the consumer-side gap noted above. ✓

### 3.10 P1 — §9 KStG Gruppenbesteuerung exclusion is documented, BUT not enforced

**Per the prompt's check 13** (*"§9 KStG Gruppenbesteuerung — out
of scope for v1 (group-tax follow-up). Verify exclusion is
documented."*):

Docstring at `cgt_provider.clj:38` and `cgt_statute.clj:36-37`
both call out "out of scope for v1". ✓

But the provider has zero defensive coding around it. A consumer
who tags a Schachtelbeteiligung disposal involving a same-group
member would receive standard §10 KStG treatment (default-exempt
or Option-elected). The §9 KStG intra-group elimination rule
(intra-group sales eliminate the gain at the leader level) is
silently ignored.

Triage: **P1** — at minimum, add a check on the disposal's
counterparty (via `kontor.entity/family` per note 134 §1.8) and
either short-circuit OR raise an `ex-info` if both legs are in the
same `:group-leader` family. v1 ship is OK if the docstring is
LOUD; current docstring is a one-liner buried in the body.

### 3.11 P2 — `provisions []` empty vector is curious

`cgt_statute.clj:229-249` ships an empty `:provision` vector with a
detailed docstring explaining "every AT CGT mechanism is provider-
internal". This is the ONLY ADR-101 consumer (out of DE / FR / JP /
CA CIT) that ships zero `:provision`s. The DE/FR/JP/CA modules
all have at least a few — even when most logic is provider-side.

The pragmatic reason given in the docstring is correct: AT CGT
selects on `:asset-class` + `:elective-regime` flags, and the
schedule choice is provider-local based on those flags. There is
no statute clause that maps cleanly to the `:provision/compute-fn`
or `:base-transform-add` / `:base-transform-deduct` predicate
vocabulary.

But there ARE good `:provision` candidates the agent didn't lift:
- `AT-EStG-§27a-KESt-27.5%-Wertsteigerungen` (the standalone-rate
  provision) — a `:provision` that PINS the financial KESt rate
  to the parameter would document the statute origin in queryable
  form.
- `AT-EStG-§30-Abs-2-Z-1-Hauptwohnsitzbefreiung` (exemption
  provision) — would let a reader of the statute DB see "where is
  Hauptwohnsitzbefreiung encoded?" with a one-line query.
- `AT-EStG-§30-Abs-7-Loss-Carry` (loss-distribution rule).

These would be "documentation provisions" — they wouldn't drive
behavior (the provider IS the behavior) but they'd give the
statute-as-data substrate query handles. Same pattern as the DE
provisions for parameters that are also provider-driven (note
108).

Triage: **P2** — add 3-5 documentation provisions in the next
sweep. Cosmetic, but it matches the "statute is queryable data"
discipline from ADR-101.

### 3.12 P2 — Schedule construction noise

`cgt_provider.clj:240` constructs `(ts/flat rate)` inside the
component for the KESt path. Similarly `:314` and `:364`. The same
parameter-driven flat schedules are constructed three times per
period. For v1 the cost is negligible (3 × `ts/flat` per call), but
the pattern is repeated across providers. Note 110 §X.x (JP
review) flagged the same as P2.

Triage: **P2** — defer; a future refactor could hoist the schedule
construction to the constructor.

### 3.13 P2 — Test names contain non-ASCII `§` characters

`cgt_provider_test.clj:330, 356, 383, 403, 448, 468` — six deftest
names use `§` (`§30-abs-7-loss-carry-60-percent-15-years`, `§10-
default-exempt-no-option`, etc.). Clojure allows it; kaocha and
clj-kondo handle it. But shell-globbing test names (e.g., `clojure
-X:test :sym at-cgt/§10-default-exempt-no-option`) requires
escaping. The DE/FR/JP/CA tests avoid `§` in deftest names. Style
inconsistency.

Triage: **P2** — match the cross-module style: replace `§30` with
`abs7-` or `paragraph30-`. Cosmetic. The encoded `§` in `:lane`
keywords and `:regime` keywords is fine.

### 3.14 P2 — `at-immoest-prepaid` not exercised by any test

`cgt_provider.clj:567` reads `(:at-immoest-prepaid inputs)`, used
in both `neuvermoegen-component` (line 310) and `altvermoegen-
component` (line 354). The line-item at 339-341 is gated on
`(pos? prepaid)`. **No test** sets a non-zero `:at-immoest-prepaid`.
The §30b EStG Selbstberechnung (notary-withheld ImmoESt) is the
default real-world path for AT ImmoESt — every notary-mediated
disposal goes through it. Latent untested code on the hot path.

Triage: **P2** — add a deftest with `:inputs {:at-immoest-prepaid
78000M}` for the Neuvermögen €260k gain × 30% = €78 000 case;
assert `liability = 0`.

---

## §4. Cross-cutting design notes

### 4.1 The INVERSION shape — correctly captured

The §10 KStG INVERSION (default exempt; opt-in to taxable) is the
single hardest design call in the AT CGT module per the prompt and
note 134 §6.1. The encoded shape:

- Default branch (no Option, qualifying participation, positive
  gain) → `§10-exempt-component` emits `:cit-base-deductions [gain]`.
  Gain that landed in the GL as ordinary income is *removed* from
  the CIT base. **CORRECT** — this matches the WKO + jusline.at
  description of "bleiben außer Ansatz".

- Default + qualifying + negative gain → `:§10-default-exempt-loss`
  regime emits `:cit-base-additions [gain]` (where gain is negative,
  so this adds back the loss that's currently *reducing* CIT base
  in the GL). The shape `:cit-base-additions [g]` with `g` negative
  is mathematically correct (subtracting a negative = adding).

  **Subtle issue**: `cgt_provider.clj:684-685` uses
  `:cit-base-additions [g]` where `g` is the realized-gain
  BigDecimal (already signed negative). So the CIT provider's
  `:base-transform-add` would ADD a negative, which reduces the
  base — wrong direction. The intended math is to NEUTRALIZE the
  loss: the loss is currently in the GL reducing CIT base, and
  we want to ADD IT BACK to the base (positive adjustment). For
  that, the value passed should be `(- 0M g)` (positive). As
  written, the provider DOUBLES the loss effect rather than
  neutralizing it. **Probable bug**, P0.

  Let me re-read carefully: line 684-685:
  ```
  :jurisdiction-specific-codes
  {:cit-base-additions [g]
   :lane :at-§10-exempt-loss}
  ```
  where g was bound at line 646 as `(realized-gain d)` — signed
  negative for a loss. So `:cit-base-additions [-2000000M]`. The
  CIT provider per ADR-104 etc. interprets `:cit-base-additions`
  as "increase the CIT base by the listed amounts". Adding -2M
  *decreases* the base by 2M, which is the SAME direction as
  the GL loss (the GL already reduced taxable income by 2M).
  Net effect: the loss is *double-counted* in the consumer's CIT
  return. This is the WRONG direction.

  The default-exempt case for positive gain at line 450 emits
  `:cit-base-deductions [gain]` where gain is POSITIVE — that's
  correct (reduces the base by gain, neutralizing the GL booking
  of the gain). The loss case should symmetrically emit
  `:cit-base-additions [(- 0M g)]` (positive) to ADD BACK what
  the GL took out.

  **PROMOTING to P0-3 below.**

### 4.2 The OR-gate for Hauptwohnsitz

`some` over a set works correctly (returns the first matching flag
or nil). Both flags (2-of-2 / 5-of-10) route to the same
`residence-component`. Provider does NOT distinguish which flag
fired in the regime field — both produce
`:regime :hauptwohnsitzbefreiung`. The line-item label at line
292-294 DOES include the specific flag via `claimed` interpolation.
Audit trail preserved. ✓

### 4.3 The closed asset-class sets

The three closed sets (`kest-asset-classes`, `immoest-asset-classes`,
`corporate-asset-classes`) at lines 90-104 are the right defensive
shape. Disposals with an unrecognized asset-class are silently
filtered out (per docstring at line 60-62 "forward-compat with new
asset classes"). ✓

### 4.4 The `regime-set` / `exemption-set` normalizers

The pull from the disposal source returns cardinality-many attrs as
vectors. The normalizers at `cgt_provider.clj:165-184` handle nil
/ vector / single-keyword / fallback-to-empty-set. Defensive and
correct. ✓

---

## §5. P0-3 — added: §10 KStG default-exempt LOSS branch direction error

(Promoted from §4.1 above.)

**File**: `cgt_provider.clj:670-686`.

**Reproduction**:
```
disposal: §10-participation, ownership 25%, no Option,
          proceeds €3M, basis €4M → gain = -€1M
provider branch: (and (not option?) qualifying? (neg? g))
emit: {:cit-base-additions [-1000000M] ...}
CIT consumer: adds -1M to CIT base → base reduced by 1M
GL: loss of 1M already reduced GL income by 1M
NET EFFECT: 2M reduction in CIT base for a single 1M loss.
```

**Expected**: the §10 default-exempt path is TAX-NEUTRAL on both
gain AND loss. The gain branch correctly emits
`:cit-base-deductions [gain]` (positive) to neutralize the GL gain.
The loss branch should symmetrically emit
`:cit-base-additions [(- 0M g)]` (positive) to neutralize the GL
loss — making both branches "remove from CIT base" in their
respective directions.

**Authority** ([WKO Internationale
Schachtelbeteiligung](https://www.wko.at/steuern/internationale-schachtelbeteiligung)):
"Sowohl Gewinne als auch Verluste aus der Veräußerung … bleiben
außer Ansatz" — both gains AND losses are tax-neutral. The kontor
implementation doubles the loss effect, which under-reports CIT
relative to ordinary-income treatment AND under-reports CIT
relative to true §10 KStG neutrality.

**Fix**: change line 685 from `[g]` to `[(- 0M g)]`. Add a deftest
`§10-default-exempt-loss-neutralizes-gl` that records a €1M §10
loss and asserts (a) the `:cit-base-additions` value is `+1000000M`
(positive, neutralizing direction); (b) the `:liability` is zero;
(c) the regime is `:§10-default-exempt-loss`.

**No test today exercises this path** — search shows zero
references to `:§10-default-exempt-loss` regime in
`cgt_provider_test.clj`. Latent untested code; the bug ships
silently.

---

## §6. Worked-example completeness check

Note 134 §2 enumerates four worked examples. All four are reproduced
in the test suite with arithmetic to the cent:

| Example | Test | Expected | Asserted | OK |
|---|---|---|---|---|
| §2.1 Frau Huber OMV | `kest-shares-flat-27-5` | €4 950 | `== 4950M` | ✓ |
| §2.2 Herr Mayer Hauptwohnsitz | `hauptwohnsitzbefreiung-5of10-zero-tax` + `2of2` | €0 | `== 0M` | ✓ |
| §2.3 Müller-Holding §10 default | `§10-default-exempt-no-option` | €8M to deductions | `= [8000000M]` | ✓ |
| §2.4 Müller-Holding §10 Option loss | `§10-option-loss-siebentelregelung` | ≈€285 714.29/yr | `compareTo bigdec "285714.285714"` | ✓ |

All four reproduce. The §10-Option *gain* path (note 134 §2.4 first
half — gain €8M × 23% = €1 840 000) is tested separately at
`§10-option-elected-gain-taxable` (`cgt_provider_test.clj:383-401`)
which asserts `[8000000M]` flows to `:cit-base-additions`. The
final `× 23%` is the consumer-side CIT provider's job. ✓

**Missing from the test suite** (P1 → P2):
- Combined-altvermögen + neuvermögen disposal in same period (the
  cross-Neuvermögen netting before §30 Abs 7 reduction).
- Herstellerbefreiung path (§3.8 above).
- ImmoESt with `:at-immoest-prepaid` (§3.14 above).
- §10-default-exempt-loss path (§5 above — and where the P0-3 bug
  hides).
- Regelbesteuerung with non-zero liability assertion (§3.7 above).
- §30 Abs 7 immediate-offset election (§3.3 above; not implemented
  yet).
- §10 Abs 4 switch-over path (§3.4 above; not implemented yet).

---

## §7. Actionable findings

### P0 — would mis-compute a real consumer in a normal scenario

- **P0-1 — Umwidmungszuschlag 2025 deferral is silent.** Add
  docstring fence (warn), failing test (pending), and 2 parameter
  rows. Cite BBG 2025 / §30 Abs 6a EStG / §4 Abs 3a Z 6 EStG.
  Files: `cgt_provider.clj:1-78, 724-741` (docstring);
  `cgt_statute.clj:130-225` (parameters);
  `cgt_provider_test.clj:330+` (pending test).
  **Material**: 30% surcharge on positive land-slice gain; €54k
  surcharge for €180k gain (Brandauer's example) = €16 200 missed
  ImmoESt.

- **P0-2 — §10 KStG default-exempt path lacks foreign-corp guard.**
  Thread `:tax-unit :held-entity-domestic?` boolean; short-circuit
  `§10-qualifying?` at `cgt_provider.clj:204-218` when true; add
  test. **Material**: silent €1.84M understatement of CIT on a 25%
  domestic-GmbH stake disposal with €8M gain.

- **P0-3 — §10 default-exempt LOSS branch direction error.** Change
  `cgt_provider.clj:685` from `[g]` to `[(- 0M g)]`. Add deftest.
  **Material**: silent 2× counting of losses in the §10 default
  branch (no test exercises this path today).

### P1 — matters for some real consumers; fix in the next l10n-at sweep

- **P1-1**: §30 Abs 7 immediate-offset election (§3.3).
- **P1-2**: §10 Abs 4 KStG anti-abuse switch-over (§3.4).
- **P1-3**: Siebentelregelung years 2-7 auto-distribution (§3.5);
  same gap for §30 Abs 7 years 2-15.
- **P1-4**: Regelbesteuerung liability should be zero when elected
  (§3.7).
- **P1-5**: Herstellerbefreiung test missing (§3.8).
- **P1-6**: §30 Abs 7 PIT consumer not built — track for the
  l10n-at PIT module (§3.9).
- **P1-7**: §9 KStG Gruppenbesteuerung — at minimum, defensive
  ex-info on same-group disposals (§3.10).

### P2 — defer; document as known gaps

- **P2-1**: Pre-2016 KESt 0.25M row (§1.1.1).
- **P2-2**: KESt 25% interest rate is dead code — docstring (§1.1.2).
- **P2-3**: 55% Begrenzung docstring in provider file (§1.1.3).
- **P2-4**: Pre-2016 Altvermögen 3.5% / 15% rows (§1.2.2-3).
- **P2-5**: 12-month vs 365-day interpretation of §10 Abs 2 KStG
  qualifying-holding (§1.3.3).
- **P2-6**: Add 3-5 documentation `:provision`s (§3.11).
- **P2-7**: Schedule construction noise (§3.12).
- **P2-8**: Replace `§` in deftest names (§3.13).
- **P2-9**: Add ImmoESt `:at-immoest-prepaid` test (§3.14).
- **P2-10**: KESt no-carryforward should log a warning when fed
  unexpected `:capital-loss-carryforward :at-kest` input (§3.6).
- **P2-11**: Add `:integer` / `:count` / `:duration-days` to the
  closed unit vocabulary in `:parameter/unit` (substrate-level
  follow-up; §1.2.5).

---

## §8. Honest summary

The AT CGT ship is **structurally complete** for the v1 scope and
**arithmetically correct against the four note-134 worked examples**.
The §10 KStG INVERSION (the prompt's most-subtle-case flag) is
correctly modelled in both shape (default → `:cit-base-deductions`)
and gate (Option flag → `:cit-base-additions`). The Hauptwohnsitz
OR-gate is correctly implemented via `some` over a closed flag set.
Altvermögen pauschale correctly applies to GROSS proceeds (not gain).
KESt no-carryforward is correctly enforced via clamp-to-zero plus
provider-side ignoring of `:capital-loss-carryforward :at-kest`.
Regelbesteuerungsoption folds the net into `:pit-base-additions` as
designed.

**The three P0s cluster around two themes**:

1. **The 2025 Umwidmungszuschlag deferral is invisible** (P0-1) —
   the surcharge went into force ten months before ADR-103 shipped;
   the encoding has zero affordance for it; a consumer with a 2026
   Bauland disposal silently undercharges ImmoESt by 30% × gain ×
   land-share. This is the same failure mode note 120 surfaced for
   DE §9 Nr 1 (Grundsteuerreform sunset) and note 126 for ON Bill 12
   (SBD limit raise) — substrate's date-keyed parameter machinery is
   the right tool, and the discipline is to USE it AT THE MOMENT the
   law changes. The fix is small but the deferral must be loud.

2. **Two silent-mis-routing bugs in the §10 KStG default-exempt
   branches** (P0-2 + P0-3) — both are domain corner cases not
   exercised by the test suite. The default-exempt-gain path has a
   working test but lacks the foreign-corp guard (silently treats
   domestic stakes under the INVERSION). The default-exempt-loss
   path is untested AND has a direction-of-effect error
   (`:cit-base-additions [g]` with `g` negative doubles the loss
   effect rather than neutralizing it). Both are one-line + one-test
   fixes.

The good news: the agent's discipline on the four worked examples
is exemplary — each note-134 example has a corresponding deftest
that reproduces to the cent. The P0s are in untested or partially-
tested code paths, exactly where the per-stage rhythm's "review-
after" step is designed to surface them.

**Honest call**: this is the third end-to-end CGT consumer (after
DE / FR / JP) and the first to model an INVERSION + a cross-category
loss carry simultaneously. The substrate stress note 134 flagged
(§6.2 cross-category) handled itself cleanly through `:inputs`
free-form-map opacity. The substrate stress *unflagged* by note 134
(the foreign-corp guard for §10) is the kind of corner that only
shows up when a maintainer who knows §10 reads the provider
docstring's "consumer assumes" disclaimer — which is exactly what
this review found. Worth a P0 status for the silent-mis-route risk.

---

## §9. Sources

AT statutes (ris.bka.gv.at / jusline.at — public):

- [§27 EStG 1988](https://www.jusline.at/gesetz/estg/paragraf/27)
- [§27a EStG 1988](https://www.jusline.at/gesetz/estg/paragraf/27a)
- [§30 EStG 1988](https://www.jusline.at/gesetz/estg/paragraf/30) —
  including the new §30 Abs 6a Umwidmungszuschlag.
- [§30a EStG 1988](https://www.jusline.at/gesetz/estg/paragraf/30a)
- [§30b EStG 1988](https://www.jusline.at/gesetz/estg/paragraf/30) —
  Selbstberechnung.
- [§10 KStG 1988](https://www.jusline.at/gesetz/kstg/paragraf/10)
- [§12 KStG 1988](https://www.jusline.at/gesetz/kstg/paragraf/12) —
  Siebentelregelung at Abs 3 Z 2.
- [§22 KStG 1988](https://www.jusline.at/gesetz/kstg/paragraf/22) —
  CIT rate.

BMF / findok / WKO (admin + practitioner):

- [BMF Verlustverwertung](https://www.bmf.gv.at/themen/steuern/fuer-unternehmen/einkommensteuer/verlustverwertung.html)
- [BMF Capital gains](https://www.bmf.gv.at/en/topics/taxation/Income-Taxation-on-savings-and-investments/Capital-gains-or-income-from-realised-value-increases.html)
- [BMF Verluste aus Veräußerung Kapitalvermögen](https://www.bmf.gv.at/themen/steuern/sparen-veranlagen/verluste-aus-veraeusserung-von-kapitalvermoegen-und-derivaten.html)
- [WKO KÖSt Körperschaftsteuer](https://www.wko.at/steuern/koest-koerperschaftsteuer)
- [WKO Aktuelle Werte 2024](https://www.wko.at/steuern/aktuelle-werte-einkommen-koerperschaftsteuer-ab-2024)
- [WKO Internationale Schachtelbeteiligung](https://www.wko.at/steuern/internationale-schachtelbeteiligung)
- [WKO Verlustverwertung](https://www.wko.at/steuern/steuerliche-verlustverwertung)

Big-4 / boutique practitioner commentary:

- [KPMG BFG Hauptwohnsitzbefreiung 2025](https://kpmg.com/at/de/media/newsletter/tax-news/2025/11/tn-bfg-zur-hauptwohnsitzbefreiung.html)
- [KPMG BFG Hauptwohnsitzbefreiung Hauptwohnsitzaufgabe 2020](https://kpmg.com/at/de/home/insights/2020/02/tn-bfg-zur-hauptwohnsitzbefreiung-hauptwohnsitzaufgabe-vor-verkauf.html)
- [KPMG Immo-ESt Herstellerbefreiung](https://home.kpmg/at/de/home/insights/2018/08/tn-herstellerbefreiung-bei-immo-est.html)
- [Deloitte BFG Herstellerbefreiung 2024](https://www.deloitte.com/at/de/services/tax/blogs/2024/bfg-inanspruchnahme-der-herstellerbefreiung-bei-eigenleistungen.html)
- [PwC Worldwide Tax Summaries — Austria Individual](https://taxsummaries.pwc.com/austria/individual/income-determination)
- [PwC Worldwide Tax Summaries — Austria Corporate](https://taxsummaries.pwc.com/austria/corporate/income-determination)

Boutique + bank sources:

- [Brandauer Umwidmungszuschlag 2025](https://brandauer-rechtsanwaelte.at/2025/10/06/umwidmung-in-bauland-der-neue-30-umwidmungszuschlag-in-oesterreich/)
- [BILLUP Umwidmungszuschlag](https://billup.at/umwidmungszuschlag-grundstuecke-2025/)
- [SBT Umwidmungszuschlag](https://www.sbt-wt.at/aktuelles/landwirtschaftsnews/herbst_2025/aenderungen_bei_der_steuerlichen_behandlung_von_bauland/)
- [ÖSV Sachliche Steuerbefreiungen §§7+10 KStG Teil 1](https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-1/)
- [ÖSV Sachliche Steuerbefreiungen §§7+10 KStG Teil 2](https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-2/)
- [ÖSV Sonstige Einkünfte §29 EStG Teil 6](https://www.steuerverein.at/22-sonstige-einkuenfte-%C2%A7-29-estg-1988-teil-6/)
- [Schelhammer KESt-Verlustausgleich 2025](https://schelhammer.at/home/newsarticle/kest-verlustausgleich-in-oesterreich-so-nuetzen-sie-verluste-steuerlich-25/)
- [Enzinger StB KESt-Verlustausgleich](https://www.enzinger-stb.at/kest-verlustausgleich-in-oesterreich-tipps-und-beispiele/)
- [BTV Verlustausgleich 2024](https://btv.at/wissen/die-neue-verlustausgleichbescheinigung/)
- [Erste Group Securities know-how](https://www.erstegroup.com/en/investments/service-knowledge/services/securities-know-how/capital-gains-tax)
- [Broker-Test Regelbesteuerung](https://www.broker-test.at/steuern/regelbesteuerung/)
- [RSM Austria Sale & Transfer of Real Estate](https://www.rsm.global/austria/en/insights/sector-insights/sale-transfer-austrian-real-estate)
- [JKU Bergmann SWK 36/2015 Regelbesteuerungsoption](https://www.jku.at/fileadmin/gruppen/150/Team/Sebastian_Bergmann/Aufsaetze_in_Fachzeitschriften/41_SWK_36_2015_Bergmann.pdf)

kontor substrate cited (file:line):

- `modules/l10n-at/src/kontor/l10n_at/cgt_statute.clj:1-263` —
  reviewed (parameters + provisions).
- `modules/l10n-at/src/kontor/l10n_at/cgt_provider.clj:1-765` —
  reviewed (three providers + helpers).
- `modules/l10n-at/test/kontor/l10n_at/cgt_provider_test.clj:1-501`
  — reviewed (14 deftests).
- `src/kontor/statute.clj:150-174` — `parameter-value-at` resolver
  the providers consume.
- `src/kontor/schema.clj:1313-1321` — `:parameter/unit` closed
  vocabulary.
- `src/kontor/disposal_source.clj` — `DisposalSource` protocol.
- `modules/disposal/src/kontor/disposal/source.clj` — canonical
  `DatahikeDisposalSource` impl.
- `doc/research/134-at-cgt-fit.md` — research-before note this
  review audits against.
- `doc/research/120-de-cit-baseline-review.md` — sibling baseline-
  review (the P0 forward-dated-amendment failure mode this review
  shares with §3.1).
- `doc/research/126-ca-cit-baseline-review.md` — sibling baseline-
  review (Ontario Bill 12 silent-stale parameter — same shape as
  P0-1).

---

End of note 146.

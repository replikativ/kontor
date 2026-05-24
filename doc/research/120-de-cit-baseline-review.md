---
date: 2026-05-24
title: 120 — DE CIT (KSt + Soli + GewSt) baseline review against statute + commercial calculators
audience: maintainer
status: review-after — ADR-104, first end-to-end ADR-101 consumer
---

# 120 — DE CIT baseline review

ADR-104 shipped the first end-to-end consumer of the ADR-101 statute-as-data
substrate: DE CIT encoded as eight `:parameter`s + six `:provision`s under
`modules/l10n-de/src/kontor/l10n_de/cit_statute.clj`, a thin
`PeriodTaxProvider` in `cit_provider.clj`, and six deftests reproducing a
BMF/onlinebilanz worked example (€43,687.50) plus a hand-derived complex case
(€83,402.15). Per the maintainer's standing mandate ("once we update our l10n
packages we should also review with agents against baselines software and
online documentation/official law again"), this note audits that encoding
against gesetze-im-internet.de, the BMF Gewerbesteuer-Handbuch, secondary
commentary (steuerkurse.de, Haufe, iww, sevdesk, onlinebilanz.de), and an
online GewSt calculator (smart-rechner.de).

**Headline.** The headline numbers (parameter values, effective dates,
citations) are correct. The worked example reproduces to the cent against
smart-rechner.de + the onlinebilanz.de source. The two real findings — both
of which would mis-compute a real customer — are scope/semantics issues:

- **P0** — `:DE.GewSt.§8.rental-share` 12.5% **silently assumes immovable
  property**. The same encoded share is wrong for movable rent (should be 5%
  = 20% × 25%) and for royalties (should be 6.25% = 25% × 25%). A
  finance-intensive consumer with mixed rent will overstate add-back
  (immovable rate applied to movable rent) or understate it (the missing
  royalty bucket simply doesn't fire).
- **P0** — the §8 €200k Freibetrag is **conceptually applied to the
  WEIGHTED SUM of all six §8 Nr. 1 categories**, not to the interest leg
  alone. The current encoding pushes the Freibetrag onto the consumer
  per-category (`:gewst-interest-post-freibetrag`), which means a consumer
  with €100k interest + €600k immovable rent would deduct the €200k twice
  (once from interest = 0, once implicit in the rental being applied at
  12.5% with no Freibetrag at all → the entire €75k rental Hinzurechnung
  fires unchecked).

Everything else is documentation polish, naming choices, and a list of
gaps that note 108 §3.1 already triaged as out-of-scope for the first
ADR-104 ship (Mindestbesteuerung carry per note 105 frontier 2; §9 Nr. 2a /
3 / 7; the elective `:regime` slots for §8c / §8d). Sections below detail
each finding with file:line + cite.

## §1. Statute-fidelity audit — parameter by parameter

### 1.1 `DE.KSt.rate` = 0.15M from 2008-01-01 — CORRECT

`§23 Abs. 1 KStG` since the Unternehmenssteuerreformgesetz 2008 (UntStRefG
2008). Effective from Veranlagungszeitraum 2008 (i.e. tax year starting
2008-01-01 for calendar-year filers; the encoding's instant is correct for
the standard case). `§34 Abs. 11a KStG` is the application clause. Citation
URL [https://www.gesetze-im-internet.de/kstg_1977/__23.html](https://www.gesetze-im-internet.de/kstg_1977/__23.html)
resolves and the current text reads "Veranlagungszeiträume bis 2027
15 Prozent" — so the 0.15M is correct **today** but already has a sunset:
the same statute schedules a phased reduction (one point per year from
2028) down to 10% in 2032 per the Investitionssofortprogramm (2025
legislation). **No bug today**, but the encoding has zero
`:parameter-value` rows for 2028+ — see §7 P1-1.

### 1.2 `DE.Soli.rate` = 0.055M from 1998-01-01 — CORRECT

`§4 SolZG 1995` confirms 5.5% as the current rate. Per Wikipedia + Lexware +
the BMF June 2025 Monatsbericht: rate was 7.5% in 1995-1997 (the Helmut Kohl
reunification phase), then permanently dropped to 5.5% effective tax year
1998 (BGBl. I 1997 S. 2743, "Gesetz zur Senkung des Solidaritätszuschlags").
For corporate filers there is no Freigrenze (the 2021 abolition was
EStG-side only). The encoding correctly applies it unconditionally on KSt.
Citation URL resolves.

### 1.3 `DE.GewSt.messzahl` = 0.035M from 2008-01-01 — CORRECT

`§11 Abs. 2 Nr. 2 GewStG` since UntStRefG 2008; pre-2008 the Messzahl was
5% with a staircase for low Gewerbeertrag. The single-rate 3.5% landed in
the same reform package that dropped KSt 25%→15% and made GewSt
non-deductible from itself. Confirmed by gesetze-im-internet.de, dejure.org,
NWB Datenbank, BMF GewStH 2016, Haufe, IHK Bergische. **One nit**: there is
a reduced rate of 56% × 3.5% = 1.96% for Hausgewerbetreibende (`§11 Abs. 3
GewStG`) — not relevant for a GmbH, but the encoding has no slot for it.
**Defer** as P2 — beyond ADR-104 scope.

### 1.4 `DE.GewSt.§8.freibetrag` = 200000M from 2020-01-01 — CORRECT

Raised from 100k → 200k by the **Zweites Corona-Steuerhilfegesetz** of
2020-06-29 (BGBl. I 2020 S. 1512), effective from Erhebungszeitraum 2020
("ab dem Erhebungszeitraum 2020"). Confirmed by ecovis-kso, Haufe, iww,
AUMA, smartsteuer, datenbank.nwb.de, and the statute text on
gesetze-im-internet.de. The 2020-01-01 instant is correct. **However** the
encoding is missing the 1.1.2008 — 31.12.2019 prior value of 100000M; if a
consumer ever passes an `:as-of` in 2019, the parameter resolver returns
nil and the §8 fold breaks. P1 finding, see §7.

### 1.5 `DE.GewSt.§8.interest-share` = 0.25M from 2008-01-01 — CORRECT IN VALUE, MISLEADING IN NAME

The 25% (= ¼) factor is correct per `§8 Nr. 1 GewStG` clause-closing "ein
Viertel der Summe aus" — confirmed by the statute text on gesetze-im-internet.de:
the post-Freibetrag residual is multiplied by ¼. **But the value's name
"interest-share" is misleading**: the 25% is not the per-category interest
weight; it is the **universal ¼ factor** applied **after** the weighted sum
of all six categories minus Freibetrag. The actual interest weight (in §8
Nr. 1a) is 100% — see §1.7 for why this matters operationally.

### 1.6 `DE.GewSt.§8.rental-share` = 0.125M from 2008-01-01 — **WRONG / AMBIGUOUS** (P0)

The encoded 0.125M (= ½ × ¼) is the **immovable-only blend** (§8 Nr. 1e:
50% × ¼). But the substrate exposes a single `:gewst-rental-expense`
input, and the compute-fn multiplies by the single 0.125M unconditionally.
Real consumers see three distinct categories:

| §8 sub-letter | Weight | × ¼ | Effective rate |
|---|---|---|---|
| 1d — movable property rent | 20% | × ¼ | **5%** (= 0.05M) |
| 1e — immovable property rent | 50% | × ¼ | **12.5%** (= 0.125M) |
| 1f — royalties / licences | 25% | × ¼ | **6.25%** (= 0.0625M) |

(Source: `§8 Nr. 1 GewStG` clauses a-f on gesetze-im-internet.de; the BMF
GewStH 2016 Anhang 1 I; steuerkurse.de Hinzurechnungen-Tabelle.)

A consumer who pays €1m of machinery rent (movable) and uses the
`:gewst-rental-expense` input gets a €125k add-back — actually €50k is
the right answer (1m × 20% × ¼). For a software firm that pays €500k of
patent royalties, the same input would compute €62.5k, but the correct
answer is €31.25k (500k × 25% × ¼). **Off by 2.5× in one direction; off
by 2× in the other.** A GewSt over-statement of this size on a mid-cap
finance bill is a material accounting error.

**Fix.** Two clean options:

- **Option A (preserved-shape):** rename the parameter to `§8.rental-share-immovable`,
  add two more parameters (`§8.rental-share-movable` = 0.05M; `§8.licence-share` =
  0.0625M), split the consumer input into `:gewst-rental-expense-movable` /
  `:gewst-rental-expense-immovable` / `:gewst-royalties`, and add two more
  provisions (`DE-GewStG-§8-Nr-1d` for movable, the existing one for immovable,
  new `DE-GewStG-§8-Nr-1f` for royalties).
- **Option B (statute-faithful):** flip the substrate to take the **six raw
  expense buckets** (interest, annuity, silent-partner, movable, immovable,
  royalties) and let the §8 provisions compute the weighted sum, **subtract
  €200k**, and apply ¼ in ONE provision over the combined pool. This
  matches the law text verbatim and removes the consumer's burden of
  pre-applying Freibetrag. Cost: ¼ is in the substrate, but it's the most
  common shape in the literature; the BMF Handbuch worked examples present
  it this way; smart-rechner.de's "Mehr Hinzurechnungen" expansion exposes
  this same six-bucket form. The §8 fold becomes one compute-fn over six
  facts instead of three over three facts.

**Recommendation: Option B.** The statute IS a weighted sum minus
Freibetrag; the substrate should mirror it. Cost is one extra
provision-row removed (six categories → one combined Hinzurechnung
amount) and one compute-fn that does the actual weighted-sum-minus-Freibetrag
× ¼ math.

### 1.7 `DE.GewSt.§9.real-estate-rate` = 0.012M from 2008-01-01 — **OBSOLETED 2025-01-01** (P0)

Encoded as "1.2% of Einheitswert × 1.4" with the consumer supplying the
already-multiplied value. The 0.012M factor matches `§9 Nr. 1 S. 1 GewStG`
**through 2024-12-31 only.** Effective **2025-01-01**, the Jahressteuergesetz
2024 (BGBl. I 2024, in force per Genoverband / iww / acconsis / haufe
commentary) **rewrote `§9 Nr. 1 S. 1` to base the reduction on the actual
property tax recorded as a business expense** during the Erhebungszeitraum,
not on the Einheitswert × 1.4 × 1.2% formula. The old rule was made
obsolete by the Grundsteuerreform (Einheitswerte ceased existing as the
property-tax base in most Länder on 2025-01-01).

So the encoded `:parameter-value` is **silently wrong for any period
starting 2025-01-01 or later**. The provision fires the old formula
unconditionally — there is no second `:parameter-value` row carrying the
2025 transition. The complex test uses `:as-of #inst "2025-06-30"` and
the §9 deduction it computes is structurally obsolete (the new rule is
"deduct the actual property tax paid as a business expense" — a totally
different input). The test number 600€ = 50000 × 1.2% is coherent only
under the old regime.

**Fix.** Two parts:

1. Add a `:parameter-value` row for `DE.GewSt.§9.real-estate-rate` with
   `:effective-from #inst "2025-01-01"` and `:decimal-value 0M` (the old
   formula no longer applies; the new rule needs a different mechanism).
2. Add a new mechanism — easiest is a second provision
   `DE-GewStG-§9-Nr-1-from-2025` that reads `:inputs :grundsteuer-paid`
   (the Grundsteuer the consumer paid as a business expense in the period)
   and deducts that amount 1:1 via `:base-deduct`. Gate the old provision
   with `:effective-until #inst "2025-01-01"`; gate the new one with
   `:effective-from #inst "2025-01-01"`.

The complex test's `:as-of` would then need to choose: re-cast as a 2024
period (and adjust the `:gewst-real-estate-value` input intent), or move
to the new mechanism with `:grundsteuer-paid` and recompute the expected
number. The fact that the test passes today is a **false positive** —
the encoding reproduces a number under a regime that no longer applies.

(Note 108 §1.3 dated 2026-05-24 quoted "1.2% of the unit value of business
real property" without flagging the 2025-01-01 reform; the fit assessment
missed this Grundsteuerreform consequence.)

### 1.8 `DE.KStG.§8b.exemption-rate` = 0.95M from 2004-01-01 — CORRECT

The 95% exemption + 5% Pauschalzuschlag landed with the **Korb-II-Gesetz
of 2003-12-22** (BGBl. I 2003 S. 2840), generalised effective
Veranlagungszeitraum 2004 ("anwendbar ab Veranlagungszeitraum 2004"). The
2004-01-01 effective-from matches. Confirmed by Haufe DPM commentary,
rechtslupe, the BVerfG 2010 ruling that upheld constitutionality, and
gesetze-im-internet.de current text. **One subtlety**: the encoded
parameter is the "exemption rate" (0.95), and the compute-fn `de-§8b-addback`
correctly applies `(1 − 0.95) = 0.05` to the participation gain. This is
fine; if anyone ever wants to read the addback rate directly, a sibling
`DE.KStG.§8b.addback-rate` = 0.05M would be a 2-line addition — but it's
not required because `(1 - x)` is the canonical reading. Keep as-is.

## §2. Provision-coverage gap analysis

The six encoded provisions cover the most common GmbH cases. What's
missing, ordered by P0 / P1 / P2 severity:

### P0 — would mis-compute a typical mid-cap GmbH today

- **§8 Nr. 1d/e/f weights** — see §1.6. The single `rental-share` is a
  silent immovable-only assumption. Movable rent (machinery, vehicles,
  computers) is enormously common; royalties are common for software
  / pharma / IP-heavy firms.
- **§9 Nr. 1 post-2025 rewrite** — see §1.7. The encoded formula is
  obsolete as of 2025-01-01.

### P1 — matters for some real consumers

- **§9 Nr. 2a GewStG — Schachtelprivileg for dividends from ≥ 15%
  participations.** A GmbH that holds a ≥ 15% stake in another corp and
  receives dividends gets a GewSt Kürzung mirror to KSt's §8b. The
  encoding only ships the KSt-side §8b; the GewSt-side §9 Nr. 2a is
  absent. (Note 108 §1.3 listed it; note 108 §3.3 left it out of the
  shipped table. Confirmed in the BMF GewStH 2024 §9 page and Haufe's
  "Beteiligung an anderen Körperschaften / 2.2.1 Kürzung gem. § 9 Nr.
  2a".) A 2-provision addition (one §9 Nr. 2a for KapGes-dividends, one
  pairing with §8b — the GewSt-side already has §8b Abs. 5 5% addback
  baked in via the income-side §7 GewStG; the cleanup is delicate per
  steuerkurse.de's "Hinzurechnung nach § 8 Nr. 5 GewStG").
- **§9 Nr. 3 GewStG — foreign permanent-establishment income.** Any
  consumer with cross-border operations (a German GmbH with a UK or US
  branch) needs this Kürzung. Missing. Add as `DE-GewStG-§9-Nr-3` with a
  consumer-supplied `:gewst-foreign-pe-income` input deducted 1:1.
- **§8 Nr. 5 GewStG — 95% addback for participation income under 15%.**
  Counterpart of §9 Nr. 2a for sub-threshold participations: the KSt §8b
  exempts 95%, but GewSt adds 95% back via §8 Nr. 5 (so net effect is
  taxable for GewSt). Missing entirely from the encoding. P1 for any
  consumer holding small participations.
- **§10d EStG Mindestbesteuerung** — the loss-carryforward 60%
  limitation above €1m. Note 108 §4.2 explicitly deferred this as
  note-105 frontier 2 (the carry primitive). Reaffirm here: still
  deferred; mark in ADR-104 followups.
- **2028+ KSt rate path** — Investitionssofortprogramm (BGBl. I 2025)
  schedules 14% / 13% / 12% / 11% / 10% from 2028 / 2029 / 2030 / 2031 /
  2032. None encoded. Easy to add now as five additional
  `:parameter-value` rows on `DE.KSt.rate`. **Add proactively** —
  forward-dated parameter values are the canonical use of the
  `:parameter-value/effective-from` axis.

### P2 — edge cases

- **§11 Abs. 3 GewStG Hausgewerbetreibende 56% reduction.** Not a GmbH
  use case; defer.
- **§9 Nr. 7 GewStG — foreign KapGes participations.** EU/EWR participations
  ≥ 15% get a similar Kürzung. Defer until a consumer needs it.
- **§9 Nr. 5 / 5a — charitable donations**, §9 Nr. 4 (gone), §9 Nr. 8
  (DBA), §8 Nr. 8 silent-partner losses, §8 Nr. 9 capital-loss
  participations: all real provisions, all niche. Defer.
- **§8c / §8d KStG — loss restrictions on shareholder changes.**
  Note 108 §4.4 deferred to Phase 4 Organschaft-style follow-up.

## §3. Worked-example cross-check

### 3.1 BMF / onlinebilanz €43,687.50 base case — VERIFIED

Inputs: book-profit €150,000, Hebesatz 380%, no §8/§9, no §10/§8b.

Hand-derivation against the statute:

```
GewSt: 150 000 × 3.5% × 380%
     = 150 000 × 0.035 × 3.80
     = 150 000 × 0.1330
     = 19 950.00 ✓

KSt:   150 000 × 15% = 22 500.00 ✓
Soli:  22 500 × 5.5% = 1 237.50 ✓
KSt+Soli: 23 737.50 ✓

Total: 19 950.00 + 23 737.50 = 43 687.50 ✓
```

Cross-checked against:
- **smart-rechner.de Gewerbesteuer-Rechner** (sub-result for GewSt): €19,950.00
- **onlinebilanz.de** worked example: "Gewerbesteuer: 19.950 €" verbatim;
  KSt/Soli not shown but trivial arithmetic.
- **sevdesk Gewerbesteuerrechner 2026**: identical formula and identical
  GewSt result.

**No discrepancy.** The encoding's `(deftest bmf-worked-example-…)` passes
because the math is identical.

### 3.2 Complex case €83,402.15 — ARITHMETIC VERIFIED, SEMANTICS WRONG

Inputs: book-profit €200,000, Hebesatz 410% (Berlin), `:kst-non-deductibles
50000`, `:participation-gain 20000`, `:gewst-interest-post-freibetrag 400000`,
`:gewst-rental-expense 40000`, `:gewst-real-estate-value 50000`.

Hand-derivation:

```
KSt base: 200 000 + §10 50 000 + §8b 20 000 × 5% (= 1 000) = 251 000 ✓
KSt:      251 000 × 15% = 37 650 ✓
Soli:     37 650 × 5.5% = 2 070.75 ✓
KSt+Soli: 39 720.75 ✓

GewSt base: 200 000
          + §8 Nr. 1a interest 400 000 × 25% = 100 000
          + §8 Nr. 1d/e rental  40 000 × 12.5% = 5 000
          − §9 Nr. 1 r.estate   50 000 × 1.2% = 600
          = 304 400 ✓
GewSt:      304 400 × 3.5% × 410%
          = 304 400 × 0.035 × 4.10
          = 304 400 × 0.14350
          = 43 681.40 ✓

Total: 39 720.75 + 43 681.40 = 83 402.15 ✓
```

The numbers reproduce. But the test's **input semantics are misleading**:

- `:gewst-interest-post-freibetrag 400000M` assumes the consumer subtracted
  the €200k Freibetrag *from the interest alone*. Under real §8 Nr. 1,
  the Freibetrag is applied to the **weighted sum across all six
  categories**. A consumer who has €600k interest + €40k rental would
  legitimately want one Freibetrag deducted from the combined weighted
  sum (600k×100% + 40k×50% = 620k → −200k → 420k → ×¼ = 105k), not from
  interest alone (600k − 200k = 400k → ×25% = 100k; plus rental 40k ×
  12.5% = 5k → 105k). In THIS test the numbers coincide because the
  rental is small enough that "rent doesn't reach Freibetrag" is true
  either way. **For a finance-intensive consumer with large rent, the
  encoding will produce wrong numbers.** See §1.6 / §1.7 P0 fixes.
- `:gewst-real-estate-value 50000M` is the pre-1.4-multiplied Einheitswert
  per the compute-fn docstring (`l10n_de/cit_provider.clj:97-98`). Under
  the 2025+ regime this input has no statutory meaning at all.

Net: the test verifies the arithmetic of the substrate but not the
fidelity of the substrate to the law. A passing test today is consistent
with the substrate being subtly wrong tomorrow.

## §4. Hebesatz convention

The provider reads `:tax-unit :hebesatz` as an integer percentage (380 =
380% = 3.80×) per the docstring at `cit_provider.clj:25` and the
`gewst-formula` at `cit_provider.clj:170-175`. This matches the **universal
German usage**: gesetze-im-internet.de §16 GewStG ("Hebesatz, der sich auf
volle Prozent beläuft"); every IHK publication; every calculator (smart-
rechner, sevdesk, lexware, papierkram) presents Hebesatz as a percentage
(e.g. "Berlin 410 %"). The conversion to the multiplier (× 3.80) is then
either presented as a factor or kept as a percentage in the final
`× Hebesatz` step.

The encoded convention is the right one. **Two small docstring
improvements** would prevent future confusion:

- `cit_provider.clj:25` says "(380 = 3.80×)" — clearer would be
  "(380 = 380% = factor 3.80)". The current parenthetical reads like the
  380 IS the factor.
- The test `complex-case-all-adjustments-fire` comments "Hebesatz 410%
  (Berlin avg)" at line 85; per sevdesk's 2026 list this is the **exact**
  Berlin rate (unchanged since 1999), not an average. Either keep the
  parenthetical as "(Berlin 2026)" or drop it.

P2 nits — no behaviour bug.

## §5. Effective-date semantics

`:provision/effective-from` matches the date each cited paragraph entered
force in its currently-encoded form. Spot checks:

- `DE-KStG-§10` 2008-01-01 — the §10 catalogue itself is older (KStG 1977
  established the structure), but the modern non-deductibles list (Soli
  + GewSt non-deductible from KSt) crystallised with UntStRefG 2008. **OK**
  for the consumer-supplies-already-itemised contract this provision
  implements.
- `DE-KStG-§8b-Abs-5` 2004-01-01 — correct; the 5% Pauschalzuschlag
  generalisation came with Korb-II-Gesetz 2003-12-22 effective VZ 2004.
- `DE-SolZG-§4` 1998-01-01 — correct; 5.5% effective tax year 1998.
- `DE-GewStG-§8-Nr-1a` / `-1d` 2008-01-01 — the universal ¼ factor + €100k
  Freibetrag + six-bucket structure landed with UntStRefG 2008.
  **Subtle**: as discussed in §1.4, the Freibetrag rose to 200k effective
  2020-01-01. The provision text on gesetze-im-internet.de is the
  POST-2020 version. If the substrate ever runs against a 2019 period it
  pulls the (missing) 100k value. P1 — add the pre-2020 `:parameter-value`
  row.
- `DE-GewStG-§9-Nr-1` 2008-01-01 — partially wrong; the 1.2% formula
  itself is older than 2008 (the Messzahl × Hebesatz architecture changed
  in 2008 but Nr. 1 itself was rewritten with the Grundsteuerreform
  effective **2025-01-01**, see §1.7). The encoded effective-from is
  *too late* for the old formula (it goes back to ~1991 at this rate) and
  *too inclusive* for the new period (it should sunset 2024-12-31).

The provision-row effective-from axis is doing real work — the encoding
just hasn't populated the second sunset/transition row yet. The dataset
is internally consistent for FY 2020-2024; it is wrong for FY ≥ 2025 and
underspecified for FY ≤ 2019.

## §6. Audit-doc completeness

Citation URLs spot-checked:

- `kstg/__23.html` ✓ resolves; modern reformatted version under
  `kstg_1977/__23.html` (the `_1977` is the BJNR namespace, gesetze-im-internet's
  alias). Both work; the encoding uses the no-`_1977` form which redirects
  cleanly.
- `solzg_1995/__4.html` ✓ resolves.
- `gewstg/__11.html`, `__8.html`, `__9.html` ✓ all resolve.
- `kstg/__8b.html`, `__10.html` ✓ resolve.

**gesetze-im-internet.de IS the canonical authority** — it is the
Bundesministerium der Justiz's official text-of-law portal (per the BMJ
imprint at the URL root). Citation choice is correct.

One small **citation improvement opportunity**: `:provision/citation` is
just the URL, but `:parameter-value/citation` is human-readable text like
"§4 SolZG 1995 — stable since 1998". The two could be aligned (either
both URL, both human-text, or both as a `{:url :text}` map). Cosmetic
P2 — note 108's design picked URL-for-provisions intentionally; keep
as-is.

## §7. Actionable findings

### P0 — mis-computes a typical GmbH today, fix before next consumer

- **P0-1: §8 Nr. 1 rental/royalty weights collapsed.** Split
  `:gewst-rental-expense` into movable / immovable / royalties; add
  three correct shares (0.05M / 0.125M / 0.0625M). **Or** restructure
  to a single six-bucket provision per §1.6 Option B. File:
  `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj:73-77, 122-125`;
  `cit_provider.clj:88-94`; new test exercising movable rent and
  royalty cases. Cite: gesetze-im-internet.de §8 Nr. 1 d/e/f;
  steuerkurse.de.

- **P0-2: §8 €200k Freibetrag is per-weighted-sum, not per-category.**
  Move Freibetrag application from consumer to substrate (compute the
  weighted sum of all six §8 buckets, subtract €200k once, apply ¼
  once). Same file edits as P0-1. Cite: gesetze-im-internet.de §8
  Nr. 1 closing clause "soweit die Summe den Betrag von 200 000 Euro
  übersteigt … ein Viertel der Summe".

- **P0-3: §9 Nr. 1 obsoleted 2025-01-01.** Add a second
  `:parameter-value` for `DE.GewSt.§9.real-estate-rate` and a second
  provision `DE-GewStG-§9-Nr-1-from-2025` that uses
  `:inputs :grundsteuer-paid` for 1:1 deduction. Sunset the old
  provision via `:effective-until #inst "2025-01-01"`. Re-cast the
  complex-case test to either use a 2024 `:as-of` or to use the new
  `:grundsteuer-paid` input. File: `cit_statute.clj:127-130, 254-268`;
  `cit_provider.clj:96-101`; `cit_provider_test.clj:86-110`. Cite:
  Jahressteuergesetz 2024 BGBl. I 2024; iww-f164103; acconsis 2025
  English summary; haufe-631656; Genoverband JStG-2024 article.

### P1 — matters for some real consumers; fix in the next l10n-de sweep

- **P1-1: §9 Nr. 2a / 3 / §8 Nr. 5 GewStG dividend + foreign-PE rules
  absent.** Add three provisions (one Kürzung for ≥15% KapGes
  dividends, one Kürzung for foreign PE income, one Hinzurechnung for
  sub-threshold participation income). Roughly tripling current §9
  provision count. Cite: BMF GewStH 2024 §9 + §8 Nr. 5; Haufe
  HI7357979.

- **P1-2: pre-2020 €100k Freibetrag missing.** Add the
  `:parameter-value` row `{:effective-from #inst "2008-01-01"
  :decimal-value 100000M}` so 2008-2019 periods resolve. Strictly
  speaking the current encoding's earliest value is 2020-01-01 which
  means any pre-2020 query returns nil. **One line of EDN.**

- **P1-3: 2028+ KSt rate path.** Add forward-dated
  `:parameter-value`s for `DE.KSt.rate` per Investitionssofortprogramm
  schedule (0.14M from 2028-01-01, 0.13 from 2029, 0.12 from 2030,
  0.11 from 2031, 0.10 from 2032). Cite: haufe-HI16854493.

- **P1-4: Mindestbesteuerung (§10d EStG) not modelled.** Re-confirm
  the note 108 §4.2 deferral; track in ADR-104 followups as gated on
  note-105 frontier 2 (carry primitive). No code change today.

### P2 — defer; document as known gaps

- **P2-1: §11 Abs. 3 Hausgewerbetreibende reduced Messzahl.**
- **P2-2: §9 Nr. 7 / 8 foreign-participation / DBA Kürzungen.**
- **P2-3: §8 Nr. 8 / 9 silent-partner-loss / capital-loss
  Hinzurechnungen.**
- **P2-4: §9 Nr. 5 / 5a charitable donations.**
- **P2-5: docstring polish** for `cit_provider.clj:25` Hebesatz
  parenthetical; remove "Berlin avg" from
  `cit_provider_test.clj:85`.
- **P2-6: §8c / §8d KStG loss-trafficking** — Phase 4.

## §8. Honest summary

The headline rates and effective dates are right. The substrate plumbing
is right — provisions priority-fold cleanly, parameter resolution at
`:as-of` works, compute-fns marshal the right facts, the provider
assembles a clean two-component `TaxReturnFacts`, and the BMF €43,687.50
worked example reproduces to the cent. **The first ADR-101 consumer is
substantively working.**

The two P0 issues are scope-of-coverage rather than substrate failures:
the §8 Hinzurechnungen are encoded as if they collapse into "interest +
immovable rent" only, when the law has six categories and a weighted
sum minus Freibetrag; the §9 Nr. 1 Kürzung is encoded against a regime
that ended 2024-12-31. Both fixes are bounded — they restructure
existing provisions rather than introduce new substrate primitives —
and the substrate's date-keyed-parameter + sunset-provision machinery
is exactly what makes the fixes a row-level migration rather than a
schema change. **The fact that the substrate already supports the
fixes cleanly is itself the substrate validation that ADR-104
intended.**

P0-3 in particular is a useful early signal: any l10n module that
encodes "the law as of when I wrote it" will silently drift as
amendments land. The note-105 ADR-101 substrate's `:effective-from` /
`:effective-until` are exactly the right tool — the discipline is to
USE them at the moment a parameter is encoded, not to retrofit them
when an amendment surprises us. ADR-104's six provisions need two
sunset dates (§9 Nr. 1 → 2024-12-31; §8 Freibetrag → encode the 100k
predecessor) before they are baseline-honest.

---

## Sources

**Statute text (gesetze-im-internet.de — BMJ canonical)**

- [§23 KStG — Steuersatz](https://www.gesetze-im-internet.de/kstg_1977/__23.html)
- [§8b KStG — Beteiligung an anderen Körperschaften](https://www.gesetze-im-internet.de/kstg_1977/__8b.html)
- [§4 SolzG 1995 — Zuschlagssatz](https://www.gesetze-im-internet.de/solzg_1995/__4.html)
- [§8 GewStG — Hinzurechnungen](https://www.gesetze-im-internet.de/gewstg/__8.html)
- [§9 GewStG — Kürzungen](https://www.gesetze-im-internet.de/gewstg/__9.html)
- [§11 GewStG — Steuermesszahl](https://www.gesetze-im-internet.de/gewstg/__11.html)

**BMF amtliches Gewerbesteuer-Handbuch**

- [GewStH 2016 §8 Hinzurechnungen](https://gewsth.bundesfinanzministerium.de/gewsth/2016/A-Gewerbesteuergesetz/II-Bemessung-der-Gewerbesteuer/Paragraf-8/inhalt.html)
- [GewStH 2024 §9 Kürzungen](https://gewsth.bundesfinanzministerium.de/gewsth/2024/A-Gewerbesteuergesetz/II-Bemessung-der-Gewerbesteuer/Paragraf-9/inhalt.html)
- [GewStH 2016 §11 Steuermesszahl](https://gewsth.bundesfinanzministerium.de/gewsth/2016/A-Gewerbesteuergesetz/II-Bemessung-der-Gewerbesteuer/Paragraf-11/inhalt.html)
- [BMF-Monatsbericht Juni 2025 — Soli vor dem BVerfG](https://www.bundesfinanzministerium.de/Monatsberichte/Ausgabe/2025/06/Inhalte/Kapitel-3-Analysen/3-2-solidaritaetszuschlag-vor-bverfge.html)

**Commercial commentary**

- [Haufe — Investitionssofortprogramm §23 KStG schrittweise Senkung](https://www.haufe.de/id/beitrag/gesetz-fuer-ein-steuerliches-investitionssofortprogramm-2-schrittweise-senkung-des-koerperschaftsteuersatzes-23-abs-1-kstg-HI16854493.html)
- [Haufe — §8b KStG Abzugsverbot](https://www.haufe.de/steuern/rechtsprechung/abzugsverbot-nach-8b-abs-5-kstg-und-hinzurechnungsbesteuerung_166_358380.html)
- [Haufe — §9 Nr. 2a + 7 Kürzungen](https://www.haufe.de/id/beitrag/beteiligung-an-anderen-koerperschaften-221-kuerzung-gem-9-nr2a-und-nr7-gewstg-HI7357979.html)
- [Haufe — JStG 2024 GewStG-Anpassung Grundsteuerreform](https://www.haufe.de/steuern/gesetzgebung-politik/anpassung-des-gewerbesteuergesetzes-wegen-grundsteuerreform_168_631656.html)
- [iww — §9 Nr. 1 S. 1 GewStG ab 2025](https://www.iww.de/ssp/unternehmer/gewerbesteuer--9-nr-1-s-1-gewstg-so-ist-die-einfache-grundstueckskuerzung-ab-2025-neu-gestaltet-f164103)
- [iww — Zweites Corona-Steuerhilfegesetz Freibetrag 200k](https://www.iww.de/ssp/alle-steuerzahler/konjunkturpaket-das-zweite-corona-steuerhilfegesetz-weitere-steueraenderungen-mit-praxisrelevanz-f130095)
- [steuerkurse.de — §8 GewStG Hinzurechnungen](https://www.steuerkurse.de/gewerbesteuer-gewst/hinzurechnungen-gewstg.html)
- [steuerkurse.de — §9 GewStG Kürzungen](https://www.steuerkurse.de/gewerbesteuer-gewst/kuerzungen-gewstg.html)
- [steuerkurse.de — §9 Nr. 1 S. 1 GewStG Grundbesitz](https://www.steuerkurse.de/gewerbesteuer-gewst/ermittlung-der-gewerbesteuer/vom-gewinn-aus-gewerbebetrieb-zum-gewerbeertrag/kuerzungen-gem-9-gewstg/9-nr-1-s-1-gewstg-kuerzung-wegen-grundbesitzes.html)
- [acconsis — Trade tax cut from 2025 (EN)](https://www.acconsis.de/en/adjustment-of-the-simple-trade-tax-cut-from-2025/)
- [Genoverband — JStG 2024 Grundsteuer als neuer Bezugspunkt](https://www.genoverband.de/newsroom/news/aus-dem-verband/jahressteuergesetz-2024-die-grundsteuer-als-neuer-bezugspunkt-fuer-die-gewerbesteuer-kuerzung-nach-9-nr-1-satz-1-gewstg/)
- [ecovis-kso — Freibetrag 100k→200k 2020](https://ecovis-kso.com/blog/gewerbesteuer-freibetrag-fuer-bestimmte-hinzurechnungen-erhoeht/)
- [Lexware — Solidaritätszuschlag](https://www.lexware.de/wissen/mitarbeiter-gehalt/solidaritaetszuschlag/)
- [Wikipedia (DE) — Solidaritätszuschlag history](https://de.wikipedia.org/wiki/Solidarit%C3%A4tszuschlag)
- [BVerfG 2010 — §8b Abs. 3+5 Pauschalierungsverbot verfassungsgemäß](https://www.bundesverfassungsgericht.de/SharedDocs/Pressemitteilungen/DE/2010/bvg10-106.html)
- [Haufe DPM — §8b Abs. 5 idF Korb-II](https://www.haufe.de/steuern/haufe-steuer-office-excellence/doetschpungmoehlenbrock-dpm-die-koerperschaftsteuer-711-8b-abs5-kstg-idf-des-sog-korb-ii-ges_idesk_PI25844_HI9678708.html)

**Online calculators (cross-check oracle)**

- [smart-rechner.de — Gewerbesteuerrechner 2026](https://www.smart-rechner.de/gewerbesteuer/rechner.php)
- [onlinebilanz.de — Gewerbesteuer GmbH berechnen](https://onlinebilanz.de/gewerbesteuer-berechnen-gmbh/)
- [sevdesk — Gewerbesteuerhebesatz Berlin 2026](https://sevdesk.de/ratgeber/buchhaltung-finanzen/steuern/gewerbesteuer/gewerbesteuerhebesatz-berlin/)
- [DIHK — Gewerbesteuer-Hebesätze](https://www.dihk.de/en/service-portal/for-businesses-and-traders/business-tax-rates)
- [Statistikportal — interaktive Hebesatz-Karte](https://www.statistikportal.de/de/karte-hebesaetze)

**kontor source under review**

- `modules/l10n-de/src/kontor/l10n_de/cit_statute.clj` — 8 parameters + 6 provisions
- `modules/l10n-de/src/kontor/l10n_de/cit_provider.clj` — `DECITProvider` + 5 compute-fns
- `modules/l10n-de/test/kontor/l10n_de/cit_provider_test.clj` — 6 deftests
- `doc/research/108-de-cit-fit.md` — prior fit assessment
- `doc/decisions.md` ADR-101 (statute-as-data substrate)
- `doc/decisions.md` ADR-104 (this ship)

---

End of note 120.

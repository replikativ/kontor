---
date: 2026-05-24
title: 124 — FR CIT (IS + CGE + mère-fille + CIR) baseline review against statute + commercial calculators
audience: maintainer
status: review-after — ADR-105, second end-to-end ADR-101 consumer
---

# 124 — FR CIT baseline review

ADR-105 shipped the second end-to-end consumer of the ADR-101 statute-as-data
substrate: FR CIT (impôt sur les sociétés) encoded as nine `:parameter`s +
four `:provision`s under
`modules/l10n-fr/src/kontor/l10n_fr/cit_statute.clj`, a thin
`PeriodTaxProvider` in `cit_provider.clj`, and eleven deftests reproducing
the canonical SAS worked example (€1,003,430.75 from note 109 §2) plus
mère-fille and CIR refundability cases. Per the maintainer's standing
mandate ("once we update our l10n packages we should also review with
agents against baselines software and online documentation/official law
again"), this note audits that encoding against legifrance.gouv.fr,
BOFiP-Impôts, impots.gouv.fr, the published worked examples on legifiscal
/ compta-online / service-public, and KPMG/Deloitte/PwC commentary on
LF 2025 + LF 2026.

**Headline.** The encoded statute reproduces the four published worked
examples to the cent. Every `:parameter` *value* is statutorily correct
for fiscal years opening in 2025-2026. The provider's compute-fns marshal
the right facts. The four-provision coverage is the right minimal set for
the first ship.

The findings are **mostly P2 (citation hygiene)** with one P0 that is a
real bug (a citation URL pointing at a 1980s expired article) and one P1
that matters for any tax-consolidated group (the 1% reduced quote-part
under CGI Art. 216 al. 3). Specifically:

- **P0** — three of the four `:provision/citation` URLs and three of the
  nine `:parameter/concept-iri` URLs return HTTP 404 against
  legifrance.gouv.fr today. One of them (`LEGIARTI000006309243`)
  resolves to a different, expired article (Art. 235 quinquies from
  1982-1989 on construction profits) — so a consumer who clicks the
  CGE citation lands on completely unrelated 1989-repealed text.
  Audit-trail breakage.
- **P1** — the mère-fille `5%` quote-part is overridden to **1%** for
  qualifying dividends inside a `kontor.entity/family` (CGI Art. 216 al. 3,
  régime de groupe / intégration fiscale). The encoding has a single
  `FR.MereFille.quote-part` = 0.05M parameter with no group-context
  toggle. Any l10n-fr consumer that runs an intégration fiscale will
  overstate the addback by 4×.
- **P2** — the contribution exceptionnelle CEBGE (LF 2025 / LF 2026,
  20.6 % / 41.2 % on the two-year IS average for groups ≥ €1 B / €1.5 B
  turnover) is correctly out of scope for v1 (note 109 §3.3 + ADR-105
  Out-of-scope §1), but the deferral is documented inconsistently
  (ADR-105 says "two-year averaging not yet on the substrate" while the
  cleanest fix would just inline the rate-table compute-fn and require
  `:inputs :fr-prior-year-is`). Cite the BOFiP `BOI-IS-AUT-60`.

Everything else — the PME schedule swap, the abattement, the CIR
piecewise, the refundability flag, the substrate provenance trail — is
green. Sections below detail each finding with file:line + cite.

## §1. Statute-fidelity audit — parameter by parameter

### 1.1 `FR.IS.standard-rate` = 0.25M from 2022-01-01 — CORRECT

Article 219 I CGI (current text, LEGIARTI000046868562, "en vigueur depuis
le 01/01/2022"): "le taux de l'impôt est de 25 %." The 0.25M value and
the 2022-01-01 instant both match the closing step of the Loi de finances
2018 staged reduction (33.33 % → 31 % → 28 % → 26.5 % → 25 % across
2018/2019/2020/2021/2022). Confirmed by:

- legifrance.gouv.fr Article 219 (current text);
- impots.gouv.fr "Impôt sur les sociétés" page;
- compta-online "Les taux d'impôt sur les sociétés en 2026";
- LégiFiscal "IS : taux normal à 25 % en 2022" + "Aménagement de la
  trajectoire de baisse de l'IS" (the LF 2018 trajectory bookkeeping);
- BOFiP `BOI-IS-LIQ-10-20200610` Taux normal.

**Forward-looking.** No further reduction is currently legislated.
LF 2026 (Loi n° 2026-103 du 19 février 2026) maintained the 25 % rate
unchanged. So no second `:parameter-value` row is required today
(contrast DE, where `DE.KSt.rate` has a legislated path down to 10 % by
2032 — see note 120 §1.1 P1-3).

**Citation URL is wrong.** The `:parameter/concept-iri` is
`https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000044979423`,
which returns **HTTP 404**. Légifrance has reissued Article 219's
current-version identifier as `LEGIARTI000046868562`. See §6 P0-1.

### 1.2 `FR.IS.pme-brackets` (15 % / 25 % @ €42,500) from 2023-01-01 — CORRECT VALUE, **EFFECTIVE-DATE OFF BY ONE DAY**

Article 219 I-b CGI: "Toutefois, le taux de l'impôt est fixé, dans la
limite de 42 500 € de bénéfice imposable par période de douze mois, à …
15 % pour les exercices ouverts à compter du 1er janvier 2002." The
two-rate ladder (15 % up to €42,500, 25 % above) matches the encoded
bracket scale exactly.

The **€42,500 cap** is the post-LF 2023 figure (raised from €38,120 by
LF 2023 Art. 37). LégiFiscal "LF 2023 — principales mesures concernant
la fiscalité des bénéfices" + impots.gouv.fr "Taux réduit d'impôt sur
les sociétés : le critère du chiffre d'affaires revu" both confirm:
**the €42,500 threshold applies to fiscal years ENDING on or after
December 31, 2022.**

The encoding uses `:effective-from #inst "2023-01-01"`. For a
calendar-year filer this is *de facto* correct (their first FY whose
closing date is ≥ 2022-12-31 is the FY opening 2022-01-01, which closes
2022-12-31 — but their NEXT FY, opening 2023-01-01, is the first one
where the consumer pulls the cap for a fresh period). For a
non-calendar-year filer (e.g. FY 2022-07-01 → 2023-06-30, whose closing
is 2023-06-30 ≥ 2022-12-31), the parameter resolver returns the new cap
correctly because `:as-of` would be ≥ 2023-01-01 by then.

But there is a thin slice — a FY opening 2022-07-01 closing 2022-12-31
(short-period filer) — where the encoding returns *no* value (no
parameter-value row for 2022) and the consumer would see a `nil` cap.
Strictly the LF 2023 enactment was 2022-12-30 (BJNR Loi 2022-1726) and
took effect for FYs closed ≥ 31/12/2022. **P2** — short-period FY 2022
edge case is academic for new consumers; leave as-is and capture in a
docstring (see §6).

### 1.3 `FR.IS.pme-bracket-upper` = 42500M from 2023-01-01 — CORRECT

The scalar mirror of the bracket-scale's upper. Same statute, same
effective-date subtlety as §1.2. Pre-LF-2023 value was €38,120 (stable
since the franc-to-euro conversion of LF 2002). No
`:parameter-value` row for the €38,120 predecessor — pre-2023 queries
return nil. **P2** — same posture as note 120's missing pre-2020
Freibetrag in DE; add the predecessor row only if a consumer demands
historical back-fills.

### 1.4 `FR.CGE.rate` = 0.033M from 2000-01-01 — CORRECT (citation URL **WRONG**)

Article 235 ter ZC al. 1 CGI: "La fraction mentionnée au premier alinéa
est égale à 3,3 % pour les exercices clos à compter du 1er janvier
2000." The 0.033M value and 2000-01-01 effective-from both match. The
rate has not changed in 26 years; confirmed by LégiFiscal, Lefebvre
Dalloz, mon-entreprise.urssaf.fr (Imposition → IS → contribution
sociale), and CMS France "Contribution sociale : notion de chiffre
d'affaires" all citing the 3.3 % figure for 2025.

**Citation URL is wrong.** `:parameter/concept-iri` is
`https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000006309243`,
which resolves to **Article 235 *quinquies*** of the CGI — an
*expired* article on construction profits in force only between
1982-01-01 and 1989-07-14, abrogated by Décret 89-801 of 1989-10-27.

A consumer who clicks this citation expecting CGE statute text gets a
1980s real-estate profits levy that has been repealed for 36 years.
**P0** — see §6 P0-1.

The currently-in-force LEGIARTI for Article 235 ter ZC is
`LEGIARTI000031011715` ("en vigueur depuis le 08/08/2015" — i.e. the
post-Loi 2015-990 du 6 août 2015 version that fine-tuned the consolidated-
group exemption test). All four `:parameter`s and one `:provision`
that cite the wrong URL must be updated.

### 1.5 `FR.CGE.abattement` = 763000M from 2000-01-01 — CORRECT (same citation URL bug)

Article 235 ter ZC al. 2 CGI: "Pour le calcul de la contribution, le
montant de l'impôt sur les sociétés … est diminué d'un abattement qui
ne peut excéder 763 000 € par période de douze mois." The €763,000
abattement has been stable since the franc-to-euro conversion (≈
FRF 5,000,000 historically). Confirmed by Legifrance, BOFiP, LégiFiscal,
mon-entreprise.urssaf.fr.

**SME exemption threshold NOT encoded as a parameter.** Article 235 ter
ZC also carries an SME-exemption threshold of **CA HT €7,630,000** with
the same 75 % individual-ownership + fully-paid-capital tests as the
PME-rate eligibility. The encoding correctly defers this adjudication
to the consumer via `:tax-unit :cge-exempt?` (matches the §1.2
posture for the PME schedule eligibility, mirroring US-1040
`:filing-status`). **P2** — for consistency the €7.63 M threshold could
be encoded as a sibling `FR.CGE.sme-exemption-turnover` parameter that
the *consumer* reads to make its eligibility call (so the value is
auditable + dated). Not a behaviour bug; documentation polish.

Citation URL same `LEGIARTI000006309243` → expired Art. 235 *quinquies*
bug. **P0** — see §6 P0-1.

### 1.6 `FR.MereFille.quote-part` = 0.05M from 2000-01-01 — CORRECT FOR THE NON-GROUP CASE; **MISSING THE 1% REDUCED RATE FOR INTÉGRATION FISCALE**

Article 216 al. 1 CGI: "Les produits nets des participations ... peuvent
être retranchés du bénéfice net total ... défalcation faite d'une
quote-part de frais et charges. La quote-part de frais et charges …
est fixée uniformément à 5 % du produit total des participations,
crédit d'impôt compris." The 5 % figure for the *general* case has
been stable since the LF 2000 / LF 2011 settlement; confirmed by
legifrance.gouv.fr Article 216 (LEGIARTI000048831340 current-version),
BOFiP `BOI-IS-BASE-10-10-10-10` (sociétés éligibles), BOFiP
`BOI-IS-BASE-10-10-10-20` (participations éligibles), AdvizExperts
"Article 216 CGI : régime mère-fille".

**Missing.** Article 216 al. 3 CGI: "Le taux de la quote-part de frais
et charges visée au premier alinéa est fixé à 1 % pour les produits de
participation perçus par une société membre d'un groupe au sens de
l'article 223 A ou de l'article 223 A bis à raison d'une participation
dans une autre société membre de ce groupe …" — i.e. the 5 % becomes
**1 %** for dividends received inside a tax-consolidated group (régime
de l'intégration fiscale). A consumer that runs intégration fiscale
across a `kontor.entity/family` would multiply the dividend by 5 %
under the current encoding and overstate the réintégration by 4×.

**Fix.** Add a sibling `FR.MereFille.quote-part-integration` = 0.01M
parameter with the same effective-from, and either:
- (Option A) split the `FR-CGI-145-216-mere-fille` provision into two
  (general-case + group-case) gated on a `:tax-unit :integration-fiscale?`
  flag; or
- (Option B) let the compute-fn read `:tax-unit :integration-fiscale?`
  and select the parameter accordingly inside `fr-mere-fille-addback`.

Option B keeps the provision count stable; Option A puts the gating in
the statute (more McComb-true). **Recommendation: Option B for v1.1**
(matches the existing `cir-refundable?` pattern at
`cit_statute.clj:280-284`); promote to Option A if/when other
group-vs-standalone toggles emerge.

**P1** — see §6 P1-1. Citation URL `LEGIARTI000033817770` also returns
HTTP 404; the current Article 216 LEGIARTI is `LEGIARTI000048831340`.
**P0** for the URL — see §6 P0-1.

### 1.7 `FR.CIR.rate-base` = 0.30M from 2008-01-01 — CORRECT

Article 244 quater B I CGI (current text, LEGIARTI000051215816, "en
vigueur depuis le 01/01/2026"): "Le taux du crédit d'impôt est de
30 % pour la fraction des dépenses de recherche inférieure ou égale à
100 millions d'euros et de 5 % pour la fraction des dépenses de
recherche supérieure à ce montant."

The 30 % rate has been stable since LF 2008 (Loi n° 2007-1822 du 24
décembre 2007 art. 69) which created the modern two-tier CIR. LF 2025
narrowed the *base* (eliminated patent + technological-monitoring
expenses, lowered the operating-cost gross-up from 43 % to 40 %) but
preserved the headline 30 % / 5 % structure. LF 2026 confirmed status
quo on the rates. Confirmed by:

- legifrance.gouv.fr Article 244 quater B (current);
- LégiFiscal "LF 2025 et aménagement du CIR" (rate preserved);
- KPMG "Fiscalité des entreprises — LF 2025" (base-narrowing
  reformulation; rate preserved);
- Deloitte "Loi de finances 2025 : analyse des mesures les plus
  marquantes" (rate maintained);
- Zabala "Loi de finances 2026 : le CIR maintenu, quels impacts ?"
  (rate maintained for 2026);
- entreprises.gouv.fr "Crédit d'impôt recherche" 30 % up to €100 M
  + 5 % above;
- BPI France Création — "CIR : refundable for PMEs".

**Out of scope (correctly).** Article 244 quater B also defines:
- A **50 %** rate for qualifying R&D expenses incurred in overseas
  departments (départements d'outre-mer). The encoding has no slot
  for this regional uplift. P2 — only relevant for consumers
  operating in DOMs; defer.
- An **Article 244 quater B bis** "CICo" (crédit d'impôt collaboration
  de recherche) and a new **§k** SME-specific rate of 20 % (LOI
  n° 2025-127 du 14 février 2025 — see §1.10). Both are separate
  credits with separate compute-fns; out of scope here.

**Citation URL is wrong.** `:parameter/concept-iri` is
`https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000049680229`,
which returns **HTTP 404**. The current Article 244 quater B LEGIARTI
is `LEGIARTI000051215816` (in force since 2026-01-01 post-LF 2026).
P0 — see §6 P0-1.

### 1.8 `FR.CIR.rate-above` = 0.05M from 2008-01-01 — CORRECT

Same Article 244 quater B I, same stability since LF 2008. Same wrong
citation URL.

### 1.9 `FR.CIR.threshold` = 100000000M from 2008-01-01 — CORRECT

Same article; €100 M kink between 30 % and 5 % rates. Same wrong
citation URL.

### 1.10 NOT ENCODED — LF 2025 Art. 244 quater B § k (20 % SME-specific rate)

LF 2025 (LOI n° 2025-127 du 14 février 2025, art. 14 bis) introduced a
**new §k** in Article 244 quater B granting a **20 % base CIR rate**
for SMEs (PME au sens européen) on R&D expenses incurred from
2025-01-01, with regional uplifts (35 % medium enterprises Corsica;
40 % small enterprises Corsica). This is a *separate* credit lane,
not a replacement of the general 30 %.

The encoding doesn't carry this lane. For most consumers the existing
30 % CIR is correct; PMEs CAN elect the §k path if it gives a better
outcome (it doesn't, because 20 % < 30 % — §k is for cases where the
SME wants the credit cumul-able with other CICo-style benefits, see
Deloitte LF 2025 commentary).

**P2** — defer. Cite as "LF 2025 §k 20 % SME path is a separate elective
not modelled" in the docstring.

## §2. Provision-coverage gap analysis

The four encoded provisions (`FR-CGI-219-I-b-PME`, `-145-216-mere-fille`,
`-235-ter-ZC-CGE`, `-244-quater-B-CIR`) cover the **core SAS/SARL ship**.
What's missing, ordered by P0 / P1 / P2 severity:

### P0 — none beyond the citation URL bugs (§1.4 / §1.6 / §1.7)

The four provisions cover every component of the canonical worked
example (note 109 §2). The math reproduces. No P0 *substrate* gap.

### P1 — matters for real consumers in the next sweep

- **P1-1: 1 % quote-part for intégration fiscale** (§1.6). The
  group-context override of the 5 % to 1 % under CGI Art. 216 al. 3.
  Any consumer that consolidates wins/dividends inside a
  `kontor.entity/family` (and the kontor consolidation primitive
  ADR-073 is exactly that surface) will overstate the réintégration
  by 4 × on group-internal dividends.

- **P1-2: Contribution exceptionnelle CEBGE (LF 2025 + LF 2026)** —
  Article 48 LOI n° 2025-127 du 14 février 2025, maintained by Article
  47 of LF 2026 (Loi n° 2026-103 du 19 février 2026) for a SECOND
  exercise. Rates 20.6 % (CA HT €1 B – €3 B) / 41.2 % (CA HT ≥ €3 B),
  with a smoothing band €1 B – €1.1 B + €3 B – €3.1 B. **Base is the
  AVERAGE of the IS due for the current and prior exercise** (not the
  current IS alone — Victoris Avocat, Deloitte commentary, BOFiP
  `BOI-IS-AUT-60`). The LF 2026 threshold raised to €1.5 B for the
  second exercise.

  The encoding correctly defers this per note 109 §3.3 + ADR-105
  Out-of-scope §1, citing "two-year averaging not yet on the substrate."
  **Critical reading**: the two-year carry can be modelled
  *without* substrate work — the compute-fn just reads
  `:inputs :fr-prior-year-is` (a consumer-supplied number, parallel to
  the CIR/cap-gains carry-in pattern in note 103 §3a). The deferral
  is conservative; for the ≈ 300 groups actually concerned, the
  liability is enormous (€8 B / year in 2025), and a consumer who
  needs it can't ship without it.

  Recommendation: in the next FR sweep, add a `FR-CGI-235-ter-ZD-CEBGE`
  provision + `FR.CEBGE.rate-tier-1` / `-tier-2` / `-threshold-1` /
  `-threshold-2` parameters + a `fr-cebge-on-average-is` compute-fn
  that reads `:inputs :fr-prior-year-is`. Same shape as DE Soli (note
  120 §1.2): rate × max(0, base − abattement-tier). Net cost: one
  provision + four parameters + one compute-fn. **No substrate work.**

  P1 — promote to P0 once a consumer asks for it; explicitly track in
  ADR-105 followups.

- **P1-3: Déficit reportable (CGI Art. 209 I al. 3)** — €1 M + 50 %
  ceiling on loss carryforward. Note 109 §3.6 + ADR-105 Out-of-scope §2
  correctly punt to note 105 frontier 2 (inter-period carry primitive).
  Reaffirm here: still deferred; mark in ADR-105 followups. **The DE
  Mindestbesteuerung is the same mechanism with different constants**
  (note 120 §2 P1-4 + note 108 §4.2); FR IS demands it second; building
  the carry primitive once pays for both.

- **P1-4: CIR carry-forward 3 years then refund for non-PMEs** — CGI
  Art. 199 ter B. The encoding floors non-refundable CIR at zero via
  `apply-adjustments` non-refundable semantics (`cit_provider.clj:71`
  docstring). The 3-year carry + final-year refund is again
  inter-period state — note 105 frontier 2. **Same demand-trigger as
  the loss carry.** Reaffirm.

### P2 — defer; document as known gaps

- **P2-1: LF 2025 §k 20 % SME-specific CIR rate** (§1.10). Separate
  elective lane; defer.
- **P2-2: 50 % CIR rate for DOM expenses** (§1.7). Regional uplift;
  defer until a DOM-operating consumer demands it.
- **P2-3: §9-style régime-mère-fille edge cases** — CGI Art. 145 *bis*
  (sub-threshold-but-grandfathered participations), Art. 145 *ter*
  (specific BSPCE / convertible-bond carve-outs), shell-company
  exclusions per BOFiP `BOI-IS-BASE-10-10-10-10` §330+. Niche.
- **P2-4: Crédit d'impôt jeu vidéo (CIJV)**, **Crédit d'impôt collection
  textile (CICT)**, **Crédit d'impôt outre-mer (CIIM)**, **Crédit
  d'impôt formation des dirigeants** — all CIR-shaped sibling credits
  on distinct bases; out-of-scope sister modules.
- **P2-5: CVAE phase-out** — correctly noted as a separate
  PeriodTaxProvider per note 109 §1. Defer until a CVAE consumer
  emerges; phase-out completes 2027 per LF 2024 schedule (CMS / CCI
  commentary).
- **P2-6: Pre-2023 €38,120 PME upper** (§1.3). One row of EDN if a
  consumer needs 2008-2022 historical filing.

## §3. Worked-example cross-check

### 3.1 §1 standard 25 % + CGE (non-PME, bénéfice €4 M) — VERIFIED

Test: `standard-25pct-with-cge-non-pme`
(`cit_provider_test.clj:69-93`).

Hand-derivation against the statute:

```
IS gross: 4 000 000 × 25 % = 1 000 000.00 ✓
CGE:      max(0, 1 000 000 − 763 000) × 3.3 %
        = 237 000 × 0.033
        = 7 821.00 ✓
Total:    1 000 000 + 7 821 = 1 007 821.00 ✓
```

Cross-checked against:
- LégiFiscal "Contribution sociale IS 3.3 %" 2024/2025 worked example
  format: `max(0, IS − 763 000) × 3.3 %` matches.
- mon-entreprise.urssaf.fr "Imposition → IS → contribution sociale":
  identical formula.
- compta-online "Impôt sur les sociétés 2026" worked-example template.

No discrepancy. The test passes because the math is identical.

### 3.2 §2 PME 15 % / 25 % + CGE (canonical note 109 §2) — VERIFIED TO THE CENT

Test: `pme-15-25-with-cge-note-109-worked-example`
(`cit_provider_test.clj:99-125`). The canonical case the build was
sized for.

```
15 % bracket: 42 500 × 0.15        =     6 375.00 ✓
25 % bracket: (4 000 000 − 42 500)  × 0.25
            = 3 957 500 × 0.25     =   989 375.00 ✓
IS gross:     6 375 + 989 375      =   995 750.00 ✓
CGE:          max(0, 995 750 − 763 000) × 3.3 %
            = 232 750 × 0.033      =     7 680.75 ✓
Total:        995 750 + 7 680.75   = 1 003 430.75 ✓
```

Cross-checked against:
- LégiFiscal "Contribution sociale Impôt Société (IS) 3.3 %" worked
  example (PME 15 %/25 % case, CA HT ≥ €7.63 M so CGE fires):
  identical structure.
- compta-online "Les taux d'impôt sur les sociétés en 2026"
  multi-bracket SAS example: identical structure and reduction.
- mon-entreprise.urssaf.fr CGE simulator (interactive): inputs
  `{IS = 995 750}` returns `CGE = 7 680.75`. **Exact match.**

Note 109 §2 reported €1,003,431 (rounded); the test correctly preserves
the cent-precise €1,003,430.75. No discrepancy.

### 3.3 §3 PME + CGE-exempt + CIR refundable — VERIFIED

Test: `pme-cge-exempt-with-refundable-cir`
(`cit_provider_test.clj:131-154`).

```
IS gross:  42 500 × 0.15 + 957 500 × 0.25
         = 6 375 + 239 375  = 245 750.00 ✓
CIR:       500 000 × 0.30   = 150 000.00 ✓
Liability: 245 750 − 150 000 = 95 750.00 ✓
```

The CGE provision is gated off by `:tax-unit :cge-exempt? true`; the
test correctly asserts `(empty? (:surtaxes is-c))`. The CIR is the
canonical refundable-credit case: refundable + amount < gross → no
floor, full deduction. Correct per note 105's adjustment-layer
semantics + Article 199 ter B.

### 3.4 §3b CIR drives liability negative (refundable) — VERIFIED

Test: `cir-refundable-credit-can-drive-liability-negative`
(`cit_provider_test.clj:156-167`).

```
IS gross:  50 000 × 0.25  = 12 500.00 ✓
CIR:       100 000 × 0.30 = 30 000.00 ✓
Liability: 12 500 − 30 000 = −17 500.00 ✓ (refund)
```

The refundable flag is the substrate-validated escape from the
"floors at zero" non-refundable default. This is exactly the worked
shape note 105 §3.3 named for FR CIR.

### 3.5 §4 régime mère-fille — VERIFIED

Test: `mere-fille-5pct-addback-on-participation-dividends`
(`cit_provider_test.clj:173-191`).

```
Base:      500 000 (book-profit, excludes dividends per French
                    réintégration practice)
         + 200 000 × 0.05  = 10 000 addback
         = 510 000.00 ✓
IS gross:  510 000 × 0.25  = 127 500.00 ✓
CGE:       0 (exempt + below abattement) ✓
Liability: 127 500.00 ✓
```

The 5 % is the general-case quote-part per Art. 216 al. 1; the test
correctly does NOT exercise the al. 3 group case (1 %). See §1.6 P1-1
for the group-context gap.

### 3.6 §5 PME without CGE (note 109 §2 first table, €3 M) — VERIFIED

Test: `pme-without-cge-when-is-below-abattement`
(`cit_provider_test.clj:197-212`).

```
15 % bracket: 42 500 × 0.15        =     6 375.00 ✓
25 % bracket: (3 000 000 − 42 500) × 0.25
            = 2 957 500 × 0.25     =   739 375.00 ✓
IS gross:                          =   745 750.00 ✓
CGE:          max(0, 745 750 − 763 000) × 0.033
            = 0 × 0.033            =         0.00 ✓
Liability:                         =   745 750.00 ✓
```

The CGE provision fires but the compute-fn correctly returns 0 because
the running IS is below the abattement. This is the "surtax provision
fires and computes 0" pattern — provenance correctly records that the
provision was considered (good for audit). Matches note 109 §2 first
row exactly.

**Net.** Five distinct worked examples, all reproducing to the cent
against the statute and (where checkable) against published numerical
examples. The arithmetic is **green**.

## §4. CIR specifics

The CIR rate stack (30 % / 5 % @ €100 M kink) is unchanged in 2025-2026
despite extensive parliamentary debate (PLF 2026 floated reductions and
employment-localization gates; final text kept the rate intact —
Zabala / financeinnovation.fr / inneance.fr commentary).

What CHANGED in LF 2025 (and persists for 2026) is the **CIR base**:
- patent filing/maintenance/defense fees ❌ excluded;
- technological-monitoring expenses ❌ excluded;
- patent-insurance premiums (€60k/year cap) ❌ excluded;
- operating-cost gross-up on personnel: lowered 43 % → 40 %;
- "young doctors" doubling regime ❌ abolished.

The substrate is **base-agnostic** — it asks the consumer to supply
`:inputs :cir-qualifying-expenses` as a number already filtered for
eligibility. This is the right posture (eligibility is a domain
adjudication the substrate should not arbitrate), but it means the
consumer must update its own eligibility logic each year. Document this
in the provider docstring as "the substrate trusts the supplied figure;
LF 2025 narrowed the eligible base — see Deloitte 'LF et LFSS pour
2025' commentary."

**Refundability**: the substrate's `:cir-refundable?` flag is the right
hook. Per BPI France Création / Article 199 ter B CGI: PMEs (au sens
européen, Annexe I Règlement UE n° 651/2014) get immediate refund;
non-PMEs carry 3 years then refund any unused fraction. The encoding's
non-refundable path floors at zero today (`apply-adjustments` semantics),
which **silently loses the carry-forward** — a non-PME with CIR > IS
would lose the excess credit until the carry primitive ships. P1
demand-trigger overlap with §3.6 frontier 2. Documented at
`cit_provider.clj:70-72` ("v1 floors non-refundable CIR at zero per
`apply-adjustments` non-refundable semantics"); the comment is honest
but the consequence (silent loss) deserves a louder warning.

## §5. CGE specifics

The substrate's `fr-cge-on-is` compute-fn at `cit_provider.clj:108-120`
computes `rate × max(0, running − abattement)`. This matches Article 235
ter ZC verbatim:
> "égale à 3,3 % … de l'impôt sur les sociétés … diminué d'un abattement
> qui ne peut excéder 763 000 € par période de douze mois."

**Abattement is applied to the running IS** (not to the base, not to
the post-credit liability). The substrate gets this right by using the
late-bound `:running` thread through `apply-adjustments`
(`tax_schedule.clj` ADR-099). The `(min 0M ...)` floor ensures the
abattement doesn't go negative (correct — CGI says "ne peut excéder"
implies the deduction is capped at the IS amount, but operationally the
IS-below-abattement case yields surtax = 0, not a refund). ✓

**Turnover gate** (CA HT €7.63 M): the substrate correctly delegates
this to the consumer via `:tax-unit :cge-exempt?`. The test for §2
correctly asserts `cge-exempt? false` because CA HT €8 M > €7.63 M, and
test §3 asserts `cge-exempt? true` because CA HT €5 M < €7.63 M. The
threshold itself is not encoded as a parameter (the consumer adjudicates
+ signals the boolean). P2 — for consistency a sibling
`FR.CGE.sme-exemption-turnover` = 7630000M parameter would let the
consumer pull the threshold instead of hard-coding 7.63 M somewhere.
Not a bug; ergonomics.

**Group dimension**: for an intégration fiscale, the CA HT used for the
CGE exemption test is the **combined turnover of all group members per
the parent's filing** (Article 235 ter ZC al. 3 final sentence). The
substrate's `:tax-unit` carrier is the right pivot — the consumer
computes the combined CA HT and signals `cge-exempt?` accordingly.
Document the convention.

## §6. Effective-date semantics + citation URL audit

`:provision/effective-from` matches the date each cited paragraph
entered force in its currently-encoded form:

- `FR-CGI-219-I-b-PME` 2023-01-01 — the €42,500 ladder dates from LF
  2023 Art. 37. ✓ (subtle FY-2022 short-period edge per §1.2 P2)
- `FR-CGI-145-216-mere-fille` 2000-01-01 — the 5 % quote-part is the
  post-LF 2000 / LF 2011 settled form; for the general case the date
  is acceptable (the modern 5 % structure stabilised by 2011's
  finalisation of the European-law-driven mère-fille reform; earlier
  text had a different formula). **Acceptable.**
- `FR-CGI-235-ter-ZC-CGE` 2000-01-01 — matches "exercices clos à
  compter du 1er janvier 2000" from al. 1. ✓
- `FR-CGI-244-quater-B-CIR` 2008-01-01 — the 30 % / 5 % structure
  landed with LF 2008 (Loi n° 2007-1822 art. 69). ✓

The provision-row effective-from axis is correctly populated for the
shipped vintage. **No silent-drift hazard like DE §9 Nr. 1 (note 120
§1.7 P0-3)** because no FR component the encoding covers has a
post-2023 sunset on the books. CEBGE is the closest sunset risk (LF
2025 + LF 2026 are explicitly temporary), but the substrate correctly
excludes CEBGE from v1.

### 6.1 Citation URL audit — **THREE BROKEN URLS**

Spot-checked all `:provision/citation` + `:parameter/concept-iri`
against legifrance.gouv.fr today (2026-05-24):

| Cite location | Encoded URL | Status |
|---|---|---|
| `FR.IS.standard-rate` `:concept-iri` | `LEGIARTI000044979423` | **404** |
| `FR.IS.pme-brackets` `:concept-iri` | `LEGIARTI000044979423` | **404** |
| `FR.IS.pme-bracket-upper` `:concept-iri` | `LEGIARTI000044979423` | **404** |
| `FR.CGE.rate` `:concept-iri` | `LEGIARTI000006309243` | **resolves to expired Art. 235 *quinquies*** |
| `FR.CGE.abattement` `:concept-iri` | `LEGIARTI000006309243` | **resolves to expired Art. 235 *quinquies*** |
| `FR.MereFille.quote-part` `:concept-iri` | `LEGIARTI000033817770` | **404** |
| `FR.CIR.rate-base` `:concept-iri` | `LEGIARTI000049680229` | **404** |
| `FR.CIR.rate-above` `:concept-iri` | `LEGIARTI000049680229` | **404** |
| `FR.CIR.threshold` `:concept-iri` | `LEGIARTI000049680229` | **404** |
| `FR-CGI-219-I-b-PME` `:citation` | `LEGIARTI000044979423` | **404** |
| `FR-CGI-145-216-mere-fille` `:citation` | `LEGIARTI000033817770` | **404** |
| `FR-CGI-235-ter-ZC-CGE` `:citation` | `LEGIARTI000006309243` | **expired Art. 235 *quinquies*** |
| `FR-CGI-244-quater-B-CIR` `:citation` | `LEGIARTI000049680229` | **404** |

**Correct LEGIARTIs** (verified by WebFetch + WebSearch on legifrance
2026-05-24):

| Article | Current LEGIARTI |
|---|---|
| Article 219 CGI (taux + PME ladder) | `LEGIARTI000046868562` |
| Article 216 CGI (quote-part 5 % / 1 %) | `LEGIARTI000048831340` |
| Article 235 ter ZC CGI (CGE) | `LEGIARTI000031011715` |
| Article 244 quater B CGI (CIR) | `LEGIARTI000051215816` |

**All 13 citation URLs need updating.** Of these, the CGE citation is
the highest-severity bug — a consumer who clicks the citation lands on
1980s real-estate text that has nothing to do with the contribution
sociale.

**Fix.** Mechanical search-and-replace across `cit_statute.clj`:
- `LEGIARTI000044979423` → `LEGIARTI000046868562` (3 sites + 1 provision)
- `LEGIARTI000006309243` → `LEGIARTI000031011715` (2 sites + 1 provision)
- `LEGIARTI000033817770` → `LEGIARTI000048831340` (1 site + 1 provision)
- `LEGIARTI000049680229` → `LEGIARTI000051215816` (3 sites + 1 provision)

Also add to the provider's CI / lint: a basic "fetch each
`:concept-iri` URL, assert HTTP 200" sanity sweep before tagging a new
l10n-fr release. Even a daily cron against legifrance.gouv.fr's
LEGIARTI permalink table would have caught this. **P0** — see §7.

## §7. Audit-doc completeness

`:parameter-value/citation` is human-readable text (e.g. "CGI Art. 219
I — taux normal 25 % (loi de finances 2018 staged reduction landed
2022-01-01)") — that posture mirrors note 120 §6's "human-text for
parameter-values, URL for provisions" split. Good.

Where the human-text citation is *clearly correct*:
- `:parameter-value/citation` for `FR.IS.standard-rate` correctly
  cites Loi de finances 2018 staged reduction;
- `FR.IS.pme-bracket-upper` correctly cites LF 2023 Art. 37 raising
  €38,120 → €42,500;
- `FR.CGE.rate` correctly cites Art. 235 ter ZC + 2000 stability;
- `FR.MereFille.quote-part` correctly cites Art. 216 I al. 2 (with the
  caveat that "I al. 2" should probably be "al. 1" — Art. 216 al. 1
  defines the 5 % default; al. 3 is the 1 % group exception. P2 — see
  §1.6 follow-up);
- `FR.CIR.rate-base` correctly cites LF 2008 stability of the 30 %.

**One small cleanup** on `FR.MereFille.quote-part`: the citation reads
"CGI Art. 216 I al. 2 — quote-part de frais et charges 5 %." The 5 %
text is in Article 216 al. 1, not al. 2; al. 2 is a defunct cross-reference
to Art. 145. Update to "CGI Art. 216 al. 1" or "CGI Art. 216, I al. 1."
P2.

## §8. Actionable findings

### P0 — mis-citations + 404 audit-trail breakage, fix before next consumer

- **P0-1: 13 citation URLs broken on legifrance.gouv.fr.** Of nine
  `:parameter/concept-iri`s seven are 404; of four `:provision/citation`s
  all four are wrong. The CGE citation specifically resolves to a
  1980s expired construction-profit article (Art. 235 *quinquies*
  abrogated 1989-07-14) — actively misleading. Mechanical
  search-and-replace per §6.1 table. File:
  `modules/l10n-fr/src/kontor/l10n_fr/cit_statute.clj:69, 75, 81, 87,
  93, 99, 105, 111, 117, 211, 241, 264, 289`. Cite: legifrance.gouv.fr
  current LEGIARTIs (table in §6.1).

  **Companion fix**: add a `bb` task / nightly cron that fetches every
  `:parameter/concept-iri` + `:provision/citation` URL across all
  shipped l10n modules and asserts HTTP 200. This is the right
  systemic guard — manual citation hygiene drifts as Légifrance
  reissues LEGIARTI identifiers after each amendment.

### P1 — matters for some real consumers; fix in the next l10n-fr sweep

- **P1-1: Mère-fille 1 % rate for intégration fiscale missing.** Per
  CGI Art. 216 al. 3, the quote-part drops from 5 % to 1 % for
  dividends received from another member of the same tax-consolidated
  group. Any consumer running an intégration fiscale across a
  `kontor.entity/family` (ADR-073) will overstate the réintégration
  by 4 × on group-internal dividends. **Fix** per §1.6 Option B: add
  sibling `FR.MereFille.quote-part-integration` = 0.01M parameter +
  let `fr-mere-fille-addback` (`cit_provider.clj:95-106`) read
  `:tax-unit :integration-fiscale?` and select. Add a deftest.
  Cite: CGI Art. 216 al. 3; BOFiP `BOI-IS-GPE`.

- **P1-2: Contribution exceptionnelle CEBGE not modelled.** LF 2025
  + LF 2026 large-company surtax (20.6 % / 41.2 % @ €1 B/€1.5 B
  thresholds on the two-year IS average) is the second-largest
  corporate-tax line in France today (≈ €8 B / year in 2025; €6-7.5 B
  in 2026). ADR-105 correctly defers, citing "two-year averaging not
  yet on the substrate" — but the substrate accommodates it via
  `:inputs :fr-prior-year-is` exactly as note 103 §3a's CGT carry-in
  pattern. **No substrate work.** Promote from "deferred" to "ship
  in 1.1 with a consumer-supplied prior-IS input." Cite: BOFiP
  `BOI-IS-AUT-60-20250917`; LOI n° 2025-127 Art. 48; Loi n° 2026-103
  Art. 47; Victoris Avocat + Deloitte commentary.

- **P1-3: Déficit reportable (CGI Art. 209 I al. 3) — €1 M + 50 %
  ceiling.** Reaffirm the note 109 §3.6 deferral as gated on note 105
  frontier 2 (carry primitive). Demand-trigger overlaps with DE
  Mindestbesteuerung (note 120 §2 P1-4); building once pays for both.
  Track in ADR-105 followups + note 105 frontier 2 backlog.

- **P1-4: CIR carry-forward 3-year refund for non-PMEs.** Same
  inter-period state as P1-3 (Art. 199 ter B CGI). Current encoding
  silently floors at zero — a non-PME with CIR > IS loses the excess
  credit. Reaffirm as gated on frontier 2; the docstring at
  `cit_provider.clj:70-72` is honest but should warn louder about
  silent loss.

### P2 — defer; document as known gaps

- **P2-1: LF 2025 §k 20 % SME-specific CIR rate** (§1.10).
- **P2-2: 50 % CIR rate for DOM expenses** (§1.7).
- **P2-3: Pre-2023 €38,120 PME upper** + pre-LF-2023 PME-bracket
  values (§1.3).
- **P2-4: `FR.CGE.sme-exemption-turnover` = 7,630,000M sibling
  parameter** for consumer-side eligibility-test consistency (§1.5).
- **P2-5: `:parameter-value/citation` for `FR.MereFille.quote-part`
  reads "Art. 216 I al. 2" — should be "al. 1"** (§7).
- **P2-6: Edge mère-fille cases** (CGI Art. 145 bis / ter, shell-company
  carve-outs per BOFiP `BOI-IS-BASE-10-10-10-10` §330+).
- **P2-7: CIJV / CICT / CIIM / CICo + Art. 244 quater B bis** —
  CIR-shaped sibling credits; out-of-scope sister modules.
- **P2-8: CVAE phase-out** — separate provider per note 109 §1; defer.

## §9. Honest summary

The headline rates, the abattement, the bracket ladder, the CIR
piecewise, and the worked-example reproduction are all green. The
substrate's `kontor.statute` evaluator + `PeriodTaxProvider` + parameter
resolution at `:as-of` all work — five distinct test cases reproduce
to the cent against legifrance.gouv.fr + LégiFiscal + mon-entreprise
worked examples. **The second ADR-101 consumer is substantively
working** and the first proof that the ADR-104 template generalises
from DE (KSt + Soli + GewSt) to a one-component IS world without code
gymnastics. That's the substrate validation.

The one **P0** is mechanical and embarrassing: 13 citation URLs are
wrong, three resolve to expired-1989 text. The fix is search-and-replace
+ a cron. Audit-trail hygiene matters because the entire point of
ADR-090's `:concept-iri` seam (note 88 / 89) is that downstream
consumers and tax authorities can follow the link from the substrate to
the statute. If the link is dead, the seam doesn't work.

The **P1**s are scope-of-coverage items, not substrate failures. The
1 % intégration-fiscale rate is a 5-line statute fix. CEBGE can ship
without substrate work the moment a consumer demands it. The two
inter-period carry items (Mindestbesteuerung / FR déficit reportable +
CIR 3-year carry) are correctly deferred to note 105 frontier 2 — DE
and FR now both demand it, which sharpens the prioritisation.

**Compare to note 120 (DE CIT review):** the DE encoding had two
genuine substrate-fidelity bugs (P0-1 §8 Nr. 1 weights collapsed; P0-3
§9 Nr. 1 obsoleted 2025-01-01) plus the per-category-Freibetrag
semantics issue. FR has none of those — the IS world is structurally
simpler (one component, one ladder, one surtax, two adjustments) and
the encoding faithfully mirrors the law. **The FR ship is qualitatively
cleaner than the DE ship**, which is what the maintainer expected when
saying ADR-104's DE work was the template and ADR-105's FR work should
be a confirmation.

Net: green substrate + green math + broken citations. Fix the
citations before tagging.

---

## Sources

**Statute text (legifrance.gouv.fr — DILA / Premier Ministre canonical)**

- [CGI Article 219 — taux normal IS + PME 15 %/25 %](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562)
- [CGI Article 216 — quote-part 5 % / 1 % mère-fille](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048831340)
- [CGI Article 235 ter ZC — Contribution sociale 3.3 % sur l'IS](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000031011715)
- [CGI Article 235 ter ZC — section parente Code général des impôts](https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577/LEGISCTA000006162551/)
- [CGI Article 244 quater B — Crédit d'Impôt Recherche](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051215816)
- [CGI Article 145 — conditions du régime mère-fille](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051203497)

**BOFiP-Impôts (administration fiscale)**

- [BOI-IS-LIQ-10 — Taux normal IS](https://bofip.impots.gouv.fr/bofip/2066-PGP.html/identifiant=BOI-IS-LIQ-10-20200610)
- [BOI-IS-LIQ-20-20 — Taux réduit PME (post-LF 2023 €42,500)](https://bofip.impots.gouv.fr/bofip/2065-PGP.html/identifiant=BOI-IS-LIQ-20-20-20230621)
- [BOI-IS-LIQ-20-10 — Liquidation IS](https://bofip.impots.gouv.fr/bofip/2062-PGP.html/identifiant=BOI-IS-LIQ-20-10-20230621)
- [BOI-IS-AUT-10-10 — Contribution sociale sur l'IS](https://bofip.impots.gouv.fr/bofip/3491-PGP.html/identifiant=BOI-IS-AUT-10-10-20180801)
- [BOI-IS-AUT-60 — Contribution exceptionnelle CEBGE (LF 2025)](https://bofip.impots.gouv.fr/bofip/14607-PGP.html/identifiant=BOI-IS-AUT-60-20250917)
- [BOI-IS-BASE-10-10-10-10 — Régime mère-fille, sociétés éligibles](https://bofip.impots.gouv.fr/bofip/1924-PGP.html/identifiant=BOI-IS-BASE-10-10-10-10-20230621)
- [BOI-IS-BASE-10-10-10-20 — Régime mère-fille, participations éligibles](https://bofip.impots.gouv.fr/bofip/8534-PGP.html/identifiant=BOI-IS-BASE-10-10-10-20-20200603)

**impots.gouv.fr / service-public.fr (gouvernement)**

- [Impôt sur les sociétés — page d'introduction](https://www.impots.gouv.fr/international-professionnel/impot-sur-les-societes)
- [Taux réduit IS — critère du chiffre d'affaires LF 2023](https://www.impots.gouv.fr/actualite/taux-reduit-dimpot-sur-les-societes-le-critere-du-chiffre-daffaires-revu-pour-les)
- [Contribution sociale sur l'IS — service-public](https://entreprendre.service-public.gouv.fr/vosdroits/F23510)
- [CIR — service-public](https://entreprendre.service-public.gouv.fr/vosdroits/F23533)
- [URSSAF — Imposition IS Contribution sociale](https://mon-entreprise.urssaf.fr/documentation/entreprise/imposition/IS/contribution-sociale)
- [DGE — Crédit d'impôt recherche](https://www.entreprises.gouv.fr/espace-entreprises/beneficier-d-une-aide-ou-d-un-credit-d-impot/credit-dimpot-recherche)
- [economie.gouv.fr — L'IS comment ça marche](https://www.economie.gouv.fr/entreprises/gerer-sa-fiscalite-et-ses-impots/limpot-sur-les-benefices-ir-et/limpot-sur-les-societes)

**Commercial commentary**

- [LégiFiscal — IS taux normal 25 % en 2022](https://www.legifiscal.fr/actualites-fiscales/3027-is-taux-normal-25-2022.html)
- [LégiFiscal — PLF 2018 trajectoire IS](https://www.legifiscal.fr/actualites-fiscales/1642-plf-2018-taux-is-25-2022.html)
- [LégiFiscal — Aménagement trajectoire baisse IS](https://www.legifiscal.fr/actualites-fiscales/2470-amenagement-trajectoire-baisse-is.html)
- [LégiFiscal — LF 2025 aménagement CIR/CII/CIC](https://www.legifiscal.fr/actualites-fiscales/4209-lf-2025-amenagement-cir-cii-cic-commentaires-administration.html)
- [LégiFiscal — PLF 2025 réduction base CIR](https://www.legifiscal.fr/actualites-fiscales/4024-plf-2025-reduction-base-calcul-credit-impot-recherche.html)
- [Lefebvre Dalloz — LF 2023 fiscalité des bénéfices](https://formation.lefebvre-dalloz.fr/actualite/lf-2023-principales-mesures-concernant-la-fiscalite-des-benefices)
- [Lefebvre Dalloz — Contribution sociale sur l'IS](https://formation.lefebvre-dalloz.fr/actualite/contribution-sociale-sur-limpot-sur-les-societes)
- [Lefebvre Dalloz — Régime mère-fille intégration fiscale 1%](https://formation.lefebvre-dalloz.fr/actualite/regime-mere-fille-la-reintegration-de-la-quote-part-vise-bien-imposer-une-fraction-des-produits)
- [Compta-Online — Taux IS 2026 (25 %/15 %/CEBGE)](https://www.compta-online.com/taux-is-impot-sur-les-societes-ao2921)
- [Compta-Online — Calcul IS 2026](https://www.compta-online.com/impot-sur-les-societes-comment-calculer-son-is-payer-ao2518)
- [Compta-Online — Réductions et crédits d'impôt 2025](https://www.compta-online.com/reductions-credits-impot-pour-entreprises-ao5633)
- [Compta-Online — Calcul acompte IS 2026](https://www.compta-online.com/baisse-du-taux-is-quel-impact-sur-les-acomptes-ao2430)
- [KPMG France — LF 2025 corporate tax mesures définitives](https://kpmg.com/av/fr/avocats/eclairages/2025/02/plf-2025-corporate-tax-les-mesures-definitives.html)
- [KPMG US — France Tax measures in 2026 Finance Bill (Pillar Two)](https://kpmg.com/us/en/taxnewsflash/news/2025/10/france-tax-measures-2026-finance-bill.html)
- [Deloitte — LF 2025 analyse mesures marquantes](https://blog.avocats.deloitte.fr/loi-de-finances-2025-analyse-des-mesures-les-plus-marquantes/)
- [Deloitte — PLF 2026 mesures significatives](https://blog.avocats.deloitte.fr/plf-2026-analyse-des-mesures-les-plus-significatives/)
- [Deloitte — LF + LFSS 2025 fiscalité R&D](https://blog.avocats.deloitte.fr/lf-et-lfss-pour-2025-panorama-des-differentes-mesures-prises-en-matiere-de-fiscalite-de-la-rd/)
- [DLA Piper — French Finance Bill 2026](https://www.dlapiper.com/en/insights/publications/2025/10/projet-de-loi-de-finances-pour-2026)
- [CMS France — Contribution sociale notion de CA](https://cms.law/fr/fra/news-information/contribution-sociale)
- [CMS France — Taux réduit IS PME](https://cms.law/fr/fra/news-information/taux-reduit-d-is-des-pme)
- [Victoris Avocat — CEBGE régime fiscal et calcul](https://www.victorisavocat.com/blog/contribution-exceptionnelle-benefices-grandes-entreprises-cebge)
- [Victoris Avocat — Régime mère-fille guide](https://www.victorisavocat.com/blog/regime-mere-fille-guide-complet-dirigeants-pme)
- [CCI Lyon Métropole — CEBGE budget 2026](https://www.lyon-metropole.cci.fr/actualite/contribution-exceptionnelle-sur-les-benefices-des-grandes-entreprises)
- [PublicSénat — CEBGE supprimée puis maintenue](https://www.publicsenat.fr/actualites/parlementaire/impot-sur-les-societes-le-senat-supprime-la-contribution-exceptionnelle-des-grandes-entreprises)
- [Alerion Avocats — Mère-fille 5 % quote-part](https://www.alerionavocats.com/en/parent-subsidiary-regime-share-costs-expenses-taxation/)
- [LegalPlace — Régime mère-fille (2026)](https://www.legalplace.fr/guides/regime-mere-fille/)
- [LegalStart — Taux IS 2026](https://www.legalstart.fr/fiches-pratiques/fiscalite-entreprises/taux-is/)
- [AdvizExperts — Article 216 CGI mère-fille](https://advizexperts.fr/code-general-impots/article-216-cgi-regime-mere-fille-quote-part-frais/)
- [AdvizExperts — Article 1668 D acomptes contribution sociale](https://advizexperts.fr/code-general-impots/article-1668-d-cgi-contribution-sociale-impot-societes/)
- [Aequitas — Réévaluation plafond taux réduit IS](https://www.aequitas.fr/reevaluation-plafond-taux-reduit-impot-des-societes/)
- [Allé Avocats — IS barème 2026](https://www.alleavocats.com/impo-des-societes-bareme-et-taux-applicables-en-2026/)
- [BPI France Création — CIR refundable PME](https://bpifrance-creation.fr/encyclopedie/aides-a-creation-a-reprise-dentreprise/aides-a-linnovation/cir-credit-dimpot-recherche)
- [Zabala — LF 2026 CIR maintenu](https://www.zabala.fr/actualites/loi-finances-2026-cir/)
- [Inneance — Promulgation LF 2026](https://www.inneance.fr/promulgation-de-la-loi-de-finances-2026/)
- [Finance Innovation — CIR/CICO/C3IV LF 2026](https://www.financeinnovation.fr/2026/02/23/cir-cico-et-c3iv-la-loi-de-finances-2026-a-ete-promulguee-au-journal-officiel/)
- [Myriad Consulting — CIR 2025 brevets veille suppression](https://www.myriadconsulting.fr/ressources/blog/cir-2025-suppression-depenses-brevet/)
- [Sogedev — LF 2025 impacts CIR](https://blog.sogedev.com/loi-de-finances-2025-les-nouveautes-et-impacts-sur-le-credit-dimpot-recherche-cir/)
- [Lefebvre Dalloz — Conseil d'État taux réduit critère CA](https://formation.lefebvre-dalloz.fr/actualite/condition-de-chiffre-daffaires-pour-le-taux-reduit-dimpot-sur-les-societes-clarifications-du-conseil-detat)
- [L-expert-comptable — CEBGE c'est fini](https://www.l-expert-comptable.com/a/531647-la-contribution-exceptionnelle-de-l-c-est-fini.html)
- [Fiscalonline — Auto-détenues exclues seuil 75%](https://fiscalonline.com/Entreprise/Taux-reduit-d-IS-et-exoneration-de-contribution-235-ter-ZC-du-CGI-les-actions-auto-detenues-exclues-du-calcul-du-seuil-de-detention-de-75)

**kontor source under review**

- `modules/l10n-fr/src/kontor/l10n_fr/cit_statute.clj` — 9 parameters + 4 provisions
- `modules/l10n-fr/src/kontor/l10n_fr/cit_provider.clj` — `FRCITProvider` + 3 compute-fns
- `modules/l10n-fr/test/kontor/l10n_fr/cit_provider_test.clj` — 11 deftests / 52 assertions
- `doc/research/109-fr-cit-fit.md` — prior fit assessment
- `doc/research/120-de-cit-baseline-review.md` — sibling DE review (template for this note)
- `doc/decisions.md` ADR-101 + Addendum 1 (statute-as-data substrate)
- `doc/decisions.md` ADR-104 (DE CIT, first end-to-end consumer)
- `doc/decisions.md` ADR-105 (FR CIT, second end-to-end consumer — this ship)

---

End of note 124.

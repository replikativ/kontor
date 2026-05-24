---
date: 2026-05-24
title: 141 — FR CGT (PFU + barème + immobilière + pro + IS-participation + IP-box) baseline review against statute + practitioner sources
audience: maintainer
status: review-after — ADR-103 FR component, second end-to-end consumer of ADR-102 disposal substrate + ADR-101 statute-as-data after note 138 DE-CGT
---

# 141 — FR CGT baseline review

ADR-103's FR limb shipped twin `PeriodTaxProvider`s
(`fr-personal-cgt-provider` + `fr-corporate-cgt-provider`) over the ADR-102
`:disposal` substrate + ADR-101 statute-as-data. Two source files +
one test file:

- `modules/l10n-fr/src/kontor/l10n_fr/cgt_statute.clj` — 21 `:parameter`s
  + 24 `:parameter-value`s spanning PFU IR (12.8 %), PS default
  (17.2 % → 18.6 % LFSS-2026 step), PS real-estate carve-out
  (17.2 %), immobilière IR (19 %), surtaxe 1609 nonies G floor,
  abattement ladders (mob + immo, IR + PS), §151 septies thresholds,
  §238 quindecies cliffs (pre/post-2025), §219 QPFC (12 %),
  §219 holding period (2 y), §238 IP-box (10 %). `provisions []`
  empty by design (note 128 §5.4 — provider routes natively).
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_provider.clj` — five lane
  components (mobilière / immobilière / pro-CT / pro-LT / titres-
  participation + brevets), per-disposal abattement-durée math,
  surtaxe 1609 nonies G cliff approximation, §151 septies +
  §238 quindecies proportional reduction, PEA IR-exoneration with
  PS retained, 12 % QPFC routed via `:cit-base-additions`, IP-box
  nexus-weighting via `:inputs :ip-box`.
- `modules/l10n-fr/test/kontor/l10n_fr/cgt_provider_test.clj` — 19
  deftests reproducing note 128 §2.1 (PFU €62 800), §2.2
  (immobilière 23y → €29 257.20), §2.3 (titres-de-participation
  €8M → €960k QPFC) to the cent + lane coverage tests.

Per the maintainer's standing mandate ("once we update our l10n
packages we should also review with agents against baselines software
and online documentation/official law again"), this note audits the
encoding against Légifrance (CGI 150-0 A / 150-0 D / 150 U / 151
septies / 167 bis / 200 A / 219 / 238 / 238 quindecies / 1609
nonies G), BOFiP (BOI-RFPI-TPVIE-20, BOI-RFPI-PVI-20-20, BOI-RPPM-
PVBMI-20-30-10, BOI-RPPM-PVBMI-20-10-40, BOI-IS-BASE-20-20-10-20,
BOI-BIC-BASE-110-30), impots.gouv.fr taxpayer pages, Bpifrance
Création, the published worked examples on hagnère-patrimoine /
construires / ramify / meilleurescpi, DLA Piper LFSS 2026
commentary, and the LFI 2026 + LFSS 2026 practitioner analyses on
légifiscal / actu-juridique / TGS France / Auguste Patrimoine.

**Headline.** The provider is structurally correct across all five FR
CGT regimes. The three worked examples reproduce to the cent. The
substrate-fit research (note 128) translated cleanly into code — only
enumerant additions on shipped slots, no schema change. The
five-lane discrimination matches the statutory carve-outs. PEA
exoneration semantics are right (IR=0, PS retained at the post-2026
18.6 % per the L.136-8 IV carve-out — yes, PEA is in the hike set
per Meilleurtaux Placement Feb 2026 confirmation). The 12 % QPFC
routing into `:cit-base-additions` mirrors the proven DE §8b pattern
(note 113 §5.1 + note 138 §3.4).

The findings split into **two P0s that affect numbers**, **two P1s
that affect material edge cases**, and a handful of P2/doc items:

- **P0-1** — `FR.CGT.PS.default-rate` effective-from is `2026-01-01`.
  Per DLA Piper + Actu-Juridique + TGS-France: the LFSS 2026 hike
  for **revenus du patrimoine** (which includes plus-values
  mobilières) is **retroactive to 2025-income** (filed in 2026).
  Only **revenus de placement** (dividendes / intérêts) take the
  hike from 2026-01-01 forward. A 2025 mobilière disposal under the
  current encoding gets the legacy 17.2 % PS rate — but the correct
  rate is 18.6 %. Under-states by 1.4 pp of the gross gain.
- **P0-2** — `FR.CGT.§238-quindecies.threshold-full` raised to
  **700 000 €** from 2025-01-01 with the citation pointing at the LFI
  2024 reform. **The €700 k / €1.2 M raise is reserved exclusively to
  AGRICULTURAL transmissions** (CGI Art. 238 quindecies VII bis;
  young-farmer installation aid). Standard non-agricultural business
  transmissions **stay at €500 k / €1 M**. Confirmed by Légifrance
  current text + Bpifrance Création (June 2025) + the Bpifrance
  encyclopedia page cited by note 128 itself. Provider over-exempts
  €500 k–€700 k non-agricultural transmissions entirely. Note 128
  §1.5 originated the misreading.
- **P1-1** — `FR.CGT.§219.QPFC-rate` and the `titres-participation`
  component do not encode the **CGI Art. 216 al. 3 / Art. 223 B
  group-context override** — inside an `intégration fiscale`
  (consolidated tax group), the QPFC drops to **1 %** for qualifying
  intra-group long-term gains on titres-de-participation. The same
  issue surfaced in note 124 §1.4 for the mère-fille dividend QPFC.
  Any l10n-fr consumer running an intégration fiscale (which is
  common for SAS/SARL groups) over-states the addback by 12×.
- **P1-2** — `abat-mobiliere-pct` reads `:fr-titres-pme` AS the
  renforcé-eligibility signal (provider docstring §abat-mobiliere-pct
  is explicit about this), but the **abattement renforcé requires
  ALSO that the shares be acquired before 2018-01-01 AND that the
  issuer-PME be < 10 years old AT ACQUISITION** (CGI Art. 150-0 D
  1 quater + BOI-RPPM-PVBMI-20-30-10, the 9-point checklist). The
  pre-2018 check IS done; the < 10y-issuer-age check is **not**
  done and is documented as consumer-side ("the consumer either
  tags the asset-class appropriately or doesn't claim"). The
  hand-off is silent — there's no error / warning / audit-doc
  attestation when a consumer tags `:fr-titres-pme` on a 15-y/o PME.

The smaller items:

- **P2-1** — `surtaxe-1609-nonies-G` docstring says the cliff
  approximation under-states by "**≤ €2 000**"; the actual maximum
  under-statement is **€2 500** (at the €250 001 – €260 000
  transition, BOI-RFPI-TPVIE-20 verbatim formula `6 % PV − (260 000
  − PV) × 25/100` → €1 500 at PV=€250 001 vs €15 000 step-rate at
  €250 001 — but at the very edge PV=€260 000 the smoothed and
  stepped agree). Magnitude off by €500.
- **P2-2** — `:agricultural` activity class is silently dropped to
  `:services` thresholds in `§151-septies-fraction` (`case` default
  fallthrough). Note 128 §1.5 documents `:agricultural` at
  €350 k / €550 k thresholds. A consumer passing `:activity
  :agricultural` gets the €90 k / €126 k cliffs applied.
- **P2-3** — LFI 2026 raised the §150-0 B ter apport-cession
  reinvestment quota from **60 % to 70 %** (effective post-publication
  February 2026, per LégiFiscal). The provider's docstring (§"Out of
  scope for v1") says "60 % / 70 % at the deadline" which is honest
  about deferral, but the elective-regime enum
  `:fr-150-0-B-ter-apport-cession` lacks a way to mark the quota
  vintage. Documentation only — no immediate code impact since the
  deadline check is deferred.
- **P2-4** — `whole-years-between` uses `365.25` averaging. FR's
  abattement-durée actually counts **whole years from acquisition
  date** (calendar arithmetic, not /365.25). For typical multi-year
  cases the two agree; for ±1-day-near-anniversary edge cases the
  floor result may differ by 1 from the BOFIP method. Note 113 §3.1
  (DE-CGT-fit) made the same observation and let it stand for v1.
- **P2-5** — three of the `:parameter/concept-iri` URLs are
  permalink-imperfect (one to `hagnere-patrimoine.fr` for the
  real-estate PS carve-out is a practitioner blog, not authority);
  one points at a deep BOI ident that has been re-versioned. Audit-
  trail aging but no immediate breakage.
- **P3-1** — `loss-buckets` enumerates `:fr-mv-titres-participation`,
  but the `titres-participation-component` only filters
  `(pos? (realized-gain %))` and never queries `:loss-bucket`. Dead
  enum on the corporate side. Acceptable since losses on titres-de-
  participation are sealed (non-deductible, BOI-IS-BASE-20-20-10-20
  ii) — but the bucket would still want to be tagged on the disposal
  for analytics; the provider should at least DOCUMENT that the
  bucket is consumer-tag-only.
- **P3-2** — note 128 §4.5 documents the per-vintage loss carry
  shape; the provider's actual `:inputs :capital-loss-carryforward`
  accepts a single pooled BigDecimal per bucket (`:fr-mv-mobilière
  <bd>`). The docstring is upfront about this v1 limitation but
  the gap remains: a consumer with a €40k vintage-2014 carry and a
  €50k vintage-2022 carry pools them at €90k and offsets oldest-first
  is not enforced — the consumer self-sorts.

Everything else — the five-lane discrimination, the abattement-durée
ladders, the surtaxe brackets, the PFU/barème routing, the PEA
exoneration semantics, the §151 septies proportional reduction, the
12 % QPFC routing into `:cit-base-additions`, the brevets nexus
weighting, the bitemporal §238 quindecies cliff swap, the multi-
lane composition tests — is green.

Sections below detail each finding with file:line + cite.

## §1. Statute-fidelity audit — parameter by parameter

### 1.1 `FR.CGT.PFU.IR-rate` = 0.128M from 2018-01-01 — CORRECT

`cgt_statute.clj:94-99` + `:236-239`. CGI Art. 200 A 1° (current text
LEGIARTI000045760882): "12,8 %". Stable since LF 2018 enacted the
PFU. No subsequent change in LF 2019-2026. Confirmed by:

- Légifrance Art. 200 A;
- impots.gouv.fr "Les cessions mobilières";
- Ramify "Flat tax 2026";
- Meilleurescpi "PFU 31,4 % le guide ultime du prélèvement
  forfaitaire unique" (2026 update);
- Victoris Avocat "Flat tax 2026 dirigeants" — Q1 2026 update
  confirming 12.8 % unchanged.

The 2018-01-01 effective-from instant matches the LF 2018 enactment
(loi 2017-1837, art. 28).

### 1.2 `FR.CGT.PS.default-rate` 17.2 % → 18.6 % at 2026-01-01 — **P0-1: WRONG EFFECTIVE DATE FOR REVENUS DU PATRIMOINE**

`cgt_statute.clj:101-105` (parameter) + `:242-250` (parameter-values).

The encoding closes the legacy 17.2 % row at `effective-until #inst
"2026-01-01"` and opens the 18.6 % row at `effective-from #inst
"2026-01-01"`. The citation is "CSG raised 9.2 → 10.6 % by LFSS 2026
(loi 2025-1403) → total PS 18.6 % on securities + ordinary
investment income."

**The real LFSS 2026 wire (LFSS 2026 = loi de financement de la
sécurité sociale pour 2026, voted 2025-12-15, published JO
2025-12-31) has TWO different effective dates per income class**,
per DLA Piper (the authoritative law-firm guidance the note 128 cited
itself in §6) and Actu-Juridique:

- **Revenus de placement** (dividendes, intérêts, produits
  d'assurance-vie, certains gains de cession soumis au PFLU) — hike
  applies to revenus **versés à compter du 1er janvier 2026** (i.e.
  the receive-date semantics matches the provider's 2026-01-01).
- **Revenus du patrimoine** (plus-values mobilières, revenus
  fonciers, rentes viagères constituées à titre onéreux) — hike is
  **RETROACTIVE to 2025-income** — i.e. a mobilière disposal realised
  in **2025** is filed on the 2026 déclaration and bears the new
  **18.6 %** PS rate.

This is the explicit category-level split L. 136-8 IV CSS makes;
plus-values mobilières fall under "revenus du patrimoine" (Section
2, Code de la sécurité sociale L. 136-6) and are subject to the
retroactive application. The provider's single `effective-from
2026-01-01` row therefore returns **17.2 %** for a 2025-09 disposal
that should be taxed at **18.6 %**.

Concrete impact: a €100 k 2025 mobilière gain under PFU pays €17 200
PS under the encoding vs €18 600 correctly — under-states by €1 400
(1.4 pp of gross gain). Scaled by population this is significant.

**Fix sketch.** The encoding needs to **distinguish revenus de
placement from revenus du patrimoine** on the PS rate, because they
have different effective dates of the same loi. Two clean options:

1. Two parameters — `FR.CGT.PS.placement-rate` (effective-from
   2026-01-01) + `FR.CGT.PS.patrimoine-rate` (effective-from
   2025-01-01, with the same 18.6 % value). The provider picks per
   asset class (mobilière → patrimoine; PEA → placement when paid
   from the envelope; immobilière → carve-out).
2. Keep the single rate row but back-date `effective-from` to
   2025-01-01 and let the immobilière carve-out (separate parameter
   already) handle the real-estate exception. This loses the placement-
   vs-patrimoine distinction but the *numbers* come out right for the
   mobilière case the provider mostly hits.

Recommendation: option 1 — preserves the doctrine. A consumer with
**dividend income** posting at 2025-12-15 (revenus de placement →
historic 17.2 %) and **mobilière disposal** posting at 2025-08-10
(revenus du patrimoine → new 18.6 %) currently both read the legacy
17.2 %; option 1 separates these correctly. The CSG-deductible-from-
N+1-IR detail (note 128 §2.1 Track 2) is its own can of worms;
not in scope of this fix.

**Confirmed by**:
- [DLA Piper "Loi de financement de la Sécurité sociale 2026"](https://www.dlapiper.com/fr-fr/insights/publications/2026/01/loi-de-finance-de-la-securite-sociale-2026) (the cited authoritative analysis);
- [Actu-Juridique "La CSG en hausse sur les revenus du capital"](https://www.actu-juridique.fr/fiscalite/fiscal-finances/la-csg-en-hausse-sur-les-revenus-du-capital/);
- [TGS France "Revenus du capital : le taux de la CSG augmente"](https://www.tgs-france.fr/blog/revenus-du-capital-le-taux-de-la-csg-augmente/) — explicit "Pour les revenus du patrimoine (LMNP, plus-values mobilières), l'augmentation est rétroactive et concerne les revenus perçus dès 2025";
- LCL "Déclaration des plus-values de cession de titres perçues en 2025" — confirms PFU @ 31.4 % on 2025 gains filed in 2026.

### 1.3 `FR.CGT.PS.real-estate-rate` 17.2 % from 2018-01-01 — CORRECT (carve-out applies)

`cgt_statute.clj:107-111` + `:253-256`. Confirmed by:

- DLA Piper analysis ("**le taux de 9,2 % est néanmoins maintenu**
  pour certains revenus limitativement énumérés à l'article L. 136-8,
  IV du code de la sécurité sociale (notamment les revenus fonciers
  et les plus-values immobilières)");
- hagnère-patrimoine "Plus-value immobilière 2026" (the cited
  source in note 128) — "PS 17,2 % maintenu, taux global 36,2 %";
- construires "Plus-value immobilière 2026 36,2 %" (cited);
- Notaires de Paris and avocats-picovschi practitioner pages.

The label correctly lists "real estate / life insurance / PEL / CEL /
PEP / revenus fonciers"; the provider only uses this parameter for the
immobilière component and pro-LT (§1.5 below — pro-LT borrows the
real-estate rate by analogy, which is documented but worth its own
parameter long-term). Citation IRI is to hagnère-patrimoine — a
practitioner blog, **NOT authority** — should be the explicit text of
LFSS 2026 art. (TBC) or BOI-RPPM-RCM-30-20-40 (BOFIP doctrine on
PS rates). P2-5.

### 1.4 `FR.CGT.Immo.IR-rate` = 0.19M from 2012-02-01 — CORRECT

`cgt_statute.clj:113-118` + `:259-262`. CGI Art. 200 B: "Les plus-
values réalisées dans les conditions prévues aux articles 150 U à
150 UC sont imposées au taux forfaitaire de 19 %". The
2012-02-01 effective date matches the relevement from 19 % to 19 %
(actually the rate was 16 % through end-2010, raised to 19 % by
LF 2011, then stable). Confirmed by:

- hagnère-patrimoine, construires, Berenfus, GPS Patrimoine — all
  the 2026 practitioner pages quote 19 %;
- IFRAP comparative European study quotes 19 % FR;
- impots.gouv.fr taxpayer page on plus-values immobilières.

Effective date precision: the LF 2011 raise enacted 2010-12-29 was
applied to cessions completed from 2011-02-01 (per BOI-RFPI-PVI-30
historical). The `2012-02-01` in the encoding is **one year off**
from the real first-application date (P3 — pre-history detail; no
disposals on the substrate are old enough to trigger it).

### 1.5 `FR.CGT.Immo.surtaxe-floor` = 50 000 € from 2013-01-01 — CORRECT

`cgt_statute.clj:120-124` + `:265-268`. CGI Art. 1609 nonies G:
"Pour les plus-values supérieures à 50 000 €". Stable since the
LFR 2012 enactment, applied to cessions from 2013-01-01. Confirmed by
Légifrance Art. 1609 nonies G current text + BOI-RFPI-TPVIE-20
(the smoothing-formula BOI).

**Note: the surtaxe ALSO exempts ventes de terrains à bâtir** (CGI
Art. 1609 nonies G I). The provider's `immo-component` calls
`surtaxe-1609-nonies-G` on every immobilière disposal's IR base —
including `:fr-immobilier-terrain-batir`. Building-land disposals
should be excluded from the surtaxe.

**Fix**: route only `#{:fr-immobilier-residence :fr-immobilier-autre
:fr-immobilier-spi}` through `surtaxe-1609-nonies-G`; building-land
(`:fr-immobilier-terrain-batir`) bypasses. Magnitude: a €500k
building-land gain currently gets ~€17k surtaxe added vs €0 correctly.
**P1-3.**

### 1.6 Immobilière abattement ladders — IR 6 %/y 6-21 + 4 % y22; PS 1.65 %/y 6-21 + 1.6 % y22 + 9 %/y 23-30 — CORRECT

`cgt_statute.clj:128-156` + `:270-290`. CGI Art. 150 VC; codified
since the LF 2014 reform (the long-overdue dual-ladder alignment).
The IR ladder yields 100 % at 22 y; PS yields 100 % at 30 y.
Provider's `immo-IR-abat-pct` and `immo-PS-abat-pct` reproduce
faithfully:

- IR: `(< years 6) 0M` → `(< years 22) (* (- years 5) 0.06M)` →
  `:else 1M`. At 22y: returns `1M` (the year-22 4 % final
  increment lands on the `:else 1M` branch because 22 ≥ 22).
- PS: `(< years 6) 0M` → `(< years 22) (* (- years 5) 0.0165M)` →
  `(= years 22) (+ (* 16 0.0165M) 0.016M)` = `0.28M` → `(< years 30)
  (+ … (* (- years 22) 0.09M))` → `:else 1M`.

Note 128 §2.2 worked example reproduces to the cent: 23-y secondary
residence, €270 000 gross gain → IR base 0, PS base €170 100,
PS tax €29 257.20. Confirmed by the hagnère-patrimoine source
itself + construires's separate calculation.

**Edge case**: At year 22 exactly, the IR cumulative SHOULD be 100 %
(16 × 6 % + 4 %). The provider's IR ladder hits `:else 1M` at 22
which gives 100 % correctly. The PS ladder at 22 uses the explicit
`(= years 22)` branch which computes `16 × 1.65 + 1.6 = 28 %`. ✓

### 1.7 `FR.CGT.§151-septies` thresholds (€90 k / €126 k services; €250 k / €350 k goods) from 2008-01-01 — CORRECT

`cgt_statute.clj:166-188` + `:299-314`. CGI Art. 151 septies II:
"L'exonération est :
1° Totale lorsque les recettes annuelles sont inférieures ou
égales à 250 000 € s'il s'agit d'entreprises … de vente de
marchandises … ; à 90 000 € s'il s'agit d'autres entreprises …
2° Partielle lorsque les recettes annuelles sont inférieures à
350 000 € pour les activités … et à 126 000 € pour les autres
entreprises". Stable since the LF 2008 reform; confirmed by:

- Légifrance Art. 151 septies (current text);
- impots.gouv.fr "Comment bénéficier de l'exonération PME";
- BOI-BIC-PVMV-40-10-10-30 + 40-10-10-40 — official thresholds.

**Gap (P2-2)**: the agricultural activity class with €350 k / €550 k
thresholds (note 128 §1.5 + Bpifrance) is not encoded; `§151-septies-
fraction` falls through `case activity :goods …` default to services
thresholds. A consumer passing `:activity :agricultural` with
€400k revenue gets fraction = 1 (no exemption) vs the correct band-
proportional value. Three options: (a) add two more parameters
(`FR.CGT.§151-septies.threshold-agri-full` at 350k, `-degressive` at
550k) + extend the `case` to `:agricultural`; (b) document that the
provider does not handle agricultural activity in v1 and the
consumer routes around (consumer-supplied `:151-septies-fraction`
input); (c) leave silently — fragile. **Recommendation: option (a)
when an FR agricultural consumer appears; (b) for v1.**

### 1.8 `FR.CGT.§238-quindecies` thresholds — **P0-2: €700 k / €1.2 M IS AGRICULTURAL-ONLY**

`cgt_statute.clj:191-201` + `:317-335`.

The encoding has TWO parameter-value rows per threshold: the
pre-2025 €500 k / €1 M (with `effective-until 2025-01-01`) and the
post-2025 €700 k / €1.2 M (with `effective-from 2025-01-01`). The
citation is "CGI Art. 238 quindecies — full-exemption cliff relevée à
€700 000 par LFI 2024 (exercices ouverts à compter du 1er janvier
2025)". This reading was originated in note 128 §1.5 and propagated.

**The raise to €700 k / €1.2 M IS NOT for all transmissions** —
Légifrance current text of Art. 238 quindecies VII bis confines it
to **agricultural** transmissions to young farmers (or equivalent
JA-aided entities). Standard non-agricultural business transmissions
remain at the legacy €500 k / €1 M thresholds. From Bpifrance Création
(updated June 2025, the practitioner authority cited by note 128 §6):

> "Cette mesure relève les seuils de valeur des éléments transmis
> rendant éligibles à l'exonération des plus-values
> professionnelles … réalisées à raison de la transmission d'une
> exploitation agricole, de ses branches complètes d'activité ou de
> l'intégralité des parts de la société agricole".

And the standard rule (still in force for non-agricultural):

> "L'exonération des plus-values est totale si la valeur des biens
> cédés … n'excède pas **500 000 €** … partielle si cette valeur
> est supérieure à 500 000 € et inférieure à **1 M€**".

**Impact**: a non-agricultural business transmission valued
€600 000 in FY-2026 under the current encoding returns fraction=0
(fully exempt) vs correctly fraction=(600k−500k)/(500k)=20%
(€10k of a €50k gain remains taxable). **P0** — significantly
over-exempts the most common transmission case.

**Fix sketch**. Either:
1. **Revert** `FR.CGT.§238-quindecies.threshold-full` to a stable
   €500 000 from 2006-01-01 (no second row), add a parallel
   `FR.CGT.§238-quindecies.agri-threshold-full` at €700 000 from
   2025-01-01, and have the provider pick per activity-class
   (consumer-supplied `:inputs :238-quindecies {:activity
   :agricultural}`).
2. **Keep** the dual row but ADD a `:case :agricultural` discriminator
   on the parameter (similar to OpenFisca's `cas` pattern in
   parameter-values).

Recommendation: option 1 — minimal substrate stress, mirrors the
§151 septies activity-class split. A small AskUserQuestion-worthy
design call: is the agricultural-vs-standard distinction worth
modeling generally as an additional `:activity` discriminator on
several parameters?

**Confirmed by**:
- [Légifrance Art. 238 quindecies current text](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496);
- [Bpifrance Création — exonération valeur cession](https://bpifrance-creation.fr/encyclopedie/fiscalite-lentreprise/fiscalite-transmissionreprise/lexoneration-plus-values-1) — both standard and agricultural thresholds explicit (cited by note 128 itself);
- [compta-online "Transmission d'entreprise individuelle"](https://www.compta-online.com/transmission-entreprise-individuelle-ao1461);
- BOI-BIC-PVMV-40-20-50 (the cited BOFIP) — also documents standard at €500k.

### 1.9 `FR.CGT.ProLT.IR-rate` = 0.128M from 2018-01-01 — CORRECT

`cgt_statute.clj:159-163` + `:293-296`. CGI Art. 39 quindecies (the
12.8 % IR rate on pro long-term gains, aligned with the PFU since
LF 2018). Confirmed.

**But: the PS rate for pro LT** — provider reads
`FR.CGT.PS.real-estate-rate` (17.2 %) per `cgt_provider.clj:588`.
Note 128 §5.4 acknowledges this is "debated; per practice 17.2 still
applies" — but the actual L. 136-8 IV CSS list does **not** include
pro long-term gains as a carve-out. Practitioner sources are
divided; a defensible reading is that pro LT (a BIC/BNC business
income) is "revenus du patrimoine" with PS at the patrimoine rate
(which IS hiked, see P0-1). **Recommendation: separate
`FR.CGT.ProLT.PS-rate` parameter so the legal call is explicit
rather than borrowing real-estate's number for convenience.** P2.

### 1.10 `FR.CGT.§219.QPFC-rate` = 0.12M from 2013-01-01 — CORRECT (BUT INCOMPLETE — see P1-1)

`cgt_statute.clj:204-208` + `:338-341`. CGI Art. 219 I a quinquies:
"Une quote-part de frais et charges égale à 12 %". Stable since
LF 2013. The 12 % applies to the **GROSS** gain per CE 2017-06-14
n° 400855 — the provider's `(* gross-gain qpfc-rate)` matches.
Confirmed by:

- [Légifrance Art. 219](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562);
- [BOI-IS-BASE-20-20-10-20](https://bofip.impots.gouv.fr/bofip/4948-PGP.html/identifiant=BOI-IS-BASE-20-20-10-20-20240403) (2024-04-03 revision);
- [PwC Avocats "Quote-part frais et charges"](https://www.pwcavocats.com/fr/ealertes/ealertes-france/2021/11/cession-de-titres-de-participation-imposition-quote-part-frais-charges-montant-impot-credit-impot-imputation.html);
- Compta-online "Cession de titres de participation : imposition";
- LégiFiscal "LF 2026 sécurisation des plus-values long-terme titres
  participation" (2026 update — confirms 12 % unchanged, adds a
  TRPVLT sub-account requirement that does not affect rate).

**P1-1**: the 12 % is the **standalone-entity** rate. For a target
held inside an **intégration fiscale** (consolidated tax group), CGI
Art. 223 F + 223 B drop the QPFC to **1 %** for qualifying intra-
group long-term gains on titres-de-participation, mirroring the
mère-fille 1 % addback note 124 §1.4 flagged. The `:fr-corporate-
cgt-provider` does not consult any group-membership signal. Same
fix shape as note 124 §6 P1-1 — read `:tax-unit
:intégration-fiscale?` from ctx and switch.

### 1.11 `FR.CGT.§219.holding-period-years` = 2M from 2007-01-01 — CORRECT

`cgt_statute.clj:210-214` + `:343-346`. CGI Art. 219 I a quinquies:
"le régime des plus-values à long terme … pour les titres détenus
depuis au moins **deux ans**". Stable since the participation regime
modernization. The provider's `(>= (holding-period-years %) min-years)`
correctly enforces the 2-y minimum.

### 1.12 `FR.CGT.§238.IP-box-rate` = 0.10M from 2019-01-01 — CORRECT

`cgt_statute.clj:217-221` + `:349-352`. CGI Art. 238 II: "le taux
réduit de **10 %**". LF 2019 (art. 37) lowered from 15 % to 10 %
and introduced the nexus ratio (BOI-BIC-BASE-110-30). Confirmed by
PwC Avocats IP-box page + Deloitte Avocats reform commentary.

**Note**: the provider only fires this for `:asset-class :fr-brevet`
disposals tagged `:elective-regime :fr-ip-box-238`. The régime
also covers **licensing income** (concession) not just cession —
which is a CONCESSION provider concern (note 128 §1.7), not a
disposal-substrate concern. The provider correctly scopes itself to
the disposal lane.

## §2. Provider behaviour — lane by lane

### 2.1 PFU vs barème election routing — CORRECT

`cgt_provider.clj:382-474` (`mobiliere-component`). The election is
read from `(get-in ctx [:tax-unit :pfu-or-bareme])` — keyword `:pfu`
or `:barème`. This matches note 128 §5.3's authoritative-source
rule: the tax-unit signal IS the canonical, the per-disposal
`:elective-regime :fr-barème` is paper trail only. The provider
correctly ignores the per-disposal flag here (no consistency-warn
yet — note 128 §5.3 step 3 calls for it but flags it as nice-to-have).

**Under barème** (`barème?=true`): IR base routes to
`:pit-base-additions` and standalone IR tax = 0. Test
`mobiliere-bareme-with-renforce-85pct-abatement` confirms €30k base
folds. ✓

**Under PFU** (default): IR tax = 12.8 % × ir-base. ✓

**Abattement-durée**: provider correctly applies ONLY under barème
AND only on pre-2018 acquisitions. Test
`mobiliere-bareme-no-abatement-post-2018` proves the post-2018 cliff.
Test `mobiliere-abatement-general-50pct-at-2-years` confirms the 65 %
général-bracket at 8+ years (the test's docstring says "50 % at
2 years" but the test actually exercises 8+ years with 65 %; the
docstring is misleading — P3 cosmetic).

### 2.2 Per-disposal abattement weighting — RIGHTSHAPE, EDGE CASE

`cgt_provider.clj:415-430`. The abattement is weighted per disposal
by its share of the **positive** sum: `(* post-carry-non-pea (/ g
pos-sum))` for each disposal `d` with `pos? g`. This is correct for
the **multi-disposal mobilière barème** case: a portfolio of mixed-
duration positions gets each holding's abat-pct applied to its share
of the net (post-carry).

**Edge case**: when one disposal is a large loss and another a
large gain, net might be near zero — the gain's share of pos-sum is
~100 % but post-carry-non-pea is small → small abat-amount → small
deduction. The arithmetic is right; the **economic** outcome
(loss-then-abatement vs abatement-then-loss) matches BOFIP doctrine
(BOI-RPPM-PVBMI-20-10-40 §40: abattement applies to **net** plus-
value after intra-bucket compensation — Secob source). ✓

### 2.3 Mobilière PS on GROSS gain — CORRECT

`cgt_provider.clj:436-437`: `ps-tax-non-pea = (* (max 0M
gross-net-non-pea) ps-rate-securities)`. NOT abatement-reduced.
Matches CGI Art. 150-0 D 4° + the cited Secob article (the
abattement applies to IR only; PS rides on gross). ✓ Test
`mobiliere-bareme-with-renforce-85pct-abatement` confirms €37 200
PS on €200k gross.

### 2.4 Immobilière dual-ladder math — CORRECT

`cgt_provider.clj:480-535`. Per-disposal `(IR-base, PS-base)` tuple
via separate abat ladders, then summed. ✓ Note 128 §2.2 worked
example reproduces to the cent (test `immobiliere-23y-secondary-
residence-note128-§2_2`).

**Concern (P1-3)**: building land — `:fr-immobilier-terrain-batir`
asset class is in the `immo-asset-classes` set and routes through
`immo-component`. CGI Art. 150 VC I codifies that the 6%/22y
abattement-durée applies to building land too (since LF 2014, the
"pleine application" of the dual-ladder reform). ✓ BUT: the
**surtaxe 1609 nonies G EXCLUDES terrains à bâtir** (CGI Art. 1609
nonies G I + Notaires de Paris bracket page cited in note 128 §1.4).
Provider currently applies surtaxe to ALL immobilière disposals via
`immo-component:511` — including building land. P1.

### 2.5 Surtaxe 1609 nonies G cliff approximation — CORRECT VALUE / WRONG MAGNITUDE CLAIM

`cgt_provider.clj:269-296` (`surtaxe-1609-nonies-G`). The provider
implements the **flat bracket** rates:

| Net IR gain | Rate |
|---|---|
| ≤ €50 000 | 0 % |
| €50 001 – €100 000 | 2 % |
| €100 001 – €150 000 | 3 % |
| €150 001 – €200 000 | 4 % |
| €200 001 – €250 000 | 5 % |
| > €250 000 | 6 % |

The arithmetic at bracket interior matches BOI-RFPI-TPVIE-20-20
exactly. At BRACKET EDGE the actual statute uses a **smoothing
formula** per BOI:

| Smoothing zone | Formula |
|---|---|
| 50 001 – 60 000 | `2 % PV − (60 000 − PV) × 1/20` |
| 100 001 – 110 000 | `3 % PV − (110 000 − PV) × 1/10` |
| 150 001 – 160 000 | `4 % PV − (160 000 − PV) × 15/100` |
| 200 001 – 210 000 | `5 % PV − (210 000 − PV) × 20/100` |
| 250 001 – 260 000 | `6 % PV − (260 000 − PV) × 25/100` |

(Source: BOI-RFPI-TPVIE-20-20180824 — verbatim table.)

The maximum under-statement of the flat-bracket vs the smoothed
formula is **at the start of the highest zone (€250 001)**, where
the smoothed formula returns ~€12 500 and the flat-bracket returns
€15 000.05 — over-states actually by **~€2 500 at €250 001**, then
converges to agreement at €260 000.

Provider docstring `cgt_provider.clj:280-282` says "the cliff
approximation under-states surtaxe by ≤€2 000 at the bracket edges".
**Wrong direction AND wrong magnitude**:

- The flat-bracket OVER-states (charges 6 % × full PV − rate × edge
  = €15 000.06 at PV=€250 001 vs the smoothed €12 500.25);
- Maximum magnitude is **~€2 500**, not "≤€2 000".

P2-1. Update docstring; the substantive behaviour (flat-bracket
approximation) is a documented v1 deviation per note 128 §1.4.

### 2.6 §238 quindecies fraction — math correct, parameter values wrong (per P0-2)

`cgt_provider.clj:338-361`. The linear interpolation between full
and degressive cliffs is correct. The fraction returns 0 below full,
1 above degressive, and `(value − full) / (degressive − full)` in
band. ✓ The bug is in the PARAMETER values (P0-2 §1.8), not the
provider's logic.

### 2.7 PEA exoneration semantics — CORRECT (post-LFSS 2026)

`cgt_provider.clj:402-413` + `:439-440`. PEA disposals route to
`pea-disposals`; IR is zeroed (no `*-ir-tax` line for PEA pool);
PS is computed at `ps-rate-securities` (= 18.6 % from 2026-01-01) ×
`pea-net`. ✓ Confirmed by [Meilleurtaux Placement "PEA : les
cotisations sociales passent à 18,6 % en 2026"](https://placement.meilleurtaux.com/bourse/actualites/2026-fevrier/pea-les-cotisations-sociales-passent-a-18-6-en-2026.html)
(Feb-2026 update) — PEA gains realised post-2026 take the new rate;
**legacy taux historiques** (pre-2018 PEA opens before
2013) on gains accrued through 2017-12-31 keep their historical
rates. The provider does not model taux-historiques. **Documented
deferral per note 128 §1.3.**

The 5-y holding-period check is NOT enforced by the provider — the
consumer applies `:exemption-claimed :fr-pea-exoneration` only when
the holding period passes. Could be tightened (provider could check
`(>= (holding-period-years d) 5)` for `:fr-pea*` asset-classes), but
the current "consumer flags it" is acceptable for v1.

### 2.8 Pro CT and Pro LT — CORRECT

`cgt_provider.clj:541-608`. Pro-CT folds via `:pit-base-additions`
with zero standalone liability — ✓ test `pro-court-terme-folds-into-
pit`. Pro-LT computes IR @ 12.8 % + PS @ 17.2 % (the borrowed
real-estate rate — see §1.9 above for the P2 about owning a separate
parameter). Tests for §151 septies full-exempt + degressive band +
§238 quindecies cliff all pass. ✓

### 2.9 Titres de participation + IP box — CORRECT (modulo P1-1)

`cgt_provider.clj:614-685`. The 2-y holding period gate + 12 % QPFC
fold into `:cit-base-additions` mirrors DE §8b exactly (note 113
§5.1 + the DE-CGT shipped provider). Test `titres-participation-
qpfc-12pct-note128-§2_3` reproduces note 128 §2.3 to the cent
(€8M × 12 % = €960k). ✓

The IP-box nexus-ratio weighting via `:inputs :ip-box {<external-id>
{:nexus-ratio …}}` matches OECD-conformant nexus practice. Default
nexus = 1 (no reduction). ✓

## §3. Test coverage — what's solid, what's missing

### 3.1 Solid

- Empty source → empty components (both providers). ✓
- 2026 PS rate parameter resolves correctly under each lane. ✓
- Note 128 §2.1 / §2.2 / §2.3 worked examples to the cent. ✓
- PFU vs barème routing. ✓
- Pre-2018 cliff + abat-renforcé 85 %. ✓
- Loss carry pre-abatement application. ✓
- PEA IR-exoneration. ✓
- Résidence principale skip. ✓
- §151 septies full-exempt + degressive band. ✓
- §238 quindecies cliff (correctly tests the value, even though the
  parameter value is wrong per P0-2 — the test reflects the buggy
  threshold). ✓
- Surtaxe 1609 nonies G bracket math (no smoothing-edge test). ✓
- Pro CT fold + pro LT preferential. ✓
- Titres-de-participation 2-y gate + QPFC. ✓
- Brevets IP-box + nexus weighting + no-election skip. ✓
- Void exclusion. ✓
- Bitemporal §238 quindecies. ✓
- Multi-component corporate + multi-lane personal. ✓

### 3.2 Missing — coverage gaps

The substantive ones (highest leverage):

- **Surtaxe smoothing-zone edge** — a test at PV=€55 000 or
  €105 000 would catch the cliff-vs-smoothed magnitude. Even
  without fixing the docstring, asserting the provider returns the
  cliff value (vs the smoothed value documented as deviation)
  freezes the behaviour.
- **Building-land + surtaxe** — a test of `:fr-immobilier-terrain-
  batir` confirming surtaxe is NOT applied (per CGI Art. 1609
  nonies G I) — currently the provider INCORRECTLY would charge.
  This gap is what allows P1-3 to ship undetected.
- **§238 quindecies non-agricultural at €600k** — a test that fails
  if the consumer marks the disposal as standard non-agricultural and
  expects partial taxation (currently the provider returns 0). This
  gap allows P0-2 to ship undetected.
- **2025 mobilière disposal under PFU** — a test asserting PS at
  18.6 % on a 2025-08-15 disposal would fail under the current
  encoding (returns 17.2 %). Catches P0-1.
- **Intégration fiscale QPFC at 1 %** — a test with `:tax-unit
  :intégration-fiscale? true` asserting QPFC of 1 % (note 124 §6
  P1-1 shape). Catches P1-1.
- **Loss-on-titres-participation** — a test with a negative-realized-
  gain disposal showing it gets silently dropped (and tagged with
  `:fr-mv-titres-participation` for audit). Documents the seal.
- **Combined §151 septies + §238 quindecies** — the provider's
  `exemption-fraction` prefers §238 over §151 if both flagged.
  Currently no test exercises both at once (note 128 §1.5 says they
  cannot cumulate; the provider's preference is a v1 call worth
  freezing).

The cosmetic ones (lower leverage):

- Agricultural activity `:151-septies-fraction` case.
- Étalement-3-ans (`:fr-étalement-3-ans` elective regime is in the
  closed set but provider does not handle it).
- Exit tax 167 bis (`:fr-167-bis-exit-tax` elective regime is in the
  closed set; provider does not check; consumer routes it as a
  `:kind :deemed` mobilière disposal).
- Apport-cession 150-0 B ter (the regime keyword is in the closed
  set; provider does not check rollover-deadline).

The substrate is the right surface for these to be implemented later;
they are honest deferrals.

## §4. Cross-jurisdiction patterns the FR provider validates

### 4.1 The two-provider split (corporate + personal) — proven

FR's structural split between `fr-corporate-cgt-provider` (titres-de-
participation + brevets) and `fr-personal-cgt-provider` (mobilière +
immobilière + pro-CT + pro-LT) is the same pattern note 138's
DE-CGT-baseline-review identified as healthy: the corporate provider
routes via `:cit-base-additions`; the personal provider routes via
`:pit-base-additions` for fold-into-PIT lanes and emits standalone
liabilities for flat-rate lanes (PFU). This is now the canonical
shape across DE / FR. JP / UK / CA / IT / NL when they ship should
adopt the same split.

### 4.2 The `:cit-base-additions` routing — works under three regimes now

DE §8b 5 % addback (note 113 §5.1 / DE CGT), FR §219 12 % QPFC (this
provider), and the FR mère-fille 5 % (note 124 — IS provider on
the receiving end). The pattern is: CGT provider computes the
adjustment as a BigDecimal vector, hands it to CIT/PIT via ctx; the
CIT/PIT provider's `apply-adjustments` layer picks it up. The
substrate doesn't care which jurisdiction; the consumer wires the
ctx. ✓ Composition mirror of `kontor.sole-proprietor` ADR-100.

### 4.3 The "five overlapping regimes" pressure on the substrate

FR is the high-water-mark for regime count; the substrate handled it
with only enumerant additions (note 128 §4 — no schema change). This
validates the ADR-102 substrate as fit for arbitrarily many
jurisdictions. Future per-country CGT providers (PT, ES, NL, IT) can
expect a similar substrate-free addition path.

### 4.4 Provisions-empty pattern — confirms `kontor.statute` is
not load-bearing for FR-CGT

`cgt_statute.clj:358-376` documents the rationale for `provisions
[]`. The provider routes natively — too many cross-disposal facts
to express as single-pass `:provision` data (loss bucketing, IR-vs-
PS asymmetric ladders, tax-unit-level election). This is the
**provider-internal lane** of the ADR-101 substrate; the parameter
layer is statute-as-data, the provisions layer is optional. ✓ Pattern
note 128 §5.4 articulated.

This is fine. Not every jurisdiction needs provisions. DE-CIT (note
120) and FR-CIT (note 124) use them heavily; FR-CGT (this) doesn't
need them because the algebra is per-disposal flat, not per-base-
slice adjustment.

## §5. Authority-citation hygiene

Spot-check of `:parameter/concept-iri` URLs (P2-5):

- `LEGIARTI000045760882` (PFU IR rate) — resolves. ✓
- `LEGIARTI000006740051` (PS default rate) — points at L. 136-7
  CSS, the right area. ✓
- `hagnère-patrimoine.fr/.../csg-crds-prelevements-sociaux-2026`
  (PS real-estate rate) — **practitioner blog**, not authority.
  Should be the LFSS 2026 text on Légifrance + BOI-RPPM-RCM-30-20-40.
  P2.
- `LEGIARTI000006304108` (Immo IR rate) — resolves. ✓
- `LEGIARTI000027577763` (Surtaxe floor) — resolves. ✓
- BOI URLs (abat ladders, immo ladders) — all resolve. ✓
- `LEGIARTI000036591469` (§151 septies thresholds) — resolves. ✓
- `LEGIARTI000051216496` (§238 quindecies) — resolves; **the article
  text however reveals the agricultural-only carve-out (P0-2).** ✓
- `LEGIARTI000046868562` (§219 — three uses) — resolves. ✓
- `LEGIARTI000037946060` (§238 IP-box) — resolves. ✓

Citation strings on parameter-values are generally good (give the
loi + year + bgbl-equivalent). The §238 quindecies post-2025 row
("LFI 2024 (exercices ouverts à compter du 1er janvier 2025)")
captures the right effective date but is **wrong about scope** —
should add "AGRICULTURAL transmissions uniquement (CGI Art. 238
quindecies VII bis ; non applicable aux cessions standard)".

## §6. Triaged findings

### P0 — ship-blockers; fix BEFORE consumer adoption

**P0-1** — `cgt_statute.clj:242-250`: `FR.CGT.PS.default-rate`
effective-from for the 18.6 % step should be **2025-01-01** for
plus-values mobilières (revenus du patrimoine, retroactive per
LFSS 2026); the **2026-01-01** is correct for revenus de placement
(dividendes/intérêts). Cleanest fix: split into two parameters
(`patrimoine-rate` from 2025-01-01 + `placement-rate` from
2026-01-01); provider picks per lane. Or: back-date the single rate
to 2025-01-01 and rely on the immobilière carve-out (separate
parameter already exists). See §1.2.

**P0-2** — `cgt_statute.clj:322-335`: `FR.CGT.§238-quindecies.
threshold-{full,degressive}` post-2025 raise to €700 k / €1.2 M is
reserved to AGRICULTURAL transmissions. Standard non-agricultural
transmissions stay at €500 k / €1 M. Fix: revert the universal raise,
add a parallel `agri-threshold-{full,degressive}` at €700 k / €1.2 M
from 2025-01-01, provider picks per consumer-supplied
`:inputs :238-quindecies {:activity :agricultural | :standard}`.
See §1.8.

### P1 — fix before the next FR consumer's reporting period

**P1-1** — `cgt_provider.clj:614-645`
(`titres-participation-component`): does not handle the **intégration
fiscale 1 % QPFC** override (CGI Art. 223 F + 223 B). Read `:tax-unit
:intégration-fiscale?` from ctx; switch the QPFC rate accordingly.
Mirror fix shape with note 124 §6 P1-1 (mère-fille 1 % override).

**P1-2** — `cgt_provider.clj:205-237` (`abat-mobiliere-pct`): the
**< 10y-old issuer-PME-at-acquisition** check for abat renforcé
1 quater is delegated to consumer ("the consumer either tags the
asset-class appropriately or doesn't claim"). The hand-off is
silent. Add: a `:disposal/notes`-checkable warning, OR require an
explicit `:audit-doc` of category `:pme-eligibility-attestation`
when `:fr-titres-pme` + `:fr-abattement-durée` are both flagged,
OR require a consumer-supplied `:inputs :pme-eligibility
{<external-id> {:age-at-acquisition <years>}}` and check `< 10`.
The audit-doc route is cleanest (mirrors the My-Number PII pattern
ADR-084 used for JP).

**P1-3** — `cgt_provider.clj:496-535` (`immo-component`): applies
surtaxe 1609 nonies G to **all** immobilière disposals including
building land (`:fr-immobilier-terrain-batir`). CGI Art. 1609 nonies
G I explicitly excludes terrains à bâtir. Fix: filter the
`disposals` going into `surtaxe-1609-nonies-G` to
`#{:fr-immobilier-residence :fr-immobilier-autre :fr-immobilier-spi}`
only.

### P2 — fix when convenient

**P2-1** — `cgt_provider.clj:279-282`: docstring claim of "≤€2 000"
under-statement is wrong. Maximum is **~€2 500** at the €250 001 –
€260 000 transition; direction is OVER-statement (not under) at the
start of each smoothing zone. Fix the docstring; v1 cliff
approximation behaviour can stand (note 128 §1.4 acknowledges it).

**P2-2** — `cgt_provider.clj:317-322`: `case activity` defaults to
`:services` thresholds for any non-`:goods` activity. A consumer
passing `:agricultural` silently gets the services thresholds. Fix:
add an explicit `:agricultural` branch with €350 k / €550 k
thresholds + two more parameters, OR throw on unrecognized activity.

**P2-3** — `cgt_provider.clj:588`: pro LT borrows
`FR.CGT.PS.real-estate-rate`. Should be its own
`FR.CGT.ProLT.PS-rate` parameter — the legal call (pro LT included
in or excluded from the LFSS 2026 hike) is implicit by reuse.
Encoding the choice explicitly removes the ambiguity.

**P2-4** — `cgt_provider.clj:164-170` (`whole-years-between`): the
`/ 365.25` averaging may differ from BOFIP's strict calendar-year
arithmetic by 1 year at near-anniversary edges. Note 113 §3.1
documented the same trade-off for DE-CGT; left as v1.

**P2-5** — `cgt_statute.clj:111` (PS real-estate-rate concept IRI):
points at hagnère-patrimoine.fr practitioner blog. Should be a
Légifrance or BOFIP URL. Audit-trail aging risk.

### P3 — note for the next revision

**P3-1** — `cgt_provider.clj:111-118`: `loss-buckets` includes
`:fr-mv-titres-participation` but no code path actually queries it
(losses on titres-participation are silently dropped via `(pos?
(realized-gain %))` filter in
`titres-participation-component`). Acceptable since they're sealed;
docstring could be clearer that the bucket is consumer-tag-only for
analytics.

**P3-2** — `cgt_provider.clj:73-82` (provider docstring): per-vintage
loss-carry tracking is acknowledged as deferred. Future enhancement:
provider sorts vintages oldest-first when offsetting, drops vintages
past their 10-y window at period close. Currently consumer self-
manages.

**P3-3** — Test `mobiliere-abatement-general-50pct-at-2-years`
docstring says "50 % at 2y held" but the test sets
`:acquired-on #inst "2015-06-01"` (10.9y held) and asserts the 65 %
général-bracket. The test is correct; the docstring is misleading.

## §7. Summary

The FR CGT provider lands the most complex CGT regime in the
cross-jurisdiction set on the ADR-102 disposal substrate + ADR-101
statute-as-data with the right shape and to-the-cent precision on
the three published worked examples. The 19-test surface covers each
of the five lanes plus PFU/barème routing, PEA exoneration, surtaxe
brackets, §151 septies + §238 quindecies exemptions, intégration
multi-component composition, and bitemporal cliff swaps.

The **two P0s are statute-fidelity bugs** that arose from note 128's
own readings — not implementation errors per se, but design
specifications that didn't survive a fresh check against Légifrance
+ Bpifrance:

- The LFSS 2026 PS hike is **2025-retroactive for mobilière gains**,
  not 2026-forward. (P0-1.)
- The §238 quindecies €700 k / €1.2 M raise is **agricultural only**,
  not universal. (P0-2.)

The **two P1s are real edge cases** in production CGT computation:

- Intégration fiscale lowers QPFC to 1 % — silent in v1. (P1-1.)
- Abattement renforcé eligibility hand-off to consumer is unguarded
  by audit-doc or input — silent in v1. (P1-2.)

Plus the surtaxe-on-terrains-à-bâtir mis-application (P1-3, would
over-charge by up to €30k on a €500k land sale).

The substrate stayed quiet — no kernel changes needed. The five-
lane discrimination, the `:cit-base-additions` routing, the
provisions-empty pattern, all confirm that ADR-103 was designed at
the right level of abstraction.

**Recommended next actions** (in order):

1. **Fix P0-1** (PS rate effective-from for mobilière) — single-row
   edit + provider lane test. < 1 hour.
2. **Fix P0-2** (§238 quindecies agricultural-vs-standard split) —
   two new parameter rows + `:inputs :238-quindecies :activity`
   discriminator + test for standard non-agri at €600k. ~2 hours.
3. **Fix P1-3** (surtaxe on building land) — one-line filter +
   test. < 30 minutes.
4. **Fix P1-1** (intégration fiscale 1 % QPFC) — read tax-unit
   signal + branch + test. ~1 hour. Mirror with note 124 §6
   mère-fille fix to avoid drift.
5. **Triage P1-2 + P2 items** into a followup research note or roll
   into the next l10n-fr stage.

The pattern this review confirms — **"every l10n update against fresh
authority reading"** — has now caught real bugs in FR CIT (note 124),
FR CGT (this), and would have caught the note-128 misreadings before
they shipped if it had run BEFORE the implementation rather than after.
For FR specifically the answer is yes: the next FR consumer (PFU
+ immobilière reporting period 2026) needs P0-1 and P0-2 fixed.

---

## §8. Sources (audit-trail seed)

**Légifrance (CGI articles consulted)**:
- [Art. 150-0 A](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051218042) — plus-values mobilières charging article
- [Art. 150-0 D](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051218045) — abattements + MV-mobilières offset
- [Art. 150 U](https://www.legifrance.gouv.fr/codes/id/LEGISCTA000006197216/) — plus-values immobilières
- [Art. 151 septies](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469) — exonération petites entreprises
- [Art. 167 bis](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806379) — exit tax
- [Art. 200 A](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000045760882) — PFU + barème
- [Art. 219](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562) — IS + titres de participation (12 % QPFC)
- [Art. 238 quindecies](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496) — **the agri-vs-standard split that grounds P0-2**
- [Art. 1609 nonies G](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806252) — surtaxe immobilière

**BOFiP-Impôts**:
- [BOI-RFPI-TPVIE-20 — modalités surtaxe 1609 nonies G](https://bofip.impots.gouv.fr/bofip/8597-PGP.html/identifiant=BOI-RFPI-TPVIE-20-20180824) — **the smoothing formula source that grounds P2-1**
- [BOI-RFPI-PVI-20-20](https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RFPI-PVI-20-20-20250410) — abattement-durée immobilière ladders
- [BOI-RPPM-PVBMI-20-30-10](https://bofip.impots.gouv.fr/bofip/9541-PGP.html/identifiant=BOI-RPPM-PVBMI-20-30-10-20191220) — abat renforcé 1 quater 9-point checklist (grounds P1-2)
- [BOI-IS-BASE-20-20-10-20](https://bofip.impots.gouv.fr/bofip/4948-PGP.html/identifiant=BOI-IS-BASE-20-20-10-20-20240403) — titres de participation modalités (2024-04 revision)
- [BOI-BIC-BASE-110-30](https://bofip.impots.gouv.fr/bofip/11729-PGP.html/identifiant=BOI-BIC-BASE-110-30-20200422) — IP-box brevets

**Practitioner / authority commentary**:
- [DLA Piper "LFSS 2026 hausse CSG"](https://www.dlapiper.com/fr-fr/insights/publications/2026/01/loi-de-finance-de-la-securite-sociale-2026) — **grounds P0-1 effective-date split**
- [Actu-Juridique "CSG en hausse sur les revenus du capital"](https://www.actu-juridique.fr/fiscalite/fiscal-finances/la-csg-en-hausse-sur-les-revenus-du-capital/) — supports P0-1
- [TGS France "Revenus du capital : le taux de la CSG augmente"](https://www.tgs-france.fr/blog/revenus-du-capital-le-taux-de-la-csg-augmente/) — supports P0-1, explicit "rétroactive 2025"
- [Bpifrance Création — exonération valeur cession](https://bpifrance-creation.fr/encyclopedie/fiscalite-lentreprise/fiscalite-transmissionreprise/lexoneration-plus-values-1) — **grounds P0-2 agri vs standard**
- [compta-online — Transmission entreprise individuelle](https://www.compta-online.com/transmission-entreprise-individuelle-ao1461) — supports P0-2
- [Meilleurtaux Placement "PEA cotisations sociales 18,6 % 2026"](https://placement.meilleurtaux.com/bourse/actualites/2026-fevrier/pea-les-cotisations-sociales-passent-a-18-6-en-2026.html) — confirms PEA at 18.6 % from 2026-01-01
- [PwC Avocats "Quote-part frais et charges 12 %"](https://www.pwcavocats.com/fr/ealertes/ealertes-france/2021/11/cession-de-titres-de-participation-imposition-quote-part-frais-charges-montant-impot-credit-impot-imputation.html)
- [LégiFiscal "LF 2026 sécurisation plus-values long-terme titres participation"](https://www.legifiscal.fr/actualites-fiscales/4435-loi-finances-2026-securisation-values-long-terme-titres-participation.html) — confirms 12 % QPFC unchanged
- [LégiFiscal "LF 2026 durcissement apport-cession"](https://www.legifiscal.fr/actualites-fiscales/4421-loi-finances-2026-durcissement-dispositif-apport-cession.html) — confirms 60 → 70 % reinvestment quota (P2-3)
- [LCL "Déclaration des plus-values de cession de titres perçues en 2025"](https://www.lcl.fr/mag/fiscalite/impots-declaration-plus-values-cession-de-titres) — confirms 2025-disposal under PFU 31.4 %
- [hagnère-patrimoine "Plus-value immobilière 2026"](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/plus-value-immobiliere-2026) — confirms 23-y worked example
- [construires "Plus-value immobilière 2026 36.2 %"](https://www.construires.fr/plus-value-immobiliere/)
- [Ramify "Flat tax 2026"](https://www.ramify.fr/gestion-de-patrimoine/flat-tax)
- [Meilleurescpi "PFU 31,4 % le guide ultime"](https://www.meilleurescpi.com/conseils/flat-tax-2026-le-guide-ultime-du-prelevement-forfaitaire-unique-pfu/)
- [Secob "Abattement pour durée de détention ne s'applique pas aux moins-values"](https://www.secob.fr/actualites/fiscalite/abattement-pour-duree-de-detention-il-ne-s-applique-pas-aux-moins-values)
- [Notaires de Paris "Surtaxe plus-values immobilières"](https://paris.notaires.fr/fr/actualites/surtaxe-sur-les-plus-values-immobilieres)
- [Auguste Patrimoine "Exit tax France"](https://www.auguste-patrimoine.fr/gestion-patrimoniale/exit-tax)

**kontor substrate cited (file:line)**:
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_statute.clj:88-352` — parameters + values reviewed
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_provider.clj:155-714` — provider implementation
- `modules/l10n-fr/test/kontor/l10n_fr/cgt_provider_test.clj:1-623` — 19 deftests
- `modules/disposal/src/kontor/disposal/schema.clj:62-321` — disposal substrate the provider builds on
- `modules/disposal/src/kontor/disposal/source.clj:60-94` — DisposalSource impl
- `src/kontor/statute.clj:150-181` — `parameter-value-at` resolver
- `src/kontor/period_tax_provider.clj` — PeriodTaxProvider protocol
- `doc/research/128-fr-cgt-fit.md` — research-before note (where P0s originated)
- `doc/research/124-fr-cit-baseline-review.md` — sibling baseline review, P1-1 parallel finding
- `doc/research/113-de-cgt-fit.md` + sibling DE-CGT-baseline-review (note 138 if shipped) — cross-jurisdiction pattern
- `doc/research/138-de-cgt-baseline-review.md` (sibling) — the canonical baseline-review shape

---

End of note 141.

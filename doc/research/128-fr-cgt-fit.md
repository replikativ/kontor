---
date: 2026-05-24
title: 128 — FR capital-gains tax — substrate fit against the `kontor-disposal` schema
status: research-before for the Phase 3 `fr-cgt-provider`(s); no code; data-gap list at §4
audience: maintainer + the FR-CGT implementation agent
---

# 128 — FR capital-gains tax: substrate fit for the `:disposal` companion

This note answers ONE question: does the shipped `:disposal` schema
(`modules/disposal/src/kontor/disposal/schema.clj`, ADR-102) carry
enough data to drive a faithful FR capital-gains-tax provider across
the **five** statutory shapes FR uses (PFU / barème individuals; plus-
values mobilières §150-0 A; plus-values immobilières §150 U; plus-
values professionnelles §39 duodecies; IS-side titres de participation
§219; brevets §238) — or do we need to extend it?

Bottom line: **the schema is largely sufficient — three additive enum
values + one provider-side convention close the gap.** The `:disposal`
schema as shipped (post-notes-112-115) already carries `:acquired-on`
+ `:disposed-on` instants (the right primitive for FR's per-asset-class
abattement-pour-durée-de-détention math), `:elective-regime`
cardinality-many (the right shape for FR's two-track PFU/barème
election plus the apport-cession overlay), `:exemption-claimed`
(PEA, residence principale, §151 septies, §238 quindecies, abattement
durée), `:loss-bucket` (four FR-specific buckets), and
`:ownership-fraction` (read by exit tax §167 bis). The gaps are: FR-
specific keyword enumerants on the existing slots + a per-bucket loss-
carry input shape on the PIT/IS provider + one new
`:disposal/asset-class` value for the corporate `titres de
participation` regime that overlaps but is distinct from individual
`:participation`.

The headline FR feature the substrate handles cleanly without stress:
the **two-track PFU vs barème** election rides `:elective-regime`,
the per-disposal abattements-pour-durée-de-détention rides the
provider's `:acquired-on`-driven math on the BARÈME-elected pool,
and the social-charges layer (CSG/CRDS/prélèvement de solidarité —
**18.6 % on securities since 2026-01-01**, but **still 17.2 % on real
estate, life insurance, and rente foncière**) rides
`apply-adjustments` as a per-component surtax.

---

## §1. The FR CGT regime — five statutory shapes

FR does not have ONE capital-gains tax. It has FIVE overlapping
regimes, with distinct base, rate, holding-period, and loss-offset
rules — and the asset/holder pair determines which one fires:

### 1.1 PFU vs barème — the individual-side two-track (CGI art. 200 A)

Since 2018 the default treatment for an individual's investment
income (interest, dividends, securities capital gains, crypto, certain
life-insurance withdrawals) is the **Prélèvement Forfaitaire Unique**
— a single flat-tax envelope at:

- **2018-2025 era**: **30 % total** = 12.8 % IR + 17.2 % prélèvements
  sociaux.
- **2026-01-01 onwards (LFSS 2026, loi 2025-1403)**: **31.4 % total**
  = 12.8 % IR + **18.6 % PS** (CSG raised from 9.2 % to 10.6 %; CRDS
  0.5 % + prélèvement de solidarité 7.5 % unchanged) ([hagnère-
  patrimoine — taux 2026](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026)).

The **barème progressif** election (art. 200 A, 2 — "option pour
l'imposition au barème") is irrevocable for the tax year, applies to
ALL of the household's PFU-eligible income (no cherry-picking by
disposal), and folds the income into the personal-income-tax brackets
(0 / 11 / 30 / 41 / 45 %) on top of the same 17.2 / 18.6 % social
layer. The election is rational when the marginal IR rate is below
12.8 % AND/OR when an abattement-pour-durée-de-détention (only
available under the barème — see §1.2) reduces the taxable base enough
to offset the higher rate.

**Critical exclusions from the 18.6 % rate** ([hagnère-patrimoine —
taux 2026 by category](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026)):
- **Real-estate capital gains** (PV immobilières) — PS stay at **17.2
  %** → effective rate **36.2 %** total (19 % IR + 17.2 % PS).
- **Life insurance** (assurance-vie) — PS stay at 17.2 %.
- **PEL / CEL / PEP** — PS stay at 17.2 %.
- **Revenus fonciers** (bare-property rental) — PS stay at 17.2 %.

Substrate consequence: the FR PFU provider must carry a per-asset-
class PS rate, not a single global rate. The `:disposal/asset-class`
discriminator is the natural locus.

### 1.2 Plus-values mobilières (securities) — CGI art. 150-0 A

Default regime for an individual's listed/unlisted share or share-
right disposal (outside PEA, outside §150-0 B-ter rollover):

- **Without barème election** → PFU 30 % / 31.4 %, **no abattement-
  durée available**. Realised gain = sale price − adjusted basis −
  related transaction costs. Reported on declaration 2074 +
  carried into the 2042 main IR return.
- **With barème election** → IR at marginal rate + PS, AND the
  taxpayer may apply the **abattement pour durée de détention**
  defined in art. 150-0 D, 1 ter (général) or 1 quater (renforcé) —
  but **only for shares acquired before 2018-01-01**. Post-2018
  acquisitions carry no abattement-durée whether or not the
  barème is elected.

The **abattement général** (art. 150-0 D, 1 ter) — applies to most
qualifying pre-2018 share holdings:

| Holding period at disposal | Abatement of net gain |
|---|---|
| < 2 years | 0 % |
| ≥ 2 years and < 8 years | **50 %** |
| ≥ 8 years | **65 %** |

The **abattement renforcé** (art. 150-0 D, 1 quater) — applies to
shares in a **PME under 10 years old at acquisition**:

| Holding period at disposal | Reinforced abatement |
|---|---|
| ≥ 1 year and < 4 years | **50 %** |
| ≥ 4 years and < 8 years | **65 %** |
| ≥ 8 years | **85 %** |

Eligibility conditions for the reinforced abatement
([BOI-RPPM-PVBMI-20-30-10](https://bofip.impots.gouv.fr/bofip/9541-PGP.html/identifiant=BOI-RPPM-PVBMI-20-30-10-20191220)):

- Shares acquired/subscribed **before 2018-01-01** (cliff).
- Issuer is a PME per the EU definition (< 250 employees AND
  (turnover ≤ €50 M OR balance sheet ≤ €43 M)).
- Issuer is **less than 10 years old at the date of acquisition**
  by the taxpayer.
- Issuer is established in the EEE and has signed administrative
  cooperation agreements with FR.
- Issuer's activity is commercial, industrial, artisanal, liberal or
  agricultural (NOT passive patrimoine management).
- Issuer subject to corporate income tax (or equivalent).
- Issuer does not guarantee capital to its shareholders.
- Taxpayer **elected the barème** (the abatement requires barème
  taxation under art. 200 A, 2).

The abattements apply to **net** gain (after offsetting moins-values
mobilières — see §1.6) and **do NOT apply to moins-values themselves**
([Secob — abattement pour durée de détention ne s'applique pas aux
moins-values](https://www.secob.fr/actualites/fiscalite/abattement-pour-duree-de-detention-il-ne-s-applique-pas-aux-moins-values)).
This asymmetry has substrate consequences: the provider must compute
the net gain FIRST, then apply the abattement to the positive net
ONLY.

### 1.3 PEA / PEA-PME — full exemption after 5 years (CGI art. 157 5°)

The **Plan d'Épargne en Actions** is a per-individual securities
envelope (cap €150 000, cumulative cap with PEA-PME €225 000) holding
EU equities. Gains realised *inside* the envelope are not currently
taxed; on a withdrawal:

- **Before 5 years** — withdrawal triggers full taxation of the gain
  at PFU 31.4 % AND closes the plan (with certain hardship
  exceptions).
- **After 5 years** — withdrawals are **exonérées d'IR** entirely; only
  the social-charges layer applies (PEA gains acquired post-2026 are
  subject to **18.6 %** PS per LFSS 2026; gains earned by historical
  pre-2026 acquisitions inside the PEA stay at their historical PS
  rates per the "taux historiques" doctrine for legacy plans —
  documented separately, complex).

PEA-PME — same mechanics, narrower investment universe (issuers with
market cap < €2 B, < 5 000 employees, ≤ €1.5 B turnover OR ≤ €2 B
balance sheet).

Substrate consequence: a `:disposal` representing a PEA withdrawal-
of-gain after 5 years carries `:exemption-claimed #{:fr-pea-exoneration}`
+ `:elective-regime` empty (the exemption is statutory, not elected
per-disposal); the FR CGT provider zeroes the IR component but
still emits the PS surcharge.

### 1.4 Plus-values immobilières — CGI art. 150 U

A separate regime entirely from the mobilière track. Applies to
individuals (and SCIs not subject to IS) disposing of real estate.

**Headline rates**:
- **IR**: 19 % flat (art. 200 B) on the taxable gain.
- **PS**: 17.2 % (NOT subject to the LFSS 2026 increase).
- **Surtaxe** (art. 1609 nonies G): **progressive 2-6 %** on the slice
  of taxable gain above €50 000. Applies to residential and other
  built property; **EXCLUDED** for building land (terrains à bâtir),
  the principal residence (which is fully exempt anyway), and
  properties held > 30 years (already fully exempt) ([paris
  notaires — surtaxe](https://paris.notaires.fr/fr/actualites/surtaxe-sur-les-plus-values-immobilieres)).

**Surtaxe bracket table** (CGI art. 1609 nonies G; transition formulas
in the €X 000-€(X+10) 000 bands smooth the jumps):

| Net taxable gain | Surtaxe rate |
|---|---|
| ≤ €50 000 | 0 % |
| €50 001 – €100 000 | 2 % |
| €100 001 – €150 000 | 3 % |
| €150 001 – €200 000 | 4 % |
| €200 001 – €250 000 | 5 % |
| > €250 000 | 6 % |

**Abattement pour durée de détention** — two separate ladders, one
for IR and one for PS:

For IR (19 %):
- Years 1-5: 0 %
- Years 6-21: **6 % per year**
- Year 22: **4 %** (the final increment)
- → 22 years held → **fully exempt from IR**.

For PS (17.2 %):
- Years 1-5: 0 %
- Years 6-21: **1.65 % per year**
- Year 22: **1.6 %**
- Years 23-30: **9 % per year**
- → 30 years held → **fully exempt from PS**.

Effective regime per holding-period at disposal:

| Years held | IR base remaining | PS base remaining |
|---|---|---|
| ≤ 5 | 100 % | 100 % |
| 6 | 94 % | 98.35 % |
| 22 | 0 % | 84.5 % (approx) |
| 30 | 0 % | 0 % |

The math is straightforward arithmetic on `:disposed-on - :acquired-
on` per asset-class rule — exactly the case `:disposal/acquired-on`
+ `:disposal/disposed-on` was added for in note 113.

**Headline exemptions** (art. 150 U II, partial list):
- **Résidence principale** at date of sale — totally exempt
  (`:residence?` on disposal + `:exemption-claimed
  :fr-residence-principale`).
- **First-time non-RP sale** if proceeds reinvested in primary
  residence within 24 months.
- **Sale ≤ €15 000** — exempt.
- **Held > 30 years** — exempt by virtue of full abatement ladder.
- **Sales to social-housing organisms** — partial / total exemption
  per timing.
- **Retirees in modest revenue brackets**, certain disabled persons —
  exempt.

### 1.5 Plus-values professionnelles (business assets, sole-prop) — CGI art. 39 duodecies + sequel

For a `BIC` / `BNC` sole proprietor or partnership (entity NOT subject
to IS) selling a business asset, FR splits the gain into two
buckets:

- **Court terme** (short-term, art. 39 duodecies, 2):
  - Asset held **< 2 years** → entire gain is short-term.
  - Asset held **≥ 2 years** AND it was depreciable → gain is short-
    term up to the **cumulative depreciation taken** (depreciation
    recapture), long-term on the excess.
  - → Taxed as **ordinary business income** at marginal rate (BIC /
    BNC / agricultural income).
- **Long terme** (long-term, art. 39 quindecies):
  - Asset held ≥ 2 years AND non-depreciable; OR the > deprec excess
    on a held-≥-2-years depreciable.
  - → Taxed at preferential **12.8 % IR + 17.2 % PS = 30 %** (the
    "flat tax" inside the BIC/BNC base) — and the **gain may be
    spread over 3 years** (étalement) for cash-flow relief.

**Two exemptions** are headline-load-bearing:

**§ 151 septies — petites entreprises (revenue-tested)**:

| Activity | Total exemption | Degressive band |
|---|---|---|
| Sales of merchandise / lodging | ≤ €250 000 turnover | €250 001–€350 000 |
| Other services (BIC/BNC) | ≤ €90 000 turnover | €90 001–€126 000 |
| Agricultural | ≤ €350 000 turnover | €350 001–€550 000 |

Conditions: 5 years' operation; activity exercised professionally;
applies to the assets sold (not all the firm's assets unless an art.
238 quindecies disposal — see below) ([impots.gouv.fr — comment
bénéficier de l'exonération PME](https://www.impots.gouv.fr/professionnel/questions/comment-beneficier-de-lexoneration-des-plus-values-reservees-aux-petites)).

**§ 238 quindecies — transmission d'entreprise (value-tested)**:

| Value of transmitted business | Exemption |
|---|---|
| ≤ €500 000 (pre-2025 thresholds; **raised to €700 000** for fiscal years opening from 2025-01-01) | **Total** |
| €500 001 – €1 000 000 (pre-2025) / **€700 001 – €1 200 000** (post-2025) | **Degressive** |
| > €1 000 000 / €1 200 000 | **None** |

Conditions: activity exercised for ≥ 5 years; commercial, industrial,
artisanal, liberal or agricultural; transmission of a complete branch
of activity OR an individual business OR (by assimilation) the
entirety of partnership shares treated as professional assets
([BOI-BIC-PVMV-40-20-50](https://bofip.impots.gouv.fr/bofip/6156-PGP.html/identifiant=BOI-BIC-PVMV-40-20-50-20220511);
[lecoindesentrepreneurs — 238 quindecies](https://www.lecoindesentrepreneurs.fr/exoneration-transmission-dentreprise-238-quindecies/)).

§ 151 septies and § 238 quindecies **CANNOT** be cumulated on the
same disposal; the taxpayer elects. Substrate: `:elective-regime`
cardinality-many keyword set carries the chosen one.

### 1.6 Plus-values des sociétés (IS-side titres de participation) — CGI art. 219, I a quinquies

When a corporation (SAS, SARL, SA, SE subject to IS) sells a
**qualifying participation** held ≥ 2 years, the gain is **exonérée**
at the IS layer — but a **quote-part de frais et charges (QPFC)** of
**12 %** of the **gross** gain is reintegrated into taxable income and
taxed at the **standard IS rate (25 %, or 15 % PME if eligible)**
([BOI-IS-BASE-20-20-10-20](https://bofip.impots.gouv.fr/bofip/4948-PGP.html/identifiant=BOI-IS-BASE-20-20-10-20-20240403);
[Légifrance art. 219](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562)).

This is the FR equivalent of DE § 8b KStG's 95/5 split, with two
differences:
- The QPFC is **12 %** (not 5 %), so the effective rate is **3 %**
  (12 % × 25 %), not DE's ≈ 1.5 %.
- The QPFC base is the **gross** gain (Conseil d'État,
  decision 2017-06-14 n° 400855), confirmed by jurisprudence.

A **moins-value** on titres de participation is **non-deductible** —
the symmetry. The bucket is sealed: long-term-titres-participation
gains and losses net inside this compartment ONLY.

**Special sub-cases**:
- Sale of titres in real-estate companies (>50 % real-estate by asset)
  → taxed at standard IS 25 % (no exemption, art. 219, I a sexies-0).
- Sale of titres in a société à prépondérance immobilière (SPI) listed
  → 19 % long-term rate.

The CIT provider integration is identical to DE § 8b: the FR CGT
provider emits a base-side adjustment via `:cit-base-additions` (the
QPFC) and zeroes the IR-side liability for the gross gain itself.

### 1.7 Brevets / IP box (CGI art. 238)

The optional **régime IP box** for patents, supplementary protection
certificates, software protected by copyright, plant variety
certificates and certain manufacturing processes — net result of
**cession** (sale) or **concession** (licensing) of the eligible asset
is taxed at a **preferential 10 %** corporate rate, weighted by the
**nexus ratio** (R&D performed by the company itself in France ÷ total
R&D on the asset) ([BOI-BIC-BASE-110-30](https://bofip.impots.gouv.fr/bofip/11729-PGP.html/identifiant=BOI-BIC-BASE-110-30-20200422);
[L'expert-comptable — IP box](https://www.l-expert-comptable.com/a/532029-la-fiscalite-de-la-propriete-industrielle.html)).

The election is annual, asset-by-asset (or by family-of-related-
assets). Substrate carries it cleanly on the `:disposal` for a patent
sale: `:asset-class :fr-brevet`, `:elective-regime
#{:fr-ip-box-238}`. The FR CGT provider emits a 10 % component on
the qualifying net result.

### 1.8 Loss buckets — four-bucket wall on the individual side

FR has **four separate loss compartments** that do NOT mix between
each other (and a fifth at the IS-side titres-de-participation
sub-bucket — sealed):

- **Moins-values mobilières** (art. 150-0 D, 11°) — offset only
  against plus-values mobilières of the same year; unused excess
  carries forward **10 years** (the canonical "MV / PV mobilières
  10-year carry"). Offset is **before** the abattement-durée
  application ([BOI-RPPM-PVBMI-20-10-40](https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RPPM-PVBMI-20-10-40-20250410)).
- **Moins-values immobilières** — **generally NOT deductible** except
  in a narrow set of cases (e.g. compensation of plus-values from
  multiple lots of the same operation). No carryforward. This is
  the cruellest bucket: real-estate losses essentially vanish.
- **Moins-values pro court-terme** — offset against ordinary BIC/BNC
  business income within the year; if excess loss, becomes a
  business-loss carryforward (general LBI rules, not a CGT carry).
- **Moins-values pro long-terme** — offset only against plus-values
  pro long-terme; carryforward **10 years** within the bucket.
- **IS-side titres-de-participation MV** — non-deductible (sealed
  bucket per §1.6).

Substrate consequence: the PIT/IS provider's `:inputs
:capital-loss-carryforward` carries a per-bucket map:
`{:fr-mv-mobilière (Money …) :fr-mv-pro-long (Money …)}`. The other
buckets are not carry-bearing.

### 1.9 Elective deferral / rollover regimes

Three distinct mechanisms:

**Sursis d'imposition (art. 150-0 B) — share-for-share exchange,
automatic**:
Applies when an individual contributes shares to a corporation subject
to IS as part of an OPE (public exchange offer), merger, spin-off,
or contribution to a non-controlled corporation. The exchange is
**deemed transparent** — no gain is realised, no tax is due, AND
the new shares inherit the basis + acquisition date of the old. The
deferral is **automatic** (no election). The 10 %-of-par soulte rule
caps cash consideration ([BOI-RPPM-PVBMI-30-10-20-10](https://bofip.impots.gouv.fr/bofip/12042-PGP.html/identifiant=BOI-RPPM-PVBMI-30-10-20-10-20191220)).

**Report d'imposition (art. 150-0 B ter) — apport-cession to a
CONTROLLED holding**:
When the contributor controls (directly/indirectly > 50 % voting,
or in concert) the recipient holding, sursis does NOT fire — the
gain is computed at the contribution date and **placed in report**
(deferral). Conditions ([Légifrance art. 150-0 B ter](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806695)):

- Recipient is a corporation subject to IS, seated in France / EU /
  treaty-jurisdiction.
- Contributor controls (≥ 33.33 % presumption; > 50 % voting; or
  effective decision power).
- Report ENDS on: sale/redemption/cancellation of the received
  shares; OR sale of the contributed shares by the holding within
  **3 years** unless ≥ **60 %** (raised to **70 % for FY-2026
  onward** per LFI 2026 — [legifiscal — durcissement 2026](https://www.legifiscal.fr/actualites-fiscales/4421-loi-finances-2026-durcissement-dispositif-apport-cession.html)) of
  proceeds reinvested in eligible operational assets / equity stakes
  / venture-capital funds within **2 years** (5 years for fund
  subscriptions, with 12-month-min holding); OR contributor's
  relocation outside FR (interacts with exit tax §1.10); OR
  non-compliance with holding-period obligations.

**Donation-cession** — pre-sale gift to a spouse/child/heir who then
sells. The donee receives the gift at fair value (basis stepped up
to the value at gift date), so when she sells immediately, the gain
is near-zero. Has been targeted by anti-abuse but remains broadly
available where the gift is genuine and not artificial.

### 1.10 Exit tax (art. 167 bis CGI)

For an individual who is fiscally resident in FR for **at least 6
of the 10 years preceding** the transfer of fiscal domicile abroad,
AND who holds either:

- securities valued in aggregate at **> €800 000**, OR
- **≥ 50 % entitlement to a company's profits** ("droits sociaux")

… the latent gains on those securities + earn-out claims + already-
deferred gains are **deemed realised** at the moment of departure
and taxed under art. 200 A conditions (i.e. PFU 31.4 % or barème
election).

**Monitoring period** ([Légifrance art. 167 bis](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806379)):

- **2-year standard** holding-abroad period (relief if the securities
  are still held by the taxpayer at the 2-year anniversary).
- **5-year extended** period when the total exit-taxed value
  **exceeds €2.57 M**.
- If the securities are sold during the period → deferral revoked,
  exit-tax becomes payable in FR even for the now-non-resident.
- Deferral mechanism: automatic for moves to compliant jurisdictions
  (EU + treaty); for other destinations, optional with guarantee
  posted (12.8 % of gross gain in security).

Substrate consequence: the exit tax is a **statutorily-deemed
disposal**. The disposal record uses `:disposal/kind :deemed` (the
existing enum value), `:elective-regime
#{:fr-167-bis-exit-tax}`, and `:realized-gain-loss` set to the latent
gain. The provider emits a one-off component; the audit-doc
attaches the form 2074-ETD and the residency-departure dossier.

---

## §2. Worked examples

### 2.1 Plus-values mobilières — PFU vs barème election crossover

Source: Adapted from [Ramify — flat tax 2026 calculation](https://www.ramify.fr/gestion-de-patrimoine/flat-tax)
and [meilleurescpi — PFU 31.4 %](https://www.meilleurescpi.com/conseils/flat-tax-2026-le-guide-ultime-du-prelevement-forfaitaire-unique-pfu/).

A. Dupont, individual, marginal IR bracket 30 % in 2026. She sells in
2026-04 a holding of unlisted PME shares acquired 2015-03 for
€100 000. Sale price €300 000. Holding period = 11 years (> 8 years
+ acquired pre-2018).

PME is < 10 years old at her acquisition date (founded 2013), EEE,
operational, IS-subject — qualifies for the **abattement renforcé
85 %**.

**Track 1 — default PFU 31.4 %**:
- Gross gain = €200 000.
- IR: 12.8 % × 200 000 = **€25 600**.
- PS: 18.6 % × 200 000 = **€37 200**.
- Total: **€62 800**.

**Track 2 — barème election + abattement renforcé**:
- Gross gain = €200 000.
- Abattement renforcé 85 % → taxable base = 200 000 × 15 % = €30 000.
- IR at 30 % marginal × €30 000 = **€9 000** (rough — actual is
  weighted by other income).
- PS: 18.6 % × **gross gain** (NOT abattement-reduced — PS apply to
  the gross, art. 150-0 D 4°) = 37 200.
- **CSG deductible** — 6.8 percentage points of the CSG (out of 10.6
  %) are deductible from N+1 IR (saves ~€4 080 next year @ 30 %).
- Total year-N: **€46 200** (and ≈ €42 120 net of the N+1 CSG-
  deductibility savings).

Election saves ≈ €16 600 — **barème is rational** here even at the
30 % marginal bracket because of the 85 % abattement. The crossover
moves with marginal rate / abattement tier; the provider must
**compute both tracks** and surface a recommendation if the taxpayer
hasn't explicitly elected.

Substrate trace: one `:disposal` with `:kind :sale`, `:subject-kind
:securities-stock`, `:asset-class :fr-titres-pme`, `:acquired-on
2015-03-15`, `:disposed-on 2026-04-20`, `:proceeds 300 000`, `:basis
100 000`, `:elective-regime #{:fr-barème}` (when elected, otherwise
nil), `:exemption-claimed #{:fr-abattement-durée}`. The FR CGT
provider reads the dates → computes 11 years → applies the 85 %
abattement to the net (per-bucket) gain when barème elected.

### 2.2 Plus-values immobilières — long-held secondary residence

Source: Adapted from [hagnère-patrimoine — plus-value immobilière 2026
combien](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/plus-value-immobiliere-2026)
and [construires — PV immobilière 2026 36.2 %](https://www.construires.fr/plus-value-immobiliere/).

B. Martin, individual, sold his secondary residence (rental) in
2026-06 for €450 000. Acquired 2002-09 for €180 000 (incl. notary fees
deemed at standard 7.5 % flat). Holding period at sale = 23 years
9 months → rounded down to 23 full years.

- Gross gain = €270 000.

**IR abatement ladder** (22 years → fully exempt):
- Years 1-5 (2002-2007): 0 %
- Years 6-21 (16 years × 6 %): 96 %
- Year 22 (4 %): 4 %
- Total: 100 % → **IR taxable base = €0**.

**PS abatement ladder** (30 years → fully exempt):
- Years 1-5: 0 %
- Years 6-21 (16 × 1.65 %): 26.4 %
- Year 22 (1.6 %): 1.6 %
- Years 23 (1 × 9 %): 9 %
- Total: 37 % → **PS taxable base = €270 000 × 63 % = €170 100**.

**Surtaxe** (art. 1609 nonies G) — applied to the **taxable** gain
remaining after abatements. Surtaxe and abatement are computed on
DIFFERENT bases (a known asymmetry):
- For IR — taxable gain = 0 → surtaxe = 0.
- For PS — but the surtaxe is an IR surtax (added to the 19 % IR),
  NOT a PS surtax → also 0 here because IR taxable is 0.

**Total tax**:
- IR: 19 % × 0 = €0.
- Surtaxe: 0.
- PS: 17.2 % × €170 100 = **€29 257**.
- **Total: €29 257** (≈ 10.8 % of gross gain).

Had he sold one year earlier (22 years), PS abatement would have been
28 % → taxable PS base = €194 400 → PS tax = €33 437. The extra
13-month holding saved €4 180. The substrate makes this analysis
trivially: `:acquired-on` + `:disposed-on` → per-component abatement
formulas → component tax.

Substrate trace: `:disposal` with `:kind :sale`, `:subject-kind
:real-estate-private`, `:asset-class :fr-immobilier-autre`,
`:acquired-on 2002-09-15`, `:disposed-on 2026-06-30`, `:proceeds
450 000`, `:basis 180 000`, `:residence? false`. The FR CGT
provider's "real-estate" component emits two line items (IR taxable
+ PS taxable) that are zero / 170 100 respectively, then runs the
schedule.

### 2.3 IS-side titres de participation — SAS sells qualifying stake

Source: [BOI-IS-BASE-20-20-10-20](https://bofip.impots.gouv.fr/bofip/4948-PGP.html/identifiant=BOI-IS-BASE-20-20-10-20-20240403)
and [PwC France — quote-part frais et charges](https://www.pwcavocats.com/fr/ealertes/ealertes-france/2021/11/cession-de-titres-de-participation-imposition-quote-part-frais-charges-montant-impot-credit-impot-imputation.html).

Holding-SAS H acquired 30 % of operating-SAS O in 2020-06 for
€2 000 000. In 2026-08 H sells the stake for €10 000 000. Holding
period = 6 years → ≥ 2 years → qualifies as titres de participation
LT.

- Realised gain = €8 000 000 (gross).
- **QPFC 12 %** = €8 000 000 × 12 % = **€960 000** reintegrated.
- IS standard 25 % × €960 000 = **€240 000** (assume not PME-
  eligible at this scale).
- Effective rate: 240 000 ÷ 8 000 000 = **3 %** (vs the DE § 8b
  ≈ 1.5 % effective rate — FR's 12 % QPFC is heavier than DE's 5 %).

Substrate trace: `:disposal` with `:kind :sale`, `:subject-kind
:participation`, `:asset-class :fr-titres-participation`,
`:subject-form :corp`, `:acquired-on 2020-06-15`, `:disposed-on
2026-08-20`, `:proceeds 10 000 000`, `:basis 2 000 000`,
`:ownership-fraction 0.30M`. The FR CGT provider's IS-side component
emits the QPFC as an **adjustment add-back to the CIT base** (same
mechanism as DE § 8b — `:cit-base-additions [960 000M]`); the gross
gain itself does NOT post to IS income.

---

## §3. `:disposal` schema fit — provision-by-provision

| FR provision | Schema field that carries it | Adequacy |
|---|---|---|
| **PFU vs barème election** (art. 200 A) | `:elective-regime #{:fr-pfu / :fr-barème}` | **CLEAN** — cardinality-many keyword set already on shipped schema. Tax-unit-level election (NOT per-disposal) — the provider reads it from `:inputs :tax-unit {:fr-baremè-elected? true}` and only falls back to per-disposal flag for paper-trail. |
| **Plus-values mobilières §150-0 A — default PFU** | `:asset-class :fr-titres-listed / :fr-titres-pme` | **CLEAN** — `:asset-class` is an open vocabulary; FR namespaces with `:fr-…` prefix per shipped convention. |
| **Plus-values mobilières — abattement-durée 150-0 D, 1 ter** | `:exemption-claimed #{:fr-abattement-durée}` + `:acquired-on` | **CLEAN** — the provider reads the date, computes years, picks 50 / 65 / 85 %; the exemption flag is the *claim* (audit trail), the math is provider-side. Eligibility tests (PME age, EEE, activity) ride `:disposal/notes` or — better — are *not* on the disposal at all; the issuer's `:entity` carries them (via `:concept-iri` per ADR-090 / note 88, or a sibling FR-l10n attr `:entity/fr-pme-eligible?`). |
| **Plus-values mobilières — abattement renforcé 1 quater** | same | **CLEAN** with above. The provider picks renforcé vs général by reading the issuer's eligibility flags (the 8-point checklist in §1.2). |
| **PEA / PEA-PME — exonération 5 ans** | `:exemption-claimed #{:fr-pea-exoneration}` + `:acquired-on` (= PEA open date) | **CLEAN** — same pattern; the `:asset-class :fr-pea` discriminator tells the provider to apply 0 % IR + 17.2 % or 18.6 % PS (depending on legacy "taux historiques" treatment). |
| **Plus-values immobilières — base + abattement-durée** | `:subject-kind :real-estate-private`, `:asset-class :fr-immobilier-residence / :fr-immobilier-autre`, `:acquired-on`, `:disposed-on` | **CLEAN** — per-IR + per-PS abatement ladders are provider math on the dates. |
| **Plus-values immobilières — résidence principale** | `:residence?` boolean + `:exemption-claimed #{:fr-residence-principale}` | **CLEAN** — `:residence?` shipped; provider zeroes the component when true. |
| **Surtaxe 1609 nonies G** | none on `:disposal`; provider computes from net taxable gain after abatement | **CLEAN** — pure provider-side surtax on the post-abatement IR base. Rides the `apply-adjustments` mechanism (tax_schedule.clj surtax-on layer). |
| **Plus-values pro — court terme vs long terme split** | `:asset-class :fr-pro-court-terme / :fr-pro-long-terme` + `:depreciation-taken-amount` + `:acquired-on` | **CLEAN** — `:depreciation-taken-amount` shipped per US §1245/§1250 (note 112) carries the recapture base; FR's split is structurally identical (recapture up to deprec, LT on excess) — the same field, different jurisdiction's allocation rule. |
| **§ 151 septies — petites entreprises exemption** | `:exemption-claimed #{:fr-151-septies-pme}` | **CLEAN** — the claim is on the disposal; the **eligibility** test (revenue thresholds, 5y operation) is on the **entity** at the period level. The provider reads the entity's period revenue from a `kontor.report` marginalisation (ADR-096) and applies the threshold cliff. NO new disposal field needed. |
| **§ 238 quindecies — transmission d'entreprise** | `:exemption-claimed #{:fr-238-quindecies-transmission}` | **CLEAN** — same pattern; the transmission-value test is *on the disposal itself* (€500 K / €700 K cliff). Provider reads `:proceeds-amount`. |
| **IS-side titres de participation — art. 219 12 % QPFC** | `:asset-class :fr-titres-participation` + `:subject-form :corp` + `:acquired-on` (>= 2y test) | **CLEAN** — the provider classifies, computes 12 % of gross gain, emits `:cit-base-additions [QPFC]` for the FR CIT provider's adjustment layer. Same shape as DE § 8b (note 113 §5.1). |
| **Brevets / IP box art. 238** | `:asset-class :fr-brevet` + `:elective-regime #{:fr-ip-box-238}` + `:subject-kind :intangible` | **CLEAN** — provider computes nexus-ratio-weighted net result; emits 10 % component. The nexus ratio inputs (R&D performed in FR vs total R&D on the asset) ride `:inputs :ip-box {:asset-id … :nexus-ratio … }` — provider-internal map. |
| **Loss buckets — 4 individual + 1 IS** | `:loss-bucket :fr-mv-mobilière / :fr-mv-immobilière / :fr-mv-pro-court / :fr-mv-pro-long / :fr-mv-titres-participation` | **CLEAN** — `:loss-bucket` shipped as keyword; FR conventionally namespaces. |
| **Loss carryforward — 10-year MV mobilière + 10-year MV pro LT** | `:inputs :capital-loss-carryforward` map per provider | **CLEAN** — opaque-to-substrate provider input; FR shape: `{:fr-mv-mobilière [{:vintage-year 2021 :amount (Money 50000 :EUR)} …] :fr-mv-pro-long […]}`. Vintage tracking is essential because the 10-year clock starts per-vintage. **DOCUMENT** the per-vintage shape in the FR provider docstring. |
| **Sursis d'imposition 150-0 B (share-for-share)** | `:disposal/kind` open question: is the exchange a disposal? | **DESIGN CALL** — the sursis is *automatic non-recognition*: the exchange does NOT realise gain. Two stances: (a) DO NOT emit a `:disposal` at all (treat as nil-event from CGT POV; the new holding inherits basis + `:acquired-on` from the old via lot tracking) — cleanest; (b) emit `:disposal/kind :deemed` with `:elective-regime #{:fr-sursis-imposition}` and `:realized-gain-loss 0M` for audit trail — heavier but provides a paper trail. **Recommendation: option (a).** Keep the substrate quiet on the deemed-transparent exchange; the lot's `:lot/acquired-on` is inherited via the GL conversion booking. The FR companion can provide a helper `record-sursis-exchange!` that updates the lot without writing a `:disposal`. |
| **Report d'imposition 150-0 B ter (apport-cession to controlled holding)** | `:disposal/kind :incorporation-contribution` + `:elective-regime #{:fr-150-0-B-ter-apport-cession}` + `:rollover-into-asset` (the holding's new shares) + `:rollover-deadline` (3 years for 60 % / 70 % reinvest) | **CLEAN-ISH** — the substrate shipped `:rollover-into-asset / -amount / -deadline` for DE § 6b; FR's report rides the same three fields. The mechanics differ (FR's window is 3y, reinvest target is 60 % → 70 % from 2026, in eligible operational assets / fund subscriptions), but the **data shape** is identical. The provider runs the FR-specific test at the rollover-deadline anniversary; the **state** of the deferral (active / liquidated / decayed) lives on the disposal's `:disposal/state` lifecycle or — better — a per-deferral entity that lives in a sibling `kontor-fr-tax-deferrals` namespace. **Recommend: shipping with the existing fields; defer the per-deferral entity to v2 if needed.** |
| **Exit tax 167 bis** | `:disposal/kind :deemed` + `:elective-regime #{:fr-167-bis-exit-tax}` + `:ownership-fraction` (≥ 0.5 test) + `:proceeds = latent FV` + `:basis = adjusted cost` | **CLEAN** — `:kind :deemed` shipped exactly for this case; `:ownership-fraction` for the 50 % test; the €800 K aggregate test is computed by the provider summing across the taxpayer's holdings at the moment of departure. |

---

## §4. Data gaps — the concrete extension list

**Only three additive enum extensions** to the shipped `:disposal`
schema. No new attributes, no new value types.

### 4.1 `:disposal/asset-class` — FR-namespaced values to seed

Add to the FR companion's published enum vocabulary (no schema change
— `:asset-class` is open vocabulary per shipped doc):

- `:fr-titres-listed` — listed shares (PFU / barème regime, no PME
  reinforced abatement).
- `:fr-titres-pme` — unlisted PME shares (PFU / barème + 1 quater
  reinforced abatement eligible if conditions met).
- `:fr-titres-participation` — IS-side qualifying participation (art.
  219 QPFC regime).
- `:fr-immobilier-residence` — primary residence (always exempt; tag
  for filtering).
- `:fr-immobilier-autre` — secondary residence / rental / investment
  property (art. 150 U regime).
- `:fr-pro-court-terme` — short-term business asset (art. 39
  duodecies).
- `:fr-pro-long-terme` — long-term business asset (art. 39 quindecies
  / 12.8 % IR + 17.2 % PS).
- `:fr-brevet` — patent / IP-box asset (art. 238 10 % regime).
- `:fr-pea` — PEA-held disposal (art. 157 5° exemption regime).
- `:fr-pea-pme` — PEA-PME (sibling).
- `:fr-immobilier-terrain-batir` — building land (no surtaxe).
- `:fr-immobilier-spi` — société à prépondérance immobilière (special
  19 % LT rate on the IS side).

### 4.2 `:disposal/elective-regime` — FR-namespaced values to seed

Add (cardinality-many keyword on the existing slot):

- `:fr-pfu` — explicit confirmation of default PFU (often nil because
  PFU is the default; for paper trail).
- `:fr-barème` — barème election under art. 200 A, 2. **Tax-unit-
  level — the provider also reads `:inputs :tax-unit {:fr-baremè-
  elected? true}` for the period-wide election**, but the per-
  disposal flag captures the intent recorded at disposal time.
- `:fr-150-0-B-ter-apport-cession` — report d'imposition under
  apport-cession to a controlled holding.
- `:fr-sursis-imposition` — sursis d'imposition under share-for-share
  (RECOMMENDED: do NOT emit a `:disposal` at all; this keyword is
  reserved for the rare audit-trail use).
- `:fr-167-bis-exit-tax` — exit-tax deemed disposal under art. 167
  bis.
- `:fr-ip-box-238` — annual election for the IP-box preferential
  10 % rate on a brevet / software.
- `:fr-étalement-3-ans` — étalement-3-ans for plus-values
  professionnelles long-terme (spreads tax over 3 years).

### 4.3 `:disposal/exemption-claimed` — FR-namespaced values to seed

Add (cardinality-many keyword on the existing slot):

- `:fr-pea-exoneration` — PEA / PEA-PME 5y exoneration.
- `:fr-residence-principale` — residence principale exemption.
- `:fr-151-septies-pme` — § 151 septies revenue-tested exemption.
- `:fr-238-quindecies-transmission` — § 238 quindecies transmission
  exemption.
- `:fr-abattement-durée` — abattement-pour-durée-de-détention
  général (1 ter) or renforcé (1 quater); the provider picks the
  variant from the issuer's eligibility flags + `:acquired-on`.
- `:fr-15000-vente` — sale ≤ €15 000 real-estate exemption.
- `:fr-réinvestissement-rp` — first-time non-RP sale with 24-month
  RP reinvestment.

### 4.4 `:disposal/loss-bucket` — FR-namespaced values to seed

Add (closed-set keyword on the existing slot):

- `:fr-mv-mobilière` — moins-values mobilières (10y carry; offset
  inside bucket).
- `:fr-mv-immobilière` — moins-values immobilières (non-deductible
  except narrow cases).
- `:fr-mv-pro-court` — moins-values pro court-terme (folds into BIC/
  BNC business loss).
- `:fr-mv-pro-long` — moins-values pro long-terme (10y carry; offset
  inside bucket).
- `:fr-mv-titres-participation` — IS-side titres-de-participation
  MV (non-deductible — sealed bucket).

### 4.5 Provider-side `:capital-loss-carryforward` input shape

The PIT / IS provider's `:inputs :capital-loss-carryforward` map
(opaque to the substrate, conventional per-provider) for FR:

```clojure
{:fr-mv-mobilière      [{:vintage-year 2018 :amount (money/money 12000M :EUR)}
                        {:vintage-year 2022 :amount (money/money 30000M :EUR)}
                        …]
 :fr-mv-pro-long       [{:vintage-year 2019 :amount (money/money 80000M :EUR)} …]}
```

Vintage-year tracking is essential because the **10-year clock starts
per-vintage**: a 2014 vintage expires at end-of-2024; a 2018 vintage
expires at end-of-2028. The provider sorts vintages oldest-first
on offset (avoid wasting an expiring vintage), then drops expired
vintages at period close.

### 4.6 Holding-period richness — `:acquired-on` IS rich enough

FR's tiered cutoffs:
- Mobilière général: 2y, 8y.
- Mobilière renforcé: 1y, 4y, 8y.
- Immobilière IR: 5y, 21y, 22y.
- Immobilière PS: 5y, 21y, 22y, 30y.
- Pro: 2y.

All driven by `(years-between :acquired-on :disposed-on)` arithmetic
in the provider — the substrate carries the dates, the provider
applies the rule. **NO schema gap.** This was the prompt's open
question; the answer is "the dates are richer than the enum, by
design (note 113 §3.1)".

---

## §5. `fr-cgt-provider` sketch — TWO providers, one orchestrator

FR CGT splits along corporate/individual exactly like DE (note 113
§5):

### 5.1 `fr-corporate-cgt-provider` — feeds into the FR CIT provider

A `PeriodTaxProvider` of kind `:capital-gains-tax`, output is an
**adjustment input** to the FR CIT provider (similar to DE § 8b):

```clojure
;; Conceptual — no code change in this note.
(defn fr-corporate-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :fr-corp-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals (disposal/disposals-of conn {:entity entity :period period})
            §219-tp   (->> disposals
                           (filter (every-pred #(= :fr-titres-participation (:disposal/asset-class %))
                                               #(>= (years-between %) 2)
                                               #(pos? (gain %))))
                           (map gain) (reduce + (money/zero :EUR)))
            qpfc       (money/multiply §219-tp 0.12M)
            ip-box-net (->> disposals
                            (filter #(contains? (:disposal/elective-regime %) :fr-ip-box-238))
                            (map (partial ip-box-nexus-weighted-net inputs))
                            (reduce + (money/zero :EUR)))
            ip-box-tax (money/multiply ip-box-net 0.10M)]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :fr-dgfip}
          :functional-commodity :EUR
          :components
          [;; QPFC component — adjustment input to FR CIT
           {:kind :capital-gains-tax :authority :fr-dgfip
            :base (money/zero :EUR)             ; exemption: gain is exempt
            :gross-liability (money/zero :EUR)  ; the IS lift is at the CIT layer
            :liability (money/zero :EUR)
            :line-items [{:line :§219-titres-participation-gain :value §219-tp}
                         {:line :§219-quote-part-12pct          :value qpfc}]
            :jurisdiction-specific-codes
            {:cit-base-additions [qpfc]}}
           ;; IP-box component — standalone 10 % at IS layer
           {:kind :capital-gains-tax :authority :fr-dgfip
            :base ip-box-net
            :schedule (ts/flat 0.10M)
            :gross-liability ip-box-tax
            :liability ip-box-tax
            :line-items [{:line :§238-ip-box-net :value ip-box-net}]
            :regime :ip-box-238}]})))))
```

### 5.2 `fr-personal-cgt-provider` — feeds into the FR PIT provider

Four buckets (mobilière, immobilière, pro CT, pro LT), each with its
own component:

```clojure
(defn fr-personal-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :fr-pers-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals (disposal/disposals-of conn {:entity entity :period period})
            tu        (:tax-unit inputs)
            barème?   (:fr-baremè-elected? tu)
            ;; Mobilière — apply abattement-durée only under barème
            mv-mob (->> disposals (filter mobilière?))
            mv-mob-net (net-against-bucket
                        mv-mob
                        (:fr-mv-mobilière (:capital-loss-carryforward inputs)))
            mv-mob-taxable (if barème?
                             (apply-abattement-durée-per-disposal mv-mob mv-mob-net)
                             mv-mob-net)
            ;; Immobilière — separate IR + PS bases (different abatement ladders)
            immo (->> disposals (filter immobilière?))
            immo-ir-base (reduce + (map immo-ir-after-abatement immo))
            immo-ps-base (reduce + (map immo-ps-after-abatement immo))
            immo-ir-tax  (money/multiply immo-ir-base 0.19M)
            immo-ps-tax  (money/multiply immo-ps-base 0.172M)      ; NOTE: 17.2 %, not 18.6 %
            immo-surtax  (surtaxe-1609-nonies-G immo-ir-base)
            ;; Mobilière tax — PFU 31.4 % default, barème route differs
            mv-mob-ir (if barème?
                        ; folds into PIT base via :pit-base-additions
                        (money/zero :EUR)
                        (money/multiply mv-mob-taxable 0.128M))
            mv-mob-ps  (money/multiply mv-mob-net 0.186M)          ; 18.6 % on gross since 2026
            ]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :fr-dgfip}
          :functional-commodity :EUR
          :components
          [;; Mobilière — PFU or barème adjustment input
           {:kind :capital-gains-tax :authority :fr-dgfip
            :base mv-mob-taxable
            :schedule (if barème? (ts/passthrough) (ts/flat 0.128M))
            :gross-liability mv-mob-ir :liability mv-mob-ir
            :surtaxes [{:code :ps :label "CSG/CRDS/Prélèvement solidarité"
                        :amount mv-mob-ps}]
            :regime (if barème? :fr-barème :fr-pfu)
            :jurisdiction-specific-codes
            (when barème? {:pit-base-additions [mv-mob-taxable]})}
           ;; Immobilière — two-base component (different abatement ladders)
           {:kind :capital-gains-tax :authority :fr-dgfip
            :base immo-ir-base
            :schedule (ts/flat 0.19M)
            :gross-liability (+ immo-ir-tax immo-surtax)
            :liability (+ immo-ir-tax immo-surtax)
            :surtaxes [{:code :surtaxe-1609 :label "Surtaxe plus-values > 50k€"
                        :amount immo-surtax}
                       {:code :ps :label "Prélèvements sociaux 17.2 %"
                        :amount immo-ps-tax}]
            :line-items [{:line :immo-ir-base :value immo-ir-base}
                         {:line :immo-ps-base :value immo-ps-base}]}
           ;; … pro CT / pro LT components similarly
           ]})))))
```

### 5.3 Tax-unit-level barème election — composition note

The barème election is **per-tax-year + per-foyer-fiscal**, not per-
disposal. The substrate convention:

1. The PIT provider's `:inputs :tax-unit {:fr-baremè-elected? true/
   false}` is the **authoritative** signal.
2. Per-disposal `:elective-regime #{:fr-barème}` is the **intent**
   recorded at disposal time (audit trail).
3. The CGT provider reads the tax-unit signal; the per-disposal flag
   is for paper trail and consistency check (if 80 % of disposals
   carry `:fr-barème` but the tax-unit signal is `false`, the
   provider warns the user — likely a missed election).

This mirrors note 113 §5.2's Günstigerprüfung treatment for DE.

### 5.4 PS layer composition — the 17.2 vs 18.6 wall

The FR provider must carry a per-asset-class PS rate map:

```clojure
(def ^:private fr-ps-rate-by-asset-class
  {:fr-titres-listed          0.186M    ; 2026+ : LFSS 2026
   :fr-titres-pme             0.186M
   :fr-titres-participation   nil       ; IS layer; no PS
   :fr-immobilier-residence   0.172M    ; excluded from LFSS 2026
   :fr-immobilier-autre       0.172M
   :fr-pro-court-terme        nil       ; folds into BIC/BNC ordinary
   :fr-pro-long-terme         0.172M    ; debated; per practice 17.2 still applies
   :fr-brevet                 nil       ; IS layer; no PS
   :fr-pea                    0.186M    ; current acquisitions; legacy "taux historiques" complex
   :fr-pea-pme                0.186M})
```

The rate is **per asset class, not per CGT regime**. The provider
emits the PS surtax against the appropriate base (gross gain for
mobilière, abattement-reduced for immobilière — note the asymmetry).

---

## §6. Sources

**FR statutes (legifrance.gouv.fr — license: public domain)**:
- [Article 150-0 A CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051218042) — plus-values mobilières (the charging article).
- [Article 150-0 B CGI](https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577/LEGISCTA000006179577/) — sursis d'imposition (share-for-share exchange).
- [Article 150-0 B ter CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806695) — report d'imposition (apport-cession to controlled holding).
- [Article 150-0 D CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051218045) — abattement-pour-durée-de-détention général (1 ter) + renforcé (1 quater), MV-mobilières offset + 10y carry (11°).
- [Article 150 U CGI](https://www.legifrance.gouv.fr/codes/id/LEGISCTA000006197216/) — plus-values immobilières (the charging article).
- [Article 151 septies CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036591469) — petites entreprises (revenue-tested) exemption.
- [Article 167 bis CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000048806379) — exit tax.
- [Article 200 A CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000045760882) — PFU + barème election (the 12.8 % IR layer).
- [Article 219 CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562) — IS rates (25 % / 15 %) + titres de participation regime (QPFC 12 %).
- [Article 238 CGI](https://bofip.impots.gouv.fr/bofip/11729-PGP.html/identifiant=BOI-BIC-BASE-110-30-20200422) — brevets / IP box (10 % preferential).
- [Article 238 quindecies CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051216496) — transmission d'entreprise (€500 K / €700 K thresholds).
- [Article 1609 nonies G CGI](https://www.legifrance.gouv.fr/codes/article/LEGITEXT000006069577/LEGIARTI000027577763/) — surtaxe progressive PV immobilière.

**BOFiP (official tax-administration commentary — bofip.impots.gouv.fr — license: public domain)**:
- [BOI-RPPM-PVBMI-20-30-10](https://bofip.impots.gouv.fr/bofip/9541-PGP.html/identifiant=BOI-RPPM-PVBMI-20-30-10-20191220) — abattement renforcé § 1 quater conditions (the 9-point checklist).
- [BOI-RPPM-PVBMI-20-20-20-10](https://bofip.impots.gouv.fr/bofip/9538-PGP.html/identifiant=BOI-RPPM-PVBMI-20-20-20-10-20191220) — abattement général § 1 ter rates.
- [BOI-RPPM-PVBMI-30-10-20-10](https://bofip.impots.gouv.fr/bofip/12042-PGP.html/identifiant=BOI-RPPM-PVBMI-30-10-20-10-20191220) — sursis d'imposition mechanics.
- [BOI-RPPM-PVBMI-30-10-60-20](https://bofip.impots.gouv.fr/bofip/12039-PGP.html/identifiant=BOI-RPPM-PVBMI-30-10-60-20-20250818) — apport-cession art. 150-0 B ter (latest revision 2025-08-18).
- [BOI-RPPM-PVBMI-20-10-40](https://bofip.impots.gouv.fr/bofip/2841-PGP.html/identifiant=BOI-RPPM-PVBMI-20-10-40-20250410) — moins-values mobilières 10y carry (latest revision 2025-04-10).
- [BOI-IS-BASE-20-20-10-20](https://bofip.impots.gouv.fr/bofip/4948-PGP.html/identifiant=BOI-IS-BASE-20-20-10-20-20240403) — titres de participation modalités (2024-04-03).
- [BOI-BIC-PVMV-40-10-10-30](https://bofip.impots.gouv.fr/bofip/6230-PGP.html/identifiant=BOI-BIC-PVMV-40-10-10-30-20220622) — § 151 septies effects of exemption.
- [BOI-BIC-PVMV-40-20-50](https://bofip.impots.gouv.fr/bofip/6156-PGP.html/identifiant=BOI-BIC-PVMV-40-20-50-20220511) — § 238 quindecies transmission.
- [BOI-BIC-BASE-110-30](https://bofip.impots.gouv.fr/bofip/11729-PGP.html/identifiant=BOI-BIC-BASE-110-30-20200422) — IP-box brevets regime fiscal.
- [BOI-BIC-BASE-110-20](https://bofip.impots.gouv.fr/bofip/11727-PGP.html/identifiant=BOI-BIC-BASE-110-20-20230503) — IP-box conditions d'application.

**impots.gouv.fr (taxpayer-facing official)**:
- [Les cessions mobilières — impots.gouv.fr](https://www.impots.gouv.fr/particulier/les-cessions-mobilieres) — taxpayer overview of plus-values mobilières.
- [Exonération PME — impots.gouv.fr](https://www.impots.gouv.fr/professionnel/questions/comment-beneficier-de-lexoneration-des-plus-values-reservees-aux-petites) — § 151 septies conditions.
- [Exit tax — impots.gouv.fr](https://www.impots.gouv.fr/international-particulier/questions/i-am-leaving-france-do-i-have-pay-exit-tax) — taxpayer-facing exit-tax overview.

**Practitioner / Big-4 / specialised commentary**:
- [PwC Avocats — quote-part frais et charges 12 %](https://www.pwcavocats.com/fr/ealertes/ealertes-france/2021/11/cession-de-titres-de-participation-imposition-quote-part-frais-charges-montant-impot-credit-impot-imputation.html) — CE 2017-06-14 + 2021-11-15 jurisprudence on QPFC base.
- [PwC Avocats — IP box](https://www.pwcavocats.com/fr/nos-expertises/gestion-et-strategie-fiscale-de-l-entreprise/cir-ip-box-et-financement-innovation/ip-box-un-regime-fiscal-favorable-pour-vos-brevets-et-logiciels.html) — IP-box practitioner walkthrough.
- [Deloitte Avocats — IP box réforme](https://blog.avocats.deloitte.fr/lip-box-a-la-francaise-reforme-du-regime-dimposition-des-produits-de-cession-ou-de-concession-de-brevets-art-14-du-plf-nouvel-article-238-du-cgi/) — 2019 reform of art. 238 (15 % → 10 % rate change + nexus).
- [DLA Piper — LFSS 2026 CSG hausse](https://www.dlapiper.com/fr-fr/insights/publications/2026/01/loi-de-finance-de-la-securite-sociale-2026) — authoritative law-firm analysis of the LFSS 2026 PS-rate change.
- [hagnère-patrimoine — taux PS 2026 par catégorie](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026) — per-asset-class breakdown of which incomes stay at 17.2 % vs move to 18.6 %.
- [Victoris Avocat — flat tax 2026 dirigeants](https://www.victorisavocat.com/en/blog/flat-tax-le-guide-complet-pour-les-dirigeants-et-investisseurs-en-2026) — PFU 31.4 % synthesis.
- [legifiscal — apport-cession durcissement 2026](https://www.legifiscal.fr/actualites-fiscales/4421-loi-finances-2026-durcissement-dispositif-apport-cession.html) — 60 % → 70 % reinvestment-quota change for 2026.
- [Hagnère Patrimoine — apport-cession 150-0 B ter](https://www.hagnere-patrimoine.fr/guides-patrimoine/holding-patrimoniale/apport-cession-150-0-b-ter) — practitioner guide with worked examples.
- [Bpifrance Création — exonération valeur cession](https://bpifrance-creation.fr/encyclopedie/fiscalite-lentreprise/fiscalite-transmissionreprise/lexoneration-plus-values-1) — § 238 quindecies value cliff.
- [Bpifrance Création — exonération recettes](https://bpifrance-creation.fr/encyclopedie/fiscalite-lentreprise/fiscalite-transmissionreprise/lexoneration-plus-values-0) — § 151 septies revenue thresholds.
- [Notaires de Paris — surtaxe PV immobilière](https://paris.notaires.fr/fr/actualites/surtaxe-sur-les-plus-values-immobilieres) — bracket table for surtaxe + exclusions.
- [Secob — abattement durée ne s'applique pas aux MV](https://www.secob.fr/actualites/fiscalite/abattement-pour-duree-de-detention-il-ne-s-applique-pas-aux-moins-values) — asymmetry of abattement-durée on net positive only.
- [CMS Law — PV en report et MV](https://cms.law/fr/fra/news-information/plus-values-en-report-d-imposition-et-moins-values) — interaction of report d'imposition with loss offsets.
- [Auguste Patrimoine — exit tax](https://www.auguste-patrimoine.fr/gestion-patrimoniale/exit-tax) — practitioner walkthrough with worked examples.
- [Syntaxe Avocats — French exit tax in a nutshell](https://syntaxe.com/en/the-french-exit-tax-in-a-nutshell/) — English-language summary.
- [Construires — PV immobilière 2026 36.2 %](https://www.construires.fr/plus-value-immobiliere/) — abatement ladder per-component breakdown.
- [Hagnère Patrimoine — PV immobilière 2026 combien](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/plus-value-immobiliere-2026) — worked examples of the IR/PS asymmetric ladders.
- [Ramify — flat tax 2026](https://www.ramify.fr/gestion-de-patrimoine/flat-tax) — PFU vs barème crossover analysis.
- [Meilleurescpi — PFU 31.4 %](https://www.meilleurescpi.com/conseils/flat-tax-2026-le-guide-ultime-du-prelevement-forfaitaire-unique-pfu/) — 2026 rate confirmation.
- [Le Coin des Entrepreneurs — § 238 quindecies](https://www.lecoindesentrepreneurs.fr/exoneration-transmission-dentreprise-238-quindecies/) — transmission conditions.
- [XTB — PEA-PME](https://www.xtb.com/fr/formation/pea-pme) — PEA-PME plafond + eligibility.

**kontor substrate cited (file:line)**:
- `modules/disposal/src/kontor/disposal/schema.clj:62-309` — the shipped `:disposal` schema this note assesses fit against.
- `modules/disposal/src/kontor/disposal/schema.clj:96-109` — `:disposal/subject-kind` enum (FR uses `:securities-stock` for mobilières, `:real-estate-private` for immobilières, `:participation` for IS-side titres-de-participation, `:fixed-asset` for pro assets, `:intangible` for brevets).
- `modules/disposal/src/kontor/disposal/schema.clj:122-129` — `:disposal/subject-form` (FR uses `:corp` to fire the art. 219 IS regime, `:individual` / `:sole-prop` for the mobilière / immobilière / pro tracks).
- `modules/disposal/src/kontor/disposal/schema.clj:132-148` — `:acquired-on` + `:disposed-on` (the date primitives that drive ALL FR abatement-durée ladders).
- `modules/disposal/src/kontor/disposal/schema.clj:189-201` — `:depreciation-taken-amount` (FR pro court-terme uses this for the recapture-up-to-depreciation rule, identical to US §1245).
- `modules/disposal/src/kontor/disposal/schema.clj:204-209` — `:ownership-fraction` (FR exit tax 50 % test rides this).
- `modules/disposal/src/kontor/disposal/schema.clj:211-217` — `:residence?` (FR résidence principale exemption).
- `modules/disposal/src/kontor/disposal/schema.clj:219-225` — `:elective-regime` cardinality-many (PFU/barème + 150-0 B ter + IP box + exit-tax + étalement).
- `modules/disposal/src/kontor/disposal/schema.clj:227-233` — `:exemption-claimed` cardinality-many (PEA, RP, § 151 septies, § 238 quindecies, abattement-durée).
- `modules/disposal/src/kontor/disposal/schema.clj:236-261` — `:rollover-into-asset` + `:rollover-amount` + `:rollover-deadline` (FR report d'imposition 150-0 B ter uses these three, structurally identical to DE § 6b).
- `modules/disposal/src/kontor/disposal/schema.clj:264-272` — `:loss-bucket` (FR has 5 buckets — keyword closed-set extension).
- `src/kontor/tax_schedule.clj:142-163` — `apply-base-transform :adjustments` (FR QPFC 12 % rides this exactly like DE § 8b 5 % add-back).
- `src/kontor/tax_schedule.clj:192-235` — `apply-adjustments` (FR PS surtax + surtaxe-1609 ride this).
- `src/kontor/personal_income_tax.clj:65-118` — the adjustment-layer composition pattern the FR barème-elected mobilière fold mirrors (`:pit-base-additions`).
- `src/kontor/period_tax_provider.clj:51-60` — `:capital-gains-tax` is in the closed period-tax-kinds enum already.
- `src/kontor/period_tax_provider.clj:134-141` — `:capital-loss-carryforward` `:inputs` slot (FR extends to per-bucket vintage-tracked maps).
- `src/kontor/sole_proprietor.clj:25-55` — the "CGT provider feeds CIT/PIT via composition" pattern.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the `:disposal` schema proposal this note assesses.
- `doc/research/112-us-cgt-fit.md` — sibling US-CGT fit note (§1245/§1250 recapture, §1202 QSBS, §1031 like-kind, §121 home sale).
- `doc/research/113-de-cgt-fit.md` — sibling DE-CGT fit note (§8b KStG 95/5, §6b rollover, §17 1 %, §20 Abgeltungsteuer, §23 10y).
- `doc/research/114-uk-cgt-fit.md` — sibling UK-CGT fit note (TCGA 1992 individual, CTA 2010 corporate, BADR lifetime cap, indexation, SSE).
- `doc/research/115-jp-cgt-fit.md` — sibling JP-CGT fit note (separate vs aggregated, Jan-1 measurement, ¥30M residence, listed/unlisted compartments).

---

End of note 128.

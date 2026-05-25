---
date: 2026-05-24
title: 149 — FR investment-income taxation — substrate fit for the `fr-investment-income-provider`
status: research-before for note-104 Phase 3 C2 (FR + UK investment-income providers); no code; data-gap list at §4
audience: maintainer + the FR-investment-income implementation agent
---

# 149 — FR investment-income substrate fit

Phase 3 has shipped (a) `kontor.incorporation` and the
`declare-dividend!` / `distribute-dividend!` verbs (note 107 §2.6 /
ADR-095) on the corporation side; (b) eleven CGT providers including
`fr-personal-cgt-provider` + `fr-corporate-cgt-provider` (note 128 /
ADR-102 / `modules/l10n-fr/.../cgt-provider.clj`); (c) the FR
`PeriodTaxProvider` for impôt sur le revenu with the quotient
familial formula (`modules/l10n-fr/.../period_tax_provider.clj`).

What is still missing is the **shareholder-side** investment-income
treatment: when the founder/individual *receives* a dividend or
interest payment, FR's PFU (Prélèvement Forfaitaire Unique) or barème
election decides the IR component; the prélèvement sociaux (PS, post-
LFSS-2026 18.6 % on patrimoine) decides the social component. Several
wrappers (PEA, assurance-vie) exempt or reduce IR; a régime mère-fille
(CGI 145 / 216) does the same job at the IS layer for corporate
recipients.

Bottom line: **the substrate is sufficient — no new schema; only an
additive provider + provider-input conventions.** The
`fr-investment-income-provider` is a sibling of `fr-personal-cgt-
provider` (a `PeriodTaxProvider` with `:kind :personal-income-tax`
in the v1 enum-fit, but composing into PIT exactly the way the
mobilière barème election already does — `:pit-base-additions` /
`:credits` for the 12.8 % withholding credit). The headline data-gap is
**zero**: the kernel's account-type marginalization (σ_E on `:income`)
+ a per-account `:account-tag :fr-investment-income/*` family + the
existing `:elective-regime` on `:partner` (the paying entity) for
mère-fille all carry the FR statute cleanly. The integrative tension
sits at one place: the **barème election is tax-unit-level + period-
wide**, not per-payment, and it cascades to BOTH the CGT mobilière
provider (already wired) AND the new investment-income provider —
both must read the same `:tax-unit :pfu-or-barème` signal.

---

## §1. The FR investment-income regime — five overlapping shapes

FR does not have ONE investment-income tax. Like CGT (note 128), the
*shape* of investment income — dividend vs interest vs life-insurance
withdrawal vs PEA withdrawal vs corporate-recipient dividend — fires a
distinct regime. The five:

### 1.1 PFU vs barème — the default two-track (CGI Art. 200 A + 117 quater + 158-3-2°)

Since the 2018 PFU reform, the default treatment for an individual's
investment income is the same single envelope CGT mobilière uses:

- **2018-2025 era**: **30 % total** = 12.8 % IR (CGI 200 A) +
  17.2 % PS.
- **2026-01-01 onwards (LFSS 2026, loi 2025-1403)**: **31.4 % total**
  = 12.8 % IR + **18.6 % PS** (CSG raised 9.2 % → 10.6 %; CRDS 0.5 %
  + prélèvement de solidarité 7.5 % unchanged).

The PS rate is per-category, not global — see §1.7 below.

The **barème election** under CGI 200 A, 2 — "option pour
l'imposition au barème" — is:

- **Irrevocable for the tax year** (the LFI 2026 added the ability
  to *renounce* the option after-the-fact if the barème turns out
  *worse* than PFU — a one-way safety valve; per [legifiscal LFI
  2026](https://www.legifiscal.fr/) commentary).
- **Tax-unit-wide** — applies to ALL of the household's PFU-eligible
  income for the year (NOT cherry-pickable by income source). This
  is critical: the same election binds both the investment-income
  provider AND the CGT mobilière provider (note 128 §5.3).
- Folds the (post-abattement) income into the personal-income-tax
  brackets (0 / 11 / 30 / 41 / 45 %) on top of the same PS layer.

When barème is elected, two **specific to investment income**
sweeteners apply that CGT mobilière does not get:

1. **40 % abattement on dividendes** (CGI 158-3-2°) — only 60 % of
   the gross dividend amount is subject to IR. Eligibility: paying
   entity must have its seat in FR / EU / a treaty-state with
   mutual-assistance clause AND be subject to corporate income tax
   (or equivalent). Excludes payments from non-cooperative-state
   entities and from SCI à l'IS in some configurations. The 40 %
   abattement applies ONLY to IR; PS still computed on the gross.
   ([BOI-RPPM-RCM-20-10-30-10](https://bofip.impots.gouv.fr/bofip/2218-PGP.html/identifiant=BOI-RPPM-RCM-20-10-30-10-20191220)).
2. **CSG déductible** (CGI 154 quinquies) — when barème is elected,
   **6.8 percentage points of the CSG** (out of 10.6 % since 2026,
   was 6.8 of 9.2 % pre-2025) are deductible from the **N+1**
   global income — a deferred income deduction. This means the
   effective marginal cost of CSG is lower than the headline rate
   for barème-elected taxpayers. ([article 154
   quinquies](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000047288608)
   confirms 6.8 points deductible).

The **12.8 % prélèvement à la source non-libératoire** (CGI 117
quater) is deducted by the paying agent at payment time, **as an
advance payment on the year's IR**. It is NOT a final settlement
— it is creditable against the IR computed at year-end (PFU or
barème). Exemption from the prélèvement is available for low-RFR
households:

- **Interest income** (revenus de placement à revenu fixe):
  exemption if RFR(N-2) < €25 000 single / €50 000 couple.
- **Dividend income** (revenus distribués): exemption if RFR(N-2)
  < €50 000 single / €75 000 couple.

The exemption is requested by the taxpayer to the paying agent
(banque / société émettrice) before **30 November of year N-1**.

### 1.2 PEA / PEA-PME — full IR exemption after 5 years (CGI Art. 157, 5°)

The **Plan d'Épargne en Actions** is a per-individual securities
envelope (cap €150 000, cumulative cap with PEA-PME €225 000)
holding EU equities. **Dividends and interest received inside the
envelope** are not taxed at the moment of receipt — they are
*capitalised* tax-free. Tax fires only at *withdrawal*:

- **Before 5 years** — withdrawal triggers full taxation of the
  cumulative gain (capital appreciation + accumulated income) at
  PFU 31.4 % AND closes the plan (with certain hardship
  exceptions: redundancy, retirement, disability).
- **After 5 years** — withdrawals are **exonérées d'IR** entirely;
  only PS apply at **18.6 %** (2026; legacy "taux historiques"
  doctrine for pre-2018 plans is complex — outside v1 scope).

Note 128 §1.3 already documented PEA for CGT; the same wrapper
covers dividend/interest income — the kontor convention is that
inside-PEA income simply isn't recognised in the personal-income
substrate at all; it is the *withdrawal* event that surfaces a
realisation. **No additional substrate stress** beyond what note
128 §3 already mapped.

PEA-PME — same mechanics, narrower investment universe (issuers
with market cap < €2 B, < 5 000 employees, ≤ €1.5 B turnover OR
≤ €2 B balance sheet).

### 1.3 Assurance-vie — preferential rates after 4y / 8y (CGI Art. 125-0 A)

The most common French savings wrapper. Income received inside the
contract is **not taxed annually** (tax-deferred). Tax fires at
**withdrawal (rachat)** OR **at death (transmission)**; the v1
investment-income provider focuses on rachats (transmission is the
ISF/succession track, separate).

Per CGI 125-0 A, the **portion of a withdrawal representing the
gain** (computed as (rachat × gain/contract value)) is taxed at:

| Contract age at rachat | IR rate (default PFU) | Notes |
|---|---|---|
| < 4 years | **12.8 %** | PFU rate; same as ordinary dividend PFU |
| 4-8 years | **12.8 %** | Same |
| ≥ 8 years AND gain ≤ €4 600 single / €9 200 couple from versements ≤ €150 k | **0 % after abattement** | The €4 600 / €9 200 annual abattement applies |
| ≥ 8 years AND gain from versements ≤ €150 k (above abattement) | **7.5 %** | Preferential IR rate |
| ≥ 8 years AND gain from versements > €150 k | **12.8 %** | Standard PFU IR — the €150 k "premiums-paid" cap (CGI 125-0 A, 1° quater) splits the gain pro-rata |

Plus PS at **17.2 %** (assurance-vie is **EXCLUDED** from the LFSS
2026 raise to 18.6 % — it stays at 17.2 %, per [hagnère-patrimoine
PS 2026 par catégorie](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026)).
PS computation depends on the contract form (eurofonds: annual
prélèvement at inscription en compte; unités de compte: at rachat).
v1 simplifies to "PS at rachat" — the eurofonds annual prélèvement
is a paying-agent operation outside kontor's substrate.

The barème election still applies if elected for the year — folds
the (post-abattement) assurance-vie gain into the IR brackets at
marginal rate.

### 1.4 Régime mère-fille — corporate recipient 95 % exemption (CGI Art. 145 + 216)

The IS-side equivalent of §1.1 for corporate recipients of dividends
from qualifying subsidiaries. Conditions ([BOI-IS-BASE-10-10-10-10](https://bofip.impots.gouv.fr/bofip/1924-PGP.html/identifiant=BOI-IS-BASE-10-10-10-10-20200415)):

- **Holding ≥ 5 %** of the subsidiary's capital (a stake threshold;
  participation must be measured at the moment of distribution).
- **2-year holding commitment** — the parent commits to hold the
  shares for at least 2 years. Sale within the 2-year window
  triggers retroactive disallowance + interest.
- **Both parent and subsidiary subject to IS** under ordinary law
  (or equivalent EU CIT regime).
- Subsidiary located in FR / EU / treaty-state.

When conditions are met: **95 % of the dividend is excluded** from
the parent's taxable income; **5 % "quote-part de frais et charges"
(QPFC)** is reintegrated as a deemed expense. This mirrors note
128 §1.6's titres-de-participation CGT QPFC (which is **12 %**, not
5 %; the difference is statutory and intentional — dividend QPFC
is lower because the underlying tax was already paid at the
subsidiary level).

Effective rate on the dividend: 5 % QPFC × 25 % IS = **1.25 %**
(vs the CIT base full 25 %).

This already integrates with the FR CIT provider (see ADR-105) via
the same `:cit-base-additions` mechanism — see §5.5 below.

### 1.5 Régime fiscal des holdings (intégration fiscale) — Art. 223 A

When a parent + 95 %-held subsidiary opt for **intégration fiscale**
(consolidation for tax purposes), intra-group dividends become
**neutralised** — only 1 % QPFC applies (vs the 5 % for mère-fille
outside intégration), and the dividend itself drops to zero in the
group's taxable result. v1 does NOT model intégration fiscale (it
is a multi-entity construct that needs ADR-073 consolidation —
deferred to a future stage); the v1 provider applies the standard
5 % QPFC for all mère-fille-qualifying dividends and documents the
intégration gap.

### 1.6 Loss offsets — capital losses do NOT offset investment income (general rule)

Unlike CGT mobilière losses (which net inside their bucket — note
128 §1.8), **moins-values mobilières (capital losses) CANNOT
offset dividends or interest income**. They net only against
plus-values mobilières (capital gains) of the same nature within
the same bucket and carry forward 10 years for capital-gain offset
ONLY.

This is the **same rule** as US, JP, UK — investment income and
capital gains are computed in *parallel* compartments, not netted.

The one exception is the IS-side **titres-de-participation MV** —
non-deductible entirely (sealed bucket per note 128 §1.6).

### 1.7 PS rate carve-outs — 17.2 % vs 18.6 % wall

The LFSS 2026 raised the prélèvements sociaux to 18.6 % for most
patrimoine-style income, but left several categories at 17.2 %.
Critical for investment income:

- **Dividends + interest (revenus de placement / distribués)** —
  **18.6 %** post-2026.
- **PEA gains (post-acquisition)** — **18.6 %** for current
  acquisitions; legacy "taux historiques" doctrine for pre-2018
  plans complex.
- **Assurance-vie** — STAYS at **17.2 %**. Per [hagnère 2026 par
  catégorie](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026)
  this is the most notable carve-out — assurance-vie is the
  preferred household savings vehicle, and the carve-out reflects
  political reluctance to disrupt that.
- **PEL / CEL / PEP** — STAYS at 17.2 %.
- **Revenus fonciers** (rental income, separate income category —
  see §1.8) — STAYS at 17.2 %.

Substrate consequence: the FR investment-income provider must
carry a per-income-source PS rate map, exactly the way the CGT
provider does (note 128 §5.4 / cgt-provider §pfu-asset-classes).

### 1.8 Revenus fonciers (rental income) — separate category, micro vs réel

A SEPARATE income category, not investment income proper, but
captured here for completeness because the FR substrate
classifies it under "revenus du patrimoine" and the PS layer
applies. CGI Art. 14 places it in its own compartment:

- **Régime micro-foncier** (CGI Art. 32): when gross rental
  income ≤ €15 000/year, automatic 30 % abattement (deemed
  expenses); no per-charge deduction. Cannot generate a deficit.
- **Régime réel** (CGI Art. 31): real expenses deductible
  (charges de copropriété, travaux, intérêts d'emprunt,
  amortissements LMNP, etc.). Net deficit limited to €10 700/year
  imputable on the global income (above that, carries forward
  10 years against future rental income).

Net rental income (after régime selection) goes into the **IR
brackets at marginal rate** — it is taxed at the barème AS A
MATTER OF COURSE (PFU does not apply to rental income, which is
the largest "I'm-not-PFU-default" investment-income carve-out).
PS at 17.2 % on the same net.

v1 investment-income provider may include rental income as a
separate component for symmetry, but the canonical home is a
**`fr-rental-income-provider`** sibling (see §5.6 — out of scope
for v1; documented as the natural extension).

---

## §2. Worked examples

### 2.1 Individual — dividend, PFU vs barème election

Source: Adapted from [Hagnère Patrimoine — dividendes 2026 PFU
ou barème](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/fiscalite-dividendes-2026)
and [Ramify — flat tax 2026 calcul](https://www.ramify.fr/gestion-de-patrimoine/flat-tax).

A. Lefèvre, single filer, marginal IR bracket 30 % in 2026. She
receives a gross dividend of **€20 000** from a FR-resident SAS
in March 2026.

**Track 1 — default PFU 31.4 %**:

- Gross dividend = €20 000.
- 12.8 % prélèvement à la source (PAS) deducted at payment by the
  SAS's paying agent: €2 560 (advance on IR).
- 18.6 % PS deducted at payment: €3 720.
- A. receives net €13 720 at payment.
- At year-end: IR component = €2 560 (the PAS was final; barème
  not elected). The PAS is credited against year-end tax in full.
- Total tax: **€6 280** (= 31.4 % of €20 000).

**Track 2 — barème election + 40 % abattement**:

- Gross dividend = €20 000.
- **40 % abattement** (CGI 158-3-2°) → taxable base = €12 000.
- IR at 30 % marginal × €12 000 = **€3 600**.
- PS: 18.6 % × **gross dividend** (NOT abattement-reduced — same
  rule as CGT mobilière, PS apply to the gross) = **€3 720**.
- **CSG deductible** — 6.8 percentage points of the 10.6 % CSG
  (rounded base) on €20 000 = €1 360 deductible from **N+1**
  global income (saves ~€408 next year @ 30 %).
- The 12.8 % PAS (€2 560) is credited against the €3 600 IR;
  net IR due at year-end = €3 600 − €2 560 = €1 040.
- Total year-N cost: **€7 320** (€3 600 IR + €3 720 PS) — minus
  CSG-deductible savings ~€408 in N+1 → effective ~€6 912.

Barème **loses** here (PFU €6 280 < barème €7 320 net) — at the
30 % marginal bracket the 40 % abattement does not offset the
higher marginal rate. The crossover sits around marginal rate
21.3 % (12.8 % ÷ (1 − 40 %)) — below that, barème wins; above,
PFU wins. A 11 % bracket taxpayer would benefit from barème.

Substrate trace: ONE posting on A.'s books — Dr Bank €13 720, Cr
Income:Dividendes €13 720 (the GL records the NET received; the
gross is on the partner's `:audit-doc` — typically an IFU /
attestation fiscale supplied by the SAS). The
`fr-investment-income-provider` at period close reads:

- `(report/marginalize postings :account-tag {:tag :fr-investment-
  income/dividende-fr-ue-éligible-abattement-40})` → €20 000 gross
  (the consumer wires the chart with the right account-tag).
- `(:tax-unit :pfu-or-barème ctx)` → `:pfu` or `:barème`.
- `(:tax-unit :marginal-band ctx)` → `:30` (one of `:0` `:11`
  `:30` `:41` `:45`) when barème, used for the IR computation
  alongside the household's quotient familial.
- `(:inputs :prepaid-pas ctx)` → `{:fr-pas-117-quater 2_560M}` —
  the 12.8 % advance withholding, credited against year-end IR.

The provider emits ONE component (kind `:personal-income-tax` per
the v1 enum) carrying:

- `:base` = the IR base (gross OR 60 % of gross under barème).
- `:gross-liability` = IR before credits.
- `:credits` = the €2 560 PAS as a `:credit` item with
  `:refundable? true` (CGI 200 A — excess PAS is refunded).
- `:surtaxes` = the PS line at 18.6 %.
- `:liability` = post-PAS IR + PS.

The `:pit-base-additions` slot routes to the FR PIT provider when
barème is elected — the dividend gross × 60 % becomes part of the
PIT taxable income, the quotient familial fires, and the FR PIT
provider's own credit cascade handles the PAS credit.

### 2.2 Individual — interest income with low-RFR exemption from PAS

B. Martin, single filer, RFR(2024) = €22 000 (below the €25 000
single threshold for interest-PAS exemption). He receives €800 of
interest from a FR-resident bank account in 2026.

- B. submitted the dispense form to his bank before 30 Nov 2025.
- **No 12.8 % PAS deducted at payment** (dispense granted).
- **18.6 % PS still deducted** (the dispense covers IR only):
  €148.80.
- B. receives net €651.20.
- At year-end (2027 filing of 2026 income): IR computed.
  - Default PFU 12.8 % × €800 = €102.40.
  - OR barème election + marginal rate (e.g. 0 % bracket if no
    other income; here B. is at the 0 % bracket on his €22 000
    other income, so barème = €0). B. clearly elects barème → IR
    = €0.
- Total tax 2026: **€148.80** (PS only).

Substrate trace: same shape as 2.1, but with `:tax-unit :pfu-or-
barème :barème`, and the `:inputs :prepaid-pas {:fr-pas-117-
quater 0M}` (no PAS deducted). The provider emits IR = 0 (0 %
bracket × 100 % of base), PS = €148.80. The CSG deductible saves
~€54 in N+1 if B. had any taxable income.

### 2.3 Corporate — dividend received under régime mère-fille

Source: [BOI-IS-BASE-10-10-10-10](https://bofip.impots.gouv.fr/bofip/1924-PGP.html/identifiant=BOI-IS-BASE-10-10-10-10-20200415)
+ [CCL avocat — mère-fille 95 % 2026](https://ccl-avocat.fr/regime-mere-fille/).

Holding-SAS H owns 30 % of operating-SAS O since 2022 (3 years held,
> 2-year commitment satisfied). In June 2026, O distributes a
dividend; H receives €500 000 (gross).

- **95 % exemption** under CGI 145 + 216: €475 000 is excluded
  from H's taxable income.
- **5 % QPFC** = €25 000 reintegrated as deemed expense.
- H's IS @ 25 % × €25 000 = **€6 250** incremental tax.
- Effective rate on the dividend: 6 250 / 500 000 = **1.25 %**.

Without régime mère-fille (e.g. only 3 % holding): full €500 000
folds into IS base → €125 000 IS @ 25 %. The mère-fille saving:
€118 750.

Substrate trace: ONE posting on H's books — Dr Bank €500 000, Cr
Income:Dividends-Subsidiaries €500 000 (the chart-of-accounts
account is tagged `:fr-investment-income/mère-fille-eligible`).
The `fr-corporate-investment-income-provider` reads:

- The dividend postings tagged mère-fille-eligible.
- The partner-side `:partner/holding-fraction` (≥ 5 % test) —
  resolved from the holding-relationship between H and O.
- The partner-side `:partner/held-since` (≥ 2-year test).

The provider emits ONE component, kind `:capital-gains-tax`
(reusing the existing enum slot since v1 has no `:investment-
income-tax` kind; alternative: extend the enum — see §4 G1) with
`:base 0M`, `:liability 0M`, and `:jurisdiction-specific-codes
{:cit-base-additions [25 000M]}` — the QPFC threads into the FR
CIT provider's adjustment layer exactly the same way the CGT
provider's titres-de-participation QPFC does (note 128 §5.1).

---

## §3. `:disposal`-style substrate — is there an analog?

The CGT substrate decision (note 107) was: **introduce a `:disposal`
companion entity** so each capital-disposal event has a first-class
record with `:proceeds`, `:basis`, `:acquired-on`, `:disposed-on`,
`:exemption-claimed`, `:elective-regime`. Question: does dividend /
interest need an equivalent `:investment-income-event` entity?

**Answer: NO — the GL posting IS the event.** Three reasons:

1. **No basis to track.** A dividend has no acquisition cost
   (other than the underlying share's basis, which is already in
   `:lot/*` per ADR-029). A coupon payment has no separate basis.
   The whole `:disposal/basis` machinery — the half of the CGT
   substrate that needs explicit modelling — has no analog here.
2. **No holding-period math.** Dividends don't taper by holding
   period (mère-fille 2-year is a *partner-relationship*
   condition, not a per-payment condition); interest doesn't
   either. The whole `:acquired-on` / `:disposed-on` arithmetic is
   absent. (PEA's 5-year mark is on the *plan*, not the
   *withdrawal* — and PEA withdrawals are CGT-mobilière events
   the existing substrate already handles.)
3. **The posting carries everything.** Date is `:transaction/
   effective-date`; amount is `:posting/amount`; classification is
   `:account-tag` on the credit account; counterparty (paying
   entity) is `:transaction/partner`. The provider has every
   datum it needs by marginalizing income postings (`report/
   marginalize`) by the appropriate tag axis.

The CGT note's `:disposal/asset-class` analog here is **the
account-tag family** — exactly the ADR-090 / note 88 `:concept-iri`
pattern. The chart-of-accounts ships with FR-tagged income
accounts; the provider routes on the tag.

### 3.1 The chart-of-accounts shape (FR PCG 76x)

The Plan Comptable Général parks investment income in the **76x**
class:

- **761** Produits des participations (dividends from associated
  companies — mère-fille candidates).
- **762** Produits des autres immobilisations financières.
- **763** Revenus des autres créances (interest on long-term
  loans).
- **764** Revenus des valeurs mobilières de placement (interest +
  dividends on short-term investments — typical individual /
  small-business case).
- **765** Escomptes obtenus.
- **766** Gains de change (FX gains — outside investment-income
  scope; covered by `kontor.fx`).
- **768** Autres produits financiers (interest income, coupon
  payments, miscellaneous).

The FR l10n chart (`modules/l10n-fr/.../chart.clj`) already ships
these accounts at note 124 baseline. The investment-income
provider adds **`:account-tag :fr-investment-income/<sub-kind>`**
to each:

- `:fr-investment-income/dividende-fr-ue-éligible-abattement-40`
  — dividends from FR/EU/treaty-state entities subject to IS
  (40 % abattement under barème + 12.8 % PAS via 117 quater).
- `:fr-investment-income/dividende-hors-abattement`
  — dividends not eligible for the 40 % abattement (non-cooperative
  state, SCI not à l'IS, etc.).
- `:fr-investment-income/dividende-mère-fille-éligible`
  — IS-side dividends qualifying for CGI 145 (5 % QPFC).
- `:fr-investment-income/intérêts-prfix-fr`
  — interest income from FR/EU sources (12.8 % PAS via 117
  quater unless dispensed).
- `:fr-investment-income/intérêts-livrets-exonérés`
  — Livret A / LEP / LDDS / Livret Jeune — fully exempt (no IR,
  no PS); provider filters out at compute time.
- `:fr-investment-income/assurance-vie-rachat-gain`
  — gain portion of an assurance-vie rachat (Art. 125-0 A track).
- `:fr-investment-income/pea-retrait-gain`
  — gain portion of a PEA withdrawal (Art. 157, 5° track) — note
  that for PEA the existing CGT mobilière provider already handles
  the capital-gain slice; this tag is for the *income*
  (dividend/interest) slice if the consumer chooses to split.
- `:fr-investment-income/foncier-net`
  — net rental income post régime micro/réel (deferred to
  `fr-rental-income-provider` — v1 marker only).

The tag set is open (per ADR-090); l10n-fr ships the v1 closed
vocabulary. Foreign-source dividends with credit d'impôt (treaty
WHT) get their own family of tags — deferred to a `fr-foreign-
investment-income` sub-namespace, since they require the FX rate
provider + treaty matrix.

---

## §4. Data gaps — concrete list

| # | Gap | Required for | Recommendation |
|---|---|---|---|
| G1 | `:period-tax-kinds` enum needs `:investment-income-tax` value | Provider's `:kind` slot is currently constrained to `period-tax-kinds` (note 102 §7 / `period_tax_provider.clj`) which has 8 values — `:personal-income-tax` is the closest fit but conflates with the PIT base | **EXTEND** the enum: add `:investment-income-tax` (kernel one-line addition, ADR-099 amendment). UK note 150 needs the same value. |
| G2 | `:partner/holding-fraction` (BigDecimal) + `:partner/held-since` (instant) on the partner edge | mère-fille 5 % stake test + 2-year holding test | **EXISTS** on the kernel `:partner` schema (verify; ADR-031 family-of-related-entities). If absent, add as two scalar attrs on `:partner` — additive. |
| G3 | `:tax-unit :pfu-or-barème` and `:marginal-band` | Tax-unit-level barème election; routes BOTH CGT and investment-income providers | **NO SCHEMA** — `:tax-unit` is opaque to substrate per note 102 §9-D. Document the v1 keys; both providers read the same `:tax-unit` slot. |
| G4 | `:inputs :prepaid-pas {:fr-pas-117-quater <amount>}` for the 12.8 % advance | Credit at year-end of advance-withheld IR | **NO SCHEMA** — opaque `:inputs` per ADR-099. Document the per-provider convention. The kontor.book `entry!` records the GROSS dividend (the bank posting); the consumer separately tracks the PAS as a prepayment (Asset: PAS-à-imputer) and threads the amount via `:inputs`. |
| G5 | Account-tag family `:fr-investment-income/*` | Routing income postings to PFU vs barème vs mère-fille vs livret-exempt | **NO SCHEMA** — the kernel `:account-tag` slot (ADR-019 / note 78) accepts open keywords. l10n-fr publishes the closed v1 vocabulary (§3.1). |
| G6 | PS rate parameter table per category (18.6 vs 17.2) | Per-category PS surtax | **`:parameter`** entries per ADR-101 — l10n-fr ships the parameter values under `"FR.IIT.PS.dividendes-rate"`, `"FR.IIT.PS.assurance-vie-rate"`, etc. Already-shipped substrate. |
| G7 | Mère-fille holding-period audit | 2-year commitment + retrospective claw-back | **NO SCHEMA in kernel** — the audit lives on the partner-relationship history; if the parent sells within 2 years, the consumer fires an adjustment posting + revises the prior year's QPFC. Document in the provider docstring; not a kernel concern. |
| G8 | Assurance-vie €150 k versements split | Per-contract pro-rata gain attribution to ≤ €150 k (7.5 %) vs > €150 k (12.8 %) bands | **`:inputs :assurance-vie {:contract-id {:premiums-paid <bd> :gain-portion-of-rachat <bd>}}`** — provider-internal map; assurance-vie contracts are typically held outside kontor (with the assureur) so the consumer supplies the per-rachat split as input |
| G9 | CSG-déductible carry-forward to N+1 | The 6.8 pp deductible from N+1 IR | **NO SCHEMA** — emit as a `:line-item` on the year-N component with kind `:fr-csg-deductible-carry`; the FR PIT provider for N+1 reads it as a base-transform deduction `:csg-deductible-prior-year`. Document the convention. |

**Summary of additive substrate changes**:

- **ONE kernel addition**: `:investment-income-tax` value in the
  closed `period-tax-kinds` enum (kernel `period_tax_provider.clj`).
- **ZERO new attributes**, **ZERO new entity kinds**, **ZERO new
  protocols**.
- All other "gaps" are provider-internal conventions on the
  already-shipped `:inputs` / `:tax-unit` / `:account-tag` /
  `:parameter` slots.

---

## §5. `fr-investment-income-provider` sketch

### 5.1 Two providers, mirroring the CGT shape

The FR CGT split (note 128 §5) into `fr-corporate-cgt-provider` +
`fr-personal-cgt-provider` is reproduced exactly:

- **`fr-personal-investment-income-provider`** — PIT-side. Three
  components: dividendes, intérêts, assurance-vie-rachat. PS layer
  per category. Barème election threads `:pit-base-additions` to
  the PIT provider; PFU emits standalone IR + PS.
- **`fr-corporate-investment-income-provider`** — IS-side. ONE
  component: mère-fille QPFC. Threads `:cit-base-additions` to the
  CIT provider.

### 5.2 Personal provider conceptual sketch

```clojure
;; Conceptual — no code change in this note.
(defrecord FRPersonalInvestmentIncomeProvider [id source authority commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs tax-unit] :as ctx}]
    (let [db        (:db ctx)
          as-of     (or (:to period) (:as-of ctx) (java.util.Date.))
          ;; Marginalize the entity's income postings by FR-l10n tag
          postings  (report/report-postings db {:from (:from period) :to (:to period)
                                                :entity entity})
          by-tag    (report/marginalize postings :account-tag {:commodity commodity})
          ;; Each lane's gross income
          div-elig  (get-in by-tag [:fr-investment-income/dividende-fr-ue-éligible-abattement-40 :value])
          div-other (get-in by-tag [:fr-investment-income/dividende-hors-abattement :value])
          int-prfix (get-in by-tag [:fr-investment-income/intérêts-prfix-fr :value])
          av-gain   (get-in by-tag [:fr-investment-income/assurance-vie-rachat-gain :value])

          barème?   (= :barème (:pfu-or-barème tax-unit))
          ir-rate   (param db "FR.IIT.PFU.IR-rate" as-of)
          ;; Per-category PS rate
          ps-div    (param db "FR.IIT.PS.dividendes-rate" as-of)         ; 18.6 %
          ps-int    (param db "FR.IIT.PS.intérêts-rate" as-of)            ; 18.6 %
          ps-av     (param db "FR.IIT.PS.assurance-vie-rate" as-of)       ; 17.2 %
          ;; 40 % abattement (CGI 158-3-2°): IR base = 60 % of gross under barème
          div-ir-base (if barème? (* div-elig 0.60M) div-elig)
          ;; PAS credit (12.8 % already withheld by paying agent)
          pas-credit  (or (get-in inputs [:prepaid-pas :fr-pas-117-quater]) 0M)
          ;; IR per lane
          div-ir   (if barème?
                     0M                                ; folds into PIT via :pit-base-additions
                     (* (+ div-elig div-other) ir-rate))
          int-ir   (if barème? 0M (* int-prfix ir-rate))
          ;; assurance-vie has its own ladder: 0/7.5/12.8 by age + abattement
          av-ir    (assurance-vie-ir-component db ctx av-gain)
          ;; PS layer (always due, even if IR exempt)
          div-ps   (* (+ div-elig div-other) ps-div)
          int-ps   (* int-prfix ps-int)
          av-ps    (* av-gain ps-av)
          ;; Net IR liability after PAS credit
          ir-total (+ div-ir int-ir av-ir)
          ir-net   (max 0M (- ir-total pas-credit))]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:country :fr :authority authority}
        :functional-commodity commodity
        :components
        [{:kind :investment-income-tax :authority authority
          :base (money/money (+ div-ir-base div-other int-prfix av-gain) commodity)
          :gross-liability (money/money ir-total commodity)
          :credits [{:code :fr-pas-117-quater :label "PAS 12.8 % crédité"
                     :amount (money/money pas-credit commodity)
                     :refundable? true}]
          :surtaxes [{:code :ps-div :label "PS dividendes (18.6 %)" :amount (money/money div-ps commodity)}
                     {:code :ps-int :label "PS intérêts (18.6 %)"   :amount (money/money int-ps commodity)}
                     {:code :ps-av  :label "PS assurance-vie (17.2 %)" :amount (money/money av-ps commodity)}]
          :liability (money/money (+ ir-net div-ps int-ps av-ps) commodity)
          :line-items [...]
          :regime (if barème? :fr-barème :fr-pfu)
          :jurisdiction-specific-codes
          (cond-> {:lane :fr-investment-income}
            barème? (assoc :pit-base-additions [(+ div-ir-base int-prfix av-gain)]
                           :csg-deductible-carry (* 0.068M (+ div-elig div-other int-prfix))))}]}))))
```

### 5.3 Tax-unit-level barème election — shared with CGT

Both `fr-personal-cgt-provider` (already shipped) and the new
`fr-personal-investment-income-provider` read the SAME `:tax-unit
:pfu-or-barème` signal. The election binds period-wide; the
substrate convention:

1. The CGT provider already reads `(get-in ctx [:tax-unit :pfu-or-bareme])`
   per note 128 §5.3.
2. The investment-income provider reads the same key.
3. The consumer typically wires a single map `{:pfu-or-barème :barème}`
   into the `:tax-unit` slot at period close; both providers see
   the same election.
4. If the CGT provider's `:disposal/elective-regime
   :fr-barème` count disagrees with the tax-unit election, the
   consumer surfaces a warning (existing CGT convention).

### 5.4 PAS (Prélèvement Forfaitaire) credit — the prepayment shape

The 12.8 % PAS is **structurally** a prepayment, not a final tax.
Two ways to model:

- **(a) prepayment-as-credit** (the v1 recommendation): the GL
  records the GROSS dividend (Dr Bank net + Dr Prepaid-PAS gross−
  net, Cr Income gross); the consumer threads the PAS via
  `:inputs :prepaid-pas`; the provider emits the PAS as a
  `:credit` item with `:refundable? true` (so excess PAS
  generates a refund); the existing adjustment-fold in
  `PersonalIncomeTaxProvider` already handles this. This mirrors
  the way US PIT handles withholding (W-2 box 2 → 1040 line 25a).
- **(b) prepayment-as-balance** (deferred): the kontor.tax kernel
  has a `:prepaid` slot on the component (ADR-099 / period_tax_-
  provider.clj line ~26: "tax already withheld in-period rides
  `:inputs :prepaid` → the component's `:prepaid`"). This is the
  cleaner shape but currently only the PIT provider uses it; the
  investment-income provider should ALSO use `:prepaid` for the
  PAS amount. Either model fits; the team picks per consistency.

**Recommendation**: use `:prepaid` (shape b) — same slot the PIT
provider uses for PAS classique (the salary withholding); the
shapes unify.

### 5.5 Corporate provider — mère-fille → CIT base addition

The corporate side mirrors the CGT corporate provider's titres-de-
participation QPFC (note 128 §5.1) exactly:

```clojure
(defrecord FRCorporateInvestmentIncomeProvider [id source authority commodity]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period] :as ctx}]
    (let [db        (:db ctx)
          as-of     (or (:to period) (java.util.Date.))
          postings  (report/report-postings db ...)
          by-tag    (report/marginalize postings :account-tag {:commodity commodity})
          ;; mère-fille-eligible dividends only
          mf-gross  (get-in by-tag [:fr-investment-income/dividende-mère-fille-éligible :value])
          ;; 5 % QPFC reintegrated
          qpfc-rate (param db "FR.IIT.MèreFille.QPFC-rate" as-of)
          qpfc      (* mf-gross qpfc-rate)]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:country :fr :authority authority}
        :functional-commodity commodity
        :components
        [{:kind :investment-income-tax :authority authority
          :base (money/zero commodity)             ; 95 % exemption
          :liability (money/zero commodity)        ; IS lift at CIT layer
          :line-items [{:line :mf-gross :label "Dividendes mère-fille éligibles (brut, exonéré 95 %)"
                        :value (money/money mf-gross commodity)}
                       {:line :mf-qpfc  :label "QPFC 5 % réintégrée à l'IS"
                        :value (money/money qpfc commodity)}]
          :jurisdiction-specific-codes {:cit-base-additions [qpfc]
                                         :lane :fr-mère-fille}}]}))))
```

The FR CIT provider (ADR-105 / `cit-provider.clj`) already
consumes `:cit-base-additions` as an adjustment input (note 128
§5.1 / ADR-101 base-transform mechanism). The mère-fille QPFC
folds into the IS computation alongside the titres-de-
participation QPFC — both via the same channel.

### 5.6 The fr-rental-income-provider — out of scope for v1

Rental income (revenus fonciers, CGI Art. 14) is a SEPARATE
category from investment income proper. The substrate fit is
clean — the provider marginalizes `:fr-investment-income/foncier-
net` (or a separate `:fr-rental-income/*` family), applies the
micro-foncier 30 % abattement OR sums régime-réel deductions, and
emits ONE component that folds into the PIT base at marginal rate
(rental income is ALWAYS barème — PFU does not apply). PS at
17.2 % (carve-out, not 18.6 %).

v1 does NOT ship a rental-income provider; the gap is documented
and a separate `fr-rental-income-provider` is a natural follow-on
(no substrate stress; same shape as the personal investment-
income provider with one component).

---

## §6. Cross-jurisdiction integration check

| Aspect | FR | (cross-check: DE) | (cross-check: US) | (cross-check: UK note 150) |
|---|---|---|---|---|
| Default rate on dividends | PFU 31.4 % (12.8 + 18.6) | Abgeltungsteuer 26.375 % (25 + Soli) | Qualified 0/15/20 % + NIIT 3.8 % | Tier rates 8.75/33.75/39.35 % + 0 PS |
| Election out | Barème (CGI 200 A, 2) | Günstigerprüfung (§32d Abs. 6 EStG) | Ordinary-rate election (Form 4952 for elected interest) | None (no rate-election mechanism) |
| Allowance/abattement | 40 % abattement on dividends (barème only); €4 600/€9 200 assurance-vie | €1 000 Sparer-Pauschbetrag | Qualified-dividend rate-cap (sub-LTCG bracket) | £500 dividend allowance + £1 000/£500 savings allowance |
| Wrapper exemption | PEA (5y), assurance-vie (8y), Livret A | Riester/Rürup pensions | 401(k) / IRA / HSA | ISA (£20 000/year), pensions |
| Corporate-recipient relief | mère-fille 95 % (5 % QPFC) | §8b KStG 95 % (5 % NABE) | DRD §243 (50/65/100 %) | Distribution exemption (small-co full; large-co conditional, Part 9A CTA 2009) |
| WHT credited at filing | 12.8 % PAS (CGI 117 quater) | 25 % Kapitalertragsteuer | 24 % backup WHT | None (no domestic WHT on dividends) |

Substrate fit across all four: the dividend-income provider is
*structurally* identical — one component, IR rate × base, optional
PS surtax, optional `:pit-base-additions` for elective barème-
folding, optional `:cit-base-additions` for corporate-recipient
QPFC. The kontor pattern works uniformly.

---

## §7. Open design questions / followups

1. **Should `:investment-income-tax` be a separate `:kind` value or
   subsumed into `:personal-income-tax`?** (G1). The cleaner shape
   is a separate enum value because PIT and investment-income
   compose distinctly (PIT base ≠ investment-income base; some
   countries tax them at different rates entirely; the reporting
   surfaces are different). **Recommend: extend the enum** — one-
   line kernel change, both FR (note 149) and UK (note 150) need
   it.

2. **PEA dividends inside the envelope** — should they emit a
   posting at the moment of receipt (with a contra-account so the
   GL records the income event) OR stay invisible until withdrawal?
   The FR practice is to *capitalise* (no annual posting; the
   envelope tracks the cumulative gain only). The kontor
   convention should mirror practice: a PEA portfolio is a single
   "investment account" on the holder's books (Asset:Portefeuille
   PEA); the cumulative gain accrues on the asset account
   directly (FX-style), and only the *withdrawal* triggers a
   recognized event. Document as a chart-of-accounts pattern; no
   substrate change.

3. **Foreign-source dividends with treaty WHT** — out of scope
   for v1. A `fr-foreign-investment-income` sub-provider reading
   `:inputs :foreign-wht {:country :de :amount <bd>}` is the
   natural follow-on. Needs the FX rate provider integration
   (already shipped: `kontor.fx`) + the treaty matrix (per-treaty
   WHT rate). Defer.

4. **Régime intégration fiscale (Art. 223 A)** — neutralises
   intra-group dividends with 1 % QPFC instead of 5 %. v1
   provider applies the standard 5 % to all mère-fille-eligible
   dividends; consumers using intégration override via
   `:inputs :mère-fille {:integration-fiscale? true}` and the
   provider reads the parameter. Document.

5. **Assurance-vie €150 k cap pro-rata math** — the gain portion
   of a rachat above the €150 k versements ceiling is split
   pro-rata between the 7.5 % (≤ €150 k) and 12.8 % (> €150 k)
   bands. v1 provider expects the consumer to pre-compute and
   supply both portions in `:inputs :assurance-vie`; the math is
   a closed-form ratio. Document with a worked example in the
   provider docstring.

---

## §8. Sources

**FR statutes (legifrance.gouv.fr — license: public domain)**:

- [Article 117 quater CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000036428175) — Prélèvement forfaitaire 12,8 % non libératoire sur les revenus distribués (the 12.8 % PAS on dividends).
- [Article 125-0 A CGI](https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577/LEGISCTA000006179577/) — Imposition des produits attachés aux bons ou contrats de capitalisation (assurance-vie).
- [Article 145 CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000038589881) — Régime des sociétés mères et filiales — conditions.
- [Article 154 quinquies CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000047288608) — Déductibilité partielle de la CSG (6,8 points).
- [Article 157 5° CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577) — PEA exonération.
- [Article 158-3-2° CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000051765203) — Abattement 40 % sur dividendes (barème seulement).
- [Article 200 A CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000045760882) — PFU + barème election (12,8 % IR sur revenus du capital).
- [Article 216 CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577) — Régime mère-fille — modalités (5 % QPFC).
- [Article 219 CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000046868562) — IS rates (25 % / 15 % PME).
- [Article 223 A CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577) — Régime de l'intégration fiscale.
- [Article 14 CGI](https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577) — Revenus fonciers (revenus catégoriels).
- [Article 32 CGI](https://www.legifrance.gouv.fr/codes/article_lc/LEGITEXT000006069577) — Régime micro-foncier.

**BOFiP (official tax-administration commentary — bofip.impots.gouv.fr — license: public domain)**:

- [BOI-RPPM-RCM-20-10-30-10](https://bofip.impots.gouv.fr/bofip/2218-PGP.html/identifiant=BOI-RPPM-RCM-20-10-30-10-20191220) — Abattement 40 % sur dividendes — conditions d'éligibilité.
- [BOI-RPPM-RCM-30-20-10](https://bofip.impots.gouv.fr/bofip/3747-PGP.html/identifiant=BOI-RPPM-RCM-30-20-10-20210706) — Prélèvement forfaitaire obligatoire non libératoire — champ d'application.
- [BOI-IS-BASE-10-10-10-10](https://bofip.impots.gouv.fr/bofip/1924-PGP.html/identifiant=BOI-IS-BASE-10-10-10-10-20200415) — Régime mère-fille — conditions d'application — sociétés éligibles.
- [BOI-IR-BASE-20-20](https://bofip.impots.gouv.fr/bofip/887-PGP.html/identifiant=BOI-IR-BASE-20-20-20170724) — Déductibilité partielle de la CSG.
- [BOI-RFPI-DECLA-10](https://bofip.impots.gouv.fr/bofip/3973-PGP.html/identifiant=BOI-RFPI-DECLA-10-20250306) — Régime micro-foncier (rev. 2025-03-06).

**impots.gouv.fr (taxpayer-facing official)**:

- [Revenus d'épargne et de placement 2026](https://www.service-public.gouv.fr/particuliers/vosdroits/F34913/1_7) — taxpayer overview, PFU vs barème 2026 rates.
- [Dispense du PFNL](https://www.impots.gouv.fr/particulier/questions/puis-je-beneficier-dune-dispense-du-prelevement-forfaitaire-non-liberatoire) — RFR thresholds for PAS exemption.
- [PFU — fonctionnement](https://www.economie.gouv.fr/particuliers/impots-et-fiscalite/gerer-mes-autres-impots-et-taxes/comment-fonctionne-le-prelevement) — Bercy explainer.

**Practitioner / patrimoine analysis**:

- [Hagnère Patrimoine — dividendes 2026 PFU ou barème](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/fiscalite-dividendes-2026) — worked PFU vs barème crossover examples, including the 21.3 % marginal-rate crossover point with 40 % abattement.
- [Hagnère Patrimoine — CSG/CRDS/PS 2026 par catégorie](https://www.hagnere-patrimoine.fr/guides-patrimoine/comment-payer-moins-impots/csg-crds-prelevements-sociaux-2026) — the per-category 18.6 vs 17.2 % carve-out table.
- [Hagnère Patrimoine — assurance-vie fiscalité 2026](https://www.hagnere-patrimoine.fr/guides-patrimoine/assurance-vie/fiscalite-assurance-vie) — assurance-vie ladder by age (0/7.5/12.8) + €4 600/€9 200 abattement.
- [PEA fiscalité 2026](https://pea.fr/fiscalite/fiscalite-pea/) — PEA exonération mechanics, 5y mark.
- [Ramify — flat tax 2026](https://www.ramify.fr/gestion-de-patrimoine/flat-tax) — PFU vs barème detailed calculator.
- [Victoris Avocat — flat tax 2026 dirigeants](https://www.victorisavocat.com/en/blog/flat-tax-le-guide-complet-pour-les-dirigeants-et-investisseurs-en-2026) — synthesis for company directors.
- [Service Public Entreprendre — évolution PFU 2026](https://entreprendre.service-public.gouv.fr/actualites/A18796) — official confirmation of 31.4 % rate post-LFSS 2026.
- [CCL Avocat — régime mère-fille 95 % 2026](https://ccl-avocat.fr/regime-mere-fille/) — practitioner walkthrough with 5 % QPFC.
- [Legalplace — régime mère-fille 2026](https://www.legalplace.fr/guides/regime-mere-fille/) — 2026 conditions + holding period.
- [PwC France — mesures IS LFI 2024](https://www.pwcavocats.com/fr/ealertes/ealertes-france/2024/loi-de-finances-pour-2024-mesures-pour-les-entreprises/principales-autres-mesures-en-matiere-impot-sur-les-benefices.html) — mère-fille recent amendments.
- [Agence France Trésor — tax treatment of government securities](https://www.aft.gouv.fr/en/tax-treatment-securities) — interest income on French OAT/BTAN/BTF, capital-loss carry rules.
- [LégiFiscal — dispense PFNL](https://www.legifiscal.fr/impots-personnels/impot-revenu/dispense-prelevement-forfaitaire-liberatoire.html) — RFR thresholds + dispense procedure.
- [Indy — CSG déductible 2026](https://www.indy.fr/guide/fiscalite/charges-deductibles/csg/) — 6.8 % deductible mechanism.
- [Le Trader du Dimanche — abattement 40 %](https://letraderdudimanche.com/quels-sont-les-dividendes-eligibles-a-labattement-de-40) — practitioner guide to dividend eligibility for 40 % abattement.

**kontor substrate cited (file:line)**:

- `src/kontor/period_tax_provider.clj:51-60` — closed `period-tax-kinds` enum (the 8 values); G1 adds `:investment-income-tax`.
- `src/kontor/personal_income_tax.clj:60-118` — the adjustment-layer composition pattern the investment-income provider mirrors.
- `src/kontor/personal_income_tax.clj:23-27` — `:prepaid` slot for in-period withholding (PAS shape b).
- `src/kontor/book.clj:296-330` — `declare-dividend!` / `distribute-dividend!` verbs (the corporation side of the dividend flow; consumer side is `receive!`).
- `src/kontor/report.clj` — `marginalize` (the σ_E primitive the provider uses to roll up income postings by tag).
- `src/kontor/statute.clj` — `parameter-value-at` (the bitemporal parameter read used for PS rates).
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_provider.clj:790-810` — `fr-personal-cgt-provider` constructor (sibling shape the new provider mirrors).
- `modules/l10n-fr/src/kontor/l10n_fr/cgt_provider.clj:414-420` — tax-unit-level barème election routing (`:tax-unit :pfu-or-bareme`).
- `modules/l10n-fr/src/kontor/l10n_fr/cit_provider.clj` — CIT provider that consumes `:cit-base-additions` (mère-fille QPFC threads here).
- `modules/l10n-fr/src/kontor/l10n_fr/period_tax_provider.clj` — FR PIT (impôt sur le revenu) provider that consumes `:pit-base-additions` (barème-elected dividend folds here).
- `modules/l10n-fr/src/kontor/l10n_fr/chart.clj` — PCG chart that publishes the 76x accounts the investment-income tag family attaches to.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §2.6 — dividend verbs design.
- `doc/research/128-fr-cgt-fit.md` §5 — `fr-personal-cgt-provider` shape this provider mirrors; §5.3 tax-unit-wide barème election shared discipline; §1.6 régime mère-fille for IS-side CGT.
- `doc/research/102-period-tax-provider-design.md` §7 — the `period-tax-kinds` enum design rationale (G1 amendment touches this).
- `doc/research/119-adr-101-draft.md` — `:parameter` substrate the PS rates ride on.
- `doc/research/150-uk-investment-income-fit.md` — sibling UK note; G1 enum addition is shared.

---

End of note 149.

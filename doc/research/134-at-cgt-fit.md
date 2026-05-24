---
date: 2026-05-24
title: 134 — AT capital-gains tax — substrate fit against the shipped `:disposal` schema
audience: maintainer + the Phase 3 `at-cgt-provider` implementer
status: research-before for the Phase 3 AT CGT provider(s); no code; data-gap list at §4
---

# 134 — AT capital-gains tax: `:disposal` schema fit assessment

This note answers one question: does the shipped `:disposal` schema
(`modules/disposal/src/kontor/disposal/schema.clj`) carry enough data
to drive a faithful Austrian capital-gains-tax provider across the
**four** statutory shapes AT uses — §27/§27a EStG (KESt-Endbesteuerung
on financial assets), §30/§30a EStG (ImmoESt on real estate), §10
KStG (corporate participation exemption with the Schachtelbeteiligung
+ optional-tax-effectiveness twist), and §9 KStG (Gruppenbesteuerung
loss sharing) — or do we need to extend it?

Bottom line: **the schema needs ZERO additive kernel fields**. The
shipped fields carry every AT distinction this note surfaces. The
schema-design choices that pay off: the cardinality-many
`:exemption-claimed` set carries Hauptwohnsitzbefreiung +
Herstellerbefreiung in parallel; the `:elective-regime` cardinality-
many set carries the §10 KStG Option zur Steuerwirksamkeit; the
`:asset-class` companion attr threads the Altvermögen/Neuvermögen
cutoff (acquired before/after 2002-03-31) into the rate-method
selection. AT does surface **two architectural calls** the substrate
must answer: (a) the §10 KStG Schachtelbeteiligung "tax-neutral by
default, opt-in to taxable" structure inverts the usual
opt-out-of-exemption assumption; (b) the §30 EStG 15-year
60%-distributed real-estate loss carryforward against §28 rental
income is the *first* substrate case where a CGT carryforward
**crosses income categories** — a substrate stress documented in §6.2.
Three sibling providers — `at-kest-cgt-provider`, `at-immoest-provider`,
and a **null** `at-corporate-cgt-provider` (gains land in EIT as
ordinary income, exceptions only).

---

## §1. The AT CGT regime — four statutory shapes

AT does not have ONE capital-gains tax. It has four overlapping
provisions, each with a distinct base, rate, taxpayer class, and
loss-offset rule:

### 1.1 Individual side — §27 + §27a EStG: KESt-Endbesteuerung

The 2011 Budgetbegleitgesetz (BBG 2011) restructured Austria's
capital-income taxation, extending what had been a
withholding-tax-on-interest regime to a **withholding-tax-on-realised-
gains** regime that took effect 2012-04-01. Codified at §§ 27 + 27a
EStG 1988 (Einkommensteuergesetz). Two rates and one mechanic:

- **27.5%** flat (§27a Abs 1 Z 2 EStG) — Austrian and foreign
  dividends, distributions from investment funds, **capital gains on
  shares + bonds + funds + derivatives**, crypto-asset gains
  (§27b EStG), and similar "Einkünfte aus realisierten
  Wertsteigerungen".
- **25%** flat (§27a Abs 1 Z 1 EStG) — interest on bank deposits
  (Sparbuch) and similar bank claims.
- **Endbesteuerungswirkung** (§97 EStG): the KESt withheld by the
  Austrian custodian bank **discharges the entire income tax
  liability** on the covered income. The investor does *not* report
  these in the annual return unless they elect Regelbesteuerung (the
  ~German-Günstigerprüfung analogue) for one of two reasons: (i)
  marginal rate is lower than 27.5% (the
  "Regelbesteuerungsoption" — §27a Abs 5 EStG); (ii) the bank failed
  to withhold (offshore custodian) so the investor must file Anlage
  E1kv.

### 1.2 Individual side — Altvermögen vs Neuvermögen

The KESt-Endbesteuerung regime applies to **Neuvermögen** (new
assets) — financial instruments **acquired after specific cutoff
dates**:

- **Shares + fund units + derivatives**: acquired after **2010-12-31**.
- **Bonds + interest-bearing securities**: acquired after **2011-03-31**
  (later adjusted to 2012-03-31 for the gain-realisation extension).
- **Crypto**: acquired after **2021-02-28** (Ökosoziales Steuerreformgesetz
  2022 introduction of crypto into the KESt regime under §27b EStG).

**Altvermögen** (legacy assets, acquired before the cutoff) is
*generally tax-free on disposal* — the gain is outside the income tax
system entirely (except for **wesentliche Beteiligungen** of ≥ 1%
held under the pre-2011 §31 EStG Beteiligungsveräußerung regime;
transition rules in §124b Z 185 EStG bring those into 27.5% even if
acquired pre-2011).

The substrate must therefore know **when** an asset was acquired and
**what kind** it is to determine whether KESt applies at all. The
shipped `:disposal/acquired-on` instant carries this; the per-asset-
class cutoff lookup is provider-side (ADR-101 `:parameter` is the
clean home — one `:parameter` per asset-class × cutoff date).

### 1.3 Individual side — §30 + §30a EStG: ImmoESt

For real estate (Grundstücke und Gebäude) held in **private hands**,
Austria runs a separate flat-rate regime — Immobilienertragsteuer
(ImmoESt) at **30%** on the realised gain (§30a Abs 1 EStG, raised
from 25% effective 2016-01-01 by the StRefG 2015/2016).

The cutoff is **2002-03-31**:

- **Neuvermögen** (acquired after 2002-03-31): 30% on (sale price −
  acquisition cost − certain adjustments). Gain is computed
  per-property; the per-disposal calculation is what the ImmoESt
  provider performs.
- **Altvermögen** (acquired on or before 2002-03-31): the
  **pauschale Anschaffungskosten** method applies — acquisition cost
  is deemed to be **86%** of the sale price (raising effective basis
  to 86% of proceeds), giving a deemed gain of 14% of proceeds, taxed
  at 30% → **effective 4.2% of gross proceeds**. For real estate that
  was rezoned (Umwidmung) from agricultural to building land after
  1987-12-31, the deemed basis is **40%** of proceeds → effective
  **18% × proceeds**.

A **2025 reform** (Umwidmungszuschlag) adds a 30% surcharge on
land-only gains where the property was rezoned to Bauland after
2024-12-31; this is layered on top of the standard ImmoESt and applies
ONLY to the land slice (not buildings) of the realised gain.

### 1.4 Individual side — §30 Abs 2 EStG: Hauptwohnsitzbefreiung

The primary-residence exemption has **two alternative tests**, either
of which exempts the full ImmoESt:

- **2-year test**: the property served as the seller's Hauptwohnsitz
  (main residence) **continuously for at least 2 years** between
  acquisition and disposal, **AND** the main residence was abandoned
  in connection with the sale.
- **5-of-last-10 test**: the property served as the seller's
  Hauptwohnsitz **continuously for at least 5 years** within the
  10-year period ending at the disposal date.

Both prongs require the residence to be the seller's *exclusive*
main residence (no parallel main residence elsewhere). The exemption
covers **only the building**; the land portion is exempt under the
2025 administrative practice unless the land share is
disproportionate (Großgartenregelung — gardens over 1000m² are
partially taxed).

### 1.5 Individual side — §30 Abs 2 Z 2 EStG: Herstellerbefreiung

A *self-built building* (selbst hergestelltes Gebäude) is exempt from
ImmoESt on the building portion, **provided the building was not
used to earn income** in the 10 years preceding disposal (no rental
use). The **land** under the building remains taxable; this is a
building-only exemption. The seller must have personally borne the
construction risk (commissioning a general contractor disqualifies
the exemption — VwGH 2018/15/0001).

### 1.6 Individual side — loss-offset rules (§27 + §30 EStG)

AT has **three watertight loss buckets** — narrower than DE's four:

- **KESt-Vermögen** (§27 + §27a EStG): within the bucket, capital
  losses on shares/funds/derivatives offset capital gains on
  shares/funds/derivatives **AND** offset dividends + interest from
  bonds, **within the same calendar year**. The bucket *excludes*
  interest on bank deposits (§27a Abs 1 Z 1 — the 25% interest
  category is a separate sub-bucket and cannot be offset by capital
  losses; §27 Abs 8 Z 4 EStG).
- **No carryforward** for KESt losses for individual investors
  (private Anleger). Losses unused in the year **expire**. (The 2024
  Steuerreportingverordnung changed the *bank-side reporting* to
  gross-income rather than KESt-amount basis, but did **not**
  introduce a carryforward — losses still expire annually for
  privately-held assets. The 2024-2025 change is mechanical, not
  substantive.)
- **§30 ImmoESt losses** (private real estate): under §30 Abs 7
  EStG, real-estate losses are **first** offset within the same
  calendar year against other §30 gains (real-estate compartment);
  any remainder is **reduced to 60%** and **distributed over 15
  calendar years** (year of loss + 14 following years) against
  **§28 Vermietung und Verpachtung** (rental income). The taxpayer
  may elect immediate offset against §28 in the loss year (full 60%
  in one year) instead of the 15-year spread.

The 55% Begrenzung the prompt references is a **business-side**
rule, not a private-investor rule: when **business-related** capital
losses (KESt-Vermögen held in Betriebsvermögen) are offset against
**other** ordinary income, only 55% of the loss is deductible
(§6 Z 2 lit c EStG, effective 2014 onwards — kept the 55% factor when
the KESt rate moved to 27.5%). The 55% is the **integration ratio**:
55% × marginal rate matches the 27.5% special rate so corporates +
sole proprietors don't gain a tax arbitrage by routing losses through
business books. **This is a Betriebsvermögen rule** the substrate
handles via the EStG/KStG business-side providers, not the
KESt-Endbesteuerung path.

### 1.7 Corporate side — §10 KStG: Beteiligungsertragsbefreiung

Austrian corporate tax rates have been **stepped down** by the
Ökosoziale Steuerreform 2022:

| Year | Rate |
|---|---|
| 2022 | 25% |
| 2023 | 24% |
| 2024 onward | 23% |

The corporate **participation exemption** under §10 KStG has THREE
tiers:

1. **Domestic participation income** (§10 Abs 1 Z 1 KStG):
   dividends + similar profit distributions from Austrian
   Kapitalgesellschaften are **tax-free at the receiving corporation
   level**, **no holding-period or minimum-stake test**. (The
   underlying corporate tax was paid by the distributing entity;
   exempting the recipient avoids double taxation.) **Disposal gains
   are NOT exempt under this tier** — they remain ordinary corporate
   income at the standard rate.

2. **International portfolio dividends** (§10 Abs 1 Z 5–6 KStG):
   dividends from foreign corporations resident in EU/EEA states or
   in treaty-partner states with comprehensive administrative
   assistance — exempt **without holding-period or stake test**, but
   with anti-abuse switch-over rules (§10 Abs 4 if the foreign
   corporation is in a low-tax jurisdiction).

3. **Internationale Schachtelbeteiligung** (§10 Abs 2 KStG): a
   participation of **≥ 10%** in a foreign corporation held for
   **≥ 1 year (12 consecutive months)** — both dividends AND
   **realized capital gains AND losses are tax-neutral by default**
   (§10 Abs 3 KStG).

The critical wrinkle: §10 Abs 3 KStG's tax-neutrality for
Schachtelbeteiligung gains/losses is **opt-out, not opt-in**. The
*default* state is "neither gain nor loss enters taxable income".
The taxpayer may elect the **Option zur Steuerwirksamkeit** in the
acquisition year (irrevocable; covers all future expansions of the
same participation) to make both gains AND losses tax-effective —
the election is symmetric and *cannot* be one-sided. The election is
revocable only within one month of filing the CIT return for the
acquisition year, after which it is locked.

Once the option is exercised:

- **Disposal gains** are taxable at the standard CIT rate (23% from
  2024).
- **Realised losses** are deductible, **but spread over 7 years**
  (§12 Abs 3 Z 2 KStG — Siebentelregelung).

A liquidation or insolvency of the held foreign corporation triggers
recognition of "final asset losses" regardless of the option, with a
5-year clawback against tax-free dividend distributions received in
the lookback window.

### 1.8 Corporate side — §9 KStG: Gruppenbesteuerung

The Austrian group-taxation regime (§9 KStG) allows a Gruppenträger
(group leader) and Gruppenmitglieder (group members) holding > 50%
of voting rights to **consolidate annual taxable results** at the
leader level. The mechanism:

- Each member's annual tax base is computed standalone, then
  **assigned** to the leader.
- Domestic members' losses fully reduce the consolidated base.
- Foreign members' losses are recognised only up to **75% of the
  combined domestic base** for the year; the excess is **carried
  forward** at the leader level (rule introduced 2015 to align with
  Marks & Spencer + AG Steuerreformgesetz 2014).
- **Intra-group capital gains and losses** on disposals of group-
  member shares are **eliminated** at the leader level (the group is
  a single taxpayer for consolidation purposes; intra-group transfers
  do not realise gain). This *is* what the kernel
  `kontor.consolidation` module already does for accounting
  consolidation (ADR-073) — the *tax* consolidation reuses the same
  elimination algorithm but at a different organisational scope.

The interaction with `:disposal` is narrow: a member-on-member
disposal is recorded as a `:disposal` for accounting + audit, but the
**tax** treatment under §9 KStG eliminates the gain. The CGT
provider must read `(kontor.entity/family :group-leader)` to detect
intra-group disposals and short-circuit them. **This is a verifiable
condition on the substrate**; no schema change.

---

## §2. Worked examples

### 2.1 Private investor sells listed shares held 3 years (KESt-Vermögen)

Frau Huber bought 1,000 OMV shares 2022-08 for **EUR 38,000** through
her Erste Bank custody account. She sells them 2026-04 for
**EUR 56,000**.

- Acquired after 2010-12-31 → **Neuvermögen** → KESt-Vermögen.
- Realised gain = EUR 18,000.
- KESt withheld by Erste Bank at 27.5%: **EUR 4,950** (§95 EStG —
  bank is the Abzugsverpflichteter).
- Endbesteuerungswirkung (§97 EStG): no entry on the annual return.
- **Total tax: EUR 4,950, withheld at source, return-free.**

Substrate trace: one `:disposal` with `:kind :sale`,
`:subject-kind :securities-stock`, `:asset-class :at-kest-vermoegen`,
`:proceeds EUR 56,000`, `:basis EUR 38,000`,
`:acquired-on 2022-08-15`, `:loss-bucket :at-kest`. The
`at-kest-cgt-provider` sums KESt-Vermögen disposals in the period,
recognises Erste's withholding via `:inputs :at-kest-prepaid EUR
4,950`, and emits a zero-net liability component for the return
(the Endbesteuerung effectively makes the return Information-only).

### 2.2 Private investor sells primary residence held 12 years

Herr Mayer bought a Vienna apartment 2014-03 for **EUR 320,000** as
his main residence. He has lived there continuously since.
2026-05 he sells it for **EUR 580,000** in connection with a move to
Salzburg.

- Acquired after 2002-03-31 → **Neuvermögen** real estate (would be
  30% ImmoESt absent exemption).
- Hauptwohnsitz: ≥ 5 of last 10 years (12 continuous) AND
  abandonment in connection with sale → **Hauptwohnsitzbefreiung
  (§30 Abs 2 Z 1 EStG) applies**.
- **Total tax: EUR 0.**

Substrate trace: one `:disposal` with `:kind :sale`,
`:subject-kind :real-estate-private`,
`:asset-class :at-immoest-neuvermoegen-residential`,
`:residence? true`, `:acquired-on 2014-03-15`,
`:disposed-on 2026-05-20`, `:proceeds EUR 580,000`,
`:basis EUR 320,000`,
`:exemption-claimed #{:at-hauptwohnsitzbefreiung}`. The
`at-immoest-provider` sees `:at-hauptwohnsitzbefreiung`, verifies
(via a companion routine reading `:audit-doc` Meldezettel +
Grundbuch records) the 5-of-10 + abandonment conditions, and emits a
zero-tax line.

### 2.3 GmbH sells 25% Schachtelbeteiligung in a Swiss AG

Müller-Holding GmbH (Austrian Kapitalgesellschaft) acquired 25% of
SwissOps AG (Zürich) in 2018-02 for **EUR 4,000,000**. In 2026-09 it
sells the stake for **EUR 12,000,000**. The Option zur
Steuerwirksamkeit was **NOT exercised** at acquisition.

- ≥ 10% participation + ≥ 1-year holding + foreign corporation →
  **Internationale Schachtelbeteiligung** under §10 Abs 2 KStG.
- Default (no Option): gains AND losses are tax-neutral (§10 Abs 3
  KStG).
- Realised gain EUR 8,000,000: **tax-free at Müller-Holding**.
- The gain is recognised in the GL (financial statements show the
  EUR 8M); the CIT base is *reduced* by the EUR 8M as a
  permanent-difference exemption.
- **Total CIT: EUR 0 on this gain.**

Substrate trace: one `:disposal` with `:kind :sale`,
`:subject-kind :participation`,
`:asset-class :at-schachtelbeteiligung`, `:subject-form :corp`,
`:ownership-fraction 0.25M`, `:proceeds EUR 12M`, `:basis EUR 4M`,
`:exemption-claimed #{:at-§10-Abs-3-schachtel}`, `:loss-bucket
:at-tax-neutral`. The `at-corporate-cgt-provider` sees the
exemption tag, verifies (`:ownership-fraction ≥ 0.10M` AND
`(- :disposed-on :acquired-on) ≥ 1y` AND the held entity is
foreign), and emits a `:cit-base-deductions [8_000_000M]` adjustment
input — the gain that landed in the GL as ordinary income is now
*removed* from the CIT taxable base at the CIT provider's
`:base-transform :adjustments :deductions` slot. Same composition
pattern as note 113 §5.1 in reverse (DE §8b adds back; AT §10
deducts).

### 2.4 GmbH sells 25% Schachtel WITH Option zur Steuerwirksamkeit

Same scenario as §2.3, but Müller-Holding *exercised* the Option at
acquisition.

- Realised gain EUR 8,000,000 is **taxable** at 23% CIT → **EUR
  1,840,000** owed.
- If instead the disposal had been at a loss of EUR 2,000,000:
  loss recognised, but **spread over 7 years** (§12 Abs 3 Z 2 KStG)
  → EUR 285,714 per year for 7 years.

Substrate trace: `:elective-regime #{:at-§10-tax-effective-option}`
on the disposal. Provider routes to taxable branch. For losses, the
provider emits a 7-year schedule of `:cit-base-deductions` (1/7 of
the loss each year) — a deferred-recognition pattern matching the
CN 5-year-spread pattern from note 133 §5.2.

---

## §3. `:disposal` schema fit — provision-by-provision

| AT provision | Schema field that carries it | Adequacy |
|---|---|---|
| §27a KESt 27.5% on shares/funds/derivatives | `:asset-class :at-kest-vermoegen` | **CLEAN** — provider's `:flat 0.275M` schedule fires on Neuvermögen disposals. |
| §27a KESt 25% on bank-deposit interest | not on `:disposal` — interest is *not* a disposal event | **N/A** — interest is recurring income, handled by the existing `kontor.book/receive-payment` + an interest-recognition provider, not a CGT provider. |
| Altvermögen tax-free for shares/funds | `:asset-class :at-kest-altvermoegen` + `:acquired-on < 2011-01-01` | **CLEAN** — provider gates: if `:asset-class :at-kest-altvermoegen`, tax is zero. |
| §30a ImmoESt 30% on Neuvermögen real estate | `:asset-class :at-immoest-neuvermoegen-residential / -commercial / -land` + `:acquired-on > 2002-03-31` | **CLEAN** — provider's `:flat 0.30M` schedule. |
| §30 Pauschal 4.2% on Altvermögen real estate | `:asset-class :at-immoest-altvermoegen` | **CLEAN** — provider branches: compute `(× proceeds 0.042M)` directly (matches the CN 1-3% deemed-rate branch in note 133 §3.2). |
| §30 18% Pauschal for rezoned Altvermögen | `:asset-class :at-immoest-altvermoegen-umgewidmet` | **CLEAN** — second branch of the same routine. |
| 2025 Umwidmungszuschlag 30% on land slice | `:asset-class` extension + `:cn-umwidmung/post-2024-12-31?` (a per-jurisdiction companion attr) | **PARTIAL — see §4.1** — the surcharge applies only to the land slice; the split needs an additional companion attr. |
| §30 Abs 2 Z 1 Hauptwohnsitzbefreiung | `:residence? true` + `:exemption-claimed :at-hauptwohnsitzbefreiung` + provider verifies via companion routine | **CLEAN** — the 2-of-2 or 5-of-10 test is provider-side discipline; the disposal carries the claim. |
| §30 Abs 2 Z 2 Herstellerbefreiung | `:exemption-claimed :at-herstellerbefreiung` + provider verifies via `:audit-doc` (construction risk attestation) | **CLEAN** — the building-vs-land split needs a companion attr (see §4.2). |
| §27 Abs 8 loss-offset (KESt sub-buckets) | `:loss-bucket :at-kest` | **CLEAN** — provider's compartment fold (note 115 §3.4 pattern). |
| §30 Abs 7 real-estate losses, 60% × 15-year carry to §28 | `:loss-bucket :at-immoest-private` + `:inputs :at-immoest-loss-carryforward [{year → amount}]` | **NEW PATTERN — see §6.2** — the carryforward CROSSES income categories (CGT loss → rental income offset). First substrate case. Handled by inputs-shape extension; no schema change. |
| §10 KStG Domestic Beteiligungsertragsbefreiung (dividends) | not on `:disposal` — dividends are not disposal events | **N/A** — handled by the existing `kontor.book/receive-payment` + an EStG/KStG-aware income recognition rule. |
| §10 Abs 2 Schachtelbeteiligung default tax-neutral | `:asset-class :at-schachtelbeteiligung` + `:ownership-fraction ≥ 0.10M` + `(- :disposed :acquired) ≥ 1y` + `:exemption-claimed :at-§10-Abs-3-schachtel` | **CLEAN** — verifiable from the disposal data alone. The provider issues a `:cit-base-deductions` adjustment input. |
| §10 Option zur Steuerwirksamkeit | `:elective-regime :at-§10-tax-effective-option` | **CLEAN** — provider routes to taxable branch. |
| §12 Abs 3 Z 2 Siebentelregelung (7-year loss spread) | provider emits 7 schedule entries | **CLEAN** — same pattern as CN 5-year-spread / US §453 installment. |
| §9 KStG Gruppenbesteuerung intra-group eliminations | provider reads `(kontor.entity/family :group-leader)` to detect intra-group disposals | **CLEAN** — no schema change; provider-side discipline. The 75% foreign-loss cap is on the EIT-side, not CGT-side. |
| §6 Z 2 lit c business-side 55% Begrenzung | gains/losses are in `Betriebsvermögen` → flow to ordinary EIT income; the 55% factor is an EIT-provider concern | **N/A for the disposal** — this is at the CIT provider's `:base-transform :adjustments :deductions` slot, applied as a *multiplier* on certain loss types. The disposal carries the loss; the provider scales it. |

### 3.2 The 30%-on-Altvermögen vs 4.2%-on-Altvermögen split

Like CN's 1-3% deemed-rate election (note 133 §3.2), the AT
Altvermögen path is a **base substitution** — `taxable = proceeds × 0.14`
(deemed gain = 14% of proceeds), then taxed at 30%. The provider
must know which path to take; the `:asset-class` selector
(`:at-immoest-altvermoegen` vs `:at-immoest-neuvermoegen-*`) is
sufficient. The 2025 Umwidmungszuschlag is *additive* on top of the
base ImmoESt for the land slice — see §4.1.

---

## §4. Data gaps — the concrete extension list

**Zero kernel-schema changes**. Three companion-namespace attrs on the
`kontor.l10n-at.disposal` side (a new namespace introduced by the AT
companion module):

### 4.1 `:at-immoest/land-share` + `:at-umwidmung/post-2024-12-31?`

The 2025 Umwidmungszuschlag applies only to the **land slice** of a
real-estate disposal. The disposal's `:proceeds` is the *total*
proceeds; the substrate cannot derive the land vs building split.
Encode as:

```
:at-immoest/land-share              :bigdec   ; 0..1, the land share of proceeds
:at-umwidmung/post-2024-12-31?      :boolean  ; was the rezoning to Bauland post-cutoff?
```

The `at-immoest-provider` reads both: if rezoned post-cutoff AND
land-share > 0, the surcharge applies as `(* proceeds land-share
30%)` on top of the standard ImmoESt. Cost: 2 companion attrs.

### 4.2 `:at-immoest/building-share` (companion attr for Herstellerbefreiung)

The Herstellerbefreiung exempts the **building only**, not the land.
The disposal's `:proceeds` is the total. The provider must split:

```
:at-immoest/building-share          :bigdec   ; 0..1, the building share of proceeds
```

(Symmetric with `:at-immoest/land-share` — they sum to 1; the
companion can store either and derive the other, or store both for
clarity. **Recommended**: store both for audit-friendliness.) Cost:
1 companion attr (the second one shared with §4.1).

### 4.3 `:at-§10/option-elected-on` + `:at-§10/option-revocable-until`

The §10 KStG Option zur Steuerwirksamkeit has a 1-month revocation
window after the CIT return for the acquisition year is filed. The
substrate may want to surface this for audit; arguably it lives on
the underlying participation, not on the disposal (the disposal is
the *consequence* of the election, years later). But for an
indirect-disposal trail (a chain of acquisitions extending the same
participation), the election date is the substrate-knowable fact
that defines whether the disposal is taxable.

**Recommended placement**: on the kernel `:entity` or `:partner` of
the held foreign corporation as a companion attr (`:at-l10n.partner/
§10-option-elected-on :instant`), NOT on the disposal. The disposal's
`:elective-regime :at-§10-tax-effective-option` is the
denormalisation; the underlying election date lives with the
partner. Cost: 1 partner-side companion attr; zero on the disposal.

### 4.4 (Optional) `:at-immoest/regelbesteuerungsoption?` — Regelbesteuerung election

When a low-marginal-rate individual elects out of the 30% flat into
their marginal rate (§30a Abs 2 EStG opt-out), the disposal carries
the election. Cost: 1 boolean companion attr; can be folded into the
existing `:elective-regime` set as `:at-regelbesteuerungsoption`.
**Recommended**: fold into `:elective-regime` (cardinality-many) —
zero attrs.

**Totals**: 3 companion attrs on the disposal (`:at-immoest/land-share`,
`:at-immoest/building-share`, `:at-umwidmung/post-2024-12-31?`), 1 on
the partner (`:at-l10n.partner/§10-option-elected-on`), 0 kernel
schema changes.

---

## §5. `at-cgt-provider` sketch — TWO active providers + ONE null

### 5.1 `at-kest-cgt-provider` — feeds the (mostly informational) E1kv return

Single `PeriodTaxProvider`, `:kind :capital-gains-tax`,
`:authority :at-finanzamt`. Most KESt-Endbesteuerung dispositions
arrive **already withheld** (the Austrian bank is the Abzugsverpflichteter);
the provider's role is to (a) aggregate the year's gains/losses for
the informational E1kv attachment, (b) verify the bank-withheld
amount matches the disposal data, (c) handle offshore dispositions
where no Austrian bank withheld.

```clojure
;; Conceptual — no code change in this note.
(defn at-kest-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :at-kest-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals      (disposal/disposals-in-period conn period entity)
            kest-disposals (filter #(= :at-kest-vermoegen (:disposal/asset-class %))
                                   disposals)
            ;; KESt-Vermögen 27.5% bucket
            kest-net       (reduce-bucket-with-loss-cap kest-disposals)  ;; same-year offset; no carry
            kest-tax       (* (max 0M kest-net) 0.275M)
            ;; bank-withheld via inputs
            prepaid        (or (:at-kest-prepaid inputs) (money/zero :EUR))
            regelbest?     (contains? (set (mapcat :disposal/elective-regime kest-disposals))
                                      :at-regelbesteuerungsoption)]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :at-finanzamt}
          :functional-commodity :EUR
          :components
          [{:kind :capital-gains-tax :authority :at-finanzamt
            :base (max 0M kest-net)
            :schedule (ts/flat 0.275M)
            :gross-liability kest-tax
            :liability (- kest-tax prepaid)
            :prepaid prepaid
            :regime (if regelbest? :regelbesteuerung :endbesteuerung)
            :line-items
            [{:line :at-kest-net :value kest-net}
             {:line :at-kest-bank-withheld :value prepaid}]}]})))))
```

When `:at-regelbesteuerungsoption` is set, the provider should emit
a `:pit-base-additions` adjustment input that folds the KESt-Vermögen
net into the PIT marginal-rate base instead of the 27.5% flat
component. Same pattern as DE Günstigerprüfung (note 113 §5.2).

### 5.2 `at-immoest-provider` — feeds the (mostly informational) E1 attachment

ImmoESt is **self-withheld** in most cases (the notary/Parteienvertreter
who closes the deal is the Selbstberechner; §30b EStG). When self-
withheld, the tax is final; the disposal feeds an information-only
component to the annual return. When **not** self-withheld
(rare — foreign-resident sellers, certain structured dispositions),
the provider's component drives an actual payment.

Same skeleton as `at-kest-cgt-provider` with three branches:

- **Hauptwohnsitzbefreiung claimed**: short-circuit to zero.
- **Herstellerbefreiung claimed**: split via `:at-immoest/building-share`,
  exempt building portion, tax land at 30%.
- **Altvermögen**: base = `(* proceeds 0.14M)` (or `(* proceeds
  0.60M)` for rezoned pre-1988 land), tax at 30%.
- **Neuvermögen**: base = proceeds − basis, tax at 30%.
- **Umwidmungszuschlag** (if `:at-umwidmung/post-2024-12-31?`):
  additional 30% on `(* proceeds land-share)`.

Loss carryforward across years: the provider reads `:inputs
:at-immoest-loss-carryforward` (a per-tax-year map of remaining
60%-distributed losses) and offsets the year's §28 rental income
contribution; **the rental income is NOT in the disposal stream**, so
the provider writes the carryforward to the PIT provider's
`:pit-base-deductions` slot rather than a CGT component.

### 5.3 `at-corporate-cgt-provider` — null in 99% of cases

For most corporate disposals, the gain lands in the GL as ordinary
income (the asset is in Betriebsvermögen) and the AT-CIT provider
sweeps it at 23%. The CGT provider exists for **two narrow
exception families**:

1. **§10 KStG Schachtelbeteiligung exemption**: when
   `:exemption-claimed :at-§10-Abs-3-schachtel` AND the provider's
   verification routine confirms the conditions, the provider emits
   a `:cit-base-deductions [gain]` adjustment input — the gain that
   landed in the GL as ordinary income is *removed* from the CIT
   taxable base.

2. **§10 Option-elected disposals with losses**: when
   `:elective-regime :at-§10-tax-effective-option` AND the disposal
   produces a loss, the provider emits a 7-year schedule of
   `:cit-base-deductions` (1/7 of the loss each year, Siebentelregelung).

3. **§9 Gruppenbesteuerung intra-group eliminations**: when the
   disposal counterparty is in the same Gruppe (verifiable via
   `kontor.entity/family :group-leader`), the gain is *eliminated*
   at the leader level — provider emits a
   `:cit-base-deductions [gain]` for the consolidated group return,
   and skips the standalone member-level recognition.

### 5.4 Summary

| Provider | Components per return | Substrate stress |
|---|---|---|
| `at-kest-cgt-provider` | 1 (multi-line) | none |
| `at-immoest-provider` | 1 (multi-line) | one — the 15-year 60% carryforward to §28 income (§6.2) |
| `at-corporate-cgt-provider` | 0–N (exceptions only) | none — adjustment-input pattern (note 113 §5.1) |

Total: **3 providers, 0 kernel schema changes, 3 disposal-side
companion attrs + 1 partner-side companion attr, 1 carryforward-
shape extension on `:inputs`**. Within the conservative posture of
notes 107 + 113 + 115 + 133.

---

## §6. Cross-cutting design notes

### 6.1 The §10 KStG "tax-neutral by default" inversion

Most participation exemptions in DE/UK/US/JP/CA are **opt-in to
exemption** — you start at taxable and elect into exempt status by
meeting conditions. AT §10 Abs 3 inverts this for
Schachtelbeteiligungen: **tax-neutral is the default**, and you elect
*out* (into taxable) via the Option zur Steuerwirksamkeit. The
elective-regime keyword `:at-§10-tax-effective-option` therefore
signals **the opt-out**, NOT the exemption claim. The exemption
itself is signalled by `:at-§10-Abs-3-schachtel` in
`:exemption-claimed` — set on every Schachtelbeteiligung disposal
*unless* the Option is in force.

This is a **convention** the AT companion documents, not a substrate
mechanic. The provider's verification routine:

```
(if (contains? (:disposal/elective-regime d) :at-§10-tax-effective-option)
  :route-to-taxable
  (if (and (contains? (:disposal/exemption-claimed d) :at-§10-Abs-3-schachtel)
           (>= (:disposal/ownership-fraction d) 0.10M)
           (>= (- (:disposal/disposed-on d) (:disposal/acquired-on d)) 1-year)
           (foreign-corp? (:disposal/subject d)))
    :route-to-exempt
    :route-to-ordinary-income))
```

### 6.2 The §30 Abs 7 EStG 60%/15-year carryforward — the first cross-category CGT loss

The DE / UK / US / JP / CA precedents all keep CGT losses *inside*
the CGT compartment (or, at most, allow a limited offset against
ordinary income with a per-year cap — US's $3,000/year).
AT §30 Abs 7 EStG is the **first jurisdiction in the kontor
substrate** where a CGT loss flows into a DIFFERENT income category
(Vermietung und Verpachtung §28 EStG) with a discounting factor
(60%) and a multi-year spread (15 years).

This is *not* a substrate problem — the existing pattern of "CGT
provider emits a `:pit-base-deductions` adjustment input to the PIT
provider" (note 113 §5.2; note 115 §5; note 133 §5.2) handles it.
But it is the first case where the *destination slot* is income-
category-specific (`§28` Vermietung, not the PIT gross income
generally). The provider needs to write to
`:pit-base-deductions {:§28-vermietung [amount]}` rather than the
generic `:pit-base-deductions [amount]`; the AT PIT provider then
applies the deduction against §28 income only, capping at the §28
amount for the year.

**Substrate stress**: the `:inputs :pit-base-deductions` shape goes
from `[Money]` to `{income-category-keyword [Money]}` for AT. This
is **opaque to the kernel** — `:inputs` is a free-form map per
ADR-099. The AT companion documents the convention. **No kernel
change**.

The 15-year carryforward state lives in `:inputs
:at-immoest-loss-carryforward {year → remaining-amount}` — same
shape as US's `:capital-loss-carryforward {:short :long}` from note
102 §3a, just per-year-keyed instead of per-bucket. **No kernel
change**.

### 6.3 The KESt "no carryforward, year-end zero" rule

Unlike the substrate's `:capital-loss-carryforward` convention, AT
KESt for **private** investors does *not* carry losses forward. The
Verlustverrechnungstopf at the Austrian bank resets to zero every
January 1. This is the second jurisdiction (after CN individuals) where
the substrate's `:capital-loss-carryforward` slot is **deliberately
unused**.

Provider-side discipline: do not write a `:capital-loss-carryforward`
output for AT individuals' KESt bucket. Document in the
`at-kest-cgt-provider` docstring with citation to §27 Abs 8 EStG.

### 6.4 The "Bank withheld via :inputs" composition

Both `at-kest-cgt-provider` and `at-immoest-provider` rely on the
consumer feeding bank-withheld / notary-withheld amounts via
`:inputs :at-kest-prepaid` / `:inputs :at-immoest-prepaid`. The
substrate convention is the same as DE Kapitalertragsteuer-Prepaid
(note 113 §5.2's `:de-kapest-prepaid inputs`); the disposal data
itself does NOT carry the withheld amount (the withholding is a
*payment event*, not a *disposal event*). The audit trail rides
through `:realizing-tx` (the GL transaction posts the bank's withhold
to a separate tax-payable account) + `:audit-doc` (the bank's
KESt-Bescheinigung is attached).

This composition keeps the disposal record clean — one event, one
record — and matches the substrate's "disposal carries the *facts*
of the disposition; payment + withholding ride separately" doctrine
documented in `kontor-disposal/schema.clj:9-14`.

---

## §7. Sources

AT statutes (ris.bka.gv.at / jusline.at — public):

- §27 EStG 1988 (Einkünfte aus Kapitalvermögen):
  https://www.jusline.at/gesetz/estg/paragraf/27
- §27a EStG 1988 (Besonderer Steuersatz und Bemessungsgrundlage):
  https://www.jusline.at/gesetz/estg/paragraf/27a
- §27b EStG 1988 (Kryptowährungen — added by Ökosoziales
  Steuerreformgesetz 2022).
- §30 EStG 1988 (Private Grundstücksveräußerungen):
  https://www.jusline.at/gesetz/estg/paragraf/30
- §30a EStG 1988 (Besonderer Steuersatz für Einkünfte aus
  Grundstücksveräußerungen).
- §30b EStG 1988 (Immobilienertragsteuer — Selbstberechnung +
  Entrichtung).
- §31 EStG 1988 (Spekulationsgeschäfte — narrowed since 2012 reform):
  https://www.jusline.at/gesetz/estg/paragraf/31
- §95 EStG 1988 (KESt-Schuldner und Abzugsverpflichteter).
- §97 EStG 1988 (Endbesteuerung mit Kapitalertragsteuer).
- §10 KStG 1988 (Befreiung für Beteiligungserträge und
  internationale Schachtelbeteiligungen):
  https://www.jusline.at/gesetz/kstg/paragraf/10
- §9 KStG 1988 (Gruppenbesteuerung).
- §12 Abs 3 Z 2 KStG 1988 (Siebentelregelung — 7-year spread for
  Schachtel-option losses).
- §6 Z 2 lit c EStG 1988 (Betriebsvermögen 55% Begrenzung on
  KESt-Vermögen losses against ordinary income).

BMF (Bundesministerium für Finanzen) guidance:

- BMF "Capital gains or income from realised value increases"
  (English overview):
  https://www.bmf.gv.at/en/topics/taxation/Income-Taxation-on-savings-and-investments/Capital-gains-or-income-from-realised-value-increases.html
- BMF "Verluste aus der Veräußerung von Kapitalvermögen und
  Derivaten":
  https://www.bmf.gv.at/themen/steuern/sparen-veranlagen/verluste-aus-veraeusserung-von-kapitalvermoegen-und-derivaten.html
- BMF Tax treatment of crypto-assets (§27b EStG):
  https://www.bmf.gv.at/en/topics/taxation/Tax-treatment-of-crypto-assets.html
- BMF Möglichkeiten zur Verlustverwertung:
  https://www.bmf.gv.at/themen/steuern/fuer-unternehmen/einkommensteuer/verlustverwertung.html
- Findok BMF (the authoritative searchable database):
  https://findok.bmf.gv.at/

Practitioner commentary (Linde-Verlag tier / WKO / professional firms):

- WKO "Körperschaftsteuer (KÖSt)" — current rates 25→24→23%:
  https://www.wko.at/steuern/koest-koerperschaftsteuer
- WKO "Internationale Schachtelbeteiligung":
  https://www.wko.at/steuern/internationale-schachtelbeteiligung
- WKO "Die steuerliche Verlustverwertung":
  https://www.wko.at/steuern/steuerliche-verlustverwertung
- WKO "Besteuerung von Kapitalvermögen":
  https://www.wko.at/oe/information-consulting/finanzdienstleister/besteuerung-kapitalvermoegen.pdf
- Erste Group "Capital gains tax — Securities know how":
  https://www.erstegroup.com/en/investments/service-knowledge/services/securities-know-how/capital-gains-tax
- ÖSV "Sachliche Steuerbefreiungen (§§ 7 und 10 KStG 1988)" Teile 1 + 2:
  https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-1/
  https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-2/
- FreeFinance "Immobilienertragsteuer (ImmoESt) — Einfach erklärt":
  https://www.freefinance.at/steuern/immobilienertragsteuer.html
- Brandauer Rechtsanwälte "Umwidmungszuschlag 2025: 30 % zusätzlich
  zur ImmoESt":
  https://brandauer-rechtsanwaelte.at/2025/10/06/umwidmung-in-bauland-der-neue-30-umwidmungszuschlag-in-oesterreich/
- ICON Wirtschaftstreuhand "Auslandsbeteiligungen | Kein endgültiger
  Vermögensverlust bei Verkauf!":
  https://www.icon.at/news/detail/auslandsbeteiligungen-kein-endgueltiger-vermoegensverlust-bei-verkauf
- Chambers and Partners "Corporate Tax 2025 — Austria":
  https://practiceguides.chambers.com/practice-guides/corporate-tax-2025/austria
- ICLG "Corporate Tax Laws and Regulations Report 2025: Austria":
  https://iclg.com/practice-areas/corporate-tax-laws-and-regulations/austria
- PwC Worldwide Tax Summaries — Austria Individual / Corporate
  Income Determination:
  https://taxsummaries.pwc.com/austria/individual/income-determination
  https://taxsummaries.pwc.com/austria/corporate/income-determination
- Schelhammer Capital "KESt-Verlustausgleich in Österreich" (2025
  update on the Verlustverrechnungstopf reset):
  https://schelhammer.at/home/newsarticle/kest-verlustausgleich-in-oesterreich-so-nuetzen-sie-verluste-steuerlich-25/
- BTV "Verlustausgleich in Österreich — Änderungen & Beispiele"
  (2024 reporting reform):
  https://btv.at/wissen/die-neue-verlustausgleichbescheinigung/
- Leopold Steuerberatung "Steuer Update im Sommer 2025":
  https://www.leopold-steuerberatung.at/steuer-update-im-sommer-2025/

kontor substrate cited (file:line):

- `modules/disposal/src/kontor/disposal/schema.clj:62-309` — the
  shipped `:disposal` schema this note assesses.
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor for the
  27.5% / 30% / 23% flat rates.
- `src/kontor/period_tax_provider.clj:44-61` — `period-tax-kinds`
  enum; `:capital-gains-tax` already in.
- `src/kontor/entity.clj` (ADR-031) — `kontor.entity/family` walk for
  the §9 KStG intra-group disposal detection.
- `src/kontor/consolidation.clj` (ADR-073) — the consolidation
  algorithm the §9 KStG tax-side reuses at a different organisational
  scope (Gruppe vs financial-statements-group).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  `:disposal` schema design this note's AT companion extends.
- `doc/research/113-de-cgt-fit.md` §5.1 — the "CGT provider feeds CIT
  via base-deduction adjustment input" pattern reused for the AT §10
  KStG exemption (in the opposite direction from §8b KStG).
- `doc/research/115-jp-cgt-fit.md` §5 — the multi-component single-
  provider pattern reused for `at-kest-cgt-provider` and
  `at-immoest-provider`.
- `doc/research/133-cn-cgt-fit.md` §3.2 + §5.3 — the deemed-rate
  base-substitution pattern reused for AT Altvermögen pauschale
  (4.2% / 18%).

---

End of note 134.

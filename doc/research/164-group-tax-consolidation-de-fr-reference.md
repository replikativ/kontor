---
date: 2026-05-25
title: 164 — Group tax consolidation: DE Organschaft + FR Intégration fiscale — statutory anatomy + design-relevant patterns
status: research — primary input to Gap #8 (group-tax consolidation) substrate design; no code
audience: maintainer + the gap-#8 design-call agent + the US/JP cross-reference agent (note 165)
inputs:
  - https://www.gesetze-im-internet.de/kstg_1977/ (KStG, public-domain federal statute)
  - https://www.gesetze-im-internet.de/gewstg/ (GewStG, public-domain)
  - https://www.gesetze-im-internet.de/solzg_1995/ (SolZG)
  - https://www.gesetze-im-internet.de/ustg_1980/ (UStG — only §2 cited for VAT-Organschaft scope demarcation)
  - https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069577/ (CGI — Code général des impôts)
  - https://bofip.impots.gouv.fr/ (BOFIP, administrative doctrine, Etalab 2.0)
  - https://www.bundesfinanzministerium.de/ (BMF-Schreiben, public-domain administrative guidance)
  - PwC Worldwide Tax Summaries (DE + FR chapters, paraphrase only)
  - KPMG / EY 2025 corporate-tax guides (paraphrase only)
---

# 164 — Group tax consolidation: DE Organschaft + FR Intégration fiscale

## Purpose

This is the §3.1 of Gap #8 (group-tax consolidation). kontor's per-entity
`PeriodTaxProvider` (ADR-099) and ADR-101 statute-as-data substrate currently
assume **one legal entity per `compute-tax` call**: every CIT/CGT/investment-income
provider takes `:tax-unit` + `:period` + an entity scope, marginalizes its
own facts via `kontor.statute/apply-provisions`, and emits one provision/payment
posting pair. Real corporate groups elect regimes that **compute one consolidated
tax base across many legal entities** — losses offset between members, intercompany
flows are tax-neutralized, and the parent files for the whole group. The two
oldest, most-cited civil-law regimes are Germany's *Organschaft* (KStG §§14-19;
GewStG §2 Abs. 2 Satz 2) and France's *Intégration fiscale* (CGI Art. 223 A ff.).

This note is the deep statutory read of both, plus the cross-regime structural
synthesis (§3) that surfaces what kontor needs to model. **No code, no
prescription** — Phase 3 fit-and-design follows in note 166 (internal gap
analysis) and the Gap #8 ADR.

The companion `kontor.consolidation` (ADR-073) does **financial** consolidation
(IAS 21 FX translation + intercompany elimination for IFRS/GAAP reporting).
*Tax* consolidation is structurally adjacent but legally distinct: different
elimination rules (tax-neutral only for elections; financial-consolidated
always), different attribution rules for losses (pre-election losses are
frozen by tax law; financial consolidation has no such freeze), regime-specific
election semantics with statutory minimum durations (DE EAV ≥5 yr; FR annual
option). §6 surfaces three substrate options without picking one.

---

## §1. DE Organschaft (Körperschaft- + Gewerbesteuer)

### 1.1 Three flavors, one name

German law uses the word *Organschaft* across three legally separate regimes,
each anchored in a different code:

| Flavor | Statute anchor | Tax | In scope here? |
|---|---|---|---|
| **körperschaftsteuerliche Organschaft** | KStG §§14–19 | KSt (federal CIT 15 %) | Yes |
| **gewerbesteuerliche Organschaft** | GewStG §2 Abs. 2 Satz 2 | GewSt (municipal trade tax) | Yes |
| **umsatzsteuerliche Organschaft** | UStG §2 Abs. 2 Nr. 2 | USt (VAT) | No — VAT, out of scope |

The CIT case (KStG §§14-19) and the trade-tax case (GewStG §2 Abs. 2 Satz 2)
share substantially the same requirements — historical case law (BFH I R 56/93,
"Gleichklang") confirms that meeting the KSt-Organschaft is sufficient for the
GewSt-Organschaft too, since GewStG §2 Abs. 2 Satz 2 explicitly cross-references
"die Voraussetzungen der §§ 14 bis 19 des Körperschaftsteuergesetzes". So in
practice a single set of conditions creates a *unified* CIT + trade-tax
Organschaft (the so-called "kombinierte ertragsteuerliche Organschaft"). The
VAT Organschaft is a separate election with separate requirements (Eingliederung
nach UStG §2 Abs. 2 Nr. 2 needs **financial, economic, and organisational**
integration of the Organgesellschaft into the Organträger) and is irrelevant
to CIT computation. We mention it only so the substrate's `:tax-group/regime`
modelling does not collide later — VAT-Organschaft membership and CIT-Organschaft
membership are **independent** sets in the same enterprise.

### 1.2 Requirements (KStG §14 Abs. 1)

For an Organschaft to be recognised by the Finanzamt, all six of the following
must hold throughout the entire Organschaft FY (KStG §14 Abs. 1 Nr. 1 –
Nr. 5 + §17 for GmbH/AG distinction):

1. **Organträger** is a domestic single trader (gewerbliches Unternehmen) or
   a corporation. Foreign Organträger with a domestic permanent establishment
   acceptable since 2013 (KStG §14 Abs. 1 Satz 1 Nr. 2 Satz 4-7 — codifying
   the *Marks & Spencer* / *Felixstowe* EU-law fallout).
2. **Organgesellschaft** is a Kapitalgesellschaft (AG, GmbH, KGaA, SE) with
   its seat (Sitz) **and** place of management (Geschäftsleitung) in Germany
   (KStG §14 Abs. 1 Satz 1; the "doppelter Inlandsbezug").
3. **Financial integration** (finanzielle Eingliederung) — the Organträger
   directly or indirectly holds the majority of voting rights in the
   Organgesellschaft **from the beginning of the Organgesellschaft's FY**
   (KStG §14 Abs. 1 Satz 1 Nr. 1). "Majority of voting rights" is normally
   >50 % of voting capital; minority-voting share classes are weighted at
   their voting share, not at par. Mid-year acquisition cannot make Year 1
   an Organschaft year — at best Year 2 starts the regime.
4. **Ergebnisabführungsvertrag (EAV)** — a profit-and-loss-transfer agreement
   under §§291 ff. AktG (Aktiengesetz). Must be:
   - In writing (§293 Abs. 3 AktG).
   - Approved by ≥¾ of the GmbH/AG shareholders' meeting of the Organgesellschaft
     (§293 Abs. 1 AktG; and of the Organträger if AG).
   - Registered with the Handelsregister (Commercial Register) of the
     Organgesellschaft (§294 AktG). Effective only **on the date of registration**.
   - Cover the **complete** profit/loss, not a percentage (Vollabführung).
   - Provide for the Organträger to **assume any loss** of the Organgesellschaft
     during the EAV term (KStG §17 Satz 2 Nr. 2 — Verlustübernahmeklausel mit
     dynamischem Verweis on §302 AktG; the BMF-Schreiben of 24.03.2021 reset
     the dynamic-vs-static interpretation).
5. **Minimum term** (KStG §14 Abs. 1 Satz 1 Nr. 3) — the EAV must be entered
   into for **at least 5 years** (full Zeitjahre, not FYs) **and must actually
   be carried out** for that duration. Early termination "for cause"
   (wichtiger Grund — sale of the subsidiary, insolvency, restructuring per
   §29 UmwStG) preserves the elapsed years' recognition; ordinary termination
   before year 5 retroactively voids the regime ab initio (rückwirkende
   Versagung) — BMF-Schreiben 26.08.2003, BStBl. I 2003, 437.
6. **Actual execution** (tatsächliche Durchführung; KStG §14 Abs. 1 Satz 1
   Nr. 3 letzter Halbsatz) — the EAV must be carried out in practice every
   year: the Organgesellschaft must actually transfer its profit (or have its
   loss assumed) by the balance-sheet date plus the time it takes to draw up
   the financial statements (typically 8 months — §264 HGB). Failure to
   transfer in one year breaks the Organschaft for that year and, if the
   defect is not cured, retroactively for the rest of the term.

The combination of (4) + (5) + (6) is the strict feature distinguishing DE
from most other tax-consolidation regimes: there is a *registered private-law
contract* with statutory minimum duration. Other systems (FR annual option,
US §1501 consolidated-return election, UK group-relief surrender) elect
through tax filings only.

### 1.3 Mechanics (KStG §14 Abs. 1, §15)

#### 1.3.1 Income attribution

The Organgesellschaft computes its own `zu versteuerndes Einkommen` (zvE)
under standard CIT rules (Handelsbilanz → Steuerbilanz adjustments, §8 KStG
modifications, GewSt-Hinzurechnungen and -Kürzungen for the trade-tax
parallel base). This zvE — positive OR negative — is then **attributed** to
the Organträger under KStG §14 Abs. 1 Satz 1 ("dem Organträger zuzurechnen").
The Organgesellschaft itself declares zvE of zero on its Körperschaftsteuer-Erklärung
KSt 1, with a memo line for the attributed amount in Anlage OG.

The Organträger consolidates: own zvE ± Σ attributed zvE of all
Organgesellschaften = the Organträger's tax base. KSt 15 % + Soli 5.5 % of KSt
applies to this single base. The Organträger files one KSt 1 return for the
whole group's CIT.

#### 1.3.2 Trade-tax parallel

For GewSt (GewStG §2 Abs. 2 Satz 2), the Organgesellschaft is treated as a
**Betriebsstätte** (permanent establishment) of the Organträger. So the
Organträger's *Gewerbeertrag* is its own + each Organgesellschaft's, with
§8 Hinzurechnungen and §9 Kürzungen computed at the **member level** and
then summed. The Hebesatz applied is **the Organträger's municipal Hebesatz**
for own activity AND a *weighted average* (Zerlegung) where Organgesellschaften
sit in different municipalities — GewStG §28 ff. governs the Zerlegung
(allocation across municipalities by payroll, with §29 the wage-sum
denominator). Each municipality issues its own Bescheid against the
Organträger; the Steuermessbetrag itself is set once federally.

#### 1.3.3 Intercompany dividends

Inside the Organschaft, the §8b KStG participation exemption is **switched
off** for dividends *from* the Organgesellschaft *to* the Organträger (KStG
§15 Satz 1 Nr. 2 — the "Bruttomethode") because the Organträger has already
been taxed on the Organgesellschaft's full income via attribution. The
formal dividend flow is therefore tax-neutral by attribution, not by
exemption. (Outside the Organschaft, the standard §8b applies: 95 % exempt
for dividends from a ≥10 % CIT participation held at the start of the year.)

For the GewSt parallel, KStG §15 Satz 1 Nr. 2 + GewStG §9 Nr. 2a are
co-ordinated so the same neutralisation applies.

#### 1.3.4 KStG §15 — the "Sonderregelungen"

KStG §15 carves out items that must be **lifted back up** to the Organträger
level so they're tested with the Organträger's facts, not the Organgesellschaft's:

- KStG §15 Satz 1 Nr. 1 — pre-Organschaft losses of the Organgesellschaft
  (§10d EStG carryforwards) are **frozen at the Organgesellschaft** and may
  not flow through to the Organträger. They are usable only against the
  Organgesellschaft's own income in the post-Organschaft period.
- KStG §15 Satz 1 Nr. 2 — §8b dividends and gains, §3 Nr. 40 EStG (Teileinkünfteverfahren
  for natural-person Organträger), and §4 Abs. 6 UmwStG items: the
  Organgesellschaft's gross amount is attributed; the exemption/Teileinkünfte
  test is applied at the Organträger level (Bruttomethode). This is the
  rule that makes a natural-person GmbH-shareholder Organträger able to use
  Teileinkünfteverfahren (60 %) on the Organgesellschaft's portfolio share
  gains.
- KStG §15 Satz 1 Nr. 2a — §4h EStG / §8a KStG **Zinsschranke** (the
  interest-deduction barrier) is computed at the **Organkreis level** — the
  Organkreis is one "Betrieb" for interest-barrier purposes. The €3M
  Freigrenze applies once to the whole Organkreis, not per member.
- KStG §15 Satz 1 Nr. 3 — Spendenabzug (donations) is tested at the
  Organträger level using the Organträger's `Gesamtbetrag der Einkünfte` and
  the limits of §9 Abs. 1 Nr. 2 KStG (20 % of GdE, or 4 ‰ of payroll +
  turnover).
- KStG §15 Satz 1 Nr. 4 — Investitionsabzugsbeträge (§7g EStG) inside the
  Organgesellschaft are attributed via the §14 mechanic, not the §15
  Bruttomethode.

The §15 Bruttomethode is the subtle bit: the kernel's `compute-tax` must
distinguish "amount that's already taxed at Organgesellschaft and just
attributed" vs "amount that's lifted gross to Organträger for the
exemption/limit test there." This is the strongest argument that group-tax
is not just "sum the members."

### 1.4 Loss treatment

#### 1.4.1 Pre-Organschaft losses (Verlustabzugsbeschränkung)

KStG §15 Satz 1 Nr. 1 — pre-Organschaft losses (i.e. §10d EStG carryforwards
of the Organgesellschaft existing at the start of the Organschaft FY) are
**frozen at the Organgesellschaft**:

- They do NOT flow up to the Organträger during the Organschaft.
- They CAN be used against the Organgesellschaft's own post-Organschaft
  income (after the EAV ends) — Organgesellschaft income during the
  Organschaft is zero by attribution, so there is nothing for them to offset
  during the regime.
- §8c / §8d KStG (shareholder-change loss-forfeit) still applies — a
  controlled change in Organträger ownership ≥ 50 % can wipe these frozen
  losses.

The same freeze applies to **GewSt carryforwards** under GewStG §10a Satz 3
(§§14 ff. KStG referenced).

#### 1.4.2 In-Organschaft losses

A loss of any Organgesellschaft is attributed to the Organträger via §14
just like a profit. So Organgesellschaft A's −€1M loss offsets Organträger's
+€2M and Organgesellschaft B's +€500k inside the same year. **No** separate
group-loss carryforward exists — what carries forward is the **Organträger's**
own §10d EStG balance, which embeds all attributed amounts year-by-year.

#### 1.4.3 Post-Organschaft losses

When the Organschaft ends, the Organgesellschaft resumes filing for its own
income. Any **pre-Organschaft losses** that were frozen become usable again
against future Organgesellschaft income (subject to §8c/§8d as always).
Losses **incurred during** the Organschaft sit at the Organträger and stay
there — they are not redistributed back on exit.

This asymmetry (frozen on entry, attributed on exit) is the most
non-trivial bit to model: a `:loss-bucket` scoped to "pre-Organschaft
period of this Organgesellschaft" is what's needed; ADR-101 already
generalises loss buckets via `:provision/op :base-deduct` over a
`:tax-unit`-scoped pool.

### 1.5 Sub-Organschaft chains (Mittelbare Organschaft)

KStG §14 Abs. 1 Satz 1 Nr. 1 explicitly allows the financial integration
requirement to be satisfied through indirect holding ("mittelbar"). Two
patterns:

1. **Direct chain.** Organträger T holds 100 % of GmbH A, which holds 60 %
   of GmbH B. T's financial integration to B is *mittelbar* via A. T can
   sign an EAV with B directly — A is not party. KSt is then attributed
   B → T, skipping A (which has its own separate EAV with T or not). This
   is the "atypische Organschaft" since the EAV chain doesn't follow the
   share chain.
2. **Sub-Organschaft.** T → A is one Organschaft (EAV T+A); A → B is
   another Organschaft (EAV A+B). B's income flows to A by attribution; A
   is treated as Organgesellschaft of T and Organträger of B simultaneously.
   B's income reaches T via the two attributions. This requires **two** EAVs
   and is structurally cleaner.

Both are recognised by the Finanzamt. BMF-Schreiben 28.03.2024, BStBl. I
2024 (the consolidated Anwendungsschreiben on §§14-19 KStG, replacing the
2003 version) confirms both chains. Note 165 covers the US §1501
parallel — which requires direct ownership chain — for cross-jurisdiction
contrast.

### 1.6 Termination / failure to meet requirements

KStG §14 Abs. 1 Satz 1 Nr. 3 — the 5-year minimum and actual-execution
requirements interact aggressively:

- **Ordinary termination before year 5** retroactively voids ALL prior
  years (rückwirkende Versagung). Every Organgesellschaft files
  back-corrected returns as a standalone Kapitalgesellschaft for the
  ostensibly-Organschaft years; Organträger does the same. KSt and GewSt
  back-assessments + 6 % p.a. interest (AO §238).
- **Termination "for cause"** preserves the elapsed years (BMF-Schreiben
  03.04.2019, BStBl. I 2019, 467 — list of accepted Gründe: sale,
  insolvency, restructuring per §§ 1, 11 UmwStG, capital reduction).
- **One-year defect** (EAV not actually executed in year N) breaks the
  Organschaft for year N only if cured promptly; uncured, the defect
  retroactively breaks the regime ab initio. The BFH (I R 19/22, judgment
  of 23.08.2023) tightened this — a mere accounting omission can void the
  regime if the omission is not corrected within the statutory window.

This is the biggest "audit risk" in DE group tax: a missed EAV transfer
caught five years later can erase a decade of group tax filings. kontor's
substrate needs to model the *contingent* nature of the regime: each year's
attribution is conditional on Year 5 + actual-execution being met.

### 1.7 Pillar Two interactions (2026-relevant)

The German *Mindestbesteuerungsgesetz* (MinStG; BGBl. I 2023 Nr. 397,
27.12.2023) transposed EU Directive 2022/2523 (the Pillar Two GloBE rules)
with effect from FY 2024. Key Organschaft interactions:

- **GloBE consolidation group** ≠ Organschaft. The MinStG group is built
  on the IFRS / national-GAAP **consolidated financial statements** (the
  "Konzernabschluss"), not the EAV chain. Subsidiaries outside the
  Organschaft but inside the financial consolidation are still in the
  GloBE group.
- The **Top-Up Tax** is computed on a per-jurisdiction basis: aggregate
  GloBE income of all DE-resident "Konstituierende Einheiten" gives the DE
  ETR, which is then topped up to 15 % if low. The Organschaft attribution
  is *invisible* to GloBE — the calculation looks through to the
  Organgesellschaften individually.
- **Filing**: the MinStG return is filed by the **Berichtspflichtige
  Geschäftseinheit** (usually the ultimate German parent), which may be
  but doesn't have to be the Organträger. MinStG §3 + §75.

So Pillar Two is **adjacent** to the Organschaft, not a layer on top of
it. Note 165's US §1501 / US GILTI contrast is similar. For kontor this
means: `:tax-group/regime :organschaft` and `:tax-group/regime :pillar-two`
are **independent** elections on (potentially) different member sets, both
attached to the same legal-entity family.

### 1.8 Statutory citation index (DE) — paragraph level

| # | Statute | Section | Topic |
|---|---|---|---|
| D1 | KStG | §14 Abs. 1 | Organträger / Organgesellschaft definitions, 5-yr EAV, actual execution |
| D2 | KStG | §14 Abs. 1 Satz 1 Nr. 1 | Financial integration ≥ 50 % voting rights from FY start |
| D3 | KStG | §14 Abs. 1 Satz 1 Nr. 2 | Domestic-PE Organträger acceptable (post-2013) |
| D4 | KStG | §14 Abs. 1 Satz 1 Nr. 3 | 5-year minimum + actual execution |
| D5 | KStG | §15 Satz 1 Nr. 1 | Pre-Organschaft loss freeze |
| D6 | KStG | §15 Satz 1 Nr. 2 | Bruttomethode for §8b / §3 Nr. 40 EStG |
| D7 | KStG | §15 Satz 1 Nr. 2a | Zinsschranke at Organkreis level |
| D8 | KStG | §15 Satz 1 Nr. 3 | Donation limit at Organträger level |
| D9 | KStG | §17 Satz 2 Nr. 2 | EAV must include Verlustübernahmeklausel (dynamic §302 AktG) |
| D10 | KStG | §18 | Foreign-PE Organträger specifics |
| D11 | KStG | §19 | Special rules for Verlustabzug across reorganisations |
| D12 | GewStG | §2 Abs. 2 Satz 2 | gewerbesteuerliche Organschaft |
| D13 | GewStG | §9 Nr. 2a | 15 % threshold for §8b-equivalent trade-tax exemption |
| D14 | GewStG | §10a Satz 3 | Trade-tax loss carryforward + Organschaft freeze |
| D15 | GewStG | §28 ff. | Zerlegung across municipalities |
| D16 | AktG | §291 | Beherrschungs- und Gewinnabführungsverträge (legal home of EAV) |
| D17 | AktG | §293 | EAV approval thresholds |
| D18 | AktG | §294 | Register-with-Handelsregister requirement |
| D19 | AktG | §302 | Verlustübernahmepflicht of the Organträger |
| D20 | SolZG | §3 Abs. 1 Nr. 1 | 5.5 % Soli on attributed KSt |
| D21 | UStG | §2 Abs. 2 Nr. 2 | VAT-Organschaft (mentioned only for scope demarcation) |
| D22 | AO | §238 | 6 % p.a. interest on retro-corrected assessments |
| D23 | MinStG | §3 | Berichtspflichtige Geschäftseinheit identification |
| D24 | MinStG | §75 | Filing duty (one DE return per GloBE group) |

Administrative guidance:

- BMF-Schreiben **28.03.2024** — consolidated Anwendungsschreiben on
  §§14-19 KStG (the current authoritative interpretation; supersedes the
  2003 version).
- BMF-Schreiben **24.03.2021** — dynamic vs static §302 AktG reference in
  EAV Verlustübernahmeklausel.
- BMF-Schreiben **03.04.2019** — accepted "wichtige Gründe" for early
  termination.

Federal Tax Court (BFH) cases that materially shape practice:

- BFH **I R 19/22** (23.08.2023) — uncured EAV-execution defect retro-voids
  the regime.
- BFH **I R 56/93** — "Gleichklang" of KSt + GewSt requirements.

---

## §2. FR Intégration fiscale (CGI Art. 223 A et seq.)

### 2.1 Scope and statutory home

French intégration fiscale is codified in **CGI Articles 223 A through 223 U**
(the "Régime de groupe", inside Code général des impôts Livre I, Titre I,
Chapitre IV, Section II). It applies only to **Impôt sur les Sociétés (IS)** —
the French CIT — plus its surtaxes. There is no French equivalent of the DE
GewSt-Organschaft (the *Contribution Économique Territoriale* and *CVAE* have
their own group-regime rules in CGI Art. 1586 ter, separate from intégration
fiscale and structurally a sectoral, not consolidation, regime).

### 2.2 Requirements (CGI Art. 223 A)

CGI Art. 223 A, I, al. 1 — for an intégration to be elected:

1. **Tête de groupe** (parent) must be a corporation subject to IS at the
   standard rate ("soumise à l'impôt sur les sociétés dans les conditions de
   droit commun"). The tête can itself be the French subsidiary of a foreign
   parent (the "intégration par le bas").
2. **Sociétés intégrées** (members) must be IS-liable French corporations
   with the standard rate (Art. 223 A, I, al. 2). Foreign-resident
   subsidiaries are excluded (but see §2.7 for the *intégration horizontale*
   carve-out post-*Stéria*).
3. **Capital ownership** — the tête must hold, **directly or indirectly**,
   at least **95 %** of the capital of each intégrée (Art. 223 A, I, al. 1
   "détenue, directement ou indirectement, à 95 % au moins par la société
   mère"). Indirect holding is computed by **multiplying** the chain: if T
   holds 100 % of A and A holds 95 % of B, T's indirect share of B is 95 %
   and B qualifies. **Treasury shares** held by the intégrée itself are
   neutralised in the denominator (Art. 46 quater-0 ZF Annexe III CGI).
4. **Continuous holding** — the 95 % must be met throughout the **entire**
   FY for every member (Art. 223 A, II). Mid-year acquisitions cannot make
   Year 1 an intégration year for that subsidiary (parallel to DE).
5. **Same FY** — every member's FY must coincide with the tête's. Art. 223 A,
   III. Mismatched FYs require a member to align by changing its closing
   date (resolution of the *Assemblée Générale Extraordinaire*).
6. **Election** — the tête files an option (Art. 223 A, V) on the standard
   form **CERFA 2065** Annexe 2058-A bis, listing all intégrées. The option
   is **5 years tacitly renewable** (Art. 223 A, V — "pour une période de
   cinq exercices, renouvelée tacitement"). Members can be added in
   subsequent years; removals require a recalculation event.

Compared to DE: **no EAV**, **no Handelsregister registration**, **no
profit-transfer contract**. The election is purely tax-administrative and
revocable at the 5-year boundary. The 95 % capital test is *stricter* than
DE's >50 % voting-rights test (the latter being notably easy to meet via
dual-class shares); the elective term is **identical** at 5 years tacit;
practical exit is much easier on the FR side.

### 2.3 Two-step computation (CGI Art. 223 B)

#### 2.3.1 Per-member taxable income

Each intégrée and the tête separately compute their individual *résultat
fiscal* under standard IS rules: starting from book profit, applying CGI
Art. 38–39 (income/expense rules), the standard depreciation regime, etc.
This per-member résultat is reported on the **2058-A** form by each member
even though the assessment will be issued only to the tête.

#### 2.3.2 Tête-level aggregation (résultat d'ensemble)

CGI Art. 223 B, al. 1 — the tête de groupe computes the *résultat d'ensemble*
as the algebraic sum of all members' results, subject to the neutralisations
listed in Art. 223 B subsequent paragraphs:

- **Élimination des dividendes intragroupe** (Art. 223 B, al. 2-3). Dividends
  paid by an intégrée to another group member are eliminated from the
  beneficiary's taxable income. Under the *régime mère-fille* (CGI Art. 145
  + 216, the 95 %-exempt-with-5 %-quote-part regime), dividends from a
  ≥5 %-owned subsidiary are already 95 % exempt — the 5 % quote-part remains
  taxable. **Inside intégration**, FA 2016 (Loi de finances pour 2016)
  reduced the intragroup quote-part to **1 %** (Art. 223 B, al. 3, since 2016).
  So the **economic group-internal dividend rate is 1 % × IS rate**, a small
  but real frictional cost not present in the DE Organschaft (which is
  fully neutral via Bruttomethode).
- **Élimination des plus-values internes** (Art. 223 F). Capital gains and
  losses on intragroup transfers of fixed assets are **neutralised** at the
  group level. The gain or loss is deferred until the asset leaves the
  group (sold to a third party) or the regime ends — see *déneutralisation*
  in §2.6. CGI Art. 223 F, al. 1 — "ne sont pas pris en compte pour la
  détermination du résultat d'ensemble".
- **Élimination des provisions** (Art. 223 D). Provisions for depreciation
  on intragroup receivables, or for risk against intragroup parties, are
  neutralised — they cannot reduce the résultat d'ensemble.
- **Réintégration des abandons de créances et subventions** (Art. 223 R) —
  intragroup debt waivers and subsidies are neutralised at recipient but
  with a clawback if the regime exits within 5 years.
- **Charges financières limitées** (Art. 212 bis + Art. 223 B bis). The
  general interest-deduction limit (ATAD, 30 % of "EBITDA fiscal" capped at
  €3M) is computed at **group level** for an integrated group (Art. 223 B
  bis, since LF 2019). The €3M Freigrenze equivalent applies once to the
  group, parallel to DE Zinsschranke.

So the FR neutralisation set is **broader** than DE's: DE only neutralises
dividends (and that mostly by attribution mechanic, not statutory line item).
FR explicitly neutralises dividends + intragroup capital gains + intragroup
provisions + intragroup debt-waivers + interest-deduction limit.

#### 2.3.3 Assessment

The tête receives one IS notice covering the résultat d'ensemble. IS rates
(2026): standard 25 %, PME-reduced bracket 15 % on the first €42,500 if the
tête meets the PME definition (CGI Art. 219 I-b — turnover < €10M, ≥75 %
held by natural persons or PME, capital fully paid). Plus the *Contribution
sociale sur les bénéfices* (CSB) at 3.3 % of IS exceeding €763,000 (CGI Art.
235 ter ZC). The intégration applies the PME bracket *once* at the group
level (Art. 223 A, I, al. 1 — the test is the tête's turnover not the
aggregate; this changed back and forth historically and was confirmed by
LF 2022).

For 2024 and 2025 only, the *contribution exceptionnelle* and *contribution
additionnelle* (CGI Art. 235 ter ZAA, 235 ter ZA temporary surtaxes from LF
2025) ride on top — 20.6 % surtax on IS if turnover > €1bn — at the **tête's
turnover** for an integrated group. This is significant: the tête, even if
modestly profitable on its own, is exposed to the surtax based on
**aggregate group turnover**.

### 2.4 Loss treatment

#### 2.4.1 Pre-integration losses (déficits pré-intégration)

CGI Art. 223 I, 1, al. 4 — losses of an intégrée arising before its entry
into the group ("déficits subis par une société du groupe au titre
d'exercices antérieurs à son entrée dans le groupe") are **boxed**:

- They do NOT contribute to the résultat d'ensemble.
- They CAN be used **only against that société's own résultat propre** for
  IS years inside the integration (the intégrée's stand-alone post-Art.-38
  result, before the elimination flows up).
- Standard French loss-carryforward rules apply: up to €1M unlimited, slice
  above €1M only 50 % per year (CGI Art. 209, I-3 — the FR "Mindestbesteuerung
  équivalent" mirror to DE §10d EStG; rates: €1M floor, 50 % above, balance
  carries forward indefinitely).

This is the same shape as DE: pre-election losses isolated at the
subsidiary, available against the subsidiary's own income, can carry
forward indefinitely.

#### 2.4.2 In-integration losses

A member's loss in an integration year **does** flow up to the tête via
the aggregation in Art. 223 B and offsets group income that year. If the
résultat d'ensemble is negative, the *déficit d'ensemble* is carried forward
at the **tête's level** under the standard Art. 209 rules (€1M / 50 %).

#### 2.4.3 Post-integration losses

When an intégrée exits the group (sold out, or the regime ends), CGI
Art. 223 R + Art. 223 S govern the loss-recovery:

- **Pre-integration losses still boxed** are released back to the
  intégrée at exit (CGI Art. 223 R, last al.).
- **Losses incurred during integration** sit at the tête and stay there —
  the intégrée does not "take back" its share of the group loss.
- **Déficits d'ensemble** unused at the tête when the *régime exits in
  full* (i.e. the tête revokes) become déficits propres of the tête going
  forward, usable normally.

The exit-time asymmetry mirrors DE almost exactly.

### 2.5 Régime mère-fille interaction (CGI Art. 145, 216)

The standard *régime mère-fille* (mother-daughter regime) is the FR
participation exemption: dividends from a ≥5 % subsidiary, held for ≥2 years,
are 95 % exempt at the parent (5 % "quote-part de frais et charges" stays
taxable). Both inside-integration and outside-integration members can elect.

The interaction with intégration fiscale:

- For dividends **between intégration members** — quote-part reduced from
  5 % to **1 %** (Art. 223 B, al. 3, LF 2016).
- For dividends from a **non-integrated** ≥5 % subsidiary — standard
  régime mère-fille (5 % quote-part).
- The 1 % quote-part rate is the LF-2016 reaction to ECJ *Stéria* (Case
  C-386/14, judgment of 02.09.2015), which held that the prior 0 % rate
  for integration-internal dividends, combined with the 5 % rate for
  cross-border EU-subsidiary dividends, was an EU freedom-of-establishment
  violation. FR re-aligned upward (group-internal 1 %) rather than down
  (cross-border to 0 %).

### 2.6 Exit-from-integration: déneutralisations

When the regime ends — by revocation, by member exit, by tête restructuring,
or by automatic termination — *deferred* items crystallise:

- **Plus-values internes** deferred under Art. 223 F crystallise at exit
  if the asset is still in the (former) intégrée — Art. 223 F, last al.
  The gain becomes immediately taxable at the **tête** in the exit year.
  This is the major "exit tax" of FR intégration: a long-held intragroup
  asset transfer can generate a multi-year deferred gain that all hits
  at once.
- **Abandons de créances** waived under Art. 223 R are clawed back if the
  recipient exits within 5 years.
- **Provisions** released under Art. 223 D are clawed back if the
  beneficiary leaves.

Tête-level result for the exit year therefore includes both the year's
normal résultat d'ensemble AND the déneutralisation crystallisations.

### 2.7 Sub-groups, horizontal, and EU dimension

#### 2.7.1 Vertical chain

CGI Art. 223 A, I, al. 1 — indirect holding through chain is acceptable
provided each link is ≥95 %. So a four-level chain (T → A 100 % → B 95 % →
C 96 %) integrates A, B, and C all under T.

#### 2.7.2 Horizontal (since LF 2015)

After ECJ *Groupe Stéria* (Case C-386/14), France introduced the
**intégration horizontale** in LF 2015 (Art. 30, codified at CGI Art. 223
A, I, al. 2): two FR-resident sister corporations both held ≥95 % by a
common EU/EEA parent (with a treaty allowing exchange of information) can
elect intégration as if they were vertical. The EU parent is the
*entité-mère non-résidente* but is not itself integrated.

This is structurally important for kontor: the "group" can be defined by a
*non-included parent* as the integration anchor. So the data model can't
assume "the tête is the topmost member" — the tête in horizontal
integration is one of the FR siblings, but the membership criterion
references an external entity.

#### 2.7.3 Pillar Two (LF 2024)

Art. 33 LF 2024 transposed EU Directive 2022/2523 into French law via
new **CGI Art. 223 VJ et seq.** ("Impôt complémentaire sur les bénéfices
des groupes multinationaux et des grands groupes nationaux"). Effective
for FYs starting on or after 31 December 2023. Like Germany, France
computes the GloBE Top-Up Tax on a **per-jurisdiction ETR** basis built
on consolidated financial statements — **not** on intégration fiscale
membership. The integration tête and the GloBE entité déclarante can be
different (Art. 223 WB).

Same conclusion as DE §1.7: Pillar Two is adjacent to intégration, not a
layer on top, and the group definitions are distinct.

### 2.8 Statutory citation index (FR) — paragraph level

| # | Statute | Article | Topic |
|---|---|---|---|
| F1 | CGI | 223 A, I, al. 1 | 95 % capital test, vertical |
| F2 | CGI | 223 A, I, al. 2 | Horizontal integration via EU parent |
| F3 | CGI | 223 A, II | Continuous-holding requirement |
| F4 | CGI | 223 A, III | Same-FY requirement |
| F5 | CGI | 223 A, V | 5-yr tacit-renewal option |
| F6 | CGI | 223 B, al. 1 | Résultat d'ensemble = Σ résultats individuels ± neutralisations |
| F7 | CGI | 223 B, al. 3 | Quote-part dividendes intragroupe = 1 % (post-LF 2016) |
| F8 | CGI | 223 D | Neutralisation des provisions intragroupes |
| F9 | CGI | 223 F | Neutralisation des plus-values internes |
| F10 | CGI | 223 I, 1, al. 4 | Déficits pré-intégration boxed to intégrée |
| F11 | CGI | 223 R | Réintégrations on exit; debt-waiver clawback |
| F12 | CGI | 223 S | Sortie du groupe — déneutralisation effects |
| F13 | CGI | 223 B bis | ATAD interest-deduction limit at group level |
| F14 | CGI | 223 VJ ff. | Pillar Two transposition (LF 2024) |
| F15 | CGI | 209, I-3 | Loss carryforward €1M / 50 % above |
| F16 | CGI | 219, I-b | PME 15 % bracket on first €42,500 |
| F17 | CGI | 235 ter ZC | CSB 3.3 % surtax above €763k IS |
| F18 | CGI | 235 ter ZAA | Contribution exceptionnelle 2024-25 (turnover > €1bn) |
| F19 | CGI | 145, 216 | Régime mère-fille standard 95 % exemption |
| F20 | CGI | 212 bis | Interest-deduction barrier (ATAD) |
| F21 | CGI Annexe III | 46 quater-0 ZF | Treasury-share neutralisation in 95 % test |
| F22 | LPF | L 169 | Three-year statute of limitations for IS group review |

Administrative doctrine:

- **BOI-IS-GPE** (BOFIP base) — "Régime fiscal des groupes de sociétés" —
  full sub-tree of administrative interpretations on intégration. Updated
  rolling; last major refresh **2025-04-09**.
- **BOI-IS-GPE-10-10** — perimeter and election.
- **BOI-IS-GPE-20-20-50** — neutralisations (dividends, plus-values, provisions).
- **BOI-IS-GPE-40** — sortie de groupe + déneutralisations.

CE / Cour de Cassation cases:

- CE **9 juin 2017, n° 396423** — méthode de neutralisation des dividendes
  cross-border (post-Stéria).
- ECJ **Case C-386/14 Groupe Stéria** — 02.09.2015 — the trigger for
  intégration horizontale.
- CE **23 décembre 2022, n° 451550** — confirms that the perimeter is
  rebuilt year-by-year on the basis of the 95 % test as of the FY start
  for each member.

---

## §3. Common structural patterns — DE + FR synthesis

This section is the substrate-design-relevant payoff. The table below
collects each axis where DE and FR are either congruent or divergent, and
states the *minimum modelling requirement* implied by the union of both
regimes. The substrate must cover the union — picking the easier of the
two would lock out the other jurisdiction.

| Axis | DE Organschaft | FR Intégration fiscale | substrate need |
|---|---|---|---|
| **Statute home** | KStG §§14-19; GewStG §2 Abs. 2 Satz 2 | CGI Art. 223 A-U | `:tax-group/regime` enum is country-specific; concept-IRI seam (ADR-090) ties them to a shared taxonomy class |
| **Election shape** | EAV private-law contract, ≥5 yr, registered with Handelsregister, must include §302 AktG Verlustübernahme | CERFA option filed annually with the 2065 return; 5-yr tacit renewal | `:tax-group/election` entity with `(start-date end-date kind contract-ref renewable?)`; ADR-034 status-machine for election lifecycle |
| **Member-set criterion** | >50 % voting rights from FY start; held by Organträger directly OR indirectly | ≥95 % capital, direct OR indirect, throughout FY | a per-member `:tax-group/qualification` derivation that takes ownership history + FY start as inputs |
| **Ownership computation** | "majority of voting rights" — class-weighted; pyramiding to indirect | indirect = multiplication of chain percentages; treasury shares neutralised | the ownership-chain primitive must be **regime-parameterised** — DE uses voting-rights with simple aggregation, FR uses capital-share with multiplicative chain |
| **Loss attribution: pre-election** | KStG §15 Satz 1 Nr. 1 — frozen at Organgesellschaft; usable only against own post-OS income; subject to §8c/§8d | CGI Art. 223 I, 1, al. 4 — boxed at intégrée; usable only against own résultat propre during integration | per-member `:loss-bucket :pre-election` scoped to `(tax-group, member, kind)`; ADR-101 `:provision/op :base-deduct` already expresses this |
| **Loss attribution: in-election** | Attributed to Organträger as part of zvE; carried at Organträger as §10d EStG | Aggregated to tête as part of résultat d'ensemble; carried at tête under Art. 209 | the substrate's normal `:loss-bucket` at the parent level — same as standalone |
| **Loss attribution: post-election (frozen revived)** | Pre-OS losses released back at OS end; in-OS losses stay at Organträger | Pre-integration losses released at exit; in-integration losses stay at tête | a regime-end *transition* event that re-tags the frozen bucket from `:scoped-to member` to `:active-at member` |
| **Intercompany dividends** | Neutralised by attribution (Bruttomethode for §8b); fully tax-free in practice | Quote-part reduced to 1 % (LF 2016); 99 % exempt | a `:elimination-policy` per (regime, transaction-kind) — DE: full exemption; FR: 1 % residual taxable. Not a binary "eliminate/keep" |
| **Intercompany asset transfers (capital gains)** | No automatic neutralisation; standard §6 EStG basis transfer if hot-asset rollover (§§6 Abs. 3-5 EStG) | Art. 223 F — deferred at group level until externalisation OR exit | per-tx `:elimination-status :deferred-until-exit` for FR; not applicable for DE — so the regime-specific elimination set must be parameterised |
| **Intercompany provisions** | Not specially neutralised | Art. 223 D — neutralised | regime-specific elimination set, ditto |
| **Intercompany debt waivers** | Not specially neutralised | Art. 223 R — neutralised with 5-yr clawback | regime-specific elimination set + clawback timer |
| **Interest-deduction limit (ATAD)** | KStG §15 Satz 1 Nr. 2a — Zinsschranke at Organkreis level, €3M Freigrenze once | Art. 223 B bis — ATAD at group level, €3M floor once | the existing per-entity Zinsschranke / ATAD provider must accept a `:tax-group` scope override |
| **Single tax base** | Yes — Organträger files for whole group | Yes — tête files for whole group | `compute-tax` accepts `:scope {:tax-group <eid>}`; marginalizes over members |
| **Single CIT rate / brackets** | Yes — KSt 15 % + Soli 5.5 % on attributed base | Yes — IS standard 25 % + PME 15 % on first €42,500 + CSB 3.3 % | already supported per-component; group-tax just changes the input base |
| **Sub-groups / chains** | Yes — direct (atypische Organschaft) or sub-Organschaft (two EAVs) | Yes — vertical chains; **horizontal** with EU non-resident parent | `:tax-group/parent` recursive ref + an out-of-group anchor entity for horizontal FR |
| **Same-FY requirement** | Same-FY for Organgesellschaft (Art. §14 references Wirtschaftsjahr); financial integration tests at FY start | Same-FY for all integrées (Art. 223 A, III) | both regimes require period-alignment — this is a `:tax-group/election` precondition, not novel substrate |
| **Termination event** | Retroactive break if 5-yr unmet OR EAV not executed; "wichtiger Grund" preserves elapsed years | Déneutralisations crystallise at exit; revocation effective end of FY | substrate needs both: a *contingent* attribution that may retro-void AND a *crystallisation* tx at exit |
| **Termination → audit risk** | High — single-year defect can retro-void; AO §238 6 % p.a. interest | Lower — exit is forward-looking with one-shot crystallisation | DE side argues for explicit "regime year-N validity" attribute that's bitemporally revisable |
| **VAT-side regime** | Independent (UStG §2 Abs. 2 Nr. 2) | None at IS-group level; sectoral CVAE rules separate | `:tax-group/regime` is per-tax-kind, not per-entity-family |
| **Pillar Two interaction** | MinStG group built on Konzernabschluss, not Organschaft | CGI Art. 223 VJ ff. group built on consolidated financials, not intégration | Pillar Two is a sibling regime, not a sub-regime of group-tax |

The synthesis is that DE and FR are **largely congruent** on the *shape*
of the substrate primitives needed (election entity + member set + scoped
loss buckets + elimination policy + crystallisation event + termination
status-machine), but **diverge sharply** on the *fill of those slots*
(election kind, ownership threshold, eliminations covered, retroactivity
posture). This is the same pattern as ADR-101 statute-as-data: a small
fixed substrate, country-specific data filling it. Group tax appears to
fit the same template *one level up* — at the regime layer rather than
the provision layer.

### 3.1 Critical divergences worth flagging now

Three regime differences will stress whichever substrate kontor builds:

1. **DE retroactive termination vs FR forward-only termination.** DE allows
   a missed EAV execution five years in the past to retro-void the regime.
   FR exit is forward-looking. kontor's bitemporal substrate (ADR-008,
   ADR-048) is naturally well-suited to retroactive change — `:tx/valid-from`
   already gives us the lever — but the *provider* must distinguish
   "regime year-N attribution that was valid then but is invalid now" vs
   "regime year-N attribution that was always invalid." The former needs a
   compensating reversal posting in year N; the latter is the original
   posting never having been valid (a kontor `:db/purge` candidate). Note
   165 will check the US §1501 posture; preliminarily US is closer to FR
   (forward-only) but with annual elections.

2. **DE Bruttomethode (§15) vs FR straight aggregation.** DE attributes
   the Organgesellschaft's *gross* amount up to Organträger for some items
   so that the Organträger's facts (e.g. its §8b threshold, its
   Spendenabzug limit) apply. FR computes each member's résultat individually
   under the member's own facts, then sums. The two are mathematically
   different — for example, a Spendenabzug limit on each FR intégrée's own
   GdE will yield a different group total than lifting all donations to
   the tête's GdE and testing once. Substrate must support **both**
   computation orders ("member-level then sum" and "lift items then test
   at parent"). This is the key insight that distinguishes group-tax from
   "just sum the members." It maps cleanly to ADR-101's `:provision/scope`
   + the proposed `:provision/level :member | :group` parameter.

3. **DE EAV registered contract vs FR tax-only election.** DE's EAV is a
   private-law document with Handelsregister registration, §302 AktG
   loss-assumption obligation, and corporate-law minimum duration. FR's
   option is a tax-administrative election that doesn't touch corporate
   law. kontor's `:tax-group/election` must support both *contract-anchored*
   (DE — refers to a private-law doc) and *filing-anchored* (FR — refers
   to a CERFA filing) elections. The ADR-038 `:audit-doc` substrate can
   hold either, but the *required* metadata differs (DE: Handelsregister
   number + registration date + EAV term + §302 reference; FR: filing
   date + tax office + 5-yr renewal anchor).

---

## §4. Worked examples — one per regime

Both examples follow the same shape: a 3-entity group with profits of
+€2M, +€500k, and −€1M. Members are domestic. The point is to show the
arithmetic, the rate stack, and the per-member return forms.

### 4.1 DE — Organschaft

#### 4.1.1 Setup

- **Organträger** Müller Holding GmbH (Sitz Hamburg; Hebesatz 470 %)
- **Organgesellschaft 1** Müller Industries GmbH (Sitz München; Hebesatz 490 %)
- **Organgesellschaft 2** Müller Logistik GmbH (Sitz Köln; Hebesatz 475 %)
- All three under EAV signed 2020-12-15, registered Handelsregister
  2021-01-10; effective from FY 2021. FY = calendar year.
- 100 % shareholding by Holding; EAVs include §302 AktG dynamic-reference
  Verlustübernahmeklausel per BMF 2021-03-24.

FY 2025 results (Steuerbilanz, post-§§4-7g EStG, before Organschaft attribution):

| Entity | Gewinn aus Gewerbebetrieb |
|---|---|
| Müller Holding GmbH | +€2,000,000 |
| Müller Industries GmbH | +€500,000 |
| Müller Logistik GmbH | −€1,000,000 |
| **Sum (Holding's zvE post-attribution)** | **+€1,500,000** |

#### 4.1.2 KSt + Soli on the attributed base

```
Holding's zvE (post-§14 attribution)         =  €1,500,000
KSt (15 %, KStG §23 Abs. 1)                  =    €225,000
Soli on KSt (5.5 %, SolZG §3 Abs. 1 Nr. 1)   =     €12,375
                                              ─────────────
KSt + Soli                                    =    €237,375
```

KSt return KSt 1 is filed by Holding for own + attributed zvE = €1.5M.
Müller Industries GmbH and Müller Logistik GmbH each file their own KSt 1
showing zvE = €0, with Anlage OG memo showing the attributed +€500k and
−€1M respectively. (Source: BMF Anwendungsschreiben 28.03.2024 §14
Abs. 1; pwc.com/de Worldwide Tax Summary Germany 2026, "Organschaft —
worked example".)

#### 4.1.3 GewSt parallel — multi-municipal Zerlegung

The trade-tax Organschaft treats each Organgesellschaft as a Betriebsstätte
of Holding, but the Steuermessbetrag is computed once on the sum and then
*allocated across municipalities* under GewStG §28 by payroll (§29). For
illustration assume payroll split: Hamburg 50 %, München 30 %, Köln 20 %.
Hinzurechnungen / Kürzungen assumed nil for simplicity (real numbers
would have §8 Nr. 1 financing add-back).

```
Gewerbeertrag (group)                                 = €1,500,000
Round down to €100 (GewStG §11 Abs. 1 Satz 3)         = €1,500,000
Steuermessbetrag (3.5 %, GewStG §11)                  =    €52,500
Zerlegung:
  Hamburg-Anteil    50 % × €52,500 = €26,250 × 470 %  =   €123,375
  München-Anteil    30 % × €52,500 = €15,750 × 490 %  =    €77,175
  Köln-Anteil       20 % × €52,500 = €10,500 × 475 %  =    €49,875
                                                       ─────────────
GewSt total                                            =   €250,425
```

(Each municipality issues its Bescheid against the Organträger; the
Organträger pays each individually. Source: GewStG §§28-29 + BMF
*Anwendungsschreiben Gewerbesteuer* 2024.)

#### 4.1.4 Group total

```
KSt + Soli + GewSt        = €237,375 + €250,425 = €487,800
Effective group rate       = €487,800 / €1,500,000 ≈ 32.52 %
```

This is close to the textbook DE "combined CIT + trade tax" rate of
~30-33 % (KSt 15 % + Soli 0.825 % + GewSt at average Hebesatz ~14-17 %).

#### 4.1.5 Filings to produce

- **Holding GmbH**: KSt 1 (own + attributed), GewSt return for Hamburg
  Betriebsstätte, Anlage GK (Zerlegung) sent to all three Finanzämter.
- **Industries GmbH**: KSt 1 (zvE = 0, Anlage OG memo €500k attributed),
  GewSt return for München Betriebsstätte (zero own — Holding pays).
- **Logistik GmbH**: KSt 1 (zvE = 0, Anlage OG memo −€1M attributed),
  GewSt return for Köln Betriebsstätte (zero own).

### 4.2 FR — Intégration fiscale

#### 4.2.1 Setup

- **Tête de groupe** Dupont Holding SAS (FR-resident, IS at standard rate)
- **Intégrée 1** Dupont Industries SAS (FR-resident, 100 % owned by
  Holding)
- **Intégrée 2** Dupont Logistique SAS (FR-resident, 96 % owned by
  Holding — meets ≥95 % capital test of Art. 223 A, I, al. 1)
- Option intégration filed 2023 effective FY 2023, renouvelée 2028, all
  FYs calendar year, all three IS standard-rate.

FY 2025 résultats fiscaux individuels (post-Art. 38-39 adjustments):

| Entity | Résultat fiscal individuel |
|---|---|
| Dupont Holding SAS | +€2,000,000 |
| Dupont Industries SAS | +€500,000 |
| Dupont Logistique SAS | −€1,000,000 |
| **Algebraic sum** | **+€1,500,000** |

Assume no intragroup dividends, no intragroup asset transfers, no
intragroup provisions or debt waivers (the simple case — additional
worked examples for those neutralisations appear in BOI-IS-GPE-20-20-50).

#### 4.2.2 Résultat d'ensemble

```
Σ résultats individuels                         = €1,500,000
± Neutralisations (Art. 223 B-F-R-D)            =          0
                                                 ─────────────
Résultat d'ensemble                              = €1,500,000
```

Assume Holding's standalone turnover < €10M but **aggregate turnover** is
>€10M, so Holding does NOT qualify for the PME 15 % bracket at group level
(LF 2022 alignment). Standard 25 % rate applies.

#### 4.2.3 IS + surtaxes

```
IS standard 25 % (CGI Art. 219, I)              =   €375,000

CSB (Contribution sociale 3.3 %, CGI Art. 235 ter ZC)
  Trigger: IS > €763,000 ? — No, IS = €375k. CSB = €0.
                                                 ─────────────
IS + CSB                                         =   €375,000
```

(If FY had been 2024 and group turnover > €1bn, the contribution
exceptionnelle 20.6 % of IS = €77,250 would have applied. For €1.5M
résultat d'ensemble at modest turnover, surtaxes are nil.)

#### 4.2.4 Group total

```
Total tax = IS €375,000 (effective rate 25.0 % on €1.5M)
```

Note the absence of a trade-tax parallel — this is the structural
difference vs DE. FR has the **Cotisation sur la Valeur Ajoutée des
Entreprises (CVAE)** + **Cotisation Foncière des Entreprises (CFE)** at
the level of the Contribution Économique Territoriale (CET), but these
follow their *own* group regime under CGI Art. 1586 ter, not Art. 223 A.
For a single-jurisdiction worked example this is acceptable simplification;
the substrate must NOT assume "one tax-group election covers all taxes."

#### 4.2.5 Filings to produce

- **Holding SAS**: Form 2065 (déclaration IS) covering résultat d'ensemble
  €1.5M, Annexe 2058-A (own profit), Annexe 2058-A bis (intégration —
  member list + each member's résultat propre + the réintégrations).
  Pays IS €375,000.
- **Industries SAS**: Form 2065 with résultat individuel +€500k filed in
  parallel; group elimination flagged.
- **Logistique SAS**: Form 2065 with résultat individuel −€1M filed in
  parallel.

(Source: legifrance.gouv.fr — CGI Art. 223 A-B + BOI-IS-GPE-30-30-10
"Calcul de l'impôt sur les sociétés du groupe"; pwc.com/fr Worldwide
Tax Summary France 2026, "Group taxation" section.)

### 4.3 Side-by-side observation

Same +€1.5M group base, materially different tax outputs:

| Regime | Federal/national CIT | Trade-/regional | Group total | Effective rate |
|---|---|---|---|---|
| DE Organschaft | KSt + Soli €237,375 | GewSt €250,425 | €487,800 | 32.52 % |
| FR Intégration | IS €375,000 | (CET separate regime) | €375,000 (IS only) | 25.00 % |

These figures illustrate two truths a kontor user might want to check
through the substrate: the *base* is identical (the elimination algebra
matches across regimes for the simple case), but the *rate stack* is
country-specific and the *trade-tax parallel* is DE-only. Provider-level
work (ADR-101 statute-as-data) covers the rate stack; group-tax substrate
covers the elimination algebra.

---

## §5. License + sourcing posture

### 5.1 Primary sources

**Germany** — all primary statute texts are public-domain federal law
published by the Bundesministerium der Justiz at `gesetze-im-internet.de`.
Citation form: `KStG §14 Abs. 1 Satz 1 Nr. 1`, paragraph-level. BMF
administrative guidance (Anwendungsschreiben, BMF-Schreiben) is published
in the Bundessteuerblatt I, which is open and citation-free.

**France** — primary law texts are at `legifrance.gouv.fr` under the
**Etalab 2.0** open licence (compatible re-use with attribution). Citation
form: `CGI Art. 223 A, I, al. 1`. BOFIP administrative doctrine
(`bofip.impots.gouv.fr`) is itself Etalab 2.0; we paraphrase, not copy.

### 5.2 Secondary sources (citation-only, NOT lifted text)

- **PwC Worldwide Tax Summaries** (Germany, France 2026 editions) —
  paraphrased only for the worked-example sanity-check numbers.
- **KPMG 2025 corporate-tax guides** — same.
- **EY 2025 corporate-tax compendium** — same.
- **CMS *Tax Connect* 2025** (group-tax cross-jurisdiction monograph) —
  consulted for cross-jurisdiction patterns; not quoted.
- **Frotscher/Drüen** *KStG Kommentar* (German Beck commentary, online
  edition via beck-online — Anthropic does not have a subscription;
  consulted only via publicly-quoted excerpts).
- **Mémento Lefebvre Fiscal 2026** — French tax compendium, same posture.

Same posture as BR / IN blueprints (notes 162 / 163): paraphrase
secondary commentary, lift only paragraph-level statute citations from
primary law.

### 5.3 What is NOT in this note

- No XML schemas of the actual filings (KSt 1, GewSt 1A, CERFA 2065,
  Annexe 2058-A bis). Those are the *adapter* deliverable, not the
  reference-study deliverable.
- No worked examples of every elimination (intragroup dividend with
  quote-part, intragroup asset gain with deferral, intragroup debt waiver
  with clawback). The §4 examples cover the simple aggregation; the
  detailed elimination algebra goes into the Phase-3 design note (note
  166) once §6's option is selected.
- No EU Pillar Two algebra. Mentioned in §1.7 + §2.7.3 only to confirm
  it's a sibling regime, not a sub-regime.
- No US §1501 / JP renketsu nōzei review. That's note 165.

---

## §6. What kontor needs — preview, not prescription

This section deliberately surfaces **three substrate options** without
picking one. The pick will be made in note 166 (internal gap analysis)
after the US/JP comparison (note 165) lands.

### 6.1 Common substrate elements all three options need

Independent of which option is chosen, the substrate has to express:

1. **An election entity** — `:tax-group/election` with
   `(regime, scope-kind, start-date, end-date, status, anchor-doc-ref)`.
   ADR-034 status-machine over `(:elected → :active → :exited | :voided-retro)`.
2. **A member-set membership criterion** — per-member, per-year, derived
   from ownership history + regime ownership threshold + same-FY check.
   Either materialised as `:tax-group/member` refs or as a derived
   datalog view over ownership + parameter.
3. **A regime-parameterised elimination policy** — DE's "Bruttomethode-vs-
   ordinary-attribution" and FR's "neutralised / deferred / clawback"
   categories are not a binary "eliminate yes/no" but a small classification
   matrix. The substrate needs a per-tx (or per-account-flow) tag like
   `{:elimination-status :neutralised | :deferred-until-exit | :reduced-quote-part-1pct | :ordinary-flow}`
   keyed against the regime + tx-kind.
4. **Per-member, regime-scoped loss buckets** — pre-election losses
   frozen at member; ADR-101 `:provision/op :base-deduct` over a
   `:loss-bucket` keyed by `(tax-group, member, period-anchor :pre-election)`.
5. **A crystallisation event** — at regime exit, deferred-internal items
   become taxable. A *transaction* materialised at the regime-end date,
   built from the substrate's collected deferred-flows ledger.
6. **A retroactivity posture** — DE retro-voids; FR forward-only. The
   substrate needs both. Bitemporal `:tx/valid-from` covers the DE
   side; FR is just the absence of retroactivity.

### 6.2 Option A — extend `kontor.consolidation` (ADR-073)

`kontor.consolidation` today already walks an entity family, translates
FX, and eliminates intercompany pairs. Add a `:tax-group/*` namespace
to its schema; add a `:consolidate-for :tax` mode that swaps the
elimination policy from "financial consolidation rules" to
"tax-group-regime rules"; add a `:tax-group/regime` parameter that
selects between DE-Organschaft, FR-Intégration, US-§1501, etc.

**Pros**
- One namespace, one ADR's worth of additive change.
- The entity-family walk, the FX-translation logic, and the elimination
  pair machinery are all reused.
- "Tax consolidation is a flavor of consolidation" is intuitive.

**Cons**
- Mixes substantively different concerns: financial consolidation is
  *always* performed (IFRS / GAAP requires it); tax consolidation is
  *elected* (regime opt-in with statutory minimum duration). Adding a
  `:tax-group/election` entity to a namespace called `consolidation`
  reads weird.
- The crystallisation-on-exit logic (FR Art. 223 F) has no parallel in
  financial consolidation — financial consolidation doesn't have a
  "regime exit" event. Adding it forces consolidation to model regime
  lifecycle, which it currently doesn't.
- ADR-073 line 32-47 already explicitly says "Not a deferred-tax /
  transfer-pricing engine. Those are kontor-tax-provision companion."
  Extending consolidation into tax-policy territory contradicts that
  scoping.

### 6.3 Option B — new companion `kontor-tax-group`

A fresh companion (Maven artifact `kontor-tax-group`) with namespaces
`:tax-group/regime`, `:tax-group/election`, `:tax-group/member`,
`:tax-group/elimination-policy`, `:tax-group/deferred-internal-flow`,
`:tax-group/crystallisation-event`. Per-jurisdiction *intégration de
groupe* and *Organschaft* rules ship as `:provision` data under ADR-101,
keyed by `:tax-group/regime`. Provider records implement
`PeriodTaxProvider` with `:scope {:tax-group <eid>}`.

**Pros**
- Separation of concerns: financial consolidation stays in `kontor.consolidation`;
  tax-group lifecycle, elimination policy, and elections live in
  `kontor-tax-group`. ADR-073 scoping preserved.
- Each per-jurisdiction provider (DE Organschaft, FR Intégration, US §1501,
  JP renketsu nōzei, UK group relief — note: surrender model, different
  shape) reuses the same substrate.
- The election-as-`:audit-doc` pattern lands naturally on ADR-038.
  Sub-Organschaft chains land naturally on `:tax-group/parent` self-ref.
- Mirrors the existing `kontor-commitment`, `kontor-disposal`,
  `kontor-lease`, `kontor-asset` companion pattern.

**Cons**
- Another companion module. Slight increase in surface area for consumer
  apps (beleg / simmis must declare the dep to use the regime).
- Some duplication in the intercompany-pair primitive: kontor.consolidation
  has one notion of "intercompany pair," kontor-tax-group will have
  another. They must agree on how `:transaction/intercompany-pair-id`
  is shared.

### 6.4 Option C — substrate-only `compute-tax` extension

No new schema. Extend ADR-099's `PeriodTaxProvider/compute-tax` to accept
`:scope {:tax-group entity-ids}` where `entity-ids` is just a `set` of
the member entity IDs. The provider does its own marginalization over
the member set, reads ownership facts and election facts from existing
`:partner/*` and `:entity/*` attrs, and emits the consolidated
provision posting. Eliminations are read from `:transaction/intercompany-pair-id`
(already exists) with a regime-keyed predicate filter.

**Pros**
- Zero schema change. Most "kontor philosophy" — the data already exists;
  the function just learns to compute over a set.
- Minimal surface area; no new companion.
- Symmetric with how `kontor.report/marginalize` (ADR-096) works over
  arbitrary entity sets — group-tax is "another marginalization axis."

**Cons**
- Election lifecycle has nowhere to live. Storing "this group elected
  Organschaft 2021-2026 with EAV registered 2021-01-10" as a tagged
  `:audit-doc` plus an ad-hoc map in the provider config is workable but
  ugly. ADR-034 status-machine over election state is the natural fit;
  no first-class entity = nowhere to attach the status history.
- Pre-election loss buckets need *somewhere* to live. ADR-101's loss-bucket
  machinery is already on `:provision/op :base-deduct` referring to a
  `:tax-unit`-scoped pool, but "tax-unit" needs to know whether to be
  the member's standalone unit or the group unit, depending on whether
  the loss is pre-election or in-election. Adding the regime-discriminator
  to the pool key is a quiet substrate change disguised as a "function
  extension."
- Crystallisation-on-exit: where does the deferred-internal-flow ledger
  live? If not in a `:tax-group/deferred-internal-flow` entity, the
  alternative is a sentinel on the source `:posting` indicating "this
  posting's tax-recognition is suspended until regime exit," which is
  intrusive on `:posting/*` schema.

### 6.5 Triage rubric (for note 166)

The three options trade off cleanly on the axis of *first-class-ness of
the regime*:

- **Option A**: regime is a knob on consolidation.
- **Option B**: regime is a first-class companion entity.
- **Option C**: regime is implicit, lives in provider config + existing
  ownership/intercompany substrate.

Note 166 should pick based on: (i) does the US §1501 regime (note 165)
add elimination or election features that don't fit Option C's "no new
schema" floor? (ii) is the contractual-anchor (DE EAV + Handelsregister)
metadata acceptably modelled as a tagged `:audit-doc` (Option C) or does
it need its own entity (Option B)? (iii) is the crystallisation event
(FR Art. 223 F) survivable as a sentinel on `:posting` (Option C) or
does it deserve a first-class deferred-flow ledger (Option B)?

A weak prior: Option B looks like it matches the existing kontor pattern
of "small new companion per intentional new concept" (kontor-disposal,
kontor-lease, kontor-commitment) more naturally than the other two.
But the call should wait on note 165's US/JP findings, because if those
two add nothing structurally new (US §1501 is purely-elective like FR;
JP renketsu nōzei is closer to DE with consolidated-tax-base since 2002),
Option C may suffice. Conversely if note 165 surfaces a third pattern
(e.g. JP's *aggregate consolidated tax* with intra-group rebates), the
first-class-companion choice (Option B) becomes more defensible.

---

## §7. Cross-references and follow-ups

- **note 165** — US §1501 consolidated-return + JP renketsu nōzei. Same
  shape as this note for those two regimes. Together with this note, the
  cross-jurisdiction substrate axis table feeds note 166.
- **note 166** — internal gap analysis. Picks one of §6's three options
  based on the union of notes 164 + 165 + the existing substrate.
- **Gap #8 ADR** — codifies the choice; ships the substrate; the per-jurisdiction
  provider PRs (de-organschaft-provider, fr-intégration-provider) follow.
- **ADR-073 (financial consolidation)** — will need a line item in any
  Gap #8 ADR noting that financial consolidation is *unchanged*; tax
  consolidation is *additive*.
- **ADR-099 (PeriodTaxProvider)** — Gap #8 may add a `:scope :tax-group`
  variant of `compute-tax`. ADR-099's `TaxReturnFacts` schema may or may
  not need a `:tax-group/member-results` slot to carry per-member
  individual results for the Anlage OG / 2058-A bis filings.
- **ADR-101 (statute-as-data)** — group-tax `:provision`s will live in
  per-jurisdiction `cit-statute` files (DE: `kstg-§14-§19.cit-statute`;
  FR: `cgi-223A-U.cit-statute`). ADR-101's `:provision/op` set may need
  a new op like `:eliminate-intragroup-flow` if Option B or C is chosen.

## §8. Summary

DE Organschaft and FR Intégration fiscale are structurally cousins, not
twins. They share the broad shape (elective regime, 5-year minimum,
single tax base, pre-election losses frozen at the member, attribution
of in-election losses to the parent, no trans-border in-group inclusion
except via Pillar Two) but diverge on the *form of the election* (DE
private-law contract + Handelsregister; FR tax-administrative option),
the *ownership threshold* (DE >50 % voting rights; FR ≥95 % capital),
the *neutralisation set* (DE narrow; FR broad — dividends + capital
gains + provisions + debt waivers), and the *termination posture* (DE
retro-void; FR forward-only with one-shot déneutralisation).

For kontor's substrate, the design choice surfaces as a clean three-way
between (A) extending `kontor.consolidation`, (B) building a new
`kontor-tax-group` companion, or (C) extending the `PeriodTaxProvider`
contract without new schema. The choice will be made in note 166 after
the US/JP cross-reference (note 165). The substrate gap is real but
modest — DE + FR together imply ~5-7 new substrate elements (election
entity, member set, elimination policy, scoped loss buckets,
deferred-flow ledger, crystallisation event, regime parameter) — and
matches the existing kontor companion-shape pattern.

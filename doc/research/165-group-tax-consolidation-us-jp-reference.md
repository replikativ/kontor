---
date: 2026-05-25
title: 165 — Group tax-consolidation reference study (US §1502 + JP group-tsuusan)
audience: substrate designers + ADR-101 maintainers
status: research-before — Gap #8 of the tax-completion program (note 104)
companion-notes: 164 (DE/FR group-tax) + 166 (internal gap analysis)
synthesis-target: 167 (the ADR draft)
---

# 165 — Group tax-consolidation reference study (US §1502 + JP group-tsuusan)

This note is one of three parallel research-before passes that feed Gap #8 of
the tax-completion program (note 104, "individual → corporation continuum").
The kernel today ships 6 ADR-101 CIT providers (DE/FR/CA/JP/BR/IN) that all
model **single legal entities**; real corporate groups, however, can elect
**tax-consolidation regimes** where members net into one tax base.
`kontor.consolidation` (ADR-073) already handles *financial* consolidation
(FX + intercompany elimination per IAS 21 / IAS 27 shape); *tax*
consolidation is the new requirement.

This sibling-note covers the two **non-European** OECD regimes that are
most architecturally distinct from the DE/FR pattern note 164 walks: the
United States §1501 *consolidated return* regime (most code-heavy in
the OECD, quasi-permanent election, group base) and the Japanese 2022
**グループ通算制度** (group-tsuusan / "group netting") regime which is
the *only* of the four where each member files its own return but
losses cross-share at year-end.

Output is **statutory anatomy + design-relevant patterns**; no kontor
code. The substrate synthesis happens in note 167 (the ADR draft).

---

## §0. Scope and what this is not

In scope:

- IRC §1501 et seq. + Treas. Reg. §1.1502-x — the United States
  consolidated-return regime as practiced today (post-TCJA, with the
  CAMT and Pillar Two overlays noted but not designed).
- Corporate Tax Act (法人税法 / Hōjin Zeihō) Articles 64-5 et seq. —
  the **2022-rewrite Japanese group-tsuusan** regime that replaced the
  2002-era 連結納税 (renketsu nōzei). The distinction matters: the
  prior regime was a true *consolidated return*; the rewrite is a
  *loss-sharing regime*. Pre-2022 examples in the literature describe
  a regime that no longer applies; we are precise about the boundary.
- Cross-axis comparison with DE Organschaft / FR intégration fiscale
  (note 164's scope) at the §3 axis-table level.

Explicitly out of scope:

- The substrate design itself. §6 lists 3 options. Synthesis lives in
  note 167.
- Pillar Two / GloBE compute. We mention CAMT (US) and **各対象会計
  年度の国際最低課税額に対する法人税** (JP top-up tax) at the
  *interaction* level only.
- US §382 ownership-change limitation as a *standalone* mechanic. We
  reference where it intersects with consolidation, but the §382
  engine itself is a separate workstream.
- Transfer-pricing (§482 / Japan's Arm's-Length Standard). Adjacent;
  not group-tax.
- State-level US combined / unitary return regimes (California,
  Illinois, Massachusetts, etc.). Genuinely different design with
  separate axes (water's edge / worldwide combined / Finnigan vs
  Joyce). Deferred to a dedicated note when state-level CIT lands.
- Japanese 100 %-group rules in the *non-elective* sense (法人による
  完全支配関係のある法人間の取引等の損益不算入, CTA Art. 61-13) —
  these apply *without* election to any 100 %-owned group, governing
  loss recognition on intra-group asset transfers. They overlap with
  but are not group-tsuusan. Mentioned in §2.6.

Methodology: primary-source citation against the Cornell LII mirror
(US) and elaws.e-gov.jp (JP). Secondary sources (PwC, KPMG, Deloitte,
EY, BDO worldwide tax summaries; NTA published Q&A) are read for
shape and worked examples; paraphrased, never lifted.

---

## §1. United States — IRC §1501 + Treas. Reg. §1.1502-x consolidated returns

The US has the most prescribed, most case-law-saturated, and most
internally cross-referenced consolidated-return regime in any major
jurisdiction. The statute (IRC §1501–1504) is brief; the substantive
mechanics live in Treas. Reg. §§1.1502-1 through 1.1502-100 — over a
hundred sections of regulations that govern how a 80 %-owned chain of
US corporations files a single federal return.

### §1.1. Authority and who can elect (IRC §§1501–1504)

**IRC §1501** ("Privilege to file consolidated returns") states the
core election: *"An affiliated group of corporations shall, subject
to the provisions of this chapter, have the privilege of making a
consolidated return with respect to the income tax imposed by chapter
1 for the taxable year in lieu of separate returns."* Authority URI:
`https://www.law.cornell.edu/uscode/text/26/1501`.

**IRC §1504(a) defines the affiliated group.** An *affiliated group*
is a chain of *includible corporations* connected through a **common
parent corporation** such that:

1. The common parent directly owns stock meeting the requirements
   below in at least one of the other includible corporations.
2. Each of the includible corporations (other than the common parent)
   has the requisite stock owned directly by one or more of the other
   includible corporations.

The "requisite stock" requirement (IRC §1504(a)(2)): stock possessing
**at least 80 % of the total voting power** of the corporation, AND
having **a value equal to at least 80 % of the total value** of the
stock of the corporation. (Both thresholds must be met; this is
"vote-and-value" since the 1984 amendment.) Authority URI:
`https://www.law.cornell.edu/uscode/text/26/1504`.

**Excluded entities** (IRC §1504(b)): tax-exempt §501 corporations,
insurance companies taxed under subchapter L (with a special election
under §1504(c) for life-insurance subgroups), foreign corporations,
corporations electing the possessions tax credit, RICs (regulated
investment companies), REITs, DISCs, and S corporations. The
*includible* set is narrower than "any corporation in the
ownership-chain"; ineligibility breaks the chain for *that* sub.

**Foreign-corp exclusion** (IRC §1504(b)(3)): a foreign-incorporated
sub is generally **excluded** from the affiliated group regardless of
US tax presence. This is structurally different from the DE
Organschaft (which allows foreign GP partnerships of a German
*Organträger* under recent reforms) and from FR intégration fiscale
(which allows EU sister-company integration since the Papillon /
Stéria saga). For the US, group-tax consolidation is a **domestic-only**
exercise as a regime mechanic, while *foreign* income flows in via
the separate GILTI / Subpart F machinery (§§951–965).

### §1.2. Election shape — quasi-permanence

Per **IRC §1501** and Treas. Reg. §1.1502-75(a), the consolidated
return *election* is made by **the act of filing**: when the common
parent files Form 1120 for the group with a consolidated affiliations
schedule (Form 851), each subsidiary that has consented (Form 1122
for the first year of joining, or implicit deemed consent under
§1.1502-75(b)) is bound. There is no separate election form.

The election is structurally **quasi-permanent**: once made, the
group **must** continue to file consolidated returns "for every
subsequent taxable year unless they have filed a consolidated return
for each subsequent year and the Commissioner, on the basis of
established lack of substantial reason, authorizes a change to a
separate-return basis" (Treas. Reg. §1.1502-75(c)). Practical
effect: deconsolidation requires affirmative IRS permission via a
private letter ruling or a regulatory cause (most commonly: the chain
broke because a sub dropped below the 80 % threshold). Authority URI:
`https://www.law.cornell.edu/cfr/text/26/1.1502-75`.

This permanence is the **single most important shape-difference**
between US and JP regimes: JP group-tsuusan is *elected per
incorporated entity at affiliation*, US consolidation is *quasi-
permanent at the group level*. Different audit-doc and status-machine
implications.

### §1.3. The core mechanic — single CTI, single liability

Under Treas. Reg. §1.1502-11 and §1.1502-12, **consolidated taxable
income (CTI)** for the year is computed as follows (simplified):

1. **Each member computes its own "separate taxable income" (STI)**
   under regular C-corp rules (§1.1502-12), but with *intercompany*
   items handled specially per §1.1502-13 and with no member-level
   NOL deduction (group NOL applies post-aggregation per §1.1502-21).
2. **Aggregate the STIs** into "consolidated taxable income before
   group items" (sometimes called "tentative CTI").
3. **Apply group-level adjustments**:
   - Intercompany items deferred/recognized per §1.1502-13.
   - Consolidated charitable-contribution limit per §1.1502-24.
   - Consolidated dividends-received deduction per §1.1502-26.
   - Consolidated NOL deduction per §1.1502-21.
   - Consolidated capital-gain/loss netting per §1.1502-22.
   - Consolidated §163(j) interest-limitation per §1.1502-79.
   - (And many more — §§1.1502-23 / -27 / -28 / -47 etc.)
4. **Apply the §11 rate** (currently 21 % flat post-TCJA, on
   consolidated taxable income).
5. **Compute group-level liabilities** including AMT/CAMT (§55), BEAT
   (§59A), and credits (foreign-tax §901, research §41 …) at the
   group level.

The result is **one** federal income-tax liability for the group,
reported on **one** Form 1120 by the common parent, supported by Form
851 listing all member corporations and Form 1122 consents from new
members. Each member is, however, **severally liable** for the entire
consolidated tax (Treas. Reg. §1.1502-6(a) — see §1.6 below).

### §1.4. §1502-13 intercompany transactions — *deferred-and-matching*

This is the single most kontor-substrate-relevant US mechanic.
Authority URI: `https://www.law.cornell.edu/cfr/text/26/1.1502-13`.

**The accounting consolidation shape**: in IAS 27 / ASC 810
consolidation, intercompany transactions are **eliminated entirely**
— the inventory sale from S1 to S2 disappears from the consolidated
P&L; only the eventual external sale matters. kontor's existing
`kontor.consolidation/eliminate-intercompany-pair-tx-data` follows
this shape.

**The §1502-13 tax-consolidation shape**: intercompany transactions
are **NOT eliminated**. Instead they create *intercompany items*
that are **deferred** and **recognized on a matching basis** when one
of the following triggering events occurs:

- The buying member sells the asset to a non-group party (the
  "matching" event — the deferred gain is then recognized in
  proportion to the external recognition).
- The asset is depreciated or amortized by the buyer (matching the
  inside-basis-step-up against the seller's deferred gain over the
  asset's tax life).
- The buying or selling member leaves the group ("acceleration"
  event under §1.1502-13(d) — all deferred items snap back to
  recognition).

Mechanically: §1502-13(b) defines an "intercompany transaction" as
any transaction between members of a consolidated group. §1502-13(c)
prescribes the **matching rule**: intercompany items of the selling
member ("S") are taken into account when the corresponding items of
the buying member ("B") affect consolidated taxable income — i.e.,
S's gain is recognized as B depreciates or sells.

This is structurally a **stateful** mechanic: the group must carry
**deferred intercompany items** as **per-asset, per-pair**
attributes across taxable years. There is no kontor primitive for
this today. ADR-073's `:transaction/intercompany-pair-id` flags the
transactions but does not track the deferred-recognition stream.

**Example (hand-traceable, from Treas. Reg. §1.1502-13(c)(7)
Example 1, paraphrased)**: S sells land to B for $100, basis $70.
S's $30 gain is **deferred**. B holds the land for 3 years, then
sells it externally for $120. Year 3: B's external gain is $50 (sale
$120 − $70 carryover basis), AND S's deferred $30 is now recognized
on the matching event. Consolidated taxable income year 3 reflects
exactly the $50 economic gain (B's $50 minus the $30 of basis-step-up
that S's deferred gain creates inside B). Net: same as if S had
held and sold to outside; that is the design point.

**For substrate design**: this is one new entity-shape the kernel
needs if we want US §1502 fidelity: a `:tax-intercompany-deferred-item`
that lives across tax years, references the originating
intercompany-transaction, and discharges (partially or fully) on
matching events.

### §1.5. §1502-21 SRLY — Separate Return Limitation Year rule

Authority URI: `https://www.law.cornell.edu/cfr/text/26/1.1502-21`.

**The problem SRLY solves**: when group P acquires sub T which has
pre-acquisition NOLs (sustained when T filed separate returns), an
*unrestricted* group-NOL deduction would let P "buy losses" — T's
losses absorbing P's group-wide income. §1502-21(c) prevents this.

**The SRLY limitation** (§1.1502-21(c)(1)(i)): a member's "SRLY
losses" (NOLs sustained in years before the member joined the
consolidated group, or during a §1.1502-21(g) SRLY-subgroup year) may
be deducted in a consolidated year **only to the extent of that
member's own positive contribution to consolidated taxable income for
the year** (with cumulative-since-affiliation tracking, not year-by-
year). The constraint is **member-by-member**, not group-wide.

**Cumulative-since-affiliation** (§1.1502-21(c)(1)(i)(B)): the
limitation is tested on a **cumulative** basis — the SRLY-bearing
member's positive contributions to CTI since affiliation, **minus**
the SRLY losses previously absorbed. Carryovers of unused SRLY
remain SRLY-restricted indefinitely (subject to §172's 80 % post-2017
NOL cap — see below).

**Interaction with §382**: when the SRLY member also underwent an
ownership change in the same transaction that brought it into the
group (almost always true for a §338 acquisition), the **§382
limitation** (annual ceiling = value of loss-corp stock × long-term
tax-exempt rate) **also** applies. SRLY layers ON TOP of §382; the
*lower* of the two limits binds. §1.1502-91 through -99 manage the
group-level §382 application — out of scope here.

**The 80 % NOL cap** (IRC §172(a)(2)): for NOLs arising in tax years
beginning after 2017, the deduction is capped at 80 % of taxable
income (before the NOL deduction). This applies to *all* NOLs (group
or member, SRLY or current) and is independent of SRLY. Post-CARES
Act, the 80 % cap was suspended for 2018-2020 and reinstated 2021+.

**For substrate design**: SRLY means a member's NOL bucket has a
**provenance tag** (pre-affiliation vs post-affiliation) AND a
**cumulative scoreboard** (since-affiliation positive CTI
contribution, since-affiliation SRLY absorption). This is more than
a flag — it's a stateful accumulator per (member, NOL-vintage)
combination.

### §1.6. Liability — §1502-6 several liability

Treas. Reg. **§1.1502-6(a)**: "The common parent corporation and each
subsidiary which was a member of the group during any part of the
consolidated return year **shall be severally liable** for the tax
for such year computed in accordance with the regulations under
section 1502 prescribed on or before the due date (not including
extensions of time) for the filing of the consolidated return for
such year." Authority URI:
`https://www.law.cornell.edu/cfr/text/26/1.1502-6`.

"Severally liable" = each member is liable for the *entire* group
tax; the IRS can collect from any one member. This is a deeper
liability than the JP 連帯納付責任 (joint-and-several) construct in
that it persists even after the member exits the group for the
**years it was a member**, even if the parent and other members
remain solvent.

**For substrate design**: each (member, consolidated-tax-year) cell
needs an attached *several-liability projection* — for governance,
disclosure, and bankruptcy/M&A diligence. The kernel does not have
this entity today.

### §1.7. CAMT — Corporate Alternative Minimum Tax (IRA 2022)

The Inflation Reduction Act of 2022 reintroduced a corporate minimum
tax (IRC §55(b)(2), §56A) effective for tax years beginning after
2022-12-31. Mechanically:

- **Scope**: "applicable corporations" — broadly, US C-corps that
  meet the "average annual adjusted financial statement income
  (AFSI)" test of **>$1 billion** averaged over the prior 3 years
  (controlled-group aggregation under §52). For foreign-parented
  multinationals, a lower $100M US-AFSI test also applies.
- **Rate**: **15 % of AFSI** (modified financial-statement income),
  to the extent it exceeds regular tax (plus BEAT). Functions as an
  alternative-minimum overlay, not a separate liability.
- **Group treatment**: CAMT applies at the **§52 controlled-group
  level** for both the threshold test and the AFSI computation. For
  a §1502 consolidated group, CAMT-AFSI is computed by aggregating
  the GAAP/IFRS financial-statement income of the entire controlled
  group (a *broader* perimeter than the affiliated group, since §52
  uses 50 %-or-greater control vs §1504's 80 %).

Authority URIs: `https://www.law.cornell.edu/uscode/text/26/55` and
`https://www.law.cornell.edu/uscode/text/26/56A`. Treasury proposed
regulations (REG-112129-23) issued 2024-09-12 — still in proposed
form at this writing.

**For substrate design**: CAMT introduces a **second group
perimeter** (the §52 controlled group, 50 % threshold) different
from the §1502 affiliated group (80 % threshold). One member-set is
not enough — at minimum the substrate needs to model both
*perimeters* against the *same set of legal entities*.

### §1.8. GILTI / BEAT / Subpart F — adjacent, not group-tax

Worth one paragraph for context:

- **GILTI** (§951A, "Global Intangible Low-Taxed Income"): a US
  shareholder's pro-rata share of net CFC tested income beyond a 10 %
  return on tangible assets. Computed at the US shareholder level
  *first*, then folded into the consolidated return as a §1502 item.
- **BEAT** (§59A, "Base Erosion and Anti-Abuse Tax"): minimum tax on
  base-erosion payments to foreign related parties. Group-level
  application via §1502-59A.
- **Subpart F** (§§951–960): pre-existing CFC inclusion regime.

These are *anti-abuse layers* that apply alongside §1502
consolidation, not part of the consolidation mechanic itself. From a
substrate standpoint, they live on the **CIT provider** as
adjustments to STI/CTI — not on the **group-tax** primitive.

**US Pillar Two** (IRC §59A interactions; pending GloBE
implementation as of 2026-05): the US has not enacted a domestic
Pillar Two top-up tax (the IIR/UTPR). The OECD-aligned GloBE
information return (GIR) regime is being implemented through Treasury
notices. A US-headquartered multinational filing in EU-Pillar-Two
jurisdictions faces top-up tax abroad; the US side flows through
GILTI as a *partial* shield (the QDMTT/IIR-treats-GILTI debate is
ongoing). Not part of group-tax v1.

### §1.9. Termination of consolidation

Per Treas. Reg. §1.1502-75(d), the consolidated group **terminates**
when:

- The common parent ceases to exist (merger, dissolution, conversion
  to non-corporate form). §1.1502-75(d)(2) provides "reverse-acquisi-
  tion" continuity if a former subsidiary effectively takes over.
- The §1504(a) chain breaks (no member remains 80 %-owned by another
  member).
- IRS-granted permission to deconsolidate under §1.1502-75(c).

On termination, **§1.1502-13(d) acceleration**: all remaining
deferred intercompany items snap to recognition in the final
consolidated year. **§1.1502-19 "excess loss accounts"**: if a
parent's basis in a sub's stock has gone negative (the sub
distributed more than parent invested), the negative basis is
recognized as gain on departure.

**For substrate design**: termination is a **status-machine
transition** (per ADR-034) at the group level, with side-effects
(deferred-item recognition, ELA snapback) that must be computed and
posted.

### §1.10. Filing mechanics — Form 1120 + Form 851 + Form 1122

For traceability:

- **Form 1120**: the consolidated income-tax return, filed by the
  common parent. One return per group per tax year.
- **Form 851** ("Affiliations Schedule"): lists every member of the
  group, their EINs, percentage stock ownership through the chain.
  Attached to the consolidated 1120.
- **Form 1122** ("Authorization and Consent of Subsidiary Corporation
  To Be Included in a Consolidated Income Tax Return"): filed by
  each new member in its first consolidated year.
- **Form 5471 / 8858** for foreign-sub reporting and **Form 8975**
  CbC-report still apply per-entity within the group.

The federal-tax artifact set the kernel must model:
- one filing entity (the common parent for the group);
- N members with consent forms;
- 1 consolidated balance-sheet, P&L, and tax computation;
- M intercompany-deferred items (potentially many, per asset/sale);
- carryovers (NOL by vintage, SRLY-tagged; capital losses similarly;
  §163(j) interest carryovers; foreign-tax credit carryovers);
- attached state-return decisions (combined/separate/consolidated by
  state — out of scope this note).

---

## §2. Japan — グループ通算制度 (group-tsuusan, post-2022 rewrite)

### §2.1. The 2022 boundary — why old examples mislead

Japan's prior consolidated-tax regime was 連結納税 (renketsu nōzei),
introduced in 2002 (TRA 2002, codified in old CTA Articles 4-2 et
seq.). Under 連結納税, the parent filed ONE return covering itself +
100 %-owned subsidiaries; the regime was a *true* consolidated return
in shape close to US §1502 (group base, single liability, parent
files).

**Reiwa-2 (2020) Tax Reform** repealed 連結納税 effective for fiscal
years beginning on or after **2022-04-01**, replacing it with
グループ通算制度 (group-tsuusan / "group netting"). The legislative
text is Law No. 8 of Reiwa-2 (令和2年法律第8号). The Cabinet
Order amending CTA articles 64-5 through 64-11 was implementing
regulation No. 207 of Reiwa-2.

**Why the rewrite**: the 連結納税 regime imposed administrative
burdens (one parent return covering all subs across prefectures with
prefectural splits) and audit-amendment friction (a single error in
one sub required amending the parent's return for the entire group).
Group-tsuusan was designed to **preserve the economic benefit of
loss-sharing** while **eliminating the single-return administrative
overhead**. The shape is structurally different from 連結納税 and
from US §1502.

**Why this is critical context**: any PwC / KPMG / EY guide dated
before approximately 2023 may describe 連結納税 mechanics that no
longer apply. The kontor design must target **post-2022** mechanics.

### §2.2. Authority and who can elect (CTA Art. 64-9, Art. 64-5)

**Eligibility (CTA Art. 64-9 §1)**: a *parent corporation*
(通算親法人, tsuusan oya-hōjin) electing the regime must be a
**domestic corporation (内国法人)** with **wholly-owned**
(100 %-owned, 完全支配関係) domestic subsidiaries. The 100 % test is
strict — the so-called *complete-control relationship* under CTA
Art. 2 No. 12-7-6, which includes both direct ownership and
indirect ownership through other 100 %-owned subs ("five-or-fewer
shareholders" lookthroughs do **not** apply to the corporate-tax
group-tsuusan).

**Excluded entities**: foreign subs (外国法人), TMK / TK pass-throughs,
public-benefit corporations, certain cooperative banks, mutual
insurance companies. Largely paralleling US exclusions, though with
different boundaries on financial-sector subs.

Authority URI for CTA articles:
`https://elaws.e-gov.jp/document?lawid=340AC0000000034` (法人税法).

**Election (CTA Art. 64-9 §2)**: the parent files a "通算承認の申請書"
(group-netting election application) with the National Tax Agency
(NTA, 国税庁) by the **deadline two months before the start of the
first elected fiscal year**. All eligible subs are automatically
brought in unless individually excluded by separate application.

**Quasi-permanence**: once elected, the regime continues **until the
parent applies for cessation** (CTA Art. 64-10) with **NTA approval**
(not granted lightly — the standard is roughly "demonstrated change
of business such that continued group-netting is unreasonable"),
**or** the parent ceases to exist, **or** the 100 % chain breaks.
Practical effect: similar to US §1501-75(c) — affirmative deconsoli-
dation requires regulator permission.

### §2.3. Mechanics — per-member filing, year-end loss-netting

This is the **single most structurally distinctive** feature of
group-tsuusan: under post-2022 rules, **each member files its own
corporate-tax return** at its own assessed taxable income, but
*losses* are **netted across the group** before the per-member
liability is computed.

Concretely (simplifying CTA Art. 64-5 §1):

1. **Each member computes its own taxable income** (所得金額) under
   ordinary CTA rules — including the existing kontor JP-CIT machinery
   (national 23.2 %, local CIT 10.3 % surtax, enterprise tax,
   inhabitants' tax, special corporate enterprise tax).
2. **Tentative pre-allocation income** (損益通算前の所得金額,
   "income before loss allocation"): each member's taxable income or
   loss in isolation.
3. **Loss-allocation step (損益通算, son-eki tsuusan)**: members with
   *current-year losses* allocate those losses **pro-rata against the
   positive income of other members in the same group year** (CTA
   Art. 64-5 §1, formula in §2). Allocation key = each absorbing
   member's positive-income share of total positive income in the
   group.
4. **Post-allocation taxable income** for each member: original
   income minus allocated absorbed loss (or zero for the contributing
   loss-member).
5. **Tax computation per member**: each member applies the regular
   rate schedule (15 % / 23.2 % SME-conditional or 23.2 % flat) to
   its **post-allocation** taxable income.
6. **Surtaxes per member**: local CIT, enterprise tax, etc. all
   compute on the post-allocation base, per the existing JP-CIT
   provider's normal machinery.
7. **Filing**: each member files its **own** national CIT return
   (法人税申告書), with disclosure of the group-tsuusan adjustment
   on Schedule 6-3 of the corporate-tax-return forms.

**Loss-allocation payment (損益通算による減税分の精算)**: a member
whose loss was absorbed by another member's positive income receives
a **"net tax-saving compensation"** from the absorbing member
(typically reflected as an intra-group payable/receivable). This is
**a real cash settlement**, not a notional accounting entry — the
loss-contributor receives compensation from the absorbing member for
the value of the loss transferred. CTA Art. 64-5 §5 sets the formula
(absorber's tax saving × loss-contributor's share). The settling
flow is **inside the group**, not paid to NTA.

**Critical substrate finding**: this is a regime where **each member's
own books carry an intercompany receivable/payable representing the
tax-effect of the group adjustment**. No US analogue (where the
group has one liability, paid by the parent). kontor's
`kontor.consolidation/eliminate-intercompany-pair-tx-data` is the
right shape (intercompany pair-id, dual postings on operating
entities, elimination at the consolidation entity), but the trigger
is a **tax computation**, not a commercial transaction.

### §2.4. Pre-election losses — CTA Art. 57 §8 + §9 (SRLY-equivalent)

Authority: CTA Art. 57 — net-loss carryovers, with §§8 + 9 specific
to group-tsuusan members.

**Pre-affiliation losses (繰越欠損金, kurikoshi kessonkin) of a
member**: these continue to carry forward at the member level but
are usable **only against that member's own positive contribution to
group-period taxable income** (CTA Art. 57 §8). The rule mirrors US
§1502-21(c) SRLY in shape: pre-affiliation losses cannot offset other
members' income.

**Carryforward window**: 10 years for losses incurred in fiscal years
starting on or after 2018-04-01 (9 years for earlier vintages, 7
years for pre-2008 — the kernel's 10-year SoLE pruning needs to
respect these vintages).

**Loss-absorption cap (大法人欠損金繰越控除限度額)**: for "large
corporations" (capital > ¥100M or specific other tests), the annual
NOL deduction is capped at **50 %** of pre-deduction taxable income
(CTA Art. 57 §11). SMEs are exempt — they may deduct against 100 %.
This cap applies BEFORE the SRLY-style §8 limitation.

**Interaction with §57-2 (qualified-merger NOL succession)**: a
qualified merger (適格合併) under CTA Art. 2 No. 12-8 allows the
acquired company's NOLs to migrate to the survivor, *subject to*
§57 §3-5 anti-abuse limits (NOL becomes SRLY-restricted in the
survivor's hands for several specific patterns). Out of scope for
group-tsuusan v1 — but worth flagging because acquisition into a
group-tsuusan parent often involves a §2-No-12-8 merger.

**For substrate design**: identical shape to US §1502-21(c) —
per-member NOL provenance tag (pre/post-affiliation) + per-member
cumulative scoreboard. The same primitive serves both regimes.

### §2.5. Joint-and-several liability — 連帯納付責任 (CTA Art. 75-12)

Authority: CTA Art. 75-12 (and Art. 152 for tax-collection rules
adapted to group-tsuusan).

**The construct**: members of a group-tsuusan group are **jointly
and severally liable** (連帯納付責任, rentai-nōfu sekinin) for the
**tax-effect of the loss-allocation adjustment** — i.e., each member
is liable to NTA for the **other** members' tax-savings attributable
to the loss-allocation that absorbed losses contributed by that
member.

Distinct from US §1502-6:
- US: each member is severally liable for the **entire** consolidated
  tax for years it was a member.
- JP: each member is jointly liable for **the loss-allocation
  attributable portion** — narrower scope, but more granular by
  computation step.

Practical: NTA pursuing a defaulting member may, after exhausting
that member's assets, pursue other group members for the *allocated*
shortfall. Group-tsuusan elections come with a duty to maintain
inter-member supporting documentation of the allocation computation.

### §2.6. CTA Art. 61-13 — non-elective 100 %-group rules

**This is a separate regime from group-tsuusan** and applies to ANY
domestic 100 %-group (完全支配関係) WITHOUT election. Under
Art. 61-13 (and Art. 61-11), intragroup transactions in certain
*high-value assets* (土地, 有価証券, 金銭債権, 繰延資産 with book
value ≥¥10M) trigger **deferral of gain/loss until the asset leaves
the group or is otherwise alienated**. Companion mechanic to
group-tsuusan but applies regardless of group-tsuusan election.

**Why this matters here**: even if a 100 %-group does NOT elect
group-tsuusan, the §61-13 deferral mechanic ALONE creates a
US §1502-13-shape requirement (deferred-and-matching items per
asset). A future kontor design that handles only the elective
group-tsuusan mechanic would still need §61-13 to be complete for
the Japanese 100 %-group case. Note 167 should consider whether the
deferred-item primitive should ride on the **`:tax-group`** entity
(election-conditional) or the **100 %-ownership relation** (always-on
for ineligible-but-related-party transactions).

### §2.7. Pillar Two — jp Min-Tax (各対象会計年度の国際最低課税額に対する法人税)

Japan enacted the EU/OECD-aligned Pillar Two **Income Inclusion Rule
(IIR)** as **各対象会計年度の国際最低課税額に対する法人税**
("corporate tax on the international minimum tax amount per applicable
fiscal year"), effective for fiscal years starting on or after
**2024-04-01**. Authority: Reiwa-5 (2023) Tax Reform Act, enacted
March 2023 — Law No. 3 of Reiwa-5. Codified in CTA new Chapter 1-2.

Mechanics (simplified):
- Applies to Japanese parented MNE groups with **consolidated annual
  revenue ≥ €750M** (~¥1.1T).
- Top-up tax = 15 % minimum ETR per jurisdiction × low-taxed
  foreign-sub income.
- Computed at the **ultimate-parent level** of the MNE group, NOT
  the group-tsuusan group (different perimeter).
- Domestic Top-up (QDMTT) and Undertaxed Profits Rule (UTPR)
  expected for FY-2025-04-01 onward — pending Reiwa-7 reform.

**For substrate design**: like US CAMT, this is a **second perimeter**
problem — Pillar Two's "MNE group" is the ultimate-parent + all
subs (regardless of ownership level for control purposes), broader
than group-tsuusan's 100 % chain. The substrate needs to model
distinct group-memberships for distinct regimes.

### §2.8. Termination of group-tsuusan

CTA Art. 64-10 governs termination. Trigger events:
- Parent corporation ceases to exist (similar to US §1.1502-75(d)(2)).
- 100 % chain breaks for the entire group (every sub drops below
  100 %; partial break = that sub exits, group continues).
- Parent files cessation application **with NTA approval**.
- Group enters bankruptcy or specific reorganization proceedings.

On termination, similar acceleration mechanics under CTA Art. 64-13:
deferred items under Art. 61-13 snap to recognition; the year of
termination is a **broken-period** year requiring per-member
adjustments.

### §2.9. Filing artifacts

For traceability, the JP-side reporting set:

- **Each member's 法人税申告書 (corporate-tax return)** — Form
  別表一 (Beppyō Ichi) as the cover sheet, with the regular schedules
  ・Beppyō 4 (taxable-income reconciliation),
  ・Beppyō 5-1 (capital-section reserves),
  ・Beppyō 6-3 (**group-tsuusan adjustment schedule** — new for the
  post-2022 regime) showing the loss-allocation arithmetic,
  ・Beppyō 7-1 (NOL carryover with SRLY/§57-§8 tagging),
  ・Beppyō 11 (§61-13 deferred items, if any).
- **Parent's group-information return** (通算グループ情報の申告) —
  CTA Art. 64-9 §6 requires the parent to file a group-level summary
  showing all members + ownership + loss-allocation reconciliation.
  This is NOT a tax-liability return; it is a disclosure.
- **Local CIT, enterprise-tax, inhabitants'-tax returns** — each
  member files prefecturally and municipally as normal; the group-
  tsuusan adjustment passes through to local-CIT base via the
  national-CIT amount.

The kontor JP-CIT provider's existing 3-component TaxReturnFacts
(`:jp-nta`, `:jp-prefecture`, `:jp-municipality`) is the right
substrate per member; group-tsuusan adds a **fourth phase** — the
pre-NTA cross-member loss-allocation step that mutates each member's
input *before* the 3-component machinery runs.

---

## §3. Cross-axis comparison — US §1502 vs JP group-tsuusan (vs DE / FR)

The substrate design is driven by where the regimes differ. Note 164
covers DE Organschaft + FR intégration fiscale at the same axis-table
depth; this section adds US and JP and identifies the spread the
substrate must accommodate.

| Axis | DE Organschaft | FR intégration fiscale | US §1502 | JP group-tsuusan | substrate need |
|---|---|---|---|---|---|
| **Election permanence** | 5-year minimum (PLTA term) | 5-year minimum | Quasi-permanent (IRS permission to exit) | Quasi-permanent (NTA permission) | `:tax-group/regime` + status-machine with min-term + termination predicate |
| **Election shape** | Profit/loss transfer agreement (PLTA) — civil-law contract | Annual election form #2058-A | Implicit via filing Form 1120 + 851 + 1122 consents | Application to NTA 2 months pre-FY | abstract `:tax-group-election` + provider hook for filing-artifact emit |
| **Member set / ownership threshold** | 50 % voting + functional integration (financial+economic+organizational) | 95 % | 80 % vote AND value | 100 % complete-control | ownership-fraction parameter on `:tax-group/regime` — range [0.5, 1.0] |
| **Member nature** | One Organträger + one Organgesellschaft (binary chains via multiple PLTAs) | Parent + chain | Parent + chain | Parent + chain | recursive `:tax-group/parent` for chains; pair-shaped for DE-binary |
| **Foreign subs** | Excluded | EU-resident sisters permitted (Papillon/Stéria) | Excluded | Excluded | bool `:tax-group/regime/foreign-allowed?` + per-regime predicate |
| **Compute model** | Single base, parent reports group OI | Single base, parent reports group OI | Single base, parent reports CTI | **Per-member compute, then loss-net** | the **load-bearing axis** — see §3.1 below |
| **Loss-sharing mechanism** | Full P&L transfer per PLTA | Group base nets all members | Group NOL with SRLY restriction | Pro-rata loss-allocation with cash settlement | per-regime base-transform + intra-group settlement entity |
| **Pre-affiliation losses** | Members' pre-PLTA losses stay with member (no SRLY-equivalent — but losses don't flow into PLTA) | Pre-affiliation losses stay frozen at member-level (similar SRLY shape) | SRLY: pre-aff losses → only against member's own contribution to CTI | CTA Art. 57 §8: same shape as SRLY | per-member NOL vintage tag + cumulative scoreboard |
| **Intercompany transactions** | PLTA already pools P&L — gain transfers to Organträger | Eliminated in group base | **Deferred-and-matching per §1502-13** | Deferred per CTA Art. 61-13 (separate regime; applies always to 100 %-groups) | new entity-shape: `:tax-intercompany-deferred-item` |
| **Single tax base vs per-member** | Single (group OI at Organträger) | Single (group base at parent) | Single (CTI at parent) | **Per-member with netting** | substrate MUST support BOTH shapes |
| **Filing entity** | Organträger (with sub disclosure) | Parent (with sub disclosure) | Common parent (with Form 851 + 1122) | **Each member files own return** | per-regime filing-pattern: 'parent-only' | 'each-member' |
| **Liability** | Joint per PLTA (civil) + tax-statute several liability | Joint and several (CGI Art. 223 A) | Several for each member (Treas. Reg. §1.1502-6) | Joint for loss-allocation effect (CTA Art. 75-12) | per-member liability projection on `:tax-group/member` |
| **Pillar Two interaction** | Pillar Two as separate German Min-Tax (MinStG, FY 2024) | Pillar Two as separate FR IIR (Article 223 VL et seq.) | CAMT (15 % AFSI > $1B) + no Pillar Two yet | jp Min-Tax (CTA Chapter 1-2, FY 2024-04-01) | second perimeter required; separate regime entity, NOT part of group-tax v1 |
| **Sub-groups / recursion** | Chains via multiple PLTAs (each pair separate) | Yes (chain via 95 % ownership) | Yes (chain via 80 % ownership) | Yes (chain via 100 % ownership) | recursive `:tax-group/parent` ref |
| **Termination triggers** | PLTA expiry / cancel / Organgesellschaft loses qualification | Member exits / chain breaks | Chain breaks / IRS permission | NTA permission / parent ceases / chain breaks | per-regime termination predicate + side-effect emitter for deferred-item snapback |
| **Audit trail** | PLTA registered in commercial register + tax-return disclosure | Group election filed + Form 2058 retained | Form 1120 + 851 + 1122 + member-level workpapers | Beppyō 6-3 group-tsuusan adjustment + parent group-info return | `:audit-doc/category :group-tax-election` + per-year disclosure attachment |

### §3.1. The load-bearing axis — single base vs per-member-with-netting

This is the single most-structurally-different finding of the four-
regime survey. DE / FR / US all compute a *single* group tax base
(the parent files the return; subsidiaries file zeros or disclosure-
only forms). **JP group-tsuusan keeps individual member filings**
**but does inter-entity loss-netting at year-end before per-member
liability computes.**

Implication: the substrate Gap #8 ships MUST support **both shapes**
at the abstract level:

- **Shape A — single group base**: marginalize income across all
  members, apply schedule once to group total, parent posts one
  liability. DE / FR / US.
- **Shape B — per-member with cross-member loss-allocation**:
  compute each member's pre-allocation taxable income, redistribute
  losses by formula, then apply schedule to each member's
  post-allocation base. JP.

These cannot be unified by hand-waving Shape B as "Shape A with one
member"; the cash-settlement and per-member-return shape are
structural. Either:

- (option A) the regime carries an `:elimination-style` enum that
  branches kontor logic at compute-time;
- (option B) the substrate exposes both as distinct provider hooks
  and per-regime providers pick;
- (option C) the abstract is "marginalize-then-allocate" — Shape A
  is the special case where allocation is trivial (single base, no
  redistribution).

Note 167 must resolve this. §6 below lists the options as scoped
preview.

### §3.2. The intercompany-deferred-item finding

Both US §1502-13 and JP CTA §61-13 (with the latter applying even
outside group-tsuusan, just on 100 %-groups) require **stateful
per-asset deferral** of intra-group gain/loss across multiple tax
years. kontor's existing `kontor.consolidation` does *not* track
this — it eliminates intercompany on commit, which is the
accounting-consolidation shape, not the tax-consolidation shape.

The two are *both* needed (accounting consolidation for IFRS/GAAP
reporting; tax consolidation for the tax-return computation), and
they DIFFER for the same set of underlying transactions. A future
substrate needs to model BOTH outputs from the SAME set of
intercompany source events.

Sub-finding: this means `:transaction/intercompany-pair-id` is
sufficient for the *source-tagging* but the **deferred items must be
new entities** with lifecycle across years.

### §3.3. The second-perimeter finding

Both US (CAMT at §52 controlled-group, 50 %) and JP (jp Min-Tax at
MNE-group, ultimate-parent) introduce **a second group perimeter**
different from the basic consolidation regime perimeter. This is
NOT part of group-tax v1, but the substrate design must not *foreclose*
multi-perimeter modeling. A clean abstract: a `:tax-group` is keyed
by `(:regime, :perimeter-definition)`; a corporate organization can
have multiple `:tax-group`s for different tax purposes against the
same underlying legal-entity graph.

---

## §4. Worked examples — hand-traceable arithmetic

### §4.1. US §1502 example — 3-entity affiliated group

**Setup**: Parent (P), Sub-1 (S1, acquired 2022-01-01 with $400k
pre-affiliation NOLs), Sub-2 (S2, formed 2024-01-01 inside the group).
P owns 100 % of S1 and S2. Tax year 2025 (calendar year).

**Per-member STIs (separate taxable income, per §1.1502-12)**:

- P STI = $5,000,000.
- S1 STI = $1,000,000.
- S2 STI = $(2,000,000).

**Intercompany check**: assume no §1502-13 events in 2025.

**Aggregation to tentative CTI**: $5M + $1M − $2M = **$4,000,000**.

**SRLY check (§1.1502-21(c))**:
- S1 has $400k of pre-affiliation NOL from before 2022-01-01.
- S1's cumulative-since-affiliation positive contribution to CTI: $1M
  for 2022 + $800k for 2023 + $1.2M for 2024 + $1M for 2025 = $4M
  cumulative. (Numbers illustrative.)
- S1's cumulative SRLY absorption to date: assume $200k absorbed
  over 2022-2024, leaving $200k SRLY-NOL available.
- 2025 SRLY ceiling: $1M (S1's 2025 contribution) × 80 % (§172 cap)
  = $800k. S1's available SRLY of $200k is well below ceiling.
- SRLY deduction in 2025 CTI = **$200k** (consuming the remainder).

**Post-SRLY CTI**: $4M − $200k = $3,800,000.

**Section 11 tax**: $3.8M × 21 % = **$798,000**.

**Severally-liable members**: P, S1, S2 each severally liable for
$798k (Treas. Reg. §1.1502-6(a)).

**Result**: ONE return (P files), ONE liability ($798k), THREE
severally-liable members.

**Variation — without SRLY**: if S1 had no pre-affiliation NOL, CTI
= $4M, tax = $4M × 21 % = $840,000. The SRLY savings of $42,000 is
attributable specifically to using the pre-affiliation losses
allowed by S1's own contribution.

Source: arithmetic verified against the Treasury Reg §1.1502-21(c)(7)
Example 1 pattern (paraphrased; not lifted). Cross-check at Cornell
LII `https://www.law.cornell.edu/cfr/text/26/1.1502-21`.

### §4.2. JP group-tsuusan example — 3-entity 100 %-owned group

**Setup**: Parent (P, 法人税法 internal-domestic, paid-in capital
¥500M = large corporation), Sub-1 (S1, 100 %-owned by P, paid-in
capital ¥50M = SME but disqualified from SME-status by §66 §6
(subsidiary of ≥¥500M parent — see kontor's existing JP-CIT provider)),
Sub-2 (S2, 100 %-owned by P, same disqualification). Fiscal year
ending 2026-03-31. All three elected group-tsuusan effective
2022-04-01.

**Per-member tentative pre-allocation income**:

- P pre-allocation = ¥500,000,000.
- S1 pre-allocation = ¥100,000,000.
- S2 pre-allocation = ¥(200,000,000).

**Loss-allocation step (CTA Art. 64-5 §1)**: S2's ¥200M loss
allocates pro-rata to P and S1's positive income:

- Total positive pre-allocation income = ¥500M + ¥100M = ¥600M.
- P's share of positive income = 500/600 = 5/6.
- S1's share of positive income = 100/600 = 1/6.
- Loss-allocation to P = ¥200M × 5/6 = **¥166,666,667**.
- Loss-allocation to S1 = ¥200M × 1/6 = **¥33,333,333**.
  (Rounding under CTA Art. 67 — ¥1 rounding handled per §67's
  prescribed rule; here ¥1 of allocation rounding goes to the
  larger-share absorber.)

**Post-allocation taxable income**:
- P = ¥500M − ¥166,666,667 = ¥333,333,333.
- S1 = ¥100M − ¥33,333,333 = ¥66,666,667.
- S2 = ¥(200M) + ¥200M (loss-out) = ¥0.

**Per-member tax computation** (national CIT only, ignoring local
and prefectural for brevity):

- P: large corporation, no SME bracket. ¥333,333,333 × 23.2 %
  = **¥77,333,333**.
- S1: SME-disqualified (≥¥500M parent). ¥66,666,667 × 23.2 %
  = **¥15,466,667**.
- S2: ¥0 × 23.2 % = **¥0**.

**Group national-CIT total**: ¥77,333,333 + ¥15,466,667 = ¥92,800,000.

**Intra-group settlement (per CTA Art. 64-5 §5)**: S2 receives a
**net tax-saving compensation** from P and S1:

- P's tax saving from absorbing ¥166,666,667 of S2's loss
  = ¥166,666,667 × 23.2 % = **¥38,666,667**.
- S1's tax saving from absorbing ¥33,333,333 of S2's loss
  = ¥33,333,333 × 23.2 % = **¥7,733,333**.
- Total compensation S2 receives from P + S1 = **¥46,400,000**.

This is **a real cash payable**, settled within the group (not paid
to NTA). On each entity's books:
- P books: dr. 法人税等 (tax expense) ¥77,333,333 + dr. 通算法人税
  相当額 (group-tsuusan compensation due) ¥38,666,667; cr.
  未払法人税 (CIT payable to NTA) ¥77,333,333 + cr.
  未払金通算 (intra-group payable to S2) ¥38,666,667.
- S1 books similarly.
- S2 books: dr. 法人税等 ¥0 + dr. 未収金通算 (intra-group receivable
  from P + S1) ¥46,400,000; cr. 通算法人税相当額利益 (group-tsuusan
  compensation income) ¥46,400,000.

**Verification — economic result**:
- Net cash leaving the group to NTA: ¥92,800,000.
- Compare to a *non-tsuusan* baseline where each member files
  independently: P ¥500M × 23.2 % = ¥116M; S1 ¥100M × 23.2 % =
  ¥23.2M; S2 ¥0 (loss carried forward but not used in current year)
  = ¥0; total ¥139.2M.
- Group-tsuusan saving = ¥139.2M − ¥92.8M = **¥46.4M** — precisely
  the intra-group compensation S2 received.

**Filing result**: THREE returns (one per member, each filed at the
relevant Beppyō 1 + 4 + 6-3 + 7-1); group-info disclosure return by
P; THREE liabilities (each member's own to NTA); joint-and-several
liability under CTA Art. 75-12 attaches to the **compensation**
component only.

Source: arithmetic verified against the NTA published Q&A on
group-tsuusan, available at `https://www.nta.go.jp/` under
"通算制度に関するQ&A" (NTA group-netting Q&A, periodic update).
Cross-check at NTA Beppyō 6-3 schedule line items.

### §4.3. The structural contrast made explicit

| Aspect | US §1502 example | JP group-tsuusan example |
|---|---|---|
| Returns filed | 1 (P consolidates) | 3 (each member separately) |
| Tax liabilities to NTA/IRS | 1 ($798,000) | 3 (P ¥77.3M + S1 ¥15.5M + S2 ¥0) |
| Liability shape | Several per member for $798k each | Joint for compensation (¥46.4M); individual for own returns |
| Intra-group cash transfers | None inherent (P may charge subs per intragroup tax-sharing agreement, but the regime is silent — pure private contract) | **¥46.4M payable from P+S1 to S2 mandated by statute** |
| Pre-affiliation loss | SRLY-restricted ($200k of S1's $400k carried) | Same shape (CTA §57 §8) but not exercised in this example |
| Intercompany transactions | §1502-13 deferred items (not exercised in this example) | §61-13 always-on (separate regime) |
| Audit-doc volume | One Form 1120 + 851 + 1122 + workpapers | Three Beppyō sets + parent group-info return |

---

## §5. License + sourcing posture

### §5.1. Primary sources (citable and public-domain or equivalent)

**United States**:

- **IRC** (Title 26 USC): Cornell LII mirror —
  `https://www.law.cornell.edu/uscode/text/26/`. Section pages are
  versioned and citable. Authority: US federal statute. Public domain.
- **Treasury Regulations** (26 CFR): Cornell LII mirror —
  `https://www.law.cornell.edu/cfr/text/26/`. Section pages versioned.
  Public domain.
- **Forms** (1120, 851, 1122, 5471, 8975): IRS.gov —
  `https://www.irs.gov/forms-instructions`. Public domain.
- **Instructions and Publications** (Pub. 542, Inst. 1120): IRS.gov.
  Public domain.

**Japan**:

- **法人税法 (Corporate Tax Act)** + Cabinet Order + Enforcement
  Regulations: e-Gov e-Laws —
  `https://elaws.e-gov.jp/document?lawid=340AC0000000034`. Government
  publication. Public domain.
- **NTA published Q&A** on group-tsuusan: `https://www.nta.go.jp/`
  under "通算制度に関するQ&A". Government publication. Public domain.
- **NTA tax-return form Beppyō** (Schedule 6-3 in particular for
  group-tsuusan adjustment): NTA forms portal. Public domain.

### §5.2. Secondary sources (citation-only, paraphrase, no lifting)

The standard "Big 4" worldwide tax summaries — PwC, KPMG, Deloitte,
EY, BDO — all publish public-access annual jurisdiction summaries on
group-tax regimes. These are **read for shape and worked-example
verification**; we cite them in a research note but do not lift
text. Same posture the BR and IN CIT blueprints adopt.

Relevant URLs (reading list, not lifting):
- `https://taxsummaries.pwc.com/united-states/corporate/group-taxation`
- `https://taxsummaries.pwc.com/japan/corporate/group-taxation`
- `https://kpmg.com/jp/en/home/insights/2022/02/group-tsuusan-system.html`
  (KPMG Japan's English-language group-tsuusan introduction)
- `https://www2.deloitte.com/jp/en/pages/tax/topics/jpcit.html`
  (Deloitte Japan CIT overview)

For US §1502 specifically, the BNA Tax Management Portfolios
(700-T.M., 701-T.M., 702-T.M. series on consolidated returns) are
the canonical practitioner reference. These are paid; we do not
lift. The freely available IRS Audit Technique Guide (ATG) on
consolidated returns is a good public-domain alternative for
practitioner shape.

### §5.3. What MUST be primary-source in the eventual ADR

When note 167 drafts the ADR, statutory citations MUST be primary:

- US: cite Treas. Reg. §1.1502-X subsections by number.
- JP: cite CTA articles by Romanized article number (e.g., "CTA
  Art. 64-5 §1", not "法人税法第六四条の五第一項").
- Effective-date precision: every cited authority gets an explicit
  effective-from date. The 2022 boundary in JP is the prime example.

---

## §6. What kontor needs — substrate preview (NOT prescription)

Given the US/JP-specific twists surfaced above, the substrate Gap #8
design must accommodate AT LEAST four mechanics not present in
kontor today:

1. **Per-member compute vs single-base compute** — both regimes
   must be expressible.
2. **Stateful intercompany-deferred items** with multi-year
   lifecycle and matching/acceleration semantics.
3. **Per-member NOL provenance + cumulative scoreboard** for SRLY
   (US §1502-21(c)) and CTA Art. 57 §8 (JP).
4. **Intra-group settlement entities** (JP) and **several-liability
   projections** (US) attached to each (member, group-year).

Three candidate substrate options to be synthesized in note 167:

### §6.1. Option A — single primitive with regime-style flag

A single `:tax-group/regime` entity carries an enum
`:elimination-style :single-base | :per-member-with-netting`. The
kernel exposes one `GroupTaxProvider` protocol with a single
`compute-group-tax` entrypoint that branches on the flag. All four
regimes (DE / FR / US / JP) share the same provider record shape;
the flag selects DE/FR/US vs JP shape.

Pros: minimal kernel surface; existing l10n providers can compose.
Cons: the JP per-member shape has structurally different return
arity (N liabilities + intra-group settlement) from the DE/FR/US
single-liability shape; a single signature must accommodate both
or use a sum-type return.

### §6.2. Option B — separate primitives per shape

The kernel exposes TWO protocols / companion packages:
`kontor-tax-group-single-base` (for DE/FR/US/old-JP-連結納税) and
`kontor-tax-group-loss-allocation` (for post-2022-JP-group-tsuusan).
Per-jurisdiction providers pick ONE of the two abstractions.

Pros: each protocol's contract is internally consistent; the JP
provider's mandatory cash-settlement output is part of the protocol
contract, not an optional field.
Cons: two protocols means two kernel modules to maintain; consumers
need to know which to invoke for a given regime.

### §6.3. Option C — pure marginalize-over-`:tax-group`-axis with regime-specific `:base-transform`s

Following the ADR-101 statute-as-data shape: a `:tax-group/regime`
is itself a statute-data object, carrying provisions. The default
provision is "marginalize income over members in this group". A
DE/FR/US regime adds no further provision (the marginalized total IS
the base; one schedule, one liability). A JP regime adds a
`:loss-allocation-provision` that PRE-marginalizes losses across
members, returning a per-member adjusted base; then schedule
application is per-member.

Pros: zero kernel churn; rides ADR-101 already. The whole substrate
is "tax law as data" turtles-all-the-way-down.
Cons: the `:loss-allocation-provision` needs a new statute-vocab op
(per-member redistribution is not in the closed `:op` set of
ADR-101); the cash-settlement output is a side-effect of the
provision that no current statute-application machinery emits.

**Recommendation for note 167 to evaluate**: Option C is most
aligned with kontor's deflated-scope philosophy (note 99 — no new
event-framework; use existing primitives) and with ADR-101's
statute-as-data direction. But it requires extending the `:op`
vocabulary, which is a substantial substrate decision in its own
right. Options A or B may be the right v1; C may be the long-term
target. Note 167 to resolve.

### §6.4. What NOT to ship in v1

Explicit non-goals for the eventual ADR:

- **No Pillar Two compute**: CAMT (US) and jp Min-Tax (JP) are
  separate regimes against a different perimeter. Out of scope.
- **No US §382 ownership-change limitation**: separate workstream;
  interacts with SRLY but is its own engine.
- **No automated §1502-13 deferred-item emission from arbitrary
  intercompany transactions**: v1 should expose the *primitive*
  (the `:tax-intercompany-deferred-item` entity) and let consumers
  drive emission. Auto-detection from `:transaction/intercompany-
  pair-id` is a Phase 2 polish.
- **No state-level US combined-reporting**: California, Illinois,
  Massachusetts, etc. all have distinct combined/unitary regimes
  with separate axes (water's edge / worldwide / Finnigan / Joyce).
  Deferred to a US-state-tax workstream when state CIT lands.
- **No 2002-era JP 連結納税 backward-compatibility**: the regime
  was repealed 2022-04-01. Customers with pre-2022 historical
  filings are read-only (existing `:audit-doc` retention covers).

---

## §7. Validation checklist for note 167 (the ADR draft)

Before note 167 declares Gap #8 ADR-ready, the synthesizer should
verify against this reference note:

- [ ] §3.1 single-base vs per-member-with-netting structural
  difference is reflected in the chosen substrate option.
- [ ] §3.2 intercompany-deferred-item primitive (or its absence)
  is justified against US §1502-13 + JP CTA §61-13 mechanics.
- [ ] §3.3 second-perimeter framing is at minimum non-foreclosing.
- [ ] §4.1 US arithmetic + §4.2 JP arithmetic can be reproduced
  through the chosen substrate — at LEAST as a hand-traceable
  worked-example test fixture.
- [ ] Pre-affiliation NOL provenance is modeled (SRLY shape, §1.5
  + §2.4 — same primitive serves US + JP).
- [ ] Cash-settlement output of JP loss-allocation (§2.3 + §4.2
  result: ¥46.4M payable) is a first-class concept, not a
  side-effect-flag.
- [ ] Quasi-permanent election semantics are reflected in the
  status-machine (per §1.2 + §2.2).
- [ ] Termination side-effects (deferred-item snapback per §1.9 +
  §2.8) have a substrate primitive.
- [ ] Authority URIs (Cornell LII + e-Laws + NTA) are encoded as
  `:concept-iri` / `:provision/citation` per ADR-090.

---

## §8. Bibliography of primary sources (citations summary)

For traceability per the constraint "primary-source only, paraphrase
secondary":

### §8.1. US — IRC, Treas. Reg., IRS forms

1. IRC §1501 — Privilege to file consolidated returns.
2. IRC §1502 — Regulations (delegation).
3. IRC §1503 — Computation and payment of tax.
4. IRC §1504(a) — Affiliated group defined.
5. IRC §1504(b) — Excluded corporations.
6. IRC §11(b) — Corporate rate (21 % post-TCJA).
7. IRC §55 — Alternative minimum tax (post-IRA CAMT).
8. IRC §56A — Adjusted financial statement income (CAMT base).
9. IRC §59A — Base erosion and anti-abuse tax.
10. IRC §172(a)(2) — 80 % NOL deduction cap (post-TCJA).
11. IRC §382 — Limitation on NOLs following ownership change.
12. IRC §951A — GILTI inclusion.
13. Treas. Reg. §1.1502-1 — Definitions.
14. Treas. Reg. §1.1502-6 — Several liability.
15. Treas. Reg. §1.1502-11 — Consolidated taxable income.
16. Treas. Reg. §1.1502-12 — Separate taxable income.
17. Treas. Reg. §1.1502-13 — Intercompany transactions
    (deferred-and-matching).
18. Treas. Reg. §1.1502-19 — Excess loss accounts.
19. Treas. Reg. §1.1502-21 — NOL deduction (and SRLY in
    subsection (c)).
20. Treas. Reg. §1.1502-22 — Consolidated capital gains/losses.
21. Treas. Reg. §1.1502-24 — Consolidated charitable contribution.
22. Treas. Reg. §1.1502-26 — Consolidated DRD.
23. Treas. Reg. §1.1502-75 — Filing of consolidated returns (and
    election permanence in subsection (c)).
24. Treas. Reg. §1.1502-91 through -99 — Group-§382 application.
25. Form 1120 (consolidated return) — IRS.
26. Form 851 (Affiliations Schedule) — IRS.
27. Form 1122 (Consent of Subsidiary Corporation) — IRS.

Total US-side primary citations: **27**.

### §8.2. JP — Corporate Tax Act, Cabinet Order, NTA

1. CTA Art. 2 No. 12-7-6 — Complete-control relationship
   (完全支配関係) definition.
2. CTA Art. 2 No. 12-8 — Qualified merger definition.
3. CTA Art. 57 §1 — NOL carryforward base rule.
4. CTA Art. 57 §8 — Pre-group-tsuusan NOL SRLY-equivalent.
5. CTA Art. 57 §9 — Group-tsuusan NOL inheritance on member
   change.
6. CTA Art. 57 §11 — Large-corporation 50 % NOL deduction cap.
7. CTA Art. 57-2 — Qualified-merger NOL succession.
8. CTA Art. 61-11 — 100 %-group intra-group transactions
   (always-on, separate from group-tsuusan).
9. CTA Art. 61-13 — Deferral of gain/loss on 100 %-group
   intra-group asset transfers.
10. CTA Art. 64-5 — Group-tsuusan loss-allocation (損益通算)
    mechanics.
11. CTA Art. 64-5 §5 — Intra-group settlement formula.
12. CTA Art. 64-9 — Group-tsuusan election application.
13. CTA Art. 64-10 — Group-tsuusan termination.
14. CTA Art. 64-11 — Effective-date and broken-period rules.
15. CTA Art. 64-13 — Termination acceleration of deferred items.
16. CTA Art. 66 §6 — SME-status disqualification for ≥¥500M-parent
    sub.
17. CTA Art. 67 — Rounding rules.
18. CTA Art. 75-12 — Joint-and-several liability for group-tsuusan
    members.
19. CTA Art. 152 — Tax-collection rules adapted for group-tsuusan.
20. CTA Chapter 1-2 (new from FY 2024-04-01) — jp Min-Tax (Pillar
    Two IIR domestic implementation).
21. Reiwa-2 Law No. 8 (令和2年法律第8号) — 2022 Tax Reform Act
    enacting group-tsuusan.
22. Reiwa-5 Law No. 3 (令和5年法律第3号) — 2023 Tax Reform Act
    enacting jp Min-Tax.
23. NTA group-tsuusan Q&A (通算制度に関するQ&A) — periodic NTA
    publication.
24. NTA Beppyō 6-3 (group-tsuusan adjustment schedule).
25. NTA Beppyō 7-1 (NOL carryover schedule with SRLY tagging).

Total JP-side primary citations: **25**.

---

## §9. Closing — what this note enables

This reference study gives note 167 (the eventual ADR draft) a
defensible base of statutory anatomy for the two non-European
group-tax regimes. The single most-structurally-different finding
— **JP group-tsuusan's per-member-filing-with-cross-member-loss-
allocation shape** — is the load-bearing constraint on the substrate
design and is highlighted in §3.1, §4.2, and §6.

Companion note 164 covers DE Organschaft + FR intégration fiscale
in the same depth. Companion note 166 maps these requirements
against the *current* kontor substrate to identify exactly what's
missing. Note 167 synthesizes all three into an ADR, picks among the
options in §6, and stages the build (typical pattern: substrate
first, then one pilot jurisdiction, then the other three).

The 2-4 substrate options surfaced in §6 (single-flag, two-protocol,
or statute-as-data extension) all require trade-off analysis against
the existing kontor-consolidation (ADR-073) shape and against the
ADR-101 statute-as-data substrate. The structural difference between
single-base (DE/FR/US) and per-member-with-netting (JP) is the axis
the answer must respect.

— end note 165 —

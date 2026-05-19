---
date: 2026-05-18
title: 93 — Employee-tracking privacy: legal/ethical framework + substrate
  posture for HR track-record and activity-monitoring data
status: research-before (informs an ADR-094 candidate on
  employee-monitoring substrate posture)
audience: maintainer — read before any consumer (`kontor-people-record`,
  `kontor-employee-monitoring`, future `kontor-it`) starts storing
  employee track-record or activity-monitoring data on the substrate
---

# 93 — Employee-tracking privacy

The maintainer is asking whether the kontor substrate can host
**employee track records** (career history, performance reviews,
training, certifications, skills) and — much more cautiously —
**employee activity data** (logon/logoff, application usage, screen
recordings, keystroke logs, communications archives, time-on-task
metrics). Both categories sit inside `:audit-doc` + `:partner`/
`:person` shaped data the substrate already stores. The substrate
question is therefore not *"can we?"* but *"what categories,
privilege values, retention floors, and consent records does the
substrate need to support so the consumer's legal posture is
correct by construction?"*

This note maps **nine concrete categories** of employee data to
existing or proposed substrate primitives, with **per-jurisdiction
retention floors** (DE / EU / US / UK / CA), **consent regimes**,
**access-control implications**, and **AI Act triggers**. It then
proposes a `:consent/*` mini-schema, eight new `:audit-doc/category`
values, and an ADR-094 sketch for the substrate posture on
employee monitoring.

A core thesis runs through this note: **the substrate is neutral;
refusal lives at the consumer policy + auth layer, not the kernel
(§7)**. The kernel never stops a consumer from transacting a
biometric emotion-recognition stream — but it offers the *vocabulary*
(`:audit-doc/category :hr-activity-biometric`,
`:consent/legal-basis :ai-act-incompatible`,
`:audit-doc/privilege :pii-sensitive`) that lets any responsible
consumer's auth layer refuse to write it, and lets a regulator
audit the refusal.

## §1 — TL;DR

**Add to the substrate.** Eight new canonical `:audit-doc/category`
values (open-set extensions, no schema change required — but worth
codifying in the schema doc-string + a `kontor.audit-doc/
canonical-categories` def per note 86 P0-86-2):

- `:hr-track-record` — career history, promotion log, training,
  certifications, skills, performance evaluations, 360 reviews,
  manager notes. Subsumes the existing `:hr-personnel`; `:hr-personnel`
  retained as the broader umbrella.
- `:hr-activity-monitoring` — logon/logoff, application usage,
  productivity metrics, time-on-task, telemetry from MDM /
  endpoint-management tools.
- `:hr-activity-content` — screen recordings, keystroke logs,
  webcam captures, screenshots. **The substrate's "do you really
  want to store this?" tripwire.**
- `:hr-communications` — email, chat, calls, meetings archived
  from corporate systems. Distinct because of the ePrivacy-
  Directive overlay.
- `:hr-background-check` — criminal records, credit checks,
  reference verifications, drug screening results. **Strict
  per-state restrictions in US; severely restricted in DE.**
- `:hr-compensation-negotiation` — equity grants, signing-bonus
  negotiations, performance-bonus discussions, market-band
  benchmarks. Adjacent to `:payroll` but the *standing data*, not
  the per-pay-period output.
- `:hr-grievance` — disciplinary actions, internal investigations,
  whistleblower reports, harassment complaints. **Frequently
  privileged under `:work-product` or `:attorney-client`.**
- `:hr-monitoring-consent` — the recorded consent / works-
  agreement / DPIA itself, the substrate's audit-doc that
  justifies any of the above. **Not the data being collected
  about the worker; the substrate's record of legal basis.**

**Add a `:consent/*` mini-schema** (§4.2) — eight attrs in
`kontor-hr` (or `kontor-consent` as a future cross-cutting
companion, since DSAR / marketing / health-record consent overlap):
`:consent/subject` (ref to `:person`) + `:consent/scope` (keyword)
+ `:consent/legal-basis` (keyword aligned with GDPR Art. 6(1) +
BDSG §26) + `:consent/granted-at` + `:consent/withdrawn-at` +
`:consent/supporting-doc` (ref to `:audit-doc`) +
`:consent/works-agreement-ref` (optional ref to `:audit-doc` of
the Betriebsvereinbarung) + `:consent/state` (status-machine:
`:proposed → :active → :withdrawn → :superseded`).

**Add retention-policy seeds** (§4.3) — `kontor-l10n-de`,
`kontor-l10n-us`, `kontor-l10n-gb`, `kontor-l10n-ca` companion
modules ship per-category × per-jurisdiction retention floors,
keyed against the eight new categories. The kernel ships no rate
data, no per-statute schedule — only the `:retention-policy`
shape (already exists; ADR-050) + the canonical category
vocabulary (open-set; §4.1).

**Refuse to ship** (§6) — kontor will NOT ship adapters for
real-time biometric emotion recognition (EU AI Act Art. 5(1)(f)
prohibits in the workplace from 2 Feb 2025); will NOT bundle
continuous-recording integrations with default-on configurations;
will NOT ship consumer-facing "productivity score" derived
metrics; will NOT pre-canonicalize categories the maintainer
believes are abusive (continuous keystroke, continuous webcam,
unmonitored screen recording without consent capture). The
substrate stores what the consumer transacts; but **the public
posture of the kontor project says some categories should never
be transacted at all without a DPIA + works-council agreement +
explicit consent record**.

**Do not block at the kernel level** (§7) — the substrate is
neutral. If a consumer's auth layer permits a write, the kernel
transacts it. Refusal is at the consumer-policy + auth + product-
positioning layer. The kernel's role is to provide the
*vocabulary* that lets refusal be expressed, audited, and
challenged.

**ADR-094 candidate** (§5) — "Employee-monitoring substrate
posture: category vocabulary + consent schema + retention floors
+ refusal positions."

**Surprises worth follow-up:**

1. **California CPRA terminated the employee-data exemption on
   1 January 2023** — the US/CA story is now structurally closer
   to GDPR than to ECPA-only. The substrate-level implication is
   that `:audit-doc/category :hr-personnel` data for CA employees
   carries DSAR obligations the substrate already supports (via
   ADR-052) but `kontor-hr`'s extension collector still needs to
   register `:partner/person` (note 86 P1-86-5 still open).
2. **The DE BAG 1 ABR 22/21 ruling (13 Sep 2022)** makes working-
   time recording **mandatory** under § 3 (2) Nr 1 ArbSchG — not
   optional. kontor's `:hr-activity-monitoring` category therefore
   has a *positive* obligation in DE, not a regulatory hazard.
   This changes the framing: the substrate is the *receptacle* for
   mandatory recording, not an optional convenience.
3. **EU AI Act Article 5(1)(f) is in force since 2 Feb 2025**,
   prohibiting workplace emotion-recognition AI; Article 5(1)(g)
   prohibits biometric categorisation by sensitive characteristics.
   These aren't future risks — they're current law. The substrate's
   refusal posture (§6) is overdue, not pre-emptive.
4. **EU AI Act Article 26 high-risk employment obligations apply
   from 2 December 2027** — deployer obligations including worker
   notification, log retention "at least 6 months," human
   oversight. The substrate needs a `:ai-deployer-log` shape by
   then; ADR-094 should schema-sketch this in advance.
5. **Illinois BIPA + Texas CUBI + Washington biometric statutes**
   apply private right of action for fingerprint time-clocks with
   damages up to $5,000 per intentional violation per scan.
   `kontor-hr` cannot ship a default fingerprint-time-clock
   integration; even the substrate-level "biometric template
   ingest" needs an explicit consent record per scan.

---

## §2 — Existing kontor privacy regime (recap)

kontor already ships a four-layer privacy substrate, codified by
ADR-038 / ADR-049 / ADR-050 / ADR-051 / ADR-052 / ADR-075 /
ADR-078:

1. **`:audit-doc/privilege`** (ADR-051) — legal-doctrine
   classification on documents: `:none | :attorney-client |
   :work-product | :joint-defense | :settlement-communication |
   :trade-secret | :pii-sensitive` (open-set, consumer-extendable).
   Status-machine facet; reclassification is approval-gated.
2. **`:audit-doc/category`** (ADR-075) — orthogonal subject-
   matter axis: `:financial | :payroll | :payroll-filing |
   :hr-personnel | :hr-medical | :hr-immigration | :tax-filing |
   :legal-proceeding | :compliance-attestation` (open-set; the
   note-86 P0-86-2 vocabulary fragmentation is still open). The
   two-axis (privilege × category) lets a consumer's auth layer
   express "HR role can access category `:payroll` regardless of
   privilege; tax-prep contractor can access category
   `:tax-filing` UNLESS privilege `:attorney-client`."
3. **`:audit-doc/language`** (ADR-078) — third orthogonal axis
   added with the CA-CRA module to handle pan-Canadian bilingual
   filings.
4. **`:retention-policy/category`** (ADR-075 retention slice) —
   the sweeper matches against `:audit-doc/category`; per-
   jurisdiction floors live in l10n companion modules.
5. **`:legal-hold/*`** (ADR-049) — preservation invariant
   structurally blocks destructive ops on held entities.
6. **`kontor.dsar/collect`** (ADR-052) — bitemporal DSAR walker
   that traverses `:partner` attrs + tx-attrs + extension
   collectors (e.g. HR's `:partner/person` bridge per note 86
   P1-86-5, still open).
7. **`:approval-policy/*`** (ADR-038) — codified reason
   vocabulary + supporting-doc slot + SoD invariants gate
   sensitive transitions including privilege reclassification.

**What's missing for the employee-monitoring use-case:**

- A category vocabulary that distinguishes "the employee's
  *track record* (career, performance)" from "the employee's
  *activity stream* (logon, screen, keystroke)." Both could fit
  `:hr-personnel` under ADR-075 but conflating them collapses
  legitimate-interest analysis under GDPR Art. 6(1)(f) — career
  data is contractually-grounded under Art. 6(1)(b), activity-
  stream data is legitimate-interest under Art. 6(1)(f) which
  requires a separate balancing test.
- A `:consent/*` shape that records the legal basis on which a
  category of data is being processed for a specific subject. The
  GDPR Art. 30 records-of-processing register is the regulatory
  cousin; kontor needs a *per-subject* consent record because
  Art. 7(3) gives the data subject the right to withdraw consent
  at any time, and the substrate's bitemporal model is uniquely
  good at recording that withdrawal as a fact at time T without
  losing the prior-consent-fact.
- A `:works-agreement/*` shape (or, more conservatively, an
  audit-doc `:type :works-agreement`) that anchors the
  Betriebsvereinbarung that BetrVG §87(1)(6) requires for any
  monitoring system in DE.
- A per-category retention-policy seed set for the new categories
  in each of DE / EU / US / UK / CA. The substrate ships the
  shape; companion modules ship the seeds.

---

## §3 — Categories 1-9: jurisdictional analysis + substrate
recommendations

For each category: (a) what existing or proposed
`:audit-doc/category` it maps to; (b) the privilege classification
typically applicable; (c) per-jurisdiction retention floor; (d)
consent regime; (e) access-control implications; (f) EU AI Act
triggers.

### 3.1 — Career + track record (job history, training, skills, certifications, promotions)

**Substrate mapping.** Existing `:audit-doc/category :hr-personnel`
is the umbrella; propose a finer-grained `:hr-track-record` for
the historical track-record subset that's most often pulled for
DSAR + transferred at internal mobility + retained longer than
operational HR data.

**Privilege.** `:audit-doc/privilege :pii-sensitive` is the
typical baseline. Performance evaluations with negative findings
that became part of a termination decision frequently escalate
to `:work-product` (litigation-prep documents) or `:attorney-
client` (counsel reviewed). The substrate's status-machine on
`:audit-doc/privilege` handles reclassification correctly
(ADR-051).

**Retention.**

| Jurisdiction | Floor | Source |
|---|---|---|
| DE | Until termination + 3 years (civil-suit limitation) for performance data; permanent for certificate (Arbeitszeugnis) reference | BGB § 195 (3-year regelmäßige Verjährung); § 109 GewO (Zeugnis); BDSG §26 + DSGVO Art. 5(1)(e) |
| EU baseline | "No longer than necessary for the purposes for which the personal data are processed" — typically 3-5 years post-termination | GDPR Art. 5(1)(e) |
| US federal | Title VII: 1 year from action; ADEA: 1 year (3 years for payroll); ADA: 1 year | 29 CFR § 1602.14; 29 CFR § 1627.3 |
| US-CA | Same federal floors + 4-year CPRA reasonable retention | Cal. Civ. Code § 1798.100(c) — necessity / proportionality |
| UK | "No longer than necessary"; ICO guidance suggests 6 years post-termination for breach-of-contract limitation | UK GDPR Art. 5(1)(e); Limitation Act 1980 s.5 |
| CA federal | "Only as long as necessary"; OPC guidance ~ employment-relationship + reasonable post-termination | PIPEDA Sch. 1, 4.5 |
| QC | Same + Law 25 strict purpose-limitation | LCCJTI |

**Consent regime.** GDPR Art. 6(1)(b) — performance of the
employment contract — is the standard basis. Consent is
problematic in employment contexts because of the BAG case-law
established power imbalance (BDSG §26(2) makes the "level of
dependence" a factor in any employee consent's validity).
Therefore the substrate's `:consent/legal-basis` value for this
category is typically `:gdpr-art-6-1-b-contract`, not
`:gdpr-art-6-1-a-consent`. The substrate records that fact so
later audits can confirm the controller never relied on the
weaker consent basis.

**Access control.** Standard `:audit-doc/privilege
:pii-sensitive`; consumer auth layer restricts to HR role +
direct line manager + worker themselves (DSAR right). For
performance reviews, the BAG case-law right-to-be-heard (BAG 12
July 2016 — 9 AZR 791/15) means a negative finding cannot be
filed until the worker has had opportunity to respond; the
substrate doesn't enforce that procedurally, but the
`:approval-policy` (ADR-038) shape composes for it
(`:approval-policy/rule :requires-worker-response-acknowledged`).

**AI Act triggers.** Article 26 high-risk obligations (from 2 Dec
2027) for AI-driven performance evaluation. Annex III, point 4(a):
"AI systems intended to be used for recruitment or selection of
natural persons, in particular for placing targeted job
advertisements, to analyse and filter job applications, and to
evaluate candidates." Point 4(b): AI for promotion / termination
decisions / task allocation / monitoring and evaluating
performance. **If kontor's consumer wires an LLM into performance-
review aggregation, Article 26 obligations attach** (deployer
informs workers + their reps; logs retained at least 6 months;
human oversight) — substrate needs to grow a `:ai-deployer-log`
shape by then. ADR-094 should schema-sketch this.

### 3.2 — Performance evaluations + 360 reviews + manager notes

**Substrate mapping.** Subset of `:hr-track-record`; or distinct
`:hr-performance-review` if the maintainer wants finer-grained
auth (the substrate is open-set, no kernel change required).
Performance reviews differ from raw career data in that they
carry **subjective assessments** that frequently become litigation
evidence — the privilege escalation path is more common.

**Privilege.** `:pii-sensitive` baseline; `:work-product`
escalation if litigation-anticipated; `:attorney-client` if
counsel-reviewed (e.g., PIP language drafted by employment
counsel). ADR-051's status-machine handles transitions.

**Retention.**

| Jurisdiction | Floor | Source |
|---|---|---|
| DE | 3 years post-evaluation typical (BGB § 195); longer if litigation-anticipated | BAG 12 Jul 2016 — 9 AZR 791/15 (right-to-be-heard) |
| EU baseline | Necessity-limited; 3-5 years post-termination typical | GDPR Art. 5(1)(e) |
| US federal | Title VII: 1 year minimum (employer must keep "personnel records" for 1 year from action); 4 years SOX if material to internal controls | 29 CFR § 1602.14; SOX § 802 |
| UK | 6 years for contract-breach limitation purposes | Limitation Act 1980 s.5 |
| CA | Necessity-limited per PIPEDA | PIPEDA Sch. 1, 4.5 |

**Consent regime.** Same as §3.1 — contractual basis, not
consent. The substrate's `:consent/legal-basis
:gdpr-art-6-1-b-contract` plus a `:consent/scope
:performance-evaluation` records the position.

**Access control.** Tighter than career data — worker + direct
manager + skip-level + HR business partner + (during litigation)
counsel. Consumer's RBAC enforces; kontor tags via privilege +
category.

**Right-to-be-heard.** DE BAG: a negative performance finding
cannot be filed in the personnel file until the worker has had
opportunity to respond. The substrate composes:
`:approval-policy/rule :requires-worker-response` blocks the
`:audit-doc/state :draft → :active` transition until a child
audit-doc (`:audit-doc/category :hr-track-record`,
`:audit-doc/type :worker-response`) is linked.

### 3.3 — Time tracking + activity logs (logon/logoff, application usage)

**Substrate mapping.** Propose new `:audit-doc/category
:hr-activity-monitoring`. Distinct from `:hr-track-record`
because (a) the consent regime is different (Art. 6(1)(f)
legitimate-interest, with balancing test required), (b) the
retention floor is shorter, (c) the works-council co-determination
under BetrVG §87(1)(6) attaches specifically.

**Privilege.** `:pii-sensitive`. Escalates only if the activity
log becomes evidence in a misconduct investigation (then
`:work-product`).

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | **Working-time records: MANDATORY** under § 3(2) Nr 1 ArbSchG per BAG 1 ABR 22/21 (13 Sep 2022); 2-year retention typical for general activity logs (Art. 17 right to erasure modulo legitimate interest) | ArbSchG § 22; BDSG §26 |
| EU baseline | Working-time: post-CCOO C-55/18 (14 May 2019) recording IS mandatory; data-minimisation favours shortest necessary | Directive 2003/88/EC; Charter Art. 31(2) |
| US federal | FLSA: payroll records 3 years, time-card records 2 years | 29 CFR § 516.5, 516.6 |
| UK | Working Time Regulations 1998 reg. 9: records "adequate to show" compliance, 2-year retention | WTR 1998 reg. 9 |
| CA | Per-province Labour Standards Act; typically 3 years | Canada Labour Code s. 252 |

**Consent regime.** **Activity logs sit on a knife-edge.** The
BAG and ECJ both require *some* working-time recording — the
substrate is the receptacle for that mandatory data. But broader
"productivity tracking" exceeds the necessary minimum and falls
under GDPR Art. 6(1)(f) legitimate-interest, which the DE BfDI
and the EDPB have repeatedly held does *not* automatically cover
employee monitoring — a documented balancing test (Art. 6(1)(f)
LIA — legitimate interests assessment) is required. The
substrate's `:consent/legal-basis :gdpr-art-6-1-f-legit-interest`
SHOULD reference a child audit-doc (`:audit-doc/type
:legitimate-interests-assessment`) carrying the LIA. The
`:approval-policy/rule :requires-supporting-doc` (ADR-038)
enforces this structurally.

**Works council in DE.** **MANDATORY** per BetrVG §87(1)(6) for
any technical device "intended to monitor employee behavior or
performance," interpreted broadly by the BAG (the device need
only be *objectively likely* to record behavioral information —
employer subjective intent irrelevant). The substrate models
this via the proposed `:consent/works-agreement-ref` slot
pointing to an audit-doc with `:audit-doc/type
:betriebsvereinbarung`.

**Access control.** Same `:pii-sensitive`; restricted to HR +
direct manager + worker (DSAR). **DE four-eyes principle on
retrieval** (no unilateral employer access without works-council
participation) — the substrate composes via `:approval-policy/
rule :no-self-approval` + works-council-representative role on
the second signer.

**Notice obligation (US states).** Distinct from consent:
multiple US states require *notice* even when consent isn't
required.

- **NY Civ Rights L. § 52-c** (eff. 7 May 2022) — written notice
  + employee acknowledgment + posted notice; AG-only enforcement;
  $500 / $1,000 / $3,000 graduated civil penalties.
- **CT Gen Stat § 31-48d** (1998) — prior written notice +
  posting in conspicuous place; $3,000 civil penalty per offense.
- **DE 19 Del C § 705** — daily electronic notice OR one-time
  notice + acknowledgment; written/electronic record of
  acknowledgment.

Per-state notice texts can live in `kontor-l10n-us` as audit-doc
templates referenced from the `:consent/supporting-doc` slot.

**AI Act triggers.** Article 26 obligations for "monitoring and
evaluating performance" AI (Annex III 4(b)) — from 2 Dec 2027.
**Article 5(1)(f) prohibition on emotion recognition triggers if
activity logs feed an emotion-inference model.** The substrate's
refusal posture (§6) is firm here.

### 3.4 — Screen recordings + keystroke logs + webcam continuous capture (the most regulated)

**Substrate mapping.** Propose new `:audit-doc/category
:hr-activity-content`. Distinct from `:hr-activity-monitoring`
(metric / event data) because of the *content* of the recording —
keystroke streams capture personal communications, webcam
captures faces (biometric data under GDPR Art. 9), screen
recordings capture incidental third-party communications.

**This is the substrate's "are you sure?" tripwire category.**

**Privilege.** `:pii-sensitive` always; frequently overlaps
`:trade-secret` (the screen content itself is the employer's IP)
and Art. 9 special-category data (faces, biometric identifiers,
incidental medical/religious/political content visible on
screen).

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | "As short as possible" — weeks not years typical; LAG / BAG case law on video surveillance (BAG 23 Aug 2018 — 2 AZR 133/18) requires deletion when purpose fulfilled | BDSG §26; BAG 29 Jun 2023 — 2 AZR 296/22 |
| EU baseline | Data-minimisation principle (Art. 5(1)(c)) overrides any default retention | GDPR Art. 5(1)(c)+(e) |
| US federal | None at the federal level for screen recording specifically; FLSA may govern if used as work-time evidence | ECPA business-purpose exception |
| US (BIPA states IL/TX/WA) | Per-scan damages; biometric template retention max 3 years from last interaction | 740 ILCS 14/15(a) |
| UK | ICO 2023 guidance: covert monitoring requires "exceptional circumstances" + DPIA + the least-intrusive method | ICO "Employment practices: Monitoring at work" (Oct 2023) |
| CA | Reasonable purpose + proportionate + minimally intrusive; QC consent + notice mandatory | PIPEDA; QC Law 25 |

**Consent regime.** **DSFA / DPIA required under GDPR Art. 35
without exception** for systematic monitoring of this category
(EDPB confirmed at 2017 WP248 + 2018 EDPB confirmation). The
substrate's `:consent/supporting-doc` MUST point to an audit-doc
with `:audit-doc/type :dpia-art-35`. Refusing to write the data
when no DPIA audit-doc is linked is a consumer-policy decision;
the substrate provides the slot.

**BAG case-law.** LAG Mecklenburg-Vorpommern 24 May 2023 — 5 Sa
234/22: continuous screen monitoring of customer-service rep
disclosed at hiring + Betriebsvereinbarung in place + DSFA
documented = lawful; without all three, illegal + data is
prozessual unverwertbar (inadmissible as evidence). The
substrate's three slots — `:consent/granted-at` (worker
disclosure date), `:consent/works-agreement-ref` (BV),
`:consent/supporting-doc` (DSFA) — directly mirror the
three-pillar test.

**Access control.** `:pii-sensitive` + **DE four-eyes principle**
on retrieval: no unilateral employer access. Audit-doc retrieval
should compose with `:approval-policy/rule :no-self-approval`
where the second approver is a works-council representative role.
**Substrate-level enforcement is policy, not invariant** —
ADR-094 should land this as a consumer-side recommendation, not
a kernel check (we don't break the substrate-neutrality posture
in §7).

**AI Act triggers.** **Article 5(1)(f) absolute prohibition** on
AI inferring emotions in the workplace, from 2 Feb 2025. If the
screen recording or webcam feed is run through emotion-inference
AI, it's a prohibited practice. **The substrate should refuse to
ship a default integration with any emotion-inference vendor and
publish that refusal** (§6). Article 5(1)(g) prohibition on
biometric categorisation by sensitive characteristics (race,
political opinion, sexual orientation) — same posture: refuse
default integration; substrate-level it's a category value
(`:audit-doc/category :hr-activity-content`,
`:consent/legal-basis :ai-act-incompatible`) that exists so
refusal can be expressed.

### 3.5 — Communications archive (email, Slack, calls, meetings)

**Substrate mapping.** Propose new `:audit-doc/category
:hr-communications`. Distinct because of the ePrivacy Directive
overlay and the DE TKG telecommunications-secrecy framing.

**Privilege.** Wildly variable. Same email thread can be
`:none` (routine ops), `:trade-secret` (commercial-sensitive
proposal), `:attorney-client` (in-house counsel involved),
`:work-product` (litigation prep). The substrate's
`:audit-doc/privilege` status-machine + ADR-052's DSAR-bundle
privilege-filtering helper (`filter-by-privilege`) already
handle this; the new category just gives auth layers a clean
hook.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | HGB § 257 (commercial correspondence): 6 years; § 147 AO (tax-relevant): 8 years; **TKG private-use creates secrecy implications** | HGB § 257; AO § 147; § 88 TKG |
| EU baseline | Data-minimisation; sector-specific (financial-services MiFID II 5 years) | GDPR + sectoral law |
| US federal | SOX § 802: 7 years for accounting / audit-related; FINRA: 3-6 years | 18 USC § 1519; SOX § 802 |
| UK | 6 years contract-limitation typical | Limitation Act 1980 |
| CA | PIPEDA Sch. 1, 4.5 + ITA: 6 years for tax-relevant | PIPEDA; ITA s.230 |

**Consent regime.** **GDPR Art. 6(1)(b) for business communications**
(necessary for contract performance) + **ePrivacy Directive Art.
5(1) confidentiality of communications** restriction.

**The DE TKG private-use trap.** If the employer permits private
use of corporate email, the employer becomes a
*Telekommunikationsanbieter* (telco provider) under TKG; the §
88 secrecy obligation attaches; any monitoring of the private
content is criminal under § 206 StGB (Verletzung des
Postgeheimnisses). The conservative kontor-hr posture: ship a
default `:works-agreement` template that bans private use of
corporate communications channels, eliminating the TKG question.
**This is a consumer-policy recommendation, not a substrate
change.**

**Access control.** Privilege-driven. Consumer's auth layer needs
to combine `:audit-doc/privilege` + `:audit-doc/category
:hr-communications` to drive policy. The substrate already has
the shape.

**AI Act triggers.** Article 26 high-risk if AI is used for
sentiment / risk-scoring on internal communications (could feed
into "monitoring and evaluating performance" under Annex III
4(b)). Article 5(1)(f) prohibition if emotion inference is run
on call audio.

### 3.6 — Health + medical (already :hr-medical category)

**Substrate mapping.** Existing `:audit-doc/category :hr-medical`
is correct; no new category needed. The note is that medical
data is **GDPR Art. 9 special-category data** and the legal
basis must be Art. 9(2)(b) — employment/social-security
obligation under Member State law — or Art. 9(2)(h) — medicine /
occupational health. Plain Art. 6(1)(b) is NOT sufficient.

**Privilege.** `:pii-sensitive` + frequently `:medical-officer-
only` (consumer-extension; not a kernel value). Occupational
health data accessed by Betriebsarzt only; HR sees fitness-for-
duty findings, not underlying diagnosis.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | **30 years** for hazardous-substance exposure records (GefStoffV; though current text says 40 years in some interpretations); 10 years for occupational health surveillance (ArbMedVV) | GefStoffV; ArbMedVV § 7 |
| EU baseline | Special-category data — strict necessity test; long retention only with Member State law backing | GDPR Art. 9(2)(b)+(h) |
| US federal | OSHA 29 CFR 1910.1020: employee medical records for duration of employment + 30 years; ADA confidential medical records separate file | 29 CFR 1910.1020 |
| UK | Same EU baseline; ICO occupational-health guidance | UK GDPR Art. 9 + DPA 2018 Sch. 1 Pt 1 Para 1 |
| CA | OSH-equivalent retention; varies by province | Per-province OHS |

**Consent regime.** Art. 9(2)(b) employment-law basis is the
standard; explicit consent (Art. 9(2)(a)) is structurally
suspect because of power imbalance per BDSG §26(2). The
substrate's `:consent/legal-basis :gdpr-art-9-2-b-employment-law`
+ `:consent/scope :occupational-health` records the position.

**Access control.** Medical-officer-only on the underlying
record; fitness-for-duty extract surfaces to HR. The substrate
composes via:
- `:audit-doc/category :hr-medical` + `:audit-doc/privilege
  :pii-sensitive` on the underlying record;
- a child `:audit-doc/category :hr-personnel` + `:audit-doc/
  privilege :pii-sensitive` "fitness-for-duty summary" linked
  via `:audit-doc/parent` (this attr doesn't exist; ADR-094
  can propose it as an open-set extension or `kontor-hr` can
  ship it as a module-local shape).

**AI Act triggers.** Health is Art. 9; AI applications to health
data trigger Art. 26 high-risk obligations + GDPR Art. 22
automated-decision-making restrictions. Substrate doesn't change.

### 3.7 — Immigration + visa + work-permit (already :hr-immigration category)

**Substrate mapping.** Existing `:audit-doc/category
:hr-immigration` is correct.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | AufenthG / AsylG records; typically 3 years post-employment for employer copies | AufenthG § 4 |
| US federal | I-9: 3 years post-hire OR 1 year post-departure, whichever is LATER | 8 CFR § 274a.2(b)(2) |
| UK | Right-to-Work: duration of employment + 2 years | UK Immigration Rules; Home Office RtW guidance |
| CA | Work permit records: typically duration + 2 years | IRPA |

**Consent regime.** Art. 6(1)(c) legal-obligation basis — employer
verifies right-to-work under statute. The substrate records
`:consent/legal-basis :gdpr-art-6-1-c-legal-obligation` +
`:consent/scope :immigration-verification`.

**Access control.** `:pii-sensitive`; restricted to HR. Standard.

**AI Act triggers.** None directly; AI risk-scoring of immigration
status would trigger Annex III 7 (migration / asylum / border
control) — almost always public-sector use, not employer.

### 3.8 — Background checks + criminal records

**Substrate mapping.** Propose new `:audit-doc/category
:hr-background-check`. Distinct because the legal regime is
*highly* jurisdiction-specific: severely restricted in DE, ban-
the-box waves in US, per-state-and-province variation.

**Privilege.** `:pii-sensitive` always; potentially `:work-product`
if obtained for litigation defense.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | **Severely restricted.** Police-record (Führungszeugnis) data may only be retained as long as necessary — typically deletion after hiring decision; criminal-record use only where job-relevant | § 30 BZRG; BAG case law |
| EU baseline | Art. 10 — criminal-conviction data only under "control of official authority or when authorised by Union or Member State law" | GDPR Art. 10 |
| US federal | FCRA: background-check results for 7 years (3 years for conviction in some statutes); Title VII disparate-impact case law (Griggs / Green) | 15 USC § 1681c |
| US-CA | **Fair Chance Act** (eff. 1 Jan 2018): pre-conditional-offer inquiry banned for employers ≥5; individualized assessment required | Cal. Gov. Code § 12952 |
| US-NYC | Local Law: pre-conditional-offer ban for both public + private | NYC Admin Code § 8-107(11)(a) |
| UK | DBS checks: criminal-record certificate has limited valid life; spent convictions under Rehabilitation of Offenders Act 1974 | ROA 1974 |
| CA | Per-province human-rights legislation often bars discrimination based on record-suspended convictions; PIPEDA limits | Canada Human Rights Act s.3(1) |

**Consent regime.** Where lawful, GDPR Art. 10 + Member State
law authorisation. **Without that authorisation, the data may
not be processed at all** — the substrate's
`:consent/legal-basis :gdpr-art-10-criminal-records-msl` records
which Member State law the controller is relying on (e.g.,
"§ 32 BDSG + § 30 BZRG für Sicherheitsbedarf"). If the value is
nil, the consumer-policy layer SHOULD refuse the write.

**Access control.** `:pii-sensitive`; restricted to HR head + role-
necessity-defined senior officers. Bitemporal: a conviction that
becomes "spent" under ROA 1974 (UK) or a "record suspension"
under the Criminal Records Act (CA) should become inaccessible to
new queries while still being preserved (legal-hold may attach).
The substrate's `:as-of-valid` query model handles this — at
valid-time T1 the record is "active," at T2 (post-suspension)
the same record can have its `:audit-doc/privilege` upgraded to
a consumer-extension keyword `:spent-conviction` that the auth
layer hard-filters.

**AI Act triggers.** Annex III 4(a) — AI in recruitment / job-
application filtering. If criminal-record data feeds an AI hiring
filter, Article 26 obligations apply from Dec 2027.

### 3.9 — Compensation history (negotiation + equity grant + market-band)

**Substrate mapping.** Propose new `:audit-doc/category
:hr-compensation-negotiation`. Distinct from `:payroll` (the per-
pay-period output) and from `:payroll-filing` (the regulator-
bound filings). This is the *standing data* around how
compensation was set + the contemporaneous negotiation /
benchmarking records.

**Privilege.** `:pii-sensitive`; often `:trade-secret` for market-
band data that includes peer compensation; potentially
`:attorney-client` for equity-grant memos drafted by counsel.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | 10 years for tax-relevant (§ 147 AO) where the comp record supports payroll filings; 3 years otherwise | AO § 147 |
| EU baseline | Necessity-limited | GDPR Art. 5(1)(e) |
| US federal | FLSA 3 years for payroll-relevant; SOX 7 years for executive comp tied to material disclosures | 29 CFR § 516.5; SOX § 802 |
| US-CA | Pay-equity reporting (SB 1162): 3 years per role | Cal. Lab. Code § 432.3 |
| UK | Gender Pay Gap Reporting (Equality Act 2010) — relevant calc data preserved | Equality Act 2010 (Gender Pay Gap Information) Regs 2017 |
| CA | Per-province pay-equity legislation | Pay Equity Act (CA federal); QC Loi sur l'équité salariale |

**Consent regime.** Art. 6(1)(b) contractual basis for the
compensation-setting itself + Art. 6(1)(c) legal-obligation
basis for pay-equity reporting.

**Access control.** `:pii-sensitive` + frequently `:trade-secret`;
restricted to HR head + compensation committee + role-necessity-
defined senior officers + (subject's own data) the worker
themselves.

**AI Act triggers.** Annex III 4(b) — AI for "deciding on
promotion or termination ... determining task allocation,
monitoring or evaluating the performance and behavior of
persons in such relationships." Pay-setting AI is firmly inside.

### 3.10 — Disciplinary / grievance / whistleblower

**Substrate mapping.** Propose new `:audit-doc/category
:hr-grievance`. Distinct because the privilege classification is
frequently `:work-product` or `:attorney-client` from the outset
(internal-investigation memos), and the retention floor is
litigation-anticipation-driven.

**Privilege.** `:pii-sensitive` + `:work-product` very common +
`:attorney-client` if counsel involved + `:joint-defense` in
multi-party investigations.

**Retention.**

| Jurisdiction | Floor | Source / Note |
|---|---|---|
| DE | 3 years (Verjährung) typical; longer if termination contested + still in court | BGB § 195; BetrVG § 102 |
| EU baseline | Necessity-limited; HinSchG / EU Whistleblower Directive 2019/1937: at least the duration of follow-up + reasonable | Directive 2019/1937 Art. 18 |
| US federal | EEOC: 1 year from action; SOX § 1107 if retaliation-relevant; Dodd-Frank whistleblower § 922 | 29 CFR § 1602.14 |
| UK | 6 years contract-limitation typical; ACAS Code | ACAS Code of Practice |
| CA | Per-province + federal Public Servants Disclosure Protection Act | PSDPA |

**Consent regime.** Art. 6(1)(c) legal-obligation (HinSchG / Whistleblower Directive transposition) for whistleblower channels; Art. 6(1)(f) legitimate-interest for general grievance handling with documented LIA.

**Access control.** `:work-product` / `:attorney-client` levels;
investigator role + outside counsel + (in some narrow paths)
HR head. Whistleblower identity protected under Directive
2019/1937 Art. 16 — the substrate's `:audit-doc/privilege
:whistleblower-identity` (consumer-extension) marks the slot.

**AI Act triggers.** Annex III 4(b) for AI-driven
investigation tooling.

### 3.11 — Children's data (parental consent for under-18 interns)

**Substrate mapping.** Existing `:audit-doc/category
:hr-personnel`; no new category. But the consent regime is
distinct: GDPR Art. 8(1) sets a baseline of 16 for valid
consent in information-society services; Member States may
lower to 13. For under-16 workers (DE Berufsausbildung permits
apprentices from 15 in some trades), parental co-consent is
required.

**Consent regime.** `:consent/legal-basis :gdpr-art-6-1-b-contract`
+ `:consent/parental-co-consent-ref` (additional optional slot
in the `:consent/*` schema; or a child consent record linked
via `:consent/parent-consent`).

### 3.12 — Trade-union membership

**Substrate mapping.** `:hr-personnel`; but **Art. 9 special-
category data** — Art. 9(1) explicitly lists "trade union
membership." Processing prohibited except under Art. 9(2). In
DE, works-council members and union representatives have
specific protected statuses.

**Privilege.** `:pii-sensitive` + arguably `:protected-status`
(consumer-extension).

**Access control.** Restricted to HR head + payroll (if dues
deducted at source).

**Substrate posture.** No new category; flag in the §8
companion sketch that storing membership status is a careful
choice, not a default.

---

## §4 — Recommended kernel additions

### 4.1 — New canonical `:audit-doc/category` values

Add to the schema doc-string at `src/kontor/schema.clj:3733-3743`
(or via a new `kontor.audit-doc/canonical-categories` def
addressing note 86 P0-86-2):

```clojure
;; Canonical categories (open-set; consumers extend):
;;   :financial
;;   :payroll                       — per-period output
;;   :payroll-filing                — regulator-bound payroll filings
;;   :hr-personnel                  — umbrella for HR data
;;   :hr-track-record               — career history, training, performance
;;   :hr-activity-monitoring        — logon/logoff, productivity metrics
;;   :hr-activity-content           — screen / keystroke / webcam
;;   :hr-communications             — email, chat, call archive
;;   :hr-medical                    — Art. 9 special-category
;;   :hr-immigration                — visa / work-permit / I-9
;;   :hr-background-check           — criminal records, credit, references
;;   :hr-compensation-negotiation   — equity, signing-bonus, market-band
;;   :hr-grievance                  — disciplinary, investigation, whistleblower
;;   :hr-monitoring-consent         — the consent / DPIA / works-agreement itself
;;   :tax-filing                    — tax-authority-bound filings
;;   :legal-proceeding
;;   :compliance-attestation
```

Open-set — consumers extend (`:hr-equity-vesting`,
`:hr-secondment-agreement`, etc.). No schema migration; the
attr `:audit-doc/category` already accepts any keyword.

### 4.2 — Proposed `:consent/*` mini-schema

Lives in `kontor-hr` (the immediate consumer); promoted to a
`kontor-consent` cross-cutting companion if marketing / health-
record / DSAR-consent overlap surfaces (likely; flagged in §10
open questions).

```clojure
;; :consent — per-subject, per-scope legal-basis record. ADR-094.
;; Bitemporal substrate captures consent + withdrawal as facts at time T;
;; (d/valid-at db T) answers "what was the legal basis at T?"

:consent/code                    string  one  identity   ; opaque consumer ID
:consent/subject                 ref     one             ; → :person
:consent/scope                   kw      one             ; matches :audit-doc/category
:consent/legal-basis             kw      one             ; see vocabulary below
:consent/granted-at              inst    one
:consent/withdrawn-at            inst    one             ; nil = still in force
:consent/supporting-doc          ref     one             ; → :audit-doc
                                                         ; (the DPIA, LIA, consent form)
:consent/works-agreement-ref     ref     one             ; → :audit-doc with
                                                         ; :audit-doc/type
                                                         ; :betriebsvereinbarung
:consent/state                   kw      one             ; ADR-034 facet:
                                                         ; :proposed → :active →
                                                         ; :withdrawn → :superseded
:consent/parent-consent          ref     one             ; for parental co-consent
                                                         ; (optional; → :consent)
:consent/notice-acknowledged-at  inst    one             ; for US-state notice statutes
                                                         ; (NY 52-c, CT 31-48d, DE 19/705)
```

`:consent/legal-basis` vocabulary (open-set; consumer-extendable):

| Value | Meaning |
|---|---|
| `:gdpr-art-6-1-a-consent` | Explicit consent (rare for employees) |
| `:gdpr-art-6-1-b-contract` | Performance of contract |
| `:gdpr-art-6-1-c-legal-obligation` | Legal obligation |
| `:gdpr-art-6-1-d-vital-interests` | Vital interests |
| `:gdpr-art-6-1-e-public-task` | Public task |
| `:gdpr-art-6-1-f-legit-interest` | Legitimate interest (LIA required in `:supporting-doc`) |
| `:gdpr-art-9-2-a-explicit-consent` | Special-category, explicit consent |
| `:gdpr-art-9-2-b-employment-law` | Special-category, employment-law basis |
| `:gdpr-art-9-2-h-occupational-medicine` | Special-category, occupational health |
| `:gdpr-art-10-criminal-records-msl` | Criminal-record data, Member State law |
| `:bdsg-26-1-employment` | BDSG §26(1) employment-relationship necessity |
| `:bdsg-26-3-special-category` | BDSG §26(3) special-category in employment |
| `:bdsg-26-4-collective-agreement` | BDSG §26(4) works-agreement basis |
| `:works-agreement` | Generic Betriebsvereinbarung / co-determination |
| `:ai-act-incompatible` | Substrate-level refusal marker (§6, §7) |
| `:withdrawn` | Consent was withdrawn; only retain if other basis applies |

Status-machine: `kontor.consent/grant!` + `kontor.consent/
withdraw!` + `kontor.consent/supersede!`. Withdrawal is a
recorded fact at time T — earlier processing under the prior
consent remains lawful for the period it was active; processing
after T must rely on a different `:consent/legal-basis` or stop.

### 4.3 — Retention-policy seeds per (jurisdiction × category)

Companion modules (`kontor-l10n-de`, `kontor-l10n-us`,
`kontor-l10n-gb`, `kontor-l10n-ca`, `kontor-l10n-eu`) ship seed
data. Kernel ships no rate data. Example shape for `kontor-l10n-de`:

```edn
{:retention-policy/code "DE-BDSG-track-record"
 :retention-policy/applies-to #{:audit-doc}
 :retention-policy/category   :hr-track-record
 :retention-policy/jurisdiction "DE"
 :retention-policy/duration-years 3
 :retention-policy/triggered-by :audit-doc/uploaded-at
 :retention-policy/legal-basis "BGB §195 (Regelmäßige Verjährung) / BDSG §26 / DSGVO Art. 5(1)(e)"
 :retention-policy/expiry-action :anonymize}

{:retention-policy/code "DE-GefStoffV-occupational-exposure"
 :retention-policy/applies-to #{:audit-doc}
 :retention-policy/category   :hr-medical
 :retention-policy/jurisdiction "DE"
 :retention-policy/duration-years 30
 :retention-policy/triggered-by :audit-doc/uploaded-at
 :retention-policy/legal-basis "GefStoffV §10a / ArbMedVV §7"
 :retention-policy/expiry-action :archive-to-cold-storage}

{:retention-policy/code "DE-BetrVG-activity-content-floor"
 :retention-policy/applies-to #{:audit-doc}
 :retention-policy/category   :hr-activity-content
 :retention-policy/jurisdiction "DE"
 :retention-policy/duration-years 0    ; deliberately 0; consumer must
                                       ; set a per-deployment shorter floor
 :retention-policy/triggered-by :audit-doc/uploaded-at
 :retention-policy/legal-basis "DSGVO Art. 5(1)(c) Datenminimierung + BAG case-law"
 :retention-policy/expiry-action :purge}
```

The `:hr-activity-content` seed at 0 years is the substrate's
strongest statement: the *default* floor is "delete immediately
unless the consumer specifically overrides with a documented
purpose + DPIA + works-agreement."

Per-state US notice templates (NY 52-c, CT 31-48d, DE 19/705)
ship as `:audit-doc/type :electronic-monitoring-notice` template
audit-docs in `kontor-l10n-us`, referenceable from
`:consent/supporting-doc`.

---

## §5 — Recommended ADR shape — ADR-094 sketch

**ADR-094 — Employee-monitoring substrate posture: category
vocabulary + consent schema + retention floors + refusal positions.**

**Decision (load-bearing).**

1. **Categories.** Add eight canonical `:audit-doc/category`
   values (§4.1). No schema change; documentation in `src/kontor/
   schema.clj:3733-3743` + a new `kontor.audit-doc/
   canonical-categories` def (composes with note 86 P0-86-2's
   canonicalization need).

2. **Consent schema.** `:consent/*` (§4.2) lives in `kontor-hr`
   initially. Promotion to `kontor-consent` cross-cutting
   companion deferred until the second consumer needs it
   (likely DSAR per ADR-052 — the maintainer should make this
   call within 1-2 stages).

3. **Per-jurisdiction retention seeds.** `kontor-l10n-{de,us,gb,
   ca,eu}` companion modules ship per-category seeds (§4.3).
   Kernel ships shape only.

4. **Substrate neutrality.** Kernel does NOT block writes. The
   `:consent/legal-basis :ai-act-incompatible` value exists for
   consumer-policy refusal; the kernel never enforces it.

5. **Project refusal posture** (§6). The kontor project
   publicly refuses to:
   - Ship a default integration with any real-time biometric
     emotion-recognition vendor (AI Act Art. 5(1)(f)).
   - Pre-canonicalize categories the maintainer believes
     facilitate abuse (e.g. `:hr-emotion-score` will not be
     added to the canonical category list, even though the
     substrate cannot prevent a consumer from using it as an
     extension keyword).
   - Bundle continuous-recording integrations with default-on
     configurations.
   - Ship consumer-facing "productivity score" derived metrics
     in the kernel or first-party companions. (Third-party
     companions can; the project does not endorse.)

6. **AI Act forward-compatibility.** A `:ai-deployer-log/*`
   shape is reserved for the Dec 2027 Article 26 obligations
   (logs retained at least 6 months, deployer / provider chain,
   human-oversight events). Schema-sketched in ADR-094; full
   landing in ADR-095 (separate; ~Q3 2027 timing).

**Consequences.**

- `kontor-hr` companion grows ~10 attrs (the `:consent/*` group
  + 2-3 `:works-agreement/*` slots).
- Five `kontor-l10n-*` modules grow retention-policy seed data
  (no schema change; install-time `transact!`).
- `kontor.audit-doc/canonical-categories` codifies open-set
  vocabulary (composes with note 86 P0-86-2 fix).
- A `kontor.consent` namespace with `grant!` + `withdraw!` +
  `active-at?` helpers (~80 LOC).
- A `kontor.consent/active-at-tx-data` builder following the
  ADR-068 convention.
- Two new ADR-038 `:approval-policy/rule` values:
  `:requires-dpia-supporting-doc` and
  `:requires-works-agreement-ref`.
- Per-jurisdiction DSAR walker registrations: each l10n module
  registers `:consent` + `:audit-doc/category` collectors with
  the ADR-052 DSAR registry.

**Non-decisions (deferred):**

- AI deployer log shape — ADR-095, ~Q3 2027.
- Biometric template separate entity (vs. embedding hash + tag
  in audit-doc) — pushed to consumer-needs.
- "Right to be forgotten" purge-vs-anonymize default per
  category — handled per-category in retention-policy seeds, not
  as a kernel decision.

**Open vs. closed-set decisions.** All new vocabularies are
open-set keywords. The kontor project endorses the canonical
form (§4.1) but the substrate never restricts. This matches the
ADR-051 / ADR-075 / ADR-078 precedent.

---

## §6 — What kontor should REFUSE to do (positions to take publicly)

The substrate is neutral (§7); the *project* takes positions.
This is the analog of the "no UI" (ADR-010), "no Avalara keys
bundled" (ADR-005), "no Odoo translation" (ADR-001) rules — what
kontor refuses to ship even if the substrate could technically
host it.

### 6.1 — Refuse: real-time biometric emotion recognition in the workplace

**Legal basis.** EU AI Act Article 5(1)(f): "the placing on the
market, putting into service for this specific purpose, or use
of AI systems to infer emotions of a natural person in the
areas of workplace and education institutions, except where the
use of the AI system is intended to be put in place or into the
market for medical or safety reasons."

In force from **2 February 2025**. Penalties up to €35 million
or 7% of global annual turnover (Art. 99(3)).

Recital 44 grounds the prohibition in "the lack of scientific
basis for the functioning of [emotion-recognition] systems and
key shortcomings such as limited reliability, lack of
specificity, and limited generalisability."

**kontor's posture.** No first-party integration. No vendor-
adapter scaffolding. Any consumer attempting to wire emotion
inference into the substrate gets a project-policy refusal at
the auth-layer recommendation level; the substrate's
`:consent/legal-basis :ai-act-incompatible` keyword exists to
let the refusal be expressed and audited.

### 6.2 — Refuse: biometric categorisation by sensitive
characteristics

**Legal basis.** EU AI Act Article 5(1)(g): "the placing on the
market, putting into service for this specific purpose, or use
of biometric categorisation systems that categorise individually
natural persons based on their biometric data to deduce or infer
their race, political opinions, trade union membership,
religious or philosophical beliefs, sex life or sexual
orientation."

In force from **2 February 2025**. Penalties as Art. 5.

**kontor's posture.** Same as 6.1. Refusal published.

### 6.3 — Refuse: continuous screen recording / keystroke
logging without DPIA + works-council agreement

**Legal basis.** GDPR Art. 35 (DPIA) + BetrVG §87(1)(6)
(works-council co-determination on monitoring devices) +
BAG case-law (e.g., LAG Mecklenburg 24 May 2023 — 5 Sa 234/22).

**kontor's posture.** The substrate stores `:hr-activity-content`
data only when `:consent/supporting-doc` references a
`:dpia-art-35` audit-doc AND, for DE workforces, `:consent/works-
agreement-ref` references a `:betriebsvereinbarung` audit-doc.
The substrate doesn't enforce this; the *project documentation*
in ADR-094 + `doc/value.md` declares it as the consumer's
obligation, and the `:approval-policy/rule :requires-dpia-
supporting-doc` (new in §5) lets the consumer wire substrate-
level enforcement.

### 6.4 — Refuse: bundled "productivity score" derived metrics
in first-party companions

**Legal basis.** GDPR Art. 22 + Annex III 4(b) (AI for
performance evaluation under AI Act high-risk regime).

**kontor's posture.** kontor's first-party companions
(`kontor-hr`, future `kontor-it`, future `kontor-employee-
monitoring`) do not ship productivity-score derivation. A
consumer or third-party companion may compute scores; the
project does not endorse and will not bundle.

### 6.5 — Refuse: pre-canonicalizing abuse-facilitating category
values

**Legal basis.** Project culture / endorsement vs. substrate
neutrality.

**kontor's posture.** The canonical `:audit-doc/category`
vocabulary (§4.1) will not list keywords like
`:hr-emotion-score`, `:hr-engagement-index`,
`:hr-burnout-prediction`, etc., even though the open-set design
cannot prevent a consumer from using them as extensions. The
canonical list reflects *project endorsement*, not substrate
capability.

### 6.6 — Refuse: covert monitoring scaffolding

**Legal basis.** BAG case-law: covert monitoring requires
"exceptional circumstances" — concrete suspicion of serious
misconduct, no less-intrusive alternative, documented in writing
ex ante (BAG 27 Jul 2017 — 2 AZR 681/16). ICO 2023 guidance
identical. CA Law 25 prohibits covert in QC.

**kontor's posture.** No first-party "incognito mode" /
"unannounced capture" / "stealth telemetry" feature. The
substrate stores what the consumer transacts; the project's
first-party companions don't help a consumer be covert.

---

## §7 — What kontor should EXPLICITLY NOT block (substrate neutrality)

The substrate is a **dumb log** with bitemporal facets. It does
not — and should not — implement consumer-facing access control.
ADR-051 has this right: "the kernel TAGS; the consumer's auth
layer ENFORCES — there is no kernel ACL." That stays.

What §7 says explicitly that §6 doesn't:

- The substrate does NOT refuse to transact an audit-doc with
  `:audit-doc/category :hr-activity-content` even if no
  `:consent` record exists. The substrate transacts what the
  consumer sends.
- The substrate does NOT refuse to write an extension category
  keyword that violates the project's refusal list (§6).
  A consumer that writes `:audit-doc/category :hr-emotion-score`
  succeeds at the kernel level.
- The substrate does NOT validate that `:consent/legal-basis`
  matches a published vocabulary; any keyword is accepted.
- The substrate does NOT enforce DPIA-presence-before-write or
  works-agreement-presence-before-write — these are *consumer-
  policy* concerns enforced via `:approval-policy/rule`
  hooks (ADR-038) the *consumer* installs.

This isn't fence-sitting; it's the established design (ADR-051
+ ADR-052 + ADR-050 + ADR-049). The substrate's job is to be the
*forensically-correct receptacle*: every write is recorded with
who, when, under what stated basis. If the basis was wrong, the
record itself is the evidence. The substrate is *more* useful
for accountability when it doesn't gate writes — a gated
substrate hides the abuse attempt; an ungated substrate logs it.

Refusal lives at three layers above the kernel:

1. **Consumer auth layer** — the application (e.g. `kontor-hr`
   consumer's HTTP layer) checks `:audit-doc/category` against
   the caller's role + `:consent` record + DPIA presence.
2. **Approval-policy middleware** — ADR-038's
   `:approval-policy/rule` set extends (per §5) with
   `:requires-dpia-supporting-doc` /
   `:requires-works-agreement-ref` to gate state transitions on
   the consumer's own audit-doc / consent / works-agreement
   chain.
3. **Project endorsement / documentation** — the canonical
   `:audit-doc/category` list (§4.1) and the §6 refusal list say
   what the project endorses, not what the substrate enforces.

---

## §8 — Concrete companion sketch: `kontor-people-record`

A companion that consumes the substrate's HR primitives + the
new categories + the `:consent/*` schema to provide a
forensically-correct *track-record* application.

**Scope (in):**

- Career history: positions held + start/end + employer entity
- Training: courses, certifications, completion dates, expiry
- Skills: declared + endorsed + assessed
- Performance reviews: each review as an `:audit-doc/type
  :performance-review`, `:audit-doc/category
  :hr-track-record` (or `:hr-performance-review` extension)
- Promotion history: events with effective-date + comp-change ref
- Goals + OKRs: linked to evaluations
- 360 reviews: peer + report + skip-level inputs as separate
  audit-docs, aggregated read-only
- Internal mobility: secondment + transfer records linking
  multiple `:employment` rows
- DSAR fulfillment: walks the substrate's category-scoped
  records + filters by consent state at the requested
  valid-time

**Scope (out):**

- No activity monitoring (separate companion, §3.3-3.4).
- No emotion / engagement / productivity scoring.
- No automated promotion / termination recommendations
  (Art. 22).
- No screen / keystroke / webcam capture (separate
  recommendation: not at all without DPIA + BV + the
  refusal-aware substrate).

**Schema sketch (~25 attrs in `kontor-people-record`):**

```
:position-held/* — :person, :entity, :title, :start-date, :end-date, :manager
:training/* — :person, :course, :provider, :completed-at, :expires-at, :certificate-ref
:skill/* — :person, :name, :level, :declared-at, :endorser
:performance-review/* — :person, :reviewer, :review-period, :outcome, :supporting-doc
:promotion/* — :person, :from-position, :to-position, :effective-date, :comp-change
:goal/* — :person, :statement, :period, :status, :linked-review
:internal-transfer/* — :person, :from-employment, :to-employment, :reason
```

Every `:audit-doc` produced by `kontor-people-record` carries
`:audit-doc/category` from the §4.1 canonical list. Every write
through the `kontor.consent/active-at?` helper checks the
worker's active consent at the write time; consumer auth-layer
refuses the write if no consent + no other lawful basis recorded.

**Composition.** Lives downstream of `kontor-hr` (depends on
`:person` + `:employment`). Sibling to (future) `kontor-employee-
monitoring`. The two are intentionally separate companions so
the maintainer can ship `kontor-people-record` without ever
shipping `kontor-employee-monitoring`.

---

## §9 — EU AI Act timing — what 2026/2027 milestones impact
kontor's posture

| Date | Milestone | kontor impact |
|---|---|---|
| 2 Feb 2025 | Art. 5 prohibitions in force (incl. workplace emotion recognition, biometric categorisation by sensitive characteristics) | **DONE.** §6 refusal posture should be public by ADR-094 land. |
| 2 Aug 2025 | General-purpose AI rules + governance + penalties | No direct kontor impact (kontor doesn't ship a GPAI model). |
| 2 Aug 2026 | Most other rules apply (high-risk for Annex III) | Indirect: if a consumer wires AI into performance evaluation per Annex III 4(b), the consumer (deployer) takes on Art. 26 obligations. ADR-094 should flag this with example "consumer DOs and DONTs." |
| 7 May 2026 | Political agreement: high-risk-employment rules apply from 2 Dec 2027 (per Search results) | Confirms the Dec 2027 timing. |
| **2 Dec 2027** | **High-risk employment AI obligations apply** (Annex III 4 — recruitment, performance evaluation, promotion/termination, task allocation, monitoring) | **ADR-094 commits to ADR-095 schema for `:ai-deployer-log/*` before this date.** Article 26 requires deployer to (a) inform workers' representatives + workers; (b) retain logs ≥ 6 months; (c) ensure human oversight; (d) monitor + report risks. The substrate needs the slots. |
| 2026-08-02 onward | Updated AI Act guidelines from EC published quarterly | Track via a research note 110+ on a cadence (probably semi-annual). |

**ADR-094 timing.** The §6 refusal list should be public
*before* the maintainer ships any consumer that touches §3.3-3.4
categories. The §4 substrate additions can land alongside the
first consumer that needs them — possibly `kontor-people-record`
for the §3.1-3.2 categories (which are lower-risk; contractual
basis; no AI Act exposure), with the activity-monitoring
categories landing only when (and if) a consumer concretely
asks for them.

---

## §10 — Open questions for maintainer decisions

1. **Promote `:consent/*` to a `kontor-consent` cross-cutting
   companion?** Marketing consent + health-record consent + DSAR
   consent will all want this shape. Pro: avoid duplicate
   schema. Con: extra module to maintain; YAGNI until the second
   consumer needs it.

2. **`:works-agreement` as its own entity, or just an
   `:audit-doc/type :betriebsvereinbarung`?** The latter is
   cheaper (no schema change); the former cleaner (works
   agreements have scope, validity period, signatories, renewal
   cadence). **Recommendation: start with `:audit-doc/type` +
   audit-doc attrs; promote to its own entity in a future ADR if
   composability demands.**

3. **Should the canonical `:audit-doc/category` vocabulary be
   *closed* (enum-shaped) for the project's first-party
   companions, even though the substrate is open-set?**
   I.e. should `kontor-hr`'s own writes refuse to use a non-
   canonical keyword, even though external consumers can use
   anything? Pro: predictable auth-grid for kontor's own
   ecosystem. Con: stifles the open-set advantage. **My take:
   ship a `kontor.audit-doc/assert-canonical-category!`
   middleware that first-party companions opt INTO, leaving
   third-party consumers free.**

4. **Where do biometric templates (fingerprint hashes for
   time-clock; facial-recognition embeddings for door access)
   live?** Inside `:audit-doc/content-hash` (cheap, already
   exists)? As a separate `:biometric-template/*` entity (more
   structured, supports per-template retention via Illinois BIPA
   max-3-years rule)? **My take: separate entity; defer to
   when a consumer actually asks (probably never for the kontor
   first-party scope).**

5. **DSAR walker registration for `:consent/*`** — should
   `kontor.dsar/register-collector!` automatically traverse
   consent records when collecting for a person? Probably yes;
   the consent record IS the worker's "what bases were used to
   process my data" answer. ADR-094 commits to registering
   `:consent/subject` with the DSAR walker.

6. **AI Act `:ai-deployer-log/*` schema timing — schema-
   sketched in ADR-094 vs. fully deferred to ADR-095?** Pro for
   sketching: forward-compatibility, signals project's
   awareness. Con: speculative. **My take: schema-sketch in
   ADR-094 with status `:proposed`, full landing in ADR-095
   when the first AI-deployer consumer concretely needs it.**

7. **Right-to-be-heard enforcement (DE BAG case-law) — should
   the substrate ship a `kontor.hr/file-performance-finding!`
   helper that structurally requires a child `:audit-doc/type
   :worker-response`?** Probably no — too DE-specific for the
   kernel + composes via existing `:approval-policy/rule`
   mechanism in `kontor-l10n-de`. ADR-094 documents the pattern.

8. **Refusal posture for non-EU consumers** — does the project
   apply the §6 refusal list globally, or only to consumers
   whose data subjects are in the EU? **My take: global. The
   refusals are project-policy, not jurisdiction-conditional.
   A US-only consumer using kontor still doesn't get a default
   integration with an emotion-recognition vendor from us.**

9. **Compensation-history privilege escalation** — should
   `:hr-compensation-negotiation` for executives default to
   `:work-product` or `:attorney-client` given that material-
   executive-comp memos are frequently counsel-drafted?
   Probably leave at `:pii-sensitive` and let consumer
   escalate; default `:work-product` would over-restrict.

10. **The `:hr-personnel` umbrella vs. the new fine-grained
    categories** — should the substrate deprecate
    `:hr-personnel` in favor of the eight new keywords? Pro:
    cleaner taxonomy. Con: backward-incompatibility (existing
    `kontor-hr` writes use `:hr-personnel`). **My take: keep
    `:hr-personnel` as an umbrella; document in §4.1 doc-string
    that fine-grained categories are preferred for new
    writes.**

---

## §11 — Sources

All URLs accessed 2026-05-18.

### GDPR / EU primary law

- **GDPR Art. 5** (principles): https://gdpr-info.eu/art-5-gdpr/
- **GDPR Art. 6** (lawful basis): https://gdpr-info.eu/art-6-gdpr/
  — six bases (a) consent, (b) contract, (c) legal obligation,
  (d) vital interests, (e) public task, (f) legitimate interests.
- **GDPR Art. 7** (conditions for consent + withdrawal right):
  https://gdpr-info.eu/art-7-gdpr/
- **GDPR Art. 9** (special categories): https://gdpr-info.eu/art-9-gdpr/
  — prohibition on race/ethnic/political/religious/trade-union/
  genetic/biometric/health/sex-life/sexual-orientation data;
  Art. 9(2)(b) employment-law exception; Art. 9(2)(h)
  occupational-medicine exception.
- **GDPR Art. 10** (criminal-conviction data): only under
  "control of official authority or when authorised by Union or
  Member State law."
- **GDPR Art. 22** (automated individual decision-making):
  https://gdpr-info.eu/art-22-gdpr/ — CJEU 2023 case clarified
  "solely" includes rubber-stamp human review.
- **GDPR Art. 30** (records of processing): organize by
  subject-matter category of personal data.
- **GDPR Art. 35** (DPIA): https://gdpr-info.eu/art-35-gdpr/ —
  required for systematic monitoring at scale.
- **GDPR Art. 88** (employment context): https://gdpr-info.eu/art-88-gdpr/
  — Member States may legislate more specific rules.
- **ePrivacy Directive 2002/58/EC Art. 5**: confidentiality of
  communications. https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32002L0058
- **Directive 2003/88/EC** (working-time): https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=celex:32003L0088
- **Directive 2019/1937** (whistleblower protection):
  https://eur-lex.europa.eu/eli/dir/2019/1937/oj — Art. 16
  whistleblower identity protection; Art. 18 retention.

### EU AI Act

- **Regulation (EU) 2024/1689** (AI Act): full text via
  https://artificialintelligenceact.eu/
- **Art. 5(1)(f)** — prohibition on workplace emotion
  recognition; in force 2 Feb 2025.
- **Art. 5(1)(g)** — prohibition on biometric categorisation by
  sensitive characteristics.
- **Art. 26** — deployer obligations for high-risk AI; worker-
  notification + 6-month log retention + human oversight; from
  2 Dec 2027 for employment per agreed timing.
- **Annex III, point 4** — high-risk AI in employment:
  recruitment, performance evaluation, promotion/termination,
  task allocation, monitoring.
- **Recital 44** — basis for emotion-recognition prohibition.

### CJEU case law

- **C-55/18 — Federación de Servicios de Comisiones Obreras
  (CCOO) v Deutsche Bank** (14 May 2019): Member States must
  require employers to set up an objective, reliable, accessible
  system for measuring daily working time. Basis: Directive
  2003/88/EC + Charter Art. 31(2).

### Germany — primary

- **BDSG § 26** (employment data processing): text via
  https://www.gesetze-im-internet.de/englisch_bdsg/englisch_bdsg.html
  §26(1) necessity-for-employment-relationship;
  §26(2) consent + power-imbalance factor; written form unless
  exceptional circumstances;
  §26(3) special-category processing under labour/social/
  protection law where no overriding subject interest;
  §26(4) collective-agreement basis;
  §26(8) employee definition (broad — includes applicants,
  trainees, civil servants, terminated).
- **BetrVG § 87(1)(6)**: works council co-determination on
  introduction + use of technical devices objectively likely to
  monitor employee behavior or performance. Absolute right;
  no monitoring system without works-council agreement.
- **HGB § 257**: commercial-records retention (6 / 10 years
  depending on document type).
- **AO § 147**: tax-record retention (8-10 years).
- **GefStoffV § 10a**: hazardous-substance exposure-record
  retention.
- **ArbMedVV § 7**: occupational-medicine record retention.
- **ArbSchG § 3(2) Nr 1**: occupational-safety duty (basis the
  BAG used for mandatory working-time recording).
- **§ 30 BZRG**: police-record (Führungszeugnis) handling.
- **§ 88 TKG / § 206 StGB**: telecommunications-secrecy criminal
  protection — triggered when employer permits private use of
  corporate email.
- **§ 28f SGB IV**: employer obligation to keep payroll/social-
  security records (Entgeltunterlagen); audit-cycle-tied retention
  (~5 years).
- **HinSchG** (2023 transposition of EU Whistleblower
  Directive): minimum retention 3 years.

### Germany — case law

- **BAG 1 ABR 22/21** (13 Sep 2022): employers obligated to
  introduce a working-time-recording system; basis is § 3(2) Nr 1
  ArbSchG via Union-law-conforming interpretation. Works council
  has co-determination on the implementation arrangements.
  Norton Rose summary:
  https://www.nortonrosefulbright.com/en/knowledge/publications/c5306eab/working-time-compliance-bag-ruling-on-systematic-recording-of-working-time
- **BAG 2 AZR 296/22** (29 Jun 2023): openly conducted video
  recordings are admissible as evidence in disciplinary
  proceedings even outside the GDPR retention floor, provided
  signage + recognizability requirements met.
- **BAG 2 AZR 546/12** (2013): covert locker (Spind) search
  inadmissible; serious privacy invasion requiring compelling
  justification; less-intrusive alternative test.
- **BAG 2 AZR 681/16** (27 Jul 2017): covert monitoring requires
  exceptional circumstances + concrete suspicion + no less-
  intrusive alternative + ex-ante written documentation.
- **BAG 2 AZR 133/18** (23 Aug 2018): video-surveillance
  retention limits; delete when purpose fulfilled.
- **BAG 9 AZR 791/15** (12 Jul 2016): right-to-be-heard before
  negative findings filed in personnel file.
- **LAG Mecklenburg-Vorpommern 5 Sa 234/22** (24 May 2023):
  continuous screen monitoring lawful only with disclosure at
  hiring + Betriebsvereinbarung + DSFA documented.

### EU advisory + EDPB

- **EDPB / Article 29 WP Opinion 2/2017 (WP249)** — opinion on
  data processing at work.
- **WP248 / EDPB endorsement**: DPIA mandatory for systematic
  employee monitoring at scale.
- **EDPB country lists of mandatory-DPIA processing**: each
  national supervisory authority publishes a list including
  employee monitoring.

### United States — federal

- **ECPA / Wiretap Act, 18 USC §§ 2510 et seq.**: business-
  purpose exception + consent exception for employer monitoring
  of company-owned systems; storage vs. transmission distinction.
- **Stored Communications Act, 18 USC §§ 2701 et seq.**: stored
  communications.
- **FLSA recordkeeping, 29 CFR § 516.5 / 516.6**: payroll 3 years,
  time-card records 2 years.
- **Title VII recordkeeping, 29 CFR § 1602.14**: personnel
  records 1 year from action.
- **ADEA recordkeeping, 29 CFR § 1627.3**.
- **OSHA medical records, 29 CFR § 1910.1020**: duration of
  employment + 30 years.
- **I-9 retention, 8 CFR § 274a.2(b)(2)**: 3 years post-hire or
  1 year post-termination, whichever later.
- **SOX § 802 (18 USC § 1519)**: 7-year retention for
  accounting/audit-related records.
- **FCRA, 15 USC § 1681c**: background-check retention; 7-year
  reporting limit for criminal records.

### United States — state

- **NY Civil Rights Law § 52-c** (eff. 7 May 2022): electronic-
  monitoring notice + employee acknowledgment + conspicuous
  posting; AG-only enforcement; $500/$1,000/$3,000 graduated
  civil penalties.
- **CT Gen. Stat. § 31-48d** (1998): prior written notice;
  conspicuous-place posting; $3,000 civil penalty per offense.
- **19 Del. C. § 705**: daily electronic notice OR one-time
  written notice + employee acknowledgment.
- **California Privacy Rights Act / Cal. Civ. Code § 1798.100
  et seq.**: employee-data exemption expired 1 Jan 2023;
  employees + applicants + contractors now have full CCPA/CPRA
  rights including DSAR + right-to-know + right-to-delete +
  data-minimization.
- **California Fair Chance Act / Cal. Gov. Code § 12952**: ban-
  the-box for employers ≥5; individualized assessment.
- **California pay-data reporting / Cal. Lab. Code § 432.3 (SB
  1162)**: pay-equity retention.
- **Illinois BIPA, 740 ILCS 14**: written notice + written
  consent + retention schedule; $1,000 negligent / $5,000
  intentional liquidated damages; 2024 amendment treats repeat
  collection of same biometric from same person as single
  violation.

### United Kingdom

- **UK GDPR + Data Protection Act 2018**: post-Brexit
  continuation of GDPR principles.
- **ICO Employment Practices: Monitoring at Work guidance**
  (Oct 2023): https://ico.org.uk/media2/migrated/4026921/monitoring-at-work-impact-assessment-202310.pdf
  — least-intrusive-means test; DPIA recommended even when not
  mandatory; home-worker higher expectation of privacy.
- **Limitation Act 1980 s.5**: 6-year contract-breach limitation
  (drives the 6-year retention floor for employment records).
- **Working Time Regulations 1998 reg. 9**: working-time records
  2-year retention.
- **Rehabilitation of Offenders Act 1974**: spent-conviction
  framework.
- **Equality Act 2010 (Gender Pay Gap Information) Regs 2017**:
  pay-gap reporting + retention.

### Canada

- **PIPEDA / SC 2000, c.5**: privacy framework for federally-
  regulated private sector; Schedule 1 Principle 4.5 (necessity-
  limited retention).
- **Quebec Law 25 / Act respecting the protection of personal
  information in the private sector**: applies to all private-
  sector employers in QC; notification + consent + minimization
  obligations.
- **Canada Labour Code s. 252**: payroll records retention.
- **Canada Human Rights Act s. 3(1)**: prohibits discrimination
  based on record-suspended convictions.
- **OPC "Privacy in the Workplace" guidance**:
  https://www.priv.gc.ca/en/privacy-topics/employers-and-employees/02_05_d_17/
- **OPC + provincial joint resolution on employee privacy in
  the modern workplace** (Oct 2023): reasonable purpose +
  proportionality + minimal intrusion test.

### Project anchor citations

- `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj:661-771`
  (`:legal-hold/*`).
- `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj:773-908`
  (`:retention-policy/*`).
- `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj:3670-3768`
  (`:audit-doc/*` incl. privilege, category, language).
- `/home/christian-weilbach/Development/kontor/doc/decisions.md:4798-5252`
  (ADR-049 legal-hold + ADR-050 retention + ADR-051 privilege +
  ADR-052 DSAR).
- `/home/christian-weilbach/Development/kontor/doc/decisions.md` ADR-075
  (audit-doc-category + retention-policy-category).
- `/home/christian-weilbach/Development/kontor/doc/decisions.md` ADR-078
  (audit-doc-language).
- `/home/christian-weilbach/Development/kontor/doc/research/76-review-after-adr-071-072-073.md`
  (privacy posture pre-Stage R).
- `/home/christian-weilbach/Development/kontor/doc/research/81-hr-data-model-gold-standards.md`
  (HR data-model gold-standards; informs `:person` + `:employment`).
- `/home/christian-weilbach/Development/kontor/doc/research/86-stage-r-final-review-after.md`
  (note 86 P0-86-2 category-vocabulary fragmentation; P1-86-5
  DSAR-registry registration; both directly compose with this
  note's recommendations).

---

End of note 93.

---
date: 2026-05-24
title: 154 — AT investment-income — KESt-Endbesteuerung on dividends + bank interest, plus the §10 KStG corporate side, on top of the shipped AT CGT provider
status: research-before for note-104 Phase C2 — AT investment-income companion of the dividend / interest verbs (book.clj) + the existing `at-cgt-provider` (note 134)
audience: maintainer + the AT-investment-income implementation agent
---

# 154 — AT investment-income substrate fit

Phase C2 of note 104 adds **investment-income** providers — the
companion of the just-shipped `kontor.incorporation` + dividend
verbs (`book.clj/declare-dividend!` + `distribute-dividend!`) and
the 11 CGT providers from Phase B. The kernel's
`distribute-dividend!` docstring already names the in-scope
adapters:

> The shareholder records the receipt separately on their books via
> `receive!` (Dr Bank, Cr Income:Dividends) — the investment-income
> regime in `kontor-l10n-<cc>` then taxes it (DE Abgeltungsteuer, US
> qualified-dividend, FR PFU, JP 20.315 %, …).

This note assesses **Austria**. Unlike AU (note 153), where the
imputation system inserts a gross-up + refundable credit, the AT
mechanics on the **individual side** are simpler — KESt
(Kapitalertragsteuer) is withheld at source by the Austrian custodian
bank at a flat rate, and **Endbesteuerung** (§97 EStG) discharges
the entire income tax liability without the dividend ever appearing
on the annual return (unless Regelbesteuerung is elected). The
hard parts are on the **corporate side** (§10 KStG —
Beteiligungsertragsbefreiung, with three tiers and the §10 Abs 3
opt-in / opt-out inversion already documented for capital gains in
note 134 §1.7), and at the **foreign-source** edge (DBA-Quellensteuer
credit, capped at treaty rates, against KESt or Regelbesteuerung
liability).

The **existing AT CGT provider** (`modules/l10n-at/src/kontor/l10n_at/
cgt_provider.clj`, note 134) already implements three sibling
providers — `at-kest-cgt-provider`, `at-immoest-provider`,
`at-corporate-cgt-provider` — for capital-GAIN treatments. This note
extends the same architectural pattern to **investment INCOME**
(dividends + interest + fund distributions), with the
**at-kest-cgt-provider** naturally generalising to
**`at-kest-investment-income-provider`** (or two siblings if the
implementer prefers; §5 discusses).

Bottom line: **the schema gains nothing kernel-side**. The
proposed cross-jurisdiction `kontor-investment-income` companion
substrate (note 153 §3 Option B / Gap 1) hosts the
`:investment-income-event` per-event record; AT adds ~5 companion
attrs in a `:at-iie/*` namespace. The provider is **two records**
(individual + corporate) plus an extension to the existing
`at-immoest-provider` for the rental-income compartment that
catches the §30 Abs 7 EStG carryforward (note 134 §6.2 — already
shipped in skeletal form). Zero kernel additions. Zero
schedule-algebra changes.

---

## §1. AT investment-income regime — the moving parts

### 1.1 KESt-Endbesteuerung — the individual side at a glance

The 2011 Budgetbegleitgesetz restructured AT's capital-income
taxation (effective 2012-04-01); a 2016 reform raised the dividend
rate from 25 % to 27.5 %; the 2022 Ökosoziales Steuerreformgesetz
folded crypto-asset income in under §27b EStG; the 2024
Steuerreportingverordnung modernised reporting without changing
substance. The system as of May 2026 has TWO rates and ONE
mechanic:

**Two rates** (§27a Abs 1 EStG):

- **27.5 %** flat — dividends (domestic + foreign), distributions
  from investment funds, capital gains on shares + bonds + funds +
  derivatives, crypto income under §27b.
- **25 %** flat — interest on bank deposits (Sparbuch /
  Girokonto) and similar bank claims.

**One mechanic** — **Endbesteuerungswirkung** (§97 EStG): the KESt
withheld by the Austrian custodian bank (the Abzugsverpflichteter)
**discharges the entire income tax liability** on the covered
income. The investor's annual return (Einkommensteuererklärung)
does NOT include these incomes — unless the investor elects
Regelbesteuerung (see §1.4) or the bank failed to withhold
(typically: offshore custodian, where the investor must file
**Anlage E1kv** to self-declare).

This is materially different from AU (note 153 §1.1 imputation
gross-up) and from DE (Abgeltungsteuer + Sparer-Pauschbetrag €1000
allowance). Austria's regime is **the simplest on the individual
side** of the four EU countries this project will touch — no
gross-up, no allowance, no progressive rate (unless Regelbesteuerung
is elected).

### 1.2 The "Investment-income disposal vs investment-income receipt" split

The AT CGT provider (note 134) handles the **disposal** of
financial assets — KESt on the realised capital gain when shares /
bonds / funds / derivatives are sold (§27 Abs 3 EStG, "Einkünfte
aus realisierten Wertsteigerungen"). The investment-income
provider this note proposes handles the **periodic receipt** —
dividends (§27 Abs 2 Z 1 EStG, "Einkünfte aus der Überlassung von
Kapital") + bank interest (§27 Abs 2 Z 2 EStG) + investment fund
distributions (§186 / §188 InvFG 2011).

Both regimes share the **27.5 % / 25 % flat rates** and the
**Endbesteuerung mechanic**; both share the
**Verlustverrechnungstopf** (loss bucket; §1.6 below). The
substrate **must enforce this composition** — a year's realised
capital LOSSES from disposals offset the SAME year's dividends +
interest (within the 27.5 % bucket; the 25 % Sparbuch bucket is
walled off; §27 Abs 8 EStG).

This is **the first cross-realisation-event-type composition in
the kontor substrate** — losses from `:disposal` records offset
income from `:investment-income-event` records, within a shared
loss bucket, within the same year. The implementation path: the
`at-kest-investment-income-provider` reads losses from the
existing `at-kest-cgt-provider`'s outputs via the `:inputs` shape
(or via a shared sibling-provider read pattern); the substrate
already supports the cross-provider input wiring.

### 1.3 The Altvermögen / Neuvermögen split — does it apply to investment income?

Note 134 §1.2 documented the Altvermögen / Neuvermögen split for
**capital gains** on disposals (Neuvermögen = acquired after
specific 2010-2012 cutoffs; Altvermögen = before). For
**investment income** (the recurring dividend / interest receipt),
the answer is **simpler**: dividends + interest are ALWAYS
taxable, irrespective of when the underlying asset was acquired.
There is no "Alt"-dividend grandfather. KESt applies whenever the
distribution is paid.

The Altvermögen split therefore affects ONLY the disposal-side
(`at-kest-cgt-provider`); the investment-income provider does NOT
read the acquisition date for tax-applicability gating. It MAY
read it for audit-trail purposes; substrate concern only.

### 1.4 Regelbesteuerungsoption (§27a Abs 5 EStG)

The investor may **elect out** of the 27.5 % flat into their
marginal rate (the AT progressive PIT schedule, §33 EStG) for ALL
KESt-Vermögen income — the election is **all-or-nothing per tax
year** for the bucket (cannot pick which dividends; either all KESt
income enters the regular assessment, or none does). The 2026
brackets (existing `at-income-tax-provider`,
`period_tax_provider.clj`):

| Rate | Upper |
|---|---|
| 0 % | €12,816 |
| 20 % | €20,818 |
| 30 % | €34,513 |
| 40 % | €66,612 |
| 48 % | €99,266 |
| 50 % | €1,000,000 |
| 55 % | above |

The election is **rational** only if the marginal rate < 27.5 % —
i.e. taxable income (after deductions, including the
KESt-Vermögen income) is in the 0 / 20 % bracket. The KESt already
withheld is **credited** against the assessed PIT liability; any
excess is **refunded** by the Finanzamt (§46 EStG).

A second, narrower option — **Verlustausgleichsoption** (§27 Abs 8
Z 2 EStG) — lets the investor file a year-end Verlust-Ausgleich
across multiple custodian banks; the bank-side
Verlustverrechnungstopf only offsets losses against gains held
**at the same custodian**, so investors holding accounts at
multiple banks can cross-net via the annual return. This is more
common than full Regelbesteuerung.

For the substrate: the elective-regime keyword
`:at-regelbesteuerungsoption` (already in the existing CGT
provider's dispatch space, note 134 §3) signals the option;
provider routes to the marginal-rate branch. New keyword
`:at-verlustausgleichsoption` for the cross-bank netting option.
Both can ride `:tax-unit :at-elections #{...}` on the
period-tax call.

### 1.5 The Sole-Earner (Alleinverdiener) deduction interaction

A wrinkle that surfaces in the 2026 BMF guidance: when a spouse is
the Alleinverdiener-Berechtigte (sole earner with dependant
spouse), the KESt refund mechanism is **offset against** the
Alleinverdienerabsetzbetrag at the AT-companion-side. The 2026
single-earner deduction is €612 minimum (Raisin source); for
applicants in this category, only the KESt amount EXCEEDING the
Alleinverdienerabsetzbetrag is refunded.

This is **PIT-side mechanics** (the AT PIT provider should know
about Absetzbeträge generally; ride via `:inputs
:at-pit-credits-claimed`). The investment-income provider does NOT
implement it; flag in the ADR for the AT PIT provider's expansion.

### 1.6 Loss-offset within KESt-Vermögen (§27 Abs 8 EStG)

Three Verlustverrechnungstopf rules from note 134 §1.6, reiterated
for the investment-income side:

- **Within 27.5 % bucket**: capital LOSSES on shares / funds /
  derivatives offset capital GAINS on shares / funds / derivatives
  AND offset dividends + interest from bonds (the latter being
  treated as Wertpapierzinsen, in the 27.5 % bucket, NOT the
  25 % Sparbuch bucket). Same calendar year only.
- **25 % Sparbuch bucket**: bank-deposit interest is in a
  separate sub-bucket; capital losses do NOT offset bank interest
  (§27a Abs 1 Z 1 vs Z 2 distinction).
- **No carryforward** for private investors. Losses unused in the
  year **expire** (Jan 1 reset).

These rules apply **across realisation event types** within the
same year — a CGT loss from a `:disposal` offsets investment
income from an `:investment-income-event`, both within the 27.5 %
bucket. **The provider must orchestrate the netting**.

Implementation pattern: the investment-income provider does NOT
duplicate the loss-bucket logic; it READS the
`at-kest-cgt-provider`'s output (specifically, the net negative
balance in the 27.5 % bucket) via the `:inputs` shape, then
applies the residue against its own dividends + interest gross.
The composition is one-way (CGT losses → reduce II income); II
losses don't exist as a concept (dividends are always positive
amounts, interest is positive, fund distributions are positive
or rarely negative-deemed-distribution which goes back to the
CGT provider).

### 1.7 §10 KStG — corporate participation exemption (dividend side)

The corporate side is where AT investment-income gets interesting.
**§10 KStG** has THREE tiers (already documented in note 134 §1.7
for the capital-GAINS treatment); each tier has a **dividend
treatment**:

1. **Domestic participation income** (§10 Abs 1 Z 1 KStG) —
   dividends from AT Kapitalgesellschaften are **tax-free at the
   receiving corp**, no holding-period or minimum-stake test. The
   underlying KöSt has been paid; exempting the recipient avoids
   double taxation. (NB: this is the dividend-only exemption;
   capital gains on the domestic participation remain
   ordinary CIT income.)
2. **International portfolio dividends** (§10 Abs 1 Z 5–6 KStG) —
   dividends from EU/EEA-resident or treaty-state-resident foreign
   corps; **exempt without holding-period or stake test**, subject
   to anti-abuse switch-over rules in §10 Abs 4 (low-tax
   jurisdictions).
3. **Internationale Schachtelbeteiligung** (§10 Abs 2 KStG) — ≥
   10 % stake in a foreign corp held ≥ 1 year; BOTH dividends AND
   capital gains AND losses are **tax-neutral by default** under
   §10 Abs 3 KStG. The Option zur Steuerwirksamkeit
   (`:elective-regime :at-§10-tax-effective-option`) inverts to
   taxable.

The **2026 update** from the WebSearch: the low-taxation threshold
moved from 12.5 % to **15 %** in 2026 (Pillar Two alignment under
the Minimum Taxation Act). The substrate must read this threshold
to determine §10 Abs 4 switch-over eligibility.

**Substrate fit**: the investment-income provider on the corporate
side emits a `:cit-base-deductions` line for §10-qualifying
dividends, **removing** the dividend from the CIT taxable base. The
GL posts the dividend as ordinary income at receipt; the provider
deducts at year-end. Same composition pattern as the existing
`at-corporate-cgt-provider` for capital gains.

Three branches in code:

```
(case (:at-iie/§10-classification iie)
  :domestic              :cit-base-deductions    ; §10 Abs 1 Z 1
  :foreign-portfolio     :cit-base-deductions    ; §10 Abs 1 Z 5-6, w/ §10 Abs 4 check
  :schachtelbeteiligung  :cit-base-deductions    ; §10 Abs 2 (default)
  :ordinary              :no-deduction           ; no §10 exemption applies
)
```

### 1.8 §10 Abs 4 switch-over (low-tax exception)

Even tier 2 (international portfolio) and tier 3
(Schachtelbeteiligung) exemptions can be **switched off** under §10
Abs 4 KStG if the foreign corporation is in a **low-tax
jurisdiction** AND has predominantly passive income. From 2026
the low-tax threshold is **15 %** (raised from 12.5 % to align
with Pillar Two GloBE rules).

When §10 Abs 4 fires:
- The dividend becomes **fully taxable** at the AT corporate rate
  (23 % from 2024);
- The foreign WHT becomes a **DBA-Quellensteuer credit** (§1.10
  below) — the dividend is taxed in AT with the foreign tax
  credited against the AT liability.

For the substrate: the consumer attests via `:tax-unit
:at-low-tax-jurisdiction? <bool>` (or, more granularly, supplies
the foreign corp's effective tax rate via `:tax-unit
:at-foreign-corp-etr <bigdec>` and the provider compares to the
15 % threshold parameter).

### 1.9 §10a KStG — Hinzurechnungsbesteuerung (CFC rules)

Implemented in 2019 (ATAD-compliant); deems passive income of
low-taxed foreign subsidiaries (controlled by Austrian residents)
to be included in the Austrian parent's taxable income — even
without distribution. The substrate already has the
`:tax-unit :at-cfc-applies?` flag pattern available; the CFC
mechanism is **out of scope for v1** investment-income — it's a
deemed-income mechanism, not a real-distribution one. Flag in
the gap list as deferred.

### 1.10 DBA-Quellensteuer — foreign WHT credit (Doppelbesteuerungsabkommen)

When an Austrian resident receives a foreign-source dividend (or
interest) that has been withheld at source in the issuer's
jurisdiction:

- The **foreign WHT** paid (subject to the treaty maximum,
  typically 15 %) is **creditable** against the AT KESt /
  Einkommensteuer / KöSt liability on the same income.
- The credit is **capped** at the lesser of the actual foreign tax
  paid (treaty-rate) or the AT tax on the foreign income.
- Foreign WHT **exceeding the treaty rate** is **NOT creditable** —
  it must be reclaimed from the foreign tax authority (typically
  within 2–5 years; application deadlines vary by jurisdiction).
- The credit is **non-refundable** at the AT side — excess credit
  is lost.

A 2024 Austrian Federal Tax Court (BFG) ruling clarified: where
the §10 KStG exemption applies, the crediting limit for foreign
WHT is **zero** (Lang 2024 SWI paper). So if an AT corp receives
a §10 Abs 1 Z 5-6 EU-portfolio dividend, the dividend is
exempt → there is no AT tax to credit against → foreign WHT is
lost (must be reclaimed at source).

For the substrate: the AT investment-income provider for the
individual side issues a `:credit` line (non-refundable) for the
DBA-Quellensteuer, capped at the treaty rate × foreign dividend
amount. The corporate-side provider issues NO credit when the
§10 exemption fires (consistent with the BFG 2024 ruling);
provider documents the loss in `:audit-doc`.

The consumer attests via `:tax-unit :at-treaty-rate-cap <bigdec>`
(typically 0.15M for most treaties) and supplies
`:au-iie/foreign-tax-withheld <bigdec>` per event (kontor uses the
AU prefix already; AT can borrow the same field). Provider does
the cap.

### 1.11 Wertpapierfondssteuer / fund-distribution tax (InvFG 2011)

AT investment funds have **special pass-through taxation** under
§186 / §188 InvFG 2011:

- **Meldefonds** (reporting funds; almost all AT-resident funds +
  many EU UCITS): the fund's tax-relevant figures are reported
  daily to the OeKB (Oesterreichische Kontrollbank), and the
  custodian bank withholds KESt on **actual distributions** +
  **deemed distributions** (the share of fund-realised gains that
  the fund didn't distribute but that are still taxable in the
  investor's hands).
- **Nichtmeldefonds** (non-reporting funds): the custodian must
  apply **pauschale Besteuerung** — deemed annual distribution
  = the GREATER of:
  - 90 % of the year-end fair value − year-start fair value;
  - 10 % of year-end fair value.
  The custodian withholds 27.5 % KESt on this deemed amount on
  December 31 each year, irrespective of cash flows.

This is a substantial complication for funds: the consumer's
holdings can produce **phantom KESt liability** annually without a
corresponding cash distribution. The substrate handles it via the
`:investment-income-event/kind :fund-distribution` record with
`:at-iie/deemed-distribution? true` + `:at-iie/fund-type
:meldefonds | :nichtmeldefonds`.

V1 recommendation: **ship the Meldefonds branch (actual + reported
deemed distributions are standard KESt)**; defer Nichtmeldefonds
to a P2 follow-up. Most retail consumers hold UCITS funds; the
Nichtmeldefonds branch is exotic.

### 1.12 Summary table

| Income type | Holder | Substrate posting | Provider action |
|---|---|---|---|
| AT-domestic dividend (e.g. OMV) | Resident individual | Dr Bank net / Cr Income gross / Dr KESt 27.5 % | KESt is final; Endbesteuerung → 0 net liability; informational E1kv |
| Foreign dividend (e.g. AAPL) | Resident individual | Dr Bank net / Cr Income gross / Dr Foreign WHT | KESt 27.5 % on gross; DBA-credit for foreign WHT (capped at treaty rate); excess lost |
| Bank deposit interest (Sparbuch) | Resident individual | Dr Bank net / Cr Income gross / Dr KESt 25 % | KESt 25 % final; Endbesteuerung; informational |
| AT dividend, low marginal rate | Resident individual (Regelbest. elected) | same | KESt 27.5 % withheld, then assessment at marginal rate; refund if marginal < 27.5 % |
| AT dividend | Resident corp (passive holding) | Dr Bank / Cr Income | §10 Abs 1 Z 1 KStG: dividend exempt; provider emits :cit-base-deductions |
| Foreign EU portfolio dividend | Resident corp | Dr Bank gross / Cr Income gross / Dr Foreign WHT | §10 Abs 1 Z 5-6 KStG: dividend exempt; foreign WHT lost (BFG 2024); provider emits :cit-base-deductions; no credit |
| Foreign dividend, ≥ 10 % held ≥ 1 yr, no Option | Resident corp | same | §10 Abs 2 + Abs 3 KStG: tax-neutral by default; provider emits :cit-base-deductions |
| Foreign dividend, low-tax jurisdiction (§10 Abs 4) | Resident corp | same | Exemption switches off → fully taxable at 23 % CIT; DBA-credit applies; provider emits :cit-base-additions + :credit |
| Meldefonds distribution | Resident individual | Dr Bank net / Cr Income gross | KESt 27.5 % on actual + reported deemed distributions; final |
| Nichtmeldefonds (year-end deemed) | Resident individual | Dr KESt 27.5 % × pauschal | 27.5 % on greater-of-90%-fair-value-delta-or-10%-year-end-fv on Dec 31 (P2 deferred) |

---

## §2. Worked examples

### 2.1 Frau Huber — AT-domestic dividend (KESt-Endbesteuerung)

Frau Huber (AT-resident individual, 48 % marginal bracket) receives
a **€1,500** dividend from her OMV holding on 2026-04-15 (custody at
Erste Bank).

- **KESt withheld by Erste Bank**: €1,500 × 27.5 % = **€412.50**
  (§95 EStG — Bank is the Abzugsverpflichteter).
- **Cash received**: €1,500 − €412.50 = **€1,087.50**.
- **Endbesteuerungswirkung** (§97 EStG): the €412.50 KESt
  discharges the entire income tax liability on the dividend.
- **No annual return entry**: dividend does NOT appear in the
  Einkommensteuererklärung.
- **Effective tax**: 27.5 % flat — Frau Huber's 48 % marginal rate
  is **irrelevant** under Endbesteuerung.

Substrate trace: one `:investment-income-event` —

```clojure
{:investment-income-event/kind            :dividend
 :investment-income-event/cash-amount     1500M    ;; gross (the assessable amount)
 :investment-income-event/source          :at-resident-corp
 :investment-income-event/event-date      #inst "2026-04-15"
 :investment-income-event/holder-kind     :individual
 :investment-income-event/realizing-tx    #db/id<…>
 :at-iie/kest-rate                        0.275M
 :at-iie/kest-prepaid                     412.50M    ;; bank-withheld
 :at-iie/endbesteuert?                    true}
```

The `at-kest-investment-income-provider` reads this in the period,
sums dividends, checks Endbesteuerung (true) → emits a zero-net
component for the (informational) return; reconciles
KESt-prepaid against KESt-due (both €412.50 → reconciled).

### 2.2 Frau Huber — low marginal rate + Regelbesteuerung

Same as 2.1 but Frau Huber is retired, sole income is a €15,000
state pension + the €1,500 OMV dividend (gross). She elects
Regelbesteuerung for 2026.

- **KESt withheld**: €412.50 (bank still withholds at source).
- **Total taxable income (PIT)**: €15,000 pension + €1,500
  dividend = **€16,500**.
- **PIT bracket**: €15,000 + €1,500 falls in the 20 % bracket
  (€12,816 – €20,818). Tax = `(16500 − 12816) × 0.20 = €736.80`
  (simplified, ignoring Absetzbeträge).
- **KESt credit**: €412.50 credited against €736.80 → net PIT
  due = **€324.30**.
- Without Regelbesteuerung: 27.5 % flat on €1,500 = €412.50
  (final) PLUS €15,000 pension at PIT = `(15000 − 12816) × 0.20 =
  €436.80` → total **€849.30**.
- With Regelbesteuerung: €736.80 total → **savings of €112.50**.

Substrate trace: same event as 2.1, plus `:tax-unit :at-elections
#{:at-regelbesteuerungsoption}`. The provider sees the election;
emits a `:pit-base-additions {:dividend 1500M}` (folding the
dividend into the PIT base) + `:pit-credits {:kest-prepaid 412.50M
:refundable? true}` (the prepayment is refundable since this is
the marginal-rate branch). PIT provider sweeps both — the
dividend enters the bracket calc; the prepayment offsets gross
liability with cash-refund if excess.

### 2.3 Frau Huber — Sparbuch interest at 25 %

Frau Huber receives €800 interest on her BAWAG savings account on
2026-12-31. KESt withheld at 25 % = €200; cash received €600.

- **KESt-rate**: 25 % (the Sparbuch / Girokonto sub-bucket).
- **Endbesteuerung**: applies.
- **Loss-offset**: Sparbuch interest is in the **25 % bucket** —
  walled off from KESt-Vermögen losses (§27a Abs 1 Z 1 vs Z 2).
  Even if Frau Huber has €1,000 of share losses in 2026, the
  €800 interest is NOT offset.

Substrate trace:

```clojure
{:investment-income-event/kind            :interest
 :investment-income-event/cash-amount     800M
 :investment-income-event/source          :at-resident-bank
 :investment-income-event/event-date      #inst "2026-12-31"
 :investment-income-event/holder-kind     :individual
 :at-iie/kest-rate                        0.25M
 :at-iie/kest-prepaid                     200M
 :at-iie/kest-bucket                      :sparbuch        ;; the 25% sub-bucket
 :at-iie/endbesteuert?                    true}
```

Provider tags the Sparbuch sub-bucket; loss-offset routine skips
this event.

### 2.4 Foreign dividend with DBA-Quellensteuer credit (US, 15 % treaty)

Frau Huber receives a **$1,000** USD dividend from her Apple
holding on 2026-04-20. The US withholds 15 % at source under the
AT-US treaty = **$150 USD**. At the distribution date USD/EUR =
0.90, so the AUD-equivalent gross is **€900**; foreign WHT €135.
Erste Bank, the AT custodian, withholds AT KESt 27.5 % on the
gross **less** the creditable foreign tax — but the mechanics are
typically that the bank withholds 27.5 % on the gross, and then
applies the foreign-tax credit at the year-end Anlage E1kv on
the annual return (not in real-time).

Per the BMF guidance: the bank withholds AT KESt 27.5 % × €900 =
**€247.50**; year-end, Frau Huber files E1kv to claim the foreign
WHT credit of €135 (capped at the treaty rate). Net AT tax =
€247.50 − €135 = **€112.50**.

Substrate trace:

```clojure
{:investment-income-event/kind                   :dividend
 :investment-income-event/cash-amount            900M       ;; EUR-equivalent gross
 :investment-income-event/commodity              :EUR
 :investment-income-event/source                 :foreign
 :investment-income-event/holder-kind            :individual
 :at-iie/foreign-jurisdiction                    :us
 :at-iie/foreign-tax-withheld                    135M       ;; EUR-equivalent
 :at-iie/foreign-treaty-rate                     0.15M
 :at-iie/kest-rate                               0.275M
 :at-iie/kest-prepaid                            247.50M    ;; bank-withheld
 :at-iie/endbesteuert?                           false      ;; E1kv required for credit
 :at-iie/dba-credit-claimable                    135M}
```

Provider issues `:credit {:kind :at-dba-quellensteuer :amount
135M :refundable? false :cap-formula :treaty-rate × gross}`.
Non-refundable: if the AT tax were zero (e.g. dividend exempt
under §10 KStG corporate side; cf. BFG 2024 ruling), the credit
would be **lost**.

### 2.5 GmbH receives a §10 Abs 1 Z 1 KStG domestic dividend

Müller-Holding GmbH (AT Kapitalgesellschaft) holds 5 % of OMV
shares. In 2026, OMV pays €100,000 dividend to Müller-Holding.

- **GL recognition**: Dr Bank €100,000 / Cr Income:Dividends
  €100,000 (gross — the corporate-receipt of a domestic dividend
  is NOT subject to KESt withholding; §94 Z 2 EStG exempts
  inter-corporate domestic dividends from KESt).
- **§10 Abs 1 Z 1 KStG**: dividends from AT corps are **exempt**
  at the receiving corp.
- **CIT base**: the €100,000 dividend is **removed** from the CIT
  taxable income via a permanent-difference deduction.
- **Provider action**: emits `:cit-base-deductions {:dividend-§10
  100000M}`.
- **AT CIT impact**: **€0** on this dividend.

Substrate trace:

```clojure
{:investment-income-event/kind                  :dividend
 :investment-income-event/cash-amount           100000M
 :investment-income-event/source                :at-resident-corp
 :investment-income-event/source-partner        #db/id<omv-id>
 :investment-income-event/holder-kind           :corporation
 :at-iie/§10-classification                     :domestic
 :at-iie/ownership-fraction                     0.05M
 :at-iie/kest-rate                              0M         ;; corporate-receipt; §94 Z 2 exemption
 :at-iie/kest-prepaid                           0M}
```

Provider routes via `:§10-classification :domestic` →
`:cit-base-deductions`.

### 2.6 GmbH receives a foreign portfolio dividend (§10 Abs 1 Z 5-6)

Müller-Holding GmbH holds 3 % of a German DAX-corp (resident in DE,
which has comprehensive treaty + administrative assistance with
AT). The DE corp pays a €50,000 dividend in 2026. DE withholds at
treaty rate 15 % = €7,500; net €42,500 received in AT.

- **GL recognition**: Dr Bank €42,500 / Cr Income:Dividends €50,000
  / Dr Foreign-WHT €7,500.
- **§10 Abs 1 Z 5 KStG**: EU portfolio dividend; **exempt** at
  Müller-Holding (no holding-period or stake threshold).
- **§10 Abs 4 check**: is DE a low-tax jurisdiction? DE corporate
  rate ~30 % (KSt + Soli + GewSt); well above the AT 15 %
  threshold → §10 Abs 4 does NOT fire; exemption stands.
- **CIT base**: €50,000 removed via permanent-difference
  deduction.
- **DBA-Quellensteuer credit**: per BFG 2024, NO — the dividend
  is exempt, so the crediting limit is zero. The €7,500 foreign
  WHT is **lost** at AT (could be reclaimed from DE if within
  the 4-year deadline).

Substrate trace:

```clojure
{:investment-income-event/kind                  :dividend
 :investment-income-event/cash-amount           50000M
 :investment-income-event/source                :foreign
 :investment-income-event/holder-kind           :corporation
 :at-iie/foreign-jurisdiction                   :de
 :at-iie/foreign-tax-withheld                   7500M
 :at-iie/foreign-treaty-rate                    0.15M
 :at-iie/§10-classification                     :foreign-portfolio
 :at-iie/ownership-fraction                     0.03M
 :at-iie/low-tax-jurisdiction?                  false}
```

Provider routes via `:§10-classification :foreign-portfolio` →
`:cit-base-deductions`. The `:foreign-tax-withheld` is recorded for
audit but generates NO credit (BFG 2024 inversion). Audit-doc
flags the lost credit as a recovery opportunity at source.

### 2.7 Same GmbH receives the dividend, but the §10 Abs 4 switch-over fires

Same as 2.6 but the foreign corp is a Cyprus IP-holding entity
with effective corporate rate 8 % (under the AT 15 % threshold)
AND predominantly passive income.

- **§10 Abs 4 KStG fires**: exemption **switches off**.
- **CIT base**: €50,000 enters the AT CIT taxable income as
  ordinary income.
- **AT CIT**: €50,000 × 23 % = **€11,500**.
- **DBA-Quellensteuer credit**: Cyprus WHT (assume 0 % under
  treaty for IP dividends — varies by structure) = **€0** credit
  in this scenario. If it were 5 % = €2,500 → credited
  non-refundably against the €11,500 → net €9,000.

Substrate trace: same as 2.6 but with
`:at-iie/low-tax-jurisdiction? true` (or
`:tax-unit :at-foreign-corp-etr 0.08M` + provider compares).
Provider routes to ordinary CIT branch + emits DBA credit if any.

---

## §3. `:investment-income-event` schema fit — borrowing from note 153

Note 153 §3 proposes a new kernel companion
`kontor-investment-income` with ~9 cross-jurisdiction kernel attrs
in `:investment-income-event/*` namespace. AT is the second
consumer (after AU) of this substrate; the design is validated
across two jurisdictions, with the pattern travelling cleanly.

### 3.1 Kernel attrs (per note 153 §3 Option B)

```
:investment-income-event/kind            #{:dividend :interest :distribution
                                            :rental :royalty :fund-distribution}
:investment-income-event/cash-amount     :bigdec    ; gross (the assessable amount)
:investment-income-event/commodity       :keyword
:investment-income-event/source          :keyword   ; resident-corp, foreign, resident-bank, trust, fund
:investment-income-event/source-partner  :ref       ; the distributing entity (optional)
:investment-income-event/event-date      :instant
:investment-income-event/holder-kind     :keyword   ; cross-jurisdiction enum
:investment-income-event/realizing-tx    :ref
:investment-income-event/audit-doc       :ref
```

AT fits **cleanly** — every event the AT regime needs to tax is
representable as `:dividend` / `:interest` / `:fund-distribution`.
Bond coupons are `:interest` from the issuer's perspective; the
substrate carries the source corp via `:source-partner`. The
`:holder-kind` enum reuses the AU
`#{:individual :trust :super-fund :company}` set; AT does not need
`:super-fund` (Austria has no equivalent of the AU
self-managed-super construct — pensions are state-run + private
Pensionsfonds, which are taxed differently and out of scope for
v1).

### 3.2 AT companion attrs (`:at-iie/*`)

| Attr | Purpose | Carries |
|---|---|---|
| `:at-iie/kest-rate` | The KESt rate withheld (0.275M / 0.25M / 0M) | Dispatches the bucket; verifies bank-side withholding |
| `:at-iie/kest-prepaid` | The actual €-amount withheld by the AT custodian | Reconciliation + credit emission |
| `:at-iie/kest-bucket` | `:wertpapier-vermoegen` (27.5 % bucket) / `:sparbuch` (25 % bucket) | Loss-offset routing |
| `:at-iie/endbesteuert?` | True if KESt discharges the liability | Skips the annual return entry if true |
| `:at-iie/foreign-jurisdiction` | ISO 3166-1 alpha-2 of the source country | DBA-Quellensteuer lookup |
| `:at-iie/foreign-tax-withheld` | EUR-equivalent foreign WHT | Credit emission |
| `:at-iie/foreign-treaty-rate` | The applicable treaty rate cap | Credit cap formula |
| `:at-iie/dba-credit-claimable` | The credit after capping (provider-computed; cached) | Audit trail |
| `:at-iie/§10-classification` | `:domestic / :foreign-portfolio / :schachtelbeteiligung / :ordinary` | Corporate-side dispatch |
| `:at-iie/ownership-fraction` | 0..1 fraction (for §10 Abs 2 ≥ 10 % test) | Schachtel-gate |
| `:at-iie/low-tax-jurisdiction?` | True if §10 Abs 4 switch-over fires | Override the §10 exemption |
| `:at-iie/fund-type` | `:meldefonds / :nichtmeldefonds` | Fund-distribution dispatch |
| `:at-iie/deemed-distribution?` | True for year-end Nichtmeldefonds pauschal | P2 deferred branch |

**Totals**: ~12 attrs on the AT companion side; 0 kernel additions
beyond what note 153 already proposes.

### 3.3 Shared with the AT CGT provider (note 134)

The `:at-iie/kest-bucket` value space (`:wertpapier-vermoegen` /
`:sparbuch`) MUST coordinate with the CGT provider's loss-bucket
dispatch (`:loss-bucket :at-kest`). The losses computed by the
CGT provider on `:disposal` records get **netted** against
dividends + interest on `:investment-income-event` records in the
same year, within the same bucket. The provider seam:

- The CGT provider's `:emits-inputs` includes
  `:capital-loss-carryforward {:at-kest {:wertpapier-vermoegen amt
  :sparbuch amt}}` — but for AT private investors, this is
  **deliberately zero** at year-end (Jan 1 reset; note 134 §6.3).
- WITHIN the year, the netting happens BEFORE either provider
  emits to the PIT provider — that is, both providers must share a
  loss-offset orchestration step. **Implementation pattern**: a
  sibling `at-kest-orchestrator` function that calls both
  providers, collects per-event gross + loss data, applies the
  Verlustverrechnungstopf within-bucket netting, then emits the
  combined `TaxReturnFacts` with the netted base. The two provider
  records themselves are pure (each computes its own gross); the
  orchestrator handles the cross-provider netting.

Alternative: the investment-income provider reads the CGT
provider's `:emits-inputs` from a prior call (impossible in single
`(period-tax-facts ctx)` call) OR receives the CGT-loss residue
via `:inputs :at-kest-cgt-loss-residue` (consumer wires it).

**Recommendation**: the **orchestrator pattern** — a `kontor.book`
or `kontor.l10n-at.tax-orchestrator` helper that wraps the
sibling providers. Returns a combined `TaxReturnFacts`. Documents
the within-year compositional discipline in code.

---

## §4. Concrete data gaps

Three layers, anchored by the proposed cross-jurisdiction substrate
in note 153 Gap 1:

### Gap 1 — depends on note 153 Gap 1 (kernel-companion approval)

If `kontor-investment-income` ships as a kernel companion (note
153 §3 Option B), AT consumes it without modification. If
rejected, AT falls back to GL-tagged via ADR-090 concept-iri and
the provider becomes more brittle (note 153 Gap 1 dissents apply
here too).

**Recommendation**: ship the kernel companion. AT + AU together
amortise the design cost.

### Gap 2 — `:at-iie/*` companion attrs (al-l10n-side)

Per §3.2. ~12 attrs in the `:at-iie/*` namespace. Standard
companion-namespace addition, no kernel concern. Lives in the
existing `kontor.l10n-at` module.

### Gap 3 — `:tax-unit :at-elections` shape extension

The AT CGT provider already accepts `:tax-unit
:at-elections #{...}` for `:at-regelbesteuerungsoption` etc.
(note 134 §1.4). The investment-income provider extends the set
with `:at-verlustausgleichsoption` (cross-bank netting) and
reuses `:at-regelbesteuerungsoption`. No new shape; new keyword
value.

### Gap 4 — `:tax-unit :at-foreign-corp-etr` (§10 Abs 4 switch-over)

For the corporate-side branch, the consumer attests the foreign
corp's effective tax rate via `:tax-unit :at-foreign-corp-etr
<bigdec>`; the provider compares to the 15 % parameter (an
ADR-101 parameter, bitemporal — the 12.5 % → 15 % step in 2026
must be carried as a `:parameter-value` history entry).

### Gap 5 — `:tax-unit :at-treaty-rate-cap` per (jurisdiction, income-type)

For the DBA-Quellensteuer credit, the cap is treaty-specific.
Three approaches:
- **(a)** Consumer attests via `:tax-unit :at-treaty-rate-cap
  <bigdec>` for each event. Simple but requires manual lookup.
- **(b)** AT companion ships a static treaty-rate table indexed
  by `:foreign-jurisdiction` + `:income-type`. ~30 treaties; a
  static map.
- **(c)** ADR-101 parameter per treaty (e.g.
  `:at-dba-us-dividend-rate 0.15M`). Bitemporal-safe but verbose.

**Recommendation: (b) — ship a static table**. Treaty rates are
stable; bitemporal versioning is unnecessary for most. Edge cases
override via (a). The table lives in
`modules/l10n-at/resources/dba-treaty-rates.edn`.

### Gap 6 — DEFER: Nichtmeldefonds pauschal year-end taxation

§1.11. Exotic; deferred to consumer demand. The `:at-iie/fund-type
:nichtmeldefonds` flag + `:at-iie/deemed-distribution? true`
record carries the data; the provider raises
`:not-yet-implemented` for v1. P2 follow-up.

### Gap 7 — DEFER: §10a KStG CFC (Hinzurechnungsbesteuerung)

§1.9. Deemed-income mechanism; out of scope for the
realisation-event substrate. Flag in the ADR for future expansion
when consumer demand surfaces.

### Gap 8 — DEFER: Alleinverdienerabsetzbetrag offset on KESt refund

§1.5. PIT-side mechanic; lives on the AT PIT provider's expansion,
not on the investment-income provider. Flag in the ADR for AT PIT
provider work.

**Totals**: 0 kernel additions beyond note 153's; ~12
companion-namespace attrs (at-l10n-side); 2 `:tax-unit` shape
extensions; 1 static resource file for treaty rates; 0 schedule-
algebra changes; 0 ADR-101 parameter additions beyond the 15 %
low-tax threshold for §10 Abs 4.

---

## §5. Provider sketch — TWO providers + an orchestrator

### 5.1 `at-kest-investment-income-provider` — individual side

Single `PeriodTaxProvider`, `:kind :investment-income-tax`,
`:authority :at-finanzamt`. Symmetric to the existing
`at-kest-cgt-provider` (note 134 §5.1):

```clojure
(defrecord AtKestInvestmentIncomeProvider [kind]
  PeriodTaxProvider
  (period-tax-facts [_ ctx period entity]
    (let [events       (events-in-period ctx period entity)
          dividends-275 (filter #(= 0.275M (:at-iie/kest-rate %)) events)
          interest-25   (filter #(and (= :interest (:kind %))
                                      (= 0.25M  (:at-iie/kest-rate %)))
                                events)
          foreign-divs  (filter #(= :foreign (:source %)) events)
          elections     (get-in ctx [:tax-unit :at-elections] #{})
          regelbest?    (:at-regelbesteuerungsoption elections)
          ;; --- per-event compute
          gross-275     (sum (map :cash-amount dividends-275))
          gross-25      (sum (map :cash-amount interest-25))
          prepaid-275   (sum (map :at-iie/kest-prepaid dividends-275))
          prepaid-25    (sum (map :at-iie/kest-prepaid interest-25))
          dba-credits   (compute-dba-credits foreign-divs ctx)
          ;; --- routing
          assemble-component
            (fn [{:keys [base rate prepaid bucket]}]
              {:kind :investment-income-tax :authority :at-finanzamt
               :base base
               :schedule (ts/flat rate)
               :gross-liability (* base rate)
               :liability (- (* base rate) prepaid)
               :prepaid prepaid
               :line-items [{:line :at-kest-base :value base}
                            {:line :at-kest-prepaid :value prepaid}
                            {:line :at-kest-bucket :value bucket}]})]
      {:kind            :investment-income-tax
       :authority       :at-finanzamt
       :period          period
       :components
       (cond-> []
         (and (not regelbest?) (pos? gross-275))
         (conj (assemble-component {:base gross-275 :rate 0.275M
                                    :prepaid prepaid-275
                                    :bucket :wertpapier-vermoegen}))
         (and (not regelbest?) (pos? gross-25))
         (conj (assemble-component {:base gross-25  :rate 0.25M
                                    :prepaid prepaid-25
                                    :bucket :sparbuch})))
       ;; If Regelbesteuerung, fold into PIT base via JSC
       :jurisdiction-specific-codes
       (cond-> {}
         regelbest?
         (assoc :pit-base-additions
                {:at-kest-vermoegen gross-275
                 :at-sparbuch       gross-25}
                :pit-credits
                {:at-kest-prepaid (+ prepaid-275 prepaid-25)
                 :refundable? true})
         (seq dba-credits)
         (assoc :pit-credits-non-refundable
                {:at-dba-quellensteuer (sum (map :amount dba-credits))}))
       :reads-inputs    #{:at-kest-cgt-loss-residue}    ;; from orchestrator
       :emits-inputs    {}})))
```

**Key design calls**:

1. **Bucket-segregated components** — 27.5 % and 25 % buckets are
   separate components; the substrate naturally supports this via
   the `:components` vector on `TaxReturnFacts`.
2. **Endbesteuerung default** — when the default flat regime fires,
   the provider emits a `:liability ≈ 0` component (KESt-prepaid
   matches KESt-due). The provider's role is **reconciliation
   + audit**, not collection. The component IS emitted (the
   informational E1kv attachment needs it).
3. **Regelbesteuerungsoption inversion** — when elected, the
   provider emits NO standalone components and instead routes the
   gross via `:jurisdiction-specific-codes :pit-base-additions`;
   the PIT provider sweeps the gross + credits the prepaid. Same
   inversion pattern as DE Günstigerprüfung (note 113 §5.2's
   recipe).
4. **DBA-Quellensteuer credits** — always emitted as
   `:pit-credits-non-refundable`; the PIT provider applies them
   capped at the bracket-rate × foreign-income.
5. **No carryforward** — `:emits-inputs` is empty (the bucket
   resets Jan 1; note 134 §6.3 + this note §1.6).

### 5.2 `at-corporate-investment-income-provider` — corporate side

Single `PeriodTaxProvider`, `:kind :investment-income-tax`,
`:authority :at-finanzamt`. Symmetric to the existing
`at-corporate-cgt-provider` (note 134 §5.3):

```clojure
(defrecord AtCorporateInvestmentIncomeProvider [kind]
  PeriodTaxProvider
  (period-tax-facts [_ ctx period entity]
    (let [events       (events-in-period ctx period entity)
          dividends    (filter #(= :dividend (:kind %)) events)
          interest     (filter #(= :interest (:kind %)) events)
          ;; --- §10 KStG classification (per event)
          classified
            (map (fn [d]
                   (assoc d :§10-effective
                          (apply-§10-classification d ctx)))
                 dividends)
          §10-exempt   (filter #(:exempt? (:§10-effective %)) classified)
          §10-taxable  (remove #(:exempt? (:§10-effective %)) classified)
          exempt-sum   (sum (map :cash-amount §10-exempt))
          taxable-sum  (sum (map :cash-amount §10-taxable))
          ;; --- DBA credits (only fire when CIT-taxable)
          dba-credits  (compute-dba-credits §10-taxable ctx)
          interest-sum (sum (map :cash-amount interest))    ;; always CIT-taxable for corps]
      {:kind            :investment-income-tax
       :authority       :at-finanzamt
       :period          period
       :components      []                                  ;; corp-side: no standalone
       :jurisdiction-specific-codes
       {:cit-base-deductions {:§10-dividends exempt-sum}    ;; remove exempt dividends
        :cit-credits-non-refundable
        {:at-dba-quellensteuer (sum (map :amount dba-credits))}}
       :reads-inputs   #{}
       :emits-inputs   {}})))
```

**Key design calls**:

1. **No standalone components** — corporate-side investment income
   folds into CIT entirely. The provider's role is the
   permanent-difference adjustment (§10 exemption deductions) +
   DBA credits.
2. **§10 classification per event** — the provider runs the
   classifier (domestic / portfolio / Schachtel / ordinary) per
   dividend; sums by classification; deducts exempt sums from CIT
   base.
3. **DBA credits only on taxable branch** — when §10 exempts the
   dividend, the DBA credit is **zero** (per BFG 2024). When §10
   Abs 4 switches off the exemption, the dividend is taxable and
   the DBA credit fires (capped).
4. **Interest always taxable for corps** — no §94 Z 5 EStG
   exemption for corps on bank interest; flows to CIT as ordinary
   income.

### 5.3 Orchestrator pattern — within-year KESt-loss netting

Per §3.3, the AT KESt regime nets capital LOSSES from disposals
against dividend + interest INCOME within the same year, within
the same bucket. Two-provider composition can't do this in a
single `(period-tax-facts ctx)` call. Recommended:

```clojure
(defn at-kest-period-tax-facts
  "Orchestrator for AT KESt regime — composes the CGT + investment-
   income providers with the §27 Abs 8 EStG within-year netting."
  [{:keys [conn entity period inputs] :as ctx}]
  (let [cgt-events       (kontor.disposal/disposals-in-period conn period entity)
        ii-events        (kontor.investment-income/events-in-period conn period entity)
        ;; --- per-bucket aggregation
        cgt-275-net      (kest-bucket-net cgt-events :at-kest-vermoegen)
        cgt-25-net       (kest-bucket-net cgt-events :sparbuch)  ;; usually 0
        ii-275-gross     (kest-bucket-gross ii-events :wertpapier-vermoegen)
        ii-25-gross      (kest-bucket-gross ii-events :sparbuch)
        ;; --- §27 Abs 8: 27.5 % bucket nets CGT losses against II gross
        bucket-275-net   (max 0M (+ cgt-275-net ii-275-gross))    ; can be negative
        bucket-25-net    (max 0M (+ cgt-25-net  ii-25-gross))     ; usually = ii-25-gross
        ;; Note: losses unused in year EXPIRE (no carryforward)
        adjusted-ctx     (assoc-in ctx [:inputs :at-kest-cgt-loss-residue]
                                  (- cgt-275-net))]
    {:cgt-facts (at-kest-cgt-provider/period-tax-facts adjusted-ctx)
     :ii-facts  (at-kest-investment-income-provider/period-tax-facts adjusted-ctx)
     :netted-bucket-275-base bucket-275-net
     :netted-bucket-25-base  bucket-25-net}))
```

**Key design call**: the orchestrator computes the netting,
substitutes the netted base back into the providers' inputs, then
calls both providers. The composition stays declarative within
each provider; the orchestrator handles the cross-provider
arithmetic.

V1 recommendation: ship the orchestrator as a SEPARATE function
(`kontor.l10n-at.kest-orchestrator/period-tax-facts`) that the
consumer calls; the two providers remain independently testable
and stay clean. Document the composition in the orchestrator
docstring + `:audit-doc/category :§27-Abs-8-EStG-Verlustverrechnung`.

### 5.4 Summary

| Provider | Components emitted | Substrate stress |
|---|---|---|
| `at-kest-investment-income-provider` | 1-2 (per bucket) | none — symmetric to `at-kest-cgt-provider` |
| `at-corporate-investment-income-provider` | 0 (JSC only) | none — symmetric to `at-corporate-cgt-provider` |
| `at-kest-orchestrator` | composite | one — within-year cross-event-type netting; orchestrator pattern handles |

Total: **2 providers + 1 orchestrator, 0 kernel schema changes
beyond note 153's `kontor-investment-income`, ~12 companion-
namespace attrs, 2 `:tax-unit` extensions, 1 static treaty-rate
resource**. Within the conservative posture of notes 134 / 153.

---

## §6. Coordination with the existing `at-cgt-provider` (note 134)

The shipped AT CGT provider has THREE provider records
(`at-kest-cgt-provider`, `at-immoest-provider`,
`at-corporate-cgt-provider` — `cgt_provider.clj` lines 6-93).
This note's investment-income providers PARALLEL them on the
income-side:

| CGT provider | Income-side sibling | Composition pattern |
|---|---|---|
| `at-kest-cgt-provider` | `at-kest-investment-income-provider` | §27 Abs 8 within-year netting via orchestrator |
| `at-immoest-provider` | (extend to handle rental-income loss offsets — note 134 §6.2) | First cross-category CGT loss; existing skeleton in `at-immoest-provider` |
| `at-corporate-cgt-provider` | `at-corporate-investment-income-provider` | Both emit `:cit-base-deductions`; no inter-provider read needed |

### 6.1 Holder-kind dispatch — share the enum

The CGT provider uses `#{:individual :trust :super-fund
:company}` as a generic holder-kind enum (note 134 line 80 +
note 129 §5). AT does not have super-funds; the AT CGT provider's
v1 implementation supports `:individual-kest`,
`:individual-immoest`, `:corporation` per its docstring (line
40-53). The investment-income provider should use the **same
dispatch keywords**:

- `at-kest-investment-income-provider` ↔ `:individual-kest`
- `at-corporate-investment-income-provider` ↔ `:corporation`

Consistency across the two providers means consumer wiring is
identical. The implementer should consolidate the holder-kind
constants in a single shared namespace
(`kontor.l10n-at.holder-kinds`) and import from both providers.

### 6.2 §27 Abs 8 EStG cross-provider netting — the orchestrator carries it

The within-year loss-netting between disposals (CGT) and
dividends + interest (II) is the **first cross-realisation-event-
type composition in the kontor substrate** (note 134 §6.2 documented
the §30 Abs 7 EStG cross-category case for ImmoESt + rental; this
note adds the within-bucket KESt case). The orchestrator pattern
in §5.3 handles it; the providers themselves stay pure.

### 6.3 The §10 KStG inversion — investment-income inherits the convention

The corporate-side investment-income provider uses the
**§10 Abs 1 + Abs 2** exemption mechanic as its default (note
154 §1.7). The Option zur Steuerwirksamkeit (`:elective-regime
:at-§10-tax-effective-option`) inverts to taxable. This is
**parallel to** the CGT-side §10 Abs 3 mechanic (note 134 §1.7
+ §6.1) — both inheritances apply uniformly: by default §10
exempts; the Option opts INTO taxable.

The Option **once exercised** covers BOTH gains AND dividends —
the election is per-participation, not per-income-type. So the
substrate's `:elective-regime :at-§10-tax-effective-option`
on the underlying `:partner` (note 134 §4.3:
`:at-l10n.partner/§10-option-elected-on`) drives BOTH providers'
classification. **Implementation discipline**: both providers
read the same `:tax-unit :at-§10-option-on-partner`
attestation; consistent classification across all events for
the participation in question.

### 6.4 DBA-Quellensteuer — shared mechanic, shared treaty table

The DBA-Quellensteuer credit applies to BOTH disposals (foreign-
source capital gains realised at a foreign custodian) and
investment income (foreign-source dividends + interest). The
mechanic + cap are identical. The treaty-rate table (§4 Gap 5)
should be shared across both providers — a single resource file
keyed by `(jurisdiction, income-type)` consumed by both the
CGT and investment-income providers.

---

## §7. Open questions for the implementation agent

1. **Should AT use the proposed `kontor-investment-income` kernel
   companion (note 153 Gap 1), or roll into `kontor.l10n-at`?**
   Recommendation: **share the kernel companion** with note 153.
   AT validates the design; the cross-jurisdiction reuse pays the
   substrate cost.

2. **The orchestrator pattern (§5.3) — exposed at the kernel or
   per-l10n?** The within-year netting between CGT losses and II
   income is **AT-specific** (the §27 Abs 8 EStG rules). Other
   jurisdictions have different netting rules (AU has none —
   different buckets; DE has the Abgeltungsteuer + §20 Abs 6
   EStG within-bank netting; FR PFU has its own rules). The
   orchestrator should live in **`kontor.l10n-at`**, not the
   kernel. **Recommendation: per-l10n orchestrator** —
   `kontor.l10n-at.kest-orchestrator/period-tax-facts`.

3. **Two providers or one — `at-kest-investment-income-provider`
   + `at-corporate-investment-income-provider` as siblings, OR a
   single provider with multi-kind dispatch?** The CGT provider
   shipped as THREE siblings (note 134 §5). For consistency, ship
   the investment-income as TWO siblings (individual + corp; AT
   has no super-fund). **Recommendation: TWO providers.**

4. **The §10 Abs 4 switch-over — how does the consumer attest the
   foreign corp's effective tax rate?** Three options:
   (a) per-event `:tax-unit :at-foreign-corp-etr`; (b) per-partner
   attestation on the source-partner record; (c) ADR-101 statute
   parameter table (per jurisdiction). **Recommendation**: (b)
   per-partner — the ETR is a property of the held entity, not the
   distribution; carry as `:at-l10n.partner/effective-tax-rate
   <bigdec>` on the partner; provider reads via `:source-partner`
   lookup.

5. **Treaty-rate table — versioning?** Treaty rates change rarely
   but DO change (e.g. AT-CH renegotiation 2023). Three options:
   (a) static EDN with effective-date keys; (b) ADR-101
   `:parameter-value` history; (c) per-call override via
   `:tax-unit`. **Recommendation**: (a) static EDN with
   per-jurisdiction `{:effective-from #inst :rate 0.15M}` entries;
   provider reads as-of the disposal date for bitemporal-safety.

6. **Anlage E1kv self-declaration — should the provider emit a
   filing-ready report?** The Regelbesteuerung branch + the
   offshore-custodian branch both require the investor to file
   E1kv. The provider's `:line-items` carry the necessary data;
   the actual XML / PDF generation is **document-side work**
   (`kontor.document.tax-return.at-e1kv` or similar). Out of
   scope for v1 provider work; flag for later document module.

7. **Meldefonds vs Nichtmeldefonds — which fund types are most
   common in practice?** Most retail AT investors hold UCITS
   funds that ARE Meldefonds (the major AT-domiciled funds are
   all Meldefonds; the OeKB reporting infrastructure is
   well-established). Nichtmeldefonds are typically exotic
   offshore funds. **v1 ships Meldefonds only**; Nichtmeldefonds
   raise `:not-yet-implemented` per Gap 6.

8. **§10a CFC (Hinzurechnungsbesteuerung) — when does it surface?**
   Deemed-income mechanism for low-taxed foreign sub-holdings.
   The substrate's `:investment-income-event` is a
   *realisation* substrate; CFC is *deemed*. They don't share a
   schema. **v1 defers**; future ADR for a CFC-specific substrate
   if consumer demand surfaces.

---

## §8. Sources

### AT statute (ris.bka.gv.at / jusline.at — public)

- **§27 EStG 1988** — Einkünfte aus Kapitalvermögen:
  https://www.jusline.at/gesetz/estg/paragraf/27
- **§27a EStG 1988** — Besonderer Steuersatz und Bemessungsgrundlage
  (the 27.5 % / 25 % flat rates):
  https://www.jusline.at/gesetz/estg/paragraf/27a
  - §27a Abs 1 Z 1 — bank-deposit interest 25 %.
  - §27a Abs 1 Z 2 — dividends + capital gains 27.5 %.
  - §27a Abs 5 — Regelbesteuerungsoption.
- **§27b EStG 1988** — Kryptowährungen (since Ökosoziales
  Steuerreformgesetz 2022).
- **§94 EStG 1988** — exemptions from KESt withholding (Z 2
  inter-corporate domestic dividends, Z 5 EU portfolio
  dividends).
- **§95 EStG 1988** — KESt-Schuldner und Abzugsverpflichteter
  (the bank as the withholder).
- **§97 EStG 1988** — Endbesteuerung mit Kapitalertragsteuer (the
  discharge mechanic).
- **§46 EStG 1988** — credit of withheld amounts against
  assessed liability.
- **§33 EStG 1988** — Einkommensteuertarif (the progressive
  brackets for Regelbesteuerung).
- **§186 + §188 InvFG 2011** — investment-fund taxation
  (Meldefonds + Nichtmeldefonds).
- **§10 KStG 1988** — Befreiung für Beteiligungserträge und
  internationale Schachtelbeteiligungen:
  https://www.jusline.at/gesetz/kstg/paragraf/10
  - §10 Abs 1 Z 1 — domestic participation.
  - §10 Abs 1 Z 5–6 — international portfolio.
  - §10 Abs 2 — internationale Schachtelbeteiligung.
  - §10 Abs 3 — tax-neutral default + Option zur Steuerwirksamkeit.
  - §10 Abs 4 — switch-over to taxable (low-tax jurisdiction).
- **§10a KStG 1988** — Hinzurechnungsbesteuerung (CFC; deferred for v1).
- Mindestbesteuerungsgesetz 2024 — Pillar Two; 15 % effective rate
  baseline.

### BMF (Bundesministerium für Finanzen) guidance

- BMF — "Besteuerung inländischer sowie im Inland bezogener
  Kapitalerträge":
  https://www.bmf.gv.at/themen/steuern/sparen-veranlagen/besteuerung-kapitalertraege-inland.html
- BMF — "Kapitalerträge im engeren Sinn":
  https://www.bmf.gv.at/themen/steuern/sparen-veranlagen/kapitalertraege-im-engeren-sinn.html
- BMF — "Allgemeine Informationen zu Einkünften aus
  Kapitalvermögen":
  https://www.bmf.gv.at/themen/steuern/sparen-veranlagen/information-zu-einkuenften-aus-kapitalvermoegen.html
- BMF — "Doppelbesteuerungsabkommen (DBA) — Allgemeines":
  https://www.bmf.gv.at/themen/steuern/internationales-steuerrecht/doppelbesteuerungsabkommen/dba-allgemeines.html
- BMF — "Rückerstattung österreichischer Abzugsteuer":
  https://www.bmf.gv.at/themen/steuern/internationales-steuerrecht/rueckerstattung/rueckerstattung-oesterreichischer-abzugsteuer.html
- BMF — "Betriebliche Kapitalerträge und Grundstücksgewinne":
  https://www.usp.gv.at/themen/steuern-finanzen/steuerliche-gewinnermittlung/weitere-informationen-zur-steuerlichen-gewinnermittlung/betriebseinnahmen-und-ausgaben/betriebliche-kapitalertraege-und-grundstuecksgewinne.html
- Findok BMF — the authoritative searchable database for BMF
  rulings: https://findok.bmf.gv.at/

### Practitioner commentary

- WKO — "Internationale Schachtelbeteiligung":
  https://www.wko.at/steuern/internationale-schachtelbeteiligung
- WKO — "Besteuerung von Kapitalvermögen":
  https://www.wko.at/steuern/besteuerung-kapitalvermoegen
- WKO — "Körperschaftsteuer (KÖSt)":
  https://www.wko.at/steuern/koest-koerperschaftsteuer
- ÖSV — "Sachliche Steuerbefreiungen (§§ 7 und 10 KStG 1988)" Teile 1 + 2:
  https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-1/
  https://www.steuerverein.at/16-sachliche-steuerbefreiungen-%C2%A7%C2%A7-7-und-10-kstg-1988-teil-2/
- Lexis Nexis — "Beteiligungsertragsbefreiung nach § 10 KStG"
  (Überblick): https://360.lexisnexis.at/d/artikel/beteiligungsertragsbefreiung_nach_10_kstg_uberblic/z_ges_2011_10_GeS_2011_10_506_0254624231
- ICON Wirtschaftstreuhand — "Auslandsbeteiligungen |
  Hinzurechnungsbesteuerung leicht gemacht!":
  https://www.icon.at/news/detail/auslandsbeteiligungen-hinzurechnungsbesteuerung-leicht-gemacht
- ICON Wirtschaftstreuhand — "Auslandsbeteiligungen |
  Steuerwirksamkeit endgültiger Vermögensverluste":
  https://www.icon.at/news/detail/auslandsbeteiligungen-steuerwirksamkeit-endgueltiger-vermoegensverluste
- Lang (WU Vienna) — "Anrechnungshöchstbetrag und steuerfreie
  Einkünfte" (SWI 2024/06):
  https://www.wu.ac.at/fileadmin/wu/d/i/taxlaw/Institute/Publikationen_Lang/SWI_2024_06_Lang.pdf
- BDO — "Ausländische Quellensteuer: Anrechnung und Rückerstattung":
  https://www.bdo.at/de-at/blog/der-standard-steuerblog-voraus/auslaendische-quellensteuer
- Wiener Börse — "Rückerstattung Quellensteuer":
  https://www.wienerborse.at/wissen/in-wertpapiere-investieren/wertpapierbesitz-und-steuer/rueckerstattung-quellensteuern/
- Enzinger Steuerberatung — "Doppelbesteuerung und Quellensteuer:
  Ausländische Kapitalerträge":
  https://www.enzinger-stb.at/doppelbesteuerung-und-quellensteuer-bei-auslaendischen-kapitalertraegen/
- Raisin — "Kapitalertragsteuer (KESt) 2026 in Österreich":
  https://www.raisin.com/de-at/steuer/kapitalertragsteuer/
- finfo.at — "Kapitalertragssteuer (KESt) in Österreich 2026":
  https://www.finfo.at/steuern/kapitalertragssteuer/
- finanzenverstehen.at — "Besteuerung von Investmentfonds bzw.
  ETFs in Österreich":
  https://finanzenverstehen.at/steuern/besteuerung-von-investmentfonds-bzw-etfs-in-oesterreich/
- finanzenverstehen.at — "Quellensteuer in Österreich (inkl.
  Berechnungstool)":
  https://finanzenverstehen.at/steuern/quellensteuer/
- KONSUMENT.AT — "Investmentfonds und KESt":
  https://konsument.at/geld-recht/investmentfonds-und-kest
- VÖIG — "9. Investmentfonds" (industry primer):
  https://www.voeig.at/voeig/internet_4.nsf/sysPages/x19ABFC5EBDAC2442C12575610030842A/$file/9.Investmentfonds.pdf
- onlinebrokertest.at — "KESt Österreich 2026":
  https://www.onlinebrokertest.at/steuern/
- Anadi Bank — "Kapitalertragsteuer (KESt) | Anadi erklärt
  Glossar": https://anadibank.com/glossar/kapitalertragsteuer-kest
- Erste Sparkasse — "Wertpapiere und Steuern in Österreich":
  https://www.sparkasse.at/sgruppe/finanziell-gesund/finanz-beitraege/wertpapiere-und-steuern
- Erste Sparkasse — "Withholding tax for non-residents":
  https://www.sparkasse.at/erstebank-en/about-us/withholding-tax-for-non-residents
- Erste Group — "Capital gains tax — Securities know how":
  https://www.erstegroup.com/en/investments/service-knowledge/services/securities-know-how/capital-gains-tax
- PwC — "INVESTITIONEN IN ÖSTERREICH: STEUERLICHE ASPEKTE":
  https://www.pwc.at/en/publikationen/steuern-und-recht/investitionen-in-oesterreich-steuerliche-aspekte.pdf
- PwC Worldwide Tax Summaries — Austria Individual Income
  Determination:
  https://taxsummaries.pwc.com/austria/individual/income-determination
- PwC Worldwide Tax Summaries — Austria Corporate Withholding
  Taxes: https://taxsummaries.pwc.com/austria/corporate/withholding-taxes
- Country Tax Calc — "Austria Income Tax Guide 2026":
  https://www.countrytaxcalc.com/tax-guides/austria-income-tax-guide-2026/
- Tax-Wizard — "Austria Tax Guide 2025: Formulare E1 & E1kv —
  Complete KESt Guide": https://tax-wizard.eu/en/p/austria-tax
- Pfennigfuchser — "Steuerliche Situation von Fonds in
  Österreich": https://www.pfennigfuchser.at/steuerliche-situation-von-fonds-in-oesterreich/

### kontor substrate cited (file:line)

- `src/kontor/book.clj:296-330` — `declare-dividend!` +
  `distribute-dividend!` verbs; docstring naming the
  jurisdiction-specific investment-income regimes implemented
  for AT here.
- `src/kontor/incorporation.clj` — issuer-side state.
- `src/kontor/period_tax_provider.clj` — `PeriodTaxProvider` +
  `TaxReturnFacts` shape + `:components` vector.
- `src/kontor/tax_schedule.clj` — `flat` constructor for the
  27.5 % / 25 % flat rates.
- `src/kontor/statute.clj` — ADR-101 `apply-provisions`; AT
  investment-income reuses for the §10 Abs 4 switch-over
  threshold parameter (12.5 % → 15 % in 2026).
- `modules/disposal/src/kontor/disposal/schema.clj` — `:disposal`
  schema; structural template for the proposed
  `:investment-income-event` (note 153 §3).
- `modules/l10n-at/src/kontor/l10n_at/cgt_provider.clj` — the
  THREE shipped AT CGT providers (`at-kest-cgt-provider`,
  `at-immoest-provider`, `at-corporate-cgt-provider`) this
  note's investment-income providers parallel.
- `modules/l10n-at/src/kontor/l10n_at/period_tax_provider.clj` —
  `at-income-tax-provider` (Einkommensteuer; the Regelbesteuerung
  branch folds into this) + `at-corporate-income-tax-provider`
  (KöSt; the corporate-side dividend exemption deducts from
  this) + `at-kommunalsteuer-provider`.
- `modules/l10n-at/src/kontor/l10n_at/cgt_statute.clj` — ADR-101
  statute data the AT-CGT provider consumes; investment-income
  side adds §10 Abs 4 low-tax-threshold parameter (15 % from
  2026) + §27a rate parameters (27.5 % / 25 %).
- `doc/decisions.md` ADR-099 — `PeriodTaxProvider` substrate.
- `doc/decisions.md` ADR-101 — statute-as-data substrate;
  potential home for the §10 Abs 4 low-tax threshold + the
  KESt rates' bitemporal history.
- `doc/decisions.md` ADR-102 — `kontor-disposal` companion; the
  structural analogue for the proposed `kontor-investment-income`
  companion (note 153 Gap 1).
- `doc/decisions.md` ADR-101 Addendum 1 — `:op :credit` +
  refundability slot; the provider's credit-line emission rides
  this.
- `doc/research/107-phase-3-incorporation-and-disposal.md` —
  Phase 3 plan; investment-income lands in Phase C2 (notes 153 + 154).
- `doc/research/134-at-cgt-fit.md` — AT CGT fit; the structural
  template this note's investment-income companion extends.
- `doc/research/146-at-cgt-baseline-review.md` — AT CGT
  baseline review; the §10 Abs 1 Z 1 dividend exemption gap
  this note's corporate-side provider closes.
- `doc/research/113-de-cgt-fit.md` §5.2 — DE Günstigerprüfung
  pattern; structural template for the AT Regelbesteuerungs-
  option opt-in branch.
- `doc/research/153-au-investment-income-fit.md` — AU
  investment-income fit (this note's sibling); shares the
  proposed `kontor-investment-income` companion substrate.

---

End of note 154.

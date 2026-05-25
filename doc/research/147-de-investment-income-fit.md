---
date: 2026-05-24
title: 147 — DE investment income — §20 EStG / Abgeltungsteuer substrate fit
audience: maintainer + the Phase C2 `de-investment-income-provider` implementer
status: research-before for the DE investment-income provider; no code; ADR-101 substrate-fit assessment + data-gap list at §3.5
---

# 147 — DE investment income (§20 EStG / Abgeltungsteuer) — substrate fit

This note answers ONE question: does the ADR-101 statute-as-data
substrate + ADR-099 `PeriodTaxProvider` + the existing DE CGT
provider (note 113 / ADR-103) carry enough machinery to drive a
faithful DE **investment-income** tax provider —
`Einkünfte aus Kapitalvermögen` (§ 20 EStG) under the
Abgeltungsteuer regime (§ 32d EStG) — or do we need to extend?

Bottom line: **the substrate is sufficient; provider is a thin
sibling of the existing personal-CGT provider.** The §20 income
flows (dividends, interest, fund distributions, Vorabpauschale,
royalty-like rents-from-capital) are σ_E-marginalizable from
posted `Income:Dividends` / `Income:Interest` / …  accounts on
the shareholder's books — exactly the McComb pattern note 99
named the verb facade for. The flat 25 % + Soli + KiSt rate
stack is the same shape the DE CGT provider already runs for
§20 capital-gains; what changes is the *base selector* (income
postings, not disposal records) and the *bucket alignment*
(dividends + interest land in the `:de-§20-other` loss-offset
bucket per § 20 Abs. 6 S. 4 EStG — they are NOT in the
stock-sale bucket). Five new parameters, one new compute-fn,
one new `:tax-unit` slot for the church-tax rate, and a
provider that emits ONE `:capital-gains-tax`-kind component
(per the closed `period-tax-kinds` enum — § 4) sharing the
existing §20 plumbing. NO kernel substrate stress; the
`PeriodTaxKind` enum does NOT need an
`:investment-income-tax` member because § 20 income +
§ 20 gains are statutorily ONE Abgeltungsteuer return.

The biggest design call is **Sparer-Pauschbetrag coordination**:
the €1 000 / €2 000 saver allowance is shared across § 20
*income* (dividends/interest) AND § 20 *capital gains* (the
existing CGT provider's `:de-§20-stock` + `:de-§20-other`
buckets). The two providers cannot each apply it independently
or the consumer double-counts. §5 lays out three options;
recommended is option (c) — a thin "combined-§20" orchestrator
helper at the consumer layer, mirroring the existing
`cgt-§8b-addback-input` bridge fn (CGT → CIT, note 136 P0-3).

---

## §1. The regime — §20 EStG / Abgeltungsteuer

### 1.1 §20 Abs. 1 EStG — categories of Einkünfte aus Kapitalvermögen

§ 20 Abs. 1 EStG enumerates **eleven** categories of investment
income (closed list; if it is not here it is not §20 income):

1. **Dividenden** — dividends and distributions from shares,
   GmbH-Anteile, Genussrechte (profit-participation rights).
2. **Liquidationserlöse** — liquidation proceeds (excluding
   return of paid-in capital).
3. **Investmenterträge** — fund distributions per InvStG
   (the Investment Tax Act layers atop §20).
4. **Stille Beteiligungen** — silent-partnership and
   profit-participating-loan returns.
5. **Hypothekenzinsen** — interest from mortgages and annuity
   debts (`Rentenschulden`).
6. **Versicherungserträge** — life-insurance proceeds with
   capital option (post-2004 contracts).
7. **Erträge aus sonstigen Kapitalforderungen** — generic
   interest from any capital loan where repayment / usage
   compensation is promised. The catch-all for ordinary bank
   interest.
8. **Diskontbeträge** — discount on bills of exchange.
9. **Schüttungs-ähnliche Bezüge** — distributions from
   tax-liable entities economically comparable to dividends.
10. **Wirtschaftliche Vorteile aus BgA** — gains from
    `Betriebe gewerblicher Art` of public corporations.
11. **Stillhalterprämien** — option-writer premia on covered
    calls.

§ 20 Abs. 2 enumerates the **capital-gains** side (sale of
securities, derivative settlement, etc.) — that's the DE CGT
provider's territory (note 113). **This note covers § 20 Abs. 1
only.**

### 1.2 The rate stack — §32d Abs. 1 EStG

§ 32d Abs. 1 EStG sets the Abgeltungsteuer at a flat **25 %**
on the §20 base, with a church-tax-aware formula:

> Die Einkommensteuer beträgt 25 % … (e − 4q) / (4 + k)

where:
- **e** = the §20 capital income (the base after
  Sparer-Pauschbetrag),
- **q** = creditable foreign withholding tax (`anrechenbare
  ausländische Quellensteuer`),
- **k** = the applicable church-tax rate (0, 0.08 or 0.09).

The formula encodes the **Sonderausgaben-effect of the church
tax** — when k > 0 the effective Abgeltungsteuer rate drops
slightly (the church tax itself is deductible as a
Sonderausgabe, but only in the lumped form embedded in this
formula; a separate Sonderausgabenabzug for the church-tax-on-
capital is **denied** by § 10 Abs. 1 Nr. 5 EStG). For k = 0 the
formula collapses to (e − 4q) / 4 = 0.25 × (e − q), i.e. plain
25 % on the post-DBA base.

On top of the Abgeltungsteuer sit:

- **Solidaritätszuschlag** (§ 4 SolZG) — 5.5 % surtax on the
  Abgeltungsteuer. **CRITICAL DIFFERENCE FROM PIT-Soli:**
  there is NO Freigrenze and NO Milderungszone on the
  Soli-on-Abgeltungsteuer; it fires from the first euro of
  Abgeltungsteuer. The 2021 Soli-reform that exempted ~90 % of
  income-tax payers from Soli on their PIT explicitly did NOT
  touch the Abgeltungsteuer-Soli (the Bundestag's gloss: capital
  income is dispositional, not salary).
- **Kirchensteuer** (KiStG der Länder) — 8 % (BY / BW) or
  9 % (other 14 Länder), applied to the **Abgeltungsteuer**
  not to the base. The bank withholds automatically via the
  Bundeszentralamt-für-Steuern KiStAM
  (`Kirchensteuerabzugsmerkmal`) query — but the bank only
  knows the rate if the taxpayer didn't `widerspruch` against
  the automatic query, in which case the church tax is assessed
  in the annual return.

Effective combined rates (rounded):
- Without KiSt: 25 % × (1 + 0.055) = **26.375 %**.
- With KiSt 8 %: 25 % × (1 + 0.055 + 0.08) − Sonderausgaben-
  effect ≈ **27.82 %**.
- With KiSt 9 %: ≈ **27.99 %** (commonly cited as "~28 %").

### 1.3 Sparer-Pauschbetrag — § 20 Abs. 9 EStG

> Bei der Ermittlung der Einkünfte aus Kapitalvermögen ist als
> Werbungskosten ein Betrag von **1 000 Euro** abzuziehen
> (Sparer-Pauschbetrag); … Ehegatten, die zusammen veranlagt
> werden, wird ein gemeinsamer Sparer-Pauschbetrag von
> **2 000 Euro** gewährt.

History (date-keyed for the substrate's `:parameter-value`
timeline):
- 2009-01-01 … 2022-12-31: € 801 single / € 1 602 joint
  (Unternehmenssteuerreform 2008).
- **2023-01-01 onward: € 1 000 single / € 2 000 joint**
  (Zinsanpassungsgesetz, BGBl. I 2022 Nr. 384).
- 2026 verified unchanged.

The Sparer-Pauschbetrag is a **Werbungskostenpauschale** — a
lump deduction *in lieu of* actual investment expenses; § 20
Abs. 9 S. 1 EStG bars deducting actual Werbungskosten on
Abgeltungsteuer income (`Werbungskostenabzugsverbot`). The
allowance applies to the **combined** §20 income + gains base —
NOT once per income type, NOT once per provider. This is the
provider-coordination wrinkle §5 addresses.

The allowance is implemented at the bank via a
`Freistellungsauftrag` (the taxpayer's per-bank instruction
allocating part of the €1 000 to that bank's withholding); any
allowance unused by withholding can be claimed in the annual
return. The bank's withholding (Kapitalertragsteuer) is the
**enforcement layer**; the substrate's `:prepaid` slot on the
component carries this — the period-tax return reconciles
withheld-to-assessed exactly the way the existing PIT provider
treats PAYE.

### 1.4 Günstigerprüfung — § 32d Abs. 6 EStG

> Auf Antrag des Steuerpflichtigen werden anstelle der
> Anwendung der Absätze 1, 3 und 4 die nach § 20 ermittelten
> Kapitaleinkünfte den Einkünften im Sinne des § 2 hinzugerechnet
> und der tariflichen Einkommensteuer unterworfen, wenn dies zu
> einer **niedrigeren Einkommensteuer einschließlich
> Zuschlagsteuern** führt.

The taxpayer **elects** out of the flat 25 % into marginal-rate
taxation when the marginal rate is below 25 %. Mechanics:

- **Unit of election:** the request is "einheitlich für
  sämtliche Kapitalerträge" — **all** §20 income (dividends +
  interest + gains) of the period is folded into the marginal
  base, all-or-nothing.
- **For joint filers:** must cover **both** spouses' §20
  income — no partial election. This is a per-`tax-unit` flag.
- **Timing:** can be filed up to the Bestandskraft of the
  Bescheid (§ 32d Abs. 6 + AO timing — Festsetzungsverjährung).
- **Better-of test:** the Finanzamt performs the comparison
  ex officio once the request is filed (the taxpayer requests
  the *test*, not the *outcome*) — if the flat regime is
  better, the test fails and the flat regime stays.
- **Sparer-Pauschbetrag survives Günstigerprüfung** — even
  under marginal-rate taxation the €1 000 / €2 000 is deducted
  from the §20 base **before** the income joins the marginal
  base (BFH VIII R 14/13). This is the easy case for the
  substrate.

The existing CGT provider already reads
`:tax-unit :abgeltungsteuer-elect-marginal?` and produces a
`§20-pit-fold-component` when set. The investment-income
provider MUST read the **same flag** and behave symmetrically,
or the all-or-nothing rule breaks (both providers must elect
together).

### 1.5 Teileinkünfteverfahren on dividends — § 32d Abs. 2 Nr. 3 EStG + § 3 Nr. 40 EStG

A taxpayer who holds a corporate participation **≥ 25 %**, OR
**≥ 1 % AND exercises maßgeblichen unternehmerischen Einfluss
through professional activity** for the issuing corporation,
may elect under § 32d Abs. 2 Nr. 3 EStG that the **dividend
income** (NOT interest, NOT other §20 categories — only the
participation-dividend) is taxed at the **marginal rate with
the Teileinkünfteverfahren applied** (i.e. only 60 % of the
dividend is taxable; only 60 % of related Werbungskosten —
e.g. acquisition-loan interest — is deductible per § 3c Abs. 2
EStG).

This is a different election from Günstigerprüfung:

| Property | Günstigerprüfung (§ 32d Abs. 6) | Teileinkünfte-Option (§ 32d Abs. 2 Nr. 3) |
|---|---|---|
| Scope | ALL §20 income, all-or-nothing | ONLY dividends from the qualifying participation |
| Inclusion rate | 100 % into marginal base | 60 % into marginal base |
| Werbungskosten | Sparer-Pauschbetrag only | 60 % of *actual* Werbungskosten deductible |
| Sparer-Pauschbetrag | Applies | DOES NOT apply to the participation dividend (it is no longer "Abgeltungsteuer income") |
| Lock-in | 1-period | 5 years (re-applies automatically) |
| Trigger | Marginal rate < 25 % | Material participation + active business influence |

Both elections coexist in the same return for different income
streams — the provider needs to discriminate per dividend
source. Recommended substrate handling: a per-dividend-source
flag on the consumer's income posting (`:partner` ref → the
issuing corp; consumer marks specific partner-relations as
"Teileinkünfte-elected" via a `:tax-unit` map slot:
`:teileinkünfte-elected-issuers #{<partner-ref>…}` — same
shape pattern as `:abgeltungsteuer-elect-marginal?`).

The existing § 17 EStG **disposal-side** Teileinkünfteverfahren
in the DE CGT provider uses the same 60 % inclusion rate
(`DE.EStG.§17.inclusion-rate` = 0.60 — installed by
`cgt-statute.clj`). The same parameter is reused for §32d Abs.
2 Nr. 3 on dividends — both rules track to § 3 Nr. 40 EStG, so
ONE parameter is correct (re-keyed by a separate parameter
code with the same statutory citation to keep the bilateral
referenceability — see §3.3).

### 1.6 § 20 Abs. 6 EStG — Verlustverrechnung (the loss buckets)

The **post-JStG 2024** loss-offset wall (effective for all
open cases per § 52 Abs. 28 EStG — the BMF Schreiben 2025-05-14
gives banks until 2026-01-01 to retrofit the withholding
systems):

- **§ 20 Abs. 6 S. 1** — §20 losses (income side OR gains side)
  CANNOT offset other income types (e.g. salary, business
  profit). Wall around the entire §20 category.
- **§ 20 Abs. 6 S. 2-3** — within §20, losses are carried
  forward indefinitely; carry-back is NOT permitted (one
  asymmetric direction).
- **§ 20 Abs. 6 S. 4** — the **stock-sale wall**:
  Aktienveräußerungsverluste (§ 20 Abs. 2 S. 1 Nr. 1 — sale of
  shares) can only be offset against
  Aktienveräußerungsgewinne. Stock losses CANNOT offset
  dividends, interest, fund distributions, ETF sale gains, or
  derivative gains. **CURRENTLY UNCONSTITUTIONAL pending —
  BFH Vorlagebeschluss VIII R 11/18 (2020-11-17), case at the
  BVerfG as 2 BvL 3/21**; decision expected 2026. Substrate
  treats the wall as in-force until BVerfG strikes it (and the
  date-keyed parameter then gets an `:effective-until` cap).
- **§ 20 Abs. 6 S. 5 + S. 6 (ABOLISHED retroactively by
  JStG 2024-12-05)** — the €20 000-cap on Termingeschäfte
  derivative-loss offset, AND the separate
  Forderungsausfälle bucket. Both REMOVED for all open cases
  (Nichtbeanstandungsfrist for banks: 2025-12-31; mandatory
  per BMF 2025-05-14 from 2026-01-01). Existing carryforwards
  from these sub-buckets are now freely offsettable against
  ALL §20 income.

**The dividend-and-interest bucket assignment (the
question the task brief asks):**

- **Dividends, interest, fund distributions, Vorabpauschale,
  insurance-contract gains, ETF-sale gains, bond-sale gains,
  derivative gains** → land in the **GENERAL §20 bucket**
  (informally `Allgemeiner Verlusttopf` at the bank), which
  the substrate calls `:de-§20-other`.
- **Stock-sale gains** (§ 20 Abs. 2 S. 1 Nr. 1 — disposal of
  shares of a Kapitalgesellschaft) → land in the
  **stock-sale gain side**, which (post-JStG 2024) can be
  offset by stock-sale losses OR by general-bucket losses
  (asymmetric — the wall is one-directional: STOCK LOSSES
  cannot leave the stock bucket; everything else can offset
  stock GAINS).

This means **dividends + interest = `:de-§20-other`** for the
purpose of consuming any carryforward. The existing CGT
provider's `(:de-§20-other carry-in)` bucket is *the same*
bucket the investment-income provider must read. **Coordination
with the existing CGT provider is essential** (see §5).

### 1.7 Foreign-dividend Quellensteuer credit — § 32d Abs. 5 EStG (OUT OF SCOPE v1)

For DBA-treaty-resident foreign issuers, the foreign
withholding tax on the dividend (typically capped at 15 % by
the DBA — US, CH, NL, FR are the common cases) is creditable
against the German Abgeltungsteuer up to 25 % of the
individual capital income (the "per-item" cap in § 32d Abs. 5 —
NOT the per-overall-tax cap of § 34c). When the foreign tax
exceeds the DBA rate, the excess is reclaimed in the source
country (the German credit is capped at the DBA rate).

This is the `q` term in the § 32d Abs. 1 formula
((e − 4q) / (4 + k)). v1 of the provider can accept a
pre-summed `:foreign-tax-credit` `:inputs` slot and feed it
into the formula; document explicitly that source-country
reclaim is OUT of scope (it's a separate `kontor.side-effect`
workflow, akin to VAT reclaim — different domain). The
BZSt-published `Anrechenbare ausländische Quellensteuer
2025` table (annual update) is the data source the consumer
plugs in.

### 1.8 Vorabpauschale on accumulating funds — § 18 InvStG

Accumulating (thesaurierende) investment funds DO NOT
distribute, so without a fiction the Abgeltungsteuer would
sit dormant until eventual disposal. The InvStG (the
2018-reformed Investment Tax Act) sets a **Vorabpauschale**:
each January the fund computes a `Basisertrag` =
NAV-at-start-of-year × Basiszins × 0.7, capped at the
actual NAV-increase over the year; this Basisertrag is
**deemed §20 income** in the FOLLOWING January.

For 2026 (per the BMF letter of 2026-01-13): Basiszins **3.20 %**
(was 2.53 % in 2025).

The **Teilfreistellung** § 20 InvStG carves out:
- **30 %** for Aktienfonds (≥ 51 % equity allocation
  continuously),
- **15 %** for Mischfonds (≥ 25 % equity),
- **60 %** for offene Immobilienfonds (real-estate funds, with
  certain conditions; 80 % if predominantly foreign assets),
- **0 %** for Rentenfonds (pure bond funds).

The Teilfreistellung reduces both the distribution AND the
Vorabpauschale before Abgeltungsteuer applies. **For v1, the
provider accepts pre-Teilfreistellung amounts on the
`Income:Fund-Distributions` postings** — the consumer (or the
bank's tax-reporting CSV) does the Teilfreistellung
classification at posting time, the same way it currently
applies for the existing CGT provider's
`:de-§20-other` bucket. A future iteration can model the
Teilfreistellung as a per-asset-class statute parameter (the
shape mirrors the §17 Teileinkünfteverfahren — a fractional
inclusion rate) but it requires fund-classification metadata
the kontor substrate does not yet carry. **Document the
limitation; do not stress the substrate to chase it in v1.**

---

## §2. Two worked examples

### 2.1 Standard Abgeltungsteuer (no church tax, no Günstigerprüfung)

Source: § 32d Abs. 1 EStG;
[brutto-netto.de Abgeltungssteuer-Rechner 2026](https://www.brutto-netto.de/wissen/steuern/abgeltungssteuer/);
[BMF Schreiben 2025-05-14 Einzelfragen zur Abgeltungsteuer](https://www.bundesfinanzministerium.de/Content/DE/Downloads/BMF_Schreiben/Steuerarten/Abgeltungsteuer/2025-05-14-einzelfragen-zur-abgeltungsteuer.pdf).

Single taxpayer A. in 2026, not church-affiliated, no foreign
holdings. Period income:
- Dividends from DE blue-chip portfolio: € 3 500
- Bond interest from a German corporate bond ETF: € 1 200
- Bank savings interest: € 380
- No § 20 Abs. 2 capital gains in the period.

Computation:
```
Gross §20 income           = 3 500 + 1 200 + 380 = € 5 080
− Sparer-Pauschbetrag      =                       −  1 000
= Taxable §20 base (e)     =                       € 4 080

Abgeltungsteuer (k = 0):
  e / 4 = 4 080 / 4         =                       € 1 020
Solidaritätszuschlag (5.5 % × Abgst):
  1 020 × 0.055             =                       €    56.10
Kirchensteuer (k = 0)       =                       €     0
─────────────────────────────────────────────────────────────
Total period liability                              € 1 076.10
```

Effective rate on taxable base: 26.375 %.
Effective rate on gross income: 21.18 % (the Sparer-Pauschbetrag
absorbed € 264 of would-be tax).

If the bank withheld correctly via Freistellungsauftrag-routed
KESt + SolZ on each payout in real time, the
period-end `:prepaid` matches the `:liability` exactly and the
return shows €0 balance due (or a tiny reconciliation for
timing mismatch on the Vorabpauschale-of-2025 booked in
2026-01).

### 2.2 Günstigerprüfung trigger — retiree with low marginal rate

Source: § 32d Abs. 6 EStG;
[Haufe — Einkünfte aus Kapitalvermögen 12.5 Günstigerprüfung](https://www.haufe.de/id/beitrag/einkuenfte-aus-kapitalvermoegen-125-guenstigerpruefung-HI9285932.html);
[BFH VIII R 14/13](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE201510209/) — Sparer-Pauschbetrag survives the election.

Single retiree R. in 2026, BY-resident (KiSt 8 %), Catholic.
Period income:
- Statutory pension (Rente): € 22 000 — but only 84 %
  Besteuerungsanteil per § 22 EStG ⇒ € 18 480 PIT-taxable.
- §20 income: dividends € 2 800, bond interest € 1 600 →
  combined € 4 400.
- No § 20 Abs. 2 gains.

**Path A — default Abgeltungsteuer:**
```
§20 base after Sparer-Pauschbetrag = 4 400 − 1 000 = 3 400
Abgeltungsteuer with k = 0.08:
  e / (4 + 0.08) = 3 400 / 4.08    =  833.33
SolZ on Abgst (5.5 %):
  833.33 × 0.055                   =   45.83
KiSt (8 % on Abgst):
  833.33 × 0.08                    =   66.67
─────────────────────────────────────────────
§20 total period liability         =  945.83
PIT on pension (Grundtabelle 2026, ~10 % effective)
                                  ≈  900    (illustrative)
Grand total                        ≈ 1 845.83
```

**Path B — Günstigerprüfung elected:**
```
§20 base after Sparer-Pauschbetrag = 3 400  (Pauschbetrag survives)
Joined into PIT base:
  Total taxable income = 18 480 + 3 400 = 21 880
Apply §32a EStG schedule (2026)
  zvE 21 880 → ESt ≈ 2 000  (illustrative — full ladder)
SolZ on PIT (no Freigrenze breach for retiree) ≈ 0
KiSt 8 % on PIT ≈ 160
─────────────────────────────────────────────
Grand total                        ≈ 2 160
```

Günstigerprüfung **does NOT help here** (Path A is better by
~€ 314). The Finanzamt rejects the election ex officio and
keeps the flat regime. Try a lower-income retiree:
pension € 12 000 (taxable € 10 080), same §20: marginal rate
0 % (below Grundfreibetrag); Path A still costs € 945.83;
Path B costs ~€ 0 on PIT + ~€ 0 SolZ + KiSt on tiny PIT;
**Günstigerprüfung saves the full €945**.

The Günstigerprüfung mechanism in the provider:
1. Compute Path A (the standalone §20 component).
2. Surface Path B's input — the `:de-§20-base` after
   Sparer-Pauschbetrag — via
   `:jurisdiction-specific-codes :pit-base-additions`
   (mirrors the existing CGT provider's §17 / §23 fold).
3. The CONSUMER (or a thin combined-§20 helper, §5) calls
   the PIT provider with and without the §20 fold and picks
   the cheaper outcome. The investment-income provider does
   NOT do the Better-Of test itself — that requires running
   the PIT provider, which is an orchestration concern, not a
   single-provider responsibility.

The existing CGT provider already takes this approach for §17 /
§23 / Günstigerprüfung; the investment-income provider
**mirrors** the convention.

---

## §3. ADR-101 substrate-fit assessment

### 3.1 Closed `period-tax-kinds` enum — no extension needed

`kontor.period-tax-provider/period-tax-kinds` (period_tax_provider.clj:44-60)
is an eight-value closed set with `:capital-gains-tax` as one
member. § 20 EStG income + § 20 EStG gains form ONE
Abgeltungsteuer return ("Anlage KAP") — the statute does not
separate them. Therefore the investment-income provider emits
components of `:kind :capital-gains-tax`, **the same kind the
DE CGT provider emits.** The consumer distinguishes the two
via `:provider-id` and via `:line-items` content.

Alternative: add `:investment-income-tax` to the enum.
**Rejected** — that would (a) violate the closed-enum
ADR-discipline (note 101 §D6: a new enum value requires an
ADR addendum showing the cross-jurisdiction case), (b)
mis-model the DE regime (the return IS Anlage KAP and is
ONE return), (c) create a coordination headache (a single
period-tax-facts call returns multiple `:kind`s mixing apples
and oranges). The `:line-items` mechanism is exactly what
sub-components inside one tax type are for.

The same coordination question arises in FR (PFU on
dividends + interest + plus-values mobilières — one regime),
US (qualified-dividend rate + LT-capital-gain rate — both
within the Schedule-D / Form-1040 income tax), JP (the
20.315 % combined rate on dividends + gains in the separate
declaration). Every jurisdiction surveyed treats the
investment-income + investment-gains as ONE tax. The closed
`:capital-gains-tax` kind is correct.

### 3.2 The base selector — σ_E over `Income:Dividends` / `Income:Interest`

The `kontor.book/distribute-dividend!` verb on the corporate
side does NOT book a shareholder posting (note 107 §2.6 — the
corp's payment debits Dividends Payable, credits Bank; the
shareholder's books receive the funds via `receive!` and
credit `Income:Dividends`). On the shareholder side, the
Income:Dividends account is the σ_E base the provider
marginalizes over the period.

The substrate already has `kontor.report/marginalize`
(report.clj:227-251) — the quotient epimorphism σ_E — which
groups postings by an axis and sums per class. The provider's
base-selector calls:

```
(report/marginalize postings :account-code
                    {:sign :inflow :commodity :EUR})
```

Then sums the entries whose `:account-code` matches the
configured §20-income account-code prefix (the consumer's
chart of accounts choice — convention: `Income:Dividends*`,
`Income:Interest*`, `Income:Fund-Distributions*`,
`Income:Royalties*`; or the `:posting-dimension` axis
`:de-§20-category` if the consumer wires it). This is the
**SAME shape** as the existing PIT provider's
`gross-income` (personal_income_tax.clj:37-46), which
marginalizes by `:account-type` to get total income. The
investment-income provider just uses a narrower predicate.

**No kernel substrate stress.** The
`:account-code`-prefix engine has been substrate-stable since
ADR-067-era and is the conventional plug-point for
per-jurisdiction account selection.

### 3.3 Statute encoding — five new `:parameter`s, ONE provision

Per ADR-101 / note 105 / note 119, the rates + thresholds go
into `:parameter` + `:parameter-value`, the surtax is a
`:provision`. The existing `cgt-statute.clj` ships
`DE.EStG.§20.flat-rate` and `DE-SolZG-§4-on-§20` already;
the investment-income statute adds:

| Parameter code | Value (2026) | Effective from | Citation |
|---|---|---|---|
| `DE.EStG.§20.sparer-pauschbetrag.single` | 1 000 € | 2023-01-01 | § 20 Abs. 9 S. 1 EStG (Zinsanpassungsgesetz, BGBl. I 2022 Nr. 384) |
| `DE.EStG.§20.sparer-pauschbetrag.joint` | 2 000 € | 2023-01-01 | § 20 Abs. 9 S. 2 EStG (same) |
| `DE.EStG.§20.teileinkünfte-inclusion-rate` | 0.60 | 2009-01-01 | § 3 Nr. 40 lit. d EStG + § 3c Abs. 2 EStG (cross-references the existing `DE.EStG.§17.inclusion-rate` — separate code to keep the per-statute referenceability the substrate convention preserves) |
| `DE.KiSt.rate.by-bw` | 0.08 | 1995-01-01 | KiStG BY Art. 22 / KiStG BW § 5 — 8 % rate for Bavaria + Baden-Württemberg |
| `DE.KiSt.rate.other` | 0.09 | 1995-01-01 | KiStG der übrigen Länder — 9 % standard rate |

Pre-2023 historical row for the Sparer-Pauschbetrag (so
bitemporal queries against pre-2023 periods return the
correct number — same pattern as the existing
`DE.EStG.§23.freigrenze` pre-2024 row in `cgt-statute.clj`):

| Parameter | Value | Effective | Citation |
|---|---|---|---|
| `DE.EStG.§20.sparer-pauschbetrag.single` | 801 € | 2009-01-01 ⇒ 2023-01-01 | § 20 Abs. 9 EStG (Unternehmenssteuerreform 2008) |
| `DE.EStG.§20.sparer-pauschbetrag.joint` | 1 602 € | 2009-01-01 ⇒ 2023-01-01 | same |

ONE new provision — the **KiSt-on-Abgeltungsteuer** surtax —
sibling of the existing `DE-SolZG-§4-on-§20`:

```edn
{:provision/code            "DE-KiStG-on-§20"
 :provision/jurisdiction    :de
 :provision/concept         [:tax-concept/code :surtax]
 :provision/title           "KiStG — Kirchensteuer 8/9 % auf §20 Abgeltungsteuer"
 :provision/citation        "https://www.gesetze-im-internet.de/estg/__32d.html (formula); KiStG der Länder (rates)"
 :provision/effective-from  #inst "1995-01-01"
 :provision/priority        110  ;; after Soli (Soli at 100), so the running tax for KiSt does NOT include Soli
 :provision/condition       (pr-str [:eq :component :de-§20-income])
 :provision/consequence     (pr-str {:op :surtax
                                     :code :kist-on-§20
                                     :label "Kirchensteuer auf §20 Abgeltungsteuer"
                                     :amount-from :compute-fn
                                     :fn :de-kist-on-abgeltungsteuer})}
```

The compute-fn `:de-kist-on-abgeltungsteuer` reads
`(get-in ctx [:tax-unit :church-tax-rate])` (a BigDecimal
0 / 0.08 / 0.09) and multiplies it by the running gross
Abgeltungsteuer **before** Soli (priority < 100 below means
SolZ-on-Abgst is computed FIRST and KiSt is a separate
parallel surtax on the same base, NOT compounded). Cross-
check the existing Soli compute-fn shape
(`cgt_provider.clj:170-175`): the same late-bound
`(:running ctx)` pattern, fresh per scope.

The § 32d Abs. 1 formula's church-tax-aware
**Sonderausgaben-effect** — the (e − 4q) / (4 + k) form —
mathematically expresses "Abgst is reduced by 25 % of the
church-tax-on-Abgst because the church tax itself is a
Sonderausgabe." For implementation: when k > 0, the
Abgeltungsteuer-side parameter `DE.EStG.§20.flat-rate` (0.25)
becomes an **effective rate** of 1 / (4 + k). The cleanest
implementation: the provider's apply-schedule call uses
`(ts/flat (/ 1M (+ 4M k)))` when k > 0, otherwise
`(ts/flat 0.25M)`. This is provider-internal — the parameter
remains 0.25; the formula adjustment is a function of the
`:tax-unit :church-tax-rate` flag the consumer supplies.
**No new substrate operator.**

### 3.4 The `:tax-unit` slot — one new flag

The existing CGT provider reads
`:tax-unit :abgeltungsteuer-elect-marginal?` and
`:tax-unit :ccpc?`-style flags (the latter via the CIT
provider). The investment-income provider needs:

```
:tax-unit
{:church-tax-rate                  0M | 0.08M | 0.09M
 :abgeltungsteuer-elect-marginal?  Boolean  ;; shared with CGT provider
 :teileinkünfte-elected-issuers   #{<partner-ref>…}  ;; § 32d Abs. 2 Nr. 3 elective
 :filing-status                    :single | :joint  ;; selects Sparer-Pauschbetrag
}
```

The `:church-tax-rate` is **canonical numeric**, not a
keyword — keep it as a rate so the provider does
arithmetic, not a lookup. The consumer fills it (the KiStAM
result from the bank, or the taxpayer's annual declaration);
the substrate stays opaque to KiStAM mechanics.

The `:teileinkünfte-elected-issuers` set is a per-issuer
flag. The provider walks the period's dividend postings,
inspects each posting's `:transaction/partner` (the issuing
corp, the same field the corp-side `declare-dividend!` set),
and routes elected-issuer dividends into a separate base
(`:de-§20-teileinkünfte-pit-fold`) that surfaces in
`:pit-base-additions` as 60 % of the dividend. Per-partner
discrimination is **already supported by the substrate** —
the partner is stamped on the transaction; `marginalize` by a
partner-classifying predicate is the natural σ_E. **No
substrate stress.**

### 3.5 Data-gap list — NONE on the substrate

This is the rare provider where the substrate-fit assessment
yields **zero** kernel extensions. Everything needed is
provider-internal logic + parameter data + the existing
`:tax-unit` opaque-map slot + the existing
`:jurisdiction-specific-codes` opaque-map slot.

Five additions, all in the **statute data + provider record**
layer:

1. The five new `:parameter` rows in §3.3 + their
   `:parameter-value` rows.
2. The new `:provision` (KiSt-on-§20).
3. The new compute-fn (`:de-kist-on-abgeltungsteuer`),
   registered via `statute/register-compute-fn!`.
4. The new provider record `DEInvestmentIncomeTaxProvider`
   + constructor `de-investment-income-provider`.
5. The combined-§20 helper bridge fn (§5 option c) at the
   l10n-de level — sibling of `cgt-§8b-addback-input`.

**Zero kernel substrate changes.** The substrate Phase 3 / 4
of the tax-completion program (notes 102 / 104) is paying off
exactly as predicted: the per-jurisdiction provider is a
slim wrapper over the statute substrate + the verb facade +
ADR-101.

---

## §4. Proposed provider shape — `de-investment-income-provider`

### 4.1 Shape sketch

```clojure
;; modules/l10n-de/src/kontor/l10n_de/investment_income_provider.clj
;; NOTE: this is a sketch for the implementer; no code committed here.

(defrecord DEInvestmentIncomeTaxProvider
           [id authority commodity statute account-prefix]
  ptp/PeriodTaxProvider
  (provider-id [_] id)
  (period-tax-facts [_ {:keys [entity period inputs] :as ctx}]
    (let [db          (or (:db ctx) (throw ...))
          as-of       (or (:as-of ctx) (:to period))
          ;; --- σ_E base selector: marginalize §20 income postings
          postings    (report/report-postings
                       db (cond-> {:from (:from period) :to (:to period)}
                            entity (assoc :entity entity)))
          ;; partition into §20 sub-categories by account-code prefix
          by-code     (report/marginalize postings :account-code
                                          {:sign :inflow :commodity commodity})
          dividends   (sum-codes by-code (:dividends   account-prefix))
          interest    (sum-codes by-code (:interest    account-prefix))
          fund-dist   (sum-codes by-code (:fund-dist   account-prefix))
          royalties   (sum-codes by-code (:royalties   account-prefix))
          ;; --- partition dividends by §32d Abs. 2 Nr. 3 elective scope
          tu          (:tax-unit inputs)
          elected?    (-> tu :teileinkünfte-elected-issuers (or #{}))
          divs-flat   (sum-non-elected-divs dividends elected?)
          divs-elect  (sum-elected-divs     dividends elected?)
          ;; --- Sparer-Pauschbetrag (single OR joint)
          sp-code     (case (:filing-status tu) :joint "DE.EStG.§20.sparer-pauschbetrag.joint"
                                                #_else "DE.EStG.§20.sparer-pauschbetrag.single")
          sp          (statute/parameter-value-at db sp-code as-of)
          ;; the §20-flat (non-elected) base
          gross-flat  (+ (:amount divs-flat) (:amount interest)
                         (:amount fund-dist) (:amount royalties))
          ;; carry-in from §20 Abs. 6 carryforward — DE has §20-other
          ;; (dividends/interest/funds) + §20-stock buckets. Income side
          ;; only consumes §20-other carry-in (per §1.6 bucket walls).
          carry-in    (-> inputs :capital-loss-carryforward :de-§20-other (or 0M))
          taxable-flat (max 0M (- gross-flat sp carry-in))
          ;; --- the Teileinkünfte slice — 60 % into PIT base
          incl-rate   (statute/parameter-value-at
                       db "DE.EStG.§20.teileinkünfte-inclusion-rate" as-of)
          teileinkünfte (* (:amount divs-elect) incl-rate)
          ;; --- Günstigerprüfung — suppress §20 standalone, fold into PIT
          günstig?    (boolean (:abgeltungsteuer-elect-marginal? tu))
          components
          (cond
            ;; Günstigerprüfung: §20 income (after Sparer-Pauschbetrag)
            ;; folds at 100 % into PIT base; no standalone Abgeltungsteuer.
            ;; Existing CGT provider mirrors this for the gains side.
            günstig?
            [(§20-pit-fold-component opts taxable-flat teileinkünfte)]

            ;; Standard path: §20-flat component + Teileinkünfte fold
            ;; (the latter as :pit-base-additions for the PIT provider).
            :else
            (let [k         (or (:church-tax-rate tu) 0M)
                  rate      (/ 1M (+ 4M k))    ;; § 32d Abs. 1 formula
                  schedule  (ts/flat rate)
                  gross-tax (max 0M (ts/apply-schedule schedule taxable-flat))
                  scoped    (assoc ctx :component :de-§20-income
                                       :db db :as-of as-of
                                       :tax-unit tu)
                  {tax-items :tax-items}
                  (statute/apply-provisions
                   db {:concept :surtax :jurisdiction :de :as-of as-of} scoped)
                  {:keys [liability resolved]}
                  (ts/apply-adjustments gross-tax tax-items scoped)
                  prepaid   (or (:de-kapest-prepaid inputs) 0M)]
              [(§20-income-component opts ctx
                                     gross-flat sp taxable-flat
                                     gross-tax resolved liability
                                     prepaid)
               ;; If Teileinkünfte election present: fold 60 % into PIT
               (when (pos? teileinkünfte)
                 (§20-teileinkünfte-fold-component opts teileinkünfte))]))]
      (ptp/tax-return-facts
       {:entity entity :period period
        :jurisdiction {:country :de :authority authority}
        :functional-commodity commodity
        :components (->> components (remove nil?) vec)}))))

(defn de-investment-income-provider
  "Build a DE investment-income (§20 EStG Abgeltungsteuer) provider.

   Required:
     :account-prefix — {:dividends [\"Income:Dividends\"]
                        :interest  [\"Income:Interest\"]
                        :fund-dist [\"Income:Fund-Distributions\"]
                        :royalties [\"Income:Royalties\"]}
       — the chart-of-accounts prefixes the σ_E base selector
       reads. Consumer's chart-of-accounts choice; the
       conventional defaults are exposed.

   Optional:
     :id        — provider-id (default :de-investment-income)
     :commodity — functional commodity (default :EUR)"
  [{:keys [account-prefix id commodity]
    :or   {id        :de-investment-income
           commodity :EUR
           account-prefix
           {:dividends ["Income:Dividends"]
            :interest  ["Income:Interest"]
            :fund-dist ["Income:Fund-Distributions"]
            :royalties ["Income:Royalties"]}}}]
  (->DEInvestmentIncomeTaxProvider
   id :de-finanzamt commodity
   "§ 20 + § 32d EStG + § 4 SolZG + KiStG"
   account-prefix))
```

### 4.2 `:inputs` shape

```clojure
{:capital-loss-carryforward
 {:de-§20-other  <BigDecimal>     ;; available §20-other loss pool
  :de-§20-stock  <BigDecimal>}    ;; (read by the CGT provider only)
 :de-kapest-prepaid     <BigDecimal>  ;; bank-withheld KESt + SolZ + KiSt
 :foreign-tax-credit    <BigDecimal>  ;; pre-summed DBA-credited q  (v1: pass-through)
 :tax-unit
 {:church-tax-rate                 0M | 0.08M | 0.09M
  :filing-status                   :single | :joint
  :abgeltungsteuer-elect-marginal? Boolean
  :teileinkünfte-elected-issuers   #{[:partner/code …] …}}}
```

### 4.3 Output component shape

ONE primary component (`:kind :capital-gains-tax`,
`:line-items` enumerates the §20-income breakdown), OPTIONALLY
a Teileinkünfte-fold component (`:jurisdiction-specific-codes
:pit-base-additions [<60 %-of-elected-div>]`) for the
consumer's PIT provider to absorb.

Component sketch (the §20-flat path):

```clojure
{:kind            :capital-gains-tax
 :authority       :de-finanzamt
 :base            (money/money taxable-flat :EUR)
 :schedule        schedule  ;; ts/flat 0.25M or ts/flat (1/(4+k))
 :gross-liability (money/money gross-tax :EUR)
 :surtaxes        [...resolved soli + kist...]
 :liability       (money/money liability :EUR)
 :prepaid         (money/money prepaid   :EUR)
 :regime          :abgeltungsteuer
 :line-items
 [{:line :gross-dividends :label "Dividenden"                :value (money/money divs-flat-amt :EUR)}
  {:line :gross-interest  :label "Zinsen"                    :value (money/money interest-amt :EUR)}
  {:line :gross-funds     :label "Fondsausschüttungen + Vorabpauschale" :value (money/money fund-dist-amt :EUR)}
  {:line :gross-royalties :label "Sonstige Kapitalerträge"    :value (money/money royalties-amt :EUR)}
  {:line :sparer-pauschbetrag :label "Sparer-Pauschbetrag (§20 Abs. 9 EStG)" :value (money/money (- sp) :EUR)}
  {:line :de-§20-base     :label "Steuerpflichtige §20-Einkünfte"  :value (money/money taxable-flat :EUR)}
  {:line :de-§20-tax      :label "Abgeltungsteuer (§32d Abs. 1 EStG)" :value (money/money gross-tax :EUR)}
  {:line :soli-on-§20     :label "Solidaritätszuschlag auf Abgeltungsteuer (5.5 %)" :value (money/money soli-amt :EUR)}
  {:line :kist-on-§20     :label (str "Kirchensteuer auf Abgeltungsteuer (" (* 100 k) " %)") :value (money/money kist-amt :EUR)}]
 :jurisdiction-specific-codes
 {:lane :de-§20-income
  :loss-bucket-contribution {:de-§20-other (if (neg? gross-flat) (- gross-flat) 0M)}}}
```

The `:loss-bucket-contribution` slot lets the consumer **carry
forward** any net §20-income LOSS for the period (rare but
possible — a Stückzinsen-heavy bond purchase mid-year can flip
the net negative). The bucket key matches the CGT provider's
carryforward convention.

---

## §5. Integration with the existing DE CGT provider — the Sparer-Pauschbetrag question

### 5.1 The wrinkle

§ 20 Abs. 9 EStG grants ONE Sparer-Pauschbetrag per filer per
year, applied against the COMBINED §20 base (income + gains).
Right now the existing DE CGT provider does NOT deduct the
Sparer-Pauschbetrag — it doesn't need to, because the
provider operates on disposal records and `:disposal/basis-
amount` already nets the taxable gain. But when **both** the
CGT provider AND the investment-income provider run on the
same period, the Sparer-Pauschbetrag becomes a shared resource.

Three options:

### 5.2 Option (a) — both providers take it independently (REJECTED)

Each provider deducts the full €1 000 from its own slice. The
combined deduction is €2 000 — DOUBLE the statutory grant.
**Wrong.** Rejects on first cross-check against any
intermediate-complexity test case.

### 5.3 Option (b) — wire it via `:inputs :sparer-pauschbetrag-consumed`

The CGT provider already runs, sets
`:inputs :sparer-pauschbetrag-consumed <amount>` (the amount
it consumed against §20 gains), and the investment-income
provider deducts only the residual. **Workable but order-
sensitive** — whichever provider runs first claims the
allowance. Asymmetric outcome under election scenarios.
**Rejected** for ergonomic reasons; the order-dependency is
the kind of footgun ADR-099 specifically warns against.

### 5.4 Option (c) — combined-§20 orchestrator helper (RECOMMENDED)

A new thin helper at the l10n-de level, sibling of
`cgt-§8b-addback-input` (cgt_provider.clj:652-669):

```clojure
;; modules/l10n-de/src/kontor/l10n_de/de_§20.clj  (new ns)
(defn combined-§20-facts
  "Run BOTH the DE CGT provider (individual kind, §20 lanes
   only) AND the DE investment-income provider, then COMBINE
   their §20 components into ONE consolidated component with
   the Sparer-Pauschbetrag deducted ONCE from the combined base.

   The §17 + §23 components from the CGT provider pass through
   UNCHANGED (they fold into PIT base, no SP interaction). The
   §8b corporate components pass through unchanged (corporate
   side, no SP).

   Returns a `TaxReturnFacts` whose components reflect ADR-101
   correctness — one Sparer-Pauschbetrag, one Abgeltungsteuer
   rate, the §20 stock-gain bucket + §20-income bucket netted
   per §20 Abs. 6 walls."
  [{:keys [conn entity period inputs cgt-provider income-provider]}]
  (let [cgt-facts (ptp/period-tax-facts cgt-provider {:db conn :entity entity
                                                       :period period :inputs inputs})
        inc-facts (ptp/period-tax-facts income-provider {:db conn :entity entity
                                                          :period period :inputs inputs})
        §20-gain-base (extract-§20-base cgt-facts)         ;; nets stock+other gain buckets
        §20-inc-base  (extract-§20-base-pre-sp inc-facts)   ;; before SP deduction
        combined-base (+ §20-gain-base §20-inc-base)
        sp (statute/parameter-value-at conn
                                       (sp-code (:filing-status (:tax-unit inputs)))
                                       (or (:as-of inputs) (:to period)))
        taxable (max 0M (- combined-base sp))
        ;; … recompute the §20 component with the correct base …
        ]
    ;; Returns merged facts: §17 / §23 / §8b unchanged; §20 consolidated
    ...))
```

**Properties:**
- The CGT provider and the investment-income provider remain
  **single-responsibility** and standalone-runnable (each can
  still be called in isolation when the consumer has only
  gains-side OR only income-side activity).
- The combined helper is OPT-IN: a consumer who only has §20
  income (no disposals in the period) calls
  `de-investment-income-provider` directly and the SP is taken
  there. A consumer with both flows uses
  `combined-§20-facts` and gets the right answer.
- **Symmetric** with the existing CGT-CIT integration pattern
  (note 136 P0-3) — the consumer is the wiring point, the
  providers are the policy points, the helper bridges them.
- **No substrate change.** The helper is a 30-line
  consumer-level function; it lives next to the provider
  records.

### 5.5 Subtleties the combined helper must handle

1. **Günstigerprüfung is shared** — `:tax-unit
   :abgeltungsteuer-elect-marginal?` must be applied
   identically to both providers (or to the combined base
   when the helper runs). The election is all-or-nothing
   across the §20 universe; the helper enforces.
2. **Stock-vs-other loss-bucket walls** — `:de-§20-stock`
   losses can only consume stock GAINS, not income. The
   helper preserves the per-bucket carryforward consumption
   logic the CGT provider already runs; the income provider's
   contribution only affects the §20-other bucket.
3. **Soli + KiSt apply to the consolidated Abgst** — once;
   the helper recomputes the surtaxes against the combined
   gross-liability (not the sum of the two providers'
   individual surtaxes, which would correctly equal it since
   surtax is linear in the base, but the cleanest accounting
   is to compute once).
4. **Prepaid (bank withholding)** is opaque — the consumer
   passes the aggregate `:de-kapest-prepaid` from the bank's
   year-end statement; the helper attaches it to the combined
   component, not to one or the other.

### 5.6 Provider standalone path — still works

When a consumer has ONLY income (no disposals):
```clojure
(def inc-prov (inv/de-investment-income-provider {:account-prefix ...}))
(ptp/period-tax-facts inc-prov {:db conn :entity entity :period period
                                 :inputs {:tax-unit {:church-tax-rate 0.09M
                                                     :filing-status :single}
                                          :capital-loss-carryforward
                                          {:de-§20-other 0M}}})
;; ⇒ TaxReturnFacts with one :de-§20-income component,
;;   SP deducted in full by the provider itself.
```

When ONLY disposals:
```clojure
(def cgt-prov (cgt/de-personal-cgt-provider {:source ds-source}))
;; existing behavior — unchanged.
```

When BOTH:
```clojure
(def combined (de-§20/combined-§20-facts
               {:conn conn :entity entity :period period :inputs inputs
                :cgt-provider     cgt-prov
                :income-provider  inc-prov}))
;; ⇒ TaxReturnFacts with: §8b (if corp also), §17, §23, ONE consolidated §20
```

This is the pattern. The provider implementer writes the
provider; the bridge fn ships in the same commit.

---

## §6. Sources

### DE statutes (gesetze-im-internet.de — public domain)
- [§ 20 EStG — Einkünfte aus Kapitalvermögen](https://www.gesetze-im-internet.de/estg/__20.html) — categories (Abs. 1), §20 Abs. 6 loss buckets, §20 Abs. 9 Sparer-Pauschbetrag.
- [§ 32d EStG — Gesonderter Steuertarif für Einkünfte aus Kapitalvermögen](https://www.gesetze-im-internet.de/estg/__32d.html) — Abs. 1 the 25 % flat + church-tax formula; Abs. 2 Nr. 3 Teileinkünfte-Option; Abs. 5 DBA-Quellensteuer-Anrechnung; Abs. 6 Günstigerprüfung.
- [§ 3 Nr. 40 EStG — Teileinkünfteverfahren-Befreiung](https://www.gesetze-im-internet.de/estg/__3.html) — 40 % tax-free of dividends in business-context / opt-in.
- [§ 3c Abs. 2 EStG — Abzugsbeschränkung](https://www.gesetze-im-internet.de/estg/__3c.html) — symmetric 40 % deduction limit on Teileinkünfte-related Werbungskosten.
- [§ 4 SolZG — Bemessung des Solidaritätszuschlags](https://www.gesetze-im-internet.de/solzg_1995/__4.html) — 5.5 % surtax with NO Freigrenze on the Abgeltungsteuer-side.
- [§ 18 InvStG — Vorabpauschale](https://www.gesetze-im-internet.de/invstg_2018/__18.html) — accumulating-fund deemed distribution.
- [§ 20 InvStG — Teilfreistellungen](https://www.gesetze-im-internet.de/invstg_2018/__20.html) — 30 % / 15 % / 60 % carve-out by fund type.

### BMF (Federal Ministry of Finance)
- [BMF Schreiben 2025-05-14 — Einzelfragen zur Abgeltungsteuer](https://www.bundesfinanzministerium.de/Content/DE/Downloads/BMF_Schreiben/Steuerarten/Abgeltungsteuer/2025-05-14-einzelfragen-zur-abgeltungsteuer.pdf) — authoritative 137-page current guidance, including the JStG 2024 §20 Abs. 6 Aufhebung (Rn. 117a Nießbrauch, transitional rules to 2026-01-01 for banks).
- [BZSt — Kirchensteuer auf Abgeltungsteuer (KiStAM)](https://www.bzst.de/DE/Unternehmen/Kapitalertraege/KirchensteuerAbgeltungsteuer/kirchensteuerabgeltungsteuer_node.html) — automatic withholding via KiStAM; 8 % / 9 % rates per Bundesland.
- [BZSt — Anrechenbare ausländische Quellensteuer 2025](https://www.bzst.de/SharedDocs/Downloads/DE/EU_OECD/anrechenbare_ausl_quellensteuer_2025.pdf) — annual table of DBA-cap creditable foreign WHT for § 32d Abs. 5.

### BFH constitutional challenges
- [BFH 2020-11-17 VIII R 11/18 — Vorlagebeschluss BVerfG (Aktienverlust-Verrechnungsbeschränkung)](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE202110103/) — case at BVerfG as **2 BvL 3/21**, decision expected 2026 on § 20 Abs. 6 S. 4 stock-bucket wall.
- [BFH 2015-05-12 VIII R 14/13](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE201510209/) — Sparer-Pauschbetrag survives Günstigerprüfung election.
- [BFH 2024-06-07 VIII B 113/23 (AdV) — Termingeschäft-Verlust-Beschränkung verfassungswidrig](https://www.bundesfinanzhof.de/en/entscheidungen/entscheidungen-online/decision-detail/STRE202410113/) — informed the JStG 2024 §20 Abs. 6 S. 5/6 retroactive Aufhebung.

### Practitioner commentary (Beck-tier — Haufe, NWB, EY, PwC)
- [Haufe — Einkünfte aus Kapitalvermögen / Sparer-Pauschbetrag](https://www.haufe.de/id/beitrag/sparer-pauschbetrag-HI1637591.html) — €1 000 / €2 000 from 2023 verified.
- [Haufe — Einkünfte aus Kapitalvermögen / 12.5 Günstigerprüfung](https://www.haufe.de/id/beitrag/einkuenfte-aus-kapitalvermoegen-125-guenstigerpruefung-HI9285932.html) — application mechanics + Sparer-Pauschbetrag survival.
- [Haufe — Einkünfte aus Kapitalvermögen / 11.1.4 Verluste aus Termingeschäften](https://www.haufe.de/id/beitrag/einkuenfte-aus-kapitalvermoegen-1114-verluste-aus-termingeschaeften-HI9285888.html) — JStG 2024 Aufhebung detail.
- [Haufe — Teileinkünfteverfahren](https://www.haufe.de/id/beitrag/teileinkuenfteverfahren-HI1673415.html) — 60 % inclusion + § 3c Abs. 2 Werbungskosten-Beschränkung.
- [EY — Anwendungsfragen zur Abgeltungsteuer (BMF 2025-05-14 Synopsis)](https://www.ey.com/de_de/technical/steuernachrichten/anwendungsfragen-zur-abgeltungsteuer) — Big-4 read of the BMF Schreiben.
- [Flick Gocke Schaumburg — Update zum JStG 2024 (Termingeschäfte/Forderungsausfälle)](https://www.fgs.de/en/news-and-insights/blog/detail/update-zum-jahressteuergesetz-2024-jstg-2024-rueckwirkender-entfall-der-verlustverrechnungsbeschraenkung-fuer-termingeschaefte-und-forderungsausfaelle-im-privatvermoegen) — § 52 Abs. 28 EStG retroactive scope, open-cases coverage.
- [PwC — BFH-Vorlage zum BVerfG: Aktienverlustverrechnungsbeschränkung](https://blogs.pwc.de/en/steuern-und-recht/article/228527/update-vorlage-an-das-bundesverfassungsgericht-der-bfh-haelt-die-verlustverrechnungsbeschraenkung-fuer-aktienveraeusserungsverluste-fuer-verfassungswidrig/) — current procedural status.
- [JUHN — Dividenden + Doppelbesteuerungsabkommen](https://www.juhn.com/fachwissen/internationales-steuerrecht/dividenden-und-doppelbesteuerungsabkommen/) — 15 %-DBA-cap for foreign dividends.
- [Finanzwissen — Aktienverlusttopf vs. Allgemeiner Verlusttopf](https://finanzwissen.de/aktien/steuern/verlusttoepfe/) — bank-level loss-bucket nomenclature confirming the dividends/interest → general bucket assignment.
- [Wikipedia — Vorabpauschale](https://de.wikipedia.org/wiki/Vorabpauschale) — § 18 InvStG mechanics, 2026 Basiszins 3.20 %.
- [extraetf — Vorabpauschale](https://extraetf.com/de/wissen/vorabpauschale) — Teilfreistellung table by fund type.
- [extraetf — Soli auf Kapitalerträge 2026](https://extraetf.com/de/wissen/solidaritaetszuschlag) — confirms NO Freigrenze on Soli-on-Abgst (key difference from PIT-Soli).

### kontor substrate cited (file:line)
- `src/kontor/period_tax_provider.clj:44-60` — closed `period-tax-kinds` enum; `:capital-gains-tax` covers §20 income + gains together.
- `src/kontor/period_tax_provider.clj:67-100` — `TaxReturnFacts` + component map.
- `src/kontor/personal_income_tax.clj:37-46` — `gross-income` σ_E base-selector pattern the investment-income provider mirrors.
- `src/kontor/personal_income_tax.clj:65-118` — adjustment-fold of credits + surtaxes; the same `apply-adjustments` pattern.
- `src/kontor/report.clj:227-251` — `marginalize` (σ_E quotient epimorphism), the substrate kernel for the base selector.
- `src/kontor/report.clj:270-276` — `:account-codes` engine for the chart-of-accounts-prefix matcher.
- `src/kontor/tax_schedule.clj:142-235` — `apply-base-transform` + `apply-adjustments` (Soli + KiSt rides on this).
- `src/kontor/statute.clj:571-614` — `:participation-exemption` + `:loss-bucket` concept catalogue entries.
- `src/kontor/book.clj:296-330` — `declare-dividend!` + `distribute-dividend!` corp-side verbs; the shareholder books `Income:Dividends` separately via `receive!`.
- `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:170-184` — `de-soli-on-abgeltungsteuer` compute-fn pattern the KiSt compute-fn mirrors.
- `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:507-525` — `§20-pit-fold-component` (Günstigerprüfung path the investment-income provider must mirror).
- `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:652-669` — `cgt-§8b-addback-input` bridge fn shape the combined-§20 helper (§5) mirrors.
- `modules/l10n-de/src/kontor/l10n_de/cgt_statute.clj:88-93` — `DE.EStG.§20.flat-rate` parameter (existing — reused unchanged).
- `modules/l10n-de/src/kontor/l10n_de/cgt_statute.clj:200-215` — `DE-SolZG-§4-on-§20` provision (existing — the new `DE-KiStG-on-§20` is its sibling).
- `modules/l10n-de/src/kontor/l10n_de/period_tax_provider.clj:1-79` — `de-income-tax-provider` (PIT); Günstigerprüfung folds INTO this when elected.
- `doc/research/113-de-cgt-fit.md` — the original DE CGT fit; this note is its companion on the §20-income side.
- `doc/research/136-de-cgt-baseline-review.md` (P0-3) — the CGT-CIT integration pattern the §20-combined helper mirrors.
- `doc/research/104-tax-completion-individual-to-corporation.md` §10 — the per-jurisdiction pilot rhythm; investment-income is the next rung.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §2.6 — the corp→shareholder dividend loop.

---

End of note 147.

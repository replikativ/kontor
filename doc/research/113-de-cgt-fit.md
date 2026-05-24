---
date: 2026-05-24
title: 113 — DE capital-gains tax — substrate fit against the proposed `:disposal` schema
audience: maintainer + the Phase 3 CGT-provider implementer
status: research-before for the Phase 3 `de-cgt-provider`(s); no code; data-gap list at §4
---

# 113 — DE capital-gains tax — `:disposal` schema fit assessment

This note answers ONE question: does the `:disposal` schema sketched in
note 107 §3 carry enough data to drive a faithful DE capital-gains-tax
provider — across the **four** statutory shapes DE uses (§8b KStG, §6b
EStG, §17 EStG, §20 EStG / Abgeltungsteuer, §23 EStG) — or do we need to
extend it?

Bottom line: **the schema needs SIX additive fields and one rewording.**
The substrate's existing `:base-transform :adjustments` cleanly carries
§8b's 95/5 mechanic; the `:holding-period :short/:long` enum DOES NOT
carry DE's per-subject-kind cutoffs and must be replaced by an
`:acquired-on` + per-provision rules. §6b needs a `:rollover-into-asset`
ref and a `§6b-reserve` lifecycle that lives in the GL, not on
`:disposal`. §17 needs an `:ownership-fraction`. §20 needs an
Abgeltungsteuer-flag and a per-category loss bucket. **No kernel
substrate stress** beyond the schema extensions and the §6b reserve
account-shape.

---

## §1. The DE CGT regime — five statutory shapes

DE does not have ONE capital-gains tax. It has five overlapping
provisions, each with different base, rate, holding-period, and
loss-offset rules:

### 1.1 Corporate side — §8b KStG (95% participation exemption)

When a `Kapitalgesellschaft` (GmbH / AG / SE / UG) disposes of shares
in **another Kapitalgesellschaft**, **95% of the gain is exempt** from
KSt + GewSt — but the remaining 5% is a **"non-deductible business
expense"** (§ 8b Abs. 3 KStG; "pauschal als Betriebsausgaben"), which
is added back to taxable income at the full statutory rate (KSt 15% +
GewSt ~7-17.5% + Soli 5.5%). This is the famous "**95% exemption with
a residual 1.5%-effective-rate**" — the exemption is partial, not full.

Threshold (for dividends, not for share disposals): §8b Abs. 4 KStG
denies the exemption for **Streubesitz** holdings — direct holdings of
**< 10%** at the start of the calendar year. For **share disposals**
under §8b Abs. 2, **NO 10% threshold applies** — the 95/5 rule fires
regardless of stake size.

### 1.2 Corporate side — §6b EStG (rollover relief)

Available to both corporations and individuals operating a `Betrieb`.
On disposal of **certain fixed assets** (real estate, buildings,
agricultural land, ships, certain shareholdings up to €500 000), the
realized gain can be **rolled over** into a §6b-Rücklage (reserve)
on the balance sheet — deferring recognition for up to **4 years
(6 years for buildings under construction)**. Within that window, the
reserve is **transferred** to the basis of a qualifying replacement
asset, reducing its depreciable base. If the window expires without
reinvestment, the reserve is dissolved with a **6%-per-year penalty
add-back** to taxable profit ("Strafverzinsung" — § 6b Abs. 7).

### 1.3 Individual side — §17 EStG (qualified shareholding)

When an individual disposes of shares in a `Kapitalgesellschaft` AND
has held a **direct or indirect ≥ 1% stake** within the last 5 years
("**wesentliche Beteiligung**"), the gain is classified as
`Einkünfte aus Gewerbebetrieb` (NOT `Kapitalvermögen`), and the
**Teileinkünfteverfahren** applies: only **60% of the gain is taxable
at the marginal income-tax rate** (§ 32a EStG). Symmetrically, only
60% of acquisition + disposal costs are deductible (§ 3c Abs. 2 EStG).
A **Freibetrag of €9 060** applies, taper-reduced once the gain
exceeds €36 100 (§ 17 Abs. 3 EStG).

### 1.4 Individual side — §20 EStG / Abgeltungsteuer (the 25% flat)

When an individual disposes of securities (shares, bonds, ETFs,
derivatives) NOT meeting the §17 1%-threshold, the gain is
`Einkünfte aus Kapitalvermögen` and is taxed at a **flat 25%** +
Soli 5.5% + church tax (8-9% where applicable) — effective ~26.375%
to ~27.99% (§ 32d EStG). The taxpayer may **opt into marginal-rate
taxation** via the `Günstigerprüfung` (§ 32d Abs. 6) when their
marginal rate is below 25%. Loss-offset is **bucketed**: stock-sale
losses offset only stock-sale gains; other capital losses offset
other capital gains (§ 20 Abs. 6 — the bucket boundary is a hard
statutory wall, narrower than §17's). The annual €20 000 cap on
derivative-loss offset was **abolished retroactively in 2025** for
all open cases.

### 1.5 Individual side — §23 EStG (private speculation)

For non-securities held in private hands: **real estate** sold within
**10 years** of acquisition, OR **other movable property** (gold,
crypto, art, foreign currency) sold within **1 year** of acquisition,
generates a taxable gain at the **marginal income-tax rate** (not the
Abgeltungsteuer flat). Held **longer than the cutoff → tax-free**.
A €1 000-per-year `Freigrenze` applies (NOT a Freibetrag — exceeding
€1 000 by even one euro makes the entire gain taxable). Loss-offset
is again bucketed: §23 losses offset only §23 gains; carry-forward
indefinitely against future §23 gains.

### 1.6 Verlustverrechnung — the loss-offset wall

DE has **three separate loss buckets** that do NOT mix:

- §8b KStG losses (corporate share disposals) — 95% non-deductible
  (the mirror of the 95% exemption) — only 5% effectively reduces
  taxable income.
- §17 losses — offset only §17 gains; carry-forward against future
  §17 gains.
- §20 sub-buckets — stock-sale losses vs other-capital losses, kept
  separate (§ 20 Abs. 6 Sätze 4-5).
- §23 losses — offset only §23 gains; carry-forward.

This mirrors US's "capital-loss-against-capital-gain only" rule but
is **finer-grained** — note 102 §7 named capital-gains tax as a hybrid
because of exactly this. The CGT provider must read the
`:capital-loss-carryforward` `:inputs` slot ADR-099 already defines,
but DE needs FOUR sub-keys, not the US two (`:short` / `:long`).

---

## §2. Worked examples

### 2.1 §8b KStG — GmbH sells a participation

Source: [steuerberatung-neeb.de — § 8b KStG](https://steuerberatung-neeb.de/ausschuettungen-zwischen-koerperschaften-aus-steuerlicher-sicht-steuerfreie-gewinne-im-konzern/),
and the statute at [gesetze-im-internet.de § 8b KStG](https://www.gesetze-im-internet.de/kstg_1977/__8b.html).

Holding-GmbH H acquired 25% of operating-GmbH O in 2020 for €1 000 000.
In 2026 H sells the stake for €5 000 000. Realised gain = €4 000 000.

- **§8b Abs. 2** — 100% of gain (€4 000 000) is exempt from KSt + GewSt.
- **§8b Abs. 3** — 5% of gain (**€200 000**) is added back as
  non-deductible business expense ("**fiktive Betriebsausgaben**").
- Add-back flows to taxable income, taxed at KSt 15% + Soli 5.5% + GewSt
  (assume Hebesatz 400% → ~14% effective GewSt) ≈ 30% × €200 000 = **€60 000
  effective tax** on a €4 000 000 gain (≈ 1.5% effective rate).

The substrate maps this cleanly: the CGT provider emits a single
`:disposal` (kind `:share-sale`, subject-kind `:participation`), then
in the CIT provider's `:base-transform :adjustments :additions` slot
adds `[200000M]` (5% of `:realized-gain-loss` summed across §8b-eligible
disposals for the period). The 95% exemption is **the absence of an
income-side posting**, and the residual is **the add-back at the CIT
layer**. The current substrate does NOT need a new operator — the
adjustment is exactly what `apply-base-transform :adjustments` does
(tax_schedule.clj:154-161).

### 2.2 §23 EStG — individual sells residential property held 8 years

Source: [haufe.de — Zehn-Jahres-Frist § 23 EStG](https://www.haufe.de/steuern/rechtsprechung/zehn-jahres-frist-bei-privaten-veraeusserungsgeschaeften_166_548536.html)
and [gesetze-im-internet.de § 23 EStG](https://www.gesetze-im-internet.de/estg/__23.html).

A. private investor bought a rental apartment 2018-03 for €250 000.
She sells it 2026-08 for €420 000. Holding period: 8 years 5 months →
**inside the 10-year window** → taxable.

- Realised gain = €170 000 (less disposal costs — broker fee €15 000 →
  €155 000 net).
- Classification: `Einkünfte aus privaten Veräußerungsgeschäften`
  (§ 22 Nr. 2 + § 23 EStG).
- The €155 000 is added to her marginal-rate taxable income — at her
  ~42% top bracket plus Soli 5.5% × 42% = **~44.31% effective** →
  tax owed ≈ €68 680.
- Had she waited 1 year 7 months longer → 10-year window cleared →
  €170 000 gain **fully tax-free** (the §23-vs-marginal cliff).

The substrate maps this: `:disposal` with `:kind :real-estate-sale`,
`:subject-kind :real-estate-private`, `:acquired-on 2018-03-15`,
`:effective-date 2026-08-20`, `:proceeds 420 000`, `:basis 250 000`.
The CGT provider classifies it as §23-in-window via a per-jurisdiction
rule `(- effective-date acquired-on) < 10y`, sums all in-window §23
gains for the period, and folds the net into the PIT provider's gross
income via `:inputs :adjustments :additions`. **CANNOT** use the
proposed `:holding-period :short/:long` enum — DE has a 10-year cutoff
for real estate AND a 1-year cutoff for movables AND tax-free-beyond,
which is a **third state** the enum cannot represent without
jurisdiction-specific overloading.

---

## §3. `:disposal` schema fit — provision-by-provision

| DE provision | Schema field that carries it | Adequacy |
|---|---|---|
| **§8b KStG** subject test | `:subject-kind :participation` | **AMBIGUOUS** — the schema's `:participation` does NOT distinguish a `Kapitalgesellschaft` holding (which gets §8b) from a `Personengesellschaft` holding (which does not). Need a `:subject-form :corp/:partnership/:other` discriminator OR the CGT provider reads the partner's `:entity/legal-form` (GLEIF ELF code, ADR-031 already carries it). Recommendation: read from `:entity/legal-form` to avoid schema growth. |
| **§8b KStG** 95/5 split | `:base-transform :adjustments` on the CIT side | **CLEAN** — the 5% add-back IS an adjustment-list addition, exactly what `apply-base-transform :adjustments` is for (tax_schedule.clj:158-161). NOT a credit, NOT a surtax-on; it is a base-level adjustment that grosses up taxable income BEFORE the rate. The substrate is correct. |
| **§8b KStG** 10% Streubesitz threshold | none | Only matters for **dividends**, not share-disposals. The provider needs an `:ownership-fraction` to compute Streubesitz for §8b Abs. 4, but that is a `TaxRateProvider` (transaction-incident) concern at dividend posting time, not `:disposal`'s. **Out of scope** for this note. |
| **§6b EStG** subject eligibility | `:subject-kind :fixed-asset` + `:asset/class` | **CLEAN** — §6b enumerates: real estate, buildings, agricultural land, ships, certain participations ≤ €500 000. Asset-class membership is the gate, already on `:asset`. |
| **§6b EStG** rollover election | none | **GAP** — `:disposal/rollover-into-asset` ref + `:disposal/rollover-amount` Money + a `:6b-reserve-account` lifecycle on the GL side. See §4. |
| **§6b EStG** 4/6-year window | none | **GAP** — needs `:rollover-deadline` instant; the period-end reserve-balance check is a provider job, but the deadline IS data. |
| **§17 EStG** ≥1% stake test | none | **GAP** — `:disposal/ownership-fraction` BigDecimal + lookback. §17's "innerhalb der letzten 5 Jahre zu mindestens 1%" is a TEST the provider runs over the partner's holding history; the *current* fraction at disposal date is the **input**. Need the field. |
| **§17 EStG** Teileinkünfteverfahren | none on `:disposal`; provider does the 60% multiplication | **CLEAN** — the 60% inclusion IS a `:base-transform :adjustments` with `:deductions [(* 0.4M gain)]`, exactly the §8b mechanism in reverse. Or a `:formula` transform (`(* 0.6M gain)`). Substrate works. |
| **§17 EStG** €9 060 Freibetrag with taper | none on `:disposal` | **CLEAN** — taper is a `:formula` schedule on the §17 gains pool: `(min 9060M (max 0M (- 9060M (* 0.5M (max 0M (- gain 36100M))))))` as a deduction adjustment. Algebra handles it. |
| **§20 EStG** Abgeltungsteuer flag | `:disposal/kind :investment-sale` | **AMBIGUOUS** — does the holder elect Günstigerprüfung? That is a tax-unit-level election, NOT per-disposal — so it lives in the PIT `:inputs :tax-unit {:abgeltungsteuer-elect-marginal? true}`. But the disposal itself needs `:disposal/category :securities-stock` vs `:securities-other` for the §20 Abs. 6 sub-bucket wall. See §4. |
| **§23 EStG** holding-period test | proposed `:holding-period :short/:long/:n-a` enum | **INADEQUATE** — DE has TWO cutoffs (1y movables, 10y real estate) and a tax-FREE-past-cutoff terminal state; an enum cannot capture both the cutoff *and* the post-cutoff tax-free state per subject kind. **REPLACE** with `:disposal/acquired-on` instant; the provider applies the per-jurisdiction rule. See §4. |
| **§23 EStG** subject discriminator (real-estate vs movable) | `:subject-kind` enum | **PARTIAL** — current proposal has `:subject-kind :fixed-asset / :participation / :inventory / :intangible / :business-segment`. Missing **`:real-estate-private`** (a §23 real-estate sale by an individual, NOT a `:fixed-asset` because not on a business balance sheet) and **`:movable-private`** (gold, crypto, art). Extend the enum. See §4. |
| **Verlustverrechnung** — four buckets | `:inputs :capital-loss-carryforward` (existing PTP slot) | **INADEQUATE** — note 102 §3a's `{:short :long}` two-bucket structure is US's, not DE's. DE needs **`{:de-§8b :de-§17 :de-§20-stock :de-§20-other :de-§23}`** — five buckets. The PTP `:inputs` map is **opaque to the substrate**, so this is provider-shape — no kernel change, but a documented jurisdiction-key convention. |
| **Sonderabschreibungen recapture** | none | **PARTIAL GAP** — when an asset on which `kontor-asset` ran accelerated depreciation (§7g IAB, AfA-Sonderabschreibung) is disposed, the excess depreciation reverses into the disposal gain. `kontor.asset.depreciation` already tracks per-book accumulated depreciation, so the data IS there; the provider needs a hook that reads the *book* depreciation (Steuerbilanz, NOT HGB) and folds the recapture into `:realized-gain-loss`. NOT a schema gap — a provider-recipe gap. |

---

## §4. Data gaps — the concrete extension list

Six additive fields on `:disposal` + one enum extension + one rewording.
All are companion-module schema (per note 107 §3.2's "kernel
untouched" posture).

1. **`:disposal/acquired-on` instant** — REPLACES `:disposal/holding-period
   :short/:long/:n-a` enum (or makes it derived). DE needs per-provision
   cutoff math; US needs the 1-year line; FR has per-asset-class lines.
   The enum is a denormalisation that does not generalize; the date IS the
   data.

2. **`:disposal/subject-form` keyword** — `:corp / :partnership / :sole-prop
   / :n-a` — DE §8b applies ONLY when the held entity is a corp. (Or:
   read `:entity/legal-form` off the underlying share's issuer — the
   note 107 §2.4 "Common Stock as a `:commodity`" convention means the
   issuer is reachable via `:commodity/issuer` → `:entity`. Need to
   confirm whether `:commodity/issuer` exists; if not, add a
   `:disposal/issuer-entity` ref.)

3. **`:disposal/ownership-fraction` BigDecimal** — the holder's percentage
   stake at disposal date (0..1). §17 (>=0.01M test), §8b Streubesitz
   (>=0.10M test), and several other jurisdictions' substantial-holding
   rules need this. Single field, multi-jurisdiction value.

4. **`:disposal/rollover-into-asset` ref → `:asset`** + **`:disposal/rollover-amount`
   Money** + **`:disposal/rollover-deadline` instant** — §6b's
   `Reinvestitionsrücklage`. Both fields nullable — present only on a
   `:kind :asset-sale` where the consumer elects rollover. The §6b reserve
   account itself is a GL **liability** (or contra-asset) account the
   consumer maintains; the disposal helper does NOT create it, but the
   disposal fields LINK the disposal record to the eventual replacement.
   When `:rollover-into-asset` is set, the CGT provider does NOT include
   this disposal's gain in the §17/§23/§8b pools — it is deferred.

5. **`:disposal/category` keyword** — finer than `:kind`. §20's sub-bucket
   wall needs `:securities-stock` vs `:securities-other`; §23 needs
   `:real-estate-private` vs `:movable-private`. Recommendation: extend
   `:subject-kind` rather than add a new field — append
   `:real-estate-private`, `:movable-private`, `:securities-stock`,
   `:securities-other` to the proposed enum. (Five enum values total —
   `:fixed-asset / :participation / :real-estate-private / :movable-private
   / :securities-stock / :securities-other / :inventory / :intangible /
   :business-segment`. Closed-set discipline per note 101.)

6. **`:disposal/elective-regime` keyword** — `:de-§6b-rollover /
   :de-§17-teileinkünfte-elected / :de-§32d-günstigerprüfung / nil`.
   Several DE provisions are elective and the election is recorded
   per-disposal (or per-tax-unit-period). Nullable; provider reads it
   when applicable.

7. **`:disposal/loss-bucket` keyword** — `:de-§8b / :de-§17 / :de-§20-stock
   / :de-§20-other / :de-§23 / :us-short / :us-long / …`. Denormalised at
   `record-disposal!` time by the per-jurisdiction classifier. The CGT
   provider sums gains/losses by bucket and applies the bucket-specific
   offset rules. Without this, the substrate cannot represent DE's
   four-bucket wall.

The PTP `:inputs :capital-loss-carryforward` map gets a per-jurisdiction
extension (no schema change — it is an opaque provider input): for DE,
the map carries `{:de-§8b :de-§17 :de-§20-stock :de-§20-other :de-§23}`
keys, each pointing at a Money.

---

## §5. `de-cgt-provider` sketch — TWO providers, not one

DE CGT splits cleanly along the corporate/individual axis. Two providers:

### 5.1 `de-corporate-cgt-provider` — feeds into KSt + GewSt

A `PeriodTaxProvider` of kind `:capital-gains-tax`, but its output is
**NOT a free-standing return** — it is **an adjustment input to the CIT
provider**. The mechanism:

```
;; Conceptual — no code change in this note.
(defn de-corporate-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :de-corp-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals (disposal/disposals-of conn {:entity entity :period period})
            §8b-gains (sum-§8b-eligible-gains disposals)         ; subject-form :corp
            §6b-defers (sum-§6b-rolled-over-gains disposals)     ; gain removed from pool
            taxable-add-back (* 0.05M §8b-gains)                  ; 5% non-deductible
            ;; Returns ZERO components — this provider only contributes
            ;; an adjustment input to the CIT provider via a side channel.
            ]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :de-finanzamt}
          :functional-commodity :EUR
          :components
          [{:kind :capital-gains-tax
            :authority :de-finanzamt
            :base (money/zero :EUR)         ; exemption: full gain is exempt
            :gross-liability (money/zero :EUR)
            :liability (money/zero :EUR)
            :prepaid (money/zero :EUR)
            :line-items
            [{:line :§8b-gains-exempt  :value (money/money §8b-gains :EUR)}
             {:line :§8b-5pct-add-back :value (money/money taxable-add-back :EUR)}
             {:line :§6b-rolled-over   :value (money/money §6b-defers :EUR)}]
            :jurisdiction-specific-codes
            {:cit-base-additions [taxable-add-back]}}]}))))  ; the integration hook
```

The CIT provider's caller composes the two: `de-corporate-income-tax-provider`'s
`:inputs :base-transform :adjustments :additions` receives the
`:cit-base-additions` from the CGT facts. **NO new substrate operator**
— this is the same composition `kontor.sole-proprietor/business-income-input`
already demonstrates between the business-net helper and the PIT provider
(sole_proprietor.clj:25-55).

### 5.2 `de-personal-cgt-provider` — feeds into the EStG return

Similar shape, but the four buckets (§17, §20-stock, §20-other, §23)
need separate computation paths:

```
(defn de-personal-cgt-provider
  [_]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :de-pers-cgt)
    (period-tax-facts [_ {:keys [conn entity period inputs]}]
      (let [disposals (disposal/disposals-of conn {:entity entity :period period})
            ;; §17 — wesentliche Beteiligung — Teileinkünfteverfahren 60 %
            §17-net (-> (filter §17? disposals)
                        (net-against-bucket (:de-§17 (:capital-loss-carryforward inputs)))
                        (* 0.6M)
                        (apply-freibetrag-with-taper {:freibetrag 9060M :phase-out 36100M}))
            ;; §20 — Abgeltungsteuer — flat 25 %, OWN component
            §20-stock-net (net-against-bucket
                           (filter §20-stock? disposals)
                           (:de-§20-stock (:capital-loss-carryforward inputs)))
            §20-other-net (net-against-bucket
                           (filter §20-other? disposals)
                           (:de-§20-other (:capital-loss-carryforward inputs)))
            §20-total (+ §20-stock-net §20-other-net)
            §20-tax (* §20-total 0.25M)        ; abgeltungsteuer
            günstig? (:abgeltungsteuer-elect-marginal? (:tax-unit inputs))
            ;; §23 — private speculation — within Frist, FREE outside
            §23-net (net-against-bucket
                     (filter §23-in-window? disposals)
                     (:de-§23 (:capital-loss-carryforward inputs)))
            §23-taxable (if (> §23-net 1000M) §23-net 0M)]  ; Freigrenze
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:authority :de-finanzamt}
          :functional-commodity :EUR
          :components
          [;; §17 + §23 fold into marginal-rate income —
           ;; surface as an :inputs :adjustments :additions for the PIT provider
           {:kind :capital-gains-tax :authority :de-finanzamt
            :line-items [{:line :§17-taxable :value (money/money §17-net :EUR)}
                         {:line :§23-taxable :value (money/money §23-taxable :EUR)}]
            :jurisdiction-specific-codes
            {:pit-base-additions [§17-net §23-taxable]}}
           ;; §20 — standalone Abgeltungsteuer component
           ;; (regime :abgeltungsteuer vs :günstigerprüfung)
           {:kind :capital-gains-tax :authority :de-finanzamt
            :base (money/money §20-total :EUR)
            :schedule (ts/flat 0.25M)
            :gross-liability (money/money §20-tax :EUR)
            :liability (money/money §20-tax :EUR)
            :prepaid (money/money (or (:de-kapest-prepaid inputs) 0M) :EUR)
            :regime (if günstig? :günstigerprüfung :abgeltungsteuer)
            :surtaxes [{:code :soli :label "Soli auf Abgeltungsteuer"
                        :amount (* §20-tax 0.055M)}]}]})))))
```

The Soli surtax on §20 uses the same `apply-adjustments` mechanism the
PIT provider already wires (personal_income_tax.clj:75-83). The
Günstigerprüfung is a **regime flag** on the tax-unit — when true, the
§20 component is suppressed and the §20 net flows into the PIT base
instead. This is the elective-regime shape note 102 §9-C names.

### 5.3 The §8b "partial exclusion via `:base-transform`" verification

Confirmed: the 95% exclusion is a **base adjustment**, not a credit.
The 5% add-back rides `:base-transform :adjustments :additions` on the
CIT provider. The substrate handles it correctly today — the adjustment
layer (`apply-adjustments`, tax_schedule.clj:192-235) is for
credits/surtaxes on the *gross tax*, not for base-side exclusions.
`apply-base-transform` (tax_schedule.clj:142-163) is the right
operator. **No substrate stress on partial-exclusion mechanics.**

---

## §6. Sources

**DE statutes (gesetze-im-internet.de — license: public domain)**:
- [§ 8b KStG](https://www.gesetze-im-internet.de/kstg_1977/__8b.html) — 95/5 participation exemption.
- [§ 6b EStG](https://www.gesetze-im-internet.de/estg/__6b.html) — rollover relief.
- [§ 17 EStG](https://www.gesetze-im-internet.de/estg/__17.html) — wesentliche Beteiligung.
- [§ 20 EStG](https://www.gesetze-im-internet.de/estg/__20.html) — Kapitalvermögen.
- [§ 23 EStG](https://www.gesetze-im-internet.de/estg/__23.html) — private Veräußerungsgeschäfte.
- [§ 32d EStG](https://www.gesetze-im-internet.de/estg/__32d.html) — Abgeltungsteuer + Günstigerprüfung.

**BMF (Federal Ministry of Finance)**:
- [BMF Schreiben 2025-05-14 "Einzelfragen zur Abgeltungsteuer"](https://www.bundesfinanzministerium.de/Content/DE/Downloads/BMF_Schreiben/Steuerarten/Abgeltungsteuer/2025-05-14-einzelfragen-zur-abgeltungsteuer.pdf?__blob=publicationFile&v=6) — authoritative current-year guidance on §20 mechanics.
- [BMF KSt-Handbuch — § 8b Anwendung](https://ksth.bundesfinanzministerium.de/ksth/2022/A-Koerperschaftsteuergesetz/II-Einkommen/1-Allgemeine-Vorschriften/Paragraf-8b/inhalt.html) — official application of § 8b.

**Practitioner commentary** (Beck-Verlag tier — Haufe, NWB, professional firms):
- [Haufe — Reinvestitionsrücklage § 6b EStG](https://www.haufe.de/id/beitrag/reinvestitionsruecklage-nach-6b-estg-HI2048180.html) — 4/6-year window mechanics.
- [Haufe — Teileinkünfteverfahren / § 17 EStG](https://www.haufe.de/id/beitrag/teileinkuenfteverfahren-123-veraeusserungen-nach-17-estg-HI6446215.html) — 60% inclusion + Freibetrag taper.
- [Haufe — Zehn-Jahres-Frist § 23 EStG](https://www.haufe.de/steuern/rechtsprechung/zehn-jahres-frist-bei-privaten-veraeusserungsgeschaeften_166_548536.html) — 10-year line.
- [steuerberatung-neeb.de — § 8b worked example](https://steuerberatung-neeb.de/ausschuettungen-zwischen-koerperschaften-aus-steuerlicher-sicht-steuerfreie-gewinne-im-konzern/) — concrete 95/5 numbers used in §2.1.
- [JUHN Partner — § 8b](https://www.juhn.com/fachwissen/gmbh-steuerrecht/steuerfreistellung-von-kapitalertraegen-nach-8b-kstg/) — Streubesitz boundary.
- [Bibukurse — § 17 EStG](https://www.bibukurse.de/einkommensteuer/einkuenfte/einkuenfte-aus-gewerbebetrieb/arten-gewerblicher-einkuenfte/einmalige-einkuenfte/veraeusserung-von-anteilen-an-kapitalgesellschaften-17-estg.html) — 1% test + 5-year lookback.

**kontor substrate cited (file:line)**:
- `src/kontor/tax_schedule.clj:142-163` — `apply-base-transform :adjustments` (the §8b 5%-add-back operator).
- `src/kontor/tax_schedule.clj:192-235` — `apply-adjustments` (Soli-on-Abgeltungsteuer surtax).
- `src/kontor/personal_income_tax.clj:65-118` — the adjustment-layer composition pattern the §17/§23 fold mirrors.
- `src/kontor/period_tax_provider.clj:51-60` — `:capital-gains-tax` is in the closed enum already.
- `src/kontor/period_tax_provider.clj:134-141` — `:capital-loss-carryforward` `:inputs` slot (DE extends to four buckets).
- `src/kontor/sole_proprietor.clj:25-55` — the "CGT provider feeds CIT/PIT via composition" pattern.
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3.2 — the `:disposal` schema proposal this note reviews.
- `doc/research/102-period-tax-provider-design.md` §7 — capital-gains tax as a hybrid.
- `doc/research/105-the-algebra-of-a-tax.md` — adjustment-layer algebra for surtaxes.

---

End of note 113.

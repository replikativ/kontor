---
date: 2026-05-24
title: 115 — JP capital-gains tax (jōto-shotoku) — substrate fit assessment for Phase 3
audience: maintainer + the Phase 3 `jp-cgt-provider` implementation agent
status: research-before for the JP CGT companion of ADR-101 `:disposal` + the future `jp-cgt-provider`; no code
---

# 115 — JP capital-gains tax: substrate fit for Phase 3

Capital-gains tax in Japan (jōto-shotoku 譲渡所得) is **not one tax** — it
is a **family** of disposal-class-specific taxes, each with its own rate,
its own holding-period rule, and its own loss-offset compartment. The
"separate vs aggregated" axis (bunri / sōgō kazei 分離・総合課税) is the
structural choice that makes JP CGT distinct from DE / UK / US: gains on
some asset classes are taxed at flat preferential rates **outside** the
progressive personal-income-tax stack; gains on other classes are
**folded into** ordinary income at marginal rates.

This note (a) summarises the JP CGT regime per asset class, (b) walks
two worked examples, (c) assesses whether the proposed `:disposal`
schema (note 107 §3) and the kontor period-tax substrate (ADR-099)
carry it cleanly, (d) names the concrete data gaps, (e) sketches a
multi-component `jp-cgt-provider`, and (f) cites sources.

---

## §1. The JP CGT regime — separate vs aggregated, by asset class

### 1.1 The bunri / sōgō kazei axis

`所得税法 §22` aggregates 10 income categories (employment, business,
real-estate rental, interest, dividend, retirement, forestry,
miscellaneous, occasional, capital-gains) into one "total income"
(総所得金額) that runs through the progressive 7-bracket schedule
(5/10/20/23/33/40/45 %). This is **sōgō kazei** (aggregated taxation).

JP CGT carves several categories OUT of this aggregation under
**租税特別措置法** (Special Taxation Measures Law) and taxes them at
flat preferential rates SEPARATELY — **bunri kazei**. Two practical
consequences:

1. The flat rate is **decoupled** from the taxpayer's marginal rate
   (a low-income individual selling shares pays 20.315 % even though
   their wage income is in the 5 % band; a high-income individual
   pays the same 20.315 % even though their wage income is in the 45 %
   band).
2. Losses **stay inside their compartment** — a real-estate loss
   cannot reduce listed-securities gain and vice versa; some
   compartments allow a 3-year carryforward (listed securities) and
   others do not (real estate, in general).

### 1.2 Rates and holding-period rules by asset class — individuals

| Asset class | Separate? | Rate | Holding-period rule | Loss carry |
|---|---|---|---|---|
| **Real estate — short-term** (≤ 5 yrs at Jan 1 of disposal year) | bunri | **39.63 %** (30 % national + 0.63 % reconstruction surtax + 9 % local) | Jan-1 measurement | No |
| **Real estate — long-term** (> 5 yrs at Jan 1 of disposal year) | bunri | **20.315 %** (15 % + 0.315 % + 5 %) | Jan-1 measurement | No (limited) |
| **Real estate — own residence, > 10 yrs** | bunri | **14.21 %** on the slice ≤ ¥60 M, **20.315 %** above; on top of the ¥30 M residence exemption | Jan-1 measurement, plus the own-occupancy + 10-yr ownership test | No |
| **Listed securities (上場株式等)** | bunri | **20.315 %** flat | No holding-period split | **3-yr carryforward** if reported, within the listed-securities compartment |
| **Unlisted securities (一般株式等)** | bunri | **20.315 %** flat | No holding-period split | No cross-compartment offset |
| **Crypto-assets — pre-2026** | sōgō (zatsu shotoku 雑所得) | progressive up to **~55 %** combined | n/a | None (loss can offset other zatsu within year, no carry) |
| **Crypto-assets — 2026 reform (specified tokens)** | bunri (proposed) | **~20 %** flat (15 % national + 5 % local), specified list (BTC, ETH, FSA-registered platform) | n/a | **3-yr carryforward** (proposed) |
| **Business-use assets** (jigyō-yo no shisan) | sōgō | progressive marginal | n/a | Folds into business income |
| **Movables / collectibles** (golf memberships, art, jewellery over ¥300 K) | sōgō | progressive marginal | 5-yr ½-rate after long-term special deduction ¥500 K | Within sōgō |

The **Jan-1 holding-period measurement** is the JP-unique twist for
real estate: an asset bought 2020-12-01 and sold 2026-02-01 is
**short-term** because as of 2026-01-01 fewer than 5 full years had
passed. The clock is NOT the actual elapsed time but the elapsed
**calendar-year boundary** to Jan 1 of the disposal year. This shifts
the rate from 20.315 % to 39.63 % — a near-doubling — for sellers who
miss the calendar boundary by months.

### 1.3 Corporate CGT — no separate regime

Capital gains realised by a domestic corporation are **ordinary
corporate income** taxed at standard CIT rates (the 5-component stack
of note 107 §4.3: national CIT + local CIT + enterprise + inhabitants +
2026 defense surtax). There is **no participation exemption** and
**no holding-company regime** — JP does not have a §8b KStG / UK SSE /
NL deelnemingsvrijstelling equivalent (pwc Japan Corporate /
Income determination). Three narrow corporate-side reliefs exist:

- **Intra-group transfer deferral** — gains on transfers between
  Japanese consolidated-group members are deferred until the asset
  leaves the group.
- **Rollover relief for qualified reinvestment** — narrow
  asset-class-specific provisions in 租税特別措置法 (e.g.
  exchange-of-fixed-assets 固定資産の交換 §50, replacement of business
  real estate §65 / §65-7, expropriation §33).
- **Dividend-received deduction** — a partial exemption on inbound
  intercorporate dividends (20–100 % depending on ownership %); does
  NOT extend to capital gains on share disposals.

For substrate purposes: corporate JP CGT does NOT need a separate
provider; gains land in the GL as ordinary income and the CIT provider
of note 107 §4.3 sweeps them up. The substrate work is **wholly on the
individual side**.

### 1.4 Loss-offset compartments and carryforward

The compartments are watertight:

- **Real-estate compartment** — short-term and long-term real-estate
  losses offset each other within the year; no carryforward to future
  years (`租税特別措置法 §31, §32`).
- **Listed-securities compartment** — losses on listed shares offset
  listed-share dividends + specified-bond interest WITHIN the year,
  and unused losses carry forward **3 years** when reported on the
  return (`租税特別措置法 §37-12-2`).
- **Unlisted-securities compartment** — losses do NOT offset listed
  gains and do NOT carry forward.
- **Crypto** — under the 2026 reform, the proposed bunri-kazei
  treatment adds a 3-year carryforward inside the crypto compartment;
  outside the reform list (NFTs, DeFi yields, non-registered
  exchanges) the pre-reform zatsu-shotoku rules persist with **no
  carryforward**.

### 1.5 The ¥30 M principal-residence exemption and the rollover

- **Principal-residence ¥30 M deduction (居住用財産の3,000万円特別控除,
  租税特別措置法 §35)** — flat ¥30 M deducted from the gross gain
  before the rate is applied; **no holding-period requirement**;
  cannot be used in consecutive years; cannot be combined with a
  housing-loan tax credit for 3 years.
- **10-yr preferential rate (軽減税率, §31-3)** — when the residence
  has been owned **and** occupied for > 10 years (Jan-1 measurement),
  the slice up to ¥60 M after the ¥30 M deduction is taxed at
  **14.21 %** (10.21 % national + 4 % local), the slice above at the
  standard 20.315 %.
- **Replacement-asset deferral (買換え特例, §36-2)** — for an own
  residence held > 10 years and sold for ≤ ¥100 M, if the proceeds
  are reinvested in a replacement residence within a prescribed
  window, the gain is **deferred** (basis carried over to the
  replacement). Mutually exclusive with the ¥30 M deduction — the
  taxpayer elects.
- **Business-real-estate replacement (§65, §65-7)** — corporate-side
  replacement rollover for business-use real estate within
  prescribed asset categories; deferral via basis adjustment to the
  replacement asset.

These are **electable**, not automatic — the substrate must let the
consumer signal the election (flag on the `:disposal`) and the
provider must read it.

---

## §2. Worked examples

### Example A — Mr. Sato, short-term residential investment sale

(Tokyo Portfolio worked-example, [Case 1](https://tokyoportfolio.com/articles/capital-gains-tax-in-japan-calculations-for-real-estate-sellers/))

- Purchase 2022-03 for ¥60,000,000; acquisition costs ¥2,000,000;
  improvements ¥3,000,000 → **adjusted basis ¥65,000,000**.
- Sale 2026-04 for ¥80,000,000; selling expenses ¥1,500,000 → **net
  proceeds ¥78,500,000**.
- **Gain = ¥13,500,000**. (The Tokyo Portfolio figure differs slightly
  — they net selling expenses against basis rather than proceeds, a
  presentation choice; both yield the same gain.)
- **Holding period**: 2022-03 → 2026-01-01 = under 5 years → **short-
  term**, 39.63 %.
- **Tax owed**: ¥13,500,000 × 39.63 % = **¥5,350,050** (national + local
  combined).

Substrate trace: one `:disposal` with `:disposal/kind :sale`,
`:disposal/subject-kind :fixed-asset` (or, if the property was not
registered as a `:asset`, `:investment`), `:proceeds = ¥78.5 M`,
`:basis = ¥65 M`, `:holding-period :short`. The JP CGT provider's
"real estate" component reads this disposal (and any siblings),
computes the gain, applies 39.63 %.

### Example B — Mr. Takahashi, long-held residence with both reliefs

(TaxMatch / Tokyo Portfolio worked example, [10-yr preferential rate](https://e-zeirishi.com/en/capital-gains-tax-japan-foreign-residents-guide/))

- Purchased 2013-06 for ¥80,000,000; ¥10,000,000 in renovations →
  **adjusted basis ¥90,000,000**.
- Sale 2026-05 for ¥150,000,000; selling expenses ¥5,000,000 →
  **net proceeds ¥145,000,000**.
- **Gross gain ¥55,000,000**.
- Apply **¥30 M principal-residence exemption** → taxable gain
  **¥25,000,000**.
- Holding-period as-of-Jan-1-2026 = 12+ years > 10 → qualifies for
  **§31-3 preferential rate** AND occupied as residence for > 10
  years.
- Taxable ¥25 M ≤ ¥60 M slice → entire amount taxed at **14.21 %**.
- **Tax owed**: ¥25,000,000 × 14.21 % = **¥3,552,500**.

Substrate trace: one `:disposal` with `:disposal/kind :sale`,
`:disposal/subject-kind :fixed-asset`, `:holding-period :long`, PLUS
two electable flags (`:residence?`, `:rollover-elect`-ed?) — see §4.

---

## §3. `:disposal` schema fit assessment

The proposed shape from note 107 §3 plus this task's prompt:

```
:disposal/subject         polymorphic ref
:disposal/subject-kind    enum (:fixed-asset/:participation/:inventory/...)
:disposal/kind            enum (:sale/:incorporation-contribution/...)
:disposal/proceeds        Money
:disposal/basis           Money
:disposal/holding-period  enum (:short/:long/:n-a)
:disposal/realizing-tx    edge
:disposal/audit-doc       edge
```

### 3.1 Holding-period — `:short` / `:long` carries the **rate
selection**, but NOT the rule

`:holding-period :short / :long` works as the **rate-selection
denorm** at the time the provider reads the disposal: the JP CGT
provider's "real estate" component maps `:short → 39.63 %` and
`:long → 20.315 %`. The substrate does **NOT** need to know JP's
Jan-1 measurement — that classification is done **once, at
`record-disposal!` time**, by a jurisdiction-aware helper the JP
companion ships:

```clojure
(defn jp-real-estate-holding-period
  "Jan-1-of-disposal-year measurement per 租税特別措置法 §31."
  [acquired-on disposal-date]
  (let [jan-1-of-disposal-year (#'date/start-of-year disposal-date)
        years (#'date/years-between acquired-on jan-1-of-disposal-year)]
    (if (> years 5) :long :short)))
```

This mirrors note 107 §3.6's open question (denorm vs computed) and
sides with the denorm: **the rule applied at disposal time is the
rule that governs the law-as-it-stood**; if the law changes after the
disposal, the recorded `:holding-period` is what the audit return
relied on. Recommendation: **keep the `:short / :long / :n-a` enum**;
the JP companion ships the classifier; document the rule in the
JP CGT provider's docstring + an `:audit-doc` that names the rule.

### 3.2 The `:long` enum collapses 5-yr vs 10-yr — a class-specific
gap

But there is a **second-order JP rule** the enum does not carry: the
**10-year preferential residence rate**. A real estate disposal can be:

- `:short` (≤ 5 yrs)
- `:long` (5–10 yrs)
- "very long" / `:long-residence` (> 10 yrs + own-occupancy)

The current enum can either (a) **add a third value** `:very-long` (or
`:long-residence`) — a kernel schema addition — or (b) **carry the
boolean elsewhere** via a small flag set on the disposal. Option (b)
is cleaner because the "10-yr preferential" is **conditional on
ownership AND occupancy AND a residence-class election**, not on
holding period alone. Recommendation: **§4 gap — add
`:disposal/residence?` boolean** (and `:residence-occupied-from /
-to` if the substrate ever needs to audit the occupancy claim); keep
`:holding-period` as the three-value enum.

### 3.3 `:subject-kind` — JP's asset-class granularity fits

JP's class-specific rate structure (real-estate vs listed-securities
vs unlisted-securities vs crypto vs business-asset) maps to
`:subject-kind` cleanly:

| JP class | `:subject-kind` |
|---|---|
| Real estate | `:fixed-asset` (or a new `:real-property` if the kernel grows it) |
| Listed securities | `:participation` with a flag, or a new `:listed-security` |
| Unlisted securities | `:participation` with a flag |
| Crypto | a new `:crypto-asset` value — likely |
| Business-use asset | `:fixed-asset` with `:disposal/sogo? true` (the aggregation flag) |
| Inventory | `:inventory` (already in the enum — ordinary business income, not CGT) |

The current 5-value enum (`:fixed-asset / :participation /
:inventory / :intangible / :business-segment`) is **adequate** for the
distinctions JP CGT needs **provided the provider has additional
discriminators on the disposal** — see §4. The kernel does NOT need
to grow a `:crypto-asset` value before consumer demand exists; the
JP companion can carry the discrimination in `:disposal/asset-class`
(a new companion attr in `kontor-disposal`'s namespace).

### 3.4 Loss-offset compartments — read-side concern, not schema

The compartment discipline (real-estate losses do not offset listed-
securities gains; 3-yr carryforward for listed-securities only) is
**purely a provider-side fold over the period's disposals** — group by
compartment, sum gain/loss per compartment, drop a negative real-estate
sum to zero, persist a listed-securities negative as a 3-yr
carryforward state (the ADR-099 `:capital-loss-carryforward
{:short :long}` shape generalises to `{:listed-securities
:unlisted-securities :real-estate ...}`).

**Substrate fit**: clean. No schema change. The provider iterates the
period's `:disposal`s and the consumer-supplied `:inputs
:capital-loss-carryforward` (a per-compartment map).

### 3.5 `:realizing-tx` — the GL link works

The kernel-supplied gain/loss leg of the GL transaction is what the
provider would need to consult IF computing tax from the GL rather
than from the disposal record. The recommended posture (note 107
§3.6's denorm preference) is the **opposite**: the `:disposal`
carries `:proceeds` and `:basis` as its own attrs; the GL transaction
is **bound for audit** via `:realizing-tx` but **not read** by the
CGT provider. This decouples the tax computation from the chart of
accounts (a consumer with a non-standard chart still gets the right
JP tax) and keeps the `:disposal` self-sufficient.

---

## §4. Concrete data gaps

Five gaps the proposed `:disposal` schema needs to close before the
`jp-cgt-provider` can ship — three small, two substantive:

1. **`:disposal/residence?` boolean** — flags an own-residence sale.
   Drives the §35 ¥30 M deduction eligibility and (with
   holding-period > 10 yrs) the §31-3 preferential rate. Cheap.

2. **`:disposal/rollover-into` ref** — when set to a replacement
   `:asset` (or another `:disposal`), signals an electable
   replacement-asset deferral (§36-2 residence; §65 / §65-7 business
   real estate; §50 exchange-of-fixed-assets). The provider that
   sees this attr emits a deferred gain (¥0 current tax, basis
   carried to the replacement) instead of a current-year liability.
   The replacement asset's basis must be reduced by the deferred gain
   — the audit trail rides through `:audit-doc` and the
   counterparty `:asset`.

3. **`:disposal/asset-class` companion attr** — JP needs
   `:listed-securities` vs `:unlisted-securities` vs `:crypto-asset`
   vs `:real-estate` vs `:business-asset` distinctions the kernel's
   5-value `:subject-kind` does not carry. This lives in
   `kontor-disposal`'s namespace (NOT kernel), and the JP provider
   reads it. Each jurisdiction's companion can extend the value set
   per its rate-classification needs.

4. **`:disposal/sogo?` boolean** (or, equivalently,
   `:disposal/treatment :separate | :aggregated`) — flags whether
   the gain enters separate taxation (bunri) or aggregated taxation
   (sōgō). The substrate-clean way is to make this **derivable from
   `(:asset-class, jurisdiction-rules)` inside the provider**, NOT
   stored on the disposal — the law (not the bookkeeper) decides.
   So **NOT a schema attr**; instead, **provider logic**.

5. **The Jan-1 holding-period classifier** — NOT a schema attr; the
   JP companion ships `jp-real-estate-holding-period` and calls it
   from `record-disposal!` (via a per-jurisdiction classifier
   registered on the disposal companion). Note this in the
   `:disposal/audit-doc` so a future auditor can verify which rule
   was applied (the law-as-it-stood doctrine).

**Bottom line**: TWO concrete companion-schema additions
(`:disposal/residence?`, `:disposal/rollover-into`), ONE companion
namespace attr (`:disposal/asset-class`), ZERO kernel changes, and
the holding-period enum stays as-is (the per-jurisdiction rule
carries the classification, the enum carries the rate-selection
denorm).

---

## §5. `jp-cgt-provider` sketch — multi-component, NOT multi-provider

JP's compartment structure suggests TWO valid architectures:

### Architecture A — one provider, multi-component `TaxReturnFacts`

One `JpCgtProvider` returns ONE `TaxReturnFacts` whose `:components`
vector fans out per asset-class compartment:

```clojure
{:kind :capital-gains-tax :authority :jp-nta
 :composed-of  []      ;; real-estate-short
 :base ...    :schedule (ts/flat 0.3963M) ...}
{:kind :capital-gains-tax :authority :jp-nta
 :composed-of  []      ;; real-estate-long
 :base ...    :schedule (ts/flat 0.20315M) ...}
{:kind :capital-gains-tax :authority :jp-nta
 :composed-of  []      ;; listed-securities
 :base ...    :schedule (ts/flat 0.20315M) ...}
;; ... unlisted, crypto-bunri, etc.
```

Each component is one compartment; loss-offsetting happens INSIDE the
component's `:base` (proceeds − basis for the per-compartment sum
across the period); the residual carryforward rides
`:line-items` + `:inputs :capital-loss-carryforward` map.

The bunri/sōgō split is **outside** the JP CGT provider:
**business-asset (sōgō) gains land in the existing `kontor.report` P&L
roll-up via the GL** (the disposal's `:realizing-tx` posts gain to a
P&L account); the `jp-income-tax-provider` (note 102 §4.3) sweeps them
into `gross-income` for the progressive schedule. **No CGT
component for sōgō gains** — the existing PIT provider handles them
automatically. This is the cleanest fit.

### Architecture B — multiple sibling providers feeding `:inputs`

One provider per compartment (`jp-cgt-real-estate-provider`,
`jp-cgt-listed-securities-provider`, …); each emits its own
`TaxReturnFacts`; the consumer's tax-return assembly sums them.

This is the shape `jp-income-tax` + `jp-inhabitant-tax` already use
(note 102 §4.3 deliberately splits them across two providers because
of the `:base-period` discrepancy). For CGT, **all compartments share
the same `:period` and `:authority`** — `:jp-nta` + `:jp-municipality`,
the same as PIT, just emitted on Form 第三表 (separate-tax return) and
the Form B inhabitant-tax addendum. So the same-authority +
same-period property removes the rationale that drove the PIT /
inhabitant split.

### Recommendation — Architecture A

**One `jp-cgt-provider` returns a multi-component `TaxReturnFacts`**,
one component per active compartment. Rationale:

- Same `:period`, same `:authority` per component → no need to fan
  across providers.
- Loss-offset compartments are an **internal** discipline of one
  return, NOT a cross-provider concern → easier to keep watertight in
  one piece of code.
- The carryforward residual (the leftover unused listed-securities
  loss after the year's offset) lives on the assessed entity as a
  next-year `:inputs :capital-loss-carryforward` — same shape PIT
  already uses for income carry-in.
- The `:composed-of` field of each component carries the compartment
  tag (e.g. `:composed-of [:real-estate-short]`) so downstream readers
  (the return assembler, an explain-this-number report) can group.
- A separate top-level **personal-income-tax** provider continues to
  cover sōgō gains via the GL; no double-counting.

The note 105 adjustment layer carries the 2.1 % reconstruction surtax
(already on the national tax) and the inhabitant-tax piece is emitted
as separate per-component lines (5 % local rate is baked into the
component's `:schedule`, exactly as the existing `jp-income-tax-provider`
flattens national + reconstruction into one rate).

### Substrate stress this provider surfaces

- **None on the schedule algebra** — `:flat` with five distinct rates
  is exactly what `:flat` is for; the algebra of note 105
  (apply-adjustments) carries the per-compartment line items.
- **One on the `:disposal` shape** — the two schema gaps in §4
  (`:residence?`, `:rollover-into`) plus the companion-namespace
  `:asset-class`.
- **One on the period-tax `TaxReturnFacts` enum** — the
  `:capital-gains-tax` value is already in `period-tax-kinds`
  (research note 102 §2.1); good. The reconciliation between
  per-compartment components and the closed enum is "all components
  share the same `:kind :capital-gains-tax`, differentiated by
  `:composed-of`" — works without enum extension.
- **One on the loss-compartment carryforward shape** — extend the
  `:capital-loss-carryforward` `:inputs` key from
  `{:short :long}` to a per-compartment map. ADR-099-addendum-track.

Total: **2 companion-schema attrs + 1 `:inputs`-shape extension + 0
kernel changes + 0 schedule-algebra changes**. Within the conservative
posture of note 107.

---

## §6. Sources

JP statutory:
- 所得税法 §22 (aggregated income), §89 (PIT brackets), §28 (employment
  income), §33 (capital gains).
- 租税特別措置法 §31 (real-estate long-term separate), §32 (short-term
  separate), §35 (¥30 M residence exemption), §31-3 (10-yr preferential
  rate), §36-2 (residence replacement deferral), §37-12-2 (listed-
  securities loss carryforward), §65, §65-7 (business real-estate
  replacement), §50 (exchange-of-fixed-assets), §33 (expropriation
  rollover), §41-14 (specified-account 特定口座).
- 復興特別所得税 — the 2.1 % reconstruction surtax that flows through
  the 15.315 % component.

Reference / commentary:
- PwC Worldwide Tax Summaries — Japan Individual: Income determination
  (https://taxsummaries.pwc.com/japan/individual/income-determination)
- PwC Worldwide Tax Summaries — Japan Corporate: Income determination
  (https://taxsummaries.pwc.com/japan/corporate/income-determination)
  — "participation exemption is not applicable, and there is no
  holding company regime"; intra-group transfer deferral; rollover
  relief for qualified reinvestment.
- TaxMatch Japan — Capital Gains Tax in Japan for Foreign Residents
  2026 (https://e-zeirishi.com/en/capital-gains-tax-japan-foreign-residents-guide/)
  — 39.63 / 20.315 / 14.21 rate ladder; Jan-1 measurement; ¥30 M
  residence exemption; 3-yr listed-securities carryforward.
- Tokyo Portfolio — Capital Gains Tax in Japan: Calculations for Real
  Estate Sellers (https://tokyoportfolio.com/articles/capital-gains-tax-in-japan-calculations-for-real-estate-sellers/)
  — worked examples (Mr. Sato short-term ¥9.3 M tax on ¥23.5 M gain;
  Ms. Kobayashi long-term ¥6.5 M tax on ¥32 M gain).
- PLAZA HOMES — Capital Gains Tax on Property Sales in Japan
  (https://www.realestate-tokyo.com/news/real-estate-tax-on-selling-property-in-japan/)
  — Jan-1 cutoff worked examples; 10-yr preferential.
- EY Japan 2026 Tax Reform Outline
  (https://www.ey.com/en_jp/technical/ey-japan-tax-library/tax-alerts/2025/tax-alerts-12-24)
  + PwC FS Tax News 2026
  (https://www.pwc.com/jp/en/taxnews-financial-services/assets/fs-20251224-en.pdf)
  + MEXC summary (https://www.mexc.com/learn/article/japan-crypto-tax-2026-overview-of-the-new-tax-regime/1)
  — the proposed crypto reform: 20 % flat (15 % national + 5 % local)
  for specified tokens via FSA-registered platforms; 3-yr carryforward;
  non-specified tokens (NFTs, DeFi, unregistered exchanges) remain
  miscellaneous-income progressive up to 55 %.
- Ministry of Finance — Overview of Taxation on JGBs for Residents +
  Corporations
  (https://www.mof.go.jp/english/policy/jgbs/topics/taxation2016/1.html)
- JSRI — Securities Taxation Chapter XIV
  (https://www.jsri.or.jp/publish/english/pdf/english_2024/2024_14.pdf)
- KPMG — Taxation in Japan 2023
  (https://assets.kpmg.com/content/dam/kpmgsites/jp/pdf/2023/jp-en-taxation-in-japan-202311.pdf)

kontor substrate cited:
- `src/kontor/personal_income_tax.clj:37-118` — the PIT provider into
  which JP CGT's sōgō gains fold via the GL.
- `src/kontor/period_tax_provider.clj:44-61` — the closed
  `period-tax-kinds` enum, `:capital-gains-tax` already in.
- `src/kontor/tax_schedule.clj:241-251` — `flat` constructor for the
  per-compartment rates.
- `modules/l10n-jp/src/kontor/l10n_jp/period_tax_provider.clj:50-180`
  — the existing JP PIT provider; the JP CGT provider mirrors its
  shape (multi-rate, separate compartments).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  proposed `:disposal` schema this note assesses.

---

End of note 115.

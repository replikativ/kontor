---
date: 2026-05-25
title: 168 — Tax-system coverage matrix + substrate-seam audit (consolidation input)
audience: maintainer (consolidate-or-extend decision); inputs to note 167 and Gap #5 / Gap #8 sequencing
status: static-analysis sweep — no code changed; pairs with note 160 (API-consistency log) and note 167 (fiscal-unit synthesis)
related:
  - ADR-005 (legacy `TaxProvider` — now historical)
  - ADR-071 (`TaxRateProvider` + `TaxFacts` + `TaxPostingBuilder`)
  - ADR-095 (`kontor.book` verb facade)
  - ADR-099 (`PeriodTaxProvider`)
  - ADR-100 (`kontor.sole-proprietor` + `kontor.vat-return`)
  - ADR-101 + Addenda 1/2 (statute-as-data — `:provision` / `:regime` / `:parameter`)
  - ADR-102 (`kontor-disposal` companion)
  - ADR-103 (`DisposalSource` protocol + per-jurisdiction CGT providers)
  - ADR-104 .. ADR-107 (DE / FR / JP / CA CIT providers)
  - notes 102, 104 (the tax-completion program and its 11-jurisdiction gap map)
  - notes 121 / 122 (BR + IN CIT substrate-fit before Gap #3 landed)
  - notes 147-158 (investment-income research per jurisdiction)
  - notes 160 (API-consistency audit, in-flight)
  - notes 164 / 165 / 166 / 167 (Gap #8 fiscal-unit research and synthesis)
---

# 168 — Tax-system coverage + substrate-seam audit

## TL;DR

The tax substrate is materially complete for the **eight categories** kontor
claims to cover (CIT, PIT, CGT, investment-income, VAT/sales-tax/GST, payroll,
property/wealth, fiscal-unit/group). Coverage is **80 of 96 cells** across 12
jurisdictions — 83 % nominal, 70 % once you discount cells that are "shipped
but not on the statute-as-data path" (e.g. the five record-shape CIT providers
in `period_tax_provider.clj` for US/AT/AU/CN/MX, which Gap #5 still tracks
for migration). The cell that is **uniformly missing** across every jurisdiction
is **property / wealth / land tax**; only one country (CN, via the bespoke
`lat_provider`) ships any code in that bucket. **Fiscal-unit / group-tax** is
planned (note 167) and untouched in every jurisdiction.

Below that, the **substrate seams are mostly P1 / P2 polish**, with two P0s
that materially affect substrate confidence:

- **S1 (P0)**: no provider emits a non-functional-currency liability via
  `kontor.fx` — every one of the 38 provider files we surveyed assumes
  `:commodity` matches the entity's functional currency. The moment a German
  taxpayer holds a CHF custody account or a US LLC receives EUR dividends,
  there is no audited path from foreign-currency income to local-currency tax
  base. S1 is a substrate-trust issue more than a code-quantity issue.
- **S2 (P0)**: dual-install entry points per module (`<file>_statute.clj/install!`
  AND `<file>_provider.clj/install-statute!`) — the second is a thin
  delegator. Consumers reading the namespace surface cannot tell which to call;
  no module exports a single `install-all!`. Phase B's two presets
  (DE + CA) do solve it for those two countries, but the other ten don't have
  a preset.

The rest of the seams are uniformity issues (S3-S11) and dead-code candidates
(D1-D4). The recommended Tier-1 priority (§6) is **S1 — wire `kontor.fx`
into the `tax_return_posting_builder` path** before any new substrate lands.
Once S1 closes, the tax surface can credibly claim "the substrate is correct,
the polish is ongoing" — which is the position Gap #5 (CIT migration) and
Gap #8 (fiscal-unit) need to be true before they begin.

---

## §1. Tax-system coverage matrix

**Legend:**
- `[shipped]` = on the ADR-101 statute-as-data path (parameters + provisions +
  evaluator-driven), with tests
- `[record]` = record-shape provider (predates / sidesteps ADR-101 — Gap #5
  tracks migration)
- `[stub]` = file present but minimal or specific carve-out only
- `[partial]` = some sub-component present, others not
- `[missing]` = no file, no schema, no test
- `[n/a]` = not applicable in the jurisdiction's tax design

Each cell cites the primary file path and the deftest/assertion count grep'd
from `*_test.clj` siblings. CIT counts are CIT-specific; PIT/CGT/investment-
income cells split similarly. Test counts are `deftest / is`.

| Jurisdiction | CIT | PIT | CGT | Investment-income | VAT/sales/GST | Payroll | Property/wealth | Fiscal-unit |
|--------------|-----|-----|-----|-------------------|----------------|---------|------------------|-------------|
| **DE** | `[shipped]` ADR-104 `l10n-de/cit_statute.clj` + `cit_provider.clj`, **7 / 31** | `[shipped]` `l10n-de/period_tax_provider.clj` (Einkommensteuer), **2 / 9** | `[shipped]` 2 providers, `l10n-de/cgt_{statute,provider}.clj`, **22 / 60** | `[shipped]` `l10n-de/investment_income_{statute,provider}.clj`, **16 / 50** | `[shipped]` `l10n-de/ustva.clj` + `tax_provider.clj`, **3 / 20** + USt | `[shipped]` `payroll-de-datev` (DATEV LODAS), see ADR-076 | `[missing]` | `[planned]` note 167 (DE Organschaft) |
| **FR** | `[shipped]` ADR-105 `l10n-fr/cit_{statute,provider}.clj`, **11 / 52** | `[shipped]` `l10n-fr/period_tax_provider.clj`, **2 / 6** | `[shipped]` 2 providers (personal + corporate), `l10n-fr/cgt_*.clj`, **29 / 69** | `[shipped]` 2 providers (PFU + corp.), `l10n-fr/investment_income_*.clj`, **15 / 60** | `[shipped]` `l10n-fr/ca3.clj` + `tax_provider.clj`, **3 / 25** | `[shipped]` `payroll-fr` (DSN NEODES), ADR-079 | `[missing]` | `[planned]` note 167 (intégration fiscale) |
| **CA** | `[shipped]` ADR-107 `l10n-ca/cit_{statute,provider}.clj` (fed + per-province), **11 / 69** | `[shipped]` ADR-099 pilot — `CaT1PeriodTaxProvider` in `l10n-ca/period_tax_provider.clj`, **4 / 17** | `[shipped]` `l10n-ca/cgt_*.clj`, **20 / 73** | `[shipped]` `l10n-ca/investment_income_*.clj`, **14 / 56** | `[shipped]` `l10n-ca/gst_hst.clj` + `tax_provider.clj`, **9 / 45** | `[shipped]` `payroll-ca` + QC RL-1 (ADR-078 / 087) | `[missing]` | `[planned]` (CA acquisition-of-control rules; note 167 §3.4 doesn't pilot CA) |
| **JP** | `[shipped]` ADR-106 `l10n-jp/cit_{statute,provider}.clj` (5-component stack), **12 / 58** | `[shipped]` 4 providers in `l10n-jp/period_tax_provider.clj` (income + inhabitants), **10 / 53** | `[shipped]` `l10n-jp/cgt_*.clj` (5-component), **24 / 63** | `[shipped]` `l10n-jp/investment_income_*.clj`, **23 / 58** | `[shipped]` `l10n-jp/consumption_tax.clj` + `tax_provider.clj`, **4 / 27** | `[shipped]` `payroll-jp` (Gensen), ADR-084 | `[missing]` (固定資産税 not modelled) | `[planned]` note 167 §3.3 — group-tsuusan is the structural outlier |
| **US** | `[record]` `l10n-us/period_tax_provider.clj` Form 1120 flat-21% (not on ADR-101 path), **9 / 44** | `[record]` `l10n-us/period_tax_provider.clj` Form 1040 (not on ADR-101), included in the **9 / 44** above | `[shipped]` `l10n-us/cgt_{statute,provider}.clj`, **12 / 34** | `[shipped]` `l10n-us/investment_income_{statute,provider}.clj`, **6 / 16** | `[partial]` `l10n-us/sales_tax.clj` (Avalara/TaxJar protocol scaffold per ADR-005; no bundled rates) | `[shipped]` `payroll-us-adp` (ADP GLI + multi-state), ADR-077 | `[missing]` (50-state property tax patchwork — not in scope) | `[planned]` note 167 §3.2 — US §1502 |
| **AU** | `[record]` `l10n-au/period_tax_provider.clj` (`au-company-tax-provider`), part of **3 / 13** | `[record]` `l10n-au/period_tax_provider.clj` (`au-income-tax-provider`), part of **3 / 13** | `[shipped]` `l10n-au/cgt_*.clj` (Div 115 50% discount), **18 / 30** | `[shipped]` `l10n-au/investment_income_*.clj`, **18 / 73** | `[shipped]` `l10n-au/gst.clj` + `bas.clj` + `tax_provider.clj`, **4 / 21** | `[shipped]` `payroll-au` (STP P2), ADR-080 | `[missing]` (state-level land tax not modelled) | `[missing]` |
| **AT** | `[record]` `l10n-at/period_tax_provider.clj` (`at-corporate-income-tax-provider`), part of **3 / 15** | `[record]` `l10n-at/period_tax_provider.clj` (`at-income-tax-provider`), part of **3 / 15** | `[shipped]` 3 providers (KESt + ImmoESt + corp.), `l10n-at/cgt_*.clj`, **26 / 78** | `[shipped]` 2 providers, `l10n-at/investment_income_*.clj`, **22 / 80** | `[shipped]` `l10n-at/uva.clj` + `tax_provider.clj`, **3 / 21** | `[shipped]` `payroll-at` (mBGM + L16), ADR-086 | `[missing]` | `[missing]` (no Gruppenbesteuerung yet) |
| **BR** | `[shipped]` `l10n-br/cit_{statute,provider}.clj` (IRPJ + CSLL with JCP cap), **13 / 68** | `[shipped]` `l10n-br/period_tax_provider.clj` (IRPF — most LOC of any PIT at 211), **7 / 34** | `[shipped]` `l10n-br/cgt_*.clj`, **22 / 56** | `[shipped]` `l10n-br/investment_income_*.clj`, **25 / 42** | `[partial]` `l10n-br/cst.clj` + `nfe.clj` + `sped.clj` + `periodic_returns.clj` (clearance scaffolding, not a unified VAT provider) | `[shipped]` `payroll-br` (eSocial), ADR-081 | `[missing]` | `[missing]` |
| **IN** | `[shipped]` `l10n-in/cit_{statute,provider}.clj` (with §115BAA `:schedule-override` + 4% cess), **13 / 95** | `[shipped]` `l10n-in/period_tax_provider.clj` (old/new regime via `InIncomeTaxProvider` wrapper), **6 / 39** | `[shipped]` `l10n-in/cgt_*.clj`, **25 / 74** | `[shipped]` `l10n-in/investment_income_*.clj`, **13 / 79** | `[partial]` `l10n-in/irn.clj` + `ewb.clj` + `returns.clj` (GST registration + IRN flow; no unified GST liability provider) | `[shipped]` `payroll-in` (TDS + PF + ESI + PT), ADR-083 | `[missing]` | `[missing]` |
| **MX** | `[record]` `l10n-mx/period_tax_provider.clj` (`mx-isr-corporate-provider`), part of **8 / 44** | `[record]` `l10n-mx/period_tax_provider.clj` (`mx-isr-personal-provider`), part of **8 / 44** | `[shipped]` `l10n-mx/cgt_*.clj`, **19 / 45** | `[shipped]` `l10n-mx/investment_income_*.clj`, **12 / 57** | `[partial]` `l10n-mx/cfdi.clj` + `returns.clj` (no IVA provider) | `[shipped]` `payroll-mx` (CFDI Nómina), ADR-082 | `[missing]` | `[missing]` |
| **CN** | `[record]` `l10n-cn/period_tax_provider.clj` (`cn-eit-provider` flat 25%), part of **6 / 32** | `[record]` `l10n-cn/period_tax_provider.clj` (`cn-iit-provider`), part of **6 / 32** | `[shipped]` `l10n-cn/cgt_*.clj` + bespoke `lat_provider.clj` (Land Appreciation Tax), **17 / 38** + LAT | `[shipped]` 2 providers (IIT + EIT), `l10n-cn/investment_income_*.clj`, **28 / 73** | `[shipped]` `l10n-cn/vat.clj` + `fapiao.clj` + `tax_provider.clj` + `returns.clj`, **7 / 35** | `[shipped]` `payroll-cn` (IIT + 五险一金), ADR-085 | `[partial]` LAT only via `lat_provider.clj` (real estate appreciation — not generic property/wealth) | `[missing]` (no enterprise income tax consolidation) |
| **UK** | `[missing]` deliberate — note 78 §3 deferred pending iXBRL gate | `[missing]` deliberate (same gate) | `[shipped]` `l10n-uk/cgt_{statute,provider}.clj`, **16 / 34** | `[shipped]` `l10n-uk/investment_income_{statute,provider}.clj`, **20 / 71** | `[missing]` no UK-VAT module | `[missing]` — UK payroll (RTI) is in note 78's deferral set | `[missing]` | `[missing]` |

### §1.1 — Verdict line

**Cells:** 8 categories × 12 jurisdictions = **96**. Shipped (any of `[shipped]`,
`[record]`, `[partial]`) = **80**. Missing or `[n/a]` = **16** — of which:

- **12 are property/wealth** (every jurisdiction except CN's bespoke LAT)
- **12 are fiscal-unit** (every jurisdiction; 4 are `[planned]` for note 167)
- minus overlap = **16 unique missing cells**

**Statute-as-data CIT coverage:** 6 / 12 = **50 %** (DE / FR / CA / JP / BR / IN
on ADR-101; US / AT / AU / CN / MX still record-shape; UK deferred). Gap #5
closes this to 11 / 12.

**Per-jurisdiction depth:** test density varies 10× across jurisdictions
(UK 36 deftests / 105 assertions; CA 205 / 704). The thin ones are UK
(deliberate gate) and the record-shape jurisdictions that haven't been pulled
through full statute-as-data coverage.

---

## §2. Substrate seams (provider-to-provider disagreements)

For each seam: scope, evidence, severity, proposed direction. P0 = silently
wrong or substrate-trust-breaking; P1 = friction / consumer-DX-breaking;
P2 = polish / convention drift.

### S1 — FX is not on the tax-emission path
**Severity: P0** · **Scope: every tax provider in every jurisdiction**

- **Evidence**: `grep -lR "kontor.fx" modules/l10n-*/src` returns **zero**
  results. Only kernel files import the FX namespace:
  `src/kontor/{report,fx,consolidation,fx_rate_provider,schema}.clj`.
- **Implication**: `tax_return_posting_builder.clj` provision-tx-data lines
  62-92 take `:commodity` from the provider's record and assume the
  base/liability are already in that commodity. There is no path where a
  `TaxReturnFacts` carrying a USD income amount is translated to EUR before
  the German liability is computed.
- **In practice**: most providers ship a record like
  `DECITProvider [id commodity]` with `:commodity :EUR` hard-coded; the
  base must already be EUR when it arrives. For single-currency taxpayers
  this is fine; for multi-currency book profit (a real scenario — DE GmbH
  with a CHF custody account, US LLC with EUR dividends) it silently
  computes against the wrong base.
- **Why it didn't surface in 11 jurisdictions × ~2k tests**: every fixture
  declares income in the local commodity. The seam is invisible to
  per-jurisdiction tests but appears the moment two jurisdictions meet
  (note 161's two-DB scenario hit a softer version when CW received DE
  dividends in CAD — solved with consumer-side FX, not provider-side).
- **Proposed direction**: extend `kontor.tax-return-posting-builder` to
  accept an `:fx-provider` in ctx; when `TaxReturnFacts` carries a base in
  a non-functional commodity, translate via `kontor.fx/translate` at the
  measurement date before computing liability. Document in a new ADR
  addendum. Cost: ~50 LOC + a kernel test that fails today.

**Status 2026-05-25 — SUBSTRATE FIXED**: kernel primitives shipped in
`kontor.period-tax-provider` (commit pending):
`translate-to-functional`, `translate-amounts-to-functional`,
`monocommodity-facts?`. Ctx contract documents `:fx-provider`. 6 new
deftests pin the substrate behaviour (identity short-circuit,
loud-failure on missing provider, CHF→EUR conversion, multi-commodity
folding, monocommodity check). ADR-099 Addendum (5). Full suite
3050/11683/0.

**Open follow-ups (P1):**
1. `kontor.report/sum-postings` (the marginalize layer) still sums
   amounts regardless of commodity — silent-wrong for mixed-commodity
   postings. Add `:strict-commodity?` opt.
2. Per-l10n adoption sweep: each provider that takes Money inputs (vs
   raw BigDecimal + commodity field) calls `monocommodity-facts?`
   post-construction.

### S2 — Two install entry points per module
**Severity: P0** · **Scope: 11 jurisdictions × {cit, cgt, investment_income}** · **Status: FIXED 2026-05-25 (commit pending)** — T1.W1.2 deleted all 30 provider-side `install-statute!` delegators across the 11 l10n modules. The statute-side `install!` is now the single per-concept entry point; 3 test callers (`l10n-{at,de,br}/test/.../investment_income_provider_test.clj`) re-wired to call `inv-statute/install!` directly.

- **Evidence**: every `*_statute.clj` exports `install!`; every
  `*_provider.clj` exports `install-statute!` which delegates one line to
  the statute file. Both are public.

  Example, `modules/l10n-de/src/kontor/l10n_de/cgt_provider.clj:643-646`:
  ```clojure
  (defn install-statute!
    "Install the DE CGT statute (parameters + provisions) into `conn`."
    [conn]
    (cgt-statute/install! conn))
  ```

- **Implication**: a consumer reading the namespace exports cannot tell
  which to call. The DE preset (`modules/l10n-de/src/kontor/l10n_de/preset.clj:44-50`)
  calls `cit-statute/install!` directly — never the provider-side
  `install-statute!`. So the provider-side wrapper exists, is exported, and
  is not what the canonical consumer-path uses.
- **Why this is P0, not P1**: in a substrate where consumers cargo-cult
  conventions from one jurisdiction to another, exposing two equivalent
  entry points seeds drift. Note 162 P1-3 already surfaced a related
  pattern: BR JCP cap reads 4 ctx keys (an early signal that an "input
  bundle" convention may be warranted).
- **Proposed direction**: pick one. **Recommend**: deprecate the
  provider-side `install-statute!` (it's a no-op delegator), keep the
  statute-side `install!` as the single per-module entry, and add a
  per-module `install-all!` that the preset calls. Same shape across all
  11 jurisdictions.

### S3 — Two `:op` vocabularies in flight (`:base-add` vs `:base-transform-add`)
**Severity: P1** · **Scope: kernel + all six ADR-101 CIT statutes**

- **Evidence**: kernel `src/kontor/statute.clj:423-451` documents the closed
  `:op` set as `:credit | :surtax | :base-add | :base-deduct | :schedule-override`.
  All six CIT statutes use these names. **But** the kernel's tax-concept seed
  (`src/kontor/statute.clj:660-672`) defines `:tax-concept/code
  :base-transform-add` and `:base-transform-deduct`. The `:provision/op`
  values and the `:tax-concept/code` values don't line up.
- **Implication**: a provision tagged `:op :base-add` cannot trivially
  cross-reference its `:tax-concept` (`:base-transform-add`). The
  `kontor.statute/explain` graph walk (ADR-091) would need an internal mapper.
- **Why P1 not P0**: today no code joins these axes, so the divergence is
  invisible. The first time the `:explain` path tries to surface "this
  liability fired the :base-transform-add concept" it'll trip over the
  vocabulary mismatch.
- **Proposed direction**: rename one. Either widen the canonical `:op` set
  to `:base-transform-add | :base-transform-deduct` (more verbose, but
  matches the concept catalogue), or rename the concept seed to
  `:base-add` / `:base-deduct`. **Recommend** the concept-seed rename —
  fewer call sites, shorter ident keywords.

### S4 — `compute-fn` escape hatch used in 2 of 6 CIT providers
**Severity: P1** · **Scope: DE + FR CIT**

- **Evidence**: `grep -l ":provision/compute-fn" modules/l10n-*/src`
  returns DE and FR CIT providers. The other four (CA / JP / BR / IN) stay
  pure predicate + ops.
- **Implication**: the escape hatch exists for the cases where the closed
  predicate vocab can't express a statutory subtlety; using it twice in six
  countries suggests the vocab is sized about right, but the two existing
  uses should be reviewed against the recent surface: were they truly
  inexpressible, or did they pre-date a primitive like `:op :schedule-override`?
- **Proposed direction**: audit the two `compute-fn` sites; if either could
  now be expressed as data-only after Addendum 1, refactor to data. If both
  remain genuinely needed, accept and document them.

### S5 — Provider record name drift
**Severity: P2** · **Scope: every l10n module**

- **Evidence**: surveyed every `defrecord` declaration across
  `*_provider.clj` files. Inconsistent naming:
  - `DECITProvider`, `FRCITProvider`, `INCITProvider`, `JPCITProvider`,
    `BRCITProvider`, `CACITProvider` — uppercase country + `CIT` (CIT
    providers).
  - `DECapitalGainsTaxProvider`, `MXCapitalGainsTaxProvider`,
    `BRCapitalGainsTaxProvider`, `CACapitalGainsTaxProvider`,
    `INCapitalGainsTaxProvider`, `USCapitalGainsTaxProvider`,
    `UKCapitalGainsTaxProvider`, `FRCorporateCapitalGainsTaxProvider`,
    `FRPersonalCapitalGainsTaxProvider`, `AuCapitalGainsTaxProvider`,
    `JpCapitalGainsTaxProvider` — country prefix in mixed case (CGT
    providers; AU + JP are `Au`/`Jp`, others all-upper).
  - `ATKestCgtProvider`, `ATImmoEstProvider`, `ATCorporateCgtProvider`,
    `CnIitCgtProvider`, `CnEitCgtProvider`, `CnLatProvider` — country +
    regime-tag + `Provider`.
  - Tax-rate / posting-builder records (`DeTaxRateProvider`,
    `AtTaxRateProvider`, etc.) use **Pascal-Case** country prefix —
    different convention again.
- **Implication**: a reader can't infer the symbol of `KR`'s CIT provider
  (when/if Korea ships). Three patterns in one substrate.
- **Proposed direction**: standardise on **`<CountryPascal><Regime?><TaxAbbrev>Provider`**
  — e.g. `DeCitProvider`, `AtKestCgtProvider`, `JpCapitalGainsTaxProvider` →
  `JpCgtProvider`. Mass rename behind an `:as-of` cutover. Doc in
  `doc/conventions.md`.

### S6 — `:as-of-valid` not threaded through provider record fields
**Severity: P1** · **Scope: every CIT / CGT / investment-income provider**

- **Evidence**: post-I-17 (note 161), kernel queries default `:as-of-valid`
  to `nil` (treated as wall-clock now). Providers receive ctx and pass
  `:as-of-valid` down to `apply-provisions` correctly; but provider
  **records** (`[id commodity]` / `[id kind commodity statute]`) don't carry
  any bitemporal axis. If a consumer instantiates a provider once and uses it
  for both pre-2025 and post-2025 valid-time queries, the statute is
  re-evaluated each call (correct), but there's no record-level safety net
  if a ctx omits `:as-of-valid`.
- **Implication**: ADR-101 Addendum 2 (`:provision/effective-from`) was
  shipped to make valid-time-aware provision resolution explicit; the
  provider's per-record ctor doesn't enforce that a caller supply ctx with
  the right axis. This is the substrate-side mirror of I-17.
- **Proposed direction**: add `:as-of-valid` (optional, per-call ctx) to a
  documented spec of every `PeriodTaxProvider` ctx. Document the failure
  mode ("missing `:as-of-valid` → "latest provision" semantics, which is
  what you wanted for prospective filings but not for back-restatement").
  Consider a `PeriodTaxProvider/with-as-of-valid` builder for the common
  case where a consumer pins a date.

### S7 — `audit-doc` not attached by any tax provider
**Severity: P1** · **Scope: every tax provider**

- **Evidence**: `grep -lE "audit-doc" modules/l10n-*/src/kontor/l10n_*/cit_provider.clj`
  returns zero. `kontor.tax-return-posting-builder/provision-tx-data` and
  `payment-tx-data` (lines 62-92) emit balanced postings but do not stamp
  `:audit-doc/*` (the legal-doctrine × subject-matter taxonomy lived since
  Stage M).
- **Implication**: a posting like "DE GewSt provision €4,287.50" carries
  no audit-doc reference back to the §-citation that drove it. The
  underlying statute *does* carry citations (e.g. DE-GewStG-§8-Nr-1) on
  the `:provision/source-citation` field; but the *posting* doesn't link
  back. The audit chain is one hop short.
- **Proposed direction**: extend `provision-tx-data` to optionally attach
  `:transaction/audit-doc` referencing the responsible `:provision`
  entity (or a synthesized audit-doc that captures the
  `apply-provisions` return-shape). Cost: schema additive; ~40 LOC.

### S8 — Account routing diverges across providers (per-country chart files all differ)
**Severity: P2** · **Scope: every l10n module**

- **Evidence**: every `l10n-{cc}/.../chart.clj` ships its own account
  vocabulary (SKR04 in DE, OFBiz-style in US, Plan Comptable Général in
  FR, Codice Agrupador in MX, etc.). Per-country this is correct;
  cross-country there is no shared `:account-tag` for "income tax payable"
  (only the country-specific account code). The IRS-XBRL `:concept-iri`
  per ADR-090 would close this; only `account-tag/concept-iri` actually
  uses it today (research note 78).
- **Implication**: a multi-entity report ("sum corporate income tax
  payable across DE GmbH + US LLC + CA Inc.") cannot ride a shared
  classification axis. The marginalize machinery is there
  (`kontor.report/marginalize`); the axis isn't tagged.
- **Proposed direction**: extend the `:concept-iri` seam (ADR-090) into
  `:account/concept-iri` and back-fill per-jurisdiction `:income-tax-payable`,
  `:vat-payable`, `:cit-provision` on the relevant accounts. Cost: schema
  additive; per-jurisdiction wire-up is ~5 LOC each.

### S9 — `kontor.book` adoption is partial
**Severity: P1** · **Scope: 5 of ~11 l10n modules touch `kontor.book`**

- **Evidence**: `grep -lR "kontor.book" modules` returns
  `l10n-ca/cgt_provider.clj`, `l10n-ca/preset.clj`, `l10n-de/preset.clj`,
  `l10n-de/cgt_provider.clj`, `l10n-mx/cgt_provider.clj`,
  `l10n-fr/cgt_provider.clj`, `l10n-fr/investment_income_provider.clj`,
  `l10n-us/cgt_provider.clj`. The kernel side
  (`tax_return_posting_builder.clj`, `sole_proprietor.clj`, `vat_return.clj`,
  `incorporation.clj`) all use `kontor.book`; per-provider adoption is
  uneven.
- **Implication**: F10 (note 161) fixed `kontor.book` verbs to stamp
  `:posting/entity`. A provider that transacts via `kontor.book` gets that
  for free; a provider that builds raw tx-data does not. Per-entity TBs
  in multi-entity scenarios will silently drop the providers' postings if
  they bypass `kontor.book`.
- **Proposed direction**: route every provider's tx-data through
  `kontor.book/entry-tx-data` (or document explicit `:posting/entity`
  stamping in the leaf builder). Add a kernel test that asserts every
  provider's emitted postings carry `:posting/entity` when ctx supplies
  `:entity`.

### S10 — Each module ships its own `install!` for every statute (no per-module orchestrator)
**Severity: P1** · **Scope: every l10n module** · **Status: FIXED 2026-05-25 (commit pending)** — T1.W1.2 added a `preset.clj` with `install-all!` + `create-XX-db` to all 10 modules missing one (`l10n-{at,au,br,cn,fr,in,jp,mx,uk,us}`); DE + CA already had presets. Each preset orders statutes by prerequisite (CIT-first where present), then chart, then default GJ/CR/CD/SJ/PJ journals (localised names where applicable). UK ships GBP commodity inline since it lacks a chart module per note 78 §3.

- **Evidence**: DE has `cit-statute/install!`, `cgt-statute/install!`,
  `investment-income-statute/install!`, `chart/install!`,
  `retention/install!`. The DE preset (`l10n-de/preset.clj`) lists all five
  in one orchestrator. Only DE and CA have a preset; the other ten don't.
- **Implication**: a consumer of l10n-jp has to discover and call five
  `install!`s in the right order with no per-module help. The matrix above
  shows shipped statutes per cell; the preset gap shows it's still
  consumer-DIY for 10 jurisdictions.
- **Proposed direction**: ship `install-all!` per l10n module, even if it's
  just the same body the DE/CA presets already have. Cost: 10 × ~15 LOC.

### S11 — UK is a "thin" module (CGT + investment-income only)
**Severity: P2** · **Scope: l10n-uk**

- **Evidence**: `modules/l10n-uk/src/kontor/l10n_uk/` contains only 4
  files: `cgt_{statute,provider}.clj` + `investment_income_{statute,provider}.clj`.
  No `chart.clj`, no `identifiers.clj`, no `tax_provider.clj`, no VAT, no
  CIT — deliberate per note 78 §3 (UK iXBRL gate). 36 deftests / 105
  assertions vs DE's 93 / 320.
- **Implication**: UK is the only jurisdiction where two of the eight
  categories are shipped but the module is not "filled out" to the depth
  of the other 11. Consumers must understand UK has a deferral, not a
  not-yet.
- **Proposed direction**: document the UK deferral prominently in
  `modules/l10n-uk/README.md` (or create one). Don't ship more until
  iXBRL substrate is in place.

---

## §3. Dead code / abandoned conventions

Primitives that are shipped but minimally used outside their own tests.

### D1 — `kontor.tax-rate-provider` (ADR-071) and `kontor.tax-provider` (ADR-005, legacy)
**Status**: Both still load-bearing, not dead.

- **Evidence**: `grep -lR "kontor.tax-rate-provider"` returns 19 files
  (kernel + l10n-de / l10n-at / l10n-au / l10n-ca / l10n-fr / l10n-jp).
  These are the *invoice-line* tax computation path (per-line VAT, sales
  tax, GST). The `PeriodTaxProvider` is the periodic / entity-level path
  (CIT, PIT, payroll, CGT). They are siblings, not duplicates.
- **However**: the kernel `tax_rate_provider.clj` docstring (line 6) calls
  itself "the rate-determination half" and explicitly notes the legacy
  `kontor.tax-provider/TaxProvider` (ADR-005) was "consequently unused."
  Confirm: `grep -lR "kontor.tax-provider"` — does anything still import
  the legacy single protocol? **Yes**: every `*_tax_provider.clj` file in
  every l10n module declares an `*TaxRateProvider` record that **defrecord
  implements `TaxRateProvider`** (the new protocol). The old `TaxProvider`
  (ADR-005) is not implemented by any code today.
- **Recommendation**: delete `kontor.tax-provider` (ADR-005)'s legacy
  protocol if confirmed unused at grep-level. Mark ADR-005 superseded by
  ADR-071 in `doc/decisions.md`.

### D2 — Record-shape CIT providers in 5 jurisdictions (Gap #5)
**Status**: Used by their own tests only — no integration test crosses them.

- **Evidence**: `l10n-{us,at,au,cn,mx}/period_tax_provider.clj` host the
  record-shape `us-corporate-income-tax-provider`, `at-corporate-...`,
  etc. Searching for these symbols outside their own files: only the
  kernel `test/kontor/cgt_pit_integration_test.clj` references the US
  provider; the other four are reachable only from their per-module
  `period_tax_provider_test.clj`.
- **Implication**: these providers exist and pass their tests but are not
  composed with anything. Gap #5 (CIT migration to ADR-101) is the
  follow-up that gives them statute-as-data parity and pulls them into
  the showcase / integration substrate.
- **Recommendation**: Gap #5 should not just "migrate"; it should also add
  one cross-jurisdiction integration test per migrated jurisdiction
  (modelled on `cgt_pit_integration_test.clj`) so the record-shape
  providers' tests don't continue to live in isolation post-migration.

### D3 — `kontor.commitment` companion
**Status**: Used outside its own tests by 3 files only.

- **Evidence**: `grep -lR "kontor.commitment|:commitment/" --exclude-dir=commitment`
  returns `src/kontor/tax_return_posting_builder.clj`, `src/kontor/book.clj`,
  `modules/disposal/src/kontor/disposal/schema.clj`. These references are
  cross-namespace dependency declarations, not live use. No showcase
  exercises the `:commitment` entity.
- **Recommendation**: not dead — these are kernel-side hooks that future
  consumers (subscription accounting, lease modifications) will use. But
  add a showcase that exercises `:commitment` in the recognise-then-
  fulfill path; the test-coverage signal is currently zero outside the
  companion's own tests. If no showcase appears before Stage T, mark for
  deprecation.

### D4 — `kontor.side-effect.cross` (`:cross-tx/*` schema)
**Status**: Used outside its own files by 1 file only.

- **Evidence**: `grep -lR "kontor.side-effect.cross|:cross-tx" --exclude-dir=side_effect`
  returns `src/kontor/incorporation.clj`, `src/kontor/schema.clj`, plus
  worktree noise. `kontor.incorporation` is the one real consumer.
- **Implication**: the cross-DB saga primitive (ADR-074) ships, has tests
  in `test/kontor/side_effect/cross_test.clj`, but one real consumer is
  thin coverage for a substrate primitive that claimed "cross-DB atomic
  transact" as motivation.
- **Recommendation**: not dead — ADR-074 is structurally required for
  multi-jurisdiction scenarios (the two-DB scenario in note 161 is exactly
  this). Track an explicit showcase using `:cross-tx` in the
  intercompany or treaty path.

---

## §4. Test-coverage sanity

Module totals (deftest / assertion) across every test file in each l10n
module:

| Module | deftest | assertions | LOC of *_provider.clj (CIT+CGT+inv-income+period) |
|---|---:|---:|---:|
| **l10n-de** | 93 | 320 | 279 + 532 + 441 + 78 |
| **l10n-fr** | 130 | 419 | 274 + 740 + 508 + 68 |
| **l10n-ca** | 205 | 704 | 405 + 590 + 547 + 90 |
| **l10n-jp** | 127 | 427 | 406 + 467 + 688 + 262 |
| **l10n-br** | 175 | 571 | 445 + 471 + 652 + 211 |
| **l10n-in** | 147 | 583 | 375 + 740 + 648 + 311 |
| **l10n-us** | 87 | 281 | — + 487 + 319 + 165 |
| **l10n-au** | 127 | 377 | — + 638 + 622 + 106 |
| **l10n-at** | 137 | 435 | — + 850 + 745 + 93 |
| **l10n-cn** | 146 | 457 | — + 488 + 649 + 129 |
| **l10n-mx** | 132 | 410 | — + 595 + 510 + 217 |
| **l10n-uk** | 36 | 105 | — + 408 + 524 + — |

### §4.1 — Coverage findings

- **F-T1**: **CIT test coverage is highly variable.** DE has the fewest CIT
  tests (7 / 31) of any ADR-101 CIT module despite being the reference. IN
  has the most (13 / 95). For a reference template, DE-CIT-7-tests is
  *thin* — the reference jurisdiction should have the most thorough
  coverage so derivative providers can pattern-match. **Recommendation:**
  add ~6 more DE CIT tests covering the worked examples in ADR-104 ¶3
  (BMF §10 / §8b-Abs-5 / §8 / §9 / Soli interactions).
- **F-T2**: **UK is order-of-magnitude smaller.** 36 deftests vs DE's 93 is
  consistent with shipping only CGT + investment-income. Once CIT and VAT
  land for UK (post-iXBRL deferral), expect parity with other Western EU
  modules (~120 deftests).
- **F-T3**: **PIT tests are uneven**: DE has 2 deftests / 9 assertions for
  its full Einkommensteuer; CA has 4 / 17; FR has 2 / 6 — all thin. JP has
  10 / 53, BR has 7 / 34 (more), IN has 6 / 39. **Recommendation**: triage
  PIT to a per-jurisdiction depth audit; DE / FR / CA are conspicuously
  light for what they cover.
- **F-T4**: **No cross-jurisdiction integration test exercises 3+
  providers.** `test/kontor/cgt_pit_integration_test.clj` cross-wires CGT
  + US PIT only. Note 161's two-DB scenario covers DE + CA but lives in a
  research note, not a regression test. Showcase 06 (DE GmbH multi-year)
  is single-jurisdiction. **Recommendation:** promote note 161's scenario
  to `test/kontor/integration/two_db_de_ca_test.clj`.

---

## §5. Design-validation findings (consolidated)

The following ranks §2 + §3 + §4 by impact on substrate confidence.

### P0 (substrate-trust)

1. **S1 — no FX path on tax emission.** The substrate cannot honestly
   claim multi-currency support today; the moment a foreign-currency base
   meets a domestic tax, the math is silently wrong. Fix before any
   consumer ships multi-currency books.
2. **S2 — dual install entry points per module.** Both `install!` (statute
   file) and `install-statute!` (provider file) exist, are public, and
   are not the same thing the canonical preset calls. Consumers will
   choose differently → silent divergence at install time.

### P1 (consistency / DX-blocking)

3. **S3 — `:op :base-add` vs `:tax-concept/code :base-transform-add`**: the
   `apply-provisions` op-keyword vocabulary and the seeded concept-catalogue
   keyword vocabulary don't align (`src/kontor/statute.clj:493-494` vs
   `:660-672`). `kontor.explain` (ADR-091) cannot join across this gap
   without a mapper.
4. **S6 — `:as-of-valid` not in provider record spec.** Per-call ctx
   carries it, but a consumer that omits ctx silently gets "latest" — the
   mirror of I-17 at the provider boundary.
5. **S7 — `:audit-doc` not attached to provider-emitted postings.** The
   audit chain is one hop short; statutes carry citations, postings
   don't link to them.
6. **S9 — `kontor.book` adoption uneven.** 8 files use it, the rest
   bypass to raw tx-data. F10's `:posting/entity` discipline is leaky.
7. **S10 — no per-module `install-all!`.** DE + CA have presets; the
   other 10 don't.
8. **S4 — `:provision/compute-fn` used in DE + FR CIT.** Audit whether
   either can move to data after Addendum 1.
9. **F-T1 — DE-CIT is the reference but the thinnest tests.** Reference
   should be deepest.
10. **F-T3 — PIT depth uneven across DE / FR / CA / IN.**
11. **F-T4 — no 3+ provider cross-jurisdiction regression test.**

### P2 (polish)

12. **S5 — provider record name drift** (three conventions in use).
13. **S8 — account-routing concept-iri seam not extended to `:account/*`.**
14. **S11 — l10n-uk has no README explaining the deferral shape.**
15. **D1 — legacy ADR-005 `kontor.tax-provider` protocol unused.**
16. **D2 — record-shape CIT providers compose with nothing** (Gap #5
    addresses).
17. **D3 — `:commitment` used only by tests and hooks** (showcase needed).
18. **D4 — `:cross-tx/*` used by one consumer** (showcase needed).

---

## §6. Recommended consolidation order

### Tier 1 — substrate-confidence prerequisites

Must happen before more substrate lands.

- **W1.1 — close S1 (FX in tax emission)**. Extend
  `kontor.tax-return-posting-builder` and `PeriodTaxProvider` ctx to thread
  `:fx-provider`; when a `TaxReturnFacts` base/liability commodity differs
  from the entity's functional currency, translate via `kontor.fx`. Add
  a kernel test that fails today (DE GmbH with CHF custody). **Cost: ~50
  LOC + 1 ADR addendum + 1-2 new kernel tests.**
- **W1.2 — close S2 (single install entry per module)**. Deprecate
  provider-side `install-statute!`, keep statute-side `install!`, add
  `install-all!` per l10n module. **Cost: ~10 × 15 LOC + a deprecation
  notice in note 160.**

These two together remove the two P0s and unlock honest cross-jurisdiction
claims.

### Tier 2 — completes the existing picture

Migration of existing patterns to coverage gaps.

- **W2.1 — Gap #5 CIT migration** (US / AT / AU / CN / MX → ADR-101).
  Per jurisdiction: ~400-500 LOC of statute-as-data + ~30-50 LOC provider
  + ~10-15 deftest / 60-95 assertions. **Cost: 5 × ~1k LOC + 5 × ~12
  deftest. Reference: notes 121 + 122 fit-analyses already exist for
  BR + IN; notes 108-111 for DE / FR / JP / CA — no analogue for the
  Gap #5 five. Need fit-analysis notes first per the per-stage rhythm.**
- **W2.2 — PIT depth audit + test fill-in** (DE / FR / CA conspicuously
  thin). **Cost: ~30 LOC + ~10 deftest per jurisdiction × 3 = ~100 LOC
  / 30 deftests total.**
- **W2.3 — close S7 (audit-doc on provider postings)**. Extend
  `provision-tx-data` to attach `:transaction/audit-doc`. **Cost: ~40 LOC
  + 1 kernel test.**
- **W2.4 — close S3 (`:op` vocabulary alignment)**. Rename seeded
  concept-catalogue keywords to `:base-add` / `:base-deduct`. **Cost: ~5
  LOC + ADR-101 Addendum 3.**
- **W2.5 — close S9 (`kontor.book` everywhere)** — route every provider
  through `kontor.book/entry-tx-data` OR document explicit
  `:posting/entity` stamping. **Cost: ~5-10 LOC per provider × ~12 = ~80
  LOC + kernel test asserting `:posting/entity` on every provider-emitted
  posting.**
- **W2.6 — close S10 (per-module `install-all!`)**. Ship for the other
  10 jurisdictions. **Cost: ~150 LOC total.**
- **W2.7 — promote note 161's two-DB scenario to a regression test**.
  **Cost: ~150 LOC.**
- **W2.8 — property/wealth tax coverage triage**. Decide whether kontor
  ships property tax as a per-jurisdiction `PeriodTaxProvider` shape or
  leaves it to consumers. Property tax is the only category that's
  uniformly missing across all 12 jurisdictions. **Cost: research note +
  ADR; defer code until decision lands.**

### Tier 3 — new substrate

New primitives with new design risk.

- **W3.1 — Gap #8 fiscal-unit (`:fiscal-unit/*`, note 167 ADR-108)**.
  Three kernel schema additions, two provider-protocol surfaces, one
  metadata flag, one evaluator helper. **Cost per note 167 §2: ~600 LOC
  kernel + ~150 LOC per pilot jurisdiction (DE Organschaft is the natural
  first). Total ~1k-1.5k LOC.**
- **W3.2 — Investment-income provider × FX × `:audit-doc` integration
  test cross-jurisdiction**. Once W1.1 + W2.3 land, the existing 11
  investment-income providers should compose into a "multi-currency
  portfolio" showcase. **Cost: 1 new showcase notebook + ~200 LOC test
  scaffold.**
- **W3.3 — Gap #4 JP pro-forma enterprise tax** (still pending, large
  corp). **Cost: ~300 LOC per existing ADR-106 pattern.**

### Sequence

- Land W1.1 (S1 FX fix) FIRST. Without it, Gap #5 CIT migration and Gap
  #8 fiscal-unit both inherit a substrate that's silently wrong on
  multi-currency.
- Land W1.2 (S2 install convention) in the same wave — it's tiny and it
  unblocks the per-module-preset uniformity Gap #5 + Gap #8 will need.
- Land Tier 2 in parallel batches (W2.1 Gap #5 is the largest; W2.2-W2.7
  are small).
- Hold Tier 3 (Gap #8 fiscal-unit + Gap #4 JP pro-forma) until Tier 1
  is closed.

---

## §7. Honest scorecard

The tax substrate is **structurally complete** and **operationally
incomplete**. The structure — `PeriodTaxProvider` + statute-as-data
(`:provision` / `:regime` / `:parameter`) + a closed `:op` vocabulary +
`apply-provisions` evaluator + 11 jurisdictions of CGT + 11 of
investment-income + 6 of CIT — is genuinely the substrate the McComb /
Catala / OpenFisca research arc promised. The naming drift (S5), the
dual install entry points (S2), the per-jurisdiction asymmetry in
test depth (F-T1, F-T3), and the missing `kontor.fx` on the emission
path (S1) are all artifacts of building 11 jurisdictions × 4 tax-types
in ~2 months — the kind of debt that accumulates from real velocity, not
the kind that signals fundamental design flaws.

**Mature:** ADR-099 PeriodTaxProvider trio + ADR-101 statute-as-data +
ADR-102/103 disposal substrate + the 11-jurisdiction CGT and
investment-income sweep. These are ready for a consumer to depend on.

**Shaky:**
- Multi-currency tax (S1) — the biggest substrate-trust gap.
- Per-module install convention (S2) — DE + CA have presets, the other
  10 don't; consumers must DIY.
- 5 record-shape CIT providers (US / AT / AU / CN / MX) outside the
  statute-as-data path (Gap #5).
- Property / wealth / land tax (essentially absent across 11 of 12
  jurisdictions).
- Fiscal-unit / group-tax (Gap #8 — substrate designed, no code yet).

**Biggest consumer-side risk if shipped as-of-today:** a German GmbH with
a CHF brokerage account, a US LLC with EUR receivables, or any
intercompany scenario between two jurisdictions with different
functional currencies. The substrate **looks like** it supports
multi-currency (every record carries a `:commodity` field), but no
provider invokes `kontor.fx` to bridge mismatched commodities. The
correct number for "US LLC owes US-IRS on EUR dividend income" is
silently wrong — and not by a small amount, because the exchange
swing across a tax year can be ±10%. Close S1 before a consumer
discovers this in production.

**Tier-1 priority recommendation, one sentence:** ship W1.1 (FX in
tax emission) before anything else. Everything else is consistency
debt; W1.1 is correctness debt.

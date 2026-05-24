---
date: 2026-05-24
title: 114 — UK capital-gains-tax substrate fit — TCGA 1992 (individuals) + CTA 2010 (corporate chargeable gains)
status: research-before for note-104 Phase 3 — the `:disposal` companion (ADR-101) + `uk-cgt-provider` siblings
audience: maintainer + the UK-CGT implementation agent
---

# 114 — UK capital-gains-tax substrate fit

Phase 3 (note 107) ships a `kontor-disposal` companion (ADR-101) and per-
jurisdiction CGT providers. This note assesses the **UK** fit. UK CGT is
the most demanding capital-gains substrate the project will touch because
it carries TWO regimes with disjoint mechanics: **TCGA 1992** for
individuals/trustees and **CTA 2010 / TCGA 1992 (with Sch 7AC, the
indexation rules, etc.)** for corporations. Two features absent from US /
DE / FR / JP appear here: **indexation allowance** (a per-acquisition
RPI-based cost-base adjustment, frozen at Dec-2017 for corps; abolished
April 2008 for individuals) and **Business Asset Disposal Relief / BADR**
(lifetime-cumulative cap that spans disposals across years).

---

## §1. UK CGT regime summary

### 1.1 Individual CGT (TCGA 1992)

Charging Act: **TCGA 1992**. Computation is separate from income tax but
the **band** allocation references the taxpayer's income-tax bands —
basic-rate band vs higher / additional band — so the CGT provider must
read the income-tax provider's residual band capacity for the year.

- **Rates** (post Autumn-Budget 30 Oct 2024 — immediate effect for disposals
  on or after that date; rossmartin.co.uk Autumn-Budget-2024 CGT update):
  - Basic-rate band: **18 %** non-residential / residential alike (the
    pre-2024 10 % non-property tier was abolished).
  - Higher / additional band: **24 %** for both classes.
  - The 2016-introduced "residential surcharge" survived in name only —
    the rates aligned upward at the same numeric values.
  - **Carried interest**: single 32 % rate from 6 Apr 2025 (separate
    rate-table row, not a BADR election).
- **Annual exempt amount (AEA)**: **£3,000** for 2024/25 (was £6,000 in
  2023/24; £12,300 through 2022/23 — reduced by the Sunak government via
  Autumn Statement 2022). Deducted from net gains before applying the
  rate; cannot be carried forward (gov.uk CGT rates and allowances).
- **Business Asset Disposal Relief (BADR)** — TCGA s169H et seq.,
  formerly Entrepreneurs' Relief. Reduced rate on first £1M *lifetime*
  of qualifying disposals (sale of an unincorporated business, of
  shares/securities in an own personal trading company holding ≥ 5 % of
  ordinary share capital + 5 % of voting rights + 5 % entitlement to
  profits/assets, held for ≥ 2 years). Rate trajectory:
  - 2024/25: **10 %**
  - 2025/26: **14 %** (from 6 Apr 2025)
  - 2026/27 onwards: **18 %** (from 6 Apr 2026)
  Lifetime limit £1M was £10M pre-March 2020. Crucially **lifetime-
  cumulative across all of the taxpayer's prior BADR claims** —
  state that does NOT live in the current year's books.
- **Investors' Relief** (TCGA s169VA et seq.) — sibling of BADR for
  qualifying unlisted trading-company shares held ≥ 3 years by a
  non-employee. Same rate trajectory as BADR (10 → 14 → 18). Lifetime
  limit **slashed from £10M to £1M from 30 Oct 2024** (Finance Bill
  2024-25; gov.uk Investors' Relief — reduction in the lifetime limit).
- **Rollover relief (TCGA s152)** — defers gain on a business-asset
  disposal where the proceeds are reinvested in a replacement
  qualifying business asset within 12 months before / 36 months after.
  Mirrors DE §6b EStG.
- **Holdover relief (TCGA s165 + Sch 7)** — gift-of-business-asset
  relief; the donor's gain is held over into the donee's base cost.
- **Negligible value claim** (TCGA s24(2)) — taxpayer elects to treat a
  worthless asset as disposed of at nil value, crystallising a loss
  without a sale.
- **Capital losses** — set against capital gains only; carry forward
  indefinite; **clogged-loss rule** (TCGA s18) restricts losses on
  disposals to connected persons to gains on later disposals to the
  same connected person.

### 1.2 Corporate chargeable gains (CTA 2010, computed under TCGA 1992)

Corporations **do not pay CGT**. Gains are computed under TCGA 1992
("chargeable gains") and folded into the corporation tax base under CTA
2010 — taxed at the standard corporation-tax rates (25 % main, 19 %
small profits for FY 2024; 26.5 % marginal slice). **No annual exempt
amount; no separate return** — chargeable gains flow into the CT600 as
a line of the corporation tax computation.

Corporate-only specials:

- **Indexation allowance** — TCGA s53 + s54, *frozen* by Finance Act
  2018 with effect from 1 Jan 2018. For disposals on or after that date,
  the RPI-based factor is computed from the acquisition month's RPI to
  **December 2017's RPI** (whichever is earlier than the actual disposal
  month). Where the asset was acquired *after* December 2017, no
  indexation is available. Indexation can reduce a gain to nil but
  **cannot create or increase a loss** (gofile.co.uk / Practical Law /
  HMRC CG17207).
- **Substantial Shareholdings Exemption (SSE)** — TCGA Sch 7AC. A
  corporate disposal of ≥ 10 % of ordinary share capital (also ≥ 10 %
  entitlement to profits and assets) in a trading company, **held
  throughout 12 months in the 6 years preceding disposal**, is exempt
  from chargeable-gains tax. UK's §8b-KStG analogue. The 12-month
  window may be aggregated from non-contiguous periods of trade-asset
  use (HMRC CG53080C).
- **Group relief for chargeable gains** (TCGA s171) — intra-75 %-group
  transfers are deemed to be at no-gain / no-loss; the transferee
  inherits the transferor's base cost + indexation history.

### 1.3 Filing surface

Individuals: report gains on the SA108 (Self Assessment Capital Gains
Summary) for non-property; UK-resident property has a separate 60-day
in-year CGT return + payment. Corporations: chargeable gains roll into
CT600 boxes 210/215/220 via the standard corporation-tax computation.

---

## §2. Worked examples

### 2.1 Individual — BADR-qualifying share sale (2024/25)

Source — adapted from the Bird & Bird BADR briefing
(`twobirds.com/.../business-asset-disposal-relief-11,-d-,24.pdf`,
Nov 2024) and gov.uk's HS275 helpsheet pattern:

- Jane sells 100 % of her shares in JaneCo Ltd (a trading company she
  founded; she is the sole director-shareholder, held since 2018) on
  20 Nov 2024 for £1,400,000. Acquisition cost = £100. No prior BADR
  claims.
- **Qualifying conditions met**: ≥ 5 % shareholding (100 % is ≥ 5 %),
  ≥ 2-year holding (6 years ≥ 2), trading-company status, personal
  company. BADR-eligible.
- Gain = £1,400,000 − £100 = £1,399,900.
- AEA = £3,000 → net gain = £1,396,900.
- **BADR slice**: first £1,000,000 at 10 % = £100,000. (BADR consumed:
  £1,000,000 of the £1M lifetime — fully used).
- **Standard slice**: remaining £396,900 at 24 % (Jane is a
  higher-rate taxpayer) = £95,256.
- **Total CGT**: £195,256.

A second disposal in a later year would attract **no BADR** — the
lifetime cap is exhausted. This is the cross-year cumulative state §3
addresses.

### 2.2 Corporate — indexed gain on a long-held commercial property

Source — gofile.co.uk indexation worked example pattern, gov.uk
"Corporation Tax on chargeable gains: Indexation Allowance December
2017" table:

- ABC Ltd bought a commercial freehold in April 2005 for £200,000.
  RPI April 2005 = 191.6.
- ABC Ltd sells the freehold in September 2025 for £450,000 (disposal
  costs £10,000).
- **Indexation factor** = (RPI Dec-2017 − RPI Apr-2005) ÷ RPI Apr-2005
  = (278.1 − 191.6) ÷ 191.6 = **0.451** (rounded to 3 dp).
- **Indexation allowance** = £200,000 × 0.451 = £90,200.
- **Indexed cost** = £200,000 + £90,200 + £10,000 disposal costs =
  £300,200.
- **Chargeable gain** = £450,000 − £300,200 = £149,800.
- Folded into ABC Ltd's FY2025 corporation tax computation; taxed at
  the marginal-relief or main 25 % rate per the company's profit
  bracket.

If the freehold had been acquired in March 2018, the indexation factor
would have been **zero** — the freeze means no indexation accrues for
post-Dec-2017 acquisitions.

---

## §3. `:disposal` schema fit

The note-107 §3.2 / ADR-101 proposed schema reads (kernel-untouched
companion):

```
:disposal/subject         polymorphic ref
:disposal/subject-kind    enum (:fixed-asset / :participation / :inventory / :intangible / :business-segment)
:disposal/kind            enum (:sale / :incorporation-contribution / :abandonment / :gift / :conversion / :distribution-in-kind)
:disposal/proceeds        Money
:disposal/basis           Money
:disposal/holding-period  enum (:short / :long / :n-a)
:disposal/realizing-tx    edge to :transaction
:disposal/audit-doc       edge to :audit-doc
```

### 3.1 What carries through cleanly

- `:disposal/subject` + `:disposal/subject-kind` — `:participation`
  covers UK share disposals (BADR / Investors' Relief / SSE),
  `:fixed-asset` covers the ABC-Ltd property example, `:business-segment`
  covers a sole-trader business sale into BADR. **Sufficient.**
- `:disposal/kind` — UK has no kind not already in the enum (gifts run
  through `:gift` + a holdover-relief flag; negligible-value claim is
  `:abandonment` + a `:nil` proceeds; share-for-share exchanges are
  `:conversion`). **Sufficient.**
- `:disposal/proceeds` + `:disposal/basis` as Money — matches HMRC's
  computation surface. **Sufficient.**
- `:disposal/realizing-tx` + `:disposal/audit-doc` — the GL settling
  entry + the SPA / contract of sale / negligible-value-claim letter.
  **Sufficient.**

### 3.2 What is MISSING for indexation allowance

Indexation needs **`:acquired-on` (instant) on the disposal entity**.
Today the schema lacks it — the proposed `:disposal/holding-period`
enum (`:short / :long / :n-a`) is a US-shaped denorm (the US 1-year
short/long line) that is too coarse for the UK. Concretely:

- The CT computation needs the **acquisition month's RPI**, which
  requires the acquisition month (`:acquired-on`).
- For a corporate share disposal, indexation runs from each
  *acquisition tranche's* month — a single holding may have been built
  up from many purchases. The disposal entity needs to either carry
  multiple `:acquired-on` tranches or point at a `:lot` ref whose
  acquisition history lives elsewhere.

**Recommendation**: add **`:disposal/acquired-on`** (single instant —
the cost-base date), and let multi-tranche cases emit ONE `:disposal`
per tranche (mechanically equivalent to FIFO/specific-id under ADR-029's
`CostingProvider` + the existing `:lot/*` schema). Keep
`:disposal/holding-period` for the simple cases the US uses; the UK
provider reads `(:acquired-on, :effective-date)` directly and ignores
the enum.

### 3.3 What is MISSING for BADR lifetime-cumulative tracking

The £1M BADR lifetime cap **spans disposals across years**, often
across decades. State that lives outside the current-year `:disposal`
record:

- The CGT provider needs **cumulative prior BADR base claimed by this
  taxpayer**, summed over all `:disposal`s back to 2008.
- A pure substrate would compute this each call by querying every
  prior `:disposal` flagged BADR. **Workable** — datalog over the
  companion's `:disposal/badr-claimed-amount` is one `(d/q ...)` —
  but expensive at the n-th year, and fragile if the taxpayer moved
  jurisdictions or kept earlier disposals outside kontor.

**Recommendation: TWO complementary mechanisms** (the substrate
supports both; the implementer picks per consumer):

1. **In-book cumulative tracking** — add
   `:disposal/badr-claimed-amount` (Money) on the disposal entity (the
   BADR slice consumed at this disposal). The provider sums all prior
   `:disposal/badr-claimed-amount` for the taxpayer at compute time.
   Idiomatic; works only if every prior BADR claim is in the same
   kontor DB.
2. **Tax-unit carry-in via `:inputs`** — exactly the
   `:capital-loss-carryforward` pattern shipped under ADR-099 addendum
   / note 103 §3a. The `PeriodTaxProvider` already accepts
   `:inputs {:capital-loss-carryforward {:short ... :long ...}}` —
   add a sibling key `:inputs {:badr-lifetime-claimed <Money>}` for
   pre-kontor history. The provider sums `(in-book + carry-in)` and
   caps the BADR slice at `(£1M − sum)`.

The `:tax-unit` slot in `ctx` (note 102 §9-D / FR quotient familial) is
the right place for the carry-in. **No new substrate**; the
`:badr-lifetime-claimed` key is jurisdiction-specific data the UK
provider reads. Document it; do not bake it into the kernel.

Same pattern applies to Investors' Relief — sibling `:inputs
{:investors-relief-lifetime-claimed <Money>}`.

### 3.4 What else the UK stresses

- **Annual exempt amount (AEA)** — provider-config constant
  (£3,000 in 2024/25). Annual-rate-bundle posture (ADR-015): the AEA
  for the assessment year is a config value of the provider instance.
  Schema-free.
- **SSE election / 12-month substantial-shareholding test** — the
  consumer asserts SSE eligibility when emitting the corporate disposal
  (the provider treats SSE-flagged disposals as exempt — gain dropped
  from the CT computation). The substrate question: where does the SSE
  flag live? Cleanest: a `:disposal/exemption-claimed` keyword set
  (`:uk-sse | :uk-rollover-s152 | :uk-holdover-s165 | …`) — schema-free
  jurisdiction-namespaced data on the disposal. The UK CT provider
  reads this set; other jurisdictions ignore.
- **Connected-persons "clogged" losses** — the disposal entity needs
  to know the buyer is a connected person (a TCGA s286 relation). The
  existing `:disposal/counterparty` is a `:partner` ref; a sibling
  `:partner/connected-to` set (or a `kontor-disposal`-resident
  `:connected-relation` edge) would carry the connection. Defer to a
  follow-up — Phase 3 ships without connected-persons modelling and
  documents the gap.

---

## §4. Data gaps — concrete list

| # | Gap | Required for | Recommendation |
|---|---|---|---|
| G1 | `:disposal/acquired-on` (instant) | Indexation allowance (corp); BADR 2-yr test; Investors' Relief 3-yr test; SSE 12-mo test | **ADD** to the ADR-101 schema |
| G2 | Multi-tranche acquisition history | Indexation on partial disposal of a share holding | Use ADR-029 `:lot/*` + emit one `:disposal` per tranche |
| G3 | BADR lifetime-claimed carry-in | BADR cap enforcement across years / pre-kontor history | **NOT schema** — `:inputs :badr-lifetime-claimed` (Money) in the `:tax-unit` ctx; mirror `:capital-loss-carryforward` pattern |
| G4 | RPI lookup table — monthly, 1982-03 to 2017-12 (and onwards for historical research) | Indexation factor | **`IndexationProvider` protocol** (§5.3) — analogous to `FxRateProvider` |
| G5 | Annual exempt amount per year | Individual CGT | Provider-config constant (ADR-015 annual bundle) |
| G6 | Income-tax band-residual lookup | Choosing 18 % vs 24 % rate for an individual | Provider reads the UK PIT provider's emitted `TaxReturnFacts` for the year; no schema |
| G7 | `:disposal/exemption-claimed` set | SSE / s152 / s165 election | **ADD** to ADR-101 — a `:db.cardinality/many` keyword set |
| G8 | Connected-persons relation | s18 clogged-loss restriction | **DEFER** — document; not Phase-3 critical |
| G9 | Negligible-value claim date | s24(2) crystallisation event | `:disposal/kind :abandonment` + the existing `:audit-doc` carries the claim letter — no new attr |

---

## §5. `uk-cgt-provider` sketch — TWO providers, sibling shape

The clean fit is the **same shape US 1040 Schedule D uses** to feed Form
1040 (per note 102 §7 / the hybrid mention of `:capital-gains-tax`):
treat individual CGT as a standalone `PeriodTaxProvider`, treat
corporate chargeable-gains as `:inputs` feeding the corporation tax
provider (Phase 3 ADR-103-equivalent for the UK CT). The two providers
share NO state; each is independently testable.

### 5.1 `uk-individual-cgt-provider` (standalone `PeriodTaxProvider`)

```clojure
(ns kontor.l10n-uk.cgt
  "UK individual CGT — TCGA 1992. A PeriodTaxProvider component of
   :capital-gains-tax (note 102 enum, already shipped). Reads
   :disposal entities for the period; applies AEA; applies BADR slice
   at the BADR rate up to the lifetime cap; remainder at 18 % / 24 %
   per the income-tax-band residual."
  ...)

(defrecord UkIndividualCgtProvider
  [id year                          ;; assessment year, drives the rate-bundle
   aea                              ;; £3,000 for 2024/25
   badr-rate badr-lifetime-cap      ;; (0.10 / 1_000_000) for 2024/25
   ir-rate ir-lifetime-cap          ;; investors' relief sibling
   basic-rate higher-rate           ;; (0.18 / 0.24) for 2024/25
   authority commodity statute]
  ptp/PeriodTaxProvider
  (period-tax-facts [_ {:keys [entity period inputs]}]
    (let [disposals (query-disposals db entity period {:kind-in #{:sale ...}})
          gross     (reduce + 0M (map realized-gain-loss disposals))
          losses    (reduce + 0M (or (:inputs :capital-loss-carryforward) 0))
          net       (- gross losses)
          after-aea (max 0M (- net aea))
          {:keys [badr-slice ir-slice rest]}
            (allocate-by-relief disposals after-aea
                                {:badr-already-used (or (:badr-lifetime-claimed inputs) 0M)
                                 :badr-cap          badr-lifetime-cap
                                 :ir-already-used   (or (:investors-relief-lifetime-claimed inputs) 0M)
                                 :ir-cap            ir-lifetime-cap})
          basic-resid (basic-band-residual (:income-tax-facts inputs))
          basic-slice (min rest basic-resid)
          higher-slice (- rest basic-slice)
          liability    (+ (* badr-slice badr-rate)
                          (* ir-slice   ir-rate)
                          (* basic-slice basic-rate)
                          (* higher-slice higher-rate))]
      (ptp/tax-return-facts
        {:entity entity :period period
         :jurisdiction {:country :uk :authority :hmrc}
         :functional-commodity commodity
         :components
         [{:kind :capital-gains-tax
           :authority :hmrc
           :base (money/money after-aea commodity)
           :gross-liability (money/money liability commodity)
           :liability       (money/money liability commodity)
           :line-items [...]
           :provenance {:provider-id id :statute "TCGA 1992 / FA 2018 / FA 2024"}}]}))))
```

Notes:

- Per-year **rate bundle**: `2024/25 → {:aea 3000M :badr-rate 0.10M
  :basic-rate 0.18M :higher-rate 0.24M}`; `2025/26 → :badr-rate 0.14M`;
  `2026/27 → :badr-rate 0.18M`. ADR-015's annual-bundle posture.
- **Income-tax band residual** is read from a sibling PIT provider's
  output that the consumer threads via `:inputs :income-tax-facts`.
  No new substrate.
- The BADR slice is enforced via `allocate-by-relief` reading
  `:inputs :badr-lifetime-claimed`. Pure Clojure; no schema.

### 5.2 `uk-corporate-chargeable-gains-feeder` (NOT a provider — feeds the CT provider)

Corporate gains are **not their own period tax** — they fold into the
CT600. The cleanest substrate-fit is a **base-transform input** to the
CT provider:

```clojure
;; consumer wires:
(period-tax-facts uk-ct-provider
  {:entity ent :period p
   :inputs {:base-transform
            {:transform/type :adjustments
             :additions   [(corporate-chargeable-gains-for db ent p
                                                            {:sse-elected sse-ids
                                                             :indexation-provider idx})]
             :deductions  []}}})
```

`corporate-chargeable-gains-for` is a UK-l10n helper (not a
`PeriodTaxProvider`):

1. Query `:disposal`s for `entity × period`, excluding SSE-elected.
2. For each, compute `gain = proceeds − basis − indexation`, where
   `indexation = basis × (IndexationProvider.factor(:acquired-on))`
   capped at zero loss (TCGA s53(2A) — indexation can't create a loss).
3. Aggregate; the substrate's existing `:adjustments` base-transform
   folds the aggregate into the CT base.

This mirrors **US Form 1040 Schedule D feeding 1040** — exactly the
maintainer-suggested pattern. **No new substrate**; the
`:base-transform :adjustments` is shipped.

### 5.3 `IndexationProvider` — is it needed?

The RPI table is **~430 monthly samples** (Mar 1982 to Dec 2017). It is:

- **immutable** (Dec 2017 is the freeze date — no future samples will
  ever be added by this regime);
- **public-domain ONS data** (UK Office for National Statistics
  publishes RPI; consumer-redistributable);
- **read by exactly one provider** (the UK corporate gains feeder).

The conservative posture mirrors the **FX-rate decision**: ECB rates
are public, license-clean, and `kontor.fx-rate-provider` ships an
adapter (`EcbReferenceRatesProvider`) over `StaticTableProvider`
without bundling the dataset. Same posture here:

**Recommendation**: ship a small **`IndexationProvider` protocol** in
the UK l10n module (NOT the kernel) — `(factor [this acquired-on])` →
BigDecimal — and a `StaticTableIndexationProvider` reading `:rpi/*`
attrs from the connected db. The UK module ships the ingest helper
(`ingest-uk-rpi-csv-rows!`) and the schema for `:rpi/*`. **No kernel
addition** — by the same reasoning the kernel does not ship monthly
FX rates: indexation data is l10n content.

Defining the protocol in the UK module rather than the kernel is also
defensible because **no other jurisdiction needs indexation** — IT's
abolition in 2008 (individuals) + 2017 (corps) was a unique UK reform.
A future Brazilian or Israeli inflation-indexation regime would
introduce its own provider; abstracting prematurely is not warranted.

If a second jurisdiction later needs indexation, the protocol promotes
to the kernel under an ADR. Today, **l10n-resident is the right home.**

### 5.4 `:tax-unit` carry-in for lifetime-cumulative state

The `:tax-unit` in `ctx` (ADR-099 / note 102 §9-D) carries the
out-of-books state the BADR/Investors'-Relief cap needs:

```clojure
{:tax-unit
 {:taxpayer-id     "NINO QQ 12 34 56 A"
  :badr-lifetime-claimed              7_500_00M   ;; £7,500 prior
  :investors-relief-lifetime-claimed       0M
  :income-tax-facts <UK PIT TaxReturnFacts for the year>}}
```

The provider reads these via `(:tax-unit context)`. The consumer is
responsible for keeping the carry-in correct (typically by querying
prior `:disposal`s + adding manual pre-kontor history). The same
discipline shipped under ADR-099 / note 103 §3a for capital-loss
carry-in.

**Bottom line on §5**: the substrate fits. The two stresses (indexation,
lifetime BADR) are solved by an l10n-resident `IndexationProvider` +
the existing `:inputs :tax-unit` carry-in pattern. Schema additions
are minimal: `:disposal/acquired-on` (G1) + `:disposal/exemption-claimed`
(G7). No kernel change.

---

## §6. Sources

UK government (HMRC / legislation):

- **TCGA 1992** Schedule 7AC (SSE): https://www.legislation.gov.uk/ukpga/1992/12/schedule/7AC
- **HMRC CGT rates and allowances**: https://www.gov.uk/guidance/capital-gains-tax-rates-and-allowances
- **HMRC Capital Gains Manual CG17207** (indexation history): https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg17207
- **HMRC CG53078** (SSE 12-month requirement): https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg53078
- **HMRC CG53080C** (SSE aggregation): https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg53080c
- **HMRC CG51620 / CG51622** (corporate share-identification, s104 holdings): https://www.gov.uk/hmrc-internal-manuals/capital-gains-manual/cg51620
- **Investors' Relief lifetime-limit reduction (Finance Bill 2024-25)**: https://www.gov.uk/government/publications/capital-gains-tax-investors-relief-lifetime-limit-reduction/capital-gains-tax-investors-relief-reduction-in-the-lifetime-limit
- **Investors' Relief 2025 (HS308)**: https://www.gov.uk/government/publications/investors-relief-2020-hs308/investors-relief-2025-hs308
- **Indexation Allowance December 2017** (the frozen factor table): https://www.gov.uk/government/publications/corporation-tax-on-chargeable-gains-indexation-allowance-2017/indexation-allowance-december-2017

Practitioner / professional analysis:

- **House of Commons Library SN05572** — CGT recent developments: https://commonslibrary.parliament.uk/research-briefings/sn05572/
- **KPMG TaxNewsFlash** — Autumn-Budget-2024 CGT rate changes + anti-forestalling: https://kpmg.com/us/en/taxnewsflash/news/2025/01/tnf-uk-capital-gains-tax-rate-changes-accompanied-anti-forestalling-measures.html
- **Deloitte Taxscape** — Autumn-Budget-2024 CGT/BADR/IR: https://taxscape.deloitte.com/measures-autumn-budget-2024/cgt-rates-and-badr-and-investors-relief.aspx
- **rossmartin.co.uk** — Autumn Budget 2024 CGT: https://www.rossmartin.co.uk/autumn-budget-2024/8057-capital-gains-tax-cgt-autumn-budget-2024
- **Bird & Bird** — BADR briefing Nov 2024 (the §2.1 worked example pattern): https://www.twobirds.com/-/media/new-website-content/pdfs/capabilities/international-hr/2024-employee-incentives-and-benefits/business-asset-disposal-relief-11,-d-,24.pdf
- **gofile.co.uk** — Indexation allowance (companies) — the §2.2 worked example pattern: https://gofile.co.uk/knowledgebase/capital-gains-tax/indexation-allowance/
- **Practical Law (Thomson Reuters)** — Indexation allowance: https://uk.practicallaw.thomsonreuters.com/9-107-6711
- **Informaccounting** — Corporate gains, end of indexation allowance: https://www.informaccounting.co.uk/blog/corporate-gains-end-of-indexation-allowance
- **ICAEW Taxline** — Investors' relief, stepping out of the shadows (2025): https://www.icaew.com/technical/tax/tax-faculty/taxline/articles/2025/investors-relief-stepping-out-of-the-shadows

kontor substrate references:

- `src/kontor/personal_income_tax.clj` — PIT provider shape the UK CGT
  sibling mirrors.
- `src/kontor/corporate_income_tax.clj` — the `:base-transform` slot
  that carries the corporate chargeable-gains feeder output.
- `src/kontor/period_tax_provider.clj` — `:capital-gains-tax` is in the
  closed `period-tax-kinds` enum (note 102 §7 / §2.1).
- `src/kontor/tax_schedule.clj` — `:progressive-bracket` + `:sum`
  cover the BADR-slice + standard-rate-band fan-out.
- `src/kontor/fx_rate_provider.clj` — the protocol-shape precedent the
  `IndexationProvider` mirrors (StaticTable, l10n adapter, customer
  ingest).
- `doc/research/107-phase-3-incorporation-and-disposal.md` §3 — the
  `:disposal` companion this note's G1/G7 add to.
- `doc/research/102-period-tax-provider-design.md` §7 + §9-D —
  `:capital-gains-tax` hybrid placement; `:tax-unit` ctx carry-in.
- `doc/research/103-period-tax-coverage-proof.md` §3a — the
  `:capital-loss-carryforward` `:inputs` pattern that the BADR
  carry-in mirrors.
- `doc/research/104-tax-completion-individual-to-corporation.md` §2 —
  CGT is named as the "deferred" gap whose data model arrives with
  `:disposal/*`.

---

End of note 114.

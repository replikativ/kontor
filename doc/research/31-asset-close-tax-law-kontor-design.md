# 31 — Fixed-asset depreciation & year-end close: regulation dimension + `kontor-asset` design

**Stage L′ research-before — the tax-law / regulation agent.**
Sibling agents study Odoo, Tryton, and SAP/NetSuite/Oracle as reference implementations; this note studies how depreciation ("Abschreibungen") and year-end close ("Jahresabschluss") are *mandated by statute* across jurisdictions, and proposes how `kontor-asset` should *reflect* that regulation onto kontor's existing substrate.

Date: 2026-05-14. Source: direct (primary-source statute/tax-authority citations + kontor source at file:line). Verified: medium-high — regulation citations are to the controlling statute; the German degressive-AfA windows are time-sensitive and re-flagged for re-research before any l10n-de ships.

---

## 0. Executive summary

1. **The regulated content is almost entirely *data*, not *mechanism*.** Useful-life tables (DE AfA-Tabellen), recovery classes (US MACRS, CA CCA classes), low-value thresholds (DE GWG €800/€1000), mandated conventions (half-year, mid-quarter, mid-month), special-depreciation windows (DE §7g, §7 Abs. 2 degressive reintroductions, US bonus depreciation) — all of these are jurisdiction tables that belong in `kontor-l10n-<cc>`. The kernel/companion ships the *mechanism*: an `:asset` entity, a `DepreciationProvider` protocol seam, and the wiring onto `:schedule` / `kontor.closing` / `:period`. This is exactly the ADR-005 split (`TaxProvider` = mechanism in kernel; rate data = l10n) applied one layer up.

2. **The parallel-book problem is solved by `:ledger`, not a new concept, and not `:valuation-book`.** German Handelsbilanz vs Steuerbilanz, US book vs tax, IFRS vs local GAAP — one physical asset, N depreciation schedules — is *the* IFRS-16-vs-ASC-842 case ADR-021 was designed for. A "depreciation area" (SAP's term) **is a kontor `:ledger`**. `:valuation-book` is the wrong axis (it is about *inventory cost basis selection*, ADR-027); the depreciation parallel is structurally identical to GAAP-vs-tax journal entries, which ADR-021 explicitly calls out (`doc/decisions.md:1012`).

3. **`kontor-asset` ships a `DepreciationProvider` protocol** — a direct sibling of `TaxProvider` (ADR-005) and `CostingProvider` (ADR-029). The companion ships straight-line / declining-balance / sum-of-years-digits / units-of-production built-ins; l10n modules supply MACRS, AfA-degressive, CCA-declining-balance, full-expensing impls. The schedule's per-period amount is computed by the provider — `:schedule` deliberately does not compute amounts (`src/kontor/schedule.clj:12-16`).

4. **`kontor-asset` is a companion module** (`modules/asset/`), not kernel — it follows the `kontor-sales` / `kontor-procurement` precedent. It needs ~6 new entities, a provider protocol, ~4 ADRs.

5. **`kontor.closing` + `kontor.financial-statements` cover the year-end *mechanics* but Stage L′ must add the Anlagengitter** (asset history sheet — a statutory Jahresabschluss component under HGB §284 Abs. 3) as a *report definition over `:asset` + occurrence history*, and the **cash-flow statement / statement-of-changes-in-equity** as additional `compute-statement`-style report engines. The Anlagengitter is kernel-adjacent (it is generic asset-roll-forward arithmetic); the *line layout* of all of these stays l10n.

6. **Effective-dated depreciation rules are ADR-026 applied verbatim.** The German degressive-AfA reintroductions (degressive AfA available for movables acquired 2020-01-01..2022-12-31, then again 2024-04-01..2025-12-31, with the rate ceiling itself changing between windows) are the proof case: the rule in force is a function of the asset's *acquisition date* (a valid-time concept). l10n-de ships N effective-windowed depreciation-rule rows; the provider selects by `:asset/acquisition-date`.

---

## 1. The regulated-content map

For every jurisdiction the three regulated knobs are the same triple:

- **(a) the method** — straight-line, declining-balance, SYD, units-of-production, immediate expense;
- **(b) the useful life / rate** — how many years, or what % per year;
- **(c) the convention** — when the depreciation clock starts (full-year, half-year, mid-quarter, mid-month, pro-rata-monthly).

In every jurisdiction all three are *partly entity choice, partly law-mandated*, and — critically — **they differ between the commercial book and the tax book**. That divergence is the whole reason a fixed-asset subsystem is hard, and it is why the parallel-book design call (§2) is the centre of gravity.

### 1.1 Germany — the two-book problem in its purest form

**Commercial book (Handelsbilanz), HGB:**
- **§253 Abs. 3 HGB** — *planmäßige Abschreibung*: depreciable fixed assets with limited useful life MUST be written down systematically over that useful life. The method is the entity's choice within "ordnungsmäßige Buchführung" (GoB) — linear is dominant; degressive and Leistungs-AfA are permitted if they reflect the actual consumption pattern.
- **§253 Abs. 3 Satz 5-6 HGB** — *außerplanmäßige Abschreibung*: on a *voraussichtlich dauernde Wertminderung* (expected permanent impairment) the asset MUST be written down to the lower fair value. For financial assets a temporary impairment MAY also be written down. **§253 Abs. 5 HGB** — a *Wertaufholungsgebot* (write-up requirement) applies when the reason for the impairment no longer exists (except goodwill).
- **§242 / §264 HGB** — the Jahresabschluss itself: Bilanz + GuV for all merchants; Kapitalgesellschaften additionally Anhang + Lagebericht. **§284 Abs. 3 HGB** mandates the **Anlagengitter** (Anlagenspiegel) in the Anhang — the gross-value roll-forward of every asset class: opening cost, additions, disposals, transfers, cumulative depreciation, current-year depreciation, closing book value.

**Tax book (Steuerbilanz), EStG:**
- **§7 EStG — AfA (Absetzung für Abnutzung)**:
  - §7 Abs. 1 — *lineare AfA*: the default; cost / Nutzungsdauer.
  - §7 Abs. 1 Satz 6 — *Leistungs-AfA*: units-of-production, permitted for movables where output varies substantially year to year and is documented.
  - §7 Abs. 2 — *degressive AfA* (declining-balance) for movable fixed assets: **available only inside statutorily-defined windows** (see §7.1 below). When available, capped at a multiple of the linear rate and an absolute % ceiling; a switch to linear in the year that yields a higher charge is permitted.
  - §7 Abs. 4-5a — buildings: fixed statutory percentages by building type and completion date (e.g. the 3% rate for business buildings completed after 2023-12-31; the §7 Abs. 5a degressive-for-residential reintroduction).
  - §7 Abs. 1 Satz 4 — the *pro-rata-monthly convention* (`zeitanteilig`): in the acquisition year AfA accrues 1/12 per month from the month of acquisition. This is Germany's "convention" — it is mandatory, not an entity choice.
- **AfA-Tabellen** — the BMF publishes the *amtliche AfA-Tabelle "AV"* (general assets) plus ~100 industry-specific tables. They bind the *useful life* (betriebsgewöhnliche Nutzungsdauer) for tax. They are administrative guidance, not statute, but in practice control unless the taxpayer proves a shorter life. **These tables are the single largest piece of l10n-de data.**
- **§6 Abs. 2 / 2a EStG — GWG (geringwertige Wirtschaftsgüter)**: low-value assets. Net cost ≤ €800 → immediate full expense in the year of acquisition. Alternatively, assets with net cost > €250 and ≤ €1000 may be pooled into a *Sammelposten* depreciated linearly over 5 years (the "Poolabschreibung"). The threshold values are statutory and have changed over time — effective-dated data.
- **§7g EStG — Investitionsabzugsbetrag (IAB) + Sonderabschreibung**: SMEs may (i) deduct up to 50% of the *planned* cost of a future acquisition up to 3 years ahead (the IAB, a pre-acquisition deduction), and (ii) take an additional *Sonderabschreibung* of up to 20% (raised to 40% for acquisitions after 2023-12-31) in the acquisition year or the following four years, on top of regular AfA. This is a tax-book-only acceleration with no Handelsbilanz equivalent — a textbook parallel-book divergence.

**Maßgeblichkeitsprinzip (§5 Abs. 1 EStG):** the tax balance sheet is in principle *derived from* the commercial balance sheet. But the principle has been progressively breached — the *umgekehrte Maßgeblichkeit* was abolished (BilMoG 2009), and tax-specific elections (degressive AfA when commercial uses linear, §7g, GWG immediate expense vs commercial multi-year) routinely diverge the two books. **kontor must treat HGB and Steuerbilanz as two fully independent depreciation runs that happen to share one physical asset and one acquisition cost — exactly the `:ledger` model.**

### 1.1.1 The German degressive-AfA windows — the effective-dating proof case

Degressive AfA for movable fixed assets has been switched on and off by statute, and the *parameters* (rate multiple, % ceiling) differ between windows:

- pre-2008: available;
- 2008-01-01 .. 2008-12-31: suspended;
- 2009-01-01 .. 2010-12-31: reintroduced (Konjunkturpaket; multiple of linear, ceiling ~25%);
- 2011-01-01 .. 2019-12-31: suspended;
- **2020-01-01 .. 2022-12-31**: reintroduced (Corona; up to 2.5× linear, ceiling 25%);
- 2023-01-01 .. 2024-03-31: suspended;
- **2024-04-01 .. 2025-12-31**: reintroduced (Wachstumschancengesetz / follow-on; up to 2× linear, ceiling 20%);
- 2026-01-01 onward: subject to current legislation — **flagged for re-research**, the coalition discussions in 2025 floated a further extension.

The legally-correct rule for a given asset depends on its **acquisition date**, which never changes once recorded. A 2021-acquired machine keeps its 2020-22-window degressive schedule for its whole life even when computed (re-computed for an amended return) in 2026. This is precisely the valid-time-not-tx-time argument of ADR-026 (`doc/decisions.md:1415`). See §7.

### 1.2 United States — book/tax divergence drives the deferred-tax machinery

- **IRC §168 — MACRS (Modified Accelerated Cost Recovery System)**, the mandatory tax method for most tangible property placed in service after 1986:
  - **Recovery classes** — 3, 5, 7, 10, 15, 20-year personal property; 27.5-year residential rental; 39-year nonresidential real property. The class assignment per asset type is in **IRS Pub 946** and the class-life tables (Rev. Proc. 87-56). *Pure l10n-us data.*
  - **GDS vs ADS** — the General Depreciation System (200%/150% declining-balance switching to straight-line) vs the Alternative Depreciation System (straight-line, longer lives), mandatory for certain property (tax-exempt use, listed property failing the predominant-use test, etc.).
  - **Conventions** — **half-year** (default for personal property: ½ year in year 1 and in the final year), **mid-quarter** (mandatory if >40% of the year's additions are in Q4), **mid-month** (real property: ½ month in the month placed in service). The convention is *law-mandated and data-driven* — not an entity choice.
  - **§168(k) — bonus depreciation** — an additional first-year deduction; the percentage is itself a statutory phase-down schedule (100% through 2022, 80% 2023, 60% 2024, 40% 2025, 20% 2026...), repeatedly amended. Effective-dated data.
  - **§179 — expensing election** — immediate expensing of qualifying property up to an annually-indexed dollar cap with a phase-out threshold. Effective-dated data.
- **Book (US GAAP, ASC 360)** — straight-line is typical; the method, useful life and salvage are management estimates subject to audit. Componentisation is permitted but not common in US practice.
- **The consequence** — book depreciation (straight-line) and tax depreciation (MACRS+bonus+§179) diverge sharply in early years, generating a **temporary difference** and a **deferred tax liability** (ASC 740). kontor does not compute the tax provision, but the two depreciation *runs* must both exist as queryable books so a `kontor-tax-provision` consumer (or the customer's CPA) can take the difference. → again, `:ledger`.

### 1.3 Canada — pooled declining-balance

- **CCA (Capital Cost Allowance)** — Canada does not depreciate individual assets for tax; it pools them into **CCA classes** (Class 1 buildings 4%, Class 8 general 20%, Class 10/10.1 vehicles 30%, Class 50 computer hardware 55%, Class 53 manufacturing equipment 50%, ...). Each class is a **declining-balance pool**: CCA = class UCC (undepreciated capital cost) × class rate.
- **Half-year rule** — net additions to a class in the year are subject to ½ the rate in the acquisition year (the "available-for-use" rule + half-year adjustment). The **Accelerated Investment Incentive** (and temporary immediate-expensing measures) modified this for property acquired in defined windows — effective-dated.
- **Modelling consequence** — the CCA "asset" is the *pool*, not the individual asset. kontor must support an asset whose depreciation schedule is *shared* — i.e. the asset is a member of a pool and the pool carries the schedule. This is a `kontor-l10n-ca`-specific shape: the l10n module models the pool as the `:schedule/origin-entity` and the individual `:asset`s reference the pool. The kernel/companion does not need a "pool" concept — l10n composes it from the generic `:schedule` ref.

### 1.4 IFRS — the richest commercial-book rules

- **IAS 16 — Property, Plant and Equipment**:
  - **Cost model vs revaluation model** — an accounting-policy election per asset *class*. The revaluation model carries the asset at fair value with subsequent revaluations through a *revaluation surplus* in OCI (or P&L to the extent it reverses a prior P&L loss).
  - **Componentisation** — *mandatory*: each part of an item of PP&E with a cost significant relative to total cost, and a different useful life, MUST be depreciated separately (the "aircraft engine vs airframe" rule).
  - **Residual value + useful life review** — both MUST be reviewed at least at each financial year-end; a change is a *change in estimate* applied prospectively (not a restatement).
  - Depreciation method must reflect the pattern of consumption; a revenue-based method is prohibited.
- **IAS 36 — Impairment** — at each reporting date assess for indicators; if present, write down to *recoverable amount* (higher of fair-value-less-costs-of-disposal and value-in-use). Reversible (except goodwill) when indicators reverse.
- **IFRS 16 — Leases** — the lessee recognises a **right-of-use (ROU) asset** and depreciates it (generally straight-line over the shorter of lease term and useful life) under IAS 16 mechanics. The ROU asset is just another `:asset` with `:asset/class :rou`; its acquisition cost is the initial lease-liability measurement. This is why a `kontor-lease` companion would *reuse* `kontor-asset`'s depreciation engine rather than reinvent it.

### 1.5 United Kingdom — capital allowances

- **Capital allowances** replace book depreciation for tax (book depreciation is added back). **Writing-down allowances (WDA)** — main pool 18% declining-balance, special-rate pool 6%. **Annual Investment Allowance (AIA)** — 100% first-year relief up to an annual cap. **Full expensing** — 100% first-year relief on qualifying main-rate plant and machinery (from 2023-04-01), 50% first-year allowance on special-rate. Pooled, like Canada; rates and the AIA cap are effective-dated.

### 1.6 The data-vs-mechanism split (the synthesis)

| Regulated content | Data (`kontor-l10n-<cc>`) | Mechanism (`kontor-asset` companion) | Mechanism (kernel) |
|---|---|---|---|
| Useful-life tables (AfA-Tabellen) | ✔ effective-windowed rows | | |
| Recovery classes / CCA classes / UK pools | ✔ class→(method,life,rate,convention) rows | | |
| Mandated methods per asset class | ✔ which provider id + params per class | | |
| Conventions (half-year, mid-quarter, mid-month, zeitanteilig) | ✔ convention keyword per class/window | the convention *interpreter* lives in the provider impl | |
| Low-value thresholds (GWG €800/€1000, §179 cap) | ✔ effective-windowed scalar | the immediate-expense *posting shape* | |
| Special-depreciation windows (§7g, §7 Abs. 2, §168(k) bonus, AII) | ✔ effective-windowed rule rows | the provider that *applies* the special charge | |
| The `:asset` entity, acquisition cost, in-service date | | ✔ schema | |
| The `DepreciationProvider` protocol seam | | ✔ protocol + built-in impls | |
| straight-line / DB / SYD / UoP math | | ✔ built-in provider impls | |
| MACRS / AfA-degressive / CCA / full-expensing math | ✔ l10n provider impls | the seam they plug into | |
| Recurring-posting occurrence log + idempotency | | the asset depreciation *runner* uses it | ✔ `:schedule` + `:schedule-occurrence` (ADR-032) |
| Year-end P&L→retained-earnings rollup | | | ✔ `kontor.closing` |
| Period locking / sealing | | | ✔ `kontor.period` (ADR-014) |
| Anlagengitter arithmetic | | ✔ generic roll-forward report engine | |
| Anlagengitter / cash-flow *line layout* | ✔ statement definition EDN | | the `compute-statement` engine (kernel) |

The pattern is exactly ADR-005's: **the kernel/companion ships the protocol and the universal mechanism; the l10n module ships the jurisdiction tables and the jurisdiction-specific provider impl.** Nothing country-specific enters the companion.

---

## 2. The parallel-book design call — `:ledger` vs `:valuation-book` vs new concept

This is the central design call. One physical asset, N regulatory depreciation schedules (Handelsbilanz + Steuerbilanz; book + tax; IFRS + local GAAP + group). SAP calls each of these a *depreciation area*; the question is what a depreciation area **is** in kontor.

### 2.1 The three candidates

**Candidate A — a depreciation area is a `:ledger` (ADR-021).**
- `:ledger` is the kernel's parallel-book primitive. Sum-to-zero is enforced *per ledger* within a transaction (`doc/decisions.md:934`, `src/kontor/posting.clj:324-328`). A `:ledger` carries a `:ledger/framework` keyword (`:HGB`, `:IFRS`, `:US-GAAP`, `:tax-de`, ...; `src/kontor/schema.clj:2317-2322`).
- ADR-021 *explicitly names* this use case: *"Forward-compat for fixed-asset register / lease accounting / revenue recognition: each consumer engine that produces postings can post to multiple ledgers, which is exactly the IFRS-16 vs ASC-842 split"* (`doc/decisions.md:1012`).
- The depreciation run for the HGB book posts `Dr Abschreibungsaufwand / Cr kumulierte Abschreibung` against `:ledger "hgb"`; the Steuerbilanz run posts the (different) amount against `:ledger "tax-de"`. Each ledger balances independently. The *GL entries* land in the right book by construction.

**Candidate B — a depreciation area is a `:valuation-book` (ADR-027).**
- `:valuation-book` is *also* a parallel-book primitive, also carries a `:framework` keyword. Superficially attractive: "asset valuation" sounds like "valuation book".
- But ADR-027 is unambiguous about what a valuation book *is*: *"an orthogonal lens on physical stock — it picks which cost basis (FIFO/LIFO/...) applies"* (`doc/decisions.md:1433`), and ADR-027's "alternatives considered" explicitly distinguishes the two axes: *"ledger is which set of books reads this posting; valuation book is which cost basis this posting uses. They're orthogonal"* (`doc/decisions.md:1483`).
- A depreciation area is **not a cost-basis selector**. There is no FIFO/LIFO question for a fixed asset — the acquisition cost is a single known number. What differs between books is the *method, life and convention applied to that one cost* — and the resulting *journal entries*. That is squarely the `:ledger` axis, not the `:valuation-book` axis. The `:valuation-layer` machinery (receipt/consumption/adjustment, `src/kontor/schema.clj:2779-2830`) has no analogue in depreciation.

**Candidate C — a new `:depreciation-area` (or `:asset-book`) concept.**
- SAP has a dedicated "depreciation area" object distinct from its ledgers (areas map *to* ledgers). One could argue depreciation areas are finer-grained than ledgers (SAP ships ~5-30 areas: book, tax, group, cost-accounting, net-worth-tax, parallel-currency variants...).
- **Rejected.** Introducing a third parallel-book primitive next to `:ledger` and `:valuation-book` violates the kernel's anti-accretion contract (`doc/architecture.md:440`) and would force every reporting query to learn a third axis. The SAP "area ≠ ledger" split exists because SAP's classic ledger model predates the Universal Journal; S/4HANA's New Asset Accounting *re-aligned* areas to ledgers precisely to remove the redundancy. kontor, designing fresh, should adopt the *post-reconciliation* SAP model: **one depreciation area per ledger.** If a customer genuinely needs a depreciation view that does not correspond to a posting ledger (e.g. a pure cost-accounting area that never posts to the GL), that is a *statistical* `:ledger` (`:ledger/type :statistical`, which ADR-021 already provides) — not a new entity.

### 2.2 Recommendation: **a depreciation area IS a `:ledger`.**

A `kontor-asset` **depreciation schedule is per (asset, ledger)**. The `:asset` entity is single (one physical machine, one acquisition cost, one in-service date); it has *N* `:asset-depreciation` schedule entities, each naming a `:ledger` and a `DepreciationProvider` configuration. The depreciation run for ledger L produces postings tagged `:posting/ledger L` via the generic `:schedule` mechanism.

**Why this is the right call:**
1. It reuses the primitive ADR-021 was *designed* to carry this exact load (`doc/decisions.md:1012`) — zero new parallel-book concepts.
2. The per-ledger sum-to-zero invariant (`doc/decisions.md:954`) gives the audit guarantee for free: the HGB book and the Steuerbilanz book each balance, and you cannot net an HGB depreciation debit against a tax-book credit.
3. Reporting "Handelsbilanz vs Steuerbilanz side by side" is a ledger-filtered `compute-statement` call — already supported (`src/kontor/financial_statements.clj:124`, `:include-states`/window opts; ledger filtering rides the same engine).
4. It composes with multi-entity: `:ledger-entity` overrides (`src/kontor/schema.clj:2947`) already let a parent and a subsidiary run different framework sets.
5. The deferred-tax consumer (US book/tax) takes `balance(asset-account, ledger="book") − balance(asset-account, ledger="tax")` — a pure ledger-filtered balance query.

**One caveat to document in the ADR:** the *primary* ledger is bootstrapped as `:ledger/framework :local` (`doc/decisions.md:988-992`). A DE customer who wants HGB-as-primary and Steuerbilanz-as-secondary, vs a customer who wants IFRS-as-primary, is an l10n-de install-time decision — `kontor-l10n-de` installs the `hgb` and `tax-de` ledgers and decides which is primary. `kontor-asset` itself is framework-agnostic: it only ever takes a `:ledger` ref.

---

## 3. `DepreciationProvider` protocol sketch

`:schedule` deliberately does not compute per-period amounts — *"The kernel does NOT compute per-period amounts. That's the consumer's job (depreciation methods, ASC 606 recognition, ...)"* (`src/kontor/schedule.clj:12-16`). ADR-032 even anticipated the seam: *"The `CostingProvider` analogue would be a `ScheduleProvider`, but most consumers use one well-known method per schedule-kind"* (`doc/decisions.md:1928`). For depreciation specifically, the method *is* runtime-pluggable (a DE customer needs both linear-HGB and degressive-tax simultaneously; an l10n module must be able to inject MACRS without forking the companion). So `kontor-asset` **does** ship a provider protocol — it is the `CostingProvider`/`TaxProvider` pattern (ADR-005, ADR-029) applied to depreciation.

```clojure
(ns kontor.asset.depreciation-provider)

(defprotocol DepreciationProvider
  "Compute a depreciation schedule for one (asset, ledger) pair.
   Mirrors TaxProvider (ADR-005) and CostingProvider (ADR-029):
   the companion ships method built-ins; l10n modules ship
   jurisdiction-specific impls (MACRS, AfA-degressive, CCA, full-
   expensing). The provider is PURE — it reads an asset spec + a
   db value, returns a plan, transacts nothing."

  (plan-schedule
    [provider db asset-book request]
    "Given an :asset-depreciation entity (the per-(asset,ledger)
     book), produce the full forward plan:
       {:periods [{:sequence    long          ; 1-indexed
                   :date        #inst         ; valid-time of the charge
                   :amount      bigdec        ; this period's depreciation
                   :method-used keyword       ; e.g. :declining-balance,
                                              ;   :straight-line (DB→SL switch)
                   :basis-remaining bigdec}   ; book value carried forward
                  ...]
        :convention  keyword                  ; :half-year :mid-quarter
                                              ;   :mid-month :zeitanteilig :full
        :total       bigdec                   ; Σ amounts = depreciable base
        :provider-id keyword}
     `asset-book` carries acquisition cost, in-service date, useful
     life, salvage, method params, the effective-dated rule row the
     l10n module resolved. The provider does NOT decide WHICH rule
     applies across statute windows — the l10n module resolves the
     effective-dated rule (ADR-026 pattern, §7) and hands it in.")

  (plan-event
    [provider db asset-book event]
    "Re-plan the REMAINING schedule after a mid-life event:
     `event` = {:kind :disposal | :impairment | :revaluation
                       | :partial-disposal | :useful-life-revision
                       | :addition  ; subsequent capitalised cost
                :date #inst :amount bigdec? :new-life int? ...}
     Returns the same shape as plan-schedule for periods from the
     event date forward. IAS 16 estimate-change is prospective, so
     this NEVER restates fired occurrences — it supersedes the
     pending tail. (Fired occurrences are immutable; ADR-032.)")

  (provider-id [provider]))
```

### 3.1 Companion-shipped built-in impls (`modules/asset/`, EPL-1.0)

- **`StraightLineProvider`** — `(cost − salvage) / life`, with a `:convention` knob (full / half-year / mid-month / pro-rata-monthly). Covers HGB §253 linear, US GAAP book, IAS 16 cost-model straight-line, IFRS 16 ROU assets.
- **`DecliningBalanceProvider`** — `book-value × rate`, parameterised by the rate *multiple* (1.5×, 2×, 2.5×) and an absolute % ceiling, with an optional automatic **switch to straight-line** in the first year SL ≥ DB (the standard optimisation; §7 Abs. 2 EStG permits it, MACRS GDS bakes it in).
- **`SumOfYearsDigitsProvider`** — accelerated; rarely mandated but a recognised method.
- **`UnitsOfProductionProvider`** — `(cost − salvage) × period-units / total-units`; covers HGB/EStG Leistungs-AfA. Its `plan-schedule` needs per-period unit input — it returns a *rate per unit* and the runner supplies actuals at fire time (the one provider whose schedule is genuinely not fully forward-computable).

### 3.2 l10n-shipped impls

- **`MacrsProvider`** (`kontor-l10n-us`) — table-driven: takes the recovery class, GDS/ADS, the convention (half-year / mid-quarter / mid-month), reads the percentage table from l10n-us data, layers §168(k) bonus and §179 expensing from effective-windowed rows. Returns the year-by-year MACRS schedule.
- **`AfaDegressiveProvider`** (`kontor-l10n-de`) — declining-balance with the *window-specific* multiple and ceiling (2.5×/25% for the 2020-22 window, 2×/20% for the 2024-25 window — §1.1.1), with the §7 Abs. 2 SL-switch. Plus a `GwgProvider` / `SammelpostenProvider` for the §6 Abs. 2/2a immediate-expense and 5-year-pool cases, and the `§7g` Sonderabschreibung overlay.
- **`CcaProvider`** (`kontor-l10n-ca`) — declining-balance on the *pool* UCC with the half-year rule and the Accelerated Investment Incentive overlay.
- **`CapitalAllowanceProvider`** (`kontor-l10n-uk`) — main/special-rate pool WDA + AIA + full expensing.

Built-ins and l10n impls register the same way `TaxProvider` impls do (ADR-005's `provider-id` + a registry on `conn` per `doc/architecture.md:84` — *"provider registration"* in `core.clj`).

---

## 4. The `:asset` schema sketch

Namespacing convention: every attribute namespaces under a new `:asset/*` family (plus `:asset-depreciation/*`, `:asset-event/*`, `:asset-component/*`, `:asset-class/*`). A new namespace requires an ADR (`CLAUDE.md` — Namespacing). `kontor-asset` is a **companion module** (`modules/asset/`), so its namespaces are companion-owned and cohabit per ADR-002 — exactly like `:invoice/*` and `:order/*`.

```clojure
;; ── :asset — one physical capitalised asset ───────────────────────
:asset/code             string  :db.unique/identity   ; "MACH-001", "VEH-2026-07"
:asset/name             string
:asset/class            ref → :asset-class             ; category (see below)
:asset/acquisition-cost bigdec                         ; the single cost ALL books share
:asset/acquisition-commodity ref → :commodity
:asset/acquisition-date instant                        ; valid-time; DRIVES effective-
                                                       ;   dated rule resolution (§7)
:asset/in-service-date  instant                        ; when depreciation clock starts;
                                                       ;   may differ from acquisition
                                                       ;   (DE: "Anschaffung" vs
                                                       ;   "betriebsbereit"; US/CA:
                                                       ;   "placed in service" /
                                                       ;   "available for use")
:asset/salvage-value    bigdec                         ; residual value (IAS 16: reviewed
                                                       ;   annually → may change via an
                                                       ;   :asset-event :useful-life-
                                                       ;   revision); often 0
:asset/asset-account    ref → :account                ; the BS account carrying gross cost
:asset/accumulated-account ref → :account             ; contra-asset (kumulierte AfA /
                                                       ;   accumulated depreciation)
:asset/expense-account  ref → :account                ; depreciation expense (P&L)
:asset/cost-center      ref → :analytic-account        ; uses the bootstrapped
                                                       ;   "cost-center" plan (ADR-032)
:asset/entity           ref → :entity                  ; legal-entity scope (ADR-031),
                                                       ;   optional
:asset/parent           ref → :asset                   ; for componentisation: the
                                                       ;   "whole" an :asset-component
                                                       ;   rolls up to (IAS 16); optional
:asset/origin-transaction ref → :transaction           ; the acquisition GL entry (the
                                                       ;   capitalisation posting)
:asset/origin-document  ref → :audit-doc               ; invoice / contract / board
                                                       ;   resolution (ADR-038)
:asset/status           keyword                        ; the lifecycle facet — see §4.2
:asset/serial-number    string                         ; optional
:asset/location         string                         ; optional free-text / ref
:asset/note             string

;; ── :asset-class — the category that carries jurisdiction defaults ─
;; Mostly l10n-installed: a DE class maps to an AfA-Tabelle row; a US
;; class maps to a MACRS recovery class. The companion ships the
;; ENTITY; l10n ships the ROWS.
:asset-class/code        string :db.unique/identity    ; "machinery", "office-equipment",
                                                       ;   "buildings-commercial"
:asset-class/name        string
:asset-class/parent      ref → :asset-class            ; hierarchy
:asset-class/default-useful-life-months long           ; default; overridable per asset
:asset-class/note        string

;; ── :asset-depreciation — the per-(asset, ledger) book ────────────
;; THIS is the "depreciation area". One :asset has N of these — one
;; per ledger (HGB + Steuerbilanz; book + tax; IFRS + local).
:asset-depreciation/asset        ref → :asset
:asset-depreciation/ledger       ref → :ledger          ; §2 — the depreciation area IS
                                                        ;   a ledger
:asset-depreciation/provider-id  keyword                ; which DepreciationProvider
                                                        ;   (:straight-line, :macrs,
                                                        ;   :afa-degressive, :cca, …)
:asset-depreciation/method-params ref → ... | string    ; provider config: rate multiple,
                                                        ;   % ceiling, table key. Modeled
                                                        ;   as a small entity or EDN-as-
                                                        ;   string; ADR decides.
:asset-depreciation/useful-life-months long             ; this book's life (HGB life ≠
                                                        ;   AfA-Tabelle life is common)
:asset-depreciation/convention   keyword                ; :full | :half-year
                                                        ;   | :mid-quarter | :mid-month
                                                        ;   | :zeitanteilig
:asset-depreciation/depreciable-base bigdec             ; usually acquisition-cost −
                                                        ;   salvage; may differ per book
                                                        ;   (tax bonus reduces tax base)
:asset-depreciation/schedule     ref → :schedule        ; the ADR-032 recurring-posting
                                                        ;   schedule that the runner fires
:asset-depreciation/effective-rule ref → ...            ; the effective-dated jurisdiction
                                                        ;   rule row the l10n module
                                                        ;   resolved at creation (§7)
:asset-depreciation/identity     tuple [asset, ledger]  ; :db.unique — one book per
                                                        ;   (asset, ledger)

;; ── :asset-event — mid-life events (immutable fact entities) ──────
;; Disposal, impairment, revaluation, partial disposal, useful-life
;; revision, subsequent capitalised addition. Each event entity is
;; immutable (kontor's posting/layer pattern); the asset's status and
;; the pending schedule tail are updated AROUND it.
:asset-event/asset       ref → :asset
:asset-event/kind        keyword                        ; :disposal | :impairment
                                                        ;   | :revaluation
                                                        ;   | :partial-disposal
                                                        ;   | :useful-life-revision
                                                        ;   | :addition | :transfer
:asset-event/date        instant                        ; valid-time of the event
:asset-event/amount      bigdec                          ; impairment loss / revaluation
                                                        ;   delta / disposal proceeds /
                                                        ;   addition cost
:asset-event/commodity   ref → :commodity
:asset-event/new-useful-life-months long                ; for :useful-life-revision
:asset-event/transaction ref → :transaction              ; the GL entry the event posted
:asset-event/justification ref → :audit-doc              ; ADR-038 — impairment test memo,
                                                        ;   disposal approval, valuation
                                                        ;   report. REQUIRED by an
                                                        ;   approval-policy for
                                                        ;   :impairment / :disposal.
:asset-event/status-history ref → :status-history        ; the transition this event
                                                        ;   produced on :asset/status
:asset-event/note        string

;; ── :asset-component — IAS 16 componentisation ────────────────────
;; A component is just an :asset whose :asset/parent points at the
;; "whole". So this may NOT need its own namespace — :asset/parent
;; plus an :asset-class flag may suffice. The ADR decides whether
;; componentisation is "an :asset with a parent" (lean, preferred)
;; or a distinct entity. Leaning lean.
```

### 4.1 What is NOT in the `:asset` schema (scope honesty, ADR-010)

- **No tax-provision computation.** kontor stores the two depreciation books; computing the deferred-tax asset/liability is a downstream consumer or the customer's CPA.
- **No depreciation *math* in the schema.** The math is in `DepreciationProvider` impls. The schema stores only inputs and (via `:schedule-occurrence`) outcomes.
- **No CCA/UK "pool" entity.** The pool is an l10n-ca/uk construct — l10n models the pool as a `:schedule/origin-entity` and points member `:asset`s at it. The companion stays jurisdiction-free.
- **No physical-asset-management** (maintenance, warranty, telemetry) — that is a `kontor-fleet` / EAM concern, deferred per research note 10.

### 4.2 The lifecycle status-machine (`:asset/status` facet, ADR-034)

`:asset/status` is a `:status-transition`/`:status-history`-governed facet (`src/kontor/status_machine.clj`, ADR-034). `kontor-asset` ships the seed vocabulary:

```
:asset.status/nil ──(acquire)──▶ :planned ──(place-in-service)──▶ :in-service
                          │                                          │
                          └──────────(acquire-in-service)────────────▶│
                                                                       │
   :in-service ──(depreciation runner: last occurrence)──▶ :fully-depreciated
   :in-service ──(impair, may recur)──────────────────────▶ :in-service
   :in-service ──(revalue, IAS 16 revaluation model)──────▶ :in-service
   :in-service ──────────(dispose / write-off / sell)─────▶ :disposed
   :fully-depreciated ───(dispose / scrap)────────────────▶ :disposed
   :in-service ───────────(transfer to another entity)────▶ :transferred
```

- `:planned` covers the DE §7g IAB case (a deduction *before* the asset exists) and the gap between order and availability.
- `:fully-depreciated` is reached when the last `:schedule-occurrence` fires — assets often sit here for years (a fully-written-down machine still in use; book value = salvage or 0).
- `:disposed` and `:transferred` are terminal.
- **Governance (ADR-038):** an `:approval-policy` row governs `:in-service → :disposed` (`:requires-supporting-doc` — the disposal authorisation) and the `:impairment`/`:revaluation` `:asset-event`s (`:requires-non-empty-reason-note` — the impairment-test justification; `:requires-supporting-doc` — the valuation report). `:no-self-approval` applies where SoD matters. This is exactly the `:audit-doc` + `:approval-policy` backbone (`doc/architecture.md:373-377`).

---

## 5. How `kontor-asset` composes with the existing substrate

`kontor-asset` is a thin companion: ~6 entities, one provider protocol, and *wiring*. Almost everything it needs already exists.

### 5.1 On `:schedule` (ADR-032) — the depreciation runner

Each `:asset-depreciation` book owns one `:schedule` (`:schedule/kind :depreciation`, `:schedule/origin-entity` → the `:asset-depreciation` entity, `:schedule/frequency :monthly` typically, `:schedule/total-amount` = the depreciable base). The **depreciation run** is:

1. `kontor.schedule/pending-occurrences` → which periods are due (`src/kontor/schedule.clj:125-160`).
2. For each pending occurrence, `kontor-asset`'s runner calls the resolved `DepreciationProvider`'s `plan-schedule` (once, cached) and pulls the period's amount.
3. `kontor.posting/build-transaction` builds `Dr :asset/expense-account / Cr :asset/accumulated-account` for that amount, tagged `:posting/ledger` = the book's ledger, `:posting/period-tag` and `:posting/effective-date` = the occurrence date, `:analytic-distribution` → the asset's cost-center.
4. `kontor.schedule/record-occurrence!` logs the `:schedule-occurrence` with a back-ref to the transaction — idempotent on `[schedule, sequence]` (`src/kontor/schedule.clj:162-199`). Re-running a missed month does not double-post.

`kontor-asset` ships exactly what ADR-032 anticipated: *"Each companion's posting-builder consumes the schedule for its domain math; the kernel just records what happened"* (`doc/decisions.md:1938`). The companion adds `run-depreciation!` (a convenience over `pending-occurrences` + provider + `build-transaction` + `record-occurrence!`) and `catch-up!` (fire all pending occurrences up to a date).

**A multi-book asset has N schedules.** A DE GmbH asset has an `hgb` book schedule and a `tax-de` book schedule; a depreciation run fires both, producing two transactions (or one transaction with postings to two ledgers — `build-transaction` supports both, `doc/architecture.md:257-271`).

### 5.2 On `kontor.closing` — year-end

`kontor.closing/close-fiscal-year!` (`src/kontor/closing.clj:210`) rolls P&L → retained earnings. Depreciation *expense* postings are ordinary `:expense`-type postings; the closing run picks them up automatically (`pnl-accounts` selects all `:income`/`:expense`, `src/kontor/closing.clj:46-53`). **No change to `kontor.closing` is needed for the depreciation-into-close mechanics.** The only sequencing rule `kontor-asset` must document: *run the year's final depreciation occurrence before `close-fiscal-year!`* — otherwise the year's last month of depreciation lands after the close. This is a runner-ordering convention, not a kernel change.

One subtlety: `close-fiscal-year!` operates per period and is country-agnostic (`src/kontor/closing.clj:18-21`); a DE customer's `l10n-de.closing/close-fiscal-year!` pins retained earnings to SKR04 2900. Multi-book close: `close-period!` works per ledger via the same posting machinery — `kontor-asset` adds nothing here.

### 5.3 On `:period` (ADR-014) — locking

The depreciation runner posts into open periods. If a period is soft-closed, `kontor.period/assert-not-in-locked-period!` (`src/kontor/period.clj:139`) refuses the occurrence — correct behaviour: a depreciation charge for a closed month must route into an adjustment period (`:adjustment-13`) or be reverse-and-reposted. `kontor-asset` does not bypass this; the runner surfaces the `:period/locked-period-violation` to the caller. **The `:period` pre-close checks already cover the depreciation case** — `default-pre-close-checks` refuses a close with draft postings (`src/kontor/period.clj:259-279`), and a pending-but-unfired depreciation occurrence is *not* a draft posting (it does not exist yet), so `kontor-asset` should optionally extend the pre-close hook with a `:no-pending-depreciation` check (a companion-supplied `:pre-checks` fn — `close!` already takes one, `src/kontor/period.clj:319`). That is the one small period-integration addition, and it is a *hook*, not a kernel change.

### 5.4 On `:status-machine` + `:audit-doc` + `:approval-policy`

Covered in §4.2. The asset lifecycle is a `:status-transition` vocabulary; disposal and impairment route through `record-status-change!` (`src/kontor/status_machine.clj:306`) with `:approval-policy` enforcement. `:asset-event/justification` is an `:audit-doc` ref. `kontor-asset` ships seed `:status-transition` rows and seed `:approval-policy` rows; it ships **no** jurisdiction data.

### 5.5 On `kontor.posting/build-transaction` — the GL entries

Every posting `kontor-asset` produces — the capitalisation entry, the monthly depreciation entry, the disposal entry (`Dr cash + Dr accumulated-depreciation / Cr asset-at-cost +/- Cr/Dr gain/loss`), the impairment entry, the revaluation entry — goes through `build-transaction` (`src/kontor/posting.clj:291`). Sum-to-zero per (entity, ledger, commodity) is enforced for free. The disposal gain/loss posting is interesting: it is computed by `kontor-asset` (proceeds − net book value), and net book value is `:asset/acquisition-cost − balance(:asset/accumulated-account, ledger)` — a `kontor.balance` query per book. Gain/loss differs per book (HGB NBV ≠ tax NBV) → again, per-ledger postings.

---

## 6. Jahresabschluss — gap analysis

### 6.1 What `kontor.closing` + `kontor.financial-statements` already cover

- **The GuV / P&L** — `compute-statement` with a P&L definition (`src/kontor/financial_statements.clj:112`). Depreciation expense is a P&L line; covered.
- **The Bilanz / Balance Sheet** — `compute-statement` with a BS definition; point-in-time semantics, `:from nil :to cutoff` (`src/kontor/financial_statements.clj:8-12`). Fixed assets at gross cost less accumulated depreciation are two account-code lines; covered. The contra-asset (`:asset/accumulated-account`) nets correctly because the BS engine sums postings.
- **The year-end roll** — `close-fiscal-year!` (`src/kontor/closing.clj:210`); covered.
- **Period locking / sealing for the closed year** — `kontor.period` (`src/kontor/period.clj`); covered.
- **The l10n line layout** — `compute-statement` takes a definition; `kontor-l10n-de` ships the HGB §266 (Bilanz) and §275 (GuV) layouts as definitions (`src/kontor/financial_statements.clj:14-18` — *"only the line layout + the account-code prefixes are country-specific"*). Covered.

### 6.2 What Stage L′ must add

1. **The Anlagengitter / Anlagenspiegel (HGB §284 Abs. 3)** — the statutory asset-history sheet: per asset class, the gross-value roll-forward (opening cost, additions, disposals, transfers, opening accumulated depreciation, current-year depreciation, disposals' accumulated depreciation, closing accumulated depreciation, opening and closing net book value). This is **not** a posting aggregation by account code — it is a roll-forward keyed on `:asset` + `:asset-event` + `:schedule-occurrence` history within a date window. `kontor-asset` ships a **generic `anlagengitter` report engine** (`kontor.asset.report/asset-roll-forward`) that produces the roll-forward arithmetic for any (date window, ledger, asset-class grouping); `kontor-l10n-de` ships the *presentation* (the HGB column layout, the SKR04 class grouping). The arithmetic is jurisdiction-free (every jurisdiction needs an asset roll-forward — US Form 4562, IFRS IAS 16.73 reconciliation); the layout is l10n. **This is the one genuine new report engine Stage L′ adds.**

2. **The cash-flow statement (Kapitalflussrechnung — mandatory for kapitalmarktorientierte / large Kapitalgesellschaften, DRS 21 / IAS 7)** — depreciation is the canonical non-cash add-back in the indirect method. The cash-flow statement is *not* asset-specific — it belongs in the **kernel** as a third `compute-statement`-class engine (`kontor.financial-statements/compute-cash-flow`), because it is a universal statement, not an asset concern. But it is *driven* by the asset subsystem (the depreciation add-back line). Recommendation: **Stage L′ adds `compute-cash-flow` to `kontor.financial-statements`** (kernel) as a small extension — the indirect-method engine that takes a definition mapping P&L/BS deltas to operating/investing/financing sections — and `kontor-asset` simply ensures depreciation is queryable as a line. The *definition* (which line is which section) stays l10n.

3. **The statement of changes in equity (Eigenkapitalspiegel — mandatory for the same set, DRS 22 / IAS 1)** — needed because the **IAS 16 revaluation surplus** flows through OCI/equity, not P&L. Same call as the cash-flow statement: a kernel-level `compute-equity-changes` engine in `kontor.financial-statements`, definition supplied by l10n. `kontor-asset` ensures revaluation postings tag the revaluation-surplus equity account so the equity statement can pick them up.

4. **A useful-life / residual-value review log** — IAS 16 mandates an annual review of useful life and residual value. This is *already covered* by `:asset-event/kind :useful-life-revision` + `:status-history` — no new entity. Stage L′ should document the convention (the annual review is a status-history-recorded event even when the estimate does not change — "reviewed, no change" is itself audit evidence).

### 6.3 What stays l10n

- All statement *line layouts* (HGB §266/§275, US balance-sheet/income-statement classification, IFRS presentation).
- All asset-class → jurisdiction-table mappings (AfA-Tabellen, MACRS classes, CCA classes, UK pools).
- The jurisdiction `DepreciationProvider` impls (MACRS, AfA-degressive, CCA, full-expensing).
- The effective-dated rule rows (§7).
- The DE-specific Jahresabschluss assembly (Anhang text, Lagebericht — these are document-generation, arguably even consumer-app, not kontor at all).

### 6.4 Summary of kernel touches

Stage L′ touches the *kernel* in exactly two small places: (a) `kontor.financial-statements` gains `compute-cash-flow` and `compute-equity-changes` (two universal statement engines, definition-driven, l10n supplies the definition); (b) optionally a documented `:pre-checks` convention for "no pending depreciation" (a hook the period already exposes — *no code change to `kontor.period`*). Everything else is the `modules/asset/` companion. This is consistent with the "kernel does not evolve by accretion" contract (`doc/architecture.md:440`).

---

## 7. Effective-dated depreciation rules — applying ADR-026

ADR-026 (`doc/decisions.md:1381`) made tax *rates* effective-dated via `:tax/effective-from` + `:tax/effective-until`, selecting the record whose validity window contains the transaction's effective date, open interval on the right. **The German degressive-AfA windows are the same problem one layer up** — and ADR-026's own "alternatives considered" already rejected year-versioned namespaces and tx-time versioning for exactly this reason (`doc/decisions.md:1412-1416`).

### 7.1 The mechanism

`kontor-l10n-de` ships a `:depreciation-rule` entity (l10n-owned namespace) with:

```clojure
:depreciation-rule/code             string :db.unique/identity
:depreciation-rule/jurisdiction     ref → :country
:depreciation-rule/asset-class      ref → :asset-class       ; or a class-pattern
:depreciation-rule/provider-id      keyword                  ; :afa-degressive | :afa-linear
:depreciation-rule/method-params    ...                      ; multiple, ceiling, etc.
:depreciation-rule/effective-from   instant                  ; ADR-026 pattern
:depreciation-rule/effective-until  instant                  ; open interval on the right
```

l10n-de ships, for "movable fixed assets, degressive AfA":
- a row with `effective-from #inst "2020-01-01"` / `effective-until #inst "2023-01-01"`, params `{:multiple 2.5M :ceiling 0.25M}`;
- a row with `effective-from #inst "2024-04-01"` / `effective-until #inst "2026-01-01"`, params `{:multiple 2M :ceiling 0.20M}`;
- the always-available linear rows with nil bounds.

### 7.2 The selection rule — keyed on acquisition date, not transaction date

The one **deliberate divergence from ADR-026**: ADR-026 selects on `:transaction/effective-date` (the date of the posting being computed). For depreciation, the selection key is the **asset's `:asset/acquisition-date`** — because the *rule that governs an asset is fixed at acquisition for the asset's whole life*. A machine acquired 2021-06 keeps the 2020-22-window degressive schedule through 2031, even though the monthly depreciation *postings* in 2027 have a 2027 effective date.

So: when `kontor-asset` creates an `:asset-depreciation` book, it (or the l10n module on its behalf) resolves the `:depreciation-rule` whose window contains `:asset/acquisition-date`, and pins it as `:asset-depreciation/effective-rule`. The pin is permanent — the schedule does not re-resolve when statute changes later. This is the valid-time-not-tx-time argument (`doc/decisions.md:1415`): a 2026 amended-return recomputation of the 2021 asset must use the rule *legally in force on the 2021 acquisition date*, which the pinned ref captures exactly.

`kontor-asset` itself stays jurisdiction-free: it takes a resolved `:asset-depreciation/effective-rule` ref and passes it into `plan-schedule`. The *resolution* — "given acquisition date D and asset class C, which rule row?" — is an l10n helper (`kontor.l10n.de.asset/resolve-depreciation-rule`), structurally identical to ADR-026's tax-record selection. The kernel/companion ships the *pattern*; l10n ships the *windows*.

### 7.3 Why this is genuinely ADR-026 and not a new ADR

The selection-by-window logic, the open-interval-on-the-right semantics, the "overlapping windows → longest-effective-from wins + validation warning" tie-break (`doc/decisions.md:1406`) all transfer verbatim. The only new content is (a) the `:depreciation-rule` entity shape (l10n-owned, so it does not even need a kernel ADR — it is l10n-de's call) and (b) the documented divergence that the *selection key* is `:asset/acquisition-date` not `:transaction/effective-date`. That divergence is worth a paragraph in an `kontor-asset` ADR, not a standalone ADR.

---

## 8. Proposed ADR breakdown for Stage L′

Stage L′ is a companion module (`modules/asset/`), so its ADRs sit in `doc/decisions.md` alongside ADR-035 (`kontor-sales`), ADR-042 (`kontor-procurement`), ADR-043 (`kontor-collections`). Recommended: **four ADRs**.

| ADR | Decides | Scope |
|---|---|---|
| **ADR-A1 — `kontor-asset`: the `:asset` register + lifecycle** | The `:asset`, `:asset-class`, `:asset-event` entities; the `:asset/status` status-machine vocabulary; componentisation = `:asset/parent` (lean, no separate entity); the `:audit-doc`/`:approval-policy` governance on disposal + impairment. Establishes `kontor-asset` as a companion module. | Companion schema + lifecycle |
| **ADR-A2 — A depreciation area is a `:ledger`** | The central design call (§2): `:asset-depreciation` is per-(asset, ledger); rejects `:valuation-book` and a new `:depreciation-area` concept; documents the SAP-post-reconciliation rationale; per-ledger sum-to-zero gives the audit guarantee. | The parallel-book decision |
| **ADR-A3 — `DepreciationProvider` protocol** | The provider seam (§3); the companion-shipped built-ins (straight-line, declining-balance, SYD, units-of-production); the l10n-shipped impls (MACRS, AfA-degressive, CCA, full-expensing); registration via the `core.clj` provider registry; the effective-dated rule resolution divergence (§7 — key on `:asset/acquisition-date`, ADR-026 pattern, l10n owns the rule rows). | The computation seam + effective-dating |
| **ADR-A4 — Jahresabschluss extensions: Anlagengitter + cash-flow + equity-changes** | The generic `asset-roll-forward` report engine in `kontor-asset` (Anlagengitter arithmetic, jurisdiction-free; l10n ships layout); `compute-cash-flow` + `compute-equity-changes` added to kernel `kontor.financial-statements` (universal engines, l10n supplies definitions); the `:no-pending-depreciation` pre-close hook convention; the depreciation-run-before-close sequencing rule. | Year-end mechanics + the two kernel touches |

Possible merge: A1+A2 could be one ADR if the parallel-book argument is short, but separating keeps the central design call independently citable (it is the most contestable decision and the one a reviewer will most want to challenge). Keep them separate.

`kontor-l10n-de`'s `:depreciation-rule` entity and the AfA-Tabellen data are **l10n-de's own ADRs**, not Stage L′ ADRs — consistent with ADR-006 (l10n modules own their licenses and their data decisions).

---

## 9. Open design questions for the maintainer

1. **`:asset-depreciation/method-params` shape.** Provider config (rate multiple, % ceiling, MACRS table key, convention overrides) is heterogeneous per provider. Options: (a) a small `:asset-method-params/*` entity with a superset of optional attrs; (b) EDN-as-string (loses queryability); (c) a `:db.type/tuple`. Recommendation leans (a) for queryability, but it bloats the schema. **Maintainer call.**

2. **Componentisation: `:asset/parent` or a distinct `:asset-component` entity?** §4 leans lean (`:asset/parent` + an `:asset-class` flag — a component is just an asset whose parent is the whole). The counter-argument: IAS 16 components share the parent's *identity* for disposal but have *independent* depreciation. `:asset/parent` handles both. Confirm the lean reading is acceptable, or whether the SAP "sub-number" pattern (one asset, N sub-numbers) is wanted instead.

3. **Should `compute-cash-flow` and `compute-equity-changes` land in Stage L′ or be deferred to their own stage?** They are *driven by* the asset subsystem (depreciation add-back, revaluation surplus) but are not *of* it — they are universal statements. Landing them in Stage L′ keeps the Jahresabschluss story complete; deferring them keeps Stage L′ tight. **Recommendation: land at least `compute-cash-flow` (depreciation is its headline line); `compute-equity-changes` could defer unless the IAS 16 revaluation model is in Stage L′ v1 scope** — which raises Q4.

4. **Is the IAS 16 revaluation model in Stage L′ v1, or deferred?** The cost model (the overwhelming default — HGB does not even permit upward revaluation of most assets; US GAAP prohibits it) covers DE + US fully. The revaluation model is IFRS-only and pulls in OCI/revaluation-surplus mechanics + the equity statement. **Recommendation: ship the cost model + impairment in v1; gate the revaluation model behind an explicit "IFRS revaluation" follow-up** unless an early consumer (a UK/IFRS customer) needs it. Impairment (IAS 36 / HGB §253 außerplanmäßige Abschreibung) is *not* deferrable — both DE and IFRS mandate it, and a write-down is a far more common event than a revaluation.

5. **The CCA/UK pool shape — confirm it is purely l10n.** §1.3 / §5 argue the pool is an l10n-ca/uk construct (l10n models the pool as `:schedule/origin-entity`, member `:asset`s reference it). But a pool *does* have a status, a roll-forward, and disposal mechanics — it is arguably an `:asset` itself (`:asset/class :cca-pool`). If so, the companion needs nothing new and the l10n module just instantiates pool-shaped `:asset`s. Confirm this reading — it keeps the companion jurisdiction-free, which is the goal.

6. **Depreciation-run trigger ownership.** ADR-032 says the kernel does not own the trigger (`doc/decisions.md:1887`). `kontor-asset` ships `run-depreciation!` / `catch-up!` as *library functions*; *who calls them* (a consumer-app cron, a manual close-period step, the `kontor-process` workflow primitive from research note 21 if it ships) is out of `kontor-asset` scope. Confirm `kontor-asset` ships the runner *functions* but not a scheduler — consistent with ADR-032, but worth stating explicitly so a reviewer does not flag a "missing cron".

7. **Mid-quarter convention statefulness (US MACRS).** The mid-quarter convention is triggered by an aggregate property of *all* the year's additions (>40% in Q4). That means `MacrsProvider/plan-schedule` for one asset depends on *sibling assets acquired the same year* — it is not asset-local. The provider reads `db`, so it *can* query siblings, but it is a subtle coupling. Flag for the l10n-us implementer; the protocol's `db` parameter (§3) is deliberately there to allow it, but the maintainer should confirm the protocol shape (passing `db`, not just the asset spec) is acceptable.

# Research note 39 — `kontor-lease` market-pain study

What real customers actually complain about in lessee-side lease-accounting
software — the operational pain that purely-standards-reading (IFRS 16 /
ASC 842) misses. Research input for the **lessee lease-accounting companion**
(`kontor-lease`): put a lease on the balance sheet as a Right-of-Use (ROU)
asset + a lease liability, unwind the liability via effective-interest,
depreciate the ROU asset, and survive a remeasurement.

This is the **customer-pain angle only**. Parallel agents do the OSS / vendor
reference study and the internal-gap audit; synthesize all three before
drafting the `kontor-lease` ADRs.

Design context already locked (do NOT relitigate):

1. `kontor-asset` exists — `:asset` register + lifecycle (ADR-053), a
   **depreciation book is per `(asset, ledger)`** (ADR-054), and a
   `DepreciationProvider` protocol + `run-depreciation!` / `catch-up!` runner
   with event-aware `plan-schedule` (ADR-055). `plan-schedule` is pure, reads
   the book's `:schedule-occurrence` log, re-plans only the un-fired tail, and
   never restates fired periods — IAS 16 estimate-changes are prospective by
   construction.
2. `:ledger` (ADR-021) is the parallel-book primitive — sum-to-zero is enforced
   *per ledger*, each `:ledger` carries a `:ledger/framework` keyword
   (`:HGB`, `:IFRS`, `:tax-de`, …). `:ledger/type :statistical` is a book that
   never posts to the GL.
3. `:schedule` + `:schedule-occurrence` (ADR-032) is the recurring-posting
   primitive — the kernel records occurrences; the consumer computes per-period
   amounts (deliberately amount-agnostic).
4. `:entity` (ADR-031) gives per-`(entity, ledger, commodity)` sum-to-zero for
   multi-entity / transnational books. `:asset-event` (ADR-053) is the
   immutable mid-life-event pattern.
5. kontor is a kernel + companions. **No UI, no OCR, no contract management.**
   Abstracting a lease contract PDF into structured data is a consumer-app
   concern by ADR-010 / ADR-037. `kontor-lease` owns the *accounting* of a
   lease whose terms are already structured data.

## TL;DR

1. **Lease modifications & remeasurements are the single most-cited operational
   pain, and they are a P0 for our buyer.** A rent change, term extension, or
   partial termination re-measures the liability at a *current* discount rate
   and adjusts the ROU asset — and the partial-termination case has a
   **catch-up gain/loss to the P&L** plus *two permissible methods* under
   ASC 842 (proportionate-to-liability vs proportionate-to-ROU) where IFRS 16
   allows only one. Excel "schedules go messy, formulas break" exactly here;
   every vendor (Trullion, EZLease) gets "extending/revising a lease is
   cumbersome" reviews. kontor's substrate is *unusually well-positioned* — the
   event-aware `plan-schedule` (ADR-055) already re-plans the un-fired tail
   without restating fired periods — but `kontor-lease` must design the
   **`:lease-event` remeasurement entity** (mirroring `:asset-event`) and the
   liability-side re-plan, including the modification-vs-separate-lease
   decision and the catch-up posting. This is **design call #1**.
2. **The discount rate (IBR) is the #2 pain and an audit-scrutiny magnet — P1
   structurally, P0 at audit.** "One of the biggest areas of confusion";
   sourcing it, the per-lease-vs-portfolio question, *when it must be
   reassessed* (every modification, every reassessment of term — at the
   *then-current* rate, not commencement). The liability is acutely sensitive
   to it. kontor cannot *source* an IBR (that is treasury/consumer data), but
   `kontor-lease` must make the rate a **first-class, effective-dated,
   per-lease attribute with an audit-doc justification ref** — never a number
   buried in a schedule. The reassessment-triggers-a-new-rate mechanic is part
   of design call #1.
3. **The ROU-asset + liability schedule mechanics are P1 and mostly a
   `DepreciationProvider`-shaped problem kontor already half-solves.** The ROU
   asset depreciates straight-line — that is *literally* `StraightLineProvider`
   under a per-`(asset, ledger)` book. The liability unwind is effective-
   interest — that is a **`LeaseLiabilityProvider`** (the same protocol
   pattern, new instance) computing `interest = opening-balance × period-rate`,
   `principal = payment − interest`. The awkward edges — mid-period
   commencement, payment-in-advance vs in-arrears (advance shifts period 0),
   rent-free periods, stepped rents, the end-of-lease rounding true-up — are
   *exactly* the edges `StraightLineProvider` and ADR-055's "last period
   absorbs the rounding remainder" already handle. This is **largely substrate
   reuse**, but the liability provider is a genuine new primitive.
4. **Operating-vs-finance classification (ASC 842) is a P1 the parallel-ledger
   substrate handles cleanly — but it is a *posting-geography* decision, not a
   schedule decision.** Both classes capitalise the same ROU asset + liability;
   they differ *only in the P&L*: a finance lease books interest + amortization
   separately, an operating lease books a single straight-line lease expense
   (with a plug that keeps total expense straight-line while the liability
   unwinds on effective-interest). `kontor-lease` must own a `:lease/classification`
   facet and **two posting builders** — and the operating-lease "expense plug"
   is subtle enough to deserve its own attention. IFRS 16 has no dual model
   (single finance-style) — which the `:ledger/framework` axis handles for free
   when the same lease runs under both standards.
5. **Multi-standard / multi-entity is a P1 that the `:ledger` + `:entity`
   substrate genuinely neutralises** — better than the surveyed tools, where
   "subledger won't reconcile to the ERP across entities and currencies" is a
   named year-end pain. The *same lease* under IFRS 16 **and** HGB/US-GAAP
   **and** tax is N depreciation-style books per `(lease, ledger)`, exactly
   ADR-054's model. The genuine new wrinkle is **foreign-currency leases**: the
   liability is a *monetary* item (retranslate at closing rate, FX difference
   to P&L) while the ROU asset is *non-monetary* (stays at historical rate, not
   retranslated). That asymmetry is a `kontor-lease` posting rule, not a
   substrate gap, but it must be designed explicitly.
6. **Variable / short-term / low-value / embedded leases are P1-P2 and split
   cleanly into "kontor's job" and "not kontor's job."** The short-term (≤12mo)
   and IFRS-16 low-value exemptions are a *classification flag* that routes the
   lease to straight-line expense with **no balance-sheet entry** — trivial
   once `:lease/classification` exists. CPI/index-linked variable payments are
   the real content: IFRS 16 remeasures the liability on an index change,
   ASC 842 expenses it in-period (unless another trigger fires) — a
   framework-divergent rule `kontor-lease` must encode. Usage-based variable
   payments are just period expense. **Embedded-lease *identification* (is
   there a lease inside this service contract?) is explicitly a
   consumer-app / contract-abstraction concern — not the kernel's job** — but
   once identified, the lease+non-lease *component split* is a `kontor-lease`
   allocation helper.
7. **Period close, disclosure, and the subledger-to-GL tie-out are P1 and the
   substrate's strongest win.** The maturity analysis, the liability
   roll-forward, the ROU-asset roll-forward, the disclosure tables — "the
   single most common year-end problem is the leasing subledger not
   reconciling to the ERP." In kontor the lease subledger *is* the
   append-only posting + `:schedule-occurrence` log; the roll-forward and
   maturity analysis are datalog queries over facts that already exist,
   bitemporally sound, tying to the GL by construction (per-ledger sum-to-zero).
   This is a *reporting-helper* deliverable, not an architecture gap.
8. **Transition / data-quality / completeness is real pain but mostly *not*
   the kernel's job.** "Did we find all our leases?" — the completeness
   assertion is the most-scrutinised ASC 842 audit area — is a
   contract-discovery / abstraction problem (consumer app, OCR, process). What
   `kontor-lease` *can* own: making the initial-population *posting* clean
   (transition entries dated correctly, the modified-retrospective
   cumulative-catch-up as ordinary postings), and the bitemporal audit trail
   that proves *when* each lease entered the books.
9. **The genuine new primitives `kontor-lease` must design are exactly four:**
   (a) the `:lease` entity + `:lease/classification` facet + the `:lease-event`
   remeasurement-event entity; (b) the **`LeaseLiabilityProvider`** protocol
   (effective-interest unwind, payment-timing aware) — the liability twin of
   `DepreciationProvider`; (c) the **two posting builders** (finance vs
   operating, the latter with the straight-line expense plug) plus the
   modification / partial-termination catch-up posting; (d) the **IBR as a
   first-class effective-dated attribute** with a justification ref and a
   reassessment mechanic. Everything else — the ROU depreciation, the
   parallel-standard books, the roll-forward reports, period locking — is
   composition of existing kernel + `kontor-asset` primitives.

---

## 1. Lease modifications & remeasurements — P0, very high frequency

**What it is.** After commencement, the lease changes: a rent increase or
decrease, a term extension or early-termination option exercised, a
partial termination (give back two floors of five), a change in the
assessment of whether a renewal option will be exercised. Each of these
**remeasures the lease liability** — re-discount the revised cash flows at a
**current** discount rate — and **adjusts the ROU asset** by the same amount.
The partial-termination case additionally throws a **catch-up gain or loss to
the P&L**: the liability reduction and the ROU-asset reduction are *not* equal,
and the difference hits income immediately.

**Why it is the worst pain.**

- It is the **single most-cited operational complaint** in the surveyed
  literature. Excel schedules "go messy and prone to broken formulas"
  *specifically* at modifications
  ([Zeroed-In — ASC 842: Why Excel Isn't Always Enough](https://www.zi.consulting/zeroed-insights/asc-842-lease-accounting-excel-vs-software),
  [Crunchafi — Hidden Costs of Spreadsheets for ASC 842](https://www.crunchafi.com/blog/the-hidden-costs-of-clients-using-spreadsheets-to-implement-asc842)).
  Dedicated tools don't escape it either: Trullion and EZLease both draw
  reviews that "extending or revising existing lease agreements is a cumbersome
  process"
  ([SelectHub — Trullion vs EZLease](https://www.selecthub.com/lease-accounting-software/trullion-vs-ezlease/)),
  and LeaseQuery is dinged that "it's not easy to adjust certain information
  after approval, requiring users to re-enter"
  ([G2 — LeaseQuery reviews](https://www.g2.com/products/leasequery-powered-by-finquery/reviews)).
- The **modification-vs-separate-lease** fork is a genuine judgment call: a
  modification that grants an *additional* right-of-use at a *standalone price*
  is a **separate new lease**; anything else is a modification of the existing
  one ([Deloitte DART 8.6 — Lease Modifications](https://dart.deloitte.com/USDART/home/codification/broad-transactions/asc842-10/roadmap-leasing/chapter-8-lessee-accounting/8-6-lease-modifications),
  [FinQuery — Lease Modifications and Remeasurements under ASC 842](https://finquery.com/blog/lease-modifications-and-remeasurements-under-asc-842/)).
- The **partial-termination ROU adjustment has two permissible methods under
  ASC 842** — reduce the ROU asset proportionate to the *change in liability*,
  or proportionate to the *remaining ROU asset* — and they produce different
  catch-up gains/losses. **IFRS 16 allows only the second.** An entity must
  pick one and apply it consistently as a policy election
  ([Occupier — Partial Lease Terminations under ASC 842](https://www.occupier.com/blog/partial-lease-terminations/),
  [Cradle — How to account for a (partial) lease termination](https://www.cradleaccounting.com/insights/how-to-account-for-a-lease-termination-including-partial-lease-terminations-under-asc-842)).
- **Multi-part modifications** — a term extension *and* a consideration
  decrease in one amendment — must be unbundled and sequenced correctly
  ([IRIS — ASC 842 Lease Modifications & Remeasurement](https://www.irisglobal.com/blog/asc-842-lease-modifications-remeasurement/)).
- The standard's own implementation guidance stresses **documentation at the
  time of change, not reconstructed later** — the original terms, the
  remeasurement basis, the rate used
  ([Black Owl — Navigating Lease Modifications and Reassessments](https://blackowlsystems.com/lease-modifications-and-reassessments/)).

**Severity:** **P0** for both DE-GmbH and US-LLC mid-market lessees. A
mishandled remeasurement misstates the balance sheet *and* the P&L (the
catch-up), and it is an audit finding.

**Does kontor's substrate address it?** *The hardest mechanical part — "re-plan
the tail without restating history" — is already solved; the entities and the
liability-side re-plan are not.*

- ADR-055's **event-aware `plan-schedule`** is precisely the right shape: it
  reads the book's `:schedule-occurrence` log, keeps every already-fired
  period's actual amount untouched (`:fired? true`), and re-plans *only the
  un-fired tail*. A lease modification applied to the book mid-life is picked
  up automatically on the next run, with fired periods never restated — exactly
  the "modifications are prospective" requirement. The asset module built this
  for useful-life revisions; a lease remeasurement is the same motion.
- `:asset-event` (ADR-053) is the **immutable mid-life-event pattern** — kind,
  date, amount, the GL transaction it posts, a required `:audit-doc`
  justification ref. A lease modification needs the *identical* pattern: a
  `:lease-event` with `:kind` ∈ `{:rent-change :term-extension
  :partial-termination :full-termination :rate-reassessment :renewal-option-reassessment}`.
- Per-ledger sum-to-zero means the catch-up gain/loss posting balances by
  construction, in whichever framework's ledger it belongs.

**What's genuinely missing.** Three things, and they are design call #1:

1. The **`:lease-event` entity** — the remeasurement-event twin of
   `:asset-event`. Immutable, carries the revised cash-flow profile, the new
   discount rate, the modification-vs-separate-lease determination, and a
   justification ref.
2. The **liability-side re-plan**. `DepreciationProvider.plan-schedule`
   re-plans the *asset* tail. The *liability* needs the symmetric thing — a
   `LeaseLiabilityProvider` (see §3) whose `plan-schedule` re-discounts the
   revised cash flows at the new rate over the revised term, reading the
   `:schedule-occurrence` log so fired periods stay fired.
3. The **catch-up posting builder** — for partial termination, compute the ROU
   reduction (under the entity's elected method), the liability reduction, and
   the gain/loss plug; emit the balanced posting. The two-methods election is a
   `:lease/partial-termination-method` policy attribute.

**Remediation hint — genuine new primitives, but the substrate carries the
hardest 60%.** Design `:lease-event`, the `LeaseLiabilityProvider`, and the
catch-up posting builder. This is **design call #1** — surface it with
AskUserQuestion, specifically: (a) is the modification-vs-separate-lease
determination a caller input or does `kontor-lease` infer it? (recommend:
caller input, with a documented standalone-price helper — judgment is not the
kernel's job); (b) is the partial-termination method a per-lease policy
attribute or a per-entity one? (recommend: per-entity default, per-lease
override, since ASC 842 wants consistency but IFRS 16 forces the second method
regardless — so it is effectively per-ledger).

---

## 2. The discount rate (IBR) — P1 structurally, P0 at audit, very high frequency

**What it is.** The lease liability is the present value of the lease payments,
discounted at the rate implicit in the lease if readily determinable —
almost never, for a lessee — otherwise the lessee's **incremental borrowing
rate (IBR)**: the rate it would pay to borrow, on a collateralised basis, an
amount equal to the lease payments, over a similar term, in a similar economic
environment.

**Why it is a pain.**

- It is **"one of the biggest areas of confusion for companies adopting
  ASC 842"** — "a lot of confusion about which rate to use, where to find the
  rates, and how to calculate them," with "no one-size-fits-all approach"
  ([Thomson Reuters — ASC 842 & the IBR: Overcoming the challenges](https://tax.thomsonreuters.com/blog/asc-842-the-incremental-borrowing-rate-overcoming-the-challenges/),
  [RSM — Calculating the IBR as a lessee](https://rsmus.com/content/dam/rsm/insights/financial-reporting/1pdf/asc-842-calculating-the-incremental-borrowing-rate-as-a-lessee.pdf)).
- **The liability is acutely sensitive to it.** "Choosing the right IBR
  directly affects the size of the lease liability and ROU asset… this
  significant impact likely explains why auditors place scrutiny on IBR
  calculations" ([FinQuery — Incremental Borrowing Rate](https://finquery.com/blog/incremental-borrowing-rate-discount-rate-asc-842-ifrs-16-gasb-87/)).
- **Per-lease vs portfolio rate.** ASC 842 permits a portfolio approach;
  IFRS 16 contemplates it less freely. Even the IASB's own IFRIC discussion
  notes *diversity in views* on whether "similar term" means the lease term as
  defined or the specific payment profile
  ([IFRS.org — Lessee's incremental borrowing rate](https://www.ifrs.org/-/media/feature/meetings/2019/june/ifric/ap2-ifrs-16-incremental-borrowing-rate.pdf)).
- **It must be reassessed.** A modification, or a reassessment of the lease
  term or a purchase option, requires a **revised discount rate at the
  then-current date** — not the commencement rate
  ([PwC Viewpoint 2.6 — Lease reassessment, modification, and remeasurement](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/utilities_and_power_/utilities_and_power__US/chapter_2_leases_US/2_6_lease_reassessment.html)).
- For **foreign-currency leases the IBR must align with the currency of the
  lease payments, not the reporting currency**
  ([Bloomberg — Impact of incremental borrowing rates](https://www.bloomberg.com/professional/insights/trading/impact-incremental-borrowing-rates-ibr-new-lease-accounting-requirements/)).

**Severity:** **P1** in normal operation, **P0 at audit** — it is a named
audit-scrutiny area, and a wrong rate misstates the balance sheet from day one.

**Does kontor's substrate address it?** *Partly — the bitemporal /
effective-dated machinery is the right tool; the modelling is not done.*

- kontor **cannot and should not source** an IBR — that is treasury data, a
  consumer-app / customer input. Correctly not the kernel's job (cf. the
  `TaxProvider` "we ship the protocol, not the rates" stance).
- But the substrate's **effective-dated pattern** (ADR-026 `:tax/effective-from`,
  ADR-055's effective-dated rule resolution keyed on a valid-time) is *exactly*
  the right shape for "the rate in force at commencement, and the new rate in
  force from the modification date." A reassessment does not overwrite the old
  rate — it adds a new effective-dated fact.
- The **`:audit-doc` justification-ref pattern** (already required on
  `:asset-event` `:impairment` / `:disposal`) is the audit-scrutiny answer:
  the IBR carries a mandatory justification ref pointing at the rate-derivation
  workpaper.

**What's missing.** The IBR is not yet a modelled thing at all (no `kontor-lease`
exists). It must be a **first-class, effective-dated, per-lease attribute** —
`:lease/discount-rate` plus the effective-dated history, never a number buried
in a computed schedule — with a justification ref. The reassessment mechanic
(a `:lease-event` of kind `:rate-reassessment` introduces a new effective-dated
rate) is part of design call #1.

**Remediation hint — substrate provides the pattern; `kontor-lease` ships the
attribute.** Model the IBR as effective-dated per-lease data with a mandatory
`:audit-doc` ref. Do *not* build an IBR calculator (consumer / treasury job;
say so explicitly). The portfolio-rate question is a *data-entry* convenience
(apply one rate to a tagged set of leases) — a consumer-app concern, but
`kontor-lease` should make the rate a per-lease fact so a portfolio is just
"many leases that happen to share a rate," not a special entity.

---

## 3. The schedule / amortization mechanics — P1, high frequency

**What it is.** Two intertwined schedules over the life of the lease:

- **The liability unwind** — effective-interest: each period,
  `interest = opening-liability × period-rate`, `principal = payment −
  interest`, `closing-liability = opening − principal`.
- **The ROU-asset depreciation** — straight-line over the shorter of the lease
  term and the asset's useful life (finance leases that transfer ownership: over
  useful life).

The awkward edges are where it breaks: **mid-period commencement** (the lease
starts on the 17th); **payment timing** — *in advance* (annuity-due: payment at
period start, so schedule line 0 carries a date and the first period has no
interest accrual on the first payment) vs *in arrears* (ordinary annuity);
**rent-free / abatement periods** at inception (the expense is still
straight-lined; the liability still accretes); **stepped rents** (escalating
fixed payments — the actual payment varies per line but the operating-lease
expense is leveled); the **rounding / true-up at lease end** (the last period
must drive the liability exactly to zero and the ROU asset exactly to its
residual).

**Why it breaks.** "Complex calculations involving incremental borrowing rates,
variable lease terms… are difficult to model accurately" in Excel
([Zeroed-In](https://www.zi.consulting/zeroed-insights/asc-842-lease-accounting-excel-vs-software)).
Payment-in-advance vs in-arrears is a documented source of off-by-one-period
errors — "if the payment method is In Advance, the commencement date is on
amortization line 0; if In Arrears, line zero has no date"
([Oracle JDE — Processing Lease Commencement](https://docs.oracle.com/en/applications/jd-edwards/asset-lifecycle/9.2/eoarm/processing-lease-commencement-for-balance-sheet-lessee.html)).
Rent-free periods "should be averaged with the payments over the life of the
lease to give an equal expense amount each month" — and stepped rents the same
— but the *liability* schedule still uses the actual cash flows
([FinQuery — Rent Abatement & Rent-Free Period Accounting](https://finquery.com/blog/rent-abatement-and-rent-free-period-accounting/)).

**Severity:** **P1**. The mechanics are well-defined by the standard; the pain
is *getting them exactly right at the edges*, every period, tying to the cent.

**Does kontor's substrate address it?** *Yes, to a remarkable degree — the ROU
side is almost free, the liability side is a new provider on a proven pattern.*

- The **ROU-asset depreciation is literally `StraightLineProvider`** (ADR-055)
  under a per-`(asset, ledger)` book: `(depreciable-base − accumulated) /
  remaining-periods`, with **"the last period absorbs the rounding
  remainder"** — that is the end-of-lease true-up, already built. Mid-period
  commencement is the `:in-service-date` ≠ `:acquisition-date` distinction
  ADR-053 *already models* (DE "Anschaffung" vs "betriebsbereit"). The ROU
  asset *is* an `:asset`; `kontor-lease` reuses `kontor-asset` wholesale here.
- The **liability unwind is a new `LeaseLiabilityProvider`** — the same
  protocol shape as `DepreciationProvider` (`provider-id` / `plan-schedule`),
  returning a `:periods` vector. Effective-interest is *simpler* than declining-
  balance depreciation, which the asset module already ships. Payment-in-advance
  vs in-arrears is a `:lease/payment-timing` flag the provider reads — the same
  way `DecliningBalanceProvider` reads `:convention`.
- **Stepped rents and rent-free periods** are just a non-uniform cash-flow
  vector fed to the provider — the liability schedule consumes actual cash
  flows, the operating-lease *expense* is the leveled average (the §4 expense
  plug). ADR-032's `:schedule` is already amount-agnostic precisely so the
  provider can compute non-uniform amounts.
- `:schedule` + `:schedule-occurrence` (ADR-032) is the materialised-occurrence
  log both schedules write to; the runner pattern (`run-depreciation!` /
  `catch-up!`) is reusable as `run-lease-period!`.

**What's missing.** The `LeaseLiabilityProvider` protocol + a built-in
effective-interest impl, and a `:lease/payment-timing` (`:advance` |
`:arrears`) attribute. That is genuinely new code — but it is a *small,
well-patterned* primitive, not an architecture question.

**Remediation hint — mostly substrate reuse + one new provider.** Reuse
`kontor-asset` for the ROU side outright (the ROU asset is an `:asset`, its
book is a `(asset, ledger)` depreciation book, `StraightLineProvider` does the
work). Ship a `LeaseLiabilityProvider` protocol + `EffectiveInterestProvider`
built-in for the liability side. This is **design call #3** only in the narrow
sense of "confirm the liability provider mirrors `DepreciationProvider` and the
ROU asset reuses `kontor-asset` rather than `kontor-lease` re-implementing
depreciation" — the answer is almost certainly yes, but it should be a
conscious ADR.

---

## 4. Operating vs finance classification (ASC 842) — P1, high frequency

**What it is.** ASC 842 keeps a **dual model** for lessees. Both classes put
the *same* ROU asset and lease liability on the balance sheet — the difference
is **entirely in the P&L (the "geography")**:

- **Finance lease** — book **interest expense** (on the liability) and **ROU
  amortization** *separately*. Front-loaded total expense (interest is higher
  early). Interest is often an operating or financing cash-flow item;
  amortization is non-cash.
- **Operating lease** — book a **single, straight-line lease expense**. But the
  liability still unwinds on effective-interest and the ROU still has to be
  amortized — so the ROU "amortization" each period is a **plug**:
  `straight-line expense − liability interest`, reverse-engineered to keep total
  expense level. This plug is the subtle part.

Classification is by the five ASC 842 criteria (transfer of ownership, bargain
purchase option, lease term ≥ major part of useful life, PV ≥ substantially all
of fair value, specialised asset). **IFRS 16 has no dual model** — for a lessee
nearly all leases are finance-style (single model).

**Why it is a pain.** "While the balance-sheet impact is now similar… the
income statement treatment and financial outcomes remain materially different —
which is where scenario modeling becomes essential"
([RecVue — Scenario modeling under ASC 842](https://www.recvue.com/blog/asc-842-scenario-modeling-operating-vs-finance-lease/)).
"Modifications, renewals, or reassessments can trigger reclassification"
([Deloitte DART 8.3 — Lease Classification](https://dart.deloitte.com/USDART/home/codification/broad-transactions/asc842-10/roadmap-leasing/chapter-8-lessee-accounting/8-3-lease-classification)).
For **dual reporters the same lease is operating under ASC 842 and finance-style
under IFRS 16**, requiring the two P&L geographies side by side
([MRI — ASC 842 vs IFRS 16 compliance checklist](https://www.mrisoftware.com/blog/asc-842-vs-ifrs-16-2026-compliance-checklist-for-lease-accounting/)).

**Severity:** **P1**. The balance sheet is the same; the P&L geography and the
expense *timing* differ materially — and reclassification on modification is a
live trap.

**Does kontor's substrate address it?** *The parallel-ledger axis handles the
dual-standard case for free; the operating-lease expense plug needs deliberate
posting logic.*

- The "same lease, operating under ASC 842, finance under IFRS 16" case is
  **exactly ADR-054's model**: N depreciation-style books per `(lease,
  ledger)`, each `:ledger` carrying a `:ledger/framework`. The IFRS-ledger book
  posts interest + amortization; the US-GAAP-ledger book posts the single
  straight-line expense. Per-ledger sum-to-zero keeps them from netting. No new
  primitive — this is the asset module's "Handelsbilanz vs Steuerbilanz side by
  side" applied to leases.
- What is *not* free: the **two posting builders**. A finance lease posts
  `Dr Interest Expense / Dr ROU Amortization / Cr Lease Liability / Cr Accum.
  Amortization` (roughly); an operating lease posts `Dr Lease Expense (level) /
  Cr Lease Liability (interest portion) / Cr ROU asset (the plug)`. The
  operating-lease plug — ROU reduction = level expense − interest — is the
  subtle posting that gets hand-rolled wrong in spreadsheets.
- Reclassification on modification is a `:lease-event` (§1) that changes
  `:lease/classification`; ADR-055's prospective re-plan handles the schedule
  side.

**Remediation hint — substrate handles the multi-standard axis; `kontor-lease`
ships two posting builders.** Model `:lease/classification` (`:finance` |
`:operating` | `:short-term` | `:low-value`) as a per-`(lease, ledger)` facet —
it *must* be per-ledger because the same lease classifies differently under
ASC 842 vs IFRS 16. Ship `post-finance-lease-period` and
`post-operating-lease-period` builders; the latter computes the expense plug.
This is part of **design call #2** (the posting builders).

---

## 5. Multi-standard / multi-entity / multi-currency — P1, high frequency

**What it is.** The same lease run simultaneously under **IFRS 16 AND local
GAAP (HGB / US-GAAP) AND tax**; **intercompany leases** (parent leases to
subsidiary — eliminated in consolidation); **foreign-currency leases** (a
EUR-functional GmbH with a USD-denominated lease).

**Why it is a pain.** "One of the most common problems encountered at year-end
is the difficulty reconciling lease expenses and balances coming from your
consolidated reporting systems and the disclosure reports coming out of your
leasing subledger… the lease liabilities in your ERP may not reconcile with the
maturity analysis report"
([Nakisa — Year-end audits of lease accounting](https://nakisa.com/resources/how-to-prepare-for-year-end-audits-of-lease-accounting-presentation-and-disclosures/)).
On currency: **the lease liability is a *monetary* item — retranslate at the
closing rate each period, FX difference to P&L — while the ROU asset is a
*non-monetary* item — it stays at the historical rate and is *not*
retranslated**, with depreciation on the historical rate
([Trullion — FX Rates & IFRS 16](https://trullion.com/blog/foreign-exchange-rates-ifrs-16-accounting/),
[PwC Viewpoint 4.7 — Foreign currency denominated leases](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/foreign_currency/foreign_currency__2_US/chapter_4_foreign_cu_US/4_7_Foreign_currency_denominated_leases.html)).
This asymmetry "introduces significant exchange-rate volatility into the income
statement — often an unexpected consequence of IFRS 16 adoption." Intercompany:
"internal leases must be eliminated in consolidation; each subsidiary measures
in its functional currency, then IAS 21 translation at group consolidation"
([House of Control — IFRS 16 across entities and currencies](https://www.houseofcontrol.com/blog/ifrs-16-reporting-across-entities-and-currencies)).

**Severity:** **P1**. Acute for any DE-GmbH in a group, or any lessee with a
cross-border lease.

**Does kontor's substrate address it?** *The multi-standard and multi-entity
axes are genuinely neutralised; the currency asymmetry is a posting rule to
design.*

- **Multi-standard** is ADR-054 verbatim: a book per `(lease, ledger)`,
  `:ledger/framework` distinguishing IFRS / HGB / tax. The "subledger won't
  reconcile to the ERP" pain is **structurally absent** — the lease subledger
  *is* the GL postings (per-ledger sum-to-zero), there is no separate subledger
  to drift. This is a positioning win worth naming in an RFP.
- **Multi-entity** is ADR-031: `:posting/entity` + per-`(entity, ledger,
  commodity)` sum-to-zero. An intercompany lease is two leases (lessor-side is
  out of `kontor-lease`'s lessee scope, but the *intra-group lessee* posting
  carries `:posting/entity`); elimination is a consolidation-ledger concern the
  `:entity` model already supports.
- **Multi-currency** — the kernel is `BigDecimal` + commodity tag throughout
  (ADR money rules). What `kontor-lease` must encode is the **monetary /
  non-monetary asymmetry**: the liability schedule is in the lease currency and
  gets a period-end retranslation posting (FX gain/loss to P&L); the ROU asset
  is booked once at the historical rate and never retranslated. This is a
  *posting rule*, not a substrate gap — but it is subtle and must be an
  explicit ADR.

**Remediation hint — substrate carries multi-standard + multi-entity; design
the FX rule.** Lean on ADR-054 (book per `(lease, ledger)`) and ADR-031
(`:posting/entity`) directly. The genuine new design work is the **foreign-
currency posting rule**: liability retranslated at closing rate (FX → P&L),
ROU asset frozen at historical rate. Make it an explicit `kontor-lease` ADR;
the IBR-currency-alignment point from §2 rides along (the rate must match the
lease's commodity).

---

## 6. Variable, short-term, low-value & embedded leases — P1/P2, medium-high frequency

**What it is.** Four exemption / edge cases:

- **Variable lease payments** — payments that depend on an index/rate (CPI,
  LIBOR-successor) or on usage (a percentage-of-sales retail lease, a per-mile
  vehicle lease).
- **Short-term exemption** — leases with a term ≤ 12 months may be kept *off*
  the balance sheet, expensed straight-line. Both standards.
- **Low-value exemption** — **IFRS 16 only**: leases of low-value assets
  (≈ USD 5,000 when new — laptops, small office equipment) may be kept off the
  balance sheet. **ASC 842 has no equivalent.**
- **Embedded leases** — a contract that *is not called* a lease but contains
  one: a service contract that gives the customer the right to control the use
  of an identified asset (a dedicated server, a specific delivery truck).

**Why it is a pain.**

- **CPI/index-linked variable payments diverge between standards.** Under
  ASC 842, an index change is recognised as **period expense** — the liability
  is *not* reassessed unless another remeasurement trigger fires. Under
  IFRS 16, **the liability and ROU asset are remeasured** whenever the index/
  rate change actually changes the cash flows
  ([Deloitte DART 6.3 — Variable Lease Payments That Depend on an Index or Rate](https://dart.deloitte.com/USDART/home/codification/broad-transactions/asc842-10/roadmap-leasing/chapter-6-lease-payments/6-3-variable-lease-payments-that),
  [FinQuery — IFRS 16 vs ASC 842 differences](https://finquery.com/blog/ifrs-16-vs-asc-842-us-gaap-lease-accounting-differences/)).
  So the *same* CPI bump is a one-line expense in the US ledger and a
  full remeasurement in the IFRS ledger.
- **Usage-based variable payments** are *not* in the liability at all under
  either standard (they are not "in-substance fixed") — pure period expense.
- **Embedded-lease identification** is "among the trickiest elements" and the
  **completeness assertion is one of the most-scrutinised ASC 842 audit areas**
  — auditors do "look-back" procedures on post-effective-date service contracts
  ([Deloitte — Embedded Lease Accounting under ASC 842](https://www2.deloitte.com/us/en/pages/audit/articles/embedded-lease-accounting-identification-asc-842.html),
  [PwC — Embedded leases reporting under ASC 842](https://www.pwc.com/us/en/services/consulting/deals/library/embedded-leases.html)).

**Severity:** **P1** for variable payments (every CPI-linked real-estate lease
hits this) and the short-term/low-value flag; **P2** for embedded-lease
*identification* (because that part is **explicitly not the kernel's job**).

**Does kontor's substrate address it?** *Short-term/low-value: trivially, once
classification exists. Variable payments: a framework-divergent rule to encode.
Embedded-lease identification: correctly out of scope.*

- **Short-term and low-value** are just `:lease/classification` values
  (`:short-term`, `:low-value`) that route the lease to a straight-line
  expense posting with **no balance-sheet entry** — no ROU asset, no liability,
  no schedule. Once §4's classification facet exists, this is a posting-builder
  branch, not new architecture. The low-value-is-IFRS-only point is handled by
  it being per-`(lease, ledger)`: `:low-value` in the IFRS ledger, `:operating`
  or `:finance` in the US-GAAP ledger.
- **Index-linked variable payments** need the framework-divergent rule:
  IFRS-ledger → a `:lease-event` of kind `:rate-reassessment` (full
  remeasurement, §1 machinery); US-GAAP-ledger → a plain period-expense
  posting. `kontor-lease` must encode this fork. It is real content but it is
  *composition* of the §1 remeasurement primitive + an ordinary posting.
- **Usage-based variable payments** are an ordinary period-expense posting —
  the consumer supplies the amount (kontor does not forecast sales). Trivial.
- **Embedded-lease identification** — "does this service contract contain a
  lease?" — is a **contract-abstraction / judgment / process concern**,
  explicitly *not* the kernel's job (ADR-010 / ADR-037: no OCR, no contract
  management). What `kontor-lease` *does* own: once a contract is identified as
  containing a lease, the **lease/non-lease component split** — allocating the
  total consideration between the lease component and the service component on
  a relative-standalone-price basis — is a `kontor-lease` allocation helper
  (and ASC 842 offers a practical expedient to *not* separate, which is a
  policy flag).

**Remediation hint — classification facet covers short-term/low-value; encode
the variable-payment fork; punt embedded-lease identification.** Add
`:short-term` / `:low-value` to the `:lease/classification` enum (§4). Encode
the index-linked-variable-payment fork as "IFRS-ledger remeasures, US-ledger
expenses." Ship a component-split allocation helper. **Explicitly document that
embedded-lease *identification* is a consumer-app concern** — like the BPMN
editor and the OCR in prior notes, say so out loud so it is not mistaken for a
gap.

---

## 7. Period close & disclosure — P1, high frequency

**What it is.** At period close: run the period's lease postings (interest,
amortization / expense), produce the **maturity analysis** (undiscounted future
lease payments by year, finance separately from operating), the **lease
liability roll-forward** and **ROU-asset roll-forward**, the quantitative
disclosure tables (weighted-average remaining term, weighted-average discount
rate, cash paid, ROU assets obtained), and **tie the lease subledger to the
GL**. Lock the closed period.

**Why it is a pain.** "The single most common problem at year-end is the
leasing subledger not reconciling to the ERP… the lease liabilities in your ERP
may not reconcile with the maturity analysis report"
([Nakisa](https://nakisa.com/resources/how-to-prepare-for-year-end-audits-of-lease-accounting-presentation-and-disclosures/)).
Common disclosure mistakes: "missing variable lease details, unreconciled
maturity tables, over-summarized narratives, inconsistent terms, outdated
prior-year data" ([Deloitte DART 15.2 — Lessee Disclosure Requirements](https://dart.deloitte.com/USDART/home/codification/broad-transactions/asc842-10/roadmap-leasing/chapter-15-disclosure/15-2-lessee-disclosure-requirements)).
NetLease reviewers explicitly want "one report to reconcile all main lease
accounts" — i.e. the tie-out is a named missing feature even in a dedicated
tool ([G2 — NetLease by Netgain](https://www.g2.com/products/netlease-by-netgain/reviews)).

**Severity:** **P1**.

**Does kontor's substrate address it?** *Yes — this is the substrate's
strongest win, exactly as inventory's roll-forward was in note 36.*

- **The lease subledger *is* the append-only posting + `:schedule-occurrence`
  log.** There is no separate subledger to drift from the GL — the
  "subledger won't reconcile to the ERP" pain is *structurally absent*, the
  same way inventory's `on-hand-value` can't diverge from the GL inventory
  account because they read one fact log.
- **The roll-forwards are datalog queries over existing facts.** Liability
  roll-forward: opening = liability balance as-of period start; additions =
  new leases in-period; accretion = interest postings in-period; payments =
  principal postings in-period; remeasurements = `:lease-event` adjustments
  in-period; closing = balance as-of period end. ROU roll-forward likewise.
  No separate roll-forward table.
- **The maturity analysis is a query over the future `:schedule-occurrence`
  rows** — the un-fired periods, undiscounted, bucketed by year, partitioned by
  `:lease/classification`. Bitemporally sound — "the maturity analysis as we
  disclosed it at the prior year-end" is `:as-of-tx` + `:as-of-valid`.
- **Period locking** — the kernel's period open/close + sealing reject a
  lease posting into a closed period via the *same* middleware as any posting.
  `kontor-lease` inherits it for free.
- **Weighted-average disclosures** (remaining term, discount rate) are
  aggregations over the `:lease` entities; cash-paid is a query over the
  payment postings.

**Remediation hint — substrate covers it; ship the reporting helpers.**
`kontor-lease` ships `lease-maturity-analysis`, `lease-liability-rollforward`,
`rou-asset-rollforward`, and the weighted-average disclosure aggregations as
thin datalog helpers over existing facts — *reporting* deliverables, not new
primitives. The "one report to reconcile all lease accounts" that NetLease
users beg for is, here, a tie-out helper asserting `Σ liability schedule
balances == GL liability account balance per (entity, ledger)` — and it can
only ever pass, because they are the same facts.

---

## 8. Transition & data quality — P1 pain, mostly NOT the kernel's job

**What it is.** The initial mess: abstracting hundreds of existing lease
contracts into structured data, the **completeness** question ("did we find
*all* our leases?"), choosing a transition method (modified-retrospective with
a cumulative catch-up vs full-retrospective), and posting the day-one
transition entries correctly.

**Why it is a pain.** "Top challenges: ensuring completeness of lease
population, data gathering and analysis, identifying embedded leases" —
"low-quality lease documentation and decentralized lease processes slow the
process"; "the completeness assertion is one of the most-scrutinised areas in
an ASC 842 audit — auditors will not rely solely on management's assertions"
([Centri — ASC 842 Transition Options and Challenges](https://centriconsulting.com/news/insights/asc-842-is-here-are-you-ready/),
[iLeasePro — A Guide to Lease Population Completeness](https://ileasepro.com/blog/lease-population-completeness/)).

**Severity:** **P1** as a *project* pain — but the largest part of it is
**explicitly not the kernel's job.**

**Does kontor's substrate address it?** *The parts that are kontor's job, yes;
the big part is correctly out of scope.*

- **Contract abstraction** (PDF → structured lease terms) is OCR / data-entry /
  contract-management — a **consumer-app concern**, ADR-010 / ADR-037. Not
  `kontor-lease`'s job. Say so explicitly.
- **Completeness** ("did we find all leases?") is a *process* assertion —
  scanning AP for recurring vendor payments, surveying departments. Not
  something a kernel can answer. `kontor-lease` can *help* at the margin: a
  bitemporal audit trail proves *when* each lease entered the books, so a
  late-discovered lease is visibly a late addition, not a silent backfill.
- **The transition postings themselves** *are* kontor's job and are *ordinary
  postings*. The modified-retrospective cumulative-catch-up is a dated journal
  entry; the day-one ROU + liability recognition is the normal commencement
  posting with a commencement date in the past. `kontor-lease`'s `catch-up!`
  runner (mirroring `kontor-asset`'s) walks a back-dated `:schedule` and emits
  the periods between commencement and the transition date — append-only,
  bitemporally honest.

**Remediation hint — own the transition *posting*, punt the abstraction.**
`kontor-lease` ships a `catch-up!` runner (reuse the `kontor-asset` pattern) so
a lease commenced before the books were live can be brought current with
correctly-dated postings. **Explicitly document that contract abstraction and
completeness-discovery are consumer-app / process concerns** — kontor ships no
OCR, no contract management.

---

## 9. Operational friction — spreadsheet escape velocity, audit trail, scope — P2 (mostly correctly out-of-scope)

**What it is.** Why companies fail with Excel and buy a tool; the audit-trail
expectation; what a consumer app owns vs the kernel.

**Why it shows up.** The Excel-failure literature is unanimous and names the
exact failure modes: **version-control failures** ("different tabs, broken
links, overwritten formulas produce inconsistent results auditors challenge"),
**no audit trail** ("auditors want not just the numbers but the assumptions,
history, and supporting documentation"), **modifications break formulas**,
**no scalability** ("when lease counts exceed 10, or leases have complex
modifications / variable terms / international components, dedicated software
becomes the smarter investment")
([Zeroed-In](https://www.zi.consulting/zeroed-insights/asc-842-lease-accounting-excel-vs-software),
[Pronexus — Why You Should Not Use Spreadsheets](https://www.pronexusllc.com/blog/lease-accounting-why-you-should-not-use-spreadsheets),
[Crunchafi — Hidden Costs of Spreadsheets](https://www.crunchafi.com/blog/the-hidden-costs-of-clients-using-spreadsheets-to-implement-asc842)).
On the tools themselves: LeaseAccelerator draws "very slow, every change takes a
long time" reviews; Trullion draws "data inaccuracy and duplication" and
"reports require thorough review before distribution" reviews
([G2 — LeaseAccelerator](https://www.g2.com/products/lease-accelerator/reviews),
[SelectHub — Trullion vs EZLease](https://www.selecthub.com/lease-accounting-software/trullion-vs-ezlease/)).

**Severity:** **P2** for the kernel — most of this is the *value proposition*
of having a kernel at all, and the rest is consumer-app UX.

**Does kontor's substrate address it?** *The substrate IS the answer to the
Excel-failure list.*

- **Version control / overwritten formulas** → kontor is an **append-only fact
  log**. There are no formulas to overwrite; a schedule is computed from facts,
  not stored as fragile cells.
- **No audit trail** → bitemporal queries + sealing (ADR-007) + the
  `:audit-doc` justification refs (on the IBR, on every `:lease-event`) are a
  *structural* audit trail. "Show the assumptions, history, and supporting
  documentation as of the auditor's sample date" is `:as-of-tx` + `:as-of-valid`
  + the justification refs — no Field-Audit-Trail upgrade tier.
- **Modifications break formulas** → §1: the event-aware `plan-schedule`
  re-plans the tail from facts; nothing to break.
- The "reports require thorough review — possible inaccuracies" complaint about
  Trullion is, in kontor, mitigated because the reports are *derived from the
  postings that are the GL* — they cannot disagree with the GL.

**Remediation hint — keep UX out of the kernel; the substrate is the
escape-velocity argument.** Scanner-free, OCR-free, UI-free — by ADR-010.
The append-only + bitemporal + sealed substrate is *precisely* the answer to
every named Excel-failure mode; that is a positioning argument for any
`kontor-lease` RFP, not a backlog item. Performance at volume (the
LeaseAccelerator "very slow" complaint) is the one real kernel concern: the
roll-forward / maturity-analysis datalog queries walk the
`:schedule-occurrence` log — bench them at representative lease × period
counts; if linear scan is too slow, a materialised as-of snapshot is its own
ADR (same caveat as note 36 §10).

---

## Severity-ranked summary

| # | Pain cluster | Severity (DE-GmbH / US-LLC) | Frequency | Substrate verdict |
|---|---|---|---|---|
| 1 | Lease modifications & remeasurements | **P0** | Very high | Event-aware `plan-schedule` (ADR-055) solves the hard 60%; **`:lease-event` entity + liability re-plan + catch-up posting are new design calls** |
| 2 | Discount rate (IBR) — sourcing, reassessment, audit scrutiny | P1 (P0 at audit) | Very high | Effective-dated pattern + `:audit-doc` ref pattern fit; **must model IBR as first-class effective-dated per-lease attribute** — do NOT build a calculator |
| 3 | Schedule mechanics — unwind, depreciation, payment timing, true-up | P1 | High | ROU side is `StraightLineProvider` reuse; **liability needs a new `LeaseLiabilityProvider`** (small, proven pattern) |
| 4 | Operating vs finance classification (ASC 842) | P1 | High | Parallel-ledger axis handles dual-standard for free; **two posting builders needed, operating-lease expense plug is subtle** |
| 5 | Multi-standard / multi-entity / multi-currency | P1 | High | `:ledger` + `:entity` **genuinely neutralise** multi-standard/entity; **FX monetary/non-monetary asymmetry is a posting rule to design** |
| 6 | Variable / short-term / low-value / embedded | P1 / P2 | Medium-high | Short-term/low-value = classification flag; variable = framework-divergent fork; **embedded-lease identification correctly out of scope** |
| 7 | Period close & disclosure — maturity, roll-forward, tie-out | P1 | High | **Substrate's strongest win** — subledger *is* the GL postings; ship reporting helpers only |
| 8 | Transition & data quality — completeness, initial population | P1 (project) | High | Transition *postings* = `catch-up!` runner reuse; **abstraction + completeness correctly out of scope** |
| 9 | Operational friction — Excel escape velocity, audit trail, scale | P2 | High | Append-only + bitemporal + sealed substrate **IS** the Excel-failure answer; bench roll-forward queries at volume |

### What the existing kontor substrate already neutralises

- **#7 period close & disclosure** — the lease subledger *is* the append-only
  posting + `:schedule-occurrence` log; roll-forwards and the maturity analysis
  are datalog queries over facts that already exist, bitemporally sound, tying
  to the GL by construction (per-ledger sum-to-zero). The "subledger won't
  reconcile to the ERP" pain is structurally absent.
- **#5 multi-standard & multi-entity** — ADR-054 (book per `(lease, ledger)`,
  `:ledger/framework`) and ADR-031 (`:posting/entity`) carry "same lease under
  IFRS 16 + HGB + tax" and intercompany leases directly.
- **#1 the hardest mechanical part** — ADR-055's event-aware `plan-schedule`
  already re-plans only the un-fired tail without restating fired periods;
  "modifications are prospective" is structural.
- **#3 the ROU-asset side** — the ROU asset *is* an `:asset`, its book is a
  `(asset, ledger)` depreciation book, `StraightLineProvider` does straight-line
  with "last period absorbs the rounding remainder" (the end-of-lease true-up);
  mid-period commencement is the `:in-service-date` ≠ `:acquisition-date`
  distinction ADR-053 already models.
- **#8 transition postings** — the `kontor-asset` `catch-up!` runner pattern
  brings a pre-existing lease current with correctly-dated, append-only postings.
- **#2 audit scrutiny / #9 audit trail** — bitemporal + sealing + `:audit-doc`
  justification refs are a structural audit trail; the Excel "no audit trail /
  broken formulas / version control" failure modes cannot occur.
- Cross-cutting: period locking + sealing reject closed-period lease postings
  for free; the `DepreciationProvider` / `CostingProvider` / `TaxProvider`
  protocol pattern is the proven template for the new `LeaseLiabilityProvider`.

### The 3-4 that `kontor-lease`'s design must explicitly solve

1. **The `:lease-event` remeasurement entity + the modification posting story
   (#1, #4).** Mirror `:asset-event`: immutable, `:kind` ∈ `{:rent-change
   :term-extension :partial-termination :full-termination :rate-reassessment
   :renewal-option-reassessment}`, the revised cash-flow profile, the new rate,
   a justification ref. Plus the **catch-up posting builder** for partial
   termination (ROU reduction under the elected method, liability reduction,
   the gain/loss plug). AskUserQuestion: is modification-vs-separate-lease a
   caller input (recommended — judgment is not the kernel's job) or inferred?
   Is the partial-termination method per-entity or per-`(lease, ledger)`?
2. **The `LeaseLiabilityProvider` protocol + the two posting builders (#3, #4).**
   A `provider-id` / `plan-schedule` protocol — the liability twin of
   `DepreciationProvider` — with an `EffectiveInterestProvider` built-in,
   payment-timing aware (`:advance` | `:arrears`). Plus `post-finance-lease-period`
   and `post-operating-lease-period` builders; the operating-lease builder
   computes the straight-line expense plug. AskUserQuestion: confirm the ROU
   asset *reuses* `kontor-asset` (the ROU asset is an `:asset`) rather than
   `kontor-lease` re-implementing depreciation.
3. **The IBR as a first-class, effective-dated, per-lease attribute (#2).**
   `:lease/discount-rate` + effective-dated history + a mandatory `:audit-doc`
   justification ref; a reassessment introduces a new effective-dated rate via
   a `:rate-reassessment` `:lease-event`. Explicitly **do not** build an IBR
   calculator — it is treasury / consumer data, like the `TaxProvider` "we ship
   the protocol, not the rates" stance.
4. **The foreign-currency posting rule (#5).** The lease liability is a
   *monetary* item — retranslate at the closing rate each period, FX gain/loss
   to P&L; the ROU asset is a *non-monetary* item — booked once at the
   historical rate, never retranslated. This is a posting rule, not a substrate
   gap, but subtle enough to be its own ADR; the IBR-currency-alignment point
   rides along.

Items 1-3 are AskUserQuestion-worthy design calls before any code. Item 4 and
the riders — `:lease/classification` enum including `:short-term` / `:low-value`,
the index-linked-variable-payment framework fork, the component-split allocation
helper, the roll-forward / maturity-analysis reporting helpers, the `catch-up!`
transition runner — are composition of existing primitives plus well-patterned
new code; implement, don't deliberate.

## Acknowledged limitations

- **No live customer feedback** — same caveat as notes 13 and 36. Pain
  prioritization reflects what *existing lease-accounting tools'* users and
  Big-4 implementation post-mortems report — correlated with, not identical to,
  `kontor-lease`'s eventual users.
- **Web-review sampling is finite** — this note draws on ~30 sources: vendor
  docs and review aggregators (G2, Capterra, SelectHub) for FinQuery/LeaseQuery,
  LeaseAccelerator, NetLease/Netgain, Trullion, EZLease, Visual Lease, CoStar,
  SAP RE-FX; Big-4 technical guidance (Deloitte DART, PwC Viewpoint, KPMG, RSM,
  BDO) on the standards in practice; and accountant-oriented blogs
  (FinQuery, Occupier, iLeasePro, Crunchafi, Nakisa, Black Owl, Cradle). It is
  representative, not exhaustive. Note that several "review" sources are vendor-
  adjacent; the pain points are cross-checked against the neutral Big-4
  technical guidance.
- **No performance numbers** — every "bench this" (§7, §9) is qualitative. Real
  numbers come from running the roll-forward / maturity-analysis queries on
  representative lease × period volumes.
- **Lessor accounting is out of scope** — this note and `kontor-lease` are
  **lessee-side only**, per the task. Lessor accounting (sales-type / direct-
  financing / operating, and the intercompany lessor leg) is a separate
  question, deliberately not covered.
- **OSS / vendor reference study + internal gap analysis are separate agents** —
  this note covers only the customer-pain angle; synthesize all three before
  drafting the `kontor-lease` ADRs.

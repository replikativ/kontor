# 38 — Lessee-side lease accounting (IFRS 16 / ASC 842) + the `kontor-lease` companion design

**Stage — `kontor-lease` research-before — reference study.**
Sibling input to research note 31 (`kontor-asset` design synthesis), which `kontor-lease` is adjacent to and reuses heavily. This note studies lessee-side lease accounting under **IFRS 16** and **ASC 842** as *mandated by the standards*, cross-checks the entity shape against SAP RE-FX / NetSuite Fixed Assets Management / Odoo, and proposes how `kontor-lease` should *reflect* that onto kontor's existing substrate.

Date: 2026-05-14. Source: direct — primary-source standard citations (IFRS 16, ASC 842 / FASB ASU 2016-02), kontor source at file:line, plus vendor-doc entity-shape cross-check. Verified: medium-high — standard citations are to the controlling paragraph; the discount-rate practical-expedient details and the ASC 842 classification tests are summarised and re-flagged for the implementer.

---

## 0. Executive summary

1. **`kontor-lease` is a thin companion that composes `kontor-asset` + `:schedule` + parallel `:ledger`.** It is not a reinvention. The right-of-use (ROU) asset *is* an `:asset` (reuse `kontor-asset` whole — register, lifecycle, `:asset-depreciation` book, `DepreciationProvider`, runner, disposal, impairment). The lease liability unwind *is* a `:schedule` whose per-period split (interest / principal) a new `LeaseProvider` computes — the exact `DepreciationProvider` pattern one domain over. IFRS-16-book vs ASC-842-book vs local-GAAP/tax-book are each a `:ledger` — ADR-021 named lease accounting as its forward-compat target (`doc/decisions.md:1012`), and ADR-054 proved the "a regulatory book IS a `:ledger`" call for depreciation areas; lease classification is the identical shape.

2. **The genuinely new schema is small: ~3 entities + 1 provider protocol.** `:lease` (the contract — classification, term, discount rate, governance), `:lease-liability` (the per-(lease, ledger) liability book — the sibling of `:asset-depreciation`), `:lease-modification` (the append-only remeasurement-event fact — the sibling of `:asset-event`). The ROU asset is *not* new — it is an `:asset` with `:asset/class :rou` whose `:asset/origin-document` points at the `:lease`. Plus a `LeaseProvider` protocol and a lease runner. ~3 ADRs.

3. **The single hardest design call: the operating-lease ROU "plug".** Under IFRS 16 (single model) and an ASC 842 *finance* lease, the ROU asset depreciates straight-line — `kontor-asset`'s `StraightLineProvider` drives it as-is, zero new code. Under an ASC 842 *operating* lease the ROU asset *also* sits on the balance sheet, but the P&L shows a single straight-line **lease expense**, and the ROU "amortization" is a **plug**: `lease-expense − interest-on-liability`. That plug is not a depreciation method — it is a function of the *liability* book's interest. So `kontor-lease` needs **one new `DepreciationProvider` impl** — a `LeaseRouPlugProvider` shipped by `kontor-lease` (not `kontor-asset`) — that reads the sibling liability book's `LeaseProvider` plan and returns `expense − interest` per period. This is the one place the two books are coupled, and §4.2 spells out the seam.

4. **The discount rate is a stored scalar on `:lease`, reassessed only on remeasurement.** Not a kernel concern, not effective-dated against statute (unlike depreciation rules) — it is contract-specific data the consumer supplies. The rate is *pinned at commencement* and only changes on a remeasurement that the standards say triggers a re-discount (a term reassessment, a floating-rate index change for ASC 842). `:lease-modification` records the trigger; the `LeaseProvider` re-plans the un-fired tail — the *exact* `revise-book!` / prospective-replan pattern `kontor-asset` already ships (`modules/asset/src/kontor/asset/depreciation.clj:299`).

5. **Lessor-side accounting, IFRS-16 transition reliefs, and disclosure-note generation are OUT of v1 scope** — confirmed §5. Lessor accounting is a different (and for IFRS 16, largely unchanged-from-IAS-17) model with sales-type / direct-financing / operating sub-cases; it is a separate companion if a consumer ever needs it. Transition reliefs are one-time. Disclosure notes are consumer-app presentation. The short-term (≤12 month) and low-value exemptions ARE in scope — but they are *trivial*: no ROU asset, no liability, just a recurring straight-line expense posting, which is a plain `:schedule` the consumer already knows how to drive (no `kontor-lease` entity at all).

---

## 1. IFRS 16 — the single lessee model

**Controlling standard:** IFRS 16 *Leases*, effective for annual periods beginning on or after 2019-01-01, replacing IAS 17.

### 1.1 The core idea

IFRS 16 abolished the lessee operating/finance distinction. **Every lease** (above the exemptions, §3.4) is recognised on-balance-sheet: the lessee records a **right-of-use asset** and a **lease liability**, both initially measured at the **present value of the lease payments not yet paid**, discounted at the **interest rate implicit in the lease** if readily determinable, otherwise the lessee's **incremental borrowing rate** (IFRS 16.26).

- **Lease liability** initial measurement (IFRS 16.27): PV of (fixed payments + in-substance fixed payments; variable payments that depend on an index or rate, initially measured using the index/rate at commencement; amounts expected under residual-value guarantees; the exercise price of a purchase option if reasonably certain; termination penalties if the term reflects termination).
- **ROU asset** initial measurement (IFRS 16.24): the lease liability amount + lease payments made at/before commencement − lease incentives received + initial direct costs + an estimate of dismantling/restoration costs.

### 1.2 Initial-recognition entry

At lease commencement, for a lease liability measured at PV `L` and initial direct costs / prepayments `d`:

```
Dr  Right-of-use asset            L + d
    Cr  Lease liability                  L
    Cr  Cash / Bank                      d        ; initial direct costs, prepaid rent
```

(If there are no initial direct costs or prepayments, it is the clean `Dr ROU asset L / Cr Lease liability L`.)

### 1.3 The per-period entries — two independent unwinds

IFRS 16 produces **two separate P&L lines** every period, from two independent mechanisms:

**(a) The liability unwinds via the effective-interest method.** Each lease payment splits into interest expense and principal reduction:
- `interest = opening liability balance × periodic discount rate`
- `principal = payment − interest`
- `closing liability = opening liability − principal`

```
Dr  Interest expense              interest
Dr  Lease liability               principal
    Cr  Cash / Bank                      payment
```

**(b) The ROU asset depreciates** — under IAS 16 mechanics, straight-line over the **shorter of the lease term and the asset's useful life** (IFRS 16.32; if ownership transfers or a purchase option is reasonably certain, over the useful life):

```
Dr  Depreciation expense          (ROU cost − residual) / term
    Cr  Accumulated depreciation         same
```

**The journal mechanics that matter for kontor:** these are two *unrelated* schedules. The liability unwind is front-loaded (interest is higher early, when the balance is higher). The depreciation is flat. So total IFRS 16 P&L expense is **front-loaded** — higher in early years than a straight rent would be. That asymmetry is *the* reason ASC 842 kept a separate operating-lease treatment (§2).

---

## 2. ASC 842 — the US dual model

**Controlling standard:** FASB ASC 842 *Leases* (ASU 2016-02 and amendments), effective for public entities from fiscal years beginning after 2018-12-15, private from after 2021-12-15.

### 2.1 Classification survives

ASC 842 kept the lessee **operating vs finance** classification — the refreshed old capital-lease tests (ASC 842-10-25-2). A lease is a **finance lease** if *any* of: ownership transfers by end of term; a purchase option is reasonably certain to be exercised; the lease term is for the major part of the remaining economic life; the PV of payments is substantially all of the fair value; the asset is so specialised it has no alternative use to the lessor. Otherwise it is an **operating lease**.

### 2.2 Finance lease — looks exactly like IFRS 16

A finance lease under ASC 842 is mechanically identical to IFRS 16: ROU asset + liability at PV; the liability unwinds via effective interest; the ROU asset amortizes straight-line; two P&L lines (interest + amortization); front-loaded total expense. **For `kontor-lease`, an ASC 842 finance lease and an IFRS 16 lease are the same code path.**

### 2.3 Operating lease — the big ASC 842 change, and the hard part

The headline change of ASC 842: an operating lease **also** puts an ROU asset and a lease liability on the balance sheet (pre-842, operating leases were off-balance-sheet — just rent expense). **But the P&L is unchanged from the old model**: a **single straight-line lease expense** — *not* separate interest + amortization.

To make a single straight-line expense come out of a liability that still unwinds via effective interest, ASC 842 makes the **ROU asset amortization a plug**:

```
single straight-line lease expense  =  total undiscounted payments / lease term   (the "straight-line rent")
interest on liability               =  opening liability × periodic rate          (effective-interest, same as finance)
ROU amortization (the PLUG)         =  straight-line lease expense − interest on liability
```

The per-period entry for an operating lease is:

```
Dr  Lease expense                 straight-line lease expense
    Cr  Lease liability                  principal      (= payment − interest, same effective-interest split)
    Cr  Accumulated ROU amortization     plug           (= lease expense − interest)
    ; and the cash payment:
Dr  Lease liability               interest portion movement … 
```

In practice the clean way to see it: the liability still unwinds by effective interest exactly as in a finance lease (`Dr Lease liability principal / Dr [interest, but rolled into lease expense] / Cr Cash payment`), and the **single `Lease expense` debit** is booked straight-line, with the ROU asset credited (amortized) by whatever balances the entry — i.e. `lease expense − interest`. Early in the lease, interest is high, so the plug (ROU amortization) is *small*; late in the lease, interest is low, so the plug is *large*. The ROU asset amortization is therefore **back-loaded** — the mirror image of the front-loaded interest — and the two net to a flat `lease expense`. At end of term both ROU asset and liability reach zero together.

### 2.4 The verdict for kontor: finance ROU depreciation ≠ operating ROU "amortization"

- **Finance lease / IFRS 16 ROU asset**: depreciates by a *self-contained* method (straight-line over the term). `kontor-asset`'s `StraightLineProvider` drives it unchanged. The depreciation schedule does not need to know anything about the liability.
- **Operating lease ROU asset (ASC 842 only)**: "amortizes" by a *plug* that is **a function of the sibling liability book's interest schedule**. It is not a depreciation method at all — it is `straight-line-lease-expense − interest(period)`. This needs a bespoke `DepreciationProvider` impl that reads across to the liability book. → §4.2 is the design seam.

---

## 3. The hard parts

### 3.1 The discount rate — where it comes from, when it is reassessed

- **Source.** The rate implicit in the lease (the rate that makes the PV of payments + unguaranteed residual = fair value + lessor's initial direct costs) if *readily determinable* — it rarely is for a lessee, because the lessee does not know the lessor's residual assumptions. So in practice the lessee uses its **incremental borrowing rate (IBR)**: the rate it would pay to borrow, over a similar term, with similar security, the funds to obtain a similar asset (IFRS 16.A; ASC 842-10-30-3). The IBR is an *entity input* — derived from the entity's actual borrowing facilities, adjusted for term and security. **kontor does not compute it.** It is a number the consumer supplies on the `:lease` entity (`:lease/discount-rate`).
- **When it is reassessed.** The rate is **pinned at commencement** and is *not* touched by routine remeasurements (a change in the index for an index-linked payment, under IFRS 16, is remeasured using the *unchanged* original rate). It **is** revised — and the liability re-discounted at a *new* current rate — only on the reassessments the standards specifically call out: a change in the lease term, a change in the assessment of a purchase option, and (ASC 842 specifically) a change in a floating-rate reference. So: `:lease/discount-rate` is pinned; a `:lease-modification` of the right `:kind` carries a `:new-discount-rate` and the `LeaseProvider` re-plans the un-fired tail at the new rate. This is the exact `kontor-asset` `revise-book!` shape (`modules/asset/src/kontor/asset/depreciation.clj:299`).

### 3.2 Lease modifications + remeasurements

A modification (a change in scope or consideration not part of the original terms) or a remeasurement (a change in an input — index, term, option assessment, residual-value-guarantee estimate) **re-measures the lease liability** and, in most cases, **adjusts the ROU asset by the same amount** (IFRS 16.39-46; ASC 842-10-25-8 ff). The exception: when the remeasurement reduces scope, the ROU asset is reduced proportionately and any difference goes to P&L; and when the ROU asset would go below zero, the excess goes to P&L.

Journal mechanics of the common case (liability up by `Δ`, ROU asset up by `Δ`):
```
Dr  Right-of-use asset            Δ
    Cr  Lease liability                  Δ
```
Then the `LeaseProvider` and the `DepreciationProvider` both re-plan their un-fired tails over the (possibly new) remaining term, at the (possibly new) rate.

**kontor mapping:** a `:lease-modification` entity — the append-only event fact, sibling of `:asset-event`. It records `:kind` (`:scope-increase`, `:scope-decrease`, `:payment-change`, `:term-change`, `:rate-reset`, `:index-reset`), the `Δ` amounts, the new term/rate, a `:justification` `:audit-doc` ref, and the `:transaction` for the `Dr ROU / Cr liability` adjustment. The companion's modification transactor (a) records the `:lease-modification`, (b) calls the liability-book equivalent of `revise-book!`, (c) calls `kontor.asset.depreciation/revise-book!` on the ROU asset's `:asset-depreciation` book(s). Fired occurrences are never restated — IFRS 16 / ASC 842 modification accounting is prospective.

### 3.3 The short-term and low-value exemptions

IFRS 16.5-8 (and ASC 842's short-term practical expedient): a lessee **may elect** not to recognise an ROU asset + liability for **short-term leases** (term ≤ 12 months, no purchase option) and **low-value asset leases** (IFRS 16 only — assessed on the asset's value when new, e.g. tablets, small office furniture; ASC 842 has no low-value exemption). For exempted leases, the lessee just **expenses the payments**, generally straight-line, over the term.

**kontor mapping — and this is important: an exempted lease needs NO `kontor-lease` entity at all.** It is a recurring straight-line expense posting — a plain `:schedule` (`:schedule/kind :lease-expense`) whose per-period amount the consumer's posting-builder computes (`payments / term`), fired by the generic schedule mechanism. `kontor-lease` should ship a thin `plan-short-term-lease-expense` *helper* and document that the exemption path deliberately bypasses the `:lease` / `:lease-liability` / ROU machinery. Modelling an exempted lease as a full `:lease` with a flag would be over-engineering — the whole point of the exemption is that it has no balance-sheet footprint.

### 3.4 Variable lease payments

Two kinds, treated differently:
- **Variable payments that depend on an index or rate** (CPI escalation, a floating reference rate) — these *are* included in the liability, initially measured at the index/rate at commencement (§1.1). A later change in the index is a **remeasurement** (§3.2) — `:lease-modification :kind :index-reset`.
- **Variable payments that depend on usage or performance** (a percentage-of-sales rent, a per-mile vehicle charge) — these are **excluded** from the liability and the ROU asset entirely; they are **expensed in the period incurred** (IFRS 16.38; ASC 842-20-25-5). kontor mapping: a usage-based variable payment is a plain expense posting (`Dr Variable lease expense / Cr Cash`) — `kontor-lease` ships a `plan-variable-lease-payment` helper but stores no schedule for it (the amount is not knowable in advance, exactly like units-of-production depreciation — but unlike UoP there is no base to spread, so it is simpler: just a posting).

### 3.5 The lease term including renewal options

The **lease term** is the non-cancellable period **plus** periods covered by an extension option the lessee is *reasonably certain* to exercise, **plus** periods covered by a termination option the lessee is reasonably certain *not* to exercise (IFRS 16.18-19; ASC 842-10-30-1). The "reasonably certain" judgement is an *entity input* — it is not computable; the consumer decides and supplies the resulting term. kontor mapping: `:lease/term-months` is the term *as assessed* (a stored scalar — the consumer has already folded in the option judgement). A *change* in that assessment is a `:lease-modification :kind :term-change` that re-discounts and re-plans (§3.1, §3.2). The companion stores the assessed term; it does not model the option-by-option reasoning (that is consumer/document territory — the reasoning lives in the `:lease-modification/justification` `:audit-doc`).

### 3.6 Sublease + lessor-side accounting

**Out of scope for v1 — confirmed.** A sublease (the original lessee becomes an intermediate lessor) and lessor-side accounting generally (sales-type / direct-financing / operating lease classification on the lessor side, net-investment-in-lease mechanics) is a *different model* — and for IFRS 16 it is largely the unchanged IAS 17 lessor model. It has no overlap with the ROU-asset machinery `kontor-lease` v1 builds. If a consumer needs it, `kontor-lessor` is a separate companion (and most kontor target customers — DE-GmbH / US-LLC mid-market — are lessees, not lessors). §5 records the full out-of-scope list.

---

## 4. The mapping verdict — compose vs new

### 4.1 The ROU asset — reuse `kontor-asset` whole

The ROU asset **is an `:asset`**. Not a new `:rou-asset` entity. Concretely:
- `:asset/class` → an `:asset-class` with code `"rou"` (or finer: `"rou-property"`, `"rou-vehicle"`) — `kontor-lease` ships these class rows as seeds, or the consumer creates them.
- `:asset/acquisition-cost` → the ROU asset's initial measurement (liability PV + initial direct costs + prepayments − incentives — §1.1). `kontor-lease`'s commencement transactor computes this and calls `kontor.asset.asset/acquire!`.
- `:asset/acquisition-date` / `:asset/in-service-date` → the lease commencement date.
- `:asset/origin-document` → the `:lease` entity's `:audit-doc` (the signed lease contract). `:asset/origin-transaction` → the initial-recognition GL entry.
- The ROU asset's depreciation is a `kontor-asset` `:asset-depreciation` book per ledger (§4.3). For IFRS 16 / finance leases the book's `:provider-id` is `:straight-line` — **`kontor-asset`'s built-in, zero new code** (`modules/asset/src/kontor/asset/depreciation_provider.clj:188`). The book's `:useful-life-months` = shorter of lease term and asset useful life; the runner (`kontor.asset.runner/run-depreciation!`) fires it; disposal/impairment/end-of-lease all reuse `kontor-asset`'s lifecycle.
- **Impairment** (IAS 36 on the ROU asset — common when a leased property is vacated) is `kontor.asset.asset/impair!` + `kontor.asset.posting/plan-impairment` — already shipped, no new code.

**The one gap: the operating-lease ROU "amortization" needs a new `DepreciationProvider` impl** — see §4.2. It is the *only* place `kontor-asset` does not cover the ROU asset as-is.

### 4.2 The lease liability unwind — a new `LeaseProvider` protocol + a new entity

The lease liability is **not** an `:asset-depreciation` book — it is a *liability* that unwinds, not an asset that depreciates, and the per-period split is **interest / principal**, not a single depreciation amount. But the *shape* is identical to `kontor-asset`: a per-(lease, ledger) book, owning one `:schedule`, whose per-period amounts a pluggable provider computes, fired by a runner, re-planned prospectively on modification.

So `kontor-lease` ships:

**`:lease-liability`** — the per-(lease, ledger) liability book. Sibling of `:asset-depreciation`. Fields: `:lease` + `:ledger` refs; `:identity` (`:db.unique/identity` tuple `[lease ledger]`); `:liability-account` (the BS liability account); `:interest-account` (the P&L interest-expense account); `:opening-liability` (the PV at commencement, this book's measurement — IFRS book and a local-GAAP book can differ); `:discount-rate` (per-book — usually the same, but a parallel book *could* discount differently); `:schedule` (ref to the ADR-032 `:schedule` the runner fires, `:schedule/kind :lease-liability`); `:provider-id` (which `LeaseProvider`); `:commodity`; `:note`.

**`LeaseProvider`** protocol — the direct sibling of `DepreciationProvider` (ADR-055), `TaxProvider` (ADR-005), `CostingProvider` (ADR-029):

```clojure
(ns kontor.lease.lease-provider)

(defprotocol LeaseProvider
  "Compute a lease-liability unwind schedule for one (lease, ledger)
   book. Sibling of kontor.asset DepreciationProvider — the companion
   ships the effective-interest built-in; an l10n or consumer module
   could ship a variant (e.g. a jurisdiction that mandates a
   different compounding convention). PURE — reads a db value,
   returns a plan, transacts nothing; event-aware via the fired
   :schedule-occurrence log (re-plans only the un-fired tail, so a
   :lease-modification is picked up automatically — exactly the
   kontor-asset plan-schedule contract)."

  (provider-id [provider])

  (plan-schedule [provider db liability-book]
    "Return the full forward unwind plan:
       {:periods [{:sequence        long
                   :date            #inst
                   :payment         bigdec   ; the period's lease payment
                   :interest        bigdec   ; opening balance × periodic rate
                   :principal       bigdec   ; payment − interest
                   :opening-balance bigdec
                   :closing-balance bigdec
                   :fired?          boolean}
                  ...]
        :total-interest  bigdec
        :total-principal bigdec               ; = opening-liability
        :provider-id     keyword}
     Already-fired periods carry their logged amounts; the un-fired
     tail is re-planned over the remaining payments at the book's
     current :discount-rate.")

  (straight-line-expense [provider db liability-book]
    "ASC 842 operating leases only: the single straight-line lease
     expense per period — total undiscounted payments / term. The
     operating-lease ROU plug provider (§4.2) calls this + reads
     :interest from plan-schedule to compute its per-period
     amortization. Returns bigdec or a per-period vector."))
```

The companion ships **one built-in**: `EffectiveInterestProvider` (`:provider-id :effective-interest`) — the standard interest/principal split. That single built-in covers IFRS 16, ASC 842 finance, and ASC 842 operating (the operating case additionally uses `straight-line-expense`).

**The operating-lease ROU plug** — `LeaseRouPlugProvider`, a `DepreciationProvider` impl shipped by **`kontor-lease`** (not `kontor-asset`). Its `plan-schedule` (the `DepreciationProvider` signature) does *not* compute a depreciation method — it resolves the *sibling* `:lease-liability` book for the same `(lease, ledger)`, calls the `LeaseProvider` `plan-schedule` + `straight-line-expense`, and returns per-period `amount = straight-line-expense − interest`. It reads `db`, so it can do the cross-book lookup (the `DepreciationProvider` protocol already passes `db` — `modules/asset/src/kontor/asset/depreciation_provider.clj:68`). The ROU `:asset-depreciation` book for an operating lease just sets `:provider-id :lease-rou-plug` and the `kontor-asset` runner drives it unchanged — the runner resolves the provider by id and `kontor-lease` registers `LeaseRouPlugProvider` in the provider registry. **This is the entire coupling between the two books, and it is one provider impl.**

**The lease runner** — `kontor.lease.runner/run-lease!` — a thin convenience exactly like `kontor.asset.runner/run-depreciation!`: for each pending `:schedule` occurrence on the liability book, ask the `LeaseProvider` for the period's `{:interest :principal :payment}`, build the `Dr interest + Dr liability / Cr cash` entry with a `kontor.lease.posting` builder, log the occurrence. For a finance/IFRS lease the ROU depreciation is a *separate* `run-depreciation!` call on the `:asset-depreciation` book (the consumer runs both); for an operating lease, same — `run-depreciation!` with the plug provider produces the ROU side. The companion documents the run-ordering: liability run before ROU run within a period, because the operating-lease plug reads the liability's fired interest.

### 4.3 IFRS-16-book vs ASC-842-book vs local-GAAP/tax-book — each a `:ledger`

This is ADR-021 + ADR-054 applied verbatim. A lessee that reports under two frameworks (a US subsidiary of an IFRS-reporting group; a German GmbH with an IFRS group package and an HGB statutory book) runs the **same physical lease** under **two classifications simultaneously**:
- The IFRS book: one `:lease-liability` book on `:ledger "ifrs"` (`:effective-interest`), one `:asset-depreciation` book on `:ledger "ifrs"` (`:straight-line`). Single-model.
- The US-GAAP book: one `:lease-liability` book on `:ledger "us-gaap"` (`:effective-interest`), one `:asset-depreciation` book on `:ledger "us-gaap"`. If the lease classifies *operating* under ASC 842, that book's `:provider-id` is `:lease-rou-plug`; if *finance*, `:straight-line`.
- An HGB book: under HGB a lessee typically does **not** capitalise an operating lease at all (HGB follows the risks-and-rewards / economic-ownership test — most leases stay off the HGB balance sheet as `Mietaufwand`). So the HGB "book" for such a lease is simply *no `:lease-liability` book and no ROU `:asset-depreciation` book on `:ledger "hgb"`* — the HGB rent expense is a plain `:schedule`-driven posting on `:ledger "hgb"`. The `:ledger` model carries this with zero special-casing: a book exists on the ledgers where the framework capitalises, and does not exist on the ledgers where it does not.

The `:lease/classification` attribute (`:operating` | `:finance` | `:exempt-short-term` | `:exempt-low-value`) is therefore **per-ledger, not per-lease** — the *same* lease is `:finance` on one ledger and (if HGB) effectively `:exempt`/off-balance on another. So classification belongs on the `:lease-liability` book (or a small `:lease-treatment` per-(lease, ledger) row), **not** on `:lease`. This is the one place the `kontor-asset` analogy needs a small extension: `kontor-asset` has one `:asset-depreciation` row per (asset, ledger) and the method differs per row; `kontor-lease` has one `:lease-liability` row per (lease, ledger) **and the classification (hence whether an ROU book even exists) differs per row.** `:lease` itself carries only the framework-neutral contract facts (term, payments, the *rate*, the counterparty, the underlying asset description). Capture this explicitly in the ADR — it is the most contestable call.

### 4.4 What is genuinely new schema vs reuse

| Piece | Verdict |
|---|---|
| ROU asset (register, lifecycle, status machine, disposal, impairment) | **Reuse `kontor-asset` whole** — `:asset` with `:asset/class :rou` |
| ROU asset depreciation — IFRS 16 / ASC 842 finance | **Reuse** `kontor-asset` `StraightLineProvider` as-is |
| ROU asset "amortization" — ASC 842 operating (the plug) | **New** — `LeaseRouPlugProvider`, a `DepreciationProvider` impl in `kontor-lease` (reads the sibling liability book) |
| ROU `:asset-depreciation` book + runner | **Reuse** `kontor-asset` — the book just names the right `:provider-id` |
| The lease liability per-(lease, ledger) book | **New entity** — `:lease-liability` (sibling of `:asset-depreciation`) |
| The liability unwind computation | **New protocol** — `LeaseProvider` + one built-in `EffectiveInterestProvider` |
| The liability runner | **New** — `kontor.lease.runner` (thin, mirrors `kontor.asset.runner`) |
| The lease contract master record | **New entity** — `:lease` (framework-neutral contract facts) |
| Per-(lease, ledger) classification / treatment | **New** — an attr on `:lease-liability` (or a tiny `:lease-treatment` row) — §4.3 |
| Modifications / remeasurements | **New entity** — `:lease-modification` (append-only fact, sibling of `:asset-event`) |
| Re-planning the un-fired tail after a modification | **Reuse the pattern** — `revise-book!`-style; `kontor-asset` already ships its half (`revise-book!` on the ROU `:asset-depreciation` book); `kontor-lease` adds the liability-book half |
| The recurring-posting substrate | **Reuse** `:schedule` / `:schedule-occurrence` (ADR-032) — two schedules per capitalised lease per ledger (liability + ROU) |
| Parallel framework books | **Reuse** `:ledger` (ADR-021) — IFRS / US-GAAP / HGB are ledgers |
| GL posting builders | **New** — `kontor.lease.posting` (thin, mirrors `kontor.asset.posting`; routes through `kontor.posting/build-transaction` so per-(ledger, commodity) sum-to-zero is free) |
| Short-term / low-value exempt leases | **Reuse** `:schedule` — a `plan-short-term-lease-expense` helper, **no `:lease` entity** (§3.3) |
| Usage-based variable payments | **Reuse** `build-transaction` — a `plan-variable-lease-payment` helper, no schedule (§3.4) |
| Governance (commencement approval, modification authorisation) | **Reuse** `:audit-doc` + `:approval-policy` (ADR-038) — `:lease` and `:lease-modification` carry `:audit-doc` refs; seed `:approval-policy` rows |
| Lease lifecycle status machine | **Reuse** `:status-transition` / `:status-history` (ADR-034) — `:lease/status` facet |

**Net new: 3 entities (`:lease`, `:lease-liability`, `:lease-modification`) + 1 protocol (`LeaseProvider`, 1 built-in) + 1 `DepreciationProvider` impl (`LeaseRouPlugProvider`) + 1 runner + posting builders.** Everything else composes.

---

## 5. What to leave behind — out of scope for v1

| Out of scope | Why |
|---|---|
| **Lessor-side accounting** | A different model (sales-type / direct-financing / operating; net-investment-in-lease). For IFRS 16, largely the unchanged IAS 17 lessor model. No overlap with the ROU machinery. Separate `kontor-lessor` companion if a consumer ever needs it; kontor's target customers are lessees. |
| **Subleases** | The intermediate-lessor case — depends on lessor accounting. Out with lessor accounting. |
| **IFRS 16 transition reliefs** | The one-time IAS 17 → IFRS 16 transition expedients (modified retrospective, the grandfathering of lease definitions, the portfolio approach at transition). One-time, dated 2019; a consumer migrating historical leases does the transition arithmetic itself and `acquire!`s the resulting ROU assets + `:lease-liability` books at their transition-date carrying amounts (the `:opening-liability` / `:opening-accumulated` mid-life-import path `kontor-asset` already supports — `modules/asset/src/kontor/asset/schema.clj:324`). |
| **Disclosure-note generation** | The IFRS 16.51-60 / ASC 842-20-50 quantitative and qualitative disclosures (maturity analysis, weighted-average discount rate, weighted-average remaining term, ROU asset additions by class). This is *presentation* — a report definition over `:lease` + `:lease-liability` + `:schedule-occurrence` history, the same shape as `kontor-asset`'s Anlagengitter (ADR-056). It is **report-engine territory, deferrable to a follow-up** or a consumer-app concern. The *data* to produce them all lives in the v1 entities; only the layout is missing. Flag as a follow-up, not v1. |
| **Lease *management* / administration** | CRM-of-landlords, lease-document storage, critical-date alerts (rent-review dates, break-option deadlines, renewal-notice windows), portfolio dashboards. This is operational lease *administration* — SAP RE-FX and the lease-management SaaS category (LeaseQuery, Visual Lease, etc.) sell this. It is UI + workflow, not GL substance. `:audit-doc` holds the contract pointer; critical-date alerting is a consumer-app / workflow concern (research note 21). Out per ADR-010. |
| **The lease-vs-service-component split** | IFRS 16.12-17 / ASC 842 — a contract may bundle a lease component with non-lease service components (a property lease bundling common-area maintenance); the consideration must be allocated. The *allocation* is an entity judgement (or a practical-expedient election to not separate). `kontor-lease` takes the *already-allocated* lease payments as input — the allocation reasoning lives in the consumer / the `:lease`'s `:audit-doc`. Document this as an explicit input boundary, not a feature. |

---

## 6. Cross-check — how SAP / NetSuite / Odoo shape the entities

For the *entity-shape* cross-check only (SAP/NetSuite are docs; Odoo is LGPL — patterns, not code). The convergent pattern strongly validates the §4 mapping.

- **SAP RE-FX** (Flexible Real Estate Management, the IFRS 16 / ASC 842 engine). A **lease contract** master object carries classification, term, conditions (the payment terms), and **valuation rules**; it integrates *into FI-AA (Asset Accounting)* for the ROU asset and *FI-GL* for the liability and posting flows. The ROU asset is an **FI-AA asset** — i.e. SAP reuses its fixed-asset register for the ROU asset, exactly the `kontor-lease`-reuses-`kontor-asset` call. Valuation rules + account determination drive the periodic posting flows; modifications/reassessments are first-class. Multiple "valuation areas" per contract = the parallel-book / `:ledger` shape. **Convergent with §4.1, §4.3.**
- **NetSuite Fixed Assets Management — Lease Accounting SuiteApp.** Distinct records: a **Lease record** (the contract), **Lease Payments**, a **Lease Amortization Schedule** (importable or generated), **Lease Journal Entries**, **Lease Interest**, and the ROU asset + lease liability. It "separates lease and interest expenses" (finance) and supports operating leases with the single-expense presentation, and supports **lease modifications** (remeasuring). The amortization schedule as a *first-class importable record* is exactly the `:schedule` + `:schedule-occurrence` shape — and "importable" is the mid-life-import path. **Convergent with §4.2, §4.4.**
- **Odoo** (community `lease_accounting` / `ifrs_16_lease_accounting` apps, LGPL — patterns only). A **lease agreement** record with configurable ROU-asset and lease-liability *accounts*, generating an **amortization schedule** and **automated journal entries** for interest + depreciation; the ROU asset is created *via Odoo's `account_asset` module* — again, the lease reuses the fixed-asset/depreciation engine rather than reinventing it. Odoo notes that PV-of-future-payments often needs an external spreadsheet — i.e. Odoo *lacks* a clean `LeaseProvider`-style computation seam, which is precisely the gap `kontor-lease`'s `LeaseProvider` protocol fills. **Convergent with §4.1; and a cautionary tale — the missing computation seam is the thing to get right.**

All three: a contract master + a schedule record + an ROU asset that *is* a fixed-asset-register member + a liability + modification events. The kontor mapping (`:lease` + `:schedule` + `:asset` + `:lease-liability` + `:lease-modification`) is the same decomposition, with kontor's advantage being the explicit `LeaseProvider` / `DepreciationProvider` computation seams and the `:ledger` parallel-book primitive doing the multi-framework work natively.

---

## 7. Proposed `:lease-*` namespace sketch

Namespacing convention (`CLAUDE.md` — Namespacing): new namespaces require an ADR. `kontor-lease` is a **companion module** (`modules/lease/`), companion-owned namespaces, cohabiting per ADR-002 — exactly like `:asset/*`, `:invoice/*`, `:order/*`. New families: `:lease/*`, `:lease-liability/*`, `:lease-modification/*`.

```clojure
;; ── :lease — the lease contract master (framework-NEUTRAL facts) ──────
;; Classification is NOT here — it is per-(lease, ledger), on the
;; liability book (§4.3). :lease carries only what is true of the
;; contract regardless of reporting framework.
:lease/code               string  :db.unique/identity   ; "LSE-2026-014"
:lease/name               string
:lease/lessor             ref → :partner                ; the counterparty (kontor-partner)
:lease/underlying-asset-desc string                     ; what is leased — free text or a ref
:lease/asset-class        ref → :asset-class            ; the ROU :asset-class to use ("rou-property" …)
:lease/commencement-date  instant                       ; valid-time anchor; the ROU :asset/
                                                        ;   acquisition-date + in-service-date
:lease/term-months        long                          ; the lease term AS ASSESSED (option
                                                        ;   judgement already folded in — §3.5)
:lease/payment-amount     bigdec                         ; the periodic fixed payment (in-substance
                                                        ;   fixed; index-linked uses commencement
                                                        ;   index — §3.4)
:lease/payment-frequency  keyword                        ; :monthly | :quarterly | :annual — the
                                                        ;   :schedule frequency for both books
:lease/payment-timing     keyword                        ; :in-advance | :in-arrears — affects
                                                        ;   period-1 interest (annuity-due vs ordinary)
:lease/commodity          ref → :commodity
:lease/discount-rate      bigdec                         ; the rate pinned at commencement (IBR or
                                                        ;   implicit rate — §3.1). Per-book override
                                                        ;   lives on :lease-liability.
:lease/initial-direct-costs bigdec                       ; capitalised into the ROU asset cost (opt)
:lease/prepaid-at-commencement bigdec                    ; payments made at/before commencement (opt)
:lease/incentives-received bigdec                        ; lease incentives — reduce ROU cost (opt)
:lease/purchase-option-price bigdec                      ; if reasonably certain — in the liability (opt)
:lease/rou-asset          ref → :asset                   ; the ROU :asset (created by the
                                                        ;   commencement transactor via
                                                        ;   kontor.asset.asset/acquire!). One ROU
                                                        ;   :asset per lease; its per-ledger
                                                        ;   depreciation books are :asset-depreciation.
:lease/entity             ref → :entity                  ; legal-entity scope (ADR-031), optional
:lease/origin-document    ref → :audit-doc               ; the signed lease contract (ADR-038)
:lease/status             keyword                        ; ADR-034 facet — see below
:lease/note               string

;; ── :lease-liability — the per-(lease, ledger) liability book ─────────
;; Sibling of :asset-depreciation. THIS carries the per-ledger
;; classification, because the same lease is :finance on one ledger
;; and effectively off-balance on another (§4.3).
:lease-liability/lease            ref → :lease
:lease-liability/ledger           ref → :ledger          ; the framework book (ADR-021)
:lease-liability/identity         tuple [lease ledger]   ; :db.unique/identity — one book per pair
:lease-liability/classification   keyword                ; :finance | :operating
                                                        ;   (:exempt-* leases get NO book — §3.3)
:lease-liability/provider-id      keyword                ; :effective-interest (the built-in)
:lease-liability/opening-liability bigdec                ; the PV at commencement, THIS book's
                                                        ;   measurement (IFRS ≠ local-GAAP possible)
:lease-liability/discount-rate    bigdec                 ; per-book; defaults to :lease/discount-rate
:lease-liability/liability-account ref → :account        ; the BS lease-liability account
:lease-liability/interest-account ref → :account         ; the P&L interest-expense account
:lease-liability/lease-expense-account ref → :account    ; ASC 842 operating only — the single
                                                        ;   straight-line lease-expense P&L account
:lease-liability/commodity        ref → :commodity
:lease-liability/schedule         ref → :schedule        ; :schedule/kind :lease-liability;
                                                        ;   :origin-entity → this book; the runner
                                                        ;   fires it
:lease-liability/opening-fired-through long              ; mid-life-import: occurrences logged before
                                                        ;   this book's schedule started (opt)
:lease-liability/note             string

;; ── :lease-modification — append-only remeasurement/modification fact ─
;; Sibling of :asset-event. The transactors only ever CREATE one —
;; append-only by convention, not sealing-enforced (same posture as
;; :asset-event — modules/asset/src/kontor/asset/schema.clj:14-19).
:lease-modification/lease         ref → :lease
:lease-modification/ledger        ref → :ledger          ; which book is being remeasured — a
                                                        ;   modification may hit one or all books
:lease-modification/kind          keyword                ; :scope-increase | :scope-decrease
                                                        ;   | :payment-change | :term-change
                                                        ;   | :rate-reset | :index-reset
                                                        ;   | :early-termination
:lease-modification/date          instant                ; valid-time of the remeasurement
:lease-modification/liability-delta bigdec               ; the change in the liability measurement
:lease-modification/rou-delta     bigdec                  ; the change to the ROU asset (usually =
                                                        ;   liability-delta; differs on scope-decrease
                                                        ;   and the floor-at-zero case — §3.2)
:lease-modification/pnl-impact    bigdec                  ; any portion routed to P&L (scope-decrease
                                                        ;   gain/loss; ROU-floored-at-zero excess)
:lease-modification/new-term-months long                 ; for :term-change (opt)
:lease-modification/new-discount-rate bigdec             ; for :term-change / :rate-reset — the
                                                        ;   re-discount rate (§3.1) (opt)
:lease-modification/new-payment-amount bigdec            ; for :payment-change / :index-reset (opt)
:lease-modification/commodity     ref → :commodity
:lease-modification/transaction   ref → :transaction      ; the Dr ROU / Cr liability adjustment entry
:lease-modification/justification ref → :audit-doc        ; the modification agreement / reassessment
                                                        ;   memo — required (inline guard) for all kinds
:lease-modification/note          string

;; ── :lease/status — the ADR-034 lifecycle facet ──────────────────────
;;   :nil ──(commence)──▶ :active ──(modify, may recur)──▶ :active
;;   :active ──(lease runner: last occurrence)──▶ :expired
;;   :active ──(early-termination)──▶ :terminated
;;   :active ──(purchase-option exercised)──▶ :purchased
;;   :expired / :terminated / :purchased are terminal.
;; Governance (ADR-038): :nil → :active requires the signed contract
;;   (:requires-supporting-doc); :active → :terminated requires the
;;   termination agreement + :no-self-approval.
```

A note on **whether classification belongs on a tiny `:lease-treatment` entity instead of as an attr on `:lease-liability`**: §4.3 argues per-(lease, ledger), and `:lease-liability` *is already* the per-(lease, ledger) entity — so the classification is just an attr on it. The only reason to split it out would be if a `:exempt-*` lease needed a per-ledger row but *no* liability book — but §3.3 establishes that exempt leases need no `kontor-lease` entity at all. So: **classification is an attr on `:lease-liability`; no separate `:lease-treatment` entity.** The maintainer should confirm this (Q1 below).

---

## 8. Proposed ADR breakdown for `kontor-lease`

`kontor-lease` is a companion module (`modules/lease/`); its ADRs sit in `doc/decisions.md` alongside ADR-053..056 (`kontor-asset`), ADR-057..060 (`kontor-inventory`), ADR-061 (`kontor-expense`). The current highest ADR is **ADR-061**, so `kontor-lease` is **ADR-062..064**. Recommended: **three ADRs.**

| ADR | Decides | Scope |
|---|---|---|
| **ADR-062 — `kontor-lease`: the `:lease` contract + the ROU asset reuses `kontor-asset`** | The `:lease` entity (framework-neutral contract facts); the `:lease/status` lifecycle status-machine; the call that the ROU asset *is* an `:asset` (`:asset/class :rou`, created via `kontor.asset.asset/acquire!`, depreciated by a `kontor-asset` `:asset-depreciation` book) — no new `:rou-asset` entity; the commencement transactor (compute ROU cost + liability PV, build the initial-recognition entry, `acquire!` the ROU asset, `open-book!` the liability + ROU books); the short-term / low-value exemption path = a plain `:schedule`, no `:lease` entity; the `:audit-doc` / `:approval-policy` governance. Establishes `kontor-lease` as a companion. | Companion schema + the reuse decision + commencement |
| **ADR-063 — `kontor-lease`: a lease framework-treatment IS a `:ledger`; the `:lease-liability` book + `LeaseProvider` + the operating-lease ROU plug** | The central design call: the IFRS-16 / ASC-842-finance / ASC-842-operating / HGB-off-balance treatments are per-`:ledger` (ADR-021 + ADR-054 applied verbatim); classification is per-(lease, ledger), hence an attr on `:lease-liability` not on `:lease`; the `:lease-liability` per-(lease, ledger) book entity; the `LeaseProvider` protocol + the `EffectiveInterestProvider` built-in; the lease runner; **the operating-lease ROU "plug" — `LeaseRouPlugProvider`, a `DepreciationProvider` impl shipped by `kontor-lease` that reads the sibling liability book** (the one coupling, §4.2); the GL posting builders; the run-ordering convention (liability before ROU). | The parallel-book decision + the computation seam + the plug |
| **ADR-064 — `kontor-lease`: modifications, remeasurements, and prospective re-planning** | The `:lease-modification` append-only event entity; the modification transactor (record the fact, build the `Dr ROU / Cr liability` adjustment, re-plan both books' un-fired tails); the prospective-replan contract (fired occurrences never restated — IFRS 16 / ASC 842 modifications are prospective); the discount-rate reassessment rule (pinned at commencement, re-discounted only on term-change / rate-reset — §3.1); the liability-book half of `revise-book!` (the ROU half reuses `kontor.asset.depreciation/revise-book!`); variable-payment handling (index-linked = remeasurement; usage-based = a plain expense helper, no schedule); early termination. | Lifecycle events + re-planning |

Possible merge: ADR-063 is large; the `LeaseProvider` protocol *could* split into its own ADR (mirroring how `kontor-asset` split ADR-054 book-entity from ADR-055 provider+runner). Recommendation: **keep ADR-063 whole** — the parallel-book call, the liability book, and the plug provider are one tightly-coupled argument, and the plug provider only makes sense once you have both books on the table. Splitting would scatter the single most important design story (the operating-lease plug) across two ADRs.

`kontor-l10n-*` modules own any jurisdiction-specific lease data (e.g. an l10n module could ship a `LeaseProvider` variant if a jurisdiction mandates a non-standard compounding convention, or seed jurisdiction-specific `:asset-class` rows for ROU classes) — those are l10n's own ADRs, not `kontor-lease` ADRs, consistent with ADR-006.

---

## 9. Open design questions for the maintainer

1. **Classification: attr on `:lease-liability`, or a separate `:lease-treatment` entity?** §7 argues attr-on-`:lease-liability` (it is already the per-(lease, ledger) entity; exempt leases need no entity at all). The counter-argument: a future need for a per-ledger treatment row that carries more than a keyword (e.g. per-ledger transition-method metadata) would want its own entity. Recommendation leans attr; confirm.

2. **Is the operating-lease ROU plug provider's cross-book read acceptable?** `LeaseRouPlugProvider` (a `DepreciationProvider`) resolving and calling into a *different* subsystem's `:lease-liability` book + `LeaseProvider` is a deliberate coupling. The `DepreciationProvider` protocol passes `db` precisely to allow `db`-reads (`modules/asset/src/kontor/asset/depreciation_provider.clj:68`), and `kontor-asset`'s own note flags that MACRS mid-quarter is *already* a cross-asset read (research note 31 Q7) — so the precedent exists. But this is `kontor-lease` reaching into its *own* sibling entity, which is cleaner than reaching across companions. Confirm the maintainer is comfortable that the `kontor-asset` runner will drive a provider id (`:lease-rou-plug`) it does not itself ship — the runner resolves by id from the registry, so `kontor-lease` registers it; no `kontor-asset` change. Worth an explicit sentence in ADR-063 so a reviewer does not flag it.

3. **One ROU `:asset` per lease, or per (lease, ledger)?** The ROU asset's *cost* can differ per book (IFRS initial-direct-costs treatment vs a local-GAAP book). `kontor-asset` already separates the single `:asset` (one `:asset/acquisition-cost`) from N `:asset-depreciation` books — but it assumes the *cost* is shared. A lease where the IFRS ROU cost ≠ the US-GAAP ROU cost breaks that assumption. Options: (a) one `:asset`, accept that the rare per-book-cost-difference is handled by per-book `:asset-depreciation/depreciable-base` overrides (the attr already exists — `modules/asset/src/kontor/asset/schema.clj:316`) + a per-book opening adjustment; (b) one `:asset` per (lease, ledger), losing the single-identity-for-disposal benefit. Recommendation leans (a) — the `:depreciable-base` per-book override already absorbs most of the difference, and the cost difference is genuinely rare for a lessee. **Maintainer call** — and it should be stated in ADR-062.

4. **Run-trigger ownership.** Same as `kontor-asset` (research note 31 Q6): `kontor-lease` ships `run-lease!` + a `catch-up!` as *library functions*; *who* calls them (consumer-app cron, a close-period step, a workflow engine) is out of scope. Confirm and state it — so a reviewer does not flag a "missing scheduler."

5. **Disclosure notes — follow-up or never?** §5 puts IFRS 16.51-60 / ASC 842-20-50 disclosure-note *generation* out of v1 (the data is all there; only the layout is missing — it is the Anlagengitter situation, ADR-056). Confirm it is a *deferred follow-up* (a `kontor-lease` report engine, or rolled into the ADR-056 statement-definition machinery) and not permanently out — an IFRS-reporting customer's auditor *will* ask for the maturity analysis and the weighted-average disclosures.

6. **Index-linked remeasurement frequency.** A CPI-linked lease is, in principle, remeasured every time the index publishes (often annually). That is a *recurring* `:lease-modification` — which is fine (append-only, the transactor just runs each time), but a high-volume index-reset stream starts to look like it wants its own lighter-weight path than the full modification transactor. Probably v1-acceptable (annual cadence is low volume), but flag it: if a consumer has hundreds of CPI-linked leases, the per-reset modification + dual re-plan could want batching. Not a v1 blocker; note it.

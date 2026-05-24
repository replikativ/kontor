---
date: 2026-05-24
title: 107 — Phase 3 research-before — the incorporation rung, the `:disposal` companion, DE/FR/JP CIT
audience: maintainer + the Phase 3 implementation agents
status: research-before for the note-104 Phase 3 program; no code, ADR list at §6
---

# 107 — Phase 3 research-before: incorporation, disposal, and three CIT jurisdictions

Note 104 Phase 3 is the rung of the individual → corporation continuum where
"a side hustle becomes a separate legal entity." Three deliverables, in this
order of design risk:

1. A primitive (or pattern) for the **incorporation event** — sole-prop assets
   contributed into a new `:entity`; the founder receives shares and from
   that point may take salary or dividend.
2. A `:disposal` capital-gains **companion** — incorporation is itself often
   a disposal event, and the realised-gain ledger is the Phase-3 deferral
   note 104 §2 named.
3. **DE, FR, JP corporate income tax** as `PeriodTaxProvider` configs, on
   top of the shipped `kontor.corporate-income-tax` substrate.

This note is the research-before: it reads the kontor substrate at file:line
depth, surveys two reference implementations and the authoritative
incorporation mechanics, surfaces design calls, and proposes a Phase-3 ADR
list with the smallest possible substrate growth.

The maintainer is conservative. The bias throughout: **compose existing
primitives; new schema only where a composition would corrupt the audit
story or force a hidden flag.**

---

## §1. The substrate read — what already exists

### 1.1 Entities and multi-entity (ADR-031)

`src/kontor/schema.clj:3309-3420` defines `:entity/*`. Key attrs:

- `:entity/code` (identity), `:entity/name`, `:entity/country`, `:entity/functional-commodity`.
- `:entity/parent-entity` — self-reference for the group hierarchy.
- `:entity/kind` — `:operating` / `:elimination` / `:consolidation`.
- `:entity/lei`, `:entity/legal-form` (GLEIF ELF — "GmbH", "LLC", "KK"),
  `:entity/registration-status` (`:issued`/`:lapsed`/`:merged`/...).

`src/kontor/entity.clj:27-122` ships resolution + hierarchy walks
(`by-code`, `parent`, `ancestors`, `children`, `descendants`, `family`).

A sole proprietor today is **one `:entity`** in one kontor DB
(`src/kontor/sole_proprietor.clj:1-21` makes this explicit). The Phase 3
incorporation event introduces a **second `:entity`** — the new legal
company — that may live in the same DB or a separate one.

### 1.2 Cross-DB saga seam (ADR-074)

`src/kontor/side_effect/cross.clj:1-85` is the existing primitive for
"commit on conn A, then idempotently commit on conn B." `CrossTxRouter`
(`:98-105`) maps `system-id → conn`; `cross-tx-intent-tx-data` (`:152-190`)
writes an intent in the same tx as the upstream change; `drain!` (`:263-288`)
executes intents with `:cross-tx/step-id`-based idempotency.

This is exactly what an "incorporate sole-prop in DB A into a new corp in
DB B" operation needs **when** the founder runs two separate kontor
databases (one personal, one corporate — the conservative tax-segregation
posture). It is over-engineered when the founder runs a single kontor DB
that hosts both `:entities`.

### 1.3 The verb facade (ADR-095)

`src/kontor/book.clj:1-70` is the small named on-ramp. `entry-tx-data` is
the one pure builder; the verbs (`receive!`, `pay!`, `sell!`, `buy!`,
`receive-payment!`, `pay-bill!`, `transfer!`, `adjust!`) each bake in a
`:journal/type` and call through. **No new schema**, ADR-095 is organising
sugar.

The Phase 3 incorporation event is the kind of work `adjust!` already
mechanises — a multi-leg `:postings` vector, judgment entries, no
mechanical debit/credit. The question is whether incorporation deserves
its own verb (`incorporate!`) or stays as a documented `adjust!` recipe.

### 1.4 The sole-proprietor rung (Phase 2, note 105)

`src/kontor/sole_proprietor.clj:25-55` is the model the incorporation
primitive builds on:
- `business-net` marginalizes (σ_E) the business P&L over a period;
- `business-income-input` folds that net into a `PersonalIncomeTaxProvider`'s
  `:inputs` as an `:adjustments` base-transform addition.

**No new schema** — pure composition of `kontor.report/marginalize` +
`kontor.personal-income-tax`. Phase 3 should aim for the same posture: a
new namespace `kontor.incorporation`, possibly thin, that wires existing
primitives.

### 1.5 The `CorporateIncomeTaxProvider` and the `(scope, base-selector, schedule)` shape

`src/kontor/corporate_income_tax.clj:38-89` is the shipped substrate. It:
- marginalizes book profit (`book-profit`, `:25-36`) over the entity's P&L;
- applies an optional `:base-transform` (`:46-47`) for book-to-taxable
  add-backs;
- runs a `:flat` schedule (`:47-48`);
- optionally enforces a `:minimum-tax` floor via `greater-of` (`:48-49`);
- emits a `TaxReturnFacts` with `:line-items` for `:book-profit`,
  `:taxable-income`, `:tax-at-rate`, and (when fired) `:minimum-tax`.

`src/kontor/period_tax_provider.clj:67-100` defines `TaxReturnFacts` —
the multi-component record with `:credits`, `:surtaxes`, `:composed-of`,
`:line-items`. A jurisdiction that needs the gross corporate tax PLUS a
separate trade-tax component (DE) or a separate local tax (JP) emits two
components on one `TaxReturnFacts`, exactly as `kontor.l10n-ca.period-tax-provider`
fans CA T1 across federal+provincial (`modules/l10n-ca/.../period_tax_provider.clj:36-79`).

### 1.6 The shipped US CIT — the reference shape

`modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:40-49`:

```clojure
(defn us-corporate-income-tax-provider
  [{:keys [rate]}]
  (cit/corporate-income-tax-provider
   {:id        :us-1120
    :rate      (or rate federal-rate)   ;; 21 % IRC §11
    :authority :us-irs
    :commodity :USD
    :statute   "IRC §11"}))
```

A flat rate is one line of config. The Schedule M-1 / M-3 book-to-tax
reconciliation rides `:inputs :base-transform` — the consumer supplies
it; the substrate does not bundle a US adjustments table.

### 1.7 The `:asset` lifecycle and disposal seam (ADR-053..056)

`modules/asset/src/kontor/asset/asset.clj:249-314` ships `dispose!` /
`dispose-tx-data`. It records:
- an append-only `:asset-event :disposal` (with `:asset-event/amount` =
  proceeds, `:asset-event/justification` = disposal authorisation,
  `:asset-event/transaction` = the GL reversal entry the caller supplied);
- a `:asset/status` transition `:in-service → :disposed` (governance-gated
  by ADR-038 `:requires-supporting-doc` + `:no-self-approval`).

The GL side is `modules/asset/src/kontor/asset/posting.clj:182-242` —
`plan-disposal` computes `gain-loss = proceeds − NBV` and emits the four-
or-five-leg disposal entry (Dr proceeds + Dr accumulated, Cr asset cost,
± gain/loss). The disposal flow is **two-step today**: build the GL
tx-data with `plan-disposal`, transact it, then pass the resulting tx eid
into `dispose!` as `:transaction`.

Critically: there is **no `:disposal` first-class entity in kontor**
today. A disposal is recorded as (a) an `:asset-event :disposal` row (in
the asset module), AND/OR (b) a GL transaction whose narration says
"disposal". This is fine for a fixed-asset disposal where `kontor-asset`
is loaded; it is **silent** for a *share* disposal or a *non-asset*
disposal (the founder selling their personal stock, a sole-prop disposing
of an investment that was never registered as a `:asset`).

### 1.8 `:commitment` as the shape precedent (ADR-098)

`doc/decisions.md` ADR-098 records the `kontor-commitment` companion
shape: a first-class `:commitment` entity (kind, counterparty, committed
amount, fulfilled amount, state), a `:commitment-fulfillment` edge that
points at the settling `:transaction`, **kernel untouched**, all helpers
ADR-068-compliant. This is the closest precedent for a `:disposal`
companion — same posture: a domain entity, an edge to the GL transaction
that booked the gain/loss, no kernel attr added.

### 1.9 Other relevant substrate (brief)

- `src/kontor/consolidation.clj:1-50` — `translate-trial-balance-tx-data`
  + `eliminate-intercompany-pair-tx-data` + `consolidate!` for a family.
  Not directly needed for a single-incorporation event but the
  multi-entity machinery it relies on (per-entity sum-to-zero) is what
  makes a same-DB two-entity incorporation possible.
- `src/kontor/tax_schedule.clj:1-90` — the schedule algebra: `:flat`,
  `:progressive-bracket`, `:capped`, `:formula`, `:elect`, `:sum`,
  `surtax-on`, `greater-of`, `apply-base-transform`. Everything DE/FR/JP
  need is already in the algebra.

---

## §2. The incorporation primitive — design call

### 2.1 The bookkeeping shape (license-clean reference reading)

**The mechanics** (Kieso / IFRS for SMEs §22; IRS §351; OFBiz "owner equity"
pattern at `cwiki.apache.org/confluence/display/OFBENDUSER/Quick+Start+-+Basic+Accounting+Setup`):

The founder contributes assets (cash + sole-prop trade assets + assumed
liabilities) to the new company in exchange for shares. The new company's
opening books:

```
Dr  Cash / Inventory / Equipment / Receivables  (carryover bases)
    Cr  Accounts Payable                        (assumed liabilities)
    Cr  Common Stock (par × shares issued)      (equity, the par split)
    Cr  Additional Paid-in Capital              (equity, the residual)
```

The founder's personal books (where one is kept):

```
Dr  Investment in NewCo Stock                   (basis carried from contributed assets)
    Cr  the contributed asset accounts
    Dr  the assumed-liability accounts          (relieving the sole-prop's liability)
```

The two sides **must agree** on the carryover basis. Under US §351 / a
typical EU rollover regime / Japan's tax-deferred reorganisation rules, the
event is **tax-deferred** (no immediate gain/loss) provided the basis carries
over. Otherwise (or to the extent of boot received), it is a **deemed
disposal** — gain/loss is recognised, and that gain/loss flows through
`:disposal` (§3).

**OFBiz pattern** (from the user-manual citation): organisations get an
"Internal Organization" role; the owner contribution is an `AcctgTrans`
debiting Cash and crediting Capital. OFBiz does NOT have a dedicated
"incorporation event" entity — it is just a balanced two-leg journal under
an explicit narration. **Lesson: the bookkeeping is unremarkable; the
substrate work is the cross-entity coordination + the basis carryover
discipline.**

**KillBill** (light skim): the account/subscription/entitlement model
separates a `Account` (the customer/owner) from the `Subscription`
(the obligation). It does not model incorporation at all (it is a billing
engine), but the separation-of-owner-from-thing-owned shape generalises:
in kontor terms, the founder is a `:partner` (the owner); the new company
is an `:entity` (the thing owned); the link is a "shareholder" relation,
not a posting attr.

### 2.2 The three deployment shapes

The maintainer has to choose what `(incorporate! …)` *does*. Three honest
deployment shapes exist:

**Shape A — single-DB, two `:entity`s.** Sole prop is `:entity` E1
(:operating); incorporation creates `:entity` E2 (:operating, with
`:entity/parent-entity` = nil — a separately registered legal entity,
NOT a subsidiary), tagged `:entity/registration-status :issued`,
`:entity/legal-form` set. The contribution is **one transaction with
postings on both entities** — ADR-031 already enforces per-entity
sum-to-zero, so the entry must balance on E1 (Cr the contributed asset
accounts; Dr "Investment in E2") AND on E2 (Dr the asset accounts; Cr
equity) independently. The two entities run in the same kontor DB, share
a chart, and one report-postings call can `marginalize` either.

**Shape B — two DBs, cross-tx saga.** Sole prop in `personal-conn`, new
corp in `corp-conn`. `(incorporate! personal-conn corp-conn opts)` writes
the personal-side legs against `personal-conn` AND a
`cross-tx-intent-tx-data` (ADR-074) targeting the corporate side. The
drain worker commits the corporate-side opening entry; the `:cross-tx/step-id`
discipline gives content-hash idempotency across worker restarts. This is
the conservative tax-segregation posture (a founder who wants the
corporate books fully separated from the personal — a common pattern when
the personal returns are prepared by a different accountant than the
corporate returns).

**Shape C — consumer choice.** The substrate ships **both** verbs. The
maintainer picks the documented default in the docstring; the consumer
overrides.

**Recommendation: Shape C, with Shape A as the default.** The reasoning:

- The kontor substrate **already** supports multi-entity from one DB
  (ADR-031, the showcase at `doc/showcases/04_multi_entity_intercompany.clj`).
  Shape A is one new namespace, zero new schema, zero new
  cross-DB-orchestration risk.
- ADR-074 already exists; Shape B is a thin wrapper that composes
  ADR-074's `cross-tx-intent-tx-data` with Shape A's per-side tx-data
  builders. No new substrate; the consumer pays the saga cost only when
  they opt into two-DB segregation.
- Conservative posture wins both ways: a one-DB consumer never sees the
  cross-DB code path; a two-DB consumer gets the audited saga.

### 2.3 The proposed API sketch (no code change, illustration only)

```clojure
(ns kontor.incorporation
  "The Phase 3 incorporation primitive — `incorporate-tx-data` /
   `incorporate!` — composes existing kontor primitives (entity,
   book.entry, posting.entity-scoped sum-to-zero) into the
   sole-prop → corporation event.")

;; Pure builder — single-DB shape (Shape A):
(defn incorporate-tx-data
  "Sole-prop E1 → new corp E2, both in `db`. Returns ONE balanced tx-data
   that ADR-031 will validate per-entity. Inputs:

     :sole-prop-entity     E1 (resolvable per kontor.entity)
     :new-entity-spec      {:code :name :functional-commodity :legal-form
                            :country :registration-status :parent-entity?}
     :contributions        [{:account :amount :commodity :basis?} ...]
                           — the contributed sole-prop assets; :basis
                           defaults to :amount (carryover); when :basis
                           differs from :amount → a `:disposal` is also
                           emitted (§3, deemed disposal)
     :assumed-liabilities  [{:account :amount :commodity} ...]
     :shares-issued        {:commodity (a new `:commodity`, ticker = E2's
                            stock symbol) :par :count :additional-paid-in?}
     :journal              the journal eid to post under
     :effective-date       the incorporation date
     :owner-partner        the founder's `:partner` ref (so the GL carries
                           the shareholder identity)
     :origin-document      the incorporation document `:audit-doc` ref"
  [db opts]
  ...)

;; Wrapper — two-DB Shape B:
(defn incorporate-cross!
  "Same opts as `incorporate-tx-data`, but takes (personal-conn
   corp-conn opts router). Writes the personal-side legs against
   personal-conn AND a `:cross-tx-post` intent targeting corp-conn;
   `(side-effect.cross/drain! personal-conn router)` commits the
   corp-side opening entry."
  [personal-conn corp-conn router opts]
  ...)
```

### 2.4 Specific design questions answered

- **Should `:entity/parent-entity` be set on the new company?** **No, by
  default.** A subsidiary (`:parent-entity` set) participates in
  consolidation (ADR-073 walks the family); a freshly incorporated
  founder-owned company is NOT typically a subsidiary of the founder's
  personal "entity" (the founder is not a legal entity). The shareholder
  relation lives on `:owner-partner` (a `:partner` ref) and the founder's
  Investment-in-NewCo holding (§2.5 basis carryover). When the new corp
  becomes a subsidiary of a HOLDING entity, the consumer sets
  `:entity/parent-entity` then — orthogonal to incorporation.

- **Should "Common Stock" be a `:commodity`?** **Yes, optionally.** This
  is the natural way to track share counts in kontor. The new corp's
  equity is a balance in (`:account` Equity:CommonStock, `:commodity` the
  new stock ticker), and the founder's holding mirrors it as
  (`:account` Investment:NewCo:Stock, `:commodity` the new stock ticker).
  `:commodity/precision 0` for a whole-share ticker. No schema change —
  `:commodity/symbol` is already free-form (it just happens to default
  to ISO-4217 for fiat).

  This composes with `kontor.fx`: the founder revalues their holding at
  any "stock price" (entered as an `:fx-rate` from STOCK to USD), enabling
  the §3 disposal at fair value when they later sell.

### 2.5 Basis carryover — the founder's side

The contributed assets had a basis in the sole prop's books (the
acquisition cost, less accumulated depreciation if any — the NBV that
`kontor.asset.depreciation/accumulated-depreciation` returns). That basis
**carries** to the founder's basis in the new shares, **not** to the new
corp's books for the assets (the new corp's books get the cost-equal-to-
contributed-amount in the opening entry).

In kontor terms: the founder's `Investment-in-NewCo` account holds the
shares at the **basis** (not at the fair-market value); the difference
between basis and FMV is the **unrecognised** gain that will become a
`:disposal` only when the founder sells the shares later.

**Does kontor need to track basis as a schema attr?** **No, not as a
posting-line attr.** Basis IS the debit amount on the founder's
`Investment-in-NewCo` account; the GL's running balance on that account
**is** the basis. When the founder later sells, `:disposal/basis` is read
off the GL balance at the disposal date (or off the per-lot allocation
when share lots matter — ADR-029's `CostingProvider` is the existing seam
for FIFO/LIFO/specific-id, and kontor's `:lot/*` schema is the storage).

So basis carryover is a **discipline** (the incorporation builder posts
basis to the founder's Investment account, not FMV) plus the **existing
lot machinery** (when share lots matter for a later partial disposal),
NOT a new schema. The Phase 3 documentation must teach this discipline;
the substrate already supports it.

### 2.6 Salary vs dividend election

The founder now has two options for personal compensation from NewCo:

1. **Salary** — NewCo runs the founder as an `:employment` via
   `kontor-hr` (ADR-075) + the country's payroll provider. The founder's
   personal books receive the net salary as a wage credit; NewCo books it
   as wage expense + payroll-tax liability + SI contributions. **Already
   shipped, across 11 jurisdictions.**

2. **Dividend** — NewCo declares a dividend, debiting Retained Earnings
   and crediting Dividends Payable; on payment, Dividends Payable is debited
   and Cash credited. The founder's personal books debit Cash and credit
   Dividend Income (a non-employment income account). The PIT provider
   then taxes the dividend (DE: Abgeltungsteuer 25 %; FR: PFU 30 % or
   barème; JP: 20.315 % separately, or aggregate election; US: qualified-
   dividend rate stack on the personal return).

Today this is **two `adjust!` calls** with hand-built postings. Phase 3
should ship two named verbs in `kontor.book` for it:

```
declare-dividend!   ;; on the corp side
distribute-dividend! ;; on both sides (corp pays; personal receives)
```

— bake in `:journal/type :general` (for the declaration) / `:journal/type :cash`
(for the payment), document the conventional accounts (Retained Earnings,
Dividends Payable, Dividend Income), and trust the consumer to wire the
chart. Same posture as `receive!` / `pay!` / `sell!`. **No new schema.**

(For symmetry: `kontor-hr` already supplies the salary path; the new verbs
fill the equity-distribution gap.)

---

## §3. The `:disposal` capital-gains companion

### 3.1 The gap

`kontor-asset`'s `dispose!` (§1.7) records a disposal of a registered
`:asset`. But Phase 3 needs disposal modelling for things that are **not**
registered fixed assets:

- the founder selling their NewCo shares;
- the sole prop disposing of investment property (or any non-PP&E asset);
- the deemed disposal at incorporation when basis ≠ FMV;
- intra-group share transfers (a holding company sells a subsidiary's
  stock);
- partial disposals (selling 30 % of a holding — `:partial-disposal` is
  RESERVED in `modules/asset/src/kontor/asset/schema.clj:215`).

Today these are silent — they happen as journal entries with no domain
entity binding the disposal, no lifecycle, no per-disposal audit doc,
and (critically for capital-gains tax) **no per-disposal record of
proceeds, basis, holding period, gain/loss**. Note 104 §2 named this
deferral explicitly.

### 3.2 The shape — companion, not kernel

Following ADR-098's `:commitment` precedent exactly:

- New companion module `modules/disposal/` — `kontor-disposal`. Kernel
  schema untouched.
- One first-class entity `:disposal`. One edge entity
  `:disposal-fulfillment` is **not** needed (a disposal is the
  realisation event itself — one disposal, one settling transaction,
  unlike a commitment which can be liquidated by many fulfilments).
  Multi-tranche sales of the same lot are multiple disposals against the
  same underlying lot, not multiple fulfilments of one disposal.

Proposed shape:

```
:disposal/external-id        string (identity)
:disposal/kind               keyword
                             ;; :asset-sale | :asset-scrap |
                             ;; :share-sale | :investment-sale |
                             ;; :deemed-incorporation |
                             ;; :deemed-distribution |
                             ;; :partial
:disposal/asset              ref → :asset           (when :asset-* kind)
:disposal/holding-account    ref → :account         (when :share/:investment
                                                     — the GL account the
                                                     basis sits on)
:disposal/lot                ref → :lot             (optional, for
                                                     specific-id costing —
                                                     ADR-029)
:disposal/counterparty       ref → :partner         (the buyer)
:disposal/entity             ref → :entity          (the disposing entity —
                                                     ADR-031 scope)
:disposal/effective-date     instant                (when ownership transferred)
:disposal/acquired-on        instant                (basis date — drives
                                                     short-vs-long holding-
                                                     period)
:disposal/proceeds           bigdec                 (gross proceeds — Money
                                                     would be 2 attrs)
:disposal/proceeds-commodity ref → :commodity
:disposal/basis              bigdec                 (the disposed portion's
                                                     basis — :proceeds-commodity)
:disposal/basis-commodity    ref → :commodity
:disposal/realized-gain-loss bigdec                 (denorm = proceeds − basis,
                                                     computed by the helper)
:disposal/holding-period     keyword                (:short | :long | :n/a —
                                                     jurisdiction maps from
                                                     (acquired-on, effective-date))
:disposal/transaction        ref → :transaction     (the settling GL entry —
                                                     the gain/loss leg lives
                                                     there)
:disposal/justification      ref → :audit-doc       (sale contract / Form 8949
                                                     line / SPA — required
                                                     guard, ADR-038 pattern)
:disposal/state              keyword                (:draft | :realized |
                                                     :voided — ADR-034 facet)
:disposal/notes              string
:disposal/recorded-by-uid    ref → :create/uid
:disposal/recorded-at        instant
```

Helpers (all ADR-068 — `*-tx-data` + `!` through `kontor.validation`,
bitemporally stamped):

- `record-disposal!` — emits a `:disposal` + drives `:state nil → :realized`.
- `void-disposal!` — `:realized → :voided` with required justification
  (a disposal posted in error; the GL entry it pointed at is reversed
  separately).
- `partial-of!` — emits multiple `:disposal`s against one underlying
  holding, each tagged `:disposal/kind :partial`, each carrying its
  share of basis.
- `disposals-of` / `realized-gain-summary` — queries for a CGT return.

### 3.3 Why companion (not kernel)

The same ADR-002 reasoning as `kontor-commitment`: not every consumer
needs disposal modelling (a SaaS-bookkeeping consumer with no investments
and no shares to sell never touches it). The kernel posting model is
unchanged; `:disposal/transaction` is the soft link to the kernel's GL.

### 3.4 Integration with `kontor-asset`

Today `dispose!` (`modules/asset/.../asset.clj:249`) records the asset
lifecycle event but NOT a `:disposal`. Phase 3 should change `dispose!`
to also emit a `:disposal` (`:kind :asset-sale` or `:asset-scrap`) when
`kontor-disposal` is loaded — same composition pattern as
`kontor-commitment` integrating with `kontor-collections`. The
`:disposal/asset` ref binds the two. **`dispose!`'s existing signature
is preserved**; the `:disposal` emission is an additive enrichment.

### 3.5 The incorporation deemed-disposal seam

When `incorporate-tx-data` (§2) detects `contributions` with `:basis
≠ :amount`, it emits one `:disposal` per such contribution, tagged
`:disposal/kind :deemed-incorporation`, with `:proceeds` = the FMV
implied by the contributed amount and `:basis` = the carried-in basis.
The realised gain/loss on the founder's personal-tax return then reads
from the `:disposal` set for the period.

### 3.6 Lifecycle and the holding-period rule

`:disposal/holding-period` is a **denorm**, set at `record-disposal!`
time by reading `(acquired-on, effective-date)` against the jurisdiction's
short-vs-long rule (US: 1 year; DE: typically held to maturity for
private investors; FR: per-asset-class). The substrate stores `:short` /
`:long` / `:n/a`; the jurisdiction's CGT provider (a future
`PeriodTaxProvider :kind :capital-gains-tax`) reads the per-disposal
holding-period and applies the correct schedule.

---

## §4. DE / FR / JP CIT — per-jurisdiction fit assessment

For each: does it fit `corporate-income-tax-provider` cleanly, or stress
the abstraction?

### 4.1 DE — Körperschaftsteuer + Gewerbesteuer + Solidaritätszuschlag

**Three taxes that file together** ([pwc, German Corporate Tax overview](https://taxsummaries.pwc.com/germany/corporate/taxes-on-corporate-income)):

1. **Körperschaftsteuer (KSt)** — flat 15 % on taxable profit (book
   profit ± Steuerbilanz adjustments).
2. **Gewerbesteuer (trade tax)** — base rate 3.5 % × municipal multiplier
   (Hebesatz, 200–900 %). Net rate ~7–17.5 %. Base is taxable profit
   ± §8 add-backs (25 % of financing costs over €200 000, implicit
   financing in leases/rents/royalties) and § 9 reductions.
3. **Solidaritätszuschlag (Soli)** — 5.5 % of the KSt liability (a
   surtax-on, not on the base).

**Fit assessment**:

- **KSt**: clean — one `:corporate-income-tax` component with
  `rate = 15M`. The book-to-Steuerbilanz add-backs ride
  `:inputs :base-transform :adjustments` (same as US Schedule M-1).

- **Gewerbesteuer**: clean — a SECOND `:corporate-income-tax` component
  on the same `TaxReturnFacts` (multi-component is what CA T1 already
  does), `:authority :de-municipality` (with `:jurisdiction-specific-codes`
  carrying the Hebesatz). The §8 add-backs ride `:base-transform :adjustments`
  on this component (DIFFERENT from the KSt add-backs — note 105's
  ordered-signed adjustment layer handles this fine). The Hebesatz IS the
  rate-config-by-municipality — a per-municipality provider config; the
  substrate is unchanged.

- **Soli on KSt**: clean — `surtax-on` is exactly designed for tax-on-a-
  tax. The Soli component carries `:composed-of [:corporate-income-tax]`
  per ADR-099. Already shipped in `kontor.tax-schedule`.

**Stress**: none. DE is the **most demanding** CIT jurisdiction in note
102's survey AND it fits cleanly. The shape work is:

- a `de-corporate-income-tax-provider` returning a 3-component
  `TaxReturnFacts` (KSt + Gewerbesteuer + Soli);
- a small `de-gewerbesteuer-municipality-config` table (one row per
  municipality, `:hebesatz` BigDecimal) — l10n content, not substrate.

Notable observations:

- The Merz coalition's reform to lower KSt to ~10 % is "still under
  negotiation" — the provider must take rate as a config not a constant,
  exactly as `us-1120` does. Annual-rate-bundling per ADR-015 covers this.
- Mindest-KöSt does NOT apply to corporations in DE (it is an AT
  feature, already handled by `:minimum-tax` in the substrate).

### 4.2 FR — Impôt sur les Sociétés (IS) — standard 25 % with SME 15 % carve-out

**The rate** ([taxsummaries.pwc.com, France](https://taxsummaries.pwc.com/france/corporate/significant-developments) /
[hayot-expertise.fr 2026](https://hayot-expertise.fr/en/blog/french-corporate-income-tax-is-2026-rates-installments-filing-et-optimization)):

- Standard rate: 25 % flat.
- **Reduced rate**: 15 % on the first €42 500 of taxable profit, for SMEs
  meeting both:
  - turnover < €10 million;
  - share capital fully paid up AND ≥ 75 % held by individuals (or by
    other SMEs themselves meeting the test).
- Exceptional surtax for very-large companies (turnover > €1 billion)
  through FY2026.

**Fit assessment**:

- **Standard 25 %**: clean — `:flat` schedule.
- **The 15 % + 25 % SME stack**: a **two-bracket progressive** schedule!
  `[{:rate 0.15M :upper 42500M} {:rate 0.25M :upper nil}]`. The kontor
  substrate's `:progressive` shape handles this with one config. **Fits
  cleanly** — it is exactly what `:progressive` is for, and the
  US 1040 `progressive` already exercises the multi-bracket fold
  (`l10n_us/period_tax_provider.clj:60-84`).

  The SME eligibility test (turnover + ownership) is NOT something the
  substrate computes — it is a precondition the consumer asserts when
  selecting which schedule to use. Two approaches:
  1. **One provider, two configs** — `fr-cit-sme-provider` (uses the
     progressive schedule) vs `fr-cit-standard-provider` (uses `:flat
     0.25M`). The consumer chooses which to instantiate based on their
     SME-status determination. Clean.
  2. **One provider, branch by `:tax-unit`** — the `:tax-unit` carries
     `{:sme? true}` and a `:formula` schedule selects the bracket table
     vs the flat rate. Same mechanism FR personal income tax uses for
     the quotient familial (ADR-099 GAP 3); same mechanism US 1040 uses
     for filing status.

  Recommendation: option (2). It mirrors note 105's `:tax-unit`-in-`ctx`
  posture and avoids the "two providers for one tax" overcounting risk
  in a multi-component fan-out.

- **The € 1 B exceptional surtax**: a `surtax-on :corporate-income-tax`
  component, conditional on a `:tax-unit :turnover > 1e9M` test in the
  config. Already in the algebra.

**Stress**: none. FR is the cleanest of the three.

### 4.3 JP — national CIT + national local CIT + enterprise tax + inhabitants' tax (+ 2026 defense surtax)

**The stack** ([taxsummaries.pwc.com, Japan](https://taxsummaries.pwc.com/japan/corporate/taxes-on-corporate-income) /
[jetro.go.jp Section 3.3](https://www.jetro.go.jp/en/invest/setting_up/section3/page3.html)):

1. **National corporate tax** — 23.2 % standard; **reduced 15 %** on the
   first ¥8 million for SMEs with paid-in capital ≤ ¥100 million.
2. **National local corporate tax** — 10.3 % of the national CIT
   liability (a surtax-on, NOT on the base).
3. **Enterprise tax** — prefectural, on income; rates vary by business
   size and prefecture (~3.5 % to ~7 %).
4. **Inhabitants' tax** — prefectural+municipal, two components:
   - **Income levy** — % of national CIT liability (surtax-on);
   - **Per-capita levy** — flat JPY amount based on capital and headcount
     (JPY 70 000 to JPY 3.8 million).
5. **Defense surtax (2026+)** — 4 % of national CIT liability, with
   ¥5 million basic deduction, for FYs beginning on or after 2026-04-01.

**Fit assessment**:

- **National CIT** with SME reduced rate: clean — same two-bracket
  `:progressive` (`[{:rate 0.15M :upper 8000000M} {:rate 0.232M :upper nil}]`)
  + `:tax-unit {:sme? true :paid-in-capital …}` test, exactly like FR.

- **National local CIT (10.3 % surtax on national)**: clean —
  `surtax-on` is the precise operator. Component `:composed-of
  [:corporate-income-tax]`.

- **Enterprise tax**: clean — a second `:corporate-income-tax` component,
  `:authority :jp-prefecture`, separate base (taxable income with JP-
  enterprise-tax-specific adjustments per ADR-099 `:base-transform`).
  Per-prefecture rate config — same pattern as DE Hebesatz.

- **Inhabitants' income levy**: clean — `surtax-on :corporate-income-tax`.

- **Inhabitants' per-capita levy**: **MILD STRESS**. This is a **flat
  per-period amount** that depends on `:tax-unit` (paid-in capital +
  headcount), not on a base. The current substrate's `:flat` schedule
  needs a `base`. Two options:
  - **Use `:formula`** — the per-capita levy IS what `:formula` is for:
    `(fn [_base _ctx] flat-amount)`. Returns the same value regardless of
    base. Works today, ugly DX (passing a base that is ignored).
  - **Add a `:fixed-amount` schedule** to `kontor.tax-schedule`. Trivial
    extension: `{:schedule/type :fixed-amount :amount <BigDecimal>}`
    → `apply-schedule` returns `:amount` ignoring the base.

  **Recommendation: add `:fixed-amount`.** It is the third trivially-
  small schedule shape (one line, one helper, one test) and it teaches
  "a per-capita levy IS a schedule with no base", which is conceptually
  clean. This is a tax-schedule algebra extension, an ADR-099 addendum,
  schema-free, additive. **The first abstraction stress this research
  surfaces — small.**

- **Defense surtax (2026+)**: `surtax-on :corporate-income-tax` with a
  base deduction. The `surtax-on` operator takes a deduction config
  today? Check `kontor.tax-schedule/surtax-on` — if not, the deduction
  is applied as a `:base-transform :adjustments` on the surtax
  component (negative additions = floor). Likely fits the existing
  algebra; verify at implementation time.

**Stress**: ONE — the per-capita inhabitants' levy needs `:fixed-amount`.
This is a small additive schedule kind, ADR-trackable, schema-free.

### 4.4 Bottom line — DE / FR / JP

| Jurisdiction | Components | Fits the substrate? | Stress |
|---|---|---|---|
| DE | KSt + Gewerbesteuer + Soli | YES | None — per-municipality config table, all in algebra |
| FR | IS (SME progressive) + ≥€1B surtax | YES | None — `:tax-unit`-driven schedule selection (same as FR PIT) |
| JP | National CIT + local CIT + enterprise + inhabitants (income + per-capita) + 2026 defense | YES with `:fixed-amount` schedule add | ONE — per-capita levy needs `:fixed-amount` schedule kind |

DE has the most components (3) and is structurally clean. FR is the
cleanest. JP is the most components (5+) and surfaces the lone stress.

All three are **substantial l10n implementation work** (faithful tables,
golden tests against published worked examples, current-year-figures
disclaimer per ADR-015) but **none demand new kernel substrate** beyond
the JP `:fixed-amount` schedule kind.

---

## §5. Market pain (brief)

Three pain points actual consumers hit at the incorporation rung:

1. **Owner draw vs salary vs dividend confusion in the GL**. Capterra's
   1700-review analysis ([Capterra simple-accounting review](https://www.capterra.com/resources/simple-accounting-software-what-easy-to-use-really-means-according-to-1700/))
   surfaced ease-of-use as the most-cited pain — and the survey's "screens
   organised around accounting concepts rather than business tasks"
   complaint applies directly to the salary/dividend split. Founders fresh
   out of incorporation often book a quarterly distribution as "owner
   draw" against a non-existent equity account, leaving the corporation's
   Retained Earnings overstated and Dividends Payable absent. **kontor's
   answer**: ship `declare-dividend!` / `distribute-dividend!` as named
   verbs (§2.6) so the consumer cannot accidentally do the wrong thing —
   the dividend path goes through Retained Earnings by construction.

2. **Basis mishandling at incorporation → wrong gain at exit**. The §351
   literature ([Cordasco "Deep Dive into Section 351"](https://cspcpa.com/2026/04/29/the-irss-gift-that-nobody-talks-about-a-deep-dive-into-section-351-tax-free-transfers/)
   and the IRS Rev. Rul. 03-51 itself) calls out the assumed-liabilities-
   exceeding-basis trap and the boot-recognition rule. When the founder
   later sells, the IRS computes gain off the carried-over basis — but
   the founder's hand-kept records often show the FMV-at-incorporation
   instead, producing an over- or under-reported gain. **kontor's
   answer**: the §2.5 discipline (the Investment account holds basis,
   not FMV) plus the §3.5 deemed-disposal seam (every basis-≠-amount
   contribution emits a `:disposal :deemed-incorporation` that the
   personal CGT return picks up). The consumer's books carry the basis
   automatically; the disposal record IS the trail.

3. **Double-taxation of dividends — the C-corp surprise**. Patriot
   ([Patriot Software, double-taxation](https://www.patriotsoftware.com/blog/accounting/double-taxation/))
   and Bench ([Bench, "What Is Double Taxation?"](https://www.bench.co/blog/tax-tips/double-taxation))
   both name this as the most-asked-about C-corp pain point. The
   founder pays 21 %/25 %/23.2 % at the corp and then 15 %/30 %/20.315 %
   on the personal return when they take dividend. The kontor substrate
   cannot make double taxation go away — it is a statutory fact — but it
   CAN make the math visible: a `kontor.report` two-line summary "tax on
   $X distributed: $A at the corp + $B at the personal = $C effective
   on $X" lets the founder see the cost of dividend-vs-salary at any
   tax-planning moment. This is a single `report-postings` + two
   provider calls — Phase 3 ships it as an example, not as substrate.
   Note: an S-corp election / partnership pass-through is the textbook
   work-around; kontor's substrate covers the *bookkeeping* either way
   because there is just one ledger; the *tax characterisation* is a
   PIT/CIT provider config choice.

These three are sanity-check signals — the maintainer is conservative
and these don't dictate substrate; they dictate that the Phase 3
verbs/recipes/reports are documented prominently.

---

## §6. Recommended Phase 3 implementation order + ADR list

**Sequencing rationale**: build the `:disposal` companion FIRST because
incorporation depends on it (deemed-disposal seam, §3.5); the
incorporation primitive next (it composes existing primitives + the new
`:disposal`); the three CIT providers last, in parallel agents, because
they are independent l10n configs that each take a `corporate-income-tax-
provider` config + tests.

**Recommended ADRs (sequential numbering)**:

1. **ADR-101 — `kontor-disposal` companion**. A new `modules/disposal/`
   artifact, the `:disposal` entity (§3.2 shape), ADR-098-style helpers
   (`record-disposal!`, `void-disposal!`, `partial-of!`,
   `disposals-of`, `realized-gain-summary`), the ADR-053 `dispose!`
   integration (§3.4). Kernel untouched.

2. **ADR-102 — `kontor.incorporation` primitive**. A new kernel
   namespace (one file, schema-free) implementing `incorporate-tx-data`
   (Shape A) + `incorporate-cross!` (Shape B over ADR-074). The §2.6
   dividend verbs go HERE too — they live in `kontor.book` proper as
   `declare-dividend!` / `distribute-dividend!`. (Two ADRs, one
   commit-per-ADR, or one ADR covering both — the maintainer's
   judgement.)

3. **ADR-099 Addendum — `:fixed-amount` schedule kind**. Tiny, additive
   `kontor.tax-schedule` extension to support JP's per-capita
   inhabitants' levy. **One sentence in `apply-schedule`'s case
   dispatch, one helper, two tests.** Lands BEFORE the JP CIT provider.

4. **ADR-103 — `de-corporate-income-tax-provider`**. The DE 3-component
   `TaxReturnFacts` (KSt + Gewerbesteuer + Soli). Per-municipality
   Hebesatz config table. Faithful golden test against a published BMF
   worked example.

5. **ADR-104 — `fr-corporate-income-tax-provider`**. FR IS provider —
   `:tax-unit`-driven schedule selection (SME progressive vs flat 25 %)
   + ≥€1B exceptional surtax. Faithful golden test against a published
   DGFiP worked example.

6. **ADR-105 — `jp-corporate-income-tax-provider`**. JP 5-component
   `TaxReturnFacts` (national CIT + local CIT + enterprise + inhabitants
   (income + per-capita) + defense surtax). Per-prefecture rate config.
   Faithful golden test against a published NTA / JETRO worked example.

**ADRs 103/104/105 fan out to parallel agents** per note 104 §4's
per-stage rhythm — each agent has the dual mandate (implement faithfully;
review whether the substrate stresses; raise findings).

**Total substrate growth**:

- **Schema**: ONE new namespace (`:disposal/*`, companion module — kernel
  schema UNCHANGED).
- **Kernel code**: ONE new file (`kontor.incorporation`), TWO new verbs
  in `kontor.book` (dividend declare/distribute), ONE new schedule kind
  (`:fixed-amount`) in `kontor.tax-schedule`.
- **L10n code**: three new providers in `modules/l10n-de/`,
  `modules/l10n-fr/`, `modules/l10n-jp/` + their config tables.

This is well within the "conservative; compose existing primitives where
possible" posture the project's culture demands.

---

## §7. Open questions for the maintainer

1. **Two-DB incorporation, ship now or later?** Shape B (cross-DB via
   ADR-074) is one wrapper over Shape A + an existing primitive. Cost is
   small; benefit is real for tax-segregated consumers. Recommendation:
   ship in ADR-102 for completeness; document Shape A as the default.
   **Alternative**: defer Shape B to a follow-up ADR once Shape A has a
   consumer.

2. **Does `incorporate!` deserve a `kontor.book` verb?** §2.3 sketches it
   as a separate `kontor.incorporation` namespace; an alternative is a
   single `kontor.book/incorporate!` verb (consistent surface). Either
   works. Recommendation: separate namespace, because incorporation
   composes `entry!` MANY times (one per contribution + the equity
   split + the founder-side investment) — it is a `kontor.process`-
   shaped multi-step operation, not a single balanced journal.

3. **`:disposal` companion or kernel namespace?** §3.3 argues
   companion. Counterargument: capital-gains tax is part of the
   "complete the tax system" program (note 104), and other tax
   substrate lives in the kernel (`kontor.corporate-income-tax`,
   `kontor.personal-income-tax`). Disposal is a *data-recording* primitive
   that the kernel-resident CGT provider would *read* — same posture as
   `kontor-asset` whose disposal data the kernel does not consume directly.
   Recommendation: **companion**, because not every consumer disposes of
   anything (a pure-service consumer never sells a share, never sells a
   fixed asset).

4. **`:disposal/proceeds` + `:basis` as `BigDecimal` + commodity ref,
   or as `Money` value type?** kontor's existing pattern (look at
   `:asset-event/amount` + `:asset-event/commodity`) is two attrs. Stay
   consistent. The helper that emits a `:disposal` constructs Money
   internally; the storage is two attrs.

5. **The "Common Stock as `:commodity`" convention** (§2.4) — is the
   maintainer comfortable with a non-currency ticker living alongside
   ISO-4217 in `:commodity/symbol`? The schema permits it; the existing
   `:commodity/precision` field is already there to handle integer-share
   commodities. Recommendation: yes, document the convention in
   `kontor.incorporation`'s docstring + add a section to `doc/conventions.md`.

6. **Should `kontor.book/declare-dividend!` post against a hard-coded
   `:journal/type :general` or accept the consumer's chosen journal
   type?** All existing verbs bake in a journal type. Recommendation:
   bake `:general` in (declarations are clearly general-journal
   territory); `distribute-dividend!` bakes `:cash` (it is a cash
   payment).

7. **`:disposal/holding-period` as denorm vs computed.** §3.6 stores
   `:short`/`:long`/`:n/a`. Computed alternative: the CGT provider reads
   `(acquired-on, effective-date)` and applies the rule each call.
   Denorm wins on jurisdictional clarity (different countries have
   different rules; the rule applied at disposal time is the right one
   under the law-as-it-stood doctrine). Recommendation: denorm at
   `record-disposal!` time, with the provider that classified it
   recorded in `:disposal/notes` or a dedicated audit-doc — investigate
   at implementation time.

8. **Phase 3 sibling — CA T2 (corporate)** is named in note 104 Phase 3
   alongside DE/FR/JP but is not in this research-before scope. Should
   it be added to the ADR list as ADR-106, or deferred to the next
   research-before? Recommendation: add as ADR-106 (CA T2 is the
   simplest of the four — flat federal 15 % + provincial flat rates,
   identical shape to US 1120). Keeps the Phase 3 batch coherent.

---

## §8. References

**kontor substrate cited (file:line)**:

- `src/kontor/entity.clj:27-122` — entity resolution and hierarchy.
- `src/kontor/schema.clj:3309-3420` — `:entity/*` attrs.
- `src/kontor/schema.clj:60-99` — `:commodity/*` attrs (Common Stock-as-
  commodity reasoning).
- `src/kontor/consolidation.clj:1-50` — translate + eliminate (multi-
  entity machinery).
- `src/kontor/side_effect/cross.clj:1-100` — cross-DB saga.
- `src/kontor/book.clj:1-291` — verb facade.
- `src/kontor/sole_proprietor.clj:1-55` — Phase 2 pattern, the
  composition posture this builds on.
- `src/kontor/corporate_income_tax.clj:38-89` — the CIT substrate.
- `src/kontor/period_tax_provider.clj:67-100` — `TaxReturnFacts`.
- `src/kontor/personal_income_tax.clj:1-90` — adjustment layer the CIT
  surtax stack will mirror.
- `src/kontor/tax_schedule.clj:1-90` — the schedule algebra.
- `modules/asset/src/kontor/asset/asset.clj:249-314` — `dispose!`
  lifecycle.
- `modules/asset/src/kontor/asset/posting.clj:182-242` — `plan-disposal`
  GL.
- `modules/asset/src/kontor/asset/schema.clj:200-260` — `:asset-event`
  (the `:partial-disposal` RESERVED tag).
- `modules/l10n-us/src/kontor/l10n_us/period_tax_provider.clj:40-49` —
  the shipped US 1120 provider (the reference shape).
- `modules/l10n-ca/src/kontor/l10n_ca/period_tax_provider.clj:36-90` —
  the multi-component fan-out shape DE/JP will mirror.
- `doc/decisions.md` ADR-031 (entity), ADR-053 (asset register),
  ADR-068 (`*-tx-data` builder), ADR-072 (FX), ADR-074 (cross-tx),
  ADR-095 (book verb facade), ADR-098 (`:commitment` — the companion
  precedent), ADR-099 (`PeriodTaxProvider`).
- `doc/research/104-tax-completion-individual-to-corporation.md` — the
  Phase 3 mandate.
- `doc/research/105-the-algebra-of-a-tax.md` — the adjustment-layer
  algebra DE Soli composes through.

**External references (license-clean reading)**:

- Apache OFBiz `AcctgTrans` + owner-equity setup —
  `cwiki.apache.org/confluence/display/OFBENDUSER/Quick+Start+-+Basic+Accounting+Setup`,
  and the OFBiz user-manual Accounting Component — pattern only, no
  code lift.
- KillBill `Account` / `Subscription` separation pattern — for the
  partner-owns-entity shape; no code lift.
- IRS Rev. Rul. 03-51 / Section 351 carryover-basis mechanics —
  `irs.gov/pub/irs-drop/rr-03-51.pdf` and the Cordasco "Deep Dive into
  Section 351 Tax-Free Transfers" 2026-04 article.
- pwc Tax Summaries for DE / FR / JP corporate income — confirmed §4's
  rates and structures.
- jetro.go.jp Section 3.3 "Overview of Corporate Income Taxes" — JP
  inhabitants' per-capita levy mechanics (the §4.3 stress).
- Capterra simple-accounting-software 1700-review analysis + Patriot
  Software's double-taxation explainer + Bench's C-corp guide — the §5
  pain-point sanity check.

---

End of note 107.

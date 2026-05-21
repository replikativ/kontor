---
date: 2026-05-21
title: 102 — PeriodTaxProvider — a sibling protocol for period/entity-incident taxes
status: design note (no code changed) — intended to become an ADR once approved
audience: maintainer + implementer of period-tax (income / capital-gains / property / wealth) support
---

# 102 — `PeriodTaxProvider`: a sibling protocol for period taxes

ADR-071's `TaxRateProvider` (notes 100 / 101) handles **transaction-incident**
taxes — VAT, sales tax, withholding — one `TaxFacts` per invoice line. It is
structurally incapable of handling **period/entity-incident** taxes — personal
and corporate income tax, capital gains, property / asset / wealth taxes,
employer payroll tax — and it should not be stretched to. Those attach to an
**entity over a period** and are computed from an **aggregate** (a marginalized
total — income, deductions, credits, net worth), through a **schedule**
(progressive brackets, not a flat per-line rate).

This note designs `PeriodTaxProvider`, the sibling. It follows ADR-071 / note
101's governing principle — *closed at the vocabulary, open at the
implementation* — and generalizes the one period-tax prototype kontor already
has: the CA T1 / T2125 / S3 income-tax computation in `modules/l10n-ca`. It
changes no code; it is meant to become an ADR once the maintainer approves the
shape.

## 0. Two tax-incidence shapes, one general form

A tax — any tax — is the composition

```
(scope, base-selector, schedule)  →  liability  →  posting
```

- **scope** — the unit of incidence the tax attaches to.
- **base-selector** — the function `scope → base` that extracts the taxable
  quantity.
- **schedule** — the function `base → liability` (a rate, a bracket ladder).
- **posting** — the GL materialization of the liability.

The two tax families differ *only* in what fills the three slots:

| slot | transaction tax (ADR-071) | period tax (this note) |
|---|---|---|
| **scope** | a transaction *line* | an *entity × period* |
| **base-selector** | the line base (`:base` — a number already on the line) | a `marginalize` (σ_E) over every posting in the period — *or* a balance-sheet snapshot |
| **schedule** | a flat rate (`:tax/amount` × base) | progressive brackets / flat / capped / formula |
| **liability** | per-component `:amount` in a `TaxFacts` | per-component `:liability` in a `TaxReturnFacts` |
| **posting** | `TaxPostingBuilder` — additive tax leg on the *same* transaction | `TaxReturnPostingBuilder` — a *separate* provision/accrual + payment transaction |

So the abstraction is the *same shape*; the contents are categorically
different. That is exactly why a period tax is **not** `TaxRateProvider`
stretched but a **sibling**:

1. **Cardinality.** `rate-facts` is `line → TaxFacts | nil`. A period tax has
   no line; its scope is `{:entity :period}`. Forcing it through `rate-facts`
   means inventing a fake line and a fake `:base`.
2. **The base-selector is machinery, not a scalar.** A transaction tax reads
   `:base` straight off the line. A period tax must *compute* its base by
   aggregating — and that machinery already exists: `kontor.report/marginalize`
   (ADR-096). The period provider's base-selector *is* a `marginalize` call.
3. **The schedule is not a rate.** `StaticTableProvider`'s `component-amount`
   does `base × rate` or a `:fixed` amount. A period tax needs progressive
   brackets — `apply-brackets` in `modules/l10n-ca/.../t1.clj:79` is the
   prototype. A flat rate is the *degenerate* schedule.
4. **The posting is a different transaction.** A transaction tax leg rides the
   invoice it taxes (it nets to zero against the base legs — `Ker σ`). A period
   tax produces its *own* balanced transaction — a provision now, a payment
   later — and is a `kontor-commitment`-shaped obligation in between.

The two providers are therefore **siblings under one general form**, not
parent/child. They share the `(scope, base-selector, schedule)` vocabulary and
the closed-enum / open-implementation discipline; they share *no protocol
operation*.

## 1. The `PeriodTaxProvider` protocol

```clojure
(ns kontor.period-tax-provider
  "PeriodTaxProvider — the period-tax sibling of ADR-071's
   TaxRateProvider. Given an entity × period, compute the period's
   tax return as pure data (a TaxReturnFacts). Determination only —
   no chart of accounts; TaxReturnPostingBuilder materializes the GL.")

(defprotocol PeriodTaxProvider
  "Resolve the period/entity-incident tax owed by one entity for one
   period — income tax, capital gains, property/wealth tax, employer
   payroll tax."
  (provider-id [this]
    "A keyword identifying the implementation — :ca-t1, :ca-t2,
     :de-est, :us-1120, :static-schedule, :chained — used in
     :provenance and logs.")
  (period-tax-facts [this context]
    "Given an entity × period `context`, return a TaxReturnFacts
     (or nil when the entity owes no period tax of this kind).

     Context keys:
       :entity       — the entity ref the tax is assessed on (ADR-031)
       :period       — the assessment period: {:from #inst :to #inst}
                       OR a :period entity ref (ADR-014). Half-open.
       :db           — the db snapshot the base is marginalized over
                       (the bitemporal substrate; defaults to now/now)
       :as-of-tx     — optional tx-time axis override
       :as-of-valid  — optional valid-time axis override
       :inputs       — optional out-of-books facts the provider needs
                       that are NOT derivable from postings (a prior-
                       year loss carryforward, a presumptive-income
                       base, a property assessed value — see §7)

     Never returns a partially-built TaxReturnFacts: either a complete
     one or nil. Determination is pure: it reads the db, never writes."))
```

`period-tax-facts` is the irregular half — its body is unconstrained, a
jurisdiction's T1 / Einkommensteuer / 1120 logic does whatever it must. Only
its *output type* is fixed: a `TaxReturnFacts` over the closed period-tax-kind
enum. The `TaxReturnPostingBuilder` (§4) is the regular half.

## 2. `TaxReturnFacts` — the inter-protocol data contract

Mirrors `TaxFacts` (`tax_rate_provider.clj:74`): a record carrying a vector of
**components**, one per distinct period tax that applies. The fields differ
because the incidence differs.

```clojure
(defrecord TaxReturnFacts
  [entity          ; the assessed entity ref
   period          ; {:from #inst :to #inst} — the assessment window
   jurisdiction    ; {:country <cc> :subdivision <s|nil>
                   ;  :authority <kw|nil>} — who assesses
   functional-commodity  ; the commodity liabilities are denominated in
   components])    ; vector of component maps — one per period tax

;; each component map:
{:kind          ;; CLOSED enum — see §2.1
 :base          ;; the resolved taxable base (Money) — output of the
                ;;   base-selector (§3b); the σ_E aggregate
 :schedule      ;; the rate-schedule data that produced the liability
                ;;   (§3a) — kept for provenance/audit, not re-run
 :gross-liability  ;; base run through the schedule, before credits (Money)
 :credits       ;; vector of {:code :label :amount} — non-refundable +
                ;;   refundable credits applied (BPA, CPP credit, DTC…)
 :liability     ;; the RESOLVED net tax owed (Money) — gross − credits,
                ;;   floored at zero for non-refundable credits.
                ;;   The single number the posting builder provisions.
 :prepaid       ;; tax already remitted in-period against this liability
                ;;   (T4 box 22 withholding, instalments) — Money. The
                ;;   provision/payment split (§4) nets against this.
 :provenance    ;; {:provider-id <kw> :statute <str|nil>
                ;;  :computed-at #inst :form <str|nil> :inputs-hash <…>}
 :line-items    ;; the return's line detail — an ordered vector of
                ;;   {:line <code> :label <str> :value <Money>}. The
                ;;   kernel never interprets it; it is the audit /
                ;;   form-render payload (the CA :t1/lines map, §6).
 :jurisdiction-specific-codes}  ;; opaque map, kernel never reads —
                ;;   e.g. {:ca/form "T1" :ca/province :BC}. Mirrors
                ;;   TaxFacts' slot of the same name.
```

`line-items` is the period-tax analogue of nothing in `TaxFacts` — a
transaction tax has no "return". It is the structured form-detail (CA's
`:t1/lines`, `t1.clj:311-336`) that the posting builder ignores and the
form-renderer / audit-doc consumes. Like `:jurisdiction-specific-codes` it is a
pressure-release valve: irregular data, regular envelope.

Helpers, mirroring `tax_rate_provider.clj`:

```clojure
(defn assessed?      [facts])   ;; ≥1 component with non-zero :liability
(defn total-liability [facts])  ;; Σ :liability over components
(defn total-prepaid   [facts])  ;; Σ :prepaid
(defn balance         [facts])  ;; total-liability − total-prepaid
                                ;;   >0 = owe; <0 = refund (CA :t1/balance)
(defn valid-return-facts? [facts])  ;; validation layer 1 — §5
```

### 2.1 The closed period-tax `:kind` enum

The bet, exactly as in note 101: a small bounded set of *mechanisms* spans the
world's period taxes, because each is a fixed posting shape (a θ-fragment). The
proposed enum:

```clojure
(def period-tax-kinds
  #{:personal-income-tax      ;; CA T1, DE Einkommensteuer, US 1040
    :corporate-income-tax     ;; CA T2, DE Körperschaftsteuer, US 1120
    :capital-gains-tax        ;; CA S3, US Sch-D — see §7, the hybrid
    :property-tax             ;; real-property / land tax — base is an
                              ;;   assessed value, often out-of-books
    :wealth-tax               ;; net-worth / fortune tax (CH, ES, NO) —
                              ;;   base is a BALANCE-SHEET SNAPSHOT (§3b)
    :payroll-tax-employer     ;; employer SI / payroll levy — the
                              ;;   employer-side period contribution
    :minimum-tax              ;; AMT / corporate minimum tax — a
                              ;;   schedule COMPOSITION (§7)
    :branch-or-presumptive-tax}) ;; presumptive / imputed-income regimes
                              ;;   whose base is NOT in the books (§7)
```

What CA's prototype reveals and the enum must cover:
- **`:personal-income-tax`** — `t1.clj/compute`: brackets + BPA phase-out +
  NRTCs. The federal+provincial split (BC428) is *two components* of one
  return, not two returns — `t1.clj:287-296`.
- **`:capital-gains-tax`** — `s3.clj`: a 50 % inclusion rate then taxed *as*
  income. In CA it is folded into `:personal-income-tax` (line 12700 → 15000).
  In jurisdictions with a separate CGT schedule (UK, partial US) it is its own
  component. The enum carries it so both modellings are expressible — see §7,
  it is the most-stressed kind.
- **`:corporate-income-tax`** — not in the CA prototype's slice 1 but t2125
  (self-employment) is the structural rehearsal; CIT is the same
  base-minus-deductions-through-a-schedule shape on an entity rather than a
  person.
- **`:payroll-tax-employer`** — `modules/payroll-de-datev` computes employer SI
  (`compute.clj:179` — Dr 6110 Soziale Aufwendungen Cr 3740 Verb. SV); CA PD7A
  (`pd7a.clj`) totals the employer-side CRA buckets per remittance period.
  These ARE period taxes (entity × period × aggregate-of-wages × rate) — today
  they are computed inside payroll modules. The enum lets a `PeriodTaxProvider`
  express them uniformly without disturbing the payroll providers (ADR-075).

`:minimum-tax` and `:branch-or-presumptive-tax` are in the enum because §7
shows the `(scope, base-selector, schedule)` form is *stressed* there — naming
them as kinds makes the stress visible and ADR-trackable rather than hidden in
a bespoke provider. A genuine 9th mechanism is an ADR-gated enum extension,
never a quiet flag.

## 3. The `:schedule` primitive

Generalize CA's `apply-brackets` (`t1.clj:79-109`) — currently a bespoke
progressive-bracket fold — into a small **data-driven rate schedule**, a pure
`(schedule, base) → liability` function. Four schedule shapes span every
period tax surveyed:

```clojure
;; A schedule is plain data, tagged by :schedule/type.

;; 1. flat — the degenerate case; ALSO what a transaction tax's
;;    "rate × base" is. Unifies the two families at the schedule layer.
{:schedule/type :flat   :rate 0.21M}

;; 2. progressive-bracket — CA federal/provincial income tax. The
;;    existing apply-brackets, lifted verbatim. Each bracket is
;;    {:rate :upper}; the last :upper is nil (open top).
{:schedule/type :progressive-bracket
 :brackets [{:rate 0.15M :upper 55867M}
            {:rate 0.205M :upper 111733M}
            {:rate 0.33M :upper nil}]}

;; 3. capped — a flat rate up to a ceiling base, zero above (employer
;;    SI on a contribution ceiling — CPP YMPE, DE Beitragsbemessungs-
;;    grenze). Optionally a :floor (CPP basic exemption $3,500).
{:schedule/type :capped :rate 0.0595M :floor 3500M :ceiling 68500M}

;; 4. formula — an escape hatch: a named, pure fn the kernel calls.
;;    For schedules that are genuinely not tabular (a tapered phase-out,
;;    a notch). The fn is supplied by the l10n module, not the kernel.
{:schedule/type :formula :fn  (fn [base ctx] ...Money...)}
```

```clojure
(defn apply-schedule
  "Run a base (Money) through a :schedule, returning gross liability
   (Money). :flat / :progressive-bracket / :capped / :formula.
   apply-brackets (CA) becomes the :progressive-bracket arm verbatim."
  [schedule base] ...)
```

This is a **kernel** primitive (`kontor.tax-schedule` or co-located in
`kontor.period-tax-provider`) — it is regular, jurisdiction-neutral, and
property-testable (monotonic in base; `:progressive-bracket` ⊇ `:flat` when one
bracket). CA's `apply-brackets`, `federal-bpa` phase-out, and `cpp-employee-split`
ratios stay in the l10n module as *application*; only the bracket *engine* is
hoisted. The schedule is stored on the component as `:schedule` for provenance
— what ladder produced this number, never re-run blindly (ADR-015 immutability:
a filed year's schedule is frozen).

## 3b. The base-selector — `marginalize` IS the period tax's `:base`

The transaction provider reads `:base` off the line. The period provider must
**compute** it — and `kontor.report/marginalize` (ADR-096, `report.clj:227`) is
exactly the σ_E that does it. The base-selector is a thin wrapper:

```clojure
;; "total taxable income" for the period = a marginalization over
;; account-type, summed across the income classes, net of deductions.
(defn taxable-income-base [conn {:keys [entity period] :as ctx}]
  (let [postings (report/report-postings
                  conn {:from (:from period) :to (:to period)
                        :entity entity})            ;; ADR-031 filter
        by-type  (report/marginalize postings :account-type
                                     {:sign :inflow})]
    (money/sub (get-in by-type [:income :value] (money/zero …))
               (get-in by-type [:expense :value] (money/zero …)))))
```

`report-postings` (`report.clj:348`) is already exposed *precisely so a
consumer can marginalize without a full report definition* — its docstring says
so. The period provider is that consumer. A richer base — a CA T1 — is a
sequence of marginalizations plus out-of-books `:inputs` (RRSP room, donation
slips); but the *flow* base is a `marginalize` call, no new machinery.

**The wealth-tax exception — base is a stock, not a flow.** A wealth /
net-worth tax's base is **net worth at an instant** — assets − liabilities on
the balance sheet at `period`'s close — *not* a flow accumulated over the
period. `marginalize` as used above sums postings *within a window* (a flow).
The fix is to select a **balance-sheet snapshot** instead:

```clojure
;; net-worth base = balance-sheet at the period's close instant.
;; NOT a windowed marginalize — a point-in-time account-balance roll-up.
(defn net-worth-base [conn {:keys [entity period]}]
  (let [as-of (:to period)
        ;; all postings up to as-of (cumulative), marginalized by type
        postings (report/report-postings
                  conn {:to as-of :entity entity})  ;; :from nil = since inception
        by-type  (report/marginalize postings :account-type {:sign :raw})]
    (money/sub (asset-total by-type) (liability-total by-type))))
```

The distinction is **`:from`**: a flow base passes `{:from … :to …}`; a stock
base passes `{:from nil :to as-of}` — `marginalize` over *all* history up to the
instant. This is the same `marginalize`, used as a cumulative roll-up rather
than a windowed sum — the period provider's base-selector chooses. The
`TaxReturnFacts` component's `:base` is `Money` either way; the provenance
records whether it was a flow or a snapshot. (`:branch-or-presumptive-tax`
bases are neither — §7.)

## 4. `TaxReturnPostingBuilder` — provision, then payment

```clojure
(defprotocol TaxReturnPostingBuilder
  "Materialize the GL transactions for a TaxReturnFacts. Chart-of-
   accounts-aware (income-tax-expense account, tax-payable account);
   it never determines liability."
  (builder-id [this])
  (provision-tx-data [this return-facts opts]
    "The accrual: recognise the period tax as an EXPENSE + a PAYABLE.
     Returns a kontor.book entry-tx-data shape (one balanced
     transaction). Run at period close.")
  (payment-tx-data [this return-facts payment opts]
    "The settlement: a later cash payment liquidating the payable
     (in whole or part). Returns an entry-tx-data shape."))
```

Unlike `TaxPostingBuilder` (which returns *legs* to splice into someone else's
transaction), this builder returns **whole balanced transactions** via the verb
facade (`kontor.book/entry-tx-data`, ADR-095) — because a period tax *is* its
own transaction, not a rider on an invoice.

**Provision** (period close) — `:adjust` / `:general` journal:

```
Dr  Income-tax expense        liability        ;; P&L
  Cr  Income-tax payable        liability       ;; balance sheet liability
```

If `:prepaid` > 0 (withholding already remitted), the provision nets it: the
payable carried is `liability − prepaid`, and the prepaid sits as an asset
already booked by the withholding transactions. `balance` (§2) is exactly the
residual the payable must reflect — positive ⇒ a payable, negative ⇒ a tax
receivable (refund due).

**Payment** (later) — `:cash` journal:

```
Dr  Income-tax payable        amount
  Cr  Bank                      amount
```

### 4b. A tax provision IS a commitment

A recognised-but-unpaid period tax is precisely a `kontor-commitment` (ADR-098,
`modules/commitment`): an obligation recorded when it arises, liquidated as
settling transactions fulfil it. The integration is *optional and clean*:

- `provision-tx-data` posts the GL accrual; the consumer *also* calls
  `commitment/record-commitment!` with `:kind :payable`, `:committed-amount`
  the liability, `:counterparty` the tax authority partner, `:due-date` the
  statutory filing/payment deadline, `:origin` the `TaxReturnFacts`.
- each `payment-tx-data` transaction is linked via `commitment/fulfill!` — the
  settling `:transaction` fulfils part of the commitment.
- `commitment/aging` then surfaces *overdue tax obligations* for free —
  exactly what a tax-payable schedule needs.

The period-tax design does **not** require `kontor-commitment` (kernel single
dep, companion-shaped — ADR-098); it *composes* with it. A `PeriodTaxProvider`
emits `TaxReturnFacts`; whether the consumer also opens a `:commitment` is the
consumer's call. This mirrors how the GL records *what moved* and a commitment
records *what is supposed to* (`commitment.clj:6-9`).

## 5. Validation — the five layers, for the period provider

Mirroring note 101 §5b. Two failure modes: **A** — the interface does not
generalize (a jurisdiction cannot express its period tax as a
`TaxReturnFacts`); **B** — a jurisdiction's computation is wrong (well-formed
facts, wrong numbers). Five layers, cheapest first:

1. **Structural — closed-vocabulary check.** `valid-return-facts?`: every
   component's `:kind` ∈ `period-tax-kinds`, `:base` / `:liability` /
   `:gross-liability` are `Money`, `liability = max(0, gross − Σcredits)` for
   non-refundable credits. An unknown `:kind` is the signal of failure mode A —
   ADR-gated enum extension, never a special-case.
2. **The kernel invariant — free.** The provision/payment transactions go
   through `kontor.book` → `post-transaction!` → the sum-to-zero gate
   (`Ker σ`). A structurally-wrong provision (expense ≠ payable) *cannot be
   posted*. Catches mis-sized legs; does **not** catch wrong-but-balanced (a
   33 % bracket applied as 30 %).
3. **Golden fixtures, per jurisdiction.** The only layer that catches mode B.
   The CA module *already has this discipline implicitly* — `t1.clj`'s tests
   run known T1 inputs to exact `:t1/lines`. Formalize: a known
   entity × period × posting-set → exact expected `TaxReturnFacts`, sourced
   from cited CRA / BMF / IRS worked examples.
4. **Property tests on the regular layers.** `apply-schedule` is monotonic in
   base; `:progressive-bracket` with one bracket ≡ `:flat`; `balance =
   total-liability − total-prepaid`; `total-liability = Σ component :liability`.
   The schedule algebra and the `TaxReturnFacts` helpers are regular — testable
   generatively. The irregular `period-tax-facts` bodies are not.
5. **Differential validation.** The CA `PeriodTaxProvider` (Shape B, wrapping
   `t1.clj/compute`) must produce a `TaxReturnFacts` whose `:line-items` are
   byte-identical to the existing `:t1/lines` map on the existing CA test
   fixtures. The existing `compute` is the oracle — no fresh ground truth.

Plus the audit layer: `:provenance` (`:computed-at`, `:statute`, `:form`,
`:inputs-hash`) per component + the `:line-items` form detail make "why this
tax number" always answerable — the period-tax analogue of the deferred
`:tax-fact/*` snapshot. An `:audit-doc` of category `:tax-filing` (the schema
already has the two-axis `:audit-doc/category`, `schema.clj:3893`) records the
filed return; PD7A's `pd7a-audit-doc-tx-data` (`pd7a.clj:248`) is the precedent
pattern.

## 6. The CA prototype, mapped onto the design

`modules/l10n-ca/.../t1.clj` is the proof the form generalizes. A
`kontor.l10n-ca.period-tax-provider` (Shape B, wrapping `compute`):

| CA prototype | `PeriodTaxProvider` design |
|---|---|
| `t1.clj/compute` arg map (`:t4s`, `:rrsp-deduction`, …) | `context` `:inputs` — the out-of-books facts (§7) |
| `apply-brackets` + `k/federal-brackets` | `apply-schedule` `:progressive-bracket` |
| federal tax (line 40400) + BC428 tax | **two components** — `:kind :personal-income-tax`, one per `:jurisdiction` (CRA / Revenu BC) — mirrors CA's *four-authority* `TaxFacts` from note 100 |
| `s3.clj` taxable capital gains (line 12700) | folded into the income component's `:base`; `s3` detail in `:line-items` (CA modelling — see §7) |
| `cpp-employee-split` / S8 enhanced CPP | a `:payroll-tax-employer`-adjacent computation; the *credits* land in the component `:credits` vector |
| `:t1/lines` map (`t1.clj:311`) | `:line-items` vector — same data, ordered |
| `:t1/balance` / `:t1/outcome` | `balance` helper / `:refund`-vs-`:payment` is `(pos? (balance facts))` |
| `:t1/income-tax-paid` (Σ T4 box 22) | component `:prepaid` |

The port is a *wrapper*, not a rewrite — exactly note 100's Shape B for the
transaction modules. CA's `compute` already *is* a period-tax provider in all
but return type.

## 7. Where the `(scope, base-selector, schedule)` form is STRESSED

A frank assessment. The form is clean for income tax; three places stress it.

**1. Capital gains — per-disposal incidence, annual assessment (a hybrid).**
A capital gain *arises* per disposal (a transaction-incident event — it has a
date, a proceeds, an ACB), but is *taxed* per year on the *aggregate* of the
year's disposals (`s3.clj`: sum gains, sum losses, net, × inclusion rate). Its
scope is therefore **neither** a clean line **nor** a clean entity×period — it
is a *set of per-disposal events, marginalized into a period*. The
`base-selector` for `:capital-gains-tax` is genuinely two-stage: (a) per-disposal
gain = proceeds − ACB − costs (a transaction-shaped computation, and ACB
tracking is itself out-of-books state — `s3.clj` explicitly defers it,
"assumed inputs are post-ACB"); (b) period aggregate with inclusion rate +
loss netting + loss *carryforward* (an inter-period `:inputs` carry). This is
the strongest stress: capital gains sits *across* the two families. The design
accommodates it by making `:base` the resolved aggregate and parking the
per-disposal detail in `:line-items` + the carryforward in `:inputs` — but the
form does not *naturally* express "an event taxed later"; it is bolted on.
Flag honestly: capital gains may eventually want its own per-disposal sub-entity
(a `:disposal/*`), with the `PeriodTaxProvider` marginalizing over it — the
relationship of `:disposal` to `:capital-gains-tax` would then mirror
`:posting` to `marginalize`.

**2. Presumptive / imputed-income taxes — the base is not in the books.**
Presumptive regimes (India's 44AD, Brazil's Lucro Presumido, deemed rental
income, imputed-income wealth proxies) compute tax from a base that is *defined
by statute, not derivable from any posting* — e.g. "8 % of turnover deemed to
be profit", or "2 % of property cadastral value deemed to be income". The
`base-selector` cannot be a `marginalize` over the GL because the taxable
quantity *is not in the GL*. The design handles this via the `context`
`:inputs` slot — the consumer supplies the presumptive base as an explicit
fact — but this is an admission: for `:branch-or-presumptive-tax` the
base-selector degenerates to "read it from `:inputs`", and the σ_E /
`marginalize` story does *not* apply. The provider still produces a clean
`TaxReturnFacts`; the *base-selector half of the general form is hollow*. That
is acceptable (it is honest, audit-able via `:provenance`) but it is a real
limit: the design unifies *posting-derived* period taxes; statutory-fiat bases
ride the `:inputs` escape hatch.

**3. Minimum taxes / AMT — schedule composition, not a schedule.** An AMT
(US T691, corporate minimum tax, the global 15 % Pillar Two top-up) is
`liability = max(regular-tax, minimum-tax)` where *both* arms are themselves a
full `(base-selector, schedule)` computation over *different* bases (AMT
disallows some deductions, so its base-selector is a *different* marginalize).
A minimum tax is therefore not one schedule but a **composition of two whole
tax computations with a `max`**. The `:schedule` primitive (§3) cannot express
it — `:flat`/`:bracket`/`:capped`/`:formula` are all `base → liability`, single
base. The design accommodates it by making `:minimum-tax` its *own component
`:kind`*: the provider runs both computations and emits the minimum-tax
component only when it bites, with `:line-items` showing the comparison. But
this means `:minimum-tax` is a *meta-kind* — a component whose liability is a
function of *other components* — which the flat component-vector shape does not
naturally model (components are otherwise independent, as in `TaxFacts`).
Flag: if minimum taxes proliferate, `TaxReturnFacts` may need an explicit
`:supersedes` / `:composed-of` edge between components. None of the surveyed
jurisdictions force that yet — flag, do not pre-solve (the note-101 posture).

A fourth, milder stress: **employer payroll tax** (`:payroll-tax-employer`)
already has a home in the payroll providers (ADR-075). Modelling it *also* as a
`PeriodTaxProvider` component risks double-counting. The design's position:
`:payroll-tax-employer` in the enum is for jurisdictions that levy a *standalone
payroll levy* assessed on aggregate wages (a payroll tax proper, e.g. AU state
payroll tax) — *not* for SI contributions already computed inside `run-payroll!`.
The boundary is a documentation call, not a structural one; flag it.

## The tax-as-two-sided-transfer note (for a future simulation)

Every tax payment is, economically, a **transfer**: it debits the taxpayer's
liability and credits the *state's* revenue. kontor today models only the
**taxpayer side** — `TaxReturnFacts` is the obligation of *one* entity; the
posting builder books *its* expense and payable. The collector (the state) is
modelled, if at all, as a `:partner` (the tax authority) — an opaque
counterparty, not a booking entity.

For a future agent-based / fiscal simulation the symmetric structure matters:
the same `(scope, base-selector, schedule)` triple, evaluated from the
*authority's* side, yields the authority's *revenue recognition*. The design
accommodates this without building it:

- `TaxReturnFacts` already carries `:jurisdiction {:authority …}` and the
  authority can be a kontor `:entity` (ADR-031) in a multi-entity book — a
  consolidated model could have the state as a sibling entity.
- The `kontor.side-effect.cross` cross-DB saga primitive (ADR-074) is the
  natural seam: a tax payment is a `:cross-tx` step pair — the taxpayer's
  `payment-tx-data` in DB-A and the authority's revenue posting in DB-B, drained
  atomically.
- A future `CollectorPostingBuilder` would be the mirror of
  `TaxReturnPostingBuilder`: same `TaxReturnFacts`, opposite sign,
  authority-side chart (Dr tax-receivable Cr tax-revenue).

This note **flags** the collector side and shows the seam (`:authority` as
entity + `:cross-tx`); it does **not** build it. The taxpayer-side
`PeriodTaxProvider` is the deliverable; the two-sided model is a simulation
follow-up.

## 8. Gap matrix — the 11 legislations (surveyed 2026-05-21)

Three survey agents covered all 11 kontor jurisdictions. **Coverage key:**
● kontor *computes* the liability · ◐ kontor *records* an engine-computed
figure (never computes) · ○ nothing.

| Jurisdiction | Personal income | Capital gains | Property / asset / wealth | Corporate income | Employer payroll |
|---|---|---|---|---|---|
| **CA** | ● `y2024/t1` — real, but 1 year / 1 province (BC), monolithic | ◐ `s3` nets gains, inputs *assumed post-ACB* | ○ | ○ (`t2125` is sole-prop → T1, not T2) | ◐ recorded; CPP/EI not computed (ADR-075) |
| DE | ○ | ○ | ○ (Grundsteuer) | ○ | ◐ recorded (LODAS) |
| AT | ○ | ○ | ○ (Grundsteuer) | ○ | ◐ recorded (BMD/RZL) |
| FR | ○ | ○ | ○ — incl. **IFI**, a real net-wealth tax | ○ | ◐ recorded (Silae/Sage) |
| US | ○ | ○ | ○ | ○ | ◐ recorded (ADP) |
| AU | ○ | ○ | ○ (land tax — progressive) | ○ | ◐ recorded (Xero/MYOB) |
| JP | ○ | ○ | ○ (固定資産税) | ○ | ◐ recorded |
| CN | ○ | ○ | ○ (房产税) | ○ | ◐ recorded |
| IN | ○ | ○ | ○ (municipal only) | ○ | ◐ recorded |
| BR | ○ | ○ | ○ (IPTU/ITR/IPVA) | ○ — 4 rate *constants* only (`l10n-br/taxes.clj:161`) | ◐ recorded |
| MX | ○ | ○ | ○ (predial) | ○ | ◐ recorded; **ISN (state payroll tax) absent entirely** — no wage-type, no account |

**Where we stand, bluntly:** kontor today computes **exactly one** period
tax — CA personal income tax (`y2024/t1`), and that is a single-year /
single-province / monolithic prototype. Capital gains exists only as CA's
`s3` aggregator (and it admits its inputs must already be post-ACB).
**Corporate income tax, and the entire property / asset / wealth family, are
uncomputed in every one of the 11 jurisdictions.** Employer payroll
contributions are uniformly *recorded* (the engine computes, kontor parses +
posts + emits the statutory file) — never *computed*; that is ADR-075 policy,
not a substrate limit. The `PeriodTaxProvider` is, for 10 of 11
jurisdictions, entirely greenfield.

## 9. Reconciliation — the design amended against the survey

§7 named three stresses from the CA prototype alone. The 11-legislation
survey confirms all three and adds five more. The amendments below keep the
note-101 discipline — *closed vocabulary, open implementation* — but the
"vocabulary" that must be richer than the §1–§4 v1 is the **schedule** and
the **base path**, not the `:kind` enum.

**A — the `:schedule` is a small algebra, not four shapes.** §3's four
*base* shapes (`:flat :progressive-bracket :capped :formula`) stand, but the
survey forces three **combinators** over them:
- `:max` — `liability = max(sched-a, sched-b)`. IN MAT, US AMT / CAMT, AT
  Mindest-KöSt, OECD Pillar-Two top-up. (Subsumes §7-stress-3 *and* the
  AT minimum-tax floor.)
- `:elect` — `liability = (chosen-of sched-a sched-b)`, the choice an
  annual taxpayer input. FR PFU-vs-barème; the schedule half of regime
  election (see C).
- `:surtax-on` — a rate applied to *another component's liability*, not to
  a marginalized base. DE Solidaritätszuschlag + Kirchensteuer, JP 復興
  特別所得税 + 法人住民税, IN/BR health-and-education cess. **Tax-on-a-tax is
  ubiquitous** — every survey found it. This promotes the §"Deferrals"
  `:composed-of` component edge from *deferred* to *required at v1*.

**B — a `base-transform` slot: `(scope, base-selector, base-transform,
schedule)`.** The v1 has the base-selector hand back the taxable base
directly. The survey shows a distinct stage between the marginalized
aggregate and the schedule: BR Lucro Presumido (taxable base = 8 % / 32 %
× *revenue*); DE Gewerbesteuer Hinzurechnungen (book profit *plus* a
rule-engine of statutory add-backs); CIT add-backs/deductions generally.
The transform is where `regime` lives. (This is distinct from §7-stress-2's
*presumptive* case — there the base is genuinely **not in the books** at all,
e.g. a cadastral value; that still rides `:inputs`. Lucro Presumido's input
*is* in the books — revenue — it is just transformed.)

**C — `regime` is first-class.** The single most pervasive finding: a
jurisdiction's period tax is often a **set** of `(base-transform, schedule)`
pairs with a per-entity-per-period **election** — IN old/new regime, BR
Lucro Real / Presumido / Simples, MX RESICO, CN HNTE / small-low-profit. A
`TaxReturnFacts` component must record `:regime` (which pair was applied);
`period-tax-facts`' `context` must accept the election as an `:inputs` key.

**D — schedule indexed by the tax unit.** FR quotient familial
(`parts × barème(base / parts)`, benefit-capped), DE Ehegattensplitting, US
filing status. The schedule is a *family of functions indexed by a household
descriptor*. Add an optional `:tax-unit` to the `context` (a descriptor, or
an entity-family ref — ADR-031); the `:formula` schedule shape already
admits it, but it must be a named, first-class input, not buried in a fn
closure.

**E — `:base-period` ≠ `:period`.** JP inhabitant tax assesses *this*
year on *last* year's income. `context` needs an optional `:base-period`
distinct from the (liability) `:period`. **Genuinely out of scope:** CN's
*cumulative monthly withholding* is stateful (each month = f(YTD base, YTD
withheld)) — that is a withholding *mechanism*; it stays in the payroll/
engine layer. The `PeriodTaxProvider` models the **annual reconciliation**,
not the monthly running withholding.

**F — scope fan-out is already handled.** US (federal + N states), AU
(8 state payroll regimes), JP (national + prefectural + municipal). The v1
`TaxReturnFacts` is *already* a component vector — CA fed + BC428 are two
components of one return. Fan-out = more components, each with its own
`:jurisdiction`. No amendment; make it explicit in §2.

**G — the framing question: tax, or period-incident obligation?** MX PTU
(10 % statutory profit-share to employees) and BR's CPC-33 accruals fit
`(scope, base-selector, schedule)` *exactly* yet are not taxes. Recommendation:
keep the name `PeriodTaxProvider` and the tax-focused enum for v1 (tax is the
95 % case), but note the abstraction is really *period-incident statutory
obligation* — if profit-shares / mandatory levies proliferate, the enum
admits a `:statutory-profit-share` kind without a redesign. Flag, do not
rename now.

## 10. What to fill right away

A staged build, cheapest-and-highest-value first — mirroring how note 100
sequenced the transaction migration:

1. **The substrate (kernel).** `kontor.tax-schedule` — the schedule algebra
   (4 base shapes + `:max` / `:elect` / `:surtax-on` combinators), lifting
   CA's `apply-brackets`. The `PeriodTaxProvider` / `TaxReturnFacts` /
   `TaxReturnPostingBuilder` protocols. Property-tested. This is the bounded,
   designable part — do it once, well.
2. **Pilot: re-express CA `t1` as a `PeriodTaxProvider`** (Shape B, wrapping
   `compute`) — the §5 layer-5 differential gate proves the protocol against
   the one real prototype. No new tax content; pure validation.
3. **Standalone employer payroll taxes that are currently UNMODELED** —
   **MX ISN**, **AU state payroll tax**, **AT Kommunalsteuer**. Textbook
   `(scope = entity × subdivision, base = marginalized wage sum, schedule =
   flat-or-capped)` — the cleanest possible fits, and ISN is a true gap (not
   even recorded). NB: this is *standalone payroll levies*, not the SI
   contributions — those stay engine-authoritative (ADR-075).
4. **Corporate income tax, flat-rate jurisdictions first** — US 21 %, AU
   25/30 %, CN 25 %, MX 30 %, AT 23 %. Flat schedule (trivial); the work is
   the `base-transform` (book profit → taxable income add-backs). High value:
   every for-profit corporation needs it.
5. **Personal income tax — the clean-bracket validators first** (AT, AU:
   plain progressive brackets), then **DE** (continuous-formula schedule) and
   **FR** (quotient familial) deliberately as the design-stress validators —
   if the schedule algebra survives DE §32a and FR `parts`, it is proven.
   This is also the **personal tax / invoicing-tool wedge** for the user
   base.
6. **Deferred, named:** capital-gains needs a `:disposal/*` sub-entity + lot/
   ACB cost tracking (§7-stress-1) — a real piece of work; property/asset/
   wealth needs an asset register carrying an externally-assessed value.
   Both are greenfield for all 11; build after 1–5 prove the substrate.

## Deferrals (explicit)

- The **collector / state side** — `CollectorPostingBuilder`, authority-as-
  entity, the `:cross-tx` tax-payment saga. Flagged above; a simulation track.
- A **per-disposal `:disposal/*` sub-entity** for capital gains (§7 stress 1) —
  deferred until a jurisdiction with a separate CGT schedule + ACB tracking
  forces it; CA folds CGT into income today.
- The **`:composed-of` / `:supersedes` component edge** for minimum taxes
  (§7 stress 3) — deferred; `:minimum-tax` as a flat component is enough until
  AMT proliferates.
- **`StaticSchedulePeriodTaxProvider`** — the period-tax analogue of
  `StaticTableProvider` (a DB-backed `:period-tax/*` schema + `:tax-schedule/*`
  brackets). Designed-shaped here; whether to ship it or keep every period tax
  Shape-B (bespoke, wrapping the l10n `compute`) is a maintainer call — the CA
  prototype is Shape B and that may be the right default, exactly as note 100
  found Shape B the realistic default for 9/11 transaction modules.
- Per-jurisdiction `PeriodTaxProvider` ports beyond CA — consumer-demand-driven,
  as ADR-071 says for the transaction side.

## Bottom line

A period tax is the *same* `(scope, base-selector, schedule)` form as a
transaction tax with categorically different slot contents — so it is a
**sibling** protocol, `PeriodTaxProvider`, not `TaxRateProvider` stretched.
`period-tax-facts [this {:entity :period :db …}] → TaxReturnFacts | nil`;
`TaxReturnFacts` mirrors `TaxFacts` with a closed 8-value period-tax `:kind`
enum, a resolved `:liability`, `:line-items` form detail, and `:provenance`.
The base-selector *is* `kontor.report/marginalize` (a windowed σ_E for flow
bases, a cumulative roll-up for wealth-tax stock bases). The `:schedule`
primitive generalizes CA's `apply-brackets` to `:flat | :progressive-bracket |
:capped | :formula`. `TaxReturnPostingBuilder` emits whole balanced provision +
payment transactions via the `kontor.book` verb facade, and a tax provision is
a `kontor-commitment`-shaped obligation. The form is stressed in three named
places — **capital gains** (per-disposal incidence, annual assessment — a true
hybrid), **presumptive/imputed taxes** (base not in the books — the σ_E story
goes hollow), and **minimum taxes / AMT** (a composition of two whole tax
computations, not one schedule). All three are flagged, kept in the enum so the
stress is ADR-trackable, and not pre-solved.

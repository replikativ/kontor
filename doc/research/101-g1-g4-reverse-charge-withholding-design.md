---
date: 2026-05-21
title: 101 — Implementing G1 (reverse charge) + G4 (withholding) on the ADR-071 tax substrate
status: design note (no code changed) — intended to become an ADR-071 addendum once approved
audience: maintainer + implementer of the per-l10n tax-provider port
---

# 101 — G1 + G4: reverse charge and withholding on the tax substrate

Research note 100 found five gaps in the ADR-071 tax substrate (note 99
Stage 2). Two are *posting-side* gaps that note 100 flagged "close before the
IN/AT/MX ports": **G1 — buyer-side reverse charge is contract-only** and
**G4 — withholding has no posting-side contract**. This note designs both
exactly, and shows how DE / AT / IN / MX tie in. It changes no code; it is
meant to become an ADR-071 addendum once the maintainer approves the shape.

G2 (compound base), G3 (period-level surcharge), G5 (seed data + multi-line
wrapper) are out of scope — G2/G5 are per-module concerns, G3 is documentation.

## TL;DR

Both gaps are small and additive. The full change-set:

- **One kernel schema attr** — `:tax/mechanism` (`:standard | :reverse-charge
  | :withholding`, default-absent = `:standard`). Lets `StaticTableProvider`
  classify a `:tax` entity; bespoke providers ignore it and set `:kind`
  directly.
- **`TaxPostingBuilder` — two dispatch branches.** `component-postings`
  (`tax_posting_builder.clj:82-99`) becomes a `case` on the component
  `:kind`: a `:reverse-charge` branch, a `:withholding` branch, and the
  current body as the `standard` default.
- **`TaxFacts` — three helper fns** + a `kind-effect` classification.
  `additive-total` / `withheld-total` / `net-tax-effect` — the **netting
  contract** that lets a consumer size the counterparty cash leg correctly.

No change to the `TaxFacts` record shape, the `:component/kind` enum (it
already has `:reverse-charge` and `:withholding`), or the kernel `:tax-rep` /
`:tax-group` schema. G1's two-legged posting reuses the `:tax-group`'s
already-existing `:payable-account` + `:receivable-account` pair.

## 0. Why a closed interface can be international

The worry behind this work: an international tax interface "needs to
generalize", and tax is notoriously full of jurisdiction-specific special
cases. The resolution is to *not* generalize the thing that cannot be
generalized. Separate two layers:

- **Mechanism** — *how* tax attaches to a transaction, as a posting shape.
  Output VAT (add-on collected), input VAT (add-on reclaimed),
  non-recoverable tax (add-on that becomes cost), reverse charge (liability
  flips, both-legs), withholding (contra deduction), cascade (tax-on-tax
  base), pre-collection (collected upstream), per-unit duty. About 8-10 of
  them — the `:component/kind` enum. This set is **small, bounded, and
  genuinely international**, because it is about double-entry bookkeeping
  structure, which is the same everywhere. Each mechanism is a fixed
  θ-fragment (note 97): a fixed pattern of postings.

- **Application** — *which* mechanism fires, at *what* rate, for *whom*,
  *when*. Rate tables, place-of-supply, B2B/B2C, exemptions, nexus,
  thresholds, effective dates. This is **irregular and jurisdiction-
  specific**, and no interface should try to generalize it.

The interface therefore generalizes the **vocabulary**, not the **logic**.
`TaxRateProvider/rate-facts` is the irregular half — its body is
unconstrained, a country may do anything; only its *output type* is fixed:
a `TaxFacts` whose components are drawn from the closed mechanism enum.
`TaxPostingBuilder` is the regular half — it consumes mechanisms, and
because mechanisms are regular it generalizes. The principle: **closed at
the vocabulary, open at the implementation.**

Two pressure-release valves keep the closed vocabulary survivable:
`:jurisdiction-specific-codes` (an opaque map the kernel never interprets —
irregular data, regular envelope) and the bespoke-provider / -builder
escape (the protocols are contracts, not a framework; `StaticTable*` are
conveniences, not mandates). And the enum is a **falsifiable bet** — the
claim that ~10 mechanisms span the world's posting shapes. Migrating all 11
l10n modules is the experiment. A country that needs an 11th mechanism
falsifies the bet visibly, and the response is a deliberate ADR-gated enum
extension — never a quiet per-country flag on the interface.

## 1. What the substrate does today, and where G1/G4 bite

`StaticTablePostingBuilder` materializes a `TaxFacts` component into
`:posting/*` maps by walking the backing `:tax` entity's `:tax-rep`
repartition lines and signing the amount by `:tax-use`:

```
;; tax_posting_builder.clj — today
sign-for           (:59-65)  :sale → -1M, :purchase → +1M, else 0M
component-postings (:82-99)  every component → rep-line walk × sign-for
```

This is correct for ordinary VAT and sales tax — output VAT on a sale is a
credit (`-`), input VAT on a purchase is a debit (`+`). It is **wrong, or
absent, for two `:kind` values**:

- **`:reverse-charge`** — `component-postings` has no branch for it. A
  reverse-charge component would be walked as if it were ordinary VAT: a
  buyer-side (`:purchase`) reverse charge would emit *one* debit posting, not
  the two-legged input+output pattern ADR-071 P1-71-2 specifies; a
  seller-side one would emit a posting where there should be none. Every
  l10n module dodges this — AT (`invoice.clj:96-99`,
  `vat-class->ust-code` maps `:reverse-charge → nil`) and IN
  (`invoice.clj:224-229`, `:status :reverse-charge` → all tax components
  `0M`) handle it as **seller-side suppression only**. The buyer-side
  both-legs pattern is implemented nowhere.

- **`:withholding`** — also no branch. `sign-for` would post a purchase
  withholding as a `+` debit. That is the wrong direction: a withholding the
  buyer performs is a **liability** (a credit); one the seller suffers is a
  **prepaid receivable** (a debit) — see §3. And withholding does not *add*
  to the gross the way VAT does; it *subtracts* from the counterparty cash
  leg. There is no contract telling a consumer that.

## 2. G1 — reverse charge

### 2.1 The accounting

In a reverse-charge supply (EU intra-community B2B, DE §13b
Bauleistungen, IN import-of-services RCM) the **seller invoices net** — no
VAT — and the **buyer self-accounts** for the VAT.

- **Seller side (`:tax-use :sale`).** No VAT moves on the seller's books.
  The invoice shows net plus a legend ("Steuerschuldnerschaft des
  Leistungsempfängers" / "reverse charge"). The only artifact is a
  **reporting marker** for the seller's VAT return — and that is a *tag on
  the base/revenue posting*, not a tax leg.

- **Buyer side (`:tax-use :purchase`).** The buyer records **both** halves
  of the VAT it self-accounts:
  - **Dr input-VAT receivable** — the VAT it may reclaim (an asset).
  - **Cr output-VAT payable** — the VAT it owes as if it were the seller (a
    liability).

  For a fully-deductible buyer the two are equal and opposite, so there is
  *no cash effect* — but both must appear, because the VAT return reports an
  output box and an input box independently.

### 2.2 Producing the `:reverse-charge` component (rate side)

Two paths, matching note 100's Shape S / Shape B:

- **Shape B (bespoke provider — AT, IN, …).** The per-country
  `TaxRateProvider` constructs the `TaxFacts` itself. When its source data
  says "reverse charge" (AT's `:vat-class :reverse-charge`, IN's
  reverse-charge line status) it simply emits a component with
  `:kind :reverse-charge`. **No schema, no substrate change** — the bespoke
  provider already owns the `:kind`.

- **Shape S (`StaticTableProvider` — DE).** `StaticTableProvider`'s private
  `component-kind` maps `(:tax/recoverable?, :tax-use)` to a `:kind`. It has
  no way to know a `:tax` row is reverse-charge. **This is the only place a
  schema attr is needed:**

  ```clojure
  ;; new kernel attr — additive, default-absent = :standard
  {:db/ident       :tax/mechanism
   :db/valueType   :db.type/keyword
   :db/cardinality :db.cardinality/one
   :db/doc ":standard | :reverse-charge | :withholding — the tax-collection
            mechanism. StaticTableProvider maps it to the TaxFacts
            component :kind. A bespoke per-country provider sets :kind
            directly and ignores this."}
  ```

  `component-kind` then checks it first:

  ```clojure
  (defn- component-kind [tax tax-use]
    (case (:tax/mechanism tax)
      :reverse-charge :reverse-charge
      :withholding    :withholding
      ;; :standard / absent — the existing logic
      (cond (not (:tax/recoverable? tax)) :sales-tax
            (= tax-use :sale)             :output-vat
            (= tax-use :purchase)         :input-vat
            :else                         :sales-tax)))
  ```

### 2.3 Materializing it (posting side)

`component-postings` becomes a dispatch on `:kind`:

```clojure
(defn- component-postings [db component commodity tax-use document-type]
  (case (:kind component)
    :reverse-charge (reverse-charge-postings db component commodity tax-use)
    :withholding    (withholding-postings  db component commodity tax-use document-type)
    ;; :output-vat :input-vat :sales-tax :cess :duty :fee :surcharge
    ;; :pre-collection — all additive, the current behaviour
    (standard-postings db component commodity tax-use document-type)))
```

`standard-postings` is the *current* body of `component-postings` verbatim —
behaviour-identical for every existing case, so the Stage-2 tests and any
ported VAT module are untouched.

`reverse-charge-postings` — the reference both-legs impl. The two account
refs come from the backing `:tax`'s **`:tax-group`**, which already carries
both a `:payable-account` (output VAT lands) and a `:receivable-account`
(input VAT lands) — exactly the pair reverse charge needs. No new schema:

```clojure
(defn- reverse-charge-postings [db component commodity tax-use]
  (if (= tax-use :sale)
    []   ;; seller side: the VAT-return marker is a tag on the base posting,
         ;; applied by the consumer — not a tax leg. The builder emits nothing.
    (let [grp (:tax/tax-group
               (d/pull db '[{:tax/tax-group [{:tax-group/payable-account [:db/id]}
                                             {:tax-group/receivable-account [:db/id]}]}]
                       (:tax-eid component)))
          amt (:amount component)
          base {:posting/commodity commodity :posting/display-type :tax
                :posting/tax-base (:base component)}]
      [(assoc base :posting/account (get-in grp [:tax-group/receivable-account :db/id])
                   :posting/amount amt)        ;; Dr input-VAT receivable
       (assoc base :posting/account (get-in grp [:tax-group/payable-account :db/id])
                   :posting/amount (- amt))])))  ;; Cr output-VAT payable
```

The two legs net to zero by themselves, so a reverse-charge component never
changes the counterparty cash leg (see §3.2's `net-tax-effect`).

**Partial deductibility** (the buyer cannot reclaim 100 % of the input VAT)
is the documented per-country override point — ADR-071 P1-71-2 already says
the dispatch is per-jurisdiction. A country with partial RC deductibility
ships its own `TaxPostingBuilder` whose reverse-charge branch applies the
`:tax-rep/factor-percent` split. `StaticTablePostingBuilder` ships the
full-deductibility reference case.

### 2.4 How the countries tie in

| Country | Shape | Reverse-charge wiring |
|---|---|---|
| **DE** | S | Seed two `:tax` rows with `:tax/mechanism :reverse-charge` — one `:type-tax-use :purchase` (§13b buyer self-account, both legs) and one `:sale` (DE-supplier §13b, marker only). `:tax/tax-group` → a group whose payable/receivable point at the SKR04 §13b accounts (the 4125 / 1577-class accounts DE `invoice.clj`'s docstring names but never wired). `StaticTableProvider` + `StaticTablePostingBuilder` then just work. |
| **AT** | B | AT is sales-only today: its provider emits `:kind :reverse-charge`, the builder returns `[]`, and AT's existing base logic routes revenue to `default-revenue-reverse-charge-code` (`invoice.clj:88`). If AT later issues purchase invoices, the buyer-side both-legs come for free. Replaces the `vat-class->ust-code :reverse-charge → nil` special-case. |
| **IN** | B | IN's `component-split` already returns component maps; a reverse-charge line sets `:kind :reverse-charge` instead of the current all-zero suppression (`invoice.clj:224-229`). Import-of-services RCM (buyer side) then materializes the both-legs pattern automatically. |

## 3. G4 — withholding

### 3.1 The accounting

Withholding tax (MX retenciones, IN/▸payroll TDS, US backup withholding):
one party withholds part of a payment and remits it to the tax authority on
the other party's behalf. It has **two sides**, by `:tax-use`:

- **Buyer side (`:tax-use :purchase`)** — the buyer withholds. The withheld
  amount is a **liability** the buyer owes the authority → **Cr
  withholding-tax payable**. It *reduces what the buyer pays the supplier*.

- **Seller side (`:tax-use :sale`)** — the supplier is withheld-from. This
  is MX's case: the supplier issues a CFDI showing `<Retenciones>`, and
  "the supplier's own books carry the withheld amount as a *receivable*"
  (MX `tax.clj:61-63`) — tax prepaid on their behalf, creditable against
  their own ISR/IVA → **Dr withholding-tax receivable**. It *reduces the
  net cash the supplier receives* — but, crucially, "they do NOT reduce the
  gross invoice IVA the supplier owes SAT" (MX `tax.clj:73-75`).

So the withholding tax posting's sign is **inverted from VAT**:

| `:tax-use` | ordinary VAT (`sign-for`) | withholding |
|---|---|---|
| `:sale` | `-1` (output VAT, credit) | `+1` (receivable, debit) |
| `:purchase` | `+1` (input VAT, debit) | `-1` (payable, credit) |

### 3.2 The netting contract — the genuinely new bit

VAT is **additive**: a sale of 1000 + 19 % VAT means the AR leg is the
*gross* 1190 — `net + tax`. The `TaxPostingBuilder` returns the tax leg
(`Cr VAT 190`); the consumer sizes the AR leg to include it.

Withholding is **subtractive**: it does not add to the gross, it nets the
counterparty cash leg *down*. A consumer that naively does `gross = net +
total-tax` will produce an unbalanced transaction, because
`kontor.tax-rate-provider/total-tax` (today) sums *all* component amounts.

The fix is a contract, expressed as three helpers in `tax_rate_provider.clj`
plus a `kind-effect` classification of the 10-value `:component/kind` enum:

```clojure
(def kind-effect
  "How a component's amount affects the counterparty cash leg."
  {:output-vat :additive :input-vat :additive :sales-tax :additive
   :cess :additive :duty :additive :fee :additive
   :surcharge :additive :pre-collection :additive
   :withholding :withheld
   :reverse-charge :neutral})   ;; both legs net to zero / seller marker only

(defn additive-total  [facts] ...)  ;; Σ :amount where kind-effect = :additive
(defn withheld-total  [facts] ...)  ;; Σ :amount where kind-effect = :withheld
(defn net-tax-effect  [facts]       ;; the signed amount to add to the net
  (- (additive-total facts) (withheld-total facts)))  ;;   to get the cash leg
```

**The contract:** a consumer sizes the counterparty cash leg as
`net + (net-tax-effect facts)`. For pure VAT that equals `net + total-tax`
(unchanged). For a withholding invoice it correctly subtracts the withheld
amount. `total-tax` stays as "gross notional tax" for reporting, with a
docstring note that it is *not* the cash adjustment when withholding or
reverse charge is present.

### 3.3 Producing + materializing

**Rate side.** A bespoke provider (MX) emits a component with
`:kind :withholding` per retención. `StaticTableProvider` emits it when
`:tax/mechanism` is `:withholding` (§2.2) — symmetric with reverse charge,
though no Shape-S country needs it yet (MX is Shape B).

**Posting side.** `withholding-postings` reuses the ordinary `:tax-rep`
rep-line walk — the withholding account *is* a `:tax-rep/account` — and only
swaps the sign function:

```clojure
(defn- withholding-sign [tax-use]
  (case tax-use :sale 1M :purchase -1M 0M))   ;; inverted from sign-for

(defn- withholding-postings [db component commodity tax-use document-type]
  ;; identical rep-line walk to standard-postings, with withholding-sign.
  (postings-from-rep-lines db component commodity tax-use document-type
                           withholding-sign))
```

(`standard-postings` and `withholding-postings` share a
`postings-from-rep-lines` helper parameterized by the sign fn — a small
refactor of the current `component-postings` body.)

The withholding tax leg lands on the withholding-payable (buyer) or
-receivable (seller) account; the consumer's base builder nets the AR/AP leg
via `net-tax-effect`. The two together balance (worked in §5).

### 3.4 How the countries tie in

| Country | Shape | Withholding wiring |
|---|---|---|
| **MX** | B | MX `compute-tax` already computes `retencion-iva` / `retencion-isr` (`tax.clj:254-304`). The bespoke MX provider emits one `:kind :withholding` component per retención, `:tax-use :sale`. The builder posts each as a debit to the IVA-/ISR-retenido **receivable** accounts. MX's invoice builder sizes the AR leg with `net-tax-effect` — which, since retenciones reduce it, is `net + output-VAT − retenciones`. The `<Retenciones>` CFDI block is emitted from the same components. |
| **IN** | B | GST RCM rides on G1 (reverse charge). TDS proper lives in `kontor-payroll-in`, not the l10n tax module — but if `kontor-l10n-in` later needs vendor-payment TDS, `:kind :withholding` + the `:purchase` branch (Cr TDS-payable) is the same machinery. |
| **DE / others** | — | No withholding today. The `:tax/mechanism :withholding` enum value + the builder branch sit ready; nothing to wire. |

## 4. The complete change-set

| # | Change | File | Kind |
|---|---|---|---|
| 1 | `:tax/mechanism` attr | `schema.clj` (`:tax/*` block) | schema, additive |
| 2 | `component-kind` reads `:tax/mechanism` | `tax_rate_provider.clj` | provider |
| 3 | `kind-effect` map + `additive-total` / `withheld-total` / `net-tax-effect` | `tax_rate_provider.clj` | new public fns |
| 4 | `component-postings` → `case` on `:kind`; `standard-postings` = current body | `tax_posting_builder.clj` | builder, behaviour-identical default |
| 5 | `reverse-charge-postings` (`:tax-group` two-account both-legs) | `tax_posting_builder.clj` | new |
| 6 | `withholding-postings` + `postings-from-rep-lines` (sign-parameterized) | `tax_posting_builder.clj` | new |
| 7 | tests — RC seller `[]` / RC buyer both-legs / withholding sign + `net-tax-effect` balance | `tax_rate_provider_test.clj` | test |

No change to: the `TaxFacts` record, the `:component/kind` enum, `:tax-rep`,
`:tax-group`, the `TaxRateProvider` / `TaxPostingBuilder` protocols. Existing
VAT behaviour is byte-identical (the `case` default is the current code).

## 5. Worked examples

**DE §13b reverse-charge purchase** — buyer books, service bought for 1000,
notional VAT 19 % = 190.

```
component {:kind :reverse-charge :amount 190M :base 1000M …}
net-tax-effect = additive(0) − withheld(0) = 0      ;; RC is cash-neutral

base postings (consumer):   Dr Expense          1000
                            Cr Accounts-Payable 1000
tax postings (builder):     Dr Input-VAT         190     ;; :tax-group receivable
                            Cr Output-VAT        190     ;; :tax-group payable
                            ───────────────────────
total                       1190 − 1190 =          0  ✓
```

**MX retención sale** — supplier books, sale 1000 + 16 % IVA, IVA retenido
10.6667 % = 106.67.

```
components [{:kind :output-vat  :amount 160M …}
            {:kind :withholding :amount 106.67M …}]
net-tax-effect = additive(160) − withheld(106.67) = 53.33

base postings (consumer):   Dr Accounts-Receivable  1053.33   ;; net + 53.33
                            Cr Revenue              1000
tax postings (builder):     Cr IVA-trasladado        160      ;; :output-vat, :sale → −
                            Dr IVA-retenido-recv     106.67   ;; :withholding, :sale → +
                            ──────────────────────────────
total                       1160 − 1160 =              0  ✓
```

Both satisfy the kernel's sum-to-zero gate because the consumer sized the
counterparty leg with `net-tax-effect`. That *is* the G4 contract.

## 5b. Validating the interface

Validation must catch two distinct failure modes, at different layers:

- **A — the interface does not generalize:** a country cannot express its
  tax as a `TaxFacts` at all. Detected only by doing the ports.
- **B — a country's implementation is wrong:** the `TaxFacts` is well-formed
  but the numbers or accounts are wrong.

Five layers, cheapest first:

1. **Structural — closed-vocabulary check.** `valid-tax-facts?` — every
   component's `:kind` is in `component-kinds`. An unknown `:kind` is the
   signal of failure mode A; the response is an ADR-gated enum extension,
   not a special-case.
2. **The kernel invariant — free.** θ_tax must land in `Ker σ`: tax legs +
   base legs sum to zero. The kernel validation gate already enforces this
   at transact time, so a structurally-wrong tax computation *cannot be
   posted* — it catches mis-signed legs and unbalanced reverse-charge
   pairs. It does *not* catch wrong-but-balanced (a 19 % rate applied as
   20 %).
3. **Golden fixtures, per country** (ADR-071 test discipline) —
   `test/kontor/l10n_*/posting_builder_test.clj`, a known invoice → exact
   expected postings, sourced from cited regulator worked examples. The
   only layer that catches failure mode B.
4. **Property tests on the mechanism layer.** Because mechanisms are
   regular: for any `TaxFacts`, reverse-charge legs sum to zero;
   withholding netting via `net-tax-effect` balances; `additive-total`
   round-trips. Generative testing works here precisely because this layer
   is regular — the irregular `rate-facts` bodies are not property-testable,
   the regular mechanism algebra is.
5. **Differential validation during migration** — the new provider path and
   the old hardcoded path produce byte-identical postings on the existing
   l10n fixtures (note 100's behaviour-identical regression gate). The old
   code is the oracle; no fresh ground truth needed.

Plus the audit layer: `:provenance` per component + the deferred
`:tax-fact/*` snapshot make "why this number" always answerable.

## 6. Sequencing + recommendation

- **Land G1 + G4 together** — they are one `case` refactor of
  `component-postings`, one schema attr, one set of helpers. Splitting them
  costs a second pass over the same two files.
- **Before the AT / IN / MX ports** (note 100 §4 order: AT → DE → AU/FR/JP →
  CA → CN → **IN** → **MX** → BR). G1 is needed at AT (template) and IN; G4
  at MX. Landing both up front means the AT pilot already exercises the
  reverse-charge path and the template the other modules copy is complete.
- **Promote to an ADR-071 addendum.** This note is the design; once the
  maintainer approves the `:tax/mechanism` attr and the `net-tax-effect`
  contract, fold §2–§4 into a dated ADR-071 addendum ("Addendum 2026-… —
  G1 + G4 posting-side completion") and implement. ADR-071's "Test
  discipline" (per-country golden fixtures) covers the per-l10n proof;
  `tax_rate_provider_test.clj` covers the substrate.

**Open question for the maintainer:** `:tax/mechanism` as designed is a flat
3-value enum. If a future jurisdiction needs *both* reverse charge *and*
withholding on one `:tax` (none of the 11 do today), the enum cannot express
it and that `:tax` would need a bespoke provider. That is an acceptable
limit — flag it, do not pre-solve it.

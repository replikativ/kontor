# kontor-incorporation

Companion for `kontor` that implements the **Phase 3 keystone** of the
tax-completion program (ADR-095): the individual→corporation
continuum.

When a founder contributes property (cash, equipment, the going concern
of a sole-prop) to a newly-formed corporation in exchange for equity,
this companion materialises four things at once in one balanced tx:

1. **New entity row** for the corp.
2. **Corp opening books** — debit contributed assets, credit
   `Common Stock` + any `Additional Paid-In Capital`. Assumed
   liabilities credit the respective liability accounts.
3. **Founder's `Investment:<NewCo>` holding** at basis (NOT FMV) —
5.
4. **Deemed disposal** on the founder's books when contributed FMV
   ≠ basis. Recorded unconditionally as
   `:kontor.disposal/kind :incorporation-contribution`; the consumer's
   CGT provider (per ADR-103) applies the per-jurisdiction deferral
   elective (US §351, CA §85, DE §20 UmwStG, FR Apport-cession,
   JP §50, …).

## Why a companion, not the kernel

Lives in `modules/incorporation/` rather than `src/kontor/` because it
requires `kontor.disposal` (also a companion). The kernel layer-cake
invariant (kernel below companions) prohibits a kernel namespace from
requiring a companion. This was moved out of the kernel pre-v0.1.0-alpha
to make the kernel jar load cleanly when shipped standalone.

## Shapes

- **Shape A (single DB)** — both founder and corp `:entity` rows in the
  same DB. `incorporate-tx-data` / `incorporate!` builds ONE balanced
  tx-data, validated per-entity by `kontor.posting` (ADR-031). The
  common case.
- **Shape B (cross-DB)** — founder personal DB and corp DB are
  SEPARATE physical DBs. Deferred to a future stage; the cross-DB
  saga primitive exists at ADR-074 / `kontor.side-effect.cross`.

## Public API

```clojure
(require '[kontor.incorporation :as inc])

(inc/incorporate-tx-data
  {:as-of #inst "2025-01-01"
   :corp  {:entity/name "NewCo Inc."
           :entity/legal-form :us/c-corp}
   :founder-entity [:kontor.entity/code "FOUNDER"]
   :contributions [{:account [:kontor.account/path "Assets:Cash"]
                    :amount  100000M  :basis 100000M}
                   {:account [:kontor.account/path "Assets:Equipment"]
                    :amount  50000M   :basis 30000M}]
   :common-stock-account     [:kontor.account/path "Equity:CommonStock"]
   :additional-paid-in       [:kontor.account/path "Equity:APIC"]
   :investment-account       [:kontor.account/path "Assets:Investment:NewCo"]})

;; → tx-data ready for kontor.validation/transact-with-validation
```

The corresponding `incorporate!` wrapper routes through the
validation gate per ADR-068.

## What this does NOT do

- **Validate corporate-law eligibility** — the consumer's job
  (a US single-member LLC has different `:kontor.entity/legal-form`
  than a DE GmbH).
- **Compute FMV / basis differences** — the consumer supplies `:basis`
  per contribution; FMV is inferred as `:amount`.
- **Issue specific share classes** — v1 treats all contributed equity
  as one class. Multi-class / preferred / convertible instruments are
  a future companion.
- **Run the CGT provider** — the disposal is recorded; the consumer
  wires the CGT provider per ADR-103.

## License

Apache 2.0 (same as the kernel).

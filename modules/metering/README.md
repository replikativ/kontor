# kontor-metering

Usage-metering → double-entry GL **summarizer** (research note 190, "Piece C").

Turns a *kontor-conformant usage subledger* — single-entry measurement rows —
into balanced, sealed, idempotent double-entry accruals in a kontor general
ledger. This is the governed second leg that a metering layer (dvergr's
`:ledger/*`) deliberately omits: metering measures *where cost went*; the GL
supplies the *counter-account* (a business/settlement fact), exactly as Odoo's
analytic ledger relates to its GL (note 190, Finding 1).

## Row shape (provider-agnostic)

```clojure
{:money      <kontor.money/Money>          ; commodity-tagged cost
 :settlement :prepaid | :postpaid           ; selects the credit leg
 :dimensions {:project <id> :provider <kw> :model <str?> :resource <kw?>}}
```

This is exactly what `dvergr.chat.accounting/usage->subledger-rows` emits.
`kontor.metering` knows nothing about dvergr — a consumer (simmis) wires them:

```clojure
(let [rows (dvergr.chat.accounting/usage->subledger-rows dvergr-conn
                                                          :from month-start :to month-end)]
  (kontor.metering/ensure-accounts! books-conn rows config)
  (kontor.metering/summarize!       books-conn rows config))
```

## What it posts

One balanced entry per (project, class, settlement, provider, commodity):

```
Dr Expenses:AI-Compute:{COGS|R&D}           ; class → account (P&L line)
Cr Assets:Prepaid-AI-Credits:<Provider>     ; :prepaid  — a drawdown
   -or-
Cr Liabilities:Accrued:AI-Provider:<Provider> ; :postpaid — an accrual
```

Provider/project ride as `:posting/dimensions` (ADR-097) so the AI-compute
expense is `marginalize`-sliceable without exploding the chart of accounts.
COGS-vs-R&D is the only account distinction; the caller supplies `:classify`
(default: all COGS). `ensure-accounts!` idempotently creates the referenced
accounts with the right types.

## Idempotency

Each entry carries a deterministic `:external-id`
(`kontor-meter|<period>|<project>|<provider>|<class>`). `summarize!` skips any
that already exist, so re-running a period — or retrying after a crash between
the meter read and the GL write — is a no-op. Cross-DB safe by construction.

## Closing the loop

- `reconcile` — accrued-vs-actual-invoice delta for a provider.
- `settle!` — books the real settlement: postpaid → `Dr Accrued / Cr Cash`;
  prepaid credit purchase → `Dr Prepaid-Credits / Cr Cash`.

## License

Apache-2.0, same as kontor. Depends on the kontor kernel (`kontor.book`,
`kontor.reporting.balance`) + `kontor.money`.

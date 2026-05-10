# datahike-accounting

A double-entry accounting **kernel** for Clojure, built on [datahike](https://github.com/replikativ/datahike).

> **Status: Phase 0.** Skeleton, schema design, and architecture are landed. Phase 1 (working kernel + tax + bitemporal queries + Beancount round-trip) is in progress. See [doc/roadmap.md](doc/roadmap.md).

## What it is

- A **schema** for double-entry accounting on datahike: accounts, journals, transactions, postings, taxes, periods, partners, balance assertions.
- A **posting validator** that enforces sum-to-zero per transaction, currency-correctness, and posted-entry sealing.
- A **tax engine** built around a `TaxProvider` protocol, with a `StaticTableProvider` covering most countries' VAT/GST/HST surfaces.
- **Bitemporal queries** out of the box: every read takes `:as-of-tx` and `:as-of-valid` parameters. Tx-time comes from datahike for free; valid-time is modeled explicitly.
- A **Beancount round-trip** as the canonical correctness test (Phase 1 acceptance).
- Hooks for **commit-level audit hashing** that complete when datahike's upstream Track-B PR lands.

## What it isn't

- Not an ERP. Not a UI. Not a US sales-tax engine. Not a Peppol Access Point. See [ADR-010](doc/decisions.md) for the full list of scope boundaries.
- Not a translation of Odoo. We use Odoo as a reference oracle; we don't lift LGPLv3 code into our EPL kernel. See [ADR-001](doc/decisions.md) and [doc/research/01-odoo-reuse-and-landscape.md](doc/research/01-odoo-reuse-and-landscape.md).

## How it composes

```
your app  →  datahike-accounting-l10n-de  →  datahike-accounting  →  datahike
              (chart of accounts,             (kernel: schema,        (storage,
               tax tags, VAT report)           postings, taxes,        history,
                                               sealing, queries)       branches)
```

Per-country localizations and adapters (Mustang for Factur-X/XRechnung, CRA filing schemas, SST CSV feeders) ship as **separate artifacts** so the kernel stays small and licenses stay honest.

## Quick start (Phase 1, when ready)

```clojure
(require '[datahike-accounting.core :as a])

;; Open an in-memory accounting DB with the schema loaded.
(def conn (a/create-test-db))

;; Post a balanced journal entry.
(a/post-transaction!
 conn
 {:transaction/journal       :sales
  :transaction/effective-date #inst "2026-05-09"
  :transaction/narration      "Customer invoice INV-2026-0001"
  :postings
  [{:posting/account #_:revenue/services
    :posting/amount  -1000.00M
    :posting/commodity :EUR}
   {:posting/account #_:asset/receivable
    :posting/amount  1000.00M
    :posting/commodity :EUR
    :posting/partner customer-eid}]})

;; Trial balance, bitemporal:
(a/trial-balance conn {:as-of-valid #inst "2026-05-31"
                       :as-of-tx    #inst "2026-06-30"})
```

## Project documents

- [doc/decisions.md](doc/decisions.md) — architecture decisions (start here)
- [doc/architecture.md](doc/architecture.md) — layer cake, namespaces, module map
- [doc/roadmap.md](doc/roadmap.md) — phased plan, acceptance criteria
- [doc/research/00-index.md](doc/research/00-index.md) — research that informed the design
- [CLAUDE.md](CLAUDE.md) — guidance for AI-assisted iteration (also useful for humans)

## License

EPL-1.0. See [LICENSE](LICENSE).

Per-country localization modules ship as separate artifacts with their own licenses (e.g., `datahike-accounting-l10n-de` is GPLv3 because its data is sourced from Tryton/GnuCash). The kernel itself stays EPL-1.0.

## Contributing

Open an issue describing the slice you'd like to take from `doc/roadmap.md`, then follow the iteration loop in [CLAUDE.md](CLAUDE.md). Test-first; ADR before non-trivial design changes; one-DB cohabitation invariant from [ADR-002](doc/decisions.md) holds throughout.

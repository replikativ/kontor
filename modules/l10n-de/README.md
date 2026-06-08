# kontor-l10n-de

Germany localization for `kontor` — chart of accounts, period taxes,
investment-income, DATEV interchange, and the e-invoice + financial-
statement plumbing for a DE-GmbH / UG / GbR / Einzelunternehmen.

## What it covers

| Surface | Status | Namespace(s) |
|---|---|---|
| SKR04 chart of accounts (~44-account starter) | shipped | `kontor.l10n-de.chart` |
| UStVA (VAT return) | shipped | `kontor.l10n-de.ustva` |
| `:kontor.country/code "DE"` tax provider + StaticTable rates (19% / 7% / 0%) | shipped | `kontor.l10n-de.tax-provider` |
| **CIT** — KSt + Soli + GewSt as ADR-101 statute-as-data (`:provision`) | shipped (ADR-104) | `kontor.l10n-de.cit-statute` + `…cit-provider` |
| **CGT** — §20 Abgeltungsteuer, §17 Teileinkünfte, §23 Spekulationsfrist, §8b 95/5, with 4 loss buckets | shipped | `kontor.l10n-de.cgt-statute` + `…cgt-provider` |
| **Investment income** — KiSt + Soli-on-§20 | shipped | `kontor.l10n-de.investment-income-{statute,provider}` |
| **DATEV** — DATEV-format EXTF Buchungssätze import + export | shipped | `kontor.l10n-de.datev` |
| **HGB / EStG financial statements** — Aktiva / Passiva / GuV (P&L) / EÜR | shipped | `kontor.l10n-de.bs` + `…pnl` + `…eur` |
| **Closing** — Bilanzierung / EÜR year-end carry | shipped | `kontor.l10n-de.closing` |
| **§147 AO retention** — 10y / 6y / 8y categories | shipped | `kontor.l10n-de.retention` |
| **Identifiers** — Steuernummer, USt-IdNr validation | shipped | `kontor.l10n-de.identifiers` |
| **Invoice helpers** — §14 UStG mandatory fields | shipped | `kontor.l10n-de.invoice` |
| **Period-tax kinds** — DE-specific kinds for the kernel `PeriodTaxProvider` | shipped | `kontor.l10n-de.period-tax-provider` |
| ELSTER (UStVA submission protocol) | NOT shipped — out of scope (consumer plugs Elster-ERIC) |
| Factur-X / ZUGFeRD XML generation | via separate `einvoice-de` companion |

## Install

The single-call preset:

```clojure
(require '[kontor.core :as k]
         '[kontor.l10n-de.preset :as de])

(def conn (k/create-test-db))            ; kernel schema pre-loaded
(de/install-all! conn)                   ; SKR04 + journals + 3 statutes
```

`install-all!` installs:

- CIT statute (15 `:kontor.parameter`s + 7 `:kontor.provision`s — KSt /
  Soli / GewSt cross-component flow)
- CGT statute (§20 / §17 / §23 / §8b — 4 loss buckets, parameter-
  history bitemporally gated by `:kontor.provision/effective-from`)
- Investment-income statute (KiSt + Soli-on-§20)
- SKR04 chart of accounts
- 5 default journals (GJ / CR / CD / SJ / PJ)

Idempotent — re-running installs nothing new.

## Quickstart — a DE GmbH posting + UStVA

```clojure
(require '[kontor.core :as k]
         '[kontor.l10n-de.preset :as de]
         '[kontor.l10n-de.ustva :as ustva]
         '[kontor.book :as book]
         '[datahike.api :as d])

(def conn (k/create-test-db))
(de/install-all! conn)

;; Seed the GmbH itself
(d/transact conn [{:kontor.entity/code "GMBH"
                   :kontor.entity/name "Acme GmbH"
                   :kontor.entity/kind :company
                   :kontor.entity/country "DE"
                   :kontor.entity/functional-commodity
                   [:kontor.commodity/symbol "EUR"]}])

;; Post a domestic B2B sale — 19% USt
(book/sell!
  conn {:entity        [:kontor.entity/code "GMBH"]
        :external-id   "INV-2026-001"
        :date          #inst "2026-03-15"
        :gross         1190.00M
        :commodity     [:kontor.commodity/symbol "EUR"]
        :revenue-account [:kontor.account/code "8400"]   ; SKR04 revenue
        :tax-rate-id   :de/std-19
        :partner       [:kontor.partner/external-id "CUST-1"]})

;; Compute UStVA for Q1-2026
(ustva/compute conn
  {:entity [:kontor.entity/code "GMBH"]
   :period {:from #inst "2026-01-01"
            :to   #inst "2026-03-31"}})
;; => {:zeile-81 ... :zeile-83 ... :zahllast 190.00M :commodity "EUR"}
```

## Status / non-goals

- **No ELSTER submission protocol.** UStVA / EÜR / KSt computes; the
  Elster-ERIC client is consumer-side. Consumers integrate ELSTER-ERIC
  or a third-party Elster client.
- **No DE payroll engine.** That's `kontor-payroll-de-datev` (LODAS +
  Buchungsbeleg + HGB §249 PTO accrual) — a separate companion.
- **No Factur-X / ZUGFeRD XML.** Use `einvoice-de` (wraps the
  Apache-2.0 Mustang library).
- **SKR04 only** as the bundled chart; SKR03 + IKR are
  consumer-pluggable via the chart loader.
- **Solidaritätszuschlag** is bitemporally aware — the threshold
  history through 2021-01-01 (Soli-Reform) is encoded as
  `:kontor.parameter-value`s, but a high-income corporate filing in
  2020 still computes the pre-reform full Soli.

## Load-bearing ADRs

- [ADR-101](../../doc/decisions.md) — statute-as-data substrate (`:kontor.tax-concept` / `:kontor.provision` / `:kontor.regime` / `:kontor.parameter`)
- [ADR-104](../../doc/decisions.md) — DE CIT (KSt + Soli + GewSt) as ADR-101 statute data
- [ADR-103](../../doc/decisions.md) — per-jurisdiction CGT design

## Key namespaces (public surface)

- `kontor.l10n-de.preset` — `install-all!`, `create-de-db`
- `kontor.l10n-de.chart` — SKR04 loader + `install!`
- `kontor.l10n-de.ustva` — `compute` (the UStVA return)
- `kontor.l10n-de.cit-provider` — KSt + Soli + GewSt provider (3-component `TaxReturnFacts`)
- `kontor.l10n-de.cgt-provider` — Abgeltungsteuer + §17 + §23 + §8b
- `kontor.l10n-de.investment-income-provider` — KiSt + Soli-on-§20
- `kontor.l10n-de.bs` / `…pnl` / `…eur` — financial statements
- `kontor.l10n-de.datev` — DATEV CSV import/export
- `kontor.l10n-de.invoice` — §14 UStG mandatory-field helpers
- `kontor.l10n-de.identifiers` — Steuernummer / USt-IdNr validators
- `kontor.l10n-de.retention` — §147 AO retention-category seeds

## Tests

`modules/l10n-de/test/kontor/l10n_de/` — 11 test files, ~104 deftests
(20% of the project's per-l10n test load):
- `cit_provider_test.clj` — KSt + Soli + GewSt, incl. the BMF GmbH
  €150k Hebesatz 380% → €43,687.50 reference case
- `cgt_provider_test.clj` — Abgeltungsteuer + §17 + §23
- `investment_income_provider_test.clj` — KiSt + Soli flows
- `ustva_test.clj` — UStVA returns + remittance
- `datev_test.clj` — DATEV CSV round-trip
- `financial_statements_test.clj` — BS / GuV
- `tax_provider_test.clj` — StaticTable rates
- `identifiers_test.clj` — Steuernummer / USt-IdNr edge cases
- `preset_test.clj` — `install-all!` end-to-end
- `period_tax_provider_test.clj` — period-tax-kind dispatch
- `retention_test.clj` — §147 AO category seeds

## License

Apache 2.0.

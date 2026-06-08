# kontor

[![Clojars Project](https://img.shields.io/clojars/v/org.replikativ/kontor.svg)](https://clojars.org/org.replikativ/kontor)
[![CircleCI](https://circleci.com/gh/replikativ/kontor.svg?style=shield)](https://circleci.com/gh/replikativ/kontor)
[![Slack](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/)

**Books as queryable data, on datahike.**

`kontor` is a double-entry accounting **kernel** for Clojure apps. Postings,
balances, periods, sealing, audit, tax — all exposed as data your application
queries with Datalog. Build an invoicing tool, a payroll product, a SaaS-billing
engine, or a country-specific filing pipeline on top — in the same datahike
connection where your business state already lives.

```clojure
(require '[datahike.api :as d]
         '[kontor.book :as book]
         '[kontor.l10n-de.preset :as de]
         '[kontor.l10n-de.cit-provider :as de-cit]
         '[kontor.l10n-de.pnl :as de-pnl]
         '[kontor.tax.period-tax-provider :as ptp])

;; In-memory datahike + DE schema + SKR04 chart + KSt/Soli/GewSt
;; statute parameters, in one call.
(def conn (de/create-de-db))

(d/transact conn
  [{:kontor.entity/name "Acme UG"
    :kontor.entity/code "ACME-UG"
    :kontor.entity/country "DE"
    :kontor.entity/functional-commodity [:kontor.commodity/symbol "EUR"]}])

;; Post a balanced, sealed, gate-checked entry. Positive amounts
;; are debits, negatives are credits; the legs must sum to zero per
;; (entity × ledger × commodity) or the gate refuses the write.
(book/entry! conn
  {:entity         [:kontor.entity/code "ACME-UG"]
   :commodity      :EUR
   :journal        [:kontor.journal/code "CR"]
   :effective-date #inst "2026-06-30"
   :narration      "Beratung H1 (€40k + 19 % USt)"
   :postings [{:account [:kontor.account/path "Umlaufvermögen:Bank"]                :amount  47600M}
              {:account [:kontor.account/path "Erträge:Erlöse:19%"]                 :amount -40000M}
              {:account [:kontor.account/path "Verbindlichkeiten:Umsatzsteuer:19%"] :amount  -7600M}]})

;; Run the DE corporate-income-tax provider over the year.
;; KSt + Soli + GewSt are ADR-101 :provision data; the result
;; matches BMF worked examples to the cent.
(def facts
  (ptp/period-tax-facts (de-cit/de-cit-provider {})
    {:db (d/db conn)
     :entity   [:kontor.entity/code "ACME-UG"]
     :period   {:from #inst "2026-01-01" :to #inst "2027-01-01"}
     :tax-unit {:hebesatz 490}
     :inputs   {:book-profit 25000M}}))

(sort (map #(-> % :liability :amount) (:components facts)))
;; => (3956.25000M 4287.5000M)   ; KSt+Soli + GewSt for €25k @ Hebesatz 490
```

Full walkthrough with explanations: [`doc/quickstart.md`](doc/quickstart.md).
The same code is regression-tested in
[`test/kontor/integration/cross_border_scenario_test.clj`](test/kontor/integration/cross_border_scenario_test.clj),
which also covers the cross-border dividend scenario (DE UG → CA personal).

### Same shape, different jurisdiction — a US LLC under IRC §11

The same `book/entry!` + `*-cit-provider` pattern works across all
11 jurisdictions. Only the preset, the chart, and the statute data
change.

```clojure
(require '[kontor.l10n-us.preset :as us]
         '[kontor.l10n-us.cit-provider :as us-cit])

;; In-memory datahike + USD commodity + QBO-style US chart of accounts
;; + IRC §11 / §1(h) / §1(j) statute parameters, in one call.
(def us-conn (us/create-us-db))

(d/transact us-conn
  [{:kontor.entity/name "Acme Inc."
    :kontor.entity/code "ACME-INC"
    :kontor.entity/country "US"
    :kontor.entity/functional-commodity [:kontor.commodity/symbol "USD"]}])

;; Book a Q2 software-license invoice — no federal sales tax (US
;; sales tax is state-level and out of substrate per ADR-005; consumers
;; plug Avalara / TaxJar via the `TaxRateProvider` protocol).
(book/entry! us-conn
  {:entity         [:kontor.entity/code "ACME-INC"]
   :commodity      :USD
   :journal        [:kontor.journal/code "CR"]
   :effective-date #inst "2025-06-30"
   :narration      "Software-license invoice — Q2 2025"
   :postings [{:account [:kontor.account/path "Assets:Bank:Checking"] :amount  500000M}
              {:account [:kontor.account/path "Income:Services"]      :amount -500000M}]})

;; IRC §11 federal corporate income tax — flat 21 % on book profit.
(ptp/period-tax-facts (us-cit/us-cit-provider {})
  {:db (d/db us-conn)
   :entity   :c-corp
   :period   {:from #inst "2025-01-01" :to #inst "2026-01-01"}
   :inputs   {:book-profit 1000000M}})
;; => 1 :cit component, $210,000 (21 % × $1M, matches IRC §11(b))
```

State income tax and state sales tax are deliberately out of the
substrate — consumers integrate Avalara, TaxJar, TaxCloud, or their
own engines via the protocol seams. Same logic in DE for trade-tax
Hebesatz: substrate provides the schedule algebra; consumers supply
the per-municipality multiplier.

## Why kontor?

**Accounting is a substrate, not an application.** Every business app that
moves money needs the same primitives — balanced postings, periods you can
close, an audit trail that holds up, tax computation that matches the
authority's filing. The hard part isn't the bookkeeping; it's keeping all of
that *queryable* alongside the business state, with no bridge to fall through.

kontor puts the substrate in your `deps.edn`. Your invoice is a `:transaction`
+ a few `:posting`s in the same datahike connection as your `:user` and
`:product`. Your "send invoice" function transacts both halves atomically. Your
year-end close is a Datalog query over the same indexes that serve your
dashboards.

Three properties shape the design:

- 🕰️ **Bitemporal by default.** Every read takes `:as-of-valid` +
  `:as-of-tx`. A Y1 misclassification caught 18 months later writes a new
  fact at the *original* valid-time; both views — what the books showed at
  year-end and what they show after the restatement — stay queryable forever.
- 🚪 **Validation gate, not a contract you can forget.** Every business
  write routes through middleware enforcing sum-to-zero per
  `(entity × ledger × commodity)`, sealing of posted entries, legal-hold,
  period-locks, and status-machine legality. Invalid tx-data raises a typed
  exception. The application never has to remember to call validators.
- 📜 **Tax law is data.** `:tax-concept`, `:provision`, `:regime`, and
  `:parameter` are entities you query. One evaluator (`apply-provisions`)
  folds the rules of 11 jurisdictions; per-country files match
  authority-published worked examples to the cent.

## What's included

**Trans-national substrate** — schema-namespaced under `:kontor.*` so kontor
cohabits with your app's namespaces in one connection.

| Concern | Surface |
|---|---|
| Posting + balance | `kontor.posting`, `kontor.reporting.{balance,ledger,trial,closing}` |
| Bitemporal | `kontor.bitemporal` (`with-vt`, `close-validity!`, `commit-tx-eid`) |
| Sealing + audit | `kontor.compliance.{sealing,audit-doc}` |
| Compliance | `kontor.compliance.{legal-hold,retention,dsar}` |
| Status machines | `kontor.workflow.status-machine` + `:approval-policy` |
| Multi-entity | `kontor.entity/family`, `kontor.provider.consolidation` |
| Reports | `kontor.reporting.report/marginalize` (σ_E primitive — any axis) |
| Verb facade | `kontor.book/{entry,receive,pay,sell,buy,receive-payment,pay-bill,transfer,adjust}` |

**Tax substrate**

| Protocol | Purpose |
|---|---|
| `TaxRateProvider` | Transactional taxes (VAT / GST / sales tax) |
| `PeriodTaxProvider` | Period taxes (CIT / PIT / CGT / property / wealth) |
| `DisposalProvider` | Capital gains substrate |
| `FxRateProvider` | IAS 21 / ASC 830 rate lookup |
| `:provision` / `:parameter` / `:regime` | Statute-as-data evaluator |
| `:fiscal-unit` | Group-tax consolidation (DE Organschaft, FR intégration, US §1502, …) |

**11 jurisdictions on the same path** — DE, FR, CA + QC, JP, BR, IN, US,
AT, AU, CN, MX. Each ships CIT (and PIT / CGT / investment-income where
applicable) as ADR-101 `:provision` data. **12 CGT providers** (same set +
UK). **11 payroll adapters** (DE LODAS, US ADP GLI, CA + QC, FR DSN, AU STP
Phase 2, BR eSocial, MX CFDI Nómina, IN TDS+PF+ESI+PT, JP Gensen, CN IIT +
五险一金, AT mBGM + L16). Per-country e-invoice for DE (Factur-X /
XRechnung).

**Companion modules** — drop into `:paths` à la carte.

| Module | What |
|---|---|
| `asset` | Asset register + depreciation books |
| `lease` | IFRS 16 / ASC 842 lessee accounting |
| `inventory` | FIFO / LIFO / WAC valuation |
| `expense` | Per-diem + reimbursement |
| `procurement` | Drop-ship + substitute + replacement + upgrade |
| `sales` / `invoice` / `collections` | Order → invoice → AR aging |
| `partner` | Party + person/org + bank-account |
| `hr` | Personnel substrate (composes with payroll-* adapters) |
| `disposal` | CGT substrate |
| `einvoice-de` | Factur-X / XRechnung via Mustang |
| `bank-{at,ca,de,fr,us}` | Bank-statement CSV importers |

**~3,000 tests / 11,500+ assertions / 0 failures.** Per-jurisdiction CIT
and CGT providers carry worked-example tests citing the authority bulletin
or XSD they were sized against.

## Install

```clojure
;; deps.edn
{:deps {org.replikativ/kontor {:mvn/version "0.1.0-alpha"}}}
```

The repo is a monorepo: the kernel ships from `src/`, and each companion
under `modules/<name>/` adds its own `src/` + `test/`. Consumers who want
only the kernel + a subset of companions trim `deps.edn` `:paths`
accordingly.

## How it composes with other apps

kontor and your app share one datahike connection by namespacing their
attributes: kernel attrs prefix under `:kontor.*`
(`:kontor.posting/account`, `:kontor.transaction/journal`, …); your app owns
its own namespaces (`:invoice/status`, `:lead/source`, …). Posting a sales
invoice writes the business state and the matching `:kontor.transaction` +
`:kontor.posting`s in **one tx** — atomic, sum-to-zero validated,
bitemporally indexed.

This is library posture, not framework. The canonical consumer is
[`beleg`](https://github.com/replikativ/beleg) (contractor invoicing); a
Clojure SaaS that wants accounting underneath puts kontor in its `deps.edn`
and writes through `kontor.book/entry!`.

## Showcases

Six end-to-end Clay notebooks in [`doc/showcases/`](doc/showcases/):

| # | Scenario | Demonstrates |
|---|---|---|
| 01 | DE GmbH B2B with Factur-X | `l10n-de` + `einvoice-de` + `collections` + sales/invoice posting bridge |
| 02 | US LLC multi-state SaaS | `TaxRateProvider` + Avalara seam + customer-dispute lifecycle |
| 03 | IN B2B with IRN + TDS | NIC e-invoice clearance + GSTR-1 export shape + reverse-charge intercompany |
| 04 | Multi-entity intercompany | FX translation + elimination + ADR-073 consolidation |
| 05 | Apple 10-K bitemporal restatement | `:tx/valid-from` + `close-validity!` against real SEC filings |
| 06 | Multi-year DE GmbH | Misclassified Y1 expense caught in Y2, restated bitemporally |

## Documentation

- [`doc/quickstart.md`](doc/quickstart.md) — 5-minute REPL walkthrough
- [`doc/accounting-model.md`](doc/accounting-model.md) — how the verbs translate to debits/credits and the algebra underneath
- [`doc/architecture.md`](doc/architecture.md) — layer cake + namespace map + module list
- [`doc/decisions.md`](doc/decisions.md) — distilled architecture decisions (~30 entries)
- [`doc/programming.md`](doc/programming.md) — the three-axis programming model (transact gate + status machines + bitemporal)

## Composable ecosystem

kontor is part of the [replikativ](https://github.com/replikativ) ecosystem:

- **[datahike](https://github.com/replikativ/datahike)** — durable Datalog
  database with bitemporal semantics; kontor's substrate.
- **[beleg](https://github.com/replikativ/beleg)** — invoicing and CRM
  for contractors and small businesses; the canonical kontor consumer app.
- **[konserve](https://github.com/replikativ/konserve)** — the pluggable
  key-value store datahike rides on (file, LMDB, S3, JDBC, Redis,
  IndexedDB, …). Use it directly for any persistent storage need.
- **[hasch](https://github.com/replikativ/hasch)** — content-addressable
  hashing for Clojure data structures.

## Contributing

Issues, PRs, and design discussions all welcome — see
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow (test-first; the
`*-tx-data` + `!` discipline; the `:kontor.*` schema-namespace rule).

## License

Copyright © 2026 Christian Weilbach et al.

Licensed under Apache License 2.0 — see [LICENSE](LICENSE).

# kontor-payroll-de-datev

DE DATEV-LODAS payroll adapter for `kontor-hr` (ADR-076).

## What it covers

| Surface | Status | Namespace |
|---|---|---|
| **DATEV EXTF Buchungsbeleg parser** — ISO-8859-1, CR-LF, semicolon-delimited LODAS Report 80 → `PayrollFacts` | shipped | `kontor.payroll-de-datev.compute` |
| **DATEV LODAS Importdatei emitter** — 4-section, ISO-8859-1, CR-LF format per LODAS Schnittstellenhandbuch | shipped | `kontor.payroll-de-datev.emit` |
| **SKR04 / SKR03 GL posting builder** — Bruttomethode2 | shipped | `kontor.payroll-de-datev.posting-builder` |
| **HGB §249 simplified Urlaubsrückstellung accrual** | shipped | `kontor.payroll-de-datev.{posting-builder,core}` |
| **Wage-type catalog validator** — consumer-supplied catalog with the standard kontor wage-type kinds | shipped | `kontor.payroll-de-datev.wage-types` |
| **Default account map** — SKR04 (default) + SKR03 starter wage-account maps | shipped | `kontor.payroll-de-datev.wage-types` (`default-account-map-skr04`, `default-account-map-skr03`) |
| Bundled DATEV vendor API keys | NOT shipped — per ADR-005 customers hold their own |
| Bundled wage-type catalog | NOT shipped — per-customer; the module ships a validator + the default account-map |

## Install

```clojure
(require '[kontor.core :as k]
         '[kontor.hr.core :as hr]
         '[kontor.l10n-de.chart :as de-chart]
         '[kontor.payroll-de-datev.core :as datev])

(def conn (k/create-test-db))
(hr/install! conn)                       ; kontor-hr schema + DSAR hook
(de-chart/install! conn)                 ; SKR04 chart
(datev/install! conn)                    ; module-local audit-doc attrs
```

Order matters — every step assumes the previous schema attrs are
present.

## Quickstart — parse a Buchungsbeleg, post payroll, emit LODAS

```clojure
(require '[kontor.payroll-de-datev.compute :as compute]
         '[kontor.payroll-de-datev.posting-builder :as pb]
         '[kontor.payroll-de-datev.emit :as emit]
         '[kontor.payroll-de-datev.wage-types :as wt]
         '[kontor.hr.payroll :as hr-payroll])

;; 1. Validate the consumer's wage-type catalog (one-time setup)
(def catalog
  (wt/validate-catalog
    {;; wage-type-id → {:kind :gross-wage :account-code "6000"}
     "100" {:kind :gross-wage :account-code "6000"}
     "200" {:kind :ek-employer-contribution :account-code "6111"}
     "300" {:kind :si-employer-contribution :account-code "6121"}
     ;; ...
     }))

;; 2. Construct the protocol-trio providers (per ADR-075)
(def compute-provider
  (compute/make-provider
    {:coa :skr04
     :employment-pnr->eid {"PNR-0001" employment-eid-alice
                           "PNR-0002" employment-eid-bob}}))

(def posting-builder
  (pb/make-builder
    {:catalog   catalog
     :commodity [:kontor.commodity/symbol "EUR"]}))

(def emit-provider
  (emit/make-provider
    {:catalog catalog
     :allgemein {:berater-nr "1234"
                 :mandant-nr "99999"
                 :stammdaten-gueltig-ab #inst "2026-05-01"}
     :pay-period-date #inst "2026-05-01"
     :pay-period-code "DE-2026-05"}))

;; 3. Run the standard kontor.hr.payroll orchestrator with the
;;    DATEV trio as its providers — produces the GL postings AND
;;    the LODAS Importdatei in one transactional pass.
(hr-payroll/run-payroll!
  conn
  {:entity          [:kontor.entity/code "GMBH"]
   :pay-period      [:kontor.pay-period/code "DE-2026-05"]
   :buchungsbeleg-path "/tmp/lohn-buchungsbeleg-2026-05.csv"
   :compute-provider compute-provider
   :posting-builder  posting-builder
   :emit-provider    emit-provider})
;; Side effects:
;;   - parses the EXTF Buchungsbeleg
;;   - posts the Bruttomethode GL entries (Lohnaufwand + LSt + SozV)
;;   - writes the LODAS Importdatei alongside (ISO-8859-1, CR-LF)
;;   - books HGB §249 Urlaubsrückstellung if month-end
;;   - emits a :kontor.audit-doc with the LODAS payload + reconciliation
```

## Status / non-goals

- **No bundled DATEV credentials.** Consumer supplies Berater-Nr +
  Mandant-Nr + Beraternummer-Konvention.
- **No bundled wage-type catalog.** Catalogs differ per customer
  (LODAS-2020 vs LODAS-2024 vs customer-specific variants). The
  module ships a validator that ensures every wage-type entry has
  a `:kind` from the closed set + a valid `:account-code`.
- **HGB §249 Urlaubsrückstellung is the simplified version.**
  Actuarial PTO (IFRS / IAS 19) is consumer-side; the default ships
  the §249 1-day-of-vacation × daily-rate × open-vacation-days
  computation1.
- **SKR04 default, SKR03 supported.** IKR / custom CoA via consumer
  account-map override.
- **No Brutto-Netto recomputation.** The DATEV LODAS engine is
  authoritative for net wages, tax, and SI; kontor consumes its
  output and never re-derives.
- **No ELSTER / DEÜV submission.** Tax-card / Sozialversicherung
  submission is the consumer's DATEV-supplied client (Datev SEPA /
  Datev Lohn-Übermittlung); kontor only produces the LODAS
  Importdatei.

## Load-bearing ADRs + research

- [ADR-075](../../doc/decisions.md) — `PayrollProvider` trio
  (`PayrollComputeProvider` + `PayrollPostingBuilder` + `PayrollEmitProvider`)
- [ADR-076](../../doc/decisions.md) — DATEV-LODAS adapter design
  (ISO-8859-1 / 4-section file / EXTF Buchungsbeleg)
-— DE-DATEV-LODAS research-before (the reference
  reading of the LODAS Schnittstellenhandbuch + EXTF v21 spec)
-— C1 review-after (2 P0s closed in commit
  `08a2e63`)
-— Stage R final review-after (2 P0s closed in
  `2b51ae8` + 5 P1s + 2 P2s closed in `b9b7229`)

## Key namespaces (public surface)

- `kontor.payroll-de-datev.core/install!` — module schema extensions
- `kontor.payroll-de-datev.compute/make-provider` — `PayrollComputeProvider`
- `kontor.payroll-de-datev.posting-builder/make-builder` — `PayrollPostingBuilder`
- `kontor.payroll-de-datev.emit/make-provider` — `PayrollEmitProvider`
- `kontor.payroll-de-datev.wage-types/validate-catalog` — consumer-catalog validator
- `kontor.payroll-de-datev.wage-types/default-account-map-skr04` — SKR04 wage-account starter
- `kontor.payroll-de-datev.wage-types/default-account-map-skr03` — SKR03 wage-account starter
- `kontor.payroll-de-datev.core/urlaubsrueckstellung-tx-data` /
  `urlaubsrueckstellung-amount` — HGB §249 helpers

## Tests

`modules/payroll-de-datev/test/kontor/payroll_de_datev/` — 5 files,
~43 deftests:
- `compute_test.clj` — Buchungsbeleg parsing edge cases
- `posting_builder_test.clj` — Bruttomethode SKR04 + SKR03 maps
- `emit_test.clj` — LODAS Importdatei round-trip (ISO-8859-1 + CR-LF)
- `wage_types_test.clj` — catalog-validator boundaries
- `e2e_test.clj` — end-to-end via `kontor.hr.payroll/run-payroll!`

Test fixture: `resources/kontor/payroll_de_datev/fixtures/buchungsbeleg-2025-11.csv`
is a real-shaped Buchungsbeleg (anonymised) the compute provider
parses against.

## License

Apache 2.0.

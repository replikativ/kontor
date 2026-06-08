# kontor-l10n-ca

Canada localization for `kontor` — chart of accounts, GST/HST/PST,
T2 federal + provincial CIT, T1 personal-income line items, CRA
filings (T4 / T5 / T5018 / NOA), capital-gains tax (50% inclusion +
LCGE), GST/HST returns, and the related substrate.

## What it covers

| Surface | Status | Namespace(s) |
|---|---|---|
| CA chart of accounts (CAD-functional starter) | shipped | `kontor.l10n-ca.chart` |
| GST / HST / PST per-province rates + return | shipped | `kontor.l10n-ca.gst-hst` + `…tax-provider` |
| **CIT** — T2 federal + per-province as ADR-101 statute-as-data (v1 = federal + ON + BC + AB) | shipped (ADR-107) | `kontor.l10n-ca.cit-statute` + `…cit-provider` |
| **CGT** — 50% inclusion + LCGE $1.275M (2026) + ABIL + CCA recapture | shipped | `kontor.l10n-ca.cgt-statute` + `…cgt-provider` |
| **Investment income** — eligible / non-eligible gross-up + DTC + §126 FTC + Part IV | shipped | `kontor.l10n-ca.investment-income-{statute,provider}` |
| **CRA XML emit** — T4 / T5 / T5018 + T619 transmittal | shipped | `kontor.l10n-ca.xml.{t4,t5,t5018,t619}` |
| **NOA (Notice of Assessment) reconciliation** | shipped | `kontor.l10n-ca.noa` |
| **Returns** — T2 + T1 line-item maps + Schedule emitters (S3/S4/S8/S9/S11) | shipped | `kontor.l10n-ca.returns` + `…y2024/*` |
| **T2125 (self-employment)** — business-income input for the PIT provider | shipped | `kontor.l10n-ca.y2024.t2125` |
| **BC428** — BC PIT schedule | shipped | `kontor.l10n-ca.y2024.bc428` |
| **PDF rendering** — selected schedules | shipped | `kontor.l10n-ca.pdf` |
| **Period-tax kinds** — CA-specific kinds | shipped | `kontor.l10n-ca.period-tax-provider` |
| QC RL-1 / TPZ-1015 (Quebec) | via `kontor-payroll-ca` C4.1 (ADR-087) |
| EFILE submission protocol | NOT shipped — out of scope (consumer plugs Internet File Transfer / CRA WebForm) |

## Install

```clojure
(require '[kontor.core :as k]
         '[kontor.l10n-ca.preset :as ca])

(def conn (k/create-test-db))            ; kernel schema pre-loaded
(ca/install-all! conn)                   ; CA chart + journals + 3 statutes
```

`install-all!` installs:

- CIT statute (18 `:kontor.parameter`s + 10 `:kontor.provision`s — federal
  T2 + per-province ON / BC / AB; CCPC Small Business Deduction cascade
  via `:op :schedule-override`)
- CGT statute (50% inclusion + LCGE + provincial)
- Investment-income statute (eligible / non-eligible gross-up + DTC +
  §126 FTC + Part IV)
- CA chart of accounts
- 5 default journals (GJ / CR / CD / SJ / PJ)

Idempotent — re-running installs nothing new.

## Quickstart — a Vancouver sole-prop posting + T2125

```clojure
(require '[kontor.core :as k]
         '[kontor.l10n-ca.preset :as ca]
         '[kontor.l10n-ca.y2024.t2125 :as t2125]
         '[kontor.book :as book]
         '[datahike.api :as d])

(def conn (k/create-test-db))
(ca/install-all! conn)

;; Seed a sole-proprietor entity
(d/transact conn [{:kontor.entity/code "ME"
                   :kontor.entity/name "Sample Owner — Sole Prop"
                   :kontor.entity/kind :individual
                   :kontor.entity/country "CA"
                   :kontor.entity/functional-commodity
                   [:kontor.commodity/symbol "CAD"]}])

;; Post a consulting-revenue invoice — 5% GST (BC)
(book/sell!
  conn {:entity        [:kontor.entity/code "ME"]
        :external-id   "INV-2026-001"
        :date          #inst "2026-03-15"
        :gross         5250.00M   ; 5000 + 5% GST
        :commodity     [:kontor.commodity/symbol "CAD"]
        :revenue-account [:kontor.account/code "4000"]
        :tax-rate-id   :ca/gst-5
        :partner       [:kontor.partner/external-id "CLIENT-1"]})

;; Pull T2125 (Statement of Business Activities) for 2026
(t2125/compute conn
  {:entity [:kontor.entity/code "ME"]
   :period {:from #inst "2026-01-01"
            :to   #inst "2026-12-31"}})
;; => {:line-8000-gross ... :line-8521-expenses ... :line-9946-net ...}
```

## Status / non-goals

- **No EFILE submission protocol.** The XML emitters produce
  `XMLDocument` per the CRA schemas; the actual transmission via
  Internet File Transfer / CRA WebForm is consumer-side.
- **v1 CIT covers federal + ON + BC + AB.** Other provinces are
  pluggable via the `kontor.l10n-ca.cit-provider` rate override
  pattern (per-province SBD pool + rate parameters); the substrate
  scales without statute changes.
- **No Quebec-specific RL-1 / TP-1.** Federal-only; QC payroll
  filings (RL-1 / TPZ-1015) ship in `kontor-payroll-ca` ADR-087.
- **No CDA (Capital Dividend Account) bookkeeping helper yet** —
  flagged as a follow-up.
- **GST/HST/PST table** is the rates as of 2026 (5% GST + provincial
  rates as published by CRA); consumers in earlier years must inject
  the historical rate via the standard `:kontor.parameter` history.

## Load-bearing ADRs + research

- [ADR-101](../../doc/decisions.md) — statute-as-data substrate
- [ADR-107](../../doc/decisions.md) — CA CIT (T2 federal + per-province)
  as ADR-101 statute data
-— CA CIT design + the CCPC ON+AB worked example
  (CAD 89,230)
- For CGT design: ADR-103 (CA CGT — 50% inclusion + LCGE + ABIL)

## Key namespaces (public surface)

- `kontor.l10n-ca.preset` — `install-all!`, `create-ca-db`
- `kontor.l10n-ca.chart` — CA CoA loader + `install!`
- `kontor.l10n-ca.gst-hst` — GST/HST/PST return computation
- `kontor.l10n-ca.cit-provider` — T2 federal + per-province (N-component)
- `kontor.l10n-ca.cgt-provider` — 50% inclusion + LCGE + ABIL
- `kontor.l10n-ca.investment-income-provider` — eligible / non-eligible
- `kontor.l10n-ca.returns` — T2 / T1 line-item maps
- `kontor.l10n-ca.y2024.t2125` — Statement of Business Activities
- `kontor.l10n-ca.y2024.{bc428,s3,s4,s8,s9,s11,t1}` — per-schedule helpers
- `kontor.l10n-ca.xml.{t4,t5,t5018,t619}` — CRA XML emitters
- `kontor.l10n-ca.noa` — Notice-of-Assessment reconciliation
- `kontor.l10n-ca.invoice` — CRA-compliant invoice helpers
- `kontor.l10n-ca.identifiers` — BN / GST-ID validators
- `kontor.l10n-ca.pdf` — PDF rendering for selected schedules
- `kontor.l10n-ca.closing` — year-end closing

## Tests

`modules/l10n-ca/test/kontor/l10n_ca/` + subdirs — ~228 deftests
total (the most test-dense l10n module):
- `cit_provider_test.clj` — federal + per-province CCPC cascade
  (incl. ON+AB → CAD 89,230 worked case)
- `cgt_provider_test.clj` — 50% inclusion / LCGE / ABIL
- `investment_income_provider_test.clj` — DTC + §126 FTC + Part IV
- `gst_hst` covered by `tax_provider_test.clj` / `tax_test.clj`
- `noa_test.clj` — NOA reconciliation
- `returns_test.clj` — return line-item maps
- `invoice_test.clj` — invoice helpers
- `identifiers_test.clj` — BN / GST-ID validators
- `closing_test.clj` — year-end carry
- `pdf_test.clj` — PDF rendering smoke
- `preset_test.clj` — `install-all!` end-to-end
- `xml/` — T4 / T5 / T5018 / T619 emitter tests
- `y2024/` — per-schedule + T2125 / BC428 tests

## License

Apache 2.0.

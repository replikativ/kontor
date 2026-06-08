# kontor-treaty-de-ca

Per-treaty-pair helper companion for the **Germany ↔ Canada
double-taxation treaty** — the first per-treaty-pair helper companion
in `kontor` (ADR-019).

## What it does

A cross-border dividend / interest / royalty between a DE-resident
payer and a CA-resident payee splits the source-state withholding
into two slices:

1. **Treaty-creditable** — the source-state WHT capped at the treaty
   rate; the destination jurisdiction's foreign-tax-credit (CA §126
   for inbound to CA) consumes this.
2. **Over-treaty excess** — the slice the source state withheld in
   excess of the treaty cap; refundable from the source state via
   `Erstattungsantrag` (DE BZSt, CA CRA reciprocally).

Without a helper, every consumer hand-computes (a) the FX rate at
value-date, (b) the split, (c) which kontor accounts receive each
slice. This module makes the move a single composite event.

### Treaty rates (post-2017 protocol)

| Income kind | Treaty cap | Source |
|---|---:|---|
| Portfolio dividends (< 10 % stake) | 15 % | Art. 10(2)(b) |
| Direct-investment dividends (≥ 10 % stake) | 5 % | Art. 10(2)(a) |
| Interest | 10 % | Art. 11(2) |
| Royalties | 0 % | Art. 12 (post-2017 protocol) |
| Pensions | 0 % | Art. 18 |
| Government service | 0 % | Art. 19 |

## Scope of v1

- **Inbound to CA** — DE-source dividend received on a CA-personal
  kontor DB. v1 implements this (the common case for the founder-
  with-European-LLC scenario).
- **Outbound from CA** (CA Inc paying a DE-resident shareholder):
  deferred to v2 — the data shape is documented but `receive-
  dividend-from-ca!` is not yet shipped.

## Install

No schema — pure functions over the kernel + l10n primitives. The
companion's prerequisites are:

1. `kontor.core/install-schema!` (kernel)
2. `kontor.l10n-ca.preset/install-all!` (CA CoA + journals)
3. The 4 destination accounts present in your CA-personal CoA:
   - `Assets:Bank:CAD`
   - `Assets:Foreign-Tax-Prepaid`
   - `Assets:Foreign-Tax-Refundable`
   - `Income:Dividends:Foreign:DE`

   These four ship in the CA preset (Phase B) plus the additions
   Phase D exercised — see `kontor.l10n-ca.chart`.

```clojure
(require '[kontor.core :as k]
         '[kontor.l10n-ca.preset :as ca]
         '[kontor.treaty.de-ca :as treaty])

(def conn (k/create-test-db))
(ca/install-all! conn)                  ; CA CoA + journals + statutes
;; (treaty/install! …) is not required — module is pure functions
```

## Quickstart — receive a DE-source portfolio dividend on the CA-personal DB

```clojure
(require '[kontor.treaty.de-ca :as treaty])

;; Acme GmbH (DE) declares €1000 portfolio dividend, withholds €263.75
;; (KESt 25% + Soli 5.5%-of-KESt = 26.375 %). FX = 1.45 CAD/EUR.
(treaty/receive-dividend-from-de!
  conn
  {:gross-amount     1000.00M           ; EUR
   :withheld-amount   263.75M           ; EUR — DE KESt + Soli
   :income-kind      :dividend-portfolio
   :fx-rate          1.45M              ; CAD per EUR at value-date
   :net-cash-amount  736.25M            ; EUR (gross − withheld)
   :effective-date   #inst "2026-04-15"
   :payer-partner    [:kontor.partner/external-id "ACME-GMBH"]
   :entity           [:kontor.entity/code "ME"]})

;; The helper posts a 4-leg balanced entry in CAD:
;;   Assets:Bank:CAD              1067.56 ← net cash inflow
;;   Assets:Foreign-Tax-Prepaid    217.50 ← treaty-creditable (15 % of €1000 × 1.45)
;;   Assets:Foreign-Tax-Refundable 164.94 ← over-treaty excess (BZSt refund-claim)
;;   Income:Dividends:Foreign:DE −1450.00 ← gross dividend in CAD

;; Lookup the treaty rate for any income kind
(treaty/treaty-rate :interest)                ; => 0.10M
(treaty/treaty-rate :dividend-direct-investment) ; => 0.05M

;; Pure split (no posting) — useful for what-if calculations
(treaty/split-de-wht
  {:gross-amount    1000.00M
   :withheld-amount  263.75M
   :income-kind     :dividend-portfolio})
;; => {:treaty-creditable 150.00M :over-treaty-refundable 113.75M :treaty-rate 0.15M}
```

## Status / non-goals

- **Inbound DE → CA only.** Outbound CA → DE deferred to v2.
- **No automatic BZSt refund-claim filing.** The companion books the
  refundable slice as an asset; submitting the `Erstattungsantrag`
  on bzst.de is consumer-side.
- **No multi-protocol versioning.** v1 uses post-2017-protocol
  rates. Earlier-protocol filings (e.g. the 2001 protocol pre-15 %
  era for direct-investment dividends) need a consumer-side rate
  override; in v2 these become bitemporally-encoded
  `:kontor.parameter`s.
- **No treaty other than DE-CA.** This module is the pattern
  reference; DE-US, DE-UK, CA-US, CA-UK etc. are tracked as future
  per-pair companions.

## Load-bearing ADRs + research

- [ADR-019](../../doc/decisions.md) — multi-pillar tax engine + treaty position
- [ADR-105](../../doc/decisions.md) — FR CIT (reference for cross-jurisdiction credit shape)

## Key namespaces (public surface)

- `kontor.treaty.de-ca/treaty-rate` — lookup the post-2017 cap
- `kontor.treaty.de-ca/split-de-wht` — pure decomposition into treaty-
  creditable + over-treaty-refundable
- `kontor.treaty.de-ca/receive-dividend-from-de!` — the composite event

## Tests

`modules/treaty-de-ca/test/kontor/treaty/de_ca_test.clj` — 5 deftests:
- treaty-rate lookup + unknown-kind throws
- split-de-wht arithmetic (boundary at the treaty cap)
- receive-dividend-from-de! 4-leg balanced posting + audit narration
- excess vs creditable when withheld exactly equals the treaty cap
- multi-currency sanity (FX-rate scaling + HALF-EVEN rounding)

## License

Apache 2.0.

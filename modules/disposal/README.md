# kontor-disposal

Companion for `kontor` that records **ownership-change events** —
the data layer that per-jurisdiction CGT (capital-gains tax)
providers consume.

## What it does

A `:kontor.disposal` is an *event*: at one moment a specific
subject (an asset, a lot of shares, a participation) is relinquished.
The GL posts proceeds and basis against accounts; the disposal entity
carries the event metadata — what was disposed, when, for how much,
against what basis, with what holding period — that the GL alone
cannot see.

- **`:kontor.disposal/*` schema** — kind / subject / asset-class /
  acquired-on / disposed-on / proceeds / basis / depreciation-taken
  / ownership-fraction / elective-regime / exemption-claimed /
  rollover-amount / loss-bucket / state. Jurisdiction-specific
  fields are *optional* — DE §8b / US §1245 / UK BADR / JP residence
  all share one substrate.
- **Status machine** (ADR-034): `:recorded → :recognized | :voided`.
  `record-disposal!` lands in `:recorded`; `recognize!` advances to
  `:recognized` and links the realising `:transaction`; `void!`
  rolls back a correction.
- **Pure `*-tx-data` builders** (ADR-068) paired with `!` wrappers
  for every write — `record-disposal-tx-data` / `record-disposal!` /
  `recognize-tx-data` / `recognize!` / `void-tx-data` / `void!`.
- **Read queries**: `disposals-of` (every disposal of a subject),
  `disposals-in-period` (date window, entity-scoped, void-aware),
  `realized-gain` (proceeds − basis − rollover), `realized-gain-
  summary` (summed across a period × `:loss-bucket`).
- **`DisposalProvider` protocol** (`kontor.disposal-provider` in the
  kernel + `kontor.disposal.provider` canonical impl): per-
  jurisdiction CGT providers depend on the protocol, not the
  companion. A pure-service consumer that never loads
  `kontor-disposal` still works — CGT providers just see no
  disposals.

## When to use it

- Selling a fixed asset and computing capital-gains tax
- Founding-shareholder share exchange / incorporation contribution
  (`:incorporation-contribution`)
- Section §351 / §85 / §6 / §15c-style rollover events
- Distribution-in-kind from a corp to a shareholder
- Deemed disposal on emigration / change-in-use / death (per
  jurisdiction)

When NOT to use it:
- Sale of inventory in the ordinary course → `kontor-inventory` +
  `kontor.book/sell!` (ordinary income, not capital gains)
- Operating-lease termination → `kontor-lease`
- Asset depreciation runs → `kontor-asset` (`run-depreciation!`)
- Refundable security deposit return → kernel ledger

## Install

```clojure
(require '[kontor.core :as k]
         '[kontor.disposal :as disp])

(def conn (k/create-test-db))            ; kernel schema pre-loaded
(disp/install! conn)                     ; companion schema + state-machine seeds
```

`install!` is idempotent for the schema attrs; the status-transition
seeds carry the kernel-wide composite-tuple-with-nil-in-tuple
non-idempotency caveat — fine for one install per DB.

## Quickstart

```clojure
(require '[kontor.disposal :as disp]
         '[datahike.api :as d])

;; Record an asset sale on 2025-06-15 — $120k proceeds, $80k basis
(disp/record-disposal!
  conn {:entity          [:kontor.entity/code "HOLDCO"]
        :external-id     "DISP-2025-001"
        :kind            :sale
        :subject         [:kontor.account/code "1900"] ; the asset account
        :subject-kind    :fixed-asset
        :acquired-on     #inst "2020-01-01"
        :disposed-on     #inst "2025-06-15"
        :proceeds        {:amount 120000.00M
                          :commodity [:kontor.commodity/symbol "USD"]}
        :basis           {:amount  80000.00M
                          :commodity [:kontor.commodity/symbol "USD"]}
        :recorded-by-uid "alice"})

;; After posting the realising transaction via kontor.book/sell! the
;; consumer links the disposal to it:
(disp/recognize!
  conn {:disposal      "DISP-2025-001"
        :transaction   [:kontor.transaction/external-id "TX-2025-0078"]
        :recognized-by "alice"
        :reason-note   "Linked to general-journal sale entry"})

;; Query realised gain
(disp/realized-gain (d/db conn) "DISP-2025-001")
;; => {:gain 40000.00M :commodity "USD"}

;; A per-jurisdiction CGT provider (e.g. l10n-us, l10n-de) consumes
;; the disposals automatically via its DisposalProvider impl.
```

## What it does NOT do

- **No tax computation.** The companion records the event; the per-
  jurisdiction CGT provider (`kontor-l10n-us/cgt-provider`,
  `kontor-l10n-de/cgt-provider`, …) applies the statute. The
  substrate has been validated across 11 jurisdictions (US / DE /
  UK / JP / CA / FR / AU / BR / IN / MX / CN / AT) with **zero**
  kernel changes required.
- **No depreciation recapture.** The companion stores `:depreciation-
  taken`; the per-jurisdiction provider re-classifies it (US §1245
  / §1250, DE §6 EStG, …).
- **No automatic GL posting.** The realising transaction is posted
  via `kontor.book/sell!` (or a per-companion equivalent); `recognize!`
  only LINKS the disposal to it.
- **No subject-quantity bookkeeping.** A disposal can carry a
  fractional `:ownership-fraction` for partial sales of a position
  but does not track running inventory of shares / units — that
  belongs in a lot-tracking layer the consumer composes.

## Load-bearing ADRs + research

- [ADR-102](../../doc/decisions.md) — `kontor-disposal` companion +
  schema + status machine
- [ADR-103](../../doc/decisions.md) — `DisposalProvider` protocol +
  per-jurisdiction CGT provider pattern

## Key namespaces

- `kontor.disposal.schema` — `:kontor.disposal/*` attrs + state-
  machine seeds + `install!`
- `kontor.disposal` — the public lifecycle (`record-disposal!`,
  `recognize!`, `void!`) + read queries (`disposals-of`,
  `disposals-in-period`, `realized-gain`, `realized-gain-summary`)
- `kontor.disposal.provider` — `DatahikeDisposalProvider` (the
  canonical `DisposalProvider` impl reading from this companion's
  schema)

## Tests

`modules/disposal/test/kontor/`:
- `disposal_test.clj` — 20 deftests covering install + record-
  disposal round-trip + recognize/void state transitions + queries +
  jurisdiction-specific extension-field round-trip (DE §8b shape, US
  §1245 depreciation-taken, UK BADR ownership-fraction + exemption-
  claimed, JP residence?)

## License

Apache 2.0.

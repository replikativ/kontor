# kontor-asset

Fixed-asset register + per-(asset, ledger) depreciation books for
`kontor`.

## What it does

A fixed asset (machine, vehicle, building, capitalized software)
needs to be tracked separately from the GL because its book value
evolves on a *schedule* — independent of any single transaction.
`kontor-asset` provides:

- **`:asset` register** with a four-state lifecycle: `:planned` →
  `:in-service` → `:fully-depreciated` / `:disposed` /
  `:transferred`. Transitions are status-machine-gated; the
  transactors live in `kontor.asset.asset` (`acquire!`,
  `place-in-service!`, `dispose!`, `transfer!`, `impair!`,
  `revalue!`, `revise-useful-life!`, `record-addition!`).
- **Per-(asset, ledger) depreciation books** — the `:asset-
  depreciation` entity. One physical asset has N books (one per
  ledger: HGB + Steuerbilanz; book + tax; IFRS + local GAAP). The
  `:asset-depreciation/identity` tuple `[asset ledger]` is
  `:db.unique/identity` so a second `open-book!` for the same pair
  collides. Each book owns one ADR-032 `:schedule`.
- **`DepreciationProvider` protocol** (`kontor.asset.depreciation-
  provider`) with four built-ins: `StraightLineProvider`,
  `DecliningBalanceProvider`, `SumOfYearsDigitsProvider`,
  `UnitsOfProductionProvider`. l10n modules ship per-jurisdiction
  providers (MACRS, AfA-degressive, CCA) + the effective-dated
  `:depreciation-rule` rows; the rule is pinned at `open-book!` time
  as `:asset-depreciation/effective-rule` and never re-resolved (an
  asset's depreciation rule is fixed at acquisition for its whole
  life).
- **Runner** (`kontor.asset.runner/run-depreciation!`) — event-aware
  via the `:schedule-occurrence` log. Re-planning is prospective per
  IAS 16: a `revise-book!` call after a useful-life change re-plans
  the un-fired tail without restating fired periods. `catch-up!`
  handles mid-period onboarding. The runner STOPS at the earliest
  `:disposal` / `:transfer` event (it never depreciates past a
  terminal event — code-review).
- **Roll-forward queries** (`scheduled-depreciation`,
  `accumulated-depreciation`, `gross-carrying-amount`,
  `net-book-value`) read from `:schedule-occurrence` +
  `:asset-event`, NOT from GL postings. GL accounts are shared across
  every asset in a class + `:posting` carries no per-asset back-ref,
  so a GL sum can't be attributed to one asset. The subsystem's own
  logs are the source of truth for the roll-forward; the GL postings
  are its *consequence* (built by `kontor.asset.posting`).

  Each query mirrors one control account, and the event fold is what
  makes that true: `plan-impairment` credits the accumulated-account
  and `plan-revaluation` debits the asset-account, and neither writes
  a `:schedule-occurrence`.

  | query | = | mirrors |
  |---|---|---|
  | `scheduled-depreciation` | `:opening-accumulated` + Σ occurrences | the schedule's own plan |
  | `accumulated-depreciation` | ` + Σ :impairment` events | the accumulated account |
  | `gross-carrying-amount` | `:acquisition-cost` + Σ `:revaluation` + Σ `:addition` | the asset account |
  | `net-book-value` | gross − accumulated | the net carrying amount |

  `accumulated-depreciation` (not `scheduled-depreciation`) is what
  `plan-disposal` relieves.
- **`asset-tie-out`** (`kontor.asset.report`) — the detective control
  that keeps the two sides honest. Nothing structurally forces a
  subledger read to agree with the control accounts, so the agreement
  is MEASURED: it returns `{:subledger :gl :difference :ok?}` for
  cost + accumulated + NBV on a given (ledger, asset-account,
  accumulated-account, commodity). Run it at close.

## When to use it

- Capitalized equipment, vehicles, machinery, real estate, intangibles
- Finance-leased ROU assets (operating-lease ROU lives in `kontor-lease`)
- Anything that depreciates on a *schedule*, not a *transaction*

When NOT to use it:
- Inventory → `kontor-inventory`
- Operating leases → `kontor-lease`
- Cash / receivables → kernel ledger

## Load-bearing ADRs

- [ADR-053](../../doc/decisions.md) — `:asset` register + lifecycle
- [ADR-054](../../doc/decisions.md) — depreciation book IS a `:ledger` (ADR-021's parallel-ledger reuse)
- [ADR-055](../../doc/decisions.md) — `DepreciationProvider` protocol + runner + effective-dated rules
- [ADR-056](../../doc/decisions.md) — Jahresabschluss extensions
- Stage L′ review-fix ADRs explain the disposal safety +
  opening-accumulated + nil-commodity + NBV decisions.

## Key namespaces

- `kontor.asset.schema` — `:asset/*`, `:asset-class/*`,
  `:asset-event/*`, `:asset-depreciation/*`, `:asset-method-params/*`
  + status-transition seeds + `install!`
- `kontor.asset.asset` — asset lifecycle (`acquire!`,
  `place-in-service!`, `dispose!`, `transfer!`, `impair!`,
  `revalue!`, `revise-useful-life!`, `record-addition!`). Every
  `!` wrapper has a paired `*-tx-data` builder (ADR-068).
- `kontor.asset.depreciation` — book lifecycle (`open-book!`,
  `revise-book!`) + roll-forward queries (`scheduled-depreciation`,
  `accumulated-depreciation`, `gross-carrying-amount`,
  `net-book-value`, `book-for`, `books-of`, `asset-of`,
  `event-amount-sum`, `periods-for`)
- `kontor.asset.depreciation-provider` — protocol + four built-ins +
  `provider-for` registry resolver
- `kontor.asset.runner` — `run-depreciation!`, `catch-up!`
- `kontor.asset.posting` — `plan-capitalisation`,
  `plan-depreciation-charge`, `plan-disposal`, `plan-impairment`,
  `plan-revaluation` (pure GL-posting planners called by the lifecycle
  transactors)
- `kontor.asset.report` — `asset-roll-forward`, `asset-tie-out`,
  `pending-depreciation-issues`

## Minimal example

```clojure
(require '[kontor.asset.asset :as asset]
         '[kontor.asset.depreciation :as dep]
         '[kontor.asset.runner :as runner]
         '[kontor.asset.schema :as asset-schema]
         '[kontor.core :as k])

(def conn (k/create-test-db))
(asset-schema/install! conn)
;; ... + seed an :asset-class, ledger, accounts, journal, commodity

;; Step 1 — create the asset (status :planned by default)
(asset/acquire!
  conn {:code "MACH-001"
        :name "CNC mill"
        :class [:asset-class/code "equipment"]
        :acquisition-cost 60000.00M
        :acquisition-commodity [:commodity/symbol "EUR"]
        :acquisition-date #inst "2026-01-15"
        ;; ADR-153 — the acquirer; stamped as :kontor.audit/create-uid, which
        ;; the seeded :no-self-approval policy on disposal reads.
        :changed-by-uid "asset-manager"
        :in-service? true
        :asset-account [:account/code "0440"]      ; SKR04: Maschinen
        :accumulated-account [:account/code "0470"]; accumulated AfA
        :expense-account [:account/code "6220"]})  ; depreciation expense

;; Step 2 — open a book per (asset, ledger). One book per ledger.
(dep/open-book!
  conn {:asset "MACH-001"
        :ledger [:ledger/code "hgb"]
        :provider-id :straight-line
        :useful-life-months 84    ; 7 years, HGB AfA-Tabelle
        :start-date #inst "2026-01-15"})

;; (Optionally open a Steuerbilanz book with declining-balance over 5
;; years on the same asset — same call, different :ledger + opts.)

;; Step 3 — run depreciation. Fires every due, un-fired occurrence
;; up to :as-of (default now). Book-spec accepts a [asset ledger]
;; pair or a book eid.
(runner/run-depreciation!
  conn ["MACH-001" [:ledger/code "hgb"]]
  {:journal [:journal/code "AFA"]
   :as-of   #inst "2026-12-31"})

;; Roll-forward query — reads :schedule-occurrence + :asset-event,
;; NOT GL postings. Book-spec is an eid or an [asset ledger] pair.
(dep/accumulated-depreciation
  (datahike.api/db conn)
  ["MACH-001" [:kontor.ledger/code "hgb"]])

;; Step 4 — at close, prove the register agrees with the GL.
(require '[kontor.asset.report :as areport])
(areport/asset-tie-out
  conn {:ledger              [:kontor.ledger/code "hgb"]
        :asset-account       [:kontor.account/code "0440"]
        :accumulated-account [:kontor.account/code "0470"]
        :commodity           [:kontor.commodity/symbol "EUR"]})
;; => {:subledger {:cost … :accumulated … :nbv …}
;;     :gl        {…}
;;     :difference {…}
;;     :ok? true}
```

## What it does NOT do

- **No bundled per-country depreciation rates.** The kernel ships the
  protocol + four built-in methods; l10n modules (`kontor-l10n-de`,
  `kontor-l10n-us`) ship `:depreciation-rule` rows for AfA-Tabellen,
  MACRS, CCA, etc. — and the providers that read them.
- **No per-asset GL attribution.** Posting accounts are shared
  across the class; the per-asset roll-forward queries read the
  `:schedule-occurrence` + `:asset-event` logs instead. Nothing
  *structurally* forces the register and the control accounts to
  agree — the events are recorded by `kontor.asset.asset` and the
  postings are built separately by `kontor.asset.posting`, so a
  recorded-but-unposted event (or a posting made without a register
  fact) drifts them apart. That is why the agreement is MEASURED
  rather than asserted: run `kontor.asset.report/asset-tie-out` at
  close and read its `:difference`.
- **No automated componentization.** ADR-053 allows
  `:asset/parent-component-of` for componentization but doesn't
  decide when one asset should be split. Consumer-side.
- **No insurance / depreciation-recapture / 1031-exchange
  computations.** The substrate gives you the book values + the
  history; consumers compose the tax-event posting.
- **No revaluation policy.** `revalue!` is the transactor; the
  policy decision (IAS 16 model choice, US GAAP impairment vs.
  revaluation) is consumer-side.

## Tests

`modules/asset/test/kontor/asset/`:
- `lifecycle_test.clj` — acquire / place-in-service / dispose / transfer / impair / revalue / revise-useful-life
- `depreciation_book_test.clj` — open-book, parallel books, revise-book
- `depreciation_run_test.clj` — runner + catch-up + the disposal-safety stop
- `jahresabschluss_test.clj` — ADR-056 year-end extensions
- `tie_out_test.clj` — subledger↔GL: disposal relieves what the GL
  carries (impairment + revaluation included), pro-rata partial
  disposal, and `asset-tie-out` as a detector. Every assertion is a
  GL balance.

## License

Apache 2.0.

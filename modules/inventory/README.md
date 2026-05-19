# kontor-inventory

Physical stock ledger + facilities + reservations + cycle counts for
`kontor`. The physical / operational half of inventory; the financial
half is the kernel's `:valuation-*` family (FIFO / LIFO / WA / standard
cost via the `CostingProvider` protocol — ADR-029).

## What it does

Inventory in an ERP is two systems pretending to be one: a *physical*
ledger of where stock IS and how much, and a *financial* ledger of
what it cost. These usually drift — receipts post one but not the
other, a count adjustment hits the subledger but not the GL.
`kontor-inventory` makes the drift structurally hard:

- **`:inventory-detail` append-only signed-quantity-delta ledger**
  (ADR-057). `on-hand-qty` derives quantity-on-hand from it
  bitemporally — no stored quantity cache. The physical ledger IS
  the roll-forward; reason codes + immutability are structural.
- **Facility / location model** — `:facility` (`:warehouse`,
  `:store`, `:plant`, `:transit`, `:virtual`) + `:facility-location`
  (bin) + `:facility-product` (per-(facility, product) policy
  including the `:negative-allowed?` switch).
- **Atomic operations** (`kontor.inventory.ops`, ADR-059) —
  `receive!` / `issue!` / `transfer!` write BOTH halves in ONE
  transaction: the valuation layer + GL postings (via
  `kontor.posting/plan-stock-move` — the ADR-030 builder) AND the
  physical `:inventory-detail`, linked by `:inventory-detail/
  transaction`. The financial view CANNOT drift from the physical
  one because they are written together and the GL is only ever
  touched through `plan-stock-move`.
- **Negative-inventory policy** — refused by default. When
  `:facility-product/negative-allowed?` is true, an over-issue
  creates an explicit negative-fill `:valuation-layer` so the issue
  still has a layer to consume; `true-up-negative-fill!` reconciles
  the estimate to actual later.
- **Two-phase transfers** — `transfer!` (issue from source) →
  `complete-transfer!` (receive at destination), with `:transit`
  facilities holding the in-flight stock. Same-entity transfers are
  GL-free quantity events; cross-entity is a documented follow-up.
- **Available-to-promise + reservations** (`kontor.inventory.
  reservation`, ADR-058) — `available-to-promise` reads
  `Σ :inventory-detail/atp-diff` over a scope; a `reserve!` appends
  an `:atp-diff` negative detail (`:qoh-diff` 0 — the stock is still
  physically there, just promised). v1 ATP netting:
  `on-hand − reservations − safety-stock`. Scheduled receipts +
  in-transit are a documented follow-up.
- **Cycle counts** (`kontor.inventory.count`, ADR-060) —
  `start-count!` opens a `:physical-inventory`; `record-count-line!`
  snapshots `on-hand-qty` AS-OF the count's `:count-date` (a
  valid-time freeze, not a DB lock — concurrent picks at a later
  valid-time do not corrupt the count); `post-count!` routes every
  non-zero variance through `plan-stock-move` so shrinkage /
  found-stock is a real posted GL event.
- **FEFO `CostingProvider`** (`kontor.inventory.costing`) — first-
  expiry-first-out: FIFO with cost layers drawn in lot-expiry order
  rather than receipt order. The perishable-goods method (food,
  pharma, chemicals). The kernel ships FIFO / LIFO /
  WeightedAverage / StandardCost; FEFO is the companion's addition.
- **Reconciliation reports** (`kontor.inventory.report`) —
  `inventory-roll-forward` (opening + Σ movements = closing,
  bucketed by `:inventory-detail/source-kind`) + `valuation-tie-out`
  (asserts subledger Σ on-hand-value = GL inventory account
  balance, surfacing any delta).

## When to use it

- Stocked goods (raw materials, WIP, finished goods, merchandise)
- Multi-facility / multi-location warehouses
- Reservation-driven sales (the bridge to `modules/sales`)
- Cycle counts + physical inventories with reason-coded variances
- Perishable inventory (use FEFO via `make-fefo-provider`)

When NOT to use it:
- Services / non-stocked items — no physical inventory
- Project-cost accumulation — not the right model
- Fixed assets → `kontor-asset`
- Right-of-Use lease assets → `kontor-lease`

## Load-bearing ADRs

- [ADR-029](../../doc/decisions.md) — `CostingProvider` protocol +
  `:valuation-*` kernel family (FIFO / LIFO / WeightedAverage /
  StandardCost). Inventory consumes this; FEFO is the companion-
  shipped extension.
- [ADR-030](../../doc/decisions.md) — `plan-stock-move` GL posting
  builder. The single funnel through which the GL inventory account
  is touched.
- [ADR-057](../../doc/decisions.md) — facilities + the
  `:inventory-detail` append-only ledger + `on-hand-qty`.
- [ADR-058](../../doc/decisions.md) — `available-to-promise` +
  reservations as `:atp-diff` deltas.
- [ADR-059](../../doc/decisions.md) — `receive!` / `issue!` /
  `transfer!` atomic write-both-halves; negative-inventory policy.
- [ADR-060](../../doc/decisions.md) — cycle counts + FEFO +
  reconciliation reports.
- Research note 47 — inventory + transaction composition
  background.

## Key namespaces

- `kontor.inventory.schema` — `:facility/*`, `:facility-location/*`,
  `:facility-product/*`, `:inventory-item/*`, `:inventory-detail/*`,
  `:physical-inventory/*`, `:inventory-variance/*` + status-
  transition seeds + `install!`
- `kontor.inventory.core` — facility + location + product policy
  builders (`define-facility!`, `define-location!`,
  `define-facility-product!`); `:inventory-item` bucket lifecycle
  (`find-or-create-inventory-item!`); the low-level
  `record-detail!` + `place-opening-stock!` + `on-hand-qty`
  derivation
- `kontor.inventory.ops` — `receive!`, `issue!`, `transfer!`,
  `complete-transfer!`, `cancel-transfer!`,
  `true-up-negative-fill!`; each with a paired `*-tx-data` builder
  (ADR-068) + `seal-stock-move` posting-sealing helper +
  `provider-for-book` resolver
- `kontor.inventory.reservation` — `atp-raw`,
  `available-to-promise`, `reserve!`, `release-reservation!` +
  `*-tx-data` builders
- `kontor.inventory.count` — `start-count!`,
  `record-count-line!`, `post-count!`
- `kontor.inventory.costing` — `FefoCostingProvider` +
  `make-fefo-provider` factory
- `kontor.inventory.report` — `inventory-roll-forward`,
  `valuation-tie-out`, `in-transit-balance`

## Minimal example

```clojure
(require '[kontor.core              :as k]
         '[kontor.inventory.core    :as inv]
         '[kontor.inventory.ops     :as ops]
         '[kontor.inventory.schema  :as inv-schema]
         '[kontor.valuation         :as valuation])

(def conn (k/create-test-db))
(inv-schema/install! conn)
;; ... + seed commodity, product, accounts (inventory + COGS + variance),
;; journal, ledger, valuation-book (with :cost-method :fifo, say)

;; Step 1 — define the facility + facility-product policy
(inv/define-facility!
  conn {:code "WH-MUC" :name "Munich Warehouse" :type :warehouse})

(inv/define-facility-product!
  conn {:facility "WH-MUC"
        :product [:product/sku "WIDGET-1"]
        :negative-allowed? false})

;; Step 2 — receive goods (writes valuation layer + GL postings + the
;; physical :inventory-detail in ONE tx)
(ops/receive!
  conn {:product [:product/sku "WIDGET-1"]
        :facility "WH-MUC"
        :book [:valuation-book/code "main"]
        :qty 100M
        :unit-cost 7.50M
        :commodity [:commodity/symbol "EUR"]
        :journal [:journal/code "GEN"]
        :account-fn (fn [_] [:account/code "1400"])  ; inventory account
        :effective-date #inst "2026-01-15"})

;; Step 3 — issue (consumes layers FIFO via the book's :cost-method)
(ops/issue!
  conn {:product [:product/sku "WIDGET-1"]
        :facility "WH-MUC"
        :book [:valuation-book/code "main"]
        :qty 30M
        :commodity [:commodity/symbol "EUR"]
        :journal [:journal/code "GEN"]
        :account-fn (fn [_] [:account/code "5000"])  ; COGS account
        :effective-date #inst "2026-02-01"})

;; Roll-forward query — derived from :inventory-detail, no cache
(inv/on-hand-qty (datahike.api/db conn)
                 {:product [:product/sku "WIDGET-1"]
                  :facility "WH-MUC"})
```

## What it does NOT do

- **No bundled FIFO / LIFO / WeightedAverage / StandardCost
  providers.** Those live in the kernel (`kontor.costing-provider`
  + `kontor.valuation`). This module ships ONLY FEFO as a
  companion-shipped `CostingProvider`.
- **No cross-entity transfer GL leg.** Same-entity transfers are
  GL-free quantity events; cross-entity transfers with an
  intercompany GL leg are a documented follow-up.
- **No scheduled-receipts / in-transit ATP netting yet.** v1 ATP is
  `on-hand − reservations − safety-stock`; supply-side ATP is a
  documented follow-up.
- **No MRP / planning / demand-forecasting.** Substrate gives you
  the on-hand + ATP picture; planning is consumer-side.
- **No serial-number tracking beyond `:lot`.** Lot is the only
  granularity; per-unit serialisation is consumer-side decomposition
  into 1-quantity lots.
- **No bin-replenishment / wave-picking workflows.** Operations
  ship; workflow is a consumer concern.

## Tests

`modules/inventory/test/kontor/inventory/`:

- `stock_ledger_test.clj` — `:inventory-detail` append + `on-hand-
  qty` bitemporal derivation
- `ops_test.clj` — `receive!` / `issue!` / `transfer!` + the
  negative-inventory policy + the atomic-both-halves invariant
- `reservation_test.clj` — `available-to-promise` + `reserve!` /
  `release-reservation!`
- `count_report_test.clj` — `start-count!` / `record-count-line!` /
  `post-count!` + `inventory-roll-forward` + `valuation-tie-out`

## License

EPL-1.0.

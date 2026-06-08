# kontor-invoice

Order → invoice bridge + invoice status machine + AcctgTrans posting
for `kontor`. Adds the order-aware machinery on top of the kernel's
`kontor.invoice` (which handles non-order-aware create / send /
mark-paid / cancel).

## What it does

A sales (or purchase) invoice usually starts life as an order — items,
adjustments, ship groups — and only later becomes a GL `:transaction`.
`kontor-invoice` is the bridge between those two worlds:

- **Order → invoice construction** (`make-invoice-from-order!`,
  ADR-036). Pulls the `:order` header + `:order-item`s +
  `:order-adjustment`s; creates an `:invoice` with `:invoice/order`
  back-ref; emits one `:invoice-line` per order item + one
  `:order-item-billing` junction per (order-item, invoice-line) for
  partial-invoice arithmetic; derives adjustment lines with
  `:invoice-line/parent-line` pointing at the parent product line.
  Per-(receipt, invoice-line) junctions are emitted for the 3-way-
  match audit trail (ADR-042).
- **Invoice type discriminator** (`:invoice/type ∈ #{:sales :purchase
  :credit-memo :debit-memo}`, ADR-042). The posting bridge dispatches
  GL routing (debit vs credit, AR vs AP, revenue vs expense) off
  this. Kontor-procurement consumes `:purchase` for vendor invoices.
- **Atomic GL posting** (`kontor.invoice.posting/post-to-ledger!`,
  ADR-036). In ONE tx: builds a `:transaction` (state `:posted`,
  `:posted-at` set) + per-line `:posting` entries via
  `kontor.posting/build-transaction` (commodity required, ledger
  explicit, sum-to-zero enforced) + sets `:invoice/transaction` on
  the invoice (the canonical "posted to GL" sentinel — there is no
  `:invoice/posted-at` denorm; the timestamp lives in
  `:status-history` + `:tx/valid-from`) + writes a `:status-history`
  row for `:draft|:ready → :sent`.
- **GL account resolution** (`kontor.invoice.posting/resolve-gl-
  account`). Per-(account-type, entity) lookup with a tenant-wide
  fallback, surfaced as `:gl-account-default/*` schema.
- **Status machine** seeded into ADR-034's `:status-transition`
  table: `:draft → :ready → :sent → :paid | :cancelled`, plus the
  clearance lifecycle (`:pending-attestation → :cleared | :rejected →
  :draft`) for jurisdictions that require regulator pre-clearance
  (NF-e, NIC IRN, etc.). `make-ready!`, `mark-paid!`, `cancel!` are
  thin status wrappers; `post-to-ledger!` does the `→ :sent`
  transition as part of the posting tx.
- **Pull helpers** — `resolve-invoice`, `pull-invoice`, `lines-of`,
  `total-of`, `partial-billed-quantity` (sum of
  `:order-item-billing/quantity` for partial-invoice arithmetic).

## When to use it

- Sales invoicing where an order precedes the invoice (the standard
  O2C flow)
- Purchase invoicing where a vendor invoice is reconciled to a PO +
  receipt (the standard P2P flow, with `kontor-procurement`)
- Credit memos + debit memos against prior invoices
- Per-shipment invoicing (ship-group filtering is a documented follow-up)

When NOT to use it:
- Non-order-aware "send a bill" flows — use the kernel's
  `kontor.invoice` directly (`create!`, `send!`, `mark-paid!`,
  `cancel!`)
- Dunning + collections workflow → `kontor-collections`
- Pre-clearance envelope generation → `kontor-einvoice-de` (Factur-X
  / XRechnung) + per-country l10n modules

## Load-bearing ADRs

- [ADR-036](../../doc/decisions.md) — order → invoice bridge + the
  posting bridge + the `:order-item-billing` junction
- [ADR-042](../../doc/decisions.md) — `:invoice/type` polymorphism
  for procurement invoices + per-(receipt, invoice-line) 3-way-match
  junctions
- [ADR-034](../../doc/decisions.md) — `:status-transition` seeds
  + `record-status-change!` (kernel) for the invoice lifecycle
- [ADR-068](../../doc/decisions.md) — `*-tx-data` pure builders +
  `!` wrappers routing through `kontor.validation/transact-with-
  validation`

## Key namespaces

- `kontor.invoice.schema` — additive `:invoice/*` extensions
  (`:type`, `:order`, `:transaction`, `:lines`, …),
  `:order-item-billing/*`, `:gl-account-default/*` +
  status-transition seeds + `install!`
- `kontor.invoice.bridge` — the order → invoice public surface:
  `make-invoice-from-order!` + `make-invoice-from-order-tx-data` +
  status wrappers (`make-ready!`, `mark-paid!`, `cancel!`) +
  `post-to-ledger!` delegate + pull helpers (`resolve-invoice`,
  `pull-invoice`, `lines-of`, `total-of`,
  `partial-billed-quantity`)
- `kontor.invoice.posting` — the GL posting bridge:
  `post-to-ledger!` + `post-to-ledger-tx-data` + `resolve-gl-
  account` + `debit-credit-for` direction helpers

**NOTE**: namespace is `kontor.invoice.bridge`, NOT `kontor.invoice`.
The kernel ships its own `kontor.invoice` (`src/kontor/invoice.clj`)
for non-order-aware flows; this companion adds the order-aware
machinery on top.

## Minimal example

```clojure
(require '[kontor.core            :as k]
         '[kontor.invoice.bridge  :as inv]
         '[kontor.invoice.schema  :as inv-schema])

(def conn (k/create-test-db))
(inv-schema/install! conn)
;; ... + seed commodity, partners (seller + buyer), order with items,
;; journal, AR / revenue accounts (or :gl-account-default rows)

;; Step 1 — build an :invoice + :invoice-line rows + :order-item-billing
;; junctions from the order. Status starts at :draft.
(inv/make-invoice-from-order!
  conn "ORD-2026-0001"
  {:external-id "INV-2026-0001"
   :issue-date  #inst "2026-01-15"
   :type        :sales        ; default — derived from :order/type
   :entity      [:entity/code "acme-de"]})

;; Step 2 — finalise (lock edits) :draft → :ready (optional)
(inv/make-ready! conn "INV-2026-0001"
                 {:changed-by-uid <uid> :reason "Approved by AR clerk"})

;; Step 3 — post to GL. In one tx: builds + posts the :transaction,
;; sets :invoice/transaction, writes the :status-history row for
;; :ready → :sent.
(inv/post-to-ledger! conn "INV-2026-0001"
                     {:journal-ref [:journal/code "AR"]
                      :posted-at   #inst "2026-01-15"
                      :changed-by-uid <approver-uid>})

;; Step 4 — bank reconciliation marks :sent → :paid
(inv/mark-paid! conn "INV-2026-0001"
                {:changed-by-uid <reconciler-uid>})
```

## What it does NOT do

- **No order entity.** `:order/*` lives in `kontor-sales` (ADR-035);
  this module reads from it.
- **No tax computation.** Per-line `:invoice-line/tax-*` carries the
  tax facts produced by `TaxRateProvider` / `TaxPostingBuilder`
  (ADR-071); compute upstream.
- **No e-invoice envelope generation.** Factur-X / XRechnung /
  ZUGFeRD / NF-e / NIC IRN XML emission is in the per-country
  modules (`einvoice-de`, `l10n-br`, `l10n-in`, etc.).
- **No dunning / collections.** AR collections + dispute + bad-debt
  write-off live in `kontor-collections`.
- **No ship-group filtering yet.** `:invoice/invoice-per-shipment-
  of` accepts a `:ship-group` ref, but per-shipment line filtering
  is a documented placeholder.
- **No `:invoice/posted-at` denorm.** Presence of
  `:invoice/transaction` IS the "posted" sentinel; the timestamp
  walks `:status-history` + `:tx/valid-from`.
- **No reversal automation on `cancel!` after `:sent`.** The status
  flips, but the consumer is responsible for posting the reversal
  `:transaction` per ADR-007 sealing.

## Tests

`modules/invoice/test/kontor/invoice/`:

- `bridge_test.clj` — `make-invoice-from-order!` (whole-order +
  partial-billed arithmetic + adjustments), status transitions,
  `post-to-ledger!` end-to-end (`:draft → :sent`, GL posting shape,
  sentinel-set invariant)

## License

Apache 2.0.

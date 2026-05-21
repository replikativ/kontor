# kontor-commitment

Recognising and liquidating **obligations** — ADR-098, research note 99 Stage 4.

The general ledger records what *moved*: postings, balanced to zero (`Ker σ`). A
`:commitment` records what is *supposed to* move — a receivable a customer owes,
a payable you owe, an encumbrance you have reserved. The obligation is recorded
when it arises and liquidated as settling transactions fulfil it. Per the McComb
reading behind note 99, recognising and liquidating obligations is the half of
accounting the ledger alone cannot see.

This is a **companion module** — it does not touch the kernel. A
`:commitment-fulfillment` edge points *at* a kernel `:transaction`, but the
kernel `:transaction` gains no attribute.

## Entities

| Entity | Role |
|---|---|
| `:commitment` | the obligation — `kind` ∈ `:receivable \| :payable \| :encumbrance`, counterparty, committed/fulfilled amounts, due-date, `:commitment/state` (ADR-034 status machine) |
| `:commitment-fulfillment` | an edge: which kernel `:transaction` settled how much of which commitment |

State machine (`:commitment/state`): `:open → :partially-fulfilled → :fulfilled`;
`:open` / `:partially-fulfilled → :cancelled`.

## Usage

```clojure
(require '[kontor.commitment :as commitment])

(commitment/install! conn)   ; after kontor.schema/install!

;; record an obligation
(commitment/record-commitment!
  conn {:external-id "C-1" :kind :receivable :counterparty cust-eid
        :committed-amount 1000M :commodity eur-eid
        :due-date #inst "2026-04-01" :recorded-by-uid actor-eid})

;; link a settling transaction (partial or full)
(commitment/fulfill! conn {:commitment "C-1" :transaction tx-eid
                           :amount 1000M :recorded-by-uid actor-eid})

(commitment/open-commitments (d/db conn))   ; still-live obligations
(commitment/aging (d/db conn))              ; overdue buckets
(commitment/cancel! conn {:commitment "C-1" :changed-by-uid actor-eid})
```

Every business write follows ADR-068: a pure `*-tx-data` builder plus a `!`
wrapper that stamps `:tx/valid-from` and routes through `kontor.validation`. The
builders compose into `kontor.process` step lists.

## Conservatively scoped

`:commitment/origin` is an **opt-in soft link** to the entity an obligation arose
from — an `:order` line, a `:schedule`, a lease liability. Those modules are not
changed; the kernel does not interpret `:origin`. Unifying the several obligation
sources behind one vocabulary is a deliberately deferred later pass.

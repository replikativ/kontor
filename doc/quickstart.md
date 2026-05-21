# Quickstart — keeping a book with `kontor.book`

This is the shortest path from nothing to a closed double-entry book.
It uses only the **verb facade**, `kontor.book` (ADR-095) — eight named
verbs that each post a balanced, sealed transaction. No UI, no ERP:
kontor is a library, and this is a REPL transcript you can paste.

For *why* the verbs map onto debits and credits the way they do, read
[accounting-model.md](accounting-model.md) next. For the locked design
choices, [decisions.md](decisions.md).

## 1. Open a book

`kontor.core/create-test-db` returns a fresh in-memory datahike
connection with the kernel schema already installed (~50 ms).

```clojure
(require '[kontor.core :as core]
         '[kontor.book :as book]
         '[kontor.trial :as trial]
         '[datahike.api :as d])

(def conn (core/create-test-db))
```

## 2. Lay out the chart

A book needs a commodity, some accounts, and journals. The verbs
resolve their journal by *type* (`:sale`, `:purchase`, `:cash`,
`:general`), so transact one of each you will use. This is plain
datahike data — no kontor helper required.

```clojure
(d/transact conn
  [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}

   {:journal/code "SALE" :journal/type :sale}
   {:journal/code "PURCH" :journal/type :purchase}
   {:journal/code "CASH" :journal/type :cash}
   {:journal/code "GEN"  :journal/type :general}

   {:account/path "Assets:Cash"        :account/code "1000" :account/type :asset}
   {:account/path "Assets:Receivable"  :account/code "1200" :account/type :asset}
   {:account/path "Liabilities:Payable" :account/code "2000" :account/type :liability}
   {:account/path "Income:Sales"       :account/code "8000" :account/type :income}
   {:account/path "Expenses:Goods"     :account/code "6000" :account/type :expense}])
```

## 3. Book a full accrual cycle

Each verb takes one options map: a `:debit-account`, a
`:credit-account`, an `:amount`, a `:commodity`, and an
`:effective-date` (the bitemporal valid-time). The verb negates the
credit leg for you, so the entry sums to zero by construction.

```clojure
(def eur [:commodity/symbol "EUR"])

;; buy goods on account — Dr expense, Cr payable
(book/buy! conn {:debit-account  [:account/path "Expenses:Goods"]
                 :credit-account [:account/path "Liabilities:Payable"]
                 :amount 600 :commodity eur
                 :effective-date #inst "2026-03-01"})

;; sell on account — Dr receivable, Cr revenue
(book/sell! conn {:debit-account  [:account/path "Assets:Receivable"]
                  :credit-account [:account/path "Income:Sales"]
                  :amount 1000 :commodity eur
                  :effective-date #inst "2026-03-05"})

;; the customer pays — Dr cash, Cr receivable
(book/receive-payment! conn {:debit-account  [:account/path "Assets:Cash"]
                             :credit-account [:account/path "Assets:Receivable"]
                             :amount 1000 :commodity eur
                             :effective-date #inst "2026-03-20"})

;; pay the supplier — Dr payable, Cr cash
(book/pay-bill! conn {:debit-account  [:account/path "Liabilities:Payable"]
                      :credit-account [:account/path "Assets:Cash"]
                      :amount 600 :commodity eur
                      :effective-date #inst "2026-03-25"})
```

The eight verbs: `receive!` `pay!` `sell!` `buy!` `receive-payment!`
`pay-bill!` `transfer!` `adjust!`. Each docstring teaches its
debit/credit convention. `transfer!` moves value between two of your
own accounts; `adjust!` is the multi-leg escape hatch (below).

## 4. Read it back

A correct book always has a zero trial balance — that *is* the
double-entry invariant (`Ker σ`, see accounting-model.md).

```clojure
(trial/balanced? (trial/trial-balance conn))
;; => true

;; account balances after the cycle:
;;   Assets:Cash         400   (1000 in − 600 out)
;;   Expenses:Goods      600
;;   Income:Sales      −1000
;;   Assets:Receivable     0   (raised then settled)
;;   Liabilities:Payable   0   (raised then settled)
```

A report is a `marginalize` — partition the postings by some axis and
sum each class. Marginalizing over `:account-type` is the balance
sheet / P&L split:

```clojure
(require '[kontor.report :as report])

(-> (report/report-postings conn)
    (report/marginalize :account-type {:sign :raw})
    (update-vals (comp :amount :value)))
;; => {:asset 400M, :expense 600M, :income -1000M}   ; sums to zero
```

## 5. The multi-leg escape hatch

A correction, an accrual, a tax-split entry — anything that is not two
legs — goes through `adjust!` with an explicit `:postings` vector.
Positive amounts are debits; they must sum to zero. A posting may also
carry `:dimensions` (ADR-097 classification axes — cost centre,
project):

```clojure
(book/adjust! conn
  {:commodity eur :effective-date #inst "2026-03-31"
   :postings [{:account [:account/path "Expenses:Goods"] :amount 120
               :dimensions {:cost-center "CC-Ops"}}
              {:account [:account/path "Liabilities:Payable"] :amount -120}]})
```

## Where next

- [accounting-model.md](accounting-model.md) — how the verbs translate
  onto debits/credits, journals, and financial statements; how kontor
  relates to the algebra of accounting.
- [programming.md](programming.md) — the transact gate, status
  machines, the bitemporal substrate.
- The pure builder behind every verb is `kontor.book/entry-tx-data` —
  it composes into a `kontor.process` step list (ADR-067/068) when a
  business write must commit several things in one transaction.
- Tax, FX, commitments, payroll, per-country localization: companion
  modules under `modules/`. See [architecture.md](architecture.md).

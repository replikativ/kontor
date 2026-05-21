# The accounting model — verbs, debits, and `σ_E`

[quickstart.md](quickstart.md) shows you *how* to keep a book with the
`kontor.book` verbs. This document explains *why* it works — how the
verbs translate onto the debits and credits of established accounting,
and how kontor sits on the algebra underneath them.

It is the bridge between two audiences: a developer who knows
`kontor.book` but not double-entry, and an accountant who knows
double-entry but not what kontor is doing with it.

## 1. The verbs are debit/credit pairs

A `kontor.book` verb posts exactly one balanced transaction. There is
no hidden machinery: each verb picks a journal type and decides which
of its two accounts is debited and which is credited.

| Verb | Debit | Credit | Journal |
|---|---|---|---|
| `receive!` | where the value landed (an asset — cash/bank) | its source (an income, liability, or equity account) | `:cash` |
| `pay!` | what the payment was for (an expense, or the liability settled) | the cash/bank account | `:cash` |
| `sell!` | the receivable (or cash, if paid at once) | the revenue account | `:sale` |
| `buy!` | the expense or asset acquired | the payable (or cash) | `:purchase` |
| `receive-payment!` | the cash/bank account | the receivable being settled | `:cash` |
| `pay-bill!` | the payable being settled | the cash/bank account | `:cash` |
| `transfer!` | the destination account | the source account | `:general` |
| `adjust!` | — multi-leg `:postings`, positive = debit — | | `:general` |

`sell!`/`buy!` are the *accrual* pair — they raise a receivable or
payable. `receive-payment!`/`pay-bill!` *settle* it. `receive!`/`pay!`
are the *cash-basis* shortcut: value moving with no accrual step in
between. `transfer!` moves value between two of your own accounts.
`adjust!` is the deliberate escape hatch for everything that is not
two legs — corrections, accruals, revaluations, a tax-split entry.

## 2. Debits and credits — keep the group, drop the notation

kontor does not store the words "debit" and "credit". A `:posting` has
a **signed `BigDecimal`** `:posting/amount`: **positive is a debit,
negative is a credit**. A verb negates the credit leg, so the two legs
sum to zero.

That sum-to-zero is the whole of double-entry. In the algebra of
accounting (Ellerman's "social systems software"; the Pacioli group)
a transaction is an element of the free abelian group over
(account × commodity), and a *valid* transaction is one in the kernel
of the sum map `σ` — `Ker σ`. kontor's `validation` gate enforces
exactly this, per (entity, ledger, commodity).

This invariant is **grammar, not physics** (research note 97 §8). It
is true by construction — the two legs are two sides of one recording
act — so it is *more* certain than any conservation law, and it says
*less*: it constrains the books, never the world. The kernel
guarantees the *form* of every entry; it cannot guarantee a number is
*right*. Keep that distinction — it is why `adjust!` exists and why
valuations live in companion modules, not the kernel.

"Debit/credit" is just one *notation* for the group. kontor keeps the
group and drops the notation — the verbs are the human-facing names,
the signed amount is the representation.

## 3. The chart of accounts is a basis, not the foundation

An `:account` is a coordinate. The set of accounts is a *basis* of the
free group — a choice of axes. Traditional practice privileges this
one basis: every report keys off account numbers, and charts grow
warts (`4400 = sales-19%-domestic-region-X`) because the account
number is forced to carry classifications it was never meant to.

kontor demotes the account to **one classification axis among
several**. A posting also carries `:posting/dimensions` (ADR-097) —
flat `{axis value}` tags for cost centre, project, segment, fund, any
axis a consumer defines. The account still backs `Ker σ` (it is
mandatory, it is what balances); it is simply no longer the *only*
thing you can pivot on.

## 4. Journals and financial statements are marginalizations (`σ_E`)

A **journal** in kontor is not a book you post into — it is a tag on a
transaction (`:transaction/journal`). A **financial statement** is not
a stored artifact — it is *computed*.

Every report is a **marginalization** — `kontor.report/marginalize`,
the quotient epimorphism `σ_E` (research note 97 §3): partition the
postings by some equivalence `E`, sum within each class.

- The **trial balance** is `marginalize` over `:account` — and the
  classes sum to zero, because that is `Ker σ` again.
- The **balance sheet / P&L split** is `marginalize` over
  `:account-type`.
- A **VAT-return box**, a **cost-centre report**, a **segment report**
  — `marginalize` over the relevant axis (`:account-tags`,
  `:posting/dimensions`).

There is no separate report machine per statement: one primitive,
many axes. The historical `:account-codes` and `:tax-tags` report
engines are just instances of it (ADR-096).

## 5. Commitments — the planned tense

The ledger records what *moved*. It does not record what is *supposed
to* move: a receivable owed before payment, a payable due, an
encumbrance reserved. The `kontor-commitment` companion (ADR-098)
records and liquidates these **obligations** — recognised when they
arise, fulfilled as settling transactions discharge them. The ledger
and the commitment book are two views; neither subsumes the other.

## 6. What kontor keeps and drops from the "future of accounting"

kontor's design was cross-checked against Dave McComb & Cheryl Dunn's
*The Future of Accounting* (research notes 80, 88, 97). McComb argues
several traditional artifacts should be discarded. Reconciled against
kontor and the algebra:

| McComb would discard | What kontor actually does |
|---|---|
| debits/credits as the organizing principle | Drops the **notation**; keeps the **group** (signed amount, `Ker σ`). |
| ledgers and journal entries as books | A `:journal` is a tag, not a book; a transaction is a group element. |
| account numbers / chart of accounts as foundation | Keeps `:account` as a basis; **adds** `:posting/dimensions` so it is not the only axis. |
| reporting taxonomies | Keeps them — as *derived* `σ_E` quotients, not stored structure. |
| adjusting entries, manual accruals | Narrowed: schedules + commitments mechanize the routine ones; `adjust!` remains for genuine judgment. |
| the closing process | "Always closed" = reports are computed on read, never a human batch. Year-end rollup still exists (`kontor.closing`). |
| manual classification by bookkeepers | The `*-tx-data` builders *are* the classification — programmatic, not hand-keyed. |

The honest summary: kontor had already discarded or half-discarded
most of this list by good functional-programming design — not by
adopting a manifesto. It is **not** an "event-sourced accounting
framework": the verbs are a facade over the builders kontor already
had, and sealing (ADR-007) deliberately freezes the past rather than
keeping it re-derivable. See research note 97 for that critical
reading.

## 7. What kontor *is*, in one line

A **typed, bitemporal realization of the balance module** `Balₙ(R)` —
the kernel of the sum map over (account × commodity) — with
`kontor.process` folds, a marginalizing report engine, and an optional
verb vocabulary on top. The bitemporal axis (`:tx/valid-from` plus
datahike's tx-time) makes every report answer not just "what is the
balance" but "what did we *believe* the balance was, *as of* when."

## Further reading

- [decisions.md](decisions.md) — every locked choice. ADR-095..098 are
  this model's verbs / report engine / dimensions / commitments.
- [programming.md](programming.md) — the transact gate, status
  machines, the bitemporal substrate, in code.
- [value.md](value.md) — kontor for evaluators and stakeholders.
- Research notes 97 / 98 / 99 — the three-layer model (vocabulary /
  algebra / composition), the accounting-theory canon, and the staged
  plan this model was built from.

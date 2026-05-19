# kontor-expense

Employee expense reports for `kontor` — submit / approve / post /
reimburse, all gated by ADR-034 status machine + ADR-038
`:no-self-approval`.

## What it does

An expense report is a small approval-gated document that composes a
GL entry. The substrate is already 100% there — `:partner` is the
employee, `:audit-doc` is the receipt, ADR-034 + ADR-038 drive the
submit → approve → post lifecycle, analytic distributions carry the
cost-center, `build-transaction` composes the GL. `kontor-expense`
adds two entities + a handful of transactors and touches the kernel
not at all:

- **`:expense-report`** — submission header grouping expense lines,
  with `:expense-report/status ∈ #{:draft :submitted :approved
  :posted :reimbursed :rejected}`. ADR-034-seeded transitions.
  `:expense-report/employee` is stamped as `:create/uid` so the
  ADR-038 `:no-self-approval` rule fires on `:approved` (an employee
  cannot approve their own report).
- **`:expense-line`** — one expense (`:expense-date`, `:amount`,
  `:commodity`, `:expense-account`, `:payment-mode ∈ #{:own-account
  :company-account}`, `:cost-center`, `:receipt` ref to
  `:audit-doc`). Add via `add-line!`.
- **Lifecycle transactors** — `submit!`, `approve!`, `reject!`,
  `reopen!` (each a thin wrapper around the status machine,
  routing through the gate); the consequential edges
  (`:submitted → :approved` and rejection) require the actor's
  `:changed-by-uid` and the ADR-038 policy enforces
  separation-of-duties.
- **`post-report!`** — `:approved → :posted` in ONE tx. Builds a
  sealed `:transaction` via `kontor.posting/build-transaction`:
  each line debits its `:expense-account`; the credit legs are
  grouped by `(payment-mode, commodity)` —
    - `:own-account` → `:reimbursement-payable-account` (settled
      later by `reimburse!`)
    - `:company-account` → `:card-clearing-account` (closed by
      bank-statement matching)
  Stamps `:transaction/source = "expense-report:<code>"`; links
  `:expense-report/transaction`. Multi-commodity reports yield
  per-(payment-mode, commodity) credit legs — the GL entry is
  correct even though `:expense-report/total` (a cached convenience
  scalar) sums raw amounts and is meaningful only for single-
  commodity reports.
- **Cost-center analytic distribution** — when `:cost-center-plan`
  is supplied, a line's `:cost-center` attaches to its debit
  posting as a `:posting/analytic-distributions` row (100%).
  Without it, the cost-center stays on the line only (a documented
  follow-up).
- **`reimburse!`** — `:posted → :reimbursed` in ONE tx. For each
  `:own-account` line, builds `Dr :reimbursement-payable-account /
  Cr :cash-account` grouped per commodity. Links
  `:expense-report/reimbursement-transaction`.

## When to use it

- Employee expense reports tied to receipts (`:audit-doc`) and
  cost centers
- Mixed corporate-card + personal-card flows (the `:payment-mode`
  split routes credit legs to two different liability buckets)
- Multi-currency travel reports (the GL entry remains balanced per
  commodity; the report-level cached total is then advisory only)

When NOT to use it:
- Per-diem allowance calculation — not shipped
- Travel booking / approval workflow — consumer-side
- Mileage rate tables — consumer-side (just record amounts)
- Vendor invoice processing → `kontor-invoice` (with `:invoice/type
  :purchase`) + `kontor-procurement`

## Load-bearing ADRs

- [ADR-061](../../doc/decisions.md) — `:expense-report` +
  `:expense-line` + the submit / approve / post / reimburse
  lifecycle
- [ADR-034](../../doc/decisions.md) — `:status-transition` seeds
  + `record-status-change!` for the lifecycle
- [ADR-038](../../doc/decisions.md) — `:no-self-approval` policy
  + `:audit-doc` receipts
- [ADR-068](../../doc/decisions.md) — every business write has a
  paired `*-tx-data` builder + `!` wrapper routing through
  `kontor.validation/transact-with-validation`

## Key namespaces

- `kontor.expense.schema` — `:expense-report/*`, `:expense-line/*`
  + status-transition seeds + approval-policy seeds + `install!`
- `kontor.expense.core` — resolution / pulls (`by-code`,
  `resolve-report`, `lines-of`, `report-total`, `pull-report`) +
  lifecycle transactors (`create-report!`, `add-line!`, `submit!`,
  `approve!`, `reject!`, `reopen!`, `post-report!`, `reimburse!`),
  each with a paired `*-tx-data` builder

## Minimal example

```clojure
(require '[kontor.core           :as k]
         '[kontor.expense.core   :as expense]
         '[kontor.expense.schema :as expense-schema])

(def conn (k/create-test-db))
(expense-schema/install! conn)
;; ... + seed commodity, employee :partner, expense accounts,
;; journal, reimbursement-payable + card-clearing accounts, manager
;; :create/uid

;; Step 1 — draft the report
(expense/create-report!
  conn {:code "EXP-2026-0042"
        :employee [:partner/external-id "alice"]
        :report-date #inst "2026-05-15"
        :commodity [:commodity/symbol "EUR"]})

;; Step 2 — add lines
(expense/add-line!
  conn {:expense-report "EXP-2026-0042"
        :expense-date #inst "2026-05-10"
        :amount 120.50M
        :commodity [:commodity/symbol "EUR"]
        :expense-account [:account/code "6740"]   ; Reisekosten
        :payment-mode :own-account
        :receipt <receipt-doc-eid>
        :cost-center [:analytic-account/code "MUC-Engineering"]})

;; Step 3 — submit then approve (different actors!)
(expense/submit! conn "EXP-2026-0042"
                 {:changed-by-uid <alice-uid>})

(expense/approve! conn "EXP-2026-0042"
                  {:changed-by-uid <manager-uid>     ; NOT alice
                   :supporting-doc <approval-doc>})

;; Step 4 — post to GL (one tx: GL transaction + status change)
(expense/post-report!
  conn {:expense-report "EXP-2026-0042"
        :journal [:journal/code "EXP"]
        :reimbursement-payable-account [:account/code "1700"]
        :cost-center-plan [:analytic-plan/code "cost-centers"]
        :changed-by-uid <manager-uid>})

;; Step 5 — pay the employee (own-account lines only)
(expense/reimburse!
  conn {:expense-report "EXP-2026-0042"
        :journal [:journal/code "BANK"]
        :cash-account [:account/code "1200"]
        :reimbursement-payable-account [:account/code "1700"]
        :changed-by-uid <ap-clerk-uid>})
```

## What it does NOT do

- **No per-diem calculation.** The user spec mentioned per-diem; the
  implementation does not. Add per-diem amounts as ordinary expense
  lines, or model the per-diem rate table in a consumer-side
  module.
- **No mileage rate tables.** Record the computed amount; the
  per-jurisdiction rate (e.g. DE 0.30 €/km, US IRS rate) is
  consumer-side.
- **No travel-booking / pre-approval workflow.** Booking lives in
  a consumer's travel system; this module is the post-trip
  expense-report flow.
- **No FX-rate fetch.** A multi-currency report's lines carry
  their raw `:commodity`; the post-time conversion to a single
  presentation currency is consumer-side (use `kontor.fx`).
- **No automated cost-center allocation.** A line's
  `:cost-center` is split 100% to that analytic account; multi-
  way splits (50/50 across two cost centers) are a documented
  follow-up.
- **No automated receipt OCR / extraction.** `:receipt` is a ref
  to `:audit-doc`; the receipt's storage + extraction is consumer-
  side.

## Tests

`modules/expense/test/kontor/expense/expense_test.clj` — single
file covering create / add-line / submit / approve / reject / post /
reimburse, the `:no-self-approval` policy, the per-(payment-mode,
commodity) credit-leg grouping, and the cost-center analytic
distribution.

## License

EPL-1.0.

---
title: Banking as a consumer — can kontor be the substrate for a commercial bank's books?
date: 2026-05-15
agent: general-purpose
status: research note
related-adrs:
  - ADR-005 (TaxProvider)
  - ADR-007 (sealing + purge as recorded commit)
  - ADR-008 / ADR-048 (bitemporal: tx/valid-from on the writing tx)
  - ADR-010 (scope boundaries — what we are not)
  - ADR-021 (parallel ledgers)
  - ADR-027 (parallel valuation books)
  - ADR-031 (`:entity` for transnational books)
  - ADR-032 (`:schedule` for recurring postings)
  - ADR-034 / ADR-038 (status machine + audit-doc / approval-policy)
  - ADR-037 (kontor as a business operating system)
  - ADR-039 (bank-account master data, partner-bank-account)
  - ADR-043 (collections + payment-application)
  - ADR-053..055 (kontor-asset: schedule-driven engine, depreciation as a ledger)
  - ADR-062..064 (kontor-lease: contract → schedule → postings)
references:
  - Apache Fineract — `fineract-accounting/.../journalentry/domain/JournalEntry.java`,
    `fineract-loan/.../domain/Loan.java`, `fineract-savings/.../SavingsAccountTransaction.java`
  - Fineract CN Accounting wiki — Ledger / Account / Entry / Journal model
  - Fineract Accrual Accounting wiki — Loan Portfolio / Receivable / Income chart
  - Modern Treasury — "How to Scale a Ledger" parts III-V; transaction-status-and-balances docs
  - Square Books — `developer.squareup.com/blog/books-an-immutable-double-entry-accounting-database-service/`
  - Formance Numscript — `formance.com/blog/engineering/defining-double-entry`
  - Stripe Treasury — `docs.stripe.com/treasury` (FinancialAccount, ReceivedCredit, OutboundPayment)
  - Increase API docs (Bank as a Service primitives)
  - ISO 20022 catalogue — `pacs.008`, `pacs.009`, `pain.001`, `camt.052/053/054`; CPMI harmonised data requirements (BIS d218.pdf)
  - Federal Reserve — Fedwire Funds Service ISO 20022 implementation (frbservices.org), Operating Circulars 5 & 6
  - Basel III — `bis.org/bcbs/publ/d424_hlsummary.pdf`; FFIEC 031 / 041 RC-R Part II
  - SDK.finance — product ledger vs general ledger architectural pieces
---

# Banking as a consumer — can kontor be the substrate for a commercial bank's books?

## 1. Bank substrate summary

A bank's books are not one ledger. The reference implementations all factor them
into roughly three layers, in increasing distance from the customer:

1. **Position / portfolio ledger (sub-ledger).** One row per economic
   position — a customer's checking balance, a loan's principal-outstanding +
   accrued-interest + fee balances, a fixed-deposit's maturity ladder, a
   derivative's notional + mark. Apache Fineract names these explicitly:
   `m_savings_account` + `m_savings_account_transaction` for deposits, `m_loan`
   + `m_loan_transaction` + `m_loan_repayment_schedule_installment` for loans,
   each with its own lifecycle (`SUBMITTED → APPROVED → ACTIVE → CLOSED_OBLIGATIONS_MET`
   / `WRITTEN_OFF`). Stripe Treasury, Modern Treasury, and Increase mirror this:
   a `FinancialAccount` / `LedgerAccount` is the position row, an
   `OutboundPayment` / `LedgerTransaction` / `ReceivedCredit` is the event.

2. **General ledger.** The five-bucket (`asset/liability/equity/income/expense`)
   double-entry book that gets summed into the regulatory balance sheet and
   P&L. **Every position event posts a derived GL pair**: Fineract's accrual
   chart routes a loan disbursement to `Dr Loan Portfolio / Cr Fund Source`,
   a repayment to `Dr Cash / Cr Loan Portfolio + Cr Receivables-Interest`,
   accrual to `Dr Receivables-Interest / Cr Interest-Income`, write-off to
   `Dr Losses-Written-Off / Cr Loan-Portfolio`. The GL is what the auditors
   and Basel III RWA report read; the position ledger is what tellers, ATMs,
   and statements read.

3. **Settlement / payment-rail layer.** ISO 20022 messages (`pacs.008` for
   customer credit transfer, `pacs.009` for FI-to-FI, `camt.053` end-of-day
   statement) carry an `EndToEndId` that the bank must preserve from the
   originator's `pain.001` all the way to the recipient's `camt.053` line.
   On a Fedwire leg, the actual settlement is a write to the Fed's books
   (Operating Circular 6) and a paired write to the participant's master
   account; the bank's internal ledger writes a third pair against its Fed
   nostro. RTGS gross-settles each payment individually; netting systems
   (ACH, card networks) accumulate positions and settle aggregate net.

**Audit & versioning.** Every public reference (Square Books, Modern
Treasury, Fineract) lands on the same shape: **journal entries are append-only,
corrections are reverse-and-repost, balances are derived but optionally
denormalized for read perf.** Modern Treasury's contribution is naming the
three balances (`pending_balance`, `posted_balance`, `available_balance`) and
the transaction lifecycle (`pending → posted | archived`). Fineract carries
`reversed:boolean` + `reversalJournalEntry:ref` directly on the journal-entry
row.

**Bitemporal touchpoints.** Banks distinguish **value date** (when the customer
gets/loses interest from this fact) from **booking date** (when the bank
recorded it). A backdated correction must respect the closed period — Modern
Treasury captures this with `effective_at` "preserves original postings; new
entry adjusts in the current period". IFRS 9 stage migrations, Basel RWA
restatements, and FFIEC call-report re-filings exercise these axes
constantly.

**Reconciliation.** Two reconciliations dominate: (i) internal position ↔
internal GL (a daily trial balance must reconcile every product-ledger
balance against its mapped GL accounts — Fineract's `m_acc_gl_journal_entry`
is queried by `entityType` + `entityId` for exactly this), and (ii) internal
GL ↔ external counter-party (nostro/vostro to correspondent bank, omnibus
account at the Fed, card network funding accounts). ISO 20022 `EndToEndId`
is the join key for the latter.

## 2. kontor coverage map

| Bank concern | Fit in kontor | Mapping / notes |
|---|---|---|
| GL accounts (asset/liability/income/expense) | **Covered.** `:account/*` with `:account/type` already supports this; `:account-tag` provides report-time rollup; `:account/external-codes` (ADR-019) handles FFIEC line items + regulator codes side-by-side. |
| Per-customer current/savings balance | **Extension.** Two options: (a) one `:account` per customer-product (the Fineract approach scales to ~10⁶ entities; datahike's AVET indexes handle this), or (b) a `:position/*` namespace where each row carries `:position/partner` + `:position/product`. (a) reuses every existing kontor primitive (balance, trial-balance, sealing) — recommend this for v1, only break out a `:position` entity if cardinality or per-position metadata pressure demands. |
| Loan + repayment schedule | **Mostly covered via composition.** A loan is a `:partner` + `:account` pair (or a new `:loan` entity in a companion) plus a `:schedule` (ADR-032 already drives lease amortization in ADR-063, asset depreciation in ADR-055, revrec, subscription — the exact `kontor-asset`/`kontor-lease` pattern transfers). Disbursement = a `build-transaction` posting; repayment = another; the lifecycle uses `:status-history` (ADR-034). The schedule's `:schedule/origin-entity` generic ref points at the loan. |
| Accrual postings (interest, fees, penalties) | **Covered.** Each accrual is one `:schedule-occurrence` firing a tx with the standard Fineract pair. `kontor-asset`/`kontor-lease` already demonstrate this. |
| Bitemporal value-date vs booking-date | **Covered.** ADR-048's `:tx/valid-from` is exactly "value date"; datahike's `:db/txInstant` is "booking date". Backdated corrections wrap with `kbt/with-vt`; period locking (`kontor.period`) enforces closed-day immutability — Modern Treasury's `effective_at` semantics map 1:1. |
| Sealing posted entries | **Covered.** ADR-007 + `kontor.sealing` already enforce reverse-and-repost. Matches Fineract `reversed` + `reversalJournalEntry` shape; matches Modern Treasury's `posted → immutable`. |
| Pending vs posted balance | **Extension.** Modern Treasury's three-balance read does not exist in kontor — `kontor.balance` returns one balance per (account, commodity). A small extension: `balance` takes an optional `:include-pending?` flag and reads postings whose parent `:transaction/state` is `:draft`. Alternatively, push pending balances to a separate `:position` row updated by status-machine side-effect-intents (ADR-041). Cheap. |
| Parallel ledgers (regulatory vs management vs IFRS) | **Covered.** ADR-021 is precisely the SAP-`RLDNR`-per-line pattern; for a bank this carries IFRS-9-vs-local-GAAP loan loss provisioning, tax-book vs statutory, and a "regulatory" ledger that strips out fair-value-through-OCI for RWA roll-up. |
| Multi-entity (group, branches, foreign subs) | **Covered.** ADR-031 `:entity` already exists; cross-entity intercompany clearing already implemented in showcase 04. Each branch / cost-center can also use the `:cost-center` analytic plan (ADR-032). |
| Multi-currency (FX revaluation, CTA, dual-currency accounts) | **Mostly covered.** `Money` (`BigDecimal + commodity`) is per-posting; FX revaluation produces additional postings against revaluation accounts; CTA on consolidation lives in a planned `kontor-consolidation` module. Same-account-multi-currency (a customer's USD+EUR sub-balances) needs the *position-per-currency* convention — one position row per `(partner, product, commodity)`. |
| Payment-rail message handling (ISO 20022) | **Sibling module.** Belongs in `kontor-payments` or `bank-payments-iso20022`: a transmitter/parser that turns a `pacs.008` into a tx-data the kernel ingests, and turns a tx-data into an outbound `pain.001`. Out of kernel — same boundary as e-invoice partner adapters (ADR-017). `EndToEndId` lives as `:transaction/external-id` (already exists) for the reconcile-by-join story. |
| Reconciliation (camt.053 ↔ ledger) | **Covered for the v1 SMB shape.** `kontor.reconciliation` + `:bank-line` already does CSV/camt-shaped statement ingest with subset-sum matching (`reconciliation.clj:8-47`). A bank's nostro/vostro reconciliation runs at higher volume but uses the same primitive — feed every camt.053 line into `:bank-line`, match by `EndToEndId`, post. |
| AML / KYC | **Sibling module.** `kontor-kyc` is the natural shape — partner has `:partner-tag` (ADR-039), risk scoring + transaction monitoring lives outside the kernel because it's *behavioral* not *bookkeeping*. The kernel's `:audit-doc` (ADR-038) hosts the KYC document; the kernel's status-machine drives the partner onboarding state. |
| Basel III RWA / regulatory reporting | **Sibling module.** RWA is a *query* over the GL + position layer that classifies each exposure by counterparty risk-weight. Build it as a `kontor-basel` companion using `kontor.report` (declarative engine) + `:account-tag` for regulatory categorization. Per ADR-010 ("not a US sales tax engine"; "not a Peppol AP") — same boundary. |
| Real-time intraday liquidity / IPS settlement | **Show-stopper, sort of.** kontor's writes go through `transact-with-validation` which is single-threaded per connection and runs the invariant suite. A high-throughput payments engine (>1000 TPS) needs an event-log layer in front of kontor that batches into kernel commits. *Not a structural conflict* — the bank's position keeper runs in front, the GL settles in batches behind. The pattern is industry standard (the "product ledger feeds the general ledger" line from sdk.finance is the exact framing). |
| Derivatives revaluation (intraday mark-to-market) | **Show-stopper for direct fit.** Mark-to-market wants frequent updates to a *single* position with the GL recording deltas. kontor's sealing rule forbids in-place edits. The reverse-and-repost shape works but is verbose at 1Hz. Recommendation: hold marks in a `:position/valuation` sub-table (separate from postings); flush nightly P&L into the GL via a `:schedule` of revaluation postings. |
| Fractional reserves / reserve requirements | **Covered.** This is a *report* on the GL ("cash + due-from-Fed ≥ required-reserve-ratio × demand-deposits"), not a posting invariant. `kontor.report` can express it. |
| Withholding tax on interest | **Covered.** ADR-040's withholding primitives already cover this pattern (Indian TDS, Brazilian INSS); the `TaxProvider` protocol generalizes to bank-interest withholding. |
| Intraday settlement / value-dated reversal of a Fedwire | **Edge case.** kontor's status-history + bitemporal axes can represent it (a pacs.002 status update flips `:transaction/state` to `:settled` with `:tx/valid-from` = settlement-time), but the throughput profile (microseconds, 24/7) is not what kontor optimizes. Same answer as derivatives: position keeper in front, kontor as the journal-of-record behind. |

## 3. Verdict + recommendation

**Yes, with discipline. Bet on kontor as the journal-of-record + sub-ledger,
NOT as the real-time position keeper or payments switch.**

The fit is much better than the question framing suggests. Banking's
double-entry GL, accrual schedules, parallel books, multi-entity group
structure, value-date-vs-booking-date bitemporality, sealing, status machine,
and audit-doc/approval-policy story all map directly onto primitives kontor
already ships. The Fineract accounting model (`m_journal_entry` with
`reversed` + `reversalJournalEntry`, accrual chart for the loan lifecycle)
is structurally identical to what `kontor.posting` + ADR-007 + `:schedule`
produce — only the table names differ. Modern Treasury's effective_at /
posted_at semantics are an exact dual of ADR-048's `:tx/valid-from` plus
the period lock. Square Books' "append-only, compensating-transaction
corrections" is ADR-007 verbatim.

**The minimum kontor would need to add to host a bank:**

1. **A `kontor-banking` companion module.** New entities:
   `:bank-product/*` (deposit-product, loan-product — like Fineract's
   `m_savings_product` + `m_loan_product`, parameterizing
   interest-method/charge-set/accounting-rules), `:loan/*` (origin entity for
   the schedule + status machine), optional `:position/*` if the
   one-account-per-customer-product approach breaks down at scale.
   Pattern is the existing `kontor-asset` / `kontor-lease` pattern; the
   companion writes `:schedule` + `:status-history` + `kontor.posting`
   tx-data — no kernel changes.

2. **A `BankingProvider`-shaped protocol seam** (ADR-005-style) for
   product-specific accounting rules — what Fineract's
   `m_product_loan_accounting_mapping` table encodes. Each bank product
   declares its (event-type → DR-account, CR-account) rules; the engine
   emits the postings. Same shape as `TaxProvider`, `CostingProvider`,
   `EInvoiceProvider`, `LeaseProvider`, `DepreciationProvider`.

3. **A pending-vs-posted balance read.** Tiny extension to `kontor.balance`
   that filters by parent `:transaction/state`. Already implementable
   today; just not the default read.

4. **Sibling modules, never bundled in the kernel**:
   - `kontor-payments-iso20022` — pacs/pain/camt parsing + emission +
     `EndToEndId` reconciliation. Builds on `kontor.reconciliation` + the
     `:bank-line` primitive.
   - `kontor-kyc` — partner KYC document register, transaction monitoring
     hooks, hold/freeze status flips.
   - `kontor-basel` — RWA classification + FFIEC 031 / Common Reporting
     line-item engine using `kontor.report`.

**What kontor should NOT try to be**, per ADR-010 discipline: a real-time
position keeper, a settlement switch, an HSM, a card-network gateway, an
AML decision engine. Those are real-time event-driven systems with
different SLAs. The standard core-banking architecture (`sdk.finance`'s
"product ledger feeds the general ledger") is the industry's
acknowledgement of this split, and the right answer for kontor.

**Show-stoppers to call out honestly:**

- *Throughput.* Single-connection `transact-with-validation` is fine for a
  community bank's daily roll-up; not fine for a card-issuer's hot path.
  Companion architectures (Fineract's `m_acc_gl_journal_entry` is also
  written by a batch posting job, not synchronously by each ATM swipe) are
  the precedent. Not a substrate conflict.
- *Same-account-multi-currency.* The "one ledger row per (partner, product,
  commodity)" workaround is fine semantically but creates UX friction for
  the multi-currency-wallet pattern (Wise-style). If kontor ends up
  hosting a neo-bank, add `:position/*` as a first-class entity and let it
  carry per-commodity sub-balances. This is the one place a future
  ADR-banking-001 is genuinely earning its keep.
- *Intraday derivatives mark-to-market.* Don't try. Hold positions
  off-book; reconcile EOD.

**Companion or fork?** Companion. A `kontor-banking` artifact (modules/
banking) following the `kontor-asset` / `kontor-lease` pattern is the
right shape: schema additions live in the module, the kernel stays
narrow, and a single tenant can compose `kontor + kontor-banking +
kontor-l10n-{country}` without forking. The kernel's existing seams
(parallel ledgers, valuation books, schedules, status machine, audit-doc,
TaxProvider) carry the load. The case for a fork would only arise if the
*sum-to-zero* invariant or the *sealing* rule conflicted with how bank
books actually work — and neither does. Banks' books sum to zero just
like everyone else's; their corrections reverse-and-repost just like
everyone else's. The novelty is volume, latency, and the rail-message
surface — none of which belong in the kernel anyway.

**Recommendation: spike `kontor-banking` as Stage R after the current
process / authz / lease wave settles.** The research-before agents should
target Fineract's accounting + loan + savings modules at file:line depth
(the `m_acc_*` and `m_loan_*` tables), the Mifos accounting rules
configuration, Stripe Treasury's `FinancialAccount` lifecycle, and
Modern Treasury's `effective_at` semantics for backdated corrections.
A four-stage rhythm — research, implement, review, user-story validation
on a "community bank with 1k customers + 50 loans + camt.053
reconciliation" showcase — keeps the substrate honest without committing
to neobank scale prematurely.

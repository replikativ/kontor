---
date: 2026-05-15
agent: general-purpose
status: research-note
topic: Is the claim "modern ERPs have moved away from double-entry internally" true?
related: [doc/decisions.md ADR-021, src/kontor/posting.clj, src/kontor/schema.clj]
---

# 52 — Single-entry vs. double-entry across real systems

Short answer up-front: **the claim is wrong about the semantics and only weakly true about the physical schema.** Every production accounting/ledger system surveyed below — classical ERPs (Odoo, Tryton, SAP S/4HANA, NetSuite), SMB cloud (Xero, QuickBooks Online), and modern fintech ledger services (Modern Treasury, Fragment, Stripe Treasury, Square Books, Increase) — enforces a strict double-entry sum-to-zero invariant on every committed transaction. What has changed is the **physical column layout** and the **enforcement boundary**, not the semantic model. Below is the evidence with citations into files and public docs.

## 1. What modern systems actually do

### Classical ERPs

- **Odoo 19** (`/home/christian-weilbach/Development/odoo/addons/account/models/account_move_line.py`). `account.move.line` carries **three** stored Monetary fields side-by-side: `debit` (L115), `credit` (L120), `balance` (L125). `debit` and `credit` are computed from `balance` by `_compute_debit_credit` (L727 — `line.debit = balance if balance > 0 else 0; line.credit = -balance if balance < 0 else 0`). A DB-level CHECK constraint `_check_credit_debit` (L463) enforces `credit * debit = 0` — at most one side per line. **Sum-to-zero is enforced in application code**, not the DB: `account_move._check_balanced` (L2762) is a context manager wrapped around every create/write (L3856, L3935, L4852) that runs `_get_unbalanced_moves` (L2781) which executes `SELECT … HAVING ROUND(SUM(balance), …) != 0`. So Odoo is **both** physically split-debit/credit AND a redundant signed-`balance` column — it has its cake and eats it.

- **Tryton 7** (`github.com/tryton/account/blob/develop/move.py`). `account.move.line` declares `debit = Monetary("Debit", …, required=True)` and `credit = Monetary("Credit", …, required=True)` as two separate fields. The 7.0 design doc states "Only account moves whose line's debits and credits are balanced can be posted." Pure split-debit/credit, application-enforced balance.

- **SAP S/4HANA Universal Journal (ACDOCA).** The 2015 consolidation of `BSEG`/`BKPF`/`COEP`/etc. into one ~360-column line-item table. Each line carries a single amount per currency-type (`HSL` local, `WSL` document, `KSL` group, `OSL` index-based) plus the `SHKZG` debit/credit indicator (`S` = Soll/debit, `H` = Haben/credit). Amounts in `HSL/WSL/KSL` are **stored positive**; the sign comes from `SHKZG`. Universal Journal kept double-entry semantics intact: every accounting document still has `SUM(WSL where SHKZG='S') = SUM(WSL where SHKZG='H')`. What SAP eliminated was the *header/line + sub-ledger duplication*, not double-entry.

- **NetSuite.** Per Oracle's NetSuite Connect docs, `transaction_lines.AMOUNT` is **one signed column**. There is no `debitAmount` / `creditAmount` field — those are derived in SuiteQL via `GREATEST(amount, 0) AS debitamount, GREATEST(-amount, 0) AS creditamount`. Multi-book postings carry a `book_id` and one row per book per line, mirroring ACDOCA's `RLDNR`. Sum-to-zero per transaction per book is invariant.

### SMB cloud APIs

- **QuickBooks Online**. `JournalEntryLineDetail.PostingType` ∈ {`Debit`, `Credit`} + `Amount` (unsigned). The line carries direction + magnitude, not a signed amount; per-entry balance is enforced.
- **Xero**. `ManualJournalLine.LineAmount` is a *signed* number (positive = debit, negative = credit). `JournalLine.NetAmount`/`GrossAmount` are signed. One column, double-entry semantics preserved.

### Modern fintech "ledger-as-a-service"

- **Modern Treasury**. `LedgerEntry { amount: integer (smallest unit), direction: 'credit'|'debit', ledger_account_id, status }`. From the docs: *"to create a ledger transaction, there must be at least one credit ledger entry and one debit ledger entry, and the sum of all credit entry amounts must equal the sum of all debit entry amounts."* The product page literally lists "Double-entry" as a feature.
- **Fragment.dev**. Markets itself as "the database for money", explicitly double-entry: *"A double-entry ledger is an accounting system that always keeps your books balanced by recording every transaction in two places."* The schema is GraphQL with `LedgerEntry` types that the user defines per financial event; balance is enforced per posted entry.
- **Stripe Treasury / payments** publishes `balance_transactions` which are signed-amount, but internally Stripe also runs a double-entry ledger (Stripe Engineering's "Online migrations at scale" and "Ringing in our Lambda" posts both reference it).
- **Square Books** (Developer Square blog, Oct 2019): *"Books, an immutable double-entry accounting database service."* Explicitly double-entry, immutable, append-only. Used by Cash App, Square Capital, Caviar.
- **Increase**. Their developer docs describe `Transaction` and per-account entries; community write-ups confirm internal double-entry with `direction: DEBIT|CREDIT` and amount.

### Text-based and academic

- **GnuCash / Beancount / hledger / ledger-cli** — all explicit double-entry; Beancount uses one signed `amount` per posting with sum-to-zero per transaction. This is exactly kontor's shape.
- **REA (McCarthy 1982)**. *Genuinely* tries to replace double-entry with a Resource-Event-Agent graph. Adoption: per Wikipedia and the AAA monograph, *"REA is a popular model in teaching AIS, but it is rare in business practice."* Workday, IBM Scalable Architecture for Financial Reporting, and ISO 15944-4 are the exceptions cited. **Not in mainstream ERPs.**
- **Event-sourcing** (Greg Young / Fowler). Event-sourced ledgers are a *storage style*; the canonical projection of those events is still a double-entry ledger. Event-sourcing complements double-entry, it does not replace it.
- **Triple-entry / blockchain** (Boyle, Grigg). Adds an external commitment to the pair; the internal pair is still double-entry.
- **"Single-entry" tools** (Wave free tier for sole proprietors, YNAB, Mint, Excel cash-flow templates). These are cash-flow trackers, not GAAP/IFRS-auditable books. The moment a real balance sheet is required, they fall back to double-entry (Wave's paid tier is double-entry).

## 2. The claim under examination — verdict

**False as stated.** Every system that produces auditable financial statements enforces double-entry semantics. **Partially true** on the physical schema axis: the industry trajectory is

```
two columns (debit, credit, both ≥ 0)  →  one signed amount + direction tag  →  one signed amount
```

Odoo and Tryton still carry split debit/credit (1980s-ERP heritage). SAP keeps a direction tag (`SHKZG`) plus unsigned magnitude. NetSuite, Xero, and most fintech ledgers (Modern Treasury, Square, the OSS designs surveyed) collapsed to a single signed column. **None of them weakened the sum-to-zero invariant.** Enforcement uniformly lives at the application layer (Odoo's `_check_balanced`, Modern Treasury's transaction validator, Fragment's schema engine, Square Books's immutable service) — the DB enforces per-line constraints only.

There is one genuinely-single-entry pattern in production: **subledger systems** (inventory in/out, time tracking, telematics meters, message counters) that emit *one* row per event and project into the GL via a periodic summary posting. This is universal in ERPs (Odoo's `stock.move` → `account.move`, SAP's MM → FI, NetSuite's item-receipt → GL). It is not "moving away from double-entry"; it is the long-standing pattern that double-entry lives at the GL boundary, and subledgers feed it.

## 3. kontor verdict

kontor's `:posting/amount` is a single signed BigDecimal (schema.clj L1735–1740: *"Signed amount in :posting/commodity. Positive = debit, negative = credit. Postings within a transaction must sum to zero per commodity."*). Sum-to-zero is enforced at the application layer in `kontor.posting/validate` (posting.clj L237–297), with the invariant strengthened per ADR-021 to **sum-to-zero per (ledger, commodity)** and per ADR-031 to **per (entity, ledger, commodity)** in multi-entity mode. `kontor.balance/account-balance` sums the signed amounts directly — no debit/credit split anywhere downstream.

This places kontor squarely in the modern camp: **same physical shape as NetSuite, Xero ManualJournalLine, Modern Treasury, Square Books, Beancount, hledger.** The two-column Odoo/Tryton shape is the older convention; the one-column-signed shape is what every greenfield ledger built in the last decade chose. ADR-021's rejection of SAP's 10-currency-column wide row in favor of "FX translation produces additional postings" is the same event-sourced instinct.

There is no kontor-ism here. The double-entry choice is **table-stakes for auditable books**, not a stylistic preference, and the one-signed-column representation is convergent with the modern fintech ledger consensus. The semantic model could not be relaxed without breaking GAAP/IFRS reporting; the physical model is already at the lean end of the spectrum.

Two adjacent things kontor does that are *not* universal but are well-justified:

1. **`:posting/ledger`** per posting (ADR-021). SAP/NetSuite/Oracle do this; Odoo does not. The right enterprise-grade choice.
2. **Bitemporal `:tx/valid-from`** on every tx (ADR-008/048). SAP's audit story does this with parallel `BKPF`/`BSEG` snapshots; nobody else surveyed exposes it as a first-class query axis. This is a kontor differentiator, not a deviation from double-entry.

## Sources

- Odoo source: `/home/christian-weilbach/Development/odoo/addons/account/models/account_move_line.py` L115, L120, L125, L463; `account_move.py` L2762, L2781.
- Tryton source: `https://github.com/tryton/account/blob/develop/move.py` Line class.
- Tryton design doc: `https://docs.tryton.org/7.0/modules-account/design/move.html`.
- SAP ACDOCA: `https://www.erpexplorer.com/sap/s4/table/ACDOCA`, `https://saplearners.com/sap-tables/acdoca/`.
- NetSuite: `https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_4400769955.html`.
- QuickBooks Online: `https://developer.intuit.com/app/developer/qbo/docs/api/accounting/all-entities/journalentry`.
- Xero: `https://developer.xero.com/documentation/api/accounting/manualjournals` and `https://github.com/XeroAPI/xero-php-oauth2/blob/master/lib/Models/Accounting/JournalLine.php`.
- Modern Treasury: `https://docs.moderntreasury.com/platform/reference/ledger-entry-object`, `https://docs.moderntreasury.com/docs/guide-to-debits-and-credits`.
- Fragment: `https://fragment.dev/docs`, `https://fragment.dev/docs/design-your-ledger`.
- Square Books: `https://developer.squareup.com/blog/books-an-immutable-double-entry-accounting-database-service/`.
- REA: `https://en.wikipedia.org/wiki/Resources,_Events,_Agents`, McCarthy 1982 PDF.
- kontor: `src/kontor/schema.clj` L1735–1740, `src/kontor/posting.clj` L237–297, `doc/decisions.md` ADR-021 + ADR-031.

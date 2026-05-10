# Period close / reopen / posting authorization — comparative review

Scope: assess whether `datahike-accounting`'s
`/home/christian-weilbach/Development/datahike-accounting/src/datahike_accounting/period.clj`
covers the 80% case before Phase 2-DE, by comparing against six production
systems. URLs and `file:line` refs cited inline.

## Our model in one paragraph

`:period` is a half-open `[start, end)` row, optionally scoped to a single
`:period/journal`. Setting `:period/locked-at` is the *only* lock state.
`assert-not-in-locked-period!` rejects any posting whose effective date
falls inside a matching closed period (`period.clj:104-140`). `close!` is
idempotent-rejecting (`period.clj:157-180`); `reopen!` simply retracts
`:period/locked-at` and is itself a recorded tx so the audit chain documents
it (`period.clj:182-191`). No fiscal year, no special periods, no soft/hard
distinction, no per-user exception, no role check.

## Vendor walk-through

### 1. Odoo 19 (read locally)

Odoo has **five** lock dates on `res.company`
(`addons/account/models/company.py:59-104`):
`fiscalyear_lock_date`, `tax_lock_date`, `sale_lock_date`,
`purchase_lock_date`, `hard_lock_date`. The first four are *soft* — an
`account.lock_exception` row (per-user, time-bounded, with `reason`) can
move the effective lock back for a single user
(`account_lock_exception.py:11-96`, `_get_user_lock_date`,
`company.py:597-630`). `hard_lock_date` is monotonically increasing,
cannot be removed, has no exception mechanism, and refuses to be set if
draft entries or unreconciled bank lines exist in the range
(`company.py:559-595`). Enforcement is one method `_check_fiscal_lock_dates`
called from `account_move._post()` and on every relevant write
(`account_move.py:2803-2820, 3787-3956`); journal type drives whether
`sale_lock_date` / `purchase_lock_date` apply. **No `account.fiscal_year`
model exists** — only `fiscalyear_last_day` / `fiscalyear_last_month` on the
company. Periods are derived from those two ints, not stored entities.

### 2. SAP S/4HANA (formerly OB52)

Posting periods are stored in **table T001B** ("Permitted Posting Periods"),
keyed by *posting period variant* + *account type* + *account range*. Account
type values are `+` (default), `S` (G/L), `K` (vendor), `D` (customer), `A`
(asset), `M` (material). Each row has *two* open windows ("From period 1/2")
plus an **authorization group** that gates extended posting at month/year
end. Special periods 13–16 sit on top of the 12 normal periods for
year-end adjustments (German HGB / IFRS audit corrections that must not
muddle January). Since S/4HANA 1809 the OB52 dialog is replaced by the
*Manage Posting Periods* / *Manage Posting Period Variants* Fiori apps,
same table underneath. Authorization is enforced via `F_BKPF_BUP` (auth
object) tied to the auth group on the variant row.
[Sources: SAP Learning – Managing Posting Periods, SAPSharks OB52, SAP blog
"OB52 – Maintain posting period control through authorization group" 2014/07/08,
TCodeSearch T001B.]

### 3. NetSuite

Periods are managed via Setup → Accounting → Manage Accounting Periods,
organized into a fiscal-year tree. The Period Close Checklist enforces a
sequence of *locking* tasks **before** the period can be closed: **Lock
A/R**, **Lock A/P**, **Lock Payroll**, then **Lock All**. Locks are by
*subledger* (= journal type), and only block general-ledger-impacting
changes — non-GL field edits remain possible if "Allow Non-G/L Changes" is
checked. After Lock All, the period must be Closed; **no one** can post
GL-impacting changes in a Closed period unless they hold the *Override
Period Restrictions* permission, in which case they can edit even
locked-but-not-closed periods. Closed periods can be reopened by users with
*Manage Accounting Periods* (Full). **GL Audit Numbering** assigns a
gapless sequence at close — *permanent* sequences cannot be re-numbered;
*repeatable* sequences can. This is the audit-trail equivalent of our
`:period/lock-tx`. [Sources: Oracle NetSuite docs `section_N1452509`,
`section_3735573963`, `section_4805169254`, `section_N1457300`.]

### 4. QuickBooks (Online + Desktop)

QuickBooks has **no period table at all**. There is one company-wide
*Closing Date* and an optional *Closing Date Password*. Without the
password, edits before the closing date trigger only a **warning dialog**
(user clicks Yes); with the password, the user must enter it. There is no
role-based override, no per-journal scope, no special periods. The Audit
Log records who attempted what. Marketed as "flexible" — the help text
explicitly contrasts QB with systems where closed periods are immutable.
[Sources: Intuit Help "Set or change the closing date and password";
Kaufman Rossin "Closing Date Password to Protect Prior Year Data".]

### 5. Xero

Two lock dates: **Period Lock Date** (blocks Standard users; Advisors can
still post pre-lock) and **End of Year Lock Date** (blocks *everyone*,
including Advisors). Both are company-wide, single value, not per-journal.
Removing the End-of-Year lock requires the Advisor role. The "Conversion
Date" is a separate concept — the date Xero took over from the previous
system, before which only opening balances exist. [Source: Xero Central
"Set up or remove lock dates".]

### 6. Sage Intacct

Periods are arranged in a *Reporting Period* tree per *Book*. Subledgers
(AP, AR, Cash Mgmt, T&E) close *individually* before the GL period closes,
so month-end JEs can still be booked against an AP-closed period. A closed
period can be **locked** for added rigidity; locked periods cannot be
reopened from the UI without unlocking first, but unlocking is permitted —
this is roughly Intacct's "soft vs hard" distinction. Year-end roll-forward
runs as a separate task that books retained-earnings closing entries.
[Source: Sage Intacct help "Open and close process overview".]

## Synthesis

**Does our model cover the 80% case?** Yes for small-business bookkeeping
(QuickBooks-class). No for any system that markets to accountants or auditors.
Specifically we are *missing*:

1. **Soft vs hard distinction.** Every serious system has it: SAP
   (auth group on variant), Odoo (`hard_lock_date` is monotone,
   irreversible, no-exception), Xero (period vs end-of-year), NetSuite
   (Locked vs Closed; Override permission only works on Locked), Intacct
   (closed vs locked). Only QuickBooks treats lock as a single switch, and
   its docs apologise for it. **Recommendation:** add a second axis,
   `:period/hard?` or distinguish `:period/closed-at` (reopen-able by admin)
   from `:period/sealed-at` (monotone, append-only). The reopen audit
   commit alone is *not* enough — auditors want to know that some date
   range *cannot* be touched at all.

2. **Per-user / per-role exception.** Odoo's `account.lock_exception`
   (with reason, end-datetime, audit trail of every move touched during
   the exception window — see `_get_audit_trail_during_exception_domain`,
   `account_lock_exception.py:257-296`) is the gold standard. SAP's
   auth-group mechanism is the equivalent. Today our `reopen!` is
   all-or-nothing and clears the lock for everybody; a controller fixing
   one accrual must reopen the whole period. **Recommendation for
   Phase 2-DE:** add an `:period/exception` entity with `:user`,
   `:reason`, `:expires-at`, checked by `assert-not-in-locked-period!`.

3. **Journal-scope granularity.** Odoo and NetSuite both lock the *tax
   ledgers* (sale + purchase) separately from the GL — because tax
   filings get locked first while year-end JEs are still being prepared.
   Our `:period/journal` is correct in spirit but is **single-valued**;
   real systems have effectively `{sale, purchase, tax, all}` orthogonal
   axes. Our schema already permits multiple `:period` rows per date
   range, so the *data model* is fine — but the UX/helpers need to
   distinguish the conventional axes (sale, purchase, tax-only, all).

4. **Fiscal year as first-class entity?** **No, do not add it now.** Odoo
   doesn't have one (just two ints on the company). NetSuite and Intacct
   do, mainly so reporting can group periods. We can add
   `:fiscal-year/start`, `:fiscal-year/end` derived from a company setting
   in Phase 3 when we add reporting. Skipping it costs nothing today.

5. **Special periods (13–16).** SAP-specific (German market). DE customers
   absolutely expect them — year-end audit corrections are booked into
   period 13 *with effective date 31 December* and must not appear in
   January's reports. Our half-open date model can't represent this without
   a discriminator. **Recommendation:** add `:period/adjustment? boolean`
   so multiple periods can share an effective-date range with a sequence
   tag. This is mandatory for DATEV / SKR03 / SKR04 export in Phase 2-DE.

6. **Pre-close validation.** Odoo refuses to set `hard_lock_date` if draft
   entries or unreconciled bank lines remain in the range
   (`company.py:559-595`). We have nothing equivalent. NetSuite's checklist
   is similar. **Recommendation:** `close!` should call a pluggable
   `pre-close-checks` hook (no draft transactions, no unreconciled,
   trial balance is zero); refuse to close otherwise.

**Minimum to not look amateurish to an accountant:**
(a) soft/hard distinction, (b) refuse to close if drafts or unreconciled
items exist, (c) pre-close trial-balance-zero check, (d) special-period
flag for DE. Per-user exceptions and fiscal-year entity can wait.

**Verdict: `period.clj` needs surgery before Phase 2-DE.** The current
file is correct as far as it goes, but the single-lock-state model is the
biggest credibility gap. Concretely: add `:period/sealed-at` (monotone),
`:period/adjustment?`, and a `pre-close-checks` hook. Defer per-user
exception and fiscal-year entity until Phase 3.

## Sources
- Odoo source: `addons/account/models/company.py` lines 59-104, 540-700;
  `addons/account/models/account_lock_exception.py` lines 11-296;
  `addons/account/models/account_move.py` lines 2803-2820, 3787-3956.
- SAP Learning: https://learning.sap.com/courses/customizing-core-settings-in-financial-accounting-in-sap-s4hana/managing-posting-periods
- SAP blog "OB52 – Maintain posting period control through authorization group" (2014-07-08): https://blogs.sap.com/2014/07/08/ob52-maintain-posting-period-control-through-authorization-group/
- SAP blog "Authorization Group in Open/Close posting period" (2014-09-11): https://blogs.sap.com/2014/09/11/authorization-group-in-openclose-posting-period-transaction-ob52/
- T001B reference: https://www.se80.co.uk/sap-tables/?name=t001b
- NetSuite Period Close: https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N1452509.html
- NetSuite GL Audit Numbering: https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_3735573963.html
- NetSuite Unlocking Period Tx: https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N1457300.html
- QuickBooks Desktop closing date: https://quickbooks.intuit.com/learn-support/en-us/help-article/close-books/set-change-closing-date-closing-date-password/L2uKHmMhZ_US_en_US
- Kaufman Rossin closing-date password: https://kaufmanrossin.com/blog/quickbooks-tip-set-a-closing-date-password-to-protect-prior-year-data/
- Xero Central lock dates: https://central.xero.com/s/article/Set-up-and-work-with-lock-dates
- Sage Intacct open/close: https://www.intacct.com/ia/docs/en_US/help_action/General_Ledger/Open_and_close_books/open-and-close-process-overview.htm

# Bitemporality in Accounting — Evidence Review for ADR-008

**Audience:** kontor maintainers
**Date:** 2026-05-09
**Verdict (TL;DR):** **Keep, but quietly demote.** The bitemporal axes are cheap to retain and earn their keep in two narrow, real situations (regulatory restatement defense; intra-period delayed entry of backdated invoices). They are *not* the dominant accounting workflow. Neither named SMB accounting product nor any cited Datomic/XTDB *production* case study materially leverages full bitemporal queries — corrections in the wild are overwhelmingly handled by the "reverse-and-repost in current period" pattern. Keep the schema attrs, drop the bitemporal-by-default ergonomics in read helpers, and stop carrying `:as-of-tx`/`:as-of-valid` through every call.

---

## 1. Does bitemporality matter in accounting practice?

### Marketing claims are loud; production evidence is sparse

The strongest *advocacy* comes from JUXT/XTDB. Every blog post we examined ([JUXT — The Value of Bitemporality](https://www.juxt.pro/blog/value-of-bitemporality/), [Bitemporality — More Than a Design Pattern](https://www.juxt.pro/blog/bitemporality-more-than-a-design-pattern/), [Data Compliance Assurance with Bitemporality](https://xtdb.com/blog/data-compliance-assurance-with-bitemporality)) cites the **same two examples**: equities trade reconstruction under FINRA "as-of" reporting, and Martin Fowler's payroll example. Bitemporal TraderX ([JUXT](https://www.juxt.pro/blog/bitemporal-traderx/)) is explicitly an *educational* reference system ("think 'Pet Store'"), not a production deployment. None of those posts name a production accounting customer.

The "More Than a Design Pattern" piece concedes the friction in its own conclusion: *"Bi-temporality is the full solution, but it's always worth thinking of ways around it."* That is the most candid sentence in the entire vendor literature — a vendor advising readers to avoid the feature when they can.

### The closest-to-real case: Nubank

Nubank's Datomic-backed double-entry ledger ([Cavalcanti, Clojure/conj 2016](http://2016.clojure-conj.org/powerful-accounting/), [slides](https://www.slideshare.net/lucascavalcantisantos/building-a-powerful-double-entry-accounting-system), [Datomic's Nubank story](https://www.datomic.com/nubanks-story.html)) is the most-cited production example in the Clojure ecosystem. The architecture *does* distinguish "actual time" (Datomic tx-log) from "post date" (system-of-record/business time), and `as-of` queries were used heavily during service-splits to replay history. **But** the public correction story is explicitly *append-only compensating entries*, not retroactive valid-time edits — the slides state movements are "immutable, append-only, can fix past by compensating." That is exactly the conventional "reverse-and-repost" pattern, layered on top of Datomic's tx-time history rather than on top of bitemporal valid-time. Nubank uses *one* axis (transaction time, free with Datomic) and emulates valid-time only via business `post-date` columns when it actually needs them.

### SMB accounting products: no bitemporality

QuickBooks, Xero, NetSuite, etc. do not expose any bitemporal model. The dominant SMB pattern, as documented across the [QuickBooks community threads](https://quickbooks.intuit.com/learn-support/en-us/payments/void-checks-in-closed-period/00/131603) and [reversing-entry guides](https://www.wafeq.com/en/learn-accounting/double-entry-accounting/reversing-entries), is: when a closed-period transaction needs correction, post **two journal entries** — one that mirrors the original on the original date (often locked behind a closing password), and a reversing entry dated in the current open period. The closed-period books are not rewritten; the audit log preserves who-changed-what.

### Banking/finance "as-of" tradition

Real "as-of" reporting *does* exist in financial markets — FINRA after-hours trade reporting and trade-pricing reconstruction are genuine ([JUXT — Value of Bitemporality](https://www.juxt.pro/blog/value-of-bitemporality/), [JUXT — Compliance webinar](https://www.juxt.pro/blog/regulatory-compliance-xtdb-webinar/)) — but that's *trading*, not bookkeeping. Trading needs to know "what price did the model see at trade time"; SMB accounting needs to know "what is the AR balance today, given everything we now know."

---

## 2. Is bitemporality the same as period-close + corrections?

**No, but the two are equivalent for >95% of SMB workflows.** The Big Four / FRC restatement framework distinguishes:

- **"Little r" revisions** (immaterial errors): correct prospectively in the current period — pure reverse-and-repost ([KPMG handbook](https://kpmg.com/kpmg-us/content/dam/kpmg/frv/pdf/2023/handbook-accounting-changes-error-corrections-1.pdf), [PwC 30.7](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/financial_statement_/financial_statement___18_US/chapter_30_accountin_US/307_correction_of_an_US.html), [BDO guide](https://www.bdo.com/insights/assurance/financial-reporting-guide-for-accounting-changes-and-error-corrections)). No bitemporality needed.
- **"Big R" restatements** (material errors): under [IAS 8](https://www.iasplus.com/en/standards/ias/ias8) / ASC 250-10-20, the entity *retrospectively restates* comparative prior-period statements *as though the error never happened* — and discloses the effect line-by-line. This is, in fact, the **opposite** of what bitemporality preserves: auditors and regulators want the *corrected* view, with a disclosure of what changed, not the as-filed view kept queryable forever.

Auditors *do* want both, but in different forms: the **corrected current view** (the actual restated statements), plus a **change log/audit trail** explaining what moved and why. They do *not* typically run "show me the trial balance exactly as it appeared on the original filing date" — they run "show me what the trial balance is *now* and reconcile every line that differs from what was filed."

**Where bitemporality genuinely earns its keep:**
1. **Intra-period backdated entries.** Invoice dated Jan 15, entered Jan 20 — `valid=Jan 15, tx=Jan 20` is the natural model. This is the single use case our own ADR-008 cites first, and it is real for any SME. But notice this is *one valid date per posting*, not a full range; you don't need `:posting/valid-to`.
2. **Tax-audit defense.** When the tax authority asks "what did you file on Mar 31 for Q1 VAT?" — having a tx-time snapshot makes the answer trivially queryable. The same snapshot can be reconstructed by replaying the tx-log against any immutable store; bitemporality just makes it free.
3. **Regulatory restatement reconstruction (rare for SMBs).** IAS 8 disclosure of "the effect of the correction on each financial statement line item" requires the as-filed numbers. SMB clients almost never restate; this matters for listed entities.

---

## 3. Cost-benefit in our specific context

**What it costs us today** (per `/home/christian-weilbach/Development/kontor/src/kontor/`):
- 3 schema attrs (`:posting/valid-from`, `:posting/valid-to`, `:posting/temporal-key` tuple) — `schema.clj:694–717`.
- Every read helper in `balance.clj` and `ledger.clj` carries `:as-of-tx` + `:as-of-valid`, defaulting to "now" — i.e., 2 extra params per query.
- Every period-close validation in `period.clj` derives an effective `valid-from`.
- Tests verify both axes (`balance_test.clj`).

That's ~10–20% extra surface area. Real but bounded.

**What it buys us:**
- The `as-of-tx-excludes-later-corrections` test demonstrates exactly one workflow: "show the books as filed at tx-time T." This costs us nothing extra because `:db/txInstant` + `d/as-of` are free in datahike. The `:posting/valid-from` axis is what costs the 10–20%, and its production payoff is *only* the backdated-invoice case (intra-period valid-time skew).
- Versus pure period-close + reversal: we *can* answer "as filed on Mar 31" in a single query without a separate snapshot mechanism. That is occasionally useful for tax-authority correspondence.

**What it doesn't buy us:**
- IAS 8 restatement disclosure (requires producing the *new* numbers plus narrative, not the old).
- Auditor satisfaction (auditors want the audit log + current view, not bitemporal slicing).
- SMB user reports (users always want today's view).

---

## Recommendation: simplify, don't rip out

ADR-008 was *defensible* but **over-built for the target user**. Concrete proposal:

1. **Keep `:posting/valid-from` and `:transaction/effective-date`.** These are the natural model for backdated invoices and they cost nothing extra at write time. They also satisfy the "intra-period valid-time skew" use case that *does* show up for SMBs.
2. **Drop `:posting/valid-to`.** No SMB workflow we found justifies a valid-time *range* on a posting. A posting represents an event, not a slowly-changing dimension. If it's wrong, you reverse and repost — you don't bound its validity. (XTDB itself models corrections as a new fact at a later tx-time, not as `valid-to` on the original.)
3. **Drop `:posting/temporal-key` tuple.** Without `:valid-to`, the bitemporal index loses its primary justification. A plain index on `:posting/valid-from` plus `:db/txInstant` (free) is enough.
4. **Make `:as-of-valid` default behavior implicit.** Filter on `(<= :posting/valid-from today)` only when the caller asks for a historical view; default reads should not carry the param. `:as-of-tx` stays as an explicit opt-in for the rare "show as filed" report — call it `as-of-snapshot` to make it sound like the audit feature it actually is.
5. **Reframe the audit story.** Document that audit defense relies on (a) datahike's tx-log + commit DAG (ADR-003) for "what did the books look like on date X," and (b) the reverse-and-repost convention for IAS 8 corrections. Bitemporality is a *tactical* tool for backdated entries, not the strategic foundation.

**Migration cost:** small — ~1 day of work. Drop the `:valid-to` attr and the tuple, simplify the read helpers in `balance.clj`/`ledger.clj` to make `:as-of-valid` opt-in, retain the existing `as-of-tx-excludes-later-corrections` test, delete the `valid-to` filtering in `ledger.clj:78–82`.

**Honest uncertainty:** evidence is sparse on both sides. No vendor publishes failure stories about bitemporality, and SMB vendors don't publish architecture decisions at all. The recommendation above weights the *absence* of bitemporal patterns in QuickBooks/Xero/Nubank-public-arch heavily — if a regulated-fintech use case appears later, restoring `:valid-to` is a forward-compatible additive change.

---

## Sources

- [JUXT — The Value of Bitemporality](https://www.juxt.pro/blog/value-of-bitemporality/)
- [JUXT — Bitemporality: More Than a Design Pattern](https://www.juxt.pro/blog/bitemporality-more-than-a-design-pattern/)
- [JUXT — Bitemporal TraderX (educational, not production)](https://www.juxt.pro/blog/bitemporal-traderx/)
- [JUXT — Streamlining Regulatory Compliance with XTDB](https://www.juxt.pro/blog/regulatory-compliance-xtdb-webinar/)
- [XTDB — Data Compliance Assurance with Bitemporality](https://xtdb.com/blog/data-compliance-assurance-with-bitemporality)
- [XTDB Bitemporality docs](https://v1-docs.xtdb.com/concepts/bitemporality/)
- [Cavalcanti — Building a Powerful Double Entry Accounting System (Clojure/conj 2016)](http://2016.clojure-conj.org/powerful-accounting/) and [slides](https://www.slideshare.net/lucascavalcantisantos/building-a-powerful-double-entry-accounting-system)
- [Datomic — Nubank's Story](https://www.datomic.com/nubanks-story.html)
- [QuickBooks Community — corrections to errors in a prior period](https://quickbooks.intuit.com/learn-support/en-us/reports-and-accounting/corrections-to-errors-in-a-prior-period/00/286498)
- [QuickBooks Community — voiding checks in closed periods](https://quickbooks.intuit.com/learn-support/en-us/payments/void-checks-in-closed-period/00/131603)
- [Wafeq — Reversing entries in accounting](https://www.wafeq.com/en/learn-accounting/double-entry-accounting/reversing-entries)
- [KPMG Accounting Changes & Error Corrections Handbook (Nov 2023)](https://kpmg.com/kpmg-us/content/dam/kpmg/frv/pdf/2023/handbook-accounting-changes-error-corrections-1.pdf)
- [PwC Viewpoint 30.7 Correction of an Error](https://viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/financial_statement_/financial_statement___18_US/chapter_30_accountin_US/307_correction_of_an_US.html)
- [BDO — Financial Reporting Guide for Accounting Changes and Error Corrections](https://bdo.com/insights/assurance/financial-reporting-guide-for-accounting-changes-and-error-corrections)
- [IAS 8 (IFRS / IAS Plus)](https://www.iasplus.com/en/standards/ias/ias8)
- [SQL:2011 Temporal — MS Learn](https://learn.microsoft.com/en-us/sql/relational-databases/tables/temporal-tables?view=sql-server-ver16) (cites only generic audit/financial-reporting use cases, no named accounting product)
- [Snowflake Time Travel docs](https://docs.snowflake.com/en/user-guide/data-time-travel) — pitched for finance audit/recovery, capped at 90 days, *not* a bitemporal valid-time facility

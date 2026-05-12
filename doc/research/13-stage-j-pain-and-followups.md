# 13 — Stage J cross-cutting pain + prioritized followups

**Date:** 2026-05-12
**Sources:**
1. Local independent code review of ADR-033 through ADR-036 (6 P0 ship-blockers, 10 P1, 9 P2)
2. Partner / MDM market-pain research (Odoo, Tryton, SAP BP, Salesforce, NetSuite — 25 items)
3. Order management market-pain research (Shopify Plus, NetSuite, Magento, Sylius, OFBiz, Adobe Commerce — 30 items)
4. Invoicing / AR / GL-posting market-pain research (SAP, NetSuite, Sage Intacct, Stripe Billing, Avalara/TaxJar, SdI/CFDI/NF-e/IRN/Peppol — 30 items)
5. Status-machine / workflow market-pain research (Camunda 8, Temporal, Sylius, Salesforce Flow, AWS Step Functions, OFBiz, Frappe — 25 items)

Total inputs: ~115 distinct pain points across 5 reports. This note synthesizes the cross-cutting patterns + prioritizes followups by impact, evidence weight, and downstream-companion dependency.

## What kontor's design genuinely wins on

Eleven properties showed up consistently across all five reports as **architectural strengths** of the design vs commercial / OSS baselines:

1. **Bitemporal queries by construction** (ADR-008). "What did this order/invoice/partner look like at time T" is a query parameter, not a separate fact table. Compare:
   - Camunda needs Operate + Optimize ETL pipeline.
   - NetSuite needs Field Audit Trail (a paid upgrade with documented performance caveats).
   - Odoo address edits retroactively mutate every historical quotation (Odoo forum #112778).
   - Workday tracks effective-date but conflates valid-time with transaction-time.
2. **Per-(entity, ledger, commodity) sum-to-zero is a kernel invariant** (ADR-021 + ADR-031). Cannot persist an unbalanced posting. Odoo issues #35334, #37469, #35785 are entire bug classes that structurally cannot occur here.
3. **Sealing as middleware** (ADR-007). SAP/NetSuite implement "posted period locked" as ACL; kontor as data invariant. The audit chain is automatic, not configured.
4. **Status machine = data, not code** (ADR-034). Vocabulary changes are a tx, not a deploy. Compare: SAP status profiles in SAP-Script; NetSuite in SuiteFlow XML; Odoo in Python decorators; Sylius in YAML; OFBiz in Java seed.
5. **Race-immunity by datahike transactor serialization**. Two concurrent `record-status-change!` calls can never produce both-prior-state-see + both-transition-apply. Rails state_machine (issue #241), Spring StateMachine (#493), and PortSwigger "Smashing the State Machine" research demonstrate this is a real production attack class elsewhere.
6. **Joinable status + accounting in one datalog query.** "What was the GL balance the moment this order transitioned to :approved" is one query. No other system surveyed delivers this.
7. **Multi-entity + multi-ledger from kernel day one** (ADR-031 + ADR-021). No SAP company-code preflight, no NetSuite OneWorld upsell, no Multi-Book Accounting license. Single-entity tenants pay zero complexity tax (`:posting/entity` nil-allowed).
8. **Multi-attestation lifecycle is generic** (ADR-024). Italy SdI, Mexico CFDI, Brazil NF-e, India IRN, Hungary RTIR all fit one shape with country-specific `EInvoiceProvider` impls.
9. **Subtype entities (`:person` / `:org`) on shared `:partner` root** (ADR-033). This is the SAP BP target architecture; we get it from day one with no legacy migration. Datalark study: 67% of S/4HANA migrations blow budget, BP-conversion is the single most-cited pain. Odoo's flag-on-one-table approach cannot separate PII from public business data at the schema layer.
10. **Polymorphic `:contact-mech` with junctions** survives multi-purpose routing and historical address-at-time-of-invoice queries. Odoo's child-partner-per-address pattern collapses on both.
11. **Non-destructive merge is structurally possible** (datahike tx-time gives free rollback). Every commercial system (NetSuite, Salesforce, Odoo) has destructive merge with documented data loss; ours doesn't have to.

These are real differentiators worth pointing to in any RFP comparison or positioning document. The gaps below are mostly **content** (vocabularies, account-types, jurisdiction adapters, default seeds) rather than **architecture**.

## Cross-cutting gap categories (P1)

### Multi-jurisdiction + multi-currency content

The single highest-frequency category across all four market-pain reports. Concrete items:

- **`:partner-tax-id` junction** (Partner P0). Single `:partner/tax-id` scalar breaks for any customer with EU multi-warehouse, OSS scheme, or intercompany VAT. Microsoft Dynamics 365 BC shipped "Alternative VAT Numbers" with effective dates in wave 2024. Schema: `[:partner/tax-id-junction :partner :country :tax-id-type :tax-id :from-date :thru-date]`.
- **`:invoice-line/reverse-charge?` flag** (Invoicing P1). EU B2B intracommunity services require reverse-charge dual-posting; ViDA 2028 makes this universal for non-established traders.
- **`:invoice/tax-inclusive?` flag** (Invoicing P1). Pinning the discount-then-tax computation order. Odoo issue #23125 documents the rounding gotcha when both interact.
- **Multi-currency FX posting hook**. Customer EUR, supplier USD, books CHF. Realized at payment application; unrealized at period-close reverse. Defer to a `kontor-fx-revalue` companion, but the invoice bridge must not assume single-commodity per transaction. SAP Advanced FCV and NetSuite revaluation are the reference shapes.
- **E-invoice clearance lifecycle states**: `:pending-attestation`, `:rejected`. Italy SdI returns reject codes within 5 days; India IRN cannot be cancelled past 24h (requires credit-memo); Brazil NF-e split-payment from 2026. The kernel `:transaction/state :pending-attestation` exists per ADR-018; the invoice companion needs the analog.
- **Withholding tax account-type** (`:withholding-tax-payable`). India TDS, Mexico ISR, US 1099 backup withholding. Posting bridge needs the credit-leg deferred to payment time.

### State-machine extensions

- **`:status-history/reason` as keyword, not free-form string** (Status-machine P1). Auditors and SoD platforms (SafePaaS, ConductorOne) want codified reasons compressible into compliance reports. Add optional `:status-history/reason-note` string for the human story.
- **`:status-history/supporting-doc` ref slot** (Status-machine P1). ASC 250 + SOX requires "supporting docs on reversals." SAP MR8M-style invoice cancellations always create a paired credit-memo document. Two attrs in the kernel.
- **Approval-policy companion** (Status-machine P1 → Stage future). Self-approval prevention is SOX 404. Don't bake into `:status-transition` (too inflexible); add a `:approval-policy` table with rules like `:no-self-approval` or `:require-role :financial-controller`. Validator runs inside `record-status-change!`. Own ADR.
- **Time-based transitions** (Status-machine P1 → highest-value followup). "Auto-cancel after 48h." Universal customer expectation; Salesforce, Temporal, Camunda all ship it. Cost: two attrs (`:status-transition/auto-after-duration`, `:status-transition/auto-when-condition`) + a sweeper. Tie to ADR-032 `:schedule`.
- **Side-effect intent row pattern** (Status-machine P1). Avoids double-emails / EDI fires on retry. Caller writes status-history + side-effect-intent in same tx; worker drains intent rows and marks done. Documents how companions do orchestration without becoming a workflow engine.
- **Inverse-pair role-direction semantics** (Order P-K-A, status-machine cross-cut). `:bill-to` means buyer-perspective consistently; kontor-procurement reuses without redefining. Document explicitly in ADR-033's vocabulary.

### Foundation companions still needed

- **`:bank-account` entity** (Partner P0 → Stage L+). Banking is master data: a supplier has N bank accounts each with IBAN/BIC/currency/validity. Without this, invoice → payment workflow has no AP target. Either extend kontor-partner OR ship a tiny `kontor-payment-bank` sibling.
- **`:partner-merge` non-destructive link** (Partner P0). One-attribute kernel addition: `:partner-merge/duplicate-of`, `:partner-merge/superseded`, `:partner-merge/at`, `:partner-merge/by`, `:partner-merge/reason`. Resolves via `(kontor.partner/resolve-canonical-partner)`. Whole MDM industry exists because every commercial system has destructive merge; we can ship the reversible one.
- **AR collections companion (Stage L)**. Aging buckets (30/60/90/120+), dunning event entity, credit-hold automation, lockbox auto-match. LedgerUp study: manual processes collapse at 500 invoices/month or $5M ARR. Touches kontor-partner (`:partner/credit-limit`, `:partner/credit-status`), kontor-invoice (`:invoice/credit-hold-at`), kontor-bank-* (lockbox matcher).
- **`:gl-account-rule` fourth tier** (Invoicing P2). Three-tier (override → entity-default → tenant-default) saturates fast: per-product GL, channel-specific contra-revenue, loyalty redemption, export-sales segregation. Add a `:gl-account-rule` table with `(account-type, predicate-expr, account)` between override and entity-default. Predicate is a datalog snippet pulled from the line.

### Forward-compat for Stage K (procurement)

These MUST land before Stage K extends the bridge:

- **debit/credit map → data table**. Currently `kontor.invoice.posting/debit-credit-for` is a closed Clojure map. Procurement adds `:goods-receipt-accrual`, `:landed-cost`, `:price-variance`, `:exchange-variance`. Schema: `[:account-type-direction :invoice-type :account-type :direction]`.
- **`cancel-order!` releases `:inv-reservation`** (Local P1-10 + Order P0 market-validated). ADR-035 docstring claims this happens; implementation doesn't. Reservations stay live, blocking stock for other orders. Shopify Developer Community #29698 is the canonical bug.
- **`cancel-invoice!` retracts `:order-item-billing` junctions** (Local P1-6). Otherwise `partial-billed-quantity` double-counts after cancel+reissue.
- **`:purchase` invoice end-to-end test**. Zero coverage today. Bridge hardcodes `:sales-revenue` on item lines; needs polymorphic dispatch on `:order/type`.
- **Reserve `:invoice/receipt` namespace** for the 3-way match (Requirement → PO → Receipt → InvoiceItem). Don't ship the attribute yet; document the slot.
- **`:invoice/order` cardinality**: currently optional. Keep optional so vendor bills with no PO can land (accrual entries on receipt with no invoice yet).

### Forward-compat for Stage M (kontor-revrec)

- **`:invoice-line/recognition` keyword** (`:direct | :deferred`). When `:deferred`, the posting bridge credits a deferred-revenue account instead of revenue, and emits a `:schedule` (ADR-032) row that releases over the obligation period. Tiny schema delta now, big migration cost avoided later.
- **Reserve `:invoice-line/performance-obligation` namespace** for ASC 606 multi-element. Don't ship; document the slot.
- **Cross-check ADR-032 `:schedule` is generic enough to drive revrec releases**, not just recurring postings. Run a small validation test: write a revrec scenario using `:schedule` + verify it composes.

### Performance + scale

- **Concurrent reservation broker** (Order P0). At flash-sale rates (5000 orders/min), datahike's CAS isn't enough; need an in-JVM soft-reservation broker (Redis-pattern, but in-JVM since datahike is in-process) with TTL'd reservation + commit-on-payment. ADR + companion.
- **Bulk-transition API** (Status-machine P1). 1000-entity batch in one tx vs per-entity. Need to bench against datahike's tuple-index update cost. If linear: ship `bulk-record-status-change!`. If not: per-entity tx with batch-commit middleware.
- **Ship-group expansion bench**. Wholesale distributor: 50k orders/day × 10 lines × 2 ship-groups × 3 lots = 3M `:inv-reservation` datoms/day. Confirm index cost is acceptable.

## Quick-win P1s (smallest scope × highest impact)

Order this list when picking up post-checkpoint work:

1. **`cancel-order!` releases reservations + `cancel-invoice!` retracts billing junctions.** Local + market-validated. ~30 LOC + 2 tests. Local-P1-10, P1-6.
2. **`:status-history/reason` → keyword + optional `:reason-note` string.** Schema-change + simple migration. SOX audit win. Status-machine-P1-22.
3. **`:purchase` invoice end-to-end test.** Local-P1-9. Surfaces the bridge's hardcoded `:sales-revenue` on item lines. Forces the dispatch-on-`:order/type` fix.
4. **debit/credit map → data table.** Local-P1-1 + market-validated. Kontor-procurement extension point. ~50 LOC + schema entity + 1 test.
5. **`:invoice-line/recognition` keyword.** Invoicing-P1-16. Single attr; revrec forward-compat.
6. **`:partner-tax-id` junction.** Partner-P0. Single junction entity; preserves `:partner/tax-id` as denorm.
7. **Period-close integration test.** Already added a regression test (P0-7), but extend to verify the cross-cut: invoice posting + period locking + reopen flow.

Combined estimate: 4-6 engineering-days. Worth doing before Stage K to avoid the "sloppy initially → more churn later" pattern the user is explicitly investing in.

## Deferred (P2)

These are documented but not on the near-term critical path:

- BPMN visual editor — positioning decision (kontor is a primitive, not a workflow product).
- Workflow DSL — datalog IS the DSL.
- ML / risk-score integration — covered by the side-effect-intent-row pattern.
- History-quota archival — datahike handles millions; future ADR when it bites.
- Camunda/Slack/SAP/Salesforce connectors — out of kernel scope; consumer side.
- Group consolidation `:partner-ancestry` denorm — defer until benchmarks show pain.
- vCard 4.0 export — ship CSV first (every CRM accepts CSV; vCard is contact-app).
- Visual modeler — same positioning argument.

## Stage K (kontor-procurement) entry conditions

Before starting Stage K, the following must land:
1. ✅ All 7 Stage J P0s fixed (commit `8524e42`).
2. Quick-win P1s 1-4 above (cancel-order releases reservations, debit/credit data table, `:purchase` test, reason-as-keyword).
3. Decide inverse-pair role-direction semantics in ADR-033 vocabulary.
4. Move debit/credit map to data table.

The `:requirement` entity shape lives at OFBiz lines 2171-2314 (per research note 12). Procurement reuses the entire kontor-sales order machinery via `:order/type :purchase`; the new entities are `:requirement` (requisition with its own state machine) and `:shipment-receipt` (for 3-way match). RTV flow is a separate order-like aggregate.

## Stage J final tally

After 4 commits this session, the foundation is at:
- 657 tests / 2278 assertions / 0 failures (up from 603 / 2000 at session start)
- ~3000+ LOC across kontor-partner / kontor-sales / kontor-invoice
- 4 ADRs (033, 034, 035, 036) covering party model, status-machine primitive, order machinery, invoice bridge
- 1 new kernel primitive (`:status-transition` + `:status-history` + `kontor.status-machine`)
- Zero pre-existing kernel attrs broken; kernel-only consumers unaffected
- 5 research notes added or referenced (09 HR, 10 business-OS, 11 grounding, 12 OFBiz study, 13 this note)

The kernel's "minimal" claim is intact: every companion is opt-in via its own `install!` fn. A kernel-only consumer never sees `:order/*`, `:invoice/order`, `:partner-role/*`, or `:status-transition/*`.

## Acknowledged limitations

The 4 market-pain agents and the local code-review agent collectively read ~80 OSS issue trackers, ~50 vendor docs / blogs, and ~30 academic / industry whitepapers. This is a substantial-but-finite sample. Specific blind spots:

- **No live customer feedback yet** — the project pre-dates a first paying user. Pain prioritization is based on what existing systems' users complain about, which is correlated but not identical to what kontor's eventual users will complain about.
- **No localization research for Stage J specifically** — research note 03 + 11 cover US/CA/DE/IN/MX baseline; per-country invoice clearance + tax engines need targeted work as each l10n-* ships.
- **No performance benchmarks** — every "scale" point above is qualitative. Real numbers come from running the relevant tests on representative data volumes.
- **No DBA/ops feedback** — datahike's operational properties (backup, replication, disaster recovery, multi-tenant isolation) are out of scope for this session but matter for any production deployment.

The shape is right; the next session of validation should pair the design with concrete deployment scenarios (a real DE GmbH bookkeeping flow, a real US LLC sales-tax flow, a real BR or IN e-invoicing flow) to surface what the OSS / vendor surveys can't.

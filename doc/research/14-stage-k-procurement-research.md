# 14 — Stage K (procurement) research-before: three-agent synthesis

**Date:** 2026-05-12
**Sources:** Three parallel background agents (per the CLAUDE.md per-stage rhythm: research-before / implement / review-after).

1. **OFBiz procurement deep study** — implementation-fidelity read of `Requirement*` + `Shipment*Receipt*` + `OrderReturnServices*` + `GeneralLedgerServices.xml` procurement bridges. ~2700 LOC scanned across `/home/christian-weilbach/Development/ofbiz-framework/applications/order/` and `/applications/accounting/`. Verified: high (file:line citations throughout).
2. **Procurement market-pain online** — ~50 web searches across SAP, NetSuite, Coupa, Ariba, Stampli, Tipalti, AvidXchange, Bill.com, Odoo Purchase, ERPNext Buying, Frappe, OFBiz JIRA, Spend Matters, plus standards (cXML, OAGIS, Peppol BIS Procurement, EDI 850/855/856/810) and regulatory (FAR/DFAR, SAM.gov, TED, UK CCS). 33 prioritized pain points with severity + remediation hints.
3. **Internal gap analysis** — focused read of `/home/christian-weilbach/Development/kontor/src/kontor/schema.clj` + `modules/sales/` + `modules/invoice/` + `modules/partner/` to map what the current substrate provides for procurement vs what Stage K must add.

This note synthesizes the cross-cutting findings; the full agent reports live in the conversation transcript. ADR-042 (kontor-procurement design) draws on this synthesis.

## What all three agents agreed on

### 1. The substrate is unusually complete for Stage K

The internal gap analysis verified concretely:
- `kontor.posting/plan-stock-move` (ADR-030, `src/kontor/posting.clj:578`) already handles `:direction :in` with a callback for `:gr-ir-clearing` resolution. The accounting bones of "Dr inventory / Cr GR-IR-clearing on receipt" already exist; Stage K just needs the `:receipt` *entity* to anchor the bridge.
- `:valuation-layer` / `:layer-consumption` / `:layer-adjustment` (ADR-028) — FIFO/LIFO/avg stacks accept a posting `:origin-transaction` as the layer anchor; receipts integrate with no schema change.
- `:order/type :purchase` discriminator already lives in ADR-035 (`modules/sales/src/kontor/sales/schema.clj:27-33`). The order surface is type-agnostic by design — no order schema change needed.
- `:invoice/type :purchase` already in ADR-036 (`modules/invoice/src/kontor/invoice/schema.clj:24-30`). `:invoice/order` back-ref is already optional, so vendor bills without a PO work.
- ADR-041's `:account-type-direction` table (`src/kontor/schema.clj:654-681`) exists but is unseeded; the `default-direction-for` fallback at `modules/invoice/src/kontor/invoice/posting.clj:84-102` covers the basic `:purchase-debit` / `:purchase-credit` set but lacks procurement-specific entries (`:gr-ir-clearing`, `:goods-receipt-accrual`, `:landed-cost-variance`, `:price-variance`, `:exchange-variance`).
- ADR-038's `:approval-policy` primitive composes for 3-way-match override-with-audit: a new `:rule :requires-three-way-match-pass` slots in without ADR-038 schema changes.
- ADR-040's `:invoice-line/withholding-on-payment?` flag + `:invoice-line/reverse-charge?` flag cover IN TDS / MX ISR / US 1099 / EU intracommunity reverse-charge for procurement specifically.

### 2. The 3-way match is a referential invariant, not a function

OFBiz models the 3-way match as three FK relationships:
- `ShipmentReceipt.orderItemSeqId` — receipt to PO line
- `OrderItemBilling.shipmentReceiptId` — invoice line to receipt
- `OrderItemBilling.orderItemSeqId` — invoice line to PO line

These three FKs *are* the 3-way match. Validation = "for every receipt, exactly one invoice item refers to it" (plus tolerance bands). Kontor's translation: three datalog rules over `:order-item-billing` (ADR-036, already exists), a new `:receipt-invoice-billing` junction, and a new `:return-item-billing` junction for credit memos. The state-machine on `:invoice/match-status` is the operational view; the FKs are the source of truth.

### 3. PR ↔ PO is many-to-many

OFBiz's `OrderRequirementCommitment` is a join table because:
- Multiple small requirements (100 for widget X) roll into one PO line (100 widgets from supplier A).
- Min-order-quantity constraints split one large requirement across multiple POs.
- Cancelling a PO line doesn't automatically un-commit the requirement (it may be re-committable to a different supplier).

Single FK on `:order-item/requirement` would be wrong. Stage K must ship `:requirement-commitment` as a junction from day 1.

### 4. RTV is one entity with role-inverted discriminator, not a separate aggregate

`ReturnHeader.returnHeaderTypeId ∈ {CUSTOMER_RETURN, VENDOR_RETURN}` and the OFBiz code paths are 95% identical with role inversion (`OrderReturnServices.java` lines 1774-1786). The pattern mirrors the `:order/type :sales | :purchase` discriminator from ADR-035 — same shape, different direction. Stage K ships `:return` with `:return/type :customer | :vendor`; not a separate `:rtv` entity.

### 5. `OrderItemAssoc` generalizes drop-ship + substitution + replacement + upgrade

OFBiz uses one join entity (`OrderItemAssoc`) with `orderItemAssocTypeId` ∈ {`DROP_SHIPMENT`, `SUBSTITUTE`, `REPLACEMENT`, `UPGRADE`} for all four scenarios. Stage K lifts this directly as `:order-item-assoc/type`. Saves four separate tables.

### 6. Services procurement needs `:service-acceptance` parallel to `:receipt`, not phantom receipts

OFBiz fudges by skipping the receipt for non-physical items (`OrderReturnServices.java:636` analog). The market-pain agent flagged this as awkward in Coupa (manual receipt entry required). Stage K's clean shape: `:order-item/requires-receipt? false` flag plus a parallel `:service-acceptance` entity (with `:audit-doc` ref via ADR-038 for the acceptance evidence — Slack thread screenshot, signed milestone PDF, etc.). The 3-way match degenerates to a 2-way match via the same query.

## Disagreements (or differences in emphasis)

### RTV entity shape

- **OFBiz deep dive**: `:return` entity with `:type :customer | :vendor` discriminator (lift OFBiz `ReturnHeader.returnHeaderTypeId` directly).
- **Internal gap analysis**: prefer `:order/type :return-to-vendor` reusing order machinery (simpler if you think of RTV as "a PO going backwards").

Resolution (per user direction during ADR-042 design): adopt the OFBiz pattern. `:return` as a distinct aggregate. Distinct lifecycle (RMA numbers, disposition flags `:available | :defective | :scrap`, replacement-order linkage) is genuinely different from forward orders. Internal gap analysis's preference was driven by minimizing entity count; the user picked the OFBiz pattern.

### Tolerance config: per-supplier vs per-supplier-product vs entity-level default

All three agents recommend `:match-tolerance` keyed multi-dimensionally with priority lookup. Slight differences in proposed key shape; ADR-042 uses `[entity, supplier?, product?]` with `(entity, supplier, product) → (entity, supplier, nil) → (entity, nil, nil) → kernel-default-0%` priority order, mirroring `:gl-account-default` from ADR-036.

### `:order-item-assoc` adoption timing

Internal gap analysis treated it as optional (junction slot only, no drop-ship workflow). OFBiz study lifted it verbatim. Market-pain agent confirmed drop-ship is high-frequency real-world pain (NetSuite Flxpoint blog + Odoo issue #13974). Resolution: ship the entity in Stage K v1; drop-ship workflow (auto-emission of PO from SO) deferred to a follow-up.

## Top 10 cross-cutting insights for ADR-042

1. **Match-status is a state machine, not a function.** First-time-match rate is 50-80% (Stampli, SoftCo benchmarks) — the exception tail is where the work is. Match-status as `:auto-matched | :exception-* | :manual-approved | :disputed | :cleared` driven by ADR-034's `:status-transition` table makes the exception path queryable and auditable.

2. **GR/IR clearing per (PO-line, commodity) is the kernel pattern.** SAP and NetSuite both have versions; both struggle with stale residuals. Kontor's explicit `:gr-ir-clearing` entry in `:account-type-direction` (ADR-041 supports) + bitemporal datoms makes "why is this residual non-zero" a one-query answer.

3. **Override audit trail belongs in `:status-history`, not free-text fields.** Coupa's free-text override reason fails compliance audits routinely (G2 reviews flag this). Kontor's `:status-history/reason` keyword + `:supporting-doc` ref + `:attestation` chain (ADRs 038 + 024) is structurally better.

4. **Approval-rule changes are tx, not deploys.** Coupa documents "policy change blows up open requisitions" as a known limitation. Kontor's bitemporal `:approval-policy` + `:status-transition` means "what policy applied when this requisition was created" is a datalog query, not a workflow-engine debug session.

5. **Tolerance bands must be per-(supplier, product, dimension).** Coarse global tolerances are universally rejected by mid-market+ customers. SAP supports this with tolerance keys (AN/AP/BW/DQ/DW/PE/PS/ST/VP) but per-company-code-only; kontor's `:match-tolerance` with full key priority is a structural improvement.

6. **`OrderItemAssoc` is one entity for four workflows.** Drop-ship + substitute + replacement + upgrade. OFBiz's pattern is right; the type discriminator generalizes.

7. **Service procurement degenerates 3-way to 2-way cleanly via `:service-acceptance`.** Don't fudge with phantom receipts. Same match-query polymorphs on `:order-item/requires-receipt?`.

8. **Receive-rejection accounting is a kontor improvement.** OFBiz silently drops `quantityRejected`. Kontor routes it to a `:receive-reject-loss` GL-account-type so over-shipment by vendors becomes visible in P&L.

9. **Drop-ship `:ship-to` is a reference, not a copy.** NetSuite's "edited SO after auto-PO emitted; address out of sync" bug becomes structurally impossible if the PO's `:ship-group/contact-mech` is a ref to the SO's `:ship-group/contact-mech`. Bitemporal lookup answers "what address at PO time" for free.

10. **Defer cXML + EDI + vendor scorecards + P-card + AP-OCR to dedicated companions.** None of these are kernel work. The kernel exposes canonical entities; serialization to a particular EDI dialect or AP-automation overlay is a downstream mapper concern.

## Pain points outside Stage K v1 scope (deferred)

Per the user's "all of the above" scope decision, Stage K v1 covers forward 3-way match + drop-ship-link + services + RTV + credit memos. Explicitly out of v1 scope:

- **Landed cost allocation** (freight, duty, insurance pro-rated to receipts). High-value but composes on `:valuation-layer` + `:layer-adjustment` per ADR-028. Defer to Stage K-followup or a sibling `kontor-landed-cost` companion.
- **Purchase Price Variance (PPV) lifecycle** (standard cost vs actual reconciliation + revaluation at period-end). Pieces exist (`:price-variance` in `:account-type-direction`); the lifecycle is its own ADR.
- **Multi-currency PO/receipt/invoice FX-source policy** (PO-fixed vs GR-spot vs IR-spot). Needs `:procurement/fx-rate-source` per-tenant config + revaluation flow. Defer.
- **Subcontracting** (we supply components; vendor returns finished goods). Composes on existing entities; document the slot but don't ship the workflow.
- **Consignment inventory** (vendor owns until consumed; titling-change-on-consumption). Composes on `:inventory-item/owner-party` (kernel inventory has this); workflow is its own companion.
- **VMI** (vendor-initiated replenishment). Documented slot; workflow deferred.
- **cXML PunchOut + EDI 850/855/856/810**. Out of kernel scope; future `kontor-edi` companion.
- **Vendor performance scorecards** (on-time-delivery %, defect rate, lead-time variance, invoice accuracy). Computable from kontor datoms via datalog; consumer-side metrics dashboards are out of kontor.
- **P-card reconciliation** (corporate card statement match). Concur / Ramp / Brex / Airbase own this; consumer-side integration.
- **AP automation OCR for non-PO invoice intake**. Stampli / Tipalti / AvidXchange own this; kontor is the substrate they target, not the OCR provider.
- **Sanctions screening** (OFAC SDN, PEP, AML). Hooks via `:partner/kyc-status` from ADR-039; full `SanctionsProvider` protocol is a future companion (same pattern as `TaxProvider` per ADR-005).

## What Stage K v1 ships

12 new entities + 3 attr extensions + ~25 status-transition seeds + ~9 account-type-direction seeds + 5 new namespaces + 2 polymorphic bridge edits + 1 new `:approval-policy/rule` value. ADR-042 has the full design. Implementation in 4 coherent commits per ADR-042's implementation plan.

## Sources used (representative)

OFBiz study agent traced specific file:line:
- `/home/christian-weilbach/Development/ofbiz-framework/applications/datamodel/entitydef/order-entitymodel.xml` lines 2171-2820 (Requirement + Return families).
- `/applications/order/src/main/java/org/apache/ofbiz/order/requirement/RequirementServices.java` (401 LOC).
- `/applications/order/src/main/java/org/apache/ofbiz/order/order/OrderReturnServices.java` (~2700 LOC).
- `/applications/accounting/src/main/java/org/apache/ofbiz/accounting/invoice/InvoiceServices.java` lines 1392-1900 (createInvoicesFromShipments).
- `/applications/accounting/minilang/ledger/GeneralLedgerServices.xml` lines 1222-2150 (GR/IR posting bridges).

Market-pain agent cited (selected):
- [Spend Matters — Why AP Automation Falters at Scale](https://spendmatters.com/2026/02/17/why-ap-automation-delivers-early-wins-but-quietly-fails-to-scale/)
- [Stampli — 3-Way Match Automation](https://www.stampli.com/blog/ap-automation/3-way-match-automation/)
- [SoftCo — Improving First-Time-Match Rate](https://softco.com/blog/improving-your-first-time-match-rate-in-procure-to-pay/)
- [SAP Help — Tolerance Limits for Invoice Postings](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/b7eb2f9e70ab4c88abbff8b34a409b26/ec236b54f94c8f4ce10000000a4450e5.html)
- [SAP Community — GR/IR Clearing Issue](https://community.sap.com/t5/financial-management-blog-posts-by-members/gr-ir-clearing-issue/ba-p/13642852)
- [Coupa Benchmark — Requisition Times](https://www.coupa.com/blog/coupa-benchmark-requisition-approval-workflow-times/)
- [Odoo Issue #13974 — Refunded Returns Ignored in Vendor Bills](https://github.com/odoo/odoo/issues/13974)
- [PLANERGY — GRNI Reconciliation](https://planergy.com/blog/grni-reconciliation-process-benefits/)
- [Tipalti — 3-Way Match Guide](https://tipalti.com/resources/learn/3-way-match/)
- [BrokenRubik — NetSuite Landed Cost Guide](https://www.brokenrubik.com/blog/netsuite-landed-cost-guide)
- Plus ~40 more URLs in the full report (Coupa reviews, NetSuite docs, Stampli/Tipalti/AvidXchange/Bill.com docs, ERPNext + OFBiz issue trackers, SAP-Press, Spend Matters, peppol.eu, FAR/SAM.gov).

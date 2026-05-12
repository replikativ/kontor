# Research note 16 — Showcase feature-coverage audit

User directive (2026-05-12): "make sure that the different use cases cover all the features of kontor and exercise it properly; you can also add features if needed."

This note inventories what kontor primitives are exercised by showcases 1–3 and what's still uncovered. Drives Showcase 4 + Stage L cross-stage user-story validation.

## What the three showcases cover

| Primitive / ADR | Showcase 1 (DE) | Showcase 2 (US) | Showcase 3 (IN) |
|---|---|---|---|
| `:account`, `:journal`, `:gl-account-default` | ✓ | ✓ | ✓ |
| `:partner` + KYC stub | ✓ | ✓ | ✓ |
| `:commodity` (single-currency invoice) | EUR | USD | INR (+ EUR vendor) |
| `:invoice` + `:invoice-line` + state machine | ✓ | ✓ | ✓ |
| `:payment-application` (ADR-043) | partial + final + bitemporal | full | partial + final |
| Bitemporal `:as-of-valid` queries | ✓ (aging snapshots) | — | — |
| `:status-transition` + `:status-history` | ✓ (case + invoice + dispute) | ✓ | ✓ |
| `:audit-doc` | ✓ (dunning letters) | ✓ (dunning letters) | — |
| `:side-effect-intent` | ✓ (email send) | ✓ (email send) | — |
| `:collection-case` + `:dispute` + `:dunning-policy` | full Mahnverfahren | Reg-F + dispute | partial |
| Multi-jurisdiction tax | — | TaxProvider stub (multi-state US) | GST split + IRN |
| ADR-040 `:reverse-charge?` | — | — | ✓ (data shape) |
| ADR-040 `:withholding-on-payment?` | — | — | ✓ (data shape; §194J) |
| ADR-024 multi-attestation (`:pending-attestation`) | — | — | ✓ (IRN clearance) |
| `kontor-l10n-in` (taxes / IRN / identifiers) | — | — | ✓ |
| HSN-code on invoice-line | — | — | ✓ |

## What's NOT exercised by any showcase

These kontor primitives have schema + tests but no end-to-end showcase narrative:

1. **`:analytic-account` / `:analytic-distribution` / `:posting-analytic` (ADR-022)** — cost-center / profit-center reporting. Critical for SaaS, consulting, project-based businesses; none of the three showcases use it.
2. **`:entity` multi-entity intercompany (ADR-031)** — `:transaction/entity` + sum-to-zero per (entity, ledger, commodity). All three showcases use a single `:entity`.
3. **`:ledger` parallel ledger (ADR-021)** — IFRS vs local-GAAP reporting, or main-book vs management-book. No showcase exercises parallel postings.
4. **Stage K procurement (`kontor-procurement`)** — `:requirement`, `:receipt`, `:return`, drop-ship, 3-way match. Massive substrate, totally unexercised in showcases.
5. **Stage J sales-order bridge (`kontor-sales`)** — `:order`, `:order-item`, `make-invoice-from-order!`. Showcases write `:invoice` rows directly, skipping the order layer.
6. **`:period` lifecycle (ADR-014)** — soft lock, hard lock, sealed, special periods. Not exercised.
7. **`:approval-policy` enforcement (ADR-038)** — `:no-self-approval`, `:requires-supporting-doc`, `:requires-non-empty-reason-note`, `:requires-three-way-match-pass`. Schema in place, no showcase shows enforcement firing.
8. **`:credit-hold` / `:partner/credit-status` (ADR-039)** — referenced in Showcase 1 + 2 partner seeds but not exercised.
9. **`:bank-line` ingest** — all three showcases simulate cash receipts as plain `:transaction` rows.
10. **DATEV export (`kontor-l10n-de.datev`)** — Showcase 1 references but doesn't exercise.
11. **Factur-X PDF generation (`kontor-einvoice-de`)** — Showcase 1 references but doesn't render.
12. **`kontor-l10n-mx` complemento (ADR-025)** — no showcase.
13. **`kontor-l10n-br` NF-e** — no showcase.
14. **`:transaction/reverses` (ADR-007 reversal pattern)** — no showcase exercises a posted-then-reversed flow.

## Priority gap-fill

The four highest-impact gaps (most-load-bearing kernel primitives that no showcase touches):

- **G1**: `:analytic-account` cost-center routing — minimal scope, high reusability.
- **G2**: `:entity` multi-entity intercompany flow — exercises ADR-031 sum-to-zero per-entity. Critical for the "business OS" positioning.
- **G3**: Stage J sales-order → invoice bridge — proves the O2C chain works end-to-end. Currently showcases skip the bridge.
- **G4**: Stage K procurement P2P — minimum: requirement → PO → receipt → invoice → posted. Currently entirely unexercised by showcases.

## Recommendation

Add **Showcase 4: multi-entity intercompany + cost-center + procurement P2P + parallel ledger**. One coherent narrative covers G1 + G2 + G3 + G4. Fictional scenario:

> *Acme Industries Holding GmbH* (DE parent) owns *Acme NA LLC* (US sub). The US sub procures raw materials from a third-party vendor, manufactures finished goods, and sells them to a US distributor. The DE parent provides centralized accounting services to the US sub and bills internally via an intercompany invoice. Both legal entities close their fiscal Q2 — the intercompany payable + receivable nets to zero by ADR-031 invariant.
>
> Exercises: `:entity` × 2 (`:DE-PARENT`, `:US-SUB`), `:analytic-account` per project, `:ledger` × 2 (`:gaap-de`, `:gaap-us-mgmt`), procurement requirement → PO → receipt → vendor invoice → AP, then sales side: SO → invoice → AR → payment, then intercompany invoice between entities, then approval-policy enforcement on a manual write-off.

Out of scope for v1 audit: the deeply-deferred showcases (NF-e, complemento) and `:bank-line` ingest — those need l10n module attention or a dedicated bank-ingest showcase, both better as future work.

## Status

Pending implementation as Showcase 4. After Showcase 4 lands, the remaining uncovered items (G5–G14 from §"What's NOT exercised") will be documented as deferred-by-priority rather than substrate gaps.

Date: 2026-05-12.

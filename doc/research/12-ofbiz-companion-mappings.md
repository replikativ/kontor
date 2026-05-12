# 12 — Apache OFBiz deep study: companion-module mappings

**Date:** 2026-05-12
**Source agent:** general-purpose (background, 4.4 min, OFBiz checked out at `../ofbiz-framework`)
**Verified?** High — every claim cites a file path + line range in the OFBiz source.

OFBiz is the rare Apache-2.0 ERP — we can read AND lift, displacing Odoo as the structural reference oracle for the companion modules per research note 11. This note condenses the agent's findings across five concerns, identifies what translates cleanly to kontor's datahike idiom, and lists what to leave behind.

The full agent report is preserved in the conversation transcript; this note is the load-bearing summary feeding ADR-033 and the future ADRs for sales, procurement, and the accounting bridge.

## Five concerns surveyed

1. **Party foundation** — `applications/datamodel/entitydef/party-entitymodel.xml` (lines 993-2900). Drives ADR-033 (kontor-partner).
2. **Order lifecycle** — `applications/datamodel/entitydef/order-entitymodel.xml` (lines 415-1250). Drives future ADR for kontor-sales and kontor-procurement (one model, `orderTypeId` discriminator).
3. **Procurement-specific patterns** — `applications/order/entitydef/...` requirement entities (lines 2171-2314) + 3-way-match logic in `InvoiceServices.createInvoiceForOrder`.
4. **Product master** — `applications/datamodel/entitydef/product-entitymodel.xml` (Product, ProductPrice, GoodIdentification, ProductAssoc).
5. **Accounting bridge** — `applications/datamodel/entitydef/accounting-entitymodel.xml` (lines 1764-1940). The AcctgTrans / AcctgTransEntry / GlAccountTypeDefault pattern. Maps to kontor's existing `:transaction` / `:posting` plus the per-org account-mapping function.

## Party foundation (→ ADR-033)

OFBiz unifies Person and PartyGroup as **discriminator subtypes** of a canonical `Party` (`partyTypeId` ∈ {PERSON, PARTY_GROUP}). Mirrored by Tryton (`party.party` + `party.party.contact_mechanism`). Odoo's single `res.partner` with `is_company` flag is the alternative; we picked the OFBiz/Tryton shape (see ADR-033 alternatives).

**Load-bearing entities:**

| OFBiz entity | Lines | Role | Kontor name |
|---|---|---|---|
| `Party` | 1594-1634 | Root entity, `partyTypeId` discriminator, status, externalId | `:partner` (already in kernel; extended) |
| `Person` | 2800-2859 | Names (incl. `*Local` for non-Latin scripts), demographics, encrypted PII (SSN, passport) | `:person` |
| `PartyGroup` | 2089-2108 | Org name, officeSiteName, annualRevenue, tickerSymbol, numEmployees | `:org` |
| `ContactMech` | 993-1009 | Polymorphic root: `contactMechTypeId` + `infoString` fallback | `:contact-mech` |
| `PostalAddress` | 1237-1295 | toName, attnName, address1/2, city, postalCode, geo FKs | `:postal-address` |
| `TelecomNumber` | 1310-1331 | countryCode, areaCode, contactNumber, extension hint | `:telecom-number` |
| `PartyContactMech` | 1152-1198 | Junction with composite PK + `fromDate`/`thruDate` | `:partner-contact-mech` |
| `PartyContactMechPurpose` | 1199-1233 | Multi-purpose (one address → BILLING + SHIPPING + GENERAL_CORRESPONDENCE) | `:partner-contact-mech-purpose` |
| `PartyRole` | 2561-2580 | Capability assignment (CUSTOMER, SUPPLIER, EMPLOYEE, CARRIER, INTERNAL_ORGANIZATIO) | `:partner-role` |
| `PartyRelationship` | 2302-2358 | Temporal, multi-role; supports Person→Org (employment), Org→Org (subsidiary) | `:partner-relationship` |

**Patterns to lift verbatim:**

- The discriminator `partyTypeId` + subtype FK. One root entity owns identity (`:partner/external-id`, audit, status); subtypes carry their own attributes.
- ContactMech polymorphism — typed `:contact-mech/type` plus typed subtype entities. Round-trips cleanly to vCard 4.0 and Peppol Business Card (per note 11).
- Junction composite PK with temporal validity (`fromDate` / `thruDate`). OFBiz uses this on every association — it's the cleanest way to model "this was true between then and now".
- Multi-purpose routing — one ContactMech entry can serve multiple purposes (BILLING, SHIPPING, PRIMARY_EMAIL, etc.). Critical: do NOT collapse the purpose into the junction; keep a separate "purpose" association so a single address can flex roles without duplicate records.
- PartyRelationship's `(fromParty, fromRole) → (toParty, toRole)` shape — supports the four-quadrant relationship space (Person→Org employment, Org→Org subsidiary, Person→Person, Org→Person) without separate tables per case.

**Patterns to leave behind:**

- **UserLogin coupling.** OFBiz pairs every entity with `createdByUserLogin` / `lastModifiedByUserLogin`. Kontor uses centralized audit (`:create/uid`, `:write/uid` per kernel schema, plus the audit chain from ADR-007).
- **Minilang DSL.** OFBiz services use XML-driven business logic (`&lt;simple-method&gt;`). Pure Clojure replaces this; we never translate Minilang verbatim.
- **EntityEngine delegator abstraction.** OFBiz's JDBC mapping layer is heavy. Datahike + datalog replaces it.
- **Enumeration + EnumType recursive hierarchy.** OFBiz models type vocabularies as parent-child enum rows for i18n + grouping. Kontor uses Clojure keywords with a documented canonical set; consumers extend via reader-friendly keywords.

## Order lifecycle (→ future kontor-sales + kontor-procurement)

OFBiz's brilliant move: **one Order model for sales AND procurement.** `orderTypeId` ∈ {SALES_ORDER, PURCHASE_ORDER} discriminates; the underlying lifecycle (CREATED → APPROVED → COMPLETED) is identical. Roles invert (CUSTOMER↔SUPPLIER, BILL_TO↔BILL_FROM) but the structure doesn't.

**Load-bearing entities:**

- `OrderHeader` (415-489) — `orderId` PK, `orderTypeId`, `statusId`, `currencyUom`, `grandTotal` (denormalized), `invoicePerShipment` flag, fulfillment hints (`priority`, `needsInventoryIssuance`).
- `OrderItem` (520-631) — composite PK `(orderId, orderItemSeqId)`, `productId`, `quantity`, `unitPrice`, `unitListPrice`, `cancelQuantity`, `overrideGlAccountId` (critical for posting overrides).
- `OrderItemShipGroup` (905-972) — the ship-group abstraction. One item can span multiple ship groups; one ship group holds multiple items. Carries `shipmentMethodTypeId`, `carrierPartyId`, `facilityId`, `contactMechId`, `trackingNumber`, `maySplit`/`isGift`. **Not optional for real ecommerce.**
- `OrderItemShipGroupAssoc` (973-995) — item ↔ ship-group allocation with quantity.
- `OrderItemShipGrpInvRes` (996-1036) — inventory reservation, with `reserveOrderEnumId` (FIFO/LIFO/PRIORITY_ORDER) and `priority` flag.
- `OrderAdjustment` (48-110) — line-level OR header-level (nullable `orderItemSeqId`/`shipGroupSeqId`). Type discriminator (DISCOUNT, TAX, SHIPPING, SURCHARGE). `sourcePercentage`, `taxAuthorityRateSeqId`, `primaryGeoId`/`secondaryGeoId` for jurisdiction. `overrideGlAccountId` for explicit GL routing. `includeInTax`/`includeInShipping` recalc flags.
- `OrderRole` (1183-1209) — composite `(orderId, partyId, roleTypeId)`. Same partner can hold multiple roles (BILL_TO + SHIP_TO).
- `OrderStatus` (1245-1274) — status history; every transition recorded.

**Procurement-specific (within Order module):**

- `Requirement` (2171-2215) — requisition root; `requirementTypeId`, `facilityId`, `productId`, `quantity`, `requiredByDate`, status machine (CREATED → APPROVED → ORDERED → RECEIVED). Optional FKs to `deliverableId` (project context), `fixedAssetId` (maintenance), `facilityIdTo` (transfer).
- `RequirementRole` (2273-2295) — requester / approver / vendor roles, temporal.
- `RequirementStatus` (2296-2314) — audit trail.

**3-way match flow:** Requirement → PurchaseOrder (OrderHeader + OrderItem) → ShipmentReceipt → InvoiceItem. The actual match logic is in `applications/accounting/.../InvoiceServices.java#createInvoiceForOrder` (services_invoice.xml lines 190-202). Kontor-procurement will model the four-entity chain explicitly.

## Accounting bridge

OFBiz separates **invoice creation** from **GL posting** — invoices are created in DRAFT, transition to READY, and only then trigger AcctgTrans creation. The pattern is `Invoice → InvoiceItem → AcctgTrans → AcctgTransEntry`, with the bridge being `GlAccountTypeDefault(glAccountTypeId, organizationPartyId) → glAccountId`.

This is the **account-mapping function** pattern. Instead of hardcoding "SALES_REVENUE → 4000", a table encodes per-org rules. Kontor's ADR-019 external-codes already covers part of this; kontor-sales will surface the rest.

**Multi-currency:** `AcctgTransEntry` carries `currencyUomId`, `origAmount`, `origCurrencyUomId` — the posted amount and the original amount, supporting FX revaluation later.

**Multi-entity:** `AcctgTransEntry.organizationPartyId` is on the LINE (matching SAP's ACDOCA.RBUKRS pattern; same as kontor's `:posting/entity` per ADR-031).

## Product master (→ future kontor-product)

OFBiz Product (2799-2872) is rich. The bits worth lifting for the lightweight reference master:

- `productId` PK + discriminator `productTypeId` (GOOD vs SERVICE vs AGGREGATED).
- `isVirtual` / `isVariant` for variant modeling (parent + concrete variants).
- `requireInventory` / `requireAmount` flags.
- `taxable`, `chargeShipping`, `returnable` business flags.
- Lifecycle dates (`introductionDate`, `supportDiscontinuationDate`, `salesDiscontinuationDate`).
- `ProductPrice` (2506-2575) — composite PK `(product, type, purpose, currency, storeGroup, fromDate)` with `price`, `priceWithoutTax`/`priceWithTax`, `taxAuthPartyId`/`taxAuthGeoId`. Multi-dimensional pricing.
- `GoodIdentification` (2718-2735) — polymorphic ID (SKU, UPC, EAN, GTIN, MPN).
- `ProductAssoc` (2935-2973) — composition for kits/bundles/variants/substitutes (`productAssocTypeId`).

What to skip: ProductConfig+ConfigOption (bespoke configurator), Minilang-driven price calcs, manufacturing BOM coupling (deferred to kontor-mfg if ever).

## Anti-patterns to leave behind (cross-cutting)

1. **ECA (Entity Change Action) XML triggers.** OFBiz uses `entitydef/eecas.xml` for post-mutation Minilang side effects. Kontor uses explicit event-stream consumers and posting middleware (ADR-007 sealing pattern).
2. **GlJournal batching.** OFBiz has GlJournal as a posting-batch concept. Kontor uses transaction aggregates (`:transaction` IS the batch unit).
3. **Tax calculation service chain.** OFBiz invokes Minilang plugins per tax engine; kontor defers to `TaxProvider` protocol (ADR-005).
4. **Entity-level audit logging.** OFBiz's `enable-audit-log` attribute. Kontor uses ADR-007's sealing + ADR-009's audit chain centrally.

## Prioritized file list for downstream ADRs

| Priority | File | Coverage |
|---|---|---|
| 1 | `applications/datamodel/entitydef/party-entitymodel.xml` lines 993-2900 | All party, contact, relationship entities (ADR-033). |
| 1 | `applications/datamodel/entitydef/order-entitymodel.xml` lines 415-1250 | OrderHeader, OrderItem, ShipGroup, Adjustment, Role, Status. |
| 1 | `applications/datamodel/entitydef/accounting-entitymodel.xml` lines 1159-1940 | Invoice, InvoiceItem, AcctgTrans, AcctgTransEntry, GlAccountTypeDefault. |
| 2 | `applications/accounting/.../InvoiceServices.java#createInvoiceForOrder` | Canonical Order→Invoice→AcctgTrans posting pattern. |
| 2 | `applications/party/.../PartyServices.java` | Party lifecycle service shapes — read for workflow context, don't translate. |
| 2 | `applications/order/entitydef` requirement entities (2171-2314) | Procurement requisitions + MRP integration. |
| 3 | `applications/datamodel/entitydef/product-entitymodel.xml` (Product, ProductPrice, GoodIdentification, ProductAssoc) | Product master — skim before kontor-product. |
| 3 | `applications/order/servicedef/*.xml`, `applications/accounting/servicedef/*.xml` | Service interface shapes — skim for parameter conventions. |

## Top 7 design insights (informing build order)

1. **Party-as-root pattern translates cleanly.** Discriminator + subtype FK is what every reference system (OFBiz, Tryton, Workday, SAP-BP) converges on. Odoo's single-table-with-flags is the outlier.
2. **Ship-group abstraction is load-bearing.** Without `OrderItemShipGroup`, multi-destination fulfillment is impossible. Must land in kontor-sales v1.
3. **Adjustments flow vertically into accounting.** `OrderAdjustment → InvoiceItem → AcctgTransEntry`, with `overrideGlAccountId` on each level. ADR-018 already models this; kontor-sales wires it.
4. **`GlAccountTypeDefault` is the account-mapping function.** Per-org GL lookup table replaces hardcoded "SALES_REVENUE → 4000". Mirrors kontor's `:account/external-codes` (ADR-019) but for per-org account routing — likely a small extension.
5. **Multi-tax requires jurisdiction breakdown per line.** OrderAdjustment with `(taxAuthPartyId, taxAuthGeoId, sourcePercentage)` is the canonical shape. Aligns with ADR-016.
6. **3-way match needs all four entities linked.** Requirement → PO → Receipt → InvoiceItem. Without explicit links, audit + reconciliation break.
7. **Anti-patterns: avoid XML-driven business logic.** Minilang, ECAs, the delegator. Kontor's "pure Clojure + datahike" gives us a much simpler stack with the same expressiveness.

## What's NOT in this report

- The full agent transcript (~4000 words, with concrete `partyContactMechPurpose` flag semantics and InvoiceServices method bodies) is preserved in the conversation history. This note is the design-shaping summary.
- Concrete schema shapes for kontor-partner are in ADR-033, not here.
- Order/invoice ADRs (sales + procurement) are deferred to their respective stages — this note primes them.

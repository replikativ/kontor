# 35 — Apache OFBiz deep study: inventory / warehouse model (→ kontor-inventory)

**Date:** 2026-05-14
**Source:** OFBiz checked out at `../ofbiz-framework` (Apache-2.0 — kontor MAY lift patterns and adapt code).
**Verified?** High — every claim cites an OFBiz `file:line`.
**Feeds:** research note 34 §2.1 (named `kontor-inventory` the #1 functional gap); the future ADR(s) for `modules/inventory`.

OFBiz is the license-clean ERP we can read *and* lift. This note studies its inventory/warehouse layer at `file:line` depth and maps it onto kontor's existing substrate. The central finding: **OFBiz conflates physical tracking and cost valuation in a single `InventoryItem` row; kontor has already split valuation out into `:valuation-*` (ADR-030 era). So `kontor-inventory` must be the *physical/operational* layer that sits ALONGSIDE `:valuation-layer`, not a port of `InventoryItem` whole.** The entity split is the hardest design call and is spelled out in §4.

---

## 1. The data model

All inventory entities live in `applications/datamodel/entitydef/product-entitymodel.xml`, package `org.apache.ofbiz.product.{facility,inventory}`.

### 1.1 Facility / FacilityLocation — the warehouse tree

`Facility` (`product-entitymodel.xml:996-1050`) is a node in a self-referential tree (`parentFacilityId`, `facilityLevel`) typed by `FacilityType` (`:1404-1414`, also a tree). Key fields: `facilityId` (PK), `facilityTypeId`, `parentFacilityId`, `ownerPartyId`, `facilityName`, `productStoreId`, `defaultInventoryItemTypeId`, `defaultDaysToShip` (`:1007` — used by reservation promise-date calc), `openedDate`/`closedDate`. Size/UOM/geo fields are warehouse-management cruft.

`FacilityLocation` (`:1281-1302`) is a *bin* within a facility: composite PK `(facilityId, locationSeqId)`, plus a structured address `areaId/aisleId/sectionId/levelId/positionId` and `locationTypeEnumId` — an `Enumeration` that matters: `FLT_PICKLOC` (pick face) vs `FLT_BULK` (bulk storage). The reservation algorithm (§2) walks pick locations before bulk (`InventoryReserveServices.xml:91,114`).

`ProductFacility` (`:1431-1450`) is the per-(product,facility) policy row: `minimumStock`, `reorderQuantity`, `daysToShip`, `replenishMethodEnumId`, `lastInventoryCount` (a denormalized ATP cache, `:1438`). This is the reorder-point / replenishment config, not stock itself.

### 1.2 InventoryItem — the physical stock record (and OFBiz's valuation conflation)

`InventoryItem` (`product-entitymodel.xml:1953-2041`) is *the* core entity. PK `inventoryItemId`. It is **one row per (product, facility, location, lot, owner, status) bucket of stock** — not one row per physical unit, except for serialized items. Fields, grouped:

- **Physical identity:** `productId`, `facilityId`, `locationSeqId`, `containerId`, `binNumber`, `lotId`, `uomId`, `ownerPartyId`, `partyId`.
- **Type & status:** `inventoryItemTypeId` → `InventoryItemType` (`:2278-2290`), values `NON_SERIAL_INV_ITEM` | `SERIALIZED_INV_ITEM`. `statusId` → `StatusItem` (`INV_AVAILABLE`, `INV_PROMISED`, `INV_DELIVERED`, `INV_ON_HOLD`, `INV_DEFECTIVE`, `INV_NS_ON_HOLD`, `INV_NS_DEFECTIVE`…).
- **Quantity (the denormalized cache):** `quantityOnHandTotal` (QOH), `availableToPromiseTotal` (ATP), `accountingQuantityTotal`. These three are **rollup caches** — the truth is the append-only `InventoryItemDetail` ledger (§1.3).
- **Lot / serial / lifecycle:** `serialNumber`, `softIdentifier`, `activationNumber`, `datetimeReceived`, `datetimeManufactured`, `expireDate`.
- **VALUATION (the conflation):** `unitCost`, `currencyUomId`, `fixedAssetId`. **This is the half kontor already owns** — `:valuation-layer/unit-cost-original` + `:valuation-book/commodity`. OFBiz puts cost on the same row as the bin location; kontor split it.

Key relations: `InventoryItemAndLocation` view-entity (`:2042-2075`) joins `InventoryItem` + `Product` + `FacilityLocation` — this is what the reservation query actually runs against. `InventoryItemAndDetail` (`:2092-2124`) and `InventoryItemDetailSummary` (`:2193-2201`) join/aggregate the detail ledger.

Supporting: `InventoryItemStatus` (`:2222-2244`) — append-only status-history with `statusDatetime`/`statusEndDatetime` (a bitemporal-ish history table). `InventoryItemAttribute` / `InventoryItemLabel*` (`:2076-2371`) — EAV decoration, skip.

### 1.3 InventoryItemDetail — the append-only quantity-delta ledger (the kontor-shaped pattern)

`InventoryItemDetail` (`product-entitymodel.xml:2125-2192`) is **the most important entity in this study.** It is an **append-only ledger of quantity deltas** against an `InventoryItem`. PK `(inventoryItemId, inventoryItemDetailSeqId)`. Fields:

- `effectiveDate` — when the delta took effect (valid-time).
- `quantityOnHandDiff`, `availableToPromiseDiff`, `accountingQuantityDiff` — **signed deltas**, never absolute values. A receipt is `+5/+5`; a reservation is `0/-3` (QOH unchanged, ATP drops); an issuance is `-3/0`; a variance is `±n/±n`.
- **Source pointers** (which event caused this delta): `orderId`+`orderItemSeqId`+`shipGroupSeqId`, `shipmentId`+`shipmentItemSeqId`, `returnId`, `workEffortId`, `itemIssuanceId`, `receiptId`, `physicalInventoryId`, `fixedAssetId`+`maintHistSeqId`. Plus `reasonEnumId` (variance reason) and free-text `description`.

**The invariant:** `InventoryItem.quantityOnHandTotal == Σ InventoryItemDetail.quantityOnHandDiff` (and likewise ATP, accountingQty). OFBiz keeps the rollup as a cache on `InventoryItem` and recomputes it from the `InventoryItemDetailSummary` aggregate view (`:2193-2201`, a `function="sum"` group-by) via `updateInventoryItemFromDetail` (`InventoryServices.xml:141-149`). **Crucially, OFBiz never UPDATEs a detail row and never UPDATEs the quantity directly — it always appends a new detail** (the code comments say so explicitly: "instead of updating InventoryItem, add an InventoryItemDetail", `InventoryReserveServices.xml:452`, `:292`).

**This is exactly kontor's sealing discipline applied to quantities.** It is the same shape as `:layer-consumption` against `:valuation-layer`. Lift this pattern verbatim — it is the spine of `kontor-inventory`.

### 1.4 InventoryItemVariance / PhysicalInventory — cycle counts

`PhysicalInventory` (`:2428-2436`) is a thin count-event header: `physicalInventoryId` (PK), `physicalInventoryDate`, `partyId` (who counted), `generalComments`. `InventoryItemVariance` (`:2309-2329`) is the per-item line of a count: PK `(inventoryItemId, physicalInventoryId)`, `varianceReasonId` → `VarianceReason`, `availableToPromiseVar`, `quantityOnHandVar`, `comments`. Creating a variance **also appends an `InventoryItemDetail`** carrying the same diffs (`InventoryServices.xml:280-309`) — the variance row is the *audit document*, the detail row is the *ledger effect*. `PhysicalInventoryAndVariance` view (`:2437-2462`) joins them.

### 1.5 InventoryTransfer — moving stock between facilities/locations

`InventoryTransfer` (`:2372-2418`): `inventoryTransferId` (PK), `statusId` (IXF_REQUESTED → IXF_COMPLETE / IXF_CANCELLED), `inventoryItemId`, the *from* triple `facilityId`/`locationSeqId`/`containerId`, the *to* triple `facilityIdTo`/`locationSeqIdTo`/`containerIdTo`, `sendDate`, `receiveDate`, `comments`. It is a **two-phase document**: `prepareInventoryTransfer` reserves/splits the source item, `completeInventoryTransfer` lands it at the destination (§3.4).

### 1.6 Lot — batch tracking

`Lot` (`:2419-2427`) is dead simple: `lotId` (PK), `creationDate`, `quantity`, `expirationDate`. `InventoryItem.lotId` FKs to it. **kontor already has `:lot/*`** in `src/kontor/schema.clj:99-117` (`:lot/commodity`, `:lot/acquired-at`, `:lot/cost-basis`, `:lot/label`) and `:valuation-layer/lot`. kontor's `:lot` is richer (cost-basis aware). **Reuse `:lot` as-is; do not introduce an inventory-specific lot.**

Serial tracking is *not* a separate entity in OFBiz — a serialized unit is just an `InventoryItem` with `inventoryItemTypeId = SERIALIZED_INV_ITEM`, `serialNumber` set, and `QOH/ATP ∈ {0,1}` driven by `statusId` (`InventoryServices.xml:151-211` `updateSerializedInventoryTotals`).

---

## 2. The reservation model

### 2.1 OrderItemShipGrpInvRes — the reservation row

`OrderItemShipGrpInvRes` (`order-entitymodel.xml:996-1035`, `never-cache="true"`) is the reservation link between an order line and a specific `InventoryItem`. PK `(orderId, shipGroupSeqId, orderItemSeqId, inventoryItemId)` — note: **a single order line fans out into multiple reservation rows, one per InventoryItem it draws from.** Fields: `quantity` (reserved against this item), `quantityNotAvailable` (the shortfall — back-order quantity), `reservedDatetime`, `createdDatetime`, `promisedDatetime` + `currentPromisedDate`, `priority` (indicator), `sequenceId` (numeric — manual ordering), and `reserveOrderEnumId` (the strategy, §2.3).

**kontor's `modules/sales` already ships this** as `:inv-reservation/*` (`modules/sales/src/kontor/sales/schema.clj:331-389`): `:inv-reservation/{order,order-item,ship-group,lot,quantity,quantity-not-available,reserve-order-enum,reserved-datetime,promised-datetime,current-promised-date,priority?}`, keyed by a `:db/tupleAttrs` identity `[order-item ship-group lot]`. **Note the divergence:** kontor's tuple key uses `:inv-reservation/lot`, not an `inventory-item` ref — because at the time `sales` was written there was no inventory-item entity to point at. **§4 calls this out as a fix-up: the reservation must point at the new `:inventory-item`.**

### 2.2 The reservation walk (`reserveProductInventory`)

`InventoryReserveServices.xml:23-160`. Given `(productId, facilityId?, quantity, reserveOrderEnumId, requireInventory)`:

1. Skip non-physical products (`productType.isPhysical = N`, `:43`).
2. Build an `orderByString` from `reserveOrderEnumId` (§2.3).
3. Query `InventoryItemAndLocation` for `availableToPromiseTotal > 0`, excluding `INV_*_DEFECTIVE` statuses, **`locationTypeEnumId = FLT_PICKLOC` first** (`:84-96`).
4. Iterate, calling `reserveForInventoryItemInline` per item until `quantityNotReserved` hits 0 (`:97-103`).
5. Still short? Repeat for `FLT_BULK` locations (`:106-124`), then items with **no location** (`:127-153`).
6. Still short and `requireInventory = N`? Push the remainder onto the *last* non-serialized item as a **negative ATP** (back-order) via one more `InventoryItemDetail` (`:160-300` region). If `requireInventory = Y`, return `quantityNotReserved` to the caller.

`reserveForInventoryItemInline` (`:395-493`) is the per-item core: for `NON_SERIAL_INV_ITEM`, `deductAmount = min(quantityNotReserved, item.availableToPromiseTotal)`; **append an `InventoryItemDetail` with `availableToPromiseDiff = -deductAmount` and `quantityOnHandDiff = 0`** (`:452-461`); then create the `OrderItemShipGrpInvRes` row (`:463-479`). For `SERIALIZED_INV_ITEM` it instead flips `statusId` to `INV_PROMISED` and writes a `quantity=1` reservation (`:399-425`). **Reservation moves ATP, never QOH** — the stock is still physically there, just promised.

### 2.3 reserveOrderEnumId — the strategy enum

`InventoryReserveServices.xml:52-79` maps the enum to a sort order over candidate `InventoryItem`s:

| `reserveOrderEnumId` | Sort | Meaning |
|---|---|---|
| `INVRO_FIFO_REC` (default) | `+datetimeReceived` | oldest received first |
| `INVRO_LIFO_REC` | `-datetimeReceived` | newest received first |
| `INVRO_FIFO_EXP` | `+expireDate` | nearest expiry first (perishables) |
| `INVRO_LIFO_EXP` | `-expireDate` | |
| `INVRO_GUNIT_COST` | `-unitCost` | greatest cost first |
| `INVRO_LUNIT_COST` | `+unitCost` | least cost first |

**Design note:** this is a *physical-picking* strategy (which bin to pull from), **distinct from** the `:valuation-book/cost-method` (FIFO/LIFO/AVG — how cost layers are *consumed* for COGS). They happen to share names. kontor must keep them separate: `:inv-reservation/reserve-order-enum` already exists in `sales`; the *valuation* cost-method is `:valuation-book/cost-method`. Picking strategy ≠ costing method. (In practice a warehouse picks FIFO-by-receipt and values FIFO too, but the substrate must not assume it.)

### 2.4 Promise dates & priority

`getPromisedDateTime` (`:495-510`): `promisedDatetime = orderDate + daysToShip`, where `daysToShip` falls back `ProductFacility.daysToShip → Facility.defaultDaysToShip → 30`. `setOrderReservationPriority` (`InventoryServices.xml:914`) lets an order jump the queue; `reassignInventoryReservations` (`:498`) and `balanceInventoryItems` (`:476`) re-sort reservations when stock arrives — back-ordered (negative-ATP) reservations get satisfied from new receipts in priority/sequence order.

---

## 3. The operations

### 3.1 QOH vs ATP — the two quantities

- **QOH (`quantityOnHandTotal`)** = physically present in the bin. Changed only by *physical* events: receipt (`+`), issuance/shipment (`-`), variance (`±`), transfer-complete at destination (`+`)/source (`-`).
- **ATP (`availableToPromiseTotal`)** = QOH − outstanding reservations + (in some configs) scheduled receipts. Changed by reservations (`-`), reservation-cancels (`+`), AND every physical event (since QOH moves). Can go **negative** = back-order.
- `accountingQuantityTotal` — a third quantity that tracks the count the *accounting* system believes in; usually equals QOH but can diverge during in-transit transfers. **This is the seam where OFBiz's `InventoryItem` reaches into accounting — kontor replaces it with the `:valuation-layer` qty.**

`getProductInventoryAvailable` (`InventoryServices.xml:325-397`) sums QOH/ATP across all `InventoryItem`s for a product (optionally facility-scoped). `countProductInventoryOnHand` (`:400-411`) sums `quantityOnHandDiff` from `InventoryItemDetail` **as of a date** (`effectiveDate` filter) — i.e. *historical* QOH is a bitemporal query over the detail ledger. This is the kontor-aligned access path.

### 3.2 Receive (`ShipmentReceipt` → `createInventoryItemDetail`)

`ShipmentReceipt` (`shipment-entitymodel.xml:389`) records goods arriving against a PO. `receiveInventoryProduct` (in `ShipmentReceiptServices.groovy`) creates (or finds) an `InventoryItem` for the (product, facility, location, lot) bucket and appends an `InventoryItemDetail` with `+qty/+qty` diffs and `receiptId` set. **kontor's `modules/procurement` already does the valuation half** — it feeds purchase receipts into `:valuation-layer`s (note 34 §2.1). `kontor-inventory` adds the *physical* half: the same receipt event also appends an `:inventory-detail` against an `:inventory-item`. One receipt → two ledger writes (valuation layer + inventory detail), same transaction.

### 3.3 Issue (`ItemIssuance` → negative `InventoryItemDetail`)

`ItemIssuance` (`shipment-entitymodel.xml:44`) records stock leaving against an order/shipment. `IssuanceServices.xml` + `InventoryIssueServices.groovy` decrement QOH by appending an `InventoryItemDetail` with `quantityOnHandDiff = -qty`, `itemIssuanceId` set, and **consume the matching `OrderItemShipGrpInvRes`** (the reservation is "spent"). Mirrors kontor's `plan-stock-move` Dr-COGS/Cr-inventory on the valuation side.

### 3.4 Transfer (two-phase)

`createInventoryTransfer` (`InventoryServices.xml:740`) opens the document. `prepareInventoryTransfer` (`InventoryServices.java:66-180`) reserves the qty at the source — for a partial transfer it **splits the `InventoryItem`**: creates a new item, moves qty across via paired `InventoryItemDetail`s. `completeInventoryTransfer` (`InventoryServices.java:221-320`), triggered by SECA on status→complete (`secas.xml:54,64`): for `NON_SERIAL_INV_ITEM` appends an adjusting `InventoryItemDetail` to **re-sync ATP to QOH** (`InventoryServices.java:258-284` — `availableToPromiseDiff = QOH − ATP`), then `updateInventoryItem` re-points `facilityId`/`locationSeqId` to the destination and re-assigns `ownerPartyId` if the destination facility has a different owner. `cancelInventoryTransfer` (`:336`) reverses it.

### 3.5 Cycle count / physical inventory

`createPhysicalInventoryAndVariance` (`InventoryServices.xml:311-319`) is the entry point: creates the `PhysicalInventory` header then a `InventoryItemVariance` line — which itself appends the `InventoryItemDetail` carrying the variance diff (§1.4). A full cycle count is N calls, one per counted item. The variance reason (`VarianceReason` enum) classifies shrinkage/damage/found.

---

## 4. The mapping verdict — entity split

The governing principle: **OFBiz `InventoryItem` = physical bucket + cost. kontor already owns the cost half (`:valuation-*`). `kontor-inventory` is the physical half ONLY, sitting alongside `:valuation-layer`, linked by a shared `(item, lot, facility)` grain.**

| OFBiz entity / field | Maps onto | New namespace? | Verdict |
|---|---|---|---|
| `Facility`, `FacilityType` | — | **NEW `:facility/*`** | **LIFT.** New. Self-ref tree, typed. Drop size/geo/UOM cruft; keep `parent`, `type`, `owner-entity` (→ kontor `:entity` ADR-031, not `Party`), `name`, `default-days-to-ship`, `opened-at`/`closed-at`. |
| `FacilityLocation` | — | **NEW `:facility-location/*`** | **LIFT.** Bin within facility. Composite identity `(facility, seq-id)` via `:db/tupleAttrs`. Keep `location-type` (`:pickloc`/`:bulk` — drives reservation walk) + structured `area/aisle/section/level/position`. |
| `ProductFacility` | — | **NEW `:facility-product/*`** | **LIFT (trim).** Per-(product,facility) reorder policy: `min-stock`, `reorder-qty`, `days-to-ship`, `replenish-method`. Drop `lastInventoryCount` denorm — compute it. |
| `InventoryItem` *physical fields* (`productId`, `facilityId`, `locationSeqId`, `lotId`, `inventoryItemTypeId`, `statusId`, `serialNumber`, `datetimeReceived`, `expireDate`, `binNumber`, `containerId`) | — | **NEW `:inventory-item/*`** | **LIFT (physical subset only).** One row per (product, facility, location, lot, owner, status) bucket. `:inventory-item/kind` ∈ `{:non-serial :serialized}`. `:inventory-item/status` joins the kernel `:status-transition` table (ADR-034). |
| `InventoryItem.quantityOnHandTotal` / `availableToPromiseTotal` | rollup cache | derived | **DON'T STORE (or store as explicit cache).** Compute QOH = `Σ :inventory-detail/qoh-diff`, ATP likewise — bitemporal query over the detail ledger. Optionally a denormalized cache attr, but the ledger is truth (kontor culture: derive, don't denormalize silently). |
| `InventoryItem.unitCost` / `currencyUomId` / `accountingQuantityTotal` / `fixedAssetId` | **`:valuation-layer/unit-cost-original`, `:valuation-book/commodity`, `:valuation-layer/qty-*`** | — | **DO NOT LIFT.** This is the conflation kontor already resolved. `:inventory-item` carries NO cost. Cost lives in `:valuation-layer`; the two are joined on shared `(item, lot)` + facility. |
| `InventoryItemDetail` | shape-twin of `:layer-consumption` | **NEW `:inventory-detail/*`** | **LIFT VERBATIM — the spine.** Append-only signed-delta ledger: `:inventory-detail/{inventory-item, effective-date, qoh-diff, atp-diff, reason, description}` + source pointers (`order`, `order-item`, `ship-group`, `shipment`, `return`, `receipt`, `issuance`, `physical-inventory`, `transfer`, `work-effort`). Never updated, only appended — natural fit for sealing discipline (ADR-007). |
| `InventoryItemStatus` | — | **NEW `:inventory-item-status/*`** *(or rely on datahike history)* | **DESIGN CALL.** OFBiz keeps an explicit status-history table. kontor gets entity history *for free* from datahike's bitemporal store — a `:as-of-tx` query over `:inventory-item/status` recovers it. **Recommend: skip the explicit table; document the bitemporal query helper instead.** |
| `InventoryItemVariance` | — | **NEW `:inventory-variance/*`** | **LIFT.** The audit document for a count line: `(inventory-item, physical-inventory)` identity, `variance-reason`, `qoh-var`, `atp-var`. Pairs with an `:inventory-detail` carrying the same diff. |
| `PhysicalInventory` | — | **NEW `:physical-inventory/*`** | **LIFT.** Thin count-event header: `count-date`, `counted-by` (→ `:partner`), `comments`. |
| `InventoryTransfer` | — | **NEW `:inventory-transfer/*`** | **LIFT.** Two-phase document: `status` (kernel state machine), `inventory-item`, from-`(facility,location)`, to-`(facility,location)`, `send-date`, `receive-date`. Completion appends paired `:inventory-detail`s. |
| `OrderItemShipGrpInvRes` | **`:inv-reservation/*` ALREADY in `modules/sales`** | exists — **FIX-UP** | **ALREADY MAPPED, needs re-point.** `modules/sales` keyed `:inv-reservation` identity on `[order-item ship-group lot]` with `:inv-reservation/lot` because no inventory-item existed. **Add `:inv-reservation/inventory-item` ref and move the tuple identity to `[order-item ship-group inventory-item]`** so reservations bind to a physical bucket, matching OFBiz's PK. This is the load-bearing integration fix. |
| `reserveOrderEnumId` | **`:inv-reservation/reserve-order-enum` ALREADY in `sales`** | exists | **ALREADY MAPPED.** Keep the enum values (`:fifo-rec`/`:lifo-rec`/`:fifo-exp`/`:lifo-exp`/`:greatest-cost`/`:least-cost`). **Keep it conceptually separate from `:valuation-book/cost-method`** — picking strategy ≠ costing method (§2.3). |
| `Lot` | **`:lot/*` ALREADY in kernel** (`schema.clj:99-117`) | exists | **REUSE.** kontor's `:lot` is richer (cost-basis aware). `:inventory-item/lot` and `:valuation-layer/lot` point at the *same* `:lot` — this is the join that ties physical and financial halves together. |
| serialized units | `:inventory-item/kind :serialized` + `:inventory-item/serial-number` | — | **LIFT pattern.** No separate entity; QOH/ATP ∈ {0,1} driven by status. |
| `InventoryItemAttribute`, `InventoryItemLabel*`, `FacilityAttribute`, `Container`, `FacilityCalendar`, `FacilityContactMech`, `FacilityGroup` | — | — | **LEAVE.** EAV decoration, WMS extras. Consumers add if needed. |

### The join that makes the split work

`:inventory-item` and `:valuation-layer` are **two views of the same goods**, joined on `(:valuation-layer/item ≡ product, :valuation-layer/lot ≡ :inventory-item/lot)` and facility scope. A goods receipt writes **both** in one transaction: a `:valuation-layer` (cost) and an `:inventory-item` + `:inventory-detail` (physical). An issuance writes **both**: a `:layer-consumption` (COGS) and an `:inventory-detail` with negative `qoh-diff`. `plan-stock-move` (ADR-030) already builds the GL side; `kontor-inventory` adds the physical-ledger side. The maintainer should provide a single `receive!` / `issue!` helper that writes both halves atomically so they cannot drift.

---

## 5. What to leave behind

- **EntityEngine / service-engine / minilang / SECA / Groovy.** OFBiz's `simple-method` XML, `entity-auto` CRUD, SECA event-condition-actions, the delegator/dispatcher — all replaced by datahike transactions + plain Clojure functions + transaction-middleware (kontor already has the sealing middleware pattern for this).
- **The `InventoryItem` quantity-cache update dance.** `updateInventoryItemFromDetail` re-summing a view into a stored field is a SQL-era workaround. kontor derives QOH/ATP from the `:inventory-detail` ledger on read; a denormalized cache is optional and explicit.
- **`accountingQuantityTotal` / `unitCost` / `currencyUomId` on the physical item.** The valuation conflation — kontor already split it. Do not re-merge.
- **`InventoryItemStatus` as a table.** datahike's bitemporal history gives status-over-time for free (§4).
- **WMS surface area:** `Container`, `FacilityCalendar`, picklists, packing, `binNumber` geometry, facility size/geo. Operational warehouse-management — out of scope for an accounting-adjacent inventory ledger; a future `kontor-wms` consumer can add it.
- **All UI** — `webapp/facility`, `widget/facility`, `template/inventory/*.ftl`. ADR-010: no UI in kontor.
- **`ProductStore` coupling.** `Facility.productStoreId` ties warehouses to a storefront concept kontor does not have. Drop the FK; if a consumer needs it, it adds its own attr.
- **`InventoryItemTempRes`** (`:2260-2277`) — ecommerce shopping-cart soft-hold keyed by `visitId`. That is a storefront concern (beleg/simmis), not kernel inventory.

---

## 6. Proposed `:inventory-*` / `:facility-*` namespace sketch

New namespaces (each needs an ADR per the naming convention):
`:facility/* :facility-location/* :facility-product/* :inventory-item/* :inventory-detail/* :inventory-variance/* :physical-inventory/* :inventory-transfer/*`.
Reuses existing: `:lot/* :valuation-layer/* :valuation-book/* :entity/* :partner/* :status-transition` (kernel), and **fixes up** `:inv-reservation/*` (in `modules/sales`).

```clojure
;; modules/inventory/src/kontor/inventory/schema.clj  (sketch)

;; --- Facility tree -----------------------------------------------------
:facility/code            unique-identity string
:facility/name            string
:facility/type            keyword         ; :warehouse :store :plant :transit
:facility/parent          ref -> :facility
:facility/owner-entity    ref -> :entity  ; ADR-031, NOT Party
:facility/default-days-to-ship long
:facility/opened-at       instant
:facility/closed-at       instant

:facility-location/facility      ref -> :facility
:facility-location/seq-id        string
:facility-location/identity      tuple [facility seq-id]   ; :db.unique/identity
:facility-location/type          keyword  ; :pickloc :bulk :staging  (drives reservation walk)
:facility-location/area          string   ; + aisle / section / level / position

:facility-product/facility       ref -> :facility
:facility-product/product        ref      ; consumer's product entity
:facility-product/min-stock      bigdec
:facility-product/reorder-qty    bigdec
:facility-product/days-to-ship   long
:facility-product/replenish-method keyword

;; --- Physical stock bucket (NO cost — cost lives in :valuation-layer) --
:inventory-item/product          ref
:inventory-item/facility         ref -> :facility
:inventory-item/location         ref -> :facility-location
:inventory-item/lot              ref -> :lot          ; SAME :lot as :valuation-layer/lot
:inventory-item/owner-entity     ref -> :entity
:inventory-item/kind             keyword  ; :non-serial | :serialized
:inventory-item/status           keyword  ; :status-transition table, ADR-034
:inventory-item/serial-number    string   ; serialized only
:inventory-item/received-at      instant
:inventory-item/expire-at        instant
;; QOH / ATP are DERIVED from :inventory-detail — not stored (or explicit cache attr)

;; --- Append-only signed-delta ledger (THE SPINE — sealing discipline) --
:inventory-detail/inventory-item ref -> :inventory-item
:inventory-detail/effective-date instant
:inventory-detail/qoh-diff       bigdec   ; signed: receipt +, issue -, variance ±
:inventory-detail/atp-diff       bigdec   ; signed: reservation -, cancel +, ...
:inventory-detail/reason         keyword  ; variance/movement reason enum
:inventory-detail/description    string
;; source pointers — exactly one is typically set:
:inventory-detail/order          ref
:inventory-detail/order-item     ref
:inventory-detail/ship-group     ref
:inventory-detail/receipt        ref      ; -> procurement receipt
:inventory-detail/issuance       ref      ; -> shipment issuance
:inventory-detail/physical-inventory ref
:inventory-detail/transfer       ref -> :inventory-transfer
;; invariant: :inventory-item QOH == Σ qoh-diff ; ATP == Σ atp-diff

;; --- Cycle count -------------------------------------------------------
:physical-inventory/count-date   instant
:physical-inventory/counted-by   ref -> :partner
:physical-inventory/comments     string

:inventory-variance/inventory-item   ref -> :inventory-item
:inventory-variance/physical-inventory ref -> :physical-inventory
:inventory-variance/identity     tuple [inventory-item physical-inventory]
:inventory-variance/reason       keyword  ; :shrinkage :damage :found :recount
:inventory-variance/qoh-var      bigdec
:inventory-variance/atp-var      bigdec

;; --- Transfer (two-phase) ---------------------------------------------
:inventory-transfer/status       keyword  ; :requested :in-transit :complete :cancelled
:inventory-transfer/inventory-item ref -> :inventory-item
:inventory-transfer/from-facility  ref -> :facility
:inventory-transfer/from-location  ref -> :facility-location
:inventory-transfer/to-facility    ref -> :facility
:inventory-transfer/to-location    ref -> :facility-location
:inventory-transfer/send-date      instant
:inventory-transfer/receive-date   instant

;; --- FIX-UP to modules/sales :inv-reservation -------------------------
;; ADD:
:inv-reservation/inventory-item  ref -> :inventory-item
;; CHANGE identity tuple from [order-item ship-group lot]
;;                         to [order-item ship-group inventory-item]
```

### Core operations (plain Clojure, no service-engine)

- `on-hand-qty` / `available-to-promise` — `(reduce + ...)` over `:inventory-detail` filtered by `:as-of-tx`/`:as-of-valid` (ADR-008 bitemporal contract). ATP also nets outstanding `:inv-reservation`s.
- `receive!` — appends `:valuation-layer` + `:inventory-item`/`:inventory-detail` in one tx (the atomic both-halves helper).
- `issue!` — appends `:layer-consumption` + negative `:inventory-detail`, consumes the `:inv-reservation`.
- `reserve!` — ports `reserveProductInventory`: walk `:pickloc`→`:bulk`→no-location, sorted by `reserve-order-enum`, append `atp-diff` details + `:inv-reservation` rows, back-order the remainder as negative ATP.
- `transfer!` / `complete-transfer!` — two-phase, paired `:inventory-detail`s.
- `count!` — `:physical-inventory` + `:inventory-variance` + reconciling `:inventory-detail`.

---

## 7. The two hardest design calls

1. **The valuation/physical join grain.** OFBiz's `InventoryItem` *is* the join — cost and quantity on one row. kontor must keep them separate yet reconcilable. The recommendation: join on shared `:lot` + facility, and provide atomic `receive!`/`issue!` helpers that write both halves in one transaction so they cannot drift. The maintainer must decide whether `:inventory-item` and `:valuation-layer` are 1:1 (simplest, but forces a valuation layer per bin) or many:1 (one valuation layer spanning several bins of the same lot — more realistic, but the reconciliation query is harder). **Recommend many-physical : one-valuation, joined by `:lot`.**

2. **Whether QOH/ATP are stored or derived.** Pure derivation (sum the `:inventory-detail` ledger every read) is the kontor-cultural answer and gives bitemporal QOH for free — but the reservation walk queries `availableToPromiseTotal > 0` across potentially thousands of items, and re-summing per item per reservation is O(details). OFBiz denormalizes precisely to make that query fast. **Recommend: derive by default, expose an optional denormalized `:inventory-item/atp-cache` attr maintained by the same middleware that appends details — explicit, auditable, and the ledger stays the source of truth.** This is the performance/purity tension the maintainer must rule on before the reservation walk is built.

# Research note 36 — `kontor-inventory` market-pain study

What real customers actually complain about in inventory / warehouse-management
modules — the pain that purely-feature-list planning misses. Research input for
the **operational inventory layer** (`kontor-inventory`): physical stock by
facility/location, available-to-promise, cycle counts, transfers, lot/serial
tracking, backing the `:inv-reservation` schema that `kontor-sales` already
ships with no availability engine behind it.

This is the **customer-pain angle only**. A parallel agent does the OSS
reference study (OFBiz / ERPNext / Sylius); a separate internal-gap agent
audits what the current kontor substrate already provides.

Design context already locked (do NOT relitigate):

1. The *financial* inventory layer exists — `:valuation-book` / `:valuation-layer` /
   `:layer-consumption` / `:layer-adjustment` (ADR-027/028), `CostingProvider`
   (ADR-029, FIFO/LIFO/AVG/Standard), `plan-stock-move` (ADR-030). `on-hand-qty` /
   `on-hand-value` are derived from the append-only fact log.
2. `kontor-sales` ships `:inv-reservation/*` (per-lot, with `:quantity-not-available`
   backorder count, `:reserve-order-enum`, promised-date fields) — but **no
   availability engine** decides whether a reservation can be honored.
3. The kernel has `:lot/*` (commodity, acquired-at, cost-basis, label) but
   the schema comment says "the `:lot` model is unused; lots only become
   interesting for stocks".
4. There is **no `:facility` / `:location` / `:bin` entity** — `:ship-group/facility-id`
   is a bare string, `:valuation-layer` has no location dimension.
5. kontor is a kernel. UI, scanner integration, replenishment planning, demand
   forecasting are consumer-app / out-of-scope concerns by ADR-010.

## TL;DR

1. **Negative inventory is the single most-cited, most-corrosive failure mode**
   across every system surveyed — NetSuite, QBO, Xero, ERPNext, Odoo all have
   open bugs or documented "negative-inventory pages" for it. It is not just a
   quantity problem: it **silently corrupts costing** because the issue has no
   layer to consume, so the system invents a cost (last-known, $0, or a
   deferred adjustment smeared across periods) — ERPNext #45414/#38014 ship
   negative stock *even with the guard disabled*. The kontor substrate
   **structurally cannot do this** the way the others do (a `:layer-consumption`
   needs a real layer), but `kontor-inventory` must make the *decision policy*
   explicit: refuse, or write a negative/backdated layer. This is the #1
   design call.
2. **Valuation drift — "my balance sheet inventory number is wrong" — is the
   #2 complaint and it is an *accountant's* complaint, P0 for our buyer.** The
   subledger (sum of layers) and the GL inventory account stop tying out.
   QBO users report $200k+ gaps; Odoo ships a whole `stock_valuation_discrepancy`
   reconciliation module; standard-cost shops fight the "standard-to-actual"
   capitalization every close. kontor's posting model (`plan-stock-move` is the
   *only* way value enters the GL, per-ledger sum-to-zero) means drift can be
   made **impossible by construction** — but only if `kontor-inventory` routes
   every operational event (count adjustment, transfer, scrap, revaluation)
   through `plan-stock-move` and never lets a raw journal entry touch the
   inventory account. That discipline is the #2 design call.
3. **ATP accuracy is the #3 complaint and the most directly in-scope gap.**
   `:inv-reservation` exists but nothing nets *on-hand − reservations − safety
   stock + scheduled-receipts + in-transit* into a single available-to-promise
   number. Overselling is the universal symptom. This is a genuine missing
   primitive: `kontor-inventory` must ship an `available-to-promise` view
   (bitemporal, like `on-hand-qty`) and the reservation-honoring decision.
4. **Multi-location is the #4 gap and it is a schema gap, not a UI gap.** No
   `:facility` / `:location` entity exists; `:valuation-layer` has no location
   dimension; transfers-in-transit have no home. "Where is my stock actually"
   is unanswerable today. `kontor-inventory` must add the location dimension —
   and decide whether the *valuation* layer is location-aware or whether
   location is a separate operational quantity ledger sitting beside it.
5. **The kontor substrate already neutralizes a surprisingly large slice.**
   Bitemporal queries (ADR-008) kill the "count vs perpetual divergence over
   time" and "what was on-hand at cutoff" problems that need Field-Audit-Trail
   upgrades elsewhere. Append-only layers + `:layer-adjustment` already give a
   non-destructive **inventory roll-forward** for free. Sealing (ADR-007) +
   period locking already solve "locking inventory transactions for a closed
   period". `:schedule` (ADR-032) already drives periodic standard-cost
   revaluation. The status machine (ADR-034) already gives count / transfer /
   RMA lifecycles. These are *content* gaps (seed the vocabularies, ship the
   helpers), not *architecture* gaps.
6. **The genuine new primitives `kontor-inventory` must design are exactly
   four:** (a) the `:facility`/`:location` dimension, (b) the
   `available-to-promise` view + reservation-honoring policy, (c) the
   negative-inventory **policy** decision (a per-item/per-facility flag plus
   the backdated-layer mechanic), (d) the **physical-count** entity (snapshot,
   freeze window, variance → `plan-stock-move` adjustment). Everything else is
   composition of existing kernel primitives.

---

## 1. Negative inventory — P0, very high frequency

**What it is.** Selling or shipping a SKU whose on-hand is at or below zero.
Happens constantly in real operations: the receipt hasn't been keyed yet, a
count is stale, an e-commerce channel oversold, manufacturing consumed before
the build was posted.

**Why it is the worst pain.** It is not the quantity that hurts — it is what it
does to **costing**:

- The outbound issue has no cost layer to draw from. The system must *invent* a
  cost. NetSuite shifts the costing of the sale to "another date" and estimates
  from the last-known cost while in stock; the adjustment then gets "spread
  across periods", skewing every period it touches
  ([HouseBlend — 10 tips to avoid NetSuite negative inventory](https://www.houseblend.io/blog/10-easy-tips-to-avoid-netsuite-negative-inventory),
  [Oracle docs — Reviewing Negative Inventory](https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2268458.html)).
- When the late receipt finally posts, COGS for the *already-shipped* unit is
  retroactively wrong, and you cannot cleanly tell which sale it belonged to.
- NetSuite's most common average-costing bug — "positive quantity, zero value" —
  traces directly to adjustments and receipts keyed at $0 cost on top of a
  negative balance ([AlphaBOLD — Fixing Inventory Costing Issues in NetSuite](https://www.alphabold.com/fixing-inventory-costing-issues-in-netsuite-with-quick-update-tool/)).
- The guard is widely **broken** even where it nominally exists: ERPNext ships
  sales invoices into negative stock with "Allow Negative Stock" *disabled*
  ([frappe/erpnext #45414](https://github.com/frappe/erpnext/issues/45414),
  [#38014](https://github.com/frappe/erpnext/issues/38014),
  [#41908](https://github.com/frappe/erpnext/issues/41908)). Odoo creates
  *duplicate* stock journal entries when a negative qty is later adjusted
  ([odoo/odoo #23210](https://github.com/odoo/odoo/issues/23210)).
- Xero went the other way and **forbids** it entirely — which makes Xero's
  tracked inventory unusable for any buy-on-demand or make-on-demand business
  ([Unleashed — Xero Inventory Management Guide](https://www.unleashedsoftware.com/app-marketplace/xero-inventory-management/xero-inventory-management-guide/)).
  So "just disallow it" is *also* a documented customer-loss path.

**Severity:** **P0** for both DE-GmbH and US-LLC mid-market. Costing corruption
flows straight into COGS, gross-margin reporting, and the balance sheet.

**Does kontor's substrate address it?** *Partially — and favorably.* The
append-only layer model means an outbound `:layer-consumption` must reference a
real `:valuation-layer`. There is no "negative quantity smeared across periods"
mechanic to accidentally invoke; the kernel can't silently invent a cost. **But
the substrate does not make the policy decision** — and the decision is genuine
business logic, not architecture. Two legitimate behaviors exist:

- *Refuse*: `kontor-inventory`'s issue helper rejects the move when ATP < 0.
  Clean, but loses the make-on-demand customer (the Xero failure mode).
- *Allow with a backdated/negative layer*: write an explicit
  `:valuation-layer` flagged as negative-origin (qty negative, or qty positive
  with a `:negative-fill?` marker), so the issue *does* have a layer, the
  costing is explicit (estimated unit cost recorded on the layer), and when the
  real receipt lands the reconciliation is a **visible `:layer-adjustment`**,
  not a hidden retroactive smear.

**Remediation hint — `kontor-inventory` needs a specific primitive.** A
per-`:item` × per-`:facility` `:negative-allowed?` policy flag, plus a
`negative-fill` layer mechanic that records the estimated cost and links the
later true-up adjustment back to the originating issue. This is **design call
#1** — surface it with AskUserQuestion. The kernel's append-only discipline is
the asset; the policy + the true-up linkage is the new code.

---

## 2. Valuation drift — subledger ≠ GL inventory account — P0, very high frequency

**What it is.** The sum of the inventory subledger (layers / item valuation
report) and the balance of the GL inventory asset account stop being equal.
The accountant's phrasing: *"my balance sheet inventory number is wrong."*

**Why it is so common.** Every system has a seam between the operational
inventory record and the financial GL, and every seam leaks:

- **QBO** pulls the balance-sheet inventory account from *anything posted to
  that account*, while inventory-valuation reports pull only from *items*. A
  bill, check, journal entry, or an adjustment offset to the inventory account
  instead of COGS shows on one and not the other. Users report gaps of
  **$200k+**, and one widely-cited thread calls QBO's COGS handling "a MASSIVE
  and unthinkable bug" that overstated P&L and the balance sheet
  ([Intuit Community — BS Inventory not matching Item Report](https://quickbooks.intuit.com/learn-support/en-us/reports-and-accounting/bs-inventory-value-for-a-specific-item-not-matching-value-in-the/00/1220608),
  [Intuit Community — QBO COGS Completely Wrong](https://quickbooks.intuit.com/learn-support/en-us/reports-and-accounting/qbo-cogs-accounting-for-inventory-completely-wrong-major-bug/00/1146388),
  [Firm of the Future — Common QuickBooks Inventory Mistakes](https://www.firmofthefuture.com/accounting/common-quickbooks-inventory-accounting-mistakes/)).
- **Odoo** ships an entire community module — `stock_valuation_account_manual_adjustment` —
  whose only job is to show "inventory stock value vs accounting value" and let
  you post an adjusting entry to force them back together
  ([Odoo Apps Store — stock_valuation_account_manual_adjustment](https://apps.odoo.com/apps/modules/9.0/stock_valuation_account_manual_adjustment)).
  The existence of that module *is* the evidence of the pain. Odoo 19 had to
  re-architect perpetual valuation to hit the stock account at invoice level
  with explicit closing entries to manage the gap
  ([Odoo 19 docs — Valuation cheat sheet](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/inventory_valuation/cheat_sheet.html)).
- **Standard-cost shops** carry permanent drift by design: standard cost is not
  GAAP-acceptable for external reporting, so the variance accounts (PPV, mfg
  variance) must be re-capitalized back into inventory each close — the
  "standard-to-actual adjustment" — allocated across raw / WIP / finished /
  COGS by where the varianced material ended up
  ([AccountingCoach — reclassifying PPV to actual cost](https://www.accountingcoach.com/blog/how-is-the-purchase-price-variance-reclassified),
  [SimpleManufacturing — PPV, Standard, Last and Actual Costs](https://www.simplemanufacturing.com/purchase-price-variation-standard-last-and-actual-costs-explained/)).
- **Standard-cost revaluation** itself: updating the standard immediately
  revalues on-hand inventory and dumps a P&L adjustment, frequently mid-close
  ([Dynamics 365 BC — Design Details: Revaluation](https://learn.microsoft.com/en-gb/dynamics365/business-central/design-details-revaluation),
  [Epicor User Forum — Standard Costing Annual Revaluation](https://www.epiusers.help/t/standard-costing-annual-revaluation/44519)).

**Severity:** **P0**. This is the headline complaint of the exact buyer kontor
targets — a controller at a DE-GmbH or US-LLC. An inventory number that won't
tie out is an audit finding.

**Does kontor's substrate address it?** *Yes — better than anything surveyed,
if the discipline holds.* The structural assets:

- `plan-stock-move` (ADR-030) is the *single* posting-builder for inventory GL
  entries, and per-(entity, ledger, commodity) sum-to-zero (ADR-021/031) means
  every value movement is balanced by construction.
- `on-hand-value` is *derived from the same layers* that `plan-stock-move`
  posts from — they read one fact log, so they cannot diverge the way QBO's
  two independent reports do.
- Standard-cost revaluation is a `:layer-adjustment` (ADR-028) + a
  `plan-stock-move` posting — append-only, bitemporally visible, fully
  audit-traceable. No silent revalue.

**Remediation hint — mostly substrate, plus one helper + one discipline rule.**
`kontor-inventory` must (a) provide a `valuation-tie-out` reconciliation view
that asserts `Σ on-hand-value (per entity/book) == GL inventory account
balance` and surfaces any delta with its cause, and (b) **forbid raw journal
entries to the inventory account** — every operational event (count adjustment,
transfer, scrap, revaluation, negative-fill true-up) must go through
`plan-stock-move`. The QBO disaster is *exactly* "someone posted a bill to the
inventory account directly." Make that structurally hard. This is **design call
#2**: is the inventory account flagged `:account/system-managed?` so only
`plan-stock-move` may touch it?

---

## 3. ATP accuracy — overselling — P1, high frequency

**What it is.** Available-to-promise is the uncommitted stock you can safely
promise: `on-hand − reservations − safety-stock + scheduled-receipts +
in-transit` (the exact netting set varies). When the components aren't netted
consistently, you oversell, or you artificially stock out.

**Why it breaks.** The components live in different places and update at
different times — "batch updates, delayed returns processing, and disconnected
allocation rules create discrepancies between what is shown online and what is
physically available"
([Deposco — Overselling inventory](https://deposco.com/blog/prevent-overselling/),
[BlueCherry — eCommerce integration prevents overselling](https://bluecherry.com/en/blog/how-ecommerce-integration-helps-reduce-overselling-and-stockouts)).
The standard mitigation in the field is a **soft-reservation** layer that holds
stock before it reaches the ERP, so concurrent omnichannel orders don't collide
([Microsoft Learn — Inventory Visibility Add-in](https://learn.microsoft.com/en-us/dynamics365/supply-chain/inventory/inventory-visibility)).
In-transit handling is its own sub-pain: JDE users complain the planner nags
them to "expedite the container bobbing on the ocean" because scheduled
receipts and lead-time fences aren't reconciled
([JDELIST — Safety Stock and Order/Expedite Messages](https://www.jdelist.com/community/threads/safety-stock-and-order-and-expedite-messages.60931/)).

**Severity:** **P1** for the core DE-GmbH/US-LLC bookkeeping buyer (overselling
is an operational embarrassment, not a books-correctness defect); **P0** for any
consumer app doing e-commerce fulfillment on top of kontor. Note research
note 13 already flagged "`cancel-order!` releases `:inv-reservation`" as a
market-validated P1 — stale reservations are themselves an ATP-corruption
source.

**Does kontor's substrate address it?** *Only the raw materials.*
`:inv-reservation` exists with `:quantity` and `:quantity-not-available`
(backorder). `on-hand-qty` exists. But **nothing nets them**, nothing models
safety stock, nothing models scheduled receipts (those would come from
`kontor-procurement` POs) or in-transit (those need the transfer entity from
§4). There is no `available-to-promise` function and no decision point that
*honors or rejects* a reservation against current ATP.

**Remediation hint — genuine new primitive.** `kontor-inventory` must ship:

- `available-to-promise` — a bitemporal view (same `:as-of-tx` / `:as-of-valid`
  contract as `on-hand-qty`) that nets on-hand − open-reservations −
  safety-stock + scheduled-receipts + in-transit, per (item, facility).
- A per-(item, facility) `:safety-stock` quantity attribute.
- The **reservation-honoring decision**: when `kontor-sales` creates an
  `:inv-reservation`, something must decide whether it's filled or goes to
  backorder. That logic belongs in `kontor-inventory` (it owns the availability
  picture), invoked by `kontor-sales`.
- The high-throughput soft-reservation broker (research note 13's "concurrent
  reservation broker", P0 at flash-sale rates) is a *separate* companion or an
  in-JVM layer — out of scope for `kontor-inventory` proper, but the
  `available-to-promise` API is the thing it would sit in front of. Document
  the seam.

This is **design call #3**: where does the reservation-honoring decision live,
and is ATP a pure derived view or a partially-materialized one for performance?

---

## 4. Multi-location — "where is my stock actually" — P1, high frequency

**What it is.** Stock split across warehouses, then sub-divided into
bins/zones, with units in-transit between locations. The basic question "how
much of SKU X is at facility Y" must be answerable, and transfers must not make
units vanish or double-count while on a truck.

**Why it breaks.** Xero simply has no multi-warehouse tracking at all
([Unleashed — Xero Inventory Management Guide](https://www.unleashedsoftware.com/app-marketplace/xero-inventory-management/xero-inventory-management-guide/)).
Odoo has open issues that inventory valuation **cannot be reported by location
or warehouse** ([odoo/odoo #25055](https://github.com/odoo/odoo/issues/25055)).
Cycle-counting guidance repeatedly cites "multiple locations per item" and
"location labels that don't match the system ID" as primary error sources
([Inventoryops — Cycle Counting and Physical Inventories](https://www.inventoryops.com/articles/cycle-counting-and-physical-inventories.html)).
In-transit stock is the classic period-close trap (see §8).

**Severity:** **P1** — and it is a **schema gap, not a UI gap**. A
single-warehouse DE-GmbH genuinely doesn't need it; the moment there's a second
location it's load-bearing.

**Does kontor's substrate address it?** *No — this is a real hole.* There is no
`:facility` / `:location` / `:bin` entity. `:ship-group/facility-id` is a bare
string. `:valuation-layer` has `book`, `item`, `lot`, but **no location
dimension**. `on-hand-qty` cannot answer per-location.

**Remediation hint — genuine new primitive, with a real design fork.**
`kontor-inventory` must add a `:facility` entity (and probably a nested
`:location`/`:bin` under it). The fork:

- *Option A — location on the valuation layer.* Add `:valuation-layer/location`.
  Costing and physical position are one fact. Simple, but couples financial
  valuation to operational geography (a bin-to-bin transfer within a warehouse
  shouldn't be an accounting event, yet now it touches the layer log).
- *Option B — separate operational quantity ledger.* Keep `:valuation-layer`
  location-agnostic (it stays the *costing* truth, often per legal entity), and
  add a parallel append-only `:stock-move` quantity ledger keyed by (item, lot,
  facility, location) for the *operational* truth. Transfers are pure quantity
  moves; only cross-entity / cross-book moves touch valuation.

Option B mirrors how kontor already separates `:ledger` (financial) from
`:valuation-book` (costing) — two parallel append-only logs for two questions.
It is the more kontor-idiomatic answer. This is **design call #4** and it has
the widest blast radius — it determines what a "transfer" *is*. In-transit is
then naturally modeled as a virtual `:location` (or `:facility`) that a transfer
moves *through*: ship debits source, credits in-transit; receive debits
in-transit, credits destination. Sum-to-zero on quantity. Nothing is lost on
the truck.

---

## 5. Lot / serial / expiry tracking, FEFO, recall — P1 (P0 in regulated verticals)

**What it is.** Per-unit or per-batch traceability: lot numbers, serial numbers,
expiry dates; **FEFO** (first-expiry-first-out) consumption ordering; the
ability to answer "which customers got units from lot L" for a recall; the
audit demand for a complete genealogy.

**Why it breaks.** Lot/serial is where reconciliation math goes wrong:
ERPNext's stock-reconciliation `difference_amount` is computed *incorrectly* for
serialized items via the Serial-and-Batch bundle
([frappe/erpnext #40168](https://github.com/frappe/erpnext/issues/40168)),
batched items won't accept a qty change in stock reconciliation
([#41698](https://github.com/frappe/erpnext/issues/41698)), and submit/cancel
of reconciliations with batch+serial throws errors
([#39777](https://github.com/frappe/erpnext/issues/39777)). Xero's batch/expiry
story is so thin that third parties write guides on bolting it on
([Katana — Optimizing Xero tracked inventory for batch and expiry](https://katanamrp.com/blog/xero-inventory-expiration-date-tracking/)).
Fishbowl markets lot/serial/expiry on *all* plans precisely because regulated
buyers (food, pharma, medical) treat its absence as disqualifying
([LilyPad — Cin7 vs Katana vs Fishbowl](https://lilypadapplications.com/cin7-vs-katana-vs-fishbowl-which-inventory-software-should-you-choose/)).

**Severity:** **P1** generally for DE-GmbH/US-LLC; **P0** if the customer is in
food / pharma / medical-device / chemicals (recall is a legal obligation, not a
nicety).

**Does kontor's substrate address it?** *Lots: largely yes. Serial + expiry +
FEFO: no.*

- `:lot` exists in the kernel (`:lot/commodity`, `:lot/acquired-at`,
  `:lot/cost-basis`, `:lot/label`) and `:valuation-layer/lot` already supports
  **lot-isolated FIFO** — `available-layers` takes a `lot` arg.
  `:layer-consumption` already records which lot a unit was drawn from, so the
  recall genealogy ("which issues consumed lot L") is **a datalog query over
  existing facts** — append-only + bitemporal makes this strong, no separate
  audit trail needed. This is a real substrate win.
- **Serial** numbers (qty-1 lots) fit the `:lot` model conceptually but the
  ergonomics of one-lot-per-unit at scale need a design pass.
- **Expiry** has no home — `:lot` has no `:expires-at`, and there is no FEFO
  cost method (`CostingProvider` ships FIFO/LIFO/AVG/Standard, not FEFO).
- The ERPNext-class bug — reconciliation difference-amount wrong for
  serial/batch items — is *structurally avoided* here, because a count
  adjustment is a `:layer-adjustment` + `plan-stock-move` posting, and
  sum-to-zero validates the money. The kernel can't ship a "wrong difference
  amount".

**Remediation hint — small schema additions + one CostingProvider impl.** Add
`:lot/expires-at` (and probably `:lot/status` for quarantine/blocked). Ship a
**FEFO `CostingProvider`** — it's the same `available-layers` query ordered by
`expires-at` instead of `received-at`, genuinely small given ADR-029's
pluggable design. Document the serial-as-qty-1-lot pattern and bench it. The
recall query ships as a helper, not a new entity.

---

## 6. Cycle counts / physical inventory — P1, high frequency

**What it is.** Periodic physical counts (full or cycle), the variance posting
when count ≠ perpetual, **freezing stock movement** during the count window,
and managing count-vs-perpetual divergence over time.

**Why it breaks.** The dominant theme in every cycle-count guide is **the
freeze and the cutoff**: counting during active picks/receipts, paperwork lag,
unapproved adjustments, missing reason codes, variances never recounted
([Effective Inventory — Achieving Cycle Counting Success](https://effectiveinventory.com/achieving-cycle-counting-success/),
[Inventoryops — Cycle Counting and Physical Inventories](https://www.inventoryops.com/articles/cycle-counting-and-physical-inventories.html),
[NetSuite — Inventory Cycle Counting 101](https://www.netsuite.com/portal/resource/articles/inventory-management/using-inventory-control-software-for-cycle-counting.shtml)).
Best practice everyone names: establish a transaction-freeze window, blind
counting, post adjustments **with reason codes** (damage / shrink / UOM /
mispick / found stock), recount every out-of-tolerance variance before posting.

**Severity:** **P1**. Mid-market does cycle counts; the variance posting is a
real GL event auditors look at.

**Does kontor's substrate address it?** *The mechanics yes, the entity no.*

- The variance posting is exactly a `:layer-adjustment` + `plan-stock-move` —
  append-only, balanced, reason-coded (`:layer-adjustment/reason` already
  exists). No "wrong difference amount" class of bug.
- **Bitemporal queries handle the freeze elegantly**: a count is *as-of* a
  valid-time instant. You don't need to physically lock the database — you
  count against `(on-hand-qty db item {:as-of-valid count-instant})` and post
  the variance with that valid-time. Concurrent picks at a *later* valid-time
  don't corrupt the count. This is a structural advantage over systems that
  must literally freeze the warehouse.
- The status machine (ADR-034) gives the count lifecycle (`:open → :counting →
  :review → :posted`) for free.
- **Missing**: a `:physical-count` / `:count-line` entity to hold the snapshot
  (expected vs counted vs variance per item/location), tie the lines to the
  resulting adjustment transaction, and record recount history. Without it the
  count is ad-hoc.

**Remediation hint — one new entity, composing existing primitives.**
`kontor-inventory` ships `:physical-count` (header: facility, scope,
count-as-of-valid-time, status) + `:count-line` (item, location, lot,
expected-qty, counted-qty, `:recount-of` self-ref, `:variance-reason`). Posting
a count walks the lines and emits `plan-stock-move` adjustments. The freeze is a
valid-time convention, not a lock. Reason codes are a seeded vocabulary.

---

## 7. Costing on the awkward edges — returns/RMA, scrap, kitting, drop-ship, consignment — P1/P2, medium frequency

**What it is.** The transactions that don't fit the clean receive→issue model:

- **Returns / RMA** — a customer return puts a unit *back* into stock; at what
  cost? Odoo's own forum has unresolved threads on returns under FIFO+automated
  valuation producing journal entries that **don't match the credit note**,
  requiring manual adjustment
  ([Odoo forum — Inventory Valuation and Credit Note on returns](https://www.odoo.com/forum/help-1/how-to-manage-inventory-valuation-and-credit-note-journal-entries-when-doing-a-return-207889)).
- **Scrap / write-off** — a quantity leaves stock with no sale; the value goes
  to a loss account, not COGS.
- **Kitting / assembly** — N component layers consumed, 1 kit layer created;
  the kit's cost is the rolled-up component cost; disassembly reverses it.
- **Drop-ship** — goods never physically touch your warehouse; the question is
  whether they hit inventory at all (mostly no — direct COGS), and PwC/Finale
  guidance says drop-ship quality risk often needs an *inventory reserve*
  anyway ([Finale — Accounting for Drop Ship Inventory](https://www.finaleinventory.com/accounting-and-inventory-software/accounting-for-drop-ship-inventory)).
- **Consignment** — stock you hold but don't own (or own but don't hold).
  Costing/accounting occurs **at consumption**, valued at the price in effect
  *then*, and consigned goods are explicitly *not* accrued until consumed
  ([Oracle — Accounting and Costing for Consigned Inventory](https://docs.oracle.com/cd/E18727_01/doc.121/e13470/T260819T260825.htm),
  [Finale — Consignment Inventory Accounting](https://www.finaleinventory.com/guides/consignment-inventory-accounting/)).
  "Tracking inventory you don't technically own" is the named pain.

**Severity:** **P1** for returns + scrap (every business does these); **P2** for
kitting / drop-ship / consignment (vertical-dependent — a DE-GmbH distributor
may never kit).

**Does kontor's substrate address it?** *Returns / scrap / kitting: yes, as
composition. Consignment ownership: needs a flag.*

- A **return** is a `plan-stock-move` receipt creating a new layer; the open
  design question (which Odoo botches) is *what unit cost* — original layer
  cost (requires linking the return to the original issue/`:layer-consumption`)
  vs. current cost vs. credit-note value. The kernel *can* express any of
  these; `kontor-inventory` must pick a default and make the link recordable.
- **Scrap** is `plan-stock-move` with the credit leg routed to a loss account
  instead of COGS — `plan-stock-move` already takes account resolution as a
  parameter (ADR-030). Pure composition.
- **Kitting** is N `:layer-consumption` + 1 new `:valuation-layer` whose
  `unit-cost-original` is the rolled-up sum — composition, but
  `kontor-inventory` should ship a `build-kit!` / `unbuild-kit!` helper so
  consumers don't hand-roll the roll-up.
- **Drop-ship** mostly bypasses inventory — that's a `kontor-procurement` /
  `kontor-sales` posting concern (direct COGS, no layer). Correctly *not*
  `kontor-inventory`'s job, beyond making sure the reservation engine knows a
  drop-ship line doesn't consume ATP.
- **Consignment** needs an **ownership flag** on the layer or facility
  (`:valuation-layer/owned?` or a consignment `:facility` type) so consigned
  stock is excluded from the balance-sheet `on-hand-value` tie-out but still
  visible to `available-to-promise`. That's a genuine small schema addition.

**Remediation hint.** Ship `build-kit!` / `unbuild-kit!` / `scrap!` / `return!`
helpers (thin composition over `plan-stock-move`). Add a layer/facility
**ownership flag** for consignment. Document drop-ship as a non-inventory path.
Pick and document the return-costing default.

---

## 8. Period close — in-transit at cutoff, accruals, locking — P1, high frequency

**What it is.** At period boundary: stock physically in-transit (shipped not
received, or received not invoiced) must land in the right period; the
cutoff/accrual entries must be made; and inventory transactions for a closed
period must be locked against further posting.

**Why it breaks.** In-transit at cutoff is the classic understatement/
overstatement trap — guidance is uniformly "process all pending receipts,
shipments, transfers, adjustments before cutoff"
([Inventoryops — Cycle Counting and Physical Inventories](https://www.inventoryops.com/articles/cycle-counting-and-physical-inventories.html),
[Sensiba — Best Practices for Year-End Inventory Counts](https://sensiba.com/resources/insights/how-to-prepare-for-year-end-physical-inventory-counts/)).
The GR/IR (goods-received / invoice-received) clearing account is the standard
accrual home for "received but not invoiced".

**Severity:** **P1**.

**Does kontor's substrate address it?** *Yes — strongly. This is mostly already
solved.*

- **Period locking**: the kernel already has period open/close (ADR-014) +
  sealing (ADR-007). An inventory transaction in a closed period is rejected by
  the *same* middleware that rejects any posting — `kontor-inventory` inherits
  it for free, no new code.
- **GR/IR clearing** already exists — `plan-stock-move`'s account vocabulary
  includes `:gr-ir-clearing` (seen in `posting.clj`). "Received not invoiced"
  is already an explicit account.
- **In-transit at cutoff** falls out of the §4 design *if* in-transit is a
  virtual location: the in-transit `:location` balance *is* the cutoff
  exposure, queryable bitemporally as-of the cutoff valid-time. No special
  accrual logic — it's just a location balance.
- **Bitemporal cutoff queries**: "what was on-hand-value at the period-close
  instant" is `on-hand-value` with `:as-of-valid` set to the cutoff. Elsewhere
  this needs a Field-Audit-Trail-class upgrade; here it's a parameter.

**Remediation hint — substrate already covers it; needs §4's in-transit
location + documentation.** No new primitive. `kontor-inventory` should ship a
period-close *checklist helper* ("list unposted receipts/shipments/transfers
before cutoff") — convenience, not architecture.

---

## 9. Reconciliation & audit — proving the perpetual record, the roll-forward — P1, medium frequency

**What it is.** Proving the perpetual inventory record to an auditor: the
**inventory roll-forward** (opening + receipts − issues ± adjustments = closing,
tying to the GL), reason-coded adjustments, the count history.

**Why it breaks.** Xero is explicitly called out as limited "for businesses
requiring detailed audit trails or adjustment reason codes"
([Unleashed — Xero Inventory Management Guide](https://www.unleashedsoftware.com/app-marketplace/xero-inventory-management/xero-inventory-management-guide/)).
Cycle-count guides repeatedly cite **missing reason codes** and **unapproved
adjustments** as audit-failure causes. NetSuite's audit story for inventory
history is a paid Field-Audit-Trail tier.

**Severity:** **P1**. An auditor who can't trace the inventory number is an
audit finding; this is core to the DE-GmbH/US-LLC buyer.

**Does kontor's substrate address it?** *Yes — this is one of the strongest
substrate wins, echoing research note 13's "bitemporal by construction" point.*

- The **roll-forward is the append-only layer log itself.** Opening = layers
  remaining as-of period start; receipts = layers created in-period; issues =
  `:layer-consumption` in-period; adjustments = `:layer-adjustment` in-period;
  closing = layers remaining as-of period end. It's one set of datalog queries
  over facts that already exist. No separate roll-forward table to drift.
- **Every adjustment is reason-coded** (`:layer-adjustment/reason`) and
  **append-only** — you cannot silently un-adjust. The Xero "no reason codes /
  no audit trail" complaint is structurally impossible here.
- **Bitemporal** means "show me the inventory record exactly as it stood when
  the auditor's sample date was T, including what we knew then" — `:as-of-tx` +
  `:as-of-valid`. NetSuite Field-Audit-Trail equivalent, no upgrade tier.
- Sealing (ADR-007) means a posted inventory transaction can't be silently
  retracted; `:db/purge` is itself a recorded commit.

**Remediation hint — substrate covers it; ship the report helper.**
`kontor-inventory` ships an `inventory-roll-forward` view (per item / facility /
book, per period) as a thin datalog helper. No new primitive needed — this is a
*reporting* helper over existing facts. It is a positioning differentiator
worth naming in any RFP.

---

## 10. Operational friction — bulk adjustments, scanners, drill-down, scale — P2 (mostly correctly out-of-scope)

**What it is.** Bulk adjustment workflows, barcode/scanner integration
expectations, reporting drill-down, performance at volume.

**Why it shows up.** Fishbowl reviews cite "exceptionally clunky UX",
"nightmarish implementation", "limited reporting"; Cin7 cites slow loads and
post-acquisition support decline (Trustpilot 2.6/5); Xero slows past ~1,000
invoices/month and caps at 4,000 tracked items
([G2 — Fishbowl Inventory Reviews](https://www.g2.com/products/fishbowl-inventory/reviews),
[Qoblex — Cin7 vs Fishbowl](https://qoblex.com/blog/cin7-vs-fishbowl-which-inventory-management-software-is-right-for-your-business-in-2026/),
[Finale — Xero Inventory Management](https://www.finaleinventory.com/accounting-and-inventory-software/xero-inventory-management)).

**Severity:** **P2** for the kernel — most of this is **correctly a
consumer-app / UI concern** by ADR-010. Scanner integration, bulk-adjustment
UX, drill-down screens: not the kernel's job. The honest exception:

- **Performance at scale IS the kernel's concern.** Research note 13 already
  flagged the `:inv-reservation` datom-volume bench (50k orders/day → 3M
  reservation datoms/day) and the bulk-transition API. `kontor-inventory` adds
  more: `available-to-promise` and `on-hand-qty` are *derived* views walking
  the layer + consumption log — at high SKU × layer × location counts these
  must be benched, and a partially-materialized snapshot (an `:as-of` balance
  cache, invalidated append-only) may be needed. This is the one P1 hiding in
  the P2 cluster.

**Remediation hint.** Keep scanner / bulk-UX / drill-down out of the kernel
(consumer-app concern — say so explicitly, like research note 13 did for the
BPMN editor). **Do** bench `available-to-promise` / `on-hand-qty` at
representative volume; if linear-scan cost is unacceptable, design a
materialized as-of snapshot as its own ADR.

---

## Severity-ranked summary

| # | Pain cluster | Severity (DE-GmbH / US-LLC) | Frequency | Substrate verdict |
|---|---|---|---|---|
| 1 | Negative inventory + costing corruption | **P0** | Very high | Substrate prevents silent corruption; **policy + negative-fill mechanic is a new design call** |
| 2 | Valuation drift — subledger ≠ GL | **P0** | Very high | Substrate makes drift near-impossible **if** all events route through `plan-stock-move`; needs tie-out helper + system-managed-account discipline |
| 3 | ATP accuracy / overselling | P1 (P0 for e-com consumers) | High | **Genuine missing primitive** — `available-to-promise` view + reservation-honoring decision + safety-stock |
| 4 | Multi-location / transfers / in-transit | P1 | High | **Genuine schema gap** — no `:facility`/`:location` entity; widest-blast-radius design call |
| 5 | Lot / serial / expiry / FEFO / recall | P1 (P0 in food/pharma) | Medium-high | Lots + recall genealogy already strong; needs `:lot/expires-at` + FEFO `CostingProvider` |
| 6 | Cycle counts / physical inventory | P1 | High | Mechanics + freeze (bitemporal) + lifecycle already there; needs `:physical-count` entity |
| 7 | Returns/RMA, scrap, kitting, drop-ship, consignment | P1 / P2 | Medium | Mostly composition over `plan-stock-move`; needs ownership flag + helpers + return-cost default |
| 8 | Period close — in-transit, accruals, locking | P1 | High | **Substrate already covers it** — period lock, GR/IR, bitemporal cutoff; needs §4 in-transit location |
| 9 | Reconciliation & audit — roll-forward | P1 | Medium | **Substrate's strongest win** — append-only log *is* the roll-forward; ship report helper only |
| 10 | Operational friction — scanners, bulk, drill-down, scale | P2 | High | UX correctly out-of-scope; **scale of derived views is the one real P1 here** |

### What the existing kontor substrate already neutralizes

- **#9 reconciliation / roll-forward** — append-only layer + consumption +
  adjustment log *is* the roll-forward; reason codes and immutability are
  structural. The Xero "no audit trail / no reason codes" complaint can't occur.
- **#8 period close** — period locking (ADR-014) + sealing (ADR-007) +
  `:gr-ir-clearing` account + bitemporal cutoff queries already exist; inherited
  for free.
- **#1 silent costing corruption** — the append-only layer model means an issue
  *must* reference a real layer; no "negative qty smeared across periods"
  mechanic exists to invoke.
- **#2 the divergence mechanism** — `on-hand-value` and `plan-stock-move` read
  the *same* fact log, so QBO-style "two reports that disagree" is structurally
  absent (the discipline rule still has to be enforced).
- **#5 recall genealogy** — `:layer-consumption` already records lot per issue;
  "who got lot L" is a datalog query, bitemporally sound.
- **#6 the count freeze** — bitemporal valid-time makes a count an *as-of* query,
  not a database lock.
- Cross-cutting: status machine (ADR-034) gives count/transfer/RMA lifecycles;
  `:schedule` (ADR-032) drives periodic standard-cost revaluation; `:layer-adjustment`
  (ADR-028) is the universal variance/revaluation/true-up primitive.

### The 3-4 that `kontor-inventory`'s design must explicitly solve

1. **The `:facility` / `:location` dimension (#4)** — and the fork: location on
   the valuation layer vs. a separate operational `:stock-move` quantity ledger
   beside the costing layer. Recommendation: the separate-ledger option, mirroring
   how kontor already separates `:ledger` from `:valuation-book`. This decision
   defines what a "transfer" is and unlocks in-transit (#8) for free.
2. **`available-to-promise` + the reservation-honoring decision (#3)** — a
   bitemporal netting view (on-hand − reservations − safety-stock +
   scheduled-receipts + in-transit) plus the logic that decides whether an
   `:inv-reservation` is filled or backordered, plus a `:safety-stock` attribute.
3. **The negative-inventory policy (#1)** — a per-(item, facility)
   `:negative-allowed?` flag and a `negative-fill` layer mechanic that records an
   explicit estimated cost and links the later true-up `:layer-adjustment` back to
   the originating issue. Neither "always refuse" (loses make-on-demand) nor
   "silently allow" (corrupts costing) is acceptable; the explicit-layer middle
   path is the design.
4. **The `:physical-count` entity (#6)** — header + `:count-line` (expected /
   counted / variance / recount-of / reason), posting walks lines into
   `plan-stock-move` adjustments, freeze is a valid-time convention. Plus the
   smaller adds that ride along: `:lot/expires-at` + a FEFO `CostingProvider`
   (#5), a consignment ownership flag (#7), and a `valuation-tie-out` +
   `inventory-roll-forward` reporting helper (#2, #9).

Items 1-3 are AskUserQuestion-worthy design calls before any code. Item 4 and
the riders are composition of existing primitives — implement, don't deliberate.

## Acknowledged limitations

- **No live customer feedback** — same caveat as research note 13. Pain
  prioritization reflects what *existing systems'* users complain about
  (correlated with, not identical to, kontor's eventual users).
- **Web-review sampling is finite** — this note draws on ~25 vendor docs, OSS
  issue trackers (ERPNext, Odoo), accountant forums (Intuit Community, Epicor/JDE
  user forums), and comparison/review aggregators (G2, Trustpilot summaries). It
  is a representative-but-not-exhaustive slice.
- **No performance numbers** — every "bench this" in §3 and §10 is qualitative.
  Real numbers come from running `available-to-promise` / `on-hand-qty` on
  representative SKU × layer × location volumes.
- **OSS reference study + internal gap analysis are separate agents** — this
  note deliberately covers only the customer-pain angle; synthesize all three
  before drafting the `kontor-inventory` ADRs.

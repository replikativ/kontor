---
date: 2026-05-15
agent: general-purpose
status: research-note
topic: How should kontor be rendered in simmis? Bridge to the categorical S category, bespoke components, or hybrid?
related:
  - simmis/src/is/simm/model/schema.clj
  - simmis/src/is/simm/model/functor.clj
  - simmis/src/is/simm/model/crud.clj
  - simmis/src/is/simm/model/room_databases.clj
  - simmis/src/is/simm/optimistic.cljc
  - dvergr/src/dvergr/chat/accounting.clj
  - kontor/doc/decisions.md (ADR-002, ADR-007, ADR-008, ADR-010, ADR-048)
  - kontor/src/kontor/schema.clj
---

# 54 — Rendering kontor in simmis: S-bridge vs. bespoke vs. hybrid

## 1. What simmis actually does

Simmis is **not** a generic UI shell. It is a categorical knowledge base / chat / sandbox that
makes its *own metaschema* an in-database citizen, then renders instances by functor lookup.
Concretely (`simmis/src/is/simm/model/schema.clj` L25-L273):

- Every entity is a **block**. Blocks have `:entity/uuid`, `:entity/name`, `:block/parent`,
  `:block/order` (fractional index), `:block/content` (HTML), `:block/references`, and
  `:instance/of-role` pointing to one or more *type entities*.
- A **type entity** is an `:object/*` block with `:object/of-category` pointing at category
  `S`. The category itself is a block. Types can be primitive (`:object/primitive?`) or
  composite.
- A **property** is a `:morphism/*` block: `:morphism/src` (domain type) →
  `:morphism/dst` (codomain type) with `:morphism/property-type` ∈ `{:text :number :date
  :checkbox :select :multi-select :url :email :phone :relation :rollup :formula}` and a
  cardinality hint.
- A **functor** `S → Comp` (`:functor/on-objects`, `:functor/on-morphisms`) maps each
  type/property to a UI component name + EDN config; `:fmap/component-name` is a *string*
  resolved at render time. `is.simm.model.functor/resolve-ui-functor` walks the precedence
  chain (instance > object > system) and `get-component-for-object` / `…-property-type`
  return the component name plus optional `:context` (`:standalone`, `:table-row`,
  `:property-input`).
- CRUD (`simmis/src/is/simm/model/crud.clj`) creates blocks with `:instance/of-role`
  references; dynamic schema installation registers new properties as datahike attrs at
  runtime.
- Per-room data lives in **separate datahike databases** keyed by `db-scope`
  (`simmis/src/is/simm/model/room_databases.clj`). The system DB holds the party / room
  index; each room DB holds blocks. `acct/install-accounting-schema!` already installs a
  tiny `dvergr.chat.accounting` schema for **LLM token accounting** (microdollars), *not*
  business accounting. That's the existing stub that explicitly is NOT kontor.
- Frontend is UIx + Spindel (reactive). `is.simm.optimistic` provides optimistic
  transactions over a synced datahike, so the client view is bitemporal-naive: it shows
  the latest known consistent state plus pending optimistic deltas.

The functor mechanism is well-suited to **uniform** record-shaped data: a row of cells, a
card with labelled fields, a table view. It is not suited to anything with cross-row
semantics (running balances, dr/cr column layout, hierarchical account tree, period
boundaries).

## 2. Option comparison

### Option A — Bridge every kontor entity to the S category

**Mechanics.** Ship `kontor.simmis-bridge` (in a new optional namespace, *or* in a
companion `kontor-simmis` artifact) that emits `:object/*` and `:morphism/*` instances
for `:account`, `:journal`, `:transaction`, `:posting`, `:commodity`, `:lot`,
`:tax-code`, `:partner`, `:period`, `:fiscal-position`, etc. Per-attr morphisms get a
`:morphism/property-type` mapped from the kontor datahike type
(`:db.type/bigdec → :number`, `:db.type/ref → :relation`, `:db.type/instant → :date`).
On the simmis side, no code changes — the functor mechanism renders cards/tables for
free.

**Schema cohabitation.** Works **with** ADR-002. The kontor attributes are already
present in the room DB; the bridge only adds a *parallel* description of them as
`:object/*`/`:morphism/*` blocks. The bridge does not duplicate data — it duplicates
schema metadata.

**Composability.** A chat message can `[[Transaction-uuid]]` a kontor transaction and
simmis's reference machinery (`is.simm.model.references`) resolves it as a block. A
knowledge-base page can pin a transaction card. Agents (parties of `:party/type :agent`)
can read/write kontor instances using the same CRUD as any other entity, gated by
whatever authorization layer wraps the room DB.

**Bitemporal.** This is the **load-bearing weakness**. Simmis blocks are present-tense;
`d/pull`/`d/q` against `@conn` returns the current snapshot. Kontor's invariant (ADR-008,
ADR-048) is that every query takes `:as-of-tx`/`:as-of-valid`. The bridge's generic
cards would render the present, which is wrong for restated transactions. Mitigation: ship
a `kontor-aware` config on the functor that picks up an `:as-of` signal from the surrounding
UI; the rendered component then queries via `kontor.bitemporal` instead of plain `d/pull`.
That's an extra hop the simmis substrate currently has no place for.

**Agent simulation.** Datahike has `:keep-history? true` (which kontor already requires)
but **no branch primitive**. The "agent fork" pattern as outlined in
`SPINDEL_INTEGRATION.md` / `optimistic.cljc` is *overlay-based*: the optimistic store wraps
the real conn with pending tx-data. For an agent to fork the books, the cleanest path is
a new room DB scope cloned at a chosen tx-id (`kontor.dsar/clone-as-of` style), with reads
served from the clone. The bridge does not constrain this — it inherits whatever fork story
simmis picks.

**Schema evolution.** Kontor's schema stays hardcoded EDN. The bridge *publishes* it into
S at install time (an idempotent transact). When kontor adds an attribute, the bridge
emits a new morphism. Simmis cannot mutate kontor's schema (correctly — that would break
the kernel invariants); it can extend with new morphisms that point at kontor objects.

**Cost.** ~300 lines in kontor for the bridge transformer + a per-attr property-type
mapping table. Zero lines in simmis. Big win: a developer who installs `kontor` into a
simmis room DB sees accounts, transactions and postings appear as browsable typed
entities *automatically*, with the same card/table UX as everything else.

### Option B — Bespoke components only

**Mechanics.** Add `simmis-kontor` namespace under `simmis/src/is/simm/uis/web/desktop/
views/accounting/` with `trial_balance.cljc`, `account_register.cljc`,
`transaction_form.cljc`, `balance_sheet.cljc`, `ledger.cljc`. Each component reads
directly from the room conn via `kontor.trial`, `kontor.balance`, `kontor.ledger`, etc.
Register them in the route table / nav, like the existing `kb_settings` / `room_settings`
views.

**Schema cohabitation.** Trivially compatible — the components query kontor attrs
directly. Nothing in S.

**Composability.** **Worst of the three.** A chat message cannot reference a transaction
unless the component manually registers a reference resolver. A knowledge-base block
cannot embed a trial-balance snapshot unless the editor learns a special embed type.
Drag-and-drop from accounting into a page becomes a custom code path.

**Bitemporal.** Easy and correct — each bespoke component owns its as-of axis, can
present a time-slider, and queries via `kontor.bitemporal`. This is the area where B
wins.

**Agent simulation.** Same as A — independent of the rendering choice; depends on the
room-DB-fork primitive.

**Schema evolution.** Each new kontor attribute requires a code change in simmis if the
attribute should be displayed. Bad for the runtime-evolution selling point.

**Cost.** ~2,500-5,000 lines of bespoke ClojureScript in simmis, growing with every new
report. Maintenance burden lives in simmis, not kontor.

### Option C — Hybrid (bridge + bespoke for cross-row views)

**Mechanics.** Ship the bridge (Option A) for "what is this entity" rendering — cards in
chats, table rows in a generic browser, references in pages. **Add bespoke components
only for the views where the functor mechanism would produce bad UX**: trial balance,
balance sheet, ledger drill-down with running balance, journal posting form (because it
has the sum-to-zero constraint that no generic form can enforce). The bespoke components
register themselves as system functors for synthetic "report" objects
(`S/kontor.TrialBalance`, `S/kontor.BalanceSheet`, `S/kontor.AccountRegister`) — so even
the report views are addressable via the categorical schema; only the *implementation*
is bespoke.

**Schema cohabitation.** Same as A. Reports are stored as parameterized blocks
(`{:report/as-of-tx ... :report/period ...}`) which other systems can reference.

**Composability.** **Best of the three.** A chat message embeds a trial-balance block by
UUID; the block's functor renders the bespoke component; the block participates in the
reference graph; agents can read/write the report parameters as ordinary property values.

**Bitemporal.** The hybrid model gives a clean answer: generic entity rendering is
present-tense (acceptable for an account list or a partner contact card); bespoke report
components own their own `:as-of-tx`/`:as-of-valid` parameters as block properties.
Simmis does **not** need a global time slider; bitemporality is per-report-block.

**Agent simulation.** Same as A/B.

**Schema evolution.** New kontor attrs get free generic rendering. New report types need
new components but each is small and self-contained.

**Cost.** ~300 lines in kontor (bridge), ~1,500 lines in simmis (5-6 bespoke report
components), plus a stable contract between them: the report-block protocol.

## 3. Recommendation + next steps

**Pick Option C (hybrid).** A and B each surrender something kontor cannot afford to lose:
A loses bitemporal correctness on reports; B loses simmis's compositional substrate
(chat references, drag-and-drop, agent uniformity). C keeps both at a modest extra cost
(one new namespace per side and a small report-block contract).

### Honest uncertainty

The single load-bearing open question is **where the kontor books physically live**. Two
plausible models:

1. **Books-in-room.** Each simmis room is a tenant. The room's datahike DB contains kontor
   attrs alongside simmis's blocks. Agents fork by cloning the room DB at a chosen tx-id.
   Maps cleanly onto `room_databases.clj`. Implies one set of books per chat room (good
   for "consultancy with one workspace per client", less good for "one company with many
   rooms").
2. **Books-in-tenant.** A tenant has *one* kontor DB and *many* simmis rooms; the rooms
   reference but do not contain the books. Requires a second DB connection per agent
   write and breaks the single-tx atomicity that ADR-002 was designed to preserve.

Model 1 is what the current `room-databases.clj` + `dvergr.chat.accounting` stub
suggests. I'd commit to it unless `simmis` evolves a tenant-level DB scope. The
recommendation below assumes model 1.

### First three commits in `kontor`

1. **`src/kontor/simmis_bridge.clj`** — pure functions `kontor->s-objects` and
   `kontor->s-morphisms` that, given the kernel attribute list, produce
   `:object/*`/`:morphism/*` tx-data. Plus `install-bridge!` (idempotent transact). Tests
   in `test/kontor/simmis_bridge_test.clj`: assert that every namespaced attr in
   `kontor.schema` is emitted, that the property-type mapping is exhaustive, that
   re-running is a no-op.
2. **`src/kontor/report_block.clj`** — define the report-block contract: a block with
   `:instance/of-role` ∈ `{S/kontor.TrialBalance, S/kontor.BalanceSheet,
   S/kontor.AccountRegister}` and properties `:report/as-of-tx`, `:report/as-of-valid`,
   `:report/account-filter`, `:report/period`. Define `materialize-report` as a pure
   function `(db, block) → report-data`. No UI here — kontor stays UI-free per ADR-010.
   The bespoke components in simmis call this.
3. **ADR-069** documenting the hybrid choice, the bridge contract, the report-block
   shape, and the books-in-room assumption. Link it from `simmis_bridge.clj` and
   `report_block.clj`.

### First three commits in `simmis`

1. **`src/is/simm/integrations/kontor.clj`** — server-side install hook:
   `(kontor.simmis-bridge/install-bridge! room-conn)` plus
   `(kontor.schema/install! room-conn)` invoked from `create-room-database!` when the
   room is marked accounting-enabled. Add a `:room/features` flag on the system DB.
2. **`src/is/simm/uis/web/desktop/views/accounting/`** — three bespoke components:
   `trial_balance.cljc` (table with debit/credit/balance columns, time-slider bound to
   the block's `:report/as-of-tx`), `account_register.cljc` (ledger drill-down with
   running balance), `transaction_form.cljc` (multi-line entry with live sum-to-zero
   indicator). Register each as a system functor under `S/kontor.*`.
3. **`src/is/simm/uis/web/desktop/views/types.cljc`** extension — make `type-tag` render
   kontor objects (account, journal, transaction) with their `:account/code` /
   `:journal/code` / `:transaction/ref` as the display label instead of the UUID. This is
   ~30 lines and immediately makes the generic card view legible.

### Deferrables

- **Agent fork primitive** — the "agent reads its own copy of the books" story needs a
  room-DB clone-at-tx primitive that neither simmis nor kontor currently has. Track as a
  separate spike; do not block the hybrid integration on it. The bridge + report-block
  contract work regardless of fork mechanics.
- **Authorization** — kontor's `kontor.authz` ReBAC and simmis's `parties.clj` party
  model need a merge story; punt to a follow-up note once both sides stabilize. The
  bridge does not commit either way.
- **Bitemporal time slider for generic cards** — only do this if user testing shows that
  account-card and partner-card views need it. The report blocks already cover the
  high-value bitemporal surface.
- **Optimistic kontor writes** — `is.simm.optimistic` does not know about kontor's
  validation gate (`transact-with-validation`). Optimistic transaction posting can show
  wrong UI while the gate rejects on the server. Either route kontor writes through a
  non-optimistic path or teach the optimistic layer to call the gate client-side. Defer
  until the bespoke `transaction_form.cljc` lands and user friction is observable.

The hybrid path keeps kontor a pure kernel, keeps simmis's categorical substrate
intact, and isolates the few accounting views that genuinely need bespoke code behind a
small, stable contract.

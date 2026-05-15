# 47 — Inventory issuance + transaction composition: prior-art read

Audience: design call for the last orchestrator cluster (`issue!` /
`complete-transfer!` / `count`) migrating to `kontor.process` (ADR-067).
Pre-read: ADR-059 (negative-fill policy), research 44 (transaction
composition prior art), 46 (`kontor.process` survey).

## Bottom line

**Option (b) — merge into ONE `kontor.process`, thread the negative-fill
layer by a string tempid.** It is the smallest change consistent with
both ADR-067's atomicity guarantee AND the established prior art:
**Odoo, NetSuite, and Tryton ALL post the over-issue and any
correction as separate, non-atomic events** — but each *individual*
move is one transaction. kontor's current two-`d/transact` `issue!` is
a step BEYOND the reference systems (it pre-creates a layer they don't
have), and merging it into one atomic process is *strictly safer*: the
audit chain still distinguishes "estimated" from "trued-up" via
`:negative-fill/status`, and the consumption tx-data resolves the
speculative layer eid correctly because string tempids round-trip
through `d/db-with` to the SAME eid the final commit assigns
(verified — see § Composition).

Option (c) — introducing a saga primitive — is rejected: nothing in
the local code currently warrants the complexity, and the reference
systems' answer to "phase A acknowledges, phase B acts on it" is
**either one DB transaction (Tryton's `Workflow.transition`) or two
honest, separately-committed events (Odoo, NetSuite, SAP)**. There is
no third "logically atomic, physically split" primitive in the prior
art.

## Odoo evidence

### Negative stock is NOT pre-fulfilled with a placeholder layer

`addons/stock_account/models/product.py:527-566` (`_run_fifo`): when an
issue's quantity exceeds the FIFO stack,

```python
# When we required more quantity than available we extrapolate with the last known price
if quantity > 0:
    if last_move and last_move.quantity:
        fifo_cost += quantity * (last_move.value / last_move.quantity)
    else:
        fifo_cost += quantity * self.standard_price
```

The over-drawn portion is **valued inline at the last-known unit
price** (or `standard_price` if there is no prior receipt). There is
NO pre-created "placeholder" / "negative-fill" / "anonymous" layer
written ahead of the issue — the value is computed and the issue posts
as one normal stock move.

### Single-move = single transaction

`addons/stock_account/models/stock_move.py:168-181` (`_action_done`):

```python
def _action_done(self, cancel_backorder=False):
    moves_out = self.filtered(lambda m: m._is_out())
    moves_out._set_value()                # compute .value (incl. negative)
    moves = super()._action_done(cancel_backorder=cancel_backorder)
    moves_in = moves.filtered(lambda m: m.is_in or m.is_dropship)
    moves_in._set_value()
    moves._create_account_move()          # post the GL
    ...
```

One Odoo ORM transaction; one `account.move` post. `_set_value` reads
the FIFO stack and writes `move.value` (which silently flows through
`_run_fifo`'s "extrapolate with last known price" branch for negative).
The over-issue is a regular goods issue at a *cost the system makes
up*.

### True-up is a SEPARATE later event (the "vacuum")

`addons/stock_account/tests/test_stockvaluation.py:293-383`
(`test_fifo_negative_1`): the test sends 50 units of stock-the-system-
does-not-have, posts the COGS at `standard_price=8`, then later
receives 40 @ 15. The closing wizard (`_close()`) emits a *second*
journal entry — the variation — moving the additional 7 €/unit ×
40 units = 280 onto inventory↔variation. The first `valuation` journal
posted Cr 400 / Dr 400 at the over-issue moment; the second posted
Dr 250 / Cr 250 to true up against the actual receipt. **Two distinct
account moves, two distinct dates, no atomic linkage at commit time.**

The Odoo community refers to this background process as `_run_fifo_
vacuum`, paired with the per-period closing entry. NetSuite calls it
"System COGS Adjustment" (see § SAP / NetSuite below). Same shape;
different name.

### Tryton evidence

### Negative stock is allowed; cost is computed *after the fact*

`tryton/modules/stock/doc/usage/quantity.inc.rst:13-22`:

> "Tryton is designed to allow you to create stock moves even if they
> create negative stock. … negative stock levels indicate that more
> stock has been used than was available."

`tryton/modules/stock/move.py:842-922` (`Move.do`): the workflow
transition posts each move's `cost_price` by calling `move._do()`,
which delegates to `_compute_product_cost_price` for `'average'`-
costed inflows/outflows. The `_do()` for an `'average'` outbound
returns `None` for cost (move.py:974-986) — the *running* cost stays
where it was; nothing special happens for negative stock at issue
time.

The reconciliation of cost is **deferred to a queued job**: `product.py:
335-345` registers `recompute_cost_price_from_moves` which calls
`recompute_cost_price` on the affected products *after the moves are
done*. The recompute walks all moves chronologically (`product.py:
434-535`) and *rewrites* `move.cost_price` on each move as it goes —
the negative-stock period's cost is whatever the algorithm rolls up
once subsequent receipts arrive. Tryton thus blurs the audit line
between estimate and true-up by *retroactively writing the issue's
cost_price* — a choice kontor's bitemporal+sealing story explicitly
forbids (ADR-007 — silent retraction is denied).

### GL leg sits in a thin per-move bridge

`tryton/modules/account_stock_continental/stock.py:96-181`: `Move.do`
is wrapped — `super().do(moves)` posts the physical move; then a
loop builds + saves + posts one `AccountMove` per stock move
(`_get_account_stock_move`, lines 100-166; the `AccountMove.post`
loop at 171-181). All running inside the same Tryton DB transaction.
**One physical move, one accounting move, atomic at the DB level** —
exactly the shape `kontor.process` provides.

There is no "negative-fill layer" entity anywhere in Tryton. There is
no two-transaction `issue!` shape.

## SAP / NetSuite

### NetSuite — "System COGS Adjustment"

Oracle's documentation
(<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2195087.html>)
is explicit:

- The over-issue **posts a GL entry immediately** at an estimated cost
  computed per the "Use Cost Estimate for Negative Inventory"
  preference: last-purchase, zero, or average.
- "When you sell an item that's not in stock, NetSuite makes an
  adjustment to the on-hand value of the item. This adjustment is
  called a system COGS adjustment …"
- The **true-up is a SECOND, linked journal entry** posted when actual
  receipts arrive: `[Estimated COGS] - [Actual cost]` is the delta and
  flows as a "system COGS adjustment" entry.

NetSuite's preference list does *not* include a "pre-create a
placeholder layer" option. The estimate is computed on-the-fly at the
issue; the layer-shape concept (FIFO bins) is internal to costing and
not visible at the GL level.

### SAP MM — "Negative Stock Allowed"

Configurable per valuation area + per material master flag
(<https://www.erpgreat.com/mm017.htm> covers the OMJ1 customization;
<https://userapps.support.sap.com/sap/support/knowledge/en/3002010>
covers the movement-type 561/551 restriction).

What I could NOT establish from public docs is whether SAP defers the
GL leg or posts an immediate offset. The community blogs
(<https://www.quora.com/What-is-negative-stock-in-SAP-MM>,
<https://ganeshsapscm.com/2016/09/24/give-few-points-about-negative-stocks-in-sap-inventory-management/>)
describe the operational permission to go negative but are silent on
the GL true-up flow. I'd guess from the moving-average semantics that
SAP posts the issue immediately at the current MAV and that the next
GR re-averages on the fly — i.e. no explicit second journal entry; the
correction is absorbed into the post-receipt MAV. **This is a guess,
not a citation** — flag for follow-up if the SAP shape matters.

## The composition primitive question

### (a) Keep two `d/transact`s, both gated

Honest about the two business events. Costs: the period gate (and any
future invariant) must succeed independently on each — if the second
fails, the first is committed and `:negative-fill/status :open`
without an originating issue, an orphan. Mitigation today: the second
transact almost cannot fail because the first one made it possible.
But "almost" leaves a window the audit story dislikes.

### (b) ONE process, thread the layer via string tempid (RECOMMENDED)

Matches Tryton's "one workflow transition = one DB transaction" shape
(`Move.do` + `super().do` + `AccountMove.post` all in one Tryton
transaction). Datahike's `d/db-with` semantics make this safe:
**the same string tempid resolves to the same numeric eid** in the
speculative db AND in the final commit (verified in REPL: a `db-with`
fragment yields the same eid the subsequent `d/transact` of the same
fragment commits, both 591 in the smoke test).

So `plan-stock-move` reading `available-layers` on the speculative db
gets the speculative-eid of the just-created negative-fill layer,
emits `:layer-consumption/layer <that-eid>` in its tx-data — and at
final commit, the consumption's reference resolves to the SAME eid
because it never left the one accumulated tx-data vector that
`run-process` hands to `transact-with-validation`. **No tempid
threading on the consumption side is needed.**

The single change required is that the negative-fill step emits a
fragment with a *string* tempid for the layer (not the integer
`-200` / `"neg-fill-layer"` mix the current ops.clj uses, which works
inside one `d/transact` call but is fragile across steps), so each
process invocation gets a distinct identity.

### (c) New "saga" primitive

The prior-art read does not motivate this. Saga patterns (Baeldung,
microservices.io) target the *distributed* case — multiple services,
multiple DBs, network failure. kontor lives in one datahike conn. The
problem `kontor.process` solves IS the orchestration problem. Adding
a second composition layer on top would be over-engineering, and the
ADR-067 footnote on the rejected `:db.fn/call`-the-whole-process
variant already lists the alternative-evaluated alternatives.

### (d) Other — "honest two-step process"

There IS a fourth option worth naming: write `issue!` as a
two-`run-process` *sequence* with explicit naming —
`acknowledge-shortfall!` then `issue!` — where each is one atomic
gated process. This is option (a) in disguise but with both halves
going through `transact-with-validation`. It surfaces "we had to
acknowledge before we could issue" in the API. Probably wrong for
kontor because the orchestrator's caller (a consumer like beleg)
should not see the split; the consumer asks for "issue 50 units" and
the kernel decides whether a fill is required.

## Implementation sketch for option (b)

Files: `modules/inventory/src/kontor/inventory/ops.clj`,
`src/kontor/posting.clj` (zero change needed),
`modules/inventory/test/kontor/inventory/ops_test.clj` (add an
assertion that the negative-fill layer + the consumption share the
SAME final eid — the regression guard).

New shape of `issue!`:

```
(defn issue! [conn spec]
  (let [steps
        [;; Step 1 (run only if needed): negative-fill fragment.
         ;; Conditional: planner reads sdb; if plan-consumption returns
         ;; an :underflow > 0 AND the policy allows it, emit the
         ;; origin-tx + layer + neg-fill records with string tempids
         ;; "nf-tx-<n>", "nf-layer-<n>", "nf-<n>". Otherwise emit nil.
         (fn [sdb ctx] ...)

         ;; Step 2: the actual issue.
         ;; Calls plan-stock-move on sdb — which now sees the layer
         ;; from step 1 (if any), with its speculative-but-stable eid.
         ;; Returns plan-stock-move's tx-data + the inventory-detail
         ;; fragment with -1 as the txn tempid.
         (fn [sdb ctx] ...)]]
    (process/run-process conn
                         {:steps steps
                          :vt-from (or (:effective-date spec) (Date.))})))
```

Contracts the design preserves:

- `transact-with-validation` runs ONCE per `issue!` call (period gate,
  sealing, sum-to-zero — all on the assembled tx-data).
- `:negative-fill/status :open` still marks the estimate vs trued-up
  audit boundary — `true-up-negative-fill!` stays a separate
  `kontor.process`, called later (it is a separate business event,
  matching Odoo's vacuum / NetSuite's system COGS adjustment).
- The physical bucket creation no longer needs the "or one is created
  by `create-negative-fill!`" branch — the bucket fragment threads
  inside step 1 OR step 2 by string tempid, never both, the speculative
  db tells step 2 if step 1 already wrote one.

Tempid scheme (deterministic + collision-free):

| Entity | Tempid (string) |
| --- | --- |
| Issue transaction | `"issue-tx"` |
| Negative-fill origin transaction | `"nf-tx"` |
| Negative-fill layer | `"nf-layer"` |
| Negative-fill record | `"nf"` |
| Inventory bucket (if new) | `"inv-item"` |

`plan-stock-move`'s internal `-1` / `-200` / `-300` / `-400` integer
tempids stay LOCAL to its returned tx-data fragment and never collide
with the strings step 1 emits, because datahike treats string and
negative-integer tempids in separate namespaces *within one tx-data
vector*. Where the current `ops.clj` re-uses `-1` to *link* the
`:inventory-detail/transaction` to the issue txn — that linkage still
works because step 2 controls both the `tx-base` (with `:db/id -1` in
`plan-stock-move`'s output) and the `:inventory-detail` fragment it
appends.

What does NOT change:

- `plan-stock-move`'s signature.
- `available-layers`' contract (eid-returning).
- The `:negative-fill` schema or its `:open` → `:trued-up` lifecycle.
- The l10n hooks (variance account routing in `true-up-negative-fill!`).

Test additions:

1. Negative-stock issue: assert one tx-report, not two; assert the
   `:layer-consumption/layer` eid equals the `:negative-fill/valuation-layer`
   eid (the regression guard on tempid round-trip).
2. Period gate: if the period containing `:vt-from` is closed, neither
   the negative-fill nor the issue is written (today: the negative-fill
   sneaks in before the period gate refuses the issue).
3. Sum-to-zero: a synthetic step that drops a leg from `plan-stock-move`'s
   output fails the whole `run-process` (today: the negative-fill
   commits first, then the issue's unbalanced posting aborts — orphan).

`complete-transfer!` and `count` get the same treatment, but they have
no negative-fill branch and are mechanically simpler — a single step
calling `plan-stock-move` (or the count-adjustment posting builder),
wrapped in `run-process`. The cluster as a whole goes from "5
orchestrators each doing 1-2 raw `d/transact` calls" to "5 thin
`run-process` wrappers", consistent with the rest of the migration.

## Open question (flagged for the user)

Should the inventory-detail's `:inventory-detail/transaction` linkage
point at the *issue* transaction (current behavior) or also carry a
back-pointer to the negative-fill's origin-transaction? The current
schema has `:negative-fill/origin-issue` going one way; the inverse
would let a stock-ledger query "show me everything the issue caused"
walk both halves in one hop. Defer to the cross-stage user-story pass;
not a blocker for (b).

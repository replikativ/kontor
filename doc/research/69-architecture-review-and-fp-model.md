---
date: 2026-05-17
title: 69 — Kontor architecture review + clean FP model for trans-national corp mgmt
status: draft
audience: maintainer + future-self mapping the next 6-12 months of architecture
---

# 69 — Kontor architecture review + clean FP model for trans-national corp mgmt

A friend-of-the-project review. I read every kernel namespace (36 files,
~13 kLoC after the bitemporal port), walked the 26 companion modules,
spot-checked ~30 `*-tx-data` builders, and traced two realistic
trans-national workflows. The kernel is in good shape and the
ADR-067/068 direction (process + universal builders) is genuinely
load-bearing. What stops kontor from being the clean substrate the
maintainer wants is not the recent work — it is older accretion
that has not been cleaned up since: a kitchen-sink kernel folder,
two `kontor.invoice` namespaces, a `TaxProvider` protocol still
unwired after a year, and the absence of `:entity` filtering in the
read-side (reports, balance, trial) that the maintainer's "multi-
national" framing demands. The reshaping plan in §6 is six items,
sized small-to-medium, and each one is a precondition for the FP
end-state in §7.

---

## TL;DR

- **The kernel is structurally sound but laid out flat.** 36
  namespaces sitting at one level under `src/kontor/` is the chief
  ergonomics drag. They cluster cleanly into 6 concern-groups (§1);
  collapsing them into a directory layout (`src/kontor/posting/`,
  `src/kontor/time/`, `src/kontor/lifecycle/`, `src/kontor/io/`)
  shortens onboarding and exposes where the kitchen-sinks live.
- **ADR-068's `*-tx-data` builder pattern works.** 87 builders
  across 207 `!`-wrappers, with consistent `(db, opts) → tx-data`
  shape, `:tempid` / `:tempid-suffix` / `:tx-tempid` knobs where
  composition demands. The convention is real and earns its keep.
- **ADR-067's `kontor.process` is *partially* adopted.** 17 source
  files require it; the heavy orchestrators (lease, asset, inventory,
  payment-allocation) route through `run-process` correctly. The
  *kernel* sites that still do `d/transact` directly are conscious
  carve-outs (the period lock-tx denorm, authz's standalone path,
  l10n chart seeders, schema installers) — all documented in
  ADR-068's "carve-outs" section. The *un-documented* leaks are the
  three `d/transact` calls in `kontor.inventory.{core,count}` — see
  §2.4. **Closing this last gap unlocks the "every commit is gated"
  invariant the maintainer keeps citing.**
- **The biggest single thing to fix is the read-side `:entity`
  axis.** Multi-entity *writes* are first-class (ADR-031, posting
  sum-to-zero per (entity, ledger, commodity)) but multi-entity
  *reads* are not: `kontor.balance`, `kontor.trial`,
  `kontor.report`, `kontor.financial-statements` accept no `:entity`
  parameter. For a "trans-national" pitch this is the load-bearing
  missing axis. The fix is local (an extra opt threaded through 4
  fns + one entity-filter predicate) and unblocks consolidation §4.
- **TaxProvider is a corpse.** `StaticTableProvider/resolve-taxes`
  returns `[]` (literally — `tax_provider.clj:78-82`). Zero callers
  in `src/` or `test/`. l10n modules compute taxes themselves in
  their invoice posting-builders. Either wire it or delete it; the
  current state misleads consumers who read the docstring and
  expect to plug a provider in.

---

## 1. Current structure assessment

### 1.1 Kernel (`src/kontor/`)

36 namespaces, ~13,300 LoC after the bitemporal port shrunk
`bitemporal.clj` from 400 → 67 LoC (commit 47daf36). Grouped by
concern:

**Write-side primitives (the load-bearing core).**
`posting.clj` (784 LoC; sum-to-zero per (entity, ledger,
commodity) + `plan-stock-move`); `money.clj` (416, the `Money`
type); `validation.clj` (225; `transact-with-validation` IS
the gate — chains legal-hold + sealing + period + state-
machine + sum-to-zero); `sealing.clj` + `state_machine.clj`
(kernel-internal `:transaction/state` lifecycle);
`bitemporal.clj` (67 LoC post-port — only `with-vt`,
`strip-tx-meta`, `forever`; everything else delegates to
upstream `:db.valid/*`); `process.clj` (138; ADR-067).

**Read-side queries.** `balance.clj` (138), `trial.clj` (69),
`ledger.clj` (169), `report.clj` (293), `financial_statements
.clj` (349), `aging.clj` (136). All bitemporal-aware via
`:as-of-valid`/`:as-of-tx`. **All lack a `:entity` filter —
the load-bearing read-side gap (§4 Gap 1).** `report.clj`
ships a `:ledger` filter (`:220-237`) that is the obvious
template.

**Lifecycle / governance (cross-cutting).** `status_machine.clj`
(464, ADR-034); `audit_doc.clj` (319, ADR-038/051);
`legal_hold.clj` (597, **largest kernel ns**, ADR-049);
`retention.clj` (560, ADR-050); `dsar.clj` (485, ADR-052);
`period.clj` (449, the read+write+validate boundary case). The
three biggest — legal-hold, retention, dsar — share the same
internal shape (schema seeds + tx-data builders + `!` wrappers
+ validator/sweeper). Could split into sub-namespaces; defer
unless one grows another ~30%.

**Provider protocols.** `tax_provider.clj` (147; **unwired
stub**, §2.5); `costing_provider.clj` (341; wired into
`plan-stock-move`); `einvoice_provider.clj` (156, ADR-017).

**Money flow.** `payment_application.clj` (537; routes the
FIFO allocator through `run-process`, `:536`);
`reconciliation.clj` (554; has builders, **does NOT use
`run-process`** at sites `:101`, `:479`);
`bank_account/bank_csv/payment_term` (small, well-scoped).

**Closing + scheduling.** `closing.clj` (233; **does not use
`run-process`** despite being on ADR-067's stated migration
list, blocked by the same `:db/current-tx`-as-value carve-out
as `period/close!`); `schedule.clj` (244, ADR-032).

**Single business-doc lifecycle.** `invoice.clj` (472,
kernel-side create/send/mark-paid/cancel) — **collides with
`modules/invoice/src/kontor/invoice/bridge.clj`** in naming
(§1.3).

**Infrastructure.** `core.clj` (157), `schema.clj` (3597,
the catalog — past 5 kLoC it becomes unreviewable, see §6
item 4b), `entity.clj` (121, hierarchy helpers + the
multi-entity primitives), `valuation.clj` (291),
`import_/beancount.clj` (sic — Clojure forbids `import` as a
ns segment, so the ns is `kontor.import-.beancount`, leaking
into every `(:require)`; renaming opportunity in §6).

### 1.2 Companions (`modules/`)

26 module artifacts in 6 conceptual groups:

| Group | Modules | Pattern |
|---|---|---|
| Kernel-adjacent business workflows | `partner`, `sales`, `invoice`, `procurement`, `collections`, `expense`, `inventory`, `lease`, `asset` | One ns per domain entity + `bridge.clj` where it interfaces with kernel posting. Schema in `<module>/schema.clj`. |
| Per-country localizations | `l10n-{de,fr,ca,us,at,au,jp,cn,in,br,mx}` | Chart of accounts, tax tables, return formats, e-invoice emitters. Pure data + thin code. |
| Bank-statement parsers | `bank-{de,fr,ca,us,at}` | Each ships one `parser.clj`. Output feeds `kontor.reconciliation`. |
| E-invoice transmitters | `einvoice-de` | Mustang-based Factur-X / XRechnung / ZUGFeRD wrapper. |
| Cross-cutting | `authz` (ReBAC, ADR-065/066) | Has its own gate-bypass (documented in ADR-068). |

The module pattern is consistent and the cohabitation invariant
holds (ADR-002): every module declares its own attribute
namespaces, ships an `install!` (or `install-schema!`), and is
opt-in. Modules are not interdependent except through the kernel,
with two exceptions:

- **lease consumes asset.** `kontor.lease.runner/commence!` calls
  `kontor.asset.asset/acquire-tx-data` to create the ROU asset
  (`runner.clj:223` area) and `kontor.asset.depreciation/open-
  book-tx-data` for the ROU depreciation book. This is a *good*
  cross-module composition — the lease module reuses asset's
  builders inside its `run-process`. The shape is exactly what
  ADR-068 promised.
- **invoice (kernel) and invoice (module).** See §1.3.

### 1.3 Overlap / unclear-ownership findings

**`kontor.invoice` (kernel) vs `kontor.invoice.bridge` (module).**
The kernel ships create / send / mark-paid / cancel for a non-
order-aware invoice. The module ships the order-aware variant plus
the GL-posting trigger. The module's `bridge.clj:1-23` docstring
explicitly warns about the naming collision. **This is a smell.**
The collision creates three problems:

  1. Consumers can't easily tell which API they should call.
  2. The kernel `kontor.invoice/send!` builds a transaction via
     `posting/build-transaction` from a caller-supplied
     `posting-builder` fn — which is the same job
     `modules/invoice/src/kontor/invoice/posting.clj`
     (`post-to-ledger!`) does, but on order-aware data.
  3. The kernel `invoice` ns reaches into `modules/invoice`'s
     status-machine seeds (the seeds live in the module's
     `schema.clj` but the transitions trigger when the kernel
     `invoice.clj` flips a status) — installation ordering matters.

**`balance.clj` vs `trial.clj`.** Not overlapping — `trial` is a
1-page helper that iterates accounts calling `balance`. Healthy
factoring.

**`closing.clj` vs `period.clj`.** Adjacent concerns, distinct
responsibilities (closing posts the retained-earnings entry;
period locks the calendar window). Healthy.

**The three "biggest" kernel namespaces (`legal_hold` 597,
`retention` 560, `payment_application` 537) and the one in the
middle (`dsar` 485)** all have the same internal shape: schema
seeds + tx-data builders + the `!` wrappers + the validator (if
any) + the sweeper. They could be uniformly split into 3-file
sub-namespaces (`kontor.legal-hold.core` / `kontor.legal-hold.seeds`
/ `kontor.legal-hold.sweeper`) but the current monolithic form is
defensible — the seed list is tightly coupled to the validator
which is tightly coupled to the builder, and splitting would
inflate `(:require)` headers everywhere. Mark as P2 unless one of
them grows by another ~30%.

**Kitchen-sink risk: `schema.clj` at 3.6 kLoC.** Adding the next
companion-cohabitable attr ns (`:hr/*`, `:payroll-period/*`,
`:fx-rate/*`, `:consolidation-mapping/*`) means adding a section
to this file. Past 5 kLoC the file becomes unreviewable. A split
into `src/kontor/schema/{core,time,money,tax,…}.clj` re-aggregated
by the existing `install!` is mechanical (see §6, item 4).

---

## 2. FP discipline (purity, composability, kontor.process adoption)

### 2.1 Purity & `!`-discipline

The convention is **strict and consistently followed**:

- Every fn that ends in `!` performs `d/transact` (or routes
  through the gate via `validation/transact-with-validation`).
- Every `*-tx-data` builder is `(db, opts) → tx-data` — pure
  Clojure, no side effects. The 87 builders I sampled all match
  this shape.
- Read fns are bare verbs: `balance/account-balance`,
  `trial/trial-balance`, `report/compute-report`,
  `entity/descendants`, `period/open?`, `sealing/find-silent-
  retracts`.

Two narrow exceptions:

- **`kontor.posting/build-transaction` embeds `kbt/with-vt`**
  (calling it `forever` as the default `:vt-to`,
  `posting.clj:371-373`). This is documented in ADR-068's
  carve-outs and the rationale is sound (the kernel's per-
  transaction valid-time is always `:transaction/effective-date`
  when nothing else is specified). Composers handle it by relying
  on `process/run-steps` to `strip-tx-meta` the fragment.
- **`kontor.ledger/running-balance` uses an `atom`** for the
  running per-commodity totals (`ledger.clj:163-169`). Pure-
  functional alternative would be a `reduce` with `[totals,
  rows]` accumulator. The current form is fine — the atom is
  function-scoped, never escapes, and the read-by-commodity
  pattern is genuinely awkward to express as a reduce. Mark as
  cosmetic.

I found **no instances** of mutable state escaping function
boundaries, no thread-local accumulators, no global registries
beyond the `provider` records (which are values).

### 2.2 Composability — the `*-tx-data` builder convention

Per ADR-068 every `defn xxx!` of the shape `(d/transact (with-vt …))`
splits into a pure `xxx-tx-data` + a thin `xxx!` wrapper. The
sample of ~30 builders I read confirms the pattern is real and
uniform across kernel + companions: kernel invoice
(`invoice.clj:62,153,266,312`), audit governance
(`audit_doc.clj:66,108,267`), payment application
(`payment_application.clj:231,340`), legal hold
(`legal_hold.clj:422,516`), period transitions
(`period.clj:318,375,423`), full asset lifecycle 8 fns at
`asset.clj:72,195,279,334,399,445,489,529`, collections
disputes/promises/credit-holds, lease lifecycle, authz writes
(`client.clj:136,160,182`).

The `:tempid` / `:tempid-suffix` / `:tx-tempid` knobs (ADR-067
addendum) are present where they need to be:
`acquire-tx-data` takes `:tempid` so `commence!` can thread
`"rou-asset"`; `apply-payment-tx-data` takes `:tempid-suffix`
so `allocate-fifo!` can fan N applications into one tx;
`build-transaction` takes `:tx-tempid` so the lease
modification transactor can post 3 transactions atomically.

**This convention works.** It is the lynchpin of the FP model.

### 2.3 `kontor.process` adoption

17 source files import `kontor.process` (excluding the namespace
itself + bitemporal which references it docstring-only):

```
kernel:        payment_application, schedule, posting (docstring),
               legal_hold, period (docstring)
modules/asset: asset, depreciation, posting, runner
modules/lease: core, liability, modification, posting, runner
modules/inventory: count, ops
modules/authz: client, schema
```

Concrete `(process/run-process …)` call sites I traced:

- `payment_application.clj:536` — FIFO allocator across N invoices.
- `inventory/ops.clj:389,617` — receive + issue stock moves.
- `inventory/count.clj:235,240` — post a count.
- `lease/runner.clj:297,554,698,750` — `commence!`, `import-lease!`,
  the modification entry points.
- `lease/modification.clj:371,509,652,767` — the four modification
  shapes (rate-revision, scope-change, term-extension, termination).
- `asset/runner.clj:169,208` — `run-depreciation!`, `catch-up!`.

The migration ADR-067 promised is **partially done**. The
explicitly-listed migration targets (`commence!`, `run-
depreciation!`, `run-lease!`, the modification transactors,
`allocate-fifo!`, the inventory flows) are all `run-process` now.
The two stragglers ADR-067 named — `closing/close-fiscal-year!`
and `posting/post-transaction!` — are NOT migrated:

- `posting/post-transaction!` doesn't need to be — it's a single-
  tx primitive, the leaf node of every process. Migration would
  be wasted ceremony.
- `closing/close-fiscal-year!` is documented as carve-out in
  ADR-068 (`:period/lock-tx` denorm requires a follow-up tx
  because `:db/current-tx` doesn't resolve as a value). That
  carve-out is real but worth a follow-up datahike PR (task #75).

### 2.4 Effect isolation — the leaks

Three `d/transact` calls bypass the gate without being documented
in ADR-068's carve-outs:

- `modules/inventory/src/kontor/inventory/count.clj:51` —
  `start-count!` transacts the `:physical-inventory` header.
- `modules/inventory/src/kontor/inventory/count.clj:85` —
  `record-count-line!` transacts one `:inventory-variance` row.
- `modules/inventory/src/kontor/inventory/core.clj:316` —
  `open-balance!` transacts an `:inventory-detail` + maybe an
  `:inventory-item`.

These are real bypasses — the writes go through none of the
gate validators (legal-hold, sealing, period, state-machine,
sum-to-zero). The risk is low (none of these write `:posting/*`
attrs, so sum-to-zero is irrelevant; the entities they write
aren't likely to be on legal hold or in a sealed period) but the
*invariant* is what makes ADR-068 valuable. Tighten by splitting
each into a `*-tx-data` builder + `transact-with-validation`
wrapper. ~30 minutes of work each. P1.

### 2.5 The unfired TaxProvider

`kontor.tax-provider/StaticTableProvider/resolve-taxes` returns
`[]` (literal empty vector — `tax_provider.clj:78-82`). Zero
callers anywhere (`grep -rn 'resolve-taxes' src/ test/`). Three
production callers of the *protocol* surface would have wired it
by now if the design were live: `kontor.invoice/send!`,
`modules/invoice/src/kontor/invoice/posting.clj/post-to-ledger!`,
and one of the l10n posting builders. None do. The l10n modules
compute tax postings inline in their invoice-posting builders
(see `modules/l10n-de/src/kontor/l10n_de/invoice.clj`).

The maintainer has two choices:

  1. **Wire it.** The l10n posting builders become
     `TaxProvider` implementations registered per-country.
     `kontor.invoice/send!` invokes the registered provider.
     Avalara / TaxJar adapters then plug in as alternative
     impls without touching the kernel.
  2. **Delete it.** Acknowledge that l10n modules are the
     "provider" by convention, and the explicit protocol is
     archeology from ADR-005's early planning. l10n_*/invoice.clj
     becomes the documented seam.

**My recommendation: wire it.** This is the *one* trans-national
seam where Avalara is the only commercially-viable answer for
US sales tax (~11,000 jurisdictions per ADR-005), and an unwired
protocol is worse than no protocol at all (consumers read the
docstring, build against it, find it returns nothing). Sized:
~1 week. See §6 item 5.

---

## 3. Composition story — two trans-national workflow traces

### 3.1 Intercompany journal: DE GmbH bills US LLC

**Scenario.** Acme GmbH (Munich) provides shared services to
Acme USA LLC. At month-end, GmbH issues an intercompany invoice
for €10,000 + 19% German VAT. The US LLC must record a USD
expense + USD-side intercompany payable. Group consolidation
eliminates the intercompany on both sides.

**What fires today.**

1. Caller invokes `kontor.invoice/send!` with the invoice eid
   + a `posting-builder` that produces a balanced 3-posting
   transaction tagged `:posting/entity [:entity/code "acme-de"]`
   on the primary ledger (Dr AR 11900, Cr Revenue -10000,
   Cr VAT-payable -1900 — all EUR).
2. `send-tx-data` (`invoice.clj:153`) composes that with the
   status flip + status-history row.
3. `transact-with-validation` gates the whole thing. Sum-to-zero
   passes per (entity, ledger, commodity).

So far so good. **Friction starts when we look at the US side:**

4. Where does the US LLC's *receiving* journal entry come from?
   `kontor.invoice/send!` posts one transaction in one currency
   with one `:posting/entity`. The US side is a *separate*
   bookkeeping event (different commodity, different entity,
   different chart of accounts). There is no kernel-level
   "intercompany journal" primitive that books both sides
   atomically.
5. The maintainer's options are:
   - Write a custom orchestrator (`intercompany-bill-tx-data`)
     that calls `build-transaction` twice — once per entity —
     and combines via `kontor.process`. The two transactions
     reference an `:intercompany/pair` denorm (which doesn't
     exist in the schema). Per (entity, ledger, commodity)
     sum-to-zero passes for each independently.
   - Lean on `:posting/entity` semantics: the GmbH AR posting
     and the USA payable posting BOTH sit in *one* transaction,
     each with its own `:posting/entity`. Per-(entity, ledger,
     commodity) sum-to-zero works — BUT only within commodity,
     and the two sides are in different commodities (EUR and
     USD). The transaction is balanced (sum to zero per
     commodity AND per entity), but there's no FX provider to
     compute the USD amount from EUR + a rate.
6. **FX provider is absent.** `kontor.lease.posting/plan-fx-
   retranslation` documents "kontor ships no FX-rate engine, the
   closing rate is a consumer input like `:discount-rate`"
   (`posting.clj:176-178`). For an intercompany flow, every
   transaction needs an FX rate; pushing it to the caller means
   every consumer writes their own rate cache. **Real missing
   primitive.**

**Consolidation elimination.** ADR-031 / `entity.clj:12-17` says
`:entity/kind :elimination` is a "synthetic entity holding
consolidation eliminations (NetSuite's Elimination Subsidiary)."
The elimination *posting* shape is implicit: at consolidation
time, you post Cr intercompany-AR (GmbH side) + Dr intercompany-
payable (USA side) onto an `:entity/kind :elimination`
sub-entity. **But there is no `eliminate-intercompany!`
transactor in the kernel.** Each consumer would build this from
scratch.

**Reporting the consolidation.** This is where the read-side
gap bites hardest. `kontor.financial-statements/compute-
statement` (`financial_statements.clj`) → `report/compute-
report` (`:239`) accepts `:ledger` (`:263`) but **not
`:entity`**. To get the "consolidated group P&L," the consumer
must:

  - Run the report for `entity = "acme-group"` (the
    consolidation entity).
  - Filter postings by walking `entity/family` themselves.
  - Re-sum, manually.

There is no kernel-level "consolidated trial balance" call. For
the maintainer's "large-scale trans-nationals" pitch, this is
the load-bearing missing piece.

**Friction summary for this workflow:**

  - **F1.** No `intercompany-bill-tx-data` builder (consumer
    rolls their own).
  - **F2.** No `FxRateProvider` protocol (consumer rolls a rate
    cache).
  - **F3.** No `eliminate-intercompany-tx-data` builder.
  - **F4.** No `:entity` filter on reports / balance / trial.
    The most-mechanical of the four.

### 3.2 Backdated payroll correction on a held entity

**Scenario.** HR reports that Q1 2026 payroll for entity
"acme-uk" understated PAYE by £2,000 across 30 employees.
The correction must be valid-dated to the original payroll
date (#inst "2026-03-31") but recorded today (#inst
"2026-05-17"). Acme UK is currently on a `:legal-hold`
(litigation discovery) and the Q1 period is `:period/locked-at`
(soft-closed).

**What fires today.**

1. The consumer builds the correction tx-data — but there is
   no `kontor-hr` or `kontor-payroll` module. The kernel has
   no `:employment/*`, `:payroll-period/*`, `:wage/*` attrs.
   So step zero is: the consumer writes the entire payroll
   schema themselves.
2. Assume they have. They build a `posting-builder` returning
   a balanced transaction (debit PAYE expense, credit PAYE
   payable), passes to `kontor.posting/post-transaction!`,
   with `:vt-from #inst "2026-03-31"` and `:vt-to` defaulting
   to `kbt/forever`.
3. `transact-with-validation` runs:
   - `legal-hold/assert-no-hold-violating-destructive-writes!`
     fires first (`validation.clj:182`). The correction is a
     `:db/add` (not `:db/purge`), so this **passes** — legal
     hold blocks deletions, not appends. ✅
   - `sealing/assert-no-silent-retracts!` — same logic; passes.
   - `period/assert-no-write-on-sealed!` — Q1 is `:locked-at`
     not `:sealed-at`, so passes.
   - `period/assert-not-in-locked-period!` (`period.clj:151`)
     — **fires**. The valid-time `#inst "2026-03-31"` falls
     in Q1 which is `:locked-at`. The transaction is
     **rejected** with `:type :period/locked-period-violation`.
4. The remediation in the error message
   (`period.clj:160-168`) says "set `:posting/period-tag` to
   route into an open adjustment period like `:adjustment-13`."
   So the consumer must:
   - Open an `:adjustment-13` period covering 2026 Q1 (if
     not already open).
   - Re-emit the postings with `:posting/period-tag
     :adjustment-13`.
   - Re-submit.
5. The second attempt passes the gate. The status-machine
   on whatever payroll entity exists (e.g., a `:payroll-run`)
   transitions `:posted → :corrected`, writing a `:status-
   history` row with `:reason :payroll-restatement`,
   `:supporting-doc` (the HR correction memo), and
   `:changed-by-uid`.

**Friction summary for this workflow:**

  - **F5.** **No `kontor-hr` companion.** Research note 09
    exists but no schema, no transactor, no `:employment/*` ns.
    Every consumer rolls their own. *This is the single biggest
    "trans-national" gap.*
  - **F6.** The `:adjustment-13` adjustment-period mechanism
    works structurally but the UX assumes the consumer knows
    the convention. A `(kontor.period/post-correction!
    conn opts)` helper that auto-routes to the open adjustment
    period would smooth this.
  - **F7.** Legal-hold + period-lock + status-machine
    **compose correctly** without fighting. The middleware
    fire-order in `validation/validate-and-apply`
    (`validation.clj:181-187`) puts legal-hold first
    deliberately so the more-specific error wins. The
    bitemporal valid-time + status-history sequence means the
    audit trail says "on 2026-05-17 we recorded a correction
    *valid as of 2026-03-31*, citing HR memo XYZ, on a
    held entity, in an adjustment period." Honest, defensible,
    reconstructible. ✅

The kernel handled the *plumbing* correctly. What it can't do
is the *domain* — there's no payroll schema and no FX, so the
consumer ships ~80% of what the maintainer's "trans-national"
pitch implies.

---

## 4. Trans-national gaps (concrete, ranked)

The maintainer's framing is "the substrate a multinational
corp-mgmt layer would sit on" — not "a complete ERP." Ranking
the gaps against that frame:

### Gap 1: Multi-entity *read-side* — `:entity` filter on every report

**Severity: P0.** This is the smallest fix on the list and the
one with the highest leverage. `report.clj:263` accepts `:ledger`
but not `:entity`. `balance.clj:108` accepts neither. The schema
is right (`:posting/entity` is a `:ref`, ADR-031); the writes
balance per-(entity, ledger, commodity); the read-side just
ignores the dimension.

**Fix sketch.**
```clojure
;; balance.clj
([conn account-eid {:keys [as-of-valid as-of-tx include-states entity]}] …)

;; report.clj
(defn- entity-filter-pred
  [db entity-spec]
  (if (nil? entity-spec)
    (constantly true)
    (let [ents (entity/family db (entity/resolve-entity db entity-spec))]
      (fn [p] (contains? ents (:entity-eid p))))))
```

`entity/family` (`entity.clj:94`) already exists. The change is
one new opt, one new pred, one pull-attribute addition in `pull-
posting` (`report.clj:78-110`), and updates in 4 call-sites in
`financial-statements.clj`. Sized: ~3 hours. Tests: a single
`compute-report` against a 3-entity fixture.

### Gap 2: FX rate provider + currency translation

**Severity: P1 for SMB, P0 for trans-national.** Today every FX-
sensitive flow (`lease/posting/plan-fx-retranslation`, the
intercompany scenario above, any multi-currency invoice from a
DE GmbH selling in USD) treats FX as a consumer input. For a
multi-national substrate this is wrong — IAS 21 (functional vs
presentation currency) is the *substrate's* responsibility, not
the consumer's.

**Fix sketch.** Mirror `TaxProvider`:
```clojure
(defprotocol FxRateProvider
  (provider-id [this])
  (resolve-rate [this {:keys [from-commodity to-commodity
                              at-date rate-type]}])
  (resolve-period-rates [this {:keys [from-commodity to-commodity
                                      from-date to-date frequency
                                      rate-type]}]))
```

Built-in impls: `StaticRateTable` (CSV / EDN), `ECBProvider`
(public ECB reference rates, EPL-clean), `XE` / `OANDA`
adapters (customer brings API key, never bundled). Wire into:

  - `kontor.posting/build-transaction` — optional `:fx-provider`
    that auto-converts a `:posting/amount` in a foreign commodity
    to the entity's functional currency (per `:entity/functional-
    currency`, which already exists in the schema:
    `schema.clj:2989-2993`).
  - `kontor.report/compute-report` — optional `:translate-to`
    that re-keys per-commodity totals into one presentation
    currency at the appropriate rate type (closing rate for BS
    items, average rate for P&L items per IAS 21).
  - `kontor.lease.posting/plan-fx-retranslation` — drop the
    "consumer supplies `:gain-loss`" requirement; compute from
    the provider.

Sized: ~2 weeks for the protocol + ECB impl + wiring + tests.
Stage R candidate before HR.

### Gap 3: HR / Personnel / Payroll substrate

**Severity: P1 for the trans-national pitch, P3 for SMB.**
Research note 09 exists; no schema. For a multinational the
employee count is the headcount that operations management
plans against; without `:person/*` + `:employment/*` (effective-
dated, multi-job per Workday pattern), and at minimum a `kontor-
payroll-DE-datev` adapter, the maintainer's pitch is materially
incomplete. The roadmap item exists as "Stage R" but has not
landed.

The kernel already has 70% of what a payroll module needs:
status machine for `:employment/state` transitions, audit-doc
for contracts + amendments, legal-hold + retention for personnel
data (where retention periods are jurisdictionally distinct from
accounting retention), bitemporal valid-time for backdated
corrections (the §3.2 scenario). What's missing is the
*personnel domain entities* and the *payroll engine* (gross-to-
net calculation, employer-side accruals).

**Sized.** `kontor-hr` schema + transactors: ~3 weeks.
`kontor-payroll-de-datev`: ~3 weeks (DATEV LODAS format
emitter). Together = Stage R.

### Gap 4: Consolidation primitive

**Severity: P1.** Schema has `:entity/kind :elimination` and
`:entity/kind :consolidation` (`entity.clj:12-17`, `schema.clj:
3013-3017`); zero transactors that *do* the elimination. A
multinational doing parent-sub consolidation needs at minimum:

  - `eliminate-intercompany-tx-data` — given a pair of
    transactions tagged `:intercompany/pair` and posted to two
    different `:operating` entities, emit the offsetting
    postings on the `:elimination` sub-entity.
  - `currency-translation-tx-data` — given an `:operating`
    entity with functional currency ≠ group presentation
    currency, translate its trial-balance per IAS 21 (closing
    rate for assets/liabilities, historical for equity,
    average for P&L). Requires Gap 2.
  - `consolidate!` orchestrator — `run-process` that walks
    `entity/family`, runs currency-translation, runs
    elimination, posts everything to the `:consolidation`
    entity.

The schema *anticipates* this (`:entity/parent-entity`,
`entity/descendants`, `entity/family`) — the missing piece is
the transactors. Sized: ~2 weeks after Gap 2 lands.

### Gaps 5-8 (deferred companions)

  - **Gap 5: Transfer pricing + BEPS Pillar 2** (P2 kernel,
    P1 brochure). Kernel coverage zero; needs a `kontor-tax-
    provision` companion on top of consolidated TB (Gap 4).
    Intentional v1 scope cut.
  - **Gap 6: Treasury / hedging** (P2). Bank importers exist
    in 5 country modules; cash-position aggregation is
    straightforward once Gaps 1+2 land. Hedge accounting
    (IFRS 9 / ASC 815 effectiveness testing) is its own
    companion.
  - **Gap 7: Income tax / deferred tax (ASC 740 / IAS 12)**
    (P2 kernel). The parallel-ledger machinery (ADR-021)
    supports `:ledger/framework :legal` (book) vs `:tax` (tax
    basis); the temporary-difference engine + the Pillar 2
    integration belong in `kontor-tax-provision` alongside
    Gap 5.
  - **Gap 8: Project / cost-center at scale** (Partial).
    `:analytic-plan/*` exists (ADR-012/022/032). Missing is
    `kontor-project` (roadmap Stage P) — project entity,
    budget, actuals-vs-budget reporting.

### Top-3 gaps to fix next

  1. **Gap 1 — `:entity` filter on the read side.** P0,
     local, ~3h.
  2. **Gap 2 — `FxRateProvider`.** P1, ~2 weeks, unblocks
     Gaps 4 & 6.
  3. **Gap 4 — Consolidation primitive.** P1, ~2 weeks
     after Gap 2.

Everything else is companion-shaped and can land per
demand. Gaps 5 / 7 are intentional scope cuts; Gap 3 is the
roadmap's Stage R; Gap 8 is Stage P; Gap 6's hedge piece
is a long-tail companion.

---

## 5. API surface health

**Kernel public surface.** `kontor.core/*` is intentionally
thin (`install-schema!`, `create-test-db`, `make-default-tax-
provider`, `schema-summary`). Real surface is per-namespace,
documented in `core.clj:6-22`. Consistency is high: `<verb>!`
for transactors, `<verb>-tx-data` for pure builders, `<noun>`
for reads (`account-balance`, `trial-balance`, `compute-
report`), `resolve-<entity>` for coercers.

**Private-fn discipline.** 104 `defn-` vs 247 `defn` in
`src/kontor/*.clj` — 30% private. Spot-checks confirm the
discipline is real.

**Naming collision.** `kontor.invoice` (kernel) vs
`kontor.invoice.bridge` (module). The one named smell. See
§1.3, §6 item 1.

**Malli / spec coverage.** None. Tx-data shape is documented
in builder docstrings and enforced by throw-sites
(`build-transaction` throws on missing fields,
`acquire-tx-data` throws on missing required opts). Adequate
for a Clojure-only library; Malli would matter mainly for a
JSON-over-HTTP consumer (none exists). Mark as P3 unless a
JSON consumer surfaces.

**Companion modules** uniformly follow kernel conventions.
`kontor.authz` is the documented exception that runs on a
minimal datahike conn without kernel schema (ADR-068
carve-outs).

**Test fixtures.** `create-test-db` is canonical entry;
companions ship `install!` for their own schema. Uniform.

---

## 6. Reshaping plan (prioritized; 6 items)

### Item 1 — Resolve the `kontor.invoice` naming collision  (LOW, 1 day, BLOCKING for clarity)

Rename kernel `src/kontor/invoice.clj` to one of:
  - `src/kontor/document/invoice.clj` (namespace
    `kontor.document.invoice`)
  - `src/kontor/posting/invoice.clj` (namespace
    `kontor.posting.invoice`) — emphasizes the kernel is
    the *posting* layer.

Rationale (§1.3, §5.3): the collision confuses readers and
blocks any future "the kernel `kontor.invoice` namespace is
the canonical one" claim. Companion module's `kontor.invoice.
bridge` stays as is; the kernel side moves out of the way.

**Rollback.** `git revert`. No data migration.

### Item 2 — Add `:entity` filter to read-side (LOW, ~1 day, UNBLOCKS Gap 4)

Add `:entity` opt to:
  - `kontor.balance/account-balance` (`balance.clj:108`)
  - `kontor.trial/trial-balance` (`trial.clj:34`)
  - `kontor.ledger/postings-against` (`ledger.clj:103`)
  - `kontor.report/compute-report` (`report.clj:263`)
  - `kontor.financial-statements/compute-statement`

Implementation: pull `:posting/entity` in `pull-posting`,
build an `entity-filter-pred` mirroring `ledger-filter-pred`
(`report.clj:220-237`), expand the `family` walk via
`entity/family`. One new pred per fn, one new opt key.

Rationale (§4 Gap 1): largest leverage, smallest fix on the
list. Without this kontor's "multi-entity" story is write-only.

**Rollback.** Trivial — the opt is opt-in; default behavior
(opt absent) preserves today's semantics.

### Item 3 — Close the inventory `d/transact` bypasses (LOW, ~1 hour)

Replace the three direct `d/transact` calls in
`modules/inventory/src/kontor/inventory/{count,core}.clj`
(see §2.4) with the `*-tx-data` + `transact-with-validation`
pattern. This is mechanical; no behavior changes.

Rationale (§2.4): closes the "every business `!` routes
through the gate" invariant.

**Rollback.** Trivial.

### Item 4 — Reorganize the kernel into directories (MEDIUM, ~2 days, IMPROVES navigability)

Group the 36 kernel namespaces into 6 sub-directories matching
the §1.1 grouping. Suggested layout:

```
src/kontor/
  posting/           ; balance, trial, ledger, posting, money,
                     ; valuation, costing_provider
  time/              ; bitemporal, period, closing, schedule
  lifecycle/         ; status_machine, state_machine, sealing,
                     ; side_effect
  governance/        ; audit_doc, legal_hold, retention, dsar
  workflow/          ; process, validation,
                     ; payment_application, reconciliation
  flow/              ; aging, bank_account, bank_csv, payment_term,
                     ; invoice (after Item 1)
  provider/          ; tax_provider, einvoice_provider
  report/            ; report, financial_statements
  io/                ; import/beancount (renamed from import_)
  schema/            ; split schema.clj per domain (Item 4b)
  core.clj
  entity.clj
```

Per-namespace `(ns kontor.<group>.<file>)`. Update ~250
`(:require)` lines (sed-clean).

Rationale (§1.1): a flat 36-file directory is the kernel's
biggest navigability cost. The grouping reflects the
conceptual structure that already exists in the documentation
but not on disk.

**Rollback.** `git revert` if the maintainer dislikes the
grouping. The shape is fully mechanical.

**Item 4b — Split `schema.clj`.** Independent sub-step,
defer if Item 4 is enough. Splits the 3.6 kLoC catalog into
~8 per-domain files plus a `schema/all.clj` aggregator. Same
risk profile as Item 4. Sized: 1 day.

### Item 5 — Wire or delete `TaxProvider` (MEDIUM, ~1 week if wire, 1 hour if delete)

Choose. My recommendation (§2.5): wire it. Concrete plan:

  - Replace `StaticTableProvider/resolve-taxes`'s `[]` return
    with a real impl that reads `:tax/*` entities from the
    db (matching country + use + effective-date) and emits
    the additional posting maps per ADR-005.
  - Refactor l10n modules' invoice posting builders to
    invoke `(tp/resolve-taxes provider context)` instead of
    inlining the tax math.
  - Promote `kontor.invoice/send!` (kernel) and
    `kontor.invoice.posting/post-to-ledger!` (module) to
    accept a `:tax-provider` opt, defaulting to a per-
    country lookup.
  - Add a kernel `kontor.tax/apply` helper that bridges
    `posting-builder` output through the provider.

If delete: remove `tax_provider.clj`, the docstring
mention in `core.clj:21`, the bootstrap helper in
`core.clj:121-125`, and the ADR-005 reference list. Doc-update
the architecture.md "Provider protocols" section to remove
TaxProvider.

Rationale (§2.5): unwired protocols are worse than no
protocols. Choose and commit.

**Rollback.** For the "wire" branch: each l10n module's
posting builder stays callable directly; the provider is an
opt that defaults to "do nothing" until wired. For the
"delete" branch: `git revert`.

### Item 6 — `FxRateProvider` protocol + ECB default impl (MEDIUM, ~2 weeks, UNBLOCKS Gap 4 + Gap 6)

Per §4 Gap 2. New namespace `kontor.fx-rate-provider`. Protocol
shape mirrors `TaxProvider` (resolve a rate; resolve a series
of rates for a window). Default impl `EcbReferenceRatesProvider`
reads from a bundleable CSV (ECB publishes a daily ZIP at a
stable URL — license-clean). Adapter scaffolds for `XE`,
`OANDA`, the Fed H.10 series — none with API keys bundled.

Wire into:
  - `kontor.posting/build-transaction` — `:fx-provider` opt
    plus a helper `convert-posting-to-functional-currency` for
    consumers who want auto-conversion to the entity's
    `:entity/functional-currency`.
  - `kontor.report/compute-report` — `:translate-to` opt
    that re-bases per-commodity totals into one presentation
    currency.
  - `kontor.lease.posting/plan-fx-retranslation` — drop the
    "consumer supplies `:gain-loss`" requirement.

Rationale (§3.1 F2, §4 Gap 2). Single biggest substrate
addition for the trans-national pitch.

**Rollback.** Provider is opt-in; default behavior (no
`:fx-provider`) preserves today's "per-commodity-only" reports.

---

**What's NOT on this list.**

  - Splitting `legal_hold` / `retention` / `dsar` / `payment_
    application` into sub-namespaces — defer unless one grows.
  - Migrating `closing/close-fiscal-year!` to `run-process`
    — blocked on the datahike `:db/current-tx` PR (task #75).
  - Malli specs — defer until a JSON consumer surfaces.
  - `kontor-hr` / `kontor-payroll` / `kontor-consolidation` /
    `kontor-tax-provision` — companion-shaped, roadmap stages,
    out of scope for this reshaping plan.

---

## 7. The 6-12 month end-state

Assuming items 1-6 land and Stages R (HR/payroll), the next
asset/inventory follow-ups, and a `kontor-consolidation`
companion follow, here is what kontor looks like by mid-2027:

### 7.1 Module organization

```
kontor/
  src/kontor/
    posting/            ; the double-entry math
    time/               ; periods + bitemporal helpers
    lifecycle/          ; sealing + status + state machines
    governance/         ; audit-doc + legal-hold + retention + dsar
    workflow/           ; process + validation + payments + recon
    flow/               ; the AR/AP money-flow primitives
    provider/           ; tax + fx + einvoice + costing
    report/             ; report + financial-statements
    io/                 ; beancount + CSV
    schema/             ; per-domain schema files + all.clj
    core.clj
    entity.clj

  modules/
    partner, sales, invoice, procurement, collections, expense,
    inventory, lease, asset, hr,
    consolidation,         ; NEW: intercompany + currency translation
    tax-provision,         ; NEW: ASC 740 / Pillar 2
    treasury,              ; NEW: cash position + (later) hedge
    project,               ; NEW: roadmap Stage P
    l10n-{de,fr,ca,…},
    bank-{de,fr,ca,…},
    payroll-{de-datev, …}, ; NEW per-country payroll adapters
    einvoice-{de, jp-peppol, in-irn, br-nfe, …},
    authz
```

`provider/` exists today as flat files; the new directory makes
the "this is a pluggable seam" claim physically visible.
`schema/` becomes an internal aggregator of per-domain catalog
files. Everything else is the addition of 4 named companions
(consolidation, tax-provision, treasury, project) plus
fleshed-out HR + payroll.

### 7.2 Composition patterns

The dominant pattern becomes: a *consumer* (or a higher-level
companion) assembles `kontor.process` steps from N module-
provided `*-tx-data` builders, then commits with one
`run-process`. The §3.1 intercompany scenario becomes:

```clojure
(process/run-process
 conn
 {:steps [;; Step 1: GmbH-side invoice + posting
          (fn [db _] (invoice/create-tx-data db gmbh-invoice))
          (fn [db _] (invoice/send-tx-data db {…}))
          ;; Step 2: USA-side mirror entry, FX-translated
          (fn [db _] (consolidation/intercompany-mirror-tx-data
                       db {:source-tx "tx-1"
                           :target-entity acme-usa
                           :fx-provider ecb}))
          ;; Step 3: link the pair via :intercompany/pair
          (fn [db ctx] (consolidation/pair-tx-data
                        db {:tx-a (get-in ctx [:tempids "tx-1"])
                            :tx-b (get-in ctx [:tempids "tx-2"])}))]
  :vt-from invoice-date})
```

One gated commit. All four sub-effects atomic. Reverse with one
`reverse-process!` invocation.

### 7.3 Public API

`kontor.core` stays thin — `install-schema!`, `create-test-db`,
the provider registration helpers. The per-concern public APIs
become physically grouped:

```clojure
(require '[kontor.posting :as posting])         ; build-transaction, post-transaction!, plan-stock-move
(require '[kontor.report :as report])           ; compute-report
(require '[kontor.workflow.process :as process]) ; run-process
(require '[kontor.governance.legal-hold :as lh]) ; place!, release!
(require '[kontor.consolidation :as cons])      ; new
(require '[kontor.provider.fx :as fx])          ; new
(require '[kontor.provider.tax :as tax])        ; wired
```

Companion namespaces follow the same `<module>.<concept>`
pattern.

### 7.4 New consumer / new sector onboarding

**New consumer (30-min path):** `(k/create-test-db)` →
install needed module schemas → install per-country chart →
register providers (`tax/register!`, `fx/register!`) → build
a `run-process` composing N `*-tx-data` builders → read via
`compute-report` with `:entity` + `:translate-to` opts for
consolidated FX-translated output. No UI, no tx-fn surgery,
no SQL.

**New sector module (the pattern 26 existing modules already
follow):** ship a `schema.clj` with prefix-namespaced attrs
and an `install!` (including status-transition seeds per
ADR-034); ship `core.clj` with `*-tx-data` + `!` wrapper
pairs; if the module posts to the GL, add `posting.clj` whose
output conforms to `kontor.posting/build-transaction`. Tests
use `(create-test-db)` + your `install!`. Read ADR-067 +
ADR-068 first.

### 7.6 New trans-national primitives that don't exist today

The end-state introduces three new kernel-or-companion
primitives:

  - **`kontor.consolidation/eliminate!`** — intercompany
    elimination on the elimination entity.
  - **`kontor.consolidation/translate-currency!`** —
    per-entity functional-to-presentation translation per
    IAS 21 / ASC 830.
  - **`kontor.consolidation/consolidate!`** — orchestrator
    walking `entity/family`, running translation, running
    elimination, posting to the consolidation entity. One
    `run-process`.

Plus the `FxRateProvider` protocol from Item 6 (provider, not
companion).

Plus, with HR + payroll: per-jurisdiction payroll provider
protocols (`PayrollProvider`), the `:person` and `:employment`
entities, the `:payroll-period` cycle aligned to
`kontor.period`.

---

## 8. Open questions for the maintainer

  1. **`kontor.invoice` rename target.** Item 1: prefer
     `kontor.document.invoice` (semantic: it's a document
     type) or `kontor.posting.invoice` (semantic: it's the
     posting-side of an invoice)? I lean `kontor.document.invoice`
     because the kernel-side covers the *document* lifecycle
     (status flips, audit doc, payment-flip-on-settlement) and
     the *posting* is one step within it.

  2. **TaxProvider — wire or delete?** Item 5. Strong recommend
     wire. Want to confirm before any work.

  3. **Directory reorg appetite.** Item 4 touches every
     `(:require)` line. Mechanical but a noisy diff. Should
     I sequence it before or after Item 6 (FxRateProvider) so
     the new code lands in the new layout? My instinct: do
     Item 4 first, ship it as one giant rename PR, then
     subsequent work goes into the new layout.

  4. **`FxRateProvider` license posture.** The ECB
     reference-rates ZIP is published under the ECB's
     "freely usable for any purpose provided source is
     acknowledged" clause — compatible with EPL bundling.
     Confirming that's the assumption.

  5. **HR / payroll stage timing.** Gap 3 ranks P1 for trans-
     national pitch. The roadmap lists it as Stage R (last
     companion). If trans-national framing is the *current*
     thesis, should Stage R promote ahead of Stages N
     (revrec) / O (subscription) / Q (Peppol)?

  6. **Schema split (Item 4b).** Whether to fold this into
     the Item 4 directory reorg or keep separate. The latter
     reduces blast radius but means two big PRs.

---

## Appendix: key file-line citations

Findings backed by specific code:

- `kontor.process/run-process`: `src/kontor/process.clj:110-138`
- `kontor.bitemporal/with-vt` post-port:
  `src/kontor/bitemporal.clj:47-66`
- `kontor.posting/build-transaction` + tx-meta carve-out:
  `src/kontor/posting.clj:299-373` (`:371-373`)
- `kontor.posting/balance-by-entity-ledger-and-commodity`:
  `src/kontor/posting.clj:184-196`
- Read-side `:entity` gap:
  `src/kontor/balance.clj:90-119`, `trial.clj:25-50`,
  `report.clj:239-286` (ledger pred at `:220-237`)
- `kontor.validation/validate-and-apply` validator chain:
  `src/kontor/validation.clj:167-188`
- `kontor.entity/family` for descendant walk:
  `src/kontor/entity.clj:94-98`
- Invoice naming collision: `modules/invoice/src/kontor/invoice/bridge.clj:1-23`
- TaxProvider stub: `src/kontor/tax_provider.clj:75-92`
- Inventory bypasses:
  `modules/inventory/src/kontor/inventory/count.clj:51,85`,
  `modules/inventory/src/kontor/inventory/core.clj:316`
- run-process adoption examples:
  `src/kontor/payment_application.clj:536`,
  `modules/lease/src/kontor/lease/runner.clj:297,554,698,750`,
  `modules/asset/src/kontor/asset/runner.clj:169,208`,
  `modules/inventory/src/kontor/inventory/ops.clj:389,617`
- Period close-tx-data + `:period/lock-tx` carve-out:
  `src/kontor/period.clj:318-373`
- FX placeholder pattern:
  `modules/lease/src/kontor/lease/posting.clj:170-191`
- `:entity/kind` schema: `src/kontor/schema.clj:3013-3017`
- ADR-067 (process): `doc/decisions.md:7146-7302`
- ADR-068 (universal builders): `doc/decisions.md:7304-7445`
- Conventions: `doc/conventions.md:13-69`
- Bitemporal port plan: `doc/research/68-bitemporal-port-and-stratum-plan.md:31-39`

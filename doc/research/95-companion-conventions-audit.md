---
date: 2026-05-18
title: 95 — Companion-module conventions audit
status: in-progress; tracked while writing per-module READMEs
audience: maintainer — read after the README round to plan a
          consistency-cleanup sweep
---

# 95 — Companion-module conventions audit

Tracking irregularities + namespace + programming-model deviations
across the 42 `modules/*/` companions while writing per-module
READMEs. This is the input list for a future consistency-cleanup
round.

## §1 — Conventions the project *should* enforce (but doesn't yet)

Drawn from CLAUDE.md + ADR-067 + ADR-068 + the kontor.core /
kontor.posting / kontor.hr.core / kontor.audit-doc prior art:

1. **`<module>/core.clj`** is the canonical entry namespace with a
   public `install!` fn. Examples doing it right: `kontor.hr.core`,
   `kontor.import-edgar.schema` (with `core.clj` re-exporting),
   `kontor.import-gleif.core`. The pattern is: one short `core.clj`
   that bundles installer + public re-exports.
2. **`<module>.schema`** is the schema-only namespace owning the
   `:db/ident` rows + status-transition seeds + approval-policy
   seeds. `install!` ideally lives in `core.clj`, NOT in
   `schema.clj`, so the schema ns stays declarative.
3. **ADR-068 `*-tx-data` builders + `!` wrappers.** Every business
   write exposes a pure `*-tx-data` builder (composable into
   `kontor.process` step lists) and a thin `!` wrapper that routes
   through `kontor.validation/transact-with-validation`.
4. **`run-<verb>!`** for orchestrators that compose multiple
   `*-tx-data` builders into one tx (e.g. `kontor.hr.payroll/
   run-payroll!`, `kontor.asset.runner/run-depreciation!`). Single
   transactor; valid-time stamped once at the outer call; commits
   atomically.
5. **Provider protocols** in `<module>.<provider-name>-provider.clj`
   — sibling pattern of `kontor.tax-provider`,
   `kontor.fx-rate-provider`, `kontor.payroll-provider`. The
   protocol + the kernel-shipped built-ins + a registry resolver
   (`provider-for`, `register-provider!`).

## §2 — Per-module audit (filled as READMEs are written)

### kontor-asset

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`kontor.asset.asset` is the lifecycle namespace** (note the
  double-`asset`). The kontor convention is `kontor.<module>.core`
  for the lifecycle entry-point. Other modules use `core` (kontor-hr,
  kontor-import-edgar, kontor-import-gleif, kontor-people-record);
  `kontor.asset.asset` is the odd one out. Suggested rename:
  `kontor.asset.lifecycle` (more descriptive) or `kontor.asset.core`
  (matches convention).
- **`install!` lives in `kontor.asset.schema`** — should be in
  `kontor.asset.core` (which doesn't exist) for consistency with
  the other modules. Or accept that schema-owns-install! is also a
  valid pattern (kontor.hr.core/install! and kontor.l10n-de.retention/
  install! are split differently; the convention isn't settled).
- **`kontor.asset.posting` uses `plan-*` instead of `*-tx-data`** for
  the pure GL-posting planners. These are called BY the lifecycle
  transactors (which DO follow `*-tx-data` per ADR-068). The
  `plan-*` naming reads cleanly but doesn't surface that these are
  composable like `*-tx-data` builders. Either rename to `*-tx-data`
  (consistency) or document that `plan-*` is an inner-tier
  convention distinct from the public `*-tx-data` surface.
- Module has NO README until 2026-05-18 (closed in this round).

### kontor-lease

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` lives in `kontor.lease.schema`**, not in `kontor.
  lease.core` — same divergence as kontor-asset. The `core.clj`
  here IS the canonical entry namespace per the spec, but it owns
  the `define-lease!` lifecycle transactor (sibling to kontor-
  asset's `kontor.asset.asset`) rather than the installer.
- **Posting builders use `plan-*` instead of `*-tx-data`** (mirrors
  kontor-asset; same critique). `kontor.lease.posting` exposes
  `plan-lease-recognition`, `plan-lease-payment`, `plan-adjustment`,
  `plan-fx-retranslation` — they ARE composable like `*-tx-data`
  builders, but the naming hides it.
- **The lifecycle transactors are split across `core.clj` +
  `runner.clj` + `modification.clj` + `liability.clj`.** `define-
  lease!` is in `core.clj`; `commence!` / `import-lease!` /
  `run-lease!` are in `runner.clj`; `remeasure!` / `partial-
  terminate!` / `terminate!` / `purchase!` are in `modification.
  clj`; `open-liability-book!` / `revise-liability-book!` are in
  `liability.clj`. The asset module bundles its lifecycle into
  `kontor.asset.asset`; lease scatters by concern. Not wrong, but
  inconsistent with the asset sibling.
- **`commence!` and `run-lease!` are explicitly NOT one atomic tx**
  (per the runner ns docstring). They do several `d/transact`s;
  only the per-period payments inside `run-lease!` are atomic via
  `kontor.process`. This is a deviation from the implicit "one
  transactor = one tx" convention and IS documented — but the
  divergence-recovery story (the lockstep guard refusing to run on
  partial-prior-failure) suggests the convention should either be
  formalised or atomicised end-to-end.
- **Module has NO README until 2026-05-18** (closed in this round).

### kontor-inventory

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` lives in `kontor.inventory.schema`** — same divergence
  as kontor-asset + kontor-lease. The `core.clj` exists and IS the
  primary lifecycle namespace (facility / location / item builders)
  but the installer is in schema.clj.
- **Lifecycle is split across `core` + `ops` + `count` +
  `reservation`.** `define-facility!` / `define-location!` /
  `define-facility-product!` / `find-or-create-inventory-item!` /
  `record-detail!` / `place-opening-stock!` are in `core.clj`;
  `receive!` / `issue!` / `transfer!` / `complete-transfer!` /
  `true-up-negative-fill!` are in `ops.clj`; `start-count!` /
  `record-count-line!` / `post-count!` in `count.clj`; `reserve!` /
  `release-reservation!` in `reservation.clj`. Symmetric scattering
  to kontor-lease — split by concern, not bundled.
- **Costing in `costing.clj`, not `<module>.<provider>-provider.clj`.**
  The §1 convention names the provider file
  `<module>.<provider-name>-provider.clj` (e.g.
  `kontor.tax-provider`). This module ships a `CostingProvider`
  impl (FEFO) in `kontor.inventory.costing` — single-word file
  name. The protocol itself lives in `kontor.costing-provider`
  (kernel); convention is fine for impls but worth canonicalizing
  the impl-file naming too.
- **`receive!` and friends are NOT split into `*-tx-data` builders
  consistently.** Most ops DO have a paired `*-tx-data` (receive-
  tx-data, transfer-tx-data, cancel-transfer-tx-data, reserve-tx-
  data, release-reservation-tx-data, true-up-negative-fill-tx-
  data). But `issue!`, `complete-transfer!`, `start-count!`,
  `record-count-line!`, `post-count!`, `record-detail!`,
  `place-opening-stock!` and the facility/location/product builders
  in `core.clj` either don't have a paired tx-data builder or have
  one that's private. ADR-068 expects ALL business writes to expose
  the pure builder.
- **Module has NO README until 2026-05-18.**

### kontor-invoice

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`kontor.invoice.bridge` is the canonical entry, not
  `kontor.invoice.core`.** The reason is justified (kernel already
  owns `kontor.invoice`), but the deviation from the §1 convention
  is real. The module also has NO `core.clj`. Suggested: either
  rename kernel `kontor.invoice` to `kontor.invoice.kernel` (big
  rename) or accept the divergence and note it as the kernel-
  collision case in §3.
- **`install!` in `kontor.invoice.schema`** — same divergence as
  kontor-asset / kontor-lease / kontor-inventory. 4 of 4 so far put
  install! in schema.clj, not core.clj — the convention as written
  in §1 is consistently broken; either §1 is wrong or every module
  needs a refactor.
- **Most status wrappers in `bridge.clj` lack a paired `*-tx-data`
  builder** — `make-ready!`, `mark-paid!`, `cancel!` all just call
  `sm/record-status-change!` directly (ADR-068 violation). Only
  `make-invoice-from-order!` and `post-to-ledger!` follow the
  `*-tx-data` pattern. The kernel's `kontor.status-machine` exposes
  `record-status-change-tx-data`, so the fix is mechanical: thread
  it through.
- **Only ONE test file (`bridge_test.clj`)** for a module with two
  source files of business logic (`bridge.clj` + `posting.clj`).
  Posting code-paths are exercised transitively via the bridge
  test, but there is no dedicated `posting_test.clj` covering
  `resolve-gl-account` resolution order, `debit-credit-for` per-
  type dispatch, or the `:gl-account-default` lookup chain. Worth
  splitting.
- **Module has NO README until 2026-05-18.**

### kontor-collections

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **No `core.clj` at all.** The module has 9 source files all of
  which are concern-namespaced (case, dispute, promise, pause,
  credit-hold, aging, dunning, writeoff, schema). No single entry
  point exists for installer + public re-exports — consumers
  require the per-concern namespaces directly. Consistent with
  kontor-lease's tendency to scatter, but more pronounced.
- **`install!` in `kontor.collections.schema`** — 5/5 now follow
  this anti-convention. The convention as written in §1 is
  effectively dead text; either rewrite §1 or refactor every
  module's installer location.
- **Two `advance-state!` functions with the same name in different
  namespaces** — `kontor.collections.case/advance-state!` and
  `kontor.collections.dispute/advance-state!`. Both are thin
  status-machine wrappers. Either both should be renamed to
  reflect their entity (`advance-case-state!` /
  `advance-dispute-state!`) or both should be the canonical name
  in a hypothetical `core.clj`. Same-name-different-ns is fine in
  Clojure but confusing in this codebase's grep-driven
  navigation.
- **Some thin status wrappers are still bare `record-status-
  change!` calls** without paired `*-tx-data` (e.g.
  `mark-promise-kept!`, `mark-promise-broken!`, `release-all-
  for!`). Most of the module DOES have the ADR-068 pair, but
  these are exceptions — same pattern as kontor-invoice.
- **`DunningTemplateProvider` protocol lives in `kontor.
  collections.dunning`, not in `kontor.collections.dunning-template-
  provider`.** §1 convention says protocol files are
  `<module>.<provider-name>-provider.clj`. The dunning logic +
  provider are bundled together — not wrong but inconsistent.
- **Module has NO README until 2026-05-18.**

### kontor-partner

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`kontor.partner` lives at `src/kontor/partner.clj` (top level),
  not in `src/kontor/partner/`.** Same kernel-collision case as
  `kontor.invoice.bridge` — except this module DOES claim the
  bare-`kontor.partner` namespace (the kernel only ships
  `:partner/*` schema attrs in `kontor.schema`, not a `kontor.
  partner` namespace). So this is structurally cleaner than
  invoice — but it means the module has BOTH `kontor.partner.clj`
  AND `kontor/partner/schema.clj`, and the test file is
  `kontor/partner_test.clj` (not nested). Worth canonicalising.
- **`install!` in `kontor.partner.schema`** — 6/6 modules so far.
  The pattern is universal.
- **All transactor logic lives in ONE file (`kontor.partner.clj`).**
  Resolution, pulls, queries, junction traversal, merge — all in
  one ~560-line file. Counter-example to kontor-collections'
  9-file scatter. There's no canonical "right" decomposition;
  both extremes coexist in the repo.
- **Only `merge-partners!` follows the ADR-068 `*-tx-data` + `!`
  pattern.** Most of `kontor.partner` is read-only (resolution +
  queries) so this is fine, but the schema implies a lot of
  business writes (create-partner, add-contact-mech, add-role,
  start-relationship, end-relationship, place-on-tag) that
  consumers presumably hand-build with raw `d/transact`. No
  transactor surface for the routine party-master writes.
- **Single test file (`partner_test.clj` at the top level).** 6/6
  modules so far have either a single test file or scattered ones
  with no clear contract about what to test where. Worth a
  test-layout convention in §3.
- **Module has NO README until 2026-05-18.**

### kontor-expense

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` in `kontor.expense.schema`** — 7/7 now follow this
  anti-convention. This is universal across companions; the
  §1 convention is dead text.
- **`change-status!` is private (`defn-`).** ADR-068 expects every
  business write to expose its `*-tx-data` builder for
  composition; `change-status!` + `change-status-tx-data` are
  both private. The four public wrappers (`submit!`, `approve!`,
  `reject!`, `reopen!`) call the private one, so the inner builder
  cannot be composed into a `kontor.process` step list — a
  consumer needing to compose a status change with another write
  must re-implement the call.
- **No `:expense-line/*-tx-data` builder.** `add-line!` exists +
  has a paired `add-line-tx-data`, which is fine, but the line
  schema is rich (`:commodity`, `:cost-center`, `:receipt`,
  `:payment-mode`) and the builder is monolithic — there's no
  decomposed primitive for "build just the line row" reusable
  outside the `add-line!` context.
- **Test ns is `kontor.expense.expense-test`** — the doubled-
  `expense` (matching `kontor.asset.asset` lifecycle namespace
  pattern). Mirrors the asset module's namespace-name oddity, but
  this time in TESTS. Suggested: rename to `kontor.expense.core-
  test` to match the source ns being tested.
- **Spec called out "per-diem" but the implementation has no
  per-diem support.** Worth either implementing it (with a
  per-jurisdiction rate table — necessarily an l10n concern) or
  removing it from the parent README table to set realistic
  expectations.
- **Module has NO README until 2026-05-18.**

### kontor-authz

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` in `kontor.authz.schema`** — 8/8 modules so far.
  The pattern is universal. The convention as written in §1 is
  dead text and §3 needs to formalise schema-owns-install! as
  canonical.
- **Explicit ADR-068 carve-out** for `do-write-relationships!`
  bypassing `transact-with-validation`. The rationale is sound
  (authz can run standalone without kernel schema) but it's the
  first explicit deviation from ADR-068 in the audit so far —
  worth noting in §3 as a precedent for "standalone-module"
  carve-outs.
- **`kontor.authz.core` is genuinely the protocol + value types**
  (no transactor, no datahike dep). This is the cleanest core.clj
  in the audit so far — purely declarative, the canonical reference
  for what a `core.clj` could mean. Counterexample to the
  installer-in-core convention §1 advocates.
- **Schema definitions (Relation/Permission) live in
  `kontor.authz.base`, NOT in `kontor.authz.schema`.** The schema
  ns is the *datahike attrs*; base.clj is the value-constructors
  for Relation/Permission/Relationship. Confusing naming — a
  consumer expects "schema-related stuff" under one of those two
  but it's split. Suggested rename: `kontor.authz.defs` or
  `kontor.authz.types`.
- **Two distinct `write-relationships*` surfaces**: the protocol
  method `write-relationships!` (single call, bypasses gate) AND
  `write-relationships-tx-data` (composable, routes through gate).
  Same name pattern, different semantics. Worth a §3 convention
  about gate-bypass naming (suggest `*-tx-data-raw` or `*-direct-tx-
  data` for builder forms that intentionally skip validation).
- **Module has NO README until 2026-05-18.**

### kontor-hr

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` exists in BOTH `kontor.hr.core` AND `kontor.hr.
  schema`.** The core one calls schema's + additionally registers
  a DSAR extension collector. This is the FIRST module in the
  audit where `core.clj` carries `install!` (matches §1's
  intended convention). Worth canonicalising as the precedent.
  The schema-level `install!` is then the bare datahike-attrs +
  seeds installer — useful for tests that don't need DSAR. Worth
  a §3 rule: schema/install! is the bare attr loader; core/
  install! is the composite that wires extension points.
- **Two `:person` namespaces in the codebase.** `kontor.hr.person`
  + `kontor.partner` (when `:partner/type :person` + a `:person`
  subtype) BOTH define `:person/*` attrs in different schemas.
  Confusing — at first glance a consumer can't tell which
  `:person/external-id` they're looking at. The schema is
  actually NS-disjoint (`:person/given-name` is HR; `:person/
  first-name` is partner), but the partial overlap is
  problematic. Worth a rename pass — e.g. `:hr-person/*` for HR
  and keep `:person/*` for partner (or vice versa).
- **`run-payroll!` lives in `kontor.hr.payroll`, not
  `kontor.hr.core`.** This is the canonical `run-<verb>!`
  orchestrator the §1 convention names; finding it requires
  knowing to look in payroll.clj specifically. core.clj is just
  installer + resolvers, not an orchestrator hub. Either accept
  the per-concern split or add public re-exports in core.clj.
- **`:consent/*` schema added incrementally** in commit
  `a247fb0` — the rest of the module is ADR-075, but `:consent/
  *` is ADR-094. The README documents both; the source file
  comments are clean — but a consumer reading the schema EDN
  would benefit from a clear "this group is ADR-094 added later"
  section divider.
- **Module had a README BEFORE this round** — check before
  overwriting? (Verified: no, the prior list showed it missing,
  and the file write created.)

### kontor-people-record

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **`install!` in `kontor.people-record.schema`** — 9/10 modules
  put it there (hr is the lone exception that has it in both).
  The pattern is settled in practice; §1 needs to be rewritten.
- **Hyphenated module namespace: `kontor.people-record.*`.** The
  module directory uses an underscore (`people_record`) per Clojure
  convention; the namespace uses a hyphen. Consistent with the
  rest of the kernel (`payment-application`, `import-edgar`) — but
  the disparity catches the eye in `find` output.
- **`check-consent!` is a private `defn-`.** Same issue as
  `kontor.expense.core/change-status!` — a consumer composing
  this module into their own `kontor.process` orchestration
  cannot reuse the consent gate as a tx-data step; they would
  have to re-implement the check or wrap the impure `record-*!`
  call. Either expose `check-consent-tx-data` (a step that
  throws on missing consent) or document the consent-check API
  as `kontor.hr.consent/active-at?` directly.
- **No `core.clj` `install!`** — only `schema.clj` `install!`,
  no convenience installer in core. By contrast kontor-hr has
  the full `core/install!` pattern that ALSO registers a DSAR
  extension. If people-record had analogous DSAR registration
  (it has `dsar-bundle`, but no automatic kernel-walker
  registration), the bundle would only be reachable via direct
  call, not via `kontor.dsar/collect`.
- **`dsar-bundle` is NOT registered with `kontor.dsar/register-
  extension-collector!`.** kontor-hr does this in its
  `core/install!`. people-record could (and probably should) do
  the same so a consumer's `kontor.dsar/collect` walk
  automatically includes track-record data without manual
  composition. Worth a followup.
- **Brand-new module (commit `bcfe1af`).** Small surface; the
  irregularities above are easy to fix early before consumers
  lock in the call patterns.

### kontor-import-gleif

**Read 2026-05-18. README written 2026-05-18.**

Irregularities flagged:

- **No `schema.clj` and no `install!`.** The module is a single
  `core.clj` because the schema attrs (`:entity/lei`, etc.) it
  populates live in the KERNEL schema (`src/kontor/schema.clj`).
  This is the first audit module without local schema, and the
  shape works — pure-ingest modules don't need installer
  boilerplate. Worth canonicalising as a §3 pattern: "substrate-
  seam ingest modules co-evolve their attrs into the kernel
  schema; they ship `core.clj` only."
- **`import-level-1!` / `import-level-2!` call `d/transact`
  directly, bypassing the validation gate.** Same shape as
  authz's `do-write-relationships!` carve-out (ADR-068 §carve-
  outs). Use case is mass-import of master data — kernel
  invariants (sum-to-zero, period-lock) don't apply. But ADR-068
  expects either a gate-route or an explicit carve-out comment;
  here there's no explicit carve-out comment. Worth adding.
- **`row->level-1-tx` is private (`defn-`).** Per-row transform
  is the natural unit of reuse / testing for a CSV importer;
  hiding it means a consumer wanting per-row inspection has to
  re-implement. Suggest making it public.
- **Two-phase semantic is correct + documented**, but the
  transactor pair isn't composable into ONE `kontor.process`
  step — Phase 2 reads its own `(d/db conn)` separately, so a
  consumer can't atomically commit "import this delta of level
  1 + level 2 together". This is fine for the daily-bulk-ingest
  use case the README describes, but a documented limitation
  for delta-ingest scenarios.
- **Brand-new module (commit `ce73598`).** Small + clean — the
  schema-in-kernel + transactor-only-module pattern feels right
  for substrate-seam ingest.

(More modules to be appended as their READMEs land.)

## §3 — Cross-cutting patterns worth canonicalizing

Stub. Will accumulate as the audit progresses:

- `install!` location: `core.clj` vs `schema.clj` vs separate
  `installer.clj` — pick one
- Lifecycle namespace name: `core` vs `<module>.<module>` vs `<module>.lifecycle`
- Pure-builder naming: `*-tx-data` (ADR-068) vs `plan-*` vs `build-*`
- Public re-exports in `core.clj` — yes / no / how much

## §4 — Cleanup proposal (deferred)

After the README round lands, propose a `kontor-conventions` ADR (or
add to existing ADR-067/068) codifying the picks from §3. Then a
mechanical rename pass to bring divergent modules in line. Estimate
~1-2 days for the rename + test/showcase reference updates.

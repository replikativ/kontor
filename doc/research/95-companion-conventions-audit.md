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

# Modules

Per-country / per-format extensions to the kernel. Each module lives in its own subdirectory with conventional `src/`, `test/`, `resources/`, `doc/`. All paths are merged into the root `deps.edn`'s `:paths` so a single REPL / single `bb ci` sees the entire tree — see the deps.edn comment for the rationale.

## Current modules

| Module | Purpose | Status | License |
|---|---|---|---|
| `bank-de/` | German bank-statement CSV importers (DKB, ING, Sparkasse, Postbank, Commerzbank, …). Lifted from openclaw `beleg/bank.clj`. | Phase 2-DE WIP | EPL-1.0 |
| `l10n-de/` | German chart of accounts (SKR03 / SKR04), VAT codes / tags, Umsatzsteuer-Voranmeldung definition, DATEV EXTF export. Sourced facts may carry GPLv3 (Tryton SKR templates). | Phase 2-DE WIP | (per source — likely GPLv3 for the data) |
| `einvoice-de/` | Factur-X / XRechnung wrapper around Mustang. | Phase 2-DE planned | EPL-1.0 (depends on Mustang APL2) |

## Contributing a module

1. Create `modules/<name>/{src,test,resources,doc}/`.
2. Add the new paths to root `deps.edn` `:paths` and the test paths in `:test`/`:dev` aliases.
3. Add it to `tests.edn` `:test-paths`.
4. Add a row to the table above.
5. Document the module's license — the kernel stays EPL-1.0; per-country data modules may inherit a different license from their data source. ADR-006.

## Why monorepo (for now)

Per ADR-006 the long-term plan is one artifact per module. For the early-iteration phase a monorepo is much faster:
- one nREPL sees the whole tree
- one `bb ci` runs the full battery
- atomic commits across kernel + module
- no version-bumping ceremony when changing a kernel API a module depends on

We split when (a) external consumers exist, (b) module licenses diverge in incompatible ways, or (c) a module reaches independent release cadence.

# Modules

Per-country / per-format extensions to the kernel. Each module lives in its own subdirectory with conventional `src/`, `test/`, `resources/`, `doc/`. All paths are merged into the root `deps.edn`'s `:paths` so a single REPL / single `clojure -M:test` sees the entire tree — see the deps.edn comment for the rationale.

## Licensing

All currently-shipped modules are Apache 2.0. Each chart of accounts
is independently keyed from public government / regulatory sources
(SKR04 from DATEV documentation, PCG from the French government's
Plan Comptable Général, SAT Código Agrupador, ATO chart skeleton,
etc.). Bank-CSV test fixtures under `modules/bank-*/test/resources/`
are either synthesized from documented format specs or sourced from
permissively-licensed third-party Beancount importers; per-file
provenance lives in each `bank-*/test/resources/SOURCES.md`.

## Current modules

(table is intentionally trimmed during the v0.1.0-alpha cleanup;
each module's own `README.md` is the source of truth.)

## Contributing a module

1. Create `modules/<name>/{src,test,resources,doc}/`.
2. Add the new paths to root `deps.edn` `:paths` and the test paths in `:test`/`:dev` aliases.
3. Add it to `tests.edn` `:test-paths`.
4. Add a row to the table above.
5. Document the module's license — the kernel stays Apache 2.0; per-country data modules may inherit a different license from their data source. ADR-006.

## Why monorepo (for now)

Per ADR-006 the long-term plan is one artifact per module. For the early-iteration phase a monorepo is much faster:
- one nREPL sees the whole tree
- one `clojure -M:test` runs the full battery
- atomic commits across kernel + module
- no version-bumping ceremony when changing a kernel API a module depends on

We split when (a) external consumers exist, (b) module licenses diverge in incompatible ways, or (c) a module reaches independent release cadence.

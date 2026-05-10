# CLAUDE.md

Guidance for Claude Code and other AI assistants working in this repository.

## Project in one paragraph

`datahike-accounting` is a double-entry accounting **kernel** built on [datahike](https://github.com/replikativ/datahike). It supplies the schema, posting/balance/period semantics, tax engine, sealing/audit story, and bitemporal queries that an accounting workload needs. **It does not ship a UI, an ERP, or country-specific data**; consumer apps (beleg, simmis) and per-country localization modules (`datahike-accounting-l10n-*`) compose on top.

The library is **EPL-1.0**, **Clojure-only**, **single dependency** (datahike). Read [doc/decisions.md](doc/decisions.md) before any non-trivial change — every locked design choice has an ADR there.

## Where to start

1. [doc/decisions.md](doc/decisions.md) — every architectural choice with rationale (ADR-001 .. ADR-010)
2. [doc/architecture.md](doc/architecture.md) — the layer cake, namespace map, kernel module list
3. [doc/roadmap.md](doc/roadmap.md) — phased plan with acceptance criteria per phase
4. [doc/research/00-index.md](doc/research/00-index.md) — point-in-time research that informed the decisions
5. [src/datahike_accounting/schema.clj](src/datahike_accounting/schema.clj) — kernel schema (the source of truth for entities and attributes)

## How to work in this repo

### Iteration loop

This project is being built test-first. The canonical loop:

1. Pick a slice from `doc/roadmap.md` (e.g., "trial balance bitemporal").
2. Write the failing test in `test/datahike_accounting/<slice>_test.clj`.
3. Implement in `src/datahike_accounting/<slice>.clj`.
4. `bb test` (uses kaocha).
5. Update `doc/roadmap.md` checkbox; if the slice produced a new design choice, add an ADR to `doc/decisions.md` and reference it from the code.

### Use the REPL for the inner loop

`bb nrepl` (or `clojure -M:dev -m nrepl.cmdline --middleware '[cider.nrepl/cider-middleware]'`) boots a Clojure REPL with test sources on the classpath and nREPL listening. The user's `clj-nrepl-eval -p <port>` is the canonical way to talk to it from a shell.

**The loop**:

```bash
clj-nrepl-eval --discover-ports                     # find the running port
clj-nrepl-eval -p 43781 "(require 'datahike-accounting.posting :reload)"
clj-nrepl-eval -p 43781 "(require 'datahike-accounting.posting-test :reload) \
                          (clojure.test/test-vars [#'datahike-accounting.posting-test/end-to-end-balanced-tx-transacts])"
```

≈ 200ms per cycle vs bb's full-JVM ~10s. Use this for every iteration; reserve `bb ci` for the final pre-commit check.

The schema fixture `(datahike-accounting.core/create-test-db)` returns a fresh in-memory connection in ~50ms. Use it freely from the REPL to poke at queries.

For schema-shape questions ("what should `:posting/foo` look like?"), the running pg-datahike + Odoo install on `:15433` is a useful reference oracle — see `../pg-datahike/test/integration/odoo/README.md`. We do not lift Odoo's schema verbatim; we use it to cross-check that we haven't missed a real-world concern.

### When you make a non-trivial decision

Add an ADR to `doc/decisions.md`. Number it sequentially. Link to it from any code comment that depends on it. The ADR documents *why*, not *what* — the code documents *what*.

### When you finish a roadmap slice

Tick the checkbox in `doc/roadmap.md` AND make sure the per-phase acceptance criterion is genuinely met. Don't tick prematurely.

## Conventions

### Namespacing

Every datahike attribute namespaces under one of: `:account/* :journal/* :transaction/* :posting/* :commodity/* :lot/* :tax/* :tax-rep/* :tax-group/* :account-tag/* :partner/* :fiscal-position/* :period/* :balance-assertion/*`. New namespaces require an ADR.

This convention is what lets datahike-accounting cohabit with beleg in one DB (ADR-002).

### Money

Always `BigDecimal` + commodity tag. Never doubles. The `Money` type in `src/datahike_accounting/money.clj` is the canonical representation. Rounding is HALF-EVEN unless a regulator mandates otherwise (some VAT jurisdictions require HALF-UP — those are documented case-by-case in l10n modules).

### Bitemporal

Every query that reads accounting data takes `:as-of-tx` and `:as-of-valid` parameters, defaulting to `now`/`now`. New query helpers must make this explicit, not implicit. ADR-008.

### Sealing

A posting transitions from "draft" to "posted" by setting `:posting/posted-at`. After that, *silent* retraction is forbidden by the middleware in `sealing.clj`. **Explicit `:db/purge` IS allowed** but is itself a recorded commit; the audit story is "the chain documents the purge", not "deletion is impossible". ADR-007.

### Tax

The `TaxProvider` protocol is the only abstraction the kernel uses to compute taxes. Per-country tax data lives in `datahike-accounting-l10n-<cc>` artifacts; the kernel ships `StaticTableProvider` as a default impl. Avalara/TaxJar adapters scaffold but do not bundle API keys. ADR-005.

## What NOT to do

- **Do not translate Odoo Python.** FSF treats translation as derivative work; LGPLv3 follows. Reference Odoo's source for *patterns*; write our own implementation. ADR-001 + research note 01.
- **Do not lift Tryton code.** GPLv3 contagion; bad for our consumers. Tryton is a reference design, not a code source.
- **Do not bundle Avalara/TaxJar/TaxCloud API keys or rate data.** Every customer holds their own. ToS-restricted. ADR-005, research note 03.
- **Do not add a UI.** Not even "just a small one." UI lives in consumers (beleg HTMX, simmis Replicant). ADR-010.
- **Do not introduce a second runtime** (no JS, no Python helpers, no shell scripts beyond `bb`). The whole stack is JVM Clojure + datahike.
- **Do not write a US sales tax engine.** We provide the protocol; customers integrate Avalara/TaxJar. ADR-005, ADR-010.
- **Do not silently retract a posted entity.** Use `:db/purge` if the data legitimately needs deletion; the purge becomes its own commit and the audit chain documents it. ADR-007.

## Relationship to nearby projects

- **`../pg-datahike`** — Postgres-wire-protocol shim over datahike. Currently used to validate that real Odoo runs against datahike (see its `test/integration/odoo/`). Useful as a reference oracle for schema decisions, not a runtime dependency.
- **`../beleg`** — contractor invoice management. Will become the *first consumer* of `datahike-accounting`: posting an issued invoice writes both `:invoice/status` and the matching `:transaction` + `:posting`s in one tx. ADR-002.
- **`../simmis`** — distributed-scope ClojureScript app. Long-term consumer for ERP-shaped workloads on top of `datahike-accounting` + `../spindel`'s reactive primitives.
- **`../spindel`** — incremental reactive computation; the simulation/computation engine simmis builds on. Not a direct dep here, but design-aligned (event-sourced, deterministic recomputation).
- **`../odoo`** — Odoo 19 source. Read-only reference oracle. We do not lift its code.

## Useful one-liners

```bash
bb test                # kaocha unit tests
bb nrepl               # Clojure REPL + nREPL on a free port
bb format              # cljfmt check
bb ffix                # cljfmt fix
bb lint                # clj-kondo
bb ci                  # all of the above
```

```clojure
;; In the REPL: open an in-memory accounting DB
(require '[datahike-accounting.core :as a])
(def conn (a/create-test-db))    ; ephemeral, schema loaded
(a/post-transaction! conn ...)
```

## File layout summary

```
LICENSE                     EPL-1.0
deps.edn                    minimal — only datahike
bb.edn                      babashka tasks
tests.edn                   kaocha config
CLAUDE.md                   this file
README.md                   short user-facing summary
doc/
  decisions.md              architectural decisions (ADRs)
  architecture.md           layer cake + module map
  roadmap.md                phased plan with acceptance criteria
  research/                 point-in-time research reports
src/datahike_accounting/    kernel
  schema.clj                schema EDN
  core.clj                  public surface
  money.clj                 Money + arithmetic
  posting.clj               build-transaction, sum-to-zero
  tax_provider.clj          TaxProvider protocol + impls
  tax.clj                   apply-tax to a posting
  sealing.clj               posted-at + middleware
  audit.clj                 commit hash wrapper
  balance.clj               account-balance bitemporal
  ledger.clj                postings-against-account
  trial.clj                 trial-balance
  period.clj                open/close periods
  query.clj                 bitemporal helpers
  import/
    beancount.clj           Beancount parser + dumper
test/datahike_accounting/   tests, mirroring src
```

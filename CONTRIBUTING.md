# Contributing to kontor

Thanks for thinking about contributing. kontor is an Apache 2.0
Clojure library with deliberately small surface area + strong
discipline on what's in scope vs out. This document covers how to
file an issue, how to submit a PR, the ADR convention, and the
schema / write / license discipline the kernel enforces.

If you haven't yet, read [`README.md`](README.md) for the project
shape and [`doc/decisions.md`](doc/decisions.md) for the distilled
architecture decisions you'll be operating inside.

## How to file an issue

We use three issue templates (under `.github/ISSUE_TEMPLATE/`):

- **Bug** — something documented or claimed in tests doesn't work.
  Include the kontor SHA, a minimal failing repro (`(create-test-db)`
  + a few transactions + the assertion that fails), and what you
  expected.
- **Feature request** — something kontor doesn't do that you
  believe it should. Frame as a concrete user story (who is the
  Clojure dev / accountant / l10n author, what are they trying to
  do, why does the current substrate make it hard).
- **Design discussion** — open-ended question about substrate
  shape, a proposed protocol, a jurisdictional gap, etc. These
  often turn into ADRs.

For per-country gaps (a missing tax rule, an outdated rate, a
filing format change), please cite the official source — the
`gesetze-im-internet.de` / `legifrance.fr` / `canada.ca` URL, the
BMF / DGFiP / CRA bulletin, the regulator's published XSD.

## How to submit a PR

1. **Branch from `main`.** kontor doesn't use long-lived release
   branches; main is always green.
2. **Write the failing test first.** kontor is test-first
   (see [`CLAUDE.md`](CLAUDE.md) "Iteration loop"). Even a
   one-liner test driving a new helper counts.
3. **Implement the change.** Use the REPL inner loop (see
   `CLAUDE.md`) for fast iteration — `clj-nrepl-eval` to a long-
   lived nREPL is much faster than `clojure -M:test` per cycle.
4. **Run the pre-push trio** before pushing:
   ```bash
   clojure -M:format && clojure -M:lint && clojure -M:test
   ```
   Each piece individually:
   - `clojure -M:test` — kaocha unit + integration tests.
   - `clojure -M:format` — cljfmt check; `clojure -M:ffix` fixes in place.
   - `clojure -M:lint` — clj-kondo on `src/` + `test/`.
5. **Open the PR** with a clear title (under 70 chars), a
   description that says **why** rather than **what**, and a test
   plan checklist. A template is in
   `.github/PULL_REQUEST_TEMPLATE.md`.

## The ADR convention

Non-trivial design choices get an **Architecture Decision Record**
(ADR). The distilled set of currently-load-bearing ADRs lives in
[`doc/decisions.md`](doc/decisions.md) (~30 topic-grouped entries).

If your ADR is load-bearing for new readers — meaning you'd want them
to internalise it before reading the surrounding code — open a PR
that adds a section to `doc/decisions.md` referencing the ADR number.
The local-maintainer chronological record (alternatives considered,
supersession trail) is a separate maintainer-side artifact; the
distilled public file plus the ADR number is the contract for
external readers.

An ADR records the **why**, not the **what** — the code documents
the what. Reference your ADR by number from any code comment that
depends on it.

If you're not sure whether a change rises to ADR-worthy: if you'd
expect a future contributor to re-litigate the decision without
documentation, it's worth an ADR.

## The `*-tx-data` + `!` discipline (ADR-068)

Every business write in kontor splits into two pieces:

- A **pure builder** (`foo-tx-data`) that returns datahike tx-data
  given a context map. No side effects, no `conn`. Composable into
  `kontor.process` step lists.
- A **`!` wrapper** (`foo!`) that takes a `conn` + opts, calls the
  builder, and routes through `transact-with-validation` so the
  invariant middleware (ADR-011) runs.

If you're adding a new business write — a new verb, a new posting
shape, a new lifecycle transition — both halves are mandatory.
**Do not bypass the validation gate.** This document does not
teach how. The two existing bypass sites
(`kontor-import-gleif`, `kontor-import-edgar`) are bulk-import
companions whose specific reasons are documented at the call site
and reviewed in their READMEs.

Companion modules under `modules/<name>/` follow the same
discipline + ship an `install!` that returns the `conn` for
threading.

## Schema-namespace discipline

Every datahike attribute the kernel owns is prefixed `:kontor.*`
(`:kontor.posting/account`, `:kontor.transaction/journal`,
`:kontor.account/path`, …). The distilled list is in
`doc/decisions.md` §2.2.

Rules:

- **New attributes** in an existing area: add under
  `:kontor.<area>/<attr>` matching the existing pattern. No ADR
  needed.
- **New top-level area**: ADR-002 says you need an ADR. Pick a
  name that won't collide with consumer-app namespaces. Document
  the area in the distilled list if it's load-bearing.
- **Companion modules** own `:kontor.<companion>/*`
  (`:kontor.asset/*`, `:kontor.lease/*`, …). HR person extras live
  under `:kontor.hr.person/*` dotted sub-namespace and ref the
  kernel person eid — that pattern travels for any companion
  extending a kernel entity.

The whole point is **one DB, two schema namespaces** (ADR-002):
kontor's `:kontor.*` and your consumer app's namespaces cohabit
without collision. Don't break that.

## License

Contributions are Apache 2.0 (same as the kernel). Each chart of
accounts is independently keyed from public government / regulatory
sources. If you contribute code from elsewhere, you certify it's
compatibly licensed and your right to contribute it — if you're
not sure, ask first.

## Test discipline

- **REPL-driven inner loop.** Per `CLAUDE.md`, the canonical loop
  is `clj-nrepl-eval -p <port>` to a long-lived `clojure -M:dev`
  nREPL JVM. ~200 ms per cycle vs ~10 s for a fresh
  `clojure -M:test`. Reserve the format/lint/test trio for the
  pre-commit pass.
- **Use the schema fixture.** `(kontor.core/create-test-db)`
  returns a fresh in-memory connection in ~50 ms (it uses
  `d/branch!` over a schema'd template per the test-speed audit;
  see `~/.claude/projects/.../test-speed-audit.md`).
  Don't roll your own.
- **Don't `:reload-all` a long-lived kontor nREPL.** It corrupts
  datahike's core.async. Restart the REPL instead. (User memory:
  `repl-reload-all-gotcha.md`.)
- **Integration tests** live in `test/kontor/integration/`. The
  canonical one is `cross_border_scenario_test.clj` (the README + the
  quickstart both link into it). New cross-cutting scenarios are
  welcome.

## Where to ask

- **GitHub issues** for bugs, features, and design discussion.
- **Per-stage rhythm** (research-before / implement / review-after,
  per ADR-037) is documented in `CLAUDE.md` for substantial new
  stages. Most contributions are smaller and don't need the full
  rhythm, but it's the playbook for major work.

Welcome aboard.

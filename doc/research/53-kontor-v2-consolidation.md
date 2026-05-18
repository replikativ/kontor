---
date: 2026-05-15
agent: general-purpose
status: research-note
topic: V2 consolidation recommendations — naming, README arc, composition, ADR weight, reference comments, cross-cutting cleanup
related: [README.md, CLAUDE.md, doc/decisions.md, src/kontor/posting.clj, src/kontor/process.clj, modules/lease/src/kontor/lease/runner.clj, modules/asset/src/kontor/asset/asset.clj]
---

# 53 — kontor v2 consolidation: a Stage-R proposal

The kernel + companions land at the end of Stage P with 68 ADRs, 36 source namespaces, ~178 distinct `!` transactors, and 939 passing tests. To publish as a Clojure library that reads on its own merits — not as a defended argument — the surface needs to be smoothed in five orthogonal directions and then re-narrated. Concrete recommendations follow.

---

## T1 — Naming standardization

**Verdict: (c) keep domain verbs, but enforce a uniform opts-shape convention + one documented dispatch path.**

The 178 `!` verbs aren't really 178 styles: skimming
`modules/asset/src/kontor/asset/asset.clj:136-544`,
`modules/lease/src/kontor/lease/runner.clj:50-762`,
`modules/lease/src/kontor/lease/modification.clj:264-670`, and
`modules/expense/src/kontor/expense/core.clj:114-489` shows a *de facto* convention is already there: every public verb is `(fn [conn opts-map])`, every verb has a paired `*-tx-data [db opts]` builder (ADR-068), every verb threads `:vt-from`/`:vt-to`/`:changed-by-uid`/`:reason-note`/`:supporting-doc` from the same vocabulary. What's missing is that the convention isn't written down as a single page and isn't lint-enforced.

Option (a) — keep as-is — already wins on accountant readability: `commence!` / `place-in-service!` / `dispose!` are the words on the auditor's checklist. Option (b) — a single `kontor.process/run!` + `{:kind :lease/commence ...}` descriptor — buys discoverability and registry-based dispatch (great for an HTTP/UI front-end), but at the cost of stack-trace legibility, arglist hints, IDE jump-to-definition, and the per-verb docstrings that today carry the regulatory rationale (ASC 842 16.46, IAS 36, ADR-038's :no-self-approval, etc.). Clojure idiom favours named functions over keyword-keyed dispatch maps unless you have a real registry need; kontor doesn't.

Option (c) is the v2 stance: keep the domain verbs, but in `kontor.process` (or a new `kontor.api` doc-namespace) publish the **canonical opts vocabulary** as a single reference, with reserved keys (`:vt-from`/`:vt-to`/`:changed-by-uid`/`:reason-note`/`:supporting-doc`/`:tempid`/`:event-tempid`/`:tempid-suffix`/`:as-of`) documented once. Add a small `clj-kondo` hook (or a `bb lint` rule) that flags new transactors using a non-canonical key. Optionally expose a thin `kontor.process/dispatch` that *also* lets a UI consumer hit verbs by keyword — but as syntactic sugar over the named function, not as the primary surface.

---

## T2 — README primary path

**Verdict: lead with the builder + `transact-with-validation`, demote the wrapper to a "convenience shortcut" footnote.**

Usage proves the maintainer's instinct: `post-transaction!` appears in 5 files
(`src/kontor/posting.clj`, `src/kontor/core.clj` docstring,
`src/kontor/import_/beancount.clj` doctring,
`test/kontor/posting_test.clj`,
`modules/asset/test/kontor/asset/jahresabschluss_test.clj`) — `build-transaction` appears in 40, and the *companion modules never call the wrapper at all*. The actual workhorse is the builder + `validation/transact-with-validation` (the gate), composed via `kontor.process/run-process` (`src/kontor/process.clj:110-138`) — that's the surface every showcase, every lease/asset/lease-modification/expense flow uses.

Community norm check:

- **Beancount** (`bean-doctor` / `bean-query`) leads with file-as-data: `*` directives that are parsed, not posted via a function call. The mental model is "describe → load."
- **hledger** ships a CLI that consumes journal files — the API for an embedder is `add`/`transact` over `Transaction` records, with balance enforced by the type, not a wrapper.
- **Crux/XTDB** docs lead with `submit-tx` taking tx-ops *data* (a vector), with the Java/Clojure helper sugar in a sidebar.
- **Datomic** leads with `(d/transact conn tx-data)` over a literal vector. The "submit a transaction = build a data structure" mental model is the Clojure community norm.

kontor today inverts that norm: the README's worked example calls a side-effecting `!` wrapper before introducing the tx-data form. For a library whose differentiator is "your accounting state is a datalog query target" (`README.md:14-17`), the data-first lead is more consistent. **Recommendation:**

1. Show `(d/transact conn (posting/build-transaction {...}))` first — a balanced draft, plain transact.
2. Then show `(posting/post-transaction! conn {...})` as "if you want one call that gates + seals."
3. Reserve a third snippet for `(process/run-process conn {:steps [...]})` showing the cross-module composition pattern — that's the punchline of ADR-067/ADR-068 and currently buried.

The wrapper stays in the API (it's genuinely useful for one-shot REPL sessions and tests) but reads as a convenience, not the canonical entry.

---

## T3 — `:db.fn/call` composition

**Verdict: keep `run-process`'s `d/db-with`-threaded model as the primary; ship a thin `to-tx-fn` adapter so consumers that need a single tx-data list have it. Don't dual-implement.**

The current model (`src/kontor/process.clj:86-138`) reads cleanly:

- `run-steps` (line 86) threads `(d/db-with db0 acc)` so each step sees prior fragments.
- `run-process` (line 110) locks on `conn`, applies one outer `with-vt`, commits through `validation/transact-with-validation`.
- The atomicity + cross-module tempid round-trip is proven by `test/kontor/composition_test.clj:58-230` (the audit-doc + legal-hold + tempid-thread test).
- The atomic-abort property is `test/kontor/process_test.clj:113-134`.
- The speculative-eid round-trip (the one footgun) has its own regression test at `test/kontor/process_test.clj:174-206`.

The docstring at `src/kontor/process.clj:60-67` already explains why the `:db.fn/call`-the-whole-process variant was rejected for v1: (1) it cannot reuse the kernel's gate (the datalog-invariant pass needs `conn` + tx-data from outside `d/transact`), (2) it runs step reads inside the writer, (3) `:dry-run?` needs a second code path.

For the **external orchestrator** case the maintainer asks about ("simmis calls `(kontor.process/run-process conn {:steps [step-1 step-2]})` from a module that doesn't depend on kontor at compile time"): the *steps are Clojure fns of (db, ctx) → result*. A consumer that wants to ship a process as data (over the wire, into a job queue) cannot transmit a fn. That's a real use case, and worth a v2 affordance — but it's narrow.

The right shape is one small adapter, not a parallel engine: **`(kontor.process/serialize steps) → [tx-fn-call …]`** that flattens the assembled tx-data into a single vector (after one pre-run on a snapshot db), suitable to be persisted, replayed, or wrapped in a single `:db.fn/call` row that an external transactor invokes. This keeps `run-process` as the one engine, lets the consumer ship the *assembled* tx-data downstream, and avoids the dual-implementation tax. The "step as data" form (a registry: `{:kind :lease/commence-step ...} → fn`) is the natural complement and dovetails with the optional dispatch from T1.

---

## T4 — ADR weight in code

**Verdict: (c) keep `decisions.md` in-repo as the internal record; strip the inline `(ADR-NNN)` parentheticals from public docstrings; keep a one-line `See: ADR-067` link at the bottom of namespaces whose central idea is captured in a single ADR.**

Numbers, from `grep -rEho "ADR-[0-9]+" src/ modules/ --include='*.clj'`: **996 ADR references** across **153 files**. The top citations are ADR-068 (150 hits), ADR-038 (84), ADR-034 (60), ADR-067 (36), ADR-042 (35), ADR-021 (31). Most aren't load-bearing — `src/kontor/posting.clj` alone has 18 ADR mentions, many in the form `(ADR-021)` mid-sentence where the surrounding prose already says what the rule is.

Option (a) keep-everything: hurts the readability of a library a newcomer is comparing against XTDB or a Beancount embedding. Numbers without context read as "this design is contested and we need a citation to defend it" — the opposite of "just works."

Option (b) gitignore + delete: throws away real institutional memory. ADRs ARE the rationale for non-obvious choices (the `:posting/posted-at` middleware contract, the multi-entity sum-to-zero rule, the universal `*-tx-data` builder convention) and the maintainers — including future Claudes — will want them.

Option (c) is the v2 stance:

- Keep `doc/decisions.md` in-tree (don't gitignore — it's part of the project's identity).
- Strip the ~900+ inline `(ADR-NNN)` parentheticals from public docstrings. Keep them in *internal* function comments (`;; — ADR-021`) and in the *headers* of namespaces whose entire raison d'être is one ADR (e.g. `kontor.process` references ADR-067 once, in the ns-docstring tail).
- Replace mid-prose `(ADR-038)` with the actual statement: not "the approval-policy gate fires (ADR-038)" but "the approval-policy gate (`:requires-supporting-doc`, `:no-self-approval`) fires."
- A single `See also: ADR-021, ADR-031` line at the end of `kontor.posting`'s ns-docstring beats 18 in-prose citations.

This is mechanical: a `bb` task can do the bulk in an afternoon. The reader sees a clean library; the maintainer keeps the archive; contributors who want the rationale jump one click.

---

## T5 — Reference-platform comments

**Verdict: (b) for the strict comparison comments — move them into `doc/research/`. (c) for one-line "Odoo's `account.move.line`" mentions where the field genuinely descends from a prior-art idiom and naming it helps a reader from that world.**

Counts: **17 files** mention Tryton/NetSuite/SAP/ACDOCA/OFBiz/Sylius/KillBill/Xero/QuickBooks/Beancount/hledger/Sage in source code (51 line hits). Odoo specifically: **20 occurrences across 7 files**. Distribution:

- `src/kontor/import_/beancount.clj` — Beancount mentions are correct here (it's the Beancount round-trip module — ADR-009). Keep.
- `src/kontor/schema.clj:135-148` — Odoo `:account/code` mention is load-bearing (explains the dual identity model). Trim to one line.
- `src/kontor/schema.clj` lines mentioning NetSuite "Locked"/"Closed", Xero "End of Year Lock Date", Sage Intacct "locked" — these are dictionary-cross-reference, useful for an accountant deciding "is this what I want?". Keep ONE consolidated comment per namespace, drop the in-line repeats.
- `modules/invoice/src/kontor/invoice/schema.clj` and `modules/invoice/src/kontor/invoice/bridge.clj` — OFBiz `GlAccountTypeDefault` references explain the lookup-table shape. Replace with the *shape description* ("a (party, product-type, account-type) → account lookup table") and demote the OFBiz citation to a research-note link.
- `src/kontor/entity.clj` — "NetSuite's Elimination Subsidiary" — substantive. Keep as the one-line footnote it already is.
- `src/kontor/costing_provider.clj` — SAP FIFO note. One line is fine.

Action: a sweep that (i) keeps `kontor.import_.beancount` untouched, (ii) reduces every OTHER reference to a single trailing comment at most per namespace, (iii) moves the detailed comparison prose into the matching research note (most are already cited in `doc/research/` — 01-odoo, 12-ofbiz, 28-odoo-asset, 29-tryton-asset, 30-sap-netsuite-asset, etc.).

The principle: kontor's identity comes from its substrate (bitemporal + sealing + parallel ledgers + status machines), not from "we're like Odoo but better." A reader who needs the prior-art context can follow a link.

---

## Cross-cutting consolidation findings

### Error type discipline

- **80+ distinct `:type` keywords** in `ex-info` calls across src + modules (`grep -oE ':type :[a-z-]+/[a-z?-]+' | sort -u | wc -l → 80+`).
- Top namespaces: `:lease` (25 hits), `:expense` (11), `:period` (10), `:inventory` (8), `:receipt` (6).
- `:authz/*` already has 4 different shapes: `:authz/bad-input`, `:authz/schema-invalid`, `:authz/subject-type-out-of-range`, `:authz/unresolvable-filter` — drift inside one module.
- A huge fraction of `ex-info` calls in `modules/lease/src/kontor/lease/runner.clj:115-121,386-391` (and many other files) throw with `{}` data — **no `:type` at all**. The catch site has to match on `(.getMessage e)`.

**v2 stance.** Adopt one convention: every `ex-info` carries `:type :module/error-kind` AND `:kontor/category` ∈ `#{:validation :authorization :state :integrity :not-found :system}`. The category is the catch-vocabulary for downstream HTTP layers ("turn `:validation` into a 422, `:authorization` into a 403, …"). The kind stays expressive. A `bb lint` rule that fails on `ex-info` with an empty data map is one afternoon's work and would catch the 30+ already-broken sites.

### Schema namespace consolidation

- `:lease-modification/*` (13 attributes, all in `modules/lease/src/kontor/lease/modification.clj`) is sufficiently distinct from `:lease/*` (modification is its own append-only entity, not a property of the lease) — keep as a sibling namespace.
- The `:posting/*` ↔ `:transaction/*` split is correct and load-bearing.
- The `:asset/*` ↔ `:asset-event/*` ↔ `:asset-depreciation/*` triplet is correct.
- Worth examining: `:status-history/*` vs `:status-machine/*` — some attributes appear in both. Roll into one namespace if they're really the same concept; if they're a vocabulary vs the running-state distinction, document the boundary in a comment at the head of `src/kontor/status_machine.clj`.

### Missing public-API namespace

`src/kontor/core.clj` (157 lines, mostly DB lifecycle + a tax-provider helper) is the *closest* thing to a public surface. Its docstring (lines 1-25) lists 11 sub-namespaces a caller should require, but doesn't re-export them. v2 should add a top-level `kontor.clj` (or convert `kontor.core` into one) that publishes a `(:require [kontor :as k])` surface re-exporting: `k/post-transaction!`, `k/build-transaction`, `k/run-process`, `k/account-balance`, `k/trial-balance`, `k/with-vt`, `k/value-at`, `k/install-schema!`, `k/create-test-db`, `k/transact-with-validation`. Potemkin-style or plain `def`s — Clojure idiom is flexible here. This is the single biggest discoverability win for first-time users.

### Asymmetric module surfaces

- `kontor-asset` has 12 transactors (asset CRUD + dep books + runner) — `kontor-lease` has 7 — `kontor-partner` has exactly **one** (`merge-partners!`). The partner asymmetry isn't a problem (partner is mostly a passive entity); flag it once in the README's module table so users don't expect parity.
- `kontor.modules/expense` has both `submit!` + `approve!` + `reject!` + `reopen!` (`modules/expense/src/kontor/expense/core.clj:214-291`). Compare to invoice/sales which expose just the state-machine; consider whether reject!/reopen! should be `kontor.status-machine`-driven instead of bespoke (would shrink the module).

### Dead / under-used surface

- The `post-transaction!` wrapper (called 5 times, all docs/tests) is a candidate for "demote to convenience" not "delete" — see T2.
- `kontor.posting/expand-distribution` (`src/kontor/posting.clj:479-519`) has a `:strategy :cartesian` arm that throws "not yet implemented." Either implement or remove the arm.
- `:db.fn/cas` is mentioned in the `kontor.process` docstring (line 58) as the lock-free escape hatch but not used anywhere in src/. Confirm we actually need to mention it.

---

## Stage R — concrete consolidation checklist

Sequenced so each step lands independently and `bb test` stays green throughout.

**R-1. Top-level `kontor.clj` public-surface namespace.**
Re-exports the ~12 canonical entry points. Update README's worked example to lead with `(:require [kontor :as k])`. (~half a day.)

**R-2. README rewrite — data-first.**
Lead with `build-transaction` + `d/transact`. Second example: `post-transaction!`. Third: `run-process` + cross-module composition (lift the audit-doc + legal-hold snippet from `composition_test.clj`). Trim "ADR-002 / ADR-008 / ADR-038" parentheticals from the introductory prose. (~half a day.)

**R-3. ADR-citation sweep.**
Strip ~900 inline `(ADR-NNN)` mentions from public docstrings; keep at most a `See also: ADR-X, ADR-Y` line per namespace; rewrite each affected docstring so the substantive claim is in the prose, not behind a citation. (~1-2 days, mostly mechanical with a script + spot-check.)

**R-4. Reference-platform comment cleanup.**
Per T5: leave `kontor.import_.beancount` alone, reduce other modules to at most one trailing footnote per ns. Move detailed comparison prose into matching `doc/research/` notes (most already exist; add cross-links). (~half a day.)

**R-5. Error-type discipline.**
Adopt the `:type :module/kind` + `:kontor/category` convention. Backfill missing `:type` on the ~30 sites that currently throw `(ex-info "…" {})`. Add a `bb lint` rule (or kondo hook) that rejects empty `ex-info` data maps. (~1 day.)

**R-6. Opts-shape convention page.**
Add `doc/programming.md` (already exists) — appendix table: the canonical opts vocabulary, which keys every transactor must accept, and the reserved names. Optional: a clj-kondo hook that flags new transactors using a non-canonical key for one of the reserved concepts. (~half a day.)

**R-7. `kontor.process/serialize` adapter.**
A thin function that runs a process on a snapshot to assemble the tx-data and returns it as a plain vector (without committing). Lets external orchestrators ship the assembled facts as data. (~half a day.)

**R-8. Dead-code triage.**
Decide on `expand-distribution :cartesian` (implement or remove). Audit `:db.fn/cas` mention. Confirm `post-transaction!` stays as convenience-shortcut. Look once at `modules/expense` reject!/reopen! for status-machine fold-in opportunity. (~half a day.)

**R-9. Stage-R review-after.**
One agent re-reads the new README + the public-surface namespace + the rewritten docstrings as a "first-time Clojure developer evaluating an accounting library" and reports friction. Catch anything the mechanical sweep missed. (~1 agent-run.)

Total: ~5-7 focused days + one review-after pass. The kernel doesn't grow; the surface gets quieter and more discoverable.

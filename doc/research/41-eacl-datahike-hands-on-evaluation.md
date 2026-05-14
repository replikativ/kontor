# 41 — EACL → datahike: hands-on REPL evaluation

**Date:** 2026-05-14
**Method:** hands-on. Not a paper study — EACL's *actual* source was
run, unmodified except for a require-swap, against a live datahike
database. Three parts: (1) a dependency-surface map of EACL's
Datomic API usage (one background agent, file:line citations); (2)
direct datahike-primitive probes in the REPL; (3) **a contained
experiment that loads EACL's real namespaces — including the hot-path
`eacl.datomic.impl.indexed` — and exercises the full `IAuthorization`
protocol against datahike.** The experiment lives at
`/tmp/eacl-dh/` (a copy of EACL `src/`, a `datomic.api`-compat shim,
a model + fixtures + assertions).

This supersedes the paper evaluation in task #91 — that asked "should
we use EACL?"; this asks "does EACL actually *run* on datahike?"

## Verdict

**EACL runs on datahike.** The whole `IAuthorization` surface —
`can?` (including arrow-permission graph traversal), `lookup-
resources` (the cursor-paginated `index-range` hot path),
`count-resources`, `lookup-subjects` (reverse traversal),
`create-relationship!`, `create-relationships!`,
`delete-relationships!`, `read-relationships` — **all pass against a
datahike DB**, with **no changes to EACL's traversal logic**. The
port is a ~40-line `datomic.api`-compat shim + one keyword change +
a `:require` swap in four files. It is **not a rewrite**.

The dependency-surface agent flagged `indexed.clj` as the
"make-or-break" file — its 14 `d/index-range` calls do bounded
range-scans over composite tuple attributes and rely on results
coming back **ordered by the trailing ref eid** (that ordering *is*
the pagination cursor). The REPL probes + the experiment confirm
**datahike provides exactly this**: composite `:db/tupleAttrs` are
auto-maintained, `index-range` over a tuple attr with full-arity
`:start`/`:end` bounds returns datoms ordered by the trailing eid,
and the cursor-resume pattern (`[… cursor-eid]` .. `[… MAX]`) works.

## The datahike ↔ Datomic gap (the whole of it)

| Datomic API EACL uses | datahike | port action |
|---|---|---|
| composite `:db/tupleAttrs`, auto-maintained | **works** ✓ | none |
| `:db.unique/identity` on a tuple attr (relationship dedup/upsert) | **enforced** ✓ — confirmed by upsert probe | none |
| `index-range` over a tuple attr, full-arity bounds, **ordered by trailing eid** | **works** ✓ — the hot-path mechanism | none |
| `d/index-range db attr start end` (positional) | present, but **map-arg** `{:attrid :start :end}` | shim: 1 fn |
| `d/datoms db :avet attr [full-tuple]` (point check) | **works** ✓ (positional + map forms) | none |
| `d/q` / `d/pull` / `d/entity` / `d/seek-datoms` / `d/db` / lookup-refs | present, compatible ✓ | none |
| `d/entid` | **missing** | shim: `(:db/id (d/entity db x))` |
| `d/basis-t` (the ZedToken) | **missing** | shim: `(:max-tx db)` |
| `@(d/transact …)` (Datomic returns a future) | datahike returns the report directly | shim: wrap deref-able |
| `[:db.fn/retractEntity eid]` | datahike uses `[:db/retractEntity eid]` | **1 keyword** in `impl.clj:170` |

**Gotcha (does NOT bite EACL):** datahike's `index-range`/`datoms` do
*not* support partial-tuple prefix bounds — `[alice]` or
`[alice nil nil]` return nil. EACL never does that; per the surface
map it always pads to full-arity tuples with `:a` / `:z` / `0` /
`Long/MAX_VALUE` sentinels, which is exactly the form datahike
supports. A naive port that tried prefix tuples would break here.

**Pre-existing EACL wart (not datahike's fault):**
`lookup-subjects` returns a cursor whose `:subject :id` is an
un-coerced internal eid (`{:subject {:id 26}}`) — `core.clj`'s
`spiceomic-lookup-subjects` has a "todo cursor coercion" comment.
The `:data` is coerced correctly; only the cursor leaks the eid.

**Keyword-sentinel fragility (pre-existing, worth knowing):** EACL
uses `:a` / `:z` as lexicographic keyword sentinels to span all
subject-types in `relation-datoms`. A subject-type named `:zebra`
sorts *after* `:z` — the upper sentinel would miss it. Not a
datahike issue; a latent EACL bug a kontor schema should avoid by
construction (don't name a type past `z…`).

## What the experiment actually proved

`/tmp/eacl-dh/experiment.clj` installs EACL's `v6-schema` in a
datahike DB, defines a 2-relation / 2-permission model (`account
admin = owner`; `server view = account->admin`), creates entities +
relationships in one tx, builds the EACL client with
`eacl.datomic.core/make-client`, and asserts:

```
PASS  can? user-1 :view server-1            (arrow traversal: server→account→owner)
PASS  can? user-2 :view server-1 => false
PASS  can? user-1 :view server-2
PASS  lookup-resources user-1 :view :server => 2     (index-range cursor path)
PASS  count-resources user-1 :view :server  => 2
PASS  lookup-subjects server-1 :view :user  => 1     (reverse traversal)
PASS  create-relationship! ; can? now true
PASS  delete-relationships! ; can? now false          (needs the :db/retractEntity fix)
PASS  create-relationships! (plural) round-trip
PASS  read-relationships {:resource/id account-1} => 2 owners
```

All ten green. The `indexed.clj` graph traversal — path calc,
arrow/self-permission recursion, the lazy sorted-merge-dedupe, the
eid cursor — ran unmodified.

## File-level coupling (from the surface map)

~40% of live EACL is DB-agnostic and ports for free: `eacl.core`
(the `IAuthorization` protocol + records), `eacl.lazy-merge-sort`
(341 lines of generic seq algorithms), `eacl.datomic.impl.base`
(entity-map builders), `eacl.datomic.spice-parser` (the SpiceDB
schema-string parser), `eacl.spicedb.consistency`. `core.clj`,
`impl.clj`, `schema.clj` use only standard datalog/`datoms`/`entity`/
`transact` — mechanical port. The risk was concentrated in
`indexed.clj` (696 lines) — and the experiment clears it.
`datalog.clj` + `rules/*` (~1200 lines) are **dead code** — the
older recursive-rules path, not wired by `impl.clj`.

## Recommendation for `kontor-authz` (task #117)

EACL's design and traversal algorithm are exactly what kontor-authz
needs, and they are now *proven* to work on datahike. EACL's ReBAC
model (SpiceDB-compatible: definitions, relations, permissions,
arrows) is a strong, well-thought-out foundation — not something to
reinvent.

Three viable paths, with the constraints in tension:

1. **Depend on EACL as a library** (`:git/sha`) + ship only the
   compat shim + schema bridge in `kontor-authz`. *Blocker:* EACL's
   `deps.edn` pins `com.datomic/peer` — depending on it pulls Datomic
   transitively. A datahike-based companion pulling the Datomic peer
   jar is ugly (though companions, unlike the kernel, *may* carry
   their own deps — ADR-002). Cleanest only if EACL upstream gains a
   way to exclude Datomic / a backend protocol.

2. **Vendor EACL into `modules/authz/`** behind the compat shim.
   *Open question:* EACL is **EPL-2.0**; kontor is **EPL-1.0**.
   EPL-2.0 is the successor licence and is *close kin* (same family,
   adjacent communities — datahike/replikativ ecosystem), far
   friendlier than the Odoo-LGPL / Tryton-GPL situation ADR-001
   rules out. But EPL-1.0 ↔ EPL-2.0 mixing in one artifact needs an
   explicit licence call before vendoring.

3. **Reimplement informed-by-EACL** natively on datahike in
   `modules/authz/` — the project's established pattern (lift
   *patterns*, write our own — ADR-001, research note 11). The
   traversal is ~1000 lines of mostly-DB-agnostic graph code; the
   experiment proves the datahike primitives carry it. Sidesteps the
   licence question entirely, keeps the dependency story clean
   (datahike-only), and lets the schema use kontor's
   `:authz/*`-namespaced attrs (ADR-002 cohabitation) instead of
   EACL's `:eacl.*`. Most work, cleanest result.

**Leaning #3** — but #2 vs #3 is a genuine design call (licence
appetite vs. effort) and the licence question (EPL-1.0 ↔ EPL-2.0)
should be settled first. Either way the *hard* technical risk —
"does the ReBAC traversal even work on datahike" — is now **retired**.

## Reproduce

```
/tmp/eacl-dh/                         # EACL src copy + the experiment
  src/eacl_compat/api.clj             # the ~40-line datomic.api shim
  src/eacl/...                        # EACL src, [datomic.api] -> [eacl-compat.api]
  experiment.clj / experiment3.clj    # schema install + model + assertions
  deps.edn                            # datahike + specter + malli + core.cache
cd /tmp/eacl-dh && clojure -M -e '(load-file "experiment3.clj")'
```

The single EACL source edit beyond the require-swap: `impl.clj:170`
`:db.fn/retractEntity` → `:db/retractEntity`.

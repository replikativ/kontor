# 26 — EACL evaluation: ReBAC-on-Datomic as a candidate for `kontor-authz`

**Date:** 2026-05-13
**Status:** research input only — feeds ADR-053 (`kontor-authz` design)
**Scope:** deep technical evaluation of [EACL](https://github.com/theronic/eacl), a Clojure ReBAC library backed by Datomic, against kontor's substrate. Companion to research note 25.
**Non-goals:** writing code, picking a winner without options, locking ADR-053.
**Reading order:** assumes research note 25 (landscape + recommended Option B) has been read.

---

## 1. EACL primer

[EACL](https://github.com/theronic/eacl) ("EE-kəl", *Enterprise Access ControL*) is a [Zanzibar](https://research.google/pubs/zanzibar-googles-consistent-global-authorization-system/)-shaped, [SpiceDB](https://authzed.com/spicedb)-compatible **ReBAC** library written in Clojure, stored in **Datomic**. Author: Petrus Theron (`theronic`), partly funded by CloudAfrica. License: **EPL-2.0** (`/home/christian-weilbach/Development/eacl/LICENCE:1` — the README's "AGPL" claim is stale, contradicted by commit `f8c3c1c` "Update licence to EPL 2.0").

**What it is.** A library (not a service) embedded in Datomic apps; no separate process, no network hop. `eacl.datomic.core/make-client` takes a Datomic `conn` and returns an `IAuthorization` record (`src/eacl/datomic/core.clj:186`). Zanzibar dialect: permissions derive from `(subject, relation, resource)` tuples plus a schema of `Relation`s and `Permission`s. Claims SpiceDB-gRPC wire compatibility as a future migration target (`README.md:14-17`). v6 schema, no tagged releases — "pin the Git SHA."

**What it isn't.** Not a policy engine (no ABAC, negation, or caveats). Not a complete SpiceDB: missing caveats, `expand-permission-tree`, `write-schema!`, negative permissions (`-`), nested arrows (`a->b->c`), `subject.relation`, consistency tokens (`README.md:584-600`). Not battery-included for datahike — every read path uses Datomic peer APIs.

**Why it exists.** The author writes (`README.md:35`): "The first version of EACL was implemented with Datalog rules, but it was simply too slow and materialized all intermediate results. Correct cursor-pagination is also non-trivial, because parallel paths through the permission graph can yield duplicate resources." The central engineering claim worth taking seriously: **naive datalog ReBAC doesn't scale for `lookup-resources`**, even on Datomic. EACL's response is to bypass datalog and traverse the permission graph by hand using `d/index-range` over composite tuple-attr indices. The implementation is `src/eacl/datomic/impl/indexed.clj` (696 LOC) — the most interesting artefact in the repo.

**Activity.** Latest commit `4632187` "Revise README"; previously `f8c3c1c` (EPL relicensing). Sole committer. Bus-factor: 1. Repo is alive but one-person.

---

## 2. Source walkthrough

### 2.1 The protocol (`src/eacl/core.clj:4-81`)

Single `defprotocol IAuthorization` with the canonical SpiceDB surface: `can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, `read-relationships`, `write-relationships!`, `read-schema`, `write-schema!`, `expand-permission-tree`. The last three throw "not impl" (`src/eacl/datomic/core.clj:200-208,242`). Schema today lives in Datomic and is transacted directly.

`Relationship` (`src/eacl/core.clj:84`) and `RelationshipUpdate` (`src/eacl/core.clj:85`) are defrecords. `SpiceObject` (`src/eacl/core.clj:89`) wraps `{:type :id :relation}` — `:relation` is the Zanzibar `subject_relation` field, which EACL doesn't fully support (no userset-on-userset).

### 2.2 The schema (`src/eacl/datomic/schema.clj:37-211`)

The v6 EACL schema declares ~20 attributes across three namespaces — `:eacl.relation/*`, `:eacl.permission/*`, `:eacl.relationship/*` — plus `:eacl/id` as the external string ID. The interesting parts:

- **Composite tuple indices** are load-bearing. The schema declares five composite `:db.type/tuple` attributes with `:db/tupleAttrs`:
  - `:eacl.relation/resource-type+relation-name+subject-type` (`schema.clj:66-73`) — uniqueness on schema relations.
  - `:eacl.permission/resource-type+permission-name` (`schema.clj:107-113`) — fast lookup of permission defs.
  - `:eacl.permission/resource-type+source-relation-name+target-type+permission-name` and `…+target-name` (`schema.clj:116-134`) — efficient arrow lookup.
  - `:eacl.relationship/subject-type+subject+relation-name+resource-type+resource` (`schema.clj:180-189`) — the **forward** relationship tuple index. Forward = traverse from a subject to find resources.
  - `:eacl.relationship/resource-type+resource+relation-name+subject-type+subject` (`schema.clj:201-211`) — the **reverse** relationship tuple index. Reverse = traverse from a resource to find subjects.

These tuples are the engine. Every `d/index-range` call in the impl uses one of them.

- **No tenancy attribute.** No `:tenant`, no `:org`, no scope. Multi-tenancy is the consumer's responsibility — typically by namespacing types (e.g., `:tenant-1/server`).
- **No bitemporal attribute.** No `:as-of`, no valid-time. EACL inherits Datomic's tx-time; valid-time is foreign.
- **No history of permission changes.** A relationship `:create`/`:delete` is a Datomic retraction — Datomic's history view preserves it, but EACL exposes no "who granted Alice viewer access on doc:42, and when" API. (Datomic users get this by querying `(d/history db)` directly.)

### 2.3 The check kernel (`src/eacl/datomic/impl/indexed.clj`)

The whole indexed implementation is in one file, 696 LOC. The architecture is a two-phase recursion: **compile** schema to *paths*, then **execute** each path as a series of `d/index-range` calls.

**Phase 1 — `calc-permission-paths` (`indexed.clj:93-162`).** Given `(resource-type, permission-name)`, walk the permission definitions recursively and return a vector of path trees. Each path is one of:

```clojure
{:type :relation, :name :owner, :subject-type :user, :relation-eid 12345}     ; direct
{:type :self-permission, :target-permission :admin, :resource-type :account}  ; same-resource pivot
{:type :arrow, :via :account, :target-type :account, :sub-paths [...]}        ; arrow hop
```

Sub-paths recurse — an `:arrow` whose `:permission` resolves to a permission with more arrows builds a tree. Cycle detection via a `visited-perms` set (`indexed.clj:101-118`). The result is **cached** in `permission-paths-cache` — `clojure.core.cache/lru-cache-factory` threshold 1000 (`indexed.clj:87-91, 164-177`). This is the only cache in EACL. It's a schema-shape cache: changes to relationship *data* don't invalidate it, but adding or removing a `Permission` def does (manual `evict-permission-paths-cache!` call needed — `indexed.clj:90-91`). That's reasonable because schema changes are rare.

**Phase 2 — traversal.** Three sibling traversers:

- `can?` (`indexed.clj:238-306`) — return true on first matching path. For `:relation`, single tuple lookup on the forward index (`d/datoms db :avet` with the full tuple value, `indexed.clj:264`). For `:arrow`, two-step reverse traversal: find intermediate resources connected to the target resource via the via-relation (using the **reverse** tuple index `indexed.clj:280-283`), then for each intermediate either check the target relation directly or recurse `can?` on the target permission.

- `traverse-permission-path-via-subject` (`indexed.clj:416-499`) — subject is known, resource set is unknown. Forward traversal from subject, deduplicating with `lazy-merge-dedupe-sort-by` (`indexed.clj:223`). Drives `lookup-resources`.

- `traverse-permission-path-reverse` (`indexed.clj:545-635`) — resource is known, subject set is unknown. Reverse traversal from resource. Drives `lookup-subjects`.

**Phase 3 — lazy merge sort (`src/eacl/lazy_merge_sort.clj`, 341 LOC).** Multiple paths through the graph can yield duplicate resources (e.g., Alice is both an `:owner` and a `:shared-admin` of server-42). EACL emits each path as a lazy seq sorted by eid, then `lazy-merge-dedupe-sort-by` k-way-merges them in eid order, deduplicating on the way. This is exactly the classic "merge sorted streams" algorithm with extra book-keeping for ascending-uniqueness.

**Cursor pagination (`indexed.clj:501-543`).** Cursors are `{:resource {:type :id}}` — an opaque pointer to "where we stopped last time." Implemented by filtering `> cursor-eid` inside each `d/index-range` consumer (`indexed.clj:202-205, 217-220, 340, 373, 397, 430, 466, 492, 559, 600`). Because results are *always* ordered by Datomic eid, the cursor is a pure scalar.

**Subtle hazard noted by the author** (`README.md:48`): EACL calls `(d/db conn)` on every API entry, so cursor-paginating across queries can yield inconsistent results if writes happen in between. The escape hatch is to call `impl.indexed/lookup-resources` directly with a stable `db` value.

### 2.4 Datomic-specific call sites

Total Datomic API surface (`grep d/`):

| API | Count | Purpose |
|---|---|---|
| `d/index-range` (positional, 4 args) | 17 in `indexed.clj` | the engine |
| `d/datoms` (`:avet` + tuple-attr + tuple-val) | 5 | spot checks for direct-relation `can?` |
| `d/q` | 7 | schema queries, relationship reads |
| `d/db` | many | snapshot per call |
| `d/entid` | many | external-id → eid |
| `d/entity`, `d/pull` | many | enrich results |
| `d/transact` | 3 | grant/revoke + schema |
| `d/basis-t` | 1 | fake "ZedToken" |

No `d/with`, no `d/sync`, no `d/log`, no `d/as-of`, no `d/since`, no `d/history`, no `d/index-range` with the 5-arg `(db idx-key start end limit)` form. The Datomic surface is small and pre-1.0 — it would have been the same against Datomic 0.9 ten years ago.

### 2.5 Specter usage

Specter (`com.rpl.specter`, `deps.edn:3`) is used in `eacl.datomic.core` for tree transforms — coercing external-id ↔ entid in nested cursor/result structures (`src/eacl/datomic/core.clj:35, 43, 80, 94, 144-152`). It is **not** load-bearing — could be replaced with plain `update-in` and `mapv` without semantic change. Probably ~50 LOC saved by Specter; not a meaningful adoption cost.

### 2.6 Malli usage

`metosin/malli` (`deps.edn:5`) declares `Relation` and unused `DirectPermission` / `ArrowPermission` (`src/eacl/datomic/schema.clj:11-15, 18-35` — most are commented out). The Malli specs are not enforced anywhere I can find via `m/validate` in the source. **Currently performative**; could be removed.

### 2.7 SpiceDB compatibility layer

`src/eacl/impl/spicedb.clj` is 4 LOC (placeholder). `src/eacl/spicedb/consistency.clj` is 7 LOC (the `fully-consistent` token). The "SpiceDB gRPC sync" claim is *aspirational*; no gRPC code, no proto bindings, no live sync exist. The IAuthorization protocol is *shaped* like SpiceDB's surface but the wire compatibility is documentation, not code. A tenant migrating to SpiceDB would currently re-implement the transaction-log tail themselves.

### 2.8 Tests (`test/eacl/`)

- `eacl.datomic.impl.indexed_test` (1075+ LOC) — the substantial coverage. `deftest`s for permission helpers, check, complex relations, read-relationships, lookup-subjects, "eacl3", lookup-resources, permission-schema-helper, get-permission-paths, traverse-paths, lookup-resources-optimized, lookup-resources-with-merge, can-optimized, infinite-recursion regression, permission-paths-caching. Heavy.
- `eacl.datomic.parser_test` — SpiceDB DSL parser tests; the parser itself (`spice_parser.clj`) is "WIP" per its docstring.
- `eacl.datomic.performance_test` — **entirely commented out** (`performance_test.clj:1-221` is all `;`-prefixed). No published benchmark numbers in source.
- `eacl.benchmark_test` — also entirely commented out (`benchmark_test.clj:1-353`).
- `eacl.spice_test` — small spicedb-spec round-trip helpers.

So the public performance claims ("800k permissioned resources at 5-30ms", "10M target", `README.md:42-43`) are author-reported, not reproducible from the test suite as committed.

---

## 3. Datahike portability analysis

The premise: replace Datomic with datahike. Let's classify each Datomic call.

### 3.1 What datahike has

A quick REPL probe (`clj-nrepl-eval` against the running kontor REPL) confirms:

```clojure
(d/transact conn [{:db/ident :rel/a+b :db/valueType :db.type/tuple
                   :db/tupleAttrs [:rel/a :rel/b]
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}])
(d/index-range @conn {:attrid :rel/a+b :start [:x :a] :end [:x :zzzz]})
;; => [#datahike/Datom [446 :rel/a+b [:x :y] ...] #datahike/Datom [447 :rel/a+b [:x :z] ...]]
```

So datahike supports both **composite tuple attributes** with `:db/tupleAttrs` (`/home/christian-weilbach/Development/datahike/src/datahike/db.cljc:755-783`, `schema.cljc:142-158`) AND **`d/index-range` on tuple values** (`api/specification.cljc:421-433`). The fundamental capability EACL relies on is present.

### 3.2 What's different at the API surface

| Datomic | datahike | Compatibility |
|---|---|---|
| `(d/index-range db attr start end)` (positional 4-arg) | `(d/index-range db {:attrid attr :start s :end e})` (map 2-arg) | **Wrapper needed** — trivial. |
| `(d/datoms db :avet attr v)` (positional) | `(d/datoms db {:index :avet :components [attr v]})` (map) | **Wrapper needed** — trivial. |
| `(d/seek-datoms db idx c1 c2 …)` (positional) | `(d/seek-datoms db {:index … :components […]})` (map) | **Wrapper needed** — trivial; EACL doesn't use `seek-datoms` so this is a non-issue. |
| `(d/entity db eid)` → lazy entity | `(d/entity db eid)` → entity record | Behavioral parity; some attribute resolution differences exist on `Entity` access semantics. |
| `(d/pull db pattern eid)` | `(d/pull db pattern eid)` | Compatible. |
| `(d/q query db & args)` | `(d/q query db & args)` | Compatible (Datalog dialect parity). |
| `(d/transact conn tx-data)` returns a future with `:db-after` | `(d/transact conn tx-data)` returns a `TxReport` synchronously | Different shape — call sites in `core.clj:99` need `(:db-after tx-report)` direct instead of `@(...)`. Trivial. |
| `(d/basis-t db)` → tx num | `:max-tx` on the db record or use `:db/txInstant` on the latest tx | EACL uses this only as `:zed/token` placeholder; substitute. |
| Datomic eids are stable across the lifetime of the DB (after re-import they change) | datahike eids are also stable across normal operation but the audit story (ADR-007) explicitly notes that `:db/purge` is a *recorded* commit — purged eids do not silently disappear | Semantic parity. **But:** EACL's cursor pagination assumes eid order is stable; datahike's eid allocation is sequential like Datomic's; not a portability blocker. |

**Classification per call site** (recapping section 2.4):

- `d/index-range` — (b) needs an adapter (4-arg → map). 17 call sites in `indexed.clj`. ~10 lines of glue.
- `d/datoms` — (b) needs an adapter (positional → map). 5 call sites. ~3 lines.
- `d/q`, `d/pull`, `d/entity`, `d/entid` — (a) trivially-equivalent.
- `d/transact` — (a) modulo dropping the `@deref`.
- `d/basis-t` — (b) substitute the equivalent.

**None** of the EACL Datomic calls fall into category (c) "needs new datahike feature" or (d) "datahike bitemporal indexes would be the right substrate but the API isn't there yet." The latter is interesting and discussed in §5.

### 3.3 The "two file find/replace" estimate

If you take EACL as-is and rewrite the two adapter shapes (`d/index-range` and `d/datoms`), you get a ported library in **~50 LOC of changes** to `src/eacl/datomic/impl/indexed.clj`, `src/eacl/datomic/impl.clj`, and `src/eacl/datomic/core.clj`. The schema (`src/eacl/datomic/schema.clj`) works *almost* unchanged — datahike accepts `:db/tupleAttrs` and `:db.unique/identity`.

Caveat: I have not run the test suite. The "almost" depends on:
1. datahike's tuple-attr `:db/index true` semantics (it is implied for `:db/unique`, may not be for non-unique tuples).
2. `d/index-range` return order for *tuple* values — datahike sorts lexicographically per-component, which matches Datomic, but should be confirmed.
3. The exact shape of `:db/id` resolution via the `make-client` config — datahike doesn't have Datomic's "schema entity ident" pattern in quite the same way for non-keyword idents.

These are all small (hour-scale) issues, not architectural.

---

## 4. Architecture fit with kontor

### 4.1 License + governance

EACL's LICENCE file is EPL-2.0 (confirmed `/home/christian-weilbach/Development/eacl/LICENCE:1`; the README's "AGPL since 2025-05-27" line is **stale** — relicensed back to EPL-2.0 in commit `f8c3c1c`). **EPL-2.0 → EPL-1.0 consumption is allowed**: EPL-2.0 explicitly preserves compatibility with EPL-1.0 distributors, and the "Secondary License" provision is opt-in (EACL's LICENCE Exhibit A is empty, so no GPL crossover). kontor (EPL-1.0) can depend on EACL without license friction. If kontor were to *import* EACL code and modify it, the EPL-2.0 covers it under either EPL-2.0 or EPL-1.0 for the unchanged parts. License-clean.

Bus-factor 1 author is a real risk for a deep dependency. ADR-001's "single dep on datahike" principle (`CLAUDE.md:50`) directly addresses this: adding EACL as a second runtime dep would be a regression.

### 4.2 Schema namespacing

EACL claims `:eacl.relation/*`, `:eacl.permission/*`, `:eacl.relationship/*`, `:eacl/*`. kontor's ADR-002 (cohabitation) requires every kernel attribute to namespace under one of an allowlist: `:account/* :journal/* :transaction/* :posting/* :commodity/* …` plus the audit-doc/approval-policy ones (`CLAUDE.md` "Namespacing"). EACL's namespaces don't collide with kontor's; both can live in one datahike DB. The cost is some namespace pollution (`:eacl/id` mixing with kontor's `:transaction/code` etc.) — visible in `kontor.core/schema-summary` output unless `eacl` is added to `internal-namespaces` (`src/kontor/core.clj:124-134`).

### 4.3 Bitemporal composition

This is the most interesting fit question.

EACL's reads run against `(d/db conn)`, the current basis. There is *no way today* to pass `:as-of-tx` or `:as-of-valid` to `can?` or `lookup-resources`. The author's design choice — call `(d/db conn)` per request — is the opposite of what kontor needs.

What kontor needs (per research note 25, §9 "the bitemporal-authorization angle"): the ability to answer "what could Alice have read on 2024-Q4-close?" — both at *tx-time* (snapshot of permissions as of that tx) AND *valid-time* (effective-dated role assignments). For instance, if Alice's role was revoked effective 2024-12-31, but the revocation was recorded on 2025-01-15, the "as Alice on 2024-12-30" query should still grant access.

The architectural shape needed:

- `(can? db {:as-of-tx <tx>} subject permission resource)` — easy if EACL's internals took `db` as the first parameter (which they do — `impl.indexed/can?` does, see `indexed.clj:238`). The wrapper at `Spiceomic` (`core.clj:191`) calls `(d/db conn)` — but the internals are already db-first. Fix is to accept an `:as-of-tx` and call `(d/as-of (d/db conn) tx)` before passing in.
- `(can? db {:as-of-valid <inst>} …)` — much harder. EACL has no concept of valid-time. Relationships are not effective-dated. To support this, *every* `:eacl.relationship/*` tuple would need a `:tx/valid-from` / `:tx/valid-to` pair, and `traverse-permission-path*` would need to filter the tuples by valid-time intersect.

The first is a 30-minute patch to EACL (push the `db` value all the way through; we already have it). The second is a structural change — it likely doubles the indexed surface (each tuple now needs to participate in a per-tuple valid-time bucket, not a per-tx valid-time as kontor's bitemporal does via `:tx/valid-from`). It is closer to "fork EACL" than "patch EACL."

**However** — kontor's own bitemporal (`src/kontor/bitemporal.clj`) achieves valid-time via *tx-meta* (`:tx/valid-from` on the transaction itself, `bitemporal.clj:75-92`), not per-row. If EACL relationships are written through `kontor.bitemporal/with-vt` (`bitemporal.clj:116`), they pick up valid-time *for free* — the existing `(value-at db eid attr cutoff)` resolver (`bitemporal.clj:249`) handles "what was the latest known value of `:eacl.relationship/...` at vt=cutoff."

This is a clean composition: EACL stores its tuples in datahike; kontor's bitemporal indexes already cover them; the missing piece is a wrapper around `lookup-resources` that injects a vt-aware db filter. The wrapper would synthesize a "db-at-vt" — datahike's `d/filter db pred` accepts a per-datom predicate; pred checks that the datom's tx-meta's `:tx/valid-from` is `<= cutoff` and `:tx/valid-to` is `> cutoff`. EACL's `d/index-range` calls flow through `FilteredDB` (`/home/christian-weilbach/Development/datahike/src/datahike/db.cljc:443-450`), which forwards to the underlying unfiltered db — so the **predicate is applied to results after retrieval**, not at the index. This is correct but means perf is slower than unfiltered queries by a factor of however many datoms get filtered out.

**Conclusion:** bitemporal composition is *possible* without changing EACL internals, by writing-through `with-vt` and querying-through `d/filter`. It is **not free** — `d/filter` is a leaky abstraction and the index-range fast-path may degrade unpredictably. A native bitemporal authz would push tx-meta into the path-traversal filter explicitly.

### 4.4 Sealing + status-machine + approval-policy composition

**Sealing (ADR-007).** kontor's sealing forbids silent retraction of posted entities. EACL's `:eacl.relationship/*` are not "posted" in the kontor sense — they're a separate concern, parallel to accounting state. There's no conflict; both layers' middleware compose. Adding EACL would not change the sealing semantics for accounting data.

**status-machine (ADR-034) + approval-policy (ADR-038).** kontor enforces write-time SoD via `record-status-change!` (`src/kontor/status_machine.clj:306-335`). Write-time authz is *who can call* `record-status-change!` — read-time authz is *what entities they can see when listing things to approve*. These are orthogonal; both must compose. The right model:

1. **Read-time gate** (EACL or alternative) — `(lookup-resources …)` filters the list of `:invoice` entities in `pending-approval` state to those Alice has visibility on.
2. **Write-time gate** (existing `check-policies`, `src/kontor/status_machine.clj:225-247`) — when Alice attempts the transition to `:approved`, `approval-policy` rules check `:no-self-approval`, `:requires-supporting-doc`, etc.

EACL has no opinion on write-time policies, so there is no contradiction. The composition is: read uses EACL; write continues to use `kontor.status-machine/check-policies`.

### 4.5 Privilege-tag mapping (Stage M, ADR-051 incoming)

`:audit-doc/privilege` is a classification keyword (`:attorney-client`, `:work-product`, …). In ReBAC terms it's an *attribute* of the resource, not a relation. EACL doesn't model attributes directly.

Two mappings exist:

**Mapping A — privilege as a relation tuple.** For each privileged doc, write a tuple `(:role:in-house-counsel, :viewer, :audit-doc:42)` instead of (or in addition to) a `:audit-doc/privilege :attorney-client` attribute. The privilege classification becomes implicit in *which role has the viewer relation*. This is the Zanzibar idiomatic answer.

**Mapping B — Caveats (SpiceDB feature, missing in EACL).** A caveat is "the policy holds *if* this context predicate evaluates true." SpiceDB supports `permission view = viewer with privilege_check` where `privilege_check(privilege string, user_role string)` is evaluated at check time. EACL does **not** implement caveats (`README.md:584-600`).

Without caveats, kontor would default to Mapping A — generate the appropriate relation tuples at `:audit-doc` creation time based on the privilege value. This is awkward (double-write) but workable.

A third option: keep the privilege attribute on the doc and use a *separate datalog rule layer on top of EACL* to perform the attribute-based filter as an outer composition. This is the hybrid "EACL for relations, custom rules for ABAC" approach. Adds a layer but stays simple.

### 4.6 Status-history of permission changes

EACL does not write a "permission changed" audit log. Grant and revoke are plain Datomic transactions (`src/eacl/datomic/core.clj:90-102, 213-231`); the `(d/history db)` view captures the change but there's no first-class API to ask "who granted Alice viewer on doc:42 and when?" The Spiceomic record at line 186 implements `IAuthorization` without any audit-write hook.

For kontor, this is a real gap — the `:status-history` pattern (ADR-034) is the kernel's universal audit trail. A `kontor-authz` port would need to either:

(a) write a `:status-history` row on every `write-relationships!` call, treating the relationship as an entity with a `:status-history/facet :authz/active` and transitions `:granted` ↔ `:revoked`, or
(b) wrap EACL's transactor to call `kontor.status-machine/record-status-change-tx-data` atomically with the relationship tx.

This is a ~50-LOC addition. It is *required* for kontor-authz to compose with the existing audit story.

### 4.7 Transactor-opts shape

kontor's transactor convention (`doc/conventions.md` per ADR-038) is that every write takes `{:changed-by-uid :reason :reason-note :supporting-doc}`. EACL's `write-relationships!` (`src/eacl/datomic/core.clj:90-102`) takes a flat list of `RelationshipUpdate`s and `@(d/transact)`s them — no audit metadata. Composing with kontor convention requires either (a) adding optional `:tx-meta` to `RelationshipUpdate` (extends `defrecord` — backward-compat), or (b) wrapping every call with `kontor.bitemporal/with-vt` and a tx-meta entity ID. Either is a small change but it is a change to EACL's API surface, which the author would need to bless or we maintain a fork.

### 4.8 Multi-tenancy

kontor is multi-entity (ADR-031): one db can hold multiple `:entity` rows, each scoped to a separate tax-id / parent-relationship / books. EACL has no built-in tenant scoping. The standard ReBAC pattern is to add a `:tenant` relation on every resource and a userset rewrite `permission view = viewer & tenant->member`. This means kontor consumers writing EACL schema would need to be disciplined about tenant tuples on every resource.

Alternative: kontor's `:user-role/scope-org` (proposed in research 25 §8.2) is a sidecar attribute on role assignments. An EACL port could honor this by post-filtering results — slower but operationally simpler. The right call is to do it the Zanzibar way (tenant as a relation) at the cost of more relationship-write volume.

---

## 5. Performance reality check

EACL's published claims (`README.md:42-46`):
- "internally benchmarked against ~800k permissioned resources with good latency (5-30ms per query)"
- "10M permissioned entities" is a future goal
- "no cache" — relies on Datomic peer's datom cache

These numbers are author-reported. The committed benchmark tests are all commented out (`test/eacl/benchmark_test.clj`, `test/eacl/datomic/performance_test.clj`). For kontor:

- **Typical workload.** A kontor tenant is one accounting entity with maybe 5-50 users, 10k–100k invoices/postings/etc., maybe 1-100k partner records. Total permissioned-entity count: 10k–500k. EACL's "800k" is comfortably in scope; the optimization is **not premature for kontor's largest plausible single tenants**.
- **Multi-tenant SaaS.** If multiple tenants in one DB, eid count balloons. Still, the algorithm is `O(log N)` on relationship-tuple count per `:resource-type+permission` arity, which is a friendly scaling regime.
- **datahike performance.** datahike is generally slower at writes than Datomic peer, but its read perf — particularly for `d/index-range` and direct datom access — is broadly comparable on the same in-memory store. For LMDB or RocksDB backings, the b-tree indices are well-tuned. EACL's traversal pattern (lazy walk via index-range) is exactly the access pattern datahike's indexes are good at.

**Verdict.** EACL's optimization is *not* premature for kontor; the typical kontor tenant fits comfortably. **But** it is also not necessary — research note 25's Option B ("light primitive — datalog rules + `kontor.authz/{can?, visible?, why}`") would suffice for the predicted workload. The trade-off is *future-proofing* (EACL handles 10× growth without redesign) versus *complexity* (EACL is 3700 LOC + cache + lazy merge sort + cursor pagination; Option B is "~500 LOC" per research 25 §13).

---

## 6. The four concrete options

### 6.1 Option α — Adopt EACL as-is (depends on Datomic)

**Path.** Add `theronic/eacl` to `deps.edn`. Add Datomic peer as a runtime dep. Run kontor against Datomic (impossible — datahike is the substrate per ADR-001) OR run EACL as a sidecar with its own Datomic instance, sync relationships via a transactor-tail.

**Effort.** Adopting the library is one line. The architectural fit is impossible: kontor cannot run on Datomic without violating ADR-001 ("single dep on datahike") and ADR-010 ("Clojure-only, single-dep").

**Risk.** Two runtime DBs (datahike for accounting, Datomic for authz). Distributed-consistency problems. Datomic licensing decisions (the on-prem peer is free for dev but Datomic Cloud is paid). Operational complexity for SMB self-hosters who'd need to run both.

**Verdict.** Non-starter for ADR-001 reasons.

### 6.2 Option β — Port EACL to datahike

**Path.** Fork or PR EACL. Replace `d/index-range` / `d/datoms` positional API with datahike's map API (10–50 LOC). Replace `@(d/transact)` with sync `(d/transact)`. Validate composite tuple-attrs (proven, §3.1). Run EACL's test suite against datahike.

**Effort.** 1-3 weeks for a working port + ~1 week each for bitemporal wrapper, `:status-history` composition, tenant relation conventions. ≈ 4-6 weeks total for v1.

**Risk.** Maintaining a fork of EACL is a long-term cost; bus-factor 1 upstream. Cursor-pagination assumes Datomic's stable-eid-order; datahike's eid allocation is also sequential and stable, with gaps possible after `:db/purge`. Author has not signaled openness; EPL-2.0 permits forking without consent, but upstream merging requires goodwill.

**License.** EPL-2.0 → EPL-1.0 inbound is fine. **Fidelity to SpiceDB.** Full. **Bitemporal integration.** Wrapper layer per §4.3. **kontor convention.** `:status-history` + transactor-opts changes (~100 LOC) per §4.6/§4.7.

### 6.3 Option γ — Lift the design patterns, write a kontor-native authz from scratch

**Path.** Implement `kontor.authz` as a new namespace. Adopt EACL's *ideas*: relation-tuple shape (under `:authz/*`); cursor pagination via `d/index-range` on composite tuple attributes; `lazy-merge-dedupe-sort-by` k-way merge (port the ~100 LOC literally); two-phase compile-paths-then-execute architecture. Re-implement the plumbing to kontor conventions: bitemporal-aware `can?` (`:as-of-tx` + `:as-of-valid` from day 1); `:status-history` on grant/revoke; tenant-aware via kontor's `:entity` ref (ADR-031); transactor-opts compatible. Loses: SpiceDB gRPC compatibility.

**Effort.** ~1000-1500 LOC v1. ~4-6 weeks. Pattern well-understood (EACL's `indexed.clj` is the reference).

**Risk.** Re-implementing well-tested code introduces bugs (EACL's tests cover cycle detection, multi-path dedup, cursor coherency — kontor's port must recreate these). Style differences (Specter vs plain transforms, malli vs ::spec) show in review. No SpiceDB migration path.

**Pros.** Single-dep purity (ADR-001). Native bitemporal, `:status-history`, transactor-opts. Easy composition with kontor conventions — we write to them from the start.

### 6.4 Option δ — Run EACL alongside, two DBs

**Path.** Continue running kontor on datahike. Run a separate Datomic peer just for EACL. Sync changes via a Datomic tx-tail or via an event bus.

**Effort.** Operationally complex; library-level changes minimal but deployment story balloons. ~2-3 weeks of operational engineering.

**Risk.**
- Two databases, two consistency stories (eventual consistency between them).
- The author's claim (`README.md:5-9`) that "your permission data lives next to your application data" — Option δ throws that benefit away.
- Bitemporal queries across DBs are very hard.
- Operational complexity unacceptable for SMB self-hosters.

**Verdict.** Not viable for the kontor target audience.

### 6.5 Comparative summary

| Dimension | α (as-is) | β (port) | γ (lift+rewrite) | δ (sidecar) |
|---|---|---|---|---|
| Effort (weeks) | impossible | 4-6 | 4-6 | 2-3 |
| Risk | very high (two DBs) | medium (fork maintenance) | medium (bug-for-bug) | very high (ops) |
| License compat | EPL-2.0 → EPL-1.0 OK | OK | n/a (clean room) | OK |
| Semantic fidelity to SpiceDB | full | full | partial (no caveats, no gRPC) | full |
| ListObjects support | yes | yes | yes (port the algorithm) | yes |
| Bitemporal integration | none | wrapper layer | native | very hard |
| Composition with kontor convention | poor | wrapper layer | native | poor |
| Single-dep compliance (ADR-001) | violation | OK (datahike-only port) | OK | violation |
| Bus-factor exposure | high (EACL author) | medium (fork or upstream) | low (kontor team) | high |

---

## 7. The "kontor-authz uses datahike directly" alternative

Research note 25 recommended **Option B — light primitive**: `:user/*` + `:user-role/*` schema, datalog rules as an extension point, `kontor.authz/{can?, visible?, why}` API. ~500 LOC.

Compared to Option γ above:

- **Smaller surface.** Option B's `can?` is "does any datalog rule grant this triple?" — no path-compilation, no traversal kernel. Datalog handles the recursion via rules. Works fine until you hit the same wall EACL hit (slow `lookup-resources` for large result sets, materialization of intermediate results).
- **Ergonomically aligned.** Datalog rules ARE the kontor idiom; the rest of the kernel uses datalog for queries.
- **Performance ceiling.** Option B will hit datahike's datalog scale limits at maybe 10× lower entity counts than EACL's indexed approach.
- **No cursor pagination.** Option B's `lookup-resources` is "run the rule, sort, paginate in memory." Fine for 1000s of results, not 100k.
- **No formal SpiceDB-compatible schema.** Option B is a datalog/rules approach; if SaaS-tier ever materializes and wants SpiceDB on the side, the conceptual translation exists but the code does not.

**Trade-off.** Option B is the right size for the **predicted** workload. Option γ (lift-from-EACL) is the right size for a **future workload** that may or may not arrive. The decision boils down to: do we believe the kontor multi-tenant SaaS thesis enough to invest in cursor-pagination *now*?

Research note 25's recommendation is Option B because (paraphrasing §8.4):
> The substrate is uniquely well-suited for Option B because the relationships kontor already models (`:partner/assigned-collector`, `:entity/parent`, `:invoice/owner-org`, `:audit-doc/privilege`) are exactly the inputs Zanzibar's rewrite tree would consume. We don't need a separate graph store — we have one.

This argues for *Option B + maybe Option γ later*. Option γ is the "Option B → Option C upgrade path" that research 25 §13 hints at.

---

## 8. kontor seams that an EACL port or kontor-authz would touch

Per file:line:

- `src/kontor/schema.clj:38-53` — add `:user/*`, `:user-role/*`, `:authz.relationship/*` (or `:eacl.relationship/*`) namespaces. Tuple-attrs for the relationship indices. Extend `audit-attrs` to require `:create/uid` resolution. Update `internal-namespaces` in `core.clj:124-134` to include the authz namespaces.
- `src/kontor/core.clj:74-84` (`install-schema!`) — add `(authz/install! conn)` after the kernel schema, before companion modules. Bootstrap roles/seeds via an extension point so consumers (beleg, kontor-l10n-de, etc.) can register additional relations.
- `src/kontor/validation.clj` — if Option γ, add a `(filter-visible-to db user-eid)` wrapper that returns a `FilteredDB` for read APIs. Existing call sites in `kontor.balance`, `kontor.trial`, `kontor.ledger` would optionally pass `:as` user-eid.
- `src/kontor/status_machine.clj:267-304` — `record-status-change-tx-data` should compose with authz: writes to `:eacl.relationship/*` ALSO emit a `:status-history` row. New helper `kontor.authz/write-relationships-with-history!` does this.
- Companion modules — `kontor-collections`, `kontor-procurement`, etc. — would each register the authz relations relevant to their entities at module install (e.g., `kontor-collections` registers `(Relation :invoice :collector :user)`).

---

## 9. Recommendation

**Option γ (lift the design, write kontor-native) deferred behind Option B (datalog primitive).**

Concretely:

1. **Land Option B first** per research 25's recommendation. ~500 LOC, ~1-2 person-weeks, addresses immediate Stage M `:audit-doc/privilege` enforcement need.
2. **If and when** kontor multi-tenant SaaS materializes OR a single-tenant install hits scale beyond ~50k permissioned entities, **upgrade to Option γ** by porting EACL's `indexed.clj` algorithm — keep Option B's schema as the public surface; replace the datalog-rule evaluator with a path-traversal evaluator. Forward-compatible schema.

**Not Option α** (ADR-001 violation).
**Not Option β** as the first step (over-engineered for the predicted workload, introduces a fork-maintenance cost we don't need today).
**Not Option δ** (operational complexity unacceptable).

Option γ is in scope as a *deferred upgrade path*, not the v1.

---

## 10. If we ever do Option γ — first three commits

Outline only; defer until Option B has landed and scale pain materializes.

1. **Commit 1 — Schema and writer.** `:authz.relation/*` / `:authz.permission/*` / `:authz.relationship/*` namespaces with the five composite tuple attributes. Port `eacl.datomic.impl.base` (the `Relation` / `Permission` / `Relationship` constructors). Implement `kontor.authz/write-relationships!` with `:status-history` + `:tx/valid-from` integration. Round-trip test.

2. **Commit 2 — `can?`.** Port `calc-permission-paths` + the direct-relation and arrow branches. Lift `lazy-merge-sort` verbatim (Datomic-free). Use datahike's map-form `d/index-range`. Test the canonical account-product-server scenarios.

3. **Commit 3 — `lookup-resources` with cursor.** Port `traverse-permission-path-via-subject` + cursor coercion. Test paginated results across mixed direct + arrow permissions.

After these, `kontor.authz` has parity with EACL's three core APIs on datahike. Follow-ups: `lookup-subjects`, bitemporal `:as-of-valid` filtering, `count-resources`.

---

## 11. Open questions for ADR-053

Inheriting the open questions from research 25 §11, plus EACL-specific:

1. **Option B → Option γ upgrade.** Should the Option B schema commit to the relation-tuple shape from day 1 (forward-compatible to γ)? **Recommendation:** yes — `:authz.relationship/{subject, relation, resource}` is the future-proof shape even when the v1 evaluator is datalog-based.

2. **Caveat-equivalent for `:audit-doc/privilege`.** Without SpiceDB caveats, do we express privilege as a relation tuple or via a sidecar attribute predicate? **Recommendation:** start with relation tuples (Zanzibar idiomatic); add the predicate layer when ABAC needs surface.

3. **SpiceDB gRPC migration path.** Does kontor-authz commit to staying SpiceDB-compatible (so a future graduating tenant can fork to SpiceDB)? **Recommendation:** maintain *schema* compatibility (same Relation/Permission shape), defer wire compatibility — too speculative.

4. **Public API stability.** Is `kontor.authz/{can?, visible?, why}` the v1 API per research 25 §13, or do we adopt EACL's `IAuthorization` protocol shape verbatim for future portability? **Recommendation:** use the kontor-idiomatic kebab-case API; document a SpiceDB-shape adapter as an optional thin layer.

5. **Fork-EACL-as-companion?** A separate `kontor-authz-eacl` companion module that wraps EACL's design more literally is also viable as a niche product. **Recommendation:** leave as a community project; kontor core ships Option B.

Deferred to a hypothetical SaaS-tier ADR:
- Multi-tenant scoping (the `:tenant` relation pattern vs. `:entity`-based scoping).
- Network-mode (separate authz service vs. embedded).
- Cache invalidation strategy at scale.
- Negation operators (`-` in SpiceDB; EACL doesn't support it either).

---

## 12. Summary verdict

EACL is **a high-quality single-author library** that solves Datomic-backed ReBAC `lookup-resources` scaling with a clever indexed-traversal kernel. Well-organized, well-tested (1000+ LOC of regression tests), EPL-2.0. Its Datomic surface is small (`d/index-range`, `d/datoms`, `d/q`); datahike has equivalents.

**Poor fit for kontor as a runtime dependency** because: (1) Datomic-bound today (ADR-001 blocker); (2) bus-factor 1 author → fork-maintenance liability; (3) no bitemporal story; (4) no `:status-history` of permission changes; (5) over-engineered for typical single-tenant kontor workloads.

**Right answer: Option B from research 25 (datalog primitive in `kontor.authz`)**, with EACL design patterns standing as a **deferred Option γ upgrade reference** if scale demands. The patterns themselves — relation-tuple shape, two-phase compile-then-traverse, composite tuple-attr indices, eid-order cursor pagination, lazy merge dedupe sort — are valuable, and EACL's `indexed.clj` is an excellent reference implementation.

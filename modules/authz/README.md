# kontor-authz

Relationship-based access control (ReBAC) for `kontor` — an
EPL-1.0, datahike-native, `:authz/*`-namespaced port of the SpiceDB /
EACL design.

## What it does

Most kontor workloads are multi-actor: an invoice has a creator + an
approver + a buyer + a seller; an expense report has an employee +
a manager + an AP clerk + an auditor. Who can see what, who can
approve what, who can post what — that's an authorization problem,
and SpiceDB / EACL / Google Zanzibar showed the answer is
relationship-based, not role-based. `kontor-authz`:

- **Implements the `IAuthorization` protocol** (`kontor.authz.core`,
  ADR-065). The SpiceDB-shaped surface: `can?` / `read-schema` /
  `write-schema!` / `read-relationships` / `write-relationships!` /
  `lookup-resources` / `lookup-subjects` / `count-resources` /
  `expand-permission-tree`. The protocol + the value types
  (`Relationship`, `RelationshipUpdate`, `ObjectRef`) are **pure**
  — no datahike dependency.
- **ReBAC primitives** (`kontor.authz.base`):
    - **Relation** — `(Relation :account :owner :user)` reads "an
      `:account` can have an `:owner` that is a `:user`". A typed
      edge definition.
    - **Permission** — a derived check: a direct relation
      (`{:relation :owner}`), an arrow through another relation
      (`{:arrow :account :permission :admin}` = "`account->admin`"),
      or a self-permission (`{:permission :other}`).
    - **Relationship** — an actual edge instance.
- **Indexed permission-graph traversal** (`kontor.authz.indexed`,
  ADR-066). `can?` walks the *schema* (relation + permission
  definitions) once to derive **paths** — every way a permission on
  a resource-type can be granted — then walks the *data*
  (`:authz.relationship/*` edges) along those paths via
  `d/index-range` scans over forward / reverse tuple indices. The
  scans are already sorted ascending by trailing ref eid;
  `kontor.authz.merge-sort` merges parallel paths into one sorted,
  deduplicated lazy seq. That eid order IS the pagination cursor.
  `lookup-resources` resumes a scan at `[… cursor-eid]`.
- **`AuthzClient` with id coercion** (`kontor.authz.client`,
  ADR-066). `make-client` wraps a datahike `conn` in a reify of
  `IAuthorization`. The traversal speaks datahike eids; the
  consumer speaks whatever external id it chose
  (`:authz/object-id` strings by default, or raw eids). The
  client coerces at the boundary.
- **Schema write/read** (`kontor.authz.schema`, #127).
  `write-schema-tx-data` is the ADR-068 pure builder; it
  `validate-schema`s the input first, throwing on unresolvable
  refs, so a consumer can install a permission schema through
  `kontor.process` composition; `read-schema` reads it back
  sorted deterministically (lex by tuple key, stable diffs).
- **Composable grant / revoke** (`grant-tx-data`, `revoke-tx-data`).
  Ergonomic single-relationship `*-tx-data` builders so a consumer
  can compose authz writes with kernel writes in ONE atomic
  `kontor.process` (e.g. "create an invoice + grant the buyer
  access to it"). The non-composing single-call wrappers
  (`do-write-relationships!`) deliberately bypass the kernel gate
  so authz can run on its own minimal datahike conn without the
  kernel schema present — see `kontor.authz.client/do-write-
  relationships!` carve-out documented in ADR-068.

## When to use it

- Multi-actor permission checks against domain objects (invoice,
  expense-report, employment, etc.)
- "Show me every invoice I can approve" enumeration queries
- "Who can post against this account?" reverse lookups
- Group-based or hierarchical permissioning (usersets)
- A standalone authz database (no kernel schema required)

When NOT to use it:
- Coarse RBAC where one role-list per user suffices — overkill
- Identity / authentication (this is authz, not authn)
- Row-level policy enforcement at the datahike layer — that's a
  datahike concern; authz here is application-layer

## Load-bearing ADRs

- [ADR-065](../../doc/decisions.md) — `IAuthorization` protocol +
  the ReBAC value types (`Relation`, `Permission`, `Relationship`)
- [ADR-066](../../doc/decisions.md) — the datahike-native indexed
  traversal + `AuthzClient` + the EACL → datahike adaptations
  (`d/entid` shim, `:max-tx` cursor, dropped LRU cache)
- Task #127 — consumer-readiness: `write-schema-tx-data` + `read-
  schema` + `grant-tx-data` / `revoke-tx-data` composable builders
- [ADR-068](../../doc/decisions.md) — `*-tx-data` builders + the
  explicit carve-out for `do-write-relationships!` (standalone
  authz conn use-case)
- Research note 41 — EACL prior-art survey + the proof that EACL
  runs on datahike unmodified

## Key namespaces

- `kontor.authz.core` — `IAuthorization` protocol + value types
  (`Relationship`, `RelationshipUpdate`, `ObjectRef`,
  `object-ref`). **PURE** — no datahike dep.
- `kontor.authz.base` — `Relation`, `Permission` constructors
  (+ `Relationship` constructor) for schema definitions
- `kontor.authz.schema` — `:authz.relation/*`,
  `:authz.permission/*`, `:authz.relationship/*` schema attrs +
  `install!` (kontor-authz schema only — no kernel dep) +
  `write-schema-tx-data` / `write-schema!` / `read-schema` +
  `validate-schema`
- `kontor.authz.indexed` — `can?`, `lookup-resources`,
  `count-resources`, `lookup-subjects`, `traverse-permission-
  path*`, `get-permission-paths`
- `kontor.authz.relationships` — `read-relationships`,
  `find-one-relationship-id`, `tx-update-relationship`
- `kontor.authz.client` — `make-client`, `AuthzClient` reify of
  `IAuthorization`, `write-relationships-tx-data`,
  `grant-tx-data`, `revoke-tx-data`
- `kontor.authz.merge-sort` — lazy sorted-merge-with-dedupe over
  the parallel index scans
- `kontor.authz.util` — `entid` helper (the `d/entid` shim)

## Minimal example

```clojure
(require '[kontor.authz.base   :as base :refer [Relation Permission Relationship]]
         '[kontor.authz.client :as client]
         '[kontor.authz.core   :as authz]
         '[kontor.authz.schema :as authz-schema]
         '[datahike.api        :as d])

;; Standalone authz conn (no kernel schema required)
(d/create-database {:store {:backend :mem :id "auth"}})
(def conn (d/connect {:store {:backend :mem :id "auth"}}))
(authz-schema/install! conn)

;; Step 1 — install a permission schema
(authz-schema/write-schema!
  conn
  [(Relation :account :owner :user)
   (Relation :account :member :user)
   (Permission :account :admin {:relation :owner})
   (Permission :account :read  {:relation :owner})
   (Permission :account :read  {:relation :member})])

;; Step 2 — make a client + grant an edge
(def c (client/make-client conn {}))     ; default :authz/object-id strings

(authz/create-relationship!
  c
  (authz/object-ref :user "alice")
  :owner
  (authz/object-ref :account "acct-1"))

;; Step 3 — check + enumerate
(authz/can? c
            (authz/object-ref :user "alice")
            :read
            (authz/object-ref :account "acct-1"))
;; => true

(authz/lookup-resources c {:subject (authz/object-ref :user "alice")
                           :permission :read
                           :resource/type :account})
;; => seq of resources alice can :read — paginated by cursor

;; Step 4 — compose authz writes with kernel writes in one tx
;; (on a conn that has both kernel + authz schemas installed)
(require '[kontor.process    :as process]
         '[kontor.validation :as validation])

(let [db (d/db conn)
      kernel-tx-data [...]
      authz-tx-data (client/grant-tx-data
                      db c
                      (authz/object-ref :user "alice") :owner
                      (authz/object-ref :invoice "INV-0001"))]
  (validation/transact-with-validation
    conn (vec (concat kernel-tx-data authz-tx-data))))
```

## What it does NOT do

- **No SpiceDB schema-string parser.** `write-schema!` takes a
  vector of `Relation` + `Permission` entity maps (built with
  `kontor.authz.base/Relation` / `Permission`); the SpiceDB
  schema-language string parser is ADR-066-deferred.
- **No `expand-permission-tree`.** ADR-066-deferred. The
  protocol method exists but throws.
- **No permission-paths LRU cache.** Dropped from the EACL port —
  a perf optimisation, not correctness. Re-add a plain `memoize`
  if profiling demands it.
- **No authn / identity / session.** This is authorization only;
  identity provider integration is consumer-side. Subjects are
  opaque ids.
- **No row-level enforcement at the datahike layer.** Authz here
  is application-layer; the consumer's read pipeline must call
  `can?` / `lookup-*` and gate accordingly.
- **No replication / mirror to an external authz service.**
  Standalone datahike conn or co-located with kernel; SpiceDB-style
  high-availability replication is out of scope.

## Tests

`modules/authz/test/kontor/authz/`:

- `schema_test.clj` — `install!`, schema attrs, `:db.unique/
  identity` upserts
- `indexed_test.clj` — `can?` + `lookup-resources` + `lookup-
  subjects` over the example schemas, cursor pagination, the
  arrow / self-permission cases, the sorted-merge dedupe
- `consumer_readiness_test.clj` (#127) — `write-schema-tx-data`
  + `read-schema` round-trip, `validate-schema` rejections,
  `grant-tx-data` / `revoke-tx-data` compositions, the
  deterministic schema sort

## License

EPL-1.0. The implementation is a faithful clean-room reimplementation
of EACL's ReBAC model (https://github.com/theronic/eacl, EPL-2.0),
written from the design — not the code — per ADR-001.

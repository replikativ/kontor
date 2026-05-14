(ns kontor.authz.schema
  "kontor-authz datahike schema — the `:authz/*` attributes (ADR-065).

   Three entity shapes, each backed by composite `:db/tupleAttrs`
   index attributes that `kontor.authz.indexed` range-scans:

   - **Relation** definitions (`:authz.relation/*`) — typed edge
     declarations. Sparse; cheap to index.
   - **Permission** definitions (`:authz.permission/*`) — derived
     checks (direct / arrow / self). Also sparse.
   - **Relationship** edges (`:authz.relationship/*`) — the actual
     access graph. The bulk of the data; the `forward` and `reverse`
     tuple indices are what make `can?` / `lookup-resources` /
     `lookup-subjects` O(log n) range-scans rather than datalog
     joins.

   ## Why tuple attributes

   Research note 41 proved datahike auto-maintains `:db/tupleAttrs`
   composite attributes, enforces `:db.unique/identity` on them, and
   `index-range` over a tuple attr with full-arity `:start`/`:end`
   bounds returns datoms **ordered by the trailing component** — for
   the relationship tuples, the trailing component is the
   subject/resource *ref eid*, so the scan yields results in stable
   eid order. That ordering IS the pagination cursor (ADR-065).

   Cohabits with the kernel + every other companion per ADR-002 —
   the `:authz/*` namespaces are reserved for this module."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :authz/object-id — the optional external-id handle
;; ============================================================================

(def ^:private object-id-attrs
  [{:db/ident       :authz/object-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Optional stable external string ID for a subject
                     / resource / definition. A consumer that exposes
                     authz to an outside system (a UI, an API) coerces
                     its own IDs to/from this; a consumer that only
                     ever passes datahike eids does not need it. Also
                     the stable handle for Relation / Permission
                     definitions."}])

;; ============================================================================
;; :authz.relation/* — typed edge definitions
;; ============================================================================

(def ^:private relation-attrs
  [{:db/ident       :authz.relation/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The resource type this relation is declared on —
                     e.g. :account in `account { relation owner }`."}

   {:db/ident       :authz.relation/relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The relation name — e.g. :owner."}

   {:db/ident       :authz.relation/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The subject type the relation points at — e.g.
                     :user in `account { relation owner: user }`."}

   {:db/ident       :authz.relation/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.relation/resource-type
                     :authz.relation/relation-name
                     :authz.relation/subject-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One relation definition per (resource-type,
                     relation-name, subject-type) — re-declaring it
                     upserts. All three members always present."}])

;; ============================================================================
;; :authz.permission/* — derived checks
;; ============================================================================

(def ^:private permission-attrs
  [{:db/ident       :authz.permission/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The resource type the permission is declared on."}

   {:db/ident       :authz.permission/permission-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The permission name — e.g. :view."}

   {:db/ident       :authz.permission/source-relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The arrow source relation — e.g. :account in
                     `view = account->admin`. `:self` for a direct or
                     self permission."}

   {:db/ident       :authz.permission/target-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "#{:relation :permission} — whether the permission
                     resolves through another relation or another
                     permission."}

   {:db/ident       :authz.permission/target-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The relation name or permission name the
                     permission resolves through."}

   {:db/ident       :authz.permission/by-resource
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.permission/resource-type
                     :authz.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Index: every permission clause on a (resource-
                     type, permission-name) — a permission can have
                     several clauses (a union)."}

   {:db/ident       :authz.permission/arrow-permission-index
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.permission/resource-type
                     :authz.permission/source-relation-name
                     :authz.permission/target-type
                     :authz.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Index: enumerate arrow-via-permission clauses."}

   {:db/ident       :authz.permission/arrow-relation-index
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.permission/resource-type
                     :authz.permission/source-relation-name
                     :authz.permission/target-type
                     :authz.permission/target-name]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Index: enumerate arrow-via-relation clauses."}

   {:db/ident       :authz.permission/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.permission/resource-type
                     :authz.permission/source-relation-name
                     :authz.permission/target-type
                     :authz.permission/target-name
                     :authz.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "One clause per (resource-type, source-relation,
                     target-type, target-name, permission-name) — the
                     full identity, re-declaring upserts."}])

;; ============================================================================
;; :authz.relationship/* — the access graph edges
;; ============================================================================

(def ^:private relationship-attrs
  [{:db/ident       :authz.relationship/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :authz.relationship/subject
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ref to the subject entity. The trailing
                     component of the `reverse` index — so a reverse
                     scan yields subjects in eid order."}

   {:db/ident       :authz.relationship/relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :authz.relationship/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :authz.relationship/resource
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Ref to the resource entity. The trailing
                     component of the `forward` index — so a forward
                     scan yields resources in eid order, which is the
                     `lookup-resources` pagination cursor."}

   {:db/ident       :authz.relationship/forward
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.relationship/subject-type
                     :authz.relationship/subject
                     :authz.relationship/relation-name
                     :authz.relationship/resource-type
                     :authz.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "The forward index — (subject-type, subject,
                     relation, resource-type, resource). `:db.unique/
                     identity`: it both dedupes relationships and is
                     the range-scan key for `lookup-resources` and
                     the forward leg of `can?`. All five members
                     always present — no nil-tuple caveat."}

   {:db/ident       :authz.relationship/reverse
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:authz.relationship/resource-type
                     :authz.relationship/resource
                     :authz.relationship/relation-name
                     :authz.relationship/subject-type
                     :authz.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "The reverse index — (resource-type, resource,
                     relation, subject-type, subject). The range-scan
                     key for `lookup-subjects` and the reverse leg of
                     `can?` arrow resolution. Indexed, not unique —
                     the `forward` tuple already enforces relationship
                     uniqueness (it carries the same five values)."}])

;; ============================================================================
;; Aggregate + installer
;; ============================================================================

(def all
  (vec (concat object-id-attrs
               relation-attrs
               permission-attrs
               relationship-attrs)))

(defn install!
  "Install the kontor-authz schema. Idempotent — the attrs upsert on
   `:db/ident`. kontor-authz has no kernel-attr dependencies (the
   relationship `:subject` / `:resource` refs point at whatever
   entities a consumer relates), so this may run any time after
   `kontor.core/install-schema!`."
  [conn]
  (d/transact conn all))

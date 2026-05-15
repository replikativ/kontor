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

;; ============================================================================
;; Schema definition write/read — the consumer-facing surface (#127)
;; ============================================================================

(defn- relation-map?
  "True iff `m` is a Relation definition entity-map (produced by
   `kontor.authz.base/Relation`)."
  [m]
  (and (map? m)
       (contains? m :authz.relation/resource-type)
       (contains? m :authz.relation/relation-name)
       (contains? m :authz.relation/subject-type)))

(defn- permission-map?
  [m]
  (and (map? m)
       (contains? m :authz.permission/resource-type)
       (contains? m :authz.permission/permission-name)
       (contains? m :authz.permission/source-relation-name)
       (contains? m :authz.permission/target-type)
       (contains? m :authz.permission/target-name)))

(defn- index-relations
  "Build a lookup map keyed by [resource-type relation-name] → the set
   of subject-types declared for that pair. Used by the validation
   pass to resolve `:relation` and `:arrow` references."
  [defs]
  (reduce (fn [acc d]
            (update acc
                    [(:authz.relation/resource-type d)
                     (:authz.relation/relation-name d)]
                    (fnil conj #{})
                    (:authz.relation/subject-type d)))
          {} (filter relation-map? defs)))

(defn- index-permissions
  "Build a lookup map keyed by [resource-type permission-name] →
   the permission map (the first one wins when several use the same
   key, mirroring the upsert-by-tuple-identity semantics)."
  [defs]
  (reduce (fn [acc d]
            (assoc acc
                   [(:authz.permission/resource-type d)
                    (:authz.permission/permission-name d)]
                   d))
          {} (filter permission-map? defs)))

(defn- validate-schema
  "Structural validation of a vector of Relation + Permission defs.
   Throws `:authz/schema-invalid` ex-info on:

   - a Permission whose `:source-relation-name` (the `:arrow`) is
     not `:self` and is not a defined Relation on the same
     resource-type;
   - a Permission whose `{:relation r}` references an undefined
     Relation on the target type;
   - a Permission whose `{:permission p}` references an undefined
     Permission on the target type.

   Cycle detection is NOT done here — that would require a graph
   walk and is documented as a known limitation (ADR-066 §note 43).
   Cyclic schemas are caught at evaluation time by the runtime
   `:visited` set in `can?` / `traverse-permission-path-*` (review-
   after fix in commit `b265200`)."
  [defs]
  (let [rels (index-relations defs)
        perms (index-permissions defs)
        errors
        (vec
         (mapcat
          (fn [p]
            (let [rt (:authz.permission/resource-type p)
                  arrow (:authz.permission/source-relation-name p)
                  tt (:authz.permission/target-type p)
                  tn (:authz.permission/target-name p)
                  errs (transient [])
                  ;; The arrow itself must resolve (unless self).
                  _ (when (and (not= arrow :self)
                               (not (contains? rels [rt arrow])))
                      (conj! errs {:permission p :error :undefined-arrow
                                   :arrow [rt arrow]}))
                  ;; Find the target-type the arrow leads to (else self).
                  target-type
                  (if (= arrow :self)
                    rt
                    (first (get rels [rt arrow] #{})))
                  ;; The target itself must resolve.
                  _ (cond
                      (nil? target-type)
                      nil  ; already flagged as :undefined-arrow above
                      (= tt :relation)
                      (when-not (contains? rels [target-type tn])
                        (conj! errs {:permission p :error :undefined-relation
                                     :ref [target-type tn]}))
                      (= tt :permission)
                      (when-not (contains? perms [target-type tn])
                        (conj! errs {:permission p :error :undefined-permission
                                     :ref [target-type tn]})))]
              (persistent! errs)))
          (filter permission-map? defs)))]
    (when (seq errors)
      (throw (ex-info "authz schema validation failed — see :errors"
                      {:type :authz/schema-invalid
                       :errors errors})))))

(defn write-schema-tx-data
  "Pure tx-data builder (ADR-068) for installing a vector of
   `Relation` + `Permission` entity maps (built via
   `kontor.authz.base/Relation` and `Permission`). Validates the
   schema structurally first — see `validate-schema` — and throws
   on any unresolvable reference before returning tx-data.

   Use as a `kontor.process` step on a conn that has the authz
   schema installed; `write-schema!` is the standalone wrapper."
  [_db schema-defs]
  (when-not (and (sequential? schema-defs) (every? map? schema-defs))
    (throw (ex-info "write-schema-tx-data: schema-defs must be a sequence of Relation / Permission entity maps"
                    {:type :authz/bad-input :got schema-defs})))
  (validate-schema schema-defs)
  (vec schema-defs))

(defn write-schema!
  "Install a vector of `Relation` + `Permission` entity maps. The
   tuple `:db.unique/identity` on `:authz.relation/identity` and
   `:authz.permission/identity` makes the write idempotent — re-
   declaring an identical Relation / Permission upserts onto the
   same entity. Validates structurally first (`validate-schema`).

   Raw `d/transact` (not gated) so authz can run on its own minimal
   datahike conn without the kernel schema present, mirroring the
   `kontor.authz.client/do-write-relationships!` carve-out
   documented in ADR-068. Composers using a kernel+authz conn can
   call `write-schema-tx-data` inside a `kontor.process` step
   instead."
  [conn schema-defs]
  (d/transact conn (write-schema-tx-data (d/db conn) schema-defs)))

(defn read-schema
  "Read the installed schema back as a `{:relations [Relation …]
   :permissions [Permission …]}` map. The entity maps round-trip
   through `write-schema!` modulo `:db/id`s. Useful for diffing,
   exporting, or driving a schema editor."
  [db]
  (let [rels (->> (d/q '[:find ?rt ?rn ?st
                         :where
                         [?e :authz.relation/resource-type ?rt]
                         [?e :authz.relation/relation-name ?rn]
                         [?e :authz.relation/subject-type ?st]]
                       db)
                  (mapv (fn [[rt rn st]]
                          {:authz.relation/resource-type rt
                           :authz.relation/relation-name rn
                           :authz.relation/subject-type st})))
        perms (->> (d/q '[:find ?rt ?pn ?src ?tt ?tn
                          :where
                          [?e :authz.permission/resource-type ?rt]
                          [?e :authz.permission/permission-name ?pn]
                          [?e :authz.permission/source-relation-name ?src]
                          [?e :authz.permission/target-type ?tt]
                          [?e :authz.permission/target-name ?tn]]
                        db)
                   (mapv (fn [[rt pn src tt tn]]
                           {:authz.permission/resource-type rt
                            :authz.permission/permission-name pn
                            :authz.permission/source-relation-name src
                            :authz.permission/target-type tt
                            :authz.permission/target-name tn})))]
    {:relations rels :permissions perms}))

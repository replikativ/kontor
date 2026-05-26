(ns kontor.authz.base
  "kontor-authz — the entity-map builders (ADR-065).

   `Relation` / `Permission` / `Relationship` turn a value-level
   description into the datahike entity map kontor-authz transacts.
   Pure — no datahike dependency, just map construction. Ported from
   EACL's `eacl.datomic.impl.base` (research note 41).

   The component attributes these emit auto-compute the tuple index
   attributes (`:authz.relation/identity`, `:authz.relationship/
   forward`, …) that `kontor.authz.indexed` range-scans — see
   `kontor.authz.schema`. (Pagination cursors flow as plain maps
   `{:resource …}` / `{:subject …}` — no cursor record.)"
  (:require [clojure.core]))

;; ============================================================================
;; Relation — a typed edge definition
;; ============================================================================

(defn ->relation-id
  "Stable `:authz/object-id` for a relation definition. `(str kw)`
   keeps namespaces; the leading colons are intentional."
  [resource-type relation-name subject-type]
  (str "authz.relation:" resource-type ":" relation-name ":" subject-type))

(defn Relation
  "Define a relation type — a typed edge the access graph can carry.

     (Relation :account :owner :user)   ; account { relation owner: user }
     (Relation :kontor.account/owner :user)    ; the namespaced-keyword form

   `:self` is reserved (it is the implicit source of a
   self-permission), so it may not be a resource-type or a
   relation-name.

   `subject-type` must sort within `:a`..`:z` — `kontor.authz.indexed`
   range-scans subject-types with `:a` / `:z` keyword sentinels, so a
   subject-type outside that range (`:Account` — uppercase sorts
   before `:a`; `:zebra` — past `:z`; `:2fa-*` — digit-leading) would
   be *silently missed* by `can?` / `lookup-*` (review-after P1).
   This throws at definition time instead — a loud error beats a
   silent wrong `false`."
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type)
          (keyword? relation-name)
          (keyword? subject-type)
          (not= resource-type :self)
          (not= relation-name :self)]}
   (when-not (and (<= 0 (compare subject-type :a))
                  (<= (compare subject-type :z) 0))
     (throw (ex-info "Relation: :subject-type must sort within :a..:z — kontor.authz.indexed range-scans subject-types with :a/:z sentinels; a type outside that range is silently missed by can?/lookup-* (ADR-066 review-after P1). Rename the type."
                     {:type :authz/subject-type-out-of-range
                      :subject-type subject-type})))
   {:authz/object-id             (->relation-id resource-type relation-name
                                                subject-type)
    :authz.relation/resource-type resource-type
    :authz.relation/relation-name relation-name
    :authz.relation/subject-type  subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name)
          (namespace resource-type+relation-name)
          (keyword? subject-type)]}
   (Relation (keyword (namespace resource-type+relation-name))
             (keyword (name resource-type+relation-name))
             subject-type)))

;; ============================================================================
;; Permission — a derived check
;; ============================================================================

(defn ->permission-id
  "Stable `:authz/object-id` for a permission definition."
  [resource-type permission-name arrow target-type relation-or-permission]
  (str "authz.permission:" resource-type ":" permission-name ":" arrow ":"
       target-type ":" relation-or-permission))

(defn Permission
  "Define a permission via `(Permission resource-type permission-name
   spec)`, where `spec` is one of:

     {:relation r}              ; direct       — permission p = r
     {:arrow a :relation r}     ; arrow-rel    — permission p = a->r
     {:arrow a :permission p2}  ; arrow-perm   — permission p = a->p2
     {:permission p2}           ; self         — permission p = p2
                                ;   (the omitted :arrow is :self)

   Modelling the SpiceDB definition

     definition account { relation owner: user
                          permission admin = owner }
     definition product { relation account: account
                          permission view  = account->admin }

   is:

     (Relation   :account :owner :user)
     (Relation   :product :account :account)
     (Permission :account :admin {:relation :owner})
     (Permission :product :view  {:arrow :account :permission :admin})

   Cycle detection in the schema is NOT done here (a known limitation
   inherited from the EACL model — ADR-065)."
  [resource-type permission-name
   {:as spec :keys [arrow relation permission] :or {arrow :self}}]
  {:pre [(keyword? resource-type)
         (keyword? permission-name)
         (map? spec)
         (or relation permission)
         (not (and relation permission))]}
  (cond
    relation
    {:authz/object-id                      (->permission-id resource-type
                                                            permission-name
                                                            arrow :relation
                                                            relation)
     :authz.permission/resource-type        resource-type
     :authz.permission/permission-name      permission-name
     :authz.permission/source-relation-name arrow
     :authz.permission/target-type          :relation
     :authz.permission/target-name          relation}

    permission
    {:authz/object-id                      (->permission-id resource-type
                                                            permission-name
                                                            arrow :permission
                                                            permission)
     :authz.permission/resource-type        resource-type
     :authz.permission/permission-name      permission-name
     :authz.permission/source-relation-name arrow
     :authz.permission/target-type          :permission
     :authz.permission/target-name          permission}

    :else
    (throw (ex-info "Invalid Permission spec — expected one of {:relation n}, {:permission n}, {:arrow a :relation n}, {:arrow a :permission n}"
                    {:spec spec}))))

;; ============================================================================
;; Relationship — an actual edge instance
;; ============================================================================

(defn Relationship
  "Build the entity map for one relationship edge between `subject`
   and `resource` via `relation-name`. `subject` / `resource` are
   `{:keys [type id]}` (an `ObjectRef`, or any such map). The `:id`s
   are datahike entity references — eids, lookup-refs, or tempids."
  [subject relation-name resource]
  {:pre [(:id subject)
         (:type subject)
         (keyword? relation-name)
         (:id resource)
         (:type resource)]}
  {:authz.relationship/resource-type (:type resource)
   :authz.relationship/resource      (:id resource)
   :authz.relationship/relation-name relation-name
   :authz.relationship/subject-type  (:type subject)
   :authz.relationship/subject       (:id subject)})

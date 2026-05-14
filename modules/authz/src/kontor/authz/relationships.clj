(ns kontor.authz.relationships
  "kontor-authz — relationship-edge CRUD (ADR-066).

   Reading + writing the `:authz.relationship/*` edges. The traversal
   (`kontor.authz.indexed`) is read-only over these edges; this
   namespace is how they get created, touched, deleted, and queried.
   Ported from the relationship half of EACL's `eacl.datomic.impl`
   (research note 41).

   `tx-update-relationship` is pure (`db` → tx-data); the client
   composes the fragments and transacts. The one EACL→datahike fix:
   `:delete` emits `[:db/retractEntity …]` (datahike), not Datomic's
   `[:db.fn/retractEntity …]`."
  (:require [datahike.api :as d]
            [kontor.authz.base :as base]
            [kontor.authz.core :as core :refer [map->Relationship]]))

(defn- entid
  "Resolve `x` (eid or lookup-ref) to an eid via `d/entity`."
  [db x]
  (cond
    (number? x) x
    (nil? x)    nil
    :else       (:db/id (d/entity db x))))

;; ============================================================================
;; read-relationships
;; ============================================================================

(defn- filters->args
  "Order matters — maps to the dynamic `:in` of `build-query`."
  [filters]
  (->> [(:resource/type filters)
        (:resource/id filters)
        (:resource/relation filters)
        (:subject/type filters)
        (:subject/id filters)]
       (remove nil?)
       vec))

(defn- build-query
  "Build a datalog query for relationship edges from a filter map.
   At least one of `:resource/type`, `:resource/id`,
   `:resource/relation`, `:subject/type`, `:subject/id` is required."
  [filters]
  {:pre [(some some? (vals (select-keys filters
                                        [:resource/type :resource/id
                                         :resource/relation :subject/type
                                         :subject/id])))]}
  {:find  '[?resource-type ?resource ?resource-relation ?subject-type ?subject]
   :keys  '[resource/type resource/id resource/relation subject/type subject/id]
   :in    (cond-> ['$]
            (:resource/type filters)     (conj '?resource-type)
            (:resource/id filters)       (conj '?resource)
            (:resource/relation filters) (conj '?resource-relation)
            (:subject/type filters)      (conj '?subject-type)
            (:subject/id filters)        (conj '?subject))
   :where '[[?rel :authz.relationship/resource ?resource]
            [?rel :authz.relationship/resource-type ?resource-type]
            [?rel :authz.relationship/relation-name ?resource-relation]
            [?rel :authz.relationship/subject ?subject]
            [?rel :authz.relationship/subject-type ?subject-type]]})

(defn- row->Relationship
  [{rt :resource/type rid :resource/id rrel :resource/relation
    st :subject/type sid :subject/id}]
  (map->Relationship
   {:subject  (core/object-ref st sid)
    :relation rrel
    :resource (core/object-ref rt rid)}))

(defn read-relationships
  "Query relationship edges. `filters` keys: `:subject/type`,
   `:subject/id`, `:resource/type`, `:resource/id`,
   `:resource/relation`. `:subject/id` / `:resource/id` are eids
   (the client coerces external ids first). Returns a seq of
   `core/Relationship` records (with eid subject/resource ids)."
  [db filters]
  (->> (apply d/q (build-query filters) db (filters->args filters))
       (map row->Relationship)))

;; ============================================================================
;; find / write
;; ============================================================================

(defn find-one-relationship-id
  "The eid of the relationship edge matching `relationship` exactly,
   or nil. `relationship` is `{:keys [subject relation resource]}`
   with `:id`s that are eids or lookup-refs."
  [db {:keys [subject relation resource]}]
  (let [subject-eid  (entid db (:id subject))
        resource-eid (entid db (:id resource))]
    (when (and subject-eid resource-eid)
      (->> (d/datoms db :avet :authz.relationship/forward
                     [(:type subject) subject-eid relation
                      (:type resource) resource-eid])
           (map :e)
           first))))

(defn tx-update-relationship
  "Pure: turn a `RelationshipUpdate` `{:keys [operation
   relationship]}` into tx-data (or nil for a no-op `:delete`).
     :create — the edge entity map; throws if it already exists.
     :touch  — the edge entity map, upserted onto an existing edge.
     :delete — `[:db/retractEntity eid]`, or nil if not found."
  [db {:keys [operation relationship]}]
  (let [{:keys [subject relation resource]} relationship]
    (case operation
      :touch
      (let [rel-id (find-one-relationship-id db relationship)]
        (cond-> (base/Relationship subject relation resource)
          rel-id (assoc :db/id rel-id)))

      :create
      (if (find-one-relationship-id db relationship)
        (throw (ex-info "create-relationship!: relationship already exists"
                        {:relationship relationship}))
        (base/Relationship subject relation resource))

      :delete
      (when-let [rel-id (find-one-relationship-id db relationship)]
        [:db/retractEntity rel-id])

      (throw (ex-info "unsupported relationship-update operation"
                      {:operation operation})))))

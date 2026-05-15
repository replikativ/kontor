(ns kontor.authz.client
  "kontor-authz — the `IAuthorization` client (ADR-066).

   `make-client` wraps a datahike `conn` in an `AuthzClient` that
   reifies `kontor.authz.core/IAuthorization`. The client's job is
   **id coercion** + dispatch: the traversal (`kontor.authz.indexed`)
   and the edge CRUD (`kontor.authz.relationships`) speak datahike
   eids; a consumer speaks whatever external id it chose
   (`:authz/object-id` strings by default, or raw eids). The client
   coerces at the boundary.

   Ported from EACL's `eacl.datomic.core` (research note 41). The
   EACL→datahike fixes: `d/entid` → an `entid` helper via
   `d/entity`; `d/basis-t` → `:max-tx`; `d/transact` returns the
   report directly (no deref).

   `read-schema` / `write-schema!` / `expand-permission-tree` are
   ADR-066-deferred — `write-schema!` wants the SpiceDB-string
   parser, a later unit.

   ## Configuring id coercion

     ;; default — subjects/resources keyed by :authz/object-id strings
     (make-client conn {})

     ;; raw datahike eids, no external-id layer
     (make-client conn {:entity->object-id :db/id
                        :object-id->ident  identity})"
  (:require [datahike.api :as d]
            [kontor.authz.core :as core
             :refer [IAuthorization object-ref ->Relationship
                     ->RelationshipUpdate]]
            [kontor.authz.indexed :as indexed]
            [kontor.authz.relationships :as rels]
            [kontor.authz.util :refer [entid]]))

;; ============================================================================
;; id coercion
;; ============================================================================

(defn- coerce-object-in
  "External object → internal: replace `:id` with the datahike eid."
  [object-id->entid db obj]
  (when obj (update obj :id #(object-id->entid db %))))

(defn- coerce-object-out
  "Internal object → external: replace the eid `:id` with the
   external object-id."
  [entid->object-id db {:keys [type id relation]}]
  (object-ref type (entid->object-id db id) relation))

(defn- coerce-cursor-in
  "External cursor → internal — coerce the `:resource` / `:subject`
   object-ref's id to an eid."
  [object-id->entid db cursor]
  (cond-> cursor
    (:resource cursor) (update :resource #(coerce-object-in object-id->entid db %))
    (:subject cursor)  (update :subject  #(coerce-object-in object-id->entid db %))))

(defn- coerce-cursor-out
  [entid->object-id db cursor]
  (cond-> cursor
    (:resource cursor) (update :resource #(when % (coerce-object-out
                                                   entid->object-id db %)))
    (:subject cursor)  (update :subject  #(when % (coerce-object-out
                                                   entid->object-id db %)))))

;; ============================================================================
;; the operations (db / conn + the coercion fns + the query)
;; ============================================================================

(defn- do-can?
  [db {:keys [object-id->entid]} subject permission resource]
  (indexed/can? db
                (coerce-object-in object-id->entid db subject)
                permission
                (coerce-object-in object-id->entid db resource)))

(defn- do-lookup-resources
  [db {:keys [object-id->entid entid->object-id]} query]
  (let [internal (-> query
                     (update :subject #(coerce-object-in object-id->entid db %))
                     (update :cursor  #(when % (coerce-cursor-in
                                                object-id->entid db %))))
        {:keys [data cursor]} (indexed/lookup-resources db internal)]
    {:data   (mapv #(coerce-object-out entid->object-id db %) data)
     :cursor (coerce-cursor-out entid->object-id db cursor)}))

(defn- do-count-resources
  [db {:keys [object-id->entid entid->object-id]} query]
  (let [internal (-> query
                     (update :subject #(coerce-object-in object-id->entid db %))
                     (update :cursor  #(when % (coerce-cursor-in
                                                object-id->entid db %))))
        result   (indexed/count-resources db internal)]
    (update result :cursor #(coerce-cursor-out entid->object-id db %))))

(defn- do-lookup-subjects
  [db {:keys [object-id->entid entid->object-id]} query]
  (let [internal (-> query
                     (update :resource #(coerce-object-in object-id->entid db %))
                     (update :cursor   #(when % (coerce-cursor-in
                                                 object-id->entid db %))))
        {:keys [data cursor]} (indexed/lookup-subjects db internal)]
    {:data   (mapv #(coerce-object-out entid->object-id db %) data)
     :cursor (coerce-cursor-out entid->object-id db cursor)}))

(defn- coerce-id-filter
  "Coerce an id filter (`:subject/id` / `:resource/id`) to an eid. A
   present-but-unresolvable id THROWS — silently dropping it would
   leave the datalog var unbound and match *every* relationship (a
   confused-deputy over-return on a security-sensitive read)."
  [object-id->entid db filters k]
  (if (contains? filters k)
    (let [eid (object-id->entid db (get filters k))]
      (when (nil? eid)
        (throw (ex-info (str "read-relationships: " k " does not resolve to an entity")
                        {:type :authz/unresolvable-filter
                         :filter k :value (get filters k)})))
      (assoc filters k eid))
    filters))

(defn- do-read-relationships
  [db {:keys [object-id->entid entid->object-id]} filters]
  (let [internal (->> :subject/id
                      (coerce-id-filter object-id->entid db filters)
                      (#(coerce-id-filter object-id->entid db % :resource/id)))]
    (->> (rels/read-relationships db internal)
         (map (fn [{:keys [subject relation resource]}]
                (core/->Relationship
                 (coerce-object-out entid->object-id db subject)
                 relation
                 (coerce-object-out entid->object-id db resource)))))))

(defn write-relationships-tx-data
  "Pure tx-data builder for authz relationship writes (ADR-068).
   Translates the API-level `updates` (each a `{:operation :create/
   :delete :touch :relationship {...}}`) into datahike tx-data via
   `rels/tx-update-relationship`. Use this to compose authz writes
   with kernel writes in one atomic `kontor.process` — e.g. create
   an invoice + grant the buyer access to it in one tx."
  [db opts updates]
  (->> updates
       (map (fn [{:keys [operation relationship]}]
              (let [{:keys [subject relation resource]} relationship]
                {:operation operation
                 :relationship
                 {:subject  (coerce-object-in (:object-id->entid opts) db subject)
                  :relation relation
                  :resource (coerce-object-in (:object-id->entid opts) db resource)}})))
       (map #(rels/tx-update-relationship db %))
       (remove nil?)
       vec))

(defn- do-write-relationships!
  [conn opts updates]
  (let [report (d/transact conn (write-relationships-tx-data (d/db conn) opts updates))]
    {:tx-report   report
     :authz/token (str (:max-tx (:db-after report)))}))

;; ============================================================================
;; the client
;; ============================================================================

(defrecord AuthzClient [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (do-can? (d/db conn) opts subject permission resource))
  (can? [_ subject permission resource _consistency]
    (do-can? (d/db conn) opts subject permission resource))
  (can? [_ {:keys [subject permission resource]}]
    (do-can? (d/db conn) opts subject permission resource))

  (read-schema [_]
    (throw (ex-info "read-schema not implemented (ADR-066-deferred)" {})))
  (write-schema! [_ _schema]
    (throw (ex-info "write-schema! not implemented (ADR-066-deferred — wants the spice-parser)" {})))

  (read-relationships [_ filters]
    (do-read-relationships (d/db conn) opts filters))

  (write-relationships! [_ updates]
    (do-write-relationships! conn opts updates))

  (write-relationship! [_ operation subject relation resource]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               operation
                               (->Relationship subject relation resource))]))
  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               operation
                               (->Relationship subject relation resource))]))

  (create-relationships! [_ relationships]
    (do-write-relationships! conn opts
                             (for [r relationships]
                               (->RelationshipUpdate :create r))))
  (create-relationship! [_ subject relation resource]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               :create (->Relationship subject relation
                                                       resource))]))
  (create-relationship! [_ {:keys [subject relation resource]}]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               :create (->Relationship subject relation
                                                       resource))]))

  (delete-relationships! [_ relationships]
    (do-write-relationships! conn opts
                             (for [r relationships]
                               (->RelationshipUpdate :delete r))))
  (delete-relationship! [_ subject relation resource]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               :delete (->Relationship subject relation
                                                       resource))]))
  (delete-relationship! [_ {:keys [subject relation resource]}]
    (do-write-relationships! conn opts
                             [(->RelationshipUpdate
                               :delete (->Relationship subject relation
                                                       resource))]))

  (lookup-resources [_ query]
    (do-lookup-resources (d/db conn) opts query))
  (count-resources [_ query]
    (do-count-resources (d/db conn) opts query))
  (lookup-subjects [_ query]
    (do-lookup-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _query]
    (throw (ex-info "expand-permission-tree not implemented (ADR-066-deferred)" {}))))

(defn make-client
  "Wrap a datahike `conn` in an `IAuthorization` client.

   Opts (all optional):
     :entity->object-id  (fn [entity] → object-id) — how to read a
                         subject/resource's external id from its
                         pulled entity. Default `:authz/object-id`.
     :object-id->ident   (fn [object-id] → eid|lookup-ref) — how to
                         resolve an external id to something datahike
                         can look up. Default
                         `(fn [oid] [:authz/object-id oid])`.

   For raw datahike eids and no external-id layer:
     (make-client conn {:entity->object-id :db/id
                        :object-id->ident  identity})"
  ([conn] (make-client conn {}))
  ([conn {:keys [entity->object-id object-id->ident]
          :or   {entity->object-id #(:authz/object-id %)
                 object-id->ident  (fn [oid] [:authz/object-id oid])}}]
   (let [object-id->entid (fn [db object-id]
                            (entid db (object-id->ident object-id)))
         entid->object-id (fn [db eid]
                            (entity->object-id (d/entity db eid)))]
     (->AuthzClient conn {:object-id->entid object-id->entid
                          :entid->object-id entid->object-id}))))

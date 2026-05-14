(ns kontor.authz.indexed
  "kontor-authz — the permission-graph traversal (ADR-066).

   The hot path: `can?`, `lookup-resources`, `lookup-subjects`,
   `count-resources`. A datahike-native port of EACL's
   `eacl.datomic.impl.indexed` (research note 41).

   ## How it works

   1. `get-permission-paths` walks the *schema* (`:authz.relation/*`
      + `:authz.permission/*` definitions) and builds a tree of
      **paths** — every way `permission` on `resource-type` can be
      granted: a direct relation, an arrow through another relation
      or permission, or a self-permission. Schema-only, cheap.
   2. The `traverse-*` fns walk the *data* (`:authz.relationship/*`
      edges) along those paths via `index-range` scans over the
      `forward` / `reverse` tuple indices. Each scan is already
      sorted ascending by the trailing ref eid (research note 41);
      `kontor.authz.merge-sort` merges the parallel paths' scans into
      one sorted, deduplicated lazy seq.
   3. That eid order IS the pagination cursor — `lookup-resources`
      resumes a scan at `[… cursor-eid]`.

   ## Datahike adaptations from the EACL source

   - `d/index-range` — datahike's is map-arg `{:attrid :start :end}`;
     `idx-range` here bridges the Datomic positional form.
   - `d/entid` — datahike has none; `entid` resolves an eid /
     lookup-ref via `d/entity`.
   - the `:db.fn/...` / cache / logging deps are dropped: the LRU
     `permission-paths-cache` is gone (a perf optimisation, not
     correctness — `get-permission-paths` calls `calc-permission-
     paths` directly; re-add a plain memoize if profiling demands
     it), and the `log/warn` diagnostics for missing schema defs
     are dropped (the fns still return `[]`).

   ## Known limitation (inherited from the EACL model)

   `calc-permission-paths` and `traverse-permission-path` carry
   visited-sets for cycle detection, but `traverse-permission-path-
   reverse`'s `:self-permission` branch does not — a cyclic
   permission *schema* could loop it. kontor's schemas are authored,
   not user-generated, so this is low-risk; ADR-066 flags it for the
   review-after."
  (:require [datahike.api :as d]
            [kontor.authz.core :as core :refer [object-ref]]
            [kontor.authz.merge-sort :as ms]))

;; ============================================================================
;; Datahike adaptation helpers
;; ============================================================================

(defn- idx-range
  "Datomic-positional `index-range` over datahike's map-arg form."
  [db attrid start end]
  (d/index-range db {:attrid attrid :start start :end end}))

(defn- entid
  "Resolve `x` (an eid or a lookup-ref) to an eid. datahike has no
   `d/entid`; an eid passes through, anything else resolves via
   `d/entity`."
  [db x]
  (cond
    (number? x) x
    (nil? x)    nil
    :else       (:db/id (d/entity db x))))

;; ============================================================================
;; Tuple-datom unpackers
;; ============================================================================

(defn- extract-resource-id
  "The trailing (resource) eid of a `:authz.relationship/forward`
   tuple datom — `[subject-type subject relation resource-type
   resource]`."
  [{v :v}]
  (let [[_st _se _rn _rt resource-eid] v] resource-eid))

(defn- extract-subject-id
  "The trailing (subject) eid of a `:authz.relationship/reverse`
   tuple datom — `[resource-type resource relation subject-type
   subject]`."
  [{v :v}]
  (let [[_rt _re _rn _st subject-eid] v] subject-eid))

;; ============================================================================
;; Schema walk — build the permission paths
;; ============================================================================

(defn relation-datoms
  "Lazy seq of `:authz.relation/identity` datoms for a (resource-type,
   relation-name) — one per declared subject-type. The `:a`/`:z`
   keyword sentinels span every subject-type; this requires
   subject-type keywords to sort within `:a … :z` (kontor's type
   vocabulary does)."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (idx-range db :authz.relation/identity
               [resource-type relation-name :a]
               [resource-type relation-name :z])
    (throw (ex-info "relation-datoms: resource-type + relation-name required"
                    {:resource-type resource-type :relation-name relation-name}))))

(defn find-permission-defs
  "Every `:authz.permission` clause granting `permission-name` on
   `resource-type` — a vector of pulled permission maps (a permission
   can have several union clauses)."
  [db resource-type permission-name]
  (->> (d/datoms db :avet :authz.permission/by-resource
                 [resource-type permission-name])
       (map :e)
       (map #(d/pull db '[*] %))
       vec))

(defn- resolve-self-relation
  "The `:relation` path map(s) for a target relation on a resource
   type — one per declared subject-type. `[]` if the relation is not
   defined (a soft failure — no path granted)."
  [db resource-type target-relation-name]
  (->> (relation-datoms db resource-type target-relation-name)
       (map (fn [datom]
              {:type         :relation
               :name         target-relation-name
               :subject-type (nth (:v datom) 2)
               :relation-eid (:e datom)}))))

(defn calc-permission-paths
  "Recursively build the paths granting `permission-name` on
   `resource-type`. A path is one of:
     {:type :relation        :name :subject-type :relation-eid}
     {:type :arrow           :via :target-type :sub-paths …}
     {:type :self-permission :target-permission :resource-type}
   `visited-perms` is the cycle guard — a permission clause already
   on the recursion stack yields no path."
  ([db resource-type permission-name]
   (calc-permission-paths db resource-type permission-name #{}))
  ([db resource-type permission-name visited-perms]
   (let [perm-defs       (find-permission-defs db resource-type permission-name)
         updated-visited (into visited-perms (map :db/id) perm-defs)]
     (->> perm-defs
          (mapcat
           (fn [{perm-eid :db/id
                 :authz.permission/keys [source-relation-name
                                         target-type target-name]}]
             (cond
               (contains? visited-perms perm-eid)
               []                                          ; cycle — no path

               (= :self source-relation-name)
               (case target-type
                 :relation   (resolve-self-relation db resource-type target-name)
                 :permission [{:type              :self-permission
                               :target-permission target-name
                               :resource-type     resource-type}])

               :else
               (let [datoms (relation-datoms db resource-type source-relation-name)]
                 (mapcat
                  (fn [datom]
                    (let [intermediate-type (nth (:v datom) 2)
                          via-relation-eid  (:e datom)
                          sub-paths
                          (case target-type
                            :permission
                            (calc-permission-paths db intermediate-type
                                                   target-name updated-visited)
                            :relation
                            (->> (relation-datoms db intermediate-type target-name)
                                 (map (fn [td]
                                        {:type         :relation
                                         :name         target-name
                                         :subject-type (nth (:v td) 2)
                                         :relation-eid (:e td)}))))]
                      (if (seq sub-paths)
                        [{:type              :arrow
                          :via               source-relation-name
                          :target-type       intermediate-type
                          :via-relation-eid  via-relation-eid
                          :target-permission (when (= target-type :permission)
                                               target-name)
                          :target-relation   (when (= target-type :relation)
                                                target-name)
                          :sub-paths         sub-paths}]
                        [])))
                  datoms)))))
          vec))))

(defn get-permission-paths
  "The paths granting `permission-name` on `resource-type`. Schema-
   derived; ADR-066 v1 has no path cache (a deferred perf
   optimisation — see the ns docstring)."
  [db resource-type permission-name]
  (calc-permission-paths db resource-type permission-name))

;; ============================================================================
;; can? — true as soon as any path grants the permission
;; ============================================================================

(defn can?
  "True iff `subject` has `permission` on `resource`. `subject` /
   `resource` are `{:keys [type id]}` — `:id` an eid or lookup-ref.
   Short-circuits on the first granting path."
  [db subject permission resource]
  (let [{subject-type :type subject-id :id}   subject
        {resource-type :type resource-id :id} resource
        subject-eid  (entid db subject-id)
        resource-eid (entid db resource-id)
        paths        (get-permission-paths db resource-type permission)]
    (boolean
     (and subject-eid resource-eid
          (some
           (fn [path]
             (case (:type path)
               :relation
               (when (= subject-type (:subject-type path))
                 (seq (d/datoms db :avet :authz.relationship/forward
                                [subject-type subject-eid (:name path)
                                 resource-type resource-eid])))

               :self-permission
               (can? db subject (:target-permission path) resource)

               :arrow
               (let [via-relation      (:via path)
                     intermediate-type (:target-type path)
                     intermediates
                     (->> (idx-range db :authz.relationship/reverse
                                     [resource-type resource-eid via-relation
                                      intermediate-type 0]
                                     [resource-type resource-eid via-relation
                                      intermediate-type Long/MAX_VALUE])
                          (map extract-subject-id))]
                 (if-let [target-relation (:target-relation path)]
                   ;; arrow → relation: subject must have target-relation
                   ;; to some intermediate connected to the resource.
                   (some (fn [intermediate-eid]
                           (seq (d/datoms db :avet :authz.relationship/forward
                                          [subject-type subject-eid
                                           target-relation intermediate-type
                                           intermediate-eid])))
                         intermediates)
                   ;; arrow → permission: recurse on each intermediate.
                   (let [target-permission (:target-permission path)]
                     (some (fn [intermediate-eid]
                             (can? db subject target-permission
                                   (object-ref intermediate-type intermediate-eid)))
                           intermediates))))))
           paths)))))

;; ============================================================================
;; lookup-resources — every resource a known subject can reach
;; ============================================================================

(declare traverse-permission-path)

(defn traverse-permission-path-via-subject
  "Walk one `path` from a known subject — a lazy seq of resource eids,
   sorted ascending, resumable from `cursor-eid`."
  [db subject-type subject-eid path resource-type cursor-eid]
  (case (:type path)
    :relation
    (when (= subject-type (:subject-type path))
      (->> (idx-range db :authz.relationship/forward
                      [subject-type subject-eid (:name path) resource-type
                       (or cursor-eid 0)]
                      [subject-type subject-eid (:name path) resource-type
                       Long/MAX_VALUE])
           (map extract-resource-id)
           (filter (fn [rid] (and rid (> rid (or cursor-eid 0)))))))

    :self-permission
    (->> (traverse-permission-path db subject-type subject-eid
                                   (:target-permission path)
                                   resource-type cursor-eid #{})
         (map first)
         (filter (fn [rid] (and rid (> rid (or cursor-eid 0))))))

    :arrow
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if-let [target-relation (:target-relation path)]
        ;; arrow → relation: intermediates the subject has
        ;; target-relation to, then resources via via-relation.
        (let [intermediate-eids
              (->> (idx-range db :authz.relationship/forward
                              [subject-type subject-eid target-relation
                               intermediate-type 0]
                              [subject-type subject-eid target-relation
                               intermediate-type Long/MAX_VALUE])
                   (map extract-resource-id)
                   (filter some?))
              resource-seqs
              (map (fn [intermediate-eid]
                     (->> (idx-range db :authz.relationship/forward
                                     [intermediate-type intermediate-eid
                                      via-relation resource-type
                                      (or cursor-eid 0)]
                                     [intermediate-type intermediate-eid
                                      via-relation resource-type
                                      Long/MAX_VALUE])
                          (map extract-resource-id)
                          (filter (fn [rid]
                                    (and rid (> rid (or cursor-eid 0)))))))
                   intermediate-eids)]
          (if (seq resource-seqs)
            (ms/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
            []))
        ;; arrow → permission: intermediates the subject has the
        ;; target permission on, then resources via via-relation.
        (let [target-permission (:target-permission path)
              intermediate-eids (->> (traverse-permission-path
                                      db subject-type subject-eid
                                      target-permission intermediate-type
                                      nil #{})
                                     (map first))
              resource-seqs
              (->> intermediate-eids
                   (map (fn [intermediate-eid]
                          (->> (idx-range db :authz.relationship/forward
                                          [intermediate-type intermediate-eid
                                           via-relation resource-type
                                           (or cursor-eid 0)]
                                          [intermediate-type intermediate-eid
                                           via-relation resource-type
                                           Long/MAX_VALUE])
                               (map extract-resource-id)
                               (filter (fn [rid]
                                         (> rid (or cursor-eid 0)))))))) ]
          (if (seq resource-seqs)
            (ms/lazy-fold2-merge-dedupe-sorted-by identity resource-seqs)
            []))))))

(defn traverse-permission-path
  "Bidirectional walk of one permission from a known subject. Returns
   a lazy seq of `[resource-eid path]` tuples (sorted by resource-eid).
   `visited-paths` guards traversal cycles."
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name
                             resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid
    visited-paths]
   (let [path-key [subject-type subject-eid permission-name resource-type]]
     (if (contains? visited-paths path-key)
       []
       (let [updated-visited (conj visited-paths path-key)
             paths (get-permission-paths db resource-type permission-name)]
         (->> paths
              (map
               (fn [{:as path path-type :type}]
                 (case path-type
                   :relation
                   (when (= subject-type (:subject-type path))
                     (->> (idx-range db :authz.relationship/forward
                                     [subject-type subject-eid (:name path)
                                      resource-type 0]
                                     [subject-type subject-eid (:name path)
                                      resource-type Long/MAX_VALUE])
                          (map (fn [datom]
                                 (let [rid (extract-resource-id datom)]
                                   (when (> rid (or cursor-eid 0))
                                     [rid path]))))
                          (filter some?)))

                   :self-permission
                   (traverse-permission-path db subject-type subject-eid
                                             (:target-permission path)
                                             resource-type cursor-eid
                                             updated-visited)

                   :arrow
                   (let [via-relation      (:via path)
                         intermediate-type (:target-type path)]
                     (if-let [target-relation (:target-relation path)]
                       (let [intermediate-eids
                             (->> (idx-range db :authz.relationship/forward
                                             [subject-type subject-eid
                                              target-relation intermediate-type 0]
                                             [subject-type subject-eid
                                              target-relation intermediate-type
                                              Long/MAX_VALUE])
                                  (map extract-resource-id))
                             resource-seqs
                             (map (fn [intermediate-eid]
                                    (->> (idx-range db :authz.relationship/forward
                                                    [intermediate-type
                                                     intermediate-eid via-relation
                                                     resource-type 0]
                                                    [intermediate-type
                                                     intermediate-eid via-relation
                                                     resource-type Long/MAX_VALUE])
                                         (map extract-resource-id)
                                         (filter #(> % (or cursor-eid 0)))
                                         (map (fn [rid] [rid path]))))
                                  intermediate-eids)]
                         (if (seq resource-seqs)
                           (ms/lazy-fold2-merge-dedupe-sorted-by first resource-seqs)
                           []))
                       (let [target-permission (:target-permission path)
                             intermediate-eids
                             (->> (traverse-permission-path
                                   db subject-type subject-eid target-permission
                                   intermediate-type nil updated-visited)
                                  (map first))
                             resource-seqs
                             (->> intermediate-eids
                                  (map (fn [intermediate-eid]
                                         (->> (idx-range db :authz.relationship/forward
                                                         [intermediate-type
                                                          intermediate-eid via-relation
                                                          resource-type 0]
                                                         [intermediate-type
                                                          intermediate-eid via-relation
                                                          resource-type Long/MAX_VALUE])
                                              (map extract-resource-id)
                                              (filter #(> % (or cursor-eid 0)))
                                              (map (fn [rid] [rid path]))))))]
                         (if (seq resource-seqs)
                           (ms/lazy-fold2-merge-dedupe-sorted-by first resource-seqs)
                           [])))))))
              (filter some?)
              (ms/lazy-fold2-merge-dedupe-sorted-by first)))))))

(defn- lazy-merged-lookup-resources
  "Merge every path's resource-eid seq into one sorted, deduplicated
   lazy seq."
  [db {:keys [subject permission resource/type cursor]}]
  (let [{subject-type :type subject-id :id} subject
        subject-eid (entid db subject-id)
        cursor-eid  (:id (:resource cursor))
        path-seqs   (->> (get-permission-paths db type permission)
                         (keep (fn [path]
                                 (let [rs (traverse-permission-path-via-subject
                                           db subject-type subject-eid
                                           path type cursor-eid)]
                                   (when (seq rs) rs)))))]
    (if (seq path-seqs)
      (ms/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
      [])))

(defn lookup-resources
  "Every resource `subject` has `permission` on. Cursor-paginated.
   `:limit` default 1000; pass `:limit -1` for all. Returns
   `{:data [object-ref …] :cursor {:resource object-ref}}`."
  [db {:as query :keys [resource/type limit cursor] :or {limit 1000}}]
  (let [merged  (lazy-merged-lookup-resources db query)
        limited (if (>= limit 0) (take limit merged) merged)
        resources (map #(object-ref type %) limited)
        last-resource (last resources)]
    {:data   resources
     :cursor {:resource (or last-resource (:resource cursor))}}))

(defn count-resources
  "Count of `lookup-resources` — enumerates from the cursor.
   `:limit` default -1 (all). Returns `{:count :limit :cursor}`."
  [db {:as query :keys [limit cursor] resource-type :resource/type
       :or {limit -1}}]
  (let [merged  (lazy-merged-lookup-resources db query)
        limited (if (>= limit 0) (take limit merged) merged)
        resources (map #(object-ref resource-type %) limited)]
    {:count  (count limited)
     :limit  limit
     :cursor {:resource (or (last resources) (:resource cursor))}}))

;; ============================================================================
;; lookup-subjects — every subject that can reach a known resource
;; ============================================================================

(defn traverse-permission-path-reverse
  "Walk one `path` backward from a known resource — a lazy seq of
   subject eids that can reach it, sorted ascending."
  [db resource-type resource-eid path subject-type cursor-eid]
  (case (:type path)
    :relation
    (when (= subject-type (:subject-type path))
      (->> (idx-range db :authz.relationship/reverse
                      [resource-type resource-eid (:name path) subject-type 0]
                      [resource-type resource-eid (:name path) subject-type
                       Long/MAX_VALUE])
           (map extract-subject-id)
           (filter (fn [sid] (and sid (> sid (or cursor-eid 0)))))))

    :self-permission
    (let [target-permission (:target-permission path)
          path-seqs
          (->> (get-permission-paths db resource-type target-permission)
               (map (fn [tp]
                      (traverse-permission-path-reverse db resource-type
                                                        resource-eid tp
                                                        subject-type cursor-eid)))
               (filter seq))]
      (if (seq path-seqs)
        (ms/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
        []))

    :arrow
    (let [via-relation      (:via path)
          intermediate-type (:target-type path)]
      (if-let [target-relation (:target-relation path)]
        ;; arrow → relation: intermediates connected to the resource
        ;; via via-relation, then subjects with target-relation to
        ;; each intermediate.
        (let [intermediate-eids
              (->> (idx-range db :authz.relationship/reverse
                              [resource-type resource-eid via-relation
                               intermediate-type 0]
                              [resource-type resource-eid via-relation
                               intermediate-type Long/MAX_VALUE])
                   (map extract-subject-id))
              subject-seqs
              (map (fn [intermediate-eid]
                     (->> (idx-range db :authz.relationship/reverse
                                     [intermediate-type intermediate-eid
                                      target-relation subject-type 0]
                                     [intermediate-type intermediate-eid
                                      target-relation subject-type
                                      Long/MAX_VALUE])
                          (map extract-subject-id)
                          (filter #(> % (or cursor-eid 0)))))
                   intermediate-eids)]
          (if (seq subject-seqs)
            (ms/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
            []))
        ;; arrow → permission: intermediates connected to the resource,
        ;; then recursively subjects with the target permission on each.
        (let [target-permission (:target-permission path)
              intermediate-eids
              (->> (idx-range db :authz.relationship/reverse
                              [resource-type resource-eid via-relation
                               intermediate-type 0]
                              [resource-type resource-eid via-relation
                               intermediate-type Long/MAX_VALUE])
                   (map extract-subject-id))
              subject-seqs
              (map (fn [intermediate-eid]
                     (let [sub-seqs
                           (->> (get-permission-paths db intermediate-type
                                                      target-permission)
                                (map (fn [sub-path]
                                       (traverse-permission-path-reverse
                                        db intermediate-type intermediate-eid
                                        sub-path subject-type cursor-eid)))
                                (filter seq))]
                       (if (seq sub-seqs)
                         (ms/lazy-fold2-merge-dedupe-sorted-by identity sub-seqs)
                         [])))
                   intermediate-eids)]
          (if (seq subject-seqs)
            (ms/lazy-fold2-merge-dedupe-sorted-by identity subject-seqs)
            []))))))

(defn- lazy-merged-lookup-subjects
  [db {:keys [resource permission subject/type cursor]}]
  (let [{resource-type :type resource-id :id} resource
        resource-eid (entid db resource-id)
        cursor-eid   (:id (:subject cursor))
        path-seqs    (->> (get-permission-paths db resource-type permission)
                          (keep (fn [path]
                                  (let [rs (traverse-permission-path-reverse
                                            db resource-type resource-eid
                                            path type cursor-eid)]
                                    (when (seq rs) rs)))))]
    (if (seq path-seqs)
      (ms/lazy-fold2-merge-dedupe-sorted-by identity path-seqs)
      [])))

(defn lookup-subjects
  "Every subject that has `permission` on `resource`. Cursor-
   paginated. Returns `{:data [object-ref …] :cursor {:subject
   object-ref}}`."
  [db {:as query :keys [resource subject/type limit cursor] :or {limit 1000}}]
  {:pre [(:type resource) (:id resource)]}
  (let [merged   (lazy-merged-lookup-subjects db query)
        limited  (if (>= limit 0) (take limit merged) merged)
        subjects (map #(object-ref type %) limited)]
    {:data   subjects
     :cursor {:subject (or (last subjects) (:subject cursor))}}))

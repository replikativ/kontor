(ns kontor.status-machine
  "Generic state-machine primitive — ADR-034.

   A `:status-transition` row represents one legal state transition
   for one (entity-type, facet) combination, optionally scoped to a
   specific `:entity` (the org-level override pattern). A
   `:status-history` row records each actual transition with audit
   metadata.

   Distinct from `kontor.state-machine` (which encodes the kernel's
   `:transaction/state` lifecycle with sealing-specific guards). The
   two coexist; new companion entities use this generic table.

   ## Vocabulary conventions

   - **Facet**: the attribute carrying state on the entity (e.g.
     `:order/status`, `:invoice/status`, `:order-item/status`). One
     entity can have multiple facets — multiple independent state
     machines on the same row.
   - **From-state `nil` pseudo-state**: when a transition represents
     entity creation (no prior state), use a `:*/nil` sentinel keyword
     by convention (e.g. `:order.status/nil`). Datahike treats nil
     values awkwardly for keyword attributes.
   - **Org scope**: a transition with `:status-transition/applies-to-
     org` set scopes to that org; one without it applies tenant-wide.
     The predicate prefers an org-specific match but falls back to
     the global row, so a tenant override doesn't require deleting
     the default.

   ## Public API

   - `legal-transition?` — predicate; consult before applying.
   - `legal-transitions-from` — set of legal next states.
   - `record-status-change!` — convenience transactor that checks
     legality, sets the facet attr, and writes a history row.
   - `status-history-of` — pull history rows for an entity.
   - `current-status` — read the current facet value."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Predicate
;; ============================================================================

(defn legal-transition?
  "True iff `(entity-type, facet, from, to)` is an active legal
   transition, optionally scoped to `org`.

   Lookup:
     1. Match an org-specific row (applies-to-org = org), OR
     2. Match a tenant-wide row (applies-to-org absent / nil).
   Either suffices. Inactive rows (`:status-transition/active false`)
   are ignored."
  ([db entity-type facet from to] (legal-transition? db entity-type facet from to nil))
  ([db entity-type facet from to org]
   (let [org-eid (cond
                   (nil? org)     nil
                   (string? org)  (d/q '[:find ?e .
                                         :in $ ?code
                                         :where [?e :entity/code ?code]]
                                       db org)
                   :else          org)]
     (boolean
      (or
       ;; Org-specific row, if org is given
       (when org-eid
         (d/q '[:find ?t .
                :in $ ?et ?facet ?from ?to ?org
                :where
                [?t :status-transition/entity-type ?et]
                [?t :status-transition/facet ?facet]
                [?t :status-transition/from ?from]
                [?t :status-transition/to ?to]
                [?t :status-transition/applies-to-org ?org]
                [?t :status-transition/active true]]
              db entity-type facet from to org-eid))
       ;; Tenant-wide row (applies-to-org absent)
       (d/q '[:find ?t .
              :in $ ?et ?facet ?from ?to
              :where
              [?t :status-transition/entity-type ?et]
              [?t :status-transition/facet ?facet]
              [?t :status-transition/from ?from]
              [?t :status-transition/to ?to]
              [?t :status-transition/active true]
              [(missing? $ ?t :status-transition/applies-to-org)]]
            db entity-type facet from to))))))

(defn legal-transitions-from
  "Set of `to` states reachable from `from` for the given entity-type
   and facet, considering org scope per `legal-transition?` semantics."
  ([db entity-type facet from] (legal-transitions-from db entity-type facet from nil))
  ([db entity-type facet from org]
   (let [org-eid (cond
                   (nil? org)     nil
                   (string? org)  (d/q '[:find ?e .
                                         :in $ ?code
                                         :where [?e :entity/code ?code]]
                                       db org)
                   :else          org)
         tenant-wide (d/q '[:find [?to ...]
                            :in $ ?et ?facet ?from
                            :where
                            [?t :status-transition/entity-type ?et]
                            [?t :status-transition/facet ?facet]
                            [?t :status-transition/from ?from]
                            [?t :status-transition/to ?to]
                            [?t :status-transition/active true]
                            [(missing? $ ?t :status-transition/applies-to-org)]]
                          db entity-type facet from)
         org-specific (when org-eid
                        (d/q '[:find [?to ...]
                               :in $ ?et ?facet ?from ?org
                               :where
                               [?t :status-transition/entity-type ?et]
                               [?t :status-transition/facet ?facet]
                               [?t :status-transition/from ?from]
                               [?t :status-transition/to ?to]
                               [?t :status-transition/applies-to-org ?org]
                               [?t :status-transition/active true]]
                             db entity-type facet from org-eid))]
     (into (set tenant-wide) org-specific))))

;; ============================================================================
;; Transactor
;; ============================================================================

(defn record-status-change-tx-data
  "Pure variant: validate the transition against `db` and return
   tx-data ready to `d/transact` (the facet update + the history row).
   Throws ex-info :type :status-machine/illegal-transition if not.

   Use this when the status change must compose atomically with other
   tx-data (e.g. the invoice posting bridge composes posting tx-data
   + invoice update + status change in one tx).

   See `record-status-change!` for opts."
  [db {:keys [entity entity-type facet from to org changed-at
              changed-by-uid reason origin-transaction]}]
  (let [from (or from (get (d/pull db [facet] entity) facet))]
    (when-not (legal-transition? db entity-type facet from to org)
      (throw (ex-info "Illegal status transition"
                      {:type        :status-machine/illegal-transition
                       :entity      entity
                       :entity-type entity-type
                       :facet       facet
                       :from        from
                       :to          to
                       :org         org
                       :legal       (legal-transitions-from db entity-type facet from org)})))
    (let [history (cond-> {:status-history/entity      entity
                           :status-history/entity-type entity-type
                           :status-history/facet       facet
                           :status-history/to          to
                           :status-history/changed-at  (or changed-at (java.util.Date.))}
                    from               (assoc :status-history/from from)
                    changed-by-uid     (assoc :status-history/changed-by-uid changed-by-uid)
                    reason             (assoc :status-history/reason reason)
                    origin-transaction (assoc :status-history/origin-transaction origin-transaction))]
      [[:db/add entity facet to]
       history])))

(defn record-status-change!
  "Convenience transactor. In one tx:
     1. Checks legality (throws ex-info :type :status-machine/illegal-
        transition if not).
     2. Sets the entity's facet attribute to `to`.
     3. Writes a :status-history row with audit metadata.

   Required keys in opts:
     :entity        — entity-id of the entity transitioning
     :entity-type   — keyword discriminator (denormed into history)
     :facet         — facet keyword (the attribute being mutated)
     :to            — destination state keyword

   Optional keys:
     :from               — explicit from-state. If omitted, pulled
                           from `(db.entity).facet` at call time.
     :org                — :entity ref or code; scopes the legality
                           check.
     :changed-at         — instant, default now.
     :changed-by-uid     — ref to :create/uid; recommended.
     :reason             — free-text rationale.
     :origin-transaction — ref to kernel :transaction that caused
                           the change.

   Returns the tx-report. For atomic composition with other tx-data,
   use `record-status-change-tx-data` directly."
  [conn opts]
  (d/transact conn (record-status-change-tx-data (d/db conn) opts)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn current-status
  "Read the current facet value for `entity`."
  [db entity facet]
  (get (d/pull db [facet] entity) facet))

(defn status-history-of
  "Pulled :status-history rows for `entity`, ordered oldest-first by
   `:status-history/changed-at`. Optionally restrict to a single
   facet via the 3-arity."
  ([db entity]
   (->> (d/q '[:find [?h ...]
               :in $ ?entity
               :where [?h :status-history/entity ?entity]]
             db entity)
        (map #(d/pull db '[*] %))
        (sort-by :status-history/changed-at)
        vec))
  ([db entity facet]
   (->> (d/q '[:find [?h ...]
               :in $ ?entity ?facet
               :where
               [?h :status-history/entity ?entity]
               [?h :status-history/facet ?facet]]
             db entity facet)
        (map #(d/pull db '[*] %))
        (sort-by :status-history/changed-at)
        vec)))

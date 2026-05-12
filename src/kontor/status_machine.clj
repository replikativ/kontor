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

;; ============================================================================
;; Approval policy lookup + enforcement (ADR-038)
;; ============================================================================

(defn applicable-policies
  "Return :approval-policy entities applicable to the
   (entity-type, facet, from, to) transition, considering org scope
   per the same semantics as legal-transition?: org-specific match
   plus tenant-wide default. Only :active? = true policies are
   returned."
  ([db entity-type facet from to] (applicable-policies db entity-type facet from to nil))
  ([db entity-type facet from to org]
   (let [org-eid (cond
                   (nil? org)     nil
                   (string? org)  (d/q '[:find ?e .
                                         :in $ ?code
                                         :where [?e :entity/code ?code]]
                                       db org)
                   :else          org)
         tenant-rows (d/q '[:find [?p ...]
                            :in $ ?et ?f ?from ?to
                            :where
                            [?p :approval-policy/entity-type ?et]
                            [?p :approval-policy/facet ?f]
                            [?p :approval-policy/transition-from ?from]
                            [?p :approval-policy/transition-to ?to]
                            [?p :approval-policy/active true]
                            [(missing? $ ?p :approval-policy/applies-to-org)]]
                          db entity-type facet from to)
         org-rows (when org-eid
                    (d/q '[:find [?p ...]
                           :in $ ?et ?f ?from ?to ?org
                           :where
                           [?p :approval-policy/entity-type ?et]
                           [?p :approval-policy/facet ?f]
                           [?p :approval-policy/transition-from ?from]
                           [?p :approval-policy/transition-to ?to]
                           [?p :approval-policy/applies-to-org ?org]
                           [?p :approval-policy/active true]]
                         db entity-type facet from to org-eid))]
     (mapv #(d/pull db '[*] %) (concat tenant-rows org-rows)))))

(defn- ->eid
  "Normalize a value that may be an eid, a pull-result map {:db/id eid},
   or nil. Returns the underlying eid, or nil."
  [x]
  (cond
    (nil? x)    nil
    (map? x)    (:db/id x)
    :else       x))

(defn- check-policy
  "Apply one :approval-policy rule to a change-spec; return nil if ok,
   {:rule ... :reason ...} if violated."
  [db {:approval-policy/keys [rule]}
   {:keys [entity changed-by-uid reason-note]
    sup-doc :supporting-doc}]
  (case rule
    :no-self-approval
    (let [creator (->eid (:create/uid (d/pull db [:create/uid] entity)))
          actor   (->eid changed-by-uid)]
      (when (and creator actor (= creator actor))
        {:rule rule
         :reason "transition actor must differ from entity creator"
         :actor actor
         :creator creator}))

    :requires-supporting-doc
    (when-not sup-doc
      {:rule rule
       :reason ":supporting-doc ref is required on this transition"})

    :requires-non-empty-reason-note
    (when (or (nil? reason-note) (= "" reason-note))
      {:rule rule
       :reason ":reason-note string is required on this transition"})

    ;; Unknown rule: treat as a no-op (forward-compat for new rules
    ;; defined by future ADRs). A future linter can flag rule-typos.
    nil))

(defn check-policies
  "Throw :approval-policy/violation if any applicable policy rejects
   the change-spec. Returns nil on success.

   change-spec must include :entity, :entity-type, :facet, :from, :to,
   and optionally :changed-by-uid, :reason-note, :supporting-doc,
   :org."
  [db change-spec]
  (let [{:keys [entity-type facet from to org]} change-spec
        policies (applicable-policies db entity-type facet from to org)
        violations (->> policies
                        (keep #(check-policy db % change-spec))
                        vec)]
    (when (seq violations)
      (throw (ex-info "Approval-policy violation"
                      {:type        :approval-policy/violation
                       :entity      (:entity change-spec)
                       :entity-type entity-type
                       :facet       facet
                       :from        from
                       :to          to
                       :violations  violations}))))
  nil)

;; ============================================================================
;; Transactor
;; ============================================================================

(defn record-status-change-tx-data
  "Pure variant: validate the transition against `db` and return
   tx-data ready to `d/transact` (the facet update + the history row).
   Throws ex-info :type :status-machine/illegal-transition or
   :approval-policy/violation if invalid.

   Use this when the status change must compose atomically with other
   tx-data (e.g. the invoice posting bridge composes posting tx-data
   + invoice update + status change in one tx).

   See `record-status-change!` for opts. ADR-038 adds:
     :reason          — keyword codified reason (was string)
     :reason-note     — optional free-text human story
     :supporting-doc  — optional ref to :audit-doc"
  [db {:keys [entity entity-type facet from to org changed-at
              changed-by-uid reason reason-note supporting-doc
              origin-transaction]
       :as change-spec}]
  (let [from (or from (get (d/pull db [facet] entity) facet))
        change-spec (assoc change-spec :from from)]
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
    ;; ADR-038: when :reason is :other, :reason-note must be non-empty.
    (when (and (= reason :other)
               (or (nil? reason-note) (= "" reason-note)))
      (throw (ex-info ":reason :other requires a non-empty :reason-note"
                      {:type :status-history/reason-note-required
                       :entity entity
                       :facet facet})))
    ;; ADR-038: apply applicable approval-policy rules.
    (check-policies db change-spec)
    (let [history (cond-> {:status-history/entity      entity
                           :status-history/entity-type entity-type
                           :status-history/facet       facet
                           :status-history/to          to
                           :status-history/changed-at  (or changed-at (java.util.Date.))}
                    from               (assoc :status-history/from from)
                    changed-by-uid     (assoc :status-history/changed-by-uid changed-by-uid)
                    reason             (assoc :status-history/reason reason)
                    reason-note        (assoc :status-history/reason-note reason-note)
                    supporting-doc     (assoc :status-history/supporting-doc supporting-doc)
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
     :reason             — keyword codified reason (ADR-038).
     :reason-note        — free-text note alongside :reason (ADR-038).
     :supporting-doc     — ref to :audit-doc (ADR-038).
     :origin-transaction — ref to kernel :transaction that caused
                           the change.

   Returns the tx-report. For atomic composition with other tx-data,
   use `record-status-change-tx-data` directly."
  [conn opts]
  (d/transact conn (record-status-change-tx-data (d/db conn) opts)))

;; ============================================================================
;; ADR-041 — Bulk transitions
;; ============================================================================

(defn bulk-record-status-change-tx-data
  "Validate + build tx-data for N status changes in ONE tx. Returns a
   single tx-data vector. If any change-spec fails validation, the
   whole batch is rejected (no partial application).

   Caller transacts the result, or composes with other tx-data (e.g.
   downstream side-effect-intent rows)."
  [db change-specs]
  (vec (mapcat #(record-status-change-tx-data db %) change-specs)))

(defn bulk-record-status-change!
  "Thin wrapper that transacts what `bulk-record-status-change-tx-data`
   returns."
  [conn change-specs]
  (d/transact conn (bulk-record-status-change-tx-data (d/db conn) change-specs)))

;; ============================================================================
;; ADR-041 — Time-based transition sweeper
;; ============================================================================

(defn- entities-eligible-for
  "Find entities currently in `from-state` for the given (entity-type,
   facet) where the most recent transition into from-state happened
   more than `millis` ms ago.

   Uses :status-history rows: the entity is in from-state iff its
   latest history row to from-state is more recent than any later
   transition out of from-state. Bitemporal: counts wall-clock time
   from :status-history/changed-at, not datahike tx-time."
  [db entity-type facet from-state millis]
  (let [threshold (java.util.Date. (- (System/currentTimeMillis) millis))
        rows (d/q '[:find ?entity ?from-when
                    :in $ ?et ?facet ?from
                    :where
                    [?entity ?facet ?from]
                    [?h :status-history/entity ?entity]
                    [?h :status-history/entity-type ?et]
                    [?h :status-history/facet ?facet]
                    [?h :status-history/to ?from]
                    [?h :status-history/changed-at ?from-when]]
                  db entity-type facet from-state)]
    (->> rows
         (filter (fn [[_ from-when]] (.before from-when threshold)))
         (map first)
         set)))

(defn sweep-time-based!
  "Scan :status-transition rows with :auto-after-millis set. For each
   such transition, find entities currently in from-state where the
   most recent transition into from-state was longer than the duration
   ago. Apply the transition with :reason :system-scheduled.

   Returns a vector of {:transition ... :entities-applied #{...}}
   maps for visibility."
  [conn]
  (let [db (d/db conn)
        transitions (d/q '[:find ?t ?et ?facet ?from ?to ?millis
                           :where
                           [?t :status-transition/auto-after-millis ?millis]
                           [?t :status-transition/active true]
                           [?t :status-transition/entity-type ?et]
                           [?t :status-transition/facet ?facet]
                           [?t :status-transition/from ?from]
                           [?t :status-transition/to ?to]]
                         db)]
    (mapv (fn [[_ et facet from to millis]]
            (let [eligible (entities-eligible-for db et facet from millis)
                  change-specs (mapv (fn [eid]
                                       {:entity eid
                                        :entity-type et
                                        :facet facet
                                        :to to
                                        :reason :system-scheduled})
                                     eligible)]
              (when (seq change-specs)
                (bulk-record-status-change! conn change-specs))
              {:entity-type et
               :facet facet
               :from from
               :to to
               :entities-applied eligible}))
          transitions)))

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

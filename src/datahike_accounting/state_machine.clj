(ns datahike-accounting.state-machine
  "Transaction-state lifecycle: enforce that `:transaction/state`
   transitions follow

       draft  ──▶  posted  ──▶  cancelled

   No skipping (draft → cancelled is rejected), no regression
   (posted → draft, cancelled → anything are rejected). Per ADR-007
   posted transactions cannot be modified except via a *new*
   reversing transaction (`:transaction/reverses`).

   Implementation: scan tx-data for entity-map updates that set
   `:transaction/state`. For each, look up the existing state in the
   db (or treat as `nil` for brand-new entities — the implicit
   start-state). Compare against the allowed-transitions table.

   When a tx writes `:transaction/state :posted` we additionally
   require `:transaction/posted-at` to be set in the same tx — this
   triggers sealing for the postings that hang off it (those need
   :posting/posted-at too, set by the caller)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; State machine
;; ============================================================================

(def allowed-transitions
  "Map {from #{to ...}}. nil represents a brand-new entity (no prior
   state datom)."
  {nil        #{:draft :posted}      ;; create-and-post in one tx is allowed
   :draft     #{:posted :cancelled}
   :posted    #{:cancelled}
   :cancelled #{}})                  ;; terminal

(defn transition-allowed?
  "True iff `from` → `to` is permitted by the state machine."
  [from to]
  (contains? (get allowed-transitions from #{}) to))

;; ============================================================================
;; tx-data scanning
;; ============================================================================

(defn- proposed-transaction-state-changes
  "Walk tx-data and return a seq of {:eid :tempid? :to} for each
   entity-map writing :transaction/state. Tuple-form
   `[:db/add eid :transaction/state v]` is also handled."
  [tx-data]
  (concat
   ;; entity-map form
   (keep (fn [tx]
           (when (and (map? tx) (contains? tx :transaction/state))
             {:eid     (:db/id tx)
              :tempid? (or (nil? (:db/id tx))
                           (and (integer? (:db/id tx)) (neg? (:db/id tx))))
              :to      (:transaction/state tx)
              :tx      tx}))
         tx-data)
   ;; tuple form
   (keep (fn [tx]
           (when (and (vector? tx)
                      (= :db/add (first tx))
                      (= :transaction/state (nth tx 2 nil)))
             {:eid     (second tx)
              :tempid? (and (integer? (second tx)) (neg? (second tx)))
              :to      (nth tx 3)
              :tx      tx}))
         tx-data)))

(defn- current-state
  "Pull the current :transaction/state for an existing eid; nil for
   tempids (treated as brand-new)."
  [db {:keys [eid tempid?]}]
  (when (and (not tempid?) (integer? eid))
    (:transaction/state (d/pull db [:transaction/state] eid))))

(defn find-violations
  "Return a vector of {:tx :from :to :reason} for any disallowed
   transition or missing-posted-at requirement in `tx-data`."
  [db tx-data]
  (vec
   (keep (fn [{:keys [eid tx to] :as change}]
           (let [from (current-state db change)]
             (cond
               (not (transition-allowed? from to))
               {:tx tx :from from :to to
                :reason :state-machine/illegal-transition
                :message (str "transition " (pr-str from) " → "
                              (pr-str to) " not allowed; permitted "
                              (pr-str (get allowed-transitions from #{})))}

                 ;; transitioning to :posted requires :posted-at in
                 ;; the same tx. If the tx is an entity-map we can
                 ;; check directly; for tuple form, look for a
                 ;; sibling [:db/add eid :transaction/posted-at ...].
               (and (= to :posted)
                    (not (and (map? tx) (:transaction/posted-at tx)))
                    (not (some #(and (vector? %)
                                     (= :db/add (first %))
                                     (= eid (second %))
                                     (= :transaction/posted-at (nth % 2 nil)))
                               tx-data)))
               {:tx tx :from from :to to
                :reason :state-machine/missing-posted-at
                :message ":transaction/state :posted requires
                           :transaction/posted-at to be set in the
                           same tx (use the same Date for both)."}

               :else nil)))
         (proposed-transaction-state-changes tx-data))))

(defn assert-transition!
  "Throws ex-info :type :state-machine/violation on any illegal
   :transaction/state transition or missing posted-at requirement."
  [db tx-data]
  (let [violations (find-violations db tx-data)]
    (when (seq violations)
      (throw (ex-info "Transaction state-machine violation"
                      {:type        :state-machine/violation
                       :violations  violations
                       :remediation
                       "Transaction states transition draft → posted →
                        cancelled. Skips and regressions are not
                        allowed; corrections are made via a NEW
                        reversing transaction (:transaction/reverses).
                        :posted requires :posted-at in the same tx
                        so sealing markers can be applied
                        consistently."}))))
  nil)

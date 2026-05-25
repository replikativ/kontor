(ns kontor.commitment
  "kontor-commitment — recognising and liquidating obligations
   (ADR-098, research note 99 Stage 4).

   The GL records what *moved*; a `:commitment` records what is
   *supposed to*. A receivable owed, a payable due, an encumbrance
   reserved — recorded when the obligation arises, liquidated as
   settling transactions fulfil it. The kernel is untouched: a
   `:commitment-fulfillment` edge points AT a kernel `:transaction`
   but the kernel `:transaction` gains no attribute.

   Every business write follows ADR-068 — a pure `*-tx-data` builder
   plus a `!` wrapper that stamps `:tx/valid-from` and routes through
   `kontor.validation`. The `!` wrappers compose into `kontor.process`
   step lists via their builders.

   Public surface:
     record-commitment! / -tx-data   open an obligation
     fulfill!           / -tx-data   link a settling transaction
     cancel!            / -tx-data   close an obligation unfulfilled
     open-commitments                still-live obligations (a db)
     outstanding                     committed − fulfilled
     aging                           overdue buckets
     pull-commitment / resolve-commitment / install!"
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.commitment.schema :as schema]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

(def install! schema/install!)

(def ^:private open-states #{:open :partially-fulfilled})
(def ^:private kinds #{:receivable :payable :encumbrance})

;; ============================================================================
;; Resolution + queries
;; ============================================================================

(defn resolve-commitment
  "Resolve a commitment spec — an `:commitment/external-id` string or
   an eid — to an eid (nil if absent)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (d/q '[:find ?e . :in $ ?x
                          :where [?e :commitment/external-id ?x]]
                        db spec)
    :else          spec))

(defn pull-commitment
  [db spec]
  (when-let [eid (resolve-commitment db spec)]
    (d/pull db
            '[* {:commitment/counterparty [:partner/external-id]}
              {:commitment/commodity [:commodity/symbol]}]
            eid)))

(defn outstanding
  "`committed − fulfilled` for a commitment (pull-result map or spec
   resolvable against `db`)."
  ([commitment-map]
   (- (or (:commitment/committed-amount commitment-map) 0M)
      (or (:commitment/fulfilled-amount commitment-map) 0M)))
  ([db spec]
   (outstanding (pull-commitment db spec))))

(defn open-commitments
  "Every commitment in an open state (`:open` or `:partially-fulfilled`)
   as `db` sees it. Pass `(d/db conn)` for the present, or
   `(d/as-of (d/db conn) t)` to travel the tx-time axis — datahike
   gives a fully consistent historical snapshot, state included."
  [db]
  (->> (d/q '[:find [?c ...]
              :in $ [?st ...]
              :where [?c :commitment/state ?st]]
            db open-states)
       (map #(pull-commitment db %))
       vec))

;; ============================================================================
;; record-commitment! — open an obligation
;; ============================================================================

(defn record-commitment-tx-data
  "Pure tx-data builder for `record-commitment!` (ADR-068). Required
   opts: `:external-id`, `:kind`, `:counterparty`, `:committed-amount`,
   `:commodity`, `:due-date`, `:recorded-by-uid`. Optional: `:entity`,
   `:origin`, `:notes`, `:tempid` (default `\"commitment-1\"`),
   `:recorded-at` (default now)."
  [db {:keys [external-id kind counterparty committed-amount commodity
              due-date recorded-by-uid entity origin notes tempid
              recorded-at]
       :or   {tempid "commitment-1"}}]
  (when-not external-id      (throw (ex-info ":external-id required" {})))
  (when-not (kinds kind)
    (throw (ex-info ":kind must be :receivable | :payable | :encumbrance"
                    {:kind kind})))
  (when-not counterparty     (throw (ex-info ":counterparty required" {})))
  (when-not committed-amount (throw (ex-info ":committed-amount required" {})))
  (when-not commodity        (throw (ex-info ":commodity required" {})))
  (when-not due-date         (throw (ex-info ":due-date required" {})))
  (when-not recorded-by-uid  (throw (ex-info ":recorded-by-uid required" {})))
  ;; Helpful pre-check (note 160 §I-12): if the consumer transacted the
  ;; schema attrs but not the `:status-transition` seeds, the underlying
  ;; sm/record-status-change-tx-data would throw a generic "Illegal
  ;; status transition" with no hint. Catch it here with a pointer.
  (when-not (d/q '[:find ?t .
                   :where [?t :status-transition/entity-type :commitment]
                          [?t :status-transition/from :nil]
                          [?t :status-transition/to :open]
                          [?t :status-transition/active true]]
                 db)
    (throw (ex-info
            (str "kontor.commitment: status-transition seeds not found in the DB. "
                 "Did you call `(kontor.commitment/install! conn)`? "
                 "It installs both the schema attrs AND the seeds.")
            {:hint :missing-status-transition-seeds})))
  (let [recorded-at (or recorded-at (java.util.Date.))
        row (cond-> {:db/id                       tempid
                     :commitment/external-id      external-id
                     :commitment/kind             kind
                     :commitment/counterparty     counterparty
                     :commitment/committed-amount (bigdec committed-amount)
                     :commitment/fulfilled-amount 0M
                     :commitment/commodity        commodity
                     :commitment/due-date         due-date
                     :commitment/recorded-by-uid  recorded-by-uid
                     :commitment/recorded-at      recorded-at
                     :commitment/state            :open}
              entity (assoc :commitment/entity entity)
              origin (assoc :commitment/origin origin)
              notes  (assoc :commitment/notes notes))
        status-tx (sm/record-status-change-tx-data
                   db {:entity         tempid
                       :entity-type    :commitment
                       :facet          :commitment/state
                       :from           :nil
                       :to             :open
                       :changed-at     recorded-at
                       :changed-by-uid recorded-by-uid
                       :reason         :commitment-recorded})]
    (into [row] status-tx)))

(defn record-commitment!
  "Open a `:commitment` (an `:open` obligation). See
   `record-commitment-tx-data` for the opts; the `!` wrapper also
   takes `:vt-from` / `:vt-to` (default now / forever)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [recorded-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (record-commitment-tx-data
                        (d/db conn) (assoc opts :recorded-at recorded-at))
                       (or vt-from recorded-at)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; fulfill! — link a settling transaction
;; ============================================================================

(defn fulfill-tx-data
  "Pure tx-data builder for `fulfill!` (ADR-068). Records a
   `:commitment-fulfillment` edge to the settling `:transaction`,
   advances the denormalized `:commitment/fulfilled-amount`, and — when
   the state actually changes — transitions `:commitment/state`
   (`:open`/`:partially-fulfilled` → `:partially-fulfilled`/`:fulfilled`).

   Required opts: `:commitment` (spec), `:transaction` (the settling
   transaction eid), `:amount`, `:recorded-by-uid`. Optional:
   `:notes`, `:tempid` (default `\"fulfillment-1\"`), `:fulfilled-at`
   (default now)."
  [db {:keys [commitment transaction amount recorded-by-uid notes
              tempid fulfilled-at]
       :or   {tempid "fulfillment-1"}}]
  (let [eid (resolve-commitment db commitment)]
    (when-not eid          (throw (ex-info "Commitment not found" {:spec commitment})))
    (when-not transaction  (throw (ex-info ":transaction required" {})))
    (when-not amount       (throw (ex-info ":amount required" {})))
    (when-not recorded-by-uid (throw (ex-info ":recorded-by-uid required" {})))
    (let [amount (bigdec amount)
          _ (when-not (pos? amount)
              (throw (ex-info ":amount must be positive" {:amount amount})))
          {:keys [commitment/state commitment/committed-amount
                  commitment/fulfilled-amount]}
          (d/pull db [:commitment/state :commitment/committed-amount
                      :commitment/fulfilled-amount]
                  eid)
          _ (when-not (open-states state)
              (throw (ex-info "Cannot fulfill a closed commitment"
                              {:commitment eid :state state})))
          at           (or fulfilled-at (java.util.Date.))
          new-fulfilled (+ (or fulfilled-amount 0M) amount)
          new-state    (cond (>= new-fulfilled committed-amount) :fulfilled
                             (pos? new-fulfilled)                :partially-fulfilled
                             :else                               :open)
          edge (cond-> {:db/id tempid
                        :commitment-fulfillment/commitment      eid
                        :commitment-fulfillment/transaction     transaction
                        :commitment-fulfillment/amount          amount
                        :commitment-fulfillment/fulfilled-at    at
                        :commitment-fulfillment/recorded-by-uid recorded-by-uid}
                 notes (assoc :commitment-fulfillment/notes notes))
          denorm {:db/id eid :commitment/fulfilled-amount new-fulfilled}]
      (cond-> [edge denorm]
        (not= new-state state)
        (into (sm/record-status-change-tx-data
               db {:entity         eid
                   :entity-type    :commitment
                   :facet          :commitment/state
                   :from           state
                   :to             new-state
                   :changed-at     at
                   :changed-by-uid recorded-by-uid
                   :reason         :commitment-fulfilled}))))))

(defn fulfill!
  "Record that `:transaction` fulfilled `:amount` of `:commitment`.
   See `fulfill-tx-data`; the `!` wrapper also takes `:vt-from` /
   `:vt-to` (default now / forever)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (fulfill-tx-data (d/db conn)
                                        (assoc opts :fulfilled-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; cancel! — close an obligation unfulfilled
;; ============================================================================

(defn cancel-tx-data
  "Pure tx-data builder for `cancel!` (ADR-068). Transitions
   `:commitment/state` to `:cancelled`. Required: `:commitment`,
   `:changed-by-uid`. Optional: `:reason` (default `:cancelled`),
   `:reason-note`, `:changed-at` (default now)."
  [db {:keys [commitment changed-by-uid reason reason-note changed-at]}]
  (let [eid (resolve-commitment db commitment)]
    (when-not eid (throw (ex-info "Commitment not found" {:spec commitment})))
    (sm/record-status-change-tx-data
     db (cond-> {:entity         eid
                 :entity-type    :commitment
                 :facet          :commitment/state
                 :to             :cancelled
                 :changed-at     (or changed-at (java.util.Date.))
                 :changed-by-uid changed-by-uid
                 :reason         (or reason :cancelled)}
          reason-note (assoc :reason-note reason-note)))))

(defn cancel!
  "Cancel a commitment — `:state` → `:cancelled`. See `cancel-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (cancel-tx-data (d/db conn) (assoc opts :changed-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; aging
;; ============================================================================

(def ^:private day-ms 86400000)

(defn- bucket-of
  "AR-style overdue bucket from a (signed) overdue-day count."
  [overdue-days]
  (cond
    (<= overdue-days 0)  :current
    (<= overdue-days 30) :1-30
    (<= overdue-days 60) :31-60
    (<= overdue-days 90) :61-90
    :else                :90+))

(defn aging
  "Bucket every open commitment by how overdue it is at `:as-of`
   (default now). Returns a vector of rows:

     {:commitment <eid> :external-id <str> :kind <kw>
      :outstanding <bigdec> :due-date <Date>
      :overdue-days <long> :bucket <kw>}

   `:bucket` ∈ #{:current :1-30 :31-60 :61-90 :90+}."
  ([db] (aging db {}))
  ([db {:keys [as-of] :or {as-of (java.util.Date.)}}]
   (let [as-of-ms (.getTime ^java.util.Date as-of)]
     (->> (open-commitments db)
          (map (fn [c]
                 (let [due-ms (.getTime ^java.util.Date (:commitment/due-date c))
                       overdue-days (quot (- as-of-ms due-ms) day-ms)]
                   {:commitment   (:db/id c)
                    :external-id  (:commitment/external-id c)
                    :kind         (:commitment/kind c)
                    :outstanding  (outstanding c)
                    :due-date     (:commitment/due-date c)
                    :overdue-days overdue-days
                    :bucket       (bucket-of overdue-days)})))
          vec))))

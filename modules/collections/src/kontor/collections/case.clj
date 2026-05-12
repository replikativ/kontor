(ns kontor.collections.case
  "Collection-case lifecycle — ADR-043.

   The case is the workflow root. One open case per (partner, entity)
   at any given time; close before re-opening. Composes with:
     - ADR-034 status-machine on `:collection-case/state`
     - ADR-038 audit-doc + approval-policy
     - ADR-041 :side-effect-intent for any outgoing communication

   Distinct from `kontor.invoice` 'cancel' or 'mark-paid' — those
   are kernel lifecycle moves on the invoice itself. A case
   *coordinates* over N invoices belonging to the same
   (partner, entity)."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  [db code]
  (d/q '[:find ?e .
         :in $ ?c
         :where [?e :collection-case/code ?c]]
       db code))

(defn resolve-case
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

(defn pull-case
  [db spec]
  (when-let [eid (resolve-case db spec)]
    (d/pull db
            '[* {:collection-case/partner [:partner/external-id :partner/name]
                 :collection-case/entity [:entity/code]
                 :collection-case/opened-by-uid [:create/uid]
                 :collection-case/assigned-collector [:create/uid]
                 :collection-case/oldest-invoice [:invoice/external-id]
                 :collection-case/supporting-doc [:audit-doc/code :audit-doc/type]}]
            eid)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn open-case-for
  "Return the eid of the open case (i.e. :closed-at is unset) for
   the (partner, entity) pair, or nil if none."
  [db partner-eid entity-eid]
  (d/q '[:find ?c .
         :in $ ?p ?e
         :where
         [?c :collection-case/partner ?p]
         [?c :collection-case/entity ?e]
         [(missing? $ ?c :collection-case/closed-at)]]
       db partner-eid entity-eid))

(defn cases-by-state
  [db state-kw]
  (->> (d/q '[:find [?c ...]
              :in $ ?s
              :where [?c :collection-case/state ?s]]
            db state-kw)
       (map #(pull-case db %))
       vec))

(defn cases-assigned-to
  [db collector-uid-eid]
  (->> (d/q '[:find [?c ...]
              :in $ ?col
              :where
              [?c :collection-case/assigned-collector ?col]
              [(missing? $ ?c :collection-case/closed-at)]]
            db collector-uid-eid)
       (map #(pull-case db %))
       vec))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn open-case!
  "Open a new :collection-case for (partner, entity).

   Required opts:
     :code            string identity
     :partner         ref/eid
     :entity          ref/eid (ADR-031 scope)
     :opened-by-uid   ref to :create/uid

   Optional opts:
     :strategy           keyword (:reminder-only :phone :legal …)
     :segment            keyword (matches :collection-case/
                          collections-segment)
     :assigned-collector ref to :create/uid
     :notes              string
     :supporting-doc     ref to :audit-doc

   Throws if an open case for (partner, entity) already exists —
   tenants must close the current case before opening another."
  [conn {:keys [code partner entity opened-by-uid strategy segment
                assigned-collector notes supporting-doc]}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not partner        (throw (ex-info ":partner required" {})))
  (when-not entity         (throw (ex-info ":entity required" {})))
  (when-not opened-by-uid  (throw (ex-info ":opened-by-uid required" {})))
  (let [db (d/db conn)
        existing (open-case-for db partner entity)
        _ (when existing
            (throw (ex-info "Open case already exists for (partner, entity)"
                            {:type :collection-case/already-open
                             :existing existing
                             :partner partner :entity entity})))
        opened-at (java.util.Date.)
        case-tempid "case-1"
        row (cond-> {:db/id case-tempid
                     :collection-case/code code
                     :collection-case/partner partner
                     :collection-case/entity entity
                     :collection-case/state :open
                     :collection-case/opened-at opened-at
                     :collection-case/opened-by-uid opened-by-uid}
              strategy           (assoc :collection-case/strategy strategy)
              segment            (assoc :collection-case/collections-segment segment)
              assigned-collector (assoc :collection-case/assigned-collector assigned-collector)
              notes              (assoc :collection-case/notes notes)
              supporting-doc     (assoc :collection-case/supporting-doc supporting-doc))
        ;; Write the initial status-history row for nil → :open via the
        ;; status machine (atomic).
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity case-tempid
                    :entity-type :collection-case
                    :facet :collection-case/state
                    :from :nil
                    :to :open
                    :changed-at opened-at
                    :changed-by-uid opened-by-uid
                    :reason :case-opened})
        all-tx (into [row] status-tx)]
    (d/transact conn all-tx)))

(defn advance-state!
  "Drive `:collection-case/state` through the status-machine. Generic
   helper for dunning-l1 / l2 / final-notice / promised / disputed
   / legal / paid / written-off transitions.

   Required opts: :case, :to, :changed-by-uid.
   Optional: :reason, :reason-note, :supporting-doc."
  [conn {:keys [case to changed-by-uid reason reason-note supporting-doc]}]
  (let [eid (resolve-case (d/db conn) case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))]
    (sm/record-status-change! conn
                              (cond-> {:entity eid
                                       :entity-type :collection-case
                                       :facet :collection-case/state
                                       :to to
                                       :changed-by-uid changed-by-uid}
                                reason         (assoc :reason reason)
                                reason-note    (assoc :reason-note reason-note)
                                supporting-doc (assoc :supporting-doc supporting-doc)))))

(defn close-case!
  "Close a case (:closed-at set). The case's :state must already be
   in a terminal-ish state (:paid, :written-off, :resolved) — caller's
   responsibility to drive to the right state first via
   `advance-state!`.

   Required opts: :case, :closed-by-uid.
   Optional: :reason, :reason-note, :supporting-doc."
  [conn {:keys [case closed-by-uid reason reason-note supporting-doc]}]
  (when-not closed-by-uid (throw (ex-info ":closed-by-uid required" {})))
  (let [db (d/db conn)
        eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))
        closed-at (java.util.Date.)
        update (cond-> {:db/id eid
                        :collection-case/closed-at closed-at}
                 supporting-doc (assoc :collection-case/supporting-doc
                                       supporting-doc))]
    (d/transact conn [update])))

(defn assign-collector!
  "Set or change the assigned collector. No state machine impact;
   purely a denorm change. Records the change via :status-history
   semantics only when status is concurrently moved — caller drives
   status separately if desired."
  [conn {:keys [case collector-uid changed-by-uid]}]
  (let [db (d/db conn)
        eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))]
    (d/transact conn
                [{:db/id eid
                  :collection-case/assigned-collector collector-uid}])))

(defn refresh-denorms!
  "Update `:collection-case/total-overdue` and `:collection-case/
   oldest-invoice` for a case. Caller passes the computed values —
   the denorm refresh is just a write. Production: nightly sweeper
   computes both via `kontor.payment-application/open-amount-of-
   invoice` + aging methods.

   Pure ADR-008 — bitemporal queries answer aging on the fly; the
   denorm exists only for fast filter/sort in lists."
  [conn {:keys [case total-overdue oldest-invoice]}]
  (let [db (d/db conn)
        eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))]
    (d/transact conn
                [(cond-> {:db/id eid}
                   total-overdue   (assoc :collection-case/total-overdue total-overdue)
                   oldest-invoice  (assoc :collection-case/oldest-invoice oldest-invoice))])))

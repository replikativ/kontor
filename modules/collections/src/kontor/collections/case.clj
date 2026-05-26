(ns kontor.collections.case
  "Collection-case lifecycle — ADR-043.

   The case is the workflow root. One open case per (partner, entity)
   at any given time; close before re-opening. Composes with:
     - ADR-034 status-machine on `:kontor.collection-case/state`
     - ADR-038 audit-doc + approval-policy
     - ADR-041 :side-effect-intent for any outgoing communication

   Distinct from `kontor.invoice` 'cancel' or 'mark-paid' — those
   are kernel lifecycle moves on the invoice itself. A case
   *coordinates* over N invoices belonging to the same
   (partner, entity)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  [db code]
  (d/q '[:find ?e .
         :in $ ?c
         :where [?e :kontor.collection-case/code ?c]]
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
            '[* {:kontor.collection-case/partner [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.collection-case/entity [:kontor.entity/code]
                 :kontor.collection-case/opened-by-uid [:kontor.audit/create-uid]
                 :kontor.collection-case/assigned-collector [:kontor.audit/create-uid]
                 :kontor.collection-case/oldest-invoice [:kontor.invoice/external-id]
                 :kontor.collection-case/supporting-doc [:kontor.audit-doc/code :kontor.audit-doc/type]}]
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
         [?c :kontor.collection-case/partner ?p]
         [?c :kontor.collection-case/entity ?e]
         [(missing? $ ?c :kontor.collection-case/closed-at)]]
       db partner-eid entity-eid))

(defn cases-by-state
  [db state-kw]
  (->> (d/q '[:find [?c ...]
              :in $ ?s
              :where [?c :kontor.collection-case/state ?s]]
            db state-kw)
       (map #(pull-case db %))
       vec))

(defn cases-assigned-to
  [db collector-uid-eid]
  (->> (d/q '[:find [?c ...]
              :in $ ?col
              :where
              [?c :kontor.collection-case/assigned-collector ?col]
              [(missing? $ ?c :kontor.collection-case/closed-at)]]
            db collector-uid-eid)
       (map #(pull-case db %))
       vec))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare open-case-tx-data
         advance-case-state-tx-data
         close-case-tx-data
         assign-collector-tx-data
         refresh-denorms-tx-data)

(defn open-case!
  "Open a new :collection-case for (partner, entity).

   Required opts:
     :code            string identity
     :partner         ref/eid
     :entity          ref/eid (ADR-031 scope)
     :opened-by-uid   ref to :kontor.audit/create-uid

   Optional opts:
     :strategy           keyword (:reminder-only :phone :legal …)
     :segment            keyword (matches :kontor.collection-case/
                          collections-segment)
     :assigned-collector ref to :kontor.audit/create-uid
     :notes              string
     :supporting-doc     ref to :audit-doc

   Throws if an open case for (partner, entity) already exists —
   tenants must close the current case before opening another.

   The pure tx-data builder is `open-case-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [opened-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (open-case-tx-data
                        (d/db conn) (assoc opts :opened-at opened-at))
                       (or vt-from opened-at)
                       (or vt-to kbt/forever)))))

(defn open-case-tx-data
  "Pure tx-data builder for `open-case!` (ADR-068). Optional
   `:tempid` (default `\"case-1\"`) and `:opened-at` (default now)."
  [db {:keys [code partner entity opened-by-uid strategy segment
              assigned-collector notes supporting-doc tempid opened-at]
       :or {tempid "case-1"}}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not partner        (throw (ex-info ":partner required" {})))
  (when-not entity         (throw (ex-info ":entity required" {})))
  (when-not opened-by-uid  (throw (ex-info ":opened-by-uid required" {})))
  (let [existing (open-case-for db partner entity)
        _ (when existing
            (throw (ex-info "Open case already exists for (partner, entity)"
                            {:type :kontor.collection-case/already-open
                             :existing existing
                             :partner partner :entity entity})))
        opened-at (or opened-at (java.util.Date.))
        row (cond-> {:db/id tempid
                     :kontor.collection-case/code code
                     :kontor.collection-case/partner partner
                     :kontor.collection-case/entity entity
                     :kontor.collection-case/state :open
                     :kontor.collection-case/opened-at opened-at
                     :kontor.collection-case/opened-by-uid opened-by-uid}
              strategy           (assoc :kontor.collection-case/strategy strategy)
              segment            (assoc :kontor.collection-case/collections-segment segment)
              assigned-collector (assoc :kontor.collection-case/assigned-collector assigned-collector)
              notes              (assoc :kontor.collection-case/notes notes)
              supporting-doc     (assoc :kontor.collection-case/supporting-doc supporting-doc))
        ;; Write the initial status-history row for nil → :open via the
        ;; status machine (atomic).
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity tempid
                    :entity-type :collection-case
                    :facet :kontor.collection-case/state
                    :from :nil
                    :to :open
                    :changed-at opened-at
                    :changed-by-uid opened-by-uid
                    :reason :case-opened})]
    (into [row] status-tx)))

(defn advance-case-state!
  "Drive `:kontor.collection-case/state` through the status-machine. Generic
   helper for dunning-l1 / l2 / final-notice / promised / disputed
   / legal / paid / written-off transitions.

   Required opts: :case, :to, :changed-by-uid.
   Optional: :reason, :reason-note, :supporting-doc.

   The pure tx-data builder is `advance-case-state-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (advance-case-state-tx-data
                        (d/db conn) (assoc opts :changed-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn advance-case-state-tx-data
  "Pure tx-data builder for `advance-case-state!` (ADR-068)."
  [db {:keys [case to changed-by-uid reason reason-note supporting-doc
              changed-at]}]
  (let [eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))
        now (or changed-at (java.util.Date.))]
    (sm/record-status-change-tx-data
     db
     (cond-> {:entity eid
              :entity-type :collection-case
              :facet :kontor.collection-case/state
              :to to
              :changed-at now
              :changed-by-uid changed-by-uid}
       reason         (assoc :reason reason)
       reason-note    (assoc :reason-note reason-note)
       supporting-doc (assoc :supporting-doc supporting-doc)))))

(defn close-case!
  "Close a case (:closed-at set). The case's :state must already be
   in a terminal-ish state (:paid, :written-off, :resolved) — caller's
   responsibility to drive to the right state first via
   `advance-case-state!`.

   Required opts: :case, :closed-by-uid.
   Optional: :reason, :reason-note, :supporting-doc, :vt-from, :vt-to.

   The pure tx-data builder is `close-case-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [closed-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (close-case-tx-data
                        (d/db conn) (assoc opts :closed-at closed-at))
                       (or vt-from closed-at)
                       (or vt-to kbt/forever)))))

(defn close-case-tx-data
  "Pure tx-data builder for `close-case!` (ADR-068)."
  [db {:keys [case closed-by-uid supporting-doc closed-at]}]
  (when-not closed-by-uid (throw (ex-info ":closed-by-uid required" {})))
  (let [eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))
        closed-at (or closed-at (java.util.Date.))
        update (cond-> {:db/id eid
                        :kontor.collection-case/closed-at closed-at}
                 supporting-doc (assoc :kontor.collection-case/supporting-doc
                                       supporting-doc))]
    [update]))

(defn assign-collector!
  "Set or change the assigned collector. No state machine impact;
   purely a denorm change. Records the change via :status-history
   semantics only when status is concurrently moved — caller drives
   status separately if desired.

   The pure tx-data builder is `assign-collector-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (assign-collector-tx-data (d/db conn) opts)))

(defn assign-collector-tx-data
  "Pure tx-data builder for `assign-collector!` (ADR-068)."
  [db {:keys [case collector-uid]}]
  (let [eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))]
    [{:db/id eid
      :kontor.collection-case/assigned-collector collector-uid}]))

(defn refresh-denorms!
  "Update `:kontor.collection-case/total-overdue` and `:kontor.collection-case/
   oldest-invoice` for a case. Caller passes the computed values —
   the denorm refresh is just a write. Production: nightly sweeper
   computes both via `kontor.payment-application/open-amount-of-
   invoice` + aging methods.

   Pure ADR-008 — bitemporal queries answer aging on the fly; the
   denorm exists only for fast filter/sort in lists.

   The pure tx-data builder is `refresh-denorms-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (refresh-denorms-tx-data (d/db conn) opts)))

(defn refresh-denorms-tx-data
  "Pure tx-data builder for `refresh-denorms!` (ADR-068)."
  [db {:keys [case total-overdue oldest-invoice]}]
  (let [eid (resolve-case db case)
        _ (when-not eid (throw (ex-info "Case not found" {:spec case})))]
    [(cond-> {:db/id eid}
       total-overdue   (assoc :kontor.collection-case/total-overdue total-overdue)
       oldest-invoice  (assoc :kontor.collection-case/oldest-invoice oldest-invoice))]))

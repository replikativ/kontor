(ns kontor.collections.dispute
  "Invoice (and line-level) dispute lifecycle — ADR-043.

   A `:dispute` is a structured record of customer pushback.
   Auto-suppresses dunning on the disputed invoice via a predicate
   query consulted by `kontor.collections.dunning`.

   `:dispute/scope` is optional — when set to an `:invoice-line` ref,
   the dispute is line-level (the market-pain #18 fix vs SAP/NetSuite
   which only model invoice-level)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :dispute/external-id ?xid]]
       db external-id))

(defn resolve-dispute
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-dispute
  [db spec]
  (when-let [eid (resolve-dispute db spec)]
    (d/pull db
            '[* {:dispute/invoice [:invoice/external-id]
                 :dispute/scope   [:db/id
                                   :invoice-line/sequence
                                   :invoice-line/name]
                 :dispute/opened-by-uid [:partner/external-id]
                 :dispute/resolved-by-uid [:partner/external-id]
                 :dispute/supporting-doc [:audit-doc/code]}]
            eid)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn open-disputes-for-invoice
  "All disputes on this invoice in non-terminal state (:open or
   :under-review or :escalated). Used by dunning to suppress
   emissions."
  [db invoice-eid]
  (->> (d/q '[:find [?d ...]
              :in $ ?inv
              :where
              [?d :dispute/invoice ?inv]
              [?d :dispute/state ?st]
              [(contains? #{:open :under-review :escalated} ?st)]]
            db invoice-eid)
       (map #(pull-dispute db %))
       vec))

(defn any-open-dispute-for-invoice?
  [db invoice-eid]
  (boolean (seq (open-disputes-for-invoice db invoice-eid))))

(defn disputes-by-reason
  "Aggregation hook for dispute analytics: count disputes by
   :reason-code over a window."
  [db]
  (->> (d/q '[:find ?reason (count ?d)
              :where [?d :dispute/reason-code ?reason]]
            db)
       (sort-by (comp - second))
       vec))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn raise-dispute!
  "Open a `:dispute` for an invoice (or line on an invoice).

   Required opts:
     :external-id     string identity
     :invoice         ref/eid
     :disputed-amount BigDecimal (subset of invoice or line total)
     :reason-code     keyword
     :opened-by-uid   ref to :create/uid

   Optional opts:
     :scope          ref to :invoice-line for line-level scope
     :sla-deadline   instant
     :notes          string
     :supporting-doc ref to :audit-doc
     :vt-from        kontor.bitemporal vt-from (default: now)
     :vt-to          kontor.bitemporal vt-to (default: open)"
  [conn {:keys [external-id invoice scope disputed-amount reason-code
                opened-by-uid sla-deadline notes supporting-doc
                vt-from vt-to]}]
  (when-not external-id     (throw (ex-info ":external-id required" {})))
  (when-not invoice         (throw (ex-info ":invoice required" {})))
  (when-not disputed-amount (throw (ex-info ":disputed-amount required" {})))
  (when-not reason-code     (throw (ex-info ":reason-code required" {})))
  (when-not opened-by-uid   (throw (ex-info ":opened-by-uid required" {})))
  (let [db (d/db conn)
        opened-at (java.util.Date.)
        disp-tempid "disp-1"
        row (cond-> {:db/id disp-tempid
                     :dispute/external-id external-id
                     :dispute/invoice invoice
                     :dispute/disputed-amount disputed-amount
                     :dispute/reason-code reason-code
                     :dispute/opened-by-uid opened-by-uid
                     :dispute/state :open}
              scope          (assoc :dispute/scope scope)
              sla-deadline   (assoc :dispute/sla-deadline sla-deadline)
              notes          (assoc :dispute/notes notes)
              supporting-doc (assoc :dispute/supporting-doc supporting-doc))
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity disp-tempid
                    :entity-type :dispute
                    :facet :dispute/state
                    :from :nil
                    :to :open
                    :changed-at opened-at
                    :changed-by-uid opened-by-uid
                    :reason reason-code})
        core-tx (into [row] status-tx)
        vf (or vt-from opened-at)
        vt (or vt-to   kbt/forever)]
    (d/transact conn (kbt/with-vt core-tx vf vt))))

(defn advance-state!
  "Drive a dispute through the state machine (:open → :under-review
   → :resolved | :escalated). Caller passes :to.

   Optional :vt-from / :vt-to stamp the tx with kontor.bitemporal
   valid-time (default: now)."
  [conn {:keys [dispute to changed-by-uid reason reason-note
                supporting-doc vt-from vt-to]}]
  (let [db (d/db conn)
        eid (resolve-dispute db dispute)
        _ (when-not eid (throw (ex-info "Dispute not found" {:spec dispute})))
        now (java.util.Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :dispute
                            :facet :dispute/state
                            :to to
                            :changed-at now
                            :changed-by-uid changed-by-uid}
                     reason         (assoc :reason reason)
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (d/transact conn (kbt/with-vt status-tx
                                  (or vt-from now)
                                  (or vt-to kbt/forever)))))

(defn resolve-dispute!
  "Resolve a dispute. Atomically:
     1. Sets :dispute/state → :resolved via the status machine.
     2. Writes :dispute/resolution, :resolved-by-uid.

   :resolved-at is no longer denormalized — read it from
   `:status-history` (the row that transitioned to :resolved) or
   from the tx's `:tx/valid-from` via kontor.bitemporal.

   Required: :dispute, :resolution keyword, :resolved-by-uid.
   Optional: :reason-note, :supporting-doc, :vt-from, :vt-to."
  [conn {:keys [dispute resolution resolved-by-uid reason-note
                supporting-doc vt-from vt-to]}]
  (when-not resolution      (throw (ex-info ":resolution required" {})))
  (when-not resolved-by-uid (throw (ex-info ":resolved-by-uid required" {})))
  (let [db (d/db conn)
        eid (resolve-dispute db dispute)
        _ (when-not eid (throw (ex-info "Dispute not found" {:spec dispute})))
        resolved-at (java.util.Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity eid
                            :entity-type :dispute
                            :facet :dispute/state
                            :to :resolved
                            :changed-at resolved-at
                            :changed-by-uid resolved-by-uid
                            :reason resolution}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))
        attrs-update (cond-> {:db/id eid
                              :dispute/resolution resolution
                              :dispute/resolved-by-uid resolved-by-uid}
                       supporting-doc (assoc :dispute/supporting-doc
                                             supporting-doc))
        core-tx (into [attrs-update] status-tx)
        vf (or vt-from resolved-at)
        vt (or vt-to   kbt/forever)]
    (d/transact conn (kbt/with-vt core-tx vf vt))))

(ns kontor.collections.promise
  "Payment-promise (PTP) lifecycle — ADR-043.

   A `:payment-promise` is a first-class entity capturing a
   customer's verbal/written commitment to pay. Distinguished from
   `:payment-application` (which records what actually moved): the
   promise is the *intent*; the application is the *fact*.

   Suppresses dunning while `:status :open` (`kontor.collections.
   dunning` consults this).

   Sweeper: `sweep-broken-promises!` flips `:open → :broken` when
   the `:promised-by-date` has passed without a matching
   `:payment-application`."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.payment-promise/external-id ?xid]]
       db external-id))

(defn resolve-promise
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-promise
  [db spec]
  (when-let [eid (resolve-promise db spec)]
    (d/pull db
            '[* {:kontor.payment-promise/case [:kontor.collection-case/code]
                 :kontor.payment-promise/invoice [:kontor.invoice/external-id]
                 :kontor.payment-promise/commodity [:kontor.commodity/symbol]
                 :kontor.payment-promise/captured-by-uid [:kontor.audit/create-uid]
                 :kontor.payment-promise/supporting-doc [:kontor.audit-doc/code]}]
            eid)))

;; ============================================================================
;; Queries
;; ============================================================================

(defn open-promises-for-case
  "All :open promises on a case. Used by dunning to suppress
   emissions."
  [db case-eid]
  (->> (d/q '[:find [?p ...]
              :in $ ?case
              :where
              [?p :kontor.payment-promise/case ?case]
              [?p :kontor.payment-promise/status :open]]
            db case-eid)
       (map #(pull-promise db %))
       vec))

(defn open-promises-for-invoice
  [db invoice-eid]
  (->> (d/q '[:find [?p ...]
              :in $ ?inv
              :where
              [?p :kontor.payment-promise/invoice ?inv]
              [?p :kontor.payment-promise/status :open]]
            db invoice-eid)
       (map #(pull-promise db %))
       vec))

(defn any-open-promise-for-partner-invoice?
  "True iff there's any :open promise (case-level OR invoice-level)
   that would suppress dunning for the given invoice."
  [db case-eid invoice-eid]
  (or (seq (open-promises-for-case db case-eid))
      (seq (open-promises-for-invoice db invoice-eid))))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare record-promise-tx-data
         transition-promise-tx-data
         renegotiate-tx-data)

(defn record-promise!
  "Capture a PTP. Required opts: :external-id, :case, :amount,
   :commodity, :promised-by-date, :captured-by-uid.

   Optional: :invoice (omit for case-level), :captured-via, :notes,
   :supporting-doc.

   The pure tx-data builder is `record-promise-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [recorded-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (record-promise-tx-data
                        (d/db conn) (assoc opts :recorded-at recorded-at))
                       (or vt-from recorded-at)
                       (or vt-to kbt/forever)))))

(defn record-promise-tx-data
  "Pure tx-data builder for `record-promise!` (ADR-068). Optional
   `:tempid` (default `\"ptp-1\"`) and `:recorded-at` (default now)."
  [db {:keys [external-id case invoice amount commodity
              promised-by-date captured-by-uid captured-via notes
              supporting-doc tempid recorded-at]
       :or {tempid "ptp-1"}}]
  (when-not external-id      (throw (ex-info ":external-id required" {})))
  (when-not case             (throw (ex-info ":case required" {})))
  (when-not amount           (throw (ex-info ":amount required" {})))
  (when-not commodity        (throw (ex-info ":commodity required" {})))
  (when-not promised-by-date (throw (ex-info ":promised-by-date required" {})))
  (when-not captured-by-uid  (throw (ex-info ":captured-by-uid required" {})))
  (let [recorded-at (or recorded-at (java.util.Date.))
        row (cond-> {:db/id tempid
                     :kontor.payment-promise/external-id external-id
                     :kontor.payment-promise/case case
                     :kontor.payment-promise/amount amount
                     :kontor.payment-promise/commodity commodity
                     :kontor.payment-promise/promised-by-date promised-by-date
                     :kontor.payment-promise/captured-by-uid captured-by-uid
                     :kontor.payment-promise/status :open}
              invoice        (assoc :kontor.payment-promise/invoice invoice)
              captured-via   (assoc :kontor.payment-promise/captured-via captured-via)
              notes          (assoc :kontor.payment-promise/notes notes)
              supporting-doc (assoc :kontor.payment-promise/supporting-doc supporting-doc))
        ;; Status-history nil → :open via the status machine
        ;; (atomic).
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity tempid
                    :entity-type :payment-promise
                    :facet :kontor.payment-promise/status
                    :from :nil
                    :to :open
                    :changed-at recorded-at
                    :changed-by-uid captured-by-uid
                    :reason :promise-recorded})]
    (into [row] status-tx)))

(defn- transition-promise!
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (transition-promise-tx-data
                        (d/db conn) (assoc opts :changed-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn transition-promise-tx-data
  "Pure tx-data builder for `transition-promise!` (ADR-068)."
  [db {:keys [promise to changed-by-uid reason reason-note changed-at]}]
  (let [eid (resolve-promise db promise)
        _ (when-not eid (throw (ex-info "Promise not found" {:spec promise})))
        now (or changed-at (java.util.Date.))]
    (sm/record-status-change-tx-data
     db
     (cond-> {:entity eid
              :entity-type :payment-promise
              :facet :kontor.payment-promise/status
              :to to
              :changed-at now
              :changed-by-uid changed-by-uid}
       reason      (assoc :reason reason)
       reason-note (assoc :reason-note reason-note)))))

(defn mark-promise-kept-tx-data
  "Pure tx-data builder for `mark-promise-kept!` (ADR-068). Returns
   the status-machine tx-data for `:open → :kept`; the `!` wrapper
   stamps `:tx/valid-from` and routes through the gate. Composes into
   `kontor.workflow.process` step lists where a payment-application + the
   promise transition must commit in one tx."
  [db {:keys [promise changed-by-uid reason reason-note changed-at]
       :as _opts}]
  (transition-promise-tx-data
   db {:promise promise
       :to :kept
       :changed-by-uid changed-by-uid
       :reason (or reason :promise-kept)
       :reason-note reason-note
       :changed-at changed-at}))

(defn mark-promise-kept!
  "Promise → :kept (a :payment-application reduced the open balance
   by enough). Caller passes the matching application eid via
   :matching-application if available; we record it as a reference
   for audit.

   The pure tx-data builder is `mark-promise-kept-tx-data` (ADR-068)."
  [conn {:keys [promise matching-application changed-by-uid reason
                reason-note vt-from vt-to]}]
  (transition-promise! conn {:promise promise
                             :to :kept
                             :changed-by-uid changed-by-uid
                             :reason (or reason :promise-kept)
                             :reason-note reason-note
                             :vt-from vt-from :vt-to vt-to}))

(defn mark-promise-broken!
  "Promise → :broken. Usually fired by `sweep-broken-promises!` when
   :promised-by-date passes without a matching payment, but can be
   called manually."
  [conn {:keys [promise changed-by-uid reason reason-note
                vt-from vt-to]}]
  (transition-promise! conn {:promise promise
                             :to :broken
                             :changed-by-uid changed-by-uid
                             :reason (or reason :promise-broken)
                             :reason-note reason-note
                             :vt-from vt-from :vt-to vt-to}))

(defn renegotiate!
  "Promise → :renegotiated (replaced by a new promise). Doesn't
   write the new promise — caller composes both.

   The pure tx-data builder is `renegotiate-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (renegotiate-tx-data (d/db conn) opts)))

(defn renegotiate-tx-data
  "Pure tx-data builder for `renegotiate!` (ADR-068)."
  [db {:keys [promise changed-by-uid reason reason-note]}]
  (let [eid (resolve-promise db promise)
        _ (when-not eid (throw (ex-info "Promise not found" {:spec promise})))]
    (sm/record-status-change-tx-data
     db
     (cond-> {:entity eid
              :entity-type :payment-promise
              :facet :kontor.payment-promise/status
              :to :renegotiated
              :changed-by-uid changed-by-uid}
       reason      (assoc :reason (or reason :renegotiated))
       reason-note (assoc :reason-note reason-note)))))

;; ============================================================================
;; Sweeper (per ADR-041 :auto-after-millis pattern, but date-driven)
;; ============================================================================

(defn sweep-broken-promises!
  "Find all :open promises whose :promised-by-date is in the past
   relative to `:now` (default today) and flip them to :broken.

   Caller is responsible for the system-actor :changed-by-uid.

   Real-world wiring: a daily cron or sweep job runs this; the
   broken-promise transitions can in turn re-open the parent case
   via :kontor.collection-case/state :promised → :open. This fn handles only
   the promise side; case-side transition is a separate call."
  [conn {:keys [now system-uid]
         :or {now (java.util.Date.)}}]
  (when-not system-uid
    (throw (ex-info ":system-uid required for sweep audit-trail" {})))
  (let [db (d/db conn)
        now-ms (.getTime ^java.util.Date now)
        open-eids (d/q '[:find [?p ...]
                         :in $ ?now-ms
                         :where
                         [?p :kontor.payment-promise/status :open]
                         [?p :kontor.payment-promise/promised-by-date ?by]
                         [(.getTime ^java.util.Date ?by) ?by-ms]
                         [(< ?by-ms ?now-ms)]]
                       db now-ms)]
    (doseq [eid open-eids]
      (mark-promise-broken! conn
                            {:promise eid
                             :changed-by-uid system-uid
                             :reason :system-scheduled
                             :reason-note "Sweeper detected lapsed :promised-by-date"}))
    {:swept (count open-eids)
     :affected open-eids}))

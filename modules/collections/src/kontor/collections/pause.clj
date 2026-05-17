(ns kontor.collections.pause
  "Dunning pause helpers — ADR-043 P0-5 fix, ported to the status
   machine per the 2026-05-13 P0-5 review fix.

   A `:dunning-pause` is an explicit hold on dunning for a case,
   distinct from the implicit suppression caused by an open dispute
   or open PTP. Reasons include holiday freeze, key-account
   exception, legal hold.

   Active = the pause's `:dunning-pause/state` is `:placed` (resolved
   bitemporally via :tx/valid-from) AND not yet expired by
   `:expires-at`. The placement date derives from the creating tx's
   `:tx/valid-from` (kontor.bitemporal, ADR-048)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Predicates
;; ============================================================================

(defn- expired? [p as-of-ms]
  (when-let [exp (:dunning-pause/expires-at p)]
    (<= (.getTime ^java.util.Date exp) as-of-ms)))

(defn- state-at [db pause-eid ^java.util.Date as-of]
  (:dunning-pause/state
   (d/pull (d/valid-at db as-of) [:dunning-pause/state] pause-eid)))

(defn active-pauses-for-case
  "Pulled `:dunning-pause` rows active at `:as-of-valid` for a case.
   Returns vec of pulled maps.

   Active = pause is visible at as-of-valid (via d/valid-at) AND its
   :dunning-pause/state at as-of-valid is :placed AND it isn't yet
   expired by :expires-at."
  ([db case-eid] (active-pauses-for-case db case-eid nil))
  ([db case-eid {:keys [as-of-valid]}]
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of-valid)
         vt-db (d/valid-at db as-of-valid)
         rows (d/q '[:find [?p ...]
                     :in $ ?case
                     :where
                     [?p :dunning-pause/case ?case]]
                   vt-db case-eid)]
     (->> rows
          (map #(d/pull db '[*] %))
          (filter #(= :placed (state-at db (:db/id %) as-of-valid)))
          (remove #(expired? % as-of-ms))
          vec))))

(defn any-active-pause?
  "True iff there is at least one active `:dunning-pause` row for
   the case at :as-of-valid."
  ([db case-eid] (any-active-pause? db case-eid nil))
  ([db case-eid opts]
   (boolean (seq (active-pauses-for-case db case-eid opts)))))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare place-pause-tx-data release-pause-tx-data)

(defn place-pause!
  "Pause dunning on a case.

   Status machine: nil → :placed. Writes a :status-history row +
   stamps :tx/valid-from on the writing tx (default = now; override
   via :vt-from for backdated placements).

   Required opts:
     :case             ref/eid
     :reason-code      keyword #{:dispute :ptp-active :holiday-freeze
                                  :key-account-exception :legal-hold}
     :placed-by-uid    ref to :create/uid

   Optional opts:
     :expires-at       instant (auto-resume). nil = manual-only.
     :notes            string
     :supporting-doc   ref to :audit-doc
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)

   The pure tx-data builder is `place-pause-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [placed-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (place-pause-tx-data
                        (d/db conn) (assoc opts :placed-at placed-at))
                       (or vt-from placed-at)
                       (or vt-to kbt/forever)))))

(defn place-pause-tx-data
  "Pure tx-data builder for `place-pause!` (ADR-068). Optional
   `:tempid` (default `\"pause-1\"`) and `:placed-at` (default now)."
  [db {:keys [case reason-code placed-by-uid expires-at notes supporting-doc
              tempid placed-at]
       :or {tempid "pause-1"}}]
  (when-not case          (throw (ex-info ":case required" {})))
  (when-not reason-code   (throw (ex-info ":reason-code required" {})))
  (when-not placed-by-uid (throw (ex-info ":placed-by-uid required" {})))
  (let [placed-at (or placed-at (java.util.Date.))
        row (cond-> {:db/id tempid
                     :dunning-pause/case case
                     :dunning-pause/reason-code reason-code
                     :dunning-pause/placed-by-uid placed-by-uid
                     :dunning-pause/state :placed}
              expires-at     (assoc :dunning-pause/expires-at expires-at)
              notes          (assoc :dunning-pause/notes notes)
              supporting-doc (assoc :dunning-pause/supporting-doc supporting-doc))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity tempid
                            :entity-type :dunning-pause
                            :facet :dunning-pause/state
                            :from :nil
                            :to :placed
                            :changed-at placed-at
                            :changed-by-uid placed-by-uid
                            :reason reason-code}
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [row] status-tx)))

(defn release-pause!
  "Release a specific pause row. Status machine: :placed → :released.

   Required: :pause-eid + :released-by-uid.
   Optional: :reason, :reason-note, :notes, :supporting-doc,
             :vt-from, :vt-to.

   The pure tx-data builder is `release-pause-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (release-pause-tx-data
                        (d/db conn) (assoc opts :released-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn release-pause-tx-data
  "Pure tx-data builder for `release-pause!` (ADR-068)."
  [db {:keys [pause-eid released-by-uid reason reason-note notes
              supporting-doc released-at]}]
  (when-not pause-eid        (throw (ex-info ":pause-eid required" {})))
  (when-not released-by-uid  (throw (ex-info ":released-by-uid required" {})))
  (let [now (or released-at (java.util.Date.))
        update (cond-> {:db/id pause-eid}
                 notes          (assoc :dunning-pause/notes notes)
                 supporting-doc (assoc :dunning-pause/supporting-doc supporting-doc))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity pause-eid
                            :entity-type :dunning-pause
                            :facet :dunning-pause/state
                            :to :released
                            :changed-at now
                            :changed-by-uid released-by-uid
                            :reason (or reason :pause-released)}
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (into [update] status-tx)))

(ns kontor.collections.pause
  "Dunning pause helpers — ADR-043 P0-5 fix.

   A `:dunning-pause` is an explicit hold on dunning for a case,
   distinct from the implicit suppression caused by an open dispute
   or open PTP. Reasons include holiday freeze, key-account
   exception, legal hold.

   Active = `:placed-at ≤ as-of-valid` AND not yet released AND
   not yet expired (mirrors `:credit-hold` shape)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Predicates
;; ============================================================================

(defn- expired? [p as-of-ms]
  (when-let [exp (:dunning-pause/expires-at p)]
    (<= (.getTime ^java.util.Date exp) as-of-ms)))

(defn- released? [p as-of-ms]
  (when-let [rel (:dunning-pause/released-at p)]
    (<= (.getTime ^java.util.Date rel) as-of-ms)))

(defn active-pauses-for-case
  "Pulled `:dunning-pause` rows active at `:as-of-valid` for a case.
   Returns vec of pulled maps sorted by :placed-at."
  ([db case-eid] (active-pauses-for-case db case-eid nil))
  ([db case-eid {:keys [as-of-valid]}]
   (let [as-of-valid (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of-valid)
         rows (d/q '[:find [?p ...]
                     :in $ ?case ?as-of-ms
                     :where
                     [?p :dunning-pause/case ?case]
                     [?p :dunning-pause/placed-at ?placed]
                     [(.getTime ^java.util.Date ?placed) ?placed-ms]
                     [(<= ?placed-ms ?as-of-ms)]]
                   db case-eid as-of-ms)]
     (->> rows
          (map #(d/pull db '[*] %))
          (remove #(or (released? % as-of-ms)
                       (expired? % as-of-ms)))
          (sort-by :dunning-pause/placed-at)
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

(defn place-pause!
  "Pause dunning on a case.

   Required opts:
     :case             ref/eid
     :reason-code      keyword #{:dispute :ptp-active :holiday-freeze
                                  :key-account-exception :legal-hold}
     :placed-by-uid    ref to :create/uid

   Optional opts:
     :expires-at       instant (auto-resume). nil = manual-only.
     :notes            string
     :supporting-doc   ref to :audit-doc"
  [conn {:keys [case reason-code placed-by-uid expires-at notes supporting-doc]}]
  (when-not case          (throw (ex-info ":case required" {})))
  (when-not reason-code   (throw (ex-info ":reason-code required" {})))
  (when-not placed-by-uid (throw (ex-info ":placed-by-uid required" {})))
  (let [row (cond-> {:dunning-pause/case case
                     :dunning-pause/reason-code reason-code
                     :dunning-pause/placed-at (java.util.Date.)
                     :dunning-pause/placed-by-uid placed-by-uid}
              expires-at     (assoc :dunning-pause/expires-at expires-at)
              notes          (assoc :dunning-pause/notes notes)
              supporting-doc (assoc :dunning-pause/supporting-doc supporting-doc))]
    (d/transact conn [row])))

(defn release-pause!
  "Release a specific pause row. Required: :pause-eid + :released-
   by-uid. Optional: :notes."
  [conn {:keys [pause-eid released-by-uid notes]}]
  (when-not pause-eid        (throw (ex-info ":pause-eid required" {})))
  (when-not released-by-uid  (throw (ex-info ":released-by-uid required" {})))
  (d/transact conn
              [(cond-> {:db/id pause-eid
                        :dunning-pause/released-at (java.util.Date.)
                        :dunning-pause/released-by-uid released-by-uid}
                 notes (assoc :dunning-pause/notes notes))]))

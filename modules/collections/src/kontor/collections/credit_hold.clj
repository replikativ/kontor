(ns kontor.collections.credit-hold
  "Per-(partner, entity) credit-hold overlay — ADR-043.

   Composes with ADR-039's `:partner/credit-status` scalar as the
   default. The resolver `credit-status-for` walks:

     1. Any active `:credit-hold` row for (partner, entity) at
        `:as-of-valid` — i.e. `:placed-at ≤ as-of` and (no
        `:released-at` OR `:released-at > as-of`).
     2. Otherwise the partner's `:partner/credit-status` scalar
        (ADR-039 default — `:open | :hold | :review | :closed`).

   This makes single-entity tenants experience zero complexity (they
   never write `:credit-hold` rows; the scalar suffices) while multi-
   entity tenants can place a hold for one subsidiary without
   blocking the same partner across other subsidiaries.

   Mirrors the org-override pattern of `:status-transition/applies-
   to-org` (status_machine.clj:71-86)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn active-holds-for
  "Pulled `:credit-hold` rows that are active at `:as-of-valid`
   (default: now) for (partner, entity)."
  ([db {:keys [partner entity as-of-valid]}]
   (let [as-of (or as-of-valid (java.util.Date.))
         as-of-ms (.getTime ^java.util.Date as-of)
         rows (d/q '[:find [?h ...]
                     :in $ ?p ?e ?as-of-ms
                     :where
                     [?h :credit-hold/partner ?p]
                     [?h :credit-hold/entity ?e]
                     [?h :credit-hold/placed-at ?placed]
                     [(.getTime ^java.util.Date ?placed) ?placed-ms]
                     [(<= ?placed-ms ?as-of-ms)]]
                   db partner entity as-of-ms)]
     (->> rows
          (map #(d/pull db '[*] %))
          (remove (fn [h]
                    (when-let [released (:credit-hold/released-at h)]
                      (<= (.getTime ^java.util.Date released) as-of-ms))))
          (sort-by :credit-hold/placed-at)
          vec))))

(defn credit-status-for
  "Resolve effective credit-status for a (partner, entity) pair.

   Returns one of:
     :open | :hold | :review | :closed

   Walks per-(partner, entity) `:credit-hold` overlay first; falls
   back to the `:partner/credit-status` scalar (ADR-039)."
  [db {:keys [partner entity as-of-valid] :as opts}]
  (let [active (active-holds-for db opts)]
    (cond
      ;; An active overlay row forces :hold regardless of partner
      ;; scalar.
      (seq active) :hold

      :else
      (or (:partner/credit-status (d/pull db [:partner/credit-status] partner))
          :open))))

(defn current-hold
  "Return the most-recent active hold row for (partner, entity), or
   nil if none active."
  [db opts]
  (last (active-holds-for db opts)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn place-hold!
  "Place a per-(partner, entity) credit hold.

   Required opts:
     :partner          ref/eid
     :entity           ref/eid
     :reason-code      keyword
     :placed-by-uid    ref to :create/uid

   Optional opts:
     :approver-uid    ref to :create/uid (distinct from :placed-by-
                      uid for ADR-038 :no-self-approval enforcement
                      at the policy layer)
     :expires-at      instant (auto-release boundary; nil = manual)
     :notes           string
     :supporting-doc  ref to :audit-doc"
  [conn {:keys [partner entity reason-code placed-by-uid approver-uid
                expires-at notes supporting-doc]}]
  (when-not partner       (throw (ex-info ":partner required" {})))
  (when-not entity        (throw (ex-info ":entity required" {})))
  (when-not reason-code   (throw (ex-info ":reason-code required" {})))
  (when-not placed-by-uid (throw (ex-info ":placed-by-uid required" {})))
  (let [placed-at (java.util.Date.)
        row (cond-> {:credit-hold/partner partner
                     :credit-hold/entity entity
                     :credit-hold/reason-code reason-code
                     :credit-hold/placed-at placed-at
                     :credit-hold/placed-by-uid placed-by-uid}
              approver-uid   (assoc :credit-hold/approver-uid approver-uid)
              expires-at     (assoc :credit-hold/expires-at expires-at)
              notes          (assoc :credit-hold/notes notes)
              supporting-doc (assoc :credit-hold/supporting-doc supporting-doc))]
    (d/transact conn [row])))

(defn release-hold!
  "Release a specific `:credit-hold` row. Records :released-at +
   :released-by-uid; the row stays in the DB for audit.

   Required opts:
     :hold-eid         the :credit-hold eid (resolve via
                       `current-hold` or `active-holds-for`)
     :released-by-uid  ref to :create/uid

   Optional:
     :notes            string
     :supporting-doc   ref to :audit-doc"
  [conn {:keys [hold-eid released-by-uid notes supporting-doc]}]
  (when-not hold-eid         (throw (ex-info ":hold-eid required" {})))
  (when-not released-by-uid  (throw (ex-info ":released-by-uid required" {})))
  (let [update (cond-> {:db/id hold-eid
                        :credit-hold/released-at (java.util.Date.)
                        :credit-hold/released-by-uid released-by-uid}
                 notes          (assoc :credit-hold/notes notes)
                 supporting-doc (assoc :credit-hold/supporting-doc
                                       supporting-doc))]
    (d/transact conn [update])))

(defn release-all-for!
  "Convenience: release every active hold for (partner, entity)."
  [conn {:keys [partner entity released-by-uid notes]
         :as opts}]
  (let [holds (active-holds-for (d/db conn) opts)]
    (doseq [h holds]
      (release-hold! conn
                     {:hold-eid (:db/id h)
                      :released-by-uid released-by-uid
                      :notes notes}))
    (count holds)))

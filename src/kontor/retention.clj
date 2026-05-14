(ns kontor.retention
  "Retention policy + sweeper (ADR-050).

   A `:retention-policy` says: entities of type T, in jurisdiction J,
   expire `duration-years` after their `triggered-by` anchor date,
   via `expiry-action`. The kernel ships only the SHAPE — per-
   jurisdiction policy *data* lives in l10n companion modules
   (`kontor-l10n-de` ships HGB §257; `kontor-l10n-us` ships SOX §103;
   …). A kontor install with no l10n module has retention disabled
   by construction.

   ## The hold-blocks-expiry invariant (composition with ADR-049)

   `apply-expiry!` does NOT call `d/transact` directly — it routes
   the purge/anonymize tx-data through
   `[:db.fn/call kontor.validation/validate-and-apply …]`. That means
   the ADR-049 hold-middleware fires on every expiry action: `:purge`
   is a `:db/purge`, `:anonymize` is N `:db.purge/attribute` ops, both
   of which the hold-middleware recognizes. The sweeper *structurally
   cannot* expire data under an active legal hold — even a buggy
   caller hitting `apply-expiry!` on a held entity gets the
   `:legal-hold/purge-blocked` exception.

   `eligible?` *also* consults `legal-hold/entities-held?` — but that
   is an optimization and a visibility feature (the sweeper reports
   'this entity would expire today but is on hold'); the load-bearing
   guarantee is the middleware.

   ## Why kernel ships the sweeper

   The sweeper must respect legal holds. If it lived in consumer-land
   a consumer could write their own expiry loop bypassing the hold
   check. Shipping `sweep!` + `apply-expiry!` in the kernel — and
   routing `apply-expiry!` through `validate-and-apply` — makes the
   hold check unavoidable. The kernel does NOT ship a scheduler (per
   ADR-010); the consumer schedules `sweep-and-apply!` on its own
   cadence.

   ## v1 scope

   - `:purge` and `:anonymize` ship fully. `:archive-to-cold-storage`
     is deferred — `apply-expiry!` throws an explicit 'not
     implemented' for it.
   - Clock anchors are *direct-attribute* only: `:triggered-by` must
     be an attribute on the entity itself. Entities lacking it are
     skipped. Cross-entity anchors are a documented follow-up."
  (:require [clojure.string]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.legal-hold :as legal-hold]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.time ZoneOffset]
           [java.util Date]))

;; ============================================================================
;; Status-transition + approval-policy seeds
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :retention-policy/state
   facet."
  [{:status-transition/entity-type :retention-policy
    :status-transition/facet :retention-policy/state
    :status-transition/from :nil
    :status-transition/to :draft
    :status-transition/active true
    :status-transition/name "Draft Retention Policy"}
   {:status-transition/entity-type :retention-policy
    :status-transition/facet :retention-policy/state
    :status-transition/from :draft
    :status-transition/to :active
    :status-transition/active true
    :status-transition/name "Activate Retention Policy"}
   {:status-transition/entity-type :retention-policy
    :status-transition/facet :retention-policy/state
    :status-transition/from :active
    :status-transition/to :superseded
    :status-transition/active true
    :status-transition/name "Supersede Retention Policy"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Activating a retention policy is a
   consequential, audit-visible change — the auditor needs to know
   why a retention rule came into force."
  [{:approval-policy/entity-type     :retention-policy
    :approval-policy/facet           :retention-policy/state
    :approval-policy/transition-from :draft
    :approval-policy/transition-to   :active
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :retention-policy
    :approval-policy/facet           :retention-policy/state
    :approval-policy/transition-from :draft
    :approval-policy/transition-to   :active
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}])

(defn install-seeds!
  "Idempotently transact the retention-policy status-transition +
   approval-policy seeds. Called from `kontor.core/install-schema!`.
   Guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat)."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :status-transition/entity-type :retention-policy]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

;; ============================================================================
;; Policy resolution
;; ============================================================================

(defn- in-effect?
  "True iff `as-of` falls in [effective-from, effective-until). A nil
   effective-until is open-ended."
  [^Date as-of ^Date from ^Date until]
  (and (>= (.compareTo as-of from) 0)
       (or (nil? until) (< (.compareTo as-of until) 0))))

(defn policy-for
  "Resolve the active `:retention-policy` for `entity-type` at valid-
   time `as-of`, preferring a jurisdiction-specific policy over a
   global one. Among candidates the ADR-026 tiebreaker applies:
   the one with the latest `:effective-from` not exceeding `as-of`.

   Returns the policy eid, or nil when no active policy covers the
   combination (retention is then disabled for that entity-type).

   Opts: `:jurisdiction` (a :country eid; nil = global only),
   `:as-of` (instant; default now)."
  [db entity-type {:keys [jurisdiction as-of]}]
  (let [as-of (or as-of (Date.))
        ;; get-else rejects a nil default, so use sentinels and
        ;; normalize them back to nil after the query.
        candidates
        (->> (d/q '[:find ?p ?from ?until ?juris
                    :in $ ?etype
                    :where
                    [?p :retention-policy/applies-to ?etype]
                    [?p :retention-policy/state :active]
                    [?p :retention-policy/effective-from ?from]
                    [(get-else $ ?p :retention-policy/effective-until :__none) ?until]
                    [(get-else $ ?p :retention-policy/jurisdiction :__none) ?juris]]
                  db entity-type)
             (map (fn [[p from until juris]]
                    [p from
                     (when-not (= until :__none) until)
                     (when-not (= juris :__none) juris)]))
             (filter (fn [[_ from until juris]]
                       (and (in-effect? as-of from until)
                            (or (nil? juris)
                                (= juris jurisdiction)))))
             ;; jurisdiction-specific beats global; then latest
             ;; effective-from wins (ADR-026 tiebreaker).
             (sort-by (fn [[_ from _ juris]]
                        [(if (= juris jurisdiction) 1 0)
                         (.getTime ^Date from)])))]
    (first (last candidates))))

;; ============================================================================
;; Deadline + eligibility
;; ============================================================================

(defn- plus-years
  "Add `n` whole years to a `java.util.Date`, returning a Date."
  ^Date [^Date d n]
  (-> (.toInstant d)
      (.atZone ZoneOffset/UTC)
      (.toLocalDate)
      (.plusYears n)
      (.atStartOfDay ZoneOffset/UTC)
      (.toInstant)
      (Date/from)))

(defn retention-deadline
  "Compute the retention deadline `Date` for `entity-eid` under
   `policy-eid` — the entity's `:triggered-by` anchor value plus the
   policy's `:duration-years`. Returns nil when the entity lacks the
   anchor attribute (v1: direct-attribute anchors only — the entity
   is then skipped by the sweeper)."
  [db entity-eid policy-eid]
  (let [{:retention-policy/keys [triggered-by duration-years]}
        (d/pull db [:retention-policy/triggered-by
                    :retention-policy/duration-years]
                policy-eid)
        anchor (get (d/pull db [triggered-by] entity-eid) triggered-by)]
    (when (instance? Date anchor)
      (plus-years anchor duration-years))))

(defn eligible?
  "True iff `entity-eid` has aged past its `policy-eid` retention
   deadline as of `:as-of` (default now) AND is not under an active
   legal hold. The hold check here is a visibility/optimization
   feature — the load-bearing guarantee is that `apply-expiry!`
   routes through the ADR-049 hold-middleware."
  [db entity-eid policy-eid {:keys [as-of]}]
  (let [as-of (or as-of (Date.))
        deadline (retention-deadline db entity-eid policy-eid)]
    (boolean
     (and deadline
          (<= (.compareTo deadline as-of) 0)
          (not (legal-hold/entity-held? db entity-eid))))))

;; ============================================================================
;; Sweeper
;; ============================================================================

(defn- candidate-eids
  "All eids carrying the policy's `:triggered-by` anchor attribute —
   the v1 enumeration key for the sweep."
  [db policy-eid]
  (let [{:retention-policy/keys [triggered-by]}
        (d/pull db [:retention-policy/triggered-by] policy-eid)]
    (d/q '[:find [?e ...]
           :in $ ?attr
           :where [?e ?attr _]]
         db triggered-by)))

(defn due-for-expiry
  "Walk every entity carrying `policy-eid`'s `:triggered-by` anchor
   and return a vec of work-items for those past their retention
   deadline as of `:as-of` (default now):

     {:entity-eid     <eid>
      :policy-eid     <policy-eid>
      :action         <:purge | :anonymize | :archive-to-cold-storage>
      :deadline       <Date>
      :blocked-by-hold? <bool>}

   Items where `:blocked-by-hold?` is true are still returned — for
   the 'this would expire today but is on hold' visibility — but
   `sweep-and-apply!` will skip them. The held set is computed once
   for the whole batch via `legal-hold/entities-held?`."
  [db policy-eid {:keys [as-of]}]
  (let [as-of (or as-of (Date.))
        {:retention-policy/keys [expiry-action]}
        (d/pull db [:retention-policy/expiry-action] policy-eid)
        cands (candidate-eids db policy-eid)
        past-deadline
        (keep (fn [eid]
                (when-let [deadline (retention-deadline db eid policy-eid)]
                  (when (<= (.compareTo deadline as-of) 0)
                    {:entity-eid eid
                     :policy-eid policy-eid
                     :action expiry-action
                     :deadline deadline})))
              cands)
        held (legal-hold/entities-held? db (map :entity-eid past-deadline))]
    (mapv (fn [item]
            (assoc item :blocked-by-hold?
                   (contains? held (:entity-eid item))))
          past-deadline)))

(defn sweep!
  "Dry-run-friendly planner. For `entity-type` (resolved to its
   active policy via `policy-for`), return the vec of expiry
   work-items from `due-for-expiry`. Pure read — produces no writes;
   compose with `apply-expiry!` or call `sweep-and-apply!`.

   Opts: `:entity-type` (required), `:jurisdiction`, `:as-of`.
   Returns `[]` when no active policy covers the entity-type
   (retention disabled for it)."
  [db {:keys [entity-type jurisdiction as-of]}]
  (when-not entity-type (throw (ex-info ":entity-type required" {})))
  (if-let [policy-eid (policy-for db entity-type
                                  {:jurisdiction jurisdiction :as-of as-of})]
    (due-for-expiry db policy-eid {:as-of as-of})
    []))

(defn apply-expiry!
  "Execute one expiry work-item. Routes the destructive tx-data
   through `[:db.fn/call kontor.validation/validate-and-apply …]` so
   the ADR-049 hold-middleware (and sealing, period, …) fire — the
   sweeper structurally cannot expire data under an active legal
   hold.

   `:purge`     — `[:db/purge entity-eid]`.
   `:anonymize` — `[:db.purge/attribute entity-eid attr]` for each
                  field in the policy's `:anonymize-fields`. The row
                  survives; the PII fields are gone.
   `:archive-to-cold-storage` — deferred in v1; throws.

   Returns the tx-report."
  [conn {:keys [entity-eid policy-eid action]}]
  (let [db (d/db conn)
        tx-data
        (case action
          :purge
          [[:db/purge entity-eid]]

          :anonymize
          (let [{:retention-policy/keys [anonymize-fields]}
                (d/pull db [:retention-policy/anonymize-fields] policy-eid)]
            (when (empty? anonymize-fields)
              (throw (ex-info ":anonymize action requires :retention-policy/anonymize-fields"
                              {:policy-eid policy-eid})))
            (mapv (fn [attr] [:db.purge/attribute entity-eid attr])
                  anonymize-fields))

          :archive-to-cold-storage
          (throw (ex-info ":archive-to-cold-storage is not implemented in v1 (ADR-050)"
                          {:entity-eid entity-eid :policy-eid policy-eid}))

          (throw (ex-info "Unknown :expiry-action"
                          {:action action :entity-eid entity-eid})))]
    (d/transact conn [[:db.fn/call validation/validate-and-apply tx-data]])))

(defn sweep-and-apply!
  "Sweep `entity-type` and apply every non-held expiry work-item.
   Held items are skipped (they stay due — the next sweep after the
   hold releases will pick them up). Returns
   `{:applied [<item> …] :blocked [<item> …]}`.

   Opts: `:entity-type` (required), `:jurisdiction`, `:as-of`,
   `:dry-run?` (when true, applies nothing — returns the same shape
   with `:applied` empty and every eligible item under `:blocked`-
   shaped `:would-apply`)."
  [conn {:keys [entity-type jurisdiction as-of dry-run?]}]
  (let [items (sweep! (d/db conn)
                      {:entity-type entity-type
                       :jurisdiction jurisdiction
                       :as-of as-of})
        {blocked true unblocked false} (group-by :blocked-by-hold? items)]
    (if dry-run?
      {:applied [] :blocked (vec blocked) :would-apply (vec unblocked)}
      {:applied (mapv (fn [item]
                        (apply-expiry! conn item)
                        item)
                      unblocked)
       :blocked (vec blocked)})))

;; ============================================================================
;; Transactors — policy lifecycle
;; ============================================================================

(defn define-policy!
  "Create a retention policy in `:draft` state. Drafting is free
   (no approval-policy gates); `activate-policy!` is what ADR-038
   governs.

   Required opts:
     :code             string
     :applies-to       coll of entity-type keywords
     :duration-years   long
     :triggered-by     keyword (the clock-anchor attribute)
     :expiry-action    keyword (:purge | :anonymize |
                       :archive-to-cold-storage)
     :effective-from   instant
     :legal-basis      string (statute reference)

   Optional:
     :jurisdiction     ref to :country (nil = global)
     :effective-until  instant (nil = open-ended)
     :anonymize-fields coll of attribute keywords (for :anonymize)
     :changed-by-uid   ref to :create/uid
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)"
  [conn {:keys [code applies-to duration-years triggered-by expiry-action
                effective-from legal-basis jurisdiction effective-until
                anonymize-fields changed-by-uid vt-from vt-to]}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not (seq applies-to) (throw (ex-info ":applies-to required" {})))
  (when-not duration-years (throw (ex-info ":duration-years required" {})))
  (when-not triggered-by   (throw (ex-info ":triggered-by required" {})))
  (when-not expiry-action  (throw (ex-info ":expiry-action required" {})))
  (when-not effective-from (throw (ex-info ":effective-from required" {})))
  (when-not legal-basis    (throw (ex-info ":legal-basis required" {})))
  (when (and (= expiry-action :anonymize) (empty? anonymize-fields))
    (throw (ex-info ":anonymize expiry-action requires :anonymize-fields" {})))
  (let [db (d/db conn)
        now (Date.)
        policy-tempid "policy-1"
        row (cond-> {:db/id policy-tempid
                     :retention-policy/code code
                     :retention-policy/applies-to (vec applies-to)
                     :retention-policy/duration-years duration-years
                     :retention-policy/triggered-by triggered-by
                     :retention-policy/expiry-action expiry-action
                     :retention-policy/effective-from effective-from
                     :retention-policy/legal-basis legal-basis
                     :retention-policy/state :draft}
              jurisdiction         (assoc :retention-policy/jurisdiction jurisdiction)
              effective-until      (assoc :retention-policy/effective-until effective-until)
              (seq anonymize-fields) (assoc :retention-policy/anonymize-fields
                                            (vec anonymize-fields)))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity policy-tempid
                            :entity-type :retention-policy
                            :facet :retention-policy/state
                            :from :nil
                            :to :draft
                            :changed-at now
                            :reason :policy-drafted}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (d/transact conn (kbt/with-vt (into [row] status-tx)
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn by-code
  "Resolve a policy's eid by its :retention-policy/code. When several
   rows share a code (effective-dated supersession), returns the one
   with the latest :effective-from."
  [db code]
  (->> (d/q '[:find ?e ?from
              :in $ ?c
              :where
              [?e :retention-policy/code ?c]
              [?e :retention-policy/effective-from ?from]]
            db code)
       (sort-by (fn [[_ ^Date from]] (.getTime from)))
       last
       first))

(defn activate-policy!
  "Transition a policy `:draft → :active`. ADR-038 enforces
   `:requires-supporting-doc` + `:requires-non-empty-reason-note` —
   the auditor needs to know why a retention rule came into force.

   Required opts:
     :policy-eid       the :retention-policy eid
     :supporting-doc   ref to :audit-doc (the retention schedule /
                       legal memo)
     :reason-note      free-text justification
     :changed-by-uid   ref to :create/uid

   Optional: :reason (default :policy-activated), :vt-from, :vt-to."
  [conn {:keys [policy-eid supporting-doc reason-note changed-by-uid
                reason vt-from vt-to]}]
  (when-not policy-eid     (throw (ex-info ":policy-eid required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required (ADR-038)" {})))
  (let [db (d/db conn)
        now (Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity policy-eid
                            :entity-type :retention-policy
                            :facet :retention-policy/state
                            :to :active
                            :changed-at now
                            :reason (or reason :policy-activated)
                            :reason-note reason-note
                            :supporting-doc supporting-doc}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)))
        ;; Also stamp the supporting-doc on the policy row for a
        ;; direct ref (the status-history carries the canonical
        ;; audit trail; this is the queryable denorm).
        update {:db/id policy-eid
                :retention-policy/supporting-doc supporting-doc}]
    (d/transact conn (kbt/with-vt (into [update] status-tx)
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn supersede-policy!
  "Transition a policy `:active → :superseded`. Terminal. To 'update'
   a policy, `define-policy!` a new row with a later
   `:effective-from` and supersede the old one.

   Required opts: :policy-eid, :changed-by-uid.
   Optional: :reason (default :policy-superseded), :reason-note,
             :supporting-doc, :vt-from, :vt-to."
  [conn {:keys [policy-eid changed-by-uid reason reason-note
                supporting-doc vt-from vt-to]}]
  (when-not policy-eid (throw (ex-info ":policy-eid required" {})))
  (let [db (d/db conn)
        now (Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity policy-eid
                            :entity-type :retention-policy
                            :facet :retention-policy/state
                            :to :superseded
                            :changed-at now
                            :reason (or reason :policy-superseded)}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)
                     reason-note    (assoc :reason-note reason-note)
                     supporting-doc (assoc :supporting-doc supporting-doc)))]
    (d/transact conn (kbt/with-vt status-tx
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

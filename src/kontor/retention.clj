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
   `:kontor.legal-hold/purge-blocked` exception.

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
  "ADR-034 :status-transition rows for the :kontor.retention-policy/state
   facet."
  [{:kontor.status-transition/entity-type :retention-policy
    :kontor.status-transition/facet :kontor.retention-policy/state
    :kontor.status-transition/from :nil
    :kontor.status-transition/to :draft
    :kontor.status-transition/active true
    :kontor.status-transition/name "Draft Retention Policy"}
   {:kontor.status-transition/entity-type :retention-policy
    :kontor.status-transition/facet :kontor.retention-policy/state
    :kontor.status-transition/from :draft
    :kontor.status-transition/to :active
    :kontor.status-transition/active true
    :kontor.status-transition/name "Activate Retention Policy"}
   {:kontor.status-transition/entity-type :retention-policy
    :kontor.status-transition/facet :kontor.retention-policy/state
    :kontor.status-transition/from :active
    :kontor.status-transition/to :superseded
    :kontor.status-transition/active true
    :kontor.status-transition/name "Supersede Retention Policy"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Both `:draft → :active` (a
   retention rule coming into force) and `:active → :superseded`
   (retiring the incumbent — which can SHORTEN retention and so
   expand what the next sweep purges) are consequential, audit-
   visible changes. Both are governed (research note 32 P1-3 —
   the recurrence of note 27's unseeded-consequential-edge pattern)."
  (vec
   (for [to   [:active :superseded]
         rule [:requires-supporting-doc
               :requires-non-empty-reason-note]]
     {:kontor.approval-policy/entity-type     :retention-policy
      :kontor.approval-policy/facet           :kontor.retention-policy/state
      :kontor.approval-policy/transition-from (if (= to :active) :draft :active)
      :kontor.approval-policy/transition-to   to
      :kontor.approval-policy/rule            rule
      :kontor.approval-policy/active          true})))

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
                         [?e :kontor.status-transition/entity-type :retention-policy]]
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

   Opts:
     `:jurisdiction` — a :country eid; nil = global only.
     `:as-of`        — instant; default now.
     `:category`     — ADR-075 subject-matter keyword
                       (:payroll | :hr-personnel | :financial | …).
                       When supplied, candidate policies match if
                       either the policy's :kontor.retention-policy/category
                       is nil (applies to any category) OR equals the
                       supplied category. The category-matched-non-nil
                       branch wins the tiebreaker — per-jurisdiction
                       payroll-PII retention floors (GDPR Art. 17 +
                       DE §28f SGB IV) take precedence over generic
                       per-jurisdiction floors when both are seeded.
                       When nil, only category-nil policies match
                       (so callers who haven't classified their data
                       see the legacy generic-floor behavior)."
  [db entity-type {:keys [jurisdiction as-of category]}]
  (let [as-of (or as-of (Date.))
        ;; get-else rejects a nil default, so use sentinels and
        ;; normalize them back to nil after the query.
        candidates
        (->> (d/q '[:find ?p ?from ?until ?juris ?cat
                    :in $ ?etype
                    :where
                    [?p :kontor.retention-policy/applies-to ?etype]
                    [?p :kontor.retention-policy/state :active]
                    [?p :kontor.retention-policy/effective-from ?from]
                    [(get-else $ ?p :kontor.retention-policy/effective-until :__none) ?until]
                    [(get-else $ ?p :kontor.retention-policy/jurisdiction :__none) ?juris]
                    [(get-else $ ?p :kontor.retention-policy/category :__none) ?cat]]
                  db entity-type)
             (map (fn [[p from until juris cat]]
                    [p from
                     (when-not (= until :__none) until)
                     (when-not (= juris :__none) juris)
                     (when-not (= cat   :__none) cat)]))
             (filter (fn [[_ from until juris cat]]
                       (and (in-effect? as-of from until)
                            (or (nil? juris)
                                (= juris jurisdiction))
                            ;; ADR-075 category gate. nil policy-cat
                            ;; matches anything; non-nil policy-cat
                            ;; requires the caller to have classified
                            ;; the entity and supplied a matching
                            ;; :category opt.
                            (or (nil? cat)
                                (= cat category)))))
             ;; (1) category-matched-non-nil wins over category-nil
             ;; (per-category floors are MORE specific than generic);
             ;; (2) jurisdiction-specific beats global;
             ;; (3) latest :effective-from wins (ADR-026 tiebreaker);
             ;; (4) lowest eid as deterministic last-resort tiebreaker
             ;; (research note 32 P2-1).
             (sort-by (fn [[p from _ juris cat]]
                        [(if (and category (= cat category)) 1 0)
                         (if (= juris jurisdiction) 1 0)
                         (.getTime ^Date from)
                         (- p)])))]
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
  (let [{:kontor.retention-policy/keys [triggered-by duration-years]}
        (d/pull db [:kontor.retention-policy/triggered-by
                    :kontor.retention-policy/duration-years]
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

(defn- entity-of-type?
  "Heuristic entity-type check: true iff `eid` carries at least one
   attribute whose namespace is in `applies-to` (a set of entity-type
   keywords). Used to guard the sweeper against a cross-namespace
   `:triggered-by` anchor (a policy `:applies-to [:audit-doc]` anchored
   on `:kontor.status-history/changed-at` must NOT sweep `:status-history`
   rows — research note 32 P1-2)."
  [db eid applies-to]
  ;; Post-W1 schema-rename: kontor-owned attrs live under "kontor.<name>".
  ;; The entity-type value remains the unqualified domain keyword
  ;; (e.g. :audit-doc, :legal-hold), so we match both the bare name and
  ;; the prefixed one. ADR-002 cohabitation: consumer namespaces use
  ;; their own prefix and are caught by the bare-name check.
  (let [ns-strs (set (mapcat (fn [k]
                               [(name k) (str "kontor." (name k))])
                             applies-to))]
    (boolean (some #(contains? ns-strs (namespace %))
                   (keys (d/pull db '[*] eid))))))

(defn- candidate-eids
  "Eids of entities subject to `policy-eid`'s sweep: those carrying
   the policy's `:triggered-by` anchor attribute AND of one of the
   policy's `:applies-to` entity-types. The `:applies-to` cross-check
   guards against a cross-namespace anchor enumerating unintended
   entity types. `limit` caps the candidate set (nil = unbounded) —
   a simple cap, not a full cursor; chunked sweeps are a follow-up."
  [db policy-eid limit]
  (let [{:kontor.retention-policy/keys [triggered-by applies-to]}
        (d/pull db [:kontor.retention-policy/triggered-by
                    :kontor.retention-policy/applies-to]
                policy-eid)
        applies-to (set applies-to)
        raw (d/q '[:find [?e ...]
                   :in $ ?attr
                   :where [?e ?attr _]]
                 db triggered-by)
        typed (filter #(entity-of-type? db % applies-to) raw)]
    (if limit (take limit typed) typed)))

(defn due-for-expiry
  "Walk `policy-eid`'s candidate entities (those carrying its
   `:triggered-by` anchor AND of its `:applies-to` type) and return
   a vec of work-items for those past their retention deadline as of
   `:as-of` (default now):

     {:entity-eid     <eid>
      :policy-eid     <policy-eid>
      :action         <:purge | :anonymize | :archive-to-cold-storage>
      :deadline       <Date>
      :blocked-by-hold? <bool>}

   Items where `:blocked-by-hold?` is true are still returned — for
   the 'this would expire today but is on hold' visibility — but
   `sweep-and-apply!` will skip them. The held set is computed once
   for the whole batch via `legal-hold/entities-held?`.

   NOTE — silent skip: an entity whose `:triggered-by` value is not
   a `Date` (e.g. a bulk import left the anchor attribute null) has
   no computable deadline and is silently omitted from the result.
   Skipping (not expiring) is the safe direction; a `:skipped`
   diagnostic in the return shape is a documented follow-up
   (research note 32 P2-2).

   `:limit` caps the candidate set (default nil = unbounded)."
  [db policy-eid {:keys [as-of limit]}]
  (let [as-of (or as-of (Date.))
        {:kontor.retention-policy/keys [expiry-action]}
        (d/pull db [:kontor.retention-policy/expiry-action] policy-eid)
        cands (candidate-eids db policy-eid limit)
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

   Opts:
     `:entity-type`   — required.
     `:jurisdiction`  — optional :country eid.
     `:as-of`         — instant; default now.
     `:category`      — ADR-075 subject-matter keyword forwarded to
                        `policy-for`. When supplied the resolver
                        prefers a category-specific :retention-policy
                        over a generic one (per-category floors:
                        payroll-PII retention vs financial-records).
                        Callers that haven't classified their data
                        omit it and get the legacy behavior.
     `:limit`         — cap the candidate set (default unbounded).

   Returns `[]` when no active policy covers the combination
   (retention disabled for it)."
  [db {:keys [entity-type jurisdiction as-of category limit]}]
  (when-not entity-type (throw (ex-info ":entity-type required" {})))
  (if-let [policy-eid (policy-for db entity-type
                                  {:jurisdiction jurisdiction
                                   :as-of as-of
                                   :category category})]
    (due-for-expiry db policy-eid {:as-of as-of :limit limit})
    []))

(defn apply-expiry!
  "Execute one expiry work-item. The destructive tx-data is checked
   through `kontor.validation/validate-and-apply` so the ADR-049
   hold-middleware (and sealing, period, …) fire — the sweeper
   structurally cannot expire data under an active legal hold.

   The check runs *directly* (not via `[:db.fn/call …]`) so the
   raised exception is the kernel's own `ex-info` with the
   `:kontor.legal-hold/purge-blocked` `:type` reachable via `(ex-data e)` —
   not double-wrapped by the transactor (research note 32 P1-1). The
   plain tx-data is then transacted. The validate→transact split is
   single-threaded-sweeper-safe; it mirrors `validation/
   transact-with-validation`'s invariants-outside / structural-inside
   shape.

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
          (let [{:kontor.retention-policy/keys [anonymize-fields]}
                (d/pull db [:kontor.retention-policy/anonymize-fields] policy-eid)]
            (when (empty? anonymize-fields)
              (throw (ex-info ":anonymize action requires :kontor.retention-policy/anonymize-fields"
                              {:policy-eid policy-eid})))
            (mapv (fn [attr] [:db.purge/attribute entity-eid attr])
                  anonymize-fields))

          :archive-to-cold-storage
          (throw (ex-info ":archive-to-cold-storage is not implemented in v1 (ADR-050)"
                          {:entity-eid entity-eid :policy-eid policy-eid}))

          (throw (ex-info "Unknown :expiry-action"
                          {:action action :entity-eid entity-eid})))]
    ;; Run the structural validators directly — this throws the
    ;; kernel's own ex-info (e.g. :kontor.legal-hold/purge-blocked) with the
    ;; :type on (ex-data e), not buried in (.getCause e).
    (validation/validate-and-apply db tx-data)
    (d/transact conn tx-data)))

(defn sweep-and-apply!
  "Sweep `entity-type` and apply every non-held expiry work-item.
   Held items are skipped (they stay due — the next sweep after the
   hold releases will pick them up). Returns
   `{:applied [<item> …] :blocked [<item> …]}`.

   Opts: `:entity-type` (required), `:jurisdiction`, `:as-of`,
   `:category` (ADR-075 subject-matter keyword forwarded to `sweep!`),
   `:dry-run?` (when true, applies nothing — returns the same shape
   with `:applied` empty and every eligible item under `:blocked`-
   shaped `:would-apply`)."
  [conn {:keys [entity-type jurisdiction as-of category dry-run?]}]
  (let [items (sweep! (d/db conn)
                      {:entity-type entity-type
                       :jurisdiction jurisdiction
                       :as-of as-of
                       :category category})
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

(defn define-policy-tx-data
  "Pure tx-data builder for `define-policy!` (ADR-068)."
  [db {:keys [code applies-to duration-years triggered-by expiry-action
              effective-from legal-basis jurisdiction effective-until
              anonymize-fields category changed-by-uid drafted-at tempid]
       :or {tempid "policy-1"}}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not (seq applies-to) (throw (ex-info ":applies-to required" {})))
  (when-not duration-years (throw (ex-info ":duration-years required" {})))
  (when-not triggered-by   (throw (ex-info ":triggered-by required" {})))
  (when-not expiry-action  (throw (ex-info ":expiry-action required" {})))
  (when-not effective-from (throw (ex-info ":effective-from required" {})))
  (when-not legal-basis    (throw (ex-info ":legal-basis required" {})))
  (when (and (= expiry-action :anonymize) (empty? anonymize-fields))
    (throw (ex-info ":anonymize expiry-action requires :anonymize-fields" {})))
  (let [row (cond-> {:db/id tempid
                     :kontor.retention-policy/code code
                     :kontor.retention-policy/applies-to (vec applies-to)
                     :kontor.retention-policy/duration-years duration-years
                     :kontor.retention-policy/triggered-by triggered-by
                     :kontor.retention-policy/expiry-action expiry-action
                     :kontor.retention-policy/effective-from effective-from
                     :kontor.retention-policy/legal-basis legal-basis
                     :kontor.retention-policy/state :draft}
              jurisdiction         (assoc :kontor.retention-policy/jurisdiction jurisdiction)
              effective-until      (assoc :kontor.retention-policy/effective-until effective-until)
              category             (assoc :kontor.retention-policy/category category)
              (seq anonymize-fields) (assoc :kontor.retention-policy/anonymize-fields
                                            (vec anonymize-fields)))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity tempid
                            :entity-type :retention-policy
                            :facet :kontor.retention-policy/state
                            :from :nil
                            :to :draft
                            :changed-at (or drafted-at (Date.))
                            :reason :policy-drafted}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (into [row] status-tx)))

(defn define-policy!
  "Create a retention policy in `:draft` state. Drafting is free
   (no approval-policy gates); `activate-policy!` is what ADR-038
   governs. Routes through the gate (ADR-068).

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
     :category         ADR-075 subject-matter keyword (:payroll |
                       :hr-personnel | :financial | …). When set,
                       this policy ONLY applies to entities whose
                       sweep-time :category opt matches; nil = the
                       policy applies regardless of category (legacy).
     :anonymize-fields coll of attribute keywords (for :anonymize)
     :changed-by-uid   ref to :kontor.audit/create-uid
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)

   The pure tx-data builder is `define-policy-tx-data` (ADR-068)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (define-policy-tx-data
                         (d/db conn) (assoc opts :drafted-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

(defn by-code
  "Resolve a policy's eid by its :kontor.retention-policy/code. When several
   rows share a code (effective-dated supersession), returns the one
   with the latest :effective-from."
  [db code]
  (->> (d/q '[:find ?e ?from
              :in $ ?c
              :where
              [?e :kontor.retention-policy/code ?c]
              [?e :kontor.retention-policy/effective-from ?from]]
            db code)
       (sort-by (fn [[_ ^Date from]] (.getTime from)))
       last
       first))

(defn activate-policy-tx-data
  "Pure tx-data builder for `activate-policy!` (ADR-068)."
  [db {:keys [policy-eid supporting-doc reason-note changed-by-uid
              reason changed-at]}]
  (when-not policy-eid     (throw (ex-info ":policy-eid required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required (ADR-038)" {})))
  (let [status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity policy-eid
                            :entity-type :retention-policy
                            :facet :kontor.retention-policy/state
                            :to :active
                            :changed-at (or changed-at (Date.))
                            :reason (or reason :policy-activated)
                            :reason-note reason-note
                            :supporting-doc supporting-doc}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)))
        ;; Queryable denorm — canonical audit lives on status-history.
        update {:db/id policy-eid
                :kontor.retention-policy/supporting-doc supporting-doc}]
    (into [update] status-tx)))

(defn activate-policy!
  "Transition a policy `:draft → :active`. ADR-038 enforces
   `:requires-supporting-doc` + `:requires-non-empty-reason-note` —
   the auditor needs to know why a retention rule came into force.
   Routes through the gate (ADR-068).

   Required opts: :policy-eid, :supporting-doc, :reason-note,
                  :changed-by-uid
   Optional: :reason (default :policy-activated), :vt-from, :vt-to.

   The pure tx-data builder is `activate-policy-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (activate-policy-tx-data
                        (d/db conn) (assoc opts :changed-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

(defn supersede-policy-tx-data
  "Pure tx-data builder for `supersede-policy!` (ADR-068)."
  [db {:keys [policy-eid changed-by-uid reason reason-note
              supporting-doc changed-at]}]
  (when-not policy-eid     (throw (ex-info ":policy-eid required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required (ADR-038)" {})))
  (sm/record-status-change-tx-data
   db
   {:entity policy-eid
    :entity-type :retention-policy
    :facet :kontor.retention-policy/state
    :to :superseded
    :changed-at (or changed-at (Date.))
    :changed-by-uid changed-by-uid
    :reason (or reason :policy-superseded)
    :reason-note reason-note
    :supporting-doc supporting-doc}))

(defn supersede-policy!
  "Transition a policy `:active → :superseded`. Terminal. Superseding
   is a consequential, data-affecting change — retiring `P-OLD-5yr`
   in favour of `P-NEW-3yr` *shortens* retention, making entities
   purge-eligible sooner. ADR-038 governs it. Routes through the
   gate (ADR-068).

   Required opts: :policy-eid, :changed-by-uid, :supporting-doc,
                  :reason-note
   Optional: :reason (default :policy-superseded), :vt-from, :vt-to.

   The pure tx-data builder is `supersede-policy-tx-data`."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (supersede-policy-tx-data
                        (d/db conn) (assoc opts :changed-at now))
            (or vt-from now)
            (or vt-to kbt/forever)))))

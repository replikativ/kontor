(ns kontor.legal-hold
  "Legal hold — write-time invariant blocking `:db/purge` of held
   entities (ADR-049).

   Stage M's headline primitive. A `:legal-hold` represents a matter-
   level preservation order (Acme v. Doe, regulatory subpoena, etc.).
   While the hold's state is `:placed` or `:pending-review`, no
   `:db/purge` against any entity in the hold's scope can succeed —
   `kontor.validation/validate-and-apply` consults this namespace
   BEFORE `kontor.sealing/assert-no-silent-retracts!` so the more-
   specific 'purge blocked by hold X' error wins on overlap.

   Two scope shapes:
   - `:legal-hold/scope-eids` — explicit `:db.cardinality/many` ref
     set. Fast path; sweepers refresh from `:scope-query` results.
   - `:legal-hold/scope-query` — EDN-encoded datalog string evaluated
     against the speculative `txdb` at purge time. Catches new
     entities matching the matter between sweeper runs.

   Both checks run; either firing blocks the purge.

   ## Composition

   - **kontor.status-machine (ADR-034):** every state change writes
     a `:status-history` row carrying `:reason`/`:reason-note`/
     `:supporting-doc`/`:changed-by-uid` (the standard transactor
     opts shape; see doc/conventions.md).
   - **kontor.audit-doc + :approval-policy (ADR-038):** both
     placement and release require a `:supporting-doc` (the
     preservation order PDF, the release order) and a non-empty
     `:reason-note`; release additionally requires `:no-self-
     approval`. Policies are installed by `install-seeds!`.
   - **kontor.bitemporal (ADR-048):** the hold itself is bitemporal.
     `(kbt/value-at db hold-eid :legal-hold/scope-query as-of)`
     answers 'what was the hold's scope on the subpoena date'.

   ## Performance

   Hot path (writes that aren't purges): unchanged. The middleware
   only fires on `:db/purge` operations. For each purge of N targets,
   the middleware runs O(active-holds) datalog queries plus
   O(N × active-holds) eid-set membership checks. Production purges
   are rare (annual GDPR-erasure cycles), so this cost is negligible
   in practice."
  (:require [clojure.edn :as edn]
            [clojure.set]
            [clojure.string]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

;; legal-hold is a validator INSIDE `kontor.validation`'s gate
;; (`assert-no-hold-violating-destructive-writes!`), so we can't
;; require `kontor.validation` statically (cycle). The `!` wrappers
;; resolve the gate lazily — same call shape, no static dep.
(defn- transact-with-validation
  [conn tx-data]
  ((requiring-resolve 'kontor.validation/transact-with-validation)
   conn tx-data))

;; ============================================================================
;; Status-transition + approval-policy seeds
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :legal-hold/state facet."
  [{:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :nil
    :status-transition/to :placed
    :status-transition/active true
    :status-transition/name "Place Legal Hold"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :placed
    :status-transition/to :pending-review
    :status-transition/active true
    :status-transition/name "Pending Review"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :pending-review
    :status-transition/to :placed
    :status-transition/active true
    :status-transition/name "Reaffirm"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :pending-review
    :status-transition/to :released
    :status-transition/active true
    :status-transition/name "Release After Review"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :placed
    :status-transition/to :released
    :status-transition/active true
    :status-transition/name "Release Hold"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :placed
    :status-transition/to :expired
    :status-transition/active true
    :status-transition/name "Auto-Expire Hold"}
   {:status-transition/entity-type :legal-hold
    :status-transition/facet :legal-hold/state
    :status-transition/from :expired
    :status-transition/to :released
    :status-transition/active true
    :status-transition/name "Confirm Expiry"}])

(def approval-policy-seeds
  "ADR-038 :approval-policy rows for legal-hold transitions."
  [;; nil → :placed — placement requires supporting doc + reason note
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :legal-hold.state/nil
    :approval-policy/transition-to   :placed
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :legal-hold.state/nil
    :approval-policy/transition-to   :placed
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}

   ;; :placed → :released — release requires SoD + supporting doc
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :placed
    :approval-policy/transition-to   :released
    :approval-policy/rule            :no-self-approval
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :placed
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :placed
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}

   ;; :pending-review → :released — same triple (P1-1 review fix).
   ;; A reviewer who flagged "do we still need this?" must NOT be
   ;; able to bless their own release; releasing a hold is the most
   ;; consequential action in the kernel (the next purge fires
   ;; unblocked).
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :pending-review
    :approval-policy/transition-to   :released
    :approval-policy/rule            :no-self-approval
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :pending-review
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :pending-review
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}

   ;; :expired → :released — admin acknowledges the sweeper-fired
   ;; auto-expiry. SoD is not required (the sweeper, not a person,
   ;; triggered the expiry) but the release order + a reason note
   ;; ARE required, since this transition also unblocks purges.
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :expired
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-supporting-doc
    :approval-policy/active          true}
   {:approval-policy/entity-type     :legal-hold
    :approval-policy/facet           :legal-hold/state
    :approval-policy/transition-from :expired
    :approval-policy/transition-to   :released
    :approval-policy/rule            :requires-non-empty-reason-note
    :approval-policy/active          true}])

(defn install-seeds!
  "Idempotently transact the legal-hold status-transition + approval-
   policy seeds. Called from `kontor.core/install-schema!`.

   The kernel-wide composite-tuple-with-nil-in-tuple
   non-idempotency caveat (see modules/collections/.../schema_test.clj
   :253-258) means we must NOT re-transact seeds on a connection that
   already has them. Guard with a presence check on the first row."
  [conn]
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where
                         [?e :status-transition/entity-type :legal-hold]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds))))))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:private active-states
  "States in which a hold blocks destructive writes."
  #{:placed :pending-review})

(defn- parse-scope-query
  "Parse the EDN scope-query string into a datalog query. Returns nil
   for a blank/nil string. Throws ex-info :type :legal-hold/invalid-
   scope-query on malformed EDN or a non-[:find …] shape — used at
   placement time so the operator hears about a bad query immediately
   rather than at the first purge (P2-1)."
  [s]
  (when (and (string? s) (not (clojure.string/blank? s)))
    (let [q (try
              (edn/read-string s)
              (catch Exception e
                (throw (ex-info "Invalid EDN in :scope-query"
                                {:type :legal-hold/invalid-scope-query
                                 :scope-query s
                                 :cause (.getMessage e)}))))]
      (when-not (and (vector? q) (= :find (first q)))
        (throw (ex-info ":scope-query must be a [:find ?eid :where …] vector"
                        {:type :legal-hold/invalid-scope-query
                         :scope-query s
                         :parsed q})))
      q)))

(defn expand-scope-query
  "Run the hold's :scope-query against `db` and return the matching
   eids as a set. The query must `:find` exactly one variable (the
   eid). Honors :scope-query-as-of when set (bitemporal anchor).
   Returns #{} when the hold has no :scope-query."
  [db hold-eid]
  (let [pulled (d/pull db
                       [:legal-hold/scope-query
                        :legal-hold/scope-query-as-of]
                       hold-eid)
        q (parse-scope-query (:legal-hold/scope-query pulled))]
    (if-not q
      #{}
      (let [as-of (:legal-hold/scope-query-as-of pulled)
            db' (if as-of (d/as-of db as-of) db)
            results (d/q q db')]
        ;; Result is a set of tuples; flatten to a set of eids
        ;; assuming a one-binding :find clause (the documented shape).
        (into #{} (map first) results)))))

(defn active-holds
  "All holds currently in an active state (i.e. blocking destructive
   writes). Idiomatic `:in $ [?s ...]` set-membership binding."
  [db]
  (set
   (d/q '[:find [?h ...]
          :in $ [?active-state ...]
          :where
          [?h :legal-hold/state ?active-state]]
        db active-states)))

(defn- scoped-eids-by-hold
  "Compute, for each active hold, the union of its :scope-eids and the
   expansion of its :scope-query — as a map {hold-eid #{eid …}}. The
   scope-query is evaluated ONCE per hold (P1-2: was O(targets×holds);
   now O(holds))."
  [db holds]
  (into {}
        (map (fn [hold-eid]
               (let [explicit (set
                               (d/q '[:find [?e ...]
                                      :in $ ?h
                                      :where [?h :legal-hold/scope-eids ?e]]
                                    db hold-eid))
                     queried (expand-scope-query db hold-eid)]
                 [hold-eid (clojure.set/union explicit queried)])))
        holds))

(defn entities-held?
  "Batched membership check. Returns the subset of `eids` that fall
   under any active hold's scope. The sweeper-friendly form — computes
   the active-hold scope sets once, then intersects (P1-2 +
   ADR-050 forward-compat)."
  [db eids]
  (let [holds (active-holds db)]
    (if (empty? holds)
      #{}
      (let [scope (scoped-eids-by-hold db holds)
            held  (reduce clojure.set/union #{} (vals scope))]
        (clojure.set/intersection (set eids) held)))))

(defn holds-covering
  "The inverse of `entities-held?` — return the vec of active-hold
   eids whose scope (explicit `:scope-eids` ∪ `:scope-query`
   expansion) includes `eid`. Lets callers that need *which holds*
   (not just *whether held*) compose with legal-hold rather than
   re-implementing scope-checking — e.g. `kontor.dsar/collect`'s
   `:legal-holds`."
  [db eid]
  (let [holds (active-holds db)]
    (if (empty? holds)
      []
      (let [scope (scoped-eids-by-hold db holds)]
        (filterv (fn [hold-eid] (contains? (get scope hold-eid) eid))
                 holds)))))

(defn entity-held?
  "True iff `eid` is in any active hold's scope (eids OR query)."
  [db eid]
  (contains? (entities-held? db [eid]) eid))

(def ^:private destructive-ops
  "datahike tx-op keywords that destroy data on an *existing* entity.
   Whole-entity forms (eid in slot 1) — the actual datahike purge +
   retract surface (see datahike.db.transaction `transact-tx-data`):"
  #{:db/purge :db.purge/entity :db/retractEntity :db.fn/retractEntity})

(def ^:private destructive-attr-ops
  "datahike tx-op keywords that destroy data on a specific attribute
   of an existing entity (eid in slot 1, attr in slot 2):"
  #{:db.purge/attribute :db/retract})

(defn- destructive-targets
  "Walk `tx-data`; return a seq of `{:tx :eid :form :attr}` for every
   form that destroys data on an existing entity — datahike's full
   purge + retract surface, not just `[:db/purge eid]` (P0-1).

   Whole-entity forms (`:db/purge`, `:db.purge/entity`,
   `:db/retractEntity`, `:db.fn/retractEntity`) carry no `:attr`.
   Attribute-level forms (`:db.purge/attribute`, `:db/retract`) carry
   the attr in slot 2.

   Entity-map nil-retracts (`{:db/id e :foo nil}`) are intentionally
   NOT covered here — they retract a single datom, the sealing
   middleware catches the posted-entity subset, and a held-but-non-
   posted single-datom retract is a far narrower exposure than a
   whole-entity purge. A follow-up can extend coverage if a real
   case surfaces."
  [tx-data]
  (->> tx-data
       (keep (fn [tx]
               (cond
                 (and (vector? tx) (destructive-ops (first tx)))
                 {:tx tx :eid (second tx) :form (first tx)}

                 (and (vector? tx) (destructive-attr-ops (first tx)))
                 {:tx tx :eid (second tx) :form (first tx)
                  :attr (nth tx 2 nil)})))))

(defn find-hold-violating-destructive-writes
  "Walk `tx-data` and return a vec of
   `{:tx :eid :form :hold-eid :hold-code :via}` rows for any
   destructive write (purge / retract / retractEntity /
   retractAttribute — see `destructive-targets`) that would discard
   data in an active hold's scope. Empty in the happy case.

   Evaluates both `:scope-eids` (fast path) and `:scope-query`
   (against the speculative `txdb`). The scope-query is expanded
   ONCE per hold (P1-2)."
  [txdb tx-data]
  (let [targets (destructive-targets tx-data)]
    (if (empty? targets)
      []
      (let [holds (active-holds txdb)]
        (if (empty? holds)
          []
          (let [scope (scoped-eids-by-hold txdb holds)]
            (->> targets
                 (mapcat
                  (fn [{:keys [eid] :as t}]
                    (keep (fn [[hold-eid held-eids]]
                            (when (contains? held-eids eid)
                              (assoc t
                                     :hold-eid hold-eid
                                     :hold-code (:legal-hold/code
                                                 (d/pull txdb
                                                         [:legal-hold/code]
                                                         hold-eid)))))
                          scope)))
                 vec)))))))

(defn assert-no-hold-violating-destructive-writes!
  "Mirror of `kontor.sealing/assert-no-silent-retracts!`. Throws
   ex-info `:type :legal-hold/purge-blocked` if any destructive write
   in `tx-data` (purge / retract / retractEntity / retractAttribute)
   would discard data under an active hold.

   Short-circuits when `tx-data` has no destructive forms — the
   common case pays only a `destructive-targets` walk, no datalog.

   Called from `kontor.validation/validate-and-apply` BEFORE the
   sealing middleware, so a destructive-write-of-held-entity surfaces
   as a more-specific 'blocked by hold X' error rather than the
   generic 'silent retract of posted entry' error."
  [txdb tx-data]
  (let [violations (find-hold-violating-destructive-writes txdb tx-data)]
    (when (seq violations)
      (throw (ex-info "Refused: destructive write blocked by active legal hold"
                      {:type        :legal-hold/purge-blocked
                       :violations  violations
                       :remediation
                       "Each violating eid is under an active legal
                        hold. Release the hold first (via
                        kontor.legal-hold/release!) — if the hold
                        is genuinely no longer needed — or move the
                        target out of scope. Hold scopes are
                        bitemporal: kbt/value-at on
                        :legal-hold/scope-query answers 'what was in
                        scope at any past valid-time'."}))))
  nil)

;; ============================================================================
;; Transactors
;; ============================================================================

(defn place-tx-data
  "Pure tx-data builder for `place!` — entity-map construction
   without the `d/transact` / `with-vt` wrapper (ADR-068). Use as a
   `kontor.process` step. Takes the same opts as `place!` minus the
   `:vt-from` / `:vt-to` valid-time bounds (owned by the caller),
   plus `:tempid` (default `\"hold-1\"`) and `:placed-at` (default
   now)."
  [db {:keys [code matter-name issued-by-uid issued-at supporting-doc
              scope-eids scope-query scope-query-as-of scope-preview
              expires-at note reason-note tempid placed-at]
       :or   {tempid "hold-1"}}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not matter-name    (throw (ex-info ":matter-name required" {})))
  (when-not issued-by-uid  (throw (ex-info ":issued-by-uid required" {})))
  (when-not issued-at      (throw (ex-info ":issued-at required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (and (empty? scope-eids) (clojure.string/blank? (or scope-query "")))
    (throw (ex-info ":scope-eids or :scope-query required" {})))
  ;; P2-1: validate the scope-query at placement time — a malformed
  ;; query throws here rather than at the first purge.
  (parse-scope-query scope-query)
  (let [placed-at (or placed-at (java.util.Date.))
        row (cond-> {:db/id tempid
                     :legal-hold/code code
                     :legal-hold/matter-name matter-name
                     :legal-hold/issued-by-uid issued-by-uid
                     :legal-hold/issued-at issued-at
                     :legal-hold/supporting-doc supporting-doc
                     :legal-hold/state :placed
                     ;; ADR-038 :no-self-approval compares
                     ;; :changed-by-uid against :create/uid on the
                     ;; entity. Stamp it so the release-side SoD check
                     ;; can fire.
                     :create/uid issued-by-uid}
              (seq scope-eids)        (assoc :legal-hold/scope-eids (vec scope-eids))
              scope-query             (assoc :legal-hold/scope-query scope-query)
              scope-query-as-of       (assoc :legal-hold/scope-query-as-of scope-query-as-of)
              scope-preview           (assoc :legal-hold/scope-preview scope-preview)
              expires-at              (assoc :legal-hold/expires-at expires-at)
              note                    (assoc :legal-hold/note note))
        ;; P1-3: no :legal-hold/placed-at denorm — the placement
        ;; instant is the :tx/valid-from of the wrapping tx and the
        ;; :status-history/changed-at of the nil → :placed row.
        ;; Resolve via kbt/value-at if needed.
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity tempid
                            :entity-type :legal-hold
                            :facet :legal-hold/state
                            :from :nil
                            :to :placed
                            :changed-at placed-at
                            :changed-by-uid issued-by-uid
                            :reason :hold-placed
                            :supporting-doc supporting-doc}
                     reason-note (assoc :reason-note reason-note)))]
    (into [row] status-tx)))

(defn place!
  "Place a legal hold. Atomic: writes the :legal-hold entity, the
   nil → :placed status-history row (with approval-policy checks),
   and stamps :tx/valid-from per ADR-048. Routes through the
   `transact-with-validation` gate (ADR-068).

   Required opts:
     :code             string (unique identity)
     :matter-name      string (human-readable matter description)
     :issued-by-uid    ref to :create/uid (the counsel)
     :issued-at        instant (when the preservation order issued)
     :supporting-doc   ref to :audit-doc (preservation order PDF)
                       — ADR-038 :requires-supporting-doc enforces

   Scope (at least one required):
     :scope-eids       coll of eids (explicit set)
     :scope-query      EDN string (datalog query)
     :scope-query-as-of  instant (optional vt-anchor for the query)
     :scope-preview    ref to :audit-doc (optional counsel-signed
                       eid-set snapshot)

   Optional:
     :expires-at       instant (sweep-time-based! auto-release)
     :note             string
     :reason-note      free-text (required by ADR-038 policy)
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)

   The pure tx-data builder is `place-tx-data` (ADR-068)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [placed-at (java.util.Date.)
        opts (assoc opts :placed-at placed-at)]
    (transact-with-validation
     conn (kbt/with-vt (place-tx-data (d/db conn) opts)
                       (or vt-from placed-at)
                       (or vt-to kbt/forever)))))

(defn release-tx-data
  "Pure tx-data builder for `release!` (ADR-068). Use as a
   `kontor.process` step; `release!` is the standalone wrapper."
  [db {:keys [hold-eid released-by-uid supporting-doc reason
              reason-note released-at]}]
  (when-not hold-eid        (throw (ex-info ":hold-eid required" {})))
  (when-not released-by-uid (throw (ex-info ":released-by-uid required" {})))
  (when-not supporting-doc  (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required (ADR-038)" {})))
  (sm/record-status-change-tx-data
   db
   {:entity hold-eid
    :entity-type :legal-hold
    :facet :legal-hold/state
    :to :released
    :changed-at (or released-at (java.util.Date.))
    :changed-by-uid released-by-uid
    :reason (or reason :hold-released)
    :reason-note reason-note
    :supporting-doc supporting-doc}))

(defn release!
  "Release a hold. Status :placed → :released; ADR-038 enforces
   :no-self-approval, :requires-supporting-doc, :requires-non-empty-
   reason-note. Routes through the gate (ADR-068).

   Required opts:
     :hold-eid         the :legal-hold eid (or use :code lookup-ref)
     :released-by-uid  ref to :create/uid (must differ from
                       :issued-by-uid per :no-self-approval)
     :supporting-doc   ref to :audit-doc (the release order)
     :reason-note      free-text justification
     :reason           keyword (default :hold-released)

   Optional:
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)

   The pure tx-data builder is `release-tx-data` (ADR-068)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [now (java.util.Date.)
        opts (assoc opts :released-at now)]
    (transact-with-validation
     conn (kbt/with-vt (release-tx-data (d/db conn) opts)
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn by-code
  "Resolve a hold's eid by its :legal-hold/code."
  [db code]
  (d/q '[:find ?e .
         :in $ ?c
         :where [?e :legal-hold/code ?c]]
       db code))

(defn refresh-scope-eids!
  "Sweeper helper: re-evaluate the hold's :scope-query against the
   current db and ADD any newly-matching eids to :scope-eids. Never
   retracts existing eids (the hold can only EXPAND its eid cache
   without counsel re-attestation — monotonic).

   Returns `{:hold-eid :added-eids :added-count}`. The `:added-eids`
   vector lets the *consumer* write its own audit trail of scope
   drift — e.g. an `:audit-doc/type :legal-hold-scope-expansion` row
   plus a counsel notification (P2-3: the kernel ships the predicate;
   the consumer owns the audit + notification cadence, consistent
   with ADR-010 and the consumer-schedules-the-sweeper split)."
  [conn hold-eid]
  (let [db (d/db conn)
        new-eids (expand-scope-query db hold-eid)
        existing (set (map :db/id
                           (:legal-hold/scope-eids
                            (d/pull db [{:legal-hold/scope-eids [:db/id]}]
                                    hold-eid))))
        to-add (vec (clojure.set/difference new-eids existing))]
    (when (seq to-add)
      (transact-with-validation
       conn [{:db/id hold-eid
              :legal-hold/scope-eids to-add}]))
    {:hold-eid hold-eid
     :added-eids to-add
     :added-count (count to-add)}))

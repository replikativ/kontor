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
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

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
  "States in which a hold blocks purges."
  #{:placed :pending-review})

(defn- read-scope-query
  "Parse the EDN string into a datalog query. Returns nil if the
   string is empty/blank/nil."
  [s]
  (when (and (string? s) (not (clojure.string/blank? s)))
    (edn/read-string s)))

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
        q (read-scope-query (:legal-hold/scope-query pulled))]
    (if-not q
      #{}
      (let [as-of (:legal-hold/scope-query-as-of pulled)
            db' (if as-of (d/as-of db as-of) db)
            results (d/q q db')]
        ;; Result is a set of tuples; flatten to a set of eids
        ;; assuming a one-binding :find clause (the documented shape).
        (into #{} (map first) results)))))

(defn active-holds
  "All holds currently in an active state (i.e. blocking purges)."
  [db]
  (->> (d/q '[:find [?h ...]
              :where
              [?h :legal-hold/state ?s]
              [(get #{:placed :pending-review} ?s) ?active]
              [(some? ?active)]]
            db)
       set))

(defn entity-held?
  "True iff `eid` is in any active hold's scope (eids OR query)."
  [db eid]
  (boolean
   (some (fn [hold-eid]
           (or
            ;; Fast path: eid in :scope-eids
            (d/q '[:find ?e .
                   :in $ ?h ?e
                   :where [?h :legal-hold/scope-eids ?e]]
                 db hold-eid eid)
            ;; Expressive path: eid matches :scope-query
            (contains? (expand-scope-query db hold-eid) eid)))
         (active-holds db))))

(defn- purge-targets
  "Walk tx-data; return seq of {:tx tx :eid eid} for every :db/purge
   form. Supports [:db/purge eid] vector form."
  [tx-data]
  (->> tx-data
       (filter #(and (vector? %) (= :db/purge (first %))))
       (map (fn [tx] {:tx tx :eid (second tx)}))))

(defn find-hold-violating-purges
  "Walk `tx-data` and return a vec of
   `{:tx :eid :hold-eid :hold-code}` rows for any `:db/purge` that
   would discard data in an active hold's scope. Empty in the happy
   case.

   Evaluates both `:scope-eids` (fast path) and `:scope-query`
   (against the speculative `txdb`)."
  [txdb tx-data]
  (let [purges (purge-targets tx-data)
        holds (active-holds txdb)]
    (->> purges
         (mapcat (fn [{:keys [eid] :as p}]
                   (keep (fn [hold-eid]
                           (let [in-eids? (d/q '[:find ?e .
                                                 :in $ ?h ?e
                                                 :where [?h :legal-hold/scope-eids ?e]]
                                               txdb hold-eid eid)
                                 in-query? (and (not in-eids?)
                                                (contains?
                                                 (expand-scope-query txdb hold-eid)
                                                 eid))]
                             (when (or in-eids? in-query?)
                               (assoc p
                                      :hold-eid hold-eid
                                      :hold-code (:legal-hold/code
                                                  (d/pull txdb
                                                          [:legal-hold/code]
                                                          hold-eid))
                                      :via (if in-eids? :scope-eids :scope-query)))))
                         holds)))
         vec)))

(defn assert-no-hold-violating-purges!
  "Mirror of `kontor.sealing/assert-no-silent-retracts!`. Throws
   ex-info `:type :legal-hold/purge-blocked` if any `:db/purge` in
   `tx-data` would discard data under an active hold.

   Called from `kontor.validation/validate-and-apply` BEFORE the
   sealing middleware, so a purge-of-posted-held-entity surfaces as
   a more-specific 'purge blocked by hold X' error rather than the
   generic 'silent retract of posted entry' error."
  [txdb tx-data]
  (let [violations (find-hold-violating-purges txdb tx-data)]
    (when (seq violations)
      (throw (ex-info "Refused: purge blocked by active legal hold"
                      {:type        :legal-hold/purge-blocked
                       :violations  violations
                       :remediation
                       "Each violating eid is under an active legal
                        hold. Release the hold first (via
                        kontor.legal-hold/release!) — if the hold
                        is genuinely no longer needed — or move the
                        purge target out of scope. Hold scopes are
                        bitemporal: kbt/value-at on
                        :legal-hold/scope-query answers 'what was in
                        scope at any past valid-time'."}))))
  nil)

;; ============================================================================
;; Transactors
;; ============================================================================

(defn place!
  "Place a legal hold. Atomic: writes the :legal-hold entity, the
   nil → :placed status-history row (with approval-policy checks),
   and stamps :tx/valid-from per ADR-048.

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
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)"
  [conn {:keys [code matter-name issued-by-uid issued-at supporting-doc
                scope-eids scope-query scope-query-as-of scope-preview
                expires-at note reason-note vt-from vt-to]}]
  (when-not code           (throw (ex-info ":code required" {})))
  (when-not matter-name    (throw (ex-info ":matter-name required" {})))
  (when-not issued-by-uid  (throw (ex-info ":issued-by-uid required" {})))
  (when-not issued-at      (throw (ex-info ":issued-at required" {})))
  (when-not supporting-doc (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (and (empty? scope-eids) (clojure.string/blank? (or scope-query "")))
    (throw (ex-info ":scope-eids or :scope-query required" {})))
  (let [db (d/db conn)
        placed-at (java.util.Date.)
        hold-tempid "hold-1"
        row (cond-> {:db/id hold-tempid
                     :legal-hold/code code
                     :legal-hold/matter-name matter-name
                     :legal-hold/issued-by-uid issued-by-uid
                     :legal-hold/issued-at issued-at
                     :legal-hold/placed-at placed-at
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
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity hold-tempid
                            :entity-type :legal-hold
                            :facet :legal-hold/state
                            :from :nil
                            :to :placed
                            :changed-at placed-at
                            :changed-by-uid issued-by-uid
                            :reason :hold-placed
                            :supporting-doc supporting-doc}
                     reason-note (assoc :reason-note reason-note)))]
    (d/transact conn (kbt/with-vt (into [row] status-tx)
                                  (or vt-from placed-at)
                                  (or vt-to kbt/forever)))))

(defn release!
  "Release a hold. Status :placed → :released; ADR-038 enforces
   :no-self-approval, :requires-supporting-doc, :requires-non-empty-
   reason-note.

   Required opts:
     :hold-eid         the :legal-hold eid (or use :code lookup-ref)
     :released-by-uid  ref to :create/uid (must differ from
                       :issued-by-uid per :no-self-approval)
     :supporting-doc   ref to :audit-doc (the release order)
     :reason-note      free-text justification
     :reason           keyword (default :hold-released)

   Optional:
     :vt-from / :vt-to valid-time bounds (default :vt-from = now)"
  [conn {:keys [hold-eid released-by-uid supporting-doc reason
                reason-note vt-from vt-to]}]
  (when-not hold-eid        (throw (ex-info ":hold-eid required" {})))
  (when-not released-by-uid (throw (ex-info ":released-by-uid required" {})))
  (when-not supporting-doc  (throw (ex-info ":supporting-doc required (ADR-038)" {})))
  (when (clojure.string/blank? (or reason-note ""))
    (throw (ex-info ":reason-note required (ADR-038)" {})))
  (let [db (d/db conn)
        now (java.util.Date.)
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity hold-eid
                    :entity-type :legal-hold
                    :facet :legal-hold/state
                    :to :released
                    :changed-at now
                    :changed-by-uid released-by-uid
                    :reason (or reason :hold-released)
                    :reason-note reason-note
                    :supporting-doc supporting-doc})]
    (d/transact conn (kbt/with-vt status-tx
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
   without counsel re-attestation).

   Returns the count of newly-added eids."
  [conn hold-eid]
  (let [db (d/db conn)
        new-eids (expand-scope-query db hold-eid)
        existing (set (map :db/id
                           (:legal-hold/scope-eids
                            (d/pull db [{:legal-hold/scope-eids [:db/id]}]
                                    hold-eid))))
        to-add (vec (clojure.set/difference new-eids existing))]
    (when (seq to-add)
      (d/transact conn
                  [{:db/id hold-eid
                    :legal-hold/scope-eids to-add}]))
    (count to-add)))

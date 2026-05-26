(ns kontor.hr.consent
  "ADR-094 — per-(subject, scope) consent records.

   A `:consent` row records that a particular `:person` has consented
   (or withdrawn consent) for processing data tagged with a particular
   `:kontor.audit-doc/category`, under a particular legal basis, supported by
   a particular DPIA / works-agreement / consent-form.

   The substrate captures consent + withdrawal as bitemporal facts at
   time T; `(d/valid-at db T)` answers \"what was the legal basis at
   T?\".

   ## Discipline

   - The kernel never enforces consent. The kontor consent slot is
     descriptive: a consumer policy layer (kontor-people-record, MCP
     agent tools, DSAR builders) reads `:consent` before deciding what
     to do.
   - Withdrawal does NOT retroactively invalidate prior processing.
     Processing under the prior consent remains lawful for the period
     it was active; processing after `:kontor.consent/withdrawn-at` must rely
     on a different basis (or stop).
   - Per ADR-094 §6, the project refuses to ship a kernel-side enforcer
     for the `:ai-act-incompatible` legal-basis marker — substrate
     neutrality means consumer policy layers decide. This namespace
     does not check legal-basis values for legitimacy.

   ## Composition

   - `kontor.hr.dsar/collect-for-person` walks `:consent` rows (see
     ADR-052).
   - `kontor.retention` matches `:kontor.retention-policy/category` against
     the same vocabulary `:kontor.consent/scope` uses, so a withdrawn consent
     pairs naturally with an accelerated retention sweep.
   - ADR-094 substrate posture per research note 93."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-code
  "Resolve a `:consent` eid by its `:kontor.consent/code`."
  [db code]
  (d/q '[:find ?e .
         :in $ ?c
         :where [?e :kontor.consent/code ?c]]
       db code))

(defn- ->eid [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    (map? spec)    (:db/id spec)
    :else          spec))

;; ============================================================================
;; Queries
;; ============================================================================

(defn active-at?
  "Was a consent for `(subject, scope)` operationally in force at
   instant `at`?

   `subject` is a `:person` eid (or pull-result map). `scope` is a
   keyword (typically a member of
   `kontor.audit-doc/canonical-categories`). Returns a boolean.

   Operationally-in-force = `:granted-at <= at` AND (`:withdrawn-at`
   is missing OR `at < :withdrawn-at`) AND `:state ∈ {:active
   :withdrawn :superseded}`. A withdrawn or superseded consent stays
   in force RETROSPECTIVELY for the time it was active — this is the
   regulator-aligned semantic (processing under the prior consent
   remains lawful for the window it was in force).

   `:proposed` rows are NOT in force — they exist as drafts but
   confer no legal basis until activated."
  [db subject scope ^java.util.Date at]
  (let [subj-eid (->eid db subject)
        rows     (->> (d/q '[:find [?c ...]
                             :in $ ?subj ?scope
                             :where
                             [?c :kontor.consent/subject ?subj]
                             [?c :kontor.consent/scope ?scope]]
                           db subj-eid scope)
                      (mapv #(d/pull db
                                     [:kontor.consent/granted-at
                                      :kontor.consent/withdrawn-at
                                      :kontor.consent/state] %)))]
    (boolean
     (some
      (fn [{:kontor.consent/keys [granted-at withdrawn-at state]}]
        (and (#{:active :withdrawn :superseded} state)
             (some? granted-at)
             (not (.after ^java.util.Date granted-at at))
             (or (nil? withdrawn-at)
                 (.before ^java.util.Date at ^java.util.Date withdrawn-at))))
      rows))))

(defn for-subject
  "All `:consent` rows for a subject. Returns pull-results sorted by
   `:granted-at` ascending."
  [db subject]
  (let [subj-eid (->eid db subject)
        eids     (d/q '[:find [?c ...]
                        :in $ ?subj
                        :where [?c :kontor.consent/subject ?subj]]
                      db subj-eid)]
    (sort-by :kontor.consent/granted-at
             (mapv #(d/pull db '[*] %) eids))))

;; ============================================================================
;; Transactors — ADR-068 pure tx-data builders + ! wrappers
;; ============================================================================

(defn grant-tx-data
  "Pure tx-data builder for `grant!`. Required keys: `:code :subject
   :scope :legal-basis`. Optional: `:granted-at` (defaults to now),
   `:supporting-doc`, `:works-agreement-ref`,
   `:notice-acknowledged-at`, `:parent-consent`, `:tempid`."
  [db {:keys [code subject scope legal-basis granted-at supporting-doc
              works-agreement-ref notice-acknowledged-at parent-consent
              tempid]
       :or {tempid "consent-1"}}]
  (when-not code        (throw (ex-info ":code required" {})))
  (when-not subject     (throw (ex-info ":subject required" {})))
  (when-not scope       (throw (ex-info ":scope required" {})))
  (when-not legal-basis (throw (ex-info ":legal-basis required" {})))
  (let [subj-eid (->eid db subject)
        sup-eid  (when supporting-doc (->eid db supporting-doc))
        wa-eid   (when works-agreement-ref (->eid db works-agreement-ref))
        pc-eid   (when parent-consent (->eid db parent-consent))]
    [(cond-> {:db/id              tempid
              :kontor.consent/code       code
              :kontor.consent/subject    subj-eid
              :kontor.consent/scope      scope
              :kontor.consent/legal-basis legal-basis
              :kontor.consent/granted-at (or granted-at (java.util.Date.))
              :kontor.consent/state      :active}
       sup-eid                (assoc :kontor.consent/supporting-doc sup-eid)
       wa-eid                 (assoc :kontor.consent/works-agreement-ref wa-eid)
       notice-acknowledged-at (assoc :kontor.consent/notice-acknowledged-at notice-acknowledged-at)
       pc-eid                 (assoc :kontor.consent/parent-consent pc-eid))]))

(defn grant!
  "Create + activate a `:consent` row in one tx. Routes through the
   validation gate (ADR-068). Returns the tx-report.

   Required: `:code :subject :scope :legal-basis`.
   Optional: `:granted-at`, `:supporting-doc`, `:works-agreement-ref`,
   `:notice-acknowledged-at`, `:parent-consent`."
  [conn spec]
  (validation/transact-with-validation
   conn (grant-tx-data (d/db conn) spec)))

(defn withdraw-tx-data
  "Pure tx-data builder for `withdraw!`. Records the withdrawal as a
   status-machine transition `:active → :withdrawn` AND sets
   `:kontor.consent/withdrawn-at`. Required: `:consent`, `:changed-by-uid`.
   Optional: `:withdrawn-at` (defaults to now), `:reason-note`,
   `:supporting-doc` (the withdrawal request)."
  [db {:keys [consent changed-by-uid withdrawn-at reason-note
              supporting-doc]}]
  (when-not consent        (throw (ex-info ":consent required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [con-eid (->eid db consent)
        when    (or withdrawn-at (java.util.Date.))]
    (when-not con-eid
      (throw (ex-info "Consent not found" {:spec consent})))
    (let [current (:kontor.consent/state
                   (d/pull db [:kontor.consent/state] con-eid))]
      (when-not (= :active current)
        (throw (ex-info "Consent not in :active state"
                        {:consent con-eid :current current})))
      (concat
       [{:db/id con-eid :kontor.consent/withdrawn-at when}]
       (sm/record-status-change-tx-data
        db
        (cond-> {:entity con-eid
                 :entity-type :consent
                 :facet :kontor.consent/state
                 :from :active
                 :to :withdrawn
                 :changed-at when
                 :changed-by-uid changed-by-uid
                 :reason :consent-withdrawn}
          reason-note    (assoc :reason-note reason-note)
          supporting-doc (assoc :supporting-doc (->eid db supporting-doc))))))))

(defn withdraw!
  "Record consent withdrawal. Routes through the gate (ADR-068).

   Required: `:consent`, `:changed-by-uid`. Optional: `:withdrawn-at`,
   `:reason-note`, `:supporting-doc`."
  [conn spec]
  (validation/transact-with-validation
   conn (withdraw-tx-data (d/db conn) spec)))

(defn supersede-tx-data
  "Pure tx-data builder for `supersede!`. Records the old `:consent`
   `:active → :superseded` and creates a fresh `:consent` row via
   `grant-tx-data`. Required: `:old :new`. `:old` resolves to an
   existing consent; `:new` is the grant-spec for the replacement."
  [db {:keys [old new changed-by-uid changed-at reason-note]}]
  (when-not old (throw (ex-info ":old required" {})))
  (when-not new (throw (ex-info ":new required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [old-eid (->eid db old)
        when    (or changed-at (java.util.Date.))
        new-tempid (or (:tempid new) "consent-new-1")]
    (when-not old-eid
      (throw (ex-info "Consent not found" {:spec old})))
    (concat
     (sm/record-status-change-tx-data
      db
      (cond-> {:entity old-eid
               :entity-type :consent
               :facet :kontor.consent/state
               :from :active
               :to :superseded
               :changed-at when
               :changed-by-uid changed-by-uid
               :reason :consent-superseded}
        reason-note (assoc :reason-note reason-note)))
     (grant-tx-data db (assoc new :tempid new-tempid
                              :granted-at (or (:granted-at new) when))))))

(defn supersede!
  "Supersede an active consent with a fresh one (scope expanded /
   legal-basis updated / works-agreement refreshed). Routes through
   the gate (ADR-068)."
  [conn spec]
  (validation/transact-with-validation
   conn (supersede-tx-data (d/db conn) spec)))

(ns kontor.disposal
  "kontor-disposal — recording ownership-change events for capital-
   gains tax (ADR-102, research notes 107 / 112-115).

   A `:disposal` is an event: at one moment in time a specific
   subject (an asset, a lot of shares, a participation) is relinquished.
   The GL posts proceeds and basis against accounts; the `:disposal`
   carries the event data — what was disposed, when, for how much,
   against what basis, with what holding period — that the GL alone
   cannot see.

   The companion is the data layer; per-jurisdiction CGT providers
   (l10n-us / l10n-de / l10n-uk / l10n-jp / …) consume `:disposal`
   data to compute capital-gains tax under their statute.

   Every business write follows ADR-068 — a pure `*-tx-data` builder
   plus a `!` wrapper that stamps `:tx/valid-from` and routes through
   `kontor.validation`.

   Public surface:
     record-disposal!  / -tx-data   record an ownership-change event
     recognize!        / -tx-data   link the realising :transaction
                                    (advance :recorded → :recognized)
     void!             / -tx-data   void an earlier disposal (correction)
     disposals-of                   every disposal of a given subject
     disposals-in-period            disposals in a date window
     realized-gain                  proceeds − basis − rollover-deferred
     realized-gain-summary          summed over a period × loss-bucket
     pull-disposal / resolve-disposal / install!"
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.disposal.schema :as schema]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

(def install! schema/install!)

(def ^:private kinds
  #{:sale :incorporation-contribution :abandonment :gift :conversion
    :distribution-in-kind :deemed})

(def ^:private states
  #{:recorded :recognized :voided})

;; ============================================================================
;; Resolution + pull
;; ============================================================================

(defn resolve-disposal
  "Resolve a disposal spec — an `:disposal/external-id` string or an
   eid — to an eid (nil if absent)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (d/q '[:find ?e . :in $ ?x
                          :where [?e :disposal/external-id ?x]]
                        db spec)
    :else          spec))

(defn pull-disposal
  "Pull a disposal with the commodities + audit-doc refs expanded."
  [db spec]
  (when-let [eid (resolve-disposal db spec)]
    (d/pull db
            '[* {:disposal/proceeds-commodity            [:kontor.commodity/symbol]
                 :disposal/basis-commodity               [:kontor.commodity/symbol]
                 :disposal/depreciation-taken-commodity  [:kontor.commodity/symbol]
                 :disposal/rollover-amount-commodity     [:kontor.commodity/symbol]}]
            eid)))

;; ============================================================================
;; record-disposal! — record a new disposal event
;; ============================================================================

(defn record-disposal-tx-data
  "Pure tx-data builder for `record-disposal!` (ADR-068).

   REQUIRED opts:
     :entity            ref to the holder `:entity` (whose CGT this is)
     :external-id       string — caller's stable id
     :kind              one of #{:sale :incorporation-contribution
                                 :abandonment :gift :conversion
                                 :distribution-in-kind :deemed}
     :subject           ref to the disposed entity
     :subject-kind      keyword classification (see schema)
     :acquired-on       :instant — acquisition date
     :disposed-on       :instant — disposal date
     :proceeds          {:amount <bigdec> :commodity <ref>}
     :basis             {:amount <bigdec> :commodity <ref>}
     :recorded-by-uid   acting user

   OPTIONAL (jurisdiction-specific extension fields — populated by
   the consumer when the regime requires them):
     :asset-class       keyword (jurisdiction-tagged)
     :subject-form      one of #{:corp :partnership :sole-prop :individual}
     :holding-period    keyword (denormalised at record time)
     :depreciation-taken {:amount :commodity}
     :ownership-fraction bigdec (0–1)
     :residence?        boolean
     :elective-regime   set of keywords
     :exemption-claimed set of keywords
     :rollover          {:into-asset :amount :commodity :deadline}
     :loss-bucket       keyword
     :audit-doc         seq of refs
     :notes             string
     :tempid            (default \"disposal-1\")
     :recorded-at       (default now)

   Initial state is `:recorded`. The companion does NOT post anything
   to the GL — the consumer's transaction (Dr cash / Cr asset / Dr-Cr
   realised gain-or-loss) is posted separately, then linked via
   `recognize!` which advances `:recorded → :recognized`."
  [db {:keys [entity external-id kind subject subject-kind asset-class subject-form
              acquired-on disposed-on holding-period
              proceeds basis depreciation-taken
              ownership-fraction residence? elective-regime exemption-claimed
              rollover loss-bucket audit-doc notes
              recorded-by-uid tempid recorded-at]
       :or   {tempid "disposal-1"}}]
  (when-not entity         (throw (ex-info ":entity required (the disposal's holder)" {})))
  (when-not external-id    (throw (ex-info ":external-id required" {})))
  (when-not (kinds kind)   (throw (ex-info (str ":kind must be one of " kinds)
                                           {:kind kind})))
  (when-not subject        (throw (ex-info ":subject required" {})))
  (when-not subject-kind   (throw (ex-info ":subject-kind required" {})))
  (when-not acquired-on    (throw (ex-info ":acquired-on required" {})))
  (when-not disposed-on    (throw (ex-info ":disposed-on required" {})))
  (when-not (and proceeds (:amount proceeds) (:commodity proceeds))
    (throw (ex-info ":proceeds must have :amount + :commodity" {:proceeds proceeds})))
  (when-not (and basis (:amount basis) (:commodity basis))
    (throw (ex-info ":basis must have :amount + :commodity" {:basis basis})))
  (when-not recorded-by-uid (throw (ex-info ":recorded-by-uid required" {})))
  (let [recorded-at (or recorded-at (java.util.Date.))
        row (cond-> {:db/id                       tempid
                     :disposal/external-id        external-id
                     :disposal/entity             entity
                     :disposal/kind               kind
                     :disposal/subject            subject
                     :disposal/subject-kind       subject-kind
                     :disposal/acquired-on        acquired-on
                     :disposal/disposed-on        disposed-on
                     :disposal/proceeds-amount    (bigdec (:amount proceeds))
                     :disposal/proceeds-commodity (:commodity proceeds)
                     :disposal/basis-amount       (bigdec (:amount basis))
                     :disposal/basis-commodity    (:commodity basis)}
              asset-class       (assoc :disposal/asset-class       asset-class)
              subject-form      (assoc :disposal/subject-form      subject-form)
              holding-period    (assoc :disposal/holding-period    holding-period)
              depreciation-taken (-> (assoc :disposal/depreciation-taken-amount
                                            (bigdec (:amount depreciation-taken)))
                                     (assoc :disposal/depreciation-taken-commodity
                                            (:commodity depreciation-taken)))
              ownership-fraction (assoc :disposal/ownership-fraction (bigdec ownership-fraction))
              (some? residence?) (assoc :disposal/residence? residence?)
              (seq elective-regime)   (assoc :disposal/elective-regime   (vec elective-regime))
              (seq exemption-claimed) (assoc :disposal/exemption-claimed (vec exemption-claimed))
              rollover (-> (assoc :disposal/rollover-into-asset (:into-asset rollover))
                           (cond->
                            (:amount rollover)
                             (assoc :disposal/rollover-amount (bigdec (:amount rollover)))
                             (:commodity rollover)
                             (assoc :disposal/rollover-amount-commodity (:commodity rollover))
                             (:deadline rollover)
                             (assoc :disposal/rollover-deadline (:deadline rollover))))
              loss-bucket       (assoc :disposal/loss-bucket loss-bucket)
              (seq audit-doc)   (assoc :disposal/audit-doc (vec audit-doc))
              notes             (assoc :disposal/notes notes))
        status-tx (sm/record-status-change-tx-data
                   db {:entity         tempid
                       :entity-type    :disposal
                       :facet          :disposal/state
                       :from           :nil
                       :to             :recorded
                       :changed-at     recorded-at
                       :changed-by-uid recorded-by-uid
                       :reason         :disposal-recorded})]
    (into [row] status-tx)))

(defn record-disposal!
  "Record a new `:disposal` (state `:recorded`). See
   `record-disposal-tx-data` for the opts; the `!` wrapper also takes
   `:vt-from` / `:vt-to` (default now / forever)."
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [recorded-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (record-disposal-tx-data
                        (d/db conn) (assoc opts :recorded-at recorded-at))
                       (or vt-from recorded-at)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; recognize! — link the realising :transaction (advance :recorded → :recognized)
;; ============================================================================

(defn recognize-tx-data
  "Link a previously-recorded disposal to the kernel `:transaction`
   that posted proceeds / basis / gain-or-loss to the GL. Advances
   `:disposal/state` from `:recorded` → `:recognized`.

   Required opts: `:disposal` (spec), `:transaction` (eid),
   `:recorded-by-uid`. Optional: `:recognized-at` (default now)."
  [db {:keys [disposal transaction recorded-by-uid recognized-at]}]
  (let [eid (resolve-disposal db disposal)]
    (when-not eid          (throw (ex-info "Disposal not found" {:spec disposal})))
    (when-not transaction  (throw (ex-info ":transaction required" {})))
    (when-not recorded-by-uid (throw (ex-info ":recorded-by-uid required" {})))
    (let [recognized-at (or recognized-at (java.util.Date.))
          curr-state    (d/q '[:find ?st . :in $ ?e
                               :where [?e :disposal/state ?st]] db eid)]
      (when-not (= :recorded curr-state)
        (throw (ex-info "Disposal must be in :recorded state to recognize"
                        {:disposal eid :state curr-state})))
      (into [{:db/id eid :disposal/realizing-tx transaction}]
            (sm/record-status-change-tx-data
             db {:entity         eid
                 :entity-type    :disposal
                 :facet          :disposal/state
                 :from           :recorded
                 :to             :recognized
                 :changed-at     recognized-at
                 :changed-by-uid recorded-by-uid
                 :reason         :disposal-recognized})))))

(defn recognize!
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [recognized-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (recognize-tx-data
                        (d/db conn) (assoc opts :recognized-at recognized-at))
                       (or vt-from recognized-at)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; void! — correction (state → :voided)
;; ============================================================================

(defn void-tx-data
  "Void a previously-recorded (or recognized) disposal. The original
   disposal is NOT retracted — the audit chain is preserved. A void
   row may optionally point at a NEW correcting disposal via
   `:replaced-by` (the new disposal's tempid)."
  [db {:keys [disposal recorded-by-uid voided-at reason replaced-by]}]
  (let [eid (resolve-disposal db disposal)]
    (when-not eid           (throw (ex-info "Disposal not found" {:spec disposal})))
    (when-not recorded-by-uid (throw (ex-info ":recorded-by-uid required" {})))
    (let [voided-at  (or voided-at (java.util.Date.))
          curr-state (d/q '[:find ?st . :in $ ?e
                            :where [?e :disposal/state ?st]] db eid)]
      (when-not (#{:recorded :recognized} curr-state)
        (throw (ex-info "Disposal must be in :recorded or :recognized state to void"
                        {:disposal eid :state curr-state})))
      (into (cond-> [{:db/id eid}]
              replaced-by (conj {:db/id replaced-by :disposal/voids eid}))
            (sm/record-status-change-tx-data
             db {:entity         eid
                 :entity-type    :disposal
                 :facet          :disposal/state
                 :from           curr-state
                 :to             :voided
                 :changed-at     voided-at
                 :changed-by-uid recorded-by-uid
                 :reason         (or reason :disposal-voided)})))))

(defn void!
  [conn {:keys [vt-from vt-to] :as opts}]
  (let [voided-at (java.util.Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (void-tx-data (d/db conn) (assoc opts :voided-at voided-at))
                       (or vt-from voided-at)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn disposals-of
  "Every disposal of `subject` (an eid) as `db` sees it. Excludes
   voided disposals."
  [db subject]
  (->> (d/q '[:find [?d ...]
              :in $ ?s
              :where
              [?d :disposal/subject ?s]
              [?d :disposal/state ?st]
              [(not= ?st :voided)]]
            db subject)
       (map #(pull-disposal db %))
       (sort-by :disposal/disposed-on)
       vec))

(defn disposals-in-period
  "Every disposal whose `:disposal/disposed-on` lies in the half-open
   `[from, to)` window. Excludes voided disposals.

   Two arities:
   - `[db period]` — all disposals in the window, across all entities.
   - `[db entity period]` — only disposals owned by `entity` (an eid
     or `[:kontor.entity/code <code>]` lookup ref). The entity-scoped form
     is what CGT providers call — per-entity is the natural CGT
     unit of analysis."
  ([db period]
   (->> (d/q '[:find [?d ...]
               :in $ ?from ?to
               :where
               [?d :disposal/disposed-on ?on]
               [(<= ?from ?on)]
               [(< ?on ?to)]
               [?d :disposal/state ?st]
               [(not= ?st :voided)]]
             db (:from period) (:to period))
        (map #(pull-disposal db %))
        (sort-by :disposal/disposed-on)
        vec))
  ([db entity {:keys [from to]}]
   (->> (d/q '[:find [?d ...]
               :in $ ?entity ?from ?to
               :where
               [?d :disposal/entity ?entity]
               [?d :disposal/disposed-on ?on]
               [(<= ?from ?on)]
               [(< ?on ?to)]
               [?d :disposal/state ?st]
               [(not= ?st :voided)]]
             db entity from to)
        (map #(pull-disposal db %))
        (sort-by :disposal/disposed-on)
        vec)))

;; ============================================================================
;; Realised gain / loss helpers
;; ============================================================================

(defn realized-gain
  "Realised gain (positive) or loss (negative) on a single disposal:
   `proceeds − basis − rollover-amount`. The rollover-amount is the
   slice deferred into a replacement asset under §1031 / §6b / s152 /
   §36-2 — NOT recognised this period.

   Takes a pull-result map. Returns BigDecimal (in the proceeds
   commodity — the caller is responsible for commodity coherence)."
  [disposal-map]
  (let [proceeds (or (:disposal/proceeds-amount disposal-map) 0M)
        basis    (or (:disposal/basis-amount disposal-map) 0M)
        rollover (or (:disposal/rollover-amount disposal-map) 0M)]
    (- proceeds basis rollover)))

(defn realized-gain-summary
  "Sum realised gain/loss across the disposals in a period, optionally
   grouped by `:loss-bucket`. Returns `{:bucket-or-nil <bigdec>}`
   where the special key `nil` collects disposals with no
   `:disposal/loss-bucket` set."
  [db period]
  (->> (disposals-in-period db period)
       (group-by :disposal/loss-bucket)
       (reduce-kv (fn [acc bucket ds]
                    (assoc acc bucket (reduce + 0M (map realized-gain ds))))
                  {})))

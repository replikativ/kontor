(ns kontor.collections.dunning
  "Dunning policy + planning + emission — ADR-043.

   `plan-dunning-run` is PURE — it reads the db at `:as-of`, walks the
   open cases under the policy, applies pause-gates (dispute, open
   promise, unapplied-cash, frequency-cap), and returns a vec of
   planned `{:case :invoice :level :template-ref :locale :skipped?
   :skip-reason}` rows. Caller chooses which to emit.

   `emit-dunning-event!` materializes one planned row: writes the
   `:dunning-event` + the `:audit-doc` for the rendered letter + the
   `:side-effect-intent` for the outgoing channel work, all in one
   transact."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.pause :as kpause]
            [kontor.collections.promise :as kpromise]
            [kontor.validation :as validation]))

;; ============================================================================
;; Template provider protocol
;; ============================================================================

(defprotocol DunningTemplateProvider
  "Resolve a template ref to renderable content. l10n modules
   (`kontor-l10n-de`, `kontor-l10n-us`) provide concrete impls;
   `static-template-provider` is a kernel fallback that takes a
   plain map.

   `(resolve-template provider opts) → {:rendered-content string
                                        :content-hash string
                                        :locale string}`"
  (resolve-template [this opts]))

(defn static-template-provider
  "Build a provider from a map of `(level locale) → EDN-template`.
   Used in tests and as a kernel-level default. The 'render' is a
   placeholder — production providers run a real templating engine."
  [templates-map]
  (reify DunningTemplateProvider
    (resolve-template [_ {:keys [level locale]}]
      (let [tpl (get-in templates-map [level locale])]
        (when-not tpl
          (throw (ex-info "No template for (level, locale)"
                          {:level level :locale locale})))
        {:rendered-content (pr-str tpl)
         :content-hash (str "sha256:" (.hashCode tpl))
         :locale (str locale)}))))

;; ============================================================================
;; Policy resolution
;; ============================================================================

(defn- decode-levels
  "Decode `:dunning-policy/levels` (EDN-encoded string) to a Clojure
   vector of level maps."
  [s]
  (if (string? s)
    (edn/read-string s)
    s))

(defn resolve-policy
  "Find the most-specific active :dunning-policy for (entity,
   segment). Falls through:
     1. (entity, segment) — most specific
     2. (entity, :default)
     3. (nil-entity, segment)
     4. (nil-entity, :default)

   Returns the pulled policy map or nil."
  [db {:keys [entity segment]
       :or {segment :default}}]
  (let [q (fn [entity-eid seg]
            (let [eid (d/q '[:find ?p .
                             :in $ ?e ?s
                             :where
                             [?p :dunning-policy/active true]
                             [?p :dunning-policy/applies-to-segment ?s]
                             [?p :dunning-policy/entity ?e]]
                           db entity-eid seg)]
              (when eid (d/pull db '[*] eid))))
        q-tenant (fn [seg]
                   (let [eid (d/q '[:find ?p .
                                    :in $ ?s
                                    :where
                                    [?p :dunning-policy/active true]
                                    [?p :dunning-policy/applies-to-segment ?s]
                                    [(missing? $ ?p :dunning-policy/entity)]]
                                  db seg)]
                     (when eid (d/pull db '[*] eid))))]
    (or (and entity (q entity segment))
        (and entity (q entity :default))
        (q-tenant segment)
        (q-tenant :default))))

;; ============================================================================
;; Frequency cap
;; ============================================================================

(defn dunning-events-in-window
  "Count sent (non-skipped) `:dunning-event` rows for a case within
   the last `window-days` days, relative to `:as-of` (default now).

   P1 fix: previously read System/currentTimeMillis which made
   `plan-dunning-run` non-deterministic across reruns at the same
   :as-of."
  ([db case-eid window-days] (dunning-events-in-window db case-eid window-days nil))
  ([db case-eid window-days as-of]
   (let [as-of-ms (.getTime ^java.util.Date (or as-of (java.util.Date.)))
         cutoff-ms (- as-of-ms (* window-days 24 60 60 1000))]
     (or (d/q '[:find (count ?e) .
                :in $ ?case ?cutoff-ms
                :where
                [?e :dunning-event/case ?case]
                [?e :dunning-event/sent-at ?when]
                [(.getTime ^java.util.Date ?when) ?when-ms]
                [(>= ?when-ms ?cutoff-ms)]
                (not [?e :dunning-event/skipped? true])]
              db case-eid cutoff-ms)
         0))))

(defn frequency-cap-violated?
  "True iff sending another event for this case would exceed the
   policy's cap. `:as-of` defaults to now."
  ([db case-eid policy] (frequency-cap-violated? db case-eid policy nil))
  ([db case-eid
    {:dunning-policy/keys [frequency-cap-window-days
                           frequency-cap-max-events]}
    as-of]
   (and frequency-cap-window-days frequency-cap-max-events
        (>= (dunning-events-in-window db case-eid frequency-cap-window-days as-of)
            frequency-cap-max-events))))

;; ============================================================================
;; Planning (pure)
;; ============================================================================

(defn- case-skip-reason
  "Read pause gates against (case, invoice). Returns the first
   matching skip-reason keyword or nil.

   Order (most-specific → least):
     1. :explicit-pause — an active :dunning-pause row (P0-5 fix)
     2. :open-dispute   — any open dispute on the invoice
     3. :open-promise   — any open PTP on the case
     4. :unapplied-cash-pending — caller-supplied fn returns positive
     5. :frequency-cap — Reg-F count over window"
  [db {:keys [case-eid invoice-eid policy unapplied-cash-fn as-of]}]
  (cond
    ;; P0-5 fix: explicit :dunning-pause row gates dunning regardless
    ;; of dispute / promise / cash flags.
    (kpause/any-active-pause? db case-eid {:as-of-valid as-of})
    :explicit-pause

    (and (:dunning-policy/pause-on-dispute? policy)
         invoice-eid
         (kdispute/any-open-dispute-for-invoice? db invoice-eid))
    :open-dispute

    (and (:dunning-policy/pause-on-open-promise? policy)
         (kpromise/any-open-promise-for-partner-invoice?
          db case-eid invoice-eid))
    :open-promise

    (and (:dunning-policy/pause-on-unapplied-cash? policy)
         unapplied-cash-fn
         (let [u (unapplied-cash-fn db case-eid)]
           (and u (pos? (.signum ^java.math.BigDecimal u)))))
    :unapplied-cash-pending

    (frequency-cap-violated? db case-eid policy as-of)
    :frequency-cap

    :else nil))

(defn- next-level-for
  "Decide the next dunning level to send for a case. Walks the
   policy's level vec; the case's `:dunning-event` history determines
   how many levels have already been sent (non-skipped). Returns
   the next level map or nil when policy exhausted."
  [db case-eid policy]
  (let [levels (decode-levels (:dunning-policy/levels policy))
        sent-levels (set (d/q '[:find [?lvl ...]
                                :in $ ?case
                                :where
                                [?e :dunning-event/case ?case]
                                [?e :dunning-event/level ?lvl]
                                (not [?e :dunning-event/skipped? true])
                                [?e :dunning-event/sent-at _]]
                              db case-eid))
        ;; Convention: level map's :ordinal is its index; fall back
        ;; to position when :ordinal is absent.
        with-ordinals (map-indexed (fn [idx lvl]
                                     (assoc lvl :ordinal
                                            (or (:ordinal lvl)
                                                (inc idx))))
                                   levels)]
    (first (remove (fn [lvl] (contains? sent-levels (:ordinal lvl)))
                   with-ordinals))))

(defn plan-dunning-run
  "Plan a dunning batch. Pure: only reads `db`. Caller emits via
   `emit-dunning-event!`.

   Required opts:
     :as-of            instant cutoff for aging eligibility
     :entity           ref to the entity scope (ADR-031)
     :policy           pulled :dunning-policy map
     :cases            seq of {:case-eid :invoice-eid :segment
                                :partner :locale}.
                       Caller computes which cases are due (typically
                       from `aging.clj` + `:collection-case/total-
                       overdue`).

   Optional opts:
     :unapplied-cash-fn  (fn [db case-eid] → BigDecimal) — used for
                         the :unapplied-cash-pending gate. Omit to
                         skip the gate.

   Returns vec of {:case :invoice :level :ordinal :template-ref
                   :locale :scheduled-at :skipped? :skip-reason}
   one row per (case, invoice) input."
  [db {:keys [as-of entity policy cases unapplied-cash-fn]}]
  (mapv (fn [{:keys [case-eid invoice-eid locale]}]
          (let [skip (case-skip-reason db {:case-eid case-eid
                                           :invoice-eid invoice-eid
                                           :policy policy
                                           :unapplied-cash-fn unapplied-cash-fn
                                           :as-of as-of})
                lvl (when-not skip (next-level-for db case-eid policy))
                base {:case case-eid
                      :invoice invoice-eid
                      :scheduled-at as-of
                      :locale locale}]
            (cond
              skip (assoc base :skipped? true :skip-reason skip)
              (nil? lvl) (assoc base :skipped? true
                                :skip-reason :policy-exhausted)
              :else (assoc base
                           :level (:ordinal lvl)
                           :ordinal (:ordinal lvl)
                           :template-ref (:template-ref lvl)
                           :late-fee-pct (:late-fee-pct lvl)
                           :late-fee-fixed (:late-fee-fixed lvl)
                           :skipped? false))))
        cases))

;; ============================================================================
;; Emission (impure)
;; ============================================================================

(declare emit-dunning-event-tx-data)

(defn emit-dunning-event!
  "Materialize one planned row. In one tx:
     1. Write the :dunning-event row.
     2. (If not skipped) write an :audit-doc for the rendered letter.
     3. (If not skipped) write a :side-effect-intent for the outgoing
        channel (email/letter/etc).

   The rendered-content + content-hash come from a
   `DunningTemplateProvider`. Caller passes the provider.

   Required opts:
     :plan-row    one row from `plan-dunning-run`
     :channel     :email | :letter | :phone | :portal
     :provider    DunningTemplateProvider impl

   Optional opts:
     :acting-uid  ref to :create/uid (audit field on the side-effect-
                  intent if your runtime uses it)
     :now         instant (default now)

   The pure tx-data builder is `emit-dunning-event-tx-data`."
  [conn {:keys [now] :as opts}]
  (let [now (or now (java.util.Date.))]
    (validation/transact-with-validation
     conn (emit-dunning-event-tx-data
           (d/db conn) (assoc opts :now now)))))

(defn emit-dunning-event-tx-data
  "Pure tx-data builder for `emit-dunning-event!` (ADR-068). Optional
   `:tempid-suffix` (default `\"\"`) namespaces internal tempids so
   multiple emissions compose in one process tx."
  [_db {:keys [plan-row channel provider now tempid-suffix]
        :or {tempid-suffix ""}}]
  (let [now (or now (java.util.Date.))
        sent? (not (:skipped? plan-row))
        event-tempid (str "ev" tempid-suffix)
        rendered (when sent?
                   (resolve-template provider
                                     {:level (:level plan-row)
                                      :locale (:locale plan-row)
                                      :template-ref (:template-ref plan-row)}))
        doc-tempid (str "ev-doc" tempid-suffix)
        intent-tempid (str "ev-intent" tempid-suffix)
        event-row (cond-> {:db/id event-tempid
                           :dunning-event/case (:case plan-row)
                           :dunning-event/level (or (:level plan-row) 0)
                           :dunning-event/scheduled-at (:scheduled-at plan-row)
                           :dunning-event/channel channel
                           :dunning-event/locale (:locale plan-row)}
                    (:invoice plan-row)
                    (assoc :dunning-event/invoice (:invoice plan-row))

                    (:template-ref plan-row)
                    (assoc :dunning-event/template-ref (:template-ref plan-row))

                    sent?
                    (assoc :dunning-event/sent-at now
                           :dunning-event/audit-doc doc-tempid
                           :dunning-event/side-effect-intent intent-tempid)

                    (:skipped? plan-row)
                    (assoc :dunning-event/skipped? true
                           :dunning-event/skip-reason (:skip-reason plan-row)))
        doc-rows (when sent?
                   [{:db/id doc-tempid
                     :audit-doc/code (str "DUNN-"
                                          (.getTime ^java.util.Date now)
                                          "-"
                                          (:case plan-row))
                     :audit-doc/type :dunning-letter
                     :audit-doc/title (str "Dunning Letter L"
                                           (:level plan-row))
                     :audit-doc/content-hash (:content-hash rendered)
                     :audit-doc/uploaded-at now}])
        intent-rows (when sent?
                      [{:db/id intent-tempid
                        :side-effect-intent/key
                        (str "DUNN-" (.getTime ^java.util.Date now)
                             "-" (:case plan-row))
                        :side-effect-intent/type (case channel
                                                   :email :send-email
                                                   :letter :send-letter
                                                   :phone :phone-call-scheduled
                                                   :portal :portal-notification
                                                   :send-email)
                        :side-effect-intent/payload (:rendered-content rendered)
                        :side-effect-intent/status :pending
                        :side-effect-intent/created-at now
                        :side-effect-intent/retry-count 0
                        :side-effect-intent/max-retries 3}])]
    (vec (concat [event-row] doc-rows intent-rows))))

;; ============================================================================
;; Seeds — sensible defaults
;; ============================================================================

(def default-policy-levels-edn
  "EDN-encoded vec for `:dunning-policy/levels`. Three-level cadence
   roughly aligned with EU Late Payment Directive 2011/7/EU and US
   common practice (30/60/90). Tenants override per jurisdiction via
   l10n modules."
  (pr-str [{:ordinal 1 :trigger-days 14 :template-ref :reminder
            :late-fee-pct 0M}
           {:ordinal 2 :trigger-days 30 :template-ref :mahnung-2
            :late-fee-pct 0.08M}
           {:ordinal 3 :trigger-days 60 :template-ref :final-notice
            :late-fee-pct 0.08M
            :late-fee-fixed 40M}]))

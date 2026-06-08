(ns kontor.asset.runner
  "The depreciation runner — ADR-055.

   A thin convenience over the ADR-032 schedule machinery: for each
   `:schedule` occurrence that is due but not yet fired, ask the
   book's `DepreciationProvider` for the period's amount, build the
   `Dr expense / Cr accumulated` entry with
   `kontor.asset.posting/plan-depreciation-charge`, and log the
   occurrence with `kontor.workflow.schedule/record-occurrence!` (idempotent
   on `[schedule, sequence]` — re-running a fired month does not
   double-post).

   Trigger ownership (ADR-032): `kontor-asset`
   ships the runner *functions* — `run-depreciation!` and
   `catch-up!` — but NOT a scheduler. *Who* calls them (a
   consumer-app cron, a manual close-period step, a workflow engine)
   is out of scope.

   Sequencing rule: run the year's final
   depreciation occurrence BEFORE `kontor.reporting.closing/close-fiscal-year!`
   — otherwise the year's last charge lands after the close. The
   runner does not enforce this; it is a caller-ordering convention."
  (:require [datahike.api :as d]
            [kontor.asset.depreciation :as depreciation]
            [kontor.asset.depreciation-provider :as dp]
            [kontor.asset.posting :as ap]
            [kontor.bitemporal :as kbt]
            [kontor.workflow.process :as process]
            [kontor.workflow.schedule :as schedule]
            [kontor.workflow.status-machine :as sm])
  (:import [java.math BigDecimal RoundingMode]
           [java.util Date]))

(defn- units-for
  "Resolve the per-occurrence unit actuals for a units-of-production
   book. `units` may be a map `{sequence → bigdec}` or a function
   `sequence → bigdec`."
  [units sequence]
  (cond
    (map? units) (get units sequence)
    (fn? units)  (units sequence)
    :else        nil))

(defn- earliest-removal-date
  "The earliest `:disposal` / `:transfer` `:asset-event` date for an
   asset, or nil. The runner must not depreciate past a terminal
   event (code-review)."
  [db asset-eid]
  (->> (d/q '[:find [?d ...]
              :in $ ?asset
              :where
              [?e :kontor.asset-event/asset ?asset]
              [?e :kontor.asset-event/kind ?k]
              [(contains? #{:disposal :transfer} ?k)]
              [?e :kontor.asset-event/date ?d]]
            db asset-eid)
       sort
       first))

(defn run-depreciation!
  "Fire every depreciation occurrence for `book-spec` that is due on
   or before `:as-of` (default = now) and not yet fired.

   `book-spec` is an `:asset-depreciation` eid or an `[asset ledger]`
   pair. Returns:

     {:book       eid
      :fired      [sequence …]
      :count      n
      :total      bigdec        ; Σ charged this run
      :completed? boolean}      ; the schedule is now fully fired

   When the schedule becomes fully fired AND the asset is still
   `:in-service`, the runner drives `:kontor.asset/status` →
   `:fully-depreciated` (unless `:mark-fully-depreciated?` is false).
   That transition is ungated (no `:approval-policy`), so it needs no
   `:changed-by-uid` — pass one anyway to attribute the system run.

   Required opts:
     :journal              journal ref for the depreciation entries

   Optional opts:
     :as-of                instant — fire occurrences due ≤ this
                           (default now)
     :provider             a DepreciationProvider instance — pass an
                           l10n provider directly; defaults to the
                           built-in resolved from the book's
                           :provider-id
     :posted?              seal the entries (default true). Sealed
                           entries show in :posted-only reports.
     :units                units-of-production books only — a map
                           {sequence → bigdec} or fn sequence → bigdec
     :changed-by-uid       attribute the :fully-depreciated transition
     :mark-fully-depreciated?  default true

   Safety (review-after fixes):
   - The runner STOPS at the earliest `:disposal` / `:transfer`
     `:asset-event` — it never depreciates past a terminal event
     (code-review). `:disposal-date` is returned when this
     truncated the run.
   - Each charge is checked against `kontor.compliance.period` — firing into a
     soft-closed or sealed period throws
     `:kontor.period/locked-period-violation`, surfaced to the caller
. Occurrences already
     fired earlier in the same run stay (they were in open periods);
     `:fired-before-violation` carries the partial progress."
  ([conn book-spec] (run-depreciation! conn book-spec {}))
  ([conn book-spec {:keys [journal as-of provider posted? units
                           changed-by-uid mark-fully-depreciated?]
                    :or {posted? true mark-fully-depreciated? true}}]
   (when-not journal (throw (ex-info ":journal required" {})))
   (let [db (d/db conn)
         book-eid (depreciation/resolve-book db book-spec)
         _ (when-not book-eid
             (throw (ex-info "Depreciation book not found" {:spec book-spec})))
         inputs (depreciation/book-plan-inputs db book-eid)
         schedule-eid (:schedule inputs)
         commodity (:commodity inputs)
         prov (or provider (dp/provider-for (:provider-id inputs)))
         plan (dp/plan-schedule prov {:db db :book book-eid})
         seq->amount (into {} (map (juxt :sequence :amount)) (:periods plan))
         requires-units? (boolean (:requires-units plan))
         rate-per-unit (:rate-per-unit plan)
         removal-date (earliest-removal-date db (:asset inputs))
         pending (cond->> (sort-by :sequence
                                   (schedule/pending-occurrences
                                    db schedule-eid (or as-of (Date.))))
                   ;; Never depreciate on or past a disposal/transfer.
                   removal-date
                   (filterv (fn [{:keys [^Date date]}]
                              (neg? (.compareTo date ^Date removal-date)))))
         fired
         (reduce
          (fn [acc {:keys [sequence date]}]
            (let [amount
                  (if requires-units?
                    (let [u (units-for units sequence)]
                      (when-not u
                        (throw (ex-info "units-of-production book: no :units supplied for a pending occurrence"
                                        {:book book-eid :sequence sequence})))
                      (.setScale (.multiply ^BigDecimal rate-per-unit
                                            ^BigDecimal u)
                                 2 RoundingMode/HALF_EVEN))
                    (seq->amount sequence))]
              (when-not amount
                (throw (ex-info "runner: no planned amount for a pending occurrence"
                                {:book book-eid :sequence sequence})))
              ;; ONE atomic, gated process per period — each its own
              ;; datahike-tx with its own :tx/valid-from = the period's
              ;; effective-date (preserves per-period bitemporal
              ;; valid-time for restated-history queries; ADR-067
              ;; addendum). The hand-call to assert-not-in-locked-period!
              ;; is gone — period (+ sealing + sum-to-zero + invariants)
              ;; check runs in the gate.
              (let [period-step
                    (fn [sdb _ctx]
                      (let [charge
                            (ap/plan-depreciation-charge
                             sdb (cond-> {:book book-eid
                                          :amount amount
                                          :journal journal
                                          :date date
                                          :narration (str "Depreciation " sequence)}
                                   posted? (assoc :posted-at date)))]
                        (schedule/record-occurrence-tx-data
                         sdb schedule-eid sequence date amount commodity
                         charge (Date.))))]
                (try
                  (process/run-process
                   conn {:steps [period-step]
                         :vt-from date
                         :vt-to kbt/forever})
                  (catch clojure.lang.ExceptionInfo e
                    (let [data (ex-data e)]
                      (if (= :kontor.period/locked-period-violation (:type data))
                        (throw (ex-info (.getMessage e)
                                        (assoc data
                                               :book book-eid
                                               :sequence sequence
                                               :fired-before-violation
                                               (mapv :sequence acc))
                                        e))
                        (throw e))))))
              (conj acc {:sequence sequence :date date :amount amount})))
          []
          pending)
         total (reduce (fn [^BigDecimal a {:keys [amount]}] (.add a amount))
                       0M fired)
         db' (d/db conn)
         completed? (>= (count (schedule/fired-sequences db' schedule-eid))
                        (:n-periods inputs))]
     (when (and completed? mark-fully-depreciated? (seq fired))
       (let [asset-eid (:asset inputs)
             status (:kontor.asset/status (d/pull db' [:kontor.asset/status] asset-eid))]
         (when (= :in-service status)
           (let [last-date (:date (last fired))
                 status-step
                 (fn [sdb _ctx]
                   (sm/record-status-change-tx-data
                    sdb (cond-> {:entity asset-eid
                                 :entity-type :asset
                                 :facet :kontor.asset/status
                                 :from :in-service
                                 :to :fully-depreciated
                                 :changed-at last-date
                                 :reason :asset-fully-depreciated}
                          changed-by-uid (assoc :changed-by-uid changed-by-uid))))]
             (process/run-process
              conn {:steps [status-step]
                    :vt-from last-date
                    :vt-to kbt/forever})))))
     (cond-> {:book book-eid
              :fired (mapv :sequence fired)
              :count (count fired)
              :total total
              :completed? completed?}
       removal-date (assoc :disposal-date removal-date)))))

(defn catch-up!
  "Fire every depreciation occurrence due on or before `as-of` that
   has not yet fired — the missed-month / backfill case. A named
   wrapper over `run-depreciation!` with an explicit `as-of`."
  ([conn book-spec as-of] (catch-up! conn book-spec as-of {}))
  ([conn book-spec as-of opts]
   (run-depreciation! conn book-spec (assoc opts :as-of as-of))))

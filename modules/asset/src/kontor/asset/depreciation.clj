(ns kontor.asset.depreciation
  "kontor-asset depreciation-book management — ADR-054.

   A depreciation book is per-(asset, ledger): the 'depreciation
   area' IS a `:ledger` (ADR-021). One physical `:asset` has N
   `:asset-depreciation` books — one per ledger (HGB + Steuerbilanz;
   book + tax; IFRS + local GAAP). Each book owns one ADR-032
   `:schedule` that the depreciation runner (ADR-055) fires.

   This namespace is the book lifecycle: `open-book!` creates a book
   + its schedule + its optional method-params in one tx;
   `accumulated-depreciation` / `net-book-value` are the asset-local
   roll-forward queries.

   ## Why the roll-forward reads `:schedule-occurrence`, not the GL

   `accumulated-depreciation` sums `:schedule-occurrence/amount` over
   the book's schedule — it does NOT sum GL postings to the
   `:asset/accumulated-account`. The GL accounts are shared across
   every asset in a class and a `:posting` carries no per-asset
   back-ref, so a GL sum cannot be attributed to one asset. The
   subsystem's own occurrence log is the source of truth for the
   roll-forward; the GL postings are its *consequence* (built by
   `kontor.asset.posting`). This is also what makes the query
   ledger-aware by construction — each book owns its own schedule."
  (:require [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.schedule :as schedule]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn book-for
  "Resolve the `:asset-depreciation` book eid for an (asset, ledger)
   pair, or nil. `asset-spec` is a code or eid; `ledger` is an eid."
  [db asset-spec ledger]
  (when-let [asset-eid (asset/resolve-asset db asset-spec)]
    (d/q '[:find ?e .
           :in $ ?a ?l
           :where
           [?e :asset-depreciation/asset ?a]
           [?e :asset-depreciation/ledger ?l]]
         db asset-eid ledger)))

(defn resolve-book
  "Coerce `spec` to an `:asset-depreciation` eid. `spec` may be an
   eid, or a `[asset-spec ledger]` pair."
  [db spec]
  (cond
    (nil? spec)        nil
    (integer? spec)    spec
    (vector? spec)     (book-for db (first spec) (second spec))
    :else              spec))

(defn books-of
  "All `:asset-depreciation` book eids for an asset."
  [db asset-spec]
  (when-let [asset-eid (asset/resolve-asset db asset-spec)]
    (set (d/q '[:find [?e ...]
                :in $ ?a
                :where [?e :asset-depreciation/asset ?a]]
              db asset-eid))))

(defn pull-book
  "Pull an `:asset-depreciation` book with its asset + ledger."
  [db spec]
  (when-let [eid (resolve-book db spec)]
    (d/pull db
            '[* {:asset-depreciation/asset [:asset/code :asset/name
                                            :asset/acquisition-cost]
                 :asset-depreciation/ledger [:ledger/code :ledger/framework]
                 :asset-depreciation/method-params [*]
                 :asset-depreciation/schedule [:db/id :schedule/code
                                               :schedule/kind :schedule/state
                                               :schedule/start-date
                                               :schedule/end-date
                                               :schedule/frequency]}]
            eid)))

;; ============================================================================
;; Roll-forward queries
;; ============================================================================

(defn accumulated-depreciation
  "Σ `:schedule-occurrence/amount` over the book's schedule — the
   accumulated depreciation charged so far for this (asset, ledger)
   book. Returns a bigdec (0M when nothing has been charged)."
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)
        sched (:db/id (:asset-depreciation/schedule
                       (d/pull db [:asset-depreciation/schedule] eid)))]
    (or (when sched
          ;; `:with ?o` keeps each occurrence distinct in the
          ;; relation — without it two €1,000 charges collapse to a
          ;; single {€1,000} value-set and the sum is wrong.
          (d/q '[:find (sum ?amt) .
                 :with ?o
                 :in $ ?s
                 :where
                 [?o :schedule-occurrence/schedule ?s]
                 [?o :schedule-occurrence/amount ?amt]]
               db sched))
        0M)))

(defn net-book-value
  "Carrying amount of the asset in this book:
   `acquisition-cost − accumulated-depreciation`.

   ADR-054's NBV reflects the depreciation schedule only. Mid-life
   events (impairment, revaluation, subsequent additions) adjust NBV
   via ADR-055's re-planning — folded in there, not here."
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)
        asset-eid (:db/id (:asset-depreciation/asset
                           (d/pull db [:asset-depreciation/asset] eid)))
        cost (:asset/acquisition-cost
              (d/pull db [:asset/acquisition-cost] asset-eid))]
    (.subtract ^java.math.BigDecimal (or cost 0M)
               (accumulated-depreciation db eid))))

;; ============================================================================
;; open-book!
;; ============================================================================

(defn- periods-for
  "Number of schedule occurrences over `useful-life-months` at
   `frequency` — used to derive the schedule's end-date."
  ^long [^long useful-life-months frequency]
  (case frequency
    :monthly   useful-life-months
    :quarterly (long (Math/ceil (/ useful-life-months 3.0)))
    :annual    (long (Math/ceil (/ useful-life-months 12.0)))
    (throw (ex-info "open-book!: unsupported :frequency"
                    {:frequency frequency
                     :supported #{:monthly :quarterly :annual}}))))

(defn open-book!
  "Create an `:asset-depreciation` book for an (asset, ledger) pair —
   plus its ADR-032 `:schedule` and its optional `:asset-method-params`
   — in one tx. Returns the tx-report.

   The `:asset-depreciation/identity` tuple (`:db.unique/identity` on
   `[asset ledger]`) means a second `open-book!` for the same pair
   collides — one book per (asset, ledger).

   Required opts:
     :asset               code or eid of :asset
     :ledger              eid of :ledger (the depreciation area)
     :provider-id         keyword — which DepreciationProvider (ADR-055)
     :useful-life-months  long — this book's useful life

   Optional opts:
     :convention          keyword (default :full)
     :depreciable-base    bigdec (default = acquisition-cost −
                          salvage-value, pulled from the asset)
     :commodity           ref/eid (default = asset's
                          :acquisition-commodity)
     :start-date          instant (default = asset's :in-service-date;
                          required if the asset has none)
     :frequency           :monthly (default) | :quarterly | :annual
     :method-params       a map (created inline as an
                          :asset-method-params entity) or an eid
     :effective-rule      eid of the l10n-owned effective-dated rule
                          row (ADR-055 §effective-dating)
     :schedule-code       string (default = \"<asset-code>-dep-<ledger-code>\")
     :note                string"
  [conn {:keys [asset ledger provider-id useful-life-months convention
                depreciable-base commodity start-date frequency
                method-params effective-rule schedule-code note]
         :or   {convention :full frequency :monthly}}]
  (when-not asset              (throw (ex-info ":asset required" {})))
  (when-not ledger             (throw (ex-info ":ledger required" {})))
  (when-not provider-id        (throw (ex-info ":provider-id required" {})))
  (when-not useful-life-months (throw (ex-info ":useful-life-months required" {})))
  (let [db (d/db conn)
        asset-eid (asset/resolve-asset db asset)
        _ (when-not asset-eid (throw (ex-info "Asset not found" {:spec asset})))
        _ (when (book-for db asset-eid ledger)
            (throw (ex-info "A depreciation book already exists for this (asset, ledger) — one book per pair (ADR-054)"
                            {:type :asset/duplicate-book
                             :asset asset :ledger ledger})))
        a (d/pull db [:asset/code :asset/acquisition-cost
                      :asset/acquisition-commodity :asset/salvage-value
                      :asset/in-service-date]
                  asset-eid)
        commodity* (or commodity (:db/id (:asset/acquisition-commodity a)))
        base (or depreciable-base
                 (.subtract ^java.math.BigDecimal (:asset/acquisition-cost a)
                            ^java.math.BigDecimal (or (:asset/salvage-value a) 0M)))
        start (or start-date (:asset/in-service-date a))
        _ (when-not start
            (throw (ex-info "open-book!: no :start-date and the asset has no :in-service-date — place it in service first or pass :start-date"
                            {:asset asset})))
        ledger-code (:ledger/code (d/pull db [:ledger/code] ledger))
        sched-code (or schedule-code
                       (str (:asset/code a) "-dep-" (or ledger-code ledger)))
        n-periods (periods-for useful-life-months frequency)
        end-date (schedule/date-of-occurrence start frequency n-periods)
        sched-tempid "asset-dep-schedule"
        mparams-tempid "asset-dep-method-params"
        mparams-entity (when (map? method-params)
                         (assoc method-params :db/id mparams-tempid))
        schedule-entity (cond-> {:db/id sched-tempid
                                 :schedule/code sched-code
                                 :schedule/kind :depreciation
                                 :schedule/origin-entity "asset-dep-book"
                                 :schedule/start-date start
                                 :schedule/end-date end-date
                                 :schedule/frequency frequency
                                 :schedule/total-amount base
                                 :schedule/state :active
                                 :schedule/active true}
                          commodity* (assoc :schedule/total-commodity commodity*))
        book-entity (cond-> {:db/id "asset-dep-book"
                             :asset-depreciation/asset asset-eid
                             :asset-depreciation/ledger ledger
                             :asset-depreciation/provider-id provider-id
                             :asset-depreciation/useful-life-months useful-life-months
                             :asset-depreciation/convention convention
                             :asset-depreciation/depreciable-base base
                             :asset-depreciation/start-date start
                             :asset-depreciation/schedule sched-tempid}
                      commodity*     (assoc :asset-depreciation/commodity commodity*)
                      mparams-entity (assoc :asset-depreciation/method-params mparams-tempid)
                      (and method-params (not (map? method-params)))
                      (assoc :asset-depreciation/method-params method-params)
                      effective-rule (assoc :asset-depreciation/effective-rule effective-rule)
                      note           (assoc :asset-depreciation/note note))]
    (d/transact conn (cond-> [book-entity schedule-entity]
                       mparams-entity (conj mparams-entity)))))

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
  "Total accumulated depreciation for this (asset, ledger) book:
   the book's `:opening-accumulated` (pre-schedule depreciation from
   a mid-life import — usually absent) plus Σ `:schedule-occurrence/
   amount` over the book's schedule. Returns a bigdec (0M when
   nothing has been charged)."
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)
        b (d/pull db [:asset-depreciation/schedule
                      :asset-depreciation/opening-accumulated]
                  eid)
        sched (:db/id (:asset-depreciation/schedule b))
        opening (or (:asset-depreciation/opening-accumulated b) 0M)
        from-occurrences
        (or (when sched
              ;; `:with ?o` keeps each occurrence distinct in the
              ;; relation — without it two €1,000 charges collapse to
              ;; a single {€1,000} value-set and the sum is wrong.
              (d/q '[:find (sum ?amt) .
                     :with ?o
                     :in $ ?s
                     :where
                     [?o :schedule-occurrence/schedule ?s]
                     [?o :schedule-occurrence/amount ?amt]]
                   db sched))
            0M)]
    (.add ^java.math.BigDecimal opening ^java.math.BigDecimal from-occurrences)))

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
;; Plan inputs (ADR-055 — consumed by the DepreciationProvider impls)
;; ============================================================================

(defn periods-for
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

(defn book-plan-inputs
  "Resolve everything a `DepreciationProvider` needs to plan a book's
   schedule into one flat map (ADR-055). Pulls the book, its asset,
   and its schedule.

   Returns:
     {:book :asset :schedule
      :provider-id :convention
      :acquisition-cost :salvage-value :depreciable-base
      :useful-life-months :n-periods
      :frequency :start-date :commodity
      :method-params <map or nil>}"
  [db book-spec]
  (let [eid (resolve-book db book-spec)
        _ (when-not eid (throw (ex-info "Depreciation book not found" {:spec book-spec})))
        b (d/pull db
                  '[:asset-depreciation/provider-id
                    :asset-depreciation/convention
                    :asset-depreciation/depreciable-base
                    :asset-depreciation/useful-life-months
                    {:asset-depreciation/asset [:db/id :asset/acquisition-cost
                                                :asset/salvage-value]}
                    {:asset-depreciation/commodity [:db/id]}
                    {:asset-depreciation/method-params [*]}
                    {:asset-depreciation/schedule [:db/id :schedule/frequency
                                                   :schedule/start-date]}]
                  eid)
        asset (:asset-depreciation/asset b)
        sched (:asset-depreciation/schedule b)
        freq  (:schedule/frequency sched)]
    {:book               eid
     :asset              (:db/id asset)
     :schedule           (:db/id sched)
     :provider-id        (:asset-depreciation/provider-id b)
     :convention         (:asset-depreciation/convention b)
     :acquisition-cost   (:asset/acquisition-cost asset)
     :salvage-value      (or (:asset/salvage-value asset) 0M)
     :depreciable-base   (:asset-depreciation/depreciable-base b)
     :useful-life-months (:asset-depreciation/useful-life-months b)
     :n-periods          (periods-for (:asset-depreciation/useful-life-months b) freq)
     :frequency          freq
     :start-date         (:schedule/start-date sched)
     :commodity          (:db/id (:asset-depreciation/commodity b))
     :method-params      (:asset-depreciation/method-params b)}))

(defn open-book-tx-data
  "Pure tx-data builder for `open-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.process` step (ADR-067); `open-book!` is the standalone
   wrapper. See `open-book!` for the opts."
  [db {:keys [asset ledger provider-id useful-life-months convention
              depreciable-base opening-accumulated commodity start-date
              frequency method-params effective-rule expense-account
              schedule-code note]
       :or   {convention :full frequency :monthly}}]
  (when-not asset              (throw (ex-info ":asset required" {})))
  (when-not ledger             (throw (ex-info ":ledger required" {})))
  (when-not provider-id        (throw (ex-info ":provider-id required" {})))
  (when-not useful-life-months (throw (ex-info ":useful-life-months required" {})))
  (let [asset-eid (asset/resolve-asset db asset)
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
        _ (when-not commodity*
            (throw (ex-info "open-book!: no :commodity and the asset has no :acquisition-commodity — pass :commodity"
                            {:asset asset})))
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
                             :asset-depreciation/commodity commodity*
                             :asset-depreciation/start-date start
                             :asset-depreciation/schedule sched-tempid}
                      opening-accumulated
                      (assoc :asset-depreciation/opening-accumulated opening-accumulated)
                      mparams-entity (assoc :asset-depreciation/method-params mparams-tempid)
                      (and method-params (not (map? method-params)))
                      (assoc :asset-depreciation/method-params method-params)
                      effective-rule (assoc :asset-depreciation/effective-rule effective-rule)
                      expense-account (assoc :asset-depreciation/expense-account
                                             expense-account)
                      note           (assoc :asset-depreciation/note note))]
    (cond-> [book-entity schedule-entity]
      mparams-entity (conj mparams-entity))))

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
                          salvage-value, pulled from the asset). For a
                          mid-life import pass the REMAINING base.
     :opening-accumulated bigdec — depreciation accumulated before
                          this book's schedule (the mid-life-import
                          case). A reporting scalar — see
                          `accumulated-depreciation`.
     :commodity           ref/eid (default = asset's
                          :acquisition-commodity; must be resolvable)
     :start-date          instant (default = asset's :in-service-date;
                          required if the asset has none)
     :frequency           :monthly (default) | :quarterly | :annual
     :method-params       a map (created inline as an
                          :asset-method-params entity) or an eid
     :effective-rule      eid of the l10n-owned effective-dated rule
                          row (ADR-055 §effective-dating)
     :expense-account     per-book override of the asset's
                          :asset/expense-account (ADR-063 — a ROU
                          asset debits a different P&L account per
                          ledger). Absent ⇒ the asset's account.
     :schedule-code       string (default = \"<asset-code>-dep-<ledger-code>\")
     :note                string

   The pure tx-data builder is `open-book-tx-data` (ADR-067)."
  [conn opts]
  (d/transact conn (open-book-tx-data (d/db conn) opts)))

;; ============================================================================
;; revise-book! — the explicit "supersede the pending tail" operation (ADR-055)
;; ============================================================================

(defn revise-book-tx-data
  "Pure tx-data builder for `revise-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.process` step (ADR-067); `revise-book!` is the standalone
   wrapper. See `revise-book!` for the opts."
  [db {:keys [book new-useful-life-months additional-base note]}]
  (when-not (or new-useful-life-months additional-base)
    (throw (ex-info "revise-book!: :new-useful-life-months or :additional-base required" {})))
  (let [eid (resolve-book db book)
        _ (when-not eid (throw (ex-info "Depreciation book not found" {:spec book})))
        b (d/pull db [:asset-depreciation/useful-life-months
                      :asset-depreciation/depreciable-base
                      :asset-depreciation/start-date
                      {:asset-depreciation/schedule [:db/id :schedule/frequency]}]
                  eid)
        sched (:asset-depreciation/schedule b)
        sched-eid (:db/id sched)
        freq (:schedule/frequency sched)
        life (or new-useful-life-months (:asset-depreciation/useful-life-months b))
        base (cond-> (:asset-depreciation/depreciable-base b)
               additional-base
               (#(.add ^java.math.BigDecimal % ^java.math.BigDecimal additional-base)))
        n-periods (periods-for life freq)
        fired (count (schedule/fired-sequences db sched-eid))
        _ (when (< n-periods fired)
            (throw (ex-info "revise-book!: revised useful life implies fewer periods than already fired"
                            {:type :asset/revision-below-fired
                             :revised-periods n-periods :fired fired})))
        end-date (schedule/date-of-occurrence (:asset-depreciation/start-date b)
                                              freq n-periods)]
    [(cond-> {:db/id eid
              :asset-depreciation/useful-life-months life
              :asset-depreciation/depreciable-base base}
       note (assoc :asset-depreciation/note note))
     {:db/id sched-eid
      :schedule/end-date end-date
      :schedule/total-amount base}]))

(defn revise-book!
  "Apply a prospective change to a book — an IAS 16 useful-life
   revision and/or a subsequent capitalised addition. Updates the
   book's `:useful-life-months` / `:depreciable-base` and reschedules
   the `:schedule` `:end-date` (+ `:total-amount`). Fired
   `:schedule-occurrence`s are NEVER touched — the next
   `run-depreciation!` re-plans only the un-fired tail (the
   DepreciationProvider's `plan-schedule` reads the fired log).

   This is the per-book half of the cross-book `:asset-event`
   recorded by `kontor.asset.asset/revise-useful-life!` /
   `record-addition!` — per-book because an HGB life and an
   AfA-Tabelle life differ.

   Required: :book (eid or [asset ledger])
   At least one of:
     :new-useful-life-months  long — the revised TOTAL useful life
     :additional-base         bigdec — capitalised cost to add to
                              :depreciable-base (a subsequent addition)
   Optional:
     :note                    string

   Throws if the revised useful life implies fewer periods than have
   already been fired.

   The pure tx-data builder is `revise-book-tx-data` (ADR-067)."
  [conn opts]
  (d/transact conn (revise-book-tx-data (d/db conn) opts)))

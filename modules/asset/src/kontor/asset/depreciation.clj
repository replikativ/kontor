(ns kontor.asset.depreciation
  "kontor-asset depreciation-book management — ADR-054.

   A depreciation book is per-(asset, ledger): the 'depreciation
   area' IS a `:ledger` (ADR-021). One physical `:asset` has N
   `:asset-depreciation` books — one per ledger (HGB + Steuerbilanz;
   book + tax; IFRS + local GAAP). Each book owns one ADR-032
   `:schedule` that the depreciation runner (ADR-055) fires.

   This namespace is the book lifecycle: `open-book!` creates a book
   + its schedule + its optional method-params in one tx;
   `scheduled-depreciation` / `accumulated-depreciation` /
   `gross-carrying-amount` / `net-book-value` are the asset-local
   roll-forward queries.

   ## Why the roll-forward reads subledger facts, not the GL

   The roll-forward queries read `:schedule-occurrence` +
   `:asset-event` — they do NOT sum GL postings to the
   `:kontor.asset/asset-account` / `:kontor.asset/accumulated-account`.
   The GL accounts are shared across every asset in a class and a
   `:posting` carries no per-asset back-ref, so a GL sum cannot be
   attributed to one asset. The subsystem's own logs are the source of
   truth for the roll-forward; the GL postings are its *consequence*
   (built by `kontor.asset.posting`). This is also what makes the query
   ledger-aware by construction — each book owns its own schedule.

   ## The event fold, and why it is load-bearing

   A subledger figure must account for EVERY value movement the module
   posts to the control account, not just the ones its own schedule
   planned. `kontor.asset.posting/plan-impairment` credits the
   accumulated-account and `plan-revaluation` debits the asset-account,
   and neither writes a `:schedule-occurrence`. So:

     scheduled-depreciation  = :opening-accumulated + Σ occurrences
     accumulated-depreciation = scheduled-depreciation + Σ :impairment
     gross-carrying-amount    = :acquisition-cost + Σ :revaluation
                                                  + Σ :addition

   `accumulated-depreciation` (not `scheduled-depreciation`) is what
   `plan-disposal` relieves — reading the schedule alone left the
   impairment stranded in the GL and flipped the sign of the
   disposal gain/loss.

   `:asset-event` is asset-level, not per-book (there is no
   `:kontor.asset-event/ledger`), so a value-moving event folds into
   EVERY book of the asset — matching the intended flow, where
   `plan-impairment` / `plan-revaluation` are called once per book.
   A caller that impairs only one ledger of a multi-book asset must
   record one event per affected book's amount; the drift is surfaced
   by `kontor.asset.report/asset-tie-out`, never hidden.

   `asset-tie-out` is the detective control for the other direction:
   an event recorded here whose GL entry was never posted shows up as
   a non-zero `:difference`, which is the correct finding (\"someone
   recorded an impairment and forgot to post it\") rather than a
   report bug."
  (:require [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.workflow.schedule :as schedule]
            [kontor.validation :as validation]))

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
           [?e :kontor.asset-depreciation/asset ?a]
           [?e :kontor.asset-depreciation/ledger ?l]]
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
                :where [?e :kontor.asset-depreciation/asset ?a]]
              db asset-eid))))

(defn pull-book
  "Pull an `:asset-depreciation` book with its asset + ledger."
  [db spec]
  (when-let [eid (resolve-book db spec)]
    (d/pull db
            '[* {:kontor.asset-depreciation/asset [:kontor.asset/code :kontor.asset/name
                                            :kontor.asset/acquisition-cost]
                 :kontor.asset-depreciation/ledger [:kontor.ledger/code :kontor.ledger/framework]
                 :kontor.asset-depreciation/method-params [*]
                 :kontor.asset-depreciation/schedule [:db/id :kontor.schedule/code
                                               :kontor.schedule/kind :kontor.schedule/state
                                               :kontor.schedule/start-date
                                               :kontor.schedule/end-date
                                               :kontor.schedule/frequency]}]
            eid)))

;; ============================================================================
;; Roll-forward queries
;; ============================================================================

(defn asset-of
  "The `:asset` eid a book belongs to."
  [db book-spec]
  (when-let [eid (resolve-book db book-spec)]
    (:db/id (:kontor.asset-depreciation/asset
             (d/pull db [:kontor.asset-depreciation/asset] eid)))))

(defn event-amount-sum
  "Σ `:kontor.asset-event/amount` over an asset's `:asset-event`s of
   `kind`. Returns a bigdec (0M when there are none).

   `:with ?e` is load-bearing: without it two events sharing a
   `(kind, amount)` collapse into ONE row of the value-set and the sum
   silently undercounts — the same hazard the occurrence sum below
   documents."
  ^java.math.BigDecimal [db asset-eid kind]
  (or (d/q '[:find (sum ?amt) .
             :with ?e
             :in $ ?a ?k
             :where
             [?e :kontor.asset-event/asset ?a]
             [?e :kontor.asset-event/kind ?k]
             [?e :kontor.asset-event/amount ?amt]]
           db asset-eid kind)
      0M))

(defn scheduled-depreciation
  "The PLANNED depreciation charged on this (asset, ledger) book: the
   book's `:opening-accumulated` (pre-schedule depreciation from a
   mid-life import — usually absent) plus Σ
   `:kontor.schedule-occurrence/amount` over the book's schedule.
   Returns a bigdec (0M when nothing has been charged).

   This is the schedule's own log and NOT the control-account figure —
   `plan-impairment` credits the accumulated-account without writing an
   occurrence. Use `accumulated-depreciation` for anything that has to
   agree with the GL."
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)
        b (d/pull db [:kontor.asset-depreciation/schedule
                      :kontor.asset-depreciation/opening-accumulated]
                  eid)
        sched (:db/id (:kontor.asset-depreciation/schedule b))
        opening (or (:kontor.asset-depreciation/opening-accumulated b) 0M)
        from-occurrences
        (or (when sched
              ;; `:with ?o` keeps each occurrence distinct in the
              ;; relation — without it two €1,000 charges collapse to
              ;; a single {€1,000} value-set and the sum is wrong.
              (d/q '[:find (sum ?amt) .
                     :with ?o
                     :in $ ?s
                     :where
                     [?o :kontor.schedule-occurrence/schedule ?s]
                     [?o :kontor.schedule-occurrence/amount ?amt]]
                   db sched))
            0M)]
    (.add ^java.math.BigDecimal opening ^java.math.BigDecimal from-occurrences)))

(defn accumulated-depreciation
  "Everything this book has credited to the asset's
   `:kontor.asset/accumulated-account`:
   `scheduled-depreciation + Σ :impairment :asset-event amounts`.

   The impairment fold is what makes this the CONTROL-ACCOUNT figure
   rather than the schedule's log — `kontor.asset.posting/plan-impairment`
   credits the accumulated-account and writes no
   `:schedule-occurrence`, so a schedule-only sum understates the
   contra-asset and, via `plan-disposal`, mis-signs the gain/loss on
   disposal. See the ns docstring for the per-book caveat on
   asset-level events."
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)]
    (.add (scheduled-depreciation db eid)
          (event-amount-sum db (asset-of db eid) :impairment))))

(defn gross-carrying-amount
  "Everything this book has debited to the asset's
   `:kontor.asset/asset-account`:
   `acquisition-cost + Σ :revaluation + Σ :addition :asset-event
   amounts`.

   `plan-revaluation` debits the asset-account and a capitalised
   `:addition` is booked to it too, while neither restates
   `:kontor.asset/acquisition-cost` (one cost, shared by every book —
   ADR-054). So the gross cost the GL carries is the acquisition cost
   PLUS the mid-life value movements, and that is what `plan-disposal`
   must relieve."
  ^java.math.BigDecimal [db book-spec]
  (let [asset-eid (asset-of db book-spec)
        cost (or (:kontor.asset/acquisition-cost
                  (d/pull db [:kontor.asset/acquisition-cost] asset-eid))
                 0M)]
    (.add (.add ^java.math.BigDecimal cost
                (event-amount-sum db asset-eid :revaluation))
          (event-amount-sum db asset-eid :addition))))

(defn net-book-value
  "Carrying amount of the asset in this book:
   `gross-carrying-amount − accumulated-depreciation`.

   Mid-life events ARE folded in: a `:revaluation` / `:addition` lifts
   the gross side, an `:impairment` lifts the accumulated side —
   mirroring the GL entries `kontor.asset.posting` builds for them.
   (`revise-book!` additionally re-spreads the un-fired schedule tail
   prospectively per IAS 16; that changes the FUTURE charges, not this
   figure.)"
  ^java.math.BigDecimal [db book-spec]
  (let [eid (resolve-book db book-spec)]
    (.subtract (gross-carrying-amount db eid)
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
                  '[:kontor.asset-depreciation/provider-id
                    :kontor.asset-depreciation/convention
                    :kontor.asset-depreciation/depreciable-base
                    :kontor.asset-depreciation/useful-life-months
                    {:kontor.asset-depreciation/asset [:db/id :kontor.asset/acquisition-cost
                                                :kontor.asset/salvage-value]}
                    {:kontor.asset-depreciation/commodity [:db/id]}
                    {:kontor.asset-depreciation/method-params [*]}
                    {:kontor.asset-depreciation/schedule [:db/id :kontor.schedule/frequency
                                                   :kontor.schedule/start-date]}]
                  eid)
        asset (:kontor.asset-depreciation/asset b)
        sched (:kontor.asset-depreciation/schedule b)
        freq  (:kontor.schedule/frequency sched)]
    {:book               eid
     :asset              (:db/id asset)
     :schedule           (:db/id sched)
     :provider-id        (:kontor.asset-depreciation/provider-id b)
     :convention         (:kontor.asset-depreciation/convention b)
     :acquisition-cost   (:kontor.asset/acquisition-cost asset)
     :salvage-value      (or (:kontor.asset/salvage-value asset) 0M)
     :depreciable-base   (:kontor.asset-depreciation/depreciable-base b)
     :useful-life-months (:kontor.asset-depreciation/useful-life-months b)
     :n-periods          (periods-for (:kontor.asset-depreciation/useful-life-months b) freq)
     :frequency          freq
     :start-date         (:kontor.schedule/start-date sched)
     :commodity          (:db/id (:kontor.asset-depreciation/commodity b))
     :method-params      (:kontor.asset-depreciation/method-params b)}))

(defn open-book-tx-data
  "Pure tx-data builder for `open-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.workflow.process` step (ADR-067); `open-book!` is the standalone
   wrapper. See `open-book!` for the opts, plus two composition knobs:

     :asset-tempid   — use this verbatim as the `:kontor.asset-depreciation/
                       asset` ref instead of resolving `:asset`. For
                       when the ROU/asset entity is created by an
                       earlier process step (`commence!`) and threads
                       by string tempid. In this mode the asset is
                       not pulled, so `:depreciable-base`,
                       `:commodity`, `:start-date` and
                       `:schedule-code` must all be passed explicitly.
     :tempid-suffix  — appended to the book/schedule/method-params
                       tempids (default \"\"); pass a distinct suffix
                       per book when several `open-book-tx-data`
                       outputs compose into one tx-data."
  [db {:keys [asset asset-tempid ledger provider-id useful-life-months
              convention depreciable-base opening-accumulated commodity
              start-date frequency method-params effective-rule
              expense-account schedule-code note tempid-suffix]
       :or   {convention :full frequency :monthly tempid-suffix ""}}]
  (when-not (or asset asset-tempid)
    (throw (ex-info ":asset or :asset-tempid required" {})))
  (when-not ledger             (throw (ex-info ":ledger required" {})))
  (when-not provider-id        (throw (ex-info ":provider-id required" {})))
  (when-not useful-life-months (throw (ex-info ":useful-life-months required" {})))
  (when asset-tempid
    (doseq [[k v] {:depreciable-base depreciable-base :commodity commodity
                   :start-date start-date :schedule-code schedule-code}]
      (when (nil? v)
        (throw (ex-info (str "open-book-tx-data: " k
                             " required when :asset-tempid is given (the asset is not pulled)")
                        {:missing k})))))
  (let [asset-eid (or asset-tempid (asset/resolve-asset db asset))
        _ (when-not asset-eid (throw (ex-info "Asset not found" {:spec asset})))
        _ (when (and asset (book-for db asset-eid ledger))
            (throw (ex-info "A depreciation book already exists for this (asset, ledger) — one book per pair (ADR-054)"
                            {:type :kontor.asset/duplicate-book
                             :asset asset :ledger ledger})))
        a (when asset
            (d/pull db [:kontor.asset/code :kontor.asset/acquisition-cost
                        :kontor.asset/acquisition-commodity :kontor.asset/salvage-value
                        :kontor.asset/in-service-date]
                    asset-eid))
        commodity* (or commodity (:db/id (:kontor.asset/acquisition-commodity a)))
        _ (when-not commodity*
            (throw (ex-info "open-book!: no :commodity and the asset has no :acquisition-commodity — pass :commodity"
                            {:asset asset})))
        base (or depreciable-base
                 (.subtract ^java.math.BigDecimal (:kontor.asset/acquisition-cost a)
                            ^java.math.BigDecimal (or (:kontor.asset/salvage-value a) 0M)))
        start (or start-date (:kontor.asset/in-service-date a))
        _ (when-not start
            (throw (ex-info "open-book!: no :start-date and the asset has no :in-service-date — place it in service first or pass :start-date"
                            {:asset asset})))
        ledger-code (:kontor.ledger/code (d/pull db [:kontor.ledger/code] ledger))
        sched-code (or schedule-code
                       (str (:kontor.asset/code a) "-dep-" (or ledger-code ledger)))
        n-periods (periods-for useful-life-months frequency)
        end-date (schedule/date-of-occurrence start frequency n-periods)
        book-tempid (str "asset-dep-book" tempid-suffix)
        sched-tempid (str "asset-dep-schedule" tempid-suffix)
        mparams-tempid (str "asset-dep-method-params" tempid-suffix)
        mparams-entity (when (map? method-params)
                         (assoc method-params :db/id mparams-tempid))
        schedule-entity (cond-> {:db/id sched-tempid
                                 :kontor.schedule/code sched-code
                                 :kontor.schedule/kind :depreciation
                                 :kontor.schedule/origin-entity book-tempid
                                 :kontor.schedule/start-date start
                                 :kontor.schedule/end-date end-date
                                 :kontor.schedule/frequency frequency
                                 :kontor.schedule/total-amount base
                                 :kontor.schedule/state :active
                                 :kontor.schedule/active true}
                          commodity* (assoc :kontor.schedule/total-commodity commodity*))
        book-entity (cond-> {:db/id book-tempid
                             :kontor.asset-depreciation/asset asset-eid
                             :kontor.asset-depreciation/ledger ledger
                             :kontor.asset-depreciation/provider-id provider-id
                             :kontor.asset-depreciation/useful-life-months useful-life-months
                             :kontor.asset-depreciation/convention convention
                             :kontor.asset-depreciation/depreciable-base base
                             :kontor.asset-depreciation/commodity commodity*
                             :kontor.asset-depreciation/start-date start
                             :kontor.asset-depreciation/schedule sched-tempid}
                      opening-accumulated
                      (assoc :kontor.asset-depreciation/opening-accumulated opening-accumulated)
                      mparams-entity (assoc :kontor.asset-depreciation/method-params mparams-tempid)
                      (and method-params (not (map? method-params)))
                      (assoc :kontor.asset-depreciation/method-params method-params)
                      effective-rule (assoc :kontor.asset-depreciation/effective-rule effective-rule)
                      expense-account (assoc :kontor.asset-depreciation/expense-account
                                             expense-account)
                      note           (assoc :kontor.asset-depreciation/note note))]
    (cond-> [book-entity schedule-entity]
      mparams-entity (conj mparams-entity))))

(defn open-book!
  "Create an `:asset-depreciation` book for an (asset, ledger) pair —
   plus its ADR-032 `:schedule` and its optional `:asset-method-params`
   — in one tx. Returns the tx-report.

   The `:kontor.asset-depreciation/identity` tuple (`:db.unique/identity` on
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
                          :kontor.asset/expense-account (ADR-063 — a ROU
                          asset debits a different P&L account per
                          ledger). Absent ⇒ the asset's account.
     :schedule-code       string (default = \"<asset-code>-dep-<ledger-code>\")
     :note                string

   The pure tx-data builder is `open-book-tx-data` (ADR-067)."
  [conn opts]
  (validation/transact-with-validation
   conn (open-book-tx-data (d/db conn) opts)))

;; ============================================================================
;; revise-book! — the explicit "supersede the pending tail" operation (ADR-055)
;; ============================================================================

(defn revise-book-tx-data
  "Pure tx-data builder for `revise-book!` — the entity-map
   construction without the `d/transact` wrapper. Use as a
   `kontor.workflow.process` step (ADR-067); `revise-book!` is the standalone
   wrapper. See `revise-book!` for the opts."
  [db {:keys [book new-useful-life-months additional-base note]}]
  (when-not (or new-useful-life-months additional-base)
    (throw (ex-info "revise-book!: :new-useful-life-months or :additional-base required" {})))
  (let [eid (resolve-book db book)
        _ (when-not eid (throw (ex-info "Depreciation book not found" {:spec book})))
        b (d/pull db [:kontor.asset-depreciation/useful-life-months
                      :kontor.asset-depreciation/depreciable-base
                      :kontor.asset-depreciation/start-date
                      {:kontor.asset-depreciation/schedule [:db/id :kontor.schedule/frequency]}]
                  eid)
        sched (:kontor.asset-depreciation/schedule b)
        sched-eid (:db/id sched)
        freq (:kontor.schedule/frequency sched)
        life (or new-useful-life-months (:kontor.asset-depreciation/useful-life-months b))
        base (cond-> (:kontor.asset-depreciation/depreciable-base b)
               additional-base
               (#(.add ^java.math.BigDecimal % ^java.math.BigDecimal additional-base)))
        n-periods (periods-for life freq)
        fired (count (schedule/fired-sequences db sched-eid))
        _ (when (< n-periods fired)
            (throw (ex-info "revise-book!: revised useful life implies fewer periods than already fired"
                            {:type :kontor.asset/revision-below-fired
                             :revised-periods n-periods :fired fired})))
        end-date (schedule/date-of-occurrence (:kontor.asset-depreciation/start-date b)
                                              freq n-periods)]
    [(cond-> {:db/id eid
              :kontor.asset-depreciation/useful-life-months life
              :kontor.asset-depreciation/depreciable-base base}
       note (assoc :kontor.asset-depreciation/note note))
     {:db/id sched-eid
      :kontor.schedule/end-date end-date
      :kontor.schedule/total-amount base}]))

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
  (validation/transact-with-validation
   conn (revise-book-tx-data (d/db conn) opts)))

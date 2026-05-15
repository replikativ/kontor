(ns kontor.lease.runner
  "kontor-lease commencement + period runner — ADR-063.

   `commence!` is the balance-sheet recognition transactor: it turns a
   `:draft` `:lease` into an `:active` one by creating the single
   Right-of-Use `:asset`, opening one `:lease-liability` book + one
   ROU `:asset-depreciation` book per ledger, posting the day-one
   recognition entry per book, and driving `:lease/status :draft →
   :active`.

   `run-lease!` is the period close for one (lease, ledger): it fires
   the liability schedule's due payment occurrences (`Dr interest +
   Dr liability / Cr cash`) and runs the sibling ROU depreciation
   book through `kontor.asset.runner/run-depreciation!`.

   The whole point of kontor-lease is that this is THIN: the ROU
   asset is an `:asset`, its books are `:asset-depreciation`, the GL
   shape is `kontor.posting/build-transaction` — the only lease-
   specific machinery is the `LeaseProvider` unwind and the
   operating-lease ROU plug.

   Like the kontor-asset runner, this ships the runner *functions*,
   NOT a scheduler — *who* calls them (a close-period step, a cron) is
   the consumer's concern (ADR-032). `commence!` / `run-lease!` each
   do several `d/transact`s (acquire → open books → post per book);
   they are NOT one atomic tx — consistent with the kontor-asset
   runner."
  (:require [datahike.api :as d]
            [kontor.asset.asset :as asset]
            [kontor.asset.depreciation :as asset-dep]
            [kontor.asset.runner :as asset-runner]
            [kontor.bitemporal :as kbt]
            [kontor.lease.core :as lease]
            [kontor.lease.liability :as liability]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.posting :as lposting]
            [kontor.lease.rou-provider :as rou]
            [kontor.process :as process]
            [kontor.schedule :as schedule]
            [kontor.status-machine :as sm])
  (:import [java.math BigDecimal RoundingMode]
           [java.util Date]))

;; ============================================================================
;; commence!
;; ============================================================================

(defn- bd ^BigDecimal [x] (or x 0M))

(defn commence!
  "Balance-sheet recognition of a `:draft` `:lease` (ADR-063).

   Creates the single Right-of-Use `:asset`; for each ledger in
   `:books` opens a `:lease-liability` book + a ROU
   `:asset-depreciation` book and posts the day-one recognition
   entry `Dr ROU-asset / Cr lease-liability [/ Cr cash]`; sets
   `:lease/rou-asset`; drives `:lease/status :draft → :active`.

   The liability PV is `present-value` of the lease payments at the
   book's discount rate; the ROU asset's cost is `PV + initial-direct-
   costs + prepaid − incentives`. Because PV is per-book (rates can
   differ), each book carries its own `:depreciable-base` / `:opening-
   liability` — the parallel-ledger shape (ADR-021).

   A `:finance` book's ROU `:asset-depreciation` book uses the
   kontor-asset `:straight-line` provider; an `:operating` book uses
   `:lease-rou-plug` (`kontor.lease.rou-provider`) and routes BOTH the
   interest leg and the ROU charge to the single lease-expense
   account — so the P&L shows one straight-line lease-cost line.

   Required opts:
     :lease                   code or eid — must be `:draft`
     :journal                 journal ref for the recognition entries
     :changed-by-uid          ref to `:create/uid` — drives the status
                              change (`:requires-supporting-doc` is
                              met by the lease's `:origin-document`)
     :rou-asset-account       eid — the ROU asset's BS account
     :rou-accumulated-account eid — the ROU accumulated-amortisation
                              account
     :books                   non-empty vector of per-ledger specs:
       {:ledger                eid                       (required)
        :classification        :finance | :operating     (required)
        :liability-account     eid                       (required)
        :interest-account      eid — finance: interest-expense;
                               operating: the single lease-expense
                               account                   (required)
        :rou-expense-account   eid — finance: depreciation-expense;
                               operating: the same lease-expense
                               account                   (required)
        :discount-rate         bigdec (default = `:lease/discount-rate`)
        :liability-provider-id keyword (default :effective-interest)}

   Optional opts:
     :cash-account     eid — credit side of the day-one entry;
                       required iff IDC + prepaid − incentives ≠ 0
     :rou-asset-code   string (default \"<lease-code>-ROU\")
     :as-of            instant valid-time (default = commencement-date)

   Returns {:lease eid :rou-asset eid :books [{:ledger :liability-book
   :rou-dep-book :pv :rou-cost} …]}.

   Implemented as a `kontor.process` (ADR-067): the ROU `:asset`, the
   per-ledger `:lease-liability` + ROU `:asset-depreciation` books +
   their schedules, the day-one recognition entries and the
   `:draft → :active` status change all commit as ONE atomic, gated
   transaction. The ROU asset threads to its dependent books by the
   `\"rou-asset\"` string tempid; the per-ledger builders take a
   `:tempid-suffix` so N books compose without collision. The old
   hand-call to `period/assert-not-in-locked-period!` is gone — the
   process commits through `transact-with-validation`, so the period
   / sealing / sum-to-zero / invariant gate covers the whole tx."
  [conn {:keys [lease journal changed-by-uid rou-asset-account
                rou-accumulated-account books cash-account rou-asset-code
                as-of]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not rou-asset-account (throw (ex-info ":rou-asset-account required" {})))
  (when-not rou-accumulated-account
    (throw (ex-info ":rou-accumulated-account required" {})))
  (when-not (seq books)    (throw (ex-info ":books must be a non-empty vector" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:lease/code :lease/name :lease/status
                      :lease/commencement-date :lease/term-months
                      :lease/payment-amount :lease/payment-frequency
                      :lease/payment-timing :lease/discount-rate
                      :lease/initial-direct-costs :lease/prepaid-at-commencement
                      :lease/incentives-received :lease/purchase-option-price
                      {:lease/asset-class [:db/id]}
                      {:lease/commodity [:db/id]}
                      {:lease/origin-document [:db/id]}]
                  lease-eid)
        _ (when-not (= :draft (:lease/status l))
            (throw (ex-info "commence!: lease is not :draft — already commenced?"
                            {:type :lease/not-draft
                             :lease lease-eid :status (:lease/status l)})))
        _ (when-not (:lease/origin-document l)
            (throw (ex-info "commence!: lease has no :origin-document — the signed contract is required for :draft → :active"
                            {:type :lease/missing-origin-document :lease lease-eid})))
        commencement (:lease/commencement-date l)
        freq         (:lease/payment-frequency l)
        timing       (:lease/payment-timing l)
        n-periods    (lease/periods-for (:lease/term-months l) freq)
        ppy          (lease/periods-per-year freq)
        commodity    (:db/id (:lease/commodity l))
        payment      (:lease/payment-amount l)
        idc          (bd (:lease/initial-direct-costs l))
        prepaid      (bd (:lease/prepaid-at-commencement l))
        incentives   (bd (:lease/incentives-received l))
        net-cash     (-> idc (.add prepaid) (.subtract incentives))
        purchase-opt (:lease/purchase-option-price l)
        ;; Both the liability schedule and the ROU depreciation
        ;; schedule start on the SAME date so occurrence `k` of each
        ;; lines up — :in-advance pays at commencement, :in-arrears at
        ;; the first period-end.
        sched-start  (if (= timing :in-advance)
                       commencement
                       (schedule/date-of-occurrence commencement freq 2))
        ;; Per-book PV + ROU cost.
        book-calcs
        (mapv (fn [{:keys [discount-rate] :as bk}]
                (let [rate (or discount-rate (:lease/discount-rate l))
                      period-rate (.divide ^BigDecimal rate
                                           (BigDecimal/valueOf (long ppy))
                                           12 RoundingMode/HALF_EVEN)
                      pv (lease/present-value payment period-rate n-periods timing
                                              {:final-value purchase-opt})
                      rou-cost (.add ^BigDecimal pv net-cash)]
                  (assoc bk :rate rate :pv pv :rou-cost rou-cost)))
              books)
        rou-code (or rou-asset-code (str (:lease/code l) "-ROU"))
        ;; Ledger codes for the per-book schedule-code default — pulled
        ;; up-front because `open-book-tx-data` in :asset-tempid mode
        ;; does not pull the (not-yet-committed) asset.
        ledger-codes (into {} (map (fn [bk]
                                     [(:ledger bk)
                                      (:ledger/code
                                       (d/pull db [:ledger/code] (:ledger bk)))]))
                           books)
        ;; The :asset's single :acquisition-cost — the first (usually
        ;; primary) book's ROU cost; each :asset-depreciation book
        ;; carries its OWN :depreciable-base, so when discount rates
        ;; differ this scalar matches only the primary book. Nothing in
        ;; kontor-lease reads it (the providers + the modification
        ;; snapshot all read the per-book :depreciable-base) — but a
        ;; consumer disposing a non-primary ROU book via
        ;; `kontor.asset.asset/dispose!` must pass an explicit
        ;; `:asset-account-cost`, since `plan-disposal` /
        ;; `net-book-value` default to this asset-level figure.
        primary-rou-cost (:rou-cost (first book-calcs))
        rou-tempid "rou-asset"
        ;; STEP 1 — the Right-of-Use :asset (threads by `rou-tempid`).
        recognize-rou
        (fn [sdb _ctx]
          (asset/acquire-tx-data
           sdb {:tempid rou-tempid
                :code rou-code
                :name (str "ROU — " (:lease/name l))
                :class (:db/id (:lease/asset-class l))
                :acquisition-cost primary-rou-cost
                :acquisition-commodity commodity
                :acquisition-date commencement
                :in-service? true
                :in-service-date commencement
                :salvage-value 0M
                :asset-account rou-asset-account
                :accumulated-account rou-accumulated-account
                :expense-account (:rou-expense-account (first books))
                :origin-document (:db/id (:lease/origin-document l))
                :changed-by-uid changed-by-uid}))
        ;; STEPS 2..N+1 — one per ledger: the :lease-liability book + its
        ;; schedule, the ROU :asset-depreciation book + its schedule, and
        ;; the day-one recognition entry. `:tempid-suffix`/`:tx-tempid`
        ;; keep the N books' tempids distinct in the one tx-data.
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [ledger classification liability-account interest-account
                         rou-expense-account liability-provider-id rate pv rou-cost]}]
            (when-not ledger (throw (ex-info "book spec: :ledger required" {})))
            (when-not (#{:finance :operating} classification)
              (throw (ex-info "book spec: :classification must be :finance | :operating"
                              {:classification classification})))
            (when-not liability-account
              (throw (ex-info "book spec: :liability-account required" {})))
            (when-not interest-account
              (throw (ex-info "book spec: :interest-account required" {})))
            (when-not rou-expense-account
              (throw (ex-info "book spec: :rou-expense-account required" {})))
            (let [suffix (str "-" i)]
              (fn [sdb _ctx]
                (-> []
                    (into (liability/open-liability-book-tx-data
                           sdb (cond-> {:lease lease-eid
                                        :ledger ledger
                                        :classification classification
                                        :opening-liability pv
                                        :discount-rate rate
                                        :liability-account liability-account
                                        :interest-account interest-account
                                        :commodity commodity
                                        :start-date sched-start
                                        :n-periods n-periods
                                        :frequency freq
                                        :tempid-suffix suffix}
                                 liability-provider-id
                                 (assoc :provider-id liability-provider-id))))
                    (into (asset-dep/open-book-tx-data
                           sdb {:asset-tempid rou-tempid
                                :ledger ledger
                                :provider-id (if (= classification :operating)
                                               :lease-rou-plug
                                               :straight-line)
                                :useful-life-months (:lease/term-months l)
                                :depreciable-base rou-cost
                                :commodity commodity
                                :start-date sched-start
                                :frequency freq
                                :expense-account rou-expense-account
                                :schedule-code (str rou-code "-dep-"
                                                    (or (ledger-codes ledger) ledger))
                                :tempid-suffix suffix}))
                    (into (lposting/plan-lease-recognition
                           (cond-> {:rou-asset-account rou-asset-account
                                    :liability-account liability-account
                                    :rou-cost rou-cost
                                    :pv pv
                                    :net-cash net-cash
                                    :commodity commodity
                                    :ledger ledger
                                    :journal journal
                                    :date commencement
                                    :posted-at commencement
                                    :tx-tempid (str "lease-recog" suffix)
                                    :narration (str "Lease recognition — "
                                                    (:lease/code l))}
                             cash-account (assoc :cash-account cash-account))))))))
          book-calcs))
        ;; FINAL STEP — link the ROU asset + drive :draft → :active.
        link-and-activate
        (fn [sdb _ctx]
          (into [{:db/id lease-eid :lease/rou-asset rou-tempid}]
                (sm/record-status-change-tx-data
                 sdb {:entity lease-eid
                      :entity-type :lease
                      :facet :lease/status
                      :from :draft :to :active
                      :changed-at (Date.)
                      :changed-by-uid changed-by-uid
                      :supporting-doc (:db/id (:lease/origin-document l))
                      :reason :lease-commenced})))
        report (process/run-process
                conn {:steps (concat [recognize-rou] book-steps [link-and-activate])
                      :vt-from (or as-of commencement)
                      :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :rou-asset (get tempids rou-tempid)
     :books (mapv (fn [i {:keys [ledger pv rou-cost]}]
                    {:ledger ledger
                     :liability-book (get tempids (str "lease-liab-book-" i))
                     :rou-dep-book (get tempids (str "asset-dep-book-" i))
                     :pv pv
                     :rou-cost rou-cost})
                  (range)
                  book-calcs)}))

;; ============================================================================
;; run-lease!
;; ============================================================================

(defn run-lease!
  "Period close for one (lease, ledger): fire the liability schedule's
   due payment occurrences and run the sibling ROU depreciation book.

   For each pending liability occurrence ≤ `:as-of`, post `Dr interest
   + Dr lease-liability(principal) / Cr cash` and log the occurrence
   (amount = the cash payment); then call
   `kontor.asset.runner/run-depreciation!` on the ROU
   `:asset-depreciation` book — with the `:lease-rou-plug` provider
   for an `:operating` book, the kontor-asset built-in for a
   `:finance` book.

   When the liability schedule becomes fully fired and `:mark-expired?`
   is true (default), drives `:lease/status :active → :expired`.

   Required opts:
     :lease           code or eid
     :ledger          eid
     :journal         journal ref for the GL entries
     :cash-account    eid — the credit side of each payment

   Optional opts:
     :as-of           instant — fire occurrences due ≤ this (default now)
     :posted?         seal the entries (default true)
     :changed-by-uid  attribute the :expired transition
     :mark-expired?   default true

   Returns {:lease :ledger :liability {…} :rou {…} :completed?}.

   Each charge is checked against `kontor.period` — firing into a
   soft-closed / sealed period throws `:period/locked-period-
   violation`, carrying the partial progress."
  [conn {:keys [lease ledger journal cash-account as-of posted?
                changed-by-uid mark-expired?]
         :or {posted? true mark-expired? true}}]
  (when-not lease        (throw (ex-info ":lease required" {})))
  (when-not ledger       (throw (ex-info ":ledger required" {})))
  (when-not journal      (throw (ex-info ":journal required" {})))
  (when-not cash-account (throw (ex-info ":cash-account required" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        liab-book (liability/book-for db lease-eid ledger)
        _ (when-not liab-book
            (throw (ex-info "run-lease!: no :lease-liability book for this (lease, ledger) — commence the lease first"
                            {:type :lease/no-liability-book
                             :lease lease-eid :ledger ledger})))
        inputs (liability/book-plan-inputs db liab-book)
        schedule-eid (:schedule inputs)
        commodity (:commodity inputs)
        classification (:classification inputs)
        b (d/pull db [{:lease-liability/liability-account [:db/id]}
                      {:lease-liability/interest-account [:db/id]}]
                  liab-book)
        liability-account (:db/id (:lease-liability/liability-account b))
        interest-account  (:db/id (:lease-liability/interest-account b))
        ;; The sibling ROU depreciation book — resolved up-front so the
        ;; lockstep invariant can be checked before anything fires.
        rou-asset (:db/id (:lease/rou-asset
                           (d/pull db [{:lease/rou-asset [:db/id]}] lease-eid)))
        _ (when-not rou-asset
            (throw (ex-info "run-lease!: lease has no :rou-asset — not commenced?"
                            {:type :lease/not-commenced :lease lease-eid})))
        rou-dep-book (asset-dep/book-for db rou-asset ledger)
        _ (when-not rou-dep-book
            (throw (ex-info "run-lease!: no ROU :asset-depreciation book for this (lease, ledger) — commence the lease first"
                            {:type :lease/no-rou-dep-book
                             :lease lease-eid :ledger ledger})))
        rou-dep-schedule (:db/id (:asset-depreciation/schedule
                                  (d/pull db [{:asset-depreciation/schedule
                                               [:db/id]}]
                                          rou-dep-book)))
        ;; Lockstep invariant: run-lease! fires the liability + the ROU
        ;; schedules together, and the operating-lease ROU plug relies
        ;; on the un-fired ROU periods matching the liability plan. If a
        ;; prior partial failure left the two diverged, refuse to run.
        _ (let [liab-fired (count (schedule/fired-sequences db schedule-eid))
                rou-fired  (count (schedule/fired-sequences db rou-dep-schedule))]
            (when-not (= liab-fired rou-fired)
              (throw (ex-info "run-lease!: the liability schedule and the ROU depreciation schedule have diverged — they must be fired in lockstep (a prior run likely failed partway). Reconcile before running."
                              {:type :lease/lockstep-divergence
                               :lease lease-eid :ledger ledger
                               :liability-fired liab-fired :rou-fired rou-fired}))))
        plan (lp/plan-for-book db liab-book)
        seq->period (into {} (map (juxt :sequence identity)) (:periods plan))
        pending (sort-by :sequence
                         (schedule/pending-occurrences
                          db schedule-eid (or as-of (Date.))))
        fired
        (reduce
         (fn [acc {:keys [sequence date]}]
           (let [{:keys [interest principal payment]} (seq->period sequence)]
             (when (nil? payment)
               (throw (ex-info "run-lease!: liability plan has no period for a pending occurrence"
                               {:book liab-book :sequence sequence})))
             ;; ONE atomic, gated process per period — each its own
             ;; datahike-tx with :tx/valid-from = the payment date
             ;; (ADR-067 addendum). Period / sealing / sum-to-zero /
             ;; invariants run in the gate. The lockstep guard above
             ;; stays — cross-half (payment ↔ ROU dep) atomicity is
             ;; not structural under per-period vt, so a partial run
             ;; can still diverge the two schedules.
             (let [period-step
                   (fn [sdb _ctx]
                     (let [tx-data (lposting/plan-lease-payment
                                    (cond-> {:interest-account interest-account
                                             :liability-account liability-account
                                             :cash-account cash-account
                                             :interest interest
                                             :principal principal
                                             :payment payment
                                             :commodity commodity
                                             :ledger ledger
                                             :journal journal
                                             :date date
                                             :narration (str "Lease payment "
                                                             sequence)}
                                      posted? (assoc :posted-at date)))]
                       (schedule/record-occurrence-tx-data
                        sdb schedule-eid sequence date payment commodity
                        tx-data (Date.))))]
               (try
                 (process/run-process
                  conn {:steps [period-step]
                        :vt-from date
                        :vt-to kbt/forever})
                 (catch clojure.lang.ExceptionInfo e
                   (let [data (ex-data e)]
                     (if (= :period/locked-period-violation (:type data))
                       (throw (ex-info (.getMessage e)
                                       (assoc data
                                              :book liab-book
                                              :sequence sequence
                                              :fired-before-violation
                                              (mapv :sequence acc))
                                       e))
                       (throw e))))))
             (conj acc {:sequence sequence :date date
                        :interest interest :principal principal
                        :payment payment})))
         []
         pending)
        total-interest  (reduce (fn [^BigDecimal a m] (.add a ^BigDecimal (:interest m)))
                                0M fired)
        total-principal (reduce (fn [^BigDecimal a m] (.add a ^BigDecimal (:principal m)))
                                0M fired)
        ;; Run the sibling ROU depreciation book — with the
        ;; :lease-rou-plug provider for an operating book, the
        ;; kontor-asset built-in for a finance book.
        rou-result (asset-runner/run-depreciation!
                    conn rou-dep-book
                    (cond-> {:journal journal
                             :posted? posted?
                             :provider (when (= classification :operating)
                                         (rou/provider))
                             :mark-fully-depreciated? false}
                      as-of (assoc :as-of as-of)))
        db' (d/db conn)
        completed? (>= (count (schedule/fired-sequences db' schedule-eid))
                       (:n-periods inputs))]
    (when (and completed? mark-expired? (seq fired))
      (let [status (:lease/status (d/pull db' [:lease/status] lease-eid))]
        (when (= :active status)
          (let [last-date (:date (last fired))
                status-step
                (fn [sdb _ctx]
                  (sm/record-status-change-tx-data
                   sdb (cond-> {:entity lease-eid
                                :entity-type :lease
                                :facet :lease/status
                                :from :active :to :expired
                                :changed-at last-date
                                :reason :lease-expired}
                         changed-by-uid (assoc :changed-by-uid changed-by-uid))))]
            (process/run-process
             conn {:steps [status-step]
                   :vt-from last-date
                   :vt-to kbt/forever})))))
    {:lease lease-eid
     :ledger ledger
     :liability {:book liab-book
                 :fired (mapv :sequence fired)
                 :count (count fired)
                 :total-interest total-interest
                 :total-principal total-principal}
     :rou rou-result
     :completed? completed?}))

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
            [kontor.period :as period]
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
   :rou-dep-book :pv :rou-cost} …]}."
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
        ;; The :asset's single :acquisition-cost — the first (usually
        ;; primary) book's ROU cost; each :asset-depreciation book
        ;; carries its own :depreciable-base.
        primary-rou-cost (:rou-cost (first book-calcs))]
    ;; 1. The Right-of-Use :asset.
    (asset/acquire! conn
                    {:code rou-code
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
                     :changed-by-uid changed-by-uid
                     :vt-from (or as-of commencement)})
    (let [rou-asset-eid (asset/by-code (d/db conn) rou-code)
          book-results
          (mapv
           (fn [{:keys [ledger classification liability-account interest-account
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
             ;; 2. The :lease-liability book + its schedule.
             (liability/open-liability-book!
              conn (cond-> {:lease lease-eid
                            :ledger ledger
                            :classification classification
                            :opening-liability pv
                            :discount-rate rate
                            :liability-account liability-account
                            :interest-account interest-account
                            :commodity commodity
                            :start-date sched-start
                            :n-periods n-periods
                            :frequency freq}
                     liability-provider-id
                     (assoc :provider-id liability-provider-id)))
             ;; 3. The ROU :asset-depreciation book + its schedule.
             (asset-dep/open-book!
              conn {:asset rou-asset-eid
                    :ledger ledger
                    :provider-id (if (= classification :operating)
                                   :lease-rou-plug
                                   :straight-line)
                    :useful-life-months (:lease/term-months l)
                    :depreciable-base rou-cost
                    :commodity commodity
                    :start-date sched-start
                    :frequency freq
                    :expense-account rou-expense-account})
             ;; 4. The day-one recognition entry for this book.
             (d/transact
              conn (lposting/plan-lease-recognition
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
                             :narration (str "Lease recognition — "
                                              (:lease/code l))}
                      cash-account (assoc :cash-account cash-account))))
             {:ledger ledger
              :liability-book (liability/book-for (d/db conn) lease-eid ledger)
              :rou-dep-book (asset-dep/book-for (d/db conn) rou-asset-eid ledger)
              :pv pv
              :rou-cost rou-cost})
           book-calcs)
          ;; 5. Link the ROU asset + drive :draft → :active.
          db' (d/db conn)
          status-tx (sm/record-status-change-tx-data
                     db' {:entity lease-eid
                          :entity-type :lease
                          :facet :lease/status
                          :from :draft :to :active
                          :changed-at (Date.)
                          :changed-by-uid changed-by-uid
                          :supporting-doc (:db/id (:lease/origin-document l))
                          :reason :lease-commenced})]
      (d/transact conn (kbt/with-vt
                         (into [{:db/id lease-eid :lease/rou-asset rou-asset-eid}]
                               status-tx)
                         (or as-of commencement) kbt/forever))
      {:lease lease-eid
       :rou-asset rou-asset-eid
       :books book-results})))

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
                                     :narration (str "Lease payment " sequence)}
                              posted? (assoc :posted-at date)))]
               (try
                 (period/assert-not-in-locked-period! db tx-data)
                 (catch clojure.lang.ExceptionInfo e
                   (throw (ex-info (.getMessage e)
                                   (assoc (ex-data e)
                                          :book liab-book
                                          :sequence sequence
                                          :fired-before-violation (mapv :sequence acc))
                                   e))))
               (schedule/record-occurrence! conn schedule-eid sequence
                                            date payment commodity tx-data)
               (conj acc {:sequence sequence :date date
                          :interest interest :principal principal
                          :payment payment}))))
         []
         pending)
        total-interest  (reduce (fn [^BigDecimal a m] (.add a ^BigDecimal (:interest m)))
                                0M fired)
        total-principal (reduce (fn [^BigDecimal a m] (.add a ^BigDecimal (:principal m)))
                                0M fired)
        ;; The sibling ROU depreciation book.
        rou-asset (:db/id (:lease/rou-asset
                           (d/pull (d/db conn) [{:lease/rou-asset [:db/id]}]
                                   lease-eid)))
        rou-dep-book (when rou-asset
                       (asset-dep/book-for (d/db conn) rou-asset ledger))
        rou-result (when rou-dep-book
                     (asset-runner/run-depreciation!
                      conn rou-dep-book
                      (cond-> {:journal journal
                               :posted? posted?
                               :provider (when (= classification :operating)
                                           (rou/provider))
                               :mark-fully-depreciated? false}
                        as-of (assoc :as-of as-of))))
        db' (d/db conn)
        completed? (>= (count (schedule/fired-sequences db' schedule-eid))
                       (:n-periods inputs))]
    (when (and completed? mark-expired? (seq fired))
      (let [status (:lease/status (d/pull db' [:lease/status] lease-eid))]
        (when (= :active status)
          (let [last-date (:date (last fired))
                status-tx (sm/record-status-change-tx-data
                           db'
                           (cond-> {:entity lease-eid
                                    :entity-type :lease
                                    :facet :lease/status
                                    :from :active :to :expired
                                    :changed-at last-date
                                    :reason :lease-expired}
                             changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
            (d/transact conn (kbt/with-vt status-tx last-date kbt/forever))))))
    {:lease lease-eid
     :ledger ledger
     :liability {:book liab-book
                 :fired (mapv :sequence fired)
                 :count (count fired)
                 :total-interest total-interest
                 :total-principal total-principal}
     :rou rou-result
     :completed? completed?}))

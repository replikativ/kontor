(ns kontor.lease.modification
  "kontor-lease modifications, remeasurements + terminations — ADR-064.

   A modification is an append-only `:lease-modification` event PLUS a
   re-measure-and-adjust of every `:lease-liability` book. The four
   transactors:

   - `remeasure!` — an index reset, a term change, a rate reset, or a
     general reassessment of the payments (IFRS 16.39-43 / ASC 842).
     The liability is remeasured at the PV of the revised remaining
     payments; the difference adjusts the ROU `:asset` (and, only when
     the adjustment would drive the ROU below zero, P&L — IFRS 16.39).
   - `partial-terminate!` — a scope decrease. The proportional
     approach (IFRS 16.46(b)): the liability and the ROU are reduced
     in proportion to the right-of-use given up, the difference is a
     P&L gain/loss, and the remaining liability is then remeasured for
     the revised payments.
   - `terminate!` — full early termination. The liability and the ROU
     are derecognised, any termination penalty is paid, the difference
     is a P&L gain/loss, both schedules are cancelled, and
     `:lease/status` is driven `:active → :terminated`.
   - `purchase!` — a purchase option is exercised. The remaining
     liability is settled in cash, both schedules are cancelled, and
     `:lease/status` is driven `:active → :purchased`. The ROU
     `:asset` CONTINUES as an owned asset (IFRS 16.67 — no
     derecognition); the consumer opens a fresh `:asset-depreciation`
     book over its owned-asset useful life.

   ## The re-anchor mechanism

   A modification never restates fired periods. It re-anchors each
   `:lease-liability` book — `liability/revise-liability-book!` sets
   the new `:opening-liability` and advances `:opening-fired-through`
   to the fired count — so the `EffectiveInterestProvider` re-plans
   only the un-fired tail from the remeasured balance. The ROU
   `:asset-depreciation` book is re-anchored by
   `kontor.asset.depreciation/revise-book!` (the same prospective
   re-plan kontor-asset already does for an IAS 16 useful-life
   revision).

   ## v1 simplification — the remeasurement PV

   `remeasure!` discounts the revised *remaining* payments as an
   ordinary annuity (in-arrears) from the modification date. The
   post-modification unwind always treats the first un-fired period
   as accruing interest, so this is self-consistent; for an
   originally-`:in-advance` lease modified mid-term there is a minor
   sub-period timing approximation (the precise day-count is a
   consumer-level refinement, as the discount rate itself is).

   ## FX

   FX retranslation is `posting/plan-fx-retranslation` — a thin
   builder, not a transactor: the lease liability is a monetary item
   (retranslated at the closing rate), the ROU asset is non-monetary
   (frozen at the historical rate). kontor ships no FX-rate engine;
   the closing rate is a consumer input."
  (:require [datahike.api :as d]
            [kontor.asset.depreciation :as asset-dep]
            [kontor.bitemporal :as kbt]
            [kontor.lease.core :as lease]
            [kontor.lease.lease-provider :as lp]
            [kontor.lease.liability :as liability]
            [kontor.lease.posting :as lposting]
            [kontor.schedule :as schedule]
            [kontor.status-machine :as sm])
  (:import [java.math BigDecimal RoundingMode]))

(defn- bd ^BigDecimal [x] (or x 0M))
(defn- bd- ^BigDecimal [^BigDecimal a ^BigDecimal b] (.subtract a b))
(defn- bd+ ^BigDecimal [^BigDecimal a ^BigDecimal b] (.add a b))
(defn- round2 ^BigDecimal [^BigDecimal x] (.setScale x 2 RoundingMode/HALF_EVEN))

;; ============================================================================
;; Pre-modification snapshot
;; ============================================================================

(defn- pre-mod-snapshot
  "For every `:lease-liability` book of `lease-eid`, gather what a
   modification needs BEFORE the `:lease` contract facts change — the
   old outstanding liability and the ROU carrying amount are computed
   against the *pre-modification* terms. Returns a vector of maps."
  [db lease-eid]
  (let [rou-asset (:db/id (:lease/rou-asset
                           (d/pull db [{:lease/rou-asset [:db/id]}] lease-eid)))
        _ (when-not rou-asset
            (throw (ex-info "Lease has no :rou-asset — not commenced?"
                            {:type :lease/not-commenced :lease lease-eid})))
        rou-asset-account (:db/id (:asset/asset-account
                                   (d/pull db [{:asset/asset-account [:db/id]}]
                                           rou-asset)))]
    (mapv (fn [lb]
            (let [pb (liability/pull-book db lb)
                  ledger (:db/id (:lease-liability/ledger pb))
                  rou-dep-book (asset-dep/book-for db rou-asset ledger)
                  rou-base (:asset-depreciation/depreciable-base
                            (d/pull db [:asset-depreciation/depreciable-base]
                                    rou-dep-book))
                  accumulated (asset-dep/accumulated-depreciation db rou-dep-book)]
              {:liability-book    lb
               :ledger            ledger
               :commodity         (:db/id (:lease-liability/commodity pb))
               :liability-account (:db/id (:lease-liability/liability-account pb))
               :liability-schedule (:db/id (:lease-liability/schedule pb))
               :rou-asset         rou-asset
               :rou-asset-account rou-asset-account
               :rou-dep-book      rou-dep-book
               :rou-dep-schedule  (:db/id (:asset-depreciation/schedule
                                           (d/pull db [{:asset-depreciation/schedule
                                                        [:db/id]}]
                                                   rou-dep-book)))
               :old-outstanding   (lp/outstanding-liability db lb)
               :rou-carrying      (bd- rou-base accumulated)}))
          (liability/books-of db lease-eid))))

;; ============================================================================
;; Shared book-level adjustment
;; ============================================================================

(defn- apply-book-adjustment!
  "Post ONE book's modification adjustment + re-anchor it. `new-
   liability` is the book's remeasured liability; `rou-base-change`
   is the intended change to the ROU `:asset-depreciation` book's
   `:depreciable-base` (clamped here so the ROU carrying amount never
   goes below zero — the excess lands in P&L, IFRS 16.39).

   The single GL entry's legs: the lease-liability account moves from
   `old-outstanding` to `new-liability`; the ROU-asset account moves
   by the clamped `rou-base-change`; P&L absorbs the remainder.
   Returns the adjustment transaction's eid."
  [conn {:keys [snapshot new-liability rou-base-change new-discount-rate
                new-term-months gain-loss-account journal date kind note]}]
  (let [{:keys [liability-book liability-account rou-asset-account rou-dep-book
                rou-carrying old-outstanding commodity ledger]} snapshot
        rou-base-change* (let [floor (.negate ^BigDecimal rou-carrying)]
                           (if (neg? (.compareTo ^BigDecimal rou-base-change floor))
                             floor
                             rou-base-change))
        liability-leg (bd- old-outstanding new-liability)
        rou-leg       rou-base-change*
        pl-leg        (.negate (bd+ liability-leg rou-leg))
        _ (when (and (not (zero? (.signum pl-leg))) (not gain-loss-account))
            (throw (ex-info "modification: a P&L gain/loss leg is required but :gain-loss-account was not supplied"
                            {:type :lease/missing-gain-loss-account
                             :book liability-book :pl pl-leg})))
        legs (cond-> [{:account liability-account :amount liability-leg}
                      {:account rou-asset-account :amount rou-leg}]
               (not (zero? (.signum pl-leg)))
               (conj {:account gain-loss-account :amount pl-leg}))
        ;; build-transaction places the :transaction at tempid -1.
        tx-report (d/transact
                   conn (lposting/plan-adjustment
                         {:legs legs
                          :commodity commodity
                          :ledger ledger
                          :journal journal
                          :date date
                          :posted-at date
                          :narration (str "Lease " (name kind))}))
        tx-eid (get (:tempids tx-report) -1)]
    ;; Re-anchor the liability book + the ROU depreciation book.
    (liability/revise-liability-book!
     conn (cond-> {:book liability-book
                   :new-opening-liability new-liability}
            new-discount-rate (assoc :new-discount-rate new-discount-rate)
            note              (assoc :note note)))
    (when (or (not (zero? (.signum rou-base-change*))) new-term-months)
      (asset-dep/revise-book!
       conn (cond-> {:book rou-dep-book :additional-base rou-base-change*}
              new-term-months (assoc :new-useful-life-months new-term-months)
              note            (assoc :note note))))
    tx-eid))

;; ============================================================================
;; The :lease-modification event
;; ============================================================================

(defn- record-modification!
  "Transact the append-only `:lease-modification` event + the updated
   `:lease` contract facts, wrapped in `kbt/with-vt` from the
   modification date. Returns the event eid."
  [conn lease-eid {:keys [kind date new-payment-amount new-term-months
                          new-discount-rate scope-decrease-pct justification
                          note tx-eids]}]
  (let [event (cond-> {:db/id "lease-mod"
                       :lease-modification/lease lease-eid
                       :lease-modification/kind kind
                       :lease-modification/date date}
                new-payment-amount  (assoc :lease-modification/new-payment-amount
                                           new-payment-amount)
                new-term-months     (assoc :lease-modification/new-term-months
                                           new-term-months)
                new-discount-rate   (assoc :lease-modification/new-discount-rate
                                           new-discount-rate)
                scope-decrease-pct  (assoc :lease-modification/scope-decrease-pct
                                           scope-decrease-pct)
                justification       (assoc :lease-modification/justification
                                           justification)
                note                (assoc :lease-modification/note note)
                (seq tx-eids)       (assoc :lease-modification/transaction
                                           (vec tx-eids)))
        lease-update (cond-> {:db/id lease-eid}
                       new-payment-amount (assoc :lease/payment-amount
                                                 new-payment-amount)
                       new-term-months    (assoc :lease/term-months new-term-months)
                       new-discount-rate  (assoc :lease/discount-rate
                                                 new-discount-rate))
        report (d/transact conn (kbt/with-vt [event lease-update]
                                  date kbt/forever))]
    (get-in report [:tempids "lease-mod"])))

;; ============================================================================
;; remeasure!
;; ============================================================================

(defn- remaining-pv
  "PV of the revised remaining payments — `remaining-n` payments of
   `payment` at `rate`, discounted as an ordinary annuity from the
   modification date (the v1 simplification — see the ns docstring)."
  ^BigDecimal [^BigDecimal payment ^BigDecimal rate frequency ^long remaining-n]
  (let [ppy (lease/periods-per-year frequency)
        period-rate (.divide rate (BigDecimal/valueOf (long ppy))
                             12 RoundingMode/HALF_EVEN)]
    (lease/present-value payment period-rate remaining-n :in-arrears)))

(defn remeasure!
  "Apply a remeasurement to an `:active` lease — an index reset, a
   term change, a discount-rate reset, or a general reassessment
   (`:kind` ∈ #{:remeasurement :index-reset :term-change :rate-reset}).
   Records the `:lease-modification` event, updates the affected
   `:lease` contract facts, and for EACH `:lease-liability` book
   re-measures the liability at the PV of the revised remaining
   payments and adjusts the ROU `:asset`.

   Required opts: :lease, :date, :kind, :journal, :changed-by-uid
   At least one of: :new-payment-amount, :new-term-months,
                    :new-discount-rate
   Optional: :justification (ref :audit-doc), :note,
             :gain-loss-account (required only if a remeasurement
             would drive a ROU book below zero — IFRS 16.39)

   Returns {:lease eid :modification eid :books [{:ledger
   :liability-book :old-outstanding :new-liability :delta} …]}."
  [conn {:keys [lease date kind journal changed-by-uid new-payment-amount
                new-term-months new-discount-rate justification note
                gain-loss-account]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not (#{:remeasurement :index-reset :term-change :rate-reset} kind)
    (throw (ex-info ":kind must be :remeasurement | :index-reset | :term-change | :rate-reset"
                    {:kind kind})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not (or new-payment-amount new-term-months new-discount-rate)
    (throw (ex-info "remeasure!: at least one of :new-payment-amount / :new-term-months / :new-discount-rate required" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:lease/status :lease/payment-amount :lease/term-months
                      :lease/discount-rate :lease/payment-frequency]
                  lease-eid)
        _ (when-not (= :active (:lease/status l))
            (throw (ex-info "remeasure!: lease is not :active"
                            {:type :lease/not-active :lease lease-eid
                             :status (:lease/status l)})))
        freq    (:lease/payment-frequency l)
        payment (or new-payment-amount (:lease/payment-amount l))
        term    (or new-term-months (:lease/term-months l))
        rate    (or new-discount-rate (:lease/discount-rate l))
        n       (lease/periods-for term freq)
        ;; Snapshot the OLD outstandings before the :lease facts move.
        snapshot (pre-mod-snapshot db lease-eid)
        ;; Update the :lease contract facts + record the event shell.
        mod-eid (record-modification!
                 conn lease-eid {:kind kind :date date
                                 :new-payment-amount new-payment-amount
                                 :new-term-months new-term-months
                                 :new-discount-rate new-discount-rate
                                 :justification justification :note note})
        results
        (mapv
         (fn [{:keys [liability-book liability-schedule old-outstanding ledger]
               :as snap}]
           (let [ofthr (count (schedule/fired-sequences (d/db conn)
                                                        liability-schedule))
                 remaining-n (- n ofthr)
                 _ (when (<= remaining-n 0)
                     (throw (ex-info "remeasure!: revised term leaves no un-fired periods"
                                     {:type :lease/no-remaining-periods
                                      :book liability-book})))
                 new-liability (remaining-pv payment rate freq remaining-n)
                 delta (bd- new-liability old-outstanding)
                 tx-eid (apply-book-adjustment!
                         conn {:snapshot snap
                               :new-liability new-liability
                               :rou-base-change delta
                               :new-discount-rate new-discount-rate
                               :new-term-months new-term-months
                               :gain-loss-account gain-loss-account
                               :journal journal :date date :kind kind :note note})]
             {:ledger ledger :liability-book liability-book
              :old-outstanding old-outstanding :new-liability new-liability
              :delta delta :transaction tx-eid}))
         snapshot)]
    (when (seq (keep :transaction results))
      (d/transact conn [{:db/id mod-eid
                         :lease-modification/transaction
                         (vec (keep :transaction results))}]))
    {:lease lease-eid :modification mod-eid :books results}))

;; ============================================================================
;; partial-terminate!
;; ============================================================================

(defn partial-terminate!
  "Apply a partial termination (a scope decrease) to an `:active`
   lease — the proportional approach (IFRS 16.46(b)). For each
   `:lease-liability` book: the liability and the ROU asset are
   reduced in proportion to `:scope-decrease-pct`, the difference is
   a P&L gain/loss, and the remaining liability is then remeasured
   for the revised payments (`:new-payment-amount` / optionally
   `:new-term-months` / `:new-discount-rate`).

   Required opts: :lease, :date, :scope-decrease-pct (0 < pct < 1),
                  :new-payment-amount, :journal, :changed-by-uid,
                  :gain-loss-account
   Optional: :new-term-months, :new-discount-rate, :justification,
             :note

   Returns the same shape as `remeasure!`."
  [conn {:keys [lease date scope-decrease-pct new-payment-amount new-term-months
                new-discount-rate journal changed-by-uid justification note
                gain-loss-account]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not date           (throw (ex-info ":date required" {})))
  (when (or (nil? scope-decrease-pct)
            (not (pos? (.signum ^BigDecimal scope-decrease-pct)))
            (>= (.compareTo ^BigDecimal scope-decrease-pct 1M) 0))
    (throw (ex-info ":scope-decrease-pct must be a fraction strictly between 0 and 1"
                    {:scope-decrease-pct scope-decrease-pct})))
  (when (nil? new-payment-amount)
    (throw (ex-info "partial-terminate!: :new-payment-amount required (the reduced payment)" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not gain-loss-account
    (throw (ex-info ":gain-loss-account required (a partial termination always books a P&L gain/loss)" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:lease/status :lease/term-months :lease/discount-rate
                      :lease/payment-frequency]
                  lease-eid)
        _ (when-not (= :active (:lease/status l))
            (throw (ex-info "partial-terminate!: lease is not :active"
                            {:type :lease/not-active :lease lease-eid})))
        freq (:lease/payment-frequency l)
        term (or new-term-months (:lease/term-months l))
        rate (or new-discount-rate (:lease/discount-rate l))
        n    (lease/periods-for term freq)
        snapshot (pre-mod-snapshot db lease-eid)
        mod-eid (record-modification!
                 conn lease-eid {:kind :partial-termination :date date
                                 :new-payment-amount new-payment-amount
                                 :new-term-months new-term-months
                                 :new-discount-rate new-discount-rate
                                 :scope-decrease-pct scope-decrease-pct
                                 :justification justification :note note})
        results
        (mapv
         (fn [{:keys [liability-book liability-schedule old-outstanding
                      rou-carrying ledger] :as snap}]
           (let [ofthr (count (schedule/fired-sequences (d/db conn)
                                                        liability-schedule))
                 remaining-n (- n ofthr)
                 _ (when (<= remaining-n 0)
                     (throw (ex-info "partial-terminate!: revised term leaves no un-fired periods"
                                     {:type :lease/no-remaining-periods
                                      :book liability-book})))
                 ;; Step 1 — proportional reduction.
                 liab-reduction (round2 (.multiply ^BigDecimal old-outstanding
                                                   ^BigDecimal scope-decrease-pct))
                 rou-reduction  (round2 (.multiply ^BigDecimal rou-carrying
                                                   ^BigDecimal scope-decrease-pct))
                 ;; Step 2 — remeasure what remains.
                 new-liability (remaining-pv new-payment-amount rate freq remaining-n)
                 remeasure-delta (bd- new-liability
                                      (bd- old-outstanding liab-reduction))
                 ;; The total ROU base change = the proportional
                 ;; write-off + the remeasurement adjustment.
                 rou-base-change (bd+ (.negate rou-reduction) remeasure-delta)
                 tx-eid (apply-book-adjustment!
                         conn {:snapshot snap
                               :new-liability new-liability
                               :rou-base-change rou-base-change
                               :new-discount-rate new-discount-rate
                               :new-term-months new-term-months
                               :gain-loss-account gain-loss-account
                               :journal journal :date date
                               :kind :partial-termination :note note})]
             {:ledger ledger :liability-book liability-book
              :old-outstanding old-outstanding :new-liability new-liability
              :delta (bd- new-liability old-outstanding) :transaction tx-eid}))
         snapshot)]
    (when (seq (keep :transaction results))
      (d/transact conn [{:db/id mod-eid
                         :lease-modification/transaction
                         (vec (keep :transaction results))}]))
    {:lease lease-eid :modification mod-eid :books results}))

;; ============================================================================
;; terminate!
;; ============================================================================

(defn terminate!
  "Fully terminate an `:active` lease early. For each `:lease-
   liability` book, derecognise the liability and the ROU asset, pay
   any `:penalty`, book the difference to P&L, and cancel both
   schedules. Drives `:lease/status :active → :terminated` (ADR-038:
   `:requires-supporting-doc` + `:no-self-approval`).

   The ROU `:asset` entity's status is left untouched — kontor-lease
   terminates the LEASE accounting; disposing the ROU `:asset` from
   the fixed-asset register (if the consumer's process requires it)
   is a `kontor.asset.asset/dispose!` call.

   Required opts: :lease, :date, :journal, :changed-by-uid,
                  :justification (the termination agreement),
                  :gain-loss-account
   Optional: :penalty (bigdec — a termination penalty paid in cash),
             :cash-account (required iff :penalty > 0), :note

   Returns {:lease eid :modification eid :books [{:ledger
   :liability-book :derecognised-liability :derecognised-rou} …]}."
  [conn {:keys [lease date journal changed-by-uid justification gain-loss-account
                penalty cash-account note]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not justification
    (throw (ex-info ":justification required (the termination agreement)" {})))
  (when-not gain-loss-account
    (throw (ex-info ":gain-loss-account required" {})))
  (when (and penalty (pos? (.signum ^BigDecimal penalty)) (not cash-account))
    (throw (ex-info ":cash-account required when :penalty > 0" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:lease/status] lease-eid)
        from (:lease/status l)
        _ (when-not (= :active from)
            (throw (ex-info "terminate!: lease is not :active"
                            {:type :lease/not-active :lease lease-eid})))
        penalty* (bd penalty)
        snapshot (pre-mod-snapshot db lease-eid)
        mod-eid (record-modification!
                 conn lease-eid {:kind :termination :date date
                                 :justification justification :note note})
        results
        (mapv
         (fn [{:keys [liability-book liability-account rou-asset-account
                      rou-dep-book liability-schedule rou-dep-schedule
                      old-outstanding rou-carrying commodity ledger]}]
           (let [;; Dr liability (remove it) / Cr ROU (remove it)
                 ;; [/ Cr cash (penalty)] ± P&L (the balancing gain/loss).
                 legs (cond-> [{:account liability-account :amount old-outstanding}
                               {:account rou-asset-account
                                :amount (.negate ^BigDecimal rou-carrying)}]
                        (pos? (.signum penalty*))
                        (conj {:account cash-account
                               :amount (.negate penalty*)}))
                 balancing (.negate (reduce (fn [^BigDecimal a leg]
                                              (bd+ a (:amount leg)))
                                            0M legs))
                 legs* (cond-> legs
                         (not (zero? (.signum balancing)))
                         (conj {:account gain-loss-account :amount balancing}))
                 tx-report (d/transact
                            conn (lposting/plan-adjustment
                                  {:legs legs* :commodity commodity :ledger ledger
                                   :journal journal :date date :posted-at date
                                   :narration "Lease termination"}))]
             ;; Cancel both schedules; zero the liability book; write
             ;; the ROU depreciable base down to its accumulated
             ;; amount (carrying → 0).
             (schedule/mark-cancelled! conn liability-schedule)
             (schedule/mark-cancelled! conn rou-dep-schedule)
             (let [rou-base (:asset-depreciation/depreciable-base
                             (d/pull (d/db conn) [:asset-depreciation/depreciable-base]
                                     rou-dep-book))]
               (d/transact conn [{:db/id liability-book
                                  :lease-liability/opening-liability 0M}
                                 {:db/id rou-dep-book
                                  :asset-depreciation/depreciable-base
                                  (bd- rou-base rou-carrying)}]))
             {:ledger ledger :liability-book liability-book
              :derecognised-liability old-outstanding
              :derecognised-rou rou-carrying
              :transaction (get (:tempids tx-report) -1)}))
         snapshot)
        ;; Drive :active → :terminated (governance: doc + SoD).
        db' (d/db conn)
        status-tx (sm/record-status-change-tx-data
                   db' {:entity lease-eid :entity-type :lease
                        :facet :lease/status :from from :to :terminated
                        :changed-at date :changed-by-uid changed-by-uid
                        :supporting-doc justification :reason :lease-terminated})]
    (d/transact conn (kbt/with-vt status-tx date kbt/forever))
    (when (seq (keep :transaction results))
      (d/transact conn [{:db/id mod-eid
                         :lease-modification/transaction
                         (vec (keep :transaction results))}]))
    {:lease lease-eid :modification mod-eid :books results}))

;; ============================================================================
;; purchase!
;; ============================================================================

(defn purchase!
  "Exercise a purchase option on an `:active` lease. For each
   `:lease-liability` book, settle the remaining liability in cash
   (`Dr liability / Cr cash ± P&L`) and cancel both schedules. Drives
   `:lease/status :active → :purchased`.

   The ROU `:asset` CONTINUES as an owned asset (IFRS 16.67 — its
   carrying amount carries over, no derecognition). kontor-lease does
   NOT presume the owned-asset useful life — the consumer opens a
   fresh `kontor.asset.depreciation/open-book!` over the asset's
   remaining useful life.

   Required opts: :lease, :date, :cash-account, :journal,
                  :changed-by-uid, :gain-loss-account
   Optional: :purchase-price (bigdec — defaults to the lease's
             :purchase-option-price), :justification, :note

   Returns {:lease eid :modification eid :books […]}."
  [conn {:keys [lease date cash-account journal changed-by-uid gain-loss-account
                purchase-price justification note]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not cash-account   (throw (ex-info ":cash-account required" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not gain-loss-account (throw (ex-info ":gain-loss-account required" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:lease/status :lease/purchase-option-price] lease-eid)
        from (:lease/status l)
        _ (when-not (= :active from)
            (throw (ex-info "purchase!: lease is not :active"
                            {:type :lease/not-active :lease lease-eid})))
        price (or purchase-price (:lease/purchase-option-price l))
        _ (when (nil? price)
            (throw (ex-info "purchase!: :purchase-price required (the lease has no :purchase-option-price)" {})))
        snapshot (pre-mod-snapshot db lease-eid)
        mod-eid (record-modification!
                 conn lease-eid {:kind :purchase :date date
                                 :justification justification :note note})
        results
        (mapv
         (fn [{:keys [liability-book liability-account liability-schedule
                      rou-dep-schedule old-outstanding commodity ledger]}]
           (let [;; Dr liability (settle it) / Cr cash (the price) ± P&L.
                 legs (cond-> [{:account liability-account :amount old-outstanding}
                               {:account cash-account
                                :amount (.negate ^BigDecimal price)}])
                 balancing (.negate (reduce (fn [^BigDecimal a leg]
                                              (bd+ a (:amount leg)))
                                            0M legs))
                 legs* (cond-> legs
                         (not (zero? (.signum balancing)))
                         (conj {:account gain-loss-account :amount balancing}))
                 tx-report (d/transact
                            conn (lposting/plan-adjustment
                                  {:legs legs* :commodity commodity :ledger ledger
                                   :journal journal :date date :posted-at date
                                   :narration "Lease purchase-option exercise"}))]
             (schedule/mark-cancelled! conn liability-schedule)
             (schedule/mark-cancelled! conn rou-dep-schedule)
             (d/transact conn [{:db/id liability-book
                                :lease-liability/opening-liability 0M}])
             {:ledger ledger :liability-book liability-book
              :settled-liability old-outstanding
              :transaction (get (:tempids tx-report) -1)}))
         snapshot)
        db' (d/db conn)
        status-tx (sm/record-status-change-tx-data
                   db' (cond-> {:entity lease-eid :entity-type :lease
                                :facet :lease/status :from from :to :purchased
                                :changed-at date :changed-by-uid changed-by-uid
                                :reason :lease-purchased}
                         justification (assoc :supporting-doc justification)))]
    (d/transact conn (kbt/with-vt status-tx date kbt/forever))
    (when (seq (keep :transaction results))
      (d/transact conn [{:db/id mod-eid
                         :lease-modification/transaction
                         (vec (keep :transaction results))}]))
    {:lease lease-eid :modification mod-eid :books results}))

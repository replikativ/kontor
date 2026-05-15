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
            [kontor.process :as process]
            [kontor.schedule :as schedule]
            [kontor.status-machine :as sm])
  (:import [java.math BigDecimal RoundingMode]))

;; ADR-067: each modification commits as ONE atomic, gated
;; `kontor.process` — the contract-fact update, the per-book
;; remeasurement + adjustment GL entry + re-anchor, and (for
;; terminate!/purchase!) the cancel-schedules + status change all land
;; in one tx through `transact-with-validation`. The old
;; `assert-modifiable!` up-front probe and the `transact-checked!`
;; period guard are gone — period (+ sealing + sum-to-zero +
;; invariant) check runs in the gate against the real tx-data, and
;; atomicity removes the orphan-contract-fact concern entirely.

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

(defn- apply-book-adjustment-tx-data
  "Pure tx-data builder for ONE book's modification adjustment +
   re-anchor (ADR-067). Returns the concatenation of: the GL
   adjustment entry, the liability-book re-anchor, and (when the ROU
   base moves or term changed) the ROU dep-book re-anchor. The
   adjustment transaction takes `:tx-tempid (str \"mod-adj\"
   tempid-suffix)` so several books compose into one process tx-data.

   `:rou-base-change` is clamped here so the ROU carrying amount
   never goes below zero — the excess lands in P&L, IFRS 16.39."
  [db {:keys [snapshot new-liability rou-base-change new-discount-rate
              new-term-months gain-loss-account journal date kind note
              tempid-suffix]
       :or {tempid-suffix ""}}]
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
        adjustment (lposting/plan-adjustment
                    {:legs legs
                     :commodity commodity
                     :ledger ledger
                     :journal journal
                     :date date
                     :posted-at date
                     :tx-tempid (str "mod-adj" tempid-suffix)
                     :narration (str "Lease " (name kind))})
        revise-liab (liability/revise-liability-book-tx-data
                     db (cond-> {:book liability-book
                                 :new-opening-liability new-liability}
                          new-discount-rate (assoc :new-discount-rate new-discount-rate)
                          note              (assoc :note note)))
        revise-dep  (when (or (not (zero? (.signum ^BigDecimal rou-base-change*)))
                              new-term-months)
                      (asset-dep/revise-book-tx-data
                       db (cond-> {:book rou-dep-book
                                   :additional-base rou-base-change*}
                            new-term-months (assoc :new-useful-life-months
                                                   new-term-months)
                            note            (assoc :note note))))]
    (cond-> (into (vec adjustment) revise-liab)
      revise-dep (into revise-dep))))

;; ============================================================================
;; The :lease-modification event
;; ============================================================================

(defn- record-modification-tx-data
  "Pure tx-data builder for the append-only `:lease-modification`
   event + the updated `:lease` contract facts (ADR-067). No
   `with-vt` — the process owns valid-time. The event takes the
   `\"lease-mod\"` tempid (callers extract its eid from the process
   tx-report's `:tempids`). When `:tx-tempids` is given (a vec of
   per-book adjustment tx-tempid strings), the event references them
   directly via `:lease-modification/transaction` — no follow-up
   d/transact needed.

   `:liability-delta`/`:rou-delta`/`:pnl-delta` are the per-modification
   aggregated movements (ADR-070); when supplied they are persisted on
   the event so the IFRS 16 / ASC 842 disclosure roll-forward is a
   trivial read."
  [lease-eid {:keys [kind date new-payment-amount new-term-months
                     new-discount-rate scope-decrease-pct justification
                     note tx-tempids liability-delta rou-delta pnl-delta]}]
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
                (seq tx-tempids)    (assoc :lease-modification/transaction
                                           (vec tx-tempids))
                (some? liability-delta)
                (assoc :lease-modification/liability-delta liability-delta)
                (some? rou-delta)
                (assoc :lease-modification/rou-delta rou-delta)
                (some? pnl-delta)
                (assoc :lease-modification/pnl-delta pnl-delta))
        lease-update (cond-> {:db/id lease-eid}
                       new-payment-amount (assoc :lease/payment-amount
                                                 new-payment-amount)
                       new-term-months    (assoc :lease/term-months new-term-months)
                       new-discount-rate  (assoc :lease/discount-rate
                                                 new-discount-rate))]
    [event lease-update]))

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
        snapshot (pre-mod-snapshot db lease-eid)
        ;; Precompute the per-book new-liability + delta. Done from the
        ;; start-snapshot (db) — the modification is one event, all books
        ;; see the same pre-mod state.
        book-plans
        (mapv (fn [{:keys [liability-book liability-schedule old-outstanding]
                    :as snap}]
                (let [ofthr (long (count (schedule/fired-sequences
                                          db liability-schedule)))
                      remaining-n (- n ofthr)
                      _ (when (<= remaining-n 0)
                          (throw (ex-info "remeasure!: revised term leaves no un-fired periods"
                                          {:type :lease/no-remaining-periods
                                           :book liability-book})))
                      new-liability (remaining-pv payment rate freq remaining-n)
                      delta (bd- new-liability old-outstanding)]
                  (assoc snap :new-liability new-liability :delta delta)))
              snapshot)
        ;; Per-book step: adjustment GL + revise-liability + revise-dep
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [new-liability delta] :as snap}]
            (fn [sdb _ctx]
              (apply-book-adjustment-tx-data
               sdb {:snapshot snap
                    :new-liability new-liability
                    :rou-base-change delta
                    :new-discount-rate new-discount-rate
                    :new-term-months new-term-months
                    :gain-loss-account gain-loss-account
                    :journal journal :date date :kind kind :note note
                    :tempid-suffix (str "-" i)})))
          book-plans))
        total-liab-delta (reduce (fn [^BigDecimal a m]
                                   (.add a ^BigDecimal (:delta m)))
                                 0M book-plans)
        ;; remeasure! flows entirely to BS (delta goes to liability +
        ;; ROU equally); no P&L unless a book is driven below zero, in
        ;; which case apply-book-adjustment-tx-data plugs to gain-loss.
        ;; That refinement is deferred — for now we record :pnl-delta 0M
        ;; on the modification, which is correct for the common case.
        ;; Final step: the :lease-modification event + the :lease
        ;; contract-fact update; references the per-book adjustment
        ;; tx-tempids directly (no follow-up :transaction link tx).
        mod-step
        (fn [_sdb _ctx]
          (record-modification-tx-data
           lease-eid {:kind kind :date date
                      :new-payment-amount new-payment-amount
                      :new-term-months new-term-months
                      :new-discount-rate new-discount-rate
                      :justification justification :note note
                      :tx-tempids (mapv #(str "mod-adj-" %)
                                        (range (count book-plans)))
                      :liability-delta total-liab-delta
                      :rou-delta total-liab-delta
                      :pnl-delta 0M}))
        ;; mod-step FIRST so the per-book revise-liability/revise-book
        ;; steps see the updated :lease contract facts in the
        ;; speculative db (they derive the period count from
        ;; :lease/term-months).
        report (process/run-process
                conn {:steps (into [mod-step] book-steps)
                      :vt-from date :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :modification (get tempids "lease-mod")
     :books (mapv (fn [i {:keys [ledger liability-book old-outstanding
                                 new-liability delta]}]
                    {:ledger ledger :liability-book liability-book
                     :old-outstanding old-outstanding
                     :new-liability new-liability
                     :delta delta
                     :transaction (get tempids (str "mod-adj-" i))})
                  (range) book-plans)}))

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
        ;; Precompute per-book new-liability + total ROU base change.
        book-plans
        (mapv (fn [{:keys [liability-book liability-schedule old-outstanding
                           rou-carrying] :as snap}]
                (let [ofthr (long (count (schedule/fired-sequences
                                          db liability-schedule)))
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
                      new-liability (remaining-pv new-payment-amount rate freq
                                                  remaining-n)
                      remeasure-delta (bd- new-liability
                                           (bd- old-outstanding liab-reduction))
                      ;; The total ROU base change = the proportional
                      ;; write-off + the remeasurement adjustment.
                      rou-base-change (bd+ (.negate rou-reduction) remeasure-delta)]
                  (assoc snap :new-liability new-liability
                              :delta (bd- new-liability old-outstanding)
                              :rou-base-change rou-base-change)))
              snapshot)
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [new-liability rou-base-change] :as snap}]
            (fn [sdb _ctx]
              (apply-book-adjustment-tx-data
               sdb {:snapshot snap
                    :new-liability new-liability
                    :rou-base-change rou-base-change
                    :new-discount-rate new-discount-rate
                    :new-term-months new-term-months
                    :gain-loss-account gain-loss-account
                    :journal journal :date date
                    :kind :partial-termination :note note
                    :tempid-suffix (str "-" i)})))
          book-plans))
        total-liab-delta (reduce (fn [^BigDecimal a m]
                                   (.add a ^BigDecimal (:delta m)))
                                 0M book-plans)
        total-rou-delta  (reduce (fn [^BigDecimal a m]
                                   (.add a ^BigDecimal (:rou-base-change m)))
                                 0M book-plans)
        ;; The P&L gain/loss is the residual — IFRS 16.46(b) sets it at
        ;; the difference between the proportional liab + ROU reductions.
        total-pnl-delta  (.subtract total-rou-delta total-liab-delta)
        mod-step
        (fn [_sdb _ctx]
          (record-modification-tx-data
           lease-eid {:kind :partial-termination :date date
                      :new-payment-amount new-payment-amount
                      :new-term-months new-term-months
                      :new-discount-rate new-discount-rate
                      :scope-decrease-pct scope-decrease-pct
                      :justification justification :note note
                      :tx-tempids (mapv #(str "mod-adj-" %)
                                        (range (count book-plans)))
                      :liability-delta total-liab-delta
                      :rou-delta total-rou-delta
                      :pnl-delta total-pnl-delta}))
        report (process/run-process
                conn {:steps (into [mod-step] book-steps)
                      :vt-from date :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :modification (get tempids "lease-mod")
     :books (mapv (fn [i {:keys [ledger liability-book old-outstanding
                                 new-liability delta]}]
                    {:ledger ledger :liability-book liability-book
                     :old-outstanding old-outstanding
                     :new-liability new-liability :delta delta
                     :transaction (get tempids (str "mod-adj-" i))})
                  (range) book-plans)}))

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
        ;; Pre-pull each book's ROU :depreciable-base (needed to write
        ;; it down by rou-carrying → carrying-after = accumulated).
        book-plans
        (mapv (fn [{:keys [rou-dep-book] :as snap}]
                (let [rou-base (:asset-depreciation/depreciable-base
                                (d/pull db [:asset-depreciation/depreciable-base]
                                        rou-dep-book))]
                  (assoc snap :rou-base rou-base)))
              snapshot)
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [liability-book liability-account rou-asset-account
                         rou-dep-book liability-schedule rou-dep-schedule
                         old-outstanding rou-carrying rou-base
                         commodity ledger]}]
            (fn [sdb _ctx]
              (let [;; Dr liability (remove it) / Cr ROU (remove it)
                    ;; [/ Cr cash (penalty)] ± P&L (the balancing).
                    legs (cond-> [{:account liability-account
                                   :amount old-outstanding}
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
                            (conj {:account gain-loss-account
                                   :amount balancing}))
                    adjustment (lposting/plan-adjustment
                                {:legs legs* :commodity commodity
                                 :ledger ledger :journal journal :date date
                                 :posted-at date
                                 :tx-tempid (str "mod-adj-" i)
                                 :narration "Lease termination"})]
                (-> (vec adjustment)
                    (conj {:db/id liability-book
                           :lease-liability/opening-liability 0M}
                          {:db/id rou-dep-book
                           :asset-depreciation/depreciable-base
                           (bd- rou-base rou-carrying)})
                    (into (schedule/set-state-tx-data
                           sdb liability-schedule :cancelled))
                    (into (schedule/set-state-tx-data
                           sdb rou-dep-schedule :cancelled))))))
          book-plans))
        ;; ADR-070 disclosure deltas — termination derecognises BOTH
        ;; the full liability AND the full ROU carrying amount; the P&L
        ;; is the residual (the gain-loss-account leg, plus any
        ;; penalty cash).
        total-liab-delta (.negate (reduce (fn [^BigDecimal a m]
                                            (.add a ^BigDecimal (:old-outstanding m)))
                                          0M book-plans))
        total-rou-delta  (.negate (reduce (fn [^BigDecimal a m]
                                            (.add a ^BigDecimal (:rou-carrying m)))
                                          0M book-plans))
        ;; The P&L pickup = (-liab-delta - cash-paid) - (-rou-delta)
        ;; — i.e. (liab-removed - cash - rou-removed). Per-book legs
        ;; sum to zero, so per-book P&L is the same residual sign-wise.
        total-pnl-delta (.subtract (.subtract (.negate total-liab-delta)
                                              ^BigDecimal penalty*)
                                   (.negate total-rou-delta))
        mod-step
        (fn [_sdb _ctx]
          (record-modification-tx-data
           lease-eid {:kind :termination :date date
                      :justification justification :note note
                      :tx-tempids (mapv #(str "mod-adj-" %)
                                        (range (count book-plans)))
                      :liability-delta total-liab-delta
                      :rou-delta total-rou-delta
                      :pnl-delta total-pnl-delta}))
        status-step
        (fn [sdb _ctx]
          (sm/record-status-change-tx-data
           sdb {:entity lease-eid :entity-type :lease
                :facet :lease/status :from from :to :terminated
                :changed-at date :changed-by-uid changed-by-uid
                :supporting-doc justification :reason :lease-terminated}))
        report (process/run-process
                conn {:steps (-> [mod-step] (into book-steps) (conj status-step))
                      :vt-from date :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :modification (get tempids "lease-mod")
     :books (mapv (fn [i {:keys [ledger liability-book old-outstanding
                                 rou-carrying]}]
                    {:ledger ledger :liability-book liability-book
                     :derecognised-liability old-outstanding
                     :derecognised-rou rou-carrying
                     :transaction (get tempids (str "mod-adj-" i))})
                  (range) book-plans)}))

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
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [liability-book liability-account liability-schedule
                         rou-dep-schedule old-outstanding commodity ledger]}]
            (fn [sdb _ctx]
              (let [;; Dr liability (settle it) / Cr cash (price) ± P&L.
                    legs [{:account liability-account :amount old-outstanding}
                          {:account cash-account
                           :amount (.negate ^BigDecimal price)}]
                    balancing (.negate (reduce (fn [^BigDecimal a leg]
                                                 (bd+ a (:amount leg)))
                                               0M legs))
                    legs* (cond-> legs
                            (not (zero? (.signum balancing)))
                            (conj {:account gain-loss-account
                                   :amount balancing}))
                    adjustment (lposting/plan-adjustment
                                {:legs legs* :commodity commodity
                                 :ledger ledger :journal journal :date date
                                 :posted-at date
                                 :tx-tempid (str "mod-adj-" i)
                                 :narration "Lease purchase-option exercise"})]
                (-> (vec adjustment)
                    (conj {:db/id liability-book
                           :lease-liability/opening-liability 0M})
                    (into (schedule/set-state-tx-data
                           sdb liability-schedule :cancelled))
                    (into (schedule/set-state-tx-data
                           sdb rou-dep-schedule :cancelled))))))
          snapshot))
        ;; ADR-070 disclosure deltas — purchase settles the liability
        ;; for cash; ROU continues (no derecognition under IFRS 16.67).
        ;; :liability-delta = -Σ outstanding; :rou-delta = 0; :pnl-delta
        ;; = settled-liability - cash-paid (the difference the plug
        ;; covers — zero if the option price = the residual liability).
        total-liab-delta (.negate (reduce (fn [^BigDecimal a m]
                                            (.add a ^BigDecimal (:old-outstanding m)))
                                          0M snapshot))
        total-rou-delta 0M
        total-pnl-delta (.subtract (.negate total-liab-delta) ^BigDecimal price)
        mod-step
        (fn [_sdb _ctx]
          (record-modification-tx-data
           lease-eid {:kind :purchase :date date
                      :justification justification :note note
                      :tx-tempids (mapv #(str "mod-adj-" %)
                                        (range (count snapshot)))
                      :liability-delta total-liab-delta
                      :rou-delta total-rou-delta
                      :pnl-delta total-pnl-delta}))
        status-step
        (fn [sdb _ctx]
          (sm/record-status-change-tx-data
           sdb (cond-> {:entity lease-eid :entity-type :lease
                        :facet :lease/status :from from :to :purchased
                        :changed-at date :changed-by-uid changed-by-uid
                        :reason :lease-purchased}
                 justification (assoc :supporting-doc justification))))
        report (process/run-process
                conn {:steps (-> [mod-step] (into book-steps) (conj status-step))
                      :vt-from date :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :modification (get tempids "lease-mod")
     :books (mapv (fn [i {:keys [ledger liability-book old-outstanding]}]
                    {:ledger ledger :liability-book liability-book
                     :settled-liability old-outstanding
                     :transaction (get tempids (str "mod-adj-" i))})
                  (range) snapshot)}))

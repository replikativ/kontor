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
     `:kontor.lease/status` is driven `:active → :terminated`.
   - `purchase!` — a purchase option is exercised. The remaining
     liability is settled in cash, both schedules are cancelled, and
     `:kontor.lease/status` is driven `:active → :purchased`. The ROU
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

   ## ADR-064 Addendum 2 — a book that declines to remeasure must PIN

   `:kontor.lease/payment-amount` is a CONTRACT fact, shared by every
   per-ledger book of the lease, and `remeasure!` rewrites it. A book
   that legitimately declines to remeasure (the ASC 842-10-30-5
   operating + `:index-reset` fork) is therefore left reading a payment
   its ledger never acted on, and its re-plan silently restates
   already-fired periods — the liability subledger and the control
   account then part company permanently (note 198 HIGH-5). Two
   complementary defences:

   - the fork writes `:kontor.lease-liability/payment-amount`, so the
     book keeps unwinding on the payments it is measured on, and
     `run-lease!` recognises the excess contractual rent as variable
     lease cost when each payment is made;
   - `lease-provider/unwind` reads FIRED periods back from the GL
     rather than re-deriving them at all, so no future contract-fact
     change can restate history either.

   `kontor.lease.report/reconcile-liability` is the detective control
   over both, and `terminate!` / `purchase!` refuse to derecognise a
   book that does not tie.

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
            [kontor.lease.report :as report]
            [kontor.workflow.process :as process]
            [kontor.workflow.schedule :as schedule]
            [kontor.workflow.status-machine :as sm])
  (:import [java.math BigDecimal RoundingMode]))

;; ADR-067: each modification commits as ONE atomic, gated
;; `kontor.workflow.process` — the contract-fact update, the per-book
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
  (let [rou-asset (:db/id (:kontor.lease/rou-asset
                           (d/pull db [{:kontor.lease/rou-asset [:db/id]}] lease-eid)))
        _ (when-not rou-asset
            (throw (ex-info "Lease has no :rou-asset — not commenced?"
                            {:type :kontor.lease/not-commenced :lease lease-eid})))
        rou-asset-account (:db/id (:kontor.asset/asset-account
                                   (d/pull db [{:kontor.asset/asset-account [:db/id]}]
                                           rou-asset)))
        contract-payment (:kontor.lease/payment-amount
                          (d/pull db [:kontor.lease/payment-amount] lease-eid))]
    (mapv (fn [lb]
            (let [pb (liability/pull-book db lb)
                  ledger (:db/id (:kontor.lease-liability/ledger pb))
                  ;; Per-(lease, ledger) index-reset fork
                  ;; needs to dispatch on framework + classification.
                  framework      (:kontor.ledger/framework
                                  (:kontor.lease-liability/ledger pb))
                  classification (:kontor.lease-liability/classification pb)
                  rou-dep-book (asset-dep/book-for db rou-asset ledger)
                  rou-base (:kontor.asset-depreciation/depreciable-base
                            (d/pull db [:kontor.asset-depreciation/depreciable-base]
                                    rou-dep-book))
                  accumulated (asset-dep/accumulated-depreciation db rou-dep-book)]
              {:liability-book    lb
               :ledger            ledger
               :framework         framework
               :classification    classification
               ;; The payment THIS book is currently measured on —
               ;; the per-book pin if a prior ASC 842 index reset set
               ;; one, else the shared contract fact.
               :effective-payment (or (:kontor.lease-liability/payment-amount pb)
                                      contract-payment)
               :commodity         (:db/id (:kontor.lease-liability/commodity pb))
               :liability-account (:db/id (:kontor.lease-liability/liability-account pb))
               :liability-schedule (:db/id (:kontor.lease-liability/schedule pb))
               :rou-asset         rou-asset
               :rou-asset-account rou-asset-account
               :rou-dep-book      rou-dep-book
               :rou-dep-schedule  (:db/id (:kontor.asset-depreciation/schedule
                                           (d/pull db [{:kontor.asset-depreciation/schedule
                                                        [:db/id]}]
                                                   rou-dep-book)))
               :old-outstanding   (lp/outstanding-liability db lb)
               :rou-carrying      (bd- rou-base accumulated)}))
          (liability/books-of db lease-eid))))

;; ============================================================================
;; Shared book-level adjustment
;; ============================================================================

(defn- clamp-rou-base-change
  "Apply IFRS 16.39's floor: a ROU adjustment cannot drive the ROU
   carrying amount below zero — the excess lands in P&L. Pure;
   shared by `apply-book-adjustment-tx-data` (writes the GL leg)
   and the ADR-070 disclosure aggregations (`partial-terminate!`'s
   `:rou-delta` must reflect the clamped movement, not the raw
   requested change)."
  ^BigDecimal [^BigDecimal rou-base-change ^BigDecimal rou-carrying]
  (let [floor (.negate rou-carrying)]
    (if (neg? (.compareTo rou-base-change floor))
      floor
      rou-base-change)))

(defn- adjustment-moves-gl?
  "Does this book's modification actually move the ledger? A
   remeasurement whose revised PV reproduces the unwound balance
   exactly — the COMMON case for a level in-arrears lease re-measured
   at the same payment and rate — produces a liability leg and a ROU
   leg of zero, i.e. an entry with no postings at all. That used to
   surface as an opaque `build-transaction: input failed structural
   validation` from the kernel (note 198 MED-3). It is not an error: a
   zero-delta remeasurement is a legitimate re-anchor, it simply has
   nothing to post. Callers use this to decide whether the book
   contributes a `mod-adj-<i>` transaction at all."
  [{:keys [old-outstanding rou-carrying]} new-liability rou-base-change]
  (let [liab (bd- old-outstanding new-liability)
        rou  (clamp-rou-base-change rou-base-change rou-carrying)]
    (or (not (zero? (.signum liab)))
        (not (zero? (.signum rou))))))

(defn- apply-book-adjustment-tx-data
  "Pure tx-data builder for ONE book's modification adjustment +
   re-anchor (ADR-067). Returns the concatenation of: the GL
   adjustment entry (omitted entirely when the modification moves
   neither the liability nor the ROU — see [[adjustment-moves-gl?]]),
   the liability-book re-anchor, and (when the ROU base moves or term
   changed) the ROU dep-book re-anchor. The adjustment transaction
   takes `:tx-tempid (str \"mod-adj\" tempid-suffix)` so several books
   compose into one process tx-data.

   `:rou-base-change` is clamped here via `clamp-rou-base-change` so
   the ROU carrying amount never goes below zero — the excess lands
   in P&L, IFRS 16.39."
  [db {:keys [snapshot new-liability rou-base-change new-discount-rate
              new-term-months gain-loss-account journal date kind note
              tempid-suffix]
       :or {tempid-suffix ""}}]
  (let [{:keys [liability-book liability-account rou-asset-account rou-dep-book
                rou-carrying old-outstanding commodity ledger]} snapshot
        rou-base-change* (clamp-rou-base-change rou-base-change rou-carrying)
        liability-leg (bd- old-outstanding new-liability)
        rou-leg       rou-base-change*
        pl-leg        (.negate (bd+ liability-leg rou-leg))
        _ (when (and (not (zero? (.signum pl-leg))) (not gain-loss-account))
            (throw (ex-info "modification: a P&L gain/loss leg is required but :gain-loss-account was not supplied"
                            {:type :kontor.lease/missing-gain-loss-account
                             :book liability-book :pl pl-leg})))
        legs (cond-> [{:account liability-account :amount liability-leg}
                      {:account rou-asset-account :amount rou-leg}]
               (not (zero? (.signum pl-leg)))
               (conj {:account gain-loss-account :amount pl-leg}))
        adjustment (when (adjustment-moves-gl? snapshot new-liability rou-base-change)
                     (lposting/plan-adjustment
                      {:legs legs
                       :commodity commodity
                       :ledger ledger
                       :journal journal
                       :date date
                       :posted-at date
                       :tx-tempid (str "mod-adj" tempid-suffix)
                       :narration (str "Lease " (name kind))}))
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
;; ASC 842 operating + index-reset fork
;;
;; Under ASC 842-10-30-5 + 842-10-35-4(c), an index-linked variable
;; payment change on an operating lease is NOT a remeasurement — only
;; changes in the index itself triggering a contractual reset event do.
;; Routine CPI escalations are expensed as variable lease cost.
;;
;; ADR-064 Addendum 2 (note 198 HIGH-5) — declining to remeasure is
;; only half the job. `remeasure!` still writes the new indexed rent
;; onto the SHARED `:kontor.lease/payment-amount`, and the liability
;; plan reads it, so the un-remeasured book silently began amortising
;; against a payment the GL never posted for it. The fork therefore
;; PINS the book's own payment and lets the runner recognise the delta
;; as variable lease cost when each payment is actually made.
;;
;; The fork fires when ALL of:
;;   - kind = :index-reset
;;   - ledger's :kontor.ledger/framework = :us-gaap (or "us-gaap" string)
;;   - :lease-liability/classification = :operating
;; ============================================================================

(defn- asc842-operating-index-reset?
  "Predicate — does this (kind, snapshot) combination need the
   variable-expense fork instead of remeasurement?"
  [kind {:keys [framework classification]}]
  (and (= kind :index-reset)
       (or (= framework :us-gaap)
           (= framework :US-GAAP))
       (= classification :operating)))

(defn- apply-payment-pin-tx-data
  "Pure tx-data builder for the ASC 842-operating-on-index-reset book
   path. NO liability remeasurement, NO ROU adjustment and — this is
   the note-198 HIGH-5 fix — NO GL entry at all at the modification
   date: an index reset that is not a remeasurement is not an event
   the ledger records.

   What it does instead is PIN this book's measurement. `remeasure!`
   moves the SHARED `:kontor.lease/payment-amount` to the new indexed
   rent (the IFRS 16 book on the same lease is genuinely remeasured),
   so an un-remeasured book must record the payment it is still
   measured on, or its deterministic re-plan starts amortising against
   money the GL never posted for it. The difference then becomes the
   ASC 842 variable lease cost, which
   `kontor.lease.runner/run-lease!` recognises WHEN PAID — one delta
   per period actually paid, which is what ASC 842-10-35-4(c) asks for
   and what the old one-shot post at the modification date could not
   express (it charged exactly one period's delta for a reset that
   affects every remaining period).

   `:pinned-payment` is the payment this book keeps unwinding on — the
   book's CURRENT effective payment, so a second index reset widens the
   variable delta rather than re-pinning (the measurement never moved)."
  [db {:keys [snapshot pinned-payment variable-lease-expense-account]}]
  (when-not variable-lease-expense-account
    (throw (ex-info "modification: ASC 842 operating + :index-reset needs :variable-lease-expense-account"
                    {:type :kontor.lease/missing-variable-lease-expense-account
                     :book (:liability-book snapshot)})))
  (liability/pin-book-payment-tx-data
   db {:book (:liability-book snapshot)
       :payment-amount pinned-payment
       :variable-expense-account variable-lease-expense-account}))

;; ============================================================================
;; Derecognition guard — note 198 MED-4
;; ============================================================================

(defn- assert-liability-is-on-the-ledger!
  "`terminate!` / `purchase!` derecognise a book's whole
   `old-outstanding` — they debit the liability account for whatever
   the SUBLEDGER says. If the GL never held that amount, the debit
   strands an equal-and-opposite balance on the liability account
   forever, and the P&L gain/loss absorbs a number that never existed.

   The concrete way in is ADR-069: `import-lease!` deliberately posts
   NO GL entry (the import-day bridge journal is the consumer's), so a
   freshly-imported lease carries a full subledger liability against an
   empty ledger. Nothing used to stop it being terminated.

   The check is `kontor.lease.report/reconcile-liability` per book.
   Pass `:allow-gl-mismatch? true` to override deliberately — e.g. mid
   remediation, or when the bridge journal genuinely lives outside the
   lease's attributable transactions (in which case the better fix is
   to pass it as `import-lease!`'s `:bridge-transaction`)."
  [conn op snapshot allow?]
  (when-not allow?
    (doseq [{:keys [liability-book old-outstanding]} snapshot]
      (let [r (report/reconcile-liability conn {:book liability-book})]
        (when-not (:ok? r)
          (throw (ex-info (str op ": the lease-liability subledger does not tie to the GL for this book — "
                               "refusing to derecognise " old-outstanding
                               " the ledger does not hold (difference " (:difference r) "). "
                               "An ADR-069 imported lease needs its import bridge journal posted and passed "
                               "as :bridge-transaction; otherwise investigate before derecognising. "
                               "Override with :allow-gl-mismatch? true.")
                          (assoc r :type :kontor.lease/liability-gl-mismatch
                                 :operation op))))))))

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
   directly via `:kontor.lease-modification/transaction` — no follow-up
   d/transact needed.

   `:liability-delta`/`:rou-delta`/`:pnl-delta` are the per-modification
   aggregated movements (ADR-070); when supplied they are persisted on
   the event so the IFRS 16 / ASC 842 disclosure roll-forward is a
   trivial read."
  [lease-eid {:keys [kind date new-payment-amount new-term-months
                     new-discount-rate scope-decrease-pct justification
                     note tx-tempids liability-delta rou-delta pnl-delta]}]
  (let [event (cond-> {:db/id "lease-mod"
                       :kontor.lease-modification/lease lease-eid
                       :kontor.lease-modification/kind kind
                       :kontor.lease-modification/date date}
                new-payment-amount  (assoc :kontor.lease-modification/new-payment-amount
                                           new-payment-amount)
                new-term-months     (assoc :kontor.lease-modification/new-term-months
                                           new-term-months)
                new-discount-rate   (assoc :kontor.lease-modification/new-discount-rate
                                           new-discount-rate)
                scope-decrease-pct  (assoc :kontor.lease-modification/scope-decrease-pct
                                           scope-decrease-pct)
                justification       (assoc :kontor.lease-modification/justification
                                           justification)
                note                (assoc :kontor.lease-modification/note note)
                (seq tx-tempids)    (assoc :kontor.lease-modification/transaction
                                           (vec tx-tempids))
                (some? liability-delta)
                (assoc :kontor.lease-modification/liability-delta liability-delta)
                (some? rou-delta)
                (assoc :kontor.lease-modification/rou-delta rou-delta)
                (some? pnl-delta)
                (assoc :kontor.lease-modification/pnl-delta pnl-delta))
        lease-update (cond-> {:db/id lease-eid}
                       new-payment-amount (assoc :kontor.lease/payment-amount
                                                 new-payment-amount)
                       new-term-months    (assoc :kontor.lease/term-months new-term-months)
                       new-discount-rate  (assoc :kontor.lease/discount-rate
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
             would drive a ROU book below zero — IFRS 16.39),
             :variable-lease-expense-account (required only when a
             book matches the ASC 842 operating + :index-reset fork
             — see [[asc842-operating-index-reset?]]).

   ## Per-book dispatch

   When `:kind` is `:index-reset` AND a book's ledger has
   `:kontor.ledger/framework :us-gaap` AND the book's
   `:lease-liability/classification` is `:operating`, kontor takes the
   ASC 842-10-30-5 path: NO liability remeasurement, NO ROU
   adjustment, and NO GL entry. The book's own payment is PINNED
   (`:kontor.lease-liability/payment-amount`) so it keeps unwinding on
   the payments it is measured on, and `run-lease!` recognises the
   excess of the new contractual rent over that pin as variable lease
   cost each period, when paid. Other books on the same lease (an IFRS
   16 finance book, an ASC 842 finance book) continue to remeasure
   normally — which is exactly why the pin is needed: they share one
   `:kontor.lease/payment-amount`.

   Returns {:lease eid :modification eid :books [{:ledger
   :liability-book :old-outstanding :new-liability :delta
   :variable-expense? :pinned-payment :period-delta :transaction} …]}.
   `:variable-expense?` is true when the book took the ASC 842 fork;
   `:transaction` is present only for books that actually posted an
   adjustment."
  [conn {:keys [lease date kind journal changed-by-uid new-payment-amount
                new-term-months new-discount-rate justification note
                gain-loss-account
                variable-lease-expense-account]}]
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
        l (d/pull db [:kontor.lease/status :kontor.lease/payment-amount :kontor.lease/term-months
                      :kontor.lease/discount-rate :kontor.lease/payment-frequency]
                  lease-eid)
        _ (when-not (= :active (:kontor.lease/status l))
            (throw (ex-info "remeasure!: lease is not :active"
                            {:type :kontor.lease/not-active :lease lease-eid
                             :status (:kontor.lease/status l)})))
        freq    (:kontor.lease/payment-frequency l)
        payment (or new-payment-amount (:kontor.lease/payment-amount l))
        term    (or new-term-months (:kontor.lease/term-months l))
        rate    (or new-discount-rate (:kontor.lease/discount-rate l))
        n       (lease/periods-for term freq)
        snapshot (pre-mod-snapshot db lease-eid)
        ;; Precompute the per-book new-liability + delta. Done from the
        ;; start-snapshot (db) — the modification is one event, all books
        ;; see the same pre-mod state.
        ;;
        ;; Per-book dispatch: ASC 842 operating books on
        ;; :index-reset take the variable-expense path (NO liability
        ;; remeasurement, NO ROU adjustment, post (Δpayment) once as
        ;; variable lease expense). All other (framework, classification)
        ;; combinations take the historical remeasurement path. Tag each
        ;; snapshot with `:variable-expense?` so subsequent steps can
        ;; pick the right branch and the modification event records
        ;; truthful per-book deltas.
        book-plans
        (mapv (fn [{:keys [liability-book liability-schedule old-outstanding
                           effective-payment]
                    :as snap}]
                (let [variable-expense? (asc842-operating-index-reset? kind snap)
                      ofthr (long (count (schedule/fired-sequences
                                          db liability-schedule)))
                      remaining-n (- n ofthr)]
                  (if variable-expense?
                    ;; ASC 842 operating + :index-reset → no liability /
                    ;; ROU movement and no GL entry. Pin the book's own
                    ;; payment so the plan keeps reproducing what the
                    ;; ledger holds; the runner recognises the delta as
                    ;; variable lease cost, per period, when paid.
                    (assoc snap
                           :variable-expense?  true
                           :pinned-payment     effective-payment
                           :period-delta       (bd- payment effective-payment)
                           :new-liability      old-outstanding
                           :delta              0M
                           :posts-adjustment?  false)
                    (do
                      (when (<= remaining-n 0)
                        (throw (ex-info "remeasure!: revised term leaves no un-fired periods"
                                        {:type :kontor.lease/no-remaining-periods
                                         :book liability-book})))
                      (let [new-liability (remaining-pv payment rate freq remaining-n)
                            delta (bd- new-liability old-outstanding)]
                        (assoc snap
                               :variable-expense? false
                               :new-liability     new-liability
                               :delta             delta
                               :posts-adjustment?
                               (adjustment-moves-gl? snap new-liability delta)))))))
              snapshot)
        ;; The per-book adjustment tx-tempids the modification event
        ;; back-references — ONLY the books that actually post one. A
        ;; forked book posts nothing; neither does a zero-delta
        ;; remeasurement (note 198 MED-3). Referencing a tempid no step
        ;; creates would be a dangling ref.
        adj-tx-tempids (into [] (comp (filter :posts-adjustment?)
                                      (map #(str "mod-adj-" (:book-index %))))
                             (map-indexed #(assoc %2 :book-index %1) book-plans))
        ;; Per-book step: dispatch on :variable-expense?
        book-steps
        (vec
         (map-indexed
          (fn [i {:keys [variable-expense? new-liability delta pinned-payment]
                  :as snap}]
            (fn [sdb _ctx]
              (if variable-expense?
                (apply-payment-pin-tx-data
                 sdb {:snapshot snap
                      :pinned-payment pinned-payment
                      :variable-lease-expense-account variable-lease-expense-account})
                (apply-book-adjustment-tx-data
                 sdb {:snapshot snap
                      :new-liability new-liability
                      :rou-base-change delta
                      :new-discount-rate new-discount-rate
                      :new-term-months new-term-months
                      :gain-loss-account gain-loss-account
                      :journal journal :date date :kind kind :note note
                      :tempid-suffix (str "-" i)}))))
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
                      :tx-tempids adj-tx-tempids
                      :liability-delta total-liab-delta
                      :rou-delta total-liab-delta
                      :pnl-delta 0M}))
        ;; mod-step FIRST so the per-book revise-liability/revise-book
        ;; steps see the updated :lease contract facts in the
        ;; speculative db (they derive the period count from
        ;; :kontor.lease/term-months).
        report (process/run-process
                conn {:steps (into [mod-step] book-steps)
                      :vt-from date :vt-to kbt/forever})
        tempids (:tempids report)]
    {:lease lease-eid
     :modification (get tempids "lease-mod")
     :books (mapv (fn [i {:keys [ledger liability-book old-outstanding
                                 new-liability delta variable-expense?
                                 pinned-payment period-delta posts-adjustment?]}]
                    (cond-> {:ledger ledger :liability-book liability-book
                             :old-outstanding old-outstanding
                             :new-liability new-liability
                             :delta delta}
                      posts-adjustment?
                      (assoc :transaction (get tempids (str "mod-adj-" i)))
                      variable-expense?
                      (assoc :variable-expense? true
                             :pinned-payment   pinned-payment
                             :period-delta     period-delta)))
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
             :note, :allow-gl-mismatch? (default false — see
             [[assert-liability-is-on-the-ledger!]])

   Returns the same shape as `remeasure!`."
  [conn {:keys [lease date scope-decrease-pct new-payment-amount new-term-months
                new-discount-rate journal changed-by-uid justification note
                gain-loss-account allow-gl-mismatch?]}]
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
        l (d/pull db [:kontor.lease/status :kontor.lease/term-months :kontor.lease/discount-rate
                      :kontor.lease/payment-frequency]
                  lease-eid)
        _ (when-not (= :active (:kontor.lease/status l))
            (throw (ex-info "partial-terminate!: lease is not :active"
                            {:type :kontor.lease/not-active :lease lease-eid})))
        freq (:kontor.lease/payment-frequency l)
        term (or new-term-months (:kontor.lease/term-months l))
        rate (or new-discount-rate (:kontor.lease/discount-rate l))
        n    (lease/periods-for term freq)
        snapshot (pre-mod-snapshot db lease-eid)
        _ (assert-liability-is-on-the-ledger! conn "partial-terminate!" snapshot
                                              allow-gl-mismatch?)
        ;; Precompute per-book new-liability + total ROU base change.
        book-plans
        (mapv (fn [{:keys [liability-book liability-schedule old-outstanding
                           rou-carrying] :as snap}]
                (let [ofthr (long (count (schedule/fired-sequences
                                          db liability-schedule)))
                      remaining-n (- n ofthr)
                      _ (when (<= remaining-n 0)
                          (throw (ex-info "partial-terminate!: revised term leaves no un-fired periods"
                                          {:type :kontor.lease/no-remaining-periods
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
                         :rou-base-change rou-base-change
                         :posts-adjustment?
                         (adjustment-moves-gl? snap new-liability
                                               rou-base-change))))
              snapshot)
        adj-tx-tempids (into [] (comp (filter :posts-adjustment?)
                                      (map #(str "mod-adj-" (:book-index %))))
                             (map-indexed #(assoc %2 :book-index %1) book-plans))
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
        ;; ADR-070 §sign convention — :rou-delta is the CLAMPED movement
        ;; (IFRS 16.39 floor: ROU never goes below zero). The disclosure
        ;; must reflect what the GL actually booked, not the raw
        ;; requested change. Mirror `apply-book-adjustment-tx-data`.
        total-rou-delta  (reduce (fn [^BigDecimal a m]
                                   (.add a (clamp-rou-base-change
                                            (:rou-base-change m)
                                            (:rou-carrying m))))
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
                      :tx-tempids adj-tx-tempids
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
   schedules. Drives `:kontor.lease/status :active → :terminated` (ADR-038:
   `:requires-supporting-doc` + `:no-self-approval`).

   The ROU `:asset` entity's status is left untouched — kontor-lease
   terminates the LEASE accounting; disposing the ROU `:asset` from
   the fixed-asset register (if the consumer's process requires it)
   is a `kontor.asset.asset/dispose!` call.

   Required opts: :lease, :date, :journal, :changed-by-uid,
                  :justification (the termination agreement),
                  :gain-loss-account
   Optional: :penalty (bigdec — a termination penalty paid in cash),
             :cash-account (required iff :penalty > 0), :note,
             :allow-gl-mismatch? (default false — see
             [[assert-liability-is-on-the-ledger!]])

   Returns {:lease eid :modification eid :books [{:ledger
   :liability-book :derecognised-liability :derecognised-rou} …]}."
  [conn {:keys [lease date journal changed-by-uid justification gain-loss-account
                penalty cash-account note allow-gl-mismatch?]}]
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
        l (d/pull db [:kontor.lease/status] lease-eid)
        from (:kontor.lease/status l)
        _ (when-not (= :active from)
            (throw (ex-info "terminate!: lease is not :active"
                            {:type :kontor.lease/not-active :lease lease-eid})))
        penalty* (bd penalty)
        snapshot (pre-mod-snapshot db lease-eid)
        _ (assert-liability-is-on-the-ledger! conn "terminate!" snapshot
                                              allow-gl-mismatch?)
        ;; Pre-pull each book's ROU :depreciable-base (needed to write
        ;; it down by rou-carrying → carrying-after = accumulated).
        book-plans
        (mapv (fn [{:keys [rou-dep-book] :as snap}]
                (let [rou-base (:kontor.asset-depreciation/depreciable-base
                                (d/pull db [:kontor.asset-depreciation/depreciable-base]
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
                    ;; Zero the book AND advance its anchor past every
                    ;; fired period. `outstanding-liability` nets the
                    ;; opening against the principal the GL relieved
                    ;; SINCE the anchor (note 198 HIGH-5), so leaving
                    ;; :opening-fired-through behind would make a
                    ;; derecognised book report a negative carrying
                    ;; amount.
                    (conj {:db/id liability-book
                           :kontor.lease-liability/opening-liability 0M
                           :kontor.lease-liability/opening-fired-through
                           (long (count (schedule/fired-sequences
                                         sdb liability-schedule)))}
                          {:db/id rou-dep-book
                           :kontor.asset-depreciation/depreciable-base
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
                :facet :kontor.lease/status :from from :to :terminated
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
   `:kontor.lease/status :active → :purchased`.

   The ROU `:asset` CONTINUES as an owned asset (IFRS 16.67 — its
   carrying amount carries over, no derecognition). kontor-lease does
   NOT presume the owned-asset useful life — the consumer opens a
   fresh `kontor.asset.depreciation/open-book!` over the asset's
   remaining useful life.

   Required opts: :lease, :date, :cash-account, :journal,
                  :changed-by-uid, :gain-loss-account
   Optional: :purchase-price (bigdec — defaults to the lease's
             :purchase-option-price), :justification, :note,
             :allow-gl-mismatch? (default false — see
             [[assert-liability-is-on-the-ledger!]])

   Returns {:lease eid :modification eid :books […]}."
  [conn {:keys [lease date cash-account journal changed-by-uid gain-loss-account
                purchase-price justification note allow-gl-mismatch?]}]
  (when-not lease          (throw (ex-info ":lease required" {})))
  (when-not date           (throw (ex-info ":date required" {})))
  (when-not cash-account   (throw (ex-info ":cash-account required" {})))
  (when-not journal        (throw (ex-info ":journal required" {})))
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not gain-loss-account (throw (ex-info ":gain-loss-account required" {})))
  (let [db (d/db conn)
        lease-eid (lease/resolve-lease db lease)
        _ (when-not lease-eid (throw (ex-info "Lease not found" {:spec lease})))
        l (d/pull db [:kontor.lease/status :kontor.lease/purchase-option-price] lease-eid)
        from (:kontor.lease/status l)
        _ (when-not (= :active from)
            (throw (ex-info "purchase!: lease is not :active"
                            {:type :kontor.lease/not-active :lease lease-eid})))
        price (or purchase-price (:kontor.lease/purchase-option-price l))
        _ (when (nil? price)
            (throw (ex-info "purchase!: :purchase-price required (the lease has no :purchase-option-price)" {})))
        snapshot (pre-mod-snapshot db lease-eid)
        _ (assert-liability-is-on-the-ledger! conn "purchase!" snapshot
                                              allow-gl-mismatch?)
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
                    ;; See terminate! — zero the book and advance its
                    ;; anchor past every fired period (note 198 HIGH-5).
                    (conj {:db/id liability-book
                           :kontor.lease-liability/opening-liability 0M
                           :kontor.lease-liability/opening-fired-through
                           (long (count (schedule/fired-sequences
                                         sdb liability-schedule)))})
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
                        :facet :kontor.lease/status :from from :to :purchased
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

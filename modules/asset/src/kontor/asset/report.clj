(ns kontor.asset.report
  "kontor-asset Jahresabschluss reports — ADR-056.

   Three pieces:

   1. `asset-roll-forward` — the Anlagengitter / Anlagenspiegel
      (HGB §284 Abs. 3) arithmetic: the per-class gross-cost and
      accumulated-depreciation roll-forward over a date window. It is
      **not** a posting aggregation by account code — it is keyed on
      `:asset` + `:asset-event` + `:schedule-occurrence` history, so
      it stays correct even when many assets in a class share one GL
      account. The arithmetic is jurisdiction-free (every regime
      needs an asset roll-forward — US Form 4562, IAS 16.73); the
      *layout* (the HGB column order, the SKR04 class grouping) is
      l10n's.

   2. `asset-tie-out` — the detective control that keeps (1) honest:
      it asserts the asset subledger (Σ gross carrying amount, Σ
      accumulated depreciation) equals the GL cost + accumulated
      control-account balances, and surfaces any delta. Because the
      roll-forward reads subledger facts rather than postings, an
      `:asset-event` whose GL entry was never posted would otherwise
      show up in the Anlagengitter and nowhere else; `asset-tie-out`
      is what turns that into a reported `:difference`.

   3. `pending-depreciation-issues` — a `kontor.compliance.period/close!`
      `:pre-checks` fn that flags 'you forgot to run depreciation for
      this period'.

   The roll-forward is per (window, ledger): a depreciation book is
   per-(asset, ledger) (ADR-054), so the roll-forward for the HGB
   book and the Steuerbilanz book are two `asset-roll-forward` calls
   with different `:ledger`s."
  (:require [datahike.api :as d]
            [kontor.asset.depreciation :as depreciation]
            [kontor.reporting.balance :as balance]
            [kontor.workflow.schedule :as schedule])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; Anlagengitter — the asset roll-forward
;; ============================================================================

(defn- before? [^Date a ^Date b] (neg? (.compareTo a b)))

(defn- window-class
  "Classify a fired occurrence's date relative to `[from, to)`."
  [^Date d ^Date from ^Date to]
  (cond
    (before? d from) :before
    (before? d to)   :in-window
    :else            :after))

(defn- bd+ ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.add a b))

(defn- bd- ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.subtract a b))

(defn- split-by-window
  "Split a seq of `[date amount]` pairs into the window buckets:
   `{:before … :in-window … :before-to …}` (before-to = before +
   in-window)."
  [date-amount-pairs ^Date from ^Date to]
  (reduce (fn [acc [d amt]]
            (case (window-class d from to)
              :before    (-> acc (update :before bd+ amt)
                             (update :before-to bd+ amt))
              :in-window (-> acc (update :in-window bd+ amt)
                             (update :before-to bd+ amt))
              :after     acc))
          {:before 0M :in-window 0M :before-to 0M}
          date-amount-pairs))

(defn- asset-record
  "Gather one asset's roll-forward inputs: cost, entry-date, the
   earliest disposal/transfer date (if any), the book's
   `:opening-accumulated`, its fired `:schedule-occurrence`
   `[date amount]` pairs, and its `:impairment` / `:revaluation` /
   `:addition` `:asset-event` `[date amount]` pairs."
  [db asset book sched]
  (let [a (d/pull db [:kontor.asset/acquisition-cost :kontor.asset/in-service-date
                      :kontor.asset/acquisition-date {:kontor.asset/class [:db/id]}]
                  asset)
        opening-accumulated (or (:kontor.asset-depreciation/opening-accumulated
                                 (d/pull db [:kontor.asset-depreciation/opening-accumulated]
                                         book))
                                0M)
        ;; `:with ?o` / `:with ?e` are load-bearing on BOTH queries
        ;; below: a datalog :find set collapses duplicate tuples, so
        ;; two occurrences sharing a (scheduled-date, amount) — the
        ;; ADR-055 re-planning case — or two events sharing a
        ;; (kind, date, amount) would fuse into ONE row and the
        ;; window sums would silently UNDERSTATE the Anlagengitter.
        ;; `kontor.asset.depreciation/scheduled-depreciation` carries
        ;; the same note; this file was missed when that one was fixed.
        occ (d/q '[:find ?d ?amt
                   :with ?o
                   :in $ ?s
                   :where
                   [?o :kontor.schedule-occurrence/schedule ?s]
                   [?o :kontor.schedule-occurrence/scheduled-date ?d]
                   [?o :kontor.schedule-occurrence/amount ?amt]]
                 db sched)
        removal-dates (d/q '[:find [?d ...]
                             :in $ ?asset
                             :where
                             [?e :kontor.asset-event/asset ?asset]
                             [?e :kontor.asset-event/kind ?kind]
                             [(contains? #{:disposal :transfer} ?kind)]
                             [?e :kontor.asset-event/date ?d]]
                           db asset)
        ;; [kind date amount] for the value-moving mid-life events.
        kind-events (d/q '[:find ?kind ?d ?amt
                           :with ?e
                           :in $ ?asset
                           :where
                           [?e :kontor.asset-event/asset ?asset]
                           [?e :kontor.asset-event/kind ?kind]
                           [(contains? #{:impairment :revaluation :addition} ?kind)]
                           [?e :kontor.asset-event/date ?d]
                           [?e :kontor.asset-event/amount ?amt]]
                         db asset)
        by-kind (group-by first kind-events)
        pairs   (fn [k] (mapv (fn [[_ d amt]] [d amt]) (get by-kind k)))]
    {:asset               asset
     :book                book
     :class               (:db/id (:kontor.asset/class a))
     :cost                (or (:kontor.asset/acquisition-cost a) 0M)
     :entry-date          (or (:kontor.asset/in-service-date a) (:kontor.asset/acquisition-date a))
     :removal-date        (when (seq removal-dates) (first (sort removal-dates)))
     :opening-accumulated opening-accumulated
     :occ                 occ
     :impairments         (pairs :impairment)
     :revaluations        (pairs :revaluation)
     :additions           (pairs :addition)}))

(defn- record-contributions
  "Per-asset contributions to the roll-forward buckets over
   `[from, to)`, or nil if the asset is outside the window entirely.

   Depreciation occurrences AND `:impairment` events flow into the
   accumulated-depreciation roll-forward (HGB §284 Abs. 3 shows
   außerplanmäßige Abschreibung). `:revaluation` and `:addition`
   events adjust the gross-cost roll-forward (`plan-revaluation` posts
   `Dr asset-account`, and a capitalised addition is booked there too).
   The book's `:opening-accumulated` (a mid-life import's pre-schedule
   depreciation) is always opening accumulated.

   The buckets mirror `kontor.asset.depreciation`'s
   `accumulated-depreciation` / `gross-carrying-amount` exactly — the
   two must not compute 'accumulated depreciation' differently, or
   the Anlagengitter and the disposal entry disagree."
  [{:keys [cost entry-date removal-date opening-accumulated
           occ impairments revaluations additions]}
   ^Date from ^Date to]
  (let [entry-before-from? (and entry-date (before? entry-date from))
        entry-in-window?   (and entry-date
                                (not (before? entry-date from))
                                (before? entry-date to))
        entry-after-to?    (and entry-date (not (before? entry-date to)))
        removed-before-from? (and removal-date (before? removal-date from))
        removed-in-window?   (and removal-date
                                  (not (before? removal-date from))
                                  (before? removal-date to))]
    (cond
      ;; Not yet in the books, or already gone before the window —
      ;; contributes nothing.
      entry-after-to?      nil
      removed-before-from? nil
      :else
      (let [occ*    (split-by-window occ from to)
            impair* (split-by-window impairments from to)
            reval*  (split-by-window revaluations from to)
            addn*   (split-by-window additions from to)
            ;; accumulated = scheduled depreciation + impairments
            ;;   (+ the book's pre-schedule opening-accumulated)
            accum-opening   (bd+ opening-accumulated
                                 (bd+ (:before occ*) (:before impair*)))
            accum-period    (bd+ (:in-window occ*) (:in-window impair*))
            accum-before-to (bd+ opening-accumulated
                                 (bd+ (:before-to occ*) (:before-to impair*)))
            ;; gross cost = acquisition cost + revaluations + additions
            cost-before     (bd+ cost (bd+ (:before reval*) (:before addn*)))
            cost-in-window  (bd+ (:in-window reval*) (:in-window addn*))
            cost-before-to  (bd+ cost (bd+ (:before-to reval*) (:before-to addn*)))]
        {:cost-opening    (if entry-before-from? cost-before 0M)
         :cost-additions  (bd+ (if entry-in-window? cost 0M) cost-in-window)
         :cost-disposals  (if removed-in-window? cost-before-to 0M)
         :accum-opening   accum-opening
         :accum-period    accum-period
         :accum-disposals (if removed-in-window? accum-before-to 0M)
         :impairments     (:in-window impair*)
         :revaluations    (:in-window reval*)
         :additions       (:in-window addn*)}))))

(defn- aggregate-group
  "Sum a group's per-asset contribution maps into the roll-forward
   row, deriving the closing + NBV figures."
  [group-key contributions]
  (let [add-k (fn [k] (reduce (fn [acc c] (bd+ acc (get c k))) 0M contributions))
        cost-opening    (add-k :cost-opening)
        cost-additions  (add-k :cost-additions)
        cost-disposals  (add-k :cost-disposals)
        cost-closing    (bd- (bd+ cost-opening cost-additions) cost-disposals)
        accum-opening   (add-k :accum-opening)
        accum-period    (add-k :accum-period)
        accum-disposals (add-k :accum-disposals)
        accum-closing   (bd- (bd+ accum-opening accum-period) accum-disposals)]
    {:group           group-key
     :asset-count     (count contributions)
     :cost-opening    cost-opening
     :cost-additions  cost-additions
     :cost-disposals  cost-disposals
     :cost-closing    cost-closing
     :accum-opening   accum-opening
     :accum-period    accum-period
     :accum-disposals accum-disposals
     :accum-closing   accum-closing
     ;; Memo lines — the in-window impairment / revaluation / addition
     ;; totals, already folded into accum-period / cost-additions above.
     :impairments     (add-k :impairments)
     :revaluations    (add-k :revaluations)
     :additions       (add-k :additions)
     :nbv-opening     (bd- cost-opening accum-opening)
     :nbv-closing     (bd- cost-closing accum-closing)}))

(defn asset-roll-forward
  "Compute the Anlagengitter roll-forward for a `:ledger` over the
   window `[from, to)`. Returns:

     {:window {:from Date :to Date}
      :ledger eid
      :groups [{:group <key>            ; :asset-class eid, or :all
                :asset-count n
                :cost-opening   :cost-additions :cost-disposals :cost-closing
                :accum-opening  :accum-period   :accum-disposals :accum-closing
                :impairments    :revaluations   :additions  ; in-window memos
                :nbv-opening    :nbv-closing} …]
      :totals {…same keys, summed across groups…}}

   Every figure is a bigdec. `cost-closing = cost-opening +
   cost-additions − cost-disposals`; `accum-closing = accum-opening +
   accum-period − accum-disposals`; NBV = cost − accumulated.

   `:cost-additions` counts assets whose `:in-service-date` (fallback
   `:acquisition-date`) falls in the window; `:cost-disposals` counts
   assets with a `:disposal` OR `:transfer` `:asset-event` in the
   window (a transfer-out is a removal from this ledger's books — v1
   folds the two; a dedicated transfers column is a follow-up).

   Mid-life events are folded in (review-after market-pain):
   `:impairment` `:asset-event`s flow into the accumulated-depreciation
   roll-forward (HGB §284 Abs. 3 shows außerplanmäßige Abschreibung);
   `:revaluation` and `:addition` events adjust the gross-cost
   roll-forward; a book's `:opening-accumulated` (a mid-life import's
   pre-schedule depreciation) is opening accumulated. `:impairments` /
   `:revaluations` / `:additions` are exposed as in-window memo totals
   (already folded into `:accum-period` / `:cost-additions`).

   This is a SUBLEDGER report: it counts every value-moving
   `:asset-event`, whether or not its GL entry was posted. That is
   deliberate — the event log is the register's own record of what the
   books owe, and the same figures drive `plan-disposal`, so counting
   only linked events would make a disposal silently under-relieve.
   The consequence — a recorded-but-unposted event makes the
   Anlagengitter disagree with the balance sheet — is caught by
   [[asset-tie-out]], not papered over here.

   Required opts: `:from`, `:to`, `:ledger` (eid).
   Optional: `:group-by` — `:class` (default, group by `:kontor.asset/class`)
             or `:none` (one `:all` group)."
  [db {:keys [from to ledger group-by] :or {group-by :class}}]
  (when-not (and from to) (throw (ex-info "asset-roll-forward requires :from and :to" {})))
  (when-not ledger        (throw (ex-info "asset-roll-forward requires :ledger" {})))
  (let [books (d/q '[:find ?asset ?book ?sched
                     :in $ ?ledger
                     :where
                     [?book :kontor.asset-depreciation/ledger ?ledger]
                     [?book :kontor.asset-depreciation/asset ?asset]
                     [?book :kontor.asset-depreciation/schedule ?sched]]
                   db ledger)
        records (mapv (fn [[asset book sched]]
                        (asset-record db asset book sched))
                      books)
        ;; {group-key → [contribution …]}
        by-group (reduce (fn [acc rec]
                           (if-let [contrib (record-contributions rec from to)]
                             (let [k (case group-by
                                       :class (:class rec)
                                       :none  :all)]
                               (update acc k (fnil conj []) contrib))
                             acc))
                         {}
                         records)
        groups (mapv (fn [[k contribs]] (aggregate-group k contribs)) by-group)
        totals (aggregate-group :totals (mapcat second by-group))]
    {:window {:from from :to to}
     :ledger ledger
     :groups groups
     :totals (dissoc totals :group)}))

;; ============================================================================
;; asset-tie-out
;; ============================================================================

(def ^:private gone-statuses
  "Statuses whose assets `plan-disposal` has already relieved out of
   BOTH control accounts, so the subledger must stop counting them."
  #{:disposed :transferred})

(defn asset-tie-out
  "Reconcile the asset subledger to its two GL control accounts.
   `subledger` = `Σ depreciation/gross-carrying-amount` and
   `Σ depreciation/accumulated-depreciation` over every still-held
   asset on `:ledger` that posts to those accounts; `gl` = the
   corresponding account balances for `:commodity`. A non-zero
   `:difference` is the 'my balance-sheet fixed-asset number is wrong'
   finding — surfaced, not hidden.

   This is the asset sibling of
   `kontor.inventory.report/valuation-tie-out`, and it exists for the
   same reason: the roll-forward reads subledger facts
   (`:schedule-occurrence` + `:asset-event`), because GL accounts are
   shared across a whole class and a `:posting` carries no per-asset
   back-ref. Nothing structurally forces the two to agree, so the
   agreement has to be MEASURED. The three ways they drift:

   - an `:asset-event` (impairment / revaluation / addition) was
     recorded but its GL entry never posted;
   - a GL entry was posted straight to the control account without a
     corresponding register fact;
   - a partial disposal — `:partial-disposal` is a RESERVED
     `:kontor.asset-event/kind` with no transactor, so the register
     still carries the full `:acquisition-cost` while
     `plan-disposal` has already relieved the disposed slice from the
     GL. Expect a `:difference` of exactly the disposed portion until
     that follow-up lands.

   Required: :ledger (eid), :asset-account (eid),
             :accumulated-account (eid), :commodity (eid).
   Optional: :as-of-valid, :as-of-tx (the bitemporal cursor, applied
             to BOTH sides — `:as-of-tx` snapshots the db the
             subledger reduce runs against, so it cannot compare a
             current subledger to a historical GL);
             :include-states (passed through to
             `kontor.reporting.balance/account-balance`; default
             `#{:posted}`, so a DRAFT disposal entry is correctly
             absent from the GL side).

   Returns
     {:ledger eid :asset-count n
      :subledger  {:cost bigdec :accumulated bigdec :nbv bigdec}
      :gl         {:cost bigdec :accumulated bigdec :nbv bigdec}
      :difference {:cost bigdec :accumulated bigdec :nbv bigdec}
      :ok? boolean}

   `:accumulated` is stated POSITIVE on both sides: the GL
   accumulated-account carries a credit (negative) balance, and it is
   negated here so it reads as the contra-asset figure the
   Anlagengitter shows. `:nbv` = `:cost − :accumulated`. `:ok?` is
   true iff both `:cost` and `:accumulated` differences are zero."
  [conn {:keys [ledger asset-account accumulated-account commodity
                as-of-valid as-of-tx include-states]}]
  (when-not ledger              (throw (ex-info ":ledger required" {})))
  (when-not asset-account       (throw (ex-info ":asset-account required" {})))
  (when-not accumulated-account (throw (ex-info ":accumulated-account required" {})))
  (when-not commodity           (throw (ex-info ":commodity required" {})))
  (let [;; :as-of-tx is honoured on the subledger side by snapshotting
        ;; the db — the depreciation roll-forward queries take a db,
        ;; so the tx-time axis must be applied to `db` itself
        ;; (account-balance applies it on the GL side).
        db    (cond-> (d/db conn) as-of-tx (d/as-of as-of-tx))
        opts  (cond-> {:ledger ledger}
                as-of-valid    (assoc :as-of-valid as-of-valid)
                as-of-tx       (assoc :as-of-tx as-of-tx)
                include-states (assoc :include-states include-states))
        books (d/q '[:find ?book ?status
                     :in $ ?ledger ?aa ?ca
                     :where
                     [?book :kontor.asset-depreciation/ledger ?ledger]
                     [?book :kontor.asset-depreciation/asset ?asset]
                     [?asset :kontor.asset/asset-account ?aa]
                     [?asset :kontor.asset/accumulated-account ?ca]
                     [(get-else $ ?asset :kontor.asset/status :none) ?status]]
                   db ledger asset-account accumulated-account)
        held  (into [] (comp (remove (fn [[_ status]] (gone-statuses status)))
                             (map first))
                    books)
        sub   (reduce (fn [acc book]
                        (-> acc
                            (update :cost
                                    (fn [^BigDecimal c]
                                      (.add c (depreciation/gross-carrying-amount db book))))
                            (update :accumulated
                                    (fn [^BigDecimal a]
                                      (.add a (depreciation/accumulated-depreciation db book))))))
                      {:cost 0M :accumulated 0M}
                      held)
        gl-of (fn ^BigDecimal [account]
                (or (:amount (get (balance/account-balance conn account opts) commodity))
                    0M))
        gl    {:cost        (gl-of asset-account)
               ;; A contra-asset carries a credit balance — negate so
               ;; both sides speak the Anlagengitter's sign.
               :accumulated (.negate ^BigDecimal (gl-of accumulated-account))}
        nbv   (fn [{:keys [^BigDecimal cost ^BigDecimal accumulated]}]
                (.subtract cost accumulated))
        diff  {:cost        (.subtract ^BigDecimal (:cost sub) ^BigDecimal (:cost gl))
               :accumulated (.subtract ^BigDecimal (:accumulated sub)
                                       ^BigDecimal (:accumulated gl))}]
    {:ledger      ledger
     :asset-count (count held)
     :subledger   (assoc sub :nbv (nbv sub))
     :gl          (assoc gl  :nbv (nbv gl))
     :difference  (assoc diff :nbv (nbv diff))
     :ok?         (and (zero? (.signum ^BigDecimal (:cost diff)))
                       (zero? (.signum ^BigDecimal (:accumulated diff))))}))

;; ============================================================================
;; Pre-close hook — :no-pending-depreciation
;; ============================================================================

(defn pending-depreciation-issues
  "A `kontor.compliance.period/close!`-compatible `:pre-checks` fn
   `(db period) → [issue …]`. Flags any `:asset-depreciation` book
   whose `:schedule` has an occurrence due within the period window
   `[start, end)` that has NOT been fired — i.e. 'you forgot to run
   the depreciation runner for this period'.

   Compose with `kontor.compliance.period/default-pre-close-checks`:

     (kontor.compliance.period/close!
       conn period-eid
       {:pre-checks
        (fn [db p]
          (into (kontor.compliance.period/default-pre-close-checks db p)
                (kontor.asset.report/pending-depreciation-issues db p)))})

   `period` is the map `close!` passes its pre-checks:
   `{:start Date :end Date …}`. Only `:kontor.schedule/state :active` books
   are considered (a `:completed` / `:paused` book never flags)."
  [db {:keys [start end]}]
  (let [book+sched (d/q '[:find ?book ?sched
                          :where [?book :kontor.asset-depreciation/schedule ?sched]]
                        db)
        with-pending
        (keep (fn [[book sched]]
                (let [pending (->> (schedule/pending-occurrences db sched end)
                                   (filter (fn [{:keys [^Date date]}]
                                             (not (before? date start)))))]
                  (when (seq pending)
                    {:book book
                     :schedule sched
                     :pending-sequences (mapv :sequence pending)})))
              book+sched)]
    (if (seq with-pending)
      [{:check :no-pending-depreciation
        :reason (str (count with-pending)
                     " depreciation book(s) have occurrences due in this"
                     " period that have not been fired — run the"
                     " depreciation runner before closing.")
        :books (mapv :book with-pending)}]
      [])))

(ns kontor.asset.report
  "kontor-asset Jahresabschluss reports — ADR-056.

   Two pieces:

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

   2. `pending-depreciation-issues` — a `kontor.period/close!`
      `:pre-checks` fn that flags 'you forgot to run depreciation for
      this period' (research note 31 §5.3).

   The roll-forward is per (window, ledger): a depreciation book is
   per-(asset, ledger) (ADR-054), so the roll-forward for the HGB
   book and the Steuerbilanz book are two `asset-roll-forward` calls
   with different `:ledger`s."
  (:require [datahike.api :as d]
            [kontor.schedule :as schedule])
  (:import [java.util Date]))

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

(defn- asset-record
  "Gather one asset's roll-forward inputs: cost, entry-date, the
   earliest disposal/transfer date (if any), and its book's fired
   `:schedule-occurrence` (date, amount) pairs."
  [db asset book sched]
  (let [a (d/pull db [:asset/acquisition-cost :asset/in-service-date
                      :asset/acquisition-date {:asset/class [:db/id]}]
                  asset)
        occ (d/q '[:find ?d ?amt
                   :in $ ?s
                   :where
                   [?o :schedule-occurrence/schedule ?s]
                   [?o :schedule-occurrence/scheduled-date ?d]
                   [?o :schedule-occurrence/amount ?amt]]
                 db sched)
        removal-dates (d/q '[:find [?d ...]
                             :in $ ?asset
                             :where
                             [?e :asset-event/asset ?asset]
                             [?e :asset-event/kind ?kind]
                             [(contains? #{:disposal :transfer} ?kind)]
                             [?e :asset-event/date ?d]]
                           db asset)]
    {:asset        asset
     :book         book
     :class        (:db/id (:asset/class a))
     :cost         (or (:asset/acquisition-cost a) 0M)
     :entry-date   (or (:asset/in-service-date a) (:asset/acquisition-date a))
     :removal-date (when (seq removal-dates) (first (sort removal-dates)))
     :occ          occ}))

(defn- record-contributions
  "Per-asset contributions to the roll-forward buckets over
   `[from, to)`, or nil if the asset is outside the window entirely."
  [{:keys [cost entry-date removal-date occ]} ^Date from ^Date to]
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
      (let [by-class (group-by (fn [[d _]] (window-class d from to)) occ)
            sum (fn [k] (reduce (fn [acc [_ amt]] (.add ^java.math.BigDecimal acc amt))
                                0M (get by-class k)))
            occ-before    (sum :before)
            occ-in-window (sum :in-window)
            occ-before-to (.add ^java.math.BigDecimal occ-before occ-in-window)]
        {:cost-opening      (if entry-before-from? cost 0M)
         :cost-additions    (if entry-in-window? cost 0M)
         :cost-disposals    (if removed-in-window? cost 0M)
         :accum-opening     occ-before
         :accum-period      occ-in-window
         :accum-disposals   (if removed-in-window? occ-before-to 0M)}))))

(defn- bd+ ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.add a b))

(defn- bd- ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (.subtract a b))

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

   Required opts: `:from`, `:to`, `:ledger` (eid).
   Optional: `:group-by` — `:class` (default, group by `:asset/class`)
             or `:none` (one `:all` group)."
  [db {:keys [from to ledger group-by] :or {group-by :class}}]
  (when-not (and from to) (throw (ex-info "asset-roll-forward requires :from and :to" {})))
  (when-not ledger        (throw (ex-info "asset-roll-forward requires :ledger" {})))
  (let [books (d/q '[:find ?asset ?book ?sched
                     :in $ ?ledger
                     :where
                     [?book :asset-depreciation/ledger ?ledger]
                     [?book :asset-depreciation/asset ?asset]
                     [?book :asset-depreciation/schedule ?sched]]
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
;; Pre-close hook — :no-pending-depreciation (research note 31 §5.3)
;; ============================================================================

(defn pending-depreciation-issues
  "A `kontor.period/close!`-compatible `:pre-checks` fn
   `(db period) → [issue …]`. Flags any `:asset-depreciation` book
   whose `:schedule` has an occurrence due within the period window
   `[start, end)` that has NOT been fired — i.e. 'you forgot to run
   the depreciation runner for this period'.

   Compose with `kontor.period/default-pre-close-checks`:

     (kontor.period/close!
       conn period-eid
       {:pre-checks
        (fn [db p]
          (into (kontor.period/default-pre-close-checks db p)
                (kontor.asset.report/pending-depreciation-issues db p)))})

   `period` is the map `close!` passes its pre-checks:
   `{:start Date :end Date …}`. Only `:schedule/state :active` books
   are considered (a `:completed` / `:paused` book never flags)."
  [db {:keys [start end]}]
  (let [book+sched (d/q '[:find ?book ?sched
                          :where [?book :asset-depreciation/schedule ?sched]]
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

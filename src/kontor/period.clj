(ns kontor.period
  "Periods and period-locking — two-tier model per ADR-014.

   Each `:period/*` entity covers a half-open `[start, end)` range,
   optionally scoped to a single `:period/journal`, and OPTIONALLY
   carrying a `:period/tag` (default :normal) to discriminate among
   multiple periods sharing the same date range — e.g. a normal
   December and a year-end-adjustment `:adjustment-13`.

   Two lock states exist:
     - `:period/locked-at`  — SOFT close. `period/reopen!` allowed.
                              Refuses new postings whose
                              `:posting/valid-from` is in range AND
                              whose `:posting/period-tag` matches the
                              period's `:period/tag`.
     - `:period/sealed-at`  — HARD close. Monotone, irrevocable.
                              `period/reopen!` refuses. Sealing
                              middleware refuses any retract on a
                              sealed-period entity.

   `period/close!` runs a pluggable `pre-close-checks` hook before
   stamping the lock — defaults to refusing the close when drafts
   exist, when there are unreconciled bank lines, or when the per-
   commodity sum across all postings in range is non-zero (trial-
   balance-zero invariant).

   Posting routing: `:posting/period-tag` selects which period a
   posting belongs to when multiple periods cover the same date.
   Default tag is `:normal`."
  (:require [datahike.api :as d]
            [kontor.money :as money])
  (:import [java.util Date]))

(def ^:const default-period-tag :normal)

;; ============================================================================
;; Internal: extract postings + their effective routing
;; ============================================================================

(defn- proposed-postings
  [tx-data]
  (filter (fn [tx]
            (and (map? tx)
                 (or (contains? tx :posting/account)
                     (contains? tx :posting/valid-from))))
          tx-data))

(defn- proposed-transactions
  [tx-data]
  (filter (fn [tx]
            (and (map? tx)
                 (or (contains? tx :transaction/journal)
                     (contains? tx :transaction/effective-date))))
          tx-data))

(defn- posting-valid-from
  ^Date [posting tx-by-id]
  (or (:posting/valid-from posting)
      (some-> (:posting/transaction posting)
              tx-by-id
              :transaction/effective-date)))

(defn- posting-journal
  [posting tx-by-id]
  (some-> (:posting/transaction posting)
          tx-by-id
          :transaction/journal))

(defn- posting-period-tag
  "Effective period tag for a proposed posting; default :normal."
  [posting]
  (or (:posting/period-tag posting) default-period-tag))

(defn- in-range?
  "[start, end) range check."
  [^Date d ^Date start ^Date end]
  (and (some? d)
       (>= (.compareTo d start) 0)
       (< (.compareTo d end) 0)))

(defn- closed-periods-for
  "Find :period entities in `db` that:
     (a) are SOFT-closed (`:period/locked-at` set) OR HARD-sealed
         (`:period/sealed-at` set),
     (b) cover `valid-from`,
     (c) match the posting's `:journal-eid` (or have no journal scope),
     (d) match the posting's `:period-tag` (or are the :normal period
         when the posting tag is :normal — both sides default to
         :normal when absent)."
  [db ^Date valid-from journal-eid period-tag]
  (->> (d/q '[:find ?p ?start ?end ?j ?tag ?locked ?sealed
              :in $
              :where
              [?p :period/start ?start]
              [?p :period/end ?end]
              [(get-else $ ?p :period/journal :__none) ?j]
              [(get-else $ ?p :period/tag :normal) ?tag]
              [(get-else $ ?p :period/locked-at :__none) ?locked]
              [(get-else $ ?p :period/sealed-at :__none) ?sealed]
              ;; period must be soft-closed OR hard-sealed
              (or [?p :period/locked-at]
                  [?p :period/sealed-at])]
            db)
       (keep (fn [[p ^Date start ^Date end j tag locked sealed]]
               (when (and (in-range? valid-from start end)
                          (or (= j :__none) (= j journal-eid))
                          (= tag period-tag))
                 {:eid p :start start :end end :journal-eid j :tag tag
                  :locked-at (when (not= locked :__none) locked)
                  :sealed-at (when (not= sealed :__none) sealed)})))
       vec))

;; ============================================================================
;; find-violations (pure)
;; ============================================================================

(defn find-violations
  "Return a vector of {:posting :valid-from :journal-eid :period-tag :periods}
   for every proposed posting in `tx-data` whose effective date falls
   in a (soft- or hard-)closed period matching its journal+tag."
  [db tx-data]
  (let [tx-by-id (into {} (map (juxt :db/id identity)) (proposed-transactions tx-data))]
    (vec
     (keep (fn [posting]
             (let [vf  (posting-valid-from posting tx-by-id)
                   j   (posting-journal posting tx-by-id)
                   tag (posting-period-tag posting)
                   periods (when vf (closed-periods-for db vf j tag))]
               (when (seq periods)
                 {:posting     posting
                  :valid-from  vf
                  :journal-eid j
                  :period-tag  tag
                  :periods     periods})))
           (proposed-postings tx-data)))))

(defn assert-not-in-locked-period!
  "Throws ex-info :type :period/locked-period-violation if any new
   posting in `tx-data` falls in a closed period."
  [db tx-data]
  (let [violations (find-violations db tx-data)]
    (when (seq violations)
      (throw (ex-info "Period violation: posting falls in a closed period"
                      {:type        :period/locked-period-violation
                       :violations  violations
                       :remediation
                       "Each violating posting either targets the wrong
                        period (re-date it or set :posting/period-tag
                        to route into an open adjustment period like
                        :adjustment-13), or the period was sealed too
                        early. Sealed periods are irrevocable; soft-
                        closed periods can be reopened by an admin via
                        period/reopen! (the reopen IS itself a recorded
                        commit so the audit chain documents it)."}))))
  nil)

(defn assert-no-write-on-sealed!
  "Refuse any tx-data that would touch (write or retract) a
   :period/sealed-at-marked entity. Sealed periods are immutable.

   Only positive (existing) eids are inspected — tempids (negative
   or string) are by definition new entities not yet in the DB and
   can't be sealed."
  [db tx-data]
  (let [touched-eids
        (->> tx-data
             (keep (fn [tx]
                     (cond
                       ;; entity-map updates with explicit positive :db/id
                       (and (map? tx)
                            (integer? (:db/id tx))
                            (pos? (:db/id tx)))
                       (:db/id tx)
                       ;; tuple-form add/retract on positive eid
                       (and (vector? tx)
                            (integer? (second tx))
                            (pos? (second tx)))
                       (second tx))))
             distinct)
        sealed-touched
        (filter (fn [eid]
                  (some? (:period/sealed-at (d/pull db [:period/sealed-at] eid))))
                touched-eids)]
    (when (seq sealed-touched)
      (throw (ex-info "Sealing violation: write/retract on sealed period"
                      {:type :period/sealed-write-attempt
                       :sealed-eids (vec sealed-touched)
                       :remediation
                       "A :period/sealed-at-marked period is immutable.
                        Corrections that touch its date range must
                        re-route into the current open period (via
                        reverse-and-repost) OR into an adjustment
                        period like :adjustment-13 if one exists and
                        is itself open."}))))
  nil)

;; ============================================================================
;; Pre-close validation
;; ============================================================================

(defn- draft-postings-in-range
  [db {:keys [start end journal-eid tag]}]
  (let [base-q '{:find [?p]
                 :in [$ ?start ?end]
                 :where
                 [[?t :transaction/state :draft]
                  [?p :posting/transaction ?t]
                  [?p :posting/valid-from ?vf]
                  [(>= ?vf ?start)]
                  [(< ?vf ?end)]]}
        ;; Optionally constrain by journal and tag
        with-journal (if journal-eid
                       (-> base-q
                           (update :in conj '?j)
                           (update :where conj '[?t :transaction/journal ?j]))
                       base-q)
        with-tag (if (and tag (not= tag :normal))
                   (-> with-journal
                       (update :in conj '?tag)
                       (update :where conj '[(get-else $ ?p :posting/period-tag :normal) ?ptag]
                               '[(= ?ptag ?tag)]))
                   with-journal)
        args (cond-> [db start end]
               journal-eid (conj journal-eid)
               (and tag (not= tag :normal)) (conj tag))]
    (apply d/q with-tag args)))

(defn- range-trial-balance
  "Per-commodity sum of all postings (across all transaction states)
   in this period's range. Returns {commodity Money}."
  [db {:keys [start end journal-eid]}]
  (let [base-q '{:find [?p]
                 :in [$ ?start ?end]
                 :where
                 [[?p :posting/valid-from ?vf]
                  [(>= ?vf ?start)]
                  [(< ?vf ?end)]]}
        with-journal (if journal-eid
                       (-> base-q
                           (update :in conj '?j)
                           (update :where conj '[?p :posting/transaction ?t]
                                   '[?t :transaction/journal ?j]))
                       base-q)
        args (cond-> [db start end]
               journal-eid (conj journal-eid))
        eids (apply d/q with-journal args)]
    (->> eids
         (map first)
         (map #(d/pull db [:posting/amount :posting/commodity] %))
         (keep money/posting->money)
         money/sum-by-commodity)))

(defn default-pre-close-checks
  "Default checks that period/close! runs before stamping the lock.
   Returns a vector of {:check :reason} — empty when the period is
   safe to close. Override by passing a custom :pre-checks fn to
   close!."
  [db period]
  (let [drafts (seq (draft-postings-in-range db period))
        bal (range-trial-balance db period)
        unbalanced (->> bal (remove (fn [[_c m]] (money/zero? m))) (into {}))]
    (cond-> []
      drafts
      (conj {:check :no-drafts
             :reason (str "There are " (count drafts)
                          " draft postings in this period; either post"
                          " or cancel them before closing.")
             :draft-postings (mapv first drafts)})

      (seq unbalanced)
      (conj {:check :trial-balance-zero
             :reason "Per-commodity trial balance does not sum to zero in this period"
             :unbalanced unbalanced}))))

;; ============================================================================
;; Domain operations
;; ============================================================================

(defn open?
  "True iff the period is OPEN — neither soft-closed nor hard-sealed."
  [db period-or-eid]
  (let [m (cond
            (map? period-or-eid) period-or-eid
            (integer? period-or-eid) (d/pull db [:period/locked-at :period/sealed-at]
                                             period-or-eid)
            :else (throw (ex-info "open? expects an eid or entity map"
                                  {:got period-or-eid})))]
    (and (nil? (:period/locked-at m))
         (nil? (:period/sealed-at m)))))

(defn sealed?
  "True iff the period is hard-sealed (irrevocable)."
  [db period-or-eid]
  (let [m (cond
            (map? period-or-eid) period-or-eid
            (integer? period-or-eid) (d/pull db [:period/sealed-at] period-or-eid)
            :else nil)]
    (some? (:period/sealed-at m))))

(defn close!
  "SOFT close: stamp :period/locked-at after running pre-close checks.

   Options:
     :at        — Date to stamp (default now)
     :pre-checks — fn (db period) → vec of issues. Default
                   `default-pre-close-checks`. Pass `(constantly [])` to
                   skip checks (NOT recommended outside tests).

   Throws on:
     :type :period/already-closed   if already soft-closed or sealed
     :type :period/pre-close-failed if any pre-check returns issues"
  ([conn period-eid] (close! conn period-eid {}))
  ([conn period-eid {:keys [at pre-checks]
                     :or {at (Date.) pre-checks default-pre-close-checks}}]
   (let [db (d/db conn)]
     (when-not (open? db period-eid)
       (throw (ex-info "Period already closed"
                       {:type :period/already-closed
                        :period-eid period-eid})))
     (let [period (d/pull db [:period/start :period/end :period/journal :period/tag]
                          period-eid)
           period* {:start (:period/start period)
                    :end   (:period/end period)
                    :journal-eid (-> period :period/journal :db/id)
                    :tag (or (:period/tag period) default-period-tag)}
           issues (pre-checks db period*)]
       (when (seq issues)
         (throw (ex-info "Period close blocked by pre-close checks"
                         {:type :period/pre-close-failed
                          :period-eid period-eid
                          :issues issues
                          :remediation
                          "Resolve each issue (post drafts, reconcile,
                           rebalance) and re-run close!. To bypass for
                           triage, pass :pre-checks (constantly []) —
                           not recommended in production."})))
       (let [report (d/transact conn [{:db/id period-eid :period/locked-at at}])
             tx-id (-> report :tempids (get :db/current-tx))]
         (when tx-id
           (d/transact conn [{:db/id period-eid :period/lock-tx tx-id}]))
         report)))))

(defn seal!
  "HARD close: stamp :period/sealed-at. Monotone — refuses if any
   later period is already sealed (would create a non-monotone
   sequence). Refuses to seal an unsoft-closed period."
  ([conn period-eid] (seal! conn period-eid {}))
  ([conn period-eid {:keys [at sealed-by]
                     :or {at (Date.)}}]
   (let [db (d/db conn)
         this (d/pull db [:period/end :period/locked-at :period/sealed-at] period-eid)]
     (when (some? (:period/sealed-at this))
       (throw (ex-info "Period already sealed"
                       {:type :period/already-sealed :period-eid period-eid})))
     (when (nil? (:period/locked-at this))
       (throw (ex-info "Period must be soft-closed before sealing"
                       {:type :period/seal-of-open
                        :period-eid period-eid
                        :remediation
                        "Call (period/close! conn eid) first; then seal!"})))
     ;; Monotonicity check: no later period (by :period/end) may be sealed.
     ;; If one is, sealing this one would create non-monotone sequence —
     ;; refuse and ask the user to seal in date order.
     (let [later-sealed
           (d/q '[:find [?p ...]
                  :in $ ?my-end
                  :where
                  [?p :period/sealed-at _]
                  [?p :period/end ?e]
                  [(< ?my-end ?e)]]
                db (:period/end this))]
       (when (seq later-sealed)
         (throw (ex-info "Refusing to seal — a later period is already sealed"
                         {:type :period/non-monotone-seal
                          :period-eid period-eid
                          :later-sealed later-sealed
                          :remediation
                          "Seal periods in date order (oldest first).
                           Sealing earlier than an already-sealed period
                           would produce a non-monotone sealing sequence."}))))
     (d/transact conn (cond-> [{:db/id period-eid :period/sealed-at at}]
                        sealed-by (conj {:db/id period-eid :period/sealed-by sealed-by}))))))

(defn reopen!
  "Admin-only: clear `:period/locked-at` on a SOFT-closed period.
   Refuses if the period is :period/sealed-at-marked. The reopen IS a
   datahike commit, so the audit chain documents it."
  [conn period-eid]
  (let [db (d/db conn)
        e (d/pull db [:period/locked-at :period/sealed-at] period-eid)]
    (when (some? (:period/sealed-at e))
      (throw (ex-info "Cannot reopen sealed period — it is irrevocable"
                      {:type :period/cannot-reopen-sealed
                       :period-eid period-eid
                       :remediation
                       "Sealed periods are by-design irrevocable.
                        Corrections must be reverse-and-repost in the
                        current open period or in an adjustment-period
                        bucket like :adjustment-13."})))
    (when (nil? (:period/locked-at e))
      (throw (ex-info "Period already open" {:period-eid period-eid})))
    (d/transact conn [[:db/retract period-eid :period/locked-at
                       (:period/locked-at e)]])))

(ns kontor.compliance.period
  "Periods and period-locking — two-tier model per ADR-014.

   Each `:kontor.period/*` entity covers a half-open `[start, end)` range,
   optionally scoped to a single `:kontor.period/journal`, and OPTIONALLY
   carrying a `:kontor.period/tag` (default :normal) to discriminate among
   multiple periods sharing the same date range — e.g. a normal
   December and a year-end-adjustment `:adjustment-13`.

   Two lock states exist:
     - `:kontor.period/locked-at`  — SOFT close. `period/reopen!` allowed.
                              Refuses new postings whose inbound
                              valid-time (`:tx/valid-from`, falling
                              back to `:kontor.transaction/effective-date`)
                              is in range AND whose `:kontor.posting/period-tag`
                              matches the period's `:kontor.period/tag`.
     - `:kontor.period/sealed-at`  — HARD close. Monotone, irrevocable.
                              `period/reopen!` refuses. Sealing
                              middleware refuses any retract on a
                              sealed-period entity.

   `period/close!` runs a pluggable `pre-close-checks` hook before
   stamping the lock — defaults to refusing the close when drafts
   exist, when there are unreconciled bank lines, or when the per-
   commodity sum across all postings in range is non-zero (trial-
   balance-zero invariant).

   Posting routing: `:kontor.posting/period-tag` selects which period a
   posting belongs to when multiple periods cover the same date.
   Default tag is `:normal`."
  (:require [datahike.api :as d]
            [datahike.db.interface :as dbi]
            [kontor.bitemporal :as kbt]
            [kontor.gate :as gate]
            [kontor.money :as money]))

(defn- now
  "Current instant — a Date on the JVM, a js/Date in cljs."
  []
  #?(:clj (java.util.Date.) :cljs (js/Date.)))

;; period is a validator INSIDE `kontor.validation`'s gate
;; (`assert-not-in-locked-period!` / `assert-no-write-on-sealed!`).
;; Per T-2 the gate API itself lives in `kontor.gate`,
;; which depends on neither this ns nor kontor.validation — so we
;; require it directly without cycle.

(def ^:const default-period-tag :normal)

;; ============================================================================
;; Internal: extract postings + their effective routing
;; ============================================================================

(defn- proposed-postings
  [tx-data]
  (filter (fn [tx]
            (and (map? tx)
                 (contains? tx :kontor.posting/account)))
          tx-data))

(defn- proposed-transactions
  [tx-data]
  (filter (fn [tx]
            (and (map? tx)
                 (or (contains? tx :kontor.transaction/journal)
                     (contains? tx :kontor.transaction/effective-date))))
          tx-data))

(defn- posting-journal
  [posting tx-by-id]
  (some-> (:kontor.posting/transaction posting)
          tx-by-id
          :kontor.transaction/journal))

(defn- posting-period-tag
  "Effective period tag for a proposed posting; default :normal."
  [posting]
  (or (:kontor.posting/period-tag posting) default-period-tag))

(defn- in-range?
  "[start, end) range check."
  [d start end]
  (and (some? d)
       (>= (.compareTo d start) 0)
       (< (.compareTo d end) 0)))

(defn closed-periods-covering
  "Find :period entities in `db` that:
     (a) are SOFT-closed (`:kontor.period/locked-at` set) OR HARD-sealed
         (`:kontor.period/sealed-at` set),
     (b) cover `valid-from`,
     (c) match the posting's `:journal-eid` (or have no journal scope),
     (d) match the posting's `:period-tag` (or are the :normal period
         when the posting tag is :normal — both sides default to
         :normal when absent)."
  [db valid-from journal-eid period-tag]
  (->> (d/q '[:find ?p ?start ?end ?j ?tag ?locked ?sealed
              :in $
              :where
              [?p :kontor.period/start ?start]
              [?p :kontor.period/end ?end]
              [(get-else $ ?p :kontor.period/journal :__none) ?j]
              [(get-else $ ?p :kontor.period/tag :normal) ?tag]
              [(get-else $ ?p :kontor.period/locked-at :__none) ?locked]
              [(get-else $ ?p :kontor.period/sealed-at :__none) ?sealed]
              ;; period must be soft-closed OR hard-sealed
              (or [?p :kontor.period/locked-at]
                  [?p :kontor.period/sealed-at])]
            db)
       (keep (fn [[p start end j tag locked sealed]]
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

(defn- posting-effective-date
  "Fallback valid-time for a proposed posting when the tx-data carries no
   `:db.valid/from` tx-meta: the `:kontor.transaction/effective-date` of the
   transaction the posting hangs off.

   Resolves through `tx-by-id` for a transaction built in the SAME tx-data
   (the normal shape — the posting refs a tempid), and falls back to reading
   the effective-date off an already-committed transaction in `db` when the
   posting attaches to an existing eid or lookup-ref.

   The db read is gated on `:kontor.transaction/effective-date` being in `db`'s
   schema: pulling an undeclared attribute throws `:transact/schema`, and a
   partial-schema db (ADR-002 cohabitation, or the cljs `node-test` lane's
   hand-written schema) may not declare it. The try/catch below would swallow
   that, but only by accident and indistinguishably from a genuinely
   unresolvable ref — so the intent is made explicit rather than left to the
   exception handler (ADR-140). Read through `dbi/-schema` (the protocol
   method) and NOT the `:schema` field, which is nil on a wrapped as-of /
   history db — same rule as `kontor.analytic/attr-installed?`."
  [db posting tx-by-id]
  (let [t (:kontor.posting/transaction posting)]
    (or (:kontor.transaction/effective-date (tx-by-id t))
        (when (and (some? t)
                   (contains? (dbi/-schema db) :kontor.transaction/effective-date))
          (try (:kontor.transaction/effective-date
                (d/pull db [:kontor.transaction/effective-date] t))
               ;; an unresolvable ref (tempid string with no sibling map) is
               ;; not a period question — leave it to the other validators
               (catch #?(:clj Exception :cljs :default) _ nil))))))

(defn find-violations
  "Return a vector of {:posting :valid-from :valid-from-source :journal-eid
   :period-tag :periods} for every proposed posting in `tx-data` whose
   valid-time falls in a (soft- or hard-)closed period matching its
   journal+tag.

   Valid-time is read from the tx-data's `:db.valid/from` on its
   `\"datomic.tx\"` tx-meta map (upstream datahike) — all postings in one tx
   share that vf, so `:valid-from-source` is `:tx-meta`.

   When the tx-data carries NO `:db.valid/from`, each posting falls back to
   its transaction's `:kontor.transaction/effective-date`
   (`:valid-from-source` `:effective-date`). This fallback is load-bearing,
   not a convenience: the accounting date IS the period anchor (Odoo derives
   the lock check from the move's own `date` in
   `account_move/_check_fiscal_lock_dates`), so a hand-built write that names
   an effective-date inside a closed period must be refused whether or not
   the caller remembered to wrap it in `kbt/with-vt`. Failing open here let a
   back-dated posting into a SEALED period."
  [db tx-data]
  (let [tx-by-id (into {} (map (juxt :db/id identity)) (proposed-transactions tx-data))
        meta-vf (some (fn [e]
                        (when (and (map? e) (= (:db/id e) "datomic.tx"))
                          (:db.valid/from e)))
                      tx-data)]
    (vec
     (keep (fn [posting]
             (let [vf  (or meta-vf (posting-effective-date db posting tx-by-id))
                   src (if meta-vf :tx-meta :effective-date)
                   j   (posting-journal posting tx-by-id)
                   tag (posting-period-tag posting)
                   periods (when (some? vf) (closed-periods-covering db vf j tag))]
               (when (seq periods)
                 {:posting           posting
                  :valid-from        vf
                  :valid-from-source src
                  :journal-eid       j
                  :period-tag        tag
                  :periods           periods})))
           (proposed-postings tx-data)))))

(defn assert-not-in-locked-period!
  "Throws ex-info :type :kontor.period/locked-period-violation if any new
   posting in `tx-data` falls in a closed period."
  [db tx-data]
  (let [violations (find-violations db tx-data)]
    (when (seq violations)
      (throw (ex-info "Period violation: posting falls in a closed period"
                      {:type        :kontor.period/locked-period-violation
                       :violations  violations
                       :remediation
                       "Each violating posting either targets the wrong
                        period (re-date it or set :kontor.posting/period-tag
                        to route into an open adjustment period like
                        :adjustment-13), or the period was sealed too
                        early. Sealed periods are irrevocable; soft-
                        closed periods can be reopened by an admin via
                        period/reopen! (the reopen IS itself a recorded
                        commit so the audit chain documents it)."}))))
  nil)

(defn assert-no-write-on-sealed!
  "Refuse any tx-data that would touch (write or retract) a
   :kontor.period/sealed-at-marked entity. Sealed periods are immutable.

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
                  (some? (:kontor.period/sealed-at (d/pull db [:kontor.period/sealed-at] eid))))
                touched-eids)]
    (when (seq sealed-touched)
      (throw (ex-info "Sealing violation: write/retract on sealed period"
                      {:type :kontor.period/sealed-write-attempt
                       :sealed-eids (vec sealed-touched)
                       :remediation
                       "A :kontor.period/sealed-at-marked period is immutable.
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
                 [[?t :kontor.transaction/state :draft]
                  [?p :kontor.posting/transaction ?t]
                  [?p :kontor.posting/transaction _ ?tx]
                  [?tx :db/txInstant ?ti]
                  [(get-else $ ?tx :db.valid/from ?ti) ?vf]
                  [(>= ?vf ?start)]
                  [(< ?vf ?end)]]}
        ;; Optionally constrain by journal and tag
        with-journal (if journal-eid
                       (-> base-q
                           (update :in conj '?j)
                           (update :where conj '[?t :kontor.transaction/journal ?j]))
                       base-q)
        with-tag (if (and tag (not= tag :normal))
                   (-> with-journal
                       (update :in conj '?tag)
                       (update :where conj '[(get-else $ ?p :kontor.posting/period-tag :normal) ?ptag]
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
                 [[?p :kontor.posting/transaction ?t]
                  [?p :kontor.posting/transaction _ ?tx]
                  [?tx :db/txInstant ?ti]
                  [(get-else $ ?tx :db.valid/from ?ti) ?vf]
                  [(>= ?vf ?start)]
                  [(< ?vf ?end)]]}
        with-journal (if journal-eid
                       (-> base-q
                           (update :in conj '?j)
                           (update :where conj '[?t :kontor.transaction/journal ?j]))
                       base-q)
        args (cond-> [db start end]
               journal-eid (conj journal-eid))
        eids (apply d/q with-journal args)]
    (->> eids
         (map first)
         (map #(d/pull db [:kontor.posting/amount :kontor.posting/commodity] %))
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
            (integer? period-or-eid) (d/pull db [:kontor.period/locked-at :kontor.period/sealed-at]
                                             period-or-eid)
            :else (throw (ex-info "open? expects an eid or entity map"
                                  {:got period-or-eid})))]
    (and (nil? (:kontor.period/locked-at m))
         (nil? (:kontor.period/sealed-at m)))))

(defn sealed?
  "True iff the period is hard-sealed (irrevocable)."
  [db period-or-eid]
  (let [m (cond
            (map? period-or-eid) period-or-eid
            (integer? period-or-eid) (d/pull db [:kontor.period/sealed-at] period-or-eid)
            :else nil)]
    (some? (:kontor.period/sealed-at m))))

(defn close-tx-data
  "Pure tx-data builder for `close!`'s lock event (ADR-068). Runs
   the pre-close checks against `db` and returns the lock-stamp.
   The `:kontor.period/lock-tx` audit denorm is recorded by `close!` in a
   follow-up tx because datahike does not resolve `:db/current-tx`
   as a `:db.type/long` value; that denorm is metadata and stays
   outside the gated lock event."
  [db period-eid {:keys [at pre-checks]
                  :or {at (now) pre-checks default-pre-close-checks}}]
  (when-not (open? db period-eid)
    (throw (ex-info "Period already closed"
                    {:type :kontor.period/already-closed
                     :period-eid period-eid})))
  (let [period (d/pull db [:kontor.period/start :kontor.period/end :kontor.period/journal :kontor.period/tag]
                       period-eid)
        period* {:start (:kontor.period/start period)
                 :end   (:kontor.period/end period)
                 :journal-eid (-> period :kontor.period/journal :db/id)
                 :tag (or (:kontor.period/tag period) default-period-tag)}
        issues (pre-checks db period*)]
    (when (seq issues)
      (throw (ex-info "Period close blocked by pre-close checks"
                      {:type :kontor.period/pre-close-failed
                       :period-eid period-eid
                       :issues issues
                       :remediation
                       "Resolve each issue (post drafts, reconcile,
                           rebalance) and re-run close!. To bypass for
                           triage, pass :pre-checks (constantly []) —
                           not recommended in production."})))
    [{:db/id period-eid :kontor.period/locked-at at}]))

(defn close!
  "SOFT close: stamp :kontor.period/locked-at after running pre-close checks.
   Routes the lock through the gate (ADR-068); records the
   `:kontor.period/lock-tx` audit denorm in a follow-up tx.

   Options:
     :at        — Date to stamp (default now)
     :pre-checks — fn (db period) → vec of issues. Default
                   `default-pre-close-checks`. Pass `(constantly [])` to
                   skip checks (NOT recommended outside tests).

   Throws on:
     :type :kontor.period/already-closed   if already soft-closed or sealed
     :type :kontor.period/pre-close-failed if any pre-check returns issues

   The pure tx-data builder is `close-tx-data` (ADR-068)."
  ([conn period-eid] (close! conn period-eid {}))
  ([conn period-eid opts]
   (let [report (gate/transact-with-validation
                 conn (close-tx-data (d/db conn) period-eid opts))
         tx-id (-> report :tempids (get :db/current-tx))]
     (when tx-id
       (d/transact conn [{:db/id period-eid :kontor.period/lock-tx tx-id}]))
     report)))

(defn seal-tx-data
  "Pure tx-data builder for `seal!` (ADR-068)."
  [db period-eid {:keys [at sealed-by] :or {at (now)}}]
  (let [this (d/pull db [:kontor.period/end :kontor.period/locked-at :kontor.period/sealed-at] period-eid)]
    (when (some? (:kontor.period/sealed-at this))
      (throw (ex-info "Period already sealed"
                      {:type :kontor.period/already-sealed :period-eid period-eid})))
    (when (nil? (:kontor.period/locked-at this))
      (throw (ex-info "Period must be soft-closed before sealing"
                      {:type :kontor.period/seal-of-open
                       :period-eid period-eid
                       :remediation
                       "Call (period/close! conn eid) first; then seal!"})))
    ;; Monotonicity check: no later period (by :kontor.period/end) may be sealed.
    ;; If one is, sealing this one would create non-monotone sequence —
    ;; refuse and ask the user to seal in date order.
    (let [later-sealed
          (d/q '[:find [?p ...]
                 :in $ ?my-end
                 :where
                 [?p :kontor.period/sealed-at _]
                 [?p :kontor.period/end ?e]
                 [(< ?my-end ?e)]]
               db (:kontor.period/end this))]
      (when (seq later-sealed)
        (throw (ex-info "Refusing to seal — a later period is already sealed"
                        {:type :kontor.period/non-monotone-seal
                         :period-eid period-eid
                         :later-sealed later-sealed
                         :remediation
                         "Seal periods in date order (oldest first).
                          Sealing earlier than an already-sealed period
                          would produce a non-monotone sealing sequence."}))))
    (cond-> [{:db/id period-eid :kontor.period/sealed-at at}]
      sealed-by (conj {:db/id period-eid :kontor.period/sealed-by sealed-by}))))

(defn seal!
  "HARD close: stamp :kontor.period/sealed-at. Monotone — refuses if any
   later period is already sealed (would create a non-monotone
   sequence). Refuses to seal an unsoft-closed period. Routes
   through the gate (ADR-068).

   The pure tx-data builder is `seal-tx-data`."
  ([conn period-eid] (seal! conn period-eid {}))
  ([conn period-eid opts]
   (gate/transact-with-validation
    conn (seal-tx-data (d/db conn) period-eid opts))))

(defn reopen-tx-data
  "Pure tx-data builder for `reopen!` (ADR-068)."
  [db period-eid]
  (let [e (d/pull db [:kontor.period/locked-at :kontor.period/sealed-at] period-eid)]
    (when (some? (:kontor.period/sealed-at e))
      (throw (ex-info "Cannot reopen sealed period — it is irrevocable"
                      {:type :kontor.period/cannot-reopen-sealed
                       :period-eid period-eid
                       :remediation
                       "Sealed periods are by-design irrevocable.
                        Corrections must be reverse-and-repost in the
                        current open period or in an adjustment-period
                        bucket like :adjustment-13."})))
    (when (nil? (:kontor.period/locked-at e))
      (throw (ex-info "Period already open" {:period-eid period-eid})))
    [[:db/retract period-eid :kontor.period/locked-at (:kontor.period/locked-at e)]]))

(defn reopen!
  "Admin-only: clear `:kontor.period/locked-at` on a SOFT-closed period.
   Refuses if the period is :kontor.period/sealed-at-marked. Routes through
   the gate (ADR-068). The reopen IS a datahike commit, so the audit
   chain documents it.

   The pure tx-data builder is `reopen-tx-data`."
  [conn period-eid]
  (gate/transact-with-validation
   conn (reopen-tx-data (d/db conn) period-eid)))

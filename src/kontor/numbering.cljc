(ns kontor.numbering
  "Gapless per-journal legal document numbering — ADR-151.

   ## The defect this closes

   In DE (GoBD / §14 UStG), FR (NF525), IT, ES, PT, BR, IN and MX, a
   gapless, per-journal, immutable document number is a LEGAL CONDITION of
   issuing an invoice, not a feature. kontor shipped
   `:kontor.journal/sequence-prefix` — a bare string no code read — and the
   legal number landed in caller-supplied `:kontor.transaction/external-id`.
   Nothing allocated a number, nothing detected a hole, and nothing stopped
   two entries claiming the same one. The nominated consumer (beleg) does
   not solve it either: its `:invoice/number` is a free-text field a human
   types into a web form and can edit after issue. So nobody owned it.

   ## How allocation is made safe

   A gapless counter is a read-modify-write, which is exactly the shape that
   goes wrong under concurrency. Two designs were available:

   1. **Allocate eagerly, before `d/transact`.** Read `last-sequence`,
      compute `n+1`, put it in the tx-data, transact. This LOSES UPDATES:
      two threads that read the same `n` both write `n+1`, and because
      `:kontor.transaction/external-id` is `:db.unique/identity`, the second
      does not even fail — it UPSERTS onto the first, sealed entry.

   2. **Allocate inside the transaction.** kontor's gate already commits
      every kernel write as `[[:db.fn/call validate-and-apply tx-data]]`
      (`kontor.gate`), and datahike invokes a `:db.fn/call` fn with the
      in-transaction db and splices its return value into the same
      transaction (`datahike/src/datahike/db/transaction.cljc:1170`). So the
      counter read and the counter write happen in one atomic unit.

   Design 2 is what ships, and datahike's writer is what makes it airtight.
   A `LocalWriter` owns ONE transaction thread with ONE queue and processes
   invocations in a `loop` that recurs on the previous transaction's
   `:db-after` (`datahike/src/datahike/writer.cljc:44-114`, esp. the
   `(recur (:db-after res))` at :111). Transactions against a connection are
   therefore strictly serialized, and each `:db.fn/call` sees the chained
   db — including transactions applied but not yet committed. Verified
   empirically for this design: 8 threads × 25 read-increment-writes through
   `:db.fn/call` on one counter produced 200 increments and 200 DISTINCT
   values, gapless.

   Serialization is what carries the guarantee; the counter write is
   additionally a **`:db/cas`** against the value that was read
   (`datahike/src/datahike/db/transaction.cljc:976-998`, which throws
   `:transact/cas` and aborts the whole transaction on mismatch — with an
   `ov` of nil meaning \"assert no counter datom exists yet\", so the very
   first allocation is covered too). The cas is defence in depth against the
   two ways this could still go wrong:

   - **Reading the wrong db.** The value MUST come from the db the
     transaction fn is handed, never from `@conn`: the connection atom is
     reset only by the lagging commit loop
     (`datahike/src/datahike/writer.cljc:143`), so `@conn` trails the writer
     chain by the commit-queue depth. An allocator that read `@conn` would
     hand out duplicates; with the cas it instead fails loudly. (Measured:
     4 threads × 20 CAS attempts sourced from `@conn` → 60 of 80 threw.)
   - **A future writer that parallelizes**, or a caller who invokes
     [[allocate]] outside a transaction. Both silently duplicate a legal
     number under design 1; here the loser's transaction aborts and no
     number is consumed.

   Note that datahike does NOT support two writer processes on one store at
   all — there is no head fencing, so concurrent branch-head flips are
   last-writer-wins (`datahike/src/datahike/gc_guard.cljc:36-42`,
   `writing.cljc:290-301`). Gapless numbering inherits that constraint: run
   exactly one `:writer {:backend :self}` and point other processes at it.

   ## Why the result is genuinely gapless

   Odoo distinguishes `standard` (fast, gaps allowed) from `no_gap` (a row
   lock per allocation) precisely because in a SQL ERP the number is
   allocated in one transaction and consumed in another, so a rollback burns
   it. Here allocation and consumption are the SAME transaction: if the
   entry does not commit, neither does the increment. There is no
   \"reserved but unused\" state to leak a hole from, and no lock to hold.

   ## The one hole that can still appear

   `:db/purge` (ADR-007) legitimately removes a posted entry, and that
   leaves a real hole in the series. That is the case [[sequence-gaps]]
   exists to surface: a purge is an auditable event and the auditor must be
   able to see its consequence in the numbering, not have it hidden.

   ## Turning it on

       (numbering/configure-journal! conn [:kontor.journal/code \"SJ\"]
                                     {:prefix \"RE/{year}/\"
                                      :reset  :yearly
                                      :padding 4})

   From then on every entry sealed in that journal gets
   `:kontor.transaction/sequence-number` (the authoritative ordinal),
   `:kontor.transaction/sequence-key` (the reset bucket) and — when the
   caller supplied none — an `:kontor.transaction/external-id` rendered from
   the prefix. Opt-in per journal: an internal accrual journal wants no
   legal series."
  (:require [clojure.string :as str]
            [datahike.api :as d]))

;; ============================================================================
;; Reset buckets
;; ============================================================================

(def default-reset
  "Reset cadence when a journal does not say. `:yearly` — what DE / FR / IT
   / ES / PT / BR require and what every reference ERP defaults to."
  :yearly)

(def default-padding 4)

(defn- utc-year-month
  "`[year month]` of a `#inst`, in UTC. UTC (not the local zone) so the
   bucket a number falls into cannot depend on which server allocated it."
  [d]
  #?(:clj  (let [zdt (-> (.toInstant ^java.util.Date d)
                         (.atZone java.time.ZoneOffset/UTC))]
             [(.getYear zdt) (.getMonthValue zdt)])
     :cljs [(.getUTCFullYear d) (inc (.getUTCMonth d))]))

(defn bucket-key
  "The reset bucket `date` falls into under `reset`:

     :never   → \"\"
     :yearly  → \"2026\"
     :monthly → \"2026-03\"

   Derived from the entry's `:kontor.transaction/effective-date` — the
   DOCUMENT date, not the wall clock. Backdating an invoice into last
   December must draw from last December's series, or the series is not the
   series the tax authority audits."
  [reset date]
  (let [reset (or reset default-reset)]
    (if (= :never reset)
      ""
      (let [[y m] (utc-year-month date)]
        (case reset
          :yearly  (str y)
          :monthly (str y "-" (when (< m 10) "0") m)
          (throw (ex-info (str "kontor.numbering: unknown :kontor.journal/sequence-reset " reset
                               " — expected :never, :yearly or :monthly")
                          {:type :kontor.numbering/unknown-reset :reset reset})))))))

;; ============================================================================
;; Rendering
;; ============================================================================

(defn- pad [n width]
  (let [s (str n)
        k (- (or width default-padding) (count s))]
    (if (pos? k) (str (str/join (repeat k "0")) s) s)))

(defn render
  "Render the human-readable legal number from a journal's prefix template,
   the allocated ordinal and the entry's date.

   `{year}` and `{month}` in the template are substituted from `date`; a
   template with no placeholder is used verbatim as a prefix. An absent
   prefix falls back to the journal's `:kontor.journal/code` plus a slash,
   so a journal that enables allocation without configuring a template
   still produces a distinguishable series rather than a bare integer."
  [{:keys [prefix code padding]} ordinal date]
  (let [[y m] (utc-year-month date)
        tmpl  (or prefix (str code "/{year}/"))]
    (str (-> tmpl
             (str/replace "{year}" (str y))
             (str/replace "{month}" (str (when (< m 10) "0") m)))
         (pad ordinal padding))))

;; ============================================================================
;; Journal configuration
;; ============================================================================

(defn configure-journal-tx-data
  "Pure tx-data enabling gapless allocation on `journal` (an eid or
   lookup-ref).

   Options: `:prefix` (template, `{year}` / `{month}` substituted),
   `:reset` (`:never` / `:yearly` / `:monthly`), `:padding`,
   `:enabled?` (default true — pass false to switch allocation off without
   losing the counter)."
  [journal {:keys [prefix reset padding enabled?] :or {enabled? true}}]
  [(cond-> {:db/id journal
            :kontor.journal/auto-sequence (boolean enabled?)}
     prefix  (assoc :kontor.journal/sequence-prefix prefix)
     reset   (assoc :kontor.journal/sequence-reset reset)
     padding (assoc :kontor.journal/sequence-padding padding))])

(defn configure-journal!
  "Enable gapless allocation on a journal. See
   [[configure-journal-tx-data]] for the options."
  [conn journal opts]
  (d/transact conn (configure-journal-tx-data journal opts)))

(defn journal-config
  "The numbering configuration of `journal` as a plain map, or nil when the
   journal does not exist."
  [db journal]
  (when-let [j (try (d/pull db [:db/id :kontor.journal/code
                                :kontor.journal/auto-sequence
                                :kontor.journal/sequence-prefix
                                :kontor.journal/sequence-reset
                                :kontor.journal/sequence-padding
                                :kontor.journal/last-sequence
                                :kontor.journal/last-sequence-key]
                            journal)
                    (catch #?(:clj Exception :cljs :default) _ nil))]
    (when (:db/id j)
      {:eid      (:db/id j)
       :code     (:kontor.journal/code j)
       :enabled? (true? (:kontor.journal/auto-sequence j))
       :prefix   (:kontor.journal/sequence-prefix j)
       :reset    (or (:kontor.journal/sequence-reset j) default-reset)
       :padding  (or (:kontor.journal/sequence-padding j) default-padding)
       :last     (:kontor.journal/last-sequence j)
       :last-key (:kontor.journal/last-sequence-key j)})))

(defn next-number
  "ADVISORY preview of the number `journal` would allocate for an entry
   dated `date` — for a UI that wants to show \"next: RE/2026/0007\".

   It is a preview and NOT a reservation: the authoritative allocation
   happens inside the transaction, so a concurrent post can take this
   number first. Never store this value; store what the commit returned."
  [db journal date]
  (when-let [{:keys [enabled? reset last last-key] :as cfg} (journal-config db journal)]
    (when enabled?
      (let [k (bucket-key reset date)
            n (if (= k last-key) (inc (or last 0)) 1)]
        {:sequence-number n
         :sequence-key    k
         :external-id     (render cfg n date)}))))

;; ============================================================================
;; The allocator — runs INSIDE the transaction
;; ============================================================================

(defn- transaction-map?
  "True for an entity-map in `tx-data` that is a sealed `:transaction`
   needing a number: it seals (carries `:kontor.transaction/posted-at`),
   names a journal, and has no ordinal yet."
  [m]
  (and (map? m)
       (contains? m :kontor.transaction/posted-at)
       (contains? m :kontor.transaction/journal)
       (not (contains? m :kontor.transaction/sequence-number))))

(defn- resolve-journal-eid
  [db journal]
  (cond
    (and (integer? journal) (pos? journal)) journal
    (vector? journal) (try (:db/id (d/entity db journal))
                           (catch #?(:clj Exception :cljs :default) _ nil))
    :else nil))

(defn- external-id-taken?
  [db xid]
  (some? (d/q '[:find ?t . :in $ ?x
                :where [?t :kontor.transaction/external-id ?x]]
              db xid)))

(defn allocate
  "Allocate gapless legal numbers for every sealed transaction in `tx-data`
   whose journal has `:kontor.journal/auto-sequence` true, returning the
   augmented tx-data. Pure over `(db, tx-data)`.

   **This must run inside the transaction.** It is composed into
   `kontor.validation/validate-and-apply`, which the gate invokes as
   `[:db.fn/call …]`, so `db` here is the in-transaction db and the counter
   increments it emits commit atomically with the entry. Calling it outside a
   transaction reintroduces the lost update it exists to prevent.

   For each such transaction it emits:
     - `:kontor.transaction/sequence-number` — the ordinal (authoritative)
     - `:kontor.transaction/sequence-key`    — the reset bucket
     - `:kontor.transaction/external-id`     — the rendered number, ONLY when
       the caller supplied none. A caller-supplied external-id is that
       caller's foreign key (a beleg invoice UUID) and is left alone; the
       legal ordinal is still allocated either way.
     - `[:db/cas journal :kontor.journal/last-sequence <read> <new>]` plus the
       new bucket key — see the ns docstring for why `:db/cas`.

   Several entries sealed in the same journal in ONE tx-data get consecutive
   numbers: the fold threads the counter through, so the `:db/cas` forms
   chain (n→n+1, n+1→n+2) instead of all claiming the same old value.

   REFUSES an entry dated into a bucket the journal has already moved past
   (`:kontor.numbering/sequence-bucket-closed`). Backdating an invoice into
   last year AFTER this year's series has started would otherwise restart at
   1 and hand out a number last year already used — a duplicate legal number,
   which is worse than the missing feature. Odoo refuses the same shape in
   `sequence.mixin._constrains_date_sequence`
   (`addons/account/models/sequence_mixin.py:157`); correct the period, or
   post the entry with an explicit `:external-id` so no allocation happens."
  [db tx-data]
  (let [;; per journal-eid: {:written <ordinal just handed out> :key <bucket>}
        ;; so N entries in one tx-data chain their cas forms correctly.
        state (volatile! {})]
    (reduce
     (fn [acc form]
       (if-not (transaction-map? form)
         (conj acc form)
         (let [jeid (resolve-journal-eid db (:kontor.transaction/journal form))
               cfg  (when jeid (journal-config db jeid))]
           (if-not (:enabled? cfg)
             (conj acc form)
             (let [date (:kontor.transaction/effective-date form)
                   _    (when (nil? date)
                          (throw (ex-info (str "kontor.numbering: cannot allocate a legal number "
                                               "without :kontor.transaction/effective-date — the "
                                               "reset bucket is derived from the document date")
                                          {:type    :kontor.numbering/no-effective-date
                                           :journal jeid})))
                   k        (bucket-key (:reset cfg) date)
                   running  (get @state jeid)
                   ;; The value the counter datom holds RIGHT NOW (any bucket) —
                   ;; what :db/cas must compare against. Distinct from the
                   ;; ordinal, which is bucket-relative.
                   cas-old  (if running (:written running) (:last cfg))
                   cur-key  (if running (:key running) (:last-key cfg))
                   _        (when (and cur-key (pos? (compare cur-key k)))
                              (throw (ex-info
                                      (str "kontor.numbering: journal " jeid " has already issued "
                                           "numbers in sequence bucket " (pr-str cur-key)
                                           ", so bucket " (pr-str k) " is closed — allocating into "
                                           "it would restart at 1 and re-issue a number that "
                                           "bucket already used. Date the entry into the open "
                                           "period, or pass an explicit :external-id so no number "
                                           "is allocated. (ADR-151)")
                                      {:type         :kontor.numbering/sequence-bucket-closed
                                       :journal      jeid
                                       :current-key  cur-key
                                       :entry-key    k})))
                   n        (if (= k cur-key) (inc (or cas-old 0)) 1)
                   xid      (render cfg n date)
                   caller-xid (:kontor.transaction/external-id form)]
               (when (and (nil? caller-xid) (external-id-taken? db xid))
                 (throw (ex-info
                         (str "kontor.numbering: allocated number " (pr-str xid)
                              " is already used by another transaction's "
                              ":kontor.transaction/external-id. The journal's counter has "
                              "drifted behind the numbers actually issued — most likely "
                              "because numbers were written by hand before allocation was "
                              "enabled. Set :kontor.journal/last-sequence past the highest "
                              "issued ordinal; do NOT skip the number, since skipping is the "
                              "gap this attribute exists to prevent. (ADR-151)")
                         {:type :kontor.numbering/number-collision
                          :journal jeid :external-id xid :sequence-number n})))
               (vswap! state assoc jeid {:written n :key k})
               (-> acc
                   (conj (cond-> (assoc form
                                        :kontor.transaction/sequence-number n
                                        :kontor.transaction/sequence-key k)
                           (nil? caller-xid)
                           (assoc :kontor.transaction/external-id xid)))
                   ;; :db/cas against exactly what the counter datom holds. A
                   ;; nil `cas-old` means "no counter datom yet", which cas
                   ;; verifies by asserting absence — so a fresh journal is
                   ;; race-safe too.
                   (conj [:db/cas jeid :kontor.journal/last-sequence cas-old n])
                   (conj [:db/add jeid :kontor.journal/last-sequence-key k])))))))
     []
     tx-data)))

;; ============================================================================
;; Gap detection (the auditor's side)
;; ============================================================================

(defn allocated-numbers
  "`{sequence-key #{ordinal …}}` for every numbered transaction in
   `journal`."
  [db journal]
  (let [jeid (resolve-journal-eid db journal)]
    (reduce (fn [acc [k n]] (update acc k (fnil conj (sorted-set)) n))
            {}
            (d/q '[:find ?k ?n
                   :in $ ?j
                   :where
                   [?t :kontor.transaction/journal ?j]
                   [?t :kontor.transaction/sequence-number ?n]
                   [?t :kontor.transaction/sequence-key ?k]]
                 db jeid))))

(defn sequence-gaps
  "Holes in `journal`'s allocated series, per reset bucket. Returns a
   vector of `{:journal :sequence-key :missing [ordinal …] :highest}`, one
   entry per bucket that has a hole; empty when the series is intact.

   Grouped by `:kontor.transaction/sequence-key`, so a legitimate 1-January
   restart is not read as a 5,000-entry hole. A series is intact iff it runs
   1..highest with nothing missing — a bucket whose lowest ordinal is 3
   reports 1 and 2 missing, because \"the first two invoices of the year are
   gone\" is precisely what an auditor is looking for.

   Because allocation and consumption are one atomic transaction (see the ns
   docstring), a hole cannot arise from a failed post. It can arise from an
   ADR-007 `:db/purge`, which is exactly the case this detector must
   surface rather than hide.

   Odoo's equivalent is `account_move._compute_made_sequence_gap`
   (`addons/account/models/account_move.py:972`), which flags the break on
   the following move; reporting per-bucket makes the whole shape of the
   damage visible in one read instead of one flag per document."
  ([db journal] (sequence-gaps db journal {}))
  ([db journal {:keys [sequence-key]}]
   (let [jeid (resolve-journal-eid db journal)
         by-k (cond-> (allocated-numbers db jeid)
                sequence-key (select-keys [sequence-key]))]
     (vec
      (for [[k ns*] (sort-by key by-k)
            :let [hi      (apply max ns*)
                  missing (vec (remove ns* (range 1 (inc hi))))]
            :when (seq missing)]
        {:journal jeid :sequence-key k :missing missing :highest hi})))))

(defn gapless?
  "True iff `journal`'s allocated series has no hole in any bucket."
  ([db journal] (gapless? db journal {}))
  ([db journal opts] (empty? (sequence-gaps db journal opts))))

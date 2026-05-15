(ns kontor.schedule
  "Recurring-posting schedule helpers — ADR-032.

   A `:schedule` is a sequence of dates at which a recurring posting
   fires. Each firing produces one immutable `:schedule-occurrence`
   referencing the kernel `:transaction` it created. Remaining
   occurrences are *derived* from `(:schedule/start-date,
   :schedule/end-date, :schedule/frequency)` minus the already-fired
   sequence numbers — the kernel doesn't materialize the full
   schedule, only the occurrences that have actually fired.

   The kernel does NOT compute per-period amounts. That's the
   consumer's job (depreciation methods, ASC 606 recognition,
   subscription proration, etc.). The kernel just records what
   happened.

   ## Usage flow

     1. Consumer constructs the `:schedule` entity (per its domain).
     2. On each firing tick (consumer-driven), consumer:
        a. Computes the period's amount.
        b. Calls `kontor.posting/build-transaction` to produce
           tx-data for the journal entry.
        c. Calls `(record-occurrence!  conn schedule sequence date
                                       amount commodity tx-tempid)`
           to log the occurrence with a back-ref to the transaction.
     3. The composite identity `[schedule, sequence]` makes
        re-firing idempotent."
  (:require [datahike.api :as d]
            [kontor.validation :as validation])
  (:import [java.time LocalDate ZoneOffset]
           [java.time.temporal ChronoUnit]))

;; ============================================================================
;; Entity resolution
;; ============================================================================

(defn by-code
  "Resolve a schedule entity-id by its `:schedule/code`."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :schedule/code ?code]]
       db code))

(defn resolve-schedule
  "Coerce `spec` to an entity-id (string code → eid, nil → nil)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

;; ============================================================================
;; Frequency arithmetic
;; ============================================================================

(defn- ^LocalDate inst->local-date [^java.util.Date d]
  (-> d .toInstant (.atZone ZoneOffset/UTC) .toLocalDate))

(defn- local-date->inst [^LocalDate ld]
  (-> ld
      (.atStartOfDay ZoneOffset/UTC)
      .toInstant
      java.util.Date/from))

(defn date-of-occurrence
  "Compute the scheduled date for occurrence `sequence` (1-indexed)
   given the schedule's start-date + frequency. Useful for consumers
   that want to compute upcoming occurrence dates without persisting
   them.

   For `:custom` frequency, throws — callers must supply their own
   date logic."
  [^java.util.Date start-date frequency sequence]
  (when-not (pos? sequence)
    (throw (ex-info "sequence must be 1-indexed positive"
                    {:sequence sequence})))
  (let [n (dec sequence)
        ld (inst->local-date start-date)]
    (local-date->inst
     (case frequency
       :daily     (.plusDays ld n)
       :weekly    (.plusWeeks ld n)
       :monthly   (.plusMonths ld n)
       :quarterly (.plusMonths ld (* 3 n))
       :annual    (.plusYears ld n)
       :custom    (throw (ex-info "Custom frequency requires caller-supplied dates"
                                  {:frequency frequency :sequence sequence}))
       (throw (ex-info "Unknown frequency"
                       {:frequency frequency
                        :supported #{:daily :weekly :monthly :quarterly :annual :custom}}))))))

;; ============================================================================
;; Occurrence log
;; ============================================================================

(defn fired-sequences
  "Set of sequence numbers already fired for the given schedule."
  [db schedule-eid]
  (set (d/q '[:find [?seq ...]
              :in $ ?s
              :where
              [?o :schedule-occurrence/schedule ?s]
              [?o :schedule-occurrence/sequence ?seq]]
            db schedule-eid)))

(defn last-fired-sequence
  "Highest fired sequence number, or 0 if none."
  ^long [db schedule-eid]
  (or (d/q '[:find (max ?seq) .
             :in $ ?s
             :where
             [?o :schedule-occurrence/schedule ?s]
             [?o :schedule-occurrence/sequence ?seq]]
           db schedule-eid)
      0))

(defn next-pending-sequence
  "The next sequence number to fire (last-fired + 1), assuming no
   gaps. Consumer can call `(date-of-occurrence ...)` with the
   schedule's start-date + frequency to get its valid-time date."
  ^long [db schedule-eid]
  (inc (last-fired-sequence db schedule-eid)))

(defn pending-occurrences
  "Sequence numbers that are due as-of `now` but not yet fired.
   Returns a vector of `{:sequence Long :date Date}` pairs ordered
   by sequence. Only valid for non-`:custom` frequencies.

   `now` defaults to the current instant. End-date (if present) is
   inclusive."
  ([db schedule-eid] (pending-occurrences db schedule-eid (java.util.Date.)))
  ([db schedule-eid ^java.util.Date now]
   (let [pulled (d/pull db
                        [:schedule/start-date
                         :schedule/end-date
                         :schedule/frequency
                         :schedule/state]
                        schedule-eid)
         start (:schedule/start-date pulled)
         end   (:schedule/end-date pulled)
         freq  (:schedule/frequency pulled)
         state (:schedule/state pulled)
         fired (fired-sequences db schedule-eid)]
     (when (and (#{:active} state)
                (not= :custom freq))
       (loop [seq 1
              acc []]
         (let [d (date-of-occurrence start freq seq)]
           (cond
             ;; Past the schedule's end date.
             (and end (pos? (.compareTo d end)))     acc
             ;; Not yet due.
             (pos? (.compareTo d now))               acc
             ;; Already fired.
             (contains? fired seq)                   (recur (inc seq) acc)
             ;; Pending — collect and continue.
             :else
             (recur (inc seq)
                    (conj acc {:sequence seq :date d})))))))))

(defn record-occurrence-tx-data
  "Pure tx-data builder for `record-occurrence!` — appends the
   `:schedule-occurrence` entity to `tx-data` without the
   `d/transact` wrapper. Use as a `kontor.process` step (ADR-067);
   `record-occurrence!` is the standalone wrapper."
  [db schedule sequence scheduled-date amount commodity tx-data fired-at]
  (let [schedule-eid (resolve-schedule db schedule)
        _ (when-not schedule-eid
            (throw (ex-info "record-occurrence!: schedule not found"
                            {:schedule schedule})))
        occurrence {:schedule-occurrence/schedule       schedule-eid
                    :schedule-occurrence/sequence       sequence
                    :schedule-occurrence/scheduled-date scheduled-date
                    :schedule-occurrence/transaction    -1
                    :schedule-occurrence/amount         amount
                    :schedule-occurrence/commodity      commodity
                    :schedule-occurrence/fired-at       fired-at}]
    (conj (vec tx-data) occurrence)))

(defn record-occurrence!
  "Idempotently record one occurrence of the schedule.

   `tx-data` is the journal entry produced by the consumer's posting
   builder (typically from `kontor.posting/build-transaction`).
   The function appends the occurrence entity to `tx-data` and
   transacts atomically. The composite identity `[schedule, sequence]`
   collapses duplicates — re-firing period 7 doesn't duplicate.

   Input:
     conn         — datahike connection
     schedule     — schedule eid or `:schedule/code` string
     sequence     — long, 1-indexed
     scheduled-date — `java.util.Date`, the valid-time
     amount       — bigdec, this period's amount
     commodity    — commodity ref
     tx-data      — vector of datahike facts; MUST include the
                    transaction at tempid -1
     fired-at     — optional; defaults to now

   Returns the datahike tx-report. The pure tx-data builder is
   `record-occurrence-tx-data` (ADR-067)."
  ([conn schedule sequence scheduled-date amount commodity tx-data]
   (record-occurrence! conn schedule sequence scheduled-date amount
                       commodity tx-data (java.util.Date.)))
  ([conn schedule sequence scheduled-date amount commodity tx-data fired-at]
   (validation/transact-with-validation
    conn (record-occurrence-tx-data
          (d/db conn) schedule sequence scheduled-date amount
          commodity tx-data fired-at))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn set-state-tx-data
  "Pure tx-data builder for the schedule lifecycle transitions —
   `mark-completed!` / `mark-paused!` / `mark-cancelled!` all reduce
   to setting `:schedule/state`. Use as a `kontor.process` step
   (ADR-067)."
  [db schedule state]
  [{:db/id (resolve-schedule db schedule) :schedule/state state}])

(defn mark-completed!
  "Mark a schedule `:completed` (no further occurrences will fire).
   Typical use: when `last-fired-sequence` reaches the planned total
   count for a finite schedule."
  [conn schedule]
  (validation/transact-with-validation
   conn (set-state-tx-data (d/db conn) schedule :completed)))

(defn mark-paused!
  "Pause a schedule. `pending-occurrences` returns empty while paused."
  [conn schedule]
  (validation/transact-with-validation
   conn (set-state-tx-data (d/db conn) schedule :paused)))

(defn mark-cancelled!
  "Cancel a schedule. Irreversible by convention; existing
   occurrences remain in the log for audit."
  [conn schedule]
  (validation/transact-with-validation
   conn (set-state-tx-data (d/db conn) schedule :cancelled)))

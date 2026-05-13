(ns kontor.bitemporal
  "Tx-meta-based bitemporal helpers — canonical valid-time anchor
   per ADR-048.

   Datahike already gives us the transaction-time axis via
   `(d/history db)` + `(d/as-of db tx-or-instant)`. This namespace
   adds the valid-time axis using two transaction-metadata
   attributes — `:tx/valid-from` and `:tx/valid-to` — plus a
   read-time resolver that derives 'value of (entity, attribute) at
   valid-time D' from the history.

   Compose with `(d/as-of db past-tx)` for full bitemporal time
   travel: `(value-at (d/as-of db past-tx) eid attr vt)` answers
   'what did we think the value was at valid-time `vt`, as known by
   transaction-time `past-tx`'.

   ## Design

   - Each transaction MAY attach `:tx/valid-from` (and optionally
     `:tx/valid-to`) by including a map with `:db/id \"datomic.tx\"`
     in the tx-data. Real-time writes that omit these attrs default
     to `vt-from = :db/txInstant`, `vt-to = forever`.

   - The valid-time on a tx applies to every datom *in that tx*. A
     tx that touches only one attribute backdates only that
     attribute on the affected entity; other attributes keep their
     own histories untouched. (XTDB v2 calls this 'per-row valid-
     time'; here it's per-(datom-in-this-tx) because a tx is the
     atomic write unit.)

   - The resolver answers 'value at vt=D' by:
       1. Selecting all assertions of `[entity attr]` from history.
       2. Filtering to those whose `[vt-from, vt-to)` interval
          contains `D` (half-open, SQL:2011-style).
       3. Picking the one with the latest `:db/txInstant` —
          'most-recently-known correction wins on overlap.'

   - Semantics match XTDB v2's polygon resolver for the common case
     of one-vt-per-tx. Future per-datom valid-time (a sibling per-
     entity attr) can be layered on without breaking this API.

   ## Schema

   Install `schema` once per database:

       (d/transact conn kontor.bitemporal/schema)

   Then transact normally, optionally backdating:

       (d/transact conn (kbt/with-vt [{:posting/code \"P\" :posting/amount 250M}]
                                     #inst \"2026-03-15\"))

   ## Reads

   - `(value-at db eid attr cutoff)` — single value at vt=cutoff
   - `(values-between db eid attr from to)` — all visible values in
     a vt window
   - `(timeline db eid attr)` — full ordered (vt, value) history
   - `(assertion-at db eid attr cutoff)` — value plus its (vf, vt,
     tx) metadata

   ## SQL:2011 period predicates

   `vt-contains?`, `vt-overlaps?`, `vt-precedes?`, `vt-meets?` etc.
   work on `{:from inst :to inst}` interval maps for in-Clojure
   reasoning. Use when you need Allen-relation logic outside the
   resolver."
  (:require [datahike.api :as d])
  (:import [java.util Date]))

;; ============================================================================
;; Schema
;; ============================================================================

(def schema
  "Two tx-meta attributes to install once per database."
  [{:db/ident :tx/valid-from
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Valid-time lower bound for every datom in this tx.
             Half-open: [vt-from, vt-to). Defaults to :db/txInstant
             when absent — i.e. real-time writes start their valid-
             time at the moment they were transacted."}

   {:db/ident :tx/valid-to
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Valid-time upper bound (exclusive) for every datom in
             this tx. When absent the interval is open-ended; the
             resolver treats this as +∞."}])

(def ^Date forever
  "Sentinel upper-bound used internally when `:tx/valid-to` is
   absent. Chosen far enough out that no real backdated correction
   will collide."
  #inst "9999-12-31T23:59:59.999-00:00")

(def ^Date dawn
  "Sentinel lower bound rarely needed; provided for symmetry. Used
   in some `between-vt` queries."
  #inst "0001-01-01T00:00:00.000-00:00")

;; ============================================================================
;; Write helpers
;; ============================================================================

(defn- strip-tx-meta
  "Remove any existing `{:db/id \"datomic.tx\" ...}` map(s) from
   `tx-data`. Lets `with-vt` be idempotent — callers can wrap a
   tx-data that already carries tx-meta and the new vf/vt wins."
  [tx-data]
  (vec (remove #(and (map? %) (= (:db/id %) "datomic.tx")) tx-data)))

(defn with-vt
  "Append (or replace) a tx-meta entity on `tx-data` carrying
   `:tx/valid-from` (and optionally `:tx/valid-to`). Idempotent: if
   `tx-data` already has a `{:db/id \"datomic.tx\"}` map, it is
   replaced — the caller's vf/vt wins.

       (d/transact conn (with-vt [{...}] vt-from))
       (d/transact conn (with-vt [{...}] vt-from vt-to))"
  ([tx-data vt-from]
   (conj (strip-tx-meta tx-data)
         {:db/id "datomic.tx"
          :tx/valid-from vt-from}))
  ([tx-data vt-from vt-to]
   (conj (strip-tx-meta tx-data)
         {:db/id "datomic.tx"
          :tx/valid-from vt-from
          :tx/valid-to vt-to})))

(defn transact-with-vt!
  "Convenience wrapper around `d/transact` that attaches valid-time
   metadata. `opts` accepts `:vt-from` and optionally `:vt-to`."
  [conn tx-data {:keys [vt-from vt-to]}]
  (cond
    (and vt-from vt-to)
    (d/transact conn (with-vt tx-data vt-from vt-to))

    vt-from
    (d/transact conn (with-vt tx-data vt-from))

    :else
    (d/transact conn tx-data)))

;; ============================================================================
;; Reader helpers — derive valid-time from tx-meta
;; ============================================================================

(defn posting-vf
  "Resolve a posting's valid-from via its creating tx's `:tx/valid-from`.
   Falls back to the tx's `:db/txInstant` when valid-from is absent
   (matches the resolver's default). Returns `java.util.Date` or nil
   when the posting/tx isn't found."
  ^Date [db posting-eid]
  (d/q '[:find ?vf .
         :in $ ?p
         :where
         [?p :posting/transaction _ ?tx]
         [?tx :db/txInstant ?ti]
         [(get-else $ ?tx :tx/valid-from ?ti) ?vf]]
       db posting-eid))

(def query-rules
  "Datalog rules for using `:tx/valid-from` in queries. Pass as the
   `%` arg of a query.

   Example: filter postings by as-of-valid:

       (d/q '[:find ?p ?vf
              :in $ % ?acct ?as-of
              :where
              [?p :posting/account ?acct]
              (posting-vf ?p ?vf)
              [(.compareTo ^java.util.Date ?vf ?as-of) ?cmp]
              [(<= ?cmp 0)]]
            db query-rules acct as-of)"
  '[[(posting-vf ?p ?vf)
     [?p :posting/transaction _ ?tx]
     [?tx :db/txInstant ?ti]
     [(get-else $ ?tx :tx/valid-from ?ti) ?vf]]
    [(tx-vf ?tx ?vf)
     [?tx :db/txInstant ?ti]
     [(get-else $ ?tx :tx/valid-from ?ti) ?vf]]])

(defn tx-data-vf
  "Pull the proposed `:tx/valid-from` out of inbound tx-data (used by
   middleware that needs to know vf BEFORE the tx commits). Returns
   the date or nil if no `\"datomic.tx\"` map is present in tx-data."
  ^Date [tx-data]
  (some (fn [e]
          (when (and (map? e) (= (:db/id e) "datomic.tx"))
            (:tx/valid-from e)))
        tx-data))

;; ============================================================================
;; Resolver — core read primitive
;; ============================================================================

(defn- ensure-history
  "Coerce `db` to a history-aware DB if it isn't already. `d/as-of`
   results CAN be passed through `d/history` — the composition is
   the bitemporal lattice."
  [db]
  (let [t (.getName (.getClass ^Object db))]
    (if (or (.contains t "Historical")
            (.contains t "HistoricalDB"))
      db
      (d/history db))))

(defn- candidates
  "All assertions of `[?e ?a]` from history with their (vf, vt, ti).
   Returns a seq of `{:value v :vf vf :vt vt :ti ti :tx tx-eid}`."
  [hist-db eid attr]
  (->> (d/q '[:find ?v ?vf ?vt ?ti ?tx
              :in $ ?e ?a ?forever
              :where
              [?e ?a ?v ?tx true]            ; assertions only
              [?tx :db/txInstant ?ti]
              [(get-else $ ?tx :tx/valid-from ?ti) ?vf]
              [(get-else $ ?tx :tx/valid-to ?forever) ?vt]]
            hist-db eid attr forever)
       (map (fn [[v vf vt ti tx]]
              {:value v :vf vf :vt vt :ti ti :tx tx}))))

(defn- in-window?
  "True iff `cutoff` ∈ [vf, vt) (half-open)."
  [^Date vf ^Date vt ^Date cutoff]
  (and (>= (.compareTo cutoff vf) 0)
       (<  (.compareTo cutoff vt) 0)))

(defn assertion-at
  "Return the assertion of `[entity attr]` that was visible at valid-
   time `cutoff` — value + (vf, vt, tx-instant, tx-eid) metadata.

   The visible assertion is the one whose `[vf, vt)` interval
   contains `cutoff` and that has the latest `:db/txInstant` (most
   recently known correction wins on overlap).

   Returns nil if no assertion applies."
  [db eid attr cutoff]
  (->> (candidates (ensure-history db) eid attr)
       (filter #(in-window? (:vf %) (:vt %) cutoff))
       (sort-by #(.getTime ^Date (:ti %)))
       last))

(defn value-at
  "The resolved value of `[entity attr]` at valid-time `cutoff`, or
   nil if no assertion applies. See `assertion-at` for full
   metadata."
  [db eid attr cutoff]
  (:value (assertion-at db eid attr cutoff)))

(defn values-between
  "Distinct visible values of `[entity attr]` during the half-open
   window [from, to), after polygon resolution. Returns a vec of
   `assertion-at`-shaped maps, ordered by `:vf`.

   Implemented by collecting the relevant vt-breakpoints (the window
   start plus every assertion's :vf that falls within the window),
   evaluating the resolver at each, and de-duplicating by value.
   This matches XTDB v2's polygon-decomposition semantics."
  [db eid attr ^Date from ^Date to]
  (let [hist (ensure-history db)
        cs (candidates hist eid attr)
        ;; Break points: window start + every vf or vt in (from, to)
        breakpoints (->> cs
                         (mapcat (fn [{:keys [vf vt]}] [vf vt]))
                         (filter (fn [^Date d]
                                   (and (>= (.compareTo d from) 0)
                                        (<  (.compareTo d to)   0))))
                         (cons from)
                         distinct
                         (sort-by #(.getTime ^Date %)))
        visible-at (fn [^Date bp]
                     (->> cs
                          (filter #(in-window? (:vf %) (:vt %) bp))
                          (sort-by #(.getTime ^Date (:ti %)))
                          last))
        seen-values (atom #{})]
    (->> breakpoints
         (keep visible-at)
         (filter (fn [{:keys [value]}]
                   (when-not (contains? @seen-values value)
                     (swap! seen-values conj value)
                     true)))
         vec)))

(defn timeline
  "Every assertion of `[entity attr]` ordered by valid-from, with
   metadata. Useful for 'show me how this fact has changed over
   time' UIs."
  [db eid attr]
  (->> (candidates (ensure-history db) eid attr)
       (sort-by (fn [{:keys [vf]}] (.getTime ^Date vf)))))

;; ============================================================================
;; Bitemporal composition
;; ============================================================================

(defn as-of-bitemporal
  "Return a db value pinned at both tx-time and valid-time.

   `(as-of-bitemporal db {:tx tx-or-instant :vt vt-cutoff})` produces
   a HistoricalDB-shaped value where:
     - tx-time is the standard datahike `d/as-of` snapshot at `:tx`,
     - valid-time is `:vt` — but valid-time is NOT enforced by the
       returned db; the caller still uses `value-at` etc. to apply
       the vt filter, on top of the tx-snapshot.

   The two-axis semantics fall out of composing the snapshot with
   the resolver."
  [db {:keys [tx vt]}]
  (cond-> db
    tx (d/as-of tx)
    true ensure-history))

;; ============================================================================
;; SQL:2011 period predicates
;; ============================================================================
;;
;; All operate on interval maps `{:from inst :to inst}` for in-
;; Clojure reasoning. The half-open convention matches the resolver.

(defn vt-contains?
  "True iff interval `a` contains interval `b` (a.from ≤ b.from AND
   b.to ≤ a.to)."
  [a b]
  (and (<= (.compareTo ^Date (:from a) (:from b)) 0)
       (>= (.compareTo ^Date (:to a)   (:to b))   0)))

(defn vt-strictly-contains?
  [a b]
  (and (<  (.compareTo ^Date (:from a) (:from b)) 0)
       (>  (.compareTo ^Date (:to a)   (:to b))   0)))

(defn vt-overlaps?
  "True iff `a` and `b` share at least one point (half-open)."
  [a b]
  (and (<  (.compareTo ^Date (:from a) (:to b))   0)
       (<  (.compareTo ^Date (:from b) (:to a))   0)))

(defn vt-equals?
  [a b]
  (and (zero? (.compareTo ^Date (:from a) (:from b)))
       (zero? (.compareTo ^Date (:to a)   (:to b)))))

(defn vt-precedes?
  "Allen 'before': a ends at or before b starts."
  [a b]
  (<= (.compareTo ^Date (:to a) (:from b)) 0))

(defn vt-strictly-precedes?
  [a b]
  (< (.compareTo ^Date (:to a) (:from b)) 0))

(defn vt-immediately-precedes?
  "Allen 'meets': a.to == b.from exactly."
  [a b]
  (zero? (.compareTo ^Date (:to a) (:from b))))

(defn vt-succeeds?
  [a b]
  (vt-precedes? b a))

(defn vt-strictly-succeeds?
  [a b]
  (vt-strictly-precedes? b a))

(defn vt-immediately-succeeds?
  [a b]
  (vt-immediately-precedes? b a))

;; ============================================================================
;; Aggregation helpers — common bitemporal-aware sums
;; ============================================================================

(defn sum-at
  "Sum of the values of `attr` across all entities matching
   `entity-pred` (a fn db→[eid]) at valid-time `cutoff`. Skips
   entities with no asserted value at cutoff.

   Use for 'balance of account X at vt=D' style queries:

       (sum-at db
               (fn [d] (->> (d/q '[...] d ...)))
               :posting/amount
               cutoff)"
  [db entity-pred attr ^Date cutoff]
  (let [hist (ensure-history db)
        eids (entity-pred db)]
    (reduce (fn [^java.math.BigDecimal acc eid]
              (if-let [v (value-at hist eid attr cutoff)]
                (.add acc ^java.math.BigDecimal v)
                acc))
            0M eids)))

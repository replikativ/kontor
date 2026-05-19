(ns kontor.bitemporal
  "Thin write-side bitemporal shim over datahike's upstream
   `:db.valid/from` / `:db.valid/to` system attrs (pre-installed on every
   fresh DB by `feature/bitemporal-v1`).

   Most of what this namespace used to do — Allen interval predicates,
   the resolver (`value-at`, `assertion-at`, `timeline`,
   `values-between`), the per-namespace `posting-vf` / `query-rules`
   helpers, and the `:tx/valid-from` schema declaration — has moved
   into datahike itself:

     - Read at a point in valid-time:  `(d/valid-at db t)` + `d/pull`/`d/q`
     - Range read:                     `(d/valid-between db from to)`
     - Allen interval rules:           built-in (`interval-overlaps?`,
                                       `interval-contains?`,
                                       `interval-meets?` …)
     - Schema:                         pre-installed; no install needed

   What stays here is the write-side ergonomic helper that 87+ kontor
   call sites use: append a tx-meta map with `:db.valid/from` (and
   optionally `:db.valid/to`) to a tx-data sequence. Plus
   `strip-tx-meta` because `kontor.process` uses it during
   step-fragment accumulation, and the `forever` upper-bound sentinel
   for callers that want to be explicit about the open-ended case
   (semantically identical to omitting `:db.valid/to`)."
  (:import [java.util Date]))

(def ^Date forever
  "Sentinel upper-bound used when a caller wants to be explicit about
   an open-ended valid-time. Semantically equivalent to omitting
   `:db.valid/to` (datahike's built-in `valid-at` rule defaults to
   this same instant via `get-else`). Kept here because 25+ call
   sites reference it for readability."
  #inst "9999-12-31T23:59:59.999-00:00")

(defn strip-tx-meta
  "Remove any existing `{:db/id \"datomic.tx\" ...}` map(s) from
   `tx-data`. Used by `with-vt` for idempotence and by
   `kontor.process/run-steps` to strip per-fragment tx-meta as it
   accumulates step fragments into one transaction.

   Process semantics: per ADR-067 each step fragment is pure tx-data
   (no tx-meta); only `run-process` owns the outer valid-time."
  [tx-data]
  (vec (remove #(and (map? %) (= (:db/id %) "datomic.tx")) tx-data)))

(defn with-vt
  "Append (or replace) a tx-meta map on `tx-data` carrying
   `:db.valid/from` (and optionally `:db.valid/to`). Idempotent: if
   `tx-data` already has a `{:db/id \"datomic.tx\"}` map, it is
   replaced — the caller's vf/vt wins.

   The 2-arity form leaves `:db.valid/to` open-ended (datahike's
   `valid-at` rule defaults it via `get-else`).

       (d/transact conn (with-vt tx-data vt-from))
       (d/transact conn (with-vt tx-data vt-from vt-to))"
  ([tx-data vt-from]
   (conj (strip-tx-meta tx-data)
         {:db/id "datomic.tx"
          :db.valid/from vt-from}))
  ([tx-data vt-from vt-to]
   (conj (strip-tx-meta tx-data)
         {:db/id "datomic.tx"
          :db.valid/from vt-from
          :db.valid/to vt-to})))

;; ============================================================================
;; commit-tx-eid — extract the datahike commit-tx eid from a tx-report.
;;
;; `close-validity!` operates on the DATAHIKE tx-entity (the carrier of
;; `:db.valid/from` / `:db.valid/to` window), NOT on any business
;; entity created in the transaction. A caller wanting to later close
;; the window on a write they just made needs the commit-tx eid, which
;; datahike does not expose directly on the tx-report. The recipe is
;; to scan `:tx-data` for the `:db/txInstant` datom — its `.-tx` slot
;; is the commit-tx eid. This helper makes the extraction obvious +
;; one-line.
;;
;; Subtle footgun this helper averts: a kontor `:transaction` /
;; `:partner` / `:posting` entity's eid is NOT the commit-tx eid. They
;; are EAVT-distinct: the tx-entity holds the `:db/txInstant` +
;; `:db.valid/from` / `:db.valid/to` slots; the business entity holds
;; user data. close-validity on a business eid is a silent no-op that
;; looks valid (no error) but doesn't close the window.

(defn commit-tx-eid
  "Extract the datahike commit-tx eid from a `d/transact` `tx-report`.

   Use when a write you just made needs to be closed via
   `close-validity!` later — pass the report's eid through.

   Throws `:type :kontor.bitemporal/no-commit-tx` if the report has no
   `:db/txInstant` datom (which would indicate a malformed report)."
  [tx-report]
  (let [d (->> (:tx-data tx-report)
               (filter #(= :db/txInstant (.-a %)))
               first)]
    (if d
      (.-tx d)
      (throw (ex-info "tx-report has no :db/txInstant datom"
                      {:type :kontor.bitemporal/no-commit-tx
                       :tx-report (select-keys tx-report [:tempids :max-tx])})))))

;; ============================================================================
;; close-validity — retroactively close a prior tx's valid-time window.
;;
;; Datahike accepts `[:db/add prior-tx-eid :db.valid/to <date>]` as a
;; first-class commit, with a transactor-level guard that the
;; resulting window stays valid (vf < vt). The closing commit's hash
;; includes the new datom; the prior tx's hash is unchanged. Auditors
;; querying `audit/verify-chain` see both.
;;
;; We expose this as a `*-tx-data` builder + a side-effecting `!`
;; wrapper per ADR-068. The builder makes the operation composable
;; with `kontor.process` step lists; the wrapper is the convenience
;; for one-shot use.
;;
;; This sits in kontor.bitemporal (next to `with-vt`) rather than in
;; datahike itself — see datahike's `doc/valid_time.md` and kontor
;; doc/research/77 §6 for the rationale (substrate stays
;; primitives-first; consumers name + opinionate).
;; ============================================================================

(defn close-validity-tx-data
  "Return tx-data that retroactively sets `:db.valid/to vt` on
   `prior-tx-eid` — closing that tx's valid-time window so queries at
   any vt ≥ `vt` no longer see the prior tx's datoms.

   **`prior-tx-eid` must be the DATAHIKE COMMIT-TX eid**, NOT any
   business entity created in that tx (kontor `:transaction`,
   `:partner`, `:posting`, etc.). The two are EAVT-distinct: the
   commit-tx holds the `:db.valid/from` / `:db.valid/to` window; the
   business entity holds user data. Closing a business-entity eid is a
   silent no-op (looks valid; doesn't close the window). Extract the
   commit-tx eid from a `tx-report` via [[commit-tx-eid]]:

       (let [report (d/transact conn ...)
             tx-eid (commit-tx-eid report)]
         ;; ... later ...
         (close-validity! conn tx-eid <vt>))

   The datahike transactor enforces a cross-tx `vf < vt` check on the
   resulting combined state — closing with a `vt` that would produce
   `vf >= vt` raises `:transact/invalid-valid-times-cross-tx` at
   commit time. Composable with [[with-vt]] for the outer transaction's
   own valid-time stamp."
  [prior-tx-eid vt]
  [{:db/id prior-tx-eid :db.valid/to vt}])

(defn close-validity!
  "Side-effecting wrapper for [[close-validity-tx-data]]. Routes
   through `kontor.validation/transact-with-validation` so kernel
   invariants run alongside datahike's cross-tx vf<vt guard.

   Returns the tx-report. Throws if datahike rejects the closure
   (invalid vf<vt window, or the prior tx doesn't exist)."
  [conn prior-tx-eid vt]
  ;; require lazily — kontor.validation pulls in kontor.schema and we
  ;; want this namespace to stay zero-dep on the rest of kontor.
  (let [transact (requiring-resolve 'kontor.validation/transact-with-validation)]
    (transact conn (close-validity-tx-data prior-tx-eid vt))))

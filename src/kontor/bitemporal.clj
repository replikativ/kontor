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

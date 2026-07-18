(ns kontor.governance
  "Post-resolution validation for GOVERNED stores — the report-based realization
   of the transact gate (ADR-118's deferred fix, now un-deferred as the
   governed-store path).

   A GOVERNED store registers `validate-report` as a `datahike.tx-preds`
   transaction predicate (via [[govern!]]), so EVERY committed write — local or
   remote (kabel) — is validated in the writer, mandatorily, on the FULLY
   RESOLVED tx-report `{:db-before :db-after :tx-data}` (real eids + added/retract
   flags). Because it sees the resolved delta it catches the corruption vectors
   the pre-resolution gate misses and closes the whole red-team battery (research
   note 193 / exp9):

   - **balance** — re-sum every transaction TOUCHED by the delta from `db-after`,
     per commodity; non-zero rejects. Delta-scoped (only touched txs), so cost is
     O(delta), independent of ledger size — the inductive double-entry property.
   - **sealing** — any RETRACTED datom whose entity was `:kontor.posting/posted-at`
     in `db-before` rejects. Sees `db-before` + retract flags, so it guards
     `:db/retractEntity` and in-place edits of posted rows — which the
     assertion-only attr/entity preds structurally cannot.
   - **invariants** — the registered datalog `:invariant/rule` / `:invariant/query`
     set, run against the resolved report sources (post-resolution, so the
     `$empty+txs`-reconstruction fragility of the pre-resolution path is gone).

   `validate-report` throws `ex-info` (NOT an `Error`/`assert` — an Error crashes
   the datahike writer) to reject. It is pure over the report (no conn), so it is
   testable standalone and runs identically JVM + cljs.

   This does NOT replace the existing `kontor.gate` for non-governed callers; it
   is the authoritative path for stores that opt into governance."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [datahike.core :as dc]
            [kontor.money :as money]
            [kontor.invariant :as inv]))

;; ============================================================================
;; balance (sum-to-zero), post-resolution
;; ============================================================================

(defn- posting-attr? [a]
  (and (keyword? a) (= "kontor.posting" (namespace a))))

(defn- touched-tx-eids
  "Transaction eids touched by the resolved report: for every posting eid that
   appears in the report's datoms, resolve its `:kontor.posting/transaction`
   from `db-after` (still present) or `db-before` (was retracted). O(delta)."
  [{:keys [db-before db-after tx-data]}]
  (let [posting-eids (into #{}
                           (comp (filter (fn [d] (posting-attr? (:a d)))) (map :e))
                           tx-data)]
    (into #{}
          (keep (fn [e]
                  (or (d/q '[:find ?t . :in $ ?p :where [?p :kontor.posting/transaction ?t]] db-after e)
                      (d/q '[:find ?t . :in $ ?p :where [?p :kontor.posting/transaction ?t]] db-before e))))
          posting-eids)))

(defn balance-violations
  "Re-sum each delta-touched transaction from `db-after`, per commodity. Returns
   a vector of `{:transaction :commodity :sum}` for every non-zero group (empty
   = balanced)."
  [{:keys [db-after] :as report}]
  (vec
   (for [tx (touched-tx-eids report)
         [c s] (d/q '[:find ?c (sum ?amt) :in $ ?tx :where
                      [?p :kontor.posting/transaction ?tx]
                      [?p :kontor.posting/commodity ?c]
                      [?p :kontor.posting/amount ?amt]]
                    db-after tx)
         :when (and s (not (money/amount-zero? s)))]
     {:transaction tx :commodity c :sum s})))

;; ============================================================================
;; sealing, post-resolution
;; ============================================================================

(defn sealing-violations
  "Every RETRACTED datom in the report whose entity had `:kontor.posting/posted-at`
   in `db-before`. Catches `:db/retract`, `:db/retractEntity`, and the destructive
   half of an in-place edit uniformly. Returns `{:eid :attr}` vector."
  [{:keys [db-before tx-data]}]
  (vec
   (for [dd tx-data
         :when (and (false? (:added dd))
                    (d/q '[:find ?x . :in $ ?e :where [?e :kontor.posting/posted-at ?x]]
                         db-before (:e dd)))]
     {:eid (:e dd) :attr (:a dd)})))

;; ============================================================================
;; datalog invariants, post-resolution
;; ============================================================================

(defn- sanitize-schema
  "Keep only the user-attribute specs datahike's `empty-db` `:write` validator
   accepts: keyword idents whose spec carries both `:db/valueType` and
   `:db/cardinality` (drops the integer reverse-lookup entries and the partial
   system/bootstrap specs). Mirrors `kontor.invariant`'s helper, inlined so this
   ns needs no private-var access (fragile in cljs)."
  [schema]
  (when schema
    (into {}
          (filter (fn [[k v]]
                    (and (keyword? k) (map? v)
                         (contains? v :db/valueType)
                         (contains? v :db/cardinality))))
          schema)))

(defn- report-empty+txs
  "Reconstruct the `$empty+txs` source from the RESOLVED report: an empty db (of
   db-after's schema) with the delta's asserted datoms applied. Post-resolution,
   so eids match `db-after` and no lookup-ref seeding is needed (unlike the
   pre-resolution `kontor.invariant` reconstruction)."
  [{:keys [db-after tx-data]}]
  (let [schema (sanitize-schema (:schema db-after))
        flex   (or (:schema-flexibility db-after)
                   (get-in db-after [:config :schema-flexibility]) :write)
        adds   (into [] (comp (filter :added)
                              (map (fn [d] [:db/add (:e d) (:a d) (:v d)])))
                     tx-data)
        edb    (dc/empty-db schema {:schema-flexibility flex})]
    (if (seq adds) (dc/db-with edb adds) edb)))

(defn invariant-violations
  "Run every registered datalog invariant whose keyed attribute is asserted in
   the delta, against the resolved report sources ($before $after $empty+txs
   $txs). Returns `{:attribute :invariant}` for each that does not hold."
  [{:keys [db-before db-after tx-data] :as report}]
  (let [attrs (into #{} (comp (filter :added) (map :a)) tx-data)
        e+t   (report-empty+txs report)
        txs   (into [] (comp (filter :added)
                             (map (fn [d] [:db/add (:e d) (:a d) (:v d)])))
                    tx-data)]
    (vec
     (for [a attrs
           :let [q (d/q inv/invariant-query db-after a)]
           :when q
           :when (not (d/q (edn/read-string q) db-before db-after e+t txs))]
       {:attribute a :invariant (edn/read-string q)}))))

;; ============================================================================
;; The governor
;; ============================================================================

(defn validate-report
  "The kontor tx-pred: validate a RESOLVED datahike tx-report and throw `ex-info`
   to reject (sealing first — the more-specific error wins on a
   destructive-write-of-posted — then balance, then datalog invariants). Returns
   nil on success. Pure over the report."
  [report]
  (when-let [v (seq (sealing-violations report))]
    (throw (ex-info "Sealing violation: destructive write against a posted entity"
                    {:type :sealing/silent-retract-of-posted :violations (vec v)})))
  (when-let [v (seq (balance-violations report))]
    (throw (ex-info "Postings do not sum to zero per commodity"
                    {:type :validation/sum-to-zero :violations (vec v)})))
  (when-let [v (seq (invariant-violations report))]
    (throw (ex-info "Invariant mismatch"
                    {:type :invariant/invariant-mismatch :violations (vec v)})))
  nil)

(defn- store-id [conn]
  (get-in @conn [:config :store :id]))

;; Registration is a server/writer-side (JVM) concern — the writer that runs the
;; tx-pred lives on the authoritative node; cljs clients run `validate-report`
;; for optimistic pre-checks but do not register governors. `requiring-resolve`
;; keeps `datahike.tx-preds` a soft dependency so kontor loads on datahike
;; versions that predate it (PR #861); once merged this can become a direct
;; `(:require [datahike.tx-preds …])` and the guard can drop.
#?(:clj
   (defn govern!
     "Register [[validate-report]] as a `datahike.tx-preds` transaction predicate
      on `conn`'s store, so every committed write is validated post-resolution in
      the writer. Idempotent per store-id. Returns the store-id."
     [conn]
     ((requiring-resolve 'datahike.tx-preds/register-tx-pred!)
      (store-id conn) validate-report)))

#?(:clj
   (defn ungovern!
     "Remove the kontor governor from `conn`'s store."
     [conn]
     ((requiring-resolve 'datahike.tx-preds/unregister-tx-pred!)
      (store-id conn))))

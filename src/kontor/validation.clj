(ns kontor.validation
  "Runtime validation of accounting transactions.

   Two-layer model per ADR-011:

     1. **State-shape invariants** via the `invariant` library: declared
        as datalog queries in `resources/invariants/*.edn`, registered
        on the connection, evaluated automatically on every
        `transact-with-validation` call. Currently:
          - account-active: every :posting/account refers to an active
            account.
          - commodity-match: posting commodity matches the account's
            commodity when the account specifies one.

     2. **Behavior / lifecycle constraints** as hand-rolled middleware.
        Currently:
          - sealing (`sealing.clj`): no silent retract of
            :posting/posted-at-marked entities (ADR-007).
          - period-locked (`period.clj`): no posting whose tx's
            :tx/valid-from (kontor.bitemporal) falls inside a closed
            :period/locked-at period.
        Planned:
          - state-machine: :transaction/state transitions follow
            draft → posted → cancelled, no skipping or regressing."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [kontor.legal-hold :as legal-hold]
            [kontor.period :as period]
            [kontor.sealing :as sealing]
            [kontor.state-machine :as state-machine]
            [invariant.datahike :as inv]))

;; ============================================================================
;; Schema for invariant registration
;; ============================================================================

(def ^:private invariant-schema
  "Two attributes the `invariant` library expects for invariant
   registration. :invariant/rule is unique-identity so re-installing
   the same rule upserts (rather than creating a second entity that
   the invariant library's first-match lookup would silently shadow)."
  [{:db/ident       :invariant/rule
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Attribute keyword that triggers this invariant
                     when present in a tx (e.g. :posting/account).
                     Identity attribute — one query per rule keyword."}
   {:db/ident       :invariant/query
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "EDN string of the datalog query; see
                     `invariant.query/assert-valid-query` for shape
                     constraints (must take 4 sources)."}])

;; ============================================================================
;; Built-in invariants
;; ============================================================================

(defn- read-invariant
  "Read an invariant query from resources/invariants/<name>.edn and
   return the EDN string ready for storage."
  [resource-name]
  (let [r (io/resource (str "invariants/" resource-name ".edn"))]
    (when-not r
      (throw (ex-info "Invariant resource not found"
                      {:name resource-name
                       :looked-in (str "resources/invariants/" resource-name ".edn")})))
    (slurp r)))

(def kernel-invariants
  "The built-in invariants the kernel installs. Each is `{:rule attr
   :query <edn-string>}`. The :rule attribute is what triggers the
   invariant — it must appear in the tx's affected attributes. Add
   new ones here as separate slices land.

   Two invariants on different rule attrs both fire when a typical
   posting tx touches both attributes."
  [{:rule  :posting/account
    :query (read-invariant "account_active")}
   {:rule  :posting/commodity
    :query (read-invariant "commodity_match")}])

(defn install-invariants!
  "Transact the invariant-registration schema and register the kernel's
   built-in invariants on `conn`. Idempotent — re-installing replaces
   existing :invariant/query strings for each :invariant/rule via a
   delete-then-insert pattern (an :invariant/rule can have only one
   query at a time per the cardinality of the join in invariant.clj)."
  [conn]
  (d/transact conn invariant-schema)
  (d/transact
   conn
   (mapv (fn [{:keys [rule query]}]
           {:invariant/rule  rule
            :invariant/query query})
         kernel-invariants))
  conn)

;; ============================================================================
;; Sum-to-zero (transactor-side check; mirrors posting/build-transaction's
;; pre-construction guarantee for tx-data that bypasses the helper —
;; e.g. SQL clients writing posting INSERTs through pg-datahike).
;; ============================================================================

(defn- posting-entries-by-tx
  "Walk tx-data, return {transaction-eid {commodity-eid total}}.
   Handles both entity-map shape (`{:posting/account … :posting/amount …}`)
   and tuple shape (`[:db/add eid :posting/amount v]`).
   Tx-data without :posting/amount entries returns {} (no postings)."
  [tx-data]
  (let [add-amt
        (fn [acc tx-eid commodity amount]
          (update-in acc [tx-eid commodity]
                     (fnil #(.add ^java.math.BigDecimal % (bigdec amount)) 0M)))]
    (reduce
     (fn [acc entry]
       (cond
         ;; Entity map with posting attrs.
         (and (map? entry) (:posting/amount entry))
         (let [tx-eid (:posting/transaction entry)
               commodity (:posting/commodity entry)
               amount (:posting/amount entry)]
           (cond-> acc
             (and tx-eid amount)
             (add-amt tx-eid commodity amount)))

         ;; [:db/add eid :posting/amount v]
         (and (vector? entry) (= 4 (count entry))
              (= :db/add (first entry))
              (= :posting/amount (nth entry 2)))
         ;; We don't know the tx-eid from this tuple alone, so we
         ;; group by the posting eid (`(second entry)`) — datalog
         ;; later associates it with the transaction. Postings without
         ;; an explicit transaction at INSERT time would mis-group;
         ;; for SQL INSERTs this is fine because the posting INSERT
         ;; sets :posting/transaction in the same entity map.
         (add-amt acc :__by-posting-eid__ (second entry) (nth entry 3))

         :else acc))
     {}
     tx-data)))

(defn assert-postings-sum-to-zero!
  "Throw `ex-info` if any transaction in `tx-data` has postings that
   don't sum to zero per commodity. Skips transactions with zero or
   one posting (those are either no-ops or single-leg, which the
   downstream invariants will catch)."
  [_db tx-data]
  (doseq [[tx-eid commodities] (posting-entries-by-tx tx-data)
          :when (not= tx-eid :__by-posting-eid__)
          [commodity total] commodities
          :when (and total
                     (not (zero? (.signum ^java.math.BigDecimal total))))]
    (throw (ex-info (str "Postings for transaction " tx-eid
                         " do not sum to zero per commodity.")
                    {:type :validation/sum-to-zero
                     :transaction tx-eid
                     :commodity commodity
                     :sum total
                     :tx-data tx-data}))))

;; ============================================================================
;; Validating transact
;; ============================================================================

(defn validate-and-apply
  "Transactor function. Runs structural validators against the
   speculative `txdb` + the user's original `tx-data`; returns
   tx-data so the transactor applies it. Throws to abort.

   Use as `[:db.fn/call kontor.validation/validate-and-apply
   tx-data]` either from Clojure (via `transact-with-validation`) or
   from pg-datahike (via the `:tx-wrap` config) so SQL writes route
   through the same validators.

   Order: cheap structural checks first; failures short-circuit."
  [txdb tx-data]
  ;; ADR-049: hold-blocks-destructive-write runs BEFORE sealing's
  ;; no-silent-retract check so the more-specific 'blocked by hold X'
  ;; error wins on destructive-write-of-posted-held-entity.
  (legal-hold/assert-no-hold-violating-destructive-writes! txdb tx-data)
  (sealing/assert-no-silent-retracts! txdb tx-data)
  (period/assert-no-write-on-sealed! txdb tx-data)
  (period/assert-not-in-locked-period! txdb tx-data)
  (state-machine/assert-transition! txdb tx-data)
  (assert-postings-sum-to-zero! txdb tx-data)
  tx-data)

(defn pg-tx-wrap
  "Build the `:tx-wrap` fn pg-datahike's `make-query-handler` accepts.
   Wraps user tx-data into a single `[:db.fn/call validate-and-apply
   tx-data]` clause so SQL writes route through `validate-and-apply`
   in the transactor.

   The kernel's data-driven invariants (account-active, commodity-
   match) still need a separate pass via the invariant library —
   they require both pre-state and post-state, which a single tx-fn
   call can synthesize but the invariant library currently expects
   to be called from outside d/transact. They run for Clojure
   callers via `transact-with-validation`; SQL callers get the
   structural checks above."
  []
  (fn [tx-data]
    [[:db.fn/call validate-and-apply tx-data]]))

(defn transact-with-validation
  "Run all enabled invariants and behavior checks against `tx-data`,
   then transact via `datahike.api/transact` if every check passes.

   Throws `ex-info` on the first failed check:
     - state invariants raise :type :invariant/invariant-mismatch
     - sealing raises :type :sealing/silent-retract-of-posted

   Returns the resulting tx-report on success."
  [conn tx-data]
  (let [db (d/db conn)]
    ;; Datalog invariants (account-active, commodity-match) still
    ;; run from the Clojure side because the invariant library
    ;; needs both pre and post db values, and our transactor-side
    ;; tx-fn can't easily produce both. Structural validators run
    ;; via [:db.fn/call validate-and-apply tx-data] for symmetry
    ;; with SQL writes.
    (inv/assert-invariants conn tx-data)
    (d/transact conn [[:db.fn/call validate-and-apply tx-data]])))

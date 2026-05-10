(ns datahike-accounting.validation
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
          - period-locked (`period.clj`): no posting whose
            :posting/valid-from falls inside a closed
            :period/locked-at period.
        Planned:
          - state-machine: :transaction/state transitions follow
            draft → posted → cancelled, no skipping or regressing."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [datahike-accounting.period :as period]
            [datahike-accounting.sealing :as sealing]
            [datahike-accounting.state-machine :as state-machine]
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
;; Validating transact
;; ============================================================================

(defn transact-with-validation
  "Run all enabled invariants and behavior checks against `tx-data`,
   then transact via `datahike.api/transact` if every check passes.

   Throws `ex-info` on the first failed check:
     - state invariants raise :type :invariant/invariant-mismatch
     - sealing raises :type :sealing/silent-retract-of-posted

   Returns the resulting tx-report on success."
  [conn tx-data]
  ;; Order: cheap structural checks first, then the (more expensive)
  ;; datalog invariant queries. Failures short-circuit; the first
  ;; throw stops the chain.
  (let [db (d/db conn)]
    (sealing/assert-no-silent-retracts! db tx-data)
    (period/assert-no-write-on-sealed! db tx-data)
    (period/assert-not-in-locked-period! db tx-data)
    (state-machine/assert-transition! db tx-data)
    (inv/assert-invariants conn tx-data)
    (d/transact conn tx-data)))

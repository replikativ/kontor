(ns kontor.validation
  "Runtime validation of accounting transactions.

   Two-layer model per ADR-011:

     1. **State-shape invariants** via the `invariant` library: declared
        as datalog queries in `resources/invariants/*.edn`, registered
        on the connection, evaluated automatically on every
        `transact-with-validation` call. Currently:
          - account-active: every :kontor.posting/account refers to an active
            account.
          - commodity-match: posting commodity matches the account's
            commodity when the account specifies one.

     2. **Behavior / lifecycle constraints** as hand-rolled middleware.
        Currently:
          - sealing (`sealing.clj`): no silent retract of
            :kontor.posting/posted-at-marked entities (ADR-007).
          - period-locked (`period.clj`): no posting whose tx's
            :tx/valid-from (kontor.bitemporal) falls inside a closed
            :kontor.period/locked-at period.
          - state-machine (`state_machine.clj`): :kontor.transaction/state
            transitions follow draft → posted → cancelled, no skipping
            or regressing (ADR-034, ADR-068).
          - legal-hold (`legal_hold.clj`): no destructive write against
            an entity in an active hold's scope (ADR-049).

   The composed gate-fn `validate-and-apply` runs all 4 middleware
   validators in order. Per T-2 of note 160 the gate API itself
   lives in `kontor.gate`; this namespace registers the composed
   `validate-and-apply` into `kontor.gate` at load time."
  (:require #?(:clj [clojure.java.io :as io])
            [datahike.api :as d]
            [kontor.gate :as gate]
            [kontor.money :as money]
            [kontor.compliance.legal-hold :as legal-hold]
            [kontor.compliance.period :as period]
            [kontor.compliance.sealing :as sealing]
            [kontor.workflow.state-machine :as state-machine]
            [kontor.invariant :as inv]))

;; ============================================================================
;; Built-in invariants
;;
;; The `:invariant/rule` + `:invariant/query` attribute DECLARATIONS
;; live in `kontor.schema` (T-7 of note 160) so a single
;; `(kontor.schema/all)` surfaces every kernel attr. The ROW data
;; (one row per registered invariant) is installed by
;; `install-invariants!` below — that part stays here because it
;; depends on the EDN-on-disk + the registration sequencing.
;; ============================================================================

#?(:clj
   (defn- read-invariant
     "Read an invariant query from resources/invariants/<name>.edn and
      return the EDN string ready for storage. JVM-only: the browser can't
      slurp resources, so cljs inlines the same queries below."
     [resource-name]
     (let [r (io/resource (str "invariants/" resource-name ".edn"))]
       (when-not r
         (throw (ex-info "Invariant resource not found"
                         {:name resource-name
                          :looked-in (str "resources/invariants/" resource-name ".edn")})))
       (slurp r))))

;; The invariant queries: JVM reads them verbatim from resources/invariants/;
;; cljs inlines the equivalent EDN string (edn/read-string parses to the same
;; query — the resource copy carries datalog-gotcha comments the reader drops).
(def ^:private account-active-query
  #?(:clj  (read-invariant "account_active")
     :cljs "[:find ?matches .
             :in $before $after $empty+txs $txs
             :where
             [(q [:find ?p
                  :in $after $empty+txs
                  :where
                  [$empty+txs ?p :kontor.posting/account ?account]
                  [$after ?account :kontor.account/active false]]
                 $after $empty+txs)
              ?violators]
             [(count ?violators) ?n-violators]
             [(= 0 ?n-violators) ?matches]]"))

(def ^:private commodity-match-query
  #?(:clj  (read-invariant "commodity_match")
     :cljs "[:find ?matches .
             :in $before $after $empty+txs $txs
             :where
             [(q [:find ?p
                  :in $after $empty+txs
                  :where
                  [$empty+txs ?p :kontor.posting/account ?account]
                  [$empty+txs ?p :kontor.posting/commodity ?p-commodity]
                  [$after ?account :kontor.account/commodity ?a-commodity]
                  [(not= ?p-commodity ?a-commodity)]]
                 $after $empty+txs)
              ?violators]
             [(count ?violators) ?n-violators]
             [(= 0 ?n-violators) ?matches]]"))

(def kernel-invariants
  "The built-in invariants the kernel installs. Each is `{:rule attr
   :query <edn-string>}`. The :rule attribute is what triggers the
   invariant — it must appear in the tx's affected attributes. Add
   new ones here as separate slices land.

   Two invariants on different rule attrs both fire when a typical
   posting tx touches both attributes."
  [{:rule  :kontor.posting/account
    :query account-active-query}
   {:rule  :kontor.posting/commodity
    :query commodity-match-query}])

(defn install-invariants!
  "Register the kernel's built-in invariants (account-active +
   commodity-match) on `conn`. The `:invariant/rule` +
   `:invariant/query` attribute DECLARATIONS are part of
   `kontor.schema/all` (T-7 of note 160) and already installed by
   `kontor.schema/install!`. This fn only transacts the rule ROWS.

   Idempotent — re-installing replaces existing :invariant/query
   strings for each :invariant/rule (one query per rule keyword,
   enforced by `:invariant/rule`'s :db.unique/identity)."
  [conn]
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
   Handles both entity-map shape (`{:kontor.posting/account … :kontor.posting/amount …}`)
   and tuple shape (`[:db/add eid :kontor.posting/amount v]`).
   Tx-data without :kontor.posting/amount entries returns {} (no postings)."
  [tx-data]
  (let [add-amt
        (fn [acc tx-eid commodity amount]
          (let [amt #?(:clj (bigdec amount) :cljs (money/->amount amount))]
            (update-in acc [tx-eid commodity]
                       (fnil #(money/add-amount % amt) (money/zero-amount)))))]
    (reduce
     (fn [acc entry]
       (cond
         ;; Entity map with posting attrs.
         (and (map? entry) (:kontor.posting/amount entry))
         (let [tx-eid (:kontor.posting/transaction entry)
               commodity (:kontor.posting/commodity entry)
               amount (:kontor.posting/amount entry)]
           (cond-> acc
             (and tx-eid amount)
             (add-amt tx-eid commodity amount)))

         ;; [:db/add eid :kontor.posting/amount v]
         (and (vector? entry) (= 4 (count entry))
              (= :db/add (first entry))
              (= :kontor.posting/amount (nth entry 2)))
         ;; We don't know the tx-eid from this tuple alone, so we
         ;; group by the posting eid (`(second entry)`) — datalog
         ;; later associates it with the transaction. Postings without
         ;; an explicit transaction at INSERT time would mis-group;
         ;; for SQL INSERTs this is fine because the posting INSERT
         ;; sets :kontor.posting/transaction in the same entity map.
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
                     (not (money/amount-zero? total)))]
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

;; ============================================================================
;; Gate wiring (T-2 in note 160)
;;
;; The actual `transact-with-validation` lives in `kontor.gate` so that
;; sub-validators (`bitemporal`, `period`, `legal-hold`, `status-machine`,
;; `posting`) can require it without creating a cycle through this
;; namespace. We register our composed `validate-and-apply` into the
;; gate at load time + re-export `gate/transact-with-validation` under
;; this namespace's name for backward compatibility with existing
;; callers (`(:require [kontor.validation :as validation])`
;; → `(validation/transact-with-validation conn tx-data)`).
;; ============================================================================

(gate/register-validate-and-apply! validate-and-apply)

(def transact-with-validation
  "Alias for `kontor.gate/transact-with-validation` — kept here so
   existing call sites using `[kontor.validation :as v]` continue
   to work. New code should require `kontor.gate` directly to make
   the gate dependency explicit."
  gate/transact-with-validation)

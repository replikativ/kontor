(ns kontor.invariant
  "Datalog-expressed transactional invariants for datahike.

   Each invariant is a 4-source Datalog query parametrized over
   `$before`, `$after`, `$empty-with-txs`, `$tx-seq` — registered under an
   `:invariant/rule` keyed on an attribute. When a transaction asserts
   that attribute, the invariant fires; if its query returns no match,
   the transact is rejected with `:invariant/invariant-mismatch`.

   ## Provenance — vendored from datopia/invariant

   This namespace is a focused vendor of github.com/datopia/invariant
   (MIT licence; maintained by the kontor maintainer; no external coordination
   needed). The upstream supports both datahike and datomic; kontor uses only
   the datahike path and uses exactly one function (`assert-invariants`), so
   the full library was collapsed into this single namespace to honour
   ADR-001's single-dep posture (datahike-only). Removed from upstream:

     - `invariant.core`       — the multimethod that dispatched between
                                 datahike/datomic. kontor is datahike-only.
     - `invariant.datomic`    — datomic adapter. Unused.
     - `invariant.transaction` + `invariant.unparse` — unused query helpers.
     - `invariant.datahike/+` — a `:db.fn/call`-form helper for transactor-
                                 side balance arithmetic. kontor's posting
                                 builders compute amounts on the Clojure side,
                                 so this form is never produced.

   Kept verbatim (apart from namespace renames and a defensive `:default`
   `get-attribute` method): the query validator, the `get-attribute`
   multimethod (so callers can extend it for custom tx-fn shapes), the
   `sanitize-schema` + `invariant-holds?` + `assert-invariants` pipeline.

   See ADR-011 + research note 04 for the kernel's adoption.

   The `(alter-var-root #'dq/built-ins assoc 'subquery datahike.api/q)`
   side effect at namespace load is what enables `(subquery …)` calls
   inside invariant queries; preserved here."
  (:refer-clojure :exclude [+])
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.core :as dc]
            [datahike.query :as dq]
            [datalog.parser :as p]))

(alter-var-root #'dq/built-ins assoc 'subquery datahike.api/q)

;; ============================================================================
;; Query validator (was invariant.query)
;; ============================================================================

(def invariant-query
  "Find the registered invariant query for an attribute."
  '[:find ?q .
    :in $ ?a
    :where
    [?e :invariant/rule ?a]
    [?e :invariant/query ?q]])

(def ^:dynamic *allowed-fns*
  "Whitelist of fn-symbols allowed inside an invariant query — every
   built-in datahike query fn plus `subquery`. Override via `binding`
   if your invariants need to call additional pure fns."
  (into #{'subquery} (keys datahike.query/built-ins)))

(let [fn-selector (comp #{datalog.parser.type.Function
                          datalog.parser.type.Predicate} type)]
  (defn assert-valid-query
    "Walk `query` and reject if it (a) doesn't have exactly 4 sources
     (`$before $after $empty-with-txs $tx-seq`), (b) calls a fn that
     isn't in `*allowed-fns*`. Recurses into `(subquery …)`."
    [query]
    (let [res        (p/parse query)
          called-fns (filter fn-selector (:qwhere res))]
      (when-not (= (count (:qin res)) 4)
        (throw (ex-info "The query operates on exactly 4 sources: $before, $after, $empty-with-txs, $tx-seq"
                        {:type    :invariant/number-of-sources-not-4
                         :sources (:qin res)})))
      (doseq [c called-fns
              :let [f (:symbol (:fn c))]]
        (when (#{'subquery} f)
          (let [q (:value (first (:args c)))]
            (assert-valid-query q)))
        (when-not (*allowed-fns* f)
          (throw (ex-info "Function not allowed."
                          {:type :invariant/invalid-function-call
                           :call c})))))))

;; ============================================================================
;; Attribute extraction — multimethod over the tx-data shapes
;; ============================================================================

(defn get-attribute-dispatch [v]
  (cond
    (map? v) :entity-map
    :else
    (let [[a _] v]
      (cond (= :db.fn/call a) [:db.fn/call (second v)]
            :else             a))))

(defmulti get-attribute
  "Returns either a single attribute keyword (for `[:db/add …]` /
   `[:db/retract …]` / `[:db.fn/call …]` tuples) OR a set of attribute
   keywords for entity-map tx forms (`{:db/id _ :foo 1 :bar 2}`).

   The single-vs-set return is what `assert-invariants` uses to schedule
   the right invariants per tx.

   Multimethod is open: callers can `defmethod` it for custom tx-fn
   shapes."
  get-attribute-dispatch)

(defmethod get-attribute :db/add
  [[_ _ a _]]
  a)

(defmethod get-attribute :db/retract
  [[_ _ a _]]
  a)

;; All "destructive" tx-data shapes — none asserts a new attribute value,
;; so per-attribute invariants have nothing to schedule against. Matching
;; the form-set `kontor.compliance.legal-hold` enumerates (`:db/purge`,
;; `:db.purge/entity`, `:db.purge/attribute`, `:db.fn/retractEntity`,
;; `:db.fn/cas`) so any of them rides inside a `transact-with-validation`
;; without crashing `spread-attrs`. `:db.fn/cas` IS an assertion
;; conceptually but the `(eid, attr, old, new)` tuple gives a `[_ _ a]`
;; arg position — left as nil for now; a future revision could lift it
;; like `:db/add`.
(defmethod get-attribute :db/retractEntity     [_] nil)
(defmethod get-attribute :db.fn/retractEntity  [_] nil)
(defmethod get-attribute :db/purge             [_] nil)
(defmethod get-attribute :db.purge/entity      [_] nil)
(defmethod get-attribute :db.purge/attribute   [_] nil)
(defmethod get-attribute :db.fn/cas            [_] nil)

(defmethod get-attribute :entity-map
  [m]
  (->> (keys m)
       (remove #{:db/id})
       set))

;; Defensive default — unknown tx-forms (custom tx-fns the consumer
;; registers) don't crash the invariant pipeline. The trade-off is that
;; the invariant doesn't fire for them; the consumer must `defmethod`
;; explicitly if they want coverage.
(defmethod get-attribute :default [_] nil)

;; ============================================================================
;; Invariant checking — the assert-invariants pipeline
;; ============================================================================

(defn- sanitize-schema
  "Datahike's internal `:schema` map carries THREE kinds of entries
   that all need to be filtered before it can be re-fed to
   `dc/empty-db`:

     1. keyword → spec maps (the genuine user-defined attrs we want)
     2. integer → ident maps (the reverse lookup datahike maintains)
     3. keyword → partial-spec maps for system/bootstrap attrs like
        `:db/ident`, `:db/txInstant`, `:db.entity/attrs`, etc. — these
        lack `:db/valueType` because datahike manages them internally,
        but `empty-db`'s `:write` validator rejects partial specs.

   Keep only entries with both `:db/valueType` and `:db/cardinality`
   present. This is exactly the contract `::old-schema-val` enforces."
  [schema]
  (when schema
    (into {}
          (filter (fn [[k v]]
                    (and (keyword? k)
                         (map? v)
                         (contains? v :db/valueType)
                         (contains? v :db/cardinality))))
          schema)))

;; ============================================================================
;; Lookup-ref resolution for the $empty+txs source
;;
;; Kontor's invariants — and most real-world tx-data — reference existing
;; entities via lookup-refs like `[:kontor.account/path "Assets:Cash"]` or
;; `[:kontor.commodity/symbol "USD"]`. When the invariant pipeline applied
;; the user's tx-data to a literally-empty db (the 3rd source), every
;; lookup-ref would fail with `:entity-id/missing` — the fresh empty-db
;; has no entities matching the unique-attr lookup target.
;;
;; The fix: before applying tx-data to the empty-db, RESOLVE each
;; lookup-ref against the LIVE conn + SEED the empty-db with a stub
;; `{:db/id <eid> <unique-attr> <value>}` map for each one. The lookup-ref
;; then resolves naturally; the empty-db sees the same posting at the same
;; eid the live db does; and the user's tx-data is preserved verbatim in
;; $empty+txs (so the existing kontor invariants — which read postings via
;; `[$empty+txs ?p :kontor.posting/account ?account]` then look up account
;; properties via `[$after ?account ...]` — keep working).
;;
;; A lookup-ref that doesn't resolve in the live conn (referenced entity
;; doesn't exist yet) is left as-is — db-with will then create it fresh
;; with whatever attrs the user's tx-data supplies. That matches what
;; would happen on the live db too.
;;
;; This implementation handles the kontor invariant gate's tx-data shapes:
;; entity-map values (`{:kontor.posting/account [...]}`), `:db/add`/
;; `:db/retract` value positions, and lookup-refs at `:db/id` positions.
;; Cardinality-many ref attrs (vector-of-refs) also walk correctly via
;; postwalk.
;; ============================================================================

(defn- lookup-ref?
  "A 2-element non-MapEntry vector whose first element is a USER
   attribute keyword (rules out `:db/retractEntity`, `:db.fn/cas`,
   `:db.purge/entity`, etc. — all 2-element tx-tuples whose head
   keyword is in datahike's reserved `db` / `db.X` namespaces).
   Matches datahike's lookup-ref shape; rejects MapEntries (which
   look like 2-element vectors to `vector?` because of
   IPersistentVector)."
  [x]
  (and (vector? x)
       (not (map-entry? x))
       (= 2 (count x))
       (keyword? (first x))
       (let [ns (namespace (first x))]
         (or (nil? ns)
             (and (not= ns "db")
                  (not (.startsWith ^String ns "db.")))))))

(defn- collect-lookup-refs
  "Walk `tx-data` and return the set of every lookup-ref `[:attr value]`
   encountered. Visits every position via `postwalk` so it catches
   refs at entity-map values, tuple-tx value positions, `:db/id`
   positions, and within cardinality-many vectors."
  [tx-data]
  (let [refs (atom #{})]
    (walk/postwalk
     (fn [x]
       (when (lookup-ref? x) (swap! refs conj x))
       x)
     tx-data)
    @refs))

(defn- seed-from-lookup-refs
  "For each lookup-ref `[attr val]` in `refs`, produce a seed map the
   empty-db can apply so the user's tx-data's lookup-ref to the same
   `[attr val]` resolves cleanly.

   Two cases:

   - **Lookup-ref resolves in the live db** — seed with `{:db/id <eid>
     attr val}`. Pinning the eid to the live-db value keeps `?account`
     bindings consistent across the `$after` and `$empty+txs` query
     sources, so join-by-eid in invariant queries works.

   - **Lookup-ref doesn't resolve** — entity is being CREATED by this
     same tx (a self-referential upsert). Seed with a tempid-keyed
     map `{:db/id <tempid> attr val}`. db-with on the empty-db then
     creates the entity at a fresh eid; the user's tx-data's
     lookup-ref upserts onto the same entity. The eid won't match
     the live-db side, but for an entity that doesn't exist in $after
     either, joins via that eid wouldn't fire anyway (the absent
     entity has no attrs to violate an invariant against).

   Either way, the seed carries only the identifying attr — not the
   full entity — so $empty+txs stays minimal."
  [db refs]
  (->> refs
       (map-indexed
        (fn [i [a v :as ref]]
          (if-let [eid (:db/id (d/entity db ref))]
            {:db/id eid a v}
            {:db/id (str "kontor.invariant/seed-" i) a v})))
       vec))

;; The datahike query planner (default-on since datahike 0.8.1705) can
;; mis-plan the invariant queries below — a nested `(q …)` subquery bound to
;; a var, then reduced to a boolean via `count`/`=` — on realistically-sized
;; `$after` databases: it returns `nil` for the scalar find where the base
;; (relational) engine correctly returns `true`, fabricating a spurious
;; invariant mismatch. Confirmed cardinality-dependent (a tiny reconstruction
;; plans correctly; only a large db trips it). See research note 190; a
;; minimal repro is tracked as a datahike bug.
;;
;; Integrity invariants are correctness checks where the planner's *speed* is
;; irrelevant but its *correctness* is essential, so we evaluate them on the
;; base engine. The toggle is resolved dynamically so kontor still loads on
;; datahike versions predating it (where the var — and the bug — don't exist).
(def ^:private disable-planner-var
  (try (requiring-resolve 'datahike.query/*disable-planner*)
       (catch Throwable _ nil)))

(defn- run-inv-query
  "Run an invariant query with the datahike planner disabled (base engine),
   falling back to a plain `d/q` when the toggle is unavailable."
  [q & sources]
  (if disable-planner-var
    (with-bindings {disable-planner-var true} (apply d/q q sources))
    (apply d/q q sources)))

(defn- invariant-holds? [inv-qs conn tx-data schema]
  ;; Datahike's `dc/empty-db` defaults to `:schema-flexibility :read`,
  ;; whose validator rejects predeclared scalar types
  ;; (string/long/bigdec/...). When the source conn was opened with
  ;; `:write` flexibility (the kernel-recommended setting for
  ;; accounting), pass that through so `empty-db` accepts the same
  ;; schema. We read the conn's resolved flexibility off the live db
  ;; value rather than reaching into the config; this works for both
  ;; legacy and current datahike layouts.
  (let [flex   (or (:schema-flexibility @conn)
                   (get-in @conn [:config :schema-flexibility])
                   :read)
        schema (sanitize-schema schema)
        db     @conn
        ;; Seed the empty-db with the entities referenced by lookup-refs
        ;; in tx-data, using the eid + identifying attr from the live db.
        ;; Without this seed, the third source (`$empty+txs`) fails on
        ;; any tx-data that contains lookup-refs like
        ;; `[:kontor.commodity/symbol "USD"]` — datahike's `db-with`
        ;; can't resolve them against a literally-empty db.
        refs        (collect-lookup-refs tx-data)
        seed        (seed-from-lookup-refs db refs)
        empty-db    (dc/empty-db schema {:schema-flexibility flex})
        empty+seed  (if (seq seed) (dc/db-with empty-db seed) empty-db)]
    (run-inv-query (edn/read-string inv-qs)
                   ;; current state
                   db
                   ;; apply transaction to current state
                   (dc/db-with db tx-data)
                   ;; empty database + lookup-ref seeds, with transaction applied
                   (dc/db-with empty+seed tx-data)
                   tx-data)))

(defn- spread-attrs
  "`get-attribute` returns either a single attribute (for tuple tx forms)
   or a set of attributes (for entity-map tx forms). Spread to a
   sequence of `[attr tx]` pairs in either case."
  [tx]
  (let [a (get-attribute tx)]
    (if (set? a)
      (map (fn [k] [k tx]) a)
      [[a tx]])))

(defn assert-invariants
  "For each attribute mentioned in `tx-data`, run any registered
   `:invariant/query` against the (before, after, empty+txs, txs)
   4-source datalog form. Throw `{:type :invariant/invariant-mismatch
   :attribute …}` on any failed invariant.

   Also validates that any `:invariant/query` BEING REGISTERED via
   `tx-data` parses as a valid 4-source query first — registering an
   invalid invariant is caught up-front, not at first-fire."
  [conn tx-data]
  (let [attr-txs (mapcat spread-attrs tx-data)
        attrs    (distinct (map first attr-txs))
        schema   (:schema @conn)]
    (doseq [[a tx] attr-txs
            :when (= a :invariant/query)
            :let  [v (cond
                       ;; flat tuple: [:db/add e :invariant/query "..."]
                       (and (vector? tx) (= 4 (count tx))) (nth tx 3)
                       ;; entity map: {:invariant/query "..."}
                       (map? tx)                            (:invariant/query tx))]]
      (assert-valid-query (edn/read-string v)))

    (doseq [a attrs
            :let  [inv-qs (d/q invariant-query @conn a)]
            :when inv-qs]
      (when-not (invariant-holds? inv-qs conn tx-data schema)
        (throw (ex-info "Invariant mismatch."
                        {:type      :invariant/invariant-mismatch
                         :attribute a
                         :invariant (edn/read-string inv-qs)
                         :tx-data   tx-data}))))
    true))

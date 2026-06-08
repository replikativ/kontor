(ns kontor.invariant-test
  "Focused unit tests for the vendored invariant primitive.

   The full end-to-end pipeline (registered invariant fires inside
   `transact-with-validation`) is exercised extensively by every
   kontor test that uses the validation gate — `posting_test`,
   `book_test`, the per-l10n closing tests, etc. This file holds the
   primitive-level coverage that doesn't depend on a full kontor
   schema fixture:

     - attribute extraction across every tx-data shape
     - query-validator shape rejection (4-source contract + allowed-fn
       whitelist + subquery recursion)
     - the `:default` get-attribute method's defensive nil return
     - lookup-ref handling in the `$empty+txs` source (the T-3 fix)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.invariant :as inv]))

;; ============================================================================
;; get-attribute multimethod
;; ============================================================================

(deftest get-attribute-tuple-forms
  (testing ":db/add tuple → single attribute keyword"
    (is (= :foo (inv/get-attribute [:db/add 1 :foo 2]))))
  (testing ":db/retract tuple → single attribute keyword"
    (is (= :foo (inv/get-attribute [:db/retract 1 :foo 2])))))

(deftest get-attribute-entity-map
  (testing "Entity-map tx form → set of touched attrs (excluding :db/id)"
    (is (= #{:foo :bar} (inv/get-attribute {:db/id -1 :foo 1 :bar 2})))
    (is (= #{}          (inv/get-attribute {:db/id -1})))))

(deftest get-attribute-destructive-forms-yield-nil
  (testing "Destructive / non-assertive tx-forms → nil (no per-attr invariant to fire)"
    (is (nil? (inv/get-attribute [:db.fn/retractEntity 1])))
    (is (nil? (inv/get-attribute [:db/retractEntity 1])))
    (is (nil? (inv/get-attribute [:db/purge 1 :foo 2])))
    (is (nil? (inv/get-attribute [:db.purge/entity 1])))
    (is (nil? (inv/get-attribute [:db.purge/attribute 1 :foo])))
    (is (nil? (inv/get-attribute [:db.fn/cas 1 :foo 2 3])))))

(deftest get-attribute-default-method-is-defensive
  (testing "An unknown tx-form (e.g. a consumer's custom tx-fn) returns nil
            rather than throwing. Consumers can defmethod for coverage."
    (is (nil? (inv/get-attribute [:db.fn/some-custom-fn 1 2 3])))))

;; ============================================================================
;; assert-valid-query — query-shape validator
;; ============================================================================

(def ^:private valid-query
  '[:find ?matches .
    :in $before $after $empty-with-txs $tx-seq
    :where [(= 0 0) ?matches]])

(deftest valid-query-passes
  (testing "A 4-source query with only built-in fns is accepted"
    (is (nil? (inv/assert-valid-query valid-query)))))

(deftest wrong-source-count-throws
  (testing "Query with the wrong source count throws with shape :number-of-sources-not-4"
    (let [e (try (inv/assert-valid-query
                  '[:find ?m :in $only-one :where [(= 0 0) ?m]])
                 nil (catch Exception ex ex))]
      (is (some? e))
      (is (= :invariant/number-of-sources-not-4 (:type (ex-data e)))))))

(deftest disallowed-fn-throws-incl-inside-subquery
  (testing "A query calling a fn outside *allowed-fns* (including nested
            inside a subquery) throws :invariant/invalid-function-call"
    (let [e (try (inv/assert-valid-query
                  '[:find ?a
                    :in $a $b $c $d
                    :where
                    [(subquery [:find ?a
                                :in $a $b $c $d
                                :where [(nested-evil ?a 5)]]
                               $a $b $c $d) ?a]])
                 nil (catch Exception ex ex))]
      (is (some? e))
      (is (= :invariant/invalid-function-call (:type (ex-data e))))
      (is (some? (:call (ex-data e)))))))

;; ============================================================================
;; Lookup-ref handling in $empty+txs (T-3 fix in note 160)
;; ============================================================================
;;
;; Background: the third source the invariant pipeline exposes,
;; `$empty+txs`, was built by `(dc/db-with (dc/empty-db schema) tx-data)`.
;; If tx-data contained a lookup-ref like `[:account/code "1000"]`, the
;; empty-db had no entities matching it and `db-with` threw
;; `:entity-id/missing`. The fix walks tx-data for lookup-refs, resolves
;; them against the live conn, seeds the empty-db with stub entities
;; carrying just the identifying attr + eid, and only then applies
;; tx-data.
;;
;; These tests pin the new behavior so a regression in the walker / seed
;; helper would fail loudly rather than re-surface as the original
;; entity-id/missing crash deep in business-write call paths.

(def ^:private collect-lookup-refs #'inv/collect-lookup-refs)
(def ^:private lookup-ref?         #'inv/lookup-ref?)

(deftest lookup-ref-predicate-shape
  (testing "Plain 2-vec with keyword head is a lookup-ref"
    (is (true? (lookup-ref? [:account/code "1000"])))
    (is (true? (lookup-ref? [:commodity/symbol "USD"]))))
  (testing "Tuples and non-2-element vectors are NOT lookup-refs"
    (is (false? (lookup-ref? [:db/add 1 :foo 2])))
    (is (false? (lookup-ref? [:db/retractEntity 1])))
    (is (false? (lookup-ref? [:a :b :c])))
    (is (false? (lookup-ref? [42 "USD"]))) ; first must be keyword
    (is (false? (lookup-ref? "string"))))
  (testing "MapEntries are 2-element vectors but explicitly rejected"
    ;; A MapEntry like {:db/id [...]} would otherwise misfire.
    (is (false? (lookup-ref? (first {:db/id [:account/code "1000"]}))))))

(deftest collect-lookup-refs-walks-all-positions
  (testing "Lookup-refs at entity-map value positions"
    (is (= #{[:account/code "1000"] [:commodity/symbol "USD"]}
           (collect-lookup-refs
            [{:db/id "p1"
              :posting/account  [:account/code "1000"]
              :posting/commodity [:commodity/symbol "USD"]}]))))
  (testing "Lookup-refs at :db/id positions"
    (is (= #{[:account/code "1000"]}
           (collect-lookup-refs
            [{:db/id [:account/code "1000"] :account/active false}]))))
  (testing "Lookup-refs inside cardinality-many vectors"
    (is (= #{[:entity/code "A"] [:entity/code "B"]}
           (collect-lookup-refs
            [{:db/id "e"
              :entity/family [[:entity/code "A"] [:entity/code "B"]]}]))))
  (testing "Lookup-refs in tuple-tx value positions"
    (is (= #{[:account/code "1000"]}
           (collect-lookup-refs
            [[:db/add "p1" :posting/account [:account/code "1000"]]]))))
  (testing "Tx-data without lookup-refs returns empty"
    (is (= #{} (collect-lookup-refs [{:db/id 1 :foo 2}])))
    (is (= #{} (collect-lookup-refs [[:db/add 1 :foo 2]])))))

;; ============================================================================
;; End-to-end: assert-invariants with lookup-refs in tx-data
;; ============================================================================
;;
;; Verifies the empty-db source seeds correctly when tx-data references
;; existing entities via lookup-refs. Uses a minimal schema (account + a
;; ref-typed posting attr) and a hand-written invariant that reads from
;; $empty+txs + $after — the same shape kontor's account-active and
;; commodity-match invariants use.

(def ^:private mini-schema
  [{:db/ident :account/code
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/active
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :posting/account
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :invariant/rule
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :invariant/query
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- mini-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn mini-schema)
      ;; Seed an account so subsequent tx-data can reference it via lookup-ref.
      (d/transact conn [{:account/code "1000" :account/active true}])
      ;; Register an invariant that uses BOTH $empty+txs (to find new
      ;; postings) and $after (to look up the account's active flag) —
      ;; exactly the kontor account-active shape.
      (d/transact conn
                  [{:invariant/rule  :posting/account
                    :invariant/query
                    (pr-str
                     '[:find ?ok .
                       :in $before $after $empty+txs $txs
                       :where
                       [(q [:find ?p
                            :in $after $empty+txs
                            :where
                            [$empty+txs ?p :posting/account ?a]
                            [$after ?a :account/active false]]
                           $after $empty+txs)
                        ?violators]
                       [(count ?violators) ?n]
                       [(= 0 ?n) ?ok]])}])
      conn)))

(deftest empty-db-source-resolves-lookup-refs-no-crash
  (testing "tx-data with a lookup-ref no longer crashes the empty-db source"
    (let [conn (mini-conn)
          ;; The original T-3 failure was here: db-with on the empty-db
          ;; threw :entity-id/missing for [:account/code "1000"]. The
          ;; lookup-ref-seed fix lets this complete.
          tx [{:db/id "p1" :posting/account [:account/code "1000"]}]]
      (is (true? (inv/assert-invariants conn tx))))))

(deftest empty-db-source-still-fires-invariant-on-violation
  (testing "Setting the referenced account inactive in the same tx triggers
            the registered invariant — fix preserves invariant semantics"
    (let [conn (mini-conn)
          ;; This tx-data writes a posting AND flips the account inactive.
          ;; The invariant query reads $empty+txs for the posting + $after
          ;; for the account's active flag → the posting's referenced
          ;; account is now inactive in $after → violation should fire.
          tx [{:db/id "p1" :posting/account [:account/code "1000"]}
              {:db/id [:account/code "1000"] :account/active false}]
          ex (try (inv/assert-invariants conn tx)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :invariant/invariant-mismatch (:type (ex-data ex))))
      (is (= :posting/account (:attribute (ex-data ex)))))))

;; A NOTE on the "upsert + self-reference in one tx" case:
;;
;; The seed helper handles two cases: lookup-refs that resolve in the
;; live db (seeded with the live eid) and lookup-refs that don't
;; (seeded with a fresh tempid). The second case lets db-with on the
;; empty-db create the entity at the tempid eid.
;;
;; The pattern "tx creates entity X via lookup-ref :db/id position AND
;; references X via lookup-ref value position in a sibling map of the
;; SAME tx" is genuinely tricky in datahike's :write mode — both
;; live-db and empty-db sides of the invariant pipeline depend on
;; datahike's upsert ordering. Kontor's own business writes never
;; produce this shape (commodities / accounts / journals / partners
;; are always pre-seeded via `install-schema!` or earlier consumer
;; txs), so we don't test it here. If a consumer hits the case, the
;; resulting error surfaces on the live-db side first — the invariant
;; pipeline isn't introducing the problem.

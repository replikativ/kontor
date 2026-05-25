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
     - the `:default` get-attribute method's defensive nil return."
  (:require [clojure.test :refer [deftest is testing]]
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

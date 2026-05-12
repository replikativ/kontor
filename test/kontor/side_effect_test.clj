(ns kontor.side-effect-test
  "Tests for :side-effect-intent + kontor.side-effect — ADR-041."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.side-effect :as se]))

(def ^:dynamic *conn* nil)

(defn- bootstrap [f]
  (binding [*conn* (core/create-test-db)]
    (f)))

(use-fixtures :each bootstrap)

(defn- seed-intent! [k type payload]
  (d/transact *conn*
              [{:side-effect-intent/key k
                :side-effect-intent/type type
                :side-effect-intent/payload payload
                :side-effect-intent/status :pending
                :side-effect-intent/created-at (java.util.Date.)
                :side-effect-intent/retry-count 0
                :side-effect-intent/max-retries 3}]))

(deftest intent-lifecycle-happy-path
  (seed-intent! "intent-1" :send-email "{:to \"a@b\"}")
  (let [eid (se/by-key (d/db *conn*) "intent-1")]
    (testing "pending shows the intent"
      (is (= 1 (count (se/pending (d/db *conn*))))))
    (se/claim! *conn* eid)
    (testing "after claim, status is :processing"
      (let [intent (se/pull-intent (d/db *conn*) "intent-1")]
        (is (= :processing (:side-effect-intent/status intent)))
        (is (some? (:side-effect-intent/processing-at intent)))))
    (se/mark-done! *conn* eid)
    (testing "after mark-done!, status is :done"
      (let [intent (se/pull-intent (d/db *conn*) "intent-1")]
        (is (= :done (:side-effect-intent/status intent)))
        (is (some? (:side-effect-intent/processed-at intent)))))))

(deftest intent-failure-retries-then-abandons
  (seed-intent! "intent-2" :send-edi "{:msg \"850\"}")
  (let [eid (se/by-key (d/db *conn*) "intent-2")]
    (se/claim! *conn* eid)
    (se/mark-failed! *conn* eid "EDI gateway timeout")
    (testing "first failure: status is :failed, retry-count 1"
      (let [intent (se/pull-intent (d/db *conn*) "intent-2")]
        (is (= :failed (:side-effect-intent/status intent)))
        (is (= 1 (:side-effect-intent/retry-count intent)))))
    ;; Two more failures should still leave it :failed
    (se/claim! *conn* eid)
    (se/mark-failed! *conn* eid "again")
    (se/claim! *conn* eid)
    (se/mark-failed! *conn* eid "third strike — at the max")
    (testing "after max retries, status is :abandoned"
      (let [intent (se/pull-intent (d/db *conn*) "intent-2")]
        (is (= :abandoned (:side-effect-intent/status intent)))
        (is (= 3 (:side-effect-intent/retry-count intent)))))))

(deftest pending-filter-by-type
  (seed-intent! "i-email" :send-email "{}")
  (seed-intent! "i-edi" :send-edi "{}")
  (testing "filter by :type"
    (is (= 1 (count (se/pending (d/db *conn*) {:type :send-email}))))
    (is (= 1 (count (se/pending (d/db *conn*) {:type :send-edi}))))
    (is (= 2 (count (se/pending (d/db *conn*)))))))

(ns kontor.sealing-test
  "Sealing semantics: posted entries cannot be silently retracted.
   Per ADR-007, explicit purges ARE permitted (and recorded as their
   own commits in datahike's commit DAG)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.sealing :as sealing]
            [kontor.validation :as v]))

(def some-date #inst "2026-05-09T00:00:00Z")

(defn- seed-and-post!
  "Plant catalog, build a balanced tx, mark it posted, return the
   `:db-after` plus a posting eid for the test's retract attempts."
  [conn]
  (d/transact
   conn
   [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
    {:db/id -2 :kontor.account/path "Assets:Receivable" :kontor.account/name "AR"
     :kontor.account/type :asset :kontor.account/active true}
    {:db/id -3 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
     :kontor.account/type :income :kontor.account/active true}
    {:db/id -4 :journal/code "INV" :journal/name "J"
     :journal/type :sale :journal/active true}])
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
        rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        ;; Build, mark posted *in the same tx*, transact.
        tx-data (-> (posting/build-transaction
                     {:transaction
                      {:transaction/external-id    "INV-2026-0001"
                       :transaction/journal        jnl
                       :transaction/effective-date some-date
                       :transaction/narration      "ACME"}
                      :postings
                      [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                       {:posting/account rev :posting/amount -100M :posting/commodity eur}]})
                    (->> (mapv (fn [m]
                                 (cond-> m
                                   (some? (:posting/account m))
                                   (assoc :posting/posted-at some-date)
                                   (some? (:transaction/journal m))
                                   (assoc :transaction/posted-at some-date
                                          :transaction/state    :posted))))))
        report (d/transact conn tx-data)
        db-after (:db-after report)
        a-posting-eid (-> (d/q '[:find ?p .
                                 :in $ ?tx-eid
                                 :where [?p :posting/transaction ?tx-eid]]
                               db-after
                               (:db/id (d/entity db-after [:transaction/external-id "INV-2026-0001"]))))]
    {:db-after db-after :posting-eid a-posting-eid}))

;; ============================================================================
;; find-silent-retracts (pure)
;; ============================================================================

(deftest find-silent-retracts-empty-on-no-retract
  (let [conn (core/create-test-db)
        {:keys [db-after]} (seed-and-post! conn)]
    (is (= [] (sealing/find-silent-retracts db-after [])))
    (is (= [] (sealing/find-silent-retracts
               db-after
               [{:db/id -1 :kontor.commodity/symbol "USD"}])))))

(deftest find-silent-retracts-flags-retract-of-posted
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)
        retracts [[:db/retract posting-eid :posting/amount 100M]]
        violations (sealing/find-silent-retracts db-after retracts)]
    (is (= 1 (count violations)))
    (is (= posting-eid (-> violations first :eid)))))

(deftest find-silent-retracts-ignores-retract-of-unposted
  (testing "Retracting attrs on a draft entity (no :posting/posted-at)
            is fine — only posted entities are sealed."
    (let [conn (core/create-test-db)
          _ (d/transact conn [{:db/id -1 :kontor.commodity/symbol "EUR"
                               :kontor.commodity/name "Euro"
                               :kontor.commodity/precision 2}])
          db (d/db conn)
          eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
          retracts [[:db/retract eur :kontor.commodity/name "Euro"]]]
      (is (= [] (sealing/find-silent-retracts db retracts))))))

;; ============================================================================
;; assert-no-silent-retracts! (throws)
;; ============================================================================

(deftest assert-throws-on-silent-retract-of-posted
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (sealing/assert-no-silent-retracts!
          db-after
          [[:db/retract posting-eid :posting/amount 100M]]))
        "Silent retract of posted-at-marked entity must throw.")))

(deftest assert-throws-on-retract-entity-of-posted
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (sealing/assert-no-silent-retracts!
          db-after
          [[:db.fn/retractEntity posting-eid]])))))

(deftest assert-passes-on-no-retracts
  (let [conn (core/create-test-db)
        {:keys [db-after]} (seed-and-post! conn)]
    (is (nil? (sealing/assert-no-silent-retracts! db-after [])))
    (is (nil? (sealing/assert-no-silent-retracts!
               db-after
               [{:db/id -1 :kontor.commodity/symbol "USD"
                 :kontor.commodity/precision 2}])))))

;; ============================================================================
;; transact-with-validation integration
;; ============================================================================

(deftest validation-rejects-silent-retract-of-posted
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [posting-eid]} (seed-and-post! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (v/transact-with-validation
          conn
          [[:db/retract posting-eid :posting/amount 100M]]))
        "transact-with-validation surfaces the sealing violation.")))
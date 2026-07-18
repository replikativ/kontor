(ns kontor.compliance.sealing-test
  "Sealing semantics: posted entries cannot be silently retracted.
   Per ADR-007, explicit purges ARE permitted (and recorded as their
   own commits in datahike's commit DAG)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.posting :as posting]
            [kontor.compliance.sealing :as sealing]
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
    {:db/id -4 :kontor.journal/code "INV" :kontor.journal/name "J"
     :kontor.journal/type :sale :kontor.journal/active true}])
  (let [db (d/db conn)
        eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
        rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
        rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
        jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))
        ;; Build, mark posted *in the same tx*, transact.
        tx-data (-> (posting/build-transaction
                     {:transaction
                      {:kontor.transaction/external-id    "INV-2026-0001"
                       :kontor.transaction/journal        jnl
                       :kontor.transaction/effective-date some-date
                       :kontor.transaction/narration      "ACME"}
                      :postings
                      [{:kontor.posting/account rec :kontor.posting/amount  100M :kontor.posting/commodity eur}
                       {:kontor.posting/account rev :kontor.posting/amount -100M :kontor.posting/commodity eur}]})
                    (->> (mapv (fn [m]
                                 (cond-> m
                                   (some? (:kontor.posting/account m))
                                   (assoc :kontor.posting/posted-at some-date)
                                   (some? (:kontor.transaction/journal m))
                                   (assoc :kontor.transaction/posted-at some-date
                                          :kontor.transaction/state    :posted))))))
        report (d/transact conn tx-data)
        db-after (:db-after report)
        a-posting-eid (-> (d/q '[:find ?p .
                                 :in $ ?tx-eid
                                 :where [?p :kontor.posting/transaction ?tx-eid]]
                               db-after
                               (:db/id (d/entity db-after [:kontor.transaction/external-id "INV-2026-0001"]))))]
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
        retracts [[:db/retract posting-eid :kontor.posting/amount 100M]]
        violations (sealing/find-silent-retracts db-after retracts)]
    (is (= 1 (count violations)))
    (is (= posting-eid (-> violations first :eid)))))

(deftest find-silent-retracts-ignores-retract-of-unposted
  (testing "Retracting attrs on a draft entity (no :kontor.posting/posted-at)
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
          [[:db/retract posting-eid :kontor.posting/amount 100M]]))
        "Silent retract of posted-at-marked entity must throw.")))

(deftest assert-throws-on-retract-entity-of-posted
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (sealing/assert-no-silent-retracts!
          db-after
          [[:db.fn/retractEntity posting-eid]])))))

(deftest assert-throws-on-db-retract-entity-of-posted
  ;; ADR-118 / A2: the `:db/retractEntity` op spelling was unrecognised — only
  ;; `:db.fn/retractEntity` was — so a posted posting could be silently deleted.
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (sealing/assert-no-silent-retracts!
          db-after
          [[:db/retractEntity posting-eid]])))))

(deftest assert-throws-on-in-place-edit-of-posted
  ;; ADR-118 / A4: an entity-map upsert that CHANGES a posted value (datahike
  ;; upserts card-one attrs as retract+add) was previously uninspected.
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)
        edit [{:db/id posting-eid :kontor.posting/amount 9999M}]]
    (is (= 1 (count (sealing/find-silent-modifications db-after edit)))
        "changing a posted amount is a silent modification")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Sealing violation"
         (sealing/assert-no-silent-retracts! db-after edit)))))

(deftest assert-allows-noop-reassert-of-posted
  ;; ADR-118: re-asserting the SAME value on a posted row is a no-op, allowed
  ;; (no false positive) — only *changes* to existing values are sealed.
  (let [conn (core/create-test-db)
        {:keys [db-after posting-eid]} (seed-and-post! conn)
        cur (:kontor.posting/amount (d/pull db-after [:kontor.posting/amount] posting-eid))
        noop [{:db/id posting-eid :kontor.posting/amount cur}]]
    (is (= [] (sealing/find-silent-modifications db-after noop)))
    (is (nil? (sealing/assert-no-silent-retracts! db-after noop)))))

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
          [[:db/retract posting-eid :kontor.posting/amount 100M]]))
        "transact-with-validation surfaces the sealing violation.")))
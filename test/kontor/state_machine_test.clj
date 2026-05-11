(ns datahike-accounting.state-machine-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike-accounting.core :as core]
            [datahike-accounting.posting :as posting]
            [datahike-accounting.state-machine :as sm]
            [datahike-accounting.validation :as v]))

(def some-date #inst "2026-05-09T00:00:00Z")

(defn- catalog! [conn]
  (d/transact
   conn
   [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
     :commodity/precision 2 :commodity/iso-4217 "EUR"}
    {:db/id -2 :account/path "Assets:Receivable" :account/name "AR"
     :account/type :asset :account/active true}
    {:db/id -3 :account/path "Income:Sales" :account/name "Sales"
     :account/type :income :account/active true}
    {:db/id -4 :journal/code "INV" :journal/name "J"
     :journal/type :sale :journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :rec (:db/id (d/entity db [:account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:account/path "Income:Sales"]))
     :jnl (:db/id (d/entity db [:journal/code "INV"]))}))

(defn- mk-tx
  [{:keys [eur rec rev jnl]} state]
  (let [tx-data (posting/build-transaction
                 {:transaction
                  {:transaction/external-id    (str "TX-" (name state))
                   :transaction/journal        jnl
                   :transaction/effective-date some-date
                   :transaction/narration      "test"
                   :transaction/state          state}
                  :postings
                  [{:posting/account rec :posting/amount  100M :posting/commodity eur}
                   {:posting/account rev :posting/amount -100M :posting/commodity eur}]})]
    ;; If we want :posted, also stamp posted-at on the header and on
    ;; each balance-affecting posting (the latter for sealing).
    (if (= :posted state)
      (mapv (fn [m]
              (cond-> m
                (some? (:transaction/journal m))
                (assoc :transaction/posted-at some-date)
                (some? (:posting/account m))
                (assoc :posting/posted-at some-date)))
            tx-data)
      tx-data)))

;; ============================================================================
;; transition-allowed?
;; ============================================================================

(deftest transition-table
  (testing "Initial create paths"
    (is (sm/transition-allowed? nil :draft))
    (is (sm/transition-allowed? nil :posted))
    (is (not (sm/transition-allowed? nil :cancelled))))
  (testing "Forward transitions"
    (is (sm/transition-allowed? :draft :posted))
    (is (sm/transition-allowed? :draft :cancelled))
    (is (sm/transition-allowed? :posted :cancelled)))
  (testing "Regressions and skips"
    (is (not (sm/transition-allowed? :posted :draft)))
    (is (not (sm/transition-allowed? :cancelled :draft)))
    (is (not (sm/transition-allowed? :cancelled :posted)))))

;; ============================================================================
;; find-violations on a fresh DB
;; ============================================================================

(deftest create-as-draft-passes
  (let [conn (core/create-test-db)
        cat  (catalog! conn)]
    (is (= [] (sm/find-violations (d/db conn) (mk-tx cat :draft))))))

(deftest create-as-posted-with-posted-at-passes
  (let [conn (core/create-test-db)
        cat  (catalog! conn)]
    (is (= [] (sm/find-violations (d/db conn) (mk-tx cat :posted))))))

(deftest create-as-cancelled-rejected
  (testing "Skipping straight to :cancelled isn't a real-world flow."
    (let [conn (core/create-test-db)
          cat  (catalog! conn)
          tx-data (mk-tx cat :cancelled)
          violations (sm/find-violations (d/db conn) tx-data)]
      (is (= 1 (count violations)))
      (is (= :state-machine/illegal-transition (-> violations first :reason))))))

(deftest posting-without-posted-at-rejected
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        ;; Same shape as :posted but stripped of :transaction/posted-at
        tx-data (mapv (fn [m]
                        (cond-> m
                          (contains? m :transaction/posted-at)
                          (dissoc :transaction/posted-at)))
                      (mk-tx cat :posted))
        violations (sm/find-violations (d/db conn) tx-data)]
    (is (some #(= :state-machine/missing-posted-at (:reason %)) violations))))

;; ============================================================================
;; Transitions on existing entities
;; ============================================================================

(deftest draft-to-posted-passes
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        ;; First create as draft
        _ (v/transact-with-validation conn (mk-tx cat :draft))
        db (d/db conn)
        tx-eid (:db/id (d/entity db [:transaction/external-id "TX-draft"]))
        ;; Now flip to posted
        upgrade [{:db/id              tx-eid
                  :transaction/state  :posted
                  :transaction/posted-at some-date}]]
    (is (= [] (sm/find-violations db upgrade)))))

(deftest posted-to-draft-rejected
  (let [conn (core/create-test-db)
        cat  (catalog! conn)
        _ (v/transact-with-validation conn (mk-tx cat :posted))
        db (d/db conn)
        tx-eid (:db/id (d/entity db [:transaction/external-id "TX-posted"]))
        regress [{:db/id tx-eid :transaction/state :draft}]
        violations (sm/find-violations db regress)]
    (is (some #(= :state-machine/illegal-transition (:reason %)) violations))))

;; ============================================================================
;; Wired into transact-with-validation
;; ============================================================================

(deftest validation-rejects-illegal-transition
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)
        bad (mk-tx cat :cancelled)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"state-machine violation"
         (v/transact-with-validation conn bad)))))

(deftest validation-allows-create-as-draft
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)]
    (is (some? (v/transact-with-validation conn (mk-tx cat :draft))))))

(deftest validation-allows-create-as-posted
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)]
    (is (some? (v/transact-with-validation conn (mk-tx cat :posted))))))

(ns datahike-accounting.trial-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike-accounting.core :as core]
            [datahike-accounting.money :as money]
            [datahike-accounting.posting :as posting]
            [datahike-accounting.trial :as trial]
            [datahike-accounting.validation :as v]))

(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-15 #inst "2026-02-15T00:00:00Z")
(def feb-end #inst "2026-02-28T23:59:59Z")
(def jan-end #inst "2026-01-31T23:59:59Z")

(defn- catalog! [conn]
  (d/transact
   conn
   [{:db/id -1 :commodity/symbol "EUR" :commodity/name "Euro"
     :commodity/precision 2 :commodity/iso-4217 "EUR"}
    {:db/id -2 :commodity/symbol "USD" :commodity/name "US Dollar"
     :commodity/precision 2 :commodity/iso-4217 "USD"}
    {:db/id -3 :account/path "Assets:Receivable" :account/name "AR"
     :account/type :asset :account/active true}
    {:db/id -4 :account/path "Income:Sales" :account/name "Sales"
     :account/type :income :account/active true}
    {:db/id -5 :account/path "Assets:USD-Bank" :account/name "USD"
     :account/type :asset :account/active true}
    {:db/id -6 :account/path "Income:USD-Sales" :account/name "USD Sales"
     :account/type :income :account/active true}
    {:db/id -7 :journal/code "INV" :journal/name "J"
     :journal/type :sale :journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:commodity/symbol "EUR"]))
     :usd (:db/id (d/entity db [:commodity/symbol "USD"]))
     :rec (:db/id (d/entity db [:account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:account/path "Income:Sales"]))
     :usd-bank (:db/id (d/entity db [:account/path "Assets:USD-Bank"]))
     :usd-rev  (:db/id (d/entity db [:account/path "Income:USD-Sales"]))
     :jnl (:db/id (d/entity db [:journal/code "INV"]))}))

(defn- post-pair!
  [conn {:keys [eur rec rev jnl]} effective amount external-id]
  (let [tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id    external-id
                  :transaction/journal        jnl
                  :transaction/effective-date effective
                  :transaction/narration      external-id
                  :transaction/state          :posted
                  :transaction/posted-at      effective}
                 :postings
                 [{:posting/account rec :posting/amount    amount
                   :posting/commodity eur}
                  {:posting/account rev :posting/amount    (.negate ^java.math.BigDecimal amount)
                   :posting/commodity eur}]})
               (->> (mapv (fn [m]
                            (cond-> m
                              (some? (:posting/account m))
                              (assoc :posting/posted-at effective))))))]
    (v/transact-with-validation conn tx)))

(defn- post-pair-usd!
  [conn {:keys [usd usd-bank usd-rev jnl]} effective amount external-id]
  (let [tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id    external-id
                  :transaction/journal        jnl
                  :transaction/effective-date effective
                  :transaction/narration      external-id
                  :transaction/state          :posted
                  :transaction/posted-at      effective}
                 :postings
                 [{:posting/account usd-bank :posting/amount    amount
                   :posting/commodity usd}
                  {:posting/account usd-rev  :posting/amount    (.negate ^java.math.BigDecimal amount)
                   :posting/commodity usd}]})
               (->> (mapv (fn [m]
                            (cond-> m
                              (some? (:posting/account m))
                              (assoc :posting/posted-at effective))))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; trial-balance shape
;; ============================================================================

(deftest empty-when-no-postings
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        _ (catalog! conn)]
    (is (= {} (trial/trial-balance conn))
        "Empty book → empty trial balance.")))

(deftest two-accounts-net-to-zero
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec rev] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        tb (trial/trial-balance conn)]
    (is (= 2 (count tb)) "Both AR and Sales appear.")
    (is (money/equiv? (money/money "100"  eur) (get-in tb [rec eur])))
    (is (money/equiv? (money/money "-100" eur) (get-in tb [rev eur])))))

(deftest balanced-predicate-true-on-clean-books
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        cat (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 250M "INV-2")
        _ (post-pair-usd! conn cat jan-15 75M "USD-1")]
    (is (trial/balanced? (trial/trial-balance conn))
        "Trial balance per commodity sums to zero across all accounts.")))

(deftest multi-currency-trial-shows-per-commodity
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur usd rec usd-bank] :as cat} (catalog! conn)
        _ (post-pair!     conn cat jan-15 100M "INV-1")
        _ (post-pair-usd! conn cat jan-15  50M "USD-1")
        tb (trial/trial-balance conn)]
    (is (= 4 (count tb)) "All four accounts touched.")
    (is (money/equiv? (money/money "100" eur)  (get-in tb [rec eur])))
    (is (money/equiv? (money/money "50"  usd)  (get-in tb [usd-bank usd])))
    (is (every? (fn [[_a per-c]] (every? money/money? (vals per-c))) tb)
        "Every leaf is a Money record.")))

;; ============================================================================
;; Bitemporal: as-of-valid
;; ============================================================================

(deftest as-of-valid-prunes-future-postings
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        _ (post-pair! conn cat jan-15 100M "INV-1")
        _ (post-pair! conn cat feb-15 200M "INV-2")
        jan-tb (trial/trial-balance conn {:as-of-valid jan-end})
        feb-tb (trial/trial-balance conn {:as-of-valid feb-end})]
    (is (money/equiv? (money/money "100" eur) (get-in jan-tb [rec eur])))
    (is (money/equiv? (money/money "300" eur) (get-in feb-tb [rec eur])))))

;; ============================================================================
;; :include-zero? flag
;; ============================================================================

(deftest include-zero-keeps-empty-accounts
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [usd-bank] :as cat} (catalog! conn)
        ;; No USD postings — usd-bank account has zero balance
        _ (post-pair! conn cat jan-15 100M "INV-1")
        tb-default (trial/trial-balance conn)
        tb-zeros (trial/trial-balance conn {:include-zero? true})]
    (is (not (contains? tb-default usd-bank))
        "Zero-balance account excluded from default trial.")
    (is (contains? tb-zeros usd-bank)
        ":include-zero? true retains it.")))

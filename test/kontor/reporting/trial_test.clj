(ns kontor.reporting.trial-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.reporting.trial :as trial]
            [kontor.validation :as v]))

(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-15 #inst "2026-02-15T00:00:00Z")
(def feb-end #inst "2026-02-28T23:59:59Z")
(def jan-end #inst "2026-01-31T23:59:59Z")

(defn- catalog! [conn]
  (d/transact
   conn
   [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
    {:db/id -2 :kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
     :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"}
    {:db/id -3 :kontor.account/path "Assets:Receivable" :kontor.account/name "AR"
     :kontor.account/type :asset :kontor.account/active true}
    {:db/id -4 :kontor.account/path "Income:Sales" :kontor.account/name "Sales"
     :kontor.account/type :income :kontor.account/active true}
    {:db/id -5 :kontor.account/path "Assets:USD-Bank" :kontor.account/name "USD"
     :kontor.account/type :asset :kontor.account/active true}
    {:db/id -6 :kontor.account/path "Income:USD-Sales" :kontor.account/name "USD Sales"
     :kontor.account/type :income :kontor.account/active true}
    {:db/id -7 :kontor.journal/code "INV" :kontor.journal/name "J"
     :kontor.journal/type :sale :kontor.journal/active true}])
  (let [db (d/db conn)]
    {:eur (:db/id (d/entity db [:kontor.commodity/symbol "EUR"]))
     :usd (:db/id (d/entity db [:kontor.commodity/symbol "USD"]))
     :rec (:db/id (d/entity db [:kontor.account/path "Assets:Receivable"]))
     :rev (:db/id (d/entity db [:kontor.account/path "Income:Sales"]))
     :usd-bank (:db/id (d/entity db [:kontor.account/path "Assets:USD-Bank"]))
     :usd-rev  (:db/id (d/entity db [:kontor.account/path "Income:USD-Sales"]))
     :jnl (:db/id (d/entity db [:kontor.journal/code "INV"]))}))

(defn- post-pair!
  [conn {:keys [eur rec rev jnl]} effective amount external-id]
  (let [tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id    external-id
                  :kontor.transaction/journal        jnl
                  :kontor.transaction/effective-date effective
                  :kontor.transaction/narration      external-id
                  :kontor.transaction/state          :posted
                  :kontor.transaction/posted-at      effective}
                 :postings
                 [{:kontor.posting/account rec :kontor.posting/amount    amount
                   :kontor.posting/commodity eur}
                  {:kontor.posting/account rev :kontor.posting/amount    (.negate ^java.math.BigDecimal amount)
                   :kontor.posting/commodity eur}]})
               (->> (mapv (fn [m]
                            (cond-> m
                              (some? (:kontor.posting/account m))
                              (assoc :kontor.posting/posted-at effective))))))]
    (v/transact-with-validation conn tx)))

(defn- post-pair-usd!
  [conn {:keys [usd usd-bank usd-rev jnl]} effective amount external-id]
  (let [tx (-> (posting/build-transaction
                {:transaction
                 {:kontor.transaction/external-id    external-id
                  :kontor.transaction/journal        jnl
                  :kontor.transaction/effective-date effective
                  :kontor.transaction/narration      external-id
                  :kontor.transaction/state          :posted
                  :kontor.transaction/posted-at      effective}
                 :postings
                 [{:kontor.posting/account usd-bank :kontor.posting/amount    amount
                   :kontor.posting/commodity usd}
                  {:kontor.posting/account usd-rev  :kontor.posting/amount    (.negate ^java.math.BigDecimal amount)
                   :kontor.posting/commodity usd}]})
               (->> (mapv (fn [m]
                            (cond-> m
                              (some? (:kontor.posting/account m))
                              (assoc :kontor.posting/posted-at effective))))))]
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

;; ============================================================================
;; I-17 regression — default :as-of-valid is nil (= all valid time).
;;
;; Pre-fix, the implicit `(or as-of-valid (now))` silently filtered
;; future-dated postings out of the default trial balance. For a
;; substrate that aims to be the deterministic forward model (θ) for
;; simmis simulations (kontor-vision), that wall-clock-now default
;; broke the use case. New default: nil = no upper bound; pass an
;; explicit date for a real point-in-time view.
;; ============================================================================

(deftest future-dated-postings-show-in-default-trial-balance
  (let [conn (core/create-test-db)
        _ (v/install-invariants! conn)
        {:keys [eur rec] :as cat} (catalog! conn)
        ;; Post 5 years in the future — well past wall-clock now.
        future-date (java.util.Date.
                     (long (+ (.getTime (java.util.Date.))
                              (* 1000 60 60 24 365 5))))
        _ (post-pair! conn cat future-date 500M "FUTURE-1")
        tb-default (trial/trial-balance conn)]
    (is (contains? tb-default rec)
        "I-17 regression: future-dated posting must appear in default trial balance.")
    (is (money/equiv? (money/money "500" eur) (get-in tb-default [rec eur])))))

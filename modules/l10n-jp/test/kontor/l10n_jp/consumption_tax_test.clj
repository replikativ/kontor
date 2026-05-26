(ns kontor.l10n-jp.consumption-tax-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.chart :as chart]
            [kontor.l10n-jp.consumption-tax :as jct]
            [kontor.money :as money]
            [kontor.posting :as posting]
            [kontor.validation :as v]))

(defn- jpy [s] (money/money (bigdec s) :JPY))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- post-standard-sale!
  "10% standard-rate sale, net=`net` JPY."
  [conn external-id date net]
  (let [db (d/db conn)
        jpy-eid (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
        rec (ace db "121000")             ; AR
        rev (ace db "411000")             ; Sales 10%
        out-tax (ace db "215100")         ; Output JCT 10%
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        tax-bd (.setScale (.multiply net-bd 0.10M) 0 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd tax-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity jpy-eid}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity jpy-eid}
                  {:posting/account out-tax :posting/amount (.negate tax-bd) :posting/commodity jpy-eid}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

(defn- post-reduced-sale!
  "8% reduced-rate sale (e.g. food/newspaper)."
  [conn external-id date net]
  (let [db (d/db conn)
        jpy-eid (:db/id (d/entity db [:kontor.commodity/symbol "JPY"]))
        rec (ace db "121000")
        rev (ace db "412000")
        out-tax (ace db "215200")
        jnl (:db/id (d/entity db [:journal/code "INV"]))
        net-bd (bigdec net)
        tax-bd (.setScale (.multiply net-bd 0.08M) 0 java.math.RoundingMode/HALF_EVEN)
        gross  (.add net-bd tax-bd)
        tx (-> (posting/build-transaction
                {:transaction
                 {:transaction/external-id external-id
                  :transaction/journal jnl
                  :transaction/effective-date date
                  :transaction/narration external-id
                  :transaction/state :posted
                  :transaction/posted-at date}
                 :postings
                 [{:posting/account rec :posting/amount gross :posting/commodity jpy-eid}
                  {:posting/account rev :posting/amount (.negate net-bd) :posting/commodity jpy-eid}
                  {:posting/account out-tax :posting/amount (.negate tax-bd) :posting/commodity jpy-eid}]})
               (->> (mapv #(if (some? (:posting/account %))
                             (assoc % :posting/posted-at date) %))))]
    (v/transact-with-validation conn tx)))

;; ============================================================================
;; Single-rate scenarios
;; ============================================================================

(deftest single-10pct-sale
  (testing "JPY 100,000 sale at 10% → sales-10=100,000, jct-out-10=10,000"
    (let [conn (bootstrap)
          _ (post-standard-sale! conn "INV-1" jan-15 100000)
          r (jct/compute-return conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (jpy "100000") (:sales-10 (:return/lines r))))
      (is (money/equiv? (jpy "10000")  (:jct-out-10 (:return/lines r))))
      (is (money/equiv? (jpy "10000")  (:return/jct-collected r)))
      (is (= :payment (:return/outcome r))))))

(deftest single-8pct-sale
  (testing "JPY 1,000 food sale at 8% → sales-8=1,000, jct-out-8=80"
    (let [conn (bootstrap)
          _ (post-reduced-sale! conn "INV-2" jan-15 1000)
          r (jct/compute-return conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (jpy "1000") (:sales-8 (:return/lines r))))
      (is (money/equiv? (jpy "80")   (:jct-out-8 (:return/lines r))))
      (is (money/equiv? (jpy "80")   (:return/jct-collected r))))))

(deftest mixed-rates
  (testing "Mixed-rate quarter: 100k at 10% + 50k at 8%
            → collected = 10,000 + 4,000 = 14,000"
    (let [conn (bootstrap)
          _ (post-standard-sale! conn "INV-1" jan-15 100000)
          _ (post-reduced-sale!  conn "INV-2" jan-15 50000)
          r (jct/compute-return conn {:from jan-1 :to feb-1})]
      (is (money/equiv? (jpy "100000") (:sales-10 (:return/lines r))))
      (is (money/equiv? (jpy "50000")  (:sales-8 (:return/lines r))))
      (is (money/equiv? (jpy "14000")  (:return/jct-collected r))))))

(deftest nil-return
  (testing "No postings → nil return"
    (let [conn (bootstrap)
          r (jct/compute-return conn {:from jan-1 :to feb-1})]
      (is (= :nil-return (:return/outcome r)))
      (is (money/equiv? (jpy "0") (:return/jct-net r))))))

(deftest annual-period-bounds
  (testing ":year 2026 picks Jan 1 2026 → Jan 1 2027"
    (let [conn (bootstrap)
          _ (post-standard-sale! conn "INV-1" jan-15 100000)
          ;; Out-of-range sale (Feb 2027) should NOT count
          _ (post-standard-sale! conn "INV-2" #inst "2027-02-01" 999999)
          r (jct/compute-return conn {:year 2026})]
      (is (money/equiv? (jpy "100000") (:sales-10 (:return/lines r)))))))

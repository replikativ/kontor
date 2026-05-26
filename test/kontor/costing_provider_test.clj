(ns kontor.costing-provider-test
  "Tests for ADR-029 — CostingProvider protocol + the four kernel-
   shipped implementations: FIFO, LIFO, Weighted Average, Standard."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.costing-provider :as costing]
            [kontor.core :as core]
            [kontor.valuation :as valuation]))

;; ============================================================================
;; Fixture: three sequential receipts of the same item
;;
;;   Layer A: 100 units @ 5.00  (received 2026-05-01)
;;   Layer B:  50 units @ 6.00  (received 2026-05-10)
;;   Layer C:  75 units @ 8.00  (received 2026-05-20)
;;
;; Total on-hand: 225 units, total value: 100*5 + 50*6 + 75*8 = 1400
;; ============================================================================

(defn- setup!
  []
  (let [conn (core/create-test-db)
        _ (d/transact conn
                      [{:db/id -1 :kontor.commodity/symbol "EUR"
                        :kontor.commodity/name "Euro" :kontor.commodity/precision 2
                        :kontor.commodity/iso-4217 "EUR"}
                       {:db/id -2 :kontor.account/path "Item:Widget"
                        :kontor.account/name "Widget" :kontor.account/type :asset
                        :kontor.account/active true}
                       {:db/id -3 :kontor.journal/code "STOCK"
                        :kontor.journal/name "Stock" :kontor.journal/type :general
                        :kontor.journal/active true}])
        db0 (d/db conn)
        eur (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
        itm (:db/id (d/entity db0 [:kontor.account/path "Item:Widget"]))
        jnl (:db/id (d/entity db0 [:kontor.journal/code "STOCK"]))
        book (valuation/primary db0)
        receipt! (fn [qty unit-cost date]
                   (d/transact conn
                               [{:db/id -1
                                 :kontor.transaction/journal jnl
                                 :kontor.transaction/effective-date date
                                 :kontor.transaction/narration "Receipt"}
                                {:db/id -2
                                 :valuation-layer/book book
                                 :valuation-layer/item itm
                                 :valuation-layer/origin-transaction -1
                                 :valuation-layer/qty-original qty
                                 :valuation-layer/unit-cost-original unit-cost
                                 :valuation-layer/commodity eur
                                 :valuation-layer/received-at date}])
                   (d/q '[:find ?l . :in $ ?d :where
                          [?l :valuation-layer/received-at ?d]]
                        (d/db conn) date))
        l-a (receipt! 100M 5.00M #inst "2026-05-01")
        l-b (receipt!  50M 6.00M #inst "2026-05-10")
        l-c (receipt!  75M 8.00M #inst "2026-05-20")]
    {:conn conn :book book :item itm :commodity eur
     :journal jnl :l-a l-a :l-b l-b :l-c l-c}))

;; ============================================================================
;; FIFO
;; ============================================================================

(deftest fifo-single-layer-consumption
  (testing "Issue 30 units → all from Layer A @ 5.00"
    (let [{:keys [conn book item l-a]} (setup!)
          provider (costing/make-fifo-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 30M})]
      (is (= 1 (count (:consumptions plan))))
      (let [{:keys [layer qty unit-cost]} (first (:consumptions plan))]
        (is (= l-a layer))
        (is (= 30M qty))
        (is (= 0 (.compareTo (bigdec "5.0000") unit-cost)))))))

(deftest fifo-spans-two-layers
  (testing "Issue 120 units → 100 from Layer A @ 5.00, then 20 from B @ 6.00"
    (let [{:keys [conn book item l-a l-b]} (setup!)
          provider (costing/make-fifo-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 120M})]
      (is (= 2 (count (:consumptions plan))))
      (is (= [l-a l-b] (mapv :layer (:consumptions plan))))
      (is (= [100M 20M] (mapv :qty (:consumptions plan))))
      (is (= 0 (.compareTo (bigdec "5.0000")
                           (-> plan :consumptions (nth 0) :unit-cost))))
      (is (= 0 (.compareTo (bigdec "6.0000")
                           (-> plan :consumptions (nth 1) :unit-cost)))))))

(deftest fifo-underflow
  (testing "Issue 1000 units against on-hand 225 → :underflow 775"
    (let [{:keys [conn book item]} (setup!)
          provider (costing/make-fifo-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 1000M})]
      (is (some? (:underflow plan)))
      (is (= 0 (.compareTo (bigdec "775") (:underflow plan)))))))

(deftest fifo-receipt-echoes-input
  (let [{:keys [conn book item commodity]} (setup!)
        provider (costing/make-fifo-provider)
        {:keys [layer-data]}
        (costing/plan-receipt provider (d/db conn)
                              {:book book :item item :qty 40M
                               :unit-cost 7.50M :commodity commodity})]
    (is (= 40M (:qty layer-data)))
    (is (= 0 (.compareTo (bigdec "7.50") (:unit-cost layer-data))))))

;; ============================================================================
;; LIFO
;; ============================================================================

(deftest lifo-single-layer-consumption
  (testing "Issue 30 units → all from Layer C @ 8.00 (newest)"
    (let [{:keys [conn book item l-c]} (setup!)
          provider (costing/make-lifo-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 30M})]
      (is (= [l-c] (mapv :layer (:consumptions plan))))
      (is (= 0 (.compareTo (bigdec "8.0000")
                           (-> plan :consumptions first :unit-cost)))))))

(deftest lifo-spans-two-layers
  (testing "Issue 100 units LIFO → 75 from C @ 8.00, 25 from B @ 6.00"
    (let [{:keys [conn book item l-b l-c]} (setup!)
          provider (costing/make-lifo-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 100M})]
      (is (= [l-c l-b] (mapv :layer (:consumptions plan))))
      (is (= [75M 25M] (mapv :qty (:consumptions plan)))))))

;; ============================================================================
;; Weighted Average
;; ============================================================================

(deftest weighted-average-unit-cost
  (testing "Weighted average across A/B/C = (500 + 300 + 600) / 225
            = 1400 / 225 = 6.2222 (rounded HALF_EVEN at 4dp)"
    (let [{:keys [conn book item]} (setup!)
          provider (costing/make-weighted-average-provider)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 50M})]
      (is (seq (:consumptions plan)))
      ;; Every consumption in this plan carries the SAME unit cost
      ;; (the weighted average).
      (let [costs (map :unit-cost (:consumptions plan))]
        (is (apply = costs)
            "All consumption rows under weighted-average share one unit cost")
        (is (= 0 (.compareTo (bigdec "6.2222") (first costs))))))))

;; ============================================================================
;; Standard Cost
;; ============================================================================

(deftest standard-cost-issue-uses-standard
  (testing "Issue at standard cost 7.00 — every consumption row stamped 7.00"
    (let [{:keys [conn book item]} (setup!)
          ;; Standard cost fixed at 7.00 regardless of actuals
          std-fn (fn [_db _book _item] 7.00M)
          provider (costing/make-standard-cost-provider std-fn)
          plan (costing/plan-consumption provider (d/db conn)
                                         {:book book :item item :qty 30M})]
      (is (= [7.00M] (distinct (map :unit-cost (:consumptions plan))))
          "Standard-cost issues stamp every drawn layer at the standard"))))

(deftest standard-cost-receipt-emits-variance
  (testing "Receipt of 40 units actual 7.50 vs standard 7.00 → variance
            of (7.50 − 7.00) × 40 = 20.00 (positive: over-cost)"
    (let [{:keys [conn book item commodity]} (setup!)
          std-fn (fn [_db _book _item] 7.00M)
          provider (costing/make-standard-cost-provider std-fn)
          {:keys [layer-data variance]}
          (costing/plan-receipt provider (d/db conn)
                                {:book book :item item :qty 40M
                                 :unit-cost 7.50M :commodity commodity})]
      (is (= 0 (.compareTo (bigdec "7.00") (:unit-cost layer-data)))
          "Layer is created at the standard, not the actual")
      (is (= 0 (.compareTo (bigdec "20.00") variance))
          "Variance = (actual − standard) × qty"))))

(deftest standard-cost-receipt-no-variance-at-standard
  (testing "Actual = standard → variance is zero"
    (let [{:keys [conn book item commodity]} (setup!)
          std-fn (fn [_db _book _item] 7.00M)
          provider (costing/make-standard-cost-provider std-fn)
          {:keys [variance]}
          (costing/plan-receipt provider (d/db conn)
                                {:book book :item item :qty 40M
                                 :unit-cost 7.00M :commodity commodity})]
      (is (= 0 (.compareTo 0M variance))))))

;; ============================================================================
;; provider-for resolver
;; ============================================================================

(deftest provider-for-builds-known-impls
  (is (instance? kontor.costing_provider.FIFOCostingProvider
                 (costing/provider-for :fifo)))
  (is (instance? kontor.costing_provider.LIFOCostingProvider
                 (costing/provider-for :lifo)))
  (is (instance? kontor.costing_provider.WeightedAverageProvider
                 (costing/provider-for :avg)))
  (is (instance? kontor.costing_provider.StandardCostProvider
                 (costing/provider-for :standard (fn [& _] 7.00M)))))

(deftest provider-for-rejects-unknown
  (is (thrown? clojure.lang.ExceptionInfo
               (costing/provider-for :wibble))))

(deftest provider-for-standard-requires-fn
  (is (thrown? clojure.lang.ExceptionInfo
               (costing/provider-for :standard))))

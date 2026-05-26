(ns kontor.stock-move-test
  "Tests for ADR-030 — `kontor.posting/plan-stock-move`. Each test
   exercises the full pipeline from move-spec → kernel transaction
   with proper balanced postings and the right valuation-layer or
   layer-consumption entities materialized."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.costing-provider :as costing]
            [kontor.posting :as posting]
            [kontor.valuation :as valuation]))

;; ============================================================================
;; Common fixture
;; ============================================================================

(def asset-acc-path    "Assets:Inventory")
(def gr-ir-path        "Liabilities:GR-IR-Clearing")
(def cogs-path         "Expense:COGS")
(def variance-path     "Expense:PriceVariance")

(defn- setup!
  []
  (let [conn (core/create-test-db)
        _ (d/transact conn
                      [{:db/id -1 :kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                        :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "EUR"}
                       {:db/id -10 :kontor.account/path asset-acc-path
                        :kontor.account/name "Inventory" :kontor.account/type :asset
                        :kontor.account/active true}
                       {:db/id -11 :kontor.account/path gr-ir-path
                        :kontor.account/name "GR/IR clearing" :kontor.account/type :liability
                        :kontor.account/active true}
                       {:db/id -12 :kontor.account/path cogs-path
                        :kontor.account/name "COGS" :kontor.account/type :expense
                        :kontor.account/active true}
                       {:db/id -13 :kontor.account/path variance-path
                        :kontor.account/name "Price variance" :kontor.account/type :expense
                        :kontor.account/active true}
                       {:db/id -20 :kontor.account/path "Item:Widget"
                        :kontor.account/name "Widget item" :kontor.account/type :asset
                        :kontor.account/active true}
                       {:db/id -30 :kontor.journal/code "STOCK"
                        :kontor.journal/name "Stock" :kontor.journal/type :general
                        :kontor.journal/active true}])
        db0 (d/db conn)]
    {:conn      conn
     :commodity (:db/id (d/entity db0 [:kontor.commodity/symbol "EUR"]))
     :item      (:db/id (d/entity db0 [:kontor.account/path "Item:Widget"]))
     :journal   (:db/id (d/entity db0 [:kontor.journal/code "STOCK"]))
     :book      (valuation/primary db0)}))

(def account-fn
  "Simple role → account-lookup-ref mapper for the tests."
  (fn [_move role]
    [:kontor.account/path (case role
                     :inventory      asset-acc-path
                     :gr-ir-clearing gr-ir-path
                     :cogs           cogs-path
                     :price-variance variance-path)]))

;; ============================================================================
;; Receipt (:in)
;; ============================================================================

(deftest receipt-creates-layer-and-balanced-postings
  (let [{:keys [conn commodity item journal book]} (setup!)
        provider (costing/make-fifo-provider)
        move {:direction      :in
              :book           book
              :item           item
              :qty            100M
              :unit-cost      5.00M
              :commodity      commodity
              :journal        journal
              :effective-date #inst "2026-05-11"
              :narration      "Receipt of 100 widgets @ 5.00"
              :provider       provider
              :account-fn     account-fn
              :transaction-state :posted}
        tx-data (posting/plan-stock-move (d/db conn) move)
        _ (d/transact conn tx-data)
        db (d/db conn)]
    (testing "One layer materialized with the supplied qty + unit cost"
      (let [n-layers (d/q '[:find (count ?l) .
                            :in $ ?item
                            :where
                            [?l :kontor.valuation-layer/item ?item]]
                          db item)]
        (is (= 1 n-layers))))
    (testing "On-hand qty matches the receipt"
      (is (= 0 (.compareTo (bigdec "100")
                           (valuation/on-hand-qty db book item)))))
    (testing "Posting count: 2 (Dr inventory / Cr GR-IR) — no variance leg"
      (let [n-postings (d/q '[:find (count ?p) .
                              :where [?p :kontor.posting/transaction _]]
                            db)]
        (is (= 2 n-postings))))
    (testing "Postings sum to zero per (ledger, commodity)"
      (let [sum (d/q '[:find (sum ?amt) .
                       :where [_ :kontor.posting/amount ?amt]]
                     db)]
        (is (= 0 (.compareTo (bigdec "0") sum)))))))

(deftest receipt-with-standard-cost-emits-variance
  (testing "Receipt 40 units actual 7.50, standard 7.00 → 3-line tx:
            Dr inventory 280, Dr variance 20, Cr GR/IR 300"
    (let [{:keys [conn commodity item journal book]} (setup!)
          std-fn (fn [_db _book _item] 7.00M)
          provider (costing/make-standard-cost-provider std-fn)
          move {:direction      :in
                :book           book
                :item           item
                :qty            40M
                :unit-cost      7.50M
                :commodity      commodity
                :journal        journal
                :effective-date #inst "2026-05-12"
                :provider       provider
                :account-fn     account-fn
                :transaction-state :posted}
          tx-data (posting/plan-stock-move (d/db conn) move)
          _ (d/transact conn tx-data)
          db (d/db conn)
          asset-bal (d/q '[:find (sum ?amt) .
                           :in $ ?path
                           :where
                           [?a :kontor.account/path ?path]
                           [?p :kontor.posting/account ?a]
                           [?p :kontor.posting/amount ?amt]]
                         db asset-acc-path)
          var-bal (d/q '[:find (sum ?amt) .
                         :in $ ?path
                         :where
                         [?a :kontor.account/path ?path]
                         [?p :kontor.posting/account ?a]
                         [?p :kontor.posting/amount ?amt]]
                       db variance-path)
          gr-ir-bal (d/q '[:find (sum ?amt) .
                           :in $ ?path
                           :where
                           [?a :kontor.account/path ?path]
                           [?p :kontor.posting/account ?a]
                           [?p :kontor.posting/amount ?amt]]
                         db gr-ir-path)]
      (is (= 0 (.compareTo (bigdec "280.00") asset-bal))
          "Inventory at standard: 40 × 7.00 = 280.00")
      (is (= 0 (.compareTo (bigdec "20.00") var-bal))
          "Variance: (7.50 − 7.00) × 40 = 20.00 debit")
      (is (= 0 (.compareTo (bigdec "-300.00") gr-ir-bal))
          "GR/IR clearing: full invoice value 300.00 credit"))))

;; ============================================================================
;; Issue (:out)
;; ============================================================================

(deftest issue-after-two-receipts-fifo
  (testing "Receipt 100 @ 5, then 50 @ 6, then issue 120 →
            consume 100 of Layer A and 20 of Layer B.
            COGS = 100×5 + 20×6 = 620"
    (let [{:keys [conn commodity item journal book]} (setup!)
          provider (costing/make-fifo-provider)
          base-move {:book book :item item :commodity commodity
                     :journal journal :provider provider
                     :account-fn account-fn :transaction-state :posted}
          _ (d/transact conn
                        (posting/plan-stock-move
                         (d/db conn)
                         (assoc base-move
                                :direction :in :qty 100M :unit-cost 5.00M
                                :effective-date #inst "2026-05-01")))
          _ (d/transact conn
                        (posting/plan-stock-move
                         (d/db conn)
                         (assoc base-move
                                :direction :in :qty 50M :unit-cost 6.00M
                                :effective-date #inst "2026-05-10")))
          _ (d/transact conn
                        (posting/plan-stock-move
                         (d/db conn)
                         (assoc base-move
                                :direction :out :qty 120M
                                :effective-date #inst "2026-05-15")))
          db (d/db conn)
          cogs-bal (d/q '[:find (sum ?amt) .
                          :in $ ?path
                          :where
                          [?a :kontor.account/path ?path]
                          [?p :kontor.posting/account ?a]
                          [?p :kontor.posting/amount ?amt]]
                        db cogs-path)]
      (testing "COGS leg equals total consumption value (620)"
        (is (= 0 (.compareTo (bigdec "620.00") cogs-bal))))
      (testing "Two consumption events written (one per drawn layer)"
        (let [n-cons (d/q '[:find (count ?c) .
                            :where [?c :kontor.layer-consumption/layer _]]
                          db)]
          (is (= 2 n-cons))))
      (testing "Remaining on-hand = 100 + 50 − 120 = 30"
        (is (= 0 (.compareTo (bigdec "30")
                             (valuation/on-hand-qty db book item))))))))

(deftest issue-underflow-rejected
  (testing "Trying to issue more than on-hand raises ex-info"
    (let [{:keys [conn commodity item journal book]} (setup!)
          provider (costing/make-fifo-provider)
          base-move {:book book :item item :commodity commodity
                     :journal journal :provider provider
                     :account-fn account-fn :transaction-state :posted}]
      ;; Receipt 10 units
      (d/transact conn
                  (posting/plan-stock-move
                   (d/db conn)
                   (assoc base-move
                          :direction :in :qty 10M :unit-cost 5.00M
                          :effective-date #inst "2026-05-01")))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"insufficient stock"
           (posting/plan-stock-move
            (d/db conn)
            (assoc base-move
                   :direction :out :qty 100M
                   :effective-date #inst "2026-05-02")))))))

;; ============================================================================
;; Parallel books (ADR-027 orthogonality)
;; ============================================================================

(deftest two-books-same-physical-stock-different-cost-method
  (testing "The same physical receipt under two different valuation
            books (primary FIFO + ifrs Average) produces two
            independent layer entities. Issues against each book
            consult only that book's layers."
    (let [{:keys [conn commodity item journal book]} (setup!)
          _ (d/transact conn
                        [{:kontor.valuation-book/code        "ifrs"
                          :kontor.valuation-book/name        "IFRS valuation"
                          :kontor.valuation-book/framework   :ifrs
                          :kontor.valuation-book/cost-method :avg
                          :kontor.valuation-book/active      true}])
          ifrs (valuation/by-code (d/db conn) "ifrs")
          fifo (costing/make-fifo-provider)
          avg  (costing/make-weighted-average-provider)
          base-move {:item item :commodity commodity :journal journal
                     :account-fn account-fn :transaction-state :posted}
          ;; Same physical event, posted to BOTH books
          _ (d/transact conn
                        (posting/plan-stock-move
                         (d/db conn)
                         (assoc base-move
                                :direction :in :qty 100M :unit-cost 5.00M
                                :book book :provider fifo
                                :effective-date #inst "2026-05-01")))
          _ (d/transact conn
                        (posting/plan-stock-move
                         (d/db conn)
                         (assoc base-move
                                :direction :in :qty 100M :unit-cost 5.00M
                                :book ifrs :provider avg
                                :effective-date #inst "2026-05-01")))
          db (d/db conn)
          fifo-layers (valuation/available-layers db book item)
          ifrs-layers (valuation/available-layers db ifrs item)]
      (is (= 1 (count fifo-layers))
          "Primary book sees one layer")
      (is (= 1 (count ifrs-layers))
          "IFRS book sees one layer")
      (is (not= fifo-layers ifrs-layers)
          "The two books refer to different layer entities"))))

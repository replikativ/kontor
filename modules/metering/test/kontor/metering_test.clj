(ns kontor.metering-test
  "Piece C (research note 190): meter a window of kontor-conformant usage
   subledger rows → balanced, sealed, idempotent double-entry accruals,
   with reconciliation + settlement. Provider-agnostic — synthesizes rows
   directly (the exact shape dvergr's `usage->subledger-rows` emits), so
   this runs inside kontor's suite without a dvergr dependency."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.money :as m]
            [kontor.metering :as meter]
            [kontor.reporting.trial :as trial]))

(defn- μ$ [micros commodity]
  (m/money (.movePointLeft (bigdec micros) 6) commodity))

(defn- row [micros commodity settlement provider resource project]
  {:money      (μ$ micros commodity)
   :settlement settlement
   :dimensions {:project project :provider provider :resource resource}})

(defn- fresh-book []
  (let [conn (core/create-test-db)]
    (d/transact conn [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                       :kontor.commodity/precision 2}
                      {:kontor.journal/code "GEN" :kontor.journal/type :general}])
    conn))

(def config
  {:period-key     "2026-07"
   :effective-date #inst "2026-07-31"
   :journal        [:kontor.journal/code "GEN"]
   ;; dev rooms are R&D (OpEx); everything else is customer-serving COGS.
   :classify       (fn [dims] (if (= :room-dev (:project dims)) :r&d :cogs))})

(def rows
  ;; room-prod / anthropic / prepaid: two resources → ONE cogs group ($0.0105)
  [(row 3000 :USD :prepaid  :anthropic      :token-input  :room-prod)
   (row 7500 :USD :prepaid  :anthropic      :token-output :room-prod)
   ;; room-prod / postpaid vendor: one cogs group ($0.005)
   (row 5000 :USD :postpaid :acme-inference :token-input  :room-prod)
   ;; room-dev / anthropic / prepaid: classified R&D ($0.002)
   (row 2000 :USD :prepaid  :anthropic      :token-input  :room-dev)])

(deftest summarize-produces-balanced-idempotent-accruals
  (let [conn (fresh-book)]
    (meter/ensure-accounts! conn rows config)

    (testing "first run posts one balanced entry per (project,class,settlement,provider,commodity) group"
      (let [{:keys [posted skipped]} (meter/summarize! conn rows config)]
        (is (= 3 (count posted)) "3 groups: prod/anthropic/cogs, prod/acme/cogs, dev/anthropic/r&d")
        (is (empty? skipped))))

    (testing "the whole ledger balances (Ker σ)"
      (is (trial/balanced? (trial/trial-balance conn))))

    (testing "postings carry the project + provider dimensions (ADR-097)"
      (let [dim-vals (d/q '[:find [?v ...]
                            :where
                            [?p :kontor.posting/dimensions ?d]
                            [?d :kontor.posting-dimension/value ?v]]
                          @conn)]
        (is (contains? (set dim-vals) "room-prod"))
        (is (contains? (set dim-vals) "anthropic"))))

    (testing "postpaid accrual reconciles to the real invoice ($0.005)"
      (let [{:keys [expected ok?]} (meter/reconcile conn config :acme-inference (μ$ 5000 :USD))]
        (is ok? "accrued postpaid liability reconciles to the $0.005 invoice")
        (is (= 0 (.compareTo (bigdec "0.005") (:amount expected))))))

    (testing "idempotency: re-running the same window posts nothing"
      (let [{:keys [posted skipped]} (meter/summarize! conn rows config)]
        (is (empty? posted))
        (is (= 3 (count skipped)))
        (is (trial/balanced? (trial/trial-balance conn)))))

    (testing "reconcile flags a mismatched invoice"
      (let [{:keys [delta ok?]} (meter/reconcile conn config :acme-inference (μ$ 6000 :USD))]
        (is (not ok?))
        (is (= 0 (.compareTo (bigdec "0.001") (:amount delta))))))

    (testing "settle! clears the postpaid accrual against cash and stays balanced"
      (meter/ensure-accounts! conn rows config)
      (d/transact conn [{:kontor.account/path "Assets:Cash" :kontor.account/type :asset}])
      (meter/settle! conn config :acme-inference :postpaid (μ$ 5000 :USD)
                     {:journal [:kontor.journal/code "GEN"]
                      :effective-date #inst "2026-08-05" :ref "inv-001"})
      (let [{:keys [ok?]} (meter/reconcile conn config :acme-inference (m/money "0" :USD))]
        (is ok? "after settlement the accrued liability is back to zero"))
      (is (trial/balanced? (trial/trial-balance conn))))))

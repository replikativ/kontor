(ns kontor.l10n-us.investment-income-provider-test
  "Tests for the US investment-income provider (research note 148)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.cgt-statute :as cgt-statute]
            [kontor.l10n-us.investment-income-provider :as inv]
            [kontor.l10n-us.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with US CGT statute + US investment-income statute
   installed (the statute file references CGT bracket parameters
   so CGT must be installed first)."
  []
  (let [conn (core/create-test-db)]
    (cgt-statute/install! conn)
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                       :kontor.commodity/precision 2}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- run-provider
  "Skip the GL marginalize by supplying pre-computed `:inputs
   :investment-income-bases` (the consumer-supplied path for
   1099-DIV uploads)."
  [conn bases & [extra-ctx]]
  (let [provider (inv/us-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity nil
             :period p2026
             :inputs {:investment-income-bases bases}}
            extra-ctx))))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Qualified dividends — reuse CGT LT bracket
;; ============================================================================

(deftest qualified-dividends-use-§1h-brackets
  (testing "small QD (single filer) falls in the 0% bracket"
    (let [conn (fresh)
          facts (run-provider conn {:qualified-dividends 20000M
                                    :ordinary-dividends 0M :reit-dividends 0M
                                    :bank-interest 0M :corp-bond-interest 0M
                                    :treasury-interest 0M :oid-interest 0M
                                    :market-discount 0M :muni-interest 0M
                                    :investment-interest-paid 0M}
                              {:tax-unit {:filing-status :single}})
          qd    (component-by-lane facts :qualified-dividend)]
      (is (some? qd))
      (is (== 20000M (-> qd :base :amount)))
      (is (== 0M     (-> qd :liability :amount))
          "$20k QD under single 0% ceiling ($49,450 in 2026) → $0")))

  (testing "mid QD (single filer) at 15%"
    (let [conn (fresh)
          facts (run-provider conn {:qualified-dividends 100000M
                                    :ordinary-dividends 0M :reit-dividends 0M
                                    :bank-interest 0M :corp-bond-interest 0M
                                    :treasury-interest 0M :oid-interest 0M
                                    :market-discount 0M :muni-interest 0M
                                    :investment-interest-paid 0M}
                              {:tax-unit {:filing-status :single}})
          qd    (component-by-lane facts :qualified-dividend)]
      ;; First 49,450 @ 0%; next 50,550 @ 15% = $7,582.50
      (is (== 7582.5M (-> qd :liability :amount))))))

;; ============================================================================
;; §2. Ordinary dividends + taxable interest → PIT base
;; ============================================================================

(deftest ordinary-investment-folds-to-pit-base
  (testing "non-QD + REIT + taxable interest all fold via :pit-base-additions"
    (let [conn (fresh)
          facts (run-provider conn {:qualified-dividends 0M
                                    :ordinary-dividends 3000M
                                    :reit-dividends 2000M
                                    :bank-interest 5000M
                                    :corp-bond-interest 4000M
                                    :treasury-interest 1000M
                                    :oid-interest 500M
                                    :market-discount 0M
                                    :muni-interest 8000M       ; SKIPPED
                                    :investment-interest-paid 0M}
                              {:tax-unit {:filing-status :single}
                               :inputs {:investment-income-bases
                                        {:qualified-dividends 0M
                                         :ordinary-dividends 3000M
                                         :reit-dividends 2000M
                                         :bank-interest 5000M
                                         :corp-bond-interest 4000M
                                         :treasury-interest 1000M
                                         :oid-interest 500M
                                         :market-discount 0M
                                         :muni-interest 8000M
                                         :investment-interest-paid 0M}}})
          ord   (component-by-lane facts :ordinary-investment)]
      (is (some? ord))
      ;; 3000 + 2000 + 5000 + 4000 + 1000 + 500 = 15500
      ;; muni $8000 is NOT included
      (is (== 15500M (-> ord :base :amount)))
      (is (== 0M     (-> ord :liability :amount)) "no own schedule")
      (is (= [15500M] (get-in ord [:jurisdiction-specific-codes :pit-base-additions]))))))

;; ============================================================================
;; §3. §163(d) investment-interest deduction cap
;; ============================================================================

(deftest §163d-deduction-capped-at-nii
  (testing "investment-interest deduction capped at net investment income"
    (let [conn (fresh)
          ;; $10k QD + $5k bank-interest = $15k NII. $20k paid → only
          ;; $15k allowed; $5k carryforward.
          facts (run-provider conn {:qualified-dividends 10000M
                                    :ordinary-dividends 0M :reit-dividends 0M
                                    :bank-interest 5000M
                                    :corp-bond-interest 0M
                                    :treasury-interest 0M :oid-interest 0M
                                    :market-discount 0M :muni-interest 0M
                                    :investment-interest-paid 20000M}
                              {:tax-unit {:filing-status :single}})
          §163d (component-by-lane facts :§163d-deduction)]
      (is (some? §163d))
      ;; allowed = min(20000, 15000) = 15000
      (is (== -15000M (-> §163d :base :amount))
          "negative base — REDUCES PIT base")
      (is (= [-15000M] (get-in §163d [:jurisdiction-specific-codes :pit-base-additions]))
          "consumer threads negative addition")
      (is (== 5000M
              (get-in §163d [:jurisdiction-specific-codes :investment-interest-carryforward]))
          "$5k excess carries forward"))))

;; ============================================================================
;; §4. NIIT coordination — emit-niit? gating
;; ============================================================================

(deftest niit-emitted-when-emit-niit-true
  (testing "default :emit-niit? true → NIIT component fires above MAGI threshold"
    (let [conn (fresh)
          facts (run-provider conn {:qualified-dividends 100000M
                                    :ordinary-dividends 0M :reit-dividends 0M
                                    :bank-interest 50000M
                                    :corp-bond-interest 0M
                                    :treasury-interest 0M :oid-interest 0M
                                    :market-discount 0M :muni-interest 0M
                                    :investment-interest-paid 0M}
                              {:tax-unit {:filing-status :single}
                               :inputs   {:investment-income-bases
                                          {:qualified-dividends 100000M
                                           :ordinary-dividends 0M :reit-dividends 0M
                                           :bank-interest 50000M
                                           :corp-bond-interest 0M
                                           :treasury-interest 0M :oid-interest 0M
                                           :market-discount 0M :muni-interest 0M
                                           :investment-interest-paid 0M}
                                          :magi 500000M}})
          niit  (component-by-lane facts :niit)]
      ;; NII = 100k + 50k = 150k; MAGI excess = 300k; taxable = 150k.
      ;; tax = 150k × 3.8% = 5,700.
      (is (some? niit))
      (is (== 5700M (-> niit :liability :amount))))))

(deftest niit-suppressed-when-emit-niit-false
  (testing ":emit-niit? false → no NIIT component (CGT or other provider owns it)"
    (let [conn     (fresh)
          provider (inv/us-investment-income-provider {:emit-niit? false})
          facts    (ptp/period-tax-facts
                    provider
                    {:db       (d/db conn)
                     :entity   nil
                     :period   p2026
                     :tax-unit {:filing-status :single}
                     :inputs   {:investment-income-bases
                                {:qualified-dividends 100000M
                                 :ordinary-dividends 0M :reit-dividends 0M
                                 :bank-interest 50000M
                                 :corp-bond-interest 0M
                                 :treasury-interest 0M :oid-interest 0M
                                 :market-discount 0M :muni-interest 0M
                                 :investment-interest-paid 0M}
                                :magi 500000M}})]
      (is (nil? (component-by-lane facts :niit))
          "NIIT suppressed — consumer wires CGT or another provider with NIIT"))))

;; ============================================================================
;; §5. Component kind is :investment-income-tax
;; ============================================================================

(deftest components-use-investment-income-tax-kind
  (let [conn (fresh)
        facts (run-provider conn {:qualified-dividends 20000M
                                  :ordinary-dividends 5000M :reit-dividends 0M
                                  :bank-interest 0M :corp-bond-interest 0M
                                  :treasury-interest 0M :oid-interest 0M
                                  :market-discount 0M :muni-interest 0M
                                  :investment-interest-paid 0M}
                            {:tax-unit {:filing-status :single}})]
    (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
        "all components carry the new period-tax kind")))

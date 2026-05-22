(ns kontor.l10n-cn.period-tax-provider-test
  "Iteration 4 — CN Enterprise Income Tax (企业所得税) provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-cn.period-tax-provider :as cn]))

(deftest cn-eit-rate
  (testing "the standard 25% rate"
    (is (= 0.25M (:rate (cn/cn-eit-provider {})))))
  (testing "the 15% High / New-Technology Enterprise rate"
    (is (= 0.15M (:rate (cn/cn-eit-provider {:hnte? true})))))
  (testing "an explicit :rate overrides (small-low-profit regimes)"
    (is (= 0.05M (:rate (cn/cn-eit-provider {:rate 0.05M})))))
  (let [p (cn/cn-eit-provider {})]
    (is (= :CNY (:commodity p)))
    (is (= :cn-eit (:id p)))))

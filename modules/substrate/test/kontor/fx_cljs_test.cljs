(ns kontor.fx-cljs-test
  "Phase-E1b follow-up (note 192): FX conversion now runs in cljs. The cljs
   money rounding shim (multiply-amounts / divide-amounts / round, all
   bit-identical to the JVM) unblocks `kontor.fx.fx/convert`, so multi-currency
   `:translate-to` reports work in the browser too."
  (:require [cljs.test :refer [deftest is]]
            [kontor.money :as money]
            [kontor.fx.fx :as fx]
            [kontor.fx.fx-rate-provider :as fxr]))

(defn- fixed-provider [rate-str]
  (reify fxr/FxRateProvider
    (provider-id [_] :test)
    (resolve-rate [_ _] (money/->amount rate-str))
    (resolve-period-rates [_ _] nil)))

(deftest fx-convert-in-cljs
  (let [eur (money/money "100.00" :EUR)
        usd (fx/convert eur (fixed-provider "0.8375") {:to "USD" :at-date #inst "2026-03-15"})]
    (is (= "83.75 USD" (money/money->str usd))
        (str "100.00 EUR × 0.8375 → round 2 → 83.75 USD in cljs; got " (money/money->str usd)))))

(deftest fx-convert-rounds-half-even
  (let [m (money/money "10.00" :EUR)
        ;; 10.00 × 0.12345 = 1.234500 → half-even to 2 → 1.23 (tie, 3 stays)
        out (fx/convert m (fixed-provider "0.12345") {:to "USD" :at-date #inst "2026-03-15"})]
    (is (= "1.23 USD" (money/money->str out)))))

(deftest fx-convert-full-precision
  (let [m (money/money "100.00" :EUR)
        out (fx/convert m (fixed-provider "0.8375") {:to "USD" :at-date #inst "2026-03-15" :precision nil})]
    (is (= "83.750000 USD" (money/money->str out)) "precision nil keeps the exact product")))

(deftest fx-identity-short-circuit
  (let [m (money/money "42.00" :EUR)]
    (is (= m (fx/convert m (fixed-provider "999") {:to :EUR :at-date #inst "2026-03-15"}))
        "from = to returns the input unchanged, no provider call")))

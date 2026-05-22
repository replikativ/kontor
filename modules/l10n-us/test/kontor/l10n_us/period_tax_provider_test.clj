(ns kontor.l10n-us.period-tax-provider-test
  "Iteration 4 — US federal corporate income tax (Form 1120) provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-us.period-tax-provider :as us]))

(deftest us-corporate-income-tax-is-flat-21pct
  (let [p (us/us-corporate-income-tax-provider {})]
    (is (= 0.21M (:rate p)) "IRC §11 flat 21%")
    (is (= :USD (:commodity p)))
    (is (= :us-1120 (:id p)))
    (is (= :us-irs (:authority p)))
    (is (nil? (:minimum-tax p)) "CAMT is a later iteration"))
  (testing "an explicit :rate overrides"
    (is (= 0.18M (:rate (us/us-corporate-income-tax-provider {:rate 0.18M}))))))

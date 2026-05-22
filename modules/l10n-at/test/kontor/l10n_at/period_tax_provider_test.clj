(ns kontor.l10n-at.period-tax-provider-test
  "Iteration 3 — AT Kommunalsteuer period-tax provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-at.period-tax-provider :as at]
            [kontor.tax-schedule :as ts]))

(deftest kommunalsteuer-is-a-flat-3pct-levy
  (let [p (at/at-kommunalsteuer-provider {:wage-codes ["6000"]})]
    (is (= :flat (:schedule/type (:schedule p))))
    (is (= 0.03M (:rate (:schedule p))))
    (is (= :EUR (:commodity p)))
    (is (= :at-municipality (:authority p)))
    (is (= :at-kommunalsteuer (:id p))))
  (testing "an explicit :rate overrides the default"
    (is (= 0.025M (:rate (:schedule (at/at-kommunalsteuer-provider
                                     {:rate 0.025M :wage-codes ["6000"]})))))))

(deftest koest-is-flat-23pct-with-a-minimum
  (let [p (at/at-corporate-income-tax-provider {})]
    (is (= 0.23M (:rate p)) "KöSt — flat 23%")
    (is (= 1750M (:minimum-tax p)) "the Mindest-KöSt floor")
    (is (= :at-koest (:id p)))
    (is (= :EUR (:commodity p))))
  (testing "the Mindest-KöSt is overridable per entity type"
    (is (= 3500M (:minimum-tax (at/at-corporate-income-tax-provider
                                {:minimum-tax 3500M}))))))

(deftest einkommensteuer-brackets
  (let [s (:schedule (at/at-income-tax-provider {}))]
    (is (zero? (ts/apply-schedule s 12816M)) "the 0% Existenzminimum")
    (is (== 11903.70M (ts/apply-schedule s 50000M))
        "§33 EStG — 7-band progressive")
    (let [p (at/at-income-tax-provider {})]
      (is (= :at-est (:id p)))
      (is (= :EUR (:commodity p))))))

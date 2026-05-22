(ns kontor.l10n-at.period-tax-provider-test
  "Iteration 3 — AT Kommunalsteuer period-tax provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-at.period-tax-provider :as at]))

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

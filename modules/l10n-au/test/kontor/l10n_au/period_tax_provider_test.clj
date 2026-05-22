(ns kontor.l10n-au.period-tax-provider-test
  "Iteration 3 — AU state payroll-tax period-tax provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-au.period-tax-provider :as au]))

(deftest payroll-tax-by-state
  (testing "NSW — a :capped schedule (rate above the tax-free threshold)"
    (let [s (:schedule (au/au-payroll-tax-provider {:state :NSW}))]
      (is (= :capped (:schedule/type s)))
      (is (= 0.0545M (:rate s)))
      (is (= 1200000M (:floor s)))))
  (testing "all eight jurisdictions resolve to a provider"
    (doseq [st [:NSW :VIC :QLD :WA :SA :TAS :ACT :NT]]
      (is (= :au-payroll-tax (:id (au/au-payroll-tax-provider {:state st})))
          (str st))
      (is (= :AUD (:commodity (au/au-payroll-tax-provider {:state st}))))))
  (testing "an unknown state throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown state"
                          (au/au-payroll-tax-provider {:state :XYZ})))))

(deftest company-tax-rate
  (testing "a base-rate entity — 25%"
    (is (= 0.25M (:rate (au/au-company-tax-provider {:base-rate-entity? true})))))
  (testing "otherwise — 30%"
    (is (= 0.30M (:rate (au/au-company-tax-provider {:base-rate-entity? false})))))
  (let [p (au/au-company-tax-provider {})]
    (is (= :au-company-tax (:id p)))
    (is (= :AUD (:commodity p)))))

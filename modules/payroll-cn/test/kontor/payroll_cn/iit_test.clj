(ns kontor.payroll-cn.iit-test
  "Unit tests for the IIT aggregation oracle (ADR-085 / note 87 §2.1)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.iit :as iit]))

(def base-fact
  {:employment 1001
   :gross 18000M
   :net 12620M
   :components [{:kind :base-wage    :amount 18000M  :employer-side? false}
                {:kind :iit-withheld :amount -1330M  :employer-side? false}
                {:kind :ee-pension   :amount -1440M  :employer-side? false}
                {:kind :ee-medical   :amount -360M   :employer-side? false}
                {:kind :ee-unemployment :amount -90M :employer-side? false}
                {:kind :ee-housing-fund :amount -2160M :employer-side? false}]
   :jurisdiction-specific-codes {:employee-external-id "E001"}})

(def bonus-fact-single
  "An employee with both wages + a 年终奖 component electing the
   单独计税 (separate-tax) method."
  {:employment 1002
   :gross 22000M
   :net 19000M
   :components [{:kind :base-wage     :amount 10000M  :employer-side? false}
                {:kind :annual-bonus  :amount 12000M  :employer-side? false}
                {:kind :iit-withheld  :amount -3000M  :employer-side? false}]
   :jurisdiction-specific-codes {:employee-external-id "E002"
                                 :cn/annual-bonus-method :single}})

(def bonus-fact-combined
  "An employee with a 年终奖 electing the 并入综合所得 method."
  {:employment 1003
   :gross 20000M
   :net 18000M
   :components [{:kind :base-wage     :amount 8000M   :employer-side? false}
                {:kind :annual-bonus  :amount 12000M  :employer-side? false}
                {:kind :iit-withheld  :amount -2000M  :employer-side? false}]
   :jurisdiction-specific-codes {:employee-external-id "E003"
                                 :cn/annual-bonus-method :combined}})

(deftest iit-summary-aggregates-correctly
  (let [summary (iit/iit-summary-for-period [base-fact bonus-fact-single bonus-fact-combined])]
    (testing "total IIT is sum of magnitudes"
      (is (= 6330M (:total-iit summary))))
    (testing "total gross sums correctly"
      (is (= 60000M (:total-gross summary))))
    (testing "total annual-bonus is the sum of magnitudes"
      (is (= 24000M (:total-bonus summary))))
    (testing "employee count is 3"
      (is (= 3 (:employee-count summary))))
    (testing "bonus-method-counts breakdown"
      (is (= {:single 1 :combined 1} (:bonus-method-counts summary))))))

(deftest iit-summary-per-employee
  (let [summary (iit/iit-summary-per-employee [base-fact bonus-fact-single])
        e001 (first (filter #(= 1001 (:employment %)) summary))
        e002 (first (filter #(= 1002 (:employment %)) summary))]
    (is (= 1330M (:iit e001)))
    (is (= 0M (:annual-bonus e001)))
    (is (nil? (:annual-bonus-method e001)))
    (is (= 12000M (:annual-bonus e002)))
    (is (= :single (:annual-bonus-method e002)))))

(deftest annual-bonus-method-defaults-to-combined
  (let [fact-no-method (-> bonus-fact-single
                           (update :jurisdiction-specific-codes
                                   dissoc :cn/annual-bonus-method))]
    (is (= :combined (iit/annual-bonus-method fact-no-method)))))

(deftest no-bonus-no-method
  (is (nil? (iit/annual-bonus-method base-fact))))

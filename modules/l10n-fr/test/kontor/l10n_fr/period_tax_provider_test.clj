(ns kontor.l10n-fr.period-tax-provider-test
  "Iteration 5 — FR impôt sur le revenu (quotient familial) provider."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-fr.period-tax-provider :as fr]
            [kontor.tax-schedule :as ts]))

(deftest quotient-familial
  (let [s (:schedule (fr/fr-income-tax-provider {}))]
    (testing "a single filer — 1 part"
      (is (== 2286.23M
              (ts/apply-schedule s 30000M
                                 {:tax-unit {:parts 1M :reference-parts 1M}}))))
    (testing "a couple — 2 parts, income split across both"
      (is (== 4572.46M
              (ts/apply-schedule s 60000M
                                 {:tax-unit {:parts 2M :reference-parts 2M}}))
          "= 2 × the single-filer tax on half the income"))
    (testing "a couple with two children — the quotient lowers the tax"
      (is (== 2872.98M
              (ts/apply-schedule s 60000M
                                 {:tax-unit {:parts 3M :reference-parts 2M}}))))
    (testing "no :tax-unit defaults to a single filer"
      (is (== 2286.23M (ts/apply-schedule s 30000M nil))))
    (is (= :fr-ir (:id (fr/fr-income-tax-provider {}))))))

(deftest plafonnement-caps-the-quotient-benefit
  ;; A high income with many child half-parts: the quotient would save
  ;; more than the statutory cap, so the plafonnement bites.
  (let [s         (:schedule (fr/fr-income-tax-provider {}))
        with-kids (ts/apply-schedule s 200000M
                                     {:tax-unit {:parts 5M :reference-parts 2M}})
        ref-tax   (ts/apply-schedule s 200000M
                                     {:tax-unit {:parts 2M :reference-parts 2M}})]
    ;; 6 extra half-parts × €1,759 cap = €10,554 maximum benefit.
    (is (== (- ref-tax 10554M) with-kids)
        "the quotient benefit is capped at the plafond per half-part")))

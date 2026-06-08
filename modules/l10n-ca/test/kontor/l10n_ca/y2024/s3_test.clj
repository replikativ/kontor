(ns kontor.l10n-ca.y2024.s3-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.s3 :as s3]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

(deftest s3-empty
  (let [r (s3/compute {})]
    (is (≈ (cad "0.00") (:s3/taxable-capital-gains r)))))

(deftest s3-simple-gain
  (testing "$10k gain → 50% inclusion → $5,000 taxable"
    (let [r (s3/compute {:s3/gains [(cad "10000.00")]})]
      (is (≈ (cad "10000.00") (:s3/total-gains r)))
      (is (≈ (cad "10000.00") (:s3/net-capital-gain r)))
      (is (≈ (cad "5000.00")  (:s3/taxable-capital-gains r))))))

(deftest s3-gains-and-losses
  (testing "Gains 10k, losses 3k → net 7k → 50% → 3,500 taxable"
    (let [r (s3/compute {:s3/gains  [(cad "10000.00")]
                         :s3/losses [(cad "3000.00")]})]
      (is (≈ (cad "7000.00") (:s3/net-capital-gain r)))
      (is (≈ (cad "3500.00") (:s3/taxable-capital-gains r))))))

(deftest s3-net-loss-unused
  (testing "Losses 8k exceed gains 3k → net loss 5k carries forward.
            Taxable gains = 0; unused loss = 5,000."
    (let [r (s3/compute {:s3/gains  [(cad "3000.00")]
                         :s3/losses [(cad "8000.00")]})]
      (is (≈ (cad "0.00")     (:s3/taxable-capital-gains r)))
      (is (≈ (cad "5000.00")  (:s3/unused-loss r))))))

(ns kontor.l10n-ca.y2024.bc428-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.bc428 :as bc428]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

(deftest bc428-zero
  (testing "Zero taxable income → BPA alone covers NRTCs; BC tax = 0."
    (let [r (bc428/compute {})]
      (is (≈ (cad "0.00") (:bc428/bc-tax r))))))

(deftest bc428-low-income
  (testing "Taxable 29,735 (e.g. $30k income post-CPP-enh deduction).
            BC tax before credits: 29,735 × 5.06% = 1,504.591 → 1,504.59
            NRTC sub: 12,580 (BPA) + 1,311.75 (CPP base) + 498 (EI) = 14,389.75
              × 5.06% = 728.12
            BC tax = 1,504.59 - 728.12 = 776.47"
    (let [r (bc428/compute {:taxable-income     (cad "29735.00")
                            :cpp-base-employed  (cad "1311.75")
                            :ei-premiums        (cad "498.00")})]
      (is (≈ (cad "1504.59") (:bc-tax-before-credits (:bc428/lines r))))
      (is (≈ (cad "728.12")  (:bc-nrtc-at-rate (:bc428/lines r))))
      (is (≈ (cad "776.47")  (:bc428/bc-tax r))))))

(deftest bc428-bracket-2
  (testing "Taxable 94,350 spans bracket 1 and 2.
            Bracket 1: 47,937 × 5.06% = 2,425.61
            Bracket 2: 46,413 × 7.7%  = 3,573.80
            Total before credits: 5,999.41"
    (let [r (bc428/compute {:taxable-income (cad "94350.00")})]
      (is (≈ (cad "5999.41") (:bc-tax-before-credits (:bc428/lines r)))))))

(deftest bc428-with-donations
  (testing "Donations $1,000 produce BC credit: 200×5.06% + 800×16.8%
            = 10.12 + 134.40 = 144.52"
    (let [r (bc428/compute {:taxable-income (cad "50000.00")
                            :donations      (cad "1000.00")})]
      (is (≈ (cad "144.52") (:bc-donation-credit (:bc428/lines r)))))))

(deftest bc428-credits-exceed-tax
  (testing "Very low income — NRTCs > BC tax before → BC tax = 0 (not negative)."
    (let [r (bc428/compute {:taxable-income (cad "5000.00")})]
      (is (≈ (cad "0.00") (:bc428/bc-tax r))))))

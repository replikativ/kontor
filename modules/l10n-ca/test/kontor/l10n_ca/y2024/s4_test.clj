(ns kontor.l10n-ca.y2024.s4-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.s4 :as s4]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

(deftest s4-empty
  (let [r (s4/compute {})]
    (is (≈ (cad "0.00") (:s4/line-12000 r)))
    (is (≈ (cad "0.00") (:s4/federal-dtc r)))))

(deftest s4-interest-only
  (testing "$500 interest → line 12100 = 500; no dividends."
    (let [r (s4/compute {:s4/interest (cad "500.00")})]
      (is (≈ (cad "500.00") (:s4/line-12100 r)))
      (is (≈ (cad "0.00")   (:s4/line-12000 r))))))

(deftest s4-eligible-dividend
  (testing "$1,000 eligible dividend:
            Grossed-up = 1,000 × 1.38 = 1,380.00 → line 12000
            Federal DTC = 1,380 × 15.0198% = 207.27 (rounded)
            BC DTC = 1,380 × 12% = 165.60"
    (let [r (s4/compute {:s4/dividends-eligible (cad "1000.00")})]
      (is (≈ (cad "1380.00") (:s4/line-12000 r)))
      (testing "1380 × 0.150198 = 207.27324 → HALF_EVEN .27|324 → .27"
        (is (≈ (cad "207.27") (:s4/federal-dtc r))))
      (is (≈ (cad "165.60") (:s4/bc-dtc r))))))

(deftest s4-non-eligible-dividend
  (testing "$1,000 non-eligible dividend:
            Grossed-up = 1,000 × 1.15 = 1,150 → line 12000
            Federal DTC = 1,150 × 9.0301% = 103.85 (rounded)
            BC DTC = 1,150 × 1.96% = 22.54"
    (let [r (s4/compute {:s4/dividends-non-eligible (cad "1000.00")})]
      (is (≈ (cad "1150.00") (:s4/line-12000 r)))
      (testing "1150 × 0.090301 = 103.84615 → HALF_EVEN .84|615 → .85"
        (is (≈ (cad "103.85") (:s4/federal-dtc r))))
      (testing "1150 × 0.0196 = 22.5400 → 22.54"
        (is (≈ (cad "22.54") (:s4/bc-dtc r)))))))

(deftest s4-combined
  (testing "Interest 500 + eligible 1,000 + non-elig 500.
            line 12000 = 1,380 (elig) + 575 (non-elig) = 1,955
            line 12100 = 500"
    (let [r (s4/compute {:s4/interest               (cad "500.00")
                         :s4/dividends-eligible     (cad "1000.00")
                         :s4/dividends-non-eligible (cad "500.00")})]
      (is (≈ (cad "1955.00") (:s4/line-12000 r)))
      (is (≈ (cad "500.00")  (:s4/line-12100 r))))))

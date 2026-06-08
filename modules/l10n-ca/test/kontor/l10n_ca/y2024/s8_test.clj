(ns kontor.l10n-ca.y2024.s8-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.s8 :as s8]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

(deftest s8-all-zero
  (let [r (s8/compute {})]
    (is (≈ (cad "0.00") (:s8/line-30800 r)))
    (is (≈ (cad "0.00") (:s8/line-22215 r)))
    (is (≈ (cad "0.00") (:s8/line-31000 r)))
    (is (≈ (cad "0.00") (:s8/line-22200 r)))))

(deftest s8-employment-only-50k
  (testing "$50k employment, CPP $2,766.75 (= 46,500 × 5.95%).
            l-30800 = 46,500 × 4.95% = 2,301.75
            l-22215 = 46,500 × 1%    =   465.00
            No SE → 31000 = 22200 = 0."
    (let [r (s8/compute {:employment-income (cad "50000.00")
                         :employment-cpp    (cad "2766.75")})]
      (is (≈ (cad "46500.00") (:s8/employment-pensionable r)))
      (is (≈ (cad "2301.75")  (:s8/line-30800 r)))
      (is (≈ (cad "465.00")   (:s8/line-22215 r)))
      (is (≈ (cad "0.00")     (:s8/line-31000 r)))
      (is (≈ (cad "0.00")     (:s8/line-22200 r))))))

(deftest s8-se-only-30k
  (testing "$30k SE only (no employment).
            Exemption applied to SE: pensionable = 30,000 - 3,500 = 26,500.
            l-31000 = 26,500 × 4.95% = 1,311.75 (NRTC, employee-half)
            l-22200 = 26,500 × 4.95% + 26,500 × 2.0%
                    = 1,311.75 + 530.00 = 1,841.75"
    (let [r (s8/compute {:se-income (cad "30000.00")})]
      (is (≈ (cad "0.00")     (:s8/employment-pensionable r)))
      (is (≈ (cad "26500.00") (:s8/se-pensionable-base r)))
      (is (≈ (cad "1311.75")  (:s8/line-31000 r)))
      (is (≈ (cad "1841.75")  (:s8/line-22200 r))))))

(deftest s8-emp-and-se-combined-under-ympe
  (testing "$50k employment + $10k SE = $60k combined (under YMPE).
            Employment uses the exemption: emp-pensionable = 46,500.
            SE room remaining = 65,000 - 46,500 = 18,500.
            SE pensionable = min(10,000, 18,500) = 10,000 (no further exemption).
            l-31000 = 10,000 × 4.95% =   495.00
            l-22200 = 495 + 200      =   695.00 (1% × 2 enhanced = 200)"
    (let [r (s8/compute {:employment-income (cad "50000.00")
                         :employment-cpp    (cad "2766.75")
                         :se-income         (cad "10000.00")})]
      (is (≈ (cad "10000.00") (:s8/se-pensionable-base r)))
      (is (≈ (cad "0.00")     (:s8/se-pensionable-cpp2 r))
          "No CPP2 since total income < YMPE")
      (is (≈ (cad "495.00")   (:s8/line-31000 r)))
      (is (≈ (cad "695.00")   (:s8/line-22200 r))))))

(deftest s8-emp-fills-ympe-se-overflows-into-cpp2
  (testing "$70k employment + $10k SE = $80k combined.
            Employment over YMPE: emp-pensionable capped at 65,000.
            SE room in CPP1 = 65,000 - 65,000 = 0 → SE base = 0.
            Total over YMPE = 11,500; emp-over-YMPE = 1,500;
              SE share of over-YMPE = 11,500 - 1,500 = 10,000.
              SE CPP2 pensionable = min(4,700, 10,000) = 4,700.
            l-31000 = 0
            l-22200 = 4,700 × 8% (both halves of CPP2) = 376.00"
    (let [r (s8/compute {:employment-income (cad "70000.00")
                         :employment-cpp    (cad "3867.50") ; max at YMPE
                         :se-income         (cad "10000.00")})]
      (is (≈ (cad "0.00")    (:s8/se-pensionable-base r)))
      (is (≈ (cad "4700.00") (:s8/se-pensionable-cpp2 r)))
      (is (≈ (cad "0.00")    (:s8/line-31000 r)))
      (is (≈ (cad "376.00")  (:s8/line-22200 r))))))

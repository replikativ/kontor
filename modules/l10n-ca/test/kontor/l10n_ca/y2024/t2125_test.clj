(ns kontor.l10n-ca.y2024.t2125-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.y2024.t2125 :as t2125]
            [kontor.money :as money]))

(defn- cad [s] (money/money (bigdec s) :CAD))
(defn- ≈ [a b] (money/equiv? a b))

;; ============================================================================
;; CCA per class
;; ============================================================================

(deftest cca-simple-addition-half-year
  (testing "Class 8 (20%), opening UCC 10,000, add 5,000.
            net-add = 5,000; half-year-adj = 2,500.
            CCA basis = 12,500; claim = 12,500 × 0.20 = 2,500.
            Closing = 10,000 + 5,000 - 2,500 = 12,500."
    (let [r (t2125/cca-claim
             {:cca/class 8 :cca/rate 0.20M
              :cca/opening-ucc (cad "10000.00")
              :cca/additions   (cad "5000.00")
              :cca/dispositions (cad "0.00")})]
      (is (≈ (cad "2500.00") (:cca/claim r)))
      (is (≈ (cad "12500.00") (:cca/closing-ucc r))))))

(deftest cca-no-additions
  (testing "Pure declining-balance: opening 20,000, claim = 20,000 × 0.20 = 4,000."
    (let [r (t2125/cca-claim
             {:cca/class 8 :cca/rate 0.20M
              :cca/opening-ucc (cad "20000.00")
              :cca/additions   (cad "0.00")
              :cca/dispositions (cad "0.00")})]
      (is (≈ (cad "4000.00") (:cca/claim r)))
      (is (≈ (cad "16000.00") (:cca/closing-ucc r))))))

(deftest cca-disposition-exceeds-additions
  (testing "Opening 20,000, add 1,000, dispose 5,000.
            net-add = -4,000 (negative, no halving).
            CCA basis = 16,000; claim = 3,200.
            Closing = 20,000 + 1,000 - 5,000 - 3,200 = 12,800."
    (let [r (t2125/cca-claim
             {:cca/class 8 :cca/rate 0.20M
              :cca/opening-ucc (cad "20000.00")
              :cca/additions   (cad "1000.00")
              :cca/dispositions (cad "5000.00")})]
      (is (≈ (cad "3200.00") (:cca/claim r)))
      (is (≈ (cad "12800.00") (:cca/closing-ucc r))))))

;; ============================================================================
;; T2125 net income
;; ============================================================================

(deftest t2125-simple-net-income
  (testing "Gross 50,000, expenses 15,000, no CCA → net 35,000."
    (let [r (t2125/compute
             {:t2125/gross-income (cad "50000.00")
              :t2125/expenses     [(cad "10000.00") (cad "5000.00")]
              :t2125/cca-classes  []})]
      (is (≈ (cad "15000.00") (:t2125/total-expenses r)))
      (is (≈ (cad "0.00")     (:t2125/total-cca r)))
      (is (≈ (cad "35000.00") (:t2125/net-income r))))))

(deftest t2125-with-cca
  (testing "Gross 80,000, expenses 20,000, CCA 2,500 → net 57,500."
    (let [r (t2125/compute
             {:t2125/gross-income (cad "80000.00")
              :t2125/expenses     [(cad "20000.00")]
              :t2125/cca-classes
              [{:cca/class 8 :cca/rate 0.20M
                :cca/opening-ucc (cad "10000.00")
                :cca/additions   (cad "5000.00")
                :cca/dispositions (cad "0.00")}]})]
      (is (≈ (cad "20000.00") (:t2125/total-expenses r)))
      (is (≈ (cad "2500.00")  (:t2125/total-cca r)))
      (is (≈ (cad "57500.00") (:t2125/net-income r))))))

(deftest t2125-empty
  (testing "All zero inputs produce zero net income."
    (let [r (t2125/compute {})]
      (is (≈ (cad "0.00") (:t2125/net-income r))))))

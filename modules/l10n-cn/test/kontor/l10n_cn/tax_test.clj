(ns kontor.l10n-cn.tax-test
  "Tests for kontor.l10n-cn.tax — the invoicing-side compute layer.

   Scenarios cover the operationally-distinct cases for the two
   PRC VAT regimes:

     - General taxpayer (一般纳税人) — three-tier rate ladder
       (13% / 9% / 6%) per Cai Shui 2019 No. 39
     - Small-scale taxpayer (小规模纳税人) — 1% preferential
       through 2027-12-31 (Cai Shui [2023] No. 19), statutory 3%,
       5% real-estate carve-out
     - Zero-rated (export / cross-border positive list)
     - Exempt (Cai Shui 2016 No. 36 Annex 3 positive list)"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-cn.tax :as tax]
            [kontor.money :as money]))

(defn- cny [s] (money/money (bigdec s) :CNY))

;; ============================================================================
;; General taxpayer — three-tier ladder
;; ============================================================================

(deftest general-13pct-manufacturing
  (testing "Standard 13% rate — manufacturing, goods sale, processing"
    (let [r (tax/compute-tax {:line 1000M})]   ;; default :general + 13%
      (is (money/equiv? (cny "130.00")  (:output-vat r)))
      (is (money/equiv? (cny "130.00")  (:total-tax r)))
      (is (money/equiv? (cny "1130.00") (:total-gross r)))
      (is (money/equiv? (cny "1000.00") (:net r)))
      (is (= 0.13M (:rate r)))
      (is (= :general (:taxpayer-status r)))
      (is (= :taxable (:tax-status r))))))

(deftest general-9pct-transport
  (testing "Reduced 9% — transport, construction, agricultural"
    (let [r (tax/compute-tax {:line 1000M :rate 0.09M})]
      (is (money/equiv? (cny "90.00")   (:output-vat r)))
      (is (money/equiv? (cny "1090.00") (:total-gross r))))))

(deftest general-6pct-modern-services
  (testing "Services 6% — modern services, IT, R&D, financial"
    (let [r (tax/compute-tax {:line 1000M :rate 0.06M})]
      (is (money/equiv? (cny "60.00")   (:output-vat r)))
      (is (money/equiv? (cny "1060.00") (:total-gross r))))))

(deftest general-0pct-export
  (testing "0% — exports + cross-border positive-list services.
            Zero-rated supply is taxable in form (input-VAT credit
            still claimable upstream); output-vat is zero."
    (let [r (tax/compute-tax {:line 1000M :tax-status :zero-rated})]
      (is (money/equiv? (cny "0.00")    (:output-vat r)))
      (is (money/equiv? (cny "1000.00") (:total-gross r)))
      (is (= 0M (:rate r))))))

(deftest general-exempt
  (testing "Exempt — Cai Shui 2016 No. 36 Annex 3 (nursery, elder
            care, medical, etc.). No output VAT AND no input-VAT
            credit upstream (the input-VAT exclusion is handled by
            the consumer's posting builder, not here)."
    (let [r (tax/compute-tax {:line 1000M :tax-status :exempt})]
      (is (money/equiv? (cny "0.00")    (:output-vat r)))
      (is (money/equiv? (cny "1000.00") (:total-gross r))))))

(deftest general-rate-not-permitted-throws
  (testing "A general taxpayer cannot apply the small-scale 1% or
            3% rate; the rate ladder is enforced."
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M :rate 0.01M})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M :rate 0.03M})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M :rate 0.05M})))))

;; ============================================================================
;; Small-scale taxpayer — simplified collection
;; ============================================================================

(deftest small-scale-preferential-1pct
  (testing "Current preferential 1% (Cai Shui [2023] No. 19 through
            2027-12-31). This is the default small-scale rate."
    (let [r (tax/compute-tax {:line 1000M :taxpayer-status :small-scale})]
      (is (money/equiv? (cny "10.00")   (:output-vat r)))
      (is (money/equiv? (cny "1010.00") (:total-gross r)))
      (is (= 0.01M (:rate r))))))

(deftest small-scale-statutory-3pct
  (testing "Statutory 3% — applies when the preferential rate is not
            in force (post-2027 default) or to the carved-out
            categories where the preferential does not apply."
    (let [r (tax/compute-tax {:line 1000M
                              :taxpayer-status :small-scale
                              :rate 0.03M})]
      (is (money/equiv? (cny "30.00")   (:output-vat r)))
      (is (money/equiv? (cny "1030.00") (:total-gross r))))))

(deftest small-scale-real-estate-5pct
  (testing "Real-estate sale / lease — 5% carve-out, retained
            regardless of the preferential."
    (let [r (tax/compute-tax {:line 100000M
                              :taxpayer-status :small-scale
                              :rate tax/real-estate-small-scale-rate})]
      (is (money/equiv? (cny "5000.00") (:output-vat r)))
      (is (money/equiv? (cny "105000.00") (:total-gross r))))))

(deftest small-scale-rate-not-permitted-throws
  (testing "A small-scale taxpayer cannot apply general-taxpayer
            rates (13%, 9%, 6%); the rate ladder is enforced."
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M
                                   :taxpayer-status :small-scale
                                   :rate 0.13M})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M
                                   :taxpayer-status :small-scale
                                   :rate 0.09M})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 1000M
                                   :taxpayer-status :small-scale
                                   :rate 0.06M})))))

;; ============================================================================
;; Rounding — HALF-EVEN (kernel default)
;; ============================================================================

(deftest rounding-half-even
  (testing "Tax rounded HALF-EVEN to 2dp per kernel convention.
            333.33 × 13% = 43.3329 → 43.33 (round-down).
            333.35 × 13% = 43.3355 → 43.34 (banker's round-up to even)."
    (let [r1 (tax/compute-tax {:line 333.33M})
          r2 (tax/compute-tax {:line 333.35M})]
      (is (money/equiv? (cny "43.33") (:output-vat r1)))
      (is (money/equiv? (cny "43.34") (:output-vat r2))))))

;; ============================================================================
;; compute-invoice-tax — multi-line aggregation
;; ============================================================================

(deftest invoice-mixed-rates-general
  (testing "General taxpayer with three lines at three rates:
              5000 @ 13% (manufacturing) → 650 output
              3000 @ 9%  (delivery)       → 270 output
              2000 @ 6%  (consulting)     → 120 output
            Total output = 1040; total net 10000; total gross 11040."
    (let [r (tax/compute-invoice-tax
             {:taxpayer-status :general
              :lines [{:line 5000M :rate 0.13M}
                      {:line 3000M :rate 0.09M}
                      {:line 2000M :rate 0.06M}]})]
      (is (money/equiv? (cny "10000.00") (:net r)))
      (is (money/equiv? (cny "1040.00")  (:output-vat r)))
      (is (money/equiv? (cny "11040.00") (:total-gross r)))
      (is (= 3 (count (:per-line r))))
      (testing "Per-rate breakdown for Schedule 1 of the general-
                taxpayer return"
        (is (money/equiv? (cny "650.00") (get (:output-by-rate r) 0.13M)))
        (is (money/equiv? (cny "270.00") (get (:output-by-rate r) 0.09M)))
        (is (money/equiv? (cny "120.00") (get (:output-by-rate r) 0.06M)))))))

(deftest invoice-small-scale-single-rate
  (testing "Small-scale invoice — preferential 1% applies uniformly"
    (let [r (tax/compute-invoice-tax
             {:taxpayer-status :small-scale
              :lines [{:line 1000M}
                      {:line 2000M}
                      {:line 500M}]})]
      (is (money/equiv? (cny "3500.00") (:net r)))
      (is (money/equiv? (cny "35.00")   (:output-vat r)))
      (is (money/equiv? (cny "35.00")   (get (:output-by-rate r) 0.01M))))))

(deftest invoice-with-mixed-tax-status
  (testing "One taxable + one zero-rated (export) line on the same
            invoice. The zero-rated line contributes net only."
    (let [r (tax/compute-invoice-tax
             {:taxpayer-status :general
              :lines [{:line 1000M :rate 0.13M}
                      {:line 500M  :tax-status :zero-rated}]})]
      (is (money/equiv? (cny "1500.00") (:net r)))
      (is (money/equiv? (cny "130.00")  (:output-vat r)))
      (is (money/equiv? (cny "130.00")  (get (:output-by-rate r) 0.13M)))
      (is (money/equiv? (cny "0.00")    (get (:output-by-rate r) 0M))))))

;; ============================================================================
;; Validation
;; ============================================================================

(deftest invalid-taxpayer-status-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :taxpayer-status :nonsense}))))

(deftest invalid-tax-status-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :tax-status :nonsense}))))

(deftest default-rate-selection
  (is (= 0.13M (tax/default-rate :general)))
  (is (= 0.01M (tax/default-rate :small-scale))
      "Current preferential through 2027-12-31"))

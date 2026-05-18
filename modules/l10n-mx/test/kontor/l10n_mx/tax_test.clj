(ns kontor.l10n-mx.tax-test
  "Tests for kontor.l10n-mx.tax — IVA + IEPS + retenciones compute.

   Reference rates verified against:
     - Ley del IVA Art. 1 (general 16%), Art. 2 (border 8%), Art. 2-A (0%)
     - Decreto región fronteriza (8% border-zone)
     - Ley del IEPS (per-product rates passed by caller)
     - Ley del IVA Art. 1-A (retención IVA 2/3 of 16% = 10.6667%)
     - Ley del ISR Art. 106 (retención ISR honorarios 10%)"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-mx.tax :as tax]
            [kontor.money :as money]))

(defn- m [v] (money/money (bigdec v) :MXN))

;; ============================================================================
;; IVA — general rate (16%)
;; ============================================================================

(deftest iva-general-16pct
  (testing "Default invoice line (region :general) → 16% IVA"
    (let [r (tax/compute-tax {:line 1000M})]
      (is (= 0.16M (:iva-rate r)))
      (is (money/equiv? (m "160.00") (:iva-amount r)))
      (is (money/equiv? (m "0.00")   (:ieps-amount r)))
      (is (money/equiv? (m "160.00") (:total-tax r)))
      (is (money/equiv? (m "1160.00") (:total-gross r)))
      (is (money/equiv? (m "1160.00") (:total-cash-receipt r))
          "No retención → cash-receipt = gross")
      (is (= :taxable (:tax-status r)))
      (is (= :general (:region r))))))

;; ============================================================================
;; IVA — border zone (8%)
;; ============================================================================

(deftest iva-border-norte-8pct
  (testing "Northern border region → 8% IVA per the región fronteriza
            decree"
    (let [r (tax/compute-tax {:line 1000M :region :border-norte})]
      (is (= 0.08M (:iva-rate r)))
      (is (money/equiv? (m "80.00")   (:iva-amount r)))
      (is (money/equiv? (m "1080.00") (:total-gross r))))))

(deftest iva-border-sur-8pct
  (testing "Southern border region also produces 8% IVA"
    (let [r (tax/compute-tax {:line 1000M :region :border-sur})]
      (is (= 0.08M (:iva-rate r)))
      (is (money/equiv? (m "80.00") (:iva-amount r))))))

;; ============================================================================
;; IVA — 0% (food/medicine/books/exports)
;; ============================================================================

(deftest iva-zero-rated-food-medicine
  (testing "Zero-rated status forces iva-rate to 0 regardless of region.
            Basic foods, prescription medicines, books, exports per
            Art. 2-A. Cash-basis split is the invoice builder's
            problem — compute just returns the rate."
    (let [r (tax/compute-tax {:line 1000M :tax-status :zero-rated})]
      (is (= 0M (:iva-rate r)))
      (is (money/equiv? (m "0.00")    (:iva-amount r)))
      (is (money/equiv? (m "1000.00") (:total-gross r))
          "Gross = net when IVA is zero")
      (is (= :zero-rated (:tax-status r))))))

;; ============================================================================
;; IVA — exempt (residential rent, healthcare, education)
;; ============================================================================

(deftest iva-exempt-residential-rent
  (testing "Exempt status (Art. 9: residential rent, healthcare,
            education) — IVA 0, total-gross = net. Distinction from
            zero-rated is upstream ITC entitlement (which compute
            does not model)."
    (let [r (tax/compute-tax {:line 5000M :tax-status :exempt})]
      (is (= 0M (:iva-rate r)))
      (is (money/equiv? (m "0.00")    (:iva-amount r)))
      (is (money/equiv? (m "5000.00") (:total-gross r)))
      (is (= :exempt (:tax-status r))))))

;; ============================================================================
;; IVA — non-resident export
;; ============================================================================

(deftest iva-non-resident-export
  (testing "Sale to foreign buyer with goods exported (Art. 29) →
            zero-rated for compute purposes."
    (let [r (tax/compute-tax {:line 1000M :tax-status :non-resident})]
      (is (= 0M (:iva-rate r)))
      (is (money/equiv? (m "0.00")    (:iva-amount r)))
      (is (money/equiv? (m "1000.00") (:total-gross r))))))

;; ============================================================================
;; IEPS — caller-supplied rate
;; ============================================================================

(deftest ieps-sugary-drink-265pct
  (testing "Sugary drink at IEPS 26.5% (illustrative; actual SAT rate
            is currently $1.6451 MXN per litre — caller supplies the
            effective rate). 16% IVA stacks on top."
    (let [r (tax/compute-tax {:line 1000M :ieps-rate 0.265M})]
      (is (money/equiv? (m "160.00")  (:iva-amount r)))
      (is (money/equiv? (m "265.00")  (:ieps-amount r)))
      (is (money/equiv? (m "425.00")  (:total-tax r))
          "Total tax = IVA + IEPS")
      (is (money/equiv? (m "1425.00") (:total-gross r))))))

(deftest ieps-tobacco-160pct
  (testing "Tobacco IEPS at 160% (illustrative — actual rate is the
            published Art. 2 ad-valorem rate). Demonstrates that
            compute handles >100% rates without overflow."
    (let [r (tax/compute-tax {:line 100M :ieps-rate 1.60M})]
      (is (money/equiv? (m "16.00")  (:iva-amount r)))
      (is (money/equiv? (m "160.00") (:ieps-amount r))))))

(deftest no-ieps-when-rate-zero
  (testing "Default :ieps-rate 0 → no IEPS line. Most non-controlled
            goods/services don't carry IEPS."
    (let [r (tax/compute-tax {:line 1000M})]
      (is (money/equiv? (m "0.00") (:ieps-amount r)))
      (is (= 0M (:ieps-rate r))))))

;; ============================================================================
;; Retenciones — buyer-withheld taxes
;; ============================================================================

(deftest retencion-iva-honorarios
  (testing "Honorario professional service. Buyer (persona moral)
            withholds 2/3 of the IVA (≈ 10.6667%) per Art. 1-A. The
            withheld amount REDUCES cash-receipt but does NOT reduce
            the supplier's gross IVA owed to SAT."
    (let [r (tax/compute-tax {:line 1000M :retencion-iva-rate 0.106667M})]
      (is (money/equiv? (m "160.00") (:iva-amount r))
          "Supplier still owes SAT the full 16% IVA")
      (is (money/equiv? (m "106.67") (:retencion-iva r))
          "Buyer withheld 10.6667% × 1000 = 106.67")
      (is (money/equiv? (m "1160.00") (:total-gross r))
          "AR carries the full gross")
      (is (money/equiv? (m "1053.33") (:total-cash-receipt r))
          "Cash receipt = gross − retención = 1160 − 106.67 = 1053.33"))))

(deftest retencion-isr-honorarios-10pct
  (testing "ISR retención on honorarios (10% per Ley del ISR
            Art. 106). The supplier's later ISR liability is reduced
            by this withheld amount via account 120.01.001."
    (let [r (tax/compute-tax {:line 1000M :retencion-isr-rate 0.10M})]
      (is (money/equiv? (m "100.00") (:retencion-isr r)))
      (is (money/equiv? (m "1060.00") (:total-cash-receipt r))
          "Cash = 1000 + 160 IVA − 100 ISR retenido = 1060"))))

(deftest retencion-both-iva-and-isr
  (testing "Honorario invoice with both retenciones (IVA + ISR).
            Both reduce cash; neither reduces gross or SAT-owed IVA."
    (let [r (tax/compute-tax {:line 1000M
                              :retencion-iva-rate 0.106667M
                              :retencion-isr-rate 0.10M})]
      (is (money/equiv? (m "106.67") (:retencion-iva r)))
      (is (money/equiv? (m "100.00") (:retencion-isr r)))
      (is (money/equiv? (m "206.67") (:total-retencion r)))
      (is (money/equiv? (m "1160.00") (:total-gross r)))
      (is (money/equiv? (m "953.33")  (:total-cash-receipt r))
          "Cash = 1160 − 206.67 = 953.33"))))

;; ============================================================================
;; Override iva-rate
;; ============================================================================

(deftest explicit-iva-rate-overrides-region
  (testing "Explicit :iva-rate trumps the region default. Useful for
            mixed-rate scenarios."
    (let [r (tax/compute-tax {:line 1000M :iva-rate 0.08M :region :general})]
      (is (= 0.08M (:iva-rate r))
          "Explicit :iva-rate wins over the general region's 16% default")
      (is (money/equiv? (m "80.00") (:iva-amount r))))))

;; ============================================================================
;; Input shape tolerance
;; ============================================================================

(deftest accepts-bigdecimal-number-money
  (testing "compute-tax tolerates BigDecimal, number, and Money for :line"
    (let [a (tax/compute-tax {:line 100M})
          b (tax/compute-tax {:line 100})
          c (tax/compute-tax {:line (m "100")})]
      (is (money/equiv? (m "16.00") (:iva-amount a)))
      (is (money/equiv? (m "16.00") (:iva-amount b)))
      (is (money/equiv? (m "16.00") (:iva-amount c))))))

;; ============================================================================
;; Validation
;; ============================================================================

(deftest rejects-unknown-region
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :region :antarctica}))))

(deftest rejects-unknown-status
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :tax-status :something-else}))))

;; ============================================================================
;; compute-invoice-tax — multi-line aggregate
;; ============================================================================

(deftest compute-invoice-tax-aggregates
  (testing "Multi-line invoice: one taxable line + one zero-rated line.
            Aggregate = sum of per-line rounded amounts."
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M}
                      {:line 500M :tax-status :zero-rated}]})]
      (is (money/equiv? (m "160.00")  (:iva-amount r))
          "Only the taxable line contributes IVA")
      (is (money/equiv? (m "1500.00") (:net r)))
      (is (money/equiv? (m "1660.00") (:total-gross r)))
      (is (= 2 (count (:per-line r)))))))

(deftest compute-invoice-tax-border-region
  (testing "Two-line border-zone invoice — IVA 8% per line, summed."
    (let [r (tax/compute-invoice-tax
             {:region :border-norte
              :lines [{:line 100M} {:line 200M} {:line 50M}]})]
      (is (money/equiv? (m "350.00") (:net r)))
      (is (money/equiv? (m "28.00")  (:iva-amount r))
          "8% × (100 + 200 + 50) = 28")
      (is (money/equiv? (m "378.00") (:total-gross r))))))

(deftest compute-invoice-tax-with-ieps-and-retencion
  (testing "Mixed invoice: one normal line + one sugary-drink line
            with IEPS + one honorario line with retenciones. Sums
            stack across all categories."
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M}
                      {:line 500M :ieps-rate 0.265M}
                      {:line 1000M :retencion-iva-rate 0.106667M
                       :retencion-isr-rate 0.10M}]})]
      ;; IVA: 160 + 80 + 160 = 400
      (is (money/equiv? (m "400.00") (:iva-amount r)))
      ;; IEPS: 0 + 132.50 + 0 = 132.50
      (is (money/equiv? (m "132.50") (:ieps-amount r)))
      ;; Retenciones: 0 + 0 + (106.67 + 100) = 206.67
      (is (money/equiv? (m "206.67") (:total-retencion r))))))

;; ============================================================================
;; HALF-EVEN rounding
;; ============================================================================

(deftest half-even-rounding
  (testing "Per-line rounding uses HALF-EVEN (banker's rounding) at 2dp."
    ;; 12.50 × 0.16 = 2.000 → 2.00 (no rounding needed)
    (let [r (tax/compute-tax {:line 12.50M})]
      (is (money/equiv? (m "2.00") (:iva-amount r))))
    ;; 25.55 × 0.16 = 4.088 → 4.09 (HALF-EVEN: 8 odd → up)
    (let [r (tax/compute-tax {:line 25.55M})]
      (is (money/equiv? (m "4.09") (:iva-amount r))))))

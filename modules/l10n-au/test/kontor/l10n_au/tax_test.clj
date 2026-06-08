(ns kontor.l10n-au.tax-test
  "Tests for kontor.l10n-au.tax — GST compute. Reference rate
   verified against the ATO public rate page (10% since 1 July 2000)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-au.tax :as tax]
            [kontor.money :as money]))

(defn- m [v] (money/money (bigdec v) :AUD))

;; ============================================================================
;; Standard 10% taxable
;; ============================================================================

(deftest taxable-10pct
  (testing "A$1000 net @ 10% GST → A$100 GST → A$1100 gross"
    (let [r (tax/compute-tax {:line 1000M})]
      (is (money/equiv? (m "100.00")  (:gst r)))
      (is (money/equiv? (m "100.00")  (:total-tax r)))
      (is (money/equiv? (m "1100.00") (:total-gross r)))
      (is (money/equiv? (m "1000.00") (:net r)))
      (is (= :taxable (:tax-status r))))))

(deftest taxable-default-status
  (testing "Default :tax-status is :taxable when not specified"
    (let [r1 (tax/compute-tax {:line 500M})
          r2 (tax/compute-tax {:line 500M :tax-status :taxable})]
      (is (money/equiv? (:gst r1) (:gst r2)))
      (is (money/equiv? (:total-gross r1) (:total-gross r2))))))

(deftest taxable-rounding
  (testing "Per-line rounding is HALF-EVEN to 2dp"
    ;; 33.33 × 0.10 = 3.333 → 3.33 (HALF-EVEN, banker's rounding)
    (let [r (tax/compute-tax {:line 33.33M})]
      (is (money/equiv? (m "3.33") (:gst r))))
    ;; 12.345 × 0.10 = 1.2345 → 1.23 (HALF-EVEN — 0.234 → 0.23)
    (let [r (tax/compute-tax {:line 12.34M})]
      (is (money/equiv? (m "1.23") (:gst r))))))

;; ============================================================================
;; GST-free (zero-rated)
;; ============================================================================

(deftest gst-free-no-tax
  (testing "GST-free lines: 0% rate; supplier may still claim ITCs"
    (let [r (tax/compute-tax {:line 1000M :tax-status :gst-free})]
      (is (money/equiv? (m "0.00")    (:gst r)))
      (is (money/equiv? (m "0.00")    (:total-tax r)))
      (is (money/equiv? (m "1000.00") (:total-gross r))
          "Gross equals net — no GST charged")
      (is (= :gst-free (:tax-status r))))))

(deftest gst-free-categories
  (testing "GST-free use cases: fresh food, exports, health, education,
            childcare — all produce zero GST"
    (doseq [amount [100M 1000M 12345M]]
      (let [r (tax/compute-tax {:line amount :tax-status :gst-free})]
        (is (zero? (.compareTo ^java.math.BigDecimal (:amount (:gst r)) 0M)))))))

;; ============================================================================
;; Input-taxed (no GST, no ITCs upstream)
;; ============================================================================

(deftest input-taxed-no-tax
  (testing "Input-taxed lines: 0% rate; supplier may NOT claim ITCs"
    (let [r (tax/compute-tax {:line 1000M :tax-status :input-taxed})]
      (is (money/equiv? (m "0.00")    (:gst r)))
      (is (money/equiv? (m "0.00")    (:total-tax r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :input-taxed (:tax-status r))))))

(deftest input-taxed-flagged
  (testing "Input-taxed status flows through so the invoice builder
            can route revenue to the right BAS-G4 account"
    (let [r (tax/compute-tax {:line 500M :tax-status :input-taxed})]
      (is (= :input-taxed (:tax-status r))
          "Status echoed even when amount is zero"))))

;; ============================================================================
;; Invalid status
;; ============================================================================

(deftest invalid-status-throws
  (testing "Unknown :tax-status is rejected at the boundary"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 100M :tax-status :unknown})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tax/compute-tax {:line 100M :tax-status :zero-rated}))
        "AU uses :gst-free, not :zero-rated (which is a CA-ism)")))

;; ============================================================================
;; Money / BigDecimal / number input shapes
;; ============================================================================

(deftest accepts-money-input
  (testing ":line accepts a Money :AUD, a BigDecimal, or a bare number"
    (let [r1 (tax/compute-tax {:line 1000M})
          r2 (tax/compute-tax {:line (m "1000")})
          r3 (tax/compute-tax {:line 1000})]
      (is (money/equiv? (:gst r1) (:gst r2)))
      (is (money/equiv? (:gst r1) (:gst r3))))))

;; ============================================================================
;; compute-invoice-tax — multi-line aggregation
;; ============================================================================

(deftest compute-invoice-tax-aggregates
  (testing "Invoice with one taxable + one GST-free line: tax accrues
            on the taxable line only"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M}
                      {:line 500M :tax-status :gst-free}]})]
      (is (money/equiv? (m "100.00")  (:gst r))
          "Only the taxable line contributes")
      (is (money/equiv? (m "1500.00") (:net r))
          "Net sums across all lines")
      (is (money/equiv? (m "1600.00") (:total-gross r))
          "Gross = 1000 + 100 GST + 500 = 1600")
      (is (= 2 (count (:per-line r))))
      (is (every? :tax-status (:per-line r))))))

(deftest compute-invoice-tax-all-taxable
  (testing "Invoice with three taxable lines — GST aggregates linearly"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 100M} {:line 200M} {:line 300M}]})]
      (is (money/equiv? (m "60.00")  (:gst r)))
      (is (money/equiv? (m "600.00") (:net r)))
      (is (money/equiv? (m "660.00") (:total-gross r))))))

(deftest compute-invoice-tax-all-input-taxed
  (testing "Pure input-taxed invoice (e.g. residential rent) → zero tax"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 2000M :tax-status :input-taxed}]})]
      (is (money/equiv? (m "0.00")    (:gst r)))
      (is (money/equiv? (m "2000.00") (:net r)))
      (is (money/equiv? (m "2000.00") (:total-gross r))))))

(deftest compute-invoice-tax-empty
  (testing "Empty :lines → zero summary"
    (let [r (tax/compute-invoice-tax {:lines []})]
      (is (money/equiv? (m "0.00") (:gst r)))
      (is (money/equiv? (m "0.00") (:net r)))
      (is (empty? (:per-line r))))))

;; ============================================================================
;; Status registry — public API stability
;; ============================================================================

(deftest tax-statuses-public
  (testing "Public registry stays in sync with the compute fn"
    (is (contains? tax/tax-statuses :taxable))
    (is (contains? tax/tax-statuses :gst-free))
    (is (contains? tax/tax-statuses :input-taxed))
    (is (= 3 (count tax/tax-statuses))
        "Three categories — additions belong in a future ADR")))

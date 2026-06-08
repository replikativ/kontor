(ns kontor.l10n-fr.tax-test
  "Tests for kontor.l10n-fr.tax — TVA compute (taux normal 20% / taux
   intermédiaire 10% / taux réduit 5,5% / taux particulier 2,1% + the
   non-taxable statuses). Reference rates verified against:
     - BOFiP-Impôts TVA-LIQ (taux applicables)
     - CGI articles 278 à 278-0 ter
     - economie.gouv.fr/cedef/taux-tva"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-fr.tax :as tax]
            [kontor.money :as money]))

(defn- m [v] (money/money (bigdec v) :EUR))

;; ============================================================================
;; Standard 20% — taux normal
;; ============================================================================

(deftest std-20pct
  (testing "Default rate (taux normal 20%): €1000 net → €200 TVA → €1200 gross"
    (let [r (tax/compute-tax {:line 1000M})]
      (is (money/equiv? (m "200.00")  (:tva r)))
      (is (money/equiv? (m "200.00")  (:total-tax r)))
      (is (money/equiv? (m "1200.00") (:total-gross r)))
      (is (money/equiv? (m "1000.00") (:net r)))
      (is (= :std (:rate r)))
      (is (= 0.20M (:rate-value r))))))

(deftest std-explicit-rate-keyword
  (testing "Passing :rate :std explicitly is equivalent to the default"
    (let [r (tax/compute-tax {:line 500M :rate :std})]
      (is (money/equiv? (m "100.00") (:tva r))))))

;; ============================================================================
;; Intermediate 10% — taux intermédiaire (restaurants, transport, hospitality)
;; ============================================================================

(deftest inter-10pct
  (testing "Taux intermédiaire 10%: restaurant bill €1000 → €100 TVA"
    (let [r (tax/compute-tax {:line 1000M :rate :inter})]
      (is (money/equiv? (m "100.00")  (:tva r)))
      (is (money/equiv? (m "1100.00") (:total-gross r)))
      (is (= :inter (:rate r)))
      (is (= 0.10M (:rate-value r))))))

;; ============================================================================
;; Reduced 5,5% — taux réduit (food, books, women's hygiene products, culture)
;; ============================================================================

(deftest red-5-5pct
  (testing "Taux réduit 5,5%: €1000 of books → €55 TVA"
    (let [r (tax/compute-tax {:line 1000M :rate :red})]
      (is (money/equiv? (m "55.00")   (:tva r)))
      (is (money/equiv? (m "1055.00") (:total-gross r)))
      (is (= :red (:rate r)))
      (is (= 0.055M (:rate-value r))))))

(deftest red-rounding
  (testing "5,5% × small amount rounds HALF-EVEN at 2dp.
            12.50 × 0.055 = 0.6875 → 0.69 (HALF-EVEN: away from 0
            because 8 is even — wait, banker's: 6.875 with cut at
            .5 rounds to even; 0.6875 at 2dp drops to 0.69 — banker's
            on the trailing 75 rounds the prior 8 to keep 9 odd? No:
            the digit before the cut is 8 (even), and the trailing
            is 75 (>50), so round up → 0.69. Equivalent test."
    (let [r (tax/compute-tax {:line 12.50M :rate :red})]
      (is (money/equiv? (m "0.69") (:tva r))))))

;; ============================================================================
;; Special 2,1% — taux particulier (medicines reimbursed by social security)
;; ============================================================================

(deftest spec-2-1pct
  (testing "Taux particulier 2,1%: €1000 of reimbursed medicines → €21 TVA"
    (let [r (tax/compute-tax {:line 1000M :rate :spec})]
      (is (money/equiv? (m "21.00")   (:tva r)))
      (is (money/equiv? (m "1021.00") (:total-gross r)))
      (is (= :spec (:rate r))))))

;; ============================================================================
;; Zero rate — :zero (kept for symmetry, callers normally use the
;; tax-status keys below)
;; ============================================================================

(deftest zero-rate-keyword
  (testing "rate :zero produces no TVA but stays in the taxable form"
    (let [r (tax/compute-tax {:line 1000M :rate :zero})]
      (is (money/equiv? (m "0.00")    (:tva r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :zero (:rate r)))
      (is (= :taxable (:tax-status r))))))

;; ============================================================================
;; Non-taxable statuses
;; ============================================================================

(deftest exempt-line
  (testing "Exempt supplies (services bancaires, location nue, santé,
              enseignement) produce no TVA and the gross equals the net.
              Distinction vs :zero matters for ITC claims upstream."
    (let [r (tax/compute-tax {:line 1000M :tax-status :exempt})]
      (is (money/equiv? (m "0.00")    (:tva r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :exempt (:tax-status r))))))

(deftest intra-eu-b2b-reverse-charge
  (testing "Intra-EU B2B supply: reverse charge — buyer self-assesses.
              French invoice carries no TVA (CGI art.283-1)."
    (let [r (tax/compute-tax {:line 1000M :tax-status :intra-eu-b2b})]
      (is (money/equiv? (m "0.00")    (:tva r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :intra-eu-b2b (:tax-status r))))))

(deftest export-zero-rated
  (testing "Export outside EU (CGI art.262 I) — zero-rated."
    (let [r (tax/compute-tax {:line 1000M :tax-status :export})]
      (is (money/equiv? (m "0.00")    (:tva r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :export (:tax-status r))))))

;; ============================================================================
;; Input validation
;; ============================================================================

(deftest rejects-unknown-rate
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :rate :bogus}))))

(deftest rejects-unknown-status
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :tax-status :nonsense}))))

(deftest accepts-bigdecimal-and-number-line
  (testing "compute-tax tolerates BigDecimal, number, and Money for :line"
    (let [a (tax/compute-tax {:line 100M})
          b (tax/compute-tax {:line 100})
          c (tax/compute-tax {:line (m "100")})]
      (is (money/equiv? (m "20.00") (:tva a)))
      (is (money/equiv? (m "20.00") (:tva b)))
      (is (money/equiv? (m "20.00") (:tva c))))))

;; ============================================================================
;; Multi-line invoice — mixed rates
;; ============================================================================

(deftest compute-invoice-tax-mixed-rates
  (testing "Restaurant bill: food at 10%, alcoholic drinks at 20%.
              Mixed-rate invoices are typical in France."
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 100M :rate :inter}    ; food
                      {:line  50M :rate :std}]})]  ; wine
      ;; 10 + 10 = 20 TVA
      (is (money/equiv? (m "20.00")  (:tva r)))
      (is (money/equiv? (m "150.00") (:net r)))
      (is (money/equiv? (m "170.00") (:total-gross r)))
      (is (= 2 (count (:per-line r)))))))

(deftest compute-invoice-tax-mixed-with-exempt
  (testing "Mixed invoice: one std 20% + one exempt line.
              The exempt line contributes net only, no TVA."
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M}                            ; std → 200
                      {:line 500M :tax-status :exempt}]})]     ; 0
      (is (money/equiv? (m "200.00")  (:tva r)))
      (is (money/equiv? (m "1500.00") (:net r)))
      (is (money/equiv? (m "1700.00") (:total-gross r))))))

(deftest compute-invoice-tax-rate-coverage
  (testing "One line per rate keyword — aggregate hits all four
              taxable rates plus the zero-rate keyword"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M :rate :std}     ; 200
                      {:line 1000M :rate :inter}   ; 100
                      {:line 1000M :rate :red}     ;  55
                      {:line 1000M :rate :spec}    ;  21
                      {:line 1000M :rate :zero}]})] ;  0
      (is (money/equiv? (m "376.00")  (:tva r))
          "200 + 100 + 55 + 21 + 0 = 376")
      (is (money/equiv? (m "5000.00") (:net r)))
      (is (money/equiv? (m "5376.00") (:total-gross r))))))

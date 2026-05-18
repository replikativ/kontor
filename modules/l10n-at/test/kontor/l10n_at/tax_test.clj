(ns kontor.l10n-at.tax-test
  "Tests for kontor.l10n-at.tax — the callable USt compute function
   family. Companion to the UVA filing-side report (see
   uva_test.clj).

   Test scenarios cover all five rate buckets (20/13/10/0/exempt)
   plus the reverse-charge audit semantics."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-at.tax :as tax]
            [kontor.money :as money]))

;; ============================================================================
;; Rate table sanity
;; ============================================================================

(deftest rate-for-returns-rates
  (is (= 0.20M (tax/rate-for :standard)))
  (is (= 0.13M (tax/rate-for :reduced-13)))
  (is (= 0.10M (tax/rate-for :reduced-10)))
  (is (= 0M    (tax/rate-for :zero)))
  (is (= 0M    (tax/rate-for :exempt)))
  (is (= 0M    (tax/rate-for :reverse-charge))))

(deftest rate-for-rejects-unknown
  (is (thrown? clojure.lang.ExceptionInfo (tax/rate-for :bogus)))
  (is (thrown? clojure.lang.ExceptionInfo (tax/rate-for nil))))

(deftest vat-classes-cover-five-buckets
  (testing "Every published BMF VAT classification has a code"
    (is (contains? tax/vat-classes :standard))
    (is (contains? tax/vat-classes :reduced-13))
    (is (contains? tax/vat-classes :reduced-10))
    (is (contains? tax/vat-classes :zero))
    (is (contains? tax/vat-classes :exempt))
    (is (contains? tax/vat-classes :reverse-charge))))

;; ============================================================================
;; compute-tax — single-line
;; ============================================================================

(deftest standard-20pct
  (testing "€1000 net at 20% Normalsteuersatz → €200 USt, €1200 gross"
    (let [r (tax/compute-tax {:line 1000M})]
      (is (money/equiv? (money/money "1000.00" :EUR) (:net r)))
      (is (money/equiv? (money/money "200.00"  :EUR) (:ust r)))
      (is (money/equiv? (money/money "1200.00" :EUR) (:total-gross r)))
      (is (= :standard (:vat-class r)))
      (is (= 0.20M (:rate r)))
      (is (false? (:recipient-owes? r)))
      (is (true?  (:supplier-deducts-vorsteuer? r))))))

(deftest reduced-13pct
  (testing "€1000 net at 13% ermäßigter Steuersatz → €130 USt, €1130 gross"
    (let [r (tax/compute-tax {:line 1000M :vat-class :reduced-13})]
      (is (money/equiv? (money/money "130.00"  :EUR) (:ust r)))
      (is (money/equiv? (money/money "1130.00" :EUR) (:total-gross r)))
      (is (= 0.13M (:rate r))))))

(deftest reduced-10pct
  (testing "€1000 net at 10% ermäßigter Steuersatz → €100 USt, €1100 gross.
            Used for books, food, residential rent (partial),
            restaurants (Speisen)."
    (let [r (tax/compute-tax {:line 1000M :vat-class :reduced-10})]
      (is (money/equiv? (money/money "100.00"  :EUR) (:ust r)))
      (is (money/equiv? (money/money "1100.00" :EUR) (:total-gross r)))
      (is (= 0.10M (:rate r))))))

(deftest zero-rated-exports
  (testing "Zero-rated (intra-EU B2B / exports) → 0 USt, supplier
            still deducts Vorsteuer"
    (let [r (tax/compute-tax {:line 1000M :vat-class :zero})]
      (is (money/zero? (:ust r)))
      (is (money/equiv? (money/money "1000.00" :EUR) (:total-gross r)))
      (is (true? (:supplier-deducts-vorsteuer? r))
          "Zero-rated allows Vorsteuer deduction"))))

(deftest exempt-no-vorsteuer-deduction
  (testing "Exempt (financial services / medical / education) → 0 USt,
            supplier CANNOT deduct Vorsteuer (echt steuerfrei OHNE
            Vorsteuerabzug)"
    (let [r (tax/compute-tax {:line 1000M :vat-class :exempt})]
      (is (money/zero? (:ust r)))
      (is (false? (:supplier-deducts-vorsteuer? r))
          "Exempt blocks Vorsteuer deduction — the key audit distinction"))))

(deftest reverse-charge-recipient-self-assesses
  (testing "Reverse charge — supplier emits 0 output VAT, recipient
            owes (`Steuerschuldnerschaft des Leistungsempfängers
            gemäß §19 Abs.1a UStG`)"
    (let [r (tax/compute-tax {:line 1000M :vat-class :reverse-charge})]
      (is (money/zero? (:ust r)))
      (is (money/equiv? (money/money "1000.00" :EUR) (:total-gross r)))
      (is (true?  (:recipient-owes? r)))
      (is (true?  (:supplier-deducts-vorsteuer? r))
          "Reverse-charge supplier still deducts Vorsteuer on inputs"))))

(deftest default-vat-class-is-standard
  (testing "Omitting :vat-class defaults to 20% Normalsteuersatz"
    (is (= :standard (:vat-class (tax/compute-tax {:line 100M}))))
    (is (= 0.20M (:rate (tax/compute-tax {:line 100M}))))))

(deftest rejects-unknown-vat-class
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :vat-class :bogus}))))

;; ============================================================================
;; Half-even rounding
;; ============================================================================

(deftest rounding-half-even
  (testing "VAT lines round to 2dp HALF-EVEN. €12.345 @ 20% → 2.469 → 2.47"
    (let [r (tax/compute-tax {:line 12.345M})]
      ;; 12.345 * 0.20 = 2.4690 → 2.47 (HALF-EVEN rounds tie-to-even
      ;; on the second-decimal boundary; here the fourth digit is 9
      ;; so we round up — same as HALF-UP).
      (is (money/equiv? (money/money "2.47" :EUR) (:ust r)))))
  (testing "Ties round to even — 0.125 → 0.12 (not 0.13)"
    (let [r (tax/compute-tax {:line 0.625M})]
      ;; 0.625 * 0.20 = 0.125. HALF-EVEN: tie → even → 0.12.
      (is (money/equiv? (money/money "0.12" :EUR) (:ust r))))))

;; ============================================================================
;; Money / numeric input variants
;; ============================================================================

(deftest accepts-money-input
  (testing "Line amount accepts BigDecimal, integer, or Money record"
    (is (money/equiv? (money/money "200.00" :EUR)
                      (:ust (tax/compute-tax {:line 1000M}))))
    (is (money/equiv? (money/money "200.00" :EUR)
                      (:ust (tax/compute-tax {:line 1000}))))
    (is (money/equiv? (money/money "200.00" :EUR)
                      (:ust (tax/compute-tax
                             {:line (money/money "1000" :EUR)}))))))

;; ============================================================================
;; compute-invoice-tax — multi-line aggregation
;; ============================================================================

(deftest invoice-mixed-rates
  (testing "Invoice with three lines at three different rates:
              €1000 @ 20% → 200 USt
              €500  @ 13% → 65 USt
              €300  @ 10% → 30 USt
            Sums: net 1800, USt 295, gross 2095"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M :vat-class :standard}
                      {:line 500M  :vat-class :reduced-13}
                      {:line 300M  :vat-class :reduced-10}]})]
      (is (money/equiv? (money/money "1800.00" :EUR) (:net r)))
      (is (money/equiv? (money/money "295.00"  :EUR) (:ust r)))
      (is (money/equiv? (money/money "2095.00" :EUR) (:total-gross r)))
      (is (= 3 (count (:per-line r)))))))

(deftest invoice-by-class-bucketing
  (testing ":by-class groups per-rate sums for posting routing"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M :vat-class :standard}
                      {:line 500M  :vat-class :standard}
                      {:line 300M  :vat-class :reduced-10}]})
          by-class (:by-class r)]
      (is (money/equiv? (money/money "1500.00" :EUR)
                        (get-in by-class [:standard :net])))
      (is (money/equiv? (money/money "300.00"  :EUR)
                        (:net (:reduced-10 by-class))))
      (is (money/equiv? (money/money "300.00"  :EUR)
                        (:ust (:standard by-class))))
      (is (money/equiv? (money/money "30.00"   :EUR)
                        (:ust (:reduced-10 by-class)))))))

(deftest invoice-intra-eu-reverse-charge
  (testing "Intra-EU B2B export → recipient owes, supplier emits 0 USt
            but :recipient-owes? flag flows per-line for invoice
            narration"
    (let [r (tax/compute-invoice-tax
             {:lines [{:line 1000M :vat-class :reverse-charge}]})]
      (is (money/zero? (:ust r)))
      (is (money/equiv? (money/money "1000.00" :EUR) (:total-gross r)))
      (is (true? (:recipient-owes? (first (:per-line r))))))))

(deftest invoice-rejects-empty-lines
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-invoice-tax {:lines []})))
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-invoice-tax {:lines nil}))))

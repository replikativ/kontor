(ns kontor.l10n-ca.tax-test
  "Tests for kontor.l10n-ca.tax — federal GST + HST + provincial
   PST/QST compute. Reference rates verified against:
     - CRA: GST/HST rate by province (current 2026)
     - BC Min. of Finance: PST 7%
     - SK Ministry of Finance: PST 6%
     - Manitoba Taxation: RST 7%
     - Revenu Québec: QST 9.975% (GST-exclusive base)"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.tax :as tax]
            [kontor.money :as money]))

(defn- m [v] (money/money (bigdec v) :CAD))

;; ============================================================================
;; HST provinces — single combined federal/provincial rate
;; ============================================================================

(deftest hst-ontario-13pct
  (testing "ON sale of $1000 net → $130 HST → $1130 gross"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :ON})]
      (is (money/equiv? (m "0.00")    (:gst r)))
      (is (money/equiv? (m "130.00")  (:hst r)))
      (is (money/equiv? (m "0.00")    (:pst r)))
      (is (money/equiv? (m "0.00")    (:qst r)))
      (is (money/equiv? (m "130.00")  (:total-tax r)))
      (is (money/equiv? (m "1130.00") (:total-gross r))))))

(deftest hst-nova-scotia-15pct
  (testing "NS sale of $1000 net → $150 HST"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :NS})]
      (is (money/equiv? (m "150.00") (:hst r)))
      (is (money/equiv? (m "150.00") (:total-tax r))))))

(deftest hst-other-15pct-provinces
  (testing "NB / NL / PE all use 15% HST"
    (doseq [prov [:NB :NL :PE]]
      (let [r (tax/compute-tax {:line 1000M :ship-to-province prov})]
        (is (money/equiv? (m "150.00") (:hst r))
            (str prov " should produce 15% HST"))))))

;; ============================================================================
;; GST-only provinces (no provincial sales tax)
;; ============================================================================

(deftest gst-alberta-only-5pct
  (testing "AB sale of $1000 net → $50 GST, no provincial component"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :AB})]
      (is (money/equiv? (m "50.00")  (:gst r)))
      (is (money/equiv? (m "0.00")   (:hst r)))
      (is (money/equiv? (m "0.00")   (:pst r)))
      (is (money/equiv? (m "0.00")   (:qst r)))
      (is (money/equiv? (m "50.00")  (:total-tax r))))))

(deftest gst-territories
  (testing "Northern territories use GST-only (no PST/HST)"
    (doseq [prov [:NT :NU :YT]]
      (let [r (tax/compute-tax {:line 1000M :ship-to-province prov})]
        (is (money/equiv? (m "50.00") (:gst r))
            (str prov " — 5% GST"))
        (is (money/equiv? (m "0.00")  (:hst r)))
        (is (money/equiv? (m "0.00")  (:pst r)))))))

;; ============================================================================
;; GST + PST provinces (BC, SK, MB)
;; ============================================================================

(deftest bc-gst-plus-pst
  (testing "BC sale of $1000 net → $50 GST + $70 PST = $120 total"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :BC})]
      (is (money/equiv? (m "50.00")   (:gst r)))
      (is (money/equiv? (m "70.00")   (:pst r)))
      (is (money/equiv? (m "0.00")    (:hst r)))
      (is (money/equiv? (m "0.00")    (:qst r)))
      (is (money/equiv? (m "120.00")  (:total-tax r)))
      (is (money/equiv? (m "1120.00") (:total-gross r))))))

(deftest sk-gst-plus-pst
  (testing "SK PST is 6% (lower than BC/MB)"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :SK})]
      (is (money/equiv? (m "50.00")  (:gst r)))
      (is (money/equiv? (m "60.00")  (:pst r)))
      (is (money/equiv? (m "110.00") (:total-tax r))))))

(deftest mb-gst-plus-rst
  (testing "MB calls it RST (Retail Sales Tax) but the math is PST-shaped"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :MB})]
      (is (money/equiv? (m "50.00")  (:gst r)))
      (is (money/equiv? (m "70.00")  (:pst r))
          "MB RST 7% surfaces under the :pst key (compute is structural)")
      (is (money/equiv? (m "120.00") (:total-tax r))))))

;; ============================================================================
;; Quebec — GST + QST parallel VAT system
;; ============================================================================

(deftest qc-gst-plus-qst-9975
  (testing "QC sale of $1000 net → $50 GST + $99.75 QST.
            Since 2013, QST is computed on the GST-exclusive base
            (i.e. NOT compounded on GST). Pre-2013 the base was
            GST-inclusive; the current rule produces 9.975% × net,
            not 9.975% × (net + GST)."
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :QC})]
      (is (money/equiv? (m "50.00")   (:gst r)))
      (is (money/equiv? (m "99.75")   (:qst r))
          "QST = 9.975% × 1000 = 99.75, on net base")
      (is (money/equiv? (m "0.00")    (:hst r)))
      (is (money/equiv? (m "0.00")    (:pst r)))
      (is (money/equiv? (m "149.75")  (:total-tax r)))
      (is (money/equiv? (m "1149.75") (:total-gross r))))))

(deftest qc-qst-rounding
  (testing "QST 9.975% × small amount rounds HALF-EVEN at 2dp"
    (let [r (tax/compute-tax {:line 12.50M :ship-to-province :QC})]
      ;; 12.50 × 0.09975 = 1.246875 → 1.25 HALF-EVEN
      (is (money/equiv? (m "1.25") (:qst r))))))

;; ============================================================================
;; Zero-rated / exempt / non-resident
;; ============================================================================

(deftest zero-rated-line
  (testing "Zero-rated supplies (groceries, exports, prescription drugs)
            generate no tax in any province, regardless of rate table"
    (doseq [prov [:ON :BC :QC :AB :NS]]
      (let [r (tax/compute-tax {:line 1000M :ship-to-province prov
                                :tax-status :zero-rated})]
        (is (money/equiv? (m "0.00")    (:total-tax r))
            (str "Zero-rated in " prov " — no tax"))
        (is (money/equiv? (m "1000.00") (:total-gross r))
            "gross = net")
        (is (= :zero-rated (:tax-status r)))))))

(deftest exempt-line
  (testing "Exempt supplies (residential rent, most healthcare) also
            produce no tax. The distinction vs zero-rated matters for
            ITC claims upstream, not for invoice-side computation."
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :ON
                              :tax-status :exempt})]
      (is (money/equiv? (m "0.00")    (:total-tax r)))
      (is (money/equiv? (m "1000.00") (:total-gross r)))
      (is (= :exempt (:tax-status r))))))

(deftest non-resident-export
  (testing "Sale to a buyer outside Canada with goods exported
            is zero-rated under ETA s.12(a)"
    (let [r (tax/compute-tax {:line 1000M :ship-to-province :ON
                              :tax-status :non-resident})]
      (is (money/equiv? (m "0.00")    (:total-tax r)))
      (is (money/equiv? (m "1000.00") (:total-gross r))))))

;; ============================================================================
;; Input validation
;; ============================================================================

(deftest rejects-unknown-province
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :ship-to-province :ZZ}))
      "Bogus province code throws"))

(deftest rejects-unknown-status
  (is (thrown? clojure.lang.ExceptionInfo
               (tax/compute-tax {:line 100M :ship-to-province :ON
                                 :tax-status :something-else}))
      "Unknown :tax-status throws"))

(deftest accepts-bigdecimal-and-number-line
  (testing "compute-tax tolerates BigDecimal, number, and Money for :line"
    (let [a (tax/compute-tax {:line 100M     :ship-to-province :AB})
          b (tax/compute-tax {:line 100      :ship-to-province :AB})
          c (tax/compute-tax {:line (m "100") :ship-to-province :AB})]
      (is (money/equiv? (m "5.00") (:gst a)))
      (is (money/equiv? (m "5.00") (:gst b)))
      (is (money/equiv? (m "5.00") (:gst c))))))

;; ============================================================================
;; Multi-line invoice
;; ============================================================================

(deftest compute-invoice-tax-aggregates
  (testing "Multi-line invoice: one taxable line + one zero-rated.
            Aggregate matches sum of per-line rounded values."
    (let [r (tax/compute-invoice-tax
             {:ship-to-province :ON
              :lines [{:line 1000M}                            ; taxable
                      {:line 500M :tax-status :zero-rated}]})] ; zero-rated
      (is (money/equiv? (m "130.00")  (:hst r))
          "HST only from the taxable line")
      (is (money/equiv? (m "1500.00") (:net r)))
      (is (money/equiv? (m "130.00")  (:total-tax r)))
      (is (money/equiv? (m "1630.00") (:total-gross r)))
      (is (= 2 (count (:per-line r)))))))

(deftest compute-invoice-tax-bc-multi-line
  (testing "BC invoice with two taxable lines — GST and PST each
            aggregate per the per-line rounded amounts"
    (let [r (tax/compute-invoice-tax
             {:ship-to-province :BC
              :lines [{:line 100M} {:line 200M} {:line 50M}]})]
      ;; net 350
      (is (money/equiv? (m "350.00") (:net r)))
      ;; 5% GST = 17.50 (5+10+2.50)
      (is (money/equiv? (m "17.50")  (:gst r)))
      ;; 7% PST = 24.50 (7+14+3.50)
      (is (money/equiv? (m "24.50")  (:pst r)))
      (is (money/equiv? (m "42.00")  (:total-tax r)))
      (is (money/equiv? (m "392.00") (:total-gross r))))))

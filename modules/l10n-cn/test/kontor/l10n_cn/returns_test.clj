(ns kontor.l10n-cn.returns-test
  "Tests for kontor.l10n-cn.returns — the per-taxpayer-status filing
   aggregator that re-keys vat/compute-return base output into the
   STA form line-numbers consumed by downstream filing tools."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.invoice :as inv]
            [kontor.l10n-cn.returns :as ret]
            [kontor.money :as money]
            [kontor.validation :as v]))

(defn- cny [s] (money/money (bigdec s) :CNY))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")
(def apr-1  #inst "2026-04-01T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV" :kontor.journal/name "Sales"
                       :kontor.journal/type :sale :kontor.journal/active true}])
    conn))

;; ============================================================================
;; General taxpayer — monthly return
;; ============================================================================

(deftest general-single-13pct-sale
  (testing "Single CNY 10,000 sale at 13% (manufacturing) within January 2026.
              Main-form line 1 = 10,000 (sales at standard rates)
              Line 11 = 1,300 (output VAT)
              Line 19 = 1,300 (payable)
              Schedule 1 rate-13 row populated."
    (let [conn (bootstrap)
          _ (inv/post-cn-invoice!
             conn
             {:kontor.invoice/external-id "INV-G-1"
              :kontor.invoice/issue-date jan-15
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 10000M}]})
          r (ret/compute-return conn {:year 2026 :month 1
                                      :compute-surcharges? false})]
      (is (= "VAT-PRC-General" (:return/form r)))
      (is (= :general (:return/taxpayer-status r)))
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "10000.00") (:1 lines))
            "Line 1 = sum of taxable per-rate sales")
        (is (money/equiv? (cny "0.00")     (:8 lines)) "Line 8 = 0 export")
        (is (money/equiv? (cny "1300.00")  (:11 lines)) "Line 11 = output")
        (is (money/equiv? (cny "1300.00")  (:19 lines)) "Line 19 = payable"))
      (let [sched-1 (:return/schedule-1 r)]
        (is (money/equiv? (cny "10000.00") (:net (:rate-13 sched-1))))
        (is (money/equiv? (cny "1300.00")  (:output (:rate-13 sched-1))))
        (is (money/equiv? (cny "0.00")     (:net (:rate-9 sched-1))))
        (is (money/equiv? (cny "0.00")     (:net (:rate-6 sched-1))))))))

(deftest general-mixed-rates
  (testing "General taxpayer with three rates inside a month.
              5,000 @ 13% + 3,000 @ 9% + 2,000 @ 6%
              → line 1 sales 10,000; line 11 output (650 + 270 + 120 = 1,040)
              → Schedule 1 splits per rate"
    (let [conn (bootstrap)
          _ (inv/post-cn-invoice!
             conn
             {:kontor.invoice/external-id "INV-G-MIX-1"
              :kontor.invoice/issue-date jan-15
              :kontor.invoice/lines
              [{:kontor.invoice-line/quantity 1 :kontor.invoice-line/unit-price 5000M
                :kontor.invoice-line/rate 0.13M}
               {:kontor.invoice-line/quantity 1 :kontor.invoice-line/unit-price 3000M
                :kontor.invoice-line/rate 0.09M}
               {:kontor.invoice-line/quantity 1 :kontor.invoice-line/unit-price 2000M
                :kontor.invoice-line/rate 0.06M}]})
          r (ret/compute-return conn {:year 2026 :month 1
                                      :compute-surcharges? false})]
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "10000.00") (:1 lines)))
        (is (money/equiv? (cny "1040.00")  (:11 lines))))
      (let [sched-1 (:return/schedule-1 r)]
        (is (money/equiv? (cny "5000.00") (:net (:rate-13 sched-1))))
        (is (money/equiv? (cny "3000.00") (:net (:rate-9  sched-1))))
        (is (money/equiv? (cny "2000.00") (:net (:rate-6  sched-1))))
        (is (money/equiv? (cny "650.00")  (:output (:rate-13 sched-1))))
        (is (money/equiv? (cny "270.00")  (:output (:rate-9  sched-1))))
        (is (money/equiv? (cny "120.00")  (:output (:rate-6  sched-1))))))))

(deftest general-with-export
  (testing "General taxpayer with one domestic + one export line.
              Line 1 (taxable) = 5,000; line 8 (export) = 1,000;
              output VAT = 650."
    (let [conn (bootstrap)
          _ (inv/post-cn-invoice!
             conn
             {:kontor.invoice/external-id "INV-G-EX-1"
              :kontor.invoice/issue-date jan-15
              :kontor.invoice/lines
              [{:kontor.invoice-line/quantity 1 :kontor.invoice-line/unit-price 5000M
                :kontor.invoice-line/rate 0.13M}
               {:kontor.invoice-line/quantity 1 :kontor.invoice-line/unit-price 1000M
                :kontor.invoice-line/tax-status :zero-rated}]})
          r (ret/compute-return conn {:year 2026 :month 1
                                      :compute-surcharges? false})]
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "5000.00") (:1 lines))
            "Line 1 = domestic standard-rate sales only")
        (is (money/equiv? (cny "1000.00") (:8 lines))
            "Line 8 = export sales")
        (is (money/equiv? (cny "650.00")  (:11 lines)))))))

(deftest general-nil-return
  (testing "No postings → nil return"
    (let [conn (bootstrap)
          r (ret/compute-return conn {:year 2026 :month 1
                                      :compute-surcharges? false})]
      (is (= :nil-return (:return/outcome r)))
      (is (money/equiv? (cny "0.00") (:11 (:return/lines r)))))))

;; ============================================================================
;; General taxpayer — surcharges (UMCT + Edu + Local-Edu)
;; ============================================================================

(deftest general-surcharges-municipal
  (testing "10k @ 13% sale for a municipal-tier general taxpayer.
              Net VAT 1,300; UMCT 91 + Edu 39 + Local 26 = surcharges 156."
    (let [conn (bootstrap)
          _ (inv/post-cn-invoice!
             conn
             {:kontor.invoice/external-id "INV-G-MUN-1"
              :kontor.invoice/issue-date jan-15
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 10000M}]})
          r (ret/compute-return conn {:year 2026 :month 1
                                      :location-tier :municipal})]
      (is (money/equiv? (cny "91.00")  (:return/umct-payable r)))
      (is (money/equiv? (cny "39.00")  (:return/edu-surcharge-payable r)))
      (is (money/equiv? (cny "26.00")  (:return/local-edu-surcharge-payable r)))
      (is (money/equiv? (cny "156.00") (:return/total-surcharges r))))))

;; ============================================================================
;; Small-scale taxpayer — quarterly return
;; ============================================================================

(deftest small-scale-1pct-quarterly
  (testing "Small-scale taxpayer with 1% preferential, Q1 2026.
              CNY 50,000 net at 1% → output 500. Form line 1 carries
              the gross-receipts sales; line 2 carries the 1%-rate-
              reduction sub-line; line 16 carries the payable."
    (let [conn (bootstrap)
          _ (inv/post-cn-invoice!
             conn
             {:kontor.invoice/external-id "INV-SS-1"
              :kontor.invoice/issue-date jan-15
              :kontor.invoice/taxpayer-status :small-scale
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 50000M}]})
          ;; Substrate doesn't auto-aggregate small-scale per-rate
          ;; totals (default chart has no per-rate small-scale
          ;; revenue accounts); the consumer supplies them.
          r (ret/compute-return
             conn
             {:year 2026 :quarter 1
              :taxpayer-status :small-scale
              :sales-1pct (cny "50000.00")
              :output-1pct (cny "500.00")
              :compute-surcharges? false})]
      (is (= "VAT-PRC-SmallScale" (:return/form r)))
      (is (= :small-scale (:return/taxpayer-status r)))
      (is (= :quarterly (:kind (:return/period r))))
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "50000.00") (:2 lines))
            "Line 2 = 1% preferential sales")
        (is (money/equiv? (cny "500.00")   (:16 lines))
            "Line 16 = total payable")))))

(deftest small-scale-real-estate-5pct
  (testing "Small-scale taxpayer real-estate sale at 5% carve-out.
              CNY 100,000 net at 5% → output 5,000. Line 4 carries
              the 5% sales, line 16 the payable."
    (let [conn (bootstrap)
          r (ret/compute-return
             conn
             {:year 2026 :quarter 1
              :taxpayer-status :small-scale
              :sales-5pct (cny "100000.00")
              :output-5pct (cny "5000.00")
              :compute-surcharges? false})]
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "100000.00") (:4 lines)))
        (is (money/equiv? (cny "5000.00")   (:16 lines)))))))

(deftest small-scale-mixed-rates
  (testing "Small-scale taxpayer with all three rates inside Q1.
              1% sales 10,000 (output 100) + 3% sales 5,000 (output 150)
              + 5% real-estate 100,000 (output 5,000)
              = total payable 5,250."
    (let [conn (bootstrap)
          r (ret/compute-return
             conn
             {:year 2026 :quarter 1
              :taxpayer-status :small-scale
              :sales-3pct (cny "5000.00")
              :sales-1pct (cny "10000.00")
              :sales-5pct (cny "100000.00")
              :output-3pct (cny "150.00")
              :output-1pct (cny "100.00")
              :output-5pct (cny "5000.00")
              :compute-surcharges? false})]
      (let [lines (:return/lines r)]
        (is (money/equiv? (cny "5000.00")   (:1  lines)) "3% sales")
        (is (money/equiv? (cny "10000.00")  (:2  lines)) "1% sales")
        (is (money/equiv? (cny "100000.00") (:4  lines)) "5% sales")
        (is (money/equiv? (cny "5250.00")   (:16 lines))
            "Sum of per-rate output amounts")))))

;; ============================================================================
;; Validation
;; ============================================================================

(deftest invalid-taxpayer-status-throws
  (let [conn (bootstrap)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ret/compute-return conn {:year 2026 :month 1
                                           :taxpayer-status :bogus})))))

;; ============================================================================
;; Period detection — monthly vs quarterly
;; ============================================================================

(deftest period-kinds
  (testing "Monthly opts → :monthly period; quarterly → :quarterly"
    (let [conn (bootstrap)
          monthly (ret/compute-return conn {:year 2026 :month 1
                                            :compute-surcharges? false})
          quarterly (ret/compute-return conn {:year 2026 :quarter 1
                                              :taxpayer-status :small-scale
                                              :compute-surcharges? false})]
      (is (= :monthly   (:kind (:return/period monthly))))
      (is (= :quarterly (:kind (:return/period quarterly)))))))

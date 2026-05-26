(ns kontor.l10n-mx.returns-test
  "Tests for kontor.l10n-mx.returns — the monthly DPI aggregator.

   Scenario: a fixture month with several cash-sale invoices (IVA
   recognised immediately on 208.01 cobrado) plus a credit-sale
   invoice (IVA on 208.02 no cobrado, which the DPI must EXCLUDE).
   The seed verifies that the DPI reads only the cash-recognised
   leg of the chart's cobrado / no-cobrado split."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.chart :as chart]
            [kontor.l10n-mx.invoice :as inv]
            [kontor.l10n-mx.returns :as ret]
            [kontor.money :as money]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-10  #inst "2026-01-10T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")
(def feb-15  #inst "2026-02-15T00:00:00Z")
(def feb-17  #inst "2026-02-17T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- mxn [s] (money/money (bigdec s) :MXN))
(defn- ≈ [a b] (money/equiv? a b))

(defn- seed-january!
  "Post a month's invoice activity in Jan 2026:
     - Cash sale R$1000 @ 16% on Jan 10 → IVA 160 lands on 208.01 cobrado
     - Cash sale R$500 @ 8% (border)  on Jan 15 → IVA 40 cobrado
     - Cash sale food R$200 @ 0%       on Jan 20 → no IVA
     - CREDIT sale R$3000 @ 16% on Jan 20  → IVA 480 on 208.02
                                                NO COBRADO — DPI excludes

   Expected DPI aggregates (cash-recognised only):
     IVA cobrado 16%  = 160
     IVA cobrado 8%   = 40
     IVA cobrado 0%   = 0
     IVA cobrado total = 200
     Ingresos          = 1000 + 500 + 200 + 3000 = 4700
                          (revenue ALL counts; the cash-basis split is
                           about IVA recognition, not revenue
                           recognition)"
  [conn]
  (inv/post-mx-invoice!
   conn
   {:kontor.invoice/external-id "INV-CASH-16"
    :kontor.invoice/issue-date  jan-10
    :kontor.invoice/cash-sale?  true
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M}]})
  (inv/post-mx-invoice!
   conn
   {:kontor.invoice/external-id "INV-CASH-8"
    :kontor.invoice/issue-date  jan-15
    :kontor.invoice/cash-sale?  true
    :kontor.invoice/region      :border-norte
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 500M}]})
  (inv/post-mx-invoice!
   conn
   {:kontor.invoice/external-id "INV-CASH-0"
    :kontor.invoice/issue-date  jan-20
    :kontor.invoice/cash-sale?  true
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 200M
                     :kontor.invoice-line/tax-status :zero-rated}]})
  (inv/post-mx-invoice!
   conn
   {:kontor.invoice/external-id "INV-CREDIT-16"
    :kontor.invoice/issue-date  jan-20
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 3000M}]}))

;; ============================================================================
;; Period helpers
;; ============================================================================

(deftest period-bounds-monthly
  (testing "January 2026 monthly window."
    (let [{:keys [from to kind year month]} (ret/period-bounds {:year 2026 :month 1})]
      (is (= :monthly kind))
      (is (= 2026 year))
      (is (= 1 month))
      (is (= jan-1 from))
      (is (= feb-1 to))))
  (testing "December 2026 wraps to January 2027 correctly."
    (let [{:keys [from to]} (ret/period-bounds {:year 2026 :month 12})]
      (is (= #inst "2026-12-01T00:00:00Z" from))
      (is (= #inst "2027-01-01T00:00:00Z" to)))))

(deftest period-bounds-quarterly
  (testing "Q1 2026 → Jan 1 to Apr 1."
    (let [{:keys [from to kind]} (ret/period-bounds {:year 2026 :quarter 1})]
      (is (= :quarterly kind))
      (is (= jan-1 from))
      (is (= #inst "2026-04-01T00:00:00Z" to)))))

(deftest period-bounds-annual
  (testing "Annual 2026 → calendar year."
    (let [{:keys [from to kind]} (ret/period-bounds {:year 2026})]
      (is (= :annual kind))
      (is (= jan-1 from))
      (is (= #inst "2027-01-01T00:00:00Z" to)))))

(deftest period-bounds-missing-args
  (is (thrown? clojure.lang.ExceptionInfo
               (ret/period-bounds {}))))

(deftest dpi-due-date-17th-of-following-month
  (testing "DPI for January 2026 is due Feb 17, 2026 per Art. 5-D LIVA."
    (is (= feb-17 (ret/dpi-due-date {:year 2026 :month 1}))))
  (testing "DPI for December 2026 wraps: due Jan 17, 2027."
    (is (= #inst "2027-01-17T00:00:00Z"
           (ret/dpi-due-date {:year 2026 :month 12})))))

;; ============================================================================
;; DPI — cash-basis aggregation (THE key MX-specific behavior)
;; ============================================================================

(deftest dpi-aggregates-only-cobrado-side
  (testing "DPI aggregates the cobrado (cash-recognised) IVA only.
              The credit-sale invoice in the seed contributes 480 to
              208.02 (NO cobrado) — that MUST be EXCLUDED from the
              DPI total. Only the three cash-sale invoices
              contribute (160 @ 16% + 40 @ 8% + 0 @ 0% = 200)."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      (is (= "DPI" (:kontor.return/form r)))
      (is (≈ (mxn "160") (-> r :kontor.return/lines :iva-cobrado-16))
          "16% cash-sale produced 160 IVA on 208.01")
      (is (≈ (mxn "40")  (-> r :kontor.return/lines :iva-cobrado-8))
          "8% border cash-sale produced 40 IVA on 208.01.002")
      (is (≈ (mxn "0")   (-> r :kontor.return/lines :iva-cobrado-0))
          "0% cash-sale produced no IVA")
      (is (≈ (mxn "200") (-> r :kontor.return/lines :iva-cobrado-total))
          "Total IVA cobrado = 200 (EXCLUDES the 480 credit-sale IVA
           still in 208.02 no cobrado)"))))

(deftest dpi-iva-net-equals-payable
  (testing "With no input ITC posted (no purchases yet in fixture),
              IVA net = IVA cobrado total. This is the amount the
              supplier remits to SAT."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      (is (≈ (mxn "200") (:kontor.return/iva-net r))
          "Net = cobrado − acreditable = 200 − 0 = 200")
      (is (≈ (mxn "0")   (-> r :kontor.return/lines :iva-acreditable-total))
          "No input ITC in fixture")
      (is (≈ (mxn "200") (:kontor.return/total-iva-payable r))
          "Total payable = iva-net + retención-iva-net = 200 + 0"))))

(deftest dpi-ingresos-aggregate-all-revenue
  (testing "Revenue (ingresos) aggregates regardless of cash-basis
              IVA status. Credit-sale revenue COUNTS in ingresos even
              though its IVA is still on 208.02 — revenue recognition
              and IVA recognition are independent under MX rules
              (LISR Art. 17 vs LIVA Art. 1-B)."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      ;; Cash 1000 + Cash 500 + Cash 200 + Credit 3000 = 4700
      (is (≈ (mxn "4700") (-> r :kontor.return/lines :ingresos-total))
          "All four invoices contribute revenue, including the
           credit-sale whose IVA is still pending"))))

(deftest dpi-empty-period-returns-zeros
  (testing "No activity in the period → all aggregates zero, no NPE."
    (let [conn (bootstrap)
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      (is (≈ (mxn "0") (:kontor.return/iva-net r)))
      (is (≈ (mxn "0") (:kontor.return/ieps-net r)))
      (is (≈ (mxn "0") (:kontor.return/total-iva-payable r))))))

(deftest dpi-due-date-on-return
  (testing "DPI return-data carries the statutory filing due date
              for monthly periods. Annual / quarterly variants get
              nil due-date (the DPI is a monthly form)."
    (let [conn (bootstrap)
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      (is (= feb-17 (:kontor.return/due-date r))))))

;; ============================================================================
;; Period scoping — feb activity must NOT contribute to jan DPI
;; ============================================================================

(deftest dpi-period-scoping
  (testing "An invoice in Feb 2026 must NOT contribute to a Jan 2026
              DPI aggregation. The kontor.report engine uses the
              half-open window from period-bounds."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          _ (inv/post-mx-invoice!
             conn
             {:kontor.invoice/external-id "INV-FEB"
              :kontor.invoice/issue-date  feb-15
              :kontor.invoice/cash-sale?  true
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 5000M}]})
          jan (ret/generate-dpi-return conn {:year 2026 :month 1})
          feb (ret/generate-dpi-return conn {:year 2026 :month 2})]
      (is (≈ (mxn "200") (:kontor.return/iva-net jan))
          "January DPI unaffected by Feb activity")
      (is (≈ (mxn "800") (:kontor.return/iva-net feb))
          "Feb DPI captures the 5000 × 16% = 800"))))

;; ============================================================================
;; Explicit window
;; ============================================================================

(deftest dpi-explicit-window-overrides-shorthand
  (testing "Explicit :from / :to override the :year / :month shorthand."
    (let [conn (bootstrap)
          _ (seed-january! conn)
          r (ret/generate-dpi-return conn {:from jan-1 :to jan-20})]
      ;; Cash sales Jan 10 (160) + Jan 15 (40) = 200; Jan 20 (0%, no IVA)
      ;; is at the :to boundary which is exclusive ⇒ excluded.
      ;; And the credit-sale on Jan 20 is also excluded by the window.
      (is (≈ (mxn "200") (-> r :kontor.return/lines :iva-cobrado-total))
          "Half-open window excludes the Jan-20 entries"))))

;; ============================================================================
;; IEPS aggregation
;; ============================================================================

(deftest dpi-ieps-aggregation
  (testing "IEPS lines aggregate independently from IVA."
    (let [conn (bootstrap)
          _ (inv/post-mx-invoice!
             conn
             {:kontor.invoice/external-id "INV-IEPS"
              :kontor.invoice/issue-date jan-10
              :kontor.invoice/cash-sale? true
              :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                               :kontor.invoice-line/unit-price 1000M
                               :kontor.invoice-line/ieps-rate 0.265M}]})
          r (ret/generate-dpi-return conn {:year 2026 :month 1})]
      (is (≈ (mxn "265") (-> r :kontor.return/lines :ieps-cobrado))
          "IEPS 26.5% × 1000 = 265 on 209.01 cobrado")
      (is (≈ (mxn "265") (:kontor.return/ieps-net r))
          "IEPS net = IEPS cobrado − IEPS acreditable = 265 − 0"))))

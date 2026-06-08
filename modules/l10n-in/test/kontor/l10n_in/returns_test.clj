(ns kontor.l10n-in.returns-test
  "Tests for kontor.l10n-in.returns — GSTR-1 + GSTR-3B aggregations.

   Scenario: a fixture company in Maharashtra files for January 2026.
   Posts:
     - One intra-state B2B sale: MH → MH, 18%, ₹1000 net (CGST+SGST)
     - One inter-state B2B sale: MH → KA, 18%, ₹500 net (IGST)
     - One zero-rated export sale: ₹400 net
     - One exempt sale: ₹200 net
   Verifies the GSTR-1 per-head totals + GSTR-3B per-head net match
   what the posting builder emitted."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.chart :as chart]
            [kontor.l10n-in.invoice :as inv]
            [kontor.l10n-in.returns :as ret]
            [kontor.money :as money]
            [kontor.validation :as v]))

;; ============================================================================
;; Period helpers
;; ============================================================================

(def jan-1   #inst "2026-01-01T00:00:00Z")
(def jan-15  #inst "2026-01-15T00:00:00Z")
(def jan-20  #inst "2026-01-20T00:00:00Z")
(def jan-25  #inst "2026-01-25T00:00:00Z")
(def jan-30  #inst "2026-01-30T00:00:00Z")
(def feb-1   #inst "2026-02-01T00:00:00Z")

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- seed-month!
  "Post the four-invoice fixture covering all four GSTR-1 outward
   categories (intra-state taxable, inter-state taxable, zero-rated
   exports, exempt)."
  [conn]
  (inv/post-in-invoice!
   conn
   {:kontor.invoice/external-id "INV-INTRA-1"
    :kontor.invoice/issue-date jan-15
    :kontor.invoice/supplier-state "MH"
    :kontor.invoice/place-of-supply "MH"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 1000M
                     :kontor.invoice-line/tax-rate 0.18M}]})
  (inv/post-in-invoice!
   conn
   {:kontor.invoice/external-id "INV-INTER-1"
    :kontor.invoice/issue-date jan-20
    :kontor.invoice/supplier-state "MH"
    :kontor.invoice/place-of-supply "KA"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 500M
                     :kontor.invoice-line/tax-rate 0.18M}]})
  (inv/post-in-invoice!
   conn
   {:kontor.invoice/external-id "INV-EXP-1"
    :kontor.invoice/issue-date jan-25
    :kontor.invoice/supplier-state "MH"
    :kontor.invoice/place-of-supply "MH"
    :kontor.invoice/export? true
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 400M
                     :kontor.invoice-line/tax-status :non-resident}]})
  (inv/post-in-invoice!
   conn
   {:kontor.invoice/external-id "INV-EXEMPT-1"
    :kontor.invoice/issue-date jan-30
    :kontor.invoice/supplier-state "MH"
    :kontor.invoice/place-of-supply "MH"
    :kontor.invoice/lines [{:kontor.invoice-line/quantity 1
                     :kontor.invoice-line/unit-price 200M
                     :kontor.invoice-line/tax-status :exempt}]})
  nil)

;; ============================================================================
;; Period bounds helpers
;; ============================================================================

(deftest month-bounds-jan-2026
  (let [{:keys [from to kind year month]}
        (ret/month-bounds {:year 2026 :month 1})]
    (is (= :monthly kind))
    (is (= 2026 year))
    (is (= 1 month))
    (is (= jan-1 from))
    (is (= feb-1 to))))

(deftest quarter-bounds-q1-2026
  (let [{:keys [from to kind year quarter]}
        (ret/quarter-bounds {:year 2026 :quarter 1})]
    (is (= :quarterly kind))
    (is (= 2026 year))
    (is (= 1 quarter))
    (is (= jan-1 from))
    (is (= #inst "2026-04-01T00:00:00Z" to))))

(deftest gstr-1-due-date-monthly
  (testing "Monthly GSTR-1: 11th of next month."
    (is (= #inst "2026-02-11T00:00:00Z"
           (ret/gstr-1-due-date {:kind :monthly :year 2026 :month 1})))))

(deftest gstr-3b-due-date-monthly
  (testing "Monthly GSTR-3B: 20th of next month."
    (is (= #inst "2026-02-20T00:00:00Z"
           (ret/gstr-3b-due-date {:year 2026 :month 1})))))

(deftest gstr-3b-due-date-qrmp
  (testing "QRMP filer in state group A: 22nd of next month."
    (is (= #inst "2026-02-22T00:00:00Z"
           (ret/gstr-3b-due-date {:year 2026 :month 1
                                  :qrmp-state-group :A}))))
  (testing "QRMP filer in state group B: 24th of next month."
    (is (= #inst "2026-02-24T00:00:00Z"
           (ret/gstr-3b-due-date {:year 2026 :month 1
                                  :qrmp-state-group :B})))))

;; ============================================================================
;; GSTR-1 aggregation
;; ============================================================================

(deftest gstr-1-totals-cover-all-categories
  (testing "GSTR-1 for Jan 2026: per-head totals match what the
              posting builder emitted across the four invoices."
    (let [conn (bootstrap)
          _ (seed-month! conn)
          r (ret/generate-gstr-1 conn {:year 2026 :month 1})
          totals (:kontor.return/totals r)]
      (is (= "GSTR-1" (:kontor.return/form r)))
      ;; B2B taxable value: intra ₹1000 + inter ₹500 = ₹1500
      (is (money/equiv? (money/money "1500.00" :INR) (get totals "b2b-taxable-value"))
          "B2B = intra (₹1000) + inter (₹500)")
      ;; Exports: ₹400
      (is (money/equiv? (money/money "400.00" :INR) (get totals "exports-value"))
          "Exports posted to 410200 Sales:Export")
      ;; Exempt: ₹200
      (is (money/equiv? (money/money "200.00" :INR) (get totals "exempt-value"))
          "Exempt posted to 410300 Sales:Exempt")
      ;; CGST + SGST from intra only (18% × ₹1000 = ₹180 split half each)
      (is (money/equiv? (money/money "90.00" :INR) (get totals "cgst")))
      (is (money/equiv? (money/money "90.00" :INR) (get totals "sgst")))
      ;; IGST from inter (18% × ₹500 = ₹90)
      (is (money/equiv? (money/money "90.00" :INR) (get totals "igst")))
      ;; No UTGST or Cess in this fixture
      (is (money/equiv? (money/zero :INR) (get totals "utgst")))
      (is (money/equiv? (money/zero :INR) (get totals "cess"))))))

(deftest gstr-1-due-date-attached
  (let [conn (bootstrap)
        _ (seed-month! conn)
        r (ret/generate-gstr-1 conn {:year 2026 :month 1})]
    (is (= #inst "2026-02-11T00:00:00Z" (:kontor.return/due-date r))
        "Monthly GSTR-1 due 11 Feb 2026")))

;; ============================================================================
;; GSTR-3B aggregation + per-head net tax
;; ============================================================================

(deftest gstr-3b-output-totals
  (testing "GSTR-3B output-side totals match GSTR-1 (this fixture has
              no inward postings yet)."
    (let [conn (bootstrap)
          _ (seed-month! conn)
          r (ret/generate-gstr-3b conn {:year 2026 :month 1})
          totals (:kontor.return/totals r)]
      (is (= "GSTR-3B" (:kontor.return/form r)))
      ;; Outward — taxable value: ₹1500 (B2B intra + inter)
      ;; Note: kontor.reporting.report `:tax-tags` engine doesn't dedupe across
      ;; tags, so a line carrying BOTH :in-gstr3b-outward-taxable and
      ;; :in-gstr1-b2b-sales gets counted once per query. Outward-
      ;; taxable here = ₹1000 + ₹500 = ₹1500.
      (is (money/equiv? (money/money "1500.00" :INR)
                        (get totals "outward-taxable")))
      (is (money/equiv? (money/money "400.00" :INR)
                        (get totals "outward-zero-rated")))
      (is (money/equiv? (money/money "200.00" :INR)
                        (get totals "outward-exempt")))
      ;; Output GST per head
      (is (money/equiv? (money/money "90.00" :INR) (get totals "output-cgst")))
      (is (money/equiv? (money/money "90.00" :INR) (get totals "output-sgst")))
      (is (money/equiv? (money/money "90.00" :INR) (get totals "output-igst"))))))

(deftest gstr-3b-net-tax-per-head
  (testing "Net tax per head with no ITC = output (since RCM is also
              zero in this fixture). Total = ₹270 = 90 (CGST) + 90
              (SGST) + 90 (IGST)."
    (let [conn (bootstrap)
          _ (seed-month! conn)
          r (ret/generate-gstr-3b conn {:year 2026 :month 1})
          {:keys [cgst sgst igst utgst cess]} (:kontor.return/net-tax r)]
      (is (money/equiv? (money/money "90.00" :INR) cgst))
      (is (money/equiv? (money/money "90.00" :INR) sgst))
      (is (money/equiv? (money/money "90.00" :INR) igst))
      (is (money/equiv? (money/zero :INR) utgst))
      (is (money/equiv? (money/zero :INR) cess))
      (is (money/equiv? (money/money "270.00" :INR) (:kontor.return/net-total r))
          "Net tax total = sum across heads"))))

(deftest gstr-3b-due-date-attached
  (let [conn (bootstrap)
        _ (seed-month! conn)
        r (ret/generate-gstr-3b conn {:year 2026 :month 1})]
    (is (= #inst "2026-02-20T00:00:00Z" (:kontor.return/due-date r))
        "Monthly GSTR-3B due 20 Feb 2026")))

;; ============================================================================
;; Period bounds — quarterly (QRMP) cadence still aggregates correctly
;; ============================================================================

(deftest gstr-1-quarterly-aggregation
  (testing "QRMP cadence: Q1 2026 captures Jan-Mar postings. Our
              fixture has January only, so totals match the monthly run."
    (let [conn (bootstrap)
          _ (seed-month! conn)
          r (ret/generate-gstr-1 conn {:year 2026 :quarter 1})
          totals (:kontor.return/totals r)]
      (is (= :quarterly (-> r :kontor.return/period :kind)))
      (is (money/equiv? (money/money "1500.00" :INR) (get totals "b2b-taxable-value")))
      (is (money/equiv? (money/money "90.00" :INR) (get totals "cgst")))
      (is (money/equiv? (money/money "90.00" :INR) (get totals "igst")))
      (is (= #inst "2026-04-13T00:00:00Z" (:kontor.return/due-date r))
          "Quarterly GSTR-1 (QRMP) due 13th of month following the quarter"))))

;; ============================================================================
;; Empty period — nil-return
;; ============================================================================

(deftest empty-period-yields-zero-totals
  (testing "Bootstrap + no invoices → GSTR-3B with all zero totals."
    (let [conn (bootstrap)
          r (ret/generate-gstr-3b conn {:year 2026 :month 1})
          totals (:kontor.return/totals r)
          {:keys [cgst sgst igst]} (:kontor.return/net-tax r)]
      (is (money/equiv? (money/zero :INR) (get totals "output-cgst")))
      (is (money/equiv? (money/zero :INR) (get totals "output-sgst")))
      (is (money/equiv? (money/zero :INR) (get totals "output-igst")))
      (is (money/equiv? (money/zero :INR) cgst))
      (is (money/equiv? (money/zero :INR) sgst))
      (is (money/equiv? (money/zero :INR) igst))
      (is (money/equiv? (money/zero :INR) (:kontor.return/net-total r))))))

;; ============================================================================
;; Missing-period opts → throws
;; ============================================================================

(deftest missing-period-throws
  (let [conn (bootstrap)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Missing period"
         (ret/generate-gstr-1 conn {})))))

;; ============================================================================
;; Explicit from/to opts pass through
;; ============================================================================

(deftest explicit-from-to-bypasses-month-resolver
  (testing "Caller passes :from + :to directly; period :kind is
              whatever they supply or nil."
    (let [conn (bootstrap)
          _ (seed-month! conn)
          r (ret/generate-gstr-1 conn {:from jan-1 :to feb-1})
          totals (:kontor.return/totals r)]
      ;; Same totals as the :year+:month run
      (is (money/equiv? (money/money "1500.00" :INR)
                        (get totals "b2b-taxable-value"))))))

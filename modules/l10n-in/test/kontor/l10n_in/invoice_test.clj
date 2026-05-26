(ns kontor.l10n-in.invoice-test
  "Tests for kontor.l10n-in.invoice — the posting builder that
   translates an Indian invoice into kernel transaction + posting
   tx-data.

   Test scenarios cover the operationally-distinct cases:
     - intra-state B2B (MH supplier → MH POS, non-UT) → CGST + SGST
     - inter-state B2B (MH supplier → KA POS)         → IGST
     - UT supply (CH supplier → CH POS, UT no leg.)   → CGST + UTGST
     - reverse-charge B2B (notified supply)           → no tax on seller side
     - export (zero-rated)                            → revenue only
     - compensation cess (aerated drinks 12% on top)
     - cash sale (debit cash not AR)

   Assertion: every posted invoice sums to zero (kernel invariant)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.chart :as chart]
            [kontor.l10n-in.invoice :as inv]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-15 #inst "2026-01-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:kontor.journal/code "INV"
                       :kontor.journal/name "Sales"
                       :kontor.journal/type :sale
                       :kontor.journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- posting-amounts
  "Return all posting amounts (BigDecimal) for the given account code."
  [db code]
  (let [a (ace db code)]
    (when a
      (d/q '[:find [?amt ...]
             :in $ ?a
             :where
             [?p :kontor.posting/account ?a]
             [?p :kontor.posting/amount ?amt]]
           db a))))

(defn- sum-account [db code]
  (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
            (.add acc x))
          0M (posting-amounts db code)))

(defn- assert-sum-to-zero [conn label]
  (let [db (d/db conn)
        pairs (d/q '[:find ?p ?amt
                     :where [?p :kontor.posting/amount ?amt]]
                   db)
        total (reduce (fn [^java.math.BigDecimal acc [_ ^java.math.BigDecimal x]]
                        (.add acc x))
                      0M pairs)]
    (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
        (str label " — postings must sum to zero, got " total))))

;; ============================================================================
;; Intra-state — CGST + SGST
;; ============================================================================

(deftest intra-state-cgst-sgst-invoice-posts
  (testing "MH→MH (intra-state), 18% headline, ₹1000 net →
              CGST 90 + SGST 90 → gross 1180.
              Dr AR 1180, Cr Sales 1000, Cr CGST 90, Cr SGST 90."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-INTRA-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "MH"
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.18M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1180M (sum-account db chart/ar-code))
            "AR debited gross 1180")
        (is (= -1000M (sum-account db chart/sales-domestic-code))
            "Sales credited 1000")
        (is (= -90M (sum-account db chart/output-cgst-code))
            "Output CGST 90")
        (is (= -90M (sum-account db chart/output-sgst-code))
            "Output SGST 90")
        (is (zero? (.compareTo 0M (sum-account db chart/output-igst-code)))
            "No IGST on intra-state supply"))
      (assert-sum-to-zero conn "intra-state CGST+SGST"))))

;; ============================================================================
;; Inter-state — IGST only
;; ============================================================================

(deftest inter-state-igst-invoice-posts
  (testing "MH→KA (inter-state), 18%, ₹1000 net → IGST 180 → gross 1180.
              Dr AR 1180, Cr Sales 1000, Cr IGST 180."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-INTER-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "KA"
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.18M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1180M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-code)))
        (is (= -180M (sum-account db chart/output-igst-code)))
        (is (zero? (.compareTo 0M (sum-account db chart/output-cgst-code)))
            "No CGST on inter-state supply")
        (is (zero? (.compareTo 0M (sum-account db chart/output-sgst-code)))
            "No SGST on inter-state supply"))
      (assert-sum-to-zero conn "inter-state IGST"))))

;; ============================================================================
;; UT supply — CGST + UTGST
;; ============================================================================

(deftest ut-supply-cgst-utgst-invoice-posts
  (testing "CH→CH (Chandigarh, UT without legislature), 18%, ₹1000 net
              → CGST 90 + UTGST 90 → gross 1180."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-UT-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "CH"
                   :invoice/place-of-supply "CH"
                   :invoice/place-of-supply-is-ut? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.18M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1180M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-code)))
        (is (= -90M (sum-account db chart/output-cgst-code))
            "Output CGST half of headline")
        (is (= -90M (sum-account db chart/output-utgst-code))
            "Output UTGST replaces SGST in UT-without-legislature")
        (is (zero? (.compareTo 0M (sum-account db chart/output-sgst-code)))
            "No SGST in a UT supply")
        (is (zero? (.compareTo 0M (sum-account db chart/output-igst-code)))))
      (assert-sum-to-zero conn "UT supply CGST+UTGST"))))

;; ============================================================================
;; Reverse Charge — no output tax on seller side
;; ============================================================================

(deftest reverse-charge-no-output-tax
  (testing "RCM B2B supply (notified service): supplier posts revenue
              only — receivable = net, no output-tax postings (the
              buyer pays GST direct to govt and self-invoices)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-RCM-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "KA"
                   :invoice/reverse-charge? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.18M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db chart/ar-code))
            "AR = net only (no tax under RCM)")
        (is (= -1000M (sum-account db chart/sales-domestic-code)))
        (is (zero? (.compareTo 0M (sum-account db chart/output-cgst-code)))
            "No CGST on RCM supply")
        (is (zero? (.compareTo 0M (sum-account db chart/output-sgst-code)))
            "No SGST")
        (is (zero? (.compareTo 0M (sum-account db chart/output-igst-code)))
            "No IGST"))
      (assert-sum-to-zero conn "reverse-charge"))))

;; ============================================================================
;; Export — zero-rated, no tax, lands on Sales:Export
;; ============================================================================

(deftest export-zero-rated-no-tax
  (testing "Export sale (out-of-country, zero-rated): revenue lands
              on 410200 Sales — Exports, no tax postings."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "MH"
                   :invoice/export? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :non-resident}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db chart/ar-export-code))
            "AR — Export debited net only")
        (is (= -1000M (sum-account db chart/sales-export-code))
            "Sales — Exports revenue")
        (is (zero? (.compareTo 0M (sum-account db chart/sales-domestic-code)))
            "No domestic sales posting"))
      (assert-sum-to-zero conn "export zero-rated"))))

;; ============================================================================
;; Compensation Cess
;; ============================================================================

(deftest compensation-cess-luxury-line
  (testing "Aerated drinks at 40% luxury + 12% Compensation Cess,
              inter-state, ₹1000 net → IGST 400 + Cess 120 → gross 1520."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CESS-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "KA"
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.40M
                     :invoice-line/cess-rate 0.12M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1520M (sum-account db chart/ar-code)))
        (is (= -1000M (sum-account db chart/sales-domestic-code)))
        (is (= -400M (sum-account db chart/output-igst-code)))
        (is (= -120M (sum-account db chart/output-cess-code))
            "Output Cess 12% on top of headline"))
      (assert-sum-to-zero conn "compensation cess"))))

;; ============================================================================
;; Cash sale
;; ============================================================================

(deftest cash-sale-debits-cash
  (testing "`:invoice/cash-sale? true` → debit cash (122100) not AR."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "MH"
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M
                     :invoice-line/tax-rate 0.18M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 118M (sum-account db chart/cash-code))
            "Cash 118 (100 net + 9 CGST + 9 SGST)")
        (is (zero? (.compareTo 0M (sum-account db chart/ar-code)))
            "AR untouched on a cash sale"))
      (assert-sum-to-zero conn "cash sale"))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-in-invoice-tx-data-pure
  (testing "plan-in-invoice-tx-data returns tx-data WITHOUT touching
              the DB. Suitable for kontor.process composition."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "MH"
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M
                     :invoice-line/tax-rate 0.18M}]}
          tx-data (inv/plan-in-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :kontor.posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → all required fields flagged."
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 5))))
  (testing "Complete invoice → no complaints."
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/supplier-state "MH"
                  :invoice/place-of-supply "MH"
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M
                                   :invoice-line/tax-rate 0.18M}]})))))

;; ============================================================================
;; Multi-line invoice — multiple components in one tx
;; ============================================================================

(deftest multi-line-invoice-sums-tax-components
  (testing "Two intra-state lines, different rates: 18% on ₹1000 +
              5% on ₹500. CGST = 90 + 12.50 = 102.50; SGST same."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-MULTI-1"
                   :invoice/issue-date jan-15
                   :invoice/supplier-state "MH"
                   :invoice/place-of-supply "MH"
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-rate 0.18M}
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-rate 0.05M}]}]
      (inv/post-in-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; AR = 1500 + 102.50 + 102.50 = 1705
        (is (= 1705.00M (sum-account db chart/ar-code)))
        (is (= -1500M (sum-account db chart/sales-domestic-code)))
        (is (= -102.50M (sum-account db chart/output-cgst-code)))
        (is (= -102.50M (sum-account db chart/output-sgst-code))))
      (assert-sum-to-zero conn "multi-line"))))

;; ============================================================================
;; Missing required-field errors throw at plan time
;; ============================================================================

(deftest missing-supplier-state-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":invoice/supplier-state"
         (inv/plan-in-invoice-tx-data
          db {:invoice/external-id "X"
              :invoice/issue-date jan-15
              :invoice/place-of-supply "MH"
              :invoice/lines [{:invoice-line/quantity 1
                               :invoice-line/unit-price 100M
                               :invoice-line/tax-rate 0.18M}]}
          {})))))

(deftest missing-place-of-supply-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":invoice/place-of-supply"
         (inv/plan-in-invoice-tx-data
          db {:invoice/external-id "X"
              :invoice/issue-date jan-15
              :invoice/supplier-state "MH"
              :invoice/lines [{:invoice-line/quantity 1
                               :invoice-line/unit-price 100M
                               :invoice-line/tax-rate 0.18M}]}
          {})))))

(deftest missing-tax-rate-on-taxable-line-throws
  (let [conn (bootstrap)
        db (d/db conn)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":invoice-line/tax-rate"
         (inv/plan-in-invoice-tx-data
          db {:invoice/external-id "X"
              :invoice/issue-date jan-15
              :invoice/supplier-state "MH"
              :invoice/place-of-supply "MH"
              :invoice/lines [{:invoice-line/quantity 1
                               :invoice-line/unit-price 100M}]}
          {})))))

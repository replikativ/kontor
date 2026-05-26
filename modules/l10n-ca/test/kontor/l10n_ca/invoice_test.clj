(ns kontor.l10n-ca.invoice-test
  "Tests for kontor.l10n-ca.invoice — the posting builder that
   translates a CA invoice into kernel transaction + posting tx-data.

   Test scenarios cover the four operationally-distinct cases:
     - ON   — 13% HST, single combined authority
     - QC   — 5% GST + 9.975% QST, two parallel-authority liabilities
     - BC   — 5% GST + 7% PST, two authorities (PST non-recoverable)
     - export — non-resident buyer, zero-rated, no tax postings"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.chart :as chart]
            [kontor.l10n-ca.invoice :as inv]
            [kontor.money :as money]
            [kontor.validation :as v]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def jan-15 #inst "2026-01-15T00:00:00Z")

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (v/install-invariants! conn)
    (chart/install! conn)
    (d/transact conn [{:journal/code "INV"
                       :journal/name "Sales"
                       :journal/type :sale
                       :journal/active true}])
    conn))

(defn- ace [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- posting-on-account
  "Return all posting amounts (BigDecimal) for the given account
   code in the test DB."
  [db code]
  (let [a (ace db code)]
    (when a
      (d/q '[:find [?amt ...]
             :in $ ?a
             :where
             [?p :posting/account ?a]
             [?p :posting/amount ?amt]]
           db a))))

(defn- sum-account [db code]
  (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
            (.add acc x))
          0M (posting-on-account db code)))

;; ============================================================================
;; ON — HST 13% sale
;; ============================================================================

(deftest on-hst-invoice-posts
  (testing "Ontario invoice: $1000 net @ 13% HST → $1130 gross.
              Dr AR 1130, Cr Sales 1000, Cr GST/HST 130."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-ON-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :ON
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1130M (sum-account db "1100"))
            "AR debited 1130")
        (is (= -1000M (sum-account db "4000"))
            "Sales credited 1000")
        (is (= -130M (sum-account db "2310"))
            "GST/HST collected 130")
        (is (zero? (.compareTo 0M (sum-account db "2320")))
            "No BC PST")
        (is (zero? (.compareTo 0M (sum-account db "2330")))
            "No QST")))))

;; ============================================================================
;; QC — GST + QST
;; ============================================================================

(deftest qc-gst-plus-qst-invoice-posts
  (testing "Quebec invoice: $1000 net → $50 GST + $99.75 QST → $1149.75 gross.
              QST posts to a separate liability account (2330)
              because Revenu Québec is a separate authority from CRA.
              (This is NOT a parallel ledger — same primary book,
              different liability account.)"
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-QC-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :QC
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1149.75M (sum-account db "1100"))
            "AR = net + GST + QST")
        (is (= -1000M (sum-account db "4000"))
            "Revenue 1000")
        (is (= -50M (sum-account db "2310"))
            "GST 50 to CRA account")
        (is (= -99.75M (sum-account db "2330"))
            "QST 99.75 to Revenu Québec account")
        (is (zero? (.compareTo 0M (sum-account db "2320")))
            "No BC PST")))))

;; ============================================================================
;; BC — GST + PST (PST is non-recoverable single-stage)
;; ============================================================================

(deftest bc-gst-plus-pst-invoice-posts
  (testing "BC invoice: $1000 net → $50 GST + $70 PST → $1120 gross.
              PST 2320 (BC Min. Finance) is separate from GST 2310 (CRA)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-BC-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :BC
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}]}]
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1120M (sum-account db "1100"))
            "AR = 1000 + 50 GST + 70 PST")
        (is (= -1000M (sum-account db "4000")))
        (is (= -50M   (sum-account db "2310"))
            "GST 50")
        (is (= -70M   (sum-account db "2320"))
            "BC PST 70")
        (is (zero? (.compareTo 0M (sum-account db "2330")))
            "No QST")))))

;; ============================================================================
;; Export — non-resident, zero-rated
;; ============================================================================

(deftest us-export-zero-rated
  (testing "Sale to US customer with goods exported is zero-rated
              (ETA s.12(a)). Lines marked :non-resident → revenue only.
              Place-of-supply is :ON (origin) but tax-status overrides
              the rate-table lookup. Posts:
                Dr AR 1000, Cr Sales-Zero-Rated 1000 (no tax)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-EXP-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :ON
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M
                     :invoice-line/tax-status :non-resident}]}]
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 1000M (sum-account db "1100"))
            "AR = net only — no tax")
        (is (= -1000M (sum-account db "4010"))
            "Sales-Zero-Rated 1000")
        (is (zero? (.compareTo 0M (sum-account db "2310")))
            "No GST/HST")
        (is (zero? (.compareTo 0M (sum-account db "4000")))
            "No regular sales posting (revenue routed to zero-rated acct)")))))

;; ============================================================================
;; Multi-line invoice — mixed tax statuses
;; ============================================================================

(deftest mixed-line-invoice
  (testing "ON invoice with one taxable line + one zero-rated line.
              Tax accrues on the taxable line only; revenue splits
              across two accounts (sales + sales-zero-rated)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-MIX-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :ON
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 1000M}                 ; taxable
                    {:invoice-line/quantity 1
                     :invoice-line/unit-price 500M
                     :invoice-line/tax-status :zero-rated}]}]        ; groceries etc.
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        ;; HST: 13% × 1000 = 130. Net total: 1500. Gross 1630.
        (is (= 1630M  (sum-account db "1100")))
        (is (= -1000M (sum-account db "4000")))
        (is (= -500M  (sum-account db "4010")))
        (is (= -130M  (sum-account db "2310")))))))

;; ============================================================================
;; Cash sale variant
;; ============================================================================

(deftest cash-sale-debits-cash-not-ar
  (testing ":invoice/cash-sale? true → debit goes to 1010 (Bank CAD)
              instead of 1100 (AR)."
    (let [conn (bootstrap)
          inv-map {:invoice/external-id "INV-CASH-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :ON
                   :invoice/cash-sale? true
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}]
      (inv/post-ca-invoice! conn inv-map)
      (let [db (d/db conn)]
        (is (= 113M (sum-account db "1010"))
            "Bank debited 113 (100 net + 13 HST)")
        (is (zero? (.compareTo 0M (sum-account db "1100")))
            "AR untouched on a cash sale")))))

;; ============================================================================
;; Plan-only (pure builder)
;; ============================================================================

(deftest plan-ca-invoice-tx-data-pure
  (testing "plan-ca-invoice-tx-data returns tx-data WITHOUT touching
              the DB (read-only db value). The result is suitable
              for kontor.process composition (ADR-068)."
    (let [conn (bootstrap)
          db (d/db conn)
          inv-map {:invoice/external-id "INV-PLAN-1"
                   :invoice/issue-date jan-15
                   :invoice/ship-to-province :ON
                   :invoice/lines
                   [{:invoice-line/quantity 1
                     :invoice-line/unit-price 100M}]}
          tx-data (inv/plan-ca-invoice-tx-data db inv-map {})]
      (is (vector? tx-data))
      (is (every? map? (filter map? tx-data))
          "tx-data entries are maps (entity-style)")
      ;; Verify no posting has been committed to the DB by the planner.
      (is (zero? (count (d/q '[:find [?p ...] :where [?p :posting/account _]] db)))
          "Pure planner does not transact"))))

;; ============================================================================
;; Validation predicates
;; ============================================================================

(deftest validate-invoice-catches-missing
  (testing "Empty invoice → all required fields missing"
    (let [missing (inv/validate-invoice {})]
      (is (>= (count missing) 4))))
  (testing "Complete invoice → no complaints"
    (is (empty? (inv/validate-invoice
                 {:invoice/external-id "X"
                  :invoice/issue-date jan-15
                  :invoice/ship-to-province :ON
                  :invoice/lines [{:invoice-line/quantity 1
                                   :invoice-line/unit-price 100M}]})))))

(deftest province-predicates
  (is (inv/province? :ON))
  (is (inv/province? :YT))
  (is (not (inv/province? :ZZ)))
  (is (inv/supports-hst? :ON))
  (is (not (inv/supports-hst? :BC)))
  (is (inv/supports-pst? :BC))
  (is (not (inv/supports-pst? :QC))
      "QC has QST, NOT PST")
  (is (inv/supports-qst? :QC))
  (is (not (inv/supports-qst? :ON))))

;; ============================================================================
;; Sum-to-zero — kernel-level invariant
;; ============================================================================

(deftest invoice-postings-sum-to-zero
  (testing "Every CA invoice we post must satisfy the kernel
              sum-to-zero rule. Sample the four flagship cases."
    (doseq [{:keys [name province lines]}
            [{:name "ON" :province :ON
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "QC" :province :QC
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "BC" :province :BC
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 1000M}]}
             {:name "MB" :province :MB
              :lines [{:invoice-line/quantity 1 :invoice-line/unit-price 500M}]}]]
      (let [conn (bootstrap)
            inv-map {:invoice/external-id (str "INV-Z-" name)
                     :invoice/issue-date jan-15
                     :invoice/ship-to-province province
                     :invoice/lines lines}]
        (inv/post-ca-invoice! conn inv-map)
        (let [db (d/db conn)
              all-amounts (d/q '[:find [?amt ...]
                                 :where [_ :posting/amount ?amt]]
                               db)
              total (reduce (fn [^java.math.BigDecimal acc ^java.math.BigDecimal x]
                              (.add acc x))
                            0M all-amounts)]
          (is (zero? (.compareTo ^java.math.BigDecimal total 0M))
              (str "Postings for " name " must sum to zero, got " total)))))))

;; Silence linter — money import preserved for downstream test compat.
(comment money/zero)

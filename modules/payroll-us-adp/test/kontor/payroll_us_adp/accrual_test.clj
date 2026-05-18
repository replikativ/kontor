(ns kontor.payroll-us-adp.accrual-test
  "Stage R C3 — ASC 710 PTO + 401(k) match accrual tests (ADR-077).

   Per note 83 §6 / ADR-021 parallel-ledger split:
     - ASC 710 PTO accrual lands ONLY on :us-gaap (IRC §461(h)
       economic-performance test blocks tax-side accrual until the
       absence is taken).
     - 401(k) employer match accrual lands ONLY on :us-gaap at pay-
       period close; tax-ledger recognition is a late-cycle adjustment
       under IRC §404(a)(6) deemed-made-prior-year rules — the consumer
       drives that via `tax-recognize-401k-match-tx-data`.

   Sign convention test: +amount = Dr expense / Cr liability (PTO
   earned), -amount reverses (over-estimate clawback)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.payroll-us-adp.accrual :as accrual])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (d/transact conn
                [{:db/id "usd" :commodity/symbol "USD" :commodity/precision 2}
                 {:db/id "ent-us" :entity/code "US-LLC" :entity/name "Acme US LLC"
                  :entity/kind :operating}
                 ;; Two ledgers — book + tax — for the parallel-ledger split.
                 {:db/id "us-gaap" :ledger/code "us-gaap"
                  :ledger/name "US GAAP (book)" :ledger/framework :us-gaap
                  :ledger/active true}
                 {:db/id "us-tax" :ledger/code "us-tax"
                  :ledger/name "US Federal Tax basis" :ledger/framework :us-tax
                  :ledger/active true}
                 ;; CoA — minimal payroll chart.
                 {:db/id "acct-pto-expense" :account/code "5040"
                  :account/name "PTO Expense" :account/type :expense
                  :account/active true}
                 {:db/id "acct-pto-accrual" :account/code "2290"
                  :account/name "PTO Accrual (current)"
                  :account/type :liability :account/active true}
                 {:db/id "acct-match-expense" :account/code "5310"
                  :account/name "401(k) Match Expense"
                  :account/type :expense :account/active true}
                 {:db/id "acct-match-payable" :account/code "2210"
                  :account/name "401(k) Match Payable"
                  :account/type :liability :account/active true}
                 {:db/id "journal-payroll" :journal/code "PAY-US"
                  :journal/name "Payroll (US)" :journal/type :general}
                 {:db/id "period-2026-04" :period/name "2026-04"
                  :period/start #inst "2026-04-01"
                  :period/end #inst "2026-05-01"}])
    conn))

(defn- by-code [db ident code]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db ident code))

;; ============================================================================
;; HALF-EVEN rounding (W-2 reconciliation default — note 83 §1 bullet 10)
;; ============================================================================

(deftest half-even-rounding
  (testing "round-half-even resolves the 0.005 case bankers'-style"
    ;; 2.125 → 2.12 (round-down because preceding digit is even).
    (is (= 2.12M (accrual/round-half-even 2.125M)))
    ;; 2.135 → 2.14 (round-up because preceding digit is odd).
    (is (= 2.14M (accrual/round-half-even 2.135M)))
    ;; Whole numbers + cents pass through.
    (is (= 100.50M (accrual/round-half-even 100.50M)))
    ;; Negative accruals (over-estimate clawback) round correctly.
    (is (= -2.12M (accrual/round-half-even -2.125M)))))

;; ============================================================================
;; ASC 710 PTO accrual — book-ledger only
;; ============================================================================

(deftest asc-710-pto-tx-data-builds-balanced-tx
  (let [conn (bootstrap)
        db (d/db conn)
        pto-exp (by-code db :account/code "5040")
        pto-acc (by-code db :account/code "2290")
        gaap (by-code db :ledger/code "us-gaap")
        usd  (by-code db :commodity/symbol "USD")
        journal (by-code db :journal/code "PAY-US")
        tx-data (accrual/asc-710-pto-accrual-tx-data
                 {:pto-expense-account pto-exp
                  :pto-accrual-account pto-acc
                  :amount 1250.00M
                  :commodity usd
                  :ledger gaap
                  :journal journal
                  :effective-date #inst "2026-04-30"
                  :tx-code "PTO-ACCR-2026-04"})
        postings (filter #(some? (:posting/account %)) tx-data)
        sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                      (.add a ^BigDecimal amount))
                    0M postings)]
    (testing "produces two postings"
      (is (= 2 (count postings))))
    (testing "balances per-ledger to zero"
      (is (zero? (.signum sum))))
    (testing "Dr expense + Cr liability"
      (let [expense-leg (first (filter #(= pto-exp (:posting/account %)) postings))
            liability-leg (first (filter #(= pto-acc (:posting/account %)) postings))]
        (is (= 1250.00M (:posting/amount expense-leg)))
        (is (= -1250.00M (:posting/amount liability-leg)))))
    (testing "both legs ride on :us-gaap (not :us-tax — IRC §461(h))"
      (doseq [p postings]
        (is (= gaap (:posting/ledger p)))))))

(deftest asc-710-pto-tx-data-rejects-missing-fields
  (testing "missing :amount throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"amount required"
                          (accrual/asc-710-pto-accrual-tx-data
                           {:pto-expense-account 1 :pto-accrual-account 2
                            :commodity 3 :ledger 4 :journal 5
                            :effective-date #inst "2026-04-30"
                            :tx-code "X"}))))
  (testing "missing :pto-accrual-account throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pto-accrual-account required"
                          (accrual/asc-710-pto-accrual-tx-data
                           {:pto-expense-account 1 :amount 100M
                            :commodity 3 :ledger 4 :journal 5
                            :effective-date #inst "2026-04-30"
                            :tx-code "X"})))))

(deftest asc-710-pto-accrual-bang-routes-through-validation
  (let [conn (bootstrap)
        db (d/db conn)
        pto-exp (by-code db :account/code "5040")
        pto-acc (by-code db :account/code "2290")
        gaap (by-code db :ledger/code "us-gaap")
        usd  (by-code db :commodity/symbol "USD")
        journal (by-code db :journal/code "PAY-US")
        report (accrual/asc-710-pto-accrual!
                conn
                {:pto-expense-account pto-exp
                 :pto-accrual-account pto-acc
                 :amount 800.00M
                 :commodity usd :ledger gaap :journal journal
                 :effective-date #inst "2026-04-30"
                 :tx-code "PTO-ACCR-LIVE"})]
    (testing "transacts successfully"
      (is (some? (:db-after report))))
    (testing "the transaction is queryable"
      (let [db' (:db-after report)
            tx-eid (d/q '[:find ?t .
                          :in $ ?c
                          :where [?t :transaction/external-id ?c]]
                        db' "PTO-ACCR-LIVE")]
        (is (some? tx-eid))))))

;; ============================================================================
;; 401(k) employer match accrual — book ledger
;; ============================================================================

(deftest er-401k-match-accrual-book-only
  (let [conn (bootstrap)
        db (d/db conn)
        match-exp (by-code db :account/code "5310")
        match-pay (by-code db :account/code "2210")
        gaap (by-code db :ledger/code "us-gaap")
        usd  (by-code db :commodity/symbol "USD")
        journal (by-code db :journal/code "PAY-US")
        tx-data (accrual/er-401k-match-accrual-tx-data
                 {:match-expense-account match-exp
                  :match-payable-account match-pay
                  :amount 425.00M
                  :commodity usd :ledger gaap :journal journal
                  :effective-date #inst "2026-04-30"
                  :tx-code "401K-MATCH-2026-04"})
        postings (filter #(some? (:posting/account %)) tx-data)]
    (testing "book-only: both legs on :us-gaap"
      (is (every? #(= gaap (:posting/ledger %)) postings)))
    (testing "Dr 5310 expense / Cr 2210 payable"
      (let [exp-leg (first (filter #(= match-exp (:posting/account %)) postings))
            pay-leg (first (filter #(= match-pay (:posting/account %)) postings))]
        (is (= 425.00M (:posting/amount exp-leg)))
        (is (= -425.00M (:posting/amount pay-leg)))))))

;; ============================================================================
;; Tax-ledger recognition of 401(k) match (IRC §404(a)(6))
;; ============================================================================

(deftest tax-recognize-401k-match-lands-on-tax-ledger
  ;; Per note 83 §6.2: when the consumer determines IRC §404(a)(6)
  ;; conditions are met (plan-document specific), the tax-ledger leg
  ;; mirrors the book accrual onto :us-tax. The substrate doesn't
  ;; make the call — kontor records it.
  (let [conn (bootstrap)
        db (d/db conn)
        match-exp (by-code db :account/code "5310")
        match-pay (by-code db :account/code "2210")
        tax (by-code db :ledger/code "us-tax")
        usd  (by-code db :commodity/symbol "USD")
        journal (by-code db :journal/code "PAY-US")
        tx-data (accrual/tax-recognize-401k-match-tx-data
                 {:match-expense-account match-exp
                  :match-payable-account match-pay
                  :amount 425.00M
                  :commodity usd :tax-ledger tax :journal journal
                  :effective-date #inst "2026-12-31"
                  :tx-code "401K-MATCH-TAX-2026"})
        postings (filter #(some? (:posting/account %)) tx-data)]
    (testing "both legs land on :us-tax"
      (is (every? #(= tax (:posting/ledger %)) postings)))
    (testing "balances per-(ledger, commodity)"
      (let [sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))))

;; ============================================================================
;; Sign convention — negative delta reverses the accrual
;; ============================================================================

(deftest negative-amount-reverses-accrual
  ;; PTO over-accrued in Q1 — Q2 trues up with -delta. Dr liability /
  ;; Cr expense.
  (let [tx-data (accrual/asc-710-pto-accrual-tx-data
                 {:pto-expense-account 100 :pto-accrual-account 200
                  :amount -300.00M
                  :commodity 300 :ledger 400 :journal 500
                  :effective-date #inst "2026-06-30"
                  :tx-code "PTO-TRUEUP-Q2"})
        postings (filter #(some? (:posting/account %)) tx-data)
        exp-leg (first (filter #(= 100 (:posting/account %)) postings))
        liability-leg (first (filter #(= 200 (:posting/account %)) postings))]
    (testing "expense leg has -delta"
      (is (= -300.00M (:posting/amount exp-leg))))
    (testing "liability leg has +delta (reduces the liability)"
      (is (= 300.00M (:posting/amount liability-leg))))))

(ns kontor.payroll-jp.accrual-test
  "Tests for the JP-specific accrual primitives:
     - 賞与引当金 (bonus accrual)
     - 4-bucket statutory-SI employer-side accrual"
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.accrual :as accrual]))

;; ============================================================================
;; bonus-accrual-amount helper
;; ============================================================================

(deftest bonus-accrual-amount-spreads-target-over-cycle
  (testing "Standard semi-annual cycle: annual 1.8M target ÷ 6 periods = 300k/mo"
    (is (= 300000M
           (accrual/bonus-accrual-amount
            {:annual-bonus-target 1800000M
             :periods-in-cycle 6}))))
  (testing "True-up delta is added on top of the per-period base"
    (is (= 350000M
           (accrual/bonus-accrual-amount
            {:annual-bonus-target 1800000M
             :periods-in-cycle 6
             :true-up-delta 50000M}))))
  (testing "Rounds HALF-EVEN to whole yen"
    ;; 1000001 / 3 = 333333.6667 → 333334 (banker)
    (is (= 333334M
           (accrual/bonus-accrual-amount
            {:annual-bonus-target 1000001M
             :periods-in-cycle 3})))))

(deftest bonus-accrual-amount-fails-on-zero-cycle
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":periods-in-cycle cannot be zero"
                        (accrual/bonus-accrual-amount
                         {:annual-bonus-target 1000000M
                          :periods-in-cycle 0}))))

;; ============================================================================
;; bonus-accrual-tx-data
;; ============================================================================

(deftest bonus-accrual-tx-data-balances
  (let [tx (accrual/bonus-accrual-tx-data
            {:bonus-accrual-expense-account :acct/bonus-accrual-exp
             :bonus-accrual-liability-account :acct/bonus-accrual-liab
             :amount 300000M
             :commodity :kontor.commodity/jpy
             :journal :kontor.journal/payroll
             :effective-date #inst "2026-05-31"
             :tx-code "BONUS-ACC-2026-05"})
        postings (filter #(contains? % :kontor.posting/account) tx)]
    (testing "Two posting legs (expense + liability)"
      (is (= 2 (count postings))))
    (testing "Sum to zero"
      (let [sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))))
    (testing "Expense leg is positive (DR)"
      (let [exp (first (filter #(= :acct/bonus-accrual-exp (:kontor.posting/account %)) postings))]
        (is (= 300000M (:kontor.posting/amount exp)))))
    (testing "Liability leg is negative (CR)"
      (let [liab (first (filter #(= :acct/bonus-accrual-liab (:kontor.posting/account %)) postings))]
        (is (= -300000M (:kontor.posting/amount liab)))))))

(deftest bonus-accrual-rounds-fractional
  (let [tx (accrual/bonus-accrual-tx-data
            {:bonus-accrual-expense-account :acct/bonus-acc
             :bonus-accrual-liability-account :acct/bonus-liab
             :amount 333333.6667M
             :commodity :kontor.commodity/jpy
             :journal :kontor.journal/payroll
             :effective-date #inst "2026-05-31"
             :tx-code "BONUS-FRAC-2026-05"})
        postings (filter #(contains? % :kontor.posting/account) tx)
        sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (testing "Each leg rounded to whole yen"
      (is (every? (fn [p]
                    (zero? (.compareTo
                            ^java.math.BigDecimal (:kontor.posting/amount p)
                            (.setScale ^java.math.BigDecimal
                             (:kontor.posting/amount p) 0))))
                  postings)))
    (testing "Sum still zero after rounding"
      (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))))

(deftest bonus-accrual-rejects-missing-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"required"
                        (accrual/bonus-accrual-tx-data
                         {:bonus-accrual-expense-account :acct/exp
                          ;; missing :bonus-accrual-liability-account
                          :amount 300000M
                          :commodity :kontor.commodity/jpy
                          :journal :kontor.journal/payroll
                          :effective-date #inst "2026-05-31"
                          :tx-code "X"}))))

;; ============================================================================
;; 4-bucket SI accrual primitives
;; ============================================================================

(defn- si-tx [accrual-fn]
  (accrual-fn
   {:si-expense-account :acct/er-si
    :si-liability-account :acct/si-liab
    :amount 25000M
    :commodity :kontor.commodity/jpy
    :journal :kontor.journal/payroll
    :effective-date #inst "2026-05-31"
    :tx-code "SI-TEST"}))

(deftest health-insurance-accrual-balances
  (let [tx (si-tx accrual/health-insurance-accrual-tx-data)
        postings (filter #(contains? % :kontor.posting/account) tx)
        sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))
    (testing "Narration includes 健康保険料"
      (is (some #(re-find #"健康保険料" (:kontor.posting/narration %)) postings)))))

(deftest pension-accrual-balances
  (let [tx (si-tx accrual/pension-accrual-tx-data)
        postings (filter #(contains? % :kontor.posting/account) tx)]
    (testing "Two legs that sum to zero"
      (is (= 2 (count postings)))
      (is (zero? (.compareTo
                  ^java.math.BigDecimal
                  (reduce (fn [a {:kontor.posting/keys [amount]}]
                            (.add ^java.math.BigDecimal a
                                  ^java.math.BigDecimal amount))
                          0M postings) 0M))))
    (testing "Narration includes 厚生年金保険料"
      (is (some #(re-find #"厚生年金保険料" (:kontor.posting/narration %)) postings)))))

(deftest employment-insurance-accrual-balances
  (let [tx (si-tx accrual/employment-insurance-accrual-tx-data)
        postings (filter #(contains? % :kontor.posting/account) tx)]
    (testing "Two legs"
      (is (= 2 (count postings))))
    (testing "Narration includes 雇用保険料"
      (is (some #(re-find #"雇用保険料" (:kontor.posting/narration %)) postings)))))

(deftest long-term-care-accrual-balances
  (let [tx (si-tx accrual/long-term-care-accrual-tx-data)
        postings (filter #(contains? % :kontor.posting/account) tx)]
    (testing "Two legs"
      (is (= 2 (count postings))))
    (testing "Narration includes 介護保険料"
      (is (some #(re-find #"介護保険料" (:kontor.posting/narration %)) postings)))))

(deftest accrual-rejects-missing-amount
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"required"
                        (accrual/health-insurance-accrual-tx-data
                         {:si-expense-account :acct/er-si
                          :si-liability-account :acct/si-liab
                          ;; missing :amount
                          :commodity :kontor.commodity/jpy
                          :journal :kontor.journal/payroll
                          :effective-date #inst "2026-05-31"
                          :tx-code "X"}))))

(deftest accrual-honors-ledger-stamp
  (let [tx (accrual/pension-accrual-tx-data
            {:si-expense-account :acct/er-si
             :si-liability-account :acct/si-liab
             :amount 25000M
             :commodity :kontor.commodity/jpy
             :journal :kontor.journal/payroll
             :effective-date #inst "2026-05-31"
             :tx-code "SI-TEST"
             :ledger :kontor.ledger/jp-jgaap})
        postings (filter #(contains? % :kontor.posting/account) tx)]
    (testing "Every leg carries the supplied :ledger"
      (is (every? #(= :kontor.ledger/jp-jgaap (:kontor.posting/ledger %)) postings)))))

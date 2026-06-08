(ns kontor.payroll-cn.accrual-test
  "Unit tests for annual-bonus accrual (ADR)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.accrual :as accrual])
  (:import [java.math BigDecimal]))

(deftest one-twelfth-rounds-half-even
  (testing "exact division"
    (is (= 1000.00M (accrual/one-twelfth 12000M))))
  (testing "non-exact division — bankers rounding"
    ;; 10000 / 12 = 833.333... → 833.33
    (is (= 833.33M (accrual/one-twelfth 10000M)))))

(deftest accrual-tx-data-shape
  (let [tx (accrual/annual-bonus-accrual-tx-data
            {:wage-expense-account :acct-5603
             :bonus-payable-account :acct-2211-bonus
             :amount 1000.00M
             :commodity :cny
             :ledger :cn-cas-book
             :journal :journal-payroll
             :effective-date #inst "2026-04-30"
             :tx-code "ACR-CN-2026-04-001"})]
    (testing "tx-data is a vector"
      (is (vector? tx)))
    (testing "contains a :transaction map"
      (is (some #(and (map? %) (contains? % :kontor.transaction/external-id)) tx)))
    (testing "contains exactly two postings"
      (let [postings (filter #(and (map? %) (contains? % :kontor.posting/account)) tx)]
        (is (= 2 (count postings)))))
    (testing "postings sum to zero per (ledger, commodity)"
      (let [postings (filter #(and (map? %) (contains? % :kontor.posting/account)) tx)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))))

(deftest accrual-uses-half-even-rounding
  (let [tx (accrual/annual-bonus-accrual-tx-data
            {:wage-expense-account :acct-5603
             :bonus-payable-account :acct-2211-bonus
             :amount 833.336M ; → 833.34 with HALF_EVEN
             :commodity :cny
             :ledger :cn-cas-book
             :journal :journal-payroll
             :effective-date #inst "2026-04-30"
             :tx-code "ACR-CN-2026-04-002"})
        postings (filter #(and (map? %) (contains? % :kontor.posting/account)) tx)
        dr (first (filter #(= :acct-5603 (:kontor.posting/account %)) postings))
        cr (first (filter #(= :acct-2211-bonus (:kontor.posting/account %)) postings))]
    (is (= 833.34M (:kontor.posting/amount dr)))
    (is (= -833.34M (:kontor.posting/amount cr)))))

(deftest accrual-rejects-missing-fields
  (testing "missing :amount"
    (is (thrown? clojure.lang.ExceptionInfo
                 (accrual/annual-bonus-accrual-tx-data
                  {:wage-expense-account :acct-5603
                   :bonus-payable-account :acct-2211-bonus
                   :commodity :cny
                   :ledger :cn-cas-book
                   :journal :journal-payroll
                   :effective-date #inst "2026-04-30"
                   :tx-code "ACR-CN-2026-04-003"}))))
  (testing "missing :tx-code"
    (is (thrown? clojure.lang.ExceptionInfo
                 (accrual/annual-bonus-accrual-tx-data
                  {:wage-expense-account :acct-5603
                   :bonus-payable-account :acct-2211-bonus
                   :amount 1000M
                   :commodity :cny
                   :ledger :cn-cas-book
                   :journal :journal-payroll
                   :effective-date #inst "2026-04-30"})))))

(deftest accrual-negative-amount-reverses
  (let [tx (accrual/annual-bonus-accrual-tx-data
            {:wage-expense-account :acct-5603
             :bonus-payable-account :acct-2211-bonus
             :amount -500.00M
             :commodity :cny
             :ledger :cn-cas-book
             :journal :journal-payroll
             :effective-date #inst "2026-04-30"
             :tx-code "ACR-CN-REV-001"})
        postings (filter #(and (map? %) (contains? % :kontor.posting/account)) tx)
        dr (first (filter #(= :acct-5603 (:kontor.posting/account %)) postings))
        cr (first (filter #(= :acct-2211-bonus (:kontor.posting/account %)) postings))]
    (is (= -500.00M (:kontor.posting/amount dr)))
    (is (= 500.00M (:kontor.posting/amount cr)))))

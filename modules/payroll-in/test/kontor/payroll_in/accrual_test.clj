(ns kontor.payroll-in.accrual-test
  "Accrual builder tests — pure tx-data shape verification."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.accrual :as accrual]))

(def common-opts
  {:commodity :INR
   :ledger :ifrs
   :journal :pay-journal
   :effective-date #inst "2026-04-30"
   :tx-code "TX-ACC-001"})

(deftest bonus-accrual-tx-data-balanced
  (let [tx-data (accrual/bonus-accrual-tx-data
                 (merge common-opts
                        {:bonus-expense-account :exp
                         :bonus-payable-account :pay
                         :amount 5000M}))
        postings (filter :kontor.posting/account tx-data)]
    (testing "Two posting legs"
      (is (= 2 (count postings))))
    (testing "Dr expense"
      (let [exp (first (filter #(= :exp (:kontor.posting/account %)) postings))]
        (is (= 5000M (:kontor.posting/amount exp)))))
    (testing "Cr liability"
      (let [pay (first (filter #(= :pay (:kontor.posting/account %)) postings))]
        (is (= -5000M (:kontor.posting/amount pay)))))
    (testing "Sums to zero"
      (let [sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum ^java.math.BigDecimal sum)))))))

(deftest bonus-accrual-half-even-rounding
  (let [tx-data (accrual/bonus-accrual-tx-data
                 (merge common-opts
                        {:bonus-expense-account :exp
                         :bonus-payable-account :pay
                         :amount 1234.5678M}))
        postings (filter :kontor.posting/account tx-data)]
    (testing "HALF-EVEN rounding to 2 decimals"
      ;; 1234.5678 with HALF-EVEN → 1234.57
      (let [exp (first (filter #(= :exp (:kontor.posting/account %)) postings))]
        (is (= 1234.57M (:kontor.posting/amount exp)))))))

(deftest leave-encashment-accrual-symmetric
  (let [tx-data (accrual/leave-encashment-accrual-tx-data
                 (merge common-opts
                        {:leave-expense-account :exp
                         :leave-liability-account :pay
                         :amount 12345M
                         :tx-code "TX-LEAVE-001"}))
        postings (filter :kontor.posting/account tx-data)]
    (is (= 2 (count postings)))
    (let [sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                        (.add ^java.math.BigDecimal a
                              ^java.math.BigDecimal amount))
                      0M postings)]
      (is (zero? (.signum ^java.math.BigDecimal sum))))))

(deftest gratuity-accrual-tx-data
  (let [tx-data (accrual/gratuity-accrual-tx-data
                 (merge common-opts
                        {:gratuity-expense-account :exp
                         :gratuity-liability-account :pay
                         :amount 50000M
                         :tx-code "TX-GR-001"
                         :audit-doc-code "ACT-VAL-2026-Q1"}))
        postings (filter :kontor.posting/account tx-data)
        tx-row (first (filter :kontor.transaction/external-id tx-data))]
    (testing "Standard accrual shape"
      (is (= 2 (count postings))))
    (testing "Audit-doc-code surfaces in transaction/source for trace"
      (is (re-find #"audit-doc:ACT-VAL-2026-Q1"
                   (or (:kontor.transaction/source tx-row) ""))))))

(deftest required-keys-fail-loud
  (testing "Missing :amount throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"amount required"
                          (accrual/bonus-accrual-tx-data
                           (merge common-opts
                                  {:bonus-expense-account :exp
                                   :bonus-payable-account :pay})))))
  (testing "Missing :ledger throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"ledger required"
                          (accrual/bonus-accrual-tx-data
                           (-> common-opts
                               (dissoc :ledger)
                               (merge {:bonus-expense-account :exp
                                       :bonus-payable-account :pay
                                       :amount 100M})))))))

(deftest negative-amount-reverses-accrual
  (let [tx-data (accrual/bonus-accrual-tx-data
                 (merge common-opts
                        {:bonus-expense-account :exp
                         :bonus-payable-account :pay
                         :amount -2000M}))
        postings (filter :kontor.posting/account tx-data)
        exp (first (filter #(= :exp (:kontor.posting/account %)) postings))
        pay (first (filter #(= :pay (:kontor.posting/account %)) postings))]
    (testing "Expense leg flips to credit"
      (is (= -2000M (:kontor.posting/amount exp))))
    (testing "Liability leg flips to debit"
      (is (= 2000M (:kontor.posting/amount pay))))))

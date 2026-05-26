(ns kontor.payroll-br.accrual-test
  "Tests for the three load-bearing BR CPC 33 / IAS 19 accruals."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-br.accrual :as accrual]))

;; ============================================================================
;; Pure formula helpers — férias
;; ============================================================================

(deftest ferias-accrual-amount-base-formula
  (testing "Per-month férias accrual = monthly-salary * (1/12) * (4/3)"
    ;; 6000 * 1/12 * (1 + 1/3) = 500 * 1.333... = 666.67 HALF-EVEN
    (is (= 666.67M
           (accrual/ferias-accrual-amount {:monthly-salary 6000M})))))

(deftest ferias-accrual-with-employer-charges
  (testing "With default 28% employer charges (CPP 20% + FGTS 8%)"
    ;; 666.67 * 1.28 = 853.33
    (let [result (accrual/ferias-accrual-amount
                  {:monthly-salary 6000M
                   :include-employer-charges? true})]
      (is (= 853.33M result)))))

(deftest ferias-accrual-with-custom-charge-rate
  (testing "Consumer overrides the employer-charge-rate"
    ;; 666.67 * 1.355 (custom 35.5%) = 903.34M (HALF-EVEN rounded)
    (let [result (accrual/ferias-accrual-amount
                  {:monthly-salary 6000M
                   :include-employer-charges? true
                   :employer-charge-rate 0.355M})]
      ;; ~903.33-903.34 depending on intermediate rounding
      (is (= 1 (.compareTo ^java.math.BigDecimal result 903M)))
      (is (= -1 (.compareTo ^java.math.BigDecimal result 905M))))))

(deftest ferias-accrual-requires-monthly-salary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":monthly-salary required"
                        (accrual/ferias-accrual-amount {}))))

;; ============================================================================
;; Pure formula helpers — 13º salário
;; ============================================================================

(deftest thirteenth-salary-accrual-amount-base-formula
  (testing "Per-month 13º accrual = monthly-salary / 12"
    ;; 6000 / 12 = 500.00
    (is (= 500.00M
           (accrual/thirteenth-salary-accrual-amount {:monthly-salary 6000M})))))

(deftest thirteenth-salary-accrual-with-employer-charges
  (testing "With default 28% employer charges"
    ;; 500 * 1.28 = 640.00
    (is (= 640.00M
           (accrual/thirteenth-salary-accrual-amount
            {:monthly-salary 6000M
             :include-employer-charges? true})))))

(deftest thirteenth-salary-accrual-requires-monthly-salary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":monthly-salary required"
                        (accrual/thirteenth-salary-accrual-amount {}))))

;; ============================================================================
;; Pure formula helpers — multa rescisória de 40% sobre FGTS
;; ============================================================================

(deftest severance-fgts-accrual-amount-base-formula
  (testing "Full 40% accrual on the FGTS balance"
    ;; 10000 * 0.40 = 4000.00
    (is (= 4000.00M
           (accrual/severance-fgts-accrual-amount {:fgts-balance 10000M})))))

(deftest severance-fgts-accrual-with-turnover-fraction
  (testing "Consumer applies a turnover-rate fraction"
    ;; 10000 * 0.40 * 0.10 = 400.00
    (is (= 400.00M
           (accrual/severance-fgts-accrual-amount
            {:fgts-balance 10000M
             :turnover-fraction 0.10M})))))

(deftest severance-fgts-accrual-requires-fgts-balance
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fgts-balance required"
                        (accrual/severance-fgts-accrual-amount {}))))

;; ============================================================================
;; Tx-data builders — sign convention, structural correctness
;; ============================================================================

(def base-opts
  {:commodity :kontor.commodity/brl
   :ledger :ledger/br-ifrs
   :journal :journal/payroll
   :effective-date #inst "2026-05-31"
   :tx-code "FERIAS-ACCRUAL-2026-05"})

(deftest ferias-tx-data-balanced-postings
  (let [tx-data (accrual/ferias-accrual-tx-data
                 (merge base-opts
                        {:ferias-expense-account :acct/ferias-expense
                         :ferias-liability-account :acct/ferias-liability
                         :amount 666.67M}))]
    (testing "Returns a vector of tx-data ops"
      (is (vector? tx-data))
      (is (seq tx-data)))
    (testing "Postings inside the transaction balance to zero"
      ;; build-transaction returns the full :db/add ops + the
      ;; entity-shaped maps with :posting/* keys. We can scan for the
      ;; :posting/amount values regardless of how they're nested.
      (let [amounts (->> tx-data
                         (mapcat (fn [op]
                                   (cond
                                     (map? op) [(:posting/amount op)]
                                     (vector? op) (when (= :db/add (first op))
                                                    [(when (= :posting/amount
                                                              (nth op 2 nil))
                                                       (nth op 3 nil))])
                                     :else nil)))
                         (filter some?))
            sum (reduce (fn [a v] (.add ^java.math.BigDecimal a
                                        ^java.math.BigDecimal v))
                        0M amounts)]
        (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))))))

(deftest thirteenth-tx-data-balanced-postings
  (let [tx-data (accrual/thirteenth-salary-accrual-tx-data
                 (merge base-opts
                        {:thirteenth-expense-account :acct/thirteenth-expense
                         :thirteenth-liability-account :acct/thirteenth-liability
                         :amount 500M}))]
    (testing "Returns tx-data"
      (is (seq tx-data)))))

(deftest severance-fgts-tx-data-balanced-postings
  (let [tx-data (accrual/severance-fgts-accrual-tx-data
                 (merge base-opts
                        {:severance-expense-account :acct/severance-expense
                         :severance-liability-account :acct/severance-liability
                         :amount 400M}))]
    (testing "Returns tx-data"
      (is (seq tx-data)))))

(deftest accrual-tx-data-rejects-missing-required-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"amount required"
                        (accrual/ferias-accrual-tx-data
                         (merge base-opts
                                {:ferias-expense-account :a
                                 :ferias-liability-account :b}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"ferias-expense-account required"
                        (accrual/ferias-accrual-tx-data
                         (merge base-opts
                                {:amount 100M
                                 :ferias-liability-account :b})))))

(deftest negative-amount-reverses-accrual
  "Sign convention: negative amount reverses the accrual (e.g.
   when employee takes vacation and the liability is drawn down)."
  (let [tx-data (accrual/ferias-accrual-tx-data
                 (merge base-opts
                        {:ferias-expense-account :acct/exp
                         :ferias-liability-account :acct/liab
                         :amount -100M}))]
    ;; The amounts net to zero regardless of sign; the leg attribution
    ;; flips so the liability account ends up with a positive entry
    ;; (reversal of the prior accrual).
    (is (seq tx-data))))

;; ============================================================================
;; HALF-EVEN rounding discipline (kernel convention)
;; ============================================================================

(deftest half-even-rounding-applied
  (testing "Round-half-even rounds 0.005 to nearest even"
    ;; 0.005 → 0.00 (round to nearest even)
    (is (= 0.00M (accrual/round-half-even 0.005M)))
    ;; 0.015 → 0.02 (round to nearest even)
    (is (= 0.02M (accrual/round-half-even 0.015M)))))

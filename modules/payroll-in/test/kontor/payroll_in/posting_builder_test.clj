(ns kontor.payroll-in.posting-builder-test
  "Posting-builder tests — verify balanced output + per-state PT
   routing + employer-side leg duplication."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-in.posting-builder :as pb]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

(defn- sum-amounts ^BigDecimal [postings]
  (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
            (.add a ^BigDecimal amount))
          0M postings))

;; Stand-in account refs. The builder doesn't care what shape the
;; values are — they're passed through to :kontor.posting/account — so for
;; unit tests we use keyword stand-ins.
(def accounts
  {:in-payroll-salaries-wages    :acct/wages
   :in-payroll-bonus             :acct/bonus
   :in-payroll-bonus-accrual     :acct/bonus-accrual
   :in-payroll-bonus-payable     :acct/bonus-payable
   :in-payroll-leave-accrual     :acct/leave-accrual
   :in-payroll-leave-liability   :acct/leave-liability
   :in-payroll-gratuity-paid     :acct/gratuity-paid
   :in-payroll-gratuity-accrual  :acct/gratuity-accrual
   :in-payroll-gratuity-liability :acct/gratuity-liability
   :in-payroll-er-pf             :acct/er-pf
   :in-payroll-er-esi            :acct/er-esi
   :in-payroll-tds-payable       :acct/tds
   :in-payroll-pf-payable        :acct/pf
   :in-payroll-esi-payable       :acct/esi
   :in-payroll-pt-payable        :acct/pt
   :in-payroll-net-wages         :acct/net
   :in-payroll-other-deduction   :acct/voluntary
   :in-payroll-loan-recovery     :acct/loan
   :in-payroll-garnishment       :acct/garnishment})

(def simple-fact
  "One Maharashtra employee. Gross 75k, deductions 6k, net 69k.
   Plus PF-employer 1800 (balanced)."
  {:employment 1
   :gross 75000M
   :net 69000M
   :components [{:kind :basic-salary       :amount 50000M :employer-side? false}
                {:kind :dearness-allowance :amount 5000M  :employer-side? false}
                {:kind :house-rent-allowance :amount 20000M :employer-side? false}
                {:kind :tds              :amount -4000M :employer-side? false}
                {:kind :pf-employee      :amount -1800M :employer-side? false}
                {:kind :professional-tax :amount -200M  :employer-side? false}
                {:kind :pf-employer      :amount 1800M  :employer-side? true}]
   :jurisdiction-specific-codes {:province-of-employment "IN-MH"}})

(deftest single-fact-postings-sum-to-zero
  (let [postings (pb/build-payroll-postings
                  {:facts [simple-fact]
                   :accounts accounts
                   :commodity :INR})]
    (testing "Posting set sums to zero (substrate invariant)"
      (is (zero? (.signum (sum-amounts postings)))))
    (testing "Wages-expense leg gets positive amount (Dr gross)"
      (let [wages (filter #(= :acct/wages (:kontor.posting/account %)) postings)]
        (is (= 1 (count wages)))
        (is (= 75000M (:kontor.posting/amount (first wages))))))
    (testing "TDS deduction lands on TDS-payable as credit (negative)"
      (let [tds (filter #(= :acct/tds (:kontor.posting/account %)) postings)]
        (is (= 1 (count tds)))
        (is (= -4000M (:kontor.posting/amount (first tds))))))
    (testing "Employer PF produces TWO legs (Dr expense, Cr payable)"
      (let [er-pf-exp (filter #(= :acct/er-pf (:kontor.posting/account %)) postings)
            er-pf-pay (filter #(= :acct/pf (:kontor.posting/account %)) postings)]
        (is (= 1 (count er-pf-exp)))
        ;; PF payable: employee -1800 + employer payable -1800 = -3600
        (is (= 2 (count er-pf-pay)))
        (is (= 1800M (:kontor.posting/amount (first er-pf-exp))))
        (is (= -3600M (sum-amounts er-pf-pay)))))
    (testing "Net wages payable = 69000 (negative leg)"
      (let [net (filter #(= :acct/net (:kontor.posting/account %)) postings)]
        (is (= 1 (count net)))
        (is (= -69000M (:kontor.posting/amount (first net))))))))

(deftest pt-posting-carries-state-distribution
  (let [postings (pb/build-payroll-postings
                  {:facts [simple-fact]
                   :accounts accounts
                   :commodity :INR})
        pt-posting (first (filter #(= :acct/pt (:kontor.posting/account %)) postings))]
    (testing "PT posting present"
      (is (some? pt-posting)))
    (testing "PT carries analytic-distribution on in-state plan"
      (is (seq (:kontor.posting/analytic-distributions pt-posting)))
      (let [d (first (:kontor.posting/analytic-distributions pt-posting))]
        (is (= [:analytic-plan/code "in-state"]
               (:analytic-distribution/plan d)))
        (is (= [:analytic-account/path "in-state:IN-MH"]
               (:analytic-distribution/account d)))
        (is (= 100M (:analytic-distribution/percent d)))))))

(deftest wages-expense-carries-state-distribution
  (let [postings (pb/build-payroll-postings
                  {:facts [simple-fact]
                   :accounts accounts
                   :commodity :INR})
        wages (first (filter #(= :acct/wages (:kontor.posting/account %)) postings))]
    (testing "Wages-expense carries analytic-distribution on in-state plan"
      (is (seq (:kontor.posting/analytic-distributions wages))))))

(deftest multi-state-allocation-overrides-fact-state
  (let [postings (pb/build-payroll-postings
                  {:facts [simple-fact]
                   :accounts accounts
                   :commodity :INR
                   :state-allocations {1 {"IN-MH" 60M "IN-KA" 40M}}})
        wages (first (filter #(= :acct/wages (:kontor.posting/account %)) postings))
        dists (:kontor.posting/analytic-distributions wages)]
    (testing "Two state distributions emitted"
      (is (= 2 (count dists))))
    (testing "Both states appear"
      (let [paths (set (map :analytic-distribution/account dists))]
        (is (contains? paths [:analytic-account/path "in-state:IN-MH"]))
        (is (contains? paths [:analytic-account/path "in-state:IN-KA"]))))
    (testing "Percentages match allocation"
      (let [by-path (into {}
                          (map (fn [d]
                                 [(:analytic-distribution/account d)
                                  (:analytic-distribution/percent d)])
                               dists))]
        (is (= 60M (by-path [:analytic-account/path "in-state:IN-MH"])))
        (is (= 40M (by-path [:analytic-account/path "in-state:IN-KA"])))))))

(deftest missing-account-tag-throws
  (testing "Builder fails loud when consumer's :accounts map missing a tag"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No account configured for tag"
                          (pb/build-payroll-postings
                           {:facts [simple-fact]
                            :accounts (dissoc accounts :in-payroll-tds-payable)
                            :commodity :INR})))))

(deftest builder-record-satisfies-protocol
  (let [b (pb/->InPayrollPostingBuilder {:commodity :INR})
        postings (pp/build-postings b [simple-fact]
                                    {:accounts accounts})]
    (is (zero? (.signum (sum-amounts postings))))))

(deftest builder-with-ledger-stamps-every-leg
  (let [b (pb/->InPayrollPostingBuilder {:commodity :INR})
        postings (pp/build-postings b [simple-fact]
                                    {:accounts accounts
                                     :ledger :ifrs})]
    (testing "Every posting carries the ledger ref"
      (is (every? #(= :ifrs (:kontor.posting/ledger %)) postings)))))

(deftest pf-employer-split-into-eps-and-epf-balances
  (let [fact (assoc simple-fact
                    :components
                    [{:kind :basic-salary       :amount 50000M :employer-side? false}
                     {:kind :dearness-allowance :amount 5000M  :employer-side? false}
                     {:kind :house-rent-allowance :amount 20000M :employer-side? false}
                     {:kind :tds              :amount -4000M :employer-side? false}
                     {:kind :pf-employee      :amount -1800M :employer-side? false}
                     {:kind :professional-tax :amount -200M  :employer-side? false}
                     ;; EPS: 8.33% capped (engine pre-computed = 1250 here)
                     {:kind :pf-employer-eps :amount 1250M :employer-side? true}
                     ;; EPF: balance (engine pre-computed = 550 here)
                     {:kind :pf-employer-epf :amount 550M  :employer-side? true}
                     {:kind :pf-employer-edli :amount 75M  :employer-side? true}])
        postings (pb/build-payroll-postings
                  {:facts [fact]
                   :accounts accounts
                   :commodity :INR})]
    (testing "Posting set sums to zero (with EPS + EPF + EDLI split)"
      (is (zero? (.signum (sum-amounts postings)))))
    (testing "All three employer-side splits route to same payable bucket"
      (let [pf-pay (filter #(= :acct/pf (:kontor.posting/account %)) postings)]
        ;; employee -1800 + employer-eps -1250 + employer-epf -550 + edli -75 = -3675
        (is (= -3675M (sum-amounts pf-pay)))))))

(deftest in-band-accrual-component-routes-to-accrual-account
  (let [fact-with-bonus-accrual
        {:employment 2
         :gross 30000M
         :net 26000M
         :components [{:kind :basic-salary :amount 30000M :employer-side? false}
                      {:kind :tds          :amount -4000M :employer-side? false}
                      {:kind :bonus-accrual :amount 2500M :employer-side? true}]
         :jurisdiction-specific-codes {:province-of-employment "IN-DL"}}
        postings (pb/build-payroll-postings
                  {:facts [fact-with-bonus-accrual]
                   :accounts accounts
                   :commodity :INR})
        bonus-acc-exp (first (filter #(= :acct/bonus-accrual (:kontor.posting/account %)) postings))
        bonus-pay (first (filter #(= :acct/bonus-payable (:kontor.posting/account %)) postings))]
    (testing "Bonus accrual produces Dr expense + Cr payable"
      (is (some? bonus-acc-exp))
      (is (some? bonus-pay))
      (is (= 2500M (:kontor.posting/amount bonus-acc-exp)))
      (is (= -2500M (:kontor.posting/amount bonus-pay))))
    (testing "Set still balances"
      (is (zero? (.signum (sum-amounts postings)))))
    (testing "Delhi employee — no PT routing (UP/Delhi/Haryana etc. don't levy PT)"
      ;; The fact doesn't have a :professional-tax component, so the
      ;; PT-payable account isn't touched. Sanity check.
      (let [pt-posting (filter #(= :acct/pt (:kontor.posting/account %)) postings)]
        (is (empty? pt-posting))))))

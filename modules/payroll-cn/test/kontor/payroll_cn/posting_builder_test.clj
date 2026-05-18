(ns kontor.payroll-cn.posting-builder-test
  "Unit tests for CnPayrollPostingBuilder (ADR-085 / note 87 §4 + §6)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-cn.posting-builder :as pb]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

(def accounts
  "Test stand-in for the consumer's :accounts map. Each value is a
   sentinel keyword we can match against in the posting output."
  {:cn-payroll-wages-expense   :acct-wages-exp
   :cn-payroll-er-si-expense   :acct-er-si-exp
   :cn-payroll-er-hf-expense   :acct-er-hf-exp
   :cn-payroll-net-wages       :acct-net-pay
   :cn-payroll-iit             :acct-iit-payable
   :cn-payroll-ee-si           :acct-ee-si-payable
   :cn-payroll-ee-hf           :acct-ee-hf-payable
   :cn-payroll-er-si-payable   :acct-er-si-payable
   :cn-payroll-er-hf-payable   :acct-er-hf-payable
   :cn-payroll-bonus-payable   :acct-bonus-payable})

(def commodity :cny)

(defn- balanced-fact
  "Build a single PayrollFact for E001 matching the yonyou fixture
   math: gross 18000, deductions 5380, net 12620, plus employer SI/HF."
  []
  {:employment 1001
   :gross 18000.00M
   :net 12620.00M
   :components [{:kind :base-wage          :amount 15000.00M  :employer-side? false}
                {:kind :performance-bonus  :amount 3000.00M   :employer-side? false}
                {:kind :ee-pension         :amount -1440.00M  :employer-side? false}
                {:kind :ee-medical         :amount -360.00M   :employer-side? false}
                {:kind :ee-unemployment    :amount -90.00M    :employer-side? false}
                {:kind :ee-housing-fund    :amount -2160.00M  :employer-side? false}
                {:kind :iit-withheld       :amount -1330.00M  :employer-side? false}
                {:kind :er-pension         :amount 2880.00M   :employer-side? true}
                {:kind :er-medical         :amount 1620.00M   :employer-side? true}
                {:kind :er-unemployment    :amount 90.00M     :employer-side? true}
                {:kind :er-work-injury     :amount 90.00M     :employer-side? true}
                {:kind :er-housing-fund    :amount 2160.00M   :employer-side? true}]
   :jurisdiction-specific-codes {:cn/province-of-employment "CN-BJ"
                                 :employee-external-id "E001"}})

(defn- sum-postings ^BigDecimal [postings]
  (reduce (fn [a {:keys [posting/amount]}]
            (.add ^BigDecimal a ^BigDecimal amount))
          0M postings))

(deftest single-fact-balances-per-ledger
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity
                   :ledger :test-ledger})]
    (testing "posting set sums to zero"
      (is (zero? (.signum (sum-postings postings)))))
    (testing "every posting carries the ledger"
      (is (every? #(= :test-ledger (:posting/ledger %)) postings)))
    (testing "every posting carries the commodity"
      (is (every? #(= commodity (:posting/commodity %)) postings)))))

(deftest gross-wage-leg-is-debited
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity})
        wage-leg (first (filter #(= :acct-wages-exp (:posting/account %)) postings))]
    (testing "wages expense leg debits the gross 18000"
      (is (= 18000.00M (:posting/amount wage-leg))))))

(deftest iit-withholding-is-credited
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity})
        iit (first (filter #(= :acct-iit-payable (:posting/account %)) postings))]
    (is (= -1330.00M (:posting/amount iit)))))

(deftest net-pay-leg-is-credited
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity})
        net (first (filter #(= :acct-net-pay (:posting/account %)) postings))]
    (is (= -12620.00M (:posting/amount net)))))

(deftest employer-side-emits-two-legs
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity})
        er-si-exp (filter #(= :acct-er-si-exp (:posting/account %)) postings)
        er-si-pay (filter #(= :acct-er-si-payable (:posting/account %)) postings)]
    (testing "four ER SI legs DR + matching CR pairs (pension/medical/unemployment/work-injury)"
      (is (= 4 (count er-si-exp)))
      (is (= 4 (count er-si-pay))))
    (testing "DR + CR per pair sum to zero"
      (is (= (sum-postings er-si-exp)
             (.negate ^BigDecimal (sum-postings er-si-pay)))))))

(deftest province-analytic-distribution-attached
  (let [postings (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts
                   :commodity commodity})]
    (testing "every posting carries a :cn-province analytic-distribution"
      (let [bj-distribution
            [{:analytic-distribution/plan [:analytic-plan/code "cn-province"]
              :analytic-distribution/account
              [:analytic-account/path "cn-province:BJ"]
              :analytic-distribution/percent 100M}]]
        (is (every? #(= bj-distribution
                        (:posting/analytic-distributions %))
                    postings))))))

(deftest missing-account-tag-throws
  (let [accounts-without-iit (dissoc accounts :cn-payroll-iit)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (pb/build-payroll-postings
                  {:facts [(balanced-fact)]
                   :accounts accounts-without-iit
                   :commodity commodity})))))

(deftest multi-fact-balances
  (let [fact2 (-> (balanced-fact)
                  (assoc :employment 1002)
                  (assoc-in [:jurisdiction-specific-codes
                             :cn/province-of-employment] "CN-SH")
                  (assoc-in [:jurisdiction-specific-codes
                             :employee-external-id] "E002"))
        postings (pb/build-payroll-postings
                  {:facts [(balanced-fact) fact2]
                   :accounts accounts
                   :commodity commodity})
        sh-leg (first (filter #(some (fn [d]
                                       (= [:analytic-account/path "cn-province:SH"]
                                          (:analytic-distribution/account d)))
                                     (:posting/analytic-distributions %))
                              postings))
        bj-leg (first (filter #(some (fn [d]
                                       (= [:analytic-account/path "cn-province:BJ"]
                                          (:analytic-distribution/account d)))
                                     (:posting/analytic-distributions %))
                              postings))]
    (testing "all postings sum to zero across both employees"
      (is (zero? (.signum (sum-postings postings)))))
    (testing "per-province analytic-distribution survives the merge"
      (is (some? sh-leg))
      (is (some? bj-leg)))))

(deftest builder-record-build-postings-protocol
  (let [builder (pb/->CnPayrollPostingBuilder {:commodity commodity})
        postings (pp/build-postings builder
                                    [(balanced-fact)]
                                    {:accounts accounts
                                     :ledger :test-ledger})]
    (is (zero? (.signum (sum-postings postings))))
    (is (every? #(= :test-ledger (:posting/ledger %)) postings))))

(deftest builder-without-commodity-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (pb/build-payroll-postings
                {:facts [(balanced-fact)]
                 :accounts accounts}))))

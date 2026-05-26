(ns kontor.payroll-ca.posting-builder-test
  "Tests for CaPayrollPostingBuilder — pay-element → CoA mapping, RP
   routing, employer-side leg pairs."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-ca.posting-builder :as pb]
            [kontor.payroll-provider :as pp]))

(def accounts
  "Synthetic eid-shaped account map. Real consumers feed eids from
   (d/q ... :kontor.account/code) lookups."
  {:ca-payroll-wages              :acct/wages
   :ca-payroll-er-cpp             :acct/er-cpp
   :ca-payroll-er-ei              :acct/er-ei
   :ca-payroll-er-rpp             :acct/er-rpp
   :ca-payroll-vacation-accrual   :acct/vacation-accrual
   :ca-payroll-itx                :acct/itx
   :ca-payroll-cpp                :acct/cpp
   :ca-payroll-ei                 :acct/ei
   :ca-payroll-rpp                :acct/rpp
   :ca-payroll-union              :acct/union
   :ca-payroll-charity            :acct/charity
   :ca-payroll-net-wages          :acct/net-wages
   :ca-payroll-vacation-liability :acct/vacation-liability
   :ca-payroll-qpp                :acct/qpp
   :ca-payroll-qpip               :acct/qpip
   :ca-payroll-qc-itx             :acct/qc-itx
   :ca-payroll-eht                :acct/eht
   :ca-payroll-wsib               :acct/wsib
   :ca-payroll-er-eht             :acct/er-eht
   :ca-payroll-er-wsib            :acct/er-wsib
   :ca-payroll-other-deduction    :acct/other-deduction
   :ca-payroll-garnishment        :acct/garnishment})

(def commodity :kontor.commodity/cad)

(defn- jane-fact
  "A representative balanced CA payroll fact, ON province, $5000 gross."
  []
  {:employment :emp/jane
   :gross 5000M
   :net 3808.20M
   :components [{:kind :base-wage          :amount 5000M     :employer-side? false}
                {:kind :income-tax-withheld :amount -850M    :employer-side? false}
                {:kind :employee-cpp       :amount -260.30M  :employer-side? false}
                {:kind :employee-ei        :amount -81.50M   :employer-side? false}
                {:kind :employer-cpp       :amount 260.30M   :employer-side? true}
                {:kind :employer-ei        :amount 114.10M   :employer-side? true}]
   :jurisdiction-specific-codes {:engine :test}})

(defn- build [opts fact]
  (let [builder (pb/->CaPayrollPostingBuilder
                 (merge {:commodity commodity} opts))]
    (pp/build-postings builder [fact] {:accounts accounts})))

(deftest postings-balance-to-zero
  (let [postings (build {} (jane-fact))
        sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (testing "All legs sum to zero (the substrate's posting invariant)"
      (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))))

(deftest one-wages-debit-for-gross
  (let [postings (build {} (jane-fact))
        wages-leg (first (filter #(= :acct/wages (:kontor.posting/account %)) postings))]
    (testing "Single wages-expense leg for the gross"
      (is (= 5000M (:kontor.posting/amount wages-leg))))))

(deftest deduction-legs-target-cra-payable-buckets
  (let [postings (build {} (jane-fact))
        by-acct (group-by :kontor.posting/account postings)]
    (testing "Income tax credited to :acct/itx"
      (let [legs (get by-acct :acct/itx)]
        (is (= 1 (count legs)))
        (is (= -850M (:kontor.posting/amount (first legs))))))
    (testing "CPP payable receives BOTH the employee deduction and the employer match"
      (let [legs (get by-acct :acct/cpp)]
        (is (= 2 (count legs)))
        (is (= -520.60M
               (.add ^java.math.BigDecimal (:kontor.posting/amount (first legs))
                     ^java.math.BigDecimal (:kontor.posting/amount (second legs)))))))
    (testing "EI payable receives BOTH employee (-81.50) and employer (-114.10) sides"
      (let [legs (get by-acct :acct/ei)]
        (is (= 2 (count legs)))
        (is (= -195.60M
               (.add ^java.math.BigDecimal (:kontor.posting/amount (first legs))
                     ^java.math.BigDecimal (:kontor.posting/amount (second legs)))))))))

(deftest employer-side-emits-paired-legs
  (let [postings (build {} (jane-fact))
        by-acct (group-by :kontor.posting/account postings)]
    (testing "Employer CPP expense debit"
      (is (= 260.30M (:kontor.posting/amount (first (get by-acct :acct/er-cpp))))))
    (testing "Employer EI expense debit"
      (is (= 114.10M (:kontor.posting/amount (first (get by-acct :acct/er-ei))))))))

(deftest net-wages-payable-credit
  (let [postings (build {} (jane-fact))
        net-legs (filter #(= :acct/net-wages (:kontor.posting/account %)) postings)]
    (testing "Single net-wages credit equal to the fact's :net"
      (is (= 1 (count net-legs)))
      (is (= -3808.20M (:kontor.posting/amount (first net-legs)))))))

(deftest rp-routing-tag-applies-to-every-posting
  (let [postings (build {:rp-account-tag "ca-cra-rp-RP0001"} (jane-fact))]
    (testing "Every posting carries the RP routing tag"
      (is (every? (fn [p]
                    (some #(= % [:kontor.account-tag/name "ca-cra-rp-RP0001"])
                          (:kontor.posting/account-tags p)))
                  postings)))))

(deftest missing-account-tag-throws
  (let [partial (dissoc accounts :ca-payroll-itx)
        builder (pb/->CaPayrollPostingBuilder {:commodity commodity})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No account configured"
                          (pp/build-postings builder [(jane-fact)]
                                             {:accounts partial})))))

(deftest qc-passthrough-deductions-route-to-qc-accounts
  "Note 84 §8 — QC carve-out passthrough: QPP / QPIP / QC ITX
   components flow to dedicated Revenu Québec payable accounts even
   though full RL-1 emission is deferred to C4.1."
  (let [qc-fact {:employment :emp/sophie
                 :gross 5000M
                 :net 3651M
                 :components [{:kind :base-wage         :amount 5000M
                               :employer-side? false}
                              {:kind :income-tax-withheld :amount -550M
                               :employer-side? false}
                              {:kind :employee-qc-itx   :amount -300M
                               :employer-side? false}
                              {:kind :employee-qpp      :amount -319M
                               :employer-side? false}
                              {:kind :employee-qpip     :amount -55M
                               :employer-side? false}
                              {:kind :employee-ei       :amount -125M
                               :employer-side? false}]
                 :jurisdiction-specific-codes {:engine :test :province-of-employment "QC"}}
        postings (build {} qc-fact)
        by-acct (group-by :kontor.posting/account postings)]
    (testing "QPP credit posts to :acct/qpp"
      (is (= -319M (:kontor.posting/amount (first (get by-acct :acct/qpp))))))
    (testing "QPIP credit posts to :acct/qpip"
      (is (= -55M (:kontor.posting/amount (first (get by-acct :acct/qpip))))))
    (testing "QC ITX credit posts to :acct/qc-itx"
      (is (= -300M (:kontor.posting/amount (first (get by-acct :acct/qc-itx))))))))

(deftest vacation-pay-accrual-emits-paired-legs
  (let [fact {:employment :emp/jane
              :gross 5000M
              :net 4768.50M
              :components [{:kind :base-wage      :amount 5000M    :employer-side? false}
                           {:kind :income-tax-withheld :amount -231.50M :employer-side? false}
                           ;; Engine emits a 4% vacation-pay-accrual employer-side
                           ;; component that the posting builder splits into
                           ;; (DR vacation-accrual, CR vacation-liability).
                           {:kind :vacation-pay-accrual :amount 200M :employer-side? true}]}
        postings (build {} fact)
        by-acct (group-by :kontor.posting/account postings)]
    (testing "DR vacation-accrual expense"
      (is (= 200M (:kontor.posting/amount (first (get by-acct :acct/vacation-accrual))))))
    (testing "CR vacation-liability"
      (is (= -200M (:kontor.posting/amount (first (get by-acct :acct/vacation-liability))))))))

(ns kontor.payroll-jp.posting-builder-test
  "Tests for JpPayrollPostingBuilder — pay-element → CoA mapping,
   bonus split, employer-side leg pairs, JPY rounding."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.payroll-jp.posting-builder :as pb]
            [kontor.payroll-provider :as pp]))

(def accounts
  "Synthetic eid-shaped account map. Real consumers feed eids from
   (d/q ... :kontor.account/code) lookups."
  {:jp-payroll-wages                  :acct/wages
   :jp-payroll-bonus                  :acct/bonus
   :jp-payroll-er-statutory-benefits  :acct/er-si
   :jp-payroll-health-insurance       :acct/health
   :jp-payroll-pension                :acct/pension
   :jp-payroll-employment-insurance   :acct/employment
   :jp-payroll-long-term-care         :acct/kaigo
   :jp-payroll-income-tax             :acct/itx
   :jp-payroll-resident-tax           :acct/resident-tax
   :jp-payroll-zaikei                 :acct/zaikei
   :jp-payroll-union-dues             :acct/union
   :jp-payroll-other-deduction        :acct/other
   :jp-payroll-net-wages              :acct/net-wages})

(def commodity :kontor.commodity/jpy)

(defn- tanaka-fact
  "A representative balanced JP monthly payroll fact (no bonus).
   Employee Tanaka under 40 (no 介護保険料):
     Earnings  : base 300000 + commuting 15000 + overtime 25000 = 340000
     Deductions: health 16500 + pension 30500 + employment 2040
                 + income-tax 8000 + resident-tax 18000 = 75040
     Net       : 264960"
  []
  {:employment :emp/tanaka
   :gross 340000M
   :net 264960M
   :components [{:kind :base-wage                     :amount 300000M :employer-side? false}
                {:kind :commuting-allowance           :amount 15000M  :employer-side? false}
                {:kind :overtime                      :amount 25000M  :employer-side? false}
                {:kind :employee-health-insurance     :amount -16500M :employer-side? false}
                {:kind :employee-pension              :amount -30500M :employer-side? false}
                {:kind :employee-employment-insurance :amount -2040M  :employer-side? false}
                {:kind :income-tax-withheld           :amount -8000M  :employer-side? false}
                {:kind :resident-tax-withheld         :amount -18000M :employer-side? false}
                ;; employer accruals (matched payable + expense pair)
                {:kind :employer-health-insurance     :amount 16500M  :employer-side? true}
                {:kind :employer-pension              :amount 30500M  :employer-side? true}
                {:kind :employer-employment-insurance :amount 3060M   :employer-side? true}]
   :jurisdiction-specific-codes {:engine :test
                                 :province-of-employment "東京都"}})

(defn- build [opts fact]
  (let [builder (pb/->JpPayrollPostingBuilder
                 (merge {:commodity commodity} opts))]
    (pp/build-postings builder [fact] {:accounts accounts})))

(deftest postings-balance-to-zero
  (let [postings (build {} (tanaka-fact))
        sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (testing "All legs sum to zero (the substrate's posting invariant)"
      (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))))

(deftest one-wages-debit-for-gross
  (let [postings (build {} (tanaka-fact))
        wages-leg (first (filter #(= :acct/wages (:kontor.posting/account %)) postings))]
    (testing "Single wages-expense leg for the gross"
      (is (= 340000M (:kontor.posting/amount wages-leg)))
      (is (some? wages-leg)))))

(deftest no-bonus-leg-on-monthly-only
  (let [postings (build {} (tanaka-fact))
        bonus-legs (filter #(= :acct/bonus (:kontor.posting/account %)) postings)]
    (testing "No 賞与 leg when the fact carries no :bonus component"
      (is (empty? bonus-legs)))))

(deftest bonus-fact-emits-separate-bonus-leg
  "Per ADR-084 §5 J-GAAP convention: 賞与 lands on its own
   account 賞与 (not collapsed with 給料手当)."
  (let [bonus-fact {:employment :emp/tanaka
                    :gross 600000M
                    :net 524000M
                    :components [{:kind :base-wage           :amount 300000M :employer-side? false}
                                 {:kind :bonus               :amount 300000M :employer-side? false}
                                 {:kind :employee-pension    :amount -50000M :employer-side? false}
                                 {:kind :income-tax-withheld :amount -26000M :employer-side? false}]}
        postings (build {} bonus-fact)
        by-acct (group-by :kontor.posting/account postings)]
    (testing "Wages leg debits the base-wage subtotal only"
      (is (= 300000M (:kontor.posting/amount (first (get by-acct :acct/wages))))))
    (testing "Bonus leg debits the bonus subtotal separately"
      (is (= 300000M (:kontor.posting/amount (first (get by-acct :acct/bonus))))))))

(deftest deduction-legs-route-to-azukari-kin
  (let [postings (build {} (tanaka-fact))
        by-acct (group-by :kontor.posting/account postings)]
    (testing "Income tax credited to :acct/itx"
      (let [legs (get by-acct :acct/itx)]
        (is (= 1 (count legs)))
        (is (= -8000M (:kontor.posting/amount (first legs))))))
    (testing "Health insurance bucket receives employee deduction AND employer match"
      (let [legs (get by-acct :acct/health)]
        (is (= 2 (count legs)))
        ;; -16500 (employee) + -16500 (employer payable) = -33000
        (is (= -33000M
               (.add ^java.math.BigDecimal (:kontor.posting/amount (first legs))
                     ^java.math.BigDecimal (:kontor.posting/amount (second legs)))))))
    (testing "Pension bucket receives BOTH employee deduction and employer match"
      (let [legs (get by-acct :acct/pension)]
        (is (= 2 (count legs)))
        (is (= -61000M
               (.add ^java.math.BigDecimal (:kontor.posting/amount (first legs))
                     ^java.math.BigDecimal (:kontor.posting/amount (second legs)))))))
    (testing "Resident tax credited to :acct/resident-tax (NOT itx — different liability)"
      (let [legs (get by-acct :acct/resident-tax)]
        (is (= 1 (count legs)))
        (is (= -18000M (:kontor.posting/amount (first legs))))))))

(deftest employer-side-emits-paired-legs
  (let [postings (build {} (tanaka-fact))
        by-acct (group-by :kontor.posting/account postings)]
    (testing "Employer SI rolled into 法定福利費 expense"
      (let [legs (get by-acct :acct/er-si)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M legs)]
        ;; 16500 (health) + 30500 (pension) + 3060 (employment) = 50060
        (is (= 50060M sum))))))

(deftest net-wages-payable-credit
  (let [postings (build {} (tanaka-fact))
        net-legs (filter #(= :acct/net-wages (:kontor.posting/account %)) postings)]
    (testing "Single net-wages credit matching the fact's :net"
      (is (= 1 (count net-legs)))
      (is (= -264960M (:kontor.posting/amount (first net-legs)))))))

(deftest missing-account-tag-throws
  (let [partial (dissoc accounts :jp-payroll-income-tax)
        builder (pb/->JpPayrollPostingBuilder {:commodity commodity})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No account configured"
                          (pp/build-postings builder [(tanaka-fact)]
                                             {:accounts partial})))))

(deftest kaigo-hoken-applies-to-age-40-employee
  "Per ADR-084 §3, 介護保険料 only applies to employees ≥40."
  (let [suzuki-fact {:employment :emp/suzuki
                     :gross 412000M
                     :net 306478M
                     :components [{:kind :base-wage                  :amount 400000M  :employer-side? false}
                                  {:kind :commuting-allowance        :amount 12000M   :employer-side? false}
                                  {:kind :employee-health-insurance  :amount -21560M  :employer-side? false}
                                  {:kind :employee-pension           :amount -39750M  :employer-side? false}
                                  {:kind :employee-long-term-care    :amount -3540M   :employer-side? false}
                                  {:kind :employee-employment-insurance :amount -2472M :employer-side? false}
                                  {:kind :income-tax-withheld        :amount -15200M  :employer-side? false}
                                  {:kind :resident-tax-withheld      :amount -23000M  :employer-side? false}
                                  {:kind :employer-long-term-care    :amount 3540M    :employer-side? true}]}
        postings (build {} suzuki-fact)
        by-acct (group-by :kontor.posting/account postings)]
    (testing "介護保険料 credit posts to :acct/kaigo"
      (let [legs (get by-acct :acct/kaigo)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M legs)]
        (is (= 2 (count legs)))
        ;; -3540 (employee) + -3540 (employer payable) = -7080
        (is (= -7080M sum))))))

(deftest rounding-balances-on-fractional-input
  "JPY has no sub-yen unit (ADR-013). Fractional input rounds HALF-EVEN
   per leg; the per-transaction sum must still be zero."
  (let [fractional-fact {:employment :emp/floaty
                         :gross 300000.5M
                         :net 287000.4M
                         :components [{:kind :base-wage              :amount 300000.5M :employer-side? false}
                                      {:kind :income-tax-withheld    :amount -13000.1M :employer-side? false}]}
        postings (build {} fractional-fact)
        sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                      (.add ^java.math.BigDecimal a
                            ^java.math.BigDecimal amount))
                    0M postings)]
    (testing "All legs round to whole yen"
      (is (every? (fn [p]
                    (zero? (.compareTo ^java.math.BigDecimal
                            (:kontor.posting/amount p)
                                       (.setScale ^java.math.BigDecimal
                                        (:kontor.posting/amount p) 0))))
                  postings)))
    (testing "Sum still zero after rounding"
      (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))))))

(deftest ledger-stamp-on-every-leg-when-supplied
  (let [builder (pb/->JpPayrollPostingBuilder {:commodity commodity})
        postings (pp/build-postings builder [(tanaka-fact)]
                                    {:accounts accounts
                                     :ledger :kontor.ledger/jp-jgaap})]
    (testing "Every posting carries the supplied :ledger"
      (is (every? (fn [p] (= :kontor.ledger/jp-jgaap (:kontor.posting/ledger p)))
                  postings)))))

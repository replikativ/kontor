(ns kontor.tax.standalone-payroll-tax-test
  "Iteration 3 — the standalone employer payroll-tax mechanism
   (ADR-099; note 102 §10 / note 103). The first period-tax provider
   to use `marginalize` as the base-selector — it books wage expense,
   marginalizes (σ_E) the wage-coded postings, applies the schedule."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.standalone-payroll-tax :as spt]
            [kontor.tax.tax-return-posting-builder :as trpb]
            [kontor.tax.tax-schedule :as ts]
            [kontor.validation :as validation]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private fy {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.journal/code "PUR" :kontor.journal/type :purchase}
                 {:kontor.account/path "Expenses:Wages"   :kontor.account/code "6200"
                  :kontor.account/type :expense}
                 {:kontor.account/path "Expenses:Other"   :kontor.account/code "6900"
                  :kontor.account/type :expense}
                 {:kontor.account/path "Assets:Cash"      :kontor.account/code "1000"
                  :kontor.account/type :asset}
                 {:kontor.account/path "Expenses:Payroll-Tax" :kontor.account/code "6300"
                  :kontor.account/type :expense}
                 {:kontor.account/path "Liabilities:Payroll-Tax-Payable"
                  :kontor.account/code "2300" :kontor.account/type :liability}])
    conn))

(defn- book-expense! [conn path amount]
  (book/buy! conn {:debit-account  [:kontor.account/path path]
                   :credit-account [:kontor.account/path "Assets:Cash"]
                   :amount amount :commodity eur
                   :effective-date #inst "2026-06-15"}))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :kontor.account/path ?p] [?pp :kontor.posting/account ?a]
                 [?pp :kontor.posting/amount ?amt]]
               (d/db conn) path)))

(deftest flat-levy-marginalizes-only-wage-coded-postings
  (let [conn (fresh)]
    (book-expense! conn "Expenses:Wages" 100000)
    (book-expense! conn "Expenses:Other" 50000)
    (let [provider (spt/standalone-payroll-tax-provider
                    {:id :test-flat :schedule (ts/flat 0.03M)
                     :wage-codes ["6200"] :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})
          [c]      (:components facts)]
      (is (ptp/valid-return-facts? facts))
      (is (= :payroll-tax-employer (:kind c)))
      (is (= :test (:authority c)))
      (is (== 100000M (:amount (:base c)))
          "the base-selector picks the wage-coded postings only")
      (is (== 3000M (:amount (:liability c)))
          "3% of 100000 — the 50000 non-wage expense is excluded"))))

(deftest capped-levy-applies-the-rate-above-the-threshold
  (let [conn (fresh)]
    (book-expense! conn "Expenses:Wages" 1500000)
    (let [provider (spt/standalone-payroll-tax-provider
                    {:id :test-capped
                     :schedule (ts/capped 0.05M {:floor 1000000M})
                     :wage-codes ["6200"] :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})]
      (is (== 25000M (:amount (ptp/total-liability facts)))
          "5% of (1.5M wages − 1M tax-free threshold)"))))

(deftest provision-posts-a-balanced-levy-transaction
  (let [conn (fresh)]
    (book-expense! conn "Expenses:Wages" 200000)
    (let [provider (spt/standalone-payroll-tax-provider
                    {:id :test :schedule (ts/flat 0.03M)
                     :wage-codes ["6200"] :authority :test :commodity :EUR})
          builder  (trpb/make-static-tax-return-posting-builder
                    {:expense-account [:kontor.account/path "Expenses:Payroll-Tax"]
                     :payable-account [:kontor.account/path
                                       "Liabilities:Payroll-Tax-Payable"]
                     :journal   [:kontor.journal/code "GEN"]
                     :commodity eur})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})]
      (validation/transact-with-validation
       conn (trpb/provision-tx-data builder facts
                                    {:effective-date #inst "2026-12-31"}))
      (is (== 6000M  (sum-account conn "Expenses:Payroll-Tax")))
      (is (== -6000M (sum-account conn "Liabilities:Payroll-Tax-Payable"))))))

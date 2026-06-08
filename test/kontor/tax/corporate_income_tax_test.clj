(ns kontor.tax.corporate-income-tax-test
  "Iteration 4 — flat-rate corporate income tax (ADR-099; note 103).
   The first user of `apply-base-transform` (the book → taxable
   adjustment, GAP 1) and the first real use of `greater-of` (the
   minimum-tax floor, GAP 2)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.tax.corporate-income-tax :as cit]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.tax-return-posting-builder :as trpb]
            [kontor.validation :as validation]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private fy {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.journal/code "PUR"  :kontor.journal/type :purchase}
                 {:kontor.journal/code "GEN"  :kontor.journal/type :general}
                 {:kontor.account/path "Income:Sales"          :kontor.account/type :income}
                 {:kontor.account/path "Expenses:Goods"        :kontor.account/type :expense}
                 {:kontor.account/path "Assets:Cash"           :kontor.account/type :asset}
                 {:kontor.account/path "Assets:Receivable"     :kontor.account/type :asset}
                 {:kontor.account/path "Expenses:Income-Tax"   :kontor.account/type :expense}
                 {:kontor.account/path "Liabilities:Tax-Payable" :kontor.account/type :liability}])
    conn))

(defn- book-pnl! [conn income expense]
  (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Receivable"]
                    :credit-account [:kontor.account/path "Income:Sales"]
                    :amount income :commodity eur
                    :effective-date #inst "2026-04-01"})
  (when (pos? expense)
    (book/buy! conn {:debit-account  [:kontor.account/path "Expenses:Goods"]
                     :credit-account [:kontor.account/path "Assets:Cash"]
                     :amount expense :commodity eur
                     :effective-date #inst "2026-05-01"})))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :kontor.account/path ?p] [?pp :kontor.posting/account ?a]
                 [?pp :kontor.posting/amount ?amt]]
               (d/db conn) path)))

(deftest book-profit-flat-rate
  (let [conn (fresh)]
    (book-pnl! conn 1000 400)
    (let [provider (cit/corporate-income-tax-provider
                    {:id :test :rate 0.25M :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})
          [c]      (:components facts)]
      (is (ptp/valid-return-facts? facts))
      (is (= :corporate-income-tax (:kind c)))
      (is (== 600M (:amount (:base c))) "book profit = 1000 income − 400 expense")
      (is (== 150M (:amount (:liability c))) "25% of 600"))))

(deftest base-transform-book-to-taxable
  ;; the first user of apply-base-transform — book profit + add-backs.
  (let [conn (fresh)]
    (book-pnl! conn 1000 400)
    (let [provider (cit/corporate-income-tax-provider
                    {:id :test :rate 0.25M :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts
                    provider {:period fy :conn conn
                              :inputs {:base-transform
                                       {:transform/type :adjustments
                                        :additions [100M] :deductions [50M]}}})
          [c]      (:components facts)]
      (is (== 650M (:amount (:base c)))
          "600 book profit + 100 add-back − 50 deduction")
      (is (== 162.50M (:amount (:liability c))) "25% of 650")
      (is (some? (:base-transform c)) "the transform is recorded for audit"))))

(deftest minimum-tax-floor-via-greater-of
  (let [conn (fresh)]
    (book-pnl! conn 1000 950)            ; profit 50 → 25% = 12.50
    (let [provider (cit/corporate-income-tax-provider
                    {:id :test :rate 0.25M :minimum-tax 2000M
                     :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})
          [c]      (:components facts)]
      (is (== 2000M (:amount (:liability c)))
          "the minimum tax bites — greater-of(12.50, 2000)")
      (is (= [:minimum-tax] (:composed-of c))
          "the component records that the minimum superseded the flat tax"))))

(deftest a-loss-yields-zero-or-the-minimum
  (let [conn (fresh)]
    (book-pnl! conn 500 900)             ; a 400 loss
    (testing "no minimum — a loss yields zero corporate tax"
      (let [p (cit/corporate-income-tax-provider
               {:id :t :rate 0.25M :authority :test :commodity :EUR})]
        (is (zero? (:amount (ptp/total-liability
                             (ptp/period-tax-facts p {:period fy :conn conn})))))))
    (testing "a minimum tax is payable even at a loss"
      (let [p (cit/corporate-income-tax-provider
               {:id :t :rate 0.25M :minimum-tax 1750M
                :authority :test :commodity :EUR})]
        (is (== 1750M (:amount (ptp/total-liability
                                (ptp/period-tax-facts
                                 p {:period fy :conn conn})))))))))

(deftest provision-posts-the-corporate-tax
  (let [conn (fresh)]
    (book-pnl! conn 1000 400)
    (let [provider (cit/corporate-income-tax-provider
                    {:id :test :rate 0.25M :authority :test :commodity :EUR})
          builder  (trpb/make-static-tax-return-posting-builder
                    {:expense-account [:kontor.account/path "Expenses:Income-Tax"]
                     :payable-account [:kontor.account/path "Liabilities:Tax-Payable"]
                     :journal   [:kontor.journal/code "GEN"]
                     :commodity eur})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})]
      (validation/transact-with-validation
       conn (trpb/provision-tx-data builder facts
                                    {:effective-date #inst "2026-12-31"}))
      (is (== 150M  (sum-account conn "Expenses:Income-Tax")))
      (is (== -150M (sum-account conn "Liabilities:Tax-Payable"))))))

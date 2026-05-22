(ns kontor.personal-income-tax-test
  "Iteration 5 — the generic personal-income-tax mechanism (ADR-099;
   note 102 §10 / note 103): marginalize gross income → deduct →
   schedule → − credits + surtaxes. The country schedules (AT/AU/DE/FR)
   are tested in their l10n modules."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.period-tax-provider :as ptp]
            [kontor.personal-income-tax :as pit]
            [kontor.tax-return-posting-builder :as trpb]
            [kontor.tax-schedule :as ts]
            [kontor.validation :as validation]))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private fy {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro"
                  :commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:journal/code "GEN"  :journal/type :general}
                 {:account/path "Income:Salary"        :account/type :income}
                 {:account/path "Assets:Bank"          :account/type :asset}
                 {:account/path "Expenses:Income-Tax"  :account/type :expense}
                 {:account/path "Liabilities:Tax-Payable" :account/type :liability}])
    conn))

(defn- book-income! [conn amount]
  (book/sell! conn {:debit-account  [:account/path "Assets:Bank"]
                    :credit-account [:account/path "Income:Salary"]
                    :amount amount :commodity eur
                    :effective-date #inst "2026-06-30"}))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :account/path ?p] [?pp :posting/account ?a]
                 [?pp :posting/amount ?amt]]
               (d/db conn) path)))

;; a synthetic 2-band schedule: 0% to 10,000, then 25%.
(def ^:private test-schedule
  (ts/progressive [{:rate 0M :upper 10000M} {:rate 0.25M :upper nil}]))

(deftest marginalize-then-schedule
  (let [conn (fresh)]
    (book-income! conn 50000)
    (let [provider (pit/personal-income-tax-provider
                    {:id :test :schedule test-schedule
                     :authority :test :commodity :EUR})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})
          [c]      (:components facts)]
      (is (ptp/valid-return-facts? facts))
      (is (= :personal-income-tax (:kind c)))
      (is (== 50000M (:amount (:base c))) "gross income, marginalized (σ_E)")
      (is (== 10000M (:amount (:liability c))) "25% of (50000 − 10000)"))))

(deftest deductions-credits-and-surtaxes
  (let [conn (fresh)]
    (book-income! conn 50000)
    (let [soli-fn  (fn [tax] {:code :soli :label "Soli"
                              :amount (* 0.055M tax)})
          provider (pit/personal-income-tax-provider
                    {:id :test :schedule test-schedule :authority :test
                     :commodity :EUR :surtax-fns [soli-fn]})
          facts    (ptp/period-tax-facts
                    provider {:period fy :conn conn
                              :inputs {:base-transform
                                       {:transform/type :adjustments
                                        :deductions [4000M]}
                                       :credits [{:code :basic :label "Basic"
                                                  :amount 500M}]}})
          [c]      (:components facts)]
      ;; taxable 46,000 → gross 25%×36,000 = 9,000 → −500 credit = 8,500
      ;; → +5.5% soli (467.50) → 8,967.50
      (is (== 46000M   (:amount (:base c)))            "income − deductions")
      (is (== 9000M    (:amount (:gross-liability c))) "before credits")
      (is (== 8967.50M (:amount (:liability c)))       "− credit + soli")
      (is (= 1 (count (:credits c))))
      (is (= 1 (count (:surtaxes c)))))))

(deftest provision-posts-the-personal-tax
  (let [conn (fresh)]
    (book-income! conn 50000)
    (let [provider (pit/personal-income-tax-provider
                    {:id :test :schedule test-schedule
                     :authority :test :commodity :EUR})
          builder  (trpb/make-static-tax-return-posting-builder
                    {:expense-account [:account/path "Expenses:Income-Tax"]
                     :payable-account [:account/path "Liabilities:Tax-Payable"]
                     :journal   [:journal/code "GEN"]
                     :commodity eur})
          facts    (ptp/period-tax-facts provider {:period fy :conn conn})]
      (validation/transact-with-validation
       conn (trpb/provision-tx-data builder facts
                                    {:effective-date #inst "2026-12-31"}))
      (is (== 10000M  (sum-account conn "Expenses:Income-Tax")))
      (is (== -10000M (sum-account conn "Liabilities:Tax-Payable"))))))

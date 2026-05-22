(ns kontor.sole-proprietor-test
  "Iteration 2 / note 104 Phase 2 — the sole-proprietor rung: the
   business net feeding the personal income-tax return, and the
   periodic VAT return. The freelancer → sole-proprietor user story,
   executable end to end."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.period-tax-provider :as ptp]
            [kontor.personal-income-tax :as pit]
            [kontor.sole-proprietor :as sp]
            [kontor.tax-schedule :as ts]
            [kontor.validation :as validation]
            [kontor.vat-return :as vat]))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private fy {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro"
                  :commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:journal/code "PUR"  :journal/type :purchase}
                 {:journal/code "GEN"  :journal/type :general}
                 {:account/path "Income:Sales"      :account/code "4000"
                  :account/type :income}
                 {:account/path "Expenses:Supplies" :account/code "6000"
                  :account/type :expense}
                 {:account/path "Assets:Bank"       :account/code "1000"
                  :account/type :asset}
                 {:account/path "Assets:Receivable" :account/code "1100"
                  :account/type :asset}
                 {:account/path "Assets:VAT-Input"  :account/code "1200"
                  :account/type :asset}
                 {:account/path "Liabilities:VAT-Output"  :account/code "2200"
                  :account/type :liability}
                 {:account/path "Liabilities:VAT-Payable" :account/code "2210"
                  :account/type :liability}])
    conn))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :account/path ?p] [?pp :posting/account ?a]
                 [?pp :posting/amount ?amt]]
               (d/db conn) path)))

;; ============================================================================
;; business-net — the business P&L, standalone
;; ============================================================================

(deftest business-net-marginalizes-the-business-pnl
  (let [conn (fresh)]
    (book/sell! conn {:debit-account  [:account/path "Assets:Receivable"]
                      :credit-account [:account/path "Income:Sales"]
                      :amount 80000 :commodity eur
                      :effective-date #inst "2026-03-01"})
    (book/buy! conn {:debit-account  [:account/path "Expenses:Supplies"]
                     :credit-account [:account/path "Assets:Bank"]
                     :amount 30000 :commodity eur
                     :effective-date #inst "2026-04-01"})
    (testing "the business kept standalone — the whole book, no :entity"
      (is (== 50000M (:amount (sp/business-net conn (assoc fy :commodity :EUR))))
          "80,000 revenue − 30,000 expenses"))
    (testing "a trading loss is a negative net"
      (book/buy! conn {:debit-account  [:account/path "Expenses:Supplies"]
                       :credit-account [:account/path "Assets:Bank"]
                       :amount 70000 :commodity eur
                       :effective-date #inst "2026-05-01"})
      (is (neg? (:amount (sp/business-net conn (assoc fy :commodity :EUR))))
          "80,000 revenue − 100,000 expenses"))))

;; ============================================================================
;; The periodic VAT return
;; ============================================================================

(deftest vat-return-nets-output-minus-input
  (let [conn (fresh)]
    ;; a taxed sale — the customer pays 119,000: 100,000 revenue + 19,000
    ;; output VAT (a liability owed to the authority).
    (book/entry! conn {:postings [{:account [:account/path "Assets:Receivable"]
                                   :amount 119000}
                                  {:account [:account/path "Income:Sales"]
                                   :amount -100000}
                                  {:account [:account/path "Liabilities:VAT-Output"]
                                   :amount -19000}]
                       :commodity eur :journal [:journal/code "SALE"]
                       :effective-date #inst "2026-03-01" :narration "Taxed sale"})
    ;; a taxed purchase — 23,800 paid: 20,000 expense + 3,800 input VAT
    ;; (recoverable).
    (book/entry! conn {:postings [{:account [:account/path "Expenses:Supplies"]
                                   :amount 20000}
                                  {:account [:account/path "Assets:VAT-Input"]
                                   :amount 3800}
                                  {:account [:account/path "Assets:Bank"]
                                   :amount -23800}]
                       :commodity eur :journal [:journal/code "PUR"]
                       :effective-date #inst "2026-04-01" :narration "Taxed purchase"})
    (let [r (vat/compute-vat-return conn {:from (:from fy) :to (:to fy)
                                          :output-vat-codes ["2200"]
                                          :input-vat-codes  ["1200"]
                                          :commodity :EUR})]
      (is (== 19000M (:amount (:output-vat r))) "output VAT charged on sales")
      (is (== 3800M  (:amount (:input-vat r)))  "input VAT paid on purchases")
      (is (== 15200M (:amount (:net-vat r)))    "19,000 − 3,800 — net payable")
      (testing "the remittance clears both VAT accounts into the net payable"
        (validation/transact-with-validation
         conn (vat/vat-return-tx-data
               r {:output-vat-account  [:account/path "Liabilities:VAT-Output"]
                  :input-vat-account   [:account/path "Assets:VAT-Input"]
                  :vat-payable-account [:account/path "Liabilities:VAT-Payable"]
                  :journal             [:journal/code "GEN"]
                  :effective-date      #inst "2026-12-31"
                  :commodity           eur}))
        (is (zero? (sum-account conn "Liabilities:VAT-Output")) "output VAT cleared")
        (is (zero? (sum-account conn "Assets:VAT-Input"))       "input VAT cleared")
        (is (== -15200M (sum-account conn "Liabilities:VAT-Payable"))
            "the net VAT owed to the authority")))))

;; ============================================================================
;; The freelancer → sole-proprietor story — business net onto the personal return
;; ============================================================================

(deftest business-net-flows-onto-the-personal-return
  ;; The business books and the personal books are SEPARATE — a kontor
  ;; DB may hold only the company (note 104 Phase 2). The business net
  ;; is wired onto the personal return via `:inputs` (the t2125
  ;; pattern); the personal return never marginalizes the business book.
  (let [biz (fresh)]
    (book/sell! biz {:debit-account  [:account/path "Assets:Receivable"]
                     :credit-account [:account/path "Income:Sales"]
                     :amount 90000 :commodity eur
                     :effective-date #inst "2026-03-01"})
    (book/buy! biz {:debit-account  [:account/path "Expenses:Supplies"]
                    :credit-account [:account/path "Assets:Bank"]
                    :amount 30000 :commodity eur
                    :effective-date #inst "2026-05-01"})
    (let [net      (sp/business-net biz (assoc fy :commodity :EUR))
          provider (pit/personal-income-tax-provider
                    {:id :test :schedule (ts/flat 0.25M)
                     :authority :test :commodity :EUR})]
      (is (== 60000M (:amount net)) "business net — 90,000 − 30,000")
      (testing "a pure sole proprietor — no employment income"
        (let [person (fresh)
              [c]    (:components
                      (ptp/period-tax-facts
                       provider {:period fy :conn person
                                 :inputs (sp/business-income-input {} net)}))]
          (is (== 60000M (:amount (:base c)))
              "the business net is the whole personal taxable base")
          (is (== 15000M (:amount (:liability c))) "25% of 60,000")))
      (testing "a proprietor who also draws a salary — both feed the return"
        (let [person (fresh)]
          (book/sell! person {:debit-account  [:account/path "Assets:Bank"]
                              :credit-account [:account/path "Income:Sales"]
                              :amount 40000 :commodity eur
                              :effective-date #inst "2026-06-30"})
          (let [[c] (:components
                     (ptp/period-tax-facts
                      provider {:period fy :conn person
                                :inputs (sp/business-income-input {} net)}))]
            (is (== 100000M (:amount (:base c)))
                "40,000 employment income + 60,000 business net")
            (is (== 25000M (:amount (:liability c))) "25% of 100,000")))))))

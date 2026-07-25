(ns kontor.bank-ca.parser-test
  "Amount assertions here are CONTROL TOTALS (ADR-131), never ratios.
   The ratio assertion this replaces passed a TD Canada Trust parse in
   which every deposit was negative and every withdrawal positive."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.banking.statement-tie-out :as tie]
            [kontor.bank-ca.parser :as p]))

(def fixture-dir (-> (io/resource "rbc.csv") io/file .getParentFile))

(def expected-bank
  {"rbc.csv"         :rbc
   "td.csv"          :td
   "scotiabank.csv"  :scotiabank
   "bmo.csv"         :bmo})

(defn- fixture-file [fname] (io/file fixture-dir fname))

(deftest detect-bank-heuristics
  (doseq [[fname expected] expected-bank]
    (is (= expected (p/detect-bank fname ""))
        (str fname " → " expected))))

(deftest each-fixture-parses
  (doseq [[fname expected-kw] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (testing fname
      (let [cs (p/parse-statement path)]
        (is (seq cs) (str fname " produced no candidates"))
        (is (every? :date cs) (str fname " has rows missing :date"))
        (is (every? #(= expected-kw (:bank %)) cs)
            (str fname " mis-detected bank"))))))

;; ============================================================================
;; Control totals (ADR-131)
;; ============================================================================

(def golden-sum
  "Σ of the parsed amounts, account-holder-signed (positive = money in),
   verified cell by cell against each fixture's own amount column."
  {"rbc.csv"        271.72M
   ;; Was -1740.44: the split debit/credit combiner treated the
   ;; Withdrawal column as an inflow. Closing 3130.62 − opening 1390.18.
   "td.csv"         1740.44M
   "scotiabank.csv" 1734.30M
   "bmo.csv"        1760.93M})

(deftest parsed-amounts-hit-their-control-total
  (doseq [[fname expected] golden-sum
          :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))]]
    (is (zero? (.compareTo ^java.math.BigDecimal expected (tie/total-tie-out cs)))
        (str fname " — Σ " (tie/total-tie-out cs) ", expected " expected))))

(deftest td-ties-out-against-its-own-balance-column
  (testing "TD EasyWeb ships a running balance; the parsed amounts must
            reproduce it for every adjacent row pair"
    (let [cs (p/parse-statement (.getAbsolutePath (fixture-file "td.csv")))
          t (tie/statement-tie-out cs {:opening 1390.18M :closing 3130.62M})]
      (is (:ok? t) (tie/explain "td.csv" t))
      (is (= :ascending (:direction t)))
      (is (= (count cs) (:n t)))
      (is (zero? (.compareTo 1740.44M ^java.math.BigDecimal (:sum t)))))))

(deftest td-direction-of-money
  (testing "the Withdrawal column is money LEAVING — a deposit that parses
            negative is filed as an expense by categorize-transaction"
    (let [by (fn [needle]
               (->> (p/parse-statement (.getAbsolutePath (fixture-file "td.csv")))
                    (filter #(str/includes? (str (:description %)) needle))
                    first))]
      (is (= 3850.00M (:amount (by "PAYROLL DEPOSIT"))))
      (is (= :income (:category (by "PAYROLL DEPOSIT"))))
      (is (= -82.40M (:amount (by "ENBRIDGE GAS"))))
      (is (= :utilities (:category (by "ENBRIDGE GAS"))))
      (is (= 250.00M (:amount (by "GST/HST CREDIT")))
          "a government credit is an inflow")
      (is (= -1450.00M (:amount (by "RENT TRANSFER")))))))

(deftest counterparty-or-description-set
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          described (filter #(or (not (str/blank? (:counterparty %)))
                                 (not (str/blank? (:description %)))) cs)
          ratio (if (seq cs) (double (/ (count described) (count cs))) 0.0)]
      (is (>= ratio 0.4) (str fname " — only " (count described) "/" (count cs))))))

(deftest categorization-by-pattern
  (is (= :groceries
         (-> {:counterparty "" :description "LOBLAWS SUPERMARKET" :amount -85M}
             p/categorize-transaction :category)))
  (is (= :income
         (-> {:counterparty "" :description "ACME PAYROLL DIRECT DEPOSIT" :amount 4500M}
             p/categorize-transaction :category)))
  (is (= :fuel
         (-> {:counterparty "" :description "PETRO-CANADA HWY 401" :amount -55M}
             p/categorize-transaction :category)))
  (is (= :uncategorized-expense
         (-> {:counterparty "" :description "Random vendor" :amount -10M}
             p/categorize-transaction :category))))

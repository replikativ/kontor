(ns kontor.bank-us.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.bank-us.parser :as p]))

(def fixture-dir
  (-> (io/resource "chase.csv") io/file .getParentFile))

(def expected-bank
  {"chase.csv"        :chase
   "wells-fargo.csv"  :wells-fargo
   "bofa.csv"         :bofa
   "amex.csv"         :amex})

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

(deftest non-zero-amounts-dominate
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          non-zero (filter #(not (zero? (.signum ^java.math.BigDecimal (:amount %)))) cs)
          ratio (if (seq cs) (double (/ (count non-zero) (count cs))) 0.0)]
      (is (>= ratio 0.5)
          (str fname " — only " (count non-zero) "/" (count cs) " non-zero")))))

(deftest counterparty-or-description-set
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          described (filter #(or (not (str/blank? (:counterparty %)))
                                 (not (str/blank? (:description %)))) cs)
          ratio (if (seq cs) (double (/ (count described) (count cs))) 0.0)]
      (is (>= ratio 0.4)
          (str fname " — only " (count described) "/" (count cs) " described")))))

(deftest categorization-by-pattern
  (is (= :groceries
         (-> {:counterparty "" :description "WHOLE FOODS MARKET 0432" :amount -78M}
             p/categorize-transaction :category)))
  (is (= :income
         (-> {:counterparty "" :description "DIRECT DEPOSIT ACME PAYROLL" :amount 4500M}
             p/categorize-transaction :category)))
  (is (= :software
         (-> {:counterparty "" :description "ADOBE CREATIVE CLOUD" :amount -54.99M}
             p/categorize-transaction :category)))
  (is (= :uncategorized-expense
         (-> {:counterparty "" :description "Random vendor" :amount -10M}
             p/categorize-transaction :category))))

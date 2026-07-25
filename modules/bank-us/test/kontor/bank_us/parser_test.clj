(ns kontor.bank-us.parser-test
  "Amount assertions here are CONTROL TOTALS (ADR-131), never ratios."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.banking.statement-tie-out :as tie]
            [kontor.bank-us.parser :as p]))

(def fixture-dir
  (-> (io/resource "chase.csv") io/file .getParentFile))

(def expected-bank
  {"chase.csv"        :chase
   "chase-credit.csv" :chase-credit
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

;; ============================================================================
;; Control totals (ADR-131)
;; ============================================================================

(def golden-sum
  "Σ of the parsed amounts, account-holder-signed (positive = money in),
   verified cell by cell against each fixture's own amount column."
  {"chase.csv"        -2945.48M
   "chase-credit.csv" 4367.65M
   "wells-fargo.csv"  242.08M
   "bofa.csv"         2062.66M
   ;; Was +152.08 — AmEx writes a charge POSITIVE (issuer side), so
   ;; every purchase parsed as an inflow and `categorize-transaction`
   ;; filed unmatched ones as :income.
   "amex.csv"         -152.08M})

(deftest parsed-amounts-hit-their-control-total
  (doseq [[fname expected] golden-sum
          :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))]]
    (is (zero? (.compareTo ^java.math.BigDecimal expected (tie/total-tie-out cs)))
        (str fname " — Σ " (tie/total-tie-out cs) ", expected " expected))))

(deftest bofa-ties-out-against-its-balance-column-and-its-preamble
  (testing "the running balance pins every interior row; the preamble's
            Beginning/Ending balance pins the two ends, which the derived
            opening cannot"
    (let [cs (p/parse-statement (.getAbsolutePath (fixture-file "bofa.csv")))
          t (tie/statement-tie-out cs {:opening 5820.44M :closing 7883.10M})]
      (is (:ok? t) (tie/explain "bofa.csv" t))
      (is (= :ascending (:direction t)))
      (is (= (count cs) (:n t)))
      (is (zero? (.compareTo 2062.66M ^java.math.BigDecimal (:sum t)))))))

(deftest chase-fixture-has-no-usable-balance-oracle
  (testing "chase.csv is concatenated data lines lifted from a third-party
            importer's test suite (see SOURCES.md), spanning 2021-2025 with
            a per-snippet balance. Its Balance column is NOT a running
            balance, so it is excluded from the tie-out ON PURPOSE and its
            golden Σ is the only control total available. Pinned here so the
            exclusion is a documented decision rather than an oversight."
    (let [cs (p/parse-statement (.getAbsolutePath (fixture-file "chase.csv")))
          t (tie/statement-tie-out cs)]
      (is (not (:ok? t)))
      (is (seq (:breaks t))
          "if this ever starts tying out, the fixture was replaced and the
           exclusion should be revisited"))))

(deftest amex-is-normalised-to-the-account-holder-convention
  (testing "an issuer-side layout and a cardholder-side layout must not
            disagree about which way money moved"
    (let [by (fn [fname needle]
               (->> (p/parse-statement (.getAbsolutePath (fixture-file fname)))
                    (filter #(str/includes? (str (:description %)) needle))
                    first))]
      (is (= -450.20M (:amount (by "amex.csv" "DELTA AIR LINES"))))
      (is (= :uncategorized-expense (:category (by "amex.csv" "DELTA AIR LINES")))
          "unnormalised this airfare parsed +450.20 and was filed as :income")
      (is (= 892.96M (:amount (by "amex.csv" "AUTOPAY PAYMENT")))
          "paying the card down improves the holder's position")
      (is (= -78.42M (:amount (by "amex.csv" "WHOLE FOODS"))))
      (is (= -20.54M (:amount (by "chase-credit.csv" "AMZN Mktp")))
          "Chase's card export already uses the holder convention — after
           normalisation the two card layouts agree on sign")
      (is (= 4000.00M (:amount (by "chase-credit.csv" "Payment Thank You")))))))

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

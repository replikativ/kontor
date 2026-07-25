(ns kontor.bank-fr.parser-test
  "Amount assertions here are CONTROL TOTALS (ADR-131), never ratios.
   The ratio assertion this replaces passed a Crédit Agricole parse in
   which an €82.40 EDF direct debit came out as +8240 — the comma was
   stripped as a thousands separator AND the debit column was treated as
   an inflow."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.banking.statement-tie-out :as tie]
            [kontor.bank-fr.parser :as p]))

(def fixture-dir (-> (io/resource "n26.csv") io/file .getParentFile))

(def expected-bank
  {"n26.csv"               :n26
   "credit-agricole.csv"   :credit-agricole
   "societe-generale.csv"  :societe-generale
   "bnp-paribas.csv"       :bnp-paribas})

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
   verified cell by cell against each fixture's own amount column.

   None of the four FR layouts ships a running-balance column, so these
   golden totals are the only oracle — hence the per-row spot checks
   below as well."
  {"n26.csv"              -143.47M
   ;; Crédit Agricole: credits 3850,00 + 25,00 + 340,00 = 4215,00 less
   ;; debits 1381,20. Was -283380 — 100x AND sign-inverted.
   "credit-agricole.csv"  2833.80M
   "societe-generale.csv" 2087.47M
   "bnp-paribas.csv"      1699.30M})

(deftest parsed-amounts-hit-their-control-total
  (doseq [[fname expected] golden-sum
          :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))]]
    (is (zero? (.compareTo ^java.math.BigDecimal expected (tie/total-tie-out cs)))
        (str fname " — Σ " (tie/total-tie-out cs) ", expected " expected))))

(deftest credit-agricole-direction-of-money
  (testing "French numerals in a split Débit/Crédit layout: the Débit column
            is money LEAVING and 3850,00 is three thousand eight hundred
            fifty euros, not three hundred eighty-five thousand"
    (let [by (fn [needle]
               (->> (p/parse-statement
                     (.getAbsolutePath (fixture-file "credit-agricole.csv")))
                    (filter #(str/includes? (str (:description %)) needle))
                    first))]
      (is (= 3850.00M (:amount (by "ACME PAYROLL"))))
      (is (= :revenus (:category (by "ACME PAYROLL"))))
      (is (= -82.40M (:amount (by "EDF"))))
      (is (= :electricite-gaz (:category (by "EDF"))))
      (is (= -820.00M (:amount (by "LOYER"))))
      (is (= -8.50M (:amount (by "FRAIS BANCAIRES"))))
      (is (= 25.00M (:amount (by "Remboursement diner")))))))

(deftest credit-agricole-column-semantics-are-unambiguous
  (testing "One fixture row is semantically odd and we do NOT paper over it:
            `PRLV SEPA DGFIP IMPOT REVENU` — a French tax DIRECT DEBIT, i.e.
            money out — sits in the CRÉDIT column. The COLUMN is unambiguous
            (crédit = money in) so the parse is +340,00 and the golden Σ
            reflects that; the row's own wording is what disagrees, and that
            is a fixture-provenance question, not a parser question."
    (let [c (->> (p/parse-statement
                  (.getAbsolutePath (fixture-file "credit-agricole.csv")))
                 (filter #(str/includes? (str (:description %)) "DGFIP"))
                 first)]
      (is (= 340.00M (:amount c))
          "parsed from the column it is actually in"))))

(deftest counterparty-or-description-set
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          described (filter #(or (not (str/blank? (:counterparty %)))
                                 (not (str/blank? (:description %)))) cs)
          ratio (if (seq cs) (double (/ (count described) (count cs))) 0.0)]
      (is (>= ratio 0.4) (str fname " — only " (count described) "/" (count cs))))))

(deftest categorization-by-pattern
  (is (= :alimentation
         (-> {:counterparty "" :description "CARREFOUR MARKET" :amount -50M}
             p/categorize-transaction :category)))
  (is (= :revenus
         (-> {:counterparty "" :description "VIREMENT EN VOTRE FAVEUR" :amount 4500M}
             p/categorize-transaction :category)))
  (is (= :transport
         (-> {:counterparty "" :description "SNCF VOYAGEURS" :amount -89M}
             p/categorize-transaction :category)))
  (is (= :charges-diverses
         (-> {:counterparty "" :description "vendor inconnu" :amount -10M}
             p/categorize-transaction :category))))

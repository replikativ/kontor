(ns kontor.bank-at.parser-test
  "Amount assertions here are CONTROL TOTALS (ADR-131), never ratios."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.banking.statement-tie-out :as tie]
            [kontor.bank-at.parser :as p]))

(def fixture-dir (-> (io/resource "erste.csv") io/file .getParentFile))

(def expected-bank
  {"erste.csv"         :erste
   "raiffeisen.csv"    :raiffeisen
   "bank-austria.csv"  :bank-austria
   "bawag-psk.csv"     :bawag-psk})

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
   verified cell by cell against each fixture's own amount column. None
   of the four AT layouts ships a running-balance column, so these
   totals plus the per-row spot checks below are the oracle."
  {"erste.csv"        1392.71M
   "raiffeisen.csv"   1232.81M
   "bank-austria.csv" 1193.45M
   "bawag-psk.csv"    -378.44M})

(deftest parsed-amounts-hit-their-control-total
  (doseq [[fname expected] golden-sum
          :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))]]
    (is (zero? (.compareTo ^java.math.BigDecimal expected (tie/total-tie-out cs)))
        (str fname " — Σ " (tie/total-tie-out cs) ", expected " expected))))

(deftest direction-of-money-per-row
  (let [by (fn [fname needle]
             (->> (p/parse-statement (.getAbsolutePath (fixture-file fname)))
                  (filter #(str/includes? (str (:description %)) needle))
                  first))]
    (is (= 3650.00M (:amount (by "bank-austria.csv" "GEHALT"))))
    (is (= :einnahmen (:category (by "bank-austria.csv" "GEHALT"))))
    (is (= -128.40M (:amount (by "bank-austria.csv" "STROMRECHNUNG"))))
    (is (= :strom-gas (:category (by "bank-austria.csv" "STROMRECHNUNG")))
        "STROMRECHNUNG is not a RECHNUNG — this bill was booked as revenue")
    (is (= 3250.00M (:amount (by "erste.csv" "Gehalt"))))
    (is (= 3450.00M (:amount (by "raiffeisen.csv" "ACME GMBH"))))
    (is (= -58.00M (:amount (by "bawag-psk.csv" "Einzugsermächtigung"))))))

(deftest counterparty-or-description-set
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          described (filter #(or (not (str/blank? (:counterparty %)))
                                 (not (str/blank? (:description %)))) cs)
          ratio (if (seq cs) (double (/ (count described) (count cs))) 0.0)]
      (is (>= ratio 0.4) (str fname " — only " (count described) "/" (count cs))))))

(deftest categorization-by-pattern
  (is (= :lebensmittel
         (-> {:counterparty "BILLA AG" :description "Einkauf" :amount -50M}
             p/categorize-transaction :category)))
  (is (= :einnahmen
         (-> {:counterparty "ACME GMBH" :description "GEHALT" :amount 3500M}
             p/categorize-transaction :category)))
  (is (= :transport
         (-> {:counterparty "WIENER LINIEN" :description "Jahreskarte" :amount -365M}
             p/categorize-transaction :category)))
  (is (= :sonstige-betriebsausgaben
         (-> {:counterparty "" :description "vendor unbekannt" :amount -10M}
             p/categorize-transaction :category))))

(ns datahike-accounting.bank-at.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike-accounting.bank-at.parser :as p]))

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

(deftest non-zero-amounts-dominate
  (doseq [[fname _] expected-bank
          :let [path (.getAbsolutePath (fixture-file fname))]]
    (let [cs (p/parse-statement path)
          non-zero (filter #(not (zero? (.signum ^java.math.BigDecimal (:amount %)))) cs)
          ratio (if (seq cs) (double (/ (count non-zero) (count cs))) 0.0)]
      (is (>= ratio 0.5) (str fname " — only " (count non-zero) "/" (count cs))))))

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

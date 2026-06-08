(ns kontor.bank-de.parser-test
  "Round-trip the 14 anonymized German-bank fixtures through the
   parser. Each must produce ≥1 candidate, every candidate must have
   a date and a non-zero amount (most of the time), and the detected
   bank must match the filename heuristic."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.bank-de.parser :as p]))

(def fixture-dir
  "Resolved at test time so the resource path is honored regardless
   of working directory."
  (-> (io/resource "commerzbank.csv") io/file .getParentFile))

(def expected-bank
  "filename → expected detected bank kw. Driven by the fixtures we
   actually have under modules/bank-de/test/resources/."
  {"dkb.csv"                              :dkb
   "ing.csv"                              :ing
   "ing-mit-saldo.csv"                    :ing
   "commerzbank.csv"                      :commerzbank
   "postbank.csv"                         :postbank
   "paypal.csv"                           :paypal
   "sparkasse-lzo-camt-v2.csv"            :sparkasse-camt
   "sparkasse-lzo-camt-v8.csv"            :sparkasse-camt
   "sparkasse-lzo-mt940.csv"              :sparkasse-mt940
   "targobank-duesseldorf.csv"            :targobank
   "targobank-duesseldorf-variation.csv"  :targobank
   "gls-bank.csv"                         :gls-bank
   "sparda-bank-west.csv"                 :sparda-bank-west
   "vr-teilhaberbank.csv"                 :vr-bank})

;; ============================================================================
;; parse-german-amount
;; ============================================================================

(deftest parse-german-amount-handles-formats
  (is (= 0M (p/parse-german-amount nil)))
  (is (= 0M (p/parse-german-amount "")))
  (is (= 0M (p/parse-german-amount "   ")))
  (is (= (bigdec "1234.56") (p/parse-german-amount "1.234,56")))
  (is (= (bigdec "-62.3")   (p/parse-german-amount "-62,3")))
  (is (= (bigdec "-123.45") (p/parse-german-amount "(123,45)"))
      "Accounting-style parens = negative.")
  (is (= (bigdec "100.00")  (p/parse-german-amount "€ 100,00")))
  (is (= (bigdec "100.00")  (p/parse-german-amount "EUR 100,00")))
  (is (= (bigdec "100.00")  (p/parse-german-amount "\"100,00\""))))

;; ============================================================================
;; detect-bank
;; ============================================================================

(deftest detect-bank-heuristics
  (doseq [[fname expected] expected-bank]
    (is (= expected (p/detect-bank fname ""))
        (str fname " → " expected))))

(deftest detect-bank-content-fallback
  (is (= :commerzbank
         (p/detect-bank "anonymous.csv" "Buchungstag;Wertstellung;Umsatzart"))
      "Header-content fallback when filename is generic.")
  (is (nil? (p/detect-bank "anonymous.csv" "totally unrelated content"))
      "Returns nil when neither filename nor content gives a hint."))

;; ============================================================================
;; categorize-transaction
;; ============================================================================

(deftest categorization-by-pattern
  (is (= :reisekosten
         (-> {:counterparty "DEUTSCHE BAHN AG" :description "Ticket"
              :amount -50M}
             p/categorize-transaction :category)))
  (is (= :software
         (-> {:counterparty "ADOBE SYSTEMS" :description "Cloud sub"
              :amount -25M}
             p/categorize-transaction :category)))
  (is (= :einnahmen
         (-> {:counterparty "Kunde GmbH" :description "RECHNUNG 2026-001"
              :amount 500M}
             p/categorize-transaction :category)))
  (is (= :sonstige-betriebsausgaben
         (-> {:counterparty "Random Vendor" :description "Sundry"
              :amount -10M}
             p/categorize-transaction :category))
      "Unmatched debit defaults to :sonstige-betriebsausgaben."))

;; ============================================================================
;; Per-fixture round-trip
;; ============================================================================

(defn- fixture-file [fname]
  (io/file fixture-dir fname))

(deftest each-fixture-parses
  (testing "Every supported bank fixture parses without throwing
            and produces ≥1 candidate."
    (doseq [[fname expected-kw] expected-bank
            :let [path (.getAbsolutePath (fixture-file fname))]]
      (testing fname
        (let [candidates (p/parse-statement path)]
          (is (seq candidates)
              (str fname " produced no candidates — parser likely broke"))
          (is (every? :date candidates)
              (str fname " has rows missing :date"))
          (is (every? #(= expected-kw (:bank %)) candidates)
              (str fname " mis-detected bank")))))))

(deftest non-zero-amounts-dominate
  (testing "Bank statements should be mostly non-zero rows. We allow
            some zero-amount rows (PayPal sometimes has fee-only
            rows) but >80% must be non-zero."
    (doseq [[fname _] expected-bank
            :let [path (.getAbsolutePath (fixture-file fname))]]
      (let [cs (p/parse-statement path)
            non-zero (filter #(not (zero? (.signum ^java.math.BigDecimal (:amount %)))) cs)
            ratio (if (seq cs)
                    (double (/ (count non-zero) (count cs)))
                    0.0)]
        (is (>= ratio 0.5)
            (str fname " — only " (count non-zero) "/" (count cs)
                 " non-zero amounts (ratio " ratio ")"))))))

(deftest counterparty-or-description-set
  (testing "Most rows should have at least counterparty OR description
            non-blank — bank statements without any descriptive text
            are unusual and probably indicate a column-mapping bug."
    (doseq [[fname _] expected-bank
            :let [path (.getAbsolutePath (fixture-file fname))]]
      (let [cs (p/parse-statement path)
            described (filter #(or (not (str/blank? (:counterparty %)))
                                   (not (str/blank? (:description %))))
                              cs)
            ratio (if (seq cs)
                    (double (/ (count described) (count cs)))
                    0.0)]
        (is (>= ratio 0.4)
            (str fname " — only " (count described) "/" (count cs)
                 " rows have counterparty/description set"))))))

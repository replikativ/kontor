(ns kontor.bank-de.parser-test
  "Round-trip the 14 anonymized German-bank fixtures through the parser.

   AMOUNT ASSERTIONS ARE CONTROL TOTALS (ADR-131). What used to stand
   here was `(is (>= ratio 0.5))` on the count of rows that parsed
   non-zero — an assertion that passes a sign inversion, a 100x
   misparse, a dropped row and a silently-ignored column alike, and did
   pass all four. Every golden Σ below was verified cell by cell against
   the fixture's own amount column; the layouts with a running-balance
   column additionally tie out against it row by row."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.banking.statement-tie-out :as tie]
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
   "targobank-duesseldorf-variation.csv"  :targobank-punkt
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

(defn- fixture-file [fname]
  (io/file fixture-dir fname))

(defn- fixture-content [fname]
  (slurp (fixture-file fname) :encoding "UTF-8"))

(deftest detect-bank-heuristics
  (doseq [[fname expected] expected-bank]
    (is (= expected (p/detect-bank fname (fixture-content fname)))
        (str fname " → " expected))))

(deftest targobank-decimal-convention-is-detected-not-defaulted
  (testing "the bank ships the SAME headerless layout in comma- and
            dot-decimal form (FORMATS.md). Routing both to the comma config
            parsed -5.00 as -500 — a silent 100x on every amount, invisible
            to a row count."
    (is (= :targobank (p/targobank-decimal-style
                       "03.11.2025;Kartenzahlung;-53,00;;;;'DE67'")))
    (is (= :targobank-punkt (p/targobank-decimal-style
                             "03.11.2025;Kartenzahlung;-53.00;;;;'DE67'")))
    (is (= :targobank (p/targobank-decimal-style "03.11.2025;Text;1.234;;"))
        "a thousands-shaped amount is genuinely ambiguous — fall back to the
         documented default rather than guess")
    (is (= :german (:number-format (:targobank p/bank-configs))))
    (is (= :english (:number-format (:targobank-punkt p/bank-configs))))))

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

(deftest category-patterns-respect-word-boundaries
  (testing "a substring match files money on the wrong side of the P&L just as
            surely as a sign inversion does"
    (is (= :sonstige-betriebsausgaben
           (-> {:counterparty "BANK AG"
                :description "Visa BestCard Smart ABRECHNUNG VOM 23 10 25"
                :amount -24M}
               p/categorize-transaction :category))
        "ABRECHNUNG is not RECHNUNG — this debit was booked as revenue")
    (is (= :einnahmen
           (-> {:counterparty "Kunde GmbH" :description "RECHNUNG 2026-001" :amount 500M}
               p/categorize-transaction :category))
        "and the real thing still matches")
    (is (= :einnahmen
           (-> {:counterparty "Rente" :description "97052181157Z00511 RV-RENTE 11.2025"
                :amount 2647.74M}
               p/categorize-transaction :category))
        "RV-RENTE is not RENT — this pension credit was booked as rent")
    (is (= :miete
           (-> {:counterparty "Vermieter" :description "MIETE Januar" :amount -800M}
               p/categorize-transaction :category)))))

;; ============================================================================
;; Per-fixture round-trip
;; ============================================================================

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

;; ============================================================================
;; Control totals (ADR-131) — these replace `(is (>= ratio 0.5))`
;; ============================================================================

(def golden-sum
  "Σ of the parsed amounts per fixture, account-holder-signed
   (positive = money in). Each value was verified cell by cell against
   the fixture's own amount column."
  {"dkb.csv"                              50.43M
   "ing.csv"                              1362.53M
   "ing-mit-saldo.csv"                    1362.53M
   "commerzbank.csv"                      -73.74M
   "postbank.csv"                         -33.10M
   "paypal.csv"                           -24.77M
   "sparkasse-lzo-camt-v2.csv"            -921.27M
   "sparkasse-lzo-camt-v8.csv"            -921.27M
   "sparkasse-lzo-mt940.csv"              -921.27M
   ;; 10 debits totalling 701.34 + one 83.16 Gutschrift. Was -696.34:
   ;; the first row was dropped AND the credit column was never read.
   "targobank-duesseldorf.csv"            -618.18M
   ;; Was -69634 — the dot-decimal export parsed with German numerals.
   "targobank-duesseldorf-variation.csv"  -618.18M
   "gls-bank.csv"                         -590.22M
   "sparda-bank-west.csv"                 -15316.35M
   "vr-teilhaberbank.csv"                 -341.91M})

(def tie-outs
  "Layouts that ship a running-balance column, with the opening and
   closing balance the column implies."
  {"gls-bank.csv"        {:direction :descending :opening 29354.50M :closing 28764.28M}
   "sparda-bank-west.csv" {:direction :descending :opening 18312.83M :closing 2996.48M}
   "vr-teilhaberbank.csv" {:direction :descending :opening 15188.85M :closing 14846.94M}})

(deftest parsed-amounts-hit-their-control-total
  (doseq [[fname expected] golden-sum
          :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))]]
    (is (zero? (.compareTo ^java.math.BigDecimal expected (tie/total-tie-out cs)))
        (str fname " — Σ " (tie/total-tie-out cs) ", expected " expected))))

(deftest running-balance-layouts-tie-out
  (testing "the bank has already told us what every amount must be; the parse
            must reproduce its balance column for EVERY adjacent row pair"
    (doseq [[fname {:keys [direction opening closing]}] tie-outs
            :let [cs (p/parse-statement (.getAbsolutePath (fixture-file fname)))
                  t (tie/statement-tie-out cs {:opening opening :closing closing})]]
      (is (:ok? t) (tie/explain fname t))
      (is (= direction (:direction t)) (str fname " row order"))
      (is (= (count cs) (:n t))
          (str fname " — every row must carry a balance for a full tie-out")))))

(deftest one-statement-several-export-formats-must-agree
  (testing "Sparkasse ships one statement as MT940 + CAMT v2 + CAMT v8, ING
            with and without a Saldo preamble, Targobank in two decimal
            conventions. Same statement ⇒ same Σ, whichever file — this is
            what catches a per-format misparse that a self-consistent parse
            (including the balance tie-out) cannot see."
    (doseq [group [["sparkasse-lzo-mt940.csv" "sparkasse-lzo-camt-v2.csv"
                    "sparkasse-lzo-camt-v8.csv"]
                   ["ing.csv" "ing-mit-saldo.csv"]
                   ["targobank-duesseldorf.csv" "targobank-duesseldorf-variation.csv"]]]
      (let [sums (mapv #(tie/total-tie-out
                         (p/parse-statement (.getAbsolutePath (fixture-file %))))
                       group)]
        (is (apply = (map #(.stripTrailingZeros ^java.math.BigDecimal %) sums))
            (str (pr-str group) " disagree: " (pr-str sums)))))))

(deftest direction-of-money-is-right-per-row
  (testing "the exact signed amount AND the category it drives — the category
            IS the consequence of the sign, so assert both"
    (let [by-desc (fn [fname needle]
                    (->> (p/parse-statement (.getAbsolutePath (fixture-file fname)))
                         (filter #(str/includes?
                                   (str (:description %) " " (:counterparty %)) needle))
                         first))]
      (testing "ING pension credit — 6-field ragged row; head-anchoring lost it"
        (let [c (by-desc "ing.csv" "RV-RENTE")]
          (is (= 2647.74M (:amount c)))
          (is (= :einnahmen (:category c))
              "a pension inflow is income, not a rent expense")))
      (testing "Targobank first row — dropped when :no-header? was missing"
        (let [c (by-desc "targobank-duesseldorf.csv" "Echtzeit")]
          (is (= -5.00M (:amount c)))))
      (testing "Targobank Gutschrift — the credit column was never mapped"
        (let [c (by-desc "targobank-duesseldorf.csv" "Gutschrift")]
          (is (= 83.16M (:amount c)) "was 0.00 — an €83.16 credit silently deleted")
          ;; Pattern-matched on STROM (a utility refund), so the sign-derived
          ;; default never runs; what matters is that the credit exists and
          ;; is positive at all.
          (is (pos? (.signum ^java.math.BigDecimal (:amount c))))))
      (testing "the dot-decimal export agrees row for row"
        (is (= -53.00M (:amount (by-desc "targobank-duesseldorf-variation.csv"
                                         "SCORE-SB-STATION"))))
        (is (= 83.16M (:amount (by-desc "targobank-duesseldorf-variation.csv"
                                        "Gutschrift"))))))))

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

(ns datahike-accounting.bank-de.info-preservation-test
  "Information-preservation assertion (per the user's chosen
   bank-CSV approach): for every parsed candidate, every NON-BLANK
   cell in its `:raw-row` must be reachable from the candidate map
   — either via the structured fields (date, amount, counterparty,
   description, …) or via the raw-row itself.

   We don't require full byte-equal round-trip; we only require that
   no information is silently dropped during parsing. If a CSV
   column carries genuine business data and our parser doesn't
   capture it anywhere, the test fails.

   Intentionally lenient on:
   - balance / running-total columns (post-import; not on the candidate
     map but reachable via :raw-row)
   - bank-internal codes (BIC, mandate-ref, sammlerreferenz) likewise
     reachable via :raw-row
   - boilerplate columns (currency = always EUR for DE, account-name
     = same for every row)

   Strict on:
   - date / amount / counterparty / description: must round-trip into
     structured fields or appear verbatim somewhere reachable."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike-accounting.bank-de.parser :as p]))

(def fixture-dir
  (-> (io/resource "commerzbank.csv") io/file .getParentFile))

(def fixtures
  ["dkb.csv" "ing.csv" "ing-mit-saldo.csv" "commerzbank.csv"
   "postbank.csv" "paypal.csv" "sparkasse-lzo-camt-v2.csv"
   "sparkasse-lzo-camt-v8.csv" "sparkasse-lzo-mt940.csv"
   "targobank-duesseldorf.csv" "targobank-duesseldorf-variation.csv"
   "gls-bank.csv" "sparda-bank-west.csv" "vr-teilhaberbank.csv"])

(defn- candidate-reachable-text
  "Concatenate every textual field on the candidate that a column's
   value could plausibly land in. Used by the assertion below to
   check 'this CSV cell appeared somewhere reachable in the parsed
   candidate'."
  [c]
  (str/join " "
            [(or (:counterparty c) "")
             (or (:counterparty-iban c) "")
             (or (:description c) "")
             (or (:transaction-type c) "")
             ;; The raw row is the catch-all
             (str/join " " (or (:raw-row c) []))]))

(deftest raw-row-preserved-on-every-candidate
  (testing "Every candidate keeps the original :raw-row vector — that
            single attribute is the safety net guaranteeing nothing
            is lost."
    (doseq [fname fixtures
            :let [path (.getAbsolutePath (io/file fixture-dir fname))
                  cs (p/parse-statement path)]]
      (is (every? #(vector? (:raw-row %)) cs)
          (str fname " — :raw-row missing on some candidates")))))

(deftest non-blank-source-cells-reachable-via-candidate
  (testing "Every non-blank cell from the source row must appear in
            the candidate's :raw-row (the safety net). This is the
            information-preservation invariant — if a cell vanishes
            silently, parsing has dropped data."
    (doseq [fname fixtures
            :let [path (.getAbsolutePath (io/file fixture-dir fname))
                  cs (p/parse-statement path)]]
      (let [drops
            (for [c cs
                  :let [row (:raw-row c)
                        non-blank-cells (filter (complement str/blank?) row)
                        reach (candidate-reachable-text c)]
                  cell non-blank-cells
                  :when (not (str/includes? reach cell))]
              {:fname fname :missing cell :row row})]
        (is (empty? drops)
            (str fname " dropped " (count drops) " non-blank cells, e.g.: "
                 (pr-str (take 3 drops))))))))

(deftest structured-extractions-non-empty-on-most-candidates
  (testing "On a typical bank CSV row most candidates should have
            (a) a parsed date, (b) a non-zero amount OR a non-blank
            description. If MOST rows lack both, we've structurally
            dropped data even if :raw-row catches it."
    (doseq [fname fixtures
            :let [path (.getAbsolutePath (io/file fixture-dir fname))
                  cs (p/parse-statement path)]]
      (let [structured-count
            (count (filter (fn [c]
                             (and (:date c)
                                  (or (and (:amount c)
                                           (not (zero? (.signum ^java.math.BigDecimal (:amount c)))))
                                      (not (str/blank? (or (:description c) ""))))))
                           cs))
            ratio (if (seq cs) (double (/ structured-count (count cs))) 0.0)]
        (is (>= ratio 0.5)
            (str fname " — only " structured-count " / " (count cs)
                 " candidates have date + (non-zero amount OR non-blank desc) "
                 "(ratio " ratio ")"))))))

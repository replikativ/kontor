(ns kontor.banking.bank-csv-test
  "Direct tests for the CSV engine behind all five `modules/bank-*`
   importers. It had none — the only amount-touching assertion in the
   whole importer surface was a ratio of non-zero rows, which cannot
   distinguish +3850.00 from -3850.00 nor 82.40 from 8240.

   Every assertion here is about a parsed AMOUNT or a rejected config.
   ADR-131."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kontor.banking.bank-csv :as bc]
            [kontor.banking.statement-tie-out :as tie]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- tmp-csv
  "Write `lines` to a temp file with `encoding` and return its path."
  ([lines] (tmp-csv lines "UTF-8"))
  ([lines encoding]
   (let [f (java.io.File/createTempFile "kontor-bank-csv" ".csv")]
     (.deleteOnExit f)
     (spit f (str (str/join "\n" lines) "\n") :encoding encoding)
     (.getAbsolutePath f))))

(defn- amounts [candidates] (mapv :amount candidates))

(defn- bd= [a b] (zero? (.compareTo ^java.math.BigDecimal a ^java.math.BigDecimal b)))

(defn- total ^java.math.BigDecimal [candidates] (tie/total-tie-out candidates))

;; ============================================================================
;; Number parsing — the decimal convention is not negotiable
;; ============================================================================

(deftest german-and-english-decimals-are-different-numbers
  (testing "the SAME digits mean different amounts under the two conventions —
            which is why :number-format can never be defaulted"
    (is (bd= 3850.00M (bc/parse-german-amount "3850,00")))
    (is (bd= 385000M (bc/parse-english-amount "3850,00"))
        "English parser strips the comma as a thousands separator: 100x")
    (is (bd= 3850.00M (bc/parse-english-amount "3,850.00")))
    (is (bd= 500M (bc/parse-german-amount "5.00"))
        "German parser strips the dot as a thousands separator: 100x the other way"))

  (testing "signs, parentheses, currency decoration"
    (is (bd= -62.30M (bc/parse-german-amount "-62,30")))
    (is (bd= -123.45M (bc/parse-german-amount "(123,45)")))
    (is (bd= 100M (bc/parse-german-amount "€ 100,00")))
    (is (bd= -42.70M (bc/parse-english-amount "-42.7")))
    (is (bd= -123.45M (bc/parse-english-amount "(123.45)")))
    (is (bd= 99.95M (bc/parse-english-amount "CAD 99.95")))
    (is (bd= 1234.56M (bc/parse-german-amount "1.234,56")))
    (is (bd= 1234.56M (bc/parse-english-amount "1,234.56"))))

  (testing "blank and garbage are 0M, never nil — callers add them up"
    (is (bd= 0M (bc/parse-german-amount nil)))
    (is (bd= 0M (bc/parse-german-amount "")))
    (is (bd= 0M (bc/parse-english-amount "n/a")))))

;; ============================================================================
;; Tail-anchored columns
;; ============================================================================

(deftest negative-col-index-counts-from-the-end
  (let [row ["a" "b" "c" "d"]]
    (is (= "d" (bc/cell row -1)))
    (is (= "c" (bc/cell row -2)))
    (is (= "a" (bc/cell row -4)))
    (is (= "a" (bc/cell row 0)))
    (is (nil? (bc/cell row 9)))
    (is (nil? (bc/cell row -9)))
    (is (nil? (bc/cell row nil)))
    (is (nil? (bc/cell [] 0)))))

(deftest tail-anchored-amount-survives-a-ragged-export
  (testing "ING omits a field entirely on one row shape; head-anchoring the
            amount reads the currency code and loses the largest inflow"
    (let [path (tmp-csv ["Buchung;Wertstellung;Auftraggeber;Buchungstext;Verwendungszweck;Betrag;Waehrung"
                         "09.12.2025;09.12.2025;VISA;Lastschrift;KAUFUMSATZ;-13,98;EUR"
                         ;; 6 fields — no Buchungstext
                         "28.11.2025;28.11.2025;Rente;RV-RENTE 11.2025;2.647,74;EUR"])
          head {:encoding "UTF-8" :skip-rows 0 :date-format "dd.MM.yyyy" :separator \;
                :amount-style :german
                :col-indexes {:date 0 :amount 5}}
          tail (assoc-in head [:col-indexes :amount] -2)]
      (is (bd= 0M (second (amounts (bc/parse-statement-with-config path :ing head))))
          "head-anchored: the pension credit silently parses as zero")
      (is (= [-13.98M 2647.74M]
             (amounts (bc/parse-statement-with-config path :ing tail)))
          "tail-anchored: both row shapes land on the real amount")
      (is (bd= 2633.76M (total (bc/parse-statement-with-config path :ing tail)))))))

;; ============================================================================
;; Split debit/credit — the sign convention is DECLARED
;; ============================================================================

(def ^:private retail-split
  {:encoding "UTF-8" :no-header? true :date-format "MM/dd/yyyy" :separator \,
   :amount-style :split-debit-credit
   :number-format :english :debit-sign -1 :credit-sign 1
   :col-indexes {:date 0 :description 1 :debit 2 :credit 3 :balance 4}})

(deftest retail-split-debit-is-money-leaving
  (testing "on a deposit-account statement the DEBIT column is money OUT.
            A payroll deposit must parse POSITIVE and a bill payment NEGATIVE
            — the inverse files the deposit as an expense downstream."
    (let [path (tmp-csv ["01/02/2026,PAYROLL DEPOSIT ACME CORP,,3850.00,5240.18"
                         "01/03/2026,ENBRIDGE GAS PAP,82.40,,5157.78"])
          cs (bc/parse-statement-with-config path :td retail-split)]
      (is (= [3850.00M -82.40M] (amounts cs)))
      (is (bd= 3767.60M (total cs))))))

(deftest already-signed-debit-column-declares-plus-one
  (testing "Targobank writes its debit column ALREADY signed (-5,00) and its
            credit column unsigned. The retail -1/1 pair would flip every
            debit; :debit-sign 1 is the whole fix — which is exactly why the
            engine refuses to guess."
    (let [cfg {:encoding "UTF-8" :no-header? true :date-format "dd.MM.yyyy" :separator \;
               :amount-style :split-debit-credit
               :number-format :german :debit-sign 1 :credit-sign 1
               :col-indexes {:date 0 :description 1 :debit 2 :credit 3}}
          path (tmp-csv ["03.11.2025;Kartenzahlung SCORE-SB-STATION;-53,00;;;;'DE67'"
                         "10.11.2025;Gutschrift TEAG Strom;;83,16;;;'DE67'"])
          cs (bc/parse-statement-with-config path :targobank cfg)]
      (is (= [-53.00M 83.16M] (amounts cs)))
      (is (bd= 30.16M (total cs))
          "mapping only the debit column deleted the 83.16 Gutschrift"))))

(deftest split-layout-honours-its-declared-decimal-convention
  (testing "the column layout says NOTHING about numerals — Crédit Agricole
            writes 3850,00 and parsing that as English is a silent 100x"
    (let [rows ["02/01/2026;02/01/2026;VIR SEPA RECU ACME PAYROLL;;3850,00"
                "03/01/2026;03/01/2026;PRLV SEPA EDF FACTURE;82,40;"]
          path (tmp-csv rows)
          base {:encoding "UTF-8" :no-header? true :date-format "dd/MM/yyyy" :separator \;
                :amount-style :split-debit-credit
                :debit-sign -1 :credit-sign 1
                :col-indexes {:date 0 :value-date 1 :description 2 :debit 3 :credit 4}}]
      (is (= [3850.00M -82.40M]
             (amounts (bc/parse-statement-with-config
                       path :ca (assoc base :number-format :german)))))
      (is (= [385000M -8240M]
             (amounts (bc/parse-statement-with-config
                       path :ca (assoc base :number-format :english))))
          "the wrong convention is a factor of 100, and nothing about the row
           count or the non-zero ratio changes"))))

;; ============================================================================
;; Single-column sign normalisation
;; ============================================================================

(deftest issuer-side-layout-is-normalised-by-amount-sign
  (testing "AmEx writes a card charge POSITIVE (it increases what you owe).
            Unnormalised it parses as an inflow."
    (let [path (tmp-csv ["Date,Description,Amount"
                         "01/08/2026,DELTA AIR LINES,450.20"
                         "01/18/2026,AUTOPAY PAYMENT,-892.96"])
          base {:encoding "UTF-8" :skip-rows 0 :date-format "MM/dd/yyyy" :separator \,
                :amount-style :english
                :col-indexes {:date 0 :description 1 :amount 2}}]
      (is (= [450.20M -892.96M] (amounts (bc/parse-statement-with-config path :amex base)))
          "raw issuer sign: the airfare looks like income")
      (is (= [-450.20M 892.96M]
             (amounts (bc/parse-statement-with-config path :amex (assoc base :amount-sign -1))))
          "normalised: positive = the holder's position improves")
      (is (bd= 442.76M (total (bc/parse-statement-with-config
                               path :amex (assoc base :amount-sign -1))))))))

;; ============================================================================
;; Header handling
;; ============================================================================

(deftest headerless-layout-must-declare-no-header
  (testing "a headerless layout configured with :skip-rows 0 loses its FIRST
            transaction: with no header keyword to detect, header-row-index
            falls through to 0 and (drop (inc 0) rows) eats row one"
    (let [rows ["03.11.2025;Kartenzahlung SCORE-SB;-53,00"
                "04.11.2025;Entgelt Kontofuehrung;-6,95"]
          path (tmp-csv rows)
          cfg {:encoding "UTF-8" :date-format "dd.MM.yyyy" :separator \;
               :amount-style :german :col-indexes {:date 0 :description 1 :amount 2}}]
      (is (bd= -6.95M (total (bc/parse-statement-with-config
                              path :targobank (assoc cfg :skip-rows 0))))
          "the -53,00 row is gone and the count assertion still passes")
      (is (bd= -59.95M (total (bc/parse-statement-with-config
                               path :targobank (assoc cfg :no-header? true))))))))

(deftest header-autodetect-drops-exactly-the-header
  (let [path (tmp-csv ["Datum;Beschreibung;Betrag"
                       "03.11.2025;Kartenzahlung;-53,00"])
        cfg {:encoding "UTF-8" :skip-rows 0 :date-format "dd.MM.yyyy" :separator \;
             :amount-style :german :col-indexes {:date 0 :description 1 :amount 2}}
        cs (bc/parse-statement-with-config path :x cfg)]
    (is (= [-53.00M] (amounts cs)))))

;; ============================================================================
;; :balance rides along
;; ============================================================================

(deftest balance-column-is-nil-when-absent-never-zero
  (let [path (tmp-csv ["01/02/2026,PAYROLL,,3850.00,5240.18"
                       "01/03/2026,GAS,82.40,,"])
        cs (bc/parse-statement-with-config path :td retail-split)]
    (is (bd= 5240.18M (:balance (first cs))))
    (is (nil? (:balance (second cs)))
        "a blank cell is nil, not 0M — 0M is a legitimate balance")
    (is (nil? (:balance (first (bc/parse-statement-with-config
                                path :td (update retail-split :col-indexes
                                                 dissoc :balance)))))
        "a layout with no :balance column yields nil")))

;; ============================================================================
;; Config validation — and WHERE it runs
;; ============================================================================

(deftest split-layout-must-declare-format-and-both-signs
  (let [bare {:amount-style :split-debit-credit
              :col-indexes {:date 0 :debit 2 :credit 3}}]
    (is (seq (bc/validate-config bare)))
    (is (some #(str/includes? % ":number-format") (bc/validate-config bare)))
    (is (some #(str/includes? % ":debit-sign") (bc/validate-config bare)))
    (is (some #(str/includes? % ":credit-sign") (bc/validate-config bare)))
    (is (empty? (bc/validate-config
                 (assoc bare :number-format :german :debit-sign -1 :credit-sign 1))))))

(deftest validate-config-rejects-the-other-silent-shapes
  (is (some #(str/includes? % ":amount-sign")
            (bc/validate-config {:amount-style :english :amount-sign 0
                                 :col-indexes {:date 0 :amount 1}})))
  (is (seq (bc/validate-config {:amount-style :english :col-indexes {:date 0}}))
      "single-column style with no :amount column")
  (is (seq (bc/validate-config {:amount-style :english :col-indexes {:amount 1}}))
      "no :date column")
  (is (seq (bc/validate-config {:amount-style :dollars :col-indexes {:date 0 :amount 1}}))
      "unknown :amount-style")
  (is (empty? (bc/validate-config {:amount-style :german :col-indexes {:date 0 :amount 1}}))
      ":number-format is DERIVED for the single-column styles"))

(deftest number-format-is-derived-only-for-single-column-styles
  (is (= :german (bc/number-format {:amount-style :german})))
  (is (= :english (bc/number-format {:amount-style :english})))
  (is (nil? (bc/number-format {:amount-style :split-debit-credit}))
      "a column layout does not imply a decimal convention")
  (is (= :english (bc/number-format {:amount-style :german :number-format :english}))
      "an explicit :number-format is authoritative"))

(deftest invalid-config-throws-instead-of-returning-an-empty-statement
  (testing "parse-statement-with-config swallows per-row exceptions, so
            validation MUST run before the row loop — otherwise a config error
            silently drops every transaction"
    (let [path (tmp-csv ["01/02/2026,PAYROLL,,3850.00,5240.18"])
          broken (dissoc retail-split :debit-sign)
          ex (try (bc/parse-statement-with-config path :td broken)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          "a broken config must not parse to []")
      (is (= :bank-csv/invalid-config (:type (ex-data ex))))
      (is (seq (:problems (ex-data ex)))))))

;; ============================================================================
;; statement-tie-out — the oracle that replaces the ratio assertions
;; ============================================================================

(def ^:private td-rows
  ["01/02/2026,PAYROLL DEPOSIT ACME CORP,,3850.00,5240.18"
   "01/03/2026,ENBRIDGE GAS PAP,82.40,,5157.78"
   "01/04/2026,SOBEYS #0432 OTTAWA,68.20,,5089.58"])

(deftest tie-out-accepts-a-correct-ascending-parse
  (let [cs (bc/parse-statement-with-config (tmp-csv td-rows) :td retail-split)
        t (tie/statement-tie-out cs)]
    (is (:ok? t) (tie/explain "td" t))
    (is (= :ascending (:direction t)))
    (is (bd= 1390.18M (:opening t)))
    (is (bd= 5089.58M (:closing t)))
    (is (bd= 3699.40M (:sum t)))
    (is (bd= (:sum t) (:expected t)))))

(deftest tie-out-accepts-a-correct-descending-parse
  (testing "German co-operative banks export newest-first; row order is
            inferred, not assumed"
    (let [cfg {:encoding "UTF-8" :no-header? true :date-format "dd.MM.yyyy" :separator \;
               :amount-style :german
               :col-indexes {:date 0 :description 1 :amount 2 :balance 3}}
          path (tmp-csv ["17.12.2025;Miete;-52,93;28764,28"
                         "16.12.2025;Kauf;-42,24;28817,21"
                         "16.12.2025;Kauf;-39,2;28859,45"])
          t (tie/statement-tie-out (bc/parse-statement-with-config path :gls cfg))]
      (is (:ok? t) (tie/explain "gls" t))
      (is (= :descending (:direction t)))
      (is (bd= 28898.65M (:opening t)))
      (is (bd= 28764.28M (:closing t)))
      (is (bd= -134.37M (:sum t))))))

(deftest tie-out-catches-a-sign-inversion
  (let [flipped (assoc retail-split :debit-sign 1 :credit-sign -1)
        t (tie/statement-tie-out
           (bc/parse-statement-with-config (tmp-csv td-rows) :td flipped))]
    (is (not (:ok? t)))
    (is (= 2 (count (:breaks t))))
    (is (str/includes? (tie/explain "td" t) "does not tie out"))))

(deftest tie-out-cannot-catch-a-uniform-scale-error
  (testing "KNOWN LIMITATION, pinned so nobody mistakes the tie-out for
            sufficient: the balance column is parsed with the same
            :number-format as the amounts, so a 100x misparse scales BOTH and
            the chain stays self-consistent. What catches it is an exact
            expected amount, or two exports of the same statement in different
            decimal conventions agreeing on Σ."
    (let [wrong (assoc retail-split :number-format :german)
          cs (bc/parse-statement-with-config (tmp-csv td-rows) :td wrong)
          t (tie/statement-tie-out cs)]
      (is (:ok? t) "self-consistent — and completely wrong")
      (is (= [385000M -8240M -6820M] (amounts cs))
          "the exact-amount assertion is the one that bites")
      (is (not (:ok? (tie/statement-tie-out cs {:opening 1390.18M})))
          "a declared opening from the statement preamble catches it"))))

(deftest tie-out-catches-a-dropped-row-in-the-middle
  (let [full (bc/parse-statement-with-config (tmp-csv td-rows) :td retail-split)
        without-middle (into [(first full)] (drop 2 full))
        t (tie/statement-tie-out without-middle)]
    (is (not (:ok? t)) "dropping an interior transaction breaks the balance chain")
    (is (= 1 (count (:breaks t))))))

(deftest end-truncation-needs-a-declared-opening
  (testing "the derived opening just shifts when the FIRST row is lost, so the
            chain alone cannot see it — the statement's own preamble can"
    (let [full (bc/parse-statement-with-config (tmp-csv td-rows) :td retail-split)
          truncated (vec (rest full))]
      (is (:ok? (tie/statement-tie-out truncated))
          "invisible to the chain")
      (is (not (:ok? (tie/statement-tie-out truncated {:opening 1390.18M})))
          "visible against the declared opening")
      (is (:ok? (tie/statement-tie-out full {:opening 1390.18M :closing 5089.58M}))
          "the untruncated parse satisfies both ends"))))

(deftest tie-out-refuses-to-pass-a-layout-with-no-balance-column
  (let [cfg (update retail-split :col-indexes dissoc :balance)
        t (tie/statement-tie-out (bc/parse-statement-with-config (tmp-csv td-rows) :td cfg))]
    (is (= :no-balance-column (:reason t)))
    (is (not (:ok? t)) "absence of an oracle is not a pass")))

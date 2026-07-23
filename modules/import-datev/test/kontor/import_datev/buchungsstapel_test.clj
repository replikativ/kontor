(ns kontor.import-datev.buchungsstapel-test
  "DATEV Buchungsstapel export + import. The keystone is the export→import
   round-trip on two-leg entries: what kontor writes, kontor reads back to
   the same bookings. Plus a header-grammar cross-check against the
   MIT-licensed `ledermann/datev` example header."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.import-datev.buchungsstapel :as bs]
            [kontor.import-datev.extf :as extf]))

(def jan-1  #inst "2026-01-01T00:00:00Z")
(def jan-15 #inst "2026-01-15T00:00:00Z")
(def jan-20 #inst "2026-01-20T00:00:00Z")
(def feb-1  #inst "2026-02-01T00:00:00Z")
(def stamp  (java.time.LocalDateTime/of 2026 2 1 0 0 0 0))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general :kontor.journal/name "GJ"}
                 {:kontor.account/path "Assets:Bank"    :kontor.account/code "1200" :kontor.account/type :asset  :kontor.account/active true}
                 {:kontor.account/path "Income:Sales"   :kontor.account/code "4400" :kontor.account/type :income :kontor.account/active true}
                 {:kontor.account/path "Expenses:Rent"  :kontor.account/code "6310" :kontor.account/type :expense :kontor.account/active true}])
    conn))

(defn- book2! []
  (let [conn (bootstrap)
        eur  [:kontor.commodity/symbol "EUR"]
        gen  [:kontor.journal/code "GEN"]]
    ;; two clean two-leg entries
    (book/entry! conn {:journal gen :effective-date jan-15 :commodity eur :narration "Sale A"
                       :debit-account [:kontor.account/path "Assets:Bank"]
                       :credit-account [:kontor.account/path "Income:Sales"]
                       :amount 1000M})
    (book/entry! conn {:journal gen :effective-date jan-20 :commodity eur :narration "Rent"
                       :debit-account [:kontor.account/path "Expenses:Rent"]
                       :credit-account [:kontor.account/path "Assets:Bank"]
                       :amount 500M})
    conn))

(defn- export [conn]
  (bs/export-buchungsstapel conn {:from jan-1 :to feb-1 :year 2026
                                  :company-name "Acme GmbH" :berater-nr "1234567"
                                  :timestamp stamp}))

;; ============================================================================
;; Export shape
;; ============================================================================

(deftest export-has-spec-correct-header-and-one-row-per-two-leg-entry
  (let [out   (export (book2!))
        lines (remove str/blank? (str/split-lines out))
        hdr   (extf/parse-header (first lines))]
    (is (= 7 (:formatversion hdr)) "field 5 = Formatversion, not a line count")
    (is (= "1234567" (:berater hdr)))
    (is (= 2026 (:fiscal-year hdr)))
    ;; header + column line + 2 data rows (one per two-leg tx)
    (is (= 4 (count lines)) (str "header + columns + 2 rows; got " (count lines)))
    (is (= bs/column-count (count (extf/split-row (nth lines 2))))
        "every data row has the full column count")))

;; ============================================================================
;; The round-trip keystone
;; ============================================================================

(deftest export-import-round-trips-two-leg-entries
  (let [conn (book2!)
        {:keys [header bookings]} (bs/parse-buchungsstapel (export conn))
        by-konto (into {} (map (juxt :konto identity)) bookings)]
    (is (= 2026 (:fiscal-year header)))
    (is (= 2 (count bookings)))
    (testing "Sale A: Dr 1200 Bank / Cr 4400 Sales, 1000 on Jan 15"
      (let [b (by-konto "1200")]
        (is (= "4400" (:gegenkonto b)))
        (is (= "S" (:soll-haben b)))
        (is (= 1000.00M (:amount b)) "signed amount on the Konto side (debit → +)")
        (is (= jan-15 (:date b)))
        (is (= "Sale A" (:text b)))))
    (testing "Rent: Dr 6310 / Cr 1200 Bank, 500 on Jan 20"
      (let [b (by-konto "6310")]
        (is (= "1200" (:gegenkonto b)))
        (is (= "S" (:soll-haben b)))
        (is (= 500.00M (:amount b)))
        (is (= jan-20 (:date b)))))))

(deftest booking-materialises-into-a-balanced-two-leg-tx
  (let [{:keys [bookings]} (bs/parse-buchungsstapel (export (book2!)))
        b (first (filter #(= "1200" (:konto %)) bookings))
        tx-data (bs/booking->tx-data b
                                     (fn [code] [:kontor.account/code code])
                                     {:journal [:kontor.journal/code "GEN"]
                                      :commodity [:kontor.commodity/symbol "EUR"]})
        amounts (keep :kontor.posting/amount tx-data)]
    (is (= 2 (count amounts)) "two legs")
    (is (zero? (reduce + amounts)) "the reconstructed entry balances")))

;; ============================================================================
;; Cross-check the header grammar against the external spec
;; ============================================================================

;; A representative EXTF Buchungsstapel header line in the shape published by
;; the MIT-licensed `ledermann/datev` project's example file
;; (github.com/ledermann/datev, examples/EXTF_Buchungsstapel.csv, MIT). Used
;; here only to cross-check that our field ORDER matches the external spec —
;; Versionsnummer 700 pairs with Formatversion 13, Berater/Mandant in fields
;; 11/12, WJ-Beginn in 13, Sachkontenlänge in 14.
(def ^:private ledermann-header
  "\"EXTF\";700;21;\"Buchungsstapel\";13;20180306102500000;;\"XY\";\"\";\"\";1001;456;20180101;4;20180201;20180228;\"\";\"\";1;0;\"\";\"EUR\"")

(deftest parses-the-mit-reference-header-per-spec
  (let [h (extf/parse-header ledermann-header)]
    (is (= 700 (:versionsnummer h)))
    (is (= 13 (:formatversion h)) "700 → Formatversion 13, matching our version->formatversion pairing")
    (is (= 21 (:datenkategorie h)))
    (is (= "Buchungsstapel" (:formatname h)))
    (is (= "1001" (:berater h)) "field 11 = Berater")
    (is (= "456" (:mandant h)) "field 12 = Mandant")
    (is (= "20180101" (:wj-beginn h)) "field 13 = WJ-Beginn")
    (is (= 4 (:sachkontenlaenge h)) "field 14 = Sachkontenlänge")
    (is (= 2018 (:fiscal-year h)))))

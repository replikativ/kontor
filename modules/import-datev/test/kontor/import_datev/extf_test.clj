(ns kontor.import-datev.extf-test
  "The shared EXTF codec: header render/parse (spec-correct field 5),
   row split/escape round-trip, and the scalar codecs."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.import-datev.extf :as extf]))

;; ============================================================================
;; Header — the P0 the whole build-out turns on (note 195 G1)
;; ============================================================================

(deftest header-field-5-is-the-formatversion-not-a-line-count
  (let [h (extf/render-header
           {:versionsnummer 510 :datenkategorie 21 :formatname "Buchungsstapel"
            :erzeugt-am "20260201000000000" :berater "1234567" :mandant "1"
            :wj-beginn "20260101" :sachkontenlaenge 4
            :datum-von "20260101" :datum-bis "20260131" :bezeichnung "Buchungen"
            :buchungstyp 1 :wkz "EUR"})
        cells (extf/split-row h)]
    (is (= "EXTF" (nth cells 0)))
    (is (= "510" (nth cells 1)) "Versionsnummer")
    (is (= "21" (nth cells 2)) "Datenkategorie")
    (is (= "Buchungsstapel" (nth cells 3)))
    (is (= "7" (nth cells 4))
        "field 5 is the Formatversion (7 for Versionsnummer 510), NOT a line count")))

(deftest formatversion-derives-from-versionsnummer
  (is (= "7"  (nth (extf/split-row (extf/render-header {:versionsnummer 510 :erzeugt-am "x"})) 4)))
  (is (= "13" (nth (extf/split-row (extf/render-header {:versionsnummer 700 :erzeugt-am "x"})) 4))
      "700 pairs with Formatversion 13 (the MIT ledermann example)"))

(deftest header-round-trips
  (let [fields {:versionsnummer 510 :datenkategorie 21 :formatname "Buchungsstapel"
                :erzeugt-am "20260201000000000" :herkunft "HC" :exportiert-von "Acme GmbH"
                :berater "1234567" :mandant "42" :wj-beginn "20260101"
                :sachkontenlaenge 4 :datum-von "20260101" :datum-bis "20260131"
                :bezeichnung "Buchungen" :buchungstyp 1 :wkz "EUR"}
        parsed (extf/parse-header (extf/render-header fields))]
    (is (= 510 (:versionsnummer parsed)))
    (is (= 7 (:formatversion parsed)))
    (is (= "1234567" (:berater parsed)))
    (is (= "42" (:mandant parsed)))
    (is (= "Acme GmbH" (:exportiert-von parsed)))
    (is (= 4 (:sachkontenlaenge parsed)))
    (is (= 2026 (:fiscal-year parsed)) "fiscal year derived from WJ-Beginn")))

;; ============================================================================
;; Row codec
;; ============================================================================

(deftest split-escape-round-trip
  (testing "cells with separators/quotes survive render → split"
    (doseq [cells [["100,00" "S" "EUR" "" "" "" "1400" "4400"]
                   ["a;b" "plain" "with \"quotes\"" "line\nbreak"]
                   ["" "" ""]]]
      (is (= cells (extf/split-row (extf/render-row cells)))
          (str "round-trip failed for " (pr-str cells))))))

(deftest decimal-codec
  (is (= 4000.00M (extf/parse-decimal "4000,00")))
  (is (= 0M (extf/parse-decimal "")))
  (is (= "1234,50" (extf/format-amount 1234.5M)))
  (is (= "100,00" (extf/format-amount -100M)) "amount is unsigned; S/H carries the sign"))

(deftest belegdatum-codec
  (let [d #inst "2026-03-15T00:00:00.000-00:00"]
    (is (= "1503" (extf/format-belegdatum d)) "DDMM")
    (is (= d (extf/parse-belegdatum "1503" 2026)) "DDMM + header year → Date")
    (is (nil? (extf/parse-belegdatum "" 2026)))))

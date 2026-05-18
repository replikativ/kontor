(ns kontor.payroll-de-datev.emit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-de-datev.emit :as emit]
            [kontor.payroll-de-datev.wage-types :as wt]
            [kontor.payroll-provider :as pp]))

(def ^:private catalog
  (wt/validate-catalog
   {:catalog/version 1
    :catalog/mandant "99999"
    :catalog/berater "1234"
    :catalog/coa     :skr04
    :catalog/wage-types
    {100 {:kind :base-salary :account-hint :gehalt}
     200 {:kind :base-wage   :account-hint :lohn :uom :hours}
     300 {:kind :weihnachtsgeld :account-hint :freiwillig-st-pflichtig}}}))

;; ============================================================================
;; format primitives
;; ============================================================================

(deftest escape-handles-quotes-and-semicolons
  (is (= "plain" (emit/escape "plain")))
  (is (= "\"has;semi\"" (emit/escape "has;semi")))
  (is (= "\"has\"\"quote\"" (emit/escape "has\"quote")))
  (is (= "\"multi\nline\"" (emit/escape "multi\nline"))))

;; ============================================================================
;; [Allgemein]
;; ============================================================================

(deftest render-allgemein-required-keys
  (let [lines (emit/render-allgemein
               {:berater-nr "1234"
                :mandant-nr "99999"
                :stammdaten-gueltig-ab #inst "2026-05-01"})]
    (is (= "[Allgemein]" (first lines)))
    (is (some #{"Ziel=LODAS"} lines))
    (is (some #{"BeraterNr=1234"} lines))
    (is (some #{"MandantenNr=99999"} lines))
    (is (some #{"Datumsformat=TTMMJJJJ"} lines))
    (is (some #{"StammdatenGueltigAb=01052026"} lines))))

(deftest render-allgemein-lug-variant
  (is (some #{"Ziel=LuG"}
            (emit/render-allgemein
             {:target :lug :berater-nr "1234" :mandant-nr "99999"}))))

(deftest render-allgemein-betriebliche-pnr
  (is (some #{"BetrieblichePNrVerwenden=Ja"}
            (emit/render-allgemein
             {:berater-nr "1234" :mandant-nr "99999"
              :betriebliche-pnr? true}))))

(deftest render-allgemein-throws-without-berater-nr
  (is (thrown? clojure.lang.ExceptionInfo
               (emit/render-allgemein {:mandant-nr "99999"}))))

;; ============================================================================
;; [Satzbeschreibung]
;; ============================================================================

(deftest render-satzbeschreibung-orders-ord-table-fields
  (let [out (emit/render-satzbeschreibung
             [{:ord 1
               :table "u_lod_psd_mitarbeiter"
               :fields ["pnr#psd" "duevo_familienname#psd"]}
              {:ord 2
               :table "u_lod_bwd_buchung_standard"
               :fields ["abrechnung_zeitraum#bwd" "pnr#bwd" "la_eigene#bwd"]}])]
    (is (= "[Satzbeschreibung]" (first out)))
    (is (= "1;u_lod_psd_mitarbeiter;pnr#psd;duevo_familienname#psd"
           (nth out 1)))
    (is (= "2;u_lod_bwd_buchung_standard;abrechnung_zeitraum#bwd;pnr#bwd;la_eigene#bwd"
           (nth out 2)))))

;; ============================================================================
;; render-importdatei — full file assembly
;; ============================================================================

(deftest render-importdatei-end-to-end-shape
  (let [out (emit/render-importdatei
             {:allgemein {:berater-nr "1234"
                          :mandant-nr "99999"
                          :stammdaten-gueltig-ab #inst "2026-05-01"}
              :record-classes
              [{:ord 2 :table "u_lod_bwd_buchung_standard"
                :fields emit/u-lod-bwd-buchung-standard-fields}]
              :bewegungsdaten-rows
              [[2 ["01.05.2026" "1234" 1 100 4000M nil "Gehalt Mai"]]
               [2 ["01.05.2026" "1234" 2 200 5.50M nil "Überstunden"]]]})]
    (testing "CR/LF line termination (LODAS rejects LF-only)"
      (is (str/includes? out "\r\n"))
      (is (not (re-find #"(?<!\r)\n[^\r]" out))))
    (testing "section markers present"
      (is (str/includes? out "[Allgemein]"))
      (is (str/includes? out "[Satzbeschreibung]"))
      (is (str/includes? out "[Bewegungsdaten]"))
      (is (not (str/includes? out "[Stammdaten]"))))
    (testing "semicolon-delimited fields"
      (is (str/includes? out "2;01.05.2026;1234;1;100;4000,00")))))

(deftest render-importdatei-rejects-empty-data-sections
  (is (thrown? clojure.lang.ExceptionInfo
               (emit/render-importdatei
                {:allgemein {:berater-nr "1234" :mandant-nr "99999"}
                 :record-classes [{:ord 2 :table "x" :fields ["a"]}]
                 :stammdaten-rows []
                 :bewegungsdaten-rows []}))))

;; ============================================================================
;; payroll-facts → Bewegungsdaten rows
;; ============================================================================

(def ^:private sample-facts
  [{:employment 17
    :employment-pnr "3011"
    :gross 4000M :net 2500M
    :components [{:kind :base-salary :amount 4000M :account-hint :gehalt}
                 {:kind :weihnachtsgeld :amount 500M :account-hint :freiwillig-st-pflichtig}
                 {:kind :employer-si :amount 800M :employer-side? true}]}])

(deftest payroll-facts-bewegungsdaten-rows-skip-employer-side
  (let [{:keys [rows unmapped]}
        (emit/payroll-facts->bewegungsdaten-rows
         {:pay-period-date #inst "2025-11-01" :catalog catalog}
         sample-facts)]
    (is (= 2 (count rows)))   ; gehalt + weihnachtsgeld; employer-si dropped
    (is (empty? unmapped))
    (testing "rows have ordinal 2 (u_lod_bwd_buchung_standard)"
      (is (every? #(= 2 (first %)) rows)))
    (testing "first row carries the resolved Lohnart-Nr (100 = gehalt)"
      (is (= 100 (-> rows first second (nth 3)))))))

(deftest payroll-facts-bewegungsdaten-unmapped-when-no-catalog-match
  (let [facts [{:employment 17 :employment-pnr "3011"
                :gross 100M :net 100M
                :components [{:kind :reisekosten-vergütung :amount 100M
                              :account-hint :gehalt}]}]
        {:keys [rows unmapped]}
        (emit/payroll-facts->bewegungsdaten-rows
         {:pay-period-date #inst "2025-11-01" :catalog catalog}
         facts)]
    (is (empty? rows))
    (is (= 1 (count unmapped)))
    (is (= :no-lohnart (-> unmapped first :reason)))))

;; ============================================================================
;; DatevLodasEmitProvider — protocol satisfaction
;; ============================================================================

(deftest emit-provider-builds-audit-doc
  (let [provider (emit/make-provider
                  {:catalog catalog
                   :allgemein {:berater-nr "1234"
                               :mandant-nr "99999"
                               :stammdaten-gueltig-ab #inst "2025-11-01"}
                   :pay-period-date #inst "2025-11-01"
                   :pay-period-code "DE-2025-11"})
        docs (pp/emit-payroll-events provider sample-facts
                                     {:pay-period-eid "pp-1"
                                      :entity-eid "ent-1"})]
    (is (= 1 (count docs)))
    (let [doc (first docs)]
      (is (= :tax-filing (:audit-doc/category doc)))
      (is (= :emit-payload (:audit-doc/type doc)))
      (is (= "LODAS-DE-2025-11" (:audit-doc/code doc)))
      (is (str/includes? (:audit-doc/inline-payload doc) "[Allgemein]"))
      (is (str/includes? (:audit-doc/inline-payload doc) "Ziel=LODAS"))
      (is (str/includes? (:audit-doc/inline-payload doc) "BeraterNr=1234"))
      (is (str/includes? (:audit-doc/inline-payload doc) "[Bewegungsdaten]"))
      (is (= 0 (:audit-doc/unmapped-count doc))))))

(ns kontor.l10n-ca.xml.t5-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.xml.t5 :as t5]
            [kontor.l10n-ca.xml.validation :as v]
            [kontor.money :as money])
  (:import [java.io File]))

(defn- cad [s] (money/money (bigdec s) :CAD))

(def ^:private xsd-path
  (str (System/getProperty "user.dir")
       "/modules/l10n-ca/test/resources/cra/info-returns-xsd-2026"
       "/SchemasEFV-Published - 202601/T619_T5.xsd"))

(defn- xsd-present? [] (.exists (File. ^String xsd-path)))

(def transmitter
  {:transmitter/account-number "111222333RZ0001"
   :transmitter/name           "Bank of Examples"
   :transmitter/country-code   "CAN"
   :transmitter/contact        {:name "T. Five"
                                :phone "416-555-0100"
                                :email "t.five@bank.example.ca"}
   :submission/reference-id    "BNKT5001"
   :submission/summary-count   1
   :submission/language        :english})

(def payer-summary
  {:t5-summary/payer-bn      "111222333RZ0001"
   :t5-summary/filer-name    "Bank of Examples"
   :t5-summary/filer-address {:line-1 "1 Bay St" :city "Toronto"
                              :province "ON" :country "CAN"
                              :postal-code "M5K1A1"}
   :t5-summary/contact       {:name "T. Five" :phone "416-555-0100"}
   :t5-summary/tax-year      2024
   :t5-summary/report-type   :original})

(deftest interest-slip-structure
  (let [slip {:t5/payer-bn "111222333RZ0001"
              :t5/recipient-sin "123456789"
              :t5/recipient-type :individual
              :t5/recipient {:surname "Jones" :given "Mary"}
              :t5/recipient-address {:line-1 "2 Oak St" :city "Vancouver"
                                     :province "BC" :country "CAN"
                                     :postal-code "V6B1A1"}
              :t5/boxes {:box-13 (cad "1500.00")}}
        xml-str (t5/emit-string
                 (t5/submission {:t619 transmitter
                                 :t5-summary payer-summary
                                 :slips [slip]}))]
    (is (re-find #"<cdn_int_amt>1500.00</cdn_int_amt>" xml-str)
        "Interest emits as cdn_int_amt")
    (is (re-find #"<snm>Jones</snm>" xml-str))
    (is (re-find #"<rcpnt_tcd>1</rcpnt_tcd>" xml-str)
        "Individual recipient type = 1")))

(deftest eligible-dividend-slip-structure
  (let [slip {:t5/payer-bn "111222333RZ0001"
              :t5/recipient-sin "987654321"
              :t5/recipient-type :individual
              :t5/recipient {:surname "Khan" :given "A."}
              :t5/recipient-address {:line-1 "3 Elm St" :city "Burnaby"
                                     :province "BC" :country "CAN"
                                     :postal-code "V5A1B1"}
              :t5/boxes {:box-24 (cad "1000.00")
                         :box-25 (cad "1380.00")
                         :box-26 (cad "207.27")}}
        xml-str (t5/emit-string
                 (t5/submission {:t619 transmitter
                                 :t5-summary payer-summary
                                 :slips [slip]}))]
    (is (re-find #"<actl_elg_dvamt>1000.00</actl_elg_dvamt>" xml-str))
    (is (re-find #"<tx_elg_dvnd_pamt>1380.00</tx_elg_dvnd_pamt>" xml-str))
    (is (re-find #"<enhn_dvtc_amt>207.27</enhn_dvtc_amt>" xml-str))))

(deftest valid-submission-passes-xsd
  (if (xsd-present?)
    (let [slip {:t5/payer-bn "111222333RZ0001"
                :t5/recipient-sin "123456789"
                :t5/recipient-type :individual
                :t5/recipient {:surname "Jones" :given "Mary"}
                :t5/recipient-address {:line-1 "2 Oak St" :city "Vancouver"
                                       :province "BC" :country "CAN"
                                       :postal-code "V6B1A1"}
                :t5/boxes {:box-13 (cad "1500.00")}}
          sub (t5/submission {:t619 transmitter
                              :t5-summary payer-summary
                              :slips [slip]})
          xml-str (t5/emit-string sub)
          {:keys [valid? errors]} (v/validate xsd-path xml-str)]
      (when-not valid?
        (println "\n=== T5 XSD ERRORS ===")
        (doseq [e errors] (println (format "  line %d: %s" (:line e) (:message e))))
        (println "=== XML ===\n" xml-str))
      (is valid? "T5 submission must validate against T619_T5.xsd"))
    (println "Skipping T5 XSD validation — bundle not present")))

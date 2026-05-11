(ns kontor.l10n-ca.xml.t5018-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.xml.t5018 :as t5018]
            [kontor.l10n-ca.xml.validation :as v]
            [kontor.money :as money])
  (:import [java.io File]))

(defn- cad [s] (money/money (bigdec s) :CAD))

(def ^:private xsd-path
  (str (System/getProperty "user.dir")
       "/modules/l10n-ca/test/resources/cra/info-returns-xsd-2026"
       "/SchemasEFV-Published - 202601/T619_T5018.xsd"))

(defn- xsd-present? [] (.exists (File. ^String xsd-path)))

(def transmitter
  {:transmitter/account-number "555666777RZ0001"
   :transmitter/name           "Builder Co."
   :transmitter/country-code   "CAN"
   :transmitter/contact        {:name "B. Owner"
                                :phone "604-555-0200"
                                :email "owner@builder.example.ca"}
   :submission/reference-id    "BLD00001"
   :submission/summary-count   1
   :submission/language        :english})

(def payer-summary
  {:t5018-summary/payer-bn   "555666777RZ0001"
   :t5018-summary/payer-name "Builder Co."
   :t5018-summary/payer-address {:line-1 "5 Site Rd" :city "Surrey"
                                 :province "BC" :country "CAN"
                                 :postal-code "V3R1A1"}
   :t5018-summary/contact    {:name "B. Owner" :phone "604-555-0200"}
   :t5018-summary/period-end "2024-12-31"
   :t5018-summary/report-type :original})

(deftest corporate-subcontractor
  (testing "Corporate sub: $25k payment, rcpnt_tcd = 3 (corporation)"
    (let [slip {:t5018/payer-bn "555666777RZ0001"
                :t5018/recipient-bn "888999000RC0001"
                :t5018/recipient-type :corporation
                :t5018/corp-name "Drywall Pros Ltd."
                :t5018/recipient-address {:line-1 "6 Trade St" :city "Burnaby"
                                          :province "BC" :country "CAN"
                                          :postal-code "V5C1A1"}
                :t5018/contract-payment-amount (cad "25000.00")}
          xml-str (t5018/emit-string
                   (t5018/submission {:t619 transmitter
                                      :t5018-summary payer-summary
                                      :slips [slip]}))]
      (is (re-find #"<sbctrcr_amt>25000.00</sbctrcr_amt>" xml-str))
      (is (re-find #"<rcpnt_tcd>3</rcpnt_tcd>" xml-str))
      (is (re-find #"<l1_nm>Drywall Pros Ltd.</l1_nm>" xml-str))
      (is (re-find #"<tot_sbctrcr_amt>25000.00</tot_sbctrcr_amt>" xml-str)))))

(deftest summary-totals
  (let [slips [{:t5018/payer-bn "555666777RZ0001"
                :t5018/recipient-sin "111222333"
                :t5018/recipient-bn  "000000000RC0001"
                :t5018/recipient-type :individual
                :t5018/recipient {:surname "Carter" :given "Jim"}
                :t5018/contract-payment-amount (cad "10000.00")}
               {:t5018/payer-bn "555666777RZ0001"
                :t5018/recipient-sin "444555666"
                :t5018/recipient-bn  "000000000RC0001"
                :t5018/recipient-type :individual
                :t5018/recipient {:surname "Singh" :given "P."}
                :t5018/contract-payment-amount (cad "15000.00")}]
        xml-str (t5018/emit-string
                 (t5018/submission {:t619 transmitter
                                    :t5018-summary payer-summary
                                    :slips slips}))]
    (is (re-find #"<slp_cnt>2</slp_cnt>" xml-str))
    (is (re-find #"<tot_sbctrcr_amt>25000.00</tot_sbctrcr_amt>" xml-str))))

(deftest valid-submission-passes-xsd
  (if (xsd-present?)
    (let [slip {:t5018/payer-bn "555666777RZ0001"
                :t5018/recipient-bn "888999000RC0001"
                :t5018/recipient-type :corporation
                :t5018/corp-name "Drywall Pros Ltd."
                :t5018/recipient-address {:line-1 "6 Trade St" :city "Burnaby"
                                          :province "BC" :country "CAN"
                                          :postal-code "V5C1A1"}
                :t5018/contract-payment-amount (cad "25000.00")}
          sub (t5018/submission {:t619 transmitter
                                 :t5018-summary payer-summary
                                 :slips [slip]})
          xml-str (t5018/emit-string sub)
          {:keys [valid? errors]} (v/validate xsd-path xml-str)]
      (when-not valid?
        (println "\n=== T5018 XSD ERRORS ===")
        (doseq [e errors] (println (format "  line %d: %s" (:line e) (:message e))))
        (println "=== XML ===\n" xml-str))
      (is valid? "T5018 submission must validate against T619_T5018.xsd"))
    (println "Skipping T5018 XSD validation — bundle not present")))

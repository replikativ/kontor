(ns kontor.l10n-jp.peppol-pint-test
  "Structural tests for the Peppol PINT JP emitter.

   These verify the shape of the output — namespace usage, presence
   of required UBL elements, JP customization id, currencies — without
   validating against a real UBL XSD (which is large and not in this
   repo). XSD validation can be added by downloading the UBL 2.1
   Invoice XSD bundle alongside the verification pass."
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [kontor.l10n-jp.peppol-pint :as pint]
            [kontor.money :as money]))

(defn- jpy [s] (money/money (bigdec s) :JPY))

(def sample-invoice
  {:invoice/number     "INV-2026-0001"
   :invoice/issue-date #inst "2026-01-15"
   :invoice/due-date   #inst "2026-02-14"
   :invoice/currency   "JPY"
   :invoice/supplier
   {:name "Acme KK"
    :registration-number "T1234567890123"
    :address {:street "1-2-3 Shibuya"
              :city "Tokyo"
              :postal-code "150-0001"
              :country-code "JP"}
    :contact {:name "Sales" :phone "+81-3-1234-5678"
              :email "sales@acme.example.jp"}}
   :invoice/customer
   {:name "Beta KK"
    :registration-number "T9876543210987"
    :address {:street "4-5 Marunouchi"
              :city "Tokyo"
              :postal-code "100-0005"
              :country-code "JP"}}
   :invoice/lines
   [{:line/id 1 :line/name "Widget A"
     :line/quantity 10 :line/unit-code "EA"
     :line/unit-price (jpy "1000")
     :line/net (jpy "10000")
     :line/tax-rate 10
     :line/tax-category "S"}
    {:line/id 2 :line/name "Food box (reduced)"
     :line/quantity 5 :line/unit-code "EA"
     :line/unit-price (jpy "500")
     :line/net (jpy "2500")
     :line/tax-rate 8
     :line/tax-category "AA"}]
   :invoice/tax-totals
   [{:rate 10 :taxable (jpy "10000") :tax (jpy "1000") :category "S"}
    {:rate 8  :taxable (jpy "2500")  :tax (jpy "200")  :category "AA"}]
   :invoice/totals
   {:net (jpy "12500") :tax (jpy "1200") :gross (jpy "13700")}})

(deftest emits-pint-jp-customization
  (let [el (pint/invoice-element sample-invoice)
        s  (pint/emit-string el)]
    (is (re-find #"CustomizationID[^>]*>urn:peppol:pint:billing-1@jp-1" s)
        "JP customization ID present")
    (is (re-find #"ProfileID[^>]*>urn:peppol:bis:billing<" s)
        "PINT BIS Billing profile (NOT the :3.0 European one)")
    (is (re-find #"DocumentCurrencyCode[^>]*>JPY" s))))

(deftest registration-number-on-supplier
  (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
    (is (re-find #"CompanyID[^>]*>T1234567890123" s)
        "Supplier registration number embedded in PartyTaxScheme")))

(deftest amounts-in-jpy-no-decimals
  (testing "JPY amounts emit with 0 decimal places"
    (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
      ;; Match elements with currencyID=JPY and amount 10000 (no decimals).
      (is (re-find #"currencyID=\"JPY\">10000<" s))
      (is (not (re-find #"currencyID=\"JPY\">10000\.\d" s))
          "No .00 suffix on JPY amounts"))))

(deftest two-tax-subtotals-emitted
  (testing "Dual-rate invoice produces two TaxSubtotal elements"
    (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
      (is (= 2 (count (re-seq #"<[^/>]*TaxSubtotal" s)))
          "One TaxSubtotal per rate (10%, 8%)"))))

(deftest two-invoice-lines
  (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
    (is (= 2 (count (re-seq #"<[^/>]*InvoiceLine" s))))))

(deftest reparseable
  (testing "Emitted XML round-trips through data.xml/parse-str"
    (let [el (pint/invoice-element sample-invoice)
          s  (pint/emit-string el)
          parsed (xml/parse-str s)]
      (is (some? parsed))
      (is (= "Invoice" (name (:tag parsed)))))))

(deftest non-jpy-currency-uses-2-decimals
  (testing "If currency=EUR, amounts emit with 2 decimals"
    (let [inv-eur (assoc sample-invoice :invoice/currency "EUR"
                        :invoice/totals
                        {:net (money/money "12500.00" :EUR)
                         :tax (money/money "1200.00" :EUR)
                         :gross (money/money "13700.00" :EUR)})
          s (pint/emit-string (pint/invoice-element inv-eur))]
      (is (re-find #"currencyID=\"EUR\">12500\.00<" s)))))

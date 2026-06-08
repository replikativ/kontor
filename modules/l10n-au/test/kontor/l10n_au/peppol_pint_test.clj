(ns kontor.l10n-au.peppol-pint-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-au.peppol-pint :as pint]
            [kontor.money :as money]))

(defn- aud [s] (money/money (bigdec s) :AUD))

(def sample-invoice
  {:kontor.invoice/number     "INV-2026-0001"
   :kontor.invoice/issue-date #inst "2026-01-15"
   :kontor.invoice/due-date   #inst "2026-02-14"
   :kontor.invoice/currency   "AUD"
   :kontor.invoice/supplier
   {:name "Acme Pty Ltd"
    :abn "12345678901"
    :address {:street "100 Burrard St"
              :city "Sydney"
              :postal-code "2000"
              :country-code "AU"}
    :contact {:name "Sales" :phone "+61-2-0000-0000"
              :email "sales@acme.example.au"}}
   :kontor.invoice/customer
   {:name "Beta Pty Ltd"
    :abn "98765432109"
    :address {:street "200 George St"
              :city "Sydney"
              :postal-code "2000"
              :country-code "AU"}}
   :kontor.invoice/lines
   [{:line/id 1 :line/name "Widget A"
     :line/quantity 10 :line/unit-code "EA"
     :line/unit-price (aud "100.00")
     :line/net (aud "1000.00")
     :line/tax-rate 10
     :line/tax-category "S"}]
   :kontor.invoice/tax-totals
   [{:rate 10 :taxable (aud "1000.00") :tax (aud "100.00") :category "S"}]
   :kontor.invoice/totals
   {:net (aud "1000.00") :tax (aud "100.00") :gross (aud "1100.00")}})

(deftest abn-validation
  (is (pint/abn-valid? "12345678901"))
  (is (pint/abn-valid? "12 345 678 901"))     ; with spaces, stripped
  (is (not (pint/abn-valid? "1234567890")))   ; too short
  (is (not (pint/abn-valid? "123456789012"))) ; too long
  (is (not (pint/abn-valid? "abcdefghijk"))))

(deftest emits-pint-anz-customization
  (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
    (is (re-find #"CustomizationID[^>]*>urn:peppol:pint:billing-1@aunz-1" s))
    (is (re-find #"ProfileID[^>]*>urn:peppol:bis:billing<" s)
        "PINT BIS Billing profile (NOT the :3.0 European one)")
    (is (re-find #"DocumentCurrencyCode[^>]*>AUD" s))))

(deftest abn-on-supplier
  (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
    (is (re-find #"CompanyID[^>]*>12345678901" s))))

(deftest aud-amounts-have-2-decimals
  (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
    (is (re-find #"currencyID=\"AUD\">1000\.00<" s))
    (is (re-find #"currencyID=\"AUD\">1100\.00<" s))))

(deftest tax-scheme-id-is-gst
  (testing "AU uses TaxScheme/ID = GST (not VAT)"
    (let [s (pint/emit-string (pint/invoice-element sample-invoice))]
      (is (re-find #"<[^/>]*ID[^>]*>GST<" s)))))

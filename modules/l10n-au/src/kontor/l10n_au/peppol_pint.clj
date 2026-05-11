(ns kontor.l10n-au.peppol-pint
  "Peppol PINT A-NZ — UBL 2.1 Invoice XML generator for Australia / New Zealand.

   PINT A-NZ is the sole supported Peppol billing specification on
   the Australian Peppol network since **2025-05-15** (it replaced
   Peppol BIS 3.0 on that date). Customization ID:
     `urn:peppol:pint:billing-1@aunz-1`

   The supplier's ABN (Australian Business Number, 11 digits) is
   carried on the PartyTaxScheme element the same way JP carries
   its QIS registration number. AUD is the default currency
   (precision 2).

   No clearance step — Peppol is a four-corner model. The ATO does
   not see invoice contents in transit. ADR-018's :pending-attestation
   state is bypassed; transactions go :draft → :posted directly."
  (:require [clojure.data.xml :as xml]
            [kontor.money :as money]))

(xml/alias-uri 'inv "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2")
(xml/alias-uri 'cbc "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2")
(xml/alias-uri 'cac "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2")

(def ^:const customization-id
  "urn:peppol:pint:billing-1@aunz-1")

(def ^:const profile-id
  ;; The PINT BIS Billing identifier (no ':3.0' suffix). The ':3.0'
  ;; form is the *European* Peppol BIS Billing 3.0 — a different
  ;; specification. PINT A-NZ inherits the base PINT BIS Billing.
  "urn:peppol:bis:billing")

(def ^:const process-id
  "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0")

;; ============================================================================
;; ABN validation
;; ============================================================================

(defn abn-valid?
  "True iff `s` looks like an Australian Business Number — 11 digits.
   Full ABN-checksum validation is documented; for the kernel scaffold
   we accept any 11-digit string."
  [s]
  (boolean (and (string? s)
                (re-matches #"^\d{11}$" (clojure.string/replace s #"\s+" "")))))

;; ============================================================================
;; Formatters
;; ============================================================================

(defn- fmt-amount-decimal
  [m precision]
  (.toPlainString
   (.setScale ^java.math.BigDecimal (:amount m)
              ^int (or precision 2)
              java.math.RoundingMode/HALF_EVEN)))

(defn- amount-el
  [tag m currency precision]
  (xml/element tag {:currencyID currency}
               (fmt-amount-decimal m precision)))

(defn- bd-str [v]
  (cond
    (number? v) (.toPlainString (bigdec v))
    (string? v) v
    :else (str v)))

(defn- fmt-iso-date [d]
  (cond
    (instance? java.time.LocalDate d) (str d)
    (instance? java.util.Date d)
    (str (-> d (.toInstant) (.atZone java.time.ZoneOffset/UTC) .toLocalDate))
    (string? d) d
    :else (throw (ex-info "Expected Date or LocalDate" {:got d}))))

;; ============================================================================
;; Party
;; ============================================================================

(defn- postal-address-el
  [{:keys [street city postal-code country-code]
    :or {country-code "AU"}}]
  (xml/element
   ::cac/PostalAddress {}
   (when street (xml/element ::cbc/StreetName {} street))
   (when city (xml/element ::cbc/CityName {} city))
   (when postal-code (xml/element ::cbc/PostalZone {} postal-code))
   (xml/element ::cac/Country {}
                (xml/element ::cbc/IdentificationCode {} country-code))))

(defn- party-tax-scheme-el [abn-or-tax-id]
  (when abn-or-tax-id
    (xml/element
     ::cac/PartyTaxScheme {}
     (xml/element ::cbc/CompanyID {} abn-or-tax-id)
     (xml/element ::cac/TaxScheme {}
                  (xml/element ::cbc/ID {} "GST")))))

(defn- party-el
  [tag {:keys [name abn address contact]}]
  (xml/element
   tag {}
   (xml/element
    ::cac/Party {}
    (when name
      (xml/element ::cac/PartyName {}
                   (xml/element ::cbc/Name {} name)))
    (when address (postal-address-el address))
    (party-tax-scheme-el abn)
    (when name
      (xml/element ::cac/PartyLegalEntity {}
                   (xml/element ::cbc/RegistrationName {} name)
                   (when abn
                     (xml/element ::cbc/CompanyID {} abn))))
    (when contact
      (xml/element ::cac/Contact {}
                   (when (:name contact) (xml/element ::cbc/Name {} (:name contact)))
                   (when (:phone contact) (xml/element ::cbc/Telephone {} (:phone contact)))
                   (when (:email contact) (xml/element ::cbc/ElectronicMail {} (:email contact))))))))

;; ============================================================================
;; Tax category
;; ============================================================================

(defn- tax-category-el [category-code rate]
  (xml/element
   ::cac/TaxCategory {}
   (xml/element ::cbc/ID {} category-code)
   (xml/element ::cbc/Percent {} (bd-str rate))
   (xml/element ::cac/TaxScheme {}
                (xml/element ::cbc/ID {} "GST"))))

;; ============================================================================
;; Invoice line
;; ============================================================================

(defn- invoice-line-el
  [{:line/keys [id name quantity unit-code unit-price net tax-rate tax-category]
    :or {unit-code "EA" tax-category "S"}}
   currency precision]
  (xml/element
   ::cac/InvoiceLine {}
   (xml/element ::cbc/ID {} (str id))
   (xml/element ::cbc/InvoicedQuantity {:unitCode unit-code} (bd-str quantity))
   (amount-el ::cbc/LineExtensionAmount net currency precision)
   (xml/element ::cac/Item {}
                (xml/element ::cbc/Name {} name)
                (tax-category-el tax-category tax-rate))
   (xml/element ::cac/Price {}
                (amount-el ::cbc/PriceAmount unit-price currency precision))))

;; ============================================================================
;; Tax totals
;; ============================================================================

(defn- tax-subtotal-el
  [{:keys [rate taxable tax category] :or {category "S"}}
   currency precision]
  (xml/element ::cac/TaxSubtotal {}
               (amount-el ::cbc/TaxableAmount taxable currency precision)
               (amount-el ::cbc/TaxAmount tax currency precision)
               (tax-category-el category rate)))

(defn- tax-total-el
  [tax-subtotals total-tax currency precision]
  (xml/element ::cac/TaxTotal {}
               (amount-el ::cbc/TaxAmount total-tax currency precision)
               (apply concat
                      [(map #(tax-subtotal-el % currency precision) tax-subtotals)])))

;; ============================================================================
;; Monetary totals
;; ============================================================================

(defn- monetary-totals-el [{:keys [net tax gross]} currency precision]
  (xml/element
   ::cac/LegalMonetaryTotal {}
   (amount-el ::cbc/LineExtensionAmount net currency precision)
   (amount-el ::cbc/TaxExclusiveAmount net currency precision)
   (amount-el ::cbc/TaxInclusiveAmount gross currency precision)
   (amount-el ::cbc/PayableAmount gross currency precision)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn invoice-element
  "Build a UBL Peppol PINT A-NZ <Invoice> element.

   Input:
     {:invoice/number / issue-date / due-date / currency
                                  (default \"AUD\")
      :invoice/invoice-type-code  (default \"380\")
      :invoice/supplier {:name … :abn … :address {…} :contact {…}}
      :invoice/customer {:name … :abn … :address {…}}
      :invoice/lines [<line-map> …]
      :invoice/tax-totals [{:rate :taxable :tax :category}]
      :invoice/totals {:net :tax :gross}}"
  [{:invoice/keys [number issue-date due-date currency invoice-type-code
                   supplier customer lines tax-totals totals]
    :or {currency "AUD"
         invoice-type-code "380"}}]
  (let [precision 2
        total-tax (:tax totals)
        subtotals tax-totals]
    (xml/element
     ::inv/Invoice {}
     (xml/element ::cbc/CustomizationID {} customization-id)
     (xml/element ::cbc/ProfileID {} profile-id)
     (xml/element ::cbc/ID {} number)
     (xml/element ::cbc/IssueDate {} (fmt-iso-date issue-date))
     (when due-date (xml/element ::cbc/DueDate {} (fmt-iso-date due-date)))
     (xml/element ::cbc/InvoiceTypeCode {} invoice-type-code)
     (xml/element ::cbc/DocumentCurrencyCode {} currency)
     (party-el ::cac/AccountingSupplierParty supplier)
     (party-el ::cac/AccountingCustomerParty customer)
     (tax-total-el subtotals total-tax currency precision)
     (monetary-totals-el totals currency precision)
     (map #(invoice-line-el % currency precision) lines))))

(defn emit-string [el] (xml/emit-str el))

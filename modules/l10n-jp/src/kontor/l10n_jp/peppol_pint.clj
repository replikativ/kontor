(ns kontor.l10n-jp.peppol-pint
  "Peppol PINT JP — UBL 2.1 Invoice XML generator for Japan.

   PINT (Peppol International Invoice) is the global successor family
   to Peppol BIS Billing 3.0. The JP profile applies the
   `urn:peppol:pint:billing-1@jp-1` customization which:
     - Carries the Qualified Invoice Issuer registration number
       (T + 13 digits) on the supplier PartyTaxScheme.
     - Splits TaxSubtotal per consumption-tax rate (10% standard,
       8% reduced, 0% zero-rated, exempt).
     - Uses JPY DocumentCurrencyCode (zero fractional digits).

   This module produces a `clojure.data.xml` element tree for an
   Invoice and a string emitter; it does NOT transmit. Transmission
   to a Peppol access point is the caller's responsibility (the
   `EInvoiceProvider` protocol surface).

   **Element-name verification needed:** the customization ID + a
   few field names should be cross-checked against the published
   PINT JP spec from peppol.org (Digital Agency JP is the local
   Peppol Authority). When verified, this docstring can be marked
   so. The structural shape (UBL Invoice → supplier/customer
   parties → invoice lines → tax totals → monetary totals) is
   stable Peppol BIS Billing and is not in doubt."
  (:require [clojure.data.xml :as xml]
            [kontor.money :as money]))

(xml/alias-uri 'inv "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2")
(xml/alias-uri 'cbc "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2")
(xml/alias-uri 'cac "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2")

(def ^:const customization-id
  "urn:peppol:pint:billing-1@jp-1")

(def ^:const profile-id
  ;; The PINT BIS Billing identifier — NOT the European BIS Billing 3.0
  ;; (which is :3.0-suffixed). Verified against docs.peppol.eu/poac/pint/pint/bis/
  ;; and docs.peppol.eu/poac/jp/pint-jp/bis/.
  "urn:peppol:bis:billing")

(def ^:const process-id
  ;; Peppol Process ID (BT-23). Used in the SBDH/transport envelope,
  ;; not the invoice body itself. Source: Peppol PINT JP docs.
  "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0")

;; ============================================================================
;; Formatters
;; ============================================================================

(defn- fmt-amount-decimal
  "UBL amounts use `Amount` elements with `currencyID` attribute. The
   value is rendered as a plain decimal string with the precision the
   currency uses (JPY = 0; most others = 2)."
  [m precision]
  (.toPlainString
   (.setScale ^java.math.BigDecimal (:amount m)
              ^int (or precision 0)
              java.math.RoundingMode/HALF_EVEN)))

(defn- amount-el
  [tag m currency precision]
  (xml/element tag {:currencyID currency}
               (fmt-amount-decimal m precision)))

(defn- bd-str
  "Plain decimal string for non-currency values (quantities, percentages)."
  [v]
  (cond
    (number? v) (.toPlainString (bigdec v))
    (string? v) v
    :else (str v)))

(defn- fmt-iso-date
  "Format a java.util.Date or java.time.LocalDate as YYYY-MM-DD UTC."
  [d]
  (cond
    (instance? java.time.LocalDate d) (str d)
    (instance? java.util.Date d)
    (let [ld (-> d (.toInstant) (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
      (str ld))
    (string? d) d
    :else (throw (ex-info "Expected Date or LocalDate" {:got d}))))

;; ============================================================================
;; Party (supplier or customer)
;; ============================================================================

(defn- postal-address-el
  [{:keys [street city postal-code country-code]
    :or {country-code "JP"}}]
  (xml/element
   ::cac/PostalAddress {}
   (when street
     (xml/element ::cbc/StreetName {} street))
   (when city
     (xml/element ::cbc/CityName {} city))
   (when postal-code
     (xml/element ::cbc/PostalZone {} postal-code))
   (xml/element
    ::cac/Country {}
    (xml/element ::cbc/IdentificationCode {} country-code))))

(defn- party-tax-scheme-el
  "For JP, the registration number goes inside PartyTaxScheme."
  [registration-number]
  (when registration-number
    (xml/element
     ::cac/PartyTaxScheme {}
     (xml/element ::cbc/CompanyID {} registration-number)
     (xml/element ::cac/TaxScheme {}
                  (xml/element ::cbc/ID {} "VAT")))))

(defn- party-el
  [tag {:keys [name registration-number address contact]}]
  (xml/element
   tag {}
   (xml/element
    ::cac/Party {}
    (when name
      (xml/element ::cac/PartyName {}
                   (xml/element ::cbc/Name {} name)))
    (when address
      (postal-address-el address))
    (party-tax-scheme-el registration-number)
    (when name
      (xml/element ::cac/PartyLegalEntity {}
                   (xml/element ::cbc/RegistrationName {} name)))
    (when contact
      (xml/element ::cac/Contact {}
                   (when (:name contact)
                     (xml/element ::cbc/Name {} (:name contact)))
                   (when (:phone contact)
                     (xml/element ::cbc/Telephone {} (:phone contact)))
                   (when (:email contact)
                     (xml/element ::cbc/ElectronicMail {} (:email contact))))))))

;; ============================================================================
;; Tax category (per UBL conventions)
;; ============================================================================

(defn- tax-category-el
  "UBL category codes:
     S  = standard rate
     AA = lower / reduced rate
     Z  = zero-rated
     E  = exempt
     O  = out of scope"
  [category-code rate]
  (xml/element
   ::cac/TaxCategory {}
   (xml/element ::cbc/ID {} category-code)
   (xml/element ::cbc/Percent {} (bd-str rate))
   (xml/element ::cac/TaxScheme {}
                (xml/element ::cbc/ID {} "VAT"))))

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
   (xml/element
    ::cac/Item {}
    (xml/element ::cbc/Name {} name)
    (tax-category-el tax-category tax-rate))
   (xml/element
    ::cac/Price {}
    (amount-el ::cbc/PriceAmount unit-price currency precision))))

;; ============================================================================
;; Tax totals
;; ============================================================================

(defn- tax-subtotal-el
  [{:keys [rate taxable tax category] :or {category "S"}}
   currency precision]
  (xml/element
   ::cac/TaxSubtotal {}
   (amount-el ::cbc/TaxableAmount taxable currency precision)
   (amount-el ::cbc/TaxAmount tax currency precision)
   (tax-category-el category rate)))

(defn- tax-total-el
  [tax-subtotals total-tax currency precision]
  (xml/element
   ::cac/TaxTotal {}
   (amount-el ::cbc/TaxAmount total-tax currency precision)
   (apply concat
          [(map #(tax-subtotal-el % currency precision) tax-subtotals)])))

;; ============================================================================
;; Legal monetary total
;; ============================================================================

(defn- monetary-totals-el
  [{:keys [net tax gross]} currency precision]
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
  "Build a UBL Peppol PINT JP <Invoice> element from an invoice map.

   Input:
     {:invoice/number       string
      :invoice/issue-date   #inst | LocalDate | YYYY-MM-DD string
      :invoice/due-date     same                          (optional)
      :invoice/currency     \"JPY\"                          (default \"JPY\")
      :invoice/invoice-type-code  \"380\"                  (default 380 = commercial invoice)
      :invoice/supplier {:name … :registration-number … :address {…} :contact {…}}
      :invoice/customer {:name … :registration-number … :address {…}}
      :invoice/lines      [<line-map> …]
      :invoice/tax-totals [{:rate … :taxable Money :tax Money :category \"S\"}]
      :invoice/totals     {:net Money :tax Money :gross Money}}

   Line maps use the :line/* shape consumed by `invoice-line-el`."
  [{:invoice/keys [number issue-date due-date currency invoice-type-code
                   supplier customer lines tax-totals totals]
    :or {currency "JPY"
         invoice-type-code "380"}}]
  (let [precision (if (= currency "JPY") 0 2)
        total-tax (:tax totals)
        subtotals tax-totals]
    (xml/element
     ::inv/Invoice {}
     (xml/element ::cbc/CustomizationID {} customization-id)
     (xml/element ::cbc/ProfileID {} profile-id)
     (xml/element ::cbc/ID {} number)
     (xml/element ::cbc/IssueDate {} (fmt-iso-date issue-date))
     (when due-date
       (xml/element ::cbc/DueDate {} (fmt-iso-date due-date)))
     (xml/element ::cbc/InvoiceTypeCode {} invoice-type-code)
     (xml/element ::cbc/DocumentCurrencyCode {} currency)
     (party-el ::cac/AccountingSupplierParty supplier)
     (party-el ::cac/AccountingCustomerParty customer)
     (tax-total-el subtotals total-tax currency precision)
     (monetary-totals-el totals currency precision)
     (map #(invoice-line-el % currency precision) lines))))

(defn emit-string
  "Render an invoice-element to an XML string."
  [el]
  (xml/emit-str el))

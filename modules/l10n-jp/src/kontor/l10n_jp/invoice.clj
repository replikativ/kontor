(ns kontor.l10n-jp.invoice
  "Japan Qualified Invoice System (QIS / 適格請求書) — in force since
   2023-10-01.

   Under QIS, a seller must register with the NTA as a Qualified
   Invoice Issuer (適格請求書発行事業者). The seller receives a 14-
   character registration number formatted as 'T' followed by 13
   digits (e.g. 'T1234567890123'). This number must appear on every
   invoice the seller issues; without it, the buyer cannot claim
   input consumption-tax credit. (Transitional 80%/50% input credit
   allowed from non-registered suppliers until Sep 2026 / Sep 2029
   respectively; 0% thereafter.)

   This namespace provides:
     - registration-number validation (T + 13 digits)
     - the per-line invoice shape JP buyers expect (rate breakdown
       per consumption-tax category)

   Per ADR-018 there is no clearance-token flow: NTA does not pre-
   approve invoices. The invoice flows directly from seller to buyer
   (or through Peppol). The :transaction goes :draft → :posted
   directly."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Registration number validation
;; ============================================================================

(def ^:private registration-pattern #"^T\d{13}$")

(defn registration-number-valid?
  "True iff `s` matches the NTA-issued T-prefix registration format."
  [s]
  (boolean (and (string? s) (re-matches registration-pattern s))))

(defn assert-registration-number!
  "Throws on invalid registration number, returns the string on success."
  [s]
  (when-not (registration-number-valid? s)
    (throw (ex-info
            "Invalid JP qualified-invoice-issuer registration number"
            {:value s
             :expected-format "T + 13 digits (e.g. T1234567890123)"})))
  s)

;; ============================================================================
;; QIS-mandatory invoice fields
;; ============================================================================

(def required-fields
  "Per NTA's QIS guide, an invoice that allows the buyer to claim
   input tax credit must show:

     1. Issuer's name + registration number
     2. Transaction date
     3. Item descriptions
     4. Taxable amount per rate (separately for 10% and 8%)
     5. Tax-rate marker per item (8%-reduced items must be explicitly
        flagged with a '※' or similar)
     6. Consumption tax amount per rate
     7. Buyer's name

   This vector is the kontor-side checklist; the renderer (Peppol
   PINT JP or PDF / paper) is responsible for laying these out."
  [:issuer/name
   :issuer/registration-number
   :transaction/date
   :buyer/name
   :line-items/by-rate
   :totals/taxable-amount-by-rate
   :totals/tax-amount-by-rate])

(defn validate-qis-fields
  "Return a vector of missing-field complaints; empty when ready to issue.

   `invoice-map` is the user-side invoice shape with QIS-relevant
   fields populated (issuer info, line items split by rate, totals).
   This validator is intentionally permissive — it only checks
   *presence*; downstream layers verify numeric consistency."
  [invoice-map]
  (vec
   (keep (fn [k]
           (let [v (get invoice-map k)]
             (when (or (nil? v)
                       (and (sequential? v) (empty? v))
                       (and (string? v) (str/blank? v)))
               {:field k :issue :missing-or-blank})))
         required-fields)))

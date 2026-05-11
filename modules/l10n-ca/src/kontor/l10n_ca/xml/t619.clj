(ns kontor.l10n-ca.xml.t619
  "T619 — CRA Electronic Transmittal record.

   Verified against the published 2026V4 XSDs in
   `modules/l10n-ca/test/resources/cra/info-returns-xsd-2026/`. The
   complete `TransmitterType` definition is in `complex.xsd`; the
   `Submission` root element wrapping it is defined per-form in
   `T619_<RETURNTYPE>.xsd` (e.g. `T619_T4.xsd` for T4 submissions).

   Required elements (no minOccurs=0 in the XSD):
     TransmitterAccountNumber     bnRPType   '999999999RP9999'
     lang_cd                      languageType  'E' or 'F'
     TransmitterCountryCode       countryType   3-letter ISO (\"CAN\")
     CNTC                         TransmitterContactType

   Optional:
     TransmitterRepID
     sbmt_ref_id    (≤ 8 chars)
     summ_cnt       (int)
     TransmitterName

   TransmitterContactType requires: cntc_nm, cntc_area_cd, cntc_phn_nbr,
   cntc_email_area. cntc_extn_nbr and sec_cntc_email_area are optional.
   Note phone is split: `cntc_area_cd` is the 3-digit NPA; `cntc_phn_nbr`
   matches `\\d{3}-\\d{4}` (the NXX-XXXX local part only).

   No <Address> in the T619 itself — only a country code. The
   transmitter mailing address concept doesn't appear in this schema.

   Per ADR-015 this namespace can be locked for the 2026 filing year
   now that it matches the published schema."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(defn split-phone
  "Split a phone for the `cntc_area_cd` / `cntc_phn_nbr` split that CRA's
   schema requires. Accepts:

     - a 10-digit string (any non-digit chars allowed and stripped):
       '604-555-0100' → ['604' '555-0100']
       '6045550100'   → ['604' '555-0100']
     - a 7-digit string (no area code):
       '5550100'      → [nil '555-0100']
     - a map {:area '604' :number '555-0100'}"
  [phone]
  (cond
    (map? phone) [(:area phone) (:number phone)]
    (string? phone)
    (let [digits (str/replace phone #"\D" "")
          n (count digits)]
      (cond
        (= n 10) [(subs digits 0 3)
                  (str (subs digits 3 6) "-" (subs digits 6 10))]
        (= n 7)  [nil
                  (str (subs digits 0 3) "-" (subs digits 3 7))]
        :else
        (throw (ex-info "Phone must be 7 or 10 digits, or a {:area :number} map"
                        {:phone phone :digits digits :count n}))))
    :else
    (throw (ex-info "Phone must be string or map" {:phone phone}))))

(defn ->element
  "Render a T619 transmittal as a clojure.data.xml element.

   Input (matches `TransmitterType` in complex.xsd):

     {:transmitter/account-number  \"123456789RP0001\"   ; bnRPType — REQUIRED
      :transmitter/rep-id          \"…\"                 ; optional
      :transmitter/name            \"Acme Inc.\"         ; optional Line1Type
      :transmitter/country-code    \"CAN\"               ; REQUIRED, default \"CAN\"
      :transmitter/contact         {:name … :phone …
                                    :phone-ext …          ; optional
                                    :email …              ; REQUIRED
                                    :secondary-email …}   ; optional

      :submission/reference-id     \"unique-id-≤8-chars\" ; optional
      :submission/summary-count    1                       ; optional
      :submission/language         :english}               ; :english :french"
  [{:transmitter/keys [account-number rep-id name country-code contact]
    :submission/keys  [reference-id summary-count language]
    :or {language :english country-code "CAN"}}]
  (let [lang-cd (case language :english "E" :french "F")
        [area phn] (split-phone (:phone contact))
        ;; TransmitterAccountNumberType wraps the BN in a typed child.
        ;; For a business BN (9 digits + 'RP' + 4 digits = 15 chars) use <bn15>.
        ;; For a 9-digit root only, use <bn9>. For NR4 / trust, <nr4>/<trust>.
        acct-el (xml/element
                 :TransmitterAccountNumber {}
                 (xml/element :bn15 {} account-number))
        children
        (remove
         nil?
         [acct-el
          (when rep-id
            (xml/element :TransmitterRepID {}
                         (xml/element :RepID {} rep-id)))
          (when reference-id (xml/element :sbmt_ref_id {} (str reference-id)))
          (when summary-count (xml/element :summ_cnt {} (str summary-count)))
          (xml/element :lang_cd {} lang-cd)
          ;; Line1Type wraps the name in <l1_nm>
          (when name
            (xml/element :TransmitterName {}
                         (xml/element :l1_nm {} name)))
          (xml/element :TransmitterCountryCode {} country-code)
          (xml/element
           :CNTC {}
           (xml/element :cntc_nm {} (:name contact))
           (xml/element :cntc_area_cd {} area)
           (xml/element :cntc_phn_nbr {} phn)
           (when (:phone-ext contact)
             (xml/element :cntc_extn_nbr {} (str (:phone-ext contact))))
           (xml/element :cntc_email_area {} (:email contact))
           (when (:secondary-email contact)
             (xml/element :sec_cntc_email_area {} (:secondary-email contact))))])]
    (apply xml/element :T619 {} children)))

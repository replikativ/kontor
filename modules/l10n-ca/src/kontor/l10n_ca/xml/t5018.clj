(ns kontor.l10n-ca.xml.t5018
  "T5018 — Statement of Contract Payments (XML for CRA IFT).

   Verified against published 2026V4 XSD `t5018.xsd` +
   `T619_T5018.xsd` (`complex.xsd:T5018ReturnType`).

   Submission structure:
     <Submission> (ReturnType)
       <T619>...</T619>
       <Return>
         <T5018>
           <T5018Slip/>+
           <T5018Summary/>
         </T5018>
       </Return>
     </Submission>

   T5018Slip required:
     sin              (1-9 digits — recipient SIN if individual)
     rcpnt_bn         (bnType — recipient BN if corp/partnership)
     rcpnt_tcd        (indicator1-3-4Type: 1=indiv, 3=corp, 4=partnership)
     bn               (bnRZType — PAYER's BN with RZ program account)
     rpt_tcd          (O/A/C)

   Optional: RCPNT_NM (NameType), CORP_PTNRP_NM (Line2Type),
             RCPNT_ADDR (CanadaAddressType), sbctrcr_amt (decimal12Type)."
  (:require [clojure.data.xml :as xml]
            [kontor.l10n-ca.xml.t619 :as t619]
            [kontor.money :as money]))

(defn- fmt-amount [m]
  (.toPlainString
   (.setScale ^java.math.BigDecimal (:amount m)
              2 java.math.RoundingMode/HALF_EVEN)))

(defn- amt-el [tag m]
  (when m (xml/element tag {} (fmt-amount m))))

(defn- name-element [tag {:keys [surname given initial]}]
  (when surname
    (apply xml/element tag {}
           (remove nil?
                   [(xml/element :snm {} surname)
                    (when given (xml/element :gvn_nm {} given))
                    (when initial (xml/element :init {} initial))]))))

(defn- line-element [tag lines]
  ;; Line2Type / Line3Type: l1_nm, l2_nm, l3_nm
  (let [lines (if (sequential? lines) lines [lines])]
    (apply xml/element tag {}
           (remove nil?
                   [(xml/element :l1_nm {} (first lines))
                    (when (second lines) (xml/element :l2_nm {} (second lines)))
                    (when (nth lines 2 nil) (xml/element :l3_nm {} (nth lines 2)))]))))

(defn- date-element
  "Emit a CRA DateType with <dy>/<mo>/<yr> children.

   Accepts: 'YYYY-MM-DD' string OR java.time.LocalDate."
  [tag d]
  (let [ld (cond
             (instance? java.time.LocalDate d) d
             (string? d) (java.time.LocalDate/parse d)
             :else (throw (ex-info "Expected ISO date string or LocalDate"
                                   {:got d})))]
    (xml/element tag {}
                 (xml/element :dy {} (format "%02d" (.getDayOfMonth ld)))
                 (xml/element :mo {} (format "%02d" (.getMonthValue ld)))
                 (xml/element :yr {} (str (.getYear ld))))))

(defn- canada-address-element [tag addr]
  (when addr
    (let [{:keys [line-1 line-2 city province country postal-code]} addr]
      (apply xml/element tag {}
             (remove nil?
                     [(when line-1 (xml/element :addr_l1_txt {} line-1))
                      (when line-2 (xml/element :addr_l2_txt {} line-2))
                      (when city (xml/element :cty_nm {} city))
                      (when province (xml/element :prov_cd {} province))
                      (when country (xml/element :cntry_cd {} country))
                      (when postal-code (xml/element :pstl_cd {} postal-code))])))))

(def ^:private rpt-code         {:original "O" :amended "A" :cancelled "C"})
(def ^:private summary-rpt-code {:original "O" :amended "A" :modified "M"})

(def ^:private rcpnt-type-code
  {:individual "1" :corporation "3" :partnership "4"})

(defn slip->element
  "Render one T5018 slip.

   Input:
     {:t5018/payer-bn        \"123456789RZ0001\"   ; bnRZType (RZ program account)
      :t5018/recipient-sin   \"123456789\"          ; for :individual
      :t5018/recipient-bn    \"987654321RC0001\"    ; for :corporation/:partnership
      :t5018/recipient-type  :individual            ; :individual :corporation :partnership
      :t5018/recipient       {:surname … :given …}  ; optional NameType
      :t5018/corp-name       \"Drywall Pros Ltd.\"  ; optional Line2Type (for corps)
      :t5018/recipient-address {…}                  ; optional
      :t5018/contract-payment-amount  Money         ; optional sbctrcr_amt
      :t5018/report-type     :original}             ; :original :amended :cancelled

   Both rcpnt-bn AND sin are REQUIRED by the schema even though one is
   typically not applicable to a given recipient type. Use a default
   like '000000000' for the non-applicable side."
  [{:t5018/keys [payer-bn recipient-sin recipient-bn recipient-type
                 recipient corp-name recipient-address
                 contract-payment-amount report-type]
    :or {report-type :original
         recipient-type :individual
         recipient-sin "000000000"
         recipient-bn  "000000000RC0001"}}]
  (apply xml/element :T5018Slip {}
         (remove nil?
                 [(name-element :RCPNT_NM recipient)
                  (xml/element :sin {} recipient-sin)
                  (xml/element :rcpnt_bn {} recipient-bn)
                  (when corp-name (line-element :CORP_PTNRP_NM corp-name))
                  (xml/element :rcpnt_tcd {} (rcpnt-type-code recipient-type))
                  (canada-address-element :RCPNT_ADDR recipient-address)
                  (xml/element :bn {} payer-bn)
                  (amt-el :sbctrcr_amt contract-payment-amount)
                  (xml/element :rpt_tcd {} (rpt-code report-type))])))

(defn summary->element
  "Render a T5018Summary."
  [{:t5018-summary/keys [payer-bn payer-name payer-address contact
                         period-end report-type]
    :or {report-type :original}}
   slips]
  (let [total (reduce money/add (money/zero :CAD)
                      (map :t5018/contract-payment-amount slips))
        [area phn] (t619/split-phone (:phone contact))]
    (apply xml/element :T5018Summary {}
           (remove nil?
                   [(xml/element :bn {} payer-bn)
                    (line-element :PAYR_NM payer-name)
                    (canada-address-element :PAYR_ADDR payer-address)
                    (apply xml/element :CNTC {}
                           (remove nil?
                                   [(xml/element :cntc_nm {} (:name contact))
                                    (xml/element :cntc_area_cd {} area)
                                    (xml/element :cntc_phn_nbr {} phn)
                                    (when (:phone-ext contact)
                                      (xml/element :cntc_extn_nbr {} (str (:phone-ext contact))))]))
                    (date-element :PRD_END_DT period-end)
                    (xml/element :slp_cnt {} (str (count slips)))
                    (amt-el :tot_sbctrcr_amt total)
                    (xml/element :rpt_tcd {} (summary-rpt-code report-type))]))))

(defn submission
  "Build a complete CRA IFT submission for T5018."
  [{:keys [t619 t5018-summary slips]}]
  (xml/element :Submission {}
               (t619/->element t619)
               (xml/element
                :Return {}
                (apply xml/element :T5018 {}
                       (concat (map slip->element slips)
                               [(summary->element t5018-summary slips)])))))

(defn emit-string [submission-element]
  (xml/emit-str submission-element))

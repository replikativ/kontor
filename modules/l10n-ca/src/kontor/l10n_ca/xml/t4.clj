(ns kontor.l10n-ca.xml.t4
  "T4 — Statement of Remuneration Paid (XML for CRA Internet File Transfer).

   Verified against published 2026V4 XSDs (`t4.xsd`, `T619_T4.xsd`,
   `complex.xsd`) in `modules/l10n-ca/test/resources/cra/`.

   Root element wrapping (per `T619_T4.xsd`):

     <Submission> (type ReturnType)
       <T619>...transmitter...</T619>
       <Return>           (T4ReturnChoiceType, can repeat)
         <T4>             (T4ReturnType)
           <T4Slip/>+     (one or more, MUST precede summary)
           <T4Summary/>   (exactly one)
         </T4>
       </Return>
     </Submission>

   T4Slip required elements (per `T4SlipType` in t4.xsd):
     EMPE_NM         NameType   (snm required; gvn_nm + init optional)
     sin             sinType    (1-9 digits)
     bn              bnRPType   ('999999999RP9999')
     cpp_qpp_xmpt_cd indicator0-1Type  ('0' or '1')
     ei_xmpt_cd      indicator0-1Type
     rpt_tcd         slipDataType  ('O' Original, 'A' Amended, 'C' Cancelled)
     empt_prov_cd    provinceType  ('BC', 'ON', etc.)
     T4_AMT          T4AmtType  (optional, but virtually always present)

   T4_AMT amount element names (snake_case, all optional):
     empt_incamt     (box 14 employment income)
     cpp_cntrb_amt   (box 16 employee CPP base)
     cppe_cntrb_amt  (box 16A CPP2 - new in 2024)
     qpp_cntrb_amt   (box 17 QPP - Quebec only)
     qppe_cntrb_amt  (box 17A QPP2 - Quebec only)
     empe_eip_amt    (box 18 EI premiums)
     rpp_cntrb_amt   (box 20 RPP contributions)
     itx_ddct_amt    (box 22 income tax deducted)
     ei_insu_ern_amt (box 24 EI insurable earnings)
     cpp_qpp_ern_amt (box 26 CPP/QPP pensionable earnings)
     unn_dues_amt    (box 44 union dues)
     chrty_dons_amt  (box 46 charitable donations)
     padj_amt        (box 52 pension adjustment)
     prov_pip_amt    (box 55 PIP premiums - Quebec)
     prov_insu_ern_amt (box 56 PIP insurable earnings - Quebec)

   T4Summary required elements:
     bn              employer BN
     EMPR_NM         employer name (Line3Type — up to 3 lines)
     CNTC            ContactType2 (cntc_nm + cntc_area_cd + cntc_phn_nbr,
                                   optional cntc_extn_nbr; NO email field)
     tx_yr           4-digit year
     slp_cnt         number of slips
     rpt_tcd         otherDataType ('O' / 'A' / 'M' — NO 'C' on summary)

   Per ADR-015, this namespace tracks the 2026V4 schema family.
   Validate any production output against the XSD bundle in
   `test/resources/cra/info-returns-xsd-2026/` using
   `kontor.l10n-ca.xml.validation/validate!`."
  (:require [clojure.data.xml :as xml]
            [kontor.l10n-ca.xml.t619 :as t619]
            [kontor.money :as money]))

(defn- fmt-amount [m]
  (.toPlainString
   (.setScale ^java.math.BigDecimal (:amount m)
              2 java.math.RoundingMode/HALF_EVEN)))

(defn- amt-el
  "Emit <tag>value</tag> only if the Money is non-zero. The schema
   allows omission (minOccurs=0); we omit zero amounts for clarity."
  [tag m]
  (when (and m (not (money/zero? m)))
    (xml/element tag {} (fmt-amount m))))

(defn- name-element [{:keys [surname given initial]}]
  (apply xml/element :EMPE_NM {}
         (remove nil?
                 [(xml/element :snm {} surname)
                  (when given (xml/element :gvn_nm {} given))
                  (when initial (xml/element :init {} initial))])))

(defn- empr-name-element [name]
  ;; EMPR_NM is Line3Type: 1-3 lines named l1_nm / l2_nm / l3_nm
  (let [lines (if (vector? name) name [name])]
    (apply xml/element :EMPR_NM {}
           (remove nil?
                   [(xml/element :l1_nm {} (first lines))
                    (when (second lines) (xml/element :l2_nm {} (second lines)))
                    (when (nth lines 2 nil) (xml/element :l3_nm {} (nth lines 2)))]))))

(defn- canada-address-element [tag {:keys [line-1 line-2 city province
                                           country postal-code]}]
  (apply xml/element tag {}
         (remove nil?
                 [(when line-1 (xml/element :addr_l1_txt {} line-1))
                  (when line-2 (xml/element :addr_l2_txt {} line-2))
                  (when city (xml/element :cty_nm {} city))
                  (when province (xml/element :prov_cd {} province))
                  (when country (xml/element :cntry_cd {} country))
                  (when postal-code (xml/element :pstl_cd {} postal-code))])))

(defn- amounts-element [{:keys [box-14 box-16 box-16a box-17 box-17a
                                box-18 box-20 box-22 box-24 box-26
                                box-44 box-46 box-52 box-55 box-56]
                         :as boxes}]
  (when (and boxes (some some? (vals boxes)))
    (apply xml/element :T4_AMT {}
           (remove nil?
                   [(amt-el :empt_incamt       box-14)
                    (amt-el :cpp_cntrb_amt     box-16)
                    (amt-el :cppe_cntrb_amt    box-16a)
                    (amt-el :qpp_cntrb_amt     box-17)
                    (amt-el :qppe_cntrb_amt    box-17a)
                    (amt-el :empe_eip_amt      box-18)
                    (amt-el :rpp_cntrb_amt     box-20)
                    (amt-el :itx_ddct_amt      box-22)
                    (amt-el :ei_insu_ern_amt   box-24)
                    (amt-el :cpp_qpp_ern_amt   box-26)
                    (amt-el :unn_dues_amt      box-44)
                    (amt-el :chrty_dons_amt    box-46)
                    (amt-el :padj_amt          box-52)
                    (amt-el :prov_pip_amt      box-55)
                    (amt-el :prov_insu_ern_amt box-56)]))))

(def ^:private rpt-code
  {:original "O" :amended "A" :cancelled "C"})

(def ^:private summary-rpt-code
  ;; T4Summary's rpt_tcd is otherDataType — no cancellation code
  {:original "O" :amended "A" :modified "M"})

(defn slip->element
  "Render one T4 slip.

   Input shape:
     {:t4/employer-bn      \"123456789RP0001\"
      :t4/sin              \"123456789\"
      :t4/employee         {:surname … :given … :initial …}
      :t4/employee-address {:line-1 … :city … :province \"BC\" :postal-code …}
      :t4/province-of-employment \"BC\"
      :t4/cpp-qpp-exempt?  false              ; default false
      :t4/ei-exempt?       false              ; default false
      :t4/report-type      :original
      :t4/boxes            {:box-14 Money …}}"
  [{:t4/keys [employer-bn sin employee employee-address
              province-of-employment cpp-qpp-exempt? ei-exempt?
              report-type boxes]
    :or {report-type :original}}]
  (apply xml/element :T4Slip {}
         (remove nil?
                 [(name-element employee)
                  (when employee-address
                    (canada-address-element :EMPE_ADDR employee-address))
                  (xml/element :sin {} sin)
                  (xml/element :bn {} employer-bn)
                  (xml/element :cpp_qpp_xmpt_cd {}
                               (if cpp-qpp-exempt? "1" "0"))
                  (xml/element :ei_xmpt_cd {}
                               (if ei-exempt? "1" "0"))
                  (xml/element :rpt_tcd {} (rpt-code report-type))
                  (xml/element :empt_prov_cd {} province-of-employment)
                  (amounts-element boxes)])))

(defn- summary-totals-element [slips]
  (let [boxes-of (fn [s] (:t4/boxes s))
        sum-box  (fn [box]
                   (reduce money/add (money/zero :CAD)
                           (map #(get (boxes-of %) box (money/zero :CAD))
                                slips)))]
    (apply xml/element :T4_TAMT {}
           (remove nil?
                   [(amt-el :tot_empt_incamt   (sum-box :box-14))
                    (amt-el :tot_empe_cpp_amt  (sum-box :box-16))
                    (amt-el :tot_empe_cppe_amt (sum-box :box-16a))
                    (amt-el :tot_empe_eip_amt  (sum-box :box-18))
                    (amt-el :tot_rpp_cntrb_amt (sum-box :box-20))
                    (amt-el :tot_itx_ddct_amt  (sum-box :box-22))
                    (amt-el :tot_padj_amt      (sum-box :box-52))]))))

(defn summary->element
  "Render a T4Summary.

   Input:
     {:t4-summary/employer-bn       \"123456789RP0001\"
      :t4-summary/employer-name     \"Acme Inc.\"    ; or vector of up to 3 strings
      :t4-summary/employer-address  {…}              ; optional CanadaAddressType
      :t4-summary/contact           {:name … :phone …
                                     :phone-ext …}   ; cntc_extn_nbr optional
      :t4-summary/tax-year          2024
      :t4-summary/report-type       :original}"
  [{:t4-summary/keys [employer-bn employer-name employer-address
                      contact tax-year report-type]
    :or {report-type :original}}
   slips]
  (let [[area phn] (t619/split-phone (:phone contact))]
    (apply xml/element :T4Summary {}
           (remove nil?
                   [(xml/element :bn {} employer-bn)
                    (empr-name-element employer-name)
                    (when employer-address
                      (canada-address-element :EMPR_ADDR employer-address))
                    (apply xml/element :CNTC {}
                           (remove nil?
                                   [(xml/element :cntc_nm {} (:name contact))
                                    (xml/element :cntc_area_cd {} area)
                                    (xml/element :cntc_phn_nbr {} phn)
                                    (when (:phone-ext contact)
                                      (xml/element :cntc_extn_nbr {}
                                                   (str (:phone-ext contact))))]))
                    (xml/element :tx_yr {} (str tax-year))
                    (xml/element :slp_cnt {} (str (count slips)))
                    (xml/element :rpt_tcd {} (summary-rpt-code report-type))
                    (summary-totals-element slips)]))))

(defn submission
  "Build a complete CRA IFT submission containing T619 + one T4 return.

   Input:
     {:t619       …    ; transmitter map (see xml/t619.clj)
      :t4-summary …    ; summary map
      :slips      [{…} …]}

   Returns a clojure.data.xml element. Use `emit-string` to serialize.
   Validate the output against `T619_T4.xsd` using
   `kontor.l10n-ca.xml.validation/validate!`."
  [{:keys [t619 t4-summary slips]}]
  (xml/element
   :Submission {}
   (t619/->element t619)
   (xml/element
    :Return {}
    (apply xml/element :T4 {}
           (concat (map slip->element slips)
                   [(summary->element t4-summary slips)])))))

(defn emit-string
  "Render a submission element to an XML string (UTF-8)."
  [submission-element]
  (xml/emit-str submission-element))

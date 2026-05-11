(ns kontor.l10n-ca.xml.t5
  "T5 — Statement of Investment Income (XML for CRA IFT).

   Verified against published 2026V4 XSD `t5.xsd` + `T619_T5.xsd`.

   T5SlipType has many required fields. Some are awkward for a small
   non-FI issuer (e.g. `rcpnt_fi_br_nbr` is the recipient's financial
   institution branch). For non-FI issuers, CRA convention is to pad
   with zero / blank-where-allowed values. The data model exposes all
   required fields so the caller has full control.

   Element-name highlights (snake_case):
     T5_AMT contents (selected):
       actl_elg_dvamt    box 24  actual amount of eligible dividends
       tx_elg_dvnd_pamt  box 25  taxable amount of eligible dividends
       enhn_dvtc_amt     box 26  dividend tax credit (eligible)
       actl_dvnd_amt     box 10  actual amount of non-eligible dividends
       tx_dvnd_amt       box 11  taxable amount of non-eligible dividends
       dvnd_tx_cr_amt    box 12  dividend tax credit (non-eligible)
       cdn_int_amt       box 13  Canadian interest
       cgain_dvnd_amt    box 18  capital gain dividends
       oth_cdn_incamt    box 14  other income from Canadian sources
       fgn_incamt        box 15  foreign income
       fgn_tx_pay_amt    box 16  foreign tax paid"
  (:require [clojure.data.xml :as xml]
            [kontor.l10n-ca.xml.t619 :as t619]
            [kontor.money :as money]))

(defn- fmt-amount [m]
  (.toPlainString
   (.setScale ^java.math.BigDecimal (:amount m)
              2 java.math.RoundingMode/HALF_EVEN)))

(defn- amt-el [tag m]
  (when (and m (not (money/zero? m)))
    (xml/element tag {} (fmt-amount m))))

(defn- recipient-name [{:keys [surname given initial]}]
  (when surname
    (apply xml/element :RCPNT_NM {}
           (remove nil?
                   [(xml/element :snm {} surname)
                    (when given (xml/element :gvn_nm {} given))
                    (when initial (xml/element :init {} initial))]))))

(defn- canada-address [tag {:keys [line-1 line-2 city province country postal-code]}]
  (apply xml/element tag {}
         (remove nil?
                 [(when line-1 (xml/element :addr_l1_txt {} line-1))
                  (when line-2 (xml/element :addr_l2_txt {} line-2))
                  (when city (xml/element :cty_nm {} city))
                  (when province (xml/element :prov_cd {} province))
                  (when country (xml/element :cntry_cd {} country))
                  (when postal-code (xml/element :pstl_cd {} postal-code))])))

(defn- summary-address
  "T5SummaryAddressType requires addr_l1_txt and pstl_cd (not optional)."
  [{:keys [line-1 line-2 city province country postal-code]}]
  (apply xml/element :FILR_ADDR {}
         (remove nil?
                 [(xml/element :addr_l1_txt {} (or line-1 ""))
                  (when line-2 (xml/element :addr_l2_txt {} line-2))
                  (when city (xml/element :cty_nm {} city))
                  (when province (xml/element :prov_cd {} province))
                  (when country (xml/element :cntry_cd {} country))
                  (xml/element :pstl_cd {} (or postal-code ""))])))

(defn- bus-name [name]
  (when name (xml/element :BUS_NM {} (xml/element :l1_nm {} name))))

(defn- amounts-element [{:as boxes
                         :keys [box-10 box-11 box-12 box-13 box-14 box-15
                                box-16 box-18 box-24 box-25 box-26]}]
  (when (and boxes (some some? (vals boxes)))
    (apply xml/element :T5_AMT {}
           (remove nil?
                   [(amt-el :actl_elg_dvamt   box-24)
                    (amt-el :actl_dvnd_amt    box-10)
                    (amt-el :tx_elg_dvnd_pamt box-25)
                    (amt-el :tx_dvnd_amt      box-11)
                    (amt-el :enhn_dvtc_amt    box-26)
                    (amt-el :dvnd_tx_cr_amt   box-12)
                    (amt-el :cdn_int_amt      box-13)
                    (amt-el :oth_cdn_incamt   box-14)
                    (amt-el :fgn_incamt       box-15)
                    (amt-el :fgn_tx_pay_amt   box-16)
                    (amt-el :cgain_dvnd_amt   box-18)]))))

(def ^:private rpt-code         {:original "O" :amended "A" :cancelled "C"})
(def ^:private summary-rpt-code {:original "O" :amended "A" :modified "M"})

(def ^:private recipient-type-code
  ;; indicator1-5Type — 1=individual, 2=joint, 3=corporation, 4=other,
  ;; 5=government
  {:individual "1" :joint "2" :corporation "3" :other "4" :government "5"})

(defn slip->element
  "Render one T5 slip.

   Input:
     {:t5/payer-bn        \"123456789RZ0001\"  ; payer's BN with RZ
      :t5/recipient-sin   \"123456789\"
      :t5/recipient-bn    \"000000000000000\"  ; bn9AccntNbr15; zeros if N/A
      :t5/recipient-trust-account  \"T00000000\" ; trustType; zeros if N/A
      :t5/recipient-fi-branch      \"00000000\"  ; alphaNumeric8Type
      :t5/recipient-fi-account     \"000000000000\" ; char12Type
      :t5/recipient-type  :individual            ; or :corporation, etc.
      :t5/recipient       {:surname … :given …}  ; optional NameType
      :t5/business-name   \"…\"                  ; optional Line2Type
      :t5/recipient-address {…}                  ; optional
      :t5/foreign-currency-indicator \"USD\"     ; optional char3 alpha
      :t5/report-type     :original
      :t5/boxes           {:box-13 Money …}}"
  [{:t5/keys [payer-bn recipient-sin recipient-bn recipient-trust-account
              recipient-fi-branch recipient-fi-account recipient-type
              recipient business-name recipient-address
              foreign-currency-indicator report-type boxes]
    :or {report-type :original
         recipient-type :individual
         recipient-bn            "000000000"        ; 9-digit BN root (bnRootType)
         recipient-trust-account "T00000000"
         recipient-fi-branch     "00000000"
         recipient-fi-account    "000000000000"}}]
  (apply xml/element :T5Slip {}
         (remove nil?
                 [(recipient-name recipient)
                  (xml/element :sin {} recipient-sin)
                  (xml/element :slp_rcpnt_bn {} recipient-bn)
                  (xml/element :rcpnt_tr_acct_nbr {} recipient-trust-account)
                  (bus-name business-name)
                  (when recipient-address (canada-address :RCPNT_ADDR recipient-address))
                  (xml/element :bn {} payer-bn)
                  (xml/element :rcpnt_fi_br_nbr {} recipient-fi-branch)
                  (xml/element :rcpnt_fi_acct_nbr {} recipient-fi-account)
                  (xml/element :rpt_tcd {} (rpt-code report-type))
                  (xml/element :rcpnt_tcd {} (recipient-type-code recipient-type))
                  (when foreign-currency-indicator
                    (xml/element :fgn_crcy_ind {} foreign-currency-indicator))
                  (amounts-element boxes)])))

(defn- filer-name [name]
  ;; Line3Type
  (let [lines (if (sequential? name) name [name])]
    (apply xml/element :FILR_NM {}
           (remove nil?
                   [(xml/element :l1_nm {} (first lines))
                    (when (second lines) (xml/element :l2_nm {} (second lines)))
                    (when (nth lines 2 nil) (xml/element :l3_nm {} (nth lines 2)))]))))

(defn summary->element
  "Render a T5Summary.
   Required: bn, FILR_NM, FILR_ADDR, CNTC, filr_fi_br_nbr, tx_yr, slp_cnt, rpt_tcd."
  [{:t5-summary/keys [payer-bn filer-name filer-address
                      contact filer-fi-branch tax-year report-type]
    :or {report-type :original
         filer-fi-branch "00000000"}}
   slips]
  (let [[area phn] (t619/split-phone (:phone contact))]
    (apply xml/element :T5Summary {}
           (remove nil?
                   [(xml/element :bn {} payer-bn)
                    (#'kontor.l10n-ca.xml.t5/filer-name filer-name)
                    (summary-address filer-address)
                    (apply xml/element :CNTC {}
                           (remove nil?
                                   [(xml/element :cntc_nm {} (:name contact))
                                    (xml/element :cntc_area_cd {} area)
                                    (xml/element :cntc_phn_nbr {} phn)
                                    (when (:phone-ext contact)
                                      (xml/element :cntc_extn_nbr {} (str (:phone-ext contact))))]))
                    (xml/element :filr_fi_br_nbr {} filer-fi-branch)
                    (xml/element :tx_yr {} (str tax-year))
                    (xml/element :slp_cnt {} (str (count slips)))
                    (xml/element :rpt_tcd {} (summary-rpt-code report-type))]))))

(defn submission
  [{:keys [t619 t5-summary slips]}]
  (xml/element :Submission {}
               (t619/->element t619)
               (xml/element
                :Return {}
                (apply xml/element :T5 {}
                       (concat (map slip->element slips)
                               [(summary->element t5-summary slips)])))))

(defn emit-string [submission-element]
  (xml/emit-str submission-element))

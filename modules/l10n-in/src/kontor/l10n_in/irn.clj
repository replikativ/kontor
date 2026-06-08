(ns kontor.l10n-in.irn
  "Indian e-invoice (IRN) payload builder.

   Per ADR-018 + ADR-024, the kernel constructs the canonical JSON
   payload that gets submitted to the NIC IRP portal. Network
   submission itself lives in a partner adapter (e.g.
   `kontor-l10n-in-irp`) because credentials + GSP-mediated transport
   are out of the kernel's scope per ADR-005 / ADR-010.

   ## Schema

   NIC schema v1.1 (the literal string `Version: \"1.1\"` in the JSON).
   ~132 fields total; 28 mandatory + 18 conditional-mandatory. This
   namespace ships a structural emitter — the caller passes a typed
   map, we produce the JSON in NIC's expected shape.

   ## IRN computation

   IRN = SHA-256 of
     `<supplier-GSTIN> + '_' + <doc-no> + '_' + <financial-year> + '_' + <doc-type>`
   (with documented separators; see NIC documentation). The hash is
   computed CLIENT-SIDE and submitted with the request; the IRP
   echoes it back in the response if it accepts the invoice.

   ## ADR-024 interaction

   The IRN attestation is one of (potentially several) attestations
   on a transaction. For goods movement it's the *first* attestation;
   the e-way bill Part A (see `kontor.l10n-in.ewb`) then carries
   `:kontor.attestation/depends-on` referencing the IRN."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

;; ============================================================================
;; IRN hash
;; ============================================================================

(defn financial-year
  "Indian financial year for a given date.
   FY runs April 1 → March 31. A date in April 2026 is FY 2026-27;
   January 2026 is FY 2025-26."
  ([^java.util.Date d]
   (let [ld (-> d .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDate)
         y  (.getYear ld)
         m  (.getMonthValue ld)
         start (if (>= m 4) y (dec y))
         end-yy (mod (inc start) 100)]
     (format "%d-%02d" start end-yy))))

(defn- sha256-hex
  ^String [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s StandardCharsets/UTF_8))
        sb (StringBuilder.)]
    (doseq [b bs]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (.toString sb)))

(defn compute-irn
  "Compute the IRN as a 64-char lowercase hex SHA-256 hash.

   Inputs:
     :supplier-gstin  String (15 chars)
     :doc-no          String — the invoice number as it appears on the doc
     :doc-date        java.util.Date — used to derive financial-year
     :doc-type        one of \"INV\" \"CRN\" \"DBN\"

   Returns the 64-char hex string."
  [{:keys [supplier-gstin doc-no doc-date doc-type]}]
  (let [fy (financial-year doc-date)
        s  (str supplier-gstin "_" doc-no "_" fy "_" doc-type)]
    (sha256-hex s)))

;; ============================================================================
;; Payload builder
;; ============================================================================

(defn- supply-type
  "Map kontor-side keyword to NIC's `SupTyp`:
     :b2b      → B2B
     :sez-wp   → SEZWP  (SEZ With Payment of Tax)
     :sez-wop  → SEZWOP (SEZ Without Payment)
     :exp-wp   → EXPWP  (Export With Payment)
     :exp-wop  → EXPWOP (Export Without Payment)
     :deemed-exp → DEXP"
  [k]
  (case k
    :b2b      "B2B"
    :sez-wp   "SEZWP"
    :sez-wop  "SEZWOP"
    :exp-wp   "EXPWP"
    :exp-wop  "EXPWOP"
    :deemed-exp "DEXP"
    (throw (ex-info "Unknown supply-type" {:value k}))))

(defn- doc-type-code
  [k]
  (case k
    :inv "INV" :crn "CRN" :dbn "DBN"
    (throw (ex-info "Unknown doc-type" {:value k}))))

(defn- ddmmyyyy
  ^String [^java.util.Date d]
  (let [ld (-> d .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
    (format "%02d/%02d/%04d" (.getDayOfMonth ld) (.getMonthValue ld) (.getYear ld))))

(defn- fmt-amt
  "Format a monetary amount per NIC's expectation: number with up to 2
   decimal places, no thousand separator."
  [n]
  (if (instance? java.math.BigDecimal n)
    (-> ^java.math.BigDecimal n
        (.setScale 2 java.math.RoundingMode/HALF_EVEN))
    (bigdec n)))

(defn- party-block
  [{:keys [gstin legal-name trade-name addr1 addr2 loc pin state phone email]}]
  (cond-> {"Gstin"  gstin
           "LglNm"  legal-name
           "Addr1"  addr1
           "Loc"    loc
           "Pin"    (Integer/parseInt (str pin))
           "Stcd"   state}
    trade-name (assoc "TrdNm" trade-name)
    addr2 (assoc "Addr2" addr2)
    phone (assoc "Ph" phone)
    email (assoc "Em" email)))

(defn- item-block
  [{:keys [sl-no prd-desc is-service hsn-code bch-nm bar-cde qty
           free-qty unit unit-price tot-amt discount pre-tax-val
           assess-amt gst-rate igst-amt cgst-amt sgst-amt cess-rate
           cess-amt cess-non-advl other-charge tot-item-val
           ord-line-ref orgn-cntry prd-srl-no]
    :or {is-service "N" free-qty 0M discount 0M other-charge 0M
         cess-rate 0M cess-amt 0M cess-non-advl 0M
         pre-tax-val 0M}}]
  (cond-> {"SlNo"       (str sl-no)
           "PrdDesc"    prd-desc
           "IsServc"    is-service
           "HsnCd"      hsn-code
           "Qty"        (fmt-amt qty)
           "FreeQty"    (fmt-amt free-qty)
           "Unit"       unit
           "UnitPrice"  (fmt-amt unit-price)
           "TotAmt"     (fmt-amt tot-amt)
           "Discount"   (fmt-amt discount)
           "PreTaxVal"  (fmt-amt pre-tax-val)
           "AssAmt"     (fmt-amt assess-amt)
           "GstRt"      (fmt-amt gst-rate)
           "IgstAmt"    (fmt-amt (or igst-amt 0M))
           "CgstAmt"    (fmt-amt (or cgst-amt 0M))
           "SgstAmt"    (fmt-amt (or sgst-amt 0M))
           "CesRt"      (fmt-amt cess-rate)
           "CesAmt"     (fmt-amt cess-amt)
           "CesNonAdvlAmt" (fmt-amt cess-non-advl)
           "OthChrg"    (fmt-amt other-charge)
           "TotItemVal" (fmt-amt tot-item-val)}
    bch-nm (assoc "BchDtls" {"Nm" bch-nm})
    bar-cde (assoc "BarCde" bar-cde)
    ord-line-ref (assoc "OrdLineRef" ord-line-ref)
    orgn-cntry (assoc "OrgCntry" orgn-cntry)
    prd-srl-no (assoc "PrdSlNo" prd-srl-no)))

(defn build-payload
  "Build the IRN JSON payload as a Clojure map (Version 1.1).

   Input shape:
     {:tran      {:tax-scheme        \"GST\"          ; only :gst valid
                  :supply-type       :b2b             ; see supply-type
                  :reverse-charge?   false
                  :igst-on-intra?    false
                  :ecom-gstin        nil}
      :doc       {:no \"INV-2026-0001\"
                  :date #inst \"2026-05-11\"
                  :type :inv}                          ; :inv :crn :dbn
      :seller    {…party-block fields…}
      :buyer     {…party-block fields + :pos \"29\"}
      :dispatch  {…party-block fields…}                ; optional
      :ship-to   {…party-block fields…}                ; optional
      :items     [{…item-block fields…} …]
      :val       {:ass-val      10000.00M
                  :cgst-val     0M
                  :sgst-val     0M
                  :igst-val     1800.00M
                  :ces-val      0M
                  :st-ces-val   0M
                  :discount     0M
                  :rnd-off-amt  0M
                  :tot-inv-val  11800.00M
                  :tot-inv-val-fc nil}                 ; foreign currency total
      :exp       {…export details…}                    ; conditional
      :pay       {…payment details…}}                  ; optional

   Returns a Clojure map ready to JSON-serialize via `payload-json`."
  [{:keys [tran doc seller buyer dispatch ship-to items val exp pay ewb]}]
  (cond->
   {"Version" "1.1"
    "TranDtls" (cond->
                {"TaxSch"      (str/upper-case (name (:tax-scheme tran :gst)))
                 "SupTyp"      (supply-type (:supply-type tran))
                 "RegRev"      (if (:reverse-charge? tran) "Y" "N")
                 "IgstOnIntra" (if (:igst-on-intra? tran) "Y" "N")}
                 (:ecom-gstin tran) (assoc "EcmGstin" (:ecom-gstin tran)))
    "DocDtls" {"Typ" (doc-type-code (:type doc))
               "No"  (:no doc)
               "Dt"  (ddmmyyyy (:date doc))}
    "SellerDtls" (party-block seller)
    "BuyerDtls"  (cond-> (party-block buyer)
                   (:pos buyer) (assoc "Pos" (:pos buyer)))
    "ItemList"   (mapv item-block items)
    "ValDtls"    {"AssVal"     (fmt-amt (:ass-val val))
                  "CgstVal"    (fmt-amt (or (:cgst-val val) 0M))
                  "SgstVal"    (fmt-amt (or (:sgst-val val) 0M))
                  "IgstVal"    (fmt-amt (or (:igst-val val) 0M))
                  "CesVal"     (fmt-amt (or (:ces-val val) 0M))
                  "StCesVal"   (fmt-amt (or (:st-ces-val val) 0M))
                  "Discount"   (fmt-amt (or (:discount val) 0M))
                  "OthChrg"    (fmt-amt (or (:oth-chrg val) 0M))
                  "RndOffAmt"  (fmt-amt (or (:rnd-off-amt val) 0M))
                  "TotInvVal"  (fmt-amt (:tot-inv-val val))}}
    dispatch (assoc "DispDtls" (party-block dispatch))
    ship-to  (assoc "ShipDtls" (party-block ship-to))
    exp      (assoc "ExpDtls" exp)
    pay      (assoc "PayDtls" pay)
    ewb      (assoc "EwbDtls" ewb)))

(defn payload-json
  "Serialize a payload map to JSON string (NIC's expected wire format)."
  ^String [payload-map]
  (json/write-str payload-map :escape-slash false))

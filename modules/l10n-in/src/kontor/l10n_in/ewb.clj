(ns kontor.l10n-in.ewb
  "E-way bill (EWB) payload builder + validity computation.

   Per ADR-024, the EWB is a *separate* attestation from the IRN:
   different document, different portal (ewaybillgst.gov.in),
   different validity window. Part A derives from the invoice (and
   may be auto-pulled from the IRN); Part B carries vehicle /
   transporter info added when the truck rolls. The two are coupled
   via `:kontor.attestation/depends-on` in kontor's storage model.

   ## Validity window

   Per Rule 138, CGST Rules (amended 2020-12-22):
     - Regular cargo: 1 day per 200 km (or part thereof)
     - Over Dimensional Cargo (ODC): 1 day per 20 km

   The clock starts when Part B is generated (the truck actually
   rolling). Validity is capped at 360 days total per a 2025-01-01
   GSTN advisory; EWB cannot be generated more than 180 days after
   the invoice date."
  (:require [clojure.string :as str])
  (:import [java.time Instant Duration]))

;; ============================================================================
;; Validity computation
;; ============================================================================

(def regular-km-per-day  200)
(def odc-km-per-day      20)

(defn validity-days
  "Days of validity for a given consignment distance in km.

   Algorithm: 1 day per (regular-km-per-day | odc-km-per-day) km
   OR PART THEREOF. So 1 km of regular cargo = 1 day; 200 km = 1 day;
   201 km = 2 days; 400 km = 2 days; 401 km = 3 days."
  ([distance-km] (validity-days distance-km :regular))
  ([distance-km cargo-type]
   (let [per-day (case cargo-type
                   :regular regular-km-per-day
                   :odc     odc-km-per-day
                   (throw (ex-info "Unknown cargo-type"
                                   {:value cargo-type
                                    :supported #{:regular :odc}})))
         days (quot distance-km per-day)
         remainder (mod distance-km per-day)]
     (if (zero? remainder) days (inc days)))))

(defn validity-window
  "Compute the EWB validity window given Part B issuance instant and
   distance.

   Returns [`valid-from` `valid-until`] — both java.util.Date.

   ## Rule 138 nuance

   The EWB validity is N *days* from Part B; in practice the EWB
   portal computes the expiry as midnight of (start-date + N days).
   The kernel here uses a simple 24-hour-per-day model — consumers
   needing day-boundary semantics override with the portal's computed
   expiry. Document this in the attestation note if it matters."
  ([^java.util.Date part-b-issued-at distance-km]
   (validity-window part-b-issued-at distance-km :regular))
  ([^java.util.Date part-b-issued-at distance-km cargo-type]
   (let [days (validity-days distance-km cargo-type)
         start (.toInstant part-b-issued-at)
         end   (.plus start (Duration/ofDays days))]
     [part-b-issued-at (java.util.Date/from end)])))

;; ============================================================================
;; Payload builder
;; ============================================================================

(defn- supply-type-ewb
  "EWB SupplyType (different vocabulary from IRN's SupTyp):
     :inward  → I
     :outward → O"
  [k]
  (case k :inward "I" :outward "O"
        (throw (ex-info "Unknown EWB supply-type" {:value k}))))

(defn- sub-supply-type
  "EWB SubSupplyType — common values:
     :supply           → 1
     :import           → 2
     :export           → 3
     :job-work         → 4
     :for-own-use      → 5
     :job-work-returns → 6
     :sales-return     → 7
     :others           → 8
     :sku-transfer     → 9
     :cks-d            → 10  (completely knocked down / semi-knocked-down)
     :line-sales       → 11
     :recipient-not-known → 12
     :exhibition       → 13
     :fairs            → 14"
  [k]
  (case k
    :supply 1 :import 2 :export 3 :job-work 4 :for-own-use 5
    :job-work-returns 6 :sales-return 7 :others 8 :sku-transfer 9
    :cks-d 10 :line-sales 11 :recipient-not-known 12
    :exhibition 13 :fairs 14
    (throw (ex-info "Unknown sub-supply-type" {:value k}))))

(defn- doc-type-ewb
  "EWB document-type codes:
     :tax-invoice → INV
     :bill-of-supply → BIL
     :bill-of-entry → BOE
     :delivery-challan → CHL
     :credit-note → CNT
     :others → OTH"
  [k]
  (case k
    :tax-invoice "INV" :bill-of-supply "BIL" :bill-of-entry "BOE"
    :delivery-challan "CHL" :credit-note "CNT" :others "OTH"
    (throw (ex-info "Unknown EWB doc-type" {:value k}))))

(defn- transport-mode
  "Mode-of-transport codes per the EWB schema:
     :road → 1
     :rail → 2
     :air  → 3
     :ship → 4"
  [k]
  (case k :road 1 :rail 2 :air 3 :ship 4
        (throw (ex-info "Unknown transport mode" {:value k}))))

(defn- vehicle-type
  "Vehicle-type code:
     :regular → R
     :odc     → O"
  [k]
  (case k :regular "R" :odc "O"
        (throw (ex-info "Unknown vehicle-type" {:value k}))))

(defn build-part-a
  "Build the EWB Part A payload (header + product details). When the
   underlying IRN exists, callers should set `:irn` so the portal
   auto-fills Part A from the IRN.

   Input:
     {:supply-type      :outward          ; :inward | :outward
      :sub-supply-type  :supply           ; see sub-supply-type
      :doc-type         :tax-invoice
      :doc-no           \"INV-2026-IN-0001\"
      :doc-date         #inst \"2026-05-11\"
      :from {:gstin :legal-name :addr1 :loc :pin :state}
      :to   {:gstin :legal-name :addr1 :loc :pin :state}
      :place-of-supply  \"29\"             ; 2-digit GSTN state code
      :items   [{:prd-desc :hsn-code :qty :unit :tax-val :gst-rate} …]
      :tot-inv-val      11800.00M
      :ass-val          10000.00M
      :transport {:distance-km 400}
      :irn              nil}              ; optional — links Part A

   Returns a Clojure map."
  [{:keys [supply-type doc-type doc-no doc-date
           from to place-of-supply items tot-inv-val ass-val
           transport irn]
    sub-st :sub-supply-type
    :or {sub-st :supply
         doc-type :tax-invoice}}]
  (let [{:keys [distance-km]} transport
        ld (-> doc-date .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDate)
        date-str (format "%02d/%02d/%04d" (.getDayOfMonth ld) (.getMonthValue ld) (.getYear ld))]
    (cond->
     {"supplyType"     (supply-type-ewb supply-type)
      "subSupplyType"  (str (sub-supply-type sub-st))
      "docType"        (doc-type-ewb doc-type)
      "docNo"          doc-no
      "docDate"        date-str
      "fromGstin"      (:gstin from)
      "fromTrdName"    (:legal-name from)
      "fromAddr1"      (:addr1 from)
      "fromPlace"      (:loc from)
      "fromPincode"    (Integer/parseInt (str (:pin from)))
      "fromStateCode"  (Integer/parseInt (str (:state from)))
      "toGstin"        (:gstin to)
      "toTrdName"      (:legal-name to)
      "toAddr1"        (:addr1 to)
      "toPlace"        (:loc to)
      "toPincode"      (Integer/parseInt (str (:pin to)))
      "toStateCode"    (Integer/parseInt (str (:state to)))
      "actualToStateCode" (Integer/parseInt (str place-of-supply))
      "transactionType" 1     ; 1 = regular; other codes for bill-to-ship-to
      "totalValue"     (-> (or ass-val 0M) bigdec
                           (.setScale 2 java.math.RoundingMode/HALF_EVEN))
      "totInvValue"    (-> tot-inv-val bigdec
                           (.setScale 2 java.math.RoundingMode/HALF_EVEN))
      "transDistance"  (str distance-km)
      "itemList"
      (mapv (fn [{:keys [prd-desc hsn-code qty unit tax-val gst-rate]}]
              {"productName"   prd-desc
               "productDesc"   prd-desc
               "hsnCode"       (Integer/parseInt (str hsn-code))
               "quantity"      (-> (bigdec qty) (.setScale 3 java.math.RoundingMode/HALF_EVEN))
               "qtyUnit"       unit
               "taxableAmount" (-> (bigdec tax-val) (.setScale 2 java.math.RoundingMode/HALF_EVEN))
               "sgstRate"      0  ; intra/inter split is at the header level
               "cgstRate"      0
               "igstRate"      (-> (bigdec (* 100 gst-rate))
                                   (.setScale 2 java.math.RoundingMode/HALF_EVEN))
               "cessRate"      0
               "cessNonadvol"  0})
            items)}
     irn (assoc "irnNo" irn))))

(defn build-part-b
  "Build EWB Part B (vehicle + transporter info, added when the truck
   rolls). Separate from Part A so it can be transacted later.

   Input:
     {:vehicle-no     \"MH12AB1234\"
      :vehicle-type   :regular          ; or :odc
      :from-place     \"Mumbai\"
      :from-state     \"27\"
      :transporter-id \"GSTIN-of-transporter or 15-char TransID\"
      :transporter-doc-no   \"LR-001\"
      :transporter-doc-date #inst \"2026-05-11\"
      :transport-mode :road}            ; :road :rail :air :ship"
  [{:keys [vehicle-no from-place from-state
           transporter-id transporter-doc-no transporter-doc-date]
    veh-type :vehicle-type
    trans-mode :transport-mode
    :or {veh-type :regular trans-mode :road}}]
  (let [doc-date-str
        (when transporter-doc-date
          (let [ld (-> transporter-doc-date .toInstant
                       (.atZone java.time.ZoneOffset/UTC) .toLocalDate)]
            (format "%02d/%02d/%04d"
                    (.getDayOfMonth ld) (.getMonthValue ld) (.getYear ld))))]
    (cond->
     {"vehicleNo"   vehicle-no
      "vehicleType" (vehicle-type veh-type)
      "fromPlace"   from-place
      "fromState"   (Integer/parseInt (str from-state))
      "transMode"   (str (transport-mode trans-mode))}
      transporter-id (assoc "transporterId" transporter-id)
      transporter-doc-no (assoc "transDocNo" transporter-doc-no)
      doc-date-str   (assoc "transDocDate" doc-date-str))))

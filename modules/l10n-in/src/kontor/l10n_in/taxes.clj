(ns kontor.l10n-in.taxes
  "India GST tax engine — slab rates + component split + place-of-supply
   dispatch.

   ## GST 2.0 (effective 2025-09-22)

   The four-slab world (5/12/18/28 + cess) was retired by the 56th GST
   Council meeting. Current slabs:

     0%      Exempt / nil-rated
     0.25%   Rough/unprocessed diamonds and precious stones
     3%      Gold, jewellery
     5%      Essentials (food products, medicines, EVs, insurance)
     18%     Standard (electronics, cement, vehicles, apparel)
     40%     Luxury / sin (aerated drinks, premium cars; from
             2026-02-01 also cigarettes, pan masala, tobacco —
             replacing the old 28% + Compensation Cess model)

   Per ADR-026 the rate registry is effective-dated. Historical
   postings (pre-2025-09-22) resolve against the legacy 5/12/18/28
   stack; new postings resolve against the current slabs.

   ## Component split (unchanged by GST 2.0)

   Same headline rate splits into:
     intra-state (supplier-state = POS):  CGST + SGST     (half each)
     inter-state (supplier-state ≠ POS):  IGST           (full)
     UT-supply  (POS is a UT without legislature):  CGST + UTGST

   ## Place-of-supply dispatch

   `(dispatch-supply supplier-state pos-state pos-is-ut?)` →
     :intra-state | :inter-state | :ut-supply

   The kernel-level `:transaction/place-of-supply` (ADR-023) is the
   POS state. The supplier state comes from the issuer entity
   (typically `:kontor.partner/state` of the company itself in single-
   establishment setups, or the establishment's state in multi-
   GSTIN setups)."
  (:require [clojure.string :as str]
            [kontor.money :as money]))

;; ============================================================================
;; Rate registry — current (post-GST-2.0)
;; ============================================================================

(def cutover-instant
  "GST 2.0 came into effect at 00:00 IST on 2025-09-22."
  #inst "2025-09-22T00:00:00+05:30")

(def post-gst-2-slabs
  "Slab → headline rate. Component split = headline halved each side
   for CGST/SGST/UTGST, full headline for IGST."
  {:exempt    0.00M
   :diamonds  0.0025M
   :gold      0.03M
   :essentials 0.05M
   :standard  0.18M
   :luxury    0.40M})

(def pre-gst-2-slabs
  "Pre-2025-09-22 slabs. Compensation Cess existed on top of the 28%
   slab; modeled as a separate notional rate."
  {:exempt        0.00M
   :diamonds      0.0025M
   :gold          0.03M
   :tier-1        0.05M
   :tier-2        0.12M
   :tier-3        0.18M
   :tier-4        0.28M})

(defn slabs-effective-on
  "Return the slab map effective at `date` (a java.util.Date).
   ADR-026 — effective-dated rate registry."
  [^java.util.Date date]
  (if (neg? (.compareTo date cutover-instant))
    pre-gst-2-slabs
    post-gst-2-slabs))

;; ============================================================================
;; Compensation Cess (legacy + the 2026 tobacco overhaul)
;; ============================================================================

(def cess-rates
  "Per-item compensation cess rates that ride on top of the slab.
   Modeled as a separate per-item lookup since cess is item-specific,
   not slab-wide."
  {:aerated-drinks       0.12M
   :luxury-cars-large    0.22M
   :tobacco              ; pre-2026-02-01 only — replaced by new
                         ; "Health Security and National Security
                         ; Cess" structure post-overhaul.
                         0.290M})

(def tobacco-overhaul-instant
  "Tobacco / pan masala overhaul: 2026-02-01."
  #inst "2026-02-01T00:00:00+05:30")

;; ============================================================================
;; Component split
;; ============================================================================

(defn dispatch-supply
  "Decide the GST component dispatch.

   Inputs:
     supplier-state  - keyword or string state-code of the supplier
     pos-state       - keyword or string state-code of the POS
     pos-is-ut?      - boolean; true iff POS is a Union Territory
                       *without legislature* (Chandigarh, Lakshadweep,
                       Andaman & Nicobar, Ladakh, Dadra & N.H. + Daman
                       & Diu). Delhi & Puducherry, despite being UTs,
                       have legislatures and use SGST.

   Output: :intra-state | :inter-state | :ut-supply"
  [supplier-state pos-state pos-is-ut?]
  (cond
    (not= supplier-state pos-state) :inter-state
    pos-is-ut?                       :ut-supply
    :else                            :intra-state))

(defn component-split
  "Given a dispatch keyword + headline rate, return a map of components.

   Returns:
     :intra-state → {:cgst rate/2 :sgst rate/2}
     :inter-state → {:igst rate}
     :ut-supply   → {:cgst rate/2 :utgst rate/2}"
  [dispatch headline-rate]
  (let [half (.divide ^java.math.BigDecimal headline-rate
                      2M 4 java.math.RoundingMode/HALF_EVEN)]
    (case dispatch
      :intra-state {:cgst half :sgst half}
      :ut-supply   {:cgst half :utgst half}
      :inter-state {:igst headline-rate})))

(defn compute-tax
  "Compute the GST tax components for a base amount.

   Inputs:
     base          - Money in INR
     headline-rate - BigDecimal (e.g. 0.18M for 18%)
     dispatch      - keyword from `dispatch-supply`
     cess-rate     - optional BigDecimal compensation-cess rate
                     (nil = no cess)

   Returns a map `{:components {kw → Money} :cess Money|nil :total Money}`."
  ([base headline-rate dispatch]
   (compute-tax base headline-rate dispatch nil))
  ([base headline-rate dispatch cess-rate]
   (let [split (component-split dispatch headline-rate)
         component-monies
         (into {}
               (map (fn [[k r]] [k (money/round (money/mul-scalar base r) 2)]))
               split)
         cess (when cess-rate
                (money/round (money/mul-scalar base cess-rate) 2))
         total (reduce money/add
                       (money/zero (:commodity base))
                       (cond-> (vals component-monies)
                         cess (concat [cess])))]
     (cond-> {:components component-monies
              :total      total}
       cess (assoc :cess cess)))))

(defn resolve-rate
  "Pick the slab map effective on `date`, then look up `slab-key`."
  [^java.util.Date date slab-key]
  (let [slabs (slabs-effective-on date)]
    (or (get slabs slab-key)
        (throw (ex-info "Unknown slab key for the effective date"
                        {:date date :slab slab-key :available (keys slabs)})))))

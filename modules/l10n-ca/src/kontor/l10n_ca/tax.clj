(ns kontor.l10n-ca.tax
  "Canadian indirect-tax compute — federal GST + HST + provincial
   PST/QST/RST as a callable function family.

   Where the existing `kontor.l10n-ca.gst-hst` ns computes the
   *filing-side* (form GST34-2 line values aggregated from posted
   tax accounts), this ns computes the *invoicing-side*: given a
   line amount and ship-to context, return the per-authority tax
   breakdown the invoice posting builder needs.

   The two namespaces are complementary and share no code: the
   filing report reads tagged ledger postings; the compute below is
   pure arithmetic against published rate tables.

   ## Rate table

   Federal GST (5%) applies everywhere except HST provinces:

       Province          GST    HST   PST    QST    Notes
       ----------------- -----  ----- -----  ------ -----------------
       AB Alberta        5%      -     -      -
       BC British Col.   5%      -    7%      -
       MB Manitoba       5%      -    7%      -     RST in MB law
       NB New Brunswick   -    15%     -      -
       NL Newfoundland    -    15%     -      -
       NS Nova Scotia     -    15%     -      -
       NT NW Territories 5%      -     -      -
       NU Nunavut        5%      -     -      -
       ON Ontario         -    13%     -      -
       PE Prince Edw.I.   -    15%     -      -
       QC Quebec         5%      -     -    9.975%
       SK Saskatchewan   5%      -    6%      -
       YT Yukon          5%      -     -      -

   ## Place-of-supply rule

   For tangible personal property, tax is computed based on the
   ship-to province (the place where physical possession transfers
   to the recipient). For services + intangibles, the place-of-
   supply rules are more nuanced (CRA Tech. Info. Bulletin B-103);
   this module accepts an explicit `:ship-to-province` and lets the
   caller resolve POS upstream.

   ## Zero-rated vs exempt vs taxable

   - **Zero-rated** (`:zero-rated`): basic groceries, prescription
     drugs, medical devices, agricultural products, exports, and
     most financial services rendered to non-residents. Tax rate
     0% but the supplier CAN claim input tax credits (ITCs).
   - **Exempt** (`:exempt`): residential rent, most healthcare,
     most educational services, daycare, legal aid, used residential
     housing resale. Tax rate 0% AND the supplier CANNOT claim ITCs.
   - **Out-of-province non-resident** (`:non-resident`): when the
     buyer is outside Canada AND tangible goods are exported, the
     supply is zero-rated under ETA s.12(a) (export rule). Domestic
     sales to non-residents whose goods stay in Canada are taxable
     based on place of supply.

   Caller signals the category via `:tax-status` on the line
   (default `:taxable`).

   ## What this module deliberately does NOT do

   - **No live CRA rate refresh.** Rates here are baked from the
     CRA + provincial pages as of 2026. When a province changes its
     rate, an l10n-ca artifact bump updates this table.
   - **No fiscal-position / customer-tax-exempt lookups.** Those are
     the consumer's responsibility (apply zero-rated when a registered
     non-resident buyer presents the right paperwork).
   - **No PST-on-services exclusions.** BC, SK, MB each carve out a
     long list of services not subject to PST. The line-supplied
     `:tax-status` is the override hatch — set `:exempt` when a
     specific PST exclusion applies.

   Algorithm sources (public, non-copyrightable rate tables):
     - CRA: https://www.canada.ca/en/revenue-agency/services/tax/businesses/topics/gst-hst-businesses/charge-collect-which-rate.html
     - BC Min. of Finance: https://www2.gov.bc.ca/gov/content/taxes/sales-taxes/pst
     - SK Ministry of Finance: https://www.saskatchewan.ca/business/taxes-licensing-and-reporting/provincial-taxes-policies-and-bulletins/provincial-sales-tax
     - MB Taxation: https://www.gov.mb.ca/finance/taxation/taxes/retail.html
     - Revenu Québec (QST): https://www.revenuquebec.ca/en/businesses/consumption-taxes/

   ## API

     compute-tax {:line :ship-to-province :tax-status} → {:gst :hst :pst :qst :total-tax :total-gross}"
  (:require [kontor.money :as money]))

;; ============================================================================
;; Rate tables
;; ============================================================================

(def gst-rate
  "Federal GST rate. Applies in all non-HST provinces."
  0.05M)

(def hst-rate-by-province
  "HST replaces federal GST in 5 provinces. The HST rate is a single
   combined number; CRA receives the full HST and remits the
   provincial share back to the province under the comprehensive
   integrated tax coordination agreement."
  {:ON 0.13M
   :NS 0.15M
   :NB 0.15M
   :NL 0.15M
   :PE 0.15M})

(def pst-rate-by-province
  "Provincial Sales Tax (PST) rate by province. RST in MB is
   semantically the same as PST for compute purposes. QC's QST is
   tracked separately because it's a VAT-style tax with its own
   filing authority (Revenu Québec)."
  {:BC 0.07M
   :SK 0.06M
   :MB 0.07M})

(def qst-rate
  "Quebec Sales Tax — VAT-style, administered by Revenu Québec.
   Applied to the GST-exclusive base (since 2013; the GST-inclusive
   base was retired)."
  0.09975M)

(def hst-provinces
  "Set of provinces where HST replaces federal GST."
  #{:ON :NS :NB :NL :PE})

(def gst-provinces
  "Set of provinces where federal GST applies (the complement of
   the HST set, restricted to the 13 Canadian jurisdictions)."
  #{:AB :BC :MB :NT :NU :QC :SK :YT})

(def pst-provinces
  "Set of provinces with a non-recoverable provincial sales tax
   (PST in BC/SK; RST in MB). PST is NOT a VAT — it's a single-
   stage retail tax. Suppliers in these provinces are not eligible
   for PST input-tax credits."
  #{:BC :SK :MB})

(def qst-provinces
  "Set of provinces with a VAT-style provincial tax (only Quebec)."
  #{:QC})

(def all-provinces
  "Set of all 13 Canadian provincial / territorial codes."
  #{:AB :BC :MB :NB :NL :NS :NT :NU :ON :PE :QC :SK :YT})

;; ============================================================================
;; Compute helpers
;; ============================================================================

(defn- bd
  "Extract the BigDecimal from a Money record, or pass through a
   BigDecimal / numeric input."
  ^java.math.BigDecimal [m]
  (cond
    (instance? java.math.BigDecimal m) m
    (number? m) (bigdec m)
    (and (map? m) (contains? m :amount)) (:amount m)
    :else (throw (ex-info "Cannot coerce to BigDecimal" {:value m}))))

(defn- m-zero [] (money/zero :CAD))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN and wrap in a Money :CAD.
   HALF-EVEN matches the kernel default; CRA does not mandate a
   specific rounding mode for invoice-line tax but does require
   the cumulative invoice tax to match the sum of line taxes ±$0.02."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :CAD))

(defn- m-mul
  "Multiply a BigDecimal net amount by a rate, returning a Money :CAD
   rounded to 2dp HALF-EVEN."
  [^java.math.BigDecimal net ^java.math.BigDecimal rate]
  (m-cents (.multiply net rate)))

;; ============================================================================
;; Tax status — top-level category
;; ============================================================================

(def tax-statuses
  "Valid `:tax-status` values for compute-tax input. Default
   `:taxable` — federal GST/HST + applicable provincial tax apply.

     :taxable      — normal rate per province
     :zero-rated   — rate is 0% but the supply is still 'taxable
                     in form' (groceries, exports, prescription drugs,
                     etc.). ITCs are claimable upstream.
     :exempt       — out of the tax base entirely (residential rent,
                     most healthcare). ITCs are NOT claimable upstream.
     :non-resident — buyer is outside Canada and goods are exported;
                     treated as zero-rated under ETA s.12(a)."
  #{:taxable :zero-rated :exempt :non-resident})

(defn- assert-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status
                     :valid tax-statuses})))
  status)

(defn- assert-province! [province]
  (when-not (contains? all-provinces province)
    (throw (ex-info "Invalid :ship-to-province — must be a Canadian provincial code"
                    {:value province
                     :valid all-provinces})))
  province)

;; ============================================================================
;; Compute
;; ============================================================================

(defn compute-tax
  "Compute the per-authority tax breakdown for one taxable line
   shipped to one province.

   Required inputs:
     :line               BigDecimal | Money | number — net taxable amount
     :ship-to-province   keyword in `all-provinces`

   Optional inputs:
     :tax-status         keyword in `tax-statuses` (default :taxable)

   Returns:
     {:gst         Money :CAD  ; federal GST (5%) — zero in HST provinces
      :hst         Money :CAD  ; HST (provincial-combined) — zero outside HST set
      :pst         Money :CAD  ; provincial PST/RST — non-recoverable
      :qst         Money :CAD  ; QC parallel VAT
      :total-tax   Money :CAD  ; sum of the four
      :total-gross Money :CAD  ; net + total-tax
      :net         Money :CAD  ; net (echoed for caller convenience)
      :province    keyword
      :tax-status  keyword}

   For `:zero-rated`, `:exempt`, and `:non-resident` lines, every
   tax field is Money 0 :CAD; the total-gross equals the net.

   Examples:
     (compute-tax {:line 1000M :ship-to-province :ON})
       → {:gst 0 :hst 130.00 :pst 0 :qst 0 :total-tax 130.00 …}

     (compute-tax {:line 1000M :ship-to-province :BC})
       → {:gst 50.00 :hst 0 :pst 70.00 :qst 0 :total-tax 120.00 …}

     (compute-tax {:line 1000M :ship-to-province :QC})
       → {:gst 50.00 :hst 0 :pst 0 :qst 99.75 :total-tax 149.75 …}

     (compute-tax {:line 1000M :ship-to-province :AB})
       → {:gst 50.00 :hst 0 :pst 0 :qst 0 :total-tax 50.00 …}"
  [{:keys [line ship-to-province tax-status]
    :or {tax-status :taxable}}]
  (assert-status! tax-status)
  (assert-province! ship-to-province)
  (let [net-bd (bd line)
        net-m  (m-cents net-bd)
        zero   (m-zero)]
    (if (contains? #{:zero-rated :exempt :non-resident} tax-status)
      {:gst zero
       :hst zero
       :pst zero
       :qst zero
       :total-tax zero
       :total-gross net-m
       :net net-m
       :province ship-to-province
       :tax-status tax-status}
      ;; :taxable — apply the relevant rates.
      (let [hst (if-let [rate (get hst-rate-by-province ship-to-province)]
                  (m-mul net-bd rate)
                  zero)
            gst (if (contains? gst-provinces ship-to-province)
                  (m-mul net-bd gst-rate)
                  zero)
            pst (if-let [rate (get pst-rate-by-province ship-to-province)]
                  (m-mul net-bd rate)
                  zero)
            qst (if (contains? qst-provinces ship-to-province)
                  (m-mul net-bd qst-rate)
                  zero)
            total-tax (-> zero
                          (money/add gst)
                          (money/add hst)
                          (money/add pst)
                          (money/add qst))
            total-gross (money/add net-m total-tax)]
        {:gst gst
         :hst hst
         :pst pst
         :qst qst
         :total-tax total-tax
         :total-gross total-gross
         :net net-m
         :province ship-to-province
         :tax-status tax-status}))))

(defn compute-invoice-tax
  "Aggregate tax over a sequence of invoice lines, all shipped to
   the same province. Each line is `{:line <amount> :tax-status
   <kw>?}`; the ship-to-province is supplied once at the top level
   (matching how a single CA invoice models a single shipment
   address).

   Returns a map with the same shape as `compute-tax` plus a
   `:per-line` vector of the individual line breakdowns. The summary
   fields are the sum across lines (each rounded to 2dp first, then
   added — matching CRA's invoice-level tax check tolerance)."
  [{:keys [lines ship-to-province]}]
  (assert-province! ship-to-province)
  (let [per-line (mapv (fn [l]
                         (compute-tax (assoc l :ship-to-province ship-to-province)))
                       lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [gst hst pst qst total-tax total-gross net]}]
                (-> acc
                    (update :gst money/add gst)
                    (update :hst money/add hst)
                    (update :pst money/add pst)
                    (update :qst money/add qst)
                    (update :total-tax money/add total-tax)
                    (update :total-gross money/add total-gross)
                    (update :net money/add net)))
              {:gst zero :hst zero :pst zero :qst zero
               :total-tax zero :total-gross zero :net zero}
              per-line)]
    (assoc sums
           :province ship-to-province
           :per-line per-line)))

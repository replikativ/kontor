(ns kontor.l10n-au.tax
  "Australian GST compute — single-rate federal VAT as a callable
   function family.

   Where the existing `kontor.l10n-au.gst` ns computes the *filing-
   side* (BAS line values aggregated from posted tax accounts), this
   ns computes the *invoicing-side*: given a line amount and tax
   status, return the GST breakdown the invoice posting builder
   needs.

   The two namespaces are complementary and share rate constants
   only: the filing report reads tagged ledger postings; the compute
   below is pure arithmetic against the published 10% rate.

   ## Rate table

   Australia operates a single-rate federal GST. There is no state /
   territory sales tax — GST is the only indirect tax on most B2C
   transactions. Three categories matter for compute:

       Status              Rate    Supplier ITC?  Examples
       ------------------- -----   -------------  ----------------------
       :taxable             10%    yes            most B2B + B2C sales
       :gst-free             0%    yes            fresh food, exports,
                                                  health, education,
                                                  childcare
       :input-taxed          0%    NO             financial services,
                                                  residential rent

   ## Zero-rated vs input-taxed (the substantive distinction)

   The Australian GST law splits 'tax not charged' into two regimes
   that differ on the supplier's input-tax-credit eligibility:

   - **GST-free** (zero-rated in international terminology): the
     supplier charges 0% on the supply AND can claim ITCs on inputs.
     Equivalent to VAT zero-rating in the EU.

   - **Input-taxed**: the supplier charges no GST on the supply BUT
     cannot claim ITCs on inputs (the inputs are 'input-taxed'). The
     economic effect is that GST embedded in inputs falls on the
     supplier rather than the customer. Mostly applies to financial
     supplies (banks, insurance) and residential rent.

   This module returns 0% tax for both, but tags the result with the
   status so the invoice builder can post to the right revenue
   account (input-taxed sales feed BAS label G4; GST-free sales feed
   G2 or G3 depending on whether they are exports).

   ## What this module deliberately does NOT do

   - **No live ATO rate refresh.** GST rate is constant since 1 July
     2000. If it ever changes, an l10n-au artifact bump updates this
     table.
   - **No registration-threshold check.** A business below AUD 75,000
     turnover is not required to register; even when not registered,
     it may not charge GST. That's a consumer-side concern (when do
     I start emitting GST-inclusive invoices) and is upstream of the
     compute fn.
   - **No place-of-supply rules for digital services.** AU treats
     non-resident digital service suppliers under GSTR 2017/1; the
     supplier-side determination of taxable-or-not is the consumer's
     responsibility, not the kernel's.

   Algorithm source (public, non-copyrightable rate):
     - ATO: https://www.ato.gov.au/business/gst/

   ## API

     compute-tax         {:line :tax-status} → {:gst :total-tax :total-gross …}
     compute-invoice-tax {:lines}            → {:gst :total-tax :per-line …}"
  (:require [kontor.money :as money]))

;; ============================================================================
;; Rate
;; ============================================================================

(def gst-rate
  "Federal GST rate. 10% since 1 July 2000."
  0.10M)

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

(defn- m-zero [] (money/zero :AUD))

(defn- m-cents
  "Round a BigDecimal to 2dp HALF-EVEN and wrap in a Money :AUD.
   HALF-EVEN matches the kernel default. ATO practice on invoices is
   commonly HALF-UP per-line; the cumulative invoice tax check
   tolerates a ±A$0.02 rounding difference."
  [^java.math.BigDecimal amt]
  (money/money
   (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)
   :AUD))

(defn- m-mul
  "Multiply a BigDecimal net amount by a rate, returning a Money :AUD
   rounded to 2dp HALF-EVEN."
  [^java.math.BigDecimal net ^java.math.BigDecimal rate]
  (m-cents (.multiply net rate)))

;; ============================================================================
;; Tax status — top-level category
;; ============================================================================

(def tax-statuses
  "Valid `:tax-status` values for compute-tax input. Default
   `:taxable` — 10% federal GST applies.

     :taxable      — normal 10% GST.
     :gst-free     — 0% GST. Supplier MAY claim input tax credits
                     (fresh food, exports, health, education).
     :input-taxed  — 0% GST. Supplier may NOT claim ITCs (financial
                     services, residential rent)."
  #{:taxable :gst-free :input-taxed})

(defn- assert-status! [status]
  (when-not (contains? tax-statuses status)
    (throw (ex-info "Invalid :tax-status"
                    {:value status
                     :valid tax-statuses})))
  status)

;; ============================================================================
;; Compute
;; ============================================================================

(defn compute-tax
  "Compute the GST breakdown for one taxable line.

   Required inputs:
     :line               BigDecimal | Money | number — net taxable amount

   Optional inputs:
     :tax-status         keyword in `tax-statuses` (default :taxable)

   Returns:
     {:gst         Money :AUD  ; 10% on a taxable line; 0 otherwise
      :total-tax   Money :AUD  ; alias of :gst at present
      :total-gross Money :AUD  ; net + total-tax
      :net         Money :AUD  ; net (echoed for caller convenience)
      :tax-status  keyword}

   For `:gst-free` and `:input-taxed` lines, :gst is Money 0 :AUD
   and total-gross equals the net.

   Examples:
     (compute-tax {:line 1000M})
       → {:gst 100.00 :total-tax 100.00 :total-gross 1100.00 …}

     (compute-tax {:line 1000M :tax-status :gst-free})
       → {:gst 0 :total-tax 0 :total-gross 1000.00 …}

     (compute-tax {:line 500M :tax-status :input-taxed})
       → {:gst 0 :total-tax 0 :total-gross 500.00 …}"
  [{:keys [line tax-status]
    :or {tax-status :taxable}}]
  (assert-status! tax-status)
  (let [net-bd (bd line)
        net-m  (m-cents net-bd)
        zero   (m-zero)]
    (if (contains? #{:gst-free :input-taxed} tax-status)
      {:gst zero
       :total-tax zero
       :total-gross net-m
       :net net-m
       :tax-status tax-status}
      (let [gst (m-mul net-bd gst-rate)
            total-gross (money/add net-m gst)]
        {:gst gst
         :total-tax gst
         :total-gross total-gross
         :net net-m
         :tax-status tax-status}))))

(defn compute-invoice-tax
  "Aggregate GST over a sequence of invoice lines. Each line is
   `{:line <amount> :tax-status <kw>?}`.

   Returns a map with the same summary shape as `compute-tax` plus a
   `:per-line` vector of the individual line breakdowns. Per-line
   amounts are rounded to 2dp first, then summed — matching ATO
   invoice-level rounding tolerance."
  [{:keys [lines]}]
  (let [per-line (mapv compute-tax lines)
        zero (m-zero)
        sums (reduce
              (fn [acc {:keys [gst total-tax total-gross net]}]
                (-> acc
                    (update :gst money/add gst)
                    (update :total-tax money/add total-tax)
                    (update :total-gross money/add total-gross)
                    (update :net money/add net)))
              {:gst zero :total-tax zero :total-gross zero :net zero}
              per-line)]
    (assoc sums :per-line per-line)))

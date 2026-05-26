(ns kontor.l10n-ca.gst-hst
  "GST/HST return — CRA form GST34-2.

   Filing-complete: produces all 15 line values for a quarterly or annual
   period and a transcription sheet suitable for entering the values into
   CRA's GST/HST NETFILE web form. No software certification is required
   for that path — the form itself authenticates with a 4-digit access
   code or via My Business Account.

   GST34-2 line structure (CRA):

       101  Sales and other revenue
       103  GST/HST collected or collectible
       104  Adjustments (added to GST/HST collected)
       105  Total GST/HST and adjustments              = 103 + 104
       106  Input tax credits (ITCs)
       107  Adjustments (deducted from ITCs)
       108  Total ITCs and adjustments                 = 106 + 107
       109  Net tax                                    = 105 − 108
       110  Instalment and other annual filer payments
       111  Rebates
       112  Total other credits                        = 110 + 111
       113A Balance                                    = 109 − 112
       205  GST/HST due on acquisition of taxable real property (caller-supplied)
       405  Other GST/HST to be self-assessed (caller-supplied)
       113B Total other debits                         = 205 + 405
       113C Final balance                              = 113A + 113B
       114  Refund claimed                             (113C if < 0)
       115  Payment enclosed                           (113C if > 0)

   Kernel-derived (from postings via :kontor.account-tag/name conventions):
     101 ← :ca-cra-line-101 (sales accounts)
     103 ← :ca-cra-line-103 (GST/HST collected accounts)
     106 ← :ca-cra-line-108 (ITC accounts — legacy tag name; semantically
            line 106 contributors, since absent line-107 adjustments
            line 106 = line 108)

   Caller-supplied (per-return adjustments — not routine postings):
     104, 107, 110, 111 — default to Money 0 CAD.

   Per ADR-015: this module sits in the kernel ring. The renderer ring's
   PDF fillable for the GST34-2 paper form is a separate module
   (`pdf.clj`); transmission via GIFT (.tax file) is cert-gated and
   deferred to Phase 4-CA-cert."
  (:require [kontor.money :as money]
            [kontor.report :as report])
  (:import [java.time LocalDate ZoneOffset]
           [java.util Date]))

;; ============================================================================
;; Period bounds
;; ============================================================================

(defn- ld->date ^Date [^LocalDate ld]
  (Date/from (.toInstant (.atStartOfDay ld ZoneOffset/UTC))))

(defn period-bounds
  "Compute the [from, to) date range for a CRA filing period.

     {:year YYYY :quarter Q}  — calendar quarter (Q ∈ #{1 2 3 4})
     {:year YYYY}             — annual (calendar year)

   Returns {:from Date :to Date :kind ... :year ... :quarter ...}."
  [{:keys [year quarter]}]
  (cond
    (and year quarter)
    (let [start-month (inc (* (dec quarter) 3))
          start (LocalDate/of (int year) (int start-month) 1)
          end   (.plusMonths start 3)]
      {:from (ld->date start) :to (ld->date end)
       :kind :quarterly :year year :quarter quarter})

    year
    {:from (ld->date (LocalDate/of (int year) 1 1))
     :to   (ld->date (LocalDate/of (int (inc year)) 1 1))
     :kind :annual :year year}

    :else
    (throw (ex-info "period-bounds needs :year (and optionally :quarter)"
                    {:given (keys (or {:year year :quarter quarter} {}))}))))

;; ============================================================================
;; Report definition (kernel-derived lines only)
;; ============================================================================

(def gst34-2-base-definition
  "The GST34-2 lines computable from kernel postings via account tags.
   Lines 104/107/110/111 are caller-supplied per-return adjustments."
  {:report/name    "GST/HST Return — Form GST34-2 (CRA)"
   :report/country "CA"
   :report/lines
   [{:line/code "101"
     :line/label "Sales and other revenue"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-101]
                       :sign :inflow :commodity :CAD}}
    {:line/code "103"
     :line/label "GST/HST collected or collectible"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-103]
                       :sign :inflow :commodity :CAD}}
    {:line/code "106"
     :line/label "Input tax credits (ITCs)"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-108]
                       :sign :inflow :commodity :CAD}}]})

;; ============================================================================
;; Return computation
;; ============================================================================

(defn compute-return
  "Compute a complete GST34-2 return for a period.

   Period (one of):
     {:from Date :to Date}    — explicit half-open bounds
     {:year YYYY :quarter Q}  — quarterly
     {:year YYYY}             — annual

   Per-return adjustments (optional, default Money 0 CAD):
     :line-104  adjustments added to GST/HST collected
     :line-107  adjustments deducted from ITCs
     :line-110  instalments + prior-period payments
     :line-111  rebates claimed
     :line-205  GST/HST due on acquisition of taxable real property
     :line-405  Other GST/HST to be self-assessed

   Returns:
     {:kontor.return/form     \"GST34-2\"
      :kontor.return/period   {...}
      :kontor.return/lines    {:101 Money :103 Money ... :115 Money}
      :kontor.return/net-tax  Money       ; line 109
      :kontor.return/balance  Money       ; line 113A (signed)
      :kontor.return/outcome  :payment | :refund | :nil-return}"
  [conn {:keys [from to year quarter
                line-104 line-107 line-110 line-111
                line-205 line-405]
         :as opts}]
  (let [period (cond
                 (and from to) {:from from :to to :kind :explicit}
                 year (period-bounds {:year year :quarter quarter})
                 :else (throw (ex-info
                               "compute-return needs :from/:to or :year(/:quarter)"
                               {:opts opts})))
        zero  (money/zero :CAD)
        l-104 (or line-104 zero)
        l-107 (or line-107 zero)
        l-110 (or line-110 zero)
        l-111 (or line-111 zero)
        l-205 (or line-205 zero)
        l-405 (or line-405 zero)
        r     (report/compute-report
               conn gst34-2-base-definition
               {:from (:from period) :to (:to period)})
        vals* (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                    (:report/lines r))
        l-101 (or (vals* "101") zero)
        l-103 (or (vals* "103") zero)
        l-106 (or (vals* "106") zero)
        l-105 (money/add l-103 l-104)
        l-108 (money/add l-106 l-107)
        l-109 (money/sub l-105 l-108)
        l-112 (money/add l-110 l-111)
        l-113A (money/sub l-109 l-112)
        l-113B (money/add l-205 l-405)
        l-113C (money/add l-113A l-113B)
        sign  (.signum ^java.math.BigDecimal (:amount l-113C))
        l-114 (if (neg? sign)
                (money/money (.negate ^java.math.BigDecimal (:amount l-113C)) :CAD)
                zero)
        l-115 (if (pos? sign) l-113C zero)]
    {:kontor.return/form    "GST34-2"
     :kontor.return/period  period
     :kontor.return/lines   {:101 l-101 :103 l-103 :104 l-104 :105 l-105
                      :106 l-106 :107 l-107 :108 l-108 :109 l-109
                      :110 l-110 :111 l-111 :112 l-112
                      :113A l-113A
                      :205 l-205 :405 l-405
                      :113B l-113B :113C l-113C
                      :114 l-114 :115 l-115}
     :kontor.return/net-tax l-109
     :kontor.return/balance l-113C
     :kontor.return/outcome (cond
                       (neg? sign) :refund
                       (pos? sign) :payment
                       :else       :nil-return)}))

;; ============================================================================
;; Transcription sheet (NETFILE web-form entry helper)
;; ============================================================================

(def ^:private line-labels
  {:101 "Sales and other revenue"
   :103 "GST/HST collected or collectible"
   :104 "  Adjustments (added)"
   :105 "Total GST/HST and adjustments (103+104)"
   :106 "Input tax credits (ITCs)"
   :107 "  Adjustments (deducted)"
   :108 "Total ITCs and adjustments (106+107)"
   :109 "Net tax (105-108)"
   :110 "Instalments and other payments"
   :111 "Rebates"
   :112 "Total other credits (110+111)"
   :113A "Balance (109-112)"
   :205 "  GST/HST on taxable real property"
   :405 "  Other GST/HST self-assessed"
   :113B "Total other debits (205+405)"
   :113C "Final balance (113A+113B)"
   :114 "Refund claimed"
   :115 "Payment enclosed"})

(def ^:private line-order
  [:101 :103 :104 :105 :106 :107 :108 :109 :110 :111 :112 :113A
   :205 :405 :113B :113C :114 :115])

(defn- fmt-money [m]
  (let [amt ^java.math.BigDecimal (:amount m)
        scaled (.setScale amt 2 java.math.RoundingMode/HALF_EVEN)]
    (format "%12s CAD" (str scaled))))

(defn- fmt-period [{:keys [kind year quarter from to]}]
  (case kind
    :quarterly (format "Q%d %d (%tF to %tF)" quarter year from to)
    :annual    (format "Annual %d (%tF to %tF)" year from to)
    :explicit  (format "%tF to %tF" from to)))

(defn transcription-sheet
  "Render a return for transcription into CRA's GST/HST NETFILE web form.
   Returns a plain-text string."
  [{:kontor.return/keys [form period lines net-tax balance outcome]}]
  (let [out (java.io.StringWriter.)]
    (binding [*out* out]
      (println "---------------------------------------------------------------")
      (println (str "  " form " - CRA NETFILE transcription sheet"))
      (println (str "  Period:  " (fmt-period period)))
      (println "---------------------------------------------------------------")
      (doseq [k line-order]
        (println (format "  %-6s %-40s %s"
                         (name k)
                         (line-labels k)
                         (fmt-money (get lines k)))))
      (println "---------------------------------------------------------------")
      (println (str "  Net tax (line 109): " (fmt-money net-tax)))
      (println (str "  Balance (line 113A): " (fmt-money balance)))
      (println (str "  Outcome: " (name outcome)))
      (println "---------------------------------------------------------------"))
    (str out)))

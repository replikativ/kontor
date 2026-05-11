(ns kontor.l10n-au.gst
  "Australian GST + BAS (Business Activity Statement) computation.

   GST rate: **10%** (single rate; no reduced rate). Registration
   threshold: AUD 75,000 annual turnover (150,000 for non-profits).

   BAS labels covered:
     G1   Total sales (incl. GST)
     G2   Export sales (zero-rated)
     G3   Other GST-free sales
     G4   Input-taxed sales (financial supplies, residential rent)
     G10  Capital purchases
     G11  Non-capital purchases
     1A   GST on sales (GST collected)
     1B   GST on purchases (input tax credits)
     W1   Total wages (payroll)
     W2   PAYG withheld

   Net GST = 1A − 1B. Positive = pay ATO; negative = refund.

   No clearance-token flow (ADR-018) — ATO doesn't pre-clear
   invoices. Peppol PINT A-NZ is the e-invoicing standard.

   ## Simpler vs Full BAS

   The ATO default for businesses with GST turnover < AUD 10,000,000
   is **Simpler BAS**, which only reports G1, 1A, 1B (and 1H for
   GST instalments). Full BAS adds the G2/G3/G4/G10/G11/W1/W2 labels
   and is mandatory at AUD 10M+. The `compute-return` :bas/mode opt
   selects which label set ships in `:return/lines`.

   ## Scope notes (per AU verification 2026-05-11)

   - **Out of scope**: STP Phase 2 lodgement (payroll-event boundary;
     verified correct to defer). W1/W2 *are* in scope here because
     they're BAS labels, not STP events.
   - **Out of scope**: W3/W4/W5 (no-ABN withholding 47% and total
     withheld). Add when a relevant customer surfaces.
   - **Out of scope**: WET, LCT, FBT instalments, PAYG instalments.
   - **AU practice**: per-line HALF-UP rounding to 2dp is more common
     than the kernel's HALF-EVEN default. Worth an ADR for production
     deployment; the math is small for SMB cases.
   - **AU financial year** = 1 July to 30 June (not calendar).
     :year-based period bounds here use *calendar* year; AU FY
     periods need to set the offset explicitly via :from/:to or via
     a future :fy-start-month aware helper."
  (:require [kontor.l10n-au.chart]                ; for tag identity refs
            [kontor.money :as money]
            [kontor.report :as report]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :AUD))
(defn- m-cents [m]
  (money/money
   (.setScale (bd m) 2 java.math.RoundingMode/HALF_EVEN)
   :AUD))

;; ============================================================================
;; GST rate
;; ============================================================================

(def gst-rate 0.10M)

;; ============================================================================
;; BAS report definition
;; ============================================================================

(def bas-report-definition
  {:report/name    "Business Activity Statement (BAS)"
   :report/country "AU"
   :report/lines
   [{:line/code "G1"  :line/label "Total sales (incl. GST)"
     :line/expression {:engine :tax-tags :tags [:au-bas-g1-total-sales]
                       :sign :inflow :commodity :AUD}}
    {:line/code "G2"  :line/label "Export sales"
     :line/expression {:engine :tax-tags :tags [:au-bas-g2-export-sales]
                       :sign :inflow :commodity :AUD}}
    {:line/code "G3"  :line/label "GST-free sales other than exports
                                    (food, health, education, going-concerns, etc.)"
     :line/expression {:engine :tax-tags :tags [:au-bas-g3-gst-free-sales]
                       :sign :inflow :commodity :AUD}}
    {:line/code "G4"  :line/label "Input-taxed sales"
     :line/expression {:engine :tax-tags :tags [:au-bas-g4-input-taxed-sales]
                       :sign :inflow :commodity :AUD}}
    {:line/code "G10" :line/label "Capital purchases"
     :line/expression {:engine :tax-tags :tags [:au-bas-g10-capital-purchases]
                       :sign :inflow :commodity :AUD}}
    {:line/code "G11" :line/label "Non-capital purchases"
     :line/expression {:engine :tax-tags :tags [:au-bas-g11-non-capital-purchases]
                       :sign :inflow :commodity :AUD}}
    {:line/code "1A"  :line/label "GST on sales"
     :line/expression {:engine :tax-tags :tags [:au-bas-1a-gst]
                       :sign :inflow :commodity :AUD}}
    {:line/code "1B"  :line/label "GST on purchases (ITC)"
     :line/expression {:engine :tax-tags :tags [:au-bas-1b-itc]
                       :sign :inflow :commodity :AUD}}
    {:line/code "W1"  :line/label "Total wages"
     :line/expression {:engine :tax-tags :tags [:au-bas-w1-total-wages]
                       :sign :inflow :commodity :AUD}}
    {:line/code "W2"  :line/label "PAYG withheld"
     :line/expression {:engine :tax-tags :tags [:au-bas-w2-payg-w]
                       :sign :inflow :commodity :AUD}}]})

(defn- ld->date ^java.util.Date [^java.time.LocalDate ld]
  (java.util.Date/from (.toInstant (.atStartOfDay ld java.time.ZoneOffset/UTC))))

(defn period-bounds
  "AU BAS periods:
     {:year 2026 :quarter 1}  — Q1 = Jul-Sep (Australian financial year)
                                 actually AU uses tax-year quarters
                                 but for simplicity we expose calendar
                                 quarters. AU FY-quarter aliases can
                                 be added; this is the easy default.
     {:year 2026 :month 6}    — monthly filer
     {:year 2026}             — annual"
  [{:keys [year quarter month]}]
  (cond
    (and year quarter)
    (let [start-month (inc (* (dec quarter) 3))
          start (java.time.LocalDate/of (int year) (int start-month) 1)
          end   (.plusMonths start 3)]
      {:from (ld->date start) :to (ld->date end)
       :kind :quarterly :year year :quarter quarter})

    (and year month)
    (let [start (java.time.LocalDate/of (int year) (int month) 1)
          end   (.plusMonths start 1)]
      {:from (ld->date start) :to (ld->date end)
       :kind :monthly :year year :month month})

    year
    {:from (ld->date (java.time.LocalDate/of (int year) 1 1))
     :to   (ld->date (java.time.LocalDate/of (int (inc year)) 1 1))
     :kind :annual :year year}

    :else (throw (ex-info "period-bounds needs :year and optional :quarter/:month" {}))))

(def ^:private simpler-bas-labels
  "Simpler BAS (turnover < AUD 10M) — only these labels lodge."
  #{:G1 :1A :1B :1H})

(defn- maybe-filter-labels [lines mode]
  (case mode
    :simpler (into {} (filter (fn [[k _]] (simpler-bas-labels k)) lines))
    lines))

(defn compute-return
  "Compute a BAS return for a period.

   Opts:
     :from, :to                 — explicit bounds
     :year (:quarter|:month)?   — alternative
     :bas/mode                  — :simpler (turnover < AUD 10M; default)
                                  or :full (≥ 10M, all labels).
                                  Defaults to :full to match the
                                  computed-label set; consumers explicitly
                                  request :simpler when they qualify.

   Returns:
     {:return/form    \"BAS\"
      :return/mode    :simpler | :full
      :return/period  {…}
      :return/lines   {<code> Money :AUD …}
      :return/net-gst Money            ; line 1A - line 1B
      :return/outcome :payment | :refund | :nil-return}"
  [conn {:keys [from to year quarter month] :as opts}]
  (let [period (cond
                 (and from to) {:from from :to to :kind :explicit}
                 year (period-bounds (select-keys opts [:year :quarter :month]))
                 :else (throw (ex-info "compute-return needs :from/:to or :year" {})))
        r (report/compute-report conn bas-report-definition
                                 {:from (:from period) :to (:to period)})
        vals* (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                    (:report/lines r))
        zero (m-zero)
        get* (fn [k] (or (vals* k) zero))
        gst-on-sales      (get* "1A")
        gst-on-purchases  (get* "1B")
        net-gst (money/sub gst-on-sales gst-on-purchases)
        sign (.signum ^java.math.BigDecimal (:amount net-gst))]
    (let [mode (get opts :bas/mode :full)
          all-lines (into {} (map (fn [[k v]] [(keyword k) (m-cents v)]) vals*))]
      {:return/form    "BAS"
       :return/mode    mode
       :return/period  period
       :return/lines   (maybe-filter-labels all-lines mode)
       :return/net-gst (m-cents net-gst)
       :return/outcome (cond
                         (neg? sign) :refund
                         (pos? sign) :payment
                         :else       :nil-return)})))

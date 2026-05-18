(ns kontor.l10n-in.returns
  "Periodic GST returns — substrate-tier aggregations for GSTR-1 and
   GSTR-3B.

   ## Indian GST filing cadence

   Two monthly forms cover most taxpayers; an annual reconciliation
   form sits on top.

   ### GSTR-1 — outward supplies (sales side)

   Lists every outbound invoice issued in the period, grouped by
   GSTIN of the buyer (or by state for B2C), with the per-line tax
   breakdown (CGST / SGST / IGST / UTGST / Cess + invoice value).
   The GSTN portal uses this to populate each buyer's auto-drafted
   GSTR-2A / GSTR-2B so the buyer can reconcile their ITC claims.

   Filing window:
     monthly   — by the 11th of the following month
     quarterly (QRMP) — by the 13th of the month following the
                       quarter, for taxpayers with turnover ≤ ₹5 cr
                       opting in to the Quarterly Return Monthly
                       Payment scheme.

   ### GSTR-3B — self-assessed summary

   Net-tax-payable view of the same period:
     output-tax  (from GSTR-1)   ─┐
     ITC claimed                  ├→ Net tax = output − ITC
     reverse-charge inward GST   ─┘

   GSTR-3B has its own statutory due date — 20th of the next month
   for monthly filers (22nd / 24th for QRMP filers, in two state
   groups). The summary doesn't require invoice-level disclosure —
   just per-head totals.

   ### GSTR-9 (annual)

   Annual consolidation of GSTR-1 + GSTR-3B, due by Dec 31 of the
   following financial year, with mandatory reconciliation in
   GSTR-9C above the ₹5 cr turnover threshold. **Out of scope here**
   — this module only ships the monthly aggregators GSTR-9 reads
   from.

   ## What this module deliberately does NOT do

   - **No GSTN envelope generation.** The portal expects a specific
     JSON shape (`json-prep-utility` schema) per release; that
     marshaling is an adapter concern, not substrate. This module
     emits the *amounts* and the per-invoice rows the adapter then
     packages.

   - **No network calls / no portal validation.** Customers run the
     adapter against their own ASP/GSP / direct GSTN credentials.

   - **No QRMP eligibility determination.** Caller supplies
     `:cadence :monthly | :quarterly` and the report engine groups
     accordingly. Eligibility (turnover < ₹5 cr) is a tax-advisor
     question, not arithmetic.

   ## Aggregation primitives

   Both reports lean on the kontor.report engine + the GST tag
   convention introduced by the l10n-in chart:

     :in-gstr1-b2b-sales       :in-gstr1-b2c-sales
     :in-gstr1-exports         :in-gstr1-exempt
     :in-gstr1-cgst   :in-gstr1-sgst   :in-gstr1-igst
     :in-gstr1-utgst  :in-gstr1-cess

     :in-gstr3b-outward-taxable     :in-gstr3b-outward-zero-rated
     :in-gstr3b-outward-exempt
     :in-gstr3b-output-cgst   :in-gstr3b-output-sgst
     :in-gstr3b-output-igst   :in-gstr3b-output-utgst
     :in-gstr3b-output-cess
     :in-gstr3b-itc-cgst      :in-gstr3b-itc-sgst
     :in-gstr3b-itc-igst      :in-gstr3b-itc-utgst
     :in-gstr3b-itc-cess
     :in-gstr3b-rcm-cgst      :in-gstr3b-rcm-sgst
     :in-gstr3b-rcm-igst

   The report engine returns per-line Money totals; we then derive
   net tax = output − ITC for the GSTR-3B summary."
  (:require [kontor.money :as money]
            [kontor.report :as report])
  (:import [java.util Calendar Date GregorianCalendar TimeZone]))

;; ============================================================================
;; Period bounds — month / quarter / FY helpers
;; ============================================================================

(def ^:const ist-zone "Asia/Kolkata")

(defn- utc-date
  "Construct a java.util.Date for midnight UTC on `y-m-d`."
  ^Date [^long year ^long month ^long day]
  (let [cal (doto (GregorianCalendar. (TimeZone/getTimeZone "UTC"))
              (.clear)
              (.set Calendar/YEAR (int year))
              (.set Calendar/MONTH (int (dec month)))
              (.set Calendar/DAY_OF_MONTH (int day)))]
    (.getTime cal)))

(defn- add-months ^Date [^Date d ^long n]
  (let [cal (doto (GregorianCalendar. (TimeZone/getTimeZone "UTC"))
              (.setTime d)
              (.add Calendar/MONTH (int n)))]
    (.getTime cal)))

(defn month-bounds
  "Inclusive `:from` + exclusive `:to` for one month (UTC midnight
   instants). Year is the Gregorian year; month is 1..12."
  [{:keys [year month]}]
  {:from (utc-date year month 1)
   :to   (add-months (utc-date year month 1) 1)
   :kind :monthly
   :year year :month month})

(defn quarter-bounds
  "Inclusive `:from` + exclusive `:to` for one quarter (calendar
   quarters Q1 = Jan-Mar, … Q4 = Oct-Dec)."
  [{:keys [year quarter]}]
  (let [start-month (-> quarter dec (* 3) inc)]
    {:from (utc-date year start-month 1)
     :to   (add-months (utc-date year start-month 1) 3)
     :kind :quarterly
     :year year :quarter quarter}))

(defn- bounds-from-opts
  "Resolve `:from`/`:to` from a flexible opts map. Caller can pass:
     {:from <date> :to <date>}                — explicit
     {:year 2026 :month 1}                    — single month
     {:year 2026 :quarter 1}                  — single quarter"
  [{:keys [from to year month quarter] :as opts}]
  (cond
    (and from to) (merge opts {:from from :to to})
    (and year month)   (month-bounds {:year year :month month})
    (and year quarter) (quarter-bounds {:year year :quarter quarter})
    :else (throw (ex-info "Missing period — supply :from/:to or :year+:month or :year+:quarter"
                          {:opts opts}))))

;; ============================================================================
;; Filing-due-date helpers — informational only
;; ============================================================================

(defn gstr-1-due-date
  "Statutory due date for GSTR-1 of the given period:
     monthly:    11th of the following month
     quarterly:  13th of the month following the quarter (QRMP)"
  [{:keys [kind year month quarter]}]
  (cond
    (= kind :monthly)
    (let [base (utc-date year month 11)]
      (add-months base 1))

    (= kind :quarterly)
    (let [start-month (-> quarter dec (* 3) inc)
          base (utc-date year start-month 13)]
      (add-months base 3))

    :else nil))

(defn gstr-3b-due-date
  "Statutory due date for GSTR-3B (monthly): 20th of the following
   month. QRMP filers have 22nd/24th depending on state group;
   callers needing that nuance can supply `:qrmp-state-group :A`
   or `:B` and we adjust."
  [{:keys [year month qrmp-state-group]}]
  (let [day (case qrmp-state-group
              :A 22
              :B 24
              20)
        base (utc-date year month day)]
    (add-months base 1)))

;; ============================================================================
;; Tag-driven report shapes
;; ============================================================================

(def gstr-1-definition
  "GSTR-1 report — outward supplies per category + per tax head.
   `:sign :inflow` flips the stored sign (revenue + tax-payable are
   credit-natural ⇒ negative stored) into the natural positive
   number a filing form expects."
  {:report/name "GSTR-1 (Outward supplies)"
   :report/country "IN"
   :report/lines
   [{:line/code "b2b-taxable-value"
     :line/label "Taxable value — B2B"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-b2b-sales]
                       :sign :inflow :commodity :INR}}
    {:line/code "b2c-taxable-value"
     :line/label "Taxable value — B2C"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-b2c-sales]
                       :sign :inflow :commodity :INR}}
    {:line/code "exports-value"
     :line/label "Exports / zero-rated value"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-exports]
                       :sign :inflow :commodity :INR}}
    {:line/code "exempt-value"
     :line/label "Exempt / nil-rated value"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-exempt]
                       :sign :inflow :commodity :INR}}
    {:line/code "cgst"
     :line/label "Output CGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-cgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "sgst"
     :line/label "Output SGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-sgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "igst"
     :line/label "Output IGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-igst]
                       :sign :inflow :commodity :INR}}
    {:line/code "utgst"
     :line/label "Output UTGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-utgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "cess"
     :line/label "Output Compensation Cess"
     :line/expression {:engine :tax-tags :tags [:in-gstr1-cess]
                       :sign :inflow :commodity :INR}}]})

(def gstr-3b-definition
  "GSTR-3B report — self-assessed summary.
   Output tax + ITC claimed per head, plus reverse-charge inward
   GST for the per-head net calculation."
  {:report/name "GSTR-3B (Summary)"
   :report/country "IN"
   :report/lines
   [{:line/code "outward-taxable"
     :line/label "Outward supplies — taxable value"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-outward-taxable]
                       :sign :inflow :commodity :INR}}
    {:line/code "outward-zero-rated"
     :line/label "Outward supplies — zero-rated"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-outward-zero-rated]
                       :sign :inflow :commodity :INR}}
    {:line/code "outward-exempt"
     :line/label "Outward supplies — exempt / nil-rated"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-outward-exempt]
                       :sign :inflow :commodity :INR}}
    {:line/code "output-cgst"
     :line/label "Output CGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-output-cgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "output-sgst"
     :line/label "Output SGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-output-sgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "output-igst"
     :line/label "Output IGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-output-igst]
                       :sign :inflow :commodity :INR}}
    {:line/code "output-utgst"
     :line/label "Output UTGST"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-output-utgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "output-cess"
     :line/label "Output Compensation Cess"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-output-cess]
                       :sign :inflow :commodity :INR}}
    {:line/code "rcm-cgst"
     :line/label "RCM CGST payable"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-rcm-cgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "rcm-sgst"
     :line/label "RCM SGST payable"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-rcm-sgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "rcm-igst"
     :line/label "RCM IGST payable"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-rcm-igst]
                       :sign :inflow :commodity :INR}}
    {:line/code "itc-cgst"
     :line/label "Input CGST ITC claimed"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-itc-cgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "itc-sgst"
     :line/label "Input SGST ITC claimed"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-itc-sgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "itc-igst"
     :line/label "Input IGST ITC claimed"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-itc-igst]
                       :sign :inflow :commodity :INR}}
    {:line/code "itc-utgst"
     :line/label "Input UTGST ITC claimed"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-itc-utgst]
                       :sign :inflow :commodity :INR}}
    {:line/code "itc-cess"
     :line/label "Input Cess ITC claimed"
     :line/expression {:engine :tax-tags :tags [:in-gstr3b-itc-cess]
                       :sign :inflow :commodity :INR}}]})

;; ============================================================================
;; Line-extraction helper
;; ============================================================================

(defn- lines->map
  "Re-key the kontor.report output (`:report/lines` vector) into a
   {line-code → Money} map. Easier for downstream computation."
  [computed]
  (into {}
        (map (fn [l] [(:line/code l) (:line/value l)]))
        (:report/lines computed)))

;; ============================================================================
;; GSTR-1
;; ============================================================================

(defn generate-gstr-1
  "Compute the GSTR-1 outward-supplies aggregations for the period.

   Required opts:
     either {:from <date> :to <date>}
     or     {:year YYYY :month 1..12}
     or     {:year YYYY :quarter 1..4}      ; QRMP filers

   Optional:
     :entity   — :transaction/entity scope (multi-entity setups, ADR-031)
     :as-of-tx — bitemporal tx-snapshot (default now)

   Returns:
     {:return/form         \"GSTR-1\"
      :return/period       {…}             ; period bounds + kind
      :return/due-date     <Date>          ; statutory filing due-date
      :return/totals       {line-code Money …}
      :return/raw          <report-output> ; full kontor.report result
                                             for drill-down}"
  [conn opts]
  (let [{:keys [from to] :as period} (bounds-from-opts opts)
        report-opts (-> opts
                        (assoc :from from :to to)
                        (dissoc :year :month :quarter))
        r (report/compute-report conn gstr-1-definition report-opts)
        totals (lines->map r)
        due-date (gstr-1-due-date period)]
    {:return/form "GSTR-1"
     :return/period period
     :return/due-date due-date
     :return/totals totals
     :return/raw r}))

;; ============================================================================
;; GSTR-3B
;; ============================================================================

(defn- m-or-zero [m]
  (or m (money/zero :INR)))

(defn- per-head-net
  "Net per head: (output + RCM-payable) − ITC. Per-head because each
   head settles independently against its own electronic credit
   ledger on the GSTN portal."
  [totals output-key rcm-key itc-key]
  (-> (m-or-zero (get totals output-key))
      (money/add (m-or-zero (get totals rcm-key)))
      (money/sub (m-or-zero (get totals itc-key)))))

(defn generate-gstr-3b
  "Compute the GSTR-3B summary aggregations + per-head net tax payable.

   Required opts:
     either {:from <date> :to <date>}
     or     {:year YYYY :month 1..12}

   Optional:
     :qrmp-state-group :A | :B  — affects the due-date only
     :entity                     — multi-entity filter
     :as-of-tx                   — bitemporal snapshot

   Returns:
     {:return/form         \"GSTR-3B\"
      :return/period       {…}
      :return/due-date     <Date>
      :return/totals       {line-code Money …}
      :return/net-tax      {:cgst Money :sgst Money :igst Money
                            :utgst Money :cess Money}
      :return/net-total    Money               ; sum across heads
      :return/raw          <report-output>}"
  [conn opts]
  (let [{:keys [from to] :as period} (bounds-from-opts opts)
        report-opts (-> opts
                        (assoc :from from :to to)
                        (dissoc :year :month :quarter :qrmp-state-group))
        r (report/compute-report conn gstr-3b-definition report-opts)
        totals (lines->map r)
        ;; Per-head net = (output + RCM-payable on that head) − ITC.
        ;; UTGST + Cess don't carry an RCM head in this substrate
        ;; (RCM CGST/SGST/IGST only); pass a sentinel that resolves
        ;; to zero.
        net-cgst  (per-head-net totals "output-cgst"  "rcm-cgst"  "itc-cgst")
        net-sgst  (per-head-net totals "output-sgst"  "rcm-sgst"  "itc-sgst")
        net-igst  (per-head-net totals "output-igst"  "rcm-igst"  "itc-igst")
        net-utgst (per-head-net totals "output-utgst" :missing   "itc-utgst")
        net-cess  (per-head-net totals "output-cess"  :missing   "itc-cess")
        net-total (reduce money/add
                          [net-cgst net-sgst net-igst net-utgst net-cess])
        due-date (gstr-3b-due-date (assoc period
                                          :qrmp-state-group (:qrmp-state-group opts)))]
    {:return/form "GSTR-3B"
     :return/period period
     :return/due-date due-date
     :return/totals totals
     :return/net-tax {:cgst net-cgst
                      :sgst net-sgst
                      :igst net-igst
                      :utgst net-utgst
                      :cess net-cess}
     :return/net-total net-total
     :return/raw r}))

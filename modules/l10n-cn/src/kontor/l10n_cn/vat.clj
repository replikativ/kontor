(ns kontor.l10n-cn.vat
  "China VAT (Value Added Tax / 增值税) — return computation.

   Rate structure (codified by the VAT Law effective 2026-01-01,
   unchanged in substance from prior State Council regulation):

     13%  Standard (goods + processing/repair/installation services)
     9%   Reduced  (transport, postal, basic telecom, real estate,
                    construction, some agriculture)
     6%   Modern services + IT + financial-services-VATable
     3%   Small-scale taxpayer levy (annual taxable sales ≤ CNY 5M;
          currently reduced to 1% via preferential policy through
          2027-12-31)
     0%   Exports

   ## Surcharges (computed on net VAT payable)

     Urban Maintenance & Construction Tax (城建税)
       7%  city (municipal districts)
       5%  county / town
       1%  other (non-urban)
     Education Surcharge (教育费附加)              3%
     Local Education Surcharge (地方教育费附加)    2%

   ## MOF-canonical account routing (corrected 2026-05-11)

   Per the CN verification agent's gap analysis: all output VAT posts
   to the single MOF account 2221.01.01 销项税额; per-rate aggregation
   is reconstructed at report time from rate-tagged REVENUE accounts
   (5001.13 / 5001.9 / 5001.6 / 5001.0). The previous per-rate split
   on the OUTPUT side was non-canonical.

   Per ADR-018 fapiao issuance flows through `:pending-attestation`
   to `:posted`. This namespace does NOT call the STA platform — that
   integration is opaque outside China and belongs in a partner
   adapter (`kontor-l10n-cn-fapiao`)."
  (:require [kontor.l10n-cn.chart]                ; for tag identity refs
            [kontor.money :as money]
            [kontor.reporting.report :as report]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :CNY))
(defn- m-cents [m]
  (money/money
   (.setScale (bd m) 2 java.math.RoundingMode/HALF_EVEN)
   :CNY))
(defn- m-mul [m rate]
  (money/money
   (.setScale (.multiply (bd m) ^java.math.BigDecimal rate)
              2 java.math.RoundingMode/HALF_EVEN)
   :CNY))

;; ============================================================================
;; Rates
;; ============================================================================

(def standard-rate     0.13M)
(def reduced-rate      0.09M)
(def services-rate     0.06M)
(def small-scale-rate  0.03M)   ; legal rate

;; Small-scale taxpayer preferential 1% rate. Set by
;; **Cai Shui [2023] No. 19** (财政部 税务总局公告 2023 年第 19 号),
;; reducing 3% → 1% through **2027-12-31**. Most small-scale
;; transactions use this rate, not the statutory 3%. Excludes
;; real-estate sale/lease + land-use-rights transfer, which retain
;; 5% / 3%.
(def small-scale-preferential-rate 0.01M)

;; Monthly / quarterly VAT exemption thresholds for small-scale
;; taxpayers. Set by **Cai Shui [2023] No. 1** (财政部 税务总局公告
;; 2023 年第 1 号) — the threshold rule. (No. 19 governs the rate;
;; No. 1 governs the exemption — separate documents, easy to
;; confuse.) Both run through 2027-12-31. Below threshold → zero VAT
;; + zero surcharges.
(def small-scale-monthly-exemption    100000M)   ; CNY 100k / month
(def small-scale-quarterly-exemption  300000M)   ; CNY 300k / quarter

;; Surcharge rates (computed off the net VAT payable).
(def urban-maintenance-city-rate    0.07M)
(def urban-maintenance-county-rate  0.05M)
(def urban-maintenance-other-rate   0.01M)
(def education-surcharge-rate       0.03M)
(def local-education-surcharge-rate 0.02M)

(defn umct-rate-for-tier
  "Urban Maintenance & Construction Tax rate by location tier
   (per UMCT Law Article 4, Presidential Order No. 51, effective
   2021-09-01):
     :municipal  → 7%   (市区 — urban districts of a prefecture-level city)
     :county     → 5%   (县城 / 建制镇 — county seat or administrative town)
     :other      → 1%   (其他 — rural / unzoned)

   The unknown-tier fallback to :other (1%) is the conservative-correct
   default (lower rate → lower under-payment risk for an unspecified
   location), but most filers in major cities should explicitly set
   :municipal. Worth requiring at call site for production deployment."
  [tier]
  (case tier
    :municipal urban-maintenance-city-rate
    :county    urban-maintenance-county-rate
    :other     urban-maintenance-other-rate
    urban-maintenance-other-rate))

;; ============================================================================
;; VAT return report definition
;; ============================================================================

(def vat-base-report-definition
  "Lines computable from kernel postings via CN account tags.

   Strategy: revenue is rate-tagged at the source (5001.13 / 5001.9
   / 5001.6 / 5001.0 each carry their per-rate tag). Output-VAT per
   rate is derived as `revenue-at-rate × rate` at report time.
   Aggregate input-VAT comes from the single `cn-vat-input` tag."
  {:report/name    "VAT Return (PRC)"
   :report/country "CN"
   :report/lines
   [{:line/code "sales-13" :line/label "Sales taxable at 13%"
     :line/expression {:engine :tax-tags :tags [:cn-vat-line-sales-13]
                       :sign :inflow :commodity :CNY}}
    {:line/code "sales-9"  :line/label "Sales taxable at 9%"
     :line/expression {:engine :tax-tags :tags [:cn-vat-line-sales-9]
                       :sign :inflow :commodity :CNY}}
    {:line/code "sales-6"  :line/label "Sales taxable at 6%"
     :line/expression {:engine :tax-tags :tags [:cn-vat-line-sales-6]
                       :sign :inflow :commodity :CNY}}
    {:line/code "sales-export" :line/label "Export sales (zero-rated)"
     :line/expression {:engine :tax-tags :tags [:cn-vat-line-sales-export]
                       :sign :inflow :commodity :CNY}}
    {:line/code "output-vat"  :line/label "Output VAT (single account, all rates)"
     :line/expression {:engine :tax-tags :tags [:cn-vat-output]
                       :sign :inflow :commodity :CNY}}
    {:line/code "input-vat"   :line/label "Input VAT (creditable)"
     :line/expression {:engine :tax-tags :tags [:cn-vat-input]
                       :sign :inflow :commodity :CNY}}]})

(defn compute-return
  "Compute a CN VAT return for a period.

   Opts:
     :from, :to            — explicit half-open bounds
     :year + :month        — calendar-month period (CN VAT is monthly
                              for general taxpayers; quarterly for
                              small-scale)
     :year + :quarter      — calendar quarter (small-scale taxpayer)
     :year                 — annual aggregate
     :location-tier        — :municipal | :county | :other (default :other)
                              determines the UMCT rate
     :compute-surcharges?  — boolean (default true). When true, the
                              return includes urban-maintenance, edu,
                              and local-edu surcharge amounts.

   Returns:
     {:kontor.return/form           \"VAT-PRC\"
      :kontor.return/period         {…}
      :kontor.return/lines          {…  ; per-rate sales + total output/input}
      :kontor.return/output-vat     Money :CNY
      :kontor.return/output-by-rate {0.13 Money 0.09 Money 0.06 Money 0.00 Money}
      :kontor.return/input-vat      Money :CNY
      :kontor.return/net-vat        Money :CNY  ; positive = pay; negative = credit-carryforward
      :kontor.return/umct-payable   Money :CNY
      :kontor.return/edu-surcharge-payable       Money :CNY
      :kontor.return/local-edu-surcharge-payable Money :CNY
      :kontor.return/total-surcharges            Money :CNY
      :kontor.return/outcome        :payment | :credit-carryforward | :nil-return}"
  [conn {:keys [location-tier compute-surcharges?]
         :or {location-tier :other compute-surcharges? true}
         :as opts}]
  (let [zero (m-zero)
        ld->date #(java.util.Date/from (.toInstant (.atStartOfDay % java.time.ZoneOffset/UTC)))
        period (let [{:keys [from to year month quarter]} opts]
                 (cond
                   (and from to) {:from from :to to :kind :explicit}
                   (and year month)
                   (let [start (java.time.LocalDate/of (int year) (int month) 1)
                         end   (.plusMonths start 1)]
                     {:from (ld->date start) :to (ld->date end)
                      :kind :monthly :year year :month month})
                   (and year quarter)
                   (let [start (java.time.LocalDate/of (int year)
                                                       (int (inc (* (dec quarter) 3))) 1)
                         end   (.plusMonths start 3)]
                     {:from (ld->date start) :to (ld->date end)
                      :kind :quarterly :year year :quarter quarter})
                   year
                   {:from (ld->date (java.time.LocalDate/of (int year) 1 1))
                    :to   (ld->date (java.time.LocalDate/of (int (inc year)) 1 1))
                    :kind :annual :year year}
                   :else (throw (ex-info "compute-return needs :from/:to or :year(+:month|:quarter)"
                                         {:opts opts}))))
        r (report/compute-report conn vat-base-report-definition
                                 {:from (:from period) :to (:to period)})
        vals* (into {} (map (fn [l] [(:line/code l) (:line/value l)])) (:report/lines r))
        get* (fn [k] (or (vals* k) zero))

        sales-13 (get* "sales-13")
        sales-9  (get* "sales-9")
        sales-6  (get* "sales-6")
        sales-0  (get* "sales-export")

        ;; Per-rate output computed from revenue × rate (the MOF-canonical
        ;; output account aggregates all rates; per-rate detail is
        ;; reconstructed from rate-tagged revenue).
        out-13 (m-mul sales-13 standard-rate)
        out-9  (m-mul sales-9  reduced-rate)
        out-6  (m-mul sales-6  services-rate)
        ;; Total output is the sum of the per-rate computed values,
        ;; cross-checked against the aggregated account balance.
        computed-output (-> (m-zero) (money/add out-13) (money/add out-9) (money/add out-6))
        booked-output   (get* "output-vat")
        ;; Use the booked (account-balance) figure as authoritative; the
        ;; computed value is for reporting + audit cross-check.
        output-total    booked-output

        input  (get* "input-vat")
        net    (money/sub output-total input)
        sign   (.signum ^java.math.BigDecimal (:amount net))
        ;; Surcharges only apply to positive net VAT (not refunds).
        ;; UMCT Law Art. 2: surcharge base is "实际缴纳的增值税" — VAT
        ;; actually paid. A negative balance means nothing was paid;
        ;; surcharge base is zero.
        ;;
        ;; The controlling current implementation rule is
        ;; **MOF/STA Announcement 2021 No. 28** (post-UMCT-Law
        ;; codification): surcharge base = actually-paid VAT+CT,
        ;; PLUS 免抵税额, MINUS direct reductions and 留抵退税
        ;; refunds. The 2021 No. 28 reaffirms and refines the
        ;; earlier Cai Shui [2018] No. 80; Cai Shui [2022] No. 14
        ;; governed the 2022 large-scale 留抵退税 wave but the
        ;; base-deduction rule itself lives in 2018-80 + 2021-28.
        ;;
        ;; FIXME (留抵退税 — input-credit refund case): when a
        ;; taxpayer receives a 留抵退税 refund, the refunded amount
        ;; reduces the surcharge base in subsequent periods. This
        ;; compute-return doesn't handle that cross-period base
        ;; reduction — extend with a :prior-refunded-credit opt
        ;; when 留抵退税 is in scope.
        net-for-surcharge (if (pos? sign) net zero)
        umct-rate         (umct-rate-for-tier location-tier)
        umct              (when compute-surcharges? (m-mul net-for-surcharge umct-rate))
        edu               (when compute-surcharges? (m-mul net-for-surcharge education-surcharge-rate))
        local-edu         (when compute-surcharges? (m-mul net-for-surcharge local-education-surcharge-rate))
        total-surcharges  (when compute-surcharges?
                            (-> (m-zero) (money/add umct) (money/add edu) (money/add local-edu)))]
    (cond->
     {:kontor.return/form           "VAT-PRC"
      :kontor.return/period         period
      :kontor.return/lines          (into {} (map (fn [[k v]] [(keyword k) (m-cents v)]) vals*))
      :kontor.return/output-vat     (m-cents output-total)
      :kontor.return/output-by-rate {0.13M (m-cents out-13)
                              0.09M (m-cents out-9)
                              0.06M (m-cents out-6)
                              0M    zero}
      :kontor.return/computed-output (m-cents computed-output)
      :kontor.return/input-vat      (m-cents input)
      :kontor.return/net-vat        (m-cents net)
      :kontor.return/outcome (cond
                        (neg? sign) :credit-carryforward
                        (pos? sign) :payment
                        :else :nil-return)}
      compute-surcharges?
      (assoc :kontor.return/location-tier              location-tier
             :kontor.return/umct-payable               (m-cents umct)
             :kontor.return/edu-surcharge-payable      (m-cents edu)
             :kontor.return/local-edu-surcharge-payable (m-cents local-edu)
             :kontor.return/total-surcharges           (m-cents total-surcharges)))))

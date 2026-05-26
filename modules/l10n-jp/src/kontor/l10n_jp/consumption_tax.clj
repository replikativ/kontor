(ns kontor.l10n-jp.consumption-tax
  "Japan Consumption Tax (消費税 / JCT) — return computation.

   Rate structure (effective Oct 2019, unchanged through TY2025):
     - Standard rate (10%): national 7.8% + local 2.2%
     - Reduced rate  (8%):  national 6.24% + local 1.76%
       Scope: food + non-alcoholic beverages (excluding restaurant
       dining), and ≥twice-weekly newspaper subscriptions.

   Three zero-tax categories (load-bearing distinction for input-tax
   credit treatment — verified 2026-05-11):
     - **Non-taxable** (非課税): financial services, residential rent,
       healthcare, education, land sales, securities, school tuition,
       childbirth services. Disallows input-tax credit on related
       purchases.
     - **Export-exempt** (免税): exports + international transport +
       international communications. Preserves input-tax credit.
     - **Out-of-scope** (不課税): outside the scope of consumption tax
       altogether (e.g. wages — not a 'supply').

   Filing cadence (corrected per NTA / JETRO verification):
     - All JCT taxpayers file annually within 2 months of fiscal
       year-end (corporations) or by March 31 of the following year
       (sole proprietors).
     - Interim filing additionally required when prior-year JCT
       exceeded JPY 480,000.
     - The JPY 50,000,000 base-period-sales threshold gates the
       *simplified taxation system* (簡易課税制度) — not the annual-vs-
       interim cadence (a common misconception).
     - The JPY 10,000,000 base-period-sales threshold determines
       whether a business is a JCT taxpayer at all.

   This module computes the values that go on the JCT return; the
   Qualified Invoice System (QIS, 適格請求書) is invoicing-side and
   lives in `invoice.clj`.

   No clearance-token flow (ADR-018) — JP does not use real-time
   government attestation."
  (:require [kontor.l10n-jp.chart]                ; for tag identity refs
            [kontor.money :as money]
            [kontor.report :as report]))

(defn- bd ^java.math.BigDecimal [m] (:amount m))
(defn- m-zero [] (money/zero :JPY))
(defn- m-cents [m]
  ;; JPY has 0 fractional digits per :kontor.commodity/precision; round to whole yen.
  (money/money
   (.setScale (bd m) 0 java.math.RoundingMode/HALF_EVEN)
   :JPY))

;; ============================================================================
;; JCT rates
;; ============================================================================

(def standard-rate 0.10M)
(def reduced-rate  0.08M)

;; ============================================================================
;; JCT classes — the invoicing-side compute (research note 100 migration)
;; ============================================================================

(def jct-class->rate
  "JCT class → BigDecimal output-tax rate.

   - `:standard` (10%) + `:reduced` (8%) collect JCT.
   - `:non-taxable` (非課税 — financial services, residential rent,
     healthcare, education, land, securities), `:export-exempt`
     (免税 — exports, international transport / communications), and
     `:out-of-scope` (不課税 — wages, donations: outside the scope of
     consumption tax entirely) all carry a 0M rate but with distinct
     input-tax-credit semantics (see the ns docstring). The
     arithmetic is identical; the audit / filing meaning is not."
  {:standard      standard-rate
   :reduced       reduced-rate
   :non-taxable   0M
   :export-exempt 0M
   :out-of-scope  0M})

(def jct-classes
  "The set of valid `:jct-class` values."
  (set (keys jct-class->rate)))

(def zero-jct-classes
  "JCT classes whose arithmetic produces 0% output JCT — the three
   distinct zero kinds. None of them emit a JCT leg."
  #{:non-taxable :export-exempt :out-of-scope})

(defn- assert-jct-class! [jct-class]
  (when-not (contains? jct-classes jct-class)
    (throw (ex-info "Invalid :jct-class"
                    {:value jct-class :valid jct-classes})))
  jct-class)

(defn rate-for
  "Return the BigDecimal output-JCT rate for a `:jct-class`. Public
   helper for callers that want the rate without computing tax."
  [jct-class]
  (assert-jct-class! jct-class)
  (get jct-class->rate jct-class))

(defn compute-tax
  "Compute the per-line JCT breakdown for one Japanese sales line.

   This is the *invoicing-side* JCT compute — the published-rate
   source of truth that `kontor.l10n-jp.tax-provider` wraps into a
   `TaxFacts`. It is complementary to `compute-return` above, which
   is the *filing-side* aggregation over tagged ledger postings.

   Required input:
     :line       BigDecimal | Money | number — net taxable amount.

   Optional input:
     :jct-class  keyword in `jct-classes` (default :standard).

   Returns:
     {:net        Money :JPY  ; echoed, rounded to whole yen
      :jct        Money :JPY  ; output JCT (0 for the three zero kinds)
      :total-gross Money :JPY ; net + jct
      :jct-class  keyword     ; echoed
      :rate       BigDecimal} ; the rate applied (informational)

   JPY has no sub-unit (`:kontor.commodity/precision 0`); every Money is
   rounded to whole yen HALF-EVEN — the same rounding the JCT return
   uses (`m-cents`). Rate logic is unchanged: `:standard` → 10%,
   `:reduced` → 8%, the three zero kinds → 0.

   Examples:
     (compute-tax {:line 100000M})
       → {:net ¥100000 :jct ¥10000 :total-gross ¥110000 :rate 0.10 …}
     (compute-tax {:line 1000M :jct-class :reduced})
       → {:net ¥1000 :jct ¥80 :total-gross ¥1080 :rate 0.08 …}
     (compute-tax {:line 1000M :jct-class :export-exempt})
       → {:net ¥1000 :jct ¥0 :total-gross ¥1000 :rate 0 …}"
  [{:keys [line jct-class] :or {jct-class :standard}}]
  (assert-jct-class! jct-class)
  (let [net-bd (cond
                 (instance? java.math.BigDecimal line) line
                 (number? line) (bigdec line)
                 (and (map? line) (contains? line :amount)) (:amount line)
                 :else (throw (ex-info "compute-tax: cannot coerce :line to BigDecimal"
                                       {:value line})))
        net-m  (m-cents (money/money net-bd :JPY))
        rate   (get jct-class->rate jct-class)
        jct-m  (if (zero? (.signum ^java.math.BigDecimal rate))
                 (m-zero)
                 (m-cents (money/money (.multiply net-bd rate) :JPY)))]
    {:net         net-m
     :jct         jct-m
     :total-gross (money/add net-m jct-m)
     :jct-class   jct-class
     :rate        rate}))

;; ============================================================================
;; Return computation
;; ============================================================================

(def jct-base-report-definition
  "Lines computable from kernel postings via the JP account tags."
  {:report/name    "JCT Return — Consumption Tax"
   :report/country "JP"
   :report/lines
   [{:line/code "sales-10" :line/label "Sales taxable at 10%"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-sales-10]
                       :sign :inflow :commodity :JPY}}
    {:line/code "sales-8"  :line/label "Sales taxable at 8% (reduced)"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-sales-8]
                       :sign :inflow :commodity :JPY}}
    {:line/code "sales-exempt" :line/label "Exempt sales"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-sales-exempt]
                       :sign :inflow :commodity :JPY}}
    {:line/code "sales-zero"   :line/label "Zero-rated sales (exports)"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-sales-zero]
                       :sign :inflow :commodity :JPY}}
    {:line/code "purchases-10" :line/label "Purchases taxable at 10%"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-purchases-10]
                       :sign :inflow :commodity :JPY}}
    {:line/code "purchases-8"  :line/label "Purchases taxable at 8%"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-purchases-8]
                       :sign :inflow :commodity :JPY}}
    {:line/code "jct-out-10"   :line/label "JCT collected at 10%"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-output-10]
                       :sign :inflow :commodity :JPY}}
    {:line/code "jct-out-8"    :line/label "JCT collected at 8%"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-output-8]
                       :sign :inflow :commodity :JPY}}
    {:line/code "jct-in-10"    :line/label "JCT paid at 10% (input credit)"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-input-10]
                       :sign :inflow :commodity :JPY}}
    {:line/code "jct-in-8"     :line/label "JCT paid at 8% (input credit)"
     :line/expression {:engine :tax-tags :tags [:jp-jct-line-input-8]
                       :sign :inflow :commodity :JPY}}]})

(defn compute-return
  "Compute a JCT return for a period.

   Opts:
     :from, :to   — explicit half-open period bounds
     :year        — alternative: full fiscal year (Jan 1 – Jan 1 of next)

   Returns:
     {:return/form    \"JCT\"
      :return/period  {:from :to ...}
      :return/lines   {<line-code> Money :JPY ...}
      :return/jct-collected   Money
      :return/jct-deductible  Money
      :return/jct-net         Money   ; positive = pay; negative = refund
      :return/outcome         :payment | :refund | :nil-return}"
  [conn {:keys [from to year] :as _opts}]
  (let [zero (m-zero)
        period (cond
                 (and from to) {:from from :to to :kind :explicit}
                 year (let [start (java.time.LocalDate/of (int year) 1 1)
                            end   (java.time.LocalDate/of (int (inc year)) 1 1)
                            ->date #(java.util.Date/from
                                     (.toInstant
                                      (.atStartOfDay % java.time.ZoneOffset/UTC)))]
                        {:from (->date start) :to (->date end)
                         :kind :annual :year year})
                 :else (throw (ex-info "compute-return needs :from/:to or :year" {})))
        r (report/compute-report conn jct-base-report-definition
                                 {:from (:from period) :to (:to period)})
        vals* (into {}
                    (map (fn [l] [(:line/code l) (:line/value l)]))
                    (:report/lines r))
        get* (fn [k] (or (vals* k) zero))
        sales-10        (get* "sales-10")
        sales-8         (get* "sales-8")
        sales-exempt    (get* "sales-exempt")
        sales-zero      (get* "sales-zero")
        purchases-10    (get* "purchases-10")
        purchases-8     (get* "purchases-8")
        jct-out-10      (get* "jct-out-10")
        jct-out-8       (get* "jct-out-8")
        jct-in-10       (get* "jct-in-10")
        jct-in-8        (get* "jct-in-8")
        collected       (money/add jct-out-10 jct-out-8)
        deductible      (money/add jct-in-10 jct-in-8)
        net             (money/sub collected deductible)
        sign            (.signum ^java.math.BigDecimal (:amount net))]
    {:return/form    "JCT"
     :return/period  period
     :return/lines   {:sales-10     (m-cents sales-10)
                      :sales-8      (m-cents sales-8)
                      :sales-exempt (m-cents sales-exempt)
                      :sales-zero   (m-cents sales-zero)
                      :purchases-10 (m-cents purchases-10)
                      :purchases-8  (m-cents purchases-8)
                      :jct-out-10   (m-cents jct-out-10)
                      :jct-out-8    (m-cents jct-out-8)
                      :jct-in-10    (m-cents jct-in-10)
                      :jct-in-8     (m-cents jct-in-8)}
     :return/jct-collected  (m-cents collected)
     :return/jct-deductible (m-cents deductible)
     :return/jct-net        (m-cents net)
     :return/outcome (cond
                       (neg? sign) :refund
                       (pos? sign) :payment
                       :else       :nil-return)}))

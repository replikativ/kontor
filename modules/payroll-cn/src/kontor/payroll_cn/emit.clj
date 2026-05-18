(ns kontor.payroll-cn.emit
  "CN payroll emit provider — produces the monthly IIT filing audit-doc
   for upload to 自然人电子税务局 (Natural Person Electronic Tax Bureau).

   ## Posture (note 87 §6 + ADR-085)

   The 自然人电子税务局 desktop importer takes a structured XML
   (申报表 2024-04 schema). We emit a structured CSV payload as the
   v1 audit-doc; the consumer's importer (or a third-party convertor)
   bridges to XML. This mirrors the DE-DATEV pattern (LODAS
   Importdatei is regulator-bound, we emit ISO-8859-1 CSV-style
   semicolon text; the consumer's DATEV agent does the upload).

   What we emit:

     - One `:audit-doc` per pay-period with
         :audit-doc/category :payroll-filing
         :audit-doc/language :zh-cn
         :audit-doc/type :emit-payload
         :audit-doc/inline-payload — the structured CSV payload
         :audit-doc/code — \"CN-IIT-<period-code>-<entity-code>\"
         :audit-doc/storage-uri — file://iit/...

   What we deliberately do NOT do:

     - We do NOT bundle XML schema validation (申报表 2024-04 is
       regulator-versioned).
     - We do NOT bundle 自然人电子税务局 credentials.
     - We do NOT auto-upload — emission is consumer-side.

   See doc/research/87-cn-payroll-research-before.md §6."
  (:require [clojure.string :as str]
            [kontor.payroll-cn.iit :as iit]
            [kontor.payroll-cn.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:private utc (TimeZone/getTimeZone "UTC"))

(defn- format-period-code
  "Render a pay-period code from a Date or pre-formatted string.
   Format: YYYY-MM for a monthly period."
  [period-code period-date]
  (cond
    (and (string? period-code) (not (str/blank? period-code)))
    period-code

    (instance? Date period-date)
    (let [fmt (SimpleDateFormat. "yyyy-MM")]
      (.setTimeZone fmt utc)
      (.format fmt ^Date period-date))

    :else
    (throw (ex-info "Need :pay-period-code or :pay-period-date"
                    {:type :cn-payroll/missing-period}))))

(defn- format-cents
  "Render a BigDecimal as a CNY-cents string with HALF-EVEN rounding."
  [^BigDecimal x]
  (-> x (.setScale 2 java.math.RoundingMode/HALF_EVEN) .toPlainString))

(defn- csv-cell
  "Escape a CSV cell — wrap in double-quotes when the cell contains a
   comma, quote, or newline. RFC 4180 style."
  [v]
  (let [s (cond
            (nil? v) ""
            (instance? BigDecimal v) (format-cents v)
            (instance? Date v)
            (let [fmt (SimpleDateFormat. "yyyy-MM-dd")]
              (.setTimeZone fmt utc)
              (.format fmt ^Date v))
            :else (str v))]
    (if (some #(or (= % \,) (= % \") (= % \newline) (= % \return)) s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn- join-row [cells]
  (str/join "," (mapv csv-cell cells)))

;; ============================================================================
;; The IIT-monthly CSV payload (note 87 §6)
;; ============================================================================
;;
;; Row shape (zh-cn + en bilingual headers):
;;
;;   员工编号,员工姓名,所属期,工资薪金,扣除社保,扣除公积金,扣除个税,
;;   年终奖金额,年终奖计税方法,实发工资
;;
;;   employee-id, employee-name, period, wage-income, si-deduction,
;;   hf-deduction, iit-withheld, annual-bonus, annual-bonus-method,
;;   net-pay

(def csv-header
  "Bilingual header row — Chinese + English keys separated by ' / '."
  ["员工编号 / employee-id"
   "员工姓名 / employee-name"
   "所属期 / period"
   "工资薪金 / wage-income"
   "扣除社保 / si-deduction"
   "扣除公积金 / hf-deduction"
   "扣除个税 / iit-withheld"
   "年终奖金额 / annual-bonus"
   "年终奖计税方法 / annual-bonus-method"
   "实发工资 / net-pay"])

(defn- ee-name-from-fact
  "Extract a display name for a fact. Three sources, in order:
     1. `:employee-name` directly on the fact.
     2. `:jurisdiction-specific-codes :cn/employee-name`.
     3. `:jurisdiction-specific-codes :employee-external-id`."
  [fact]
  (or (:employee-name fact)
      (get-in fact [:jurisdiction-specific-codes :cn/employee-name])
      (get-in fact [:jurisdiction-specific-codes :employee-external-id])
      (str (:employment fact))))

(defn- ee-id-from-fact
  [fact]
  (or (get-in fact [:jurisdiction-specific-codes :employee-external-id])
      (str (:employment fact))))

(defn- sum-by-asbe-sub-account
  "Sum negated (positive-magnitude) deduction amounts for a fact's
   employee-side components whose kind's :asbe-sub-account matches
   the supplied bucket (:si | :hf)."
  ^BigDecimal [fact bucket extras-map]
  (reduce (fn [a {:keys [kind amount employer-side?]}]
            (if (and (not employer-side?)
                     (= bucket (wt/asbe-sub-account kind extras-map))
                     (neg? (compare ^BigDecimal amount 0M)))
              (.add ^BigDecimal a (.abs ^BigDecimal amount))
              a))
          0M
          (:components fact)))

(defn- fact->row
  [fact period-label extras-map]
  (let [emp-id (ee-id-from-fact fact)
        emp-name (ee-name-from-fact fact)
        wage (:gross fact)
        si-ded (sum-by-asbe-sub-account fact :si extras-map)
        hf-ded (sum-by-asbe-sub-account fact :hf extras-map)
        iit-summary (iit/iit-summary-per-employee [fact] extras-map)
        {:keys [iit annual-bonus annual-bonus-method]} (first iit-summary)
        net (:net fact)]
    [emp-id emp-name period-label wage si-ded hf-ded iit
     annual-bonus (some-> annual-bonus-method name)
     net]))

(defn render-iit-monthly-csv
  "Render the per-period IIT monthly CSV payload. Returns a string
   suitable for `:audit-doc/inline-payload`. Each row carries one
   employee. Per note 87 §6.

   Required keys:
     :facts            — vector of PayrollFacts
     :pay-period-code  — string (or supply :pay-period-date)

   Optional keys:
     :pay-period-date  — java.util.Date (fallback for :pay-period-code)
     :extras-map       — :component-kind catalog extras for the rows"
  [{:keys [facts pay-period-code pay-period-date extras-map]}]
  (let [period (format-period-code pay-period-code pay-period-date)
        rows (mapv #(fact->row % period extras-map) facts)
        all (cons csv-header rows)]
    (str (str/join "\n" (mapv join-row all))
         "\n")))

;; ============================================================================
;; CnIitMonthlyEmitProvider — the PayrollEmitProvider impl
;; ============================================================================

(defrecord CnIitMonthlyEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ facts {:keys [pay-period-eid entity-eid]}]
    (when-not (seq facts)
      (throw (ex-info "CnIitMonthlyEmitProvider: no PayrollFacts to emit"
                      {:type :cn-payroll/no-facts})))
    (let [{:keys [pay-period-code pay-period-date entity-code uri-prefix
                  extras-map]} opts
          period (format-period-code pay-period-code pay-period-date)
          payload (render-iit-monthly-csv
                   {:facts facts
                    :pay-period-code pay-period-code
                    :pay-period-date pay-period-date
                    :extras-map extras-map})
          ent-suffix (or entity-code (str entity-eid) "entity")
          code (str "CN-IIT-" period "-" ent-suffix)
          uri (str (or uri-prefix "file://iit/")
                   period "/" ent-suffix ".csv")]
      [{:audit-doc/code code
        :audit-doc/type :emit-payload
        :audit-doc/category :payroll-filing
        :audit-doc/language :zh-cn
        :audit-doc/storage-uri uri
        :audit-doc/uploaded-at (java.util.Date.)
        :audit-doc/inline-payload payload
        :audit-doc/payroll-period pay-period-eid
        :audit-doc/payroll-entity entity-eid
        ;; All facts mapped — :iit-withheld is a known kind so we
        ;; report 0 unmapped. This field reuses the DE/CA convention
        ;; (note 86 P0-86-1).
        :audit-doc/unmapped-count 0}])))

(defn make-provider
  "Construct a `CnIitMonthlyEmitProvider`. Required opts:

     :pay-period-code  — string used in the audit-doc code + filename
                          (or supply :pay-period-date for derivation)
     :entity-code      — string used in the audit-doc code + filename

   Optional:
     :pay-period-date  — java.util.Date; if :pay-period-code is nil,
                          derive YYYY-MM from this
     :uri-prefix       — string prefix for :audit-doc/storage-uri
     :extras-map       — :component-kind catalog extras"
  [opts]
  (when-not (or (:pay-period-code opts) (:pay-period-date opts))
    (throw (ex-info ":pay-period-code or :pay-period-date required" {})))
  (->CnIitMonthlyEmitProvider opts))

(ns kontor.payroll-cn.compute
  "CN payroll compute providers — file-ingest CSV adapters for the
   three dominant Chinese payroll engines (用友 Yonyou NC / NCC / U8+,
   金蝶 Kingdee K/3 + Cloud, 北森 Beisen) per note 87 §5.

   ## Architectural posture (note 87 §5 + ADR-075)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for gross-to-net:
     - IIT cumulative-method withholding (国家税务总局公告 2018年第61号)
     - per-city 五险一金 rate + base-cap / base-floor lookup
     - 年终奖 special-tax-treatment election application
   This namespace's job is to PARSE the engine's CSV export and shape
   it into `PayrollFacts` per `kontor.payroll-provider`.

   ## Provider trio (note 87 §5)

   - `YonyouCsvComputeProvider`  — config-driven CSV (default column
     mapping matches Yonyou NC export). Per-customer column variation
     handled via a `:column-mapping` opts map.
   - `KingdeeCsvComputeProvider` — same config-driven core; default
     column mapping matches Kingdee K/3 Cloud export.
   - `BeisenCsvComputeProvider`  — same config-driven core; default
     column mapping matches Beisen SaaS export.

   All three use the same `parse-cn-csv` parser; they differ only in
   the `provider-id` recorded for audit logging + the default
   `:column-mapping`.

   ## License posture (note 87 §8)

   - CSV column schemas are described from public vendor documentation;
     no vendor source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-cn.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips
   commas, currency symbols, and the CN Yuan symbol (¥ / ￥)."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim
                      (str/replace #"[¥￥$,，\s]" ""))]
      (if (str/blank? cleaned) 0M (BigDecimal. cleaned)))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace. Preserves CJK characters as-is so
   header names like 员工编号 (employee-id) can be matched directly."
  [s]
  (when s (-> s str/trim (str/replace #"[\s_]+" "-"))))

(defn- coerce-reader
  "Coerce CSV source into a java.io.Reader. Tolerates a path String
   that points at a real CSV file vs a String that IS the CSV text:
   if the value contains a newline OR a comma, it's treated as
   inline CSV text; otherwise it's passed to `io/reader` directly."
  [source]
  (cond
    (instance? java.io.Reader source) source
    (and (string? source)
         (or (.contains ^String source "\n")
             (.contains ^String source ",")))
    (java.io.StringReader. ^String source)
    :else (io/reader source)))

(defn- read-csv-rows
  "Read CSV string/reader into a vector of maps keyed by normalized
   header names. Drops empty rows + BOM."
  [source]
  (with-open [r (coerce-reader source)]
    (let [rows (csv/read-csv r)
          [header & data] rows
          ;; Strip optional UTF-8 BOM from the first header cell.
          header (if (and (seq header) (string? (first header)))
                   (cons (str/replace (first header) #"^﻿" "")
                         (rest header))
                   header)
          headers (mapv normalize-header header)]
      (->> data
           (remove (fn [row] (every? str/blank? row)))
           (mapv (fn [row] (zipmap headers row)))))))

(defn- sum-amounts
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

;; ============================================================================
;; Per-employee fact assembly
;; ============================================================================

(defn- components->fact
  "Given a vector of {:kind :amount :employer-side?} components for ONE
   employee, derive {:gross :net :components} per the substrate's sum
   invariant. Carry-only components (e.g. :si-base) do NOT participate
   in gross/net but flow through :jurisdiction-specific-codes for the
   audit-doc per ADR-075."
  [{:keys [employment-eid pay-period-eid commodity-eid components
           extras-map jurisdiction-specific-codes]}]
  (let [posting-comps (filterv #(wt/posts? (:kind %) extras-map) components)
        carry-comps   (remove #(wt/posts? (:kind %) extras-map) components)
        pos-employee
        (->> posting-comps
             (remove :employer-side?)
             (map :amount)
             (filter #(pos? (compare ^BigDecimal % 0M)))
             sum-amounts)
        neg-employee
        (->> posting-comps
             (remove :employer-side?)
             (map :amount)
             (filter #(neg? (compare ^BigDecimal % 0M)))
             sum-amounts)
        gross pos-employee
        net (.add ^BigDecimal gross ^BigDecimal neg-employee)
        carry-codes
        (reduce (fn [m {:keys [kind amount]}]
                  (assoc m kind amount))
                {} carry-comps)]
    (cond-> {:employment employment-eid
             :gross gross
             :net net
             :components posting-comps
             :jurisdiction-specific-codes
             (merge {} jurisdiction-specific-codes carry-codes)}
      pay-period-eid (assoc :pay-period pay-period-eid)
      commodity-eid  (assoc :commodity commodity-eid))))

;; ============================================================================
;; The shared CN CSV parser
;; ============================================================================
;;
;; Column-mapping shape (consumer-supplied or per-provider default):
;;
;;   {:employee-id-col   "员工编号"   ; or "employee-id"
;;    :pay-element-col   "工资项目"   ; or "pay-element"
;;    :debit-col         "借方"       ; or "debit"
;;    :credit-col        "贷方"       ; or "credit"
;;    :amount-col        "金额"       ; OR debit/credit; if present
;;                                      it's used as a signed amount
;;    :employer-side-col "单位/个人"} ; informational; if "单位" the row
;;                                      is employer-side
;;
;; Either (debit-col + credit-col) OR (amount-col) is required. Most
;; CN engines emit a signed amount-col since they encode SI/HF and
;; deductions with explicit negative values.

(defn- cn-row->component
  "One CSV row → one component map. Returns nil if the row is a
   pay-group header / blank / balancing row.

   `:pay-element-codes` is the consumer-supplied lookup from the
   engine's wage-element code (Yonyou 工资项目 / Kingdee 薪酬项目 /
   Beisen pay-element-code) to a kontor `:component-kind` keyword."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "员工编号")
        pe-col  (or (:pay-element-col column-mapping) "工资项目")
        amount-col (:amount-col column-mapping)
        dr-col  (or (:debit-col column-mapping) "借方")
        cr-col  (or (:credit-col column-mapping) "贷方")
        emp     (get row emp-col)
        pe      (get row pe-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe)))
      (let [amount (if amount-col
                     (coerce-bigdec (get row amount-col))
                     (let [debit  (coerce-bigdec (get row dr-col))
                           credit (coerce-bigdec (get row cr-col))]
                       (.subtract ^BigDecimal debit ^BigDecimal credit)))
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown CN pay-element code: " pe)
                          {:type :cn-payroll/unknown-pay-element
                           :pay-element pe
                           :employee-id emp
                           :hint "Add an entry to :pay-element-codes mapping this engine code to a kontor :component-kind keyword (or :__skip if it's a balancing-row artifact)."})))
        (when-not (= :__skip mapping)
          (let [kind (if (map? mapping) (:kind mapping) mapping)
                employer? (or (and (map? mapping) (:employer-side? mapping))
                              (wt/employer-side? kind))]
            {:employee-external-id emp
             :kind kind
             :amount amount
             :employer-side? (boolean employer?)}))))))

(defn parse-cn-csv
  "Parse a CN payroll engine CSV (string or Reader) into a vector of
   `{:employee-external-id :kind :amount :employer-side?}` maps.

   Required opts:
     :pay-element-codes — engine code → kontor :component-kind (or
                          :__skip for engine-internal balancing rows).

   Optional opts:
     :column-mapping    — per-customer column name overrides; defaults
                          match Yonyou (员工编号 / 工资项目 / 金额 or
                          借方/贷方). Use the keyword keys documented
                          above."
  [source opts]
  (when-not (:pay-element-codes opts)
    (throw (ex-info "parse-cn-csv needs :pay-element-codes" {})))
  (->> (read-csv-rows source)
       (mapv #(cn-row->component % opts))
       (remove nil?)
       vec))

(defn cn-facts
  "Group parsed CSV rows by employee-external-id and assemble
   PayrollFacts.

   `external-id->eid` is a function (employee-external-id → :employment
   eid) the consumer supplies; this keeps kontor agnostic to how the
   engine identifies employees.

   `jurisdiction-codes-fn` (optional) is a function (external-id → map)
   producing the per-employee `:jurisdiction-specific-codes` payload —
   typical use: `{:cn/social-insurance-city \"CN-BJ-110100\"
   :cn/annual-bonus-method :single}`."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map jurisdiction-codes-fn engine-id]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee external-id"
                                   {:type :cn-payroll/unknown-employee
                                    :employee-external-id ext-id})))
                 (components->fact
                  {:employment-eid emp-eid
                   :pay-period-eid pay-period-eid
                   :commodity-eid commodity-eid
                   :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   (merge {:engine (or engine-id :cn-csv)
                           :employee-external-id ext-id}
                          (when jurisdiction-codes-fn
                            (jurisdiction-codes-fn ext-id)))}))))))

;; ============================================================================
;; Default column mappings per engine (note 87 §5)
;; ============================================================================

(def yonyou-default-column-mapping
  "Yonyou NC / U8+ default column names. Per public Yonyou API docs the
   wage-export carries the CN canonical labels."
  {:employee-id-col "员工编号"
   :pay-element-col "工资项目"
   :amount-col      "金额"})

(def kingdee-default-column-mapping
  "Kingdee K/3 + Cloud default column names. Per public Kingdee admin
   docs the export uses 薪酬项目 as the wage-type column."
  {:employee-id-col "员工编号"
   :pay-element-col "薪酬项目"
   :amount-col      "金额"})

(def beisen-default-column-mapping
  "Beisen SaaS default column names. The export favours English-keyed
   columns (Beisen targets multinational customers)."
  {:employee-id-col "employee-id"
   :pay-element-col "pay-element-code"
   :amount-col      "amount"})

;; ============================================================================
;; Provider records
;; ============================================================================

(defrecord YonyouCsvComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :yonyou-csv)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          mapping (merge yonyou-default-column-mapping
                         (:column-mapping opts)
                         (:column-mapping ctx))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          jur-fn (or (:jurisdiction-codes-fn ctx)
                     (:jurisdiction-codes-fn opts))]
      (when-not source (throw (ex-info "YonyouCsvComputeProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "YonyouCsvComputeProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "YonyouCsvComputeProvider needs :external-id->eid" {})))
      (let [parsed (parse-cn-csv source {:column-mapping mapping
                                         :pay-element-codes codes})]
        (cn-facts parsed
                  {:external-id->eid ext->eid
                   :pay-period-eid (:pay-period-eid ctx)
                   :commodity-eid commodity-eid
                   :extras-map extras
                   :jurisdiction-codes-fn jur-fn
                   :engine-id :yonyou})))))

(defrecord KingdeeCsvComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :kingdee-csv)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          mapping (merge kingdee-default-column-mapping
                         (:column-mapping opts)
                         (:column-mapping ctx))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          jur-fn (or (:jurisdiction-codes-fn ctx)
                     (:jurisdiction-codes-fn opts))]
      (when-not source (throw (ex-info "KingdeeCsvComputeProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "KingdeeCsvComputeProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "KingdeeCsvComputeProvider needs :external-id->eid" {})))
      (let [parsed (parse-cn-csv source {:column-mapping mapping
                                         :pay-element-codes codes})]
        (cn-facts parsed
                  {:external-id->eid ext->eid
                   :pay-period-eid (:pay-period-eid ctx)
                   :commodity-eid commodity-eid
                   :extras-map extras
                   :jurisdiction-codes-fn jur-fn
                   :engine-id :kingdee})))))

(defrecord BeisenCsvComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :beisen-csv)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          mapping (merge beisen-default-column-mapping
                         (:column-mapping opts)
                         (:column-mapping ctx))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          jur-fn (or (:jurisdiction-codes-fn ctx)
                     (:jurisdiction-codes-fn opts))]
      (when-not source (throw (ex-info "BeisenCsvComputeProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "BeisenCsvComputeProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "BeisenCsvComputeProvider needs :external-id->eid" {})))
      (let [parsed (parse-cn-csv source {:column-mapping mapping
                                         :pay-element-codes codes})]
        (cn-facts parsed
                  {:external-id->eid ext->eid
                   :pay-period-eid (:pay-period-eid ctx)
                   :commodity-eid commodity-eid
                   :extras-map extras
                   :jurisdiction-codes-fn jur-fn
                   :engine-id :beisen})))))

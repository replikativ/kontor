(ns kontor.payroll-jp.compute
  "JP payroll compute providers — file-ingest CSV adapters for the
   dominant Japanese engines (freee人事労務 / Money Forwardクラウド
   給与 / 弥生給与 / 給与奉行) plus a stub for direct API access.

   Reference: ADR-084 §2.

   ## Architectural posture (ADR-084 §2 + ADR-075)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for gross-to-net — 4-bucket statutory SI rate
   tables (per-prefecture 健保, age-bracket 介護, industry 雇用),
   月額表 / 賞与表 income-tax brackets, 年末調整 / Nenmatsu Chosei
   reconciliation. This namespace's job is to PARSE the engine's
   payroll-result file and shape it into `PayrollFacts` per
   `kontor.payroll-provider`.

   ## Provider quartet

   - `FreeeProvider` — freee人事労務 CSV export. freee is the largest
     SaaS payroll engine in Japan by SMB market share (~40%);
     the CSV column layout is per-customer-configurable, so a
     `:column-mapping` opts map carries the load-bearing field
     names. Reference: freee 給与計算 CSV 出力 仕様
     (https://support.freee.co.jp/hc/ja/articles/202588010).

   - `MoneyForwardProvider` — Money Forwardクラウド給与 CSV export.
     MF is the runner-up; similar shape to freee but column
     headers differ. Reference: Money Forward 給与計算 CSV エクスポート
     (https://biz.moneyforward.com/support/payroll/guide/import-export/).

   - `YayoiProvider` — 弥生給与 GL-export CSV (on-prem desktop;
     dominant in the larger-SMB / hand-bookkeeping market). Reference:
     弥生給与 CSV エクスポート (https://support.yayoi-kk.co.jp/).

   - `PcaKyuyoApiProvider` — 給与奉行 cloud API skeleton. PCA's API
     is partner-gated; the consumer supplies OAuth credentials. This
     skeleton documents the protocol surface; live wiring lands when
     a PCA partner-program consumer surfaces.

   All four providers share the same `:pay-element-codes` lookup
   contract — the consumer maps the engine's wage-type code (e.g.
   freee's `基本給` / `健康保険料` / `所得税`, Money Forward's
   `salary` / `health_insurance` / `income_tax`, etc.) to a kontor
   `:component-kind` keyword.

   ## License posture (ADR-084 license-posture preamble)

   - CSV column schemas are described from public vendor support
     documentation; no vendor source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine → kontor mapping via `:pay-element-codes`.
   - No per-prefecture SI rate tables bundled — engine is
     authoritative for the math."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-jp.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips
   commas + currency symbols (¥, JPY). Refuses doubles.

   JPY has no sub-yen unit (precision 0 per ADR-013); the parser
   accepts decimal cells (some engines emit `1500.0`) but the values
   round to whole yen downstream."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim
                      (str/replace #"[¥￥,円]" "")
                      (str/replace #"^JPY\s*" ""))]
      (if (str/blank? cleaned) 0M (BigDecimal. cleaned)))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace; preserves CJK characters so the
   engine's Kanji header names (e.g. 基本給, 健康保険料) survive."
  [s]
  (when s (-> s str/trim (str/replace #"\s+" ""))))

(defn- read-csv-rows
  "Read CSV string/reader into a vector of maps keyed by normalized
   header names. Drops empty rows. The reader is opened with
   :encoding \"UTF-8\" via io/reader (default in modern JVMs).
   freee + MF emit UTF-8 with BOM; the helper strips the BOM from
   the first column header if present."
  [source]
  (with-open [r (io/reader source)]
    (let [rows (csv/read-csv r)
          [header & data] rows
          ;; strip UTF-8 BOM (U+FEFF) from first header if present
          header (if (and (seq header)
                          (pos? (count (first header)))
                          (= \ufeff (.charAt ^String (first header) 0)))
                   (cons (subs (first header) 1) (rest header))
                   header)
          headers (mapv normalize-header header)]
      (->> data
           (remove (fn [row] (every? str/blank? row)))
           (mapv (fn [row] (zipmap headers row)))))))

(defn- sum-amounts
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

;; ============================================================================
;; Per-employee fact assembly (shared across all providers)
;; ============================================================================

(defn- components->fact
  "Given a vector of {:kind :amount :employer-side?} components for ONE
   employee, derive {:gross :net :components} per the substrate's sum
   invariant. Carry-only components (e.g. :gensen-taxable-income) do
   NOT participate in gross/net but are forwarded as
   :jurisdiction-specific-codes so the Gensen builder can read them.

   The carry-only kinds flow through `:jurisdiction-specific-codes`
   per ADR-075 so the substrate `check-facts` doesn't choke on them."
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

(defn- resolve-mapping
  "Look up an engine pay-element code in the consumer-supplied
   :pay-element-codes map. The map value can be either a bare
   :component-kind keyword OR a richer
   `{:kind … :employer-side? …}` map for codes that override the
   catalog's default flag (e.g. an engine that emits a single
   'long-term-care' code that's age-dependent — the consumer's
   mapping overrides per employee profile)."
  [pay-element-codes code]
  (let [mapping (get pay-element-codes code)]
    (when (nil? mapping)
      (throw (ex-info (str "Unknown JP pay-element code: " code)
                      {:pay-element-code code
                       :available-codes (set (keys pay-element-codes))})))
    (if (map? mapping)
      mapping
      {:kind mapping
       :employer-side? (wt/employer-side? mapping)})))

;; ============================================================================
;; FreeeProvider — freee人事労務 CSV
;; ============================================================================
;;
;; freee's export format (per support article 202588010): one row per
;; (employee × pay-element). Per-customer column layout, so the
;; adapter takes a `:column-mapping` opts map. Defaults match freee's
;; standard 給与計算 CSV column names.

(defn- freee-row->component
  "One CSV row → one component map. Returns nil if the row is a
   header / total row / blank row.

   Per ADR-084 §2.1 the column layout is per-customer-configurable
   in freee — the adapter takes a `:column-mapping` opts map for the
   load-bearing fields:

     :employee-id-col     defaults '従業員番号' (employee-number)
     :pay-element-col     defaults '項目名' (item-name)
     :amount-col          defaults '金額' (amount)
     :category-col        defaults '区分' (kind)
                          (freee marks each row as 支給 (earnings),
                          控除 (deduction), or 集計 (summary).
                          Summary rows are dropped here.)

   `:pay-element-codes` is the consumer-supplied lookup from the
   engine's wage-element name (e.g. '基本給' / '健康保険料' /
   '所得税') to a kontor `:component-kind` keyword."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "従業員番号")
        pe-col  (or (:pay-element-col column-mapping) "項目名")
        amt-col (or (:amount-col column-mapping) "金額")
        cat-col (or (:category-col column-mapping) "区分")
        emp     (get row emp-col)
        pe      (get row pe-col)
        cat     (get row cat-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe))
               ;; Drop summary / total rows.
               (not (#{"集計" "合計" "総支給" "差引支給"} cat)))
      (let [amount (coerce-bigdec (get row amt-col))
            ;; In freee's export, 控除 (deduction) rows carry a
            ;; POSITIVE amount but indicate a withholding; we sign-
            ;; flip them to match kontor's convention (deductions
            ;; are negative employee-side amounts).
            signed (if (= cat "控除")
                     (.negate ^BigDecimal amount)
                     amount)
            {:keys [kind employer-side?]} (resolve-mapping pay-element-codes pe)]
        {:employee-external-id emp
         :kind kind
         :amount signed
         :employer-side? (boolean employer-side?)}))))

(defn parse-freee-csv
  "Parse a freee人事労務 CSV (string, file, or Reader) into a vector
   of `{:employee-external-id :kind :amount :employer-side?}` maps."
  [source opts]
  (->> (read-csv-rows source)
       (mapv #(freee-row->component % opts))
       (remove nil?)
       vec))

(defn freee-facts
  "Group a parsed freee CSV by employee-external-id and assemble
   PayrollFacts.

   `external-id->eid` is a function (employee-external-id →
   :employment eid) the consumer supplies; this keeps kontor agnostic
   to how the engine identifies employees."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee external-id"
                                   {:employee-external-id ext-id})))
                 (components->fact
                  {:employment-eid emp-eid
                   :pay-period-eid pay-period-eid
                   :commodity-eid commodity-eid
                   :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine :freee
                    :employee-external-id ext-id}}))))))

(defrecord FreeeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :freee)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source column-mapping pay-element-codes
                  external-id->eid commodity-eid extras-map]} opts
          ;; Per-call ctx overrides static opts (the same provider may
          ;; run for multiple pay-periods).
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes (or (:pay-element-codes ctx) pay-element-codes)
          mapping (or (:column-mapping ctx) column-mapping)]
      (when-not source
        (throw (ex-info "FreeeProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "FreeeProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "FreeeProvider needs :pay-element-codes" {})))
      (let [parsed (parse-freee-csv source {:column-mapping mapping
                                            :pay-element-codes codes})]
        (freee-facts parsed
                     {:external-id->eid ext->eid
                      :pay-period-eid (:pay-period-eid ctx)
                      :commodity-eid commodity-eid
                      :extras-map extras})))))

;; ============================================================================
;; MoneyForwardProvider — Money Forwardクラウド給与 CSV
;; ============================================================================
;;
;; Money Forward's GL CSV uses English (snake_case) column headers
;; by default. The shape is otherwise similar to freee: one row per
;; (employee × pay-element).

(defn- mf-row->component
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "employee_code")
        pe-col  (or (:pay-element-col column-mapping) "item_code")
        amt-col (or (:amount-col column-mapping) "amount")
        cat-col (or (:category-col column-mapping) "category")
        emp     (get row emp-col)
        pe      (get row pe-col)
        cat     (some-> (get row cat-col) str/trim str/lower-case)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe))
               (not (#{"summary" "total" "subtotal"} cat)))
      (let [amount (coerce-bigdec (get row amt-col))
            signed (case cat
                     "deduction" (.negate ^BigDecimal amount)
                     "earning"   amount
                     "earnings"  amount
                     ;; Default: trust the amount sign as-given.
                     amount)
            {:keys [kind employer-side?]} (resolve-mapping pay-element-codes pe)]
        {:employee-external-id emp
         :kind kind
         :amount signed
         :employer-side? (boolean employer-side?)}))))

(defn parse-mf-csv
  "Parse a Money Forwardクラウド給与 CSV into the component vector."
  [source opts]
  (->> (read-csv-rows source)
       (mapv #(mf-row->component % opts))
       (remove nil?)
       vec))

(defn mf-facts
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee external-id"
                                   {:employee-external-id ext-id})))
                 (components->fact
                  {:employment-eid emp-eid
                   :pay-period-eid pay-period-eid
                   :commodity-eid commodity-eid
                   :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine :money-forward
                    :employee-external-id ext-id}}))))))

(defrecord MoneyForwardProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :money-forward)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts))]
      (when-not source (throw (ex-info "MoneyForwardProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "MoneyForwardProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "MoneyForwardProvider needs :external-id->eid" {})))
      (let [parsed (parse-mf-csv source {:column-mapping mapping
                                         :pay-element-codes codes})]
        (mf-facts parsed
                  {:external-id->eid ext->eid
                   :pay-period-eid (:pay-period-eid ctx)
                   :commodity-eid commodity-eid
                   :extras-map extras})))))

;; ============================================================================
;; YayoiProvider — 弥生給与 CSV
;; ============================================================================
;;
;; 弥生 is an on-prem desktop product; its GL CSV uses Kanji-based
;; column names (similar to freee). Reuses the freee-row parser
;; pattern with different column-mapping defaults.

(defn- yayoi-row->component
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "社員コード")
        pe-col  (or (:pay-element-col column-mapping) "支給控除項目")
        amt-col (or (:amount-col column-mapping) "金額")
        cat-col (or (:category-col column-mapping) "支給/控除")
        emp     (get row emp-col)
        pe      (get row pe-col)
        cat     (get row cat-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe))
               (not (#{"集計" "合計"} cat)))
      (let [amount (coerce-bigdec (get row amt-col))
            signed (if (= cat "控除")
                     (.negate ^BigDecimal amount)
                     amount)
            {:keys [kind employer-side?]} (resolve-mapping pay-element-codes pe)]
        {:employee-external-id emp
         :kind kind
         :amount signed
         :employer-side? (boolean employer-side?)}))))

(defn parse-yayoi-csv
  "Parse a 弥生給与 CSV into the component vector."
  [source opts]
  (->> (read-csv-rows source)
       (mapv #(yayoi-row->component % opts))
       (remove nil?)
       vec))

(defn yayoi-facts
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee external-id"
                                   {:employee-external-id ext-id})))
                 (components->fact
                  {:employment-eid emp-eid
                   :pay-period-eid pay-period-eid
                   :commodity-eid commodity-eid
                   :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine :yayoi
                    :employee-external-id ext-id}}))))))

(defrecord YayoiProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :yayoi)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts))]
      (when-not source (throw (ex-info "YayoiProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "YayoiProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "YayoiProvider needs :external-id->eid" {})))
      (let [parsed (parse-yayoi-csv source {:column-mapping mapping
                                            :pay-element-codes codes})]
        (yayoi-facts parsed
                     {:external-id->eid ext->eid
                      :pay-period-eid (:pay-period-eid ctx)
                      :commodity-eid commodity-eid
                      :extras-map extras})))))

;; ============================================================================
;; PcaKyuyoApiProvider — partner-program-gated skeleton
;; ============================================================================

(defrecord PcaKyuyoApiProvider [opts]
  ;; PCA 給与奉行 cloud API is partner-program-gated. A consumer with
  ;; enrolled partner credentials wires their OAuth client through
  ;; `:credentials` and supplies an HTTP client. This skeleton
  ;; documents the protocol surface; the live wiring lands when a
  ;; partner-program consumer surfaces. See ADR-084 §2.4.
  pp/PayrollComputeProvider
  (provider-id [_] :pca-kyuyo-api)
  (compute-payroll [_ _ctx]
    (throw
     (ex-info
      "PcaKyuyoApiProvider is a skeleton. 給与奉行 cloud API is partner-program-gated; supply OAuth credentials + a live HTTP client implementation. See ADR-084 §2.4."
      {:provider :pca-kyuyo-api
       :status :skeleton-only}))))

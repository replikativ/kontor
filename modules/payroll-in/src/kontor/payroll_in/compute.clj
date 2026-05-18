(ns kontor.payroll-in.compute
  "IN payroll compute providers — file-ingest CSV adapters for the
   three dominant Indian SMB / mid-market engines:

     - **Keka** (`KekaProvider`) — Keka HR / Keka Payroll, dominant
       in mid-market tech.
     - **GreytHR** (`GreytHrProvider`) — Greytip Software's GreytHR;
       very broad SMB footprint.
     - **ZenHR / ZingHR / SumoPayroll / Saral PayPack** — handled
       via the generic `:column-mapping` shape (engine name passed
       in opts for audit only).

   Architecturally identical to `kontor.payroll-ca.compute` (Stage R
   C4) — file-ingest CSV parser, per-customer column-mapping config,
   consumer-supplied `:pay-element-codes` lookup. kontor NEVER
   re-implements IN gross-to-net math (Section 192 slabs, Section 10
   exemptions, Section 89(1) relief, surcharge / cess, Old vs New tax
   regime per Finance Act 2023-2026, PF wage ceiling, ESI threshold,
   per-state PT slabs — each runs to hundreds of pages of regulator
   publications; the engine is authoritative).

   Reference: doc/research/79-hr-payroll-stage-r-plan.md §5.3 +
   note 86 P2-86-2 (canonical key matrix — IN uses `:csv-source`
   per the convention).

   ## License posture (ADR-083 — same as ADR-077 / ADR-078)

   - CSV column schemas are described from public vendor documentation
     (Keka, GreytHR public help-center / API documentation pages); no
     vendor source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No bundled pay-element catalog — consumer supplies the
     engine-component-code → kontor-kind mapping via
     `:pay-element-codes`."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-in.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.io Reader StringReader]
           [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips commas
   and the Rupee symbol (₹). Refuses doubles."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim
                      (str/replace #"[₹₨\$,]" ""))]
      (if (str/blank? cleaned) 0M (BigDecimal. cleaned)))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace; tolerant of underscore-vs-hyphen."
  [s]
  (when s (-> s str/trim str/lower-case
              (str/replace #"[\s_]+" "-"))))

(defn- to-reader
  "Coerce a String / Reader / file / URL / classpath-resource to a
   `java.io.Reader`. A raw CSV string (i.e. one that looks like CSV
   content rather than a path) is wrapped in a StringReader; this
   mirrors the convention `kontor.payroll-us-adp.compute/read-lines`
   uses for the GLI parser."
  ^Reader [source]
  (cond
    (instance? Reader source) source
    (string? source)
    ;; Heuristic: if the string contains a newline OR a comma, it's
    ;; CSV content; otherwise treat as a file/URL path the way
    ;; clojure.java.io/reader does.
    (if (or (str/includes? source "\n")
            (str/includes? source ","))
      (StringReader. ^String source)
      (io/reader source))
    :else (io/reader source)))

(defn- read-csv-rows
  "Read a CSV string / Reader / file into a vector of maps keyed by
   normalized header names. Drops empty rows."
  [source]
  (with-open [r (to-reader source)]
    (let [rows (csv/read-csv r)
          [header & data] rows
          headers (mapv normalize-header header)]
      (->> data
           (remove (fn [row] (every? str/blank? row)))
           (mapv (fn [row] (zipmap headers row)))))))

(defn- sum-bd
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

;; ============================================================================
;; Generic per-employee fact assembly
;; ============================================================================

(defn components->fact
  "Given a vector of {:kind :amount :employer-side?} components for ONE
   employee, derive {:gross :net :components} per the substrate's sum
   invariant. Carry-only kinds flow through `:jurisdiction-specific-codes`."
  [{:keys [employment-eid pay-period-eid commodity-eid components
           extras-map jurisdiction-specific-codes]}]
  (let [posting-comps (filterv #(wt/posts? (:kind %) extras-map) components)
        carry-comps   (remove #(wt/posts? (:kind %) extras-map) components)
        pos-employee
        (->> posting-comps
             (remove :employer-side?)
             (map :amount)
             (filter #(pos? (compare ^BigDecimal % 0M)))
             sum-bd)
        neg-employee
        (->> posting-comps
             (remove :employer-side?)
             (map :amount)
             (filter #(neg? (compare ^BigDecimal % 0M)))
             sum-bd)
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
;; CSV row → component map (generic shape)
;; ============================================================================

(defn- row->component
  "Map ONE CSV row to ONE component map. The CSV row supplies a
   pay-element code which the consumer's `:pay-element-codes` map
   resolves to a kontor `:component-kind` keyword (or `{:kind :foo
   :employer-side? true}` map). Returns nil for header / blank rows.

   Required column-mapping keys:
     :employee-id-col   default 'employee-id'
     :pay-element-col   default 'component'
     :amount-col        default 'amount'
     :sign-col          default 'sign'   (optional — 'D'/'C' or '+'/'-')

   When :sign-col is absent the parser treats the amount sign as
   authoritative (Keka emits signed amounts; GreytHR emits unsigned
   + a sign indicator column).

   Optional column-mapping keys:
     :province-col      e.g. 'state' — drives per-state PT routing
     :department-col    e.g. 'cost-center'"
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "employee-id")
        pe-col  (or (:pay-element-col column-mapping) "component")
        amt-col (or (:amount-col column-mapping) "amount")
        sign-col (:sign-col column-mapping)
        prov-col (:province-col column-mapping)
        dept-col (:department-col column-mapping)
        emp (get row emp-col)
        pe  (get row pe-col)
        amt-raw (get row amt-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe)))
      (let [raw-amount (coerce-bigdec amt-raw)
            sign-ind (some-> (when sign-col (get row sign-col))
                             str/trim str/upper-case)
            signed (cond
                     (= "C" sign-ind) (.negate ^BigDecimal raw-amount)
                     (= "-" sign-ind) (.negate ^BigDecimal raw-amount)
                     (= "D" sign-ind) raw-amount
                     (= "+" sign-ind) raw-amount
                     :else raw-amount)
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown IN pay-element code: " pe)
                          {:pay-element-code pe
                           :employee-external-id emp
                           :available-codes (set (keys pay-element-codes))})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))
              province (when prov-col
                         (let [v (get row prov-col)]
                           (when-not (str/blank? v) (str/trim v))))
              department (when dept-col
                           (let [v (get row dept-col)]
                             (when-not (str/blank? v) (str/trim v))))]
          (cond-> {:employee-external-id emp
                   :kind kind
                   :amount signed
                   :employer-side? (boolean employer?)}
            province   (assoc :province-of-employment province)
            department (assoc :department department)))))))

(defn parse-csv
  "Parse a CSV source (String, Reader, file path, java.net.URL) into
   a vector of `{:employee-external-id :kind :amount :employer-side?
   :province-of-employment? :department?}` maps.

   `opts` keys:
     :column-mapping       — see row->component docstring
     :pay-element-codes    — engine-component-code → kontor-kind lookup

   Rows mapped to `:__skip` are filtered out silently (some engines
   emit pre-computed payable-mirror rows that kontor's posting
   builder derives independently)."
  [source opts]
  (when-not (:pay-element-codes opts)
    (throw (ex-info "parse-csv needs :pay-element-codes" {})))
  (->> (read-csv-rows source)
       (mapv #(row->component % opts))
       (remove nil?)
       (remove #(= :__skip (:kind %)))
       vec))

(defn csv->facts
  "Group a parsed CSV by employee-external-id and assemble PayrollFacts.

   `external-id->eid` is a function (employee-external-id → :employment
   eid) the consumer supplies — keeps kontor agnostic to how the
   engine identifies employees.

   Per note 86 P1-86-3 the per-fact `:province-of-employment` (when
   present on a row) surfaces in `:jurisdiction-specific-codes` so the
   posting-builder + TDS / PF / ESI builders can route per-state."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map engine]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv
        (fn [[ext-id rows]]
          (let [emp-eid (external-id->eid ext-id)
                _ (when (nil? emp-eid)
                    (throw (ex-info "Unknown employee external-id"
                                    {:employee-external-id ext-id})))
                ;; Province lifts from the first row carrying one
                ;; (most engines emit it on every row but we tolerate
                ;; it on the first only).
                province (some :province-of-employment rows)
                department (some :department rows)]
            (components->fact
             {:employment-eid emp-eid
              :pay-period-eid pay-period-eid
              :commodity-eid commodity-eid
              :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                rows)
              :extras-map extras-map
              :jurisdiction-specific-codes
              (cond-> {:engine (or engine :in-csv)
                       :employee-external-id ext-id}
                province (assoc :province-of-employment province)
                department (assoc :department department))}))))))

;; ============================================================================
;; KekaProvider — Keka GL export with default Keka column names
;; ============================================================================

(def keka-default-column-mapping
  "Keka's default GL-export column names (per Keka admin-portal
   documentation). Consumers override individual fields when their
   instance has custom-renamed columns."
  {:employee-id-col   "employee-id"
   :pay-element-col   "component"
   :amount-col        "amount"
   :sign-col          "type"         ; Keka emits 'Earning' / 'Deduction'
   :province-col      "work-state"
   :department-col    "department"})

(defn- keka-row->component
  "Keka emits 'type' as 'Earning' / 'Deduction' rather than D/C; we
   translate before delegating to the generic row->component."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [cm (or column-mapping keka-default-column-mapping)
        sign-col (:sign-col cm)
        type-val (some-> (when sign-col (get row sign-col)) str/trim str/lower-case)
        normalized-row (if type-val
                         (assoc row sign-col
                                (cond
                                  (or (= "earning" type-val)
                                      (= "earnings" type-val)
                                      (= "credit-to-employee" type-val)) "+"
                                  (or (= "deduction" type-val)
                                      (= "deductions" type-val)
                                      (= "debit-to-employee" type-val)) "-"
                                  :else type-val))
                         row)]
    (row->component normalized-row
                    {:column-mapping cm
                     :pay-element-codes pay-element-codes})))

(defn parse-keka-csv
  "Parse a Keka GL CSV. Default columns map Keka's documented field
   names; consumers pass a different `:column-mapping` for custom
   instances. Same `:pay-element-codes` shape as the generic parser."
  [source opts]
  (when-not (:pay-element-codes opts)
    (throw (ex-info "parse-keka-csv needs :pay-element-codes" {})))
  (->> (read-csv-rows source)
       (mapv #(keka-row->component % opts))
       (remove nil?)
       (remove #(= :__skip (:kind %)))
       vec))

(defrecord KekaProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :keka)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts)
                      keka-default-column-mapping)
          extras (or (:extras-map ctx) (:extras-map opts))]
      (when-not source   (throw (ex-info "KekaProvider needs :csv-source" {})))
      (when-not codes    (throw (ex-info "KekaProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "KekaProvider needs :external-id->eid" {})))
      (let [parsed (parse-keka-csv source {:column-mapping mapping
                                           :pay-element-codes codes})]
        (csv->facts parsed
                    {:external-id->eid ext->eid
                     :pay-period-eid (:pay-period-eid ctx)
                     :commodity-eid commodity-eid
                     :extras-map extras
                     :engine :keka})))))

;; ============================================================================
;; GreytHrProvider — GreytHR / Greytip GL export
;; ============================================================================

(def greythr-default-column-mapping
  "GreytHR's default GL-export column names (per Greytip help-center
   documentation)."
  {:employee-id-col   "emp-no"
   :pay-element-col   "head-code"     ; GreytHR calls them 'salary heads'
   :amount-col        "amount"
   :sign-col          "head-type"     ; 'EAR' / 'DED' / 'EMP' (employer)
   :province-col      "work-state"
   :department-col    "department"})

(defn- greythr-row->component
  "GreytHR's head-type vocabulary: EAR = earning (sign +), DED =
   deduction (sign -), EMP = employer-side (handled below)."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [cm (or column-mapping greythr-default-column-mapping)
        head-type-col (:sign-col cm)
        head-type (some-> (when head-type-col (get row head-type-col))
                          str/trim str/upper-case)
        ;; For EMP rows we override the sign to + and let the mapping
        ;; declare :employer-side? true.
        normalized-row (cond
                         (= head-type "EAR") (assoc row head-type-col "+")
                         (= head-type "DED") (assoc row head-type-col "-")
                         (= head-type "EMP") (assoc row head-type-col "+")
                         :else row)]
    (row->component normalized-row
                    {:column-mapping cm
                     :pay-element-codes pay-element-codes})))

(defn parse-greythr-csv
  "Parse a GreytHR GL CSV with the documented head-code / head-type
   structure."
  [source opts]
  (when-not (:pay-element-codes opts)
    (throw (ex-info "parse-greythr-csv needs :pay-element-codes" {})))
  (->> (read-csv-rows source)
       (mapv #(greythr-row->component % opts))
       (remove nil?)
       (remove #(= :__skip (:kind %)))
       vec))

(defrecord GreytHrProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :greythr)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts)
                      greythr-default-column-mapping)
          extras (or (:extras-map ctx) (:extras-map opts))]
      (when-not source   (throw (ex-info "GreytHrProvider needs :csv-source" {})))
      (when-not codes    (throw (ex-info "GreytHrProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "GreytHrProvider needs :external-id->eid" {})))
      (let [parsed (parse-greythr-csv source {:column-mapping mapping
                                              :pay-element-codes codes})]
        (csv->facts parsed
                    {:external-id->eid ext->eid
                     :pay-period-eid (:pay-period-eid ctx)
                     :commodity-eid commodity-eid
                     :extras-map extras
                     :engine :greythr})))))

;; ============================================================================
;; ZenHrProvider — generic CSV (handles ZingHR, SumoPayroll, Saral
;; PayPack, ZenHR — share the same shape)
;; ============================================================================

(defrecord ZenHrProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_]
    ;; Engine name is informational; allow consumer override.
    (or (:engine-id opts) :zenhr))
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          engine-id (or (:engine-id ctx) (:engine-id opts) :zenhr)]
      (when-not source   (throw (ex-info "ZenHrProvider needs :csv-source" {})))
      (when-not codes    (throw (ex-info "ZenHrProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "ZenHrProvider needs :external-id->eid" {})))
      (when-not mapping  (throw (ex-info "ZenHrProvider needs :column-mapping (no engine default)"
                                         {:hint "ZenHR / ZingHR / Saral / SumoPayroll column names vary; supply a :column-mapping map."})))
      (let [parsed (parse-csv source {:column-mapping mapping
                                      :pay-element-codes codes})]
        (csv->facts parsed
                    {:external-id->eid ext->eid
                     :pay-period-eid (:pay-period-eid ctx)
                     :commodity-eid commodity-eid
                     :extras-map extras
                     :engine engine-id})))))

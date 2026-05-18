(ns kontor.payroll-au.compute
  "AU payroll compute providers — file-ingest CSV adapters for the two
   dominant Australian engines (Xero Payroll AU + MYOB AccountRight /
   MYOB Business) plus a Reckon One skeleton.

   Reference: ADR-080 + research note 79 §5.3.

   ## Architectural posture (ADR-075 + ADR-080)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for gross-to-net (PAYG withholding tables, super-
   guarantee calc, salary-sacrifice OTE base, lump-sum tax averaging,
   ETP withholding). This namespace's job is to PARSE the engine's
   GL export and shape it into `PayrollFacts` per
   `kontor.payroll-provider`.

   ## Provider trio

   - `XeroGlComputeProvider` — Xero Payroll AU's GL Journal export
     (dominant SMB engine; CSV). Xero's GL Journal report exposes the
     per-pay-period DR/CR breakdown including the OTE / PAYGW / Super
     splits. Column layout is per-customer-configurable so the
     adapter takes a `:column-mapping`.

   - `MyobGlComputeProvider` — MYOB AccountRight / MYOB Business GL
     export (mid-market; CSV). Similar shape with different default
     headers; same `:column-mapping` parameterization handles both.

   - `ReckonOneComputeProvider` — skeleton + TODO. Reckon One has a
     smaller AU share but ships an STP-Phase-2-ready GL export;
     wiring lands when a Reckon consumer surfaces.

   ## License posture (ADR-080)

   - CSV column schemas are described from the public vendor
     documentation (Xero AU Payroll help-centre + MYOB Help Centre);
     no vendor source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`.

   ## Canonical `:variable-inputs` keys (per note 86 P2-86-4)

   Both providers consume:
     :csv-source           — path / Reader / raw CSV string
     :pay-element-codes    — map vendor-code → kontor kind (or
                             {:kind :employer-side?} map)
     :external-id->eid     — fn (employee-external-id → :employment eid)
     :column-mapping       — optional column-mapping overrides
     :extras-map           — optional wage-type extras"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-au.wage-types :as wt]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty / nil → 0M. Strips
   commas + currency symbols + AUD sign. Refuses doubles."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim (str/replace #"[A-Z$,]" ""))]
      (if (str/blank? cleaned) 0M (BigDecimal. cleaned)))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace to make column-mapping case-insensitive."
  [s]
  (when s (-> s str/trim str/lower-case (str/replace #"[\s_]+" "-"))))

(defn- read-csv-rows
  "Read CSV string/reader into a vector of maps keyed by normalized
   header names. Drops empty rows."
  [source]
  (with-open [r (io/reader source)]
    (let [rows (csv/read-csv r)
          [header & data] rows
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
  "Given a vector of `{:kind :amount :employer-side?}` components for
   ONE employee, derive `{:gross :net :components}` per the substrate's
   sum invariant.

   Carry-only kinds (YTD totals, RFBA) flow through
   `:jurisdiction-specific-codes`."
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
;; Generic CSV row → component
;; ============================================================================

(defn- row->component
  "Generic GL CSV row → one component map. Returns nil if the row is a
   pay-group header / blank row. Column-mapping spec:

     :employee-id-col   — employee external id column header (lower-
                          cased + dash-normalized)
     :pay-element-col   — vendor pay-element code column
     :debit-col         — debit amount column
     :credit-col        — credit amount column
     :state-col         — optional state-of-employment column

   `:pay-element-codes` is the consumer-supplied lookup from the
   engine's wage-element code (e.g. 'OTE' / 'OVT' / 'PAYGW') to a
   kontor `:component-kind` keyword."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "employee-external-id")
        pe-col  (or (:pay-element-col column-mapping) "pay-element-code")
        dr-col  (or (:debit-col column-mapping) "debit")
        cr-col  (or (:credit-col column-mapping) "credit")
        st-col  (or (:state-col column-mapping) "state")
        emp     (get row emp-col)
        pe      (get row pe-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe)))
      (let [debit  (coerce-bigdec (get row dr-col))
            credit (coerce-bigdec (get row cr-col))
            amount (.subtract ^BigDecimal debit ^BigDecimal credit)
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown pay-element code: " pe)
                          {:pay-element-code pe
                           :employee-external-id emp
                           :known (set (keys pay-element-codes))})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))
              state (get row st-col)]
          (cond-> {:employee-external-id emp
                   :kind kind
                   :amount amount
                   :employer-side? (boolean employer?)}
            (and state (not (str/blank? state)))
            (assoc :state state)))))))

(defn parse-au-gl-csv
  "Parse an AU GL CSV (Xero or MYOB or any column-mapping-compatible
   shape) into a vector of `{:employee-external-id :kind :amount
   :employer-side? [:state]}` maps.

   Verifies per-employee net = 0 (the engine produces self-balancing
   employee-block journals); rejects on imbalance to surface a corrupt
   or truncated export.

   Rows mapped to `:__skip-payable` are dropped silently (engine's
   pre-balanced payable-mirror rows the posting builder will derive
   from employer-side components on its own)."
  [source opts]
  (when-not (:pay-element-codes opts)
    (throw (ex-info "parse-au-gl-csv needs :pay-element-codes" {})))
  (let [parsed (->> (read-csv-rows source)
                    (mapv #(row->component % opts))
                    (remove nil?)
                    vec)
        mirror? #(= :__skip-payable (:kind %))
        per-emp (group-by :employee-external-id parsed)]
    ;; Per-employee net-zero invariant (a balanced GL journal nets to
    ;; zero per employee block; ATO STP requires self-balancing for
    ;; the pay-event payload).
    (doseq [[emp rows] per-emp]
      (let [net (sum-amounts (map :amount rows))]
        (when (pos? (compare (.abs ^BigDecimal net) 0.01M))
          (throw (ex-info "AU GL CSV: per-employee sum != 0; engine balancer missing or bad data"
                          {:employee-external-id emp :net net})))))
    (vec (remove mirror? parsed))))

(defn au-gl-facts
  "Group a parsed CSV by employee-external-id and assemble PayrollFacts."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map engine-tag]}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee external-id"
                                   {:employee-external-id ext-id})))
                 (let [first-state (some :state rows)]
                   (components->fact
                    {:employment-eid emp-eid
                     :pay-period-eid pay-period-eid
                     :commodity-eid commodity-eid
                     ;; Carry :state per-component so the posting builder
                     ;; can attach per-state analytic-distributions to
                     ;; every wage-side leg (mirror US ADP C3 pattern;
                     ;; the per-state value is also surfaced on
                     ;; :jurisdiction-specific-codes for at-a-glance
                     ;; downstream consumers).
                     :components (mapv #(select-keys %
                                                     [:kind :amount
                                                      :employer-side? :state])
                                       rows)
                     :extras-map extras-map
                     :jurisdiction-specific-codes
                     (cond-> {:engine (or engine-tag :au-gl)
                              :employee-external-id ext-id}
                       first-state (assoc :state first-state))})))))))

;; ============================================================================
;; XeroGlComputeProvider — dominant SMB AU engine
;; ============================================================================

(defrecord XeroGlComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :xero-gl)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts))]
      (when-not source (throw (ex-info "XeroGlComputeProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "XeroGlComputeProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "XeroGlComputeProvider needs :external-id->eid" {})))
      (let [parsed (parse-au-gl-csv source {:pay-element-codes codes
                                            :column-mapping mapping})]
        (au-gl-facts parsed {:external-id->eid ext->eid
                             :pay-period-eid (:pay-period-eid ctx)
                             :commodity-eid commodity-eid
                             :extras-map extras
                             :engine-tag :xero})))))

;; ============================================================================
;; MyobGlComputeProvider — mid-market AU engine
;; ============================================================================

(defrecord MyobGlComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :myob-gl)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          mapping (or (:column-mapping ctx) (:column-mapping opts))]
      (when-not source (throw (ex-info "MyobGlComputeProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "MyobGlComputeProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "MyobGlComputeProvider needs :external-id->eid" {})))
      (let [parsed (parse-au-gl-csv source {:pay-element-codes codes
                                            :column-mapping mapping})]
        (au-gl-facts parsed {:external-id->eid ext->eid
                             :pay-period-eid (:pay-period-eid ctx)
                             :commodity-eid commodity-eid
                             :extras-map extras
                             :engine-tag :myob})))))

;; ============================================================================
;; ReckonOneComputeProvider — skeleton; full wiring on consumer demand
;; ============================================================================

(defrecord ReckonOneComputeProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :reckon-one)
  (compute-payroll [_ _ctx]
    (throw
     (ex-info
      "ReckonOneComputeProvider is a skeleton. Reckon One's STP-Phase-2 GL export shape is documented at developer.reckon.com; wiring lands when a Reckon-using consumer surfaces. Use XeroGlComputeProvider with a custom :column-mapping for the same shape in the interim."
      {:partner-program-url "https://developer.reckon.com"
       :provider :reckon-one
       :status :skeleton-only}))))

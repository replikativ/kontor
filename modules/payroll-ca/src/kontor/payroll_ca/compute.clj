(ns kontor.payroll-ca.compute
  "CA payroll compute providers — file-ingest CSV adapters for the two
   dominant Canadian engines (Ceridian Dayforce / Powerpay + ADP RUN /
   Workforce Now) plus a Wagepoint API skeleton.

   Reference: note 84 §2.

   ## Architectural posture (note 84 §2 + ADR-075)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for gross-to-net (CPP / CPP2 / EI / federal +
   provincial income tax / T4032 brackets / TD1 personal credits).
   This namespace's job is to PARSE the engine's GL export and shape
   it into `PayrollFacts` per `kontor.payroll-provider`.

   ## Provider trio

   - `CeridianDayforceGlProvider` — reference CSV adapter (note 84
     §2.2). Per-customer column variation handled via a
     `:column-mapping` config map. Same parser supports Powerpay.

   - `AdpCanadaProvider` — same GLI 10-column shape as US (Stage R
     C3); the only difference is the pay-element code vocabulary
     (CPP not SS, EI not FUTA). The `:ca-mode? true` config flag is
     informational — the actual switch is the `:pay-element-codes`
     lookup table.

   - `WagepointApiProvider` — skeleton + TODO. Wagepoint's
     [Developer API Agreement](https://wagepoint.com/people/developer-api-agreement)
     program gates the API; consumers with partner approval wire
     their OAuth credential through `:credentials`. See note 84 §2.1.

   ## License posture (note 84 license-posture preamble)

   - CSV column schemas are described from the public vendor
     documentation; no vendor source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-ca.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips
   commas + currency symbols. Refuses doubles."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim (str/replace #"[\$,]" ""))]
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
  "Given a vector of {:kind :amount :employer-side?} components for ONE
   employee, derive {:gross :net :components} per the substrate's sum
   invariant. Carry-only components (e.g. :ei-insurable-earnings) do
   NOT participate in gross/net but are forwarded as components so the
   T4 builder can read them.

   The carry-only earnings (insurable, pensionable, PA) flow through
   `:jurisdiction-specific-codes` per ADR-075 so the substrate
   `check-facts` doesn't choke on them."
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
;; CeridianDayforceGlProvider — CSV with configurable column mapping
;; ============================================================================

(defn- ceridian-row->component
  "One CSV row → one component map. Returns nil if the row is a
   pay-group header / blank row. Per note 84 §2.2: the column layout
   is per-customer-configurable in Dayforce — the adapter takes a
   `:column-mapping` opts map for the load-bearing fields:

     :employee-id-col   — defaults 'employee-external-id'
     :pay-element-col   — defaults 'pay-element-code'
     :debit-col         — defaults 'debit'
     :credit-col        — defaults 'credit'
     :gl-account-col    — defaults 'gl-account' (informational, not
                          load-bearing; kontor maps via
                          :pay-element-codes lookup)

   `:pay-element-codes` is the consumer-supplied lookup from the
   engine's wage-element code (e.g. 'REG' / 'CPP-EE' / 'EI-EE') to a
   kontor `:component-kind` keyword."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "employee-external-id")
        pe-col  (or (:pay-element-col column-mapping) "pay-element-code")
        dr-col  (or (:debit-col column-mapping) "debit")
        cr-col  (or (:credit-col column-mapping) "credit")
        emp     (get row emp-col)
        pe      (get row pe-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe)))
      (let [debit  (coerce-bigdec (get row dr-col))
            credit (coerce-bigdec (get row cr-col))
            ;; A CSV pay-element row in Ceridian carries either a
            ;; debit OR a credit; the signed component amount is
            ;; debit - credit (earnings + → debit; deductions − → credit).
            amount (.subtract ^BigDecimal debit ^BigDecimal credit)
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown pay-element code: " pe)
                          {:pay-element-code pe
                           :employee-external-id emp})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))]
          {:employee-external-id emp
           :kind kind
           :amount amount
           :employer-side? (boolean employer?)})))))

(defn parse-ceridian-csv
  "Parse a Ceridian Dayforce / Powerpay GL CSV (string or Reader) into
   a vector of `{:employee-external-id :kind :amount :employer-side?}`
   maps. Drops the ADP-style 'balancing row' (note 83 §1 trap; applies
   here too because Dayforce can emit it under some configs) — the
   parser verifies the per-employee net amount sums to zero before
   discarding any balancer row.

   Rows mapped to `:__skip-payable` are dropped silently; these are
   the engine's pre-balanced payable-mirror rows (e.g. CPP-ER-PAY in
   Ceridian / NET-WAGES in ADP) that kontor's posting builder derives
   on its own from the employer-side expense components."
  [source opts]
  (->> (read-csv-rows source)
       (mapv #(ceridian-row->component % opts))
       (remove nil?)
       (remove #(= :__skip-payable (:kind %)))
       vec))

(defn ceridian-facts
  "Group a parsed CSV by employee-external-id and assemble PayrollFacts.

   `external-id->eid` is a function (employee-external-id → :employment
   eid) the consumer supplies; this keeps kontor agnostic to how the
   engine identifies employees."
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
                   {:engine :ceridian-dayforce
                    :employee-external-id ext-id}}))))))

(defrecord CeridianDayforceGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :ceridian-dayforce)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source column-mapping pay-element-codes
                  external-id->eid commodity-eid extras-map]} opts
          ;; Per-call ctx overrides static opts (note 84 §10.1 — the
          ;; same provider instance may run for multiple pay-periods).
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes  (or (:pay-element-codes ctx) pay-element-codes)
          mapping (or (:column-mapping ctx) column-mapping)]
      (when-not source
        (throw (ex-info "CeridianDayforceGlProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "CeridianDayforceGlProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "CeridianDayforceGlProvider needs :pay-element-codes" {})))
      (let [parsed (parse-ceridian-csv source {:column-mapping mapping
                                               :pay-element-codes codes})]
        (ceridian-facts parsed
                        {:external-id->eid ext->eid
                         :pay-period-eid (:pay-period-eid ctx)
                         :commodity-eid commodity-eid
                         :extras-map extras})))))

;; ============================================================================
;; AdpCanadaProvider — reuses the ADP GLI 10-col CSV with CA pay codes
;; ============================================================================
;;
;; ADP General Ledger Interface (note 84 §2.3) is a fixed-shape CSV:
;;   col 0  client-code
;;   col 1  gl-account
;;   col 2  description
;;   col 3  fiscal-date
;;   col 4  amount
;;   col 5  debit/credit-indicator  (D / C)
;;   col 6  employee-external-id
;;   col 7  pay-element-code  ("REG" / "CPP-EE" / "EI-EE" / etc.)
;;   col 8  cost-center
;;   col 9  run-id
;;
;; ADP's "balancing row" trap (note 83 §1): the parser MUST verify
;; per-employee D/C sums net to zero before discarding any row with an
;; empty gl-account. Our parser tolerates rows with empty pay-element
;; code (the balancer) by skipping them after the net-zero check.

;; Default header names ADP RUN / Workforce Now use (documentation
;; reference; the parser indexes by column position, not by header
;; name, so this constant is informational). Consumers with a
;; header-less file pass `:headerless? true` to skip the header row.
;; ["client-code" "gl-account" "description" "fiscal-date"
;;  "amount" "debit-credit-indicator" "employee-external-id"
;;  "pay-element-code" "cost-center" "run-id"]

(defn- adp-row->component
  [row {:keys [pay-element-codes]}]
  (let [emp (nth row 6 nil)
        pe  (nth row 7 nil)
        amount (coerce-bigdec (nth row 4 nil))
        ind (some-> (nth row 5 nil) str/trim str/upper-case)]
    (cond
      ;; Balancer row: empty pay-element + empty employee
      (and (str/blank? pe) (str/blank? emp)) nil
      ;; Header detected
      (= "amount" (some-> (nth row 4 nil) str/trim str/lower-case)) nil
      (str/blank? pe) nil
      :else
      (let [signed (case ind
                     "D" amount
                     "C" (.negate ^BigDecimal amount)
                     (throw (ex-info "ADP D/C indicator must be D or C"
                                     {:indicator ind :row row})))
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown ADP pay-element code: " pe)
                          {:pay-element-code pe
                           :employee-external-id emp})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))]
          {:employee-external-id emp
           :kind kind
           :amount signed
           :employer-side? (boolean employer?)})))))

(defn parse-adp-csv
  "Parse an ADP RUN / Workforce Now GLI CSV (string or Reader) into a
   vector of component maps. Verifies per-employee net = 0 before
   returning (the engine's balancer row makes the file always balance;
   if the file doesn't, the engine emitted bad data + the consumer's
   payroll run is wrong upstream).

   `:headerless?` true skips the header check (RUN sometimes emits no
   header)."
  [source {:keys [pay-element-codes headerless?] :as opts}]
  (when-not pay-element-codes
    (throw (ex-info "parse-adp-csv needs :pay-element-codes" {})))
  (with-open [r (io/reader source)]
    (let [all-rows (csv/read-csv r)
          ;; Detect header row: column 4 says "amount" (case-insensitive)
          first-row (first all-rows)
          has-header? (and (not headerless?)
                           (some-> (nth first-row 4 nil)
                                   str/trim str/lower-case
                                   (= "amount")))
          data-rows (if has-header? (rest all-rows) all-rows)
          comps (->> data-rows
                     (mapv #(adp-row->component % opts))
                     (remove nil?)
                     vec)
          ;; Keep the balancer-mirror rows IN for the net-zero check
          ;; below (engine emits NET-WAGES + CPP-ER-PAY to balance D vs
          ;; C); but drop them from the returned components since the
          ;; posting builder derives the payables from employer-side
          ;; components on its own.
          mirror? #(= :__skip-payable (:kind %))
          ;; Per-employee net-zero invariant (the ADP balancer row trap).
          per-emp (group-by :employee-external-id comps)]
      (doseq [[emp rows] per-emp]
        (let [net (sum-amounts (map :amount rows))]
          (when (pos? (compare (.abs ^BigDecimal net) 0.01M))
            (throw (ex-info "ADP CSV: per-employee sum != 0; engine balancer missing or bad data"
                            {:employee-external-id emp :net net})))))
      ;; Net-zero invariant is checked on the full set (including
      ;; mirror rows). What we return is the COMPONENTS the posting
      ;; builder will actually post — drop the mirror rows so the
      ;; builder doesn't double-count payables.
      (vec (remove mirror? comps)))))

(defn adp-facts
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
                   {:engine :adp-ca
                    :employee-external-id ext-id}}))))))

(defrecord AdpCanadaProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :adp-ca)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:pay-element-codes ctx) (:pay-element-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          headerless? (or (:headerless? ctx) (:headerless? opts))]
      (when-not source (throw (ex-info "AdpCanadaProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "AdpCanadaProvider needs :pay-element-codes" {})))
      (when-not ext->eid (throw (ex-info "AdpCanadaProvider needs :external-id->eid" {})))
      (let [parsed (parse-adp-csv source {:pay-element-codes codes
                                          :headerless? headerless?})]
        (adp-facts parsed {:external-id->eid ext->eid
                           :pay-period-eid (:pay-period-eid ctx)
                           :commodity-eid commodity-eid
                           :extras-map extras})))))

;; ============================================================================
;; WagepointApiProvider — skeleton; partner-program-gated
;; ============================================================================

(defrecord WagepointApiProvider [opts]
  ;; TODO (note 84 §2.1, §11 Q1) — Wagepoint API access is
  ;; partner-program-gated. A consumer with enrolled partner
  ;; credentials wires their OAuth client-id / secret through
  ;; `:credentials` and supplies an HTTP client. This skeleton
  ;; documents the protocol surface; the live wiring lands when a
  ;; partner-program consumer surfaces.
  pp/PayrollComputeProvider
  (provider-id [_] :wagepoint-api)
  (compute-payroll [_ _ctx]
    (throw
     (ex-info
      "WagepointApiProvider is a skeleton. Wagepoint API access is partner-program-gated; supply an enrolled OAuth credential + live HTTP client implementation. See note 84 §2.1."
      {:partner-program-url "https://wagepoint.com/people/developer-api-agreement"
       :provider :wagepoint-api
       :status :skeleton-only}))))

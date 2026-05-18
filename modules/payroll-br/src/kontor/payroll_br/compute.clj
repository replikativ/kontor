(ns kontor.payroll-br.compute
  "BR payroll compute providers — file-ingest CSV adapters for the
   dominant Brazilian engines per ADR-081 §2: RH Sistemas, Senior,
   Pluxee (formerly Sodexo), TOTVS Datasul, ContaAzul Folha. The BR
   market is fragmented across SMB (ContaAzul, Pluxee) and mid-market
   (RH Sistemas, Senior, Datasul) tiers; all converge on a wage-type-
   per-row CSV / GL-export shape similar to the US ADP GLI + CA
   Ceridian Dayforce patterns.

   ## Architectural posture (ADR-075 + ADR-081)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for INSS / IRRF / FGTS / Salário-Família / SAT-RAT
   / outras-entidades. This namespace's job is to PARSE the engine's
   GL export and shape it into `PayrollFacts` per
   `kontor.payroll-provider`.

   ## Provider trio

   - `RhSistemasGlProvider` — reference CSV adapter with configurable
     column mapping. RH Sistemas / Senior / Datasul converge on the
     same column shape (Brazilian payroll engines tend to follow the
     SEFIP / GFIP layout norms).

   - `SeniorHcmGlProvider` — same parser shape as RH Sistemas; both
     accept the GFIP-style monthly summary CSV. The provider records
     the engine provenance in :jurisdiction-specific-codes for the
     eSocial event builders to consume.

   - `PluxeeCsvGlProvider` — Pluxee (formerly Sodexo Folha + Pluxee
     People Cloud) ships a slightly different layout — column-position
     based rather than header-based. Same parser core with a position-
     based row→component mapping.

   ## License posture (ADR-081 license-posture preamble)

   - CSV column schemas are described from public vendor documentation
     + the Receita Federal eSocial S-1200/1210 layouts; no vendor
     source has been lifted.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary rubrica catalog bundled — consumer supplies the
     engine→kontor kind mapping via `:rubrica-codes`."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-provider :as pp]
            [kontor.payroll-br.wage-types :as wt])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips
   currency symbols + thousand separators. BR uses comma as the
   decimal separator + dot as the thousand separator (e.g.
   '1.234,56') — this fn handles both BR-localized and US-localized
   numeric strings. Refuses doubles."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [trimmed (str/trim s)]
      (if (str/blank? trimmed)
        0M
        (let [;; Detect BR locale: if both comma and dot appear AND the
              ;; comma is to the right of the last dot, BR-locale.
              ;; If only comma, assume BR decimal separator.
              has-comma? (str/includes? trimmed ",")
              has-dot? (str/includes? trimmed ".")
              last-comma (str/last-index-of trimmed ",")
              last-dot (str/last-index-of trimmed ".")
              br-locale? (or (and has-comma? has-dot? (> last-comma last-dot))
                             (and has-comma? (not has-dot?)))
              cleaned (cond-> trimmed
                        true (str/replace #"[R\$\s]" "")
                        br-locale? (str/replace "." "")
                        br-locale? (str/replace "," "."))]
          (if (str/blank? cleaned) 0M (BigDecimal. ^String cleaned)))))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace + strip BR-locale accents to make
   column-mapping case-insensitive. We strip accents because BR engine
   exports often vary on whether 'Crédito' is written 'credito' or
   'crédito'."
  [s]
  (when s
    (-> s
        str/trim
        str/lower-case
        (str/replace #"[áàâã]" "a")
        (str/replace #"[éê]" "e")
        (str/replace #"[íî]" "i")
        (str/replace #"[óôõ]" "o")
        (str/replace #"[úû]" "u")
        (str/replace "ç" "c")
        (str/replace #"[\s_]+" "-"))))

(defn- read-csv-rows
  "Read CSV string/reader into a vector of maps keyed by normalized
   header names. Drops empty rows. BR engines sometimes use ';' as
   the field separator (Microsoft Excel default for pt-BR locale);
   the `:separator` opt overrides the default ','."
  [source {:keys [separator]
           :or {separator \,}}]
  (with-open [r (io/reader source)]
    (let [rows (csv/read-csv r :separator separator)
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
   invariant. Carry-only components (e.g. :inss-base, :irrf-base) do
   NOT participate in gross/net but flow through
   `:jurisdiction-specific-codes` so the eSocial event builders can
   read them."
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
;; RhSistemasGlProvider — CSV with configurable column mapping
;; ============================================================================

(defn- rh-row->component
  "One CSV row → one component map. Returns nil if the row is a header
   / pay-group / blank row. Per ADR-081 §2: RH Sistemas / Senior /
   Datasul converge on a similar column layout — the adapter takes a
   `:column-mapping` opts map for the load-bearing fields:

     :employee-id-col   — defaults 'cpf' (or 'matricula' / 'cracha')
     :rubrica-col       — defaults 'rubrica' (or 'codigo-rubrica')
     :debit-col         — defaults 'provento' (earnings)
     :credit-col        — defaults 'desconto' (deduction)

   `:rubrica-codes` is the consumer-supplied lookup from the engine's
   rubrica code (a per-company string declared in eSocial S-1010) to
   a kontor `:component-kind` keyword OR a {:kind … :employer-side?}
   map."
  [row {:keys [column-mapping rubrica-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "cpf")
        rb-col  (or (:rubrica-col column-mapping) "rubrica")
        pr-col  (or (:debit-col column-mapping) "provento")
        ds-col  (or (:credit-col column-mapping) "desconto")
        emp     (get row emp-col)
        rb      (get row rb-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? rb)))
      (let [provento (coerce-bigdec (get row pr-col))
            desconto (coerce-bigdec (get row ds-col))
            ;; Provento (earning) is positive; desconto (deduction)
            ;; is negative in kontor's sign convention.
            amount (.subtract ^BigDecimal provento ^BigDecimal desconto)
            mapping (get rubrica-codes rb)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown BR rubrica code: " rb)
                          {:rubrica-code rb
                           :employee-external-id emp
                           :known-codes (set (keys rubrica-codes))})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))]
          {:employee-external-id emp
           :kind kind
           :amount amount
           :employer-side? (boolean employer?)
           :rubrica rb})))))

(defn parse-rh-sistemas-csv
  "Parse an RH Sistemas / Senior / Datasul payroll GL CSV (string or
   Reader) into a vector of `{:employee-external-id :kind :amount
   :employer-side? :rubrica}` maps.

   Rows mapped to `:__skip-payable` are dropped silently; these are
   the engine's pre-balanced payable-mirror rows that kontor's posting
   builder derives on its own from the employer-side expense
   components.

   Opts:
     :separator         — CSV field separator (default \\,; many BR
                          engines use \\;)
     :column-mapping    — per-customer column-mapping override
     :rubrica-codes     — string rubrica → kontor :kind (required)"
  [source opts]
  (->> (read-csv-rows source (select-keys opts [:separator]))
       (mapv #(rh-row->component % opts))
       (remove nil?)
       (remove #(= :__skip-payable (:kind %)))
       vec))

(defn rh-sistemas-facts
  "Group a parsed CSV by employee-external-id and assemble
   PayrollFacts.

   `external-id->eid` is a function (employee-external-id → :employment
   eid) the consumer supplies; this keeps kontor agnostic to how the
   engine identifies employees (CPF / matrícula / crachá / etc.)."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map engine-id]
                :or {engine-id :rh-sistemas}}]
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
                   :components (mapv #(select-keys % [:kind :amount
                                                      :employer-side?
                                                      :rubrica])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine engine-id
                    :employee-external-id ext-id}}))))))

(defrecord RhSistemasGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :rh-sistemas)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source column-mapping rubrica-codes
                  external-id->eid commodity-eid extras-map
                  separator]} opts
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes  (or (:rubrica-codes ctx) rubrica-codes)
          mapping (or (:column-mapping ctx) column-mapping)
          sep (or (:separator ctx) separator \,)]
      (when-not source
        (throw (ex-info "RhSistemasGlProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "RhSistemasGlProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "RhSistemasGlProvider needs :rubrica-codes" {})))
      (let [parsed (parse-rh-sistemas-csv source
                                          {:column-mapping mapping
                                           :rubrica-codes codes
                                           :separator sep})]
        (rh-sistemas-facts parsed
                           {:external-id->eid ext->eid
                            :pay-period-eid (:pay-period-eid ctx)
                            :commodity-eid commodity-eid
                            :extras-map extras
                            :engine-id :rh-sistemas})))))

;; ============================================================================
;; SeniorHcmGlProvider — same parser shape, distinct provider id
;; ============================================================================
;;
;; Senior HCM ships a similar payroll-GL export (the BR mid-market
;; convention follows the GFIP layout norms set by Caixa/RFB). We
;; reuse the RH parser; the only difference is the provider-id
;; recorded in :jurisdiction-specific-codes for downstream
;; provenance tracking.

(defrecord SeniorHcmGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :senior-hcm)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source column-mapping rubrica-codes
                  external-id->eid commodity-eid extras-map
                  separator]} opts
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes  (or (:rubrica-codes ctx) rubrica-codes)
          mapping (or (:column-mapping ctx) column-mapping)
          sep (or (:separator ctx) separator \,)]
      (when-not source
        (throw (ex-info "SeniorHcmGlProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "SeniorHcmGlProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "SeniorHcmGlProvider needs :rubrica-codes" {})))
      (let [parsed (parse-rh-sistemas-csv source
                                          {:column-mapping mapping
                                           :rubrica-codes codes
                                           :separator sep})]
        (rh-sistemas-facts parsed
                           {:external-id->eid ext->eid
                            :pay-period-eid (:pay-period-eid ctx)
                            :commodity-eid commodity-eid
                            :extras-map extras
                            :engine-id :senior-hcm})))))

;; ============================================================================
;; PluxeeCsvGlProvider — position-based CSV layout
;; ============================================================================
;;
;; Pluxee (formerly Sodexo Folha + Pluxee People Cloud) exports a
;; position-based CSV (no headers; column position dictates meaning):
;;   col 0  CNPJ
;;   col 1  CPF (employee)
;;   col 2  matricula
;;   col 3  rubrica code
;;   col 4  rubrica descricao
;;   col 5  provento (debit / earning amount)
;;   col 6  desconto (credit / deduction amount)
;;   col 7  competencia (yyyy-mm)
;;
;; The substrate handles both with the same component-assembly logic.

(defn- pluxee-row->component
  [row {:keys [rubrica-codes]}]
  (let [emp (nth row 1 nil)         ; CPF
        rb  (nth row 3 nil)         ; rubrica code
        provento (coerce-bigdec (nth row 5 nil))
        desconto (coerce-bigdec (nth row 6 nil))]
    (cond
      (str/blank? rb) nil
      (str/blank? emp) nil
      :else
      (let [amount (.subtract ^BigDecimal provento ^BigDecimal desconto)
            mapping (get rubrica-codes rb)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown Pluxee rubrica code: " rb)
                          {:rubrica-code rb
                           :employee-external-id emp})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))]
          {:employee-external-id emp
           :kind kind
           :amount amount
           :employer-side? (boolean employer?)
           :rubrica rb})))))

(defn parse-pluxee-csv
  "Parse a Pluxee People Cloud / Sodexo Folha position-based CSV
   (string or Reader). The default field-separator is ';' (Pluxee's
   convention); pass `:separator \\,` to override.

   Pluxee CSVs may include a header row (the column-position convention
   makes it informational; we tolerate an optional header by skipping
   any row whose column 5 ('provento') is not numerically parseable)."
  [source {:keys [rubrica-codes separator]
           :or {separator \;}
           :as opts}]
  (when-not rubrica-codes
    (throw (ex-info "parse-pluxee-csv needs :rubrica-codes" {})))
  (with-open [r (io/reader source)]
    (let [rows (csv/read-csv r :separator separator)
          ;; Skip an optional header row (col 5 not numeric).
          first-row (first rows)
          has-header? (and first-row
                           (let [c5 (some-> (nth first-row 5 nil) str/trim)]
                             (or (str/blank? c5)
                                 (re-find #"[a-zA-Z]" c5))))
          data-rows (if has-header? (rest rows) rows)]
      (->> data-rows
           (remove (fn [row] (every? str/blank? row)))
           (mapv #(pluxee-row->component % opts))
           (remove nil?)
           (remove #(= :__skip-payable (:kind %)))
           vec))))

(defn pluxee-facts
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
                   :components (mapv #(select-keys % [:kind :amount
                                                      :employer-side?
                                                      :rubrica])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine :pluxee
                    :employee-external-id ext-id}}))))))

(defrecord PluxeeCsvGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :pluxee)
  (compute-payroll [_ ctx]
    (let [source (or (:csv-source ctx) (:csv-source opts))
          codes  (or (:rubrica-codes ctx) (:rubrica-codes opts))
          ext->eid (or (:external-id->eid ctx) (:external-id->eid opts))
          commodity-eid (or (:commodity-eid ctx) (:commodity-eid opts))
          extras (or (:extras-map ctx) (:extras-map opts))
          separator (or (:separator ctx) (:separator opts) \;)]
      (when-not source (throw (ex-info "PluxeeCsvGlProvider needs :csv-source" {})))
      (when-not codes  (throw (ex-info "PluxeeCsvGlProvider needs :rubrica-codes" {})))
      (when-not ext->eid (throw (ex-info "PluxeeCsvGlProvider needs :external-id->eid" {})))
      (let [parsed (parse-pluxee-csv source {:rubrica-codes codes
                                             :separator separator})]
        (pluxee-facts parsed {:external-id->eid ext->eid
                              :pay-period-eid (:pay-period-eid ctx)
                              :commodity-eid commodity-eid
                              :extras-map extras})))))

;; ============================================================================
;; Constructors for clarity (mirrors payroll-de-datev/payroll-ca shape)
;; ============================================================================

(defn make-rh-sistemas-provider [opts] (->RhSistemasGlProvider opts))
(defn make-senior-hcm-provider [opts] (->SeniorHcmGlProvider opts))
(defn make-pluxee-provider [opts] (->PluxeeCsvGlProvider opts))

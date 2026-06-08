(ns kontor.payroll-fr.compute
  "FR payroll compute providers — file-ingest CSV adapters for FR
   payroll engines (Silae / Sage / Cegid) plus a partner-program-gated
   API skeleton.

   Reference: ADR-079.

   ## Architectural posture (ADR-075 + ADR-079)

   kontor NEVER re-implements jurisdictional payroll math. The engine
   is authoritative for gross-to-net: cotisations URSSAF, CSG/CRDS,
   ARRCO/AGIRC, plafond annuel de la sécurité sociale (PASS), PAS
   withholding rate. This namespace's job is to PARSE the engine's
   GL export and shape it into `PayrollFacts` per
   `kontor.provider.payroll-provider`.

   ## Provider trio

   - `SilaeGlProvider` — reference CSV adapter. Per-customer column
     variation is handled via a `:column-mapping` config map.

   - `SageGlProvider` — same shape (CSV with per-customer columns).
     Same parser, different `:column-mapping`.

   - `CegidApiProvider` — partner-program-gated skeleton. Consumers
     with enrolled API credentials wire their OAuth client through
     `:credentials` and supply an HTTP client. This skeleton documents
     the protocol surface only.

   ## License posture (CLAUDE.md + ADR-005 + ADR-071 + ADR-075 + ADR-079)

   - CSV column schemas are described from the public vendor
     documentation; no vendor source has been lifted.
   - DSN format spec (NEODES / Cahier Technique de la Norme) is
     published by net-entreprises.fr as a public interop standard.
   - No vendor API keys / OAuth secrets bundled — consumer holds.
   - No proprietary pay-element catalog bundled — consumer supplies
     the engine→kontor kind mapping via `:pay-element-codes`."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-fr.wage-types :as wt]
            [kontor.provider.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- coerce-bigdec
  "Coerce a string CSV cell to a BigDecimal. Empty → 0M. Strips
   thousands separators (space + non-breaking space + comma) +
   currency symbols (€). French decimal separator is comma; we
   normalize to dot before parsing.

   Refuses doubles."
  ^BigDecimal [s]
  (cond
    (nil? s) 0M
    (instance? BigDecimal s) s
    (string? s)
    (let [cleaned (-> s str/trim
                      (str/replace #"[€\s ]" "")
                      ;; If there's a comma AND a dot, assume European
                      ;; format ('1.234,56' → '1234.56'). If only a
                      ;; comma, treat it as the decimal separator.
                      (as-> s'
                            (cond
                              (and (str/includes? s' ",")
                                   (str/includes? s' "."))
                              (-> s' (str/replace "." "") (str/replace "," "."))
                              (str/includes? s' ",")
                              (str/replace s' "," ".")
                              :else s')))]
      (if (str/blank? cleaned) 0M (BigDecimal. cleaned)))
    (integer? s) (BigDecimal/valueOf (long s))
    :else (throw (ex-info "Cannot coerce to BigDecimal"
                          {:value s :type (class s)}))))

(defn- normalize-header
  "Lowercase + collapse whitespace + accents to make column-mapping
   case-insensitive. FR CSV headers often carry accented chars (libellé,
   compte, débit, crédit); we strip them to ASCII for matching."
  [s]
  (when s
    (-> s str/trim str/lower-case
        (str/replace #"[éèê]" "e")
        (str/replace #"[àâ]" "a")
        (str/replace #"[ô]" "o")
        (str/replace #"[ùû]" "u")
        (str/replace #"[ç]" "c")
        (str/replace #"[\s_]+" "-"))))

(defn- read-csv-rows
  "Read CSV string/reader into a vector of maps keyed by normalized
   header names. Drops empty rows. Per the Silae default, the CSV is
   semicolon-delimited; consumers with comma-delimited files pass
   `:separator \\,`."
  ([source] (read-csv-rows source {}))
  ([source {:keys [separator]
            :or {separator \;}}]
   (with-open [r (io/reader source)]
     (let [rows (csv/read-csv r :separator separator)
           [header & data] rows
           headers (mapv normalize-header header)]
       (->> data
            (remove (fn [row] (every? str/blank? row)))
            (mapv (fn [row] (zipmap headers row))))))))

(defn- sum-amounts
  ^BigDecimal [bds]
  (reduce (fn [a v] (.add ^BigDecimal a ^BigDecimal v)) 0M bds))

;; ============================================================================
;; Per-employee fact assembly
;; ============================================================================

(defn- components->fact
  "Given a vector of {:kind :amount :employer-side?} components for ONE
   employee, derive {:gross :net :components} per the substrate's sum
   invariant. Carry-only components (e.g. :base-soumise-urssaf,
   :plafond-secu) do NOT participate in gross/net but flow through
   `:jurisdiction-specific-codes` so the substrate `check-facts`
   doesn't choke on them."
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
;; Generic pay-element CSV parser (Silae + Sage share this shape)
;; ============================================================================

(defn- gl-row->component
  "One CSV row → one component map. Returns nil if the row is a blank
   row / group header. Per ADR-079: the column layout varies per
   customer; the adapter takes a `:column-mapping` opts map for the
   load-bearing fields:

     :employee-id-col   defaults 'matricule'
     :pay-element-col   defaults 'rubrique'
     :debit-col         defaults 'debit'
     :credit-col        defaults 'credit'
     :compte-col        defaults 'compte' (informational; kontor maps
                        via :pay-element-codes lookup, NOT by compte)

   `:pay-element-codes` is the consumer-supplied lookup from the
   engine's rubrique code (e.g. 'SAL_BASE' / 'CSG_DED' / 'COT_URSS_AS')
   to a kontor `:component-kind` keyword."
  [row {:keys [column-mapping pay-element-codes]}]
  (let [emp-col (or (:employee-id-col column-mapping) "matricule")
        pe-col  (or (:pay-element-col column-mapping) "rubrique")
        dr-col  (or (:debit-col column-mapping) "debit")
        cr-col  (or (:credit-col column-mapping) "credit")
        emp     (get row emp-col)
        pe      (get row pe-col)]
    (when (and (not (str/blank? emp))
               (not (str/blank? pe)))
      (let [debit  (coerce-bigdec (get row dr-col))
            credit (coerce-bigdec (get row cr-col))
            ;; A CSV pay-element row carries either debit OR credit;
            ;; the signed component amount is debit - credit
            ;; (earnings + → debit; deductions − → credit).
            amount (.subtract ^BigDecimal debit ^BigDecimal credit)
            mapping (get pay-element-codes pe)]
        (when (nil? mapping)
          (throw (ex-info (str "Unknown FR pay-element code (rubrique): " pe)
                          {:rubrique pe
                           :matricule emp
                           :known-rubriques (keys pay-element-codes)})))
        (let [kind (if (map? mapping) (:kind mapping) mapping)
              employer? (or (and (map? mapping) (:employer-side? mapping))
                            (wt/employer-side? kind))]
          {:employee-external-id emp
           :kind kind
           :amount amount
           :employer-side? (boolean employer?)})))))

(defn parse-gl-csv
  "Parse a Silae / Sage GL CSV (string or Reader) into a vector of
   `{:employee-external-id :kind :amount :employer-side?}` maps. The
   CSV is typically semicolon-delimited; pass `:separator \\,` for
   comma-delimited variants.

   Rows mapped to `:__skip-balancer` are dropped silently; these are
   the engine's pre-balanced row mirrors (some Sage variants emit a
   payable mirror row that kontor's posting builder derives on its
   own from the employer-side components)."
  [source opts]
  (->> (read-csv-rows source opts)
       (mapv #(gl-row->component % opts))
       (remove nil?)
       (remove #(= :__skip-balancer (:kind %)))
       vec))

(defn assemble-facts
  "Group parsed rows by employee-external-id and assemble PayrollFacts.

   `external-id->eid` is a function (employee-external-id → :employment
   eid) the consumer supplies; this keeps kontor agnostic to how the
   engine identifies employees (Silae uses 'matricule'; Sage's default
   is the employee ID).

   `engine` is :silae | :sage | :cegid | <other>; recorded in
   :jurisdiction-specific-codes for downstream introspection."
  [parsed-rows {:keys [external-id->eid pay-period-eid commodity-eid
                       extras-map engine]
                :or {engine :unknown}}]
  (->> parsed-rows
       (group-by :employee-external-id)
       (mapv (fn [[ext-id rows]]
               (let [emp-eid (external-id->eid ext-id)]
                 (when (nil? emp-eid)
                   (throw (ex-info "Unknown employee matricule"
                                   {:matricule ext-id})))
                 (components->fact
                  {:employment-eid emp-eid
                   :pay-period-eid pay-period-eid
                   :commodity-eid commodity-eid
                   :components (mapv #(select-keys % [:kind :amount :employer-side?])
                                     rows)
                   :extras-map extras-map
                   :jurisdiction-specific-codes
                   {:engine engine
                    :matricule ext-id}}))))))

;; ============================================================================
;; SilaeGlProvider
;; ============================================================================

(defrecord SilaeGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :silae)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source column-mapping pay-element-codes
                  external-id->eid commodity-eid extras-map separator]} opts
          ;; Per-call ctx overrides static opts (the same provider
          ;; instance may run for multiple pay-periods).
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes  (or (:pay-element-codes ctx) pay-element-codes)
          mapping (or (:column-mapping ctx) column-mapping)
          sep (or (:separator ctx) separator \;)]
      (when-not source
        (throw (ex-info "SilaeGlProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "SilaeGlProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "SilaeGlProvider needs :pay-element-codes" {})))
      (let [parsed (parse-gl-csv source {:column-mapping mapping
                                         :pay-element-codes codes
                                         :separator sep})]
        (assemble-facts parsed
                        {:external-id->eid ext->eid
                         :pay-period-eid (:pay-period-eid ctx)
                         :commodity-eid commodity-eid
                         :extras-map extras
                         :engine :silae})))))

;; ============================================================================
;; SageGlProvider — same parser, Sage default column-mapping
;; ============================================================================

(def sage-default-column-mapping
  "Sage Paie & RH default 'écritures comptables' CSV column names
   (per public Sage documentation). Customers reconfigure these in the
   Sage GL-export template; the consumer can override via the
   provider's `:column-mapping` opt.

   Sage's default employee identifier column is 'matricule', same as
   Silae. The pay-element column is 'code-rubrique'."
  {:employee-id-col "matricule"
   :pay-element-col "code-rubrique"
   :debit-col       "debit"
   :credit-col      "credit"
   :compte-col      "compte-general"})

(defrecord SageGlProvider [opts]
  pp/PayrollComputeProvider
  (provider-id [_] :sage-paie)
  (compute-payroll [_ ctx]
    (let [{:keys [csv-source pay-element-codes external-id->eid
                  commodity-eid extras-map separator]} opts
          mapping (merge sage-default-column-mapping
                         (:column-mapping opts)
                         (:column-mapping ctx))
          source (or (:csv-source ctx) csv-source)
          ext->eid (or (:external-id->eid ctx) external-id->eid)
          extras (or (:extras-map ctx) extras-map)
          codes  (or (:pay-element-codes ctx) pay-element-codes)
          sep (or (:separator ctx) separator \;)]
      (when-not source
        (throw (ex-info "SageGlProvider needs :csv-source" {})))
      (when-not ext->eid
        (throw (ex-info "SageGlProvider needs :external-id->eid" {})))
      (when-not codes
        (throw (ex-info "SageGlProvider needs :pay-element-codes" {})))
      (let [parsed (parse-gl-csv source {:column-mapping mapping
                                         :pay-element-codes codes
                                         :separator sep})]
        (assemble-facts parsed
                        {:external-id->eid ext->eid
                         :pay-period-eid (:pay-period-eid ctx)
                         :commodity-eid commodity-eid
                         :extras-map extras
                         :engine :sage-paie})))))

;; ============================================================================
;; CegidApiProvider — skeleton; partner-program-gated
;; ============================================================================

(defrecord CegidApiProvider [opts]
  ;; TODO — Cegid Paie API access is partner-program-gated. A consumer
  ;; with enrolled partner credentials wires their OAuth client-id /
  ;; secret through `:credentials` and supplies an HTTP client. This
  ;; skeleton documents the protocol surface; the live wiring lands
  ;; when a partner-program consumer surfaces.
  pp/PayrollComputeProvider
  (provider-id [_] :cegid-paie)
  (compute-payroll [_ _ctx]
    (throw
     (ex-info
      "CegidApiProvider is a skeleton. Cegid Paie API access is partner-program-gated; supply an enrolled OAuth credential + live HTTP client implementation. See ADR-079."
      {:provider :cegid-paie
       :status :skeleton-only
       :opts opts}))))

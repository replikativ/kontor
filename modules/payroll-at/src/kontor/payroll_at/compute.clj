(ns kontor.payroll-at.compute
  "Per-engine adapters that ingest an external payroll engine's export
   file and produce a normalized `:payroll-result` map (ADR-072).

   Two impls in v1:
     - BmdGlProvider — BMD-NTCS Buchungsexport CSV
       (ISO-8859-15, semicolon-separated)
     - RzlGlProvider — RZL Lohn FibuExport CSV
       (ISO-8859-15, semicolon-separated, smaller column set)

   The kernel does NOT run a payroll engine itself. The engine has
   already computed each employee's gross-to-net, applied tariff
   tables, etc.; the adapter is in the IO-and-mapping business.

   The normalized `:payroll-result` shape — the format the rest of the
   adapter consumes:

     {:payroll-result/period      {:from <inst> :to <inst>}
      :payroll-result/employees   [{:vsnr             \"1234567890\"
                                    :name             \"Mustermann, Max\"
                                    :gross-monthly    <bigdec>
                                    :sonderzahlung    <bigdec>      ; if any
                                    :lst              <bigdec>
                                    :sv-arbeitnehmer  <bigdec>
                                    :sv-arbeitgeber   <bigdec>
                                    :db-flag          <bigdec>
                                    :dz               <bigdec>
                                    :kommunalsteuer   <bigdec>
                                    :nettogehalt      <bigdec>
                                    :commodity        :EUR
                                    :beitragsgruppe   \"D1\"
                                    :line-items       [...]
                                    } ...]
      :payroll-result/totals       {:wage-type → bigdec} (period-summary)
      :payroll-result/source-file  \"file://...\"}"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-at.wage-types :as wt])
  (:import [java.io InputStreamReader]
           [java.math BigDecimal RoundingMode]
           [java.nio.charset Charset]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol AtEngineProvider
  "Pluggable AT-payroll-engine CSV ingestion (intentionally distinct
   from the kernel `kontor.provider.payroll-provider/PayrollComputeProvider`
   protocol — this one operates at the BMD/RZL CSV layer; the
   kernel protocol operates at the `PayrollFacts` layer. The
   `kontor.payroll-at.adapter` namespace bridges the two).

   The kernel ships two impls (BMD + RZL); consumers extend the
   protocol for additional Austrian engines.

   ADR-072 — implementations consume raw bytes (from `:source-uri`)
   and emit the normalized `:payroll-result`. No kernel side effects
   here — the result feeds `posting-builder/build-tx-data`.

   Renamed from `PayrollEngineProvider` once the kernel
   `kontor.provider.payroll-provider` PayrollProvider trio landed in Stage R;
   the AT engine-CSV layer is a legitimate level above the kernel
   protocol but the name collision was confusing."

  (engine-name [this]
    "Identifying keyword for this engine (`:bmd`, `:rzl`, …).")

  (parse-export [this source]
    "Parse an export. `source` is anything `clojure.java.io/reader`
     accepts (file, URL, InputStream, String). Returns the normalized
     `:payroll-result` map."))

;; ============================================================================
;; Shared helpers
;; ============================================================================

(def ^:private iso-8859-15 (Charset/forName "ISO-8859-15"))

(defn- reader-for [source]
  ;; BMD + RZL both emit ISO-8859-15. utf-8 also works for ASCII rows.
  (cond
    (string? source)
    (java.io.StringReader. source)

    (instance? java.io.InputStream source)
    (InputStreamReader. ^java.io.InputStream source iso-8859-15)

    :else
    (io/reader source :encoding "ISO-8859-15")))

(defn- parse-bd
  "Parse the engine's number format. AT engines use comma as decimal
   separator (\"1.234,56\") or sometimes period (\"1234.56\")."
  ^BigDecimal [s]
  (when (and s (not (str/blank? s)))
    (let [t (-> (str s) str/trim)
          last-comma  (.lastIndexOf t ",")
          last-period (.lastIndexOf t ".")
          normalized
          (cond
            (and (neg? last-comma) (neg? last-period)) t
            (> last-comma last-period)
            (-> t (str/replace "." "") (str/replace "," "."))
            :else
            (str/replace t "," ""))]
      (try
        (BigDecimal. ^String normalized)
        (catch NumberFormatException _
          (throw (ex-info "Cannot parse decimal" {:input s})))))))

(defn- ->cents
  "Snap a parsed BigDecimal to two-decimal cents (HALF-EVEN). The
   engine produced cents already; this is defensive."
  ^BigDecimal [^BigDecimal bd]
  (when bd
    (.setScale bd 2 RoundingMode/HALF_EVEN)))

(defn- parse-date
  "Engines emit yyyy-MM-dd or dd.MM.yyyy. We accept both."
  ^java.util.Date [s]
  (when (and s (not (str/blank? s)))
    (let [t (str/trim s)
          [yyyy mm dd]
          (cond
            (re-matches #"\d{4}-\d{2}-\d{2}" t)
            (str/split t #"-")

            (re-matches #"\d{2}\.\d{2}\.\d{4}" t)
            (let [[d m y] (str/split t #"\.")] [y m d])

            :else (throw (ex-info "Cannot parse date" {:input s})))
          cal (doto (java.util.Calendar/getInstance
                     (java.util.TimeZone/getTimeZone "UTC"))
                (.clear)
                (.set (Integer/parseInt yyyy)
                      (dec (Integer/parseInt mm))
                      (Integer/parseInt dd)))]
      (.getTime cal))))

(defn- sum-by-wage-type
  "Aggregate the per-employee line-items into a {:wage-type → bigdec}
   period summary."
  [employees]
  (->> employees
       (mapcat :line-items)
       (group-by :wage-type)
       (reduce-kv
        (fn [acc wt items]
          (assoc acc wt
                 (->cents
                  (reduce (fn [^BigDecimal a it]
                            (.add a ^BigDecimal (:amount it)))
                          0M items))))
        {})))

(defn- compose-employee-line-items
  "Given an employee's per-wage-type aggregates, materialize the
   `:line-items` vector in a stable order."
  [emp]
  (let [pull (fn [k] (some-> (get emp k) ->cents))
        items (cond-> []
                (pull :gross-monthly)
                (conj {:wage-type :grundgehalt   :amount (pull :gross-monthly)})

                (pull :überstunden)
                (conj {:wage-type :überstunden    :amount (pull :überstunden)})

                ;; The Sonderzahlung is split across :urlaubs- and
                ;; :weihnachts- by the period (June / November) at the
                ;; engine level; the adapter just carries one
                ;; :sonderzahlung column and lets the caller split.
                (pull :sonderzahlung)
                (conj {:wage-type :urlaubsremuneration :amount (pull :sonderzahlung)})

                (pull :sachbezüge)
                (conj {:wage-type :sachbezüge      :amount (pull :sachbezüge)})

                (pull :lst)
                (conj {:wage-type :lohnsteuer      :amount (pull :lst)})

                (pull :sv-arbeitnehmer)
                (conj {:wage-type :sv-arbeitnehmer :amount (pull :sv-arbeitnehmer)})

                (pull :sv-arbeitgeber)
                (conj {:wage-type :sv-arbeitgeber  :amount (pull :sv-arbeitgeber)})

                (pull :db-flag)
                (conj {:wage-type :dienstgeberbeitrag-fond :amount (pull :db-flag)})

                (pull :dz)
                (conj {:wage-type :zuschlag-zum-db :amount (pull :dz)})

                (pull :kommunalsteuer)
                (conj {:wage-type :kommunalsteuer  :amount (pull :kommunalsteuer)})

                (pull :nettogehalt)
                (conj {:wage-type :nettogehalt     :amount (pull :nettogehalt)}))]
    (assoc emp :line-items items)))

;; ============================================================================
;; BMD-NTCS Buchungsexport CSV
;; ============================================================================
;;
;; BMD's "Lohnauswertung-Export" emits a CSV with a header row +
;; per-employee per-period rows. The v1 adapter reads the *summary*
;; format — one row per (period, employee, wage-type) with columns:
;;
;;   "Periode";"VSNR";"Name";"Lohnart-Nr";"Lohnart-Bez";"Betrag";
;;   "Beitragsgruppe";"Konto";"Kostenstelle"
;;
;; The summary collapses the per-day breakdown into a per-period total
;; per wage type — exactly what we need for monthly posting.

(defn- bmd-rows->employees
  "Fold parsed CSV rows (each row is a map) into the normalized
   employee list."
  [rows]
  (->> rows
       (group-by (juxt :vsnr :name))
       (reduce-kv
        (fn [acc [vsnr name] rows]
          (let [emp-base {:vsnr vsnr
                          :name name
                          :beitragsgruppe (some :beitragsgruppe rows)
                          :commodity :EUR}
                ;; collapse to one entry per wage-type
                wage-rollup
                (reduce
                 (fn [m r]
                   (let [wt (:wage-type r)
                         amt (:amount r)
                         k (case wt
                             :grundgehalt              :gross-monthly
                             :überstunden              :überstunden
                             :urlaubsremuneration      :sonderzahlung
                             :weihnachtsremuneration   :sonderzahlung
                             :sachbezüge               :sachbezüge
                             :lohnsteuer               :lst
                             :sv-arbeitnehmer          :sv-arbeitnehmer
                             :sv-arbeitgeber           :sv-arbeitgeber
                             :dienstgeberbeitrag-fond  :db-flag
                             :zuschlag-zum-db          :dz
                             :kommunalsteuer           :kommunalsteuer
                             :nettogehalt              :nettogehalt
                             nil)]
                     (if k
                       (update m k (fnil #(.add ^BigDecimal % ^BigDecimal amt) 0M))
                       m)))
                 emp-base
                 rows)]
            (conj acc (compose-employee-line-items wage-rollup))))
        [])))

(defn- bmd-parse-row [row]
  ;; row is a vector matching the BMD column order documented above.
  (let [[periode vsnr name lohnart-nr _lohnart-bez betrag beitragsgruppe _konto _kst]
        row
        wt (get wt/bmd-wage-code-map (str/trim (or lohnart-nr "")))]
    (cond
      ;; Skip rows we don't recognize (some BMD exports include
      ;; rows for non-payroll Lohnarten — Spesen, Auslagen, etc.)
      (nil? wt) nil

      :else
      {:periode        (str/trim (or periode ""))
       :vsnr           (str/trim (or vsnr ""))
       :name           (str/trim (or name ""))
       :wage-type      wt
       :beitragsgruppe (when-not (str/blank? beitragsgruppe)
                         (str/trim beitragsgruppe))
       :amount         (->cents (parse-bd betrag))})))

(defn- parse-bmd-csv [source]
  ;; BMD uses semicolon separator. The first row is the header.
  (with-open [rdr (reader-for source)]
    (let [rows (vec (csv/read-csv rdr :separator \;))
          _ (when (empty? rows)
              (throw (ex-info "BMD CSV is empty" {})))
          header (mapv (comp str/lower-case str/trim) (first rows))
          ;; The BMD header line lets us be lenient about column order
          ;; but the v1 adapter just expects the documented layout.
          _ (when-not (and (= 9 (count header))
                           (some #(re-find #"periode" %) header)
                           (some #(re-find #"vsnr" %) header))
              (throw (ex-info "BMD CSV header does not match expected layout"
                              {:header header
                               :expected ["Periode" "VSNR" "Name" "Lohnart-Nr"
                                          "Lohnart-Bez" "Betrag"
                                          "Beitragsgruppe" "Konto" "Kostenstelle"]})))
          data-rows (rest rows)
          parsed (keep bmd-parse-row data-rows)]
      parsed)))

(defn- period-bounds
  "Derive {:from :to} bounds from a set of periode strings of the
   form 'yyyy-MM' (the BMD convention)."
  [periode-strs]
  (when-let [first (first periode-strs)]
    (let [[yyyy mm] (str/split first #"-")
          y (Integer/parseInt yyyy)
          m (Integer/parseInt mm)
          cal-from (doto (java.util.Calendar/getInstance
                          (java.util.TimeZone/getTimeZone "UTC"))
                     (.clear)
                     (.set y (dec m) 1))
          ;; first day of next month → exclusive upper bound
          cal-to (doto (java.util.Calendar/getInstance
                        (java.util.TimeZone/getTimeZone "UTC"))
                   (.clear)
                   (.set y (dec m) 1)
                   (.add java.util.Calendar/MONTH 1))]
      {:from (.getTime cal-from)
       :to   (.getTime cal-to)})))

(defrecord BmdGlProvider [source-uri]
  AtEngineProvider
  (engine-name [_] :bmd)
  (parse-export [_ source]
    (let [parsed   (parse-bmd-csv source)
          periodes (distinct (map :periode parsed))
          _ (when (< 1 (count periodes))
              (throw (ex-info "BMD export must contain exactly one period"
                              {:periodes periodes
                               :hint "Split multi-period exports before ingest"})))
          employees (bmd-rows->employees parsed)
          totals    (sum-by-wage-type employees)
          period    (period-bounds periodes)]
      {:payroll-result/period      period
       :payroll-result/employees   (vec employees)
       :payroll-result/totals      totals
       :payroll-result/source-file source-uri
       :payroll-result/engine      :bmd})))

(defn make-bmd-provider
  ([] (->BmdGlProvider nil))
  ([source-uri] (->BmdGlProvider source-uri)))

;; ============================================================================
;; RZL Lohn FibuExport CSV
;; ============================================================================
;;
;; RZL uses a slimmer schema and short alpha wage-codes:
;;
;;   "Periode";"VSNR";"Name";"Code";"Betrag";"BG"
;;
;; Same semicolon separator, same ISO-8859-15 encoding. The :sonder-
;; zahlung column is implicit — when :code is "URL" or "WEI", the
;; entire amount IS the Sonderzahlung.

(defn- rzl-parse-row [row]
  (let [[periode vsnr name code betrag bg] row
        wt (get wt/rzl-wage-code-map (str/trim (or code "")))]
    (when wt
      {:periode        (str/trim (or periode ""))
       :vsnr           (str/trim (or vsnr ""))
       :name           (str/trim (or name ""))
       :wage-type      wt
       :beitragsgruppe (when-not (str/blank? bg) (str/trim bg))
       :amount         (->cents (parse-bd betrag))})))

(defn- parse-rzl-csv [source]
  (with-open [rdr (reader-for source)]
    (let [rows (vec (csv/read-csv rdr :separator \;))
          _ (when (empty? rows)
              (throw (ex-info "RZL CSV is empty" {})))
          header (mapv (comp str/lower-case str/trim) (first rows))
          _ (when-not (and (= 6 (count header))
                           (some #(re-find #"periode" %) header))
              (throw (ex-info "RZL CSV header does not match expected layout"
                              {:header header
                               :expected ["Periode" "VSNR" "Name" "Code" "Betrag" "BG"]})))
          data-rows (rest rows)
          parsed (keep rzl-parse-row data-rows)]
      parsed)))

(defrecord RzlGlProvider [source-uri]
  AtEngineProvider
  (engine-name [_] :rzl)
  (parse-export [_ source]
    (let [parsed (parse-rzl-csv source)
          periodes (distinct (map :periode parsed))
          _ (when (< 1 (count periodes))
              (throw (ex-info "RZL export must contain exactly one period"
                              {:periodes periodes})))
          employees (bmd-rows->employees parsed)
          totals (sum-by-wage-type employees)
          period (period-bounds periodes)]
      {:payroll-result/period      period
       :payroll-result/employees   (vec employees)
       :payroll-result/totals      totals
       :payroll-result/source-file source-uri
       :payroll-result/engine      :rzl})))

(defn make-rzl-provider
  ([] (->RzlGlProvider nil))
  ([source-uri] (->RzlGlProvider source-uri)))

;; ============================================================================
;; Convenience entry points
;; ============================================================================

(defn parse
  "Parse an engine export. `engine` is `:bmd` | `:rzl`. `source` is
   anything io/reader accepts."
  [engine source]
  (let [provider (case engine
                   :bmd (make-bmd-provider)
                   :rzl (make-rzl-provider)
                   (throw (ex-info "Unsupported engine"
                                   {:engine engine
                                    :supported #{:bmd :rzl}})))]
    (parse-export provider source)))

(defn validate-result
  "Defensive: ensure the gross+employer parts and the withholding sum
   to a sensible nettogehalt — flags engine-export rows where columns
   contradict. Returns {:ok? :anomalies}."
  [{:keys [:payroll-result/employees]}]
  (let [anomalies
        (->> employees
             (keep
              (fn [{:keys [vsnr name line-items]}]
                (let [pull (fn [wt]
                             (->> line-items
                                  (filter #(= wt (:wage-type %)))
                                  (map :amount)
                                  (reduce (fn [^BigDecimal a ^BigDecimal b]
                                            (.add a b))
                                          0M)))
                      brutto (->cents (.add (.add ^BigDecimal (pull :grundgehalt)
                                                  ^BigDecimal (pull :überstunden))
                                            ^BigDecimal (pull :urlaubsremuneration)))
                      withholdings (->cents (.add ^BigDecimal (pull :lohnsteuer)
                                                  ^BigDecimal (pull :sv-arbeitnehmer)))
                      claimed-net (->cents (pull :nettogehalt))
                      expected-net (->cents (.subtract brutto withholdings))]
                  ;; tolerate cent-rounding (up to 0.02 either way)
                  (when (and (pos? (.signum claimed-net))
                             (pos? (.signum expected-net))
                             (< 2 (.intValue
                                   (.abs (.subtract claimed-net expected-net)))))
                    {:vsnr vsnr :name name
                     :claimed-net claimed-net
                     :expected-net expected-net}))))
             vec)]
    {:ok? (empty? anomalies)
     :anomalies anomalies}))

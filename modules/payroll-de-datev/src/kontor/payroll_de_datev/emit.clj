(ns kontor.payroll-de-datev.emit
  "DATEV LODAS Importdatei encoder + PayrollEmitProvider impl
   (`DatevLodasEmitProvider`).

   Per research note 82 §2 + §8.2: the C2 EmitProvider writes a single
   inbound payload to LODAS — the **Importdatei**, a Latin-1 / CR-LF /
   semicolon-delimited 4-section ASCII text file:

     [Allgemein]         — file-level header (Ziel=LODAS, BeraterNr,
                            MandantenNr, Datumsformat, …)
     [Satzbeschreibung]  — per-file mini-schema; declares record-class
                            ordinal + DB table + ordered column list
     [Stammdaten]        — per-employee master rows (optional)
     [Bewegungsdaten]    — per-employee per-period variable rows
                            (optional; at least one of Stammdaten /
                            Bewegungsdaten must be present)

   See note 82 §2 for the format spec and §8 for the DE scoping of the
   PayrollEmitProvider (only LODAS Importdatei; DEÜV / GKV-Monats-
   meldung / SV-Beitragsnachweis / ELStAM stay inside DATEV).

   License posture (ADR-001 / CLAUDE.md): the LODAS file format is a
   public DATEV cooperative specification; we describe it and write a
   clean-room encoder. No proprietary code lifted; no bundled
   wage-type catalog (consumer-supplied per ADR-005 / ADR-071 /
   ADR-075)."
  (:require [clojure.string :as str]
            [kontor.payroll-de-datev.wage-types :as wage-types]
            [kontor.payroll-provider :as pp])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date TimeZone]))

;; ============================================================================
;; Format constants (note 82 §2.1)
;; ============================================================================

(def ^:const lodas-encoding
  "ISO-8859-1 (Latin-1). UTF-8 is NOT reliably accepted by LODAS;
   Umlaute must be in the Latin-1 code points. Note 82 §2.1."
  "ISO-8859-1")

(def ^:const lodas-line-terminator
  "Windows CR/LF — LF-only is rejected by the LODAS importer. Note 82 §2.1."
  "\r\n")

(def ^:const lodas-field-separator
  "Semicolon is the de-facto standard; configurable via
   `Feldtrennzeichen=;` in [Allgemein]. Note 82 §2.1."
  ";")

(def ^:const lodas-comment-char
  "Comment line marker. Note 82 §2.1."
  "*")

;; ============================================================================
;; Formatters
;; ============================================================================

(def ^:private utc (TimeZone/getTimeZone "UTC"))
(def ^:private ddmmyyyy-fmt (DateTimeFormatter/ofPattern "ddMMyyyy"))
(def ^:private dd-mm-yyyy-fmt (DateTimeFormatter/ofPattern "dd.MM.yyyy"))

(defn- ^LocalDate inst->local
  [^Date d]
  (.toLocalDate (.atZone (.toInstant d) (.toZoneId utc))))

(defn- format-date
  "Format a java.util.Date per the LODAS Datumsformat directive.
   :ttmmjjjj → `ddMMyyyy` (default), :tt-mm-jjjj → `dd.MM.yyyy`."
  [^Date d datumsformat]
  (.format (inst->local d)
           (case datumsformat
             :tt-mm-jjjj dd-mm-yyyy-fmt
             ddmmyyyy-fmt)))

(defn- format-decimal
  "DATEV decimals use comma as separator, no thousands grouping.
   `1234.56M` → `1234,56`; `0M` → `0,00`."
  [^java.math.BigDecimal bd]
  (-> bd
      (.setScale 2 java.math.RoundingMode/HALF_EVEN)
      .toPlainString
      (str/replace "." ",")))

(defn escape
  "DATEV string fields wrapped in double quotes when they contain
   the field separator, a quote, or a newline. Internal quotes
   doubled (RFC 4180 style). Note 82 §2.1."
  [^String s]
  (let [s (or s "")]
    (if (some #(or (= % \;) (= % \") (= % \newline) (= % \return)) s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn- format-cell
  "Coerce a value to its LODAS field representation."
  [v]
  (cond
    (nil? v)                  ""
    (string? v)               (escape v)
    (instance? java.math.BigDecimal v) (format-decimal v)
    (integer? v)              (str v)
    (instance? Date v)        (format-date v :tt-mm-jjjj)
    :else                     (escape (str v))))

;; ============================================================================
;; [Allgemein] block
;; ============================================================================

(defn render-allgemein
  "Build the `[Allgemein]` section as a vector of lines (without the
   trailing CR/LF — `join-file` adds those). Note 82 §2.2.

   Required opts:
     :berater-nr      — 4–7 digit string, DATEV consultant number
     :mandant-nr      — string, client number within consultant scope
     :stammdaten-gueltig-ab — java.util.Date or pre-formatted string,
                              the effective date for master changes
                              (required when shipping [Stammdaten])

   Optional opts:
     :target          — :lodas (default) | :lug
     :datumsformat    — :ttmmjjjj (default) | :tt-mm-jjjj
     :kommentarzeichen — single-char string (default \"*\")
     :feldtrennzeichen — single-char string (default \";\")
     :version-sst     — interface version string (default \"1.0\")
     :version-db      — LODAS DB version string (default \"12.2\")
     :betriebliche-pnr? — boolean (default false); when true emits
                          `BetrieblichePNrVerwenden=Ja`"
  [{:keys [target berater-nr mandant-nr stammdaten-gueltig-ab
           datumsformat kommentarzeichen feldtrennzeichen
           version-sst version-db betriebliche-pnr?]
    :or {target :lodas
         datumsformat :ttmmjjjj
         kommentarzeichen "*"
         feldtrennzeichen ";"
         version-sst "1.0"
         version-db "12.2"
         betriebliche-pnr? false}}]
  (when-not berater-nr (throw (ex-info ":berater-nr required" {})))
  (when-not mandant-nr (throw (ex-info ":mandant-nr required" {})))
  (let [target-str (case target :lodas "LODAS" :lug "LuG")
        gueltig-ab-str (cond
                         (nil? stammdaten-gueltig-ab) nil
                         (instance? Date stammdaten-gueltig-ab)
                         (format-date stammdaten-gueltig-ab datumsformat)
                         :else (str stammdaten-gueltig-ab))
        datumsformat-str (case datumsformat
                           :tt-mm-jjjj "TT.MM.JJJJ"
                           "TTMMJJJJ")]
    (cond-> ["[Allgemein]"
             (str "Ziel=" target-str)
             (str "Kommentarzeichen=" kommentarzeichen)
             (str "Feldtrennzeichen=" feldtrennzeichen)
             (str "BeraterNr=" berater-nr)
             (str "MandantenNr=" mandant-nr)
             (str "Datumsformat=" datumsformat-str)
             (str "Version_SST=" version-sst)
             (str "Version_DB=" version-db)]
      gueltig-ab-str        (conj (str "StammdatenGueltigAb=" gueltig-ab-str))
      betriebliche-pnr?     (conj "BetrieblichePNrVerwenden=Ja"))))

;; ============================================================================
;; [Satzbeschreibung] block — per-file mini-schema
;; ============================================================================

(defn render-satzbeschreibung
  "Render the `[Satzbeschreibung]` section from an ordered seq of
   record-class specs:

     [{:ord 1 :table \"u_lod_psd_mitarbeiter\"
       :fields [\"pnr_betriebliche#psd\" \"duevo_familienname#psd\" …]}
      {:ord 2 :table \"u_lod_bwd_buchung_standard\"
       :fields [\"abrechnung_zeitraum#bwd\" \"pnr#bwd\" …]}]

   Note 82 §2.2 / §2.3 / §2.4."
  [record-classes]
  (cons "[Satzbeschreibung]"
        (mapv (fn [{:keys [ord table fields]}]
                (when-not ord    (throw (ex-info ":ord required" {})))
                (when-not table  (throw (ex-info ":table required" {})))
                (when-not (seq fields)
                  (throw (ex-info ":fields required" {})))
                (str/join lodas-field-separator
                          (cons (str ord)
                                (cons table fields))))
              record-classes)))

;; ============================================================================
;; [Stammdaten] / [Bewegungsdaten] body rows
;; ============================================================================

(defn render-body-row
  "Render one body row given a record-class ord + a positional vector
   of field values. Note 82 §2.2."
  [ord values]
  (str/join lodas-field-separator
            (cons (str ord) (mapv format-cell values))))

(defn- render-section
  [section-name ord-rows]
  (when (seq ord-rows)
    (cons (str "[" section-name "]")
          (mapv (fn [[ord vs]] (render-body-row ord vs))
                ord-rows))))

;; ============================================================================
;; File assembly
;; ============================================================================

(defn render-importdatei
  "Assemble a complete LODAS Importdatei as a single string with the
   correct CR/LF discipline. Caller writes this to a file with
   `:encoding \"ISO-8859-1\"` via `write-importdatei!`.

   Input shape (note 82 §9.2 worked example):

     {:allgemein           {:berater-nr  …  :mandant-nr  …  …}
      :record-classes      [{:ord 1 :table … :fields […]}
                            {:ord 2 :table … :fields […]}]
      :stammdaten-rows     [[1 [v0 v1 …]] [1 [w0 w1 …]] …]
      :bewegungsdaten-rows [[2 [v0 v1 …]] …]}

   At least one of :stammdaten-rows / :bewegungsdaten-rows must be
   non-empty per the LODAS spec."
  [{:keys [allgemein record-classes stammdaten-rows bewegungsdaten-rows]}]
  (when-not (seq record-classes)
    (throw (ex-info ":record-classes required" {})))
  (when (and (empty? stammdaten-rows) (empty? bewegungsdaten-rows))
    (throw (ex-info "at least one of :stammdaten-rows / :bewegungsdaten-rows required"
                    {})))
  (let [parts (concat (render-allgemein allgemein)
                      [""]
                      (render-satzbeschreibung record-classes)
                      [""]
                      (render-section "Stammdaten" stammdaten-rows)
                      (when (seq stammdaten-rows) [""])
                      (render-section "Bewegungsdaten" bewegungsdaten-rows))]
    (str (str/join lodas-line-terminator (remove nil? parts))
         lodas-line-terminator)))

(defn write-importdatei!
  "Convenience: write the Importdatei string to `filepath` in
   ISO-8859-1 encoding."
  [filepath input]
  (spit filepath (render-importdatei input) :encoding lodas-encoding))

;; ============================================================================
;; PayrollFacts → Bewegungsdaten rows (note 82 §2.4 — u_lod_bwd_buchung_standard)
;; ============================================================================

(def u-lod-bwd-buchung-standard-fields
  "The standard Bewegungsdaten table — the most common Bewegungs-row.
   Note 82 §2.4."
  ["abrechnung_zeitraum#bwd"
   "pnr#bwd"
   "bs_nr#bwd"
   "la_eigene#bwd"
   "bs_wert_butab#bwd"
   "kostenstelle#bwd"
   "bemerkung#bwd"])

(defn- component->lohnart
  "Resolve a :compensation-component (or PayrollFact component) to
   its LODAS Lohnart-Nr. Strategy:
     1. Explicit :lohnart-nr on the component (preferred).
     2. Reverse-lookup in :catalog/wage-types by (:kind, :account-hint).
     3. nil → caller routes to :unmapped-wage-types queue."
  [{:keys [catalog/wage-types]} component]
  (or (:lohnart-nr component)
      (when wage-types
        (let [{:keys [kind account-hint]} component]
          (some (fn [[lohnart entry]]
                  (when (and (= (:kind entry) kind)
                             (or (nil? account-hint)
                                 (= (:account-hint entry) account-hint)))
                    lohnart))
                wage-types)))))

(defn payroll-facts->bewegungsdaten-rows
  "Convert a vector of PayrollFacts to LODAS Bewegungsdaten body
   rows (record-class ordinal 2 = u_lod_bwd_buchung_standard).

   Per PayrollFact, emits one row per non-employer-side component
   that maps to a Lohnart. employer-side components are computed
   inside LODAS (KV/RV/AV/PV/UV split per Beitragssatz tables);
   we do NOT push them as Bewegungsdaten.

   Returns `{:rows [[ord values] …] :unmapped [components-without-lohnart]}`.
   The caller can surface :unmapped to a manual-review queue per
   note 82 §6.3.5."
  [{:keys [pay-period-date catalog ord]
    :or {ord 2}} facts]
  (let [stamp (some-> pay-period-date (format-date :tt-mm-jjjj))]
    (reduce
     (fn [acc {:keys [employment components] :as fact}]
       (reduce
        (fn [{:keys [rows unmapped bs-nr] :as acc'} comp]
          (if (:employer-side? comp)
            acc'
            (let [lohnart (component->lohnart catalog comp)]
              (if lohnart
                {:rows (conj rows
                             [ord [stamp
                                   (str (or (:employment-pnr fact)
                                            employment))
                                   bs-nr
                                   lohnart
                                   (:amount comp)
                                   (:kostenstelle comp)
                                   (or (:memo comp) "")]])
                 :unmapped unmapped
                 :bs-nr (inc bs-nr)}
                {:rows rows
                 :unmapped (conj unmapped {:employment employment
                                           :component comp
                                           :reason :no-lohnart})
                 :bs-nr bs-nr}))))
        acc
        components))
     {:rows [] :unmapped [] :bs-nr 1}
     facts)))

;; ============================================================================
;; PayrollEmitProvider — DatevLodasEmitProvider
;; ============================================================================

(defrecord DatevLodasEmitProvider [opts]
  pp/PayrollEmitProvider
  (emit-payroll-events [_ facts {:keys [pay-period-eid entity-eid] :as _ctx}]
    (let [{:keys [catalog allgemein pay-period-date
                  pay-period-code uri-prefix]} opts
          _ (when-not catalog
              (throw (ex-info "DatevLodasEmitProvider requires :catalog in opts" {})))
          _ (when-not (seq facts)
              (throw (ex-info "DatevLodasEmitProvider: no PayrollFacts to emit" {})))
          {:keys [rows unmapped]}
          (payroll-facts->bewegungsdaten-rows
           {:pay-period-date pay-period-date :catalog catalog} facts)
          record-classes [{:ord 2
                           :table "u_lod_bwd_buchung_standard"
                           :fields u-lod-bwd-buchung-standard-fields}]
          file-content (render-importdatei
                        {:allgemein allgemein
                         :record-classes record-classes
                         :bewegungsdaten-rows rows})]
      ;; Return one :audit-doc per emission (note 79 §2.4 +
      ;; ADR-075 PayrollEmitProvider contract).
      ;; :audit-doc/category :payroll-filing per note 86 P0-86-2
      ;; (canonical vocabulary; this is a periodic payroll-engine
      ;; emission to a regulator, NOT a tax-return-shaped filing).
      [{:audit-doc/code (str (or uri-prefix "LODAS-") pay-period-code)
        :audit-doc/type :emit-payload
        :audit-doc/category :payroll-filing
        :audit-doc/storage-uri (str (or uri-prefix "lodas://import/")
                                    pay-period-code ".txt")
        :audit-doc/uploaded-at (java.util.Date.)
        :audit-doc/inline-payload file-content
        :audit-doc/payroll-period pay-period-eid
        :audit-doc/payroll-entity entity-eid
        :audit-doc/unmapped-count (long (count unmapped))}])))

(defn make-provider
  "Construct a DatevLodasEmitProvider. Required opts:

     :catalog          — validated wage-type catalog (per
                          `kontor.payroll-de-datev.wage-types/validate-catalog`)
     :allgemein        — map of [Allgemein] section opts (per
                          `render-allgemein`)
     :pay-period-date  — java.util.Date marking the abrechnung_zeitraum
                          for the body rows (typically the pay-period
                          start)
     :pay-period-code  — string used in the audit-doc code + filename

   Optional:
     :uri-prefix       — string prefix for :audit-doc/storage-uri"
  [opts]
  (when-not (:catalog opts)
    (throw (ex-info ":catalog required" {})))
  (when-not (:allgemein opts)
    (throw (ex-info ":allgemein required" {})))
  (->DatevLodasEmitProvider opts))

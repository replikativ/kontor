(ns kontor.import-datev.extf
  "The shared DATEV **EXTF** codec — header + CSV-row grammar, parse and
   render, one spec-correct implementation.

   EXTF (Export-Format) is DATEV's semicolon-separated interchange format
   for handing data to / from third-party programs (as opposed to DTVF,
   DATEV's internal format). A file is:

     line 1  — the **header** (`\"EXTF\";Versionsnummer;Datenkategorie;…`)
     line 2  — the column-label line
     line 3+ — data rows

   Encoding is ISO-8859-1; line ending CR-LF; separator `;`; strings that
   contain `;`/`\"`/newline are double-quoted with internal quotes doubled
   (RFC-4180 style); decimals use a comma and no thousands separator.

   This namespace is the single home for those conventions. Before it,
   three copies existed — `l10n-de/datev.clj` (GL export), and
   `payroll-de-datev`'s `compute.clj` (parse) + `emit.clj` (LODAS, a
   different format) — that drifted. Notably the GL exporter wrote the
   line-count into **header field 5**, which the spec reserves for the
   **Formatversion** (note 195 G1): a real DATEV import misreads the
   header. This codec fixes that by construction — field 5 is the
   Formatversion, pinned to the Versionsnummer via `version->formatversion`.

   Reference: developer.datev.de DATEV-Format (Buchungsstapel); the
   field order + the {Versionsnummer, Formatversion} pairing were
   cross-checked against the MIT-licensed `ledermann/datev` example header
   (see test/resources)."
  (:require [clojure.string :as str])
  (:import [java.time LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Date TimeZone]
           [java.math BigDecimal RoundingMode]))

;; ============================================================================
;; Datenkategorie + version pairing
;; ============================================================================

(def datenkategorie
  "The EXTF Datenkategorie codes this codec knows (header field 3)."
  {:buchungsstapel      21
   :debitoren-kreditoren 16
   :kontenbeschriftung  20})

(def version->formatversion
  "DATEV pins the Formatversion (header field 5) to the Versionsnummer
   (field 2). The two valid Buchungsstapel pairs in the wild: 510→7 and
   700→13. Cross-checked against a real EXTF file (510/7) and the MIT
   `ledermann/datev` example (700/13)."
  {510 7
   700 13})

;; ============================================================================
;; Cell / row codec
;; ============================================================================

(defn- parse-cell
  "Strip surrounding quotes; un-double internal quotes (RFC-4180)."
  [^String cell]
  (let [s (str/trim cell)]
    (if (and (>= (count s) 2) (.startsWith s "\"") (.endsWith s "\""))
      (-> s (subs 1 (dec (count s))) (str/replace "\"\"" "\""))
      s)))

(defn split-row
  "Split one EXTF row on `;`, respecting double-quote escaping (internal
   quotes doubled). Returns a vector of unquoted cell strings."
  [^String line]
  (loop [chars (.toCharArray line)
         i 0
         field (StringBuilder.)
         in-quote? false
         out (transient [])]
    (if (>= i (alength chars))
      (persistent! (conj! out (parse-cell (.toString field))))
      (let [c (aget chars i)
            next-c (when (< (inc i) (alength chars)) (aget chars (inc i)))]
        (cond
          (and in-quote? (= c \") (= next-c \"))
          (do (.append field c) (.append field next-c)
              (recur chars (+ i 2) field in-quote? out))

          (= c \")
          (do (.append field c)
              (recur chars (inc i) field (not in-quote?) out))

          (and (not in-quote?) (= c \;))
          (recur chars (inc i) (StringBuilder.) in-quote?
                 (conj! out (parse-cell (.toString field))))

          :else
          (do (.append field c)
              (recur chars (inc i) field in-quote? out)))))))

(defn escape
  "Quote an EXTF string field iff it contains `;`, `\"`, or a newline;
   double internal quotes."
  [s]
  (let [s (or s "")]
    (if (some #(or (= % \;) (= % \") (= % \newline)) s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn render-row
  "Render a vector of cell values (nil → empty) as one EXTF row."
  [cells]
  (str/join ";" (map (fn [c] (escape (if (nil? c) "" (str c)))) cells)))

;; ============================================================================
;; Scalar codecs — amount, dates
;; ============================================================================

(defn parse-decimal
  "EXTF decimal: comma separator, no thousands grouping. `\"4000,00\"` →
   4000.00M; blank → 0M. The sign is carried by the Soll/Haben-Kz, not
   here, so this is always non-negative."
  ^BigDecimal [s]
  (let [s (str/trim (or s ""))]
    (if (str/blank? s)
      0M
      (-> s (str/replace "," ".") (BigDecimal.) (.setScale 2 RoundingMode/HALF_EVEN)))))

(defn format-amount
  "BigDecimal → EXTF amount string: 2-decimal, comma, unsigned (the
   Soll/Haben-Kz carries the sign)."
  [^BigDecimal bd]
  (-> (.abs bd) (.setScale 2 RoundingMode/HALF_EVEN) .toPlainString
      (str/replace "." ",")))

(def ^:private utc (TimeZone/getTimeZone "UTC"))
(def ^:private ddmm-fmt (DateTimeFormatter/ofPattern "ddMM"))
(def ^:private yyyymmdd-fmt (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def ^:private stamp-fmt (DateTimeFormatter/ofPattern "yyyyMMddHHmmssSSS"))

(defn- ^LocalDate inst->local [^Date d]
  (.toLocalDate (.atZone (.toInstant d) (.toZoneId utc))))

(defn format-belegdatum
  "Date → EXTF Belegdatum `DDMM` (the year comes from the header WJ)."
  [^Date d]
  (.format (inst->local d) ddmm-fmt))

(defn parse-belegdatum
  "EXTF Belegdatum `DDMM` + the header's fiscal year → a UTC Date, or nil
   when blank/malformed."
  [s ^long year]
  (let [s (str/trim (or s ""))]
    (when (and (= 4 (count s)) (every? #(Character/isDigit ^char %) s))
      (let [dd (Integer/parseInt (subs s 0 2))
            mm (Integer/parseInt (subs s 2 4))
            ld (LocalDate/of (int year) (int mm) (int dd))]
        (Date/from (.toInstant (.atStartOfDay ld (ZoneId/of "UTC"))))))))

(defn format-period-bound
  "Date → EXTF header period bound `yyyyMMdd`."
  [^Date d]
  (.format (inst->local d) yyyymmdd-fmt))

(defn format-stamp
  "LocalDateTime → EXTF `Erzeugt am` `yyyyMMddHHmmssSSS`."
  [^LocalDateTime ldt]
  (.format ldt stamp-fmt))

;; ============================================================================
;; Header — the load-bearing part, spec-correct field order
;; ============================================================================

;; The EXTF header field order (1-indexed), per developer.datev.de +
;; the MIT ledermann example. Fields past 22 are optional and emitted
;; empty. Field 5 is the Formatversion — THE fix vs the old exporter.
(def ^:private header-fields
  [:kennzeichen       ; 1  always "EXTF"
   :versionsnummer    ; 2  510 | 700
   :datenkategorie    ; 3  21 = Buchungsstapel
   :formatname        ; 4  "Buchungsstapel"
   :formatversion     ; 5  7 (for 510) | 13 (for 700)  ← was the line count
   :erzeugt-am        ; 6  yyyyMMddHHmmssSSS
   :importiert        ; 7  (empty on export)
   :herkunft          ; 8  2-char origin
   :exportiert-von    ; 9
   :importiert-von    ; 10 (empty)
   :berater           ; 11
   :mandant           ; 12
   :wj-beginn         ; 13 yyyyMMdd
   :sachkontenlaenge  ; 14
   :datum-von         ; 15 yyyyMMdd
   :datum-bis         ; 16 yyyyMMdd
   :bezeichnung       ; 17
   :diktatkuerzel     ; 18
   :buchungstyp       ; 19 1 = Finanzbuchführung
   :rechnungslegungszweck ; 20
   :festschreibung    ; 21
   :wkz])             ; 22 EUR

(def ^:const header-field-count (count header-fields))

(defn render-header
  "Render the EXTF header from a map of the fields in `header-fields`.
   Fills field 1 (`\"EXTF\"`) and field 5 (Formatversion, derived from
   `:versionsnummer` unless given) automatically; unknown/absent fields
   render empty. `:erzeugt-am` may be a LocalDateTime (formatted) or a
   ready string."
  [{:keys [versionsnummer erzeugt-am] :as m}]
  (let [vnr (or versionsnummer 510)
        m   (assoc m
                   :kennzeichen "EXTF"
                   :versionsnummer vnr
                   :formatversion (or (:formatversion m)
                                      (version->formatversion vnr)
                                      (throw (ex-info "EXTF: no Formatversion for Versionsnummer"
                                                      {:versionsnummer vnr})))
                   :erzeugt-am (cond
                                 (instance? LocalDateTime erzeugt-am) (format-stamp erzeugt-am)
                                 (nil? erzeugt-am) (format-stamp (LocalDateTime/now))
                                 :else erzeugt-am))]
    (render-row (map m header-fields))))

(defn parse-header
  "Parse an EXTF header line into a map keyed by `header-fields`, plus
   `:fiscal-year` (the yyyy of `:wj-beginn` or `:datum-von`) for
   Belegdatum resolution. Coerces `:versionsnummer`/`:formatversion`/
   `:sachkontenlaenge` to longs when numeric."
  [^String header-line]
  (let [cols (split-row header-line)
        m    (zipmap header-fields cols)
        ->long (fn [x] (when (and x (re-matches #"\d+" x)) (parse-long x)))
        year (let [wj (or (:wj-beginn m) (:datum-von m))]
               (when (and wj (>= (count wj) 4)) (parse-long (subs wj 0 4))))]
    (-> m
        (update :versionsnummer ->long)
        (update :datenkategorie ->long)
        (update :formatversion ->long)
        (update :sachkontenlaenge ->long)
        (assoc :fiscal-year year))))

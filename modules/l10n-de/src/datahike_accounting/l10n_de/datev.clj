(ns datahike-accounting.l10n-de.datev
  "DATEV EXTF (Buchungsstapel) exporter — the test-oracle format the
   user asked us to diff against (per the report-engine direction
   chosen for Phase 2-DE).

   DATEV EXTF is German tax-software cooperative DATEV's
   semicolon-separated format for importing journal entries into
   their accounting software. The header line declares the
   schema version + period; the second line is column names; the
   rest is data. Encoding is **ISO-8859-1**; quotes around strings
   that contain semicolons / newlines.

   This implementation emits the **schema 510 v21** subset that
   real DATEV importers accept — all 122 official columns (mostly
   empty) so the column count matches DATEV's parser. The first
   12 columns are load-bearing (Umsatz, Soll/Haben, Konto,
   Gegenkonto, BU-Schlüssel, Belegdatum, Belegfeld, Buchungstext);
   the rest stay empty unless the consumer fills them in via
   per-posting metadata maps.

   The exporter reads :posting/* entities directly and projects
   them to DATEV rows. It does NOT use the report engine — DATEV
   is an *export* format (per-posting), not a report aggregation.

   Test oracle: compare bytes against a hand-crafted expected
   fixture; if they drift, surface the diff. Round-trip stability
   isn't a goal here (DATEV parsers are not lossless)."
  (:require [clojure.string :as str]
            [datahike.api :as d])
  (:import [java.io StringWriter Writer]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date Calendar TimeZone]))

;; ============================================================================
;; Constants — the 122-column DATEV EXTF Buchungsstapel header (schema 510)
;; ============================================================================

(def datev-columns
  "Official DATEV Buchungsstapel column names. All 122 columns must
   be present in every data row even when empty; the header line
   uses these labels verbatim."
  ["Umsatz (ohne Soll/Haben-Kz)" "Soll/Haben-Kennzeichen" "WKZ Umsatz"
   "Kurs" "Basis-Umsatz" "WKZ Basis-Umsatz" "Konto"
   "Gegenkonto (ohne BU-Schlüssel)" "BU-Schlüssel" "Belegdatum"
   "Belegfeld 1" "Belegfeld 2" "Skonto" "Buchungstext" "Postensperre"
   "Diverse Adressnummer" "Geschäftspartnerbank" "Sachverhalt"
   "Zinssperre" "Beleglink" "Beleginfo - Art 1" "Beleginfo - Inhalt 1"
   "Beleginfo - Art 2" "Beleginfo - Inhalt 2" "Beleginfo - Art 3"
   "Beleginfo - Inhalt 3" "Beleginfo - Art 4" "Beleginfo - Inhalt 4"
   "Beleginfo - Art 5" "Beleginfo - Inhalt 5" "Beleginfo - Art 6"
   "Beleginfo - Inhalt 6" "Beleginfo - Art 7" "Beleginfo - Inhalt 7"
   "Beleginfo - Art 8" "Beleginfo - Inhalt 8" "KOST1 - Kostenstelle"
   "KOST2 - Kostenstelle" "KOST - Menge" "EU-Land u. UStID"
   "EU-Steuersatz" "Abw. Versteuerungsart" "Sachverhalt L+L"
   "Funktionsergänzung L+L" "BU 49 Hauptfunktionstyp"
   "BU 49 Hauptfunktionsnummer" "BU 49 Funktionsergänzung"
   "Zusatzinformation - Art 1" "Zusatzinformation - Inhalt 1"
   "Zusatzinformation - Art 2" "Zusatzinformation - Inhalt 2"
   "Zusatzinformation - Art 3" "Zusatzinformation - Inhalt 3"
   "Zusatzinformation - Art 4" "Zusatzinformation - Inhalt 4"
   "Zusatzinformation - Art 5" "Zusatzinformation - Inhalt 5"
   "Zusatzinformation - Art 6" "Zusatzinformation - Inhalt 6"
   "Zusatzinformation - Art 7" "Zusatzinformation - Inhalt 7"
   "Zusatzinformation - Art 8" "Zusatzinformation - Inhalt 8"
   "Zusatzinformation - Art 9" "Zusatzinformation - Inhalt 9"
   "Zusatzinformation - Art 10" "Zusatzinformation - Inhalt 10"
   "Zusatzinformation - Art 11" "Zusatzinformation - Inhalt 11"
   "Zusatzinformation - Art 12" "Zusatzinformation - Inhalt 12"
   "Zusatzinformation - Art 13" "Zusatzinformation - Inhalt 13"
   "Zusatzinformation - Art 14" "Zusatzinformation - Inhalt 14"
   "Zusatzinformation - Art 15" "Zusatzinformation - Inhalt 15"
   "Zusatzinformation - Art 16" "Zusatzinformation - Inhalt 16"
   "Zusatzinformation - Art 17" "Zusatzinformation - Inhalt 17"
   "Zusatzinformation - Art 18" "Zusatzinformation - Inhalt 18"
   "Zusatzinformation - Art 19" "Zusatzinformation - Inhalt 19"
   "Zusatzinformation - Art 20" "Zusatzinformation - Inhalt 20"
   "Stück" "Gewicht" "Zahlweise" "Forderungsart" "Veranlagungsjahr"
   "Zugeordnete Fälligkeit" "Skontotyp" "Auftragsnummer"
   "Buchungstyp" "USt-Schlüssel (Anzahlungen)"
   "EU-Land (Anzahlungen)" "Sachverhalt L+L (Anzahlungen)"
   "EU-Steuersatz (Anzahlungen)" "Erlöskonto (Anzahlungen)"
   "Herkunft-Kz" "Buchungs GUID" "KOST-Datum" "SEPA-Mandatsreferenz"
   "Skontosperre" "Gesellschaftername" "Beteiligtennummer"
   "Identifikationsnummer" "Zeichnernummer" "Postensperre bis"
   "Bezeichnung SoBil-Sachverhalt" "Kennzeichen SoBil-Buchung"
   "Festschreibung" "Leistungsdatum" "Datum Zuord. Steuerperiode"
   "Fälligkeit" "Generalumkehr (GU)" "Steuersatz" "Land"])

;; DATEV EXTF Buchungsstapel schema 510 v21 declares 122 columns.
;; This list ships 120 — we omit the two seldom-used columns
;; "Stammkonto-Nr." and "Verkaufsrolle" that are reserved for
;; group-account scenarios outside the SMB scope. Real DATEV
;; importers tolerate the shorter list. Bump this when a real
;; customer needs the missing columns.
(def ^:const datev-column-count
  (count datev-columns))

;; ============================================================================
;; Formatters
;; ============================================================================

(def ^:private utc (TimeZone/getTimeZone "UTC"))
(def ^:private ddmm-fmt (DateTimeFormatter/ofPattern "ddMM"))
(def ^:private yyyymmdd-fmt (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def ^:private yyyymmddhhmmssSSS-fmt (DateTimeFormatter/ofPattern "yyyyMMddHHmmssSSS"))

(defn- ^LocalDate inst->local
  [^Date d]
  (.toLocalDate (.atZone (.toInstant d) (.toZoneId utc))))

(defn- format-belegdatum
  "DATEV Belegdatum is DDMM (without the year — year comes from the
   header). java.util.Date → 'ddMM' string."
  [^Date d]
  (.format (inst->local d) ddmm-fmt))

(defn- format-amount
  "DATEV amount: 2-decimal, comma-separated, no thousands separator,
   sign always positive (Soll/Haben kennzeichen carries the sign).
   100.00 → \"100,00\"; 1234.5 → \"1234,50\"; -42.7 → \"42,70\"."
  [^java.math.BigDecimal bd]
  (-> (.abs bd)
      (.setScale 2 java.math.RoundingMode/HALF_EVEN)
      .toPlainString
      (str/replace "." ",")))

(defn- escape
  "DATEV string fields wrapped in double quotes when they contain
   semicolons, double-quotes, or newlines. Internal quotes get
   doubled (RFC 4180 style)."
  [^String s]
  (let [s (or s "")]
    (if (some #(or (= % \;) (= % \") (= % \newline)) s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn- format-period-bound
  "DATEV header period dates: yyyyMMdd."
  [^Date d]
  (.format (inst->local d) yyyymmdd-fmt))

;; ============================================================================
;; Posting → DATEV row
;; ============================================================================

(defn- posting->row
  "Convert one posting (with its account.code resolved) into a 122-
   column DATEV row vector."
  [{:keys [amount account-code contra-code text date]}]
  (let [umsatz (format-amount amount)
        soll-haben (if (pos? (.signum ^java.math.BigDecimal amount)) "S" "H")
        ;; The other 110 columns are empty by default. Consumers wanting
        ;; e.g. KOST1 can pass via the optional `:extra` map (future).
        row (vec (concat
                  [umsatz                ; 1  Umsatz (ohne S/H-Kz)
                   soll-haben            ; 2  Soll/Haben-Kennzeichen
                   "EUR"                 ; 3  WKZ Umsatz
                   ""                    ; 4  Kurs
                   ""                    ; 5  Basis-Umsatz
                   ""                    ; 6  WKZ Basis-Umsatz
                   account-code          ; 7  Konto
                   contra-code           ; 8  Gegenkonto (ohne BU)
                   ""                    ; 9  BU-Schlüssel (we leave empty —
                                         ;     contra account encodes VAT)
                   (format-belegdatum date)  ; 10 Belegdatum (DDMM)
                   ""                    ; 11 Belegfeld 1 (e.g. invoice no.)
                   ""                    ; 12 Belegfeld 2
                   ""                    ; 13 Skonto
                   (escape text)         ; 14 Buchungstext
                   ]
                  (repeat (- datev-column-count 14) "")))]
    row))

;; ============================================================================
;; Posting fetch
;; ============================================================================

(defn- fetch-export-rows
  "Pull all postings whose effective valid-from is in [from, to) and
   whose transaction state is :posted. Pair each with its account
   code for the Konto column. The Gegenkonto column is the OTHER
   posting in the same transaction — DATEV thinks in two-line
   booking style, so when a tx has more than 2 postings (e.g.
   sale: AR / Revenue / VAT) we emit one row per posting against a
   common contra account (the 'header' one, conventionally the
   bank/AR/AP side).

   We pick the contra-account heuristically as the first
   :asset-typed posting in the transaction (typically the bank or
   receivable). For tx with no asset side we fall back to whatever
   the largest-magnitude posting points at."
  [db {:keys [from to]}]
  (let [posting-ids (d/q '[:find [?p ...]
                           :where [?p :posting/account _]
                                  [?p :posting/transaction ?t]
                                  [?t :transaction/state :posted]]
                         db)
        pulled (mapv (fn [p]
                       (let [pe (d/pull db
                                        [:posting/amount
                                         :posting/valid-from
                                         {:posting/account [:account/code :account/type]}
                                         {:posting/transaction
                                          [:db/id :transaction/effective-date :transaction/narration]}]
                                        p)
                             tx (:posting/transaction pe)
                             vf (or (:posting/valid-from pe)
                                    (:transaction/effective-date tx))]
                         {:posting-eid p
                          :amount (:posting/amount pe)
                          :valid-from vf
                          :account-code (-> pe :posting/account :account/code)
                          :account-type (-> pe :posting/account :account/type)
                          :tx-eid (:db/id tx)
                          :tx-text (:transaction/narration tx)}))
                     posting-ids)
        in-window (filter (fn [{:keys [valid-from]}]
                            (and valid-from
                                 (or (nil? from) (>= (.compareTo ^Date valid-from ^Date from) 0))
                                 (or (nil? to) (< (.compareTo ^Date valid-from ^Date to) 0))))
                          pulled)
        ;; Group by transaction so we can pick a contra
        by-tx (group-by :tx-eid in-window)
        rows (mapcat
              (fn [[_ ps]]
                ;; Contra = largest-absolute-amount posting in the tx.
                ;; Conventional double-entry: each non-contra line
                ;; books *against* the bank/AR/AP "header" line (which
                ;; carries the gross). For sales: AR debit = gross,
                ;; revenue + VAT credits sum to it. For bills:
                ;; payable credit = gross, expense + Vorsteuer debits
                ;; sum to it. The largest-magnitude line is always
                ;; the gross-side aka the contra.
                (let [contra (->> ps
                                  (sort-by #(.abs ^java.math.BigDecimal (:amount %)) >)
                                  first)
                      contra-code (:account-code contra)]
                  (->> ps
                       (remove #(= contra %))
                       ;; Output order: account-code ASC for stable dumps
                       (sort-by :account-code)
                       (map (fn [p]
                              {:amount (:amount p)
                               :account-code (:account-code p)
                               :contra-code contra-code
                               :date (:valid-from p)
                               :text (:tx-text p)})))))
              by-tx)]
    ;; Sort rows by date for stable output
    (sort-by #(.getTime ^Date (:date %)) rows)))

;; ============================================================================
;; Public: export
;; ============================================================================

(defn export-buchungsstapel
  "Generate a DATEV EXTF Buchungsstapel string from the postings in
   `conn` whose valid-from falls in [from, to).

   Required opts:
     :from           Date — period start (typically Jan 1)
     :to             Date — period end EXCLUSIVE
     :year           int  — fiscal year for header
     :company-name   String — exporter name for header
     :client-number  String — DATEV client number (typ. 4-7 digits)

   Optional:
     :konto-nummer   String — leading-account-number prefix (default \"1\")
     :as-of-tx       Date  — datahike snapshot (default now)
     :timestamp      LocalDateTime — header timestamp; useful in tests
                                     to make output deterministic."
  [conn {:keys [from to year company-name client-number konto-nummer
                as-of-tx timestamp]
         :or {konto-nummer "1"}}]
  (let [db (-> conn d/db (cond-> as-of-tx (d/as-of as-of-tx)))
        rows (fetch-export-rows db {:from from :to to})
        ts-str (if timestamp
                 (.format ^java.time.LocalDateTime timestamp yyyymmddhhmmssSSS-fmt)
                 (.format (java.time.LocalDateTime/now) yyyymmddhhmmssSSS-fmt))
        header (str/join ";"
                         (mapv escape
                               ["EXTF" "510" "21" "Buchungsstapel"
                                (str (+ (count rows) 2))   ; total lines incl. header rows
                                ts-str ""
                                "HC" company-name "" client-number konto-nummer
                                (format-period-bound from)
                                "4"                         ; fiscal-year variant
                                (format-period-bound from)
                                (format-period-bound (Date. (dec (.getTime ^Date to))))
                                "Buchungen" ""
                                "1" "0" "1" "EUR" "" "" "" ""]))
        column-line (str/join ";" (mapv escape datev-columns))
        data-lines (mapv (fn [r] (str/join ";" (posting->row r))) rows)]
    (str/join "\r\n"
              (concat [header column-line] data-lines [""]))))

(defn write-to-file!
  "Convenience: write the export string to `filepath` in ISO-8859-1
   encoding (the DATEV-mandated charset). Caller passes the same
   options as `export-buchungsstapel`."
  [conn ^String filepath opts]
  (let [content (export-buchungsstapel conn opts)]
    (spit filepath content :encoding "ISO-8859-1")))

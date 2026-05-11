(ns datahike-accounting.bank-csv
  "Generic bank-statement CSV parser.

   The kernel ships the engine; per-country `modules/bank-{cc}/`
   namespaces ship per-bank `bank-configs` + per-country
   categorizers. The split keeps the parser code in one place
   (debugged once, reused everywhere) while letting each country
   add a new bank format in ~10 LOC.

   Bank configurations carry:
     :encoding         — \"UTF-8\" / \"ISO-8859-1\" / \"UTF-16LE\"
     :skip-rows        — fallback header-row offset if the
                         keyword-driven auto-detect misses
     :date-format      — Java DateTimeFormatter pattern, e.g.
                         \"dd.MM.yy\", \"dd/MM/yyyy\", \"yyyy-MM-dd\"
     :separator        — \\;, \\,, \\\\t
     :amount-style     — :german (1.234,56)
                         | :english (1,234.56)
                         | :split-debit-credit (separate debit + credit
                           columns; one is blank per row)
     :col-indexes      — map keyword → 0-based column index. Required
                         keys: :date :amount (or :debit + :credit when
                         :amount-style :split-debit-credit). Optional:
                         :value-date :counterparty :recipient
                         :description :type :iban :currency :info

   `parse-statement` runs the parse and returns a vector of
   candidate maps with structured fields + the raw row. Caller-
   supplied `categorize-fn` (defaults to identity) tags each
   candidate with a category keyword for downstream contra-account
   resolution.

   See modules/bank-de/parser.clj for the reference set of bank
   configs and the openclaw-derived categorizer."
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Calendar Date TimeZone]))

;; ============================================================================
;; Date + amount parsing
;; ============================================================================

(def ^:private utc (TimeZone/getTimeZone "UTC"))

(defn parse-date
  "Parse `s` per `fmt` into a UTC-midnight java.util.Date.
   Returns nil on failure rather than throwing."
  ^Date [s ^String fmt]
  (when (and s (not (str/blank? s)))
    (try
      (let [ld (LocalDate/parse (str/trim s) (DateTimeFormatter/ofPattern fmt))
            cal (doto (Calendar/getInstance utc)
                  (.clear)
                  (.set (.getYear ld)
                        (dec (.getMonthValue ld))
                        (.getDayOfMonth ld)))]
        (.getTime cal))
      (catch Exception _ nil))))

(defn parse-german-amount
  "Parse German-style number: '1.234,56' / '-62,3' / '(123,45)' /
   '€ 100,00'. Returns BigDecimal; 0M for blank/garbage."
  ^java.math.BigDecimal [s]
  (if (or (nil? s) (str/blank? s))
    0M
    (let [t (-> s str/trim
                (str/replace "€" "")
                (str/replace "EUR" "")
                (str/replace "\"" "")
                str/trim)
          negative? (or (str/starts-with? t "-")
                        (str/starts-with? t "("))
          stripped (cond
                     (str/starts-with? t "-") (subs t 1)
                     (str/starts-with? t "(") (subs t 1 (dec (count t)))
                     :else t)
          normalized (-> stripped
                         (str/replace "." "")
                         (str/replace "," "."))]
      (try
        (let [v (java.math.BigDecimal. ^String (str/trim normalized))]
          (if negative? (.negate v) v))
        (catch NumberFormatException _ 0M)))))

(defn parse-english-amount
  "Parse English-style: '1,234.56' / '-42.7' / '(123.45)' /
   '$100.00' / 'CAD 99.95'. Returns BigDecimal; 0M for garbage."
  ^java.math.BigDecimal [s]
  (if (or (nil? s) (str/blank? s))
    0M
    (let [t (-> s str/trim
                (str/replace #"^\$|\bUSD\b|\bCAD\b|\bAUD\b|\bGBP\b" "")
                (str/replace "\"" "")
                str/trim)
          negative? (or (str/starts-with? t "-")
                        (str/starts-with? t "("))
          stripped (cond
                     (str/starts-with? t "-") (subs t 1)
                     (str/starts-with? t "(") (subs t 1 (dec (count t)))
                     :else t)
          normalized (str/replace stripped "," "")]
      (try
        (let [v (java.math.BigDecimal. ^String (str/trim normalized))]
          (if negative? (.negate v) v))
        (catch NumberFormatException _ 0M)))))

(defn- pull-amount
  "Resolve the signed amount from a row per the config's amount style.
     :german / :english       — one column carries a signed value
     :split-debit-credit      — separate :debit + :credit columns; one
                                blank per row; the parser combines
                                them, treating debit as positive
                                inflow and credit as negative outflow
                                (matching most CA / FR retail-bank
                                conventions).

   Returns BigDecimal."
  ^java.math.BigDecimal [row {:keys [amount-style col-indexes] :as _config}]
  (let [parser (case amount-style
                 :english parse-english-amount
                 :split-debit-credit parse-english-amount   ;; numeric format
                 parse-german-amount)]
    (case amount-style
      :split-debit-credit
      (let [debit  (parser (get row (:debit col-indexes)))
            credit (parser (get row (:credit col-indexes)))
            ;; Convention: debit-side appears as a POSITIVE inflow on
            ;; bank statements (it's money OUT of the account in
            ;; bookkeeping but we surface the bank's POV here);
            ;; credit-side is NEGATIVE. Most CA/FR bank exports follow
            ;; this. If a particular bank inverts, swap in the config
            ;; via :debit-sign / :credit-sign overrides (TODO).
            ;; For now: debit positive, credit negated.
            ]
        (cond-> (.subtract debit credit)
          ;; (already signed: inflow positive, outflow negative)
          ))
      ;; Default: signed-amount in one column
      (parser (get row (:amount col-indexes))))))

;; ============================================================================
;; Header auto-detection
;; ============================================================================

(def ^:private default-header-keywords
  "Keywords to look for in the header row across multiple languages.
   Auto-detection: scan rows top-to-bottom; the first row that
   contains any of these is the header."
  #{;; DE
    "buchung" "betrag" "datum" "valuta" "umsatzart"
    "auftraggeber" "empfaenger" "verwendungszweck" "guthaben"
    ;; EN
    "date" "amount" "description" "balance" "transaction"
    "posting" "details" "type"
    ;; FR
    "libell" "débit" "crédit" "valeur" "opération" "montant"
    ;; IT (preview for AT/CH)
    "data" "operazione"})

(defn- header-row-index
  "Resolve the header row index. With `:no-header? true` we bypass
   keyword auto-detection and return -1 so `(drop (inc -1) rows)` =
   keep every row (used for Wells Fargo + similar headerless exports)."
  [rows {:keys [skip-rows header-keywords no-header?]
         :or {header-keywords default-header-keywords}}]
  (cond
    no-header? -1
    :else (or (first
               (for [[i row] (map-indexed vector rows)
                     :let [text (str/lower-case (str/join " " row))]
                     :when (some #(str/includes? text %) header-keywords)]
                 i))
              skip-rows)))

;; ============================================================================
;; Public: parse-statement
;; ============================================================================

(defn read-csv
  "Read the file with the configured encoding, strip BOM, parse
   semicolon/comma per config. Returns the raw rows."
  [^java.io.File file {:keys [encoding separator]}]
  (let [raw (slurp file :encoding (or encoding "UTF-8"))
        content (if (str/starts-with? raw "﻿") (subs raw 1) raw)]
    (vec (csv/read-csv (java.io.StringReader. content)
                       :separator separator))))

(defn- row->candidate
  "Build the canonical candidate map for one row."
  [bank config row]
  (let [idx       (:col-indexes config)
        amount    (pull-amount row config)
        date      (parse-date (get row (:date idx)) (:date-format config))
        value     (parse-date (or (get row (:value-date idx))
                                  (get row (:date idx)))
                              (:date-format config))]
    (when date
      {:bank             bank
       :date             date
       :value-date       value
       :amount           amount
       :counterparty     (str/trim (or (get row (:counterparty idx))
                                       (get row (:recipient idx)) ""))
       :description      (str/trim (str (or (get row (:description idx))
                                            (get row (:libelle idx)) "")))
       :counterparty-iban (str/trim (str (get row (:iban idx) "")))
       :transaction-type (str/trim (str (get row (:type idx) "")))
       :raw-row          row})))

(defn parse-statement-with-config
  "Parse `path` using a known bank-config map. The kernel-level
   alternative when a per-country namespace can hand you the config
   directly (skipping the auto-detect step).

   `categorize-fn` is an optional 1-arg fn that takes a candidate
   and returns it (typically with `:category` added). Defaults to
   identity."
  ([path bank config]
   (parse-statement-with-config path bank config identity))
  ([path bank config categorize-fn]
   (let [file (java.io.File. ^String path)
         rows (read-csv file config)
         hdr (header-row-index rows config)
         data-rows (drop (inc hdr) rows)]
     (->> data-rows
          ;; Keep any row that has SOMETHING non-blank — some bank
          ;; exports use a permanently-blank leading column (e.g.
          ;; Scotiabank's 'Filter') so checking only `(first row)`
          ;; would drop every data row.
          (filter (fn [row] (and (seq row) (some (complement str/blank?) row))))
          (keep #(try (some-> (row->candidate bank config %)
                              categorize-fn)
                      (catch Exception _ nil)))
          vec))))

(defn parse-statement
  "Detect-and-parse: take a `bank-configs` map (keyword → config), a
   `detect-bank` fn (filename + content-preview → bank-keyword), an
   optional `categorize-fn`, and a path. Returns candidate vec.

   This is the same shape as bank-de's `parse-statement` but
   parameterized — country modules just supply their map + detector."
  [{:keys [bank-configs detect-bank-fn categorize-fn]
    :or {categorize-fn identity}}
   path]
  (let [file     (java.io.File. ^String path)
        filename (.getName file)
        sniff    (try (slurp file :encoding "UTF-8") (catch Exception _ ""))
        bank     (detect-bank-fn filename sniff)
        _ (when-not bank
            (throw (ex-info "Unrecognized bank format"
                            {:type :bank-csv/unknown-format
                             :file path :filename filename})))
        config (get bank-configs bank)
        _ (when-not config
            (throw (ex-info "No config for bank"
                            {:type :bank-csv/no-config :bank bank})))]
    (parse-statement-with-config path bank config categorize-fn)))

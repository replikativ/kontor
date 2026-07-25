(ns kontor.banking.bank-csv
  "Generic bank-statement CSV parser.

   The kernel ships the engine; per-country `modules/bank-{cc}/`
   namespaces ship per-bank `bank-configs` + per-country
   categorizers. The split keeps the parser code in one place
   (debugged once, reused everywhere) while letting each country
   add a new bank format in ~10 LOC.

   THE AMOUNT CONTRACT (ADR-131). `:amount` on every candidate is
   signed from the ACCOUNT HOLDER's point of view: **positive = money
   in**. Banks disagree wildly about how they encode that, so every
   layout DECLARES its convention; nothing is defaulted, because a
   defaulted convention is what shipped a 100x misparse and two sign
   inversions past `(is (>= ratio 0.5))`.

   Bank configurations carry:
     :encoding         — \"UTF-8\" / \"ISO-8859-1\" / \"UTF-16LE\"
     :skip-rows        — fallback header-row offset if the
                         keyword-driven auto-detect misses
     :no-header?       — bypass keyword auto-detect; every row is data
     :date-format      — Java DateTimeFormatter pattern, e.g.
                         \"dd.MM.yy\", \"dd/MM/yyyy\", \"yyyy-MM-dd\"
     :separator        — \\;, \\,, \\\\t
     :amount-style     — :german (1.234,56)
                         | :english (1,234.56)
                         | :split-debit-credit (separate debit + credit
                           columns; one is blank per row)
     :number-format    — :german | :english. The DECIMAL convention,
                         which is a different question from the column
                         layout. Derived from :amount-style for the two
                         single-column styles; **required** for
                         :split-debit-credit, which names a column
                         layout and says nothing about numerals.
     :debit-sign       — -1 | 1, **required** for :split-debit-credit
     :credit-sign      — -1 | 1, **required** for :split-debit-credit
                         Each column's parsed magnitude is multiplied by
                         its sign and the two products added. The retail
                         deposit-account pair is `-1 / 1` — the debit
                         column is money LEAVING. A bank that already
                         writes its debit column signed (Targobank) uses
                         `1 / 1`.
     :amount-sign      — -1 | 1, default 1, for the single-column
                         styles. `-1` normalises an ISSUER-side layout
                         (AmEx writes a card charge POSITIVE because it
                         increases what you owe).
     :col-indexes      — map keyword → column index. Required keys:
                         :date :amount (or :debit + :credit when
                         :amount-style :split-debit-credit). Optional:
                         :value-date :counterparty :recipient
                         :description :type :iban :currency :info
                         :balance.
                         **A NEGATIVE index counts from the END of the
                         row** — `-1` is the last field. Tail anchoring
                         is how a config declares which columns the
                         layout pins to the end of the line, which is
                         the only non-guessing answer to a ragged export
                         (ING omits a field entirely on one row shape).
                         The engine never right-shifts on its own.

   `parse-statement` runs the parse and returns a vector of
   candidate maps with structured fields + the raw row. Caller-
   supplied `categorize-fn` (defaults to identity) tags each
   candidate with a category keyword for downstream contra-account
   resolution.

   When the layout declares a `:balance` column the parsed running
   balance rides along on the candidate (nil — not 0M — when the layout
   has none or the cell is blank). That is what lets
   `kontor.banking.statement-tie-out` check a parse against the bank's
   own arithmetic instead of against a row count.

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

;; ============================================================================
;; Column access: negative indexes are TAIL-anchored (ADR-131)
;; ============================================================================

(defn cell
  "Read column `i` of `row`. A NEGATIVE `i` counts from the end of the
   row: -1 is the last field, -2 the second-to-last. Returns nil when
   `i` is nil or out of range.

   Tail anchoring is a config author's declaration that the layout pins
   this column to the END of the line. It is the answer to a ragged
   export — a bank that omits an optional field on some row shapes — and
   it is a DECLARATION, not a guess: the engine never re-aligns a row on
   its own."
  [row i]
  (when (and i (seq row))
    (let [n (count row)
          j (if (neg? i) (+ n i) i)]
      (when (and (>= j 0) (< j n))
        (nth row j)))))

;; ============================================================================
;; Config validation (ADR-131)
;; ============================================================================

(def amount-styles #{:german :english :split-debit-credit})
(def number-formats #{:german :english})
(def signs #{-1 1})

(defn number-format
  "The DECIMAL convention for `config`. `:number-format` is
   authoritative when present; otherwise it is derived from the
   single-column `:amount-style`. Returns nil for
   `:amount-style :split-debit-credit` without an explicit
   `:number-format` — a column layout says nothing about numerals, and
   defaulting it is exactly the defect that made every Crédit Agricole
   amount 100x too large."
  [{:keys [number-format amount-style]}]
  (or number-format (number-formats amount-style)))

(defn validate-config
  "Return a vector of human-readable problems with `config`, empty when
   it is well-formed. Pure — see `validate-config!` for the throwing
   variant."
  [{:keys [amount-style col-indexes amount-sign debit-sign credit-sign] :as config}]
  (let [nf (number-format config)]
    (cond-> []
      (not (map? col-indexes))
      (conj ":col-indexes must be a map of keyword → column index")

      (and (map? col-indexes) (nil? (:date col-indexes)))
      (conj ":col-indexes is missing the required :date column")

      (not (amount-styles amount-style))
      (conj (str ":amount-style must be one of " (pr-str amount-styles)
                 " — got " (pr-str amount-style)))

      (and (:number-format config) (not (number-formats (:number-format config))))
      (conj (str ":number-format must be one of " (pr-str number-formats)
                 " — got " (pr-str (:number-format config))))

      (and (= :split-debit-credit amount-style) (nil? nf))
      (conj (str ":amount-style :split-debit-credit requires an explicit "
                 ":number-format — the column layout does not imply the "
                 "decimal convention"))

      (and (= :split-debit-credit amount-style) (map? col-indexes)
           (or (nil? (:debit col-indexes)) (nil? (:credit col-indexes))))
      (conj ":amount-style :split-debit-credit requires both :debit and :credit in :col-indexes")

      (and (= :split-debit-credit amount-style) (not (signs debit-sign)))
      (conj (str ":amount-style :split-debit-credit requires :debit-sign ∈ #{-1 1} "
                 "— got " (pr-str debit-sign)
                 ". A retail deposit account is -1 (the debit column is money LEAVING)."))

      (and (= :split-debit-credit amount-style) (not (signs credit-sign)))
      (conj (str ":amount-style :split-debit-credit requires :credit-sign ∈ #{-1 1} "
                 "— got " (pr-str credit-sign)))

      (and (not= :split-debit-credit amount-style) (map? col-indexes)
           (nil? (:amount col-indexes)))
      (conj (str ":amount-style " (pr-str amount-style)
                 " requires :amount in :col-indexes"))

      (and (some? amount-sign) (not (signs amount-sign)))
      (conj (str ":amount-sign must be -1 or 1 — got " (pr-str amount-sign))))))

(defn validate-config!
  "Throw `:bank-csv/invalid-config` when `config` is malformed; return
   `config` otherwise.

   Called ONCE per parse, BEFORE the row loop. That placement is
   load-bearing: `parse-statement-with-config` wraps `row->candidate` in
   a catch-all, so a throw raised from inside the loop would silently
   drop EVERY transaction and hand the caller an empty statement instead
   of an error."
  [config]
  (let [problems (validate-config config)]
    (when (seq problems)
      (throw (ex-info (str "Invalid bank config: " (str/join "; " problems))
                      {:type :bank-csv/invalid-config
                       :problems problems
                       :config config})))
    config))

;; ============================================================================
;; Amount resolution
;; ============================================================================

(defn- amount-parser
  [config]
  (if (= :german (number-format config)) parse-german-amount parse-english-amount))

(defn- signed
  ^java.math.BigDecimal [^java.math.BigDecimal v sign]
  (if (neg? (long sign)) (.negate v) v))

(defn- pull-amount
  "Resolve the account-holder-signed amount from a row (positive = money
   in) per the config's declared conventions.

     :german / :english     — one column carries a signed value; the
                              magnitude is multiplied by :amount-sign
                              (default 1) so an issuer-side export can
                              be normalised without a bespoke parser.
     :split-debit-credit    — separate :debit + :credit columns, one
                              blank per row. Each column's parsed value
                              is multiplied by its declared sign and the
                              products are added. No default: see
                              `validate-config`.

   Returns BigDecimal."
  ^java.math.BigDecimal [row {:keys [amount-style col-indexes debit-sign credit-sign
                                     amount-sign] :as config}]
  (let [parser (amount-parser config)]
    (if (= :split-debit-credit amount-style)
      (.add (signed (parser (cell row (:debit col-indexes))) debit-sign)
            (signed (parser (cell row (:credit col-indexes))) credit-sign))
      (signed (parser (cell row (:amount col-indexes))) (or amount-sign 1)))))

(defn- pull-balance
  "The layout's running-balance cell, or nil when the layout declares no
   `:balance` column or the cell is blank. Deliberately nil rather than
   0M — 0M is a legitimate balance and would make an absent column
   indistinguishable from an account at zero."
  [row {:keys [col-indexes] :as config}]
  (let [raw (cell row (:balance col-indexes))]
    (when-not (str/blank? raw)
      ((amount-parser config) raw))))

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
        date      (parse-date (cell row (:date idx)) (:date-format config))
        value     (parse-date (or (cell row (:value-date idx))
                                  (cell row (:date idx)))
                              (:date-format config))]
    (when date
      {:bank             bank
       :date             date
       :value-date       value
       :amount           amount
       :balance          (pull-balance row config)
       :counterparty     (str/trim (or (cell row (:counterparty idx))
                                       (cell row (:recipient idx)) ""))
       :description      (str/trim (str (or (cell row (:description idx))
                                            (cell row (:libelle idx)) "")))
       :counterparty-iban (str/trim (str (or (cell row (:iban idx)) "")))
       :transaction-type (str/trim (str (or (cell row (:type idx)) "")))
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
   ;; ONCE, before the row loop — the `keep`/`catch` below swallows
   ;; per-row exceptions, so a config error raised inside the loop would
   ;; return an empty statement instead of an error. ADR-131.
   (validate-config! config)
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

(ns datahike-accounting.bank-de.parser
  "Bank-statement CSV importers for German banks. Configuration +
   pure parsing only — emits posting candidates as plain maps. Caller
   wraps them in a `posting/build-transaction` to land them in the
   accounting kernel.

   Lifted from openclaw `beleg/bank.clj` (609 LOC) per research note
   06; stripped of the datahike write side and rebased on
   `datahike-accounting.money` for amount handling.

   Supported formats (11):
     :dkb                 — DKB Girokonto (semicolon, dd.MM.yy)
     :ing                 — ING Girokonto (semicolon, dd.MM.yyyy)
     :commerzbank         — Commerzbank Kontoumsatz
     :postbank            — Postbank Kontoumsatz
     :paypal              — PayPal Activity export
     :sparkasse-camt      — Sparkasse CAMT v2 / v8
     :sparkasse-mt940     — Sparkasse MT940
     :targobank           — Targobank (no header)
     :gls-bank            — GLS Bank (18-column SEPA-rich)
     :sparda-bank-west    — Sparda West (same shape as GLS)
     :vr-bank             — VR / VR Teilhaberbank (same shape as GLS)

   Auto-detect via filename or header content (`detect-bank`).

   See `test/resources/FORMATS.md` for field-by-field documentation
   of each bank's CSV shape (lifted from RechnungsFee/vorlagen)."
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str]
            [datahike-accounting.money :as money])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Date Calendar TimeZone]))

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  "Per-bank format configuration. Column indices are 0-based positions
   in the data rows after `skip-rows` rows of header are dropped."
  {:dkb {:encoding "UTF-8"
         :skip-rows 4
         :date-format "dd.MM.yy"
         :separator \;
         :col-indexes {:date 0 :value-date 1 :counterparty 3 :recipient 4
                       :description 5 :type 6 :iban 7 :amount 8}}

   :ing {:encoding "ISO-8859-1"
         :skip-rows 12
         :date-format "dd.MM.yyyy"
         :separator \;
         :col-indexes {:date 0 :value-date 1 :counterparty 2
                       :description 4 :type 3 :amount 5}}

   :commerzbank {:encoding "UTF-8"
                 :skip-rows 1
                 :date-format "dd.MM.yyyy"
                 :separator \;
                 :col-indexes {:date 0 :value-date 1 :type 2 :description 3
                               :amount 4 :currency 5 :iban 6 :category 7}}

   ;; Postbank — Umsatzübersicht. The actual export has 18 columns
   ;; (Buchungstag / Wert / Umsatzart / Begünstigter / Verwendungszweck
   ;; / IBAN / BIC / Kundenref / Mandatsref / Gläubiger-ID /
   ;; Fremde Gebühren / Betrag / Abw. Empfänger / # Aufträge /
   ;; # Schecks / Soll / Haben / Währung). The openclaw beleg config
   ;; was off (assumed :amount=4 — that's actually Verwendungszweck);
   ;; corrected here against the canonical fixture shape.
   :postbank {:encoding "UTF-8"
              :skip-rows 8
              :date-format "d.M.yyyy"
              :separator \;
              :col-indexes {:date 0 :value-date 1 :type 2 :counterparty 3
                            :description 4 :iban 5 :amount 11 :currency 17}}

   :paypal {:encoding "UTF-8"
            :skip-rows 1
            :date-format "dd.MM.yyyy"
            :separator \,
            :col-indexes {:date 0 :time 1 :timezone 2 :counterparty 3 :type 4
                          :status 5 :currency 6 :gross 7 :fee 8 :amount 9
                          :sender-email 10 :receiver-email 11 :tx-id 12}}

   ;; Sparkasse CAMT v2/v8 — 17 columns starting with Auftragskonto.
   ;; The openclaw config was off-by-one (didn't include Auftragskonto
   ;; as col 0); corrected against the canonical anonymized fixture.
   :sparkasse-camt {:encoding "UTF-8"
                    :skip-rows 1
                    :date-format "dd.MM.yy"
                    :separator \;
                    :col-indexes {:auftragskonto 0 :date 1 :value-date 2
                                  :type 3 :description 4 :creditor-id 5
                                  :mandate-ref 6 :e2e-ref 7 :collector-ref 8
                                  :original-amount 9 :returnee 10
                                  :counterparty 11 :iban 12 :bic 13 :amount 14
                                  :currency 15 :info 16}}

   ;; Sparkasse MT940 — 11 columns:
   ;;   Auftragskonto / Buchungstag / Valutadatum / Buchungstext /
   ;;   Verwendungszweck / Beguenstigter / Kontonummer / BLZ /
   ;;   Betrag / Waehrung / Info
   :sparkasse-mt940 {:encoding "UTF-8"
                     :skip-rows 1
                     :date-format "dd.MM.yy"
                     :separator \;
                     :col-indexes {:auftragskonto 0 :date 1 :value-date 2
                                   :type 3 :description 4 :counterparty 5
                                   :iban 6 :bic 7 :amount 8 :currency 9
                                   :info 10}}

   :targobank {:encoding "UTF-8"
               :skip-rows 0
               :date-format "dd.MM.yyyy"
               :separator \;
               :col-indexes {:date 0 :type 1 :counterparty 1 :description 1
                             :amount 2 :currency 5}}

   :gls-bank {:encoding "UTF-8"
              :skip-rows 1
              :date-format "dd.MM.yyyy"
              :separator \;
              :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                            :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                            :counterparty-bic 8 :type 9 :description 10 :amount 11
                            :currency 12 :balance 13 :remark 14 :marked 15
                            :creditor-id 16 :mandate-ref 17}}

   :sparda-bank-west {:encoding "UTF-8"
                      :skip-rows 1
                      :date-format "dd.MM.yyyy"
                      :separator \;
                      :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                                    :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                                    :counterparty-bic 8 :type 9 :description 10 :amount 11
                                    :currency 12 :balance 13 :remark 14 :marked 15
                                    :creditor-id 16 :mandate-ref 17}}

   :vr-bank {:encoding "UTF-8"
             :skip-rows 1
             :date-format "dd.MM.yyyy"
             :separator \;
             :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                           :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                           :counterparty-bic 8 :type 9 :description 10 :amount 11
                           :currency 12 :balance 13 :remark 14 :marked 15
                           :creditor-id 16 :mandate-ref 17}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
  "Detect bank type from filename or content preview.
   Filename heuristics first (most reliable), then header content."
  [filename content-preview]
  (let [lower (str/lower-case (str filename))
        preview (str content-preview)]
    (cond
      (str/includes? lower "dkb")          :dkb
      (str/includes? lower "ing")          :ing
      (str/includes? lower "commerzbank")  :commerzbank
      (str/includes? lower "postbank")     :postbank
      (str/includes? lower "paypal")       :paypal
      (str/includes? lower "sparkasse")    (if (str/includes? lower "mt940")
                                             :sparkasse-mt940
                                             :sparkasse-camt)
      (str/includes? lower "targobank")    :targobank
      (str/includes? lower "gls")          :gls-bank
      (str/includes? lower "sparda")       :sparda-bank-west
      (str/includes? lower "vr-")          :vr-bank
      (str/includes? lower "vr")           :vr-bank
      ;; Header-content fallbacks
      (str/includes? preview "Girokonto")          :dkb
      (str/includes? preview "Umsatzanzeige")      :ing
      (str/includes? preview "Buchungstag;Wertstellung;Umsatzart") :commerzbank
      (str/includes? preview "Bezeichnung Auftragskonto;IBAN Auftragskonto;BIC") :gls-bank
      (str/includes? preview "Auftragskonto;Buchungstag")          :sparkasse-camt
      (str/includes? preview "Umsätze")            :postbank
      (str/includes? preview "Datum,Uhrzeit")      :paypal
      :else nil)))

;; ============================================================================
;; Parsing utilities
;; ============================================================================

(defn parse-german-amount
  "Parse German number format. Examples:
     \"1.234,56\"  → 1234.56M
     \"-62,3\"     → -62.3M
     \"(123,45)\"  → -123.45M  (parens = negative, accounting style)
     \"€ 100,00\"  → 100.00M
   Returns BigDecimal so it can be wrapped in `money/money` later
   without precision loss. Returns 0M for nil/blank/garbage (the
   caller should usually filter these before posting)."
  ^java.math.BigDecimal [s]
  (if (or (nil? s) (str/blank? s))
    0M
    (let [t (-> s
                str/trim
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
          ;; German: 1.234,56 → 1234.56 (drop thousands-dots, swap decimal)
          normalized (-> stripped
                         (str/replace "." "")
                         (str/replace "," "."))]
      (try
        (let [v (java.math.BigDecimal. ^String (str/trim normalized))]
          (if negative? (.negate v) v))
        (catch NumberFormatException _ 0M)))))

(def ^:private utc (TimeZone/getTimeZone "UTC"))

(defn parse-date
  "Parse a date string per the bank's :date-format and return a
   java.util.Date at UTC midnight (datahike-friendly). Returns nil
   on failure rather than throwing — caller filters."
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

;; ============================================================================
;; Categorization
;; ============================================================================

(def category-patterns
  "Regex patterns for auto-categorizing transactions by counterparty +
   description text. Keys are SKR-04-style category buckets; the
   l10n-de module maps them onto the actual Konto numbers."
  {:einnahmen        [#"(?i)RECHNUNG" #"(?i)RG-" #"(?i)RE-\d" #"(?i)LEISTUNG"
                      #"(?i)AUFTRAG" #"(?i)HONORAR" #"(?i)PROVISION"]
   :gehalt           [#"(?i)GEHALT" #"(?i)LOHN" #"(?i)ENTGELT"]
   :buero            [#"(?i)PAPER" #"(?i)PENDEL" #"(?i)STAPLES" #"(?i)BUERO"
                      #"(?i)AMAZON.*BUERO" #"(?i)NOTIZBUCH"]
   :telekommunikation [#"(?i)TELEKOM" #"(?i)VODAFONE" #"(?i)O2"
                       #"(?i)TELEFON" #"(?i)INTERNET" #"(?i)DSL"]
   :software         [#"(?i)ADOBE" #"(?i)MICROSOFT" #"(?i)GOOGLE.*CLOUD"
                      #"(?i)AWS" #"(?i)DROPBOX" #"(?i)GITHUB" #"(?i)SPOTIFY.*BUSINESS"
                      #"(?i)OPENAI" #"(?i)CHATGPT"]
   :werbung          [#"(?i)GOOGLE.?ADS" #"(?i)FACEBOOK.?ADS" #"(?i)LINKEDIN"
                      #"(?i)WERBUNG" #"(?i)MARKETING"]
   :reisekosten      [#"(?i)DEUTSCHE.?BAHN" #"(?i)DB.?BAHN" #"(?i)FLUG"
                      #"(?i)AIRLINE" #"(?i)REISE" #"(?i)HOTEL" #"(?i)UBER"]
   :bewirtung        [#"(?i)RESTAURANT" #"(?i)CAFE" #"(?i)BAR" #"(?i)LIEFERANDO"]
   :fahrzeuge        [#"(?i)TANK" #"(?i)SHELL" #"(?i)ARAL" #"(?i)ESSO" #"(?i)BP"
                      #"(?i)TANKSTELLE" #"(?i)KFZ"]
   :miete            [#"(?i)MIETE" #"(?i)RENT" #"(?i)STELLPLATZ"]
   :nebenkosten      [#"(?i)NEBENKOSTEN" #"(?i)HEIZUNG" #"(?i)WASSER"
                      #"(?i)STROM" #"(?i)GAS" #"(?i)ELEKTRIZIT"]
   :versicherung     [#"(?i)VERSICHERUNG" #"(?i)AXA" #"(?i)ALLIANZ" #"(?i)HUK"]
   :steuerberater    [#"(?i)STEUERBERATER" #"(?i)TAX" #"(?i)FINANZAMT"]
   :beitraege        [#"(?i)BEITRAG" #"(?i)AOK" #"(?i)TK" #"(?i)KRANKENKASSE"
                      #"(?i)HANDELSKAMMER" #"(?i)IHK"]})

(defn categorize-transaction
  "Auto-categorize a parsed transaction. Returns the row with
   `:category` set. Falls back to :einnahmen for credit / :sonstige-
   betriebsausgaben for debit when no pattern matches."
  [tx]
  (let [text   (str/upper-case (str (:counterparty tx) " " (:description tx)))
        amount (or (:amount tx) 0M)
        base   (if (>= (.signum ^java.math.BigDecimal amount) 0)
                 :einnahmen :sonstige-betriebsausgaben)
        match (reduce
               (fn [_ [cat patterns]]
                 (when (some #(re-find % text) patterns)
                   (reduced cat)))
               nil
               category-patterns)]
    (assoc tx :category (or match base))))

(defn suggest-vat-rate
  "VAT rate (as a percent — 19, 7, 0) for a category. Caller may
   override at posting time. Defaults to 19% for unknown categories."
  [category]
  (cond
    (#{:gehalt :versicherung :steuerberater :beitraege} category) 0
    :else 19))

;; ============================================================================
;; Public: parse-statement
;; ============================================================================

(defn- read-csv-content
  "Read the file's bytes per the bank's encoding, strip any leading
   UTF-8 BOM, return the text + parsed CSV rows."
  [^java.io.File file {:keys [encoding separator]}]
  (let [raw (slurp file :encoding (or encoding "UTF-8"))
        content (if (str/starts-with? raw "﻿") (subs raw 1) raw)
        rows (vec (csv/read-csv (java.io.StringReader. content)
                                :separator separator))]
    [content rows]))

(defn- header-row-index
  "Auto-detect the header row by scanning for known German banking
   column-name keywords. Falls back to `:skip-rows` from the config."
  [rows {:keys [skip-rows]}]
  (let [keywords #{"buchung" "betrag" "datum" "valuta" "umsatzart"
                   "auftraggeber" "empfaenger" "verwendungszweck"
                   "beschreibung" "guthaben" "umsatz" "transaction"}]
    (or (first
         (for [[i row] (map-indexed vector rows)
               :let [text (str/lower-case (str/join " " row))]
               :when (some #(str/includes? text %) keywords)]
           i))
        skip-rows)))

(defn- pull-amount
  [row idx config bank]
  (let [amt-str   (get row (:amount idx))
        type-str  (str (get row (:type idx) ""))
        raw-amt   (parse-german-amount amt-str)
        ;; DKB-specific: their CSV uses 'Eingang'/'Ausgang' for
        ;; +/- and writes the amount as a positive number.
        sign      (if (and (= :dkb bank)
                           (str/includes? type-str "Ausgang"))
                    -1 1)]
    (cond-> raw-amt
      (and (= :dkb bank) (= sign -1))
      (.negate))))

(defn- row->candidate
  "Extract one parsed row → posting-candidate map."
  [bank config row]
  (let [idx       (:col-indexes config)
        amount    (pull-amount row idx config bank)
        date      (parse-date (get row (:date idx)) (:date-format config))
        value     (parse-date (or (get row (:value-date idx))
                                  (get row (:date idx)))
                              (:date-format config))]
    (when date
      (categorize-transaction
       {:bank             bank
        :date             date
        :value-date       value
        :amount           amount
        :counterparty     (str/trim (or (get row (:counterparty idx))
                                        (get row (:recipient idx)) ""))
        :description      (str/trim (str (get row (:description idx) "")))
        :counterparty-iban (str/trim (str (get row (:iban idx) "")))
        :transaction-type (str/trim (str (get row (:type idx) "")))
        :raw-row          row}))))

(defn parse-statement
  "Parse a bank-statement CSV file → seq of candidate maps.

   Each candidate has:
     :bank             keyword (e.g. :dkb)
     :date             java.util.Date at UTC midnight
     :value-date       java.util.Date or nil
     :amount           BigDecimal (signed; positive = inflow,
                       negative = outflow)
     :counterparty     trimmed counterparty / recipient name
     :description      trimmed transaction description
     :counterparty-iban (when present)
     :transaction-type bank-specific type code
     :category         autocategorized SKR-bucket keyword
     :raw-row          original CSV row vector

   Caller wraps each in a `:posting/*` entity-map and runs through
   `posting/build-transaction`. The kernel does NOT auto-post —
   bank import always produces *suggestions* that need human review.

   Options:
     :bank            — explicit bank kw, skips detection
     :file-name       — when calling on a Reader instead of a File,
                        used for detection."
  ([^String path] (parse-statement path {}))
  ([^String path {:keys [bank file-name]}]
   (let [file (java.io.File. path)
         filename (or file-name (.getName file))
         ;; Tentative read with default UTF-8 just to sniff the bank
         sniff-content (try (slurp file :encoding "UTF-8") (catch Exception _ ""))
         resolved-bank (or bank (detect-bank filename sniff-content))
         _ (when-not resolved-bank
             (throw (ex-info "Unrecognized bank format"
                             {:type :bank-de/unknown-format
                              :file path :filename filename})))
         config (get bank-configs resolved-bank)
         _ (when-not config
             (throw (ex-info "No config for bank"
                             {:type :bank-de/no-config
                              :bank resolved-bank})))
         [content rows] (read-csv-content file config)
         hdr-idx (header-row-index rows config)
         data-rows (drop (inc hdr-idx) rows)]
     (->> data-rows
          (filter (fn [row] (and (seq row) (not (str/blank? (first row))))))
          (keep #(try (row->candidate resolved-bank config %)
                      (catch Exception _ nil)))
          vec))))

;; ============================================================================
;; Posting projection
;; ============================================================================

(defn candidate->posting-pair
  "Project a parsed candidate into a balanced posting pair (the bank-
   account side + a contra-account side based on the auto-category).

   Caller supplies:
     :bank-account-eid  — :db/id of the :account entity for this bank
     :commodity-eid     — :db/id of the EUR (or other) commodity
     :journal-eid       — :db/id of the bank journal
     :contra-resolver   — fn (category) → contra-account-eid

   Returns a `posting/build-transaction`-ready input map. The bank
   side debits when the candidate amount is positive (inflow), credits
   when negative."
  [candidate {:keys [bank-account-eid commodity-eid journal-eid contra-resolver]}]
  (let [amt (money/money (:amount candidate) commodity-eid)
        contra (contra-resolver (:category candidate))]
    {:transaction
     {:transaction/external-id   (str (:bank candidate) "/"
                                      (.getTime ^Date (:date candidate))
                                      "/" (hash (:raw-row candidate)))
      :transaction/journal       journal-eid
      :transaction/effective-date (:date candidate)
      :transaction/narration     (str (:counterparty candidate)
                                      " — " (:description candidate))
      :transaction/state         :draft}      ;; suggestions are NEVER auto-posted
     :postings
     [{:posting/account   bank-account-eid
       :posting/amount    (:amount candidate)
       :posting/commodity commodity-eid
       :posting/narration (:description candidate)}
      {:posting/account   contra
       :posting/amount    (.negate ^java.math.BigDecimal (:amount candidate))
       :posting/commodity commodity-eid
       :posting/narration (:description candidate)}]
     :_money amt}))

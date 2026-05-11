(ns kontor.bank-de.parser
  "German bank-statement CSV configs + categorizer.

   The generic CSV-parsing engine lives in the kernel at
   `kontor.bank-csv`; this namespace just supplies
   the per-bank `bank-configs` map, a `detect-bank` heuristic,
   and the openclaw-derived auto-categorizer for German-language
   transactions.

   Supported formats (11 banks, 14 file variants):
     :dkb  :ing  :commerzbank  :postbank  :paypal
     :sparkasse-camt  :sparkasse-mt940
     :targobank  :gls-bank  :sparda-bank-west  :vr-bank

   See `test/resources/FORMATS.md` for field-by-field documentation."
  (:require [clojure.string :as str]
            [kontor.bank-csv :as csv-core]
            [kontor.money :as money]))

;; Re-export kernel helpers so existing call-sites (and tests) that
;; reach for `parser/parse-german-amount` still work — no semantic
;; difference; the engine lives in the kernel now.
(def parse-german-amount csv-core/parse-german-amount)
(def parse-english-amount csv-core/parse-english-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations (German banks)
;; ============================================================================

(def bank-configs
  {:dkb {:encoding "UTF-8" :skip-rows 4 :date-format "dd.MM.yy" :separator \;
         :amount-style :german
         :col-indexes {:date 0 :value-date 1 :counterparty 3 :recipient 4
                       :description 5 :type 6 :iban 7 :amount 8}}

   :ing {:encoding "ISO-8859-1" :skip-rows 12 :date-format "dd.MM.yyyy" :separator \;
         :amount-style :german
         :col-indexes {:date 0 :value-date 1 :counterparty 2
                       :description 4 :type 3 :amount 5}}

   :commerzbank {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yyyy" :separator \;
                 :amount-style :german
                 :col-indexes {:date 0 :value-date 1 :type 2 :description 3
                               :amount 4 :currency 5 :iban 6 :category 7}}

   :postbank {:encoding "UTF-8" :skip-rows 8 :date-format "d.M.yyyy" :separator \;
              :amount-style :german
              :col-indexes {:date 0 :value-date 1 :type 2 :counterparty 3
                            :description 4 :iban 5 :amount 11 :currency 17}}

   :paypal {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yyyy" :separator \,
            :amount-style :german
            :col-indexes {:date 0 :time 1 :timezone 2 :counterparty 3 :type 4
                          :status 5 :currency 6 :gross 7 :fee 8 :amount 9
                          :sender-email 10 :receiver-email 11 :tx-id 12}}

   :sparkasse-camt {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yy" :separator \;
                    :amount-style :german
                    :col-indexes {:auftragskonto 0 :date 1 :value-date 2
                                  :type 3 :description 4 :creditor-id 5
                                  :mandate-ref 6 :e2e-ref 7 :collector-ref 8
                                  :original-amount 9 :returnee 10
                                  :counterparty 11 :iban 12 :bic 13 :amount 14
                                  :currency 15 :info 16}}

   :sparkasse-mt940 {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yy" :separator \;
                     :amount-style :german
                     :col-indexes {:auftragskonto 0 :date 1 :value-date 2
                                   :type 3 :description 4 :counterparty 5
                                   :iban 6 :bic 7 :amount 8 :currency 9
                                   :info 10}}

   :targobank {:encoding "UTF-8" :skip-rows 0 :date-format "dd.MM.yyyy" :separator \;
               :amount-style :german
               :col-indexes {:date 0 :type 1 :counterparty 1 :description 1
                             :amount 2 :currency 5}}

   :gls-bank {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yyyy" :separator \;
              :amount-style :german
              :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                            :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                            :counterparty-bic 8 :type 9 :description 10 :amount 11
                            :currency 12 :balance 13 :remark 14 :marked 15
                            :creditor-id 16 :mandate-ref 17}}

   :sparda-bank-west {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yyyy" :separator \;
                      :amount-style :german
                      :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                                    :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                                    :counterparty-bic 8 :type 9 :description 10 :amount 11
                                    :currency 12 :balance 13 :remark 14 :marked 15
                                    :creditor-id 16 :mandate-ref 17}}

   :vr-bank {:encoding "UTF-8" :skip-rows 1 :date-format "dd.MM.yyyy" :separator \;
             :amount-style :german
             :col-indexes {:account-name 0 :account-iban 1 :account-bic 2 :bank-name 3
                           :date 4 :value-date 5 :counterparty 6 :counterparty-iban 7
                           :counterparty-bic 8 :type 9 :description 10 :amount 11
                           :currency 12 :balance 13 :remark 14 :marked 15
                           :creditor-id 16 :mandate-ref 17}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
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
      (str/includes? preview "Girokonto")          :dkb
      (str/includes? preview "Umsatzanzeige")      :ing
      (str/includes? preview "Buchungstag;Wertstellung;Umsatzart") :commerzbank
      (str/includes? preview "Bezeichnung Auftragskonto;IBAN Auftragskonto;BIC") :gls-bank
      (str/includes? preview "Auftragskonto;Buchungstag")          :sparkasse-camt
      (str/includes? preview "Umsätze")            :postbank
      (str/includes? preview "Datum,Uhrzeit")      :paypal
      :else nil)))

;; ============================================================================
;; DE-specific categorizer (lifted from openclaw beleg/bank.clj)
;; ============================================================================

(def category-patterns
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
  [tx]
  (let [text   (str/upper-case (str (:counterparty tx) " " (:description tx)))
        amount (or (:amount tx) 0M)
        base   (if (>= (.signum ^java.math.BigDecimal amount) 0)
                 :einnahmen :sonstige-betriebsausgaben)
        match (reduce (fn [_ [cat patterns]]
                        (when (some #(re-find % text) patterns) (reduced cat)))
                      nil category-patterns)]
    (assoc tx :category (or match base))))

;; ============================================================================
;; Public: parse-statement (DE-flavored)
;; ============================================================================

(defn parse-statement
  "DE bank-statement parser. Auto-detects bank, parses, categorizes."
  [path]
  (csv-core/parse-statement
   {:bank-configs   bank-configs
    :detect-bank-fn detect-bank
    :categorize-fn  categorize-transaction}
   path))

;; ============================================================================
;; Re-export the canonical posting-pair projection (kernel-side)
;; ============================================================================

(defn candidate->posting-pair
  "Project a parsed candidate into a balanced posting-pair input for
   posting/build-transaction. Caller supplies a contra-resolver fn
   that maps (category) → contra-account-eid (typically via
   kontor.l10n-de.chart/category->contra-eid)."
  [candidate {:keys [bank-account-eid commodity-eid journal-eid contra-resolver]}]
  (let [contra (contra-resolver (:category candidate))]
    {:transaction
     {:transaction/external-id   (str (:bank candidate) "/"
                                      (.getTime ^java.util.Date (:date candidate))
                                      "/" (hash (:raw-row candidate)))
      :transaction/journal       journal-eid
      :transaction/effective-date (:date candidate)
      :transaction/narration     (str (:counterparty candidate)
                                      " — " (:description candidate))
      :transaction/state         :draft}
     :postings
     [{:posting/account   bank-account-eid
       :posting/amount    (:amount candidate)
       :posting/commodity commodity-eid
       :posting/narration (:description candidate)}
      {:posting/account   contra
       :posting/amount    (.negate ^java.math.BigDecimal (:amount candidate))
       :posting/commodity commodity-eid
       :posting/narration (:description candidate)}]
     :_money (money/money (:amount candidate) commodity-eid)}))

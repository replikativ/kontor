(ns kontor.bank-at.parser
  "Austrian bank-statement CSV configs + categorizer.

   Generic CSV engine lives in `kontor.bank-csv`. AT and
   DE bank exports are very close (German-language, EUR, dd.MM.yyyy)
   but the column orders differ per bank — and Austria has its own
   set of dominant retail banks (Erste, Raiffeisen, Bank Austria,
   BAWAG).

   Supported:
     :erste            — Erste Bank / Sparkassen (george / netbanking)
     :raiffeisen       — Raiffeisen-Landesbanken
     :bank-austria     — UniCredit Bank Austria
     :bawag-psk        — BAWAG P.S.K. (PSK successor)

   Notes (column specs derived from each bank's documented CSV
   export — we keep the parser format-tolerant since regional
   Raiffeisen banks and Sparkassen each lightly vary their layouts):

     ERSTE BANK / GEORGE  (CAVEAT — user-configurable)
       George's CSV export lets the user drag-and-drop column order
       and toggle individual fields. There is NO canonical header.
       The config below targets one *common* configuration with all
       counterparty fields enabled. Users with a different field
       layout should derive a per-export config from this template.
       Header: 'Buchungsdatum;Valutadatum;Buchungsinformation;
                IBAN Auftraggeber;BIC Auftraggeber;Auftraggeber;
                IBAN Empfänger;BIC Empfänger;Empfänger;Betrag;Währung'
       Date: dd.MM.yyyy  •  Amount: signed German (1.234,56)

     RAIFFEISEN (Mein ELBA)
       NO HEADER  •  6 cols: Buchungstag;Buchungstext;Valutadatum;
                              Betrag;Währung;Zeitstempel
       Date: dd.MM.yyyy  •  Amount: signed German
       Counterparty + Verwendungszweck embedded in quoted Buchungstext
       Source: nblock/ofxstatement-austrian sample fixture

     BANK AUSTRIA (UniCredit)
       Header: 'Buchungsdatum;Valutadatum;Buchungstext;Interne Notiz;
                Währung;Betrag;Belegdaten'
       Date: dd.MM.yyyy  •  Amount: signed German
       Source: onetwoapps.de CSV importer config (medium confidence;
       Bank Austria has not published an authoritative spec)

     BAWAG P.S.K. (native short form)
       NO HEADER  •  6 cols: Account;Buchungstext;Buchungsdatum;
                              Valutadatum;Betrag;Währung
       Date: dd.MM.yyyy  •  Amount: signed German with '+' on credits
       Source: PeterTheOne/bawag-csv-parser unit-test fixture
       Note: an extended 18-col form exists in newer eBanking exports;
       use a different config when targeting that variant."
  (:require [clojure.string :as str]
            [kontor.bank-csv :as csv-core]))

(def parse-german-amount csv-core/parse-german-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  {:erste
   {:encoding "UTF-8" :skip-rows 0 :date-format "dd.MM.yyyy" :separator \;
    :amount-style :german
    :col-indexes {:date 0 :value-date 1 :description 2 :iban-sender 3
                  :bic-sender 4 :counterparty 5 :iban 6 :bic 7
                  :recipient 8 :amount 9 :currency 10}}

   ;; Raiffeisen / Mein ELBA — 6 cols, NO header, embedded counterparty
   ;; in quoted Buchungstext field. Confirmed by nblock/ofxstatement-austrian
   ;; sample fixture (raiffeisen.csv).
   :raiffeisen
   {:encoding "UTF-8" :no-header? true :date-format "dd.MM.yyyy" :separator \;
    :amount-style :german
    :col-indexes {:date 0 :description 1 :value-date 2 :amount 3
                  :currency 4 :timestamp 5}}

   ;; Bank Austria (UniCredit) — 7 cols, with header. Counterparty is
   ;; embedded in Buchungstext. Source: onetwoapps.de CSV importer config
   ;; (3rd-party — medium confidence; treat as one common variant).
   :bank-austria
   {:encoding "UTF-8" :skip-rows 0 :date-format "dd.MM.yyyy" :separator \;
    :amount-style :german
    :col-indexes {:date 0 :value-date 1 :description 2 :note 3
                  :currency 4 :amount 5 :belegdaten 6}}

   ;; BAWAG P.S.K. (native short form) — 6 cols, NO header, embedded
   ;; counterparty in Buchungstext, '+' prefix on credits. Confirmed by
   ;; PeterTheOne/bawag-csv-parser unit-test fixture (PHP repo).
   :bawag-psk
   {:encoding "UTF-8" :no-header? true :date-format "dd.MM.yyyy" :separator \;
    :amount-style :german
    :col-indexes {:account 0 :description 1 :date 2 :value-date 3
                  :amount 4 :currency 5}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
  [filename content-preview]
  (let [lower (str/lower-case (str filename))
        preview (str content-preview)]
    (cond
      (str/includes? lower "erste")          :erste
      (str/includes? lower "george")         :erste
      (str/includes? lower "raiffeisen")     :raiffeisen
      (str/includes? lower "rlb")            :raiffeisen
      (str/includes? lower "bank-austria")   :bank-austria
      (str/includes? lower "bankaustria")    :bank-austria
      (str/includes? lower "unicredit")      :bank-austria
      (str/includes? lower "bawag")          :bawag-psk
      (str/includes? lower "psk")            :bawag-psk
      (str/includes? preview "Buchungsinformation;IBAN Auftraggeber") :erste
      (str/includes? preview "Buchungsdatum;Valutadatum;Buchungstext;Interne Notiz;Währung") :bank-austria
      :else nil)))

;; ============================================================================
;; Categorizer (German-language patterns; AT-flavored merchants)
;; ============================================================================

(def category-patterns
  {:einnahmen        [#"(?i)\bGEHALT\b" #"(?i)LOHN" #"(?i)\bENTGELT\b"
                      #"(?i)RECHNUNG" #"(?i)HONORAR" #"(?i)PROVISION"
                      #"(?i)FAMILIENBEIHILFE"]
   :miete            [#"(?i)\bMIETE\b" #"(?i)GENOSSENSCHAFT" #"(?i)BAUTRÄGER"]
   :strom-gas        [#"(?i)\bWIEN ENERGIE\b" #"(?i)\bENERGIE AG\b"
                      #"(?i)\bVERBUND\b" #"(?i)\bEVN\b"]
   :telekom          [#"(?i)\bA1\b" #"(?i)MAGENTA TELEKOM" #"(?i)\bDREI\b"
                      #"(?i)\bUPC\b" #"(?i)\bSIMPLITV\b"]
   :lebensmittel     [#"(?i)BILLA" #"(?i)SPAR" #"(?i)\bHOFER\b" #"(?i)PENNY"
                      #"(?i)LIDL" #"(?i)\bMERKUR\b" #"(?i)\bMPREIS\b"]
   :gastronomie      [#"(?i)RESTAURANT" #"(?i)\bWIRT\b" #"(?i)\bBEISL\b"
                      #"(?i)\bCAFE\b" #"(?i)KONDITOREI" #"(?i)MCDONALD"]
   :transport        [#"(?i)\bWIENER LINIEN\b" #"(?i)\bÖBB\b" #"(?i)WESTBAHN"
                      #"(?i)\bUBER\b" #"(?i)\bBOLT\b"]
   :treibstoff       [#"(?i)\bOMV\b" #"(?i)\bBP\b" #"(?i)SHELL" #"(?i)JET"
                      #"(?i)TURMÖL" #"(?i)\bENI\b"]
   :software         [#"(?i)ADOBE" #"(?i)MICROSOFT" #"(?i)GOOGLE.*WORKSPACE"
                      #"(?i)\bAWS\b" #"(?i)NETFLIX" #"(?i)SPOTIFY"
                      #"(?i)CHATGPT" #"(?i)\bOPENAI\b"]
   :versicherung     [#"(?i)VERSICHERUNG" #"(?i)\bUNIQA\b" #"(?i)\bWIENER STÄDTISCHE\b"
                      #"(?i)\bGENERALI\b" #"(?i)\bOÖGKK\b" #"(?i)\bSVS\b"]
   :bankspesen       [#"(?i)KONTOFÜHRUNGSGEBÜHR" #"(?i)BANKSPESEN" #"(?i)\bSPESEN\b"]
   :steuer           [#"(?i)FINANZAMT" #"(?i)\bUSt\b" #"(?i)EINKOMMENSTEUER"]})

(defn categorize-transaction
  [tx]
  (let [text   (str/upper-case (str (:counterparty tx) " " (:description tx)
                                    " " (:transaction-type tx)))
        amount (or (:amount tx) 0M)
        base   (if (>= (.signum ^java.math.BigDecimal amount) 0)
                 :einnahmen :sonstige-betriebsausgaben)
        match (reduce (fn [_ [cat patterns]]
                        (when (some #(re-find % text) patterns) (reduced cat)))
                      nil category-patterns)]
    (assoc tx :category (or match base))))

;; ============================================================================
;; Public
;; ============================================================================

(defn parse-statement
  [path]
  (csv-core/parse-statement
   {:bank-configs   bank-configs
    :detect-bank-fn detect-bank
    :categorize-fn  categorize-transaction}
   path))

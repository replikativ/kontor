(ns kontor.bank-fr.parser
  "French bank-statement CSV configs + categorizer.

   Generic CSV engine lives in `kontor.banking.bank-csv`.

   Supported:
     :n26              — N26 EUR account export (also serves AT/DE)
     :credit-agricole  — Crédit Agricole personal account
     :societe-generale — Société Générale personal account
     :bnp-paribas      — BNP Paribas personal account

   Notes:

     N26
       Header: 'Date,Payee,Account number,Transaction type,Payment reference,
                Category,Amount (EUR),Amount (Foreign Currency),
                Type Foreign Currency,Exchange Rate'
       Date: yyyy-MM-dd  •  Amount: signed English in EUR
       (Covers EN/DE/FR N26 export variants; column order is identical,
       only headers translated.)

     CRÉDIT AGRICOLE (CSV export, regional CR formats vary slightly)
       Header: 'Date;Date valeur;Libellé;Débit euros;Crédit euros'
       Date: dd/MM/yyyy  •  Split-debit-credit, French numerals (1.234,56)

     SOCIÉTÉ GÉNÉRALE (Espace Client export)
       Header: 'Date de l'opération;Libellé;Détail;Montant;Devise'
       Date: dd/MM/yyyy  •  Signed amount in single column, French numerals

     BNP PARIBAS (Mes Comptes export)
       Header: 'Date opération;Libellé opération;Montant'
       Date: dd/MM/yyyy  •  Signed amount, French numerals"
  (:require [clojure.string :as str]
            [kontor.banking.bank-csv :as csv-core]))

(def parse-german-amount csv-core/parse-german-amount)
(def parse-english-amount csv-core/parse-english-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  {:n26
   {:encoding "UTF-8" :skip-rows 0 :date-format "yyyy-MM-dd" :separator \,
    :amount-style :english
    :col-indexes {:date 0 :counterparty 1 :iban 2 :type 3
                  :description 4 :category 5 :amount 6
                  :original-amount 7 :original-currency 8 :exchange-rate 9}}

   :credit-agricole
   {:encoding "ISO-8859-1" :skip-rows 0 :date-format "dd/MM/yyyy" :separator \;
    :amount-style :split-debit-credit
    :col-indexes {:date 0 :value-date 1 :description 2 :debit 3 :credit 4}}

   :societe-generale
   {:encoding "ISO-8859-1" :skip-rows 0 :date-format "dd/MM/yyyy" :separator \;
    :amount-style :german
    :col-indexes {:date 0 :description 1 :detail 2 :amount 3 :currency 4}}

   :bnp-paribas
   {:encoding "ISO-8859-1" :skip-rows 0 :date-format "dd/MM/yyyy" :separator \;
    :amount-style :german
    :col-indexes {:date 0 :description 1 :amount 2}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
  [filename content-preview]
  (let [lower (str/lower-case (str filename))
        preview (str content-preview)]
    (cond
      (str/includes? lower "n26")               :n26
      (str/includes? lower "credit-agricole")   :credit-agricole
      (str/includes? lower "credit_agricole")   :credit-agricole
      (str/includes? lower "ca-")               :credit-agricole
      (str/includes? lower "societe-generale")  :societe-generale
      (str/includes? lower "societe_generale")  :societe-generale
      (str/includes? lower "sg-")               :societe-generale
      (str/includes? lower "bnp")               :bnp-paribas
      (str/includes? preview "Date\",\"Payee\",\"Account number\"")     :n26
      (str/includes? preview "Datum\",\"Empfänger\",\"Kontonummer\"")  :n26
      (str/includes? preview "Date\",\"Bénéficiaire\",\"Numéro de compte\"") :n26
      (str/includes? preview "Date;Date valeur;Libellé;Débit")        :credit-agricole
      (str/includes? preview "Date de l'opération;Libellé;Détail")    :societe-generale
      (str/includes? preview "Date opération;Libellé opération;Montant") :bnp-paribas
      :else nil)))

;; ============================================================================
;; Categorizer (French-language patterns)
;; ============================================================================

(def category-patterns
  {:revenus          [#"(?i)\bSALAIRE\b" #"(?i)VIREMENT.*EN VOTRE FAVEUR"
                      #"(?i)REMBOURSEMENT" #"(?i)\bPRESTATION\b" #"(?i)\bHONORAIRE\b"]
   :loyer            [#"(?i)\bLOYER\b" #"(?i)\bBAIL\b"]
   :electricite-gaz  [#"(?i)\bEDF\b" #"(?i)\bENGIE\b" #"(?i)\bGRDF\b"
                      #"(?i)ENI GAS" #"(?i)TOTALENERGIES"]
   :telecom          [#"(?i)\bORANGE\b" #"(?i)\bSFR\b" #"(?i)\bBOUYGUES\b"
                      #"(?i)\bFREE TELECOM\b" #"(?i)\bFREE MOBILE\b"]
   :alimentation     [#"(?i)CARREFOUR" #"(?i)LECLERC" #"(?i)AUCHAN"
                      #"(?i)MONOPRIX" #"(?i)FRANPRIX" #"(?i)\bU EXPRESS\b"
                      #"(?i)PICARD"]
   :restauration     [#"(?i)RESTAURANT" #"(?i)BRASSERIE" #"(?i)BISTRO"
                      #"(?i)\bCAFE\b" #"(?i)BOULANGERIE" #"(?i)UBER ?EATS"
                      #"(?i)DELIVEROO"]
   :transport        [#"(?i)\bSNCF\b" #"(?i)\bRATP\b" #"(?i)NAVIGO"
                      #"(?i)\bUBER\b" #"(?i)\bBLABLACAR\b"]
   :carburant        [#"(?i)\bSHELL\b" #"(?i)\bTOTAL\b" #"(?i)\bESSO\b"
                      #"(?i)\bBP\b" #"(?i)CARBURANT"]
   :logiciels        [#"(?i)ADOBE" #"(?i)MICROSOFT" #"(?i)GOOGLE"
                      #"(?i)\bAWS\b" #"(?i)NETFLIX" #"(?i)SPOTIFY"
                      #"(?i)CHATGPT" #"(?i)\bOPENAI\b"]
   :assurance        [#"(?i)ASSURANCE" #"(?i)\bAXA\b" #"(?i)\bMAIF\b"
                      #"(?i)\bMAAF\b" #"(?i)MATMUT" #"(?i)\bMACIF\b"]
   :frais-bancaires  [#"(?i)FRAIS.*BANCAIRES" #"(?i)COTISATION"
                      #"(?i)COMMISSION.*INTERVENTION"]
   :impots           [#"(?i)\bDGFIP\b" #"(?i)IMPOTS" #"(?i)\bTRESOR PUBLIC\b"]
   :sante            [#"(?i)PHARMACIE" #"(?i)\bMEDECIN\b" #"(?i)CPAM"
                      #"(?i)MUTUELLE"]})

(defn categorize-transaction
  [tx]
  (let [text   (str/upper-case (str (:counterparty tx) " " (:description tx)
                                    " " (:transaction-type tx)))
        amount (or (:amount tx) 0M)
        base   (if (>= (.signum ^java.math.BigDecimal amount) 0)
                 :revenus :charges-diverses)
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

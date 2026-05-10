(ns datahike-accounting.bank-ca.parser
  "Canadian bank-statement CSV configs + categorizer.

   Generic CSV engine lives in `datahike-accounting.bank-csv`.

   Supported:
     :rbc        — Royal Bank of Canada (chequing/savings export)
     :td         — TD Canada Trust (chequing export)
     :scotiabank — Scotiabank (chequing/savings)
     :bmo        — Bank of Montreal (chequing)

   Notes:

     RBC (chequing/savings)
       NO HEADER  •  cols: Account Type, Account Number, Transaction Date,
                           Cheque Number, Description 1, Description 2,
                           CAD$, USD$
       Date format: M/d/yyyy  •  Amount: signed English in CAD$
                                 (USD$ separately tracked)

     TD CANADA TRUST (chequing)
       NO HEADER  •  cols: Date, Description, Withdrawal, Deposit, Balance
       Date format: MM/dd/yyyy  •  Split-debit-credit (withdrawal+deposit)

     SCOTIABANK (chequing/savings)
       Header: 'Filter,Date,Time,Description,Sub-description,Status,Type
                of Transaction,Amount'
       Date format: yyyy-MM-dd  •  Amount: signed English

     BMO (chequing)
       Header (5-row preamble then real header):
         'First Bank Card,Transaction Type,Date Posted,Transaction Amount,
          Description'
       Date format: yyyyMMdd  •  Amount: signed English"
  (:require [clojure.string :as str]
            [datahike-accounting.bank-csv :as csv-core]))

(def parse-english-amount csv-core/parse-english-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  {:rbc
   {:encoding "UTF-8" :no-header? true :date-format "M/d/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:account-type 0 :account-number 1 :date 2 :check 3
                  :description 4 :description-2 5 :amount 6 :usd-amount 7}}

   :td
   {:encoding "UTF-8" :no-header? true :date-format "MM/dd/yyyy" :separator \,
    :amount-style :split-debit-credit
    :col-indexes {:date 0 :description 1 :debit 2 :credit 3 :balance 4}}

   :scotiabank
   {:encoding "UTF-8" :skip-rows 0 :date-format "yyyy-MM-dd" :separator \,
    :amount-style :english
    :col-indexes {:filter 0 :date 1 :time 2 :description 3
                  :sub-description 4 :status 5 :type 6 :amount 7}}

   :bmo
   {:encoding "UTF-8" :skip-rows 5 :date-format "yyyyMMdd" :separator \,
    :amount-style :english
    :col-indexes {:card 0 :type 1 :date 2 :amount 3 :description 4}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
  [filename content-preview]
  (let [lower (str/lower-case (str filename))
        preview (str content-preview)]
    (cond
      (str/includes? lower "rbc")        :rbc
      (str/includes? lower "royal-bank") :rbc
      (str/includes? lower "td")         :td
      (str/includes? lower "scotia")     :scotiabank
      (str/includes? lower "bmo")        :bmo
      (str/includes? lower "montreal")   :bmo
      (str/includes? preview "Filter,Date,Time,Description") :scotiabank
      (str/includes? preview "First Bank Card,Transaction Type,Date Posted") :bmo
      (str/includes? preview "Following data is valid as of") :bmo
      :else nil)))

;; ============================================================================
;; Categorizer (English-language patterns; CA-flavored merchants)
;; ============================================================================

(def category-patterns
  {:income           [#"(?i)PAYROLL" #"(?i)\bDEPOSIT\b" #"(?i)\bSALARY\b"
                      #"(?i)PAY DEP" #"(?i)\bPAYMENT\b.*FROM" #"(?i)e-?TRANSFER FROM"
                      #"(?i)CRA REFUND" #"(?i)GST/?HST CREDIT" #"(?i)CCTB"]
   :rent             [#"(?i)\bRENT\b" #"(?i)LANDLORD"]
   :utilities        [#"(?i)HYDRO" #"(?i)\bENBRIDGE\b" #"(?i)EPCOR"
                      #"(?i)BC HYDRO" #"(?i)ONTARIO HYDRO"]
   :telecom          [#"(?i)\bROGERS\b" #"(?i)\bBELL\b" #"(?i)\bTELUS\b"
                      #"(?i)\bFREEDOM MOBILE\b" #"(?i)\bKOODO\b" #"(?i)FIDO"]
   :groceries        [#"(?i)LOBLAWS" #"(?i)\bMETRO\b" #"(?i)\bSOBEYS\b"
                      #"(?i)NO FRILLS" #"(?i)WALMART" #"(?i)COSTCO"
                      #"(?i)SHOPPERS DRUG"]
   :dining           [#"(?i)\bTIM HORTONS\b" #"(?i)STARBUCKS" #"(?i)\bMCD\b"
                      #"(?i)RESTAURANT" #"(?i)CAFE" #"(?i)SKIP.?THE.?DISHES"
                      #"(?i)DOORDASH" #"(?i)UBER ?EATS"]
   :transport        [#"(?i)\bTTC\b" #"(?i)\bGO TRANSIT\b" #"(?i)PRESTO"
                      #"(?i)TRANSLINK" #"(?i)\bUBER\b" #"(?i)\bLYFT\b"
                      #"(?i)PARKING"]
   :fuel             [#"(?i)\bPETRO-?CANADA\b" #"(?i)\bESSO\b" #"(?i)\bSHELL\b"
                      #"(?i)\bCHEVRON\b" #"(?i)\bIRVING\b" #"(?i)\bHUSKY\b"]
   :software         [#"(?i)ADOBE" #"(?i)MICROSOFT" #"(?i)GOOGLE.*CLOUD"
                      #"(?i)\bAWS\b" #"(?i)NETFLIX" #"(?i)SPOTIFY"
                      #"(?i)CHATGPT" #"(?i)\bOPENAI\b"]
   :insurance        [#"(?i)INSURANCE" #"(?i)\bICBC\b" #"(?i)INTACT"
                      #"(?i)BELAIRDIRECT" #"(?i)TD INSURANCE"]
   :bank-fees        [#"(?i)SERVICE.?CHARGE" #"(?i)NSF" #"(?i)OVERDRAFT"
                      #"(?i)WIRE.?FEE" #"(?i)PLAN FEE"]
   :tax              [#"(?i)\bCRA\b" #"(?i)CDA TAX" #"(?i)REVENU.QUEBEC"
                      #"(?i)\bGST/?HST\b.*OWING"]})

(defn categorize-transaction
  [tx]
  (let [text   (str/upper-case (str (:counterparty tx) " " (:description tx)
                                    " " (:transaction-type tx)))
        amount (or (:amount tx) 0M)
        base   (if (>= (.signum ^java.math.BigDecimal amount) 0)
                 :income :uncategorized-expense)
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

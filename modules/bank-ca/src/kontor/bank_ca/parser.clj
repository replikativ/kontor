(ns kontor.bank-ca.parser
  "Canadian bank-statement CSV configs + categorizer.

   Generic CSV engine lives in `kontor.banking.bank-csv`.

   Supported:
     :rbc        — Royal Bank of Canada (chequing/savings export)
     :td         — TD Canada Trust (chequing export)
     :scotiabank — Scotiabank (chequing/savings)
     :bmo        — Bank of Montreal (chequing)

   Notes:

     RBC (chequing/savings)
       Header: 'Account Type,Account Number,Transaction Date,
                Cheque Number,Description 1,Description 2,CAD$,USD$'
       Date format: M/d/yyyy  •  Amount: signed English in CAD$
                                 (USD$ separately tracked)

     TD CANADA TRUST (EasyWeb)
       NO HEADER  •  cols: Date, Description, Withdrawal, Deposit, Balance
       Date format: MM/dd/yyyy  •  Split-debit-credit (withdrawal+deposit)

     SCOTIABANK (personal banking)
       NO HEADER  •  5 cols: Date, signed Amount, [reserved],
                              Transaction Type, Description
       Date format: MM/dd/yyyy  •  Amount: signed English ('-' on debits)
       Note: ScotiaConnect (business banking) uses a different
       user-template-driven CSV; not modeled here.

     BMO (chequing)
       Format: 1 informational preamble line + 2 blanks + header line
       + 2 blanks before data. Header (note literal leading space
       before 'Transaction Amount'):
         'First Bank Card,Transaction Type,Date Posted, Transaction
          Amount,Description'
       Date format: yyyyMMdd  •  Amount: signed English"
  (:require [clojure.string :as str]
            [kontor.banking.bank-csv :as csv-core]))

(def parse-english-amount csv-core/parse-english-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  ;; RBC chequing/savings — header IS present (real export from
  ;; "Manage My Money → Account Activity").
  {:rbc
   {:encoding "UTF-8" :skip-rows 0 :date-format "M/d/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:account-type 0 :account-number 1 :date 2 :check 3
                  :description 4 :description-2 5 :amount 6 :usd-amount 7}}

   ;; TD Canada Trust EasyWeb — NO header, 5 cols (Date, Description,
   ;; Withdrawal, Deposit, Balance).
   ;; Retail deposit account: the Withdrawal column is money LEAVING
   ;; → :debit-sign -1, :credit-sign 1. English numerals (3,850.00).
   :td
   {:encoding "UTF-8" :no-header? true :date-format "MM/dd/yyyy" :separator \,
    :amount-style :split-debit-credit
    :number-format :english :debit-sign -1 :credit-sign 1
    :col-indexes {:date 0 :description 1 :debit 2 :credit 3 :balance 4}}

   ;; Scotiabank PERSONAL banking — 5 cols, NO header.
   ;; Cols: Date, signed Amount, [reserved/blank], Type-of-transaction,
   ;; Description.
   ;; (Note: ScotiaConnect business banking uses a totally different
   ;; user-template-driven CSV — model that as :scotia-business if
   ;; needed.)
   :scotiabank
   {:encoding "UTF-8" :no-header? true :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:date 0 :amount 1 :reserved 2 :type 3 :description 4}}

   ;; BMO chequing — 1-line preamble + 2 blanks + header + 2 blanks +
   ;; data (5 leading rows total); header has '. Transaction Amount'
   ;; with leading space.
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
      (str/includes? preview "First Bank Card,Transaction Type,Date Posted") :bmo
      (str/includes? preview "Following data is valid as of") :bmo
      (str/includes? preview "Account Type\",\"Account Number\",\"Transaction Date") :rbc
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

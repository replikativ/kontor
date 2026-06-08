(ns kontor.bank-us.parser
  "US bank-statement CSV configs + categorizer.

   Generic CSV engine lives in `kontor.banking.bank-csv`; this
   module just adds US bank configs + a USD categorizer.

   Supported (so far):
     :chase         — Chase consumer checking export
     :wells-fargo   — Wells Fargo personal checking export
     :bofa          — Bank of America checking export
     :amex          — American Express card statement export

   Notes on each:

     CHASE (consumer checking)
       Header: 'Details,Posting Date,Description,Amount,Type,Balance,Check or Slip #'
       Date format: MM/DD/YYYY  •  Amount: signed English (-123.45)
       Source spec: Chase official 'Download account activity' (CSV)

     CHASE (credit card)
       Header: 'Card,Transaction Date,Post Date,Description,Category,Type,Amount,Memo'
       Date format: MM/DD/YYYY  •  Amount: signed English (negative=charge)

     WELLS FARGO (personal checking)
       NO HEADER  •  cols: Date, Amount (signed), '*', Check#, Description
       Date format: MM/DD/YYYY  •  Amount: signed English

     BANK OF AMERICA (checking)
       Header (after 6-row preamble): 'Date,Description,Amount,Running Bal.'
       Date format: MM/DD/YYYY  •  Amount: signed English

     AMERICAN EXPRESS
       Header: 'Date,Description,Amount,Extended Details,Appears On Your Statement As,Address,Reference,Category'
       Date format: MM/DD/YYYY  •  Amount: signed English (positive=charge)"
  (:require [clojure.string :as str]
            [kontor.banking.bank-csv :as csv-core]))

;; Re-exports for ergonomics + back-compat.
(def parse-english-amount csv-core/parse-english-amount)
(def parse-date csv-core/parse-date)

;; ============================================================================
;; Bank configurations
;; ============================================================================

(def bank-configs
  {:chase
   {:encoding "UTF-8" :skip-rows 0 :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:type 0 :date 1 :description 2 :amount 3
                  :type-detail 4 :balance 5 :check 6}}

   :chase-credit
   {:encoding "UTF-8" :skip-rows 0 :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:card 0 :date 1 :post-date 2 :description 3
                  :category 4 :type 5 :amount 6 :memo 7}}

   :wells-fargo
   {:encoding "UTF-8" :no-header? true :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    ;; No header row at all — every line is data. :no-header? bypasses
    ;; keyword auto-detect entirely.
    :col-indexes {:date 0 :amount 1 :marker 2 :check 3 :description 4}}

   :bofa
   {:encoding "UTF-8" :skip-rows 6 :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    ;; BoA prepends 6 lines of beginning-balance preamble before the
    ;; real header. The header keyword auto-detect catches it via
    ;; \"Date\" + \"Description\" anyway, but skip-rows is the safety net.
    :col-indexes {:date 0 :description 1 :amount 2 :balance 3}}

   :amex
   {:encoding "UTF-8" :skip-rows 1 :date-format "MM/dd/yyyy" :separator \,
    :amount-style :english
    :col-indexes {:date 0 :description 1 :amount 2 :extended 3
                  :statement-as 4 :address 5 :reference 6 :category 7}}})

;; ============================================================================
;; Detection
;; ============================================================================

(defn detect-bank
  "Detect bank from filename + first-line preview."
  [filename content-preview]
  (let [lower (str/lower-case (str filename))
        preview (str content-preview)]
    (cond
      (str/includes? lower "chase-credit")   :chase-credit
      (str/includes? lower "chase")          :chase
      (str/includes? lower "wells")          :wells-fargo
      (str/includes? lower "wf-")            :wells-fargo
      (str/includes? lower "bofa")           :bofa
      (str/includes? lower "bankofamerica")  :bofa
      (str/includes? lower "amex")           :amex
      (str/includes? lower "americanexpress") :amex
      ;; Header-content fallback when filename is generic.
      (str/includes? preview "Details,Posting Date,Description,Amount") :chase
      (str/includes? preview "Card,Transaction Date,Post Date,Description") :chase-credit
      (str/includes? preview "Posted Transactions") :bofa
      (str/includes? preview "Beginning balance as of")  :bofa
      (and (str/includes? preview "Date,Description,Amount,Extended Details")) :amex
      :else nil)))

;; ============================================================================
;; Categorizer (English-language patterns; minimal — bookkeepers refine)
;; ============================================================================

(def category-patterns
  {:income           [#"(?i)\bDEPOSIT\b" #"(?i)PAYROLL" #"(?i)DIRECT.?DEP"
                      #"(?i)\bSALARY\b" #"(?i)REFUND" #"(?i)ZELLE.*FROM"
                      #"(?i)VENMO.*FROM" #"(?i)CASH.?APP.*FROM"]
   :rent             [#"(?i)\bRENT\b" #"(?i)LEASE.?PAYMENT"]
   :utilities        [#"(?i)\bELEC\b" #"(?i)\bGAS\b" #"(?i)WATER" #"(?i)CONED"
                      #"(?i)PG&E" #"(?i)NATIONAL.?GRID" #"(?i)PSE&G"]
   :telecom          [#"(?i)VERIZON" #"(?i)AT&T" #"(?i)T-?MOBILE" #"(?i)SPRINT"
                      #"(?i)COMCAST" #"(?i)XFINITY" #"(?i)SPECTRUM"]
   :groceries        [#"(?i)WHOLE FOODS" #"(?i)TRADER.?JOE" #"(?i)SAFEWAY"
                      #"(?i)KROGER" #"(?i)PUBLIX" #"(?i)WEGMANS"
                      #"(?i)COSTCO" #"(?i)SAM.?S CLUB"]
   :dining           [#"(?i)RESTAURANT" #"(?i)CAFE" #"(?i)STARBUCKS"
                      #"(?i)CHIPOTLE" #"(?i)\bMCD\b" #"(?i)DOORDASH"
                      #"(?i)GRUBHUB" #"(?i)UBER ?EATS"]
   :transport        [#"(?i)\bUBER\b" #"(?i)\bLYFT\b" #"(?i)MTA"
                      #"(?i)BART" #"(?i)CTA" #"(?i)PARKING"]
   :fuel             [#"(?i)SHELL" #"(?i)CHEVRON" #"(?i)EXXON" #"(?i)BP\b"
                      #"(?i)\bGAS STATION\b" #"(?i)76 GAS"]
   :software         [#"(?i)ADOBE" #"(?i)MICROSOFT" #"(?i)GOOGLE.*WORKSPACE"
                      #"(?i)\bAWS\b" #"(?i)DROPBOX" #"(?i)GITHUB" #"(?i)NETFLIX"
                      #"(?i)SPOTIFY" #"(?i)\bOPENAI\b" #"(?i)CHATGPT"]
   :insurance        [#"(?i)INSURANCE" #"(?i)GEICO" #"(?i)PROGRESSIVE"
                      #"(?i)STATE FARM" #"(?i)ALLSTATE"]
   :bank-fees        [#"(?i)SERVICE.?FEE" #"(?i)NSF" #"(?i)OVERDRAFT"
                      #"(?i)WIRE.?FEE" #"(?i)\bATM FEE\b"]
   :tax              [#"(?i)IRS" #"(?i)\bTAX\b.*PAYMENT" #"(?i)EFTPS"]})

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
  "US bank-statement parser. Auto-detects bank, parses, categorizes.
   Returns vec of candidate maps or throws if format is unknown."
  [path]
  (csv-core/parse-statement
   {:bank-configs   bank-configs
    :detect-bank-fn detect-bank
    :categorize-fn  categorize-transaction}
   path))

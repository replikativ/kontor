(ns kontor.l10n-ca.statements-test
  "The CA income statement + balance sheet against a hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted.

   The book is a realistic first year for a small Canadian corporation:
   owner capital + prior-year retained earnings, taxable / zero-rated /
   exempt sales (each collecting the right sales tax into the right
   LIABILITY, never into revenue), purchases that generate recoverable
   GST/HST input tax credits and a QST input tax refund, one
   non-recoverable PST-paid expense, the usual operating expenses, and a
   customer collection. A next-fiscal-year sale is posted too and must
   never appear in an FY2026 statement."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.l10n-ca.bs :as bs]
            [kontor.l10n-ca.pnl :as pnl]
            [kontor.l10n-ca.preset :as ca]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private cad "CAD")
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- acct [path] [:kontor.account/path path])

(defn- two-leg! [conn date debit credit amount]
  (book/entry! conn {:debit-account  (acct debit)
                     :credit-account (acct credit)
                     :amount         amount
                     :commodity      cad
                     :journal        gj
                     :effective-date date}))

(defn- multi! [conn date postings]
  ;; postings: [[path signed-amount] …]; positive = debit, must sum to 0
  (book/entry! conn {:postings       (mapv (fn [[path amt]]
                                             {:account (acct path) :amount amt})
                                           postings)
                     :commodity      cad
                     :journal        gj
                     :effective-date date}))

(defn- seed! [conn]
  ;; opening equity
  (two-leg! conn #inst "2026-01-02" "Assets:Bank" "Equity:Owner" 100000M)
  (two-leg! conn #inst "2026-01-02" "Assets:Bank" "Equity:Retained-Earnings" 20000M)
  ;; taxable sale on account: 4000 revenue + 520 GST/HST (13% HST)
  (multi! conn #inst "2026-03-01"
          [["Assets:Receivable" 4520M]
           ["Income:Sales" -4000M]
           ["Liabilities:GST-HST-Collected" -520M]])
  ;; zero-rated + exempt sales (no tax collected)
  (two-leg! conn #inst "2026-03-05" "Assets:Bank:CAD" "Income:Sales:Zero-Rated" 3000M)
  (two-leg! conn #inst "2026-03-06" "Assets:Bank:CAD" "Income:Sales:Exempt" 1000M)
  ;; Quebec taxable sale: 2000 revenue + 200 QST collected
  (multi! conn #inst "2026-04-10"
          [["Assets:Receivable" 2200M]
           ["Income:Sales" -2000M]
           ["Liabilities:QST-Collected" -200M]])
  ;; BC taxable sale: 1000 revenue + 50 GST + 70 BC PST collected
  (multi! conn #inst "2026-04-20"
          [["Assets:Bank:CAD" 1120M]
           ["Income:Sales" -1000M]
           ["Liabilities:GST-HST-Collected" -50M]
           ["Liabilities:BC-PST-Collected" -70M]])
  ;; COGS purchase on account with recoverable ITC
  (multi! conn #inst "2026-05-01"
          [["Expenses:COGS" 3000M]
           ["Assets:GST-HST-ITC" 390M]
           ["Liabilities:Payable" -3390M]])
  ;; operating expenses, several with recoverable ITC
  (multi! conn #inst "2026-05-10"
          [["Expenses:Office" 500M] ["Assets:GST-HST-ITC" 65M] ["Assets:Bank" -565M]])
  (two-leg! conn #inst "2026-05-15" "Expenses:Rent" "Assets:Bank" 1200M)
  (multi! conn #inst "2026-05-20"
          [["Expenses:Utilities" 300M] ["Assets:GST-HST-ITC" 39M] ["Assets:Bank" -339M]])
  (two-leg! conn #inst "2026-06-01" "Expenses:Insurance" "Assets:Bank" 400M)
  (multi! conn #inst "2026-06-10"
          [["Expenses:Telecom" 200M] ["Assets:GST-HST-ITC" 26M] ["Assets:Bank" -226M]])
  (multi! conn #inst "2026-06-20"
          [["Expenses:Professional" 800M] ["Assets:GST-HST-ITC" 104M] ["Assets:Bank" -904M]])
  ;; Quebec purchase generating a recoverable QST input tax refund (asset)
  (multi! conn #inst "2026-07-01"
          [["Expenses:Professional" 200M] ["Assets:QST-ITR" 20M] ["Assets:Bank:CAD" -220M]])
  ;; non-recoverable PST paid — lands as an expense, not an asset
  (two-leg! conn #inst "2026-07-15" "Expenses:BC-PST-Paid" "Assets:Bank" 70M)
  ;; a customer collection
  (two-leg! conn #inst "2026-09-01" "Assets:Bank" "Assets:Receivable" 4520M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (two-leg! conn #inst "2027-02-01" "Assets:Bank" "Income:Sales" 50000M)
  conn)

(defn- book [] (seed! (ca/create-ca-db)))

;; Hand-computed FY2026 (all amounts CAD):
;;   Revenue    taxable 4000+2000+1000 = 7000; zero-rated 3000; exempt 1000  = 11000
;;   COGS                                                                    =  3000
;;   Gross profit                                                            =  8000
;;   Operating  office 500 + rent 1200 + util 300 + ins 400 + telecom 200
;;              + professional (800+200) 1000 + PST-paid 70                  =  3670
;;   Net income (pre-tax) 11000 − 3000 − 3670                                =  4330
;;
;;   Sales tax collected — all LIABILITIES, never in revenue:
;;     GST/HST 520 + 50 = 570 ; QST 200 ; BC PST 70
;;
;;   Balance sheet @ 2026-12-31:
;;     Bank (Assets:Bank)  100000 + 20000 − 565 − 1200 − 339 − 400 − 226
;;                         − 904 − 70 + 4520                               = 120816
;;     Bank:CAD            3000 + 1000 + 1120 − 220                        =   4900
;;     Receivable          4520 + 2200 − 4520                             =   2200
;;     GST/HST ITC         390 + 65 + 39 + 26 + 104                        =    624
;;     QST ITR                                                             =     20
;;     Total assets                                                        = 128560
;;     AP 3390 + GST/HST 570 + QST 200 + BC PST 70 = liabilities           =   4230
;;     Owner 100000 + retained 20000 + current earnings 4330 = equity      = 124330
;;     4230 + 124330                                                       = 128560  ✓

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (= 11000M (sub "1")) "revenue, net of all sales tax")
      (is (= 3000M (sub "2")) "cost of goods sold")
      (is (= 3670M (sub "3")) "operating expenses incl. non-recoverable PST paid"))
    (testing "derived subtotals"
      (is (= 8000M (:amount (:ca.pnl/gross-profit p))))
      (is (= 4330M (:amount (:ca.pnl/net-income p)))))
    (testing "revenue lines split by GST/HST treatment"
      (is (= 7000M (:amount (fs/line-value p "1" "1.1"))) "taxable")
      (is (= 3000M (:amount (fs/line-value p "1" "1.2"))) "zero-rated")
      (is (= 1000M (:amount (fs/line-value p "1" "1.3"))) "exempt"))
    (testing "every P&L Money is tagged CAD (validates note-196 F5)"
      (is (= #{:CAD} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3"])))
      (is (= :CAD (:commodity (:ca.pnl/gross-profit p))))
      (is (= :CAD (:commodity (:ca.pnl/net-income p)))))))

(deftest revenue-excludes-collected-sales-tax
  ;; The counterpart of the revenue assertion: the tax the sales collected
  ;; (570 + 200 + 70 = 840) sits in LIABILITY accounts and rolls up under
  ;; current liabilities on the balance sheet — never into the 11000 of
  ;; revenue on the income statement.
  (let [conn (book)
        b    (bs/compute conn {:through #inst "2026-12-31"})]
    (testing "GST/HST + QST + PST collected roll up under current liabilities"
      (is (= 570M (:amount (fs/line-value b "B" "B.2"))) "GST/HST collected")
      (is (= 200M (:amount (fs/line-value b "B" "B.4"))) "QST collected")
      (is (= 70M  (:amount (fs/line-value b "B" "B.3"))) "PST/RST collected"))
    (testing "gross sales tax collected (840) is NOT in the 11000 revenue"
      (is (= 11000M (:amount (fs/section-subtotal
                              (pnl/compute conn fy) "1"))))
      ;; liabilities (4230) = AP 3390 + the 840 of collected tax
      (is (= 4230M (:amount (:ca.bs/total-liabilities b)))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 128560M (:amount (:ca.bs/total-assets b))))
    (is (= 4230M (:amount (:ca.bs/total-liabilities b))))
    (is (= 124330M (:amount (:ca.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (= 0M (:amount (:ca.bs/difference b))))
      (is (:ca.bs/balanced? b)))
    (testing "recoverable input tax sits in ASSETS"
      (is (= 624M (:amount (fs/line-value b "A" "A.3"))) "GST/HST ITC")
      (is (= 20M  (:amount (fs/line-value b "A" "A.4"))) "QST ITR"))
    (testing "interim equity carries the unclosed period result"
      ;; C.3 current-period revenue (11000) + C.4 current expenses (−6670)
      ;; net to 4330 net income, held OUTSIDE retained earnings until the
      ;; year is closed; C.1 owner 100000, C.2 opening retained 20000.
      (is (= 100000M (:amount (fs/line-value b "C" "C.1"))) "owner's equity")
      (is (= 20000M (:amount (fs/line-value b "C" "C.2"))) "opening retained earnings")
      (is (= 11000M (:amount (fs/line-value b "C" "C.3"))))
      (is (= -6670M (:amount (fs/line-value b "C" "C.4")))))
    (testing "every BS Money is tagged CAD (validates note-196 F5)"
      (is (= :CAD (:commodity (:ca.bs/total-assets b))))
      (is (= :CAD (:commodity (:ca.bs/total-liabilities b))))
      (is (= :CAD (:commodity (:ca.bs/total-equity b))))
      (is (= :CAD (:commodity (:ca.bs/difference b)))))))

(deftest window-bound-excludes-next-fiscal-year
  (let [conn (book)]
    (testing ":through includes FY2026 and excludes the 2027 sale"
      (is (= 4330M (:amount (:ca.pnl/net-income (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (= 4330M (:amount (:ca.pnl/net-income
                             (pnl/compute conn {:from #inst "2026-01-01"
                                                :to #inst "2027-01-01"}))))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:ca.bs/balanced? r))
    (is (= 0M (:amount (:ca.bs/difference r))))
    (is (= #{:ca.bs/total-assets :ca.bs/total-liabilities :ca.bs/total-equity
             :ca.bs/difference :ca.bs/balanced?}
           (set (keys r))))))

(ns kontor.l10n-uk.statements-test
  "The UK profit-and-loss account + balance sheet against a hand-computed
   book (Companies Act 2006 Sch 1 Format 1).

   Every expectation is arithmetic done by hand in the comment block, not
   a value captured from a run — a golden-value test would pass just as
   happily if the definitions double-counted a line. l10n-uk ships no
   chart, so the test installs a minimal UK nominal ledger (the codes the
   pnl/bs definitions target) before booking."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-uk.bs :as bs]
            [kontor.l10n-uk.pnl :as pnl]
            [kontor.l10n-uk.preset :as uk]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private gbp [:kontor.commodity/symbol "GBP"])
(def ^:private gj  [:kontor.journal/code "GJ"])

;; A minimal UK nominal ledger — only the accounts this test posts to,
;; carrying the codes the pnl/bs definitions match. Consumers bring their
;; own richer chart; l10n-uk ships none.
(def ^:private chart
  [["0020" "Fixed-Assets:Plant-and-Machinery" :asset]
   ["0021" "Fixed-Assets:Plant-and-Machinery:Accumulated-Depreciation" :asset]
   ["0040" "Fixed-Assets:Investments" :asset]
   ["1000" "Current-Assets:Stock" :asset]
   ["1100" "Current-Assets:Debtors:Trade" :asset]
   ["1200" "Current-Assets:Bank:Current" :asset]
   ["1300" "Current-Assets:Prepayments" :asset]
   ["2100" "Current-Liabilities:Creditors:Trade" :liability]
   ["2200" "Current-Liabilities:VAT" :liability]
   ["2210" "Current-Liabilities:PAYE-NIC" :liability]
   ["2300" "Current-Liabilities:Accruals" :liability]
   ["2400" "Long-term-Liabilities:Bank-Loan" :liability]
   ["3000" "Capital:Share-Capital" :equity]
   ["3200" "Capital:Retained-Earnings" :equity]
   ["3300" "Capital:Dividends" :equity]
   ["4000" "Income:Sales" :income]
   ["4100" "Income:Services" :income]
   ["4900" "Income:Other-Operating" :income]
   ["4950" "Income:Interest-Receivable" :income]
   ["5000" "Cost-of-Sales:Purchases" :expense]
   ["6000" "Distribution:Carriage" :expense]
   ["6100" "Distribution:Advertising" :expense]
   ["7000" "Admin:Wages" :expense]
   ["7100" "Admin:Rent" :expense]
   ["7200" "Admin:Insurance" :expense]
   ["7300" "Admin:Professional-Fees" :expense]
   ["7400" "Admin:Depreciation" :expense]
   ["8000" "Finance:Interest-Payable" :expense]])

(defn- install-chart! [conn]
  (d/transact conn
              (mapv (fn [[code path type]]
                      {:kontor.account/path      path
                       :kontor.account/code      code
                       :kontor.account/name      path
                       :kontor.account/type      type
                       :kontor.account/active    true
                       :kontor.account/commodity gbp})
                    chart))
  conn)

(defn- post! [conn date debit credit amount]
  (book/entry! conn {:debit-account  [:kontor.account/path debit]
                     :credit-account [:kontor.account/path credit]
                     :amount         amount
                     :commodity      gbp
                     :journal        gj
                     :effective-date date}))

(defn- post-multi! [conn date postings]
  ;; postings: [[path signed-amount] …]; positive = debit; sum-to-zero.
  (book/entry! conn {:postings       (mapv (fn [[path amt]]
                                             {:account   [:kontor.account/path path]
                                              :amount    amt
                                              :commodity gbp})
                                           postings)
                     :commodity      gbp
                     :journal        gj
                     :effective-date date}))

(defn- seed!
  "A structurally complete FY2026 for a UK Ltd: share capital, a bank
   loan, plant and stock bought on credit, an investment, VAT-bearing
   sales of goods and services, COGS, payroll with a PAYE creditor, rent,
   insurance, professional fees (one on credit, one accrued), distribution
   costs, interest both ways, a grant, a prepayment, year-end
   depreciation, a dividend, plus collections and creditor payments."
  [conn]
  (post! conn #inst "2026-01-02" "Current-Assets:Bank:Current" "Capital:Share-Capital" 100000M)
  (post! conn #inst "2026-01-05" "Current-Assets:Bank:Current" "Long-term-Liabilities:Bank-Loan" 30000M)
  (post! conn #inst "2026-01-10" "Fixed-Assets:Plant-and-Machinery" "Current-Liabilities:Creditors:Trade" 24000M)
  (post! conn #inst "2026-01-15" "Current-Assets:Stock" "Current-Liabilities:Creditors:Trade" 20000M)
  (post! conn #inst "2026-02-01" "Fixed-Assets:Investments" "Current-Assets:Bank:Current" 5000M)
  ;; Sale of goods, 20% VAT: net 50000, VAT 10000, gross 60000
  (post-multi! conn #inst "2026-03-01"
               [["Current-Assets:Debtors:Trade" 60000M]
                ["Income:Sales" -50000M]
                ["Current-Liabilities:VAT" -10000M]])
  ;; Services, 20% VAT: net 15000, VAT 3000, gross 18000
  (post-multi! conn #inst "2026-03-05"
               [["Current-Assets:Debtors:Trade" 18000M]
                ["Income:Services" -15000M]
                ["Current-Liabilities:VAT" -3000M]])
  (post! conn #inst "2026-04-10" "Cost-of-Sales:Purchases" "Current-Assets:Stock" 12000M)
  ;; Wages 20000: 16000 net to bank, 4000 PAYE/NIC creditor
  (post-multi! conn #inst "2026-05-01"
               [["Admin:Wages" 20000M]
                ["Current-Assets:Bank:Current" -16000M]
                ["Current-Liabilities:PAYE-NIC" -4000M]])
  (post! conn #inst "2026-05-01" "Admin:Rent" "Current-Assets:Bank:Current" 12000M)
  (post! conn #inst "2026-06-01" "Admin:Insurance" "Current-Assets:Bank:Current" 3000M)
  (post! conn #inst "2026-06-01" "Current-Assets:Prepayments" "Current-Assets:Bank:Current" 1000M)
  (post! conn #inst "2026-06-15" "Admin:Professional-Fees" "Current-Liabilities:Creditors:Trade" 4000M)
  (post! conn #inst "2026-07-01" "Distribution:Advertising" "Current-Assets:Bank:Current" 5000M)
  (post! conn #inst "2026-07-15" "Distribution:Carriage" "Current-Assets:Bank:Current" 2000M)
  (post! conn #inst "2026-08-01" "Current-Assets:Bank:Current" "Income:Interest-Receivable" 800M)
  (post! conn #inst "2026-09-01" "Finance:Interest-Payable" "Current-Assets:Bank:Current" 1500M)
  (post! conn #inst "2026-09-15" "Current-Assets:Bank:Current" "Income:Other-Operating" 1200M)
  (post! conn #inst "2026-10-01" "Current-Assets:Bank:Current" "Current-Assets:Debtors:Trade" 50000M)
  (post! conn #inst "2026-10-15" "Current-Liabilities:Creditors:Trade" "Current-Assets:Bank:Current" 30000M)
  (post! conn #inst "2026-11-01" "Capital:Dividends" "Current-Assets:Bank:Current" 10000M)
  ;; year-end adjusting entries, ON Dec 31 — what an exclusive :to drops
  (post! conn #inst "2026-12-31" "Admin:Depreciation" "Fixed-Assets:Plant-and-Machinery:Accumulated-Depreciation" 4000M)
  (post! conn #inst "2026-12-31" "Admin:Professional-Fees" "Current-Liabilities:Accruals" 500M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (post! conn #inst "2027-02-01" "Current-Assets:Bank:Current" "Income:Sales" 25000M)
  conn)

(defn- book [] (seed! (install-chart! (uk/create-uk-db))))

;; Hand-computed FY2026 (all GBP, VAT excluded from turnover):
;;   Turnover        50000 sales + 15000 services            =  65000
;;   Cost of sales   12000 purchases                         =  12000
;;   Gross profit                                            =  53000
;;   Distribution    2000 carriage + 5000 advertising        =   7000
;;   Admin           20000 wages + 12000 rent + 3000 ins
;;                   + 4500 prof fees (4000+500) + 4000 depn  =  43500
;;   Other op income grant                                   =   1200
;;   Operating profit 53000 − 7000 − 43500 + 1200            =   3700
;;   Interest recv                                           =    800
;;   Interest paid                                           =   1500
;;   Profit before tax 3700 + 800 − 1500                     =   3000
;;
;;   Bank    100000 + 30000 − 5000 − 16000 − 12000 − 3000
;;           − 1000 − 5000 − 2000 + 800 − 1500 + 1200
;;           + 50000 − 30000 − 10000                         =  96500
;;   Debtors 60000 + 18000 − 50000                           =  28000
;;   Stock   20000 − 12000                                   =   8000
;;   Prepay                                                  =   1000
;;   Plant   24000 − 4000 accumulated depreciation           =  20000
;;   Investments                                             =   5000
;;   Assets                                                  = 158500
;;   Trade creditors 24000 + 20000 + 4000 − 30000            =  18000
;;   VAT      10000 + 3000                                   =  13000
;;   PAYE/NIC                                                =   4000
;;   Accruals                                                =    500
;;   Bank loan (long-term)                                   =  30000
;;   Liabilities                                             =  65500
;;   Equity  100000 capital − 10000 dividend + 3000 profit   =  93000
;;   Liab + equity  65500 + 93000                            = 158500  ✓

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "Sch 1 Format 1 sections"
      (is (== 65000M (sub "1")) "turnover, net of VAT")
      (is (== 12000M (sub "2")) "cost of sales")
      (is (== 7000M (sub "3")) "distribution costs")
      (is (== 43500M (sub "4")) "administrative expenses incl. Dec-31 depn + accrual")
      (is (== 1200M (sub "5")) "other operating income")
      (is (== 800M (sub "6")) "interest receivable")
      (is (== 1500M (sub "7")) "interest payable"))
    (testing "derived subtotals"
      (is (== 53000M (:amount (:uk.pnl/gross-profit p))))
      (is (== 3700M (:amount (:uk.pnl/operating-profit p))))
      (is (== 3000M (:amount (:uk.pnl/profit-before-tax p)))))
    (testing "turnover excludes the 13000 of VAT collected (a liability)"
      ;; VAT (2200) is a creditor, so it must not appear in any P&L line.
      (is (== 65000M (sub "1")) "turnover is net, not the 78000 gross invoiced")
      (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
        (is (== 17000M (:amount (fs/line-value b "C" "C.2")))
            "the 13000 VAT + 4000 PAYE/NIC sit in current liabilities, not turnover")))
    (testing "every amount is tagged GBP, not the engine's :EUR default (F5)"
      (is (= #{:GBP} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3" "4" "5" "6" "7"])))
      (is (= :GBP (:commodity (:uk.pnl/profit-before-tax p)))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (== 158500M (:amount (:uk.bs/total-assets b))))
    (is (== 65500M (:amount (:uk.bs/total-liabilities b))))
    (is (== 93000M (:amount (:uk.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (== 0M (:amount (:uk.bs/difference b))))
      (is (:uk.bs/balanced? b)))
    (testing "accumulated depreciation nets against gross cost"
      ;; 24000 plant − 4000 accumulated = 20000 shown
      (is (== 20000M (:amount (fs/line-value b "A" "A.2")))))
    (testing "VAT collected is a current-liability creditor, not equity/turnover"
      ;; C.2 = 13000 VAT + 4000 PAYE/NIC
      (is (== 17000M (:amount (fs/line-value b "C" "C.2")))))
    (is (= :GBP (:commodity (:uk.bs/total-assets b))))))

(deftest current-period-result-sits-in-equity-before-close
  ;; l10n-uk ships no closing helper, so section E must carry the running
  ;; result via E.5/E.6 for the interim BS to balance.
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})
        net (+ (:amount (fs/line-value b "E" "E.5"))
               (:amount (fs/line-value b "E" "E.6")))]
    (is (== 67000M (:amount (fs/line-value b "E" "E.5"))) "current-period income (incl. interest + grant)")
    (is (== -64000M (:amount (fs/line-value b "E" "E.6"))) "current-period expenses, negated")
    (is (== 3000M net) "their sum is the year's profit before tax")
    (is (== 0M (:amount (fs/line-value b "E" "E.3"))) "no retained earnings brought forward")))

(deftest window-bound-is-inclusive-via-through
  (let [conn (book)]
    (testing ":through includes the Dec-31 entries and excludes FY2027"
      (is (== 3000M (:amount (:uk.pnl/profit-before-tax (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (== 3000M (:amount (:uk.pnl/profit-before-tax
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to #inst "2027-01-01"}))))))
    (testing "an exclusive Dec-31 bound silently drops the Dec-31 depn + accrual"
      ;; 4000 depreciation + 500 accrual go missing → profit reads 4500 too high
      (is (== 7500M (:amount (:uk.pnl/profit-before-tax
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to #inst "2026-12-31"}))))))
    (testing ":to and :through together is an error, not silent precedence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pnl/compute conn {:to #inst "2027-01-01"
                                      :through #inst "2026-12-31"}))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:uk.bs/balanced? r))
    (is (== 0M (:amount (:uk.bs/difference r))))
    (is (= #{:uk.bs/total-assets :uk.bs/total-liabilities :uk.bs/total-equity
             :uk.bs/difference :uk.bs/balanced?}
           (set (keys r))))))

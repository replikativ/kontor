(ns kontor.l10n-cn.statements-test
  "The CN 利润表 (income statement) + 资产负债表 (balance sheet) against a
   hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.l10n-cn.bs :as bs]
            [kontor.l10n-cn.pnl :as pnl]
            [kontor.l10n-cn.preset :as cn]
            [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private cny [:kontor.commodity/symbol "CNY"])
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- p [path] [:kontor.account/path path])

(defn- post2!
  "Two-leg entry: debit `d`, credit `c`, for `amount`."
  [conn date d c amount]
  (book/entry! conn {:debit-account  (p d)
                     :credit-account (p c)
                     :amount         amount
                     :commodity      cny
                     :journal        gj
                     :effective-date date}))

(defn- postn!
  "Multi-leg entry: `legs` is a vector of [path signed-amount], positive =
   debit. Amounts must sum to zero."
  [conn date legs]
  (book/entry! conn {:postings       (mapv (fn [[path amt]] {:account (p path) :amount amt}) legs)
                     :commodity      cny
                     :journal        gj
                     :effective-date date}))

(defn- seed!
  "A structurally complete FY2026 for a CN trading company: owner capital,
   a cash equipment purchase, a long-term loan, an inventory purchase on
   credit with input VAT, a credit sale with output VAT, COGS out of
   inventory, admin + financial expense, investment income, non-operating
   income and expense, an AR collection, year-end depreciation and an
   income-tax provision (both posted ON Dec 31), plus a next-year sale that
   must never appear in FY2026."
  [conn]
  (post2! conn #inst "2026-01-02" "Assets:Bank:Deposits" "Equity:PaidInCapital" 1000000M)
  (post2! conn #inst "2026-01-10" "Assets:Fixed" "Assets:Bank:Deposits" 300000M)
  (post2! conn #inst "2026-01-15" "Assets:Bank:Deposits" "Liabilities:Loan:Long" 200000M)
  ;; inventory purchase on credit, 13% input VAT (进项税额)
  (postn! conn #inst "2026-02-01"
          [["Assets:Inventory:Finished" 500000M]
           ["Assets:Tax:VAT:InputVAT"    65000M]
           ["Liabilities:Payable"      -565000M]])
  ;; credit sale, 13% output VAT (销项税额) — revenue is booked NET of VAT
  (postn! conn #inst "2026-03-01"
          [["Assets:Receivable"          904000M]
           ["Income:Sales:13pct"        -800000M]
           ["Liabilities:Tax:VAT:OutputVAT" -104000M]])
  (post2! conn #inst "2026-04-10" "Expense:COGS" "Assets:Inventory:Finished" 400000M)
  (post2! conn #inst "2026-05-01" "Expense:Admin" "Assets:Bank:Deposits" 120000M)
  (post2! conn #inst "2026-06-01" "Expense:Financial" "Assets:Bank:Deposits" 10000M)
  (post2! conn #inst "2026-07-01" "Assets:Bank:Deposits" "Income:Investment" 30000M)
  (post2! conn #inst "2026-08-01" "Assets:Bank:Deposits" "Income:NonOperating" 5000M)
  (post2! conn #inst "2026-09-01" "Expense:NonOperating" "Assets:Bank:Deposits" 8000M)
  (post2! conn #inst "2026-10-01" "Assets:Bank:Deposits" "Assets:Receivable" 500000M)
  ;; year-end entries, ON Dec 31 — the postings an exclusive :to drops
  (post2! conn #inst "2026-12-31" "Expense:Admin" "Assets:Fixed:AccumDep" 20000M)
  (post2! conn #inst "2026-12-31" "Expense:IncomeTax" "Liabilities:Tax:CIT" 69250M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (post2! conn #inst "2027-02-01" "Assets:Receivable" "Income:Sales:13pct" 100000M)
  conn)

(defn- book [] (seed! (cn/create-cn-db)))

;; Hand-computed FY2026 (all CNY):
;;   营业收入 Operating revenue                                     = 800000
;;   营业成本 Operating costs (COGS)                                = 400000
;;   税金及附加 / 销售费用                                          =      0
;;   管理费用 Admin  120000 + 20000 depreciation                   = 140000
;;   财务费用 Financial                                            =  10000
;;   投资收益 Investment income                                     =  30000
;;   营业利润 Operating profit  800000−400000−140000−10000+30000    = 280000
;;   营业外收入 Non-operating income                                =   5000
;;   营业外支出 Non-operating expense                               =   8000
;;   利润总额 Total profit  280000+5000−8000                        = 277000
;;   所得税费用 Income tax  25% × 277000                            =  69250
;;   净利润 Net profit  277000−69250                                = 207750
;;
;;   货币资金 Bank  1000000−300000+200000−120000−10000+30000
;;                  +5000−8000+500000                              = 1297000
;;   应收账款 AR    904000−500000                                   =  404000
;;   存货 Inventory 500000−400000                                   =  100000
;;   进项税额 Input VAT                                             =   65000
;;   流动资产 Current assets                                        = 1866000
;;   固定资产净额 Fixed net  300000−20000                           =  280000
;;   资产合计 Total assets                                          = 2146000
;;   应付账款 AP                                                    =  565000
;;   销项税额 Output VAT                                            =  104000
;;   应交所得税 CIT payable                                         =   69250
;;   流动负债 Current liabilities                                   =  738250
;;   长期借款 Long-term borrowings                                  =  200000
;;   负债合计 Total liabilities                                     =  938250
;;   实收资本 Paid-in capital                                       = 1000000
;;   本期利润 Current-period result                                 =  207750
;;   所有者权益 Total equity                                        = 1207750
;;   负债 + 权益                                                    = 2146000  ✓

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [pl  (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal pl %))]
    (testing "sections"
      (is (= 800000M (sub "1")) "operating revenue, net of the 104000 output VAT")
      (is (= 400000M (sub "2")) "operating costs")
      (is (= 0M (sub "3")) "taxes and surcharges")
      (is (= 0M (sub "4")) "selling expenses")
      (is (= 140000M (sub "5")) "admin, including Dec-31 depreciation")
      (is (= 10000M (sub "6")) "financial expenses")
      (is (= 30000M (sub "7")) "investment income")
      (is (= 5000M (sub "8")) "non-operating income")
      (is (= 8000M (sub "9")) "non-operating expense")
      (is (= 69250M (sub "10")) "income tax expense"))
    (testing "derived ASBE subtotals"
      (is (= 800000M (:amount (:cn.pnl/operating-revenue pl))))
      (is (= 280000M (:amount (:cn.pnl/operating-profit pl))))
      (is (= 277000M (:amount (:cn.pnl/total-profit pl))))
      (is (= 207750M (:amount (:cn.pnl/net-profit pl)))))
    (testing "revenue excludes the collected output VAT (a liability)"
      ;; 800000 net, not 904000 gross — VAT lives on 2221.01.01, never a 5xxx account
      (is (not= 904000M (:amount (:cn.pnl/operating-revenue pl)))))
    (testing "every amount is tagged CNY, not the engine's :EUR default (F5)"
      (is (= #{:CNY}
             (into #{} (map (comp :commodity (partial fs/section-subtotal pl)))
                   ["1" "2" "5" "6" "7" "8" "9" "10"])))
      (is (= :CNY (:commodity (:cn.pnl/net-profit pl)))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 2146000M (:amount (:cn.bs/total-assets b))))
    (is (= 938250M  (:amount (:cn.bs/total-liabilities b))))
    (is (= 1207750M (:amount (:cn.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (money/zero? (:cn.bs/difference b)))
      (is (= 0M (:amount (:cn.bs/difference b))))
      (is (:cn.bs/balanced? b)))
    (testing "accumulated depreciation nets against gross fixed assets"
      ;; 300000 equipment − 20000 accumulated = 280000 shown
      (is (= 280000M (:amount (fs/line-value b "B" "B.1")))))
    (testing "input VAT is presented as an asset; taxes payable groups output VAT + CIT"
      (is (= 65000M (:amount (fs/line-value b "A" "A.7"))))
      ;; 应交税费 = 销项税额 104000 + 应交所得税 69250 = 173250
      (is (= 173250M (:amount (fs/line-value b "D" "D.6")))))
    (testing "amounts are tagged CNY (F5)"
      (is (= :CNY (:commodity (:cn.bs/total-assets b)))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE, :through is the inclusive form — the Dec-31
  ;; depreciation + tax provision must be INCLUDED, and FY2027 EXCLUDED.
  (let [conn (book)]
    (testing ":through includes the Dec-31 entries and excludes FY2027"
      (is (= 207750M (:amount (:cn.pnl/net-profit (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (= 207750M (:amount (:cn.pnl/net-profit
                               (pnl/compute conn {:from #inst "2026-01-01"
                                                  :to   #inst "2027-01-01"}))))))
    (testing "an exclusive Dec-31 :to drops the year-end entries"
      ;; without the 20000 depreciation and 69250 tax provision the pre-close
      ;; net would be 277000 (still no tax) − 20000 = 297000
      (is (= 297000M (:amount (:cn.pnl/net-profit
                               (pnl/compute conn {:from #inst "2026-01-01"
                                                  :to   #inst "2026-12-31"}))))))))

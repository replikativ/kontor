(ns kontor.l10n-us.statements-test
  "The US income statement + balance sheet against a hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-us.bs :as bs]
            [kontor.l10n-us.closing :as closing]
            [kontor.l10n-us.pnl :as pnl]
            [kontor.l10n-us.preset :as us]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private usd [:kontor.commodity/symbol "USD"])
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- post! [conn date debit credit amount]
  (book/entry! conn {:debit-account  [:kontor.account/path debit]
                     :credit-account [:kontor.account/path credit]
                     :amount         amount
                     :commodity      usd
                     :journal        gj
                     :effective-date date}))

(defn- seed!
  "A structurally complete year: owner capital, a financed truck bought on
   credit, inventory on account, product + service + subscription revenue,
   a sales return, COGS out of inventory, payroll, rent, insurance,
   professional fees, year-end depreciation, interest both ways, a
   collection and an owner draw. Touches every account class the two
   statements cover."
  [conn]
  (post! conn #inst "2026-01-02" "Assets:Bank:Checking" "Equity:Owner-Contributions" 100000M)
  (post! conn #inst "2026-01-10" "Assets:Fixed:Vehicles" "Liabilities:Long-term-Debt:Notes" 40000M)
  (post! conn #inst "2026-01-15" "Assets:Inventory" "Liabilities:Payable" 30000M)
  (post! conn #inst "2026-03-01" "Assets:Receivable" "Income:Sales" 80000M)
  (post! conn #inst "2026-03-05" "Assets:Receivable" "Income:Services" 25000M)
  (post! conn #inst "2026-03-20" "Assets:Bank:Checking" "Income:Subscription" 12000M)
  ;; contra-revenue — a DEBIT to an :income account
  (post! conn #inst "2026-04-02" "Income:Sales:Returns-and-Allowances" "Assets:Receivable" 3000M)
  (post! conn #inst "2026-04-10" "Expenses:COGS:Materials" "Assets:Inventory" 22000M)
  (post! conn #inst "2026-05-01" "Expenses:Payroll:Wages" "Assets:Bank:Checking" 35000M)
  (post! conn #inst "2026-05-01" "Expenses:Rent" "Assets:Bank:Checking" 18000M)
  (post! conn #inst "2026-06-01" "Expenses:Insurance" "Assets:Bank:Checking" 4000M)
  (post! conn #inst "2026-06-15" "Expenses:Professional" "Liabilities:Payable" 6000M)
  ;; year-end adjusting entry, ON Dec 31 — the posting an exclusive :to drops
  (post! conn #inst "2026-12-31" "Expenses:Depreciation"
         "Assets:Fixed:Vehicles:Accumulated-Depreciation" 8000M)
  (post! conn #inst "2026-07-01" "Assets:Bank:Checking" "Other-Income:Interest" 500M)
  (post! conn #inst "2026-07-01" "Other-Expense:Interest" "Assets:Bank:Checking" 2400M)
  (post! conn #inst "2026-09-01" "Assets:Bank:Checking" "Assets:Receivable" 70000M)
  (post! conn #inst "2026-11-01" "Equity:Owner-Distributions" "Assets:Bank:Checking" 15000M)
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (post! conn #inst "2027-02-01" "Assets:Bank:Checking" "Income:Sales" 50000M)
  conn)

(defn- book [] (seed! (us/create-us-db)))

;; Hand-computed FY2026:
;;   Revenue          80000 + 25000 + 12000 − 3000 return       = 114000
;;   COGS                                                       =  22000
;;   Gross profit                                               =  92000
;;   Operating exp    35000 + 18000 + 4000 + 6000 + 8000 depn   =  71000
;;   Operating income                                           =  21000
;;   Other            +500 interest income − 2400 interest exp  =  −1900
;;   Net income                                                 =  19100
;;
;;   Cash      100000 + 12000 + 500 + 70000 − 35000 − 18000
;;             − 4000 − 2400 − 15000                            = 108100
;;   AR        80000 + 25000 − 3000 − 70000                     =  32000
;;   Inventory 30000 − 22000                                    =   8000
;;   Vehicles  40000 − 8000 accumulated depreciation            =  32000
;;   Assets                                                     = 180100
;;   Liabs     AP 30000 + 6000, LT notes 40000                  =  76000
;;   Equity    100000 contributed − 15000 draws + 19100 earned  = 104100

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (= 114000M (sub "1")) "revenue, net of the 3000 return")
      (is (= 22000M (sub "2")) "cost of goods sold")
      (is (= 71000M (sub "3")) "operating expenses, including Dec-31 depreciation")
      (is (= 500M (sub "4")) "other income")
      (is (= 2400M (sub "5")) "other expense"))
    (testing "derived multi-step subtotals"
      (is (= 92000M (:amount (:us.pnl/gross-profit p))))
      (is (= 21000M (:amount (:us.pnl/operating-income p))))
      (is (= 19100M (:amount (:us.pnl/net-income p)))))
    (testing "every amount is tagged USD, not the engine's :EUR default"
      (is (= #{:USD} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3" "4" "5"])))
      (is (= :USD (:commodity (:us.pnl/net-income p)))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 180100M (:amount (:us.bs/total-assets b))))
    (is (= 76000M (:amount (:us.bs/total-liabilities b))))
    (is (= 104100M (:amount (:us.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (= 0M (:amount (:us.bs/difference b))))
      (is (:us.bs/balanced? b)))
    (testing "accumulated depreciation nets against gross PP&E"
      ;; 40000 truck − 8000 accumulated = 32000 shown
      (is (= 32000M (:amount (fs/line-value b "B" "B.2")))))
    (is (= :USD (:commodity (:us.bs/total-assets b))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE and compute-statement does not forward :through, so
  ;; pnl/resolve-window has to translate it. Both halves matter: Dec-31
  ;; entries must be INCLUDED, and next-year entries must be EXCLUDED.
  (let [conn (book)]
    (testing ":through includes the Dec-31 depreciation and excludes FY2027"
      (is (= 19100M (:amount (:us.pnl/net-income (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (= 19100M (:amount (:us.pnl/net-income
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to #inst "2027-01-01"}))))))
    (testing "an exclusive Dec-31 bound silently drops the Dec-31 entry"
      ;; documents the trap rather than endorsing it: 8000 of depreciation
      ;; goes missing, so net income reads 8000 too high
      (is (= 27100M (:amount (:us.pnl/net-income
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to #inst "2026-12-31"}))))))
    (testing ":to and :through together is an error, not a silent precedence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pnl/compute conn {:to #inst "2027-01-01"
                                      :through #inst "2026-12-31"}))))))

(deftest balance-sheet-survives-the-fiscal-year-close
  ;; Before the close the period result sits in the 4xxx-7xxx accounts and
  ;; section F carries it via lines F.4/F.5. After close-us-fiscal-year!
  ;; those accounts are zeroed into 3100 — the same statement must still
  ;; balance, with retained earnings now carrying the amount.
  (let [conn (book)
        _ (d/transact conn [{:kontor.period/start #inst "2026-01-01"
                             :kontor.period/end   #inst "2027-01-01"
                             :kontor.period/name  "FY2026"}])
        period (d/q '[:find ?e . :where [?e :kontor.period/name "FY2026"]] @conn)
        before (bs/compute conn {:through #inst "2026-12-31"})
        net-of (fn [b] (+ (:amount (fs/line-value b "F" "F.4"))
                          (:amount (fs/line-value b "F" "F.5"))))]
    (testing "before the close, section F carries the result on F.4 + F.5"
      ;; F.4 is revenue gross (114000 sales + 500 interest), F.5 the
      ;; negated expense side; their sum is the period's net income.
      (is (= 114500M (:amount (fs/line-value before "F" "F.4"))))
      (is (= -95400M (:amount (fs/line-value before "F" "F.5"))))
      (is (= 19100M (net-of before)) "net income, held outside retained earnings")
      (is (= 0M (:amount (fs/line-value before "F" "F.3"))) "retained earnings still empty")
      (is (:us.bs/balanced? before)))
    (closing/close-us-fiscal-year! conn {:period-eid period})
    (let [after (bs/compute conn {:through #inst "2026-12-31"})]
      (is (:us.bs/balanced? after) "still balances after the close")
      (is (= 180100M (:amount (:us.bs/total-assets after))) "assets unchanged")
      (is (= 104100M (:amount (:us.bs/total-equity after))) "equity unchanged")
      (is (= 19100M (:amount (fs/line-value after "F" "F.3")))
          "retained earnings now carries the year's result")
      (is (= 0M (:amount (fs/line-value after "F" "F.4"))) "current revenue zeroed")
      (is (= 0M (:amount (fs/line-value after "F" "F.5"))) "current expenses zeroed"))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:us.bs/balanced? r))
    (is (= 0M (:amount (:us.bs/difference r))))
    (is (= #{:us.bs/total-assets :us.bs/total-liabilities :us.bs/total-equity
             :us.bs/difference :us.bs/balanced?}
           (set (keys r))))))

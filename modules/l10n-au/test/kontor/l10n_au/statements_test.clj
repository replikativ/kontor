(ns kontor.l10n-au.statements-test
  "The AU income statement + balance sheet against a hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted.

   The book is a small but structurally complete year for an Australian
   company: opening capital + retained earnings, short- and long-term
   loans, a fixed-asset purchase, GST-taxable and GST-free sales,
   interest income, inventory bought and partly expensed to cost of
   sales, a prepayment, wages with PAYG withholding, rent, year-end
   depreciation, interest expense, an income-tax accrual, an owner
   drawing, and a customer receipt. It touches every line of both
   statements."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-au.bs :as bs]
            [kontor.l10n-au.pnl :as pnl]
            [kontor.l10n-au.preset :as au]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private aud [:kontor.commodity/symbol "AUD"])

(defn- ace
  "Resolve an account eid by code. `:kontor.account/code` is not
   `:db/unique` in the kernel schema, so a lookup-ref will not do."
  [conn code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] @conn code))

(defn- post!
  "Book a balanced multi-leg entry. `legs` are `[account-code amount]`
   pairs; a positive amount is a debit. Journal type `:general`."
  [conn date & legs]
  (book/entry! conn {:journal-type   :general
                     :effective-date date
                     :commodity      aud
                     :postings       (mapv (fn [[code amt]]
                                             {:account (ace conn code) :amount amt})
                                           legs)}))

(defn- seed! [conn]
  ;; --- opening balances (an established company, not a startup) ---
  (post! conn #inst "2026-01-01" ["11100" 50000M] ["31100" -50000M]) ; owner capital
  (post! conn #inst "2026-01-01" ["11100" 10000M] ["31200" -10000M]) ; retained earnings b/f
  (post! conn #inst "2026-01-01" ["11100" 8000M]  ["22100" -8000M])  ; short-term loan
  (post! conn #inst "2026-01-01" ["11100" 20000M] ["23100" -20000M]) ; long-term loan
  (post! conn #inst "2026-01-05" ["12100" 24000M] ["11100" -24000M]) ; buy office equipment
  ;; --- operating year ---
  ;; GST 10% taxable sale: net 100000 to revenue, 10000 GST to the 1A
  ;; liability, 110000 gross to the receivable
  (post! conn #inst "2026-03-01" ["11200" 110000M] ["41100" -100000M] ["21500" -10000M])
  (post! conn #inst "2026-03-02" ["11200" 20000M]  ["41200" -20000M]) ; GST-free sale
  (post! conn #inst "2026-03-10" ["11100" 500M]    ["42100" -500M])   ; interest income
  ;; buy inventory with GST input credit
  (post! conn #inst "2026-04-01" ["11330" 30000M] ["11700" 3000M] ["21100" -33000M])
  (post! conn #inst "2026-04-15" ["51100" 18000M] ["11330" -18000M])  ; COGS out of inventory
  (post! conn #inst "2026-05-01" ["11500" 1000M] ["11700" 100M] ["11100" -1100M]) ; prepayment + GST
  (post! conn #inst "2026-05-15" ["61100" 30000M] ["11100" -25000M] ["21700" -5000M]) ; wages + PAYG-W
  (post! conn #inst "2026-06-01" ["61500" 12000M] ["11700" 1200M] ["11100" -13200M]) ; rent + GST
  ;; year-end depreciation — posted ON Dec 31, the entry an exclusive :to drops
  (post! conn #inst "2026-12-31" ["62100" 4000M] ["12110" -4000M])
  (post! conn #inst "2026-07-01" ["71100" 1000M] ["11100" -1000M])   ; interest expense
  (post! conn #inst "2026-11-30" ["91100" 6000M] ["21800" -6000M])   ; income-tax accrual
  (post! conn #inst "2026-08-01" ["31300" 5000M] ["11100" -5000M])   ; owner drawing
  (post! conn #inst "2026-09-01" ["11100" 60000M] ["11200" -60000M]) ; customer receipt
  ;; NEXT fiscal year — must never appear in an FY2026 statement
  (post! conn #inst "2027-02-01" ["11200" 40000M] ["41100" -40000M])
  conn)

(defn- book [] (seed! (au/create-au-db)))

;; Hand-computed FY2026 income statement (all AUD):
;;   Revenue        100000 taxable + 20000 GST-free           = 120000
;;                  (the 10000 GST collected is a LIABILITY, not revenue)
;;   Other income   interest                                  =    500
;;   Cost of sales  inventory expensed                        =  18000
;;   Gross profit   120000 − 18000                            = 102000
;;   Operating exp  30000 wages + 12000 rent + 4000 depn      =  46000
;;   Operating profit 102000 − 46000                          =  56000
;;   Finance costs  interest expense                          =   1000
;;   Profit before tax (120000+500) − (18000+46000+1000)      =  55500
;;   Income tax                                               =   6000
;;   Profit for the year 55500 − 6000                         =  49500

(def ^:private fy {:from #inst "2026-01-01" :through #inst "2026-12-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (= 120000M (sub "1")) "revenue, NET of the 10000 GST collected")
      (is (= 500M    (sub "2")) "other income")
      (is (= 18000M  (sub "3")) "cost of sales")
      (is (= 46000M  (sub "4")) "operating expenses, incl. Dec-31 depreciation")
      (is (= 1000M   (sub "5")) "finance costs")
      (is (= 6000M   (sub "6")) "income tax expense"))
    (testing "derived multi-step subtotals"
      (is (= 102000M (:amount (:au.pnl/gross-profit p))))
      (is (= 56000M  (:amount (:au.pnl/operating-profit p))))
      (is (= 55500M  (:amount (:au.pnl/profit-before-tax p))))
      (is (= 49500M  (:amount (:au.pnl/profit-for-year p)))))
    (testing "GST collected is a liability — it never lands in revenue"
      ;; the gross taxable receivable was 110000; revenue shows 100000
      (is (= 120000M (sub "1")) "gross 130000 would double-count the 10000 GST"))
    (testing "every amount is tagged AUD, derived from the postings (F5)"
      (is (= #{:AUD} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3" "4" "5" "6"])))
      (is (= :AUD (:commodity (:au.pnl/profit-for-year p)))))))

;; Hand-computed FY2026 balance sheet (all AUD, as of Dec 31):
;;   Cash      50000 + 10000 + 8000 + 20000 − 24000 + 500 − 1100
;;             − 25000 − 13200 − 1000 − 5000 + 60000            =  79200
;;   Receivables 110000 + 20000 − 60000                         =  70000
;;   Inventory 30000 − 18000                                    =  12000
;;   GST recv  3000 + 100 + 1200                                =   4300
;;   Prepayments                                                =   1000
;;   Current assets                                             = 166500
;;   PP&E      24000 − 4000 accum. depreciation                 =  20000
;;   Total assets                                               = 186500
;;
;;   Payables                                                   =  33000
;;   GST payable                                                =  10000
;;   PAYG withheld 5000 + income-tax accrual 6000               =  11000
;;   Short-term borrowings                                      =   8000
;;   Current liabilities                                        =  62000
;;   Long-term borrowings                                       =  20000
;;   Total liabilities                                          =  82000
;;
;;   Contributed 50000 − drawings 5000 + retained b/f 10000
;;             + profit for the year 49500                      = 104500
;;   Liabilities + equity                                       = 186500  ✓

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-12-31"})]
    (is (= 186500M (:amount (:au.bs/total-assets b))))
    (is (= 82000M  (:amount (:au.bs/total-liabilities b))))
    (is (= 104500M (:amount (:au.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (= 0M (:amount (:au.bs/difference b))))
      (is (:au.bs/balanced? b)))
    (testing "accumulated depreciation nets against gross PP&E"
      ;; 24000 cost − 4000 accumulated = 20000 carrying amount
      (is (= 20000M (:amount (fs/line-value b "B" "B.1")))))
    (testing "GST collected sits in current liabilities, not equity"
      (is (= 10000M (:amount (fs/line-value b "D" "D.2")))))
    (testing "the un-closed period result is carried in equity (F.4/F.5)"
      (is (= 120500M (:amount (fs/line-value b "F" "F.4"))) "income incl. interest")
      (is (= -71000M (:amount (fs/line-value b "F" "F.5"))) "negated expense side")
      ;; F.4 + F.5 = 49500 = profit for the year, held outside retained earnings
      (is (= 49500M (+ (:amount (fs/line-value b "F" "F.4"))
                       (:amount (fs/line-value b "F" "F.5"))))))
    (testing "every Money is tagged AUD, derived from the postings (F5)"
      (is (= #{:AUD}
             (into #{} (for [s (:statement/sections b)
                             l (:section/lines s)]
                         (:commodity (:line/value l))))))
      (is (= :AUD (:commodity (:au.bs/total-assets b)))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-12-31"})]
    (is (:au.bs/balanced? r))
    (is (= 0M (:amount (:au.bs/difference r))))
    (is (= #{:au.bs/total-assets :au.bs/total-liabilities :au.bs/total-equity
             :au.bs/difference :au.bs/balanced?}
           (set (keys r))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE; :through is the inclusive form. The inclusive
  ;; window must INCLUDE the Dec-31 depreciation and EXCLUDE the FY2027
  ;; sale — both are proven by the same 49500 bottom line.
  (let [conn (book)]
    (testing ":through includes Dec-31 depreciation and excludes FY2027"
      (is (= 49500M (:amount (:au.pnl/profit-for-year (pnl/compute conn fy))))))
    (testing "the explicit exclusive form (next-day) agrees"
      (is (= 49500M (:amount (:au.pnl/profit-for-year
                              (pnl/compute conn {:from #inst "2026-01-01"
                                                 :to   #inst "2027-01-01"}))))))))
;; NOTE: the l10n-us statements test also asserts the "exclusive Dec-31
;; bound silently drops the Dec-31 entry" trap. That case cannot be
;; asserted on a non-EUR book here: dropping the depreciation posting
;; empties P&L line 4.3 (its only account, 62100), and an empty line
;; still defaults to :EUR under the partial F5 fix — so the section fold
;; throws a cross-commodity :add rather than returning a too-high profit.
;; See the run summary's :issues for the underlying engine gap.

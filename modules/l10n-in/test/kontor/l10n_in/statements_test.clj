(ns kontor.l10n-in.statements-test
  "The IN Schedule III Statement of P&L + Balance Sheet against a
   hand-computed book.

   Every expectation below is arithmetic done by hand in the comment
   block, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted.

   The book runs the Indian fiscal year 2025-26 (1 April 2025 →
   31 March 2026). GST is booked as the statute requires: output GST
   collected from buyers is a LIABILITY (33xxxx), input GST paid to
   vendors is a recoverable ASSET (13xxxx) — neither ever touches the
   revenue or expense lines."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.book :as book]
            [kontor.l10n-in.bs :as bs]
            [kontor.l10n-in.pnl :as pnl]
            [kontor.l10n-in.preset :as in]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private inr [:kontor.commodity/symbol "INR"])
(def ^:private gj  [:kontor.journal/code "GJ"])

(defn- post!
  "Two-leg entry: debit `dr`, credit `cr` for `amount` INR."
  [conn date dr cr amount]
  (book/entry! conn {:debit-account  [:kontor.account/path dr]
                     :credit-account [:kontor.account/path cr]
                     :amount         amount
                     :commodity      inr
                     :journal        gj
                     :effective-date date}))

(defn- post-legs!
  "Multi-leg entry. `legs` is a vector of `[path amount]`; positive is a
   debit, negative a credit. Must sum to zero."
  [conn date legs]
  (book/entry! conn {:postings       (mapv (fn [[path amt]]
                                             {:account [:kontor.account/path path]
                                              :amount  amt})
                                           legs)
                     :commodity      inr
                     :journal        gj
                     :effective-date date}))

;; account paths (from resources/kontor/l10n_in/chart.edn)
(def ^:private bank      "Assets:Current:Cash-and-Equivalents:Bank")
(def ^:private capital   "Equity:Share-Capital:Equity-Shares")
(def ^:private term-loan "Liabilities:Non-Current:Borrowings:Term-Loan")
(def ^:private plant     "Assets:Non-Current:PPE:Plant-and-Machinery")
(def ^:private accum-dep "Assets:Non-Current:PPE:Accumulated-Depreciation")
(def ^:private purchases "Expenses:Cost-of-Materials:Purchases:Domestic")
(def ^:private itc-cgst  "Assets:ITC:CGST")
(def ^:private itc-sgst  "Assets:ITC:SGST")
(def ^:private payables  "Liabilities:Current:Trade-Payables:Domestic")
(def ^:private recv      "Assets:Current:Trade-Receivables:Domestic")
(def ^:private sales     "Revenue:Operations:Sales")
(def ^:private services  "Revenue:Operations:Services")
(def ^:private out-cgst  "Liabilities:Output-GST:CGST")
(def ^:private out-sgst  "Liabilities:Output-GST:SGST")
(def ^:private salaries  "Expenses:Employee-Benefits:Salaries")
(def ^:private rent      "Expenses:Other-Expenses:Rent")
(def ^:private fin-int   "Expenses:Finance-Costs:Interest")
(def ^:private int-inc   "Revenue:Other-Income:Interest")
(def ^:private deprec    "Expenses:Depreciation-and-Amortization")

(defn- seed!
  "A structurally complete FY2025-26: equity, a financed plant purchase,
   an intra-state credit purchase with 18% input GST, an intra-state
   credit sale and a cash service both with 18% output GST, payroll,
   rent, interest both ways, a collection, a supplier payment and
   year-end depreciation ON 31 March. Touches every account class both
   statements cover, and every GST leg lands on its own statutory head."
  [conn]
  (post! conn #inst "2025-04-01" bank capital 5000000M)     ; share capital
  (post! conn #inst "2025-04-05" bank term-loan 2000000M)   ; term loan drawdown
  (post! conn #inst "2025-04-10" plant bank 3000000M)       ; buy plant & machinery
  ;; purchase ₹10,00,000 + 9% CGST + 9% SGST input credit, on account
  (post-legs! conn #inst "2025-05-01"
              [[purchases 1000000M] [itc-cgst 90000M] [itc-sgst 90000M]
               [payables -1180000M]])
  ;; credit sale ₹20,00,000 + 9% CGST + 9% SGST output tax
  (post-legs! conn #inst "2025-06-15"
              [[recv 2360000M] [sales -2000000M]
               [out-cgst -180000M] [out-sgst -180000M]])
  ;; cash service ₹5,00,000 + 9% CGST + 9% SGST output tax
  (post-legs! conn #inst "2025-07-20"
              [[bank 590000M] [services -500000M]
               [out-cgst -45000M] [out-sgst -45000M]])
  (post! conn #inst "2025-08-01" salaries bank 800000M)     ; payroll
  (post! conn #inst "2025-09-01" rent bank 240000M)         ; rent
  (post! conn #inst "2025-10-01" fin-int bank 120000M)      ; interest on loan
  (post! conn #inst "2025-11-01" bank int-inc 30000M)       ; interest income
  (post! conn #inst "2025-12-01" bank recv 2000000M)        ; collection
  (post! conn #inst "2026-01-15" payables bank 1000000M)    ; pay supplier
  ;; year-end depreciation, ON 31 March — the posting an exclusive :to drops
  (post! conn #inst "2026-03-31" deprec accum-dep 300000M)
  ;; NEXT fiscal year — must never appear in an FY2025-26 statement
  (post! conn #inst "2026-05-01" bank sales 1000000M)
  conn)

(defn- book [] (seed! (in/create-in-db)))

;; Hand-computed FY2025-26:
;;   Revenue from operations  sales 2,000,000 + services 500,000  = 2,500,000
;;   Other income             interest                             =    30,000
;;   Total income                                                  = 2,530,000
;;   Cost of materials        purchases                            = 1,000,000
;;   Employee benefits        salaries                             =   800,000
;;   Finance costs            interest on loan                     =   120,000
;;   Depreciation             year-end                             =   300,000
;;   Other expenses           rent                                 =   240,000
;;   Total expenses                                                = 2,460,000
;;   Profit before tax                                             =    70,000
;;
;;   GST collected (450,000 output) sits in liabilities, NOT revenue;
;;   GST paid (180,000 ITC) sits in assets, NOT expense.
;;
;;   Bank  5,000,000 + 2,000,000 − 3,000,000 + 590,000 − 800,000
;;         − 240,000 − 120,000 + 30,000 + 2,000,000 − 1,000,000     = 4,460,000
;;   Receivables 2,360,000 − 2,000,000                              =   360,000
;;   ITC         90,000 CGST + 90,000 SGST                          =   180,000
;;   Current assets                                                 = 5,000,000
;;   Plant 3,000,000 − 300,000 accumulated depreciation            = 2,700,000
;;   Total assets                                                   = 7,700,000
;;   Trade payables 1,180,000 − 1,000,000                           =   180,000
;;   Output GST 225,000 CGST + 225,000 SGST                         =   450,000
;;   Current liabilities                                            =   630,000
;;   Term loan (non-current)                                        = 2,000,000
;;   Total liabilities                                              = 2,630,000
;;   Share capital 5,000,000 + current-period profit 70,000        = 5,070,000
;;   assets 7,700,000 = liabilities 2,630,000 + equity 5,070,000    ✓

(def ^:private fy {:from #inst "2025-04-01" :through #inst "2026-03-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "Schedule III sections"
      (is (= 2500000M (sub "I"))   "revenue from operations, GST excluded")
      (is (= 30000M   (sub "II"))  "other income")
      (is (= 1000000M (sub "III")) "cost of materials consumed")
      (is (= 800000M  (sub "IV"))  "employee benefits expense")
      (is (= 120000M  (sub "V"))   "finance costs")
      (is (= 300000M  (sub "VI"))  "depreciation, incl. the 31-March entry")
      (is (= 240000M  (sub "VII")) "other expenses"))
    (testing "derived Schedule III intermediate totals"
      (is (= 2530000M (:amount (:in.pnl/total-income p))))
      (is (= 2460000M (:amount (:in.pnl/total-expenses p))))
      (is (= 70000M   (:amount (:in.pnl/profit-before-tax p)))))))

(deftest revenue-excludes-collected-gst
  ;; The sale was ₹23,60,000 gross (₹20,00,000 + ₹3,60,000 GST). Revenue
  ;; must show only the ₹20,00,000 net; the ₹4,50,000 GST collected across
  ;; both sales sits in the current-liability GST head, never in income.
  (let [p (pnl/compute (book) fy)
        b (bs/compute (book) {:through #inst "2026-03-31"})]
    (is (= 2500000M (:amount (fs/section-subtotal p "I")))
        "revenue net of the 450,000 output GST collected")
    (is (= 450000M (:amount (fs/line-value b "D" "D.5")))
        "the collected GST lives in the current-liability statutory head")
    (is (= 180000M (:amount (fs/line-value b "B" "B.5")))
        "input GST is a recoverable asset, not netted into revenue")))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2026-03-31"})]
    (is (= 7700000M (:amount (:in.bs/total-assets b))))
    (is (= 2630000M (:amount (:in.bs/total-liabilities b))))
    (is (= 5070000M (:amount (:in.bs/total-equity b))))
    (testing "the accounting equation holds"
      (is (= 0M (:amount (:in.bs/difference b))))
      (is (:in.bs/balanced? b)))
    (testing "accumulated depreciation nets against gross PP&E"
      ;; 3,000,000 plant − 300,000 accumulated = 2,700,000 shown net
      (is (= 2700000M (:amount (fs/line-value b "A" "A.1")))))
    (testing "before the year is closed, section E carries the result"
      ;; E.3 revenue gross (2,500,000 + 30,000), E.4 the negated expenses
      (is (= 2530000M (:amount (fs/line-value b "E" "E.3"))))
      (is (= -2460000M (:amount (fs/line-value b "E" "E.4"))))
      (is (= 0M (:amount (fs/line-value b "E" "E.2")))
          "reserves and surplus still empty pre-close"))))

(deftest every-amount-is-tagged-inr
  ;; Validates note-196 F5: the engine derives INR from the postings, so
  ;; the statement never reads the engine's :EUR default on this INR book.
  (let [p (pnl/compute (book) fy)
        b (bs/compute (book) {:through #inst "2026-03-31"})]
    (testing "P&L"
      (is (= #{:INR} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["I" "II" "III" "IV" "V" "VI" "VII"])))
      (is (= :INR (:commodity (:in.pnl/profit-before-tax p))))
      (is (= :INR (:commodity (:statement/total p)))))
    (testing "balance sheet"
      (is (= #{:INR} (into #{} (map (comp :commodity (partial fs/section-subtotal b)))
                           ["A" "B" "C" "D" "E"])))
      (is (= :INR (:commodity (:in.bs/total-assets b))))
      (is (= :INR (:commodity (:in.bs/difference b)))))))

(deftest window-bound-is-inclusive-via-through
  ;; :to is EXCLUSIVE. :through includes the 31-March depreciation and
  ;; excludes the next fiscal year; the two forms must agree, and an
  ;; exclusive 31-March bound must silently drop the depreciation.
  (let [conn (book)]
    (testing ":through includes 31-March depreciation and excludes FY2026-27"
      (is (= 70000M (:amount (:in.pnl/profit-before-tax (pnl/compute conn fy))))))
    (testing "the explicit exclusive form agrees"
      (is (= 70000M (:amount (:in.pnl/profit-before-tax
                              (pnl/compute conn {:from #inst "2025-04-01"
                                                 :to #inst "2026-04-01"}))))))
    (testing "an exclusive 31-March bound silently drops that day's entry"
      ;; 300,000 depreciation goes missing, so profit reads 300,000 too high
      (is (= 370000M (:amount (:in.pnl/profit-before-tax
                               (pnl/compute conn {:from #inst "2025-04-01"
                                                  :to #inst "2026-03-31"}))))))
    (testing ":to and :through together is an error"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pnl/compute conn {:to #inst "2026-04-01"
                                      :through #inst "2026-03-31"}))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2026-03-31"})]
    (is (:in.bs/balanced? r))
    (is (= 0M (:amount (:in.bs/difference r))))
    (is (= #{:in.bs/total-assets :in.bs/total-liabilities :in.bs/total-equity
             :in.bs/difference :in.bs/balanced?}
           (set (keys r))))))

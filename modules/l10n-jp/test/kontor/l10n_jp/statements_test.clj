(ns kontor.l10n-jp.statements-test
  "The JP 損益計算書 (income statement) + 貸借対照表 (balance sheet)
   against a hand-computed book.

   Every expectation is arithmetic done by hand in the comment block
   below, not a value captured from a previous run — a golden-value test
   would pass just as happily if the definitions double-counted.

   The book is a small realistic KK (株式会社) fiscal year running
   2026-04-01 .. 2027-03-31 (the March-31 決算日 that ~70% of Japanese
   corporations use). Amounts are whole yen (JPY precision 0)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.l10n-jp.bs :as bs]
            [kontor.l10n-jp.pnl :as pnl]
            [kontor.l10n-jp.preset :as jp]
            [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def ^:private gj [:kontor.journal/code "GJ"])

(defn- ace
  "Resolve an account eid by its chart code — :kontor.account/code is
   indexed but not a unique identity, so it is not a lookup-ref."
  [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- entry!
  "Book a balanced entry. `legs` is a vector of [code signed-amount] pairs
   (positive = debit, negative = credit); they must sum to zero. All JPY."
  [conn date legs]
  (let [db (d/db conn)]
    (book/entry! conn
                 {:postings       (mapv (fn [[code amt]]
                                          {:account (ace db code) :amount amt})
                                        legs)
                  :commodity      :JPY
                  :journal        gj
                  :effective-date date})))

(defn- seed!
  "A structurally complete JP fiscal year touching every section of both
   statements. Consumption tax (JCT) is booked 税抜方式 — collected JCT
   lands on the 仮受消費税 liability (215xxx), never on a 4xxxxx revenue
   account, and paid JCT on 仮払消費税 asset (180xxx)."
  [conn]
  ;; ---- opening (2026-04-01) ----
  ;; incorporation: ¥10,000,000 cash → 資本金 6,000,000 + 資本剰余金 4,000,000
  (entry! conn #inst "2026-04-01" [["110200" 10000000M]
                                   ["310000" -6000000M]
                                   ["320000" -4000000M]])
  ;; prior 繰越利益剰余金 brought forward (going concern): ¥2,000,000 cash
  (entry! conn #inst "2026-04-01" [["110200" 2000000M] ["330000" -2000000M]])
  ;; ---- fixed asset + inventory ----
  (entry! conn #inst "2026-04-10" [["153000" 3000000M] ["110200" -3000000M]]) ; buy machinery
  (entry! conn #inst "2026-05-01" [["130000" 1500000M] ["211000" -1500000M]]) ; inventory on account
  ;; purchase of goods w/ 10% input JCT: net 1,000,000 + JCT 100,000
  (entry! conn #inst "2026-05-15" [["511000" 1000000M]
                                   ["180100" 100000M]
                                   ["211000" -1100000M]])
  ;; ---- sales (税抜方式) ----
  ;; 10% standard sale: net 8,000,000 + output JCT 800,000
  (entry! conn #inst "2026-06-01" [["121000" 8800000M]
                                   ["411000" -8000000M]
                                   ["215100" -800000M]])
  ;; 8% reduced sale: net 2,000,000 + output JCT 160,000
  (entry! conn #inst "2026-07-01" [["121000" 2160000M]
                                   ["412000" -2000000M]
                                   ["215200" -160000M]])
  ;; ---- loans ----
  (entry! conn #inst "2026-06-15" [["110200" 1000000M] ["213000" -1000000M]]) ; short-term
  (entry! conn #inst "2026-06-15" [["110200" 5000000M] ["220000" -5000000M]]) ; long-term
  ;; ---- AR collection ----
  (entry! conn #inst "2026-08-01" [["110200" 5000000M] ["121000" -5000000M]])
  ;; ---- operating expenses ----
  (entry! conn #inst "2026-09-01" [["610000" 3000000M] ["110200" -3000000M]]) ; salaries
  (entry! conn #inst "2026-09-01" [["620000" 1200000M] ["110200" -1200000M]]) ; rent
  (entry! conn #inst "2026-10-01" [["660000" 300000M]  ["110200" -300000M]])  ; supplies
  ;; ---- non-operating ----
  (entry! conn #inst "2026-11-01" [["110200" 50000M]  ["710000" -50000M]])   ; interest income
  (entry! conn #inst "2026-11-01" [["720000" 120000M] ["110200" -120000M]])  ; interest expense
  ;; ---- year-end adjustments, ON the 決算日 2027-03-31 (an exclusive :to drops these) ----
  (entry! conn #inst "2027-03-31" [["670000" 600000M] ["155000" -600000M]])  ; depreciation (間接法)
  (entry! conn #inst "2027-03-31" [["680000" 200000M] ["214000" -200000M]])  ; accrued other SG&A
  (entry! conn #inst "2027-03-31" [["900000" 1500000M] ["214000" -1500000M]]) ; income tax provision
  ;; ---- NEXT fiscal year — must never appear in FY2026 statements ----
  (entry! conn #inst "2027-05-01" [["110200" 9999999M] ["411000" -9999999M]])
  conn)

(defn- book [] (seed! (jp/create-jp-db)))

;; Hand-computed FY2026 (2026-04-01 .. 2027-03-31):
;;   売上高            411000 8,000,000 + 412000 2,000,000     = 10,000,000  (JCT excluded)
;;   売上原価          511000                                   =  1,000,000
;;   売上総利益                                                  =  9,000,000
;;   販管費   給料 3,000,000 + 家賃 1,200,000 + 減価償却 600,000
;;            + 消耗品 300,000 + その他(accrued) 200,000        =  5,300,000
;;   営業利益                                                    =  3,700,000
;;   営業外収益 受取利息                                          =     50,000
;;   営業外費用 支払利息                                          =    120,000
;;   経常利益 = 税引前当期純利益                                   =  3,630,000
;;   法人税等                                                    =  1,500,000
;;   当期純利益                                                   =  2,130,000
;;
;;   現金預金 110200 10,000,000 +2,000,000 −3,000,000 +8?/collections…
;;     = 10,000,000 +2,000,000 −3,000,000 +5,000,000 −3,000,000 −1,200,000
;;       −300,000 +50,000 −120,000 +1,000,000 +5,000,000        = 15,430,000
;;   売上債権 121000 8,800,000 + 2,160,000 − 5,000,000          =  5,960,000
;;   棚卸資産 130000                                            =  1,500,000
;;   仮払消費税 180100                                          =    100,000
;;   有形固定資産 153000                                         =  3,000,000
;;   減価償却累計額 155000 (contra)                              =   −600,000
;;   資産合計                                                    = 25,390,000
;;   仕入債務 211000 1,500,000 + 1,100,000                      =  2,600,000
;;   短期借入金 213000                                          =  1,000,000
;;   未払金 214000 200,000 + 1,500,000                          =  1,700,000
;;   仮受消費税 215100 800,000 + 215200 160,000                  =    960,000
;;   長期借入金 220000                                          =  5,000,000
;;   負債合計                                                    = 11,260,000
;;   資本金 6,000,000 + 資本剰余金 4,000,000 + 利益剰余金 2,000,000
;;     + 当期純利益 2,130,000                                    = 14,130,000
;;   負債純資産合計 11,260,000 + 14,130,000                       = 25,390,000  ✓

(def ^:private fy {:from #inst "2026-04-01" :through #inst "2027-03-31"})

(deftest income-statement-matches-hand-computed-book
  (let [p   (pnl/compute (book) fy)
        sub #(:amount (fs/section-subtotal p %))]
    (testing "sections"
      (is (= 10000000M (sub "1")) "売上高, net of consumption tax")
      (is (= 1000000M (sub "2")) "売上原価")
      (is (= 5300000M (sub "3")) "販管費, including the 2027-03-31 accruals")
      (is (= 50000M (sub "4")) "営業外収益")
      (is (= 120000M (sub "5")) "営業外費用")
      (is (= 1500000M (sub "6")) "法人税等"))
    (testing "running subtotals of the 会社計算規則 form"
      (is (= 9000000M (:amount (:jp.pnl/gross-profit p))) "売上総利益")
      (is (= 3700000M (:amount (:jp.pnl/operating-income p))) "営業利益")
      (is (= 3630000M (:amount (:jp.pnl/ordinary-income p))) "経常利益")
      (is (= 3630000M (:amount (:jp.pnl/pretax-income p))) "税引前当期純利益")
      (is (= 2130000M (:amount (:jp.pnl/net-income p))) "当期純利益"))
    (testing "revenue EXCLUDES collected consumption tax (a liability)"
      ;; gross cash-in on the two sales was 8,800,000 + 2,160,000 = 10,960,000;
      ;; revenue is 10,000,000, so the 960,000 JCT is NOT in the P&L.
      (is (= 10000000M (sub "1")))
      (is (not= 10960000M (sub "1"))))
    (testing "every amount is tagged JPY, not the engine's :EUR default (F5)"
      (is (= #{:JPY} (into #{} (map (comp :commodity (partial fs/section-subtotal p)))
                           ["1" "2" "3" "4" "5" "6"])))
      (is (= :JPY (:commodity (:jp.pnl/net-income p))))
      ;; a single populated line derives JPY purely from its postings —
      ;; nothing stamps :line/commodity
      (is (= :JPY (:commodity (fs/line-value p "1" "1.1")))))))

(deftest balance-sheet-matches-and-balances
  (let [b (bs/compute (book) {:through #inst "2027-03-31"})]
    (is (= 25390000M (:amount (:jp.bs/total-assets b))) "資産合計")
    (is (= 11260000M (:amount (:jp.bs/total-liabilities b))) "負債合計")
    (is (= 14130000M (:amount (:jp.bs/total-equity b))) "純資産合計")
    (testing "the accounting equation 資産 = 負債 + 純資産 holds"
      (is (= 0M (:amount (:jp.bs/difference b))))
      (is (:jp.bs/balanced? b)))
    (testing "減価償却累計額 nets against gross 固定資産 (間接法)"
      ;; 3,000,000 machinery − 600,000 accumulated = 2,400,000 shown
      (is (= 3000000M (:amount (fs/line-value b "B" "B.1"))))
      (is (= -600000M (:amount (fs/line-value b "B" "B.2"))))
      (is (= 2400000M (:amount (fs/section-subtotal b "B")))))
    (testing "collected 仮受消費税 sits in current liabilities, not revenue"
      (is (= 960000M (:amount (fs/line-value b "C" "C.4")))))
    (testing "current-period result sits outside 利益剰余金 pre-close"
      (is (= 2000000M (:amount (fs/line-value b "E" "E.3"))) "利益剰余金 = opening only")
      (is (= 10050000M (:amount (fs/line-value b "E" "E.4"))) "current income")
      (is (= -7920000M (:amount (fs/line-value b "E" "E.5"))) "current expense (negated)")
      ;; E.4 + E.5 = 当期純利益 = 2,130,000
      (is (= 2130000M (+ (:amount (fs/line-value b "E" "E.4"))
                         (:amount (fs/line-value b "E" "E.5"))))))
    (testing "every summary Money is tagged JPY (F5)"
      (is (= :JPY (:commodity (:jp.bs/total-assets b))))
      (is (= :JPY (:commodity (:jp.bs/total-equity b))))
      (is (= :JPY (:commodity (:jp.bs/difference b)))))))

(deftest window-bound-is-inclusive-via-through
  (let [conn (book)]
    (testing ":through includes the 2027-03-31 year-end entries and excludes FY2027"
      ;; if FY2027's 9,999,999 sale leaked in, revenue would be wrong; if the
      ;; Mar-31 depreciation/accruals/tax were dropped, net income would read
      ;; 4,430,000. Getting exactly 2,130,000 proves both bounds are right.
      (is (= 2130000M (:amount (:jp.pnl/net-income (pnl/compute conn fy))))))
    (testing "the explicit exclusive (:to = day-after) form agrees"
      (is (= 2130000M (:amount (:jp.pnl/net-income
                                (pnl/compute conn {:from #inst "2026-04-01"
                                                   :to #inst "2027-04-01"}))))))
    (testing "the FY2027 sale is excluded from FY2026 revenue"
      (is (= 10000000M (:amount (fs/section-subtotal (pnl/compute conn fy) "1")))))
    (testing ":to and :through together is an error, not a silent precedence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pnl/compute conn {:to #inst "2027-04-01"
                                      :through #inst "2027-03-31"}))))))

(deftest check-reports-the-accounting-equation
  (let [r (bs/check (book) {:through #inst "2027-03-31"})]
    (is (:jp.bs/balanced? r))
    (is (= 0M (:amount (:jp.bs/difference r))))
    (is (= #{:jp.bs/total-assets :jp.bs/total-liabilities :jp.bs/total-equity
             :jp.bs/difference :jp.bs/balanced?}
           (set (keys r))))))

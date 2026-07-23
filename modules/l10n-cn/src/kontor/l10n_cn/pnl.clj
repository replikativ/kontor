(ns kontor.l10n-cn.pnl
  "Chinese income statement — 利润表 (Profit & Loss).

   Follows the conventional MOF ASBE / 小企业会计准则 (Accounting
   Standards for Small Business Enterprises) single-step layout, whose
   ordering is fixed by the ministry's model 利润表 form:

     一、营业收入   Operating revenue          (主营业务收入 + 其他业务收入)
       减：营业成本   Operating costs
           税金及附加  Taxes and surcharges
           销售费用    Selling expenses
           管理费用    Administrative expenses
           财务费用    Financial expenses
       加：投资收益   Investment income
     二、营业利润   Operating profit
       加：营业外收入  Non-operating income
       减：营业外支出  Non-operating expense
     三、利润总额   Total profit (before income tax)
       减：所得税费用  Income tax expense
     四、净利润     Net profit

   Note that investment income (投资收益) is an addition WITHIN operating
   profit under this standard, not an \"other income\" item after it — that
   is what distinguishes the ASBE order from the US multi-step form.

   Account codes target the ASSBE chart in `kontor.l10n-cn.chart`
   (`resources/kontor/l10n_cn/chart.edn`). Main-business revenue is split
   per VAT rate in that chart (5001.13 / 5001.9 / 5001.6 / 5001.0) purely
   for VAT-return aggregation; the `\"5001%\"` prefix rolls the whole range
   back into the single statutory 营业收入 line, so a consumer who adds a
   further rate lane is picked up without touching this definition.

   Revenue is recognised NET of output VAT (销项税额): output VAT is a
   liability booked to 2221.01.01, never to a 5xxx income account, so it
   never reaches this statement. See the statements test.

   The manufacturer cost-gathering accounts 4101 生产成本 / 4105 制造费用
   are deliberately absent — they accumulate into inventory (1405/1406)
   and clear into 营业成本 (5401), so their balances are WIP inventory (an
   asset, typed :asset in the chart since note 197), not a P&L line of
   their own under the ASBE model; the Balance Sheet 存货 line carries them."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; Commodity needs no per-line stamp: the report engine derives a line's
;; commodity from the postings it sums (note 196 F5), a zero is the
;; additive identity across commodities so the empty lines a P&L always
;; has fold cleanly (F5b), and an all-empty section inherits the book
;; commodity — so a CNY book reports :CNY throughout.
(def definition
  "Single-step 利润表 over the `kontor.l10n-cn.chart` codes."
  {:statement/name    "利润表 (Income Statement)"
   :statement/country "CN"
   :statement/sections
   [{:section/code  "1"
     :section/label "营业收入 Operating revenue"
     :section/lines
     [{:line/code "1.1" :line/label "主营业务收入 Main-business revenue"
       :line/codes ["5001%"]}
      {:line/code "1.2" :line/label "其他业务收入 Other-business revenue"
       :line/codes ["5051"]}]}

    {:section/code  "2"
     :section/label "营业成本 Operating costs"
     :section/lines
     [{:line/code "2.1" :line/label "主营业务成本 Main-business cost"
       :line/codes ["5401"]}]}

    {:section/code  "3"
     :section/label "税金及附加 Taxes and surcharges"
     :section/lines
     [{:line/code "3.1" :line/label "税金及附加 Taxes and surcharges"
       :line/codes ["5601"]}]}

    {:section/code  "4"
     :section/label "销售费用 Selling expenses"
     :section/lines
     [{:line/code "4.1" :line/label "销售费用 Selling expenses"
       :line/codes ["5602"]}]}

    {:section/code  "5"
     :section/label "管理费用 Administrative expenses"
     :section/lines
     [{:line/code "5.1" :line/label "管理费用 Administrative expenses"
       :line/codes ["5603"]}]}

    {:section/code  "6"
     :section/label "财务费用 Financial expenses"
     :section/lines
     [{:line/code "6.1" :line/label "财务费用 Financial expenses"
       :line/codes ["5604"]}]}

    {:section/code  "7"
     :section/label "投资收益 Investment income"
     :section/lines
     [{:line/code "7.1" :line/label "投资收益 Investment income"
       :line/codes ["5201"]}]}

    {:section/code  "8"
     :section/label "营业外收入 Non-operating income"
     :section/lines
     [{:line/code "8.1" :line/label "营业外收入 Non-operating income"
       :line/codes ["6001"]}]}

    {:section/code  "9"
     :section/label "营业外支出 Non-operating expense"
     :section/lines
     [{:line/code "9.1" :line/label "营业外支出 Non-operating expense"
       :line/codes ["6101"]}]}

    {:section/code  "10"
     :section/label "所得税费用 Income tax expense"
     :section/lines
     [{:line/code "10.1" :line/label "所得税费用 Income tax expense"
       :line/codes ["6301"]}]}]})

(def ^:private sign-map
  "Revenue and gains add; costs, expenses and income tax subtract.
   Investment income (7) adds within operating profit. The statement
   total is 净利润 (net profit)."
  {"1" :+ "2" :- "3" :- "4" :- "5" :- "6" :- "7" :+ "8" :+ "9" :- "10" :-})

(defn compute
  "Compute the 利润表 over the window `[:from, :to]`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from` / `:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst
   \"2026-12-31\"` (the inclusive form) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` silently omits everything posted on Dec 31 —
   which is where year-end depreciation, tax provisions and accruals live.

   Returns the standard computed-statement map plus the ASBE subtotals
   under the `:cn.pnl/*` keys, because the 利润表 is defined by them:

     :cn.pnl/operating-revenue = 营业收入 (section 1)
     :cn.pnl/operating-profit  = 营业利润 = revenue − (costs + T&S +
                                 selling + admin + financial) + investment
     :cn.pnl/total-profit      = 利润总额 = operating profit + non-op
                                 income − non-op expense
     :cn.pnl/net-profit        = 净利润 = the statement total (after tax)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         ;; ASBE 营业利润: revenue − Σ(operating expense sections) + investment income
         op-profit (money/add
                    (reduce money/sub
                            (sub "1")
                            (map sub ["2" "3" "4" "5" "6"]))
                    (sub "7"))
         total-profit (money/sub (money/add op-profit (sub "8")) (sub "9"))]
     (assoc computed
            :cn.pnl/operating-revenue (sub "1")
            :cn.pnl/operating-profit  op-profit
            :cn.pnl/total-profit      total-profit
            :cn.pnl/net-profit        (:statement/total computed)))))

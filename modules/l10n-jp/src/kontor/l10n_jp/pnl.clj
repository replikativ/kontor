(ns kontor.l10n-jp.pnl
  "Japanese income statement — 損益計算書 (Son'eki Keisan-sho), the
   report-form (報告式) layout the 会社法 (Companies Act) calculation of
   distributable profit and the 財務諸表等規則 presentation both follow.

   J-GAAP does not fix a chart of accounts, but the *ordering* of the
   P/L is settled convention — 会社計算規則 §88 and the 財務諸表等規則 §69ff
   enumerate the running subtotals in this exact sequence:

     売上高            (net sales)
     − 売上原価         (cost of sales)
     = 売上総利益        (gross profit)              [会社計算規則 §89]
     − 販売費及び一般管理費 (selling, general & admin)
     = 営業利益          (operating income)          [§90]
     + 営業外収益        (non-operating income)
     − 営業外費用        (non-operating expenses)
     = 経常利益          (ordinary/recurring income) [§91]
     + 特別利益          (extraordinary gains)
     − 特別損失          (extraordinary losses)
     = 税引前当期純利益   (income before income taxes) [§92]
     − 法人税等          (corporate income taxes)
     = 当期純利益         (net income for the period) [§94]

   Sales are booked NET of consumption tax (消費税, JCT): the 税抜方式
   (tax-exclusive) convention this chart follows posts collected JCT to
   the 仮受消費税 liability accounts (215xxx), not to a 4xxxxx revenue
   account, so 売上高 here already excludes JCT — as the VAT-return
   split requires. See `kontor.l10n-jp.consumption-tax`.

   Account codes target the J-GAAP-style skeleton in
   `resources/kontor/l10n_jp/chart.edn`:
     4xxxxx  revenue        → 売上高
     5xxxxx  cost of sales  → 売上原価
     6xxxxx  operating exp  → 販売費及び一般管理費
     7xxxxx  non-operating  (710000 income / 720000 expense — split
                             by account because 7xxxxx is mixed-sign)
     9xxxxx  income tax     → 法人税等

   Prefix patterns (`\"6%\"` = every 6xxxxx) are used where a whole range
   rolls into one line so a consumer who adds `660500 消耗品費:文具` is
   picked up without editing this definition.

   ## 特別利益 / 特別損失 (extraordinary items) are omitted

   The 会社計算規則 §88 form carries 特別利益 (extraordinary gains) and
   特別損失 (extraordinary losses) between 経常利益 and 税引前当期純利益.
   The shipped starter chart ships NO 8xxxxx accounts for them, so
   rather than invent codes (and rather than ship an always-empty
   section — an empty line would carry the report engine's :EUR fallback
   commodity on a JPY book and break the JPY subtotal fold), the two
   sections are left out. A consumer that adds 8xxxxx extraordinary
   accounts adds the two sections; `pretax-income` below already equals
   `ordinary-income` in their absence, so the running subtotals stay
   correct.

   Because the whole chart is JPY, no line stamps `:line/commodity`: the
   report engine derives every line's currency from the postings it sums
   (note-196 F5, `kontor.reporting.report/resolve-commodity-symbol`)."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "報告式 income statement over the `kontor.l10n-jp.chart` codes."
  {:statement/name    "損益計算書 (Income Statement)"
   :statement/country "JP"
   :statement/sections
   [{:section/code  "1"
     :section/label "売上高 (Net sales)"
     :section/lines
     ;; NET of JCT — 税抜方式; collected JCT is a 215xxx liability.
     [{:line/code "1.1" :line/label "売上高 (Sales)" :line/codes ["4%"]}]}

    {:section/code  "2"
     :section/label "売上原価 (Cost of sales)"
     :section/lines
     [{:line/code "2.1" :line/label "売上原価 (Cost of goods sold)"
       :line/codes ["5%"]}]}

    {:section/code  "3"
     :section/label "販売費及び一般管理費 (Selling, general & administrative)"
     :section/lines
     [{:line/code "3.1" :line/label "給料手当 (Salaries)" :line/codes ["610000"]}
      {:line/code "3.2" :line/label "地代家賃 (Rent)" :line/codes ["620000"]}
      {:line/code "3.3" :line/label "減価償却費 (Depreciation)" :line/codes ["670000"]}
      {:line/code "3.4" :line/label "その他販管費 (Other SG&A)"
       :line/codes ["630000" "640000" "650000" "660000" "680000"]}]}

    {:section/code  "4"
     :section/label "営業外収益 (Non-operating income)"
     :section/lines
     [{:line/code "4.1" :line/label "受取利息 (Interest income)"
       :line/codes ["710000"]}]}

    {:section/code  "5"
     :section/label "営業外費用 (Non-operating expenses)"
     :section/lines
     [{:line/code "5.1" :line/label "支払利息 (Interest expense)"
       :line/codes ["720000"]}]}

    {:section/code  "6"
     :section/label "法人税等 (Corporate income taxes)"
     :section/lines
     [{:line/code "6.1" :line/label "法人税、住民税及び事業税 (Income taxes)"
       :line/codes ["9%"]}]}]})

(def ^:private sign-map
  "Revenue and non-operating income add; cost of sales, SG&A,
   non-operating expense and income tax subtract. The statement total is
   the 会社計算規則 §94 当期純利益 (net income for the period)."
  {"1" :+ "2" :- "3" :- "4" :+ "5" :- "6" :-})

(defn compute
  "Compute the 損益計算書 over the window `[:from, :to)`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement` (`:from`
   `:to` `:through` `:as-of-tx` `:include-states` `:ledger` `:entity`).
   `:from`/`:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For a fiscal year ending 2027-03-31 pass
   `:through #inst \"2027-03-31\"` (the inclusive form) or
   `:to #inst \"2027-04-01\"`; `:to #inst \"2027-03-31\"` silently omits
   everything posted on the kessanbi (決算日) — where year-end
   depreciation and accruals live.

   Returns the standard computed-statement map plus the running
   subtotals the 会社計算規則 form is defined by, under `:jp.pnl/*`:

     :jp.pnl/gross-profit     売上総利益 = 売上高 − 売上原価
     :jp.pnl/operating-income 営業利益   = 売上総利益 − 販管費
     :jp.pnl/ordinary-income  経常利益   = 営業利益 + 営業外収益 − 営業外費用
     :jp.pnl/pretax-income    税引前当期純利益 (= 経常利益 absent 特別損益)
     :jp.pnl/net-income       当期純利益 = the statement total"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed  (fs/compute-statement conn statement
                                         (assoc opts :total-sign-map sign-map))
         sub       #(fs/section-subtotal computed %)
         revenue   (sub "1")
         cogs      (sub "2")
         sga       (sub "3")
         nonop-inc (sub "4")
         nonop-exp (sub "5")
         gross     (money/sub revenue cogs)
         operating (money/sub gross sga)
         ordinary  (money/sub (money/add operating nonop-inc) nonop-exp)]
     (assoc computed
            :jp.pnl/gross-profit     gross
            :jp.pnl/operating-income operating
            :jp.pnl/ordinary-income  ordinary
            ;; no 特別利益/特別損失 in the starter chart, so 税引前 = 経常
            :jp.pnl/pretax-income    ordinary
            :jp.pnl/net-income       (:statement/total computed)))))

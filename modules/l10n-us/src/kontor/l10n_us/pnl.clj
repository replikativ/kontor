(ns kontor.l10n-us.pnl
  "US income statement (Profit & Loss), multi-step form.

   There is no statutory US P&L layout the way HGB §275 fixes the German
   one — Regulation S-X art. 5-03 governs SEC registrants, and everyone
   else follows the conventional multi-step presentation this namespace
   ships:

     Revenue (net of returns and discounts)
     − Cost of goods sold
     = Gross profit
     − Operating expenses
     = Operating income
     + Other income − other expense
     = Net income (pre-tax)

   Account codes target the SMB chart in `kontor.l10n-us.chart`
   (`resources/kontor/l10n_us/chart.edn`). Prefix patterns (`\"6%\"` = all
   6xxx) are used wherever a whole range belongs to one line, so a
   customer who adds `6410 Expenses:Telecom:Mobile` gets picked up
   without touching this definition.

   Contra accounts need no special handling: the chart gives them their
   parent's `:kontor.account/type` (sales returns are `:income`,
   accumulated depreciation is `:asset`), so the report engine's
   `:sign :inflow` — which flips sign by account type — nets them
   automatically.

   Tax is NOT a line here. US income tax is an entity-level computation
   (`kontor.l10n-us.cit-provider` / `pit-provider`), so this statement
   stops at pre-tax income; consumers that book a tax provision to
   2500/2510 can add a section for it."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; Commodity needs no per-line stamp: the report engine derives each
;; populated line's commodity from the postings it sums (note 196 F5), a
;; zero is the additive identity across commodities so empty lines fold
;; cleanly (F5b), and an all-empty section inherits the book commodity —
;; so a USD book reports :USD throughout.
(def definition
  "Multi-step income statement over the `kontor.l10n-us.chart` codes."
  {:statement/name    "Income Statement"
   :statement/country "US"
   :statement/sections
   [{:section/code  "1"
     :section/label "Revenue"
     :section/lines
     [{:line/code "1.1" :line/label "Product sales"
       :line/codes ["4000" "4200"]}
      {:line/code "1.2" :line/label "Service revenue"
       :line/codes ["4100"]}
      {:line/code "1.3" :line/label "Subscription revenue"
       :line/codes ["4400"]}
      {:line/code "1.4" :line/label "Shipping revenue"
       :line/codes ["4300"]}
       ;; contra-revenue: :income type, debit balance, nets negative
      {:line/code "1.5" :line/label "Less: returns, allowances and discounts"
       :line/codes ["4010" "4020"]}]}

    {:section/code  "2"
     :section/label "Cost of goods sold"
     :section/lines
     [{:line/code "2.1" :line/label "Materials" :line/codes ["5010"]}
      {:line/code "2.2" :line/label "Direct labor" :line/codes ["5020"]}
      {:line/code "2.3" :line/label "Freight-in" :line/codes ["5030"]}
      {:line/code "2.4" :line/label "Subcontractors" :line/codes ["5040"]}
      {:line/code "2.5" :line/label "Other cost of sales" :line/codes ["5000"]}]}

    {:section/code  "3"
     :section/label "Operating expenses"
     :section/lines
     [{:line/code "3.1" :line/label "Salaries, wages and payroll taxes"
       :line/codes ["6110" "6120" "6130"]}
      {:line/code "3.2" :line/label "Contract labor (1099)"
       :line/codes ["6900"]}
      {:line/code "3.3" :line/label "Rent and utilities"
       :line/codes ["6100" "6200"]}
      {:line/code "3.4" :line/label "Insurance" :line/codes ["6300"]}
      {:line/code "3.5" :line/label "Office and telecom"
       :line/codes ["6000" "6400"]}
      {:line/code "3.6" :line/label "Software and subscriptions"
       :line/codes ["6990"]}
      {:line/code "3.7" :line/label "Professional fees" :line/codes ["6500"]}
      {:line/code "3.8" :line/label "Advertising and marketing" :line/codes ["6600"]}
      {:line/code "3.9" :line/label "Travel, meals and entertainment"
       :line/codes ["6700" "6800"]}
      {:line/code "3.10" :line/label "Depreciation and amortization"
       :line/codes ["6950" "6960"]}
      {:line/code "3.11" :line/label "Bank fees and bad debt"
       :line/codes ["6970" "6980"]}]}

    {:section/code  "4"
     :section/label "Other income"
     :section/lines
     [{:line/code "4.1" :line/label "Interest and investment income"
       :line/codes ["7000" "7100"]}
      {:line/code "4.2" :line/label "Foreign-exchange gain" :line/codes ["7200"]}]}

    {:section/code  "5"
     :section/label "Other expense"
     :section/lines
     [{:line/code "5.1" :line/label "Interest expense" :line/codes ["7500"]}
      {:line/code "5.2" :line/label "Foreign-exchange loss" :line/codes ["7600"]}
      {:line/code "5.3" :line/label "Tax penalties" :line/codes ["7700"]}
      {:line/code "5.4" :line/label "Loss on asset disposal" :line/codes ["7800"]}]}]})

(def ^:private sign-map
  "Revenue and other income add; cost, operating expense and other
   expense subtract. Total = net income before tax."
  {"1" :+ "2" :- "3" :- "4" :+ "5" :-})

(defn compute
  "Compute the income statement over the window `[:from, :to]`.

   Opts are forwarded to `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:as-of-tx` `:include-states` `:ledger` `:entity`); `:from`
   and `:to` are what make it a period statement — omit them and you get
   every posting since inception.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst \"2026-12-31\"`
   (the inclusive form) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` silently omits everything posted on Dec 31 —
   which is where year-end depreciation and accruals live.

   Returns the standard computed-statement map plus three derived
   subtotals under the `:us.pnl/*` keys, because the multi-step form is
   defined by them:

     :us.pnl/gross-profit     = revenue − COGS
     :us.pnl/operating-income = gross profit − operating expenses
     :us.pnl/net-income       = the statement total (pre-tax)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         revenue  (sub "1")
         cogs     (sub "2")
         opex     (sub "3")
         gross    (money/sub revenue cogs)]
     (assoc computed
            :us.pnl/gross-profit     gross
            :us.pnl/operating-income (money/sub gross opex)
            :us.pnl/net-income       (:statement/total computed)))))

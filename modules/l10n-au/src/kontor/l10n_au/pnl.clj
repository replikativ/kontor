(ns kontor.l10n-au.pnl
  "Australian income statement (Statement of Profit or Loss).

   Australia has no single mandated small-business P&L layout. AASB 101
   (Presentation of Financial Statements, the local IFRS/IAS 1 adoption)
   governs reporting entities and permits either an 'analysis by nature'
   or 'analysis by function' presentation. This module ships the
   conventional by-function multi-step form Australian accountants and
   the ATO's own worksheets use:

     Revenue (net of GST)
     + Other income
     − Cost of sales
     = Gross profit
     − Operating expenses
     = Operating profit
     + Other income − finance costs        (folded into profit-before-tax)
     = Profit before income tax
     − Income tax expense
     = Profit for the year

   GST is NEVER a P&L line. GST collected on a taxable supply is a
   liability (`Liabilities:GSTPayable`, BAS label 1A) and GST paid is an
   input-tax-credit asset (`Assets:GSTReceivable`, 1B) — revenue is
   booked NET of the 10% GST, so the tax never touches this statement.
   That is the defining GST invariant this module tests.

   Account codes target the AU chart in `kontor.l10n-au.chart`
   (`resources/kontor/l10n_au/chart.edn`). Prefix patterns (`\"41%\"` =
   every 41xxx) roll a whole range into one line, so a consumer who adds
   `41500 Income:Sales:Digital` is picked up without editing this
   definition.

   Contra accounts need no special handling: the chart gives a provision
   or accumulated-depreciation account its parent's
   `:kontor.account/type`, so the report engine's `:sign :inflow` — which
   flips sign by account type — nets them automatically.

   Note the currency is NOT stamped per line: the report engine derives a
   line's commodity from the postings it sums (note-196 F5,
   `kontor.reporting.report/resolve-commodity-symbol`), so an AUD book
   reads AUD. A `:total-sign-map` is still required to fold sections into
   the bottom line."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "By-function multi-step income statement over the `kontor.l10n-au.chart`
   codes."
  {:statement/name    "Statement of Profit or Loss"
   :statement/country "AU"
   :statement/sections
   [{:section/code  "1"
     :section/label "Revenue"
     :section/lines
     ;; Sales are booked NET of GST — the 10% collected is a liability
     ;; (21500), never revenue. Covers taxable / GST-free / export /
     ;; input-taxed supplies (41100-41400).
     [{:line/code "1.1" :line/label "Sale of goods and services"
       :line/codes ["41%"]}]}

    {:section/code  "2"
     :section/label "Other income"
     :section/lines
     [{:line/code "2.1" :line/label "Interest and other income"
       :line/codes ["42%"]}]}

    {:section/code  "3"
     :section/label "Cost of sales"
     :section/lines
     [{:line/code "3.1" :line/label "Cost of sales"
       :line/codes ["51%"]}]}

    {:section/code  "4"
     :section/label "Operating expenses"
     :section/lines
     [{:line/code "4.1" :line/label "Employee benefits expense"
       :line/codes ["61100" "61200"]}
      {:line/code "4.2" :line/label "Occupancy, office and administration"
       :line/codes ["61500" "61600" "61700" "61800" "61900" "62200" "62500"]}
      {:line/code "4.3" :line/label "Depreciation and amortisation"
       :line/codes ["62100"]}]}

    {:section/code  "5"
     :section/label "Finance costs"
     :section/lines
     [{:line/code "5.1" :line/label "Interest expense"
       :line/codes ["71100"]}]}

    {:section/code  "6"
     :section/label "Income tax expense"
     :section/lines
     [{:line/code "6.1" :line/label "Income tax expense"
       :line/codes ["91100"]}]}]})

(def ^:private sign-map
  "Revenue and other income add; cost of sales, operating expenses,
   finance costs and income tax subtract. Total = profit for the year
   (after tax)."
  {"1" :+ "2" :+ "3" :- "4" :- "5" :- "6" :-})

(defn compute
  "Compute the income statement over the window `[:from, :to]`.

   Opts are forwarded to `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from`/`:to` make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst \"2026-12-31\"`
   (the inclusive form) or `:to #inst \"2027-01-01\"`; `:to #inst
   \"2026-12-31\"` silently omits everything posted on Dec 31 — where
   year-end depreciation and accruals live.

   Returns the standard computed-statement map plus four derived
   subtotals under `:au.pnl/*`, because the multi-step form is defined by
   them:

     :au.pnl/gross-profit       = revenue − cost of sales
     :au.pnl/operating-profit   = gross profit − operating expenses
     :au.pnl/profit-before-tax  = (revenue + other income)
                                  − (cost of sales + opex + finance costs)
     :au.pnl/profit-for-year    = the statement total (after income tax)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed  (fs/compute-statement conn statement
                                         (assoc opts :total-sign-map sign-map))
         sub       #(fs/section-subtotal computed %)
         revenue   (sub "1")
         other     (sub "2")
         cogs      (sub "3")
         opex      (sub "4")
         finance   (sub "5")
         gross     (money/sub revenue cogs)
         operating (money/sub gross opex)
         pbt       (money/sub (money/sub (money/add revenue other) cogs)
                              (money/add opex finance))]
     (assoc computed
            :au.pnl/gross-profit      gross
            :au.pnl/operating-profit  operating
            :au.pnl/profit-before-tax pbt
            :au.pnl/profit-for-year   (:statement/total computed)))))

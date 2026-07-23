(ns kontor.l10n-ca.pnl
  "Canadian income statement (Statement of Income / État des résultats),
   conventional single-step-with-gross-profit form.

   Canada has no statutory income-statement layout the way HGB §275
   fixes the German one — CPA Canada / ASPE (Part II of the CPA Canada
   Handbook) and IFRS both leave presentation to management judgement,
   and the CRA's T2 Schedule 125 (GIFI) is a data-collection grid, not a
   presentation form. This module ships the conventional SMB
   presentation an ASPE preparer would recognise:

     Revenue (net of returns; EXCLUDING GST/HST/PST/QST collected)
     − Cost of goods sold
     = Gross profit
     − Operating expenses
     = Net income (pre-tax)

   ## Sales tax is never revenue

   GST/HST collected (`2310`), PST/RST collected (`2320`–`2322`) and QST
   collected (`2330`) are LIABILITIES — money held on the Crown's / a
   province's behalf and remitted to the CRA / Revenu Québec. They live
   in the 2xxx range in `kontor.l10n-ca.chart` and are never touched by
   any line here, so revenue is stated net of tax by construction. The
   corresponding input tax credits (`1310` GST/HST ITC, `1320` QST ITR)
   are recoverable ASSETS, likewise absent from the P&L; only
   non-recoverable PST *paid* (`5100`) is an expense and appears in
   operating expenses.

   ## No other-income / other-expense section

   The shipped SMB chart carries no interest-income, interest-expense,
   FX or gain/loss accounts, so this definition stops at pre-tax net
   income from operations. A consumer who adds e.g. `7000
   Income:Interest` can append an \"Other income (expense)\" section
   against the kernel; `financial-statements/statement-coverage` reports
   any account no line covers.

   Account codes target `kontor.l10n-ca.chart`
   (`resources/kontor/l10n_ca/chart.edn`). Per note-196 F5 the report
   engine derives each line's commodity (CAD) from the postings, so no
   `:line/commodity` stamping is needed — only the section
   `:total-sign-map` below.

   Tax is NOT a line here. Canadian corporate income tax (T2) is an
   entity-level computation (`kontor.l10n-ca.cit-provider`), so this
   statement stops at pre-tax income."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Conventional Canadian income statement over `kontor.l10n-ca.chart`."
  {:statement/name    "Statement of Income"
   :statement/country "CA"
   :statement/sections
   [{:section/code  "1"
     :section/label "Revenue"
     :section/lines
     [{:line/code "1.1" :line/label "Sales — taxable"
       :line/codes ["4000"]}
      {:line/code "1.2" :line/label "Sales — zero-rated (groceries, exports)"
       :line/codes ["4010"]}
      {:line/code "1.3" :line/label "Sales — exempt (financial services)"
       :line/codes ["4020"]}]}

    {:section/code  "2"
     :section/label "Cost of goods sold"
     :section/lines
     [{:line/code "2.1" :line/label "Cost of goods sold" :line/codes ["5000"]}]}

    {:section/code  "3"
     :section/label "Operating expenses"
     :section/lines
     [{:line/code "3.1" :line/label "Office expenses" :line/codes ["6000"]}
      {:line/code "3.2" :line/label "Rent" :line/codes ["6100"]}
      {:line/code "3.3" :line/label "Utilities" :line/codes ["6200"]}
      {:line/code "3.4" :line/label "Insurance" :line/codes ["6300"]}
      {:line/code "3.5" :line/label "Telecommunications" :line/codes ["6400"]}
      {:line/code "3.6" :line/label "Professional fees" :line/codes ["6500"]}
      ;; PST paid is a retail sales tax the business cannot recover, so
      ;; it lands as an operating expense (chart note), NOT as a
      ;; recoverable ITC asset the way GST/HST paid does.
      {:line/code "3.7" :line/label "Non-recoverable PST paid"
       :line/codes ["5100"]}]}]})

(def ^:private sign-map
  "Revenue adds; cost of goods sold and operating expenses subtract.
   Total = net income before tax."
  {"1" :+ "2" :- "3" :-})

(defn compute
  "Compute the income statement over the window `[:from, :to]`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from`/`:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass
   `:through #inst \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`;
   `:to #inst \"2026-12-31\"` silently omits everything posted on Dec 31.

   Returns the standard computed-statement map plus two derived
   subtotals under the `:ca.pnl/*` keys:

     :ca.pnl/gross-profit = revenue − COGS
     :ca.pnl/net-income   = the statement total (pre-tax)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         revenue  (sub "1")
         cogs     (sub "2")]
     (assoc computed
            :ca.pnl/gross-profit (money/sub revenue cogs)
            :ca.pnl/net-income   (:statement/total computed)))))

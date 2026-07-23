(ns kontor.l10n-in.pnl
  "Indian Statement of Profit and Loss — Schedule III, Companies Act 2013
   (Division II, Ind AS-aligned form).

   Schedule III fixes the *line items* and their order for the Statement
   of Profit and Loss (it does NOT prescribe numeric account codes — see
   `kontor.l10n-in.chart`). The statutory shape this namespace ships:

     I    Revenue from operations
     II   Other income
     III  Total income (I + II)
     IV   Expenses
            Cost of materials consumed / purchases of stock-in-trade
            Changes in inventories
            Employee benefits expense
            Finance costs
            Depreciation and amortisation expense
            Other expenses
          Total expenses
     V    Profit before exceptional items and tax (III − IV)
     ...  (exceptional / extraordinary items — omitted in v1)
     Profit before tax

   Account codes target the Schedule-III-shaped SMB chart in
   `kontor.l10n-in.chart` (`resources/kontor/l10n_in/chart.edn`). Prefix
   patterns (`\"52%\"` = every 52xxxx account) roll a whole Schedule III
   head into one line, so a consumer who adds
   `520400 Expenses:Employee-Benefits:Bonus` is picked up without
   touching this definition.

   ## GST is not revenue

   Output GST (CGST / SGST / IGST / UTGST / Cess) collected from buyers
   is a LIABILITY (chart class 33xxxx), never income. Because the revenue
   sections here match only the 41xxxx / 42xxxx income accounts, the tax
   a seller collects and owes to the government is structurally excluded
   from `Revenue from operations` — it can never inflate the top line.

   ## Tax is not a line here

   Indian income tax (CIT under the Income-tax Act 1961 — normal
   provisions / §115BAA / MAT) is an entity-level period computation
   (`kontor.l10n-in.cit-provider`), so this statement stops at profit
   before tax. A consumer that books a current-tax provision to a
   324xxx / 333xxx account can add a `Tax expense` section for the
   post-tax `Profit for the period`.

   ## Currency

   note 196 F5 lets the report engine derive each line's commodity from
   the postings it sums, so a line that matches INR postings reads INR
   without being told. F5b makes a zero the additive identity across
   commodities, so an empty line folds cleanly into an INR subtotal, and
   an all-empty section inherits the book commodity — the statement reports
   :INR throughout with no per-line stamp."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Schedule III Statement of Profit and Loss over the
   `kontor.l10n-in.chart` codes."
  {:statement/name    "Statement of Profit and Loss"
   :statement/country "IN"
   :statement/sections
   [{:section/code  "I"
     :section/label "Revenue from operations"
     :section/lines
     [{:line/code "I.1" :line/label "Sale of products and services (taxable)"
       :line/codes ["410000" "410100" "410900"]}
      {:line/code "I.2" :line/label "Exports (zero-rated)"
       :line/codes ["410200"]}
      {:line/code "I.3" :line/label "Exempt / nil-rated supplies"
       :line/codes ["410300"]}]}

    {:section/code  "II"
     :section/label "Other income"
     :section/lines
     [{:line/code "II.1" :line/label "Interest income" :line/codes ["420100"]}
      {:line/code "II.2" :line/label "Other non-operating income"
       :line/codes ["420000" "420200"]}]}

    {:section/code  "III"
     :section/label "Cost of materials consumed"
     :section/lines
     [{:line/code "III.1" :line/label "Purchases of raw materials / stock-in-trade"
       :line/codes ["510000" "510100" "510200"]}
      {:line/code "III.2" :line/label "Changes in inventories of FG / WIP / stock-in-trade"
       :line/codes ["511000"]}]}

    {:section/code  "IV"
     :section/label "Employee benefits expense"
     :section/lines
     [{:line/code "IV.1" :line/label "Salaries, wages and staff welfare"
       :line/codes ["52%"]}]}

    {:section/code  "V"
     :section/label "Finance costs"
     :section/lines
     [{:line/code "V.1" :line/label "Interest and bank charges"
       :line/codes ["53%"]}]}

    {:section/code  "VI"
     :section/label "Depreciation and amortisation expense"
     :section/lines
     [{:line/code "VI.1" :line/label "Depreciation and amortisation"
       :line/codes ["54%"]}]}

    {:section/code  "VII"
     :section/label "Other expenses"
     :section/lines
     [{:line/code "VII.1" :line/label "Rent, power, repairs, insurance, admin"
       :line/codes ["55%"]}]}]})

(def ^:private sign-map
  "Revenue and other income add; every expense head subtracts.
   `:statement/total` is Schedule III `Profit before tax`."
  {"I" :+ "II" :+ "III" :- "IV" :- "V" :- "VI" :- "VII" :-})

(defn compute
  "Compute the Statement of Profit and Loss over the window `[:from, :to]`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from` and `:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For the Indian fiscal year 2025-26 pass
   `:from #inst \"2025-04-01\"` `:through #inst \"2026-03-31\"` (the
   inclusive form) or `:to #inst \"2026-04-01\"`. `:to #inst
   \"2026-03-31\"` silently omits everything posted on 31 March — where
   year-end depreciation and accruals live.

   Returns the standard computed-statement map plus three derived
   subtotals under the `:in.pnl/*` keys — the Schedule III intermediate
   totals:

     :in.pnl/total-income      = I + II
     :in.pnl/total-expenses    = III + IV + V + VI + VII
     :in.pnl/profit-before-tax = the statement total"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         income   (reduce money/add (map sub ["I" "II"]))
         expenses (reduce money/add (map sub ["III" "IV" "V" "VI" "VII"]))]
     (assoc computed
            :in.pnl/total-income      income
            :in.pnl/total-expenses    expenses
            :in.pnl/profit-before-tax (:statement/total computed)))))

(ns kontor.l10n-us.bs
  "US balance sheet (Statement of Financial Position), classified form.

   Like the income statement there is no statutory US layout outside
   Regulation S-X art. 5-02; this ships the conventional classified
   presentation — current vs non-current on both sides, in decreasing
   order of liquidity:

     Assets      = current + property & equipment (net) + other assets
     Liabilities = current + long-term
     Equity      = contributed capital − distributions + retained
                   earnings + current-period earnings

   Account codes target `kontor.l10n-us.chart`. Accumulated-depreciation
   accounts carry `:kontor.account/type :asset` in that chart, so their
   credit balances net against gross PP&E automatically under
   `:sign :inflow` — the \"(net)\" in \"property and equipment, net\" needs
   no special handling.

   ## Why equity has a current-earnings line

   A balance sheet only balances when the period's profit is IN equity.
   Revenue and expense accounts are closed into retained earnings at
   fiscal-year end (`kontor.l10n-us.closing/close-fiscal-year!`), so
   before that runs — i.e. for any interim balance sheet — the 4xxx-7xxx
   balances are sitting outside equity and assets exceed
   liabilities + equity by exactly the period's net income.

   Section E therefore carries the current-period result as two lines:
   revenue-side accounts, and expense-side accounts flipped by
   `:line/negate`. That is the standard interim presentation, and it
   makes the statement balance both before and after a close: once
   `close-fiscal-year!` has zeroed the P&L accounts into 3100 those two
   lines compute to zero and retained earnings carries the amount
   instead.

   `check` reports whether the accounting equation actually holds — see
   its docstring for what a non-zero difference means."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; See the note in kontor.l10n-us.pnl — commodity is derived from the
;; postings (note 196 F5), empty lines fold as the additive identity
;; (F5b), and an all-empty section inherits the book commodity, so a USD
;; book reports :USD with no per-line stamp.
(def definition
  "Classified balance sheet over the `kontor.l10n-us.chart` codes."
  {:statement/name    "Balance Sheet"
   :statement/country "US"
   :statement/sections
   [{:section/code  "A"
     :section/label "Current assets"
     :section/lines
     [{:line/code "A.1" :line/label "Cash and cash equivalents"
       :line/codes ["1000" "1100" "1110" "1120" "1130"]}
      {:line/code "A.2" :line/label "Accounts receivable, net of allowance"
       :line/codes ["1200" "1210" "1220" "1230"]}
      {:line/code "A.3" :line/label "Inventory"
       :line/codes ["1500" "1510" "1520" "1530"]}
      {:line/code "A.4" :line/label "Prepaid expenses and vendor deposits"
       :line/codes ["1300" "1310" "1320" "1390"]}]}

    {:section/code  "B"
     :section/label "Property and equipment, net"
     :section/lines
     [{:line/code "B.1" :line/label "Land and buildings"
       :line/codes ["1550" "1560" "1565"]}
      {:line/code "B.2" :line/label "Vehicles"
       :line/codes ["1570" "1575"]}
      {:line/code "B.3" :line/label "Furniture and equipment"
       :line/codes ["1580" "1585"]}
      {:line/code "B.4" :line/label "Computer equipment"
       :line/codes ["1590" "1595"]}]}

    {:section/code  "C"
     :section/label "Other assets"
     :section/lines
     [{:line/code "C.1" :line/label "Intangibles and goodwill"
       :line/codes ["1600" "1610"]}
      {:line/code "C.2" :line/label "Security deposits" :line/codes ["1700"]}]}

    {:section/code  "D"
     :section/label "Current liabilities"
     :section/lines
     [{:line/code "D.1" :line/label "Accounts payable" :line/codes ["2000"]}
      {:line/code "D.2" :line/label "Accrued expenses"
       :line/codes ["2010" "2020"]}
      {:line/code "D.3" :line/label "Credit cards" :line/codes ["2100"]}
      {:line/code "D.4" :line/label "Customer deposits and deferred revenue"
       :line/codes ["2110" "2120" "2130"]}
      {:line/code "D.5" :line/label "Sales tax payable" :line/codes ["22%"]}
      {:line/code "D.6" :line/label "Payroll liabilities"
       :line/codes ["23%" "2400" "2410" "2420"]}
      {:line/code "D.7" :line/label "Income tax payable"
       :line/codes ["2500" "2510"]}
      {:line/code "D.8" :line/label "Short-term debt"
       :line/codes ["2600" "2610"]}]}

    {:section/code  "E"
     :section/label "Long-term liabilities"
     :section/lines
     [{:line/code "E.1" :line/label "Notes and mortgages payable"
       :line/codes ["2700" "2710"]}]}

    {:section/code  "F"
     :section/label "Equity"
     :section/lines
     [{:line/code "F.1" :line/label "Contributed capital"
       :line/codes ["3000" "3010" "3200" "3210"]}
      {:line/code "F.2" :line/label "Owner distributions and dividends"
       :line/codes ["3020" "3110"]}
      {:line/code "F.3" :line/label "Retained earnings" :line/codes ["3100"]}
       ;; Current-period result, held outside equity until the fiscal
       ;; year is closed — see the namespace docstring.
      {:line/code "F.4" :line/label "Current-period revenue"
       :line/codes ["4%" "7000" "7100" "7200"]}
      {:line/code "F.5" :line/label "Current-period expenses"
       :line/codes ["5%" "6%" "7500" "7600" "7700" "7800"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :+ "C" :+ "D" :- "E" :- "F" :-})

(defn compute
  "Compute the balance sheet as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass
   `:through #inst \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` omits everything posted on Dec 31, which is
   where year-end depreciation and accruals land — and because it drops
   both sides of those entries the statement still BALANCES while being
   wrong.

   Returns the standard computed-statement map plus:

     :us.bs/total-assets      = sections A + B + C
     :us.bs/total-liabilities = sections D + E
     :us.bs/total-equity      = section F
     :us.bs/difference        = assets − (liabilities + equity); zero
                                for a balanced book
     :us.bs/balanced?         = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         assets   (reduce money/add (map sub ["A" "B" "C"]))
         liabs    (reduce money/add (map sub ["D" "E"]))
         equity   (sub "F")
         diff     (money/sub assets (money/add liabs equity))]
     (assoc computed
            :us.bs/total-assets      assets
            :us.bs/total-liabilities liabs
            :us.bs/total-equity      equity
            :us.bs/difference        diff
            :us.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:us.bs/*` summary of [[compute]].

   A non-zero `:us.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger:
   the kernel's transact gate refuses any entry whose postings do not sum
   to zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:us.bs/total-assets :us.bs/total-liabilities
                    :us.bs/total-equity :us.bs/difference :us.bs/balanced?])))

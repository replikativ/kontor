(ns kontor.l10n-au.bs
  "Australian balance sheet (Statement of Financial Position), classified
   form.

   Like the income statement there is no single mandated small-business
   layout; AASB 101 §60 requires the current / non-current split (unless a
   liquidity presentation is more relevant), and this ships the
   conventional classified form Australian practice uses:

     Assets      = current assets + non-current assets
     Liabilities = current + non-current
     Equity      = contributed equity − owner drawings + retained
                   earnings + current-period result

   Account codes target `kontor.l10n-au.chart`. Accumulated-depreciation
   and doubtful-debt-provision accounts carry
   `:kontor.account/type :asset` in that chart, so their credit balances
   net against the gross asset automatically under `:sign :inflow` — the
   \"(net)\" carrying amounts need no special handling.

   ## Why equity has a current-period line

   A balance sheet only balances when the period's profit is IN equity.
   Revenue and expense accounts are closed into retained earnings at
   year end (`kontor.l10n-au.closing/close-fiscal-year!`), so before that
   runs — i.e. for any interim balance sheet — the 4xxxx-9xxxx balances
   are sitting outside equity and assets exceed liabilities + equity by
   exactly the period's profit.

   Section F therefore carries the current-period result as two lines:
   income-side accounts (F.4), and expense-side accounts flipped by
   `:line/negate` (F.5). That is the standard interim presentation, and
   it makes the statement balance both before and after a close: once
   `close-fiscal-year!` has zeroed the P&L accounts into retained
   earnings those two lines compute to zero and F.3 carries the amount
   instead.

   ## GST is a liability, not equity

   GST collected (21500, BAS 1A) is a current liability (D.2); GST paid
   (11700, BAS 1B input-tax credits) is a current asset (A.4). Neither
   touches equity — revenue is booked net of GST — which is why the
   current-period income line (F.4, `\"4%\"`) is the tax-free net sales,
   and the statement still balances.

   `check` reports whether the accounting equation actually holds — see
   its docstring for what a non-zero difference means.

   Currency is not stamped per line: the report engine derives each
   line's commodity from its postings (note-196 F5), so an AUD book reads
   AUD throughout."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Classified balance sheet over the `kontor.l10n-au.chart` codes.

   The chart ships no intangibles / goodwill / long-term-deposit
   accounts, so there is no 'other non-current assets' section — a
   consumer that adds such accounts adds the line."
  {:statement/name    "Statement of Financial Position"
   :statement/country "AU"
   :statement/sections
   [{:section/code  "A"
     :section/label "Current assets"
     :section/lines
     [{:line/code "A.1" :line/label "Cash and cash equivalents"
       :line/codes ["11100"]}
      {:line/code "A.2" :line/label "Trade and other receivables, net of provision"
       :line/codes ["11200" "11250"]}
      {:line/code "A.3" :line/label "Inventories"
       :line/codes ["11310" "11320" "11330"]}
      {:line/code "A.4" :line/label "GST receivable (input tax credits)"
       :line/codes ["11700"]}
      {:line/code "A.5" :line/label "Prepayments"
       :line/codes ["11500"]}]}

    {:section/code  "B"
     :section/label "Non-current assets"
     :section/lines
     ;; Property, plant and equipment at cost less accumulated
     ;; depreciation — the contra accounts (12110/12210) net in here.
     [{:line/code "B.1" :line/label "Property, plant and equipment, net"
       :line/codes ["12100" "12110" "12200" "12210"]}]}

    {:section/code  "D"
     :section/label "Current liabilities"
     :section/lines
     [{:line/code "D.1" :line/label "Trade and other payables"
       :line/codes ["21100" "21300"]}
      {:line/code "D.2" :line/label "GST payable"
       :line/codes ["21500"]}
      {:line/code "D.3" :line/label "Employee and PAYG liabilities"
       :line/codes ["21700" "21800" "21900"]}
      {:line/code "D.4" :line/label "Short-term borrowings"
       :line/codes ["22100"]}]}

    {:section/code  "E"
     :section/label "Non-current liabilities"
     :section/lines
     [{:line/code "E.1" :line/label "Long-term borrowings"
       :line/codes ["23100"]}]}

    {:section/code  "F"
     :section/label "Equity"
     :section/lines
     [{:line/code "F.1" :line/label "Contributed equity"
       :line/codes ["31100"]}
      {:line/code "F.2" :line/label "Owner drawings"
       :line/codes ["31300"]}
      {:line/code "F.3" :line/label "Retained earnings"
       :line/codes ["31200"]}
      ;; Current-period result, held outside retained earnings until the
      ;; fiscal year is closed — see the namespace docstring.
      {:line/code "F.4" :line/label "Current-period income"
       :line/codes ["4%"]}
      {:line/code "F.5" :line/label "Current-period expenses"
       :line/codes ["5%" "6%" "71100" "91100"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :+ "D" :- "E" :- "F" :-})

(defn compute
  "Compute the balance sheet as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass
   `:through #inst \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` omits everything posted on Dec 31, where
   year-end depreciation and accruals land — and because it drops both
   sides of those entries the statement still BALANCES while being wrong.

   Returns the standard computed-statement map plus:

     :au.bs/total-assets      = sections A + B
     :au.bs/total-liabilities = sections D + E
     :au.bs/total-equity      = section F
     :au.bs/difference        = assets − (liabilities + equity); zero for
                                a balanced book
     :au.bs/balanced?         = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         assets   (reduce money/add (map sub ["A" "B"]))
         liabs    (reduce money/add (map sub ["D" "E"]))
         equity   (sub "F")
         diff     (money/sub assets (money/add liabs equity))]
     (assoc computed
            :au.bs/total-assets      assets
            :au.bs/total-liabilities liabs
            :au.bs/total-equity      equity
            :au.bs/difference        diff
            :au.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:au.bs/*` summary of [[compute]].

   A non-zero `:au.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger:
   the kernel's transact gate refuses any entry whose postings do not sum
   to zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:au.bs/total-assets :au.bs/total-liabilities
                    :au.bs/total-equity :au.bs/difference :au.bs/balanced?])))

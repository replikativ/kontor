(ns kontor.l10n-ca.bs
  "Canadian balance sheet (Statement of Financial Position), classified
   form.

   Like the income statement there is no statutory Canadian layout —
   ASPE (CPA Canada Handbook Part II §1521) and IFRS both prescribe a
   classified presentation (current vs non-current) but leave the line
   detail to judgement. This ships the conventional classified form:

     Assets      = current assets
     Liabilities = current liabilities
     Equity      = owner's equity + retained earnings
                   + current-period earnings (interim)

   ## GST/HST/PST/QST are balance-sheet items, not P&L items

   Tax collected on sales is a LIABILITY (owed to the CRA / a province /
   Revenu Québec): `2310` GST/HST, `2320`–`2322` PST/RST, `2330` QST.
   Tax paid on purchases that is recoverable is an ASSET: `1310` GST/HST
   input tax credit, `1320` QST input tax refund. All appear here; none
   is in the income statement (`kontor.l10n-ca.pnl`).

   ## What the shipped chart omits

   `kontor.l10n-ca.chart` is a minimal SMB chart with no fixed-asset,
   intangible or long-term-debt accounts, so this definition carries no
   \"Property and equipment\" / \"Long-term liabilities\" sections — an
   empty section would tag its (zero) subtotal :EUR and break the
   CAD-only total (note-196 F5). A consumer who adds such accounts
   appends the section against the kernel;
   `financial-statements/statement-coverage` reports any account no line
   covers.

   ## Why equity has a current-earnings line

   A balance sheet only balances when the period's profit is IN equity.
   Revenue and expense accounts are closed into retained earnings at
   fiscal-year end (`kontor.l10n-ca.closing`), so before that runs — for
   any interim balance sheet — the 4xxx–6xxx balances sit OUTSIDE equity
   and assets exceed liabilities + equity by exactly the period's net
   income.

   Section C therefore carries the current-period result as two lines:
   revenue-side accounts (`4%`), and expense-side accounts (`5%`/`6%`)
   flipped by `:line/negate`. That is the standard interim presentation
   and it makes the statement balance both before and after a close:
   once closing has zeroed the P&L accounts into `3100` those two lines
   compute to zero and retained earnings carries the amount instead.

   Per note-196 F5 the report engine derives each line's commodity (CAD)
   from the postings — no `:line/commodity` stamping, only the section
   `:total-sign-map`. `check` reports whether the accounting equation
   actually holds; see its docstring."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Classified balance sheet over `kontor.l10n-ca.chart`."
  {:statement/name    "Balance Sheet"
   :statement/country "CA"
   :statement/sections
   [{:section/code  "A"
     :section/label "Current assets"
     :section/lines
     [{:line/code "A.1" :line/label "Cash and bank"
       :line/codes ["1000" "1010"]}
      {:line/code "A.2" :line/label "Accounts receivable"
       :line/codes ["1100"]}
      {:line/code "A.3" :line/label "GST/HST input tax credits (recoverable)"
       :line/codes ["1310"]}
      {:line/code "A.4" :line/label "QST input tax refunds (recoverable)"
       :line/codes ["1320"]}]}

    {:section/code  "B"
     :section/label "Current liabilities"
     :section/lines
     [{:line/code "B.1" :line/label "Accounts payable" :line/codes ["2000"]}
      {:line/code "B.2" :line/label "GST/HST collected" :line/codes ["2310"]}
      {:line/code "B.3" :line/label "Provincial sales tax collected (PST/RST)"
       :line/codes ["2320" "2321" "2322"]}
      {:line/code "B.4" :line/label "QST collected" :line/codes ["2330"]}]}

    {:section/code  "C"
     :section/label "Equity"
     :section/lines
     [{:line/code "C.1" :line/label "Owner's equity" :line/codes ["3000"]}
      {:line/code "C.2" :line/label "Retained earnings" :line/codes ["3100"]}
      ;; Current-period result, held outside retained earnings until the
      ;; fiscal year is closed — see the namespace docstring.
      {:line/code "C.3" :line/label "Current-period revenue"
       :line/codes ["4%"]}
      {:line/code "C.4" :line/label "Current-period expenses"
       :line/codes ["5%" "6%"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :- "C" :-})

(defn compute
  "Compute the balance sheet as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass
   `:through #inst \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.

   Returns the standard computed-statement map plus:

     :ca.bs/total-assets      = section A
     :ca.bs/total-liabilities = section B
     :ca.bs/total-equity      = section C
     :ca.bs/difference        = assets − (liabilities + equity); zero for
                                a balanced book
     :ca.bs/balanced?         = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         assets   (sub "A")
         liabs    (sub "B")
         equity   (sub "C")
         diff     (money/sub assets (money/add liabs equity))]
     (assoc computed
            :ca.bs/total-assets      assets
            :ca.bs/total-liabilities liabs
            :ca.bs/total-equity      equity
            :ca.bs/difference        diff
            :ca.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:ca.bs/*` summary of [[compute]].

   A non-zero `:ca.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   kernel's transact gate refuses any entry whose postings do not sum to
   zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:ca.bs/total-assets :ca.bs/total-liabilities
                    :ca.bs/total-equity :ca.bs/difference :ca.bs/balanced?])))

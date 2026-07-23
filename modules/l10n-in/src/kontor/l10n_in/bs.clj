(ns kontor.l10n-in.bs
  "Indian Balance Sheet — Schedule III, Companies Act 2013 (Division II,
   Ind AS-aligned form).

   Schedule III fixes the vertical Balance Sheet layout as two totals
   that must agree:

     EQUITY AND LIABILITIES
       (1) Shareholders' funds   — share capital + reserves and surplus
       (2) Non-current liabilities — long-term borrowings + provisions
       (3) Current liabilities   — short-term borrowings, trade payables,
                                    other current liabilities (incl. the
                                    statutory GST / TDS heads), provisions
     ASSETS
       (1) Non-current assets    — PP&E (net), intangibles, investments,
                                    long-term loans and advances
       (2) Current assets        — inventories, trade receivables, cash
                                    and cash equivalents, short-term loans
                                    and advances (incl. GST input-tax
                                    credit and TDS receivable)

   Account codes target `kontor.l10n-in.chart`. Accumulated-depreciation
   account 110900 carries `:kontor.account/type :asset` in that chart, so
   its credit balance nets against gross PP&E automatically under
   `:sign :inflow` — `Property, plant and equipment (net)` needs no
   special handling. GST input-tax-credit (13xxxx) and TDS-receivable
   (132xxx) are assets; output GST / TDS payable (33xxxx) are current
   liabilities — the report picks each up on its own side.

   ## Why equity carries a current-period result

   A balance sheet only balances when the period's profit is IN equity.
   Under Schedule III the year's profit lands in `Reserves and surplus`
   (the Surplus / Statement-of-P&L balance, account 220900) only after
   the year is closed. Before that — for any interim balance sheet — the
   4xxxxx / 5xxxxx revenue and expense balances sit OUTSIDE equity and
   assets exceed equity + liabilities by exactly the period's profit.

   Section E therefore carries the current-period result as two lines:
   the revenue side (`4%`) and the expense side (`5%`, flipped by
   `:line/negate`). Their sum is the profit not yet transferred to
   reserves. Once `kontor.l10n-in.closing` transfers the P&L result into
   220900 those two lines compute to zero and `Reserves and surplus`
   carries the amount instead — so the statement balances both before and
   after the close.

   `check` reports whether the accounting equation actually holds — see
   its docstring for what a non-zero difference means.

   Currency: see the note in `kontor.l10n-in.pnl` — every line is stamped
   `:INR` as the empty-line fallback on this non-EUR book; the note-196
   F5 derivation still wins for any populated line."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(defn- in-inr
  "Stamp every section + line commodity :INR (empty-line fallback)."
  [statement]
  (update statement :statement/sections
          (fn [sections]
            (mapv (fn [s]
                    (-> s
                        (assoc :section/commodity :INR)
                        (update :section/lines
                                (fn [ls] (mapv #(assoc % :line/commodity :INR) ls)))))
                  sections))))

(def definition
  "Schedule III vertical Balance Sheet over the `kontor.l10n-in.chart`
   codes."
  (in-inr
   {:statement/name    "Balance Sheet"
    :statement/country "IN"
    :statement/sections
    [;; ── ASSETS ───────────────────────────────────────────────────────
     {:section/code  "A"
      :section/label "Non-current assets"
      :section/lines
      [{:line/code "A.1" :line/label "Property, plant and equipment (net)"
        :line/codes ["110000" "110100" "110200" "110300" "110400"
                     "110500" "110600" "110900"]}
       {:line/code "A.2" :line/label "Intangible assets" :line/codes ["111%"]}
       {:line/code "A.3" :line/label "Non-current investments" :line/codes ["115%"]}
       {:line/code "A.4" :line/label "Long-term loans and advances" :line/codes ["116%"]}]}

     {:section/code  "B"
      :section/label "Current assets"
      :section/lines
      [{:line/code "B.1" :line/label "Inventories" :line/codes ["120%"]}
       {:line/code "B.2" :line/label "Trade receivables" :line/codes ["121%"]}
       {:line/code "B.3" :line/label "Cash and cash equivalents" :line/codes ["122%"]}
       {:line/code "B.4" :line/label "Short-term loans and advances"
        :line/codes ["123%" "124%"]}
       ;; GST input-tax credit + TDS receivable are recoverable assets
       {:line/code "B.5" :line/label "Balances with government (ITC + TDS receivable)"
        :line/codes ["13%"]}]}

     ;; ── EQUITY AND LIABILITIES ───────────────────────────────────────
     {:section/code  "C"
      :section/label "Non-current liabilities"
      :section/lines
      [{:line/code "C.1" :line/label "Long-term borrowings" :line/codes ["310%"]}
       {:line/code "C.2" :line/label "Long-term provisions" :line/codes ["311%"]}]}

     {:section/code  "D"
      :section/label "Current liabilities"
      :section/lines
      [{:line/code "D.1" :line/label "Short-term borrowings" :line/codes ["322%"]}
       {:line/code "D.2" :line/label "Trade payables" :line/codes ["320%"]}
       {:line/code "D.3" :line/label "Other current liabilities and advances"
        :line/codes ["321%" "323%"]}
       {:line/code "D.4" :line/label "Short-term provisions" :line/codes ["324%"]}
       ;; statutory heads: output GST + RCM + TDS payable
       {:line/code "D.5" :line/label "GST / RCM / TDS payable" :line/codes ["33%"]}]}

     {:section/code  "E"
      :section/label "Shareholders' funds"
      :section/lines
      [{:line/code "E.1" :line/label "Share capital" :line/codes ["21%"]}
       {:line/code "E.2" :line/label "Reserves and surplus" :line/codes ["22%"]}
       ;; Current-period result, held outside reserves until the year is
       ;; closed — see the namespace docstring.
       {:line/code "E.3" :line/label "Surplus for the period — revenue"
        :line/codes ["4%"]}
       {:line/code "E.4" :line/label "Surplus for the period — expenses"
        :line/codes ["5%"] :line/negate true}]}]}))

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :+ "C" :- "D" :- "E" :-})

(defn compute
  "Compute the Balance Sheet as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a 31 March balance sheet pass
   `:through #inst \"2026-03-31\"` (inclusive) or `:to #inst
   \"2026-04-01\"`. `:to #inst \"2026-03-31\"` omits everything posted on
   31 March — where year-end depreciation and accruals land — and because
   it drops both sides of those entries the statement still BALANCES while
   being wrong.

   Returns the standard computed-statement map plus:

     :in.bs/total-assets      = sections A + B
     :in.bs/total-liabilities = sections C + D
     :in.bs/total-equity      = section E
     :in.bs/difference        = assets − (liabilities + equity); zero for
                                a balanced book
     :in.bs/balanced?         = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         assets   (reduce money/add (map sub ["A" "B"]))
         liabs    (reduce money/add (map sub ["C" "D"]))
         equity   (sub "E")
         diff     (money/sub assets (money/add liabs equity))]
     (assoc computed
            :in.bs/total-assets      assets
            :in.bs/total-liabilities liabs
            :in.bs/total-equity      equity
            :in.bs/difference        diff
            :in.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:in.bs/*` summary of [[compute]].

   A non-zero `:in.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   kernel's transact gate refuses any entry whose postings do not sum to
   zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:in.bs/total-assets :in.bs/total-liabilities
                    :in.bs/total-equity :in.bs/difference :in.bs/balanced?])))

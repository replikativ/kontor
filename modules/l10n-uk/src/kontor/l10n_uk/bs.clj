(ns kontor.l10n-uk.bs
  "UK balance sheet — Companies Act 2006 Schedule 1, Part 1, Format 1
   (statement of financial position), the vertical form UK FRS 102 /
   FRS 105 accounts use.

   Sch 1 Format 1 SOFP runs:

     B  Fixed assets            (I intangible, II tangible, III investments)
     C  Current assets          (I stocks, II debtors, III investments,
                                 IV cash at bank and in hand)
     D  Prepayments and accrued income
     E  Creditors: amounts falling due within one year
     H  Creditors: amounts falling due after more than one year
     I  Provisions for liabilities
     K  Capital and reserves    (I called-up share capital, II share
                                 premium, III revaluation reserve,
                                 IV other reserves, V profit and loss
                                 account)

   Presented here classified into five sections — the three-asset /
   two-liability-and-equity split every classified BS uses:

     Assets      = fixed assets + current assets
     Liabilities = creditors < 1yr + creditors > 1yr
     Equity      = called-up capital + share premium + retained earnings
                   + current-period result

   ## No chart module

   l10n-uk ships no chart; codes follow the UK nominal convention
   documented in `kontor.l10n-uk.pnl` (0xxx fixed, 1xxx current asset,
   2xxx liability, 3xxx capital). Accumulated-depreciation accounts carry
   `:kontor.account/type :asset`, so their credit balances net against
   gross cost automatically under `:sign :inflow` — \"tangible assets,
   net\" needs no special handling.

   ## Why equity has a current-period line

   A balance sheet only balances when the period's profit is IN equity.
   Turnover and expense accounts (4xxx-8xxx) are closed into the profit-
   and-loss reserve at year-end; before that runs — i.e. for any interim
   balance sheet — those balances sit OUTSIDE equity and assets exceed
   liabilities + equity by exactly the period's profit.

   Section E therefore carries the current-period result as two lines:
   the income side (4xxx), and the expense side (5xxx-8xxx) flipped by
   `:line/negate`. This is the standard interim presentation and makes
   the statement balance both before and after a close: once the P&L
   accounts have been rolled into the retained-earnings account those two
   lines compute to zero and retained earnings carries the amount
   instead. (l10n-uk ships no closing helper yet; a consumer supplies
   one, or reads the interim form.)

   VAT collected on sales is a 2xxx creditor owed to HMRC, so it lands in
   current liabilities (section D), never in equity or turnover."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; See the note in kontor.l10n-uk.pnl: F5 derives a populated line's
;; commodity from its postings, but a line matching no account has none to
;; derive from and defaults to :EUR. Several BS lines deliberately cover a
;; fuller nominal ledger than a single consumer ships, so stamping :GBP
;; keeps those empty lines zero-GBP and a section subtotal never hits a
;; cross-commodity add.
(defn- in-gbp [statement]
  (update statement :statement/sections
          (fn [sections]
            (mapv (fn [s]
                    (-> s
                        (assoc :section/commodity :GBP)
                        (update :section/lines
                                (fn [ls] (mapv #(assoc % :line/commodity :GBP) ls)))))
                  sections))))

(def definition
  "Classified balance sheet over the UK nominal codes."
  (in-gbp
   {:statement/name    "Balance Sheet"
    :statement/country "GB"
    :statement/sections
   [{:section/code  "A"
     :section/label "Fixed assets"
     :section/lines
     [{:line/code "A.1" :line/label "Intangible assets"
       :line/codes ["0010" "0011"]}
      ;; tangible net: gross cost + accumulated depreciation (contra,
      ;; :asset type, credit balance) net automatically
      {:line/code "A.2" :line/label "Tangible assets, net"
       :line/codes ["0020" "0021" "0030" "0031"]}
      {:line/code "A.3" :line/label "Investments" :line/codes ["0040"]}]}

    {:section/code  "B"
     :section/label "Current assets"
     :section/lines
     [{:line/code "B.1" :line/label "Stocks" :line/codes ["1000" "1010"]}
      {:line/code "B.2" :line/label "Debtors"
       :line/codes ["1100" "1110" "1120"]}
      {:line/code "B.3" :line/label "Prepayments and accrued income"
       :line/codes ["1300" "1310"]}
      {:line/code "B.4" :line/label "Cash at bank and in hand"
       :line/codes ["1200" "1210" "1220"]}]}

    {:section/code  "C"
     :section/label "Creditors: amounts falling due within one year"
     :section/lines
     [{:line/code "C.1" :line/label "Trade creditors" :line/codes ["2100"]}
      {:line/code "C.2" :line/label "Taxation and social security (VAT, PAYE/NIC)"
       :line/codes ["2200" "2210" "2220"]}
      {:line/code "C.3" :line/label "Accruals and deferred income"
       :line/codes ["2300" "2310"]}
      {:line/code "C.4" :line/label "Other creditors" :line/codes ["2320" "2330"]}]}

    {:section/code  "D"
     :section/label "Creditors: amounts falling due after more than one year"
     :section/lines
     [{:line/code "D.1" :line/label "Bank loans and overdrafts"
       :line/codes ["2400" "2410"]}]}

    {:section/code  "E"
     :section/label "Capital and reserves"
     :section/lines
     [{:line/code "E.1" :line/label "Called-up share capital" :line/codes ["3000"]}
      {:line/code "E.2" :line/label "Share premium account" :line/codes ["3100"]}
      {:line/code "E.3" :line/label "Profit and loss account (brought forward)"
       :line/codes ["3200"]}
      ;; Dividends are an :equity account with a debit balance; under
      ;; :inflow the engine already reports equity credit-natural, so a
      ;; debit reads negative (reducing equity) with NO :line/negate —
      ;; exactly like owner distributions in the US template's section F.
      {:line/code "E.4" :line/label "Dividends" :line/codes ["3300"]}
      ;; Current-period result, held outside the reserves until year-end
      ;; close — see the namespace docstring.
      {:line/code "E.5" :line/label "Current-period income"
       :line/codes ["4%"]}
      {:line/code "E.6" :line/label "Current-period expenses"
       :line/codes ["5%" "6%" "7%" "8%"]
       :line/negate true}]}]}))

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), zero for a balanced book."
  {"A" :+ "B" :+ "C" :- "D" :- "E" :-})

(defn compute
  "Compute the balance sheet as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`. `:to #inst
   \"2026-12-31\"` omits everything posted on Dec 31 (year-end
   depreciation, accruals) and, because it drops both sides of those
   entries, the statement still BALANCES while being wrong.

   Returns the standard computed-statement map plus:

     :uk.bs/total-assets      = sections A + B
     :uk.bs/total-liabilities = sections C + D
     :uk.bs/total-equity      = section E
     :uk.bs/difference        = assets − (liabilities + equity); zero
                                for a balanced book
     :uk.bs/balanced?         = whether that difference is zero"
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
            :uk.bs/total-assets      assets
            :uk.bs/total-liabilities liabs
            :uk.bs/total-equity      equity
            :uk.bs/difference        diff
            :uk.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`/`:through`.
   Returns the `:uk.bs/*` summary of [[compute]].

   A non-zero `:uk.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a consumer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   transact gate refuses any entry whose postings do not sum to zero, so
   the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:uk.bs/total-assets :uk.bs/total-liabilities
                    :uk.bs/total-equity :uk.bs/difference :uk.bs/balanced?])))

(ns kontor.l10n-uk.pnl
  "UK profit-and-loss account — Companies Act 2006 Schedule 1, Part 1,
   Format 1 (analysis by function), the layout the overwhelming majority
   of UK companies filing FRS 102 / FRS 105 accounts use.

   Sch 1 Format 1 runs (items 1-14, abbreviated to the load-bearing set
   a private company actually posts to):

     1  Turnover
     2  Cost of sales
     3  Gross profit or loss                (= 1 − 2)
     4  Distribution costs
     5  Administrative expenses
     6  Other operating income
        Operating profit or loss           (= 3 − 4 − 5 + 6; conventional,
                                            not a numbered Sch 1 item)
     12 Interest receivable and similar income
     13 Interest payable and similar charges
     14 Profit or loss on ordinary activities before taxation

   UK corporation tax is deferred in kontor (no CIT provider — the iXBRL
   filing gate is not built), so this statement stops at item 14, profit
   before taxation, exactly as the US P&L stops at pre-tax income. A
   consumer that books a CT charge to a tax account adds a section for it.

   ## No chart module

   Unlike DE (SKR04) or US (the SMB chart), the l10n-uk module ships no
   chart of accounts — a UK consumer brings their own nominal ledger.
   The account codes below therefore follow the widely-used UK nominal
   convention (Sage-50 / QuickBooks-UK style):

     0xxx  fixed assets
     1xxx  current assets
     2xxx  liabilities (current + long-term)
     3xxx  capital and reserves
     4xxx  turnover / other operating + finance income
     5xxx  cost of sales
     6xxx  distribution costs
     7xxx  administrative expenses
     8xxx  finance costs

   Prefix patterns (`\"7%\"` = all 7xxx) are used wherever a whole range
   rolls into one Sch 1 item, so a consumer who adds
   `7250 Admin-Expenses:Subscriptions` is picked up without touching this
   definition. Turnover and the non-turnover 4xxx income items
   (other-operating, interest) are enumerated by exact code so a `\"4%\"`
   prefix cannot pull finance income into turnover.

   VAT is never a P&L line: output VAT collected on a sale is a liability
   owed to HMRC (a 2xxx creditor), so turnover here is always net of VAT."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; Commodity needs no per-line stamp: the report engine derives a
;; populated line's commodity from its postings (note 196 F5), a zero is
;; the additive identity across commodities so the empty lines here (this
;; def deliberately covers a fuller UK nominal ledger than any single
;; consumer ships) fold cleanly (F5b), and an all-empty section inherits
;; the book commodity — so a GBP book reports :GBP throughout.
(def definition
  "Companies Act 2006 Sch 1 Format 1 P&L over the UK nominal codes."
  {:statement/name    "Profit and Loss Account"
   :statement/country "GB"
   :statement/sections
   [{:section/code  "1"
     :section/label "Turnover"
     :section/lines
     [{:line/code "1.1" :line/label "Sale of goods"
       :line/codes ["4000" "4010" "4020"]}
      {:line/code "1.2" :line/label "Rendering of services"
       :line/codes ["4100" "4110"]}]}

    {:section/code  "2"
     :section/label "Cost of sales"
     :section/lines
     [{:line/code "2.1" :line/label "Purchases / materials" :line/codes ["5000"]}
      {:line/code "2.2" :line/label "Direct labour" :line/codes ["5100"]}
      {:line/code "2.3" :line/label "Other direct costs" :line/codes ["5200"]}]}

    {:section/code  "3"
     :section/label "Distribution costs"
     :section/lines
     [{:line/code "3.1" :line/label "Carriage and delivery" :line/codes ["6000"]}
      {:line/code "3.2" :line/label "Advertising and marketing" :line/codes ["6100"]}]}

    {:section/code  "4"
     :section/label "Administrative expenses"
     :section/lines
     [{:line/code "4.1" :line/label "Wages, salaries and NIC"
       :line/codes ["7000" "7010"]}
      {:line/code "4.2" :line/label "Rent, rates and utilities"
       :line/codes ["7100" "7110"]}
      {:line/code "4.3" :line/label "Insurance" :line/codes ["7200"]}
      {:line/code "4.4" :line/label "Legal and professional fees" :line/codes ["7300"]}
      {:line/code "4.5" :line/label "Depreciation and amortisation" :line/codes ["7400"]}
      {:line/code "4.6" :line/label "Other administrative expenses"
       :line/codes ["7500" "7600" "7700" "7800" "7900"]}]}

    {:section/code  "5"
     :section/label "Other operating income"
     :section/lines
     [{:line/code "5.1" :line/label "Grants, rents and sundry income"
       :line/codes ["4900" "4910"]}]}

    {:section/code  "6"
     :section/label "Interest receivable and similar income"
     :section/lines
     [{:line/code "6.1" :line/label "Bank and other interest receivable"
       :line/codes ["4950" "4960"]}]}

    {:section/code  "7"
     :section/label "Interest payable and similar charges"
     :section/lines
     [{:line/code "7.1" :line/label "Interest payable on loans and overdrafts"
       :line/codes ["8000" "8010"]}]}]})

(def ^:private sign-map
  "Turnover, other operating income and interest receivable add; cost of
   sales, distribution, administrative expenses and interest payable
   subtract. The total is Sch 1 item 14 — profit on ordinary activities
   before taxation."
  {"1" :+ "2" :- "3" :- "4" :- "5" :+ "6" :+ "7" :-})

(defn compute
  "Compute the P&L over the window `[:from, :to)`.

   Opts are forwarded verbatim to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`). `:from` + `:to`/`:through` are what make it a period
   statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`. `:to #inst
   \"2026-12-31\"` silently omits everything posted on Dec 31 — where
   year-end depreciation and accruals live. Passing both `:to` and
   `:through` throws.

   Returns the standard computed-statement map plus three derived
   subtotals under `:uk.pnl/*`:

     :uk.pnl/gross-profit       = turnover − cost of sales
     :uk.pnl/operating-profit   = gross profit − distribution
                                  − administrative + other operating income
     :uk.pnl/profit-before-tax  = the statement total (Sch 1 item 14)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub       #(fs/section-subtotal computed %)
         turnover  (sub "1")
         cogs      (sub "2")
         gross     (money/sub turnover cogs)
         operating (-> gross
                       (money/sub (sub "3"))
                       (money/sub (sub "4"))
                       (money/add (sub "5")))]
     (assoc computed
            :uk.pnl/gross-profit      gross
            :uk.pnl/operating-profit  operating
            :uk.pnl/profit-before-tax (:statement/total computed)))))

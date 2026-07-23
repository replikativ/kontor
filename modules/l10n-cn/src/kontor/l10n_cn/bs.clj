(ns kontor.l10n-cn.bs
  "Chinese balance sheet — 资产负债表 (Statement of Financial Position),
   classified form.

   Follows the conventional MOF ASBE / 小企业会计准则 model 资产负债表
   ordering: current before non-current on the asset side, current before
   long-term on the liability side, then owner's equity:

     流动资产     Current assets
     非流动资产   Non-current assets
     流动负债     Current liabilities
     非流动负债   Non-current liabilities
     所有者权益   Owner's equity

   Account codes target `kontor.l10n-cn.chart`. Contra accounts carry
   their parent's `:kontor.account/type` in that chart — 累计折旧 (1602)
   and 累计摊销 (1702) are `:asset`, 坏账准备 (1231) is `:asset` — so their
   credit balances net against gross under `:sign :inflow` automatically;
   \"固定资产 (net)\" needs no special handling.

   ## The 应交税费 (2221) tree is split by TYPE, not by prefix

   The MOF 应交税费 sub-tree mixes asset and liability memo-columns under
   the one 2221 code family: 进项税额 (2221.01.02), 待抵扣/待认证进项税额
   (2221.04 / 2221.05), 留抵税额 (2221.07) and 出口退税 (2221.01.05) are
   `:asset`; everything else (销项税额 2221.01.01, the CIT/surcharge
   sub-accounts …) is `:liability`. A `\"2221%\"` prefix would sweep the
   asset columns into the liability section and unbalance the sheet, so
   the asset VAT columns are enumerated on the asset side and the
   liability tax sub-accounts are enumerated on the liability side.

   ## Why equity has a current-period-result line

   A balance sheet only balances when the period's profit is IN equity.
   Revenue and expense accounts (5xxx income / cost, 6xxx non-operating +
   income tax) are closed into 本年利润 / 利润分配 at fiscal-year end
   (`kontor.l10n-cn.closing`), so before that runs — i.e. for any interim
   balance sheet — those balances sit OUTSIDE equity and assets exceed
   liabilities + equity by exactly the period's net profit.

   Section F therefore carries the current-period result as two lines:
   the income-side accounts, and the expense-side accounts flipped by
   `:line/negate`. Once the year is closed those lines compute to zero and
   本年利润 / 利润分配 (3103 / 3104) carry the amount instead — so the sheet
   balances both before and after a close. This mirrors the section-F
   pattern documented in `kontor.l10n-us.bs`.

   `check` reports whether the accounting equation actually holds — see its
   docstring for what a non-zero difference means."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; See the note in kontor.l10n-cn.pnl. F5 derives a line's commodity from
;; the postings it sums, a zero is the additive identity across
;; commodities so the empty lines a BS always has (no notes payable this
;; year, no capital surplus, …) fold cleanly (F5b), and an all-empty
;; section inherits the book commodity — so a CNY book reports :CNY.
(def definition
  "Classified 资产负债表 over the `kontor.l10n-cn.chart` codes."
  {:statement/name    "资产负债表 (Balance Sheet)"
   :statement/country "CN"
   :statement/sections
   [{:section/code  "A"
     :section/label "流动资产 Current assets"
     :section/lines
     [{:line/code "A.1" :line/label "货币资金 Cash and bank"
       :line/codes ["1001" "1002"]}
      {:line/code "A.2" :line/label "应收票据 Notes receivable"
       :line/codes ["1121"]}
      {:line/code "A.3" :line/label "应收账款 (净额) Accounts receivable, net"
       :line/codes ["1122" "1231"]}
      {:line/code "A.4" :line/label "预付账款 Prepayments"
       :line/codes ["1123"]}
      {:line/code "A.5" :line/label "其他应收款 Other receivables"
       :line/codes ["1221"]}
      {:line/code "A.6" :line/label "存货 Inventories"
       :line/codes ["1403" "1405" "1406"]}
      ;; asset-typed VAT memo-columns under 应交税费 — see namespace docstring
      {:line/code "A.7" :line/label "增值税进项 (待抵扣/留抵) Deductible / carried-forward input VAT"
       :line/codes ["2221.01.02" "2221.01.05" "2221.04" "2221.05" "2221.07"]}]}

    {:section/code  "B"
     :section/label "非流动资产 Non-current assets"
     :section/lines
     [{:line/code "B.1" :line/label "固定资产 (净额) Fixed assets, net of accumulated depreciation"
       :line/codes ["1601" "1602"]}
      {:line/code "B.2" :line/label "在建工程 Construction in progress"
       :line/codes ["1604"]}
      {:line/code "B.3" :line/label "无形资产 (净额) Intangible assets, net of accumulated amortization"
       :line/codes ["1701" "1702"]}]}

    {:section/code  "D"
     :section/label "流动负债 Current liabilities"
     :section/lines
     [{:line/code "D.1" :line/label "短期借款 Short-term borrowings"
       :line/codes ["2001"]}
      {:line/code "D.2" :line/label "应付票据 Notes payable"
       :line/codes ["2201"]}
      {:line/code "D.3" :line/label "应付账款 Accounts payable"
       :line/codes ["2202"]}
      {:line/code "D.4" :line/label "预收账款 Advances from customers"
       :line/codes ["2203"]}
      {:line/code "D.5" :line/label "应付职工薪酬 Employee compensation payable"
       :line/codes ["2211"]}
      ;; 应交税费 — the liability-typed sub-accounts, enumerated (a "2221%"
      ;; prefix would also pull in the asset VAT columns shown in A.7).
      {:line/code "D.6" :line/label "应交税费 Taxes payable"
       :line/codes ["2221" "2221.01.01" "2221.01.03" "2221.01.04"
                    "2221.01.06" "2221.01.07" "2221.01.08" "2221.01.09"
                    "2221.01.10" "2221.01.99" "2221.02" "2221.03"
                    "2221.06" "2221.09" "2221.10" "2221.11" "2221.12"
                    "2221.13" "2221.14" "2221.15" "2221.16"]}]}

    {:section/code  "E"
     :section/label "非流动负债 Non-current liabilities"
     :section/lines
     [{:line/code "E.1" :line/label "长期借款 Long-term borrowings"
       :line/codes ["2401"]}]}

    {:section/code  "F"
     :section/label "所有者权益 Owner's equity"
     :section/lines
     [{:line/code "F.1" :line/label "实收资本 Paid-in capital"
       :line/codes ["3001"]}
      {:line/code "F.2" :line/label "资本公积 Capital surplus"
       :line/codes ["3002"]}
      {:line/code "F.3" :line/label "盈余公积 Surplus reserves"
       :line/codes ["3101"]}
      {:line/code "F.4" :line/label "未分配利润 Retained earnings"
       :line/codes ["3103" "3104"]}
      ;; Current-period result, held outside equity until the fiscal year
      ;; is closed — see the namespace docstring.
      {:line/code "F.5" :line/label "本期利润 — 收入类 Current-period income accounts"
       :line/codes ["5001%" "5051" "5201" "6001"]}
      {:line/code "F.6" :line/label "本期利润 — 费用类 Current-period expense accounts"
       :line/codes ["5401" "5601" "5602" "5603" "5604" "6101" "6301"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :+ "D" :- "E" :- "F" :-})

(defn compute
  "Compute the 资产负债表 as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass `:through #inst
   \"2026-12-31\"` (inclusive) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` omits everything posted on Dec 31 (year-end
   depreciation, the tax provision) and, because it drops both sides of
   those entries, the statement still BALANCES while being wrong.

   Returns the standard computed-statement map plus:

     :cn.bs/total-assets      = sections A + B
     :cn.bs/total-liabilities = sections D + E
     :cn.bs/total-equity      = section F
     :cn.bs/difference        = assets − (liabilities + equity); zero for a
                                balanced book
     :cn.bs/balanced?         = whether that difference is zero"
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
            :cn.bs/total-assets      assets
            :cn.bs/total-liabilities liabs
            :cn.bs/total-equity      equity
            :cn.bs/difference        diff
            :cn.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:cn.bs/*` summary of [[compute]].

   A non-zero `:cn.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a customer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger: the
   kernel's transact gate refuses any entry whose postings do not sum to
   zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:cn.bs/total-assets :cn.bs/total-liabilities
                    :cn.bs/total-equity :cn.bs/difference :cn.bs/balanced?])))

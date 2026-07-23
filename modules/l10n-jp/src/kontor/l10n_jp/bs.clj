(ns kontor.l10n-jp.bs
  "Japanese balance sheet — 貸借対照表 (Taishaku Taishō-hyō), the
   report-form (報告式) classified layout of 会社計算規則 §73ff /
   財務諸表等規則.

   The three-part 会社計算規則 §73 structure, in conventional order:

     資産の部        (Assets)
       I. 流動資産    (current assets)
       II. 固定資産   (fixed / non-current assets)
     負債の部        (Liabilities)
       I. 流動負債    (current liabilities)
       II. 固定負債   (long-term liabilities)
     純資産の部       (Net assets / equity)
       I. 株主資本    (shareholders' equity)

   資産の部 = 負債の部 + 純資産の部 — the accounting equation, which
   `check` verifies.

   Account codes target the J-GAAP-style skeleton in
   `resources/kontor/l10n_jp/chart.edn`. The 減価償却累計額 (accumulated
   depreciation) account 155000 carries `:kontor.account/type :asset`, so
   its credit balance nets against gross 固定資産 automatically under the
   report engine's `:sign :inflow` (which flips sign by account type) —
   the 間接法 (indirect) contra presentation needs no special handling.

   仮受消費税 (215xxx, output JCT collected) is a 流動負債, NOT revenue —
   it belongs to the tax authority and is shown here as a current
   liability, mirroring the 税抜方式 treatment in `kontor.l10n-jp.pnl`.

   ## Why 純資産 carries a current-earnings line

   A balance sheet only balances when the period's profit is IN equity.
   Revenue and expense accounts (4xxxxx–9xxxxx) are closed into
   繰越利益剰余金 (retained earnings, 330000) at fiscal-year end
   (`kontor.l10n-jp.closing/close-jp-fiscal-year!`), so before that runs —
   any interim balance sheet — those balances sit OUTSIDE equity and
   assets exceed liabilities + equity by exactly the period's net income.

   Section E therefore carries the current-period result as two lines:
   the income accounts (E.4) and the expense accounts flipped by
   `:line/negate` (E.5). Their sum is 当期純利益. That makes the statement
   balance both before and after the close: once the close has zeroed the
   P&L accounts into 330000, E.4/E.5 compute to zero and 利益剰余金 carries
   the amount instead. This is the 会社計算規則 §76 当期純利益 presentation
   within 株主資本, applied to an interim book.

   Because the whole chart is JPY, no line stamps `:line/commodity`: the
   report engine derives every line's currency from the postings it sums
   (note-196 F5)."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "Classified 貸借対照表 over the `kontor.l10n-jp.chart` codes."
  {:statement/name    "貸借対照表 (Balance Sheet)"
   :statement/country "JP"
   :statement/sections
   ;; ---------- 資産の部 (Assets) ----------
   [{:section/code  "A"
     :section/label "流動資産 (Current assets)"
     :section/lines
     [{:line/code "A.1" :line/label "現金及び預金 (Cash and deposits)"
       :line/codes ["110%"]}
      {:line/code "A.2" :line/label "売上債権 (Trade receivables)"
       :line/codes ["121000" "122000"]}
      {:line/code "A.3" :line/label "棚卸資産 (Inventory)"
       :line/codes ["130000"]}
      {:line/code "A.4" :line/label "仮払消費税 (Input consumption tax)"
       :line/codes ["180%"]}]}

    {:section/code  "B"
     :section/label "固定資産 (Fixed assets)"
     :section/lines
     [{:line/code "B.1" :line/label "有形固定資産 (Property, plant & equipment)"
       :line/codes ["151000" "152000" "153000"]}
      ;; contra-asset: 減価償却累計額 carries :type :asset, credit balance
      ;; nets negative under :sign :inflow (間接法 indirect presentation)
      {:line/code "B.2" :line/label "減価償却累計額 (Accumulated depreciation)"
       :line/codes ["155000"]}]}

    ;; ---------- 負債の部 (Liabilities) ----------
    {:section/code  "C"
     :section/label "流動負債 (Current liabilities)"
     :section/lines
     [{:line/code "C.1" :line/label "仕入債務 (Trade payables)"
       :line/codes ["211000" "212000"]}
      {:line/code "C.2" :line/label "短期借入金 (Short-term loans payable)"
       :line/codes ["213000"]}
      {:line/code "C.3" :line/label "未払金・未払費用 (Accrued liabilities)"
       :line/codes ["214000"]}
      {:line/code "C.4" :line/label "仮受消費税 (Output consumption tax)"
       :line/codes ["215%"]}]}

    {:section/code  "D"
     :section/label "固定負債 (Long-term liabilities)"
     :section/lines
     [{:line/code "D.1" :line/label "長期借入金 (Long-term loans payable)"
       :line/codes ["220000"]}]}

    ;; ---------- 純資産の部 (Net assets / equity) ----------
    {:section/code  "E"
     :section/label "純資産 (Net assets / shareholders' equity)"
     :section/lines
     [{:line/code "E.1" :line/label "資本金 (Capital stock)"
       :line/codes ["310000"]}
      {:line/code "E.2" :line/label "資本剰余金 (Capital surplus)"
       :line/codes ["320000"]}
      {:line/code "E.3" :line/label "利益剰余金 (Retained earnings)"
       :line/codes ["330000"]}
      ;; Current-period result, held outside 利益剰余金 until the fiscal
      ;; year is closed — see the namespace docstring.
      {:line/code "E.4" :line/label "当期純利益 — 収益 (Current-period income)"
       :line/codes ["4%" "710000"]}
      {:line/code "E.5" :line/label "当期純利益 — 費用 (Current-period expense)"
       :line/codes ["5%" "6%" "720000" "9%"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = assets −
   (liabilities + equity), which is zero for a balanced book."
  {"A" :+ "B" :+ "C" :- "D" :- "E" :-})

(defn compute
  "Compute the 貸借対照表 as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a 決算日 2027-03-31 balance sheet pass
   `:through #inst \"2027-03-31\"` (inclusive) or `:to #inst \"2027-04-01\"`.
   `:to #inst \"2027-03-31\"` omits everything posted on Mar 31 (year-end
   depreciation, accruals) — and because it drops both sides of those
   entries the statement still BALANCES while being wrong.

   Returns the standard computed-statement map plus:

     :jp.bs/total-assets      = sections A + B      (資産合計)
     :jp.bs/total-liabilities = sections C + D      (負債合計)
     :jp.bs/total-equity      = section  E          (純資産合計)
     :jp.bs/difference        = assets − (liabilities + equity); zero
                                for a balanced book
     :jp.bs/balanced?         = whether that difference is zero"
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
            :jp.bs/total-assets      assets
            :jp.bs/total-liabilities liabs
            :jp.bs/total-equity      equity
            :jp.bs/difference        diff
            :jp.bs/balanced?         (money/zero? diff)))))

(defn check
  "Assert the accounting equation (資産 = 負債 + 純資産) as of `:to`.
   Returns the `:jp.bs/*` summary of [[compute]].

   A non-zero `:jp.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a consumer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger:
   the kernel's transact gate refuses any entry whose postings do not sum
   to zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:jp.bs/total-assets :jp.bs/total-liabilities
                    :jp.bs/total-equity :jp.bs/difference :jp.bs/balanced?])))

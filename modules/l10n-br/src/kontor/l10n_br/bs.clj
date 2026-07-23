(ns kontor.l10n-br.bs
  "Brazilian balance sheet — Balanço Patrimonial per Lei 6.404/76
   (Lei das S.A.) art. 178 and CPC 26 (R1), classified form.

   art. 178 fixes the two sides:

     ATIVO (assets), in decreasing liquidity
       Ativo Circulante        (current assets)          — 1.01.xx
       Ativo Não Circulante    (non-current assets)      — 1.02.xx
         realizável a longo prazo / investimentos /
         imobilizado / intangível

     PASSIVO (liabilities + equity)
       Passivo Circulante      (current liabilities)     — 2.01.xx
       Passivo Não Circulante  (long-term liabilities)   — 2.02.xx
       Patrimônio Líquido      (equity)                  — 2.03.xx
         capital social / reservas / lucros acumulados

   Account codes target the Plano de Contas Referencial-aligned starter
   in `kontor.l10n-br.chart`. The chart ships current-asset, non-current
   asset, current-liability and equity accounts; it has no
   Passivo Não Circulante (2.02.xx) accounts yet, so section D below
   claims the `\"2.02%\"` sub-tree and renders zero until a consumer adds
   long-term-loan / deferred-tax accounts there. It is kept in the
   layout so the slot exists and picks those up automatically.

   Contra accounts need no special handling: the chart types the
   allowance for doubtful accounts (1.01.03.99.01) and accumulated
   depreciation (1.02.03.99.01) as `:asset`, so their credit balances
   net against gross receivables / PP&E under `:sign :inflow` — the
   \"(-)\" lines fold in automatically.

   Commodity is derived from the POSTINGS (note-196 F5 —
   `resolve-commodity-symbol` wins over a line's declared `:commodity`),
   so every populated line is tagged `:BRL` off its own postings. An
   EMPTY line (codes that match no posting) has nothing to derive from
   and would fall back to the engine's `:EUR` default; `in-brl` supplies
   `:BRL` as that fallback so the several optional sections here
   (Passivo Não Circulante, reservas, tributos a recuperar on a book
   without them) stay `:BRL` and do not trip a cross-commodity add.

   ## Why equity carries a current-period result

   A balance sheet only balances when the period's result is IN equity.
   Revenue and expense accounts (3.xx) are rolled into Lucros Acumulados
   (2.03.04.01.01) only at fiscal-year close
   (`kontor.l10n-br.closing/close-br-fiscal-year!`); before that runs —
   i.e. for any interim Balanço — the 3.xx balances sit OUTSIDE equity,
   and assets exceed liabilities + equity by exactly the period's net
   result.

   Section E therefore carries the current-period result as two lines:
   the income accounts (3.01 + 3.07.01) and the expense accounts
   (3.02/3.03/3.04/3.07.02/3.10) flipped by `:line/negate`. This is the
   art. 187 → art. 178 bridge (the DRE lucro líquido lands in
   Patrimônio Líquido), and it makes the statement balance both before
   and after the close: once `close-br-fiscal-year!` has zeroed the 3.xx
   accounts into 2.03.04.01.01 those two lines compute to zero and
   Lucros Acumulados carries the amount instead.

   `check` reports whether the accounting equation holds — see its
   docstring for what a non-zero difference means."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(defn- in-brl
  "Stamp `:BRL` as the section + per-line commodity FALLBACK. On a
   populated line the postings-derived symbol still wins (note-196 F5);
   this only decides the commodity of an EMPTY line so a section mixing
   populated and empty lines does not hit a cross-commodity add."
  [statement]
  (update statement :statement/sections
          (fn [sections]
            (mapv (fn [s]
                    (-> s
                        (assoc :section/commodity :BRL)
                        (update :section/lines
                                (fn [ls] (mapv #(assoc % :line/commodity :BRL) ls)))))
                  sections))))

(def definition
  "Classified Balanço Patrimonial over the `kontor.l10n-br.chart` codes."
  (in-brl
   {:statement/name    "Balanço Patrimonial"
   :statement/country "BR"
   :statement/sections
   [{:section/code  "A"
     :section/label "Ativo Circulante"
     :section/lines
     [{:line/code "A.1" :line/label "Caixa e equivalentes de caixa"
       :line/codes ["1.01.01%" "1.01.02%"]}
      {:line/code "A.2" :line/label "Contas a receber de clientes (líq. de PDD)"
       :line/codes ["1.01.03%"]}
      {:line/code "A.3" :line/label "Estoques"
       :line/codes ["1.01.04%"]}
      {:line/code "A.4" :line/label "Tributos a recuperar"
       :line/codes ["1.01.05%"]}]}

    {:section/code  "B"
     :section/label "Ativo Não Circulante"
     :section/lines
     [{:line/code "B.1" :line/label "Imobilizado (líq. de depreciação)"
       :line/codes ["1.02.03%"]}
      {:line/code "B.2" :line/label "Intangível"
       :line/codes ["1.02.04%"]}]}

    {:section/code  "C"
     :section/label "Passivo Circulante"
     :section/lines
     [{:line/code "C.1" :line/label "Fornecedores"
       :line/codes ["2.01.01%"]}
      {:line/code "C.2" :line/label "Empréstimos e financiamentos"
       :line/codes ["2.01.02%"]}
      {:line/code "C.3" :line/label "Obrigações trabalhistas e sociais"
       :line/codes ["2.01.03%"]}
      {:line/code "C.4" :line/label "Obrigações tributárias"
       :line/codes ["2.01.04%"]}]}

    ;; The starter chart carries no Passivo Não Circulante (2.02.xx)
    ;; accounts. The section is kept so long-term loans / deferred tax a
    ;; consumer adds under 2.02 roll up here without editing this file.
    {:section/code  "D"
     :section/label "Passivo Não Circulante"
     :section/lines
     [{:line/code "D.1" :line/label "Exigível a longo prazo"
       :line/codes ["2.02%"]}]}

    {:section/code  "E"
     :section/label "Patrimônio Líquido"
     :section/lines
     [{:line/code "E.1" :line/label "Capital social"
       :line/codes ["2.03.01%"]}
      {:line/code "E.2" :line/label "Reservas de lucros"
       :line/codes ["2.03.02%" "2.03.03%"]}
      {:line/code "E.3" :line/label "Lucros / prejuízos acumulados"
       :line/codes ["2.03.04%"]}
      ;; Current-period result, held outside equity until the fiscal
      ;; year is closed — see the namespace docstring.
      {:line/code "E.4" :line/label "Resultado do exercício — receitas"
       :line/codes ["3.01%" "3.07.01%"]}
      {:line/code "E.5" :line/label "Resultado do exercício — custos e despesas"
       :line/codes ["3.02%" "3.03%" "3.04%" "3.07.02%" "3.10%"]
       :line/negate true}]}]}))

(def ^:private sign-map
  "Assets add; liabilities and equity subtract. Total = ativo −
   (passivo + patrimônio líquido), which is zero for a balanced book."
  {"A" :+ "B" :+ "C" :- "D" :- "E" :-})

(defn compute
  "Compute the Balanço as of a point in time.

   A balance sheet is cumulative since inception, so pass the as-of date
   and leave `:from` nil. Other opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:as-of-tx` `:include-states` `:ledger` `:entity`).

   `:to` is EXCLUSIVE — for a Dec 31 balance sheet pass
   `:through #inst \"2025-12-31\"` (inclusive) or `:to #inst
   \"2026-01-01\"`. `:to #inst \"2025-12-31\"` omits everything posted on
   Dec 31 — and because it drops both sides of those entries the
   statement still BALANCES while being wrong.

   Returns the standard computed-statement map plus:

     :br.bs/total-ativo       = sections A + B
     :br.bs/total-passivo     = sections C + D
     :br.bs/total-pl          = section E (patrimônio líquido)
     :br.bs/difference        = ativo − (passivo + PL); zero for a
                                balanced book
     :br.bs/balanced?         = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         ativo    (money/add (sub "A") (sub "B"))
         passivo  (money/add (sub "C") (sub "D"))
         pl       (sub "E")
         diff     (money/sub ativo (money/add passivo pl))]
     (assoc computed
            :br.bs/total-ativo   ativo
            :br.bs/total-passivo passivo
            :br.bs/total-pl      pl
            :br.bs/difference    diff
            :br.bs/balanced?     (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:br.bs/*` summary of [[compute]].

   A non-zero `:br.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a consumer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger:
   the kernel's transact gate refuses any entry whose postings do not sum
   to zero. Compare against
   `kontor.reporting.financial-statements/statement-coverage` (or the
   trial balance) to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:br.bs/total-ativo :br.bs/total-passivo
                    :br.bs/total-pl :br.bs/difference :br.bs/balanced?])))

(ns kontor.l10n-br.pnl
  "Brazilian income statement — Demonstração do Resultado do Exercício
   (DRE), the statutory form fixed by Lei 6.404/76 (Lei das S.A.)
   art. 187 and CPC 26 (R1).

   The art. 187 layout runs, top to bottom:

     I.   Receita bruta das vendas e serviços        (gross revenue)
          (-) deduções: devoluções, abatimentos,
              impostos sobre vendas (ICMS, PIS,
              COFINS, ISS)                            (sales-tax deductions)
     II.  Receita líquida                             (net revenue)
     III. (-) Custo das mercadorias / serviços
              vendidos (CMV / CPV)                    (cost of sales)
     IV.  Lucro bruto                                 (gross profit)
     V.   (-) Despesas operacionais                   (operating expenses)
          (±) Resultado financeiro                    (finance result)
     VI.  Resultado operacional                       (operating result)
     VII. Resultado antes dos tributos (LAIR)         (pre-tax result)
     VIII.(-) Provisão para IRPJ e CSLL               (corporate tax)
     IX.  Lucro / prejuízo líquido do exercício       (net income)

   Since Lei 11.941/09 the DRE no longer distinguishes operational
   from non-operational; the finance result is part of the operating
   block, and \"outras receitas/despesas\" (if any) sit between the
   operating result and the pre-tax result.

   ## Sales taxes are deductions, not revenue

   ICMS, PIS, COFINS and ISS are booked \"por dentro\": the receita
   bruta on the 3.01 accounts is the full invoice price, the tax the
   seller collects lands in a *liability* (2.01.04 — a Recolher) and
   simultaneously in a *deduction* account (3.02 — sobre vendas). So
   the tax never sits in a revenue account; it is subtracted from gross
   to reach receita líquida, and its unpaid balance shows on the
   Balanço as an obligation. That is why section 2 exists and why
   revenue (section 1) already excludes collected tax.

   Account codes target the Plano de Contas Referencial-aligned starter
   in `kontor.l10n-br.chart` (`resources/kontor/l10n_br/chart.edn`).
   The starter uses 5-level dotted codes (1.01.01.01.01), so lines use
   dotted PREFIX patterns (`\"3.01%\"` = every receita-bruta account)
   wherever a whole sub-tree rolls into one line — a consumer who adds
   `3.01.01.03.01 Receita de Locação` under receita bruta is picked up
   without touching this definition.

   Contra accounts need no special handling: the chart gives the sales-
   tax deduction accounts `:kontor.account/type :expense`, so under the
   report engine's `:sign :inflow` their debit balances read as positive
   deductions and section 2 subtracts them.

   Commodity is derived from the POSTINGS (note-196 F5 —
   `kontor.reporting.report/resolve-commodity-symbol` wins over a line's
   declared `:commodity` in `sum-postings`), so every populated line is
   tagged `:BRL` off its own postings. F5b makes a zero the additive
   identity across commodities, so an empty line (e.g. ISS on a goods-only
   book) folds cleanly into a BRL subtotal, and an all-empty section
   inherits the book commodity — :BRL throughout, with no per-line stamp."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

(def definition
  "DRE per Lei 6.404/76 art. 187 over the `kontor.l10n-br.chart` codes."
  {:statement/name    "Demonstração do Resultado do Exercício"
   :statement/country "BR"
   :statement/sections
   [{:section/code  "1"
     :section/label "Receita bruta das vendas e serviços"
     :section/lines
     [{:line/code "1.1" :line/label "Receita bruta — mercadorias e serviços"
       :line/codes ["3.01%"]}]}

    {:section/code  "2"
     :section/label "Deduções da receita bruta (impostos sobre vendas)"
     :section/lines
     [{:line/code "2.1" :line/label "ICMS sobre vendas" :line/codes ["3.02.01.01.01"]}
      {:line/code "2.2" :line/label "PIS sobre vendas" :line/codes ["3.02.01.01.02"]}
      {:line/code "2.3" :line/label "COFINS sobre vendas" :line/codes ["3.02.01.01.03"]}
      {:line/code "2.4" :line/label "ISS sobre serviços" :line/codes ["3.02.01.01.04"]}]}

    {:section/code  "3"
     :section/label "Custo das mercadorias e serviços vendidos (CMV/CPV)"
     :section/lines
     [{:line/code "3.1" :line/label "Custo das mercadorias vendidas" :line/codes ["3.03%"]}]}

    {:section/code  "4"
     :section/label "Despesas operacionais"
     :section/lines
     [{:line/code "4.1" :line/label "Salários e encargos" :line/codes ["3.04.01%"]}
      {:line/code "4.2" :line/label "Aluguéis" :line/codes ["3.04.02%"]}
      {:line/code "4.3" :line/label "Energia, água, telefone" :line/codes ["3.04.03%"]}
      {:line/code "4.4" :line/label "Depreciação" :line/codes ["3.04.04%"]}
      {:line/code "4.5" :line/label "Outras despesas operacionais" :line/codes ["3.04.99%"]}]}

    {:section/code  "5"
     :section/label "Receitas financeiras"
     :section/lines
     [{:line/code "5.1" :line/label "Receitas financeiras" :line/codes ["3.07.01%"]}]}

    {:section/code  "6"
     :section/label "Despesas financeiras"
     :section/lines
     [{:line/code "6.1" :line/label "Despesas financeiras" :line/codes ["3.07.02%"]}]}

    {:section/code  "7"
     :section/label "Provisão para IRPJ e CSLL"
     :section/lines
     [{:line/code "7.1" :line/label "Provisão para IRPJ" :line/codes ["3.10.01.01.01"]}
      {:line/code "7.2" :line/label "Provisão para CSLL" :line/codes ["3.10.01.01.02"]}]}]})

(def ^:private sign-map
  "Revenue and finance income add; deductions, cost, operating
   expense, finance expense and the IRPJ/CSLL provision subtract.
   Including the tax block makes `:statement/total` the art. 187 IX
   lucro/prejuízo líquido do exercício — the statutory bottom line."
  {"1" :+ "2" :- "3" :- "4" :- "5" :+ "6" :- "7" :-})

(defn compute
  "Compute the DRE over the window `[:from, :to]`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from` + `:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2025 pass `:through #inst
   \"2025-12-31\"` (the inclusive form) or `:to #inst \"2026-01-01\"`;
   `:to #inst \"2025-12-31\"` silently drops everything posted on Dec 31,
   which is where year-end depreciation and the tax provision live.

   Returns the standard computed-statement map plus the art. 187
   sub-results under `:br.pnl/*`:

     :br.pnl/receita-liquida       = receita bruta − deduções          (II)
     :br.pnl/lucro-bruto           = receita líquida − CMV             (IV)
     :br.pnl/resultado-operacional = lucro bruto − despesas
                                     operacionais ± resultado
                                     financeiro                        (VI)
     :br.pnl/lucro-antes-tributos  = resultado operacional +
                                     outras receitas/despesas (0 aqui) (VII)
     :br.pnl/lucro-liquido         = the statement total (IX)"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub          #(fs/section-subtotal computed %)
         receita      (sub "1")
         deducoes     (sub "2")
         cmv          (sub "3")
         despesas     (sub "4")
         rec-fin      (sub "5")
         desp-fin     (sub "6")
         receita-liq  (money/sub receita deducoes)
         lucro-bruto  (money/sub receita-liq cmv)
         resultado-op (-> lucro-bruto
                          (money/sub despesas)
                          (money/add rec-fin)
                          (money/sub desp-fin))]
     (assoc computed
            :br.pnl/receita-liquida       receita-liq
            :br.pnl/lucro-bruto           lucro-bruto
            :br.pnl/resultado-operacional resultado-op
            ;; No "outras receitas/despesas" section in this starter, so
            ;; LAIR coincides with the operating result. Exposed as its
            ;; own key so a consumer that adds one keeps the semantics.
            :br.pnl/lucro-antes-tributos  resultado-op
            :br.pnl/lucro-liquido         (:statement/total computed)))))

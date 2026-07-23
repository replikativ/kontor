(ns kontor.l10n-mx.pnl
  "Mexican income statement — Estado de Resultados (Integral).

   There is no single statutory line-layout the way HGB §275 fixes the
   German one. The presentation here follows the NIF (Normas de
   Información Financiera, NIF B-3 \"Estado de resultado integral\")
   conventional multi-step form used across Mexican practice and
   reflected in the SAT Contabilidad Electrónica taxonomy:

     Ingresos (netos de IVA/IEPS — impuestos trasladados son pasivos)
     − Costo de ventas
     = Utilidad bruta
     − Gastos de operación
     = Utilidad de operación
     + Otros ingresos (productos financieros) − gastos financieros
     = Utilidad antes de impuestos

   Account codes target the SAT Código Agrupador chart in
   `kontor.l10n-mx.chart` (`resources/kontor/l10n_mx/chart.edn`), whose
   codes are dot-separated (`401.01.001`). Prefix patterns (`\"401%\"` =
   every 401.xx) roll a whole SAT group into one line, so a consumer who
   adds `401.01.005 Ingresos:Ventas:Nacional:otra-tasa` is picked up
   without touching this definition.

   ## Ingresos exclude IVA — the cash-basis MX-specific

   Mexico recognises IVA on a cash basis (Ley del IVA Art. 1-B): the
   collected IVA (IVA trasladado, group 208) is a LIABILITY owed to SAT,
   never revenue. The revenue accounts (401/402) carry the net price
   only; the tax leg lands on 208.xx and shows on the Balance General,
   not here. The same holds for IEPS trasladado (group 209).

   ## Gastos financieros are split out per convention

   NIF B-3's \"Resultado Integral de Financiamiento\" nets interest,
   commissions and FX results. In the shipped chart these live inside
   the 601 gastos group (601.14 intereses, 601.15 comisiones bancarias,
   601.16 pérdida cambiaria) alongside the operating gastos (601.01 ..
   601.13). A single `\"601%\"` prefix cannot separate them — 601.10..13
   are operating but share the `601.1` stem with the financial 601.14..16
   — so the operating and financial sections enumerate their 601.xx
   codes individually. Financial income (405 intereses / utilidad
   cambiaria) sits in `Otros ingresos`.

   ## No tax line

   ISR (income tax) is an entity-level computation
   (`kontor.l10n-mx.cit-provider` / `pit-provider`), so this statement
   stops at Utilidad antes de impuestos. A consumer that provisions ISR
   to 205.01.001 can add a section for it."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; The kernel report engine derives each line's commodity from the
;; postings it sums (note-196 F5), so a populated MXN line is tagged MXN
;; automatically. Empty lines and empty sections have no postings to
;; derive from and would fall back to the engine's :EUR default; adding
;; an :EUR zero to an MXN subtotal throws `Cross-commodity :add`. Stamping
;; :MXN keeps those zeros in-currency so a partially-populated chart
;; still computes. On populated lines the postings-derived MXN wins, so
;; this stamp never masks a real currency.
(defn- in-mxn [statement]
  (update statement :statement/sections
          (fn [sections]
            (mapv (fn [s]
                    (-> s
                        (assoc :section/commodity :MXN)
                        (update :section/lines
                                (fn [ls] (mapv #(assoc % :line/commodity :MXN) ls)))))
                  sections))))

(def definition
  "Multi-step Estado de Resultados over the `kontor.l10n-mx.chart` codes."
  (in-mxn
   {:statement/name    "Estado de Resultados"
    :statement/country "MX"
    :statement/sections
    [{:section/code  "1"
      :section/label "Ingresos"
      :section/lines
      [{:line/code "1.1" :line/label "Ventas netas"
        :line/codes ["401%"]}
       {:line/code "1.2" :line/label "Servicios"
        :line/codes ["402%"]}]}

     {:section/code  "2"
      :section/label "Costo de ventas"
      :section/lines
      [{:line/code "2.1" :line/label "Costo de lo vendido"
        :line/codes ["501%"]}]}

     {:section/code  "3"
      :section/label "Gastos de operación"
      :section/lines
      [{:line/code "3.1" :line/label "Sueldos y prestaciones"
        :line/codes ["601.01.001" "601.02.001"]}
       {:line/code "3.2" :line/label "Renta y servicios públicos"
        :line/codes ["601.03.001" "601.04.001" "601.05.001"]}
       {:line/code "3.3" :line/label "Honorarios y mantenimiento"
        :line/codes ["601.06.001" "601.07.001"]}
       {:line/code "3.4" :line/label "Papelería, combustibles y viáticos"
        :line/codes ["601.08.001" "601.09.001" "601.10.001"]}
       {:line/code "3.5" :line/label "Publicidad y seguros"
        :line/codes ["601.11.001" "601.12.001"]}
       {:line/code "3.6" :line/label "Depreciación del ejercicio"
        :line/codes ["601.13.001"]}
       ;; Non-deductible expenses reduce book profit even though ISR
       ;; disallows them; they belong in the P&L (and in the BS current-
       ;; period result) so the two stay consistent.
       {:line/code "3.7" :line/label "Gastos no deducibles"
        :line/codes ["701%"]}]}

     {:section/code  "4"
      :section/label "Otros ingresos (productos financieros)"
      :section/lines
      [{:line/code "4.1" :line/label "Intereses y utilidad cambiaria"
        :line/codes ["405%"]}]}

     {:section/code  "5"
      :section/label "Gastos financieros"
      :section/lines
      [{:line/code "5.1" :line/label "Intereses pagados" :line/codes ["601.14.001"]}
       {:line/code "5.2" :line/label "Comisiones bancarias" :line/codes ["601.15.001"]}
       {:line/code "5.3" :line/label "Pérdida cambiaria" :line/codes ["601.16.001"]}]}]}))

(def ^:private sign-map
  "Ingresos y otros ingresos suman; costo, gastos de operación y gastos
   financieros restan. El total es la Utilidad antes de impuestos."
  {"1" :+ "2" :- "3" :- "4" :+ "5" :-})

(defn compute
  "Compute the Estado de Resultados over the window `[:from, :to]`.

   Opts are forwarded to
   `kontor.reporting.financial-statements/compute-statement`
   (`:from` `:to` `:through` `:as-of-tx` `:include-states` `:ledger`
   `:entity`); `:from` and `:to` are what make it a period statement.

   `:to` is EXCLUSIVE. For calendar 2026 pass `:through #inst
   \"2026-12-31\"` (the inclusive form) or `:to #inst \"2027-01-01\"`.
   `:to #inst \"2026-12-31\"` silently omits everything posted on Dec 31 —
   where year-end depreciation and accruals live.

   Returns the standard computed-statement map plus three derived
   subtotals under the `:mx.pnl/*` keys, since the multi-step form is
   defined by them:

     :mx.pnl/utilidad-bruta           = ingresos − costo de ventas
     :mx.pnl/utilidad-operacion       = utilidad bruta − gastos de operación
     :mx.pnl/utilidad-antes-impuestos = the statement total"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         ingresos (sub "1")
         costo    (sub "2")
         opex     (sub "3")
         bruta    (money/sub ingresos costo)]
     (assoc computed
            :mx.pnl/utilidad-bruta           bruta
            :mx.pnl/utilidad-operacion       (money/sub bruta opex)
            :mx.pnl/utilidad-antes-impuestos (:statement/total computed)))))

(ns kontor.l10n-mx.bs
  "Mexican balance sheet — Balance General (Estado de Situación
   Financiera), classified form.

   As with the income statement there is no single statutory line
   layout; this ships the NIF (NIF B-6 \"Estado de situación
   financiera\") conventional classified presentation — circulante vs
   no-circulante on the asset side, corto vs largo plazo on the
   liability side, in decreasing order of liquidity:

     Activo   = circulante + fijo (neto) + intangible
     Pasivo   = corto plazo + largo plazo
     Capital  = capital social + reservas + resultados acumulados
                + resultado del ejercicio

   Account codes target the SAT Código Agrupador chart in
   `kontor.l10n-mx.chart`. The depreciación-acumulada account
   (165.01.001) carries `:kontor.account/type :asset` in that chart, so
   its credit balance nets against gross activo fijo automatically under
   `:sign :inflow` — the \"(neto)\" needs no special handling. IVA
   trasladado (208) and IEPS trasladado (209) are LIABILITIES — the
   cash-basis collected tax owed to SAT — and sit in Pasivo, never in
   revenue.

   ## Why capital has a resultado-del-periodo pair

   A balance sheet only balances when the period's profit is IN equity.
   The ingreso (4xx) and costo/gasto (5xx/6xx/7xx) accounts are closed
   into resultados at fiscal-year end
   (`kontor.l10n-mx.closing`), so before that runs — i.e. for any interim
   Balance General — those balances sit OUTSIDE capital and activo
   exceeds pasivo + capital by exactly the period's utilidad neta.

   Section F therefore carries the current-period result as two lines:
   the ingreso side (F.5) and the costo/gasto side flipped by
   `:line/negate` (F.6). That is the standard interim presentation and
   it makes the statement balance both before and after a close: once
   closing has zeroed the P&L accounts into 304/305 those two lines
   compute to zero and the Utilidad del Ejercicio line carries the
   amount instead.

   `check` reports whether the accounting equation actually holds — see
   its docstring for what a non-zero difference means."
  (:require [kontor.money :as money]
            [kontor.reporting.financial-statements :as fs]))

;; See the note in kontor.l10n-mx.pnl — populated lines derive MXN from
;; their postings (note 196 F5), empty lines/sections fold as the additive
;; identity (F5b), and an all-empty section inherits the book commodity,
;; so a partially-populated MXN chart reports :MXN with no per-line stamp.
(def definition
  "Classified Balance General over the `kontor.l10n-mx.chart` codes."
  {:statement/name    "Balance General"
   :statement/country "MX"
   :statement/sections
   [{:section/code  "A"
     :section/label "Activo circulante"
     :section/lines
     [{:line/code "A.1" :line/label "Efectivo, bancos e inversiones"
       :line/codes ["101%" "102%" "103%"]}
      {:line/code "A.2" :line/label "Clientes"
       :line/codes ["105%"]}
      {:line/code "A.3" :line/label "Otras cuentas por cobrar y anticipos"
       :line/codes ["106%" "107%" "108%"]}
      {:line/code "A.4" :line/label "Inventarios"
       :line/codes ["115%"]}
       ;; IVA acreditable / retenciones por cobrar / pagos provisionales
       ;; / IEPS acreditable are all `:asset` — impuestos a favor.
      {:line/code "A.5" :line/label "Impuestos a favor"
       :line/codes ["119%" "120%" "121%" "216%"]}]}

    {:section/code  "B"
     :section/label "Activo fijo (neto)"
     :section/lines
     [{:line/code "B.1" :line/label "Terrenos y edificios"
       :line/codes ["151%" "152%"]}
      {:line/code "B.2" :line/label "Maquinaria, mobiliario y equipo"
       :line/codes ["153%" "154%" "155%" "156%"]}
       ;; contra-activo: :asset type, credit balance, nets negative
      {:line/code "B.3" :line/label "Menos: depreciación acumulada"
       :line/codes ["165%"]}]}

    {:section/code  "C"
     :section/label "Activo intangible y diferido"
     :section/lines
     [{:line/code "C.1" :line/label "Intangibles (software, marcas)"
       :line/codes ["172%"]}]}

    {:section/code  "D"
     :section/label "Pasivo a corto plazo"
     :section/lines
     [{:line/code "D.1" :line/label "Proveedores"
       :line/codes ["201%"]}
      {:line/code "D.2" :line/label "Documentos y acreedores diversos"
       :line/codes ["202%" "203%"]}
      {:line/code "D.3" :line/label "Anticipos de clientes"
       :line/codes ["204%"]}
      {:line/code "D.4" :line/label "Impuestos y retenciones por pagar"
       :line/codes ["205%" "206%"]}
       ;; Cash-basis output IVA owed to SAT — a liability, not revenue.
      {:line/code "D.5" :line/label "IVA trasladado por pagar"
       :line/codes ["208%"]}
      {:line/code "D.6" :line/label "IEPS trasladado por pagar"
       :line/codes ["209%"]}]}

    {:section/code  "E"
     :section/label "Pasivo a largo plazo"
     :section/lines
     [{:line/code "E.1" :line/label "Préstamos y acreedores hipotecarios"
       :line/codes ["251%" "252%"]}]}

    {:section/code  "F"
     :section/label "Capital contable"
     :section/lines
     [{:line/code "F.1" :line/label "Capital social"
       :line/codes ["301%"]}
      {:line/code "F.2" :line/label "Reserva legal"
       :line/codes ["303%"]}
       ;; 306 pérdidas de ejercicios anteriores is `:equity` with a debit
       ;; balance, so it nets negative here automatically.
      {:line/code "F.3" :line/label "Resultados de ejercicios anteriores"
       :line/codes ["305%" "306%"]}
      {:line/code "F.4" :line/label "Utilidad del ejercicio (cerrada)"
       :line/codes ["304%"]}
       ;; Current-period result, held outside capital until the fiscal
       ;; year is closed — see the namespace docstring.
      {:line/code "F.5" :line/label "Resultado del periodo — ingresos"
       :line/codes ["401%" "402%" "405%"]}
      {:line/code "F.6" :line/label "Resultado del periodo — costos y gastos"
       :line/codes ["501%" "601%" "701%"]
       :line/negate true}]}]})

(def ^:private sign-map
  "Activo suma; pasivo y capital restan. El total es activo − (pasivo +
   capital), que es cero para un libro cuadrado."
  {"A" :+ "B" :+ "C" :+ "D" :- "E" :- "F" :-})

(defn compute
  "Compute the Balance General as of a point in time.

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

     :mx.bs/total-activo   = sections A + B + C
     :mx.bs/total-pasivo   = sections D + E
     :mx.bs/total-capital  = section F
     :mx.bs/difference     = activo − (pasivo + capital); zero for a
                             balanced book
     :mx.bs/balanced?      = whether that difference is zero"
  ([conn opts] (compute conn definition opts))
  ([conn statement opts]
   (let [computed (fs/compute-statement conn statement
                                        (assoc opts :total-sign-map sign-map))
         sub      #(fs/section-subtotal computed %)
         activo   (reduce money/add (map sub ["A" "B" "C"]))
         pasivo   (reduce money/add (map sub ["D" "E"]))
         capital  (sub "F")
         diff     (money/sub activo (money/add pasivo capital))]
     (assoc computed
            :mx.bs/total-activo  activo
            :mx.bs/total-pasivo  pasivo
            :mx.bs/total-capital capital
            :mx.bs/difference    diff
            :mx.bs/balanced?     (money/zero? diff)))))

(defn check
  "Assert the accounting equation for the book as of `:to`. Returns the
   `:mx.bs/*` summary of [[compute]].

   A non-zero `:mx.bs/difference` means real money is unaccounted for by
   this DEFINITION — most often an account whose code falls outside every
   `:line/codes` pattern above (a consumer-added account in a range the
   definition does not cover). It does NOT mean an unbalanced ledger:
   the kernel's transact gate refuses any entry whose postings do not sum
   to zero, so the ledger itself cannot drift. Compare against
   `kontor.reporting.trial/trial-balance` to find the stray account."
  [conn opts]
  (-> (compute conn opts)
      (select-keys [:mx.bs/total-activo :mx.bs/total-pasivo
                    :mx.bs/total-capital :mx.bs/difference :mx.bs/balanced?])))

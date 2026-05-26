(ns kontor.l10n-mx.closing
  "Mexican fiscal-year close — rolls P&L accounts to retained
   earnings (\"Utilidades Retenidas\" / Resultados de Ejercicios
   Anteriores) using the SAT-aligned account convention.

   Sits as a thin country wrapper over `kontor.closing/close-
   fiscal-year!`, pinning:

     - Retained-earnings account → 305.01.001 Utilidades Retenidas
                                    (Resultados de Ejercicios Anteriores)
     - Closing journal           → CLOSE (auto-created if absent)

   Both are overridable.

   ## Mexican fiscal-year specifics

   Mexico's fiscal year is the **calendar year** (January 1 –
   December 31) per Ley del ISR Art. 11. Unlike the US, Canada, or
   the UK, there's no option to choose a non-calendar year for the
   ordinary corporate income-tax return (Art. 11 only allows
   shortened first/last years on incorporation / liquidation).

   ## Mexican terminology for the close

     - **Utilidad del Ejercicio** (304.01.001) — current year's net
       income. P&L accumulates here during the year as a memo balance
       in some chart variants; in our model the P&L collapses directly
       into Utilidades Retenidas at year-end.

     - **Utilidades Retenidas** / **Resultados de Ejercicios
       Anteriores** (305.01.001) — retained earnings, the canonical
       rollup target on the equity side. Carries the cumulative
       prior-period profit.

     - **Pérdidas de Ejercicios Anteriores** (306.01.001) — accumulated
       losses, separate equity account on the deficit side. For
       simplicity the kontor close routes a loss into Utilidades
       Retenidas as a negative balance (the equity account itself
       carries a debit balance representing accumulated deficit);
       consumers can re-route to 306 with the `:retained-code`
       override if they prefer that disclosure layout.

   ## Per ADR-068

   - **`plan-mx-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns
     the opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, info-return export)
     in one `kontor.process`.

   - **`close-mx-fiscal-year!`** — side-effecting wrapper that calls
     the planner, runs `kontor.closing/close-fiscal-year!` (which
     itself routes through the validation gate), and returns the
     close + period-close report.

   ## Sources (public, non-copyrightable)

     - Ley del ISR Art. 11 (fiscal-year = calendar year)
     - Anexo 24 RMF (Código Agrupador account labels)"
  (:require [datahike.api :as d]
            [kontor.closing :as closing]
            [kontor.l10n-mx.chart :as chart]))

(def ^:const default-retained-code chart/utilidades-retenidas-code) ; "305.01.001"
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-mx chart first")
                      {:type :l10n-mx/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE / CA l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Asientos de cierre del ejercicio"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-mx-fiscal-year-close-tx-data
  "Resolve the opts a MX fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"305.01.001\" (Utilidades Retenidas)
     :journal-code   — defaults to \"CLOSE\"
                        (auto-created via close-mx-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-mx-
                                 ; fiscal-year!` creates if absent
      :external-id, :narration, :at  ; pass-throughs}

   Throws if the retained-earnings account is missing."
  [db {:keys [period-eid retained-code]
       :or {retained-code default-retained-code}
       :as opts}]
  (when-not period-eid
    (throw (ex-info ":period-eid is required" {})))
  (let [retained-eid (require-retained db retained-code)]
    (-> opts
        (dissoc :retained-code)
        (assoc :retained-earnings-eid retained-eid))))

(defn close-mx-fiscal-year!
  "Close a MX fiscal-year period using the SAT-aligned default
   account convention.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                        (default \"305.01.001\" Utilidades Retenidas)
     :journal-code   — journal code for the closing tx
                        (default \"CLOSE\"; auto-created if absent)
     :external-id, :narration, :at — passed through.

   Returns the kernel close-fiscal-year! result:
     {:close-result …  ; from close-period!
      :period-close-tx-report …}

   Idempotent-ish: refuses if the period already has a closing
   transaction (`:kontor.transaction/closes-period`)."
  [conn {:keys [retained-code journal-code]
         :or {retained-code default-retained-code
              journal-code default-journal-code}
         :as opts}]
  (let [db (d/db conn)
        planned (plan-mx-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

(ns kontor.l10n-br.closing
  "Brazilian fiscal-year close — rolls P&L accounts to retained
   earnings (lucros acumulados) using the Plano de Contas
   Referencial-aligned starter shipped in `chart.edn`.

   Sits as a thin country wrapper over `kontor.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `2.03.04.01.01`
       (Lucros / Prejuízos Acumulados — Retained Earnings)
     - Closing journal → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## BR fiscal-year specifics

   Most Brazilian entities use the calendar year (January 1 to
   December 31) as the fiscal period (`exercício social`) per Lei
   6.404/1976 art. 175 (Lei das S.A.) and CTN art. 150 §3. Some
   privately-held companies elect a non-calendar period in their
   bylaws — those are the exceptions.

   ## Lucros acumulados

   The equity-side rollup target. For S.A. companies the
   shareholder assembly resolves on dividends + reserve allocations
   AFTER the close; the close itself parks the period's net result
   in `Lucros Acumulados` (2.03.04.01.01 in our starter chart;
   account `2.0X.04.0X.0X` family in the broader Plano
   Referencial). Distribution / reserve appropriation is a
   subsequent transaction the consumer posts via standard
   bookkeeping primitives.

   ## IFRS-aligned reporting

   CPC pronouncements (the Brazilian equivalent of IFRS) govern SA
   reporting. The close mechanics are jurisdiction-agnostic — what's
   BR-shaped is the retained-earnings account routing and the
   conventional fiscal-period bounds.

   ## Per ADR-068

   - **`plan-br-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns the
     opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, ECF / ECD export) in
     one `kontor.process`.

   - **`close-br-fiscal-year!`** — side-effecting wrapper that
     calls the planner, runs `kontor.closing/close-fiscal-year!`
     (which itself routes through the validation gate), and returns
     the close + period-close report."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code "2.03.04.01.01")
(def ^:const default-journal-code  "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-br chart first")
                      {:type :l10n-br/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the CA / DE l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:journal/code code]))
      (do
        (d/transact conn [{:journal/code code
                           :journal/name "Year-end closing entries (encerramento)"
                           :journal/type :closing
                           :journal/active true}])
        (:db/id (d/entity (d/db conn) [:journal/code code])))))

(defn plan-br-fiscal-year-close-tx-data
  "Resolve the opts a BR fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"2.03.04.01.01\"
                       (Plano Referencial Lucros Acumulados)
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-br-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …   ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-br-
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

(defn close-br-fiscal-year!
  "Close a BR fiscal-year period using the Plano Referencial-aligned
   account convention.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"2.03.04.01.01\")
     :journal-code   — journal code for the closing tx
                       (default \"CLOSE\"; auto-created if absent)
     :external-id, :narration, :at — passed through.

   Returns the kernel close-fiscal-year! result:
     {:close-result …       ; from close-period!
      :period-close-tx-report …}"
  [conn {:keys [retained-code journal-code]
         :or {retained-code default-retained-code
              journal-code default-journal-code}
         :as opts}]
  (let [db (d/db conn)
        planned (plan-br-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

(ns kontor.l10n-fr.closing
  "French fiscal-year close — rolls P&L accounts to retained earnings
   using the PCG (Plan Comptable Général) convention.

   Sits as a thin country wrapper over `kontor.reporting.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `110 Report à nouveau (créditeur)`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## FR fiscal-year-end specifics

   Most French SARLs / SAS use a calendar fiscal year (1 janvier →
   31 décembre); the law (CGI art.36) allows any 12-month exercice
   comptable, with a one-time election at incorporation. The close
   *mechanics* are jurisdiction-agnostic — the period bounds and
   retained-earnings account are what's FR-shaped.

   ## PCG two-step flow vs the kontor one-step close

   PCG art.934-1 conventionally splits the year-end equity reshuffle
   into two phases:

     1. **Clôture des comptes annuels (year-end):**
        P&L (classe 6 + classe 7) → 120 (bénéfice) or 129 (perte).
        At this point the balance sheet shows the year's result on
        a single line in equity.

     2. **Affectation du résultat (post-AG assemblée générale):**
        120 (bénéfice) → 110 (Report à nouveau créditeur), 106
        (Réserves), and/or 457 (Dividendes à payer) per the
        shareholder resolution.

   The kontor closing kernel rolls P&L straight into ONE retained-
   earnings account in one tx. That matches the *substance* of step
   1 but routes to `110` directly, skipping the intermediate `120`.
   For SARLs / SAS that need the two-step flow exactly, override
   `:retained-code \"120\"` on the close call and post a manual
   reclassification tx after the AG.

   For SCI / EI / micro-entreprises where there is no AG and the
   result is immediately retained, the one-step close to `110` is
   the natural posting.

   ## French-language convention

   The retained-earnings account is conventionally labelled `Report à
   nouveau`. The l10n-fr chart ships this label in French; a future
   `:en` locale could surface English labels alongside, but the
   account code (`110`) remains stable.

   ## Per ADR-068

   - **`plan-fr-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns the
     opts map the kernel closer needs.

   - **`close-fr-fiscal-year!`** — side-effecting wrapper that calls
     the planner, runs `kontor.reporting.closing/close-fiscal-year!` (which
     itself routes through the validation gate), and returns the
     close + period-close report."
  (:require [datahike.api :as d]
            [kontor.reporting.closing :as closing]))

(def ^:const default-retained-code "110")
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-fr chart first")
                      {:type :l10n-fr/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE / CA l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Écritures de clôture annuelle"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-fr-fiscal-year-close-tx-data
  "Resolve the opts a FR fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.reporting.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.workflow.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"110\" (Report à nouveau créditeur).
                       Override to \"120\" if you want the two-step
                       PCG flow with a post-AG reclassification.
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-fr-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-fr-
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

(defn close-fr-fiscal-year!
  "Close a FR fiscal-year period using PCG retained-earnings routing.

   Required:
     :period-eid

   Optional:
     :retained-code  — PCG code for the retained-earnings account
                       (default \"110\" — Report à nouveau créditeur).
                       Override to \"120\" for the two-step PCG flow
                       (close to Résultat de l'exercice; reclassify
                       to 110/106/dividends post-AG).
     :journal-code   — journal code for the closing tx
                       (default \"CLOSE\"; auto-created if absent)
     :external-id, :narration, :at — passed through.

   Returns the kernel close-fiscal-year! result:
     {:close-result …  ; from close-period!
      :period-close-tx-report …}"
  [conn {:keys [retained-code journal-code]
         :or {retained-code default-retained-code
              journal-code default-journal-code}
         :as opts}]
  (let [db (d/db conn)
        planned (plan-fr-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

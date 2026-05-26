(ns kontor.l10n-au.closing
  "Australian fiscal-year close — rolls P&L accounts to retained
   earnings using the standard AU CoA convention.

   Sits as a thin country wrapper over `kontor.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `31200 Retained earnings`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## AU fiscal-year-end specifics

   The Australian financial year runs **1 July to 30 June** (s.4-10
   Income Tax Assessment Act 1997). This differs from the calendar-
   year convention used in many other jurisdictions (DE, CA-individuals,
   US individuals) and is the AU-specific tweak this module exists
   for. A small set of taxpayers may apply to the ATO for a
   substituted-accounting-period (SAP) under s.18 ITAA 1936, but
   the standard period applies by default and is what this module
   helps close.

   The close *mechanics* are jurisdiction-agnostic — the period
   bounds and the account convention are what's AU-shaped. The
   caller is responsible for creating the `:period` entity with
   :period/start and :period/end set to the AU FY bounds (1 July
   00:00:00 → following 1 July 00:00:00); this module does not
   itself derive period bounds (that lives in
   `kontor.l10n-au.bas/fy-period-bounds`).

   ## Account naming

   The shipped AU chart uses 'Retained earnings' (code 31200) as the
   equity-side bucket. Alternative AU conventions ('Accumulated
   profits / losses' is common in some company-side CoAs) can be
   accommodated by passing a `:retained-code` override.

   ## Per ADR-068

   - **`plan-au-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns the
     opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, BAS export) in one
     `kontor.process`.

   - **`close-au-fiscal-year!`** — side-effecting wrapper that calls
     the planner, runs `kontor.closing/close-fiscal-year!` (which
     itself routes through the validation gate), and returns the
     close + period-close report."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code "31200")
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-au chart first")
                      {:type :l10n-au/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE / CA l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:journal/code code]))
      (do
        (d/transact conn [{:journal/code code
                           :journal/name "Year-end closing entries"
                           :journal/type :closing
                           :journal/active true}])
        (:db/id (d/entity (d/db conn) [:journal/code code])))))

(defn plan-au-fiscal-year-close-tx-data
  "Resolve the opts an AU fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid  — the AU FY period (1 July – 30 June) to close

   Optional:
     :retained-code  — defaults to \"31200\"
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-au-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-au-
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

(defn close-au-fiscal-year!
  "Close an AU fiscal-year period (1 July – 30 June) using the
   shipped AU CoA convention.

   Required:
     :period-eid  — the AU FY period entity

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"31200\")
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
        planned (plan-au-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

(ns kontor.l10n-ca.closing
  "Canadian fiscal-year close — rolls P&L accounts to retained
   earnings using the QuickBooks-Canada-default account convention.

   Sits as a thin country wrapper over `kontor.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `3100 Retained earnings`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## CA fiscal-year-end specifics

   Canadian individuals and most sole proprietorships have a
   Dec-31 fiscal year-end (Income Tax Act s.249.1(1)). Corporations
   may elect any 12-month (or 53-week) fiscal period when first
   registering with the CRA. The close *mechanics* are
   jurisdiction-agnostic — the period bounds and retained-earnings
   account is what's CA-shaped.

   ## French-language convention

   The retained-earnings account is conventionally labelled
   'Retained earnings' (EN) or 'Bénéfices non répartis' (FR). The
   l10n-ca chart ships the EN label; a future :fr locale could
   override the label, but the account code (3100) remains stable.

   ## Per ADR-068

   - **`plan-ca-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns the
     opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, info-return export) in
     one `kontor.process`.

   - **`close-ca-fiscal-year!`** — side-effecting wrapper that calls
     the planner, runs `kontor.closing/close-fiscal-year!` (which
     itself routes through the validation gate), and returns the
     close + period-close report."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code "3100")
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-ca chart first")
                      {:type :l10n-ca/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE l10n closing module's bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:journal/code code]))
      (do
        (d/transact conn [{:journal/code code
                           :journal/name "Year-end closing entries"
                           :journal/type :closing
                           :journal/active true}])
        (:db/id (d/entity (d/db conn) [:journal/code code])))))

(defn plan-ca-fiscal-year-close-tx-data
  "Resolve the opts a CA fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"3100\"
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-ca-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-ca-
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

(defn close-ca-fiscal-year!
  "Close a CA fiscal-year period using the QuickBooks-Canada-default
   account convention.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"3100\")
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
        planned (plan-ca-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

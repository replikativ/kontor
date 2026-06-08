(ns kontor.l10n-in.closing
  "Indian fiscal-year close — rolls P&L accounts to Reserves &
   Surplus (Retained Earnings) per the Companies Act / Ind AS
   convention.

   Sits as a thin country wrapper over `kontor.reporting.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `220900 Reserves and Surplus —
       Retained Earnings (Surplus / P&L balance)`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable. Some Indian companies that distinguish
   `:general-reserve` from `:retained-earnings` before the AGM
   resolution may want to route to `220200 General Reserve` or split
   the close across multiple equity accounts — supply
   `:retained-code` to override.

   ## IN fiscal-year-end specifics

   * Indian fiscal year is **April 1 → March 31**, called the
     'previous year' for income-tax purposes (the immediately
     following 'assessment year' is when that year's return is
     filed). This is statutorily mandated by Section 3 of the
     Income-tax Act 1961 for individuals + non-corporates;
     Section 2(41) Companies Act 2013 mandates the same for
     companies (one-time exceptions exist for newly-incorporated
     companies + transition).
   * Distinct from CA / DE which are calendar-year (Dec 31).
   * The fiscal year FY24-25 covers 1 Apr 2024 — 31 Mar 2025; the
     close runs as of 31 Mar 2025 23:59:59 IST.
   * 'Reserves and Surplus' under Schedule III Division II
     (Ind AS-aligned) is the equity-side rollup target. The
     Surplus / P&L balance line within Reserves and Surplus is
     where the period's net profit (or loss) lands. Under
     Division I (legacy Indian GAAP) the same line is called
     'Surplus in the Statement of Profit and Loss' — same account
     in this kernel chart (`220900`).

   ## Per ADR-068

   - **`plan-in-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns
     the opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, GSTR filing snapshot,
     audit-doc emission) in one `kontor.workflow.process`.

   - **`close-in-fiscal-year!`** — side-effecting wrapper that
     calls the planner, runs `kontor.reporting.closing/close-fiscal-year!`
     (which routes through the validation gate itself), and returns
     the close + period-close report."
  (:require [datahike.api :as d]
            [kontor.reporting.closing :as closing]
            [kontor.l10n-in.chart :as chart]))

(def ^:const default-retained-code chart/retained-earnings-code)
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-in chart first")
                      {:type :l10n-in/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Year-end closing entries"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-in-fiscal-year-close-tx-data
  "Resolve the opts an IN fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.reporting.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.workflow.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"220900\" (Reserves and Surplus —
                       Retained Earnings)
     :journal-code   — defaults to \"CLOSE\" (auto-created via
                       `close-in-fiscal-year!`)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …    ; resolved from the chart
      :journal-code   …           ; not yet resolved — `close-in-
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

(defn close-in-fiscal-year!
  "Close an IN fiscal-year period (April 1 – March 31) using the
   Reserves-and-Surplus → Retained-Earnings account convention.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"220900\")
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
        planned (plan-in-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

(ns kontor.l10n-us.closing
  "US fiscal-year close — rolls P&L accounts to retained earnings
   using the QuickBooks-Online-default account convention.

   Sits as a thin country wrapper over `kontor.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `3100 Retained earnings`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## US fiscal-year-end specifics

   US tax law (IRC §441) allows multiple fiscal-year conventions
   depending on entity type:

     - **Individuals** — calendar year (Jan 1 – Dec 31) by default,
       short period possible at first/last year, fiscal-year election
       requires IRS Form 1128 + valid business purpose.
     - **C-Corporations** — may elect any 12-month period when filing
       the initial return. Calendar year is most common (~85% of
       SMBs); large/retail corps often use a January-ending year
       (Walmart Jan 31, Target Jan 31) or a 52/53-week year (Apple
       last Saturday of September).
     - **S-Corporations / Partnerships / LLCs taxed as P/S-Corp** —
       generally must use a required-taxable-year matching the
       majority-shareholder/partner year (almost always calendar) per
       IRC §444 unless a §444 election is made (a §444 fiscal year
       requires a §7519 deposit).

   The close *mechanics* are jurisdiction-agnostic — the period
   bounds + retained-earnings account is what's US-shaped.

   ## Entity-type vocabulary

   The closing flow is the same; the equity-account naming varies:

     - **C-Corp / S-Corp**  Retained Earnings (3100)
     - **LLC**              Members' Equity / Members' Distributions
     - **Partnership**      Partners' Capital
     - **Sole proprietor**  Owner's Equity / Owner's Drawing

   The default account is `3100 Retained Earnings` matching the
   seed QBO-style chart. Callers can pass `:retained-code` to redirect
   to e.g. `3050 Members' Equity` for an LLC, or override the chart
   to add the entity-type-appropriate account name.

   ## Per ADR-068

   - **`plan-us-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns the
     opts map the kernel closer needs. Useful for composing the
     close with adjacent writes (period seal, 1099-NEC export) in
     one `kontor.process`.

   - **`close-us-fiscal-year!`** — side-effecting wrapper that calls
     the planner, runs `kontor.closing/close-fiscal-year!` (which
     itself routes through the validation gate), and returns the
     close + period-close report.

   ## 1099-NEC carve-out

   Year-end in the US has a parallel 1099-NEC reporting obligation
   for non-employee compensation ≥ $600. That's NOT a posting-time
   concern — it's a downstream report generated from the
   `6900 Expenses:1099-Contractors` account's posting log. A future
   `kontor.l10n-us.reports/1099-nec` namespace will handle it; the
   close itself does not."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code "3100")
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-us chart first")
                      {:type :l10n-us/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE/CA l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:journal/code code]))
      (do
        (d/transact conn [{:journal/code code
                           :journal/name "Year-end closing entries"
                           :journal/type :closing
                           :journal/active true}])
        (:db/id (d/entity (d/db conn) [:journal/code code])))))

(defn plan-us-fiscal-year-close-tx-data
  "Resolve the opts a US fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"3100\". Override for LLCs
                       (\"3050 Members' Equity\") or partnerships
                       (\"3060 Partners' Capital\") if the chart
                       was extended.
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-us-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-us-
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

(defn close-us-fiscal-year!
  "Close a US fiscal-year period using the QuickBooks-Online-default
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
        planned (plan-us-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

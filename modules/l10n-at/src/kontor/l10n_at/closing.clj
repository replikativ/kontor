(ns kontor.l10n-at.closing
  "Austrian fiscal-year close — rolls P&L accounts to Bilanzgewinn
   (or Bilanzverlust) using the Einheitskontenrahmen default.

   Sits as a thin country wrapper over `kontor.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `9460 Bilanzgewinn/Bilanzverlust`
                                  per UGB §224 Bilanzgliederung
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## AT fiscal-year-end specifics

   Austrian fiscal year defaults to the **calendar year** (Jan 1 –
   Dec 31). UGB §193 Abs.3 permits Kapitalgesellschaften (GmbH /
   AG) to elect any 12-month Geschäftsjahr at incorporation —
   common alternatives are Apr 1 – Mar 31 (matching DE-aligned
   parents) and Jul 1 – Jun 30 (matching some sectoral conventions).
   The close mechanics are jurisdiction-agnostic — the period
   bounds and retained-earnings account are what's AT-shaped.

   ## Bilanzgewinn vs Bilanzverlust

   UGB §224 prescribes the Bilanzgliederung. Equity line under
   Kapitalgesellschaften is **Bilanzgewinn (Bilanzverlust)** — a
   single account whose sign distinguishes profit (negative balance
   on equity = credit) from loss (positive = debit). We use a single
   9460 account in the chart for both; the sign of its balance
   carries the meaning.

   A Kapitalgesellschaft that wants to split the carryover into
   :gewinnvortrag and :verlustvortrag explicitly (before the
   shareholder resolution about Verwendung / dividend) can override
   `:retained-code` to route to a dedicated account.

   ## German-language convention

   The retained-earnings account is conventionally labelled
   'Bilanzgewinn / Bilanzverlust' per UGB §224. A future :en
   locale could override the label, but the account code (9460)
   remains stable.

   ## Per ADR-068

   - **`plan-at-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns
     the opts map the kernel closer needs. Useful for composing
     the close with adjacent writes (period seal, UVA export) in
     one `kontor.process`.

   - **`close-at-fiscal-year!`** — side-effecting wrapper that
     calls the planner, runs `kontor.closing/close-fiscal-year!`
     (which itself routes through the validation gate), and
     returns the close + period-close report."
  (:require [datahike.api :as d]
            [kontor.closing :as closing]))

(def ^:const default-retained-code
  "9460 Bilanzgewinn/Bilanzverlust per UGB §224 Bilanzgliederung."
  "9460")

(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-at chart first")
                      {:type :l10n-at/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the DE / CA l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Jahresabschluss-Buchungen"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-at-fiscal-year-close-tx-data
  "Resolve the opts an AT fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"9460\"
                       (UGB §224 Bilanzgewinn/Bilanzverlust)
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-at-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code   …          ; not yet resolved — `close-at-
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

(defn close-at-fiscal-year!
  "Close an Austrian fiscal-year period using the
   Einheitskontenrahmen default convention.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"9460\" — Bilanzgewinn/Bilanzverlust
                       per UGB §224)
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
        planned (plan-at-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

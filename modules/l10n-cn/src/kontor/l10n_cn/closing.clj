(ns kontor.l10n-cn.closing
  "Chinese fiscal-year close — rolls P&L accounts to retained
   earnings using the ASBE-default account convention.

   Sits as a thin country wrapper over `kontor.reporting.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `3104 利润分配 (Equity:RetainedEarnings)`
     - Closing journal           → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## CN fiscal-year-end specifics

   Per **Article 6 of the Accounting Law of the PRC** (会计法 第六条,
   1985-01-21 enacted; amended 2017, 1999, 1993) and the **Enterprise
   Income Tax Law Article 53** (企业所得税法 第五十三条, 2007-03-16):

     - The fiscal year for substantially all PRC enterprises is the
       **calendar year** (公历年度): January 1 – December 31.
     - A handful of carve-outs apply for FIE setup-mid-year or
       short-period closures, but they're statutorily defined
       exceptions, not customer-elected ones (unlike CA, where a
       corporation may elect any 12- or 53-week period at first
       registration).

   Practical consequence: this closer assumes a calendar-year
   `:period` entity. Setup-period or short-period closures are
   handled by supplying a non-calendar-year period to the kernel
   closer directly.

   ## ASBE / ASSBE account convention

   Per **Cai Kuai [2006] No. 18** (ASBE chart) and **Cai Kuai [2013]
   No. 13** (ASSBE companion), Chinese equity accounting uses a
   two-stage close:

     1. **Mid-year monthly close** — revenue + expense → `3103 本年利润`
        (Profit for the year). Accounting Standard #30 §17.
     2. **Year-end close** — `3103 本年利润` → `3104 利润分配—未分配
        利润` (Profit distribution — Undistributed profits).

   The substrate ships a **one-stage close** (P&L directly to 3104)
   to match the CA / DE template. The intermediate 3103 transit
   account is bypassed; consumers needing it produce two closing
   transactions (the kernel closer handles the per-account zero-out
   in one batch, and reporting tools that need the monthly-3103
   transit can compute it from the period balance).

   The 3104 label '利润分配 — 未分配利润' (Profit distribution —
   Undistributed profits) is the ASBE-canonical Chinese title; the
   l10n-cn chart ships the EN label 'Retained earnings'. A future
   :zh-CN locale could override the label, but the account code
   (3104) remains stable.

   ## Per ADR-068

   - **`plan-cn-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns
     the opts map the kernel closer needs.

   - **`close-cn-fiscal-year!`** — side-effecting wrapper that
     calls the planner, runs `kontor.reporting.closing/close-fiscal-year!`,
     and returns the close + period-close report."
  (:require [datahike.api :as d]
            [kontor.reporting.closing :as closing]))

(def ^:const default-retained-code "3104")     ; 利润分配 (Retained earnings)
(def ^:const default-profit-of-year-code "3103") ; 本年利润 (intermediate)
(def ^:const default-journal-code "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-cn chart first")
                      {:type :l10n-cn/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the CA / DE l10n closing module's bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Year-end closing entries / 年终结账"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-cn-fiscal-year-close-tx-data
  "Resolve the opts a CN fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.reporting.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"3104\" (利润分配)
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-cn-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …  ; resolved from the chart
      :journal-code …           ; not yet resolved
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

(defn close-cn-fiscal-year!
  "Close a CN fiscal-year period using the ASBE-default account
   convention. Per Article 6 of the Accounting Law of the PRC, the
   period is assumed to be the calendar year (January 1 – December
   31); the kernel closer takes a `:period-eid` so the caller
   nonetheless picks the actual period.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for 利润分配 (default \"3104\")
     :journal-code   — closing tx journal code
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
        planned (plan-cn-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

(ns kontor.l10n-jp.closing
  "Japanese fiscal-year close — rolls P&L accounts to retained
   earnings using the J-GAAP-style starter chart shipped in
   `chart.edn`.

   Sits as a thin country wrapper over `kontor.reporting.closing/close-fiscal-
   year!`, pinning:

     - Retained-earnings account → `330000`
       (Equity:RetainedEarnings — 利益剰余金 / 繰越利益剰余金)
     - Closing journal → `CLOSE` (auto-created if absent)

   Both are overridable.

   ## JP fiscal-year specifics — date flexibility

   Unlike Brazil or much of the EU, Japan does NOT mandate a
   calendar-year fiscal period. The fiscal-year-end (`決算日` /
   kessanbi) is declared in the company's `定款` (articles of
   incorporation) at registration and can be any date the company
   chooses, subject to a maximum 12-month period (会社法 §296).
   Empirically:

     - **March 31** is by far the most common (matches the government
       fiscal year `年度` and most listed entities). MoF surveys
       suggest 65-75 % of Japanese corporations end FY on March 31.
     - **December 31** (calendar year) is the second most common,
       typical of subsidiaries of foreign multinationals and many
       SMEs that prefer alignment with the personal income-tax year.
     - **September 30** and **June 30** are seen in some industries
       (e.g. some retailers and trading houses).

   The close *mechanics* are jurisdiction-agnostic — the period
   bounds and the retained-earnings account are what's JP-shaped.
   The wrapper does **not** enforce a March-31 end; it accepts any
   `:kontor.period/end` the caller has set up.

   ## 繰越利益剰余金 (kurikoshi rieki jōyokin)

   The equity-side rollup target. Under J-GAAP / Companies Act
   §445-§453, retained earnings sit in the `純資産` (net assets)
   section, typically split into:

     - `利益剰余金` (rieki jōyokin) — Retained Earnings (umbrella)
       - `利益準備金` (legal reserve, ~10 % cap on distributions)
       - `その他利益剰余金`
         - `任意積立金` (voluntary appropriated reserves)
         - `繰越利益剰余金` (retained earnings *carried forward*) ←
           the target for fiscal-year close

   The conventional kanji terms appearing during close:
     - `当期純利益` (tōki jun-rieki) — Net Income for the Current Period
     - `当期純損失` (tōki jun-sonshitsu) — Net Loss for the Current Period
     - `繰越利益剰余金` — receives the period's net result

   Our starter chart consolidates these under a single
   `Equity:RetainedEarnings` account (code `330000`); businesses
   that distinguish 利益準備金 from 繰越利益剰余金 (Kabushiki Kaisha
   that have paid out dividends under §445) can override
   `:retained-code` to route to their own sub-account. After
   the close, the shareholder meeting (株主総会) resolves on
   dividends + reserve appropriations — those are subsequent
   transactions the consumer posts via the standard bookkeeping
   primitives, not this module's responsibility.

   ## Per ADR-068

   - **`plan-jp-fiscal-year-close-tx-data`** — pure planner that
     resolves account/journal eids from the DB value and returns
     the opts map the kernel closer needs. Useful for composing
     the close with adjacent writes (period seal, e-Tax filing
     export) in one `kontor.workflow.process`.

   - **`close-jp-fiscal-year!`** — side-effecting wrapper that
     calls the planner, runs `kontor.reporting.closing/close-fiscal-year!`
     (which itself routes through the validation gate), and
     returns the close + period-close report."
  (:require [datahike.api :as d]
            [kontor.reporting.closing :as closing]))

(def ^:const default-retained-code "330000")
(def ^:const default-journal-code  "CLOSE")

(defn- account-by-code [db code]
  (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code))

(defn- require-retained [db code]
  (or (account-by-code db code)
      (throw (ex-info (str "Retained-earnings account " code
                           " not found — install l10n-jp chart first")
                      {:type :l10n-jp/missing-retained-earnings
                       :code code}))))

(defn- ensure-journal!
  "Resolve the closing journal eid, auto-creating it if missing.
   Mirrors the CA / DE / BR l10n closing modules' bootstrap behaviour."
  [conn code]
  (or (:db/id (d/entity (d/db conn) [:kontor.journal/code code]))
      (do
        (d/transact conn [{:kontor.journal/code code
                           :kontor.journal/name "Year-end closing entries (決算仕訳)"
                           :kontor.journal/type :closing
                           :kontor.journal/active true}])
        (:db/id (d/entity (d/db conn) [:kontor.journal/code code])))))

(defn plan-jp-fiscal-year-close-tx-data
  "Resolve the opts a JP fiscal-year close needs (ADR-068 pure form).

   The kernel closer (`kontor.reporting.closing/close-fiscal-year!`) reads
   period-end balances from the live connection — those reads are
   not expressible as a static tx-data vector, so this planner
   returns the *resolved-opts map* the closer will consume rather
   than a tx-data vector directly. The shape is still composable
   into a `kontor.workflow.process` step via a custom build-fn.

   Required:
     :period-eid

   Optional:
     :retained-code  — defaults to \"330000\"
                       (Equity:RetainedEarnings / 繰越利益剰余金)
     :journal-code   — defaults to \"CLOSE\"
                       (auto-created via close-jp-fiscal-year!)
     :external-id, :narration, :at — passed through to kernel closer.

   Returns:
     {:period-eid …
      :retained-earnings-eid …    ; resolved from the chart
      :journal-code   …           ; not yet resolved — `close-jp-
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

(defn close-jp-fiscal-year!
  "Close a JP fiscal-year period using the starter J-GAAP-style chart.

   The fiscal-year-end date is determined by the `:kontor.period/end` of the
   referenced period — the wrapper does **not** enforce March 31. JP
   businesses set their fiscal-year-end in their 定款 (articles of
   incorporation); common patterns include March 31, December 31,
   September 30, and June 30. The same wrapper handles all of them.

   Required:
     :period-eid

   Optional:
     :retained-code  — chart code for the retained-earnings account
                       (default \"330000\" — 繰越利益剰余金 /
                       Equity:RetainedEarnings)
     :journal-code   — journal code for the closing tx
                       (default \"CLOSE\"; auto-created if absent)
     :external-id, :narration, :at — passed through.

   Returns the kernel close-fiscal-year! result:
     {:close-result …          ; from close-period!
      :period-close-tx-report …}"
  [conn {:keys [retained-code journal-code]
         :or {retained-code default-retained-code
              journal-code default-journal-code}
         :as opts}]
  (let [db (d/db conn)
        planned (plan-jp-fiscal-year-close-tx-data
                 db (assoc opts :retained-code retained-code))
        jnl-eid (ensure-journal! conn journal-code)
        opts'   (-> planned
                    (dissoc :journal-code)
                    (assoc :journal-eid jnl-eid))]
    (closing/close-fiscal-year! conn opts')))

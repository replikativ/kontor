(ns kontor.l10n-us.sales-tax
  "Per-state US sales-tax filing reports.

   The US has no federal sales tax. Every state with sales tax
   requires its own return — Texas Comptroller, California CDTFA,
   New York DTF, Washington DOR, Florida DOR — typically monthly,
   quarterly, or annually depending on volume. Some Colorado / Alabama /
   Louisiana home-rule cities self-administer and require an
   additional filing per municipality.

   Per ADR-014's :tax/authority + the cross-country research note 09:
   we model each filing authority as its own report definition. This
   namespace ships the 5 highest-revenue states + a sample home-rule
   city (Denver, CO) as the reference shape; consumers add states
   as they hit nexus.

   Every report is single-line for the kernel: total sales tax
   collected for that authority. Real DOR returns are richer
   (gross sales, taxable sales, exempt sales, then per-tax-rate
   breakdowns) — those expansions land per-state when an actual
   filer asks.

   Sources: per-state DOR forms (TX 01-117, CA CDTFA-401-A2,
   NY ST-100, WA combined excise return, FL DR-15, Denver Sales/Use
   Tax Return)."
  (:require [kontor.money :as money]
            [kontor.report :as report]))

(def state-codes
  "Map of state-code keyword → {label / authority / tag-prefix}.
   Authority is what prints on the filing — used by the bookkeeper
   to know who to remit to. Tag-prefix is the :kontor.account-tag/name
   that the chart's per-state liability accounts use."
  {:ca {:label "California" :authority :us-ca-cdtfa
        :tag :us-ca-state-line-1}
   :tx {:label "Texas"      :authority :us-tx-cpa
        :tag :us-tx-state-line-1}
   :ny {:label "New York"   :authority :us-ny-dtf
        :tag :us-ny-state-line-1}
   :wa {:label "Washington" :authority :us-wa-dor
        :tag :us-wa-state-line-1}
   :fl {:label "Florida"    :authority :us-fl-dor
        :tag :us-fl-state-line-1}
   :co-denver {:label "Denver, CO (home-rule city)"
               :authority :us-co-denver
               :tag :us-co-denver-line-1}})

(defn report-definition-for
  "Build a report definition for `state-kw` (must be a key of
   `state-codes`)."
  [state-kw]
  (let [{:keys [label tag]} (state-codes state-kw)]
    {:report/name (str "US Sales Tax Return — " label)
     :report/country "US"
     :report/lines
     [{:line/code "1"
       :line/label "Sales tax collected"
       :line/expression {:engine :tax-tags :tags [tag]
                         :sign :inflow :commodity :USD}}]}))

(defn compute-state
  "Run the per-state filing report. Returns standard report shape
   plus :sales-tax/payable convenience field."
  [conn state-kw opts]
  (let [r (report/compute-report conn (report-definition-for state-kw) opts)
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        amt (or (get line "1") (money/zero :USD))]
    (assoc r
           :sales-tax/state state-kw
           :sales-tax/authority (:authority (state-codes state-kw))
           :sales-tax/payable amt
           :sales-tax/lines (into {} (map (fn [[k v]] [(keyword k) v])) line))))

(defn compute-all-active-states
  "Convenience: run every state report against the same window.
   Returns {state-kw computed-report}. Skips states with zero
   activity (no postings to that state's tax-tag)."
  [conn opts]
  (->> (keys state-codes)
       (map (fn [s] [s (compute-state conn s opts)]))
       (remove (fn [[_ r]] (money/zero? (:sales-tax/payable r))))
       (into {})))

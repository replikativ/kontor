(ns kontor.l10n-ca.returns
  "Canadian per-authority preparatory reports for QST and BC PST.

   * **Revenu Québec QST** — Quebec only. Filed combined with GST in
     QC for GST-only-or-QC-resident filers. Form lines:
       203 — QST collected
       206 — QST ITRs
       213 = 203 − 206 = QST payable

   * **BC PST / SK PST / MB RST** — provincial, separate per province.
     PST is NOT a VAT — collected by sellers and remitted; the
     PST PAID side hits expense (no input-tax credit). Single line:
       BC PST line A = total PST collected

   GST/HST (CRA federal) lives in `kontor.l10n-ca.gst-hst` — see ADR-015
   for the filing-module-per-authority pattern.

   The kernel filters on `:kontor.account-tag/name` (which embeds the
   :ca-{authority}-{box} convention)."
  (:require [kontor.money :as money]
            [kontor.report :as report]))

;; ============================================================================
;; Revenu Québec QST report
;; ============================================================================

(def rq-qst-definition
  {:report/name    "QST Return (Revenu Québec)"
   :report/country "CA"
   :report/lines
   [{:line/code "203"
     :line/label "QST collected"
     :line/expression {:engine :tax-tags :tags [:ca-rq-line-203]
                       :sign :inflow :commodity :CAD}}
    {:line/code "206"
     :line/label "QST input tax refunds (ITRs)"
     :line/expression {:engine :tax-tags :tags [:ca-rq-line-206]
                       :sign :inflow :commodity :CAD}}]})

(defn compute-qst
  [conn opts]
  (let [r (report/compute-report conn rq-qst-definition opts)
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        collected (line "203")
        itr       (line "206")
        zero (money/zero (or (:commodity collected) :CAD))
        net (-> zero
                (cond-> collected (money/add collected))
                (cond-> itr       (money/sub itr)))]
    (assoc r
           :qst/net-tax net
           :qst/lines (into {} (map (fn [[k v]] [(keyword k) v])) line))))

;; ============================================================================
;; BC PST report (provincial — separate authority)
;; ============================================================================

(def bc-pst-definition
  {:report/name    "BC PST Return (Province of BC)"
   :report/country "CA"
   :report/lines
   [{:line/code "A"
     :line/label "BC PST collected on sales"
     :line/expression {:engine :tax-tags :tags [:ca-bc-pst-line-A]
                       :sign :inflow :commodity :CAD}}]})

(defn compute-bc-pst
  [conn opts]
  (let [r (report/compute-report conn bc-pst-definition opts)
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        a (line "A")]
    (assoc r
           :bc-pst/payable (or a (money/zero :CAD))
           :bc-pst/lines (into {} (map (fn [[k v]] [(keyword k) v])) line))))

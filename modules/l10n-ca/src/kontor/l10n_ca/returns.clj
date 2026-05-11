(ns datahike-accounting.l10n-ca.returns
  "Canadian tax returns — split per authority, per ADR-014's
   `:tax/authority` design.

   Three filings most CA SMBs hit:

   * **CRA GST/HST** (federal) — combined report covering federal GST
     5% and the harmonized HST in ON/NB/NL/PEI/NS. CRA form lines:
       101 — Sales (incl. zero-rated, exempt)
       103 — GST/HST collected
       108 — GST/HST input tax credits (ITCs)
       113 = 103 − 108 = net tax (positive = pay; negative = refund)

   * **Revenu Québec QST** — Quebec only. Filed combined with GST in
     QC for GST-only-or-QC-resident filers. Form lines:
       203 — QST collected
       206 — QST ITRs
       213 = 203 − 206 = QST payable

   * **BC PST / SK PST / MB RST** — provincial, separate per province.
     PST is NOT a VAT — collected by sellers and remitted; the
     PST PAID side hits expense (no input-tax credit). Single line:
       BC PST line A = total PST collected

   The kernel filters on `:account-tag/name` (which embeds the
   :ca-{authority}-{box} convention) and `:tax/authority` (when we
   transact :tax entities later). Today the CA chart uses tags only —
   no :tax entities yet because GST/HST/PST/QST are typically modeled
   as direct postings rather than TaxProvider-derived ones for SMBs."
  (:require [datahike-accounting.money :as money]
            [datahike-accounting.report :as report]))

;; ============================================================================
;; CRA GST/HST report
;; ============================================================================

(def cra-gst-hst-definition
  {:report/name    "GST/HST Return (CRA)"
   :report/country "CA"
   :report/lines
   [{:line/code "101"
     :line/label "Total sales and other revenue"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-101]
                       :sign :inflow :commodity :CAD}}
    {:line/code "103"
     :line/label "GST/HST collected"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-103]
                       :sign :inflow :commodity :CAD}}
    {:line/code "108"
     :line/label "GST/HST input tax credits (ITCs)"
     :line/expression {:engine :tax-tags :tags [:ca-cra-line-108]
                       :sign :inflow :commodity :CAD}}]})

(defn compute-gst-hst
  [conn opts]
  (let [r (report/compute-report conn cra-gst-hst-definition opts)
        line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                   (:report/lines r))
        collected (line "103")
        itc       (line "108")
        zero (money/zero (or (:commodity collected) :CAD))
        net (-> zero
                (cond-> collected (money/add collected))
                (cond-> itc       (money/sub itc)))]
    (assoc r
           :gst-hst/net-tax net
           :gst-hst/lines (into {} (map (fn [[k v]] [(keyword k) v])) line))))

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

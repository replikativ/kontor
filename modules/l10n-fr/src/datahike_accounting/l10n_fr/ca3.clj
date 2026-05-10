(ns datahike-accounting.l10n-fr.ca3
  "Déclaration TVA CA3 (Cerfa 3310-CA3) — French monthly VAT return.

   Field codes per official 3310-CA3 form. Load-bearing subset:

     01-base — Operations 20% (base montant HT)
     02-base — Operations 10% (base)
     03-base — Operations 5,5% (base)
     04-base — Operations 2,1% (base)
     06-base — Acquisitions intracommunautaires (base)
     06-tva  — TVA due intracommunautaire (auto-liquidation)
     08-vt20 — TVA 20% collectée
     08-vt10 — TVA 10% collectée
     08-vt55 — TVA 5,5% collectée
     08-vt21 — TVA 2,1% collectée
     20      — TVA déductible sur biens et services + immobilisations

   Source: BOFiP-Impôts BOI-TVA-DECLA-20-20 (CA3 instructions);
   Cerfa 3310-CA3 form layout 2026.

   The CA3 then computes:
     16 = sum of 08-* (TVA collectée totale)
     20 = TVA déductible totale
     28 = 16 + 06-tva − 20 = TVA à payer (positive) or crédit (negative)

   FR encaissements vs débits:
     Service providers default to TVA sur les encaissements (cash).
     Goods sellers always sur les débits (accrual). Modeled per-:tax
     entity via :tax/exigibility :on-payment | :on-invoice. The CA3
     computation is the same; only the timing of when a posting
     enters the report changes."
  (:require [datahike-accounting.money :as money]
            [datahike-accounting.report :as report]))

(def report-definition
  {:report/name    "Déclaration de TVA CA3 2026 (France)"
   :report/country "FR"
   :report/lines
   [;; Bases (taxed sales by rate)
    {:line/code "01-base"
     :line/label "Opérations imposables 20% (HT)"
     :line/expression {:engine :tax-tags :tags [:ca3-01-base] :sign :inflow}}
    {:line/code "02-base"
     :line/label "Opérations imposables 10% (HT)"
     :line/expression {:engine :tax-tags :tags [:ca3-02-base] :sign :inflow}}
    {:line/code "03-base"
     :line/label "Opérations imposables 5,5% (HT)"
     :line/expression {:engine :tax-tags :tags [:ca3-03-base] :sign :inflow}}
    {:line/code "04-base"
     :line/label "Taux particuliers 2,1% (HT)"
     :line/expression {:engine :tax-tags :tags [:ca3-04-base] :sign :inflow}}
    {:line/code "06-base"
     :line/label "Acquisitions intracommunautaires (HT)"
     :line/expression {:engine :tax-tags :tags [:ca3-06-base] :sign :inflow}}

    ;; TVA collectée (output VAT amounts)
    {:line/code "08-vt20"
     :line/label "TVA 20% collectée"
     :line/expression {:engine :tax-tags :tags [:ca3-08-vt20] :sign :inflow}}
    {:line/code "08-vt10"
     :line/label "TVA 10% collectée"
     :line/expression {:engine :tax-tags :tags [:ca3-08-vt10] :sign :inflow}}
    {:line/code "08-vt55"
     :line/label "TVA 5,5% collectée"
     :line/expression {:engine :tax-tags :tags [:ca3-08-vt55] :sign :inflow}}
    {:line/code "08-vt21"
     :line/label "TVA 2,1% collectée"
     :line/expression {:engine :tax-tags :tags [:ca3-08-vt21] :sign :inflow}}

    {:line/code "06-tva"
     :line/label "TVA due intracommunautaire (auto-liquidation)"
     :line/expression {:engine :tax-tags :tags [:ca3-06-tva] :sign :inflow}}

    ;; TVA déductible (input VAT)
    {:line/code "20"
     :line/label "TVA déductible (biens, services, immobilisations)"
     :line/expression {:engine :tax-tags :tags [:ca3-20] :sign :inflow}}]})

(defn compute
  "Returns the standard report shape plus :ca3/tva-a-payer (positive
   = à payer, negative = crédit) and :ca3/lines digest."
  ([conn] (compute conn {}))
  ([conn opts]
   (let [computed (report/compute-report conn report-definition opts)
         line (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                    (:report/lines computed))
         ;; Total TVA collectée: sum of 08-vt20/10/55/21 + 06-tva
         outputs (keep line ["08-vt20" "08-vt10" "08-vt55" "08-vt21" "06-tva"])
         input  (line "20")
         zero (money/zero (:commodity (first outputs)))
         total-out (reduce money/add zero outputs)
         a-payer (cond-> total-out
                   input (money/sub input))]
     (assoc computed
            :ca3/tva-collectee total-out
            :ca3/tva-deductible (or input zero)
            :ca3/tva-a-payer a-payer
            :ca3/lines (into {} (map (fn [[k v]] [(keyword k) v])) line)))))

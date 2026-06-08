(ns kontor.l10n-at.uva
  "Austrian Umsatzsteuervoranmeldung (U30 / monthly UVA).

   The UVA is field-coded — every line is a 3-digit Kennzahl. We ship
   the load-bearing 8 fields:

     022 — Steuerpflichtige Umsätze 20% (base)
     006 — Steuerpflichtige Umsätze 13% (base, 2026)
     029 — Steuerpflichtige Umsätze 10% (base)
     022-ust — USt 20% (output VAT)
     006-ust — USt 13%
     029-ust — USt 10%
     057 — Reverse-Charge §19/1a Bauleistungen (base — recipient owes)
     066 — Vorsteuer (deductible input VAT total)
     011 — Steuerfreie innergem. Lieferungen §6 Abs.1 Z.6
     021 — Steuerfreie Umsätze §6

   Source: BMF UVA-Formular Stand 2026; field codes per official
   Bundesministerium für Finanzen documentation. Form layout differs
   slightly between paper and FinanzOnline electronic submission;
   field codes are stable.

   Computation:
     (compute conn {:from <Date> :to <Date>}) → computed report

   Mirrors the DE UStVA shape (modules/l10n-de/.../ustva.clj). The
   only structural difference is the rate set (20/13/10/0 vs DE's
   19/7/0)."
  (:require [kontor.money :as money]
            [kontor.reporting.report :as report]))

(def report-definition
  "UVA Stand 2026 monthly. 10 load-bearing fields."
  {:report/name    "Umsatzsteuervoranmeldung 2026 (Österreich)"
   :report/country "AT"
   :report/lines
   [{:line/code "022"
     :line/label "Steuerpflichtige Umsätze 20% (Normalsatz)"
     :line/expression {:engine :tax-tags :tags [:uva-022] :sign :inflow}}
    {:line/code "006"
     :line/label "Steuerpflichtige Umsätze 13% (ermäßigter Satz)"
     :line/expression {:engine :tax-tags :tags [:uva-006] :sign :inflow}}
    {:line/code "029"
     :line/label "Steuerpflichtige Umsätze 10% (ermäßigter Satz)"
     :line/expression {:engine :tax-tags :tags [:uva-029] :sign :inflow}}

    {:line/code "011"
     :line/label "Innergemeinschaftliche Lieferungen §6 Abs.1 Z.6"
     :line/expression {:engine :tax-tags :tags [:uva-011] :sign :inflow}}
    {:line/code "021"
     :line/label "Steuerfreie Umsätze §6 (übrige)"
     :line/expression {:engine :tax-tags :tags [:uva-021] :sign :inflow}}

    {:line/code "022-ust"
     :line/label "USt 20% (auf Umsätze 022)"
     :line/expression {:engine :tax-tags :tags [:uva-022-ust] :sign :inflow}}
    {:line/code "006-ust"
     :line/label "USt 13% (auf Umsätze 006)"
     :line/expression {:engine :tax-tags :tags [:uva-006-ust] :sign :inflow}}
    {:line/code "029-ust"
     :line/label "USt 10% (auf Umsätze 029)"
     :line/expression {:engine :tax-tags :tags [:uva-029-ust] :sign :inflow}}

    {:line/code "057-ust"
     :line/label "USt aus Reverse-Charge §19/1a (Bauleistungen)"
     :line/expression {:engine :tax-tags :tags [:uva-057-ust] :sign :inflow}}

    {:line/code "066"
     :line/label "Vorsteuer (gesamt)"
     :line/expression {:engine :tax-tags :tags [:uva-066] :sign :inflow}}]})

(defn compute
  "Compute UVA. Returns the standard report shape plus
   :uva/zahllast (positive = pay; negative = Gutschrift) and
   :uva/lines (keyword-keyed by field code)."
  ([conn] (compute conn {}))
  ([conn opts]
   (let [computed (report/compute-report conn report-definition opts)
         line-by-code (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                            (:report/lines computed))
         ust-20 (get line-by-code "022-ust")
         ust-13 (get line-by-code "006-ust")
         ust-10 (get line-by-code "029-ust")
         ust-rc (get line-by-code "057-ust")
         vorst  (get line-by-code "066")
         zahllast (cond-> (money/zero (:commodity ust-20))
                    ust-20 (money/add ust-20)
                    ust-13 (money/add ust-13)
                    ust-10 (money/add ust-10)
                    ust-rc (money/add ust-rc)
                    vorst  (money/sub vorst))]
     (assoc computed
            :uva/zahllast zahllast
            :uva/lines    (into {} (map (fn [[k v]] [(keyword k) v]))
                                line-by-code)))))

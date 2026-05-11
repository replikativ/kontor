(ns datahike-accounting.l10n-de.ustva
  "Umsatzsteuer-Voranmeldung (UStVA — German VAT advance return).

   The 8 load-bearing line items, mapped onto the kernel's
   declarative report engine via `:tax-tags`. Account tags are
   defined in `resources/datahike_accounting/l10n_de/skr04.edn`:

     :ust-81      — Steuerpflichtige Umsätze 19% (revenue, base)
     :ust-86      — Steuerpflichtige Umsätze 7%  (revenue, base)
     :ust-81-ust  — Umsatzsteuer 19% (output VAT collected)
     :ust-86-ust  — Umsatzsteuer 7%  (output VAT collected)
     :ust-66      — Vorsteuer (deductible input VAT)
     :ust-41      — Innergem. Lieferungen (intra-community supplies)
     :ust-21      — Reverse-charge sonstige Leistungen
     :ust-43      — Steuerfreie Umsätze §4 UStG

   The official Umsatzsteuer-Voranmeldung 2026 form has more lines
   (~40 boxes); we ship the load-bearing 8 and add others as
   real customer scenarios surface. Box numbers track the official
   form so the line-coded output is directly fillable.

   Computation:
     (compute conn {:from <Date> :to <Date>}) → computed report

   The result mirrors `report/compute-report` shape; consumer apps
   render line-by-line, flag negative balances (refunds), and
   prepare the ELSTER XML for filing (out of scope — separate
   module when we get there)."
  (:require [datahike-accounting.money :as money]
            [datahike-accounting.report :as report]))

(def report-definition
  "UStVA 2026 monthly. The 8 load-bearing boxes."
  {:report/name    "Umsatzsteuer-Voranmeldung 2026"
   :report/country "DE"
   :report/lines
   [;; Revenue (base amounts) — :sign :inflow because revenue is
    ;; credit-natural; users expect a positive number.
    {:line/code "81"
     :line/label "Steuerpflichtige Umsätze 19%"
     :line/expression {:engine :tax-tags :tags [:ust-81] :sign :inflow}}
    {:line/code "86"
     :line/label "Steuerpflichtige Umsätze 7%"
     :line/expression {:engine :tax-tags :tags [:ust-86] :sign :inflow}}

    ;; Steuerfrei
    {:line/code "41"
     :line/label "Innergemeinschaftliche Lieferungen §4 Nr. 1b UStG"
     :line/expression {:engine :tax-tags :tags [:ust-41] :sign :inflow}}
    {:line/code "21"
     :line/label "Steuerfreie sonstige Leistungen §3a Abs. 2 UStG"
     :line/expression {:engine :tax-tags :tags [:ust-21] :sign :inflow}}
    {:line/code "43"
     :line/label "Steuerfreie Umsätze §4 UStG (übrige)"
     :line/expression {:engine :tax-tags :tags [:ust-43] :sign :inflow}}

    ;; Output VAT (the tax itself, not the base) — :inflow on
    ;; the credit-natural Umsatzsteuer accounts gives the positive
    ;; amount the form expects.
    {:line/code "81-ust"
     :line/label "USt 19% (auf Umsätze 81)"
     :line/expression {:engine :tax-tags :tags [:ust-81-ust] :sign :inflow}}
    {:line/code "86-ust"
     :line/label "USt 7% (auf Umsätze 86)"
     :line/expression {:engine :tax-tags :tags [:ust-86-ust] :sign :inflow}}

    ;; Input VAT (Vorsteuer) — debit-natural on asset accounts;
    ;; :inflow leaves the stored positive amount as-is, which is
    ;; the form-expected positive number.
    {:line/code "66"
     :line/label "Abziehbare Vorsteuer"
     :line/expression {:engine :tax-tags :tags [:ust-66] :sign :inflow}}]})

(defn compute
  "Compute UStVA for the given window. Returns a map adding two
   convenience fields to the raw computed report:

     :ustva/zahllast — net VAT payable (positive = pay, negative = refund)
     :ustva/lines    — keyword-keyed digest by box code:
                       {:81 Money :86 Money :66 Money …}

   Use the standard `:from`/`:to`/`:as-of-tx`/`:include-states`
   options from `report/compute-report`."
  ([conn] (compute conn {}))
  ([conn opts]
   (let [computed (report/compute-report conn report-definition opts)
         line-by-code (into {} (map (fn [l] [(:line/code l) (:line/value l)]))
                            (:report/lines computed))
         ust-19 (get line-by-code "81-ust")
         ust-7  (get line-by-code "86-ust")
         vorst  (get line-by-code "66")
         ;; All EUR. Zahllast = USt-19 + USt-7 - Vorsteuer
         zahllast (cond-> (money/zero (:commodity ust-19))
                    ust-19 (money/add ust-19)
                    ust-7  (money/add ust-7)
                    vorst  (money/sub vorst))]
     (assoc computed
            :ustva/zahllast zahllast
            :ustva/lines    (into {} (map (fn [[k v]]
                                            [(keyword k) v]))
                                  line-by-code)))))

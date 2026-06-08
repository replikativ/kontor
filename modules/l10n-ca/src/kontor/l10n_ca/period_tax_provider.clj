(ns kontor.l10n-ca.period-tax-provider
  "CA T1 personal income tax as a kontor `PeriodTaxProvider` — the
   period-tax pilot (ADR-099).

   A Shape-B wrapper: it does not reimplement T1 — it calls
   the existing `kontor.l10n-ca.y2024.t1/compute` and reshapes the
   result into a `TaxReturnFacts`. `compute` works off T4 slips, so
   the t1 input map rides `context :inputs`; the provider does not
   marginalize the GL (the σ_E base-selector path is exercised by the
   kernel substrate's own tests — this pilot validates the protocol
   shape against real prior art).

   The CA return is **two** `:personal-income-tax` components — federal
   (CRA) and provincial (BC428) — over one `TaxReturnFacts`: the
   period-tax instance of the multi-authority fan-out CA's transaction
   `TaxFacts` already used. Capital gains is NOT a separate component:
   CA folds it into income (S3 line 12700 → 15000), so an `:s3` entry
   in `:inputs` lifts the federal component's `:base` — the
   `:inputs`-fed CGT path.

   Per ADR-015 this wraps the immutable TY2024 `t1`; a multi-year
   provider is a later refinement."
  (:require [kontor.l10n-ca.y2024.constants :as k]
            [kontor.l10n-ca.y2024.t1 :as t1]
            [kontor.money :as money]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.tax-schedule :as ts]))

(defn- line-items
  "Reshape t1's `:t1/lines` (line# keyword → Money) into the ordered
   `:line-items` vector — the return's form detail, the audit payload."
  [t1-result]
  (mapv (fn [[line amount]]
          {:line line :label (str "T1 line " (name line)) :value amount})
        (sort-by key (:t1/lines t1-result))))

(defn t1-tax-return-facts
  "Pure: `{:entity :period :inputs}` (the `:inputs` a t1 input map) →
   a `TaxReturnFacts`. The wrapper core, exposed for direct testing /
   reuse independent of the protocol record."
  [{:keys [entity period inputs]}]
  (let [result   (t1/compute inputs)
        province (:filer/province inputs)
        taxable  (get-in result [:t1/lines :26000])
        federal  {:kind            :personal-income-tax
                  :authority       :cra
                  :base            taxable
                  :schedule        (ts/progressive k/federal-brackets)
                  :gross-liability (get-in result [:t1/lines :40400])
                  :credits         [{:code   :nrtc
                                     :label  "Non-refundable tax credits"
                                     :amount (get-in result [:t1/lines :35000])}
                                    {:code   :dtc
                                     :label  "Federal dividend tax credit"
                                     :amount (get-in result [:t1/lines :40425])}]
                  :liability       (:t1/federal-tax result)
                  :prepaid         (:t1/income-tax-paid result)
                  :provenance      {:provider-id :ca-t1 :form "T1"
                                    :statute "Income Tax Act (Canada)"}
                  :line-items      (line-items result)
                  :jurisdiction-specific-codes {:ca/form "T1"}}
        provincial (when (and province (:t1/bc-tax result))
                     {:kind       :personal-income-tax
                      :authority  :bc
                      :base       taxable
                      :schedule   (ts/progressive k/bc-brackets)
                      :liability  (:t1/bc-tax result)
                      :prepaid    (money/zero :CAD)
                      :provenance {:provider-id :ca-t1 :form "BC428"
                                   :statute "Income Tax Act (British Columbia)"}
                      :jurisdiction-specific-codes {:ca/form "BC428"
                                                    :ca/province province}})]
    (ptp/tax-return-facts
     {:entity               entity
      :period               (or period {:from #inst "2024-01-01"
                                         :to   #inst "2025-01-01"})
      :jurisdiction         {:country "CA" :subdivision province}
      :functional-commodity :CAD
      :components           (if provincial [federal provincial] [federal])})))

(defrecord CaT1PeriodTaxProvider []
  ptp/PeriodTaxProvider
  (provider-id [_] :ca-t1)
  (period-tax-facts [_ context]
    (t1-tax-return-facts context)))

(defn ca-t1-period-tax-provider
  "The CA T1 (TY2024) personal-income-tax `PeriodTaxProvider`."
  []
  (->CaT1PeriodTaxProvider))

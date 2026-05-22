(ns kontor.l10n-au.period-tax-provider
  "Australian state payroll tax — the employer payroll levy — as a
   kontor `PeriodTaxProvider` (ADR-099; research note 103).

   Each state / territory sets its own rate and tax-free threshold;
   the levy is the rate on the annual wage bill ABOVE the threshold —
   the substrate's `:capped` schedule with `:floor` = the threshold.

   `payroll-tax-rates` carries all eight jurisdictions. Statutory
   rates and thresholds move — verify against current state revenue
   offices."
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.standalone-payroll-tax :as spt]
            [kontor.tax-schedule :as ts]))

(def payroll-tax-rates
  "Per-state `{:rate :threshold}` — annual figures (verify; they move)."
  {:NSW {:rate 0.0545M :threshold 1200000M}
   :VIC {:rate 0.0485M :threshold 900000M}
   :QLD {:rate 0.0475M :threshold 1300000M}
   :WA  {:rate 0.055M  :threshold 1000000M}
   :SA  {:rate 0.0495M :threshold 1500000M}
   :TAS {:rate 0.061M  :threshold 1250000M}
   :ACT {:rate 0.0685M :threshold 2000000M}
   :NT  {:rate 0.055M  :threshold 1500000M}})

(defn au-payroll-tax-provider
  "An AU state payroll-tax `PeriodTaxProvider` for one state. Config:
     :state      — a state keyword (NSW / VIC / QLD / WA / SA / TAS /
                   ACT / NT)
     :wage-codes — chart account codes for payroll wage expense"
  [{:keys [state wage-codes]}]
  (let [{:keys [rate threshold]}
        (or (payroll-tax-rates state)
            (throw (ex-info "au-payroll-tax-provider: unknown state"
                            {:state state
                             :known (set (keys payroll-tax-rates))})))]
    (spt/standalone-payroll-tax-provider
     {:id         :au-payroll-tax
      :schedule   (ts/capped rate {:floor threshold})
      :wage-codes wage-codes
      :authority  (keyword "au-state" (name state))
      :commodity  :AUD
      :statute    "Payroll Tax Act (state / territory)"
      :base-label "Taxable wages"})))

;; ============================================================================
;; Company income tax
;; ============================================================================

(defn au-company-tax-provider
  "AU company income tax provider. 25 % for a base-rate entity
   (aggregated turnover < $50M and ≤ 80 % passive income), else 30 %.
   Config:
     :base-rate-entity? — true for the 25 % base-rate-entity rate
     :rate              — optional explicit override"
  [{:keys [base-rate-entity? rate]}]
  (cit/corporate-income-tax-provider
   {:id        :au-company-tax
    :rate      (or rate (if base-rate-entity? 0.25M 0.30M))
    :authority :au-ato
    :commodity :AUD
    :statute   "Income Tax Assessment Act 1997"}))

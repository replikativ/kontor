(ns kontor.l10n-mx.period-tax-provider
  "Mexican ISN (Impuesto Sobre Nóminas) — the state employer payroll
   tax — as a kontor `PeriodTaxProvider` (ADR-099; research note 103).

   ISN is a flat state levy on the monthly payroll; rates run ~1–3 %
   and are set per state. Research note 103 flagged ISN as a true gap
   — `l10n-mx` did not model it at all. It is the textbook standalone
   payroll tax: `flat-rate × Σ(wage-expense postings)`.

   `isn-rates` carries a representative subset of the 32 states; for
   any other state pass an explicit `:rate`. Statutory rates change —
   verify against current state law."
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.standalone-payroll-tax :as spt]
            [kontor.tax-schedule :as ts]))

(def isn-rates
  "ISN rate by state — a representative subset (rates change; verify).
   Supply an explicit `:rate` for states not listed; `:default` 3 %."
  {:cdmx          0.03M
   :jalisco       0.02M
   :nuevo-leon    0.03M
   :estado-mexico 0.03M
   :default       0.03M})

(defn mx-isn-provider
  "An MX ISN `PeriodTaxProvider` for one state. Config:
     :state      — a state keyword (looked up in `isn-rates`)
     :rate       — optional explicit rate override
     :wage-codes — chart account codes for payroll wage expense"
  [{:keys [state rate wage-codes]}]
  (spt/standalone-payroll-tax-provider
   {:id         :mx-isn
    :schedule   (ts/flat (or rate
                             (get isn-rates state)
                             (:default isn-rates)))
    :wage-codes wage-codes
    :authority  (keyword "mx-state" (name (or state :unknown)))
    :commodity  :MXN
    :statute    "Impuesto Sobre Nóminas (ley estatal)"
    :base-label "Nómina gravable"}))

;; ============================================================================
;; ISR personas morales — corporate income tax
;; ============================================================================

(def isr-corporate-rate
  "ISR personas morales — flat 30 % (Ley del ISR §9)."
  0.30M)

(defn mx-isr-corporate-provider
  "MX corporate income tax — ISR personas morales — provider. A flat
   30 % on taxable profit (utilidad fiscal). Config:
     :rate — optional override (default 30 %)"
  [{:keys [rate]}]
  (cit/corporate-income-tax-provider
   {:id        :mx-isr-corporate
    :rate      (or rate isr-corporate-rate)
    :authority :mx-sat
    :commodity :MXN
    :statute   "Ley del Impuesto sobre la Renta"}))

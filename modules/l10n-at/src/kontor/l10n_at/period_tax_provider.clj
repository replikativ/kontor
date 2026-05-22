(ns kontor.l10n-at.period-tax-provider
  "Austrian Kommunalsteuer — the municipal employer payroll tax — as a
   kontor `PeriodTaxProvider` (ADR-099; research note 103). A flat 3 %
   on the monthly municipal wage sum (Kommunalsteuergesetz 1993).

   NB: `l10n-at`'s payroll adapter already INGESTS Kommunalsteuer from
   the BMD/RZL file as a wage type; this provider COMPUTES it from the
   wage base. Both paths are legitimate (note 102 §7-stress-4 — a
   standalone levy may be computed; social-insurance contributions
   stay engine-authoritative). They are separate concerns: this
   provider determines the liability as a `TaxReturnFacts`; it does
   not duplicate the payroll module's posting."
  (:require [kontor.standalone-payroll-tax :as spt]
            [kontor.tax-schedule :as ts]))

(def kommunalsteuer-rate
  "Kommunalsteuer — flat 3 % of the municipal wage sum."
  0.03M)

(defn at-kommunalsteuer-provider
  "An AT Kommunalsteuer `PeriodTaxProvider`. Config:
     :rate       — optional rate override (default 3 %)
     :wage-codes — chart account codes for payroll wage expense"
  [{:keys [rate wage-codes]}]
  (spt/standalone-payroll-tax-provider
   {:id         :at-kommunalsteuer
    :schedule   (ts/flat (or rate kommunalsteuer-rate))
    :wage-codes wage-codes
    :authority  :at-municipality
    :commodity  :EUR
    :statute    "Kommunalsteuergesetz 1993"
    :base-label "Bemessungsgrundlage (Lohnsumme)"}))

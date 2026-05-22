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
  (:require [kontor.corporate-income-tax :as cit]
            [kontor.personal-income-tax :as pit]
            [kontor.standalone-payroll-tax :as spt]
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

;; ============================================================================
;; Körperschaftsteuer — corporate income tax
;; ============================================================================

(def koest-rate
  "Körperschaftsteuer — flat 23 % (KStG 1988, from 2024)."
  0.23M)

(def koest-minimum-default
  "Mindest-KöSt — the minimum corporate tax, payable even at a loss.
   A GmbH pays €1,750/yr (€437.50 × 4); €3,500/yr after year 5; an AG
   €5,452/yr. Pass `:minimum-tax` to match the entity. Verify against
   current law — the figure has changed."
  1750M)

(defn at-corporate-income-tax-provider
  "AT corporate income tax — Körperschaftsteuer — provider. A flat
   23 % with the Mindest-KöSt floor (applied via `greater-of`).
   Config:
     :rate        — optional override (default 23 %)
     :minimum-tax — optional Mindest-KöSt override (default €1,750)"
  [{:keys [rate minimum-tax]}]
  (cit/corporate-income-tax-provider
   {:id          :at-koest
    :rate        (or rate koest-rate)
    :minimum-tax (or minimum-tax koest-minimum-default)
    :authority   :at-finanz
    :commodity   :EUR
    :statute     "Körperschaftsteuergesetz 1988"}))

;; ============================================================================
;; Einkommensteuer — personal income tax
;; ============================================================================

(def est-brackets
  "AT Einkommensteuer — §33 EStG progressive brackets, tax year 2024
   (after the cold-progression adjustment; verify against current
   law). The first band is the 0 % Existenzminimum."
  [{:rate 0M    :upper 12816M}
   {:rate 0.20M :upper 20818M}
   {:rate 0.30M :upper 34513M}
   {:rate 0.40M :upper 66612M}
   {:rate 0.48M :upper 99266M}
   {:rate 0.50M :upper 1000000M}
   {:rate 0.55M :upper nil}])

(defn at-income-tax-provider
  "AT personal income tax — Einkommensteuer — provider. A 7-band
   progressive schedule (§33 EStG). Absetzbeträge (tax credits) and
   Sonderausgaben / Werbungskosten (deductions) ride `context
   :inputs`."
  [_]
  (pit/personal-income-tax-provider
   {:id        :at-est
    :schedule  (ts/progressive est-brackets)
    :authority :at-finanz
    :commodity :EUR
    :statute   "§33 EStG"}))

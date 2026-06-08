(ns kontor.l10n-au.period-tax-provider
  "Australian period-tax providers — state payroll tax, and the legacy
   record-shape company-tax (CIT) + individual-income-tax (PIT)
   factories.

   ## DEPRECATION (v0.next)

   The `au-company-tax-provider` (company tax) and `au-income-tax-provider`
   (individual income tax + Medicare levy) factories below are now
   forwarders to the ADR-101 statute-as-data implementations in
   `kontor.l10n-au.cit-provider` / `kontor.l10n-au.pit-provider`.
   They preserve the v0.x consumer API; new consumers should call the
   new factories directly.
   Slated for removal in v0.next.

   The `au-payroll-tax-provider` is **unchanged** — state payroll tax
   is a standalone employer wage levy ( EXCLUDES payroll
   levies2). It stays on `StandalonePayrollTaxProvider`."
  (:require [kontor.l10n-au.cit-provider :as cit]
            [kontor.l10n-au.pit-provider :as pit]
            [kontor.tax.standalone-payroll-tax :as spt]
            [kontor.tax.tax-schedule :as ts]))

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
;; Company income tax (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded rate map. Reads come
  from `AU.InvIncome.corporate-rate.large` /
  `AU.InvIncome.corporate-rate.base-rate-entity` via
  `kontor.tax.statute/parameter-value-at`. Kept for documentation only;
  not consulted by the new provider."
       :deprecated "v0.next — use parameters AU.InvIncome.corporate-rate.*"}
  legacy-rates
  {:base-rate-entity 0.25M
   :standard         0.30M})

(defn ^:deprecated au-company-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-au.cit-provider/au-cit-provider`.

   Recommended replacement: `(kontor.l10n-au.cit-provider/au-cit-provider opts)`.
   The new provider reads §23 ITRA 1986 standard rate (30 %) + §23AA
   BRE rate (25 % from 2021-07-01; backfilled history 27.5 % at
   2017-07-01 + 26 % at 2020-07-01) from `:parameter` data; the BRE
   schedule swap rides `:op :schedule-override`. Eligibility flag
   continues to come from `:tax-unit :base-rate-entity?`.

   This thin forwarder ships for the v0.x public API contract; v0.x
   consumers continue to call the old name.

   The legacy `:base-rate-entity?` / `:rate` config keys are accepted
   but ignored — the new provider reads from `:parameter` data and
   uses `:tax-unit :base-rate-entity?` at evaluation time."
  [_opts]
  (cit/au-cit-provider {}))

;; ============================================================================
;; Individual income tax + Medicare levy (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded 2024-25 (Stage-3)
  brackets. Reads come from `AU.PIT.brackets` (year-keyed bracket
  scale; pre-Stage-3 + post-Stage-3 sets shipped) via
  `kontor.tax.statute/parameter-brackets-at`. Kept for documentation
  only; not consulted by the new provider."
       :deprecated "v0.next — use parameter AU.PIT.brackets"}
  legacy-stage-3-brackets
  [{:rate 0M    :upper 18200M}
   {:rate 0.16M :upper 45000M}
   {:rate 0.30M :upper 135000M}
   {:rate 0.37M :upper 190000M}
   {:rate 0.45M :upper nil}])

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded FY 2024-25 Medicare
  low-income threshold. Reads come from
  `AU.PIT.medicare-low-income-threshold` (annually-indexed; 5 yearly
  rows shipped). Kept for documentation only; not consulted by the
  new provider."
       :deprecated "v0.next — use parameter AU.PIT.medicare-low-income-threshold"}
  legacy-medicare-threshold
  27222M)

(defn ^:deprecated au-income-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-au.pit-provider/au-pit-provider`.

   Recommended replacement: `(kontor.l10n-au.pit-provider/au-pit-provider opts)`.
   The new provider reads ITRA 1986 Sch 7 progressive brackets from
   year-keyed `:parameter-bracket` rows (pre-Stage-3 + post-Stage-3
   sets) and folds Medicare Levy (with low-income shade-in) + LITO
   + franking + FITO + TFN-prepaid lanes via `:provision` data.

   This thin forwarder ships for the v0.x public API contract; v0.x
   consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` /
   `:parameter-bracket` data."
  [_opts]
  (pit/au-pit-provider {}))

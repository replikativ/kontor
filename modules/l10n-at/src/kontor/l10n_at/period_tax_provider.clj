(ns kontor.l10n-at.period-tax-provider
  "Austrian period-tax providers — Kommunalsteuer (KommSt), and the
   legacy record-shape Körperschaftsteuer (KöSt) + Einkommensteuer (ESt)
   factories.

   ## DEPRECATION (v0.next)

   The `at-corporate-income-tax-provider` (KöSt) and
   `at-income-tax-provider` (ESt) factories below are now forwarders
   to the ADR-101 statute-as-data implementations in
   `kontor.l10n-at.cit-provider` / `kontor.l10n-at.pit-provider`.
   They preserve the v0.x consumer API; new consumers should call the
   new factories directly.
   Slated for removal in v0.next.

   The `at-kommunalsteuer-provider` is **unchanged** — Kommunalsteuer
   is a standalone employer payroll tax ( EXCLUDES payroll
   levies2). It stays on `StandalonePayrollTaxProvider`.

   NB: `l10n-at`'s payroll adapter already INGESTS Kommunalsteuer from
   the AT payroll companion's wage-type ingest path; this provider
   COMPUTES it from the wage base. Both paths are legitimate — a
   standalone levy may be computed; social-insurance contributions
   stay engine-authoritative. They are separate concerns: this
   provider determines the liability as a `TaxReturnFacts`; it does
   not duplicate the payroll module's posting."
  (:require [kontor.l10n-at.cit-provider :as cit]
            [kontor.l10n-at.pit-provider :as pit]
            [kontor.tax.standalone-payroll-tax :as spt]
            [kontor.tax.tax-schedule :as ts]))

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
    :statute    "Kommunalsteuergesetz 1993"}))

;; ============================================================================
;; Körperschaftsteuer — corporate income tax (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded rate. Read
  `AT.KStG.cit-rate` from the substrate via
  `kontor.tax.statute/parameter-value-at` for the right `as-of`. Kept
  for documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter AT.KStG.cit-rate"}
  koest-rate
  0.23M)

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded Mindest-KöSt default.
  STALE — the post-2024 GmbH figure is €500 (per Startup-
  Förderungsgesetz 2023), not €1 750. The new provider reads the
  correct value from parameter
  `AT.KStG.§24.mindest-koest-gmbh-amount` (or
  `…ag-amount`) and selects via `:tax-unit :entity-kind`. Kept for
  documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter AT.KStG.§24.mindest-koest-gmbh-amount"}
  koest-minimum-default
  1750M)

(defn ^:deprecated at-corporate-income-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-at.cit-provider/at-cit-provider`.

   Recommended replacement: `(kontor.l10n-at.cit-provider/at-cit-provider opts)`.
   The new provider reads §22 KStG rate + §24 Mindest-KöSt amount from
   `:parameter` data (with bitemporal history — the legacy €1 750 GmbH
   floor was reduced to €500 by the Startup-Förderungsgesetz effective
   2024-01-01); the Mindest-KöSt floor rides
   `kontor.tax.statute/compose-greater-of` for audit-trace.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (cit/at-cit-provider {}))

;; ============================================================================
;; Einkommensteuer — personal income tax (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded 2024 brackets. Read
  the year-keyed bracket scale from
  `AT.EStG.§33-Abs-1.brackets` via
  `kontor.tax.statute/parameter-brackets-at` for the right `as-of`.
  Kept for documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter AT.EStG.§33-Abs-1.brackets"}
  est-brackets
  [{:rate 0M    :upper 12816M}
   {:rate 0.20M :upper 20818M}
   {:rate 0.30M :upper 34513M}
   {:rate 0.40M :upper 66612M}
   {:rate 0.48M :upper 99266M}
   {:rate 0.50M :upper 1000000M}
   {:rate 0.55M :upper nil}])

(defn ^:deprecated at-income-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-at.pit-provider/at-pit-provider`.

   Recommended replacement: `(kontor.l10n-at.pit-provider/at-pit-provider opts)`.
   The new provider reads §33 Abs 1 EStG progressive brackets from
   year-keyed `:parameter-bracket` rows (2022-2026 Kalte-Progression
   history) and folds Familienbonus / Alleinverdiener / Verkehrsabsetz /
   Kindermehrbetrag / Regelbesteuerung lanes via `:provision` data.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (pit/at-pit-provider {}))

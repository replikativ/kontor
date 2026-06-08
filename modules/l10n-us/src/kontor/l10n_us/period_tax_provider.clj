(ns kontor.l10n-us.period-tax-provider
  "US federal income tax — Form 1120 (corporate) + Form 1040 (personal).

   ## DEPRECATION (v0.next)

   The `us-corporate-income-tax-provider` (1120) and
   `us-personal-income-tax-provider` (1040) factories below are now
   forwarders to the ADR-101 statute-as-data implementations in
   `kontor.l10n-us.cit-provider` / `kontor.l10n-us.pit-provider`.
   They preserve the v0.x consumer API; new consumers should call the
   new factories directly.
   Slated for removal in v0.next.

   State corporate / personal income tax (the 50-state patchwork) and
   the 15 % corporate AMT (CAMT) remain OUT of substrate per ADR-005 /
   ADR-010 /. The CA federal+provincial
   pattern (ADR-107) is the structural template if/when state CIT/PIT
   is re-opened (N-component fan-out via `:tax-unit :state-allocation`)."
  (:require [kontor.l10n-us.cit-provider :as cit]
            [kontor.l10n-us.pit-provider :as pit]))

;; ============================================================================
;; Form 1120 — federal corporate income tax (DEPRECATED record-shape entry)
;; ============================================================================

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded rate. Read
  `US.CIT.§11.rate` from the substrate via
  `kontor.tax.statute/parameter-value-at` for the right `as-of`. Kept
  for documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter US.CIT.§11.rate"}
  federal-rate
  0.21M)

(defn ^:deprecated us-corporate-income-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-us.cit-provider/us-cit-provider`.

   Recommended replacement: `(kontor.l10n-us.cit-provider/us-cit-provider opts)`.
   The new provider reads §11 rate from `:parameter` data (with
   bitemporal history) and folds CGT corp-net + optional §172 NOL /
   §163(j) / §250 stubs via `:provision` data.

   This thin forwarder ships for the v0.x public API contract; v0.x
   consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (cit/us-cit-provider {}))

;; ============================================================================
;; Form 1040 — federal personal income tax (DEPRECATED record-shape entry)
;; ============================================================================
;;
;; The legacy record-shape provider exposed several internal symbols
;; (`ordinary-rates` / `filing-status-brackets-2024` /
;; `standard-deduction-2024` / `filing-statuses`) for documentation +
;; the period-tax-provider-test's introspection. The new substrate
;; carries all four equivalents as `:parameter` / `:parameter-bracket`
;; data installed by `kontor.l10n-us.pit-statute/install!`. The legacy
;; symbols are kept ONLY as `^:deprecated` documentation constants
;; (TY 2024 snapshot) and are NOT consulted by the new provider —
;; v0.x consumers reading them get the same TY 2024 values they always
;; did; new consumers should query the substrate via
;; `kontor.tax.statute/parameter-brackets-at` /
;; `kontor.tax.statute/parameter-value-at`.

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded ordinary-income rates.
  IRC §1(j) rates are stable post-TCJA; read individual bracket rows
  from `US.PIT.§1.brackets-<status>` via
  `kontor.tax.statute/parameter-brackets-at` for the right `as-of`."
       :deprecated "v0.next — use parameter US.PIT.§1.brackets-<status>"}
  ordinary-rates
  [0.10M 0.12M 0.22M 0.24M 0.32M 0.35M 0.37M])

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded TY 2024 brackets per
  filing status. Read year-keyed bracket scales from
  `US.PIT.§1.brackets-<status>` via
  `kontor.tax.statute/parameter-brackets-at` for the right `as-of`."
       :deprecated "v0.next — use parameter US.PIT.§1.brackets-<status>"}
  filing-status-brackets-2024
  {:single
   [{:rate 0.10M :upper 11600M}
    {:rate 0.12M :upper 47150M}
    {:rate 0.22M :upper 100525M}
    {:rate 0.24M :upper 191950M}
    {:rate 0.32M :upper 243725M}
    {:rate 0.35M :upper 609350M}
    {:rate 0.37M :upper nil}]
   :married-filing-jointly
   [{:rate 0.10M :upper 23200M}
    {:rate 0.12M :upper 94300M}
    {:rate 0.22M :upper 201050M}
    {:rate 0.24M :upper 383900M}
    {:rate 0.32M :upper 487450M}
    {:rate 0.35M :upper 731200M}
    {:rate 0.37M :upper nil}]
   :married-filing-separately
   [{:rate 0.10M :upper 11600M}
    {:rate 0.12M :upper 47150M}
    {:rate 0.22M :upper 100525M}
    {:rate 0.24M :upper 191950M}
    {:rate 0.32M :upper 243725M}
    {:rate 0.35M :upper 365600M}
    {:rate 0.37M :upper nil}]
   :head-of-household
   [{:rate 0.10M :upper 16550M}
    {:rate 0.12M :upper 63100M}
    {:rate 0.22M :upper 100500M}
    {:rate 0.24M :upper 191950M}
    {:rate 0.32M :upper 243700M}
    {:rate 0.35M :upper 609350M}
    {:rate 0.37M :upper nil}]})

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded TY 2024 std deduction
  per filing status. Read via
  `kontor.tax.statute/parameter-value-at` of
  `US.PIT.§63.standard-deduction-<status>`."
       :deprecated "v0.next — use parameter US.PIT.§63.standard-deduction-<status>"}
  standard-deduction-2024
  {:single                    14600M
   :married-filing-jointly    29200M
   :married-filing-separately 14600M
   :head-of-household         21900M})

(def ^{:doc "DEPRECATED — pre-ADR-101 closed set of long-form filing-
  status keywords used by the legacy provider's `:tax-unit
  :filing-status`. The new provider uses the short-name set
  `kontor.l10n-us.pit-provider/filing-statuses` (`#{:single :mfj :mfs :hoh}`)."
       :deprecated "v0.next — use kontor.l10n-us.pit-provider/filing-statuses"}
  filing-statuses
  #{:single :married-filing-jointly :married-filing-separately :head-of-household})

(defn ^:deprecated us-personal-income-tax-provider
  "DEPRECATED — forwards to `kontor.l10n-us.pit-provider/us-pit-provider`.

   Recommended replacement: `(kontor.l10n-us.pit-provider/us-pit-provider opts)`.
   The new provider reads §1(j) progressive brackets from year-keyed
   `:parameter-bracket` rows (4 statuses × 7 bands × TY 2020-2025) +
   §63 std deduction per filing status + §24 CTC / ACTC + the CGT /
   investment-income lane folds via `:provision` data.

   This thin forwarder ships for the v0.x public API contract; v0.x
   consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (pit/us-pit-provider {}))

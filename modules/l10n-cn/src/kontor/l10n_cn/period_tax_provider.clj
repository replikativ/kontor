(ns kontor.l10n-cn.period-tax-provider
  "Chinese period taxes — the legacy record-shape Enterprise Income Tax
   (EIT) + Individual Income Tax (IIT) factories.

   ## DEPRECATION (v0.next)

   The `cn-eit-provider` (EIT) and `cn-iit-provider` (IIT) factories
   below are now forwarders to the ADR-101 statute-as-data
   implementations in `kontor.l10n-cn.cit-provider` /
   `kontor.l10n-cn.pit-provider`.
   They preserve the v0.x consumer API; new consumers should call the
   new factories directly. Slated for removal in v0.next.

   The legacy record-shape providers (record-shape
   `corporate-income-tax-provider` / `personal-income-tax-provider`)
   were record/protocol implementations carrying hard-coded rates and
   brackets. The new providers read everything from substrate
   `:parameter` / `:provision` / `:parameter-bracket` data — supporting
   the SLPE / HNTE / regional 15 % overrides, R&D super-deductions,
   bitemporal rate history, and the annual-reconciliation `:prepaid`
   lane through one substrate-consistent API."
  (:require [kontor.l10n-cn.cit-provider :as cit]
            [kontor.l10n-cn.pit-provider :as pit]))

;; ============================================================================
;; Legacy compatibility — record-shape constants (DEPRECATED)
;; ============================================================================
;;
;; These defs documented the hard-coded values the legacy record-shape
;; provider carried. The new ADR-101 providers read these from
;; substrate data:
;; - eit-standard-rate → CN.EIT.standard-rate (cgt-statute)
;; - eit-hnte-rate     → CN.EIT.hnte-rate (cit-statute)
;; - iit-basic-deduction → CN.IIT.basic-deduction (pit-statute)
;; - iit-comprehensive-income-brackets → CN.IIT.comprehensive-income.brackets
;;
;; Kept for documentation only — not consulted by the new providers.

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded rate. Read
  `CN.EIT.standard-rate` from the substrate via
  `kontor.tax.statute/parameter-value-at` for the right `as-of`. Kept
  for documentation only; not consulted by the new provider."
       :deprecated "v0.next — use parameter CN.EIT.standard-rate"}
  eit-standard-rate
  0.25M)

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded HNTE rate. Read
  `CN.EIT.hnte-rate` from the substrate via
  `kontor.tax.statute/parameter-value-at`."
       :deprecated "v0.next — use parameter CN.EIT.hnte-rate"}
  eit-hnte-rate
  0.15M)

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded basic deduction. Read
  `CN.IIT.basic-deduction` from the substrate via
  `kontor.tax.statute/parameter-value-at`."
       :deprecated "v0.next — use parameter CN.IIT.basic-deduction"}
  iit-basic-deduction
  60000M)

(def ^{:doc "DEPRECATED — pre-ADR-101 hard-coded brackets. Read
  `CN.IIT.comprehensive-income.brackets` from the substrate via
  `kontor.tax.statute/parameter-brackets-at`."
       :deprecated "v0.next — use parameter CN.IIT.comprehensive-income.brackets"}
  iit-comprehensive-income-brackets
  [{:rate 0.03M :upper 36000M}
   {:rate 0.10M :upper 144000M}
   {:rate 0.20M :upper 300000M}
   {:rate 0.25M :upper 420000M}
   {:rate 0.30M :upper 660000M}
   {:rate 0.35M :upper 960000M}
   {:rate 0.45M :upper nil}])

;; ============================================================================
;; 企业所得税 — Enterprise Income Tax (DEPRECATED record-shape entry)
;; ============================================================================

(defn ^:deprecated cn-eit-provider
  "DEPRECATED — forwards to `kontor.l10n-cn.cit-provider/cn-cit-provider`.

   Recommended replacement: `(kontor.l10n-cn.cit-provider/cn-cit-provider opts)`.
   The new provider reads §4 ¶1 standard rate + §28 ¶2 HNTE rate +
   Cai Shui [2023] 12 SLPE effective rate from `:parameter` data
   (with bitemporal history), and supports the SLPE cliff
   (taxable-income ≤ ¥3M) via the substrate's two-pass query pattern.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data.
   Consumers wanting HNTE / SLPE preferential rates now supply
   `:tax-unit :hnte? true` / `:tax-unit :slpe? true` to the
   `period-tax-facts` call (not via the factory)."
  [_opts]
  (cit/cn-cit-provider {}))

;; ============================================================================
;; 个人所得税 — Individual Income Tax (DEPRECATED record-shape entry)
;; ============================================================================

(defn ^:deprecated cn-iit-provider
  "DEPRECATED — forwards to `kontor.l10n-cn.pit-provider/cn-pit-provider`.

   Recommended replacement: `(kontor.l10n-cn.pit-provider/cn-pit-provider opts)`.
   The new provider reads §3 ¶1 + §6 IIT Law brackets and basic
   deduction from `:parameter-bracket` / `:parameter` data, and
   surfaces the seven special-additional deductions + statutory
   contributions as `:provision`-tracked base-deducts. Business-
   income filers swap to the §3 ¶2 5-band schedule via
   `:tax-unit :business-income? true`.

   Per Q5.2 this thin forwarder ships for the v0.x
   public API contract; v0.x consumers continue to call the old name.

   Config: ignored — the new provider reads from `:parameter` data."
  [_opts]
  (pit/cn-pit-provider {}))

;; ============================================================================
;; Legacy helper: iit-comprehensive-base-transform (DEPRECATED)
;; ============================================================================
;;
;; The legacy `personal-income-tax-provider` consumed a
;; `:base-transform` map on `:inputs`. The new
;; `kontor.l10n-cn.pit-provider` reads consumer-supplied special-
;; additional deductions as individual `:inputs :pit-base-deductions-*`
;; values (each surfacing as a `:provision`-tracked base-deduct for
;; audit). The helper is kept for documentation only.

(defn ^:deprecated iit-comprehensive-base-transform
  "DEPRECATED — the new `kontor.l10n-cn.pit-provider` reads consumer-
   supplied special-additional deductions per category via
   `:inputs :pit-base-deductions-children-education`, etc., each
   surfacing as its own `:provision`-tracked base-deduct. Kept for
   documentation only; the new provider does not consume the
   `:base-transform` shape this fn produced.

   Recommended replacement: pass individual category amounts via
   `:inputs :pit-base-deductions-<category>` keys to the new
   `cn-pit-provider`."
  {:deprecated "v0.next — supply :inputs :pit-base-deductions-* per category"}
  [special-additional-deductions]
  {:transform/type :adjustments
   :additions      []
   :deductions     (into [iit-basic-deduction]
                         (map bigdec special-additional-deductions))})

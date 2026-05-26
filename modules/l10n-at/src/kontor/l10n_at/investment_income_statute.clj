(ns kontor.l10n-at.investment-income-statute
  "AT investment-income tax — KESt on dividends (§27a Abs 1 Z 2 EStG —
   27.5 %) + bank interest (§27a Abs 1 Z 1 EStG — 25 %) + §10 KStG
   corporate participation exemption — encoded as `kontor.statute`
   data per ADR-101. Research note 154.

   THE KEY INSIGHT (note 154 §3.2): every KESt rate this provider needs
   ALREADY EXISTS in `cgt-statute.clj`:

     - `AT.EStG.§27a.kest-financial-rate` (27.5 %) — same rate AT KESt
       uses for dividends as for capital gains on financial assets.
     - `AT.EStG.§27a.kest-interest-rate` (25 %) — bank-deposit interest.
     - `AT.KStG.§10.qualifying-ownership-fraction` (10 %)
     - `AT.KStG.§10.qualifying-holding-days` (365)
     - `AT.KStG.cit-rate` (23 % from 2024)

   This namespace adds ONLY what's unique to investment-income:

     - `AT.KStG.§10-Abs-4.low-tax-threshold` — the foreign effective-tax-
       rate threshold below which the §10 Abs 4 switch-over fires.
       12.5 % from 2014-01-01 (AbgÄG 2014 introduction);
       15 % from 2026-01-01 (Mindestbesteuerungsgesetz / Pillar Two
       alignment). Bitemporal cliff handled by parameter-value history.

   No new provisions — the §10 Abs 4 switch-over is provider-internal:
   the provider compares the consumer-attested foreign-corp ETR against
   the parameter and inverts the §10 default-exempt classification. The
   2026 cliff (12.5 % → 15 %) is driven by `parameter-value-at` at the
   consumer-supplied `:as-of` (defaults to `period :to`); no ADR-101
   provision needed.

   ASSUMES the AT CGT statute has already been installed (KESt rates +
   §10 thresholds + CIT rate live there)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "AT investment-income parameter definitions. All KESt rates + §10
   stake/holding thresholds + CIT rate are owned by `cgt-statute`; this
   file adds only the §10 Abs 4 low-tax threshold that gates the
   switch-over to taxable for foreign-source participation income."
  [{:kontor.parameter/code         "AT.KStG.§10-Abs-4.low-tax-threshold"
    :kontor.parameter/label        "§10 Abs 4 KStG — Switch-over effective-tax-rate threshold for foreign portfolio / Schachtel income (12.5 % pre-2026 → 15 % from 2026 per Mindestbesteuerungsgesetz)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/kstg/paragraf/10"}])

;; ============================================================================
;; Parameter values — date-keyed value history
;; ============================================================================

(def parameter-values
  "AT investment-income parameter values with their statutory effective
   windows. The §10 Abs 4 low-tax threshold steps up from 12.5 % to 15 %
   on 2026-01-01 per the Mindestbesteuerungsgesetz (BGBl I 2023/187),
   aligning AT's switch-over trigger with the OECD Pillar Two GloBE
   15 % effective minimum. Bitemporal cliff — `parameter-value-at`
   selects the rate effective at the asked `:as-of` instant."
  [{:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.KStG.§10-Abs-4.low-tax-threshold"]
    :kontor.parameter-value/effective-from  #inst "2014-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   0.125M
    :kontor.parameter-value/citation        "§10 Abs 4 KStG (AbgÄG 2014, BGBl I 2014/13) — 12.5 % low-tax threshold for switch-over to taxable on foreign-portfolio and Schachtel income (2014-01-01 to 2025-12-31)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.KStG.§10-Abs-4.low-tax-threshold"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "§10 Abs 4 KStG (Mindestbesteuerungsgesetz, BGBl I 2023/187) — 15 % low-tax threshold ab 2026-01-01 (Pillar Two / GloBE alignment)"}])

;; ============================================================================
;; Provisions — empty for v1
;; ============================================================================

(def provisions
  "AT investment-income statutory provisions. Empty for v1 — every AT
   investment-income mechanism is provider-internal:

   - KESt 27.5 % dividends / 25 % bank interest: parameter-driven
     `ts/flat` schedule (rates owned by `cgt-statute`).
   - Endbesteuerungswirkung (§97 EStG): provider emits a near-zero
     liability when bank-withheld KESt matches gross-due.
   - Regelbesteuerungsoption (§27a Abs 5 EStG): when
     `:tax-unit :regelbesteuerung-elected?` is true, provider folds
     gross dividends + interest via `:pit-base-additions` and the
     prepaid KESt via `:pit-credits {:refundable? true}` so the
     downstream AT PIT provider sweeps both.
   - §10 KStG INVERSION: default exempt for qualifying participation;
     opt-in to taxable via `:elective-regime :at-§10-tax-effective-option`.
     Provider checks the elective regime first, then routes to
     `:cit-base-deductions`.
   - §10 Abs 4 switch-over: provider compares consumer-attested foreign-
     corp ETR (`:tax-unit :at-foreign-corp-etr` OR
     `:tax-unit :at-low-tax-jurisdiction?`) against the bitemporal
     threshold parameter; switch-over fully taxes the dividend in CIT.
   - §10 foreign-corp guard (per AT CGT P0-2, note 146 §3.2): when
     `:tax-unit :held-entity-domestic? true`, the §10 default-exempt
     INVERSION does NOT fire for dividends from foreign-portfolio /
     Schachtel classification — gain stays in CIT base as ordinary
     income. (Domestic dividends remain exempt under §10 Abs 1 Z 1.)
   - BFG 2024 DBA cap: when §10 exempts a foreign dividend, the
     DBA-Quellensteuer credit cap is ZERO — provider emits no credit
     in that branch (audit-doc records the lost recovery opportunity)."
  [])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install AT investment-income statute (parameters + provisions) into
   `conn`. Idempotent via `:kontor.parameter/code` + `:kontor.provision/code` unique
   identity attrs.

   ASSUMES the AT CGT statute has already been installed — this
   statute references KESt rates (27.5 % / 25 %) + §10 thresholds
   + CIT rate parameters by code that live in `cgt-statute`."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions) (d/transact conn provisions)))

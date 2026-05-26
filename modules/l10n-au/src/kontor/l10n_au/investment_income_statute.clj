(ns kontor.l10n-au.investment-income-statute
  "AU investment-income tax — imputation (franking) system + foreign-
   source dividends (FITO) + interest income (TFN withholding) —
   encoded as `kontor.statute` data per ADR-101. Research note 153.

   The AU substrate is structurally distinctive: a resident shareholder
   receiving a franked dividend GROSSES UP the cash dividend by an
   imputation credit (`cash × rate / (1 − rate)`) and applies that
   credit against their own tax liability — refundable for resident
   individuals and complying super funds, lost for non-fixed trusts
   and foreign residents. The franking-credit mechanic itself lives
   in the provider (per-event compute over consumer-supplied dividend
   facts); the statute carries the moving numerical parameters:

   - **Corporate rates** (s.23) — 30 % large / 25 % base rate entity.
     Used as the `rate` in `franking-credit = cash × rate / (1 − rate)`.
   - **45-day holding-period cutoff** (former Pt IIIAA Div 1A,
     s.160APHO ITAA 1936 — preserved as savings provisions).
   - **Small-shareholder exemption threshold** (s.160APHT ITAA 1936) —
     $5,000 annual franking-credit cap below which the 45-day rule
     does not apply (individuals only).
   - **TFN withholding rate** (Pt VA ITAA 1936) — 47 % when no TFN
     supplied to the financial institution.
   - **Non-resident interest WHT rate** (s.128B) — 10 %.

   No `:provisions` ship — the franking-credit + FITO + TFN flows are
   provider-internal logic per the AU CGT precedent (note 129 §5):
   the imputation cascade is a per-holder-class ordered sequence whose
   semantics don't fit the ADR-101 closed `:op` vocabulary
   (`:base-add` / `:base-deduct` / `:credit` / `:surtax` /
   `:schedule-override`).

   Bitemporal-safe — any future rate change (corporate-rate band shift,
   2027 retiree-tax-grab reversal that was rejected, etc.) is a single
   `:parameter-value` row addition.

   Citations point at the Federal Register of Legislation
   (legislation.gov.au) for ITAA 1936 / 1997 + ATO for guidance."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "AU investment-income parameter definitions. Values live in
   `parameter-values`, keyed by `:effective-from`."
  [;; --- Corporate rates (drive franking-credit gross-up formula) -----------
   {:kontor.parameter/code         "AU.InvIncome.corporate-rate.large"
    :kontor.parameter/label        "Corporate tax rate — large companies (≥ $50M aggregated turnover)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C2004A02904/latest/text"}

   {:kontor.parameter/code         "AU.InvIncome.corporate-rate.base-rate-entity"
    :kontor.parameter/label        "Corporate tax rate — base rate entity (BRE) (< $50M aggregated turnover, ≤ 80% passive)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C2004A02904/latest/text"}

   ;; --- 45-day holding-period rule (ITAA 1936 former Pt IIIAA Div 1A) ------
   {:kontor.parameter/code         "AU.InvIncome.holding-period-days"
    :kontor.parameter/label        "At-risk holding-period — s.160APHO ITAA 1936 (45 days for ordinary shares)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :days
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C1936A00027/latest/text"}

   ;; --- Small-shareholder exemption (s.160APHT ITAA 1936) ------------------
   {:kontor.parameter/code         "AU.InvIncome.small-shareholder-exemption"
    :kontor.parameter/label        "Small shareholder exemption — s.160APHT ITAA 1936 ($5,000 annual franking-credit cap)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C1936A00027/latest/text"}

   ;; --- TFN withholding rate (Pt VA ITAA 1936) -----------------------------
   {:kontor.parameter/code         "AU.InvIncome.tfn-withholding-rate"
    :kontor.parameter/label        "TFN withholding rate — Pt VA ITAA 1936 (top marginal, no-TFN penalty)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C1936A00027/latest/text"}

   ;; --- Non-resident interest WHT (s.128B ITAA 1936) -----------------------
   {:kontor.parameter/code         "AU.InvIncome.non-resident-interest-wht-rate"
    :kontor.parameter/label        "Non-resident interest withholding tax — s.128B ITAA 1936"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/C1936A00027/latest/text"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "AU investment-income parameter values. Sources:
     - s.23 ITAA 1936 / s.4-10 ITAA 1997 — corporate rate 30 % (stable
       since 2001-07-01 for large; 25 % BRE effective 2021-07-22 via
       Treasury Laws Amendment (Enterprise Tax Plan Base Rate Entities)
       Act 2018).
     - s.160APHO ITAA 1936 — 45 days (preserved as savings provision).
     - s.160APHT — $5,000 cap, frozen since introduction (Taxation Laws
       Amendment Act (No. 2) 1999).
     - Pt VA — 47 % since 2017-07-01 (tracks top marginal — was 49 % in
       the Temporary Budget Repair Levy years 2014-2017).
     - s.128B — 10 % interest WHT, stable; some treaty rates lower."
  [;; Corporate rates.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.corporate-rate.large"]
    :kontor.parameter-value/effective-from #inst "2001-07-01"
    :kontor.parameter-value/decimal-value  0.30M
    :kontor.parameter-value/citation       "s.23(2) ITAA 1936 — 30 % large-company rate since 2001-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.corporate-rate.base-rate-entity"]
    :kontor.parameter-value/effective-from #inst "2021-07-22"
    :kontor.parameter-value/decimal-value  0.25M
    :kontor.parameter-value/citation       "s.23 ITAA 1936 — 25 % BRE since 2021-07-22 (Treasury Laws Amendment (Enterprise Tax Plan Base Rate Entities) Act 2018)"}

   ;; Holding period.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.holding-period-days"]
    :kontor.parameter-value/effective-from #inst "1999-07-01"
    :kontor.parameter-value/decimal-value  45M
    :kontor.parameter-value/citation       "s.160APHO ITAA 1936 — 45-day at-risk holding period for ordinary shares (90 days preference; preserved as savings provision when Pt IIIAA repealed)"}

   ;; Small-shareholder exemption.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.small-shareholder-exemption"]
    :kontor.parameter-value/effective-from #inst "1999-07-01"
    :kontor.parameter-value/decimal-value  5000M
    :kontor.parameter-value/citation       "s.160APHT ITAA 1936 — $5,000 annual franking-credit cap below which the 45-day rule does not apply (individuals only)"}

   ;; TFN withholding (47 % since temporary-levy repeal).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.tfn-withholding-rate"]
    :kontor.parameter-value/effective-from #inst "2017-07-01"
    :kontor.parameter-value/decimal-value  0.47M
    :kontor.parameter-value/citation       "Pt VA ITAA 1936 + Tax Administration Regulations — 47 % top marginal rate; no-TFN-supplied penalty (refundable prepayment, not final tax)"}

   ;; Non-resident interest WHT.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.InvIncome.non-resident-interest-wht-rate"]
    :kontor.parameter-value/effective-from #inst "1973-09-26"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "s.128B(5A) ITAA 1936 — 10 % non-resident interest WHT (final tax; treaty rates may differ)"}])

;; ============================================================================
;; Provisions — none (v1)
;; ============================================================================

(def provisions
  "AU investment-income provisions — empty for v1.

   The imputation (franking-credit) cascade, the FITO (foreign income
   tax offset), and the TFN withholding mechanics are all PROVIDER-
   INTERNAL LOGIC per the AU CGT precedent (research note 129 §5 +
   note 153 §5). Encoding the franking gross-up as an ADR-101
   provision would force per-holder-class refundability semantics into
   the `:credit :refundable?` slot, which does NOT support the
   trust-passthrough / non-fixed-trust-trapped distinctions cleanly.

   The provider emits the credits with the correct `:refundable?` flag
   per holder class so a downstream PIT/CIT provider can fold them
   through `apply-adjustments` if desired."
  [])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install AU investment-income statute (parameters + values) into
   `conn`. Idempotent — `:kontor.parameter/code` is a unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))

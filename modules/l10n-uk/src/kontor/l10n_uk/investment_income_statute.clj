(ns kontor.l10n-uk.investment-income-statute
  "UK investment-income tax — dividends + savings interest — encoded
   as `kontor.statute` data per ADR-101. Research note 150.

   THE KEY MECHANIC (note 150 §1.4): ITA 2007 §16 stacks income in a
   strict order — non-savings → savings → dividends — and each layer's
   zero-rate band depends on the prior layer's consumption. The
   ordering math lives in the provider (`uk-income-tax-allocation`,
   pure function); this namespace ships the parameters + a single
   bitemporal provision marking the April-2026 dividend-rate hike.

   The dividend basic + higher rates change on 2026-04-06:

     - 2024-04-06 → 2026-04-06: basic 8.75 %, higher 33.75 %
     - 2026-04-06 →           : basic 10.75 %, higher 35.75 %
     - Additional rate UNCHANGED throughout at 39.35 %.

   Savings rates always track non-savings (20 / 40 / 45 %) — sourced
   per band from the standard income-tax rate parameters.

   ## Surfaces this statute does NOT encode (provider-internal)

   - The §16 ordering algorithm — PA cascade, SRS taper, PSA band
     selection, dividend allowance application. Procedural; lives in
     `uk-income-tax-allocation`.
   - ISA-wrapped income — the consumer routes ISA postings to wrapped-
     tag accounts; the provider filters them at compute time
     (`:uk-investment-income/dividend-uk-isa-wrapped`,
     `/savings-uk-isa-wrapped`).
   - CTA 2009 Part 9A corporate dividend exemption — the consumer
     asserts via account tag (`:uk-investment-income/corp-dividend-
     exempt` vs `/corp-dividend-taxable`); the provider trusts the
     tag (same discipline as UK CGT trusting `:kontor.disposal/exemption-
     claimed :uk-sse`).

   Citations point at legislation.gov.uk / gov.uk."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "UK investment-income parameter definitions. Values live in
   `parameter-values`, keyed by `:effective-from`."
  [;; --- Personal Allowance (PA) — global income allowance, frozen ----------
   {:kontor.parameter/code         "UK.IIT.PA"
    :kontor.parameter/label        "Personal Allowance (global income allowance)"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates/personal-allowances"}

   ;; --- Starting Rate for Savings (SRS) — ITA 2007 §12 ----------------------
   {:kontor.parameter/code         "UK.IIT.SRS"
    :kontor.parameter/label        "Starting rate band for savings (zero-rate)"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/2007/3/section/12"}

   ;; --- Personal Savings Allowance (PSA) — FA 2016 §4 ----------------------
   {:kontor.parameter/code         "UK.IIT.PSA-basic"
    :kontor.parameter/label        "Personal Savings Allowance — basic-rate band"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/2016/24/section/4"}
   {:kontor.parameter/code         "UK.IIT.PSA-higher"
    :kontor.parameter/label        "Personal Savings Allowance — higher-rate band"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/2016/24/section/4"}

   ;; --- Dividend allowance — ITA 2007 §13A + Finance Acts ------------------
   {:kontor.parameter/code         "UK.IIT.dividend-allowance"
    :kontor.parameter/label        "Dividend allowance (dividend nil-rate band)"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/2007/3/section/13A"}

   ;; --- Standard income-tax bands ------------------------------------------
   {:kontor.parameter/code         "UK.IIT.basic-band"
    :kontor.parameter/label        "Basic-rate band width above PA"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates"}
   {:kontor.parameter/code         "UK.IIT.additional-threshold"
    :kontor.parameter/label        "Additional-rate threshold (total taxable income)"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates"}

   ;; --- Standard income-tax rates (apply to non-savings + savings) ---------
   {:kontor.parameter/code         "UK.IIT.basic-rate"
    :kontor.parameter/label        "Income tax — basic rate"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates"}
   {:kontor.parameter/code         "UK.IIT.higher-rate"
    :kontor.parameter/label        "Income tax — higher rate"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates"}
   {:kontor.parameter/code         "UK.IIT.additional-rate"
    :kontor.parameter/label        "Income tax — additional rate"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/income-tax-rates"}

   ;; --- Dividend rates — bitemporal: April 2026 hike -----------------------
   {:kontor.parameter/code         "UK.IIT.dividend.basic-rate"
    :kontor.parameter/label        "Dividend tax — basic-rate band"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/tax-on-dividends"}
   {:kontor.parameter/code         "UK.IIT.dividend.higher-rate"
    :kontor.parameter/label        "Dividend tax — higher-rate band"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/tax-on-dividends"}
   {:kontor.parameter/code         "UK.IIT.dividend.additional-rate"
    :kontor.parameter/label        "Dividend tax — additional-rate band"
    :kontor.parameter/jurisdiction :uk
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.gov.uk/tax-on-dividends"}])

;; ============================================================================
;; Parameter values — current rates with statutory effective windows
;; ============================================================================

(def parameter-values
  "UK investment-income parameter values. Current = TY 2024/25 and
   2025/26 (frozen / unchanged for most slots); the dividend rates
   carry a SECOND row effective 2026-04-06 per Autumn Budget 2025."
  [;; --- Personal Allowance — frozen at £12,570 through 2028 ---------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.PA"]
    :kontor.parameter-value/effective-from #inst "2021-04-06"
    :kontor.parameter-value/decimal-value  12570M
    :kontor.parameter-value/citation       "FA 2021 — PA £12,570; frozen through 2028 by Sunak-era policy"}

   ;; --- Starting Rate for Savings — £5,000 since 2015-16 ------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.SRS"]
    :kontor.parameter-value/effective-from #inst "2015-04-06"
    :kontor.parameter-value/decimal-value  5000M
    :kontor.parameter-value/citation       "ITA 2007 §12 + FA 2015 — SRS band £5,000"}

   ;; --- Personal Savings Allowance — FA 2016 ------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.PSA-basic"]
    :kontor.parameter-value/effective-from #inst "2016-04-06"
    :kontor.parameter-value/decimal-value  1000M
    :kontor.parameter-value/citation       "FA 2016 — PSA £1,000 (basic-rate taxpayer)"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.PSA-higher"]
    :kontor.parameter-value/effective-from #inst "2016-04-06"
    :kontor.parameter-value/decimal-value  500M
    :kontor.parameter-value/citation       "FA 2016 — PSA £500 (higher-rate taxpayer); additional-rate PSA = £0"}

   ;; --- Dividend allowance — slashed £1,000 → £500 in 2024/25 -------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend-allowance"]
    :kontor.parameter-value/effective-from #inst "2023-04-06"
    :kontor.parameter-value/effective-until #inst "2024-04-06"
    :kontor.parameter-value/decimal-value  1000M
    :kontor.parameter-value/citation       "Autumn Statement 2022 — dividend allowance £2,000 → £1,000 from 2023/24"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend-allowance"]
    :kontor.parameter-value/effective-from #inst "2024-04-06"
    :kontor.parameter-value/decimal-value  500M
    :kontor.parameter-value/citation       "Autumn Statement 2022 — dividend allowance further reduced to £500 from 2024/25"}

   ;; --- Basic-rate band width — £37,700 frozen ----------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.basic-band"]
    :kontor.parameter-value/effective-from #inst "2021-04-06"
    :kontor.parameter-value/decimal-value  37700M
    :kontor.parameter-value/citation       "FA 2021 — basic-rate band £37,700; frozen alongside PA"}

   ;; --- Additional-rate threshold — lowered £150k → £125,140 in 2023 ------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.additional-threshold"]
    :kontor.parameter-value/effective-from #inst "2023-04-06"
    :kontor.parameter-value/decimal-value  125140M
    :kontor.parameter-value/citation       "FA 2023 — additional-rate threshold £150,000 → £125,140"}

   ;; --- Standard income-tax rates (savings + non-savings share these) -----
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.basic-rate"]
    :kontor.parameter-value/effective-from #inst "2008-04-06"
    :kontor.parameter-value/decimal-value  0.20M
    :kontor.parameter-value/citation       "ITA 2007 + post-2008 reform — basic rate 20 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.higher-rate"]
    :kontor.parameter-value/effective-from #inst "2008-04-06"
    :kontor.parameter-value/decimal-value  0.40M
    :kontor.parameter-value/citation       "ITA 2007 — higher rate 40 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.additional-rate"]
    :kontor.parameter-value/effective-from #inst "2010-04-06"
    :kontor.parameter-value/decimal-value  0.45M
    :kontor.parameter-value/citation       "FA 2010 — additional rate 50 % (since reduced to 45 % in FA 2013)"}

   ;; --- Dividend rates — bitemporal across April 2026 hike ----------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend.basic-rate"]
    :kontor.parameter-value/effective-from #inst "2022-04-06"
    :kontor.parameter-value/effective-until #inst "2026-04-06"
    :kontor.parameter-value/decimal-value  0.0875M
    :kontor.parameter-value/citation       "FA 2022 — dividend basic rate 7.5 → 8.75 % (HSC levy added); through 2025/26"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend.basic-rate"]
    :kontor.parameter-value/effective-from #inst "2026-04-06"
    :kontor.parameter-value/decimal-value  0.1075M
    :kontor.parameter-value/citation       "Autumn Budget 2025 — dividend basic rate 8.75 → 10.75 % from 6 Apr 2026"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend.higher-rate"]
    :kontor.parameter-value/effective-from #inst "2022-04-06"
    :kontor.parameter-value/effective-until #inst "2026-04-06"
    :kontor.parameter-value/decimal-value  0.3375M
    :kontor.parameter-value/citation       "FA 2022 — dividend higher rate 32.5 → 33.75 %; through 2025/26"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend.higher-rate"]
    :kontor.parameter-value/effective-from #inst "2026-04-06"
    :kontor.parameter-value/decimal-value  0.3575M
    :kontor.parameter-value/citation       "Autumn Budget 2025 — dividend higher rate 33.75 → 35.75 % from 6 Apr 2026"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "UK.IIT.dividend.additional-rate"]
    :kontor.parameter-value/effective-from #inst "2022-04-06"
    :kontor.parameter-value/decimal-value  0.3935M
    :kontor.parameter-value/citation       "FA 2022 — dividend additional rate 38.1 → 39.35 %; unchanged by 2026 reform"}])

;; ============================================================================
;; Provisions — UK investment-income carries the April-2026 rate cutover
;; ============================================================================
;;
;; The bitemporal rate change is handled entirely by `:parameter-value`
;; effective-from windows above (`apply-schedule` / `parameter-value-at`
;; pick up the right rate as-of the period end). No `:provision`-style
;; rule is needed in v1 — the ordering algorithm + parameter lookups
;; cover all of the substrate's behaviour.
;;
;; If a future feature needs `apply-provisions`-style folding (e.g. a
;; PA-taper provision above £100k, or a foreign-tax-credit), it joins
;; this seq.
;; ============================================================================

(def provisions
  "UK investment-income provisions — empty in v1. The bitemporal rate
   cutover is parameter-driven; the §16 ordering algorithm is
   procedural (lives in the provider)."
  [])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install UK investment-income statute (parameters + parameter-values)
   into `conn`. Idempotent via `:kontor.parameter/code` unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))

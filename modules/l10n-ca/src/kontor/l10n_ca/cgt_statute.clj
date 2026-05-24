(ns kontor.l10n-ca.cgt-statute
  "CA capital-gains tax — encoded as `kontor.statute` data per ADR-101.
   Research note 127.

   CA CGT is statutorily simple: a single 50% inclusion rate
   (ITA s.38(a)) applied to the gain on capital property. The
   complexity lives in:

   - **LCGE** — `s.110.6` lifetime exemption on QSBC shares + qualified
     farm/fishing property. $1,275,000 in 2026 (indexed annually under
     `s.117.1`).
   - **ABIL** — `s.39(1)(c)` business investment loss; the half-rate
     (`s.38(c)`) deduction flows OUT of the capital lane into ANY
     income (provider-side split, not a statute provision here).
   - **Principal residence** — `s.40(2)(b)` formula; v1 trusts the
     consumer's `:residence?` + `:elective-regime :ca-principal-residence`
     to flag full exemption.
   - **Rollovers** — `s.85` / `s.86` / `s.44` / `s.73` etc.; the
     gain is excluded from the pool entirely when an elective regime
     is flagged.
   - **CCA recapture** — depreciable property gain splits between
     ordinary income (`s.13(1)` recapture) and capital (the excess).
     Provider-side split using `:disposal/depreciation-taken-amount`.

   ## Note on the cancelled 2024 increase

   The proposed 2/3 inclusion rate above $250k (Bill C-69, Budget 2024)
   was cancelled by the Department of Finance on 2025-03-21. The
   substrate carries the 50% rate as a SINGLE parameter value with a
   1972 effective-from; no tiered bracket, no $250k threshold. A
   parameter-value's `:citation` notes the cancellation for the audit
   trail.

   ## Why no `:provision`s

   Unlike US CGT (NIIT surtax) or DE CIT (Soli + multi-component),
   CA CGT in the kontor model has no provider-level credits / surtaxes
   / base-adjustments — all complexity lives in:

   1. The 50% inclusion at the schedule level (handled by the provider
      with `(ts/flat 0.5M)` reading the parameter).
   2. The exemptions (LCGE / principal residence) at the per-disposal
      classification level.
   3. The downstream PIT/CIT base addition (the taxable capital gain
      flows into PIT or CIT through `:jurisdiction-specific-codes`).

   So this file ships PARAMETERS ONLY — the date-keyed values the
   provider reads to know the inclusion rate, the LCGE cap, and the
   ABIL rate. Provisions remain empty.

   Citations point at laws-lois.justice.gc.ca for ITA sections."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "CA CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- Inclusion rate (ITA s.38(a)) ---------------------------------------
   {:parameter/code         "CA.CGT.inclusion-rate"
    :parameter/label        "ITA s.38(a) — taxable capital gain inclusion rate (1/2)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html"}

   ;; --- ABIL rate (ITA s.38(c)) --------------------------------------------
   {:parameter/code         "CA.CGT.abil-rate"
    :parameter/label        "ITA s.38(c) — allowable business investment loss rate (1/2)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-38.html"}

   ;; --- LCGE — Lifetime Capital Gains Exemption (s.110.6) ------------------
   {:parameter/code         "CA.CGT.lcge-cap"
    :parameter/label        "ITA s.110.6 — Lifetime Capital Gains Exemption cap (QSBC + QFP/QFishing shared pool)"
    :parameter/jurisdiction :ca
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/i-3.3/section-110.6.html"}

   ;; --- Personal-use property floor (s.46(1)) ------------------------------
   {:parameter/code         "CA.CGT.pup-floor"
    :parameter/label        "ITA s.46(1) — $1,000 floor on ACB and proceeds for personal-use property"
    :parameter/jurisdiction :ca
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-46.html"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "CA CGT parameter values. 2026 LCGE per TaxTips.ca history
   (https://www.taxtips.ca/smallbusiness/lifetime-capital-gains-exemption.htm)."
  [;; Inclusion rate — stable 1/2 since 1972 reform. The proposed 2/3
   ;; increase above $250k (Budget 2024) was cancelled by the
   ;; Department of Finance on 2025-03-21; no parameter row for it.
   {:parameter-value/parameter      [:parameter/code "CA.CGT.inclusion-rate"]
    :parameter-value/effective-from #inst "1972-01-01"
    :parameter-value/decimal-value  0.5M
    :parameter-value/citation       "ITA s.38(a) — 1/2 inclusion stable since 1972 reform; proposed 2/3 above $250k (Bill C-69) cancelled by Dept. of Finance 2025-03-21"}

   ;; ABIL — 1/2 of a business investment loss per s.38(c).
   {:parameter-value/parameter      [:parameter/code "CA.CGT.abil-rate"]
    :parameter-value/effective-from #inst "1972-01-01"
    :parameter-value/decimal-value  0.5M
    :parameter-value/citation       "ITA s.38(c) — 1/2 of a business investment loss (allowable business investment loss)"}

   ;; LCGE — the indexed values. 2025 = 1,250,000; 2026 = 1,275,000.
   ;; The June-25-2024 jump from 1,016,836 was retained even after the
   ;; inclusion-rate increase was cancelled (Bill C-69 §10).
   {:parameter-value/parameter       [:parameter/code "CA.CGT.lcge-cap"]
    :parameter-value/effective-from  #inst "2024-06-25"
    :parameter-value/effective-until #inst "2026-01-01"
    :parameter-value/decimal-value   1250000M
    :parameter-value/citation        "ITA s.110.6(2) — LCGE raised to $1,250,000 effective 2024-06-25 (Bill C-69 §10; retained when inclusion-rate increase was cancelled)"}

   {:parameter-value/parameter      [:parameter/code "CA.CGT.lcge-cap"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  1275000M
    :parameter-value/citation       "ITA s.110.6(2) — LCGE indexed to $1,275,000 for 2026 disposals (s.117.1 indexation resumed); per TaxTips.ca LCGE history"}

   ;; PUP floor — $1,000 since the floor was established.
   {:parameter-value/parameter      [:parameter/code "CA.CGT.pup-floor"]
    :parameter-value/effective-from #inst "1972-01-01"
    :parameter-value/decimal-value  1000M
    :parameter-value/citation       "ITA s.46(1) — $1,000 floor on ACB and proceeds for personal-use property"}])

;; ============================================================================
;; Provisions — none in v1 (see ns docstring §"Why no :provisions")
;; ============================================================================

(def provisions
  "CA CGT provisions — empty in v1. The 50% inclusion, LCGE, ABIL
   split, principal-residence exemption, rollover exclusion, and CCA
   recapture split are all handled in the provider directly against
   the per-disposal `:asset-class` / `:exemption-claimed` /
   `:elective-regime` slots; no provision-level credits or surtaxes
   apply at the CGT layer itself."
  [])

;; ============================================================================
;; Install! — transact parameters (+ provisions, if any)
;; ============================================================================

(defn install!
  "Install CA CGT statute (parameters + parameter-values) into `conn`.
   Idempotent — `:parameter/code` is a unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))

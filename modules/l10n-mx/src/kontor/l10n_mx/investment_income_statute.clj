(ns kontor.l10n-mx.investment-income-statute
  "MX investment-income tax — dividends + bank interest — encoded as
   `kontor.statute` data per ADR-101. Research note 157.

   ## What's encoded

   Date-keyed parameters:

     - `MX.INV.dividendos.isr-adicional-rate` (0.10) — the art. 140
       LISR 10 % ISR Adicional on PF dividends (post-2014 CUFIN slice).
       SCJN constitutionality CONFIRMED 8 January 2026 — settled law,
       no more amparo litigation risk.
     - `MX.INV.dividendos.gross-up-factor` (1.4286) — the art. 140
       grossing-up factor = 1 / (1 − 0.30 CIT). Stored explicitly so
       the audit trail can show \"the factor in effect was 1.4286\"
       without back-deriving from the CIT rate.
     - `MX.INV.dividendos.corporate-isr-credit-rate` (0.30) — deemed
       corporate-ISR credit fraction on the grossed-up dividend
       (art. 140 factor-credit).
     - `MX.INV.bank-interest.wht-rate` — annual LIF cliff: 0.0050
       2023-2025, 0.0090 from 2026-01-01 (LIF 2026 raised 80 %; the
       SCJN-confirmed Adicional has nothing to do with this — it's
       the inflation-calibration adjustment).
     - `MX.INV.nr-dividend.base-rate` (0.10) — non-resident art. 164
       LISR definitive WHT base rate (treaty caps separately).
     - `MX.INV.foreign-tax-credit-cap-rate` (1.00) — art. 5 LISR
       foreign-tax credit cap rate (the cap is at the MX ISR that
       would otherwise apply — provider math caps the credit at the
       slab-rate ISR on the foreign income; the parameter encodes the
       per-country basket multiplier which is 1.0 = full).

   One provision:

     - `MX-LISR-art-140-isr-adicional-dividendos`: 10 % definitive
       WHT on PF dividends NOT sourced from pre-2014 CUFIN. The
       consumer signals CUFIN sourcing via
       `:elective-regime :mx-cufin-paid` — when set, the provision
       does NOT fire (= 0 % additional). Otherwise the law's
       presumption applies (post-2014 CUFIN) and 10 % fires.

   ## Bitemporal interest-rate cliff

   The 2026-01-01 cliff rides the parameter's `:effective-from`
   (the parameter-value lookup automatically honours `as-of`).
   ADR-101 Addendum 2 `period-from-on-or-after` is NOT used here
   because we want the rate that applied on the deposit date, not the
   fiscal-year start — both effectively coincide for the LIF cliff."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "MX investment-income parameter definitions."
  [{:kontor.parameter/code         "MX.INV.dividendos.isr-adicional-rate"
    :kontor.parameter/label        "Art. 140 LISR — 10 % ISR Adicional on PF dividends (post-2014 CUFIN)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/"}

   {:kontor.parameter/code         "MX.INV.dividendos.gross-up-factor"
    :kontor.parameter/label        "Art. 140 LISR — grossing-up factor (= 1 / (1 − CIT 0.30))"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/"}

   {:kontor.parameter/code         "MX.INV.dividendos.corporate-isr-credit-rate"
    :kontor.parameter/label        "Art. 140 LISR — deemed corporate-ISR credit fraction (30 %)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/"}

   {:kontor.parameter/code         "MX.INV.bank-interest.wht-rate"
    :kontor.parameter/label        "Art. 54 LISR + annual LIF — bank-interest provisional WHT (on daily-avg balance)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-vi/"}

   {:kontor.parameter/code         "MX.INV.nr-dividend.base-rate"
    :kontor.parameter/label        "Art. 164 LISR — non-resident dividend base-rate WHT (10 %)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-v/"}

   {:kontor.parameter/code         "MX.INV.foreign-tax-credit-cap-rate"
    :kontor.parameter/label        "Art. 5 LISR — foreign-tax credit per-country basket cap rate (1.0 = full)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-i/"}])

(def parameter-values
  "MX investment-income parameter values."
  [;; 10 % ISR Adicional — in effect since 2014-01-01 (post-2014 CUFIN).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.dividendos.isr-adicional-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "Art. 140 LISR — 10 % ISR Adicional sobre Dividendos; SCJN Pleno 8 Jan 2026 confirmed constitutionality"}

   ;; Gross-up factor 1.4286 — same effective date as the CIT 30 %.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.dividendos.gross-up-factor"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  1.4286M
    :kontor.parameter-value/citation       "Art. 140 LISR — factor 1.4286 = 1 / (1 − 0.30 CIT)"}

   ;; Corporate-ISR credit rate (the 30 % factor-credit).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.dividendos.corporate-isr-credit-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.30M
    :kontor.parameter-value/citation       "Art. 140 LISR — deemed corporate-ISR credit (= CIT rate × grossed-up dividend)"}

   ;; Bank-interest WHT — the LIF 2026 cliff: 0.50 % until 2025-12-31,
   ;; then 0.90 % from 2026-01-01 (80 % hike confirmed in note 157).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.bank-interest.wht-rate"]
    :kontor.parameter-value/effective-from #inst "2023-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  0.0050M
    :kontor.parameter-value/citation       "Art. 54 LISR + LIF 2023/2024/2025 — provisional WHT 0.50 % on daily-avg principal"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.bank-interest.wht-rate"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  0.0090M
    :kontor.parameter-value/citation       "LIF 2026 — provisional WHT raised to 0.90 % (note 157 §1.2.1)"}

   ;; Non-resident dividend art. 164 base rate.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.nr-dividend.base-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "Art. 164 LISR — non-resident dividend WHT 10 % (treaty caps apply separately)"}

   ;; Foreign tax credit cap multiplier — 1.0 (full).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.INV.foreign-tax-credit-cap-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  1.00M
    :kontor.parameter-value/citation       "Art. 5 LISR — foreign tax credit capped at MX ISR otherwise due; per-country basket"}])

;; ============================================================================
;; Provisions
;; ============================================================================

(def provisions
  "MX investment-income provisions for the `kontor.statute` evaluator.

   Single provision: the 10 % ISR Adicional on PF dividends NOT
   sourced from pre-2014 CUFIN. The provider thread the CUFIN-bucket
   decision into ctx via `:cufin-bucket` (kontor.statute condition
   reads `[:eq :cufin-bucket :pre-2014]` for the exempt slice; the
   default = post-2014 fires the 10 %)."
  [{:kontor.provision/code            "MX-LISR-art-140-isr-adicional-dividendos"
    :kontor.provision/jurisdiction    :mx
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "Art. 140 LISR — 10 % ISR Adicional sobre Dividendos (PF, post-2014 CUFIN)"
    :kontor.provision/citation        "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-viii/"
    :kontor.provision/effective-from  #inst "2014-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :pass :mx-adicional]
                                        [:gt [:inputs :mx-dividend-amount] 0M]])
    :kontor.provision/consequence     (pr-str {:op :surtax
                                        :code :mx-adicional-dividendos
                                        :label "Art. 140 LISR — 10 % ISR Adicional sobre dividendos"
                                        :parameter "MX.INV.dividendos.isr-adicional-rate"
                                        :base-from :inputs
                                        :base-input :mx-dividend-amount})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install MX investment-income statute (parameters + parameter-values
   + provisions) into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs.

   Note: the existing MX CGT statute is independent — installing
   either does not require the other (this provider does not lean on
   any MX CGT parameter)."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

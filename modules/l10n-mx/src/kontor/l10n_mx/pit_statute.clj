(ns kontor.l10n-mx.pit-statute
  "MX personal income tax — ISR personas físicas — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `mx-isr-personal-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-at.pit-statute` (the closest single-component PIT
   template with the same `:parameter-bracket`-driven progressive
   schedule pattern).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the LISR art. 152
     bracket scale (one bracket parameter, 11 bands × 3 distinct
     `:effective-from`-keyed sets: pre-2024 stable / 2024-reform /
     2025-RMF-Anexo-8). Plus optional v1 stubs for the subsidio para
     el empleo UMA-month / factor / ceiling parameters and the
     art. 151 deduction caps.

   - **Provisions** (per-jurisdiction rules) — 7 provisions:
       - MX-LISR-art-96-bis-subsidio-empleo — Q5.5 migration. The
         refundable employment subsidy that drives liability
         negative for low earners; consumer supplies magnitude.
       - MX-LISR-art-96-isr-prepaid-credit — refundable ISR
         retenido (wage withholding) credit.
       - MX-LISR-art-120-cgt-pit-fold — base-add for the
         `cgt-provider/real-estate-component` + `unlisted-component`
         `:pit-base-additions` lanes.
       - MX-LISR-art-140-investment-pit-fold — base-add for the
         `investment-income-provider` PF lanes (dividend acumulable,
         bank-interest real, foreign-dividend).
       - MX-LISR-art-140-corporate-isr-credit — non-refundable
         factor-credit for art. 140 grossed-up dividends.
       - MX-LISR-art-54-bank-interest-credit — refundable
         provisional WHT on bank interest.
       - MX-LISR-art-5-foreign-tax-credit — non-refundable foreign
         tax credit (per-country basket capped externally).

   - **Scoping** — all provisions scoped to `:isr-pf` via
     `[:eq :component :isr-pf]`, matching the FR `:is` and AT `:est`
     per-component discipline.

   ## Bracket-set shape (Q5.4 — 5y bitemporal history)

   Three distinct sets keyed by `:effective-from`:

   - **2018-01-01** — pre-2024-reform stable set (covers 2018-2023;
     the 2018 INPC adjustment created this set, then no INPC ≥ 10 %
     trigger fired until DOF 28-Dic-2023). 11 bands.
   - **2024-01-01** — post-2024 reform set (DOF 28-Dic-2023 / RMF
     2024 Anexo 8). 11 bands.
   - **2025-01-01** — RMF 2025 Anexo 8 (no threshold movement vs
     2024; bands repeat — but the row is shipped anyway because the
     audit trail wants 'this is the 2025 set' explicitly).

   Total 11 bands × 3 sets = **33 bracket rows** — the largest data
   section in.

   ## Sub-cent divergence

   The substrate-native marginal-rate form computes the exact
   integral of the bracket folds. SAT's published `cuota fija`
   figures (in `límite-inferior / cuota-fija / %-excedente` form)
   are each rounded to 2 decimals; the two forms differ by up to
   ~3 cents at the top bands. This statute file ships the
   marginal-rate form (substrate-native); the deprecated
   `isr-personal-cuota-fija-schedule` `:formula` in
   `period_tax_provider.clj` remains available for legacy consumers
   who need bit-exact SAT-form agreement.

   ## Out of scope for v1 ( slice)

   - **Deducciones personales (LISR art. 151)** — capped at the
     lesser of 15 % of income or 5 UMA-years. Consumer pre-computes
     and folds via `:inputs :gross-income` net.
   - **Estímulo fiscal por colegiaturas** — consumer-supplied
     credit lane (legacy `:credit`).
   - **Negativsteuer / Pension reconciliation** — n/a for MX.
   - **Subsidio compute-fn (v1.1)** — magnitude depends on
     monthly-wage-ceiling test the kernel cannot adjudicate from
     the GL; consumer supplies via `:inputs :subsidio-empleo`. The
     UMA-month / factor / ceiling parameters are shipped as stubs
     so a future v1.1 compute-fn can cross-check.

   ## Audit-doc seam (TODO —)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   small kernel sweep tracked separately, not a per-jurisdiction
   fix.

   ## Citations

   `mexico.justia.com` for the consolidated statute text (same
   convention as the shipped MX modules); RMF Anexo 8 per year for
   the bracket history."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "MX ISR personas físicas parameter definitions. The bracket parameter
   (`MX.PIT.art-152.brackets`) is the primary one — bracket rows live
   in `parameter-brackets` keyed by `:effective-from`. The remaining
   parameters are v1-OPTIONAL stubs documenting statutory constants
   the consumer or a v1.1 compute-fn may use."
  [{:kontor.parameter/code         "MX.PIT.art-152.brackets"
    :kontor.parameter/label        "LISR art. 152 — annual progressive Tarifa (11 bands; year-keyed via :effective-from per INPC ≥ 10 % adjustment)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-xi/"}

   {:kontor.parameter/code         "MX.PIT.art-96-bis.subsidio-empleo-uma-month"
    :kontor.parameter/label        "LISR art. 96-bis — UMA elevated to a month (post-2024 subsidio para el empleo reform); INEGI-published, annual"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"}

   {:kontor.parameter/code         "MX.PIT.art-96-bis.subsidio-empleo-factor"
    :kontor.parameter/label        "LISR art. 96-bis — subsidio para el empleo factor (11.82 % post-2024 reform)"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"}

   {:kontor.parameter/code         "MX.PIT.art-96-bis.subsidio-empleo-ceiling"
    :kontor.parameter/label        "LISR art. 96-bis — monthly taxable-income ceiling above which the subsidio is no longer granted"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"}

   {:kontor.parameter/code         "MX.PIT.art-151.deduction-cap-uma-years"
    :kontor.parameter/label        "LISR art. 151 — deducciones personales cap, alternative as 5 UMA-years"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :years
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-xi/"}

   {:kontor.parameter/code         "MX.PIT.art-151.deduction-cap-income-fraction"
    :kontor.parameter/label        "LISR art. 151 — deducciones personales cap, alternative as 15 % of income"
    :kontor.parameter/jurisdiction :mx
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-xi/"}])

(def parameter-values
  "MX ISR personas físicas scalar parameter values. Optional v1 stubs
   documenting current statutory constants for reference. The
   subsidio UMA-month is INEGI-published each February; we ship 2024
   + 2025 values. The factor (11.82 %) + ceiling are post-2024 reform
   constants. The art. 151 caps are stable since the 2014 LISR
   reform."
  [;; Subsidio UMA-month — annual INEGI release.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "MX.PIT.art-96-bis.subsidio-empleo-uma-month"]
    :kontor.parameter-value/effective-from  #inst "2024-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   475M
    :kontor.parameter-value/citation        "LISR art. 96-bis / INEGI UMA mensual 2024 — MX$ 475 / month (post-2024 reform mechanic)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.PIT.art-96-bis.subsidio-empleo-uma-month"]
    :kontor.parameter-value/effective-from #inst "2025-01-01"
    :kontor.parameter-value/decimal-value  496M
    :kontor.parameter-value/citation       "LISR art. 96-bis / INEGI UMA mensual 2025 — MX$ 496 / month (5 % indexed)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.PIT.art-96-bis.subsidio-empleo-factor"]
    :kontor.parameter-value/effective-from #inst "2024-01-01"
    :kontor.parameter-value/decimal-value  0.1182M
    :kontor.parameter-value/citation       "LISR art. 96-bis (2024 reform) — 11.82 % flat factor on UMA elevated to a month"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.PIT.art-96-bis.subsidio-empleo-ceiling"]
    :kontor.parameter-value/effective-from #inst "2024-01-01"
    :kontor.parameter-value/decimal-value  10171M
    :kontor.parameter-value/citation       "LISR art. 96-bis (2024 reform) — monthly taxable-income ceiling MX$ 10 171 (≈ 1 UMA × 12 + extras)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.PIT.art-151.deduction-cap-uma-years"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  5M
    :kontor.parameter-value/citation       "LISR art. 151 — deducciones personales cap, 5 UMA-years"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "MX.PIT.art-151.deduction-cap-income-fraction"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "LISR art. 151 — deducciones personales cap, 15 % of income"}])

;; ============================================================================
;; Parameter brackets — the LISR art. 152 11-band Tarifa, 3 yearly sets
;; ============================================================================
;;
;; The marginal-rate form `{:rate :upper}` is the substrate-native shape
;; (kontor.tax.tax-schedule). Each set is the conversion of the
;; SAT-published `(límite-inferior, cuota-fija, %-excedente)` tarifa
;; via `tarifa->marginal-brackets` in the legacy provider:
;;
;;   :upper(band) = next-band's :lower − 0.01
;;   :rate(band)  = band's %-excedente
;;
;; See.2.2 +.2 for the form-choice rationale.
;; The deprecated `isr-personal-cuota-fija-schedule` in
;; `period_tax_provider.clj` is the SAT-form alternative for legacy
;; consumers needing bit-exact agreement on the cuota-fija arithmetic.

(def ^:private pre-2024-set
  "Pre-2024 reform (stable 2018-2023 set per RMF Anexo 8 2023; the
   2018 INPC adjustment created this set, then no 10 %-INPC trigger
   fired until DOF 28-Dic-2023). 11 bands."
  [{:rate 0.0192M :upper 7735M}
   {:rate 0.0640M :upper 65651.07M}
   {:rate 0.1088M :upper 115375.90M}
   {:rate 0.1600M :upper 134119.41M}
   {:rate 0.1792M :upper 160577.65M}
   {:rate 0.2136M :upper 323862M}
   {:rate 0.2352M :upper 510451M}
   {:rate 0.3000M :upper 974535.03M}
   {:rate 0.3200M :upper 1299380.04M}
   {:rate 0.3400M :upper 3898140.12M}
   {:rate 0.3500M :upper nil}])

(def ^:private post-2024-set
  "Post-2024 reform (DOF 28-Dic-2023 / RMF 2024 Anexo 8). The 2024
   reform widened all 11 thresholds for cumulative INPC ≥ 10 % since
   2018. Effective 2024-01-01 onwards; the 2025 set repeats these
   values (RMF 2025 Anexo 8 — no INPC ≥ 10 % trigger fired from 2024
   to 2025). 11 bands."
  [{:rate 0.0192M :upper 8952.49M}
   {:rate 0.0640M :upper 75984.55M}
   {:rate 0.1088M :upper 133536.07M}
   {:rate 0.1600M :upper 155229.80M}
   {:rate 0.1792M :upper 185852.57M}
   {:rate 0.2136M :upper 374837.88M}
   {:rate 0.2352M :upper 590795.99M}
   {:rate 0.3000M :upper 1127926.84M}
   {:rate 0.3200M :upper 1503902.46M}
   {:rate 0.3400M :upper 4511707.37M}
   {:rate 0.3500M :upper nil}])

(def parameter-brackets
  "LISR art. 152 progressive bracket scale — 3 yearly sets (pre-2024
   stable / 2024-reform / 2025-Anexo-8 repeat-row). Each set's
   `[:effective-from, :effective-until)` half-open window selects
   exactly one set for any given `:as-of`. 11 bands per set × 3 sets
   = 33 rows.

   Sources: SAT RMF Anexo 8 per year. The pre-2024 set is the 2018
   INPC-adjusted baseline; the 2024 set lifted thresholds per the
   DOF 28-Dic-2023 reform; the 2025 set is the RMF 2025 Anexo 8
   repeat (no INPC ≥ 10 % trigger fired)."
  (vec
   (concat
    ;; pre-2024 set (effective 2018-2023)
    (map-indexed
     (fn [idx {:keys [rate upper]}]
       (cond->
        {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "MX.PIT.art-152.brackets"]
         :kontor.parameter-bracket/index           idx
         :kontor.parameter-bracket/rate            rate
         :kontor.parameter-bracket/effective-from  #inst "2018-01-01"
         :kontor.parameter-bracket/effective-until #inst "2024-01-01"}
         upper (assoc :kontor.parameter-bracket/upper upper)))
     pre-2024-set)
    ;; 2024 set (post-2024 reform, effective 2024-01-01 only)
    (map-indexed
     (fn [idx {:keys [rate upper]}]
       (cond->
        {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "MX.PIT.art-152.brackets"]
         :kontor.parameter-bracket/index           idx
         :kontor.parameter-bracket/rate            rate
         :kontor.parameter-bracket/effective-from  #inst "2024-01-01"
         :kontor.parameter-bracket/effective-until #inst "2025-01-01"}
         upper (assoc :kontor.parameter-bracket/upper upper)))
     post-2024-set)
    ;; 2025 set (open-ended; identical values to 2024 — explicit audit row)
    (map-indexed
     (fn [idx {:keys [rate upper]}]
       (cond->
        {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "MX.PIT.art-152.brackets"]
         :kontor.parameter-bracket/index          idx
         :kontor.parameter-bracket/rate           rate
         :kontor.parameter-bracket/effective-from #inst "2025-01-01"}
         upper (assoc :kontor.parameter-bracket/upper upper)))
     post-2024-set))))

;; ============================================================================
;; Provisions — MX ISR personas físicas statute as :provision data
;; ============================================================================

(def provisions
  "MX ISR personas físicas statutory provisions encoded for the
   `kontor.tax.statute` evaluator. Conditions reference `:component`
   (always `:isr-pf` in v1) and gate on the presence of driver facts.
   Consequences are `:tax-context-fact` reads (consumer-supplied
   amounts); rates and amounts come from `:parameter` data, NOT
   inlined here.

   v1 ships 7 provisions. The progressive bracket schedule itself is
   `:parameter-bracket`-driven (NOT a provision) — pulled by the
   provider via `parameter-brackets-at`."
  [;; ----------------------------------------------------------------
   ;; LISR art. 96-bis — Subsidio para el empleo (refundable, Q5.5)
   ;; ----------------------------------------------------------------
   ;; Q5.5 migration: the refundable employment
   ;; subsidy moves from the legacy `:credit` on `:inputs` to a
   ;; first-class `:provision` with `:op :credit :refundable? true`.
   ;; The consumer continues to supply the annual magnitude via
   ;; `:inputs :subsidio-empleo` (the monthly-wage-ceiling test is
   ;; payroll-level and out of kernel reach); the substrate honours
   ;; `:refundable? true` per `apply-adjustments`.
   {:kontor.provision/code           "MX-LISR-art-96-bis-subsidio-empleo"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "LISR art. 96-bis — Subsidio para el empleo (refundable, drives liability negative for low earners)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"
    :kontor.provision/effective-from #inst "2024-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :subsidio-empleo] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :mx-subsidio-empleo
                                              :label       "LISR art. 96-bis — Subsidio para el empleo"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :subsidio-empleo]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 96 — ISR retenido prepaid (refundable)
   ;; ----------------------------------------------------------------
   ;; Wage withholding the employer paid throughout the year; the
   ;; annual return refunds any excess. Refundable per the LISR
   ;; declaración anual mechanism.
   {:kontor.provision/code           "MX-LISR-art-96-isr-prepaid-credit"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "LISR art. 96 — ISR retenido (wage withholding); refundable on annual reconciliation"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-i/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :isr-retenido] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :mx-isr-retenido
                                              :label       "LISR art. 96 — ISR retenido (refundable)"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :isr-retenido]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 120 — Real-estate / unlisted-share fold into PIT base
   ;; ----------------------------------------------------------------
   ;; Reads the lane `cgt-provider/real-estate-component` emits
   ;; (cgt_provider.clj line 382 — art. 120 averaging acumulable
   ;; portion) + `unlisted-component` (line 451 — art. 22 net).
   ;; Consumer harvests + sums into one scalar.
   {:kontor.provision/code           "MX-LISR-art-120-cgt-pit-fold"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "LISR art. 120 averaging — acumulable portion folds into PIT base"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-iv/seccion-i/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :cgt-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :mx-cgt-art-120-fold
                                              :label       "LISR art. 120 averaging — acumulable portion folds into PIT base"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 140 — Investment income fold (dividend + bank + foreign)
   ;; ----------------------------------------------------------------
   ;; Reads the lanes `investment-income-provider` emits:
   ;;   line 181 — PF dividend acumulable (grossed-up)
   ;;   line 271 — PF bank-interest real
   ;;   line 323 — PF foreign dividend (folded amount)
   ;; Consumer harvests + sums into one scalar.
   {:kontor.provision/code           "MX-LISR-art-140-investment-pit-fold"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "LISR art. 140 + bank-interest — investment income folds into PIT base"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-vi/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :investment-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :mx-investment-pit-fold
                                              :label       "LISR art. 140 + bank-interest — investment income folds into PIT base"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 140 — Corporate-ISR factor credit (non-refundable)
   ;; ----------------------------------------------------------------
   ;; Reads the lane investment-income-provider emits at line 161
   ;; (the post-2014 CUFIN factor-credit on grossed-up PF dividends).
   {:kontor.provision/code           "MX-LISR-art-140-corporate-isr-credit"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "LISR art. 140 — Corporate-ISR factor credit on grossed-up dividends (non-refundable)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-vi/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       500
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :investment-pit-credits-factor] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :mx-art-140-factor-credit
                                              :label       "LISR art. 140 — factor-credit on grossed-up dividends"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-credits-factor]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 54 — Bank-interest WHT credit (refundable)
   ;; ----------------------------------------------------------------
   ;; Reads the lane investment-income-provider emits at line 251
   ;; (provisional bank-interest withholding; refundable in annual
   ;; reconciliation).
   {:kontor.provision/code           "MX-LISR-art-54-bank-interest-credit"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "LISR art. 54 — Bank-interest WHT credit (refundable on annual reconciliation)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-iv/capitulo-vi/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       600
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :investment-pit-credits-bank-wht] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :mx-bank-wht-credit
                                              :label       "LISR art. 54 — bank-interest WHT (refundable)"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-credits-bank-wht]})}

   ;; ----------------------------------------------------------------
   ;; LISR art. 5 — Foreign tax credit (non-refundable)
   ;; ----------------------------------------------------------------
   ;; Reads the lane investment-income-provider emits at line 306
   ;; (per-country basket-capped FTC; non-refundable).
   {:kontor.provision/code           "MX-LISR-art-5-foreign-tax-credit"
    :kontor.provision/jurisdiction   :mx
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "LISR art. 5 — Foreign tax credit, per-country basket-capped (non-refundable)"
    :kontor.provision/citation       "https://mexico.justia.com/federales/leyes/ley-del-impuesto-sobre-la-renta/titulo-i/"
    :kontor.provision/effective-from #inst "2014-01-01"
    :kontor.provision/priority       700
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :isr-pf]
                                              [:gt [:inputs :investment-pit-credits-ftc] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :mx-foreign-tax-credit
                                              :label       "LISR art. 5 — Foreign tax credit (non-refundable)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-credits-ftc]})}])

;; ============================================================================
;; Install! — transact parameters + values + brackets + provisions
;; ============================================================================

(defn- bracket-row-already-present?
  "True iff a `:parameter-bracket` row with the same `(parameter-code,
   index, effective-from)` triple is already in `db`. Used to make the
   bracket install idempotent — `:parameter-bracket` carries no
   `:db/unique :db.unique/identity` attr in the kernel schema (the
   parent `:kontor.parameter/code` is the natural-key seam; the
   bracket's identity is the `(parent, index, effective-from)`
   triple), so the provider must do the dedup itself.

   Mirrors `kontor.l10n-at.pit-statute/bracket-row-already-present?`
   + `kontor.l10n-fr.cit-statute/bracket-row-already-present?`."
  [db {:kontor.parameter-bracket/keys [parameter index effective-from]}]
  (boolean
   (seq
    (d/q '[:find ?b
           :in $ ?code ?idx ?from
           :where
           [?p :kontor.parameter/code ?code]
           [?b :kontor.parameter-bracket/parameter ?p]
           [?b :kontor.parameter-bracket/index ?idx]
           [?b :kontor.parameter-bracket/effective-from ?from]]
         db (second parameter) index effective-from))))

(defn install!
  "Install MX PIT statute (parameters + parameter-values + bracket
   rows + provisions) into `conn`. Idempotent —
   `:kontor.parameter/code` and `:kontor.provision/code` are unique
   identity attrs (upsert on re-install); parameter-brackets get
   explicit dedup via `bracket-row-already-present?` since the kernel
   schema does not carry a `:db/unique` on them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (let [db           (d/db conn)
        new-brackets (remove #(bracket-row-already-present? db %)
                             parameter-brackets)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))

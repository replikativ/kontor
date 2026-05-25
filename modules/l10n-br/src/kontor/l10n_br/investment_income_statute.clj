(ns kontor.l10n-br.investment-income-statute
  "BR investment-income tax — dividends, JCP, renda fixa, FII — encoded
   as `kontor.statute` data per ADR-101. Research note 155.

   Four orthogonal pillars (note 155 §1):

   1. **Dividend WHT (Lei 15.270/2025)** — newly enacted, in force
      2026-01-01. 10 % IRRF on PF distributions exceeding R$ 50k/month
      from the SAME payer; 10 % flat on any cross-border distribution
      regardless of amount; PJ-to-PJ exemption preserved.
   2. **IRPFM (Lei 15.270/2025 art. 9-13)** — high-earner minimum
      tax; linear ramp 0 %→10 % across R$ 600k-R$ 1.2M annual income.
      The first kontor provider needing a parametric-on-base
      `(ts/formula …)` schedule (note 155 §5.2).
   3. **JCP IRRF (PLP 128/2025)** — 15 %→17.5 % rate cliff at
      2026-01-01. Read at the JCP DELIBERATION date, not the payment
      date. ADR-101 + ADR-048 carry it natively.
   4. **Renda fixa regressive** — 22.5 / 20 / 17.5 / 15 % by holding-
      period bucket; preserved unchanged by Lei 15.270/2025. FII
      distributions to PF remain exempt (Lei 11.033/2004 art. 3 III)
      subject to the historical conditions (≥100 cotistas + ≤10 %
      stake + listed).

   Per the recommendation in note 155 §6, the renda-fixa table ships as
   FOUR FLAT PARAMETERS keyed by bucket (1=≤180d, 2=181-360d, 3=361-
   720d, 4=>720d), avoiding extending `:parameter-bracket` with a new
   `:upper-days` axis. The provider's classifier maps holding-days to
   bucket.

   The 2026 dividend cutover is bitemporal: TWO `:parameter-value` rows
   for `BR.INV.PF.dividend-irrf-rate` — 0M from 1996-01-01 (the Lei
   9.249/95 art. 10 PF exemption), 0.10M from 2026-01-01 (the Lei
   15.270/2025 enactment). The provider reads via `parameter-value-at`
   keyed on the period's `:as-of`. ONE provision uses the ADR-101
   Addendum 2 `period-from-on-or-after` helper to gate the
   post-2026 cross-border 10 % IRRF rule on the period start (so a
   late-2025 / early-2026 straddle period does not over-fire)."
  (:require [datahike.api :as d]
            [kontor.statute :as statute]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "BR investment-income parameter definitions. All scalar except
   per-bucket renda-fixa rates (also flat, see ns docstring)."

  [;; --- Pillar 1 — PF dividend IRRF rate (resident, > R$ 50k/month) -------
   {:parameter/code         "BR.INV.PF.dividend-irrf-rate"
    :parameter/label        "PF dividend IRRF rate (Lei 9.249/95 art. 10 → Lei 15.270/2025 art. 6)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 1 — cross-border dividend IRRF rate -----------------------
   {:parameter/code         "BR.INV.NR.dividend-irrf-rate"
    :parameter/label        "Non-resident dividend IRRF rate (Lei 15.270/2025 art. 7)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 1 — R$ 50k/month per-payer trigger ------------------------
   {:parameter/code         "BR.INV.PF.dividend-monthly-trigger"
    :parameter/label        "PF dividend monthly per-payer trigger (R$) — Lei 15.270/2025 art. 6"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 2 — IRPFM band-low (R$ 600k) ------------------------------
   {:parameter/code         "BR.INV.IRPFM.band-low"
    :parameter/label        "IRPFM ramp low anchor (R$ 600k) — Lei 15.270/2025 art. 11"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 2 — IRPFM band-high (R$ 1.2M) -----------------------------
   {:parameter/code         "BR.INV.IRPFM.band-high"
    :parameter/label        "IRPFM ramp top anchor (R$ 1.2M) — Lei 15.270/2025 art. 11"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 2 — IRPFM top rate (10 %) ---------------------------------
   {:parameter/code         "BR.INV.IRPFM.top-rate"
    :parameter/label        "IRPFM top rate (10 % above R$ 1.2M) — Lei 15.270/2025 art. 11"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_Ato2023-2026/2025/Lei/L15270.htm"}

   ;; --- Pillar 3 — JCP IRRF rate (15 % → 17.5 % at 2026-01-01) -----------
   {:parameter/code         "BR.INV.JCP.irrf-rate"
    :parameter/label        "JCP IRRF rate (Lei 9.249/95 art. 9 → PLP 128/2025)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.camara.leg.br/noticias/1233924-camara-aprova-projeto-que-reduz-beneficios-fiscais-federais-e-aumenta-tributacao-de-bets-e-fintechs"}

   ;; --- Pillar 4 — Renda fixa rate per bucket ----------------------------
   ;; Per note 155 §6 recommendation: four flat parameters, not a
   ;; bracket scale on the (novel) :upper-days axis. The provider's
   ;; classifier maps holding-days → bucket index.
   {:parameter/code         "BR.INV.renda-fixa.rate-bucket-1"
    :parameter/label        "Renda fixa IRRF (≤180 days, 22.5 %)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   {:parameter/code         "BR.INV.renda-fixa.rate-bucket-2"
    :parameter/label        "Renda fixa IRRF (181-360 days, 20 %)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   {:parameter/code         "BR.INV.renda-fixa.rate-bucket-3"
    :parameter/label        "Renda fixa IRRF (361-720 days, 17.5 %)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   {:parameter/code         "BR.INV.renda-fixa.rate-bucket-4"
    :parameter/label        "Renda fixa IRRF (>720 days, 15 %)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   ;; --- Pillar 4 — FII PF exemption conditions ---------------------------
   ;; The conditions live in the provider (≥100 cotistas + ≤10 % stake +
   ;; listed); these parameters surface the numeric thresholds for
   ;; audit-doc / citation.
   {:parameter/code         "BR.INV.FII.min-cotistas"
    :parameter/label        "FII PF exemption — minimum number of cotistas (Lei 11.033/2004 art. 3 III)"
    :parameter/jurisdiction :br
    :parameter/unit         :amount-money     ; placeholder — :count semantic
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}

   {:parameter/code         "BR.INV.FII.max-ownership-fraction"
    :parameter/label        "FII PF exemption — maximum holder ownership fraction (Lei 11.033/2004 art. 3 III)"
    :parameter/jurisdiction :br
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.planalto.gov.br/ccivil_03/_ato2004-2006/2004/lei/l11033.htm"}])

;; ============================================================================
;; Parameter values — date-keyed history
;; ============================================================================

(def parameter-values
  "BR investment-income parameter values with their statutory
   effective windows. The PF dividend rate carries TWO rows: 0M from
   1996-01-01 (Lei 9.249/95 art. 10 exemption), 0.10M from 2026-01-01
   (Lei 15.270/2025 enactment). The JCP rate carries TWO rows: 0.15M
   from 1996-01-01 (Lei 9.249/95 art. 9), 0.175M from 2026-01-01
   (PLP 128/2025)."

  [;; --- Pillar 1 — PF dividend IRRF rate cliff ---------------------------
   {:parameter-value/parameter       [:parameter/code "BR.INV.PF.dividend-irrf-rate"]
    :parameter-value/effective-from  #inst "1996-01-01"
    :parameter-value/effective-until #inst "2026-01-01"
    :parameter-value/decimal-value   0M
    :parameter-value/citation        "Lei 9.249/95 art. 10 — PF dividend exemption (1996-2025)"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.PF.dividend-irrf-rate"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 15.270/2025 art. 6 — 10 % IRRF on PF dividend distributions > R$ 50k/month/payer"}

   ;; --- Pillar 1 — cross-border dividend IRRF rate -----------------------
   ;; Pre-2026 cross-border distributions were also exempt under the
   ;; 1996 regime (no PF / NR distinction in art. 10). The 2026 cutover
   ;; introduces the 10 % flat rate for NR regardless of amount.
   {:parameter-value/parameter       [:parameter/code "BR.INV.NR.dividend-irrf-rate"]
    :parameter-value/effective-from  #inst "1996-01-01"
    :parameter-value/effective-until #inst "2026-01-01"
    :parameter-value/decimal-value   0M
    :parameter-value/citation        "Lei 9.249/95 art. 10 — pre-2026 cross-border exemption"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.NR.dividend-irrf-rate"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 15.270/2025 art. 7 — 10 % IRRF on cross-border dividends (any amount)"}

   ;; --- Pillar 1 — R$ 50k/month per-payer trigger ------------------------
   {:parameter-value/parameter      [:parameter/code "BR.INV.PF.dividend-monthly-trigger"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  50000M
    :parameter-value/citation       "Lei 15.270/2025 art. 6 — R$ 50 000 monthly per-payer trigger"}

   ;; --- Pillar 2 — IRPFM ramp anchors + top rate -------------------------
   {:parameter-value/parameter      [:parameter/code "BR.INV.IRPFM.band-low"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  600000M
    :parameter-value/citation       "Lei 15.270/2025 art. 11 — IRPFM begins at R$ 600 000 annual base"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.IRPFM.band-high"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  1200000M
    :parameter-value/citation       "Lei 15.270/2025 art. 11 — IRPFM ramp peaks at R$ 1 200 000"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.IRPFM.top-rate"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 15.270/2025 art. 11 — 10 % IRPFM top rate"}

   ;; --- Pillar 3 — JCP IRRF rate cliff -----------------------------------
   {:parameter-value/parameter       [:parameter/code "BR.INV.JCP.irrf-rate"]
    :parameter-value/effective-from  #inst "1996-01-01"
    :parameter-value/effective-until #inst "2026-01-01"
    :parameter-value/decimal-value   0.15M
    :parameter-value/citation        "Lei 9.249/95 art. 9 § 2 II — JCP IRRF 15 % (definitive on PF)"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.JCP.irrf-rate"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  0.175M
    :parameter-value/citation       "PLP 128/2025 — JCP IRRF raised to 17.5 % effective 2026-01-01"}

   ;; --- Pillar 4 — Renda fixa per-bucket rates (stable since 2005) -------
   {:parameter-value/parameter      [:parameter/code "BR.INV.renda-fixa.rate-bucket-1"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  0.225M
    :parameter-value/citation       "Lei 11.033/2004 art. 1 I — 22.5 % up to 180 days"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.renda-fixa.rate-bucket-2"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "Lei 11.033/2004 art. 1 II — 20 % 181-360 days"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.renda-fixa.rate-bucket-3"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  0.175M
    :parameter-value/citation       "Lei 11.033/2004 art. 1 III — 17.5 % 361-720 days"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.renda-fixa.rate-bucket-4"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Lei 11.033/2004 art. 1 IV — 15 % >720 days"}

   ;; --- Pillar 4 — FII PF exemption thresholds ---------------------------
   {:parameter-value/parameter      [:parameter/code "BR.INV.FII.min-cotistas"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  100M
    :parameter-value/citation       "Lei 11.033/2004 art. 3 III — minimum 100 cotistas"}

   {:parameter-value/parameter      [:parameter/code "BR.INV.FII.max-ownership-fraction"]
    :parameter-value/effective-from #inst "2005-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Lei 11.033/2004 art. 3 III — maximum 10 % ownership"}])

;; ============================================================================
;; Provisions — v1 ships ZERO provisions
;; ============================================================================

(def provisions
  "BR investment-income v1 ships ZERO provisions. The condition-rich
   logic (per-payer monthly aggregation; FII conditions on
   cotistas/listed/stake; IRPFM credit-against-ordinary-IRPF) lives in
   the provider, per note 155 §3-§5.

   The bitemporal cliff at 2026-01-01 for dividend WHT + JCP rate is
   handled via DATE-KEYED `:parameter-value` rows (above) — the
   provider reads via `parameter-value-at` at the period's `:as-of`,
   which collapses the cliff naturally without needing a provision.

   ADR-101 Addendum 2 `period-from-on-or-after` is reserved for the
   case where a SAME-rate rule needs to gate on the period START
   (rather than `:as-of`); the BR dividend cliff matches statutory
   semantics under `:as-of` (the law took effect 2026-01-01 with no
   fiscal-year-straddle carve-out for individuals), so no provision is
   required. A consumer wanting to gate a late-2025 PF straddle return
   to the old rule can simply pass an `:as-of` of #inst \"2025-12-31\"."
  [])

;; ============================================================================
;; Install! — transact parameters + values
;; ============================================================================

(defn install!
  "Install BR investment-income statute (parameters + values) into
   `conn`. Idempotent — `:parameter/code` is the unique identity attr."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))

;; Re-export for parity with sibling l10n statute namespaces.
(def parameter-value-at statute/parameter-value-at)

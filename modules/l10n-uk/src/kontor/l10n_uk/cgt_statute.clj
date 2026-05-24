(ns kontor.l10n-uk.cgt-statute
  "UK capital-gains tax — TCGA 1992 (individuals) + chargeable-gains
   feeder into Corporation Tax (CTA 2010) — encoded as `kontor.statute`
   data per ADR-101. Research note 114.

   Two regimes with disjoint mechanics share this parameter table:

   - **Individual CGT** (TCGA 1992) — Annual Exempt Amount (AEA),
     Business Asset Disposal Relief (BADR — lifetime-cumulative cap,
     reduced rate), Investors' Relief (sibling of BADR), the standard
     basic / higher rates, and the (now-aligned) residential-property
     rates. Post Autumn-Budget 30 Oct 2024 the previously-distinct
     non-residential 10/20 tier was abolished — all classes share the
     18/24 stack. Residential rates were 18/24 already, so the alignment
     looks like a non-event for that class.
   - **Corporate chargeable gains** (TCGA 1992 + CTA 2010) — gains
     fold into the CT base at the company's main rate; the corporate
     half-tax has its own specials: indexation allowance (frozen Dec
     2017) and SSE (Substantial Shareholding Exemption — 10 %+ holding
     for 12 months in 6 years preceding disposal).

   ## Surfaces this statute does NOT encode (provider-internal)

   - BADR / IR lifetime-cap allocation, AEA application — these are
     base-side mechanics the provider runs in code; the parameter
     table carries the numeric values.
   - SSE eligibility — claimed via `:disposal/exemption-claimed
     #{:uk-sse}` on the disposal entity; the provider filters.
   - Indexation factor — already-indexed `:disposal/basis-amount`
     responsibility lies with the consumer per note 114 §3.2
     recommendation (multi-tranche FIFO/specific-id falls under
     ADR-029 `:lot/*` + emit-one-disposal-per-tranche).

   The §5.1 sketch in note 114 referenced `:formula` schedules for
   bracket-residual logic. v1 keeps it simpler: the provider reads
   `:tax-unit :income-band` (`:basic` or `:higher`) supplied by the
   consumer and selects the rate directly. The basic-residual layering
   pattern is a note 114 §5 follow-up.

   Citations point at legislation.gov.uk for the statute and gov.uk
   guidance pages for the rate / threshold values."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "UK CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- Annual Exempt Amount (AEA) — individuals only -----------------------
   {:parameter/code         "UK.CGT.AEA"
    :parameter/label        "Annual Exempt Amount (individuals)"
    :parameter/jurisdiction :uk
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gov.uk/capital-gains-tax/allowances"}

   ;; --- Standard CGT rates (post-Autumn-Budget 30 Oct 2024) -----------------
   ;; The pre-2024 "non-residential" 10/20 tier was abolished; all
   ;; asset classes now share 18/24 (basic/higher). Residential rates
   ;; carried over unchanged numerically — but with no class distinction.
   {:parameter/code         "UK.CGT.std.basic-rate"
    :parameter/label        "Standard CGT rate — basic-rate band"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gov.uk/capital-gains-tax/rates"}
   {:parameter/code         "UK.CGT.std.higher-rate"
    :parameter/label        "Standard CGT rate — higher-rate / additional band"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gov.uk/capital-gains-tax/rates"}

   ;; --- Residential-property CGT rates ---------------------------------------
   ;; Kept as separate codes so a future divergence (a residential
   ;; surcharge re-introduced, or BADR-on-non-residence carve-out)
   ;; flows as a one-row migration, not a code-edit.
   {:parameter/code         "UK.CGT.residential.basic-rate"
    :parameter/label        "Residential-property CGT rate — basic-rate band"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gov.uk/capital-gains-tax/rates"}
   {:parameter/code         "UK.CGT.residential.higher-rate"
    :parameter/label        "Residential-property CGT rate — higher-rate / additional band"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.gov.uk/capital-gains-tax/rates"}

   ;; --- BADR — TCGA s169H et seq. (formerly Entrepreneurs' Relief) ----------
   {:parameter/code         "UK.CGT.BADR.rate"
    :parameter/label        "Business Asset Disposal Relief rate"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/1992/12/section/169H"}
   {:parameter/code         "UK.CGT.BADR.lifetime-cap"
    :parameter/label        "BADR lifetime cap (cumulative across all of the taxpayer's BADR claims)"
    :parameter/jurisdiction :uk
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/1992/12/section/169N"}

   ;; --- Investors' Relief — TCGA s169VA et seq. -----------------------------
   {:parameter/code         "UK.CGT.IR.rate"
    :parameter/label        "Investors' Relief rate"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/1992/12/section/169VA"}
   {:parameter/code         "UK.CGT.IR.lifetime-cap"
    :parameter/label        "Investors' Relief lifetime cap (reduced from £10M to £1M from 30 Oct 2024)"
    :parameter/jurisdiction :uk
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.gov.uk/government/publications/capital-gains-tax-investors-relief-lifetime-limit-reduction/capital-gains-tax-investors-relief-reduction-in-the-lifetime-limit"}

   ;; --- Corporate side -------------------------------------------------------
   ;; The corporate CGT rate is not a CGT rate per se — chargeable gains
   ;; fold into the CT base at the company's main rate; the provider
   ;; emits `:cit-base-additions` rather than computing a liability.
   ;; This parameter is here for the SSE-exempt amount which surfaces as
   ;; a `:line-items` value at 0.
   {:parameter/code         "UK.CGT.SSE.min-holding-fraction"
    :parameter/label        "SSE substantial-shareholding threshold (TCGA Sch 7AC)"
    :parameter/jurisdiction :uk
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legislation.gov.uk/ukpga/1992/12/schedule/7AC"}])

;; ============================================================================
;; Parameter values — current rates with statutory effective windows
;; ============================================================================

(def parameter-values
  "UK CGT parameter values. Current = TY 2025/26 post Autumn-Budget 2024.
   BADR rate trajectory (10 % → 14 % → 18 %) is reflected as multiple
   effective windows."
  [;; --- AEA timeline ---------------------------------------------------------
   ;; £12,300 through 2022/23 (carried over from earlier); £6,000 in 2023/24;
   ;; £3,000 from 2024/25 (and frozen forward per HMT). We seed the current
   ;; window starting 2024-04-06 (UK tax year start).
   {:parameter-value/parameter      [:parameter/code "UK.CGT.AEA"]
    :parameter-value/effective-from #inst "2023-04-06"
    :parameter-value/effective-until #inst "2024-04-06"
    :parameter-value/decimal-value  6000M
    :parameter-value/citation       "Spring Budget 2023 — AEA reduced 12,300 → 6,000 for 2023/24"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.AEA"]
    :parameter-value/effective-from #inst "2024-04-06"
    :parameter-value/decimal-value  3000M
    :parameter-value/citation       "Spring Budget 2023 — AEA further reduced 6,000 → 3,000 from 2024/25 (frozen)"}

   ;; --- Standard CGT rates ---------------------------------------------------
   ;; Pre-2024 rates were 10/20 for non-residential; post-Autumn-Budget 2024
   ;; (effective 30 Oct 2024) the standard rates align upward to 18/24
   ;; for ALL non-residential classes — note 114 §1.1.
   {:parameter-value/parameter      [:parameter/code "UK.CGT.std.basic-rate"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/effective-until #inst "2024-10-30"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "FA 2016 — 10 % basic-rate CGT (non-residential), 2016/17 onwards"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.std.basic-rate"]
    :parameter-value/effective-from #inst "2024-10-30"
    :parameter-value/decimal-value  0.18M
    :parameter-value/citation       "Autumn Budget 2024 — standard basic-rate CGT raised 10 → 18 % from 30 Oct 2024"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.std.higher-rate"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/effective-until #inst "2024-10-30"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "FA 2016 — 20 % higher-rate CGT (non-residential), 2016/17 onwards"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.std.higher-rate"]
    :parameter-value/effective-from #inst "2024-10-30"
    :parameter-value/decimal-value  0.24M
    :parameter-value/citation       "Autumn Budget 2024 — standard higher-rate CGT raised 20 → 24 % from 30 Oct 2024"}

   ;; --- Residential-property rates -------------------------------------------
   {:parameter-value/parameter      [:parameter/code "UK.CGT.residential.basic-rate"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/decimal-value  0.18M
    :parameter-value/citation       "FA 2016 — 18 % basic-rate residential CGT, stable through Autumn 2024 alignment"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.residential.higher-rate"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/effective-until #inst "2024-04-06"
    :parameter-value/decimal-value  0.28M
    :parameter-value/citation       "FA 2016 — 28 % higher-rate residential CGT"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.residential.higher-rate"]
    :parameter-value/effective-from #inst "2024-04-06"
    :parameter-value/decimal-value  0.24M
    :parameter-value/citation       "Spring Budget 2024 — higher-rate residential CGT reduced 28 → 24 % from 6 Apr 2024"}

   ;; --- BADR --- 10 % until 5 Apr 2025; 14 % from 6 Apr 2025; 18 % from 6 Apr 2026 --
   {:parameter-value/parameter      [:parameter/code "UK.CGT.BADR.rate"]
    :parameter-value/effective-from #inst "2020-03-11"
    :parameter-value/effective-until #inst "2025-04-06"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "FA 2020 — BADR rate 10 % (lifetime cap reduced from £10M to £1M)"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.BADR.rate"]
    :parameter-value/effective-from #inst "2025-04-06"
    :parameter-value/effective-until #inst "2026-04-06"
    :parameter-value/decimal-value  0.14M
    :parameter-value/citation       "Autumn Budget 2024 — BADR rate 10 → 14 % from 6 Apr 2025"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.BADR.rate"]
    :parameter-value/effective-from #inst "2026-04-06"
    :parameter-value/decimal-value  0.18M
    :parameter-value/citation       "Autumn Budget 2024 — BADR rate 14 → 18 % from 6 Apr 2026"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.BADR.lifetime-cap"]
    :parameter-value/effective-from #inst "2020-03-11"
    :parameter-value/decimal-value  1000000M
    :parameter-value/citation       "FA 2020 — BADR lifetime cap reduced £10M → £1M (formerly Entrepreneurs' Relief £10M cap)"}

   ;; --- Investors' Relief ----------------------------------------------------
   ;; Rate trajectory mirrors BADR; cap slashed £10M → £1M from 30 Oct 2024
   ;; (Finance Bill 2024-25). User spec called for £10M; note 114 §1.1
   ;; says £1M post Oct-2024. We follow note 114 (the authoritative
   ;; research) — see report.
   {:parameter-value/parameter      [:parameter/code "UK.CGT.IR.rate"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/effective-until #inst "2025-04-06"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "FA 2016 — Investors' Relief rate 10 %"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.IR.rate"]
    :parameter-value/effective-from #inst "2025-04-06"
    :parameter-value/effective-until #inst "2026-04-06"
    :parameter-value/decimal-value  0.14M
    :parameter-value/citation       "Autumn Budget 2024 — IR rate 10 → 14 % from 6 Apr 2025"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.IR.rate"]
    :parameter-value/effective-from #inst "2026-04-06"
    :parameter-value/decimal-value  0.18M
    :parameter-value/citation       "Autumn Budget 2024 — IR rate 14 → 18 % from 6 Apr 2026"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.IR.lifetime-cap"]
    :parameter-value/effective-from #inst "2016-04-06"
    :parameter-value/effective-until #inst "2024-10-30"
    :parameter-value/decimal-value  10000000M
    :parameter-value/citation       "FA 2016 — IR lifetime cap £10M (pre-reform)"}
   {:parameter-value/parameter      [:parameter/code "UK.CGT.IR.lifetime-cap"]
    :parameter-value/effective-from #inst "2024-10-30"
    :parameter-value/decimal-value  1000000M
    :parameter-value/citation       "Finance Bill 2024-25 — IR lifetime cap reduced £10M → £1M from 30 Oct 2024"}

   ;; --- SSE threshold --------------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "UK.CGT.SSE.min-holding-fraction"]
    :parameter-value/effective-from #inst "2002-04-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "TCGA Sch 7AC — SSE threshold 10 % ordinary share capital (also 10 % profits / assets); 12-month hold in 6 years preceding disposal"}])

;; ============================================================================
;; Provisions — UK CGT has very few rule-shaped provisions
;; ============================================================================
;;
;; Most UK CGT logic is procedural (lifetime-cap allocation, AEA, lane
;; classification by asset-class + claimed-exemption), so lives in the
;; provider. The substrate's `:provision` table is for predicate-gated
;; consequences that flow through `apply-provisions`. The SSE exemption
;; is the canonical case: a flagged disposal drops out of the chargeable
;; pool — but the filter happens in the provider (it touches the
;; disposal stream, not a base / a tax) so SSE too lives provider-side.
;;
;; If a future feature needs `apply-provisions`-style folding (e.g. a
;; Soli-style CGT surtax, or a base-side anti-avoidance addition keyed
;; on the disposal counterparty), it joins this seq.
;; ============================================================================

(def provisions
  "UK CGT provisions — empty in v1. Procedural rules live in the
   provider; see ns docstring for the rationale."
  [])

;; ============================================================================
;; Install! — transact parameters (+ provisions when there are any)
;; ============================================================================

(defn install!
  "Install UK CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (when (seq provisions)
    (d/transact conn provisions)))

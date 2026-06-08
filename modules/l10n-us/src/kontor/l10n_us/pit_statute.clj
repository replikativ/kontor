(ns kontor.l10n-us.pit-statute
  "US federal personal income tax — Form 1040 / IRC §1 — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `us-personal-income-tax-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. The largest
   data volume in — 168 bracket rows (4 filing statuses × 7
   bands × 6 years 2020-2025) + 24 standard-deduction values + ~10 CTC
   value rows. Mirrors `kontor.l10n-at.pit-statute` structurally (one
   bracket-scale parameter per per-status variant) but shipping four
   bracket-scale parameters keyed by `:tax-unit :filing-status`
   instead of AT's single parameter.

   The encoding splits along the substrate seams:

   - **Parameters** — 4 bracket-scale parameters (one per filing
     status: `:single` / `:mfj` / `:mfs` / `:hoh`), 4 standard-
     deduction parameters (one per filing status), and the §24 CTC /
     ACTC parameters (per-child caps + earned-income floor / rate).

   - **Provisions** — 6 provisions:
       - US-IRC-§63-standard-deduction — applies the per-status std
         deduction unless `:tax-unit :itemized? true`.
       - US-IRC-§63-itemized-deductions — applies consumer-supplied
         itemized total when `:tax-unit :itemized? true`.
       - US-IRC-§24-CTC-non-refundable — §24 non-refundable child tax
         credit at $2 000/child up to tax-before-credits.
       - US-IRC-§24-CTC-refundable-ACTC — refundable ACTC residual
         capped at $1 700/child AND 15 % × (earned-income − $2 500).
       - US-IRC-§1-cgt-pit-base-additions — ST cap gains + §1245/§1250
         individual recapture (cgt-provider `:pit-base-additions` lane).
       - US-IRC-§1-investment-pit-base-additions — ordinary dividends +
         interest + §163(d) (investment-income-provider lane).

   - **Scoping** — all provisions scoped to `:pit` via `[:eq :component
     :pit]`, matching the FR `:is`-component discipline.

   ## Filing-status-keyed brackets

   The §1(j) bracket schedule is filing-status-dependent (single / MFJ /
   MFS / HoH). Each status gets its own `:bracket-scale` parameter
   with 7 bands × 6 years = 42 bracket rows. The provider reads
   `:tax-unit :filing-status` (default `:single`), picks the right
   `US.PIT.§1.brackets-<status>` parameter via
   `parameter-brackets-at`, constructs a `ts/progressive` schedule.
   No `:formula` escape hatch needed; no compute-fn for the schedule
   itself (recipe §A-10 anti-pattern avoided).

   ## §1411 NIIT stays on cgt / investment-income providers

   The PIT provider does NOT consume nor emit §1411 NIIT. NIIT is a
   3.8 % surtax on net investment income (NOT on ordinary income); it
   is OWNED by `investment-income-provider` (default) and emitted as
   a separate `TaxReturnFacts` component. The cgt-provider opts out via
   `:emit-niit? false` when composed alongside the investment-income
   provider. v1 preserves this 'one surtax, one owner' discipline.

   ## LT capital gain / qualified dividends / §1250-unrecaptured stay separate

   LT capital gain, qualified dividends, and §1250-unrecaptured each
   live on their own provider's components (with their own preferential
   schedules); they are NOT consumed by PIT via `:base-additions`. The
   consumer composes by reading `(:components facts)` from all four
   providers (PIT + CGT + investment-income + sales tax).

   ## Out of scope for v1 ( slice)

   - **§32 EITC (Earned Income Credit).** Income-dependent compute-fn
     with 4 income ranges × 3 child-count tiers × bipartite phase-in /
     phase-out per filing status — ~100 LOC + ~30 parameter rows. v1
     consumer pre-computes via `:inputs :credits`; deferred to v1.x.
   - **§199A QBI deduction.** Pass-through 20 % deduction with W-2 /
     UBIA basis limitations + SSTB carve-outs. Consumer pre-computes
     and folds via consumer-supplied `:base-transform`.
   - **§55-§59 individual AMT.** Few SMB owner-operators trip the
     thresholds post-TCJA. Consumer pre-computes if applicable.
   - **State PIT.** 41 states have an income tax; out of substrate v1
     (Q5.3 federal-only). The CA federal+provincial pattern is the
     structural template if/when state PIT is opened.
   - **TY 2021 ARPA CTC expansion.** Temporary $3 000/$3 600 fully-
     refundable CTC. Consumer reconstructing TY 2021 owns parameter
     override.

   ## TCJA 2025-12-31 sunset

   §1(j) brackets, §24 CTC $2 000, §63 std deduction levels sunset
   2025-12-31 unless Congress extends. The substrate ships TY 2020-2025
   bracket sets with the 2025 row open-ended (`:effective-from
   2025-01-01` only; no `:effective-until`). The §24 CTC parameter
   value carries `:effective-until #inst \"2026-01-01\"` — assessing
   as-of 2026 onwards will find no CTC value (intentional loud-fail;
   consumer or maintainer ships the post-sunset row once Congress
   acts).

   ## Audit-doc seam (TODO)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   ~50 LOC kernel sweep tracked as a follow-up.

   ## Citations

   `law.cornell.edu/uscode/text/26` for the IRC (the stable, public-
   domain Cornell mirror); IRS Rev. Procs. 2019-44 / 2020-45 / 2021-45
   / 2022-38 / 2023-34 / 2024-40 for the annual inflation-adjusted
   bracket thresholds + standard deduction values + ACTC cap."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "US PIT parameter definitions — 12 parameters: 4 bracket-scale (one
   per filing status), 4 standard-deduction amount-money (one per
   filing status), 4 §24 CTC / ACTC scalars."
  [;; --- §1(j) progressive bracket scales (4, one per filing status) ---
   {:kontor.parameter/code         "US.PIT.§1.brackets-single"
    :kontor.parameter/label        "IRC §1(c)+(j) — single-filer 7-band progressive bracket schedule"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1"}

   {:kontor.parameter/code         "US.PIT.§1.brackets-mfj"
    :kontor.parameter/label        "IRC §1(a)+(j) — married-filing-jointly + surviving-spouse 7-band progressive bracket schedule"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1"}

   {:kontor.parameter/code         "US.PIT.§1.brackets-mfs"
    :kontor.parameter/label        "IRC §1(d)+(j) — married-filing-separately 7-band progressive bracket schedule"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1"}

   {:kontor.parameter/code         "US.PIT.§1.brackets-hoh"
    :kontor.parameter/label        "IRC §1(b)+(j) — head-of-household 7-band progressive bracket schedule"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1"}

   ;; --- §63 standard deduction (4, one per filing status) ---
   {:kontor.parameter/code         "US.PIT.§63.standard-deduction-single"
    :kontor.parameter/label        "IRC §63(c) — single-filer standard deduction (annual; inflation-indexed)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/63"}

   {:kontor.parameter/code         "US.PIT.§63.standard-deduction-mfj"
    :kontor.parameter/label        "IRC §63(c) — married-filing-jointly + surviving-spouse standard deduction"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/63"}

   {:kontor.parameter/code         "US.PIT.§63.standard-deduction-mfs"
    :kontor.parameter/label        "IRC §63(c) — married-filing-separately standard deduction"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/63"}

   {:kontor.parameter/code         "US.PIT.§63.standard-deduction-hoh"
    :kontor.parameter/label        "IRC §63(c) — head-of-household standard deduction"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/63"}

   ;; --- §24 CTC / ACTC scalars ---
   {:kontor.parameter/code         "US.PIT.§24.ctc-per-child"
    :kontor.parameter/label        "IRC §24(a) — Child Tax Credit per qualifying child under 17 (annual amount; TCJA-baseline $2 000)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/24"}

   {:kontor.parameter/code         "US.PIT.§24.actc-per-child"
    :kontor.parameter/label        "IRC §24(d)(1) — Additional Child Tax Credit (refundable) per qualifying child (annual; inflation-indexed)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/24"}

   {:kontor.parameter/code         "US.PIT.§24.actc-earned-income-floor"
    :kontor.parameter/label        "IRC §24(d)(1)(B)(i) — ACTC earned-income floor (TCJA $2 500)"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/24"}

   {:kontor.parameter/code         "US.PIT.§24.actc-earned-income-rate"
    :kontor.parameter/label        "IRC §24(d)(1)(B)(ii) — ACTC 15 % of (earned-income − floor) cap rate"
    :kontor.parameter/jurisdiction :us
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/24"}])

(def parameter-values
  "US PIT scalar parameter values with statutory effective windows.
   Standard deductions ship 6 years per filing status (TY 2020-2025);
   §24 CTC ships TCJA-baseline $2 000 with `:effective-until 2026-01-01`
   for the sunset cliff; ACTC per-child cap ships its 4-step inflation
   history ($1 400 [2018-2021] / $1 500 [2022] / $1 600 [2023] /
   $1 700 [2024+]); the ACTC floor + rate are TCJA-stable.

   Sources: IRS Rev. Proc. 2019-44 / 2020-45 / 2021-45 / 2022-38 /
   2023-34 / 2024-40 §3.16 (standard deduction) + §3.04 (ACTC cap).
   §24 TCJA basis Pub L 115-97 §11022."
  (vec
   (concat
    ;; --- Standard deduction by filing status × 6 years ---
    ;; SINGLE
    [{:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from  #inst "2020-01-01"
      :kontor.parameter-value/effective-until #inst "2021-01-01"
      :kontor.parameter-value/decimal-value   12400M
      :kontor.parameter-value/citation        "IRC §63(c) — single std deduction TY 2020 (IRS Rev. Proc. 2019-44 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from  #inst "2021-01-01"
      :kontor.parameter-value/effective-until #inst "2022-01-01"
      :kontor.parameter-value/decimal-value   12550M
      :kontor.parameter-value/citation        "IRC §63(c) — single std deduction TY 2021 (IRS Rev. Proc. 2020-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from  #inst "2022-01-01"
      :kontor.parameter-value/effective-until #inst "2023-01-01"
      :kontor.parameter-value/decimal-value   12950M
      :kontor.parameter-value/citation        "IRC §63(c) — single std deduction TY 2022 (IRS Rev. Proc. 2021-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from  #inst "2023-01-01"
      :kontor.parameter-value/effective-until #inst "2024-01-01"
      :kontor.parameter-value/decimal-value   13850M
      :kontor.parameter-value/citation        "IRC §63(c) — single std deduction TY 2023 (IRS Rev. Proc. 2022-38 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from  #inst "2024-01-01"
      :kontor.parameter-value/effective-until #inst "2025-01-01"
      :kontor.parameter-value/decimal-value   14600M
      :kontor.parameter-value/citation        "IRC §63(c) — single std deduction TY 2024 (IRS Rev. Proc. 2023-34 §3.16)"}
     {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.PIT.§63.standard-deduction-single"]
      :kontor.parameter-value/effective-from #inst "2025-01-01"
      :kontor.parameter-value/decimal-value  15000M
      :kontor.parameter-value/citation       "IRC §63(c) — single std deduction TY 2025 (IRS Rev. Proc. 2024-40 §3.16)"}

     ;; MFJ
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from  #inst "2020-01-01"
      :kontor.parameter-value/effective-until #inst "2021-01-01"
      :kontor.parameter-value/decimal-value   24800M
      :kontor.parameter-value/citation        "IRC §63(c) — MFJ std deduction TY 2020 (IRS Rev. Proc. 2019-44 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from  #inst "2021-01-01"
      :kontor.parameter-value/effective-until #inst "2022-01-01"
      :kontor.parameter-value/decimal-value   25100M
      :kontor.parameter-value/citation        "IRC §63(c) — MFJ std deduction TY 2021 (IRS Rev. Proc. 2020-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from  #inst "2022-01-01"
      :kontor.parameter-value/effective-until #inst "2023-01-01"
      :kontor.parameter-value/decimal-value   25900M
      :kontor.parameter-value/citation        "IRC §63(c) — MFJ std deduction TY 2022 (IRS Rev. Proc. 2021-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from  #inst "2023-01-01"
      :kontor.parameter-value/effective-until #inst "2024-01-01"
      :kontor.parameter-value/decimal-value   27700M
      :kontor.parameter-value/citation        "IRC §63(c) — MFJ std deduction TY 2023 (IRS Rev. Proc. 2022-38 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from  #inst "2024-01-01"
      :kontor.parameter-value/effective-until #inst "2025-01-01"
      :kontor.parameter-value/decimal-value   29200M
      :kontor.parameter-value/citation        "IRC §63(c) — MFJ std deduction TY 2024 (IRS Rev. Proc. 2023-34 §3.16)"}
     {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfj"]
      :kontor.parameter-value/effective-from #inst "2025-01-01"
      :kontor.parameter-value/decimal-value  30000M
      :kontor.parameter-value/citation       "IRC §63(c) — MFJ std deduction TY 2025 (IRS Rev. Proc. 2024-40 §3.16)"}

     ;; MFS
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from  #inst "2020-01-01"
      :kontor.parameter-value/effective-until #inst "2021-01-01"
      :kontor.parameter-value/decimal-value   12400M
      :kontor.parameter-value/citation        "IRC §63(c) — MFS std deduction TY 2020 (IRS Rev. Proc. 2019-44 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from  #inst "2021-01-01"
      :kontor.parameter-value/effective-until #inst "2022-01-01"
      :kontor.parameter-value/decimal-value   12550M
      :kontor.parameter-value/citation        "IRC §63(c) — MFS std deduction TY 2021 (IRS Rev. Proc. 2020-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from  #inst "2022-01-01"
      :kontor.parameter-value/effective-until #inst "2023-01-01"
      :kontor.parameter-value/decimal-value   12950M
      :kontor.parameter-value/citation        "IRC §63(c) — MFS std deduction TY 2022 (IRS Rev. Proc. 2021-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from  #inst "2023-01-01"
      :kontor.parameter-value/effective-until #inst "2024-01-01"
      :kontor.parameter-value/decimal-value   13850M
      :kontor.parameter-value/citation        "IRC §63(c) — MFS std deduction TY 2023 (IRS Rev. Proc. 2022-38 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from  #inst "2024-01-01"
      :kontor.parameter-value/effective-until #inst "2025-01-01"
      :kontor.parameter-value/decimal-value   14600M
      :kontor.parameter-value/citation        "IRC §63(c) — MFS std deduction TY 2024 (IRS Rev. Proc. 2023-34 §3.16)"}
     {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.PIT.§63.standard-deduction-mfs"]
      :kontor.parameter-value/effective-from #inst "2025-01-01"
      :kontor.parameter-value/decimal-value  15000M
      :kontor.parameter-value/citation       "IRC §63(c) — MFS std deduction TY 2025 (IRS Rev. Proc. 2024-40 §3.16)"}

     ;; HoH
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from  #inst "2020-01-01"
      :kontor.parameter-value/effective-until #inst "2021-01-01"
      :kontor.parameter-value/decimal-value   18650M
      :kontor.parameter-value/citation        "IRC §63(c) — HoH std deduction TY 2020 (IRS Rev. Proc. 2019-44 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from  #inst "2021-01-01"
      :kontor.parameter-value/effective-until #inst "2022-01-01"
      :kontor.parameter-value/decimal-value   18800M
      :kontor.parameter-value/citation        "IRC §63(c) — HoH std deduction TY 2021 (IRS Rev. Proc. 2020-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from  #inst "2022-01-01"
      :kontor.parameter-value/effective-until #inst "2023-01-01"
      :kontor.parameter-value/decimal-value   19400M
      :kontor.parameter-value/citation        "IRC §63(c) — HoH std deduction TY 2022 (IRS Rev. Proc. 2021-45 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from  #inst "2023-01-01"
      :kontor.parameter-value/effective-until #inst "2024-01-01"
      :kontor.parameter-value/decimal-value   20800M
      :kontor.parameter-value/citation        "IRC §63(c) — HoH std deduction TY 2023 (IRS Rev. Proc. 2022-38 §3.16)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from  #inst "2024-01-01"
      :kontor.parameter-value/effective-until #inst "2025-01-01"
      :kontor.parameter-value/decimal-value   21900M
      :kontor.parameter-value/citation        "IRC §63(c) — HoH std deduction TY 2024 (IRS Rev. Proc. 2023-34 §3.16)"}
     {:kontor.parameter-value/parameter      [:kontor.parameter/code "US.PIT.§63.standard-deduction-hoh"]
      :kontor.parameter-value/effective-from #inst "2025-01-01"
      :kontor.parameter-value/decimal-value  22500M
      :kontor.parameter-value/citation       "IRC §63(c) — HoH std deduction TY 2025 (IRS Rev. Proc. 2024-40 §3.16)"}]

    ;; --- §24 CTC per-child cap (TCJA $2 000 with sunset cliff) ---
    [{:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.ctc-per-child"]
      :kontor.parameter-value/effective-from  #inst "2018-01-01"
      :kontor.parameter-value/effective-until #inst "2026-01-01"
      :kontor.parameter-value/decimal-value   2000M
      :kontor.parameter-value/citation        "IRC §24(a) (TCJA §11022, Pub L 115-97) — $2 000/child TCJA-baseline 2018-2025; sunsets 2025-12-31 absent extension"}

     ;; --- §24 ACTC per-child cap (refundable, inflation-indexed) ---
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-per-child"]
      :kontor.parameter-value/effective-from  #inst "2018-01-01"
      :kontor.parameter-value/effective-until #inst "2022-01-01"
      :kontor.parameter-value/decimal-value   1400M
      :kontor.parameter-value/citation        "IRC §24(d)(1) (TCJA §11022) — $1 400 ACTC refundable cap TY 2018-2021"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-per-child"]
      :kontor.parameter-value/effective-from  #inst "2022-01-01"
      :kontor.parameter-value/effective-until #inst "2023-01-01"
      :kontor.parameter-value/decimal-value   1500M
      :kontor.parameter-value/citation        "IRC §24(d)(1) — $1 500 ACTC refundable cap TY 2022 (IRS Rev. Proc. 2021-45 §3.04)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-per-child"]
      :kontor.parameter-value/effective-from  #inst "2023-01-01"
      :kontor.parameter-value/effective-until #inst "2024-01-01"
      :kontor.parameter-value/decimal-value   1600M
      :kontor.parameter-value/citation        "IRC §24(d)(1) — $1 600 ACTC refundable cap TY 2023 (IRS Rev. Proc. 2022-38 §3.04)"}
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-per-child"]
      :kontor.parameter-value/effective-from  #inst "2024-01-01"
      :kontor.parameter-value/effective-until #inst "2026-01-01"
      :kontor.parameter-value/decimal-value   1700M
      :kontor.parameter-value/citation        "IRC §24(d)(1) — $1 700 ACTC refundable cap TY 2024-2025 (IRS Rev. Proc. 2023-34 + 2024-40 §3.04)"}

     ;; --- §24 ACTC earned-income floor + rate (TCJA-stable) ---
     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-earned-income-floor"]
      :kontor.parameter-value/effective-from  #inst "2018-01-01"
      :kontor.parameter-value/effective-until #inst "2026-01-01"
      :kontor.parameter-value/decimal-value   2500M
      :kontor.parameter-value/citation        "IRC §24(d)(1)(B)(i) (TCJA §11022) — $2 500 ACTC earned-income floor 2018-2025"}

     {:kontor.parameter-value/parameter       [:kontor.parameter/code "US.PIT.§24.actc-earned-income-rate"]
      :kontor.parameter-value/effective-from  #inst "2018-01-01"
      :kontor.parameter-value/effective-until #inst "2026-01-01"
      :kontor.parameter-value/decimal-value   0.15M
      :kontor.parameter-value/citation        "IRC §24(d)(1)(B)(ii) (TCJA §11022) — 15 % ACTC earned-income cap rate 2018-2025"}])))

;; ============================================================================
;; §1(j) progressive bracket scales (4 statuses × 6 years × 7 bands = 168 rows)
;; ============================================================================

(def parameter-brackets
  "US §1(j) post-TCJA 7-band progressive bracket schedule × 4 filing
   statuses × 6 years (TY 2020-2025). Each row's `[:effective-from,
   :effective-until)` half-open window selects exactly one year's set
   for any `:as-of`. The TY 2025 set is open-ended (no
   `:effective-until`) — the TCJA §1(j) sunset 2025-12-31 is intentional
   loud-fail behavior; consumer or maintainer ships the post-sunset row
   once Congress acts.

   Rates stable post-TCJA (10/12/22/24/32/35/37 %); thresholds adjust
   annually for inflation per §1(f) (IRS Rev. Procs.). 7 bands × 4
   statuses × 6 years = 168 rows."
  (vec
   (for [[code k1 k2 k3 k4 k5 k6 effective-from effective-until citation]
         [;; --- SINGLE (IRC §1(c)+(j)) ---
          ["US.PIT.§1.brackets-single" 9875M  40125M  85525M  163300M 207350M 518400M
           #inst "2020-01-01" #inst "2021-01-01"
           "IRC §1(c)+(j) — single brackets TY 2020 (IRS Rev. Proc. 2019-44 §3.01 Table 3)"]
          ["US.PIT.§1.brackets-single" 9950M  40525M  86375M  164925M 209425M 523600M
           #inst "2021-01-01" #inst "2022-01-01"
           "IRC §1(c)+(j) — single brackets TY 2021 (IRS Rev. Proc. 2020-45 §3.01 Table 3)"]
          ["US.PIT.§1.brackets-single" 10275M 41775M  89075M  170050M 215950M 539900M
           #inst "2022-01-01" #inst "2023-01-01"
           "IRC §1(c)+(j) — single brackets TY 2022 (IRS Rev. Proc. 2021-45 §3.01 Table 3)"]
          ["US.PIT.§1.brackets-single" 11000M 44725M  95375M  182100M 231250M 578125M
           #inst "2023-01-01" #inst "2024-01-01"
           "IRC §1(c)+(j) — single brackets TY 2023 (IRS Rev. Proc. 2022-38 §3.01 Table 3)"]
          ["US.PIT.§1.brackets-single" 11600M 47150M  100525M 191950M 243725M 609350M
           #inst "2024-01-01" #inst "2025-01-01"
           "IRC §1(c)+(j) — single brackets TY 2024 (IRS Rev. Proc. 2023-34 §3.01 Table 3)"]
          ["US.PIT.§1.brackets-single" 11925M 48475M  103350M 197300M 250525M 626350M
           #inst "2025-01-01" nil
           "IRC §1(c)+(j) — single brackets TY 2025 (IRS Rev. Proc. 2024-40 §3.01 Table 3)"]

          ;; --- MFJ (IRC §1(a)+(j)) ---
          ["US.PIT.§1.brackets-mfj" 19750M 80250M  171050M 326600M 414700M 622050M
           #inst "2020-01-01" #inst "2021-01-01"
           "IRC §1(a)+(j) — MFJ brackets TY 2020 (IRS Rev. Proc. 2019-44 §3.01 Table 1)"]
          ["US.PIT.§1.brackets-mfj" 19900M 81050M  172750M 329850M 418850M 628300M
           #inst "2021-01-01" #inst "2022-01-01"
           "IRC §1(a)+(j) — MFJ brackets TY 2021 (IRS Rev. Proc. 2020-45 §3.01 Table 1)"]
          ["US.PIT.§1.brackets-mfj" 20550M 83550M  178150M 340100M 431900M 647850M
           #inst "2022-01-01" #inst "2023-01-01"
           "IRC §1(a)+(j) — MFJ brackets TY 2022 (IRS Rev. Proc. 2021-45 §3.01 Table 1)"]
          ["US.PIT.§1.brackets-mfj" 22000M 89450M  190750M 364200M 462500M 693750M
           #inst "2023-01-01" #inst "2024-01-01"
           "IRC §1(a)+(j) — MFJ brackets TY 2023 (IRS Rev. Proc. 2022-38 §3.01 Table 1)"]
          ["US.PIT.§1.brackets-mfj" 23200M 94300M  201050M 383900M 487450M 731200M
           #inst "2024-01-01" #inst "2025-01-01"
           "IRC §1(a)+(j) — MFJ brackets TY 2024 (IRS Rev. Proc. 2023-34 §3.01 Table 1)"]
          ["US.PIT.§1.brackets-mfj" 23850M 96950M  206700M 394600M 501050M 751600M
           #inst "2025-01-01" nil
           "IRC §1(a)+(j) — MFJ brackets TY 2025 (IRS Rev. Proc. 2024-40 §3.01 Table 1)"]

          ;; --- MFS (IRC §1(d)+(j)) — top-of-35 cliff at MFJ/2 ---
          ["US.PIT.§1.brackets-mfs" 9875M  40125M  85525M  163300M 207350M 311025M
           #inst "2020-01-01" #inst "2021-01-01"
           "IRC §1(d)+(j) — MFS brackets TY 2020 (IRS Rev. Proc. 2019-44 §3.01 Table 4)"]
          ["US.PIT.§1.brackets-mfs" 9950M  40525M  86375M  164925M 209425M 314150M
           #inst "2021-01-01" #inst "2022-01-01"
           "IRC §1(d)+(j) — MFS brackets TY 2021 (IRS Rev. Proc. 2020-45 §3.01 Table 4)"]
          ["US.PIT.§1.brackets-mfs" 10275M 41775M  89075M  170050M 215950M 323925M
           #inst "2022-01-01" #inst "2023-01-01"
           "IRC §1(d)+(j) — MFS brackets TY 2022 (IRS Rev. Proc. 2021-45 §3.01 Table 4)"]
          ["US.PIT.§1.brackets-mfs" 11000M 44725M  95375M  182100M 231250M 346875M
           #inst "2023-01-01" #inst "2024-01-01"
           "IRC §1(d)+(j) — MFS brackets TY 2023 (IRS Rev. Proc. 2022-38 §3.01 Table 4)"]
          ["US.PIT.§1.brackets-mfs" 11600M 47150M  100525M 191950M 243725M 365600M
           #inst "2024-01-01" #inst "2025-01-01"
           "IRC §1(d)+(j) — MFS brackets TY 2024 (IRS Rev. Proc. 2023-34 §3.01 Table 4)"]
          ["US.PIT.§1.brackets-mfs" 11925M 48475M  103350M 197300M 250525M 375800M
           #inst "2025-01-01" nil
           "IRC §1(d)+(j) — MFS brackets TY 2025 (IRS Rev. Proc. 2024-40 §3.01 Table 4)"]

          ;; --- HoH (IRC §1(b)+(j)) ---
          ["US.PIT.§1.brackets-hoh" 14100M 53700M  85500M  163300M 207350M 518400M
           #inst "2020-01-01" #inst "2021-01-01"
           "IRC §1(b)+(j) — HoH brackets TY 2020 (IRS Rev. Proc. 2019-44 §3.01 Table 2)"]
          ["US.PIT.§1.brackets-hoh" 14200M 54200M  86350M  164900M 209400M 523600M
           #inst "2021-01-01" #inst "2022-01-01"
           "IRC §1(b)+(j) — HoH brackets TY 2021 (IRS Rev. Proc. 2020-45 §3.01 Table 2)"]
          ["US.PIT.§1.brackets-hoh" 14650M 55900M  89050M  170050M 215950M 539900M
           #inst "2022-01-01" #inst "2023-01-01"
           "IRC §1(b)+(j) — HoH brackets TY 2022 (IRS Rev. Proc. 2021-45 §3.01 Table 2)"]
          ["US.PIT.§1.brackets-hoh" 15700M 59850M  95350M  182100M 231250M 578100M
           #inst "2023-01-01" #inst "2024-01-01"
           "IRC §1(b)+(j) — HoH brackets TY 2023 (IRS Rev. Proc. 2022-38 §3.01 Table 2)"]
          ["US.PIT.§1.brackets-hoh" 16550M 63100M  100500M 191950M 243700M 609350M
           #inst "2024-01-01" #inst "2025-01-01"
           "IRC §1(b)+(j) — HoH brackets TY 2024 (IRS Rev. Proc. 2023-34 §3.01 Table 2)"]
          ["US.PIT.§1.brackets-hoh" 17000M 64850M  103350M 197300M 250500M 626350M
           #inst "2025-01-01" nil
           "IRC §1(b)+(j) — HoH brackets TY 2025 (IRS Rev. Proc. 2024-40 §3.01 Table 2)"]]
         [idx rate upper-sym]
         [;; band 0: 10 % up to k1
          [0 0.10M :k1]
          [1 0.12M :k2]
          [2 0.22M :k3]
          [3 0.24M :k4]
          [4 0.32M :k5]
          [5 0.35M :k6]
          ;; band 6: 37 % open top
          [6 0.37M :open]]]
     (let [uppers {:k1 k1 :k2 k2 :k3 k3 :k4 k4 :k5 k5 :k6 k6 :open nil}
           upper  (get uppers upper-sym)]
       (cond->
        {:kontor.parameter-bracket/parameter      [:kontor.parameter/code code]
         :kontor.parameter-bracket/index          idx
         :kontor.parameter-bracket/rate           rate
         :kontor.parameter-bracket/effective-from effective-from
         :kontor.parameter-bracket/_citation      citation}
         upper           (assoc :kontor.parameter-bracket/upper upper)
         effective-until (assoc :kontor.parameter-bracket/effective-until effective-until))))))

(def parameter-brackets-rows
  "Transactable bracket rows (synthetic `_citation` key filtered out)."
  (mapv #(dissoc % :kontor.parameter-bracket/_citation) parameter-brackets))

;; ============================================================================
;; Provisions — US PIT statute as :provision data
;; ============================================================================

(def provisions
  "US PIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:pit` in v1)
   and use vector fact-keys for the consumer-supplied facts; compute-fns
   (`:us-std-deduction-amount`, `:us-ctc-non-refundable`,
   `:us-ctc-refundable`) are registered by `kontor.l10n-us.pit-provider`
   at namespace load (recipe §A-5)."
  [;; ----------------------------------------------------------------
   ;; §63 standard deduction (when NOT itemized)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "US-IRC-§63-standard-deduction"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "IRC §63(c) — standard deduction (when not itemized)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/63"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :pit]
                                              [:not [:eq [:tax-unit :itemized?] true]]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :us-§63-std-deduction
                                              :label       "§63(c) standard deduction"
                                              :amount-from :compute-fn
                                              :fn          :us-std-deduction-amount})}

   ;; ----------------------------------------------------------------
   ;; §63 itemized deductions (consumer-supplied; fires only when itemized? true)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "US-IRC-§63-itemized-deductions"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "IRC §63 — itemized deductions (consumer pre-computed)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/63"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       110
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :pit]
                                              [:eq [:tax-unit :itemized?] true]
                                              [:gt [:inputs :itemized-deductions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :us-§63-itemized
                                              :label       "§63 itemized deductions (consumer pre-computed)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :itemized-deductions]})}

   ;; ----------------------------------------------------------------
   ;; §1 base-add — CGT individual lane (ST cap gain + ordinary recapture)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "US-IRC-§1-cgt-pit-base-additions"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "IRC §1 + §1222 ST cap gain + §1245/§1250 individual recapture (cgt-provider lane)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/1"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       150
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :pit]
                                              [:gt [:inputs :cgt-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :us-cgt-pit-base-additions
                                              :label       "§1222 ST cap gain + §1245/§1250 ordinary recapture (cgt-provider lane)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; §1 base-add — investment-income ordinary lane (may be negative w/ §163(d))
   ;; ----------------------------------------------------------------
   ;; The investment-income-provider emits ordinary dividends +
   ;; interest as positive `:pit-base-additions` plus the §163(d)
   ;; deduction as negative `:pit-base-additions`. The consumer sums
   ;; the two into a single scalar. We use `[:not [:eq ... 0M]]` (not
   ;; `[:gt ...]`) because the net may be negative.
   {:kontor.provision/code           "US-IRC-§1-investment-pit-base-additions"
    :kontor.provision/jurisdiction   :us
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "IRC §1 + §61 — ordinary investment income + §163(d) deduction (investment-income-provider lane; net may be negative)"
    :kontor.provision/citation       "https://www.law.cornell.edu/uscode/text/26/1"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       160
    ;; Gate on the consumer signalling the lane explicitly:
    ;; `[:gt :investment-pit-base-additions-signal? true]` lets the
    ;; consumer opt-in even for a NET-zero or net-NEGATIVE lane (which
    ;; the §163(d) deduction can produce). `[:not [:eq nil 0M]]` would
    ;; fire silently when the lane was simply absent — guard with a
    ;; presence check instead.
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :pit]
                                              [:or
                                               [:gt [:inputs :investment-pit-base-additions] 0M]
                                               [:lt [:inputs :investment-pit-base-additions] 0M]]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :us-investment-pit-base-additions
                                              :label       "Ordinary investment income + §163(d) deduction (investment-income-provider lane)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; §24 Child Tax Credit (non-refundable portion)
   ;; ----------------------------------------------------------------
   ;; min(tax-before-credits, $2 000 × qualifying-children). The
   ;; compute-fn reads `:running` (the running tax post-schedule),
   ;; the children count from `:tax-unit`, and the CTC per-child
   ;; amount from the parameter.
   {:kontor.provision/code            "US-IRC-§24-CTC-non-refundable"
    :kontor.provision/jurisdiction    :us
    :kontor.provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title           "IRC §24(a) — Child Tax Credit (non-refundable portion, $2 000/child TCJA-baseline)"
    :kontor.provision/citation        "https://www.law.cornell.edu/uscode/text/26/24"
    :kontor.provision/effective-from  #inst "2018-01-01"
    :kontor.provision/effective-until #inst "2026-01-01"
    :kontor.provision/priority        200
    :kontor.provision/condition       (pr-str [:and
                                               [:eq :component :pit]
                                               [:gt [:tax-unit :qualifying-children-under-17] 0]])
    :kontor.provision/consequence     (pr-str {:op          :credit
                                               :code        :us-§24-ctc
                                               :label       "§24 Child Tax Credit (non-refundable)"
                                               :refundable? false
                                               :amount-from :compute-fn
                                               :fn          :us-ctc-non-refundable})}

   ;; ----------------------------------------------------------------
   ;; §24 Additional Child Tax Credit (refundable portion / ACTC)
   ;; ----------------------------------------------------------------
   ;; min(actc-residual, $1 700 × children, 15 % × (earned-income − $2 500)).
   ;; The compute-fn reads `:running`, `:tax-unit :qualifying-children-under-17`,
   ;; `:inputs :earned-income`, and the three ACTC parameters.
   {:kontor.provision/code            "US-IRC-§24-CTC-refundable-ACTC"
    :kontor.provision/jurisdiction    :us
    :kontor.provision/concept         [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title           "IRC §24(d) — Additional Child Tax Credit (refundable, ACTC; $1 700/child for 2024-2025)"
    :kontor.provision/citation        "https://www.law.cornell.edu/uscode/text/26/24"
    :kontor.provision/effective-from  #inst "2018-01-01"
    :kontor.provision/effective-until #inst "2026-01-01"
    :kontor.provision/priority        210
    :kontor.provision/condition       (pr-str [:and
                                               [:eq :component :pit]
                                               [:gt [:tax-unit :qualifying-children-under-17] 0]])
    :kontor.provision/consequence     (pr-str {:op          :credit
                                               :code        :us-§24-actc
                                               :label       "§24(d) Additional Child Tax Credit (refundable ACTC)"
                                               :refundable? true
                                               :amount-from :compute-fn
                                               :fn          :us-ctc-refundable})}])

;; ============================================================================
;; Install! — transact parameters + values + brackets + provisions
;; ============================================================================

(defn- bracket-row-already-present?
  "True iff a `:parameter-bracket` row with the same `(parameter-code,
   index, effective-from)` triple is already in `db`. Used to make the
   bracket install idempotent — `:parameter-bracket` carries no
   `:db/unique :db.unique/identity` attr in the kernel schema, so the
   provider must do the dedup itself.

   Mirrors `kontor.l10n-fr.cit-statute/bracket-row-already-present?` +
   `kontor.l10n-at.pit-statute/bracket-row-already-present?`."
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
  "Install US PIT statute (parameters + parameter-values + bracket rows
   + provisions) into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs (upsert on
   re-install); parameter-brackets get explicit dedup via
   `bracket-row-already-present?` since the kernel schema does not
   carry a `:db/unique` on them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (let [db (d/db conn)
        new-brackets (remove #(bracket-row-already-present? db %)
                             parameter-brackets-rows)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))

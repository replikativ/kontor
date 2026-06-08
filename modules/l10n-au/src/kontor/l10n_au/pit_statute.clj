(ns kontor.l10n-au.pit-statute
  "AU personal income tax — individual income tax + Medicare Levy +
   LITO — encoded as `kontor.tax.statute` data per ADR-101. Migrates
   the record-shape `au-income-tax-provider` (in
   `period_tax_provider.clj`) to statute-as-data — slice
. Mirrors `kontor.l10n-fr.cit-statute` (the
   `parameter-brackets`-driven schedule pattern) + the AT `pit-statute`
   bitemporal-bracket sweep; Medicare Levy is modelled as a `:surtax`
   provision with a compute-fn (the substrate-clean alternative to
   the legacy `:sum-of` schedule shape).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the ITRA 1986 Sch 7
     5-band progressive bracket scale (one bracket parameter with two
     yearly sets — pre-Stage-3 from 2020-07-01 + post-Stage-3 from
     2024-07-01) + the Medicare Levy rate / shade-rate / low-income
     threshold (indexed annually) + the LITO 6 thresholds (stable
     since 2022-07-01 reform).

   - **Provisions** (per-jurisdiction rules) — 7 provisions:
       - AU-MedicareLevyAct-§7-medicare-levy — `:surtax` 2 % flat
         with low-income shade-in (compute-fn).
       - AU-ITAA-1997-§159N-LITO — `:non-refundable-credit` 3-band
         shade (compute-fn).
       - AU-cgt-pit-base-additions — `:base-add` lane reading
         cgt-provider's `:pit-base-additions` (post-Div-115-discount
         post-cascade net gain).
       - AU-investment-pit-base-additions — `:base-add` lane reading
         investment-income-provider's `:pit-base-additions` (franking
         gross-up + unfranked + foreign + interest).
       - AU-ITAA-1997-§207-45-franking-credit — `:refundable-credit`
         (refundable for :individual + :super-fund + :fixed-trust
         holders4).
       - AU-ITAA-1997-§770-10-fito — `:non-refundable-credit` for
         FITO.
       - AU-ITAA-1936-PtVA-tfn-prepaid — `:refundable-credit` for
         TFN-withheld prepaid amounts.

   - **Scoping** — all provisions scoped to `:au-pit` via `[:eq
     :component :au-pit]`, future-proofing for any later second
     component.

   ## Inputs the consumer supplies (consumed by the provider)

   The PIT provider reads `:inputs :gross-income` (the consumer-
   maintained ITAA 1997 §4-15 taxable income after deductions for
   §16 Werbungskosten / §18 Sonderausgaben / etc.) + multiple
   OPTIONAL lanes the AU CGT and investment-income providers emit:

   - `:au-cgt-pit-base-additions` — assessable net capital gain
     (post-Div-115-discount for individuals; post-Subdiv-152 cascade).
   - `:au-investment-pit-base-additions` — franking gross-up +
     unfranked + foreign + interest from investment-income-provider.
   - `:au-franking-credit-pit-credit` — refundable for non-`:company`
     holders.
   - `:au-fito-pit-credit` — non-refundable per §770-10.
   - `:au-tfn-prepaid-pit-credit` — refundable per Pt VA ITAA 1936.

   ## Out of scope for v1 ( slice)

   - **Medicare Levy Surcharge (MLS)** — separate base + surcharge
     progression; consumer pre-computes via `:inputs :credits`.
   - **Family Medicare Levy thresholds** — household-level facts;
     consumer adjudicates.
   - **LMITO** — sunset 2022-07-01; not shipped v1.
   - **HELP / HECS repayment** — separate deferred-debt levy; not
     income tax. Consumer reports separately.
   - **Foreign-resident PIT schedule** — different rates, no tax-free
     threshold; v2 via factory `:resident? false` option.
   - **Pension / age-related rebates (SAPTO etc.)** — consumer
     pre-computes via `:inputs :credits`.

   ## Audit-doc seam (TODO)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   ~50 LOC kernel sweep tracked as a follow-up, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`.

   ## Citations

   `legislation.gov.au` for the consolidated statute text;
   `ato.gov.au` for the administrative position (rates / thresholds /
   shade-in formula). Each parameter-value row carries its own
   citation."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "AU PIT parameter definitions — one row per `:kontor.parameter/code`.
   Bracket values live in `parameter-brackets` keyed by `:effective-from`;
   scalar values live in `parameter-values`."
  [;; --- ITRA 1986 Sch 7 Pt I — resident progressive bracket scale ----------
   {:kontor.parameter/code         "AU.PIT.brackets"
    :kontor.parameter/label        "ITRA 1986 Sch 7 Pt I — resident progressive bracket scale (5 bands; year-keyed via :effective-from per Stage-3 cliff)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A03205"}

   ;; --- Medicare Levy (Medicare Levy Act 1986) -----------------------------
   {:kontor.parameter/code         "AU.PIT.medicare-levy-rate"
    :kontor.parameter/label        "Medicare Levy Act 1986 §6 — flat 2 % levy rate"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A03307"}

   {:kontor.parameter/code         "AU.PIT.medicare-shade-rate"
    :kontor.parameter/label        "Medicare Levy Act 1986 §7(2) — shading-in rate (10 % over low-income threshold)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A03307"}

   {:kontor.parameter/code         "AU.PIT.medicare-low-income-threshold"
    :kontor.parameter/label        "Medicare Levy Act 1986 §7 — low-income threshold (single, no dependants; indexed annually)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A03307"}

   ;; --- LITO (ITAA 1997 §159N) ---------------------------------------------
   {:kontor.parameter/code         "AU.PIT.lito-max"
    :kontor.parameter/label        "ITAA 1997 §159N — LITO maximum offset (flat-zone $700)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-flat-upper"
    :kontor.parameter/label        "ITAA 1997 §159N — taxable-income ceiling for the flat $700 zone"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-mid-rate"
    :kontor.parameter/label        "ITAA 1997 §159N — taper rate in the $37.5k–$45k band (5 c per $)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-mid-upper"
    :kontor.parameter/label        "ITAA 1997 §159N — taxable-income ceiling for the mid-shade zone"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-mid-amount"
    :kontor.parameter/label        "ITAA 1997 §159N — LITO amount at the start of the upper-taper zone ($325)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-upper-rate"
    :kontor.parameter/label        "ITAA 1997 §159N — taper rate in the $45k–$66.667k band (1.5 c per $)"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}

   {:kontor.parameter/code         "AU.PIT.lito-upper-cap"
    :kontor.parameter/label        "ITAA 1997 §159N — taxable-income ceiling above which LITO = 0"
    :kontor.parameter/jurisdiction :au
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.legislation.gov.au/Series/C2004A05138"}])

(def parameter-values
  "AU PIT scalar parameter values with their statutory effective
   windows. Medicare Levy low-income threshold is indexed annually
   (5y of FY values: 2020-21 .. 2024-25 — annual February press
   release confirms the figure for each Australian FY); Medicare
   levy rate stable at 2 % since 2014-07-01; LITO values stable since
   2022-07-01 reform."
  [;; --- Medicare Levy rate ------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.medicare-levy-rate"]
    :kontor.parameter-value/effective-from #inst "2014-07-01"
    :kontor.parameter-value/decimal-value  0.02M
    :kontor.parameter-value/citation       "Medicare Levy Act 1986 §6 — 2 % flat levy rate since 2014-07-01 (last increase)"}

   ;; --- Medicare Levy shade-in rate ----------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.medicare-shade-rate"]
    :kontor.parameter-value/effective-from #inst "1986-09-08"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "Medicare Levy Act 1986 §7(2) — 10 c per $ shading-in rate above low-income threshold"}

   ;; --- Medicare Levy low-income threshold — single, no dependants ---------
   ;; Annual indexation; ship the 5 most-recent FY values (2020-21 .. 2024-25)
   ;; per Q5.4.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.PIT.medicare-low-income-threshold"]
    :kontor.parameter-value/effective-from  #inst "2020-07-01"
    :kontor.parameter-value/effective-until #inst "2021-07-01"
    :kontor.parameter-value/decimal-value   22801M
    :kontor.parameter-value/citation        "Medicare Levy Act 1986 §7 — FY 2020-21 low-income threshold $22,801 (ATO archived threshold table)"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.PIT.medicare-low-income-threshold"]
    :kontor.parameter-value/effective-from  #inst "2021-07-01"
    :kontor.parameter-value/effective-until #inst "2022-07-01"
    :kontor.parameter-value/decimal-value   23226M
    :kontor.parameter-value/citation        "Medicare Levy Act 1986 §7 — FY 2021-22 low-income threshold $23,226 (ATO archived threshold table)"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.PIT.medicare-low-income-threshold"]
    :kontor.parameter-value/effective-from  #inst "2022-07-01"
    :kontor.parameter-value/effective-until #inst "2023-07-01"
    :kontor.parameter-value/decimal-value   23365M
    :kontor.parameter-value/citation        "Medicare Levy Act 1986 §7 — FY 2022-23 low-income threshold $23,365 (ATO archived threshold table)"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AU.PIT.medicare-low-income-threshold"]
    :kontor.parameter-value/effective-from  #inst "2023-07-01"
    :kontor.parameter-value/effective-until #inst "2024-07-01"
    :kontor.parameter-value/decimal-value   24276M
    :kontor.parameter-value/citation        "Medicare Levy Act 1986 §7 — FY 2023-24 low-income threshold $24,276 (ATO archived threshold table)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.medicare-low-income-threshold"]
    :kontor.parameter-value/effective-from #inst "2024-07-01"
    :kontor.parameter-value/decimal-value  27222M
    :kontor.parameter-value/citation       "Medicare Levy Act 1986 §7 — FY 2024-25 low-income threshold $27,222 (ATO press 2024-12; stable into FY 2025-26 until next indexation)"}

   ;; --- LITO ($700 max, stable since 2022-07-01 reform) --------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-max"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  700M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — $700 LITO maximum offset (LITO reform, Treasury Laws Amendment (Personal Income Tax Plan) Act 2018 + reform tranche), stable since 2022-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-flat-upper"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  37500M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — $37,500 ceiling for the flat $700 LITO zone, stable since 2022-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-mid-rate"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — 5 c per $ taper rate for the $37.5k–$45k LITO mid-zone, stable since 2022-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-mid-upper"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  45000M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — $45,000 ceiling for the LITO mid-zone, stable since 2022-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-mid-amount"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  325M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — $325 LITO amount at the start of the upper-taper zone (= $700 − 0.05 × ($45,000 − $37,500))"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-upper-rate"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  0.015M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — 1.5 c per $ taper rate for the $45k–$66.667k LITO upper-zone, stable since 2022-07-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AU.PIT.lito-upper-cap"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  66667M
    :kontor.parameter-value/citation       "ITAA 1997 §159N — $66,667 ceiling above which LITO = 0, stable since 2022-07-01"}])

(def parameter-brackets
  "AU ITRA 1986 Sch 7 Pt I progressive bracket scale — two distinct
   yearly sets: the pre-Stage-3 set (effective 2020-07-01 .. 2024-06-30;
   stable through Stage 1 + Stage 2 of the Personal Income Tax Plan)
   and the post-Stage-3 set (effective 2024-07-01 onwards; enacted by
   Treasury Laws Amendment (Cost of Living Tax Cuts) Act 2024).

   Each row's `[:effective-from, :effective-until)` half-open window
   selects exactly one set for any given `:as-of`. 5 bands per set ×
   2 distinct sets = 10 rows.

   Pre-Stage-3 rates: 0 / 19 / 32.5 / 37 / 45 % at $18,200 / $45,000 /
   $120,000 / $180,000. Post-Stage-3 rates: 0 / 16 / 30 / 37 / 45 % at
   $18,200 / $45,000 / $135,000 / $190,000."
  [;; --- Pre-Stage-3 set (FY 2020-21 .. FY 2023-24) -------------------------
   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index           0
    :kontor.parameter-bracket/rate            0M
    :kontor.parameter-bracket/upper           18200M
    :kontor.parameter-bracket/effective-from  #inst "2020-07-01"
    :kontor.parameter-bracket/effective-until #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index           1
    :kontor.parameter-bracket/rate            0.19M
    :kontor.parameter-bracket/upper           45000M
    :kontor.parameter-bracket/effective-from  #inst "2020-07-01"
    :kontor.parameter-bracket/effective-until #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index           2
    :kontor.parameter-bracket/rate            0.325M
    :kontor.parameter-bracket/upper           120000M
    :kontor.parameter-bracket/effective-from  #inst "2020-07-01"
    :kontor.parameter-bracket/effective-until #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index           3
    :kontor.parameter-bracket/rate            0.37M
    :kontor.parameter-bracket/upper           180000M
    :kontor.parameter-bracket/effective-from  #inst "2020-07-01"
    :kontor.parameter-bracket/effective-until #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter       [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index           4
    :kontor.parameter-bracket/rate            0.45M
    :kontor.parameter-bracket/effective-from  #inst "2020-07-01"
    :kontor.parameter-bracket/effective-until #inst "2024-07-01"}

   ;; --- Post-Stage-3 set (FY 2024-25 onwards; open-ended) ------------------
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index          0
    :kontor.parameter-bracket/rate           0M
    :kontor.parameter-bracket/upper          18200M
    :kontor.parameter-bracket/effective-from #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index          1
    :kontor.parameter-bracket/rate           0.16M
    :kontor.parameter-bracket/upper          45000M
    :kontor.parameter-bracket/effective-from #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index          2
    :kontor.parameter-bracket/rate           0.30M
    :kontor.parameter-bracket/upper          135000M
    :kontor.parameter-bracket/effective-from #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index          3
    :kontor.parameter-bracket/rate           0.37M
    :kontor.parameter-bracket/upper          190000M
    :kontor.parameter-bracket/effective-from #inst "2024-07-01"}

   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AU.PIT.brackets"]
    :kontor.parameter-bracket/index          4
    :kontor.parameter-bracket/rate           0.45M
    :kontor.parameter-bracket/effective-from #inst "2024-07-01"}])

;; ============================================================================
;; Provisions — AU PIT statute as :provision data
;; ============================================================================

(def provisions
  "AU PIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:au-pit` in
   v1) and gate on the presence of driver facts; consequences are
   compute-fns or `:tax-context-fact` reads — rates and amounts come
   from `:parameter` data, NOT inlined."
  [;; ----------------------------------------------------------------
   ;; Medicare Levy Act 1986 §7 — 2 % flat with low-income shade-in
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AU-MedicareLevyAct-§7-medicare-levy"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :surtax]
    :kontor.provision/title          "Medicare Levy Act 1986 §7 — Medicare Levy (2 % flat with low-income shade-in)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A03307"
    :kontor.provision/effective-from #inst "1986-09-08"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :gross-income] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :surtax
                                              :code        :au-medicare-levy
                                              :label       "Medicare Levy (2 % flat, low-income shade-in)"
                                              :amount-from :compute-fn
                                              :fn          :au-medicare-levy})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1997 §159N — Low Income Tax Offset (LITO)
   ;; ----------------------------------------------------------------
   ;; 3-band shade: $700 flat below $37,500; $700 − 0.05 × (TI −
   ;; $37,500) in the mid-zone up to $45,000; $325 − 0.015 × (TI −
   ;; $45,000) in the upper-zone up to $66,667; 0 above. Non-
   ;; refundable per §159N.
   {:kontor.provision/code           "AU-ITAA-1997-§159N-LITO"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "ITAA 1997 §159N — Low Income Tax Offset (LITO, non-refundable, 3-band shade)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2022-07-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:lt [:inputs :gross-income] 66667M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-lito
                                              :label       "ITAA 1997 §159N — LITO"
                                              :refundable? false
                                              :amount-from :compute-fn
                                              :fn          :au-lito})}

   ;; ----------------------------------------------------------------
   ;; CGT-pit-base-additions lane (from cgt-provider)
   ;; ----------------------------------------------------------------
   ;; The shipped `cgt-provider` emits the post-Div-115-discount
   ;; post-Subdiv-152-cascade net capital gain via
   ;; `:jurisdiction-specific-codes {:pit-base-additions [net]}` for
   ;; `:individual / :trust / :super-fund` holders. The consumer
   ;; harvests and passes via `:inputs :au-cgt-pit-base-additions`.
   {:kontor.provision/code           "AU-cgt-pit-base-additions"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "CGT pit-base-additions lane (from cgt-provider — post-Div-115 / Subdiv-152 net gain)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "1985-09-20"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :au-cgt-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :au-cgt-pit-base-fold
                                              :label       "CGT pit-base-additions lane (from cgt-provider)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-cgt-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; Investment-income-pit-base-additions lane (from investment-income-provider)
   ;; ----------------------------------------------------------------
   ;; The shipped `investment-income-provider` emits
   ;; `:jurisdiction-specific-codes {:pit-base-additions [gross-up +
   ;; unfranked + foreign + interest]}` for non-`:company` holders.
   {:kontor.provision/code           "AU-investment-pit-base-additions"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "ITAA 1997 §207-20 — Investment-income pit-base-additions lane (imputation gross-up + unfranked + foreign + interest)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2002-07-01"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :au-investment-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :au-investment-pit-base-fold
                                              :label       "Investment-income pit-base-additions lane (from investment-income-provider)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-investment-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1997 §207-45 — Franking credit (refundable for non-:company)
   ;; ----------------------------------------------------------------
   ;; Refundable for `:individual` + `:super-fund` + `:fixed-trust`
   ;;4. The upstream investment-income-provider
   ;; resolves the franking-credit fate and zeroes lost-fate amounts
   ;; before emit; this provision trusts the resulting scalar.
   {:kontor.provision/code           "AU-ITAA-1997-§207-45-franking-credit-pit"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "ITAA 1997 §207-45 — Franking credit (refundable for resident :individual + :super-fund + :fixed-trust)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2002-07-01"
    :kontor.provision/priority       500
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :au-franking-credit-pit-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-franking-pit-credit
                                              :label       "ITAA 1997 §207-45 — Franking credit (refundable)"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-franking-credit-pit-credit]})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1997 §770-10 — Foreign income tax offset (FITO; non-refundable)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AU-ITAA-1997-§770-10-fito-pit"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "ITAA 1997 §770-10 — Foreign income tax offset (FITO, non-refundable)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C2004A05138"
    :kontor.provision/effective-from #inst "2008-07-01"
    :kontor.provision/priority       600
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :au-fito-pit-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-fito-pit-credit
                                              :label       "ITAA 1997 §770-10 — Foreign income tax offset (FITO)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-fito-pit-credit]})}

   ;; ----------------------------------------------------------------
   ;; ITAA 1936 Pt VA — TFN-withheld prepaid credit (refundable)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AU-ITAA-1936-PtVA-tfn-prepaid-pit"
    :kontor.provision/jurisdiction   :au
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "ITAA 1936 Pt VA — TFN-withheld prepaid (refundable)"
    :kontor.provision/citation       "https://www.legislation.gov.au/Series/C1936A00027"
    :kontor.provision/effective-from #inst "1991-07-01"
    :kontor.provision/priority       700
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :au-pit]
                                              [:gt [:inputs :au-tfn-prepaid-pit-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :au-tfn-prepaid-pit-credit
                                              :label       "ITAA 1936 Pt VA — TFN-withheld prepaid (refundable)"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :au-tfn-prepaid-pit-credit]})}])

;; ============================================================================
;; Install! — transact parameters + values + brackets + provisions
;; ============================================================================

(defn- bracket-row-already-present?
  "True iff a `:parameter-bracket` row with the same `(parameter-code,
   index, effective-from)` triple is already in `db`. Used to make the
   bracket install idempotent — `:parameter-bracket` carries no
   `:db/unique :db.unique/identity` attr in the kernel schema (the
   parent `:kontor.parameter/code` is the natural-key seam; the bracket's
   identity is the `(parent, index, effective-from)` triple), so the
   provider must do the dedup itself.

   Mirrors `kontor.l10n-at.pit-statute/bracket-row-already-present?`."
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
  "Install AU PIT statute (parameters + parameter-values + bracket
   rows + provisions) into `conn`. Idempotent —
   `:kontor.parameter/code` and `:kontor.provision/code` are unique
   identity attrs (upsert on re-install); parameter-brackets get
   explicit dedup via `bracket-row-already-present?` since the kernel
   schema does not carry a `:db/unique` on them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (let [db (d/db conn)
        new-brackets (remove #(bracket-row-already-present? db %) parameter-brackets)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))

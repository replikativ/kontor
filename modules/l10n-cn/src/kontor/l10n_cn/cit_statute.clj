(ns kontor.l10n-cn.cit-statute
  "CN corporate income tax — Enterprise Income Tax (企业所得税, EIT) —
   encoded as `kontor.tax.statute` data per ADR-101. Migrates the
   record-shape `cn-eit-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-fr.cit-statute` (the closest single-component
   schedule-override comparator) — CN ships THREE regime overrides
   (standard 25 % / SLPE preferential 5 % / HNTE 15 %) instead of the
   one (PME) FR ships.

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the §4 ¶1 EIT Law flat
     rate (`CN.EIT.standard-rate`, 25 %) ALREADY LIVES in
     `kontor.l10n-cn.cgt-statute`; this file adds the §28 ¶2 HNTE
     reduced rate, the Cai Shui [2023] 12 SLPE effective rate + ¥3M
     income cap + 300-headcount cap + ¥50M assets cap, and the
     Cai Shui [2023] 7 / [2023] 44 R&D super-deduction multipliers
     (200 % general / 220 % IC + machine-tools).

   - **Provisions** (per-jurisdiction rules) — 11 provisions in v1:
       - CN-EITLaw-§28-¶1-slpe — `:schedule-override` swapping the
         flat 25 % schedule for the SLPE effective 5 % flat rate when
         the consumer signals `:slpe?` AND taxable income ≤ ¥3M (the
         CLIFF condition handled via the substrate's two-pass query
         pattern; see `kontor.tax.statute/apply-provisions` docstring
         §\"Two-pass query pattern\").
       - CN-EITLaw-§28-¶2-hnte — `:schedule-override` swapping for
         the HNTE 15 % flat rate when `:hnte?` is set.
       - CN-EITLaw-§10-non-deductibles — `:base-add` reading
         `:inputs :cn-non-deductibles` (consumer pre-computed; mirrors
         DE §10 KStG add-back posture).
       - CN-EITLaw-§26-¶2-tre-dividend — `:base-deduct` reading the
         `:cit-base-deductions` lane the `investment-income-provider`
         emits when receiving dividends from PRC-resident enterprises.
       - CN-EITLaw-§30-rd-general — `:base-deduct` for the R&D
         super-deduction (general sector, 200 % = extra 100 %) via the
         `:cn-rd-super-deduction-general` compute-fn.
       - CN-EITLaw-§30-rd-ic-mt — `:base-deduct` for the IC +
         industrial-mother-machine sector R&D super-deduction
         (220 % = extra 120 %) via `:cn-rd-super-deduction-ic-mt`.
       - CN-EITLaw-§23-foreign-tax-credit — non-refundable `:credit`
         reading `:inputs :cn-foreign-tax-credit` (consumer pre-computed
         cap-respecting amount; the FTC cap discipline is consumer-side).
       - CN-EITLaw-§18-nol-carry — `:base-deduct` for consumer-pre-
         computed net operating loss carry-forward (5y standard, 10y
         for HNTE/TSME — Cai Shui [2018] 76; consumer adjudicates).
       - CN-EITLaw-§28-Hainan-FTP — `:schedule-override` for Hainan
         Free Trade Port preferential 15 % (Cai Shui [2020] 31;
         sunsets 2027-12-31).
       - CN-EITLaw-§28-Western-Region — `:schedule-override` for
         Western Region encouraged-industry 15 % (Cai Shui [2020] 23;
         extended to 2030-12-31).
       - CN-EITLaw-§28-Lingang — `:schedule-override` for Shanghai
         Lingang New Area key-industry 15 % (Cai Shui [2020] 38).

   - **Scoping** — all provisions are scoped to the single EIT
     component via `[:eq :component :eit]`, mirroring FR's per-component
     gating discipline (even single-component providers gate to
     future-proof).

   ## SLPE qualification cliff — two-pass query pattern

   Cai Shui [2023] 12 §2 requires the FULL taxable-income test ≤ ¥3M
   (cumulatively with headcount ≤ 300 AND total assets ≤ ¥50M). Above
   ¥3M the SLPE regime falls away entirely → standard 25 % applies to
   the WHOLE base, not '5 % on first ¥3M + 25 % on the rest'.

   The SLPE provision's condition gates on `[:leq [:inputs
   :taxable-income] 3000000M]` — but `:inputs :taxable-income` does NOT
   exist on the first pass (consumer supplies `:book-profit`, and
   base-side adjustments only just computed `:taxable-income`). The
   provider computes taxable-income on pass 1 (apply-base-adjustments
   over the standard 25 % regime's base fold), then re-queries
   `apply-provisions` with `:inputs :taxable-income computed-base`
   injected so the cliff condition can fire. See
   `kontor.tax.statute/apply-provisions` §\"Two-pass query pattern\".

   ## Out of scope for v1 ( slice)

   - **CCSV multi-province fan-out** (SAT Bulletin 57/2012; HQ 50 %
     + branches 50 % × 3-factor allocation) — deferred to
     `kontor-group-consolidation` companion (no inter-entity allocation
     in v1). The CA federal + provincial pattern (ADR-107) is the
     substrate analogue.
   - **CFC anti-deferral** (Caishui [2009] 1) — substrate gap (no
     income-allocation primitive); consumer pre-computes the imputed
     income and folds via `:inputs :cn-cfc-imputed-income` as a
     `:base-add` (NOT shipped as a provision in v1 — extend if a
     consumer surfaces an actual case).
   - **Pillar Two / Global Minimum Tax** (effective 2026-01-01 per
     Caishui [2024] 12) — v2; `compose-greater-of` is the right
     primitive when it lands.
   - **§28 ¶1 SLPE pre-2023 stratified rates** (2.5 % first ¥1M + 5 %
     on ¥1M-3M per Cai Shui [2022] 13) — v1 ships only the post-2023
     flat 5 % regime; pre-2023 retrospective assessment would need an
     additional `:effective-until 2023-01-01` row + a bracket parameter
     scale. Maintainer extends if needed.

   ## Audit-doc seam (TODO —)

   `:transaction/audit-doc` on the eventual posting does not yet
   reference back to the responsible `:kontor.provision` — that's a
   small kernel sweep tracked separately, not a per-jurisdiction
   fix. The citation already lives on `:kontor.provision/citation`;
   the posting wire-up lands in a kernel sweep.

   ## Citations

   `chinatax.gov.cn` (STA portal) and `fgk.chinatax.gov.cn` (法规库)
   for the authoritative statute texts; `flk.npc.gov.cn` for the NPC
   legal-text repository where applicable. Cai Shui / Guoshuifa / STA
   Bulletin numbers are stable identifiers in PRC tax law."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================
;;
;; NOTE: `CN.EIT.standard-rate` ALREADY lives in `kontor.l10n-cn.cgt-statute`
;; with a :parameter-value row at 2008-01-01 = 25 %. This file does NOT
;; re-define it; the install order is documented in
;; `kontor.l10n-cn.preset/install-all!` (CGT statute runs before CIT in
;; CN — same posture as AT — because the rate parameter shipped on the
;; CGT side first1.1).

(def parameters
  "CN CIT parameter definitions — one row per `:kontor.parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`. The
   `CN.EIT.standard-rate` parameter is NOT here — it ships from
   `kontor.l10n-cn.cgt-statute` and CIT references it by code at
   evaluation time."
  [{:kontor.parameter/code         "CN.EIT.hnte-rate"
    :kontor.parameter/label        "§28 ¶2 EIT Law — High and New-Technology Enterprise (HNTE) preferential rate 15 %"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"}

   {:kontor.parameter/code         "CN.EIT.slpe-effective-rate"
    :kontor.parameter/label        "Cai Shui [2023] 12 — Small Low-Profit Enterprise (SLPE) effective rate (flat 5 % on taxable income ≤ ¥3M)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5212001/content.html"}

   {:kontor.parameter/code         "CN.EIT.slpe-income-cap"
    :kontor.parameter/label        "Cai Shui [2023] 12 §2 — SLPE taxable-income cap ¥3 000 000"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5212001/content.html"}

   {:kontor.parameter/code         "CN.EIT.slpe-headcount-cap"
    :kontor.parameter/label        "Cai Shui [2023] 12 §2 — SLPE headcount cap 300 (average; from-employees + dispatched)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5212001/content.html"}

   {:kontor.parameter/code         "CN.EIT.slpe-assets-cap"
    :kontor.parameter/label        "Cai Shui [2023] 12 §2 — SLPE total-assets cap ¥50 000 000 (period-average)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5212001/content.html"}

   {:kontor.parameter/code         "CN.EIT.rd-multiplier-general"
    :kontor.parameter/label        "Cai Shui [2023] 7 — R&D super-deduction multiplier (general sector, 200 %)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/c5208302/content.html"}

   {:kontor.parameter/code         "CN.EIT.rd-multiplier-ic-mt"
    :kontor.parameter/label        "Cai Shui [2023] 44 — R&D super-deduction multiplier (integrated-circuit + industrial mother-machine sectors, 220 %)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/c5208302/content.html"}])

(def parameter-values
  "CN CIT scalar parameter values with their statutory effective
   windows. The R&D general multiplier stepped from 1.75 (Cai Shui
   [2018] 99) to 2.00 (Cai Shui [2023] 7) effective 2023-01-01. The
   SLPE 5 % flat rate is the post-Cai Shui [2023] 12 version; pre-2023
   stratified rates are v2. Sunset windows match the
   published Cai Shui circular cadence — the maintainer extends when
   new circulars land."
  [;; HNTE rate — stable 15 % since the 2008 EIT Law promulgation.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.EIT.hnte-rate"]
    :kontor.parameter-value/effective-from #inst "2008-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "§28 ¶2 EIT Law (effective 2008-01-01) — HNTE reduced rate 15 %; certification per Guo Ke Fa Huo [2016] 32 (qualification 3-year renewable cycle)"}

   ;; SLPE effective rate — Cai Shui [2023] 12 set flat 5 % on the
   ;; full ¥3M bucket from 2023-01-01; extended through 2027-12-31 by
   ;; Cai Shui [2025] No. 6.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CN.EIT.slpe-effective-rate"]
    :kontor.parameter-value/effective-from  #inst "2023-01-01"
    :kontor.parameter-value/effective-until #inst "2028-01-01"
    :kontor.parameter-value/decimal-value   0.05M
    :kontor.parameter-value/citation        "Cai Shui [2023] 12 §1 + STA Announcement 2023 No. 6 (extended through 2027-12-31 by Cai Shui [2025] No. 6) — SLPE effective rate 5 % flat on taxable income ≤ ¥3M"}

   ;; SLPE income cap — ¥3M, stable since Cai Shui [2019] 13 (the
   ;; cliff has held even as the effective rate stratification simplified).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.EIT.slpe-income-cap"]
    :kontor.parameter-value/effective-from #inst "2019-01-01"
    :kontor.parameter-value/decimal-value  3000000M
    :kontor.parameter-value/citation       "Cai Shui [2023] 12 §2 (stable since Cai Shui [2019] 13) — SLPE annual taxable-income cap ¥3 000 000"}

   ;; SLPE headcount cap — 300, stable since Cai Shui [2019] 13.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.EIT.slpe-headcount-cap"]
    :kontor.parameter-value/effective-from #inst "2019-01-01"
    :kontor.parameter-value/decimal-value  300M
    :kontor.parameter-value/citation       "Cai Shui [2023] 12 §2 — SLPE average-headcount cap 300 (period-average across from-employees + dispatched workers)"}

   ;; SLPE total-assets cap — ¥50M, stable since Cai Shui [2019] 13.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.EIT.slpe-assets-cap"]
    :kontor.parameter-value/effective-from #inst "2019-01-01"
    :kontor.parameter-value/decimal-value  50000000M
    :kontor.parameter-value/citation       "Cai Shui [2023] 12 §2 — SLPE total-assets cap ¥50 000 000 (period-average)"}

   ;; R&D multiplier (general) — pre-2023 = 1.75 per Cai Shui [2018] 99.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CN.EIT.rd-multiplier-general"]
    :kontor.parameter-value/effective-from  #inst "2018-01-01"
    :kontor.parameter-value/effective-until #inst "2023-01-01"
    :kontor.parameter-value/decimal-value   1.75M
    :kontor.parameter-value/citation        "Cai Shui [2018] 99 — R&D super-deduction multiplier 175 % (general sector) 2018-2022"}

   ;; R&D multiplier (general) — post-2023 = 2.00 per Cai Shui [2023] 7
   ;; (extended to 2027-12-31).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CN.EIT.rd-multiplier-general"]
    :kontor.parameter-value/effective-from  #inst "2023-01-01"
    :kontor.parameter-value/effective-until #inst "2028-01-01"
    :kontor.parameter-value/decimal-value   2.00M
    :kontor.parameter-value/citation        "Cai Shui [2023] 7 — R&D super-deduction multiplier 200 % (general sector), effective 2023-01-01 through 2027-12-31"}

   ;; R&D multiplier (IC + industrial mother-machine) — 220 % per Cai
   ;; Shui [2023] 44 (extended to 2027-12-31).
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CN.EIT.rd-multiplier-ic-mt"]
    :kontor.parameter-value/effective-from  #inst "2023-01-01"
    :kontor.parameter-value/effective-until #inst "2028-01-01"
    :kontor.parameter-value/decimal-value   2.20M
    :kontor.parameter-value/citation        "Cai Shui [2023] 44 — R&D super-deduction multiplier 220 % (integrated-circuit + industrial mother-machine sectors), effective 2023-01-01 through 2027-12-31"}])

;; ============================================================================
;; Provisions — CN EIT statute as :provision data
;; ============================================================================

(def provisions
  "CN EIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:eit` in v1)
   and gate on the presence of driver facts — each provision is
   gated on either a `:tax-unit` flag or a positive `:inputs` value so
   an absent fact silently no-ops.

   Consequences are compute-fns (R&D super-deductions),
   parameter-driven `:schedule-override` shapes (SLPE / HNTE /
   regional), or `:tax-context-fact` reads (consumer-pre-computed
   lanes) — rates and amounts come from `:parameter` data, NOT inlined
   here.

   ## SLPE / HNTE / regional priority

   Note 186 §4.1 + §6 CN-S4: SLPE at priority 100 (fires first if it's
   the only match); HNTE + regional at priority 110 (regional and HNTE
   share priority but are mutually-exclusive via `:tax-unit :region`
   gating). When both `:slpe?` and `:hnte?` match, HNTE wins (higher
   priority on the elective-regime concept query). The substrate's
   ambiguity-trap fires for genuine same-priority collisions; the v1
   shape avoids them by construction."
  [;; ----------------------------------------------------------------
   ;; §28 ¶1 EIT Law — SLPE preferential (Cai Shui [2023] 12)
   ;; ----------------------------------------------------------------
   ;; The CLIFF condition `[:leq [:inputs :taxable-income] 3000000M]`
   ;; requires the two-pass query pattern (see ns docstring and
   ;; `kontor.tax.statute/apply-provisions` §"Two-pass query pattern").
   ;; The provider computes taxable-income on pass 1 (standard 25 %
   ;; base fold), then re-queries with the computed value injected
   ;; into `:inputs :taxable-income` so this condition fires correctly.
   {:kontor.provision/code           "CN-EITLaw-§28-¶1-slpe"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "§28 ¶1 EIT Law + Cai Shui [2023] 12 — Small Low-Profit Enterprise (SLPE) effective 5 %"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5212001/content.html"
    :kontor.provision/effective-from #inst "2023-01-01"
    :kontor.provision/effective-until #inst "2028-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:eq [:tax-unit :slpe?] true]
                                              [:leq [:inputs :taxable-income] 3000000M]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-slpe
                                              :label    "SLPE preferential (effective 5 % flat, Cai Shui [2023] 12)"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from :parameter
                                                         :parameter "CN.EIT.slpe-effective-rate"}})}

   ;; ----------------------------------------------------------------
   ;; §28 ¶2 EIT Law — HNTE preferential 15 %
   ;; ----------------------------------------------------------------
   ;; Consumer adjudicates Guo Ke Fa Huo [2016] 32 qualification
   ;; (substrate does not verify the 8-tech-fields / R&D-staff /
   ;; revenue-from-products tests). When `:slpe?` and `:hnte?` are both
   ;; set, HNTE prevails at priority 110 > 100.
   {:kontor.provision/code           "CN-EITLaw-§28-¶2-hnte"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "§28 ¶2 EIT Law — HNTE (High and New-Technology Enterprise) preferential 15 %"
    :kontor.provision/citation       "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"
    :kontor.provision/effective-from #inst "2008-01-01"
    :kontor.provision/priority       110
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:eq [:tax-unit :hnte?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-hnte
                                              :label    "HNTE preferential 15 % (§28 ¶2 EIT Law)"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from :parameter
                                                         :parameter "CN.EIT.hnte-rate"}})}

   ;; ----------------------------------------------------------------
   ;; §28 ¶3 EIT Law — Hainan Free Trade Port preferential 15 %
   ;; ----------------------------------------------------------------
   ;; Cai Shui [2020] 31; sunsets 2027-12-31. Gated on
   ;; `:tax-unit :region :hainan-ftp` + substantive-operations
   ;; attestation. Mutually exclusive with HNTE / Western-Region /
   ;; Lingang via the `:region` kw.
   {:kontor.provision/code           "CN-EITLaw-§28-Hainan-FTP"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "Cai Shui [2020] 31 — Hainan Free Trade Port preferential 15 %"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c5152120/content.html"
    :kontor.provision/effective-from #inst "2020-06-01"
    :kontor.provision/effective-until #inst "2028-01-01"
    :kontor.provision/priority       120
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:eq [:tax-unit :region] :hainan-ftp]
                                              [:eq [:tax-unit :hainan-substantive-ops?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-hainan-ftp
                                              :label    "Hainan FTP preferential 15 %"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from :parameter
                                                         :parameter "CN.EIT.hnte-rate"}})}

   ;; ----------------------------------------------------------------
   ;; §28 ¶3 EIT Law — Western Region encouraged-industry 15 %
   ;; ----------------------------------------------------------------
   ;; Cai Shui [2020] 23 extended Western Region preferential to
   ;; 2030-12-31. Gated on `:region :western-region` +
   ;; `:encouraged-industry?` attestation.
   {:kontor.provision/code           "CN-EITLaw-§28-Western-Region"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "Cai Shui [2020] 23 — Western Region encouraged-industry preferential 15 %"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5152120/content.html"
    :kontor.provision/effective-from #inst "2011-01-01"
    :kontor.provision/effective-until #inst "2031-01-01"
    :kontor.provision/priority       121
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:eq [:tax-unit :region] :western-region]
                                              [:eq [:tax-unit :encouraged-industry?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-western-region
                                              :label    "Western Region encouraged-industry 15 %"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from :parameter
                                                         :parameter "CN.EIT.hnte-rate"}})}

   ;; ----------------------------------------------------------------
   ;; §28 ¶3 EIT Law — Shanghai Lingang New Area key-industry 15 %
   ;; ----------------------------------------------------------------
   ;; Cai Shui [2020] 38; 5-year preferential for key-industry firms
   ;; in Shanghai's Lingang New Area; consumer attests
   ;; `:lingang-industry?`.
   {:kontor.provision/code           "CN-EITLaw-§28-Lingang"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "Cai Shui [2020] 38 — Shanghai Lingang New Area key-industry preferential 15 %"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5152120/content.html"
    :kontor.provision/effective-from #inst "2020-08-20"
    :kontor.provision/priority       122
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:eq [:tax-unit :region] :lingang]
                                              [:eq [:tax-unit :lingang-industry?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-lingang
                                              :label    "Lingang New Area key-industry 15 %"
                                              :schedule {:kontor.schedule/type :flat
                                                         :rate-from :parameter
                                                         :parameter "CN.EIT.hnte-rate"}})}

   ;; ----------------------------------------------------------------
   ;; §10 EIT Law — Non-deductible expenses (consumer pre-computed)
   ;; ----------------------------------------------------------------
   ;; Mirrors DE §10 KStG and FR réintégrations: the consumer pre-
   ;; computes the net non-deductible amount outside the substrate
   ;; (entertainment > 60 %/0.5 %, donations > 12 %, etc.) and surfaces
   ;; the total via `:inputs :cn-non-deductibles`.
   {:kontor.provision/code           "CN-EITLaw-§10-non-deductibles"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "§10 EIT Law + Implementing Regs §27-29 — Non-deductible expenses (add-back)"
    :kontor.provision/citation       "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"
    :kontor.provision/effective-from #inst "2008-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :cn-non-deductibles] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :cn-§10-non-deductibles
                                              :label       "§10 EIT Law — non-deductible expenses"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cn-non-deductibles]})}

   ;; ----------------------------------------------------------------
   ;; §26 ¶2 EIT Law — Inter-TRE dividend exemption
   ;; ----------------------------------------------------------------
   ;; Reads the lane `cn-eit-investment-income-provider` emits as
   ;; `:cit-base-deductions`; consumer harvests + passes via
   ;; `:inputs :cn-tre-dividend-exemption`.
   {:kontor.provision/code           "CN-EITLaw-§26-¶2-tre-dividend"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§26 ¶2 EIT Law + Implementing Regs §83 — Inter-TRE dividend exemption"
    :kontor.provision/citation       "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"
    :kontor.provision/effective-from #inst "2008-01-01"
    :kontor.provision/priority       210
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :cn-tre-dividend-exemption] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-§26-tre-dividend
                                              :label       "§26 ¶2 EIT Law — TRE-to-TRE dividend exemption"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cn-tre-dividend-exemption]})}

   ;; ----------------------------------------------------------------
   ;; §30 ¶1 EIT Law — R&D super-deduction (general sector, 200 %)
   ;; ----------------------------------------------------------------
   ;; Cai Shui [2023] 7. Negative-list industries (Cai Shui [2015] 119)
   ;; do NOT qualify: tobacco / hospitality / wholesale / real-estate /
   ;; leasing / entertainment — the condition rejects them via `:in`.
   ;; The IC + machine-tools sector takes precedence (the other R&D
   ;; provision); this one fires for everything else.
   {:kontor.provision/code           "CN-EITLaw-§30-rd-general"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§30 ¶1 EIT Law + Cai Shui [2023] 7 — R&D super-deduction (general sector, 200 %)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/c5208302/content.html"
    :kontor.provision/effective-from #inst "2018-01-01"
    :kontor.provision/priority       220
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :rd-qualifying-expense] 0M]
                                              [:not [:in [:tax-unit :industry]
                                                    [:tobacco :hospitality :wholesale
                                                     :real-estate :leasing :entertainment]]]
                                              [:not [:eq [:tax-unit :rd-sector] :ic-machine-tools]]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-§30-rd-general
                                              :label       "§30 ¶1 EIT Law — R&D super-deduction (general, extra 100 %)"
                                              :amount-from :compute-fn
                                              :fn          :cn-rd-super-deduction-general})}

   ;; ----------------------------------------------------------------
   ;; §30 ¶1 EIT Law — R&D super-deduction (IC + machine-tools, 220 %)
   ;; ----------------------------------------------------------------
   ;; Cai Shui [2023] 44. Same negative-list exclusion; fires only when
   ;; `:tax-unit :rd-sector :ic-machine-tools`.
   {:kontor.provision/code           "CN-EITLaw-§30-rd-ic-mt"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§30 ¶1 EIT Law + Cai Shui [2023] 44 — R&D super-deduction (IC + industrial mother-machine, 220 %)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/c5208302/content.html"
    :kontor.provision/effective-from #inst "2023-01-01"
    :kontor.provision/effective-until #inst "2028-01-01"
    :kontor.provision/priority       221
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :rd-qualifying-expense] 0M]
                                              [:not [:in [:tax-unit :industry]
                                                    [:tobacco :hospitality :wholesale
                                                     :real-estate :leasing :entertainment]]]
                                              [:eq [:tax-unit :rd-sector] :ic-machine-tools]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-§30-rd-ic-mt
                                              :label       "§30 ¶1 EIT Law — R&D super-deduction (IC + machine-tools, extra 120 %)"
                                              :amount-from :compute-fn
                                              :fn          :cn-rd-super-deduction-ic-mt})}

   ;; ----------------------------------------------------------------
   ;; §18 EIT Law + Cai Shui [2018] 76 — NOL carry-forward
   ;; ----------------------------------------------------------------
   ;; Consumer pre-computes the permitted offset (5y standard; 10y for
   ;; HNTE / TSME per Cai Shui [2018] 76). Audit-trail provision; the
   ;; inter-period carry maths live outside the substrate. Same posture
   ;; as DE Verlustvortrag in AT §8 Abs 4
   ;; mirror.
   {:kontor.provision/code           "CN-EITLaw-§18-nol-carry"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§18 EIT Law + Cai Shui [2018] 76 — Net operating loss carry-forward"
    :kontor.provision/citation       "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"
    :kontor.provision/effective-from #inst "2008-01-01"
    :kontor.provision/priority       230
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :cn-nol-applied] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-§18-nol
                                              :label       "§18 EIT Law — net operating loss carry-forward"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cn-nol-applied]})}

   ;; ----------------------------------------------------------------
   ;; §23 EIT Law — Foreign tax credit (non-refundable)
   ;; ----------------------------------------------------------------
   ;; Consumer pre-computes the cap-respecting amount (per-country or
   ;; worldwide-basket — the 5-year election locks the method per §23).
   ;; Substrate floors the liability at 0 via the non-refundable
   ;; semantics in `tax-schedule/apply-adjustments`.
   {:kontor.provision/code           "CN-EITLaw-§23-foreign-tax-credit"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "§23 EIT Law — Foreign tax credit (per-country or worldwide-basket; non-refundable)"
    :kontor.provision/citation       "https://flk.npc.gov.cn/detail2.html?MmM5MDlmZGQ2NzhiZjE3OTAxNjc4YmYyYTM4ODA1Mzc%3D"
    :kontor.provision/effective-from #inst "2008-01-01"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :eit]
                                              [:gt [:inputs :cn-foreign-tax-credit] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :cn-§23-ftc
                                              :label       "§23 EIT Law — foreign tax credit (non-refundable)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cn-foreign-tax-credit]})}])

;; ============================================================================
;; Install! — transact parameters + parameter-values + provisions
;; ============================================================================

(defn install!
  "Install CN CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs, so re-running
   is a no-op on unchanged rows. The §4 ¶1 standard rate parameter
   (`CN.EIT.standard-rate`) is NOT installed here — it ships with
   `kontor.l10n-cn.cgt-statute`; this file references it by code at
   evaluation time."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

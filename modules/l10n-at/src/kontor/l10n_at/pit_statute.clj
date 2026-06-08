(ns kontor.l10n-at.pit-statute
  "AT personal income tax — Einkommensteuer (ESt) — encoded as
   `kontor.tax.statute` data per ADR-101. Migrates the record-shape
   `at-income-tax-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-fr.cit-statute` (the single-component template) plus
   the FR PME `parameter-brackets` pattern (one bracket parameter,
   `:effective-from`-keyed yearly rows; FR ships 2 brackets ×
   1 year; AT ships 7 bands × 5 years = 35 rows).

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) — the §33 Abs 1 EStG
     bracket scale (one bracket parameter, 7 bands × 5 years of
     Kalte-Progression history 2022-2026), and the §33 Abs 3a-7 EStG
     Absetzbeträge / credit amounts (Familienbonus, Alleinverdiener,
     Verkehrsabsetzbetrag, Kindermehrbetrag).

   - **Provisions** (per-jurisdiction rules) — 8 provisions:
       - AT-EStG-§33-Abs-3a-familienbonus-under-18 — Familienbonus
         Plus for children < 18 (non-refundable except the
         Kindermehrbetrag tail).
       - AT-EStG-§33-Abs-3a-familienbonus-over-18 — Familienbonus Plus
         for adult children in education (separate, smaller rate).
       - AT-EStG-§33-Abs-7-kindermehrbetrag — refundable top-up when
         tax-before-credits is too low to absorb the Familienbonus.
       - AT-EStG-§33-Abs-4-Z-1-alleinverdiener — refundable single-
         earner credit (Negativsteuer-fähig).
       - AT-EStG-§33-Abs-5-verkehrsabsetzbetrag — non-refundable
         employee commuting credit (default flat).
       - AT-EStG-§27a-Abs-5-regelbesteuerung-fold — base-add for the
         investment-income provider's `:pit-base-additions` lane when
         the consumer elected Regelbesteuerung.
       - AT-EStG-§30-Abs-7-vermietung-loss-carry — base-deduct for
         the cgt-provider's §30 Abs 7 ImmoESt-loss carry against §28
         Vermietung income.
       - AT-EStG-§27a-Abs-5-kest-prepaid-credit — refundable credit
         for KESt already withheld by the bank.
       - AT-EStG-§10-Abs-1-DBA-quellensteuer-credit — non-refundable
         credit for treaty-rate withholding tax.

   - **Scoping** — all provisions scoped to `:est` via `[:eq :component
     :est]`, matching the FR `:is`-component discipline.

   ## Inputs the consumer supplies (consumed by the provider)

   The PIT provider reads `:inputs :gross-income` (the §2 EStG
   Gesamtbetrag der Einkünfte after the Steuerbilanz delta the
   consumer maintains outside the substrate) + multiple OPTIONAL
   lanes the AT investment-income and CGT providers emit:

   - `:cgt-pit-base-additions` — KESt CGT gains folded into ESt base
     under Regelbesteuerung.
   - `:cgt-pit-base-deductions-§28` — ImmoESt §30 Abs 7 yearly loss
     slice against §28 Vermietung income (15-year carry).
   - `:investment-pit-base-additions` — Regelbesteuerungsoption fold
     from the investment-income provider.
   - `:investment-pit-credits-kest-prepaid` — KESt already withheld
     by the bank, refundable.
   - `:investment-pit-credits-non-refundable-dba` — DBA-Quellensteuer
     non-refundable credit.

   `:tax-unit` flags drive the Familienbonus + Alleinverdiener +
   Verkehrsabsetz provisions:
   - `:children-under-18-count` / `:children-over-18-count` — drives
     Familienbonus magnitude.
   - `:familienbonus-claimed` — pre-computed Familienbonus magnitude
     the Kindermehrbetrag check compares against.
   - `:alleinverdiener?` — single-earner flag.
   - `:children-count` — total children for the Alleinverdiener tier
     selection.
   - `:employment-relationship?` — drives the Verkehrsabsetzbetrag.

   ## Out of scope for v1 ( slice)

   - **Negativsteuer (§33 Abs 8 EStG)** — pension contribution refund
     mechanism for low-income employees; consumer pre-computes via
     `:inputs :credits` outside the substrate.
   - **Pendlerpauschale-elevated Verkehrsabsetzbetrag (§33 Abs 5 Z 2)**
     — separate parameter shipped; v1 provision selects the default
     flat; the elevated version + income-shading is consumer-supplied.
   - **Pensionistenabsetzbetrag (§33 Abs 6)** — consumer supplies via
     `:inputs :credits`.
   - **Sonderausgaben (§18 EStG), Werbungskosten (§16 EStG),
     Außergewöhnliche Belastungen (§34 EStG)** — consumer pre-computes
     net deductions and supplies via `:inputs :gross-income`.

   ## Citations

   `jusline.at` for the consolidated statute text; `ris.bka.gv.at` for
   the official BGBl Fundstelle
   the bracket history (cited per parameter-value row). Parameter
   brackets cite the Inflationsanpassungsgesetz / Drittelbeschluss for
   each year's adjustment."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "AT ESt parameter definitions — one row per `:kontor.parameter/code`.
   Bracket values live in `parameter-brackets` keyed by
   `:effective-from`; scalar values live in `parameter-values`."
  [{:kontor.parameter/code         "AT.EStG.§33-Abs-1.brackets"
    :kontor.parameter/label        "§33 Abs 1 EStG — progressive Tarif (7 bands; year-keyed via :effective-from per Kalte-Progression-Abschaffung)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-3a.familienbonus-under-18"
    :kontor.parameter/label        "§33 Abs 3a Z 1 EStG — Familienbonus Plus per qualifying child < 18 (annual amount)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-3a.familienbonus-over-18"
    :kontor.parameter/label        "§33 Abs 3a Z 2 EStG — Familienbonus Plus per qualifying child ≥ 18 in education (annual amount)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child"
    :kontor.parameter/label        "§33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag, 1 child (annual amount, refundable)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children"
    :kontor.parameter/label        "§33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag, 2 children (annual amount, refundable)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-each-addl"
    :kontor.parameter/label        "§33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag, increment per additional child > 2 (annual amount)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-5.verkehrsabsetz-default"
    :kontor.parameter/label        "§33 Abs 5 Z 1 EStG — Verkehrsabsetzbetrag, default (annual amount, non-refundable)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}

   {:kontor.parameter/code         "AT.EStG.§33-Abs-7.kindermehrbetrag"
    :kontor.parameter/label        "§33 Abs 7 EStG — Kindermehrbetrag (annual amount, refundable; per child)"
    :kontor.parameter/jurisdiction :at
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.jusline.at/gesetz/estg/paragraf/33"}])

(def parameter-values
  "AT ESt scalar parameter values with their statutory effective
   windows. The Familienbonus stepped from €1 500 to €2 000.16 per
   child under 18 effective 2022-07-01 (Antiteuerungspaket I, BGBl I
   2022/93). The Alleinverdiener amounts have been indexed annually
   since 2023 (Kalte-Progression-Abschaffung). Verkehrsabsetzbetrag
   default values per WKO Aktuelle Werte tables 2022-2026."
  [;; Familienbonus Plus < 18 — €2 000.16/yr (€166.68/mo) from 2022-07-01.
   ;; The pre-2022 value €1 500/yr is documented for completeness but the
   ;; under-18 fact-based system v1 ships only the current value.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-3a.familienbonus-under-18"]
    :kontor.parameter-value/effective-from #inst "2022-07-01"
    :kontor.parameter-value/decimal-value  2000.16M
    :kontor.parameter-value/citation       "§33 Abs 3a Z 1 EStG (Antiteuerungspaket I, BGBl I 2022/93) — €2 000.16/yr (€166.68/mo) per child < 18 from 2022-07-01"}

   ;; Familienbonus Plus ≥ 18 — €700.08/yr from 2024-01-01.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-3a.familienbonus-over-18"]
    :kontor.parameter-value/effective-from #inst "2024-01-01"
    :kontor.parameter-value/decimal-value  700.08M
    :kontor.parameter-value/citation       "§33 Abs 3a Z 2 EStG — €700.08/yr per adult child in education from 2024"}

   ;; Alleinverdienerabsetzbetrag — yearly indexed (Kalte-Progression).
   ;; v1 ships 2024-2026 with the 2026 row open-ended (the 2025 row
   ;; has effective-until 2026-01-01; the 2024 row has effective-until
   ;; 2025-01-01). Pre-2024 values (€494 / €669 / +€220) omitted v1.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child"]
    :kontor.parameter-value/effective-from  #inst "2024-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   572M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener €572 (1 child) for 2024"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child"]
    :kontor.parameter-value/effective-from  #inst "2025-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   601M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener €601 (1 child) for 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-1child"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  611M
    :kontor.parameter-value/citation       "§33 Abs 4 Z 1 EStG — Alleinverdiener €611 (1 child) for 2026"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children"]
    :kontor.parameter-value/effective-from  #inst "2024-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   774M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener €774 (2 children) for 2024"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children"]
    :kontor.parameter-value/effective-from  #inst "2025-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   813M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener €813 (2 children) for 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-2children"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  827M
    :kontor.parameter-value/citation       "§33 Abs 4 Z 1 EStG — Alleinverdiener €827 (2 children) for 2026"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-each-addl"]
    :kontor.parameter-value/effective-from  #inst "2024-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   255M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener +€255 / addl child for 2024"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-each-addl"]
    :kontor.parameter-value/effective-from  #inst "2025-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   268M
    :kontor.parameter-value/citation        "§33 Abs 4 Z 1 EStG — Alleinverdiener +€268 / addl child for 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-4-Z-1.alleinverdiener-each-addl"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  273M
    :kontor.parameter-value/citation       "§33 Abs 4 Z 1 EStG — Alleinverdiener +€273 / addl child for 2026"}

   ;; Verkehrsabsetzbetrag default — indexed; 2022-2026.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-5.verkehrsabsetz-default"]
    :kontor.parameter-value/effective-from  #inst "2022-01-01"
    :kontor.parameter-value/effective-until #inst "2023-01-01"
    :kontor.parameter-value/decimal-value   400M
    :kontor.parameter-value/citation        "§33 Abs 5 Z 1 EStG (pre-Kalte-Progression baseline) — Verkehrsabsetzbetrag default €400 for 2022"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-5.verkehrsabsetz-default"]
    :kontor.parameter-value/effective-from  #inst "2023-01-01"
    :kontor.parameter-value/effective-until #inst "2024-01-01"
    :kontor.parameter-value/decimal-value   421M
    :kontor.parameter-value/citation        "§33 Abs 5 Z 1 EStG (Inflationsanpassungsgesetz 2022) — Verkehrsabsetzbetrag default €421 for 2023"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-5.verkehrsabsetz-default"]
    :kontor.parameter-value/effective-from  #inst "2024-01-01"
    :kontor.parameter-value/effective-until #inst "2025-01-01"
    :kontor.parameter-value/decimal-value   463M
    :kontor.parameter-value/citation        "§33 Abs 5 Z 1 EStG (WKO Aktuelle Werte 2024) — Verkehrsabsetzbetrag default €463 for 2024"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-5.verkehrsabsetz-default"]
    :kontor.parameter-value/effective-from  #inst "2025-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   487M
    :kontor.parameter-value/citation        "§33 Abs 5 Z 1 EStG (WKO Aktuelle Werte 2025) — Verkehrsabsetzbetrag default €487 for 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-5.verkehrsabsetz-default"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  495M
    :kontor.parameter-value/citation       "§33 Abs 5 Z 1 EStG (WKO Aktuelle Werte 2026) — Verkehrsabsetzbetrag default €495 for 2026"}

   ;; Kindermehrbetrag — stepped 2024.
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "AT.EStG.§33-Abs-7.kindermehrbetrag"]
    :kontor.parameter-value/effective-from  #inst "2022-01-01"
    :kontor.parameter-value/effective-until #inst "2024-01-01"
    :kontor.parameter-value/decimal-value   550M
    :kontor.parameter-value/citation        "§33 Abs 7 EStG — Kindermehrbetrag €550/yr per child 2022-2023"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-7.kindermehrbetrag"]
    :kontor.parameter-value/effective-from #inst "2024-01-01"
    :kontor.parameter-value/decimal-value  700M
    :kontor.parameter-value/citation       "§33 Abs 7 EStG (Steuerreform 2024) — Kindermehrbetrag €700/yr per child from 2024"}])

(def parameter-brackets
  "AT §33 Abs 1 EStG progressive bracket scale — 5 yearly sets (2022
   pre-Kalte-Progression-Abschaffung baseline + 2023-2026 reformed
   sets, indexed annually). Each row's `[:effective-from,
   :effective-until)` half-open window selects exactly one set for any
   given `:as-of`. 7 bands per year × 5 years = 35 rows.

   Sources: 2023 = pre-Kalte-Progression-Abschaffung first reformed year
   (Inflationsanpassungsgesetz 2022, BGBl I 2022/194). 2024 brackets
   raised per the Tax Reform Act 2023 (Teuerungs-Entlastungspaket III).
   2025 + 2026 brackets per Drittelbeschluss (BMF Drittelbeschluss
   2024-08 / 2024-12 / 2025-09).

   Rates 0/20/30/40/48/50/55 % stable since 2024 (the 55 % top bracket
   is statutorily sunset 2030-01-01 per §124b Z 271 EStG unless
   extended)."
  (vec
   (for [[year start until k1 k2 k3 k4 k5 k6 citation]
         [;; 2022 — pre-Kalte-Progression-Abschaffung baseline.
          [2022 #inst "2022-01-01" #inst "2023-01-01" 11000M 18000M 31000M 60000M 90000M 1000000M
           "§33 Abs 1 EStG (pre-Inflationsanpassungsgesetz 2022 baseline) — 2022 brackets"]
          ;; 2023 — first reformed year.
          [2023 #inst "2023-01-01" #inst "2024-01-01" 11693M 19134M 32075M 62080M 93120M 1000000M
           "§33 Abs 1 EStG (Inflationsanpassungsgesetz 2022, BGBl I 2022/194) — 2023 reformed brackets (first year of cold-progression adjustment)"]
          ;; 2024 — second reformed year (Tax Reform Act 2023).
          [2024 #inst "2024-01-01" #inst "2025-01-01" 12816M 20818M 34513M 66612M 99266M 1000000M
           "§33 Abs 1 EStG (Tax Reform Act 2023) — 2024 brackets +9.6 % / +8.8 % / +7.6 % / +6.6 % / +6.6 %"]
          ;; 2025 — Drittelbeschluss 2024.
          [2025 #inst "2025-01-01" #inst "2026-01-01" 13308M 21617M 35836M 69166M 103072M 1000000M
           "§33 Abs 1 EStG — 2025 brackets +3.833 % per Drittelbeschluss"]
          ;; 2026 — Drittelbeschluss 2025 (open-ended).
          [2026 #inst "2026-01-01" nil 13539M 21992M 36458M 70365M 104859M 1000000M
           "§33 Abs 1 EStG — 2026 brackets +1.733 % per Drittelbeschluss"]]
         [idx rate upper]
         [;; band 0: 0 % up to k1
          [0 0M    k1]
          ;; band 1: 20 % up to k2
          [1 0.20M k2]
          ;; band 2: 30 % up to k3
          [2 0.30M k3]
          ;; band 3: 40 % up to k4
          [3 0.40M k4]
          ;; band 4: 48 % up to k5
          [4 0.48M k5]
          ;; band 5: 50 % up to k6
          [5 0.50M k6]
          ;; band 6: 55 % open top (the sunset post-2030 rate; the
          ;; substrate carries the rate as-is; the sunset is its own
          ;; bitemporal slice if the maintainer ships a 2030 set with
          ;; band 6 = 50 % / open top)
          [6 0.55M nil]]]
     (cond->
      {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "AT.EStG.§33-Abs-1.brackets"]
       :kontor.parameter-bracket/index          idx
       :kontor.parameter-bracket/rate           rate
       :kontor.parameter-bracket/effective-from start
       :kontor.parameter-bracket/_year-marker   year   ; not transacted; used only to keep the for-binding readable
       :kontor.parameter-bracket/_citation      citation}
       upper (assoc :kontor.parameter-bracket/upper upper)
       until (assoc :kontor.parameter-bracket/effective-until until)))))

;; Strip the synthetic helper keys before transact (the `_year-marker`
;; and `_citation` keys above are NOT in the kernel schema; they
;; document the year + citation for a human reader of this file).
(def parameter-brackets-rows
  "The transactable bracket rows (helper keys filtered out)."
  (mapv #(dissoc % :kontor.parameter-bracket/_year-marker
                 :kontor.parameter-bracket/_citation)
        parameter-brackets))

;; ============================================================================
;; Provisions — AT ESt statute as :provision data
;; ============================================================================

(def provisions
  "AT ESt statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:est` in v1)
   and gate on the presence of driver facts; consequences are
   compute-fns or `:tax-context-fact` reads — rates and amounts come
   from `:parameter` data, NOT inlined."
  [;; ----------------------------------------------------------------
   ;; §33 Abs 3a EStG — Familienbonus Plus (children < 18)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AT-EStG-§33-Abs-3a-familienbonus-under-18"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "§33 Abs 3a EStG — Familienbonus Plus (children < 18)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/33"
    :kontor.provision/effective-from #inst "2022-07-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:tax-unit :children-under-18-count] 0]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-familienbonus-plus-under-18
                                              :label       "§33 Abs 3a EStG — Familienbonus Plus (children < 18)"
                                              :refundable? false
                                              :amount-from :compute-fn
                                              :fn          :at-familienbonus-plus})}

   ;; ----------------------------------------------------------------
   ;; §33 Abs 3a EStG — Familienbonus Plus (children ≥ 18 in education)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AT-EStG-§33-Abs-3a-familienbonus-over-18"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "§33 Abs 3a EStG — Familienbonus Plus (children ≥ 18 in education)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/33"
    :kontor.provision/effective-from #inst "2022-07-01"
    :kontor.provision/priority       110
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:tax-unit :children-over-18-count] 0]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-familienbonus-plus-over-18
                                              :label       "§33 Abs 3a EStG — Familienbonus Plus (children ≥ 18 in education)"
                                              :refundable? false
                                              :amount-from :compute-fn
                                              :fn          :at-familienbonus-plus-over-18})}

   ;; ----------------------------------------------------------------
   ;; §33 Abs 7 EStG — Kindermehrbetrag (refundable)
   ;; ----------------------------------------------------------------
   ;; Kicks in when tax-before-credits is too low to absorb the
   ;; full Familienbonus. v1 fires whenever the consumer signals
   ;; the cliff via `:tax-unit :kindermehrbetrag-eligible?` — the
   ;; consumer adjudicates the §33 Abs 7 Z 2 EStG income test
   ;; (Erwerbseinkünfte < €25 500 + at least 30 days employment)
   ;; outside the substrate. Refundable per §33 Abs 8 Negativsteuer-
   ;; Mechanik.
   {:kontor.provision/code           "AT-EStG-§33-Abs-7-kindermehrbetrag"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "§33 Abs 7 EStG — Kindermehrbetrag (refundable)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/33"
    :kontor.provision/effective-from #inst "2022-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:tax-unit :children-under-18-count] 0]
                                              [:eq [:tax-unit :kindermehrbetrag-eligible?] true]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-kindermehrbetrag
                                              :label       "§33 Abs 7 EStG — Kindermehrbetrag"
                                              :refundable? true
                                              :amount-from :compute-fn
                                              :fn          :at-kindermehrbetrag-amount})}

   ;; ----------------------------------------------------------------
   ;; §33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag (refundable)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AT-EStG-§33-Abs-4-Z-1-alleinverdiener"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "§33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag (single-earner; refundable as Negativsteuer)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/33"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       300
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:eq [:tax-unit :alleinverdiener?] true]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-alleinverdiener
                                              :label       "§33 Abs 4 Z 1 EStG — Alleinverdienerabsetzbetrag"
                                              :refundable? true
                                              :amount-from :compute-fn
                                              :fn          :at-alleinverdiener-amount})}

   ;; ----------------------------------------------------------------
   ;; §33 Abs 5 Z 1 EStG — Verkehrsabsetzbetrag (non-refundable)
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "AT-EStG-§33-Abs-5-verkehrsabsetzbetrag"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "§33 Abs 5 Z 1 EStG — Verkehrsabsetzbetrag (default, non-refundable)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/33"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       400
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:eq [:tax-unit :employment-relationship?] true]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-verkehrsabsetz
                                              :label       "§33 Abs 5 Z 1 EStG — Verkehrsabsetzbetrag"
                                              :refundable? false
                                              :amount-from :parameter
                                              :parameter   "AT.EStG.§33-Abs-5.verkehrsabsetz-default"})}

   ;; ----------------------------------------------------------------
   ;; §27a Abs 5 EStG — Regelbesteuerungsoption fold (base-add)
   ;; ----------------------------------------------------------------
   ;; Reads the lane the investment-income-provider emits at line 217
   ;; when `:regelbesteuerung-elected?` is signalled. The consumer
   ;; harvests + passes via `:inputs :investment-pit-base-additions`.
   {:kontor.provision/code           "AT-EStG-§27a-Abs-5-regelbesteuerung-fold"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-add]
    :kontor.provision/title          "§27a Abs 5 EStG — Regelbesteuerungsoption fold (lane from investment-income provider)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/27a"
    :kontor.provision/effective-from #inst "2012-04-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:inputs :investment-pit-base-additions] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-add
                                              :code        :at-regelbesteuerung-fold
                                              :label       "§27a Abs 5 EStG — Regelbesteuerungsoption fold"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-base-additions]})}

   ;; ----------------------------------------------------------------
   ;; §30 Abs 7 EStG — ImmoESt-loss carry against §28 Vermietung income
   ;; ----------------------------------------------------------------
   ;; Reads the lane cgt-provider emits as
   ;; `:pit-base-deductions {:§28-vermietung [yearly-slice]}` — the
   ;; consumer extracts the value and passes via
   ;; `:inputs :cgt-pit-base-deductions-§28`.
   {:kontor.provision/code           "AT-EStG-§30-Abs-7-vermietung-loss-carry"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§30 Abs 7 EStG — ImmoESt-loss yearly slice against §28-Vermietung income"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/30"
    :kontor.provision/effective-from #inst "2012-04-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:inputs :cgt-pit-base-deductions-§28] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :at-§30-immo-loss-carry
                                              :label       "§30 Abs 7 EStG — ImmoESt-loss carry against §28-Vermietung"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :cgt-pit-base-deductions-§28]})}

   ;; ----------------------------------------------------------------
   ;; §27a Abs 5 EStG — KESt-prepaid refundable credit
   ;; ----------------------------------------------------------------
   ;; Reads the lane investment-income-provider emits as
   ;; `:pit-credits {:at-kest-prepaid prepaid :refundable? true}`; the
   ;; consumer extracts the prepaid scalar and passes via
   ;; `:inputs :investment-pit-credits-kest-prepaid`.
   {:kontor.provision/code           "AT-EStG-§27a-Abs-5-kest-prepaid-credit"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title          "§27a Abs 5 EStG — KESt prepaid (refundable when Regelbesteuerung elected)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/27a"
    :kontor.provision/effective-from #inst "2012-04-01"
    :kontor.provision/priority       500
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:inputs :investment-pit-credits-kest-prepaid] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-kest-prepaid
                                              :label       "§27a Abs 5 EStG — KESt prepaid credit (refundable)"
                                              :refundable? true
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-credits-kest-prepaid]})}

   ;; ----------------------------------------------------------------
   ;; §10 Abs 1 EStG — DBA-Quellensteuer non-refundable credit
   ;; ----------------------------------------------------------------
   ;; Reads the DBA-Quellensteuer credit lane from investment-income
   ;; (non-refundable; capped at treaty rate).
   {:kontor.provision/code           "AT-EStG-§10-Abs-1-DBA-quellensteuer-credit"
    :kontor.provision/jurisdiction   :at
    :kontor.provision/concept        [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title          "§10 Abs 1 EStG — DBA-Quellensteuer credit (capped at treaty rate; non-refundable)"
    :kontor.provision/citation       "https://www.jusline.at/gesetz/estg/paragraf/10"
    :kontor.provision/effective-from #inst "1988-07-07"
    :kontor.provision/priority       600
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :est]
                                              [:gt [:inputs :investment-pit-credits-non-refundable-dba] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :credit
                                              :code        :at-dba-quellensteuer
                                              :label       "§10 Abs 1 EStG — DBA-Quellensteuer credit (non-refundable)"
                                              :refundable? false
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :investment-pit-credits-non-refundable-dba]})}])

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

   Mirrors `kontor.l10n-fr.cit-statute/bracket-row-already-present?`."
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
  "Install AT PIT statute (parameters + parameter-values + bracket
   rows + provisions) into `conn`. Idempotent —
   `:kontor.parameter/code` and `:kontor.provision/code` are unique
   identity attrs (upsert on re-install); parameter-brackets get
   explicit dedup via `bracket-row-already-present?` since the kernel
   schema does not carry a `:db/unique` on them."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (let [db (d/db conn)
        new-brackets (remove #(bracket-row-already-present? db %)
                             parameter-brackets-rows)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))

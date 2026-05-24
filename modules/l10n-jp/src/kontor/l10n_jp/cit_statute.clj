(ns kontor.l10n-jp.cit-statute
  "JP corporate income tax — 法人税 + 地方法人税 + 防衛特別法人税 + 事業税 +
   特別法人事業税 + 法人住民税 (法人税割 + 均等割) — encoded as
   `kontor.statute` data per ADR-101 / ADR-106. The first non-DE consumer
   of the statute-as-data substrate; the deflated-DE template (one statute
   namespace, one provider namespace, one test namespace).

   The encoding splits cleanly along the substrate seams:

   - **Parameters** (date-keyed value history) — every statutory rate:
       JP.CIT.sme-reduced-rate (15 %),
       JP.CIT.flat-rate (23.2 %),
       JP.CIT.sme-kink (¥8 000 000),
       JP.LocalCIT.rate (10.3 %),
       JP.DefenseSurtax.rate (4 %, FY ≥ 2026-04-01),
       JP.DefenseSurtax.deduction (¥5 000 000),
       JP.Enterprise.sme-rate-1 (3.5 % ≤¥4M),
       JP.Enterprise.sme-rate-2 (5.3 % ≤¥8M),
       JP.Enterprise.sme-rate-3 (7.0 % >¥8M),
       JP.Enterprise.sme-kink-1 (¥4 000 000),
       JP.Enterprise.sme-kink-2 (¥8 000 000),
       JP.Enterprise.flat-large-rate (1.18 %),
       JP.SpecialCorpEnterprise.sme-rate (37 %),
       JP.SpecialCorpEnterprise.large-rate (260 %),
       JP.Inhabitant.income-levy-rate (7 % SME standard;
                                       1 % prefectural + 6 % municipal).

     Per-capita inhabitants' levy (均等割) values are NOT carried as
     `:parameter`s — the lookup is a 10-cell table over `:tax-unit`
     dimensions (capital × headcount) and reads cleanest as a compute-fn
     that consults the consumer-supplied `:tax-unit`. Adding the 10
     thresholds as parameters would inflate the substrate without
     winning a real authoring affordance (the tiers haven't moved
     since 2015).

   - **Provisions** (per-jurisdiction rules) — the surtax / adjustment
     paths:
       JP-CIT-§66-large    — schedule-override to a flat 23.2 % schedule
                             when [:eq [:tax-unit :is-sme?] false]
       JP-LocalCIT-§9      — 10.3 % surtax on national CIT
       JP-DefenseSurtax    — 4 % × (national CIT − ¥5M); from 2026-04-01
       JP-Enterprise-§72   — schedule-override to large-co flat 1.18 %
                             when not SME (income-base only; pro-forma
                             value-added / capital deferred — note 110
                             §1)
       JP-SpecialCorpEnterprise-§7 — surtax on the enterprise-tax amount
       JP-Inhabitant-income-levy   — 7 % surtax on national CIT
                                     (consumed in the inhabitants'
                                     component)
       JP-Inhabitant-per-capita    — fixed per-capita levy from the
                                     10-cell capital × headcount table

   - **Scoping** — provisions are scoped to one component (`:national`
     / `:enterprise` / `:inhabitant`) via a `:condition [:eq :component
     <kw>]` predicate; the provider sets `:component` in ctx on each
     per-component pass. Same convention as DE's `:kst` / `:gewst`.

   Citations point at NTA (nta.go.jp), Tokyo Metropolitan Bureau of
   Taxation, e-gov.go.jp statute text, JETRO Section 3.3 and the
   PwC / JETRO worked examples. Research note 110."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "JP CIT parameter definitions — one row per `:parameter/code`.
   Values live in `parameter-values` keyed by `:effective-from`."
  [;; ----- National corporation tax 法人税 -----
   {:parameter/code         "JP.CIT.sme-reduced-rate"
    :parameter/label        "Hōjinzei (法人税) reduced SME rate on the first ¥8 000 000 of taxable income"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=340AC0000000034"}

   {:parameter/code         "JP.CIT.flat-rate"
    :parameter/label        "Hōjinzei (法人税) standard flat rate (above the SME kink / all-income for large corps)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=340AC0000000034"}

   {:parameter/code         "JP.CIT.sme-kink"
    :parameter/label        "Hōjinzei (法人税) SME reduced-rate kink (¥8 000 000)"
    :parameter/jurisdiction :jp
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=340AC0000000034"}

   ;; ----- National local corporation tax 地方法人税 -----
   {:parameter/code         "JP.LocalCIT.rate"
    :parameter/label        "Chihō Hōjinzei (地方法人税) — 10.3 % surtax on the national CIT amount"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5121.htm"}

   ;; ----- Defense surtax 防衛特別法人税 (FY ≥ 2026-04-01) -----
   {:parameter/code         "JP.DefenseSurtax.rate"
    :parameter/label        "Bōei Tokubetsu Hōjinzei (防衛特別法人税) — 4 % surtax on (national CIT − ¥5M)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.mof.go.jp/tax_policy/summary/corporation/c01.htm"}

   {:parameter/code         "JP.DefenseSurtax.deduction"
    :parameter/label        "Bōei Tokubetsu Hōjinzei — basic deduction (¥5 000 000) applied before the 4 %"
    :parameter/jurisdiction :jp
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.mof.go.jp/tax_policy/summary/corporation/c01.htm"}

   ;; ----- Enterprise tax 事業税 (SME progressive ladder) -----
   ;; SME schedule = 3-bracket progressive on income (Tokyo uses 3.75 /
   ;; 5.665 / 7.48 %; we ship national-standard 3.5 / 5.3 / 7.0 % and
   ;; let consumers override per-prefecture). Carried as 3 `:parameter`s
   ;; + 2 kinks because note 102's `:parameter-bracket` rows are an
   ;; equally valid encoding; the flat-list form is closer to the
   ;; statute text and parallels DE GewSt's flat-rate carriage.
   {:parameter/code         "JP.Enterprise.sme-rate-1"
    :parameter/label        "Jigyōzei (事業税) SME bracket 1 — 3.5 % on the first ¥4 000 000"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   {:parameter/code         "JP.Enterprise.sme-rate-2"
    :parameter/label        "Jigyōzei (事業税) SME bracket 2 — 5.3 % on ¥4 000 001 .. ¥8 000 000"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   {:parameter/code         "JP.Enterprise.sme-rate-3"
    :parameter/label        "Jigyōzei (事業税) SME bracket 3 — 7.0 % on income > ¥8 000 000"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   {:parameter/code         "JP.Enterprise.sme-kink-1"
    :parameter/label        "Jigyōzei SME bracket 1 → 2 kink (¥4 000 000)"
    :parameter/jurisdiction :jp
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   {:parameter/code         "JP.Enterprise.sme-kink-2"
    :parameter/label        "Jigyōzei SME bracket 2 → 3 kink (¥8 000 000)"
    :parameter/jurisdiction :jp
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   {:parameter/code         "JP.Enterprise.large-rate"
    :parameter/label        "Jigyōzei (事業税) large-corporation income-base standard rate (1.18 %; pro-forma value-added/capital bases deferred — note 110 §1)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"}

   ;; ----- Special corporate enterprise tax 特別法人事業税 -----
   {:parameter/code         "JP.SpecialCorpEnterprise.sme-rate"
    :parameter/label        "Tokubetsu Hōjin Jigyōzei (特別法人事業税) — 37 % SME surtax on enterprise-tax income-base amount"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5765.htm"}

   {:parameter/code         "JP.SpecialCorpEnterprise.large-rate"
    :parameter/label        "Tokubetsu Hōjin Jigyōzei (特別法人事業税) — 260 % large-corp surtax on enterprise-tax income-base amount"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5765.htm"}

   ;; ----- Corporate inhabitants' tax 法人住民税 -----
   {:parameter/code         "JP.Inhabitant.income-levy-rate"
    :parameter/label        "Hōjin Jūminzei (法人住民税) 法人税割 — combined income levy on national CIT (7 % SME standard = 1 % prefectural + 6 % municipal; max 10.4 % Tokyo large)"
    :parameter/jurisdiction :jp
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jumin.html"}])

(def parameter-values
  "JP CIT parameter values with their statutory effective windows.
   National rates have been stable for years; the defense surtax is
   the only one with a future effective-from (FY ≥ 2026-04-01)."
  [;; National CIT — both rates introduced in the 2018 Tax Reform Act,
   ;; flat 23.2 % since 2018-04-01.
   {:parameter-value/parameter      [:parameter/code "JP.CIT.sme-reduced-rate"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "法人税法 §66②; NTA No. 5759 (中小法人の税率特例)"}

   {:parameter-value/parameter      [:parameter/code "JP.CIT.flat-rate"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  0.232M
    :parameter-value/citation       "法人税法 §66①; NTA No. 5759 — flat 23.2 % since 2018-04-01"}

   {:parameter-value/parameter      [:parameter/code "JP.CIT.sme-kink"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  8000000M
    :parameter-value/citation       "法人税法 §66② — ¥8 000 000 kink for SME reduced-rate band"}

   ;; Local CIT — introduced 2014-10-01 at 4.4 %, raised to 10.3 % on
   ;; 2019-10-01 when prefectural inhabitant rates were correspondingly
   ;; reduced (revenue-neutral reform).
   {:parameter-value/parameter       [:parameter/code "JP.LocalCIT.rate"]
    :parameter-value/effective-from  #inst "2014-10-01"
    :parameter-value/effective-until #inst "2019-10-01"
    :parameter-value/decimal-value   0.044M
    :parameter-value/citation        "地方法人税法 §10 (introduction rate)"}

   {:parameter-value/parameter      [:parameter/code "JP.LocalCIT.rate"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.103M
    :parameter-value/citation       "地方法人税法 §10 — 10.3 % from 2019-10-01"}

   ;; Defense surtax — FY ≥ 2026-04-01 only.
   {:parameter-value/parameter      [:parameter/code "JP.DefenseSurtax.rate"]
    :parameter-value/effective-from #inst "2026-04-01"
    :parameter-value/decimal-value  0.04M
    :parameter-value/citation       "防衛力強化のための税制措置 (MOF tax reform outline, 令和7年度)"}

   {:parameter-value/parameter      [:parameter/code "JP.DefenseSurtax.deduction"]
    :parameter-value/effective-from #inst "2026-04-01"
    :parameter-value/decimal-value  5000000M
    :parameter-value/citation       "防衛特別法人税 — ¥5 000 000 basic deduction (MOF outline)"}

   ;; Enterprise tax — SME ladder (national standard rates).
   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.sme-rate-1"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.035M
    :parameter-value/citation       "地方税法 §72-24-7 — national standard 3.5 % on ≤¥4M"}

   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.sme-rate-2"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.053M
    :parameter-value/citation       "地方税法 §72-24-7 — national standard 5.3 % on ¥4M..¥8M"}

   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.sme-rate-3"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.07M
    :parameter-value/citation       "地方税法 §72-24-7 — national standard 7.0 % above ¥8M"}

   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.sme-kink-1"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  4000000M
    :parameter-value/citation       "地方税法 §72-24-7 — SME bracket-1 ceiling"}

   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.sme-kink-2"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  8000000M
    :parameter-value/citation       "地方税法 §72-24-7 — SME bracket-2 ceiling"}

   {:parameter-value/parameter      [:parameter/code "JP.Enterprise.large-rate"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.0118M
    :parameter-value/citation       "地方税法 §72-24-7 — large-corp income-base standard 1.18 %"}

   ;; Special corp enterprise tax — 37 % SME / 260 % large since 2019-10-01.
   {:parameter-value/parameter      [:parameter/code "JP.SpecialCorpEnterprise.sme-rate"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.37M
    :parameter-value/citation       "特別法人事業税及び特別法人事業譲与税に関する法律 §7 (SME 37 %)"}

   {:parameter-value/parameter      [:parameter/code "JP.SpecialCorpEnterprise.large-rate"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  2.60M
    :parameter-value/citation       "特別法人事業税及び特別法人事業譲与税に関する法律 §7 (large 260 %)"}

   ;; Inhabitants' income-levy — 7 % combined standard (1 % pref + 6 %
   ;; municipal); same since the 2019-10-01 reform.
   {:parameter-value/parameter      [:parameter/code "JP.Inhabitant.income-levy-rate"]
    :parameter-value/effective-from #inst "2019-10-01"
    :parameter-value/decimal-value  0.07M
    :parameter-value/citation       "地方税法 §51 + §314-4 — combined 1 % prefectural + 6 % municipal standard"}])

;; ============================================================================
;; Per-capita levy 均等割 table (NOT carried as :parameters — see ns docstring)
;; ============================================================================

(def per-capita-levy-table
  "Hōjin Jūminzei 均等割 — the corporate per-capita inhabitants' levy.
   A 10-cell table over (paid-in-capital-class × headcount-class).
   `:capital-class` keys (consumer-supplied via `:tax-unit`):
     :capital-up-to-10m   capital ≤ ¥10 000 000
     :capital-up-to-100m  ¥10M < capital ≤ ¥100M
     :capital-up-to-1b    ¥100M < capital ≤ ¥1B
     :capital-up-to-5b    ¥1B < capital ≤ ¥5B
     :capital-above-5b    capital > ¥5B
   `:headcount-class` (consumer-supplied via `:tax-unit`; default :small):
     :small   ≤ 50 employees
     :large   > 50 employees

   Citation: 地方税法 §52 + §312 ; JETRO Section 3.3 table; Tokyo
   Metropolitan Bureau of Taxation 法人都民税のあらまし. Tier
   boundaries have been stable since 2015."
  {[:capital-up-to-10m  :small] 70000M
   [:capital-up-to-10m  :large] 140000M
   [:capital-up-to-100m :small] 180000M
   [:capital-up-to-100m :large] 200000M
   [:capital-up-to-1b   :small] 290000M
   [:capital-up-to-1b   :large] 530000M
   [:capital-up-to-5b   :small] 950000M
   [:capital-up-to-5b   :large] 2290000M
   [:capital-above-5b   :small] 1210000M
   [:capital-above-5b   :large] 3800000M})

;; ============================================================================
;; Provisions — JP CIT statute as :provision data
;; ============================================================================

(def provisions
  "JP CIT statutory provisions encoded for the `kontor.statute`
   evaluator. Conditions reference `:component` (set by the provider on
   each per-component pass — `:national` / `:enterprise` / `:inhabitant`)
   and consumer-supplied facts under `[:tax-unit ...]` / `[:inputs ...]`.

   Consequences are compute-fns, `:tax-context-fact` amounts, or
   `:schedule-override`s. JP rates and tier amounts live in
   `:parameter` data (or the `per-capita-levy-table` for the 10-cell
   均等割 lookup), NOT inlined here."

  [;; --------------------------------------------------------------------
   ;; National CIT 法人税
   ;; --------------------------------------------------------------------
   ;; The default schedule (set in the provider) is the SME progressive
   ;; ladder: 15 % on the first ¥8M, 23.2 % thereafter. For large
   ;; corporations (capital > ¥100M ⇒ :is-sme? false) the schedule is
   ;; overridden to a flat 23.2 %. The provision encodes the
   ;; large-corp override per ADR-101 Addendum 1 `:op :schedule-override`.
   {:provision/code            "JP-CIT-§66-large"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "法人税法 §66① — flat 23.2 % schedule for large corporations (capital > ¥100M)"
    :provision/citation        "https://elaws.e-gov.go.jp/document?lawid=340AC0000000034"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :national]
                                        [:eq [:tax-unit :is-sme?] false]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :jp-cit-large-flat
                                        :label "法人税 large-corporation flat 23.2 %"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "JP.CIT.flat-rate"}})}

   ;; 地方法人税 — 10.3 % surtax on the national CIT amount. Same shape
   ;; as DE Soli (late-bound compute-fn reads `:running`).
   {:provision/code            "JP-LocalCIT-§9"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "地方法人税法 §9-§10 — 10.3 % surtax on national CIT"
    :provision/citation        "https://elaws.e-gov.go.jp/document?lawid=426AC0000000011"
    :provision/effective-from  #inst "2014-10-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :national])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :local-corporate-tax
                                        :label "地方法人税 (Local Corporation Tax, 10.3 % × 法人税)"
                                        :amount-from :compute-fn
                                        :fn :jp-local-cit-on-national})}

   ;; 防衛特別法人税 — 4 % × max(0, national CIT − ¥5M). FY ≥ 2026-04-01.
   {:provision/code            "JP-DefenseSurtax"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "防衛特別法人税 — 4 % × max(0, national CIT − ¥5M); FY ≥ 2026-04-01"
    :provision/citation        "https://www.mof.go.jp/tax_policy/summary/corporation/c01.htm"
    :provision/effective-from  #inst "2026-04-01"
    :provision/priority        200
    :provision/condition       (pr-str [:eq :component :national])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :defense-surtax
                                        :label "防衛特別法人税 (Defense Surtax, 4 % × (法人税 − ¥5M))"
                                        :amount-from :compute-fn
                                        :fn :jp-defense-surtax})}

   ;; --------------------------------------------------------------------
   ;; Enterprise tax 事業税
   ;; --------------------------------------------------------------------
   ;; Default schedule (set in the provider) is the SME progressive
   ;; ladder. For large corporations the schedule overrides to flat
   ;; 1.18 % on the income base. (Pro-forma value-added + capital bases
   ;; deferred — note 110 §1 / stress B.)
   {:provision/code            "JP-Enterprise-§72-large"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :elective-regime]
    :provision/title           "地方税法 §72 — flat 1.18 % income-base for large corporations (pro-forma value-added/capital bases not yet shipped)"
    :provision/citation        "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jigyou.html"
    :provision/effective-from  #inst "2019-10-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :enterprise]
                                        [:eq [:tax-unit :is-sme?] false]])
    :provision/consequence     (pr-str {:op :schedule-override
                                        :code :jp-enterprise-large-flat
                                        :label "事業税 large-corporation flat 1.18 %"
                                        :schedule {:schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "JP.Enterprise.large-rate"}})}

   ;; 特別法人事業税 — 37 % SME / 260 % large surtax on the
   ;; enterprise-tax income-base amount (note 110 §1 component 4 /
   ;; stress D). The compute-fn reads `:running` (which inside the
   ;; enterprise component IS the just-computed enterprise tax).
   {:provision/code            "JP-SpecialCorpEnterprise-§7"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "特別法人事業税及び特別法人事業譲与税に関する法律 §7 — 37 % SME / 260 % large surtax on enterprise tax"
    :provision/citation        "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5765.htm"
    :provision/effective-from  #inst "2019-10-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :enterprise])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :special-corp-enterprise-tax
                                        :label "特別法人事業税 (Special Corp Enterprise Tax)"
                                        :amount-from :compute-fn
                                        :fn :jp-special-corp-enterprise})}

   ;; --------------------------------------------------------------------
   ;; Inhabitants' tax 法人住民税 — TWO surtax provisions on the
   ;; :inhabitant component (which carries a zero gross-liability;
   ;; both pieces are layered as surtaxes that don't reference the
   ;; component's own base — see provider docstring).
   ;; --------------------------------------------------------------------
   ;; 法人税割 — 7 % standard combined rate on the national CIT amount.
   ;; The compute-fn reads `:national-cit-amount` from ctx (the provider
   ;; injects it after computing the national component).
   {:provision/code            "JP-Inhabitant-income-levy"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "法人住民税 法人税割 — 7 % combined (1 % pref + 6 % municipal) on national CIT"
    :provision/citation        "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jumin.html"
    :provision/effective-from  #inst "2019-10-01"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :component :inhabitant])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :inhabitant-income-levy
                                        :label "法人住民税 法人税割 (Inhabitants' Income Levy, 7 % × 法人税)"
                                        :amount-from :compute-fn
                                        :fn :jp-inhabitant-income-levy})}

   ;; 均等割 — fixed per-capita levy from the 10-cell capital × headcount
   ;; table. No statutory base; the compute-fn reads `:tax-unit`
   ;; dimensions and looks up `per-capita-levy-table`.
   {:provision/code            "JP-Inhabitant-per-capita"
    :provision/jurisdiction    :jp
    :provision/concept         [:tax-concept/code :surtax]
    :provision/title           "法人住民税 均等割 — fixed per-capita levy (capital × headcount lookup)"
    :provision/citation        "https://www.tax.metro.tokyo.lg.jp/kazei/hojin_jumin.html"
    :provision/effective-from  #inst "2015-04-01"
    :provision/priority        200
    :provision/condition       (pr-str [:eq :component :inhabitant])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :inhabitant-per-capita-levy
                                        :label "法人住民税 均等割 (Per-Capita Inhabitants' Levy)"
                                        :amount-from :compute-fn
                                        :fn :jp-inhabitant-per-capita})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install JP CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:parameter/code` and `:provision/code`
   are unique identity attrs, so re-running the install is a no-op on
   unchanged rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

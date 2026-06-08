(ns kontor.l10n-cn.pit-statute
  "CN personal income tax — Individual Income Tax (个人所得税, IIT) —
   encoded as `kontor.tax.statute` data per ADR-101. Migrates the
   record-shape `cn-iit-provider` (in `period_tax_provider.clj`) to
   statute-as-data — slice. Mirrors
   `kontor.l10n-at.pit-statute` structurally (single-component +
   bracket-from-parameter schedule) but distinguishes from
   AT/DE/FR/JP in a key way: CN PIT does NOT aggregate capital gains
   (category 9) or investment income (category 7) into the
   comprehensive-income base. Those are SEPARATE final taxes at flat
   20 % computed by `cgt-provider` and `investment-income-provider`
   independently.

   The encoding splits along the substrate seams:

   - **Parameters** (date-keyed value history) —
       - `CN.IIT.comprehensive-income.brackets` — §3 ¶1 + §6 IIT Law
         post-2018-reform 7-band progressive scale (3/10/20/25/30/35/45 %).
         Stable since 2019-01-01 (no Kalte-Progression analogue —
         contrast AT EStG since 2023).
       - `CN.IIT.business-income.brackets` — §3 ¶2 IIT Law 5-band
         scale (5/10/20/30/35 %) for sole-traders / individual
         industrial-commercial households.
       - `CN.IIT.basic-deduction` — ¥60 000/year basic deduction
         (¥5 000/month).
       - Seven special-additional-deduction caps —
         children-education / continuing-education / major-medical /
         mortgage-interest / housing-rent / elderly-support /
         infant-care (audit-trail values; not enforced by substrate).

   - **Provisions** (per-jurisdiction rules) — 10 provisions in v1:
       - CN-IITLaw-§3-¶2-business-income-schedule — `:schedule-override`
         swapping the 7-band comprehensive schedule for the 5-band
         business-income schedule when `:tax-unit :business-income?`
         is set.
       - CN-IITLaw-§6-¶1(1)-basic-deduction — `:base-deduct` for the
         ¥60 000/year basic deduction (suppressed for business-income
         filers whose taxable income is already net of the deduction).
       - Seven audit-trail special-additional-deduction provisions
         (children-education / continuing-education / major-medical /
         mortgage-interest / housing-rent / elderly-support /
         infant-care) — each a `:base-deduct` reading consumer-
         supplied `:inputs :pit-base-deductions-<category>`.
       - CN-IITLaw-§6-¶2-statutory-contributions — `:base-deduct`
         for 五险一金 employee contributions (consumer-supplied, the
         payroll layer computes monthly).

   - **Scoping** — all provisions scoped to `:iit` via `[:eq :component
     :iit]`, matching the FR `:is`-component / AT `:est`-component
     discipline.

   ## CN-specific posture: no aggregation across categories

   Per.2.8 the new PIT provider does NOT read CGT /
   investment-income lanes. Category 7 (interest / dividends /
   royalties — 利息、股息、特许权使用费所得) and category 9
   (capital gains — 财产转让所得) are taxed as SEPARATE final taxes at
   flat 20 % via their own providers (`cn-iit-investment-income-provider`
   and `cn-iit-cgt-provider`). The consumer's audit-trail query 'what
   made up this taxpayer's total tax' composes results from THREE
   provider runs, not from one PIT-with-folded-lanes call.

   ## Inputs the consumer supplies (consumed by the provider)

   The PIT provider reads `:inputs :gross-comprehensive-income`
   (sum of category 1 wages/salaries + category 2 labour-service +
   category 3 author's remuneration + category 4 royalties — what
   the substrate calls comprehensive income / 综合所得) + optional
   special-additional-deduction lanes:

   - `:pit-base-deductions-statutory` — 五险一金 employee
     contributions; payroll-computed.
   - `:pit-base-deductions-children-education` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-continuing-education` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-major-medical` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-mortgage-interest` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-housing-rent` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-elderly-support` — STA Ann. 2018 No. 60.
   - `:pit-base-deductions-infant-care` — STA Ann. 2022 No. 7
     (effective 2022-01-01).
   - `:prepaid` — cumulative monthly withholding (累计预扣) feeding
     the annual reconciliation (年度汇算清缴) — ADR-099 Addendum 3.

   For business-income filers, the consumer pre-computes business
   taxable income (already net of basic deduction + business expenses
   per §3 ¶2) and supplies via `:inputs :business-taxable-income`;
   `:tax-unit :business-income? true` swaps the schedule.

   ## Out of scope for v1 ( slice)

   - **Pre-2018-reform brackets** — the v1 ships only the post-reform
     scale; retrospective FY2018-or-earlier assessment requires the
     pre-reform monthly brackets.
   - **Stage-3 reconciliation cascade** (Stage-1 monthly cumulative,
     Stage-2 annual, Stage-3 multi-year offset) — the substrate
     supports Stage-1 + Stage-2 via `:prepaid`; Stage-3 lives outside.
   - **Six-method special bonus tax** (年终奖) — payroll-engine-
     authoritative (per ADR-085); not duplicated here.
   - **Equity-incentive special method** (Caishui [2018] 164) —
     extension provision when a consumer surfaces.

   ## Citations

   `chinatax.gov.cn` for the consolidated statute text;
   `fgk.chinatax.gov.cn` for circulars (Cai Shui, STA Announcement)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "CN IIT parameter definitions — one row per `:kontor.parameter/code`.
   Bracket values live in `parameter-brackets` keyed by
   `:effective-from`; scalar values live in `parameter-values`."
  [{:kontor.parameter/code         "CN.IIT.comprehensive-income.brackets"
    :kontor.parameter/label        "§3 ¶1 + §6 IIT Law (post-2018 reform) — comprehensive-income 7-band annual progressive scale"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"}

   {:kontor.parameter/code         "CN.IIT.business-income.brackets"
    :kontor.parameter/label        "§3 ¶2 IIT Law — business-income (sole-trader / 个体工商户) 5-band progressive scale"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :bracket-scale
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"}

   {:kontor.parameter/code         "CN.IIT.basic-deduction"
    :kontor.parameter/label        "§6 ¶1(1) IIT Law — basic deduction (¥60 000/year = ¥5 000/month)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.children-education-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — children's-education special-additional-deduction cap (per qualifying child, annual)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.continuing-education-academic-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — continuing-education (academic) deduction cap (annual)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.continuing-education-vocational-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — continuing-education (vocational, one-shot) deduction cap"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.major-medical-threshold"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — major-medical excess threshold (out-of-pocket above this is deductible)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.major-medical-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — major-medical deduction cap (annual)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.mortgage-interest-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — mortgage-interest deduction (per couple, annual; 240-month cap)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.housing-rent-cap-tier-1"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — housing-rent deduction (tier-1 cities: BJ / SH / GZ / SZ +)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.housing-rent-cap-tier-2"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — housing-rent deduction (tier-2 cities)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.housing-rent-cap-tier-3"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — housing-rent deduction (tier-3 cities)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.elderly-support-cap"
    :kontor.parameter/label        "STA Ann. 2018 No. 60 — elderly-support deduction (cap, split among siblings)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"}

   {:kontor.parameter/code         "CN.IIT.specials.infant-care-cap"
    :kontor.parameter/label        "STA Ann. 2022 No. 7 — infant-care (0-3) deduction (per qualifying infant, effective 2022-01-01)"
    :kontor.parameter/jurisdiction :cn
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c5174854/content.html"}])

(def parameter-values
  "CN IIT scalar parameter values. Most have been stable since the
   2018-09-10 reform (effective 2019-01-01 for full annual brackets;
   2018-10-01 transitional for the basic deduction). The infant-care
   category was added by STA Announcement 2022 No. 7 effective
   2022-01-01."
  [;; Basic deduction — ¥60 000/year, stable since 2018-10-01.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.basic-deduction"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  60000M
    :kontor.parameter-value/citation       "§6 ¶1(1) IIT Law (post-2018 reform; STA Announcement 2018 No. 56 transitional) — ¥60 000/year basic deduction, stable since 2018-10-01"}

   ;; Special-additional-deduction caps — STA Announcement 2018 No. 60
   ;; (the original 6 categories) + STA Ann. 2022 No. 7 (infant-care).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.children-education-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  24000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — children's-education ¥24 000/year per qualifying child (post-2023 raise from ¥12 000 per Cai Shui [2023] 13)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.continuing-education-academic-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  4800M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — continuing-education (academic) ¥4 800/year"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.continuing-education-vocational-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  3600M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — continuing-education (vocational, one-shot) ¥3 600"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.major-medical-threshold"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  15000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — major-medical out-of-pocket threshold ¥15 000"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.major-medical-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  80000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — major-medical deduction cap ¥80 000/year"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.mortgage-interest-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  12000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — mortgage-interest ¥12 000/year (¥1 000/mo) per couple; 240-month cap"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.housing-rent-cap-tier-1"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  18000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — housing-rent tier-1 ¥18 000/year"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.housing-rent-cap-tier-2"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  13200M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — housing-rent tier-2 ¥13 200/year"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.housing-rent-cap-tier-3"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  9600M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — housing-rent tier-3 ¥9 600/year"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.elderly-support-cap"]
    :kontor.parameter-value/effective-from #inst "2018-10-01"
    :kontor.parameter-value/decimal-value  36000M
    :kontor.parameter-value/citation       "STA Ann. 2018 No. 60 — elderly-support ¥36 000/year (split among siblings; post-2023 raise from ¥24 000 per Cai Shui [2023] 13)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CN.IIT.specials.infant-care-cap"]
    :kontor.parameter-value/effective-from #inst "2022-01-01"
    :kontor.parameter-value/decimal-value  24000M
    :kontor.parameter-value/citation       "STA Announcement 2022 No. 7 — infant-care (0-3) ¥24 000/year per qualifying infant (post-2023 raise from ¥12 000 per Cai Shui [2023] 13)"}])

(def parameter-brackets
  "CN IIT progressive bracket scales —
     - `CN.IIT.comprehensive-income.brackets` (7 bands × 1
       effective-from = 7 rows; stable since 2019-01-01).
     - `CN.IIT.business-income.brackets` (5 bands × 1 effective-from
       = 5 rows; stable since 2019-01-01).

   Rates 3/10/20/25/30/35/45 % (comprehensive) and 5/10/20/30/35 %
   (business-income) have been stable since the 2018 reform
   (effective 2019-01-01). No annual indexation — contrast AT EStG
   Kalte-Progression-Abschaffung since 2023.

   Total: 12 bracket rows."
  ;; comprehensive-income brackets (post-2018 reform, stable)
  [{:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          0
    :kontor.parameter-bracket/rate           0.03M
    :kontor.parameter-bracket/upper          36000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          1
    :kontor.parameter-bracket/rate           0.10M
    :kontor.parameter-bracket/upper          144000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          2
    :kontor.parameter-bracket/rate           0.20M
    :kontor.parameter-bracket/upper          300000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          3
    :kontor.parameter-bracket/rate           0.25M
    :kontor.parameter-bracket/upper          420000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          4
    :kontor.parameter-bracket/rate           0.30M
    :kontor.parameter-bracket/upper          660000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          5
    :kontor.parameter-bracket/rate           0.35M
    :kontor.parameter-bracket/upper          960000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   ;; band 6: top, open
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.comprehensive-income.brackets"]
    :kontor.parameter-bracket/index          6
    :kontor.parameter-bracket/rate           0.45M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}

   ;; business-income brackets (§3 ¶2)
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.business-income.brackets"]
    :kontor.parameter-bracket/index          0
    :kontor.parameter-bracket/rate           0.05M
    :kontor.parameter-bracket/upper          30000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.business-income.brackets"]
    :kontor.parameter-bracket/index          1
    :kontor.parameter-bracket/rate           0.10M
    :kontor.parameter-bracket/upper          90000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.business-income.brackets"]
    :kontor.parameter-bracket/index          2
    :kontor.parameter-bracket/rate           0.20M
    :kontor.parameter-bracket/upper          300000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.business-income.brackets"]
    :kontor.parameter-bracket/index          3
    :kontor.parameter-bracket/rate           0.30M
    :kontor.parameter-bracket/upper          500000M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}
   ;; band 4: top, open
   {:kontor.parameter-bracket/parameter      [:kontor.parameter/code "CN.IIT.business-income.brackets"]
    :kontor.parameter-bracket/index          4
    :kontor.parameter-bracket/rate           0.35M
    :kontor.parameter-bracket/effective-from #inst "2019-01-01"}])

;; ============================================================================
;; Provisions — CN IIT statute as :provision data
;; ============================================================================

(def provisions
  "CN IIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (always `:iit` in v1)
   and gate on the presence of driver facts. Consequences are
   `:tax-context-fact` reads (consumer-pre-computed special-additional
   deductions, statutory contributions) or `:amount-from :parameter`
   reads (basic deduction); rates and amounts come from `:parameter`
   data, NOT inlined.

   The comprehensive-income progressive schedule lives as
   `:parameter-bracket` data — read by the provider directly via
   `kontor.tax.statute/parameter-brackets-at`, not via a provision.
   The business-income 5-band schedule swap (provision #1) is the only
   `:schedule-override`."
  [;; ----------------------------------------------------------------
   ;; §3 ¶2 IIT Law — Business-income 5-band schedule override
   ;; ----------------------------------------------------------------
   ;; Sole-traders / 个体工商户 / partnership-allocated individuals
   ;; use a SEPARATE 5-band schedule (NOT comprehensive). Consumer
   ;; pre-computes business taxable income (already net of business
   ;; expenses + basic deduction per §3 ¶2) and supplies via
   ;; `:inputs :business-taxable-income`; `:business-income? true`
   ;; swaps the schedule.
   {:kontor.provision/code           "CN-IITLaw-§3-¶2-business-income-schedule"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title          "§3 ¶2 IIT Law — Business-income 5-band schedule (个体工商户)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:eq [:tax-unit :business-income?] true]])
    :kontor.provision/consequence    (pr-str {:op       :schedule-override
                                              :code     :cn-business-income
                                              :label    "Business-income 5-band schedule (§3 ¶2)"
                                              :schedule {:kontor.schedule/type :progressive-bracket
                                                         :brackets-from :parameter
                                                         :parameter "CN.IIT.business-income.brackets"}})}

   ;; ----------------------------------------------------------------
   ;; §6 ¶1(1) IIT Law — Basic deduction (¥60 000/yr)
   ;; ----------------------------------------------------------------
   ;; Suppressed for business-income filers — their basic deduction is
   ;; built into the upstream :business-taxable-income per consumer
   ;; practice (§3 ¶2 already accounts for the basic deduction at the
   ;; sole-trader's books).
   {:kontor.provision/code           "CN-IITLaw-§6-¶1(1)-basic-deduction"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§6 ¶1(1) IIT Law — Basic deduction (¥60 000/yr; ¥5 000/mo)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"
    :kontor.provision/effective-from #inst "2018-10-01"
    :kontor.provision/priority       100
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :gross-comprehensive-income] 0M]
                                              [:not [:eq [:tax-unit :business-income?] true]]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-basic-deduction
                                              :label       "Basic deduction (¥60 000/yr)"
                                              :amount-from :parameter
                                              :parameter   "CN.IIT.basic-deduction"})}

   ;; ----------------------------------------------------------------
   ;; §6 ¶2 IIT Law — Statutory contributions (五险一金)
   ;; ----------------------------------------------------------------
   ;; The five social-insurance schemes + housing-fund employee
   ;; contributions are pre-tax deductible. Consumer supplies the
   ;; per-period sum (payroll-computed monthly; annual reconciliation
   ;; consumer sums and passes via `:inputs
   ;; :pit-base-deductions-statutory`).
   {:kontor.provision/code           "CN-IITLaw-§6-¶2-statutory-contributions"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "§6 ¶2 IIT Law — Statutory contributions (五险一金 employee portion)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/n810341/n810755/c4015770/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       110
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-statutory] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-statutory-contributions
                                              :label       "Statutory contributions (五险一金)"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-statutory]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Children's education special-additional
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-children-education"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Children's education (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       200
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-children-education] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-children-education
                                              :label       "Children's education special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-children-education]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Continuing education
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-continuing-education"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Continuing education (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       210
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-continuing-education] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-continuing-education
                                              :label       "Continuing education special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-continuing-education]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Major medical
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-major-medical"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Major medical (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       220
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-major-medical] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-major-medical
                                              :label       "Major medical special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-major-medical]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Mortgage interest
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-mortgage-interest"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Mortgage interest (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       230
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-mortgage-interest] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-mortgage-interest
                                              :label       "Mortgage interest special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-mortgage-interest]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Housing rent
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-housing-rent"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Housing rent (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       240
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-housing-rent] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-housing-rent
                                              :label       "Housing rent special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-housing-rent]})}

   ;; ----------------------------------------------------------------
   ;; STA Ann. 2018 No. 60 — Elderly support
   ;; ----------------------------------------------------------------
   {:kontor.provision/code           "CN-STA-2018-60-elderly-support"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Ann. 2018 No. 60 — Elderly support (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c3956023/content.html"
    :kontor.provision/effective-from #inst "2019-01-01"
    :kontor.provision/priority       250
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-elderly-support] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-elderly-support
                                              :label       "Elderly support special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-elderly-support]})}

   ;; ----------------------------------------------------------------
   ;; STA Announcement 2022 No. 7 — Infant care (0-3)
   ;; ----------------------------------------------------------------
   ;; The 7th special-additional-deduction category, added effective
   ;; 2022-01-01 per STA Ann. 2022 No. 7.
   {:kontor.provision/code           "CN-STA-2022-7-infant-care"
    :kontor.provision/jurisdiction   :cn
    :kontor.provision/concept        [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title          "STA Announcement 2022 No. 7 — Infant care 0-3 (special-additional deduction)"
    :kontor.provision/citation       "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c5174854/content.html"
    :kontor.provision/effective-from #inst "2022-01-01"
    :kontor.provision/priority       260
    :kontor.provision/condition      (pr-str [:and
                                              [:eq :component :iit]
                                              [:gt [:inputs :pit-base-deductions-infant-care] 0M]])
    :kontor.provision/consequence    (pr-str {:op          :base-deduct
                                              :code        :cn-infant-care
                                              :label       "Infant care (0-3) special-additional deduction"
                                              :amount-from :tax-context-fact
                                              :fact        [:inputs :pit-base-deductions-infant-care]})}])

;; ============================================================================
;; Install! — transact parameters + parameter-values + brackets + provisions
;; ============================================================================

(defn- bracket-row-already-present?
  "True iff a `:parameter-bracket` row with the same `(parameter-code,
   index, effective-from)` triple is already in `db`. Used to make the
   bracket install idempotent — `:parameter-bracket` carries no
   `:db/unique :db.unique/identity` attr in the kernel schema (the
   parent `:kontor.parameter/code` is the natural-key seam; the
   bracket's identity is the `(parent, index, effective-from)` triple),
   so the provider must do the dedup itself.

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
  "Install CN PIT statute (parameters + parameter-values + bracket
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
                             parameter-brackets)]
    (when (seq new-brackets)
      (d/transact conn (vec new-brackets))))
  (d/transact conn provisions))

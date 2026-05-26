(ns kontor.l10n-cn.cgt-statute
  "CN capital-gains tax — IIT category 9 (财产转让所得) + EIT inclusion
   (property-transfer income) — encoded as `kontor.statute` data per
   ADR-101. Research note 133.

   The Chinese CGT regime is a stack of four statutory shapes (note 133
   §1):

   - **IIT 财产转让所得 (category 9)** — flat 20 % on net gain for
     individuals (个人所得税法 §3). Carve-outs covered here:

       * **Listed A-/B-share gains for mainland individuals — TEMPORARILY
         EXEMPT** since Caishui [1998] No. 61 (extended by [2005] 35,
         [2009] 167; still in force in 2026). The provider returns
         **NO COMPONENT** for `:cn-listed-a-share / :cn-listed-b-share`
         when the holder is a resident individual.
       * **Stock Connect H-shares for mainland residents — EXEMPT
         through 2027-12-31** per Caishui [2014] No. 81 + successive
         extensions (current MOF/SAT/CSRC joint notice 2024).
       * **滿五唯一 residential exemption** (Guoshuifa [2006] No. 108
         §1): BOTH 5-year self-occupation AND family-sole-residence in
         the same province → fully exempt (both prongs required).
       * **Real-estate deemed-rate election** (Guoshuifa [2006] No.
         108): when basis cannot be substantiated the taxpayer may
         elect a provincial 1–3 % rate on gross proceeds instead of
         20 % on net gain.

   - **EIT 25 %** — corporate transfers fold into ordinary EIT income
     (企业所得税法 §4 + §6); no separate CGT. Carve-outs:

       * **Special restructuring deferral** — Caishui [2009] No. 59 +
         lowered threshold Caishui [2014] No. 109 (50 % asset/equity,
         85 % equity-payment, 12-month lockup + business continuity).
         When all met (the consumer attests qualitative criteria via
         `:audit-doc`) the equity-paid slice is fully deferred.
       * **100%-controlled intra-group transfer** — Caishui [2014] No.
         109 §3 — relaxed track at net book value.
       * **Non-cash-asset 5-year spread** — Caishui [2014] No. 116.
       * **SAT Announcement [2015] No. 7 indirect transfer** —
         re-characterisation of offshore HoldCo equity transfers as
         direct transfers of Chinese taxable property.

   See note 133 §1 for the full discussion and `cgt-provider` for the
   determination logic. LAT (土地增值税) is STRUCTURALLY SEPARATE and
   lives in `kontor.l10n-cn.lat-provider` per ADR-099 §5.3.

   Citations point at chinatax.gov.cn (the SAT's official portal) and
   the State Council fgk (法规库) where applicable; circular numbers
   (Caishui / Guoshuifa) are stable identifiers in PRC tax law."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "CN CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- IIT category 9 flat rate -------------------------------------------
   {:parameter/code         "CN.IIT.CGT.flat-rate"
    :parameter/label        "IIT category 9 (财产转让所得) flat rate — 20 %"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html"}

   ;; --- EIT standard rate ---------------------------------------------------
   {:parameter/code         "CN.EIT.standard-rate"
    :parameter/label        "EIT standard rate — 25 % (企业所得税法 §4)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100009/"}

   ;; --- EIT non-resident WHT on gross gain ---------------------------------
   {:parameter/code         "CN.EIT.non-resident-wht-rate"
    :parameter/label        "Non-resident enterprise WHT on equity-transfer gross gain — 10 %"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/index.html"}

   ;; --- Special-restructuring eligibility thresholds (Caishui [2014] 109) --
   {:parameter/code         "CN.EIT.special-restructuring.equity-threshold"
    :parameter/label        "Caishui [2014] 109 §1: ≥ 50 % equity/asset share threshold"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100012/"}

   {:parameter/code         "CN.EIT.special-restructuring.equity-payment-threshold"
    :parameter/label        "Caishui [2009] 59 §5: ≥ 85 % equity-consideration share"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100012/"}

   {:parameter/code         "CN.EIT.special-restructuring.lockup-months"
    :parameter/label        "Caishui [2009] 59 §5: 12-month transferor lockup + business continuity"
    :parameter/jurisdiction :cn
    :parameter/unit         :months
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100012/"}

   ;; --- 滿五唯一 residential exemption ---------------------------------------
   {:parameter/code         "CN.IIT.manwuweiyi.years"
    :parameter/label        "Guoshuifa [2006] 108 §1: 5-year self-occupation prong"
    :parameter/jurisdiction :cn
    :parameter/unit         :years
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html"}

   ;; --- Real-estate deemed-rate band (provincial, 1-3 %) -------------------
   {:parameter/code         "CN.IIT.CGT.real-estate.deemed-rate-floor"
    :parameter/label        "Guoshuifa [2006] 108 deemed-rate floor — 1 %"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html"}

   {:parameter/code         "CN.IIT.CGT.real-estate.deemed-rate-ceiling"
    :parameter/label        "Guoshuifa [2006] 108 deemed-rate ceiling — 3 %"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html"}

   ;; --- Stock Connect exemption window end-date -----------------------------
   {:parameter/code         "CN.IIT.stock-connect.exemption-until"
    :parameter/label        "Stock Connect mainland-individual IIT exemption end date — Caishui [2014] 81 (extended)"
    :parameter/jurisdiction :cn
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5210255/content.html"}])

;; ============================================================================
;; Parameter values — current statutory windows
;; ============================================================================

(def parameter-values
  "CN CGT parameter values. Dates align with the statutory effective
   windows (note 133 §7 sources)."
  [;; --- IIT 20 % flat rate — Caishui [1998] 61 effective; rate stable
   ;;     since the 1980 IIT Law promulgated 20 % for category 9.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.CGT.flat-rate"]
    :parameter-value/effective-from #inst "1980-09-10"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "中华人民共和国个人所得税法 §3 — 20 % flat for category 9"}

   ;; --- EIT 25 % standard — 2008 EIT Law promulgated rate, stable
   ;;     through subsequent 2017/2018 amendments.
   {:parameter-value/parameter      [:parameter/code "CN.EIT.standard-rate"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "中华人民共和国企业所得税法 §4 — 25 % standard rate"}

   ;; --- Non-resident WHT 10 % — SAT Announcement [2017] No. 37 +
   ;;     EIT Law Article 4 (reduced rate for non-resident WHT income).
   {:parameter-value/parameter      [:parameter/code "CN.EIT.non-resident-wht-rate"]
    :parameter-value/effective-from #inst "2017-12-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "SAT Announcement [2017] No. 37; EIT Law §4 reduced-rate WHT"}

   ;; --- Special-restructuring thresholds.
   ;; Caishui [2009] 59 originally set 75% / 85% equity criteria;
   ;; Caishui [2014] 109 §1 LOWERED the asset/equity criterion from
   ;; 75% to 50%, effective 2014-01-01.
   {:parameter-value/parameter      [:parameter/code "CN.EIT.special-restructuring.equity-threshold"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/effective-until #inst "2014-01-01"
    :parameter-value/decimal-value  0.75M
    :parameter-value/citation       "Caishui [2009] 59 §5 — original 75% equity/asset criterion"}

   {:parameter-value/parameter      [:parameter/code "CN.EIT.special-restructuring.equity-threshold"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "Caishui [2014] 109 §1 — threshold lowered 75 % → 50 %"}

   {:parameter-value/parameter      [:parameter/code "CN.EIT.special-restructuring.equity-payment-threshold"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  0.85M
    :parameter-value/citation       "Caishui [2009] 59 §5 — ≥ 85 % equity consideration (cash ≤ 15 %)"}

   {:parameter-value/parameter      [:parameter/code "CN.EIT.special-restructuring.lockup-months"]
    :parameter-value/effective-from #inst "2009-01-01"
    :parameter-value/decimal-value  12M
    :parameter-value/citation       "Caishui [2009] 59 §5 — 12 consecutive months of business continuity + lockup"}

   ;; --- 滿五唯一 5-year prong.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.manwuweiyi.years"]
    :parameter-value/effective-from #inst "2006-07-18"
    :parameter-value/decimal-value  5M
    :parameter-value/citation       "Guoshuifa [2006] 108 §1 — 5-year self-occupation prong of 滿五唯一"}

   ;; --- Deemed-rate band 1-3 %.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.CGT.real-estate.deemed-rate-floor"]
    :parameter-value/effective-from #inst "2006-07-18"
    :parameter-value/decimal-value  0.01M
    :parameter-value/citation       "Guoshuifa [2006] 108 — deemed-rate floor 1 % (provincial)"}

   {:parameter-value/parameter      [:parameter/code "CN.IIT.CGT.real-estate.deemed-rate-ceiling"]
    :parameter-value/effective-from #inst "2006-07-18"
    :parameter-value/decimal-value  0.03M
    :parameter-value/citation       "Guoshuifa [2006] 108 — deemed-rate ceiling 3 % (provincial)"}

   ;; --- Stock Connect exemption window end (currently 2027-12-31 per
   ;;     the 2024 MOF/SAT/CSRC joint notice extending Caishui [2014]
   ;;     81). Encoded as :days millisecond epoch (substrate's
   ;;     :amount-money/days slots both take BigDecimal).
   {:parameter-value/parameter      [:parameter/code "CN.IIT.stock-connect.exemption-until"]
    :parameter-value/effective-from #inst "2014-11-17"
    :parameter-value/decimal-value  (bigdec (.getTime #inst "2027-12-31"))
    :parameter-value/citation       "Caishui [2014] 81 + 2024 MOF/SAT/CSRC extension to 2027-12-31"}])

;; ============================================================================
;; Provisions — IIT exemptions + EIT exception family
;; ============================================================================
;;
;; Most CN CGT logic lives in the PROVIDER (classification by asset-class,
;; exemption verification, the deemed-rate base substitution, the
;; special-restructuring deferral). The :provision data covers what the
;; ADR-101 evaluator can express cleanly: the LISTED-share exemption +
;; the IIT 20 % flat rate as a schedule-override (when not exempt).

(def provisions
  "CN CGT provisions encoded for the `kontor.statute` evaluator.

   These provisions are AUDIT-TRAIL data — the provider performs the
   classification + exemption gates in code. The provisions catalogue
   the statutory authority (Caishui circular + IIT Law article) so a
   resolved item carries its provenance.

   Note: the listed-A-share exemption + 滿五唯一 are
   PROVIDER-VERIFIED (require code to test sole-residence /
   holding-period); we keep the citations as :provision data so
   future statute queries (e.g. `(statute/applicable-provisions ...)`)
   surface them, but the provider does NOT fold them via
   `apply-provisions` for the v1 ship."

  [;; --------------------------------------------------------------------
   ;; Caishui [1998] 61 — Listed A/B-share gains for individuals exempt.
   ;; Encoded as a :base-deduct provision so an audit query surfaces it.
   ;; The provider verifies the routing in code (returns NO component
   ;; rather than fold a deduction).
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-1998-61-listed-A-share-exemption"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "Caishui [1998] 61 — Listed A-/B-share temporary exemption for individuals"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/199803/c1197999/content.html"
    :provision/effective-from  #inst "1998-03-30"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:in :asset-class [:cn-listed-a-share :cn-listed-b-share]]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :cn-caishui-1998-61
                                        :label "Listed A-/B-share temporary IIT exemption"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :listed-share-gain]})}

   ;; --------------------------------------------------------------------
   ;; Caishui [2014] 81 — Stock Connect mainland-individual exemption.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-2014-81-stock-connect-exemption"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "Caishui [2014] 81 — Stock Connect H-share IIT exemption (mainland residents)"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5210255/content.html"
    :provision/effective-from  #inst "2014-11-17"
    :provision/effective-until #inst "2028-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :asset-class :cn-listed-h-share-via-connect]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :cn-stock-connect
                                        :label "Stock Connect H-share exemption (through 2027-12-31)"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :listed-share-gain]})}

   ;; --------------------------------------------------------------------
   ;; Guoshuifa [2006] 108 §1 — 滿五唯一 exemption.
   ;; Audit-trail provision; provider verifies the prongs.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Guoshuifa-2006-108-manwuweiyi"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :holding-period-preference]
    :provision/title           "Guoshuifa [2006] 108 §1 — 滿五唯一 residential exemption"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810765/n812183/200607/c1197073/content.html"
    :provision/effective-from  #inst "2006-07-18"
    :provision/priority        120
    :provision/condition       (pr-str [:and
                                        [:eq :asset-class :cn-residential]
                                        [:eq :residence? true]
                                        [:eq [:tax-unit :family-sole-residence?] true]
                                        [:geq [:inputs :holding-years] 5M]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :cn-manwuweiyi
                                        :label "滿五唯一 residential IIT exemption"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :residential-gain]})}

   ;; --------------------------------------------------------------------
   ;; Caishui [2009] 59 + [2014] 109 — special restructuring deferral.
   ;; Audit-trail provision; provider applies the carry-over basis
   ;; treatment in code.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-2009-59-special-restructuring"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :rollover-relief]
    :provision/title           "Caishui [2009] 59 + [2014] 109 — special-restructuring tax deferral"
    :provision/citation        "https://fgk.chinatax.gov.cn/zcfgk/c100012/"
    :provision/effective-from  #inst "2009-01-01"
    :provision/priority        130
    :provision/condition       (pr-str [:and
                                        [:eq :elective-regime :cn-special-restructuring]
                                        [:eq [:tax-unit :tax-residency] :resident-corporation]])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :cn-special-restructuring
                                        :label "Special-restructuring equity-paid deferral"
                                        :amount-from :tax-context-fact
                                        :fact [:inputs :equity-paid-gain]})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install CN CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

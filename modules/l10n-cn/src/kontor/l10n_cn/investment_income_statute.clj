(ns kontor.l10n-cn.investment-income-statute
  "CN investment-income tax — IIT category 7 (利息、股息、红利所得) +
   EIT inter-TRR exemption — encoded as `kontor.statute` data per
   ADR-101. Research note 158.

   The Chinese investment-income regime is a stack of three statutory
   shapes (note 158 §1):

   - **IIT category 7 — 20 % flat** on dividends + interest for
     individuals (个人所得税法 §3). Carve-outs covered here:

       * **Listed A-share dividend differential (Caishui [2015] 101)** —
         holding-period gradation:
           - ≤ 1 month  → full 20 %
           - 1m – 1y    → half (10 % effective)
           - > 1 year   → fully exempt
         Encoded via three `:op :schedule-override` provisions keyed on
         the per-event `:holding-band` ctx the provider injects. ADR-101
         Addendum 1.

       * **Stock Connect H-share dividend** for mainland residents —
         exempt via Caishui [2014] 81 (currently sunset 2027-12-31 per
         the 2024 MOF/SAT/CSRC joint extension). Encoded via
         `:op :schedule-override` with `period-from-before #inst
         \"2028-01-01\"` (ADR-101 Addendum 2 — the exemption applies to
         periods BEGINNING before the sunset).

       * **Bank savings deposit interest** — 0 % since Caishui [2008]
         132 (effective 2008-10-09; was 5 % between 2007-08-15 and
         2008-10-08). Encoded as a two-value rate parameter; the
         provider checks `:income-class :cn-bank-deposit-interest` and
         emits NO COMPONENT when the rate is 0.

       * **Government bond interest** — 0 % under 个税法 §4(2) + 国债
         条例. Same provider treatment as bank interest.

   - **EIT 25 % standard** with the inter-TRR §26(2) exemption —
     corporate dividends from qualifying resident enterprises are
     fully excluded from EIT base. Covered:

       * **Direct unlisted** — always excluded.
       * **Listed shares held > 12 months** — excluded (Impl. Reg. §83).
       * **Listed shares held ≤ 12 months** — INCLUDED at 25 %.
       * **Partnership-allocated dividend** — §26(2) NOT available
         (Caishui [2008] 159 §4; partnership veil for §26(2)).
       * **Foreign-source dividend** — included at 25 % with §23
         foreign-tax credit (FTC computed provider-side).

   - **Outbound WHT** — 10 % standard on dividends to non-resident
     enterprises (Caishui [2008] 130); 20 % to non-resident
     individuals. Treaty reductions are consumer-supplied via
     `:tax-unit :treaty-rate`.

   See note 158 §1 for the full discussion and `investment-income-
   provider` for the determination logic. Most CN logic is
   PROVIDER-VERIFIED (the holding-band classification + partnership
   veil check require code); the statute provisions document the
   statutory authority + carry the rate parameters for ADR-101 audit
   queries.

   Citations point at chinatax.gov.cn / fgk.chinatax.gov.cn / shanghai
   .chinatax.gov.cn (SAT portals) where applicable; circular numbers
   (Caishui / Guoshuifa) are stable identifiers in PRC tax law."
  (:require [datahike.api :as d]
            [kontor.statute :as statute]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "CN investment-income parameter definitions. Values live in
   `parameter-values`, keyed by `:effective-from`."

  [;; --- IIT category 7 flat rate -------------------------------------------
   {:parameter/code         "CN.IIT.investment-income.flat-rate"
    :parameter/label        "IIT category 7 (利息、股息、红利所得) flat rate — 20 %"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html"}

   ;; --- Caishui [2015] 101 listed-A-share gradation factors ----------------
   {:parameter/code         "CN.IIT.investment-income.listed-A.le-1m-factor"
    :parameter/label        "Caishui [2015] 101 — ≤ 1 month band base factor (full)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"}

   {:parameter/code         "CN.IIT.investment-income.listed-A.1m-1y-factor"
    :parameter/label        "Caishui [2015] 101 — 1m–1y band base factor (half)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"}

   {:parameter/code         "CN.IIT.investment-income.listed-A.gt-1y-factor"
    :parameter/label        "Caishui [2015] 101 — > 1 year band base factor (exempt)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"}

   ;; --- Holding-band boundaries (days) -------------------------------------
   {:parameter/code         "CN.IIT.investment-income.listed-A.le-1m-days"
    :parameter/label        "Caishui [2015] 101 — ≤ 1 month boundary (31 days)"
    :parameter/jurisdiction :cn
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"}

   {:parameter/code         "CN.IIT.investment-income.listed-A.gt-1y-days"
    :parameter/label        "Caishui [2015] 101 — > 1 year boundary (365 days strict)"
    :parameter/jurisdiction :cn
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"}

   ;; --- Stock Connect H-share dividend WHT rate ----------------------------
   {:parameter/code         "CN.IIT.investment-income.stock-connect-rate"
    :parameter/label        "Stock Connect H-share dividend rate (mainland individual, Caishui [2014] 81)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5210255/content.html"}

   ;; --- Stock Connect exemption sunset --------------------------------------
   {:parameter/code         "CN.IIT.investment-income.stock-connect.exemption-until"
    :parameter/label        "Stock Connect mainland-individual dividend exemption end date — Caishui [2014] 81 (extended)"
    :parameter/jurisdiction :cn
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5210255/content.html"}

   ;; --- Bank savings deposit interest rate ---------------------------------
   {:parameter/code         "CN.IIT.investment-income.bank-deposit-rate"
    :parameter/label        "Bank savings deposit interest rate — Caishui [2008] 132 (exempt since 2008-10-09)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://shanghai.chinatax.gov.cn/zcfw/zcfgk/grsds/200810/t288953.html"}

   ;; --- Government bond interest rate --------------------------------------
   {:parameter/code         "CN.IIT.investment-income.gov-bond-rate"
    :parameter/label        "Government bond interest rate — 0 % under §4(2) 国债条例"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100009/"}

   ;; --- Outbound WHT (non-resident corp) -----------------------------------
   {:parameter/code         "CN.EIT.outbound-wht-rate"
    :parameter/label        "Outbound dividend WHT to non-resident enterprise — 10 % (Caishui [2008] 130)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c101434/index.html"}

   ;; --- Outbound WHT (non-resident individual) -----------------------------
   {:parameter/code         "CN.IIT.outbound-wht-rate"
    :parameter/label        "Outbound dividend WHT to non-resident individual — 20 % (IIT Law §3)"
    :parameter/jurisdiction :cn
    :parameter/unit         :rate
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html"}

   ;; --- EIT §26(2) inter-TRR exemption holding-period threshold -------------
   {:parameter/code         "CN.EIT.investment-income.inter-TRR-hold-months"
    :parameter/label        "Inter-TRR dividend exemption listed-share hold threshold — 12 months (EIT Impl. Reg. §83)"
    :parameter/jurisdiction :cn
    :parameter/unit         :months
    :parameter/concept-iri  "https://fgk.chinatax.gov.cn/zcfgk/c100012/"}])

;; ============================================================================
;; Parameter values — current statutory windows
;; ============================================================================

(def parameter-values
  "CN investment-income parameter values. Dates align with the
   statutory effective windows (note 158 §7 sources)."

  [;; --- IIT 20 % flat — stable since the 1980 IIT Law's category-7 rate.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.flat-rate"]
    :parameter-value/effective-from #inst "1980-09-10"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "中华人民共和国个人所得税法 §3 — 20 % flat for category 7"}

   ;; --- Caishui [2015] 101 holding-band factors (effective 2015-09-08).
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.listed-A.le-1m-factor"]
    :parameter-value/effective-from #inst "2015-09-08"
    :parameter-value/decimal-value  1.00M
    :parameter-value/citation       "Caishui [2015] 101 §1 — full base for ≤ 1 month holding"}

   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.listed-A.1m-1y-factor"]
    :parameter-value/effective-from #inst "2015-09-08"
    :parameter-value/decimal-value  0.50M
    :parameter-value/citation       "Caishui [2015] 101 §1 — half base for 1m–1y holding"}

   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.listed-A.gt-1y-factor"]
    :parameter-value/effective-from #inst "2015-09-08"
    :parameter-value/decimal-value  0M
    :parameter-value/citation       "Caishui [2015] 101 §1 — full exemption for > 1 year holding"}

   ;; --- Boundary days.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.listed-A.le-1m-days"]
    :parameter-value/effective-from #inst "2015-09-08"
    :parameter-value/decimal-value  31M
    :parameter-value/citation       "Caishui [2015] 101 — 1-month boundary measured in days"}

   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.listed-A.gt-1y-days"]
    :parameter-value/effective-from #inst "2015-09-08"
    :parameter-value/decimal-value  365M
    :parameter-value/citation       "Caishui [2015] 101 — > 1 year measured as > 365 days strict"}

   ;; --- Stock Connect H-share rate (currently 0 % through 2027-12-31).
   ;; The provider gates this with the period-from-before sunset; when
   ;; the sunset passes the parameter SHOULD be re-set to 0.20M (or
   ;; whatever the post-sunset reform settles on).
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.stock-connect-rate"]
    :parameter-value/effective-from #inst "2014-11-17"
    :parameter-value/decimal-value  0M
    :parameter-value/citation       "Caishui [2014] 81 + 2024 joint extension — exempt through 2027-12-31"}

   ;; --- Stock Connect sunset end date (epoch ms, per CGT statute convention).
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.stock-connect.exemption-until"]
    :parameter-value/effective-from #inst "2014-11-17"
    :parameter-value/decimal-value  (bigdec (.getTime #inst "2027-12-31"))
    :parameter-value/citation       "Caishui [2014] 81 + 2024 MOF/SAT/CSRC extension to 2027-12-31"}

   ;; --- Bank deposit interest rate — pre-2008 5 % → 0 % from 2008-10-09.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.bank-deposit-rate"]
    :parameter-value/effective-from #inst "2007-08-15"
    :parameter-value/effective-until #inst "2008-10-09"
    :parameter-value/decimal-value  0.05M
    :parameter-value/citation       "Caishui [2007] 64 — bank deposit interest reduced to 5 % (transitional)"}

   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.bank-deposit-rate"]
    :parameter-value/effective-from #inst "2008-10-09"
    :parameter-value/decimal-value  0M
    :parameter-value/citation       "Caishui [2008] 132 — bank savings deposit interest IIT exempt from 2008-10-09"}

   ;; --- Government bond — always 0 %.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.investment-income.gov-bond-rate"]
    :parameter-value/effective-from #inst "1980-09-10"
    :parameter-value/decimal-value  0M
    :parameter-value/citation       "个人所得税法 §4(2) + 国债条例 — government bond interest IIT exempt"}

   ;; --- Outbound corporate WHT — 10 % stable since 2008.
   {:parameter-value/parameter      [:parameter/code "CN.EIT.outbound-wht-rate"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Caishui [2008] 130 — 10 % WHT on outbound dividends to non-resident enterprises"}

   ;; --- Outbound IIT WHT — 20 % from 1994 IIT Law amendment.
   {:parameter-value/parameter      [:parameter/code "CN.IIT.outbound-wht-rate"]
    :parameter-value/effective-from #inst "1994-01-01"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "个人所得税法 §3 — 20 % WHT on non-resident-individual dividends"}

   ;; --- 12-month inter-TRR threshold.
   {:parameter-value/parameter      [:parameter/code "CN.EIT.investment-income.inter-TRR-hold-months"]
    :parameter-value/effective-from #inst "2008-01-01"
    :parameter-value/decimal-value  12M
    :parameter-value/citation       "企业所得税法实施条例 §83 — 12-month listed-share holding test for §26(2)"}])

;; ============================================================================
;; Provisions
;; ============================================================================
;;
;; Most CN investment-income logic lives in the PROVIDER (per-event
;; holding-band classification, partnership-veil check for §26(2), the
;; per-income-class routing). The :provision data covers what the
;; ADR-101 evaluator can express cleanly:
;;
;;   - Listed-A-share gradation as THREE :schedule-override provisions
;;     keyed on the provider-injected :holding-band ctx key (ADR-101
;;     Addendum 1).
;;   - Stock Connect H-share sunset as a :schedule-override gated by
;;     `period-from-before` (ADR-101 Addendum 2).
;;   - Bank-savings + government-bond exemptions documented as
;;     :base-deduct audit-trail provisions (the provider verifies the
;;     exemption in code and returns no component).
;;   - §26(2) inter-TRR exemption documented as :base-deduct audit
;;     trail.

(def provisions
  "CN investment-income provisions encoded for the `kontor.statute`
   evaluator. The listed-A-share gradation uses ADR-101 Addendum 1's
   `:op :schedule-override` to swap the effective rate per holding
   band; Stock Connect uses Addendum 2's `period-from-before` for the
   2027-12-31 sunset.

   The exemption provisions (bank-deposit, government-bond, §26(2))
   are AUDIT-TRAIL data — the provider performs the routing in code
   (it returns NO COMPONENT for fully exempt classes). Keeping them
   here so a future statute query (e.g. `(statute/applicable-
   provisions ...)`) surfaces them with their citations."

  [;; --------------------------------------------------------------------
   ;; Caishui [2015] 101 — Listed A-share gradation as schedule-overrides.
   ;; Three provisions, one per band — keyed on `:holding-band` ctx the
   ;; provider injects per dividend event. Lower priority means default;
   ;; higher priority means more specific override.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-2015-101-listed-A-le-1m"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :holding-period-preference]
    :provision/title           "Caishui [2015] 101 — Listed A-share ≤ 1 month band (full 20 %)"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"
    :provision/effective-from  #inst "2015-09-08"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :income-class :listed-a-share-dividend]
                                        [:eq :holding-band :le-1m]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]])
    :provision/consequence     (pr-str {:op       :schedule-override
                                        :code     :cn-2015-101-le-1m
                                        :label    "Caishui [2015] 101 — ≤ 1 month band (full)"
                                        :schedule {:schedule/type :flat
                                                   :rate-from     :parameter
                                                   :parameter     "CN.IIT.investment-income.flat-rate"}})}

   {:provision/code            "CN-Caishui-2015-101-listed-A-1m-1y"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :holding-period-preference]
    :provision/title           "Caishui [2015] 101 — Listed A-share 1m–1y band (half, 10 % effective)"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"
    :provision/effective-from  #inst "2015-09-08"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :income-class :listed-a-share-dividend]
                                        [:eq :holding-band :1m-1y]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]])
    :provision/consequence     (pr-str {:op       :schedule-override
                                        :code     :cn-2015-101-1m-1y
                                        :label    "Caishui [2015] 101 — 1m–1y band (half base)"
                                        :schedule {:schedule/type :flat
                                                   :rate          0.10M}})}

   {:provision/code            "CN-Caishui-2015-101-listed-A-gt-1y"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :holding-period-preference]
    :provision/title           "Caishui [2015] 101 — Listed A-share > 1 year band (exempt)"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810765/c1973234/content.html"
    :provision/effective-from  #inst "2015-09-08"
    :provision/priority        120
    :provision/condition       (pr-str [:and
                                        [:eq :income-class :listed-a-share-dividend]
                                        [:eq :holding-band :gt-1y]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]])
    :provision/consequence     (pr-str {:op       :schedule-override
                                        :code     :cn-2015-101-gt-1y
                                        :label    "Caishui [2015] 101 — > 1 year band (exempt)"
                                        :schedule {:schedule/type :flat
                                                   :rate          0M}})}

   ;; --------------------------------------------------------------------
   ;; Caishui [2014] 81 — Stock Connect H-share sunset.
   ;; Schedule-override (flat 0 %) gated by period-from-before, so when
   ;; the period BEGINS before the sunset the rate is 0; otherwise the
   ;; standard 20 % default applies. Addendum 2 pattern.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-2014-81-stock-connect-sunset"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "Caishui [2014] 81 — Stock Connect H-share dividend exemption (sunset 2027-12-31)"
    :provision/citation        "https://www.chinatax.gov.cn/chinatax/n810341/n810755/c5210255/content.html"
    :provision/effective-from  #inst "2014-11-17"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :income-class :stock-connect-h-share-dividend]
                                        [:eq [:tax-unit :tax-residency] :resident-individual]
                                        (statute/period-from-before #inst "2028-01-01")])
    :provision/consequence     (pr-str {:op       :schedule-override
                                        :code     :cn-stock-connect-sunset
                                        :label    "Stock Connect H-share exemption (through 2027-12-31)"
                                        :schedule {:schedule/type :flat
                                                   :rate          0M}})}

   ;; --------------------------------------------------------------------
   ;; Caishui [2008] 132 — Bank savings deposit interest exemption.
   ;; Audit-trail provision. The provider verifies the income class and
   ;; returns no component when the rate is 0 — the :base-deduct here
   ;; is informational.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-Caishui-2008-132-bank-deposit-exemption"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "Caishui [2008] 132 — Bank savings deposit interest IIT exemption"
    :provision/citation        "https://shanghai.chinatax.gov.cn/zcfw/zcfgk/grsds/200810/t288953.html"
    :provision/effective-from  #inst "2008-10-09"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :income-class :cn-bank-deposit-interest])
    :provision/consequence     (pr-str {:op          :base-deduct
                                        :code        :cn-bank-deposit-exemption
                                        :label       "Bank savings deposit interest exemption"
                                        :amount-from :tax-context-fact
                                        :fact        [:inputs :bank-deposit-interest]})}

   ;; --------------------------------------------------------------------
   ;; Government bond interest exemption — 个税法 §4(2) + 国债条例.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-IITLaw-§4-2-gov-bond-exemption"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "个人所得税法 §4(2) + 国债条例 — Government bond interest IIT exemption"
    :provision/citation        "https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193028/content.html"
    :provision/effective-from  #inst "1980-09-10"
    :provision/priority        100
    :provision/condition       (pr-str [:eq :income-class :government-bond-interest])
    :provision/consequence     (pr-str {:op          :base-deduct
                                        :code        :cn-gov-bond-exemption
                                        :label       "Government bond interest exemption"
                                        :amount-from :tax-context-fact
                                        :fact        [:inputs :gov-bond-interest]})}

   ;; --------------------------------------------------------------------
   ;; EIT §26(2) inter-TRR dividend exemption.
   ;; Audit-trail provision; provider verifies all four prongs
   ;; (TRR-to-TRR + qualifying period + direct holding + NOT through
   ;; partnership) and emits :cit-base-deductions for the qualifying
   ;; dividend.
   ;; --------------------------------------------------------------------
   {:provision/code            "CN-EITLaw-§26-2-inter-TRR-exemption"
    :provision/jurisdiction    :cn
    :provision/concept         [:kontor.tax-concept/code :participation-exemption]
    :provision/title           "EIT Law §26(2) + Impl. Reg. §83 — Inter-TRR dividend exemption"
    :provision/citation        "https://fgk.chinatax.gov.cn/zcfgk/c100012/"
    :provision/effective-from  #inst "2008-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq [:tax-unit :tax-residency] :resident-corporation]
                                        [:eq :qualifies-§26-2? true]])
    :provision/consequence     (pr-str {:op          :base-deduct
                                        :code        :cn-eit-26-2-inter-trr
                                        :label       "Inter-TRR dividend exemption (§26(2))"
                                        :amount-from :tax-context-fact
                                        :fact        [:inputs :exempt-dividend-amount]})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install CN investment-income statute (parameters + provisions) into
   `conn`. Idempotent — `:parameter/code` and `:provision/code` are
   unique identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

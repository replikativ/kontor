(ns kontor.l10n-ca.investment-income-statute
  "CA investment-income tax — dividend gross-up + DTC + foreign-tax
   credit + Part IV refundable corporate tax — encoded as
   `kontor.statute` data per ADR-101. Research note 152.

   The CA investment-income story is **integration**: the corporate
   tax that funded a dividend is reconstructed as a notional credit
   so combined corporate + personal tax ≈ unincorporated marginal
   rate. The eligible/non-eligible split tracks whether the funding
   corp paid the full corporate rate (eligible, high gross-up + high
   DTC) or the SBD rate (non-eligible, low gross-up + low DTC).

   Two tax sides are wired here:

   1. **Individual** — gross-up adds to PIT base via `:base-add`
      (handled in the provider, not as a `:provision` because each
      dividend's class is consumer-supplied). Federal + provincial
      DTC fire as `:non-refundable-credit` provisions. §126 foreign
      tax credit is per-country (provider math via a compute-fn);
      the provision wires the cap.

   2. **Corporate** — Part IV refundable tax on portfolio dividends
      (38⅓ %, §186). §123.3 ART on AII (10⅔ %). Both surface as
      their own `:kind` components, so the kernel enum has
      `:part-iv-tax` distinct from `:corporate-income-tax`.

   Parameters: federal DTC fractions (6/11, 9/13), federal DTC
   rates as % of grossed-up amount (15.0198 %, 9.0301 %), per-
   province DTC rates (ON / BC / AB / QC × eligible / non-eligible),
   Part IV rate (0.38333), ART rate (0.10667), §126 non-business
   foreign cap (15 %), gross-up rates (1.38, 1.15)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "CA investment-income parameter definitions."
  [;; --------------------------------------------------------------------
   ;; Gross-up rates (§82(1)(b))
   ;; --------------------------------------------------------------------
   {:parameter/code         "CA.InvIncome.eligible-gross-up"
    :parameter/label        "ITA §82(1)(b)(ii) — eligible dividend gross-up (1.38)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-82.html"}

   {:parameter/code         "CA.InvIncome.non-eligible-gross-up"
    :parameter/label        "ITA §82(1)(b)(i) — non-eligible dividend gross-up (1.15)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-82.html"}

   ;; --------------------------------------------------------------------
   ;; Federal DTC (§121(a))
   ;; --------------------------------------------------------------------
   {:parameter/code         "CA.InvIncome.federal-dtc-eligible-rate"
    :parameter/label        "ITA §121(a) — federal DTC on eligible (15.0198% of grossed-up; = 6/11 × 0.38)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-121.html"}

   {:parameter/code         "CA.InvIncome.federal-dtc-non-eligible-rate"
    :parameter/label        "ITA §121(a) — federal DTC on non-eligible (9.0301% of grossed-up; = 9/13 × 0.15)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-121.html"}

   ;; --------------------------------------------------------------------
   ;; Provincial DTC (per province, v1 = ON / BC / AB / QC)
   ;; --------------------------------------------------------------------
   {:parameter/code         "CA.ON.InvIncome.dtc-eligible-rate"
    :parameter/label        "Ontario Taxation Act 2007 §20 — eligible dividend DTC (10.0% of grossed-up)"
    :parameter/jurisdiction :ca-on
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.ontario.ca/laws/statute/07t11"}

   {:parameter/code         "CA.ON.InvIncome.dtc-non-eligible-rate"
    :parameter/label        "Ontario Taxation Act 2007 §20.1 — non-eligible dividend DTC (2.9863% of grossed-up)"
    :parameter/jurisdiction :ca-on
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.ontario.ca/laws/statute/07t11"}

   {:parameter/code         "CA.BC.InvIncome.dtc-eligible-rate"
    :parameter/label        "BC Income Tax Act §4.69 — eligible dividend DTC (12.0% of grossed-up)"
    :parameter/jurisdiction :ca-bc
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"}

   {:parameter/code         "CA.BC.InvIncome.dtc-non-eligible-rate"
    :parameter/label        "BC Income Tax Act §4.69 — non-eligible dividend DTC (1.96% of grossed-up)"
    :parameter/jurisdiction :ca-bc
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"}

   {:parameter/code         "CA.AB.InvIncome.dtc-eligible-rate"
    :parameter/label        "Alberta Personal Income Tax Act §21 — eligible dividend DTC (8.12% of grossed-up)"
    :parameter/jurisdiction :ca-ab
    :parameter/unit         :rate
    :parameter/concept-iri  "https://kings-printer.alberta.ca/1266.cfm?page=A30P1.cfm&leg_type=Acts"}

   {:parameter/code         "CA.AB.InvIncome.dtc-non-eligible-rate"
    :parameter/label        "Alberta Personal Income Tax Act §21.1 — non-eligible dividend DTC (2.18% of grossed-up)"
    :parameter/jurisdiction :ca-ab
    :parameter/unit         :rate
    :parameter/concept-iri  "https://kings-printer.alberta.ca/1266.cfm?page=A30P1.cfm&leg_type=Acts"}

   {:parameter/code         "CA.QC.InvIncome.dtc-eligible-rate"
    :parameter/label        "Quebec Taxation Act §767 — eligible dividend DTC (11.70% of grossed-up)"
    :parameter/jurisdiction :ca-qc
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legisquebec.gouv.qc.ca/en/document/cs/I-3"}

   {:parameter/code         "CA.QC.InvIncome.dtc-non-eligible-rate"
    :parameter/label        "Quebec Taxation Act §767.1 — non-eligible dividend DTC (3.42% of grossed-up)"
    :parameter/jurisdiction :ca-qc
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.legisquebec.gouv.qc.ca/en/document/cs/I-3"}

   ;; --------------------------------------------------------------------
   ;; Corporate side — Part IV + ART
   ;; --------------------------------------------------------------------
   {:parameter/code         "CA.InvIncome.part-iv-rate"
    :parameter/label        "ITA §186(1) — Part IV refundable tax on portfolio dividends (38⅓ %)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-186.html"}

   {:parameter/code         "CA.InvIncome.art-rate"
    :parameter/label        "ITA §123.3 — additional refundable tax on CCPC aggregate investment income (10⅔ %)"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.3.html"}

   ;; --------------------------------------------------------------------
   ;; §126 foreign tax credit cap — non-business income
   ;; --------------------------------------------------------------------
   {:parameter/code         "CA.InvIncome.foreign-non-bus-tax-cap"
    :parameter/label        "ITA §126(1) — non-business foreign tax credit capped at 15% of foreign income"
    :parameter/jurisdiction :ca
    :parameter/unit         :rate
    :parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-126.html"}])

(def parameter-values
  "CA investment-income parameter values. Sources: ITA §82 / §121 /
   §126 / §186 / §123.3; TaxTips.ca eligible+non-eligible DTC tables;
   KPMG Tax Facts 2025-2026; per-province statutes."
  [;; Eligible gross-up — 1.38× since 2014-01-01 (was 1.45× 2009-2013).
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.eligible-gross-up"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  1.38M
    :parameter-value/citation       "ITA §82(1)(b)(ii) — gross-up 1.38 (since 2014; was 1.45 from 2009-2013, 1.41 in 2008, 1.45 pre-2008)"}

   ;; Non-eligible gross-up — 1.15× since 2019-01-01 (was 1.16× 2018,
   ;; 1.17× 2017, 1.18× 2016, 1.25× pre-2016).
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.non-eligible-gross-up"]
    :parameter-value/effective-from #inst "2019-01-01"
    :parameter-value/decimal-value  1.15M
    :parameter-value/citation       "ITA §82(1)(b)(i) — gross-up 1.15 (since 2019; tracks the §125 SBD effective-rate drop to 9%)"}

   ;; Federal DTC eligible — 15.0198% of grossed-up = 6/11 × 0.38.
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.federal-dtc-eligible-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.150198M
    :parameter-value/citation       "ITA §121(a) — eligible DTC fraction 6/11 of gross-up (15.0198% of grossed-up amount); CRA line 40425"}

   ;; Federal DTC non-eligible — 9.0301% of grossed-up = 9/13 × 0.15.
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.federal-dtc-non-eligible-rate"]
    :parameter-value/effective-from #inst "2019-01-01"
    :parameter-value/decimal-value  0.090301M
    :parameter-value/citation       "ITA §121(a) — non-eligible DTC fraction 9/13 of gross-up (9.0301% of grossed-up amount); CRA line 40425"}

   ;; --- Per-province DTC values ---------------------------------------
   {:parameter-value/parameter      [:parameter/code "CA.ON.InvIncome.dtc-eligible-rate"]
    :parameter-value/effective-from #inst "2014-01-01"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Ontario eligible DTC 10.0% of grossed-up (TaxTips.ca DTC tables; KPMG Tax Facts 2025-2026)"}

   {:parameter-value/parameter      [:parameter/code "CA.ON.InvIncome.dtc-non-eligible-rate"]
    :parameter-value/effective-from #inst "2025-01-01"
    :parameter-value/decimal-value  0.029863M
    :parameter-value/citation       "Ontario non-eligible DTC 2.9863% of grossed-up effective 2025 (was 3.2863% pre-2025; harmonised w/ small-business rate change)"}

   {:parameter-value/parameter      [:parameter/code "CA.BC.InvIncome.dtc-eligible-rate"]
    :parameter-value/effective-from #inst "2019-01-01"
    :parameter-value/decimal-value  0.12M
    :parameter-value/citation       "BC eligible DTC 12.0% of grossed-up since 2019 (TaxTips.ca DTC tables)"}

   {:parameter-value/parameter      [:parameter/code "CA.BC.InvIncome.dtc-non-eligible-rate"]
    :parameter-value/effective-from #inst "2019-01-01"
    :parameter-value/decimal-value  0.0196M
    :parameter-value/citation       "BC non-eligible DTC 1.96% of grossed-up since 2019"}

   {:parameter-value/parameter      [:parameter/code "CA.AB.InvIncome.dtc-eligible-rate"]
    :parameter-value/effective-from #inst "2017-01-01"
    :parameter-value/decimal-value  0.0812M
    :parameter-value/citation       "Alberta eligible DTC 8.12% of grossed-up (TaxTips.ca — long-stable; matches note 152 §1.4 table)"}

   {:parameter-value/parameter      [:parameter/code "CA.AB.InvIncome.dtc-non-eligible-rate"]
    :parameter-value/effective-from #inst "2017-01-01"
    :parameter-value/decimal-value  0.0218M
    :parameter-value/citation       "Alberta non-eligible DTC 2.18% of grossed-up"}

   {:parameter-value/parameter      [:parameter/code "CA.QC.InvIncome.dtc-eligible-rate"]
    :parameter-value/effective-from #inst "2022-01-01"
    :parameter-value/decimal-value  0.117M
    :parameter-value/citation       "Quebec eligible DTC 11.70% of grossed-up since 2022 (TaxTips.ca QC table)"}

   {:parameter-value/parameter      [:parameter/code "CA.QC.InvIncome.dtc-non-eligible-rate"]
    :parameter-value/effective-from #inst "2022-01-01"
    :parameter-value/decimal-value  0.0342M
    :parameter-value/citation       "Quebec non-eligible DTC 3.42% of grossed-up since 2022"}

   ;; --- Corporate ---------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.part-iv-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.383333M
    :parameter-value/citation       "ITA §186(1) — Part IV refundable tax raised to 38⅓ % effective TY beginning after 2015 (was 33⅓ % prior)"}

   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.art-rate"]
    :parameter-value/effective-from #inst "2016-01-01"
    :parameter-value/decimal-value  0.106667M
    :parameter-value/citation       "ITA §123.3 — additional refundable tax 10⅔ % on AII effective TY beginning after 2015"}

   ;; --- §126 cap -----------------------------------------------------
   {:parameter-value/parameter      [:parameter/code "CA.InvIncome.foreign-non-bus-tax-cap"]
    :parameter-value/effective-from #inst "1972-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "ITA §126(1) — non-business foreign income tax claim limited to 15% of foreign income (excess goes to §20(11) deduction)"}])

;; ============================================================================
;; Provisions
;; ============================================================================

(def provisions
  "CA investment-income provisions.

   The federal + provincial DTC fire as `:non-refundable-credit`s
   keyed on `:component` (`:ca-fed` for the federal DTC, `:ca-on` /
   `:ca-bc` / `:ca-ab` / `:ca-qc` for provincial). Amounts are
   computed by `:ca-inv-income-dtc` which reads the grossed-up
   running totals out of `:inputs`.

   §126 foreign tax credit fires when `:inputs :ca-foreign-tax-paid`
   is non-empty; amount computed by `:ca-inv-income-foreign-credit`.

   The Part IV refundable tax on corporate portfolio dividends fires
   under `:elective-regime` (since it swaps the schedule itself for
   that component); amount = 38⅓ % × portfolio-dividend-base."

  [;; --------------------------------------------------------------------
   ;; Federal DTC — eligible
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-ITA-§121-DTC-Federal-Eligible"
    :provision/jurisdiction    :ca
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "ITA §121(a) — federal dividend tax credit on eligible dividends"
    :provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-121.html"
    :provision/effective-from  #inst "2014-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:gt [:inputs :ca-grossed-up-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-federal-dtc-eligible
                                        :label "Federal DTC on eligible dividends (§121(a))"
                                        :amount-from :compute-fn
                                        :fn :ca-federal-dtc-eligible})}

   ;; --------------------------------------------------------------------
   ;; Federal DTC — non-eligible
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-ITA-§121-DTC-Federal-NonEligible"
    :provision/jurisdiction    :ca
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "ITA §121(a) — federal dividend tax credit on non-eligible dividends"
    :provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-121.html"
    :provision/effective-from  #inst "2019-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:gt [:inputs :ca-grossed-up-non-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-federal-dtc-non-eligible
                                        :label "Federal DTC on non-eligible dividends (§121(a))"
                                        :amount-from :compute-fn
                                        :fn :ca-federal-dtc-non-eligible})}

   ;; --------------------------------------------------------------------
   ;; §126 federal foreign tax credit
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-ITA-§126-Foreign-Tax-Credit"
    :provision/jurisdiction    :ca
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "ITA §126(1) — federal foreign tax credit on non-business income (15% cap)"
    :provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-126.html"
    :provision/effective-from  #inst "1972-01-01"
    :provision/priority        200
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:gt [:inputs :ca-foreign-tax-paid-total] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-federal-foreign-tax-credit
                                        :label "Federal foreign tax credit (§126; non-business 15% cap)"
                                        :amount-from :compute-fn
                                        :fn :ca-federal-foreign-tax-credit})}

   ;; --------------------------------------------------------------------
   ;; Provincial DTC — Ontario
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-ON-DTC-Eligible"
    :provision/jurisdiction    :ca-on
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Ontario eligible-dividend tax credit (10.0% of grossed-up)"
    :provision/citation        "https://www.ontario.ca/laws/statute/07t11"
    :provision/effective-from  #inst "2014-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-on]
                                        [:gt [:inputs :ca-grossed-up-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-on-dtc-eligible
                                        :label "Ontario DTC on eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-eligible})}

   {:provision/code            "CA-ON-DTC-NonEligible"
    :provision/jurisdiction    :ca-on
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Ontario non-eligible-dividend tax credit (2.9863% of grossed-up; 2025+)"
    :provision/citation        "https://www.ontario.ca/laws/statute/07t11"
    :provision/effective-from  #inst "2025-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-on]
                                        [:gt [:inputs :ca-grossed-up-non-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-on-dtc-non-eligible
                                        :label "Ontario DTC on non-eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-non-eligible})}

   ;; --------------------------------------------------------------------
   ;; Provincial DTC — British Columbia
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-BC-DTC-Eligible"
    :provision/jurisdiction    :ca-bc
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "BC eligible-dividend tax credit (12.0% of grossed-up)"
    :provision/citation        "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"
    :provision/effective-from  #inst "2019-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-bc]
                                        [:gt [:inputs :ca-grossed-up-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-bc-dtc-eligible
                                        :label "BC DTC on eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-eligible})}

   {:provision/code            "CA-BC-DTC-NonEligible"
    :provision/jurisdiction    :ca-bc
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "BC non-eligible-dividend tax credit (1.96% of grossed-up)"
    :provision/citation        "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"
    :provision/effective-from  #inst "2019-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-bc]
                                        [:gt [:inputs :ca-grossed-up-non-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-bc-dtc-non-eligible
                                        :label "BC DTC on non-eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-non-eligible})}

   ;; --------------------------------------------------------------------
   ;; Provincial DTC — Alberta
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-AB-DTC-Eligible"
    :provision/jurisdiction    :ca-ab
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Alberta eligible-dividend tax credit (8.12% of grossed-up)"
    :provision/citation        "https://kings-printer.alberta.ca/1266.cfm?page=A30P1.cfm&leg_type=Acts"
    :provision/effective-from  #inst "2017-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-ab]
                                        [:gt [:inputs :ca-grossed-up-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-ab-dtc-eligible
                                        :label "Alberta DTC on eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-eligible})}

   {:provision/code            "CA-AB-DTC-NonEligible"
    :provision/jurisdiction    :ca-ab
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Alberta non-eligible-dividend tax credit (2.18% of grossed-up)"
    :provision/citation        "https://kings-printer.alberta.ca/1266.cfm?page=A30P1.cfm&leg_type=Acts"
    :provision/effective-from  #inst "2017-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-ab]
                                        [:gt [:inputs :ca-grossed-up-non-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-ab-dtc-non-eligible
                                        :label "Alberta DTC on non-eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-non-eligible})}

   ;; --------------------------------------------------------------------
   ;; Provincial DTC — Quebec
   ;; --------------------------------------------------------------------
   {:provision/code            "CA-QC-DTC-Eligible"
    :provision/jurisdiction    :ca-qc
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Quebec eligible-dividend tax credit (11.70% of grossed-up)"
    :provision/citation        "https://www.legisquebec.gouv.qc.ca/en/document/cs/I-3"
    :provision/effective-from  #inst "2022-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-qc]
                                        [:gt [:inputs :ca-grossed-up-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-qc-dtc-eligible
                                        :label "Quebec DTC on eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-eligible})}

   {:provision/code            "CA-QC-DTC-NonEligible"
    :provision/jurisdiction    :ca-qc
    :provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :provision/title           "Quebec non-eligible-dividend tax credit (3.42% of grossed-up)"
    :provision/citation        "https://www.legisquebec.gouv.qc.ca/en/document/cs/I-3"
    :provision/effective-from  #inst "2022-01-01"
    :provision/priority        110
    :provision/condition       (pr-str [:and
                                        [:eq :component :ca-qc]
                                        [:gt [:inputs :ca-grossed-up-non-eligible] 0M]])
    :provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-qc-dtc-non-eligible
                                        :label "Quebec DTC on non-eligible dividends"
                                        :amount-from :compute-fn
                                        :fn :ca-provincial-dtc-non-eligible})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install CA investment-income statute (parameters + parameter-values
   + provisions) into `conn`. Idempotent — `:parameter/code` and
   `:provision/code` are unique identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

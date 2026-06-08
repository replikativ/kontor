(ns kontor.l10n-ca.cit-statute
  "CA corporate income tax — federal T2 Part I + per-province CIT —
   encoded as `kontor.tax.statute` data per ADR-101 / ADR-107. Sibling of
   the DE CIT statute (ADR-104) — same shape (parameters + provisions
   + idempotent install!), CA-specific content.

   The CA T2 stack is **multi-jurisdiction**:

     - **Federal Part I** (Income Tax Act §123 + §123.4 + §125):
       base 38% − 10% provincial abatement − 13% general tax reduction
       = 15% effective; for CCPCs the first $500k of active business
       income drops to 9% (the Small Business Deduction). The provider
       models this as a **schedule-override** keyed by `:tax-unit
       :ccpc?` — CCPCs get a 2-bracket progressive (9% to $500k, 15%
       above); non-CCPCs get a flat 15%.

     - **Provincial CIT** (one per province with allocated income):
       each province has its own general + small-business rates and
       its own small-business limit. Allocation across provinces uses
       the T2 Schedule 5 two-factor formula (wages share + revenue
       share, averaged) — the consumer pre-computes the per-province
       fraction and supplies it via `:tax-unit :provincial-allocation`.
       The provider builds one `:corporate-income-tax` component per
       province with non-zero allocation, scoped via
       `:condition [:eq :component :ca-on]` / `:ca-bc` / `:ca-ab`.

   The CA pattern leans on the **schedule-override** op (ADR-101
   Addendum 1) for the CCPC SBD cascade, since the small-business
   schedule is a different shape (progressive 2-bracket) from the
   non-CCPC default (flat). DE could express its provisions purely as
   base-adds / surtaxes; CA's CCPC carve-out is a *schedule* change.

   `:kontor.provision/concept` references the seeded `:tax-concept`s
   (`:base-transform-add`, `:base-transform-deduct`, `:refundable-credit`,
   `:non-refundable-credit`, `:elective-regime`). For the
   schedule-override provisions we use `:elective-regime` since CCPC
   status is essentially a tax-unit-elected regime swap.

   Citations point at canada.ca / laws-lois.justice.gc.ca for the
   statute text; parameter-values carry their own citations
   (CRA / Income Tax Act / provincial-finance-ministry references).
"
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters (date-keyed value history per ADR-101 :parameter)
;; ============================================================================

(def parameters
  "CA CIT parameter definitions — one row per `:kontor.parameter/code`. Values
   live in `parameter-values` keyed by `:effective-from`."
  [;; --------------------------------------------------------------------
   ;; Federal
   ;; --------------------------------------------------------------------
   {:kontor.parameter/code         "CA.Federal.CIT.base-rate"
    :kontor.parameter/label        "ITA §123 — basic federal corporate income tax rate (38%, before reductions)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.html"}

   {:kontor.parameter/code         "CA.Federal.CIT.provincial-abatement"
    :kontor.parameter/label        "ITA §124 — 10% provincial abatement on income earned in a province"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-124.html"}

   {:kontor.parameter/code         "CA.Federal.CIT.general-reduction"
    :kontor.parameter/label        "ITA §123.4 — General Tax Reduction (−13%)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.4.html"}

   {:kontor.parameter/code         "CA.Federal.CIT.sbd-rate"
    :kontor.parameter/label        "ITA §125 — Small Business Deduction effective rate (9% on first $500k for CCPCs)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html"}

   {:kontor.parameter/code         "CA.Federal.CIT.general-rate"
    :kontor.parameter/label        "Effective general federal rate: 38 − 10 − 13 = 15%"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.4.html"}

   {:kontor.parameter/code         "CA.Federal.CIT.sbd-business-limit"
    :kontor.parameter/label        "ITA §125(2) — annual business limit for SBD ($500k)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html"}

   {:kontor.parameter/code         "CA.Federal.SRED.ccpc-refundable-rate"
    :kontor.parameter/label        "ITA §127.1 — SR&ED 35% refundable ITC for CCPCs (on first $3M of qualifying expenditures)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.1.html"}

   {:kontor.parameter/code         "CA.Federal.SRED.standard-rate"
    :kontor.parameter/label        "ITA §127(5) — SR&ED 15% non-refundable ITC (non-CCPC + excess CCPC)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.html"}

   {:kontor.parameter/code         "CA.Federal.SRED.ccpc-expenditure-limit"
    :kontor.parameter/label        "ITA §127.1 — SR&ED CCPC enhanced-rate expenditure limit ($3M; raised to $6M for tax years beginning after 2024-12-15)"
    :kontor.parameter/jurisdiction :ca
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.canada.ca/en/revenue-agency/services/scientific-research-experimental-development-tax-incentive-program/investment-tax-credit-policy.html"}

   ;; --------------------------------------------------------------------
   ;; Ontario
   ;; --------------------------------------------------------------------
   {:kontor.parameter/code         "CA.ON.CIT.general-rate"
    :kontor.parameter/label        "Ontario Taxation Act 2007 §29 — general corporate income tax rate (11.5%)"
    :kontor.parameter/jurisdiction :ca-on
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.ontario.ca/laws/statute/07t11"}

   {:kontor.parameter/code         "CA.ON.CIT.sbd-rate"
    :kontor.parameter/label        "Ontario Taxation Act 2007 §31 — small-business corporate income tax rate (3.2%)"
    :kontor.parameter/jurisdiction :ca-on
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.ontario.ca/laws/statute/07t11"}

   {:kontor.parameter/code         "CA.ON.CIT.sbd-limit"
    :kontor.parameter/label        "Ontario small-business limit ($500k, matches federal)"
    :kontor.parameter/jurisdiction :ca-on
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.ontario.ca/laws/statute/07t11"}

   ;; --------------------------------------------------------------------
   ;; British Columbia
   ;; --------------------------------------------------------------------
   {:kontor.parameter/code         "CA.BC.CIT.general-rate"
    :kontor.parameter/label        "BC Income Tax Act §14 — general corporate income tax rate (12%)"
    :kontor.parameter/jurisdiction :ca-bc
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"}

   {:kontor.parameter/code         "CA.BC.CIT.sbd-rate"
    :kontor.parameter/label        "BC Income Tax Act §14 — small-business corporate income tax rate (2%)"
    :kontor.parameter/jurisdiction :ca-bc
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"}

   {:kontor.parameter/code         "CA.BC.CIT.sbd-limit"
    :kontor.parameter/label        "BC small-business limit ($500k, matches federal)"
    :kontor.parameter/jurisdiction :ca-bc
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"}

   ;; --------------------------------------------------------------------
   ;; Alberta
   ;; --------------------------------------------------------------------
   {:kontor.parameter/code         "CA.AB.CIT.general-rate"
    :kontor.parameter/label        "Alberta Corporate Tax Act §21 — general corporate income tax rate (8%)"
    :kontor.parameter/jurisdiction :ca-ab
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts"}

   {:kontor.parameter/code         "CA.AB.CIT.sbd-rate"
    :kontor.parameter/label        "Alberta small-business CIT rate (2%)"
    :kontor.parameter/jurisdiction :ca-ab
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts"}

   {:kontor.parameter/code         "CA.AB.CIT.sbd-limit"
    :kontor.parameter/label        "Alberta small-business limit ($500k, matches federal)"
    :kontor.parameter/jurisdiction :ca-ab
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts"}])

(def parameter-values
  "CA CIT parameter values with their statutory effective windows.
   Provincial rates verified TY2024-2025 per published rate tables
   and per-province finance-ministry pages. Most are stable for years;
   the date-keyed history is here for when amendments land."
  [;; --------------------------------------------------------------------
   ;; Federal — stable since 2012 (general 15%) / 2019 (SBD 9%)
   ;; --------------------------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.base-rate"]
    :kontor.parameter-value/effective-from #inst "1972-01-01"
    :kontor.parameter-value/decimal-value  0.38M
    :kontor.parameter-value/citation       "ITA §123(1)(a) — basic rate stable since the 1972 reform"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.provincial-abatement"]
    :kontor.parameter-value/effective-from #inst "1972-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "ITA §124(1) — 10% provincial abatement"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.general-reduction"]
    :kontor.parameter-value/effective-from #inst "2012-01-01"
    :kontor.parameter-value/decimal-value  0.13M
    :kontor.parameter-value/citation       "ITA §123.4(2) — General Tax Reduction stable at 13% since 2012"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.sbd-rate"]
    :kontor.parameter-value/effective-from #inst "2019-01-01"
    :kontor.parameter-value/decimal-value  0.09M
    :kontor.parameter-value/citation       "ITA §125 — SBD effective rate 9% since 2019 (was 10% 2018, 10.5% 2017)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.general-rate"]
    :kontor.parameter-value/effective-from #inst "2012-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "Effective general federal rate (38 − 10 − 13 = 15%) since 2012"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.CIT.sbd-business-limit"]
    :kontor.parameter-value/effective-from #inst "2009-01-01"
    :kontor.parameter-value/decimal-value  500000M
    :kontor.parameter-value/citation       "ITA §125(2) — annual business limit $500k since 2009"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CA.Federal.SRED.ccpc-refundable-rate"]
    :kontor.parameter-value/effective-from  #inst "1985-01-01"
    :kontor.parameter-value/decimal-value   0.35M
    :kontor.parameter-value/citation        "ITA §127.1 — 35% refundable ITC for CCPCs"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.SRED.standard-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "ITA §127(5) — standard SR&ED ITC reduced from 20% to 15% effective 2014"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CA.Federal.SRED.ccpc-expenditure-limit"]
    :kontor.parameter-value/effective-from  #inst "2010-01-01"
    :kontor.parameter-value/effective-until #inst "2024-12-15"
    :kontor.parameter-value/decimal-value   3000000M
    :kontor.parameter-value/citation        "ITA §127.1 — CCPC enhanced-rate limit $3M (2010 → 2024-12-15)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.Federal.SRED.ccpc-expenditure-limit"]
    :kontor.parameter-value/effective-from #inst "2024-12-15"
    :kontor.parameter-value/decimal-value  6000000M
    :kontor.parameter-value/citation       "ITA §127.1 — CCPC enhanced-rate limit raised to $6M for TYs beginning after 2024-12-15 (Fall 2024 Economic Statement)"}

   ;; --------------------------------------------------------------------
   ;; Ontario — 11.5% general / 3.2% small / $500k since 2020-01-01
   ;; --------------------------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.ON.CIT.general-rate"]
    :kontor.parameter-value/effective-from #inst "2018-07-01"
    :kontor.parameter-value/decimal-value  0.115M
    :kontor.parameter-value/citation       "Ontario general CIT rate 11.5% since 2018-07-01"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CA.ON.CIT.sbd-rate"]
    :kontor.parameter-value/effective-from  #inst "2020-01-01"
    :kontor.parameter-value/effective-until #inst "2026-07-01"
    :kontor.parameter-value/decimal-value   0.032M
    :kontor.parameter-value/citation        "Ontario small-business CIT rate 3.2% (2020-01-01 to 2026-06-30; was 3.5% in 2018-2019)"}

   ;; Note 126 (Bill 12, royal assent Nov 2025; SO 2025, c.12) —
   ;; ON small-business rate cuts from 3.2% to 2.2% effective 2026-07-01.
   ;; Straddle-year corps prorate per Ontario Taxation Act 2007 §32(2).
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.ON.CIT.sbd-rate"]
    :kontor.parameter-value/effective-from #inst "2026-07-01"
    :kontor.parameter-value/decimal-value  0.022M
    :kontor.parameter-value/citation       "Ontario Taxation Act 2007 §32 as amended by Bill 12 (RA 2025-11; SO 2025, c.12) — SBD rate cut to 2.2% from 2026-07-01"}

   {:kontor.parameter-value/parameter       [:kontor.parameter/code "CA.ON.CIT.sbd-limit"]
    :kontor.parameter-value/effective-from  #inst "2009-01-01"
    :kontor.parameter-value/effective-until #inst "2026-01-01"
    :kontor.parameter-value/decimal-value   500000M
    :kontor.parameter-value/citation        "Ontario small-business limit $500k 2009-01-01 to 2025-12-31 (matched federal)"}

   ;; Note 126 (Bill 12, same act) — ON business limit raises from
   ;; $500k to $600k effective 2026-01-01. Diverges from federal $500k
   ;; for the first time since 2009; the sbd-pool fix () ensures
   ;; the per-province limit is now read, not the federal value.
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.ON.CIT.sbd-limit"]
    :kontor.parameter-value/effective-from #inst "2026-01-01"
    :kontor.parameter-value/decimal-value  600000M
    :kontor.parameter-value/citation       "Ontario Taxation Act 2007 §31 as amended by Bill 12 — business limit raised to $600k from 2026-01-01"}

   ;; --------------------------------------------------------------------
   ;; British Columbia — 12% general / 2% small / $500k
   ;; --------------------------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.BC.CIT.general-rate"]
    :kontor.parameter-value/effective-from #inst "2018-01-01"
    :kontor.parameter-value/decimal-value  0.12M
    :kontor.parameter-value/citation       "BC general CIT rate 12% since 2018-01-01 (raised from 11%)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.BC.CIT.sbd-rate"]
    :kontor.parameter-value/effective-from #inst "2017-04-01"
    :kontor.parameter-value/decimal-value  0.02M
    :kontor.parameter-value/citation       "BC small-business CIT rate 2% since 2017-04-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.BC.CIT.sbd-limit"]
    :kontor.parameter-value/effective-from #inst "2009-01-01"
    :kontor.parameter-value/decimal-value  500000M
    :kontor.parameter-value/citation       "BC small-business limit $500k (matches federal)"}

   ;; --------------------------------------------------------------------
   ;; Alberta — 8% general / 2% small / $500k
   ;; --------------------------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.AB.CIT.general-rate"]
    :kontor.parameter-value/effective-from #inst "2020-07-01"
    :kontor.parameter-value/decimal-value  0.08M
    :kontor.parameter-value/citation       "Alberta general CIT rate 8% since 2020-07-01 (Job Creation Tax Cut, accelerated from 12%)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.AB.CIT.sbd-rate"]
    :kontor.parameter-value/effective-from #inst "2017-01-01"
    :kontor.parameter-value/decimal-value  0.02M
    :kontor.parameter-value/citation       "Alberta small-business CIT rate 2% since 2017-01-01"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "CA.AB.CIT.sbd-limit"]
    :kontor.parameter-value/effective-from #inst "2009-01-01"
    :kontor.parameter-value/decimal-value  500000M
    :kontor.parameter-value/citation       "Alberta small-business limit $500k (matches federal)"}])

;; ============================================================================
;; Provisions — CA CIT statute as :provision data
;; ============================================================================

(def provisions
  "CA CIT statutory provisions encoded for the `kontor.tax.statute`
   evaluator. Conditions reference `:component` (set by the provider on
   each per-component pass — `:ca-fed`, `:ca-on`, `:ca-bc`, `:ca-ab`)
   and use vector fact-keys `[:inputs <fact>]` / `[:tax-unit <fact>]`
   to read consumer-supplied facts.

   The federal CCPC carve-out is expressed via TWO schedule-override
   provisions — one for CCPCs (progressive 2-bracket SBD cascade) and
   one for non-CCPCs (flat 15%). The provider's `apply-provisions`
   call picks the first override; conditions ensure exactly one fires
   per call.

   Provincial SBD cascades fire the same way — one schedule-override
   per `(province, CCPC?)` pair. Note that the small-business bracket
   upper bound is computed *by the compute-fn* — for CCPCs in a
   multi-province corporation, the province's slice of the federal
   $500k limit is allocated by the same Sch-5 factor."

  [;; --------------------------------------------------------------------
   ;; Federal Part I (Income Tax Act §123 / §123.4 / §125)
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "CA-ITA-§125-CCPC-SBD"
    :kontor.provision/jurisdiction    :ca
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "ITA §125 — CCPC Small Business Deduction (9% on first $500k, 15% above)"
    :kontor.provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-125.html"
    :kontor.provision/effective-from  #inst "2019-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:eq [:tax-unit :ccpc?] true]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-fed-ccpc-sbd
                                        :label "Federal CCPC schedule: 9% to $500k, 15% above"
                                        :schedule {:kontor.schedule/type :formula
                                                   :fn-from :compute-fn
                                                   :fn :ca-federal-ccpc-schedule}})}

   {:kontor.provision/code            "CA-ITA-§123.4-General"
    :kontor.provision/jurisdiction    :ca
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "ITA §123 + §123.4 — general 15% federal rate (non-CCPCs)"
    :kontor.provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-123.4.html"
    :kontor.provision/effective-from  #inst "2012-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:not [:eq [:tax-unit :ccpc?] true]]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-fed-general
                                        :label "Federal general schedule: flat 15%"
                                        :schedule {:kontor.schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "CA.Federal.CIT.general-rate"}})}

   ;; SR&ED Investment Tax Credit — refundable for CCPCs, non-refundable
   ;; otherwise. Single provision; the compute-fn picks the rate +
   ;; refundability flag from `:tax-unit :ccpc?`.
   {:kontor.provision/code            "CA-ITA-§127.1-SRED-CCPC"
    :kontor.provision/jurisdiction    :ca
    :kontor.provision/concept         [:kontor.tax-concept/code :refundable-credit]
    :kontor.provision/title           "ITA §127.1 — SR&ED 35% refundable Investment Tax Credit (CCPCs)"
    :kontor.provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.1.html"
    :kontor.provision/effective-from  #inst "1985-01-01"
    :kontor.provision/priority        200
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:eq [:tax-unit :ccpc?] true]
                                        [:gt [:inputs :sred-expenditure] 0M]])
    :kontor.provision/consequence     (pr-str {:op :credit
                                        :refundable? true
                                        :code :ca-sred-ccpc
                                        :label "SR&ED 35% refundable ITC (CCPC, up to expenditure limit; 15% non-refundable on excess)"
                                        :amount-from :compute-fn
                                        :fn :ca-sred-credit})}

   {:kontor.provision/code            "CA-ITA-§127-SRED-Standard"
    :kontor.provision/jurisdiction    :ca
    :kontor.provision/concept         [:kontor.tax-concept/code :non-refundable-credit]
    :kontor.provision/title           "ITA §127(5) — SR&ED 15% non-refundable ITC (non-CCPCs)"
    :kontor.provision/citation        "https://laws-lois.justice.gc.ca/eng/acts/I-3.3/section-127.html"
    :kontor.provision/effective-from  #inst "2014-01-01"
    :kontor.provision/priority        200
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-fed]
                                        [:not [:eq [:tax-unit :ccpc?] true]]
                                        [:gt [:inputs :sred-expenditure] 0M]])
    :kontor.provision/consequence     (pr-str {:op :credit
                                        :refundable? false
                                        :code :ca-sred-standard
                                        :label "SR&ED 15% non-refundable ITC (non-CCPC)"
                                        :amount-from :compute-fn
                                        :fn :ca-sred-credit})}

   ;; --------------------------------------------------------------------
   ;; Ontario (Taxation Act 2007)
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "CA-ON-TA-§31-CCPC-SBD"
    :kontor.provision/jurisdiction    :ca-on
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Ontario Taxation Act 2007 §31 — CCPC small-business cascade (3.2% to allocated SBD pool, 11.5% above)"
    :kontor.provision/citation        "https://www.ontario.ca/laws/statute/07t11"
    :kontor.provision/effective-from  #inst "2020-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-on]
                                        [:eq [:tax-unit :ccpc?] true]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-on-ccpc-sbd
                                        :label "Ontario CCPC schedule: 3.2% to allocated SBD pool, 11.5% above"
                                        :schedule {:kontor.schedule/type :formula
                                                   :fn-from :compute-fn
                                                   :fn :ca-on-ccpc-schedule}})}

   {:kontor.provision/code            "CA-ON-TA-§29-General"
    :kontor.provision/jurisdiction    :ca-on
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Ontario Taxation Act 2007 §29 — general 11.5% (non-CCPCs)"
    :kontor.provision/citation        "https://www.ontario.ca/laws/statute/07t11"
    :kontor.provision/effective-from  #inst "2018-07-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-on]
                                        [:not [:eq [:tax-unit :ccpc?] true]]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-on-general
                                        :label "Ontario general schedule: flat 11.5%"
                                        :schedule {:kontor.schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "CA.ON.CIT.general-rate"}})}

   ;; --------------------------------------------------------------------
   ;; British Columbia
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "CA-BC-ITA-§14-CCPC-SBD"
    :kontor.provision/jurisdiction    :ca-bc
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "BC Income Tax Act §14 — CCPC cascade (2% to allocated SBD pool, 12% above)"
    :kontor.provision/citation        "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"
    :kontor.provision/effective-from  #inst "2018-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-bc]
                                        [:eq [:tax-unit :ccpc?] true]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-bc-ccpc-sbd
                                        :label "BC CCPC schedule: 2% to allocated SBD pool, 12% above"
                                        :schedule {:kontor.schedule/type :formula
                                                   :fn-from :compute-fn
                                                   :fn :ca-bc-ccpc-schedule}})}

   {:kontor.provision/code            "CA-BC-ITA-§14-General"
    :kontor.provision/jurisdiction    :ca-bc
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "BC Income Tax Act §14 — general 12% (non-CCPCs)"
    :kontor.provision/citation        "https://www.bclaws.gov.bc.ca/civix/document/id/complete/statreg/96215_01"
    :kontor.provision/effective-from  #inst "2018-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-bc]
                                        [:not [:eq [:tax-unit :ccpc?] true]]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-bc-general
                                        :label "BC general schedule: flat 12%"
                                        :schedule {:kontor.schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "CA.BC.CIT.general-rate"}})}

   ;; --------------------------------------------------------------------
   ;; Alberta
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "CA-AB-CTA-§21-CCPC-SBD"
    :kontor.provision/jurisdiction    :ca-ab
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Alberta Corporate Tax Act §21 — CCPC cascade (2% to allocated SBD pool, 8% above)"
    :kontor.provision/citation        "https://kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts"
    :kontor.provision/effective-from  #inst "2020-07-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-ab]
                                        [:eq [:tax-unit :ccpc?] true]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-ab-ccpc-sbd
                                        :label "Alberta CCPC schedule: 2% to allocated SBD pool, 8% above"
                                        :schedule {:kontor.schedule/type :formula
                                                   :fn-from :compute-fn
                                                   :fn :ca-ab-ccpc-schedule}})}

   {:kontor.provision/code            "CA-AB-CTA-§21-General"
    :kontor.provision/jurisdiction    :ca-ab
    :kontor.provision/concept         [:kontor.tax-concept/code :elective-regime]
    :kontor.provision/title           "Alberta Corporate Tax Act §21 — general 8% (non-CCPCs)"
    :kontor.provision/citation        "https://kings-printer.alberta.ca/1266.cfm?page=A15.cfm&leg_type=Acts"
    :kontor.provision/effective-from  #inst "2020-07-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :component :ca-ab]
                                        [:not [:eq [:tax-unit :ccpc?] true]]])
    :kontor.provision/consequence     (pr-str {:op :schedule-override
                                        :code :ca-ab-general
                                        :label "Alberta general schedule: flat 8%"
                                        :schedule {:kontor.schedule/type :flat
                                                   :rate-from :parameter
                                                   :parameter "CA.AB.CIT.general-rate"}})}])

;; ============================================================================
;; Install! — transact parameters + provisions into a connection
;; ============================================================================

(defn install!
  "Install CA CIT statute (parameters + parameter-values + provisions)
   into `conn`. Idempotent — `:kontor.parameter/code` and `:kontor.provision/code`
   are unique identity attrs, so re-running the install is a no-op on
   unchanged rows."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

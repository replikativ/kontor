(ns kontor.l10n-in.cgt-statute
  "Indian capital-gains tax — Income-tax Act, 1961 (post-Finance Act
   2024) — encoded as `kontor.statute` data per ADR-101. Research
   note 131.

   The Finance (No. 2) Act 2024 (effective for transfers from 23 July
   2024) collapsed a previously complex stack into TWO uniform rates
   and removed indexation for nearly every asset class:

   - **12.5 % LTCG** (no indexation, all asset classes; §112 / §112A)
     replaced the previous patchwork (10 % equity / 20 %-w-indexation
     for everything else).
   - **20 % STCG** on listed STT-paid equity (§111A) — raised from the
     pre-FA-2024 15 %.
   - **₹ 1.25 lakh** annual §112A floor for listed-equity LTCG, raised
     from ₹ 1 lakh.
   - **Resident-individual / HUF election** for **pre-23-Jul-2024**
     immovable property: 12.5 % without indexation OR 20 % with
     indexation (CII) — taxpayer picks the lower-tax option per-disposal.
   - **§54EC ₹ 50 lakh cap** on the bond-reinvestment exemption (per FY,
     across NHAI / REC / PFC / IRFC 5-year bonds).
   - **§194-IA 1 % TDS** on immovable-property transfers > ₹ 50 lakh
     (creditable against final tax).

   Finance Act 2025 made NO further changes to the CGT rates. The
   Income-tax Bill 2025 (effective AY 2026-27) tidies section
   numbering and adds the **one-time** relief: pre-31-Mar-2026 LTCL
   may set off against ANY capital gain (incl. STCG) from AY 2027-28
   onward (§74 modification).

   All five rollover-relief sections (§54, §54B, §54D, §54EC, §54F)
   plus the §54G/§54GA/§54GB family are DEFERRED to the provider —
   the substrate-fit note (131 §3.4) tracks them via the disposal's
   `:exemption-claimed` set; the provider folds them in priority order
   (§54EC first because of its hard ₹ 50 L cap, then §54/§54F).

   Citations point at incometaxindia.gov.in (CBDT-hosted IT Act
   mirror) and the official CBDT tutorials. The 4 % Health &
   Education cess and the income-banded surcharge live in the IN PIT
   provider (`kontor.l10n-in.period-tax-provider`) — when this CGT
   provider folds slab-rate STCG into PIT, the cess rides on PIT.
   When standalone (LTCG at 12.5 %, equity STCG at 20 %) the cess
   fires as a surtax in the IN-CGT-component."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "IN CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`. The rate cliff at 23-Jul-2024 (FA 2024)
   uses `:effective-until` on the OLD parameter row + `:effective-from`
   on the NEW row to express the half-open window cleanly."
  [;; --- Holding-period cutoffs (post-FA-2024 two-tier) -----------------------
   {:parameter/code         "IN.CGT.holding-period-cutoff-listed-equity-months"
    :parameter/label        "Listed equity / equity-MF / BT-units LTCG cutoff (months)"
    :parameter/jurisdiction :in
    :parameter/unit         :months
    :parameter/concept-iri  "https://incometaxindia.gov.in/_layouts/15/dit/pages/viewer.aspx?path=https://www.incometaxindia.gov.in/acts/income-tax%20act,%201961/2024/102120000000089006.htm"}

   {:parameter/code         "IN.CGT.holding-period-cutoff-other-months"
    :parameter/label        "All other assets LTCG cutoff (months) — post-FA-2024"
    :parameter/jurisdiction :in
    :parameter/unit         :months
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/14-%20stcg.pdf"}

   ;; --- Rates (post-FA-2024 uniformised) -------------------------------------
   {:parameter/code         "IN.CGT.§112A.rate"
    :parameter/label        "§112A — listed STT-paid equity LTCG rate"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"}

   {:parameter/code         "IN.CGT.§111A.rate"
    :parameter/label        "§111A — listed STT-paid equity STCG rate"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://cleartax.in/s/short-term-capital-gain-on-shares"}

   {:parameter/code         "IN.CGT.§112.rate"
    :parameter/label        "§112 — default LTCG rate (no indexation, post-FA-2024)"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"}

   {:parameter/code         "IN.CGT.§112.rate-with-indexation"
    :parameter/label        "§112 proviso — LTCG rate with indexation (resident indiv/HUF, pre-23-Jul-2024 immovable)"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"}

   ;; --- §112A floor (FA 2024 raised ₹1 L → ₹1.25 L) -------------------------
   {:parameter/code         "IN.CGT.§112A.floor"
    :parameter/label        "§112A — annual LTCG exemption floor for STT-paid listed equity"
    :parameter/jurisdiction :in
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"}

   ;; --- §54EC bond cap -------------------------------------------------------
   {:parameter/code         "IN.CGT.§54EC.cap-per-fy"
    :parameter/label        "§54EC — annual ₹50 L cap on REC/NHAI/PFC/IRFC bond reinvestment"
    :parameter/jurisdiction :in
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://cleartax.in/s/section-54ec-bonds"}

   ;; --- §54 / §54F new-residential-house cost cap (FA 2023) -----------------
   {:parameter/code         "IN.CGT.§54.cost-cap"
    :parameter/label        "§54 / §54F — ₹10 crore cap on the new residential house cost (FA 2023, AY 2024-25+)"
    :parameter/jurisdiction :in
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.bajajfinserv.in/understanding-sec-54-of-the-income-tax-act"}

   ;; --- §194-IA TDS rate + threshold ----------------------------------------
   {:parameter/code         "IN.CGT.§194-IA.rate"
    :parameter/label        "§194-IA — buyer-side TDS rate on immovable-property purchase"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.incometaxindia.gov.in/w/tds-purchase-of-immovable-property"}

   {:parameter/code         "IN.CGT.§194-IA.threshold"
    :parameter/label        "§194-IA — consideration threshold below which TDS does not apply"
    :parameter/jurisdiction :in
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.incometaxindia.gov.in/w/tds-purchase-of-immovable-property"}

   ;; --- §50C anti-undervaluation tolerance ----------------------------------
   {:parameter/code         "IN.CGT.§50C.safe-harbour-ratio"
    :parameter/label        "§50C — SDV-vs-consideration safe-harbour ratio (FA 2020: SDV must exceed 110 %)"
    :parameter/jurisdiction :in
    :parameter/unit         :ratio
    :parameter/concept-iri  "https://cleartax.in/s/taxability-sale-land-building-section-50c"}

   ;; --- 4 % Health & Education cess (rides on standalone CGT) ---------------
   {:parameter/code         "IN.CGT.cess.rate"
    :parameter/label        "Health & Education cess — 4 % surtax on standalone CGT liability"
    :parameter/jurisdiction :in
    :parameter/unit         :rate
    :parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"}

   ;; --- CII table — base FY 2001-02 = 100 -----------------------------------
   {:parameter/code         "IN.CGT.cii.fy-2001-02"
    :parameter/label        "CII — Cost Inflation Index, FY 2001-02 (base)"
    :parameter/jurisdiction :in :parameter/unit :index
    :parameter/concept-iri  "https://incometaxindia.gov.in/charts%20%20tables/cost-inflation-index.htm"}
   {:parameter/code "IN.CGT.cii.fy-2002-03" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2003-04" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2004-05" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2005-06" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2006-07" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2007-08" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2008-09" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2009-10" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2010-11" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2011-12" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2012-13" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2013-14" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2014-15" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2015-16" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2016-17" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2017-18" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2018-19" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2019-20" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2020-21" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2021-22" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2022-23" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2023-24" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2024-25" :parameter/jurisdiction :in :parameter/unit :index}
   {:parameter/code "IN.CGT.cii.fy-2025-26" :parameter/jurisdiction :in :parameter/unit :index}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "IN CGT parameter values. Pre-FA-2024 rates carry an `:effective-until`
   of 2024-07-23 (the rate cliff); post-FA-2024 rates begin on
   2024-07-23. A disposal `:disposed-on` queried as-of a pre-cliff
   instant reads the old rate — the bitemporal axis carries the law as
   it stood (ADR-008 + the wider kontor bitemporal posture)."
  [;; --- Holding-period cutoffs ----------------------------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.holding-period-cutoff-listed-equity-months"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  12M
    :parameter-value/citation       "Income-tax Act §2(42A) — 12-month cutoff for listed equity / equity MF / BT units"}

   {:parameter-value/parameter      [:parameter/code "IN.CGT.holding-period-cutoff-other-months"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  24M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — collapsed the 36-mo bucket to 24 months for all other assets"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.holding-period-cutoff-other-months"]
    :parameter-value/effective-from #inst "1962-04-01"
    :parameter-value/effective-until #inst "2024-07-23"
    :parameter-value/decimal-value  36M
    :parameter-value/citation       "Pre-FA-2024 §2(42A) — 36-month cutoff for other assets"}

   ;; --- §112A — listed STT-paid LTCG rate (FA 2024 cliff) -------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112A.rate"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  0.125M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — §112A raised to 12.5 % from 10 %"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112A.rate"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/effective-until #inst "2024-07-23"
    :parameter-value/decimal-value  0.10M
    :parameter-value/citation       "Finance Act 2018 — §112A introduced at 10 %"}

   ;; --- §111A — listed STT-paid STCG rate (FA 2024 cliff) -------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§111A.rate"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — §111A raised to 20 % from 15 %"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§111A.rate"]
    :parameter-value/effective-from #inst "2008-10-01"
    :parameter-value/effective-until #inst "2024-07-23"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "Pre-FA-2024 §111A — 15 %"}

   ;; --- §112 — default LTCG rate (post-FA-2024 uniform 12.5 %) --------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112.rate"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  0.125M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — §112 uniformised to 12.5 % without indexation"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112.rate"]
    :parameter-value/effective-from #inst "1992-04-01"
    :parameter-value/effective-until #inst "2024-07-23"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "Pre-FA-2024 §112 — 20 % with indexation (now elective for resident indiv/HUF pre-2024 immovable only)"}

   ;; --- §112 proviso — 20 % with indexation (FA 2024 election) --------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112.rate-with-indexation"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — §112 proviso — resident indiv/HUF election on pre-23-Jul-2024 immovable property"}

   ;; --- §112A floor (FA 2024 raised) ----------------------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112A.floor"]
    :parameter-value/effective-from #inst "2024-07-23"
    :parameter-value/decimal-value  125000M
    :parameter-value/citation       "Finance (No. 2) Act 2024 — §112A floor raised to ₹1.25 L (from ₹1 L)"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§112A.floor"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/effective-until #inst "2024-07-23"
    :parameter-value/decimal-value  100000M
    :parameter-value/citation       "Pre-FA-2024 §112A floor — ₹1 L"}

   ;; --- §54EC bond cap (₹50 L since 2014) ------------------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§54EC.cap-per-fy"]
    :parameter-value/effective-from #inst "2014-04-01"
    :parameter-value/decimal-value  5000000M
    :parameter-value/citation       "Finance Act 2014 — §54EC capped at ₹50 L per FY across all eligible bonds"}

   ;; --- §54 / §54F new-house cost cap (FA 2023, AY 2024-25+) ---------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§54.cost-cap"]
    :parameter-value/effective-from #inst "2023-04-01"
    :parameter-value/decimal-value  100000000M
    :parameter-value/citation       "Finance Act 2023 — §54 / §54F cap on new-residential-house cost (₹10 crore) AY 2024-25+"}

   ;; --- §194-IA TDS rate + threshold ----------------------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§194-IA.rate"]
    :parameter-value/effective-from #inst "2013-06-01"
    :parameter-value/decimal-value  0.01M
    :parameter-value/citation       "Finance Act 2013 — §194-IA: 1 % TDS on immovable-property purchase (FA 2024: applied to max(consideration, SDV))"}
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§194-IA.threshold"]
    :parameter-value/effective-from #inst "2013-06-01"
    :parameter-value/decimal-value  5000000M
    :parameter-value/citation       "§194-IA — TDS triggers when consideration ≥ ₹50 L"}

   ;; --- §50C safe-harbour ratio (FA 2020: 110 %) ----------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.§50C.safe-harbour-ratio"]
    :parameter-value/effective-from #inst "2020-04-01"
    :parameter-value/decimal-value  1.10M
    :parameter-value/citation       "Finance Act 2020 — §50C safe-harbour raised to 110 % (from 105 %)"}

   ;; --- 4 % cess (long-standing since FA 2018) ------------------------------
   {:parameter-value/parameter      [:parameter/code "IN.CGT.cess.rate"]
    :parameter-value/effective-from #inst "2018-04-01"
    :parameter-value/decimal-value  0.04M
    :parameter-value/citation       "Finance Act 2018 — 4 % Health & Education cess on total tax"}

   ;; --- CII table (FY 2001-02 = 100, base since FA 2017) --------------------
   ;; Source: https://incometaxindia.gov.in/charts%20%20tables/cost-inflation-index.htm
   {:parameter-value/parameter      [:parameter/code "IN.CGT.cii.fy-2001-02"]
    :parameter-value/effective-from #inst "2001-04-01"
    :parameter-value/decimal-value  100M
    :parameter-value/citation       "CBDT Notification — CII base FY 2001-02 = 100 (FA 2017 rebase)"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2002-03"] :parameter-value/effective-from #inst "2002-04-01" :parameter-value/decimal-value 105M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2003-04"] :parameter-value/effective-from #inst "2003-04-01" :parameter-value/decimal-value 109M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2004-05"] :parameter-value/effective-from #inst "2004-04-01" :parameter-value/decimal-value 113M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2005-06"] :parameter-value/effective-from #inst "2005-04-01" :parameter-value/decimal-value 117M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2006-07"] :parameter-value/effective-from #inst "2006-04-01" :parameter-value/decimal-value 122M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2007-08"] :parameter-value/effective-from #inst "2007-04-01" :parameter-value/decimal-value 129M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2008-09"] :parameter-value/effective-from #inst "2008-04-01" :parameter-value/decimal-value 137M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2009-10"] :parameter-value/effective-from #inst "2009-04-01" :parameter-value/decimal-value 148M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2010-11"] :parameter-value/effective-from #inst "2010-04-01" :parameter-value/decimal-value 167M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2011-12"] :parameter-value/effective-from #inst "2011-04-01" :parameter-value/decimal-value 184M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2012-13"] :parameter-value/effective-from #inst "2012-04-01" :parameter-value/decimal-value 200M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2013-14"] :parameter-value/effective-from #inst "2013-04-01" :parameter-value/decimal-value 220M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2014-15"] :parameter-value/effective-from #inst "2014-04-01" :parameter-value/decimal-value 240M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2015-16"] :parameter-value/effective-from #inst "2015-04-01" :parameter-value/decimal-value 254M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2016-17"] :parameter-value/effective-from #inst "2016-04-01" :parameter-value/decimal-value 264M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2017-18"] :parameter-value/effective-from #inst "2017-04-01" :parameter-value/decimal-value 272M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2018-19"] :parameter-value/effective-from #inst "2018-04-01" :parameter-value/decimal-value 280M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2019-20"] :parameter-value/effective-from #inst "2019-04-01" :parameter-value/decimal-value 289M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2020-21"] :parameter-value/effective-from #inst "2020-04-01" :parameter-value/decimal-value 301M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2021-22"] :parameter-value/effective-from #inst "2021-04-01" :parameter-value/decimal-value 317M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2022-23"] :parameter-value/effective-from #inst "2022-04-01" :parameter-value/decimal-value 331M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2023-24"] :parameter-value/effective-from #inst "2023-04-01" :parameter-value/decimal-value 348M :parameter-value/citation "CBDT CII"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2024-25"] :parameter-value/effective-from #inst "2024-04-01" :parameter-value/decimal-value 363M :parameter-value/citation "CBDT CII FY 2024-25"}
   {:parameter-value/parameter [:parameter/code "IN.CGT.cii.fy-2025-26"] :parameter-value/effective-from #inst "2025-04-01" :parameter-value/decimal-value 376M :parameter-value/citation "CBDT CII FY 2025-26"}])

;; ============================================================================
;; Provisions — minimal v1 set (most IN CGT logic lives in the provider)
;; ============================================================================

(def provisions
  "IN CGT provisions encoded for the `kontor.statute` evaluator.

   Most IN CGT logic — §54/§54EC/§54F two-pass exemption stacking,
   the FA 2024 lower-of-12.5/20 % election on pre-2024 immovable,
   §50C deemed-proceeds, §194-IA TDS prepayment, the 4 % cess —
   lives in the PROVIDER (note 131 §5). These provisions wire only
   the cross-cutting items that benefit from `kontor.statute`'s
   provenance fold:

   - 4 % Health & Education cess as a `:surtax` on the standalone
     CGT running tax (LTCG + equity STCG)."

  [;; --------------------------------------------------------------------
   ;; 4 % cess — surtax on standalone CGT liability
   ;; --------------------------------------------------------------------
   {:provision/code            "IN-FA-2018-cess-cgt"
    :provision/jurisdiction    :in
    :provision/concept         [:kontor.tax-concept/code :surtax]
    :provision/title           "Health & Education cess — 4 % on standalone CGT tax"
    :provision/citation        "https://incometaxindia.gov.in/tutorials/65.exemptions-from-capital-gains.pdf"
    :provision/effective-from  #inst "2018-04-01"
    :provision/priority        100
    :provision/condition       (pr-str [:gt [:inputs :standalone-cgt-running-tax] 0M])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :health-education-cess
                                        :label "§ 2(11) Finance Act — Health & Education cess (4 %)"
                                        :amount-from :compute-fn
                                        :fn :in-cgt-cess})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install IN CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

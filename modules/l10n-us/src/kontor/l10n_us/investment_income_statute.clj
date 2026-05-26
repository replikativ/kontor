(ns kontor.l10n-us.investment-income-statute
  "US investment-income tax — dividends + interest — encoded as
   `kontor.statute` data per ADR-101. Research note 148.

   THE KEY INSIGHT (note 148 §4.4): most parameters this provider
   needs ALREADY EXIST in `cgt-statute.clj`:

     - `US.CGT.LT.rate-0` / `-15` / `-20` (the §1(h)(11) qualified-
       dividend rates ARE the §1(h) LT brackets)
     - `US.CGT.LT.threshold-<status>-0to15` / `-15to20` (per filing
       status)
     - `US.CGT.§1411.rate` / `.threshold-<status>` (NIIT)

   This namespace adds just the §163(d) investment-interest deduction
   cap parameter + the §103 muni-interest exempt-account-pattern
   convention. The §163(d) cap also requires a registered compute-fn
   in the provider.

   No new `:parameter-bracket` rows; no per-filing-status rate
   gradation; just two cliff parameters + one provision."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters
;; ============================================================================

(def parameters
  "US investment-income parameter definitions. Most are reused from
   `cgt-statute.clj`; only §163(d) + §103 anchor here."
  [{:parameter/code         "US.INV.§163d.deduction-rate"
    :parameter/label        "§163(d) investment-interest deduction cap rate (always 100% of NII)"
    :parameter/jurisdiction :us
    :parameter/unit         :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/163#d"}

   {:parameter/code         "US.INV.§103.exempt-account-pattern"
    :parameter/label        "§103 municipal-interest exempt-account chart prefix (documentation)"
    :parameter/jurisdiction :us
    :parameter/unit         :rate                ; placeholder unit — provider doesn't read this
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/103"}])

(def parameter-values
  "US investment-income parameter values."
  [{:parameter-value/parameter      [:parameter/code "US.INV.§163d.deduction-rate"]
    :parameter-value/effective-from #inst "1986-10-22"  ; TRA 1986
    :parameter-value/decimal-value  1.00M
    :parameter-value/citation       "IRC §163(d)(1) — deduction limited to net investment income"}

   {:parameter-value/parameter      [:parameter/code "US.INV.§103.exempt-account-pattern"]
    :parameter-value/effective-from #inst "1986-10-22"
    :parameter-value/decimal-value  0M    ; sentinel — documentation only
    :parameter-value/citation       "IRC §103 — interest on State + local bonds exempt from gross income"}])

;; ============================================================================
;; Provisions
;; ============================================================================

(def provisions
  "US investment-income provisions for the `kontor.statute` evaluator.
   The §163(d) cap is the only non-trivial rule in v1 — it gates the
   investment-interest deduction on net investment income."

  [;; §163(d) investment-interest deduction cap — limited to NII.
   {:provision/code            "US-IRC-§163d-investment-interest-cap"
    :provision/jurisdiction    :us
    :provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :provision/title           "§163(d) — investment-interest deduction limited to net investment income"
    :provision/citation        "https://www.law.cornell.edu/uscode/text/26/163#d"
    :provision/effective-from  #inst "1986-10-22"
    :provision/priority        100
    :provision/condition       (pr-str [:gt [:inputs :investment-interest-paid] 0M])
    :provision/consequence     (pr-str {:op :base-deduct
                                        :code :§163d-deduction
                                        :label "§163(d) investment-interest deduction"
                                        :amount-from :compute-fn
                                        :fn :us-§163d-cap})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install US investment-income statute (parameters + provisions)
   into `conn`. Idempotent via `:parameter/code` + `:provision/code`
   unique identity attrs.

   ASSUMES the US CGT statute has already been installed (the bracket
   + NIIT parameters live there)."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

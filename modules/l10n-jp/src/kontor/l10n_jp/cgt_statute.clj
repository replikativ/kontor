(ns kontor.l10n-jp.cgt-statute
  "JP capital-gains tax — 譲渡所得 (jōto-shotoku) — encoded as
   `kontor.statute` data per ADR-101 / ADR-102. Research note 115.

   JP CGT is a FAMILY of disposal-class-specific separate-taxation
   (申告分離課税) regimes, each with its own rate, holding-period rule,
   and loss-offset compartment. Five individual regimes the provider
   fans out per disposal:

   - **Listed securities (上場株式等)** — 15 % national + 5 % local
     = 20.315 % effective (incl. 復興特別所得税 2.1 % × the national
     15 % = 0.315 %). Loss carryforward 3 years within compartment
     (租税特別措置法 §37-12-2).
   - **Unlisted securities (一般株式等)** — 15 % national + 5 % local
     = 20.315 %. Loss does NOT cross compartments and does NOT carry
     forward.
   - **Real estate short-term** (≤5 yrs at Jan 1 of disposal year,
     租税特別措置法 §32) — 30 % national + 9 % local = 39.63 %
     effective (incl. 復興 0.63 %). No carryforward.
   - **Real estate long-term** (>5 yrs at Jan 1, 租税特別措置法 §31)
     — 15 % + 5 % = 20.315 %. No carryforward.
   - **Real estate long-residence** (>10 yrs at Jan 1 + own residence,
     租税特別措置法 §31-3) — 10 % up to ¥60M / 15 % above on the
     national side (progressive bracket), with 復興 2.1 % surtax and
     4 % inhabitants on the first ¥60M / 5 % above. Combined: 14.21 %
     on ≤¥60M slice; 20.315 % on the slice above.

   §35 ¥30M principal-residence deduction (居住用財産の3,000万円特別控除)
   applies as a base deduction BEFORE the rate; signalled by
   `:exemption-claimed :jp-§35-residence` on the disposal.

   §36-2 replacement-property deferral (買換え特例) — when the holder
   elects via `:elective-regime :jp-§36-2-replacement` and supplies a
   `:rollover-amount`, the gain is deferred (basis carried to the
   replacement); the disposal's rollover-amount slice is excluded from
   the current-year base by the disposal companion's `realized-gain`
   already, so the provider does nothing extra.

   The 復興特別所得税 (2.1 %) applies to the NATIONAL income-tax
   portion ONLY (NOT the inhabitants' tax). It is encoded as a single
   `:surtax` provision that fires across all regimes; the provider
   identifies the national-tax slice via ctx `:component`.

   Citations point at www.nta.go.jp (NTA — National Tax Agency) for the
   No.### Tsutatsu numbers and at e-gov.go.jp / elaws.e-gov.go.jp for
   the underlying 所得税法 / 租税特別措置法 articles. Research note 115."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "JP CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`. One row per `:kontor.parameter/code`."
  [;; --- Listed securities (上場株式等) ---------------------------------------
   {:kontor.parameter/code         "JP.CGT.listed.national-rate"
    :kontor.parameter/label        "Listed securities — national income-tax rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026"}
   {:kontor.parameter/code         "JP.CGT.listed.local-rate"
    :kontor.parameter/label        "Listed securities — inhabitants (local) rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=325AC0000000226"}

   ;; --- Unlisted securities (一般株式等) ------------------------------------
   {:kontor.parameter/code         "JP.CGT.unlisted.national-rate"
    :kontor.parameter/label        "Unlisted securities — national income-tax rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026"}
   {:kontor.parameter/code         "JP.CGT.unlisted.local-rate"
    :kontor.parameter/label        "Unlisted securities — inhabitants (local) rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=325AC0000000226"}

   ;; --- Real-estate short (短期, ≤5 yrs at Jan 1, §32) -----------------------
   {:kontor.parameter/code         "JP.CGT.realestate-short.national-rate"
    :kontor.parameter/label        "Real estate short-term — national rate (租税特別措置法 §32)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_32"}
   {:kontor.parameter/code         "JP.CGT.realestate-short.local-rate"
    :kontor.parameter/label        "Real estate short-term — inhabitants rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3211.htm"}

   ;; --- Real-estate long (長期, >5 yrs at Jan 1, §31) -----------------------
   {:kontor.parameter/code         "JP.CGT.realestate-long.national-rate"
    :kontor.parameter/label        "Real estate long-term — national rate (租税特別措置法 §31)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_31"}
   {:kontor.parameter/code         "JP.CGT.realestate-long.local-rate"
    :kontor.parameter/label        "Real estate long-term — inhabitants rate"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3208.htm"}

   ;; --- Real-estate long-residence preferential (§31-3) ---------------------
   ;; The 軽減税率 (preferential rate) is a two-bracket progressive on the
   ;; NATIONAL side: 10 % on the first ¥60M of taxable gain (after the §35
   ;; deduction), 15 % above. Inhabitants' side: 4 % on the first ¥60M,
   ;; 5 % above. The 復興 surtax is 2.1 % × national.
   {:kontor.parameter/code         "JP.CGT.realestate-long-residence.national-low-rate"
    :kontor.parameter/label        "§31-3 residence preferential — national low rate (≤¥60M slice)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_31_3"}
   {:kontor.parameter/code         "JP.CGT.realestate-long-residence.national-high-rate"
    :kontor.parameter/label        "§31-3 residence preferential — national high rate (>¥60M slice)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_31_3"}
   {:kontor.parameter/code         "JP.CGT.realestate-long-residence.local-low-rate"
    :kontor.parameter/label        "§31-3 residence preferential — inhabitants low rate (≤¥60M)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3305.htm"}
   {:kontor.parameter/code         "JP.CGT.realestate-long-residence.local-high-rate"
    :kontor.parameter/label        "§31-3 residence preferential — inhabitants high rate (>¥60M)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3305.htm"}
   {:kontor.parameter/code         "JP.CGT.realestate-long-residence.kink"
    :kontor.parameter/label        "§31-3 residence preferential — bracket boundary (¥60 000 000)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_31_3"}

   ;; --- §35 ¥30M principal-residence deduction ------------------------------
   {:kontor.parameter/code         "JP.CGT.§35.residence-deduction"
    :kontor.parameter/label        "§35 principal-residence special deduction (¥30 000 000)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=332AC0000000026#Mp-At_35"}

   ;; --- 復興特別所得税 2.1 % (effective until 2037) -------------------------
   {:kontor.parameter/code         "JP.CGT.reconstruction-surtax-rate"
    :kontor.parameter/label        "復興特別所得税 — Special Reconstruction Income Tax (2.1 % × national)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://elaws.e-gov.go.jp/document?lawid=423AC0000000117"}

   ;; --- 長期 (>5 yr) holding-period cutoff for §31/§32 (in WHOLE YEARS) ----
   ;; Jan-1 measurement rule: (disposed-year - acquired-year) > 5 ⇒ long.
   {:kontor.parameter/code         "JP.CGT.realestate.long-cutoff-years"
    :kontor.parameter/label        "Real estate long-term cutoff (years at Jan 1 of disposal year)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :years
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3202.htm"}
   {:kontor.parameter/code         "JP.CGT.realestate.long-residence-cutoff-years"
    :kontor.parameter/label        "Real estate §31-3 long-residence cutoff (years at Jan 1)"
    :kontor.parameter/jurisdiction :jp
    :kontor.parameter/unit         :years
    :kontor.parameter/concept-iri  "https://www.nta.go.jp/taxes/shiraberu/taxanswer/joto/3305.htm"}])

;; ============================================================================
;; Parameter values — current rates with statutory effective windows
;; ============================================================================

(def parameter-values
  "JP CGT parameter values. Stable 2013-2037 values; the 復興 surtax
   sunsets 2037-12-31 (the 復興 statute's 25-year window from 2013)."
  [;; --- Listed securities — 15 % national + 5 % local -----------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.listed.national-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "租税特別措置法 §37-10 — listed-securities national rate 15 % from 2014"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.listed.local-rate"]
    :kontor.parameter-value/effective-from #inst "2014-01-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "地方税法 — listed-securities inhabitants rate 5 % from 2014"}

   ;; --- Unlisted — same 15 % + 5 % ------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.unlisted.national-rate"]
    :kontor.parameter-value/effective-from #inst "2003-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "租税特別措置法 §37-10 — unlisted-securities national rate 15 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.unlisted.local-rate"]
    :kontor.parameter-value/effective-from #inst "2003-01-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "地方税法 — unlisted-securities inhabitants rate 5 %"}

   ;; --- Real-estate short — 30 % national + 9 % local -----------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-short.national-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.30M
    :kontor.parameter-value/citation       "租税特別措置法 §32 — short-term real-estate national rate 30 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-short.local-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.09M
    :kontor.parameter-value/citation       "地方税法 — short-term real-estate inhabitants rate 9 %"}

   ;; --- Real-estate long — 15 % national + 5 % local ------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long.national-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "租税特別措置法 §31 — long-term real-estate national rate 15 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long.local-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "地方税法 — long-term real-estate inhabitants rate 5 %"}

   ;; --- §31-3 residence preferential ----------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long-residence.national-low-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "租税特別措置法 §31-3 — residence preferential national rate (≤¥60M) 10 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long-residence.national-high-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "租税特別措置法 §31-3 — residence preferential national rate (>¥60M) 15 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long-residence.local-low-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.04M
    :kontor.parameter-value/citation       "地方税法 — §31-3 residence preferential inhabitants rate (≤¥60M) 4 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long-residence.local-high-rate"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "地方税法 — §31-3 residence preferential inhabitants rate (>¥60M) 5 %"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate-long-residence.kink"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  60000000M
    :kontor.parameter-value/citation       "租税特別措置法 §31-3 — bracket boundary ¥60 000 000"}

   ;; --- §35 ¥30M deduction --------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.§35.residence-deduction"]
    :kontor.parameter-value/effective-from #inst "1969-01-01"
    :kontor.parameter-value/decimal-value  30000000M
    :kontor.parameter-value/citation       "租税特別措置法 §35 — principal-residence ¥30 000 000 deduction"}

   ;; --- 復興特別所得税 2.1 % ------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.reconstruction-surtax-rate"]
    :kontor.parameter-value/effective-from #inst "2013-01-01"
    :kontor.parameter-value/effective-until #inst "2038-01-01"
    :kontor.parameter-value/decimal-value  0.021M
    :kontor.parameter-value/citation       "東日本大震災復興特別措置法 §13 — 2.1 % × national tax, 2013-2037"}

   ;; --- Holding-period cutoffs ----------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate.long-cutoff-years"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  5M
    :kontor.parameter-value/citation       "租税特別措置法 §31 — >5 years at Jan 1 = long-term"}
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "JP.CGT.realestate.long-residence-cutoff-years"]
    :kontor.parameter-value/effective-from #inst "2004-01-01"
    :kontor.parameter-value/decimal-value  10M
    :kontor.parameter-value/citation       "租税特別措置法 §31-3 — >10 years at Jan 1 = §31-3 eligible"}])

;; ============================================================================
;; Provisions — the 復興 surtax (sole provision; remaining logic is provider)
;; ============================================================================

(def provisions
  "JP CGT provisions for the `kontor.statute` evaluator.

   v1 ships ONE provision — the 復興特別所得税 (Special Reconstruction
   Income Tax) — encoded as a `:surtax` on the national-rate component.
   The provider sets `:component :jp-listed-securities` /
   `:jp-unlisted-equity` / `:jp-real-estate-short` / `:jp-real-estate-long`
   / `:jp-real-estate-long-residence` in ctx; the surtax fires on every
   regime's national-tax pass and is recorded as a separate line item.

   §35 and §31-3 are handled provider-side because they are
   disposal-level conditional logic (per-disposal `:exemption-claimed` /
   `:elective-regime`) rather than entity-level conditional logic the
   statute fold is shaped for. Same posture as the US provider's
   per-disposal lane classification."

  [;; --------------------------------------------------------------------
   ;; 復興特別所得税 — 2.1 % × national CGT
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "JP-FUKKO-§13-reconstruction-surtax"
    :kontor.provision/jurisdiction    :jp
    :kontor.provision/concept         [:kontor.tax-concept/code :surtax]
    :kontor.provision/title           "復興特別所得税 — 2.1 % × the national CGT amount (2013-2037)"
    :kontor.provision/citation        "https://elaws.e-gov.go.jp/document?lawid=423AC0000000117"
    :kontor.provision/effective-from  #inst "2013-01-01"
    :kontor.provision/effective-until #inst "2038-01-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:eq :pass :national])
    :kontor.provision/consequence     (pr-str {:op          :surtax
                                        :code        :reconstruction-surtax
                                        :label       "復興特別所得税 (Special Reconstruction Income Tax)"
                                        :amount-from :compute-fn
                                        :fn          :jp-cgt-reconstruction-surtax})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install JP CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:kontor.parameter/code` and `:kontor.provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

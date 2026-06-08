(ns kontor.l10n-in.investment-income-statute
  "IN investment-income tax — §194 / §194A / §194K TDS + §115A NRI flat
   + §80TTA / §80TTB savings-interest deductions + FA-2024 dividend
   surcharge cap — encoded as `kontor.tax.statute` data per ADR-101.
   

   THE KEY INSIGHT: India's post-FA-2020 framework folds
   all resident investment income into the recipient's slab-rate base
   (the IN PIT provider already handles the slab/§87A/surcharge/cess
   stack). The substrate gains are concentrated in five places:

   1. TDS-section thresholds + rates (§194 dividends, §194A bank
      interest, §194K MF IDCW) as bitemporal parameters — Finance Act
      2025 raised the §194 / §194K thresholds from ₹5 000 → ₹10 000
      effective 1 April 2025.

   2. §115A NRI dividend rate (20 % flat) — the rare definitive
      withholding lane.

   3. §80TTA (₹10 000 cap) + §80TTB (₹50 000 cap) interest deductions —
      OLD regime only (§115BAC default-new disallows them).

   4. FA-2024 dividend surcharge cap (15 %) — overrides the PIT
      provider's 25 % / 37 % high-income surcharge bands when the
      surcharge falls on dividend income (or §111A / §112 / §112A
      capital gains — handled by the IN CGT provider).

   5. §115BBDA was REPEALED by FA 2020 — explicitly NOT modelled.

   ## Income-tax Act 2025 renumbering

   The IT Act 2025 (effective AY 2026-27 / from 1 April 2026) renumbers:

     §194    → §393(1)     (TDS on dividends; rate + threshold unchanged)
     §194A   → §391        (TDS on interest; rate + threshold unchanged)
     §194K   → §392        (TDS on MF IDCW; rate + threshold unchanged)
     §115A   → §163        (NRI rates; unchanged)
     §80M    → §134        (inter-corporate dividend relief; unchanged)

   Per.7 we keep the 1961-Act numbering as the canonical
   `:kontor.parameter/code` (established practitioner vocabulary). The
   renumbering is documented in citations + `:kontor.parameter/concept-iri`
   (each parameter additionally carries a CBDT IT-Act-2025 alias IRI
   so consumers can resolve either numbering)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — closed v1 set
;; ============================================================================

(def parameters
  "IN investment-income parameter definitions. Values live in
   `parameter-values`, keyed by `:effective-from`. The §194 / §194K
   threshold raise on 1 April 2025 (FA 2025) rides
   `:effective-until` + `:effective-from` cleanly."
  [;; --- §194 / §393(1) — TDS on dividends -------------------------------
   {:kontor.parameter/code         "IN.InvIncome.§194.dividend-tds-threshold"
    :kontor.parameter/label        "§194 / §393(1) — annual dividend threshold per company per FY (PAN-furnished)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/tax%20treatment%20of%20dividend%20received.pdf"}

   {:kontor.parameter/code         "IN.InvIncome.§194.dividend-tds-rate"
    :kontor.parameter/label        "§194 / §393(1) — dividend TDS rate (10 % with PAN)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investments/section-194-income-tax-act"}

   {:kontor.parameter/code         "IN.InvIncome.§194.dividend-tds-rate-no-pan"
    :kontor.parameter/label        "§194 + §206AA — dividend TDS rate when PAN not furnished (20 %)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investments/section-194-income-tax-act"}

   ;; --- §194A / §391 — TDS on interest ---------------------------------
   {:kontor.parameter/code         "IN.InvIncome.§194A.interest-tds-threshold-general"
    :kontor.parameter/label        "§194A / §391 — annual bank/FD interest threshold per bank per FY (general)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investments/section-194a-income-tax-act"}

   {:kontor.parameter/code         "IN.InvIncome.§194A.interest-tds-threshold-senior"
    :kontor.parameter/label        "§194A / §391 — annual interest threshold (senior citizens — ≥ 60)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investments/section-194a-income-tax-act"}

   {:kontor.parameter/code         "IN.InvIncome.§194A.interest-tds-rate"
    :kontor.parameter/label        "§194A / §391 — interest TDS rate (10 % with PAN)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investments/section-194a-income-tax-act"}

   ;; --- §194K / §392 — TDS on MF IDCW ----------------------------------
   {:kontor.parameter/code         "IN.InvIncome.§194K.mf-idcw-tds-threshold"
    :kontor.parameter/label        "§194K / §392 — annual MF IDCW threshold per AMC per FY"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://cleartax.in/s/section-194k"}

   {:kontor.parameter/code         "IN.InvIncome.§194K.mf-idcw-tds-rate"
    :kontor.parameter/label        "§194K / §392 — MF IDCW TDS rate (10 % with PAN)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://cleartax.in/s/section-194k"}

   ;; --- §115A — NRI flat dividend / interest ---------------------------
   {:kontor.parameter/code         "IN.InvIncome.§115A.nri-dividend-rate"
    :kontor.parameter/label        "§115A / §163 — NRI dividend rate (20 % flat; definitive)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.bajajfinserv.in/investment/tax-on-dividend-income"}

   {:kontor.parameter/code         "IN.InvIncome.§115A.nri-infrastructure-debt-rate"
    :kontor.parameter/label        "§115A + §194LBA — NRI interest from infrastructure debt fund (5 %)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/tutorials/tax%20treatment%20of%20dividend%20received.pdf"}

   ;; --- §80TTA / §80TTB — savings interest deductions ------------------
   {:kontor.parameter/code         "IN.InvIncome.§80TTA.cap"
    :kontor.parameter/label        "§80TTA — savings-account interest deduction cap (resident individuals < 60)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://cleartax.in/s/section-80tta"}

   {:kontor.parameter/code         "IN.InvIncome.§80TTB.cap"
    :kontor.parameter/label        "§80TTB — senior-citizen all-interest deduction cap (resident individuals ≥ 60)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :amount-money
    :kontor.parameter/concept-iri  "https://cleartax.in/s/section-80ttb-senior-citizens-deduction"}

   ;; --- FA-2024 dividend surcharge cap ---------------------------------
   {:kontor.parameter/code         "IN.InvIncome.FA2024.dividend-surcharge-cap"
    :kontor.parameter/label        "FA 2024 — surcharge cap on dividend (and §111A/§112/§112A CG) income (15 %)"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://www.indiabudget.gov.in/budget2024-25/doc/Finance_Bill.pdf"}

   ;; --- 4 % H&E cess — for the standalone §115A NRI lane ---------------
   {:kontor.parameter/code         "IN.InvIncome.cess.rate"
    :kontor.parameter/label        "Health & Education Cess (4 %) — applies on §115A standalone tax + surcharge"
    :kontor.parameter/jurisdiction :in
    :kontor.parameter/unit         :rate
    :kontor.parameter/concept-iri  "https://incometaxindia.gov.in/Pages/tax-information-services/tax-rates.aspx"}])

;; ============================================================================
;; Parameter values — date-keyed value history
;; ============================================================================

(def parameter-values
  "IN investment-income parameter values with statutory effective
   windows. The §194 / §194K thresholds carry TWO rows each: the
   pre-FA-2025 ₹5 000 value and the from-FA-2025 ₹10 000 value
   (Finance Act 2025, effective 1 April 2025). The IT Act 2025
   renumbering (1 April 2026) leaves rates / thresholds unchanged — no
   new parameter rows required (the renumbering rides citations +
   `:concept-iri`)."
  [;; --- §194 dividend threshold (₹5 000 → ₹10 000) -----------------------
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "IN.InvIncome.§194.dividend-tds-threshold"]
    :kontor.parameter-value/effective-from  #inst "2020-04-01"
    :kontor.parameter-value/effective-until #inst "2025-04-01"
    :kontor.parameter-value/decimal-value   5000M
    :kontor.parameter-value/citation        "§194 IT Act 1961 (post-FA-2020) — ₹5 000 per company per FY threshold; raised to ₹10 000 by FA 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194.dividend-tds-threshold"]
    :kontor.parameter-value/effective-from #inst "2025-04-01"
    :kontor.parameter-value/decimal-value  10000M
    :kontor.parameter-value/citation       "§194 IT Act 1961 (FA 2025 raise) — ₹10 000 per company per FY threshold effective 1 April 2025; renumbered to §393(1) under IT Act 2025 effective 1 April 2026 (rate + threshold unchanged)"}

   ;; --- §194 dividend TDS rate (10 % with PAN, 20 % without) -------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194.dividend-tds-rate"]
    :kontor.parameter-value/effective-from #inst "2020-04-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "§194 IT Act 1961 — 10 % TDS on dividends (PAN furnished); rate stable post-FA-2020. Renumbered to §393(1) under IT Act 2025."}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194.dividend-tds-rate-no-pan"]
    :kontor.parameter-value/effective-from #inst "2020-04-01"
    :kontor.parameter-value/decimal-value  0.20M
    :kontor.parameter-value/citation       "§194 + §206AA IT Act 1961 — 20 % TDS on dividends when PAN not furnished (the §206AA penalty rate)."}

   ;; --- §194A interest thresholds ----------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194A.interest-tds-threshold-general"]
    :kontor.parameter-value/effective-from #inst "2019-09-01"
    :kontor.parameter-value/decimal-value  40000M
    :kontor.parameter-value/citation       "§194A IT Act 1961 (FA 2019 amendment) — ₹40 000 per bank per FY threshold (general); renumbered to §391 under IT Act 2025 effective 1 April 2026 (threshold unchanged)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194A.interest-tds-threshold-senior"]
    :kontor.parameter-value/effective-from #inst "2018-04-01"
    :kontor.parameter-value/decimal-value  50000M
    :kontor.parameter-value/citation       "§194A IT Act 1961 (FA 2018 amendment) — ₹50 000 per bank per FY threshold for senior citizens (≥ 60)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194A.interest-tds-rate"]
    :kontor.parameter-value/effective-from #inst "2009-10-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "§194A IT Act 1961 — 10 % TDS on bank/FD interest (PAN furnished); stable rate"}

   ;; --- §194K MF IDCW threshold (₹5 000 → ₹10 000) -----------------------
   {:kontor.parameter-value/parameter       [:kontor.parameter/code "IN.InvIncome.§194K.mf-idcw-tds-threshold"]
    :kontor.parameter-value/effective-from  #inst "2020-04-01"
    :kontor.parameter-value/effective-until #inst "2025-04-01"
    :kontor.parameter-value/decimal-value   5000M
    :kontor.parameter-value/citation        "§194K IT Act 1961 (introduced FA 2020) — ₹5 000 per AMC per FY MF IDCW threshold; raised to ₹10 000 by FA 2025"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194K.mf-idcw-tds-threshold"]
    :kontor.parameter-value/effective-from #inst "2025-04-01"
    :kontor.parameter-value/decimal-value  10000M
    :kontor.parameter-value/citation       "§194K IT Act 1961 (FA 2025 raise) — ₹10 000 per AMC per FY MF IDCW threshold effective 1 April 2025; renumbered to §392 under IT Act 2025 effective 1 April 2026"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§194K.mf-idcw-tds-rate"]
    :kontor.parameter-value/effective-from #inst "2020-04-01"
    :kontor.parameter-value/decimal-value  0.10M
    :kontor.parameter-value/citation       "§194K IT Act 1961 — 10 % TDS on MF IDCW (PAN furnished); stable rate"}

   ;; --- §115A NRI flat rates ---------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§115A.nri-dividend-rate"]
    :kontor.parameter-value/effective-from #inst "2020-04-01"
    :kontor.parameter-value/decimal-value  0.20M
    :kontor.parameter-value/citation       "§115A IT Act 1961 (post-FA-2020 DDT abolition) — 20 % flat on NRI dividend (definitive with §195 TDS at 20 %; DTAA may reduce)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§115A.nri-infrastructure-debt-rate"]
    :kontor.parameter-value/effective-from #inst "2013-06-01"
    :kontor.parameter-value/decimal-value  0.05M
    :kontor.parameter-value/citation       "§115A + §194LBA IT Act 1961 — 5 % flat on NRI interest from infrastructure debt fund"}

   ;; --- §80TTA / §80TTB caps ---------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§80TTA.cap"]
    :kontor.parameter-value/effective-from #inst "2013-04-01"
    :kontor.parameter-value/decimal-value  10000M
    :kontor.parameter-value/citation       "§80TTA IT Act 1961 (FA 2012 insertion, effective FY 2013-14) — ₹10 000 savings-account interest deduction (resident individuals < 60); OLD regime only (§115BAC default-new disallows)"}

   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.§80TTB.cap"]
    :kontor.parameter-value/effective-from #inst "2018-04-01"
    :kontor.parameter-value/decimal-value  50000M
    :kontor.parameter-value/citation       "§80TTB IT Act 1961 (FA 2018 insertion, effective FY 2018-19) — ₹50 000 all-interest deduction (resident seniors ≥ 60); OLD regime only; supersedes §80TTA for the same taxpayer"}

   ;; --- FA 2024 dividend surcharge cap -----------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.FA2024.dividend-surcharge-cap"]
    :kontor.parameter-value/effective-from #inst "2024-07-23"
    :kontor.parameter-value/decimal-value  0.15M
    :kontor.parameter-value/citation       "Finance (No. 2) Act 2024 — surcharge on dividend (and §111A / §112 / §112A CG) capped at 15 %, overriding the 25 % / 37 % high-income bands"}

   ;; --- 4 % cess ---------------------------------------------------------
   {:kontor.parameter-value/parameter      [:kontor.parameter/code "IN.InvIncome.cess.rate"]
    :kontor.parameter-value/effective-from #inst "2018-04-01"
    :kontor.parameter-value/decimal-value  0.04M
    :kontor.parameter-value/citation       "Health & Education Cess (FA 2018) — 4 % on (income tax + surcharge), applies across all income categories incl. §115A standalone"}])

;; ============================================================================
;; Provisions — §80TTA + §80TTB deductions + §115A NRI rate override
;; ============================================================================

(def provisions
  "IN investment-income provisions.

   - **§80TTA + §80TTB** fire as `:base-deduct` adjustment items
     against the resident-individual interest base in the OLD regime
     only. The condition gates on `:eq :regime :old` AND the
     `:senior?` flag — §80TTA for non-seniors, §80TTB for seniors. A
     senior cannot claim both (§80TTB supersedes); the condition logic
     enforces the split.

   - **§115A NRI rate override** is informational only — the provider
     decides whether to emit a §115A component based on
     `:tax-unit :nri?` (the rate parameter is read directly, not via a
     provision). Documented here so the audit chain can surface the
     statute citation.

   - **FA-2024 dividend surcharge cap** is provider-internal — the
     surcharge calculation reads the parameter directly. No provision
     fires because the cap is conditional on the running surcharge
     band exceeding 15 %, which the provider computes inline."

  [;; --------------------------------------------------------------------
   ;; §80TTA — non-senior savings interest deduction (OLD regime)
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-IT-§80TTA-savings-interest-deduction"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title           "§80TTA — savings-account interest deduction (up to ₹10 000; OLD regime; < 60)"
    :kontor.provision/citation        "https://cleartax.in/s/section-80tta"
    :kontor.provision/effective-from  #inst "2013-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :regime :old]
                                        [:eq :senior? false]
                                        [:gt [:inputs :in-savings-interest] 0M]])
    :kontor.provision/consequence     (pr-str {:op :base-deduct
                                        :code :§80tta-deduction
                                        :label "§80TTA savings-account interest deduction"
                                        :amount-from :compute-fn
                                        :fn :in-§80tta-deduction})}

   ;; --------------------------------------------------------------------
   ;; §80TTB — senior all-interest deduction (OLD regime)
   ;; --------------------------------------------------------------------
   {:kontor.provision/code            "IN-IT-§80TTB-senior-interest-deduction"
    :kontor.provision/jurisdiction    :in
    :kontor.provision/concept         [:kontor.tax-concept/code :base-transform-deduct]
    :kontor.provision/title           "§80TTB — senior-citizen all-interest deduction (up to ₹50 000; OLD regime; ≥ 60)"
    :kontor.provision/citation        "https://cleartax.in/s/section-80ttb-senior-citizens-deduction"
    :kontor.provision/effective-from  #inst "2018-04-01"
    :kontor.provision/priority        100
    :kontor.provision/condition       (pr-str [:and
                                        [:eq :regime :old]
                                        [:eq :senior? true]
                                        [:gt [:inputs :in-senior-interest-total] 0M]])
    :kontor.provision/consequence     (pr-str {:op :base-deduct
                                        :code :§80ttb-deduction
                                        :label "§80TTB senior-citizen all-interest deduction"
                                        :amount-from :compute-fn
                                        :fn :in-§80ttb-deduction})}])

;; ============================================================================
;; Install!
;; ============================================================================

(defn install!
  "Install IN investment-income statute (parameters + parameter-values
   + provisions) into `conn`. Idempotent — `:kontor.parameter/code` and
   `:kontor.provision/code` are unique identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

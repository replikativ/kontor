(ns kontor.l10n-us.cgt-statute
  "US capital-gains tax — Form 8949 / Schedule D — encoded as
   `kontor.statute` data per ADR-101. Research note 112.

   Five jurisdictional ingredients enter the statute, all date-keyed
   so a future TCJA-style rate change is a one-row migration:

   - **Holding-period cutoff** — IRC §1222 — exactly 1 year (>365 days
     LT, ≤365 days ST).
   - **LT capital-gains bracket** — IRC §1(h) — the 0 % / 15 % / 20 %
     stack for individuals; threshold tied to taxable-income bracket
     (depends on filing status — handled at the provider via
     ctx `:tax-unit :filing-status`).
   - **§1250 unrecaptured-gain cap** — IRC §1(h)(6) — 25 % cap on the
     unrecaptured depreciation portion of real-property gain (individuals
     only).
   - **§1411 NIIT** — 3.8 % on net investment income above MAGI
     thresholds ($200k single / $250k MFJ / $125k MFS).
   - **§1212 corporate capital-loss carry** — 3 years back, 5 forward
     (corporations only; individuals carry forward indefinitely with
     a $3k/yr ordinary-income offset cap per §1211(b)).

   The five specialist provisions named in note 112 §4 (§1031 / §1202 /
   §121 / §453 / §1091) are DEFERRED — the disposal schema already has
   the data slots (`:elective-regime` / `:exemption-claimed` /
   `:rollover-into-asset`) but the v1 provider does NOT compute them;
   the consumer either flags eligibility upstream or ships out-of-band.

   Citations point at law.cornell.edu's Title 26 mirror (a stable,
   public-domain US Code source); IRS forms / pubs are referenced for
   the worked-example mechanics."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Parameters — date-keyed value history (ADR-101 :parameter)
;; ============================================================================

(def parameters
  "US CGT parameter definitions. Values live in `parameter-values`,
   keyed by `:effective-from`."
  [;; --- Holding-period cutoff -----------------------------------------------
   {:parameter/code         "US.CGT.holding-period-cutoff-days"
    :parameter/label        "Short vs long-term cutoff (IRC §1222: 1 year, exclusive)"
    :parameter/jurisdiction :us
    :parameter/unit         :days
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1222"}

   ;; --- LT brackets (§1(h)) — individual ------------------------------------
   ;; Brackets shipped as three parameters per filing status (a single
   ;; `:progressive-bracket` schedule needs the threshold-and-rate
   ;; vectors). For 2026 (IRS Rev. Proc. 2025-32 inflation indexing).
   {:parameter/code         "US.CGT.LT.threshold-single-0to15"
    :parameter/label        "Single — 0 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us
    :parameter/unit         :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-single-15to20"
    :parameter/label        "Single — 15 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-mfj-0to15"
    :parameter/label        "MFJ — 0 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-mfj-15to20"
    :parameter/label        "MFJ — 15 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-mfs-0to15"
    :parameter/label        "MFS — 0 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-mfs-15to20"
    :parameter/label        "MFS — 15 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-hoh-0to15"
    :parameter/label        "HoH — 0 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.threshold-hoh-15to20"
    :parameter/label        "HoH — 15 % bracket ceiling (LT cap gain)"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}

   {:parameter/code         "US.CGT.LT.rate-0"
    :parameter/label        "Long-term capital gain — 0 % rate"
    :parameter/jurisdiction :us :parameter/unit :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.rate-15"
    :parameter/label        "Long-term capital gain — 15 % rate"
    :parameter/jurisdiction :us :parameter/unit :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}
   {:parameter/code         "US.CGT.LT.rate-20"
    :parameter/label        "Long-term capital gain — 20 % rate"
    :parameter/jurisdiction :us :parameter/unit :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h"}

   ;; --- §1250 unrecaptured cap (25 %) ---------------------------------------
   {:parameter/code         "US.CGT.§1250.rate"
    :parameter/label        "§1250 unrecaptured-gain cap (real property depreciation, individuals)"
    :parameter/jurisdiction :us :parameter/unit :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1#h-6"}

   ;; --- §1411 NIIT -----------------------------------------------------------
   {:parameter/code         "US.CGT.§1411.rate"
    :parameter/label        "Net Investment Income Tax (NIIT) rate"
    :parameter/jurisdiction :us :parameter/unit :rate
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1411"}
   {:parameter/code         "US.CGT.§1411.threshold-single"
    :parameter/label        "NIIT MAGI threshold — Single / HoH"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1411"}
   {:parameter/code         "US.CGT.§1411.threshold-mfj"
    :parameter/label        "NIIT MAGI threshold — MFJ"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1411"}
   {:parameter/code         "US.CGT.§1411.threshold-mfs"
    :parameter/label        "NIIT MAGI threshold — MFS"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1411"}

   ;; --- §1211(b) individual capital-loss-against-ordinary cap ---------------
   {:parameter/code         "US.CGT.§1211b.ordinary-offset-cap"
    :parameter/label        "Annual cap on individual capital-loss offset against ordinary income"
    :parameter/jurisdiction :us :parameter/unit :amount-money
    :parameter/concept-iri  "https://www.law.cornell.edu/uscode/text/26/1211"}])

;; ============================================================================
;; Parameter values — current rates with their statutory effective windows
;; ============================================================================

(def parameter-values
  "US CGT parameter values. 2026 values per IRS Rev. Proc. 2025-32
   inflation indexing (TY 2026)."
  [{:parameter-value/parameter      [:parameter/code "US.CGT.holding-period-cutoff-days"]
    :parameter-value/effective-from #inst "1942-01-01"
    :parameter-value/decimal-value  365M
    :parameter-value/citation       "IRC §1222 — long-term requires >1 year holding"}

   ;; LT bracket thresholds — TY 2026 (Rev. Proc. 2025-32).
   ;; These are the TAXABLE-INCOME thresholds at which a taxpayer
   ;; crosses out of the 0 % cap-gain bracket / out of 15 % into 20 %.
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-single-0to15"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  49450M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-single-15to20"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  545500M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 §3.03 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-mfj-0to15"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  98900M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-mfj-15to20"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  613700M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 §3.03 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-mfs-0to15"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  49450M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-mfs-15to20"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  306850M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 §3.03 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-hoh-0to15"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  66200M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 TY 2026"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.threshold-hoh-15to20"]
    :parameter-value/effective-from #inst "2026-01-01"
    :parameter-value/decimal-value  579600M
    :parameter-value/citation       "IRS Rev. Proc. 2025-32 §3.03 TY 2026"}

   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.rate-0"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  0.00M
    :parameter-value/citation       "IRC §1(h)(1)(B) — 0 % rate stable since ATRA 2012"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.rate-15"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  0.15M
    :parameter-value/citation       "IRC §1(h)(1)(C) — 15 % rate stable since ATRA 2012"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.LT.rate-20"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  0.20M
    :parameter-value/citation       "IRC §1(h)(1)(D) — 20 % rate stable since ATRA 2012"}

   {:parameter-value/parameter      [:parameter/code "US.CGT.§1250.rate"]
    :parameter-value/effective-from #inst "1997-08-05"
    :parameter-value/decimal-value  0.25M
    :parameter-value/citation       "IRC §1(h)(6) — 25 % cap (TRA 1997)"}

   {:parameter-value/parameter      [:parameter/code "US.CGT.§1411.rate"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  0.038M
    :parameter-value/citation       "IRC §1411(a) — 3.8 % surtax (ACA 2013)"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.§1411.threshold-single"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  200000M
    :parameter-value/citation       "IRC §1411(b)(3) — Single / HoH threshold (NOT indexed)"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.§1411.threshold-mfj"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  250000M
    :parameter-value/citation       "IRC §1411(b)(1) — MFJ threshold (NOT indexed)"}
   {:parameter-value/parameter      [:parameter/code "US.CGT.§1411.threshold-mfs"]
    :parameter-value/effective-from #inst "2013-01-01"
    :parameter-value/decimal-value  125000M
    :parameter-value/citation       "IRC §1411(b)(2) — MFS threshold (NOT indexed)"}

   {:parameter-value/parameter      [:parameter/code "US.CGT.§1211b.ordinary-offset-cap"]
    :parameter-value/effective-from #inst "1977-01-01"
    :parameter-value/decimal-value  3000M
    :parameter-value/citation       "IRC §1211(b) — $3 000 annual cap (frozen since TRA 1976)"}])

;; ============================================================================
;; Provisions — corp + individual lane classification rules
;; ============================================================================

(def provisions
  "US CGT provisions encoded for the `kontor.statute` evaluator.

   Most US CGT logic lives in the PROVIDER (loss netting within lanes,
   carry-in application, schedule selection by filing status) — these
   provisions cover the ADR-101-shaped rules: NIIT as a surtax on net
   investment income for individuals.

   The §1250 / LT / ST classification is done by the provider on the
   disposal stream — these provisions wire the surtax pieces."

  [;; --------------------------------------------------------------------
   ;; §1411 NIIT — 3.8 % on individual net investment income above MAGI
   ;; threshold. A `:surtax` on the standalone-CGT components.
   ;; --------------------------------------------------------------------
   {:provision/code            "US-IRC-§1411-NIIT"
    :provision/jurisdiction    :us
    :provision/concept         [:kontor.tax-concept/code :surtax]
    :provision/title           "§1411 — Net Investment Income Tax (3.8 % surtax)"
    :provision/citation        "https://www.law.cornell.edu/uscode/text/26/1411"
    :provision/effective-from  #inst "2013-01-01"
    :provision/priority        100
    :provision/condition       (pr-str [:and
                                        [:eq :kind :individual]
                                        [:gt [:inputs :net-investment-income] 0M]])
    :provision/consequence     (pr-str {:op :surtax
                                        :code :niit
                                        :label "§1411 Net Investment Income Tax (3.8 %)"
                                        :amount-from :compute-fn
                                        :fn :us-niit})}])

;; ============================================================================
;; Install! — transact parameters + provisions
;; ============================================================================

(defn install!
  "Install US CGT statute (parameters + provisions) into `conn`.
   Idempotent — `:parameter/code` and `:provision/code` are unique
   identity attrs."
  [conn]
  (d/transact conn parameters)
  (d/transact conn parameter-values)
  (d/transact conn provisions))

(ns kontor.l10n-jp.cgt-provider-test
  "Tests for the JP CGT provider (ADR-102 + ADR-101, research note 115).

   The shape mirrors the US CGT test suite — fixture + per-§ regime
   coverage — but the substrate stress sits in different places:

   - The Jan-1 measurement rule (note 115 §1.2 / §3.1) — the JP-unique
     design feature.
   - The five separate-taxation compartments (note 115 §1.1).
   - The §35 ¥30M residence deduction (note 115 §1.5).
   - The §31-3 progressive 10 % / 15 % residence preferential.
   - The 復興特別所得税 2.1 % surtax on the NATIONAL slice only.
   - The two §2 worked examples — Mr Sato (short-term) and Mr Takahashi
     (long-residence with both reliefs)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-jp.cgt-provider :as jp-cgt]
            [kontor.l10n-jp.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, JP CGT statute, a JPY
   commodity, and one JP-CO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "JPY" :kontor.commodity/name "Japanese Yen"
                       :kontor.commodity/precision 0}
                      {:kontor.entity/code "JP-CO" :kontor.entity/name "JP Co"
                       :kontor.entity/kind :company :kontor.entity/country "JP"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "JPY"]}])
    conn))

(def ^:private jpy [:kontor.commodity/symbol "JPY"])
(def ^:private jp-co [:kontor.entity/code "JP-CO"])

(defn- record!
  "Record a minimal JP disposal."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          jp-co
                :kind            :sale
                :subject         jpy
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity jpy}
                :basis           {:amount 0M :commodity jpy}}
               opts)))

(defn- jp-co-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "JP-CO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting
   facts."
  [conn kind period & [extra-ctx]]
  (let [source (disp-source/datahike-source conn)
        provider (case kind
                   :individual  (jp-cgt/jp-individual-cgt-provider {:source source})
                   :corporation (jp-cgt/jp-corporate-cgt-provider  {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (jp-co-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- component-by-regime
  "Find the first component whose `:regime` matches the given keyword."
  [facts regime]
  (->> (:components facts)
       (filter #(= regime (:regime %)))
       first))

;; ============================================================================
;; §1. Plumbing — empty source, kind validation, jan-1 helper
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn :individual p2026)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn (fresh)
          source (disp-source/datahike-source conn)
          bad (jp-cgt/->JpCapitalGainsTaxProvider :bogus source :jp-nta :JPY "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn)
                                  :entity (jp-co-eid conn)
                                  :period p2026}))))))

(deftest jan-1-helper-direct
  (testing "boundary case from the task prompt — acquired 2020-12-15, disposed 2026-02-01"
    (is (= 6 (jp-cgt/jan-1-elapsed-years #inst "2020-12-15" #inst "2026-02-01"))
        "6 calendar-year boundaries crossed = LONG (>5)"))
  (testing "edge: acquired 2021-01-02, disposed 2026-12-30 — 5 elapsed years (NOT long)"
    (is (= 5 (jp-cgt/jan-1-elapsed-years #inst "2021-01-02" #inst "2026-12-30"))))
  (testing "edge: acquired 2015-06-01, disposed 2026-05-01 — 11 elapsed years (long-residence eligible)"
    (is (= 11 (jp-cgt/jan-1-elapsed-years #inst "2015-06-01" #inst "2026-05-01")))))

;; ============================================================================
;; §2. Listed securities — 20.315 % effective
;; ============================================================================

(deftest listed-securities-flat-20-315pct
  (testing "a listed-share gain of ¥10,000,000 → ¥1,500,000 nat + ¥31,500 復興 + ¥500,000 local = ¥2,031,500"
    (let [conn (fresh)]
      (record! conn {:external-id "listed-1"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 15000000M :commodity jpy}
                     :basis    {:amount 5000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            lsec  (component-by-regime facts :jp-listed-securities)]
        (is (some? lsec) "listed component exists")
        (is (== 10000000M (-> lsec :base :amount)))
        ;; 10M × 15% = 1,500,000; surtax = 1,500,000 × 2.1% = 31,500;
        ;; 10M × 5% = 500,000; total = 2,031,500
        (is (== 2031500M (-> lsec :liability :amount)))
        (is (= :jp-listed-securities (:regime lsec)))))))

(deftest listed-securities-loss-carryforward-3-year
  (testing "listed compartment accepts a carry-in loss"
    (let [conn (fresh)]
      (record! conn {:external-id "listed-cf"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 8000000M :commodity jpy}
                     :basis    {:amount 3000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:capital-loss-carryforward
                                          {:jp-listed-securities 2000000M}}})
            lsec  (component-by-regime facts :jp-listed-securities)]
        (is (== 3000000M (-> lsec :base :amount))
            "¥5M gain − ¥2M carry-in = ¥3M taxable")
        ;; 3M × 15% = 450,000; +2.1% = 459,450; +5% × 3M = 150,000 → 609,450
        (is (== 609450M (-> lsec :liability :amount)))))))

;; ============================================================================
;; §3. Unlisted equity — same 20.315 %, separate bucket
;; ============================================================================

(deftest unlisted-equity-flat-20-315pct
  (testing "unlisted equity at ¥5,000,000 gain → 20.315 % = ¥1,015,750"
    (let [conn (fresh)]
      (record! conn {:external-id "unlisted-1"
                     :asset-class :jp-unlisted-equity
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 12000000M :commodity jpy}
                     :basis    {:amount 7000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            ue    (component-by-regime facts :jp-unlisted-equity)]
        (is (some? ue))
        (is (== 5000000M (-> ue :base :amount)))
        ;; 5M × 15% = 750,000; +2.1% = 765,750; +5% × 5M = 250,000 → 1,015,750
        (is (== 1015750M (-> ue :liability :amount)))))))

(deftest listed-and-unlisted-do-not-cross-offset
  (testing "a listed carry-in does NOT reduce unlisted gain"
    (let [conn (fresh)]
      (record! conn {:external-id "u-1"
                     :asset-class :jp-unlisted-equity
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 12000000M :commodity jpy}
                     :basis    {:amount 7000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:capital-loss-carryforward
                                          {:jp-listed-securities 999999M}}})
            ue    (component-by-regime facts :jp-unlisted-equity)]
        ;; The listed carry-in is in :jp-listed-securities bucket only.
        (is (== 5000000M (-> ue :base :amount))
            "unlisted base unchanged by listed carry-in")))))

;; ============================================================================
;; §4. Real estate — short vs long via Jan-1 measurement rule
;; ============================================================================

(deftest realestate-short-39-63pct
  (testing "real estate held ≤5 yrs (Jan-1) → 39.63 % effective"
    (let [conn (fresh)]
      (record! conn {:external-id "re-short"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2024-06-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 50000000M :commodity jpy}
                     :basis    {:amount 40000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-short)]
        (is (some? re) "short-term component present")
        (is (== 10000000M (-> re :base :amount)))
        ;; 10M × 30% = 3,000,000; +2.1% = 3,063,000; +9% × 10M = 900,000 → 3,963,000
        (is (== 3963000M (-> re :liability :amount)))))))

(deftest realestate-long-20-315pct
  (testing "real estate held >5 yrs (Jan-1) → 20.315 % effective"
    (let [conn (fresh)]
      (record! conn {:external-id "re-long"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 80000000M :commodity jpy}
                     :basis    {:amount 60000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long)]
        (is (some? re))
        (is (== 20000000M (-> re :base :amount)))
        ;; 20M × 15% = 3,000,000; +2.1% = 3,063,000; +5% × 20M = 1,000,000 → 4,063,000
        (is (== 4063000M (-> re :liability :amount)))))))

(deftest jan-1-boundary-classifies-long
  (testing "acquired 2020-12-15, disposed 2026-02-01 → Jan-1 measurement = 6 → LONG"
    (let [conn (fresh)]
      (record! conn {:external-id "boundary-1"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2020-12-15"
                     :disposed-on #inst "2026-02-01"
                     :proceeds {:amount 50000000M :commodity jpy}
                     :basis    {:amount 40000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            long  (component-by-regime facts :jp-real-estate-long)
            short (component-by-regime facts :jp-real-estate-short)]
        (is (some? long)  "classifies as :long")
        (is (nil? short)  "does NOT classify as :short")
        (is (== 10000000M (-> long :base :amount)))))))

(deftest jan-1-edge-short-five-years-flat
  (testing "acquired 2021-01-02, disposed 2026-12-30 → Jan-1 measurement = 5 → NOT long (>5)"
    (let [conn (fresh)]
      (record! conn {:external-id "edge-five"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2021-01-02"
                     :disposed-on #inst "2026-12-30"
                     :proceeds {:amount 30000000M :commodity jpy}
                     :basis    {:amount 20000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)]
        (is (some? (component-by-regime facts :jp-real-estate-short))
            "5 elapsed years is NOT >5 → short-term")
        (is (nil? (component-by-regime facts :jp-real-estate-long)))))))

;; ============================================================================
;; §5. §35 ¥30M residence deduction — base deduction before the rate
;; ============================================================================

(deftest §35-30m-deduction-applies-to-plain-long
  (testing "§35 deduction reduces base on plain :long (no §31-3 election) — note 140 P0-1 fix"
    (let [conn (fresh)]
      (record! conn {:external-id "§35-plain-long"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2018-06-01"  ; long but <10y from disposal year start
                     :disposed-on #inst "2026-05-01"
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 100000000M :commodity jpy}
                     :basis    {:amount 50000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long)]
        (is (some? re) "classifies as long-term (8 elapsed years)")
        ;; Per NTA タックスアンサー No.3302 + 措置法通達 35-2 / 35-6, the §35
        ;; ¥30M deduction applies to ANY residence sale regardless of
        ;; holding period. Gross gain ¥50M − ¥30M § 35 = ¥20M taxable base.
        (is (== 20000000M (-> re :base :amount))
            "§35 deduction DOES apply on plain :long when :residence? true + claim stamped")
        ;; 20M × 15% = 3,000,000 national; +2.1% = 3,063,000; +5% × 20M = 1,000,000 → 4,063,000
        (is (== 4063000M (-> re :liability :amount))
            "plain-long rate (20.315%) applied to post-§35 base ¥20M")))))

(deftest §35-deduction-applies-to-short-term
  (testing "§35 + §32 short-term residence (sub-5-year) — note 140 P0-1"
    (let [conn (fresh)]
      (record! conn {:external-id "§35-short"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2023-03-01"  ; ~3 elapsed years → SHORT
                     :disposed-on #inst "2026-04-01"
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 80000000M :commodity jpy}
                     :basis    {:amount 30000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-short)]
        (is (some? re) "classifies as short-term (3 elapsed years)")
        ;; Per NTA No.3302 — §35 has NO holding-period requirement; the
        ;; short-term §32 lane (39.63 %) DOES allow the ¥30M deduction.
        ;; Gross gain ¥50M − ¥30M § 35 = ¥20M taxable base.
        (is (== 20000000M (-> re :base :amount))
            "§35 deduction DOES apply on §32 short-term residence")
        ;; 20M × 30% = 6,000,000 national; +2.1% = 6,126,000;
        ;; +9% × 20M = 1,800,000 → 7,926,000
        (is (== 7926000M (-> re :liability :amount))
            "short-term rate (39.63%) applied to post-§35 base ¥20M")))))

(deftest §35-not-applied-without-residence-flag
  (testing "§35 is residence-only — a non-residence real-estate sale stamping §35 does not deduct"
    (let [conn (fresh)]
      (record! conn {:external-id "§35-non-residence"
                     :asset-class :jp-real-estate
                     :residence?  false   ; not a residence
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-05-01"
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 100000000M :commodity jpy}
                     :basis    {:amount 50000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long)]
        (is (some? re))
        ;; §35 must NOT apply — no `:kontor.disposal/residence? true` flag.
        ;; Defensive: silently drop the claim rather than reward bad data.
        (is (== 50000000M (-> re :base :amount))
            "§35 deduction NOT applied without :kontor.disposal/residence? true")))))

(deftest §35-deduction-applies-to-§31-3-lane
  (testing "§35 + §31-3 election → deduction subtracts before §31-3 rate"
    (let [conn (fresh)]
      (record! conn {:external-id "§35-with-§31-3"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2013-06-01"
                     :disposed-on #inst "2026-05-01"
                     :elective-regime   #{:jp-§31-3}
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 100000000M :commodity jpy}
                     :basis    {:amount 50000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long-residence)]
        (is (some? re) "classifies as long-residence (13 yrs + residence + §31-3)")
        ;; ¥50M gross − ¥30M §35 = ¥20M base
        (is (== 20000000M (-> re :base :amount)))))))

;; ============================================================================
;; §6. §31-3 progressive 10 % / 15 % residence preferential
;; ============================================================================

(deftest §31-3-below-60m-uses-low-bracket
  (testing "§31-3 base ≤¥60M → 10 % national + 0.21 % surtax + 4 % local = 14.21 %"
    (let [conn (fresh)]
      (record! conn {:external-id "§31-3-low"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2013-06-01"
                     :disposed-on #inst "2026-05-01"
                     :elective-regime   #{:jp-§31-3}
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 145000000M :commodity jpy}
                     :basis    {:amount 90000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long-residence)]
        ;; Gross ¥55M; §35 → ¥25M base; ≤ ¥60M; 10 %+0.21 %+4 % = 14.21 %
        ;; Mr Takahashi worked example (note 115 §2 example B):
        ;; ¥25,000,000 × 14.21 % = ¥3,552,500
        (is (== 25000000M (-> re :base :amount)))
        (is (== 3552500M (-> re :liability :amount))
            "Mr Takahashi worked example — note 115 §2.2")))))

(deftest §31-3-above-60m-uses-progressive
  (testing "§31-3 base >¥60M — first ¥60M at 14.21 %, slice above at 20.315 %"
    (let [conn (fresh)]
      (record! conn {:external-id "§31-3-high"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2010-06-01"
                     :disposed-on #inst "2026-05-01"
                     :elective-regime   #{:jp-§31-3}
                     :proceeds {:amount 200000000M :commodity jpy}
                     :basis    {:amount 100000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long-residence)]
        ;; Gross ¥100M (no §35 claimed). §31-3 base = ¥100M.
        ;; National: 60M × 10% + 40M × 15% = 6,000,000 + 6,000,000 = 12,000,000
        ;; Surtax: 12,000,000 × 2.1% = 252,000
        ;; Local: 60M × 4% + 40M × 5% = 2,400,000 + 2,000,000 = 4,400,000
        ;; Total: 12,000,000 + 252,000 + 4,400,000 = 16,652,000
        (is (== 100000000M (-> re :base :amount)))
        (is (== 16652000M (-> re :liability :amount)))))))

;; ============================================================================
;; §7. Worked examples from note 115 §2
;; ============================================================================

(deftest worked-example-mr-sato-short-term-real-estate
  (testing "note 115 §2.1 — Mr Sato: 2022-03 buy / 2026-04 sell short-term"
    (let [conn (fresh)]
      (record! conn {:external-id "sato"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2022-03-15"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 78500000M :commodity jpy}
                     :basis    {:amount 65000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-short)]
        ;; (2026 − 2022) = 4 elapsed years → short-term
        ;; Gain = ¥13,500,000; rate 39.63 %; tax = ¥5,350,050
        (is (some? re) "short-term as expected (4 elapsed years)")
        (is (== 13500000M (-> re :base :amount)))
        (is (== 5350050M (-> re :liability :amount))
            "Mr Sato worked example — note 115 §2.1")))))

(deftest worked-example-mr-takahashi-long-residence-with-§35
  (testing "note 115 §2.2 — Mr Takahashi: 2013-06 buy / 2026-05 sell, §35 + §31-3"
    (let [conn (fresh)]
      (record! conn {:external-id "takahashi"
                     :asset-class :jp-real-estate
                     :residence?  true
                     :acquired-on #inst "2013-06-01"
                     :disposed-on #inst "2026-05-01"
                     :elective-regime   #{:jp-§31-3}
                     :exemption-claimed #{:jp-§35-residence}
                     :proceeds {:amount 145000000M :commodity jpy}
                     :basis    {:amount 90000000M  :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long-residence)]
        ;; (2026 − 2013) = 13 elapsed years > 10 → long-residence
        ;; Gross ¥55M; §35 → ¥25M; ≤¥60M; 14.21 % → ¥3,552,500
        (is (some? re))
        (is (== 25000000M (-> re :base :amount)))
        (is (== 3552500M (-> re :liability :amount)))))))

;; ============================================================================
;; §8. Corporation — gains fold into CIT
;; ============================================================================

(deftest corporate-folds-into-cit-base
  (testing "corp gains → :cit-base-additions, no separate JP corporate CGT regime"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-1"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 30000000M :commodity jpy}
                     :basis    {:amount 10000000M :commodity jpy}})
      (let [facts (run-provider conn :corporation p2026)
            corp  (->> (:components facts)
                       (filter #(= :jp-corporate (get-in % [:jurisdiction-specific-codes :regime])))
                       first)]
        (is (some? corp))
        (is (== 20000000M (-> corp :base :amount)))
        (is (== 0M (-> corp :liability :amount))
            "corporate component has no standalone CGT liability")
        (is (= [20000000M]
               (get-in corp [:jurisdiction-specific-codes :cit-base-additions])))))))

;; ============================================================================
;; §9. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 50000000M :commodity jpy}
                     :basis    {:amount 10000000M :commodity jpy}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2026)]
        (is (empty? (:components facts)))))))

;; ============================================================================
;; §10. Multi-regime fan-out — listed + real-estate-long in one period
;; ============================================================================

(deftest multi-regime-fan-out
  (testing "two disposals in different regimes → two components, watertight"
    (let [conn (fresh)]
      (record! conn {:external-id "m-listed"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 15000000M :commodity jpy}
                     :basis    {:amount 5000000M  :commodity jpy}})
      (record! conn {:external-id "m-realong"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-05-01"
                     :proceeds {:amount 80000000M :commodity jpy}
                     :basis    {:amount 60000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            comps (:components facts)
            lsec  (component-by-regime facts :jp-listed-securities)
            re-l  (component-by-regime facts :jp-real-estate-long)]
        (is (= 2 (count comps)))
        (is (some? lsec))
        (is (some? re-l))
        (is (== 10000000M (-> lsec :base :amount)))
        (is (== 20000000M (-> re-l :base :amount)))
        ;; Each component carries its own surtax line item
        (is (every? (fn [c]
                      (some #(= :reconstruction-surtax (:code %))
                            (:surtaxes c)))
                    comps)
            "every standalone-CGT component carries a 復興 surtax line")))))

;; ============================================================================
;; §11. Reconstruction surtax — sits on national only
;; ============================================================================

(deftest reconstruction-surtax-on-national-only
  (testing "the 復興 surtax fires on the national rate, NOT on the inhabitants tax"
    (let [conn (fresh)]
      (record! conn {:external-id "fukko-1"
                     :asset-class :jp-listed-securities
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 20000000M :commodity jpy}
                     :basis    {:amount 10000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            lsec  (component-by-regime facts :jp-listed-securities)
            surtax-line (->> lsec :line-items
                             (filter #(= :reconstruction-surtax (:line %)))
                             first)
            surtax-item (->> lsec :surtaxes
                             (filter #(= :reconstruction-surtax (:code %)))
                             first)]
        ;; 10M × 15% × 2.1% = 31,500
        (is (some? surtax-item))
        (is (== 31500M (:amount surtax-item)))
        (is (some? surtax-line))
        (is (== 31500M (-> surtax-line :value :amount)))))))

;; ============================================================================
;; §12. Audit trail — provenance + provisions-applied
;; ============================================================================

(deftest provenance-records-applied-provisions
  (testing "each standalone-CGT component records the JP-FUKKO provision in :provenance"
    (let [conn (fresh)]
      (record! conn {:external-id "prov-1"
                     :asset-class :jp-real-estate
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-05-01"
                     :proceeds {:amount 50000000M :commodity jpy}
                     :basis    {:amount 30000000M :commodity jpy}})
      (let [facts (run-provider conn :individual p2026)
            re    (component-by-regime facts :jp-real-estate-long)]
        (is (= [:jp-cgt] [(get-in re [:provenance :provider-id])]))
        (is (contains? (set (get-in re [:provenance :provisions-applied]))
                       "JP-FUKKO-§13-reconstruction-surtax"))))))

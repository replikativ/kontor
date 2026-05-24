(ns kontor.l10n-uk.cgt-provider-test
  "Tests for the UK CGT providers (ADR-102 + ADR-101, research note 114).

   Notable worked examples covered:
   - §2.1 — Jane sells JaneCo (BADR-qualifying) for £1.4M → £195,256 CGT
     (£3k AEA, £1M @ 10 % BADR, £396,900 @ 24 % standard).
   - §2.2 corp adaptation — ABC Ltd indexed-basis disposal feeding CT
     base (no liability at this layer)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-uk.cgt-provider :as uk-cgt]
            [kontor.l10n-uk.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with disposal companion, UK CGT statute, GBP commodity,
   and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:commodity/symbol "GBP" :commodity/name "Pound sterling"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "GB"
                       :entity/functional-commodity [:commodity/symbol "GBP"]}])
    conn))

(def ^:private gbp [:commodity/symbol "GBP"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         gbp                       ; placeholder ref
                :subject-kind    :participation
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity gbp}
                :basis           {:amount 0M :commodity gbp}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn kind period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (case kind
                   :individual  (uk-cgt/uk-individual-cgt-provider {:source source})
                   :corporation (uk-cgt/uk-corporate-cgt-provider  {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

;; All in TY 2024/25 post Autumn-Budget (rates: std 18/24, residential 18/24,
;; BADR 10 % through 5 Apr 2025, AEA £3,000). Period `:to` deliberately
;; placed BEFORE 2025-04-06 so `as-of` reads the 2024-25 BADR rate
;; (the parameter-value windows use `[from, until)` half-open semantics
;; — landing on 2025-04-06 would pick up the 14 % 2025-26 rate).
(def ^:private p2024-25
  {:from #inst "2024-11-01" :to #inst "2025-04-05"})

(defn- component-by-lane
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn :individual p2024-25)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn (fresh)
          source (disp-source/datahike-source conn)
          bad (uk-cgt/->UKCapitalGainsTaxProvider :bogus source :uk-hmrc :GBP "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :entity (holdco-eid conn) :period p2024-25}))))))

;; ============================================================================
;; §2. Worked example — note 114 §2.1 Jane's BADR sale
;; ============================================================================

(deftest note114-§2-1-jane-badr-sale
  (testing "Jane sells 100% of JaneCo for £1.4M (post-Oct-2024) — £195,256 CGT"
    (let [conn (fresh)]
      (record! conn {:external-id "jane-janeco"
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-trading-company-shares
                     :exemption-claimed #{:uk-badr}
                     :proceeds {:amount 1400000M :commodity gbp}
                     :basis    {:amount 100M     :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher
                                            :badr-lifetime-claimed 0M}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        (is (some? cmp))
        ;; Total CGT = £100,000 (BADR) + £95,256 (standard) = £195,256.
        (is (== 195256M (-> cmp :liability :amount))
            "Total CGT matches note 114 §2.1 to the penny")
        (is (== 1000000M (-> cmp :jurisdiction-specific-codes :badr-claimed-this-period))
            "BADR slice exactly fills the £1M lifetime cap")
        (is (= :higher (:regime cmp)))))))

(deftest badr-lifetime-cap-fully-exhausted-stops-future-claims
  (testing "second BADR-flagged sale with full prior cap gets zero BADR rate"
    (let [conn (fresh)]
      (record! conn {:external-id "second-claim"
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2024-12-15"
                     :asset-class :uk-trading-company-shares
                     :exemption-claimed #{:uk-badr}
                     :proceeds {:amount 500000M :commodity gbp}
                     :basis    {:amount 0M      :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher
                                            ;; Lifetime cap already consumed.
                                            :badr-lifetime-claimed 1000000M}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        (is (== 0M (-> cmp :jurisdiction-specific-codes :badr-claimed-this-period))
            "no BADR slice available — fully consumed")
        ;; Whole gain (post AEA £3k = £497,000) goes to standard @ 24 % = £119,280.
        (is (== 119280M (-> cmp :liability :amount)))))))

;; ============================================================================
;; §3. Residential property — 24 % higher-rate (task spec §2.2)
;; ============================================================================

(deftest residential-property-at-higher-rate
  (testing "residential property sale at higher band → 24 %"
    (let [conn (fresh)]
      (record! conn {:external-id "house-1"
                     :acquired-on #inst "2015-01-01"
                     :disposed-on #inst "2024-11-15"
                     :asset-class :uk-residential-property
                     :proceeds {:amount 600000M :commodity gbp}
                     :basis    {:amount 350000M :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gain = 250k − AEA 3k = 247k; tax @ 24 % = 59,280.
        (is (some? cmp))
        (is (== 247000M (-> cmp :base :amount)))
        (is (== 59280M  (-> cmp :liability :amount)))))))

(deftest residential-property-basic-band-at-18pct
  (testing "residential property sale at basic band → 18 %"
    (let [conn (fresh)]
      (record! conn {:external-id "house-basic"
                     :acquired-on #inst "2015-01-01"
                     :disposed-on #inst "2024-11-15"
                     :asset-class :uk-residential-property
                     :proceeds {:amount 100000M :commodity gbp}
                     :basis    {:amount 50000M  :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :basic}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gain 50k − AEA 3k = 47k; @ 18 % = 8,460.
        (is (== 47000M (-> cmp :base :amount)))
        (is (== 8460M  (-> cmp :liability :amount)))))))

;; ============================================================================
;; §4. AEA — consumption + zero liability when below
;; ============================================================================

(deftest aea-consumes-small-gain-zero-tax
  (testing "small gain under AEA → zero tax (lane still emitted at zero base)"
    (let [conn (fresh)]
      (record! conn {:external-id "tiny"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-12-01"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 5000M :commodity gbp}
                     :basis    {:amount 2500M :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gain 2,500 < AEA 3,000 → zero base → no component.
        (is (nil? cmp) "no component when AEA wipes the gain")))))

(deftest aea-applied-to-highest-rate-slice-first
  (testing "AEA applied to standard slice (highest-rate-first ordering)"
    (let [conn (fresh)]
      ;; A BADR-eligible disposal AND a standard disposal in same period:
      ;; AEA should eat into the standard slice first (saves the
      ;; taxpayer 24 % on £3k vs 10 % on £3k).
      (record! conn {:external-id "badr-1"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-trading-company-shares
                     :exemption-claimed #{:uk-badr}
                     :proceeds {:amount 100000M :commodity gbp}
                     :basis    {:amount 0M      :commodity gbp}})
      (record! conn {:external-id "std-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 20000M :commodity gbp}
                     :basis    {:amount 5000M  :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher
                                            :badr-lifetime-claimed 0M}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Standard gain 15k − AEA 3k = 12k @ 24 % = 2,880.
        ;; BADR gain 100k @ 10 % = 10,000.
        ;; Total liability = 12,880.
        (is (== 12880M (-> cmp :liability :amount))
            "AEA absorbed by standard slice (£3k @ 24% saved); BADR slice untouched")))))

;; ============================================================================
;; §5. Loss bucket
;; ============================================================================

(deftest loss-carryforward-offsets-current-gain
  (testing "uk-capital carry-in loss offsets current-period gain"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-carry"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 60000M :commodity gbp}
                     :basis    {:amount 10000M :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher}
                                 :inputs {:capital-loss-carryforward
                                          {:uk-capital 30000M}}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gain 50k − loss 30k − AEA 3k = 17k @ 24 % = 4,080.
        (is (== 17000M (-> cmp :base :amount)))
        (is (== 4080M  (-> cmp :liability :amount)))))))

(deftest in-period-loss-nets-against-gains
  (testing "in-period loss nets against gains BEFORE rate application"
    (let [conn (fresh)]
      (record! conn {:external-id "gain-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 100000M :commodity gbp}
                     :basis    {:amount 30000M  :commodity gbp}})
      (record! conn {:external-id "loss-1"
                     :acquired-on #inst "2021-01-01"
                     :disposed-on #inst "2024-12-15"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 5000M  :commodity gbp}
                     :basis    {:amount 25000M :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gross gain 70k − in-period loss 20k − AEA 3k = 47k @ 24 % = 11,280.
        (is (== 47000M (-> cmp :base :amount)))
        (is (== 11280M (-> cmp :liability :amount)))))))

;; ============================================================================
;; §6. Investors' Relief
;; ============================================================================

(deftest investors-relief-rate-and-cap
  (testing "IR-flagged disposal taxed at 10 % up to remaining £1M cap"
    (let [conn (fresh)]
      (record! conn {:external-id "ir-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-trading-company-shares
                     :exemption-claimed #{:uk-investors-relief}
                     :proceeds {:amount 800000M :commodity gbp}
                     :basis    {:amount 0M      :commodity gbp}})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher
                                            :investors-relief-lifetime-claimed 0M}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Post-AEA: AEA applied to STANDARD first (none here) →
        ;; STANDARD is 0, RESIDENTIAL 0, IR-eligible: AEA flows through
        ;; to IR slice since residential/std are zero. Wait — re-read
        ;; the allocator: order is :standard, :residential, :ir, :badr.
        ;; Standard 0, residential 0, IR 800k − AEA 3k = 797k.
        ;; All within £1M cap. Tax @ 10 % = 79,700.
        (is (== 797000M (-> cmp :jurisdiction-specific-codes :ir-claimed-this-period)))
        (is (== 79700M  (-> cmp :liability :amount)))))))

;; ============================================================================
;; §7. Corporate — SSE exemption
;; ============================================================================

(deftest corporate-sse-exempts-disposal
  (testing "SSE-flagged disposal drops out of chargeable pool"
    (let [conn (fresh)]
      (record! conn {:external-id "sse-1"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-trading-company-shares
                     :exemption-claimed #{:uk-sse}
                     :proceeds {:amount 5000000M :commodity gbp}
                     :basis    {:amount 1000000M :commodity gbp}})
      (let [facts (run-provider conn :corporation p2024-25)
            cmp   (component-by-lane facts :uk-corporate-cgt)]
        (is (some? cmp))
        ;; Gain 4M is fully SSE-exempt → CT base addition is 0.
        (is (== 0M (-> cmp :base :amount)))
        (is (== 4000000M (-> cmp :jurisdiction-specific-codes :sse-exempt-amount))
            "SSE-exempt amount recorded for audit")
        (is (= [0M] (-> cmp :jurisdiction-specific-codes :cit-base-additions)))))))

(deftest corporate-net-folds-into-cit-base
  (testing "non-SSE corporate gain folds into CT base via :cit-base-additions"
    (let [conn (fresh)]
      ;; ABC Ltd commercial freehold — note 114 §2.2.
      ;; Proceeds £450,000, indexed cost £300,200 (consumer-supplied,
      ;; already including indexation + disposal costs).
      (record! conn {:external-id "abc-freehold"
                     :acquired-on #inst "2005-04-01"
                     :disposed-on #inst "2024-12-01"
                     :asset-class :uk-other
                     :proceeds {:amount 450000M :commodity gbp}
                     :basis    {:amount 300200M :commodity gbp}})
      (let [facts (run-provider conn :corporation p2024-25)
            cmp   (component-by-lane facts :uk-corporate-cgt)]
        (is (some? cmp))
        ;; Chargeable gain = 450,000 − 300,200 = 149,800.
        (is (== 149800M (-> cmp :base :amount))
            "matches note 114 §2.2 chargeable-gain figure")
        (is (== 0M (-> cmp :liability :amount))
            "no liability at this layer — fold-into-CT-base only")
        (is (= [149800M] (-> cmp :jurisdiction-specific-codes :cit-base-additions)))))))

(deftest corporate-loss-carryforward-applies
  (testing "carry-in loss reduces the corporate net chargeable gain"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-loss-test"
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2024-12-01"
                     :asset-class :uk-other
                     :proceeds {:amount 500000M :commodity gbp}
                     :basis    {:amount 100000M :commodity gbp}})
      (let [facts (run-provider conn :corporation p2024-25
                                {:inputs {:capital-loss-carryforward
                                          {:uk-capital 150000M}}})
            cmp   (component-by-lane facts :uk-corporate-cgt)]
        ;; Gain 400k − carry 150k = 250k.
        (is (== 250000M (-> cmp :base :amount)))
        (is (= [250000M] (-> cmp :jurisdiction-specific-codes :cit-base-additions)))))))

;; ============================================================================
;; §8. Void exclusion
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-11-20"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 200000M :commodity gbp}
                     :basis    {:amount 50000M  :commodity gbp}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2024-25
                                {:tax-unit {:income-band :higher}})]
        (is (empty? (:components facts)))))))

;; ============================================================================
;; §9. Bitemporal rate window — pre-Oct-2024 standard rate is 10/20
;; ============================================================================

(deftest pre-budget-2024-uses-old-standard-rates
  (testing "disposal before 30 Oct 2024 uses the old 10/20 standard rates"
    (let [conn (fresh)
          ;; Period end < 30 Oct 2024 (the new-rate effective-from) so
          ;; `as-of` reads the pre-budget 20 % higher rate.
          p-2024 {:from #inst "2024-04-06" :to #inst "2024-10-29"}]
      (record! conn {:external-id "pre-budget"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2024-09-15"
                     :asset-class :uk-listed-shares
                     :proceeds {:amount 200000M :commodity gbp}
                     :basis    {:amount 50000M  :commodity gbp}})
      (let [facts (run-provider conn :individual p-2024
                                {:tax-unit {:income-band :higher}})
            cmp   (component-by-lane facts :uk-individual-cgt)]
        ;; Gain 150k − AEA 3k = 147k @ 20 % (pre-budget higher rate) = 29,400.
        (is (== 147000M (-> cmp :base :amount)))
        (is (== 29400M  (-> cmp :liability :amount)))))))

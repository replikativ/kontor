(ns kontor.l10n-us.cgt-provider-test
  "Tests for the US CGT provider (ADR-102 + ADR-101, research note 112)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal-source :as ds]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-us.cgt-provider :as us-cgt]
            [kontor.l10n-us.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, US CGT statute, a USD
   commodity, and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:commodity/symbol "USD" :commodity/name "US Dollar"
                       :commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "US"
                       :entity/functional-commodity [:commodity/symbol "USD"]}])
    conn))

(def ^:private usd [:commodity/symbol "USD"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal disposal."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         usd
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity usd}
                :basis           {:amount 0M :commodity usd}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  "Build a provider, call `period-tax-facts`, return the resulting facts."
  [conn kind period & [extra-ctx]]
  (let [source ((case kind :individual disp-source/datahike-source
                      :corporation disp-source/datahike-source) conn)
        provider (case kind
                   :individual  (us-cgt/us-individual-cgt-provider  {:source source})
                   :corporation (us-cgt/us-corporate-cgt-provider   {:source source}))]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- component-by-lane
  "Find the first component whose `:jurisdiction-specific-codes :lane`
   matches the given lane keyword."
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing — DisposalSource, empty case, kind validation
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
          bad (us-cgt/->USCapitalGainsTaxProvider :bogus source :us-irs :USD "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :entity (holdco-eid conn) :period p2026}))))))

;; ============================================================================
;; §2. Short-term — folds into PIT base
;; ============================================================================

(deftest individual-st-folds-into-pit-base
  (testing "a 6-month-held disposal at gain → :st lane → PIT base addition"
    (let [conn (fresh)]
      (record! conn {:external-id "st-1"
                     :acquired-on #inst "2025-12-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 10000M :commodity usd}
                     :basis    {:amount 6000M  :commodity usd}})
      (let [facts (run-provider conn :individual p2026)
            st    (component-by-lane facts :st)]
        (is (some? st) "an :st component exists")
        (is (== 0M (-> st :liability :amount))
            "ST has no standalone liability — flows into PIT")
        (is (= [4000M] (get-in st [:jurisdiction-specific-codes :pit-base-additions]))
            "PIT base receives the +$4 000 ST gain")))))

;; ============================================================================
;; §3. Long-term — own §1(h) progressive bracket schedule
;; ============================================================================

(deftest individual-lt-uses-§1h-bracket-schedule
  (testing "small LT gain (single filer) falls in the 0 % bracket"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-zero"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 30000M :commodity usd}
                     :basis    {:amount 10000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}})
            lt    (component-by-lane facts :lt)]
        (is (some? lt))
        (is (== 20000M (-> lt :base :amount)))
        (is (== 0M (-> lt :liability :amount))
            "$20k LT under the single 0% ceiling ($49 450 in 2026) → $0")
        (is (= :single (:regime lt))))))

  (testing "mid LT gain (single filer) at 15 %"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-mid"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 300000M :commodity usd}
                     :basis    {:amount 100000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}})
            lt    (component-by-lane facts :lt)]
        (is (== 200000M (-> lt :base :amount)))
        ;; 200k LT, single: first 49,450 @ 0%, next 150,550 @ 15% = $22,582.50
        (is (== 22582.5M (-> lt :liability :amount))))))

  (testing "filing-status-conditioned brackets — MFJ has higher thresholds"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-mfj"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 130000M :commodity usd}
                     :basis    {:amount 30000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :mfj}})
            lt    (component-by-lane facts :lt)]
        (is (== 100000M (-> lt :base :amount)))
        ;; MFJ 0% ceiling 98,900 → first 98,900 @ 0% + 1,100 @ 15% = 165
        (is (== 165M (-> lt :liability :amount)))))))

;; ============================================================================
;; §4. §1250 unrecaptured — real-property depreciation at 25 %
;; ============================================================================

(deftest individual-§1250-unrecaptured-at-25pct
  (testing "depreciated real-property LT sale routes to §1250 lane at 25%"
    (let [conn (fresh)]
      (record! conn {:external-id "§1250-1"
                     :acquired-on #inst "2010-06-01"
                     :disposed-on #inst "2026-04-01"
                     :asset-class :us-real-property
                     :proceeds {:amount 1100000M :commodity usd}
                     :basis    {:amount 338462M  :commodity usd}
                     :depreciation-taken {:amount 461538M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}})
            §1250 (component-by-lane facts :§1250-unrecaptured)]
        (is (some? §1250))
        (is (== 761538M (-> §1250 :base :amount))
            "the entire LT gain folds into the §1250 lane (depreciation present)")
        ;; 761,538 × 25% = 190,384.50
        (is (== 190384.5M (-> §1250 :liability :amount)))))))

;; ============================================================================
;; §5. §1245 ordinary recapture — personal property at ordinary rate
;; ============================================================================

(deftest individual-§1245-recapture-ordinary
  (testing "depreciated personal property — depreciation slice goes ordinary"
    (let [conn (fresh)]
      (record! conn {:external-id "§1245-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :us-personal-property-§1245
                     :proceeds {:amount 150000M :commodity usd}
                     :basis    {:amount 40000M  :commodity usd}
                     :depreciation-taken {:amount 60000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}})
            recapture (component-by-lane facts :ordinary-recapture)
            lt        (component-by-lane facts :lt)]
        ;; Gain = 150k − 40k = 110k. Recapture = min(dep=60k, gain=110k) = 60k.
        ;; Residual 50k stays LT.
        (is (some? recapture))
        (is (= [60000M] (get-in recapture [:jurisdiction-specific-codes :pit-base-additions])))
        (is (some? lt))
        (is (== 50000M (-> lt :base :amount)))))))

;; ============================================================================
;; §6. Corporation — net cap gain folds into CIT base
;; ============================================================================

(deftest corporate-net-folds-into-cit-base
  (testing "corp ST+LT net flows into :cit-base-additions (no preferential rate)"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-1"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity usd}
                     :basis    {:amount 100000M :commodity usd}})
      (let [facts (run-provider conn :corporation p2026)
            net   (component-by-lane facts :corp-net-capital)]
        (is (some? net))
        (is (== 400000M (-> net :base :amount)))
        (is (== 0M (-> net :liability :amount)))
        (is (= [400000M] (get-in net [:jurisdiction-specific-codes :cit-base-additions])))))))

(deftest corporate-loss-carryforward-applies
  (testing "carry-in loss reduces the corporate net cap gain"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-2"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity usd}
                     :basis    {:amount 100000M :commodity usd}})
      (let [facts (run-provider conn :corporation p2026
                                {:inputs {:capital-loss-carryforward {:capital 150000M}}})
            net   (component-by-lane facts :corp-net-capital)]
        (is (== 250000M (-> net :base :amount))
            "$400k net − $150k carry-in = $250k taxable")))))

;; ============================================================================
;; §7. §1411 NIIT surtax — fires on standalone CGT tax above MAGI threshold
;; ============================================================================

(deftest individual-niit-fires-above-magi-threshold
  (testing "high-MAGI individual incurs the 3.8% NIIT on net investment income"
    (let [conn (fresh)]
      (record! conn {:external-id "niit-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 300000M :commodity usd}
                     :basis    {:amount 100000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}
                                 :inputs   {:net-investment-income 200000M
                                            :magi 500000M}})
            niit  (component-by-lane facts :niit)]
        (is (some? niit) "NIIT component present")
        ;; NII 200k, MAGI 500k > threshold 200k → excess 300k → taxable = min(NII, excess) = 200k
        ;; 200k × 3.8% = 7,600
        (is (== 7600M (-> niit :liability :amount)))
        (is (= [:lt :§1250-unrecaptured] (:composed-of niit)))))))

(deftest individual-niit-does-not-fire-below-threshold
  (testing "low-MAGI individual — no NIIT component"
    (let [conn (fresh)]
      (record! conn {:external-id "niit-low"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 100000M :commodity usd}
                     :basis    {:amount 50000M  :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}
                                 :inputs   {:net-investment-income 50000M
                                            :magi 150000M}})]
        (is (nil? (component-by-lane facts :niit))
            "MAGI 150k below threshold 200k → no NIIT")))))

;; ============================================================================
;; §8. Loss carryforward — LT lane consumption
;; ============================================================================

(deftest lt-carryforward-loss-offsets-current-gain
  (testing "$50k LT gain offset by $30k LT carry-in → $20k taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "lt-carry"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 60000M :commodity usd}
                     :basis    {:amount 10000M :commodity usd}})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}
                                 :inputs   {:capital-loss-carryforward {:long 30000M}}})
            lt    (component-by-lane facts :lt)]
        (is (== 20000M (-> lt :base :amount))
            "$50k − $30k carry = $20k taxable — fits in single 0% bracket")))))

;; ============================================================================
;; §9. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity usd}
                     :basis    {:amount 50000M  :commodity usd}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2026
                                {:tax-unit {:filing-status :single}})]
        (is (empty? (:components facts)))))))

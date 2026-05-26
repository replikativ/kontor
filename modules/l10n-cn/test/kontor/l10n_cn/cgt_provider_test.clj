(ns kontor.l10n-cn.cgt-provider-test
  "Tests for the CN IIT + EIT CGT providers (ADR-102 + ADR-101,
   research note 133)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-cn.cgt-provider :as cn-cgt]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, CN CGT statute, a CNY
   commodity, and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "CNY" :kontor.commodity/name "Chinese Yuan"
                       :kontor.commodity/precision 2}
                      {:entity/code "HOLDCO" :entity/name "HoldCo"
                       :entity/kind :company :entity/country "CN"
                       :entity/functional-commodity [:kontor.commodity/symbol "CNY"]}])
    conn))

(def ^:private cny [:kontor.commodity/symbol "CNY"])
(def ^:private holdco [:entity/code "HOLDCO"])

(defn- record!
  "Record a minimal CN disposal."
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         cny
                :subject-kind    :participation
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity cny}
                :basis           {:amount 0M :commodity cny}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "HOLDCO"]] (d/db conn)))

(defn- run-iit
  "Build the IIT provider, call `period-tax-facts`."
  [conn period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (cn-cgt/cn-iit-cgt-provider {:source source})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(defn- run-eit
  [conn period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (cn-cgt/cn-eit-cgt-provider {:source source})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (holdco-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- component-by-lane
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(defn- approx==
  "BigDecimal approximate equality within `eps`."
  [^java.math.BigDecimal a ^java.math.BigDecimal b ^java.math.BigDecimal eps]
  (<= (compare (.abs (- a b)) eps) 0))

;; ============================================================================
;; §1. Plumbing — empty source, statute install, asset-class enum closure
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          iit-facts (run-iit conn p2026)
          eit-facts (run-eit conn p2026)]
      (is (empty? (:components iit-facts)))
      (is (empty? (:components eit-facts))))))

(deftest statute-parameters-installed
  (testing "the IIT 20 % flat rate, EIT 25 %, and 10 % WHT loaded as parameters"
    (let [conn (fresh)
          db   (d/db conn)]
      (let [param (fn [c] (d/q '[:find ?v . :in $ ?c :where
                                 [?p :parameter/code ?c]
                                 [?pv :parameter-value/parameter ?p]
                                 [?pv :parameter-value/decimal-value ?v]]
                               db c))]
        (is (= 0.20M (param "CN.IIT.CGT.flat-rate")))
        (is (= 0.25M (param "CN.EIT.standard-rate")))
        (is (= 0.10M (param "CN.EIT.non-resident-wht-rate")))))))

;; ============================================================================
;; §2. IIT — unlisted equity 20 % (note 133 §2.1 worked example)
;; ============================================================================

(deftest iit-unlisted-equity-20pct
  (testing "§2.1: Wang sells unlisted equity — gain 19.8M @ 20 % = 3.96M"
    (let [conn (fresh)]
      (record! conn {:external-id "wang-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2019-03-15"
                     :disposed-on #inst "2026-04-10"
                     :proceeds {:amount 25000000M :commodity cny}
                     :basis    {:amount  5200000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (some? iit) "IIT component present")
        (is (== 19800000M (-> iit :base :amount))
            "net gain 19,800,000 after basis 5.2M (200k broker-fee folded in)")
        ;; 19,800,000 × 20% = 3,960,000
        (is (== 3960000M (-> iit :gross-liability :amount))
            "IIT @ 20 % on net gain = 3,960,000")))))

(deftest iit-unlisted-equity-with-transferee-prepaid
  (testing "transferee-withheld prepaid nets to zero (statutory withholding)"
    (let [conn (fresh)]
      (record! conn {:external-id "wang-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2019-03-15"
                     :disposed-on #inst "2026-04-10"
                     :proceeds {:amount 25000000M :commodity cny}
                     :basis    {:amount  5200000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual}
                            :inputs   {:cn-iit-prepaid 3960000M}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (== 3960000M (-> iit :prepaid :amount)))
        (is (== 0M (-> iit :liability :amount))
            "gross 3.96M − prepaid 3.96M = 0 residual")))))

;; ============================================================================
;; §3. Listed A-share exemption — Caishui [1998] 61 — RETURNS NO COMPONENT
;; ============================================================================

(deftest listed-a-share-residents-no-component
  (testing "listed A-share gain by resident individual → NO tax component"
    (let [conn (fresh)]
      (record! conn {:external-id "a-share-1"
                     :asset-class :cn-listed-a-share
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity cny}
                     :basis    {:amount 100000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual}})]
        ;; The exempt entry is recorded as an exempt-line so a component
        ;; IS produced — but its taxable-gain sum is zero and liability
        ;; is zero. Check there is NO POSITIVE LIABILITY.
        (let [iit (component-by-lane facts :cn-iit-cgt)]
          (is (or (nil? iit)
                  (== 0M (-> iit :gross-liability :amount)))
              "listed A-share by resident individual incurs ZERO CGT")
          (when iit
            (is (== 0M (-> iit :base :amount))
                "the exempt gain does NOT enter the 20 % base")))))))

(deftest listed-a-share-non-resident-NOT-exempt
  (testing "listed A-share gain by a non-resident individual IS taxable"
    (let [conn (fresh)]
      (record! conn {:external-id "a-share-2"
                     :asset-class :cn-listed-a-share
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 500000M :commodity cny}
                     :basis    {:amount 100000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :non-resident-individual}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (some? iit))
        (is (== 400000M (-> iit :base :amount)))
        (is (== 80000M (-> iit :gross-liability :amount))
            "non-resident-individual on A-shares: 400k × 20 % = 80k")))))

(deftest stock-connect-h-share-residents-exempt
  (testing "Stock Connect H-share gain by resident individual → no tax"
    (let [conn (fresh)]
      (record! conn {:external-id "h-share-1"
                     :asset-class :cn-listed-h-share-via-connect
                     :acquired-on #inst "2022-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 200000M :commodity cny}
                     :basis    {:amount 100000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (or (nil? iit) (== 0M (-> iit :gross-liability :amount))))))))

;; ============================================================================
;; §4. 滿五唯一 — BOTH 5y + sole-residence prongs required (§2.2 worked)
;; ============================================================================

(deftest manwuweiyi-both-prongs-exempt
  (testing "§2.2: 8-year residential, sole residence → fully exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "zhang-1"
                     :asset-class :cn-residential
                     :residence?  true
                     :acquired-on #inst "2018-05-15"
                     :disposed-on #inst "2026-06-20"
                     :proceeds {:amount 7500000M :commodity cny}
                     :basis    {:amount 4000000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual
                                       :family-sole-residence? true}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (or (nil? iit) (== 0M (-> iit :gross-liability :amount)))
            "滿五唯一 satisfied — zero CGT")))))

(deftest manwuweiyi-five-year-prong-alone-NOT-exempt
  (testing "5-year hold but NOT sole residence → 20 % on net gain"
    (let [conn (fresh)]
      (record! conn {:external-id "two-homes-1"
                     :asset-class :cn-residential
                     :residence?  true
                     :acquired-on #inst "2018-05-15"
                     :disposed-on #inst "2026-06-20"
                     :proceeds {:amount 7500000M :commodity cny}
                     :basis    {:amount 4000000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual
                                       :family-sole-residence? false}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (some? iit))
        (is (== 3500000M (-> iit :base :amount))
            "missing sole-residence prong → net gain 3.5M is taxable")
        (is (== 700000M (-> iit :gross-liability :amount))
            "3.5M × 20 % = 700,000")))))

(deftest manwuweiyi-sole-residence-prong-alone-NOT-exempt
  (testing "sole residence but only 3 years hold → 20 % on net gain"
    (let [conn (fresh)]
      (record! conn {:external-id "short-hold-1"
                     :asset-class :cn-residential
                     :residence?  true
                     :acquired-on #inst "2023-06-15"
                     :disposed-on #inst "2026-06-20"
                     :proceeds {:amount 7500000M :commodity cny}
                     :basis    {:amount 4000000M :commodity cny}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual
                                       :family-sole-residence? true}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (some? iit))
        (is (== 3500000M (-> iit :base :amount))
            "missing 5-year prong → net gain 3.5M is taxable")))))

;; ============================================================================
;; §5. Real-estate deemed-rate election — Guoshuifa [2006] 108
;; ============================================================================

(deftest real-estate-deemed-rate-election-1pct
  (testing "deemed-rate election at 1 % (Beijing default) on gross proceeds"
    (let [conn (fresh)]
      (record! conn {:external-id "deemed-1"
                     :asset-class :cn-residential
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 6000000M :commodity cny}
                     :basis    {:amount 5000000M :commodity cny}
                     :elective-regime #{:cn-real-estate-deemed-rate}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual
                                       :deemed-rate 0.01M}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        (is (some? iit))
        ;; Deemed-gross: 6,000,000 × 1 % = 60,000 (NOT the 200k that 20%
        ;; on net gain would produce — the election BENEFITS the
        ;; taxpayer when gain is small relative to proceeds)
        (is (== 60000M (-> iit :gross-liability :amount))
            "deemed-rate path: proceeds 6M × 1 % = 60,000")
        ;; The base for deemed-gross is the pre-multiplied liability —
        ;; the 20 % schedule is BYPASSED.
        (is (== 0M (-> iit :base :amount))
            "no net-gain in this disposal → base 0 for the 20 % path")))))

(deftest real-estate-deemed-rate-3pct
  (testing "deemed-rate at the 3 % provincial ceiling"
    (let [conn (fresh)]
      (record! conn {:external-id "deemed-3"
                     :asset-class :cn-non-residential
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 4000000M :commodity cny}
                     :basis    {:amount 3500000M :commodity cny}
                     :elective-regime #{:cn-real-estate-deemed-rate}})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual
                                       :deemed-rate 0.03M}})
            iit   (component-by-lane facts :cn-iit-cgt)]
        ;; 4,000,000 × 3 % = 120,000
        (is (== 120000M (-> iit :gross-liability :amount)))))))

;; ============================================================================
;; §6. EIT corporate — net gain folds into CIT base (no separate CGT)
;; ============================================================================

(deftest eit-normal-folds-into-cit-base
  (testing "corporate equity transfer: full gain → :cit-base-additions"
    (let [conn (fresh)]
      (record! conn {:external-id "corp-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 10000000M :commodity cny}
                     :basis    {:amount  2000000M :commodity cny}})
      (let [facts (run-eit conn p2026
                           {:tax-unit {:tax-residency :resident-corporation}})
            eit   (component-by-lane facts :cn-eit-cgt)]
        (is (some? eit))
        (is (== 8000000M (-> eit :base :amount)))
        (is (= [8000000M] (get-in eit [:jurisdiction-specific-codes :cit-base-additions])))
        (is (= [0M] (get-in eit [:jurisdiction-specific-codes :cit-base-deductions])))))))

;; ============================================================================
;; §7. EIT special-restructuring — equity-paid slice deferred (§2.3)
;; ============================================================================

(deftest eit-special-restructuring-equity-deferred
  (testing "§2.3: GMP-PRC special restructuring, 90 % equity / 10 % cash"
    ;; Per §2.3: total recognisable gain = 600M (= 720M proceeds − 120M
    ;; basis on the 60% × disposed equity). The 90 % equity-paid slice
    ;; → 540M DEFERRED; the 10 % cash slice → 60M CURRENTLY TAXABLE.
    (let [conn (fresh)]
      (record! conn {:external-id "gmp-rt-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-03-15"
                     :proceeds {:amount 720000000M :commodity cny}
                     :basis    {:amount 120000000M :commodity cny}
                     :elective-regime #{:cn-special-restructuring}})
      (let [facts (run-eit conn p2026
                           {:tax-unit {:tax-residency :resident-corporation
                                       :equity-payment-share {"gmp-rt-1" 0.90M}}})
            eit   (component-by-lane facts :cn-eit-cgt)
            adds  (first (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
            deds  (first (get-in eit [:jurisdiction-specific-codes :cit-base-deductions]))]
        (is (some? eit))
        (is (== 60000000M adds)
            "cash slice 10 % × 600M gain = 60M → current taxable")
        (is (== 540000000M deds)
            "equity slice 90 % × 600M gain = 540M → deferred")))))

(deftest eit-intra-group-100pct-fully-deferred
  (testing "Caishui [2014] 109 §3 — 100 % controlled intra-group transfer deferred"
    (let [conn (fresh)]
      (record! conn {:external-id "intra-group-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-04-01"
                     :proceeds {:amount 50000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}
                     :elective-regime #{:cn-intra-group-100pct}})
      (let [facts (run-eit conn p2026
                           {:tax-unit {:tax-residency :resident-corporation}})
            eit   (component-by-lane facts :cn-eit-cgt)
            adds  (first (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
            deds  (first (get-in eit [:jurisdiction-specific-codes :cit-base-deductions]))]
        (is (== 0M adds))
        (is (== 40000000M deds)
            "full gain 40M → deferred via carry-over basis")))))

;; ============================================================================
;; §8. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the IIT provider"
    (let [conn (fresh)]
      (record! conn {:external-id "void-1"
                     :asset-class :cn-unlisted-equity
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 10000000M :commodity cny}
                     :basis    {:amount  2000000M :commodity cny}})
      (disposal/void! conn {:disposal "void-1" :recorded-by-uid "u"})
      (let [facts (run-iit conn p2026
                           {:tax-unit {:tax-residency :resident-individual}})]
        (is (empty? (:components facts))
            "voided disposal → no components")))))

;; ============================================================================
;; §9. LAT-eligible disposals are skipped by the IIT/EIT providers
;; ============================================================================

(deftest lat-eligible-disposals-skipped-by-iit-and-eit
  (testing "developer real-estate disposals are handled by LAT, not IIT/EIT CGT"
    (let [conn (fresh)]
      (record! conn {:external-id "developer-1"
                     :asset-class :cn-developer-real-estate
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-06-15"
                     :proceeds {:amount 100000000M :commodity cny}
                     :basis    {:amount  50000000M :commodity cny}})
      (let [iit-facts (run-iit conn p2026
                               {:tax-unit {:tax-residency :resident-individual}})
            eit-facts (run-eit conn p2026
                               {:tax-unit {:tax-residency :resident-corporation}})]
        (is (empty? (:components iit-facts))
            "developer real-estate → no IIT CGT")
        (is (empty? (:components eit-facts))
            "developer real-estate → no EIT CGT (LAT handles it)")))))
